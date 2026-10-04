import Foundation
import Combine
import WebRTC
import AVFoundation
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
    @Published public var currentPeerUuid: String? = nil

    public func isCurrentPeer(_ peerUuid: String) -> Bool {
        currentPeerUuid == peerUuid && callState != .idle && callState != .ended
    }
    @Published public var currentPeerAlias: String = "Unknown"
    @Published public var currentPeerAvatarPath: String? = nil
    @Published public var isMinimized = false
    @Published public var isMuted: Bool = false
    @Published public private(set) var isSpeakerOn = false
    private let audioOutput = CallAudioOutput()
    private let nativeAudioDevice = MacCallAudioDevice()
    private var isSwitchingSpeaker = false

    private var isCaller: Bool = false
    private var pendingOffer: RTCSessionDescription? = nil
    private var pendingIceCandidates: [RTCIceCandidate] = []
    private var pendingLocalIceCandidates: [RTCIceCandidate] = []
    private var localDescriptionSignaled = false

    private var peerConnectionFactory: RTCPeerConnectionFactory?
    private var peerConnection: RTCPeerConnection?
    private var localAudioTrack: RTCAudioTrack?
    private var localAudioSource: RTCAudioSource?

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
                    self.endCall()
                }
            }
            nativeAudioDevice.setRouteChangeHandler { [weak self] in
                DispatchQueue.main.async {
                    guard let self, self.peerConnection.map(ObjectIdentifier.init) == identity,
                          !self.isSwitchingSpeaker, self.isSpeakerOn,
                          (try? self.audioOutput.isSpeakerOverrideActive()) == false else { return }
                    self.restoreAudioOutput()
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
    }

    // MARK: - Outgoing Call
    public func startOutgoingCall(peerUuid: String) {
        guard callState == .idle else { return }
        guard let conv = try? MsgDao.shared.getConversationByUuid(peerUuid), !conv.peerPublicKey.isEmpty else {
            Task { @MainActor in NoticeCenter.shared.show("Cannot call a non-friend.") }; return
        }
        guard ensureMicrophonePermission(resume: { [weak self] in self?.startOutgoingCall(peerUuid: peerUuid) }) else { return }

        isCaller = true
        currentPeerUuid = peerUuid
        currentPeerAlias = conv.alias
        currentPeerAvatarPath = conv.avatarPath
        callState = .outgoingRinging
        callDurationSeconds = 0
        rttMs = 0
        pendingIceCandidates.removeAll()

        startTimeoutTimer()
        createPeerConnection()

        guard let connection = peerConnection else { endCall(); return }
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        connection.offer(for: constraints) { [weak self] sdp, error in
            self?.withCurrentConnection(connection) {
                guard let self, let desc = sdp, error == nil else { self?.endCall(); return }
                let finalOffer = RTCSessionDescription(type: desc.type,
                    sdp: OpusCodecHelper.modifySdpForOpus(sdpDescription: desc.sdp, maxBitrateKbps: 32))
                connection.setLocalDescription(finalOffer) { [weak self] error in
                    self?.withCurrentConnection(connection) {
                        guard let self else { return }
                        if error != nil { self.endCall() }
                        else { self.signalLocalDescription(finalOffer, peerUuid: peerUuid, isOffer: true) }
                    }
                }
            }
        }
    }

    // MARK: - Incoming Call
    public func handleIncomingOffer(senderUuid: String, sdp: String) {
        guard callState == .idle else {
            CallSignalingHandler.shared.sendBusy(peerUuid: senderUuid)
            return
        }

        let conv = try? MsgDao.shared.getConversationByUuid(senderUuid)
        let alias = conv?.alias ?? "Unknown"
        let avatar = conv?.avatarPath

        isCaller = false
        currentPeerUuid = senderUuid
        currentPeerAlias = alias
        currentPeerAvatarPath = avatar
        pendingOffer = RTCSessionDescription(type: .offer, sdp: sdp)
        callState = .incomingRinging
        callDurationSeconds = 0
        rttMs = 0
        pendingIceCandidates.removeAll()

        NotificationHelper.shared.showIncomingCallNotification(peerUuid: senderUuid, alias: alias)
        startTimeoutTimer()
    }

    public func acceptCall() {
        guard callState == .incomingRinging, let peerUuid = currentPeerUuid, let rawOffer = pendingOffer else { return }
        startTimeoutTimer()

        guard ensureMicrophonePermission(resume: { [weak self] in
            guard self?.currentPeerUuid == peerUuid else { return }; self?.acceptCall()
        }) else { return }

        NotificationHelper.shared.clearCallNotification(peerUuid: peerUuid)
        callState = .connecting

        createPeerConnection()
        guard let connection = peerConnection else { endCall(); return }
        let offer = RTCSessionDescription(type: rawOffer.type,
            sdp: OpusCodecHelper.modifySdpForOpus(sdpDescription: rawOffer.sdp, maxBitrateKbps: 32))
        connection.setRemoteDescription(offer) { [weak self] error in
            self?.withCurrentConnection(connection) {
                guard let self else { return }
                guard error == nil else { self.endCall(); return }
                self.flushPendingCandidates(connection)
                let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
                connection.answer(for: constraints) { [weak self] answer, error in
                    self?.withCurrentConnection(connection) {
                        guard let self, let answer, error == nil else { self?.endCall(); return }
                        let finalAnswer = RTCSessionDescription(type: answer.type,
                            sdp: OpusCodecHelper.modifySdpForOpus(sdpDescription: answer.sdp, maxBitrateKbps: 32))
                        connection.setLocalDescription(finalAnswer) { [weak self] error in
                            self?.withCurrentConnection(connection) {
                                guard let self else { return }
                                if error != nil { self.endCall() }
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

    public func rejectCall() {
        guard callState == .incomingRinging else { return }
        if let peerUuid = currentPeerUuid {
            NotificationHelper.shared.clearCallNotification(peerUuid: peerUuid)
            CallSignalingHandler.shared.sendReject(peerUuid: peerUuid)
        }
        resetState()
    }

    public func handleRemoteAnswer(sdp: String) {
        guard isCaller, callState == .outgoingRinging else { return }
        startTimeoutTimer()

        let optimizedAnswerSdp = OpusCodecHelper.modifySdpForOpus(sdpDescription: sdp, maxBitrateKbps: 32)
        let finalAnswer = RTCSessionDescription(type: .answer, sdp: optimizedAnswerSdp)

        guard let connection = peerConnection else { endCall(); return }
        callState = .connecting
        connection.setRemoteDescription(finalAnswer) { [weak self] error in
            self?.withCurrentConnection(connection) {
                guard let self else { return }
                if error != nil { self.endCall() }
                else { self.flushPendingCandidates(connection) }
            }
        }
    }

    public func handleRemoteSync(duration: Int64) {
        // CALL_SYNC is advisory signaling only. A peer must never be considered
        // connected until WebRTC reports ICE connected/completed below.
        // Starting timers here caused unanswered calls to appear connected.
        _ = duration
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
        if let peerUuid = currentPeerUuid {
            NotificationHelper.shared.clearCallNotification(peerUuid: peerUuid)
        }
        saveCallRecord(rejected: true)
        resetState()
    }

    public func handleRemoteBusy() {
        guard callState != .idle && callState != .ended else { return }
        if let peerUuid = currentPeerUuid {
            NotificationHelper.shared.clearCallNotification(peerUuid: peerUuid)
        }
        saveCallRecord(busy: true)
        resetState()
    }

    public func handleRemoteEnd(duration: Int64) {
        guard callState != .idle && callState != .ended else { return }
        if let peerUuid = currentPeerUuid {
            NotificationHelper.shared.clearCallNotification(peerUuid: peerUuid)
        }
        saveCallRecord(durationOverride: duration >= 0 ? duration : nil)
        resetState()
    }

    public func endCall() {
        guard callState != .idle && callState != .ended else { return }
        let finalDuration = callDurationSeconds
        saveCallRecord(durationOverride: finalDuration)
        if let peerUuid = currentPeerUuid {
            NotificationHelper.shared.clearCallNotification(peerUuid: peerUuid)
            CallSignalingHandler.shared.sendEnd(peerUuid: peerUuid, duration: finalDuration)
        }
        resetState()
    }

    public func toggleMute() {
        isMuted.toggle()
        localAudioTrack?.isEnabled = !isMuted
    }

    public func toggleSpeaker() {
        guard !isSwitchingSpeaker,
              callState == .outgoingRinging || callState == .connecting || callState == .connected else { return }
        let requested = !isSpeakerOn
        let connectionID = peerConnection.map(ObjectIdentifier.init)
        do {
            if isSpeakerOn { try audioOutput.restore() }
            else { try audioOutput.enableSpeaker() }
            isSwitchingSpeaker = true
            nativeAudioDevice.refreshOutputRoute { [weak self] success in
                DispatchQueue.main.async {
                    guard let self, self.peerConnection.map(ObjectIdentifier.init) == connectionID,
                          self.callState != .idle, self.callState != .ended else { return }
                    self.isSwitchingSpeaker = false
                    if success {
                        self.isSpeakerOn = requested && (try? self.audioOutput.isSpeakerOverrideActive()) == true
                        if requested && !self.isSpeakerOn { self.restoreAudioOutput() }
                    } else { self.endCall() }
                }
            }
        } catch {
            print("[MsgAT-Call] Audio output switch failed: \(error.localizedDescription)")
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

    private func saveCallRecord(rejected: Bool = false, busy: Bool = false, durationOverride: Int64? = nil) {
        guard isCaller, let peerUuid = currentPeerUuid else { return }

        let duration = durationOverride ?? callDurationSeconds
        let recordText: String
        if busy {
            recordText = "[Voice Call: Busy]"
        } else if rejected {
            recordText = "[Voice Call: Declined]"
        } else if duration == 0 {
            recordText = "[Voice Call: Missed]"
        } else {
            let minutes = duration / 60
            let seconds = duration % 60
            recordText = String(format: "[Voice Call: %02d:%02d]", minutes, seconds)
        }
        CallSignalingHandler.shared.sendCallRecordMessage(peerUuid: peerUuid, text: recordText)
    }

    private func resetState() {
        callState = .ended
        isSwitchingSpeaker = false
        timerTask?.cancel()
        timerTask = nil
        timeoutTask?.cancel()
        timeoutTask = nil
        rttTask?.cancel()
        rttTask = nil
        disconnectGraceTask?.cancel()
        disconnectGraceTask = nil

        peerConnection?.close()
        peerConnection = nil
        localAudioTrack = nil
        localAudioSource = nil
        pendingOffer = nil
        pendingIceCandidates.removeAll()
        pendingLocalIceCandidates.removeAll()
        localDescriptionSignaled = false

        restoreAudioOutput()

        Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            await MainActor.run {
                self.callState = .idle
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
                        if self.callState == .connected && self.peerConnection === pc && !Task.isCancelled {
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
                    else { NoticeCenter.shared.show("Microphone permission is required for voice calls.") }
                }
            }
        default: Task { @MainActor in NoticeCenter.shared.show("Microphone permission is required for voice calls.") }
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
                    self.rejectCall()
                } else if self.callState == .outgoingRinging || self.callState == .connecting {
                    self.endCall()
                }
            }
        }
    }

    // MARK: - RTCPeerConnectionDelegate
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}

    public func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            guard self.peerConnection === peerConnection, self.callState != .idle, self.callState != .ended else { return }
            print("[MsgAT-Call] ICE Connection State: \(newState.rawValue)")
            if newState == .connected || newState == .completed {
                self.disconnectGraceTask?.cancel()
                self.disconnectGraceTask = nil
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
                                self.endCall()
                            }
                        }
                    }
                }
            } else if newState == .failed {
                self.endCall()
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
