// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import AppKit
import AVFoundation
import ImageIO
import UniformTypeIdentifiers

enum EncryptedMediaWork {
    private static let admission = NSLock()
    private static let playbackReads = DispatchGroup()
    private static var stopping = false
    private static let queue: OperationQueue = {
        let value = OperationQueue()
        value.name = "org.msgat.media-decode"; value.maxConcurrentOperationCount = 2; value.qualityOfService = .userInitiated
        return value
    }()
    static func run<T>(_ operation: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            admission.withLock {
                guard !stopping else { continuation.resume(throwing: CancellationError()); return }
                queue.addOperation {
                    do {
                        try checkOpen()
                        continuation.resume(returning: try operation())
                    } catch { continuation.resume(throwing: error) }
                }
            }
        }
    }

    static func beginPlaybackRead() -> Bool {
        admission.withLock {
            guard !stopping else { return false }
            playbackReads.enter()
            return true
        }
    }

    static func endPlaybackRead() { playbackReads.leave() }

    static func checkOpen() throws {
        if admission.withLock({ stopping }) { throw CancellationError() }
    }

    static func stop() {
        admission.withLock { stopping = true }
        queue.waitUntilAllOperationsAreFinished()
        playbackReads.wait()
    }
}

// Called on EncryptedMediaWork. Full-screen and animated playback still open the original object.
func encryptedMediaThumbnail(_ media: AttachmentMetadata, edge: Int) throws -> NSImage {
    let store = EncryptedObjectStore.shared
    if var bytes = try? store.thumbnailBytes(media.reference, edge: edge) {
        defer { bytes.resetBytes(in: bytes.startIndex..<bytes.endIndex) }
        if let source = CGImageSourceCreateWithData(bytes as CFData, nil),
           let image = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary) {
            return NSImage(cgImage: image, size: .zero)
        }
    }

    let image: CGImage
    if media.isVideo {
        let loader = try EncryptedAssetLoader(media)
        defer { loader.close() }
        let generator = AVAssetImageGenerator(asset: loader.asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: CGFloat(edge), height: CGFloat(edge))
        image = try generator.copyCGImage(at: .zero, actualTime: nil)
    } else {
        image = try store.imageRaster(media, maximumDimension: edge)
    }
    let storage = NSBitmapImageRep(cgImage: image)
    let opaque = media.isVideo || media.mime == "image/jpeg"
    if var encoded = storage.representation(using: opaque ? .jpeg : .png,
                                            properties: opaque ? [.compressionFactor: 0.86] : [:]) {
        defer { encoded.resetBytes(in: encoded.startIndex..<encoded.endIndex) }
        try? store.cacheThumbnail(media.reference, edge: edge, encoded: encoded, mime: opaque ? "image/jpeg" : "image/png")
    }
    return NSImage(cgImage: image, size: .zero)
}

