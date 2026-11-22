import Combine
// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import AppKit

@MainActor
struct ForwardMessageRequest: Identifiable {
    let id=UUID()
    let sourcePeer: String?
    let ids: [String]
    let onCommitted: ()->Void
}

@MainActor
private final class ForwardMessagesModel: ObservableObject {
    static let recipientLimit = 20
    @Published var targets: [Conversation] = []
    @Published var selected: [String] = []
    @Published var filter = ""
    @Published var hideSender = false
    @Published var hideCaption = false
    @Published var omitPreview = false
    @Published var options = SendOptions()
    @Published var loading = true
    @Published var loadFailed = false
    @Published var busy = false
    @Published var error: String?

    var visible: [Conversation] {
        let term = filter.trimmingCharacters(in: .whitespacesAndNewlines)
        return term.isEmpty ? targets : targets.filter {
            $0.alias.localizedCaseInsensitiveContains(term) || $0.uuid.localizedCaseInsensitiveContains(term)
        }
    }

    var chosen: [Conversation] {
        let byID = Dictionary(uniqueKeysWithValues: targets.map { ($0.uuid, $0) })
        return selected.compactMap { byID[$0] }
    }

    var canSend: Bool { !busy && !loading && !loadFailed && !selected.isEmpty }

    func load() async {
        loading = true
        loadFailed = false
        defer { loading = false }
        do {
            let current = try await EncryptedMediaWork.run { try MsgDao.shared.getForwardTargets() }
            guard !Task.isCancelled else { return }
            let available = Set(current.map(\.uuid))
            targets = current
            selected.removeAll { !available.contains($0) }
        } catch {
            if !Task.isCancelled { loadFailed = true }
        }
    }

    func toggle(_ id: String) {
        guard !busy else { return }
        if let index = selected.firstIndex(of: id) { selected.remove(at: index) }
        else if selected.count < Self.recipientLimit { selected.append(id) }
        error = nil
    }

    func send(_ request: ForwardMessageRequest, completion: @escaping @MainActor @Sendable () -> Void) {
        guard canSend else { return }
        busy = true
        error = nil
        SecureMessaging.shared.forward(sourcePeer: request.sourcePeer, ids: request.ids, recipients: selected,
                                       hideSender: hideSender, hideCaption: hideCaption, options: options, omitPreview: omitPreview) { [weak self] ok in
            Task { @MainActor in
                guard let self else { return }
                self.busy = false
                if ok { completion() }
                else { self.error = "forward_queue_failed" }
            }
        }
    }
}

@MainActor
struct ForwardMessagesDialog: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var model = ForwardMessagesModel()
    @State private var showOptions = false
    @State private var reload = 0
    @FocusState private var searchFocused: Bool
    let request: ForwardMessageRequest

    var body: some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        VStack(alignment: .leading, spacing: 12) {
            Text(request.ids.count == 1 ? interfaceText("forward_message") : interfaceText.format("forward_messages_count", request.ids.count))
                .font(.title3.weight(.semibold)).accessibilityAddTraits(.isHeader)
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
                TextField(interfaceText("find_recipient"), text: $model.filter)
                    .textFieldStyle(.plain).focused($searchFocused)
                if !model.filter.isEmpty {
                    Button { model.filter = ""; searchFocused = true } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.plain).foregroundStyle(.secondary)
                        .accessibilityLabel(interfaceText("forward_clear_search"))
                }
            }
            .padding(.horizontal, 10).padding(.vertical, 8)
            .background(colors.timeline, in: RoundedRectangle(cornerRadius: 8))
            .disabled(model.busy)
            if !model.selected.isEmpty { selectedRecipients }
            if model.loading || model.busy { ProgressView().controlSize(.small) }
            let visible = model.visible
            if model.loadFailed || visible.isEmpty {
                VStack(spacing: 8) {
                    if !model.loading {
                        Text(interfaceText(model.loadFailed ? "forward_recipients_failed" : model.targets.isEmpty ? "forward_no_recipients" : "forward_no_matches"))
                            .multilineTextAlignment(.center).foregroundStyle(.secondary)
                        if model.loadFailed { Button(interfaceText("retry")) { reload += 1 } }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(visible) { peer in recipientRow(peer) }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .background(colors.timeline, in: RoundedRectangle(cornerRadius: 8))
            }
            if let error = model.error { Text(interfaceText(error)).font(.callout).foregroundStyle(.red) }
            Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
            let summary = sendOptionsLabel(model.options)
            if !summary.isEmpty { Text(summary).font(.caption).foregroundStyle(.secondary) }
            HStack(spacing: 12) {
                Menu {
                    Toggle(interfaceText("forward_hide_sender"), isOn: $model.hideSender)
                    Toggle(interfaceText("forward_hide_caption"), isOn: $model.hideCaption)
                    Toggle(interfaceText("forward_omit_preview"), isOn: $model.omitPreview)
                    Divider()
                    Button(interfaceText("send_options")) { showOptions = true }
                } label: { Image(systemName: "ellipsis.circle") }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                .help(interfaceText("forward_options")).accessibilityLabel(interfaceText("forward_options"))
                .disabled(model.busy)
                Text(interfaceText.format("forward_recipient_count", model.selected.count, ForwardMessagesModel.recipientLimit))
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button(interfaceText("cancel")) { dismiss() }.keyboardShortcut(.cancelAction).disabled(model.busy)
                Button { model.send(request) { dismiss(); request.onCommitted() } } label: {
                    if model.busy { Text(interfaceText("forward_task_queuing")) }
                    else { Label(interfaceText("forward_action"), systemImage: "arrowshape.turn.up.right") }
                }
                .buttonStyle(.borderedProminent).keyboardShortcut(.defaultAction).disabled(!model.canSend)
            }
        }
        .padding(20).frame(minWidth: 420, idealWidth: 500, minHeight: 400, idealHeight: 540)
        .background(colors.chrome)
        .interactiveDismissDisabled(model.busy)
        .task(id: reload) { await model.load() }
        .onAppear { searchFocused = true }
        .sheet(isPresented: $showOptions) { SendOptionsView(initial: model.options) { model.options = $0 } }
    }

    private var selectedRecipients: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(model.chosen) { peer in
                        Button { model.toggle(peer.uuid) } label: {
                            HStack(spacing: 5) {
                                AvatarView(avatarPath: peer.avatarPath, fallbackText: peer.alias, size: 24, cornerRadius: 12)
                                Text(peer.alias).font(.caption).lineLimit(1).frame(maxWidth: 135)
                                Image(systemName: "xmark").font(.system(size: 9, weight: .semibold)).foregroundStyle(.secondary)
                            }
                            .padding(4).padding(.trailing, 4)
                            .background(Color.accentColor.opacity(0.13), in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(interfaceText.format("forward_remove_recipient", peer.alias))
                        .help(interfaceText.format("forward_remove_recipient", peer.alias))
                        .id(peer.uuid)
                    }
                }
            }
            .onChange(of: model.selected) { selection in
                guard selection.count > 0, let last = selection.last else { return }
                withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) { proxy.scrollTo(last, anchor: .trailing) }
            }
        }
        .disabled(model.busy)
    }

    private func recipientRow(_ peer: Conversation) -> some View {
        let chosen = model.selected.contains(peer.uuid)
        return Toggle(isOn: Binding(get: { chosen }, set: { _ in model.toggle(peer.uuid) })) {
            HStack(spacing: 10) {
                AvatarView(avatarPath: peer.avatarPath, fallbackText: peer.alias, size: 36, cornerRadius: 18)
                VStack(alignment: .leading, spacing: 3) {
                    Text(peer.alias).lineLimit(1)
                    Text(String(peer.uuid.prefix(12)) + "…").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
            }
        }
        .toggleStyle(.checkbox).padding(.vertical, 3)
        .disabled(model.busy || (!chosen && model.selected.count >= ForwardMessagesModel.recipientLimit))
    }
}

