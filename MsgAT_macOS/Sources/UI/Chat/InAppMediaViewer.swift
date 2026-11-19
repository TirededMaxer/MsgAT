import SwiftUI
import AVKit
import Combine

@MainActor
final class MediaPreviewFocus {
    private weak var window: NSWindow?
    private weak var responder: NSResponder?
    private var revision = 0

    func capture(in window: NSWindow?) {
        revision += 1
        self.window = window
        let current = window?.firstResponder
        if let fieldEditor = current as? NSTextView, fieldEditor.isFieldEditor,
           let field = fieldEditor.delegate as? NSTextField {
            responder = field
        } else {
            responder = current
        }
    }

    func restore() {
        let window = window
        let responder = responder
        clear()
        let expected = revision
        DispatchQueue.main.async { [weak self, weak window, weak responder] in
            guard let self, self.revision == expected, let window, window.isKeyWindow,
                  let responder, AppLockSession.shared.canShowContent else { return }
            if let view = responder as? NSView {
                guard view.window === window, !view.isHiddenOrHasHiddenAncestor else { return }
            }
            window.makeFirstResponder(responder)
        }
    }

    func clear() {
        revision += 1
        window = nil
        responder = nil
    }
}

@MainActor
final class MediaWindowFullScreen: ObservableObject {
    @Published private(set) var active = false
    private weak var window: NSWindow?
    private var entering = false
    private var ownsFullScreen = false
    private var entryObserver: AnyCancellable?
    private var entryTimeout: Task<Void, Never>?

    func toggle(in window: NSWindow?) {
        if active { end(); return }
        guard !entering, let window else { return }
        self.window = window
        active = true
        if !window.styleMask.contains(.fullScreen) {
            ownsFullScreen = true
            entering = true
            entryObserver = NotificationCenter.default.publisher(for: NSWindow.didEnterFullScreenNotification, object: window)
                .merge(with: NotificationCenter.default.publisher(for: NSWindow.willCloseNotification, object: window))
                .prefix(1)
                .receive(on: RunLoop.main)
                .sink { [self, weak window] notice in
                    if notice.name == NSWindow.willCloseNotification { didExit(window) }
                    else { didEnter(window) }
                }
            window.toggleFullScreen(nil)
            entryTimeout = Task { @MainActor [weak self, weak window] in
                do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
                guard let self, let window, self.entering else { return }
                if window.styleMask.contains(.fullScreen) { self.didEnter(window) }
                else { self.didExit(window) }
            }
        }
    }

    func end() {
        active = false
        if entering { return }
        if ownsFullScreen, let window, window.styleMask.contains(.fullScreen) {
            window.toggleFullScreen(nil)
        }
        ownsFullScreen = false
        window = nil
    }

    func didEnter(_ window: NSWindow?) {
        guard let window, self.window === window else { return }
        entryTimeout?.cancel()
        entryTimeout = nil
        entryObserver = nil
        entering = false
        if !active { end() }
    }

    func didExit(_ window: NSWindow?) {
        guard let window, self.window === window else { return }
        entryTimeout?.cancel()
        entryTimeout = nil
        entryObserver = nil
        entering = false
        ownsFullScreen = false
        self.window = nil
        active = false
    }
}

public struct InAppMediaViewer: View {
    struct Navigation {
        let position: String?
        let previous: (() -> Void)?
        let next: (() -> Void)?
        var album: [ChatMessage] = []
        var select: ((ChatMessage) -> Void)? = nil
    }

    let message: ChatMessage
    var onDismiss: () -> Void
    private let onCloseStarted: (() -> Void)?
    private let navigation: Navigation?
    private let onShowMessage: (() -> Void)?
    private let onForward: (() -> Void)?
    private let onReply: (() -> Void)?
    private let onOpenSharedMedia: ((AttachmentMetadata?) -> Void)?
    private let onRetryRefresh: (() -> Void)?
    private let saved: Bool
    private let origin: MediaPreviewOrigin?
    private let returnFrame: CGRect?
    private let albumStep: Int
    private let fullScreen: Bool
    private let toggleFullScreen: (() -> Void)?

    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var photoZoom = PhotoZoom()
    @State private var viewport: CGSize = .zero
    @GestureState private var magnification: CGFloat = 1
    @GestureState private var drag: CGSize = .zero
    @StateObject private var playback = MediaPlaybackModel()
    @State private var media: AttachmentMetadata?
    @State private var canSaveAttachment = false
    @State private var metadataRevision = 0
    @State private var metadataReadFailed = false
    @State private var transfer: AttachmentProgress?
    @State private var downloadOperation: UUID?
    @State private var downloadFailed = false
    @State private var image: NSImage?
    @State private var failed = false
    @State private var gifFallback: String?
    @State private var scrubPosition: Double?
    @State private var captionContentHeight: CGFloat = 0
    private enum PlaybackSlider: Hashable { case seek, volume }
    @FocusState private var focusedSlider: PlaybackSlider?
    @FocusState private var previewFocused: Bool
    @State private var entranceExpanded = false
    @State private var entranceFinished = false
    @State private var albumPhotoEntered = false
    @State private var dismissal: PhotoDismissal?
    @State private var dismissalCollapsed = false
    @State private var controlsShown = true
    @State private var toolbarHovered = false
    @State private var footerHovered = false
    @State private var lastControlActivity = ContinuousClock().now
    @State private var controlsIdleTask: Task<Void, Never>?

    private struct MetadataRequest: Hashable {
        let id: String
        let reference: String
        let revision: Int
        let gifFallback: String?
    }