/// Only the selected asset's authenticated byte ranges reach AVFoundation. No
/// HTTP server, exported content provider or decrypted local URL is registered.
final class EncryptedAssetLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    let asset: AVURLAsset
    private let media: AttachmentMetadata
    private let read:@Sendable(Int64,Int)throws->Data
    private var recordingLease:EncryptedCaptureLease?
    private let queue = DispatchQueue(label: "org.msgat.encrypted-asset", qos: .userInitiated)
    private let lock = NSLock()
    private var closed = false
    init(_ media: AttachmentMetadata) throws {
        guard media.available, let identifier = media.reference.split(separator: "/").last,
              let url = URL(string: "msgat-encrypted://\(identifier)/attachment") else { throw CocoaError(.fileReadCorruptFile) }
        self.media = media; asset = AVURLAsset(url: url)
        self.read={offset,count in try EncryptedObjectStore.shared.read(media.reference,offset:UInt64(offset),length:count)}
        super.init()
        asset.resourceLoader.setDelegate(self, queue: queue)
    }
    init(recording:EncryptedCaptureFile)throws {
        let lease=try recording.retainFrozen()
        do {
            media=AttachmentMetadata(reference:"recording",filename:recording.filename,mime:recording.mime,size:try recording.size,available:true,caption:"")
            read={offset,count in try recording.read(offset:offset,count:count)}
            recordingLease=lease
            asset=AVURLAsset(url:URL(string:"msgat-encrypted://\(UUID().uuidString)/recording")!)
            super.init();asset.resourceLoader.setDelegate(self,queue:queue)
        }catch{try? lease.close();throw error}
    }
    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
        guard request.request.url == asset.url else { return false }
        guard EncryptedMediaWork.beginPlaybackRead() else {
            request.finishLoading(with: CancellationError())
            return true
        }
        defer { EncryptedMediaWork.endPlaybackRead() }
        do {
            guard !isClosed else { throw CancellationError() }
            if let information = request.contentInformationRequest {
                information.contentType = UTType(mimeType: media.mime)?.identifier ?? UTType.data.identifier
                information.contentLength = media.size; information.isByteRangeAccessSupported = true
            }
            if let data = request.dataRequest {
                var offset = max(data.requestedOffset, data.currentOffset)
                guard offset >= 0, offset <= media.size, data.requestedOffset >= 0, data.requestedLength >= 0,
                      Int64(data.requestedLength) <= Int64.max - data.requestedOffset else { throw CocoaError(.fileReadCorruptFile) }
                let end = data.requestsAllDataToEndOfResource ? media.size : min(media.size, data.requestedOffset + Int64(data.requestedLength))
                while offset < end {
                    try EncryptedMediaWork.checkOpen()
                    guard !isClosed, !request.isCancelled else { throw CancellationError() }
                    let count = Int(min(256*1024, end - offset))
                    var bytes = try read(offset,count)
                    defer { bytes.resetBytes(in: bytes.startIndex..<bytes.endIndex) }
                    guard bytes.count == count else { throw CocoaError(.fileReadCorruptFile) }
                    data.respond(with: bytes); offset += Int64(bytes.count)
                }
            }
            request.finishLoading()
        } catch { request.finishLoading(with: error) }
        return true
    }
    private var isClosed: Bool { lock.lock(); defer { lock.unlock() }; return closed }
    func close() {
        let lease:EncryptedCaptureLease?=lock.withLock{closed=true;let value=recordingLease;recordingLease=nil;return value}
        asset.cancelLoading();queue.async{try? lease?.close()}
    }
    deinit{close()}
}

enum MediaPreviewCoordinateSpace: Hashable {
    case chat
}

struct MediaPreviewOrigin {
    let frame: CGRect
    let thumbnail: NSImage
}

struct MediaThumbnailFrame: Equatable {
    let inChat: CGRect
    let inTimeline: CGRect
}

struct MediaThumbnailFramePreference: PreferenceKey {
    static var defaultValue: [String: MediaThumbnailFrame] { [:] }

    static func reduce(value: inout [String: MediaThumbnailFrame], nextValue: () -> [String: MediaThumbnailFrame]) {
        value.merge(nextValue(), uniquingKeysWith: { _, latest in latest })
    }
}

struct ChatInlineAudio {
    let playback: MediaPlaybackModel?
    let toggle: (AttachmentMetadata) -> Void
}

private struct ChatInlineAudioKey: EnvironmentKey {
    static let defaultValue: ChatInlineAudio? = nil
}

extension EnvironmentValues {
    var chatInlineAudio: ChatInlineAudio? {
        get { self[ChatInlineAudioKey.self] }
        set { self[ChatInlineAudioKey.self] = newValue }
    }
}

