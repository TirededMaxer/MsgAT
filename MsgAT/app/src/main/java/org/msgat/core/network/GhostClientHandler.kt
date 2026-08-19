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

import org.msgat.core.crypto.securestorage.SecurePrefs

class GhostClientHandler(
    private val prefs: SecurePrefs,
    private val msgDao: MsgDao,
    private val onStateChange: (SyncState) -> Unit
) : SimpleChannelInboundHandler<GhostPacket>() {

    private val myUuid = prefs.myUuid
    private val myPrivateKey = prefs.getOrCreatePrivateKey()
    private val myMailboxId = CryptoManager.calculateMailboxId(myUuid)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val fileAssembler = mutableMapOf<String, MutableMap<Int, String>>()
    private val fileAssemblerTimestamps = mutableMapOf<String, Long>()

    // Handles the successful establishment of a TCP connection to the server.
    override fun channelActive(ctx: ChannelHandlerContext) {
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
            GhostPacket.TYPE_AUTH_CHAL -> {
                val nonce = msg.nonceOrIv ?: return
                val (pubKey, signature) = CryptoManager.signChallenge(myPrivateKey, nonce)

                val responsePayload = pubKey + signature
                ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_AUTH_RESP, myMailboxId, nonce, responsePayload, ByteArray(16)))

                try {
                    val json = org.json.JSONObject()
                    json.put("uuid", myUuid)
                    json.put("nickname", prefs.myNickname ?: "Unknown")
                    val avPath = prefs.myAvatarPath ?: ""
                    json.put("avatarPath", avPath)

                    if (avPath.startsWith("file://") || avPath.startsWith("content://")) {
                        try {
                            val uri = android.net.Uri.parse(avPath)
                            val file = java.io.File(uri.path!!)
                            if (file.exists()) {
                                val bytes = file.readBytes()
                                json.put("avatarData", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                            }
                        } catch (e: Exception) {
                            Log.e("MsgAT", "Failed to encode avatarData", e)
                        }
                    }

                    val payload = json.toString().toByteArray(Charsets.UTF_8)
                    ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_UPDATE_PROFILE, myMailboxId, ByteArray(16), payload, ByteArray(16)))
                } catch (e: Exception) {
                    Log.e("MsgAT", "Failed to send profile update", e)
                }
            }

            GhostPacket.TYPE_MESSAGE -> {
                handleIncomingMessage(ctx, msg)
            }

            GhostPacket.TYPE_SEARCH_USER -> {
                handleFriendRequest(ctx, msg)
            }

            GhostPacket.TYPE_OFFLINE_END -> {
                onStateChange(SyncState.SYNCED)
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
            GhostPacket.TYPE_CALL_BUSY -> {
                handleCallSignaling(ctx, msg)
            }
        }
    }

    // Processes and decrypts standard P2P text or media messages.
    private fun handleIncomingMessage(ctx: ChannelHandlerContext, msg: GhostPacket) {
        val iv = msg.nonceOrIv ?: return
        val cipherText = msg.encryptedData ?: return
        val authTag = msg.authTag ?: return

        scope.launch {
            try {
                var decryptedPayload: MessagePayload? = null

                val myX25519PrivateKey = prefs.getX25519PrivateKey()
                val friends = msgDao.getConversationList()
                for (friend in friends) {
                    try {
                        val peerPublicKeyBase64 = friend.peerPublicKey
                        if (peerPublicKeyBase64.isEmpty()) continue

                        val decryptedBytes = CryptoManager.decryptPayloadECDH(myX25519PrivateKey, peerPublicKeyBase64, iv, cipherText, authTag)
                        val payloadStr = String(decryptedBytes, Charsets.UTF_8)
                        decryptedPayload = MessagePayload.fromJson(payloadStr)
                        break
                    } catch (_: Exception) {

                    }
                }

                if (decryptedPayload != null) {
                    val fileId = decryptedPayload.fileId
                    val total = decryptedPayload.total
                    var finalData = ""

                    if (total > 1) {
                        val now = System.currentTimeMillis()
                        fileAssemblerTimestamps.entries.removeIf { now - it.value > 10 * 60 * 1000L }
                        fileAssembler.keys.retainAll(fileAssemblerTimestamps.keys)

                        val chunks = fileAssembler.getOrPut(fileId) { mutableMapOf() }
                        fileAssemblerTimestamps[fileId] = now
                        chunks[decryptedPayload.index] = decryptedPayload.data
                        if (chunks.size == total) {
                            finalData = (0 until total).joinToString("") { chunks[it] ?: "" }
                            fileAssembler.remove(fileId)
                            fileAssemblerTimestamps.remove(fileId)
                        }
                    } else {

                        finalData = decryptedPayload.data
                    }

                    if (finalData.isNotEmpty() || decryptedPayload.action == "revoke") {
                        if (decryptedPayload.ext == "ack_deliv") {
                            val ackMsg = msgDao.getMessageById(finalData)
                            if (ackMsg != null) {
                                msgDao.updateMessageStatusUpTo(ackMsg.conversationUuid, ackMsg.timestamp, 2)
                            } else {
                                msgDao.updateMessageStatus(finalData, 2)
                            }
                        } else if (decryptedPayload.ext == "ack_read") {
                            val ackMsg = msgDao.getMessageById(finalData)
                            if (ackMsg != null) {
                                msgDao.updateMessageStatusUpTo(ackMsg.conversationUuid, ackMsg.timestamp, 3)
                            } else {
                                msgDao.updateMessageStatus(finalData, 3)
                            }
                        } else {
                            val contentToSave = if (decryptedPayload.ext == "txt") {
                                try {
                                    String(Base64.decode(finalData, Base64.DEFAULT), Charsets.UTF_8)
                                } catch (_: Exception) {
                                    finalData 
                                }
                            } else {
                                try {
                                    val context = org.msgat.MsgATApp.instance.applicationContext
                                    var cleanBase64 = finalData.replace(Regex("\\s+"), "").replace("=", "")
                                    val padding = cleanBase64.length % 4
                                    if (padding > 0) {
                                        cleanBase64 += "=".repeat(4 - padding)
                                    }
                                    val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                                    val savedFile = org.msgat.core.media.MediaStorageManager.saveMediaBytes(context, fileId, decryptedPayload.ext, bytes)
                                    savedFile.absolutePath
                                } catch (e: Exception) {
                                    Log.e("MsgAT", "Failed to save incoming media to sandbox", e)
                                    finalData
                                }
                            }

                            if (decryptedPayload.action == "revoke" && !decryptedPayload.targetMessageId.isNullOrEmpty()) {
                                val targetMsg = msgDao.getMessageById(decryptedPayload.targetMessageId)
                                if (targetMsg != null && targetMsg.ext != "txt") {
                                    val context = org.msgat.MsgATApp.instance.applicationContext
                                    org.msgat.core.media.MediaStorageManager.deleteMediaFile(context, targetMsg.messageId, targetMsg.ext)
                                }
                                msgDao.updateMessageRevoked(decryptedPayload.targetMessageId)
                            } else if (decryptedPayload.action == "edit" && !decryptedPayload.targetMessageId.isNullOrEmpty()) {
                                val editedContent = try {
                                    String(Base64.decode(finalData, Base64.DEFAULT), Charsets.UTF_8)
                                } catch (_: Exception) {
                                    finalData
                                }
                                msgDao.updateMessageContent(decryptedPayload.targetMessageId, editedContent)
                            } else {
                                val chatMsg = ChatMessage(
                                    fileId,
                                    decryptedPayload.senderUuid,
                                    false,
                                    contentToSave,
                                    decryptedPayload.ext,
                                    decryptedPayload.timestamp,
                                    false,
                                    decryptedPayload.replyToId,
                                    decryptedPayload.replyToSender,
                                    decryptedPayload.replyToContent,
                                    status = 1
                                )
                                msgDao.insertMessage(chatMsg)
                            }

                            // Send ACK_DELIVERED back to sender
                            val conv = msgDao.getConversationByUuid(decryptedPayload.senderUuid)
                            val peerPublicKeyBase64 = conv?.peerPublicKey ?: ""
                            if (peerPublicKeyBase64.isNotEmpty()) {
                                try {
                                    val ackPayload = MessagePayload(
                                        senderUuid = myUuid,
                                        fileId = java.util.UUID.randomUUID().toString(),
                                        total = 1,
                                        index = 0,
                                        ext = "ack_deliv",
                                        data = fileId,
                                        timestamp = System.currentTimeMillis()
                                    )
                                    val jsonBytes = ackPayload.toJson().toByteArray(Charsets.UTF_8)
                                    val iv = ByteArray(16)
                                    java.security.SecureRandom().nextBytes(iv)
                                    val pair = CryptoManager.encryptPayloadECDH(myX25519PrivateKey, peerPublicKeyBase64, iv, jsonBytes)
                                    val targetMailboxId = CryptoManager.calculateMailboxId(decryptedPayload.senderUuid)
                                    val packet = GhostPacket(GhostPacket.TYPE_MESSAGE, targetMailboxId, iv, pair.first, pair.second)
                                    ctx.writeAndFlush(packet)
                                } catch (e: Exception) {
                                    Log.e("MsgAT", "Failed to send ack_deliv", e)
                                }
                            }

                            if (!org.msgat.MsgATApp.instance.isAppInForeground) {
                                val context = org.msgat.MsgATApp.instance.applicationContext
                                val alias = conv?.alias ?: "Unknown"
                                org.msgat.NotificationHelper.showMessageNotification(
                                    context,
                                    decryptedPayload.senderUuid,
                                    alias,
                                    decryptedPayload.ext,
                                    contentToSave
                                )
                            }
                        }
                    }
                } else {
                    Log.i("MsgAT", "No message found for $decryptedPayload")
                }

                val ackPacket = GhostPacket(GhostPacket.TYPE_ACK, myMailboxId, ByteArray(16), ByteArray(0), ByteArray(16))
                ctx.writeAndFlush(ackPacket)

            } catch (e: Exception) {
                Log.e("MsgAT", "FATAL", e)
            }
        }
    }

    // Processes an incoming friend request and inserts it into the local database.
    private fun handleFriendRequest(ctx: ChannelHandlerContext, msg: GhostPacket) {
        val iv = msg.nonceOrIv ?: return
        val cipherText = msg.encryptedData ?: return
        val authTag = msg.authTag ?: return

        scope.launch {
            try {

                val decryptedBytes = CryptoManager.decryptPayloadFallback(myUuid, myUuid, iv, cipherText, authTag)
                val payloadStr = String(decryptedBytes, Charsets.UTF_8)
                val jsonObj = org.json.JSONObject(payloadStr)

                val senderUuid = jsonObj.optString("senderUuid")
                val senderPublicKey = jsonObj.optString("senderPublicKey")

                if (senderUuid.isNotEmpty()) {
                    val request = org.msgat.data.db.FriendRequest(senderUuid, "Loading...", "", senderPublicKey, System.currentTimeMillis())
                    msgDao.insertFriendRequest(request)

                    val jsonReq = org.json.JSONObject()
                    jsonReq.put("targetUuid", senderUuid)
                    val payload = jsonReq.toString().toByteArray(Charsets.UTF_8)
                    ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_GET_PROFILE, myMailboxId, ByteArray(16), payload, ByteArray(16)))
                }

                val ackPacket = GhostPacket(GhostPacket.TYPE_ACK, myMailboxId, ByteArray(16), ByteArray(0), ByteArray(16))
                ctx.writeAndFlush(ackPacket)
            } catch (e: Exception) {
                Log.e("MsgAT", "Failed to decrypt friend request", e)
            }
        }
    }

    // Updates the local database with fetched profile information for a contact.
    private fun handleProfileResp(ctx: ChannelHandlerContext, msg: GhostPacket) {
        scope.launch {
            try {
                val json = String(msg.encryptedData ?: return@launch, Charsets.UTF_8)
                val obj = org.json.JSONObject(json)
                val uuid = obj.optString("uuid")
                val nickname = obj.optString("nickname", "Unknown")
                val avatarData = obj.optString("avatarData", "")

                var finalAvatarPath = obj.optString("avatarPath", "")
                if (avatarData.isNotEmpty()) {
                    try {
                        val bytes = Base64.decode(avatarData, Base64.DEFAULT)
                        val file = java.io.File(org.msgat.MsgATApp.instance.cacheDir, "avatar_${uuid}.jpg")
                        file.writeBytes(bytes)
                        finalAvatarPath = android.net.Uri.fromFile(file).toString()
                    } catch (e: Exception) {
                        Log.e("MsgAT", "Failed to decode avatarData", e)
                    }
                }

                val req = msgDao.getFriendRequestByUuid(uuid)
                if (req != null) {
                    msgDao.insertFriendRequest(req.copy(nickname = nickname, avatarPath = finalAvatarPath))
                }

                val conv = msgDao.getConversationByUuid(uuid)
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

        scope.launch {
            try {
                val decryptedBytes = CryptoManager.decryptPayloadFallback(myUuid, myUuid, iv, cipherText, authTag)
                val payloadStr = String(decryptedBytes, Charsets.UTF_8)
                val jsonObj = org.json.JSONObject(payloadStr)

                val senderUuid = jsonObj.optString("senderUuid")
                val senderPublicKey = jsonObj.optString("senderPublicKey")

                if (senderUuid.isNotEmpty()) {
                    val conv = org.msgat.data.db.Conversation(senderUuid, "Loading...", "", senderPublicKey)
                    msgDao.insertConversation(conv)

                    val jsonReq = org.json.JSONObject()
                    jsonReq.put("targetUuid", senderUuid)
                    val payload = jsonReq.toString().toByteArray(Charsets.UTF_8)
                    ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_GET_PROFILE, myMailboxId, ByteArray(16), payload, ByteArray(16)))
                }

                val ackPacket = GhostPacket(GhostPacket.TYPE_ACK, myMailboxId, ByteArray(16), ByteArray(0), ByteArray(16))
                ctx.writeAndFlush(ackPacket)

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
        scope.launch {
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
                        if (senderUuid.isNotEmpty()) {
                            msgDao.deleteConversation(senderUuid)
                            msgDao.clearMessages(senderUuid)
                            break
                        }
                    } catch (_: Exception) {}
                }
                ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_ACK, myMailboxId, ByteArray(16), ByteArray(0), ByteArray(16)))
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
        scope.launch {
            try {
                val decryptedBytes = CryptoManager.decryptPayloadFallback(myUuid, myUuid, iv, cipherText, authTag)
                val payloadStr = String(decryptedBytes, Charsets.UTF_8)
                val jsonObj = org.json.JSONObject(payloadStr)
                val senderUuid = jsonObj.optString("senderUuid")
                if (senderUuid.isNotEmpty()) {
                    msgDao.deleteFriendRequest(senderUuid)
                }
                ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_ACK, myMailboxId, ByteArray(16), ByteArray(0), ByteArray(16)))
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
        scope.launch {
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
                        if (senderUuid.isNotEmpty()) {
                            val updatedName = friend.alias + " [已注销]"
                            val conv = org.msgat.data.db.Conversation(friend.uuid, updatedName, friend.avatarPath, "") // Set peer public key empty to block send
                            msgDao.insertConversation(conv)
                            break
                        }
                    } catch (_: Exception) {}
                }
                ctx.writeAndFlush(GhostPacket(GhostPacket.TYPE_ACK, myMailboxId, ByteArray(16), ByteArray(0), ByteArray(16)))
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

        scope.launch {
            try {
                var payloadStr: String? = null
                val myX25519PrivateKey = prefs.getX25519PrivateKey()
                val friends = msgDao.getConversationList()
                
                for (friend in friends) {
                    try {
                        val peerPublicKeyBase64 = friend.peerPublicKey
                        if (peerPublicKeyBase64.isEmpty()) continue
                        val decryptedBytes = CryptoManager.decryptPayloadECDH(myX25519PrivateKey, peerPublicKeyBase64, iv, cipherText, authTag)
                        payloadStr = String(decryptedBytes, Charsets.UTF_8)
                        break
                    } catch (_: Exception) {}
                }

                if (payloadStr != null) {
                    val jsonObj = org.json.JSONObject(payloadStr)
                    val senderUuid = jsonObj.optString("senderUuid")
                    if (senderUuid.isEmpty()) return@launch

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
    }
}