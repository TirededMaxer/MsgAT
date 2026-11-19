// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

enum AttachmentCacheCategory: String, CaseIterable, Sendable {
    case photos, videos, audio, files
    case linkPreviews = "link_previews"
}

struct AttachmentCacheUsage: Sendable {
    let bytesByCategory: [AttachmentCacheCategory: Int64]
    let pendingBytes: Int64
    let automaticLimitBytes: Int64
    let automaticBudgetUsedBytes: Int64
    var bytes: Int64 { bytesByCategory.values.reduce(0, +) }
    var automaticBudgetRemainingBytes: Int64 { max(0, automaticLimitBytes - automaticBudgetUsedBytes) }
}

final class EncryptedObjectLifecycle: @unchecked Sendable {
    private let directory: URL
    private var owner: String {AppDatabase.shared.storageOwner}
    private let maintenance=NSLock()
    private var recovered=false
    private var sweptLegacy=false
    private var nextPageReclaim: UInt64 = 0
    private var now: Int64 {Int64(Date().timeIntervalSince1970*1000)}
    init(directory: URL){self.directory=directory}
    func pinTransfer(id: String, direction: Int, eligible: (AppDatabase.Transaction) throws -> Bool) throws -> (() -> Void)? {
        let pinned=try AppDatabase.shared.transaction {db in
            guard try eligible(db) else { return false }
            try db.execute("""
                INSERT INTO object_transfer_pins(object_id,direction,owner) VALUES(?,?,?)
                ON CONFLICT(object_id,direction) DO UPDATE SET owner=excluded.owner
                WHERE object_transfer_pins.owner<>excluded.owner
                """, values: [.text(id), .integer(Int64(direction)), .text(self.owner)])
            return try db.query("SELECT changes()", map: { sqlite3_column_int($0, 0) != 0 }).first ?? false
        }
        guard pinned else {return nil}
        return {try? AppDatabase.shared.execute("DELETE FROM object_transfer_pins WHERE object_id=? AND direction=? AND owner=?",values:[.text(id),.integer(Int64(direction)),.text(self.owner)])}
    }
    func stage(_ id: String) throws {
        guard id.range(of:"^[a-f0-9]{64}$",options:.regularExpression) != nil else {throw SecurityCore.Failure.invalidResponse}
        try AppDatabase.shared.transaction {db in
            guard try db.query("SELECT 1 FROM media_objects WHERE object_id=?",values:[.text(id)],map:{_ in true}).isEmpty else {throw SecurityCore.Failure.operation("Encrypted object identity collision.")}
            try db.execute("INSERT INTO media_staging(object_id,owner,created_at) VALUES(?,?,?)",values:[.text(id),.text(self.owner),.integer(self.now)])
        }
    }
    func discard(_ id: String) throws {
        guard id.range(of:"^[a-f0-9]{64}$",options:.regularExpression) != nil else {throw SecurityCore.Failure.invalidResponse}
        do {try AppDatabase.shared.transaction {db in
            if try db.query("SELECT 1 FROM media_objects WHERE object_id=? UNION ALL SELECT 1 FROM media_object_references WHERE object_id=?",values:[.text(id),.text(id)],map:{_ in true}).isEmpty {
                _=try SecurityCore.call(["operation":"remove_object_files","directory":self.directory.path,"object_id":id])
                try db.execute("DELETE FROM media_staging WHERE object_id=? AND owner=?",values:[.text(id),.text(self.owner)])
                try db.execute("DELETE FROM media_garbage WHERE object_id=?",values:[.text(id)])
            }
        }} catch {try AppDatabase.shared.transaction {db in
            try db.execute("INSERT OR IGNORE INTO media_garbage(object_id,attempts,next_retry) VALUES(?,1,?)",values:[.text(id),.integer(self.now+30000)])
            try db.execute("DELETE FROM media_staging WHERE object_id=? AND owner=?",values:[.text(id),.text(self.owner)])
        }}
    }
    func collect() throws -> Int {
        maintenance.lock();defer {maintenance.unlock()}
        if !recovered {
            try AppDatabase.shared.transaction {db in
                try db.execute("INSERT OR IGNORE INTO media_garbage(object_id) SELECT object_id FROM media_staging WHERE owner<>?",values:[.text(self.owner)])
                try db.execute("DELETE FROM media_staging WHERE owner<>?",values:[.text(self.owner)])
                try db.execute("DELETE FROM object_transfer_pins WHERE owner<>?",values:[.text(self.owner)])
            };recovered=true
        }
        if !sweptLegacy {
            let removed=try AppDatabase.shared.transaction {db in
                try db.execute("DELETE FROM media_objects WHERE object_id IN (SELECT o.object_id FROM media_objects o WHERE NOT EXISTS(SELECT 1 FROM media_object_references r WHERE r.object_id=o.object_id) AND NOT EXISTS(SELECT 1 FROM media_staging s WHERE s.object_id=o.object_id) LIMIT 64)")
                return try db.query("SELECT changes()",values:[],map:{sqlite3_column_int($0,0)}).first ?? 0
            };sweptLegacy=removed<64
        }
        let cache = try AppDatabase.shared.query("""
            SELECT object_id,attempts FROM media_cache_evictions
            WHERE file_pending=1 AND next_retry<=? ORDER BY next_retry,object_id LIMIT 64
            """, values: [.integer(now)]) {
            (String(cString: sqlite3_column_text($0, 0)), Int(sqlite3_column_int($0, 1)))
        }
        for (id, attempts) in cache { try evictOne(id, attempts: attempts) }
        let jobs=try AppDatabase.shared.query("SELECT object_id,attempts FROM media_garbage WHERE next_retry<=? ORDER BY next_retry,object_id LIMIT 64",values:[.integer(now)]) {
            (String(cString:sqlite3_column_text($0,0)),Int(sqlite3_column_int($0,1)))
        }
        for (id,attempts) in jobs {try collectOne(id,attempts:attempts)}
        let elapsed = DispatchTime.now().uptimeNanoseconds
        if elapsed >= nextPageReclaim {
            nextPageReclaim = elapsed + 30_000_000_000
            try? AppDatabase.shared.reclaimUnusedPages()
        }
        return cache.count + jobs.count
    }
    private func evictOne(_ id: String, attempts: Int) throws {
        do {
            try AppDatabase.shared.transaction { db in
                guard try !db.query("SELECT 1 FROM media_cache_evictions WHERE object_id=? AND file_pending=1",
                                    values: [.text(id)], map: { _ in true }).isEmpty else { return }
                _ = try SecurityCore.call(["operation": "remove_object_files", "directory": directory.path, "object_id": id])
                try db.execute("UPDATE media_cache_evictions SET file_pending=0,attempts=0,next_retry=0 WHERE object_id=?",
                               values: [.text(id)])
            }
        } catch {
            let delay = min(Int64(300_000), Int64(30_000) * (Int64(1) << min(max(attempts, 0), 4)))
            try AppDatabase.shared.execute("UPDATE media_cache_evictions SET attempts=MIN(attempts+1,16),next_retry=? WHERE object_id=?",
                                           values: [.integer(now + delay), .text(id)])
        }
    }
    private func collectOne(_ id: String,attempts: Int) throws {
        do {try AppDatabase.shared.transaction {db in
            guard try db.query("SELECT 1 FROM media_staging WHERE object_id=?",values:[.text(id)],map:{_ in true}).isEmpty else {return}
            if try db.query("SELECT 1 FROM media_objects WHERE object_id=? UNION ALL SELECT 1 FROM media_object_references WHERE object_id=?",values:[.text(id),.text(id)],map:{_ in true}).isEmpty {
                _=try SecurityCore.call(["operation":"remove_object_files","directory":self.directory.path,"object_id":id])
            }
            try db.execute("DELETE FROM media_garbage WHERE object_id=?",values:[.text(id)])
        }} catch {
            let delay=min(Int64(300000),Int64(30000)*(Int64(1)<<min(max(attempts,0),4)))
            try AppDatabase.shared.execute("UPDATE media_garbage SET attempts=MIN(attempts+1,16),next_retry=? WHERE object_id=?",values:[.integer(now+delay),.text(id)])
        }
    }
    private static let cacheSources = """
        SELECT message_id,object_id,0 AS is_preview FROM message_attachments
        UNION ALL SELECT owner_id AS message_id,image_object AS object_id,1 AS is_preview
          FROM message_previews WHERE owner_kind='message' AND image_object IS NOT NULL
        """
    private static let receivedCache = """
            o.local_state=1
            AND EXISTS(SELECT 1 FROM (\(cacheSources)) a JOIN messages m ON m.messageId=a.message_id
                WHERE a.object_id=o.object_id AND m.isMine=0 AND m.isRevoked=0)
            AND NOT EXISTS(SELECT 1 FROM (\(cacheSources)) a JOIN messages m ON m.messageId=a.message_id
                WHERE a.object_id=o.object_id AND m.isMine=1)
        """
    private static let cacheCandidate = """
            \(receivedCache)
            AND NOT EXISTS(SELECT 1 FROM media_cache_evictions e WHERE e.object_id=o.object_id)
            AND NOT EXISTS(SELECT 1 FROM media_staging s WHERE s.object_id=o.object_id)
            AND (SELECT COUNT(*) FROM media_object_references r WHERE r.object_id=o.object_id)=
                (SELECT COUNT(*) FROM (\(cacheSources)) a WHERE a.object_id=o.object_id)
        """
    private static let cachedObjectBytes = """
        json_extract(o.manifest_json,'$.cipher_size') +
        (SELECT COALESCE(SUM(json_extract(preview.manifest_json,'$.cipher_size')),0)
         FROM media_thumbnail_cache t JOIN media_objects preview ON preview.object_id=t.thumbnail_object
         WHERE t.object_id=o.object_id AND preview.local_state=1)
        """
    private static let categorizedCache = """
        SELECT o.object_id,\(cachedObjectBytes) AS bytes,
            CASE
                WHEN NOT EXISTS(SELECT 1 FROM message_attachments a WHERE a.object_id=o.object_id) THEN 'link_previews'
                WHEN json_extract(o.manifest_json,'$.mime') LIKE 'image/%' THEN 'photos'
                WHEN json_extract(o.manifest_json,'$.mime') LIKE 'video/%' THEN 'videos'
                WHEN json_extract(o.manifest_json,'$.mime') LIKE 'audio/%' THEN 'audio'
                ELSE 'files'
            END AS category
        FROM media_objects o WHERE \(cacheCandidate)
        """
    static let automaticLimits: [Int64] = [0, 512_000_000, 1_000_000_000, 2_000_000_000, 5_000_000_000]
    private static let pendingCacheBytes = """
        (SELECT COALESCE(SUM(json_extract(p.manifest_json,'$.cipher_size')),0)
         FROM media_cache_evictions e JOIN media_objects p ON p.object_id=e.object_id WHERE e.file_pending=1)
        """
    private static let receivedCacheBytes = """
        (SELECT COALESCE(SUM(\(cachedObjectBytes)),0)
         FROM media_objects o WHERE \(receivedCache))
        """
    private static let downloadReservations = """
        SELECT pin.object_id,json_extract(reserved.manifest_json,'$.cipher_size') AS bytes
        FROM object_transfer_pins pin JOIN media_objects reserved ON reserved.object_id=pin.object_id
        WHERE pin.direction=1 AND reserved.local_state=0
          AND NOT EXISTS(SELECT 1 FROM media_cache_evictions e
                         WHERE e.object_id=pin.object_id AND e.file_pending=1)
          AND EXISTS(SELECT 1 FROM object_transfers active
                     WHERE active.object_id=pin.object_id AND active.direction=1
                       AND active.owner_kind='message')
        """
    static let automaticDownloadBudget = """
        CASE
            WHEN t.direction=0 OR t.download_requested=1 OR t.owner_kind<>'message' THEN 1
            WHEN (SELECT auto_download_limit_bytes FROM privacy_preferences WHERE id=1)=0 THEN 1
            ELSE json_extract(o.manifest_json,'$.cipher_size') <=
                (SELECT auto_download_limit_bytes FROM privacy_preferences WHERE id=1)
                - \(receivedCacheBytes)
                - \(pendingCacheBytes)
                - (SELECT COALESCE(SUM(bytes),0) FROM (\(downloadReservations)) reserved
                   WHERE reserved.object_id<>o.object_id)
        END
        """

