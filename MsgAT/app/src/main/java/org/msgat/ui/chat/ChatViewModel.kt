package org.msgat.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.paging.cachedIn
import androidx.paging.map
import org.msgat.core.crypto.CryptoManager
import org.msgat.core.network.NettyClient
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.ChatMessage
import org.msgat.data.db.MsgDao
import org.msgat.model.GhostPacket
import org.msgat.model.MessagePayload
import org.msgat.utils.MediaHelper.getExtensionFromUri
import org.msgat.utils.MediaHelper.uriToBase64Chunks
import java.security.SecureRandom
import java.util.UUID

class ChatViewModel(
    private val targetUuid: String,
    private val msgDao: MsgDao,
    private val nettyClient: NettyClient,
    private val securePrefs: SecurePrefs
) : ViewModel() {

    val syncState = nettyClient.connectionState

    val messagesPagingData: kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<MessageUIModel>> = 
        androidx.paging.Pager(
            config = androidx.paging.PagingConfig(pageSize = 50, enablePlaceholders = false),
            pagingSourceFactory = { msgDao.getPagingMessages(targetUuid) }
        ).flow.map { pagingData ->
            val myAvatar = securePrefs.myAvatarPath ?: ""
            val targetAvatar = msgDao.getConversationByUuid(targetUuid)?.avatarPath ?: ""
            pagingData.map { MessageUIModel(it.messageId, it.isMine, it.content, it.ext, it.timestamp, if (it.isMine) myAvatar else targetAvatar, it.isRead, it.replyToId, it.replyToSender, it.replyToContent, it.status, isRevoked = it.isRevoked == 1, isEdited = it.isEdited == 1) }
        }.cachedIn(viewModelScope)

    private val _alias = MutableStateFlow(targetUuid)
    val alias: StateFlow<String> = _alias.asStateFlow()

    init {
        viewModelScope.launch {
            val convo = msgDao.getConversationByUuid(targetUuid)
            if (convo != null) {
                _alias.value = convo.alias
            }
            msgDao.markMessagesAsRead(targetUuid)
        }
    }

    fun markAsRead() {
        viewModelScope.launch {
            msgDao.markMessagesAsRead(targetUuid)
        }
    }

    // Packages and encrypts a text message payload, then sends it via the network client.
    fun sendMessage(
        text: String,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToContent: String? = null,
        onFail: () -> Unit
    ) {
        val myUuid = securePrefs.myUuid
        if (text.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            val convo = msgDao.getConversationByUuid(targetUuid)
            val peerPublicKeyBase64 = convo?.peerPublicKey ?: ""
            if (peerPublicKeyBase64.isEmpty()) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onFail() }
                return@launch
            }

            try {

                val msgId = UUID.randomUUID().toString()

                val chatMsg = ChatMessage(
                    messageId = msgId,
                    conversationUuid = targetUuid,
                    isMine = true,
                    content = text,
                    ext = "txt",
                    timestamp = System.currentTimeMillis(),
                    isRead = true,
                    replyToId = replyToId,
                    replyToSender = replyToSender,
                    replyToContent = replyToContent,
                    status = 0
                )
                msgDao.insertMessage(chatMsg)

                val payload = MessagePayload(
                    senderUuid = myUuid,
                    fileId = msgId,
                    total = 1,
                    index = 0,
                    ext = "txt",
                    data = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP),
                    timestamp = System.currentTimeMillis(),
                    replyToId = replyToId,
                    replyToSender = replyToSender,
                    replyToContent = replyToContent
                )
                val jsonBytes = payload.toJson().toByteArray(Charsets.UTF_8)

                val iv = ByteArray(16)
                SecureRandom().nextBytes(iv)

                val cipherText: ByteArray
                val authTag: ByteArray
                
                if (peerPublicKeyBase64.isNotEmpty()) {
                    val myPrivateKey = securePrefs.getX25519PrivateKey()
                    val pair = CryptoManager.encryptPayloadECDH(myPrivateKey, peerPublicKeyBase64, iv, jsonBytes)
                    cipherText = pair.first
                    authTag = pair.second
                } else {
                    val pair = CryptoManager.encryptPayloadFallback(myUuid, targetUuid, iv, jsonBytes)
                    cipherText = pair.first
                    authTag = pair.second
                }

                val targetMailboxId = CryptoManager.calculateMailboxId(targetUuid)

                val packet = GhostPacket(
                    type = GhostPacket.TYPE_MESSAGE,
                    targetMailboxId = targetMailboxId,
                    nonceOrIv = iv,
                    encryptedData = cipherText,
                    authTag = authTag
                )

                val success = nettyClient.sendPacket(packet)
                if (success) {
                    msgDao.updateMessageStatus(msgId, 1)
                }

            } catch (e: Exception) {
                Log.e("ChatViewModel", "发送消息失败", e)
            }
        }
    }

    // Processes, encrypts, and transmits media files (such as images) as base64 payloads.
    fun sendMedia(
        context: Context,
        uri: Uri,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToContent: String? = null,
        onFail: () -> Unit
    ) {
        val myUuid = securePrefs.myUuid

        viewModelScope.launch(Dispatchers.IO) {
            val convo = msgDao.getConversationByUuid(targetUuid)
            val peerPublicKeyBase64 = convo?.peerPublicKey ?: ""
            if (peerPublicKeyBase64.isEmpty()) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onFail() }
                return@launch
            }

            val msgId = UUID.randomUUID().toString()

            try {

                val fileSize = org.msgat.utils.MediaHelper.getFileSize(context, uri)
                if (fileSize > 100L * 1024 * 1024) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) { 
                        android.widget.Toast.makeText(context, "文件大小不能超过100MB", android.widget.Toast.LENGTH_SHORT).show()
                        onFail() 
                    }
                    return@launch
                }

                val ext = getExtensionFromUri(context, uri)

                val chunks = uriToBase64Chunks(context, uri)

                if (chunks.isEmpty()) return@launch
                val total = chunks.size

                val localContent = chunks.joinToString("")
                val chatMsg = ChatMessage(
                    messageId = msgId,
                    conversationUuid = targetUuid,
                    isMine = true,
                    content = localContent,
                    ext = ext,
                    timestamp = System.currentTimeMillis(),
                    isRead = true,
                    replyToId = replyToId,
                    replyToSender = replyToSender,
                    replyToContent = replyToContent,
                    status = 0
                )
                msgDao.insertMessage(chatMsg)

                chunks.forEachIndexed { index, chunkData ->
                    val payload = MessagePayload(
                        senderUuid = myUuid,
                        fileId = msgId,
                        total = total,
                        index = index,
                        ext = ext,
                        data = chunkData,
                        timestamp = System.currentTimeMillis(),
                        replyToId = replyToId,
                        replyToSender = replyToSender,
                        replyToContent = replyToContent
                    )
                    val jsonBytes = payload.toJson().toByteArray(Charsets.UTF_8)

                    val iv = ByteArray(16)
                    SecureRandom().nextBytes(iv)
                    
                    val cipherText: ByteArray
                    val authTag: ByteArray
                    
                    if (peerPublicKeyBase64.isNotEmpty()) {
                        val myPrivateKey = securePrefs.getX25519PrivateKey()
                        val pair = CryptoManager.encryptPayloadECDH(myPrivateKey, peerPublicKeyBase64, iv, jsonBytes)
                        cipherText = pair.first
                        authTag = pair.second
                    } else {
                        val pair = CryptoManager.encryptPayloadFallback(myUuid, targetUuid, iv, jsonBytes)
                        cipherText = pair.first
                        authTag = pair.second
                    }
                    
                    val targetMailboxId = CryptoManager.calculateMailboxId(targetUuid)

                    val packet =
                        GhostPacket(GhostPacket.TYPE_MESSAGE, targetMailboxId, iv, cipherText, authTag)

                    val success = nettyClient.sendPacket(packet)
                    if (!success) {
                        throw Exception("Chunk $index 发送失败")
                    }

                    kotlinx.coroutines.delay(50)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "发送媒体消息失败", e)
                msgDao.deleteMessageById(msgId)
                kotlinx.coroutines.withContext(Dispatchers.Main) { onFail() }
            }
        }
    }

    fun sendReadAck(messageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val convo = msgDao.getConversationByUuid(targetUuid) ?: return@launch
            val peerPublicKeyBase64 = convo.peerPublicKey
            if (peerPublicKeyBase64.isEmpty()) return@launch

            try {
                val myUuid = securePrefs.myUuid
                val payload = MessagePayload(
                    senderUuid = myUuid,
                    fileId = UUID.randomUUID().toString(),
                    total = 1,
                    index = 0,
                    ext = "ack_read",
                    data = messageId,
                    timestamp = System.currentTimeMillis()
                )
                val jsonBytes = payload.toJson().toByteArray(Charsets.UTF_8)
                val iv = ByteArray(16)
                SecureRandom().nextBytes(iv)
                val myPrivateKey = securePrefs.getX25519PrivateKey()
                val pair = CryptoManager.encryptPayloadECDH(myPrivateKey, peerPublicKeyBase64, iv, jsonBytes)
                val targetMailboxId = CryptoManager.calculateMailboxId(targetUuid)
                val packet = GhostPacket(GhostPacket.TYPE_MESSAGE, targetMailboxId, iv, pair.first, pair.second)
                nettyClient.sendPacket(packet)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to send ack_read", e)
            }
        }
    }

    fun revokeMessage(messageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            msgDao.updateMessageRevoked(messageId)
            val convo = msgDao.getConversationByUuid(targetUuid) ?: return@launch
            val peerPublicKeyBase64 = convo.peerPublicKey
            if (peerPublicKeyBase64.isEmpty()) return@launch

            try {
                val myUuid = securePrefs.myUuid
                val payload = MessagePayload(
                    senderUuid = myUuid,
                    fileId = UUID.randomUUID().toString(),
                    total = 1,
                    index = 0,
                    ext = "txt",
                    data = "",
                    timestamp = System.currentTimeMillis(),
                    action = "revoke",
                    targetMessageId = messageId
                )
                val jsonBytes = payload.toJson().toByteArray(Charsets.UTF_8)
                val iv = ByteArray(16)
                SecureRandom().nextBytes(iv)
                val myPrivateKey = securePrefs.getX25519PrivateKey()
                val pair = CryptoManager.encryptPayloadECDH(myPrivateKey, peerPublicKeyBase64, iv, jsonBytes)
                val targetMailboxId = CryptoManager.calculateMailboxId(targetUuid)
                val packet = GhostPacket(GhostPacket.TYPE_MESSAGE, targetMailboxId, iv, pair.first, pair.second)
                nettyClient.sendPacket(packet)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to send revoke payload", e)
            }
        }
    }

    fun editMessage(messageId: String, newContent: String) {
        viewModelScope.launch(Dispatchers.IO) {
            msgDao.updateMessageContent(messageId, newContent)
            val convo = msgDao.getConversationByUuid(targetUuid) ?: return@launch
            val peerPublicKeyBase64 = convo.peerPublicKey
            if (peerPublicKeyBase64.isEmpty()) return@launch

            try {
                val myUuid = securePrefs.myUuid
                val payload = MessagePayload(
                    senderUuid = myUuid,
                    fileId = UUID.randomUUID().toString(),
                    total = 1,
                    index = 0,
                    ext = "txt",
                    data = Base64.encodeToString(newContent.toByteArray(), Base64.NO_WRAP),
                    timestamp = System.currentTimeMillis(),
                    action = "edit",
                    targetMessageId = messageId
                )
                val jsonBytes = payload.toJson().toByteArray(Charsets.UTF_8)
                val iv = ByteArray(16)
                SecureRandom().nextBytes(iv)
                val myPrivateKey = securePrefs.getX25519PrivateKey()
                val pair = CryptoManager.encryptPayloadECDH(myPrivateKey, peerPublicKeyBase64, iv, jsonBytes)
                val targetMailboxId = CryptoManager.calculateMailboxId(targetUuid)
                val packet = GhostPacket(GhostPacket.TYPE_MESSAGE, targetMailboxId, iv, pair.first, pair.second)
                nettyClient.sendPacket(packet)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to send edit payload", e)
            }
        }
    }
}