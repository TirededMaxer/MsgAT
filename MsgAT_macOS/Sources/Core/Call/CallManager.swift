import Foundation
import Combine
import WebRTC
import AVFoundation
import CoreAudio
#if canImport(MsgATNativeAudio)
import MsgATNativeAudio
#endif

public enum CallState: String, Sendable {
    case idle = "IDLE"
    case outgoingRinging = "Calling..."
    case incomingRinging = "Incoming Voice Call..."
    case connecting = "Connecting..."
    case connected = "Connected"
    case ended = "Call Ended"
}

public final class CallManager: NSObject, ObservableObject, RTCPeerConnectionDelegate, @unchecked Sendable {
    public static let shared = CallManager()

    @Published public var callState: CallState = .idle
    @Published public var callDurationSeconds: Int64 = 0
    @Published public var rttMs: Int64 = 0
    @Published private(set) var isRecoveringConnection = false
    @Published public var currentPeerUuid: String? = nil
    private(set) var endedOutcome: CallOutcome = .completed

    public private(set) var currentCallId: String?
    public func isCurrentCall(_ peer: String, _ id: String) -> Bool { isCurrentPeer(peer) && currentCallId == id }
    public func isOutgoingCall(_ peer: String, _ id: String) -> Bool {
        isCurrentCall(peer,id) && isCaller && (callState == .outgoingRinging || callState == .connecting)
    }
    public func isCurrentPeer(_ peerUuid: String) -> Bool {
        currentPeerUuid == peerUuid && callState != .idle && callState != .ended
    }
    @Published public var currentPeerAlias: String = "Unknown"
    @Published public var currentPeerAvatarPath: String? = nil
    @Published public var isMinimized = false
    @Published public var isMuted: Bool = false
    @Published public private(set) var isSpeakerOn = false
    @Published private(set) var isVideoCall = false
    @Published private(set) var videoSession: CallVideoSession?
    private let audioOutput = CallAudioOutput()
    private let nativeAudioDevice = MacCallAudioDevice()
    @Published private(set) var isSwitchingAudio = false
    @Published private(set) var microphones: [CallAudioEndpoint] = []
    @Published private(set) var outputs: [CallAudioEndpoint] = []
    @Published private(set) var audioSelection = MacCallAudioDevice.Selection()
    private var speakerDevice: AudioDeviceID?

    private var isCaller: Bool = false
    private var pendingOffer: RTCSessionDescription? = nil
    private var pendingIceCandidates: [RTCIceCandidate] = []
    private var pendingLocalIceCandidates: [RTCIceCandidate] = []
    private var localDescriptionSignaled = false

    private var peerConnectionFactory: RTCPeerConnectionFactory?
    private var peerConnection: RTCPeerConnection?
    private var localAudioTrack: RTCAudioTrack?
    private var localAudioSource: RTCAudioSource?
    private var microphone: MicrophoneOwnership.CallLease?
    private var outgoingAttempt: UUID?
    private var videoRelease: Task<Void, Never>?

    private var timerTask: Task<Void, Never>?
    private var timeoutTask: Task<Void, Never>?
    private var rttTask: Task<Void, Never>?
    private var disconnectGraceTask: Task<Void, Never>?

    private override init() {
        super.init()
        initializeWebRTC()
    }

    private func initializeWebRTC() {
        RTCInitializeSSL()
        peerConnectionFactory = MsgATCreateAudioFactory(nativeAudioDevice)
    }