private struct SavedMessageRow: Identifiable,Sendable {
    let item: SavedMessageItem
    let media: AttachmentMetadata?
    var id: String {item.id}
    var message: ChatMessage {
        ChatMessage(messageId:item.id,conversationUuid:item.sourcePeer,isMine:true,content:item.reference ?? item.text,
                    ext:item.kind,timestamp:item.savedAt,isRead:true,status:3)
    }
}

@MainActor
private final class SavedMessagesModel: ObservableObject {
    @Published var rows: [SavedMessageRow] = []
    @Published var sources: [SavedMessageSource] = []
    @Published var source: SavedMessageSource?
    @Published var byChat = false
    @Published var filter = ""
    @Published var selected = Set<String>()
    @Published var loading = false
    @Published var busy = false
    @Published var error: String?
    @Published var pageFailed = false
    private var failedReset = true
    @Published private(set) var cursor: MessageSearchCursor?
    private var work: Task<Void, Never>?
    private var closed = false
    var selectedRows: [SavedMessageRow] { rows.filter { selected.contains($0.id) } }
    var canAct: Bool { !loading && !busy }
    var search: String { filter.trimmingCharacters(in: .whitespacesAndNewlines) }
    var showingSources: Bool { byChat && source == nil && search.isEmpty }

    func changeScope(_ entry: SavedMessageSource?, grouped: Bool) {
        guard !busy else { return }
        source = entry
        byChat = grouped
        filter = ""
        load(reset: true)
    }

    func load(reset: Bool, typing: Bool = false, keepVisible: Bool = false) {
        guard !closed, reset || (canAct && cursor != nil) else { return }
        work?.cancel()
        loading = true
        pageFailed = false
        error = nil
        if reset && !keepVisible { rows = []; sources = []; cursor = nil; selected = [] }
        let before = reset ? nil : cursor
        let peer = source?.peer
        let query = search
        let showChats = showingSources
        work = Task { [weak self] in
            do {
                if typing && !query.isEmpty { try await Task.sleep(nanoseconds: 180_000_000) }
                if showChats {
                    let entries = try await EncryptedMediaWork.run { try MessageActionsStore.shared.sources() }
                    try Task.checkCancellation()
                    self?.sources = entries
                } else {
                    let worker = Task.detached(priority: .userInitiated) {
                        let page = try MessageActionsStore.shared.list(before: before, sourcePeer: peer, input: query) { try Task.checkCancellation() }
                        let data = try page.prefix(50).map { item in
                            try Task.checkCancellation()
                            let media = item.reference.flatMap { try? EncryptedObjectStore.shared.metadata($0, messageID: item.id) }
                            return SavedMessageRow(item: item, media: media)
                        }
                        let next = page.count > 50 ? data.last.map { MessageSearchCursor(timestamp: $0.item.savedAt, messageId: $0.id) } : nil
                        return (data, next)
                    }
                    let (data, next) = try await withTaskCancellationHandler(operation: { try await worker.value }, onCancel: { worker.cancel() })
                    try Task.checkCancellation()
                    if let self {
                        if reset {
                            self.rows = data
                            self.selected.formIntersection(data.map(\.id))
                        } else {
                            self.rows += data
                        }
                        self.cursor = next
                    }
                }
            } catch {
                if !Task.isCancelled {
                    self?.error = "saved_load_failed"
                    self?.pageFailed = true
                    self?.failedReset = reset
                }
            }
            if !Task.isCancelled { self?.loading = false }
        }
    }

