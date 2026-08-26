package org.msgat.core.call

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.MediaStreamTrack
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.msgat.AppState
import org.msgat.utils.DesktopUtils
import java.util.logging.Logger

enum class CallState {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTING,
    CONNECTED,
    ENDED
}

/**
 * Windows voice call manager — real WebRTC engine (webrtc-java / libwebrtc),
 * ported 1:1 in behavior from the Android CallManager:
 *  - P2P-first ICE (multi STUN + optional TURN from settings)
 *  - DISCONNECTED 10s recovery grace, FAILED ends immediately
 *  - Opus 32 kbps sender bitrate enforcement + SDP Opus optimization
 *  - Caller-only call-record messages, RTT stats polling, 30s ring timeout
 *
 * The public method surface is intentionally identical to the previous
 * skeleton so CallSignalingHandler and the UI layer need no changes.
 */
class CallManager private constructor() {

    private val logger = Logger.getLogger("MsgAT-Call")

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private val _rttMs = MutableStateFlow(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: RTCPeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioTrackSource? = null

    private var signalingHandler: CallSignalingHandler? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var durationJob: Job? = null
    private var rttJob: Job? = null
    private var timeoutJob: Job? = null
    private var disconnectGraceJob: Job? = null

    var currentPeerUuid: String? = null
        private set

    private var isCaller: Boolean = false
    private val pendingIceCandidates = mutableListOf<RTCIceCandidate>()
    private var incomingOfferSdp: String? = null

    init {
        initializeWebRTC()
    }

    fun setSignalingHandler(handler: CallSignalingHandler) {
        this.signalingHandler = handler
    }

    private fun initializeWebRTC() {
        try {
            factory = PeerConnectionFactory()
        } catch (e: Exception) {
            logger.severe("Failed to initialize WebRTC PeerConnectionFactory: ${e.message}")
        }
    }

    /**
     * P2P-first ICE configuration (same as Android/macOS):
     * - Multiple STUN servers (Google + Cloudflare + China-reachable endpoints)
     * - Optional TURN relay only when configured in Settings (empty = pure P2P)
     */
    private fun buildIceServers(): List<RTCIceServer> {
        val servers = mutableListOf<RTCIceServer>()

        listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
            "stun:stun.miwifi.com:3478",
            "stun:stun.chat.bilibili.com:3478"
        ).forEach { url ->
            val server = RTCIceServer()
            server.urls.add(url)
            servers += server
        }

        val prefs = AppState.securePrefs
        val turnHost = prefs.turnServer?.trim() ?: ""
        if (turnHost.isNotEmpty()) {
            val turnPort = if (prefs.turnPort > 0) prefs.turnPort else 3478
            val user = prefs.turnUsername ?: ""
            val pass = prefs.turnPassword ?: ""
            val endpoints = mutableListOf(
                "turn:$turnHost:$turnPort",
                "turn:$turnHost:$turnPort?transport=tcp"
            )
            if (prefs.turnUseTls) {
                endpoints += "turns:$turnHost:$turnPort?transport=tcp"
            }
            endpoints.forEach { url ->
                val server = RTCIceServer()
                server.urls.add(url)
                server.username = user
                server.password = pass
                servers += server
            }
        }
        return servers
    }

