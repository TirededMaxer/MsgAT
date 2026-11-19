import Foundation
import SQLCipher
import Darwin

public enum DatabaseValue: Sendable {
    case text(String)
    case integer(Int64)
    case null
}

public struct MsgATDatabaseError: LocalizedError, Sendable {
    public let operation: String
    public let detail: String
    public var errorDescription: String? { "\(operation): \(detail)" }
}

private let msgatSQLiteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// Serial SQLCipher store whose data key is wrapped by the local root identity.
public final class AppDatabase: @unchecked Sendable {
    public static let shared = AppDatabase()

    private let queue = DispatchQueue(label: "org.msgat.mac.database", qos: .userInitiated)
    private var handle: OpaquePointer?
    private let databaseURL: URL
    private var testLease: Int32 = -1
    private var initializationFailure: String?
    let storageOwner=UUID().uuidString.lowercased()
    public static var startupFailure: String? {shared.initializationFailure}
    private static let productionURL = RuntimePaths.applicationSupport.appendingPathComponent("msgat_v2_vault.sqlite")
    // Hold the same file lease from identity recovery until the process exits.
    private static let productionLease: Result<Int32, Error> = Result {
        try FileManager.default.createDirectory(at: productionURL.deletingLastPathComponent(),
            withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        return try acquireLease(productionURL)
    }
    static func requireProfileLease() throws { _ = try productionLease.get() }

    private init() {
        databaseURL = Self.productionURL
        do {
            try Self.requireProfileLease()
            if !FileManager.default.fileExists(atPath: databaseURL.path),
               ["-wal","-shm","-journal"].contains(where:{FileManager.default.fileExists(atPath:databaseURL.path+$0)}) {
                throw MsgATDatabaseError(operation:"Open vault",detail:"The encrypted vault file is missing. Recovery files were preserved.")
            }
            var key = try KeychainManager.shared.getOrCreateDatabasePassphrase()
            defer { key.resetBytes(in: key.startIndex..<key.endIndex) }
            if Self.isPlaintextSQLite(at: databaseURL) {
                throw MsgATDatabaseError(operation: "Open V2 vault", detail: "Refusing an unauthenticated plaintext database in the encrypted vault location.")
            }

            try openEncryptedDatabase(key: key)
            try createSchema()
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: databaseURL.path)
        } catch {
            if let handle {sqlite3_close_v2(handle);self.handle=nil}
            initializationFailure = error.localizedDescription
        }
    }

    /// Test-only construction path that avoids Keychain and user data.
    init(testDatabaseURL: URL, key: Data) throws {
        databaseURL = testDatabaseURL
        testLease=try Self.acquireLease(testDatabaseURL)
        do {
        if Self.isPlaintextSQLite(at: testDatabaseURL) {
            try Self.migratePlaintextDatabase(at: testDatabaseURL, key: key)
        }
        try openEncryptedDatabase(key: key)
        try createSchema()
        } catch {
            if let handle {sqlite3_close_v2(handle);self.handle=nil}
            Darwin.close(testLease);testLease = -1;throw error
        }
    }

    deinit {
        if let handle { sqlite3_close_v2(handle) }
        if testLease>=0 {Darwin.close(testLease)}
    }

    func closeForTermination() {
        queue.sync {
            guard let handle else { return }
            sqlite3_close_v2(handle)
            self.handle = nil
        }
    }
    private static func acquireLease(_ url: URL) throws -> Int32 {
        let descriptor=Darwin.open(url.path+".lock",O_RDWR|O_CREAT|O_CLOEXEC|O_NOFOLLOW,0o600)
        guard descriptor>=0 else {throw MsgATDatabaseError(operation:"Open vault",detail:"The encrypted vault could not be locked.")}
        guard fchmod(descriptor,0o600)==0,flock(descriptor,LOCK_EX|LOCK_NB)==0 else {
            Darwin.close(descriptor)
            throw MsgATDatabaseError(operation:"Open vault",detail:"The encrypted vault is already open or could not be locked.")
        }
        return descriptor
    }

