import Foundation
import AppKit
import Combine
import SwiftUI

@MainActor
public final class ChatViewModel: ObservableObject {
    public let targetUuid: String

    @Published public var conversation: Conversation?
    @Published public var messages: [ChatMessage] = []
    @Published private(set) var historyLoaded = false
    @Published private(set) var historyLoadFailed = false
    @Published public var replyAuthors: [String: String] = [:]
    @Published private(set) var forwardedMessages: [String: ForwardedMessageInfo] = [:]
    @Published private(set) var albumItems: [String: EncryptedObjectStore.TimelineAlbumItem] = [:]

    var displayedAlbums: [String: [ChatMessage]] {
        var groups: [String: [ChatMessage]] = [:]
        for message in messages {
            guard message.isRevoked == 0, let album = albumItems[message.messageId]?.position else { continue }
            groups[(message.isMine ? "out/" : "in/") + album.id, default: []].append(message)
        }
        var result: [String: [ChatMessage]] = [:]
        for group in groups.values where group.count >= 2 {
            for message in group { result[message.messageId] = group }
        }
        return result
    }

    @Published private(set) var pinnedMessage:PinnedMessageItem?
    @Published private(set) var canCyclePinnedPreview = false
    private var pinnedPreviewAnchor: PinnedMessageItem?
    private var nextPinnedPreview: PinnedMessageItem?
    @Published private(set) var muteUntil: Int64 = 0
    @Published private(set) var pinnedMessageIDs=Set<String>()
    func messageIsPinned(_ id:String)->Bool {
        pinnedMessageIDs.contains(id)
    }
    func setMessagePinned(_ id:String,pinned:Bool,notify:Bool){
        SecureMessaging.shared.setMessagePinned(peer:targetUuid,messageID:id,pinned:pinned,notify:notify){ok in
            if !ok {Task{@MainActor in NoticeCenter.shared.show(InterfaceStrings.current()("pin_change_failed"))}}
        }
    }
    @Published public private(set) var selectedMessages=Set<String>()
    private var selectionAnchor: String?
    @Published public private(set) var savingSelection=false
    public private(set) var selectionVersion=0
    public var isSelecting: Bool {!selectedMessages.isEmpty}
    public var canCompose: Bool {draftReady && !isSelecting}
    private struct Preparing:Sendable {let id:UUID;let owner:ObjectPreparation;let poll:Task<Void,Never>}
    private var preparations=[UUID:Preparing]()
    @Published private(set) var pendingPreparations=[PendingPreparation]()
    private func beginPreparation(filename: String? = nil, fileCount: Int = 1, isSticker: Bool = false,
                                  savingMessages: Bool = false) -> Preparing? {
        guard preparations.count<4 else{return nil}
        let id=UUID();let owner=ObjectPreparation()
        pendingPreparations.append(PendingPreparation(id: id, filename: filename, fileCount: fileCount,
                                                      isSticker: isSticker, progress: owner.snapshot(), savingMessages: savingMessages))
        let poll=Task { [weak self] in
            while !Task.isCancelled {
                let next=await Task.detached(priority:.utility){owner.snapshot()}.value
                if Task.isCancelled{return};self?.updatePreparation(id,next)
                do{try await Task.sleep(nanoseconds:200_000_000)}catch{return}
            }
        }
        let value=Preparing(id:id,owner:owner,poll:poll);preparations[id]=value;return value
    }
    private func updatePreparation(_ id:UUID,_ next:PreparationProgress){
        guard let index = pendingPreparations.firstIndex(where: { $0.id == id }) else { return }
        let current = pendingPreparations[index].progress
        guard next.version > current.version ||
                next.version == current.version && next.completed >= current.completed else { return }
        pendingPreparations[index].progress = next
    }
    private func endPreparation(_ value:Preparing){value.poll.cancel();value.owner.finish();preparations.removeValue(forKey:value.id);pendingPreparations.removeAll{$0.id==value.id}}
    func cancelPreparation(_ id:UUID){if let value=preparations[id]{value.owner.cancel();updatePreparation(id,value.owner.snapshot())}}

    @Published var sendOptions=SendOptions(){didSet{sendOptionsRevision &+= 1}}
    private var sendOptionsRevision:UInt64=0
    private func captureSendOptions(_ value:SendOptions?=nil)throws->(SendOptions,UInt64){
        let selected=value ?? sendOptions
        guard selected.sendAt.map({$0>Int64(Date().timeIntervalSince1970*1000)}) ?? true else {throw SecurityCore.Failure.operation("Choose a future scheduled time")}
        return (selected,sendOptionsRevision)
    }
    private func completeSendOptions(_ revision:UInt64){if sendOptionsRevision==revision {sendOptions=SendOptions()}}
    @Published public var draftText = "" { didSet { persistDraft();refreshWebPreview() } }
    @Published public private(set) var draftReply: ChatMessage?
    @Published private(set) var draftQuote: String?
    @Published private(set) var draftQuoteText: String?
    @Published private(set) var draftReplyAttachmentSummary: String?

    func setReply(_ reply: ChatMessage?, quote: String? = nil, excerpt: String? = nil) {
        draftReply = reply
        draftReplyAttachmentSummary = reply.flatMap(replyAttachmentSummary)
        draftQuote = reply == nil ? nil : quote
        draftQuoteText = draftQuote == nil ? nil : excerpt
        persistDraft()
    }

    private func replyAttachmentSummary(_ reply: ChatMessage) -> String? {
        guard reply.ext == "attachment",
              let attachment = try? EncryptedObjectStore.shared.metadata(reply.content, messageID: reply.messageId)
        else { return nil }
        return attachment.caption.isEmpty ? attachment.filename : attachment.caption
    }

