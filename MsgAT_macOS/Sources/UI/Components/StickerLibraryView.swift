// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import AppKit

private struct StickerFilter: Hashable {
    var pack: String? = nil
    var favorites = false
    var recent = false
    var animated = false
}

@MainActor
private final class StickerLibraryModel: ObservableObject {
    @Published var packs: [StickerPack] = []
    @Published var items: [StickerItem] = []
    @Published var filter = StickerFilter()
    @Published var search = ""
    @Published var source: StickerSource?
    @Published var selected: [Int: StickerSelection] = [:]
    @Published var candidateCount = 60
    @Published var name = ""
    @Published var importPack: String?
    @Published var status = ""
    @Published var summary = ""
    @Published var error: String?
    @Published private(set) var libraryReadFailed = false
    @Published private(set) var restoration = 0
    @Published private(set) var busy = false
    @Published private(set) var sending = false
    @Published private(set) var loading = false
    @Published private(set) var more = false
    private var offset = 0
    private var offsets: [StickerFilter: Int] = [:]
    private var generation = 0
    private var closing = false
    private var cancellation: StickerImportCancellation?
    private var panel: NSOpenPanel?
    private var libraryTask: Task<Void, Never>?
    private var retryRead: (() -> Void)?

    func load(reset: Bool = true, debounce: Bool = false, preserveScroll: Bool = false, restoreTab: Bool = false) {
        guard source == nil, !closing, reset || (!loading && more) else { return }
        libraryReadFailed = false
        retryRead = nil
        libraryTask?.cancel()
        generation += 1
        let version = generation
        let selection = filter
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        let start = reset ? 0 : offset + 60
        let end: Int
        if !reset { end = start }
        else if restoreTab && query.isEmpty { end = offsets[selection, default: 0] }
        else if preserveScroll { end = offset }
        else { end = start }
        if reset { more = false }
        if reset && !preserveScroll { items = [] }
        loading = true
        libraryTask = Task {
            defer { if version == generation { loading = false } }
            do {
                if debounce { try await Task.sleep(nanoseconds: 180_000_000) }
                let result = try await EncryptedMediaWork.run {
                    let groups = reset ? try StickerLibraryStore.shared.packs() : nil
                    var accumulated: [StickerItem] = []
                    var hasMore = false
                    var last = start
                    for page in stride(from: start, through: end, by: 60) {
                        let rows = try StickerLibraryStore.shared.list(pack: selection.pack, favorites: selection.favorites,
                            recent: selection.recent, search: query, offset: page, animated: selection.animated)
                        accumulated.append(contentsOf: rows)
                        last = page
                        hasMore = rows.count == 60
                        if !hasMore { break }
                    }
                    return (groups, accumulated, last, hasMore)
                }
                guard !Task.isCancelled, version == generation, !closing else { return }
                if let groups = result.0 { packs = groups }
                var existing = Set(reset ? [] : items.map(\.id))
                let unique = result.1.filter { existing.insert($0.id).inserted }
                items = reset ? unique : items + unique
                offset = result.2
                more = result.3
                if query.isEmpty { offsets[selection] = offset }
                if restoreTab { restoration += 1 }
            } catch is CancellationError {
            } catch {
                if version == generation && !closing {
                    libraryReadFailed = true
                    retryRead = { [weak self] in
                        self?.load(reset: reset, preserveScroll: preserveScroll, restoreTab: restoreTab)
                    }
                }
            }
        }
    }

    func retryLibraryRead() { retryRead?() }

    func choose(folder: Bool) {
        guard !busy, !closing, panel == nil else { return }
        let picker = NSOpenPanel()
        picker.canChooseDirectories = folder
        picker.canChooseFiles = !folder
        picker.allowsMultipleSelection = false
        panel = picker
        let finish: (NSApplication.ModalResponse) -> Void = { [weak self, weak picker] result in
            guard let self, let picker, panel === picker else { return }
            panel = nil
            if result == .OK, let url = picker.url { open(url) }
        }
        if let owner = NSApp.keyWindow { picker.beginSheetModal(for: owner, completionHandler: finish) }
        else { picker.begin(completionHandler: finish) }
    }

