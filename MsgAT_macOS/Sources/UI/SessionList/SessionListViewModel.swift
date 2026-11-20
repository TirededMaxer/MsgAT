import Foundation
import AppKit
import Combine
import SwiftUI

@MainActor
public final class SessionListViewModel: ObservableObject {
    @Published public var conversations: [ConversationWithUnread] = []
    @Published var folders=[ConversationFolder]()
    @Published var folderFilter="all"
    @Published private(set) var clock = Date()
    struct FolderTab: Identifiable {
        let id: String
        let title: String
        let unreadCount: Int
    }

    private func includes(_ row: ConversationWithUnread, in id: String, folder: ConversationFolder?) -> Bool {
        if let folder {
            return (folder.includeAll || row.folderIDs.split(separator: ",").contains(Substring(id))) &&
                (folder.includeArchived || !row.archived) &&
                (!folder.unreadOnly || row.hasUnread) &&
                (!folder.excludeMuted || !row.muted)
        }
        switch id {
        case "archive": return row.archived
        case "unread": return !row.archived && row.hasUnread
        default: return !row.archived
        }
    }

    func folderTabs(all: String, unreadTitle: String, archive: String) -> [FolderTab] {
        let unread = conversations.filter(\.hasUnread)
        var standard = [("all", all), ("unread", unreadTitle)]
        if folderFilter == "archive" { standard.append(("archive", archive)) }
        return standard.map { id, title in
            FolderTab(id: id, title: title, unreadCount: unread.filter { includes($0, in: id, folder: nil) }.count)
        } + folders.map { folder in
            FolderTab(id: folder.id, title: folder.name, unreadCount: unread.filter {
                includes($0, in: folder.id, folder: folder)
            }.count)
        }
    }

    var hasArchivedConversations: Bool { conversations.contains { $0.archived } }
    var archivedUnreadCount: Int { conversations.lazy.filter { $0.archived && $0.hasUnread }.count }

    var customFolderID: String? { folders.first { $0.id == folderFilter }?.id }

    var visibleConversations: [ConversationWithUnread] {
        let folder = folders.first { $0.id == folderFilter }
        let visible = conversations.filter { includes($0, in: folderFilter, folder: folder) }
        guard let folder else { return visible }
        return visible.map { row in
            var displayRow = row
            displayRow.pinOrder = row.folderPinOrders[folder.id, default: 0]
            return displayRow
        }.sorted { left, right in
            if left.pinOrder != right.pinOrder { return left.pinOrder > right.pinOrder }
            if left.activityAt != right.activityAt { return left.activityAt > right.activityAt }
            return left.conversation.uuid < right.conversation.uuid
        }
    }

    func conversationAction(_ peer: String, _ action: String, until: Int64? = nil, folderID: String? = nil) {
        if action == "read" {
            SecureMessaging.shared.markAllRead(peer)
        } else {
            SecureMessaging.shared.changeConversation(peer, action: action, until: until, folderID: folderID)
        }
    }
    func movePin(scope: String, peer: String, target: String, before: Bool) {
        SecureMessaging.shared.moveConversationPin(scope: scope, peer: peer, target: target, before: before) { moved in
            if !moved { Task { @MainActor in NoticeCenter.shared.show("Pinned chats could not be reordered") } }
        }
    }

    func saveFolder(_ folder:ConversationFolder,peers:[String])async->Bool{await withCheckedContinuation{continuation in SecureMessaging.shared.saveConversationFolder(folder,peers:peers){continuation.resume(returning:$0)}}}
    func deleteFolder(_ id:String)async->Bool{let ok:Bool=await withCheckedContinuation{continuation in SecureMessaging.shared.deleteConversationFolder(id){continuation.resume(returning:$0)}};if ok,folderFilter==id{folderFilter="all"};return ok}
    func moveFolder(_ id: String, targetID: String, before: Bool) async -> Bool {
        await withCheckedContinuation { continuation in
            SecureMessaging.shared.moveConversationFolder(id, targetID: targetID, before: before) {
                continuation.resume(returning: $0)
            }
        }
    }

    @Published public var friendRequests: [FriendRequest] = []
    @Published public var syncState: SyncState = .disconnected
    @Published public var resetErrorMessage: String? = nil
    private var resettingIdentity=false
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
    public var isIdentityMissing: Bool { (KeychainManager.shared.myNickname?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true) }

    private var refreshTask:Task<Void,Never>?
    private func scheduleRefresh(){guard refreshTask==nil else{return};refreshTask=Task { @MainActor [weak self] in
        do{try await Task.sleep(nanoseconds:16_000_000)}catch{return};guard let self else{return};self.loadData();self.refreshTask=nil
    }}
    deinit{refreshTask?.cancel()}
    private var cancellables = Set<AnyCancellable>()

