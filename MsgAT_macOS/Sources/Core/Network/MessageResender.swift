import Foundation

public final class MessageResender: Sendable {
    public static let shared = MessageResender()
    private let queue = DispatchQueue(label: "org.msgat.resender", qos: .utility)
    private init() {}

    public func resendPendingMessages() {
        queue.async {
            do {
                let pending = try MsgDao.shared.getPendingMessages()
                let myUuid = KeychainManager.shared.myUuid
                let privateKey = KeychainManager.shared.x25519PrivateKey
                for message in pending {
                    guard myUuid == KeychainManager.shared.myUuid else { return }
                    do {
                        guard let peer = try MsgDao.shared.getConversationByUuid(message.conversationUuid),
                              !peer.peerPublicKey.isEmpty else { continue }
                        let chunks: [String]
                        if message.ext == "txt" {
                            chunks = [Data(message.content.utf8).base64EncodedString()]
                        } else {
                            guard let url = MediaStorageManager.shared.getOrExtractMediaFile(
                                msgId: message.messageId, ext: message.ext, content: message.content) else { continue }
                            chunks = try MediaStorageManager.shared.base64Chunks(bytes: Data(contentsOf: url))
                        }
                        var sent = true
                        for (index, chunk) in chunks.enumerated() {
                            guard myUuid == KeychainManager.shared.myUuid else { return }
                            let payload = MessagePayload(senderUuid: myUuid, fileId: message.messageId,
                                total: chunks.count, index: index, ext: message.ext, data: chunk, timestamp: message.timestamp,
                                replyToId: message.replyToId, replyToSender: message.replyToSender, replyToContent: message.replyToContent)
                            var bytes = [UInt8](repeating: 0, count: 16)
                            guard SecRandomCopyBytes(kSecRandomDefault, 16, &bytes) == errSecSuccess else { throw CryptoError.encryptionFailed }
                            let iv = Data(bytes)
                            let (cipher, tag) = try CryptoManager.encryptPayloadECDH(myPrivateKey: privateKey,
                                peerPublicKeyBase64: peer.peerPublicKey, iv: iv, plaintext: JSONEncoder().encode(payload))
                            if !NWClient.shared.sendPacket(GhostPacket(type: GhostPacket.typeMessage,
                                targetMailboxId: CryptoManager.calculateMailboxId(uuid: peer.uuid), nonceOrIv: iv,
                                encryptedData: cipher, authTag: tag)) {
                                sent = false
                                break
                            }
                            if index + 1 < chunks.count { Thread.sleep(forTimeInterval: 0.05) }
                        }
                        if sent { try MsgDao.shared.updateMessageStatus(messageId: message.messageId, status: 1) }
                    } catch { NSLog("Pending message could not be sent: %@", String(describing: error)) }
                }
            } catch { NSLog("Pending messages could not be loaded: %@", String(describing: error)) }
        }
    }
}