    private func open(_ url: URL) {
        guard !busy, !closing else { return }
        busy = true
        summary = ""
        error = nil
        generation += 1
        libraryTask?.cancel()
        loading = false
        Task {
            do {
                let opened = try await StickerSource.open(url)
                let destinations = try? await EncryptedMediaWork.run { try StickerLibraryStore.shared.packs() }
                guard !closing else { await opened.close(); busy = false; return }
                if let destinations { packs = destinations }
                source = opened
                selected = [:]
                candidateCount = 60
                name = InterfaceStrings.current()("sticker_imported")
                importPack = nil
            } catch {
                if !closing { self.error = error.localizedDescription }
            }
            busy = false
        }
    }

    func back() {
        guard !busy, let old = source else { return }
        source = nil
        selected = [:]
        error = nil
        Task { await old.close(); load(preserveScroll: true, restoreTab: true) }
    }

    func toggle(_ entry: StickerSourceEntry, sha: String) {
        if selected[entry.id] != nil { selected.removeValue(forKey: entry.id) }
        else if selected.count < 200 { selected[entry.id] = StickerSelection(entry: entry, expectedSha256: sha) }
    }

    var validName: Bool {
        let cleaned = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return !cleaned.isEmpty && cleaned.utf8.count <= 128
    }

    var validDestination: Bool {
        if let importPack { return packs.contains { $0.id == importPack } }
        return validName
    }

    func importSelected() {
        guard !busy, let source, !selected.isEmpty, validDestination else { return }
        busy = true
        error = nil
        summary = ""
        let token = StickerImportCancellation()
        cancellation = token
        let strings = InterfaceStrings.current()
        StickerLibraryStore.shared.importItems(source: source, selection: selected.values.sorted { $0.entry.id < $1.entry.id },
            packName: name, packId: importPack, cancellation: token,
            inspect: { bytes, info in try StickerPixels.inspect(bytes, expected: info) },
            progress: { value in
                Task { @MainActor in
                    guard !self.closing, self.cancellation === token else { return }
                    self.status = strings.format(value.phase == "Scanning" ? "sticker_scan_progress" : "sticker_encrypt_progress",
                        Int64(value.done), Int64(value.total))
                }
            }, completion: { result in
                Task { @MainActor in
                    self.busy = false
                    self.cancellation = nil
                    self.status = ""
                    guard !self.closing else { return }
                    switch result {
                    case .success(let imported):
                        self.source = nil
                        self.selected = [:]
                        self.filter = StickerFilter(pack: imported.packId)
                        self.search = ""
                        self.summary = strings.format("sticker_imported_count", Int64(imported.added), Int64(imported.duplicates))
                        await source.close()
                        self.load()
                    case .failure(let error):
                        if !(error is CancellationError) { self.error = error.localizedDescription }
                    }
                }
            })
    }

    func cancelImport() { cancellation?.cancel() }

    func shutdown() {
        closing = true
        generation += 1
        libraryTask?.cancel()
        let picker = panel
        panel = nil
        picker?.cancel(nil)
        cancellation?.cancel()
        let previous = source
        source = nil
        Task { await previous?.close() }
    }

    func mutate(_ operation: (@escaping @Sendable (Bool) -> Void) -> Void) {
        guard !busy, !closing else { return }
        busy = true
        error = nil
        summary = ""
        operation { success in
            Task { @MainActor in
                self.busy = false
                guard !self.closing else { return }
                if !success { self.error = InterfaceStrings.current()("sticker_change_error") }
                self.load(preserveScroll: true)
            }
        }
    }