    static func cacheUsage(_ db: AppDatabase.Transaction) throws -> AttachmentCacheUsage {
        let rows = try db.query("SELECT category,SUM(bytes) FROM (\(categorizedCache)) GROUP BY category") { row in
            let key = String(cString: sqlite3_column_text(row, 0))
            guard let category = AttachmentCacheCategory(rawValue: key) else { throw SecurityCore.Failure.invalidResponse }
            return (category, sqlite3_column_int64(row, 1))
        }
        let bytesByCategory = Dictionary(uniqueKeysWithValues: rows)
        return try db.query("""
            SELECT \(pendingCacheBytes),auto_download_limit_bytes,
                   CASE WHEN auto_download_limit_bytes>0 THEN \(receivedCacheBytes)+\(pendingCacheBytes)+
                       (SELECT COALESCE(SUM(bytes),0) FROM (\(downloadReservations))) ELSE 0 END
            FROM privacy_preferences WHERE id=1
            """) { row in
            AttachmentCacheUsage(bytesByCategory: bytesByCategory,
                                 pendingBytes: sqlite3_column_int64(row, 0),
                                 automaticLimitBytes: sqlite3_column_int64(row, 1),
                                 automaticBudgetUsedBytes: sqlite3_column_int64(row, 2))
        }.first ?? AttachmentCacheUsage(bytesByCategory: bytesByCategory, pendingBytes: 0,
                                       automaticLimitBytes: 0, automaticBudgetUsedBytes: 0)
    }