    func retryPage() { load(reset: failedReset, keepVisible: true) }

    func delete(_ ids: [String]) {
        guard canAct, (1...100).contains(ids.count) else { return }
        busy = true
        error = nil
        MessageActionsStore.shared.delete(ids: ids) { [weak self] ok in
            Task { @MainActor in
                guard let self, !self.closed else { return }
                self.busy = false
                if ok {
                    let removed = Set(ids)
                    self.rows.removeAll { removed.contains($0.id) }
                    self.selected.subtract(removed)
                } else { self.error = "saved_delete_failed" }
            }
        }
    }

    func original(_ item: SavedMessageItem, open: @escaping () -> Void) {
        guard canAct else { return }
        busy = true
        error = nil
        Task { [weak self] in
            let present = (try? await EncryptedMediaWork.run { try MessageActionsStore.shared.hasOriginal(item) }) == true
            guard let self, !self.closed else { return }
            self.busy = false
            if present { open() } else { self.error = "saved_original_unavailable" }
        }
    }

    func start() { closed = false; load(reset: true) }
    func stop() { closed = true; work?.cancel() }
}

@MainActor
struct SavedMessagesDialog: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var model=SavedMessagesModel()
    @State private var forwarding: ForwardMessageRequest?
    @State private var media: SavedMessageRow?
    @State private var mediaFocus = MediaPreviewFocus()
    @State private var text: SavedMessageItem?
    @State private var openedCard: SavedMessageItem?
    @State private var openedLocation: SavedMessageItem?
    @State private var confirmDelete=false
    @State private var deleteIDs: [String] = []
    @FocusState private var searchFocused: Bool
    @State private var copyTask: Task<Void, Never>?
    let onOriginal: (SavedMessageItem)->Void
    let onOpenContactChat: (String) -> Void
    private var single: SavedMessageRow? {model.selectedRows.count==1 ? model.selectedRows.first : nil}
    var body: some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        ZStack {
            VStack(alignment:.leading,spacing:12) {
                savedHeader
                if model.loading || model.busy {ProgressView().controlSize(.small)}
                if let error = model.error { Text(interfaceText(error)).foregroundStyle(.red) }
                Group {
                    if model.showingSources {
                        List(model.sources) { entry in
                            Button { model.changeScope(entry, grouped: true) } label: {
                                HStack(spacing: 10) {
                                    AvatarView(avatarPath: entry.avatar, fallbackText: sourceName(entry), size: 38, cornerRadius: 19, fallbackSymbol: "bubble.left")
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(sourceName(entry)).lineLimit(1)
                                        Text(interfaceText.format("saved_source_count", entry.count)).font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right").foregroundStyle(.secondary).accessibilityHidden(true)
                                }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 3).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                        }
                    } else {
                        List(model.rows,selection:$model.selected) {row in
                            VStack(alignment:.leading,spacing:4) {
                                HStack {Text(row.item.author).fontWeight(.semibold);Spacer();Text(Date(timeIntervalSince1970:Double(row.item.savedAt)/1000),style:.date).font(.caption).foregroundStyle(.secondary)}
                                if row.item.reference != nil {Label(row.media?.filename ?? interfaceText("quote_attachment"),systemImage:"doc").lineLimit(2)}
                                if row.item.contactAccount != nil { Label(interfaceText("contact_card"), systemImage: "person.crop.circle").font(.caption) }
                                if row.item.kind == "location", row.item.text != "Location" {
                                    Label(interfaceText("location"), systemImage: "mappin.circle").font(.caption)
                                }
                                if row.item.kind == "txt" {
                                    LinkPreviewMessage(id:row.item.id,text:row.item.text,saved:true,canOpen:!model.busy && model.selected.isEmpty) {
                                        MessageSummaryText(text:row.item.text).lineLimit(4)
                                    }
                                } else if !row.item.text.isEmpty {
                                    MessageSummaryText(text:row.item.text).lineLimit(4)
                                }
                            }.padding(.vertical,4).tag(row.id).contentShape(Rectangle()).onTapGesture(count:2) {open(row)}
                        }
                    }
                }
                .scrollContentBackground(.hidden)
                .background(colors.timeline, in: RoundedRectangle(cornerRadius: 8))
                .disabled(model.loading || model.busy)
                .overlay {
                    if !model.loading && model.error == nil &&
                        (model.showingSources ? model.sources.isEmpty : model.rows.isEmpty && model.cursor == nil) {
                        Text(interfaceText(model.search.isEmpty ? "saved_empty" : "saved_no_matches"))
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(16)
                            .allowsHitTesting(false)
                    }
                }
                if model.selected.count>100 {Text(interfaceText("saved_select_limit")).font(.caption).foregroundStyle(.secondary)}
                Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
                HStack(spacing:16) {
                    if model.pageFailed {
                        Button(interfaceText("retry")) { model.retryPage() }.disabled(!model.canAct)
                    } else {
                        Button { model.load(reset: true, keepVisible: true) } label: {
                            Label(interfaceText("queue_refresh"), systemImage: "arrow.clockwise").labelStyle(.iconOnly)
                        }.help(interfaceText("queue_refresh")).disabled(!model.canAct || !model.selected.isEmpty)
                        if model.cursor != nil {
                            Button { model.load(reset: false) } label: { Image(systemName: "arrow.up") }
                                .help(interfaceText("saved_older")).accessibilityLabel(interfaceText("saved_older")).disabled(!model.canAct)
                        }
                    }
                    Spacer()
                    Button {if let row=single {open(row)}} label:{Image(systemName:"arrow.up.forward.square")}
                        .help(interfaceText("saved_open")).accessibilityLabel(interfaceText("saved_open")).disabled(!model.canAct || single==nil)
                    Button {
                        let ids=Array(model.selected)
                        forwarding = ForwardMessageRequest(sourcePeer: nil, ids: ids, onCommitted: {
                            model.selected = []
                            NoticeCenter.shared.show(interfaceText("forwarding_queued"))
                        })
                    } label:{Image(systemName:"arrowshape.turn.up.right")}.help(interfaceText("forward_action"))
                        .accessibilityLabel(interfaceText("forward_action")).disabled(!model.canAct || !(1...100).contains(model.selected.count))
                    Button {
                        if let row=single {
                            copyTask?.cancel()
                            copyTask=Task { @MainActor in
                                await MessageTextClipboard.copy(row.item.text, strings: interfaceText)
                            }
                        }
                    } label:{Image(systemName:"doc.on.doc")}.help(interfaceText("copy_message_text")).disabled(!model.canAct || single?.item.text.isEmpty != false)
                    Button {if let row=single {saveEncryptedAttachment(row.message, saved: true)}} label:{Image(systemName:"square.and.arrow.down")}
                        .help(interfaceText("save_attachment")).accessibilityLabel(interfaceText("save_attachment")).disabled(!model.canAct || single?.media?.available != true)
                    Button {
                        if let row = single { model.original(row.item) { dismiss(); onOriginal(row.item) } }
                    } label:{Image(systemName:"arrow.uturn.backward")}.help(interfaceText("show_in_chat"))
                        .accessibilityLabel(interfaceText("show_in_chat")).disabled(!model.canAct || single==nil)
                    Button(role:.destructive) { deleteIDs = Array(model.selected); confirmDelete = true } label:{Image(systemName:"trash")}
                        .help(interfaceText("saved_delete")).accessibilityLabel(interfaceText("saved_delete")).disabled(!model.canAct || !(1...100).contains(model.selected.count))
                }.buttonStyle(.borderless)
            }.padding(20)
                .background(colors.chrome)
                .disabled(media != nil).allowsHitTesting(media == nil).accessibilityHidden(media != nil)
            if let media {
                InAppMediaViewer(message: media.message, navigation: nil, onShowMessage: {
                    model.original(media.item) { dismiss(); onOriginal(media.item) }
                }, saved: true) {
                    self.media = nil
                    mediaFocus.restore()
                }
                .overlay(alignment: .topLeading) {
                    if let error = model.error {
                        Text(interfaceText(error)).font(.callout).foregroundStyle(.white).padding(9)
                            .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 8)).padding(10)
                    }
                }
            }
        }.frame(minWidth:560,idealWidth:680,minHeight:440,idealHeight:600)
        .interactiveDismissDisabled(model.busy)
        .onAppear { model.start() }.onDisappear { copyTask?.cancel(); model.stop() }
        .onChange(of: model.filter) { _ in model.load(reset: true, typing: true) }
        .onChange(of: model.selected) { _ in copyTask?.cancel() }
        .sheet(item:$forwarding) {ForwardMessagesDialog(request:$0)}
        .sheet(item: $openedCard) { card in
            ContactCardDetailsView(name: card.text, readAccount: { try MsgDao.shared.getSavedContactCardAccount(card.id) }) { account in
                openedCard = nil
                dismiss()
                onOpenContactChat(account)
            }
        }
        .sheet(item: $openedLocation) { location in
            LocationDetailsView(label: location.text) { try MsgDao.shared.getSavedLocation(location.id) }
        }
        .sheet(item:$text) {item in
            VStack(alignment:.leading,spacing:16) {
                ScrollView {
                    LinkPreviewMessage(id: item.id, text: item.text, saved: true) {
                        RichMessageText(text: item.text).id(item.text).textSelection(.enabled)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
                HStack {Spacer();Button(interfaceText("close")) {text=nil}.keyboardShortcut(.cancelAction)}
            }.padding(20).frame(minWidth:420,idealWidth:560,minHeight:320,idealHeight:440)
        }
        .confirmationDialog(interfaceText("saved_delete_confirm"),isPresented:$confirmDelete,titleVisibility:.visible) {
            Button(interfaceText("saved_delete"),role:.destructive) {model.delete(deleteIDs)}
        }
    }

    private var savedHeader: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                if model.source != nil {
                    Button { model.changeScope(nil, grouped: true) } label: { Image(systemName: "chevron.left") }
                        .buttonStyle(.borderless).help(interfaceText("saved_view_chats")).accessibilityLabel(interfaceText("saved_view_chats"))
                }
                Text(model.source.map(sourceName) ?? interfaceText("saved_messages")).font(.headline).lineLimit(1)
                Spacer()
                Button { dismiss() } label: { Image(systemName: "xmark") }
                    .buttonStyle(.borderless).help(interfaceText("close")).accessibilityLabel(interfaceText("close")).keyboardShortcut(.cancelAction)
            }
            if model.source == nil {
                Picker(interfaceText("saved_view"), selection: Binding(get: { model.byChat }, set: { model.changeScope(nil, grouped: $0) })) {
                    Text(interfaceText("saved_view_messages")).tag(false)
                    Text(interfaceText("saved_view_chats")).tag(true)
                }.pickerStyle(.segmented).labelsHidden()
            }
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
                TextField(interfaceText("saved_search"), text: $model.filter).textFieldStyle(.plain).focused($searchFocused)
                if !model.filter.isEmpty {
                    Button { model.filter = ""; searchFocused = true } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.plain).accessibilityLabel(interfaceText("forward_clear_search"))
                }
            }.padding(8).background(Color(NSColor.controlBackgroundColor), in: RoundedRectangle(cornerRadius: 8))
        }.disabled(model.busy)
    }

    private func sourceName(_ source: SavedMessageSource) -> String {
        source.alias ?? interfaceText.format("saved_removed_chat", String(source.peer.prefix(8)))
    }

    private func open(_ row: SavedMessageRow) {
        guard model.canAct else {return}
        if row.item.contactAccount != nil { openedCard = row.item; return }
        if row.item.kind == "location" { openedLocation = row.item; return }
        guard row.item.reference != nil else {text=row.item;return}
        guard let value=row.media,value.available else { model.error = "saved_attachment_unavailable";return }
        if value.isImage || value.isVideo || value.isAudio {
            mediaFocus.capture(in: NSApp.keyWindow)
            model.error = nil
            media = row
        }
        else { saveEncryptedAttachment(row.message, saved: true) }
    }
}

