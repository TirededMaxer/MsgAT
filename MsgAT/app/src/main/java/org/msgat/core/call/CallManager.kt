package org.msgat.core.call

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.msgat.MsgATApp
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

enum class CallState {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTING,
    CONNECTED,
    ENDED
}

class CallManager private constructor(private val context: Context) {

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private val _rttMs = MutableStateFlow(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null

    private var signalingHandler: CallSignalingHandler? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var durationJob: Job? = null
    private var rttJob: Job? = null
    private var timeoutJob: Job? = null
    
    var currentPeerUuid: String? = null
        private set
    
    private var isCaller: Boolean = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    init {
        initializeWebRTC()
    }

    fun setSignalingHandler(handler: CallSignalingHandler) {
        this.signalingHandler = handler
    }

    private fun initializeWebRTC() {
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val options = PeerConnectionFactory.Options()

        val audioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(audioAttributes)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
            
        audioDeviceModule.release()
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            // Turn server will be added here later
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d("MsgAT-Call", "ICE Connection State: $newState")
                if (newState == PeerConnection.IceConnectionState.CONNECTED || newState == PeerConnection.IceConnectionState.COMPLETED) {
                    if (_callState.value != CallState.CONNECTED) {
                        _callState.value = CallState.CONNECTED
                        startCallTimers()
                        try {
                            peerConnection?.senders?.forEach { sender ->
                                if (sender.track()?.kind() == "audio") {
                                    val params = sender.parameters
                                    params.encodings.forEach { encoding ->
                                        encoding.maxBitrateBps = 32_000
                                    }
                                    sender.parameters = params
                                    Log.d("MsgAT-Call", "Enforced audio RTP Sender max bitrate to 32 kbps")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MsgAT-Call", "Failed to set audio sender max bitrate", e)
                        }
                    }
                } else if (newState == PeerConnection.IceConnectionState.DISCONNECTED || newState == PeerConnection.IceConnectionState.FAILED) {
                    endCall()
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    signalingHandler?.sendIceCandidate(it, currentPeerUuid ?: return)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dataChannel: DataChannel?) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        })

        // Create Local Audio Track
        val audioConstraints = MediaConstraints()
        val useHwAec = org.webrtc.audio.JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
        val useHwNs = org.webrtc.audio.JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()

        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", if (useHwAec) "false" else "true"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", if (useHwNs) "false" else "true"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))

        localAudioSource = factory?.createAudioSource(audioConstraints)
        localAudioTrack = factory?.createAudioTrack("ARDAMSa0", localAudioSource)

