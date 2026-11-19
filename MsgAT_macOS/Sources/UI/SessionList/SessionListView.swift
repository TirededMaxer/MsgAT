import SwiftUI

private struct AdjacentChatCommand: FocusedValueKey {
    typealias Value = @MainActor (Bool) -> Void
}

extension FocusedValues {
    var moveToAdjacentChat: (@MainActor (Bool) -> Void)? {
        get { self[AdjacentChatCommand.self] }
        set { self[AdjacentChatCommand.self] = newValue }
    }
}

public struct SessionListView: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    @ObservedObject var viewModel: SessionListViewModel
    @Binding var selectedConversationUuid: String?
    var onSearch: () -> Void
    var onSavedMessages: ()->Void
    var onCallHistory: ()->Void
    var onForwardTasks: ()->Void
    var onOpenQueuedMessage: ((String, String?) throws -> Bool)?
    private let sendQueuePresented: Binding<Bool>?
    private let onDismissSendQueue: () -> Void
    private let contactsPresented: Binding<Bool>?
    private let onOpenContact: ((String) -> Void)?
    private let onDismissContacts: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var folderIndicator
    @State private var showAddDialog = false
    @State private var showContacts=false
    private var contactsPresentation: Binding<Bool> { contactsPresented ?? $showContacts }
    @State private var showStickerLibrary = false
    @State private var showPrivacy=false
    @ObservedObject private var appLock = AppLockSession.shared
    @State private var showStorage = false
    @State private var showFolders=false
    @State private var showSendQueue=false
    private var sendQueuePresentation: Binding<Bool> { sendQueuePresented ?? $showSendQueue }
    @State private var archiveNotice: ArchiveNotice?
    @State private var archivePending = false
    @State private var archiveNoticeTimer: Task<Void, Never>?
    @State private var archiveRequest: Task<Void, Never>?
    private struct ArchiveNotice {
        let peer: String
        let archived: Bool
    }
    @State private var showRequestsDialog = false
    @State private var showSettingsDialog = false

    @State private var conversationConfirmation: ConversationConfirmation?
    private struct ConversationConfirmation: Identifiable {
        let id: String
        let name: String
        let deletesContact: Bool
    }
    @State private var keyboardTarget: String?
    private var messagingColors: MessagingColors { MsgATTheme.messagingColors(for: colorScheme) }

    public init(viewModel: SessionListViewModel, selectedConversationUuid: Binding<String?>, onSearch: @escaping () -> Void = {}, onSavedMessages: @escaping ()->Void = {}, onForwardTasks: @escaping ()->Void = {}, onCallHistory: @escaping ()->Void = {}, onOpenQueuedMessage: ((String, String?) throws -> Bool)? = nil,
                sendQueuePresented: Binding<Bool>? = nil, onDismissSendQueue: @escaping () -> Void = {},
                contactsPresented: Binding<Bool>? = nil, onOpenContact: ((String) -> Void)? = nil,
                onDismissContacts: @escaping () -> Void = {}) {
        self.viewModel = viewModel
        self._selectedConversationUuid = selectedConversationUuid
        self.onSearch = onSearch
        self.onSavedMessages=onSavedMessages
        self.onCallHistory=onCallHistory
        self.onForwardTasks=onForwardTasks
        self.onOpenQueuedMessage = onOpenQueuedMessage
        self.sendQueuePresented = sendQueuePresented
        self.onDismissSendQueue = onDismissSendQueue
        self.contactsPresented = contactsPresented
        self.onOpenContact = onOpenContact
        self.onDismissContacts = onDismissContacts
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                HStack(spacing: 6) {
                    if viewModel.syncState == .syncing {
                        ProgressView().controlSize(.mini).tint(MsgATTheme.connectionDotColor)
                            .frame(width: 12, height: 12)
                    } else {
                        Circle().fill(statusColor).frame(width: 10, height: 10)
                    }
                    Text(statusText).font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Menu {
                    Button(interfaceText("chat_folders")){showFolders=true}
                    Button(interfaceText("contacts")) { contactsPresentation.wrappedValue = true }
                    Button(interfaceText("sticker_library")) { showStickerLibrary = true }
                    Button(interfaceText("privacy")){showPrivacy=true}
                    Button(interfaceText(appLock.enabled ? "disable_app_lock" : "enable_app_lock"), action: appLock.toggle)
                        .disabled(appLock.authenticating)
                    if appLock.enabled { Button(interfaceText("lock_now"), action: appLock.lockNow) }
                    Button(interfaceText("storage_usage")) { showStorage = true }
                    Button(interfaceText("add_friend")) { showAddDialog = true }
                    Button(interfaceText("friend_requests")) { showRequestsDialog = true }
                    Button(interfaceText("edit_profile")){viewModel.showIdentityDialog=true}
                    Button(interfaceText("settings")) { showSettingsDialog = true }
                    Button(interfaceText("search_messages"), action: onSearch)
                    Button(interfaceText("saved_messages"),action:onSavedMessages)
                    Button(interfaceText("call_history"),action:onCallHistory)
                    Button(interfaceText("forwarding_queue"),action:onForwardTasks)
                    Button(interfaceText("sending_queue")) { sendQueuePresentation.wrappedValue = true }
                } label: {
                    Image(systemName: "plus")
                        .frame(width: 20, height: 20)
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .help(interfaceText("menu"))
            }
            .padding(.horizontal, 12)
            .frame(height: 36)

            folderBar
            Divider()
            VStack(spacing: 0) {
                if viewModel.folderFilter == "all" && viewModel.hasArchivedConversations {
                    VStack(spacing: 0) { archiveEntry; Divider() }
                        .transition(.opacity.combined(with: .offset(y: -5)))
                } else if viewModel.folderFilter == "archive" {
                    VStack(spacing: 0) {
                        Button {
                            viewModel.folderFilter = "all"
                        } label: {
                            Label(interfaceText("all_chats"), systemImage: "chevron.backward")
                        }
                        .buttonStyle(.borderless)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        Divider()
                    }
                    .transition(.opacity.combined(with: .offset(y: -5)))
                }
            }
            .clipped()
            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: viewModel.folderFilter)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: viewModel.hasArchivedConversations)
            Group {
            let rows = viewModel.visibleConversations
            if !rows.isEmpty {
                let scope = viewModel.folderFilter
                let pinned = Array(rows.prefix { $0.pinOrder > 0 })
                ScrollViewReader { proxy in
                List(selection: listSelection) {
                    ForEach(rows, id: \.conversation.uuid) { item in
                        let conv = item.conversation
                        let folderID = viewModel.customFolderID
                        let selected = selectedConversationUuid == conv.uuid
                        ConversationSidebarRow(item: item, now: viewModel.clock)
                        .overlay(alignment: .leading) {
                            if selected {
                                Capsule()
                                    .fill(messagingColors.selectionMark)
                                    .frame(width: 3)
                                    .padding(.vertical, 13)
                                    .allowsHitTesting(false)
                                    .accessibilityHidden(true)
                            }
                        }
                        .listRowBackground(selected ? messagingColors.selection : Color.clear)
                        .listRowInsets(EdgeInsets())
                        .tag(conv.uuid)
                        .id(conv.uuid)
                        .moveDisabled(item.pinOrder == 0 || pinned.count < 2)
                        .contextMenu {
                            Button(interfaceText(item.pinOrder > 0 ? "unpin_chat" : "pin_chat")){viewModel.conversationAction(conv.uuid,item.pinOrder>0 ? "unpin":"pin",folderID:folderID)}
                            if let index = pinned.firstIndex(where: { $0.conversation.uuid == conv.uuid }) {
                                if index > 0 {
                                    Button(interfaceText("move_pin_up")) {
                                        viewModel.movePin(scope: scope, peer: conv.uuid, target: pinned[index - 1].conversation.uuid, before: true)
                                    }
                                }
                                if index < pinned.count - 1 {
                                    Button(interfaceText("move_pin_down")) {
                                        viewModel.movePin(scope: scope, peer: conv.uuid, target: pinned[index + 1].conversation.uuid, before: false)
                                    }
                                }
                            }
                            Button(interfaceText(item.archived ? "unarchive_chat" : "archive_chat")) {
                                changeArchive(conv.uuid, archived: !item.archived)
                            }.disabled(archivePending)
                            Button(interfaceText(item.hasUnread ? "mark_chat_read" : "mark_chat_unread")){viewModel.conversationAction(conv.uuid,item.hasUnread ? "read":"unread")}
                            Menu(interfaceText("conversation_notifications")) {
                                ConversationMuteMenuItems(muteUntil: item.muteUntil) { action, until in
                                    viewModel.conversationAction(conv.uuid, action, until: until)
                                }
                            }
                            Divider()
                            Button(interfaceText("clear_messages"), role: .destructive) {
                                conversationConfirmation = .init(id: conv.uuid, name: conv.alias, deletesContact: false)
                            }
                            Button(interfaceText("delete_friend"), role: .destructive) {
                                conversationConfirmation = .init(id: conv.uuid, name: conv.alias, deletesContact: true)
                            }
                        }
                    }
                    .onMove { offsets, insertion in
                        guard offsets.count == 1, let source = offsets.first, pinned.indices.contains(source) else { return }
                        let target = insertion > source ? insertion - 1 : insertion
                        guard pinned.indices.contains(target), source != target else { return }
                        viewModel.movePin(scope: scope, peer: pinned[source].conversation.uuid,
                                          target: pinned[target].conversation.uuid, before: target < source)
                    }
                }
                .listStyle(.sidebar)
                .scrollContentBackground(.hidden)
                .onChange(of: selectedConversationUuid) { peer in
                    if let peer, keyboardTarget == peer {
                        keyboardTarget = nil
                        proxy.scrollTo(peer)
                    }
                }
                }
                .transition(.opacity)
            } else if viewModel.folderFilter == "all" && viewModel.hasArchivedConversations {
                Spacer()
            } else {
                Text(interfaceText(emptyFolderMessage))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 20)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .transition(.opacity)
            }
            }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: viewModel.folderFilter)
            VStack(spacing: 0) {
                if let notice = archiveNotice {
                    VStack(spacing: 0) {
                        Divider()
                        HStack {
                            Text(interfaceText(notice.archived ? "chat_archived" : "chat_unarchived"))
                                .font(.callout).foregroundStyle(.secondary)
                            Spacer()
                            Button(interfaceText("archive_undo")) {
                                changeArchive(notice.peer, archived: !notice.archived, showUndo: false)
                            }
                            .disabled(archivePending)
                        }
                        .padding(.horizontal, 12).padding(.vertical, 8)
                    }
                    .transition(.opacity.combined(with: .offset(y: 5)))
                }
            }
            .clipped()
            .allowsHitTesting(archiveNotice != nil && !archivePending)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: archiveNotice != nil)
        }
        .frame(minWidth: 260)
        .background(messagingColors.sidebar)
        .onDisappear {
            archiveNoticeTimer?.cancel()
            archiveRequest?.cancel()
            archiveNotice = nil
        }
        .onChange(of: appLock.canShowContent) { visible in
            if !visible {
                archiveNoticeTimer?.cancel()
                archiveRequest?.cancel()
                archiveNotice = nil
                conversationConfirmation = nil
            }
        }
        .navigationTitle("MsgAT")
        .sheet(isPresented: $showAddDialog) {
            AddConversationDialog(
                isPresented: $showAddDialog,
                myUuid: viewModel.currentUuid,
                onConfirm: { uuid in
                    viewModel.addConversation(rawUuid: uuid)
                }
            )
        }
        .sheet(isPresented: $showRequestsDialog) {
            FriendRequestsDialog(
                isPresented: $showRequestsDialog,
                requests: viewModel.friendRequests,
                onAccept: { peer, completion in
                    viewModel.acceptFriendRequest(senderUuid: peer, completion: completion)
                },
                onDecline: { peer, completion in
                    viewModel.declineFriendRequest(senderUuid: peer, completion: completion)
                }
            )
        }
        .focusedSceneValue(\.moveToAdjacentChat, moveToAdjacentChat)
        .onChange(of:selectedConversationUuid){peer in if let peer{viewModel.conversationAction(peer,"clear_unread")}}
        .sheet(isPresented:$showFolders){ConversationFoldersView(model:viewModel)}
        .sheet(isPresented: sendQueuePresentation, onDismiss: onDismissSendQueue) {
            SendQueueView(onOpenChat: onOpenQueuedMessage)
        }
        .sheet(isPresented:$showPrivacy){ReadReceiptPrivacyView()}
        .sheet(isPresented: $showStorage) { AttachmentCacheView() }
        .sheet(isPresented: contactsPresentation, onDismiss: onDismissContacts) {
            ContactsView { peer in
                if let onOpenContact { onOpenContact(peer) }
                else { selectedConversationUuid = peer }
            }
        }
        .sheet(isPresented: $showStickerLibrary) { StickerLibraryView() }
        .sheet(isPresented: $showSettingsDialog) {
            SettingsDialog(
                isPresented: $showSettingsDialog,
                initialIp: viewModel.currentIp,
                initialPort: viewModel.currentPort,
                initialNickname: viewModel.currentNickname,
                myUuid: viewModel.currentUuid,
                initialTurnServer: viewModel.currentTurnServer,
                initialTurnPort: viewModel.currentTurnPort,
                initialTurnUsername: viewModel.currentTurnUsername,
                initialTurnPassword: viewModel.currentTurnPassword,
                initialTurnUseTls: viewModel.currentTurnUseTls,
                onSave: { settings in
                    viewModel.saveSettings(settings: settings)
                },
                onResetIdentity: {
                    viewModel.resetIdentity()
                },
                onSaveIdentity: { name, avatar, remove, completion in
                    viewModel.saveIdentity(nickname: name, avatarBytes: avatar, removeAvatar: remove, completion: completion)
                }
            )
        }
        .sheet(isPresented: $viewModel.showIdentityDialog) {
            IdentitySetupDialog(
                isPresented: $viewModel.showIdentityDialog,
                initialNickname:viewModel.currentNickname,initialAvatar:KeychainManager.shared.myAvatarPath,editing:!viewModel.isIdentityMissing,
                onConfirm: { nick, bytes, remove, completion in
                    viewModel.saveIdentity(nickname:nick,avatarBytes:bytes,removeAvatar:remove,completion:completion)
                }
            )
        }
        .onChange(of: viewModel.resetErrorMessage) { message in
            if let message { NoticeCenter.shared.show(interfaceText(message)); viewModel.resetErrorMessage = nil }
        }
        .sheet(item: $conversationConfirmation) { target in
            let action = interfaceText(target.deletesContact ? "delete_friend" : "clear_messages")
            let message = interfaceText.format(target.deletesContact ? "delete_friend_confirm" : "clear_messages_confirm", target.name)
            ConfirmationDialog(message: message, title: action, confirmLabel: action,
                onCancel: { conversationConfirmation = nil }, onConfirm: {
                    conversationConfirmation = nil
                    guard appLock.canShowContent else { return }
                    Task { @MainActor in
                        if target.deletesContact {
                            if await viewModel.deleteConversation(uuid: target.id) {
                                if selectedConversationUuid == target.id { selectedConversationUuid = nil }
                            } else if appLock.canShowContent {
                                NoticeCenter.shared.show(interfaceText("contact_action_failed"))
                            }
                        } else if !(await viewModel.clearMessages(uuid: target.id)), appLock.canShowContent {
                            NoticeCenter.shared.show(interfaceText("clear_messages_failed"))
                        }
                    }
                })
        }
    }

    private func moveToAdjacentChat(_ forward: Bool) {
        guard appLock.canShowContent, let window = NSApp.keyWindow,
              window.attachedSheet == nil, window.sheetParent == nil, NSApp.modalWindow == nil,
              (window.firstResponder as? NSTextView)?.hasMarkedText() != true else { return }
        let rows = viewModel.visibleConversations
        let index = rows.firstIndex { $0.conversation.uuid == selectedConversationUuid }
        let next = index.map { $0 + (forward ? 1 : -1) } ?? 0
        guard rows.indices.contains(next) else { return }
        let target = rows[next].conversation.uuid
        keyboardTarget = target
        selectedConversationUuid = target
    }

    private var listSelection: Binding<String?> {
        Binding(get: { selectedConversationUuid }, set: { selection in
            if let selection { selectedConversationUuid = selection }
        })
    }

    private var emptyFolderMessage: String {
        switch viewModel.folderFilter {
        case "unread": return "no_unread_chats"
        case "archive": return "no_archived_chats"
        default: return "no_chats"
        }
    }

    private func changeArchive(_ peer: String, archived: Bool, showUndo: Bool = true) {
        guard !archivePending, appLock.canShowContent else { return }
        archiveNoticeTimer?.cancel()
        archiveNotice = nil
        archivePending = true
        archiveRequest = Task { @MainActor in
            defer { archivePending = false }
            let changed: Bool = await withCheckedContinuation { continuation in
                SecureMessaging.shared.setConversationArchived(peer, expected: !archived, archived: archived) {
                    continuation.resume(returning: $0)
                }
            }
            guard !Task.isCancelled, appLock.canShowContent else { return }
            guard changed else {
                NoticeCenter.shared.show(interfaceText("archive_change_failed"))
                return
            }
            if showUndo {
                archiveNotice = ArchiveNotice(peer: peer, archived: archived)
                archiveNoticeTimer = Task { @MainActor in
                    do { try await Task.sleep(nanoseconds: 6_000_000_000) } catch { return }
                    archiveNotice = nil
                }
            }
        }
    }

    private var archiveEntry: some View {
        let unread = viewModel.archivedUnreadCount
        return Button {
            viewModel.folderFilter = "archive"
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "archivebox").frame(width: 24)
                Text(interfaceText("archive")).lineLimit(1)
                Spacer()
                if unread > 0 {
                    Text(unread > 99 ? "99+" : String(unread))
                        .font(.caption2.monospacedDigit())
                        .padding(5)
                        .background(.quaternary, in: Circle())
                }
                Image(systemName: "chevron.right").font(.caption.weight(.semibold))
            }
            .padding(.horizontal, 14).frame(height: 42)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(interfaceText("archive"))
        .accessibilityValue(unread > 0 ? interfaceText.format("chat_unread_count", Int64(unread)) : "")
    }

    private var folderBar: some View {
        let folders = viewModel.folderTabs(all: interfaceText("all_chats"), unreadTitle: interfaceText("unread_chats"),
                                           archive: interfaceText("archive"))
        let scrollKey = [viewModel.folderFilter] + folders.map(\.id)
        return ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(folders) { folder in
                        let selected = viewModel.folderFilter == folder.id
                        Button {
                            viewModel.folderFilter = folder.id
                        } label: {
                            HStack(spacing: 5) {
                                Text(folder.title).lineLimit(1)
                                if folder.unreadCount > 0 {
                                    Text(folder.unreadCount > 99 ? "99+" : String(folder.unreadCount))
                                        .font(.caption2.monospacedDigit())
                                        .padding(.horizontal, 5).padding(.vertical, 2)
                                        .background(.quaternary, in: Capsule())
                                }
                            }
                            .font(.system(size: 12, weight: selected ? .semibold : .medium))
                            .foregroundStyle(selected ? Color.accentColor : Color.secondary)
                            .padding(.horizontal, 9)
                            .frame(height: 32)
                            .contentShape(Rectangle())
                            .overlay(alignment: .bottom) {
                                if selected {
                                    Capsule()
                                        .fill(Color.accentColor)
                                        .frame(height: 2)
                                        .matchedGeometryEffect(id: "selection", in: folderIndicator)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(selected ? .isSelected : [])
                        .help(folder.title)
                        .id(folder.id)
                    }
                }
                .padding(.horizontal, 10)
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: viewModel.folderFilter)
            }
            .accessibilityLabel(interfaceText("chat_folders"))
            .task(id: scrollKey) {
                await Task.yield()
                guard !Task.isCancelled else { return }
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.18)) {
                    proxy.scrollTo(viewModel.folderFilter, anchor: .center)
                }
            }
        }
    }

    private var statusText: String {
        switch viewModel.syncState {
        case .synced: return interfaceText("connected")
        case .syncing: return interfaceText("syncing")
        case .disconnected: return interfaceText("disconnected")
        }
    }

    private var statusColor: Color {
        switch viewModel.syncState {
        case .synced:
            return MsgATTheme.connectionDotColor
        case .syncing:
            return MsgATTheme.connectionDotColor
        case .disconnected:
            return .red
        }
    }
}