@MainActor
private final class ForwardTasksModel: ObservableObject {
    @Published var rows: [ForwardTaskInfo]=[]
    @Published var selected: String?
    @Published var loading=false
    @Published var busy=false
    @Published var error: String?
    @Published var loadFailed = false
    @Published var downloadQueued = false
    private var closed=false
    private var reload=false
    var row: ForwardTaskInfo? {rows.first {$0.id==selected}}
    func load() {
        guard !closed else {return}
        if loading || busy {reload=true;return}
        loading=true
        SecureMessaging.shared.forwardingTasks.list { [weak self] result in
            Task { @MainActor in
                guard let self,!self.closed else {return}
                do {
                    self.rows = try result.get()
                    self.loadFailed = false
                    if !self.rows.contains(where: { $0.id == self.selected }) { self.selected = nil }
                } catch { self.loadFailed = true }
                self.loading=false
                if self.reload {self.reload=false;self.load()}
            }
        }
    }
    func change(_ id: String, cancel: Bool) {
        guard !closed,!busy else {return}
        busy=true;error=nil;downloadQueued=false
        let completion: @Sendable (Bool)->Void = { [weak self] ok in
            Task { @MainActor in
                guard let self,!self.closed else {return};self.busy=false
                if !ok {self.error="forward_task_changed"}
                self.load()
            }
        }
        if cancel {SecureMessaging.shared.forwardingTasks.cancel(id,completion:completion)}
        else {SecureMessaging.shared.forwardingTasks.retry(id,completion:completion)}
    }
    func requestDownloads(_ id: String) {
        guard !closed, !busy, !loading else { return }
        busy = true
        error = nil
        downloadQueued = false
        SecureMessaging.shared.forwardingTasks.requestMissingDownloads(id) { [weak self] result in
            Task { @MainActor in
                guard let self, !self.closed else { return }
                self.busy = false
                switch result {
                case .success(true): self.downloadQueued = true
                case .success(false): self.error = "forward_task_download_unavailable"
                case .failure: self.error = "forward_task_change_failed"
                }
                self.load()
            }
        }
    }
    func stop(){closed=true}
}

