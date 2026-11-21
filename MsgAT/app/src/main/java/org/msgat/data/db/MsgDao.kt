package org.msgat.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class ForwardedMessageInfo(val name: String?,val originalAt: Long)
data class MessageReceiptStatus(val messageId: String, val status: Int)

data class ConversationWithUnreadCount(
    @Embedded val conversation: Conversation,
    val unreadCount:Int,
    val pinOrder:Long=0,val archived:Boolean=false,val muteUntil:Long=0,val manualUnread:Boolean=false,
    val summary:String="",val draftText:String="",val lastAt:Long=0,val lastID:String="",val firstUnreadID:String="",val folderIDs:String="",val hasDraft:Boolean=false,val activityAt:Long=0,val folderPinOrders:String="{}",
    val lastReceiptStatus: Int? = null,
    val summaryKind: String = ""
)

// All history queries bind their snapshot time as a numbered SQLite parameter.
private fun historyWithinLocalRetention(at: Int) = """
    NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
      AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?$at-r.duration_ms)
""".trimIndent()

@Dao
interface MsgDao {
    @RawQuery(observedEntities=[MessagePinClock::class,ChatMessage::class])
    fun observePinnedMessages(query:androidx.sqlite.db.SupportSQLiteQuery):Flow<List<PinnedMessageItem>>

    @RawQuery
    suspend fun readPinState(query: androidx.sqlite.db.SupportSQLiteQuery): Boolean

