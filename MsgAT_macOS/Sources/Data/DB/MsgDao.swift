import Foundation
import SQLCipher

struct ForwardedMessageInfo: Sendable, Equatable {let name: String?;let originalAt: Int64}
struct MessageDeliveryChange: Sendable {
    let peer: String
    let messageID: String
}
struct VisibleMessagesRead: Sendable {
    let peer: String
    let messageIDs: Set<String>
}

public final class MsgDao: Sendable {
    public static let shared = MsgDao()
    public static let didChangeNotification = Notification.Name("org.msgat.database.didChange")
    static let deliveryDidChangeNotification = Notification.Name("org.msgat.database.deliveryDidChange")
    static let visibleMessagesReadNotification = Notification.Name("org.msgat.database.visibleMessagesRead")

    func selectedMessageText(peer: String, ids: [String]) throws -> [String] {
        guard !ids.isEmpty, ids.count <= 100 else { return [] }
        let placeholders = Array(repeating: "?", count: ids.count).joined(separator: ",")
        return try database.query("""
            SELECT content FROM messages
            WHERE conversationUuid=? AND messageId IN (\(placeholders)) AND ext='txt' AND isRevoked=0
            ORDER BY timestamp,messageId
            """, values: [.text(peer)] + ids.map { .text($0) }) { databaseText($0, 0) }
    }

    private let database = AppDatabase.shared
    private init() {}

    private func changed() {
        NotificationCenter.default.post(name: Self.didChangeNotification, object: nil)
    }

    func getForwardInfo(ids: [String]) throws -> [String: ForwardedMessageInfo] {
        var result: [String: ForwardedMessageInfo] = [:]
        for start in stride(from: 0, to: ids.count, by: 100) {
            let batch = ids[start..<min(start + 100, ids.count)]
            let placeholders = Array(repeating: "?", count: batch.count).joined(separator: ",")
            let rows = try database.query("""
                SELECT message_id,name,original_at FROM message_forwards
                WHERE message_id IN (\(placeholders))
                """, values: batch.map { .text($0) }) { row in
                let name = sqlite3_column_type(row, 1) == SQLITE_NULL ? nil : databaseText(row, 1)
                return (databaseText(row, 0), ForwardedMessageInfo(name: name, originalAt: sqlite3_column_int64(row, 2)))
            }
            for (id, info) in rows { result[id] = info }
        }
        return result
    }

    func getForwardTargets() throws -> [Conversation] {
        try database.query("SELECT c.uuid,c.alias,c.avatarPath,c.peerPublicKey FROM conversations c JOIN contact_states s ON s.uuid=c.uuid WHERE s.accepted=1 AND s.blocked=0 ORDER BY c.alias COLLATE NOCASE,c.uuid",map:conversation)
    }

    func getContactCardAccount(peer: String, messageID: String) throws -> String? {
        try database.query("""
            SELECT c.account FROM message_contact_cards c JOIN messages m ON m.messageId=c.message_id
            WHERE m.conversationUuid=? AND m.messageId=? AND m.ext='contact' AND m.isRevoked=0
            """, values: [.text(peer), .text(messageID)]) { String(cString: sqlite3_column_text($0, 0)) }.first
    }

    func getSavedContactCardAccount(_ id: String) throws -> String? {
        try database.query("SELECT account FROM saved_contact_cards WHERE id=?", values: [.text(id)]) {
            String(cString: sqlite3_column_text($0, 0))
        }.first
    }

    func getSavedLocation(_ id: String) throws -> LocationPoint? {
        try database.query("SELECT latitude_e6,longitude_e6 FROM saved_locations WHERE id=?", values: [.text(id)]) {
            LocationPoint(latitudeE6: Int(sqlite3_column_int64($0, 0)), longitudeE6: Int(sqlite3_column_int64($0, 1)))
        }.compactMap { $0 }.first
    }

    func getLocationPoint(peer: String, messageID: String) throws -> LocationPoint? {
        try database.query("""
            SELECT l.latitude_e6,l.longitude_e6 FROM message_locations l JOIN messages m ON m.messageId=l.message_id
            WHERE m.conversationUuid=? AND m.messageId=? AND m.ext='location' AND m.isRevoked=0
            """, values: [.text(peer), .text(messageID)]) {
                LocationPoint(latitudeE6: Int(sqlite3_column_int64($0, 0)), longitudeE6: Int(sqlite3_column_int64($0, 1)))
            }.compactMap { $0 }.first
    }