@MainActor
struct ForwardTasksDialog: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var model=ForwardTasksModel()
    @State private var confirmCancel=false
    @State private var cancelTarget: ForwardTaskInfo?
    let onOpenOriginal: (String, String) throws -> Bool
    var body: some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        VStack(alignment:.leading,spacing:12) {
            Text(interfaceText("forwarding_queue")).font(.title3.weight(.semibold)).accessibilityAddTraits(.isHeader)
            if model.loading || model.busy {ProgressView().controlSize(.small)}
            if let error=model.error {Text(interfaceText(error)).foregroundStyle(.red)}
            if model.downloadQueued { Text(interfaceText("download_queued")).foregroundStyle(.secondary) }
            if model.loadFailed { Text(interfaceText("forward_task_load_failed")).foregroundStyle(.red) }
            List(model.rows,selection:$model.selected) {row in
                VStack(alignment:.leading,spacing:4) {
                    Text(interfaceText.format("forward_task_summary", row.messageCount, row.recipients.count)).fontWeight(.semibold)
                    if !row.recipients.isEmpty {
                        Text(row.recipients.map {$0 ?? interfaceText("forward_task_unknown_recipient")}.joined(separator:" · ")).font(.caption)
                    }
                    if row.state=="preparing" {ForwardTaskProgress(row:row)}
                    else if row.state == "waiting", row.pendingMediaMessages > 0 {
                        Text(interfaceText.format("forward_task_waiting_media", row.pendingMediaMessages))
                            .foregroundStyle(.secondary)
                    }
                    else {Text(interfaceText(status(row))).foregroundStyle(.secondary)}
                    let options = sendOptionsLabel(row.options)
                    if !options.isEmpty { Text(options).font(.caption).foregroundStyle(.secondary) }
                    Text(Date(timeIntervalSince1970:Double(row.createdAt)/1000)
                        .formatted(.dateTime.year().month(.abbreviated).day().hour().minute().locale(interfaceText.locale)))
                        .font(.caption).foregroundStyle(.secondary)
                }.padding(.vertical,6).tag(row.id)
            }
            .scrollContentBackground(.hidden)
            .background(colors.timeline, in: RoundedRectangle(cornerRadius: 8))
            .disabled(model.busy)
            if !model.loading && !model.loadFailed && model.rows.isEmpty {Text(interfaceText("forward_task_empty")).foregroundStyle(.secondary)}
            Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
            HStack(spacing:16) {
                Button(interfaceText(model.loadFailed ? "retry" : "queue_refresh")) {
                    model.error = nil
                    model.downloadQueued = false
                    model.load()
                }.disabled(model.busy || model.loading)
                if let row = model.row, let peer = row.sourcePeer, let id = row.waitingMessageID {
                    Button { model.requestDownloads(row.id) } label: {
                        Label(interfaceText("forward_task_download"), systemImage: "arrow.down.circle").labelStyle(.iconOnly)
                    }
                    .help(interfaceText("forward_task_download"))
                    .disabled(model.busy || model.loading)
                    Button(interfaceText("forward_task_open_source")) {
                        model.error = nil
                        do {
                            if try onOpenOriginal(peer, id) { dismiss() }
                            else { model.error = "search_target_unavailable" }
                        } catch { model.error = "history_load_failed" }
                    }.disabled(model.busy || model.loading)
                }
                Spacer()
                Button {if let id=model.row?.id {model.change(id,cancel:false)}} label:{Image(systemName:"arrow.clockwise")}
                    .help(interfaceText("retry"))
                    .disabled(model.busy || model.loading || model.row?.state != "failed" || model.row?.errorCode == "source_changed")
                Button {cancelTarget=model.row;confirmCancel=cancelTarget != nil} label:{Image(systemName:"trash")}
                    .help(interfaceText(model.row?.state=="failed" ? "forward_task_remove" : "forward_task_cancel"))
                    .disabled(model.busy || model.loading || model.row==nil)
                Button(interfaceText("close")) {dismiss()}.keyboardShortcut(.cancelAction).disabled(model.busy)
            }
        }.padding(20).frame(minWidth:440,idealWidth:600,minHeight:360,idealHeight:500)
        .background(colors.chrome)
        .interactiveDismissDisabled(model.busy)
        .onAppear {model.load()}.onDisappear {model.stop()}
        .onReceive(NotificationCenter.default.publisher(for:ForwardTaskQueue.didChange).receive(on:RunLoop.main)) {_ in model.load()}
        .confirmationDialog(interfaceText(cancelTarget?.state=="failed" ? "forward_task_remove_confirm" : "forward_task_cancel_confirm"),
                            isPresented:$confirmCancel,titleVisibility:.visible,presenting:cancelTarget) { target in
            Button(interfaceText("confirm"),role:.destructive) {model.change(target.id,cancel:true)}
            Button(interfaceText("cancel"),role:.cancel) {}
        }
    }
    private func status(_ row: ForwardTaskInfo)->String {
        guard row.state=="failed" else {return "forward_task_waiting"}
        switch row.errorCode {
        case "source_changed":return "forward_task_source_changed"
        case "recipient_unavailable":return "forward_task_recipient_unavailable"
        case "source_download_unavailable": return "forward_task_source_download_unavailable"
        case "expired":return "forward_task_expired"
        case "insufficient_space":return "forward_task_no_space"
        default:return "forward_task_prepare_failed"
        }
    }
}