    init(message: ChatMessage, navigation: Navigation? = nil, origin: MediaPreviewOrigin? = nil, returnFrame: CGRect? = nil,
         albumStep: Int = 0,
         fullScreen: Bool = false, toggleFullScreen: (() -> Void)? = nil,
         onShowMessage: (() -> Void)? = nil, onForward: (() -> Void)? = nil, onReply: (() -> Void)? = nil,
         onOpenSharedMedia: ((AttachmentMetadata?) -> Void)? = nil, onRetryRefresh: (() -> Void)? = nil,
         saved: Bool = false,
         onCloseStarted: (() -> Void)? = nil,
         onDismiss: @escaping () -> Void) {
        self.message = message
        self.navigation = navigation
        self.origin = origin
        self.returnFrame = returnFrame
        self.albumStep = albumStep
        self.fullScreen = fullScreen
        self.toggleFullScreen = toggleFullScreen
        self.onShowMessage = onShowMessage
        self.onForward = onForward
        self.onReply = onReply
        self.onOpenSharedMedia = onOpenSharedMedia
        self.onRetryRefresh = onRetryRefresh
        self.saved = saved
        self.onCloseStarted = onCloseStarted
        self.onDismiss = onDismiss
    }

    private var isImage: Bool { media?.available == true && media?.isImage == true }
    private var animatedGIF: AttachmentMetadata? {
        guard let media, media.available, media.mime == "image/gif",
              media.size <= 32 * 1024 * 1024, gifFallback != media.reference else { return nil }
        return media
    }
    private var isPlayable: Bool { media?.available == true && (media?.isVideo == true || media?.isAudio == true) }
    private var animateFromThumbnail: Bool { origin != nil && !reduceMotion }
    private var chromeVisible: Bool { (!animateFromThumbnail || entranceExpanded) && !dismissalCollapsed }
    private var previewControlsVisible: Bool { chromeVisible && controlsShown }
    private var viewerSurface: Color { Color(red: 34 / 255.0, green: 43 / 255.0, blue: 39 / 255.0) }
    private var canAutoHideControls: Bool {
        dismissal == nil && !failed && !playback.failed && !playback.preparing &&
            !metadataReadFailed && onRetryRefresh == nil &&
            (image != nil || animatedGIF != nil || fullScreen && media?.isVideo == true && playback.isPlaying)
    }