    /**
     * P2P-first ICE configuration.
     * - Multiple STUN servers (Google + Cloudflare + China-reachable endpoints) so both
     *   peers can always learn their server-reflexive candidates for direct hole punching.
     * - Optional TURN relay only when configured in Settings (empty = pure P2P).
     *   ICE always prefers the direct path; TURN is a last-resort fallback.
     */
    private func buildIceServers() -> [RTCIceServer] {
        var servers: [RTCIceServer] = []

        let stunUrls = [
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478",
            "stun:stun.miwifi.com:3478",
            "stun:stun.chat.bilibili.com:3478"
        ]
        for url in stunUrls {
            servers.append(RTCIceServer(urlStrings: [url]))
        }

        let prefs = KeychainManager.shared
        let turnHost = prefs.turnServer?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !turnHost.isEmpty {
            let turnPort = prefs.turnPort > 0 ? prefs.turnPort : 3478
            let user = prefs.turnUsername ?? ""
            let pass = prefs.turnPassword ?? ""
            var endpoints = [
                "turn:\(turnHost):\(turnPort)",
                "turn:\(turnHost):\(turnPort)?transport=tcp"
            ]
            if prefs.turnUseTls {
                endpoints.append("turns:\(turnHost):\(turnPort)?transport=tcp")
            }
            for url in endpoints {
                servers.append(RTCIceServer(urlStrings: [url], username: user, credential: pass))
            }
        }
        return servers
    }

    private func createPeerConnection() {
        guard let factory = peerConnectionFactory else { return }
        restoreAudioOutput()
        nativeAudioDevice.resetRoutes()
        updateAudioRoutes(MacCallAudioDevice.Selection())
        pendingLocalIceCandidates.removeAll()
        localDescriptionSignaled = false

        let config = RTCConfiguration()
        config.iceServers = buildIceServers()
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        peerConnection = factory.peerConnection(with: config, constraints: constraints, delegate: self)
        if let connection = peerConnection {
            let identity = ObjectIdentifier(connection)
            nativeAudioDevice.setFailureHandler { [weak self] in
                DispatchQueue.main.async {
                    guard let self, self.peerConnection.map(ObjectIdentifier.init) == identity else { return }
                    self.endCall(outcome: .failed)
                }
            }
            nativeAudioDevice.setRouteChangeHandler { [weak self] selection in
                DispatchQueue.main.async {
                    guard let self, self.peerConnection.map(ObjectIdentifier.init) == identity else { return }
                    self.updateAudioRoutes(selection)
                }
            }
        }

        // Create audio source & track
        let audioConstraints = RTCMediaConstraints(
            mandatoryConstraints: [
                "googEchoCancellation": "true",
                "googNoiseSuppression": "true",
                "googAutoGainControl": "true"
            ],
            optionalConstraints: nil
        )

        localAudioSource = factory.audioSource(with: audioConstraints)
        if let source = localAudioSource {
            localAudioTrack = factory.audioTrack(with: source, trackId: "ARDAMSa0")
            if let track = localAudioTrack {
                peerConnection?.add(track, streamIds: ["ARDAMS"])
            }
        }
        if isVideoCall, let connection = peerConnection {
            let session = CallVideoSession(factory: factory)
            guard connection.add(session.localTrack, streamIds: ["ARDAMS"]) != nil else {
                endCall(outcome: .failed)
                return
            }
            videoSession = session
        }
    }

    // MARK: - Outgoing Call
    public func startOutgoingCall(peerUuid: String, video: Bool = false) {
        guard callState == .idle else { return }
        guard ensureMicrophonePermission(resume: { [weak self] in self?.startOutgoingCall(peerUuid: peerUuid, video: video) }) else { return }
        callState = .connecting
        let attempt = UUID()
        outgoingAttempt = attempt
        SecureMessaging.shared.requireCallable(peerUuid) { success in
            DispatchQueue.main.async {
                guard self.callState == .connecting, self.currentCallId == nil, self.outgoingAttempt == attempt else { return }
                self.outgoingAttempt = nil
                guard success else {
                    self.callState = .idle
                    Task { @MainActor in NoticeCenter.shared.show("Connect and accept the contact before calling.") }
                    return
                }
                self.beginOutgoingCall(peerUuid: peerUuid, video: video)
            }
        }
    }

