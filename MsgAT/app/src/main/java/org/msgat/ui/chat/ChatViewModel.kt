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
import androidx.core.net.toUri
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
            val conversation = msgDao.getConversationByUuid(targetUuid)
            val targetAvatar = conversation?.avatarPath ?: ""
            pagingData.map {
                val original = it.replyToId?.let { id -> msgDao.getMessageById(id) }
                    ?.takeIf { message -> message.conversationUuid == targetUuid }
                val author = original?.let { message -> if (message.isMine) securePrefs.myUuid else targetUuid }
                    ?: it.replyToSender
                val displayAuthor = when (author) {
                    securePrefs.myUuid -> "Me"
                    targetUuid -> conversation?.alias ?: "User"
                    else -> null
                }
                MessageUIModel(it.messageId, it.isMine, it.content, it.ext, it.timestamp, if (it.isMine) myAvatar else targetAvatar, it.isRead, it.replyToId, displayAuthor, it.replyToContent, it.status, isRevoked = it.isRevoked == 1, isEdited = it.isEdited == 1)
            }
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
                    timestamp = chatMsg.timestamp,
                    replyToId = replyToId,
                    replyToSender = replyToSender,
                    replyToContent = replyToContent
                )
                val jsonBytes = payload.toJson().toByteArray(Charsets.UTF_8)

                val iv = ByteArray(16)
                SecureRandom().nextBytes(iv)

                val (cipherText, authTag) = CryptoManager.encryptPayloadECDH(
                    securePrefs.getX25519PrivateKey(), peerPublicKeyBase64, iv, jsonBytes)

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
                Log.e("ChatViewModel", "Failed to send message", e)
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
                        android.widget.Toast.makeText(context, "Files cannot exceed 100 MB.", android.widget.Toast.LENGTH_SHORT).show()
                        onFail()
                    }
                    return@launch
                }

                val ext = getExtensionFromUri(context, uri)
                val savedFile = org.msgat.core.media.MediaStorageManager.saveMediaFromUri(context, msgId, ext, uri)

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

                val chunks = uriToBase64Chunks(context, savedFile.toUri())
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
                        timestamp = chatMsg.timestamp,
                        replyToId = replyToId,
                        replyToSender = replyToSender,
                        replyToContent = replyToContent
                    )
                    val jsonBytes = payload.toJson().toByteArray(Charsets.UTF_8)

                    val iv = ByteArray(16)
                    SecureRandom().nextBytes(iv)

                    val (cipherText, authTag) = CryptoManager.encryptPayloadECDH(
                        securePrefs.getX25519PrivateKey(), peerPublicKeyBase64, iv, jsonBytes)

                    val targetMailboxId = CryptoManager.calculateMailboxId(targetUuid)

                    val packet =
                        GhostPacket(GhostPacket.TYPE_MESSAGE, targetMailboxId, iv, cipherText, authTag)

                    val success = nettyClient.sendPacket(packet)
                    if (!success) {
                        allChunksSent = false
                        break
                    }

                    if (index < total - 1) {
                        kotlinx.coroutines.delay(50)
                    }
                }

                if (allChunksSent) {
                    msgDao.updateMessageStatus(msgId, 1)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to send media message", e)
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

    fun revokeMessage(messageId: String) = sendMessageAction(messageId, "revoke", "")

    fun editMessage(messageId: String, newContent: String) {
        if (newContent.isNotBlank()) sendMessageAction(messageId, "edit", newContent)
    }

    private fun sendMessageAction(messageId: String, action: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = msgDao.getMessageById(messageId) ?: return@launch
                val now = System.currentTimeMillis()
                if (!target.isMine || target.conversationUuid != targetUuid || target.isRevoked != 0 ||
                    target.timestamp > now || now - target.timestamp > 120000 || (action == "edit" && target.ext != "txt")) return@launch
                val peer = msgDao.getConversationByUuid(targetUuid) ?: return@launch
                if (peer.peerPublicKey.isEmpty()) return@launch
                val payload = MessagePayload(securePrefs.myUuid, UUID.randomUUID().toString(), 1, 0, "txt",
                    if (action == "edit") Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) else "",
                    now, action = action, targetMessageId = messageId)
                val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val (cipher, tag) = CryptoManager.encryptPayloadECDH(securePrefs.getX25519PrivateKey(), peer.peerPublicKey,
                    iv, payload.toJson().toByteArray(Charsets.UTF_8))
                check(nettyClient.sendPacket(GhostPacket(GhostPacket.TYPE_MESSAGE, CryptoManager.calculateMailboxId(targetUuid), iv, cipher, tag)))
                if (action == "edit") msgDao.updateMessageContent(messageId, text)
                else {
                    msgDao.updateMessageRevoked(messageId)
                    if (target.ext != "txt") org.msgat.core.media.MediaStorageManager.deleteMediaFile(
                        org.msgat.MsgATApp.instance, messageId, target.ext)
                }
            } catch (error: Exception) {
                Log.e("ChatViewModel", "Could not send message change", error)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(org.msgat.MsgATApp.instance, "The message change could not be sent.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