    static func queueCacheReplacement(_ db: AppDatabase.Transaction, peer: String, messageID: String, now: Int64,
                                      previewOnly: Bool = false) throws -> Bool {
        guard let id = try db.query("""
            SELECT o.object_id FROM media_objects o
            JOIN (\(cacheSources)) a ON a.object_id=o.object_id
            JOIN messages m ON m.messageId=a.message_id
            JOIN contact_states c ON c.uuid=m.conversationUuid
            WHERE m.messageId=? AND m.conversationUuid=? AND a.is_preview=? AND m.isMine=0 AND m.isRevoked=0
              AND c.accepted=1 AND c.blocked=0 AND \(cacheCandidate)
              AND NOT EXISTS(SELECT 1 FROM object_transfer_pins p WHERE p.object_id=o.object_id)
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?)
            """, values: [.text(messageID), .text(peer), .integer(previewOnly ? 1 : 0), .integer(now)],
            map: { String(cString: sqlite3_column_text($0, 0)) }).first else { return false }
        try db.execute("INSERT INTO media_cache_evictions(object_id) VALUES(?)", values: [.text(id)])
        try db.execute("UPDATE media_objects SET local_state=0 WHERE object_id=?", values: [.text(id)])
        try db.execute("""
            INSERT OR IGNORE INTO object_transfers(event_id,object_id,direction,owner_peer,owner_kind,created_at)
            SELECT m.messageId,a.object_id,1,m.conversationUuid,'message',m.timestamp
            FROM (\(cacheSources)) a JOIN messages m ON m.messageId=a.message_id
            WHERE a.object_id=? AND m.isMine=0 AND m.isRevoked=0
            """, values: [.text(id)])
        return true
    }