    public func getAllConversations() throws -> [Conversation] {
        try database.query(
            "SELECT uuid, alias, avatarPath, peerPublicKey FROM conversations ORDER BY rowid",
            map: conversation
        )
    }

    public func getAllConversationsWithUnread()throws->[ConversationWithUnread]{
        try database.query("""
            SELECT s.*, CASE WHEN m.isMine=1 AND m.isRevoked=0 AND m.status BETWEEN -1 AND 3
                THEN m.status END AS lastReceiptStatus,
            CASE
                WHEN m.isRevoked=1 THEN 'revoked'
                WHEN m.ext='contact' THEN 'contact'
                WHEN m.ext='attachment' AND NOT EXISTS (
                    SELECT 1 FROM message_attachments a LEFT JOIN media_objects o ON o.object_id=a.object_id
                    WHERE a.message_id=m.messageId AND
                        (COALESCE(a.caption,'')!='' OR COALESCE(json_extract(o.manifest_json,'$.filename'),'')!='')
                ) THEN 'attachment'
                WHEN m.ext NOT IN ('txt','location','live_location','attachment') THEN 'call'
                ELSE ''
            END AS summaryKind
            FROM conversation_summaries s LEFT JOIN messages m ON m.messageId=s.lastID
            ORDER BY s.pinOrder DESC,s.activityAt DESC,s.uuid
            """) {statement in
            var row=ConversationWithUnread(conversation:conversation(statement),unreadCount:Int(sqlite3_column_int64(statement,4)))
            row.pinOrder=sqlite3_column_int64(statement,5);row.archived=sqlite3_column_int(statement,6) != 0;row.muteUntil=sqlite3_column_int64(statement,7);row.manualUnread=sqlite3_column_int(statement,8) != 0
            row.summary=String(cString:sqlite3_column_text(statement,9));row.draftText=String(cString:sqlite3_column_text(statement,10));row.lastAt=sqlite3_column_int64(statement,11);row.lastID=String(cString:sqlite3_column_text(statement,12));row.firstUnreadID=String(cString:sqlite3_column_text(statement,13));row.folderIDs=String(cString:sqlite3_column_text(statement,14));row.hasDraft=sqlite3_column_int(statement,15) != 0;row.activityAt=sqlite3_column_int64(statement,16)
            let ranks = String(cString: sqlite3_column_text(statement, 17))
            row.folderPinOrders = try JSONDecoder().decode([String: Int64].self, from: Data(ranks.utf8))
            if sqlite3_column_type(statement, 18) != SQLITE_NULL {
                row.lastReceiptStatus = Int(sqlite3_column_int(statement, 18))
            }
            row.summaryKind = String(cString: sqlite3_column_text(statement, 19))
            return row
        }
    }

    public func getConversationByUuid(_ uuid: String) throws -> Conversation? {
        try database.query(
            "SELECT uuid, alias, avatarPath, peerPublicKey FROM conversations WHERE uuid = ? LIMIT 1",
            values: [.text(uuid)],
            map: conversation
        ).first
    }

    public func insertConversation(_ value: Conversation) throws {
        try database.execute("""
            INSERT INTO conversations(uuid, alias, avatarPath, peerPublicKey) VALUES(?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias,
              avatarPath=excluded.avatarPath, peerPublicKey=excluded.peerPublicKey
            """, values: [.text(value.uuid), .text(value.alias), .text(value.avatarPath), .text(value.peerPublicKey)])
        changed()
    }

    public func deleteConversation(_ uuid: String) throws {
        try database.execute("DELETE FROM conversations WHERE uuid = ?", values: [.text(uuid)])
        changed()
    }

    public func markConversationRead(conversationUuid: String) throws -> ChatMessage? {
        let unread = try database.queryAndExecute("""
            SELECT messageId, conversationUuid, isMine, content, ext, timestamp, isRead,
                   replyToId, replyToSender, replyToContent, status, isRevoked, isEdited
            FROM messages WHERE conversationUuid = ? AND isMine = 0 AND isRead = 0
            ORDER BY timestamp DESC, messageId DESC LIMIT 1
            """,
            updateSQL: "UPDATE messages SET isRead = 1 WHERE conversationUuid = ? AND isMine = 0 AND isRead = 0",
            values: [.text(conversationUuid)], map: message).first
        if unread != nil { changed() }
        return unread
    }

