// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import AppKit
import Combine

struct LinkPreviewComposer: View {
    @ObservedObject var model: ChatViewModel
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @State private var decodedCover: (request: CoverRequest, image: NSImage)?

    private struct CoverRequest: Equatable {
        let url: String
        let bytes: Data
    }

    private var coverRequest: CoverRequest? {
        guard model.previewEnabled, let draft = model.webPreview, draft.url == model.previewURL,
              let bytes = draft.image, (1...512 * 1024).contains(bytes.count) else { return nil }
        return CoverRequest(url: draft.url, bytes: bytes)
    }

    private var canChoose: Bool {
        model.previewEnabled && (model.previewChoices.count > 1 || model.previewURL == nil)
    }

    private var canEdit: Bool { model.previewEnabled && model.previewURL != nil }
    private var imageSizeKey: String { model.previewCompactImage ? "link_preview_large_image" : "link_preview_small_image" }
    private var placementKey: String { model.previewAboveText ? "move_link_preview_below" : "move_link_preview_above" }

    var body: some View {
        if !model.previewChoices.isEmpty {
            let cover = coverRequest
            let colors = MsgATTheme.messagingColors(for: colorScheme)
            VStack(alignment: .leading, spacing: 4) {
                previewToolbar(hasCover: cover != nil)
                if model.previewEnabled, let url = model.previewURL {
                    let draft = model.webPreview.flatMap { $0.url == url ? $0 : nil }
                    let host = MessageLinks.target(url)?.host ?? url
                    PreviewCardContents(host: host, title: draft?.title ?? "", summary: draft?.summary ?? "",
                                        image: decodedCover?.request == cover ? decodedCover?.image : nil,
                                        compact: model.previewCompactImage,
                                        coverHeight: 92, address: url)
                        .frame(maxWidth: 380, alignment: .leading)
                        .help(url)
                    if model.previewLoading {
                        ProgressView().progressViewStyle(.linear).frame(maxWidth: 380)
                            .accessibilityLabel(interfaceText("link_previews"))
                    } else if draft == nil {
                        HStack(spacing: 8) {
                            Text(interfaceText("link_preview_unavailable")).font(.caption2).foregroundStyle(.secondary)
                            Button(interfaceText("retry"), action: model.retryWebPreview)
                                .controlSize(.small)
                        }
                    }
                }
            }
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(colors.timeline, in: RoundedRectangle(cornerRadius: 8))
            .overlay {
                RoundedRectangle(cornerRadius: 8).strokeBorder(colors.divider).allowsHitTesting(false)
            }
            .padding(.horizontal, 12)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: model.previewCompactImage)
            .task(id: cover) {
                guard !Task.isCancelled, coverRequest == cover else { return }
                decodedCover = nil
                guard let cover, let image = try? await EncryptedMediaWork.run({ NSImage(data: cover.bytes) }),
                      !Task.isCancelled, coverRequest == cover else { return }
                decodedCover = (cover, image)
            }
        }
    }

    private func previewToolbar(hasCover: Bool) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 6) {
                previewToggle.fixedSize()
                Spacer(minLength: 6)
                if canChoose {
                    Menu { linkChoices } label: { Image(systemName: "link") }
                        .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                        .accessibilityLabel(interfaceText("choose_link_preview"))
                        .help(interfaceText("choose_link_preview"))
                }
                if canEdit {
                    if hasCover {
                        tool(model.previewCompactImage ? "arrow.up.left.and.arrow.down.right" : "arrow.down.right.and.arrow.up.left",
                             imageSizeKey, action: model.togglePreviewImageSize)
                    }
                    tool(model.previewAboveText ? "arrow.down" : "arrow.up", placementKey, action: model.togglePreviewPlacement)
                    tool("xmark", "remove_link_preview", action: model.removeWebPreview)
                }
            }
            HStack(spacing: 6) {
                previewToggle
                Spacer(minLength: 4)
                if canChoose || canEdit {
                    Menu {
                        if canChoose { Menu(interfaceText("choose_link_preview")) { linkChoices } }
                        if canEdit {
                            if hasCover { Button(interfaceText(imageSizeKey), action: model.togglePreviewImageSize) }
                            Button(interfaceText(placementKey), action: model.togglePreviewPlacement)
                            Button(interfaceText("remove_link_preview"), action: model.removeWebPreview)
                        }
                    } label: {
                        Image(systemName: "ellipsis").frame(width: 28, height: 28)
                    }
                    .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                    .accessibilityLabel(interfaceText("link_preview_actions"))
                    .help(interfaceText("link_preview_actions"))
                }
            }
        }
    }

    private var previewToggle: some View {
        Toggle(interfaceText("link_previews"), isOn: $model.previewEnabled)
            .toggleStyle(.checkbox).font(.caption).foregroundStyle(.secondary)
    }

    private var linkChoices: some View {
        ForEach(model.previewChoices, id: \.self) { url in
            Button {
                model.selectWebPreview(url)
            } label: {
                if url == model.previewURL { Label(url, systemImage: "checkmark") }
                else { Text(url) }
            }
        }
    }

    private func tool(_ icon: String, _ key: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).frame(width: 28, height: 28) }
            .buttonStyle(.plain)
            .accessibilityLabel(interfaceText(key))
            .help(interfaceText(key))
    }
}