    public init() {
        NWClient.shared.$connectionState
            .receive(on: DispatchQueue.main)
            .assign(to: &$syncState)

        Timer.publish(every:60,on:.main,in:.common).autoconnect().sink { [weak self] now in self?.clock = now }.store(in: &cancellables)
        loadData()

        Task {
            do {
                let oldName=KeychainManager.shared.legacyNickname
                let oldPath=KeychainManager.shared.legacyAvatarPath
                let profile=try await EncryptedMediaWork.run {try UserProfileStore.own()}
                KeychainManager.shared.installProfile(profile)
                if profile==nil,let oldName {
                    saveIdentity(nickname:oldName,avatarUrl:oldPath.map {URL(fileURLWithPath:$0)}) {saved in
                        if saved,let oldPath {
                            let old=URL(fileURLWithPath:oldPath).standardizedFileURL
                            if old.deletingLastPathComponent()==MediaStorageManager.shared.mediaDirectory.standardizedFileURL,old.lastPathComponent=="avatar_self.jpg" {try? FileManager.default.removeItem(at:old)}
                        }
                        self.showIdentityDialog = !saved || self.isIdentityMissing
                    }
                } else {showIdentityDialog=isIdentityMissing}
                objectWillChange.send()
            } catch {NoticeCenter.shared.show("Profile could not be loaded")}
        }

        NotificationCenter.default.publisher(for: MsgDao.didChangeNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.scheduleRefresh()
            }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: MsgDao.visibleMessagesReadNotification)
            .compactMap { $0.object as? VisibleMessagesRead }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] change in self?.refreshUnreadState(peer: change.peer) }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: MsgDao.deliveryDidChangeNotification)
            .compactMap { $0.object as? MessageDeliveryChange }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] change in self?.refreshReceipt(change) }
            .store(in: &cancellables)
    }

    private func refreshReceipt(_ change: MessageDeliveryChange) {
        guard let index = conversations.firstIndex(where: {
            $0.conversation.uuid == change.peer && $0.lastID == change.messageID
        }) else { return }
        do {
            guard let message = try MsgDao.shared.getMessageById(change.messageID),
                  message.conversationUuid == change.peer, message.isMine, message.isRevoked == 0,
                  (-1...3).contains(message.status) else { scheduleRefresh(); return }
            if conversations[index].lastReceiptStatus != message.status {
                conversations[index].lastReceiptStatus = message.status
            }
        } catch {
            scheduleRefresh()
        }
    }

    public func loadData() {
        if let groups = try? ConversationIndexStore.folders() {
            if folders != groups { folders = groups }
            if !["all", "archive", "unread"].contains(folderFilter),
               !groups.contains(where: { $0.id == folderFilter }) { folderFilter = "all" }
        }
        loadConversations()
        if let reqs = try? MsgDao.shared.getAllFriendRequests() {
            self.friendRequests = reqs
        }
    }

    private func loadConversations() {
        if let list = try? MsgDao.shared.getAllConversationsWithUnread(), conversations != list {
            conversations = list
        }
    }

    private func refreshUnreadState(peer: String) {
        guard let index = conversations.firstIndex(where: { $0.conversation.uuid == peer }),
              let state = try? MsgDao.shared.unreadState(peer: peer) else {
            scheduleRefresh()
            return
        }
        var row = conversations[index]
        row.unreadCount = state.count
        row.firstUnreadID = state.firstID
        row.manualUnread = state.marked
        if row != conversations[index] { conversations[index] = row }
    }
    public func addConversation(rawUuid: String) {
        guard syncState == .synced else { return }
        let uuid = rawUuid.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard uuid.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              uuid != currentUuid, (try? MsgDao.shared.getConversationByUuid(uuid)) == nil else { return }

        SecureMessaging.shared.requestContact(uuid)
    }

    public func acceptFriendRequest(senderUuid: String, completion: @escaping @Sendable (Bool) -> Void) {
        SecureMessaging.shared.acceptContact(senderUuid, completion: completion)
    }

    public func declineFriendRequest(senderUuid: String, completion: @escaping @Sendable (Bool) -> Void) {
        SecureMessaging.shared.declineContact(senderUuid, completion: completion)
    }

    public func deleteConversation(uuid: String) async -> Bool {
        let oldFiles: [(id: String, ext: String)]? = try? await EncryptedMediaWork.run {
            try MsgDao.shared.getMessageListByConversation(uuid).compactMap { message in
                message.ext == "txt" ? nil : (id: message.messageId, ext: message.ext)
            }
        }
        let deleted: Bool = await withCheckedContinuation { continuation in
            SecureMessaging.shared.deleteContact(uuid) { continuation.resume(returning: $0) }
        }
        if deleted, let oldFiles, !oldFiles.isEmpty {
            try? await EncryptedMediaWork.run {
                for file in oldFiles {
                    MediaStorageManager.shared.deleteMediaFile(msgId: file.id, ext: file.ext)
                }
            }
        }
        return deleted
    }

    public func clearMessages(uuid: String) async -> Bool {
        await withCheckedContinuation { continuation in
            SecureMessaging.shared.clearMessages(uuid) { continuation.resume(returning: $0) }
        }
    }

    public func resetIdentity(onFail: (() -> Void)? = nil) {
        guard !resettingIdentity else{return}
        resettingIdentity=true
        Task { @MainActor in
            do {
                try await Task.detached(priority: .utility) { try IdentityLifecycle.prepare() }.value
            } catch {
                if !IdentityLifecycle.hasPendingReset {
                    resettingIdentity = false
                    resetErrorMessage = "identity_reset_prepare_failed"
                    onFail?()
                    return
                }
            }
            if let delegate = AppDelegate.running {
                delegate.quitAfterIdentityReset()
            } else {
                RunLoop.main.perform { NSApp.terminate(nil) }
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

    public func saveIdentity(nickname:String,avatarUrl:URL?=nil,avatarBytes:Data?=nil,removeAvatar:Bool=false,completion:@escaping(Bool)->Void){
        SecureMessaging.shared.updateProfile(nickname:nickname,avatarURL:avatarUrl,avatarBytes:avatarBytes,removeAvatar:removeAvatar){saved in
            Task { @MainActor in self.objectWillChange.send();completion(saved)}
        }
    }

    public func broadcastProfileUpdate() {
        SecureMessaging.shared.broadcastProfile()
    }
}