    static func queueCacheClear(_ db: AppDatabase.Transaction, categories: Set<AttachmentCacheCategory>) throws -> Int {
        let keys = String(decoding: try JSONSerialization.data(withJSONObject: categories.map(\.rawValue)), as: UTF8.self)
        try db.execute("""
                INSERT INTO media_cache_evictions(object_id)
                SELECT object_id FROM (\(categorizedCache))
                WHERE category IN (SELECT value FROM json_each(?))
            """, values: [.text(keys)])
        let count = try db.query("SELECT changes()", map: { Int(sqlite3_column_int64($0, 0)) }).first ?? 0
        guard count > 0 else { return 0 }
        try db.execute("""
            UPDATE media_objects SET local_state=0 WHERE local_state=1
              AND object_id IN (SELECT object_id FROM media_cache_evictions WHERE file_pending=1)
            """)
        try db.execute("""
                INSERT OR IGNORE INTO object_transfers(event_id,object_id,direction,owner_peer,owner_kind,created_at)
                SELECT m.messageId,a.object_id,1,m.conversationUuid,'message',m.timestamp
                FROM media_cache_evictions e JOIN (\(cacheSources)) a ON a.object_id=e.object_id
                JOIN messages m ON m.messageId=a.message_id WHERE e.file_pending=1 AND m.isMine=0 AND m.isRevoked=0
            """)
        return count
    }

}

final class EncryptedObjectMaintenance: @unchecked Sendable {
    private let queue=DispatchQueue(label:"org.msgat.object-maintenance",qos:.utility)
    private var timer: DispatchSourceTimer?
    private var closed=false
    private let onCollected: @Sendable () -> Void
    init(onCollected: @escaping @Sendable () -> Void) {
        self.onCollected = onCollected
        let source=DispatchSource.makeTimerSource(queue:queue);timer=source
        source.schedule(deadline:.now());source.setEventHandler { [weak self] in self?.tick() };source.resume()
    }
    private func tick() {
        guard !closed else {return}
        let count=(try? EncryptedObjectStore.shared.collectGarbage()) ?? 0
        if count > 0 { onCollected() }
        let delay: DispatchTimeInterval=count>=64 ? .milliseconds(250) : .seconds(30)
        timer?.schedule(deadline:.now()+delay)
    }
    func wake() { queue.async { [self] in timer?.schedule(deadline: .now()) } }
    func close(){queue.sync {self.closed=true;self.timer?.cancel();self.timer=nil}}
}
