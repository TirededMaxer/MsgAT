package org.msgat.core.call

import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.msgat.AppState
import org.msgat.core.crypto.CryptoManager
import org.msgat.data.db.ChatMessage
import org.msgat.model.GhostPacket
import org.msgat.model.MessagePayload
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class CallSignalingHandler private constructor() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val callManager get() = CallManager.getInstance()

    var pendingOfferSdp: String? = null
        private set

    init {
        callManager.setSignalingHandler(this)
    }

    private suspend fun getPeerPublicKey(uuid: String): String? {
        val conv = AppState.msgDao.getConversationByUuid(uuid)
        return if (conv?.peerPublicKey.isNullOrEmpty()) null else conv?.peerPublicKey
    }

    private fun sendSignalingPacket(peerUuid: String, type: Byte, payload: JsonObject) {
        scope.launch {
            val peerPubKey = getPeerPublicKey(peerUuid) ?: return@launch
            val securePrefs = AppState.securePrefs
            val nettyClient = AppState.nettyClient

            payload.addProperty("senderUuid", securePrefs.myUuid)
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

    fun sendOffer(sdp: String, peerUuid: String) {
        val payload = JsonObject().apply {
            addProperty("type", "offer")
            addProperty("sdp", sdp)
        }
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_OFFER, payload)
    }

    fun sendAnswer(sdp: String, peerUuid: String) {
        val payload = JsonObject().apply {
            addProperty("type", "answer")
            addProperty("sdp", sdp)
        }
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_ANSWER, payload)
    }

    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String, peerUuid: String) {
        val payload = JsonObject().apply {
            addProperty("type", "ice")
            addProperty("sdpMid", sdpMid)
            addProperty("sdpMLineIndex", sdpMLineIndex)
            addProperty("sdp", sdp)
        }
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_ICE, payload)
    }

    fun sendReject(peerUuid: String) {
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_REJECT, JsonObject())
    }

    fun sendEnd(peerUuid: String, duration: Long) {
        val payload = JsonObject().apply {
            addProperty("duration", duration)
        }
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_END, payload)
    }

    fun sendBusy(peerUuid: String) {
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_BUSY, JsonObject())
    }

    fun sendSync(peerUuid: String, duration: Long) {
        val payload = JsonObject().apply {
            addProperty("duration", duration)
        }
        sendSignalingPacket(peerUuid, GhostPacket.TYPE_CALL_SYNC, payload)
    }

    fun onOfferReceived(senderUuid: String, sdp: String) {
        pendingOfferSdp = sdp
        scope.launch(Dispatchers.Main) {
            callManager.handleIncomingOffer(sdp, senderUuid)
        }
    }

    fun onAnswerReceived(senderUuid: String, sdp: String) {
        scope.launch(Dispatchers.Main) {
            callManager.handleRemoteAnswer(sdp)
        }
    }

    fun onIceCandidateReceived(senderUuid: String, sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        scope.launch(Dispatchers.Main) {
            callManager.handleRemoteIceCandidate(sdpMid, sdpMLineIndex, sdp)
        }
    }

    fun onRejectReceived(senderUuid: String) {
        scope.launch(Dispatchers.Main) {
            callManager.remoteRejectedCall()
        }
    }

    fun onEndReceived(senderUuid: String, duration: Long) {
        scope.launch(Dispatchers.Main) {
            callManager.remoteEndedCall(duration)
        }
    }

    fun onBusyReceived(senderUuid: String) {
        scope.launch(Dispatchers.Main) {
            callManager.remoteBusyCall()
        }
    }

    fun onSyncReceived(senderUuid: String, duration: Long) {
        scope.launch(Dispatchers.Main) {
            callManager.remoteSyncReceived(duration)
        }
    }

    fun sendCallRecordMessage(peerUuid: String, text: String) {
        scope.launch(Dispatchers.IO) {
            val db = AppState.msgDao
            val securePrefs = AppState.securePrefs
            val myUuid = securePrefs.myUuid
            val convo = db.getConversationByUuid(peerUuid)
            val peerPublicKeyBase64 = convo?.peerPublicKey ?: ""

            if (peerPublicKeyBase64.isNotEmpty()) {
                val msgId = UUID.randomUUID().toString()
                val chatMsg = ChatMessage(msgId, peerUuid, true, text, "txt", System.currentTimeMillis())
                db.insertMessage(chatMsg)

                val payload = MessagePayload(
                    senderUuid = myUuid,
                    fileId = msgId,
                    total = 1,
                    index = 0,
                    ext = "txt",
                    data = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)),
                    timestamp = System.currentTimeMillis()
                )
                val jsonBytes = payload.toJson().toByteArray(Charsets.UTF_8)
                val iv = ByteArray(16)
                SecureRandom().nextBytes(iv)

                val myPrivateKey = securePrefs.getX25519PrivateKey()
                val pair = CryptoManager.encryptPayloadECDH(myPrivateKey, peerPublicKeyBase64, iv, jsonBytes)

                val targetMailboxId = CryptoManager.calculateMailboxId(peerUuid)
                val packet = GhostPacket(
                    type = GhostPacket.TYPE_MESSAGE,
                    targetMailboxId = targetMailboxId,
                    nonceOrIv = iv,
                    encryptedData = pair.first,
                    authTag = pair.second
                )
                AppState.nettyClient.sendPacket(packet)
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: CallSignalingHandler? = null

        fun getInstance(): CallSignalingHandler {
            return INSTANCE ?: synchronized(this) {
                val instance = CallSignalingHandler()
                INSTANCE = instance
                instance
            }
        }
    }
}
