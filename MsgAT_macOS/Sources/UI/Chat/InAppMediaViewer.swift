import SwiftUI
import AVKit

public struct InAppMediaViewer: View {
    let message: ChatMessage
    var onDismiss: () -> Void

    @State private var scale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var settledScale: CGFloat = 1.0
    @State private var settledOffset: CGSize = .zero
    @StateObject private var playback = MediaPlaybackModel()

    public init(message: ChatMessage, onDismiss: @escaping () -> Void) {
        self.message = message
        self.onDismiss = onDismiss
    }

    private var isImage: Bool {
        ["jpg", "jpeg", "png", "webp"].contains(message.ext.lowercased())
    }

    private var mediaUrl: URL? {
        MediaStorageManager.shared.getOrExtractMediaFile(
            msgId: message.messageId,
            ext: message.ext,
            content: message.content
        )
    }

    public var body: some View {
        ZStack {
            GeometryReader { geometry in
                ZStack {
                    Color.black.onTapGesture(perform: onDismiss)
                    if isImage {
                        if let url = mediaUrl, let img = NSImage(contentsOf: url) {
                            Image(nsImage: img)
                                .resizable()
                                .aspectRatio(contentMode: .fit)
                                .frame(width: geometry.size.width, height: geometry.size.height)
                                .scaleEffect(scale)
                                .offset(offset)
                                .gesture(
                                    MagnificationGesture()
                                        .onChanged { value in
                                            scale = max(1, min(settledScale * value, 5))
                                            if scale == 1 {
                                                offset = .zero
                                                settledOffset = .zero
                                            }
                                        }
                                        .onEnded { _ in settledScale = scale }
                                )
                                .simultaneousGesture(
                                    DragGesture()
                                        .onChanged { value in
                                            guard scale > 1 else { return }
                                            offset = CGSize(
                                                width: settledOffset.width + value.translation.width,
                                                height: settledOffset.height + value.translation.height
                                            )
                                        }
                                        .onEnded { _ in settledOffset = offset }
                                )
                                .simultaneousGesture(TapGesture().onEnded { _ in onDismiss() })
                        } else {
                            Text("Corrupted Image").foregroundColor(.white)
                        }
                    } else if let url = mediaUrl {
                        NativeMediaPlayer(player: playback.player)
                            .onAppear { playback.open(url) }
                            .onTapGesture(perform: onDismiss)
                            .frame(width: geometry.size.width, height: geometry.size.height)
                    } else {
                        Text("Loading Video...").foregroundColor(.white)
                    }
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
                .clipped()
            }
        }
        .background(Color.black)
        .overlay(alignment: .bottom) {
            if !isImage, mediaUrl != nil {
                VStack(spacing: 0) {
                    HStack(spacing: 8) {
                        Button { playback.toggle() } label: {
                            Image(systemName: playback.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 24)).foregroundColor(.white).padding(8)
                        }.buttonStyle(.plain).help(playback.isPlaying ? "Pause" : "Play")
                        Text("\(playback.positionText) / \(playback.durationText)").font(.system(size: 12)).foregroundColor(.white)
                        Spacer()
                    }
                    Slider(value: Binding(get: { playback.duration > 0 ? playback.position / playback.duration : 0 }, set: { playback.seek(to: $0 * playback.duration) }), in: 0...1)
                        .tint(MsgATTheme.primaryColor).disabled(playback.duration <= 0)
                }.padding(16).background(Color.black.opacity(0.6))
            }
        }
        .onDisappear { playback.close() }
        .onExitCommand(perform: onDismiss)
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

@MainActor private final class MediaPlaybackModel: ObservableObject {
    let player = AVPlayer()
    @Published var position: Double = 0
    @Published var duration: Double = 0
    @Published var isPlaying = true
    private var observer: Any?
    private var stateObserver: NSKeyValueObservation?
    private var endObserver: NSObjectProtocol?
    private var loadedURL: URL?
    var positionText: String { format(position) }
    var durationText: String { format(duration) }
    private func format(_ seconds: Double) -> String { String(format: "%02d:%02d", Int(max(0, seconds)) / 60, Int(max(0, seconds)) % 60) }
    func open(_ url: URL) {
        guard loadedURL != url else { return }
        close(); loadedURL = url
        let item = AVPlayerItem(url: url); player.replaceCurrentItem(with: item)
        stateObserver = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self, self.player.currentItem === item else { return }
                if item.status == .readyToPlay { let value = item.duration.seconds; self.duration = value.isFinite ? max(0, value) : 0 }
                if item.status == .failed { NoticeCenter.shared.show("Failed to load video") }
            }
        }
        observer = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.1, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor [weak self] in
                guard let self, self.loadedURL == url else { return }
                if time.seconds.isFinite { self.position = max(0, time.seconds) }
                self.isPlaying = self.player.rate != 0
            }
        }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.loadedURL == url else { return }
                self.isPlaying = false
                self.position = self.duration
            }
        }
        isPlaying = true; player.play()
    }
    func toggle() {
        if isPlaying { player.pause(); isPlaying = false }
        else { if position >= duration { seek(to: 0) }; player.play(); isPlaying = true }
    }
    func seek(to seconds: Double) { position = max(0, seconds); player.seek(to: CMTime(seconds: position, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) }
    func close() {
        if let observer { player.removeTimeObserver(observer); self.observer = nil }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver); self.endObserver = nil }
        stateObserver?.invalidate(); stateObserver = nil
        player.pause(); player.replaceCurrentItem(with: nil); loadedURL = nil; position = 0; duration = 0; isPlaying = false
    }
}
