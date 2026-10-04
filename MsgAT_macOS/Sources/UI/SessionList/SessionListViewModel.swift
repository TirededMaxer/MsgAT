import Foundation
import AppKit
import Combine
import SwiftUI

@MainActor
public final class SessionListViewModel: ObservableObject {
    @Published public var conversations: [ConversationWithUnread] = []
    @Published public var friendRequests: [FriendRequest] = []
    @Published public var syncState: SyncState = .disconnected
    @Published public var resetErrorMessage: String? = nil
    @Published public var showIdentityDialog: Bool = false

    public var currentIp: String { KeychainManager.shared.serverIp ?? "" }
    public var currentPort: String { KeychainManager.shared.serverPort > 0 ? "\(KeychainManager.shared.serverPort)" : "" }
    public var currentNickname: String { KeychainManager.shared.myNickname ?? "" }
    public var currentUuid: String { KeychainManager.shared.myUuid }
    public var currentTurnServer: String { KeychainManager.shared.turnServer ?? "" }
    public var currentTurnPort: String { KeychainManager.shared.turnPort > 0 ? "\(KeychainManager.shared.turnPort)" : "3478" }
    public var currentTurnUsername: String { KeychainManager.shared.turnUsername ?? "" }
    public var currentTurnPassword: String { KeychainManager.shared.turnPassword ?? "" }
    public var currentTurnUseTls: Bool { KeychainManager.shared.turnUseTls }
    public var isIdentityMissing: Bool { (KeychainManager.shared.myNickname?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true) || (KeychainManager.shared.myAvatarPath?.isEmpty ?? true) }

    private var cancellables = Set<AnyCancellable>()

    public init() {
        NWClient.shared.$connectionState
            .receive(on: DispatchQueue.main)
            .assign(to: &$syncState)

        loadData()

        if isIdentityMissing {
            showIdentityDialog = true
        }

        // Refresh timer for database polling
        NotificationCenter.default.publisher(for: MsgDao.didChangeNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.loadData()
            }
            .store(in: &cancellables)
    }

    public func loadData() {
        if let list = try? MsgDao.shared.getAllConversationsWithUnread() {
            self.conversations = list
        }
        if let reqs = try? MsgDao.shared.getAllFriendRequests() {
            self.friendRequests = reqs
        }
    }

    public func addConversation(rawUuid: String) {
        guard syncState == .synced else { return }
        let uuid = rawUuid.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard uuid.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              uuid != currentUuid, (try? MsgDao.shared.getConversationByUuid(uuid)) == nil else { return }

        let conv = Conversation(uuid: uuid, alias: "Unknown")
        try? MsgDao.shared.insertConversation(conv)

        let myUuid = KeychainManager.shared.myUuid
        let myNickname = KeychainManager.shared.myNickname ?? "Unknown"
        let myPub = KeychainManager.shared.myX25519PublicKeyBase64

        let dict = [
            "senderUuid": myUuid,
            "nickname": myNickname,
            "senderPublicKey": myPub
        ]

        guard let json = try? JSONSerialization.data(withJSONObject: dict) else { return }

        var ivBytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, 16, &ivBytes)
        let iv = Data(ivBytes)

        guard let (cipher, tag) = try? CryptoManager.encryptPayloadFallback(
            myUuid: uuid,
            targetUuid: uuid,
            iv: iv,
            plaintext: json
        ) else { return }