    public func execute(_ sql: String, values: [DatabaseValue] = []) throws {
        try queue.sync {
            guard let handle else { throw Self.databaseError("Execute", handle: nil) }
            try Self.execute(handle: handle, sql: sql, values: values)
        }
    }

    /// The closure runs synchronously on the database queue. Never perform network
    /// operations or call the queue-owning public DAO from inside this closure.
    func transaction<T>(notifyChanged: Bool = false, _ operation: (Transaction) throws -> T) throws -> T {
        let result = try queue.sync {
            guard let handle else { throw Self.databaseError("Begin transaction", handle: nil) }
            try Self.execute(handle: handle, sql: "BEGIN IMMEDIATE")
            let transaction = Transaction(handle: handle)
            defer { transaction.active = false }
            do {
                let result = try operation(transaction)
                try Self.execute(handle: handle, sql: "COMMIT")
                return result
            } catch {
                try? Self.execute(handle: handle, sql: "ROLLBACK")
                throw error
            }
        }
        if notifyChanged { NotificationCenter.default.post(name: MsgDao.didChangeNotification, object: nil) }
        return result
    }

    final class Transaction {
        fileprivate var active = true
        private let handle: OpaquePointer
        fileprivate init(handle: OpaquePointer) { self.handle = handle }
        func execute(_ sql: String, values: [DatabaseValue] = []) throws {
            guard active else { throw MsgATDatabaseError(operation: "Transaction", detail: "Transaction is no longer active") }
            try AppDatabase.execute(handle: handle, sql: sql, values: values)
        }
        func query<T>(_ sql: String, values: [DatabaseValue] = [], map: (OpaquePointer) throws -> T) throws -> [T] {
            guard active else { throw MsgATDatabaseError(operation: "Transaction", detail: "Transaction is no longer active") }
            return try AppDatabase.query(handle: handle, sql: sql, values: values, map: map)
        }
    }

    public func query<T>(
        _ sql: String,
        values: [DatabaseValue] = [],
        map: (OpaquePointer) throws -> T
    ) throws -> [T] {
        try queue.sync {
            guard let handle else { throw Self.databaseError("Query", handle: nil) }
            return try Self.query(handle: handle, sql: sql, values: values, map: map)
        }
    }

    /// Reads a snapshot and updates it without allowing another database operation between them.
    public func queryAndExecute<T>(
        _ sql: String,
        updateSQL: String,
        values: [DatabaseValue] = [],
        map: (OpaquePointer) throws -> T
    ) throws -> [T] {
        try queue.sync {
            guard let handle else { throw Self.databaseError("Read and update", handle: nil) }
            try Self.execute(handle: handle, sql: "BEGIN IMMEDIATE")
            do {
                let rows = try Self.query(handle: handle, sql: sql, values: values, map: map)
                if !rows.isEmpty {
                    try Self.execute(handle: handle, sql: updateSQL, values: values)
                }
                try Self.execute(handle: handle, sql: "COMMIT")
                return rows
            } catch {
                try? Self.execute(handle: handle, sql: "ROLLBACK")
                throw error
            }
        }
    }

    private static func query<T>(
        handle: OpaquePointer,
        sql: String,
        values: [DatabaseValue],
        map: (OpaquePointer) throws -> T
    ) throws -> [T] {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK,
              let statement else {
            throw Self.databaseError("Prepare query", handle: handle)
        }
        defer { sqlite3_finalize(statement) }
        try Self.bind(values, to: statement, handle: handle)

        var rows: [T] = []
        while true {
            switch sqlite3_step(statement) {
            case SQLITE_ROW:
                rows.append(try map(statement))
            case SQLITE_DONE:
                return rows
            default:
                throw Self.databaseError("Read query", handle: handle)
            }
        }
    }

