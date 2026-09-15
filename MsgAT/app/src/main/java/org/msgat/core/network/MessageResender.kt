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
                    if (peerPublicKeyBase64.isEmpty()) continue

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

                        val (cipherText, authTag) = CryptoManager.encryptPayloadECDH(
                            myPrivateKey, peerPublicKeyBase64, iv, jsonBytes)

                        val targetMailboxId = CryptoManager.calculateMailboxId(msg.conversationUuid)
                        val packet = GhostPacket(GhostPacket.TYPE_MESSAGE, targetMailboxId, iv, cipherText, authTag)

                        val success = nettyClient.sendPacket(packet)
                        if (success) {
                            msgDao.updateMessageStatus(msg.messageId, 1)
                        }
                    } else {
                        val context = app.applicationContext
                        val mediaFile = org.msgat.core.media.MediaStorageManager.getOrExtractMediaFile(context, msg.messageId, msg.ext, msg.content)
                        if (mediaFile == null || !mediaFile.isFile) continue
                        val chunks = org.msgat.utils.MediaHelper.uriToBase64Chunks(context, android.net.Uri.fromFile(mediaFile))

                        if (chunks.isEmpty()) continue
                        val totalChunks = chunks.size
                        var allSent = true

                        for (i in 0 until totalChunks) {
                            val chunkData = chunks[i]
                            val payload = MessagePayload(
                                senderUuid = myUuid,
                                fileId = msg.messageId,
                                total = totalChunks,
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

                            val (cipherText, authTag) = CryptoManager.encryptPayloadECDH(
                                myPrivateKey, peerPublicKeyBase64, iv, jsonBytes)

                            val targetMailboxId = CryptoManager.calculateMailboxId(msg.conversationUuid)
                            val packet = GhostPacket(GhostPacket.TYPE_MESSAGE, targetMailboxId, iv, cipherText, authTag)

                            val success = nettyClient.sendPacket(packet)
                            if (!success) {
                                allSent = false
                                break
                            }

                            if (i < totalChunks - 1) {
                                kotlinx.coroutines.delay(50)
                            }
                        }

                        if (allSent) {
                            msgDao.updateMessageStatus(msg.messageId, 1)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MessageResender", "Failed to resend messages", e)
            }
        }
    }
}