    public var body: some View {
        ZStack {
            GeometryReader { geometry in
                ZStack {
                    Color.black.opacity(chromeVisible ? 1 : 0)
                        .contentShape(Rectangle()).onTapGesture(perform: requestDismissal)
                    if let dismissal {
                        let viewportFrame = geometry.frame(in: .named(MediaPreviewCoordinateSpace.chat))
                        let target = dismissal.target.offsetBy(dx: -viewportFrame.minX, dy: -viewportFrame.minY)
                        transitionImage(dismissal.image, frame: dismissalCollapsed ? target : dismissal.start,
                                        cornerRadius: dismissalCollapsed ? 12 : 0)
                    } else if let gif = animatedGIF {
                        EncryptedStickerView(reference: gif.reference, gif: gif, onFailure: { gifFallback = gif.reference })
                            .frame(width: geometry.size.width, height: geometry.size.height)
                            .allowsHitTesting(false)
                    } else if let origin, animateFromThumbnail, !failed, media?.available != false, !entranceFinished || image == nil {
                        openingThumbnail(origin, in: geometry)
                    } else if isImage {
                        if let image {
                            zoomableImage(image, in: geometry.size)
                        } else if failed {
                            previewError
                        } else {
                            previewLoading
                        }
                    } else if isPlayable, !playback.failed {
                        NativeMediaPlayer(player: playback.player)
                            .frame(width: geometry.size.width, height: geometry.size.height)
                        if playback.preparing {
                            previewLoading
                        } else if media?.isAudio == true {
                            Image(systemName: "waveform").font(.system(size: 52)).foregroundStyle(.white.opacity(0.65))
                                .accessibilityHidden(true).allowsHitTesting(false)
                        }
                    } else if let media, !media.available, !saved, !message.isMine, message.isRevoked == 0,
                              onShowMessage != nil {
                        downloadControls(media)
                    } else if failed || playback.failed {
                        previewError
                    } else {
                        previewLoading
                    }
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
                .clipped()
                .coordinateSpace(name: PhotoCoordinateSpace.viewport)
                .onAppear { resizeViewport(to: geometry.size) }
                .onChange(of: geometry.size) { resizeViewport(to: $0) }
            }
        }
        .overlay(alignment: .bottom) {
            VStack(spacing: 4) {
                if metadataReadFailed || onRetryRefresh != nil {
                    HStack(spacing: 12) {
                        Text(interfaceText(metadataReadFailed ? "file_details_unavailable" : "shared_media_load_failed"))
                        Button(interfaceText("retry")) {
                            if metadataReadFailed { metadataRevision += 1 }
                            onRetryRefresh?()
                        }
                        .buttonStyle(.bordered)
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12).padding(.vertical, 7)
                    .background(viewerSurface, in: RoundedRectangle(cornerRadius: 8))
                    .environment(\.colorScheme, .dark)
                    .padding(.horizontal, 12)
                }
                if let caption = media?.caption, !caption.isEmpty, photoZoom.scale <= 1 {
                    captionView(caption)
                }
                if isPlayable, !playback.failed { playbackControls }
                if let navigation, navigation.album.count > 1, let select = navigation.select {
                    ScrollViewReader { proxy in
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(spacing: 6) {
                                ForEach(Array(navigation.album.enumerated()), id: \.element.id) { index, item in
                                    let current = item.id == message.id
                                    Button { if !current { select(item) } } label: {
                                        AlbumPreviewThumbnail(item: item, number: index + 1)
                                            .frame(width: 48, height: 44)
                                            .overlay(RoundedRectangle(cornerRadius: 5)
                                                .strokeBorder(current ? Color.accentColor : Color.clear, lineWidth: 2))
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityAddTraits(current ? .isSelected : [])
                                    .id(item.id)
                                }
                            }.padding(.horizontal, 8)
                        }
                        .onAppear { proxy.scrollTo(message.id, anchor: .center) }
                        .onChange(of: message.id) { proxy.scrollTo($0, anchor: .center) }
                        .onChange(of: navigation.album.map(\.id)) { _ in proxy.scrollTo(message.id, anchor: .center) }
                    }
                    .frame(height: 52)
                    .background(.black.opacity(0.75))
                }
            }
            .onHover { hovered in
                footerHovered = hovered
                revealControls()
            }
            .opacity(previewControlsVisible ? 1 : 0)
            .disabled(dismissal != nil)
            .allowsHitTesting(previewControlsVisible && dismissal == nil)
            .accessibilityHidden(!previewControlsVisible || dismissal != nil)
        }
        .overlay(alignment: .topTrailing) {
            Group {
                if let navigation {
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 8) {
                            navigationButtons(navigation)
                            Spacer(minLength: 8)
                            previewActions
                        }
                        VStack(alignment: .trailing, spacing: 6) {
                            previewActions
                            navigationButtons(navigation)
                        }
                    }
                    .padding(12)
                } else {
                    previewActions.padding(12)
                }
            }
            .onHover { hovered in
                toolbarHovered = hovered
                revealControls()
            }
            .opacity(previewControlsVisible ? 1 : 0)
            .disabled(dismissal != nil)
            .allowsHitTesting(previewControlsVisible && dismissal == nil)
            .accessibilityHidden(!previewControlsVisible || dismissal != nil)
        }
        .task(id: animateFromThumbnail) {
            guard animateFromThumbnail else { return }
            do {
                try await Task.sleep(nanoseconds: 16_000_000)
                withAnimation(.spring(response: 0.25, dampingFraction: 0.95)) {
                    entranceExpanded = true
                }
                try await Task.sleep(nanoseconds: 280_000_000)
                entranceFinished = true
            } catch is CancellationError {
                return
            } catch {
                entranceExpanded = true
                entranceFinished = true
            }
        }
        .task(id: MetadataRequest(id: message.messageId, reference: message.content,
                                  revision: metadataRevision, gifFallback: gifFallback)) {
            canSaveAttachment = false
            let message = message
            let metadata: AttachmentMetadata?
            do {
                metadata = try await EncryptedMediaWork.run {
                    try EncryptedObjectStore.shared.metadata(message.content, messageID: message.messageId)
                }
            } catch {
                guard !Task.isCancelled else { return }
                if media?.reference == message.content { metadataReadFailed = true }
                else { failed = true }
                return
            }
            guard !Task.isCancelled else { return }
            metadataReadFailed = false
            do {
                guard let value = metadata else { throw CocoaError(.fileReadNoSuchFile) }
                transfer = SecureMessaging.shared.attachmentProgress(message.id)
                let sameContent = media?.reference == value.reference && media?.available == value.available
                media = value
                if value.available && (onShowMessage != nil || saved) {
                    let isSaved = saved
                    let allowed = try? await EncryptedMediaWork.run {
                        if isSaved { return try MessageActionsStore.shared.canExport(id: message.id, reference: message.content) }
                        return try MsgDao.shared.canExportAttachment(message)
                    }
                    guard !Task.isCancelled else { return }
                    canSaveAttachment = allowed == true
                }
                if sameContent && !failed && !playback.failed && (image != nil || isPlayable || animatedGIF != nil) { return }
                playback.close()
                image = nil
                failed = false
                scrubPosition = nil
                photoZoom = PhotoZoom()
                guard value.available else { return }
                if animatedGIF != nil { return }
                if value.isImage {
                    let decoded = try await EncryptedMediaWork.run { try EncryptedObjectStore.shared.image(value, maximumDimension: 4096) }
                    guard !Task.isCancelled else { return }; image = decoded
                } else if value.isAudio || value.isVideo { playback.open(value) }
                else { failed = true }
            } catch {
                if !Task.isCancelled { playback.close(); image = nil; media = nil; failed = true }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: SecureMessaging.attachmentMetadataChanged).receive(on: RunLoop.main)) { notice in
            if notice.object as? String == message.id { metadataRevision += 1 }
        }
        .onReceive(NotificationCenter.default.publisher(for: SecureMessaging.attachmentCacheChanged).receive(on: RunLoop.main)) { _ in
            guard !message.isMine, !saved else { return }
            playback.close()
            image = nil
            media = nil
            gifFallback = nil
            metadataRevision += 1
        }
        .onReceive(NotificationCenter.default.publisher(for: AttachmentQueue.progressChanged).receive(on: RunLoop.main)) { notice in
            guard notice.object as? String == message.id else { return }
            let phase = transfer?.phase
            transfer = SecureMessaging.shared.attachmentProgress(message.id)
            if transfer?.phase != phase && (transfer?.phase == .complete || transfer?.phase == .unavailable) {
                metadataRevision += 1
            }
        }
        .task(id: dismissal != nil) {
            guard dismissal != nil else { return }
            do {
                try await Task.sleep(nanoseconds: 16_000_000)
                withAnimation(.spring(response: 0.25, dampingFraction: 0.95)) {
                    dismissalCollapsed = true
                }
                try await Task.sleep(nanoseconds: 280_000_000)
                guard !Task.isCancelled else { return }
                onDismiss()
            } catch {
                return
            }
        }
        .focusable()
        .focused($previewFocused)
        .onContinuousHover { phase in
            if case .active = phase { revealControls() }
        }
        .onChange(of: canAutoHideControls) { _ in revealControls() }
        .onChange(of: fullScreen) { _ in revealControls() }
        .onChange(of: focusedSlider) { _ in revealControls() }
        .onChange(of: previewFocused) { _ in revealControls() }
        .accessibilityAction(named: Text(interfaceText("media_show_controls"))) { revealControls() }
        .task {
            previewFocused = true
            revealControls()
        }
        .onChange(of: message.id) { _ in downloadOperation = nil; downloadFailed = false }
        .onDisappear {
            downloadOperation = nil
            controlsIdleTask?.cancel()
            controlsIdleTask = nil
            playback.close()
            image = nil
        }
        .onExitCommand(perform: handleEscape)
    }

    private var previewLoading: some View {
        HStack(spacing: 10) {
            ProgressView().controlSize(.small)
            Text(interfaceText("media_loading")).font(.callout)
        }
        .foregroundStyle(.white)
        .environment(\.colorScheme, .dark)
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(viewerSurface, in: RoundedRectangle(cornerRadius: 8))
        .accessibilityElement(children: .combine)
    }

    private var previewError: some View {
        VStack(spacing: 10) {
            Text(interfaceText("media_playback_unavailable"))
                .foregroundStyle(.white).multilineTextAlignment(.center)
            if message.isRevoked == 0, dismissal == nil {
                Button(interfaceText("retry")) {
                    if playback.failed, let media, media.available, media.reference == message.content {
                        playback.retry(media)
                    } else {
                        failed = false
                        metadataRevision += 1
                    }
                }
                .buttonStyle(.bordered)
                .foregroundStyle(.white)
            }
        }
        .padding(14)
        .frame(maxWidth: 340)
        .environment(\.colorScheme, .dark)
        .background(viewerSurface, in: RoundedRectangle(cornerRadius: 9))
        .padding(.horizontal, 16)
    }

    private func downloadControls(_ media: AttachmentMetadata) -> some View {
        let paused = media.downloadIntent == .paused
        let queued = media.downloadIntent == .requested
        let retrying = transfer?.phase == .retry
        let unavailable = media.remoteUnavailable || transfer?.phase == .unavailable
        let pause = !paused && !unavailable && !retrying && (queued || transfer?.phase == .downloading)
        return VStack(spacing: 8) {
            if let transfer, transfer.phase == .downloading, !paused, transfer.total > 0 {
                let fraction = min(1, max(0, Double(transfer.bytes) / Double(transfer.total)))
                ProgressView(value: fraction).frame(width: 140)
                Text(interfaceText.format("album_download_progress", Int64((fraction * 100).rounded())))
                    .foregroundStyle(.white)
            } else if paused || unavailable || queued && !retrying {
                Text(interfaceText(paused ? "download_paused" : unavailable ? "download_relay_unavailable" : "download_queued"))
                    .foregroundStyle(.white)
            }
            Button(interfaceText(pause ? "pause_download" : paused ? "resume_download" :
                unavailable || retrying ? "retry" : "download_attachment")) {
                guard downloadOperation == nil else { return }
                let operation = UUID()
                downloadOperation = operation
                downloadFailed = false
                SecureMessaging.shared.requestAttachmentDownload(peer: message.conversationUuid, messageID: message.id,
                                                                 requested: !pause) { success in
                    Task { @MainActor in
                        guard downloadOperation == operation else { return }
                        downloadOperation = nil
                        downloadFailed = !success
                        metadataRevision += 1
                    }
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(downloadOperation != nil)
            if downloadFailed {
                Text(interfaceText("media_playback_unavailable")).foregroundStyle(.white)
            }
        }
    }

    private func captionView(_ caption: String) -> some View {
        ScrollView {
            RichMessageText(text: caption)
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background {
                    GeometryReader { geometry in
                        Color.clear.preference(key: MediaCaptionHeight.self, value: geometry.size.height)
                    }
                }
        }
        .id(caption)
        .onPreferenceChange(MediaCaptionHeight.self) { captionContentHeight = $0 }
        .frame(maxWidth: 600)
        .frame(height: min(max(30, captionContentHeight), min(130, max(30, viewport.height * 0.28))))
        .background(.black.opacity(0.9), in: RoundedRectangle(cornerRadius: 8))
        .environment(\.colorScheme, .dark)
        .padding(.horizontal, 14)
        .padding(.bottom, 4)
    }

    private var playbackControls: some View {
        let playLabel = interfaceText(playback.isPlaying ? "pause" : "media_play")
        let silent = playback.isMuted || playback.volume == 0
        let muteLabel = interfaceText(silent ? "unmute" : "mute")
        return VStack(spacing: 5) {
            let upperBound = playback.duration > 0 ? playback.duration : 1
            Slider(value: Binding(get: { min(upperBound, scrubPosition ?? playback.position) }, set: { value in
                if scrubPosition != nil { scrubPosition = value }
                else { playback.seek(to: value) }
            }), in: 0...upperBound, onEditingChanged: { editing in
                if editing { scrubPosition = playback.position }
                else if let target = scrubPosition {
                    scrubPosition = nil
                    playback.seek(to: target)
                }
            })
            .tint(MsgATTheme.primaryColor)
            .disabled(playback.duration <= 0 || playback.preparing)
            .focused($focusedSlider, equals: .seek)
            .accessibilityLabel(interfaceText("media_seek"))
            HStack(spacing: 8) {
                previewButton(playback.isPlaying ? "pause.fill" : "play.fill", playLabel) {
                    revealControls()
                    playback.toggle()
                }
                    .keyboardShortcut(.space, modifiers: [])
                    .disabled(playback.preparing)
                Text("\(playback.timeText(scrubPosition ?? playback.position)) / \(playback.timeText(playback.duration))")
                    .font(.caption.monospacedDigit()).foregroundStyle(.white)
                Spacer(minLength: 4)
                MediaSpeedMenu(playback: playback)
                .foregroundStyle(.white)
                .environment(\.colorScheme, .dark)
                previewButton(silent ? "speaker.slash.fill" : "speaker.wave.2.fill", muteLabel) {
                    if playback.volume == 0 { playback.volume = 1 }
                    playback.isMuted = !silent
                }
                Slider(value: Binding(get: { playback.isMuted ? 0 : playback.volume }, set: { value in
                    playback.volume = value
                    playback.isMuted = false
                }), in: 0...1)
                .frame(width: 66)
                .focused($focusedSlider, equals: .volume)
                .accessibilityLabel(interfaceText("media_volume"))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.82)], startPoint: .top, endPoint: .bottom))
    }

    private var previewActions: some View {
        let zoomPercent = Double(photoZoom.scale).formatted(.percent.precision(.fractionLength(0)).locale(interfaceText.locale))
        return HStack(spacing: 8) {
            if image != nil {
                previewButton("minus.magnifyingglass", interfaceText("media_zoom_out"), enabled: photoZoom.scale > 1) {
                    setZoom(photoZoom.scale / 1.25)
                }
                .keyboardShortcut("-", modifiers: .command)
                previewButton("arrow.up.left.and.arrow.down.right", interfaceText("media_fit_window"), enabled: photoZoom.scale > 1) {
                    setZoom(1)
                }
                .keyboardShortcut("0", modifiers: .command)
                previewButton("plus.magnifyingglass", interfaceText("media_zoom_in"), enabled: photoZoom.scale < 5) {
                    setZoom(photoZoom.scale * 1.25)
                }
                .keyboardShortcut("=", modifiers: .command)
                .help("\(interfaceText("media_zoom_in")) · \(zoomPercent)")
                .accessibilityValue(zoomPercent)
            }
            if onShowMessage != nil || onOpenSharedMedia != nil || onReply != nil || canSaveAttachment {
                mediaActionsMenu
            }
            if let toggleFullScreen {
                previewButton(fullScreen ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right",
                              interfaceText(fullScreen ? "media_exit_full_screen" : "media_full_screen"), action: toggleFullScreen)
            }
            previewButton("xmark", interfaceText("close_media_preview"), action: requestDismissal)
                .keyboardShortcut(fullScreen ? nil : .cancelAction)
        }
        .padding(6)
        .background(viewerSurface.opacity(0.93), in: Capsule())
        .fixedSize()
    }

    private var mediaActionsMenu: some View {
        Menu {
            if let onShowMessage {
                Button(action: onShowMessage) { Label(interfaceText("show_in_chat"), systemImage: "text.bubble") }
            }
            if let onOpenSharedMedia {
                Button { onOpenSharedMedia(media) } label: {
                    Label(interfaceText("shared_media"), systemImage: "photo.on.rectangle")
                }
            }
            if message.isRevoked == 0, let onReply {
                Button(action: onReply) { Label(interfaceText("reply"), systemImage: "arrowshape.turn.up.left") }
            }
            if canSaveAttachment {
                if let onForward {
                    Button(action: onForward) { Label(interfaceText("forward_message"), systemImage: "arrowshape.turn.up.right") }
                }
                Button { saveEncryptedAttachment(message, saved: saved) } label: {
                    Label(interfaceText("save_attachment"), systemImage: "arrow.down.to.line")
                }
            }
        } label: {
            Image(systemName: "ellipsis").frame(width: 32, height: 32)
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .foregroundStyle(.white)
        .environment(\.colorScheme, .dark)
        .help(interfaceText("media_actions"))
        .accessibilityLabel(interfaceText("media_actions"))
    }

    private func navigationButtons(_ navigation: Navigation) -> some View {
        HStack(spacing: 8) {
            previewButton("chevron.left", interfaceText(navigation.position == nil ? "media_newer" : "media_previous_attachment"),
                          enabled: navigation.previous != nil) { navigation.previous?() }
                .keyboardShortcut(focusedSlider == nil ? KeyboardShortcut(.leftArrow, modifiers: []) : nil)
            if let position = navigation.position {
                Text(position).font(.caption.monospacedDigit()).foregroundStyle(.white)
            }
            previewButton("chevron.right", interfaceText(navigation.position == nil ? "media_older" : "media_next_attachment"),
                          enabled: navigation.next != nil) { navigation.next?() }
                .keyboardShortcut(focusedSlider == nil ? KeyboardShortcut(.rightArrow, modifiers: []) : nil)
        }
        .padding(6)
        .background(Color.black.opacity(0.75), in: Capsule())
        .fixedSize()
    }

    private func previewButton(_ icon: String, _ label: String, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 15, weight: .semibold))
                .frame(width: 30, height: 30).contentShape(Rectangle())
                .foregroundStyle(.white.opacity(enabled ? 1 : 0.45))
        }
        .buttonStyle(.plain).disabled(!enabled).help(label).accessibilityLabel(label)
    }

    private func setZoom(_ scale: CGFloat, at anchor: CGPoint? = nil) {
        revealControls()
        guard let image else { return }
        let fitted = fittedSize(of: image, in: viewport)
        let next = photoZoom.scaled(to: scale, at: anchor, in: viewport)
            .bounded(to: fitted, in: viewport)
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.20)) {
            photoZoom = next
        }
    }

    private func resizeViewport(to size: CGSize) {
        if let image, viewport.width > 0, viewport.height > 0, size.width > 0, size.height > 0 {
            let oldFit = fittedSize(of: image, in: viewport)
            let newFit = fittedSize(of: image, in: size)
            let ratio = oldFit.width > 0 ? newFit.width / oldFit.width : 1
            var adjusted = photoZoom
            adjusted.offset.width *= ratio
            adjusted.offset.height *= ratio
            photoZoom = adjusted.bounded(to: newFit, in: size)
        }
        viewport = size
    }

    private func fittedSize(of image: NSImage, in viewport: CGSize) -> CGSize {
        let ratio = min(viewport.width / max(1, image.size.width), viewport.height / max(1, image.size.height))
        return CGSize(width: image.size.width * ratio, height: image.size.height * ratio)
    }

    private func requestDismissal() {
        guard dismissal == nil else { return }
        controlsIdleTask?.cancel()
        controlsIdleTask = nil
        onCloseStarted?()
        guard !reduceMotion, !failed, !animateFromThumbnail || entranceFinished,
              let target = returnFrame, !target.isEmpty, let photo = image ?? origin?.thumbnail,
              viewport.width > 0, viewport.height > 0 else {
            onDismiss()
            return
        }
        let fitted = fittedSize(of: photo, in: viewport)
        let zoom = displayedPhotoZoom(fitted: fitted, in: viewport)
        let width = fitted.width * zoom.scale
        let height = fitted.height * zoom.scale
        let frame = CGRect(x: (viewport.width - width) / 2 + zoom.offset.width,
                           y: (viewport.height - height) / 2 + zoom.offset.height,
                           width: width, height: height)
        dismissal = PhotoDismissal(image: photo, start: frame, target: target)
    }

    private func handleEscape() {
        if fullScreen, let toggleFullScreen { revealControls(); toggleFullScreen() }
        else { requestDismissal() }
    }

    @MainActor private func revealControls() {
        guard dismissal == nil else { return }
        let clock = ContinuousClock()
        let idleDelay: Duration = .milliseconds(1400)
        lastControlActivity = clock.now
        if !controlsShown {
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.16)) { controlsShown = true }
        }
        guard canAutoHideControls else {
            controlsIdleTask?.cancel()
            controlsIdleTask = nil
            return
        }
        guard controlsIdleTask == nil else { return }
        controlsIdleTask = Task { @MainActor in
            while !Task.isCancelled {
                let deadline = lastControlActivity.advanced(by: idleDelay)
                do { try await Task.sleep(until: deadline, clock: clock) }
                catch { return }
                guard !Task.isCancelled else { return }
                if lastControlActivity.advanced(by: idleDelay) > clock.now { continue }
                controlsIdleTask = nil
                if !toolbarHovered && !footerHovered && focusedSlider == nil && drag == .zero {
                    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.24)) { controlsShown = false }
                }
                return
            }
        }
    }

    private func openingThumbnail(_ origin: MediaPreviewOrigin, in geometry: GeometryProxy) -> some View {
        let viewportFrame = geometry.frame(in: .named(MediaPreviewCoordinateSpace.chat))
        let source = origin.frame.offsetBy(dx: -viewportFrame.minX, dy: -viewportFrame.minY)
        let fitted = fittedSize(of: origin.thumbnail, in: geometry.size)
        let frame = entranceExpanded
            ? CGRect(x: (geometry.size.width - fitted.width) / 2, y: (geometry.size.height - fitted.height) / 2,
                     width: fitted.width, height: fitted.height)
            : source
        return transitionImage(origin.thumbnail, frame: frame, cornerRadius: entranceExpanded ? 0 : 12)
    }

    private func transitionImage(_ image: NSImage, frame: CGRect, cornerRadius: CGFloat) -> some View {
        Image(nsImage: image)
            .resizable()
            .scaledToFill()
            .frame(width: frame.width, height: frame.height)
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
            .position(x: frame.midX, y: frame.midY)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }

    private func displayedPhotoZoom(fitted: CGSize, in viewport: CGSize) -> PhotoZoom {
        photoZoom.scaled(to: photoZoom.scale * magnification, at: nil, in: viewport)
            .translated(by: drag).bounded(to: fitted, in: viewport)
    }

    private func zoomableImage(_ image: NSImage, in viewport: CGSize) -> some View {
        let fitted = fittedSize(of: image, in: viewport)
        let displayed = displayedPhotoZoom(fitted: fitted, in: viewport)
        let enteringAlbumPhoto = albumStep != 0 && !reduceMotion && !albumPhotoEntered
        return Image(nsImage: image)
            .resizable()
            .frame(width: fitted.width, height: fitted.height)
            .contentShape(Rectangle())
            .scaleEffect(displayed.scale)
            .offset(displayed.offset)
            .gesture(SpatialTapGesture(count: 2, coordinateSpace: CoordinateSpace.named(PhotoCoordinateSpace.viewport))
                .onEnded { value in setZoom(photoZoom.scale > 1 ? 1 : 2, at: value.location) })
            .simultaneousGesture(MagnificationGesture()
                .updating($magnification) { value, state, _ in state = value }
                .onEnded { value in
                    photoZoom = photoZoom.scaled(to: photoZoom.scale * value, at: nil, in: viewport)
                        .bounded(to: fitted, in: viewport)
                })
            .simultaneousGesture(DragGesture(minimumDistance: 5, coordinateSpace: .named(PhotoCoordinateSpace.viewport))
                .updating($drag) { value, state, _ in
                    if photoZoom.scale > 1 { state = value.translation }
                }
                .onEnded { value in
                    guard photoZoom.scale > 1 else { return }
                    photoZoom = photoZoom.translated(by: value.translation).bounded(to: fitted, in: viewport)
                    revealControls()
                })
            .offset(x: enteringAlbumPhoto ? CGFloat(albumStep) * 22 : 0)
            .opacity(enteringAlbumPhoto ? 0 : 1)
            .task {
                guard enteringAlbumPhoto else { return }
                do { try await Task.sleep(for: .milliseconds(16)) }
                catch { return }
                withAnimation(.easeOut(duration: 0.18)) { albumPhotoEntered = true }
            }
    }
}