    public func clearAllData() throws {
        try queue.sync {
            guard let handle else { throw Self.databaseError("Clear database", handle: nil) }
            try Self.execute(handle: handle, sql: "BEGIN IMMEDIATE")
            do {
                try Self.execute(handle: handle, sql: "DELETE FROM messages")
                try Self.execute(handle: handle, sql: "DELETE FROM conversations")
                try Self.execute(handle: handle, sql: "DELETE FROM friend_requests")
                try Self.execute(handle: handle, sql: "COMMIT")
            } catch {
                try? Self.execute(handle: handle, sql: "ROLLBACK")
                throw error
            }
        }
        NotificationCenter.default.post(name: MsgDao.didChangeNotification, object: nil)
    }

    func reclaimUnusedPages() throws {
        try queue.sync {
            guard let handle else {throw Self.databaseError("Reclaim database pages",handle:nil)}
            let mode = try Self.query(handle:handle,sql:"PRAGMA auto_vacuum",values:[]) {sqlite3_column_int($0,0)}.first
            guard mode == 2 else {return}
            let free = try Self.query(handle:handle,sql:"PRAGMA freelist_count",values:[]) {sqlite3_column_int64($0,0)}.first ?? 0
            guard free >= 128 else {return}
            _ = try Self.query(handle:handle,sql:"PRAGMA incremental_vacuum(64)",values:[]) {_ in ()}
            _ = try Self.query(handle:handle,sql:"PRAGMA wal_checkpoint(PASSIVE)",values:[]) {_ in ()}
        }
    }

    private func openEncryptedDatabase(key: Data) throws {
        var opened: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(databaseURL.path, &opened, flags, nil) == SQLITE_OK,
              let opened else {
            let error = Self.databaseError("Open database", handle: opened)
            if let opened { sqlite3_close_v2(opened) }
            throw error
        }

        let keyResult: Int32
        do {
            var passphrase = key.base64EncodedData()
            defer { passphrase.resetBytes(in: passphrase.startIndex..<passphrase.endIndex) }
            keyResult = passphrase.withUnsafeBytes { bytes in
                sqlite3_key(opened, bytes.baseAddress, Int32(bytes.count))
            }
        }
        guard keyResult == SQLITE_OK else {
            let error = Self.databaseError("Apply database key", handle: opened)
            sqlite3_close_v2(opened)
            throw error
        }

        do {
            try Self.execute(handle: opened, sql: "PRAGMA cipher_memory_security = ON")
            try Self.execute(handle: opened, sql: "PRAGMA temp_store = MEMORY")
            try Self.execute(handle: opened, sql: "PRAGMA foreign_keys = ON")
            let schemaEntries = try Self.query(handle:opened,sql:"SELECT count(*) FROM sqlite_master",values:[]) {
                sqlite3_column_int64($0,0)
            }.first
            if schemaEntries == 0 {try Self.execute(handle:opened,sql:"PRAGMA auto_vacuum=INCREMENTAL")}
            try Self.execute(handle: opened, sql: "PRAGMA journal_mode = WAL")
            try Self.execute(handle: opened, sql: "PRAGMA synchronous = FULL")
            try Self.execute(handle: opened, sql: "PRAGMA fullfsync = ON")
            try Self.execute(handle: opened, sql: "PRAGMA checkpoint_fullfsync = ON")
            try Self.readOne(handle: opened, sql: "PRAGMA journal_size_limit=4194304")
            handle = opened
        } catch {
            sqlite3_close_v2(opened)
            throw error
        }
    }

