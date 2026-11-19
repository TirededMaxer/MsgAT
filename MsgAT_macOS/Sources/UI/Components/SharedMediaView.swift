// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import AVFoundation
import Combine
import SwiftUI

@MainActor
struct SharedMediaView: View {
    let peer: String
    let onJump: (String) -> Void
    let onForward: ([String]) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    @State private var category: SharedMediaCategory
    @State private var searchText = ""
    @State private var dateLimit: Date?
    @State private var calendarDate = Date()
    @State private var showCalendar = false
    @State private var categoryPages: [SharedMediaCategory: [SharedMediaCursor?]] = [:]
    @State private var scrollAnchors: [PageKey: String] = [:]
    @State private var anchorOrder: [PageKey] = []
    @State private var displayedPageKey: PageKey?
    @State private var firstVisibleItem: String?
    @State private var page: SharedMediaPage?
    @State private var activeTransfers: [String: AttachmentProgress] = [:]
    @State private var changingDownloads: Set<String> = []
    @State private var revision = 0
    @State private var preview: ChatMessage?
    @State private var previewFocus = MediaPreviewFocus()
    @State private var previewOrigin: MediaPreviewOrigin?
    @State private var thumbnailBounds: [String: CGRect] = [:]
    @State private var browserBounds: CGRect = .zero
    @State private var neighbors: VisualMediaNeighbors?
    @State private var previewGeneration = 0
    @State private var previewRequest: Task<Void, Never>?
    @State private var previewRefreshFailed = false
    @State private var failedPreviewOpen: SharedMediaItem?
    @State private var opening = false
    @State private var loading = true
    @State private var error: String?
    @State private var selecting = false
    @State private var selected: Set<String> = []
    private struct SaveOperation {
        let owner: ObjectPreparation
        var row: PendingPreparation
    }
    @State private var saveOperation: SaveOperation?
    private var saving: Bool { saveOperation != nil }

    init(peer: String, initialCategory: SharedMediaCategory = .media, onJump: @escaping (String) -> Void,
         onForward: @escaping ([String]) -> Void) {
        self.peer = peer
        self.onJump = onJump
        self.onForward = onForward
        _category = State(initialValue: initialCategory)
    }

    private struct PageKey: Hashable {
        let category: SharedMediaCategory
        let cursor: SharedMediaCursor?
        let query: String
    }