    private func refreshDraftReply() {
        guard let previous = draftReply else { return }
        do {
            guard let latest = try MsgDao.shared.getMessageById(previous.messageId),
                  latest.conversationUuid == targetUuid, latest.isRevoked == 0,
                  draftQuote == nil || latest.content == previous.content else {
                setReply(nil)
                return
            }
            if draftReply != latest { draftReply = latest }
            let summary = replyAttachmentSummary(latest)
            if draftReplyAttachmentSummary != summary { draftReplyAttachmentSummary = summary }
        } catch {
            // Keep the draft if its target could not be read.
        }
    }
    @Published public private(set) var draftReady = false
    private var draftValue: ConversationDraft?
    private var restoringDraft = false
    private var sendingDrafts = Set<Int64>()
    @Published private(set) var textSendPending = false
    private var draftSaveErrorReported = false

    @Published public var currentLimit: Int = 50
    @Published public var hasMoreOlderMessages: Bool = true
    @Published public private(set) var hasMoreNewerMessages = false
    private var historyAnchor: ChatMessage?

    @Published public private(set) var isViewingLatest = false
    private(set) var recentMessageArrivals: [String: ContinuousClock.Instant] = [:]
    @Published private(set) var followLatestRevision = 0
    @Published private(set) var unreadCount = 0
    @Published private(set) var peerActivity: PeerActivityStatus?
    private var activityAttached=false
    private var activityExpiry: Task<Void, Never>?
    public func syncChatActivity(typing:Bool?=nil){
        let active=isContentVisible
        if active {if !activityAttached || typing != nil {SecureMessaging.shared.setActiveChat(targetUuid,typing:typing ?? false)}}
        else if activityAttached {SecureMessaging.shared.clearActiveChat(targetUuid)}
        if activityAttached != active {
            activityAttached = active
            refreshPeerActivity()
        }
    }

    private func refreshPeerActivity() {
        activityExpiry?.cancel()
        activityExpiry = nil
        let activity = isContentVisible ? SecureMessaging.shared.peerActivity(targetUuid) : nil
        if peerActivity != activity?.status { peerActivity = activity?.status }
        guard let activity else { return }
        activityExpiry = Task { @MainActor [weak self] in
            let remaining = max(0, activity.expires - ProcessInfo.processInfo.systemUptime)
            do { try await Task.sleep(nanoseconds: UInt64((remaining * 1_000_000_000).rounded(.up))) }
            catch { return }
            guard !Task.isCancelled else { return }
            self?.refreshPeerActivity()
        }
    }

    private func updateChatVisibility() {
        syncChatActivity()
        markAsRead()
    }
    public var isMediaPresented = false {didSet {updateChatVisibility()}}
    public var isChatMounted = false {
        didSet {
            if !isChatMounted { visibleMessageIDs.removeAll() }
            updateChatVisibility()
        }
    }
    private var visibleMessageIDs=Set<String>()
    private var pendingReadIDs = Set<String>()
    private var readTask:Task<Void,Never>?
    private var readCandidates = Set<String>()
    public func setVisibleMessages(_ ids:Set<String>){
        guard visibleMessageIDs != ids else {return}
        visibleMessageIDs=ids;markAsRead()
    }
    private var isContentVisible: Bool {
        guard historyLoaded, isChatMounted, !isMediaPresented, NSApp.isActive,
              let window = NSApp.keyWindow, window === NotificationHelper.mainWindow,
              window.isVisible, !window.isMiniaturized, window.attachedSheet == nil,
              NSApp.modalWindow == nil else { return false }
        return CallManager.shared.callState == .idle || CallManager.shared.isMinimized
    }
    private var refreshTask:Task<Void,Never>?
    private var refreshRevision:UInt64=0
    private func scheduleRefresh(){
        refreshRevision &+= 1
        guard refreshTask==nil else{return}
        refreshTask=Task { @MainActor [weak self] in
            do{try await Task.sleep(nanoseconds:16_000_000)}catch{return}
            guard let self else{return};let revision=self.refreshRevision
            self.loadConversation();self.loadMessages();self.refreshTask=nil
            if self.refreshRevision != revision {self.scheduleRefresh()}
        }
    }
    private var cancellables = Set<AnyCancellable>()

    @Published var webPreview: WebPreviewDraft?
    @Published var previewEnabled=false {
        didSet {
            guard !loadingPreviewPreference else {return}
            do {try WebPreviewStore.setEnabled(previewEnabled)}
            catch {NoticeCenter.shared.show("Link preview preference could not be saved")}
            refreshWebPreview()
        }
    }
    private var loadingPreviewPreference=true
    private var previewTask: Task<Void,Never>?
    @Published private(set) var previewChoices: [String] = []
    @Published private(set) var previewURL: String?
    @Published private(set) var previewAboveText = false
    @Published private(set) var previewCompactImage = false
    @Published private(set) var previewLoading = false
    private var chosenPreview: String?
    private var removedPreview: String?

    func selectWebPreview(_ url: String) {
        guard previewEnabled, previewChoices.contains(url) else { return }
        chosenPreview = url
        removedPreview = nil
        refreshWebPreview()
    }

    func togglePreviewPlacement() {
        if previewURL != nil { previewAboveText.toggle() }
    }

    func togglePreviewImageSize() {
        if webPreview?.image != nil { previewCompactImage.toggle() }
    }

    func removeWebPreview() {
        guard let previewURL else { return }
        removedPreview = previewURL
        refreshWebPreview()
    }

    func retryWebPreview() {
        guard previewEnabled, !previewLoading, webPreview == nil, let previewURL else { return }
        loadWebPreview(previewURL)
    }