    // Called during initialization or while reset holds the database queue.
    private func createSchema() throws {
        guard let handle else { throw Self.databaseError("Create schema", handle: nil) }
        try Self.execute(handle:handle,sql:"BEGIN IMMEDIATE")
        do {
        try Self.execute(handle: handle, sql: "CREATE TABLE IF NOT EXISTS contact_states (uuid TEXT PRIMARY KEY NOT NULL, outgoing INTEGER NOT NULL DEFAULT 0, incoming INTEGER NOT NULL DEFAULT 0, accepted INTEGER NOT NULL DEFAULT 0, blocked INTEGER NOT NULL DEFAULT 0)")
        try Self.execute(handle:handle,sql:"CREATE TABLE IF NOT EXISTS contact_links(uuid TEXT PRIMARY KEY NOT NULL,state_json TEXT NOT NULL)")
        try Self.execute(handle:handle,sql:"CREATE TABLE IF NOT EXISTS contact_verifications(uuid TEXT PRIMARY KEY NOT NULL,display TEXT NOT NULL,verified_at INTEGER NOT NULL)")
        try Self.execute(handle: handle, sql: """
            CREATE TABLE IF NOT EXISTS attachment_export_preferences (
                id INTEGER PRIMARY KEY CHECK(id=1),
                folder TEXT NOT NULL
            )
            """)
        try Self.execute(handle: handle, sql: """
            CREATE TABLE IF NOT EXISTS conversations (
                uuid TEXT PRIMARY KEY NOT NULL,
                alias TEXT NOT NULL,
                avatarPath TEXT NOT NULL DEFAULT '',
                peerPublicKey TEXT NOT NULL DEFAULT ''
            )
            """)
        try Self.execute(handle: handle, sql: """
            CREATE TABLE IF NOT EXISTS messages (
                messageId TEXT PRIMARY KEY NOT NULL,
                conversationUuid TEXT NOT NULL,
                isMine INTEGER NOT NULL,
                content TEXT NOT NULL,
                ext TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                isRead INTEGER NOT NULL DEFAULT 0,
                replyToId TEXT,
                replyToSender TEXT,
                replyToContent TEXT,
                status INTEGER NOT NULL DEFAULT 1,
                isRevoked INTEGER NOT NULL DEFAULT 0,
                isEdited INTEGER NOT NULL DEFAULT 0
            )
            """)
        try Self.execute(handle: handle, sql: "CREATE INDEX IF NOT EXISTS index_messages_conversationUuid_timestamp ON messages(conversationUuid, timestamp)")
        try Self.execute(handle: handle, sql: "CREATE INDEX IF NOT EXISTS index_messages_isRead ON messages(isRead)")
        try Self.execute(handle: handle, sql: """
            CREATE TABLE IF NOT EXISTS friend_requests (
                senderUuid TEXT PRIMARY KEY NOT NULL,
                nickname TEXT NOT NULL,
                avatarPath TEXT NOT NULL DEFAULT '',
                peerPublicKey TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL
            )
            """)
        let pendingColumns=Set(try Self.query(handle:handle,sql:"PRAGMA table_info(protocol_pending_events)",values:[],map:{String(cString:sqlite3_column_text($0,1))}))
        if pendingColumns.contains("created_at"),!pendingColumns.contains("held"){try Self.execute(handle:handle,sql:"ALTER TABLE protocol_pending_events ADD COLUMN held INTEGER NOT NULL DEFAULT 0 CHECK(held IN (0,1))")}
        for sql in SignalProtocolStore.schema {try Self.execute(handle:handle,sql:sql)}
        let notificationColumns = try Self.query(handle: handle, sql: "PRAGMA table_info(notification_targets)", values: []) {
            String(cString: sqlite3_column_text($0, 1))
        }
        if !notificationColumns.contains("is_pin") {
            try Self.execute(handle: handle, sql: "ALTER TABLE notification_targets ADD COLUMN is_pin INTEGER NOT NULL DEFAULT 0 CHECK(is_pin IN (0,1))")
            try Self.execute(handle: handle, sql: "DELETE FROM notification_targets")
        }
        let draftColumns = try Self.query(handle: handle, sql: "PRAGMA table_info(conversation_drafts)", values: []) {
            String(cString: sqlite3_column_text($0, 1))
        }
        if !draftColumns.contains("reply_quote") {
            try Self.execute(handle: handle, sql: "ALTER TABLE conversation_drafts ADD COLUMN reply_quote TEXT")
        }
        let previewColumns = try Self.query(handle: handle, sql: "PRAGMA table_info(message_previews)", values: []) { row in
            String(cString: sqlite3_column_text(row, 1))
        }
        for column in ["above_text", "compact_image"] where !previewColumns.contains(column) {
            try Self.execute(handle: handle, sql: "ALTER TABLE message_previews ADD COLUMN \(column) INTEGER NOT NULL DEFAULT 0 CHECK(\(column) IN (0,1))")
        }
        try Self.execute(handle:handle,sql:"UPDATE protocol_pending_events SET held=1 WHERE held=0 AND EXISTS(SELECT 1 FROM scheduled_sends s WHERE s.event_id=protocol_pending_events.event_id)")
        let privacyColumns=Set(try Self.query(handle:handle,sql:"PRAGMA table_info(privacy_preferences)",values:[],map:{String(cString:sqlite3_column_text($0,1))}))
        for column in ["share_online","share_typing","show_notification_previews","app_lock_enabled"] where !privacyColumns.contains(column) {try Self.execute(handle:handle,sql:"ALTER TABLE privacy_preferences ADD COLUMN \(column) INTEGER NOT NULL DEFAULT 0 CHECK(\(column) IN (0,1))")}
        for column in ["message_alerts_enabled", "message_alert_sound"] where !privacyColumns.contains(column) {
            try Self.execute(handle: handle, sql: "ALTER TABLE privacy_preferences ADD COLUMN \(column) INTEGER NOT NULL DEFAULT 1 CHECK(\(column) IN (0,1))")
        }
        if !privacyColumns.contains("auto_download_attachments") {
            try Self.execute(handle: handle, sql: "ALTER TABLE privacy_preferences ADD COLUMN auto_download_attachments INTEGER NOT NULL DEFAULT 1 CHECK(auto_download_attachments IN (0,1))")
        }
        if !privacyColumns.contains("auto_download_limit_bytes") {
            try Self.execute(handle: handle, sql: "ALTER TABLE privacy_preferences ADD COLUMN auto_download_limit_bytes INTEGER NOT NULL DEFAULT 0 CHECK(auto_download_limit_bytes>=0)")
        }
        let transferColumns = try Self.query(handle: handle, sql: "PRAGMA table_info(object_transfers)", values: [],
                                             map: { String(cString: sqlite3_column_text($0, 1)) })
        if !transferColumns.contains("download_requested") {
            try Self.execute(handle: handle, sql: "ALTER TABLE object_transfers ADD COLUMN download_requested INTEGER NOT NULL DEFAULT 0 CHECK(download_requested IN (0,1))")
        }
        if !transferColumns.contains("download_paused") {
            try Self.execute(handle: handle, sql: "ALTER TABLE object_transfers ADD COLUMN download_paused INTEGER NOT NULL DEFAULT 0 CHECK(download_paused IN (0,1))")
        }
        if !transferColumns.contains("download_unavailable") {
            try Self.execute(handle: handle, sql: "ALTER TABLE object_transfers ADD COLUMN download_unavailable INTEGER NOT NULL DEFAULT 0 CHECK(download_unavailable IN (0,1))")
        }
        let legacy=try Self.query(handle:handle,sql:"SELECT 1 FROM sqlite_master WHERE type='table' AND name='media_transfers'",values:[],map:{_ in true})
        if !legacy.isEmpty {
            guard try Self.query(handle:handle,sql:"SELECT 1 FROM media_transfers t LEFT JOIN messages m ON m.messageId=t.message_id LEFT JOIN media_objects o ON o.object_id=t.object_id WHERE m.messageId IS NULL OR o.object_id IS NULL",values:[],map:{_ in true}).isEmpty else {throw MsgATDatabaseError(operation:"Upgrade transfers",detail:"Stored transfer state is inconsistent. Existing data was retained.")}
            try Self.execute(handle:handle,sql:"INSERT OR IGNORE INTO object_transfers(event_id,object_id,owner_peer,owner_kind,created_at,direction,attempts,next_retry,remote_expiry,lease_session) SELECT t.message_id,t.object_id,m.conversationUuid,'message',m.timestamp,t.direction,t.attempts,t.next_retry,t.remote_expiry,t.lease_session FROM media_transfers t JOIN messages m ON m.messageId=t.message_id JOIN media_objects o ON o.object_id=t.object_id")
            try Self.execute(handle:handle,sql:"DROP TABLE media_transfers")
        }
        let openedAt = Int64(Date().timeIntervalSince1970 * 1_000)
        let changeColumns = try Self.query(handle: handle, sql: "PRAGMA table_info(message_changes)", values: [],
                                           map: { String(cString: sqlite3_column_text($0, 1)) })
        if !changeColumns.contains("pending_since") {
            try Self.execute(handle: handle, sql: "ALTER TABLE message_changes ADD COLUMN pending_since INTEGER NOT NULL DEFAULT 0")
            try Self.execute(handle: handle, sql: "UPDATE message_changes SET pending_since=? WHERE pending=1", values: [.integer(openedAt)])
        }
        try Self.execute(handle: handle, sql: "DELETE FROM protocol_incoming_dispositions WHERE expires_at<=?", values: [.integer(openedAt)])
        try Self.execute(handle: handle, sql: "DELETE FROM message_changes WHERE pending=1 AND pending_since<=?", values: [.integer(openedAt - 15 * 86_400_000)])
        try Self.execute(handle: handle, sql: MessageExpirationStore.markDueRetentionSQL, values: [.integer(openedAt), .integer(-1)])
        try Self.execute(handle: handle, sql: """
            DELETE FROM messages WHERE messageId IN (
                SELECT message_id FROM message_expiration WHERE expires_at<=?)
            """, values: [.integer(openedAt)])
        try Self.execute(handle:handle,sql:"COMMIT")
        } catch {try? Self.execute(handle:handle,sql:"ROLLBACK");throw error}
    }

