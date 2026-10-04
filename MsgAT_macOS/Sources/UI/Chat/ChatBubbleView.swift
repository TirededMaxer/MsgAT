import SwiftUI
import AppKit

public struct ChatBubbleView: View {
    let message: ChatMessage
    let replyAuthor: String
    let peerAlias: String
    let peerAvatarPath: String?
    let isHighlighted: Bool

    var onMediaClick: (ChatMessage) -> Void
    var onReply: (ChatMessage) -> Void
    var onQuotedClick: (String) -> Void
    var onRevoke: (String) -> Void
    var onEdit: (ChatMessage) -> Void
    var onReEdit: (String) -> Void

    @State private var thumbnail: NSImage? = nil
    @State private var showSaveConfirmation = false

    public init(
        message: ChatMessage,
        peerAlias: String,
        replyAuthor: String = "User",
        peerAvatarPath: String?,
        isHighlighted: Bool = false,
        onMediaClick: @escaping (ChatMessage) -> Void,
        onReply: @escaping (ChatMessage) -> Void,
        onQuotedClick: @escaping (String) -> Void,
        onRevoke: @escaping (String) -> Void,
        onEdit: @escaping (ChatMessage) -> Void,
        onReEdit: @escaping (String) -> Void
    ) {
        self.message = message
        self.replyAuthor = replyAuthor
        self.peerAlias = peerAlias
        self.peerAvatarPath = peerAvatarPath
        self.isHighlighted = isHighlighted
        self.onMediaClick = onMediaClick
        self.onReply = onReply
        self.onQuotedClick = onQuotedClick
        self.onRevoke = onRevoke
        self.onEdit = onEdit
        self.onReEdit = onReEdit
    }

    private var isMe: Bool { message.isMine }

