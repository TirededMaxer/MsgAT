import SwiftUI

struct ChatNotificationNavigation: Hashable {
    let token: String
    let peer: String
    let messageID: String
}

struct ChatSearchNavigation {
    let id = UUID()
    let source: String?
    let origin: String?
    let hit: MessageSearchHit
    let input: MessageSearchInput
}

enum ChatNavigationPanel: Equatable {
    case savedMessages, forwarding, callHistory, sendQueue, contacts
}

struct ChatPanelNavigation {
    let id = UUID()
    let source: String?
    let peer: String
    let messageID: String?
    let panel: ChatNavigationPanel?
    let unavailable: String
    var ready: Bool
}

public struct MainWindow: View {
    private static let minimumChatWidth: CGFloat = 390

    @StateObject private var sessionViewModel = SessionListViewModel()
    @State private var selectedConversationUuid: String? = nil
    @State private var sidebarChatRequest: String?
    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    @State private var columnsBeforeMedia: NavigationSplitViewVisibility?
    @State private var showSearch = false
    @State private var searchCoversChat = false
    @State private var searchNavigation: ChatSearchNavigation?
    @State private var panelNavigation: ChatPanelNavigation?
    @State private var sendQueuePresented = false
    @State private var contactsPresented = false
    @State private var showSavedMessages=false
    @State private var showForwardTasks=false
    @State private var showCallHistory=false
    @State private var searchPeer: String?
    @State private var previousSearches: [String?: MessageSearchInput] = [:]
    @State private var searchOrder: [String?] = []
    @State private var searchInitial: MessageSearchInput?
    private struct SearchReturn {
        let peer: String
        let origin: String?
        var input: MessageSearchInput
        var anchor: MessageSearchCursor
    }
    @Environment(\.interfaceStrings) private var interfaceText
    @State private var searchReturn: SearchReturn?
    @State private var searchMove: Task<Void, Never>?
    @State private var messageTarget: (peer: String, message: String)?
    @State private var notificationNavigation: ChatNotificationNavigation?
    @State private var searchGeneration=0
    @State private var historyPositions: [String: ChatHistoryPosition] = [:]
    @State private var historyOrder: [String] = []

    public init() {}

    private var sidebarSelection: Binding<String?> {
        Binding(get: { selectedConversationUuid }, set: { target in
            guard target != selectedConversationUuid else { return }
            if selectedConversationUuid != nil, let target {
                sidebarChatRequest = target
            } else {
                selectedConversationUuid = target
            }
        })
    }

    private func rememberSearch(_ input: MessageSearchInput, for peer: String?) {
        searchOrder.removeAll { $0 == peer }
        searchOrder.append(peer)
        previousSearches[peer] = input
        if searchOrder.count > 16 { previousSearches.removeValue(forKey: searchOrder.removeFirst()) }
    }

    private func openSearch(_ peer: String?, resume: MessageSearchInput? = nil) {
        searchNavigation = nil
        searchPeer = peer
        searchInitial = resume ?? previousSearches[peer]
        searchCoversChat = true
        showSearch = true
    }

    private func searchHitIsAvailable(_ hit: MessageSearchHit) -> Bool {
        (try? MsgDao.shared.hasHistoryAnchor(peer: hit.peer, id: hit.id, includeRevoked: false)) == true &&
            (try? MsgDao.shared.getConversationByUuid(hit.peer)) != nil
    }

    private func finishSearchDismissal() {
        searchCoversChat = false
        guard let request = searchNavigation, request.source == nil || request.source == request.hit.peer else { return }
        revealSearchHit(request)
    }

    private func resumeSearch(_ request: ChatSearchNavigation) {
        guard searchNavigation?.id == request.id, selectedConversationUuid == request.source,
              AppLockSession.shared.canShowContent else { return }
        openSearch(request.origin, resume: request.input)
    }