private struct PreviewCardContents: View {
    let host: String
    let title: String
    let summary: String
    let image: NSImage?
    let compact: Bool
    let coverHeight: CGFloat
    var address: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 8) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(host).font(.caption2.weight(.semibold)).foregroundStyle(Color.accentColor).lineLimit(1)
                        .environment(\.layoutDirection, .leftToRight)
                    if !title.isEmpty { Text(title).font(.system(size: 13, weight: .semibold)).lineLimit(2) }
                    if !summary.isEmpty { Text(summary).font(.caption).foregroundStyle(.secondary).lineLimit(3) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if compact, let image { cover(image).frame(width: 56, height: 56).clipped() }
            }
            if !compact, let image { cover(image).frame(height: coverHeight).clipped() }
            if let address {
                Text(address).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .padding(.leading, 12).padding(.trailing, 8).padding(.vertical, 7)
        .background(Color.secondary.opacity(0.10), in: RoundedRectangle(cornerRadius: 7))
        .overlay(alignment: .leading) {
            RoundedRectangle(cornerRadius: 2).fill(Color.accentColor).frame(width: 3).allowsHitTesting(false)
        }
        .contentShape(RoundedRectangle(cornerRadius: 7))
    }

    private func cover(_ image: NSImage) -> some View {
        Color.clear.overlay { Image(nsImage: image).resizable().scaledToFill() }
            .clipShape(RoundedRectangle(cornerRadius: 5))
            .accessibilityHidden(true)
    }
}

struct LinkPreviewMessage<Content: View>: View {
    let id: String;let text: String;var saved=false;var canOpen=true
    var downloadPeer: String? = nil
    @ViewBuilder var content: () -> Content
    @Environment(\.interfaceStrings) private var interfaceText
    @State private var preview: WebPreviewSnapshot?
    @State private var previewSource: Source?
    @State private var image: NSImage?
    @State private var pendingCover: AttachmentMetadata?
    @State private var coverReadFailed = false
    @State private var transfer: AttachmentProgress?
    @State private var requesting = false
    @State private var retry=0
    private struct Source: Equatable { let id: String; let text: String; let saved: Bool }
    private struct Key: Equatable { let source: Source; let retry: Int }
    var body: some View {
        let source = Source(id: id, text: text, saved: saved)
        let row = previewSource == source ? preview : nil
        VStack(alignment: .leading, spacing: 4) {
            if let row, row.aboveText { card(row) }
            content()
            if let row, !row.aboveText { card(row) }
        }
        .task(id: Key(source: source, retry: retry)) { await load(source) }
        .onReceive(NotificationCenter.default.publisher(for: AttachmentQueue.previewProgressChanged).receive(on: RunLoop.main)) { note in
            guard !saved, let update = note.object as? AttachmentProgress,
                  update.messageID == id, update.reference == row?.imageReference else { return }
            transfer = update
        }
        .onReceive(NotificationCenter.default.publisher(for: SecureMessaging.attachmentCacheChanged).receive(on: RunLoop.main)) { _ in
            if !saved && downloadPeer != nil && row?.imageReference != nil { retry &+= 1 }
        }
        .onReceive(NotificationCenter.default.publisher(for: MsgDao.didChangeNotification)
            .receive(on: RunLoop.main)
            .throttle(for: .milliseconds(200), scheduler: RunLoop.main, latest: true)) { _ in
            if preview?.imageReference != nil && image == nil { retry &+= 1 }
        }
    }

