import Foundation
import CryptoKit
import UserNotifications
import AppKit

public final class GhostClientHandler: @unchecked Sendable {
    public static let shared = GhostClientHandler()

    private let queue = DispatchQueue(label: "org.msgat.handler", qos: .userInitiated)

    private init() {}

    public func handlePacket(_ packet: GhostPacket) {
        queue.async {
            do {
            switch packet.type {
            case GhostPacket.typeAuthChal:
                self.handleAuthChallenge(packet)
            case GhostPacket.typeMessage:
                try self.handleIncomingMessage(packet)
            case GhostPacket.typeSearchUser:
                try self.handleFriendRequest(packet)
            case GhostPacket.typeOfflineEnd:
                self.handleOfflineEnd()
            case GhostPacket.typeProfileResp:
                self.handleProfileResponse(packet)
            case GhostPacket.typeFriendAccepted:
                try self.handleFriendAccepted(packet)
            case GhostPacket.typeFriendDeleted:
                try self.handleFriendDeleted(packet)
            case GhostPacket.typeRequestRevoked:
                try self.handleRequestRevoked(packet)
            case GhostPacket.typeAccountReset:
                try self.handleAccountReset(packet)
            case GhostPacket.typeCallOffer,
                 GhostPacket.typeCallAnswer,
                 GhostPacket.typeCallIce,
                 GhostPacket.typeCallReject,
                 GhostPacket.typeCallEnd,
                 GhostPacket.typeCallBusy,
                 GhostPacket.typeCallSync:
                try self.handleCallSignaling(packet)
            default:
                break
            }
            } catch {
                NSLog("Incoming packet was not committed; relay retains it: %@", String(describing: error))
            }
        }
    }

    // MARK: - Handlers

    private func handleAuthChallenge(_ packet: GhostPacket) {
        guard let nonce = packet.nonceOrIv else { return }
        let privKey = KeychainManager.shared.ed25519PrivateKey
        guard let (pubKey, signature) = try? CryptoManager.signChallenge(privateKey: privKey, nonce: nonce) else { return }

        var responsePayload = pubKey
        responsePayload.append(signature)

        let mailboxId = KeychainManager.shared.myMailboxId
        let respPacket = GhostPacket(
            type: GhostPacket.typeAuthResp,
            targetMailboxId: mailboxId,
            nonceOrIv: nonce,
            encryptedData: responsePayload,
            authTag: Data(count: 16)
        )
        NWClient.shared.sendPacket(respPacket)

        // Update Profile
        let myUuid = KeychainManager.shared.myUuid
        let myNickname = KeychainManager.shared.myNickname ?? "Unknown"
        let myAvatarPath = KeychainManager.shared.myAvatarPath ?? ""

        var avatarDataStr = ""
        if !myAvatarPath.isEmpty {
            let avatarUrl = URL(fileURLWithPath: myAvatarPath)
            if let data = try? Data(contentsOf: avatarUrl) {
                avatarDataStr = data.base64EncodedString()
            }
        }

        let profileDict: [String: String] = [
            "uuid": myUuid,
            "nickname": myNickname,
            "avatarPath": myAvatarPath,
            "avatarData": avatarDataStr
        ]

        if let json = try? JSONSerialization.data(withJSONObject: profileDict) {
            let updatePacket = GhostPacket(
                type: GhostPacket.typeUpdateProfile,
                targetMailboxId: mailboxId,
                nonceOrIv: Data(count: 16),
                encryptedData: json,
                authTag: Data(count: 16)
            )
            NWClient.shared.sendPacket(updatePacket)
        }
    }

    private func handleOfflineEnd() {
        DispatchQueue.main.async {
            NWClient.shared.connectionState = .synced
        }
        MessageResender.shared.resendPendingMessages()
    }

    private struct Assembly {
        let metadata: MessagePayload
        var chunks: [Int: String] = [:]
        var receipts: [Int: Data] = [:]
        var updatedAt = Date()
    }
    private var assemblies: [String: Assembly] = [:]