    func send(_ item: StickerItem, action: @escaping (StickerItem) async -> PreparationSendResult,
              dismiss: @escaping () -> Void) {
        guard !busy, !closing else { return }
        busy = true
        sending = true
        error = nil
        Task {
            let result = await action(item)
            busy = false
            sending = false
            guard !closing else { return }
            switch result {
            case .queued: dismiss()
            case .cancelled: break
            case .failed: error = InterfaceStrings.current()("sticker_send_error")
            }
        }
    }
}

struct StickerLibraryView: View {
    @StateObject private var model = StickerLibraryModel()
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @FocusState private var searchFocused: Bool
    @FocusState private var closeFocused: Bool
    @State private var searchOpen = false
    @State private var rename = false
    @State private var deletePack = false
    @State private var preview: StickerItem?
    @State private var previewError = false
    @State private var previewAttempt = 0
    @State private var removal: StickerItem?
    @State private var scrollPositions: [StickerFilter: String] = [:]
    @State private var topItem: String?
    @State private var libraryReturnItem: String?
    let compact: Bool
    let preparations: [PendingPreparation]
    let onCancelPreparation: (UUID) -> Void
    let onSend: ((StickerItem) async -> PreparationSendResult)?
    let onClose: (() -> Void)?
    let onBusyChange: (Bool) -> Void

    init(compact: Bool = false, preparations: [PendingPreparation] = [], onCancelPreparation: @escaping (UUID) -> Void = { _ in },
         onSend: ((StickerItem) async -> PreparationSendResult)? = nil, onClose: (() -> Void)? = nil,
         onBusyChange: @escaping (Bool) -> Void = { _ in }) {
        self.compact = compact
        self.preparations = preparations
        self.onCancelPreparation = onCancelPreparation
        self.onSend = onSend
        self.onClose = onClose
        self.onBusyChange = onBusyChange
    }

    var body: some View {
        lifecyclePanel
            .alert(interfaceText("sticker_rename"), isPresented: $rename) { renameActions }
            .alert(interfaceText("sticker_delete_group_confirm"), isPresented: $deletePack) { deletePackActions }
                message: { Text(interfaceText("sticker_other_groups_kept")) }
            .alert(removalTitle, isPresented: removalPresented, presenting: removal) { item in
                removalActions(item)
            } message: { _ in EmptyView() }
    }