struct CallHistoryView: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.dismiss) private var dismiss
    @State private var entries: [CallHistoryEntry] = []
    @State private var missedOnly = false
    @State private var older = false
    @State private var loading = false
    @State private var failed = false
    @State private var refreshPending = false
    @State private var retryOlder = false
    @State private var showingRemoval = false
    @State private var removalEntry: CallHistoryEntry?
    @State private var removing = false
    @State private var removalFailed = false
    @State private var request: Task<Void,Never>?
    let onOpen: (CallHistoryEntry) -> Void
    let onCall: (CallHistoryEntry) -> Void

    var body: some View {
        VStack(alignment:.leading,spacing:10) {
            HStack {
                Text(interfaceText("call_history")).font(.headline)
                Spacer()
                if !missedOnly {
                    let label = interfaceText("call_history_clear_all")
                    Button { confirmRemoval(nil) } label: { Image(systemName: "trash") }
                        .buttonStyle(.borderless)
                        .disabled(loading || removing || entries.isEmpty)
                        .help(label).accessibilityLabel(label)
                }
            }
            Picker(interfaceText("call_history"), selection: $missedOnly) {
                Text(interfaceText("call_history_all")).tag(false)
                Text(interfaceText("call_history_missed")).tag(true)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .disabled(loading || removing)
            if loading && entries.isEmpty { ProgressView().controlSize(.small) }
            if removalFailed {
                Text(interfaceText("call_history_remove_failed")).foregroundStyle(.red)
            }
            if failed {
                Text(interfaceText("call_history_failed")).foregroundStyle(.secondary)
                Button(interfaceText("call_history_retry")) { load(append: retryOlder) }
            } else if !loading && entries.isEmpty {
                Text(interfaceText(missedOnly ? "call_history_missed_empty" : "call_history_empty"))
                    .foregroundStyle(.secondary)
            }
            List(entries) { entry in
                HStack(spacing:12) {
                    Button {onOpen(entry)} label: {
                        HStack(spacing: 10) {
                            AvatarView(avatarPath: entry.avatarPath, fallbackText: entry.alias, size: 40, cornerRadius: 20)
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(entry.alias).fontWeight(.semibold).lineLimit(1)
                                HStack(spacing: 4) {
                                    Image(systemName: entry.outgoing ? "arrow.up.right" : "arrow.down.left")
                                        .accessibilityHidden(true)
                                    Text(summary(entry)).lineLimit(2)
                                }
                                .font(.caption).foregroundStyle(entry.outcome == .missed ? Color.red : Color.secondary)
                            }
                        }.frame(maxWidth:.infinity,alignment:.leading).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                    Text(Date(timeIntervalSince1970:Double(entry.endedAt)/1000)
                        .formatted(.dateTime.locale(interfaceText.locale).month(.abbreviated).day().year().hour().minute()))
                        .font(.caption2).foregroundStyle(.secondary)
                    let label=interfaceText(entry.video ? "video_call" : "voice_call")
                    Button {onCall(entry)} label: {Image(systemName:entry.video ? "video" : "phone")}
                        .buttonStyle(.borderless).help(label).accessibilityLabel(label)
                }
                .padding(.vertical,4)
                .disabled(removing)
                .contextMenu {
                    Button(role: .destructive) { confirmRemoval(entry) } label: {
                        Label(interfaceText("call_history_remove_entry"), systemImage: "trash")
                    }
                    .disabled(removing)
                }
            }.id(missedOnly)
            HStack {
                if older { Button(interfaceText("call_history_older")) {load(append:true)}.disabled(loading || removing) }
                Spacer()
                Button(interfaceText("close")) {dismiss()}.keyboardShortcut(.cancelAction)
            }
        }
        .padding(20).frame(minWidth:440,idealWidth:540,minHeight:350,idealHeight:490)
        .alert(interfaceText(removalEntry == nil ? "call_history_clear_all" : "call_history_remove_entry"),
               isPresented: $showingRemoval) {
            Button(interfaceText("cancel"), role: .cancel) {}
            Button(interfaceText("local_delete_action"), role: .destructive) { removeConfirmed() }
        } message: {
            Text(interfaceText("call_history_remove_local"))
        }
        .onAppear {load()}
        .onChange(of: missedOnly) { _ in
            entries = []
            older = false
            load()
        }
        .onReceive(NotificationCenter.default.publisher(for:CallHistory.didChange).receive(on:RunLoop.main)) {_ in
            if loading {refreshPending=true}
            else {load()}
        }
        .onDisappear {request?.cancel()}
    }

    private func confirmRemoval(_ entry: CallHistoryEntry?) {
        guard !removing else { return }
        removalEntry = entry
        showingRemoval = true
    }

    private func removeConfirmed() {
        let entry = removalEntry
        removing = true
        removalFailed = false
        SecureMessaging.shared.removeCallHistory(entry) { removed in
            Task { @MainActor in
                removing = false
                removalFailed = !removed
            }
        }
    }

    private func summary(_ entry: CallHistoryEntry) -> String {
        let direction = interfaceText(entry.outgoing ? "call_outgoing" : "call_incoming")
        let outcome = interfaceText("call_outcome_" + entry.outcome.rawValue)
        if entry.outcome != .completed {return direction + " · " + outcome}
        let duration=String(format:"%02lld:%02lld",entry.duration/60,entry.duration%60)
        return direction + " · " + outcome + " · " + duration
    }

    private func load(append: Bool = false) {
        guard !loading else {return}
        loading = true; failed = false
        let before = append ? entries.last : nil
        let count = append ? 40 : max(40, entries.count)
        let filter = missedOnly
        request = Task { @MainActor in
            defer {
                loading=false
                if refreshPending && !Task.isCancelled {
                    refreshPending=false
                    load()
                }
            }
            do {
                let page = try await Task.detached(priority:.userInitiated) {
                    try CallHistory.page(before, count: count, missedOnly: filter)
                }.value
                guard !Task.isCancelled else {return}
                entries = (append ? entries : []) + Array(page.prefix(count))
                older = page.count > count
            } catch {
                if !Task.isCancelled { retryOlder = append; failed = true }
            }
        }
    }
}