    public func getMessages(conversationUuid: String, limit: Int = 100, offset: Int = 0) throws -> [ChatMessage] {
        let rows = try database.query("""
            SELECT messageId, conversationUuid, isMine, content, ext, timestamp, isRead,
                   replyToId, replyToSender, replyToContent, status, isRevoked, isEdited, e.expires_at
            FROM messages m LEFT JOIN message_expiration e ON e.message_id=m.messageId
            WHERE conversationUuid = ? AND (e.expires_at IS NULL OR e.expires_at>?2)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?2-r.duration_ms)
            ORDER BY timestamp DESC, messageId DESC LIMIT ? OFFSET ?
            """, values: [.text(conversationUuid), .integer(Int64(Date().timeIntervalSince1970 * 1000)),
                           .integer(Int64(limit)), .integer(Int64(offset))], map: message)
        return rows.reversed()
    }

    public func getMessageListByConversation(_ uuid: String) throws -> [ChatMessage] {
        try database.query("""
            SELECT messageId, conversationUuid, isMine, content, ext, timestamp, isRead,
                   replyToId, replyToSender, replyToContent, status, isRevoked, isEdited
            FROM messages WHERE conversationUuid = ? ORDER BY timestamp
            """, values: [.text(uuid)], map: message)
    }

    public func getMessageById(_ messageId: String) throws -> ChatMessage? {
        try database.query("""
            SELECT messageId, conversationUuid, isMine, content, ext, timestamp, isRead,
                   replyToId, replyToSender, replyToContent, status, isRevoked, isEdited, e.expires_at
            FROM messages m LEFT JOIN message_expiration e ON e.message_id=m.messageId
            WHERE messageId = ? LIMIT 1
            """, values: [.text(messageId)], map: message).first
    }

    func replyOwners(in peer: String, ids: Set<String>) throws -> [String: Bool] {
        let keys = Array(ids)
        var result: [String: Bool] = [:]
        for start in stride(from: 0, to: keys.count, by: 100) {
            let batch = keys[start..<min(start + 100, keys.count)]
            let placeholders = Array(repeating: "?", count: batch.count).joined(separator: ",")
            let rows = try database.query("""
                SELECT messageId,isMine FROM messages
                WHERE conversationUuid=? AND messageId IN (\(placeholders))
                """, values: [.text(peer)] + batch.map { .text($0) }) { row in
                    (databaseText(row, 0), sqlite3_column_int(row, 1) != 0)
                }
            for (id, mine) in rows { result[id] = mine }
        }
        return result
    }

    func canQuoteText(_ value: ChatMessage) throws -> Bool {
        try !database.query("""
            SELECT 1 FROM messages m WHERE m.messageId=? AND m.conversationUuid=? AND m.ext='txt' AND m.isRevoked=0
              AND m.content=? AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId)
            """, values: [.text(value.id), .text(value.conversationUuid), .text(value.content)], map: { _ in true }).isEmpty
    }

    func canExportAttachment(_ message: ChatMessage) throws -> Bool {
        try !database.query("""
            SELECT 1 FROM messages m JOIN message_attachments a ON a.message_id=m.messageId
            JOIN media_objects o ON o.object_id=a.object_id
            WHERE m.messageId=? AND m.conversationUuid=? AND m.content=? AND m.isRevoked=0 AND o.local_state=1
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?-r.duration_ms)
            """, values: [.text(message.id), .text(message.conversationUuid), .text(message.content),
                          .integer(Int64(Date().timeIntervalSince1970 * 1000))], map: { _ in true }).isEmpty
    }

    public func getMessageAtDate(peer: String, dayStart: Int64, nextDayStart: Int64) throws -> String? {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return try database.query("""
            SELECT m.messageId FROM messages m
            WHERE m.conversationUuid=? AND m.timestamp>=? AND m.timestamp<? AND m.isRevoked=0
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?4)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND m.timestamp<=?4-r.duration_ms AND (m.isMine=0 OR m.status>=2))
            ORDER BY m.timestamp,m.messageId LIMIT 1
            """, values: [.text(peer), .integer(dayStart), .integer(nextDayStart), .integer(now)],
            map: { String(cString: sqlite3_column_text($0, 0)) }).first
    }

