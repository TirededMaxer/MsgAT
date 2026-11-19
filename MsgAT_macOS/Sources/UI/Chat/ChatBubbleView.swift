import SwiftUI
import AppKit

private struct ReplyGutterBoundary: PreferenceKey {
    static let defaultValue: Anchor<CGRect>? = nil

    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        if let next = nextValue() { value = next }
    }
}

public struct ChatBubbleView: View {
    @State private var linkTargets: [URL] = []
    @State private var revealedForCopy = Set<Int>()
    @State private var copyTask: Task<Void, Never>?
    @State private var attachmentMetadata: AttachmentMetadata?
    @State private var confirmPin = false
    @State private var unpinRequested = false
    @State private var confirmDownloadAgain = false
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.layoutDirection) private var layoutDirection
    let message: ChatMessage
    let replyAuthor: String
    let peerAlias: String
    let peerAvatarPath: String?
    let forwardLabel: String?
    let albumTile: Bool
    let maximumMessageBodyWidth: CGFloat
    let joinsOlder: Bool
    let joinsNewer: Bool
    let showAvatars: Bool
    let quickReplyEnabled: Bool

    var onMediaClick: (ChatMessage) -> Void
    private var onPhotoOpen: ((ChatMessage, CGRect, NSImage) -> Void)?
    var onReply: (ChatMessage) -> Void
    var onQuoteText: ((ChatMessage) -> Void)?
    var quotePicker: AnyView?
    var onQuotedClick: (String) -> Void
    var quoteHighlight: String?
    var onRevoke: (String) -> Void
    var onCancelQueuedSend: (() -> Void)?
    var onRetrySend: (() -> Void)?
    var retryingSend: Bool
    var onDeleteLocally: (() -> Void)?
    var onEdit: (ChatMessage) -> Void
    var onReEdit: (String) -> Void
    var onSelect: ()->Void
    var onForward: ()->Void
    var onSave: ()->Void
    var onPinState:()->Bool
    var onSetPinned:(Bool,Bool)->Void
    var onContactClick: (ChatMessage) -> Void
    var onLocationClick: (ChatMessage) -> Void


    public init(
        message: ChatMessage,
        peerAlias: String,
        replyAuthor: String = "User",
        peerAvatarPath: String?,
        forwardLabel: String? = nil,
        albumTile: Bool = false,
        maximumMessageBodyWidth: CGFloat = 408,
        joinsOlder: Bool = false,
        joinsNewer: Bool = false,
        showAvatars: Bool = true,
        quickReplyEnabled: Bool = false,
        onMediaClick: @escaping (ChatMessage) -> Void,
        onReply: @escaping (ChatMessage) -> Void,
        onQuoteText: ((ChatMessage) -> Void)? = nil,
        quotePicker: AnyView? = nil,
        onQuotedClick: @escaping (String) -> Void,
        quoteHighlight: String? = nil,
        onRevoke: @escaping (String) -> Void,
        onCancelQueuedSend: (() -> Void)? = nil,
        onRetrySend: (() -> Void)? = nil,
        retryingSend: Bool = false,
        onDeleteLocally: (() -> Void)? = nil,
        onEdit: @escaping (ChatMessage) -> Void,
        onReEdit: @escaping (String) -> Void,
        onSelect: @escaping ()->Void = {},
        onForward: @escaping ()->Void = {},
        onSave: @escaping ()->Void = {},
        onPinState:@escaping()->Bool={false},
        onSetPinned:@escaping(Bool,Bool)->Void={_,_ in},
        onContactClick: @escaping (ChatMessage) -> Void = { _ in },
        onLocationClick: @escaping (ChatMessage) -> Void = { _ in },
        onPhotoOpen: ((ChatMessage, CGRect, NSImage) -> Void)? = nil
    ) {
        self.message = message
        self.replyAuthor = replyAuthor
        self.peerAlias = peerAlias
        self.peerAvatarPath = peerAvatarPath
        self.forwardLabel=forwardLabel
        self.albumTile = albumTile
        self.maximumMessageBodyWidth = maximumMessageBodyWidth
        self.joinsOlder = joinsOlder
        self.joinsNewer = joinsNewer
        self.showAvatars = showAvatars
        self.quickReplyEnabled = quickReplyEnabled
        self.onMediaClick = onMediaClick
        self.onPhotoOpen = onPhotoOpen
        self.onReply = onReply
        self.onQuoteText = onQuoteText
        self.quotePicker = quotePicker
        self.onQuotedClick = onQuotedClick
        self.quoteHighlight = quoteHighlight
        self.onRevoke = onRevoke
        self.onCancelQueuedSend = onCancelQueuedSend
        self.onRetrySend = onRetrySend
        self.retryingSend = retryingSend
        self.onDeleteLocally = onDeleteLocally
        self.onEdit = onEdit
        self.onReEdit = onReEdit
        self.onSelect=onSelect;self.onForward=onForward;self.onSave=onSave
        self.onPinState=onPinState;self.onSetPinned=onSetPinned
        self.onContactClick = onContactClick
        self.onLocationClick = onLocationClick
    }

    private var isMe: Bool { message.isMine }
    private var hasLinkText: Bool {
        message.content.localizedCaseInsensitiveContains("https://") ||
            message.content.localizedCaseInsensitiveContains("http://")
    }

    private var allowInlineFooter: Bool {
        !hasLinkText && message.content.rangeOfCharacter(from: .newlines) == nil
    }

    private var showVisualFooter: Bool {
        message.ext == "attachment" &&
            EncryptedAttachmentCard.usesVisualFooter(attachmentMetadata, albumTile: albumTile)
    }

    private var canRevokeOrEdit: Bool {
        return isMe && message.isRevoked == 0
    }

    @ViewBuilder
    private func forwardAttribution(inTextBubble: Bool = false) -> some View {
        if let forwardLabel {
            Text(forwardLabel).font(.caption.weight(.medium))
                .foregroundStyle(inTextBubble ? Color.primary : Color.accentColor)
                .lineLimit(2)
        }
    }

    public var body: some View {
        if message.isRevoked == 1 {
            HStack {
                Spacer()
                HStack(spacing: 6) {
                    Text(interfaceText(isMe ? "revoked_by_you" : "revoked_by_peer"))
                        .font(.system(size: 12))
                        .foregroundColor(Color(white: 128/255))
                    if isMe && message.ext == "txt" && !message.content.isEmpty {
                        Button(interfaceText("re_edit")) {
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
            HStack(alignment: .bottom, spacing: 6) {
                if isMe && !albumTile { Spacer(minLength: 0) }

                if !isMe && !albumTile && showAvatars {
                    timelineAvatar(path: peerAvatarPath, name: peerAlias)
                }

                // Bubble Column
                VStack(alignment: isMe ? .trailing : .leading, spacing: 4) {
                    if message.ext != "txt" { forwardAttribution() }
                    // Bubble Content Box
                    VStack(alignment: isMe ? .trailing : .leading, spacing: 3) {
                        // Main Bubble
                        VStack(alignment: .leading, spacing: 0) {
                            if message.ext != "txt", message.replyToId?.isEmpty == false { replyQuote.padding(.bottom, 4) }
                            if message.ext == "txt" {
                                VStack(alignment: .leading, spacing: 4) {
                                    forwardAttribution(inTextBubble: true)
                                    replyQuote
                                    if let quotePicker {
                                        quotePicker
                                        messageMetadata.frame(maxWidth: .infinity, alignment: .trailing)
                                    } else {
                                        CappedBubbleLayout(allowInline: allowInlineFooter, direction: layoutDirection,
                                                           maximumBodyWidth: maximumMessageBodyWidth) {
                                            let displayText = quoteHighlight == nil ? displayCallSummary(message.content, strings: interfaceText) : message.content
                                            let textBody = RichMessageText(text: displayText, quoteHighlight: quoteHighlight,
                                                                           onReveal: { revealedForCopy = $0 },
                                                                           measuresFinalLine: !hasLinkText && !allowInlineFooter).id(displayText)
                                                .foregroundColor(.primary)
                                                .textSelection(.enabled)
                                                .onPreferenceChange(VisibleMessageLinks.self) { targets in
                                                    var seen = Set<URL>()
                                                    linkTargets = targets.filter { seen.insert($0).inserted }
                                                }
                                            if hasLinkText {
                                                LinkPreviewMessage(id: message.messageId, text: message.content, canOpen: showAvatars,
                                                                   downloadPeer: isMe ? nil : message.conversationUuid) { textBody }
                                            } else {
                                                textBody
                                            }
                                            messageMetadata
                                        }
                                    }
                                }
                                    .padding(.horizontal, 11).padding(.vertical, 7)
                                    .background(bubbleColor(isMe: isMe))
                                    .clipShape(
                                        UnevenRoundedRectangle(
                                            topLeadingRadius: !isMe && joinsOlder ? 4 : 16,
                                            bottomLeadingRadius: !isMe && joinsNewer ? 4 : 16,
                                            bottomTrailingRadius: isMe && joinsNewer ? 4 : 16,
                                            topTrailingRadius: isMe && joinsOlder ? 4 : 16
                                        )
                                    )
                            } else if ["contact", "location", "live_location"].contains(message.ext) {
                                let location = message.ext != "contact"
                                let live = message.ext == "live_location"
                                Button {
                                    if location { onLocationClick(message) } else { onContactClick(message) }
                                } label: {
                                    HStack(spacing: 10) {
                                        Image(systemName: location ? "mappin.circle.fill" : "person.crop.circle.fill").font(.system(size: 28))
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(message.content == "Location" ? interfaceText(live ? "live_location" : "location") : message.content).font(.headline).lineLimit(2)
                                            if live {
                                                LiveLocationCardStatus(message: message)
                                            } else if !location || message.content != "Location" {
                                                Text(interfaceText(location ? "location" : "contact_card")).font(.caption).opacity(0.78)
                                            }
                                        }
                                        Image(systemName: "chevron.right").font(.caption)
                                    }
                                    .padding(.horizontal, 12).padding(.vertical, 7)
                                    .frame(maxWidth: 275, alignment: .leading)
                                    .foregroundColor(.primary)
                                    .background(bubbleColor(isMe: isMe))
                                    .clipShape(RoundedRectangle(cornerRadius: 14))
                                    .contentShape(RoundedRectangle(cornerRadius: 14))
                                }
                                .buttonStyle(.plain)
                                .accessibilityHint(interfaceText(location ? "open_shared_location" : "open_contact_card"))
                            } else if message.ext == "attachment" {
                                EncryptedAttachmentCard(message: message, albumTile: albumTile,
                                    onRetryUpload: isMe && message.status == 0 ? onRetrySend : nil, retryingUpload: retryingSend,
                                    onMetadataChange: { attachmentMetadata = $0 },
                                    onStatusRetry: retryingSend ? nil : onRetrySend) { origin in
                                    if let origin, let onPhotoOpen {
                                        onPhotoOpen(message, origin.frame, origin.thumbnail)
                                    } else {
                                        onMediaClick(message)
                                    }
                                }
                            } else {
                                Text(interfaceText("unsupported_attachment")).font(.caption).foregroundStyle(.secondary)
                            }
                        }

                    }
                    .contextMenu {
                        if let deadline = messageExpirationLabel(message, format: interfaceText("message_expiration_deadline")) {
                            Text(deadline)
                        }
                        Button {
                            onReply(message)
                        } label: {
                            Label(interfaceText("reply"), systemImage: "arrowshape.turn.up.left")
                        }
                        if ["txt", "attachment", "contact", "location"].contains(message.ext) {
                            Button(action: onSelect) {
                                Label(interfaceText("select"), systemImage: "checkmark.circle")
                            }
                            if message.expiresAt == nil {
                                Button(action: onForward) {
                                    Label(interfaceText("forward"), systemImage: "arrowshape.turn.up.right")
                                }
                                Button(action: onSave) {
                                    Label(interfaceText("save_message"), systemImage: "bookmark")
                                }
                            }
                            let pinned = onPinState()
                            Button {
                                unpinRequested = pinned
                                confirmPin = true
                            } label: {
                                Label(interfaceText(pinned ? "unpin_message" : "pin_message"),
                                      systemImage: pinned ? "pin.slash" : "pin")
                            }
                        }
                        if message.ext == "txt" {
                            if message.expiresAt == nil, let onQuoteText {
                                Button {
                                    onQuoteText(message)
                                } label: {
                                    Label(interfaceText("quote_selected_text"), systemImage: "quote.bubble")
                                }
                            }
                            Button {
                                copyDisplayedText(message.content, revealed: revealedForCopy)
                            } label: {
                                Label(interfaceText("copy_message_text"), systemImage: "doc.on.doc")
                            }
                            ForEach(linkTargets, id: \.self) { destination in
                                Menu(destination.absoluteString) { MessageLinkActions(url: destination) }
                            }
                        } else if message.ext == "attachment", let caption = attachmentMetadata?.caption,
                                  !caption.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            Button {
                                copyDisplayedText(caption)
                            } label: {
                                Label(interfaceText("copy_attachment_caption"), systemImage: "doc.on.doc")
                            }
                        }
                        if canRevokeOrEdit {
                            if (-1...0).contains(message.status), let onRetrySend {
                                Button(action: onRetrySend) {
                                    Label(interfaceText("send_retry"), systemImage: "arrow.clockwise")
                                }.disabled(retryingSend)
                            }
                            if message.expiresAt == nil &&
                                (message.ext == "txt" || (message.ext == "attachment" && attachmentMetadata?.isSticker == false)) {
                                Button {
                                    onEdit(message)
                                } label: {
                                    Label(interfaceText(message.ext == "attachment" ? "edit_caption" : "edit_message"),
                                          systemImage: "pencil")
                                }
                            }
                        }
                        if message.ext == "attachment" {
                            if message.expiresAt == nil {
                                Button {
                                    saveEncryptedAttachment(message)
                                } label: {
                                    Label(interfaceText("save_attachment"), systemImage: "square.and.arrow.down")
                                }
                            }
                            if !isMe && attachmentMetadata?.available == true {
                                Button {
                                    confirmDownloadAgain = true
                                } label: {
                                    Label(interfaceText("download_attachment_again"), systemImage: "arrow.clockwise")
                                }
                            }
                        }
                        let canDelete = message.ext != "live_location" && (!isMe || message.status != 0)
                        if canRevokeOrEdit || (canDelete && onDeleteLocally != nil) { Divider() }
                        if canRevokeOrEdit {
                            if message.status == 0, let onCancelQueuedSend {
                                Button(role: .destructive, action: onCancelQueuedSend) {
                                    Label(interfaceText("send_cancel_action"), systemImage: "xmark.circle")
                                }
                            }
                            Button(role: .destructive) {
                                onRevoke(message.messageId)
                            } label: {
                                Label(interfaceText("revoke"), systemImage: "arrow.uturn.backward")
                            }
                        }
                        if canDelete, let onDeleteLocally {
                            Button(role: .destructive, action: onDeleteLocally) {
                                Label(interfaceText("local_delete_action"), systemImage: "trash")
                            }
                        }
                    }


                    if message.ext != "txt" && !showVisualFooter { messageMetadata }
                }
                .anchorPreference(key: ReplyGutterBoundary.self, value: .bounds) {
                    quickReplyEnabled && !albumTile ? $0 : nil
                }

                if isMe && !albumTile && showAvatars {
                    timelineAvatar(path: KeychainManager.shared.myAvatarPath, name: KeychainManager.shared.myNickname ?? "Me")
                } else if !isMe && !albumTile {
                    Spacer(minLength: 0)
                }
            }
            .overlayPreferenceValue(ReplyGutterBoundary.self) { boundary in
                if let boundary {
                    GeometryReader { geometry in
                        let bubble = geometry[boundary]
                        let onLeft = isMe == (layoutDirection == .leftToRight)
                        let gutterWidth = onLeft ? bubble.minX : geometry.size.width - bubble.maxX
                        if gutterWidth > 0 {
                            Color.clear
                                .frame(width: gutterWidth, height: geometry.size.height)
                                .contentShape(Rectangle())
                                .onTapGesture(count: 2) { onReply(message) }
                                .accessibilityHidden(true)
                                .position(x: onLeft ? gutterWidth / 2 : bubble.maxX + gutterWidth / 2,
                                          y: geometry.size.height / 2)
                        }
                    }
                }
            }
            .alert(interfaceText("download_attachment_again"), isPresented: $confirmDownloadAgain) {
                Button(interfaceText("cancel"), role: .cancel) {}
                Button(interfaceText("download_attachment_again"), role: .destructive) {
                    SecureMessaging.shared.replaceAttachmentCache(peer: message.conversationUuid, messageID: message.messageId)
                }
            } message: {
                Text(interfaceText("download_attachment_again_confirm"))
            }
            .confirmationDialog(interfaceText(unpinRequested ? "unpin_message" : "pin_message"),
                                isPresented: $confirmPin, titleVisibility: .visible) {
                if unpinRequested {
                    Button(interfaceText("unpin_message"), role: .destructive) { onSetPinned(false, false) }
                } else {
                    Button(interfaceText("pin_message")) { onSetPinned(true, false) }
                    Button(interfaceText("pin_and_notify_peer")) { onSetPinned(true, true) }
                }
                Button(interfaceText("cancel"), role: .cancel) {}
            } message: {
                if unpinRequested { Text(interfaceText("unpin_for_both")) }
            }
            .onDisappear { copyTask?.cancel() }
            .onChange(of: message.content) { _ in
                copyTask?.cancel()
                revealedForCopy = []
            }
            .onChange(of: attachmentMetadata?.caption) { _ in copyTask?.cancel() }
        }
    }

    private func copyDisplayedText(_ text: String, revealed: Set<Int> = []) {
        copyTask?.cancel()
        copyTask = Task { @MainActor in
            await MessageTextClipboard.copy(text, revealed: revealed, strings: interfaceText)
        }
    }

    @ViewBuilder
    private var replyQuote: some View {
        if let id = message.replyToId, !id.isEmpty {
            CappedBubbleLayout(allowInline: false, direction: layoutDirection, maximumBodyWidth: maximumMessageBodyWidth) {
                Button { onQuotedClick(id) } label: {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(replyAuthor).font(.caption.weight(.semibold)).foregroundStyle(Color.accentColor)
                        MessageSummaryText(text: quoteExcerpt).font(.caption2).foregroundStyle(.secondary)
                    }
                    .lineLimit(1)
                    .padding(.horizontal, 9).padding(.vertical, 4)
                    .background(Color.accentColor.opacity(0.13))
                    .overlay(alignment: .leading) { Rectangle().fill(Color.accentColor).frame(width: 2) }
                    .clipShape(RoundedRectangle(cornerRadius: 5))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help(interfaceText("quote_jump"))
                .accessibilityHint(interfaceText("quote_jump"))
            }
        }
    }

    private var quoteExcerpt: String {
        switch message.replyToContent ?? "" {
        case "[Message revoked]": return interfaceText("quote_revoked")
        case "[Media]", "[Attachment]", "[ATTACHMENT]": return interfaceText("quote_attachment")
        case let excerpt where !excerpt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty: return excerpt
        default: return interfaceText("quote_no_preview")
        }
    }


    private var messageMetadata: some View {
        ChatMessageMetadata(message: message, onRetry: retryingSend ? nil : onRetrySend)
    }

    private func bubbleColor(isMe: Bool) -> Color {
        if colorScheme == .dark {
            return isMe ? MsgATTheme.myBubbleDarkColor : MsgATTheme.peerBubbleDarkColor
        }
        return isMe ? MsgATTheme.myBubbleColor : MsgATTheme.peerBubbleColor
    }

    @ViewBuilder
    private func timelineAvatar(path: String?, name: String) -> some View {
        if joinsNewer {
            Color.clear.frame(width: 34, height: 1).accessibilityHidden(true)
        } else {
            AvatarView(avatarPath: path, fallbackText: name, size: 34, cornerRadius: 17)
                .accessibilityHidden(true)
        }
    }
}

private func messageExpirationLabel(_ message: ChatMessage, format: String) -> String? {
    guard message.isRevoked == 0, let expiresAt = message.expiresAt else { return nil }
    let date = Date(timeIntervalSince1970: Double(expiresAt) / 1000)
    return String(format: format, date.formatted(date: .abbreviated, time: .shortened))
}

struct ChatMessageMetadata: View {
    let message: ChatMessage
    var overMedia = false
    var onRetry: (() -> Void)? = nil
    @State private var showStatus = false
    @Environment(\.interfaceStrings) private var interfaceText

    var body: some View {
        let timeString = Date(timeIntervalSince1970: Double(message.timestamp) / 1000)
            .formatted(date: .omitted, time: .shortened)
        HStack(spacing: 4) {
            if let deadline = messageExpirationLabel(message, format: interfaceText("message_expiration_deadline")) {
                Image(systemName: "timer")
                    .font(.system(size: 10))
                    .foregroundStyle(overMedia ? Color.white : Color.secondary)
                    .help(deadline)
                    .accessibilityLabel(deadline)
            }
            Text(message.isEdited == 1 ? String(format: interfaceText("message_time_edited"), timeString) : timeString)
                .font(.system(size: 10))
                .foregroundStyle(overMedia ? Color.white : Color.secondary)

            if message.isMine {
                if message.status == -1 || message.status == 0 && onRetry != nil {
                    Button { showStatus = true } label: {
                        MessageReceiptMark(status: message.status, overMedia: overMedia)
                    }
                    .buttonStyle(.plain)
                    .popover(isPresented: $showStatus) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(interfaceText(message.status == -1 ? "receipt_failed" : "receipt_sending"))
                            if let onRetry {
                                Button(interfaceText("send_retry")) { showStatus = false; onRetry() }
                            }
                        }.padding(12)
                    }
                    .onChange(of: message.status) { _ in showStatus = false }
                } else {
                    MessageReceiptMark(status: message.status, overMedia: overMedia)
                }
            }
        }
    }
}