    private func beginOutgoingCall(peerUuid: String, video: Bool) {
        guard let conv = try? MsgDao.shared.getConversationByUuid(peerUuid) else { callState = .idle; return }
        currentCallId = UUID().uuidString.lowercased()
        isCaller = true
        currentPeerUuid = peerUuid
        currentPeerAlias = conv.alias
        currentPeerAvatarPath = conv.avatarPath
        isVideoCall = video
        callState = .outgoingRinging
        callDurationSeconds = 0
        rttMs = 0
        pendingIceCandidates.removeAll()

        startTimeoutTimer()
        withMicrophone { [weak self] in self?.createOffer(peerUuid: peerUuid) }
    }

    private func createOffer(peerUuid: String) {
        createPeerConnection()

        guard let connection = peerConnection else { endCall(outcome: .failed); return }
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        connection.offer(for: constraints) { [weak self] sdp, error in
            self?.withCurrentConnection(connection) {
                guard let self, let desc = sdp, error == nil else { self?.endCall(outcome: .failed); return }
                let finalOffer = RTCSessionDescription(type: desc.type,
                    sdp: OpusCodecHelper.modifySdpForOpus(sdpDescription: desc.sdp, maxBitrateKbps: 32))
                connection.setLocalDescription(finalOffer) { [weak self] error in
                    self?.withCurrentConnection(connection) {
                        guard let self else { return }
                        if error != nil { self.endCall(outcome: .failed) }
                        else { self.signalLocalDescription(finalOffer, peerUuid: peerUuid, isOffer: true) }
                    }
                }
            }
        }
    }

    // MARK: - Incoming Call
    public func handleIncomingOffer(senderUuid: String, callId: String, sdp: String, video: Bool = false) {
        guard callState == .idle else { return }
        currentCallId = callId

        let conv = try? MsgDao.shared.getConversationByUuid(senderUuid)
        let alias = conv?.alias ?? "Unknown"
        let avatar = conv?.avatarPath

        isCaller = false
        currentPeerUuid = senderUuid
        currentPeerAlias = alias
        currentPeerAvatarPath = avatar
        pendingOffer = RTCSessionDescription(type: .offer, sdp: sdp)
        isVideoCall = video
        callState = .incomingRinging
        callDurationSeconds = 0
        rttMs = 0
        pendingIceCandidates.removeAll()

        NotificationHelper.shared.showIncomingCallNotification(id: callId, video: video)
        startTimeoutTimer()
    }

    public func acceptCall() {
        guard callState == .incomingRinging, let peerUuid = currentPeerUuid, let id = currentCallId,
              let rawOffer = pendingOffer else { return }
        startTimeoutTimer()

        guard ensureMicrophonePermission(resume: { [weak self] in
            guard let self, self.isCurrentCall(peerUuid, id) else { return }
            self.acceptCall()
        }) else { return }

        NotificationHelper.shared.clearCallNotification()
        callState = .connecting
        withMicrophone { [weak self] in self?.connectIncoming(peerUuid: peerUuid, rawOffer: rawOffer) }
    }

    private func withMicrophone(_ connect: @escaping @MainActor () -> Void) {
        guard let callID = currentCallId, let lease = MicrophoneOwnership.shared.reserveCall() else {
            endCall(outcome: .failed)
            return
        }
        microphone = lease
        Task { @MainActor in
            let available = await MicrophoneOwnership.shared.drainRecording(for: lease)
            if self.isVideoCall { await self.videoRelease?.value }
            guard self.currentCallId == callID, self.microphone === lease else { return }
            guard available else {
                NoticeCenter.shared.show("Could not switch the microphone to the call.")
                self.endCall(outcome: .failed)
                return
            }
            connect()
        }
    }