        let targetMailbox = CryptoManager.calculateMailboxId(uuid: uuid)
        let packet = GhostPacket(
            type: GhostPacket.typeSearchUser,
            targetMailboxId: targetMailbox,
            nonceOrIv: iv,
            encryptedData: cipher,
            authTag: tag
        )
        if !NWClient.shared.sendPacket(packet) { try? MsgDao.shared.deleteConversation(uuid) }
        loadData()
    }

    public func acceptFriendRequest(senderUuid: String, nickname: String, avatarPath: String) {
        guard syncState == .synced else { return }
        guard let req = try? MsgDao.shared.getFriendRequestByUuid(senderUuid), !req.peerPublicKey.isEmpty else { return }
        let conv = Conversation(uuid: senderUuid, alias: nickname, avatarPath: avatarPath, peerPublicKey: req.peerPublicKey)

        let myUuid = KeychainManager.shared.myUuid
        let myPub = KeychainManager.shared.myX25519PublicKeyBase64
        let dict = ["senderUuid": myUuid, "senderPublicKey": myPub]

        guard let json = try? JSONSerialization.data(withJSONObject: dict) else { return }

        var ivBytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, 16, &ivBytes)
        let iv = Data(ivBytes)

        guard let (cipher, tag) = try? CryptoManager.encryptPayloadFallback(
            myUuid: senderUuid,
            targetUuid: senderUuid,
            iv: iv,
            plaintext: json
        ) else { return }

        let targetMailbox = CryptoManager.calculateMailboxId(uuid: senderUuid)
        let packet = GhostPacket(
            type: GhostPacket.typeFriendAccepted,
            targetMailboxId: targetMailbox,
            nonceOrIv: iv,
            encryptedData: cipher,
            authTag: tag
        )
        guard NWClient.shared.sendPacket(packet) else { return }
        do {
            try MsgDao.shared.insertConversation(conv)
            try MsgDao.shared.deleteFriendRequest(senderUuid)
        } catch { resetErrorMessage = error.localizedDescription }
        loadData()
    }

    public func declineFriendRequest(senderUuid: String) {
        try? MsgDao.shared.deleteFriendRequest(senderUuid)
        loadData()
    }

    public func deleteConversation(uuid: String) {
        if let convo = try? MsgDao.shared.getConversationByUuid(uuid) {
            let targetMailbox = CryptoManager.calculateMailboxId(uuid: uuid)
            var ivBytes = [UInt8](repeating: 0, count: 16)
            _ = SecRandomCopyBytes(kSecRandomDefault, 16, &ivBytes)
            let iv = Data(ivBytes)

            let myUuid = KeychainManager.shared.myUuid
            let dict = ["senderUuid": myUuid]
            if let json = try? JSONSerialization.data(withJSONObject: dict) {
                if !convo.peerPublicKey.isEmpty {
                    let myPriv = KeychainManager.shared.x25519PrivateKey
                    if let (cipher, tag) = try? CryptoManager.encryptPayloadECDH(
                        myPrivateKey: myPriv,
                        peerPublicKeyBase64: convo.peerPublicKey,
                        iv: iv,
                        plaintext: json
                    ) {
                        let packet = GhostPacket(
                            type: GhostPacket.typeFriendDeleted,
                            targetMailboxId: targetMailbox,
                            nonceOrIv: iv,
                            encryptedData: cipher,
                            authTag: tag
                        )
                        NWClient.shared.sendPacket(packet)
                    }
                } else {
                    if let (cipher, tag) = try? CryptoManager.encryptPayloadFallback(
                        myUuid: uuid,
                        targetUuid: uuid,
                        iv: iv,
                        plaintext: json
                    ) {
                        let packet = GhostPacket(
                            type: GhostPacket.typeRequestRevoked,
                            targetMailboxId: targetMailbox,
                            nonceOrIv: iv,
                            encryptedData: cipher,
                            authTag: tag
                        )
                        NWClient.shared.sendPacket(packet)
                    }
                }
            }
        }

        if let msgs = try? MsgDao.shared.getMessageListByConversation(uuid) {
            for m in msgs where m.ext != "txt" {
                MediaStorageManager.shared.deleteMediaFile(msgId: m.messageId, ext: m.ext)
            }
        }
        try? MsgDao.shared.deleteConversation(uuid)
        try? MsgDao.shared.clearMessages(conversationUuid: uuid)
        loadData()
    }

    public func clearMessages(uuid: String) {
        if let msgs = try? MsgDao.shared.getMessageListByConversation(uuid) {
            for m in msgs where m.ext != "txt" {
                MediaStorageManager.shared.deleteMediaFile(msgId: m.messageId, ext: m.ext)
            }
        }
        try? MsgDao.shared.clearMessages(conversationUuid: uuid)
        loadData()
    }

    public func resetIdentity(onFail: (() -> Void)? = nil) {
        guard NWClient.shared.connectionState == .synced else {
            resetErrorMessage = "Must be online to reset"
            onFail?()
            return
        }

        Task {
            let friends = (try? MsgDao.shared.getAllConversations()) ?? []
            let myUuid = KeychainManager.shared.myUuid
            let myPriv = KeychainManager.shared.x25519PrivateKey
            let dict = ["senderUuid": myUuid]

            if let json = try? JSONSerialization.data(withJSONObject: dict) {
                for friend in friends where !friend.peerPublicKey.isEmpty {
                    var ivBytes = [UInt8](repeating: 0, count: 16)
                    _ = SecRandomCopyBytes(kSecRandomDefault, 16, &ivBytes)
                    let iv = Data(ivBytes)

                    if let (cipher, tag) = try? CryptoManager.encryptPayloadECDH(
                        myPrivateKey: myPriv,
                        peerPublicKeyBase64: friend.peerPublicKey,
                        iv: iv,
                        plaintext: json
                    ) {
                        let targetMailbox = CryptoManager.calculateMailboxId(uuid: friend.uuid)
                        let packet = GhostPacket(
                            type: GhostPacket.typeAccountReset,
                            targetMailboxId: targetMailbox,
                            nonceOrIv: iv,
                            encryptedData: cipher,
                            authTag: tag
                        )
                        NWClient.shared.sendPacket(packet)
                    }
                }
            }

            guard await NWClient.shared.deleteAccount() else {
                resetErrorMessage = "The relay did not confirm account deletion. Identity was retained."
                onFail?()
                return
            }
            NWClient.shared.disconnect()
            CallManager.shared.endCall()
            do { try AppDatabase.shared.resetIdentityAndDatabase() }
            catch {
                resetErrorMessage = error.localizedDescription
                onFail?()
                return
            }
            MediaStorageManager.shared.deleteAllMedia()

            // Reset memory state & trigger welcome setup dialog
            await MainActor.run {
                self.conversations = []
                self.friendRequests = []
                self.loadData()

                NWClient.shared.disconnect()
                if KeychainManager.shared.isConfigured {
                    NWClient.shared.connect()
                }

                self.showIdentityDialog = true
            }
        }
    }

    public func saveSettings(settings: ServerSettings) {
        let trimmedIp = settings.ip.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedTurnServer = settings.turnServer.trimmingCharacters(in: .whitespacesAndNewlines)

        KeychainManager.shared.serverCertificateSha256 = settings.serverCertificateSha256
        KeychainManager.shared.allowDevelopmentCertificate = settings.allowDevelopmentCertificate
        KeychainManager.shared.serverIp = trimmedIp
        KeychainManager.shared.serverPort = Int(settings.port) ?? 0
        KeychainManager.shared.turnServer = trimmedTurnServer.isEmpty ? nil : trimmedTurnServer
        KeychainManager.shared.turnPort = Int(settings.turnPort) ?? 3478
        let turnUsername = settings.turnUsername.trimmingCharacters(in: .whitespacesAndNewlines)
        KeychainManager.shared.turnUsername = turnUsername.isEmpty ? nil : turnUsername
        KeychainManager.shared.turnPassword = settings.turnPassword.isEmpty ? nil : settings.turnPassword
        KeychainManager.shared.turnUseTls = settings.turnUseTls

        broadcastProfileUpdate()
        NWClient.shared.disconnect()
        NWClient.shared.connect()
        objectWillChange.send()
    }

    @discardableResult public func saveIdentity(nickname: String, avatarUrl: URL? = nil) -> Bool {
        if let url = avatarUrl {
            guard updateMyAvatar(from: url, broadcast: false) else { return false }
            if url.lastPathComponent.hasPrefix("msgat-avatar-") { try? FileManager.default.removeItem(at: url) }
        }
        KeychainManager.shared.myNickname = nickname
        broadcastProfileUpdate()
        objectWillChange.send()
        return true
    }

    @discardableResult public func updateMyAvatar(from sourceUrl: URL, broadcast: Bool = true) -> Bool {
        let avatarTarget = MediaStorageManager.shared.mediaDirectory.appendingPathComponent("avatar_self.jpg")
        guard let thumbnail = MediaStorageManager.shared.generateImageThumbnail(fileUrl: sourceUrl, maxDimension: 256),
              let tiff = thumbnail.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff),
              let jpeg = bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.8]) else {
            NoticeCenter.shared.show("Failed to save"); return false
        }
        do { try jpeg.write(to: avatarTarget, options: .atomic) }
        catch { NoticeCenter.shared.show("Failed to save"); return false }
        KeychainManager.shared.myAvatarPath = avatarTarget.path
        if broadcast { broadcastProfileUpdate() }
        objectWillChange.send()
        return true
    }

    public func broadcastProfileUpdate() {
        guard NWClient.shared.connectionState == .synced || NWClient.shared.connectionState == .syncing else { return }

        let myUuid = KeychainManager.shared.myUuid
        let myNickname = KeychainManager.shared.myNickname ?? "Unknown"
        let myAvatarPath = KeychainManager.shared.myAvatarPath ?? ""
        let myMailbox = KeychainManager.shared.myMailboxId

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
                targetMailboxId: myMailbox,
                nonceOrIv: Data(count: 16),
                encryptedData: json,
                authTag: Data(count: 16)
            )
            NWClient.shared.sendPacket(updatePacket)
        }
    }
}
