import SwiftUI
import AppKit
import UniformTypeIdentifiers

public struct ChatView: View {
    @StateObject var viewModel: ChatViewModel

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
                // Header Bar with Avatar
                HStack(spacing: 12) {
                    AvatarView(
                        avatarPath: viewModel.conversation?.avatarPath,
                        fallbackText: viewModel.conversation?.alias ?? "Chat",
                        size: 36,
                        cornerRadius: 10
                    )

                    Text(viewModel.conversation?.alias ?? "Chat")
                        .font(.system(size: 16, weight: .semibold))

                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Color(NSColor.controlBackgroundColor))

                Divider()

                // Messages List
                ZStack(alignment: .bottomTrailing) {
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(spacing: 14) {
                                if viewModel.hasMoreOlderMessages && viewModel.messages.count >= 100 {
                                    Button("Load Earlier Messages") {
                                        viewModel.loadMoreMessages()
                                    }
                                    .buttonStyle(.bordered)
                                    .controlSize(.small)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                    .padding(.top, 8)
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
                                            highlightedMessageId = targetId
                                            withAnimation {
                                                proxy.scrollTo(targetId, anchor: .center)
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
                                }

                                Color.clear
                                    .frame(height: 1)
                                    .id("BOTTOM_ANCHOR")
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                        }
                        .applyDefaultBottomScrollAnchor()
                        .onAppear {
                            proxy.scrollTo("BOTTOM_ANCHOR", anchor: .bottom)
                        }
                        .onChange(of: viewModel.messages.last?.messageId) { _ in
                            withAnimation(.easeOut(duration: 0.2)) {
                                proxy.scrollTo("BOTTOM_ANCHOR", anchor: .bottom)
                            }
                        }
                        .onChange(of: viewModel.targetUuid) { _ in
                            proxy.scrollTo("BOTTOM_ANCHOR", anchor: .bottom)
                        }
                    }
                }

                // Reply-to Bar
                if let reply = replyingTo {
                    HStack {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(Color.accentColor)
                            .frame(width: 3, height: 28)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Replying to \(reply.isMine ? "Me" : viewModel.conversation?.alias ?? "User")")
                                .font(.caption2)
                                .fontWeight(.bold)
                                .foregroundColor(.accentColor)
                            Text(reply.ext == "txt" ? reply.content : "[\(reply.ext.uppercased())]")
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                        Button(action: { replyingTo = nil }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .background(Color(NSColor.controlBackgroundColor))
                }

                Divider()

                // Input Bar
                HStack(alignment: .bottom, spacing: 10) {
                    // Keep media and voice calling in the original attachment menu.
                    Menu {
                        Button(action: openFilePicker) {
                            Label("Send Media File", systemImage: "photo.on.rectangle")
                        }
                        Button {
                            CallManager.shared.startOutgoingCall(peerUuid: viewModel.targetUuid)
                        } label: {
                            Label("Voice Call", systemImage: "phone.fill")
                        }
                    } label: {
                        Image(systemName: "paperclip")
                            .font(.system(size: 16))
                            .foregroundColor(.secondary)
                            .padding(6)
                    }
                    .buttonStyle(.plain)
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                    .help("Attachments and Voice Call")

                    // Input TextField
                    TextField("Message...", text: $inputText, axis: .vertical)
                        .textFieldStyle(.plain)
                        .lineLimit(1...5)
                        .padding(8)
                        .background(Color(NSColor.textBackgroundColor))
                        .cornerRadius(8)
                        .onSubmit {
                            handleSend()
                        }

                    // Send Button
                    Button(action: handleSend) {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 24))
                            .foregroundColor(!inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? .accentColor : .secondary.opacity(0.5))
                    }
                    .buttonStyle(.plain)
                    .disabled(inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color(NSColor.controlBackgroundColor))
            }
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
        .sheet(item: $editingMessage) { msg in
            VStack(alignment: .leading, spacing: 16) {
                Text("Edit Message")
                    .font(.headline)

                TextEditor(text: $editingText)
                    .frame(height: 100)
                    .padding(4)
                    .background(Color(NSColor.textBackgroundColor))
                    .cornerRadius(6)

                HStack {
                    Spacer()
                    Button("Cancel") {
                        editingMessage = nil
                    }
                    Button("Save") {
                        if !editingText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            viewModel.editMessage(messageId: msg.messageId, newContent: editingText)
                        }
                        editingMessage = nil
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding(20)
            .frame(width: 400)
        }
    }

    private func handleSend() {
        let textToSend = inputText
        guard !textToSend.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        inputText = ""
        let reply = replyingTo
        replyingTo = nil
        viewModel.sendMessage(text: textToSend, replyTo: reply)
    }

    private func openFilePicker() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image, .movie, .audio]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.begin { response in
            if response == .OK, let url = panel.url {
                let reply = replyingTo
                replyingTo = nil
                viewModel.sendMedia(sourceUrl: url, replyTo: reply)
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
                viewModel.sendMedia(sourceUrl: url, replyTo: reply)
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