    private func revealSearchHit(_ request: ChatSearchNavigation) {
        guard searchNavigation?.id == request.id, selectedConversationUuid == request.source,
              AppLockSession.shared.canShowContent else { return }
        let hit = request.hit
        guard searchHitIsAvailable(hit) else {
            NoticeCenter.shared.show(interfaceText("search_target_unavailable"))
            resumeSearch(request)
            return
        }
        searchNavigation = nil
        rememberSearch(request.input, for: request.origin)
        messageTarget = (hit.peer, hit.id)
        searchGeneration += 1
        selectedConversationUuid = hit.peer
        searchReturn = SearchReturn(peer: hit.peer, origin: request.origin, input: request.input,
            anchor: MessageSearchCursor(timestamp: hit.timestamp, messageId: hit.id))
    }

    private func panelTargetIsAvailable(peer: String, messageID: String?) throws -> Bool {
        guard try MsgDao.shared.getConversationByUuid(peer) != nil else { return false }
        guard let messageID else { return true }
        return try MsgDao.shared.hasHistoryAnchor(peer: peer, id: messageID, includeRevoked: false)
    }

    @discardableResult
    private func requestPanelNavigation(peer: String, messageID: String? = nil, panel: ChatNavigationPanel? = nil,
                                        unavailable: String = "search_target_unavailable") throws -> Bool {
        guard AppLockSession.shared.canShowContent, try panelTargetIsAvailable(peer: peer, messageID: messageID) else { return false }
        let request = ChatPanelNavigation(source: selectedConversationUuid, peer: peer, messageID: messageID,
            panel: panel, unavailable: unavailable, ready: panel == nil)
        panelNavigation = request
        if request.ready { openPanelNavigationWithoutSwitch(request) }
        return true
    }

    private func finishPanelDismissal(_ panel: ChatNavigationPanel) {
        guard var request = panelNavigation, request.panel == panel else { return }
        request.ready = true
        panelNavigation = request
        openPanelNavigationWithoutSwitch(request)
    }

    private func openPanelNavigationWithoutSwitch(_ request: ChatPanelNavigation) {
        if request.source == nil || request.source == request.peer { navigateToPanelTarget(request) }
    }

    private func keepEditingInsteadOfPanelNavigation(_ request: ChatPanelNavigation) {
        guard panelNavigation?.id == request.id, selectedConversationUuid == request.source else { return }
        panelNavigation = nil
        switch request.panel {
        case .savedMessages: showSavedMessages = true
        case .forwarding: showForwardTasks = true
        case .callHistory: showCallHistory = true
        case .sendQueue: sendQueuePresented = true
        case .contacts: contactsPresented = true
        case nil: break
        }
    }

    private func navigateToPanelTarget(_ request: ChatPanelNavigation) {
        guard panelNavigation?.id == request.id, request.ready, selectedConversationUuid == request.source,
              AppLockSession.shared.canShowContent else { return }
        panelNavigation = nil
        guard (try? panelTargetIsAvailable(peer: request.peer, messageID: request.messageID)) == true else {
            NoticeCenter.shared.show(interfaceText(request.unavailable))
            return
        }
        if let id = request.messageID {
            searchReturn = nil
            messageTarget = (request.peer, id)
            searchGeneration += 1
        } else if request.peer != request.source {
            messageTarget = nil
        }
        selectedConversationUuid = request.peer
    }

    private func returnToSearchAction(for peer: String) -> (() -> Void)? {
        guard let route = searchReturn, route.peer == peer else { return nil }
        return { openSearch(route.origin, resume: route.input) }
    }

