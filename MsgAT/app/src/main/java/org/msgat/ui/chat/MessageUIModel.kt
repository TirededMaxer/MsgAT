package org.msgat.ui.chat

data class MessageUIModel(
    val id: String,
    val isMine: Boolean,
    val content: String,
    val ext: String,
    val timestamp: Long,
    val senderAvatar: String = "",
    val isRead: Boolean = false
)