    private struct RowBounds: PreferenceKey {
        static let defaultValue: [String: CGRect] = [:]
        static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
            value.merge(nextValue(), uniquingKeysWith: { _, latest in latest })
        }
    }

    private struct PreviewKey: Hashable {
        let id: String?
        let reference: String?
        let revision: Int
    }

    private var pages: [SharedMediaCursor?] {
        categoryPages[category] ?? [firstCursor]
    }
    private var firstCursor: SharedMediaCursor? {
        dateLimit.flatMap { Calendar.current.dateInterval(of: .day, for: $0)?.end }
            .map { SharedMediaCursor(timestamp: Int64($0.timeIntervalSince1970 * 1_000), messageId: "") }
    }
    private var query: String {
        String(searchText.prefix(256)).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    private var pageKey: PageKey { PageKey(category: category, cursor: pages.last ?? nil, query: query) }

    private var columns: [GridItem] {
        category.visual ? [GridItem(.adaptive(minimum: 114), spacing: 4)] : [GridItem(.flexible())]
    }

    private struct MonthSection<Item>: Identifiable {
        let id: Date
        let items: [Item]
    }

    private func monthSections<Item>(_ items: [Item], timestamp: KeyPath<Item, Int64>) -> [MonthSection<Item>] {
        let calendar = Calendar.current
        let groups = Dictionary(grouping: items) { item in
            let date = Date(timeIntervalSince1970: Double(item[keyPath: timestamp]) / 1_000)
            return calendar.dateInterval(of: .month, for: date)?.start ?? date
        }
        return groups.keys.sorted(by: >).map { MonthSection(id: $0, items: groups[$0] ?? []) }
    }

    private func monthHeader(_ date: Date) -> some View {
        Text(date.formatted(.dateTime.month(.wide).year().locale(interfaceText.locale)))
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 7)
            .accessibilityAddTraits(.isHeader)
    }

    var body: some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(interfaceText("shared_media")).font(.title3.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                if opening {
                    ProgressView().controlSize(.small)
                    Text(interfaceText("checking_attachment")).font(.caption)
                    Button(interfaceText("cancel"), action: cancelPreviewRequest)
                }
                if preview == nil {
                    Button {
                        calendarDate = dateLimit ?? Date()
                        showCalendar = true
                    } label: { Image(systemName: "calendar") }
                        .help(interfaceText("jump_to_date"))
                        .accessibilityLabel(interfaceText("jump_to_date"))
                        .disabled(opening || saving)
                    Button(interfaceText(selecting ? "cancel" : "select")) {
                        selecting.toggle()
                        selected.removeAll()
                    }.disabled(loading || saving)
                }
                if preview == nil {
                    Button(interfaceText("close")) { dismiss() }
                        .keyboardShortcut(.cancelAction).disabled(saving)
                }
            }
            Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
            ZStack {
                browser.disabled(preview != nil).allowsHitTesting(preview == nil).accessibilityHidden(preview != nil)
                if let preview {
                    InAppMediaViewer(message: preview, navigation: previewNavigation, origin: previewOrigin,
                                     returnFrame: previewOrigin == nil ? nil : visibleThumbnail(preview.messageId), onShowMessage: {
                        closePreview(restoreFocus: false)
                        onJump(preview.messageId)
                        dismiss()
                    }, onForward: {
                        closePreview(restoreFocus: false)
                        dismiss()
                        onForward([preview.messageId])
                    }, onRetryRefresh: previewRefreshFailed || failedPreviewOpen != nil || error != nil ? {
                        if let target = failedPreviewOpen { open(target, save: false) }
                        else { revision += 1 }
                    } : nil, onCloseStarted: cancelPreviewRequest, onDismiss: { closePreview() })
                    .id(preview.messageId)
                }
            }
            .coordinateSpace(name: MediaPreviewCoordinateSpace.chat)
        }
        .padding(16)
        .frame(minWidth: 510, idealWidth: 650, minHeight: 420, idealHeight: 570)
        .background(colors.chrome)
        .interactiveDismissDisabled(saving)
        .overlay(alignment: .top) { NoticeView() }
        .popover(isPresented: $showCalendar) {
            VStack(alignment: .leading, spacing: 12) {
                DatePicker(interfaceText("jump_to_date"), selection: $calendarDate, displayedComponents: .date)
                    .datePickerStyle(.graphical).labelsHidden()
                HStack {
                    Button(interfaceText("cancel")) { showCalendar = false }
                        .keyboardShortcut(.cancelAction)
                    Spacer()
                    Button(interfaceText("jump")) {
                        showCalendar = false
                        changeDate(Calendar.current.startOfDay(for: calendarDate))
                    }.keyboardShortcut(.defaultAction)
                }
            }.padding(16).frame(width: 300)
        }
        .task(id: revision) { await loadPage() }
        .task(id: PreviewKey(id: preview?.messageId, reference: preview?.content, revision: revision)) {
            await refreshPreview()
        }
        .task(id: saveOperation?.row.id) {
            guard let operation = saveOperation else { return }
            let owner = operation.owner
            let id = operation.row.id
            while !Task.isCancelled {
                let next = await Task.detached(priority: .utility) { owner.snapshot() }.value
                guard !Task.isCancelled, let current = saveOperation?.row, current.id == id else { return }
                if next.version > current.progress.version ||
                    next.version == current.progress.version && next.completed >= current.progress.completed {
                    saveOperation?.row.progress = next
                }
                do { try await Task.sleep(nanoseconds: 200_000_000) }
                catch { return }
            }
        }
        .onDisappear {
            cancelPreviewRequest()
            saveOperation?.owner.cancel()
            saveOperation = nil
            changingDownloads.removeAll()
        }
        .onReceive(NotificationCenter.default.publisher(for: MsgDao.didChangeNotification)
            .throttle(for: .milliseconds(160), scheduler: RunLoop.main, latest: true)) { _ in revision += 1 }
        .onReceive(NotificationCenter.default.publisher(for: AttachmentQueue.progressChanged).receive(on: RunLoop.main)) { note in
            guard let id = note.object as? String,
                  preview?.messageId == id || page?.items.contains(where: { $0.id == id }) == true else { return }
            if let progress = SecureMessaging.shared.attachmentProgress(id) {
                switch progress.phase {
                case .downloading, .uploading, .retry: activeTransfers[id] = progress
                case .complete, .unavailable: activeTransfers.removeValue(forKey: id); revision += 1
                default: activeTransfers.removeValue(forKey: id)
                }
            } else {
                activeTransfers.removeValue(forKey: id)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: SecureMessaging.attachmentMetadataChanged).receive(on: RunLoop.main)) { note in
            guard let id = note.object as? String,
                  preview?.messageId == id || page?.items.contains(where: { $0.id == id }) == true else { return }
            revision += 1
        }
    }

    private var previewNavigation: InAppMediaViewer.Navigation? {
        guard let neighbors, neighbors.newer != nil || neighbors.older != nil else { return nil }
        return InAppMediaViewer.Navigation(
            position: nil,
            previous: opening ? nil : neighbors.newer.map { item in { open(item, save: false) } },
            next: opening ? nil : neighbors.older.map { item in { open(item, save: false) } }
        )
    }

    private func refreshPreview() async {
        guard let shown = preview else { return }
        let peer = peer
        let filter = category
        let search = query
        let before = firstCursor
        func stillShowing() -> Bool {
            !Task.isCancelled && preview?.messageId == shown.messageId && preview?.content == shown.content
        }
        do {
            let result = try await EncryptedMediaWork.run { () throws -> (Bool, VisualMediaNeighbors?) in
                let store = EncryptedObjectStore.shared
                guard let current = try store.sharedMediaItem(peer: peer, messageId: shown.messageId),
                      current.attachment.reference == shown.content else { return (false, nil) }
                guard filter.visual else { return (true, nil) }
                let anchor = SharedMediaCursor(timestamp: current.timestamp, messageId: current.id)
                return (true, try store.visualMediaNeighbors(peer: peer, anchor: anchor, category: filter,
                                                             query: search, before: before))
            }
            guard stillShowing() else { return }
            if result.0 {
                neighbors = result.1
                previewRefreshFailed = false
            } else {
                closePreview()
            }
        } catch {
            if stillShowing() { previewRefreshFailed = true }
        }
    }

    private func cancelPreviewRequest() {
        previewGeneration += 1
        previewRequest?.cancel()
        previewRequest = nil
        opening = false
    }

    private func closePreview(restoreFocus: Bool = true) {
        let wasOpen = preview != nil
        cancelPreviewRequest()
        preview = nil
        previewOrigin = nil
        previewRefreshFailed = false
        failedPreviewOpen = nil
        neighbors = nil
        if wasOpen && restoreFocus { previewFocus.restore() }
        else { previewFocus.clear() }
    }

    private var browser: some View {
        VStack(alignment: .leading, spacing: 10) {
            TextField(interfaceText("search_shared_media"), text: $searchText)
                .disabled(opening || saving)
                .onChange(of: query) { _ in
                    categoryPages.removeAll()
                    changePage(resetScroll: true)
                }
            if let dateLimit {
                HStack {
                    Text(String(format: interfaceText("shared_media_through_date"),
                                dateLimit.formatted(date: .abbreviated, time: .omitted)))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button(interfaceText("shared_media_all_dates")) { changeDate(nil) }.disabled(opening || saving)
                }
            }
            Picker(interfaceText("shared_media"), selection: $category) {
                ForEach(SharedMediaCategory.allCases) { Text(interfaceText("shared_media_" + $0.rawValue)).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .disabled(opening || saving)
            .onChange(of: category) { _ in
                changePage()
            }
            if loading { ProgressView().controlSize(.small) }
            if let error {
                HStack {
                    Text(error).foregroundStyle(.red)
                    Spacer()
                    Button(interfaceText("retry")) { self.error = nil; revision += 1 }
                }
                .padding(8)
                .background(MsgATTheme.messagingColors(for: colorScheme).timeline,
                            in: RoundedRectangle(cornerRadius: 6))
            }
            if let page, page.items.isEmpty && page.links.isEmpty && !loading {
                Text(interfaceText(query.isEmpty ? "shared_media_empty" : "shared_media_no_matches"))
                    .foregroundStyle(.secondary)
            }
            let key = pageKey
            ScrollViewReader { scroll in
            ScrollView {
                LazyVGrid(columns: columns, alignment: .leading, spacing: category.visual ? 4 : 12) {
                    if category == .links {
                        ForEach(monthSections(page?.links ?? [], timestamp: \.timestamp)) { month in
                            Section {
                                ForEach(month.items) { item in
                                    Group {
                                        if selecting { selectionRow(item.urls.joined(separator: "\n"), id: item.id) }
                                        else {
                                            HStack(alignment: .top, spacing: 10) {
                                                RichMessageText(text: item.urls.joined(separator: "\n"))
                                                    .frame(maxWidth: .infinity, alignment: .leading)
                                                jumpButton(item.id)
                                            }
                                        }
                                    }
                                    .background { trackRow(item.id) }
                                    .id(item.id)
                                }
                            } header: { monthHeader(month.id) }
                        }
                    } else {
                        ForEach(monthSections(page?.items ?? [], timestamp: \.timestamp)) { month in
                            Section {
                                ForEach(month.items) { item in
                                    Group {
                                        if selecting && !category.visual { selectionRow(item.attachment.filename, id: item.id) }
                                        else { mediaRow(item) }
                                    }
                                    .background { trackRow(item.id) }
                                    .id(item.id)
                                }
                            } header: { monthHeader(month.id) }
                        }
                    }
                }.padding(3)
            }
            .background {
                GeometryReader { geometry in
                    let bounds = geometry.frame(in: .named(MediaPreviewCoordinateSpace.chat))
                    Color.clear.onAppear { browserBounds = bounds }.onChange(of: bounds) { browserBounds = $0 }
                }
            }
            .onPreferenceChange(SharedMediaThumbnailBounds.self) { thumbnailBounds = $0 }
            .onPreferenceChange(RowBounds.self) { bounds in
                guard displayedPageKey == key, !browserBounds.isEmpty else { return }
                let item = bounds.filter { $0.value.intersects(browserBounds) }.min {
                    if $0.value.minY == $1.value.minY { return $0.value.minX < $1.value.minX }
                    return $0.value.minY < $1.value.minY
                }?.key
                if let item, item != firstVisibleItem { firstVisibleItem = item }
            }
            .id(key)
            .task(id: displayedPageKey) {
                guard displayedPageKey == key, let item = scrollAnchors[key],
                      page?.items.contains(where: { $0.id == item }) == true ||
                      page?.links.contains(where: { $0.id == item }) == true else { return }
                await Task.yield()
                guard !Task.isCancelled, displayedPageKey == key else { return }
                scroll.scrollTo(item, anchor: .top)
            }
            }
            if selecting {
                HStack {
                    Text(interfaceText.format("message_selection_count", selected.count)).monospacedDigit()
                    Spacer()
                    Button(interfaceText("forward")) {
                        let ids = Array(selected)
                        dismiss()
                        onForward(ids)
                    }
                    Button(interfaceText("shared_media_save_selection")) { saveSelection() }
                        .disabled(opening)
                }.disabled(loading || saving || selected.isEmpty)
            }
            if let operation = saveOperation {
                PreparationRows(rows: [operation.row]) { _ in
                    operation.owner.cancel()
                    saveOperation?.row.progress = operation.owner.snapshot()
                }
            }
            HStack {
                Button(interfaceText("media_newer")) {
                    categoryPages[category] = Array(pages.dropLast())
                    changePage(keepSelection: true)
                }.disabled(loading || opening || saving || pages.count <= 1)
                Spacer()
                Button(interfaceText("media_older")) {
                    guard let cursor = page?.older else { return }
                    categoryPages[category] = pages + [cursor]
                    changePage(keepSelection: true)
                }.disabled(loading || opening || saving || page?.older == nil)
            }
        }
    }

    private func trackRow(_ id: String) -> some View {
        GeometryReader { geometry in
            Color.clear.preference(key: RowBounds.self,
                value: [id: geometry.frame(in: .named(MediaPreviewCoordinateSpace.chat))])
        }
    }

    private func changeDate(_ date: Date?) {
        guard dateLimit != date else { return }
        dateLimit = date
        categoryPages.removeAll()
        changePage(resetScroll: true)
    }

    private func changePage(keepSelection: Bool = false, resetScroll: Bool = false) {
        cancelPreviewRequest()
        if resetScroll {
            scrollAnchors.removeAll()
            anchorOrder.removeAll()
        } else if let displayedPageKey, let firstVisibleItem {
            scrollAnchors[displayedPageKey] = firstVisibleItem
            anchorOrder.removeAll { $0 == displayedPageKey }
            anchorOrder.append(displayedPageKey)
            if anchorOrder.count > 24 { scrollAnchors.removeValue(forKey: anchorOrder.removeFirst()) }
        }
        displayedPageKey = nil
        firstVisibleItem = nil
        if !keepSelection {
            selecting = false
            selected.removeAll()
        }
        page = nil
        loading = true
        error = nil
        revision += 1
    }

    private func toggleSelection(_ id: String) {
        guard !saving else { return }
        if selected.remove(id) != nil { return }
        guard selected.count < 100 else {
            NoticeCenter.shared.show(interfaceText("message_selection_limit"))
            return
        }
        selected.insert(id)
    }

    private func selectionRow(_ title: String, id: String) -> some View {
        Toggle(isOn: Binding(get: { selected.contains(id) }, set: { checked in
            if checked != selected.contains(id) { toggleSelection(id) }
        })) { Text(title).lineLimit(2) }
            .toggleStyle(.checkbox).disabled(saving)
    }

    private func saveSelection() {
        guard !saving, !opening, !selected.isEmpty else { return }
        let ids = Array(selected)
        let id = UUID()
        let owner = ObjectPreparation()
        saveOperation = SaveOperation(owner: owner, row: PendingPreparation(
            id: id, filename: nil, fileCount: 1, isSticker: false, progress: owner.snapshot(), savingMessages: true
        ))
        error = nil
        MessageActionsStore.shared.save(peer: peer, ids: ids, preparation: owner) { ok in
            let cancelled = owner.wasCancelled
            owner.finish()
            Task { @MainActor in
                guard saveOperation?.row.id == id else { return }
                saveOperation = nil
                if ok {
                    selecting = false
                    selected.removeAll()
                    NoticeCenter.shared.show(interfaceText("shared_media_saved"))
                } else if !cancelled { error = interfaceText("shared_media_save_failed") }
            }
        }
    }

    @ViewBuilder private func mediaRow(_ item: SharedMediaItem) -> some View {
        let media = item.attachment
        if category.visual {
            let checked = selected.contains(item.id)
            ZStack {
                SharedMediaThumbnail(media: media) { image in
                    if selecting {
                        toggleSelection(item.id)
                    } else if media.available {
                        let origin = image.flatMap { thumbnail in
                            visibleThumbnail(item.id).map { MediaPreviewOrigin(frame: $0, thumbnail: thumbnail) }
                        }
                        open(item, save: false, origin: media.isImage && media.mime != "image/gif" ? origin : nil)
                    } else { saveOrDownload(item) }
                }
                        .aspectRatio(1, contentMode: .fit)
                        .overlay {
                            if selecting && checked { Color.accentColor.opacity(0.20).allowsHitTesting(false) }
                        }
                        .overlay(alignment: .topTrailing) {
                            if selecting {
                                Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                                    .font(.system(size: 20, weight: .semibold))
                                    .foregroundStyle(checked ? Color.accentColor : Color.white)
                                    .padding(2).background(Color.black.opacity(0.72), in: Circle())
                                    .padding(6).accessibilityHidden(true)
                            }
                        }
                        .overlay(alignment: .bottomTrailing) {
                            if !media.available {
                                Image(systemName: item.isMine ? "clock" : downloadIcon(item))
                                    .padding(6).foregroundStyle(.white)
                                    .background(Color.black.opacity(0.65), in: Circle())
                                    .padding(5)
                                    .accessibilityHidden(true)
                            }
                        }
                        .overlay(alignment: .bottom) {
                            if let fraction = transferFraction(item) {
                                ProgressView(value: fraction).controlSize(.small)
                                    .padding(.leading, 6).padding(.trailing, media.available ? 6 : 40)
                                    .padding(.bottom, 6).allowsHitTesting(false).accessibilityHidden(true)
                            }
                        }
                        .background {
                            if media.available, media.isImage {
                                GeometryReader { geometry in
                                    Color.clear.preference(key: SharedMediaThumbnailBounds.self, value: [
                                        item.id: geometry.frame(in: .named(MediaPreviewCoordinateSpace.chat))
                                    ])
                                }
                            }
                        }
                .buttonStyle(.plain)
                .disabled(saving || opening || (!selecting && !media.available &&
                    (item.isMine || changingDownloads.contains(item.id))))
                .help(media.filename).accessibilityLabel(media.filename)
                .accessibilityValue(attachmentStatus(item))
                .accessibilityAddTraits(selecting && checked ? .isSelected : [])
                .accessibilityHint(selecting || media.available ? "" : attachmentActionTitle(item))
            }
            .contextMenu {
                if !selecting {
                    Button(interfaceText("select")) {
                        selected = [item.id]
                        selecting = true
                    }.disabled(loading || opening)
                    Button(interfaceText("show_in_chat")) { onJump(item.id); dismiss() }
                    Button(attachmentActionTitle(item)) { saveOrDownload(item) }
                        .disabled((!media.available && item.isMine) || opening || changingDownloads.contains(item.id))
                }
            }
        } else {
            HStack(spacing: 10) {
                Image(systemName: media.isAudio ? "waveform" : "doc").font(.title3)
                VStack(alignment: .leading) {
                    Text(media.filename).lineLimit(2)
                    Text(attachmentStatus(item))
                        .font(.caption).foregroundStyle(.secondary)
                    if let fraction = transferFraction(item) {
                        ProgressView(value: fraction).controlSize(.small)
                            .accessibilityLabel(attachmentStatus(item))
                    }
                }
                Spacer()
                if media.isAudio {
                    Button { open(item, save: false) } label: { Image(systemName: "play.fill") }
                        .help("Play audio").disabled(!media.available || opening)
                }
                jumpButton(item.id)
                saveButton(item)
            }
        }
    }

    private func jumpButton(_ messageId: String) -> some View {
        Button { onJump(messageId); dismiss() } label: { Image(systemName: "arrow.turn.down.right") }
            .help(interfaceText("show_in_chat")).accessibilityLabel(interfaceText("show_in_chat"))
    }

    private func attachmentActionTitle(_ item: SharedMediaItem) -> String {
        interfaceText(item.attachment.available ? "save_attachment" :
            canPauseDownload(item) ? "pause_download" :
            item.attachment.downloadIntent == .paused ? "resume_download" :
            item.attachment.remoteUnavailable || activeTransfers[item.id]?.phase == .retry ? "retry" : "download_attachment")
    }

    private func canPauseDownload(_ item: SharedMediaItem) -> Bool {
        let media = item.attachment
        return !item.isMine && !media.available && !media.remoteUnavailable && media.downloadIntent != .paused &&
            (media.downloadIntent == .requested || activeTransfers[item.id]?.phase == .downloading)
    }

    private func transferFraction(_ item: SharedMediaItem) -> Double? {
        guard let transfer = activeTransfers[item.id], transfer.total > 0,
              transfer.phase == .uploading || (transfer.phase == .downloading && !item.attachment.available &&
                  item.attachment.downloadIntent != .paused && !item.attachment.remoteUnavailable) else { return nil }
        return min(1, max(0, Double(transfer.bytes) / Double(transfer.total)))
    }

    private func attachmentStatus(_ item: SharedMediaItem) -> String {
        let media = item.attachment
        if let fraction = transferFraction(item) {
            let key = activeTransfers[item.id]?.phase == .uploading ? "attachment_upload_progress" : "album_download_progress"
            return interfaceText.format(key, Int64((fraction * 100).rounded()))
        }
        if media.available { return ByteCountFormatter.string(fromByteCount: media.size, countStyle: .file) }
        if media.downloadIntent == .paused { return interfaceText("download_paused") }
        if media.remoteUnavailable { return interfaceText("download_relay_unavailable") }
        if activeTransfers[item.id]?.phase == .downloading { return interfaceText("downloading_attachment") }
        if activeTransfers[item.id]?.phase == .retry { return interfaceText("attachment_waiting_retry") }
        if media.downloadIntent == .requested { return interfaceText("download_queued") }
        return interfaceText("download_attachment")
    }

    private func downloadIcon(_ item: SharedMediaItem) -> String {
        canPauseDownload(item) ? "pause.circle" : "arrow.down.circle"
    }

    private func saveOrDownload(_ item: SharedMediaItem) {
        if item.attachment.available { open(item, save: true) }
        else if !item.isMine, changingDownloads.insert(item.id).inserted {
            let peer = peer
            let id = item.id
            let reference = item.attachment.reference
            SecureMessaging.shared.requestAttachmentDownload(peer: peer, messageID: item.id,
                requested: !canPauseDownload(item)) { success in
                Task { @MainActor in
                    defer { changingDownloads.remove(id) }
                    guard changingDownloads.contains(id), AppLockSession.shared.canShowContent else { return }
                    let updated = try? await EncryptedMediaWork.run {
                        try EncryptedObjectStore.shared.sharedMediaItem(peer: peer, messageId: id)
                    }
                    guard changingDownloads.contains(id), AppLockSession.shared.canShowContent else { return }
                    if let updated, updated.attachment.reference == reference, let visible = page {
                        page = SharedMediaPage(items: visible.items.map {
                            $0.id == id && $0.attachment.reference == reference ? updated : $0
                        }, older: visible.older, links: visible.links)
                    }
                    if !success { NoticeCenter.shared.show(interfaceText("shared_media_load_failed")) }
                }
            }
        }
    }

    private func saveButton(_ item: SharedMediaItem) -> some View {
        let label = attachmentActionTitle(item)
        return Button {
            saveOrDownload(item)
        } label: {
            Image(systemName: item.attachment.available ? "square.and.arrow.down" : downloadIcon(item))
        }
            .help(label).accessibilityLabel(label)
            .disabled((!item.attachment.available && item.isMine) || opening || changingDownloads.contains(item.id))
    }

    private func loadPage() async {
        loading = true
        let peer = peer
        let filter = category
        let cursor = pages.last ?? nil
        let search = query
        do {
            if !search.isEmpty && page == nil { try await Task.sleep(nanoseconds: 220_000_000) }
            let work = Task.detached(priority: .userInitiated) {
                try EncryptedObjectStore.shared.sharedMedia(peer: peer, category: filter, before: cursor, query: search)
            }
            let result = try await withTaskCancellationHandler {
                try await work.value
            } onCancel: {
                work.cancel()
            }
            guard !Task.isCancelled, filter == category, cursor == (pages.last ?? nil), search == query else { return }
            page = result
            displayedPageKey = PageKey(category: filter, cursor: cursor, query: search)
            activeTransfers = Dictionary(uniqueKeysWithValues: result.items.compactMap { item in
                guard let transfer = SecureMessaging.shared.attachmentProgress(item.id),
                      transfer.phase == .downloading || transfer.phase == .uploading || transfer.phase == .retry else { return nil }
                return (item.id, transfer)
            })
            error = nil
        } catch {
            guard !Task.isCancelled, filter == category, cursor == (pages.last ?? nil), search == query else { return }
            self.error = interfaceText("shared_media_load_failed")
        }
        loading = false
    }

    private func visibleThumbnail(_ id: String) -> CGRect? {
        guard let frame = thumbnailBounds[id], !frame.isEmpty, browserBounds.contains(frame) else { return nil }
        return frame
    }

    private func open(_ item: SharedMediaItem, save: Bool, origin: MediaPreviewOrigin? = nil) {
        guard !opening else { return }
        cancelPreviewRequest()
        failedPreviewOpen = nil
        opening = true
        let version = previewGeneration
        let peer = peer
        previewRequest = Task {
            defer {
                if version == previewGeneration {
                    previewRequest = nil
                    opening = false
                }
            }
            do {
                let result = try await EncryptedMediaWork.run { () throws -> (ChatMessage, Bool)? in
                    let store = EncryptedObjectStore.shared
                    guard let current = try store.sharedMediaItem(peer: peer, messageId: item.id),
                          current.attachment.reference == item.attachment.reference,
                          !save || current.attachment.available,
                          let message = try MsgDao.shared.getMessageById(item.id),
                          message.conversationUuid == peer, message.isRevoked == 0,
                          message.content == current.attachment.reference else { return nil }
                    return (message, current.attachment.available)
                }
                guard !Task.isCancelled, version == previewGeneration, AppLockSession.shared.canShowContent else { return }
                guard let (message, available) = result else {
                    if preview == nil { error = interfaceText("file_details_unavailable") }
                    else { NoticeCenter.shared.show(interfaceText("file_details_unavailable")) }
                    return
                }
                let complete = message.isMine || !available ? true : try await EncryptedObjectStore.shared.checkCiphertext(message.content)
                guard !Task.isCancelled, version == previewGeneration, AppLockSession.shared.canShowContent else { return }
                if !complete {
                    let queued = await SecureMessaging.shared.queueAttachmentCacheReplacement(peer: peer,
                        messageID: message.messageId, reference: message.content)
                    guard queued, !save, !Task.isCancelled, version == previewGeneration,
                          AppLockSession.shared.canShowContent else { return }
                }
                if save { saveEncryptedAttachment(message) }
                else {
                    if preview == nil { previewFocus.capture(in: NSApp.keyWindow) }
                    previewOrigin = available && complete ? origin.flatMap { candidate in
                        visibleThumbnail(item.id) == candidate.frame ? candidate : nil
                    } : nil
                    neighbors = nil
                    previewRefreshFailed = false
                    preview = message
                }
            } catch {
                if !Task.isCancelled && version == previewGeneration {
                    if preview != nil && !save { failedPreviewOpen = item }
                    else { self.error = interfaceText("shared_media_load_failed") }
                }
            }
        }
    }
}

private struct SharedMediaThumbnailBounds: PreferenceKey {
    static var defaultValue: [String: CGRect] { [:] }

    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, latest in latest })
    }
}

struct SharedMediaThumbnail: View {
    let media: AttachmentMetadata
    var activate: ((NSImage?) -> Void)? = nil
    @State private var image: NSImage?

    var body: some View {
        Group {
            if let activate {
                Button { activate(image) } label: { thumbnail }
            } else {
                thumbnail
            }
        }
    }

    private var thumbnail: some View {
        RoundedRectangle(cornerRadius: 4).fill(Color(NSColor.controlBackgroundColor))
            .overlay {
                if let image {
                    GeometryReader { geometry in
                        Image(nsImage: image).resizable().scaledToFill()
                            .frame(width: geometry.size.width, height: geometry.size.height)
                    }
                }
            }
            .overlay {
                if image == nil || media.isVideo {
                    Image(systemName: media.isVideo ? "play.fill" : "photo")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 32, height: 32)
                        .background(Color.black.opacity(0.55), in: Circle())
                }
            }
        .clipShape(RoundedRectangle(cornerRadius: 4))
        .task(id: media) {
            image = nil
            guard media.available else { return }
            let media = media
            let loaded = try? await EncryptedMediaWork.run { try encryptedMediaThumbnail(media, edge: 320) }
            if !Task.isCancelled { image = loaded }
        }
    }
}
