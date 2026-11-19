// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import SwiftUI

struct MessageSearchInput: Equatable {
    var query = ""
    var all: Bool
    var kind: MessageSearchKind = .all
    var selectedMessage: String? = nil
    var loadedCount = 0
    var day: Date? = nil
    var selectedIndex: Int? = nil
}

@MainActor
private final class MessageSearchModel: ObservableObject {
    @Published var query=""
    @Published var all = false
    @Published var kind: MessageSearchKind = .all
    @Published var day: Date?
    @Published var hits: [MessageSearchHit]=[]
    @Published var loading=false
    @Published var failed = false
    private(set) var cursor: MessageSearchCursor?
    private var generation=0
    private var task: Task<Void,Never>?
    private var refreshPending = false
    private var retryOlder = false
    private var advanceAfterRetry: (() -> Void)?
    let peer: String?
    private var loadedInput: MessageSearchInput?
    private var restoreCount: Int
    var input: MessageSearchInput { MessageSearchInput(query: query, all: all, kind: kind, day: day) }
    var hasCurrentResults: Bool { loadedInput == input }

    init(peer: String?, initial: MessageSearchInput?) {
        self.peer = peer
        query = initial?.query ?? ""
        all = peer == nil || initial?.all == true
        kind = initial?.kind ?? .all
        day = initial?.day
        restoreCount = initial?.loadedCount ?? 0
    }
    func requestRefresh() {
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        refreshPending = true
        if !loading && !(failed && retryOlder) { load(reset: true, refresh: true) }
    }

    func retry() {
        guard failed, !loading else { return }
        load(reset: !retryOlder, refresh: !retryOlder && hasCurrentResults, afterPage: advanceAfterRetry)
    }

    func cancelPageAdvance() { advanceAfterRetry = nil }

    func load(reset: Bool, refresh: Bool = false, afterPage: (() -> Void)? = nil) {
        if !reset && (loading || cursor == nil) { return }
        generation+=1;let version=generation;task?.cancel()
        let requested = input
        if reset && !refresh {
            refreshPending = false
            if loadedInput != requested { hits = [] }
            cursor = nil
        }
        let text = requested.query
        let target = requested.all ? nil : peer
        let before = reset ? nil : cursor
        let kind = requested.kind
        let day = requested.day
        let limit = reset ? max(50, max(restoreCount, refresh ? hits.count : 0)) : 50
        failed = false
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {loading=false;return}
        loading=true
        task=Task {
            defer {
                if version == generation {
                    loading = false
                    if refreshPending && !(failed && retryOlder) { load(reset: true, refresh: true) }
                }
            }
            do {
                if reset {try await Task.sleep(nanoseconds:250_000_000)}
                try Task.checkCancellation()
                if reset { refreshPending = false }
                let page = try await Task.detached(priority: .userInitiated) {
                    try MessageSearchStore.shared.search(text, peer: target, before: before, kind: kind, limit: limit, day: day)
                }.value
                guard !Task.isCancelled, version == generation, input == requested else { return }
                if reset {
                    if hits != page.hits { hits = page.hits }
                } else {
                    let existing = Set(hits.map(\.id))
                    hits += page.hits.filter { !existing.contains($0.id) }
                }
                loadedInput = requested
                cursor=page.next
                restoreCount = 0
                advanceAfterRetry = nil
                afterPage?()
            } catch is CancellationError {} catch {
                if version == generation {
                    retryOlder = !reset
                    advanceAfterRetry = reset ? nil : afterPage
                    failed = true
                }
            }
        }
    }
    func changeCriteria() {
        restoreCount = 0
        advanceAfterRetry = nil
        load(reset:true)
    }
    func stop() {generation+=1;refreshPending=false;advanceAfterRetry=nil;task?.cancel()}
}

