// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Security

/// Call state is main-thread confined. The shared coordinator serializes Signal sends.
public final class CallSignalingHandler: @unchecked Sendable {
    public static let shared = CallSignalingHandler()
    private init() {}
    private var queued = 0
    private struct Ice { let mid: String; let index: Int; let candidate: String }
    private final class Invitation: @unchecked Sendable {
        let peer: String; let id: String; let sdp: String; let video: Bool; let nonce: String
        let deadline = DispatchTime.now().uptimeNanoseconds + 10_000_000_000
        var ice = [Ice]()
        init(peer: String, id: String, sdp: String, video: Bool, nonce: String) {
            self.peer=peer; self.id=id; self.sdp=sdp; self.video=video; self.nonce=nonce
        }
    }
    private var invitation: Invitation?

    private func send(peer: String, id: String, kind: String, values: [String: Any] = [:]) {
        guard queued < 128 else { failed(peer: peer, id: id); return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var body=values; body["call_id"]=id; body["expires_at"]=now+120_000
        queued += 1
        SecureMessaging.shared.sendCallSignal(to: peer, kind: kind, body: body, createdAt: now) { success in
            DispatchQueue.main.async {
                self.queued -= 1
                if !success { self.failed(peer: peer, id: id) }
            }
        }
    }
    private func failed(peer: String, id: String) {
        if CallManager.shared.isCurrentCall(peer, id) { CallManager.shared.signalingFailed() }
        if invitation?.peer==peer, invitation?.id==id { invitation=nil }
    }
    private func active(_ peer: String, _ kind: String, _ values: [String: Any] = [:]) {
        guard let id=CallManager.shared.currentCallId, CallManager.shared.isCurrentCall(peer, id) else { return }
        send(peer: peer, id: id, kind: kind, values: values)
    }
    public func sendOffer(offerSdp: String, peerUuid: String) {
        active(peerUuid, "call.offer", ["sdp":offerSdp,"video":CallManager.shared.isVideoCall])
    }
    public func sendAnswer(answerSdp: String, peerUuid: String) { active(peerUuid, "call.answer", ["sdp":answerSdp]) }
    public func sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String, peerUuid: String) {
        active(peerUuid,"call.ice",["sdp_mid":sdpMid,"mline_index":sdpMLineIndex,"candidate":sdp])
    }
    public func sendReject(peerUuid: String) { active(peerUuid,"call.reject") }
    public func sendEnd(peerUuid: String) { active(peerUuid,"call.end") }
    public func sendCallRecordMessage(peerUuid: String, text: String) {
        SecureMessaging.shared.sendText(to: peerUuid,text: text,reply: nil) { _ in }
    }

    func receive(peer: String, kind: String, body: [String: Any]) {
        guard let id=body["call_id"] as? String else { return }
        if let pending=invitation, pending.deadline <= DispatchTime.now().uptimeNanoseconds { invitation=nil }
        let manager=CallManager.shared
        switch kind {
        case "call.offer":
            if manager.isCurrentCall(peer,id) || (invitation?.peer==peer && invitation?.id==id) { return }
            guard let video = body["video"] as? Bool else { return }
            guard manager.callState == .idle, invitation == nil else { send(peer:peer,id:id,kind:"call.busy"); return }
            var bytes=[UInt8](repeating:0,count:32)
            guard SecRandomCopyBytes(kSecRandomDefault,bytes.count,&bytes)==errSecSuccess, let sdp=body["sdp"] as? String else { return }
            let pending=Invitation(peer:peer,id:id,sdp:sdp,video:video,nonce:Data(bytes).base64EncodedString())
            invitation=pending
            DispatchQueue.main.asyncAfter(deadline:.now()+10) { if self.invitation === pending { self.invitation=nil } }
            send(peer:peer,id:id,kind:"call.challenge",values:["nonce":pending.nonce])
        case "call.challenge":
            if manager.isOutgoingCall(peer,id), let nonce=body["nonce"] as? String { send(peer:peer,id:id,kind:"call.confirm",values:["nonce":nonce]) }
        case "call.confirm":
            guard let pending=invitation, pending.peer==peer, pending.id==id, pending.nonce==body["nonce"] as? String else { return }
            invitation=nil
            guard manager.callState == .idle else { send(peer:peer,id:id,kind:"call.busy"); return }
            manager.handleIncomingOffer(senderUuid:peer,callId:id,sdp:pending.sdp,video:pending.video)
            for ice in pending.ice { manager.handleRemoteIce(sdpMid:ice.mid,sdpMLineIndex:ice.index,sdp:ice.candidate) }
        case "call.ice":
            guard let mid=body["sdp_mid"] as? String, let index=body["mline_index"] as? Int, let candidate=body["candidate"] as? String else { return }
            if manager.isCurrentCall(peer,id) { manager.handleRemoteIce(sdpMid:mid,sdpMLineIndex:index,sdp:candidate) }
            else if let pending=invitation, pending.peer==peer, pending.id==id, pending.ice.count<256 { pending.ice.append(Ice(mid:mid,index:index,candidate:candidate)) }
        case "call.answer":
            if manager.isCurrentCall(peer,id), let sdp=body["sdp"] as? String { manager.handleRemoteAnswer(sdp:sdp) }
        case "call.reject", "call.end", "call.busy":
            if invitation?.peer==peer, invitation?.id==id { invitation=nil }
            guard manager.isCurrentCall(peer,id) else { return }
            if kind=="call.reject" { manager.handleRemoteReject() }
            else if kind=="call.busy" { manager.handleRemoteBusy() }
            else { manager.handleRemoteEnd() }
        default: break
        }
    }
}