struct EncryptedAttachmentCard: View {
    let message: ChatMessage
    var albumTile = false
    var onRetryUpload: (() -> Void)? = nil
    var retryingUpload = false
    var onMetadataChange: ((AttachmentMetadata?) -> Void)? = nil
    var onStatusRetry: (() -> Void)? = nil
    var onOpen: (MediaPreviewOrigin?) -> Void
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.chatInlineAudio) private var inlineAudio
    @State private var media: AttachmentMetadata?
    @State private var image: NSImage?
    @State private var progress: AttachmentProgress?
    @State private var failed = false
    @State private var cacheRevision = 0
    @State private var thumbnailFrame: CGRect = .zero
    @State private var openRequest: Task<Void, Never>?

    static func usesVisualFooter(_ media: AttachmentMetadata?, albumTile: Bool) -> Bool {
        guard !albumTile, let media, media.available, !media.isSticker, media.album == nil, media.caption.isEmpty else {
            return false
        }
        return media.isImage || media.isVideo
    }

    private func openLocalCopy(_ value: AttachmentMetadata, onPending: (() -> Void)? = nil, action: @escaping () -> Void) {
        openRequest?.cancel()
        openRequest = nil
        if message.isMine {
            action()
            return
        }
        openRequest = Task { @MainActor in
            defer { if !Task.isCancelled { openRequest = nil } }
            do {
                let complete = try await EncryptedObjectStore.shared.checkCiphertext(value.reference)
                guard !Task.isCancelled, media?.reference == value.reference, media?.available == true,
                      AppLockSession.shared.canShowContent else { return }
                if complete { action() }
                else {
                    let queued = await SecureMessaging.shared.queueAttachmentCacheReplacement(peer: message.conversationUuid,
                        messageID: message.messageId, reference: value.reference)
                    if queued && !Task.isCancelled && AppLockSession.shared.canShowContent { onPending?() }
                }
            } catch {
                if !Task.isCancelled { NoticeCenter.shared.show(interfaceText("file_details_unavailable")) }
            }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let media, media.isAudio, media.available, !albumTile, let inlineAudio {
                Group {
                    if let playback = inlineAudio.playback {
                        ActiveAudioAttachment(media: media, playback: playback) {
                            openLocalCopy(media) { inlineAudio.toggle(media) }
                        }
                    } else {
                        AudioAttachmentButton(media: media, playing: false) {
                            openLocalCopy(media) { inlineAudio.toggle(media) }
                        }
                    }
                }
                .padding(8).frame(width: 240, alignment: .leading)
                .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            } else {
                Button {
                    if let media, media.available {
                        let pending: (() -> Void)? = !media.isSticker && (media.isImage || media.isVideo) ? { onOpen(nil) } : nil
                        openLocalCopy(media, onPending: pending) {
                            if media.isImage || media.isVideo || media.isAudio {
                                let origin = (media.mime == "image/gif" ? nil : image).flatMap { thumbnail in
                                    thumbnailFrame.isEmpty ? nil : MediaPreviewOrigin(frame: thumbnailFrame, thumbnail: thumbnail)
                                }
                                onOpen(origin)
                            } else if message.expiresAt == nil {
                                saveEncryptedAttachment(message)
                            }
                        }
                    } else if opensPendingPreview {
                        onOpen(nil)
                    } else if canRequestDownload || canPauseDownload {
                        SecureMessaging.shared.requestAttachmentDownload(peer: message.conversationUuid, messageID: message.messageId,
                            requested: canRequestDownload)
                    }
                } label: {
                    if let media,media.isSticker,media.available {
                        EncryptedStickerView(reference:media.reference).frame(width:176,height:176)
                    } else if let image {
                        Image(nsImage: image).resizable().aspectRatio(contentMode: .fill)
                            .frame(width: albumTile ? nil : 250, height: albumTile ? 112 : 190)
                            .frame(maxWidth: albumTile ? .infinity : nil).clipped()
                    } else {
                        HStack(spacing: 10) {
                            Image(systemName: opensPendingPreview && canPauseDownload ? "clock" :
                                    canPauseDownload ? "pause.circle" : canRetryDownload ? "arrow.clockwise.circle" :
                                    canRequestDownload ? "arrow.down.circle" :
                                    (media?.isVideo == true || media?.isAudio == true ? "play.circle.fill" : "doc.fill"))
                                .font(.system(size: 28))
                            VStack(alignment: .leading, spacing: 4) {
                                Text(media?.filename ?? interfaceText("search_attachment")).lineLimit(2)
                                if let media { Text(ByteCountFormatter.string(fromByteCount: media.size, countStyle: .file)).font(.caption).foregroundStyle(.secondary) }
                                if fileCard {
                                    if !retryUploadOffered, let transferText, transferText != fileActionText {
                                        Text(transferText).font(.caption).foregroundStyle(.secondary)
                                    }
                                    if let transferFraction {
                                        ProgressView(value: transferFraction)
                                            .accessibilityLabel(transferText ?? "")
                                    }
                                    if let fileActionText {
                                        Text(fileActionText).font(.caption.weight(.semibold)).foregroundStyle(Color.accentColor)
                                    }
                                }
                            }
                        }.padding(12).frame(width: albumTile ? nil : 226, alignment: .leading)
                            .frame(maxWidth: albumTile ? .infinity : nil, minHeight: albumTile ? 112 : nil, alignment: .leading)
                    }
                }.buttonStyle(.plain)
                    .background(media?.isSticker==true ? Color.clear:Color.secondary.opacity(0.12)).clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(alignment: .bottomTrailing) {
                        if Self.usesVisualFooter(media, albumTile: albumTile) {
                            ChatMessageMetadata(message: message, overMedia: true, onRetry: onStatusRetry)
                                .padding(.horizontal, 6).padding(.vertical, 3)
                                .background(Color.black.opacity(0.72), in: Capsule())
                                .padding(6)
                                .allowsHitTesting(message.isMine && (message.status == -1 || message.status == 0 && onStatusRetry != nil))
                        }
                    }
                    .background {
                        if image != nil {
                            GeometryReader { geometry in
                                let frame = geometry.frame(in: .named(MediaPreviewCoordinateSpace.chat))
                                let timelineFrame = geometry.frame(in: .named("messageViewport"))
                                Color.clear
                                    .preference(key: MediaThumbnailFramePreference.self, value: [
                                        message.messageId: MediaThumbnailFrame(inChat: frame, inTimeline: timelineFrame)
                                    ])
                                    .onAppear { thumbnailFrame = frame }
                                    .onChange(of: frame) { thumbnailFrame = $0 }
                            }
                        }
                    }
                    .accessibilityLabel(media?.filename ?? interfaceText("search_attachment"))
                    .accessibilityValue(fileCard ? fileAccessibilityDetails : "")
                    .accessibilityHint(fileActionText ?? (opensPendingPreview ? interfaceText("open_media_preview") :
                        canPauseDownload ? interfaceText("pause_download") :
                        canRequestDownload ? interfaceText(media?.downloadIntent == .paused ? "resume_download" :
                            canRetryDownload ? "retry" : "download_attachment") : ""))
                    .disabled(fileCard && fileActionText == nil)
            }
            if let media, !media.caption.isEmpty, !albumTile || media.album?.index != 0 {
                RichMessageText(text: media.caption).id(media.caption).font(.system(size: 14))
                    .frame(maxWidth: albumTile ? .infinity : 250, alignment: .leading)
            }
            if progress?.phase == .retry, let onRetryUpload {
                Button(action: onRetryUpload) { Label(interfaceText("send_retry"), systemImage: "arrow.clockwise") }
                    .font(.caption).buttonStyle(.borderless).disabled(retryingUpload)
            } else if !fileCard, let transferText {
                VStack(alignment: .leading, spacing: 4) {
                    Text(transferText).font(.caption).foregroundStyle(.secondary)
                    if !albumTile, let transferFraction {
                        ProgressView(value: transferFraction)
                            .frame(maxWidth: 250)
                            .accessibilityLabel(transferText)
                    }
                }
            }
            if failed {
                HStack(spacing: 6) {
                    Text(interfaceText("file_details_unavailable")).foregroundStyle(.secondary)
                    Button(interfaceText("retry")) { cacheRevision += 1 }.buttonStyle(.borderless)
                }.font(.caption)
            }
            if openRequest != nil {
                HStack {
                    ProgressView().controlSize(.small)
                    Text(interfaceText("checking_attachment")).font(.caption)
                    Button(interfaceText("cancel")) {
                        openRequest?.cancel()
                        openRequest = nil
                    }
                }
            }
        }
        .task(id: "\(cacheRevision):\(message.content)") { await load() }
        .onDisappear { openRequest?.cancel(); openRequest = nil }
        .onChange(of: media) { value in onMetadataChange?(value) }
        .onReceive(NotificationCenter.default.publisher(for: SecureMessaging.attachmentCacheChanged)) { _ in
            if !message.isMine {
                image = nil
                media = nil
                cacheRevision += 1
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: AttachmentQueue.progressChanged).receive(on: RunLoop.main)) { event in
            guard event.object as? String == message.messageId else { return }
            let previous = progress?.phase
            progress = SecureMessaging.shared.attachmentProgress(message.messageId)
            if progress?.phase != previous && (progress?.phase == .complete || progress?.phase == .unavailable) {
                Task { await load() }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: SecureMessaging.attachmentMetadataChanged).receive(on: RunLoop.main)) { event in
            if event.object as? String == message.messageId { cacheRevision += 1 }
        }
    }
    private var fileCard: Bool {
        guard !albumTile, let media else { return false }
        return !media.isImage && !media.isVideo && !media.isAudio && !media.isSticker
    }
    private var retryUploadOffered: Bool { progress?.phase == .retry && onRetryUpload != nil }
    private var fileActionText: String? {
        guard fileCard, message.isRevoked == 0, let media else { return nil }
        if media.available { return message.expiresAt == nil ? interfaceText("save_attachment") : nil }
        if canPauseDownload { return interfaceText("pause_download") }
        if !canRequestDownload { return nil }
        if media.downloadIntent == .paused { return interfaceText("resume_download") }
        return interfaceText(canRetryDownload ? "retry" : "download_attachment")
    }
    private var fileAccessibilityDetails: String {
        guard let media else { return "" }
        let size = ByteCountFormatter.string(fromByteCount: media.size, countStyle: .file)
        guard let status = transferText, status != fileActionText else { return size }
        return "\(size), \(status)"
    }
    private var opensPendingPreview: Bool {
        guard let media, !media.isSticker, media.isImage || media.isVideo else { return false }
        return canRequestDownload || canPauseDownload
    }
    private var canRequestDownload: Bool {
        !message.isMine && message.isRevoked == 0 && media?.available == false && !canPauseDownload
    }
    private var canRetryDownload: Bool {
        canRequestDownload && media?.downloadIntent != .paused &&
            (media?.remoteUnavailable == true || progress?.phase == .retry)
    }
    private var canPauseDownload: Bool {
        guard !message.isMine, message.isRevoked == 0, let media,
              !media.available, !media.remoteUnavailable, media.downloadIntent != .paused else { return false }
        return media.downloadIntent == .requested || progress?.phase == .downloading
    }
    private var transferText: String? {
        if media?.available == true && progress?.phase != .uploading { return nil }
        if media?.downloadIntent == .paused { return interfaceText("download_paused") }
        if media?.remoteUnavailable == true { return interfaceText("download_relay_unavailable") }
        if media?.downloadIntent == .requested && progress?.phase != .downloading && progress?.phase != .retry {
            return interfaceText("download_queued")
        }
        switch progress?.phase {
        case .uploading: return interfaceText.format("attachment_upload_progress", percentage)
        case .downloading: return interfaceText.format("album_download_progress", percentage)
        case .retry: return interfaceText(canRequestDownload ? "retry" : "attachment_waiting_retry")
        case .unavailable: return media?.available == true ? nil : interfaceText("download_relay_unavailable")
        default:
            return canRequestDownload ? interfaceText("download_attachment") : nil
        }
    }
    private var transferFraction: Double? {
        guard let progress, progress.total > 0,
              progress.phase == .uploading || (progress.phase == .downloading && media?.downloadIntent != .paused)
        else { return nil }
        return Double(min(max(0, progress.bytes), progress.total)) / Double(progress.total)
    }
    private var percentage: Int64 { Int64((transferFraction ?? 0) * 100) }
    @MainActor private func load() async {
        let message = message
        failed = false
        do {
            let value = try await EncryptedMediaWork.run { try EncryptedObjectStore.shared.metadata(message.content, messageID: message.messageId) }
            guard !Task.isCancelled else { return }
            media = value; progress = SecureMessaging.shared.attachmentProgress(message.messageId)
            if value?.available != true { image = nil }
            if let value, value.available, value.isImage, !value.isSticker {
                let decoded = try await EncryptedMediaWork.run { try encryptedMediaThumbnail(value, edge: 640) }
                guard !Task.isCancelled else { return }; image = decoded
            }
        } catch { if !Task.isCancelled { failed = true } }
    }
}