    private func decryptPeer(_ packet: GhostPacket) throws -> Data? {
        guard let iv = packet.nonceOrIv, let cipher = packet.encryptedData, let tag = packet.authTag else { return nil }
        for friend in try MsgDao.shared.getAllConversations() where !friend.peerPublicKey.isEmpty {
            if let plain = try? CryptoManager.decryptPayloadECDH(
                myPrivateKey: KeychainManager.shared.x25519PrivateKey, peerPublicKeyBase64: friend.peerPublicKey,
                iv: iv, cipherText: cipher, authTag: tag),
               let json = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] {
                guard json["senderUuid"] as? String == friend.uuid else { return nil }
                return plain
            }
        }
        return nil
    }

    private func acknowledge(_ packet: GhostPacket) {
        guard let cipher = packet.encryptedData else { return }
        acknowledgeDigest(Data(SHA256.hash(data: cipher)))
    }

    private func acknowledgeDigest(_ digest: Data) {
        NWClient.shared.sendPacket(GhostPacket(type: GhostPacket.typeAck,
            targetMailboxId: KeychainManager.shared.myMailboxId, nonceOrIv: Data(count: 16),
            encryptedData: digest, authTag: Data(count: 16)))
    }

    private func sendDelivered(_ payload: MessagePayload) throws {
        guard let peer = try MsgDao.shared.getConversationByUuid(payload.senderUuid), !peer.peerPublicKey.isEmpty else { return }
        let ack = MessagePayload(senderUuid: KeychainManager.shared.myUuid, fileId: UUID().uuidString,
            total: 1, index: 0, ext: "ack_deliv", data: payload.fileId, timestamp: Int64(Date().timeIntervalSince1970 * 1000))
        var bytes = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, 16, &bytes) == errSecSuccess else { return }
        let iv = Data(bytes)
        let (cipher, tag) = try CryptoManager.encryptPayloadECDH(
            myPrivateKey: KeychainManager.shared.x25519PrivateKey, peerPublicKeyBase64: peer.peerPublicKey,
            iv: iv, plaintext: try JSONEncoder().encode(ack))
        NWClient.shared.sendPacket(GhostPacket(type: GhostPacket.typeMessage,
            targetMailboxId: CryptoManager.calculateMailboxId(uuid: peer.uuid), nonceOrIv: iv,
            encryptedData: cipher, authTag: tag))
    }

    private func handleIncomingMessage(_ packet: GhostPacket) throws {
        guard let plain = try decryptPeer(packet), let cipher = packet.encryptedData else { return }
        let payload = try JSONDecoder().decode(MessagePayload.self, from: plain)
        guard UUID(uuidString: payload.fileId) != nil,
              payload.ext.range(of: "^[A-Za-z0-9_]{1,10}$", options: .regularExpression) != nil,
              (1...4096).contains(payload.total), (0..<payload.total).contains(payload.index),
              payload.data.utf8.count <= 200000, (0...253402300799999).contains(payload.timestamp) else { return }
        if payload.action == nil, let existing = try MsgDao.shared.getMessageById(payload.fileId) {
            if !existing.isMine && existing.conversationUuid == payload.senderUuid {
                try sendDelivered(payload)
                acknowledge(packet)
            }
            return
        }
        let key = "\(payload.senderUuid):\(payload.fileId)"
        let digest = Data(SHA256.hash(data: cipher))
        var receipts = [digest]
        var data = payload.data
        if payload.total > 1 {
            guard payload.action == nil, !payload.ext.hasPrefix("ack_") else { return }
            let now = Date()
            assemblies = assemblies.filter { now.timeIntervalSince($0.value.updatedAt) <= 600 }
            guard assemblies[key] != nil || assemblies.count < 16 else { return }
            var assembly = assemblies[key] ?? Assembly(metadata: payload)
            let meta = assembly.metadata
            guard meta.total == payload.total, meta.ext == payload.ext, meta.timestamp == payload.timestamp,
                  meta.replyToId == payload.replyToId, meta.replyToSender == payload.replyToSender,
                  meta.replyToContent == payload.replyToContent, meta.action == payload.action,
                  meta.targetMessageId == payload.targetMessageId else { return }
            assembly.chunks[payload.index] = payload.data
            assembly.receipts[payload.index] = digest
            assembly.updatedAt = now
            assemblies[key] = assembly
            let bytes = assemblies.values.reduce(0) { $0 + $1.chunks.values.reduce(0) { $0 + $1.utf8.count } }
            guard bytes <= 140000000 else { assemblies.removeValue(forKey: key); return }
            guard assembly.chunks.count == payload.total else { return }
            data = (0..<payload.total).map { assembly.chunks[$0]! }.joined()
            receipts = Array(assembly.receipts.values)
        }
        if payload.ext == "ack_deliv" || payload.ext == "ack_read" {
            if let target = try MsgDao.shared.getMessageById(data), target.isMine,
               target.conversationUuid == payload.senderUuid {
                if payload.ext == "ack_deliv" {
                    try MsgDao.shared.updateMessageStatus(messageId: target.messageId, status: 2)
                } else {
                    try MsgDao.shared.updateMessageStatusUpTo(targetUuid: payload.senderUuid, timestamp: target.timestamp, status: 3)
                }
            }
        } else if payload.action == "revoke" || payload.action == "edit" {
            guard let id = payload.targetMessageId, let target = try MsgDao.shared.getMessageById(id) else { return }
            if !target.isMine, target.conversationUuid == payload.senderUuid, target.timestamp >= 0,
               payload.timestamp >= target.timestamp, payload.timestamp - target.timestamp <= 120000 {
                if payload.action == "revoke" {
                    try MsgDao.shared.updateMessageRevoked(messageId: id)
                    if target.ext != "txt" { MediaStorageManager.shared.deleteMediaFile(msgId: id, ext: target.ext) }
                } else if target.ext == "txt", target.isRevoked == 0, let bytes = Data(base64Encoded: data),
                          let text = String(data: bytes, encoding: .utf8) {
                    try MsgDao.shared.updateMessageContent(messageId: id, newContent: text)
                }
            }
        } else if payload.action != nil {
            return
        } else {
            guard let bytes = Data(base64Encoded: data), bytes.count <= 100 * 1024 * 1024 else { return }
            let content: String
            if payload.ext == "txt" {
                guard let text = String(data: bytes, encoding: .utf8) else { return }
                content = text
            } else {
                content = try MediaStorageManager.shared.saveMediaBytes(msgId: payload.fileId, ext: payload.ext, bytes: bytes).path
            }
            try MsgDao.shared.insertMessage(ChatMessage(messageId: payload.fileId, conversationUuid: payload.senderUuid,
                isMine: false, content: content, ext: payload.ext, timestamp: payload.timestamp,
                replyToId: payload.replyToId, replyToSender: payload.replyToSender, replyToContent: payload.replyToContent))
            postLocalNotification(senderUuid: payload.senderUuid, ext: payload.ext, content: content)
        }
        if !payload.ext.hasPrefix("ack_") { try sendDelivered(payload) }
        receipts.forEach(acknowledgeDigest)
        assemblies.removeValue(forKey: key)
    }

    private func validFriendIdentity(_ uuid: String, _ key: String) -> Bool {
        uuid != KeychainManager.shared.myUuid && uuid.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil &&
            Data(base64Encoded: key)?.count == 32
    }

    private func handleFriendRequest(_ packet: GhostPacket) throws {
        guard let iv = packet.nonceOrIv,
              let cipherText = packet.encryptedData,
              let authTag = packet.authTag else { return }

        let myUuid = KeychainManager.shared.myUuid
        let myMailboxId = KeychainManager.shared.myMailboxId

        guard let decryptedBytes = try? CryptoManager.decryptPayloadFallback(
            myUuid: myUuid,
            targetUuid: myUuid,
            iv: iv,
            cipherText: cipherText,
            authTag: authTag
        ) else { return }

        guard let jsonObj = try? JSONSerialization.jsonObject(with: decryptedBytes) as? [String: Any],
              let senderUuid = jsonObj["senderUuid"] as? String,
              let senderPublicKey = jsonObj["senderPublicKey"] as? String else { return }

        guard validFriendIdentity(senderUuid, senderPublicKey) else { return }
        if let existing = try MsgDao.shared.getConversationByUuid(senderUuid), !existing.peerPublicKey.isEmpty {
            acknowledge(packet)
            return
        }
        let initialNickname = (jsonObj["nickname"] as? String) ?? "Unknown"
        let initialAvatar = ""

        let request = FriendRequest(
            senderUuid: senderUuid,
            nickname: initialNickname,
            avatarPath: initialAvatar,
            peerPublicKey: senderPublicKey,
            timestamp: Int64(Date().timeIntervalSince1970 * 1000)
        )
        try MsgDao.shared.insertFriendRequest(request)

        // Fetch remote profile
        let getProfileDict = ["targetUuid": senderUuid]
        if let json = try? JSONSerialization.data(withJSONObject: getProfileDict) {
            let reqPacket = GhostPacket(
                type: GhostPacket.typeGetProfile,
                targetMailboxId: myMailboxId,
                nonceOrIv: Data(count: 16),
                encryptedData: json,
                authTag: Data(count: 16)
            )
            NWClient.shared.sendPacket(reqPacket)
        }

        acknowledge(packet)
    }

    private func handleProfileResponse(_ packet: GhostPacket) {
        guard let data = packet.encryptedData,
              let jsonObj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let uuid = jsonObj["uuid"] as? String else { return }

        guard uuid.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { return }
        let nickname = (jsonObj["nickname"] as? String) ?? "Unknown"
        var finalAvatarPath = ""
        let avatarData = (jsonObj["avatarData"] as? String) ?? ""

        if !avatarData.isEmpty, let bytes = Data(base64Encoded: avatarData) {
            let avatarFile = MediaStorageManager.shared.mediaDirectory.appendingPathComponent("avatar_\(uuid).jpg")
            try? bytes.write(to: avatarFile)
            finalAvatarPath = avatarFile.path
        }

        if var req = try? MsgDao.shared.getFriendRequestByUuid(uuid) {
            req.nickname = nickname
            req.avatarPath = finalAvatarPath
            try? MsgDao.shared.insertFriendRequest(req)
        }

        if var conv = try? MsgDao.shared.getConversationByUuid(uuid) {
            conv.alias = nickname
            conv.avatarPath = finalAvatarPath
            try? MsgDao.shared.insertConversation(conv)
        }
    }

    private func handleFriendAccepted(_ packet: GhostPacket) throws {
        guard let iv = packet.nonceOrIv,
              let cipherText = packet.encryptedData,
              let authTag = packet.authTag else { return }

        let myUuid = KeychainManager.shared.myUuid
        let myMailboxId = KeychainManager.shared.myMailboxId

        guard let decryptedBytes = try? CryptoManager.decryptPayloadFallback(
            myUuid: myUuid,
            targetUuid: myUuid,
            iv: iv,
            cipherText: cipherText,
            authTag: authTag
        ) else { return }

        guard let jsonObj = try? JSONSerialization.jsonObject(with: decryptedBytes) as? [String: Any],
              let senderUuid = jsonObj["senderUuid"] as? String,
              let senderPublicKey = jsonObj["senderPublicKey"] as? String else { return }

        guard validFriendIdentity(senderUuid, senderPublicKey) else { return }
        guard var conv = try MsgDao.shared.getConversationByUuid(senderUuid),
              conv.peerPublicKey.isEmpty || conv.peerPublicKey == senderPublicKey else {
            acknowledge(packet)
            return
        }
        conv.peerPublicKey = senderPublicKey
        try MsgDao.shared.insertConversation(conv)
        try MsgDao.shared.deleteFriendRequest(senderUuid)

        // Fetch remote profile
        let getProfileDict = ["targetUuid": senderUuid]
        if let json = try? JSONSerialization.data(withJSONObject: getProfileDict) {
            let reqPacket = GhostPacket(
                type: GhostPacket.typeGetProfile,
                targetMailboxId: myMailboxId,
                nonceOrIv: Data(count: 16),
                encryptedData: json,
                authTag: Data(count: 16)
            )
            NWClient.shared.sendPacket(reqPacket)
        }

        acknowledge(packet)
    }

    private func handleFriendDeleted(_ packet: GhostPacket) throws {
        guard let iv = packet.nonceOrIv,
              let cipherText = packet.encryptedData,
              let authTag = packet.authTag else { return }

        let myXPrivKey = KeychainManager.shared.x25519PrivateKey

        do {
            let friends = try MsgDao.shared.getAllConversations()
            for friend in friends where !friend.peerPublicKey.isEmpty {
                if let decryptedBytes = try? CryptoManager.decryptPayloadECDH(
                    myPrivateKey: myXPrivKey,
                    peerPublicKeyBase64: friend.peerPublicKey,
                    iv: iv,
                    cipherText: cipherText,
                    authTag: authTag
                ),
                let jsonObj = try? JSONSerialization.jsonObject(with: decryptedBytes) as? [String: Any],
                let senderUuid = jsonObj["senderUuid"] as? String, senderUuid == friend.uuid {
                    do {
                        let msgs = try MsgDao.shared.getMessageListByConversation(senderUuid)
                        for m in msgs where m.ext != "txt" {
                            MediaStorageManager.shared.deleteMediaFile(msgId: m.messageId, ext: m.ext)
                        }
                    }
                    try MsgDao.shared.deleteConversation(senderUuid)
                    try MsgDao.shared.clearMessages(conversationUuid: senderUuid)
                    break
                }
            }
        }

        acknowledge(packet)
    }

    private func handleRequestRevoked(_ packet: GhostPacket) throws {
        guard let iv = packet.nonceOrIv,
              let cipherText = packet.encryptedData,
              let authTag = packet.authTag else { return }

        let myUuid = KeychainManager.shared.myUuid

        if let decryptedBytes = try? CryptoManager.decryptPayloadFallback(
            myUuid: myUuid,
            targetUuid: myUuid,
            iv: iv,
            cipherText: cipherText,
            authTag: authTag
        ),
        let jsonObj = try? JSONSerialization.jsonObject(with: decryptedBytes) as? [String: Any],
        let senderUuid = jsonObj["senderUuid"] as? String {
            try MsgDao.shared.deleteFriendRequest(senderUuid)
        }

        acknowledge(packet)
    }

    private func handleAccountReset(_ packet: GhostPacket) throws {
        guard let iv = packet.nonceOrIv,
              let cipherText = packet.encryptedData,
              let authTag = packet.authTag else { return }

        let myXPrivKey = KeychainManager.shared.x25519PrivateKey

        do {
            let friends = try MsgDao.shared.getAllConversations()
            for friend in friends where !friend.peerPublicKey.isEmpty {
                if let decryptedBytes = try? CryptoManager.decryptPayloadECDH(
                    myPrivateKey: myXPrivKey,
                    peerPublicKeyBase64: friend.peerPublicKey,
                    iv: iv,
                    cipherText: cipherText,
                    authTag: authTag
                ),
                let jsonObj = try? JSONSerialization.jsonObject(with: decryptedBytes) as? [String: Any],
                let senderUuid = jsonObj["senderUuid"] as? String, senderUuid == friend.uuid {
                    var updated = friend
                    updated.alias += " [Identity deleted]"
                    updated.peerPublicKey = "" // block sending
                    try MsgDao.shared.insertConversation(updated)
                    break
                }
            }
        }

        acknowledge(packet)
    }

    private func handleCallSignaling(_ packet: GhostPacket) throws {
        guard let plain = try decryptPeer(packet),
              let json = try JSONSerialization.jsonObject(with: plain) as? [String: Any],
              let sender = json["senderUuid"] as? String else { return }
        CallSignalingHandler.shared.handleIncomingSignaling(type: packet.type, senderUuid: sender, json: json)
    }

    // MARK: - Local Notification

    private func postLocalNotification(senderUuid: String, ext: String, content: String) {
        // Match Android behavior: suppress system notifications while the app is active.
        let active = Thread.isMainThread ? NSApp.isActive : DispatchQueue.main.sync { NSApp.isActive }
        if active { return }

        let conv = try? MsgDao.shared.getConversationByUuid(senderUuid)
        let title = conv?.alias ?? String(senderUuid.prefix(8))
        let body = ext == "txt" ? content : "[\(ext.uppercased())]"

        let contentObj = UNMutableNotificationContent()
        contentObj.title = title
        contentObj.body = body
        contentObj.sound = .default

        let request = UNNotificationRequest(identifier: UUID().uuidString, content: contentObj, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
