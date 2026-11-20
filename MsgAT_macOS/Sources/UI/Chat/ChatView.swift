import SwiftUI
import AppKit
import ImageIO
import Combine
import UniformTypeIdentifiers


@MainActor
final class DroppedFileSelection {
    private var urls: [URL?]
    private var remaining: Int

    private init(count: Int) {
        urls = Array(repeating: nil, count: count)
        remaining = count
    }

    private func receive(_ url: URL?, at index: Int) -> [URL?]? {
        urls[index] = url
        remaining -= 1
        return remaining == 0 ? urls : nil
    }

    static func load(_ providers: [NSItemProvider], allowPartial: Bool = false,
                     completion: @escaping @MainActor @Sendable ([URL]?) -> Void) {
        guard !providers.isEmpty else { completion(nil); return }
        let selection = DroppedFileSelection(count: providers.count)
        for (index, provider) in providers.enumerated() {
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                let url = (item as? URL) ?? (item as? Data).flatMap { URL(dataRepresentation: $0, relativeTo: nil) }
                Task { @MainActor in
                    guard let ordered = selection.receive(url?.isFileURL == true ? url : nil, at: index) else { return }
                    let files = ordered.compactMap { $0 }
                    completion((files.count == ordered.count || allowPartial && !files.isEmpty) ? files : nil)
                }
            }
        }
    }
}

private enum ChatDropMode: Equatable { case media, file }
private enum ChatDroppedItem: Sendable { case file(URL), folder(URL) }
private enum HistoryPage: Equatable { case older, newer }
private struct HistoryLookup: Equatable {
    let peer: String
    let day: DateInterval?
}
private struct HeaderVerificationRequest: Hashable {
    let peer: String
    let revision: Int
}

private struct ChatFileDropDelegate: DropDelegate {
    let height: CGFloat
    let enabled: Bool
    let update: (ChatDropMode?) -> Void
    let accept: ([NSItemProvider], ChatDropMode) -> Bool

    private func mode(at point: CGPoint) -> ChatDropMode { point.y < height / 2 ? .media : .file }

    func validateDrop(info: DropInfo) -> Bool { enabled && info.hasItemsConforming(to: [UTType.fileURL]) }
    func dropEntered(info: DropInfo) { if enabled { update(mode(at: info.location)) } }
    func dropExited(info: DropInfo) { update(nil) }
    func dropUpdated(info: DropInfo) -> DropProposal? {
        update(enabled ? mode(at: info.location) : nil)
        return DropProposal(operation: enabled ? .copy : .cancel)
    }
    func performDrop(info: DropInfo) -> Bool {
        update(nil)
        guard enabled else { return false }
        return accept(info.itemProviders(for: [UTType.fileURL]), mode(at: info.location))
    }
}

private struct QuoteReturnAnchor: Equatable {
    let messageId: String
    let position: CGFloat
}

struct ChatHistoryPosition {
    let messageId: String
    let relativeOffset: CGFloat
}