struct MessageReceiptMark: View {
    let status: Int
    var overMedia = false
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if let label = receiptLabel {
            ZStack {
                receiptMark
                    .id(status)
                    .transition(.opacity.combined(with: .scale(scale: 0.55)))
            }
            .frame(width: 16, height: 12)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: status)
            .help(label)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(label)
        }
    }

    private var receiptLabel: String? {
        switch status {
        case -1: return interfaceText("receipt_failed")
        case 0: return interfaceText("receipt_sending")
        case 1: return interfaceText("receipt_sent")
        case 2: return interfaceText("receipt_delivered")
        case 3: return interfaceText("receipt_read")
        default: return nil
        }
    }

    @ViewBuilder private var receiptMark: some View {
        let neutral = overMedia ? Color(red: 0.77, green: 0.79, blue: 0.80) : .gray
        let delivered = overMedia ? Color(red: 0.41, green: 0.85, blue: 0.69) : MsgATTheme.primaryColor
        switch status {
        case -1:
            Image(systemName:"exclamationmark.circle").font(.system(size:11))
                .foregroundStyle(overMedia ? Color(red: 1, green: 0.62, blue: 0.64) : Color.red)
        case 0:
            Image(systemName:"clock").font(.system(size:10)).foregroundStyle(neutral)
        case 1,2:
            Image(systemName:"checkmark")
                .font(.system(size:9,weight:.semibold))
                .foregroundStyle(status == 1 ? neutral : delivered)
        case 3:
            HStack(spacing:-4) {
                Image(systemName:"checkmark")
                Image(systemName:"checkmark")
            }
            .font(.system(size:9,weight:.semibold))
            .foregroundStyle(delivered)
        default:
            EmptyView()
        }
    }

}

