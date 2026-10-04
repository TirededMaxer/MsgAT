package org.msgat.core.network

import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.msgat.core.crypto.CryptoManager
import org.msgat.data.db.ChatMessage
import org.msgat.data.db.MsgDao
import org.msgat.model.GhostPacket
import org.msgat.model.MessagePayload
import org.msgat.ui.chat.SyncState

import org.msgat.core.crypto.securestorage.SecurePrefs

class GhostClientHandler(
    private val prefs: SecurePrefs,
    private val msgDao: MsgDao,
    private val onStateChange: (SyncState) -> Unit,
    private val onDeleteConfirmed: (GhostPacket) -> Unit = {}
) : SimpleChannelInboundHandler<GhostPacket>() {

    private val myUuid = prefs.myUuid
    private val myPrivateKey = prefs.getOrCreatePrivateKey()
    private val myMailboxId = CryptoManager.calculateMailboxId(myUuid)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private data class Assembly(
        val metadata: MessagePayload,
        val chunks: MutableMap<Int, String> = mutableMapOf(),
        val receipts: MutableMap<Int, ByteArray> = mutableMapOf(),
        var updatedAt: Long = System.currentTimeMillis()
    )
    private val assemblies = mutableMapOf<String, Assembly>()
    private val inbox = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(256)

    init {
        scope.launch { for (work in inbox) work() }
    }

    private fun enqueue(work: suspend () -> Unit) {
        if (inbox.trySend(work).isFailure) {
            // Close and replay retained relay packets instead of dropping work on overload.
            connectionContext?.close()
        }
    }
    private var connectionContext: ChannelHandlerContext? = null

    private fun acknowledge(ctx: ChannelHandlerContext, packet: GhostPacket) {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(packet.encryptedData ?: return)
        acknowledgeDigest(ctx, digest)
    }

    private fun acknowledgeDigest(ctx: ChannelHandlerContext, digest: ByteArray) {
        ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_ACK, myMailboxId, ByteArray(16), digest, ByteArray(16)))
    }

    // Handles the successful establishment of a TCP connection to the server.
    override fun channelActive(ctx: ChannelHandlerContext) {
        connectionContext = ctx
        onStateChange(SyncState.SYNCING)
        ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_AUTH_REQ, myMailboxId, ByteArray(16), ByteArray(0), ByteArray(16)))
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is io.netty.handler.timeout.IdleStateEvent) {
            if (evt.state() == io.netty.handler.timeout.IdleState.WRITER_IDLE) {
                // Send a lightweight heartbeat to keep the NAT router and server connection alive
                val heartbeatPacket = GhostPacket(GhostPacket.TYPE_HEARTBEAT, null, ByteArray(16), ByteArray(0), ByteArray(16))
                ctx.writeAndFlush(heartbeatPacket)
            }
        }
        super.userEventTriggered(ctx, evt)
    }

    // Receives and routes an incoming GhostPacket based on its packet type.
    override fun channelRead0(ctx: ChannelHandlerContext, msg: GhostPacket) {
        when (msg.type) {
            GhostPacket.TYPE_DELETE_ACCOUNT -> onDeleteConfirmed(msg)
            GhostPacket.TYPE_AUTH_CHAL -> {
                val nonce = msg.nonceOrIv ?: return
                val (pubKey, signature) = CryptoManager.signChallenge(myPrivateKey, nonce)

                val responsePayload = pubKey + signature
                ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_AUTH_RESP, myMailboxId, nonce, responsePayload, ByteArray(16)))

                try {
                    ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_UPDATE_PROFILE, myMailboxId,
                        ByteArray(16), prefs.profilePayload(), ByteArray(16)))
                } catch (error: Exception) {
                    Log.e("MsgAT", "Failed to send profile update", error)
                }
            }

            GhostPacket.TYPE_MESSAGE -> {
                handleIncomingMessage(ctx, msg)
            }

            GhostPacket.TYPE_SEARCH_USER -> {
                handleFriendRequest(ctx, msg)
            }

            GhostPacket.TYPE_OFFLINE_END -> {
                enqueue { onStateChange(SyncState.SYNCED) }
            }

            GhostPacket.TYPE_PROFILE_RESP -> {
                handleProfileResp(ctx, msg)
            }

            GhostPacket.TYPE_FRIEND_ACCEPTED -> {
                handleFriendAccepted(ctx, msg)
            }
            GhostPacket.TYPE_FRIEND_DELETED -> {
                handleFriendDeleted(ctx, msg)
            }
            GhostPacket.TYPE_REQUEST_REVOKED -> {
                handleRequestRevoked(ctx, msg)
            }
            GhostPacket.TYPE_ACCOUNT_RESET -> {
                handleAccountReset(ctx, msg)
            }
            GhostPacket.TYPE_HEARTBEAT -> {
                // Ignore the echoed heartbeat to silently keep the connection alive
            }
            GhostPacket.TYPE_CALL_OFFER,
            GhostPacket.TYPE_CALL_ANSWER,
            GhostPacket.TYPE_CALL_ICE,
            GhostPacket.TYPE_CALL_REJECT,
            GhostPacket.TYPE_CALL_END,
            GhostPacket.TYPE_CALL_BUSY,
            GhostPacket.TYPE_CALL_SYNC -> {
                handleCallSignaling(ctx, msg)
            }
        }
    }

    private fun handleIncomingMessage(ctx: ChannelHandlerContext, msg: GhostPacket) {
        enqueue {
            try {
                val iv = msg.nonceOrIv ?: return@enqueue
                val cipher = msg.encryptedData ?: return@enqueue
                val tag = msg.authTag ?: return@enqueue
                val key = prefs.getX25519PrivateKey()
                var decoded: MessagePayload? = null
                for (friend in msgDao.getConversationList()) {
                    if (friend.peerPublicKey.isEmpty()) continue
                    try {
                        val plain = CryptoManager.decryptPayloadECDH(key, friend.peerPublicKey, iv, cipher, tag)
                        val candidate = MessagePayload.fromJson(String(plain, Charsets.UTF_8))
                        if (candidate.senderUuid != friend.uuid) return@enqueue
                        decoded = candidate
                        break
                    } catch (_: Exception) { }
                }
                val payload = decoded ?: return@enqueue
                if (!payload.fileId.matches(Regex("[A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}")) ||
                    payload.timestamp !in 0..253402300799999L ||
                    payload.total !in 1..4096 || payload.index !in 0 until payload.total ||
                    !payload.ext.matches(Regex("[A-Za-z0-9_]{1,10}")) ||
                    payload.data.length > 200000) return@enqueue
                val existing = msgDao.getMessageById(payload.fileId)
                if (payload.action == null && existing != null) {
                    if (!existing.isMine && existing.conversationUuid == payload.senderUuid) {
                        sendDelivered(ctx, payload)
                        acknowledge(ctx, msg)
                    }
                    return@enqueue
                }
                val assemblyKey = "${payload.senderUuid}:${payload.fileId}"
                val receipts: List<ByteArray>
                val data: String
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cipher)
                if (payload.total > 1) {
                    if (payload.action != null || payload.ext.startsWith("ack_")) return@enqueue
                    val now = System.currentTimeMillis()
                    assemblies.entries.removeIf { now - it.value.updatedAt > 600000 }
                    if (!assemblies.containsKey(assemblyKey) && assemblies.size >= 16) return@enqueue
                    val metadata = payload.copy(index = 0, data = "")
                    val assembly = assemblies.getOrPut(assemblyKey) { Assembly(metadata) }
                    if (assembly.metadata != metadata) return@enqueue
                    assembly.chunks[payload.index] = payload.data
                    assembly.receipts[payload.index] = digest
                    assembly.updatedAt = now
                    if (assemblies.values.sumOf { it.chunks.values.sumOf { chunk -> chunk.length.toLong() } } > 140000000L) {
                        assemblies.remove(assemblyKey)
                        return@enqueue
                    }
                    if (assembly.chunks.size != payload.total) return@enqueue
                    data = (0 until payload.total).joinToString("") { assembly.chunks.getValue(it) }
                    receipts = assembly.receipts.values.toList()
                } else {
                    data = payload.data
                    receipts = listOf(digest)
                }
                when {
                    payload.ext == "ack_deliv" || payload.ext == "ack_read" -> {
                        val target = msgDao.getMessageById(data)
                        if (target != null && target.isMine && target.conversationUuid == payload.senderUuid) {
                            if (payload.ext == "ack_deliv") msgDao.updateMessageStatus(data, 2)
                            else msgDao.updateMessageStatusUpTo(payload.senderUuid, target.timestamp, 3)
                        }
                    }
                    payload.action == "revoke" || payload.action == "edit" -> {
                        val target = payload.targetMessageId?.let { msgDao.getMessageById(it) } ?: return@enqueue
                        if (!target.isMine && target.conversationUuid == payload.senderUuid &&
                            target.timestamp >= 0 && payload.timestamp >= target.timestamp && payload.timestamp - target.timestamp <= 120000) {
                            if (payload.action == "revoke") {
                                msgDao.updateMessageRevoked(target.messageId)
                                if (target.ext != "txt") org.msgat.core.media.MediaStorageManager.deleteMediaFile(
                                    org.msgat.MsgATApp.instance, target.messageId, target.ext)
                            } else if (target.ext == "txt" && target.isRevoked == 0) {
                                msgDao.updateMessageContent(target.messageId, String(Base64.decode(data, Base64.DEFAULT), Charsets.UTF_8))
                            }
                        }
                    }
                    payload.action != null -> return@enqueue
                    else -> {
                        val bytes = Base64.decode(data, Base64.DEFAULT)
                        if (bytes.size > 100 * 1024 * 1024) return@enqueue
                        val content = if (payload.ext == "txt") String(bytes, Charsets.UTF_8)
                        else org.msgat.core.media.MediaStorageManager.saveMediaBytes(
                            org.msgat.MsgATApp.instance, payload.fileId, payload.ext, bytes).absolutePath
                        msgDao.insertMessage(ChatMessage(payload.fileId, payload.senderUuid, false, content,
                            payload.ext, payload.timestamp, replyToId = payload.replyToId,
                            replyToSender = payload.replyToSender, replyToContent = payload.replyToContent))
                        if (!org.msgat.MsgATApp.instance.isAppInForeground) {
                            org.msgat.NotificationHelper.showMessageNotification(org.msgat.MsgATApp.instance,
                                payload.senderUuid, msgDao.getConversationByUuid(payload.senderUuid)?.alias ?: "Unknown",
                                payload.ext, content)
                        }
                    }
                }
                if (!payload.ext.startsWith("ack_")) sendDelivered(ctx, payload)
                receipts.forEach { acknowledgeDigest(ctx, it) }
                assemblies.remove(assemblyKey)
            } catch (e: Exception) {
                Log.e("MsgAT", "Incoming message was not committed; relay retains it", e)
            }
        }
    }

    private suspend fun sendDelivered(ctx: ChannelHandlerContext, payload: MessagePayload) {
        val peer = msgDao.getConversationByUuid(payload.senderUuid) ?: return
        if (peer.peerPublicKey.isEmpty()) return
        val ack = MessagePayload(myUuid, java.util.UUID.randomUUID().toString(), 1, 0,
            "ack_deliv", payload.fileId, System.currentTimeMillis())
        val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val encrypted = CryptoManager.encryptPayloadECDH(prefs.getX25519PrivateKey(), peer.peerPublicKey,
            iv, ack.toJson().toByteArray(Charsets.UTF_8))
        ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_MESSAGE, CryptoManager.calculateMailboxId(payload.senderUuid),
            iv, encrypted.first, encrypted.second))
    }

    private fun validFriendIdentity(uuid: String, key: String): Boolean = try {
        uuid != myUuid && uuid.matches(Regex("[a-f0-9]{64}")) && Base64.decode(key, Base64.NO_WRAP).size == 32
    } catch (_: Exception) { false }

    // Processes an incoming friend request and inserts it into the local database.
    private fun handleFriendRequest(ctx: ChannelHandlerContext, msg: GhostPacket) {
        val iv = msg.nonceOrIv ?: return
        val cipherText = msg.encryptedData ?: return
        val authTag = msg.authTag ?: return

        enqueue {
            try {

                val decryptedBytes = CryptoManager.decryptPayloadFallback(myUuid, myUuid, iv, cipherText, authTag)
                val payloadStr = String(decryptedBytes, Charsets.UTF_8)
                val jsonObj = org.json.JSONObject(payloadStr)

                val senderUuid = jsonObj.optString("senderUuid")
                val senderPublicKey = jsonObj.optString("senderPublicKey")

                if (!validFriendIdentity(senderUuid, senderPublicKey)) return@enqueue
                if (msgDao.getConversationByUuid(senderUuid)?.peerPublicKey?.isNotEmpty() == true) {
                    acknowledge(ctx, msg)
                    return@enqueue
                }
                if (senderUuid.isNotEmpty()) {
                    val request = org.msgat.data.db.FriendRequest(senderUuid, "Loading...", "", senderPublicKey, System.currentTimeMillis())
                    msgDao.insertFriendRequest(request)

                    val jsonReq = org.json.JSONObject()
                    jsonReq.put("targetUuid", senderUuid)
                    val payload = jsonReq.toString().toByteArray(Charsets.UTF_8)
                    ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_GET_PROFILE, myMailboxId, ByteArray(16), payload, ByteArray(16)))
                }

                acknowledge(ctx, msg)
            } catch (e: Exception) {
                Log.e("MsgAT", "Failed to decrypt friend request", e)
            }
        }
    }

    // Updates the local database with fetched profile information for a contact.
    private fun handleProfileResp(ctx: ChannelHandlerContext, msg: GhostPacket) {
        enqueue {
            try {
                val json = String(msg.encryptedData ?: return@enqueue, Charsets.UTF_8)
                val obj = org.json.JSONObject(json)
                val uuid = obj.optString("uuid")
                val nickname = obj.optString("nickname", "Unknown")
                val avatarData = obj.optString("avatarData", "")

                if (!uuid.matches(Regex("[a-f0-9]{64}"))) return@enqueue
                val req = msgDao.getFriendRequestByUuid(uuid)
                val conv = msgDao.getConversationByUuid(uuid)
                if (req == null && conv == null) return@enqueue
                var finalAvatarPath = conv?.avatarPath ?: req?.avatarPath ?: ""
                if (avatarData.isNotEmpty()) {
                    try {
                        val bytes = Base64.decode(avatarData, Base64.DEFAULT)
                        val directory = java.io.File(org.msgat.MsgATApp.instance.filesDir, "media").apply { mkdirs() }
                        val file = java.io.File(directory, "avatar_${uuid}.jpg")
                        val atomic = android.util.AtomicFile(file)
                        val stream = atomic.startWrite()
                        try { stream.write(bytes); atomic.finishWrite(stream) }
                        catch (error: Exception) { atomic.failWrite(stream); throw error }
                        finalAvatarPath = android.net.Uri.fromFile(file).toString()
                    } catch (e: Exception) {
                        Log.e("MsgAT", "Failed to decode avatarData", e)
                    }
                } else { finalAvatarPath = "" }

                if (req != null) {
                    msgDao.insertFriendRequest(req.copy(nickname = nickname, avatarPath = finalAvatarPath))
                }

                if (conv != null) {
                    msgDao.insertConversation(conv.copy(alias = nickname, avatarPath = finalAvatarPath))
                }
            } catch (e: Exception) {
                Log.e("MsgAT", "Failed to parse profile resp", e)
            }
        }
    }

    // Acknowledges accepted friend requests and updates local profile records.
    private fun handleFriendAccepted(ctx: ChannelHandlerContext, msg: GhostPacket) {
        val iv = msg.nonceOrIv ?: return
        val cipherText = msg.encryptedData ?: return
        val authTag = msg.authTag ?: return

        enqueue {
            try {
                val decryptedBytes = CryptoManager.decryptPayloadFallback(myUuid, myUuid, iv, cipherText, authTag)
                val payloadStr = String(decryptedBytes, Charsets.UTF_8)
                val jsonObj = org.json.JSONObject(payloadStr)

                val senderUuid = jsonObj.optString("senderUuid")
                val senderPublicKey = jsonObj.optString("senderPublicKey")

                if (!validFriendIdentity(senderUuid, senderPublicKey)) return@enqueue
                val existing = msgDao.getConversationByUuid(senderUuid)
                if (existing == null || (existing.peerPublicKey.isNotEmpty() && existing.peerPublicKey != senderPublicKey)) {
                    acknowledge(ctx, msg)
                    return@enqueue
                }
                if (senderUuid.isNotEmpty()) {
                    msgDao.insertConversation(existing.copy(peerPublicKey = senderPublicKey))
                    msgDao.deleteFriendRequest(senderUuid)

                    val jsonReq = org.json.JSONObject()
                    jsonReq.put("targetUuid", senderUuid)
                    val payload = jsonReq.toString().toByteArray(Charsets.UTF_8)
                    ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_GET_PROFILE, myMailboxId, ByteArray(16), payload, ByteArray(16)))
                }

                acknowledge(ctx, msg)

            } catch (e: Exception) {
                Log.e("MsgAT", "Failed to decrypt friend accepted", e)
            }
        }
    }

    // Deletes the conversation when a peer terminates the friendship.
    private fun handleFriendDeleted(ctx: ChannelHandlerContext, msg: GhostPacket) {
        val iv = msg.nonceOrIv ?: return
        val cipherText = msg.encryptedData ?: return
        val authTag = msg.authTag ?: return
        enqueue {
            try {
                val myX25519PrivateKey = prefs.getX25519PrivateKey()
                val friends = msgDao.getConversationList()
                for (friend in friends) {
                    try {
                        val peerPublicKeyBase64 = friend.peerPublicKey
                        if (peerPublicKeyBase64.isEmpty()) continue
                        val decryptedBytes = CryptoManager.decryptPayloadECDH(myX25519PrivateKey, peerPublicKeyBase64, iv, cipherText, authTag)
                        val payloadStr = String(decryptedBytes, Charsets.UTF_8)
                        val jsonObj = org.json.JSONObject(payloadStr)
                        val senderUuid = jsonObj.optString("senderUuid")
                        if (senderUuid == friend.uuid) {
                            for (message in msgDao.getMessageListByConversation(senderUuid)) {
                                if (message.ext != "txt") org.msgat.core.media.MediaStorageManager.deleteMediaFile(
                                    org.msgat.MsgATApp.instance, message.messageId, message.ext)
                            }
                            msgDao.deleteConversation(senderUuid)
                            msgDao.clearMessages(senderUuid)
                            break
                        }
                    } catch (_: Exception) {}
                }
                acknowledge(ctx, msg)
            } catch (e: Exception) {
                Log.e("MsgAT", "Failed to handle friend deleted", e)
            }
        }
    }

    // Revokes a pending friend request from the local database.
    private fun handleRequestRevoked(ctx: ChannelHandlerContext, msg: GhostPacket) {
        val iv = msg.nonceOrIv ?: return
        val cipherText = msg.encryptedData ?: return
        val authTag = msg.authTag ?: return
        enqueue {
            try {
                val decryptedBytes = CryptoManager.decryptPayloadFallback(myUuid, myUuid, iv, cipherText, authTag)
                val payloadStr = String(decryptedBytes, Charsets.UTF_8)
                val jsonObj = org.json.JSONObject(payloadStr)
                val senderUuid = jsonObj.optString("senderUuid")
                if (senderUuid.isNotEmpty()) {
                    msgDao.deleteFriendRequest(senderUuid)
                }
                acknowledge(ctx, msg)
            } catch (e: Exception) {
                Log.e("MsgAT", "Failed to handle request revoked", e)
            }
        }
    }

    // Marks a contact's alias as [Deleted Account] when they wipe their identity.
    private fun handleAccountReset(ctx: ChannelHandlerContext, msg: GhostPacket) {
        val iv = msg.nonceOrIv ?: return
        val cipherText = msg.encryptedData ?: return
        val authTag = msg.authTag ?: return
        enqueue {
            try {
                val myX25519PrivateKey = prefs.getX25519PrivateKey()
                val friends = msgDao.getConversationList()
                for (friend in friends) {
                    try {
                        val peerPublicKeyBase64 = friend.peerPublicKey
                        if (peerPublicKeyBase64.isEmpty()) continue
                        val decryptedBytes = CryptoManager.decryptPayloadECDH(myX25519PrivateKey, peerPublicKeyBase64, iv, cipherText, authTag)
                        val payloadStr = String(decryptedBytes, Charsets.UTF_8)
                        val jsonObj = org.json.JSONObject(payloadStr)
                        val senderUuid = jsonObj.optString("senderUuid")
                        if (senderUuid == friend.uuid) {
                            val updatedName = friend.alias + " [Identity deleted]"
                            val conv = org.msgat.data.db.Conversation(friend.uuid, updatedName, friend.avatarPath, "") // Set peer public key empty to block send
                            msgDao.insertConversation(conv)
                            break
                        }
                    } catch (_: Exception) {}
                }
                acknowledge(ctx, msg)
            } catch (e: Exception) {
                Log.e("MsgAT", "Failed to handle account reset", e)
            }
        }
    }

    // Handles incoming WebRTC call signaling packets.
    private fun handleCallSignaling(ctx: ChannelHandlerContext, msg: GhostPacket) {
        val iv = msg.nonceOrIv ?: return
        val cipherText = msg.encryptedData ?: return
        val authTag = msg.authTag ?: return

        enqueue {
            try {
                var payloadStr: String? = null
                val myX25519PrivateKey = prefs.getX25519PrivateKey()
                val friends = msgDao.getConversationList()

                for (friend in friends) {
                    try {
                        val peerPublicKeyBase64 = friend.peerPublicKey
                        if (peerPublicKeyBase64.isEmpty()) continue
                        val decryptedBytes = CryptoManager.decryptPayloadECDH(myX25519PrivateKey, peerPublicKeyBase64, iv, cipherText, authTag)
                        val candidate = String(decryptedBytes, Charsets.UTF_8)
                        if (org.json.JSONObject(candidate).optString("senderUuid") != friend.uuid) return@enqueue
                        payloadStr = candidate
                        break
                    } catch (_: Exception) {}
                }

                if (payloadStr != null) {
                    val jsonObj = org.json.JSONObject(payloadStr)
                    val senderUuid = jsonObj.optString("senderUuid")
                    if (senderUuid.isEmpty()) return@enqueue

                    val callHandler = org.msgat.core.call.CallSignalingHandler.getInstance()

                    when (msg.type) {
                        GhostPacket.TYPE_CALL_OFFER -> {
                            val sdp = jsonObj.getString("sdp")
                            callHandler.onOfferReceived(senderUuid, sdp)
                        }
                        GhostPacket.TYPE_CALL_ANSWER -> {
                            val sdp = jsonObj.getString("sdp")
                            callHandler.onAnswerReceived(senderUuid, sdp)
                        }
                        GhostPacket.TYPE_CALL_ICE -> {
                            val sdpMid = jsonObj.getString("sdpMid")
                            val sdpMLineIndex = jsonObj.getInt("sdpMLineIndex")
                            val sdp = jsonObj.getString("sdp")
                            callHandler.onIceCandidateReceived(senderUuid, sdpMid, sdpMLineIndex, sdp)
                        }
                        GhostPacket.TYPE_CALL_REJECT -> {
                            callHandler.onRejectReceived(senderUuid)
                        }
                        GhostPacket.TYPE_CALL_END -> {
                            val duration = jsonObj.optLong("duration", -1L)
                            callHandler.onEndReceived(senderUuid, duration)
                        }
                        GhostPacket.TYPE_CALL_BUSY -> {
                            callHandler.onBusyReceived(senderUuid)
                        }
                        GhostPacket.TYPE_CALL_SYNC -> {
                            val duration = jsonObj.optLong("duration", 0L)
                            callHandler.onSyncReceived(senderUuid, duration)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MsgAT", "Failed to handle call signaling", e)
            }
        }
    }

    // Handles the sudden disconnection or closure of the TCP channel.
    override fun channelInactive(ctx: ChannelHandlerContext) {
        onStateChange(SyncState.DISCONNECTED)
        inbox.close()
        scope.cancel()
    }
}
