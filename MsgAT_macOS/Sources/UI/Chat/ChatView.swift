import SwiftUI
import AppKit
import UniformTypeIdentifiers

public struct ChatView: View {
    @StateObject var viewModel: ChatViewModel

    @State private var showAttachmentMenu = false
    @ObservedObject private var client = NWClient.shared
    @State private var viewportHeight: CGFloat = 0
    @State private var inputText = ""
    @State private var replyingTo: ChatMessage? = nil
    @State private var highlightedMessageId: String? = nil
    @State private var editingMessage: ChatMessage? = nil
    @State private var editingText = ""
    @State private var selectedMedia: ChatMessage? = nil
    @State private var isDragOver = false

    public init(targetUuid: String) {
        _viewModel = StateObject(wrappedValue: ChatViewModel(targetUuid: targetUuid))
    }

    public var body: some View {
        ZStack {
            VStack(spacing: 0) {
                // Messages List
                ZStack(alignment: .bottomTrailing) {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(spacing: 24) {
                                if viewModel.hasMoreOlderMessages {
                                    Color.clear.frame(height: 1).onAppear {
                                        let anchor = viewModel.messages.first?.messageId
                                        viewModel.loadMoreMessages()
                                        if let anchor { DispatchQueue.main.async { proxy.scrollTo(anchor, anchor: .top) } }
                                    }
                                }

                                ForEach(viewModel.messages) { msg in
                                    ChatBubbleView(
                                        message: msg,
                                        peerAlias: viewModel.conversation?.alias ?? "User",
                                        replyAuthor: viewModel.replyAuthors[msg.messageId] ?? "User",
                                        peerAvatarPath: viewModel.conversation?.avatarPath,
                                        isHighlighted: msg.messageId == highlightedMessageId,
                                        onMediaClick: { selectedMedia = $0 },
                                        onReply: { replyingTo = $0 },
                                        onQuotedClick: { targetId in
                                            guard viewModel.revealMessage(targetId) else { NoticeCenter.shared.show("Quoted message not found"); return }
                                            highlightedMessageId = targetId
                                            DispatchQueue.main.async {
                                                withAnimation { proxy.scrollTo(targetId, anchor: .center) }
                                            }
                                            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                                                if highlightedMessageId == targetId {
                                                    highlightedMessageId = nil
                                                }
                                            }
                                        },
                                        onRevoke: { viewModel.revokeMessage(messageId: $0) },
                                        onEdit: {
                                            editingMessage = $0
                                            editingText = $0.content
                                        },
                                        onReEdit: { inputText = $0 }
                                    )
                                    .id(msg.messageId)
                                    .background(GeometryReader { geometry in
                                        Color.clear.preference(key: MessageFramePreference.self,
                                            value: [msg.messageId: geometry.frame(in: .named("messageViewport"))])
                                    })
                                }

                                Color.clear
                                    .frame(height: 1)
                                    .id("BOTTOM_ANCHOR")
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 16)
                        }
                        .coordinateSpace(name: "messageViewport")
                        .background(GeometryReader { geometry in
                            Color.clear.onAppear { viewportHeight = geometry.size.height }
                                .onChange(of: geometry.size.height) { viewportHeight = $0 }
                        })
                        .onPreferenceChange(MessageFramePreference.self) { frames in
                            let visible = viewModel.messages.suffix(3).contains { message in
                                guard let frame = frames[message.messageId] else { return false }
                                return frame.maxY > 0 && frame.minY < viewportHeight
                            }
                            viewModel.setViewingLatest(visible)
                        }
                        .applyDefaultBottomScrollAnchor()
                        .onAppear {
                            proxy.scrollTo("BOTTOM_ANCHOR", anchor: .bottom)
                        }
                        .onChange(of: viewModel.messages.last?.messageId) { _ in
                            if viewModel.isViewingLatest || viewModel.messages.last?.isMine == true {
                                withAnimation(.easeOut(duration: 0.2)) {
                                    proxy.scrollTo("BOTTOM_ANCHOR", anchor: .bottom)
                                }
                            }
                        }
                        .onChange(of: viewModel.targetUuid) { _ in
                            proxy.scrollTo("BOTTOM_ANCHOR", anchor: .bottom)
                        }
                        .overlay(alignment: .bottomTrailing) {
                            if !viewModel.isViewingLatest {
                                let unread = viewModel.messages.last.map { !$0.isMine && !$0.isRead } ?? false
                                Button { withAnimation { proxy.scrollTo("BOTTOM_ANCHOR", anchor: .bottom) } } label: {
                                    Image(systemName: "chevron.down").font(.system(size: 24)).foregroundColor(.primary)
                                        .frame(width: 40, height: 40).background(unread ? MsgATTheme.primaryColor : MsgATTheme.surfaceVariantDark, in: Circle())
                                }.buttonStyle(.plain).help("Scroll to bottom").padding(.trailing, 16).padding(.bottom, 16)
                            }
                        }
                    }
                }