    private var panel: some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        return VStack(spacing: 0) {
            header
            Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
            if model.busy && (!model.sending || preparations.isEmpty) {
                ProgressView().controlSize(.small).padding(.vertical, 5)
            }
            if model.sending && !preparations.isEmpty {
                PreparationRows(rows: preparations, onCancel: onCancelPreparation).padding(6)
            }
            if !model.status.isEmpty { notice(model.status, color: .secondary) }
            if !model.summary.isEmpty { notice(model.summary, color: .secondary) }
            if let error = model.error { notice(error, color: .red) }
            if let source = model.source { importPanel(source) }
            else if let preview { previewPanel(preview) }
            else { library }
        }
        .background(colors.chrome)
        .frame(minWidth: compact ? 0 : 520, idealWidth: compact ? 360 : 590,
               maxWidth: compact ? .infinity : 700, minHeight: compact ? 0 : 380,
               idealHeight: compact ? 388 : 490, maxHeight: compact ? .infinity : 660)
    }

    private var lifecyclePanel: some View {
        panel
        .task {
            model.load()
            if compact {
                await Task.yield()
                if !Task.isCancelled { closeFocused = true }
            }
        }
        .onChange(of: model.search) { value in
            if value.count > 256 {
                model.search = String(value.prefix(256))
                return
            }
            model.error = nil
            model.load(debounce: true, restoreTab: value.isEmpty)
        }
        .onChange(of: model.busy, perform: onBusyChange)
        .onChange(of: preview?.id) { _ in previewError = false }
        .onChange(of: model.source != nil) { importing in
            if importing, let topItem { libraryReturnItem = topItem }
        }
        .onDisappear { model.shutdown(); onBusyChange(false) }
        .interactiveDismissDisabled(model.busy)
        .onExitCommand {
            if model.busy { return }
            if preview != nil { preview = nil }
            else if model.source != nil { model.back() }
            else if searchOpen { searchOpen = false; model.search = "" }
            else { close() }
        }
    }

    @ViewBuilder private var renameActions: some View {
        TextField(interfaceText("sticker_group_name"), text: $model.name)
        Button(action: savePackName) { Text(interfaceText("save")) }
        Button(interfaceText("cancel"), role: .cancel) {}
    }

    private func savePackName() {
        guard let id = model.filter.pack, model.validName else {
            model.error = interfaceText("sticker_group_name_limit")
            return
        }
        let name = model.name
        model.mutate { completion in StickerLibraryStore.shared.renamePack(id, name: name, completion: completion) }
    }

    @ViewBuilder private var deletePackActions: some View {
        Button(interfaceText("sticker_delete"), role: .destructive) {
            if let id = model.filter.pack {
                model.mutate { complete in StickerLibraryStore.shared.deletePack(id, completion: complete) }
                model.filter = StickerFilter()
            }
        }
        Button(interfaceText("cancel"), role: .cancel) {}
    }

    private var removalTitle: String {
        interfaceText(model.filter.pack == nil ? "sticker_delete_item_confirm" : "sticker_remove_from_pack_confirm")
    }

    private var removalPresented: Binding<Bool> {
        Binding(get: { removal != nil }, set: { if !$0 { removal = nil } })
    }

    @ViewBuilder private func removalActions(_ item: StickerItem) -> some View {
        Button(interfaceText("sticker_remove"), role: .destructive) {
            model.mutate { complete in
                if let id = model.filter.pack { StickerLibraryStore.shared.removeFromPack(id, id: item.id, completion: complete) }
                else { StickerLibraryStore.shared.delete(item.id, completion: complete) }
            }
            preview = nil
        }
        Button(interfaceText("cancel"), role: .cancel) {}
    }

    private var header: some View {
        HStack(spacing: 8) {
            if model.source != nil || preview != nil {
                toolbarButton("sticker_back", icon: "chevron.backward") {
                    if preview != nil { preview = nil }
                    else { model.back() }
                }
            }
            Text(interfaceText(model.source != nil ? "sticker_import" : preview != nil ? "sticker_preview" :
                    onSend == nil ? "sticker_library" : "stickers"))
                .font(.headline).lineLimit(1)
            Spacer(minLength: 4)
            if model.source == nil && preview == nil {
                toolbarButton("sticker_search", icon: "magnifyingglass") {
                    if !searchOpen && model.search.isEmpty { scrollPositions[model.filter] = topItem }
                    searchOpen.toggle()
                    if !searchOpen { model.search = "" }
                    searchFocused = searchOpen
                }
                Menu {
                    Button(interfaceText("sticker_import_file")) {
                        libraryReturnItem = topItem
                        model.choose(folder: false)
                    }
                    Button(interfaceText("sticker_import_folder")) {
                        libraryReturnItem = topItem
                        model.choose(folder: true)
                    }
                    if let id = model.filter.pack {
                        Divider()
                        Button(interfaceText("sticker_rename")) {
                            model.name = model.packs.first { $0.id == id }?.name ?? ""
                            rename = true
                        }
                        Button(interfaceText("sticker_delete_group"), role: .destructive) { deletePack = true }
                    }
                } label: { Image(systemName: "ellipsis.circle") }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                .accessibilityLabel(interfaceText("sticker_manage")).help(interfaceText("sticker_manage"))
            }
            toolbarButton("sticker_close_panel", icon: "xmark", action: close)
                .focused($closeFocused)
        }
        .padding(.horizontal, 10).frame(minHeight: 41)
        .disabled(model.busy)
    }

    private var library: some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        return VStack(spacing: 0) {
            if searchOpen || !compact {
                TextField(interfaceText("sticker_search"), text: $model.search)
                    .textFieldStyle(.roundedBorder).focused($searchFocused)
                    .padding(.horizontal, 10).padding(.top, 7)
                    .disabled(model.busy)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 5) {
                    tab(interfaceText("sticker_all"), StickerFilter())
                    tab(interfaceText("sticker_favorites"), StickerFilter(favorites: true))
                    tab(interfaceText("sticker_recent"), StickerFilter(recent: true))
                    tab(interfaceText("sticker_animated"), StickerFilter(animated: true))
                    ForEach(model.packs) { group in tab(group.name, StickerFilter(pack: group.id)) }
                }
                .padding(.horizontal, 8).padding(.vertical, 6)
            }
            .disabled(model.busy)
            Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
            if model.libraryReadFailed {
                HStack(spacing: 8) {
                    Text(interfaceText("sticker_load_error"))
                        .font(.caption).foregroundStyle(.red)
                    Spacer(minLength: 5)
                    Button(interfaceText("retry"), action: model.retryLibraryRead).disabled(model.busy)
                }
                .padding(.horizontal, 9).padding(.vertical, 6)
            }
            if model.items.isEmpty {
                VStack(spacing: 8) {
                    if model.loading { ProgressView(interfaceText("sticker_loading")).controlSize(.small) }
                    else if model.error == nil && !model.libraryReadFailed {
                        Text(interfaceText("sticker_empty")).foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: compact ? 61 : 74), spacing: 4)], spacing: 4) {
                            ForEach(model.items) { item in
                                Button {
                                    if let onSend { model.send(item, action: onSend, dismiss: close) }
                                    else { showPreview(item) }
                                } label: {
                                    ZStack(alignment: .bottomTrailing) {
                                        StickerThumbnail(item: item)
                                            .frame(maxWidth: .infinity).frame(height: compact ? 61 : 74)
                                        if item.frames > 1 {
                                            Image(systemName: "play.fill").font(.system(size: 7))
                                                .padding(3).background(.regularMaterial, in: Circle())
                                                .accessibilityHidden(true)
                                        }
                                    }
                                    .padding(3)
                                    .contentShape(RoundedRectangle(cornerRadius: 8))
                                    .background(colors.timeline, in: RoundedRectangle(cornerRadius: 8))
                                }
                                .buttonStyle(.plain).help(item.label)
                                .accessibilityLabel(item.frames > 1 ? item.label + ", " + interfaceText("sticker_animated") : item.label)
                                .contextMenu { actions(item) }
                                .background {
                                    GeometryReader { cell in
                                        Color.clear.preference(key: StickerCellPositions.self,
                                            value: [item.id: cell.frame(in: .named("sticker-scroll")).minY])
                                    }
                                }
                                .id(item.id)
                            }
                        }
                        .padding(8)
                        if model.loading {
                            ProgressView(interfaceText("sticker_loading")).controlSize(.small).padding(5)
                        } else if model.more && !model.libraryReadFailed {
                            Button(interfaceText("sticker_more")) { model.load(reset: false) }
                                .buttonStyle(.borderless).disabled(model.loading).padding(5)
                        }
                    }
                    .coordinateSpace(name: "sticker-scroll")
                    .onPreferenceChange(StickerCellPositions.self) { frames in
                        topItem = frames.filter { $0.value >= -4 }.min { $0.value < $1.value }?.key
                    }
                    .onAppear {
                        if let id = libraryReturnItem {
                            libraryReturnItem = nil
                            proxy.scrollTo(id, anchor: .top)
                        } else if model.search.isEmpty, let id = scrollPositions[model.filter] {
                            proxy.scrollTo(id, anchor: .top)
                        }
                    }
                    .onChange(of: model.restoration) { _ in
                        guard model.search.isEmpty, let id = scrollPositions[model.filter] else { return }
                        proxy.scrollTo(id, anchor: .top)
                    }
                    .disabled(model.busy)
                }
            }
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.14), value: searchOpen)
    }

    private func importPanel(_ source: StickerSource) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(interfaceText.format("sticker_candidates", Int64(source.entries.count), Int64(model.selected.count)))
                .font(.caption).foregroundStyle(.secondary)
            if !model.packs.isEmpty {
                Picker(interfaceText("sticker_import_destination"), selection: $model.importPack) {
                    Text(interfaceText("sticker_create_group")).tag(nil as String?)
                    ForEach(model.packs) { group in Text(group.name).tag(Optional(group.id)) }
                }
                .disabled(model.busy)
            }
            if model.importPack == nil {
                TextField(interfaceText("sticker_new_group"), text: $model.name)
                    .textFieldStyle(.roundedBorder).disabled(model.busy)
            }
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 5)], spacing: 5) {
                    ForEach(Array(source.entries.prefix(model.candidateCount)), id: \.id) { entry in
                        StickerCandidateView(source: source, entry: entry, selected: model.selected[entry.id] != nil,
                            enabled: !model.busy) { model.toggle(entry, sha: $0) }
                    }
                }
                if model.candidateCount < source.entries.count {
                    Button(interfaceText("sticker_more")) { model.candidateCount += 60 }.disabled(model.busy)
                }
            }
            HStack {
                Spacer()
                if model.busy {
                    Button(interfaceText("sticker_cancel_operation"), action: model.cancelImport)
                } else {
                    Button(interfaceText("sticker_import_selected"), action: model.importSelected)
                        .buttonStyle(.borderedProminent).disabled(model.selected.isEmpty || !model.validDestination)
                }
            }
        }
        .padding(10)
    }

    private func previewPanel(_ item: StickerItem) -> some View {
        VStack(spacing: 8) {
            Group {
                if previewError {
                    VStack(spacing: 8) {
                        Text(interfaceText("sticker_invalid_image")).foregroundStyle(.secondary)
                        Button(interfaceText("retry")) { previewAttempt += 1; previewError = false }
                    }
                } else {
                    EncryptedStickerView(reference: item.reference, onFailure: { previewError = true })
                        .id(previewAttempt)
                }
            }
                .frame(maxWidth: 250, maxHeight: compact ? 220 : 320)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            Text(item.label).lineLimit(2).truncationMode(.middle)
            HStack {
                Button(interfaceText(item.favorite ? "sticker_unfavorite" : "sticker_favorite")) { favorite(item) }
                Menu {
                    Button(interfaceText(model.filter.pack == nil ? "sticker_delete_item" : "sticker_remove_from_pack"),
                           role: .destructive) { removal = item }
                } label: { Image(systemName: "ellipsis.circle") }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                .accessibilityLabel(interfaceText("sticker_manage"))
                if let onSend {
                    Button(interfaceText("send")) {
                        preview = nil
                        model.send(item, action: onSend, dismiss: close)
                    }.buttonStyle(.borderedProminent).disabled(previewError)
                }
            }
        }
        .padding(12)
        .disabled(model.busy)
    }

    @ViewBuilder
    private func actions(_ item: StickerItem) -> some View {
        Button(interfaceText("sticker_preview")) { showPreview(item) }
        Button(interfaceText(item.favorite ? "sticker_unfavorite" : "sticker_favorite")) { favorite(item) }
        Button(interfaceText(model.filter.pack == nil ? "sticker_delete_item" : "sticker_remove_from_pack"),
               role: .destructive) { removal = item }
    }

    private func favorite(_ item: StickerItem) {
        preview = nil
        model.mutate { StickerLibraryStore.shared.favorite(item.id, value: !item.favorite, completion: $0) }
    }

    private func showPreview(_ item: StickerItem) {
        libraryReturnItem = topItem ?? item.id
        previewAttempt = 0
        previewError = false
        preview = item
    }

    private func tab(_ title: String, _ filter: StickerFilter) -> some View {
        let selected = model.filter == filter
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        return Button {
            guard !selected else { return }
            if model.search.isEmpty { scrollPositions[model.filter] = topItem }
            model.filter = filter
            model.error = nil
            model.load(restoreTab: true)
        } label: {
            Text(title).lineLimit(1).padding(.horizontal, 9).frame(maxWidth: 140, minHeight: 27)
                .foregroundStyle(selected ? colors.selectionMark : Color.primary)
                .background(selected ? colors.selection : .clear, in: Capsule())
        }
        .buttonStyle(.plain).help(title).accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func toolbarButton(_ key: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).frame(width: 28, height: 28) }
            .buttonStyle(.borderless).help(interfaceText(key)).accessibilityLabel(interfaceText(key))
    }

    private func notice(_ text: String, color: Color) -> some View {
        Text(text).font(.caption).foregroundStyle(color).textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 12).padding(.vertical, 4)
    }

    private func close() {
        onBusyChange(false)
        if let onClose { onClose() }
        else { dismiss() }
    }
}

