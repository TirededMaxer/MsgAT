// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

struct MessageRetentionSettings: Sendable {
    var outgoing: Int64
    var local: Int64

    func shortensLocalHistory(_ previous: Self) -> Bool {
        local > 0 && (previous.local == 0 || local < previous.local)
    }
}

enum MessageExpirationStore {
    static let choices: [(duration: Int64, labelKey: String)] = [
        (0, "duration_off"), (60_000, "duration_minute"), (3_600_000, "duration_hour"),
        (86_400_000, "duration_day"), (604_800_000, "duration_week"), (2_592_000_000, "duration_30_days")
    ]
    static let retentionChoices: [(duration: Int64, labelKey: String)] = [
        (0, "duration_forever"), (86_400_000, "duration_day"), (604_800_000, "duration_week"),
        (2_592_000_000, "duration_30_days"), (7_776_000_000, "duration_90_days")
    ]

    static let schema = [
        """
        CREATE TABLE IF NOT EXISTS conversation_expiration(
        peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,
        duration_ms INTEGER NOT NULL CHECK(duration_ms IN (60000,3600000,86400000,604800000,2592000000)))
        """,
        """
        CREATE TABLE IF NOT EXISTS conversation_retention(
            peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,
            duration_ms INTEGER NOT NULL CHECK(duration_ms IN (86400000,604800000,2592000000,7776000000)))
        """,
        """
        CREATE TABLE IF NOT EXISTS message_expiration(
        message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(messageId) ON DELETE CASCADE,
        expires_at INTEGER NOT NULL CHECK(expires_at>0))
        """,
        """
        CREATE INDEX IF NOT EXISTS message_expiration_due ON message_expiration(expires_at,message_id)
        """,
        """
        CREATE TRIGGER IF NOT EXISTS message_expiration_outgoing AFTER INSERT ON messages
        WHEN NEW.isMine=1 BEGIN
        INSERT INTO message_expiration(message_id,expires_at)
        SELECT NEW.messageId,NEW.timestamp+json_extract(p.body_json,'$.expire_after_ms')
        FROM protocol_pending_events p WHERE p.event_id=NEW.messageId
          AND p.recipient=NEW.conversationUuid AND json_type(p.body_json,'$.expire_after_ms')='integer';
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS message_expiration_cleanup BEFORE DELETE ON messages
        WHEN EXISTS(SELECT 1 FROM message_expiration WHERE message_id=OLD.messageId) BEGIN
        DELETE FROM protocol_outbox WHERE recipient=OLD.conversationUuid AND event_id IN (
            SELECT OLD.messageId WHERE OLD.isMine=1
            UNION ALL SELECT event_id FROM send_related_events WHERE message_id=OLD.messageId
            UNION ALL SELECT event_id FROM protocol_receipts WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId);
        DELETE FROM protocol_pending_events WHERE recipient=OLD.conversationUuid AND event_id IN (
            SELECT OLD.messageId WHERE OLD.isMine=1
            UNION ALL SELECT event_id FROM send_related_events WHERE message_id=OLD.messageId
            UNION ALL SELECT event_id FROM protocol_receipts WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId);
        DELETE FROM protocol_receipts WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId;
        DELETE FROM read_receipt_suppression WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId;
        DELETE FROM message_changes WHERE conversation_uuid=OLD.conversationUuid AND message_id=OLD.messageId;
        UPDATE messages SET replyToContent=NULL WHERE conversationUuid=OLD.conversationUuid AND replyToId=OLD.messageId;
        END
        """
    ]

    static func duration(_ db: AppDatabase.Transaction, peer: String) throws -> Int64 {
        try db.query("SELECT duration_ms FROM conversation_expiration WHERE peer=?", values: [.text(peer)],
                     map: { sqlite3_column_int64($0, 0) }).first ?? 0
    }

    static func settings(_ db: AppDatabase.Transaction, peer: String) throws -> MessageRetentionSettings {
        try MessageRetentionSettings(outgoing: duration(db, peer: peer), local: localDuration(db, peer: peer))
    }