    private static func execute(
        handle: OpaquePointer,
        sql: String,
        values: [DatabaseValue] = []
    ) throws {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK,
              let statement else {
            throw databaseError("Prepare statement", handle: handle)
        }
        defer { sqlite3_finalize(statement) }
        try bind(values, to: statement, handle: handle)
        while true {
            switch sqlite3_step(statement) {
            case SQLITE_ROW:
                continue
            case SQLITE_DONE:
                return
            default:
                throw databaseError("Execute statement", handle: handle)
            }
        }
    }

    private static func readOne(handle: OpaquePointer, sql: String) throws {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK,
              let statement else {
            throw databaseError("Prepare validation query", handle: handle)
        }
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_ROW else {
            throw databaseError("Validate encrypted database", handle: handle)
        }
    }

    private static func bind(
        _ values: [DatabaseValue],
        to statement: OpaquePointer,
        handle: OpaquePointer
    ) throws {
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            let result: Int32
            switch value {
            case .text(let text):
                result = sqlite3_bind_text(statement, index, text, -1, msgatSQLiteTransient)
            case .integer(let integer):
                result = sqlite3_bind_int64(statement, index, integer)
            case .null:
                result = sqlite3_bind_null(statement, index)
            }
            guard result == SQLITE_OK else {
                throw databaseError("Bind statement", handle: handle)
            }
        }
    }

    private static func isPlaintextSQLite(at url: URL) -> Bool {
        guard let data = try? Data(contentsOf: url, options: [.mappedIfSafe]), data.count >= 16 else {
            return false
        }
        return data.prefix(16) == Data("SQLite format 3\0".utf8)
    }

    private static func migratePlaintextDatabase(at url: URL, key: Data) throws {
        let encryptedURL = url.deletingLastPathComponent()
            .appendingPathComponent(".msgat_vault.encrypted-\(UUID().uuidString)")
        var plaintext: OpaquePointer?
        guard sqlite3_open_v2(url.path, &plaintext, SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK,
              let sourceHandle = plaintext else {
            throw databaseError("Open plaintext database for migration", handle: plaintext)
        }
        defer { if let plaintext { sqlite3_close_v2(plaintext) } }

        do {
            try readOne(handle: sourceHandle, sql: "PRAGMA wal_checkpoint(TRUNCATE)")
            guard FileManager.default.createFile(
                atPath: encryptedURL.path,
                contents: nil,
                attributes: [.posixPermissions: 0o600]
            ) else {
                throw MsgATDatabaseError(operation: "Create encrypted migration file", detail: encryptedURL.path)
            }
            let escapedPath = encryptedURL.path.replacingOccurrences(of: "'", with: "''")
            let passphrase = key.base64EncodedString().replacingOccurrences(of: "'", with: "''")
            try execute(
                handle: sourceHandle,
                sql: "ATTACH DATABASE '\(escapedPath)' AS encrypted KEY '\(passphrase)'"
            )
            try readOne(handle: sourceHandle, sql: "SELECT sqlcipher_export('encrypted')")
            try execute(handle: sourceHandle, sql: "DETACH DATABASE encrypted")
            sqlite3_close_v2(sourceHandle)
            plaintext = nil

            var encrypted: OpaquePointer?
            guard sqlite3_open_v2(
                encryptedURL.path,
                &encrypted,
                SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX,
                nil
            ) == SQLITE_OK, let encryptedHandle = encrypted else {
                throw databaseError("Open encrypted migration database", handle: encrypted)
            }
            defer { if let encrypted { sqlite3_close_v2(encrypted) } }
            let passphraseData = Data(key.base64EncodedString().utf8)
            let keyResult = passphraseData.withUnsafeBytes { bytes in
                sqlite3_key(encryptedHandle, bytes.baseAddress, Int32(bytes.count))
            }
            guard keyResult == SQLITE_OK,
                  try readText(handle: encryptedHandle, sql: "PRAGMA integrity_check") == "ok" else {
                throw databaseError("Validate encrypted migration database", handle: encryptedHandle)
            }
            sqlite3_close_v2(encryptedHandle)
            encrypted = nil
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: encryptedURL.path)
            _ = try FileManager.default.replaceItemAt(url, withItemAt: encryptedURL)
            try? FileManager.default.removeItem(atPath: url.path + "-wal")
            try? FileManager.default.removeItem(atPath: url.path + "-shm")
        } catch {
            try? FileManager.default.removeItem(at: encryptedURL)
            throw error
        }
    }

    private static func readText(handle: OpaquePointer, sql: String) throws -> String {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK,
              let statement else {
            throw databaseError("Prepare text query", handle: handle)
        }
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_ROW,
              let value = sqlite3_column_text(statement, 0) else {
            throw databaseError("Read text query", handle: handle)
        }
        return String(cString: value)
    }

    private static func databaseError(_ operation: String, handle: OpaquePointer?) -> MsgATDatabaseError {
        let detail = handle.flatMap { sqlite3_errmsg($0) }.map(String.init(cString:)) ?? "Database is unavailable"
        return MsgATDatabaseError(operation: operation, detail: detail)
    }
}

@inline(__always)
func databaseText(_ statement: OpaquePointer, _ column: Int32) -> String {
    guard let value = sqlite3_column_text(statement, column) else { return "" }
    return String(cString: value)
}

@inline(__always)
func databaseOptionalText(_ statement: OpaquePointer, _ column: Int32) -> String? {
    guard sqlite3_column_type(statement, column) != SQLITE_NULL else { return nil }
    return databaseText(statement, column)
}
