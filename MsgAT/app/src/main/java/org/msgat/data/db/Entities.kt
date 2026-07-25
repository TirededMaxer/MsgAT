package org.msgat.data.db

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Keep
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val uuid: String,
    val alias: String,
    val avatarPath: String = "",
    val peerPublicKey: String = ""
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationUuid", "timestamp"]),
        Index(value = ["isRead"])
    ]
)
@Keep
data class ChatMessage(
    @PrimaryKey val messageId: String,
    val conversationUuid: String,
    val isMine: Boolean,
    val content: String,
    val ext: String,
    val timestamp: Long,
    @androidx.room.ColumnInfo(defaultValue = "0") val isRead: Boolean = false,
    val replyToId: String? = null,
    val replyToSender: String? = null,
    val replyToContent: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "1") val status: Int = 1,
    @androidx.room.ColumnInfo(defaultValue = "0") val isRevoked: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val isEdited: Int = 0
)

@Entity(tableName = "friend_requests")
@Keep
data class FriendRequest(
    @PrimaryKey val senderUuid: String,
    val nickname: String,
    val avatarPath: String = "",
    val peerPublicKey: String = "",
    val timestamp: Long
)