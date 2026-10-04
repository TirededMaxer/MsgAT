package org.msgat.ui.sessionlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.msgat.core.network.NettyClient
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.Conversation
import org.msgat.data.db.MsgDao

class SessionListViewModel(
    private val msgDao: MsgDao,
    private val nettyClient: NettyClient,
    private val securePrefs: SecurePrefs
) : ViewModel() {

    val currentIp: String get() = securePrefs.serverIp ?: ""

    val currentPort: String get() = if (securePrefs.serverPort > 0) securePrefs.serverPort.toString() else ""

    val currentUuid: String get() = securePrefs.myUuid

    val currentTurnServer: String get() = securePrefs.turnServer ?: ""

    val currentTurnPort: String get() = if (securePrefs.turnPort > 0) securePrefs.turnPort.toString() else "3478"

    val currentTurnUsername: String get() = securePrefs.turnUsername ?: ""

    val currentTurnPassword: String get() = securePrefs.turnPassword ?: ""

    val currentTurnUseTls: Boolean get() = securePrefs.turnUseTls

    private val _currentAvatarPath = kotlinx.coroutines.flow.MutableStateFlow(securePrefs.myAvatarPath)
    val currentAvatarPath: StateFlow<String?> = _currentAvatarPath

    private val _currentNickname = kotlinx.coroutines.flow.MutableStateFlow(securePrefs.myNickname)
    val currentNickname: StateFlow<String?> = _currentNickname

    val isIdentityMissing: Boolean get() = securePrefs.myNickname.isNullOrBlank() || securePrefs.myAvatarPath.isNullOrBlank()

    val connectionState = nettyClient.connectionState

    val conversations: StateFlow<List<ConversationUIModel>> = msgDao.getAllConversationsWithUnread()
        .map { list -> list.map { ConversationUIModel(it.conversation.uuid, it.conversation.alias, it.conversation.avatarPath, it.unreadCount) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val friendRequests: StateFlow<List<org.msgat.data.db.FriendRequest>> = msgDao.getAllFriendRequests()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Accepts an incoming friend request and creates a new local conversation.
    fun acceptFriendRequest(senderUuid: String, nickname: String, avatarPath: String) {
        if (nettyClient.connectionState.value != org.msgat.ui.chat.SyncState.SYNCED) return
        viewModelScope.launch {
            val request = msgDao.getFriendRequestByUuid(senderUuid) ?: return@launch
            val peerPub = request.peerPublicKey
            if (peerPub.isEmpty()) return@launch

            try {
                val payloadJson = """{"senderUuid":"${securePrefs.myUuid}","senderPublicKey":"${securePrefs.myX25519PublicKeyBase64}"}"""
                val iv = ByteArray(16)
                java.security.SecureRandom().nextBytes(iv)

                val (cipherText, authTag) = org.msgat.core.crypto.CryptoManager.encryptPayloadFallback(senderUuid, senderUuid, iv, payloadJson.toByteArray(Charsets.UTF_8))

                val targetMailboxId = org.msgat.core.crypto.CryptoManager.calculateMailboxId(senderUuid)
                val packet = org.msgat.model.GhostPacket(
                    type = org.msgat.model.GhostPacket.TYPE_FRIEND_ACCEPTED,
                    targetMailboxId = targetMailboxId,
                    nonceOrIv = iv,
                    encryptedData = cipherText,
                    authTag = authTag
                )
                if (nettyClient.sendPacket(packet)) {
                    msgDao.insertConversation(Conversation(senderUuid, nickname, avatarPath, peerPub))
                    msgDao.deleteFriendRequest(senderUuid)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Declines and removes a pending friend request from the database.
    fun declineFriendRequest(senderUuid: String) {
        viewModelScope.launch {
            msgDao.deleteFriendRequest(senderUuid)
        }
    }

    // Adds a new conversation thread by target UUID.
    fun addConversation(rawUuid: String, initialDisplayName: String = "Unknown") {
        if (nettyClient.connectionState.value != org.msgat.ui.chat.SyncState.SYNCED) return
        val uuid = rawUuid.trim().lowercase()
        if (!uuid.matches(Regex("[a-f0-9]{64}")) || uuid == securePrefs.myUuid) return
        viewModelScope.launch {
            if (msgDao.getConversationByUuid(uuid) != null) return@launch
            msgDao.insertConversation(Conversation(uuid, initialDisplayName))

            try {
                val payloadJson = org.json.JSONObject().apply {
                    put("senderUuid", securePrefs.myUuid)
                    put("nickname", securePrefs.myNickname ?: "Unknown")
                    put("senderPublicKey", securePrefs.myX25519PublicKeyBase64)
                }.toString()
                val iv = ByteArray(16)
                java.security.SecureRandom().nextBytes(iv)

                val (cipherText, authTag) = org.msgat.core.crypto.CryptoManager.encryptPayloadFallback(uuid, uuid, iv, payloadJson.toByteArray(Charsets.UTF_8))

                val targetMailboxId = org.msgat.core.crypto.CryptoManager.calculateMailboxId(uuid)
                val packet = org.msgat.model.GhostPacket(
                    type = org.msgat.model.GhostPacket.TYPE_SEARCH_USER,
                    targetMailboxId = targetMailboxId,
                    nonceOrIv = iv,
                    encryptedData = cipherText,
                    authTag = authTag
                )
                if (!nettyClient.sendPacket(packet)) msgDao.deleteConversation(uuid)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Removes a conversation thread and all its associated messages.
    fun deleteConversation(uuid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val convo = msgDao.getConversationByUuid(uuid)
            if (convo != null) {
                val peerPublicKeyBase64 = convo.peerPublicKey
                val iv = ByteArray(16)
                java.security.SecureRandom().nextBytes(iv)
                val targetMailboxId = org.msgat.core.crypto.CryptoManager.calculateMailboxId(uuid)

                if (peerPublicKeyBase64.isNotEmpty()) {
                    // Send TYPE_FRIEND_DELETED
                    val myPrivateKey = securePrefs.getX25519PrivateKey()
                    val payloadJson = """{"senderUuid":"${securePrefs.myUuid}"}"""
                    val (cipherText, authTag) = org.msgat.core.crypto.CryptoManager.encryptPayloadECDH(myPrivateKey, peerPublicKeyBase64, iv, payloadJson.toByteArray(Charsets.UTF_8))
                    val packet = org.msgat.model.GhostPacket(org.msgat.model.GhostPacket.TYPE_FRIEND_DELETED, targetMailboxId, iv, cipherText, authTag)
                    nettyClient.sendPacket(packet)
                } else {
                    // Send TYPE_REQUEST_REVOKED
                    val payloadJson = """{"senderUuid":"${securePrefs.myUuid}"}"""
                    val (cipherText, authTag) = org.msgat.core.crypto.CryptoManager.encryptPayloadFallback(uuid, uuid, iv, payloadJson.toByteArray(Charsets.UTF_8))
                    val packet = org.msgat.model.GhostPacket(org.msgat.model.GhostPacket.TYPE_REQUEST_REVOKED, targetMailboxId, iv, cipherText, authTag)
                    nettyClient.sendPacket(packet)
                }
            }
            val msgs = msgDao.getMessageListByConversation(uuid)
            val context = org.msgat.MsgATApp.instance.applicationContext
            for (m in msgs) {
                if (m.ext != "txt") {
                    org.msgat.core.media.MediaStorageManager.deleteMediaFile(context, m.messageId, m.ext)
                }
            }
            msgDao.deleteConversation(uuid)
            msgDao.clearMessages(uuid)
        }
    }

    // Wipes all chat history for a specific conversation without deleting the thread.
    fun clearMessages(uuid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msgs = msgDao.getMessageListByConversation(uuid)
            val context = org.msgat.MsgATApp.instance.applicationContext
            for (m in msgs) {
                if (m.ext != "txt") {
                    org.msgat.core.media.MediaStorageManager.deleteMediaFile(context, m.messageId, m.ext)
                }
            }
            msgDao.clearMessages(uuid)
        }
    }

    // Triggers a complete cryptographic wipe of the user's local identity.
    fun resetIdentity(onFail: () -> Unit) {
        if (nettyClient.connectionState.value != org.msgat.ui.chat.SyncState.SYNCED) {
            onFail()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val friends = msgDao.getConversationList()
            for (friend in friends) {
                val peerPublicKeyBase64 = friend.peerPublicKey
                if (peerPublicKeyBase64.isNotEmpty()) {
                    val iv = ByteArray(16)
                    java.security.SecureRandom().nextBytes(iv)
                    val targetMailboxId = org.msgat.core.crypto.CryptoManager.calculateMailboxId(friend.uuid)
                    val myPrivateKey = securePrefs.getX25519PrivateKey()
                    val payloadJson = """{"senderUuid":"${securePrefs.myUuid}"}"""
                    val (cipherText, authTag) = org.msgat.core.crypto.CryptoManager.encryptPayloadECDH(myPrivateKey, peerPublicKeyBase64, iv, payloadJson.toByteArray(Charsets.UTF_8))
                    val packet = org.msgat.model.GhostPacket(org.msgat.model.GhostPacket.TYPE_ACCOUNT_RESET, targetMailboxId, iv, cipherText, authTag)
                    nettyClient.sendPacket(packet)
                }
            }

            if (!nettyClient.deleteAccount()) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { onFail() }
                return@launch
            }
            nettyClient.disconnect()

            msgDao.clearAllMessages()
            msgDao.clearAllConversations()
            msgDao.clearAllFriendRequests()
            org.msgat.core.media.MediaStorageManager.deleteAllMedia(org.msgat.MsgATApp.instance.applicationContext)

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                securePrefs.myNickname = ""
                securePrefs.myAvatarPath = ""
                securePrefs.burnIdentityAndGenerateNew()
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(0)
            }
        }
    }

    // Persists the user's nickname and avatar choice to the secure preferences.
    fun saveIdentity(nickname: String, avatarUri: String, onSaved: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = org.msgat.MsgATApp.instance.applicationContext
            try {
                val target = java.io.File(org.msgat.core.media.MediaStorageManager.getMediaDir(context), "avatar_self.jpg")
                val source = android.net.Uri.parse(avatarUri)
                context.contentResolver.openInputStream(source)?.use { input ->
                    val atomic = android.util.AtomicFile(target)
                    val output = atomic.startWrite()
                    try { input.copyTo(output); atomic.finishWrite(output) }
                    catch (e: Exception) { atomic.failWrite(output); throw e }
                } ?: throw java.io.IOException("Cannot open avatar")
                securePrefs.myNickname = nickname
                securePrefs.myAvatarPath = target.absolutePath
                _currentNickname.value = nickname
                _currentAvatarPath.value = target.absolutePath
                nettyClient.sendProfile()
                kotlinx.coroutines.withContext(Dispatchers.Main) { onSaved() }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Failed to save", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Updates and saves the server connection settings (relay + optional TURN fallback).
    fun saveSettings(settings: org.msgat.ui.components.ServerSettings) {
        securePrefs.serverCertificateSha256 = settings.serverCertificateSha256.ifBlank { null }
        securePrefs.allowDevelopmentCertificate = settings.allowDevelopmentCertificate
        securePrefs.serverIp = settings.ip.trim()
        securePrefs.serverPort = settings.port.toIntOrNull() ?: 0
        securePrefs.turnServer = settings.turnServer.trim().ifEmpty { null }
        securePrefs.turnPort = settings.turnPort.toIntOrNull() ?: 3478
        securePrefs.turnUsername = settings.turnUsername.trim().ifEmpty { null }
        securePrefs.turnPassword = settings.turnPassword.ifEmpty { null }
        securePrefs.turnUseTls = settings.turnUseTls

        nettyClient.disconnect()
        nettyClient.connect()
    }
}