    private func moveSearchAction(for peer: String) -> ((Bool) -> Void)? {
        guard let route = searchReturn, route.peer == peer, !route.input.all else { return nil }
        return { newer in
            guard searchMove == nil, !showSearch, searchReturn?.anchor.messageId == route.anchor.messageId else { return }
            searchMove = Task { @MainActor in
                defer { searchMove = nil }
                do {
                    let query = route.input.query
                    let kind = route.input.kind
                    let day = route.input.day
                    let hit = try await EncryptedMediaWork.run {
                        try MessageSearchStore.shared.search(query, peer: peer, before: route.anchor,
                            kind: kind, limit: 1, newer: newer, day: day).hits.first
                    }
                    guard !Task.isCancelled, AppLockSession.shared.canShowContent,
                          selectedConversationUuid == peer, !showSearch,
                          searchReturn?.anchor.messageId == route.anchor.messageId else { return }
                    guard let hit else {
                        NoticeCenter.shared.show(interfaceText("search_no_adjacent_result"))
                        return
                    }
                    guard try MsgDao.shared.hasHistoryAnchor(peer: peer, id: hit.id, includeRevoked: false) else {
                        NoticeCenter.shared.show(interfaceText("search_target_unavailable"))
                        return
                    }
                    var updated = route
                    updated.anchor = MessageSearchCursor(timestamp: hit.timestamp, messageId: hit.id)
                    updated.input.selectedMessage = hit.id
                    let index = max(0, (route.input.selectedIndex ?? 0) + (newer ? -1 : 1))
                    updated.input.selectedIndex = index
                    updated.input.loadedCount = max(route.input.loadedCount, index + 1)
                    searchReturn = updated
                    rememberSearch(updated.input, for: route.origin)
                    messageTarget = (peer, hit.id)
                    searchGeneration += 1
                } catch {
                    if !Task.isCancelled, AppLockSession.shared.canShowContent, selectedConversationUuid == peer, !showSearch {
                        NoticeCenter.shared.show(interfaceText("search_failed"))
                    }
                }
            }
        }
    }

