package org.msgat.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class ConversationWithUnreadCount(
    @Embedded val conversation: Conversation,
    val unreadCount: Int
)

@Dao
interface MsgDao {
    @Query("SELECT * FROM conversations")
    // Retrieves an observable flow of all saved conversations.
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("""
        SELECT c.*, 
        (SELECT COUNT(*) FROM messages m WHERE m.conversationUuid = c.uuid AND m.isMine = 0 AND m.isRead = 0) as unreadCount 
        FROM conversations c
    """)
    fun getAllConversationsWithUnread(): Flow<List<ConversationWithUnreadCount>>

    @Query("UPDATE messages SET isRead = 1 WHERE conversationUuid = :uuid AND isMine = 0")
    suspend fun markMessagesAsRead(uuid: String)

    @Query("SELECT * FROM conversations WHERE uuid = :uuid LIMIT 1")
    // Fetches a specific conversation from the database by its UUID.
    suspend fun getConversationByUuid(uuid: String): Conversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    // Inserts or updates a conversation record in the database.
    suspend fun insertConversation(conversation: Conversation)

    @Query("DELETE FROM conversations WHERE uuid = :uuid")
    // Deletes a conversation record from the database.
    suspend fun deleteConversation(uuid: String)

    @Query("SELECT * FROM messages WHERE conversationUuid = :uuid ORDER BY timestamp DESC")
    // Retrieves an observable flow of all messages belonging to a specific conversation.
    fun getMessages(uuid: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM messages WHERE conversationUuid = :uuid ORDER BY timestamp DESC")
    // Retrieves a PagingSource of messages belonging to a specific conversation.
    fun getPagingMessages(uuid: String): androidx.paging.PagingSource<Int, ChatMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    // Inserts a new chat message into the database.
    suspend fun insertMessage(message: ChatMessage)

    @Query("DELETE FROM messages WHERE conversationUuid = :uuid")
    // Deletes all messages associated with a specific conversation UUID.
    suspend fun clearMessages(uuid: String)

    @Query("SELECT * FROM conversations")
    // Fetches a static list of all conversations currently in the database.
    suspend fun getConversationList(): List<Conversation>

    @Query("DELETE FROM messages WHERE messageId = :id")
    // Deletes a specific chat message by its unique database ID.
    suspend fun deleteMessageById(id: String)

    @Query("SELECT * FROM friend_requests ORDER BY timestamp DESC")
    // Retrieves an observable flow of all pending friend requests.
    fun getAllFriendRequests(): Flow<List<FriendRequest>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    // Inserts a new incoming friend request into the database.
    suspend fun insertFriendRequest(request: FriendRequest)

    @Query("SELECT * FROM friend_requests WHERE senderUuid = :senderUuid LIMIT 1")
    // Fetches a specific friend request from the database by sender UUID.
    suspend fun getFriendRequestByUuid(senderUuid: String): FriendRequest?

    @Query("DELETE FROM friend_requests WHERE senderUuid = :senderUuid")
    // Deletes a specific friend request from the database by sender UUID.
    suspend fun deleteFriendRequest(senderUuid: String)

    @Query("DELETE FROM conversations")
    // Wipes all conversations from the database.
    suspend fun clearAllConversations()

    @Query("DELETE FROM messages")
    // Wipes all messages from the database.
    suspend fun clearAllMessages()

    @Query("DELETE FROM friend_requests")
    // Wipes all friend requests from the database.
    suspend fun clearAllFriendRequests()

}