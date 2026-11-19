package org.msgat.ui.sessionlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    val isIdentityMissing: Boolean get() = securePrefs.myNickname.isNullOrBlank()

    val connectionState = nettyClient.connectionState
    private var resettingIdentity = false

    val folders: StateFlow<List<org.msgat.data.db.ConversationFolder>?> =
        msgDao.observeFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val allConversations:StateFlow<List<ConversationUIModel>> = msgDao.getAllConversationsWithUnread().map{list->list.map{row->
        val pins = org.json.JSONObject(row.folderPinOrders)
        val ranks = pins.keys().asSequence().associateWith { pins.getLong(it) }
        ConversationUIModel(row.conversation.uuid,row.conversation.alias,row.conversation.avatarPath,row.unreadCount,row.pinOrder,row.archived,row.muteUntil,row.manualUnread,row.summary,row.draftText,row.lastAt,row.firstUnreadID,row.folderIDs,row.hasDraft,row.activityAt,ranks,
            lastReceiptStatus = row.lastReceiptStatus, summaryKind = row.summaryKind, lastMessageId = row.lastID)
    }}.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    fun conversationAction(peer: String, action: String, until: Long? = null, folderId: String? = null) {
        viewModelScope.launch {
            try {
                if (action == "read") nettyClient.secureMessaging.markAllRead(peer)
                else nettyClient.conversationIndex.change(peer, action, until, folderId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(org.msgat.MsgATApp.instance, "Conversation could not be updated", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    suspend fun setArchived(peer: String, expected: Boolean, archived: Boolean): Boolean =
        nettyClient.conversationIndex.setArchived(peer, expected, archived)
    fun movePin(scope: String, peer: String, target: String, before: Boolean) {
        viewModelScope.launch {
            val moved = try {
                nettyClient.conversationIndex.movePin(scope, peer, target, before)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!moved) android.widget.Toast.makeText(
                org.msgat.MsgATApp.instance, "Pinned chats could not be reordered", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    suspend fun saveFolder(value:org.msgat.data.db.ConversationFolder,peers:Collection<String>):Boolean=try{nettyClient.conversationIndex.save(value,peers);true}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:Exception){false}
    suspend fun deleteFolder(id:String):Boolean=try{nettyClient.conversationIndex.delete(id);true}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:Exception){false}
    suspend fun moveFolder(id:String,targetId:String,before:Boolean):Boolean=try{nettyClient.conversationIndex.move(id,targetId,before)}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:Exception){false}

    val friendRequests: StateFlow<List<org.msgat.data.db.FriendRequest>> = msgDao.getAllFriendRequests()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    suspend fun acceptFriendRequest(senderUuid: String): Boolean =
        nettyClient.secureMessaging.acceptContact(senderUuid)

    suspend fun declineFriendRequest(senderUuid: String): Boolean =
        nettyClient.secureMessaging.declineContact(senderUuid)

    fun addConversation(rawUuid: String, initialDisplayName: String = "Unknown") {
        val uuid=rawUuid.trim().lowercase()
        if (!uuid.matches(Regex("[a-f0-9]{64}")) || uuid==securePrefs.myUuid) return
        viewModelScope.launch {
            if (msgDao.getConversationByUuid(uuid)==null) nettyClient.secureMessaging.requestContact(uuid)
        }
    }

    fun deleteConversation(uuid: String, onFailure: () -> Unit) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                val messages = try {
                    msgDao.getMessageListByConversation(uuid)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                val committed = nettyClient.secureMessaging.deleteContact(uuid)
                if (committed) {
                    val context = org.msgat.MsgATApp.instance.applicationContext
                    for (message in messages) if (message.ext != "txt") {
                        org.msgat.core.media.MediaStorageManager.deleteMediaFile(context, message.messageId, message.ext)
                    }
                }
                committed
            }
            if (!deleted) onFailure()
        }
    }

    // Wipes all chat history for a specific conversation without deleting the thread.
    fun clearMessages(uuid: String, onFailure: () -> Unit) {
        viewModelScope.launch {
            if (!nettyClient.secureMessaging.clearMessages(uuid)) onFailure()
        }
    }

    // The committed marker moves erasure to the next process, before any vault opens.
    @androidx.annotation.MainThread
    fun resetIdentity(onFail: () -> Unit) {
        if (resettingIdentity) return
        resettingIdentity = true
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            kotlinx.coroutines.withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                val app = org.msgat.MsgATApp.instance
                val context = app.applicationContext
                val lifecycle = org.msgat.core.crypto.securestorage.IdentityLifecycle
                try {
                    lifecycle.prepare(context, securePrefs)
                } catch (_: Exception) {
                    if (!lifecycle.hasPendingReset(context)) {
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            resettingIdentity = false
                            onFail()
                        }
                        return@withContext
                    }
                }
                try {
                    app.shutdownForIdentityReset()
                } catch (error: Exception) {
                    android.util.Log.w("MsgAT_Identity", "Reset shutdown interrupted: ${error.javaClass.simpleName}")
                } finally {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(0)
                }
            }
        }
    }

    // Own the selected in-memory crop before launching so dialog disposal cannot clear it mid-import.
    fun saveIdentity(nickname: String,avatarBytes: ByteArray?,removeAvatar: Boolean,onSaved: (Boolean)->Unit) {
        val owned=avatarBytes?.clone()
        viewModelScope.launch(Dispatchers.IO){
            var saved=false
            try {
                val bytes=owned?.let {org.msgat.core.media.ProfileImages.jpeg(it)}
                try {saved=nettyClient.secureMessaging.updateProfile(nickname,bytes,removeAvatar)}finally {bytes?.fill(0)}
                if(saved){_currentNickname.value=securePrefs.myNickname;_currentAvatarPath.value=securePrefs.myAvatarPath}
            }catch(cancelled: kotlinx.coroutines.CancellationException){throw cancelled}
            catch(_: Exception){saved=false}
            finally {owned?.fill(0);kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable+Dispatchers.Main){onSaved(saved)}}
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
    }
}