    public func getFirstUnreadId(peer: String) throws -> String? {
        try database.query("""
            SELECT m.messageId FROM messages m
            WHERE m.conversationUuid=? AND m.isMine=0 AND m.isRead=0 AND m.isRevoked=0
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?2)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND m.timestamp<=?2-r.duration_ms)
            ORDER BY m.timestamp,m.messageId LIMIT 1
            """, values: [.text(peer), .integer(Int64(Date().timeIntervalSince1970 * 1000))],
            map: { String(cString: sqlite3_column_text($0, 0)) }).first
    }

    func latestEditableMessage(peer: String) throws -> ChatMessage? {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return try database.query("""
            SELECT m.messageId, m.conversationUuid, m.isMine, m.content, m.ext, m.timestamp, m.isRead,
                   m.replyToId, m.replyToSender, m.replyToContent, m.status, m.isRevoked, m.isEdited
            FROM messages m
            WHERE m.conversationUuid=? AND m.isMine=1 AND m.isRevoked=0
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND m.status>=2 AND m.timestamp<=?-r.duration_ms)
              AND (m.ext='txt' OR (m.ext='attachment'
                   AND EXISTS(SELECT 1 FROM message_attachments a WHERE a.message_id=m.messageId)
                   AND NOT EXISTS(SELECT 1 FROM message_stickers s WHERE s.message_id=m.messageId)))
            ORDER BY m.timestamp DESC, m.messageId DESC LIMIT 1
            """, values: [.text(peer), .integer(now)], map: message).first
    }

    func adjacentReplyMessage(to anchor: ChatMessage, newer: Bool, includingAnchor: Bool) throws -> ChatMessage? {
        let comparison = newer ? ">" : (includingAnchor ? "<=" : "<")
        let order = newer ? "ASC" : "DESC"
        let now = Int64(Date().timeIntervalSince1970 * 1_000)
        return try database.query("""
            SELECT m.messageId, m.conversationUuid, m.isMine, m.content, m.ext, m.timestamp, m.isRead,
                   m.replyToId, m.replyToSender, m.replyToContent, m.status, m.isRevoked, m.isEdited
            FROM messages m
            WHERE m.conversationUuid=? AND m.isRevoked=0 AND (m.timestamp,m.messageId) \(comparison) (?,?)
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?4-r.duration_ms)
            ORDER BY m.timestamp \(order),m.messageId \(order) LIMIT 1
            """, values: [.text(anchor.conversationUuid), .integer(anchor.timestamp), .text(anchor.messageId), .integer(now)],
            map: message).first
    }

    func unreadCount(peer: String) throws -> Int {
        try database.query("SELECT COUNT(*) FROM messages WHERE conversationUuid=? AND isMine=0 AND isRead=0 AND isRevoked=0",
                           values: [.text(peer)], map: { Int(sqlite3_column_int64($0, 0)) }).first ?? 0
    }

    func unreadState(peer: String) throws -> (count: Int, firstID: String, marked: Bool)? {
        try database.query("""
            SELECT unreadCount,firstUnreadID,manualUnread FROM conversation_summaries WHERE uuid=?
            """, values: [.text(peer)]) { row in
                (Int(sqlite3_column_int64(row, 0)), String(cString: sqlite3_column_text(row, 1)),
                 sqlite3_column_int(row, 2) != 0)
            }.first
    }

    public func getMessagesBefore(_ anchor: ChatMessage,limit: Int=50) throws -> [ChatMessage] {
        try database.transaction {try seekMessages($0,anchor:anchor,newer:false,inclusive:false,limit:limit)}
    }
    public func getMessagesAfter(_ anchor: ChatMessage,limit: Int=50) throws -> [ChatMessage] {
        try database.transaction {try seekMessages($0,anchor:anchor,newer:true,inclusive:false,limit:limit)}
    }
    public func getMessagesFrom(_ anchor: ChatMessage,limit: Int) throws -> [ChatMessage] {
        try database.transaction {try seekMessages($0,anchor:anchor,newer:true,inclusive:true,limit:limit)}
    }
    public func getMessageWindow(peer: String,id: String) throws -> [ChatMessage]? {
        try database.transaction {db in
            let at = Int64(Date().timeIntervalSince1970 * 1000)
            guard let target=try db.query("""
                SELECT messageId,conversationUuid,isMine,content,ext,timestamp,isRead,
                 replyToId,replyToSender,replyToContent,status,isRevoked,isEdited,e.expires_at
                FROM messages m LEFT JOIN message_expiration e ON e.message_id=m.messageId
                WHERE messageId=? AND conversationUuid=? AND (e.expires_at IS NULL OR e.expires_at>?3)
                  AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                     AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?3-r.duration_ms)
                """,values:[.text(id),.text(peer),.integer(at)],map:message).first else {return nil}
            return try seekMessages(db, anchor: target, newer: false, inclusive: false, limit: 25, at: at)
                + seekMessages(db, anchor: target, newer: true, inclusive: true, limit: 26, at: at)
        }
    }