private struct MediaCaptionHeight: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct AlbumPreviewThumbnail: View {
    let item: ChatMessage
    let number: Int
    @Environment(\.interfaceStrings) private var interfaceText
    @State private var media: AttachmentMetadata?
    @State private var transfer: AttachmentProgress?
    @State private var revision = 0

    private var downloadState: (icon: String, label: String)? {
        guard let media, !media.available, !item.isMine, item.isRevoked == 0 else { return nil }
        if media.remoteUnavailable { return ("arrow.clockwise", interfaceText("download_relay_unavailable")) }
        if media.downloadIntent == .paused { return ("pause.fill", interfaceText("download_paused")) }
        if let transfer, transfer.phase == .downloading, transfer.total > 0 {
            let fraction = min(1, max(0, Double(transfer.bytes) / Double(transfer.total)))
            return ("arrow.down", interfaceText.format("album_download_progress", Int64((fraction * 100).rounded())))
        }
        if transfer?.phase == .retry { return ("arrow.clockwise", interfaceText("retry")) }
        if media.downloadIntent == .requested { return ("clock", interfaceText("download_queued")) }
        return ("arrow.down.to.line", interfaceText("download_attachment"))
    }

    var body: some View {
        let state = downloadState
        Group {
            if let media { SharedMediaThumbnail(media: media) }
            else { Image(systemName: "photo").foregroundStyle(.white) }
        }
        .overlay {
            if let symbol = state?.icon ?? (media?.isVideo == true ? "play.fill" : nil) {
                Image(systemName: symbol)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 32, height: 32)
                    .background(.black.opacity(0.75), in: Circle())
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(interfaceText.format("media_attachment_number", Int64(number)))
        .accessibilityValue(state?.label ?? "")
        .task(id: "\(item.content):\(revision)") {
            let reference = item.content
            let id = item.id
            let result = try? await EncryptedMediaWork.run { try EncryptedObjectStore.shared.metadata(reference, messageID: id) }
            if !Task.isCancelled {
                media = result
                transfer = SecureMessaging.shared.attachmentProgress(id)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: SecureMessaging.attachmentMetadataChanged).receive(on: RunLoop.main)) { notice in
            if notice.object as? String == item.id { revision += 1 }
        }
        .onReceive(NotificationCenter.default.publisher(for: SecureMessaging.attachmentCacheChanged).receive(on: RunLoop.main)) { _ in
            if !item.isMine { revision += 1 }
        }
        .onReceive(NotificationCenter.default.publisher(for: AttachmentQueue.progressChanged).receive(on: RunLoop.main)) { notice in
            guard notice.object as? String == item.id else { return }
            let previous = transfer?.phase
            transfer = SecureMessaging.shared.attachmentProgress(item.id)
            if transfer?.phase != previous && (transfer?.phase == .complete || transfer?.phase == .unavailable) {
                revision += 1
            }
        }
    }
}

private struct PhotoDismissal {
    let image: NSImage
    let start: CGRect
    let target: CGRect
}

private enum PhotoCoordinateSpace: Hashable {
    case viewport
}

private struct PhotoZoom {
    var scale: CGFloat = 1
    var offset: CGSize = .zero

