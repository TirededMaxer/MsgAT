// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

enum MessageNotificationStore {
    struct Settings: Sendable {
        let enabled: Bool
        let playSound: Bool
        let showPreviews: Bool
    }
    struct Target {
        let peer: String
        let messageID: String
    }

    struct Notice {
        let token: String
        let replacedToken: String?
        let title: String
        let body: String?
        let playSound: Bool
    }

    static func settings(_ db: AppDatabase.Transaction) throws -> Settings {
        guard let settings = try db.query("""
            SELECT message_alerts_enabled,message_alert_sound,show_notification_previews FROM privacy_preferences WHERE id=1
            """, map: { row in
                Settings(enabled: sqlite3_column_int(row,0) == 1, playSound: sqlite3_column_int(row,1) == 1,
                         showPreviews: sqlite3_column_int(row,2) == 1)
            }).first else { throw MsgATDatabaseError(operation: "Notifications", detail: "Notification preferences are unavailable.") }
        return settings
    }

    static func setSettings(_ settings: Settings, _ db: AppDatabase.Transaction) throws {
        try db.execute("""
            UPDATE privacy_preferences SET message_alerts_enabled=?,message_alert_sound=?,show_notification_previews=? WHERE id=1
            """, values: [.integer(settings.enabled ? 1 : 0), .integer(settings.playSound ? 1 : 0), .integer(settings.showPreviews ? 1 : 0)])
    }

    static func prepare(peer: String, messageID: String, allowPreview: Bool, pinned: Bool = false) throws -> Notice? {
        try AppDatabase.shared.transaction { db in
            let now = Int64(Date().timeIntervalSince1970 * 1_000)
            let settings = try Self.settings(db)
            guard settings.enabled, try !ConversationIndexStore.muted(db, peer: peer, now: now) else { return nil }
            let deadline = try MessageExpirationStore.deadline(db, id: messageID)
            if let deadline, deadline <= now { return nil }
            let preview = try settings.showPreviews && !pinned && deadline == nil && allowPreview && !db.query("""
                SELECT 1 FROM privacy_preferences WHERE id=1 AND app_lock_enabled=0
                """, map: { _ in true }).isEmpty
            let columns = preview ? "c.alias,m.ext,m.content" : "1"
            let target = pinned ? """
                EXISTS(SELECT 1 FROM message_pin_clocks p WHERE p.peer=m.conversationUuid
                    AND p.message_id=m.messageId AND p.actor=m.conversationUuid AND p.addition>p.removed)
                """ : "m.isMine=0 AND m.isRead=0"
            let messages = try db.query("""
                SELECT \(columns) FROM messages m JOIN conversations c ON c.uuid=m.conversationUuid
                JOIN contact_states s ON s.uuid=c.uuid
                WHERE m.conversationUuid=? AND m.messageId=? AND m.isRevoked=0 AND (\(target))
                  AND s.accepted=1 AND s.blocked=0
                """, values: [.text(peer), .text(messageID)]) { row -> (title: String, body: String?) in
                guard preview else { return ("MsgAT", nil) }
                let title = clean(String(cString: sqlite3_column_text(row, 0)), limit: 60)
                let ext = String(cString: sqlite3_column_text(row, 1))
                let body: String
                if ext == "txt" {
                    let text = String(cString: sqlite3_column_text(row, 2))
                    let summary = (try? SecurityCore.call(["operation": "summarize_message_text", "text": text, "limit": 120])["text"] as? String) ?? ""
                    body = clean(summary, limit: 120)
                } else { body = "" }
                return (title.isEmpty ? "MsgAT" : title, body.isEmpty ? nil : body)
            }
            guard let display = messages.first else { return nil }
            let replacedToken = try db.query("SELECT token FROM notification_targets WHERE peer=?", values: [.text(peer)],
                                     map: { String(cString: sqlite3_column_text($0, 0)) }).first
            let token = UUID().uuidString.lowercased()
            try db.execute("""
                INSERT INTO notification_targets(peer,token,message_id,is_pin) VALUES(?,?,?,?)
                ON CONFLICT(peer) DO UPDATE SET token=excluded.token,message_id=excluded.message_id,is_pin=excluded.is_pin
                """, values: [.text(peer), .text(token), .text(messageID), .integer(pinned ? 1 : 0)])
            return Notice(token: token, replacedToken: replacedToken, title: display.title, body: display.body, playSound: settings.playSound)
        }
    }

    static func resolve(_ token: String) throws -> Target? {
        guard UUID(uuidString: token)?.uuidString.lowercased() == token else { return nil }
        return try AppDatabase.shared.query("""
            SELECT n.peer,n.message_id FROM notification_targets n
            JOIN messages m ON m.messageId=n.message_id AND m.conversationUuid=n.peer
            JOIN contact_states s ON s.uuid=n.peer
            WHERE n.token=? AND s.accepted=1 AND s.blocked=0 AND m.isRevoked=0
              AND (n.is_pin=0 OR EXISTS (SELECT 1 FROM message_pin_clocks p WHERE p.peer=n.peer
                  AND p.message_id=n.message_id AND p.actor=n.peer AND p.addition>p.removed))
              AND EXISTS(SELECT 1 FROM privacy_preferences WHERE id=1 AND message_alerts_enabled=1)
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=n.message_id AND e.expires_at<=?2)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=n.peer
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?2-r.duration_ms)
            """, values: [.text(token), .integer(Int64(Date().timeIntervalSince1970 * 1_000))]) { row in
            Target(peer: String(cString: sqlite3_column_text(row, 0)), messageID: String(cString: sqlite3_column_text(row, 1)))
        }.first
    }

    private static func clean(_ value: String, limit: Int) -> String {
        var output = ""
        var count = 0
        for scalar in value.unicodeScalars {
            if count >= limit { break }
            if CharacterSet.whitespacesAndNewlines.contains(scalar) {
                guard !output.isEmpty, output.last != " " else { continue }
                output.append(" ")
            } else {
                guard !CharacterSet.controlCharacters.contains(scalar), scalar.properties.generalCategory != .format else { continue }
                output.unicodeScalars.append(scalar)
            }
            count += 1
        }
        return output.trimmingCharacters(in: .whitespaces)
    }
}
