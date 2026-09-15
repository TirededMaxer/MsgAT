import Foundation
import SQLCipher

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

/// Serial SQLCipher store backed by a random Keychain passphrase.
public final class AppDatabase: @unchecked Sendable {
    public static let shared = AppDatabase()

    private let queue = DispatchQueue(label: "org.msgat.mac.database", qos: .userInitiated)
    private var handle: OpaquePointer?
    private let databaseURL: URL

    private init() {
        do {
            guard let appSupport = FileManager.default.urls(
                for: .applicationSupportDirectory,
                in: .userDomainMask
            ).first else {
                throw CocoaError(.fileNoSuchFile)
            }
            let appDirectory = appSupport.appendingPathComponent("org.msgat.mac", isDirectory: true)
            try FileManager.default.createDirectory(
                at: appDirectory,
                withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700]
            )

            databaseURL = appDirectory.appendingPathComponent("msgat_vault.sqlite")
            let key = KeychainManager.shared.getOrCreateDatabasePassphrase()
            if Self.isPlaintextSQLite(at: databaseURL) {
                try Self.migratePlaintextDatabase(at: databaseURL, key: key)
            }

            try openEncryptedDatabase(key: key)
            try createSchema()
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: databaseURL.path)
        } catch {
            fatalError("Unable to initialize the encrypted MsgAT database: \(error.localizedDescription)")
        }
    }

    /// Test-only construction path that avoids Keychain and user data.
    init(testDatabaseURL: URL, key: Data) throws {
        databaseURL = testDatabaseURL
        if Self.isPlaintextSQLite(at: testDatabaseURL) {
            try Self.migratePlaintextDatabase(at: testDatabaseURL, key: key)
        }
        try openEncryptedDatabase(key: key)
        try createSchema()
    }

    deinit {
        if let handle { sqlite3_close_v2(handle) }
    }

    public func execute(_ sql: String, values: [DatabaseValue] = []) throws {
        try queue.sync {
            guard let handle else { throw Self.databaseError("Execute", handle: nil) }
            try Self.execute(handle: handle, sql: sql, values: values)
        }
    }

    public func query<T>(
        _ sql: String,
        values: [DatabaseValue] = [],
        map: (OpaquePointer) throws -> T
    ) throws -> [T] {
        try queue.sync {
            guard let handle else { throw Self.databaseError("Query", handle: nil) }
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

    public func resetIdentityAndDatabase() throws {
        try queue.sync {
            if let handle {
                try Self.execute(handle: handle, sql: "PRAGMA wal_checkpoint(TRUNCATE)")
                guard sqlite3_close(handle) == SQLITE_OK else { throw Self.databaseError("Close identity database", handle: handle) }
                self.handle = nil
            }
            for suffix in ["", "-wal", "-shm"] {
                let path = databaseURL.path + suffix
                if FileManager.default.fileExists(atPath: path) { try FileManager.default.removeItem(atPath: path) }
            }
            KeychainManager.shared.burnIdentityAndGenerateNew()
            try openEncryptedDatabase(key: KeychainManager.shared.getOrCreateDatabasePassphrase())
            try createSchema()
        }
        NotificationCenter.default.post(name: MsgDao.didChangeNotification, object: nil)
    }

    private func openEncryptedDatabase(key: Data) throws {
        var opened: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(databaseURL.path, &opened, flags, nil) == SQLITE_OK,
              let opened else {
            throw Self.databaseError("Open database", handle: opened)
        }

        let passphrase = Data(key.base64EncodedString().utf8)
        let keyResult = passphrase.withUnsafeBytes { bytes in
            sqlite3_key(opened, bytes.baseAddress, Int32(bytes.count))
        }
        guard keyResult == SQLITE_OK else {
            sqlite3_close_v2(opened)
            throw Self.databaseError("Apply database key", handle: opened)
        }

        do {
            try Self.execute(handle: opened, sql: "PRAGMA cipher_memory_security = ON")
            try Self.execute(handle: opened, sql: "PRAGMA foreign_keys = ON")
            try Self.execute(handle: opened, sql: "PRAGMA journal_mode = WAL")
            try Self.readOne(handle: opened, sql: "SELECT count(*) FROM sqlite_master")
            handle = opened
        } catch {
            sqlite3_close_v2(opened)
            throw error
        }
    }

    // Called during initialization or while reset holds the database queue.
    private func createSchema() throws {
        guard let handle else { throw Self.databaseError("Create schema", handle: nil) }
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
