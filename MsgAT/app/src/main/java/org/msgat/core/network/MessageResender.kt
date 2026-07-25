package org.msgat.core.network

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.msgat.MsgATApp
import org.msgat.core.crypto.CryptoManager
import org.msgat.model.GhostPacket
import org.msgat.model.MessagePayload
import java.security.SecureRandom

object MessageResender {

    suspend fun resendPendingMessages() {
        withContext(Dispatchers.IO) {
            try {
                val app = MsgATApp.instance
                val msgDao = app.db.msgDao()
                val nettyClient = app.nettyClient
                val securePrefs = app.securePrefs

                val pendingMessages = msgDao.getPendingMessages()
                if (pendingMessages.isEmpty()) return@withContext

                val myUuid = securePrefs.myUuid
                val myPrivateKey = securePrefs.getX25519PrivateKey()

                for (msg in pendingMessages) {
                    val convo = msgDao.getConversationByUuid(msg.conversationUuid) ?: continue
                    val peerPublicKeyBase64 = convo.peerPublicKey

                    if (msg.ext == "txt") {
                        val payload = MessagePayload(
                            senderUuid = myUuid,
                            fileId = msg.messageId,
                            total = 1,
                            index = 0,
                            ext = msg.ext,
                            data = Base64.encodeToString(msg.content.toByteArray(), Base64.NO_WRAP),
                            timestamp = msg.timestamp,
                            replyToId = msg.replyToId,
                            replyToSender = msg.replyToSender,
                            replyToContent = msg.replyToContent
                        )

                        val jsonBytes = payload.toJson().toByteArray(Charsets.UTF_8)
                        val iv = ByteArray(16)
                        SecureRandom().nextBytes(iv)
                        
                        val cipherText: ByteArray
                        val authTag: ByteArray
                        
                        if (peerPublicKeyBase64.isNotEmpty()) {
                            val pair = CryptoManager.encryptPayloadECDH(myPrivateKey, peerPublicKeyBase64, iv, jsonBytes)
                            cipherText = pair.first
                            authTag = pair.second
                        } else {
                            val pair = CryptoManager.encryptPayloadFallback(myUuid, msg.conversationUuid, iv, jsonBytes)
                            cipherText = pair.first
                            authTag = pair.second
                        }

                        val targetMailboxId = CryptoManager.calculateMailboxId(msg.conversationUuid)
                        val packet = GhostPacket(GhostPacket.TYPE_MESSAGE, targetMailboxId, iv, cipherText, authTag)
                        
                        val success = nettyClient.sendPacket(packet)
                        if (success) {
                            msgDao.updateMessageStatus(msg.messageId, 1)
                        }
                    } else {
                        val contentLen = msg.content.length
                        val actualChunkSize = 40000
                        val chunksCount = (contentLen + actualChunkSize - 1) / actualChunkSize
                        
                        for (i in 0 until chunksCount) {
                            val end = kotlin.math.min(contentLen, (i + 1) * actualChunkSize)
                            val chunkData = msg.content.substring(i * actualChunkSize, end)
                            
                            val payload = MessagePayload(
                                senderUuid = myUuid,
                                fileId = msg.messageId,
                                total = chunksCount,
                                index = i,
                                ext = msg.ext,
                                data = chunkData,
                                timestamp = msg.timestamp,
                                replyToId = msg.replyToId,
                                replyToSender = msg.replyToSender,
                                replyToContent = msg.replyToContent
                            )
                            
                            val jsonBytes = payload.toJson().toByteArray(Charsets.UTF_8)
                            val iv = ByteArray(16)
                            SecureRandom().nextBytes(iv)
                            
                            val cipherText: ByteArray
                            val authTag: ByteArray
                            
                            if (peerPublicKeyBase64.isNotEmpty()) {
                                val pair = CryptoManager.encryptPayloadECDH(myPrivateKey, peerPublicKeyBase64, iv, jsonBytes)
                                cipherText = pair.first
                                authTag = pair.second
                            } else {
                                val pair = CryptoManager.encryptPayloadFallback(myUuid, msg.conversationUuid, iv, jsonBytes)
                                cipherText = pair.first
                                authTag = pair.second
                            }
                            
                            val targetMailboxId = CryptoManager.calculateMailboxId(msg.conversationUuid)
                            val packet = GhostPacket(GhostPacket.TYPE_MESSAGE, targetMailboxId, iv, cipherText, authTag)
                            
                            val success = nettyClient.sendPacket(packet)
                            if (success && i == chunksCount - 1) {
                                msgDao.updateMessageStatus(msg.messageId, 1)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MessageResender", "Failed to resend messages", e)
            }
        }
    }
}