                // Reply-to Bar
                if let reply = replyingTo {
                    HStack {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(Color.accentColor)
                            .frame(width: 4, height: 32)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Replying to \(reply.isMine ? "Me" : viewModel.conversation?.alias ?? "User")")
                                .font(.system(size: 12))
                                .fontWeight(.bold)
                                .foregroundColor(.accentColor)
                            Text(reply.ext == "txt" ? reply.content : "[\(reply.ext.uppercased())]")
                                .font(.system(size: 12))
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                        Button(action: { replyingTo = nil }) {
                            Image(systemName: "xmark").font(.system(size: 24))
                                .foregroundColor(.gray).frame(width: 24, height: 24)
                        }
                        .buttonStyle(.plain).help("Cancel reply").accessibilityLabel("Cancel reply")
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color(NSColor.controlBackgroundColor))
                }

                HStack(alignment: .bottom, spacing: 8) {
                    ImagePasteComposer(text: $inputText, onPasteImage: pasteClipboardImage)
                        .frame(minHeight: 36)
                        .background(Color(NSColor.controlBackgroundColor), in: RoundedRectangle(cornerRadius: 5))
                        .overlay(RoundedRectangle(cornerRadius: 5).stroke(Color.secondary.opacity(0.25)))
                        .overlay(alignment: .topLeading) {
                            if inputText.isEmpty {
                                Text("Message...").foregroundColor(.secondary).padding(8).allowsHitTesting(false)
                            }
                        }
                        .onChange(of: inputText) { value in
                            if !value.isEmpty { showAttachmentMenu = false }
                        }
                    Button {
                        if inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { showAttachmentMenu.toggle() }
                        else { handleSend() }
                    } label: {
                        Image(systemName: inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "plus" : "paperplane.fill")
                            .font(.system(size: 18))
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.bordered).help(inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Add Media" : "Send")
                }
                .padding(8).background(Color(NSColor.windowBackgroundColor))

                if showAttachmentMenu && inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    HStack {
                        Spacer()
                        attachmentAction(title: "Media", icon: "plus") {
                            showAttachmentMenu = false
                            openFilePicker()
                        }
                        Spacer()
                        attachmentAction(title: "Voice Call", icon: "phone.fill") {
                            showAttachmentMenu = false
                            CallManager.shared.startOutgoingCall(peerUuid: viewModel.targetUuid)
                        }
                        Spacer()
                    }
                    .padding(16).background(Color(NSColor.windowBackgroundColor))
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }

            }
            .animation(.easeInOut(duration: 0.18), value: showAttachmentMenu && inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .onDrop(of: [.fileURL], isTargeted: $isDragOver) { providers in
                handleFileDrop(providers: providers)
            }
            .overlay(
                Group {
                    if isDragOver {
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.accentColor, lineWidth: 3)
                            .background(Color.accentColor.opacity(0.1))
                    }
                }
            )

            // In-App Media Viewer
            if let media = selectedMedia {
                InAppMediaViewer(message: media) {
                    selectedMedia = nil
                }
            }
        }
        .navigationTitle("")
        .onAppear { viewModel.isChatMounted = true; viewModel.loadMessages() }
        .onDisappear { viewModel.isChatMounted = false }
        .toolbar {
            if #available(macOS 26.0, *) {
                ToolbarItem(placement: .navigation) { conversationTitle }
                    .sharedBackgroundVisibility(.hidden)
            } else {
                ToolbarItem(placement: .navigation) { conversationTitle }
            }
        }
        .onChange(of: selectedMedia?.messageId) { id in
            viewModel.isMediaPresented = id != nil
            if id == nil { viewModel.loadMessages() }
        }
        .sheet(item: $editingMessage) { msg in
            DialogSurface {
                VStack(alignment: .leading, spacing: 24) {
                    Text("Edit Message").font(.headline)
                    TextEditor(text: $editingText).font(.body)
                        .frame(height: 100).padding(6)
                        .border(Color(NSColor.separatorColor))
                    HStack {
                        Spacer()
                        Button("Cancel") { editingMessage = nil }.buttonStyle(.bordered)
                        Button("Save") {
                            viewModel.editMessage(messageId: msg.messageId, newContent: editingText); editingMessage = nil
                        }.buttonStyle(.borderedProminent)
                    }
                }
            }.frame(width: 400)
        }
    }

    private var conversationTitle: some View {
        HStack(spacing: 6) {
            Text(viewModel.conversation?.alias ?? "MsgAT")
                .font(.headline).lineLimit(1).truncationMode(.tail)
            Group {
                switch client.connectionState {
                case .disconnected:
                    Image(systemName: "exclamationmark.circle").foregroundColor(MsgATTheme.errorColor).help("Disconnected")
                case .syncing:
                    ProgressView().controlSize(.mini).tint(MsgATTheme.connectionColor).help("Syncing...")
                case .synced:
                    Image(systemName: "checkmark").foregroundColor(MsgATTheme.connectionColor).help("Synced")
                }
            }.font(.system(size: 12, weight: .semibold)).frame(width: 14, height: 14)
        }.frame(maxWidth: 320, alignment: .leading).allowsHitTesting(false)
    }

    private func attachmentAction(title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon).font(.system(size: 28))
                    .frame(width: 60, height: 60)
                    .background(Color(NSColor.controlBackgroundColor), in: RoundedRectangle(cornerRadius: 8))
                Text(title).font(.caption).foregroundColor(.secondary)
            }
        }.buttonStyle(.plain).help(title)
    }

    private func handleSend() {
        let textToSend = inputText
        guard !textToSend.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        inputText = ""
        let reply = replyingTo
        replyingTo = nil
        viewModel.sendMessage(text: textToSend, replyTo: reply) { if inputText.isEmpty { inputText = textToSend }; NoticeCenter.shared.show("Send content failed") }
    }

    private func pasteClipboardImage() -> Bool {
        guard let source = PastedImageSource.read(from: .general) else { return false }
        let model = viewModel
        let reply = replyingTo
        replyingTo = nil
        showAttachmentMenu = false
        Task { @MainActor in
            do {
                let temporary = try await Task.detached(priority: .userInitiated) {
                    try source.writeTemporaryImage()
                }.value
                defer { try? FileManager.default.removeItem(at: temporary) }
                model.sendMedia(sourceUrl: temporary, replyTo: reply) { NoticeCenter.shared.show("Send content failed") }
            } catch let error as CocoaError where error.code == .fileReadTooLarge {
                NoticeCenter.shared.show("Files cannot exceed 100 MB.")
            } catch { NoticeCenter.shared.show("Failed to paste image") }
        }
        return true
    }

    private func openFilePicker() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image, .movie]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.begin { response in
            if response == .OK, let url = panel.url {
                let reply = replyingTo
                replyingTo = nil
                viewModel.sendMedia(sourceUrl: url, replyTo: reply) { NoticeCenter.shared.show("Send content failed") }
            }
        }
    }

    private func handleFileDrop(providers: [NSItemProvider]) -> Bool {
        guard let provider = providers.first else { return false }
        provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { (item, error) in
            guard let data = item as? Data, let url = URL(dataRepresentation: data, relativeTo: nil) else { return }
            DispatchQueue.main.async {
                let reply = replyingTo
                replyingTo = nil
                viewModel.sendMedia(sourceUrl: url, replyTo: reply) { NoticeCenter.shared.show("Send content failed") }
            }
        }
        return true
    }
}

