package org.msgat.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val uuid: String,
    val alias: String
)

@Entity(tableName = "messages")
data class ChatMessage(
    @PrimaryKey val messageId: String,
    val conversationUuid: String,
    val isMine: Boolean,
    val content: String,
    val ext: String,
    val timestamp: Long
)