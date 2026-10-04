package org.msgat.core.call

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.msgat.MsgATApp
import org.msgat.core.crypto.CryptoManager
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.core.network.NettyClient
import org.msgat.data.db.AppDatabase
import org.msgat.data.db.ChatMessage
import org.msgat.model.GhostPacket
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

class CallSignalingHandler private constructor(context: Context) {
    private val securePrefs = MsgATApp.instance.securePrefs
    private val nettyClient = MsgATApp.instance.nettyClient
    private val msgDao = MsgATApp.instance.db.msgDao()
    private val callManager = CallManager.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val outgoingSignals = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    init {
        scope.launch {
            for (send in outgoingSignals) {
                try { send() } catch (error: Exception) { Log.e("MsgAT-Call", "Could not send call signaling", error) }
            }
        }
    }

    var pendingOffer: SessionDescription? = null
        private set

    init {
        callManager.setSignalingHandler(this)
    }

    private suspend fun getPeerPublicKey(uuid: String): String? {
        val conv = msgDao.getConversationByUuid(uuid)
        return if (conv?.peerPublicKey.isNullOrEmpty()) null else conv?.peerPublicKey
    }

    private fun sendSignalingPacket(peerUuid: String, type: Byte, payload: JSONObject) {
        outgoingSignals.trySend {
            val peerPubKey = getPeerPublicKey(peerUuid) ?: return@trySend

            payload.put("senderUuid", securePrefs.myUuid)
            val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)

            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)

            val myPrivateKey = securePrefs.getX25519PrivateKey()
            val (cipherText, authTag) = CryptoManager.encryptPayloadECDH(myPrivateKey, peerPubKey, iv, payloadBytes)

            val targetMailboxId = CryptoManager.calculateMailboxId(peerUuid)
            val packet = GhostPacket(type, targetMailboxId, iv, cipherText, authTag)
            nettyClient.sendPacket(packet)
        }
    }

    fun sendOffer(offer: SessionDescription, peerUuid: String) {
        val payload = JSONObject().apply {
            put("type", "offer")
            put("sdp", offer.description)
        }
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_OFFER, payload)
    }

    fun sendAnswer(answer: SessionDescription, peerUuid: String) {
        val payload = JSONObject().apply {
            put("type", "answer")
            put("sdp", answer.description)
        }
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_ANSWER, payload)
    }

    fun sendIceCandidate(candidate: IceCandidate, peerUuid: String) {
        val payload = JSONObject().apply {
            put("type", "ice")
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("sdp", candidate.sdp)
        }
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_ICE, payload)
    }

    fun sendReject(peerUuid: String) {
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_REJECT, JSONObject())
    }

    fun sendEnd(peerUuid: String, duration: Long) {
        val payload = JSONObject().apply {
            put("duration", duration)
        }
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_END, payload)
    }

    fun sendBusy(peerUuid: String) {
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_BUSY, JSONObject())
    }

    // Handlers called by GhostClientHandler when packets arrive

    fun onOfferReceived(senderUuid: String, sdp: String) {
        scope.launch(Dispatchers.Main) {
            if (callManager.callState.value != CallState.IDLE) {
                sendBusy(senderUuid)
                return@launch
            }
            val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
            pendingOffer = offer
            callManager.handleIncomingOffer(offer, senderUuid)
        }
    }

    fun onAnswerReceived(senderUuid: String, sdp: String) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        scope.launch(Dispatchers.Main) {
            if (!callManager.isCurrentPeer(senderUuid)) return@launch
            callManager.handleRemoteAnswer(answer)
        }
    }

    fun onIceCandidateReceived(senderUuid: String, sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
        scope.launch(Dispatchers.Main) {
            if (!callManager.isCurrentPeer(senderUuid)) return@launch
            callManager.handleRemoteIceCandidate(candidate)
        }
    }

    fun onRejectReceived(senderUuid: String) {
        scope.launch(Dispatchers.Main) {
            if (!callManager.isCurrentPeer(senderUuid)) return@launch
            callManager.remoteRejectedCall()
        }
    }

    fun onEndReceived(senderUuid: String, duration: Long) {
        scope.launch(Dispatchers.Main) {
            if (!callManager.isCurrentPeer(senderUuid)) return@launch
            callManager.remoteEndedCall(duration)
        }
    }

    fun onBusyReceived(senderUuid: String) {
        scope.launch(Dispatchers.Main) {
            if (!callManager.isCurrentPeer(senderUuid)) return@launch
            callManager.remoteBusyCall()
        }
    }

    fun sendSync(peerUuid: String, duration: Long) {
        val payload = JSONObject().apply {
            put("duration", duration)
        }
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_SYNC, payload)
    }

    fun onSyncReceived(senderUuid: String, duration: Long) {
        scope.launch(Dispatchers.Main) {
            if (!callManager.isCurrentPeer(senderUuid)) return@launch
            callManager.remoteSyncReceived(duration)
        }
    }

    fun sendCallRecordMessage(peerUuid: String, text: String) {
        scope.launch(Dispatchers.IO) {
            val db = org.msgat.data.db.AppDatabase.getInstance(MsgATApp.instance.applicationContext).msgDao()
            val securePrefs = org.msgat.core.crypto.securestorage.SecurePrefs(MsgATApp.instance.applicationContext)
            val myUuid = securePrefs.myUuid
            val convo = db.getConversationByUuid(peerUuid)
            val peerPublicKeyBase64 = convo?.peerPublicKey ?: ""

            if (peerPublicKeyBase64.isNotEmpty()) {
                val msgId = java.util.UUID.randomUUID().toString()
                val chatMsg = org.msgat.data.db.ChatMessage(msgId, peerUuid, true, text, "txt", System.currentTimeMillis(), isRead = true, status = 0)
                db.insertMessage(chatMsg)

                val payload = org.msgat.model.MessagePayload(
                    senderUuid = myUuid,
                    fileId = msgId,
                    total = 1,
                    index = 0,
                    ext = "txt",
                    data = android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP),
                    timestamp = chatMsg.timestamp
                )
                val jsonBytes = payload.toJson().toByteArray(Charsets.UTF_8)
                val iv = ByteArray(16)
                java.security.SecureRandom().nextBytes(iv)

                val myPrivateKey = securePrefs.getX25519PrivateKey()
                val pair = org.msgat.core.crypto.CryptoManager.encryptPayloadECDH(myPrivateKey, peerPublicKeyBase64, iv, jsonBytes)

                val targetMailboxId = org.msgat.core.crypto.CryptoManager.calculateMailboxId(peerUuid)
                val packet = org.msgat.model.GhostPacket(
                    type = org.msgat.model.GhostPacket.TYPE_MESSAGE,
                    targetMailboxId = targetMailboxId,
                    nonceOrIv = iv,
                    encryptedData = pair.first,
                    authTag = pair.second
                )
                if (MsgATApp.instance.nettyClient.sendPacket(packet)) db.updateMessageStatus(msgId, 1)
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: CallSignalingHandler? = null

        fun getInstance(): CallSignalingHandler {
            return INSTANCE ?: synchronized(this) {
                val instance = CallSignalingHandler(MsgATApp.instance.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
