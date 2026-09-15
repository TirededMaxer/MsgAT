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
        return isMe && elapsed <= 120_000 && message.isRevoked == 0
    }

    public var body: some View {
        if message.isRevoked == 1 {
            HStack {
                Spacer()
                HStack(spacing: 6) {
                    Text(isMe ? "You revoked a message" : "\(peerAlias) revoked a message")
                        .font(.caption)
                        .foregroundColor(.secondary)
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
                .padding(.horizontal, 12)
                .padding(.vertical, 5)
                .background(Color.gray.opacity(0.12))
                .cornerRadius(10)
                Spacer()
            }
            .padding(.vertical, 4)
        } else {
            HStack(alignment: .top, spacing: 8) {
                if isMe { Spacer(minLength: 48) }

                // Peer Avatar on Left
                if !isMe {
                    AvatarView(
                        avatarPath: peerAvatarPath,
                        fallbackText: peerAlias,
                        size: 38,
                        cornerRadius: 11
                    )
                }

                // Bubble Column
                VStack(alignment: isMe ? .trailing : .leading, spacing: 4) {
                    // Bubble Content Box
                    VStack(alignment: isMe ? .trailing : .leading, spacing: 3) {
                        // Main Bubble
                        VStack(alignment: .leading, spacing: 0) {
                            if message.ext == "txt" {
                                Text(attributedMessage(message.content))
                                    .font(.system(size: 14))
                                    .foregroundColor(isMe ? .white : Color(red: 0.1, green: 0.1, blue: 0.12))
                                    .textSelection(.enabled)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 10)
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
                                                .frame(maxWidth: 240, maxHeight: 200)
                                                .clipped()
                                                .cornerRadius(12)
                                        } else {
                                            Rectangle()
                                                .fill(Color.gray.opacity(0.2))
                                                .frame(width: 180, height: 160)
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
                                                .frame(maxWidth: 240, maxHeight: 200)
                                                .clipped()
                                                .cornerRadius(12)
                                        } else {
                                            Rectangle()
                                                .fill(Color.black.opacity(0.7))
                                                .frame(width: 200, height: 160)
                                                .cornerRadius(12)
                                        }
                                        Circle()
                                            .fill(Color.black.opacity(0.6))
                                            .frame(width: 44, height: 44)
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

                            HStack(spacing: 5) {
                                RoundedRectangle(cornerRadius: 1)
                                    .fill(Color.accentColor)
                                    .frame(width: 3, height: 18)
                                Text("\(sender): \(displayContent)")
                                    .font(.system(size: 11))
                                    .foregroundColor(.secondary)
                                    .lineLimit(1)
                            }
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(Color(NSColor.controlBackgroundColor).opacity(0.8))
                            .cornerRadius(6)
                            .onTapGesture {
                                onQuotedClick(replyId)
                            }
                        }
                    }
                    .contextMenu {
                        if message.ext == "txt" {
                            Button("Copy") {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(message.content, forType: .string)
                            }
                        }

                        Button("Reply") {
                            onReply(message)
                        }

                        if canRevokeOrEdit {
                            Divider()
                            if message.ext == "txt" {
                                Button("Edit") {
                                    onEdit(message)
                                }
                            }
                            Button("Revoke") {
                                onRevoke(message.messageId)
                            }
                        }

                        if message.ext != "txt" {
                            Divider()
                            Button("Save Media...") {
                                saveMediaToFinder()
                            }
                        }
                    }

                    // Time and Message Status Checkmarks
                    HStack(spacing: 4) {
                        Text(message.isEdited == 1 ? "\(timeString) (edited)" : timeString)
                            .font(.system(size: 10))
                            .foregroundColor(.secondary)

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
                                    .foregroundColor(.secondary)
                            case 2:
                                // Single green check (Delivered)
                                Image(systemName: "checkmark")
                                    .font(.system(size: 9, weight: .bold))
                                    .foregroundColor(.green)
                            case 3:
                                // Double green check (Read)
                                HStack(spacing: -4) {
                                    Image(systemName: "checkmark")
                                    Image(systemName: "checkmark")
                                }
                                .font(.system(size: 9, weight: .bold))
                                .foregroundColor(.green)
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
                        size: 38,
                        cornerRadius: 11
                    )
                } else {
                    Spacer(minLength: 48)
                }
            }
            .onAppear {
                loadThumbnail()
            }
        }
    }

    private func attributedMessage(_ text: String) -> AttributedString {
        var attributed = AttributedString(text)
        let types: NSTextCheckingResult.CheckingType = [.link]
        guard let detector = try? NSDataDetector(types: types.rawValue) else { return attributed }
        let matches = detector.matches(in: text, options: [], range: NSRange(location: 0, length: text.utf16.count))
        for match in matches {
            if let range = Range(match.range, in: text),
               let attributedRange = Range(range, in: attributed),
               let url = match.url {
                attributed[attributedRange].link = url
                attributed[attributedRange].underlineStyle = .single
                attributed[attributedRange].foregroundColor = Color(red: 0.4, green: 0.7, blue: 1.0)
            }
        }
        return attributed
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

    private func saveMediaToFinder() {
        guard let url = MediaStorageManager.shared.getOrExtractMediaFile(
            msgId: message.messageId,
            ext: message.ext,
            content: message.content
        ) else { return }

        let panel = NSSavePanel()
        panel.nameFieldStringValue = "\(message.messageId).\(message.ext)"
        panel.begin { response in
            if response == .OK, let dest = panel.url {
                try? FileManager.default.copyItem(at: url, to: dest)
            }
        }
    }
}
