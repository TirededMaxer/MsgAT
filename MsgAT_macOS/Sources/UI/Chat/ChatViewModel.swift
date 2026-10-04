import Foundation
import AppKit
import Combine
import SwiftUI

@MainActor
public final class ChatViewModel: ObservableObject {
    public let targetUuid: String

    @Published public var conversation: Conversation?
    @Published public var messages: [ChatMessage] = []
    @Published public var replyAuthors: [String: String] = [:]

    @Published public var currentLimit: Int = 50
    @Published public var hasMoreOlderMessages: Bool = true

    @Published public private(set) var isViewingLatest = false
    public var isMediaPresented = false
    public var isChatMounted = false
    private var isContentVisible: Bool {
        guard isChatMounted, !isMediaPresented, NSApp.isActive,
              let window = NSApp.keyWindow, window === NotificationHelper.mainWindow,
              window.isVisible, !window.isMiniaturized, window.attachedSheet == nil,
              NSApp.modalWindow == nil else { return false }
        return CallManager.shared.callState == .idle || CallManager.shared.isMinimized
    }
    private var cancellables = Set<AnyCancellable>()

    public init(targetUuid: String) {
        self.targetUuid = targetUuid

        loadConversation()
        loadMessages()

        CallManager.shared.$callState.combineLatest(CallManager.shared.$isMinimized)
            .receive(on: RunLoop.main).sink { [weak self] _ in self?.loadMessages() }.store(in: &cancellables)

        NotificationCenter.default.publisher(for: MsgDao.didChangeNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.loadConversation(); self?.loadMessages() }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.loadMessages() }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: NSWindow.didBecomeKeyNotification)
            .merge(with: NotificationCenter.default.publisher(for: NSWindow.didEndSheetNotification))
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.loadMessages() }
            .store(in: &cancellables)
    }

    public func loadConversation() {
        self.conversation = try? MsgDao.shared.getConversationByUuid(targetUuid)
    }

    public func loadMessages() {
        if let msgs = try? MsgDao.shared.getMessages(conversationUuid: targetUuid, limit: currentLimit) {
            var authors: [String: String] = [:]
            let myUuid = KeychainManager.shared.myUuid
            for message in msgs where message.replyToId != nil {
                let original = try? MsgDao.shared.getMessageById(message.replyToId!)
                let author: String?
                if let original, original.conversationUuid == targetUuid {
                    author = original.isMine ? myUuid : targetUuid
                } else {
                    author = message.replyToSender
                }
                authors[message.messageId] = author == myUuid ? "Me" :
                    (author == targetUuid ? conversation?.alias ?? "User" : "User")
            }
            if replyAuthors != authors { replyAuthors = authors }
            if self.messages != msgs {
                self.messages = msgs
            }
            self.hasMoreOlderMessages = (msgs.count >= currentLimit)

            markAsRead()
        }
    }

    public func loadMoreMessages() {
        currentLimit += 50
        loadMessages()
    }

    public func revealMessage(_ id: String) -> Bool {
        guard let target = try? MsgDao.shared.getMessageById(id), target.conversationUuid == targetUuid else { return false }
        while !messages.contains(where: { $0.messageId == id }) && hasMoreOlderMessages { loadMoreMessages() }
        return messages.contains(where: { $0.messageId == id })
    }

    public func setViewingLatest(_ visible: Bool) {
        isViewingLatest = visible
        markAsRead()
    }

    public func markAsRead() {
        guard isViewingLatest, isContentVisible else { return }
        guard let last = try? MsgDao.shared.markConversationRead(conversationUuid: targetUuid) else { return }
        sendReadAck(messageId: last.messageId)
    }

    public func sendMessage(
        text: String,
        replyTo: ChatMessage? = nil,
        onFail: (() -> Void)? = nil
    ) {
        let trimmed = text
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }

        let myUuid = KeychainManager.shared.myUuid
        let myPrivKey = KeychainManager.shared.x25519PrivateKey
        let peerPubKey = conversation?.peerPublicKey ?? ""
        guard !peerPubKey.isEmpty else {
            onFail?()
            return
        }

        let msgId = UUID().uuidString
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)

        let chatMsg = ChatMessage(
            messageId: msgId,
            conversationUuid: targetUuid,
            isMine: true,
            content: trimmed,
            ext: "txt",
            timestamp: timestamp,
            isRead: true,
            replyToId: replyTo?.messageId,
            replyToSender: replyTo != nil ? (replyTo!.isMine ? KeychainManager.shared.myUuid : targetUuid) : nil,
            replyToContent: replyTo != nil ? (replyTo!.ext == "txt" ? replyTo!.content : "[\(replyTo!.ext.uppercased())]") : nil,
            status: 0
        )
        try? MsgDao.shared.insertMessage(chatMsg)
        loadMessages()

        Task.detached {
            let base64Data = trimmed.data(using: .utf8)?.base64EncodedString() ?? ""
            let payload = MessagePayload(
                senderUuid: myUuid,
                fileId: msgId,
                total: 1,
                index: 0,
                ext: "txt",
                data: base64Data,
                timestamp: timestamp,
                replyToId: chatMsg.replyToId,
                replyToSender: chatMsg.replyToSender,
                replyToContent: chatMsg.replyToContent
            )

            guard let jsonStr = payload.toJsonString(), let jsonBytes = jsonStr.data(using: .utf8) else { return }

            var ivBytes = [UInt8](repeating: 0, count: 16)
            _ = SecRandomCopyBytes(kSecRandomDefault, 16, &ivBytes)
            let iv = Data(ivBytes)

            guard let (cipher, tag) = try? CryptoManager.encryptPayloadECDH(
                myPrivateKey: myPrivKey,
                peerPublicKeyBase64: peerPubKey,
                iv: iv,
                plaintext: jsonBytes
            ) else { return }

            let targetMailbox = CryptoManager.calculateMailboxId(uuid: self.targetUuid)
            let packet = GhostPacket(
                type: GhostPacket.typeMessage,
                targetMailboxId: targetMailbox,
                nonceOrIv: iv,
                encryptedData: cipher,
                authTag: tag
            )

            if NWClient.shared.sendPacket(packet) {
                try? MsgDao.shared.updateMessageStatus(messageId: msgId, status: 1)
            }
        }
    }

    public func sendMedia(
        sourceUrl: URL,
        replyTo: ChatMessage? = nil,
        onFail: (() -> Void)? = nil
    ) {
        let myUuid = KeychainManager.shared.myUuid
        let myPrivKey = KeychainManager.shared.x25519PrivateKey
        let peerPubKey = conversation?.peerPublicKey ?? ""
        guard !peerPubKey.isEmpty else {
            onFail?()
            return
        }

        let ext = sourceUrl.pathExtension.isEmpty ? "bin" : sourceUrl.pathExtension.lowercased()
        let msgId = UUID().uuidString
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)

        let savedUrl: URL
        do { savedUrl = try MediaStorageManager.shared.saveMediaFromUrl(msgId: msgId, ext: ext, sourceUrl: sourceUrl) }
        catch let error as CocoaError where error.code == .fileReadTooLarge {
            NoticeCenter.shared.show("Files cannot exceed 100 MB."); return
        }
        catch { onFail?(); return }

        let chatMsg = ChatMessage(
            messageId: msgId,
            conversationUuid: targetUuid,
            isMine: true,
            content: savedUrl.path,
            ext: ext,
            timestamp: timestamp,
            isRead: true,
            replyToId: replyTo?.messageId,
            replyToSender: replyTo != nil ? (replyTo!.isMine ? KeychainManager.shared.myUuid : targetUuid) : nil,
            replyToContent: replyTo != nil ? (replyTo!.ext == "txt" ? replyTo!.content : "[\(replyTo!.ext.uppercased())]") : nil,
            status: 0
        )
        try? MsgDao.shared.insertMessage(chatMsg)
        loadMessages()

        Task.detached {
            guard let bytes = try? Data(contentsOf: savedUrl) else { return }

            guard let chunkList = try? MediaStorageManager.shared.base64Chunks(bytes: bytes) else { return }
            let total = chunkList.count
            var allSent = true

            for index in 0..<total {
                let chunk = chunkList[index]
                let payload = MessagePayload(
                    senderUuid: myUuid,
                    fileId: msgId,
                    total: total,
                    index: index,
                    ext: ext,
                    data: chunk,
                    timestamp: timestamp,
                    replyToId: chatMsg.replyToId,
                    replyToSender: chatMsg.replyToSender,
                    replyToContent: chatMsg.replyToContent
                )

                guard let jsonStr = payload.toJsonString(), let jsonBytes = jsonStr.data(using: .utf8) else { continue }

                var ivBytes = [UInt8](repeating: 0, count: 16)
                _ = SecRandomCopyBytes(kSecRandomDefault, 16, &ivBytes)
                let iv = Data(ivBytes)

                guard let (cipher, tag) = try? CryptoManager.encryptPayloadECDH(
                    myPrivateKey: myPrivKey,
                    peerPublicKeyBase64: peerPubKey,
                    iv: iv,
                    plaintext: jsonBytes
                ) else {
                    allSent = false
                    break
                }

                let targetMailbox = CryptoManager.calculateMailboxId(uuid: self.targetUuid)
                let packet = GhostPacket(
                    type: GhostPacket.typeMessage,
                    targetMailboxId: targetMailbox,
                    nonceOrIv: iv,
                    encryptedData: cipher,
                    authTag: tag
                )

                let success = NWClient.shared.sendPacket(packet)
                if !success {
                    allSent = false
                    break
                }

                if index < total - 1 {
                    try? await Task.sleep(nanoseconds: 50_000_000)
                }
            }

            if allSent {
                try? MsgDao.shared.updateMessageStatus(messageId: msgId, status: 1)
            }
        }
    }

    public func sendReadAck(messageId: String) {
        let myUuid = KeychainManager.shared.myUuid
        let myPrivKey = KeychainManager.shared.x25519PrivateKey
        let peerPubKey = conversation?.peerPublicKey ?? ""
        guard !peerPubKey.isEmpty else { return }

        Task.detached {
            let payload = MessagePayload(
                senderUuid: myUuid,
                fileId: UUID().uuidString,
                total: 1,
                index: 0,
                ext: "ack_read",
                data: messageId,
                timestamp: Int64(Date().timeIntervalSince1970 * 1000)
            )
            guard let jsonStr = payload.toJsonString(), let jsonBytes = jsonStr.data(using: .utf8) else { return }

            var ivBytes = [UInt8](repeating: 0, count: 16)
            _ = SecRandomCopyBytes(kSecRandomDefault, 16, &ivBytes)
            let iv = Data(ivBytes)

            guard let (cipher, tag) = try? CryptoManager.encryptPayloadECDH(
                myPrivateKey: myPrivKey,
                peerPublicKeyBase64: peerPubKey,
                iv: iv,
                plaintext: jsonBytes
            ) else { return }

            let targetMailbox = CryptoManager.calculateMailboxId(uuid: self.targetUuid)
            let packet = GhostPacket(
                type: GhostPacket.typeMessage,
                targetMailboxId: targetMailbox,
                nonceOrIv: iv,
                encryptedData: cipher,
                authTag: tag
            )
            NWClient.shared.sendPacket(packet)
        }
    }

    public func revokeMessage(messageId: String) {
        sendMessageAction(messageId: messageId, action: "revoke", text: "")
    }

    public func editMessage(messageId: String, newContent: String) {
        guard !newContent.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        sendMessageAction(messageId: messageId, action: "edit", text: newContent)
    }

    private func sendMessageAction(messageId: String, action: String, text: String) {
        let peerUuid = targetUuid
        Task.detached {
            do {
                guard let target = try MsgDao.shared.getMessageById(messageId), target.isMine,
                      target.conversationUuid == peerUuid, target.isRevoked == 0,
                      let peer = try MsgDao.shared.getConversationByUuid(peerUuid), !peer.peerPublicKey.isEmpty else { return }
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                guard target.timestamp <= now, now - target.timestamp <= 120000,
                      action != "edit" || target.ext == "txt" else { return }
                let payload = MessagePayload(senderUuid: KeychainManager.shared.myUuid, fileId: UUID().uuidString,
                    total: 1, index: 0, ext: "txt", data: action == "edit" ? Data(text.utf8).base64EncodedString() : "",
                    timestamp: now, action: action, targetMessageId: messageId)
                var bytes = [UInt8](repeating: 0, count: 16)
                guard SecRandomCopyBytes(kSecRandomDefault, 16, &bytes) == errSecSuccess else { throw CryptoError.encryptionFailed }
                let iv = Data(bytes)
                let (cipher, tag) = try CryptoManager.encryptPayloadECDH(myPrivateKey: KeychainManager.shared.x25519PrivateKey,
                    peerPublicKeyBase64: peer.peerPublicKey, iv: iv, plaintext: JSONEncoder().encode(payload))
                guard NWClient.shared.sendPacket(GhostPacket(type: GhostPacket.typeMessage,
                    targetMailboxId: CryptoManager.calculateMailboxId(uuid: peerUuid), nonceOrIv: iv,
                    encryptedData: cipher, authTag: tag)) else { throw CocoaError(.fileWriteUnknown) }
                if action == "edit" { try MsgDao.shared.updateMessageContent(messageId: messageId, newContent: text) }
                else {
                    try MsgDao.shared.updateMessageRevoked(messageId: messageId)
                    if target.ext != "txt" { MediaStorageManager.shared.deleteMediaFile(msgId: messageId, ext: target.ext) }
                }
            } catch {
                await MainActor.run {
                    NoticeCenter.shared.show("The message change could not be sent.")
                }
            }
        }
    }
}