        peerConnection?.addTrack(localAudioTrack, listOf("ARDAMS"))
    }

    fun startOutgoingCall(peerUuid: String) {
        if (_callState.value != CallState.IDLE) return
        _callState.value = CallState.CONNECTING // Lock state immediately to prevent double clicks
        
        scope.launch {
            val db = org.msgat.data.db.AppDatabase.getInstance(context).msgDao()
            val conv = db.getConversationByUuid(peerUuid)
            if (conv == null || conv.peerPublicKey.isEmpty()) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Cannot call a non-friend.", android.widget.Toast.LENGTH_SHORT).show()
                    _callState.value = CallState.IDLE
                }
                return@launch
            }
            
            withContext(Dispatchers.Main) {
                if (_callState.value == CallState.CONNECTING) {
                    isCaller = true
                    currentPeerUuid = peerUuid
                    _callState.value = CallState.OUTGOING_RINGING
                    startTimeoutTimer()
                    AudioDeviceManager.getInstance().startCallMode()
                    
                    createPeerConnection()

                    peerConnection?.createOffer(object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) {
                            desc?.let { origDesc ->
                                val optimizedSdp = OpusCodecHelper.modifySdpForOpus(origDesc.description, maxBitrateKbps = 32)
                                val finalOffer = SessionDescription(origDesc.type, optimizedSdp)
                                peerConnection?.setLocalDescription(this, finalOffer)
                                signalingHandler?.sendOffer(finalOffer, peerUuid)
                            }
                        }

                        override fun onSetSuccess() {}
                        override fun onCreateFailure(s: String?) {}
                        override fun onSetFailure(s: String?) {}
                    }, MediaConstraints())
                }
            }
        }
    }

    fun handleIncomingOffer(offer: SessionDescription, peerUuid: String) {
        if (_callState.value != CallState.IDLE) {
            signalingHandler?.sendBusy(peerUuid)
            return
        }
        isCaller = false
        currentPeerUuid = peerUuid
        _callState.value = CallState.INCOMING_RINGING
        startTimeoutTimer()
        
        val db = org.msgat.data.db.AppDatabase.getInstance(context).msgDao()
        scope.launch(Dispatchers.IO) {
            val conv = db.getConversationByUuid(peerUuid)
            val alias = conv?.alias ?: "Unknown"
            if (!org.msgat.MsgATApp.instance.isAppInForeground) {
                org.msgat.NotificationHelper.showMessageNotification(
                    context,
                    peerUuid,
                    alias,
                    "txt",
                    "[Incoming Voice Call]"
                )
            }
        }
    }

    fun acceptCall() {
        if (_callState.value != CallState.INCOMING_RINGING) return
        _callState.value = CallState.CONNECTING
        timeoutJob?.cancel()
        AudioDeviceManager.getInstance().startCallMode()
        
        val intent = android.content.Intent(context, org.msgat.core.network.CallService::class.java).apply {
            putExtra("peerUuid", currentPeerUuid)
            putExtra("isIncoming", true)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        createPeerConnection()

        val rawOffer = signalingHandler?.pendingOffer ?: return
        val optimizedOfferSdp = OpusCodecHelper.modifySdpForOpus(rawOffer.description, maxBitrateKbps = 32)
        val offer = SessionDescription(rawOffer.type, optimizedOfferSdp)

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
                pendingIceCandidates.clear()
                
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        answer?.let { origAnswer ->
                            val optimizedAnswerSdp = OpusCodecHelper.modifySdpForOpus(origAnswer.description, maxBitrateKbps = 32)
                            val finalAnswer = SessionDescription(origAnswer.type, optimizedAnswerSdp)
                            peerConnection?.setLocalDescription(this, finalAnswer)
                            signalingHandler?.sendAnswer(finalAnswer, currentPeerUuid ?: return)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(s: String?) {}
                    override fun onSetFailure(s: String?) {}
                }, MediaConstraints())
            }
            override fun onCreateFailure(s: String?) {}
            override fun onSetFailure(s: String?) {}
        }, offer)
    }

    fun rejectCall() {
        currentPeerUuid?.let { signalingHandler?.sendReject(it) }
        resetState()
    }

    fun handleRemoteAnswer(answer: SessionDescription) {
        val optimizedAnswerSdp = OpusCodecHelper.modifySdpForOpus(answer.description, maxBitrateKbps = 32)
        val finalAnswer = SessionDescription(answer.type, optimizedAnswerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
                pendingIceCandidates.clear()
            }
            override fun onCreateFailure(s: String?) {}
            override fun onSetFailure(s: String?) {}
        }, answer)
        _callState.value = CallState.CONNECTING
        timeoutJob?.cancel()
    }

    fun handleRemoteIceCandidate(candidate: IceCandidate) {
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
        // Obsolete: We rely on local elapsedRealtime now to prevent jitter.
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
        
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        
        localAudioTrack?.dispose()
        localAudioTrack = null
        
        localAudioSource?.dispose()
        localAudioSource = null
        
        AudioDeviceManager.getInstance().endCallMode()
        
        currentPeerUuid = null
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
            val startTime = android.os.SystemClock.elapsedRealtime()
            while (isActive && _callState.value == CallState.CONNECTED) {
                val currentSeconds = (android.os.SystemClock.elapsedRealtime() - startTime) / 1000
                _callDurationSeconds.value = currentSeconds
                delay(500)
            }
        }

        rttJob = scope.launch {
            while (isActive && _callState.value == CallState.CONNECTED) {
                delay(2000)
                peerConnection?.getStats { report ->
                    var minRtt = Long.MAX_VALUE
                    report.statsMap.values.forEach { stat ->
                        if (stat.type == "candidate-pair" && stat.members["state"] == "succeeded") {
                            val rttDouble = (stat.members["currentRoundTripTime"] as? Double)
                                ?: (stat.members["roundTripTime"] as? Double)
                            if (rttDouble != null) {
                                val rttMs = (rttDouble * 1000).toLong()
                                if (rttMs in 1 until minRtt) minRtt = rttMs
                            }
                        } else if (stat.type == "remote-inbound-rtp") {
                            val rttDouble = stat.members["roundTripTime"] as? Double
                            if (rttDouble != null) {
                                val rttMs = (rttDouble * 1000).toLong()
                                if (rttMs in 1 until minRtt) minRtt = rttMs
                            }
                        }
                    }
                    if (minRtt != Long.MAX_VALUE) {
                        _rttMs.value = minRtt
                    }
                }
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
                    android.widget.Toast.makeText(context, "Time out", android.widget.Toast.LENGTH_SHORT).show()
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
                val instance = CallManager(MsgATApp.instance.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