    static func setSettings(_ db: AppDatabase.Transaction, peer: String, settings: MessageRetentionSettings) throws {
        let duration = settings.outgoing
        guard choices.contains(where: { $0.duration == duration }),
              retentionChoices.contains(where: { $0.duration == settings.local }) else {
            throw MsgATDatabaseError(operation: "Message retention", detail: "Invalid retention duration.")
        }
        if duration == 0 {
            try db.execute("DELETE FROM conversation_expiration WHERE peer=?", values: [.text(peer)])
        } else {
            try db.execute("""
                INSERT INTO conversation_expiration(peer,duration_ms) VALUES(?,?)
                ON CONFLICT(peer) DO UPDATE SET duration_ms=excluded.duration_ms
                """, values: [.text(peer), .integer(duration)])
        }
        if settings.local == 0 {
            try db.execute("DELETE FROM conversation_retention WHERE peer=?", values: [.text(peer)])
        } else {
            try db.execute("""
                INSERT INTO conversation_retention(peer,duration_ms) VALUES(?,?)
                ON CONFLICT(peer) DO UPDATE SET duration_ms=excluded.duration_ms
                """, values: [.text(peer), .integer(settings.local)])
        }
    }

    private static func localDuration(_ db: AppDatabase.Transaction, peer: String) throws -> Int64 {
        try db.query("SELECT duration_ms FROM conversation_retention WHERE peer=?", values: [.text(peer)],
                     map: { sqlite3_column_int64($0, 0) }).first ?? 0
    }

    static func discardIncoming(_ db: AppDatabase.Transaction, peer: String, kind: String, created: Int64, now: Int64) throws -> Bool {
        guard ["chat.text", "chat.attachment", "chat.contact", "chat.location", "chat.location.start"].contains(kind) else { return false }
        let local = try localDuration(db, peer: peer)
        return local > 0 && created <= now - local
    }

    static let markDueRetentionSQL = """
        INSERT INTO message_expiration(message_id,expires_at)
        SELECT m.messageId,?1 FROM conversation_retention r
        JOIN messages m ON m.conversationUuid=r.peer AND m.timestamp<=?1-r.duration_ms
        WHERE m.isMine=0 OR m.status>=2
        ORDER BY m.timestamp,m.messageId LIMIT ?2
        ON CONFLICT(message_id) DO UPDATE SET expires_at=MIN(message_expiration.expires_at,excluded.expires_at)
        """

    static func markDueRetention(_ db: AppDatabase.Transaction, now: Int64) throws -> Int {
        try db.execute(markDueRetentionSQL, values: [.integer(now), .integer(64)])
        return try db.query("SELECT changes()", map: { Int(sqlite3_column_int64($0, 0)) }).first ?? 0
    }

    static func deadline(_ db: AppDatabase.Transaction, id: String) throws -> Int64? {
        try db.query("SELECT expires_at FROM message_expiration WHERE message_id=?", values: [.text(id)],
                     map: { sqlite3_column_int64($0, 0) }).first
    }

    static func record(_ db: AppDatabase.Transaction, id: String, deadline: Int64) throws {
        try db.execute("INSERT INTO message_expiration(message_id,expires_at) VALUES(?,?)",
                       values: [.text(id), .integer(deadline)])
    }

    static func removeDue(_ db: AppDatabase.Transaction, now: Int64) throws -> (next: Int64, ids: [String], tokens: [String]) {
        let rows = try db.query("""
            SELECT d.message_id,n.token
            FROM (SELECT message_id FROM message_expiration WHERE expires_at<=? ORDER BY expires_at,message_id LIMIT 64) d
            LEFT JOIN notification_targets n ON n.message_id=d.message_id
            """, values: [.integer(now)]) { row in
            let id = String(cString: sqlite3_column_text(row, 0))
            let token = sqlite3_column_text(row, 1).map { String(cString: $0) }
            return (id, token)
        }
        if !rows.isEmpty {
            try db.execute("""
                DELETE FROM messages WHERE messageId IN (
                    SELECT message_id FROM message_expiration WHERE expires_at<=? ORDER BY expires_at,message_id LIMIT 64)
                """, values: [.integer(now)])
        }
        let next = try db.query("SELECT expires_at FROM message_expiration ORDER BY expires_at LIMIT 1",
                                map: { sqlite3_column_int64($0, 0) }).first ?? Int64.max
        return (next, rows.map { $0.0 }, rows.compactMap { $0.1 })
    }
}