public struct ChatView: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    @StateObject var viewModel: ChatViewModel
    @State private var callCoversChat = false
    @State private var focusBeforeCall = MediaPreviewFocus()
    @AppStorage(MsgATTheme.chatTextPreference) private var chatTextPercentage = 100

    @State private var showAttachmentMenu = false
    @State private var attachmentMaskHeld = false
    @State private var attachmentFilePicker: NSOpenPanel?
    @State private var hoveredAttachmentAction: Int?
    @FocusState private var attachmentToggleFocused: Bool
    @FocusState private var attachmentActionFocus: Int?
    @State private var showStickers = false
    @State private var stickerBusy = false
    @State private var stickerMaskHeld = false
    @State private var sharingContact: ContactCardSendRequest?
    @State private var contactCardMessage: ChatMessage?
    @State private var contactCardChatTarget: String?
    @State private var sharingLocation: LocationSendRequest?
    @State private var locationMessage: ChatMessage?
    @State private var showVoiceRecording=false
    @State private var capturedRecording:CapturedRecording?
    @State private var recordingReviewReady = false
    @State private var recordingDraft:ConversationDraft?
    @State private var recordingReply:ChatMessage?
    @State private var recordingOptions=SendOptions()
    @State private var showSendOptions=false
    @State private var startScheduled = false
    @State private var showSendQueue=false
    @State private var liveLocationStopFailed = false
    @State private var showHistoryNavigation=false
    @State private var showTimelineCalendar = false
    @State private var showSharedMedia = false
    @State private var sharedMediaStart = SharedMediaCategory.media
    @State private var showContactProfile = false
    @State private var verifiedPeer: String?
    @State private var verificationRevision = 0
    @State private var openMediaAfterContactProfile = false
    @State private var callAfterContactProfile: Bool?
    @State private var showExpiration = false
    @State private var showPinnedMessages=false
    @State private var historyDate=Date()
    @State private var historyNavigationError: String?
    @State private var historyLookup: HistoryLookup?
    @State private var initializedTimeline = false
    @State private var initialHistoryRevision = 0
    @State private var unreadTimelineAnchor: String?
    @State private var requestedHistoryMessage:String?
    @State private var requestedQuotePreview: String?
    @State private var openingHistoryQuote = false
    @State private var historyJumpGeneration=0
    @State private var historyPositionRevision = 0
    @State private var quoteReturnAnchors: [QuoteReturnAnchor] = []
    @State private var retryingLatestJump = false
    @State private var historyPageToRetry: HistoryPage?
    @State private var requestedQuoteOrigin: (target: String, anchor: QuoteReturnAnchor)?
    @State private var mediaConfirmation:MediaSendRequest?
    @ObservedObject private var client = NWClient.shared
    @ObservedObject private var liveLocations = LiveLocationCoordinator.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var viewportSize: CGSize = .zero
    @State private var viewportReflowing = false
    private var viewportHeight: CGFloat { viewportSize.height }
    @State private var chatHeight: CGFloat = 0
    @State private var chatWidth: CGFloat = 0
    @State private var messageFrames:[String:CGRect]=[:]
    @State private var floatingTimelineDay: Date?
    @State private var timelineDayAnchor: (id: String, offset: CGFloat)?
    @State private var timelineDayDeadline: ContinuousClock.Instant?
    @State private var timelineDayHideTask: Task<Void, Never>?
    private var inputText: String {
        get { viewModel.draftText }
        nonmutating set { viewModel.draftText = newValue }
    }
    private var hasTextToSend: Bool { !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var replyingTo: ChatMessage? {
        get { viewModel.draftReply }
        nonmutating set { viewModel.setReply(newValue) }
    }
    private struct ComposerAccessoryIdentity: Hashable {
        let messageID: String
        let editing: Bool
    }
    private var composerAccessoryIdentity: ComposerAccessoryIdentity? {
        (editingMessage ?? replyingTo).map {
            ComposerAccessoryIdentity(messageID: $0.id, editing: editingMessage != nil)
        }
    }
    @State private var quoteTarget: ChatMessage?
    @State private var highlightedMessageId: String? = nil
    @State private var highlightedQuote: String?
    @State private var highlightGeneration = 0
    @State private var pendingSendCancellation: String?
    private struct DeletionRequest {
        let ids: Set<String>
        let canRevoke: Bool
    }
    @State private var pendingLocalDeletion: DeletionRequest?
    @State private var checkingLocalDeletion = false
    @State private var checkingSendCancellation = false
    @State private var retryingSends: Set<String> = []
    @State private var editingMessage: ChatMessage? = nil
    @State private var editLoad: Task<Void, Never>?
    @State private var originalEditText = ""
    @State private var editingText = ""
    @State private var pendingEditExit: (proceed: () -> Void, keep: () -> Void)?
    @State private var confirmDiscardEdit = false
    @State private var savingEdit = false
    @State private var draftFocusRevision = 0
    @State private var editFocusRevision = 0
    @State private var selectedMedia: ChatMessage? = nil
    @StateObject private var mediaFullScreen = MediaWindowFullScreen()
    @State private var inlineAudioID: String?
    @State private var inlineAudio = MediaPlaybackModel()
    @State private var previewOrigin: MediaPreviewOrigin?
    @State private var previewFocus = MediaPreviewFocus()
    @State private var thumbnailFrames: [String: MediaThumbnailFrame] = [:]
    @State private var previewAlbum: [ChatMessage] = []
    @State private var previewAlbumStep = 0
    @State private var previewNeighbors: VisualMediaNeighbors?
    @State private var previewRefreshFailed = false
    @State private var previewRevision = 0
    @State private var previewMove: Task<Void, Never>?
    @State private var dropMode: ChatDropMode?
    @State private var pendingDrop: UUID?
    @State private var forwarding: ForwardMessageRequest?
    let initialMessageId: String?
    let searchGeneration: Int
    let onSearch: () -> Void
    let searchQuery: String?
    let searchMoving: Bool
    let onReturnToSearch: (() -> Void)?
    let onMoveSearch: ((Bool) -> Void)?
    let onCloseSearch: () -> Void
    private let onMediaFullScreen: (Bool) -> Void
    @Binding private var sidebarChatRequest: String?
    private let onOpenSidebarChat: (String) -> Void
    private let searchCoversChat: Bool
    private let searchNavigation: ChatSearchNavigation?
    private let onOpenSearchHit: (ChatSearchNavigation) -> Void
    private let onKeepEditingAfterSearch: (ChatSearchNavigation) -> Void
    private let panelNavigation: ChatPanelNavigation?
    private let onOpenPanelTarget: (ChatPanelNavigation) -> Void
    private let onKeepEditingAfterPanel: (ChatPanelNavigation) -> Void
    private let navigationPanelCoversChat: Bool
    private let notificationNavigation: ChatNotificationNavigation?
    private let onOpenNotification: (ChatNotificationNavigation) -> Void
    private let onKeepEditingAfterNotification: (String) -> Void
    let onOpenContactChat: (String) -> Void
    private let historyPosition: ChatHistoryPosition?
    private let onSaveHistoryPosition: (ChatHistoryPosition?) -> Void
    @State private var restoringHistory = false

    public init(targetUuid: String, initialMessageId: String? = nil, searchGeneration: Int = 0,
                onSearch: @escaping () -> Void = {}, onOpenContactChat: @escaping (String) -> Void = { _ in }) {
        self.init(targetUuid:targetUuid,initialMessageId:initialMessageId,searchGeneration:searchGeneration,
                  historyPosition:nil,onSaveHistoryPosition:{_ in},onSearch:onSearch,onOpenContactChat:onOpenContactChat)
    }

    init(targetUuid: String, initialMessageId: String?, searchGeneration: Int, historyPosition: ChatHistoryPosition?,
         onSaveHistoryPosition: @escaping (ChatHistoryPosition?) -> Void, onSearch: @escaping () -> Void,
         searchQuery: String? = nil, searchMoving: Bool = false,
         onReturnToSearch: (() -> Void)? = nil,
         onMoveSearch: ((Bool) -> Void)? = nil,
         onCloseSearch: @escaping () -> Void = {},
         onMediaFullScreen: @escaping (Bool) -> Void = { _ in },
         sidebarChatRequest: Binding<String?> = .constant(nil),
         onOpenSidebarChat: @escaping (String) -> Void = { _ in },
         searchCoversChat: Bool = false,
         searchNavigation: ChatSearchNavigation? = nil,
         onOpenSearchHit: @escaping (ChatSearchNavigation) -> Void = { _ in },
         onKeepEditingAfterSearch: @escaping (ChatSearchNavigation) -> Void = { _ in },
         panelNavigation: ChatPanelNavigation? = nil,
         onOpenPanelTarget: @escaping (ChatPanelNavigation) -> Void = { _ in },
         onKeepEditingAfterPanel: @escaping (ChatPanelNavigation) -> Void = { _ in },
         navigationPanelCoversChat: Bool = false,
         notificationNavigation: ChatNotificationNavigation? = nil,
         onOpenNotification: @escaping (ChatNotificationNavigation) -> Void = { _ in },
         onKeepEditingAfterNotification: @escaping (String) -> Void = { _ in },
         onOpenContactChat: @escaping (String) -> Void) {
        _viewModel = StateObject(wrappedValue: ChatViewModel(targetUuid: targetUuid))
        self.initialMessageId=initialMessageId
        self.searchGeneration=searchGeneration
        self.onSearch=onSearch
        self.searchQuery = searchQuery
        self.searchMoving = searchMoving
        self.onReturnToSearch = onReturnToSearch
        self.onMoveSearch = onMoveSearch
        self.onCloseSearch = onCloseSearch
        self.onMediaFullScreen = onMediaFullScreen
        self._sidebarChatRequest = sidebarChatRequest
        self.onOpenSidebarChat = onOpenSidebarChat
        self.searchCoversChat = searchCoversChat
        self.searchNavigation = searchNavigation
        self.onOpenSearchHit = onOpenSearchHit
        self.onKeepEditingAfterSearch = onKeepEditingAfterSearch
        self.panelNavigation = panelNavigation
        self.onOpenPanelTarget = onOpenPanelTarget
        self.onKeepEditingAfterPanel = onKeepEditingAfterPanel
        self.navigationPanelCoversChat = navigationPanelCoversChat
        self.notificationNavigation = notificationNavigation
        self.onOpenNotification = onOpenNotification
        self.onKeepEditingAfterNotification = onKeepEditingAfterNotification
        self.onOpenContactChat = onOpenContactChat
        self.historyPosition = historyPosition
        self.onSaveHistoryPosition = onSaveHistoryPosition
    }

    private var chatContentCovered: Bool {
        searchCoversChat || navigationPanelCoversChat || callCoversChat || showAttachmentMenu || attachmentMaskHeld || showStickers || stickerMaskHeld ||
            attachmentFilePicker != nil || selectedMedia != nil || showVoiceRecording || mediaConfirmation != nil || showSharedMedia || showContactProfile || showExpiration ||
            sharingContact != nil || contactCardMessage != nil || sharingLocation != nil || locationMessage != nil ||
            showHistoryNavigation || showTimelineCalendar || showPinnedMessages ||
            showSendOptions || showSendQueue || confirmDiscardEdit || quoteTarget != nil || pendingSendCancellation != nil
    }

    private var canOpenChatTarget: Bool {
        !chatContentCovered && !savingEdit && dropMode == nil && pendingDrop == nil
    }

    private var availableSearchNavigation: ChatSearchNavigation? {
        guard canOpenChatTarget, let request = searchNavigation, request.source == viewModel.targetUuid,
              request.hit.peer != viewModel.targetUuid else { return nil }
        return request
    }

    private var availableNotification: ChatNotificationNavigation? {
        canOpenChatTarget ? notificationNavigation : nil
    }

    private var availablePanelNavigation: ChatPanelNavigation? {
        guard canOpenChatTarget, searchNavigation == nil, let request = panelNavigation, request.ready,
              request.source == viewModel.targetUuid, request.peer != viewModel.targetUuid else { return nil }
        return request
    }

    private var composerHeightLimit: CGFloat {
        guard chatHeight > 0 else { return 96 }
        return max(36, min(224, chatHeight * 0.35))
    }

    private var compactChatToolbar: Bool { chatWidth > 0 && chatWidth < 480 }
    private var messagingColors: MessagingColors { MsgATTheme.messagingColors(for: colorScheme) }
    private var chatColumnInset: CGFloat { max(0, (chatWidth - 856) / 2) }
    private var maximumMessageBodyWidth: CGFloat {
        guard chatWidth > 0 else { return 408 }
        let reserved: CGFloat = viewModel.isSelecting ? 108 : 100
        return max(1, min(408, chatWidth - 2 * chatColumnInset - reserved))
    }

    public var body: some View {
        ZStack {
            VStack(spacing: 0) {
                if let share = liveLocations.shares[viewModel.targetUuid] {
                    HStack {
                        Label(interfaceText(liveLocations.collecting ? "live_location_active" : "live_location_paused"), systemImage: "location.fill")
                        Spacer()
                        Button(interfaceText("live_location_stop")) {
                            Task {
                                if !(await liveLocations.stop(peer: viewModel.targetUuid, messageID: share.messageID)) {
                                    liveLocationStopFailed = true
                                }
                            }
                        }
                    }
                    .font(.callout)
                    .padding(.horizontal, 16).padding(.vertical, 8)
                    .background(messagingColors.chrome)
                }
                if let pin=viewModel.pinnedMessage {
                    HStack {
                        Button {
                            showHistoryMessage(pin.id)
                        } label:{
                            HStack(spacing: 8) {
                                Capsule().fill(Color.accentColor).frame(width: 3, height: 28)
                                VStack(alignment:.leading,spacing:2){
                                    Text(interfaceText("pinned_message")).font(.caption).foregroundStyle(Color.accentColor)
                                    MessageSummaryText(text:pin.summary).lineLimit(1).frame(maxWidth:.infinity,alignment:.leading)
                                }
                            }.contentShape(Rectangle())
                        }.buttonStyle(.plain)
                        if viewModel.canCyclePinnedPreview {
                            Button { viewModel.cyclePinnedPreview() } label: {
                                Image(systemName: "chevron.down")
                                    .frame(width: 20, height: 22)
                            }
                            .help(interfaceText("next_pinned_message"))
                            .accessibilityLabel(interfaceText("next_pinned_message"))
                        }
                        Button { showPinnedMessages = true } label: {
                            Label(interfaceText("pinned_messages"), systemImage: "list.bullet")
                                .labelStyle(.iconOnly)
                        }
                        .help(interfaceText("pinned_messages"))
                    }.padding(.horizontal,16).padding(.vertical,8).background(Color(NSColor.controlBackgroundColor))
                }
                if let searchQuery, let onReturnToSearch, !viewModel.isSelecting {
                    HStack(spacing: 8) {
                        Button(action: onReturnToSearch) {
                            Label {
                                Text(searchQuery).lineLimit(1)
                            } icon: {
                                Image(systemName: "magnifyingglass")
                            }
                            .frame(maxWidth: .infinity, minHeight: 30, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .help(interfaceText("return_to_search_results"))
                        .accessibilityHint(interfaceText("return_to_search_results"))
                        if let onMoveSearch {
                            searchNavigationButton("chevron.up", title: "search_previous_result") { onMoveSearch(true) }
                                .keyboardShortcut("g", modifiers: [.command, .shift])
                                .disabled(searchMoving || restoringHistory || editingMessage != nil)
                            searchNavigationButton("chevron.down", title: "search_next_result") { onMoveSearch(false) }
                                .keyboardShortcut("g", modifiers: .command)
                                .disabled(searchMoving || restoringHistory || editingMessage != nil)
                        }
                        searchNavigationButton("xmark", title: "close_search_navigation", action: onCloseSearch)
                    }
                    .padding(.horizontal, 16).padding(.vertical, 4)
                    .background(Color(NSColor.controlBackgroundColor))
                    .disabled(chatContentCovered)
                }
                // Messages List
                ZStack(alignment: .bottomTrailing) {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(spacing: 0) {
                                if viewModel.hasMoreOlderMessages {
                                    Color.clear.frame(height: 1).onAppear {
                                        loadHistoryPage(.older, proxy: proxy)
                                    }
                                }

                                let albums = viewModel.displayedAlbums
                                let unreadRowID = unreadTimelineAnchor.map { albums[$0]?.first?.messageId ?? $0 }
                                ForEach(timelineEntries(albums: albums, unreadRowID: unreadRowID)) { entry in
                                    VStack(spacing: 0) {
                                        if let day = entry.day {
                                            timelineDateButton(day)
                                                .padding(.top, 8).padding(.bottom, 6)
                                        }
                                        if entry.message.messageId == unreadRowID {
                                            HStack(spacing: 8) {
                                                Rectangle().fill(Color.accentColor.opacity(0.35)).frame(height: 1)
                                                Text(interfaceText("unread_messages")).font(.caption.weight(.medium)).foregroundStyle(Color.accentColor)
                                                Rectangle().fill(Color.accentColor.opacity(0.35)).frame(height: 1)
                                            }
                                            .padding(.vertical, 7)
                                        }
                                        Group {
                                            if let album = albums[entry.message.messageId] {
                                                albumRow(album, anchor: entry.message)
                                            } else {
                                                messageRow(entry.message, joinsOlder: entry.joinsOlder, joinsNewer: entry.joinsNewer)
                                            }
                                        }
                                        .modifier(LiveMessageArrival(
                                            arrival: viewModel.recentMessageArrivals[entry.id],
                                            enabled: !reduceMotion && !restoringHistory && !chatContentCovered &&
                                                viewModel.isViewingLatest && !viewModel.isSelecting,
                                            isMine: entry.message.isMine))
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.bottom, entry.joinsNewer ? 2 : 7)
                                    .id("timeline-row/" + entry.id)
                                }

                                Color.clear
                                    .frame(height: 1)
                                    .background(GeometryReader { geometry in
                                        Color.clear.preference(key: TimelineBottomPreference.self,
                                            value: geometry.frame(in: .named("messageViewport")).maxY)
                                    })
                                    .id("BOTTOM_ANCHOR")
                            }
                            .padding(.horizontal, 16 + chatColumnInset)
                            .padding(.vertical, 8)
                        }
                        .coordinateSpace(name: "messageViewport")
                        .overlay {
                            if viewModel.messages.isEmpty && viewModel.historyLoaded && !viewModel.historyLoadFailed {
                                Text(interfaceText("no_messages_in_conversation"))
                                    .foregroundStyle(.secondary).allowsHitTesting(false)
                            } else if !viewModel.historyLoaded && !viewModel.historyLoadFailed {
                                ProgressView(interfaceText("loading"))
                                    .controlSize(.small).foregroundStyle(.secondary).allowsHitTesting(false)
                            }
                        }
                        .overlay(alignment: viewModel.messages.isEmpty ? .center : .top) {
                            if viewModel.historyLoadFailed {
                                VStack(spacing: 6) {
                                    Text(interfaceText("history_load_failed"))
                                    Button(interfaceText("retry")) {
                                        if retryingLatestJump { jumpToLatest() }
                                        else if let page = historyPageToRetry { loadHistoryPage(page, proxy: proxy) }
                                        else if !viewModel.historyLoaded { initialHistoryRevision += 1 }
                                        else { viewModel.loadMessages() }
                                    }
                                }
                                .padding(10)
                                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
                                .padding(.top, 8)
                            }
                        }
                        .background(GeometryReader { geometry in
                            Color.clear.onAppear { updateViewportSize(geometry.size) }
                                .onChange(of: geometry.size) { updateViewportSize($0) }
                        })
                        .onPreferenceChange(MessageFramePreference.self) { frames in
                            messageFrames = frames
                            updateReadVisibility()
                            updateFloatingTimelineDay(frames)
                        }
                        .onPreferenceChange(MediaThumbnailFramePreference.self) { thumbnailFrames = $0 }
                        .onPreferenceChange(TimelineBottomPreference.self) { bottom in
                            timelineBottom = bottom
                            updateViewingLatest()
                            loadNewerHistoryIfVisible(proxy: proxy)
                        }
                        .onChange(of: viewportSize) { _ in
                            if !restoringHistory && viewModel.isViewingLatest && !viewModel.hasMoreNewerMessages {
                                proxy.scrollTo("BOTTOM_ANCHOR", anchor: .bottom)
                            } else {
                                updateViewingLatest()
                            }
                            updateReadVisibility()
                        }
                        .task(id: viewportSize) {
                            guard viewportReflowing else { return }
                            do { try await Task.sleep(for: .milliseconds(80)) }
                            catch { return }
                            guard !Task.isCancelled else { return }
                            viewportReflowing = false
                            updateReadVisibility()
                        }
                        .onChange(of: viewModel.hasMoreNewerMessages) { _ in
                            updateViewingLatest()
                            loadNewerHistoryIfVisible(proxy: proxy)
                        }
                        .onChange(of: viewModel.historyLoadFailed) { failed in
                            if !failed { historyPageToRetry = nil }
                        }
                        .applyTimelineScrollAnchor(followingLatest: viewModel.isViewingLatest && !restoringHistory)
                        .task(id: initialHistoryRevision) {
                            guard !initializedTimeline else { return }
                            guard let position = await viewModel.loadInitialHistory(target: initialMessageId,
                                savedMessageID: historyPosition?.messageId), !Task.isCancelled else { return }
                            initializedTimeline = true
                            unreadTimelineAnchor = position.unread
                            if position.restoresSavedPosition, let target = position.target, let saved = historyPosition {
                                applyInitialHistoryPosition(target, proxy: proxy,
                                    anchor: UnitPoint(x: 0.5, y: saved.relativeOffset))
                                return
                            }
                            if let id = position.target {
                                highlightMessage(id)
                                applyInitialHistoryPosition(id, proxy: proxy)
                            } else {
                                if initialMessageId == nil && historyPosition != nil { onSaveHistoryPosition(nil) }
                                proxy.scrollTo("BOTTOM_ANCHOR", anchor: .bottom)
                            }
                        }
                        .onChange(of:historyJumpGeneration) {_ in
                            let origin = requestedQuoteOrigin
                            let excerpt = requestedQuotePreview
                            let openingQuote = openingHistoryQuote
                            requestedQuoteOrigin = nil
                            requestedQuotePreview = nil
                            openingHistoryQuote = false
                            retryingLatestJump = false
                            guard let id = requestedHistoryMessage else { return }
                            do {
                                guard let message = try MsgDao.shared.getMessageById(id),
                                      message.conversationUuid == viewModel.targetUuid,
                                      openingQuote || message.isRevoked == 0 else {
                                    NoticeCenter.shared.show(interfaceText(openingQuote ? "quoted_message_unavailable" : "search_target_unavailable"))
                                    return
                                }
                            } catch {
                                NoticeCenter.shared.show(interfaceText("history_load_failed"))
                                return
                            }
                            guard viewModel.revealMessage(id) else {
                                NoticeCenter.shared.show(interfaceText("history_load_failed"))
                                return
                            }
                            initializedTimeline = true
                            if let origin, origin.target == id, quoteReturnAnchors.last != origin.anchor {
                                if quoteReturnAnchors.count == 16 { quoteReturnAnchors.removeFirst() }
                                quoteReturnAnchors.append(origin.anchor)
                            }
                            viewModel.setViewingLatest(false)
                            highlightMessage(id, quote: excerpt)
                            applyInitialHistoryPosition(id, proxy: proxy, animated: true)
                        }
                        .onChange(of:searchGeneration) {_ in
                            guard let id=initialMessageId,viewModel.revealMessage(id) else {return}
                            initializedTimeline = true
                            highlightMessage(id)
                            applyInitialHistoryPosition(id, proxy: proxy)
                        }
                        .onChange(of: viewModel.followLatestRevision) { _ in
                            guard !restoringHistory else { return }
                            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
                                proxy.scrollTo("BOTTOM_ANCHOR", anchor: .bottom)
                            }
                        }
                        .onChange(of: viewModel.targetUuid) { _ in
                            historyPositionRevision += 1
                            restoringHistory = false
                            closeInlineAudio()
                            clearFloatingTimelineDay()
                            highlightMessage(nil)
                            pendingSendCancellation = nil
                            pendingLocalDeletion = nil
                            quoteReturnAnchors.removeAll()
                            retryingLatestJump = false
                            historyPageToRetry = nil
                            requestedQuoteOrigin = nil
                            if initialMessageId == nil && historyPosition == nil {
                                proxy.scrollTo("BOTTOM_ANCHOR", anchor: .bottom)
                            }
                        }
                        .overlay(alignment: .top) {
                            Group {
                                if let day = floatingTimelineDay, !viewModel.isViewingLatest,
                                   !viewModel.isSelecting, !chatContentCovered {
                                    timelineDateButton(day)
                                        .padding(.top, 8)
                                        .transition(.opacity)
                                }
                            }
                            .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: floatingTimelineDay != nil)
                        }
                        .overlay(alignment: .bottomTrailing) {
                            HStack(spacing: 8) {
                            if !quoteReturnAnchors.isEmpty && !viewModel.isSelecting && !chatContentCovered && !restoringHistory {
                                Button { returnFromQuote(proxy: proxy) } label: {
                                    Image(systemName: "arrow.backward").font(.system(size: 16, weight: .semibold))
                                        .frame(width: 40, height: 40).background(.regularMaterial, in: Circle())
                                }
                                .buttonStyle(.plain)
                                .help(interfaceText("return_to_position"))
                                .accessibilityLabel(interfaceText("return_to_position"))
                            }
                            if !viewModel.isViewingLatest && !viewModel.messages.isEmpty &&
                                !viewModel.isSelecting && !chatContentCovered {
                                let unread = viewModel.unreadCount
                                Button(action: jumpToLatest) {
                                    Image(systemName: "chevron.down").font(.system(size: 17, weight: .semibold)).foregroundColor(.primary)
                                        .frame(width: 40, height: 40).background(.regularMaterial, in: Circle())
                                        .overlay(alignment: .top) {
                                            if unread > 0 {
                                                Text(unread > 99 ? "99+" : "\(unread)").font(.caption2.bold()).foregroundStyle(.white)
                                                    .padding(.horizontal, 5).frame(minWidth: 18, minHeight: 18)
                                                    .background(.red, in: Capsule()).offset(y: -7).accessibilityHidden(true)
                                            }
                                        }
                                }.buttonStyle(.plain).help(interfaceText("go_to_latest"))
                                    .accessibilityLabel(unread > 0 ? String(format: interfaceText("go_to_latest_with_unread"), Int64(unread))
                                                                : interfaceText("go_to_latest"))
                                    .transition(.opacity.combined(with: .offset(y: 8)))
                            }
                            }
                            .padding(.trailing, 16 + chatColumnInset).padding(.bottom, 16)
                            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: viewModel.isViewingLatest)
                        }
                    }
                }

                .environment(\.stickerPlaybackPaused, showStickers || stickerMaskHeld)
                .overlay {
                    if showStickers || stickerMaskHeld {
                        Color.clear.contentShape(Rectangle()).onTapGesture { closeStickers() }
                            .accessibilityHidden(true)
                    } else if showAttachmentMenu || attachmentMaskHeld {
                        Color.black.opacity(showAttachmentMenu ? 0.16 : 0.001)
                            .contentShape(Rectangle()).onTapGesture { closeAttachmentMenu() }
                            .accessibilityHidden(true)
                            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: showAttachmentMenu)
                    }
                }

                if editingMessage == nil {
                    LinkPreviewComposer(model: viewModel).padding(.horizontal, chatColumnInset)
                }
                VStack(spacing: 0) {
                    if let target = editingMessage ?? replyingTo, let identity = composerAccessoryIdentity {
                        composerAccessory(for: target, identity: identity)
                            .id(identity)
                            .transition(reduceMotion ? .identity : .opacity.combined(with: .offset(y: 7)))
                    }
                }
                .accessibilityElement(children: .contain)
                .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: composerAccessoryIdentity)
                .padding(.horizontal, chatColumnInset)

                if !viewModel.pendingPreparations.isEmpty && !showStickers {
                    PreparationRows(rows:viewModel.pendingPreparations,onCancel:viewModel.cancelPreparation)
                        .padding(.horizontal, chatColumnInset)
                }
                if editingMessage == nil && viewModel.sendOptions != SendOptions() {
                    HStack {
                        Text(sendOptionsLabel(viewModel.sendOptions)).font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button { viewModel.sendOptions = SendOptions() } label: {
                            Label(interfaceText("clear_send_options"), systemImage: "xmark").labelStyle(.iconOnly)
                        }.buttonStyle(.borderless).help(interfaceText("clear_send_options"))
                    }.padding(.horizontal, 12 + chatColumnInset).padding(.top,6)
                }
                HStack(alignment: .bottom, spacing: 8) {
                    HStack(alignment: .bottom, spacing: 4) {
                        Button {
                            if showAttachmentMenu { closeAttachmentMenu() }
                            else {
                                closeStickers(restoreFocus: false)
                                showAttachmentMenu = true
                            }
                        } label: {
                            Image(systemName: "plus")
                                .frame(width: 28, height: 28)
                                .rotationEffect(.degrees(showAttachmentMenu ? 45 : 0))
                                .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: showAttachmentMenu)
                        }
                        .buttonStyle(.borderless)
                        .help(interfaceText(showAttachmentMenu ? "close_attachments" : "add_attachments"))
                        .accessibilityLabel(interfaceText(showAttachmentMenu ? "close_attachments" : "add_attachments"))
                        .focused($attachmentToggleFocused)
                        .disabled(!viewModel.canCompose || editingMessage != nil || stickerBusy)
                        ZStack(alignment: .topLeading) {
                            ImagePasteComposer(text: $viewModel.draftText, onPasteImage: pasteClipboardImage, onSubmit: { handleSend() },
                                               onTyping: { viewModel.syncChatActivity(typing: $0) },
                                               onHistoryCommand: handleComposerHistory, focusRevision: draftFocusRevision,
                                               accessibilityTitle: interfaceText("message_placeholder"),
                                               maximumHeight: composerHeightLimit)
                                .frame(height: editingMessage == nil ? nil : 0)
                                .opacity(editingMessage == nil ? 1 : 0)
                                .accessibilityHidden(editingMessage != nil)
                                .disabled(!viewModel.canCompose || editingMessage != nil || stickerBusy)
                            if let target = editingMessage {
                                ImagePasteComposer(text: $editingText, onPasteImage: { false }, onSubmit: saveEdit,
                                                   onTyping: { _ in }, onHistoryCommand: { command in
                                                       guard command == .cancelAccessory, !savingEdit else { return false }
                                                       requestEditExit { cancelEdit() }
                                                       return true
                                                   }, focusRevision: editFocusRevision,
                                                   accessibilityTitle: interfaceText("edit_message"),
                                                   readOnly: savingEdit,
                                                   maximumHeight: composerHeightLimit)
                                    .id(target.messageId)
                                    .disabled(!viewModel.canCompose)
                            } else if inputText.isEmpty {
                                Text(interfaceText("message_placeholder")).foregroundStyle(.secondary)
                                    .padding(.horizontal, 10).padding(.vertical, 8).allowsHitTesting(false)
                            }
                        }
                        .frame(minHeight: 36)
                        .onChange(of: inputText) {
                            if !$0.isEmpty { showAttachmentMenu = false }
                            if showStickers && !stickerBusy { closeStickers(restoreFocus: false) }
                        }
                        if editingMessage == nil {
                            Button {
                                if showStickers { closeStickers() }
                                else { openStickers() }
                            } label: {
                                Image(systemName: "face.smiling").font(.system(size: 17)).frame(width: 25, height: 28)
                                    .foregroundStyle(showStickers ? Color.accentColor : Color.secondary)
                            }
                            .buttonStyle(.borderless)
                            .help(interfaceText(showStickers ? "sticker_close_panel" : "stickers"))
                            .accessibilityLabel(interfaceText(showStickers ? "sticker_close_panel" : "stickers"))
                            .disabled(!viewModel.canCompose || stickerBusy)
                        }
                    }
                    .padding(.horizontal, 4)
                    .background(messagingColors.timeline, in: RoundedRectangle(cornerRadius: 16))
                    .overlay {
                        RoundedRectangle(cornerRadius: 16).strokeBorder(messagingColors.divider).allowsHitTesting(false)
                    }
                    Button {
                        if editingMessage != nil { saveEdit() }
                        else if hasTextToSend {
                            draftFocusRevision += 1
                            handleSend()
                        }
                        else { beginRecording() }
                    } label: {
                        ZStack {
                            if composerActionIsPending {
                                if reduceMotion { Image(systemName: "clock") }
                                else { ProgressView().controlSize(.small) }
                            } else {
                                Image(systemName: composerActionSymbol)
                                    .font(.system(size: 18)).id(composerActionSymbol)
                                    .transition(.scale(scale: 0.8).combined(with: .opacity))
                            }
                        }
                            .frame(width: 36, height: 36)
                            .foregroundStyle(hasTextToSend || editingMessage != nil ? messagingColors.selectionMark : Color.secondary)
                            .background(hasTextToSend || editingMessage != nil
                                        ? messagingColors.selection : messagingColors.timeline,
                                        in: RoundedRectangle(cornerRadius: 12))
                            .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: composerActionSymbol)
                    }
                    .buttonStyle(.plain).help(composerActionTitle)
                    .accessibilityLabel(composerActionTitle)
                    .contextMenu {
                        if editingMessage == nil && hasTextToSend && !viewModel.textSendPending {
                            Button(interfaceText("send_without_sound")) {
                                draftFocusRevision += 1
                                var options = viewModel.sendOptions
                                options.silent = true
                                handleSend(options: options)
                            }
                            Button(interfaceText("schedule_next_message")) {
                                closeStickers(restoreFocus: false)
                                startScheduled = true
                                showSendOptions = true
                            }
                        }
                    }
                    .disabled(!viewModel.canCompose || stickerBusy || composerActionIsPending ||
                        (editingMessage != nil && !canSaveEdit))
                }
                .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 12)
                .overlay(alignment: .topLeading) {
                    if showAttachmentMenu && viewModel.canCompose {
                        attachmentMenu
                            .padding(.leading, 8)
                            .alignmentGuide(.top) { $0[.bottom] + 6 }
                            .transition(.offset(y: 8).combined(with: .opacity))
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if showStickers {
                        StickerLibraryView(compact: true,
                            preparations: viewModel.pendingPreparations.filter(\.isSticker),
                            onCancelPreparation: viewModel.cancelPreparation,
                            onSend: { item in
                                guard viewModel.isChatMounted, viewModel.canCompose, AppLockSession.shared.canShowContent else { return .failed }
                                return await viewModel.sendSticker(item, draft: viewModel.captureMediaDraft(), reply: replyingTo)
                            },
                            onClose: { closeStickers() },
                            onBusyChange: { stickerBusy = $0 })
                        .frame(width: min(364, max(240, chatWidth - 22)),
                               height: min(470, max(212, chatHeight * 0.67)))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.24)).allowsHitTesting(false))
                        .shadow(color: .black.opacity(0.19), radius: 10, y: 3)
                        .padding(.trailing, 10)
                        .alignmentGuide(.top) { $0[.bottom] + 6 }
                        .transition(.offset(y: 8).combined(with: .opacity))
                    }
                }
                .animation(reduceMotion ? nil : .timingCurve(0.25, 0.8, 0.25, 1, duration: 0.18), value: showAttachmentMenu)
                .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: showStickers)
                .padding(.horizontal, chatColumnInset)
                .background(messagingColors.chrome)
                .overlay(alignment: .top) {
                    Rectangle()
                        .fill(messagingColors.divider)
                        .frame(height: 1)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
            .background {
                GeometryReader { geometry in
                    Color.clear
                        .onAppear { chatHeight = geometry.size.height; chatWidth = geometry.size.width }
                        .onChange(of: geometry.size) { chatHeight = $0.height; chatWidth = $0.width }
                }
            }
            .background(messagingColors.timeline)
            .onDrop(of: [.fileURL], delegate: ChatFileDropDelegate(height: chatHeight, enabled: canAcceptFileDrop,
                update: updateDropMode, accept: { handleFileDrop(providers: $0, asFile: $1 == .file) }))
            .overlay {
                if pendingDrop != nil, attachmentFilePicker == nil {
                    ProgressView(interfaceText("preparing_dropped_files"))
                        .padding(20)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                        .transition(.opacity)
                        .allowsHitTesting(false)
                } else if let dropMode, canAcceptFileDrop {
                    VStack(spacing: 8) {
                        dropRegion("drop_as_media", icon: "photo.on.rectangle", active: dropMode == .media)
                        dropRegion("drop_as_file", icon: "doc", active: dropMode == .file)
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .transition(.opacity)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                }
            }
            .onChange(of: canAcceptFileDrop) { if !$0 { dropMode = nil } }

            .disabled(selectedMedia != nil)
            .allowsHitTesting(selectedMedia == nil)
            .accessibilityHidden(selectedMedia != nil)

            // In-App Media Viewer
            if let media = selectedMedia {
                InAppMediaViewer(message: media, navigation: previewNavigation(media), origin: previewOrigin,
                                 returnFrame: mediaFullScreen.active ? nil : previewReturnFrame(media.messageId),
                                 albumStep: previewAlbumStep,
                                 fullScreen: mediaFullScreen.active,
                                 toggleFullScreen: {
                                     previewOrigin = nil
                                     mediaFullScreen.toggle(in: NotificationHelper.mainWindow)
                                 }, onShowMessage: {
                    closeMedia(restoreFocus: false)
                    showHistoryMessage(media.messageId)
                }, onForward: {
                    closeMedia(restoreFocus: false)
                    forwardMessages([media.messageId])
                }, onReply: viewModel.canCompose && editingMessage == nil ? {
                    guard AppLockSession.shared.canShowContent, selectedMedia?.messageId == media.messageId,
                          viewModel.canCompose, editingMessage == nil,
                          let target = try? MsgDao.shared.getMessageById(media.messageId), target.isRevoked == 0,
                          target.conversationUuid == viewModel.targetUuid else { return }
                    closeMedia(restoreFocus: false)
                    replyingTo = target
                    draftFocusRevision += 1
                } : nil, onOpenSharedMedia: { metadata in
                    guard AppLockSession.shared.canShowContent, selectedMedia?.messageId == media.messageId else { return }
                    closeMedia(restoreFocus: false)
                    openSharedMedia(metadata?.isAudio == true ? .audio : .media)
                }, onRetryRefresh: previewRefreshFailed ? { previewRevision += 1 } : nil,
                   onCloseStarted: { previewMove?.cancel() }, onDismiss: {
                    if selectedMedia?.messageId == media.messageId { closeMedia() }
                })
                    .id(media.messageId)
            }
        }
        .coordinateSpace(name: MediaPreviewCoordinateSpace.chat)
        .toolbar(mediaFullScreen.active ? .hidden : .automatic, for: .windowToolbar)
        .onChange(of: mediaFullScreen.active) { onMediaFullScreen($0) }
        .onReceive(NotificationCenter.default.publisher(for: NSWindow.didExitFullScreenNotification)) { mediaFullScreen.didExit($0.object as? NSWindow) }
        .navigationTitle("")
        .onChange(of: historyDate) { _ in clearHistoryLookup() }
        .task(id: historyLookup) { await performHistoryLookup() }
        .sheet(isPresented: $showHistoryNavigation, onDismiss: clearHistoryLookup) {
            historyNavigationDialog
        }
        .sheet(isPresented: $showTimelineCalendar, onDismiss: clearHistoryLookup) {
            VStack(alignment: .leading, spacing: 16) {
                Text(interfaceText("jump_to_date")).font(.headline)
                DatePicker(interfaceText("jump_to_date"), selection: $historyDate, displayedComponents: .date)
                    .datePickerStyle(.graphical).labelsHidden()
                historyNavigationStatus
                HStack {
                    Spacer()
                    Button(interfaceText("cancel")) { showTimelineCalendar = false }.keyboardShortcut(.cancelAction)
                    Button(interfaceText("jump")) { navigateHistory(unread: false) }
                        .buttonStyle(.borderedProminent).keyboardShortcut(.defaultAction).disabled(historyLookup != nil)
                }
            }.padding(20).frame(width: 320).background(messagingColors.chrome)
        }
        .sheet(isPresented: $showExpiration) { MessageExpirationView(peer: viewModel.targetUuid) }
        .sheet(isPresented: $showContactProfile, onDismiss: {
            if openMediaAfterContactProfile {
                openMediaAfterContactProfile = false
                openSharedMedia()
            }
            if let video = callAfterContactProfile {
                callAfterContactProfile = nil
                if viewModel.isChatMounted && viewModel.canCompose {
                    CallManager.shared.startOutgoingCall(peerUuid: viewModel.targetUuid, video: video)
                }
            }
        }) {
            ContactsView(focusedPeer: viewModel.targetUuid, onSharedMedia: {
                openMediaAfterContactProfile = true
            }, onCall: { callAfterContactProfile = $0 }, muteUntil: viewModel.muteUntil,
            onChangeNotifications: { action, until in
                SecureMessaging.shared.changeConversation(viewModel.targetUuid, action: action, until: until)
            }, onOpen: { _ in })
        }
        .sheet(isPresented: $showSharedMedia) {
            SharedMediaView(peer: viewModel.targetUuid, initialCategory: sharedMediaStart, onJump: { id in
                showSharedMedia = false
                showHistoryMessage(id)
            }, onForward: { ids in
                showSharedMedia = false
                DispatchQueue.main.async { forwardMessages(ids) }
            })
        }
        .sheet(isPresented:$showPinnedMessages) {PinnedMessagesView(peer:viewModel.targetUuid){id in
            showHistoryMessage(id)
            showPinnedMessages = false
        }}
        .confirmationDialog(interfaceText("send_cancel_title"), isPresented: Binding(
            get: { pendingSendCancellation != nil },
            set: { if !$0 { pendingSendCancellation = nil } }
        ), titleVisibility: .visible, presenting: pendingSendCancellation) { id in
            Button(interfaceText("send_cancel_confirm"), role: .destructive) {
                pendingSendCancellation = nil
                let peer = viewModel.targetUuid
                guard viewModel.isChatMounted, AppLockSession.shared.canShowContent else { return }
                SecureMessaging.shared.manageSend(id, action: "cancel", expectedPeer: peer) { ok in
                    guard !ok else { return }
                    Task { @MainActor in
                        guard viewModel.isChatMounted, viewModel.targetUuid == peer,
                              AppLockSession.shared.canShowContent else { return }
                        NoticeCenter.shared.show(interfaceText("send_cancel_unavailable"))
                    }
                }
            }
            Button(interfaceText("send_cancel_keep"), role: .cancel) { pendingSendCancellation = nil }
        } message: { _ in
            Text(interfaceText("send_cancel_message"))
        }
        .alert(interfaceText(pendingLocalDeletion?.canRevoke == true ? "delete_messages_title" : "local_delete_title"), isPresented: Binding(
            get: { pendingLocalDeletion != nil }, set: { if !$0 { pendingLocalDeletion = nil } }
        ), presenting: pendingLocalDeletion) { request in
            Button(interfaceText("local_delete"), role: .destructive) { viewModel.deleteMessages(request.ids) }
            if request.canRevoke {
                Button(interfaceText("revoke_selected"), role: .destructive) {
                    viewModel.deleteMessages(request.ids, revokeForBoth: true)
                }
            }
            Button(interfaceText("cancel"), role: .cancel) { pendingLocalDeletion = nil }
        } message: { request in
            Text(interfaceText(request.canRevoke ? "revoke_selected_choice" : "local_delete_message"))
        }
        .task(id: highlightGeneration) {
            guard highlightedMessageId != nil else { return }
            let generation = highlightGeneration
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if !Task.isCancelled, generation == highlightGeneration {
                highlightedMessageId = nil
                highlightedQuote = nil
            }
        }
        .onAppear {
            viewModel.isChatMounted = true
            if viewModel.historyLoaded { viewModel.loadMessages() }
        }
        .onDisappear {
            saveHistoryPosition()
            viewModel.isChatMounted = false
            pendingDrop = nil
            dropMode = nil
            let picker = attachmentFilePicker
            attachmentFilePicker = nil
            picker?.cancel(nil)
            editLoad?.cancel()
            editLoad = nil
            clearFloatingTimelineDay()
        }
        .toolbar {
            if viewModel.isSelecting {
                ToolbarItem(placement: .primaryAction) {
                    let selectionCanBeKept = !viewModel.messages.contains {
                        viewModel.selectedMessages.contains($0.id) && $0.expiresAt != nil
                    }
                    HStack(spacing:12) {
                        Button {
                            let version = viewModel.selectionVersion
                            let ids = Array(viewModel.selectedMessages)
                            Task {
                                await MessageTextClipboard.copySelection(peer: viewModel.targetUuid, ids: ids,
                                    strings: interfaceText) {
                                    viewModel.isChatMounted && viewModel.selectionVersion == version
                                }
                            }
                        } label: { Image(systemName: "doc.on.doc") }
                            .help(interfaceText("copy_selected_messages"))
                            .accessibilityLabel(interfaceText("copy_selected_messages"))
                            .keyboardShortcut("c", modifiers: .command)
                            .disabled(viewModel.savingSelection)
                        Button {forwardMessages(Array(viewModel.selectedMessages))} label:{Image(systemName:"arrowshape.turn.up.right")}
                            .help(interfaceText("forward_selected_messages")).accessibilityLabel(interfaceText("forward_selected_messages"))
                            .disabled(viewModel.savingSelection || !selectionCanBeKept)
                        Button {viewModel.saveMessages(Array(viewModel.selectedMessages))} label:{Image(systemName:"bookmark")}
                            .help(interfaceText("save_selected_messages")).accessibilityLabel(interfaceText("save_selected_messages"))
                            .disabled(viewModel.savingSelection || !selectionCanBeKept)
                        Button { requestLocalDeletion(viewModel.selectedMessages) } label: { Image(systemName: "trash") }
                            .help(interfaceText("delete_messages")).accessibilityLabel(interfaceText("delete_messages"))
                            .disabled(viewModel.savingSelection)
                        Button {viewModel.clearSelection()} label:{Image(systemName:"xmark")}
                            .help(interfaceText("cancel_message_selection")).accessibilityLabel(interfaceText("cancel_message_selection"))
                            .disabled(viewModel.savingSelection)
                    }
                }
            } else {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        CallManager.shared.startOutgoingCall(peerUuid: viewModel.targetUuid)
                    } label: {
                        Image(systemName: "phone")
                    }.help(interfaceText("voice_call")).accessibilityLabel(interfaceText("voice_call")).disabled(!viewModel.canCompose)
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        CallManager.shared.startOutgoingCall(peerUuid: viewModel.targetUuid, video: true)
                    } label: {
                        Image(systemName: "video")
                    }.help(interfaceText("video_call")).accessibilityLabel(interfaceText("video_call")).disabled(!viewModel.canCompose)
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(action: onSearch) {
                        Image(systemName: "magnifyingglass")
                    }.help(interfaceText("search_messages")).accessibilityLabel(interfaceText("search_messages")).keyboardShortcut("f", modifiers: .command)
                }
                if !compactChatToolbar {
                    ToolbarItem(placement: .primaryAction) {
                        ConversationMuteMenu(muteUntil: viewModel.muteUntil, onChange: changeMute)
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        if compactChatToolbar {
                            Menu(interfaceText("conversation_notifications")) {
                                ConversationMuteMenuItems(muteUntil: viewModel.muteUntil, onChange: changeMute)
                            }
                            Divider()
                        }
                        Button { showHistoryNavigation = true } label: { Label(interfaceText("history_navigation"), systemImage: "calendar") }
                        Button { showSendQueue = true } label: { Label(interfaceText("sending_queue"), systemImage: "clock") }
                        Button {
                            if showAttachmentMenu { closeAttachmentMenu() }
                            closeStickers(restoreFocus: false)
                            startScheduled = false
                            showSendOptions = true
                        } label: {
                            Label(interfaceText("send_options"), systemImage: "slider.horizontal.3")
                        }.disabled(!viewModel.canCompose || editingMessage != nil || stickerBusy)
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .menuIndicator(.hidden)
                    .help(interfaceText("chat_actions"))
                    .accessibilityLabel(interfaceText("chat_actions"))
                }
            }
            if #available(macOS 26.0, *) {
                ToolbarItem(placement: .navigation) { conversationTitle }
                    .sharedBackgroundVisibility(.hidden)
            } else {
                ToolbarItem(placement: .navigation) { conversationTitle }
            }
        }
        .onChange(of: sidebarChatRequest) { target in
            guard let target else { return }
            sidebarChatRequest = nil
            guard !chatContentCovered else { return }
            requestEditExit { onOpenSidebarChat(target) }
        }
        .onReceive(NotificationCenter.default.publisher(for: ContactDirectoryStore.changed).receive(on: RunLoop.main)) { _ in
            verifiedPeer = nil
            verificationRevision &+= 1
        }
        .task(id: HeaderVerificationRequest(peer: viewModel.targetUuid, revision: verificationRevision)) {
            let peer = viewModel.targetUuid
            let revision = verificationRevision
            guard AppLockSession.shared.canShowContent else { return }
            let verified = (try? await EncryptedMediaWork.run { try ContactDirectoryStore.isVerified(peer) }) == true
            guard !Task.isCancelled, AppLockSession.shared.canShowContent,
                  viewModel.targetUuid == peer, verificationRevision == revision else { return }
            verifiedPeer = verified ? peer : nil
        }
        .task(id: availableSearchNavigation?.id) {
            guard !Task.isCancelled, let request = availableSearchNavigation else { return }
            requestEditExit(onKeep: { onKeepEditingAfterSearch(request) }) { onOpenSearchHit(request) }
        }
        .task(id: availablePanelNavigation?.id) {
            guard !Task.isCancelled, let request = availablePanelNavigation else { return }
            requestEditExit(onKeep: { onKeepEditingAfterPanel(request) }) { onOpenPanelTarget(request) }
        }
        .task(id: availableNotification) {
            guard !Task.isCancelled, let request = availableNotification else { return }
            if request.peer == viewModel.targetUuid {
                onOpenNotification(request)
            } else {
                requestEditExit(onKeep: { onKeepEditingAfterNotification(request.token) }) {
                    onOpenNotification(request)
                }
            }
        }
        .onChange(of: selectedMedia?.messageId) { id in
            if id == nil { viewModel.loadMessages() }
        }
        .task(id: TimelineMediaKey(id: selectedMedia?.messageId, reference: selectedMedia?.content,
                                  inAlbum: previewAlbum.count > 1, revision: previewRevision)) {
            await refreshMediaNeighbors()
        }
        .onReceive(NotificationCenter.default.publisher(for: MsgDao.didChangeNotification)
            .throttle(for: .milliseconds(160), scheduler: RunLoop.main, latest: true)) { _ in
                if selectedMedia != nil { previewRevision += 1 }
                if let id = inlineAudioID {
                    let peer = viewModel.targetUuid
                    Task { @MainActor in
                        let item = try? await EncryptedMediaWork.run {
                            try EncryptedObjectStore.shared.sharedMediaItem(peer: peer, messageId: id)
                        }
                        if inlineAudioID == id, item?.attachment.available != true { closeInlineAudio() }
                    }
                }
            }
        .onDisappear {
            pendingEditExit = nil
            confirmDiscardEdit = false
            capturedRecording = nil
            recordingReviewReady = false
            recordingDraft = nil
            recordingReply = nil
            focusBeforeCall.clear()
            previewMove?.cancel()
            closeInlineAudio()
            mediaFullScreen.end()
            onMediaFullScreen(false)
        }
        .onChange(of: chatContentCovered || dropMode != nil || pendingDrop != nil) { covered in
            viewModel.isMediaPresented = covered
            if covered { closeInlineAudio() }
            else { reviewCapturedRecording() }
        }
        .onReceive(CallManager.shared.$callState.combineLatest(CallManager.shared.$isMinimized)
            .map { state, minimized in state != .idle && !minimized }
            .removeDuplicates()) { covered in
            guard callCoversChat != covered else { return }
            callCoversChat = covered
            if covered {
                pendingEditExit = nil
                confirmDiscardEdit = false
                let window = NotificationHelper.mainWindow
                focusBeforeCall.capture(in: window)
                window?.makeFirstResponder(nil)
                showAttachmentMenu = false
            } else if !chatContentCovered, viewModel.isChatMounted {
                focusBeforeCall.restore()
            } else {
                focusBeforeCall.clear()
            }
            if !covered { reviewCapturedRecording() }
        }
        .onChange(of: canFinishFileDrop) { eligible in
            if !eligible, attachmentFilePicker == nil { pendingDrop = nil }
        }
        .onReceive(CallManager.shared.$callState) { state in
            if state != .idle && state != .ended {
                closeInlineAudio()
                closeStickers(restoreFocus: false, force: true)
            } else if recordingReviewReady {
                Task { @MainActor in reviewCapturedRecording() }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            reviewCapturedRecording()
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)) { _ in
            closeInlineAudio()
        }
        .onChange(of: viewModel.canCompose) {
            if !$0 {
                showAttachmentMenu = false
                closeStickers(restoreFocus: false, force: true)
                quoteTarget = nil
                let picker = attachmentFilePicker
                attachmentFilePicker = nil
                picker?.cancel(nil)
            }
        }
        .task(id: showAttachmentMenu) {
            if showAttachmentMenu {
                attachmentMaskHeld = true
                await Task.yield()
                if !Task.isCancelled && showAttachmentMenu { attachmentActionFocus = 0 }
            } else if attachmentMaskHeld {
                hoveredAttachmentAction = nil
                attachmentActionFocus = nil
                if !reduceMotion { try? await Task.sleep(nanoseconds: 180_000_000) }
                if !Task.isCancelled { attachmentMaskHeld = false }
            }
        }
        .task(id: showStickers) {
            if !showStickers && stickerMaskHeld {
                if !reduceMotion { try? await Task.sleep(nanoseconds: 180_000_000) }
                if !Task.isCancelled { stickerMaskHeld = false }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: SecureMessaging.attachmentCacheChanged)) { _ in
            if selectedMedia?.isMine == false { closeMedia() }
            closeInlineAudio()
        }
        .onReceive(NotificationCenter.default.publisher(for: SecureMessaging.messagesRemoved)) { notice in
            guard let ids = notice.object as? Set<String> else { return }
            viewModel.removeSelectedMessages(ids)
            removeFromMediaPreview(ids)
            if let id = inlineAudioID, ids.contains(id) { closeInlineAudio() }
            if let editingMessage, ids.contains(editingMessage.messageId) {
                cancelEdit()
            }
            if let replyingTo, ids.contains(replyingTo.messageId) { self.replyingTo = nil }
            if let quoteTarget, ids.contains(quoteTarget.messageId) { self.quoteTarget = nil }
        }
        .onChange(of: viewModel.messages) { messages in
            if selectedMedia != nil {
                removeFromMediaPreview(Set(messages.lazy.filter { $0.isRevoked != 0 }.map(\.messageId)))
            }
            if let edit = editingMessage, messages.contains(where: { $0.messageId == edit.messageId && $0.isRevoked != 0 }) {
                cancelEdit()
            }
            if let reply = replyingTo, messages.contains(where: {
                $0.messageId == reply.messageId && ($0.isRevoked != 0 || viewModel.draftQuote != nil && $0.content != reply.content)
            }) {
                replyingTo = nil
            }
            if let quoteTarget, messages.contains(where: { $0.messageId == quoteTarget.messageId && ($0.isRevoked != 0 || $0.content != quoteTarget.content) }) {
                self.quoteTarget = nil
            }
            if let card = contactCardMessage, !messages.contains(where: { $0.id == card.id && $0.isRevoked == 0 }) {
                contactCardMessage = nil
            }
            if let location = locationMessage, !messages.contains(where: { $0.id == location.id && $0.isRevoked == 0 }) {
                locationMessage = nil
            }
        }
        .onChange(of: viewModel.isSelecting) { selecting in
            if selecting {
                showAttachmentMenu = false
                closeStickers(restoreFocus: false, force: true)
                quoteTarget = nil
                cancelEdit(restoreFocus: false)
            }
        }
        .focusedSceneValue(\.chatFilePicker, ChatFilePickerCommand(enabled: canAcceptFileDrop) {
            guard canAcceptFileDrop else { return }
            openFilePicker(allowed: [.item], asFile: true)
        })
        .onExitCommand {
            if (NSApp.keyWindow?.firstResponder as? NSTextView)?.hasMarkedText() == true { return }
            if quoteTarget != nil { quoteTarget = nil }
            else if showStickers { closeStickers() }
            else if showAttachmentMenu { closeAttachmentMenu() }
            else if chatContentCovered { return }
            else if editingMessage != nil {
                if !savingEdit { requestEditExit { cancelEdit() } }
            } else if viewModel.isSelecting {
                if !viewModel.savingSelection { viewModel.clearSelection() }
            } else if replyingTo != nil {
                _ = handleComposerHistory(.cancelAccessory)
            } else if searchQuery != nil {
                onCloseSearch()
            }
        }
        .alert(interfaceText("discard_message_edit"), isPresented: $confirmDiscardEdit) {
            Button(interfaceText("keep_editing"), role: .cancel) {
                let request = pendingEditExit
                pendingEditExit = nil
                request?.keep()
                editFocusRevision += 1
            }
            Button(interfaceText("discard"), role: .destructive) {
                let action = pendingEditExit
                pendingEditExit = nil
                action?.proceed()
            }
        }
        .sheet(item: $mediaConfirmation, onDismiss: {
            restoreComposerAfterAttachment()
        }) { MediaSendView(request: $0, model: viewModel) }
        .sheet(isPresented:$showVoiceRecording,onDismiss:{
            recordingReviewReady = capturedRecording != nil
            if recordingReviewReady { reviewCapturedRecording() }
            else {
                recordingDraft = nil
                recordingReply = nil
                restoreComposerAfterAttachment()
            }
        }) {RecordingView{result in capturedRecording=result;showVoiceRecording=false}}
        .sheet(isPresented: $showSendOptions) {
            SendOptionsView(initial: viewModel.sendOptions, startScheduled: startScheduled) {
                if startScheduled { handleSend(options: $0) }
                else { viewModel.sendOptions = $0 }
            }
        }
        .sheet(isPresented: $showSendQueue) {
            SendQueueView(peer: viewModel.targetUuid) { peer, id in
                guard AppLockSession.shared.canShowContent, viewModel.targetUuid == peer,
                      let id, let message = try MsgDao.shared.getMessageById(id),
                      message.conversationUuid == peer, message.isRevoked == 0 else { return false }
                showHistoryMessage(id)
                return true
            }
        }
        .alert(interfaceText("live_location_stop_error"), isPresented: $liveLocationStopFailed) {
            Button(interfaceText("close"), role: .cancel) {}
        }
        .sheet(item: $sharingContact) { ContactCardPickerView(request: $0) }
        .sheet(item: $contactCardMessage, onDismiss: {
            guard let account = contactCardChatTarget else { return }
            contactCardChatTarget = nil
            onOpenContactChat(account)
        }) { message in
            ContactCardDetailsView(message: message) { contactCardChatTarget = $0 }
        }
        .sheet(item: $sharingLocation) { LocationSendView(request: $0) }
        .sheet(item: $locationMessage) { message in
            if message.ext == "live_location" {
                LiveLocationDetailsView(message: message)
            } else {
                LocationDetailsView(label: message.content) {
                    try MsgDao.shared.getLocationPoint(peer: message.conversationUuid, messageID: message.id)
                }
            }
        }
        .sheet(item:$forwarding) {ForwardMessagesDialog(request:$0)}
        .environment(\.chatTextScale, CGFloat(MsgATTheme.chatTextPercentage(chatTextPercentage)) / 100)

    }

    private func searchNavigationButton(_ icon: String, title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 13, weight: .semibold))
                .frame(width: 30, height: 30)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .help(interfaceText(title))
        .accessibilityLabel(interfaceText(title))
    }

    private var historyNavigationDialog:some View {
        VStack(alignment:.leading,spacing:16) {
            Text(interfaceText("history_navigation")).font(.headline)
            DatePicker(interfaceText("jump_to_date"), selection: $historyDate, displayedComponents: .date).datePickerStyle(.field)
            Button(interfaceText("message_retention")) {
                showHistoryNavigation = false
                DispatchQueue.main.async { showExpiration = true }
            }
            HStack {
                Button(interfaceText("first_unread_message")) {navigateHistory(unread:true)}
                    .disabled(historyLookup != nil)
                Button(interfaceText("shared_media")) {
                    showHistoryNavigation = false
                    DispatchQueue.main.async { openSharedMedia() }
                }
                Button(interfaceText("pinned_messages")) {showHistoryNavigation=false;DispatchQueue.main.async{showPinnedMessages=true}}
            }
            historyNavigationStatus
            HStack {
                Spacer()
                Button(interfaceText("cancel")) {showHistoryNavigation=false}.keyboardShortcut(.cancelAction)
                Button(interfaceText("jump")) {navigateHistory(unread:false)}
                    .buttonStyle(.borderedProminent).keyboardShortcut(.defaultAction).disabled(historyLookup != nil)
            }
        }.padding(24).frame(width:440).background(messagingColors.chrome)
    }

    @ViewBuilder
    private var historyNavigationStatus: some View {
        if historyLookup != nil {
            ProgressView().controlSize(.small)
        } else if let historyNavigationError {
            Text(interfaceText(historyNavigationError))
                .font(.callout)
                .foregroundStyle(.red)
        }
    }

    private func timelineDateButton(_ day: Date) -> some View {
        let calendar = Calendar.current
        let label: String
        if calendar.isDateInToday(day) { label = interfaceText("timeline_today") }
        else if calendar.isDateInYesterday(day) { label = interfaceText("timeline_yesterday") }
        else { label = day.formatted(Date.FormatStyle(date: .abbreviated, time: .omitted, locale: interfaceText.locale)) }
        return Button {
            historyDate = day
            showTimelineCalendar = true
        } label: {
            Text(label)
                .font(.caption2.weight(.medium)).foregroundStyle(.secondary)
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(Color(NSColor.controlBackgroundColor), in: Capsule())
        }
        .buttonStyle(.plain)
        .help(interfaceText("jump_to_date"))
        .accessibilityHint(interfaceText("jump_to_date"))
    }

    private func updateFloatingTimelineDay(_ frames: [String: CGRect]) {
        guard viewportHeight > 0, let top = frames.lazy.filter({ $0.value.maxY > 0 && $0.value.minY < viewportHeight })
            .min(by: { $0.value.minY == $1.value.minY ? $0.key < $1.key : $0.value.minY < $1.value.minY }) else { return }
        let sameMessage = timelineDayAnchor?.id == top.key
        let offset = top.value.minY
        if sameMessage, let previous = timelineDayAnchor?.offset, abs(previous - offset) < 1 { return }
        let hadAnchor = timelineDayAnchor != nil
        timelineDayAnchor = (top.key, offset)
        guard hadAnchor, !viewModel.isViewingLatest, !chatContentCovered else { return }
        if !sameMessage || floatingTimelineDay == nil {
            guard let message = viewModel.messages.first(where: { $0.messageId == top.key }) else { return }
            floatingTimelineDay = Calendar.current.startOfDay(for: Date(timeIntervalSince1970: Double(message.timestamp) / 1_000))
        }
        let clock = ContinuousClock()
        timelineDayDeadline = clock.now.advanced(by: .milliseconds(1600))
        guard timelineDayHideTask == nil else { return }
        timelineDayHideTask = Task { @MainActor in
            while let deadline = timelineDayDeadline {
                do { try await clock.sleep(until: deadline) }
                catch { return }
                guard !Task.isCancelled else { return }
                if timelineDayDeadline == deadline {
                    floatingTimelineDay = nil
                    timelineDayDeadline = nil
                }
            }
            timelineDayHideTask = nil
        }
    }

    private func clearFloatingTimelineDay() {
        timelineDayHideTask?.cancel()
        timelineDayHideTask = nil
        timelineDayDeadline = nil
        timelineDayAnchor = nil
        floatingTimelineDay = nil
    }

    private func clearHistoryLookup() {
        historyLookup = nil
        historyNavigationError = nil
    }

    private func navigateHistory(unread: Bool) {
        guard historyLookup == nil else { return }
        historyNavigationError = nil
        let day = unread ? nil : Calendar.current.dateInterval(of: .day, for: historyDate)
        if !unread && day == nil {
            historyNavigationError = "no_messages_on_date"
            return
        }
        historyLookup = HistoryLookup(peer: viewModel.targetUuid, day: day)
    }

    @MainActor
    private func performHistoryLookup() async {
        guard let lookup = historyLookup else { return }
        let peer = lookup.peer
        let day = lookup.day
        defer { if !Task.isCancelled, historyLookup == lookup { historyLookup = nil } }
        do {
            let id = try await EncryptedMediaWork.run {
                if let day {
                    return try MsgDao.shared.getMessageAtDate(peer: peer,
                        dayStart: Int64(day.start.timeIntervalSince1970 * 1000),
                        nextDayStart: Int64(day.end.timeIntervalSince1970 * 1000))
                }
                return try MsgDao.shared.getFirstUnreadId(peer: peer)
            }
            guard !Task.isCancelled, historyLookup == lookup, viewModel.targetUuid == peer,
                  showHistoryNavigation || showTimelineCalendar, AppLockSession.shared.canShowContent else { return }
            guard let id else {
                historyNavigationError = day == nil ? "no_unread_messages" : "no_messages_on_date"
                return
            }
            if day == nil { unreadTimelineAnchor = id }
            showHistoryMessage(id)
            showHistoryNavigation = false
            showTimelineCalendar = false
        } catch {
            if !Task.isCancelled, historyLookup == lookup { historyNavigationError = "history_load_failed" }
        }
    }

    private struct TimelineEntry: Identifiable {
        let message: ChatMessage
        let day: Date?
        let joinsOlder: Bool
        let joinsNewer: Bool
        var id: String { message.messageId }
    }

    private func timelineEntries(albums: [String: [ChatMessage]], unreadRowID: String?) -> [TimelineEntry] {
        let calendar = Calendar.current
        let rows = viewModel.messages.filter { row in
            (albums[row.messageId]?.first?.messageId ?? row.messageId) == row.messageId
        }
        let days = rows.map { calendar.startOfDay(for: Date(timeIntervalSince1970: Double($0.timestamp) / 1_000)) }
        let messageTypes: Set<String> = ["txt", "attachment", "contact", "location", "live_location"]
        func joins(at index: Int) -> Bool {
            guard index > 0, rows.indices.contains(index), days[index] == days[index - 1] else { return false }
            let older = rows[index - 1]
            let newer = rows[index]
            return newer.messageId != unreadRowID && older.isMine == newer.isMine &&
                older.isRevoked == 0 && newer.isRevoked == 0 &&
                viewModel.forwardedMessages[older.messageId] == nil && viewModel.forwardedMessages[newer.messageId] == nil &&
                messageTypes.contains(older.ext) && messageTypes.contains(newer.ext) &&
                viewModel.albumItems[older.messageId] == nil && viewModel.albumItems[newer.messageId] == nil &&
                older.timestamp >= 0 && newer.timestamp >= older.timestamp && newer.timestamp - older.timestamp <= 5 * 60 * 1_000
        }
        return rows.enumerated().map { index, row in
            TimelineEntry(message: row,
                          day: index == 0 || days[index] != days[index - 1] ? days[index] : nil,
                          joinsOlder: joins(at: index),
                          joinsNewer: joins(at: index + 1))
        }
    }

    private func openMedia(_ message: ChatMessage, origin: MediaPreviewOrigin? = nil) {
        if selectedMedia == nil { previewFocus.capture(in: NotificationHelper.mainWindow) }
        previewMove?.cancel()
        previewNeighbors = nil
        previewAlbumStep = 0
        previewRefreshFailed = false
        let members = viewModel.displayedAlbums[message.messageId] ?? [message]
        previewAlbum = members.sorted {
            (viewModel.albumItems[$0.messageId]?.position.index ?? 0) <
            (viewModel.albumItems[$1.messageId]?.position.index ?? 0)
        }
        previewOrigin = reduceMotion ? nil : origin
        selectedMedia = message
    }

    private func toggleInlineAudio(_ message: ChatMessage, media: AttachmentMetadata) {
        let call = CallManager.shared.callState
        guard !chatContentCovered, !viewModel.isSelecting, call == .idle || call == .ended else { return }
        if inlineAudioID == message.messageId {
            if inlineAudio.failed { inlineAudio.retry(media) }
            else { inlineAudio.toggle() }
            return
        }
        inlineAudio.close()
        inlineAudioID = message.messageId
        inlineAudio.open(media)
    }

    private func closeInlineAudio() {
        guard inlineAudioID != nil else { return }
        inlineAudioID = nil
        inlineAudio.close()
    }

    private func closeMedia(restoreFocus: Bool = true) {
        let wasOpen = selectedMedia != nil
        mediaFullScreen.end()
        previewMove?.cancel()
        previewMove = nil
        previewNeighbors = nil
        previewRefreshFailed = false
        previewOrigin = nil
        selectedMedia = nil
        previewAlbum = []
        previewAlbumStep = 0
        if wasOpen && restoreFocus { previewFocus.restore() }
        else { previewFocus.clear() }
    }

    private func openSharedMedia(_ category: SharedMediaCategory = .media) {
        sharedMediaStart = category
        showSharedMedia = true
    }

    private func previewReturnFrame(_ id: String) -> CGRect? {
        guard let frame = thumbnailFrames[id], !frame.inChat.isEmpty,
              frame.inTimeline.minY >= 0, frame.inTimeline.maxY <= viewportHeight else { return nil }
        return frame.inChat
    }

    private struct TimelineMediaKey: Hashable {
        let id: String?
        let reference: String?
        let inAlbum: Bool
        let revision: Int
    }

    private func refreshMediaNeighbors() async {
        guard let shown = selectedMedia, shown.ext == "attachment", previewAlbum.count <= 1 else { return }
        let peer = viewModel.targetUuid
        func stillShowing() -> Bool {
            !Task.isCancelled && selectedMedia?.messageId == shown.messageId && selectedMedia?.content == shown.content
        }
        do {
            let result = try await Task.detached(priority: .userInitiated) { () throws -> (Bool, VisualMediaNeighbors?) in
                let store = EncryptedObjectStore.shared
                guard let message = try MsgDao.shared.getMessageById(shown.messageId), message.isRevoked == 0,
                      message.conversationUuid == peer, message.content == shown.content else { return (false, nil) }
                guard let row = try store.sharedMediaItem(peer: peer, messageId: message.messageId) else {
                    let media = try store.metadata(message.content, messageID: message.messageId)
                    return (media?.isSticker == true && media?.available == true, nil)
                }
                guard row.attachment.isImage || row.attachment.isVideo else { return (true, nil) }
                let anchor = SharedMediaCursor(timestamp: row.timestamp, messageId: row.id)
                return (true, try store.visualMediaNeighbors(peer: peer, anchor: anchor))
            }.value
            guard stillShowing() else { return }
            if result.0 {
                previewNeighbors = result.1
                previewRefreshFailed = false
            }
            else { closeMedia() }
        } catch {
            if stillShowing() { previewRefreshFailed = true }
        }
    }

    private func moveMedia(id: String, reference: String, inAlbum: Bool = false) {
        guard let origin = selectedMedia?.messageId, origin != id else { return }
        previewMove?.cancel()
        let peer = viewModel.targetUuid
        previewMove = Task { @MainActor in
            do {
                let destination = try await Task.detached(priority: .userInitiated) { () throws -> (ChatMessage, Bool)? in
                    guard let message = try MsgDao.shared.getMessageById(id),
                          message.conversationUuid == peer, message.isRevoked == 0,
                          message.ext == "attachment", message.content == reference,
                          let media = try EncryptedObjectStore.shared.metadata(reference, messageID: id),
                          media.isImage || media.isVideo,
                          inAlbum || media.available else { return nil }
                    return (message, media.available)
                }.value
                guard !Task.isCancelled, selectedMedia?.messageId == origin,
                      AppLockSession.shared.canShowContent, let (message, available) = destination else { return }
                if available && !message.isMine {
                    let complete = try await EncryptedObjectStore.shared.checkCiphertext(reference)
                    guard !Task.isCancelled, selectedMedia?.messageId == origin, AppLockSession.shared.canShowContent else { return }
                    if !complete {
                        let queued = await SecureMessaging.shared.queueAttachmentCacheReplacement(peer: peer,
                            messageID: id, reference: reference)
                        guard queued, !Task.isCancelled, selectedMedia?.messageId == origin,
                              AppLockSession.shared.canShowContent else { return }
                    }
                }
                if inAlbum {
                    guard let from = previewAlbum.firstIndex(where: { $0.messageId == origin }),
                          let to = previewAlbum.firstIndex(where: { $0.messageId == id && $0.content == reference }) else { return }
                    previewAlbumStep = to > from ? 1 : -1
                } else {
                    previewAlbumStep = 0
                }
                previewNeighbors = nil
                previewOrigin = nil
                previewRefreshFailed = false
                selectedMedia = message
            } catch {
                if !Task.isCancelled { NoticeCenter.shared.show(interfaceText("file_details_unavailable")) }
            }
        }
    }

    private func removeFromMediaPreview(_ ids: Set<String>) {
        guard !ids.isEmpty else { return }
        if let selectedMedia, ids.contains(selectedMedia.messageId) { closeMedia() }
        else { previewAlbum.removeAll { ids.contains($0.messageId) } }
    }

    private func previewNavigation(_ media: ChatMessage) -> InAppMediaViewer.Navigation? {
        if previewAlbum.count > 1, let index = previewAlbum.firstIndex(where: { $0.messageId == media.messageId }) {
            let previous = index > 0 ? previewAlbum[index - 1] : nil
            let next = index + 1 < previewAlbum.count ? previewAlbum[index + 1] : nil
            let select: (ChatMessage) -> Void = { item in
                moveMedia(id: item.messageId, reference: item.content, inAlbum: true)
            }
            return InAppMediaViewer.Navigation(
                position: "\(index + 1) / \(previewAlbum.count)",
                previous: previous.map { item in { select(item) } },
                next: next.map { item in { select(item) } },
                album: previewAlbum,
                select: select
            )
        }
        let previous = previewNeighbors?.newer
        let next = previewNeighbors?.older
        guard previous != nil || next != nil else { return nil }
        return InAppMediaViewer.Navigation(
            position: nil,
            previous: previous.map { item in { moveMedia(id: item.id, reference: item.attachment.reference) } },
            next: next.map { item in { moveMedia(id: item.id, reference: item.attachment.reference) } }
        )
    }

    private func albumRow(_ album: [ChatMessage], anchor: ChatMessage) -> some View {
        let ordered = album.sorted {
            (viewModel.albumItems[$0.messageId]?.position.index ?? 0) <
            (viewModel.albumItems[$1.messageId]?.position.index ?? 0)
        }
        let hasWideFirstTile = !ordered.count.isMultiple(of: 2)
        let pairedTiles = hasWideFirstTile ? Array(ordered.dropFirst()) : ordered
        let first = ordered.first.flatMap { viewModel.albumItems[$0.messageId] }
        return VStack(alignment: .leading, spacing: 5) {
            if hasWideFirstTile, let item = ordered.first {
                messageRow(item, albumTile: true)
            }
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 5), GridItem(.flexible())],
                      alignment: .leading, spacing: 5) {
                ForEach(pairedTiles) { item in messageRow(item, albumTile: true) }
            }
            if let first, first.position.index == 0, !first.groupCaption.isEmpty {
                RichMessageText(text: first.groupCaption).id(first.groupCaption)
                    .font(.system(size: 14))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 4)
                    .allowsHitTesting(!viewModel.isSelecting)
                    .accessibilityHidden(viewModel.isSelecting)
            }
        }
        .padding(5)
        .background(Color(NSColor.controlBackgroundColor), in: RoundedRectangle(cornerRadius: 12))
        .frame(maxWidth: 330)
        .frame(maxWidth: .infinity, alignment: anchor.isMine ? .trailing : .leading)
        .id("album-row/" + anchor.messageId)
    }

    private var conversationTitle: some View {
        Group {
            if viewModel.isSelecting {
                Text(interfaceText.format("message_selection_count", viewModel.selectedMessages.count))
                    .font(.headline).lineLimit(1)
            } else {
                let name = viewModel.conversation?.alias ?? "MsgAT"
                let activity = viewModel.peerActivity
                let status = activity.map { interfaceText($0.rawValue) } ?? connectionText
                let verified = verifiedPeer == viewModel.targetUuid
                let spokenStatus = [
                    verified ? interfaceText("contact_verified") : nil,
                    status,
                    activity == nil ? nil : connectionText
                ].compactMap { $0 }.joined(separator: ", ")
                Button { showContactProfile = true } label: {
                    HStack(spacing: 8) {
                        AvatarView(avatarPath: viewModel.conversation?.avatarPath, fallbackText: name, size: 34, cornerRadius: 17)
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 4) {
                                Text(name).font(.headline).lineLimit(1).truncationMode(.tail)
                                if verified {
                                    Image(systemName: "checkmark.shield")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(MsgATTheme.connectionColor)
                                        .accessibilityHidden(true)
                                }
                            }
                            HStack(spacing: 4) {
                                conversationConnectionMark
                                if activity == .typing { typingActivityDots }
                                Text(status)
                                    .font(.caption)
                                    .foregroundStyle(activity == .typing || activity == .online ? Color.accentColor : Color.secondary)
                                    .lineLimit(1)
                            }
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help(interfaceText("open_contact_profile"))
                .accessibilityLabel(name)
                .accessibilityValue(spokenStatus)
                .accessibilityHint(interfaceText("open_contact_profile"))
            }
        }
        .frame(maxWidth: compactChatToolbar ? max(112, chatWidth - 200) : 320, alignment: .leading)
    }

    private func changeMute(_ action: String, _ until: Int64?) {
        SecureMessaging.shared.changeConversation(viewModel.targetUuid, action: action, until: until)
    }

    @ViewBuilder private var typingActivityDots: some View {
        if reduceMotion {
            activityDots(phase: nil)
        } else {
            TimelineView(.animation(minimumInterval: 1.0 / 24)) { frame in
                let phase = frame.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 0.96) / 0.96
                activityDots(phase: phase)
            }
        }
    }

    private func activityDots(phase: Double?) -> some View {
        HStack(spacing: 2) {
            ForEach(0..<3) { index in
                let pulse = phase.map { 0.5 + 0.5 * cos(($0 - Double(index) / 6) * (2 * Double.pi)) } ?? 1
                Circle()
                    .fill(Color.accentColor)
                    .frame(width: 3, height: 3)
                    .opacity(0.45 + 0.55 * pulse)
            }
        }
        .accessibilityHidden(true)
    }

    private var connectionText: String {
        switch client.connectionState {
        case .disconnected: return interfaceText("disconnected")
        case .syncing: return interfaceText("syncing")
        case .synced: return interfaceText("connected")
        }
    }

    private var conversationConnectionMark: some View {
        Group {
            switch client.connectionState {
            case .disconnected:
                Circle().fill(MsgATTheme.errorColor).frame(width: 7, height: 7)
            case .syncing:
                ProgressView().controlSize(.mini).tint(MsgATTheme.connectionColor)
            case .synced:
                Circle().fill(MsgATTheme.connectionColor).frame(width: 7, height: 7)
            }
        }
        .frame(width: 13, height: 13)
        .help(connectionText)
        .accessibilityHidden(true)
    }

    @State private var timelineBottom: CGFloat?

    private func updateViewportSize(_ size: CGSize) {
        guard viewportSize != size else { return }
        viewportReflowing = true
        viewportSize = size
        viewModel.setVisibleMessages([])
    }

    private func updateViewingLatest() {
        guard viewModel.historyLoaded, !restoringHistory else { return }
        let nearBottom = !viewModel.hasMoreNewerMessages && (timelineBottom.map { $0 <= viewportHeight + 48 && $0 >= 0 } ?? false)
        viewModel.setViewingLatest(nearBottom)
    }

    private func updateReadVisibility(){
        guard !restoringHistory, !viewportReflowing else {viewModel.setVisibleMessages([]);return}
        let ids=Set(messageFrames.compactMap {id,frame -> String? in
            let overlap=min(frame.maxY,viewportHeight)-max(frame.minY,0)
            return frame.height>0 && overlap>=min(frame.height,48) ? id : nil
        })
        viewModel.setVisibleMessages(ids)
    }

    private func saveHistoryPosition() {
        guard initializedTimeline,!restoringHistory,!messageFrames.isEmpty else {return}
        if viewModel.isViewingLatest {onSaveHistoryPosition(nil);return}
        if let position = captureHistoryPosition() { onSaveHistoryPosition(position) }
    }

    private func captureHistoryPosition() -> ChatHistoryPosition? {
        let valid = Set(viewModel.messages.filter {$0.isRevoked == 0}.map(\.messageId))
        let visible = messageFrames.filter {
            valid.contains($0.key) && $0.value.maxY > 0 && $0.value.minY < viewportHeight
        }
        let fullyVisible = visible.filter { $0.value.minY >= 0 && $0.value.maxY <= viewportHeight }
        guard let (id, frame) = (fullyVisible.isEmpty ? visible : fullyVisible).min(by: {
            let left = abs($0.value.minY)
            let right = abs($1.value.minY)
            return left == right ? $0.key < $1.key : left < right
        }) else { return nil }
        let offset = min(1,max(0,frame.minY / max(1,viewportHeight-frame.height)))
        return ChatHistoryPosition(messageId: id, relativeOffset: offset)
    }

    private func loadHistoryPage(_ page: HistoryPage, proxy: ScrollViewProxy) {
        guard !restoringHistory else { return }
        let position = captureHistoryPosition()
        let first = viewModel.messages.first?.messageId
        let last = viewModel.messages.last?.messageId
        let count = viewModel.messages.count
        switch page {
        case .older: viewModel.loadMoreMessages()
        case .newer: viewModel.loadNewerMessages()
        }
        historyPageToRetry = viewModel.historyLoadFailed ? page : nil
        guard count != viewModel.messages.count || first != viewModel.messages.first?.messageId ||
                last != viewModel.messages.last?.messageId else { return }
        if let position, viewModel.messages.contains(where: { $0.messageId == position.messageId }) {
            applyInitialHistoryPosition(position.messageId, proxy: proxy,
                                        anchor: UnitPoint(x: 0.5, y: position.relativeOffset))
        } else if let edge = page == .newer ? last : first {
            applyInitialHistoryPosition(edge, proxy: proxy, anchor: page == .newer ? .bottom : .top)
        }
    }

    private func loadNewerHistoryIfVisible(proxy: ScrollViewProxy) {
        guard initializedTimeline, !restoringHistory, !chatContentCovered,
              viewModel.hasMoreNewerMessages, !viewModel.historyLoadFailed,
              viewportHeight > 0, let bottom = timelineBottom, bottom >= 0, bottom <= viewportHeight + 1 else { return }
        loadHistoryPage(.newer, proxy: proxy)
    }

    private func closeAttachmentMenu() {
        guard showAttachmentMenu else { return }
        showAttachmentMenu = false
        attachmentToggleFocused = true
    }

    private func openStickers() {
        guard viewModel.canCompose, editingMessage == nil, !viewModel.isSelecting else { return }
        showAttachmentMenu = false
        stickerMaskHeld = true
        showStickers = true
    }

    private func closeStickers(restoreFocus: Bool = true, force: Bool = false) {
        guard showStickers, !stickerBusy || force else { return }
        showStickers = false
        if force || reduceMotion { stickerMaskHeld = false }
        if restoreFocus && viewModel.isChatMounted && AppLockSession.shared.canShowContent {
            draftFocusRevision += 1
        }
    }

    private var attachmentMenu: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 2) {
                    attachmentAction(0, "photo_video", icon: "photo.on.rectangle") {
                        openFilePicker(allowed: [.image, .movie])
                    }
                    attachmentAction(1, "files", icon: "doc") {
                        openFilePicker(allowed: [.item], asFile: true)
                    }
                    attachmentAction(2, "audio", icon: "music.note") { openFilePicker(allowed: [.audio]) }
                    Rectangle().fill(messagingColors.divider).frame(height: 1)
                        .padding(.horizontal, 4).padding(.vertical, 2)
                        .accessibilityHidden(true)
                    attachmentAction(3, "voice_message", icon: "mic") { beginRecording() }
                    attachmentAction(4, "stickers", icon: "face.smiling") { openStickers() }
                    attachmentAction(5, "contact_card", icon: "person.crop.circle") {
                        let draft = viewModel.captureMediaDraft()
                        let reply = replyingTo
                        showAttachmentMenu = false
                        sharingContact = ContactCardSendRequest(recipient: viewModel.conversation?.alias ?? "User",
                                                                initial: viewModel.sendOptions) { account, name, options in
                            await viewModel.sendContactCard(account: account, name: name, draft: draft, reply: reply, options: options)
                        }
                    }
                    attachmentAction(6, "location", icon: "mappin.circle") {
                        let draft = viewModel.captureMediaDraft()
                        let reply = replyingTo
                        showAttachmentMenu = false
                        sharingLocation = LocationSendRequest(recipient: viewModel.conversation?.alias ?? "User",
                            initial: viewModel.sendOptions, send: { point, label, options in
                                await viewModel.sendLocation(point: point, label: label, draft: draft, reply: reply, options: options)
                            }, shareLive: { point, duration, label, silent in
                                await viewModel.shareLiveLocation(point: point, durationMs: duration, label: label,
                                                                  draft: draft, reply: reply, silent: silent)
                            })
                    }
                }
                .padding(5)
            }
            .onChange(of: attachmentActionFocus) { if let row = $0 { proxy.scrollTo(row) } }
        }
        .frame(width: 234, height: min(262, max(0, viewportHeight - 12)))
        .background(messagingColors.chrome, in: RoundedRectangle(cornerRadius: 12))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay {
            RoundedRectangle(cornerRadius: 12).strokeBorder(messagingColors.divider).allowsHitTesting(false)
        }
        .shadow(color: .black.opacity(0.18), radius: 12, y: 3)
        .onMoveCommand { direction in
            let index = attachmentActionFocus ?? 0
            switch direction {
            case .up: attachmentActionFocus = (index + 6) % 7
            case .down: attachmentActionFocus = (index + 1) % 7
            default: break
            }
        }
    }

    private func attachmentAction(_ index: Int, _ titleKey: String, icon: String, action: @escaping () -> Void) -> some View {
        let title = interfaceText(titleKey)
        let focused = attachmentActionFocus == index
        let highlighted = focused || hoveredAttachmentAction == index
        return Button { if showAttachmentMenu { action() } } label: {
            HStack(spacing: 10) {
                Image(systemName: icon).font(.system(size: 16)).foregroundStyle(.secondary).frame(width: 20)
                Text(title).font(.body).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 10)
            .frame(minHeight: 33)
            .background(highlighted ? messagingColors.selection : Color.clear, in: RoundedRectangle(cornerRadius: 6))
            .overlay {
                if focused {
                    RoundedRectangle(cornerRadius: 6)
                        .strokeBorder(messagingColors.selectionMark)
                        .allowsHitTesting(false)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .focused($attachmentActionFocus, equals: index)
        .onHover { hovering in
            if hovering { hoveredAttachmentAction = index }
            else if hoveredAttachmentAction == index { hoveredAttachmentAction = nil }
        }
        .accessibilityLabel(title)
        .id(index)
    }

    private var canSaveEdit: Bool {
        guard let target = editingMessage, !savingEdit else { return false }
        return (target.ext == "attachment" || !editingText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) &&
            editingText != originalEditText
    }

    private var composerActionIsPending: Bool {
        editingMessage == nil ? viewModel.textSendPending : savingEdit
    }

    private var composerActionTitle: String {
        if editingMessage != nil { return interfaceText(savingEdit ? "saving" : "save") }
        if composerActionIsPending { return interfaceText("preparation_queuing") }
        return interfaceText(hasTextToSend ? "send" : "voice_message")
    }

    private var composerActionSymbol: String {
        if editingMessage != nil { return "checkmark" }
        return hasTextToSend ? "paperplane.fill" : "mic"
    }

    private func composerAccessory(for target: ChatMessage, identity: ComposerAccessoryIdentity) -> some View {
        let editing = identity.editing
        let current = viewModel.messages.first { $0.messageId == target.messageId } ?? target
        let author = target.isMine ? interfaceText("quoted_you") : viewModel.conversation?.alias ?? viewModel.targetUuid
        let title = editing ? interfaceText(target.ext == "attachment" ? "editing_caption" : "editing_message")
            : interfaceText.format("replying_to", author)
        let close = interfaceText(editing ? "cancel_edit" : "cancel_reply")
        return HStack(spacing: 10) {
            Button {
                guard composerAccessoryIdentity == identity, !chatContentCovered, AppLockSession.shared.canShowContent else { return }
                requestQuotedMessage(target.messageId, quote: editing ? nil : viewModel.draftQuoteText, openingQuote: !editing)
            } label: {
                HStack(spacing: 10) {
                    RoundedRectangle(cornerRadius: 2).fill(messagingColors.selectionMark)
                        .frame(width: 3, height: 28).accessibilityHidden(true)
                    Image(systemName: editing ? "pencil" : "arrowshape.turn.up.left")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(messagingColors.selectionMark).frame(width: 22)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.caption.weight(.semibold)).foregroundStyle(messagingColors.selectionMark).lineLimit(1)
                        MessageSummaryText(text: composerAccessoryPreview(for: current, editing: editing))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(minHeight: 40)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain).help(interfaceText("jump_to_message"))
            .accessibilityHint(interfaceText("jump_to_message"))
            Button {
                guard composerAccessoryIdentity == identity, !chatContentCovered, AppLockSession.shared.canShowContent else { return }
                if editing { requestEditExit { cancelEdit() } }
                else {
                    replyingTo = nil
                    highlightMessage(nil)
                    draftFocusRevision += 1
                }
            } label: {
                Image(systemName: "xmark").frame(width: 30, height: 34)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless).help(close).accessibilityLabel(close)
            .disabled(editing && savingEdit)
        }
        .padding(.horizontal, 12)
        .background(messagingColors.timeline)
        .overlay(alignment: .top) {
            Rectangle().fill(messagingColors.divider).frame(height: 1).allowsHitTesting(false)
        }
    }

    private func composerAccessoryPreview(for target: ChatMessage, editing: Bool) -> String {
        if editing && !originalEditText.isEmpty { return originalEditText }
        if !editing, let excerpt = viewModel.draftQuoteText { return excerpt }
        switch target.ext {
        case "txt", "location", "live_location": return target.content
        case "contact": return interfaceText("contact_card")
        case "attachment":
            return editing ? interfaceText("quote_attachment")
                : viewModel.draftReplyAttachmentSummary ?? interfaceText("quote_attachment")
        default: return target.quoteSummary
        }
    }

    private func handleComposerHistory(_ command: ComposerHistoryCommand) -> Bool {
        guard viewModel.isChatMounted, viewModel.canCompose, editingMessage == nil, editLoad == nil,
              !chatContentCovered, !restoringHistory else { return false }

        switch command {
        case .cancelAccessory:
            guard replyingTo != nil else { return false }
            replyingTo = nil
            highlightMessage(nil)
            return true
        case .editLatest:
            guard inputText.isEmpty, replyingTo == nil, !viewModel.textSendPending,
                  let target = try? MsgDao.shared.latestEditableMessage(peer: viewModel.targetUuid) else { return false }
            beginEdit(target, requiresEmptyDraft: true)
            return true
        case .previousReply, .nextReply:
            guard inputText.isEmpty else { return false }
            let newer = command == .nextReply
            let current = replyingTo
            guard !newer || current != nil,
                  let anchor = current ?? viewModel.messages.last else { return false }
            do {
                if let target = try MsgDao.shared.adjacentReplyMessage(to: anchor, newer: newer, includingAnchor: current == nil) {
                    replyingTo = target
                    showHistoryMessage(target.messageId)
                    return true
                }
                if newer {
                    replyingTo = nil
                    highlightMessage(nil)
                    return true
                }
            } catch {
                return false
            }
            return false
        }
    }

    private func beginEdit(_ target: ChatMessage, requiresEmptyDraft: Bool = false) {
        guard viewModel.canCompose, editingMessage?.id != target.id else { return }
        requestEditExit { loadEdit(target, requiresEmptyDraft: requiresEmptyDraft) }
    }

    private func loadEdit(_ target: ChatMessage, requiresEmptyDraft: Bool) {
        guard viewModel.canCompose, viewModel.isChatMounted, AppLockSession.shared.canShowContent, !savingEdit else { return }
        editLoad?.cancel()
        editLoad = nil
        if target.ext == "txt" {
            openEdit(target, text: target.content, revealTarget: requiresEmptyDraft)
        } else if target.ext == "attachment" {
            let model = viewModel
            let previousTarget = editingMessage?.id
            let previousText = editingText
            editLoad = Task { @MainActor in
                let metadata = try? await EncryptedMediaWork.run {
                    try EncryptedObjectStore.shared.metadata(target.content, messageID: target.messageId)
                }
                guard !Task.isCancelled else { return }
                editLoad = nil
                guard model.isChatMounted, model.canCompose, AppLockSession.shared.canShowContent, !savingEdit,
                      !callCoversChat, editingMessage?.id == previousTarget,
                      !requiresEmptyDraft || (inputText.isEmpty && replyingTo == nil && !chatContentCovered && !viewModel.textSendPending) else { return }
                guard let metadata, !metadata.isSticker else { return }
                if editingText == previousText {
                    openEdit(target, text: metadata.caption, revealTarget: requiresEmptyDraft)
                } else {
                    requestEditExit { openEdit(target, text: metadata.caption, revealTarget: requiresEmptyDraft) }
                }
            }
        }
    }

    private func openEdit(_ target: ChatMessage, text: String, revealTarget: Bool = false) {
        showAttachmentMenu = false
        originalEditText = text
        editingText = text
        editingMessage = target
        editFocusRevision += 1
        if revealTarget {
            if let frame = messageFrames[target.messageId], frame.maxY > 0 && frame.minY < viewportHeight {
                highlightMessage(target.messageId)
            } else {
                showHistoryMessage(target.messageId)
            }
        }
    }

    private func cancelEdit(restoreFocus: Bool = true) {
        editLoad?.cancel()
        editLoad = nil
        guard editingMessage != nil else { return }
        pendingEditExit = nil
        confirmDiscardEdit = false
        editingMessage = nil
        editingText = ""
        originalEditText = ""
        if restoreFocus && viewModel.isChatMounted && viewModel.canCompose &&
            !chatContentCovered && AppLockSession.shared.canShowContent {
            draftFocusRevision += 1
        }
    }

    private func requestEditExit(onKeep: @escaping () -> Void = {}, _ action: @escaping () -> Void) {
        guard !savingEdit, !confirmDiscardEdit else { return }
        if editingMessage != nil && editingText != originalEditText {
            pendingEditExit = (action, onKeep)
            confirmDiscardEdit = true
        } else {
            action()
        }
    }

    private func saveEdit() {
        guard viewModel.canCompose, canSaveEdit, let target = editingMessage else { return }
        savingEdit = true
        viewModel.editMessage(messageId: target.messageId, newContent: editingText) { saved in
            savingEdit = false
            guard editingMessage?.messageId == target.messageId else { return }
            if saved { cancelEdit() }
            else {
                if viewModel.isChatMounted && !chatContentCovered && AppLockSession.shared.canShowContent {
                    editFocusRevision += 1
                }
                NoticeCenter.shared.show(interfaceText("edit_failed"))
            }
        }
    }

    private func handleSend(options: SendOptions? = nil) {
        viewModel.syncChatActivity(typing:false)
        guard viewModel.canCompose, viewModel.isChatMounted, AppLockSession.shared.canShowContent,
              editingMessage == nil, !stickerBusy, !callCoversChat else { return }
        closeStickers(restoreFocus: false)
        let textToSend = inputText
        guard !textToSend.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let reply = replyingTo
        viewModel.sendMessage(text: textToSend, replyTo: reply, options: options) {
            NoticeCenter.shared.show(interfaceText("send_failed"))
        }
    }

    private func applyInitialHistoryPosition(_ id: String, proxy: ScrollViewProxy, anchor: UnitPoint = .center,
                                             animated: Bool = false) {
        restoringHistory = true
        viewModel.setVisibleMessages([])
        viewModel.setViewingLatest(false)
        let generation = highlightGeneration
        historyPositionRevision += 1
        let navigation = historyPositionRevision
        Task { @MainActor in
            defer {
                if navigation == historyPositionRevision {
                    restoringHistory = false
                    updateViewingLatest()
                    updateReadVisibility()
                }
            }
            for (attempt, delay) in [0, 32, 80, 160, 220].enumerated() {
                if delay > 0 { try? await Task.sleep(for: .milliseconds(delay)) }
                guard navigation == historyPositionRevision, generation == highlightGeneration,
                      viewModel.isChatMounted, AppLockSession.shared.canShowContent else { return }
                if attempt > 0, let frame = messageFrames[id], frame.maxY > 0, frame.minY < viewportHeight { return }
                withAnimation(attempt == 0 && animated && !reduceMotion ? .easeOut(duration: 0.2) : nil) {
                    scrollToMessage(id, proxy: proxy, anchor: anchor, positioningRevision: navigation)
                }
            }
        }
    }

    private func scrollToMessage(_ id: String, proxy: ScrollViewProxy, anchor: UnitPoint = .center,
                                 positioningRevision: Int? = nil) {
        let lead = viewModel.displayedAlbums[id]?.first?.messageId ?? id
        proxy.scrollTo("timeline-row/" + lead, anchor: anchor)
        if lead != id {
            DispatchQueue.main.async {
                guard positioningRevision == nil || positioningRevision == historyPositionRevision,
                      viewModel.isChatMounted, AppLockSession.shared.canShowContent else { return }
                proxy.scrollTo(id, anchor: anchor)
            }
        }
    }

    private func requestQuotedMessage(_ target: String, source: String? = nil, quote: String? = nil, openingQuote: Bool = false) {
        let visible = messageFrames.filter { $0.key != target && $0.value.maxY > 0 && $0.value.minY < viewportHeight }
        let originId = source.flatMap { visible[$0] == nil ? nil : $0 } ?? visible.min { lhs, rhs in
            abs(lhs.value.minY) < abs(rhs.value.minY)
        }?.key
        requestedQuoteOrigin = nil
        if let originId, let frame = visible[originId] {
            let position = min(1, max(0, frame.minY / max(1, viewportHeight - frame.height)))
            requestedQuoteOrigin = (target, QuoteReturnAnchor(messageId: originId, position: position))
        }
        requestedHistoryMessage = target
        requestedQuotePreview = quote
        openingHistoryQuote = openingQuote
        historyJumpGeneration += 1
    }

    private func showHistoryMessage(_ id: String) {
        requestQuotedMessage(id)
    }

    private func highlightMessage(_ id: String?, quote: String? = nil) {
        highlightedMessageId = id
        highlightedQuote = quote
        highlightGeneration += 1
    }

    private func requestSendCancellation(_ id: String) {
        guard !checkingSendCancellation, pendingSendCancellation == nil else { return }
        checkingSendCancellation = true
        let peer = viewModel.targetUuid
        Task { @MainActor in
            defer { checkingSendCancellation = false }
            let rows: [SendQueueEntry]? = await withCheckedContinuation { continuation in
                SecureMessaging.shared.sendQueue(peer: peer, messageID: id) { continuation.resume(returning: $0) }
            }
            guard viewModel.isChatMounted, viewModel.targetUuid == peer,
                  AppLockSession.shared.canShowContent else { return }
            if rows?.first?.canCancel == true { pendingSendCancellation = id }
            else { NoticeCenter.shared.show(interfaceText("send_cancel_unavailable")) }
        }
    }

    private func retrySend(_ id: String) {
        guard viewModel.isChatMounted, !viewModel.isSelecting, AppLockSession.shared.canShowContent,
              retryingSends.insert(id).inserted else { return }
        let peer = viewModel.targetUuid
        Task { @MainActor in
            defer { retryingSends.remove(id) }
            let queued: Bool = await withCheckedContinuation { continuation in
                SecureMessaging.shared.manageSend(id, action: "retry", expectedPeer: peer) { continuation.resume(returning: $0) }
            }
            if !queued, viewModel.isChatMounted, AppLockSession.shared.canShowContent {
                NoticeCenter.shared.show(interfaceText("send_retry_unavailable"))
            }
        }
    }

    private func requestLocalDeletion(_ ids: Set<String>) {
        guard !viewModel.savingSelection, !checkingLocalDeletion,
              pendingLocalDeletion == nil, !ids.isEmpty else { return }
        let peer = viewModel.targetUuid
        let version = viewModel.selectionVersion
        let fromSelection = ids == viewModel.selectedMessages
        checkingLocalDeletion = true
        Task { @MainActor in
            defer { checkingLocalDeletion = false }
            let canRevoke = try? await EncryptedMediaWork.run {
                try SecureMessaging.shared.deletionCanRevoke(peer: peer, ids: ids)
            }
            guard viewModel.isChatMounted, AppLockSession.shared.canShowContent,
                  viewModel.selectionVersion == version, !viewModel.savingSelection else { return }
            guard let canRevoke else {
                NoticeCenter.shared.show(interfaceText("local_delete_unavailable"))
                return
            }
            pendingLocalDeletion = DeletionRequest(ids: ids, canRevoke: fromSelection && canRevoke)
        }
    }

    private func jumpToLatest() {
        guard !restoringHistory else { return }
        retryingLatestJump = !viewModel.loadLatestMessages()
        guard !retryingLatestJump else { return }
        quoteReturnAnchors.removeAll()
        highlightMessage(nil)
        clearFloatingTimelineDay()
    }

    private func returnFromQuote(proxy: ScrollViewProxy) {
        guard let origin = quoteReturnAnchors.last else { return }
        let message: ChatMessage?
        do {
            message = try MsgDao.shared.getMessageById(origin.messageId)
        } catch {
            NoticeCenter.shared.show(interfaceText("history_load_failed"))
            return
        }
        guard let message, message.conversationUuid == viewModel.targetUuid, message.isRevoked == 0 else {
            quoteReturnAnchors.removeLast()
            NoticeCenter.shared.show(interfaceText("search_target_unavailable"))
            return
        }
        guard viewModel.revealMessage(origin.messageId) else {
            NoticeCenter.shared.show(interfaceText("history_load_failed"))
            return
        }
        retryingLatestJump = false
        quoteReturnAnchors.removeLast()
        highlightMessage(nil)
        applyInitialHistoryPosition(origin.messageId, proxy: proxy,
                                    anchor: UnitPoint(x: 0.5, y: origin.position), animated: true)
    }

    private func messageRow(_ message: ChatMessage, albumTile: Bool = false,
                            joinsOlder: Bool = false, joinsNewer: Bool = false) -> some View {
        let checked = viewModel.selectedMessages.contains(message.id)
        let selectionAction = interfaceText(checked ? "deselect_chat_message" : "select_chat_message")
        return HStack(alignment: .top, spacing: 10) {
            if viewModel.isSelecting && !albumTile {
                Button { selectMessageFromRow(message) } label: {
                    Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 20))
                }
                .buttonStyle(.borderless).disabled(viewModel.savingSelection || message.isRevoked != 0)
                .help(selectionAction).accessibilityLabel(selectionAction)
                .accessibilityAddTraits(checked ? .isSelected : [])
            }
            ChatBubbleView(
                message: message,
                peerAlias: viewModel.conversation?.alias ?? "User",
                replyAuthor: viewModel.replyAuthors[message.messageId] ?? interfaceText("quoted_message"),
                peerAvatarPath: viewModel.conversation?.avatarPath,
                forwardLabel: viewModel.forwardedMessages[message.messageId].map { info in
                    if let name = info.name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        return interfaceText.format("forwarded_from", name)
                    }
                    return interfaceText("forwarded_message")
                },
                albumTile: albumTile,
                maximumMessageBodyWidth: maximumMessageBodyWidth,
                joinsOlder: joinsOlder,
                joinsNewer: joinsNewer,
                showAvatars: !viewModel.isSelecting,
                quickReplyEnabled: viewModel.canCompose && editingMessage == nil && quoteTarget == nil &&
                    !chatContentCovered && !restoringHistory,
                onMediaClick: { openMedia($0) },
                onReply: {
                    if viewModel.isChatMounted && viewModel.canCompose && editingMessage == nil && !chatContentCovered {
                        replyingTo = $0
                        draftFocusRevision += 1
                    }
                },
                onQuoteText: viewModel.canCompose && editingMessage == nil ? { target in
                    if (try? MsgDao.shared.canQuoteText(target)) == true { quoteTarget = target }
                    else { NoticeCenter.shared.show(interfaceText("quote_selection_unavailable")) }
                } : nil,
                quotePicker: quoteTarget?.id == message.id && quoteTarget?.content == message.content
                    ? AnyView(ReplyQuotePicker(message: message, onDismiss: { quoteTarget = nil }) { selection, preview in
                        guard viewModel.isChatMounted, viewModel.canCompose, editingMessage == nil,
                              AppLockSession.shared.canShowContent, quoteTarget?.id == message.id,
                              (try? MsgDao.shared.canQuoteText(message)) == true else {
                            quoteTarget = nil
                            NoticeCenter.shared.show(interfaceText("quote_selection_unavailable"))
                            return
                        }
                        viewModel.setReply(message, quote: selection, excerpt: preview)
                        quoteTarget = nil
                        draftFocusRevision += 1
                    }.onDisappear { if quoteTarget?.id == message.id { quoteTarget = nil } }) : nil,
                onQuotedClick: { target in
                    requestQuotedMessage(target, source: message.messageId, quote: message.replyToContent, openingQuote: true)
                },
                quoteHighlight: message.id == highlightedMessageId ? highlightedQuote : nil,
                onRevoke: { viewModel.revokeMessage(messageId: $0) },
                onCancelQueuedSend: { requestSendCancellation(message.messageId) },
                onRetrySend: { retrySend(message.messageId) },
                retryingSend: retryingSends.contains(message.messageId),
                onDeleteLocally: { requestLocalDeletion([message.messageId]) },
                onEdit: { beginEdit($0) },
                onReEdit: { if viewModel.canCompose && editingMessage == nil { inputText = $0 } },
                onSelect: { requestEditExit { viewModel.toggleSelection(message) } },
                onForward: { forwardMessages([message.id]) },
                onSave: { viewModel.saveMessages([message.id]) },
                onPinState: { viewModel.messageIsPinned(message.id) },
                onSetPinned: { pinned, notify in viewModel.setMessagePinned(message.id, pinned: pinned, notify: notify) },
                onContactClick: { if !viewModel.isSelecting { contactCardMessage = $0 } },
                onLocationClick: { if !viewModel.isSelecting { locationMessage = $0 } },
                onPhotoOpen: { message, frame, thumbnail in
                    openMedia(message, origin: MediaPreviewOrigin(frame: frame, thumbnail: thumbnail))
                }
            )
            .environment(\.chatInlineAudio, ChatInlineAudio(playback: inlineAudioID == message.messageId ? inlineAudio : nil) { media in
                toggleInlineAudio(message, media: media)
            })
            .allowsHitTesting(!viewModel.isSelecting).accessibilityHidden(viewModel.isSelecting)
            .overlay {
                if viewModel.isSelecting {
                    Button { selectMessageFromRow(message) } label: {
                        ZStack(alignment: .topTrailing) {
                            Color.clear
                            if albumTile {
                                Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                                    .font(.system(size: 19, weight: .semibold))
                                    .foregroundStyle(.white)
                                    .padding(3)
                                    .background(checked ? Color.accentColor : Color.black.opacity(0.65), in: Circle())
                                    .padding(7)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain).disabled(viewModel.savingSelection || message.isRevoked != 0)
                    .accessibilityLabel(selectionAction)
                    .accessibilityAddTraits(checked ? .isSelected : [])
                    .accessibilityHidden(!albumTile)
                }
            }
        }
        .background(viewModel.selectedMessages.contains(message.id) ? Color.accentColor.opacity(0.1) : Color.clear)
        .overlay {
            let target = message.messageId == highlightedMessageId
            RoundedRectangle(cornerRadius: 10).fill(Color.accentColor)
                .opacity(target ? 0.20 : 0)
                .animation(reduceMotion ? nil : .easeOut(duration: target ? 0.13 : 0.24), value: target)
                .allowsHitTesting(false).accessibilityHidden(true)
        }
        .id(message.messageId)
        .background(GeometryReader { geometry in
            Color.clear.preference(key: MessageFramePreference.self,
                value: [message.messageId: geometry.frame(in: .named("messageViewport"))])
        })
    }

    private func selectMessageFromRow(_ message: ChatMessage) {
        let event = NSApp.currentEvent
        let extending = event?.modifierFlags.contains(.shift) == true &&
            (event?.type == .leftMouseDown || event?.type == .leftMouseUp)
        viewModel.toggleSelection(message, extending: extending)
    }

    private func beginRecording(){
        guard viewModel.canCompose, capturedRecording == nil, !stickerBusy, editingMessage == nil, let draft = viewModel.captureMediaDraft(),
              CallManager.shared.callState == .idle || CallManager.shared.callState == .ended else { return }
        closeStickers(restoreFocus: false)
        recordingDraft=draft;recordingReply=replyingTo;recordingOptions=viewModel.sendOptions;showAttachmentMenu=false;showVoiceRecording=true
    }

    private func reviewCapturedRecording() {
        let state = CallManager.shared.callState
        guard recordingReviewReady, let result = capturedRecording, NSApp.isActive, !chatContentCovered,
              state == .idle || state == .ended,
              AppLockSession.shared.canShowContent, viewModel.isChatMounted else { return }
        recordingReviewReady = false
        capturedRecording = nil
        confirmMedia(.recording(result), model: viewModel, reply: recordingReply, draft: recordingDraft, options: recordingOptions)
        recordingDraft = nil
        recordingReply = nil
    }
    private func confirmMedia(_ source: MediaSendSource, model: ChatViewModel, reply: ChatMessage?,
                              draft: ConversationDraft?, options: SendOptions) {
        confirmMedia([source], model: model, reply: reply, draft: draft, options: options)
    }

    private func confirmMedia(_ sources: [MediaSendSource], model: ChatViewModel, reply: ChatMessage?,
                              draft: ConversationDraft?, options: SendOptions, asFile: Bool = false) {
        guard mediaConfirmation == nil, !sources.isEmpty else { return }
        guard sources.count <= 10 else { NoticeCenter.shared.show(interfaceText("attachment_limit")); return }
        let recipient = model.conversation?.alias ?? "User"
        mediaConfirmation = MediaSendRequest(sources: sources, recipient: recipient, initial: options, initialAsFile: asFile) { items, choice, started, finished in
            model.sendMedia(sources: items, replyTo: reply, draft: draft, choice: choice,
                            onResult: finished, onPreparationStarted: started)
        }
    }

    private func pasteClipboardImage() -> Bool {
        guard viewModel.canCompose, editingMessage == nil, mediaConfirmation == nil, !stickerBusy else { return false }
        let pasteboard = NSPasteboard.general
        let files = (pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? [])
            .filter(\.isFileURL)
        if !files.isEmpty {
            guard canAcceptFileDrop, let draft = viewModel.captureMediaDraft() else { return true }
            guard files.count <= 10 else {
                NoticeCenter.shared.show(interfaceText("attachment_limit"))
                return true
            }
            let model = viewModel
            let reply = replyingTo
            let options = model.sendOptions
            let token = UUID()
            pendingDrop = token
            Task { @MainActor in
                await reviewExternalFiles(files, token: token, model: model, reply: reply,
                                          draft: draft, options: options, asFile: false, providersSkipped: false)
            }
            return true
        }
        guard let source = PastedImageSource.read(from: pasteboard) else { return false }
        closeStickers(restoreFocus: false)
        let message = interfaceText("paste_image_failed")
        let failed = { NoticeCenter.shared.show(message) }
        guard let send = viewModel.capturePastedImageSender(onFail: failed) else { failed(); return true }
        showAttachmentMenu = false
        Task { @MainActor in
            do {
                let (bytes, ext) = try await EncryptedMediaWork.run { try source.encodedImage() }
                guard AppLockSession.shared.canShowContent else { return }
                let mime = UTType(filenameExtension: ext)?.preferredMIMEType ?? "image/png"
                send(.bytes(bytes, "image.\(ext)", mime))
            } catch { failed() }
        }
        return true
    }
    private func confirmFiles(_ urls: [URL], model: ChatViewModel, reply: ChatMessage?,
                              draft: ConversationDraft, options: SendOptions, asFile: Bool = false) {
        var seen = Set<URL>()
        let sources = urls.filter { seen.insert($0.standardizedFileURL).inserted }.map(MediaSendSource.file)
        confirmMedia(sources, model: model, reply: reply, draft: draft, options: options, asFile: asFile)
    }

    private func openFilePicker(allowed: [UTType], asFile: Bool = false) {
        guard viewModel.canCompose, editingMessage == nil, mediaConfirmation == nil, attachmentFilePicker == nil,
              let draft = viewModel.captureMediaDraft() else { return }
        showAttachmentMenu = false
        let model = viewModel
        let reply = replyingTo
        let options = model.sendOptions
        let panel = NSOpenPanel()
        panel.allowedContentTypes = allowed
        presentAttachmentPicker(panel, model: model) { urls in
            confirmFiles(urls, model: model, reply: reply, draft: draft, options: options, asFile: asFile)
        }
    }

    private func presentAttachmentPicker(_ panel: NSOpenPanel, model: ChatViewModel,
                                         continueOnCancel: Bool = false,
                                         onInvalidated: @escaping () -> Void = {},
                                         release: @escaping () -> Void = {}, choose: @escaping ([URL]) -> Void) {
        guard attachmentFilePicker == nil else { release(); onInvalidated(); return }
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        attachmentFilePicker = panel
        let finished: (NSApplication.ModalResponse) -> Void = { response in
            defer { release() }
            guard attachmentFilePicker === panel else { return }
            attachmentFilePicker = nil
            guard model.isChatMounted, model.canCompose, editingMessage == nil,
                  mediaConfirmation == nil, AppLockSession.shared.canShowContent else { onInvalidated(); return }
            if response == .OK { choose(panel.urls) }
            else if continueOnCancel { choose([]) }
            restoreComposerAfterAttachment()
        }
        if let window = NSApp.keyWindow { panel.beginSheetModal(for: window, completionHandler: finished) }
        else { panel.begin(completionHandler: finished) }
    }

    private func restoreComposerAfterAttachment() {
        guard viewModel.isChatMounted, viewModel.canCompose, AppLockSession.shared.canShowContent,
              editingMessage == nil, !chatContentCovered, pendingDrop == nil else { return }
        draftFocusRevision += 1
    }

    private var canAcceptFileDrop: Bool {
        pendingDrop == nil && canFinishFileDrop
    }

    private var canFinishFileDrop: Bool {
        viewModel.isChatMounted && viewModel.canCompose && AppLockSession.shared.canShowContent &&
            editingMessage == nil && !chatContentCovered
    }

    private func updateDropMode(_ mode: ChatDropMode?) {
        guard dropMode != mode else { return }
        withAnimation(reduceMotion ? nil : .linear(duration: 0.20)) { dropMode = mode }
    }

    private func dropRegion(_ label: String, icon: String, active: Bool) -> some View {
        VStack(spacing: 7) {
            Image(systemName: icon).font(.system(size: 26)).foregroundStyle(Color.accentColor)
            Text(interfaceText(label)).font(.headline)
        }
        .padding(12)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .overlay {
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.accentColor.opacity(active ? 0.18 : 0))
        }
        .overlay {
            RoundedRectangle(cornerRadius: 12)
                .strokeBorder(active ? Color.accentColor : Color.secondary.opacity(0.25), lineWidth: active ? 2 : 1)
        }
    }

    private func handleFileDrop(providers: [NSItemProvider], asFile: Bool) -> Bool {
        guard canAcceptFileDrop, !providers.isEmpty,
              let draft = viewModel.captureMediaDraft() else { return false }
        guard providers.count <= 10 else { NoticeCenter.shared.show(interfaceText("attachment_limit")); return true }
        let model = viewModel
        let reply = replyingTo
        let options = model.sendOptions
        let token = UUID()
        pendingDrop = token
        let providerCount = providers.count
        DroppedFileSelection.load(providers, allowPartial: true) { urls in
            guard pendingDrop == token else { return }
            guard let urls else {
                pendingDrop = nil
                if canFinishFileDrop {
                    NoticeCenter.shared.show(InterfaceStrings.current()("dropped_files_open_failed"))
                }
                return
            }
            Task { @MainActor in
                await reviewExternalFiles(urls, token: token, model: model, reply: reply,
                                         draft: draft, options: options, asFile: asFile,
                                         providersSkipped: urls.count != providerCount)
            }
        }
        return true
    }

    @MainActor
    private func reviewExternalFiles(_ urls: [URL], token: UUID, model: ChatViewModel, reply: ChatMessage?,
                                    draft: ConversationDraft, options: SendOptions, asFile: Bool,
                                    providersSkipped: Bool) async {
        let items = await Task.detached(priority: .userInitiated) { () -> [ChatDroppedItem] in
            var items: [ChatDroppedItem] = []
            for url in urls {
                let scoped = url.startAccessingSecurityScopedResource()
                let kind = try? url.resourceValues(forKeys: [.isDirectoryKey, .isRegularFileKey])
                if scoped { url.stopAccessingSecurityScopedResource() }
                if kind?.isDirectory == true { items.append(.folder(url)) }
                else if kind?.isRegularFile == true { items.append(.file(url)) }
            }
            return items
        }.value
        guard pendingDrop == token else { return }
        let unreadable = providersSkipped || items.count != urls.count
        var knownFiles = Set(items.compactMap { item -> URL? in
            if case .file(let url) = item { return url.standardizedFileURL }
            return nil
        })
        var skippedFolderForLimit = false

        func review(_ remaining: ArraySlice<ChatDroppedItem>, files: [URL]) {
            guard pendingDrop == token else { return }
            guard canFinishFileDrop else { pendingDrop = nil; return }
            guard let next = remaining.first else {
                pendingDrop = nil
                let warnings = [
                    unreadable ? interfaceText("dropped_files_open_failed") : nil,
                    skippedFolderForLimit ? interfaceText("dropped_folder_limit") : nil
                ].compactMap { $0 }
                if !warnings.isEmpty { NoticeCenter.shared.show(warnings.joined(separator: "\n")) }
                if files.isEmpty { restoreComposerAfterAttachment() }
                else { confirmFiles(files, model: model, reply: reply, draft: draft, options: options, asFile: asFile) }
                return
            }

            let rest = remaining.dropFirst()
            switch next {
            case .file(let url):
                review(rest, files: files + [url])
            case .folder(let folder):
                guard knownFiles.count < 10 else {
                    skippedFolderForLimit = true
                    review(rest, files: files)
                    return
                }
                let panel = NSOpenPanel()
                panel.message = interfaceText("choose_dropped_folder_files")
                let scoped = folder.startAccessingSecurityScopedResource()
                panel.directoryURL = folder
                presentAttachmentPicker(panel, model: model, continueOnCancel: true, onInvalidated: {
                    if pendingDrop == token { pendingDrop = nil }
                }, release: {
                    if scoped { folder.stopAccessingSecurityScopedResource() }
                }) { selected in
                    var candidateFiles = knownFiles
                    let additions = selected.filter { candidateFiles.insert($0.standardizedFileURL).inserted }
                    guard candidateFiles.count <= 10 else {
                        skippedFolderForLimit = true
                        review(rest, files: files)
                        return
                    }
                    knownFiles = candidateFiles
                    review(rest, files: files + additions)
                }
            }
        }
        review(items[...], files: [])
    }

    private func forwardMessages(_ ids: [String]) {
        guard !viewModel.savingSelection else {return}
        let version=viewModel.selectionVersion;let clearAfter=viewModel.selectedMessages==Set(ids)
        forwarding=ForwardMessageRequest(sourcePeer:viewModel.targetUuid,ids:ids,onCommitted:{
            if clearAfter {viewModel.clearSelection(ifVersion:version)}
            NoticeCenter.shared.show(interfaceText("forwarding_queued"))
        })
    }
}