private struct ForwardTaskProgress: View {
    let row: ForwardTaskInfo
    @Environment(\.interfaceStrings) private var interfaceText
    @State private var progress:PreparationProgress?

    var body: some View {
        Group {
            if progress?.phase=="committing" {
                Text(interfaceText("forward_task_queuing"))
                ProgressView().controlSize(.small)
            } else if let progress,progress.total>0 {
                let fraction=Double(progress.completed)/Double(progress.total)
                let copiesPerRecipient = progress.attachmentCount / max(1, row.recipients.count)
                let recipientIndex = (progress.attachment - 1) / max(1, copiesPerRecipient)
                let recipient = row.recipients.indices.contains(recipientIndex) ? row.recipients[recipientIndex] : nil
                let overall = (Double(progress.attachment - 1) + fraction) / Double(progress.attachmentCount)
                Text(interfaceText.format("forward_task_copy", recipient ?? interfaceText("forward_task_unknown_recipient"),
                                          progress.attachment, progress.attachmentCount, Int(fraction*100)))
                ProgressView(value:min(1,max(0,overall))).controlSize(.small)
            } else {
                Text(interfaceText("forward_task_preparing"))
                ProgressView().controlSize(.small)
            }
        }
        .foregroundStyle(.secondary)
        .task(id:row.id) {
            while !Task.isCancelled {
                progress=await SecureMessaging.shared.forwardingTasks.progress(row.id)
                do {try await Task.sleep(for:.milliseconds(200))}
                catch {break}
            }
        }
    }
}

@MainActor
private final class PinnedMessagesModel: ObservableObject {
    let peer: String
    @Published private(set) var rows: [PinnedMessageItem] = []
    @Published private(set) var offset = 0
    @Published private(set) var hasMore = false
    @Published private(set) var loading = false
    @Published private(set) var busy = false
    @Published private(set) var failedOffset: Int?
    @Published private(set) var changeFailed = false
    private var reload = false
    private var closed = false

    init(peer: String) { self.peer = peer }
    func stop() { closed = true }

    func page(_ destination: Int) {
        guard !loading, !busy, !closed else { return }
        Task { await load(at: destination) }
    }

