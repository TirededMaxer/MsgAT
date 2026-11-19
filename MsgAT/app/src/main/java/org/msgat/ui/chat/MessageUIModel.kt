package org.msgat.ui.chat

data class MessageUIModel(
    val id: String,
    val isMine: Boolean,
    val content: String,
    val ext: String,
    val timestamp: Long,
    val senderAvatar: String = "",
    val isRead: Boolean = false,
    val replyToId: String? = null,
    val replyToSender: String? = null,
    val replyToContent: String? = null,
    val status: Int = 1,
    val isRevoked: Boolean = false,
    val isEdited: Boolean = false,
    val attachment: org.msgat.core.media.AttachmentMetadata? = null,
    val forwardInfo: org.msgat.data.db.ForwardedMessageInfo? = null,
    val contactAccount: String? = null,
    val expiresAt: Long? = null
)