    private func connectIncoming(peerUuid: String, rawOffer: RTCSessionDescription) {
        createPeerConnection()
        guard let connection = peerConnection else { endCall(outcome: .failed); return }
        let offer = RTCSessionDescription(type: rawOffer.type,
            sdp: OpusCodecHelper.modifySdpForOpus(sdpDescription: rawOffer.sdp, maxBitrateKbps: 32))
        connection.setRemoteDescription(offer) { [weak self] error in
            self?.withCurrentConnection(connection) {
                guard let self else { return }
                guard error == nil else { self.endCall(outcome: .failed); return }
                self.flushPendingCandidates(connection)
                let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
                connection.answer(for: constraints) { [weak self] answer, error in
                    self?.withCurrentConnection(connection) {
                        guard let self, let answer, error == nil else { self?.endCall(outcome: .failed); return }
                        let finalAnswer = RTCSessionDescription(type: answer.type,
                            sdp: OpusCodecHelper.modifySdpForOpus(sdpDescription: answer.sdp, maxBitrateKbps: 32))
                        connection.setLocalDescription(finalAnswer) { [weak self] error in
                            self?.withCurrentConnection(connection) {
                                guard let self else { return }
                                if error != nil { self.endCall(outcome: .failed) }
                                else { self.signalLocalDescription(finalAnswer, peerUuid: peerUuid, isOffer: false) }
                            }
                        }
                    }
                }
            }
        }
    }