private struct AudioAttachmentButton: View {
    @Environment(\.interfaceStrings) private var interfaceText
    let media: AttachmentMetadata
    let playing: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            HStack(spacing: 10) {
                Image(systemName: playing ? "pause.fill" : "play.fill")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 36)
                    .background(Color.accentColor, in: Circle())
                Text(media.filename).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(interfaceText(playing ? "pause" : "media_play"))
        .accessibilityLabel(media.filename)
        .accessibilityHint(interfaceText(playing ? "pause" : "media_play"))
    }
}

private struct ActiveAudioAttachment: View {
    @Environment(\.interfaceStrings) private var interfaceText
    let media: AttachmentMetadata
    @ObservedObject var playback: MediaPlaybackModel
    @State private var scrub: Double?
    let toggle: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            AudioAttachmentButton(media: media, playing: playback.isPlaying, toggle: toggle)
            if playback.failed {
                Text(interfaceText("media_playback_unavailable")).font(.caption).foregroundStyle(.secondary)
            } else if playback.preparing {
                ProgressView(interfaceText("media_loading")).controlSize(.small).font(.caption)
            } else if playback.duration > 0 {
                Slider(value: Binding(get: { scrub ?? playback.position }, set: { value in
                    if scrub != nil { scrub = value } else { playback.seek(to: value) }
                }), in: 0...playback.duration, onEditingChanged: { editing in
                    if editing { scrub = playback.position }
                    else if let target = scrub { scrub = nil; playback.seek(to: target) }
                })
                .accessibilityLabel(interfaceText("media_seek"))
                HStack {
                    Text("\(playback.timeText(scrub ?? playback.position)) / \(playback.timeText(playback.duration, duration: true))")
                        .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    Spacer(minLength: 4)
                    MediaSpeedMenu(playback: playback)
                }
            }
        }
    }
}

