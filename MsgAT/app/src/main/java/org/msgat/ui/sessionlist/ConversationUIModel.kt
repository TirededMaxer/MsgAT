package org.msgat.ui.sessionlist

data class ConversationUIModel(
    val uuid: String,
    val alias: String,
    val avatarPath: String = "",
    val hasUnread: Boolean = false
)