fileprivate extension View {
    @ViewBuilder
    func applyDefaultBottomScrollAnchor() -> some View {
        if #available(macOS 14.0, *) {
            self.defaultScrollAnchor(.bottom)
        } else {
            self
        }
    }
}

private struct MessageFramePreference: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, latest in latest })
    }
}


private enum PastedImageSource: Sendable {
    case file(URL)
    case bytes(Data, String)

    private static let pasteboardTypes: [(NSPasteboard.PasteboardType, String)] = [
        (.png, "png"), (.init("public.jpeg"), "jpg"), (.init("com.compuserve.gif"), "gif"), (.tiff, "tiff")
    ]

    static func canRead(from pasteboard: NSPasteboard) -> Bool {
        pasteboard.availableType(from: pasteboardTypes.map { $0.0 }) != nil || imageFiles(from: pasteboard).first != nil
    }

    static func read(from pasteboard: NSPasteboard) -> PastedImageSource? {
        if let file = imageFiles(from: pasteboard).first { return .file(file) }
        for (type, ext) in pasteboardTypes {
            if let data = pasteboard.data(forType: type) { return .bytes(data, ext) }
        }
        return nil
    }

    private static func imageFiles(from pasteboard: NSPasteboard) -> [URL] {
        pasteboard.readObjects(forClasses: [NSURL.self], options: [
            .urlReadingFileURLsOnly: true,
            .urlReadingContentsConformToTypes: [UTType.image.identifier]
        ]) as? [URL] ?? []
    }