@MainActor func saveEncryptedAttachment(_ message: ChatMessage, saved: Bool = false) {
    guard AttachmentExportPanel.canStart else { return }
    let strings = InterfaceStrings.current()
    let allowed: @Sendable () throws -> Bool = {
        if saved { return try MessageActionsStore.shared.canExport(id: message.id, reference: message.content) }
        return try MsgDao.shared.canExportAttachment(message)
    }
    Task {
        do {
            guard try await EncryptedMediaWork.run(allowed) else {
                NoticeCenter.shared.show(strings("shared_media_save_failed")); return
            }
            guard let media = try await EncryptedMediaWork.run({ try EncryptedObjectStore.shared.metadata(message.content, messageID: message.messageId) }), media.available else { throw CocoaError(.fileReadNoSuchFile) }
            let exportFolder = try? await EncryptedMediaWork.run { try AttachmentExportPreferences.folder() }
            guard AppLockSession.shared.canShowContent, AttachmentExportPanel.canStart else { return }
            let panel = NSSavePanel(); panel.nameFieldStringValue = media.filename
            if let exportFolder { panel.directoryURL = URL(fileURLWithPath: exportFolder, isDirectory: true) }
            panel.title = strings("save_attachment")
            panel.canCreateDirectories = true
            panel.begin { result in
                guard result == .OK, let destination = panel.url, AppLockSession.shared.canShowContent else { return }
                AttachmentExportPanel.start(media: media, destination: destination, allowed: allowed)
            }
        } catch { NoticeCenter.shared.show(strings("shared_media_save_failed")) }
    }
}

