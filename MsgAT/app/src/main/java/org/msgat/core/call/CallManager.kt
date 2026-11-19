package org.msgat.core.call

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.msgat.MsgATApp
import org.msgat.core.media.MicrophoneOwnership
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.Locale

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

    val isMinimized = MutableStateFlow(false)

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private val _rttMs = MutableStateFlow(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()
    private val _recoveringConnection = MutableStateFlow(false)
    internal val recoveringConnection = _recoveringConnection.asStateFlow()
    internal var endedOutcome = CallOutcome.Completed
        private set

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null
    private var microphone: MicrophoneOwnership.CallLease? = null
    private var egl: EglBase? = null
    private val _isVideoCall = MutableStateFlow(false)
    internal val isVideoCall = _isVideoCall.asStateFlow()
    private val _videoSession = MutableStateFlow<CallVideoSession?>(null)
    internal val videoSession = _videoSession.asStateFlow()
    private var videoRelease: Job? = null
    internal val supportsVideo: Boolean get() = egl != null &&
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)

    private var signalingHandler: CallSignalingHandler? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var durationJob: Job? = null
    private var rttJob: Job? = null
    private var timeoutJob: Job? = null
    private var disconnectGraceJob: Job? = null

    var currentPeerUuid: String? = null
        private set

    var currentCallId: String? = null
        private set
    fun isCurrentCall(peer: String,id: String) = isCurrentPeer(peer) && currentCallId==id
    fun isOutgoingCall(peer: String,id: String) = isCurrentCall(peer,id) && isCaller &&
        _callState.value in listOf(CallState.OUTGOING_RINGING,CallState.CONNECTING)

    fun isCurrentPeer(peerUuid: String): Boolean = currentPeerUuid == peerUuid && _callState.value != CallState.IDLE && _callState.value != CallState.ENDED

    private var isCaller: Boolean = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private val pendingLocalIceCandidates = mutableListOf<IceCandidate>()
    private var localDescriptionSignaled = false

    private fun onCurrentConnection(connection: PeerConnection, action: () -> Unit) {
        scope.launch {
            if (peerConnection === connection && _callState.value != CallState.IDLE && _callState.value != CallState.ENDED) action()
        }
    }

    private fun signalLocalDescription(description: SessionDescription, peer: String, offer: Boolean) {
        if (offer) signalingHandler?.sendOffer(description, peer) else signalingHandler?.sendAnswer(description, peer)
        localDescriptionSignaled = true
        pendingLocalIceCandidates.forEach { signalingHandler?.sendIceCandidate(it, peer) }
        pendingLocalIceCandidates.clear()
    }

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

        val builder = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(audioDeviceModule)
        egl = runCatching { EglBase.create() }.getOrNull()
        egl?.eglBaseContext?.let { shared ->
            builder.setVideoEncoderFactory(DefaultVideoEncoderFactory(shared, true, true))
            builder.setVideoDecoderFactory(DefaultVideoDecoderFactory(shared))
        }
        factory = builder.createPeerConnectionFactory()

        audioDeviceModule.release()
    }

    /**
     * P2P-first ICE configuration.
     * - Multiple STUN servers (Google + Cloudflare + China-reachable endpoints) so both
     *   peers can always learn their server-reflexive candidates for direct hole punching.
     * - Optional TURN relay only when configured in Settings (empty = pure P2P).
     *   ICE always prefers the direct path; TURN is a last-resort fallback.
     */
    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()

        listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
            "stun:stun.miwifi.com:3478",
            "stun:stun.chat.bilibili.com:3478"
        ).forEach { url ->
            servers += PeerConnection.IceServer.builder(url).createIceServer()
        }

        val prefs = MsgATApp.instance.securePrefs
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
                servers += PeerConnection.IceServer.builder(url)
                    .setUsername(user)
                    .setPassword(pass)
                    .createIceServer()
            }
        }
        return servers
    }

    private var peerGeneration = 0

    private fun createPeerConnection() {
        val generation = ++peerGeneration
        localDescriptionSignaled = false
        pendingLocalIceCandidates.clear()
        val rtcConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                scope.launch {
                if (generation != peerGeneration || _callState.value == CallState.IDLE || _callState.value == CallState.ENDED) return@launch
                Log.d("MsgAT-Call", "ICE Connection State: $newState")
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        disconnectGraceJob?.cancel()
                        disconnectGraceJob = null
                        _recoveringConnection.value = false
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
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        if (_callState.value == CallState.CONNECTED) {
                            _recoveringConnection.value = true
                            _rttMs.value = 0
                        }
                        // Long-distance links can transiently drop ICE; give a 10s recovery
                        // window before treating the call as dead (prevents instant hang-up).
                        if (disconnectGraceJob == null) {
                            disconnectGraceJob = scope.launch {
                                delay(10_000)
                                disconnectGraceJob = null
                                val st = peerConnection?.iceConnectionState()
                                if (st != PeerConnection.IceConnectionState.CONNECTED &&
                                    st != PeerConnection.IceConnectionState.COMPLETED) {
                                    endCall(CallOutcome.Failed)
                                }
                            }
                        }
                    }
                    PeerConnection.IceConnectionState.FAILED -> endCall(CallOutcome.Failed)
                    else -> {}
                }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                scope.launch {
                    if (generation != peerGeneration || !isCurrentPeer(currentPeerUuid ?: return@launch)) return@launch
                    candidate?.let {
                        if (localDescriptionSignaled) signalingHandler?.sendIceCandidate(it, currentPeerUuid ?: return@launch)
                        else if (pendingLocalIceCandidates.size < 256) pendingLocalIceCandidates.add(it)
                    }
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dataChannel: DataChannel?) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}

            override fun onTrack(transceiver: RtpTransceiver?) {
                scope.launch {
                    if (generation != peerGeneration || !_isVideoCall.value) return@launch
                    val track = transceiver?.receiver?.track() as? VideoTrack ?: return@launch
                    _videoSession.value?.attachRemote(track)
                }
            }
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
        if (_isVideoCall.value && peerConnection != null) {
            try {
                val video = CallVideoSession(context, scope, checkNotNull(factory), checkNotNull(egl).eglBaseContext)
                _videoSession.value = video
                checkNotNull(peerConnection?.addTrack(video.track, listOf("ARDAMS")))
                setCameraEnabled(true)
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, "Video could not be started.", android.widget.Toast.LENGTH_SHORT).show()
                endCall(CallOutcome.Failed)
            }
        }
    }

    fun startOutgoingCall(peerUuid: String, video: Boolean = false) {
        if (_callState.value != CallState.IDLE) return
        if (!hasMicrophonePermission()) return
        if (video && (!supportsVideo || !videoWindowVisible || !hasVideoPermissions())) {
            android.widget.Toast.makeText(context, "Camera and microphone access are required for video calls.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        _isVideoCall.value = video
        _callState.value = CallState.CONNECTING // Lock state immediately to prevent double clicks
        val generation = peerGeneration

        scope.launch {
            val callable = try { MsgATApp.instance.nettyClient.secureMessaging.requireCallable(peerUuid) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            if (peerGeneration != generation || _callState.value != CallState.CONNECTING) return@launch
            if (!callable) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Cannot call a non-friend.", android.widget.Toast.LENGTH_SHORT).show()
                    _callState.value = CallState.IDLE
                    _isVideoCall.value = false
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                if (peerGeneration == generation && _callState.value == CallState.CONNECTING && prepareMicrophone(generation)) {
                    isCaller = true
                    currentPeerUuid = peerUuid
                    currentCallId = java.util.UUID.randomUUID().toString()
                    _callState.value = CallState.OUTGOING_RINGING
                    startTimeoutTimer()
                    AudioDeviceManager.getInstance().startCallMode(video)

                    createPeerConnection()

                    val connection = peerConnection ?: run { endCall(CallOutcome.Failed); return@withContext }
                    if (video) startCallService()
                    connection.createOffer(object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription?) = onCurrentConnection(connection) {
                            if (desc == null) { endCall(CallOutcome.Failed); return@onCurrentConnection }
                            val finalOffer = SessionDescription(desc.type, OpusCodecHelper.modifySdpForOpus(desc.description, maxBitrateKbps = 32))
                            connection.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(desc: SessionDescription?) {}
                                override fun onSetSuccess() = onCurrentConnection(connection) { signalLocalDescription(finalOffer, peerUuid, true) }
                                override fun onCreateFailure(s: String?) = onCurrentConnection(connection) { endCall(CallOutcome.Failed) }
                                override fun onSetFailure(s: String?) = onCurrentConnection(connection) { endCall(CallOutcome.Failed) }
                            }, finalOffer)
                        }
                        override fun onSetSuccess() {}
                        override fun onCreateFailure(s: String?) = onCurrentConnection(connection) { endCall(CallOutcome.Failed) }
                        override fun onSetFailure(s: String?) = onCurrentConnection(connection) { endCall(CallOutcome.Failed) }
                    }, MediaConstraints())
                }
            }
        }
    }

    fun handleIncomingOffer(peerUuid: String, callId: String, video: Boolean = false) {
        if (_callState.value != CallState.IDLE) return
        isCaller = false
        currentCallId = callId
        currentPeerUuid = peerUuid
        _isVideoCall.value = video
        _callState.value = CallState.INCOMING_RINGING
        startTimeoutTimer()

        if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            runCatching {
                val notice = org.msgat.NotificationHelper.createCallNotification(context,
                    isIncoming = true, isOngoing = false, video = video, callToken = callId,
                    showFullScreen = !MsgATApp.instance.isAppInForeground)
                context.getSystemService(android.app.NotificationManager::class.java)
                    ?.notify(org.msgat.NotificationHelper.CALL_NOTIFICATION_ID, notice)
            }
        }
    }

    fun acceptCall() {
        val rawOffer = signalingHandler?.pendingOffer
        if (_callState.value != CallState.INCOMING_RINGING || rawOffer == null) return
        if (!hasMicrophonePermission()) return
        if (_isVideoCall.value && (!videoWindowVisible || !hasVideoPermissions())) return
        val generation = peerGeneration
        _callState.value = CallState.CONNECTING
        clearCallNotification()
        startTimeoutTimer()
        scope.launch {
            if (prepareMicrophone(generation)) connectIncoming(rawOffer)
        }
    }

    private suspend fun prepareMicrophone(generation: Int): Boolean {
        if (peerGeneration != generation || _callState.value != CallState.CONNECTING) return false
        return try {
            val lease = MicrophoneOwnership.reserveCall()
            microphone = lease
            lease.drainRecording()
            if (_isVideoCall.value) videoRelease?.join()
            peerGeneration == generation && microphone === lease && _callState.value == CallState.CONNECTING
        } catch (cancelled: CancellationException) {
            if (peerGeneration == generation) endCall(CallOutcome.Failed)
            throw cancelled
        } catch (_: Exception) {
            if (peerGeneration == generation) {
                android.widget.Toast.makeText(context, "Could not switch the microphone to the call.", android.widget.Toast.LENGTH_SHORT).show()
                endCall(CallOutcome.Failed)
            }
            false
        }
    }

    private fun connectIncoming(rawOffer: SessionDescription) {
        AudioDeviceManager.getInstance().startCallMode(_isVideoCall.value)
        startCallService()
        applyIncomingOffer(rawOffer)
    }

    private fun startCallService() {
        val intent = android.content.Intent(context, org.msgat.core.network.CallService::class.java).apply {
            putExtra("callToken", currentCallId)
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

    }

    internal fun missingCallPermissions(video: Boolean): List<String> =
        buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            if (video) add(android.Manifest.permission.CAMERA)
        }.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun hasMicrophonePermission(): Boolean = missingCallPermissions(video = false).isEmpty()
    internal fun hasVideoPermissions(): Boolean = missingCallPermissions(video = true).isEmpty()

    internal fun setCameraEnabled(enabled: Boolean) {
        if (enabled && (!videoWindowVisible || !hasVideoPermissions())) return
        _videoSession.value?.enable(enabled)
    }

    private fun applyIncomingOffer(rawOffer: SessionDescription) {
        createPeerConnection()
        val optimizedOfferSdp = OpusCodecHelper.modifySdpForOpus(rawOffer.description, maxBitrateKbps = 32)
        val offer = SessionDescription(rawOffer.type, optimizedOfferSdp)

        val connection = peerConnection ?: run { endCall(CallOutcome.Failed); return }
        val peer = currentPeerUuid ?: run { endCall(CallOutcome.Failed); return }
        connection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() = onCurrentConnection(connection) {
                pendingIceCandidates.forEach { connection.addIceCandidate(it) }
                pendingIceCandidates.clear()
                connection.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription?) = onCurrentConnection(connection) {
                        if (answer == null) { endCall(CallOutcome.Failed); return@onCurrentConnection }
                        val finalAnswer = SessionDescription(answer.type, OpusCodecHelper.modifySdpForOpus(answer.description, maxBitrateKbps = 32))
                        connection.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(desc: SessionDescription?) {}
                            override fun onSetSuccess() = onCurrentConnection(connection) { signalLocalDescription(finalAnswer, peer, false) }
                            override fun onCreateFailure(s: String?) = onCurrentConnection(connection) { endCall(CallOutcome.Failed) }
                            override fun onSetFailure(s: String?) = onCurrentConnection(connection) { endCall(CallOutcome.Failed) }
                        }, finalAnswer)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(s: String?) = onCurrentConnection(connection) { endCall(CallOutcome.Failed) }
                    override fun onSetFailure(s: String?) = onCurrentConnection(connection) { endCall(CallOutcome.Failed) }
                }, MediaConstraints())
            }
            override fun onCreateFailure(s: String?) = onCurrentConnection(connection) { endCall(CallOutcome.Failed) }
            override fun onSetFailure(s: String?) = onCurrentConnection(connection) { endCall(CallOutcome.Failed) }
        }, offer)
    }

    fun rejectCall() = rejectCall(CallOutcome.Declined)

    private fun rejectCall(outcome: CallOutcome) {
        if (_callState.value != CallState.INCOMING_RINGING) return
        currentPeerUuid?.let { signalingHandler?.sendReject(it) }
        resetState(outcome)
    }

    fun handleRemoteAnswer(answer: SessionDescription) {
        if (!isCaller || _callState.value != CallState.OUTGOING_RINGING) return
        val video = answer.description.lineSequence().any { it.startsWith("m=video ") }
        if (video != _isVideoCall.value) { endCall(CallOutcome.Failed); return }
        val optimizedAnswerSdp = OpusCodecHelper.modifySdpForOpus(answer.description, maxBitrateKbps = 32)
        val finalAnswer = SessionDescription(answer.type, optimizedAnswerSdp)
        val connection = peerConnection ?: run { endCall(CallOutcome.Failed); return }
        _callState.value = CallState.CONNECTING
        startTimeoutTimer()
        connection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() = onCurrentConnection(connection) {
                pendingIceCandidates.forEach { connection.addIceCandidate(it) }
                pendingIceCandidates.clear()
            }
            override fun onCreateFailure(s: String?) = onCurrentConnection(connection) { endCall(CallOutcome.Failed) }
            override fun onSetFailure(s: String?) = onCurrentConnection(connection) { endCall(CallOutcome.Failed) }
        }, finalAnswer)
    }

    fun handleRemoteIceCandidate(candidate: IceCandidate) {
        if (_callState.value == CallState.IDLE || _callState.value == CallState.ENDED || candidate.sdpMLineIndex !in 0..255) return
        if (peerConnection?.remoteDescription == null) {
            if (pendingIceCandidates.size < 256) pendingIceCandidates.add(candidate)
        } else {
            peerConnection?.addIceCandidate(candidate)
        }
    }

    fun endCall() = endCall(CallOutcome.Canceled)

    internal suspend fun endCallAndDrainVideo() {
        endCall()
        videoRelease?.join()
    }

    private fun endCall(outcome: CallOutcome) {
        if (_callState.value == CallState.IDLE || _callState.value == CallState.ENDED) return

        currentPeerUuid?.let { signalingHandler?.sendEnd(it) }
        resetState(outcome)
    }

    fun remoteEndedCall() {
        if (_callState.value == CallState.IDLE || _callState.value == CallState.ENDED) return

        resetState(if (_callState.value == CallState.INCOMING_RINGING) CallOutcome.Missed else CallOutcome.Failed)
    }

    fun signalingFailed() {
        if (_callState.value == CallState.IDLE || _callState.value == CallState.ENDED) return
        resetState(CallOutcome.Failed)
        android.widget.Toast.makeText(context,"Call signaling unavailable",android.widget.Toast.LENGTH_SHORT).show()
    }

    fun remoteRejectedCall() {
        if (_callState.value == CallState.IDLE || _callState.value == CallState.ENDED) return
        resetState(CallOutcome.Declined)
    }

    fun remoteBusyCall() {
        if (_callState.value == CallState.IDLE || _callState.value == CallState.ENDED) return
        resetState(CallOutcome.Busy)
    }

    private fun sendCallRecord(peer: String, outcome: CallOutcome, duration: Long) {
        val kind = if (_isVideoCall.value) "Video Call" else "Voice Call"
        val result = when (outcome) {
            CallOutcome.Completed -> String.format(Locale.ROOT, "%02d:%02d", duration / 60, duration % 60)
            CallOutcome.Missed -> "Missed"
            CallOutcome.Declined -> "Declined"
            CallOutcome.Busy -> "Busy"
            CallOutcome.Canceled -> "Canceled"
            CallOutcome.Failed -> "Failed"
        }
        signalingHandler?.sendCallRecordMessage(peer, "[$kind: $result]")
    }

    private fun resetState(outcome: CallOutcome) {
        val peer = currentPeerUuid
        val id = currentCallId
        val duration = _callDurationSeconds.value
        endedOutcome = if (_callState.value == CallState.CONNECTED || duration > 0) CallOutcome.Completed else outcome
        if (peer != null && id != null) {
            signalingHandler?.recordHistory(peer,id,isCaller,_isVideoCall.value,
                endedOutcome,duration,System.currentTimeMillis())
            if (isCaller) sendCallRecord(peer, endedOutcome, duration)
        }
        peerGeneration++
        _callState.value = CallState.ENDED
        _recoveringConnection.value = false
        currentCallId = null
        signalingHandler?.clearOffer()

        durationJob?.cancel()
        rttJob?.cancel()
        timeoutJob?.cancel()
        disconnectGraceJob?.cancel()
        disconnectGraceJob = null

        val video = _videoSession.value
        _videoSession.value = null
        video?.detachTracks()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        if (video != null) videoRelease = video.releaseCamera()
        clearCallNotification()

        localAudioTrack?.dispose()
        localAudioTrack = null

        localAudioSource?.dispose()
        localAudioSource = null

        AudioDeviceManager.getInstance().endCallMode()
        microphone?.close()
        microphone = null

        pendingIceCandidates.clear()
        pendingLocalIceCandidates.clear()
        localDescriptionSignaled = false

        scope.launch {
            delay(1500)
            currentPeerUuid = null
            isMinimized.value = false
            _callState.value = CallState.IDLE
            _isVideoCall.value = false
            _callDurationSeconds.value = 0
            _rttMs.value = 0
        }
    }

    private fun clearCallNotification() {
        context.getSystemService(android.app.NotificationManager::class.java)
            ?.cancel(org.msgat.NotificationHelper.CALL_NOTIFICATION_ID)
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
                val connection = peerConnection ?: break
                val generation = peerGeneration
                connection.getStats { report ->
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
                        scope.launch {
                            if (generation == peerGeneration && _callState.value == CallState.CONNECTED &&
                                !_recoveringConnection.value) _rttMs.value = minRtt
                        }
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
            if (_callState.value == CallState.OUTGOING_RINGING || _callState.value == CallState.INCOMING_RINGING || _callState.value == CallState.CONNECTING) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Time out", android.widget.Toast.LENGTH_SHORT).show()
                }
                if (_callState.value == CallState.INCOMING_RINGING) {
                    rejectCall(CallOutcome.Missed)
                } else {
                    endCall(if (_callState.value == CallState.OUTGOING_RINGING) CallOutcome.Missed else CallOutcome.Failed)
                }
            }
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: CallManager? = null

        internal fun endUnavailablePeer(peer: String) { INSTANCE?.takeIf { it.isCurrentPeer(peer) }?.remoteEndedCall() }
        private var videoWindowVisible = false
        internal fun setVideoWindowVisible(visible: Boolean) {
            videoWindowVisible = visible
            if (!visible) INSTANCE?.setCameraEnabled(false)
        }

        fun getInstance(): CallManager {
            return INSTANCE ?: synchronized(this) {
                val instance = CallManager(MsgATApp.instance.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
