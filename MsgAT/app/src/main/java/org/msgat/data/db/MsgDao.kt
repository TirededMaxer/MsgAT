package org.msgat.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MsgDao {
    @Query("SELECT * FROM conversations")
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE uuid = :uuid LIMIT 1")
    suspend fun getConversationByUuid(uuid: String): Conversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: Conversation)

    @Query("DELETE FROM conversations WHERE uuid = :uuid")
    suspend fun deleteConversation(uuid: String)

    @Query("SELECT * FROM messages WHERE conversationUuid = :uuid ORDER BY timestamp DESC")
    fun getMessages(uuid: String): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage)

    @Query("DELETE FROM messages WHERE conversationUuid = :uuid")
    suspend fun clearMessages(uuid: String)

    @Query("SELECT * FROM conversations")
    suspend fun getConversationList(): List<Conversation>

    @Query("DELETE FROM messages WHERE messageId = :id")
    suspend fun deleteMessageById(id: String)
}