@MainActor
final class AttachmentExportPanel: NSObject, NSWindowDelegate {
    private static var active: AttachmentExportPanel?
    private static var stopping = false
    static var canStart: Bool { !stopping && active == nil }

    private let window: NSPanel
    private let progress = NSProgressIndicator()
    private let status = NSTextField(labelWithString: "")
    private let cancelButton = NSButton()
    private var worker: Task<Void, Error>?
    private var completion: Task<Void, Never>?
    private var finished = false
    private let strings = InterfaceStrings.current()

    private init(size: Int64) {
        window = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 360, height: 112),
                         styleMask: [.titled, .closable], backing: .buffered, defer: false)
        super.init()
        window.title = strings("save_attachment")
        window.isReleasedWhenClosed = false
        window.delegate = self
        status.stringValue = strings("saving")
        progress.isIndeterminate = size <= 0
        progress.maxValue = Double(max(size, 1))
        progress.style = .bar
        progress.setAccessibilityLabel(strings("saving"))
        cancelButton.title = strings("cancel")
        cancelButton.bezelStyle = .rounded
        cancelButton.target = self
        cancelButton.action = #selector(requestCancellation)
        cancelButton.keyEquivalent = "\u{1b}"
        let content = NSStackView(views: [status, progress, cancelButton])
        content.orientation = .vertical
        content.alignment = .leading
        content.spacing = 8
        content.translatesAutoresizingMaskIntoConstraints = false
        if let root = window.contentView {
            root.addSubview(content)
            NSLayoutConstraint.activate([
                content.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 18),
                content.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -18),
                content.topAnchor.constraint(equalTo: root.topAnchor, constant: 14),
                progress.widthAnchor.constraint(equalTo: content.widthAnchor)
            ])
        }
    }

    static func start(media: AttachmentMetadata, destination: URL, allowed: @escaping @Sendable () throws -> Bool) {
        guard canStart, AppLockSession.shared.canShowContent else { return }
        let panel = AttachmentExportPanel(size: media.size)
        active = panel
        panel.window.center()
        panel.window.makeKeyAndOrderFront(nil)
        if panel.progress.isIndeterminate { panel.progress.startAnimation(nil) }
        let reference = media.reference
        let worker = Task.detached(priority: .userInitiated) { [weak panel] in
            let access = destination.startAccessingSecurityScopedResource()
            defer { if access { destination.stopAccessingSecurityScopedResource() } }
            try EncryptedObjectStore.shared.export(reference, to: destination, progress: { bytes in
                Task { @MainActor [weak panel] in
                    guard let panel, !panel.finished else { return }
                    panel.progress.doubleValue = min(panel.progress.maxValue, Double(bytes))
                }
            }, allowed: allowed)
        }
        panel.worker = worker
        panel.completion = Task { @MainActor [weak panel] in
            let result = await worker.result
            guard let panel else { return }
            panel.finish(result)
        }
    }

    static func cancelActive() { active?.requestCancellation() }

    static func stop() async {
        stopping = true
        cancelActive()
        await active?.completion?.value
    }

    @objc private func requestCancellation() {
        guard cancelButton.isEnabled, !finished else { return }
        cancelButton.isEnabled = false
        status.stringValue = strings("preparation_canceling")
        worker?.cancel()
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        if finished { return true }
        requestCancellation()
        return false
    }

    private func finish(_ result: Result<Void, Error>) {
        finished = true
        progress.stopAnimation(nil)
        window.close()
        Self.active = nil
        guard !Self.stopping, AppLockSession.shared.canShowContent else { return }
        switch result {
        case .success:
            NoticeCenter.shared.show(strings("attachment_exported"))
        case .failure(let error):
            if !(error is CancellationError) { NoticeCenter.shared.show(strings("shared_media_save_failed")) }
        }
    }
}
