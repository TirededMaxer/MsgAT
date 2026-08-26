package org.msgat.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class DatabaseManager(private val dbFile: File) : MsgDao {

    private val dbUrl = "jdbc:sqlite:${dbFile.absolutePath}"
    private val changeNotifier = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    init {
        if (!dbFile.parentFile.exists()) {
            dbFile.parentFile.mkdirs()
        }
        initTables()
    }

    private fun getConnection(): Connection {
        return DriverManager.getConnection(dbUrl)
    }

    private fun initTables() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                // Enable WAL mode and foreign keys
                stmt.execute("PRAGMA journal_mode=WAL;")
                stmt.execute("PRAGMA synchronous=NORMAL;")

                // Table: conversations
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS conversations (
                        uuid TEXT PRIMARY KEY,
                        alias TEXT NOT NULL,
                        avatarPath TEXT NOT NULL DEFAULT '',
                        peerPublicKey TEXT NOT NULL DEFAULT ''
                    );
                """)

                // Table: messages
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                        messageId TEXT PRIMARY KEY,
                        conversationUuid TEXT NOT NULL,
                        isMine INTEGER NOT NULL,
                        content TEXT NOT NULL,
                        ext TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        isRead INTEGER NOT NULL DEFAULT 0,
                        replyToId TEXT,
                        replyToSender TEXT,
                        replyToContent TEXT,
                        status INTEGER NOT NULL DEFAULT 1,
                        isRevoked INTEGER NOT NULL DEFAULT 0,
                        isEdited INTEGER NOT NULL DEFAULT 0
                    );
                """)
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_convo_time ON messages(conversationUuid, timestamp);")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_is_read ON messages(isRead);")

                // Table: friend_requests
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS friend_requests (
                        senderUuid TEXT PRIMARY KEY,
                        nickname TEXT NOT NULL,
                        avatarPath TEXT NOT NULL DEFAULT '',
                        peerPublicKey TEXT NOT NULL DEFAULT '',
                        timestamp INTEGER NOT NULL
                    );
                """)
            }
        }
    }

    private fun notifyDataChanged() {
        changeNotifier.tryEmit(Unit)
    }

    override fun getAllConversations(): Flow<List<Conversation>> = flow {
        emit(getConversationList())
        changeNotifier.collect {
            emit(getConversationList())
        }
    }.flowOn(Dispatchers.IO)

    override fun getAllConversationsWithUnread(): Flow<List<ConversationWithUnreadCount>> = flow {
        emit(fetchConversationsWithUnread())
        changeNotifier.collect {
            emit(fetchConversationsWithUnread())
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchConversationsWithUnread(): List<ConversationWithUnreadCount> {
        val list = mutableListOf<ConversationWithUnreadCount>()
        val sql = """
            SELECT c.uuid, c.alias, c.avatarPath, c.peerPublicKey,
            (SELECT COUNT(*) FROM messages m WHERE m.conversationUuid = c.uuid AND m.isMine = 0 AND m.isRead = 0) as unreadCount 
            FROM conversations c
        """
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                while (rs.next()) {
                    val convo = Conversation(
                        uuid = rs.getString("uuid"),
                        alias = rs.getString("alias"),
                        avatarPath = rs.getString("avatarPath") ?: "",
                        peerPublicKey = rs.getString("peerPublicKey") ?: ""
                    )
                    val unread = rs.getInt("unreadCount")
                    list.add(ConversationWithUnreadCount(convo, unread))
                }
            }
        }
        return list
    }

    override suspend fun markMessagesAsRead(uuid: String) = withContext(Dispatchers.IO) {
        val sql = "UPDATE messages SET isRead = 1 WHERE conversationUuid = ? AND isMine = 0 AND isRead = 0"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid)
                stmt.executeUpdate()
            }
        }
        notifyDataChanged()
    }

    override suspend fun getConversationByUuid(uuid: String): Conversation? = withContext(Dispatchers.IO) {
        val sql = "SELECT * FROM conversations WHERE uuid = ? LIMIT 1"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    Conversation(
                        uuid = rs.getString("uuid"),
                        alias = rs.getString("alias"),
                        avatarPath = rs.getString("avatarPath") ?: "",
                        peerPublicKey = rs.getString("peerPublicKey") ?: ""
                    )
                } else null
            }
        }
    }

    override suspend fun insertConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val sql = "INSERT OR REPLACE INTO conversations (uuid, alias, avatarPath, peerPublicKey) VALUES (?, ?, ?, ?)"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, conversation.uuid)
                stmt.setString(2, conversation.alias)
                stmt.setString(3, conversation.avatarPath)
                stmt.setString(4, conversation.peerPublicKey)
                stmt.executeUpdate()
            }
        }
        notifyDataChanged()
    }

    override suspend fun deleteConversation(uuid: String) = withContext(Dispatchers.IO) {
        val sql = "DELETE FROM conversations WHERE uuid = ?"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid)
                stmt.executeUpdate()
            }
        }
        notifyDataChanged()
    }

    override fun getMessages(uuid: String): Flow<List<ChatMessage>> = flow {
        emit(fetchMessages(uuid))
        changeNotifier.collect {
            emit(fetchMessages(uuid))
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchMessages(uuid: String): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        val sql = "SELECT * FROM messages WHERE conversationUuid = ? ORDER BY timestamp DESC"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(rsToChatMessage(rs))
                }
            }
        }
        return list
    }

    private fun rsToChatMessage(rs: ResultSet): ChatMessage {
        return ChatMessage(
            messageId = rs.getString("messageId"),
            conversationUuid = rs.getString("conversationUuid"),
            isMine = rs.getInt("isMine") == 1,
            content = rs.getString("content"),
            ext = rs.getString("ext"),
            timestamp = rs.getLong("timestamp"),
            isRead = rs.getInt("isRead") == 1,
            replyToId = rs.getString("replyToId"),
            replyToSender = rs.getString("replyToSender"),
            replyToContent = rs.getString("replyToContent"),
            status = rs.getInt("status"),
            isRevoked = rs.getInt("isRevoked"),
            isEdited = rs.getInt("isEdited")
        )
    }

    override suspend fun getMessageListByConversation(uuid: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ChatMessage>()
        val sql = "SELECT * FROM messages WHERE conversationUuid = ?"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(rsToChatMessage(rs))
                }
            }
        }
        list
    }

    override suspend fun insertMessage(message: ChatMessage) = withContext(Dispatchers.IO) {
        val sql = """
            INSERT OR REPLACE INTO messages 
            (messageId, conversationUuid, isMine, content, ext, timestamp, isRead, replyToId, replyToSender, replyToContent, status, isRevoked, isEdited)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, message.messageId)
                stmt.setString(2, message.conversationUuid)
                stmt.setInt(3, if (message.isMine) 1 else 0)
                stmt.setString(4, message.content)
                stmt.setString(5, message.ext)
                stmt.setLong(6, message.timestamp)
                stmt.setInt(7, if (message.isRead) 1 else 0)
                stmt.setString(8, message.replyToId)
                stmt.setString(9, message.replyToSender)
                stmt.setString(10, message.replyToContent)
                stmt.setInt(11, message.status)
                stmt.setInt(12, message.isRevoked)
                stmt.setInt(13, message.isEdited)
                stmt.executeUpdate()
            }
        }
        notifyDataChanged()
    }

    override suspend fun updateMessageStatus(messageId: String, status: Int) = withContext(Dispatchers.IO) {
        val sql = "UPDATE messages SET status = ? WHERE messageId = ? AND status < ?"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, status)
                stmt.setString(2, messageId)
                stmt.setInt(3, status)
                stmt.executeUpdate()
            }
        }
        notifyDataChanged()
    }

    override suspend fun getMessageById(id: String): ChatMessage? = withContext(Dispatchers.IO) {
        val sql = "SELECT * FROM messages WHERE messageId = ? LIMIT 1"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                val rs = stmt.executeQuery()
                if (rs.next()) rsToChatMessage(rs) else null
            }
        }
    }

    override suspend fun updateMessageStatusUpTo(targetUuid: String, timestamp: Long, status: Int) = withContext(Dispatchers.IO) {
        val sql = "UPDATE messages SET status = ? WHERE conversationUuid = ? AND timestamp <= ? AND isMine = 1 AND status < ?"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, status)
                stmt.setString(2, targetUuid)
                stmt.setLong(3, timestamp)
                stmt.setInt(4, status)
                stmt.executeUpdate()
            }
        }
        notifyDataChanged()
    }

    override suspend fun getPendingMessages(): List<ChatMessage> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ChatMessage>()
        val sql = "SELECT * FROM messages WHERE status = 0 AND isMine = 1"
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                while (rs.next()) {
                    list.add(rsToChatMessage(rs))
                }
            }
        }
        list
    }

    override suspend fun updateMessageRevoked(messageId: String) = withContext(Dispatchers.IO) {
        val sql = "UPDATE messages SET isRevoked = 1 WHERE messageId = ?"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, messageId)
                stmt.executeUpdate()
            }
        }
        notifyDataChanged()
    }

    override suspend fun updateMessageContent(messageId: String, newContent: String) = withContext(Dispatchers.IO) {
        val sql = "UPDATE messages SET content = ?, isEdited = 1 WHERE messageId = ?"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, newContent)
                stmt.setString(2, messageId)
                stmt.executeUpdate()
            }
        }
        notifyDataChanged()
    }

    override suspend fun clearMessages(uuid: String) = withContext(Dispatchers.IO) {
        val sql = "DELETE FROM messages WHERE conversationUuid = ?"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid)
                stmt.executeUpdate()
            }
        }
        notifyDataChanged()
    }

    override suspend fun getConversationList(): List<Conversation> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Conversation>()
        val sql = "SELECT * FROM conversations"
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                while (rs.next()) {
                    list.add(
                        Conversation(
                            uuid = rs.getString("uuid"),
                            alias = rs.getString("alias"),
                            avatarPath = rs.getString("avatarPath") ?: "",
                            peerPublicKey = rs.getString("peerPublicKey") ?: ""
                        )
                    )
                }
            }
        }
        list
    }

    override suspend fun deleteMessageById(id: String) = withContext(Dispatchers.IO) {
        val sql = "DELETE FROM messages WHERE messageId = ?"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate()
            }
        }
        notifyDataChanged()
    }

    override fun getAllFriendRequests(): Flow<List<FriendRequest>> = flow {
        emit(fetchFriendRequests())
        changeNotifier.collect {
            emit(fetchFriendRequests())
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchFriendRequests(): List<FriendRequest> {
        val list = mutableListOf<FriendRequest>()
        val sql = "SELECT * FROM friend_requests ORDER BY timestamp DESC"
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                while (rs.next()) {
                    list.add(
                        FriendRequest(
                            senderUuid = rs.getString("senderUuid"),
                            nickname = rs.getString("nickname"),
                            avatarPath = rs.getString("avatarPath") ?: "",
                            peerPublicKey = rs.getString("peerPublicKey") ?: "",
                            timestamp = rs.getLong("timestamp")
                        )
                    )
                }
            }
        }
        return list
    }

    override suspend fun insertFriendRequest(request: FriendRequest) = withContext(Dispatchers.IO) {
        val sql = "INSERT OR REPLACE INTO friend_requests (senderUuid, nickname, avatarPath, peerPublicKey, timestamp) VALUES (?, ?, ?, ?, ?)"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, request.senderUuid)
                stmt.setString(2, request.nickname)
                stmt.setString(3, request.avatarPath)
                stmt.setString(4, request.peerPublicKey)
                stmt.setLong(5, request.timestamp)
                stmt.executeUpdate()
            }
        }
        notifyDataChanged()
    }

    override suspend fun getFriendRequestByUuid(senderUuid: String): FriendRequest? = withContext(Dispatchers.IO) {
        val sql = "SELECT * FROM friend_requests WHERE senderUuid = ? LIMIT 1"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, senderUuid)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    FriendRequest(
                        senderUuid = rs.getString("senderUuid"),
                        nickname = rs.getString("nickname"),
                        avatarPath = rs.getString("avatarPath") ?: "",
                        peerPublicKey = rs.getString("peerPublicKey") ?: "",
                        timestamp = rs.getLong("timestamp")
                    )
                } else null
            }
        }
    }

    override suspend fun deleteFriendRequest(senderUuid: String) = withContext(Dispatchers.IO) {
        val sql = "DELETE FROM friend_requests WHERE senderUuid = ?"
        getConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, senderUuid)
                stmt.executeUpdate()
            }
        }
        notifyDataChanged()
    }

    override suspend fun clearAllConversations() = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.createStatement().use { it.executeUpdate("DELETE FROM conversations") }
        }
        notifyDataChanged()
    }

    override suspend fun clearAllMessages() = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.createStatement().use { it.executeUpdate("DELETE FROM messages") }
        }
        notifyDataChanged()
    }

    override suspend fun clearAllFriendRequests() = withContext(Dispatchers.IO) {
        getConnection().use { conn ->
            conn.createStatement().use { it.executeUpdate("DELETE FROM friend_requests") }
        }
        notifyDataChanged()
    }
}
