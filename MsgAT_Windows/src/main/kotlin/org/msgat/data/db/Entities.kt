package org.msgat.data.db

data class Conversation(
    val uuid: String,
    val alias: String,
    val avatarPath: String = "",
    val peerPublicKey: String = ""
)

data class ChatMessage(
    val messageId: String,
    val conversationUuid: String,
    val isMine: Boolean,
    val content: String,
    val ext: String,
    val timestamp: Long,
    val isRead: Boolean = false,
    val replyToId: String? = null,
    val replyToSender: String? = null,
    val replyToContent: String? = null,
    val status: Int = 1,
    val isRevoked: Int = 0,
    val isEdited: Int = 0
)

data class FriendRequest(
    val senderUuid: String,
    val nickname: String,
    val avatarPath: String = "",
    val peerPublicKey: String = "",
    val timestamp: Long
)

data class ConversationWithUnreadCount(
    val conversation: Conversation,
    val unreadCount: Int
)
