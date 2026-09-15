import Foundation

public final class CallSignalingHandler: Sendable {
    public static let shared = CallSignalingHandler()

    private init() {}

    public func sendSignalingPacket(peerUuid: String, type: UInt8, payloadDict: [String: Any]) {
        guard let conv = try? MsgDao.shared.getConversationByUuid(peerUuid), !conv.peerPublicKey.isEmpty else { return }

        var dict = payloadDict
        dict["senderUuid"] = KeychainManager.shared.myUuid

        guard let payloadData = try? JSONSerialization.data(withJSONObject: dict) else { return }

        var ivBytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, 16, &ivBytes)
        let iv = Data(ivBytes)

        let myPrivKey = KeychainManager.shared.x25519PrivateKey
        guard let (cipher, tag) = try? CryptoManager.encryptPayloadECDH(
            myPrivateKey: myPrivKey,
            peerPublicKeyBase64: conv.peerPublicKey,
            iv: iv,
            plaintext: payloadData
        ) else { return }

        let targetMailbox = CryptoManager.calculateMailboxId(uuid: peerUuid)
        let packet = GhostPacket(
            type: type,
            targetMailboxId: targetMailbox,
            nonceOrIv: iv,
            encryptedData: cipher,
            authTag: tag
        )
        NWClient.shared.sendPacket(packet)
    }

    public func sendOffer(offerSdp: String, peerUuid: String) {
        sendSignalingPacket(peerUuid: peerUuid, type: GhostPacket.typeCallOffer, payloadDict: [
            "type": "offer",
            "sdp": offerSdp
        ])
    }

    public func sendAnswer(answerSdp: String, peerUuid: String) {
        sendSignalingPacket(peerUuid: peerUuid, type: GhostPacket.typeCallAnswer, payloadDict: [
            "type": "answer",
            "sdp": answerSdp
        ])
    }

    public func sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String, peerUuid: String) {
        sendSignalingPacket(peerUuid: peerUuid, type: GhostPacket.typeCallIce, payloadDict: [
            "type": "ice",
            "sdpMid": sdpMid,
            "sdpMLineIndex": sdpMLineIndex,
            "sdp": sdp
        ])
    }

    public func sendReject(peerUuid: String) {
        sendSignalingPacket(peerUuid: peerUuid, type: GhostPacket.typeCallReject, payloadDict: [:])
    }

    public func sendEnd(peerUuid: String, duration: Int64) {
        sendSignalingPacket(peerUuid: peerUuid, type: GhostPacket.typeCallEnd, payloadDict: ["duration": duration])
    }

    public func sendBusy(peerUuid: String) {
        sendSignalingPacket(peerUuid: peerUuid, type: GhostPacket.typeCallBusy, payloadDict: [:])
    }

    public func sendSync(peerUuid: String, duration: Int64) {
        sendSignalingPacket(peerUuid: peerUuid, type: GhostPacket.typeCallSync, payloadDict: ["duration": duration])
    }

    public func sendCallRecordMessage(peerUuid: String, text: String) {
        guard let conv = try? MsgDao.shared.getConversationByUuid(peerUuid), !conv.peerPublicKey.isEmpty else { return }

        let msgId = UUID().uuidString
        let chatMsg = ChatMessage(
            messageId: msgId,
            conversationUuid: peerUuid,
            isMine: true,
            content: text,
            ext: "txt",
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            isRead: true,
            status: 1
        )
        try? MsgDao.shared.insertMessage(chatMsg)

        let base64Data = text.data(using: .utf8)?.base64EncodedString() ?? ""
        let payload = MessagePayload(
            senderUuid: KeychainManager.shared.myUuid,
            fileId: msgId,
            total: 1,
            index: 0,
            ext: "txt",
            data: base64Data,
            timestamp: Int64(Date().timeIntervalSince1970 * 1000)
        )

        guard let jsonStr = payload.toJsonString(), let jsonBytes = jsonStr.data(using: .utf8) else { return }

        var ivBytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, 16, &ivBytes)
        let iv = Data(ivBytes)

        let myPrivKey = KeychainManager.shared.x25519PrivateKey
        guard let (cipher, tag) = try? CryptoManager.encryptPayloadECDH(
            myPrivateKey: myPrivKey,
            peerPublicKeyBase64: conv.peerPublicKey,
            iv: iv,
            plaintext: jsonBytes
        ) else { return }

        let targetMailbox = CryptoManager.calculateMailboxId(uuid: peerUuid)
        let packet = GhostPacket(
            type: GhostPacket.typeMessage,
            targetMailboxId: targetMailbox,
            nonceOrIv: iv,
            encryptedData: cipher,
            authTag: tag
        )
        NWClient.shared.sendPacket(packet)
    }

    public func handleIncomingSignaling(type: UInt8, senderUuid: String, json: [String: Any]) {
        DispatchQueue.main.async {
            switch type {
            case GhostPacket.typeCallOffer:
                if let sdp = json["sdp"] as? String {
                    CallManager.shared.handleIncomingOffer(senderUuid: senderUuid, sdp: sdp)
                }
            case GhostPacket.typeCallAnswer:
                guard CallManager.shared.isCurrentPeer(senderUuid) else { return }
                if let sdp = json["sdp"] as? String {
                    CallManager.shared.handleRemoteAnswer(sdp: sdp)
                }
            case GhostPacket.typeCallIce:
                guard CallManager.shared.isCurrentPeer(senderUuid) else { return }
                let sdpMidRaw = json["sdpMid"] as? String
                let sdpMid = (sdpMidRaw != nil && !sdpMidRaw!.isEmpty) ? sdpMidRaw! : "0"
                let sdpMLineIndex = (json["sdpMLineIndex"] as? Int) ?? (json["sdpMLineIndex"] as? NSNumber)?.intValue ?? 0
                if let sdp = json["sdp"] as? String, !sdp.isEmpty {
                    CallManager.shared.handleRemoteIce(sdpMid: sdpMid, sdpMLineIndex: sdpMLineIndex, sdp: sdp)
                }
            case GhostPacket.typeCallReject:
                guard CallManager.shared.isCurrentPeer(senderUuid) else { return }
                CallManager.shared.handleRemoteReject()
            case GhostPacket.typeCallEnd:
                guard CallManager.shared.isCurrentPeer(senderUuid) else { return }
                let duration = (json["duration"] as? Int64) ?? (json["duration"] as? Int).map { Int64($0) } ?? -1
                CallManager.shared.handleRemoteEnd(duration: duration)
            case GhostPacket.typeCallBusy:
                guard CallManager.shared.isCurrentPeer(senderUuid) else { return }
                CallManager.shared.handleRemoteBusy()
            case GhostPacket.typeCallSync:
                guard CallManager.shared.isCurrentPeer(senderUuid) else { return }
                let duration = (json["duration"] as? Int64) ?? (json["duration"] as? Int).map { Int64($0) } ?? 0
                CallManager.shared.handleRemoteSync(duration: duration)
            default:
                break
            }
        }
    }
}
