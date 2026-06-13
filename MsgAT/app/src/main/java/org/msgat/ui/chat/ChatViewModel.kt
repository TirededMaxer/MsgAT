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

    // 监听全局网络同步状态
    val syncState = nettyClient.connectionState

    // 监听当前会话的聊天记录
    val messages: StateFlow<List<MessageUIModel>> = msgDao.getMessages(targetUuid)
        .map { list ->
            list.map { MessageUIModel(it.messageId, it.isMine, it.content, it.ext, it.timestamp) }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 发送消息的终极战术逻辑
    fun sendMessage(text: String, onFail: () -> Unit) {
        val myUuid = securePrefs.myUuid
        if (text.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 生成唯一消息 ID
                val msgId = UUID.randomUUID().toString()

                // 2. 本地落库 (呈现给 UI)
                val chatMsg = ChatMessage(msgId, targetUuid, true, text, "txt", System.currentTimeMillis())
                msgDao.insertMessage(chatMsg)

                // 3. 构造业务负载 JSON
                val payload = MessagePayload(
                    senderUuid = myUuid,
                    fileId = msgId,
                    total = 1,
                    index = 0,
                    ext = "txt",
                    data = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP),
                    timestamp = System.currentTimeMillis()
                )
                val jsonBytes = payload.toJson().toByteArray(Charsets.UTF_8)

                // 4. 生成 16 字节的安全随机 IV
                val iv = ByteArray(16)
                SecureRandom().nextBytes(iv)

                // 5. 端到端 AES-GCM 加密 (使用你们俩的 UUID 计算出的共享密钥)
                val (cipherText, authTag) = CryptoManager.encryptPayload(myUuid, targetUuid, iv, jsonBytes)

                // 6. 计算目标的盲路由地址 (Balke2b Hash)
                val targetMailboxId = CryptoManager.calculateMailboxId(targetUuid)

                // 7. 组装成幽灵数据包！
                val packet = GhostPacket(
                    type = GhostPacket.TYPE_MESSAGE,
                    targetMailboxId = targetMailboxId,
                    nonceOrIv = iv,
                    encryptedData = cipherText,
                    authTag = authTag
                )

                // 8. 压入 Netty 流水线 (底层的 GhostPacketCodec 会自动用随机数把它塞满 500KB)
                val success = nettyClient.sendPacket(packet)
                if (!success) {
                    // 发送失败：删掉数据库里的泡泡，并在主线程通知 UI
                    msgDao.deleteMessageById(msgId)
                    kotlinx.coroutines.withContext(Dispatchers.Main) { onFail() }
                }

            } catch (e: Exception) {
                Log.e("ChatViewModel", "发送消息失败", e)
            }
        }
    }

    private val _alias = MutableStateFlow(targetUuid)
    val alias: StateFlow<String> = _alias.asStateFlow()

    init {
        // 初始化时去数据库查真实的备注名
        viewModelScope.launch(Dispatchers.IO) {
            val convo = msgDao.getConversationByUuid(targetUuid)
            if (convo != null) {
                _alias.value = convo.alias
            }
        }
    }

    fun sendMedia(context: Context, uri: Uri, onFail: () -> Unit) {
        val myUuid = securePrefs.myUuid

        viewModelScope.launch(Dispatchers.IO) {
            val msgId = UUID.randomUUID().toString()

            try {
                // 1. 自动识别文件真实后缀名 (图片或视频)
                val ext = getExtensionFromUri(context, uri)
                // 2. 将大文件切成多个 250KB 的碎片
                val chunks = uriToBase64Chunks(context, uri)

                if (chunks.isEmpty()) return@launch
                val total = chunks.size

                // 3. 将完整数据拼接落库，用于本地 UI 立即显示
                val localContent = chunks.joinToString("")
                val chatMsg = ChatMessage(msgId, targetUuid, true, localContent, ext, System.currentTimeMillis())
                msgDao.insertMessage(chatMsg)

                // 4. 循环发送所有分片
                chunks.forEachIndexed { index, chunkData ->
                    val payload = MessagePayload(
                        senderUuid = myUuid,
                        fileId = msgId,
                        total = total,
                        index = index,
                        ext = ext,
                        data = chunkData,
                        timestamp = System.currentTimeMillis()
                    )
                    val jsonBytes = payload.toJson().toByteArray(Charsets.UTF_8)

                    val iv = ByteArray(16)
                    SecureRandom().nextBytes(iv)
                    val (cipherText, authTag) = CryptoManager.encryptPayload(myUuid, targetUuid, iv, jsonBytes)
                    val targetMailboxId = CryptoManager.calculateMailboxId(targetUuid)

                    val packet =
                        GhostPacket(GhostPacket.TYPE_MESSAGE, targetMailboxId, iv, cipherText, authTag)

                    val success = nettyClient.sendPacket(packet)
                    if (!success) {
                        throw Exception("Chunk $index 发送失败")
                    }

                    // 稍微停顿，防止超大文件瞬间把 Netty 流水线和手机内存撑爆
                    kotlinx.coroutines.delay(50)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "媒体发送失败", e)
                if (msgId.isNotEmpty()) {
                    msgDao.deleteMessageById(msgId)
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) { onFail() }
            }
        }
    }
}