    suspend fun isMessagePinned(peer: String, local: String, id: String): Boolean =
        readPinState(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT EXISTS(SELECT 1 FROM messages m JOIN message_pin_clocks p ON p.peer=m.conversationUuid AND p.message_id=m.messageId
            WHERE m.conversationUuid=?1 AND m.messageId=?2 AND m.isRevoked=0
              AND p.author=CASE WHEN m.isMine=1 THEN ?3 ELSE ?1 END AND p.addition>p.removed
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?4)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?4-r.duration_ms))
            """, arrayOf(peer, id, local, System.currentTimeMillis())))
    @RawQuery
    suspend fun readForwardTargets(query: androidx.sqlite.db.SupportSQLiteQuery): List<Conversation>

    suspend fun getForwardTargets(): List<Conversation> = readForwardTargets(androidx.sqlite.db.SimpleSQLiteQuery("""
        SELECT c.* FROM conversations c JOIN contact_states s ON s.uuid=c.uuid
        WHERE s.accepted=1 AND s.blocked=0 ORDER BY c.alias COLLATE NOCASE,c.uuid
        """))
    data class MessagePresentation(val forwardName: String?, val originalAt: Long?, val expiresAt: Long?) {
        fun forwardInfo(): ForwardedMessageInfo? =
            originalAt?.let { ForwardedMessageInfo(forwardName, it) }
    }

    data class HistoryRow(
        @Embedded val message: ChatMessage,
        @Embedded val presentation: MessagePresentation?,
        val originalMine: Boolean?,
        val contactAccount: String?
    )

    @RawQuery
    suspend fun readMessagePresentation(query: androidx.sqlite.db.SupportSQLiteQuery): MessagePresentation?

    suspend fun getMessagePresentation(peer: String, id: String): MessagePresentation? =
        readMessagePresentation(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT f.name AS forwardName,f.original_at AS originalAt,e.expires_at AS expiresAt
            FROM messages m LEFT JOIN message_forwards f ON f.message_id=m.messageId
            LEFT JOIN message_expiration e ON e.message_id=m.messageId
            WHERE m.messageId=? AND m.conversationUuid=? AND m.isRevoked=0
            """, arrayOf(id, peer)))

    @RawQuery
    suspend fun readContactCardAccount(query: androidx.sqlite.db.SupportSQLiteQuery): String?

    suspend fun getContactCardAccount(peer: String, id: String): String? = readContactCardAccount(androidx.sqlite.db.SimpleSQLiteQuery(
        """SELECT c.account FROM message_contact_cards c JOIN messages m ON m.messageId=c.message_id
            WHERE m.conversationUuid=? AND m.messageId=? AND m.ext='contact' AND m.isRevoked=0""", arrayOf(peer,id)))

    @RawQuery
    suspend fun readLocationPoint(query: androidx.sqlite.db.SupportSQLiteQuery): org.msgat.core.network.LocationPoint?

    suspend fun getLocationPoint(peer: String, id: String): org.msgat.core.network.LocationPoint? =
        readLocationPoint(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT l.latitude_e6 AS latitudeE6,l.longitude_e6 AS longitudeE6
            FROM message_locations l JOIN messages m ON m.messageId=l.message_id
            WHERE m.conversationUuid=? AND m.messageId=? AND m.ext='location' AND m.isRevoked=0
            """, arrayOf(peer,id)))
    @RawQuery
    suspend fun readMessageOffset(query: androidx.sqlite.db.SupportSQLiteQuery): Int

    suspend fun getMessageOffset(peer: String, id: String, at: Long): Int =
        readMessageOffset(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT COUNT(*) FROM messages m JOIN messages t ON t.messageId=? AND t.conversationUuid=?2
            WHERE m.conversationUuid=?2 AND (m.timestamp,m.messageId)>(t.timestamp,t.messageId)
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?3)
              AND ${historyWithinLocalRetention(3)}
            """, arrayOf(id, peer, at)))

    @RawQuery
    suspend fun readHistoryAnchor(query: androidx.sqlite.db.SupportSQLiteQuery): String?

    suspend fun hasHistoryAnchor(peer: String, id: String, includeRevoked: Boolean): Boolean =
        readHistoryAnchor(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT m.messageId FROM messages m
            WHERE m.conversationUuid=? AND m.messageId=? AND (m.isRevoked=0 OR ?=1)
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?4)
              AND ${historyWithinLocalRetention(4)}
            """, arrayOf(peer, id, if (includeRevoked) 1 else 0, System.currentTimeMillis()))) != null

    suspend fun getMessageAtDate(peer: String, dayStart: Long, nextDayStart: Long, now: Long): String? =
        readHistoryAnchor(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT m.messageId FROM messages m
            WHERE m.conversationUuid=? AND m.timestamp>=? AND m.timestamp<? AND m.isRevoked=0
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?4)
              AND ${historyWithinLocalRetention(4)}
            ORDER BY m.timestamp,m.messageId LIMIT 1
            """, arrayOf(peer, dayStart, nextDayStart, now)))

    suspend fun getFirstUnreadId(peer: String): String? =
        readHistoryAnchor(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT m.messageId FROM messages m
            WHERE m.conversationUuid=? AND m.isMine=0 AND m.isRead=0 AND m.isRevoked=0
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?2)
              AND ${historyWithinLocalRetention(2)}
            ORDER BY m.timestamp,m.messageId LIMIT 1
            """, arrayOf(peer, System.currentTimeMillis())))
    @RawQuery(observedEntities = [Conversation::class, ChatMessage::class])
    fun observeUnreadSummary(query: androidx.sqlite.db.SupportSQLiteQuery): Flow<Int>

    fun observeUnreadCount(peer: String): Flow<Int> =
        observeUnreadSummary(androidx.sqlite.db.SimpleSQLiteQuery(
            "SELECT COALESCE((SELECT unreadCount FROM conversation_summaries WHERE uuid=?),0)", arrayOf(peer)
        ))
    @Query("SELECT * FROM conversations")
    // Retrieves an observable flow of all saved conversations.
    fun getAllConversations(): Flow<List<Conversation>>

    @RawQuery(observedEntities=[Conversation::class,ChatMessage::class,ConversationPreference::class,ConversationFolder::class,ConversationFolderMember::class,ConversationFolderPin::class,DraftIndexRow::class,DraftActivityRow::class])
    fun observeConversationIndex(query:androidx.sqlite.db.SupportSQLiteQuery):Flow<List<ConversationWithUnreadCount>>
    fun getAllConversationsWithUnread(): Flow<List<ConversationWithUnreadCount>> =
        observeConversationIndex(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT s.*, CASE WHEN m.isMine=1 AND m.isRevoked=0 AND m.status BETWEEN -1 AND 3
                THEN m.status END AS lastReceiptStatus,
            CASE
                WHEN m.isRevoked=1 THEN 'revoked'
                WHEN m.ext='contact' THEN 'contact'
                WHEN m.ext='attachment' AND NOT EXISTS (
                    SELECT 1 FROM message_attachments a LEFT JOIN media_objects o ON o.object_id=a.object_id
                    WHERE a.message_id=m.messageId AND
                        (COALESCE(a.caption,'')!='' OR COALESCE(json_extract(o.manifest_json,'$.filename'),'')!='')
                ) THEN 'attachment'
                WHEN m.ext NOT IN ('txt','location','live_location','attachment') THEN 'call'
                ELSE ''
            END AS summaryKind
            FROM conversation_summaries s LEFT JOIN messages m ON m.messageId=s.lastID
            ORDER BY s.pinOrder DESC,s.activityAt DESC,s.uuid
        """.trimIndent()))
    @Query("SELECT * FROM conversation_folders ORDER BY sort_order,id")
    fun observeFolders():Flow<List<ConversationFolder>>

    @Query("SELECT * FROM messages WHERE conversationUuid = :uuid AND isMine = 0 AND isRead = 0 ORDER BY timestamp DESC, messageId DESC LIMIT 1")
    suspend fun getLatestUnreadMessage(uuid: String): ChatMessage?

    @Query("UPDATE messages SET isRead = 1 WHERE conversationUuid = :uuid AND isMine = 0 AND isRead = 0")
    suspend fun markMessagesAsRead(uuid: String)

    @Transaction
    suspend fun markConversationRead(uuid: String): ChatMessage? {
        val unread = getLatestUnreadMessage(uuid) ?: return null
        markMessagesAsRead(uuid)
        return unread
    }

    @Query("SELECT * FROM conversations WHERE uuid = :uuid LIMIT 1")
    // Fetches a specific conversation from the database by its UUID.
    suspend fun getConversationByUuid(uuid: String): Conversation?

    @Query("SELECT * FROM conversations WHERE uuid = :uuid LIMIT 1")
    fun observeConversation(uuid: String): Flow<Conversation?>

    @Query("SELECT COALESCE((SELECT mute_until FROM conversation_preferences WHERE peer = :uuid), 0)")
    fun observeConversationMuteUntil(uuid: String): Flow<Long>

    @Upsert
    // Inserts or updates a conversation record in the database.
    suspend fun insertConversation(conversation: Conversation)

    @Query("DELETE FROM conversations WHERE uuid = :uuid")
    // Deletes a conversation record from the database.
    suspend fun deleteConversation(uuid: String)

    @Query("SELECT * FROM messages WHERE conversationUuid = :uuid ORDER BY timestamp DESC, messageId DESC")
    // Retrieves an observable flow of all messages belonging to a specific conversation.
    fun getMessages(uuid: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM messages WHERE conversationUuid = :uuid")
    suspend fun getMessageListByConversation(uuid: String): List<ChatMessage>

    @RawQuery(observedEntities = [ChatMessage::class])
    fun readPagingMessages(query: androidx.sqlite.db.SupportSQLiteQuery): androidx.paging.PagingSource<Int, HistoryRow>

    fun getPagingMessages(uuid: String, at: Long): androidx.paging.PagingSource<Int, HistoryRow> =
        readPagingMessages(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT m.*, source.isMine AS originalMine, f.name AS forwardName, f.original_at AS originalAt,
                   e.expires_at AS expiresAt, c.account AS contactAccount
            FROM messages m
            LEFT JOIN messages source ON source.messageId=m.replyToId AND source.conversationUuid=m.conversationUuid
            LEFT JOIN message_forwards f ON f.message_id=m.messageId AND m.isRevoked=0
            LEFT JOIN message_expiration e ON e.message_id=m.messageId
            LEFT JOIN message_contact_cards c ON c.message_id=m.messageId AND m.ext='contact' AND m.isRevoked=0
            WHERE m.conversationUuid=?1 AND (e.expires_at IS NULL OR e.expires_at>?2)
              AND ${historyWithinLocalRetention(2)}
            ORDER BY m.timestamp DESC,m.messageId DESC
            """, arrayOf(uuid, at)))

    suspend fun getNewestMessageId(uuid: String): String? =
        readHistoryAnchor(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT m.messageId FROM messages m WHERE conversationUuid=?
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?2)
              AND ${historyWithinLocalRetention(2)}
            ORDER BY timestamp DESC,messageId DESC LIMIT 1
            """, arrayOf(uuid, System.currentTimeMillis())))

    @RawQuery
    suspend fun readComposerMessage(query: androidx.sqlite.db.SupportSQLiteQuery): ChatMessage?

    suspend fun latestEditableMessage(peer: String): ChatMessage? =
        readComposerMessage(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT m.* FROM messages m
            WHERE m.conversationUuid=? AND m.isMine=1 AND m.isRevoked=0
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND m.status>=2 AND m.timestamp<=?-r.duration_ms)
              AND (m.ext='txt' OR (m.ext='attachment'
                  AND EXISTS(SELECT 1 FROM message_attachments a WHERE a.message_id=m.messageId)
                  AND NOT EXISTS(SELECT 1 FROM message_stickers s WHERE s.message_id=m.messageId)))
            ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 1
            """, arrayOf(peer, System.currentTimeMillis())))

    suspend fun adjacentReplyMessage(peer: String, anchorTime: Long?, anchorId: String?, newer: Boolean): ChatMessage? {
        val comparison = if (newer) ">" else "<"
        val order = if (newer) "ASC" else "DESC"
        val now = System.currentTimeMillis()
        return readComposerMessage(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT m.* FROM messages m
            WHERE m.conversationUuid=? AND m.isRevoked=0
              AND (? IS NULL OR (m.timestamp,m.messageId) $comparison (?,?))
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?-r.duration_ms)
            ORDER BY m.timestamp $order,m.messageId $order LIMIT 1
            """, arrayOf(peer, anchorTime, anchorTime, anchorId, now, now)))
    }

    @Query("SELECT messageId,status FROM messages WHERE conversationUuid=:peer AND isMine=1 AND isRevoked=0 AND messageId IN (:ids)")
    fun observeMessageReceipts(peer: String, ids: List<String>): Flow<List<MessageReceiptStatus>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    // Inserts a new chat message into the database.
    suspend fun insertMessage(message: ChatMessage)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId AND status < :status")
    suspend fun updateMessageStatus(messageId: String, status: Int)

    @Query("SELECT * FROM messages WHERE messageId = :id LIMIT 1")
    suspend fun getMessageById(id: String): ChatMessage?

    @Query("SELECT * FROM messages WHERE conversationUuid = :peer AND messageId = :id LIMIT 1")
    fun observeMessage(peer: String, id: String): Flow<ChatMessage?>

    @Query("SELECT content FROM messages WHERE conversationUuid=:peer AND messageId IN (:ids) AND ext='txt' AND isRevoked=0 ORDER BY timestamp,messageId")
    suspend fun getSelectedMessageText(peer: String, ids: List<String>): List<String>

    @Query("UPDATE messages SET status = :status WHERE conversationUuid = :targetUuid AND timestamp <= :timestamp AND isMine = 1 AND status < :status")
    suspend fun updateMessageStatusUpTo(targetUuid: String, timestamp: Long, status: Int)

    @Query("SELECT * FROM messages WHERE status < 2 AND isMine = 1 AND isRevoked = 0 ORDER BY timestamp")
    suspend fun getPendingMessages(): List<ChatMessage>

    @Query("UPDATE messages SET isRevoked = 1 WHERE messageId = :messageId")
    suspend fun updateMessageRevoked(messageId: String)

    @Query("UPDATE messages SET content = :newContent, isEdited = 1 WHERE messageId = :messageId")
    suspend fun updateMessageContent(messageId: String, newContent: String)

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