    private func cancelWebPreview() {
        previewTask?.cancel()
        previewTask = nil
        previewLoading = false
        webPreview?.clearImage()
        webPreview = nil
    }
    private func refreshWebPreview(){
        let choices = (try? WebPreviewFetcher.candidates(draftText)) ?? []
        if previewChoices != choices { previewChoices = choices }
        if choices.isEmpty {
            previewAboveText = false
            previewCompactImage = false
        }
        if let chosenPreview, !choices.contains(chosenPreview) { self.chosenPreview = nil }
        if let removedPreview, !choices.contains(removedPreview) { self.removedPreview = nil }
        let selection = chosenPreview ?? choices.first
        let url = previewEnabled && selection != removedPreview ? selection : nil
        guard url != previewURL else { return }
        cancelWebPreview()
        previewURL = url
        guard let url else { return }
        loadWebPreview(url)
    }

    private func loadWebPreview(_ url: String) {
        previewLoading = true
        previewTask=Task { @MainActor [weak self] in
            do {try await Task.sleep(nanoseconds:600_000_000)} catch {return}
            var result=await WebPreviewFetcher.fetchURL(url)
            guard let self,!Task.isCancelled,self.previewURL==url else {result?.clearImage();return}
            self.webPreview=result
            self.previewLoading = false
        }
    }
    deinit {refreshTask?.cancel();previewTask?.cancel();readTask?.cancel();activityExpiry?.cancel();for value in preparations.values {value.poll.cancel()}}

