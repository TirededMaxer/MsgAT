// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

enum CallOutcome: String, Sendable {
    case completed, missed, declined, busy, canceled, failed
}

func displayCallSummary(_ text: String, strings: InterfaceStrings) -> String {
    let voice = "[Voice Call: "
    let video = "[Video Call: "
    guard text.hasSuffix("]") else { return text }
    let isVideo = text.hasPrefix(video)
    guard isVideo || text.hasPrefix(voice) else { return text }
    let result = String(text.dropFirst(isVideo ? video.count : voice.count).dropLast())
    let detail: String
    switch result {
    case "Missed": detail = strings("call_outcome_missed")
    case "Declined": detail = strings("call_outcome_declined")
    case "Busy": detail = strings("call_outcome_busy")
    case "Canceled": detail = strings("call_outcome_canceled")
    case "Failed": detail = strings("call_outcome_failed")
    default:
        guard result.range(of: #"\A[0-9]{2,12}:[0-5][0-9]\z"#, options: .regularExpression) != nil else { return text }
        detail = result
    }
    return "\(strings(isVideo ? "video_call" : "voice_call")) · \(detail)"
}

struct CallHistoryEntry: Sendable, Identifiable {
    let peer: String
    let callId: String
    let alias: String
    let outgoing: Bool
    let video: Bool
    let outcome: CallOutcome
    let duration: Int64
    let endedAt: Int64
    let avatarPath: String
    var id: String {peer + "/" + callId}
}

enum CallHistory {
    static let didChange = Notification.Name("org.msgat.call-history-changed")
    static let table = """
        CREATE TABLE IF NOT EXISTS call_history(
            peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,
            call_id TEXT NOT NULL, outgoing INTEGER NOT NULL CHECK(outgoing IN(0,1)),
            video INTEGER NOT NULL CHECK(video IN(0,1)), outcome TEXT NOT NULL
              CHECK(outcome IN('completed','missed','declined','busy','canceled','failed')),
            duration INTEGER NOT NULL CHECK(duration>=0), ended_at INTEGER NOT NULL,
            PRIMARY KEY(peer,call_id))
        """
    static let index = "CREATE INDEX IF NOT EXISTS call_history_timeline ON call_history(ended_at DESC,call_id DESC,peer DESC)"

    static func insert(_ db: AppDatabase.Transaction, peer: String, callId: String, outgoing: Bool, video: Bool,
                       outcome: CallOutcome, duration: Int64, endedAt: Int64) throws {
        try db.execute("""
            INSERT INTO call_history(peer,call_id,outgoing,video,outcome,duration,ended_at)
            SELECT uuid,?,?,?,?,?,? FROM conversations WHERE uuid=?
            ON CONFLICT(peer,call_id) DO NOTHING
            """, values:[.text(callId),.integer(outgoing ? 1 : 0),.integer(video ? 1 : 0),.text(outcome.rawValue),
                         .integer(duration),.integer(endedAt),.text(peer)])
    }

    static func remove(_ db: AppDatabase.Transaction, entry: CallHistoryEntry?) throws {
        if let entry {
            try db.execute("DELETE FROM call_history WHERE peer=? AND call_id=?",
                           values: [.text(entry.peer), .text(entry.callId)])
        } else {
            try db.execute("DELETE FROM call_history")
        }
    }

    static func page(_ before: CallHistoryEntry?, count: Int, missedOnly: Bool) throws -> [CallHistoryEntry] {
        var conditions: [String] = []
        if missedOnly { conditions.append("h.outgoing=0 AND h.outcome='missed'") }
        if before != nil { conditions.append("(h.ended_at,h.call_id,h.peer)<(?,?,?)") }
        let filter = conditions.isEmpty ? "" : "WHERE " + conditions.joined(separator: " AND ")
        let values: [DatabaseValue] = (before.map { [.integer($0.endedAt), .text($0.callId), .text($0.peer)] } ?? [])
            + [.integer(Int64(count) + 1)]
        return try AppDatabase.shared.query("""
            SELECT h.peer,h.call_id,c.alias,h.outgoing,h.video,h.outcome,h.duration,h.ended_at,c.avatarPath
            FROM call_history h JOIN conversations c ON c.uuid=h.peer \(filter)
            ORDER BY h.ended_at DESC,h.call_id DESC,h.peer DESC LIMIT ?
            """, values:values) { row in
                guard let outcome=CallOutcome(rawValue:String(cString:sqlite3_column_text(row,5))) else {
                    throw MsgATDatabaseError(operation:"Read calls",detail:"Invalid call outcome")
                }
                return CallHistoryEntry(peer:String(cString:sqlite3_column_text(row,0)),callId:String(cString:sqlite3_column_text(row,1)),
                                        alias:String(cString:sqlite3_column_text(row,2)),outgoing:sqlite3_column_int(row,3) != 0,
                                        video:sqlite3_column_int(row,4) != 0,outcome:outcome,duration:sqlite3_column_int64(row,6),
                                        endedAt:sqlite3_column_int64(row,7),avatarPath:String(cString:sqlite3_column_text(row,8)))
            }
    }
}