fileprivate extension View {
    @ViewBuilder
    func applyTimelineScrollAnchor(followingLatest: Bool) -> some View {
        if #available(macOS 15.0, *) {
            self.defaultScrollAnchor(.bottom, for: .initialOffset)
                .defaultScrollAnchor(.bottom, for: .alignment)
                .defaultScrollAnchor(followingLatest ? .bottom : nil, for: .sizeChanges)
        } else if #available(macOS 14.0, *) {
            self.defaultScrollAnchor(followingLatest ? .bottom : nil)
        } else {
            self
        }
    }
}

private struct LiveMessageArrival: ViewModifier {
    let enabled: Bool
    let isMine: Bool
    @State private var revealed: Bool

    init(arrival: ContinuousClock.Instant?, enabled: Bool, isMine: Bool) {
        self.enabled = enabled
        self.isMine = isMine
        let fresh = arrival.map { $0.duration(to: ContinuousClock().now) < .milliseconds(120) } ?? false
        _revealed = State(initialValue: !enabled || !fresh)
    }

    func body(content: Content) -> some View {
        let showFullRow = revealed || !enabled
        return content
            .opacity(showFullRow ? 1 : 0)
            .scaleEffect(showFullRow ? 1 : 0.9, anchor: isMine ? .bottomTrailing : .bottomLeading)
            .onAppear {
                guard !revealed else { return }
                withAnimation(enabled ? .easeOut(duration: 0.25) : nil) { revealed = true }
            }
    }
}

