package org.msgat.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val uuid: String,
    val alias: String,
    val avatarPath: String = "",
    val peerPublicKey: String = ""
)

@Entity(tableName = "messages")
data class ChatMessage(
    @PrimaryKey val messageId: String,
    val conversationUuid: String,
    val isMine: Boolean,
    val content: String,
    val ext: String,
    val timestamp: Long,
    @androidx.room.ColumnInfo(defaultValue = "0") val isRead: Boolean = false
)

@Entity(tableName = "friend_requests")
data class FriendRequest(
    @PrimaryKey val senderUuid: String,
    val nickname: String,
    val avatarPath: String = "",
    val peerPublicKey: String = "",
    val timestamp: Long
)