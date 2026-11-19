// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.call

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.msgat.MsgATApp
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.security.SecureRandom

/** Main-thread call state; bounded outgoing events use the shared Signal coordinator. */
class CallSignalingHandler private constructor() {
    private val manager = CallManager.getInstance()
    private val messaging get() = MsgATApp.instance.nettyClient.secureMessaging
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private data class Outgoing(val peer: String,val id: String,val kind: String,val body: JSONObject,val created: Long)
    private val outgoing = Channel<Outgoing>(128)
    private data class Invitation(val peer: String,val id: String,val sdp: String,val video: Boolean,val nonce: String,
        val deadline: Long,val ice: MutableList<IceCandidate> = mutableListOf())
    private var invitation: Invitation? = null
        set(value) {
            field = value
            updateReception()
        }
    var pendingOffer: SessionDescription? = null
        private set

    init {
        manager.setSignalingHandler(this)
        scope.launch { manager.callState.collect { updateReception() } }
        scope.launch {
            for (event in outgoing) {
                val sent = try { messaging.sendCallSignal(event.peer,event.kind,event.body,event.created) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                if (!sent) {
                    Log.w("MsgAT-Call","Encrypted call signaling could not be queued")
                    if (manager.isCurrentCall(event.peer,event.id)) manager.signalingFailed()
                    if (invitation?.id==event.id && invitation?.peer==event.peer) invitation=null
                }
            }
        }
    }

    private fun updateReception() {
        val state = manager.callState.value
        MsgATApp.instance.setCallReceptionRequested(invitation != null || state != CallState.IDLE && state != CallState.ENDED)
    }

    private fun send(peer: String,id: String,kind: String,body: JSONObject=JSONObject()) {
        val now=System.currentTimeMillis()
        body.put("call_id",id).put("expires_at",now+120_000)
        if (outgoing.trySend(Outgoing(peer,id,kind,body,now)).isFailure) {
            if (manager.isCurrentCall(peer,id)) manager.signalingFailed()
            if (invitation?.id==id && invitation?.peer==peer) invitation=null
        }
    }
    private fun active(peer: String,kind: String,body: JSONObject=JSONObject()) {
        val id=manager.currentCallId ?: return
        if (manager.isCurrentCall(peer,id)) send(peer,id,kind,body)
    }
    fun sendOffer(offer: SessionDescription,peerUuid: String) = active(peerUuid,"call.offer",JSONObject().put("sdp",offer.description).put("video",manager.isVideoCall.value))
    fun sendAnswer(answer: SessionDescription,peerUuid: String) = active(peerUuid,"call.answer",JSONObject().put("sdp",answer.description))
    fun sendIceCandidate(candidate: IceCandidate,peerUuid: String) = active(peerUuid,"call.ice",JSONObject()
        .put("sdp_mid",candidate.sdpMid ?: "").put("mline_index",candidate.sdpMLineIndex).put("candidate",candidate.sdp))
    fun sendReject(peerUuid: String) = active(peerUuid,"call.reject")
    fun sendEnd(peerUuid: String) = active(peerUuid,"call.end")
    fun clearOffer() { pendingOffer=null }

    // Caller must still own this call and answer a fresh, short-lived challenge before we ring.
    // This also prevents an offline/replayed offer from producing a ghost incoming call.
    fun receive(peer: String,kind: String,body: JSONObject) {
        val id=body.getString("call_id")
        val pending=invitation?.takeIf { it.deadline>SystemClock.elapsedRealtime() }
        invitation=pending
        when (kind) {
            "call.offer" -> {
                if (manager.isCurrentCall(peer,id) || pending?.let { it.peer==peer && it.id==id }==true) return
                val video = body.getBoolean("video")
                if (video && !manager.supportsVideo) { send(peer,id,"call.reject"); return }
                if (manager.callState.value!=CallState.IDLE || pending!=null) { send(peer,id,"call.busy"); return }
                val nonce=Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) },Base64.NO_WRAP)
                val next=Invitation(peer,id,body.getString("sdp"),video,nonce,SystemClock.elapsedRealtime()+10_000)
                invitation=next
                scope.launch { delay(10_000); if (invitation===next) invitation=null }
                send(peer,id,"call.challenge",JSONObject().put("nonce",nonce))
            }
            "call.challenge" -> if (manager.isOutgoingCall(peer,id)) send(peer,id,"call.confirm",JSONObject().put("nonce",body.getString("nonce")))
            "call.confirm" -> {
                if (pending==null || pending.peer!=peer || pending.id!=id || pending.nonce!=body.getString("nonce")) return
                if (manager.callState.value!=CallState.IDLE) { invitation=null; send(peer,id,"call.busy"); return }
                pendingOffer=SessionDescription(SessionDescription.Type.OFFER,pending.sdp)
                manager.handleIncomingOffer(peer,id,pending.video)
                invitation=null
                pending.ice.forEach { manager.handleRemoteIceCandidate(it) }
            }
            "call.ice" -> {
                val candidate=IceCandidate(body.getString("sdp_mid").ifEmpty { null },body.getInt("mline_index"),body.getString("candidate"))
                if (manager.isCurrentCall(peer,id)) manager.handleRemoteIceCandidate(candidate)
                else if (pending!=null && pending.peer==peer && pending.id==id && pending.ice.size<256) pending.ice.add(candidate)
            }
            "call.answer" -> if (manager.isCurrentCall(peer,id)) manager.handleRemoteAnswer(SessionDescription(SessionDescription.Type.ANSWER,body.getString("sdp")))
            "call.reject", "call.end", "call.busy" -> {
                if (pending?.peer==peer && pending.id==id) invitation=null
                if (!manager.isCurrentCall(peer,id)) return
                when (kind) {
                    "call.reject" -> manager.remoteRejectedCall()
                    "call.busy" -> manager.remoteBusyCall()
                    else -> manager.remoteEndedCall()
                }
            }
        }
    }

    fun sendCallRecordMessage(peerUuid: String,text: String) {
        scope.launch {
            try { messaging.sendText(peerUuid,text,null) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Log.w("MsgAT-Call","Call record could not be queued") }
        }
    }
    internal fun recordHistory(peer: String, callId: String, outgoing: Boolean, video: Boolean,
                               outcome: CallOutcome, duration: Long, endedAt: Long) {
        scope.launch {
            try { messaging.recordCallHistory(peer,callId,outgoing,video,outcome,duration,endedAt) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Log.w("MsgAT-Call","Call history could not be saved") }
        }
    }
    companion object {
        @Volatile private var instance: CallSignalingHandler? = null
        fun getInstance(): CallSignalingHandler = instance ?: synchronized(this) {
            instance ?: CallSignalingHandler().also { instance=it }
        }
    }
}