private struct MessageFramePreference: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, latest in latest })
    }
}

private struct TimelineBottomPreference: PreferenceKey {
    static let defaultValue: CGFloat? = nil
    static func reduce(value: inout CGFloat?, nextValue: () -> CGFloat?) {
        value = nextValue() ?? value
    }
}


private struct PastedImageSource: Sendable {
    let data: Data
    private static let pasteboardTypes: [NSPasteboard.PasteboardType] = [
        .png, .init("public.jpeg"), .init("com.compuserve.gif"), .tiff
    ]

    static func canRead(from pasteboard: NSPasteboard) -> Bool {
        pasteboard.availableType(from: pasteboardTypes) != nil ||
            pasteboard.canReadObject(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true])
    }

    static func read(from pasteboard: NSPasteboard) -> PastedImageSource? {
        for type in pasteboardTypes {
            if let data = pasteboard.data(forType: type) { return PastedImageSource(data: data) }
        }
        return nil
    }

    func encodedImage() throws -> (Data, String) {
        let maximumBytes = 100 * 1024 * 1024
        guard data.count <= maximumBytes else { throw CocoaError(.fileReadTooLarge) }
        guard let image = CGImageSourceCreateWithData(data as CFData, nil),
              let kind = CGImageSourceGetType(image),
              let properties = CGImageSourceCopyPropertiesAtIndex(image, 0, nil) as? [String: Any],
              let width = properties[kCGImagePropertyPixelWidth as String] as? Int,
              let height = properties[kCGImagePropertyPixelHeight as String] as? Int,
              width > 0, height > 0, width <= (64 * 1024 * 1024) / height else {
            throw CocoaError(.fileReadCorruptFile)
        }
        switch kind as String {
        case UTType.png.identifier: return (data, "png")
        case UTType.jpeg.identifier: return (data, "jpg")
        case UTType.gif.identifier: return (data, "gif")
        case UTType.tiff.identifier:
            guard let bitmap = NSBitmapImageRep(data: data),
                  let png = bitmap.representation(using: .png, properties: [:]) else { throw CocoaError(.fileReadCorruptFile) }
            guard png.count <= maximumBytes else { throw CocoaError(.fileReadTooLarge) }
            return (png, "png")
        default: throw CocoaError(.fileReadCorruptFile)
        }
    }
}