private struct CappedBubbleLayout: Layout {
    let allowInline: Bool
    let direction: LayoutDirection
    var maximumBodyWidth: CGFloat = 408

    private func measure(_ subviews: Subviews, width: CGFloat) -> (text: CGSize, footer: CGSize, size: CGSize) {
        guard let body = subviews.first else { return (.zero, .zero, .zero) }
        let available = max(0, min(maximumBodyWidth, width))
        let natural = body.sizeThatFits(.unspecified)
        let text = body.sizeThatFits(ProposedViewSize(width: min(natural.width, available), height: nil))
        let metadata = subviews.dropFirst().first
        let footer = metadata?.sizeThatFits(ProposedViewSize(width: available, height: nil)) ?? .zero
        var lastLineWidth: CGFloat?
        if allowInline {
            lastLineWidth = text.width
        } else if let lastLine = body[LastPlainMessageLine.self],
                  text.height <= natural.height, footer.height <= lastLine.height {
            lastLineWidth = lastLine.width
        }
        let inlineWidth = lastLineWidth.map { max(text.width, $0 + 8 + footer.width) }
        let inline = metadata != nil && direction == .leftToRight && natural.width <= available &&
            inlineWidth.map { $0 <= available } == true
        let bodyWidth = inline ? (inlineWidth ?? text.width) : max(text.width, footer.width)
        let result = CGSize(width: min(available, bodyWidth),
                            height: inline ? max(text.height, footer.height) : text.height + (metadata == nil ? 0 : 3 + footer.height))
        return (text, footer, result)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        measure(subviews, width: proposal.width ?? maximumBodyWidth).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let layout = measure(subviews, width: bounds.width)
        let textX = direction == .rightToLeft ? bounds.maxX - layout.text.width : bounds.minX
        let footerX = direction == .rightToLeft ? bounds.minX : bounds.maxX - layout.footer.width
        let topLeft = UnitPoint(x: 0, y: 0)
        subviews.first?.place(at: CGPoint(x: textX, y: bounds.minY), anchor: topLeft,
                              proposal: ProposedViewSize(layout.text))
        subviews.dropFirst().first?.place(at: CGPoint(x: footerX, y: bounds.maxY - layout.footer.height), anchor: topLeft,
                                          proposal: ProposedViewSize(layout.footer))
    }
}