    public var body: some View {
        ZStack(alignment: .top) {
            NavigationSplitView(columnVisibility: $columnVisibility) {
                SessionListView(viewModel: sessionViewModel, selectedConversationUuid: sidebarSelection,
                                onSearch: { openSearch(nil) }, onSavedMessages: { showSavedMessages = true },
                                onForwardTasks: { showForwardTasks = true }, onCallHistory: { showCallHistory = true },
                                onOpenQueuedMessage: openQueuedMessage, sendQueuePresented: $sendQueuePresented,
                                onDismissSendQueue: { finishPanelDismissal(.sendQueue) }, contactsPresented: $contactsPresented,
                                onOpenContact: { peer in _ = try? requestPanelNavigation(peer: peer, panel: .contacts) },
                                onDismissContacts: { finishPanelDismissal(.contacts) })
                    .background(SidebarPlacement().allowsHitTesting(false))
                    .navigationSplitViewColumnWidth(min: 260, ideal: 300, max: 460)
            } detail: {
                if let uuid = selectedConversationUuid {
                    ChatView(targetUuid: uuid, initialMessageId: messageTarget?.peer == uuid ? messageTarget?.message : nil,
                             searchGeneration: searchGeneration,
                             historyPosition: historyPositions[uuid],
                             onSaveHistoryPosition: { position in
                                 historyOrder.removeAll {$0 == uuid}
                                 historyPositions[uuid] = position
                                 if position != nil {
                                     historyOrder.append(uuid)
                                     if historyOrder.count > 64 {historyPositions[historyOrder.removeFirst()] = nil}
                                 }
                             },
                             onSearch: { openSearch(uuid) },
                             searchQuery: searchReturn?.peer == uuid ? searchReturn?.input.query : nil,
                             searchMoving: searchMove != nil,
                             onReturnToSearch: returnToSearchAction(for: uuid),
                             onMoveSearch: moveSearchAction(for: uuid),
                             onCloseSearch: {
                                 guard searchReturn?.peer == uuid else { return }
                                 searchMove?.cancel()
                                 searchReturn = nil
                             },
                             onMediaFullScreen: { fullScreen in
                                 if fullScreen {
                                     if columnsBeforeMedia == nil { columnsBeforeMedia = columnVisibility }
                                     columnVisibility = .detailOnly
                                 } else if let previous = columnsBeforeMedia {
                                     columnVisibility = previous
                                     columnsBeforeMedia = nil
                                 }
                             },
                             sidebarChatRequest: $sidebarChatRequest,
                             onOpenSidebarChat: { target in
                                 guard selectedConversationUuid == uuid, AppLockSession.shared.canShowContent else { return }
                                 selectedConversationUuid = target
                             },
                             searchCoversChat: searchCoversChat,
                             searchNavigation: searchNavigation,
                             onOpenSearchHit: revealSearchHit,
                             onKeepEditingAfterSearch: resumeSearch,
                             panelNavigation: panelNavigation,
                             onOpenPanelTarget: navigateToPanelTarget,
                             onKeepEditingAfterPanel: keepEditingInsteadOfPanelNavigation,
                             navigationPanelCoversChat: showSavedMessages || showForwardTasks || showCallHistory ||
                                 sendQueuePresented || contactsPresented,
                             notificationNavigation: searchNavigation == nil && panelNavigation == nil ? notificationNavigation : nil,
                             onOpenNotification: navigateToNotification,
                             onKeepEditingAfterNotification: dismissNotificationNavigation,
                             onOpenContactChat: { account in
                                 _ = try? requestPanelNavigation(peer: account)
                             })
                        .id(uuid)
                        .frame(minWidth: Self.minimumChatWidth)
                } else {
                    Text(interfaceText("select_chat"))
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .foregroundStyle(.secondary)
                    .background(.regularMaterial, in: Capsule())
                    .frame(minWidth: Self.minimumChatWidth, maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color(NSColor.textBackgroundColor))
                    .navigationTitle("MsgAT")
                }
            }
            .navigationSplitViewStyle(.balanced)
            CallOverlayView()
            NoticeView()
        }
        .background(Color(NSColor.windowBackgroundColor))
        .background(ModalCallLayer().allowsHitTesting(false))
        .frame(minWidth: 700, minHeight: 500)
        .sheet(isPresented: $showSearch, onDismiss: finishSearchDismissal) {
            let origin = searchPeer
            MessageSearchDialog(peer: origin, initial: searchInitial,
                                onClose: { if AppLockSession.shared.canShowContent { rememberSearch($0, for: origin) } }) { hit, input in
                guard AppLockSession.shared.canShowContent, searchHitIsAvailable(hit) else { return false }
                searchNavigation = ChatSearchNavigation(source: selectedConversationUuid, origin: origin, hit: hit, input: input)
                return true
            }
        }
        .onChange(of:selectedConversationUuid) {uuid in
            sidebarChatRequest = nil
            if searchNavigation?.source != uuid { searchNavigation = nil }
            if panelNavigation?.source != uuid { panelNavigation = nil }
            if let request = notificationNavigation { dismissNotificationNavigation(request.token) }
            searchMove?.cancel()
            if messageTarget?.peer != uuid { messageTarget = nil }
            if searchReturn?.peer != uuid { searchReturn = nil }
        }
        .onChange(of: showSearch) { shown in
            if shown { searchMove?.cancel() }
        }
        .onDisappear { searchMove?.cancel() }
        .sheet(isPresented: $showSavedMessages, onDismiss: { finishPanelDismissal(.savedMessages) }) {
            SavedMessagesDialog(onOriginal: {item in
                if (try? requestPanelNavigation(peer: item.sourcePeer, messageID: item.sourceId, panel: .savedMessages,
                    unavailable: "saved_original_unavailable")) != true {
                    NoticeCenter.shared.show(interfaceText("saved_original_unavailable"))
                }
            }, onOpenContactChat: { account in
                _ = try? requestPanelNavigation(peer: account, panel: .savedMessages)
            })
        }
        .sheet(isPresented: $showForwardTasks, onDismiss: { finishPanelDismissal(.forwarding) }) {
            ForwardTasksDialog { peer, id in
                try requestPanelNavigation(peer: peer, messageID: id, panel: .forwarding)
            }
        }
        .sheet(isPresented: $showCallHistory, onDismiss: { finishPanelDismissal(.callHistory) }) {
            CallHistoryView(onOpen: { entry in
                guard (try? requestPanelNavigation(peer: entry.peer, panel: .callHistory)) == true else { return }
                showCallHistory = false
            }, onCall: { entry in
                showCallHistory = false
                CallManager.shared.startOutgoingCall(peerUuid:entry.peer,video:entry.video)
            })
        }
        .onReceive(NotificationCenter.default.publisher(for: NotificationHelper.messageTargetChanged)) { _ in
            openNotificationMessage()
        }
        .onReceive(NotificationCenter.default.publisher(for: NotificationHelper.callAnswerRequested)) { _ in
            answerNotificationCall()
        }
        .onAppear {
            openNotificationMessage()
            answerNotificationCall()
        }
    }