private struct StickerCellPositions: PreferenceKey {
    static let defaultValue: [String: CGFloat] = [:]
    static func reduce(value: inout [String: CGFloat], nextValue: () -> [String: CGFloat]) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
}

private struct StickerThumbnail: View {
    @Environment(\.interfaceStrings) private var interfaceText
    let item: StickerItem
    @State private var image: NSImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image { Image(nsImage: image).resizable().scaledToFit().accessibilityHidden(true) }
            else if failed { Image(systemName: "exclamationmark.triangle").help(interfaceText("sticker_invalid_image")) }
            else { Color.clear }
        }
        .task(id: item.reference) {
            image = nil
            failed = false
            do {
                let thumbnail = try await EncryptedMediaWork.run {
                    var data = try EncryptedObjectStore.shared.readStickerBytes(item.reference)
                    defer { data.resetBytes(in: 0..<data.count) }
                    return try StickerPixels.thumbnail(data)
                }
                if !Task.isCancelled { image = thumbnail }
            } catch {
                if !Task.isCancelled { failed = true }
            }
        }
        .onDisappear { image = nil }
    }
}

private struct StickerCandidateView:View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    let source:StickerSource;let entry:StickerSourceEntry;let selected:Bool;let enabled:Bool;let onSelect:(String)->Void
    @State private var image:NSImage?;@State private var hash:String?;@State private var failed=false
    var body: some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        Button {
            if let hash { onSelect(hash) }
        } label: {
            VStack(spacing: 3) {
                ZStack(alignment: .topTrailing) {
                    if let image {
                        Image(nsImage: image).resizable().scaledToFit().frame(width: 96, height: 96)
                    } else {
                        Text(interfaceText(failed ? "sticker_invalid_image" : "loading"))
                            .font(.caption).multilineTextAlignment(.center).frame(width: 96, height: 96)
                    }
                    if selected {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(colors.selectionMark)
                            .background(colors.chrome, in: Circle())
                    }
                }
                Text(entry.name).font(.caption).lineLimit(1)
            }
            .frame(width: 104, height: 120)
            .background(selected ? colors.selection : colors.timeline, in: RoundedRectangle(cornerRadius: 8))
            .contentShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .disabled(!enabled || hash == nil)
        .accessibilityLabel(entry.name)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .task {
            do {
                var loaded = try await source.read(entry)
                defer { loaded.clear() }
                let decoded = try await EncryptedMediaWork.run {
                    try StickerPixels.inspect(loaded.bytes, expected: loaded.info)
                    return try StickerPixels.thumbnail(loaded.bytes)
                }
                guard !Task.isCancelled else { return }
                image = decoded
                hash = loaded.sha256
            } catch {
                if !Task.isCancelled { failed = true }
            }
        }
    }
}
