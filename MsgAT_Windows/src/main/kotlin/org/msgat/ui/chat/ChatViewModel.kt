package org.msgat.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.msgat.core.crypto.CryptoManager
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.core.media.MediaStorageManager
import org.msgat.core.network.NettyClient
import org.msgat.data.db.ChatMessage
import org.msgat.data.db.MsgDao
import org.msgat.model.GhostPacket
import org.msgat.model.MessagePayload
import org.msgat.model.SyncState
import org.msgat.utils.MediaHelper
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class ChatViewModel(
    val targetUuid: String,
    private val msgDao: MsgDao,
    private val nettyClient: NettyClient,
    private val securePrefs: SecurePrefs,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    val syncState: StateFlow<SyncState> = nettyClient.connectionState

    val messages: StateFlow<List<MessageUIModel>> = msgDao.getMessages(targetUuid)
        .map { list ->
            val myAvatar = securePrefs.myAvatarPath ?: ""
            val targetAvatar = msgDao.getConversationByUuid(targetUuid)?.avatarPath ?: ""
            list.map {
                MessageUIModel(
                    id = it.messageId,
                    isMine = it.isMine,
                    content = it.content,
                    ext = it.ext,
                    timestamp = it.timestamp,
                    senderAvatar = if (it.isMine) myAvatar else targetAvatar,
                    isRead = it.isRead,
                    replyToId = it.replyToId,
                    replyToSender = it.replyToSender,
                    replyToContent = it.replyToContent,
                    status = it.status,
                    isRevoked = it.isRevoked == 1,
                    isEdited = it.isEdited == 1
                )
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _alias = MutableStateFlow(targetUuid)
    val alias: StateFlow<String> = _alias.asStateFlow()

    init {
        scope.launch {
            val convo = msgDao.getConversationByUuid(targetUuid)
            if (convo != null) {
                _alias.value = convo.alias
            }
            msgDao.markMessagesAsRead(targetUuid)
        }
    }

    fun markAsRead() {
        scope.launch {
            msgDao.markMessagesAsRead(targetUuid)
        }
    }

    // Packages and encrypts a text message payload, then sends it via the network client.
    fun sendMessage(
        text: String,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToContent: String? = null,
        onFail: () -> Unit = {}
    ) {
        val myUuid = securePrefs.myUuid
        if (text.isBlank()) return

        scope.launch(Dispatchers.IO) {
            val convo = msgDao.getConversationByUuid(targetUuid)
            val peerPublicKeyBase64 = convo?.peerPublicKey ?: ""
            if (peerPublicKeyBase64.isEmpty()) {
                withContext(Dispatchers.Main) { onFail() }
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
                    data = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)),
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
                println("Failed to send message: ${e.message}")
            }
        }
    }

    // Processes, encrypts, and transmits media files as base64 payloads.
    fun sendMedia(
        file: File,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToContent: String? = null,
        onFail: () -> Unit = {}
    ) {
        val myUuid = securePrefs.myUuid

        scope.launch(Dispatchers.IO) {
            val convo = msgDao.getConversationByUuid(targetUuid)
            val peerPublicKeyBase64 = convo?.peerPublicKey ?: ""
            if (peerPublicKeyBase64.isEmpty()) {
                withContext(Dispatchers.Main) { onFail() }
                return@launch
            }

            val msgId = UUID.randomUUID().toString()

            try {
                if (file.length() > 100L * 1024 * 1024) {
                    println("File size exceeds 100MB limit.")
                    withContext(Dispatchers.Main) { onFail() }
                    return@launch
                }

                val ext = MediaHelper.getExtension(file)
                val savedFile = MediaStorageManager.saveMediaFromFile(msgId, ext, file)

                val chatMsg = ChatMessage(
                    messageId = msgId,
                    conversationUuid = targetUuid,
                    isMine = true,
                    content = savedFile.absolutePath,
                    ext = ext,
                    timestamp = System.currentTimeMillis(),
                    isRead = true,
                    replyToId = replyToId,
                    replyToSender = replyToSender,
                    replyToContent = replyToContent,
                    status = 0
                )
                msgDao.insertMessage(chatMsg)

                val chunks = MediaHelper.fileToBase64Chunks(file)
                if (chunks.isEmpty()) return@launch
                val total = chunks.size

                var allChunksSent = true
                for (index in 0 until total) {
                    val chunkData = chunks[index]
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
                    val packet = GhostPacket(GhostPacket.TYPE_MESSAGE, targetMailboxId, iv, cipherText, authTag)

                    val success = nettyClient.sendPacket(packet)
                    if (!success) {
                        allChunksSent = false
                        break
                    }

                    if (index < total - 1) {
                        delay(50)
                    }
                }

                if (allChunksSent) {
                    msgDao.updateMessageStatus(msgId, 1)
                }
            } catch (e: Exception) {
                println("Failed to send media: ${e.message}")
            }
        }
    }

    fun sendReadAck(messageId: String) {
        scope.launch(Dispatchers.IO) {
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
                println("Failed to send ack_read: ${e.message}")
            }
        }
    }

    fun revokeMessage(messageId: String) {
        scope.launch(Dispatchers.IO) {
            val targetMsg = msgDao.getMessageById(messageId)
            if (targetMsg != null && targetMsg.ext != "txt") {
                MediaStorageManager.deleteMediaFile(targetMsg.messageId, targetMsg.ext)
            }
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
                println("Failed to send revoke payload: ${e.message}")
            }
        }
    }

    fun editMessage(messageId: String, newContent: String) {
        scope.launch(Dispatchers.IO) {
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
                    data = Base64.getEncoder().encodeToString(newContent.toByteArray(Charsets.UTF_8)),
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
                println("Failed to send edit payload: ${e.message}")
            }
        }
    }
}
