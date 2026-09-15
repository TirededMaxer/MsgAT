import SwiftUI
import AVKit

public struct InAppMediaViewer: View {
    let message: ChatMessage
    var onDismiss: () -> Void

    @State private var scale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var settledScale: CGFloat = 1.0
    @State private var settledOffset: CGSize = .zero

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
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button(action: onDismiss) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 26))
                        .foregroundColor(.white.opacity(0.8))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close")
                .keyboardShortcut(.cancelAction)
            }
            .padding(.horizontal, 16)
            .frame(height: 52)

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
                        } else {
                            Text("Failed to load image").foregroundColor(.white)
                        }
                    } else if let url = mediaUrl {
                        NativeMediaPlayer(url: url)
                            .frame(width: geometry.size.width, height: geometry.size.height)
                    } else {
                        Text("Failed to load video").foregroundColor(.white)
                    }
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
                .clipped()
            }
        }
        .background(Color.black)
    }
}

private struct NativeMediaPlayer: NSViewRepresentable {
    let url: URL

    final class Coordinator {
        var loadedURL: URL?
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class PlayerContainer: NSView {
        let playerView = AVPlayerView()

        override init(frame frameRect: NSRect) {
            super.init(frame: frameRect)
            wantsLayer = true
            layer?.masksToBounds = true
            playerView.controlsStyle = .inline
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
        guard context.coordinator.loadedURL != url else { return }
        view.player?.pause()
        view.player?.replaceCurrentItem(with: nil)
        context.coordinator.loadedURL = url
        view.player = AVPlayer(url: url)
        view.player?.play()
    }

    static func dismantleNSView(_ container: PlayerContainer, coordinator: Coordinator) {
        let view = container.playerView
        view.player?.pause()
        view.player?.replaceCurrentItem(with: nil)
        view.player = nil
        coordinator.loadedURL = nil
    }
}