    func scaled(to proposed: CGFloat, at anchor: CGPoint?, in viewport: CGSize) -> PhotoZoom {
        let next = min(5, max(1, proposed))
        guard next > 1 else { return PhotoZoom() }
        let x = anchor.map { $0.x - viewport.width / 2 } ?? 0
        let y = anchor.map { $0.y - viewport.height / 2 } ?? 0
        let ratio = next / scale
        return PhotoZoom(scale: next, offset: CGSize(width: x + (offset.width - x) * ratio,
                                                     height: y + (offset.height - y) * ratio))
    }

    func translated(by delta: CGSize) -> PhotoZoom {
        PhotoZoom(scale: scale, offset: CGSize(width: offset.width + delta.width,
                                               height: offset.height + delta.height))
    }

    func bounded(to image: CGSize, in viewport: CGSize) -> PhotoZoom {
        guard scale > 1, viewport.width > 0, viewport.height > 0 else {
            return PhotoZoom(scale: scale)
        }
        let x = max(0, (image.width * scale - viewport.width) / 2)
        let y = max(0, (image.height * scale - viewport.height) / 2)
        return PhotoZoom(scale: scale, offset: CGSize(width: min(x, max(-x, offset.width)),
                                                     height: min(y, max(-y, offset.height))))
    }
}

private struct NativeMediaPlayer: NSViewRepresentable {
    let player: AVPlayer

