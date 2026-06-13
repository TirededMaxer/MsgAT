package org.msgat.core.network

import android.util.Base64
import android.util.Log
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.msgat.core.crypto.CryptoManager
import org.msgat.data.db.ChatMessage
import org.msgat.data.db.MsgDao
import org.msgat.model.GhostPacket
import org.msgat.model.MessagePayload
import org.msgat.ui.chat.SyncState

class GhostClientHandler(
    private val myUuid: String,
    private val myPrivateKey: Ed25519PrivateKeyParameters,
    private val msgDao: MsgDao,
    private val onStateChange: (SyncState) -> Unit
) : SimpleChannelInboundHandler<GhostPacket>() {

    private val myMailboxId = CryptoManager.calculateMailboxId(myUuid)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val fileAssembler = mutableMapOf<String, MutableMap<Int, String>>()

    override fun channelActive(ctx: ChannelHandlerContext) {
        onStateChange(SyncState.SYNCING)
        ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_AUTH_REQ, myMailboxId, ByteArray(16), ByteArray(0), ByteArray(16)))
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: GhostPacket) {
        when (msg.type) {
            GhostPacket.TYPE_AUTH_CHAL -> {
                val nonce = msg.nonceOrIv ?: return
                val (pubKey, signature) = CryptoManager.signChallenge(myPrivateKey, nonce)

                val responsePayload = pubKey + signature
                ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_AUTH_RESP, myMailboxId, nonce, responsePayload, ByteArray(16)))
            }

            GhostPacket.TYPE_MESSAGE -> {
                handleIncomingMessage(ctx, msg)
            }

            GhostPacket.TYPE_OFFLINE_END -> {
                onStateChange(SyncState.SYNCED)
            }

            GhostPacket.TYPE_HEARTBEAT -> {
                // 服务端反射回来的废包，直接丢弃
            }
        }
    }

    private fun handleIncomingMessage(ctx: ChannelHandlerContext, msg: GhostPacket) {
        val iv = msg.nonceOrIv ?: return
        val cipherText = msg.encryptedData ?: return
        val authTag = msg.authTag ?: return

        scope.launch {
            try {
                val friends = msgDao.getConversationList()
                var decryptedPayload: MessagePayload? = null

                for (friend in friends) {
                    try {
                        val decryptedBytes = CryptoManager.decryptPayload(myUuid, friend.uuid, iv, cipherText, authTag)
                        val payloadStr = String(decryptedBytes, Charsets.UTF_8)
                        decryptedPayload = MessagePayload.fromJson(payloadStr)
                        break
                    } catch (_: Exception) {
                        // 继续尝试下一个好友
                    }
                }

                if (decryptedPayload != null) {
                    val fileId = decryptedPayload.fileId
                    val total = decryptedPayload.total
                    var finalData = ""

                    if (total > 1) {
                        // 组装大文件碎片
                        val chunks = fileAssembler.getOrPut(fileId) { mutableMapOf() }
                        chunks[decryptedPayload.index] = decryptedPayload.data
                        if (chunks.size == total) {
                            finalData = (0 until total).joinToString("") { chunks[it] ?: "" }
                            fileAssembler.remove(fileId)
                        }
                    } else {
                        // 单碎片直接获取
                        finalData = decryptedPayload.data
                    }

                    // 只有获取到完整数据后才落库
                    if (finalData.isNotEmpty()) {
                        // 如果是文本，在存入数据库前，将其从 Base64 还原为人类可读的明文
                        val contentToSave = if (decryptedPayload.ext == "txt") {
                            try {
                                String(Base64.decode(finalData, Base64.DEFAULT), Charsets.UTF_8)
                            } catch (_: Exception) {
                                finalData // 如果解密失败防崩溃
                            }
                        } else {
                            finalData // 媒体文件必须保持 Base64
                        }

                        val chatMsg = ChatMessage(
                            fileId,
                            decryptedPayload.senderUuid,
                            false,
                            contentToSave,
                            decryptedPayload.ext,
                            decryptedPayload.timestamp
                        )
                        msgDao.insertMessage(chatMsg)
                    }
                } else {
                    Log.i("MsgAT", "No message found for $decryptedPayload")
                }

                // 发送 ACK 阅后即焚
                val ackPacket = GhostPacket(GhostPacket.TYPE_ACK, myMailboxId, ByteArray(16), ByteArray(0), ByteArray(16))
                ctx.writeAndFlush(ackPacket)

            } catch (e: Exception) {
                Log.e("MsgAT", "FATAL", e)
            }
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        onStateChange(SyncState.DISCONNECTED)
    }
}