    @ViewBuilder private func card(_ row: WebPreviewSnapshot) -> some View {
        if let url = MessageLinks.target(row.url) {
            VStack(alignment: .leading, spacing: 3) {
                Button {
                    if canOpen { _ = MessageLinks.open(url, failure: interfaceText("link_open_failed")) }
                } label: {
                    PreviewCardContents(host: url.host ?? row.url, title: row.title,
                        summary: row.summary, image: image, compact: row.compactImage, coverHeight: 132)
                        .frame(maxWidth: 264, alignment: .leading)
                }.buttonStyle(.plain).disabled(!canOpen)
                    .help(url.absoluteString)
                    .contextMenu {
                        if canOpen {
                            Menu(url.absoluteString) { MessageLinkActions(url: url) }
                        }
                    }
                if !saved, canOpen, let peer = downloadPeer, let reference = row.imageReference,
                   coverReadFailed || pendingCover?.reference == reference {
                    let currentTransfer = transfer.flatMap { update in
                        update.messageID == id && update.reference == reference ? update : nil
                    }
                    let paused = pendingCover?.downloadIntent == .paused
                    let needsRetry = !paused && (coverReadFailed || pendingCover?.remoteUnavailable == true || currentTransfer?.phase == .retry)
                    let queued = !paused && !needsRetry && pendingCover?.downloadIntent == .requested
                    let downloading = !paused && !needsRetry && currentTransfer?.phase == .downloading ? currentTransfer : nil
                    let canPause = queued || downloading != nil
                    let status = downloading.map { update in
                        interfaceText.format("album_download_progress", min(100, max(0, 100 * update.bytes / max(1, update.total))))
                    } ?? (queued ? interfaceText("download_queued") : nil)
                    let action = canPause ? "pause_download" : paused ? "resume_download" : needsRetry ? "retry" : "preview_download"
                    HStack(spacing: 6) {
                        if let status { Text(status).foregroundStyle(.secondary) }
                        Button(interfaceText(action)) {
                            setDownloadRequested(peer: peer, reference: reference, requested: !canPause,
                                replacingCache: coverReadFailed)
                        }
                        .buttonStyle(.borderless)
                        .disabled(requesting)
                    }
                    .font(.caption)
                }
            }
        }
    }
    @MainActor private func setDownloadRequested(peer: String, reference: String, requested: Bool, replacingCache: Bool) {
        guard !requesting, AppLockSession.shared.canShowContent else { return }
        requesting = true
        SecureMessaging.shared.setPreviewDownloadRequested(peer: peer, messageID: id, reference: reference,
                                                       requested: requested, replacingCache: replacingCache) { changed in
            Task { @MainActor in
                requesting = false
                guard AppLockSession.shared.canShowContent, preview?.imageReference == reference else { return }
                if !changed { NoticeCenter.shared.show(interfaceText("preview_download_unavailable")) }
                retry &+= 1
            }
        }
    }
    @MainActor private func load(_ source: Source) async {
        do {
            let row=try await EncryptedMediaWork.run {try AppDatabase.shared.transaction {try WebPreviewStore.read($0,id:source.id,saved:source.saved)}}
            guard !Task.isCancelled else {return}
            if previewSource != source || preview?.imageReference != row?.imageReference {
                image = nil
                pendingCover = nil
                coverReadFailed = false
            }
            previewSource = source
            preview = row
            if let ref=row?.imageReference {
                let result = try await EncryptedMediaWork.run { () -> (NSImage?, AttachmentMetadata?, Bool) in
                    guard let media = try EncryptedObjectStore.shared.metadata(ref, messageID: source.id),
                          (1...512 * 1024).contains(media.size), media.mime == "image/jpeg" else { return (nil, nil, false) }
                    guard media.available else { return (nil, media.hasPendingDownload ? media : nil, false) }
                    do { return (try EncryptedObjectStore.shared.image(media, maximumDimension: 512), nil, false) }
                    catch { return (nil, nil, true) }
                }
                if !Task.isCancelled { (image, pendingCover, coverReadFailed) = result }
            }
        } catch { if !Task.isCancelled { image = nil } }
    }
}