    final class PlayerContainer: NSView {
        let playerView = AVPlayerView()

        override init(frame frameRect: NSRect) {
            super.init(frame: frameRect)
            wantsLayer = true
            layer?.masksToBounds = true
            playerView.controlsStyle = .none
            playerView.videoGravity = .resizeAspect
            playerView.translatesAutoresizingMaskIntoConstraints = false
            playerView.wantsLayer = true
            addSubview(playerView)
            NSLayoutConstraint.activate([
                playerView.leadingAnchor.constraint(equalTo: leadingAnchor),
                playerView.trailingAnchor.constraint(equalTo: trailingAnchor),
                playerView.topAnchor.constraint(equalTo: topAnchor),
                playerView.bottomAnchor.constraint(equalTo: bottomAnchor)
            ])
        }

        required init?(coder: NSCoder) { nil }

    }

    func makeNSView(context: Context) -> PlayerContainer { PlayerContainer() }

    func sizeThatFits(_ proposal: ProposedViewSize, nsView: PlayerContainer, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? 800, height: proposal.height ?? 600)
    }

    func updateNSView(_ container: PlayerContainer, context: Context) {
        let view = container.playerView
        if view.player !== player { view.player = player }
    }

    static func dismantleNSView(_ container: PlayerContainer, coordinator: ()) {
        let view = container.playerView
        view.player = nil
    }
}