private struct ConversationSidebarRow: View {
    @Environment(\.interfaceStrings) private var interfaceText

    private func conversationPreview(_ item: ConversationWithUnread) -> String {
        if item.hasDraft { return item.draftText.isEmpty ? interfaceText("reply") : item.draftText }
        switch item.summaryKind {
        case "revoked": return interfaceText("quote_revoked")
        case "attachment": return interfaceText("quote_attachment")
        case "call": return interfaceText("call_history")
        case "contact":
            let prefix = "Contact: "
            let name = item.summary.hasPrefix(prefix) ? String(item.summary.dropFirst(prefix.count)) : item.summary
            return interfaceText("contact_card") + " · " + name
        case "": return displayCallSummary(item.summary, strings: interfaceText)
        default: return item.summary
        }
    }
    let item: ConversationWithUnread
    let now: Date

    private var activityTime: String {
        let timestamp = item.hasDraft ? item.activityAt : item.lastAt
        guard timestamp > 0 else { return "" }
        let date = Date(timeIntervalSince1970: Double(timestamp) / 1_000)
        let calendar = Calendar.current
        if calendar.isDate(date, inSameDayAs: now) {
            return date.formatted(date: .omitted, time: .shortened)
        }
        let startOfToday = calendar.startOfDay(for: now)
        if let cutoff = calendar.date(byAdding: .day, value: -6, to: startOfToday), date >= cutoff, date < startOfToday {
            return date.formatted(.dateTime.weekday(.abbreviated))
        }
        if calendar.isDate(date, equalTo: now, toGranularity: .year) {
            return date.formatted(.dateTime.month(.abbreviated).day())
        }
        return date.formatted(date: .numeric, time: .omitted)
    }

