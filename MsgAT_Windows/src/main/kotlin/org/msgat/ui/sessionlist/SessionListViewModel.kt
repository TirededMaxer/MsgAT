package org.msgat.ui.sessionlist

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.msgat.core.crypto.CryptoManager
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.core.media.MediaStorageManager
import org.msgat.core.network.NettyClient
import org.msgat.data.db.Conversation
import org.msgat.data.db.FriendRequest
import org.msgat.data.db.MsgDao
import org.msgat.model.GhostPacket
import org.msgat.model.SyncState
import java.io.File
import java.security.SecureRandom
import java.util.Base64

class SessionListViewModel(
    private val msgDao: MsgDao,
    private val nettyClient: NettyClient,
    private val securePrefs: SecurePrefs,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    val currentIp: String get() = securePrefs.serverIp ?: ""

    val currentPort: String get() = if (securePrefs.serverPort > 0) securePrefs.serverPort.toString() else ""

    val currentUuid: String get() = securePrefs.myUuid

    private val _currentAvatarPath = MutableStateFlow(securePrefs.myAvatarPath)
    val currentAvatarPath: StateFlow<String?> = _currentAvatarPath

    private val _currentNickname = MutableStateFlow(securePrefs.myNickname)
    val currentNickname: StateFlow<String?> = _currentNickname

    val isIdentityMissing: Boolean get() = securePrefs.myNickname.isNullOrBlank() || securePrefs.myAvatarPath.isNullOrBlank()

    val connectionState = nettyClient.connectionState

    val conversations: StateFlow<List<ConversationUIModel>> = msgDao.getAllConversationsWithUnread()
        .map { list -> list.map { ConversationUIModel(it.conversation.uuid, it.conversation.alias, it.conversation.avatarPath, it.unreadCount > 0) } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val friendRequests: StateFlow<List<FriendRequest>> = msgDao.getAllFriendRequests()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    // Accepts an incoming friend request and creates a new local conversation.
    fun acceptFriendRequest(senderUuid: String, nickname: String, avatarPath: String) {
        scope.launch {
            val request = msgDao.getFriendRequestByUuid(senderUuid)
            val peerPub = request?.peerPublicKey ?: ""
            msgDao.insertConversation(Conversation(senderUuid, nickname, avatarPath, peerPub))
            msgDao.deleteFriendRequest(senderUuid)

            try {
                val payloadJson = """{"senderUuid":"${securePrefs.myUuid}","senderPublicKey":"${securePrefs.myX25519PublicKeyBase64}"}"""
                val iv = ByteArray(16)
                SecureRandom().nextBytes(iv)

                val (cipherText, authTag) = CryptoManager.encryptPayloadFallback(senderUuid, senderUuid, iv, payloadJson.toByteArray(Charsets.UTF_8))

                val targetMailboxId = CryptoManager.calculateMailboxId(senderUuid)
                val packet = GhostPacket(
                    type = GhostPacket.TYPE_FRIEND_ACCEPTED,
                    targetMailboxId = targetMailboxId,
                    nonceOrIv = iv,
                    encryptedData = cipherText,
                    authTag = authTag
                )
                nettyClient.sendPacket(packet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Declines and removes a pending friend request from the database.
    fun declineFriendRequest(senderUuid: String) {
        scope.launch {
            msgDao.deleteFriendRequest(senderUuid)
        }
    }

    // Adds a new conversation thread by target UUID.
    fun addConversation(rawUuid: String, initialDisplayName: String = "Unknown") {
        val uuid = rawUuid.trim()
        scope.launch {
            msgDao.insertConversation(Conversation(uuid, initialDisplayName))

            try {
                val payloadJson = """{"senderUuid":"${securePrefs.myUuid}","nickname":"${securePrefs.myNickname ?: "Unknown"}","senderPublicKey":"${securePrefs.myX25519PublicKeyBase64}"}"""
                val iv = ByteArray(16)
                SecureRandom().nextBytes(iv)

                val (cipherText, authTag) = CryptoManager.encryptPayloadFallback(uuid, uuid, iv, payloadJson.toByteArray(Charsets.UTF_8))

                val targetMailboxId = CryptoManager.calculateMailboxId(uuid)
                val packet = GhostPacket(
                    type = GhostPacket.TYPE_SEARCH_USER,
                    targetMailboxId = targetMailboxId,
                    nonceOrIv = iv,
                    encryptedData = cipherText,
                    authTag = authTag
                )
                nettyClient.sendPacket(packet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Removes a conversation thread and all its associated messages.
    fun deleteConversation(uuid: String) {
        scope.launch(Dispatchers.IO) {
            val convo = msgDao.getConversationByUuid(uuid)
            if (convo != null) {
                val peerPublicKeyBase64 = convo.peerPublicKey
                val iv = ByteArray(16)
                SecureRandom().nextBytes(iv)
                val targetMailboxId = CryptoManager.calculateMailboxId(uuid)

                if (peerPublicKeyBase64.isNotEmpty()) {
                    val myPrivateKey = securePrefs.getX25519PrivateKey()
                    val payloadJson = """{"senderUuid":"${securePrefs.myUuid}"}"""
                    val (cipherText, authTag) = CryptoManager.encryptPayloadECDH(myPrivateKey, peerPublicKeyBase64, iv, payloadJson.toByteArray(Charsets.UTF_8))
                    val packet = GhostPacket(GhostPacket.TYPE_FRIEND_DELETED, targetMailboxId, iv, cipherText, authTag)
                    nettyClient.sendPacket(packet)
                } else {
                    val payloadJson = """{"senderUuid":"${securePrefs.myUuid}"}"""
                    val (cipherText, authTag) = CryptoManager.encryptPayloadFallback(uuid, uuid, iv, payloadJson.toByteArray(Charsets.UTF_8))
                    val packet = GhostPacket(GhostPacket.TYPE_REQUEST_REVOKED, targetMailboxId, iv, cipherText, authTag)
                    nettyClient.sendPacket(packet)
                }
            }
            val msgs = msgDao.getMessageListByConversation(uuid)
            for (m in msgs) {
                if (m.ext != "txt") {
                    MediaStorageManager.deleteMediaFile(m.messageId, m.ext)
                }
            }
            msgDao.deleteConversation(uuid)
            msgDao.clearMessages(uuid)
        }
    }

    // Wipes all chat history for a specific conversation without deleting the thread.
    fun clearMessages(uuid: String) {
        scope.launch(Dispatchers.IO) {
            val msgs = msgDao.getMessageListByConversation(uuid)
            for (m in msgs) {
                if (m.ext != "txt") {
                    MediaStorageManager.deleteMediaFile(m.messageId, m.ext)
                }
            }
            msgDao.clearMessages(uuid)
        }
    }

    // Triggers a complete cryptographic wipe of the user's identity.
    fun resetIdentity(onFail: () -> Unit = {}) {
        if (nettyClient.connectionState.value != SyncState.SYNCED) {
            onFail()
            return
        }

        scope.launch(Dispatchers.IO) {
            val friends = msgDao.getConversationList()
            for (friend in friends) {
                val peerPublicKeyBase64 = friend.peerPublicKey
                if (peerPublicKeyBase64.isNotEmpty()) {
                    val iv = ByteArray(16)
                    SecureRandom().nextBytes(iv)
                    val targetMailboxId = CryptoManager.calculateMailboxId(friend.uuid)
                    val myPrivateKey = securePrefs.getX25519PrivateKey()
                    val payloadJson = """{"senderUuid":"${securePrefs.myUuid}"}"""
                    val (cipherText, authTag) = CryptoManager.encryptPayloadECDH(myPrivateKey, peerPublicKeyBase64, iv, payloadJson.toByteArray(Charsets.UTF_8))
                    val packet = GhostPacket(GhostPacket.TYPE_ACCOUNT_RESET, targetMailboxId, iv, cipherText, authTag)
                    nettyClient.sendPacket(packet)
                }
            }

            val myMailboxId = CryptoManager.calculateMailboxId(securePrefs.myUuid)
            val packet = GhostPacket(GhostPacket.TYPE_DELETE_ACCOUNT, myMailboxId, ByteArray(16), ByteArray(0), ByteArray(16))
            nettyClient.sendPacket(packet)

            delay(1000)

            msgDao.clearAllMessages()
            msgDao.clearAllConversations()
            msgDao.clearAllFriendRequests()
            MediaStorageManager.deleteAllMedia()

            securePrefs.burnIdentityAndGenerateNew()

            _currentAvatarPath.value = null
            _currentNickname.value = null

            nettyClient.disconnect()
            nettyClient.connect()
        }
    }

    // Persists the user's initial nickname and avatar choice and syncs profile to server.
    fun saveIdentity(nickname: String, avatarPath: String) {
        securePrefs.myNickname = nickname
        securePrefs.myAvatarPath = avatarPath
        _currentNickname.value = nickname
        _currentAvatarPath.value = avatarPath

        scope.launch(Dispatchers.IO) {
            try {
                val json = JsonObject()
                json.addProperty("uuid", securePrefs.myUuid)
                json.addProperty("nickname", nickname)
                json.addProperty("avatarPath", avatarPath)

                if (avatarPath.isNotEmpty()) {
                    val file = File(avatarPath)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        json.addProperty("avatarData", Base64.getEncoder().encodeToString(bytes))
                    }
                }

                val payload = json.toString().toByteArray(Charsets.UTF_8)
                val myMailboxId = CryptoManager.calculateMailboxId(securePrefs.myUuid)
                val packet = GhostPacket(GhostPacket.TYPE_UPDATE_PROFILE, myMailboxId, ByteArray(16), payload, ByteArray(16))
                nettyClient.sendPacket(packet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Updates and saves the server connection settings (IP and port).
    fun saveSettings(ip: String, port: String, turnServer: String?, turnPort: Int, turnUsername: String?, turnPassword: String?, turnUseTls: Boolean) {
        securePrefs.serverIp = ip
        securePrefs.serverPort = port.toIntOrNull() ?: 0
        securePrefs.turnServer = turnServer?.trim()?.ifEmpty { null }
        securePrefs.turnPort = turnPort
        securePrefs.turnUsername = turnUsername?.trim()?.ifEmpty { null }
        securePrefs.turnPassword = turnPassword
        securePrefs.turnUseTls = turnUseTls
        nettyClient.disconnect()
        nettyClient.connect()
    }
}