struct MessageSearchDialog: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var model: MessageSearchModel
    @State private var selectedId: String?
    @State private var restoreAnchor: String?
    @State private var initial: MessageSearchInput?
    @State private var unavailable = false
    @State private var choosingDay = false
    @State private var draftDay = Date()
    let onClose: (MessageSearchInput) -> Void
    let onSelect: (MessageSearchHit, MessageSearchInput) -> Bool

    init(peer: String?, initial: MessageSearchInput? = nil, onClose: @escaping (MessageSearchInput) -> Void = { _ in },
         onSelect: @escaping (MessageSearchHit, MessageSearchInput) -> Bool) {
        _model = StateObject(wrappedValue: MessageSearchModel(peer: peer, initial: initial))
        _restoreAnchor = State(initialValue: initial?.selectedMessage)
        _initial = State(initialValue: initial)
        self.onClose = onClose
        self.onSelect = onSelect
    }
    var body: some View {
        let title = interfaceText("search_messages")
        let needle = model.query.trimmingCharacters(in: .whitespacesAndNewlines)
        let selectedIndex = model.hits.firstIndex { $0.id == selectedId }
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        VStack(alignment:.leading,spacing:12) {
            Text(title).font(.title3.weight(.semibold)).accessibilityAddTraits(.isHeader)
            MessageSearchField(text: $model.query, title: title, move: moveSelection, open: openSelection, close: { dismiss() })
                .frame(height: 25)
            HStack(spacing: 12) {
                if model.peer != nil { Toggle(interfaceText("search_all_conversations"), isOn: $model.all) }
                Spacer(minLength: 4)
                Picker(interfaceText("search_message_type"), selection: $model.kind) {
                    Text(interfaceText("search_all_types")).tag(MessageSearchKind.all)
                    Text(interfaceText("search_text_type")).tag(MessageSearchKind.text)
                    Text(interfaceText("attachments")).tag(MessageSearchKind.attachments)
                }.pickerStyle(.segmented).labelsHidden()
            }
            dateFilter
            if model.loading { ProgressView().controlSize(.small) }
            if model.failed {
                HStack {
                    Text(interfaceText("search_failed")).foregroundStyle(.secondary)
                    Spacer()
                    Button(interfaceText("retry"), action: model.retry)
                }
                .padding(8).background(colors.timeline, in: RoundedRectangle(cornerRadius: 6))
            }
            if unavailable { Text(interfaceText("search_target_unavailable")).foregroundStyle(.red) }
            ScrollViewReader { proxy in
                List(model.hits,selection:$selectedId) {hit in
                    VStack(alignment:.leading,spacing:4) {
                        HStack {
                            Text(hit.alias).fontWeight(.semibold).lineLimit(1)
                            Spacer()
                            Text(Date(timeIntervalSince1970: Double(hit.timestamp) / 1_000)
                                .formatted(.dateTime.year().month(.abbreviated).day().hour().minute().locale(interfaceText.locale)))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        if hit.isAttachment {
                            Label(interfaceText("search_attachment"), systemImage: "paperclip").font(.caption2).foregroundStyle(Color.accentColor)
                        }
                        Text(highlightedPreview(hit.preview, needle: needle)).lineLimit(3)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }.padding(.vertical,4).tag(hit.id)
                    .contentShape(Rectangle()).onTapGesture(count:2) { open(hit) }
                }
                .scrollContentBackground(.hidden)
                .background(colors.timeline, in: RoundedRectangle(cornerRadius: 8))
                .onChange(of: selectedId) { id in
                    if let id { proxy.scrollTo(id) }
                }
            }
            if !model.loading && model.hits.isEmpty && !needle.isEmpty && !model.failed {
                Text(interfaceText("search_no_results")).foregroundStyle(.secondary)
            }
            Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
            HStack {
                if !model.hits.isEmpty {
                    Button { moveSelection(-1) } label: { Image(systemName: "chevron.up") }
                        .help(interfaceText("search_previous_result"))
                        .accessibilityLabel(interfaceText("search_previous_result"))
                        .disabled(!model.hasCurrentResults || selectedIndex == 0)
                    Button { moveSelection(1) } label: { Image(systemName: "chevron.down") }
                        .help(interfaceText("search_next_result"))
                        .accessibilityLabel(interfaceText("search_next_result"))
                        .disabled(!model.hasCurrentResults ||
                            (selectedIndex == model.hits.count - 1 && (model.cursor == nil || model.loading)))
                    Text(String(format: interfaceText("search_result_position"),
                                Int64((selectedIndex ?? -1) + 1), Int64(model.hits.count)))
                        .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
                Spacer()
                Button(interfaceText("close")) {dismiss()}.keyboardShortcut(.cancelAction)
                Button(interfaceText("open"), action: openSelection)
                    .buttonStyle(.borderedProminent)
                    .disabled(selectedId == nil || !model.hasCurrentResults).keyboardShortcut(.defaultAction)
            }
        }.padding(20).frame(minWidth:460,idealWidth:600,minHeight:460,idealHeight:560)
        .background(colors.chrome)
        .onAppear { model.load(reset: true) }
        .onChange(of: model.input) { _ in
            unavailable = false
            initial = nil
            restoreAnchor = nil
            model.changeCriteria()
        }
        .onChange(of: model.hits.map(\.id)) { ids in
            if selectedId.map({ ids.contains($0) }) != true {
                selectedId = restoreAnchor.flatMap { ids.contains($0) ? $0 : nil } ?? ids.first
            }
            if !ids.isEmpty { restoreAnchor = nil }
        }
        .onReceive(NotificationCenter.default.publisher(for:MsgDao.didChangeNotification)) { _ in model.requestRefresh() }
        .onDisappear {
            onClose(searchSnapshot)
            model.stop()
        }
    }

    private var searchSnapshot: MessageSearchInput {
        var input = model.input
        if !model.hasCurrentResults, var initial {
            let original = MessageSearchInput(query: initial.query, all: model.peer == nil || initial.all,
                kind: initial.kind, day: initial.day)
            if input == original {
                initial.all = original.all
                return initial
            }
        }
        if model.hasCurrentResults {
            input.selectedMessage = selectedId
            input.loadedCount = model.hits.count
            input.selectedIndex = model.hits.firstIndex { $0.id == selectedId }
        }
        return input
    }

    private var dateFilter: some View {
        HStack(spacing: 6) {
            Button {
                draftDay = model.day ?? Date()
                choosingDay = true
            } label: {
                let label = model.day?.formatted(.dateTime.year().month(.abbreviated).day().locale(interfaceText.locale))
                Label(label ?? interfaceText("date_label"), systemImage: "calendar")
            }
            .popover(isPresented: $choosingDay) {
                VStack(alignment: .trailing, spacing: 8) {
                    DatePicker(interfaceText("date_label"), selection: $draftDay, displayedComponents: .date)
                        .datePickerStyle(.graphical)
                        .environment(\.locale, interfaceText.locale)
                    HStack {
                        Button(interfaceText("cancel")) { choosingDay = false }
                        Button(interfaceText("apply")) {
                            model.day = Calendar.current.startOfDay(for: draftDay)
                            choosingDay = false
                        }
                    }
                }.padding(12)
            }
            if model.day != nil {
                Button { model.day = nil } label: {
                    Label(interfaceText("search_clear_date"), systemImage: "xmark").labelStyle(.iconOnly)
                }.help(interfaceText("search_clear_date"))
            }
        }
    }

    private func highlightedPreview(_ preview: String, needle: String) -> AttributedString {
        guard !needle.isEmpty else { return AttributedString(preview) }
        var result = AttributedString()
        var start = preview.startIndex
        while let match = preview.range(of: needle, options: .caseInsensitive, range: start..<preview.endIndex),
              !match.isEmpty {
            result += AttributedString(String(preview[start..<match.lowerBound]))
            var matched = AttributedString(String(preview[match]))
            matched.inlinePresentationIntent = .stronglyEmphasized
            result += matched
            start = match.upperBound
        }
        result += AttributedString(String(preview[start...]))
        return result
    }

    private func moveSelection(_ delta: Int) {
        guard model.hasCurrentResults, !model.hits.isEmpty else { return }
        let current = model.hits.firstIndex { $0.id == selectedId }
        let next = (current ?? (delta > 0 ? -1 : model.hits.count)) + delta
        if model.hits.indices.contains(next) {
            model.cancelPageAdvance()
            selectedId = model.hits[next].id
        } else if next == model.hits.count, let anchor = selectedId {
            model.load(reset: false) {
                guard selectedId == anchor, let index = model.hits.firstIndex(where: { $0.id == anchor }),
                      model.hits.indices.contains(index + 1) else { return }
                selectedId = model.hits[index + 1].id
            }
        }
    }

    private func openSelection() {
        if let hit = model.hits.first(where: { $0.id == selectedId }) { open(hit) }
    }

    private func open(_ hit: MessageSearchHit) {
        guard model.hasCurrentResults else { return }
        unavailable = false
        selectedId = hit.id
        if onSelect(hit, searchSnapshot) {dismiss()}
        else {unavailable = true; model.requestRefresh()}
    }
}

private struct MessageSearchField: NSViewRepresentable {
    @Binding var text: String
    let title: String
    let move: (Int) -> Void
    let open: () -> Void
    let close: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> SearchField {
        let field = SearchField()
        field.placeholderString = title
        field.setAccessibilityLabel(title)
        field.delegate = context.coordinator
        field.target = context.coordinator
        field.action = #selector(Coordinator.queryAction(_:))
        field.stringValue = text
        return field
    }

    func updateNSView(_ field: SearchField, context: Context) {
        context.coordinator.parent = self
        if field.placeholderString != title {
            field.placeholderString = title
            field.setAccessibilityLabel(title)
        }
        guard (field.currentEditor() as? NSTextView)?.hasMarkedText() != true else { return }
        if field.stringValue != text { field.stringValue = text }
    }

    final class SearchField: NSSearchField {
        private var focusedInitially = false

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard window != nil, !focusedInitially else { return }
            focusedInitially = true
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.window?.makeFirstResponder(self)
            }
        }
    }

    final class Coordinator: NSObject, NSSearchFieldDelegate {
        var parent: MessageSearchField
        init(_ parent: MessageSearchField) { self.parent = parent }

        @objc func queryAction(_ sender: NSSearchField) {
            guard (sender.currentEditor() as? NSTextView)?.hasMarkedText() != true else { return }
            parent.text = sender.stringValue
        }

        func controlTextDidChange(_ notification: Notification) {
            guard let field = notification.object as? NSSearchField else { return }
            queryAction(field)
        }

        func control(_ control: NSControl, textView: NSTextView, doCommandBy command: Selector) -> Bool {
            guard !textView.hasMarkedText() else { return false }
            switch command {
            case #selector(NSResponder.moveUp(_:)): parent.move(-1)
            case #selector(NSResponder.moveDown(_:)): parent.move(1)
            case #selector(NSResponder.insertNewline(_:)):
                if textView.string == parent.text { parent.open() }
                else { parent.text = textView.string }
            case #selector(NSResponder.cancelOperation(_:)): parent.close()
            default: return false
            }
            return true
        }
    }
}
