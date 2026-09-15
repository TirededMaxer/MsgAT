import Foundation
import Combine
import WebRTC
import AVFoundation

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
    @Published public var isMuted: Bool = false

    private var isCaller: Bool = false
    private var pendingOffer: RTCSessionDescription? = nil
    private var pendingIceCandidates: [RTCIceCandidate] = []

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
        peerConnectionFactory = RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
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

        let config = RTCConfiguration()
        config.iceServers = buildIceServers()
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        peerConnection = factory.peerConnection(with: config, constraints: constraints, delegate: self)

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
            return
        }

        if #available(macOS 10.14, *) {
            if AVCaptureDevice.authorizationStatus(for: .audio) == .notDetermined {
                AVCaptureDevice.requestAccess(for: .audio) { _ in }
            }
        }

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

        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)

        peerConnection?.offer(for: constraints) { [weak self] sdp, error in
            guard let self = self, let desc = sdp, error == nil else {
                print("[MsgAT-Call] offer error: \(String(describing: error))")
                self?.endCall()
                return
            }
            let optimizedSdp = OpusCodecHelper.modifySdpForOpus(sdpDescription: desc.sdp, maxBitrateKbps: 32)
            let finalOffer = RTCSessionDescription(type: desc.type, sdp: optimizedSdp)

            self.peerConnection?.setLocalDescription(finalOffer) { [weak self] err in
                if let err = err {
                    print("[MsgAT-Call] setLocalDescription offer error: \(err)")
                    self?.endCall()
                } else {
                    CallSignalingHandler.shared.sendOffer(offerSdp: finalOffer.sdp, peerUuid: peerUuid)
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

        if #available(macOS 10.14, *) {
            if AVCaptureDevice.authorizationStatus(for: .audio) == .notDetermined {
                AVCaptureDevice.requestAccess(for: .audio) { _ in }
            }
        }

        NotificationHelper.shared.clearCallNotification(peerUuid: peerUuid)
        callState = .connecting

        createPeerConnection()

        let optimizedOfferSdp = OpusCodecHelper.modifySdpForOpus(sdpDescription: rawOffer.sdp, maxBitrateKbps: 32)
        let offer = RTCSessionDescription(type: rawOffer.type, sdp: optimizedOfferSdp)

        peerConnection?.setRemoteDescription(offer) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                print("[MsgAT-Call] setRemoteDescription offer error: \(error)")
                self.endCall()
                return
            }

            for candidate in self.pendingIceCandidates {
                self.peerConnection?.add(candidate) { err in
                    if let err = err {
                        print("[MsgAT-Call] Failed to add pending candidate: \(err)")
                    }
                }
            }
            self.pendingIceCandidates.removeAll()

            let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)

            self.peerConnection?.answer(for: constraints) { [weak self] answerDesc, err in
                guard let self = self, let origAnswer = answerDesc, err == nil else {
                    print("[MsgAT-Call] answer creation error: \(String(describing: err))")
                    self?.endCall()
                    return
                }
                let optimizedAnswerSdp = OpusCodecHelper.modifySdpForOpus(sdpDescription: origAnswer.sdp, maxBitrateKbps: 32)
                let finalAnswer = RTCSessionDescription(type: origAnswer.type, sdp: optimizedAnswerSdp)

                self.peerConnection?.setLocalDescription(finalAnswer) { [weak self] setErr in
                    guard let self = self else { return }
                    if let setErr = setErr {
                        print("[MsgAT-Call] setLocalDescription answer error: \(setErr)")
                        self.endCall()
                    } else {
                        CallSignalingHandler.shared.sendAnswer(answerSdp: finalAnswer.sdp, peerUuid: peerUuid)
                    }
                }
            }
        }
    }

    public func rejectCall() {
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

        peerConnection?.setRemoteDescription(finalAnswer) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                print("[MsgAT-Call] setRemoteDescription answer error: \(error)")
                return
            }
            for candidate in self.pendingIceCandidates {
                self.peerConnection?.add(candidate) { err in
                    if let err = err {
                        print("[MsgAT-Call] Failed to add pending candidate: \(err)")
                    }
                }
            }
            self.pendingIceCandidates.removeAll()
        }
        callState = .connecting
    }

    public func handleRemoteSync(duration: Int64) {
        // CALL_SYNC is advisory signaling only. A peer must never be considered
        // connected until WebRTC reports ICE connected/completed below.
        // Starting timers here caused unanswered calls to appear connected.
        _ = duration
    }

    public func handleRemoteIce(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
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
            }
        }
    }

    private func startCallTimers() {
        timerTask?.cancel()
        rttTask?.cancel()

        callDurationSeconds = 0
        // Zero means "not measured yet". Never fabricate a plausible latency.
        rttMs = 0

        let startTime = Date()
        timerTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                let elapsed = Int64(Date().timeIntervalSince(startTime))
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
                guard let self = self, let pc = self.peerConnection else { break }

                let report = await pc.statistics()
                var minRtt: Int64 = Int64.max

                for (_, stat) in report.statistics {
                    if stat.type == "candidate-pair" {
                        if let rttNum = (stat.values["currentRoundTripTime"] as? NSNumber) ?? (stat.values["roundTripTime"] as? NSNumber) {
                            let ms = Int64(rttNum.doubleValue * 1000.0)
                            if ms > 0 && ms < minRtt { minRtt = ms }
                        }
                    } else if stat.type == "remote-inbound-rtp" {
                        if let rttNum = stat.values["roundTripTime"] as? NSNumber {
                            let ms = Int64(rttNum.doubleValue * 1000.0)
                            if ms > 0 && ms < minRtt { minRtt = ms }
                        }
                    }
                }

                if minRtt != Int64.max {
                    let measuredRtt = minRtt
                    await MainActor.run {
                        if self.callState == .connected {
                            self.rttMs = measuredRtt
                        }
                    }
                }
            }
        }
    }

    private func startTimeoutTimer() {
        timeoutTask?.cancel()
        timeoutTask = Task {
            try? await Task.sleep(nanoseconds: 30 * 1_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
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
        if let peerUuid = currentPeerUuid {
            let mid = (candidate.sdpMid != nil && !candidate.sdpMid!.isEmpty) ? candidate.sdpMid! : "0"
            CallSignalingHandler.shared.sendIceCandidate(
                sdpMid: mid,
                sdpMLineIndex: Int(candidate.sdpMLineIndex),
                sdp: candidate.sdp,
                peerUuid: peerUuid
            )
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}