enum ComposerHistoryCommand: Equatable {
    case editLatest, previousReply, nextReply, cancelAccessory
}

struct ImagePasteComposer: NSViewRepresentable {
    @Binding var text: String
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.chatTextScale) private var chatTextScale
    let onPasteImage: () -> Bool
    let onSubmit: () -> Void
    let onTyping:(Bool)->Void
    var onHistoryCommand: ((ComposerHistoryCommand) -> Bool)? = nil
    var focusRevision = 0
    var accessibilityTitle = ""
    var readOnly = false
    var tabNavigates = false
    var maximumHeight: CGFloat? = nil
    private var horizontalInset: CGFloat { maximumHeight == nil ? 8 : 10 }
    private var font: NSFont { .systemFont(ofSize: NSFont.systemFontSize * chatTextScale) }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> NSScrollView {
        let scroll = NSScrollView()
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.drawsBackground = false
        scroll.borderType = .noBorder
        let editor = PasteTextView()
        editor.isRichText = false
        editor.isEditable = isEnabled && !readOnly
        editor.importsGraphics = false
        editor.allowsUndo = true
        editor.isVerticallyResizable = true
        editor.isHorizontallyResizable = false
        editor.autoresizingMask = [.width]
        editor.font = font
        editor.textColor = .labelColor
        editor.drawsBackground = false
        editor.textContainerInset = NSSize(width: horizontalInset, height: 8)
        editor.textContainer?.lineFragmentPadding = 0
        editor.textContainer?.widthTracksTextView = true
        editor.textContainer?.containerSize = NSSize(width: 0, height: CGFloat.greatestFiniteMagnitude)
        editor.delegate = context.coordinator
        editor.onPasteImage = onPasteImage
        editor.onSubmit = onSubmit
        editor.onHistoryCommand = onHistoryCommand
        scroll.documentView = editor
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        context.coordinator.parent = self
        guard let editor = scroll.documentView as? PasteTextView else { return }
        editor.isEditable = isEnabled && !readOnly
        editor.onPasteImage = onPasteImage
        editor.onSubmit = onSubmit
        editor.onHistoryCommand = onHistoryCommand
        editor.setAccessibilityLabel(accessibilityTitle)
        editor.interfaceText = interfaceText
        editor.tabNavigates = tabNavigates
        if editor.font?.pointSize != font.pointSize { editor.font = font }
        if editor.string != text {
            editor.string = text
            editor.setSelectedRange(NSRange(location: text.utf16.count, length: 0))
        }
        let coordinator = context.coordinator
        if editor.isEditable && focusRevision != coordinator.focusRevision {
            let requestedRevision = focusRevision
            DispatchQueue.main.async { [weak editor, weak coordinator] in
                guard let editor, editor.isEditable, let window = editor.window, let coordinator,
                      coordinator.focusRevision != requestedRevision,
                      coordinator.parent.focusRevision == requestedRevision else { return }
                if window.makeFirstResponder(editor) {
                    coordinator.focusRevision = requestedRevision
                }
            }
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, nsView: NSScrollView, context: Context) -> CGSize? {
        let width = proposal.width ?? 240
        let storage = NSTextStorage(string: text.isEmpty ? " " : text, attributes: [.font: font])
        let layout = NSLayoutManager()
        let container = NSTextContainer(size: NSSize(width: max(20, width - 2 * horizontalInset), height: CGFloat.greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        layout.addTextContainer(container); storage.addLayoutManager(layout)
        layout.ensureLayout(for: container)
        let lineHeight = layout.defaultLineHeight(for: font)
        let usedHeight = layout.usedRect(for: container).height + (text.hasSuffix("\n") ? lineHeight : 0)
        let limit = maximumHeight ?? (lineHeight * 5 + 16)
        return CGSize(width: width, height: max(36, min(limit, usedHeight + 16)))
    }

    final class Coordinator: NSObject, NSTextViewDelegate {
        var parent: ImagePasteComposer
        var focusRevision = 0
        init(_ parent: ImagePasteComposer) { self.parent = parent }
        func textDidEndEditing(_ notification:Notification){parent.onTyping(false)}
        func textDidChange(_ notification: Notification) {
            guard let editor = notification.object as? NSTextView else { return }
            parent.text = editor.string
            parent.onTyping(editor.window?.firstResponder === editor && !editor.string.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty)
        }
    }

    final class PasteTextView: NSTextView {
        var interfaceText = InterfaceStrings()
        var tabNavigates = false
        var onPasteImage: (() -> Bool)?
        var onSubmit: (() -> Void)?
        var onHistoryCommand: ((ComposerHistoryCommand) -> Bool)?
        override func menu(for event: NSEvent) -> NSMenu? {
            let menu = super.menu(for: event) ?? NSMenu()
            guard isEditable, !hasMarkedText() else { return menu }
            menu.addItem(.separator())
            let title = interfaceText("formatting")
            let formats = NSMenu(title: title)
            for (kind, key, shifted) in [
                ("bold", "b", false), ("italic", "i", false), ("underline", "u", false),
                ("strike", "x", true), ("code", "m", true), ("spoiler", "p", true),
                ("quote", "9", true), ("code_block", "", false)
            ] {
                let item = NSMenuItem(title: interfaceText("format_" + kind),
                                      action: #selector(applySelectedFormat(_:)), keyEquivalent: key)
                item.keyEquivalentModifierMask = shifted ? [.command, .shift] : .command
                item.target = self
                item.representedObject = kind
                formats.addItem(item)
            }
            let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            item.submenu = formats
            menu.addItem(item)
            return menu
        }
        @objc private func applySelectedFormat(_ sender:NSMenuItem){if let kind=sender.representedObject as? String{formatSelection(kind)}}
        private func formatSelection(_ kind:String){
            guard isEditable,!hasMarkedText() else{return}
            let range=selectedRange()
            do {
                let result=try SecurityCore.call(["operation":"format_message_selection","text":string,"start":range.location,"end":range.location+range.length,"kind":kind])
                guard let text=result["text"] as? String,let start=result["start"] as? Int,let end=result["end"] as? Int else{throw SecurityCore.Failure.invalidResponse}
                insertText(text,replacementRange:NSRange(location:0,length:string.utf16.count));setSelectedRange(NSRange(location:start,length:end-start))
            }catch{NoticeCenter.shared.show(interfaceText("format_unavailable"))}
        }
        private func formatShortcut(_ event:NSEvent)->Bool {
            let modifiers=event.modifierFlags.intersection([.command,.shift,.control,.option])
            guard isEditable,!hasMarkedText(),modifiers == .command || modifiers == [.command,.shift] else{return false}
            let key=event.charactersIgnoringModifiers?.lowercased() ?? ""
            let kind:String?
            if modifiers.contains(.shift){if event.keyCode==25{kind="quote"}else{switch key {case "x":kind="strike";case "m":kind="code";case "p":kind="spoiler";default:kind=nil}}}
            else{switch key{case "b":kind="bold";case "i":kind="italic";case "u":kind="underline";default:kind=nil}}
            guard let kind else{return false};formatSelection(kind);return true
        }
        override func performKeyEquivalent(with event:NSEvent)->Bool{if formatShortcut(event){return true};return super.performKeyEquivalent(with:event)}
        override func keyDown(with event: NSEvent) {
            if formatShortcut(event){return}
            let modifiers = event.modifierFlags.intersection([.shift, .control, .option, .command])
            if isEditable, !hasMarkedText() {
                if tabNavigates, event.keyCode == 48, let window {
                    if modifiers.isEmpty { window.selectNextKeyView(self); return }
                    if modifiers == .shift { window.selectPreviousKeyView(self); return }
                }
                if event.keyCode == 126, modifiers.isEmpty, !event.isARepeat, onHistoryCommand?(.editLatest) == true { return }
                if modifiers == .command, event.keyCode == 126 || event.keyCode == 125,
                   onHistoryCommand?(event.keyCode == 126 ? .previousReply : .nextReply) == true { return }
                if event.keyCode == 53, modifiers.isEmpty, onHistoryCommand?(.cancelAccessory) == true { return }
            }
            if (event.keyCode == 36 || event.keyCode == 76), isEditable, !hasMarkedText(),
               modifiers.isEmpty {
                onSubmit?()
                return
            }
            super.keyDown(with: event)
        }
        override func paste(_ sender: Any?) {
            if isEditable, !hasMarkedText(), onPasteImage?() == true { return }
            super.paste(sender)
        }
        override func validateUserInterfaceItem(_ item: NSValidatedUserInterfaceItem) -> Bool {
            if item.action == #selector(applySelectedFormat(_:)){return isEditable && !hasMarkedText()}
            if item.action == #selector(paste(_:)), isEditable, !hasMarkedText(),
               PastedImageSource.canRead(from: .general) { return true }
            return super.validateUserInterfaceItem(item)
        }
    }
}
