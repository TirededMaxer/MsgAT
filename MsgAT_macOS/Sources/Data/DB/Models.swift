import Foundation

// MARK: - Conversation Model
public struct Conversation: Codable, Identifiable, Sendable {
    public var id: String { uuid }
    public let uuid: String
    public var alias: String
    public var avatarPath: String
    public var peerPublicKey: String

    public init(uuid: String, alias: String, avatarPath: String = "", peerPublicKey: String = "") {
        self.uuid = uuid
        self.alias = alias
        self.avatarPath = avatarPath
        self.peerPublicKey = peerPublicKey
    }

}

// MARK: - ChatMessage Model
public struct ChatMessage: Codable, Identifiable, Equatable, Sendable {
    public var id: String { messageId }
    public let messageId: String
    public let conversationUuid: String
    public let isMine: Bool
    public var content: String
    public let ext: String
    public let timestamp: Int64
    public var isRead: Bool
    public let replyToId: String?
    public let replyToSender: String?
    public let replyToContent: String?
    public var status: Int // 0: Sending, 1: Sent, 2: Delivered, 3: Read
    public var isRevoked: Int
    public var isEdited: Int

    public init(
        messageId: String,
        conversationUuid: String,
        isMine: Bool,
        content: String,
        ext: String,
        timestamp: Int64,
        isRead: Bool = false,
        replyToId: String? = nil,
        replyToSender: String? = nil,
        replyToContent: String? = nil,
        status: Int = 1,
        isRevoked: Int = 0,
        isEdited: Int = 0
    ) {
        self.messageId = messageId
        self.conversationUuid = conversationUuid
        self.isMine = isMine
        self.content = content
        self.ext = ext
        self.timestamp = timestamp
        self.isRead = isRead
        self.replyToId = replyToId
        self.replyToSender = replyToSender
        self.replyToContent = replyToContent
        self.status = status
        self.isRevoked = isRevoked
        self.isEdited = isEdited
    }

}

// MARK: - FriendRequest Model
public struct FriendRequest: Codable, Identifiable, Sendable {
    public var id: String { senderUuid }
    public let senderUuid: String
    public var nickname: String
    public var avatarPath: String
    public var peerPublicKey: String
    public let timestamp: Int64

    public init(
        senderUuid: String,
        nickname: String,
        avatarPath: String = "",
        peerPublicKey: String = "",
        timestamp: Int64
    ) {
        self.senderUuid = senderUuid
        self.nickname = nickname
        self.avatarPath = avatarPath
        self.peerPublicKey = peerPublicKey
        self.timestamp = timestamp
    }

}

// MARK: - Conversation with unread count
public struct ConversationWithUnread: Sendable {
    public let conversation: Conversation
    public let unreadCount: Int

    public init(conversation: Conversation, unreadCount: Int) {
        self.conversation = conversation
        self.unreadCount = unreadCount
    }
}

// MARK: - Wire MessagePayload Model
public struct MessagePayload: Codable, Sendable {
    public let senderUuid: String
    public let fileId: String
    public let total: Int
    public let index: Int
    public let ext: String
    public let data: String
    public let timestamp: Int64
    public let replyToId: String?
    public let replyToSender: String?
    public let replyToContent: String?
    public let action: String?
    public let targetMessageId: String?

    public init(
        senderUuid: String,
        fileId: String,
        total: Int,
        index: Int,
        ext: String,
        data: String,
        timestamp: Int64,
        replyToId: String? = nil,
        replyToSender: String? = nil,
        replyToContent: String? = nil,
        action: String? = nil,
        targetMessageId: String? = nil
    ) {
        self.senderUuid = senderUuid
        self.fileId = fileId
        self.total = total
        self.index = index
        self.ext = ext
        self.data = data
        self.timestamp = timestamp
        self.replyToId = replyToId
        self.replyToSender = replyToSender
        self.replyToContent = replyToContent
        self.action = action
        self.targetMessageId = targetMessageId
    }

    public func toJsonString() -> String? {
        guard let data = try? JSONEncoder().encode(self) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public static func from(jsonString: String) -> MessagePayload? {
        guard let data = jsonString.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(MessagePayload.self, from: data)
    }
}