    var body: some View {
        let conversation = item.conversation
        let time = activityTime
        HStack(spacing: 10) {
            AvatarView(avatarPath: conversation.avatarPath, fallbackText: conversation.alias, size: 50, cornerRadius: 25)
                .frame(width: 50, height: 50)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 4) {
                    Text(conversation.alias)
                        .font(.system(size: 14, weight: .semibold))
                        .lineLimit(1)
                    if item.muteUntil > Int64(now.timeIntervalSince1970 * 1_000) {
                        Image(systemName: "bell.slash").font(.system(size: 10))
                            .foregroundStyle(.secondary).accessibilityLabel(interfaceText("conversation_muted"))
                    }
                    Spacer(minLength: 5)
                    if !time.isEmpty {
                        HStack(spacing: 3) {
                            if !item.hasDraft, let status = item.lastReceiptStatus {
                                MessageReceiptMark(status: status)
                                    .id(item.lastID)
                            }
                            Text(time).font(.caption.monospacedDigit()).foregroundStyle(.secondary).lineLimit(1)
                        }
                        .fixedSize(horizontal: true, vertical: false)
                    }
                }
                HStack(alignment: .bottom, spacing: 6) {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        if item.hasDraft {
                            Text(interfaceText("draft_prefix")).fontWeight(.semibold).foregroundStyle(.red)
                                .fixedSize(horizontal: true, vertical: false)
                        }
                        let preview = conversationPreview(item)
                        MessageSummaryText(text: preview)
                            .foregroundStyle(.secondary)
                            .lineLimit(item.hasDraft ? 1 : 2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .font(.caption)
                    if item.pinOrder > 0 {
                        Image(systemName: "pin.fill").font(.caption2)
                            .foregroundStyle(.secondary).accessibilityLabel(interfaceText("chat_pinned"))
                    }
                    if item.hasUnread {
                        CircularUnreadLayout {
                            Text(item.unreadCount > 0 ? String(item.unreadCount) : "•")
                                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                                .foregroundColor(.white)
                                .padding(.horizontal, 5).padding(.vertical, 2)
                        }
                        .background(Color(red: 242/255, green: 38/255, blue: 38/255), in: Circle())
                        .accessibilityLabel(item.unreadCount > 0 ? interfaceText.format("chat_unread_count", item.unreadCount)
                            : interfaceText("chat_marked_unread"))
                    }
                }
                .frame(minHeight: 20)
            }
            .frame(minHeight: 50, alignment: .topLeading)
        }
        .padding(10)
    }
}

private struct CircularUnreadLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let content = subviews.first?.sizeThatFits(.unspecified) ?? .zero
        let diameter = max(20, max(content.width, content.height))
        return CGSize(width: diameter, height: diameter)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: CGPoint(x: bounds.midX, y: bounds.midY), anchor: .center, proposal: .unspecified)
    }
}