    private func withCurrentConnection(_ connection: RTCPeerConnection, perform: @escaping () -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.peerConnection === connection,
                  self.callState != .idle, self.callState != .ended else { return }
            perform()
        }
    }

    private func flushPendingCandidates(_ connection: RTCPeerConnection) {
        let candidates = pendingIceCandidates
        pendingIceCandidates.removeAll()
        for candidate in candidates {
            connection.add(candidate) { error in
                if let error { print("[MsgAT-Call] Failed to add pending candidate: \(error)") }
            }
        }
    }

    private func signalLocalDescription(_ description: RTCSessionDescription, peerUuid: String, isOffer: Bool) {
        if isOffer { CallSignalingHandler.shared.sendOffer(offerSdp: description.sdp, peerUuid: peerUuid) }
        else { CallSignalingHandler.shared.sendAnswer(answerSdp: description.sdp, peerUuid: peerUuid) }
        localDescriptionSignaled = true
        let candidates = pendingLocalIceCandidates
        pendingLocalIceCandidates.removeAll()
        for candidate in candidates { sendLocalCandidate(candidate, peerUuid: peerUuid) }
    }

    private func sendLocalCandidate(_ candidate: RTCIceCandidate, peerUuid: String) {
        let mid = candidate.sdpMid.flatMap { $0.isEmpty ? nil : $0 } ?? "0"
        CallSignalingHandler.shared.sendIceCandidate(sdpMid: mid,
            sdpMLineIndex: Int(candidate.sdpMLineIndex), sdp: candidate.sdp, peerUuid: peerUuid)
    }

    public func rejectCall() { rejectCall(outcome: .declined) }

    private func rejectCall(outcome: CallOutcome) {
        guard callState == .incomingRinging else { return }
        if let peerUuid = currentPeerUuid {
            CallSignalingHandler.shared.sendReject(peerUuid: peerUuid)
        }
        resetState(outcome: outcome)
    }

    public func handleRemoteAnswer(sdp: String) {
        guard isCaller, callState == .outgoingRinging else { return }
        guard sdp.components(separatedBy: "\n").contains(where: { $0.hasPrefix("m=video ") }) == isVideoCall else {
            endCall(outcome: .failed)
            return
        }
        startTimeoutTimer()

        let optimizedAnswerSdp = OpusCodecHelper.modifySdpForOpus(sdpDescription: sdp, maxBitrateKbps: 32)
        let finalAnswer = RTCSessionDescription(type: .answer, sdp: optimizedAnswerSdp)

        guard let connection = peerConnection else { endCall(outcome: .failed); return }
        callState = .connecting
        connection.setRemoteDescription(finalAnswer) { [weak self] error in
            self?.withCurrentConnection(connection) {
                guard let self else { return }
                if error != nil { self.endCall(outcome: .failed) }
                else { self.flushPendingCandidates(connection) }
            }
        }
    }

    public func signalingFailed() {
        guard callState != .idle, callState != .ended else { return }
        resetState(outcome: .failed)
        Task { @MainActor in NoticeCenter.shared.show("Call signaling unavailable") }
    }

    public func handleRemoteIce(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        guard callState != .idle, callState != .ended, (0...255).contains(sdpMLineIndex) else { return }
        let candidate = RTCIceCandidate(sdp: sdp, sdpMLineIndex: Int32(sdpMLineIndex), sdpMid: sdpMid)
        if peerConnection?.remoteDescription == nil {
            if pendingIceCandidates.count < 256 { pendingIceCandidates.append(candidate) }
        } else {
            peerConnection?.add(candidate) { err in
                if let err = err {
                    print("[MsgAT-Call] addIceCandidate error: \(err)")
                }
            }
        }
    }

    public func handleRemoteReject() {
        guard callState != .idle && callState != .ended else { return }
        resetState(outcome: .declined)
    }

    public func handleRemoteBusy() {
        guard callState != .idle && callState != .ended else { return }
        resetState(outcome: .busy)
    }

    public func handleRemoteEnd() {
        guard callState != .idle && callState != .ended else { return }
        resetState(outcome: callState == .incomingRinging ? .missed : .failed)
    }

    public func endCall() { endCall(outcome: .canceled) }

    private func endCall(outcome: CallOutcome) {
        guard callState != .idle && callState != .ended else { return }
        if let peerUuid = currentPeerUuid {
            CallSignalingHandler.shared.sendEnd(peerUuid: peerUuid)
        }
        resetState(outcome: outcome)
    }

    public func toggleMute() {
        isMuted.toggle()
        localAudioTrack?.isEnabled = !isMuted
    }

    public func toggleSpeaker() {
        guard let speakerDevice else {
            Task { @MainActor in NoticeCenter.shared.show(InterfaceStrings.current()("call_speaker_unavailable")) }
            return
        }
        chooseAudioDevice(isSpeakerOn ? nil : speakerDevice, input: false)
    }

    func chooseAudioDevice(_ device: AudioDeviceID?, input: Bool) {
        guard callState == .connected, !isSwitchingAudio, let connection = peerConnection else { return }
        if let device, !(input ? microphones : outputs).contains(where: { $0.id == device }) {
            Task { @MainActor in NoticeCenter.shared.show(InterfaceStrings.current()("call_audio_device_unavailable")) }
            return
        }
        isSwitchingAudio = true
        nativeAudioDevice.selectRoute(device, input: input) { [weak self] success in
            DispatchQueue.main.async {
                guard let self, self.peerConnection === connection else { return }
                self.isSwitchingAudio = false
                if !success { NoticeCenter.shared.show(InterfaceStrings.current()("call_audio_switch_failed")) }
            }
        }
    }

    private func updateAudioRoutes(_ selection: MacCallAudioDevice.Selection) {
        let availableInputs = audioOutput.endpoints(input: true)
        let availableOutputs = audioOutput.endpoints(input: false)
        let inputLost = audioSelection.input.map { id in selection.input == nil && !availableInputs.contains { $0.id == id } } ?? false
        let outputLost = audioSelection.output.map { id in selection.output == nil && !availableOutputs.contains { $0.id == id } } ?? false
        if audioSelection != selection { audioSelection = selection }
        if microphones != availableInputs { microphones = availableInputs }
        if outputs != availableOutputs { outputs = availableOutputs }
        speakerDevice = try? audioOutput.builtInSpeaker()
        let speaker = speakerDevice != nil && selection.output == speakerDevice
        if isSpeakerOn != speaker { isSpeakerOn = speaker }
        if inputLost || outputLost {
            Task { @MainActor in NoticeCenter.shared.show(InterfaceStrings.current()("call_audio_fallback")) }
        }
    }

    public func restoreAudioOutput() {
        do {
            try audioOutput.restore()
            isSpeakerOn = false
        } catch {
            print("[MsgAT-Call] Audio output restoration failed: \(error.localizedDescription)")
        }
    }

    private func sendCallRecord(peer: String, outcome: CallOutcome, duration: Int64) {
        let kind = isVideoCall ? "Video Call" : "Voice Call"
        let result: String
        switch outcome {
        case .completed: result = String(format: "%02lld:%02lld", duration / 60, duration % 60)
        case .missed: result = "Missed"
        case .declined: result = "Declined"
        case .busy: result = "Busy"
        case .canceled: result = "Canceled"
        case .failed: result = "Failed"
        }
        CallSignalingHandler.shared.sendCallRecordMessage(peerUuid: peer, text: "[\(kind): \(result)]")
    }

    func pauseVideoCamera() { videoSession?.disableCamera() }

    @MainActor
    func endCallAndDrainVideo() async {
        endCall(outcome: .failed)
        await videoRelease?.value
    }

    private func resetState(outcome: CallOutcome) {
        NotificationHelper.shared.clearCallNotification()
        endedOutcome = callState == .connected || callDurationSeconds > 0 ? .completed : outcome
        if let peer=currentPeerUuid, let id=currentCallId {
            SecureMessaging.shared.recordCallHistory(peer:peer,callId:id,outgoing:isCaller,video:isVideoCall,
                outcome:endedOutcome,duration:callDurationSeconds,
                endedAt:Int64(Date().timeIntervalSince1970*1000))
            if isCaller { sendCallRecord(peer: peer, outcome: endedOutcome, duration: callDurationSeconds) }
        }
        callState = .ended
        isRecoveringConnection = false
        currentCallId = nil
        outgoingAttempt = nil
        isSwitchingAudio = false
        timerTask?.cancel()
        timerTask = nil
        timeoutTask?.cancel()
        timeoutTask = nil
        rttTask?.cancel()
        rttTask = nil
        disconnectGraceTask?.cancel()
        disconnectGraceTask = nil

        if let videoSession { videoRelease = videoSession.close() }
        videoSession = nil
        peerConnection?.close()
        peerConnection = nil
        localAudioTrack = nil
        localAudioSource = nil
        pendingOffer = nil
        pendingIceCandidates.removeAll()
        pendingLocalIceCandidates.removeAll()
        localDescriptionSignaled = false

        restoreAudioOutput()
        nativeAudioDevice.resetRoutes()
        audioSelection = MacCallAudioDevice.Selection()
        MicrophoneOwnership.shared.release(microphone)
        microphone = nil

        Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            await MainActor.run {
                self.callState = .idle
                self.isVideoCall = false
                self.callDurationSeconds = 0
                self.rttMs = 0
                self.currentPeerUuid = nil
                self.currentPeerAlias = "Unknown"
                self.currentPeerAvatarPath = nil
                self.isMuted = false
                self.isMinimized = false
            }
        }
    }

    private func startCallTimers() {
        timerTask?.cancel()
        rttTask?.cancel()

        callDurationSeconds = 0
        // Zero means "not measured yet". Never fabricate a plausible latency.
        rttMs = 0

        let startTime = ProcessInfo.processInfo.systemUptime
        timerTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard !Task.isCancelled else { return }
                let elapsed = Int64(max(0, ProcessInfo.processInfo.systemUptime - startTime))
                await MainActor.run {
                    if self.callState == .connected {
                        self.callDurationSeconds = elapsed
                    }
                }
            }
        }

        rttTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard !Task.isCancelled, let self = self, let pc = self.peerConnection else { break }

                let report = await pc.statistics()
                var minRtt: Int64 = Int64.max

                for (_, stat) in report.statistics {
                    let seconds: Double?
                    if stat.type == "candidate-pair" {
                        guard stat.values["state"] as? String == "succeeded" else { continue }
                        seconds = ((stat.values["currentRoundTripTime"] as? NSNumber) ?? (stat.values["roundTripTime"] as? NSNumber))?.doubleValue
                    } else if stat.type == "remote-inbound-rtp" {
                        seconds = (stat.values["roundTripTime"] as? NSNumber)?.doubleValue
                    } else { continue }
                    guard let seconds, seconds.isFinite, seconds > 0,
                          seconds < Double(Int64.max) / 1000 else { continue }
                    let ms = Int64(seconds * 1000)
                    if ms > 0 && ms < minRtt { minRtt = ms }
                }

                if minRtt != Int64.max {
                    let measuredRtt = minRtt
                    await MainActor.run {
                        if self.callState == .connected && !self.isRecoveringConnection &&
                            self.peerConnection === pc && !Task.isCancelled {
                            self.rttMs = measuredRtt
                        }
                    }
                }
            }
        }
    }

    private func ensureMicrophonePermission(resume: @escaping () -> Void) -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: return true
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { granted in
                DispatchQueue.main.async {
                    if granted { resume() }
                    else { NoticeCenter.shared.show(InterfaceStrings.current()("call_microphone_permission")) }
                }
            }
        default: Task { @MainActor in NoticeCenter.shared.show(InterfaceStrings.current()("call_microphone_permission")) }
        }
        return false
    }

    private func startTimeoutTimer() {
        timeoutTask?.cancel()
        timeoutTask = Task {
            try? await Task.sleep(nanoseconds: 30 * 1_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                if self.callState == .incomingRinging || self.callState == .outgoingRinging || self.callState == .connecting {
                    NoticeCenter.shared.show("Time out")
                }
                if self.callState == .incomingRinging {
                    self.rejectCall(outcome: .missed)
                } else if self.callState == .outgoingRinging || self.callState == .connecting {
                    self.endCall(outcome: self.callState == .outgoingRinging ? .missed : .failed)
                }
            }
        }
    }

    // MARK: - RTCPeerConnectionDelegate
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        withCurrentConnection(peerConnection) { [weak self] in
            if let track = stream.videoTracks.first { self?.videoSession?.setRemoteTrack(track) }
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {
        withCurrentConnection(peerConnection) { [weak self] in
            if let track = rtpReceiver.track as? RTCVideoTrack { self?.videoSession?.setRemoteTrack(track) }
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove rtpReceiver: RTCRtpReceiver) {
        withCurrentConnection(peerConnection) { [weak self] in
            if let track = rtpReceiver.track as? RTCVideoTrack { self?.videoSession?.removeRemoteTrack(track) }
        }
    }

    public func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            guard self.peerConnection === peerConnection, self.callState != .idle, self.callState != .ended else { return }
            print("[MsgAT-Call] ICE Connection State: \(newState.rawValue)")
            if newState == .connected || newState == .completed {
                self.disconnectGraceTask?.cancel()
                self.disconnectGraceTask = nil
                self.isRecoveringConnection = false
                if self.callState != .connected {
                    self.callState = .connected
                    self.startCallTimers()

                    // Clamp audio sender bitrate to 32 kbps (1:1 with Android)
                    if let senders = self.peerConnection?.senders {
                        for sender in senders where sender.track?.kind == "audio" {
                            let params = sender.parameters
                            for encoding in params.encodings {
                                encoding.maxBitrateBps = NSNumber(value: 32_000)
                            }
                            sender.parameters = params
                        }
                    }
                }
            } else if newState == .disconnected {
                if self.callState == .connected {
                    self.isRecoveringConnection = true
                    self.rttMs = 0
                }
                // Long-distance links can transiently drop ICE; give a 10s recovery
                // window before treating the call as dead (prevents instant hang-up).
                if self.disconnectGraceTask == nil {
                    self.disconnectGraceTask = Task {
                        try? await Task.sleep(nanoseconds: 10_000_000_000)
                        guard !Task.isCancelled else { return }
                        await MainActor.run {
                            self.disconnectGraceTask = nil
                            let st = self.peerConnection?.iceConnectionState
                            if st != .connected && st != .completed {
                                self.endCall(outcome: .failed)
                            }
                        }
                    }
                }
            } else if newState == .failed {
                self.endCall(outcome: .failed)
            }
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        withCurrentConnection(peerConnection) { [weak self] in
            guard let self, let peerUuid = self.currentPeerUuid else { return }
            if self.localDescriptionSignaled { self.sendLocalCandidate(candidate, peerUuid: peerUuid) }
            else if self.pendingLocalIceCandidates.count < 256 { self.pendingLocalIceCandidates.append(candidate) }
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}
