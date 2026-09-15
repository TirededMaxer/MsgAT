import SwiftUI
import AppKit

public struct CallOverlayView: View {
    @ObservedObject var callManager = CallManager.shared

    @State private var isMinimized = false
    @State private var savedOffset = CGSize(width: -30, height: 40)
    @GestureState private var dragOffset = CGSize.zero

    public init() {}

    private var durationString: String {
        let mins = callManager.callDurationSeconds / 60
        let secs = callManager.callDurationSeconds % 60
        return String(format: "%02d:%02d", mins, secs)
    }

    public var body: some View {
        ZStack {
            if callManager.callState != .idle {
                if isMinimized {
                    // Minimized Floating Box (80x80 rounded squircle)
                    GeometryReader { geo in
                        VStack(spacing: 4) {
                            AvatarView(
                                avatarPath: callManager.currentPeerAvatarPath,
                                fallbackText: callManager.currentPeerAlias,
                                size: 42,
                                cornerRadius: 21
                            )

                            if callManager.callState == .connected {
                                Text(durationString)
                                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                                    .foregroundColor(.green)
                            } else {
                                Text(callManager.callState == .incomingRinging ? "Ringing" : (callManager.callState == .outgoingRinging ? "Calling" : "Connecting"))
                                    .font(.system(size: 10, weight: .medium))
                                    .foregroundColor(.white)
                            }
                        }
                        .frame(width: 82, height: 82)
                        .background(Color(red: 0.1, green: 0.1, blue: 0.1).opacity(0.92))
                        .cornerRadius(18)
                        .overlay(
                            RoundedRectangle(cornerRadius: 18)
                                .stroke(Color.white.opacity(0.25), lineWidth: 1)
                        )
                        .shadow(color: Color.black.opacity(0.4), radius: 10, x: 0, y: 5)
                        .offset(
                            x: clampX(savedOffset.width + dragOffset.width, screenWidth: geo.size.width),
                            y: clampY(savedOffset.height + dragOffset.height, screenHeight: geo.size.height)
                        )
                        .gesture(
                            DragGesture()
                                .updating($dragOffset) { value, state, _ in
                                    state = value.translation
                                }
                                .onEnded { value in
                                    let newX = clampX(savedOffset.width + value.translation.width, screenWidth: geo.size.width)
                                    let newY = clampY(savedOffset.height + value.translation.height, screenHeight: geo.size.height)
                                    savedOffset = CGSize(width: newX, height: newY)
                                }
                        )
                        .onTapGesture {
                            withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                                isMinimized = false
                            }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    }
                    .transition(.asymmetric(
                        insertion: .scale(scale: 0.85).combined(with: .opacity),
                        removal: .scale(scale: 0.85).combined(with: .opacity)
                    ))
                } else {
                    // Fullscreen Call Screen matching Android 0xFF2C2C2C background
                    ZStack {
                        Color(red: 0.17, green: 0.17, blue: 0.17) // 0xFF2C2C2C
                            .ignoresSafeArea()

                        VStack(spacing: 0) {
                            // Top Header Bar
                            HStack {
                                Button(action: {
                                    withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                                        isMinimized = true
                                    }
                                }) {
                                    Image(systemName: "chevron.down")
                                        .font(.system(size: 18, weight: .bold))
                                        .foregroundColor(.white)
                                        .padding(12)
                                        .background(Color.white.opacity(0.12))
                                        .clipShape(Circle())
                                }
                                .buttonStyle(.plain)
                                .help("Minimize to floating box")

                                Spacer()

                                if callManager.callState == .connected {
                                    HStack(spacing: 6) {
                                        Circle()
                                            .fill(callManager.rttMs == 0 ? Color.gray : (callManager.rttMs < 150 ? Color.green : (callManager.rttMs < 300 ? Color.yellow : Color.red)))
                                            .frame(width: 8, height: 8)
                                        Text(callManager.rttMs > 0 ? "\(callManager.rttMs)ms" : "—")
                                            .font(.system(size: 12, weight: .medium, design: .monospaced))
                                            .foregroundColor(.white.opacity(0.8))
                                    }
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 6)
                                    .background(Color.black.opacity(0.35))
                                    .cornerRadius(12)
                                }
                            }
                            .padding(.horizontal, 24)
                            .padding(.top, 20)

                            Spacer()

                            // Center Avatar & Info
                            VStack(spacing: 16) {
                                AvatarView(
                                    avatarPath: callManager.currentPeerAvatarPath,
                                    fallbackText: callManager.currentPeerAlias,
                                    size: 120,
                                    cornerRadius: 60
                                )
                                .overlay(
                                    Circle()
                                        .stroke(Color.white.opacity(0.2), lineWidth: 3)
                                )
                                .shadow(color: Color.black.opacity(0.35), radius: 14)

                                Text(callManager.currentPeerAlias)
                                    .font(.system(size: 28, weight: .bold))
                                    .foregroundColor(.white)

                                Text(statusText)
                                    .font(.system(size: 18, weight: .medium))
                                    .foregroundColor(statusColor)
                            }

                            Spacer()

                            // Bottom Actions
                            if callManager.callState == .incomingRinging {
                                // Incoming Call: Decline (Red) / Accept (Green 0xFF4CAF50)
                                HStack(spacing: 80) {
                                    VStack(spacing: 8) {
                                        Button(action: {
                                            withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) {
                                                callManager.rejectCall()
                                            }
                                        }) {
                                            Image(systemName: "phone.down.fill")
                                                .font(.system(size: 28))
                                                .foregroundColor(.white)
                                                .frame(width: 72, height: 72)
                                                .background(Color.red)
                                                .clipShape(Circle())
                                        }
                                        .buttonStyle(.plain)
                                        Text("Decline")
                                            .font(.caption)
                                            .foregroundColor(.white)
                                    }

                                    VStack(spacing: 8) {
                                        Button(action: {
                                            withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) {
                                                callManager.acceptCall()
                                            }
                                        }) {
                                            Image(systemName: "phone.fill")
                                                .font(.system(size: 28))
                                                .foregroundColor(.white)
                                                .frame(width: 72, height: 72)
                                                .background(Color(red: 0.3, green: 0.69, blue: 0.31)) // 0xFF4CAF50
                                                .clipShape(Circle())
                                        }
                                        .buttonStyle(.plain)
                                        Text("Accept")
                                            .font(.caption)
                                            .foregroundColor(.white)
                                    }
                                }
                                .padding(.bottom, 60)
                            } else {
                                // Desktop audio output follows the system-selected device.
                                VStack(spacing: 30) {
                                    HStack {
                                        Button(action: { callManager.toggleMute() }) {
                                            Image(systemName: callManager.isMuted ? "mic.slash.fill" : "mic.fill")
                                                .font(.system(size: 24))
                                                .foregroundColor(callManager.isMuted ? .black : .white)
                                                .frame(width: 64, height: 64)
                                                .background(callManager.isMuted ? Color.white : Color.white.opacity(0.2))
                                                .clipShape(Circle())
                                        }
                                        .buttonStyle(.plain)
                                        .help(callManager.isMuted ? "Unmute" : "Mute")

                                    }

                                    Button(action: {
                                        withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) {
                                            callManager.endCall()
                                        }
                                    }) {
                                        Image(systemName: "phone.down.fill")
                                            .font(.system(size: 28))
                                            .foregroundColor(.white)
                                            .frame(width: 72, height: 72)
                                            .background(Color.red)
                                            .clipShape(Circle())
                                    }
                                    .buttonStyle(.plain)
                                    .help("End Call")
                                }
                                .padding(.bottom, 50)
                            }
                        }
                    }
                    .transition(.asymmetric(
                        insertion: .move(edge: .bottom).combined(with: .opacity),
                        removal: .move(edge: .bottom).combined(with: .opacity)
                    ))
                }
            }
        }
        .animation(.spring(response: 0.45, dampingFraction: 0.85), value: callManager.callState)
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: isMinimized)
    }

    private var statusText: String {
        switch callManager.callState {
        case .outgoingRinging:
            return "Calling..."
        case .incomingRinging:
            return "Incoming Voice Call..."
        case .connecting:
            return "Connecting..."
        case .connected:
            return durationString
        case .ended:
            return "Call Ended"
        case .idle:
            return ""
        }
    }

    private var statusColor: Color {
        switch callManager.callState {
        case .connected:
            return Color(red: 0.3, green: 0.85, blue: 0.4)
        case .ended:
            return .red
        default:
            return .white.opacity(0.8)
        }
    }

    private func clampX(_ x: CGFloat, screenWidth: CGFloat) -> CGFloat {
        let minX = -(screenWidth - 100)
        let maxX: CGFloat = 0
        return min(max(x, minX), maxX)
    }

    private func clampY(_ y: CGFloat, screenHeight: CGFloat) -> CGFloat {
        let minY: CGFloat = 20
        let maxY = max(20, screenHeight - 120)
        return min(max(y, minY), maxY)
    }
}