    func hasHistoryAnchor(peer: String, id: String, includeRevoked: Bool = true) throws -> Bool {
        try !database.query("""
            SELECT 1 FROM messages m WHERE conversationUuid=? AND messageId=?
              AND (m.isRevoked=0 OR ?=1)
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?4)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?4-r.duration_ms)
            """, values: [.text(peer), .text(id), .integer(includeRevoked ? 1 : 0),
                          .integer(Int64(Date().timeIntervalSince1970 * 1000))],
            map: { _ in true }).isEmpty
    }
    private func seekMessages(_ db: AppDatabase.Transaction, anchor: ChatMessage, newer: Bool, inclusive: Bool, limit: Int,
                              at: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> [ChatMessage] {
        guard limit>0 else {throw MsgATDatabaseError(operation:"History",detail:"Invalid page size.")}
        let strictComparison = newer ? ">" : "<"
        let comparison = inclusive ? strictComparison + "=" : strictComparison
        let direction=newer ? "ASC" : "DESC"
        let rows=try db.query("""
            SELECT messageId,conversationUuid,isMine,content,ext,timestamp,isRead,
             replyToId,replyToSender,replyToContent,status,isRevoked,isEdited,e.expires_at
            FROM messages m LEFT JOIN message_expiration e ON e.message_id=m.messageId
            WHERE m.conversationUuid=? AND (m.timestamp,m.messageId) \(comparison) (?,?)
              AND (e.expires_at IS NULL OR e.expires_at>?4)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?4-r.duration_ms)
            ORDER BY timestamp \(direction),messageId \(direction) LIMIT ?
            """,values:[.text(anchor.conversationUuid),.integer(anchor.timestamp),
                        .text(anchor.messageId),.integer(at),.integer(Int64(limit))],map:message)
        return newer ? rows : Array(rows.reversed())
    }

    public func insertMessage(_ value: ChatMessage) throws {
        try database.execute("""
            INSERT INTO messages(messageId, conversationUuid, isMine, content, ext, timestamp,
              isRead, replyToId, replyToSender, replyToContent, status, isRevoked, isEdited)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(messageId) DO NOTHING
            """, values: [
                .text(value.messageId), .text(value.conversationUuid), .integer(value.isMine ? 1 : 0),
                .text(value.content), .text(value.ext), .integer(value.timestamp), .integer(value.isRead ? 1 : 0),
                optionalText(value.replyToId), optionalText(value.replyToSender), optionalText(value.replyToContent),
                .integer(Int64(value.status)), .integer(Int64(value.isRevoked)), .integer(Int64(value.isEdited))
            ])
        changed()
    }

    public func updateMessageStatus(messageId: String, status: Int) throws {
        try database.execute(
            "UPDATE messages SET status = ? WHERE messageId = ? AND status < ?",
            values: [.integer(Int64(status)), .text(messageId), .integer(Int64(status))]
        )
        changed()
    }

    public func updateMessageStatusUpTo(targetUuid: String, timestamp: Int64, status: Int) throws {
        try database.execute("""
            UPDATE messages SET status = ?
            WHERE conversationUuid = ? AND timestamp <= ? AND isMine = 1 AND status < ?
            """, values: [.integer(Int64(status)), .text(targetUuid), .integer(timestamp), .integer(Int64(status))])
        changed()
    }

    public func getPendingMessages() throws -> [ChatMessage] {
        try database.query("""
            SELECT messageId, conversationUuid, isMine, content, ext, timestamp, isRead,
                   replyToId, replyToSender, replyToContent, status, isRevoked, isEdited
            FROM messages WHERE status < 2 AND isMine = 1 AND isRevoked = 0 ORDER BY timestamp
            """, map: message)
    }

    public func updateMessageRevoked(messageId: String) throws {
        try database.execute("UPDATE messages SET isRevoked = 1 WHERE messageId = ?", values: [.text(messageId)])
        changed()
    }

    public func updateMessageContent(messageId: String, newContent: String) throws {
        try database.execute(
            "UPDATE messages SET content = ?, isEdited = 1 WHERE messageId = ?",
            values: [.text(newContent), .text(messageId)]
        )
        changed()
    }

    public func clearMessages(conversationUuid: String) throws {
        try database.execute("DELETE FROM messages WHERE conversationUuid = ?", values: [.text(conversationUuid)])
        changed()
    }

    public func deleteMessageById(_ messageId: String) throws {
        try database.execute("DELETE FROM messages WHERE messageId = ?", values: [.text(messageId)])
        changed()
    }

    public func getAllFriendRequests() throws -> [FriendRequest] {
        try database.query("""
            SELECT senderUuid, nickname, avatarPath, peerPublicKey, timestamp
            FROM friend_requests ORDER BY timestamp DESC
            """, map: friendRequest)
    }

    public func getFriendRequestByUuid(_ senderUuid: String) throws -> FriendRequest? {
        try database.query("""
            SELECT senderUuid, nickname, avatarPath, peerPublicKey, timestamp
            FROM friend_requests WHERE senderUuid = ? LIMIT 1
            """, values: [.text(senderUuid)], map: friendRequest).first
    }

    public func insertFriendRequest(_ value: FriendRequest) throws {
        try database.execute("""
            INSERT INTO friend_requests(senderUuid, nickname, avatarPath, peerPublicKey, timestamp)
            VALUES(?, ?, ?, ?, ?)
            ON CONFLICT(senderUuid) DO UPDATE SET nickname=excluded.nickname,
              avatarPath=excluded.avatarPath, peerPublicKey=excluded.peerPublicKey,
              timestamp=excluded.timestamp
            """, values: [
                .text(value.senderUuid), .text(value.nickname), .text(value.avatarPath),
                .text(value.peerPublicKey), .integer(value.timestamp)
            ])
        changed()
    }

    public func deleteFriendRequest(_ senderUuid: String) throws {
        try database.execute("DELETE FROM friend_requests WHERE senderUuid = ?", values: [.text(senderUuid)])
        changed()
    }

    private func conversation(_ statement: OpaquePointer) -> Conversation {
        Conversation(
            uuid: databaseText(statement, 0), alias: databaseText(statement, 1),
            avatarPath: databaseText(statement, 2), peerPublicKey: databaseText(statement, 3)
        )
    }

    private func message(_ statement: OpaquePointer) -> ChatMessage {
        ChatMessage(
            messageId: databaseText(statement, 0), conversationUuid: databaseText(statement, 1),
            isMine: sqlite3_column_int(statement, 2) != 0, content: databaseText(statement, 3),
            ext: databaseText(statement, 4), timestamp: sqlite3_column_int64(statement, 5),
            isRead: sqlite3_column_int(statement, 6) != 0,
            replyToId: databaseOptionalText(statement, 7), replyToSender: databaseOptionalText(statement, 8),
            replyToContent: databaseOptionalText(statement, 9), status: Int(sqlite3_column_int(statement, 10)),
            isRevoked: Int(sqlite3_column_int(statement, 11)), isEdited: Int(sqlite3_column_int(statement, 12)),
            expiresAt: sqlite3_column_count(statement) > 13 && sqlite3_column_type(statement, 13) != SQLITE_NULL
                ? sqlite3_column_int64(statement, 13) : nil
        )
    }

    private func friendRequest(_ statement: OpaquePointer) -> FriendRequest {
        FriendRequest(
            senderUuid: databaseText(statement, 0), nickname: databaseText(statement, 1),
            avatarPath: databaseText(statement, 2), peerPublicKey: databaseText(statement, 3),
            timestamp: sqlite3_column_int64(statement, 4)
        )
    }

    private func optionalText(_ value: String?) -> DatabaseValue {
        value.map(DatabaseValue.text) ?? .null
    }
}