@MainActor
struct MediaSpeedMenu: View {
    @ObservedObject var playback: MediaPlaybackModel
    @Environment(\.interfaceStrings) private var interfaceText

    var body: some View {
        let label = MediaPlaybackModel.speedLabel(playback.speed)
        Menu {
            ForEach(MediaPlaybackModel.speeds, id: \.self) { speed in
                Button {
                    playback.setSpeed(speed)
                } label: {
                    if playback.speed == speed {
                        Label(MediaPlaybackModel.speedLabel(speed), systemImage: "checkmark")
                    } else {
                        Text(MediaPlaybackModel.speedLabel(speed))
                    }
                }
            }
        } label: {
            Text(label).font(.caption.monospacedDigit())
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
        .accessibilityLabel(interfaceText("media_playback_speed"))
        .accessibilityValue(label)
        .help(interfaceText("media_playback_speed"))
    }
}

@MainActor final class MediaPlaybackModel: ObservableObject {
    static let speeds: [Float] = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]

    static func speedLabel(_ speed: Float) -> String { speed.formatted() + "×" }

    let player = AVPlayer()
    @Published var position: Double = 0
    @Published var duration: Double = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var failed = false
    @Published private(set) var preparing = false
    @Published private(set) var speed: Float = 1
    @Published var volume: Double = 1 { didSet { player.volume = Float(volume) } }
    @Published var isMuted = false { didSet { player.isMuted = isMuted } }
    private var observer: Any?
    private var stateObserver: NSKeyValueObservation?
    private var endObserver: NSObjectProtocol?
    private var failureObserver: NSObjectProtocol?
    private var loadedReference: String?
    private var assetLoader: EncryptedAssetLoader?
    private var playbackInterruptions = Set<AnyCancellable>()
    private var pauseRevision = 0