    private var timeString: String {
        let date = Date(timeIntervalSince1970: Double(message.timestamp) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter.string(from: date)
    }

    private var canRevokeOrEdit: Bool {
        let elapsed = Date().timeIntervalSince1970 * 1000 - Double(message.timestamp)
        return isMe && elapsed >= 0 && elapsed <= 120_000 && message.isRevoked == 0
    }

    public var body: some View {
        if message.isRevoked == 1 {
            HStack {
                Spacer()
                HStack(spacing: 6) {
                    Text(isMe ? "You revoked a message" : "Peer revoked a message")
                        .font(.system(size: 12))
                        .foregroundColor(Color(white: 128/255))
                    if isMe && message.ext == "txt" && !message.content.isEmpty {
                        Button("Re-edit") {
                            onReEdit(message.content)
                        }
                        .buttonStyle(.plain)
                        .font(.caption)
                        .fontWeight(.bold)
                        .foregroundColor(.accentColor)
                    }
                }
                .padding(.vertical, 8)
                Spacer()
            }
            .padding(.vertical, 4)
        } else {
            HStack(alignment: .top, spacing: 8) {
                if isMe { Spacer(minLength: 0) }

                // Peer Avatar on Left
                if !isMe {
                    AvatarView(
                        avatarPath: peerAvatarPath,
                        fallbackText: peerAlias,
                        size: 40,
                        cornerRadius: 12,
                        fallbackSymbol: "plus"
                    )
                }

                // Bubble Column
                VStack(alignment: isMe ? .trailing : .leading, spacing: 4) {
                    // Bubble Content Box
                    VStack(alignment: isMe ? .trailing : .leading, spacing: 3) {
                        // Main Bubble
                        VStack(alignment: .leading, spacing: 0) {
                            if message.ext == "txt" {
                                CappedBubbleLayout {
                                Text(attributedMessage(message.content))
                                    .font(.system(size: 14))
                                    .foregroundColor(isMe ? .white : Color(red: 32/255, green: 32/255, blue: 36/255))
                                    .textSelection(.enabled)
                                    .padding(.horizontal, 14).padding(.vertical, 10)
                                    .onTapGesture { openFirstMessageLink() }
                                }
                                    .background(isMe ? MsgATTheme.myBubbleColor : MsgATTheme.peerBubbleColor)
                                    .clipShape(
                                        UnevenRoundedRectangle(
                                            topLeadingRadius: isMe ? 18 : 4,
                                            bottomLeadingRadius: 18,
                                            bottomTrailingRadius: 18,
                                            topTrailingRadius: isMe ? 4 : 18
                                        )
                                    )
                            } else if ["jpg", "jpeg", "png", "webp"].contains(message.ext.lowercased()) {
                                // Image Thumbnail
                                Button { onMediaClick(message) } label: {
                                    ZStack {
                                        if let img = thumbnail {
                                            Image(nsImage: img)
                                                .resizable()
                                                .aspectRatio(contentMode: .fill)
                                                .frame(width: 250, height: 200)
                                                .clipped()
                                                .cornerRadius(12)
                                        } else {
                                            Rectangle()
                                                .fill(Color.gray.opacity(0.2))
                                                .frame(width: 250, height: 200)
                                                .cornerRadius(12)
                                                .overlay(ProgressView())
                                        }
                                    }
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Open image")
                            } else {
                                // Video Thumbnail
                                Button { onMediaClick(message) } label: {
                                    ZStack {
                                        if let img = thumbnail {
                                            Image(nsImage: img)
                                                .resizable()
                                                .aspectRatio(contentMode: .fill)
                                                .frame(width: 250, height: 200)
                                                .clipped()
                                                .cornerRadius(12)
                                        } else {
                                            Rectangle()
                                                .fill(Color.black.opacity(0.7))
                                                .frame(width: 250, height: 200)
                                                .cornerRadius(12)
                                        }
                                        Circle()
                                            .fill(Color.black.opacity(0.6))
                                            .frame(width: 48, height: 48)
                                            .overlay(
                                                Image(systemName: "play.fill")
                                                    .foregroundColor(.white)
                                            )
                                    }
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Open video")
                            }
                        }
                        .overlay(
                            RoundedRectangle(cornerRadius: 14)
                                .stroke(isHighlighted ? Color.yellow : Color.clear, lineWidth: 2)
                        )

                        // Quoted Message Preview Underneath Bubble (Matching Android layout)
                        if let replyId = message.replyToId, !replyId.isEmpty {
                            let sender = replyAuthor
                            let rawContent = message.replyToContent ?? ""
                            let displayContent = (rawContent.hasPrefix("[") && rawContent.hasSuffix("]")) ? "[Media]" : rawContent

                            HStack(spacing: 0) {
                                Text("\(sender): \(displayContent)")
                                    .font(.system(size: 11))
                                    .foregroundColor(Color(white: 208/255))
                                    .lineLimit(1)
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 2)
                            .background(Color(white: 56/255))
                            .cornerRadius(6)
                            .onTapGesture {
                                onQuotedClick(replyId)
                            }
                        }
                    }
                    .contextMenu {
                        Button("Reply") { onReply(message) }
                        if message.ext == "txt" {
                            Button("Copy Text") { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(message.content, forType: .string); NoticeCenter.shared.show("Copied to clipboard") }
                        }
                        if canRevokeOrEdit {
                            Button("Revoke") { onRevoke(message.messageId) }
                            if message.ext == "txt" { Button("Edit") { onEdit(message) } }
                        }
                        if ["jpg", "jpeg", "png", "webp", "mp4", "m4a"].contains(message.ext) {
                            Button("Save Media") { showSaveConfirmation = true }
                        }
                    }
                    .sheet(isPresented: $showSaveConfirmation) {
                        ConfirmationDialog(message: "Do you want to save this media to your gallery?", title: "Save Media", confirmColor: MsgATTheme.primaryColor,
                            onCancel: { showSaveConfirmation = false }, onConfirm: { showSaveConfirmation = false; saveMediaToGallery() })
                    }

                    // Time and Message Status Checkmarks
                    HStack(spacing: 4) {
                        Text(message.isEdited == 1 ? "\(timeString) (edited)" : timeString)
                            .font(.system(size: 10))
                            .foregroundColor(Color(white: 68/255))

                        if isMe {
                            switch message.status {
                            case 0:
                                ProgressView()
                                    .scaleEffect(0.5)
                                    .frame(width: 12, height: 12)
                            case 1:
                                // Single grey check (Sent)
                                Image(systemName: "checkmark")
                                    .font(.system(size: 9, weight: .bold))
                                    .foregroundColor(.gray).help("Sent").accessibilityLabel("Sent")
                            case 2:
                                // Single green check (Delivered)
                                Image(systemName: "checkmark")
                                    .font(.system(size: 9, weight: .bold))
                                    .foregroundColor(MsgATTheme.primaryColor).help("Delivered").accessibilityLabel("Delivered")
                            case 3:
                                // Double green check (Read)
                                HStack(spacing: -4) {
                                    Image(systemName: "checkmark")
                                    Image(systemName: "checkmark")
                                }
                                .font(.system(size: 9, weight: .bold))
                                .foregroundColor(MsgATTheme.primaryColor).help("Read").accessibilityElement(children: .ignore).accessibilityLabel("Read")
                            default:
                                EmptyView()
                            }
                        }
                    }
                }

                if isMe {
                    AvatarView(
                        avatarPath: KeychainManager.shared.myAvatarPath,
                        fallbackText: KeychainManager.shared.myNickname ?? "Me",
                        size: 40,
                        cornerRadius: 12,
                        fallbackSymbol: "plus"
                    )
                } else {
                    Spacer(minLength: 0)
                }
            }
            .onAppear {
                loadThumbnail()
            }
        }
    }

    private func attributedMessage(_ text: String) -> AttributedString {
        var attributed = AttributedString(text)
        guard let detector = try? NSRegularExpression(pattern: "(?<!\\S)https?://\\S*") else { return attributed }
        let matches = detector.matches(in: text, options: [], range: NSRange(location: 0, length: text.utf16.count))
        for match in matches {
            if let range = Range(match.range, in: text),
               let attributedRange = Range(range, in: attributed) {
                attributed[attributedRange].underlineStyle = .single
                attributed[attributedRange].foregroundColor = isMe ? Color.white : Color.blue
            }
        }
        return attributed
    }

    private func openFirstMessageLink() {
        guard let detector = try? NSRegularExpression(pattern: "(?<!\\S)https?://\\S*"),
              let match = detector.firstMatch(in: message.content, range: NSRange(location: 0, length: message.content.utf16.count)),
              let range = Range(match.range, in: message.content) else { return }
        guard let url = URL(string: String(message.content[range])), let host = url.host, !host.isEmpty,
              NSWorkspace.shared.open(url) else { NoticeCenter.shared.show("Invalid URL"); return }
    }

    private func loadThumbnail() {
        guard message.ext != "txt" else { return }
        DispatchQueue.global(qos: .userInitiated).async {
            let fileUrl = MediaStorageManager.shared.getOrExtractMediaFile(
                msgId: self.message.messageId,
                ext: self.message.ext,
                content: self.message.content
            )
            guard let url = fileUrl else { return }

            if ["jpg", "jpeg", "png", "webp"].contains(self.message.ext.lowercased()) {
                let img = MediaStorageManager.shared.generateImageThumbnail(fileUrl: url, maxDimension: 400)
                DispatchQueue.main.async { self.thumbnail = img }
            } else {
                let img = MediaStorageManager.shared.generateVideoThumbnail(fileUrl: url)
                DispatchQueue.main.async { self.thumbnail = img }
            }
        }
    }

    private func saveMediaToGallery() {
        guard let url = MediaStorageManager.shared.getOrExtractMediaFile(msgId: message.messageId, ext: message.ext, content: message.content) else { NoticeCenter.shared.show("Failed to save"); return }
        do {
            let folder: FileManager.SearchPathDirectory = message.ext == "mp4" ? .moviesDirectory : message.ext == "m4a" ? .musicDirectory : .picturesDirectory
            let directory = try FileManager.default.url(for: folder, in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("MsgAT", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var destination = directory.appendingPathComponent("\(message.messageId).\(message.ext)")
            if FileManager.default.fileExists(atPath: destination.path) { destination = directory.appendingPathComponent("\(message.messageId)_\(UUID().uuidString).\(message.ext)") }
            try FileManager.default.copyItem(at: url, to: destination); NoticeCenter.shared.show("Saved to Gallery")
        } catch { NoticeCenter.shared.show("Failed to save") }
    }
}

private struct CappedBubbleLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let content = subviews.first else { return .zero }
        let width = min(250, min(proposal.width ?? 250, content.sizeThatFits(.unspecified).width))
        return content.sizeThatFits(ProposedViewSize(width: width, height: nil))
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
    }
}