    func writeTemporaryImage() throws -> URL {
        let maximumBytes = 100 * 1024 * 1024
        var data: Data
        var ext: String
        switch self {
        case .file(let url):
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size <= maximumBytes else { throw CocoaError(.fileReadTooLarge) }
            data = try Data(contentsOf: url, options: .mappedIfSafe)
            ext = url.pathExtension.lowercased()
        case .bytes(let bytes, let type): data = bytes; ext = type
        }
        guard data.count <= maximumBytes else { throw CocoaError(.fileReadTooLarge) }
        if !["png", "jpg", "jpeg", "gif", "webp"].contains(ext) {
            guard let bitmap = NSBitmapImageRep(data: data),
                  let png = bitmap.representation(using: .png, properties: [:]) else { throw CocoaError(.fileReadCorruptFile) }
            data = png; ext = "png"
        }
        guard data.count <= maximumBytes else { throw CocoaError(.fileReadTooLarge) }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("msgat-paste-\(UUID().uuidString).\(ext)")
        try data.write(to: url, options: .atomic)
        return url
    }
}

private struct ImagePasteComposer: NSViewRepresentable {
    @Binding var text: String
    let onPasteImage: () -> Bool

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeNSView(context: Context) -> NSScrollView {
        let scroll = NSScrollView()
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.drawsBackground = false
        scroll.borderType = .noBorder
        let editor = PasteTextView()
        editor.isRichText = false
        editor.importsGraphics = false
        editor.allowsUndo = true
        editor.isVerticallyResizable = true
        editor.isHorizontallyResizable = false
        editor.autoresizingMask = [.width]
        editor.font = .systemFont(ofSize: NSFont.systemFontSize)
        editor.textColor = .labelColor
        editor.drawsBackground = false
        editor.textContainerInset = NSSize(width: 8, height: 8)
        editor.textContainer?.lineFragmentPadding = 0
        editor.textContainer?.widthTracksTextView = true
        editor.textContainer?.containerSize = NSSize(width: 0, height: CGFloat.greatestFiniteMagnitude)
        editor.delegate = context.coordinator
        editor.onPasteImage = onPasteImage
        editor.setAccessibilityLabel("Message")
        scroll.documentView = editor
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {
        context.coordinator.parent = self
        guard let editor = scroll.documentView as? PasteTextView else { return }
        editor.onPasteImage = onPasteImage
        if editor.string != text {
            editor.string = text
            editor.setSelectedRange(NSRange(location: text.utf16.count, length: 0))
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, nsView: NSScrollView, context: Context) -> CGSize? {
        let width = proposal.width ?? 240
        let font = NSFont.systemFont(ofSize: NSFont.systemFontSize)
        let storage = NSTextStorage(string: text.isEmpty ? " " : text, attributes: [.font: font])
        let layout = NSLayoutManager()
        let container = NSTextContainer(size: NSSize(width: max(20, width - 16), height: CGFloat.greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        layout.addTextContainer(container); storage.addLayoutManager(layout)
        layout.ensureLayout(for: container)
        let lineHeight = layout.defaultLineHeight(for: font)
        let usedHeight = layout.usedRect(for: container).height + (text.hasSuffix("\n") ? lineHeight : 0)
        return CGSize(width: width, height: max(36, min(lineHeight * 5 + 16, usedHeight + 16)))
    }

    final class Coordinator: NSObject, NSTextViewDelegate {
        var parent: ImagePasteComposer
        init(_ parent: ImagePasteComposer) { self.parent = parent }
        func textDidChange(_ notification: Notification) {
            guard let editor = notification.object as? NSTextView else { return }
            parent.text = editor.string
        }
    }

    final class PasteTextView: NSTextView {
        var onPasteImage: (() -> Bool)?
        override func paste(_ sender: Any?) {
            if onPasteImage?() == true { return }
            super.paste(sender)
        }
        override func validateUserInterfaceItem(_ item: NSValidatedUserInterfaceItem) -> Bool {
            if item.action == #selector(paste(_:)), PastedImageSource.canRead(from: .general) { return true }
            return super.validateUserInterfaceItem(item)
        }
    }
}