    private func answerNotificationCall() {
        guard let id = NotificationHelper.takeCallAnswer() else { return }
        let call = CallManager.shared
        guard call.callState == .incomingRinging, call.currentCallId == id else { return }
        call.isMinimized = false
        call.acceptCall()
    }

    private func openQueuedMessage(peer: String, id: String?) throws -> Bool {
        try requestPanelNavigation(peer: peer, messageID: id, panel: .sendQueue, unavailable: "queue_stale")
    }

    private func openNotificationMessage() {
        guard let token = NotificationHelper.messageToken() else { return }
        guard let target = try? MessageNotificationStore.resolve(token) else {
            if NotificationHelper.consumeMessageToken(token) {
                notificationNavigation = nil
                NoticeCenter.shared.show(interfaceText("search_target_unavailable"))
            }
            return
        }
        let request = ChatNotificationNavigation(token: token, peer: target.peer, messageID: target.messageID)
        if searchNavigation == nil { showSearch = false }
        showSavedMessages = false
        showForwardTasks = false
        showCallHistory = false
        notificationNavigation = request
        if selectedConversationUuid == nil { navigateToNotification(request) }
    }

    private func dismissNotificationNavigation(_ token: String) {
        guard notificationNavigation?.token == token else { return }
        _ = NotificationHelper.consumeMessageToken(token)
        notificationNavigation = nil
    }

    private func navigateToNotification(_ request: ChatNotificationNavigation) {
        guard notificationNavigation == request, NotificationHelper.consumeMessageToken(request.token) else { return }
        notificationNavigation = nil
        guard let target = try? MessageNotificationStore.resolve(request.token),
              target.peer == request.peer, target.messageID == request.messageID else {
            NoticeCenter.shared.show(interfaceText("search_target_unavailable"))
            return
        }
        searchReturn = nil
        messageTarget = (request.peer, request.messageID)
        searchGeneration += 1
        selectedConversationUuid = request.peer
    }
}

private struct SidebarPlacement: NSViewRepresentable {
    func makeNSView(context: Context) -> Anchor { Anchor() }
    func updateNSView(_ nsView: Anchor, context: Context) {}

    final class Anchor: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard window != nil else { return }
            DispatchQueue.main.async { [weak self] in self?.configureSplitView() }
        }

        private func configureSplitView() {
            guard window != nil else { return }
            var ancestor = superview
            while let view = ancestor {
                if let split = view as? NSSplitView, split.isVertical, split.arrangedSubviews.count == 2 {
                    let name = "\(RuntimePaths.identifier).main-sidebar"
                    if split.autosaveName != name { split.autosaveName = name }
                    return
                }
                ancestor = view.superview
            }
        }
    }
}

struct MainWindowHost: NSViewRepresentable {
    func makeNSView(context: Context) -> HostView { HostView() }
    func updateNSView(_ nsView: HostView, context: Context) {}

    final class HostView: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            if let window {
                NotificationHelper.mainWindow = window
                window.sharingType = .none
            }
        }
    }
}
