// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

enum MessageSearchKind: String, CaseIterable, Identifiable, Sendable {
    case all = "All", text = "Text", attachments = "Attachments"
    var id: Self { self }
    var messageExtension: String? {
        switch self {
        case .all: return nil
        case .text: return "txt"
        case .attachments: return "attachment"
        }
    }
}

struct MessageSearchHit: Identifiable, Equatable, Sendable {
    let id: String
    let peer: String
    let alias: String
    let preview: String
    let timestamp: Int64
    let isAttachment: Bool
}
struct MessageSearchCursor: Sendable { let timestamp: Int64; let messageId: String }
struct MessageSearchPage: Sendable { let hits: [MessageSearchHit]; let next: MessageSearchCursor? }

final class MessageSearchStore: @unchecked Sendable {
    static let shared = MessageSearchStore()
    private let gate = NSLock()
    private var ready = false

    func search(_ input: String, peer: String? = nil, before: MessageSearchCursor? = nil,
                kind: MessageSearchKind = .all, limit: Int = 50, newer: Bool = false,
                day: Date? = nil) throws -> MessageSearchPage {
        let query=input.trimmingCharacters(in: .whitespacesAndNewlines)
        if query.isEmpty { return MessageSearchPage(hits: [],next: nil) }
        guard query.unicodeScalars.count<=256, !query.contains("\0"), limit > 0, limit < Int.max else {
            throw MsgATDatabaseError(operation: "Search",detail: "Search text is too long or invalid.")
        }
        let interval = day.flatMap { Calendar.current.dateInterval(of: .day, for: $0) }
        let dayStart = interval.map { DatabaseValue.integer(Int64($0.start.timeIntervalSince1970 * 1_000)) } ?? .null
        let dayEnd = interval.map { DatabaseValue.integer(Int64($0.end.timeIntervalSince1970 * 1_000)) } ?? .null
        gate.lock(); defer { gate.unlock() }
        let page = try AppDatabase.shared.transaction { db in
            if !ready {
                guard let url=Bundle.main.url(forResource: "message-search",withExtension: "sql") else {
                    throw MsgATDatabaseError(operation: "Search",detail: "The search resource is unavailable.")
                }
                for statement in try String(contentsOf: url,encoding: .utf8).components(separatedBy: "\n-- statement\n") { try db.execute(statement) }
            }
            let indexed=query.unicodeScalars.count>=3
            let condition=indexed ? "local_message_search MATCH ?" : "instr(lower(s.body),lower(?))>0"
            let needle=indexed ? "\""+query.replacingOccurrences(of: "\"",with: "\"\"")+"\"" : query
            let cursorTime=before.map { DatabaseValue.integer($0.timestamp) } ?? .null
            let extensionFilter = kind.messageExtension.map(DatabaseValue.text) ?? .null
            let comparison = newer ? ">" : "<"
            let order = newer ? "ASC" : "DESC"
            let rows=try db.query("""
                SELECT m.messageId,m.conversationUuid,c.alias,s.body,m.timestamp,m.ext
                FROM local_message_search s JOIN messages m ON m.rowid=s.rowid
                JOIN conversations c ON c.uuid=m.conversationUuid
                WHERE \(condition) AND m.isRevoked=0 AND (? IS NULL OR m.conversationUuid=?)
                 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?4)
                 AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                     AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?4-r.duration_ms)
                 AND (? IS NULL OR m.ext=?)
                 AND (? IS NULL OR (m.timestamp>=? AND m.timestamp<?))
                 AND (? IS NULL OR m.timestamp\(comparison)? OR (m.timestamp=? AND m.messageId\(comparison)?))
                ORDER BY m.timestamp \(order),m.messageId \(order) LIMIT ?
                """,values: [.text(needle),peer.map(DatabaseValue.text) ?? .null,peer.map(DatabaseValue.text) ?? .null,
                    .integer(Int64(Date().timeIntervalSince1970 * 1000)),
                    extensionFilter,extensionFilter,dayStart,dayStart,dayEnd,
                    cursorTime,cursorTime,cursorTime,before.map { .text($0.messageId) } ?? .null,
                    .integer(Int64(limit + 1))]) { row in
                let body=SecurityCore.messageSummary(String(cString: sqlite3_column_text(row,3)),limit:48*1024)
                let match=body.range(of: query,options: .caseInsensitive)?.lowerBound ?? body.startIndex
                let start=body.index(match,offsetBy: -70,limitedBy: body.startIndex) ?? body.startIndex
                let end=body.index(start,offsetBy: 280,limitedBy: body.endIndex) ?? body.endIndex
                return MessageSearchHit(id: String(cString: sqlite3_column_text(row,0)),peer: String(cString: sqlite3_column_text(row,1)),
                    alias: String(cString: sqlite3_column_text(row,2)),preview: (start>body.startIndex ? "…" : "")+String(body[start..<end])+(end<body.endIndex ? "…" : ""),timestamp: sqlite3_column_int64(row,4),
                    isAttachment: String(cString: sqlite3_column_text(row,5)) == "attachment")
            }
            let hits=Array(rows.prefix(limit))
            let next=rows.count>limit ? hits.last.map { MessageSearchCursor(timestamp: $0.timestamp,messageId: $0.id) } : nil
            return MessageSearchPage(hits: hits,next: next)
        }
        ready=true
        return page
    }
}
