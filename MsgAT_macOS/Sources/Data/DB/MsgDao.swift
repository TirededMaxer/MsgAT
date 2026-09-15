import Foundation
import SQLCipher

public final class MsgDao: Sendable {
    public static let shared = MsgDao()
    public static let didChangeNotification = Notification.Name("org.msgat.database.didChange")

    private let database = AppDatabase.shared
    private init() {}

    private func changed() {
        NotificationCenter.default.post(name: Self.didChangeNotification, object: nil)
    }

    public func getAllConversations() throws -> [Conversation] {
        try database.query(
            "SELECT uuid, alias, avatarPath, peerPublicKey FROM conversations ORDER BY alias COLLATE NOCASE",
            map: conversation
        )
    }

    public func getAllConversationsWithUnread() throws -> [ConversationWithUnread] {
        try database.query("""
            SELECT c.uuid, c.alias, c.avatarPath, c.peerPublicKey,
              (SELECT COUNT(*) FROM messages m
               WHERE m.conversationUuid = c.uuid AND m.isMine = 0 AND m.isRead = 0)
            FROM conversations c ORDER BY c.alias COLLATE NOCASE
            """) { statement in
                ConversationWithUnread(
                    conversation: conversation(statement),
                    unreadCount: Int(sqlite3_column_int64(statement, 4))
                )
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

    public func markMessagesAsRead(conversationUuid: String) throws {
        try database.execute(
            "UPDATE messages SET isRead = 1 WHERE conversationUuid = ? AND isMine = 0 AND isRead = 0",
            values: [.text(conversationUuid)]
        )
        changed()
    }

    public func getMessages(conversationUuid: String, limit: Int = 100, offset: Int = 0) throws -> [ChatMessage] {
        let rows = try database.query("""
            SELECT messageId, conversationUuid, isMine, content, ext, timestamp, isRead,
                   replyToId, replyToSender, replyToContent, status, isRevoked, isEdited
            FROM messages WHERE conversationUuid = ?
            ORDER BY timestamp DESC LIMIT ? OFFSET ?
            """, values: [.text(conversationUuid), .integer(Int64(limit)), .integer(Int64(offset))], map: message)
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
                   replyToId, replyToSender, replyToContent, status, isRevoked, isEdited
            FROM messages WHERE messageId = ? LIMIT 1
            """, values: [.text(messageId)], map: message).first
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
            isRevoked: Int(sqlite3_column_int(statement, 11)), isEdited: Int(sqlite3_column_int(statement, 12))
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
