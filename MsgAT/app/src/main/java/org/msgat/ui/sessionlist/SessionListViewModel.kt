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

    private val _currentAvatarPath = kotlinx.coroutines.flow.MutableStateFlow(securePrefs.myAvatarPath)
    val currentAvatarPath: StateFlow<String?> = _currentAvatarPath

    private val _currentNickname = kotlinx.coroutines.flow.MutableStateFlow(securePrefs.myNickname)
    val currentNickname: StateFlow<String?> = _currentNickname

    val isIdentityMissing: Boolean get() = securePrefs.myNickname.isNullOrBlank() || securePrefs.myAvatarPath.isNullOrBlank()

    val connectionState = nettyClient.connectionState

    val conversations: StateFlow<List<ConversationUIModel>> = msgDao.getAllConversations()
        .map { list -> list.map { ConversationUIModel(it.uuid, it.alias, it.avatarPath) } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val friendRequests: StateFlow<List<org.msgat.data.db.FriendRequest>> = msgDao.getAllFriendRequests()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Accepts an incoming friend request and creates a new local conversation.
    fun acceptFriendRequest(senderUuid: String, nickname: String, avatarPath: String) {
        viewModelScope.launch {
            val request = msgDao.getFriendRequestByUuid(senderUuid)
            val peerPub = request?.peerPublicKey ?: ""
            msgDao.insertConversation(Conversation(senderUuid, nickname, avatarPath, peerPub))
            msgDao.deleteFriendRequest(senderUuid)

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
                nettyClient.sendPacket(packet)
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

    // Manually adds a new conversation thread with a specific alias.
    fun addConversation(rawUuid: String, alias: String) {
        val uuid = rawUuid.trim()
        viewModelScope.launch {
            msgDao.insertConversation(Conversation(uuid, alias))

            try {
                val payloadJson = """{"senderUuid":"${securePrefs.myUuid}","nickname":"${securePrefs.myNickname ?: "Unknown"}","senderPublicKey":"${securePrefs.myX25519PublicKeyBase64}"}"""
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
                nettyClient.sendPacket(packet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Updates the local display alias for a specific contact UUID.
    fun updateAlias(uuid: String, newAlias: String) {
        viewModelScope.launch {
            msgDao.insertConversation(Conversation(uuid, newAlias))
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
            msgDao.deleteConversation(uuid)
            msgDao.clearMessages(uuid)
        }
    }

    // Wipes all chat history for a specific conversation without deleting the thread.
    fun clearMessages(uuid: String) {
        viewModelScope.launch {
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
            
            // Send TYPE_DELETE_ACCOUNT to server
            val myMailboxId = org.msgat.core.crypto.CryptoManager.calculateMailboxId(securePrefs.myUuid)
            val packet = org.msgat.model.GhostPacket(org.msgat.model.GhostPacket.TYPE_DELETE_ACCOUNT, myMailboxId, ByteArray(16), ByteArray(0), ByteArray(16))
            nettyClient.sendPacket(packet)
            
            kotlinx.coroutines.delay(1000) // wait for packets to be sent
            
            msgDao.clearAllMessages()
            msgDao.clearAllConversations()
            msgDao.clearAllFriendRequests()
            
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
    fun saveIdentity(nickname: String, avatarUri: String) {
        securePrefs.myNickname = nickname
        securePrefs.myAvatarPath = avatarUri
        _currentNickname.value = nickname
        _currentAvatarPath.value = avatarUri
    }

    // Updates and saves the server connection settings (IP and port).
    fun saveSettings(ip: String, port: String) {
        securePrefs.serverIp = ip
        securePrefs.serverPort = port.toIntOrNull() ?: 0

        nettyClient.disconnect()
        nettyClient.connect()
    }
}