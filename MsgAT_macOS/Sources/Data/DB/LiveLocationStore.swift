// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

struct LiveLocationSnapshot: Sendable, Equatable {
    let startedAt: Int64
    let expiresAt: Int64
    let revision: Int
    let ended: Bool
    let point: LocationPoint
}

enum LiveLocationStore {
    static func apply(_ db: AppDatabase.Transaction, peer: String, mine: Bool, kind: String,
                      eventID: String, body: [String: Any], createdAt: Int64, now: Int64) throws -> Bool {
        let start = kind == "chat.location.start"
        guard let messageID = start ? eventID : body["message_id"] as? String else { throw SecurityCore.Failure.invalidResponse }
        let direction: Int64 = mine ? 1 : 0
        let scope: [DatabaseValue] = [.text(peer), .integer(direction), .text(messageID)]
        let existing = try db.query("SELECT conversationUuid,isMine,ext,isRevoked FROM messages WHERE messageId=?",
                                    values: [.text(messageID)]) {
            (String(cString: sqlite3_column_text($0, 0)), sqlite3_column_int64($0, 1),
             String(cString: sqlite3_column_text($0, 2)), sqlite3_column_int($0, 3))
        }.first
        if let existing {
            guard existing.0 == peer, existing.1 == direction, existing.2 == "live_location", existing.3 == 0 else { return false }
        } else if mine && !start {
            return false
        }

        try pruneOrphans(db, now: now)
        let previous = try db.query("SELECT state_json FROM live_location_states WHERE peer=? AND is_mine=? AND message_id=?",
                                    values: scope) {
            try JSONSerialization.jsonObject(with: Data(String(cString: sqlite3_column_text($0, 0)).utf8))
        }.first
        if !start && previous == nil {
            guard !mine, try countOrphans(db, peer: peer) < 16, try countOrphans(db, peer: nil) < 256 else { return false }
        }
        let result = try SecurityCore.call([
            "operation": "live_location_transition", "state": previous ?? NSNull(), "event_kind": kind,
            "event_id": eventID, "body": body, "created_at": createdAt, "now": now
        ])
        guard result["changed"] as? Bool == true else { return false }
        guard let state = result["state"] as? [String: Any], let expiresAt = state["expires_at"] as? Int64,
              let anchored = state["anchored"] as? Bool else { throw SecurityCore.Failure.invalidResponse }
        let json = String(decoding: try JSONSerialization.data(withJSONObject: state, options: .sortedKeys), as: UTF8.self)
        try db.execute("""
            INSERT INTO live_location_states(peer,is_mine,message_id,expires_at,anchored,state_json) VALUES(?,?,?,?,?,?)
            ON CONFLICT(peer,is_mine,message_id) DO UPDATE SET
            expires_at=excluded.expires_at,anchored=excluded.anchored,state_json=excluded.state_json
            """, values: scope + [.integer(expiresAt), .integer(anchored ? 1 : 0), .text(json)])
        return true
    }

    static func read(_ db: AppDatabase.Transaction, peer: String, messageID: String, mineOnly: Bool = false) throws -> LiveLocationSnapshot? {
        try db.query("""
            SELECT s.state_json FROM messages m JOIN live_location_states s
              ON s.message_id=m.messageId AND s.peer=m.conversationUuid AND s.is_mine=m.isMine
            WHERE m.conversationUuid=? AND m.messageId=? AND m.ext='live_location' AND m.isRevoked=0 AND s.anchored=1
              AND (?=0 OR m.isMine=1)
            """, values: [.text(peer), .text(messageID), .integer(mineOnly ? 1 : 0)]) { row in
            let json = Data(String(cString: sqlite3_column_text(row, 0)).utf8)
            guard let state = try JSONSerialization.jsonObject(with: json) as? [String: Any],
                  let startedAt = state["started_at"] as? Int64, let expiresAt = state["expires_at"] as? Int64,
                  let revision = state["revision"] as? Int, let ended = state["ended"] as? Bool,
                  let rawPoint = state["point"] as? [String: Any], let point = LocationPoint(body: rawPoint)
            else { throw SecurityCore.Failure.invalidResponse }
            return LiveLocationSnapshot(startedAt: startedAt, expiresAt: expiresAt, revision: revision, ended: ended, point: point)
        }.first
    }

    static func hasActiveShare(_ db: AppDatabase.Transaction, peer: String, now: Int64) throws -> Bool {
        try !db.query("""
            SELECT 1 FROM live_location_states
            WHERE peer=? AND is_mine=1 AND anchored=1 AND expires_at>? AND json_extract(state_json,'$.ended')=0
            LIMIT 1
            """, values: [.text(peer), .integer(now)], map: { _ in true }).isEmpty
    }

    static func replaceOutgoing(_ db: AppDatabase.Transaction, eventID: String, peer: String, messageID: String,
                                expiresAt: Int64, end: Bool) throws {
        if end {
            try db.execute("DELETE FROM live_location_outgoing WHERE message_id=?", values: [.text(messageID)])
        } else {
            try db.execute("""
                DELETE FROM live_location_outgoing WHERE message_id=? AND NOT EXISTS (
                    SELECT 1 FROM protocol_outbox b WHERE b.event_id=live_location_outgoing.event_id AND b.delivered=0
                )
                """, values: [.text(messageID)])
        }
        try db.execute("INSERT INTO live_location_outgoing(event_id,peer,message_id,expires_at) VALUES(?,?,?,?)",
                       values: [.text(eventID), .text(peer), .text(messageID), .integer(expiresAt)])
    }

    static func pruneOutgoing(_ db: AppDatabase.Transaction, now: Int64) throws {
        try db.execute("""
            DELETE FROM live_location_outgoing WHERE expires_at<=? OR NOT EXISTS (
                SELECT 1 FROM contact_states c WHERE c.uuid=live_location_outgoing.peer AND c.accepted=1 AND c.blocked=0
            )
            """, values: [.integer(now)])
    }

    static func pruneOrphans(_ db: AppDatabase.Transaction, now: Int64) throws {
        try db.execute("DELETE FROM live_location_states WHERE anchored=0 AND expires_at<=?", values: [.integer(now)])
    }

    private static func countOrphans(_ db: AppDatabase.Transaction, peer: String?) throws -> Int64 {
        let sql = "SELECT COUNT(*) FROM live_location_states WHERE anchored=0" + (peer == nil ? "" : " AND peer=?")
        return try db.query(sql, values: peer.map { [.text($0)] } ?? []) { sqlite3_column_int64($0, 0) }.first ?? 0
    }
}