    private fun createPeerConnection() {
        val rtcConfig = RTCConfiguration()
        rtcConfig.iceServers = buildIceServers()

        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnectionObserver {
            override fun onIceConnectionChange(state: RTCIceConnectionState) {
                logger.info("ICE Connection State: $state")
                when (state) {
                    RTCIceConnectionState.CONNECTED,
                    RTCIceConnectionState.COMPLETED -> {
                        disconnectGraceJob?.cancel()
                        disconnectGraceJob = null
                        if (_callState.value != CallState.CONNECTED) {
                            _callState.value = CallState.CONNECTED
                            startCallTimers()
                            try {
                                peerConnection?.senders?.forEach { sender ->
                                    val track = sender.track
                                    if (track?.kind == MediaStreamTrack.AUDIO_TRACK_KIND) {
                                        val params = sender.parameters
                                        params.encodings.forEach { encoding ->
                                            encoding.maxBitrate = 32_000
                                        }
                                        sender.parameters = params
                                        logger.info("Enforced audio RTP Sender max bitrate to 32 kbps")
                                    }
                                }
                            } catch (e: Exception) {
                                logger.warning("Failed to set audio sender max bitrate: ${e.message}")
                            }
                        }
                    }
                    RTCIceConnectionState.DISCONNECTED -> {
                        // Long-distance links can transiently drop ICE; give a 10s recovery
                        // window before treating the call as dead (prevents instant hang-up).
                        if (disconnectGraceJob == null) {
                            disconnectGraceJob = scope.launch {
                                delay(10_000)
                                disconnectGraceJob = null
                                val st = peerConnection?.iceConnectionState
                                if (st != RTCIceConnectionState.CONNECTED &&
                                    st != RTCIceConnectionState.COMPLETED
                                ) {
                                    endCall()
                                }
                            }
                        }
                    }
                    RTCIceConnectionState.FAILED -> endCall()
                    else -> {}
                }
            }

            override fun onIceGatheringChange(state: RTCIceGatheringState) {
                logger.info("ICE Gathering State: $state")
            }

            override fun onIceCandidate(candidate: RTCIceCandidate) {
                val peerUuid = currentPeerUuid ?: return
                signalingHandler?.sendIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp, peerUuid)
            }
        })

        // Create local audio track (native ADM handles echo cancellation on Windows)
        val audioOptions = AudioOptions()
        audioOptions.echoCancellation = true
        audioOptions.autoGainControl = true
        audioOptions.noiseSuppression = true

        localAudioSource = factory?.createAudioSource(audioOptions)
        localAudioTrack = factory?.createAudioTrack("ARDAMSa0", localAudioSource)

        peerConnection?.addTrack(localAudioTrack, listOf("ARDAMS"))
    }

    fun startOutgoingCall(peerUuid: String) {
        if (_callState.value != CallState.IDLE) return
        _callState.value = CallState.CONNECTING // Lock state immediately to prevent double clicks

        scope.launch {
            val conv = AppState.msgDao.getConversationByUuid(peerUuid)
            if (conv == null || conv.peerPublicKey.isEmpty()) {
                withContext(Dispatchers.Main) {
                    DesktopUtils.showNotification("Voice Call", "Cannot call a non-friend.")
                    _callState.value = CallState.IDLE
                }
                return@launch
            }

            if (_callState.value == CallState.CONNECTING) {
                isCaller = true
                currentPeerUuid = peerUuid
                _callState.value = CallState.OUTGOING_RINGING
                startTimeoutTimer()
                AudioDeviceManager.getInstance().startCallMode()

                createPeerConnection()

                val options = RTCOfferOptions()
                peerConnection?.createOffer(options, object : CreateSessionDescriptionObserver {
                    override fun onSuccess(description: RTCSessionDescription) {
                        val optimizedSdp = OpusCodecHelper.modifySdpForOpus(description.sdp, maxBitrateKbps = 32)
                        val finalOffer = RTCSessionDescription(RTCSdpType.OFFER, optimizedSdp)
                        peerConnection?.setLocalDescription(finalOffer, object : SetSessionDescriptionObserver {
                            override fun onSuccess() {}
                            override fun onFailure(error: String) {
                                logger.warning("setLocalDescription(offer) failed: $error")
                            }
                        })
                        signalingHandler?.sendOffer(finalOffer.sdp, peerUuid)
                    }

                    override fun onFailure(error: String) {
                        logger.warning("createOffer failed: $error")
                        endCall()
                    }
                })
            }
        }
    }

    fun handleIncomingOffer(sdp: String, peerUuid: String) {
        if (_callState.value != CallState.IDLE) {
            signalingHandler?.sendBusy(peerUuid)
            return
        }
        isCaller = false
        currentPeerUuid = peerUuid
        incomingOfferSdp = sdp
        _callState.value = CallState.INCOMING_RINGING
        startTimeoutTimer()

        scope.launch(Dispatchers.IO) {
            val conv = AppState.msgDao.getConversationByUuid(peerUuid)
            val alias = conv?.alias ?: "Unknown"
            DesktopUtils.showNotification("Incoming Voice Call", "From: $alias")
        }
    }

    fun acceptCall() {
        if (_callState.value != CallState.INCOMING_RINGING) return
        _callState.value = CallState.CONNECTING
        timeoutJob?.cancel()
        AudioDeviceManager.getInstance().startCallMode()

        createPeerConnection()

        val rawOfferSdp = incomingOfferSdp ?: return
        val optimizedOfferSdp = OpusCodecHelper.modifySdpForOpus(rawOfferSdp, maxBitrateKbps = 32)
        val offer = RTCSessionDescription(RTCSdpType.OFFER, optimizedOfferSdp)

        peerConnection?.setRemoteDescription(offer, object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
                pendingIceCandidates.clear()

                val answerOptions = RTCAnswerOptions()
                peerConnection?.createAnswer(answerOptions, object : CreateSessionDescriptionObserver {
                    override fun onSuccess(answer: RTCSessionDescription) {
                        val optimizedAnswerSdp = OpusCodecHelper.modifySdpForOpus(answer.sdp, maxBitrateKbps = 32)
                        val finalAnswer = RTCSessionDescription(RTCSdpType.ANSWER, optimizedAnswerSdp)
                        peerConnection?.setLocalDescription(finalAnswer, object : SetSessionDescriptionObserver {
                            override fun onSuccess() {}
                            override fun onFailure(error: String) {
                                logger.warning("setLocalDescription(answer) failed: $error")
                            }
                        })
                        signalingHandler?.sendAnswer(finalAnswer.sdp, currentPeerUuid ?: return)
                    }

                    override fun onFailure(error: String) {
                        logger.warning("createAnswer failed: $error")
                        endCall()
                    }
                })
            }

            override fun onFailure(error: String) {
                logger.warning("setRemoteDescription(offer) failed: $error")
                endCall()
            }
        })
    }

    fun rejectCall() {
        currentPeerUuid?.let { signalingHandler?.sendReject(it) }
        resetState()
    }

    fun handleRemoteAnswer(sdp: String) {
        val optimizedAnswerSdp = OpusCodecHelper.modifySdpForOpus(sdp, maxBitrateKbps = 32)
        val answer = RTCSessionDescription(RTCSdpType.ANSWER, optimizedAnswerSdp)
        peerConnection?.setRemoteDescription(answer, object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
                pendingIceCandidates.clear()
            }

            override fun onFailure(error: String) {
                logger.warning("setRemoteDescription(answer) failed: $error")
            }
        })
        _callState.value = CallState.CONNECTING
        timeoutJob?.cancel()
    }

    fun handleRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        val candidate = RTCIceCandidate(sdpMid, sdpMLineIndex, sdp)
        if (peerConnection?.remoteDescription == null) {
            pendingIceCandidates.add(candidate)
        } else {
            peerConnection?.addIceCandidate(candidate)
        }
    }

    fun endCall() {
        if (_callState.value == CallState.IDLE || _callState.value == CallState.ENDED) return

        val finalDuration = _callDurationSeconds.value
        // Save Call Record
        saveCallRecord(durationOverride = finalDuration)

        currentPeerUuid?.let { signalingHandler?.sendEnd(it, finalDuration) }
        resetState()
    }

    fun remoteEndedCall(remoteDuration: Long) {
        if (_callState.value == CallState.IDLE || _callState.value == CallState.ENDED) return

        saveCallRecord(durationOverride = if (remoteDuration >= 0) remoteDuration else null)
        resetState()
    }

    fun remoteSyncReceived(remoteDuration: Long) {
        // Obsolete: We rely on local elapsed time now to prevent jitter.
    }

    fun remoteRejectedCall() {
        saveCallRecord(rejected = true)
        resetState()
    }

    fun remoteBusyCall() {
        saveCallRecord(busy = true)
        resetState()
    }

    private fun saveCallRecord(rejected: Boolean = false, busy: Boolean = false, durationOverride: Long? = null) {
        if (!isCaller) return // Only the Caller manages and sends the call record

        val duration = durationOverride ?: _callDurationSeconds.value
        val recordText = when {
            busy -> "[Voice Call: Busy]"
            rejected -> "[Voice Call: Declined]"
            duration == 0L -> "[Voice Call: Missed]"
            else -> {
                val minutes = duration / 60
                val seconds = duration % 60
                String.format("[Voice Call: %02d:%02d]", minutes, seconds)
            }
        }
        currentPeerUuid?.let { signalingHandler?.sendCallRecordMessage(it, recordText) }
    }

    private fun resetState() {
        _callState.value = CallState.ENDED

        durationJob?.cancel()
        rttJob?.cancel()
        timeoutJob?.cancel()
        disconnectGraceJob?.cancel()
        disconnectGraceJob = null

        peerConnection?.close()
        peerConnection = null

        localAudioTrack?.dispose()
        localAudioTrack = null

        localAudioSource = null

        AudioDeviceManager.getInstance().endCallMode()

        currentPeerUuid = null
        incomingOfferSdp = null
        pendingIceCandidates.clear()

        scope.launch {
            delay(1500)
            _callState.value = CallState.IDLE
            _callDurationSeconds.value = 0
            _rttMs.value = 0
        }
    }

    private fun startCallTimers() {
        durationJob?.cancel()
        rttJob?.cancel()
        timeoutJob?.cancel()

        durationJob = scope.launch {
            val startTime = System.currentTimeMillis()
            while (isActive && _callState.value == CallState.CONNECTED) {
                val currentSeconds = (System.currentTimeMillis() - startTime) / 1000
                _callDurationSeconds.value = currentSeconds
                delay(500)
            }
        }

        rttJob = scope.launch {
            while (isActive && _callState.value == CallState.CONNECTED) {
                delay(2000)
                peerConnection?.getStats(object : RTCStatsCollectorCallback {
                    override fun onStatsDelivered(report: RTCStatsReport) {
                        var minRtt = Long.MAX_VALUE
                        report.stats.values.forEach { stat ->
                            if (stat.type == RTCStatsType.CANDIDATE_PAIR) {
                                val attrs = stat.attributes
                                if (attrs["state"] == "succeeded") {
                                    val rtt = (attrs["currentRoundTripTime"] as? Double)
                                        ?: (attrs["roundTripTime"] as? Double)
                                    if (rtt != null) {
                                        val rttMs = (rtt * 1000).toLong()
                                        if (rttMs in 1 until minRtt) minRtt = rttMs
                                    }
                                }
                            } else if (stat.type == RTCStatsType.REMOTE_INBOUND_RTP) {
                                val rtt = stat.attributes["roundTripTime"] as? Double
                                if (rtt != null) {
                                    val rttMs = (rtt * 1000).toLong()
                                    if (rttMs in 1 until minRtt) minRtt = rttMs
                                }
                            }
                        }
                        if (minRtt != Long.MAX_VALUE) {
                            _rttMs.value = minRtt
                        }
                    }
                })
            }
        }
    }

    fun setMicrophoneMute(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    private fun startTimeoutTimer() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(30000) // 30 seconds max ringing time
            if (_callState.value == CallState.OUTGOING_RINGING || _callState.value == CallState.INCOMING_RINGING) {
                withContext(Dispatchers.Main) {
                    DesktopUtils.showNotification("Voice Call", "Time out")
                }
                if (_callState.value == CallState.INCOMING_RINGING) {
                    rejectCall()
                } else {
                    endCall()
                }
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: CallManager? = null

        fun getInstance(): CallManager {
            return INSTANCE ?: synchronized(this) {
                val instance = CallManager()
                INSTANCE = instance
                instance
            }
        }
    }
}
