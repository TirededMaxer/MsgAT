package org.msgat.ui.sessionlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    val connectionState = nettyClient.connectionState

    val conversations: StateFlow<List<ConversationUIModel>> = msgDao.getAllConversations()
        .map { list -> list.map { ConversationUIModel(it.uuid, it.alias) } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun addConversation(uuid: String, alias: String) {
        viewModelScope.launch {
            msgDao.insertConversation(Conversation(uuid, alias))
        }
    }

    fun updateAlias(uuid: String, newAlias: String) {
        viewModelScope.launch {
            msgDao.insertConversation(Conversation(uuid, newAlias))
        }
    }

    fun deleteConversation(uuid: String) {
        viewModelScope.launch {
            msgDao.deleteConversation(uuid)
            msgDao.clearMessages(uuid)
        }
    }

    fun clearMessages(uuid: String) {
        viewModelScope.launch {
            msgDao.clearMessages(uuid)
        }
    }

    fun resetIdentity() {
        securePrefs.burnIdentityAndGenerateNew()

        nettyClient.disconnect()
        nettyClient.connect()
    }

    fun saveSettings(ip: String, port: String) {
        securePrefs.serverIp = ip
        securePrefs.serverPort = port.toIntOrNull() ?: 0

        nettyClient.disconnect()
        nettyClient.connect()
    }
}