    func load(at destination: Int? = nil) async {
        guard !closed else { return }
        if loading || busy {
            reload = true
            return
        }
        guard destination != nil || failedOffset == nil else { return }
        loading = true
        var requested = destination ?? offset
        defer { loading = false }
        repeat {
            reload = false
            let page: PinnedMessagePage? = await withCheckedContinuation { continuation in
                SecureMessaging.shared.pinnedMessages(peer: peer, offset: requested) { continuation.resume(returning: $0) }
            }
            guard !closed else { return }
            guard let page else { failedOffset = requested; return }
            if page.items.isEmpty && requested > 0 {
                requested = max(0, requested - 50)
                reload = true
                continue
            }
            if rows != page.items { rows = page.items }
            offset = requested
            hasMore = page.hasMore
            failedOffset = nil
        } while reload && !closed
    }

    func unpin(_ row: PinnedMessageItem) {
        guard !closed, !busy, !loading else { return }
        busy = true
        changeFailed = false
        Task {
            let ok: Bool = await withCheckedContinuation { continuation in
                SecureMessaging.shared.setMessagePinned(peer: peer, messageID: row.id, pinned: false) {
                    continuation.resume(returning: $0)
                }
            }
            guard !closed else { return }
            busy = false
            changeFailed = !ok
            if ok || reload {
                await load(at: offset)
            }
        }
    }
}

@MainActor
struct PinnedMessagesView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var model: PinnedMessagesModel
    @State private var unpinTarget: PinnedMessageItem?
    @State private var openingID: String?
    @State private var openError: String?
    let peer: String
    let onOpen: (String) -> Void

    init(peer: String, onOpen: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: PinnedMessagesModel(peer: peer))
        self.peer = peer
        self.onOpen = onOpen
    }

    var body: some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        VStack(alignment: .leading, spacing: 12) {
            Text(interfaceText("pinned_messages")).font(.title3.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            if model.loading || model.busy || openingID != nil { ProgressView().controlSize(.small) }
            if model.failedOffset != nil { Text(interfaceText("pins_load_failed")).foregroundStyle(.red) }
            if model.changeFailed { Text(interfaceText("pin_change_failed")).foregroundStyle(.red) }
            if let openError { Text(interfaceText(openError)).foregroundStyle(.red) }
            if !model.loading && model.failedOffset == nil && model.rows.isEmpty {
                Text(interfaceText("pins_empty")).foregroundStyle(.secondary)
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    ForEach(model.rows) { row in
                        HStack(alignment: .top) {
                            Button { openError = nil; openingID = row.id } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    MessageSummaryText(text: row.summary).lineLimit(3).frame(maxWidth: .infinity, alignment: .leading)
                                    Text(Date(timeIntervalSince1970: Double(row.timestamp) / 1000)
                                        .formatted(.dateTime.year().month(.abbreviated).day().hour().minute().locale(interfaceText.locale)))
                                        .font(.caption).foregroundStyle(.secondary)
                                }.contentShape(Rectangle())
                            }.buttonStyle(.plain).disabled(model.busy || model.loading || openingID != nil)
                            Button { unpinTarget = row } label: {
                                Label(interfaceText("unpin_message"), systemImage: "pin.slash").labelStyle(.iconOnly)
                            }
                            .help(interfaceText("unpin_message"))
                            .disabled(model.busy || model.loading || openingID != nil)
                        }
                        Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
                    }
                }
            }.id(model.offset).frame(maxHeight: .infinity)
            Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
            HStack {
                if let failed = model.failedOffset {
                    Button(interfaceText("retry")) { model.page(failed) }
                        .disabled(model.loading || model.busy || openingID != nil)
                }
                Button(interfaceText("queue_previous")) { model.page(max(0, model.offset - 50)) }
                    .disabled(model.offset == 0 || model.loading || model.busy || openingID != nil)
                Button(interfaceText("queue_next")) { model.page(model.offset + 50) }
                    .disabled(!model.hasMore || model.loading || model.busy || openingID != nil)
                Spacer()
                Button(interfaceText("close")) { dismiss() }.disabled(model.busy).keyboardShortcut(.cancelAction)
            }
        }
        .padding(20).frame(minWidth: 440, idealWidth: 500, minHeight: 360, idealHeight: 520)
        .background(colors.chrome)
        .task(id: openingID) {
            guard let id = openingID else { return }
            let conversation = peer
            defer { if openingID == id { openingID = nil } }
            do {
                let valid = try await EncryptedMediaWork.run {
                    try MsgDao.shared.hasHistoryAnchor(peer: conversation, id: id, includeRevoked: false)
                }
                guard !Task.isCancelled, openingID == id, AppLockSession.shared.canShowContent else { return }
                if valid { onOpen(id) } else { openError = "search_target_unavailable" }
            } catch {
                if !Task.isCancelled, openingID == id { openError = "history_load_failed" }
            }
        }
        .alert(interfaceText("unpin_message"), isPresented: Binding(
            get: { unpinTarget != nil },
            set: { if !$0 { unpinTarget = nil } }
        ), presenting: unpinTarget) { row in
            Button(interfaceText("unpin_message"), role: .destructive) { model.unpin(row) }
            Button(interfaceText("cancel"), role: .cancel) {}
        } message: { _ in
            Text(interfaceText("unpin_for_both"))
        }
        .task { await model.load() }.onDisappear { model.stop() }.interactiveDismissDisabled(model.busy)
        .onReceive(NotificationCenter.default.publisher(for: MsgDao.didChangeNotification)
            .receive(on: RunLoop.main).throttle(for: .milliseconds(100), scheduler: RunLoop.main, latest: true)) { _ in
            Task { await model.load() }
        }
    }
}