    public init(targetUuid: String) {
        self.targetUuid = targetUuid
        Task { @MainActor [weak self] in
            let enabled=(try? await EncryptedMediaWork.run {try WebPreviewStore.enabled()}) ?? false
            guard let self else {return};self.previewEnabled=enabled;self.loadingPreviewPreference=false;self.refreshWebPreview()
        }

        loadConversation()
        loadDraft()

        CallManager.shared.$callState.combineLatest(CallManager.shared.$isMinimized)
            .receive(on: DispatchQueue.main).sink { [weak self] _ in self?.updateChatVisibility() }.store(in: &cancellables)

        NotificationCenter.default.publisher(for: MsgDao.didChangeNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.scheduleRefresh() }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: MsgDao.deliveryDidChangeNotification)
            .compactMap { $0.object as? MessageDeliveryChange }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] change in self?.refreshDelivery(change) }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: MsgDao.visibleMessagesReadNotification)
            .compactMap { $0.object as? VisibleMessagesRead }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] change in self?.refreshVisibleReads(change) }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: SecureMessaging.peerActivityChanged)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] notification in
                guard let self, (notification.object as? String).map({ $0 == self.targetUuid }) ?? true else { return }
                self.refreshPeerActivity()
            }
            .store(in: &cancellables)

        let visibilityNotifications = [
            NSApplication.didBecomeActiveNotification, NSApplication.didResignActiveNotification,
            NSWindow.didBecomeKeyNotification, NSWindow.didResignKeyNotification,
            NSWindow.willBeginSheetNotification, NSWindow.didEndSheetNotification,
            NSWindow.didMiniaturizeNotification, NSWindow.didDeminiaturizeNotification
        ]
        Publishers.MergeMany(visibilityNotifications.map { NotificationCenter.default.publisher(for: $0) })
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.updateChatVisibility() }
            .store(in: &cancellables)
    }

    private func refreshDelivery(_ change: MessageDeliveryChange) {
        guard change.peer == targetUuid else { return }
        guard historyLoaded else { refreshRevision &+= 1; return }
        guard let index = messages.firstIndex(where: { $0.messageId == change.messageID && $0.isMine && $0.isRevoked == 0 })
        else { return }
        do {
            guard let stored = try MsgDao.shared.getMessageById(change.messageID),
                  stored.conversationUuid == targetUuid, stored.isMine, stored.isRevoked == 0
            else { scheduleRefresh(); return }
            var updated = messages[index]
            updated.status = stored.status
            updated.isRead = stored.isRead
            if updated != stored {
                scheduleRefresh()
            } else if messages[index] != updated {
                messages[index] = updated
            }
        } catch {
            scheduleRefresh()
        }
    }

    private func refreshVisibleReads(_ change: VisibleMessagesRead) {
        guard change.peer == targetUuid else { return }
        if !historyLoaded { refreshRevision &+= 1 }
        var current = messages
        var updated = false
        for index in current.indices where !current[index].isRead && change.messageIDs.contains(current[index].messageId) {
            current[index].isRead = true
            updated = true
        }
        if updated { messages = current }
        if let count = try? MsgDao.shared.unreadCount(peer: targetUuid), count != unreadCount { unreadCount = count }
        markAsRead()
    }

    public func loadConversation() {
        do {
            let current = try MsgDao.shared.getConversationByUuid(targetUuid)
            if current != conversation { conversation = current }
        } catch {
            // Preserve the last committed profile until the next refresh can read the vault.
        }
        if let until = try? AppDatabase.shared.transaction({ try ConversationIndexStore.muteUntil($0, peer: targetUuid) }), until != muteUntil {
            muteUntil = until
        }
        if let count = try? MsgDao.shared.unreadCount(peer: targetUuid), count != unreadCount {
            unreadCount = count
        }
    }

    private func canSelect(_ message: ChatMessage) -> Bool {
        message.isRevoked == 0 && ["txt", "attachment", "contact", "location"].contains(message.ext)
    }

    func toggleSelection(_ message: ChatMessage, extending: Bool = false) {
        guard !savingSelection, canSelect(message) else { return }
        if extending, let selectionAnchor, selectedMessages.contains(selectionAnchor),
           let start = messages.firstIndex(where: { $0.id == selectionAnchor }),
           let end = messages.firstIndex(where: { $0.id == message.id }) {
            let ids = messages[min(start, end)...max(start, end)].lazy.filter { self.canSelect($0) }.map(\.id)
            let next = selectedMessages.union(ids)
            guard next.count <= 100 else {
                NoticeCenter.shared.show(InterfaceStrings.current()("message_selection_limit"))
                return
            }
            if next != selectedMessages {
                selectedMessages = next
                selectionVersion += 1
            }
            return
        }
        if selectedMessages.contains(message.id) {selectedMessages.remove(message.id)}
        else {
            guard selectedMessages.count < 100 else {
                NoticeCenter.shared.show(InterfaceStrings.current()("message_selection_limit"))
                return
            }
            selectedMessages.insert(message.id)
        }
        selectionAnchor = selectedMessages.contains(message.id) ? message.id : nil
        selectionVersion+=1
    }
    func clearSelection() {
        selectedMessages = []
        selectionAnchor = nil
        selectionVersion += 1
    }
    func clearSelection(ifVersion version: Int) {if selectionVersion==version {clearSelection()}}
    func removeSelectedMessages(_ ids: Set<String>) {
        if !selectedMessages.isDisjoint(with: ids) {
            selectedMessages.subtract(ids)
            selectionVersion += 1
        }
    }

    func deleteMessages(_ ids: Set<String>, revokeForBoth: Bool = false) {
        guard !savingSelection, isChatMounted, AppLockSession.shared.canShowContent else { return }
        savingSelection = true
        let completion: @Sendable (Bool) -> Void = { [weak self] removed in
            Task { @MainActor in
                guard let self else { return }
                self.savingSelection = false
                if removed { self.removeSelectedMessages(ids) }
                else if revokeForBoth, self.isChatMounted, AppLockSession.shared.canShowContent {
                    NoticeCenter.shared.show(InterfaceStrings.current()("revoke_selected_unavailable"))
                }
            }
        }
        if revokeForBoth {
            SecureMessaging.shared.revokeSentMessages(to: targetUuid, ids: ids, completion: completion)
        } else {
            SecureMessaging.shared.deleteLocalMessages(peer: targetUuid, ids: ids, completion: completion)
        }
    }
    func saveMessages(_ ids: [String]) {
        guard !savingSelection, let preparation = beginPreparation(savingMessages: true) else { return }
        let version=selectionVersion;let clearAfter=selectedMessages==Set(ids)
        savingSelection=true
        MessageActionsStore.shared.save(peer:targetUuid,ids:ids,preparation:preparation.owner) { [weak self] ok in
            Task { @MainActor in
                guard let self else { preparation.owner.finish(); return }
                self.endPreparation(preparation)
                self.savingSelection=false
                if ok {
                    if clearAfter {self.clearSelection(ifVersion:version)}
                    NoticeCenter.shared.show("Saved")
                } else if !preparation.owner.wasCancelled {
                    NoticeCenter.shared.show("Could not save. Check free storage and attachment downloads.")
                }
            }
        }
    }

    private func loadDraft() {
        ConversationDraftStore.shared.open(targetUuid) { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                do {
                    let value = try result.get()
                    var reply: ChatMessage?
                    if let id = value.replyId { reply = try MsgDao.shared.getMessageById(id) }
                    if reply?.conversationUuid != self.targetUuid || reply?.isRevoked != 0 { reply = nil }
                    var quote = value.replyQuote
                    var excerpt: String?
                    if let selection = quote {
                        if let reply, try MsgDao.shared.canQuoteText(reply),
                           let result = try? SecurityCore.call(["operation": "reply_quote_selection", "text": reply.content, "selection": selection]) {
                            excerpt = result["preview"] as? String
                        }
                        if excerpt == nil { quote = nil; reply = nil }
                    }
                    let normalized = try ConversationDraftStore.shared.update(value, text: value.text, replyId: reply?.messageId, replyQuote: quote)
                    self.restoringDraft = true
                    self.draftValue = normalized
                    self.draftText = normalized.text
                    self.setReply(reply, quote: quote, excerpt: excerpt)
                    self.restoringDraft = false
                    self.draftReady = true
                } catch {
                    self.restoringDraft = false
                    self.draftValue = nil
                    self.draftReady = false
                    NoticeCenter.shared.show("The saved draft could not be opened.")
                }
            }
        }
    }

    private func persistDraft() {
        guard !restoringDraft, let value = draftValue else { return }
        do {
            draftValue = try ConversationDraftStore.shared.update(value, text: draftText, replyId: draftReply?.messageId, replyQuote: draftQuote)
            draftSaveErrorReported = false
        } catch {
            if !draftSaveErrorReported { NoticeCenter.shared.show("The draft could not be saved."); draftSaveErrorReported = true }
        }
    }

    public func loadMessages() {
        guard historyLoaded else { refreshRevision &+= 1; return }
        _ = loadMessages(jumpToLatest: false)
    }

    struct InitialHistoryPosition: Sendable {
        let target: String?
        let unread: String?
        let restoresSavedPosition: Bool
    }

    func loadInitialHistory(target explicitTarget: String?, savedMessageID: String?) async -> InitialHistoryPosition? {
        guard !historyLoaded, !Task.isCancelled else { return nil }
        historyLoadFailed = false
        let peer = targetUuid
        let local = KeychainManager.shared.myUuid
        let alias = conversation?.alias
        let quotedYou = InterfaceStrings.current()("quoted_you")
        let revision = refreshRevision
        let limit = currentLimit
        let loader = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            let unread = try MsgDao.shared.getFirstUnreadId(peer: peer)
            let target = explicitTarget ?? unread ?? savedMessageID
            let window: [ChatMessage]? = try target.flatMap { id in
                try MsgDao.shared.getMessageWindow(peer: peer, id: id).flatMap { rows in
                    rows.contains { $0.messageId == id && $0.isRevoked == 0 } ? rows : nil
                }
            }
            let usesWindow = window != nil
            let rows = try window ?? MsgDao.shared.getMessages(conversationUuid: peer, limit: limit)
            try Task.checkCancellation()
            let pinned = try Self.readPinnedPreview(peer: peer, local: local, anchor: nil)
            let presentation = Self.readHistoryPresentation(rows, peer: peer, local: local, alias: alias, quotedYou: quotedYou)
            let position = InitialHistoryPosition(target: usesWindow ? target : nil,
                unread: usesWindow ? unread : nil, restoresSavedPosition: usesWindow && explicitTarget == nil && unread == nil)
            return (presentation, pinned, position)
        }
        do {
            let (presentation, pinned, position) = try await withTaskCancellationHandler {
                try await loader.value
            } onCancel: {
                loader.cancel()
            }
            guard !Task.isCancelled, !historyLoaded else { return nil }
            isViewingLatest = position.target == nil
            if position.target != nil {
                historyAnchor = presentation.rows.first
                currentLimit = presentation.rows.count
            }
            applyPinnedPreview(pinned.page, anchor: pinned.anchor)
            applyHistoryPresentation(presentation)
            refreshDraftReply()
            historyLoaded = true
            if refreshRevision != revision { scheduleRefresh() }
            return position
        } catch is CancellationError {
            return nil
        } catch {
            if !Task.isCancelled && !historyLoaded { historyLoadFailed = true }
            return nil
        }
    }

    func cyclePinnedPreview() {
        guard canCyclePinnedPreview else { return }
        do { try loadPinnedPreview(anchor: nextPinnedPreview) }
        catch { NoticeCenter.shared.show(InterfaceStrings.current()("history_load_failed")) }
    }

    private func refreshPinnedPreview() throws {
        try loadPinnedPreview(anchor: pinnedPreviewAnchor)
    }

    private func loadPinnedPreview(anchor: PinnedMessageItem?) throws {
        let pinned = try Self.readPinnedPreview(peer: targetUuid, local: KeychainManager.shared.myUuid, anchor: anchor)
        applyPinnedPreview(pinned.page, anchor: pinned.anchor)
    }

    private nonisolated static func readPinnedPreview(peer: String, local: String, anchor: PinnedMessageItem?) throws
        -> (page: PinnedMessagePage, anchor: PinnedMessageItem?) {
        try AppDatabase.shared.transaction { db in
            let page = try MessagePinsStore.list(db, peer: peer, local: local, pageSize: 2, through: anchor)
            if let anchor, page.items.first?.id != anchor.id {
                return (try MessagePinsStore.list(db, peer: peer, local: local, pageSize: 2), nil)
            }
            return (page, anchor)
        }
    }

    private func applyPinnedPreview(_ page: PinnedMessagePage, anchor: PinnedMessageItem?) {
        pinnedPreviewAnchor = anchor
        nextPinnedPreview = nil
        let current = page.items.first
        if let current, page.items.count > 1 {
            let candidate = page.items[1]
            if candidate.timestamp < current.timestamp || (candidate.timestamp == current.timestamp && candidate.id < current.id) {
                nextPinnedPreview = candidate
            }
        }
        if pinnedMessage != current { pinnedMessage = current }
        let canCycle = page.items.count > 1
        if canCyclePinnedPreview != canCycle { canCyclePinnedPreview = canCycle }
    }

    private func loadMessages(jumpToLatest: Bool, from requestedStart: ChatMessage? = nil, limit requestedLimit: Int? = nil) -> Bool {
        do {
            try refreshPinnedPreview()
            let limit = jumpToLatest ? 50 : requestedLimit ?? currentLimit
            let start = jumpToLatest ? nil : requestedStart ?? historyAnchor ?? (isViewingLatest ? nil : messages.first)
            var msgs: [ChatMessage]
            if let start {
                msgs = try MsgDao.shared.getMessagesFrom(start, limit: limit)
            } else {
                msgs = try MsgDao.shared.getMessages(conversationUuid: targetUuid, limit: limit)
            }
            let startWasRemoved = start != nil && msgs.isEmpty
            if startWasRemoved {
                msgs = try MsgDao.shared.getMessages(conversationUuid: targetUuid, limit: limit)
            }
            historyAnchor = startWasRemoved || start == nil ? nil : msgs.first
            if currentLimit != limit { currentLimit = limit }
            let previous = messages.last
            let follow = !jumpToLatest && start == nil && isViewingLatest && msgs.last.map { newest in
                previous.map { newest.messageId != $0.messageId && newest.timestamp >= $0.timestamp } ?? true
            } == true
            if follow, let previous,
               let boundary = msgs.lastIndex(where: { $0.messageId == previous.messageId }) {
                let tail = msgs.suffix(from: msgs.index(after: boundary))
                if (1...12).contains(tail.count) {
                    let now = ContinuousClock().now
                    recentMessageArrivals = recentMessageArrivals.filter { $0.value.duration(to: now) < .milliseconds(350) }
                    for message in tail where message.isRevoked == 0 {
                        recentMessageArrivals[message.messageId] = now
                    }
                }
            }
            presentMessages(msgs)
            refreshDraftReply()
            historyLoaded = true
            historyLoadFailed = false
            if jumpToLatest || (follow && !hasMoreNewerMessages) { followLatestRevision += 1 }
            return true
        } catch {
            historyLoadFailed = true
            return false
        }
    }

    private struct HistoryPresentation: Sendable {
        let rows: [ChatMessage]
        let pins: Set<String>?
        let forwards: [String: ForwardedMessageInfo]?
        let authors: [String: String]
        let albums: [String: EncryptedObjectStore.TimelineAlbumItem]
        let hasOlder: Bool
        let hasNewer: Bool
    }

    private nonisolated static func readHistoryPresentation(_ rows: [ChatMessage], peer: String, local: String,
                                                           alias: String?, quotedYou: String) -> HistoryPresentation {
        let pins = try? AppDatabase.shared.transaction {
            try MessagePinsStore.states($0, peer: peer, local: local, ids: rows.map(\.messageId))
        }
        let visible = rows.filter { $0.isRevoked == 0 }
        let forwards = try? MsgDao.shared.getForwardInfo(ids: visible.map(\.messageId))
        var authors: [String: String] = [:]
        for message in rows {
            guard let replyID = message.replyToId else { continue }
            let original = try? MsgDao.shared.getMessageById(replyID)
            let author: String?
            if let original, original.conversationUuid == peer {
                author = original.isMine ? local : peer
            } else {
                author = message.replyToSender
            }
            if author == local { authors[message.messageId] = quotedYou }
            else if author == peer { authors[message.messageId] = alias }
        }
        let attachmentIDs = visible.filter { $0.ext == "attachment" }.map(\.messageId)
        let albums = (try? EncryptedObjectStore.shared.timelineAlbumItems(peer: peer, messageIds: attachmentIDs)) ?? [:]
        let older = rows.first.map { (try? MsgDao.shared.getMessagesBefore($0, limit: 1).isEmpty) == false } ?? false
        let newer = rows.last.map { (try? MsgDao.shared.getMessagesAfter($0, limit: 1).isEmpty) == false } ?? false
        return HistoryPresentation(rows: rows, pins: pins, forwards: forwards, authors: authors, albums: albums,
                                   hasOlder: older, hasNewer: newer)
    }

    private func presentMessages(_ rows: [ChatMessage]) {
        applyHistoryPresentation(Self.readHistoryPresentation(rows, peer: targetUuid, local: KeychainManager.shared.myUuid,
            alias: conversation?.alias, quotedYou: InterfaceStrings.current()("quoted_you")))
    }

    private func applyHistoryPresentation(_ presentation: HistoryPresentation) {
        if let pins = presentation.pins, pinnedMessageIDs != pins { pinnedMessageIDs = pins }
        let revoked = Set(presentation.rows.filter { $0.isRevoked != 0 }.map(\.messageId))
        if !selectedMessages.isDisjoint(with: revoked) {
            selectedMessages.subtract(revoked)
            selectionVersion += 1
        }
        if let forwards = presentation.forwards, forwardedMessages != forwards { forwardedMessages = forwards }
        if replyAuthors != presentation.authors { replyAuthors = presentation.authors }
        if albumItems != presentation.albums { albumItems = presentation.albums }
        if messages != presentation.rows { messages = presentation.rows }
        hasMoreOlderMessages = presentation.hasOlder
        hasMoreNewerMessages = presentation.hasNewer
        markAsRead()
    }

    public func loadMoreMessages() {
        guard hasMoreOlderMessages, let first = messages.first else { return }
        do {
            let older = try MsgDao.shared.getMessagesBefore(first)
            guard let start = older.first else {
                hasMoreOlderMessages = false
                historyLoadFailed = false
                return
            }
            _ = loadMessages(jumpToLatest: false, from: start, limit: currentLimit + older.count)
        } catch {
            historyLoadFailed = true
        }
    }

    public func loadNewerMessages() {
        guard hasMoreNewerMessages, let first = messages.first else { return }
        _ = loadMessages(jumpToLatest: false, from: first, limit: currentLimit + 50)
    }

    @discardableResult
    public func loadLatestMessages() -> Bool {
        loadMessages(jumpToLatest: true)
    }

    public func revealMessage(_ id: String) -> Bool {
        if messages.contains(where:{$0.messageId==id}) {
            guard (try? MsgDao.shared.hasHistoryAnchor(peer: targetUuid, id: id)) == true else { return false }
            isViewingLatest = false
            historyAnchor = messages.first
            currentLimit = messages.count
            return true
        }
        guard let window = try? MsgDao.shared.getMessageWindow(peer: targetUuid, id: id),
              window.contains(where: { $0.messageId == id }) else { return false }
        isViewingLatest=false;historyAnchor=window.first;currentLimit=window.count
        presentMessages(window)
        historyLoaded = true
        historyLoadFailed = false
        return true
    }

    public func setViewingLatest(_ visible: Bool) {
        let latest = visible && !hasMoreNewerMessages
        if latest { historyAnchor = nil }
        if isViewingLatest != latest { isViewingLatest = latest }
    }

    public func markAsRead() {
        scheduleVisibleReads(retry: false)
    }

    private func scheduleVisibleReads(retry: Bool) {
        guard isContentVisible else {
            readTask?.cancel()
            readTask = nil
            readCandidates.removeAll()
            return
        }
        let visible = Set(messages.lazy.filter {
            !$0.isMine && !$0.isRead && $0.isRevoked == 0 &&
                self.visibleMessageIDs.contains($0.messageId) && !self.pendingReadIDs.contains($0.messageId)
        }.prefix(100).map(\.messageId))
        if visible == readCandidates { return }
        readTask?.cancel()
        readTask = nil
        readCandidates = visible
        guard !visible.isEmpty else { return }
        readTask=Task { @MainActor [weak self] in
            do { try await Task.sleep(nanoseconds: retry ? 500_000_000 : 150_000_000) }
            catch { return }
            guard let self, !Task.isCancelled else { return }
            self.readTask = nil
            self.readCandidates.removeAll()
            guard self.isContentVisible else { return }
            let ids = visible.intersection(self.visibleMessageIDs).subtracting(self.pendingReadIDs)
            guard !ids.isEmpty else { return }
            self.pendingReadIDs.formUnion(ids)
            SecureMessaging.shared.markRead(self.targetUuid, visibleIDs: ids) { [weak self] committed in
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    self.pendingReadIDs.subtract(ids)
                    if !committed && !retry && self.readTask == nil &&
                        !ids.isDisjoint(with: self.visibleMessageIDs) {
                        self.scheduleVisibleReads(retry: true)
                    }
                }
            }
        }
    }

    public func sendMessage(
        text: String,
        replyTo: ChatMessage? = nil,
        onFail: (() -> Void)? = nil
    ) {
        sendMessage(text: text, replyTo: replyTo, options: nil, onFail: onFail)
    }

    func sendMessage(
        text: String,
        replyTo: ChatMessage?,
        options: SendOptions?,
        onFail: (() -> Void)?
    ) {
        guard !textSendPending, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        guard canCompose, let before = draftValue else { onFail?(); return }
        let value: ConversationDraft
        let commit: DraftSendCommit
        let selection:(SendOptions,UInt64)
        do {
            selection=try captureSendOptions(options)
            let quote = draftReply?.messageId == replyTo?.messageId ? draftQuote : nil
            value = try ConversationDraftStore.shared.update(before, text: text, replyId: replyTo?.messageId, replyQuote: quote)
            draftValue = value
            if sendingDrafts.contains(value.revision) { return }
            commit = try ConversationDraftStore.shared.prepareSend(value)
            sendingDrafts.insert(value.revision)
        } catch { onFail?(); return }
        let preview = webPreview.flatMap { candidate -> WebPreviewDraft? in
            guard previewEnabled, candidate.url == previewURL,
                  (try? WebPreviewFetcher.candidates(text).contains(candidate.url)) == true else { return nil }
            var positioned = candidate
            positioned.aboveText = previewAboveText
            positioned.compactImage = previewCompactImage
            return positioned
        }
        let preparation: Preparing?
        if preview?.image != nil {
            guard let coverPreparation = beginPreparation(filename: InterfaceStrings.current()("link_preview_cover")) else {
                commit.owner.observeFailedSend(commit)
                sendingDrafts.remove(value.revision)
                onFail?()
                return
            }
            preparation = coverPreparation
        } else {
            preparation = nil
        }
        textSendPending = true
        SecureMessaging.shared.sendText(to: targetUuid, text: text, reply: replyTo, draft: commit,
                                        preview: preview, options: selection.0, preparation: preparation?.owner) { saved in
            Task { @MainActor in
                self.sendingDrafts.remove(value.revision)
                defer {
                    if let preparation { self.endPreparation(preparation) }
                    self.textSendPending = false
                }
                if saved {
                    self.completeSendOptions(selection.1)
                    if commit.updatesDraft, self.draftValue?.token == value.token, self.draftValue?.revision == value.revision,
                       self.draftText == text, self.draftReply?.messageId == replyTo?.messageId,
                       self.draftQuote == value.replyQuote {
                        self.restoringDraft = true
                        self.draftText = ""
                        self.setReply(nil)
                        self.draftValue = ConversationDraft(peer: value.peer, token: value.token, revision: commit.clearRevision, text: "", replyId: nil)
                        self.restoringDraft = false
                    }
                    self.loadMessages()
                } else if preparation?.owner.wasCancelled != true {
                    onFail?()
                }
            }
        }
    }

    func captureMediaDraft() -> ConversationDraft? { canCompose ? draftValue : nil }

    func capturePastedImageSender(onFail: @escaping () -> Void) -> ((MediaSendSource) -> Void)? {
        guard let draft = captureMediaDraft(), let selection = try? captureSendOptions() else { return nil }
        let reply = draftReply
        return { source in
            self.sendMedia(sources: [source], replyTo: reply, draft: draft, choice: MediaSendChoice(options: selection.0),
                           capturedOptionsRevision: selection.1, onFail: onFail)
        }
    }

    private func prepareMediaDraft(_ captured: ConversationDraft?, reply: ChatMessage?) throws -> DraftSendCommit {
        guard canCompose, let value = captured ?? draftValue, value.peer == targetUuid, value.replyId == reply?.messageId else {
            throw MsgATDatabaseError(operation: "Draft", detail: "The draft no longer matches the attachment.")
        }
        guard !sendingDrafts.contains(value.revision) else {
            throw MsgATDatabaseError(operation: "Draft", detail: "Wait until the current send has been queued.")
        }
        let commit = try ConversationDraftStore.shared.prepareSend(value, clearsText: false)
        sendingDrafts.insert(value.revision)
        return commit
    }

    private func completeMediaDraft(_ commit: DraftSendCommit) {
        let value = commit.draft
        guard commit.updatesDraft, draftValue?.token == value.token, draftValue?.revision == value.revision else { return }
        restoringDraft = true
        setReply(nil)
        draftValue = ConversationDraft(peer: value.peer, token: value.token, revision: commit.clearRevision, text: value.text, replyId: nil)
        restoringDraft = false
    }

    func sendSticker(_ item:StickerItem,draft:ConversationDraft?,reply:ChatMessage?) async -> PreparationSendResult {
        guard let preparation=beginPreparation(isSticker: true) else{return .failed}
        defer{endPreparation(preparation)}
        let commit:DraftSendCommit;let selection:(SendOptions,UInt64)
        do {selection=try captureSendOptions();commit=try prepareMediaDraft(draft,reply:reply)}catch{return .failed}
        let saved=await withCheckedContinuation {continuation in
            SecureMessaging.shared.sendSticker(to:targetUuid,id:item.id,reply:reply,draft:commit,options:selection.0,preparation:preparation.owner){continuation.resume(returning:$0)}
        }
        sendingDrafts.remove(commit.draft.revision)
        if saved {completeSendOptions(selection.1);completeMediaDraft(commit);loadMessages()}
        return saved ? .queued:preparation.owner.wasCancelled ? .cancelled:.failed
    }

    func sendContactCard(account: String, name: String, draft: ConversationDraft?, reply: ChatMessage?,
                         options: SendOptions) async -> Bool {
        await sendStructuredMessage(draft: draft, reply: reply, options: options) { commit, selected in
            await withCheckedContinuation { continuation in
                SecureMessaging.shared.sendContactCard(to: targetUuid, account: account, name: name, reply: reply,
                                                       draft: commit, options: selected) { continuation.resume(returning: $0) }
            }
        }
    }

    func sendLocation(point: LocationPoint, label: String?, draft: ConversationDraft?, reply: ChatMessage?,
                      options: SendOptions) async -> Bool {
        await sendStructuredMessage(draft: draft, reply: reply, options: options) { commit, selected in
            await withCheckedContinuation { continuation in
                SecureMessaging.shared.sendLocation(to: targetUuid, point: point, label: label, reply: reply,
                                                    draft: commit, options: selected) { continuation.resume(returning: $0) }
            }
        }
    }

    func shareLiveLocation(point: LocationPoint, durationMs: Int64, label: String?, draft: ConversationDraft?,
                           reply: ChatMessage?, silent: Bool) async -> Bool {
        await sendStructuredMessage(draft: draft, reply: reply, options: SendOptions(silent: silent)) { commit, options in
            await LiveLocationCoordinator.shared.start(peer: targetUuid, point: point, durationMs: durationMs, label: label,
                                                       reply: reply, draft: commit, silent: options.silent)
        }
    }

    private func sendStructuredMessage(draft: ConversationDraft?, reply: ChatMessage?, options: SendOptions,
                                       queue: (DraftSendCommit, SendOptions) async -> Bool) async -> Bool {
        let commit: DraftSendCommit
        let selection: (SendOptions, UInt64)
        do {
            selection = try captureSendOptions(options)
            commit = try prepareMediaDraft(draft, reply: reply)
        } catch { return false }
        defer { sendingDrafts.remove(commit.draft.revision) }
        let saved = await queue(commit, selection.0)
        if saved {
            completeSendOptions(selection.1)
            completeMediaDraft(commit)
            loadMessages()
        }
        return saved
    }

    func sendMedia(
        sources: [MediaSendSource],
        replyTo: ChatMessage? = nil,
        draft: ConversationDraft? = nil,
        choice:MediaSendChoice?=nil,
        capturedOptionsRevision: UInt64? = nil,
        onComplete:(@MainActor @Sendable()->Void)?=nil,
        onFail: (() -> Void)? = nil,
        onResult: (@MainActor @Sendable (PreparationSendResult) -> Void)? = nil,
        onPreparationStarted: ((UUID) -> Void)? = nil
    ) {
        guard let first = sources.first,
              let preparation = beginPreparation(filename: sources.count == 1 ? first.name : nil, fileCount: sources.count)
        else { onComplete?(); onFail?(); onResult?(.failed); return }
        let commit: DraftSendCommit;let selection:(SendOptions,UInt64)
        do {selection=try captureSendOptions(choice?.options);commit = try prepareMediaDraft(draft, reply: replyTo) } catch {
            endPreparation(preparation); onComplete?(); onFail?(); onResult?(.failed); return
        }
        onPreparationStarted?(preparation.id)
        SecureMessaging.shared.sendMedia(to: targetUuid, sources: sources, reply: replyTo, draft: commit,
            options: selection.0, caption: choice?.caption ?? "", asFile: choice?.asFile == true,
            asAlbum: choice?.asAlbum == true, compressPhotos: choice?.compressPhotos == true,
            compressVideos: choice?.compressVideos == true, videoTrims: choice?.videoTrims ?? [:],
            itemCaptions: choice?.itemCaptions ?? [],
            preparation: preparation.owner) { saved in
            Task { @MainActor in
                defer{onComplete?()}
                self.endPreparation(preparation);self.sendingDrafts.remove(commit.draft.revision)
                if saved {self.completeSendOptions(capturedOptionsRevision ?? selection.1); self.completeMediaDraft(commit); self.loadMessages() } else if !preparation.owner.wasCancelled {onFail?()}
                onResult?(saved ? .queued : preparation.owner.wasCancelled ? .cancelled : .failed)
            }
        }
    }

    public func revokeMessage(messageId: String) {
        SecureMessaging.shared.revokeMessage(to: targetUuid, messageID: messageId) { saved in
            if saved { Task { @MainActor in self.loadMessages() } }
        }
    }
    public func editMessage(messageId: String, newContent: String, completion: @escaping @MainActor (Bool) -> Void) {
        SecureMessaging.shared.editMessage(to: targetUuid, messageID: messageId, text: newContent) { saved in
            Task { @MainActor in
                if saved { self.loadMessages() }
                completion(saved)
            }
        }
    }
}
