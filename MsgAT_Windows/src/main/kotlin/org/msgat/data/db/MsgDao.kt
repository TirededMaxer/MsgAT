package org.msgat.data.db

import kotlinx.coroutines.flow.Flow

interface MsgDao {
    fun getAllConversations(): Flow<List<Conversation>>
    fun getAllConversationsWithUnread(): Flow<List<ConversationWithUnreadCount>>
    suspend fun markMessagesAsRead(uuid: String)
    suspend fun getConversationByUuid(uuid: String): Conversation?
    suspend fun insertConversation(conversation: Conversation)
    suspend fun deleteConversation(uuid: String)
    fun getMessages(uuid: String): Flow<List<ChatMessage>>
    suspend fun getMessageListByConversation(uuid: String): List<ChatMessage>
    suspend fun insertMessage(message: ChatMessage)
    suspend fun updateMessageStatus(messageId: String, status: Int)
    suspend fun getMessageById(id: String): ChatMessage?
    suspend fun updateMessageStatusUpTo(targetUuid: String, timestamp: Long, status: Int)
    suspend fun getPendingMessages(): List<ChatMessage>
    suspend fun updateMessageRevoked(messageId: String)
    suspend fun updateMessageContent(messageId: String, newContent: String)
    suspend fun clearMessages(uuid: String)
    suspend fun getConversationList(): List<Conversation>
    suspend fun deleteMessageById(id: String)
    fun getAllFriendRequests(): Flow<List<FriendRequest>>
    suspend fun insertFriendRequest(request: FriendRequest)
    suspend fun getFriendRequestByUuid(senderUuid: String): FriendRequest?
    suspend fun deleteFriendRequest(senderUuid: String)
    suspend fun clearAllConversations()
    suspend fun clearAllMessages()
    suspend fun clearAllFriendRequests()
}