    init() {
        CallManager.shared.$callState
            .receive(on: RunLoop.main)
            .sink { [weak self] state in
                if state != .idle && state != .ended { self?.pause() }
            }.store(in: &playbackInterruptions)
        for event in [NSApplication.didResignActiveNotification, AppLockSession.changed] {
            NotificationCenter.default.publisher(for: event)
                .receive(on: RunLoop.main)
                .sink { [weak self] _ in
                    if !NSApp.isActive || !AppLockSession.shared.canShowContent { self?.pause() }
                }.store(in: &playbackInterruptions)
        }
    }
    func timeText(_ seconds: Double) -> String {
        let whole = Int(max(0, seconds))
        if whole >= 3600 { return String(format: "%d:%02d:%02d", whole / 3600, whole / 60 % 60, whole % 60) }
        return String(format: "%02d:%02d", whole / 60, whole % 60)
    }
    func open(_ media: AttachmentMetadata) {
        guard loadedReference != media.reference else { return }
        open(media, resumeAt: 0)
    }
    func retry(_ media: AttachmentMetadata) {
        guard failed else { return }
        open(media, resumeAt: loadedReference == media.reference ? position : 0)
    }
    private func open(_ media: AttachmentMetadata, resumeAt: Double) {
        close()
        loadedReference = media.reference
        position = resumeAt
        guard let loader = try? EncryptedAssetLoader(media) else { failed = true; return }
        prepare(loader, autoplay: true, resumeAt: resumeAt)
    }
    func openRecording(_ file: EncryptedCaptureFile, resumeAt: Double = 0) {
        close()
        position = resumeAt
        guard let loader = try? EncryptedAssetLoader(recording: file) else { failed = true; return }
        prepare(loader, autoplay: false, resumeAt: resumeAt)
    }
    private func prepare(_ loader: EncryptedAssetLoader, autoplay: Bool, resumeAt: Double = 0) {
        let autoplayRevision = pauseRevision
        assetLoader = loader
        preparing = true
        let item = AVPlayerItem(asset: loader.asset); player.replaceCurrentItem(with: item)
        stateObserver = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self, self.player.currentItem === item else { return }
                if item.status == .readyToPlay {
                    let value = item.duration.seconds
                    self.duration = value.isFinite ? max(0, value) : 0
                    if resumeAt > 0 {
                        let target = self.duration > 0 ? min(self.duration, resumeAt) : resumeAt
                        self.position = target
                        self.player.seek(to: CMTime(seconds: target, preferredTimescale: 600),
                                         toleranceBefore: .zero, toleranceAfter: .zero) { [weak self, weak item] finished in
                            Task { @MainActor [weak self, weak item] in
                                guard let self, let item, self.player.currentItem === item, !self.failed else { return }
                                if !finished { self.position = self.player.currentTime().seconds.isFinite ? self.player.currentTime().seconds : 0 }
                                self.preparing = false
                                if autoplay, self.pauseRevision == autoplayRevision { self.startPlayback() }
                            }
                        }
                    } else {
                        self.preparing = false
                    }
                }
                if item.status == .failed { self.failPlayback() }
            }
        }
        observer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.1, preferredTimescale: 600), queue: .main) { [weak self, weak item] time in
            Task { @MainActor [weak self, weak item] in
                guard let self, let item, self.player.currentItem === item else { return }
                if !self.preparing, !self.failed, time.seconds.isFinite { self.position = max(0, time.seconds) }
            }
        }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self, weak item] _ in
            Task { @MainActor [weak self, weak item] in
                guard let self, let item, self.player.currentItem === item else { return }
                self.isPlaying = false
                self.position = self.duration
            }
        }
        failureObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self, weak item] _ in
            Task { @MainActor [weak self, weak item] in
                guard let self, let item, self.player.currentItem === item else { return }
                self.failPlayback()
            }
        }
        if autoplay && resumeAt == 0 { startPlayback() }
    }
    private func failPlayback() {
        pause()
        failed = true
        preparing = false
    }
    func toggle() {
        guard player.currentItem != nil, !failed else { return }
        if isPlaying { pause() }
        else { if duration > 0, position >= duration { seek(to: 0) }; startPlayback() }
    }
    func setSpeed(_ speed: Float) {
        guard Self.speeds.contains(speed), self.speed != speed else { return }
        self.speed = speed
        if isPlaying { player.rate = speed }
    }
    private func startPlayback() {
        let state = CallManager.shared.callState
        guard NSApp.isActive, AppLockSession.shared.canShowContent,
              state == .idle || state == .ended else { return }
        player.playImmediately(atRate: speed)
        isPlaying = true
    }
    func pause() {
        pauseRevision += 1
        player.pause()
        isPlaying = false
    }
    func seek(to seconds: Double) {
        guard duration > 0, seconds.isFinite else { return }
        position = min(duration, max(0, seconds))
        player.seek(to: CMTime(seconds: position, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }
    func close() {
        if let observer { player.removeTimeObserver(observer); self.observer = nil }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver); self.endObserver = nil }
        if let failureObserver { NotificationCenter.default.removeObserver(failureObserver); self.failureObserver = nil }
        stateObserver?.invalidate(); stateObserver = nil
        player.pause(); player.replaceCurrentItem(with: nil); assetLoader?.close(); assetLoader = nil; loadedReference = nil; position = 0; duration = 0; isPlaying = false; failed = false
        preparing = false
    }
}
