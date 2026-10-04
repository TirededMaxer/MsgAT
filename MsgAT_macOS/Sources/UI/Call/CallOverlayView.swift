import SwiftUI
import AppKit

public struct CallOverlayView: View {
    @ObservedObject var callManager = CallManager.shared
    @State private var savedPosition = CGPoint(x: 0, y: 100)
    @GestureState private var dragOffset = CGSize.zero
    public init() {}
    private var durationString: String { String(format: "%02d:%02d", callManager.callDurationSeconds / 60, callManager.callDurationSeconds % 60) }
    public var body: some View {
        GeometryReader { geometry in
            if callManager.callState != .idle {
                ZStack(alignment: .topLeading) {
                if callManager.isMinimized {
                    VStack(spacing: 4) {
                        AvatarView(avatarPath: callManager.currentPeerAvatarPath, fallbackText: callManager.currentPeerAlias, size: 40, cornerRadius: 20)
                        Text(durationString).font(.system(size: 12)).foregroundColor(Color(red: 0, green: 1, blue: 0))
                    }.frame(width: 80, height: 80).background(Color.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
                    .offset(x: clamped(savedPosition.x + dragOffset.width, max: geometry.size.width - 80),
                            y: clamped(savedPosition.y + dragOffset.height, max: geometry.size.height - 80))
                    .gesture(DragGesture().updating($dragOffset) { value, state, _ in state = value.translation }
                        .onEnded { value in
                            let x = clamped(savedPosition.x + value.translation.width, max: geometry.size.width - 80)
                            savedPosition.x = x + 40 > geometry.size.width / 2 ? max(0, geometry.size.width - 80) : 0
                            savedPosition.y = clamped(savedPosition.y + value.translation.height, max: geometry.size.height - 80)
                        })
                    .onTapGesture { callManager.isMinimized = false }
                    .transition(.opacity.combined(with: .scale(scale: 0.8)))
                } else {
                    ZStack(alignment: .top) {
                        Color(white: 44/255).ignoresSafeArea()
                        VStack(spacing: 0) {
                            Spacer().frame(height: min(100, max(24, (geometry.size.height - 440) / 2)))
                            AvatarView(avatarPath: callManager.currentPeerAvatarPath, fallbackText: callManager.currentPeerAlias, size: 120, cornerRadius: 60)
                            Text(callManager.currentPeerAlias).font(.system(size: 28)).foregroundColor(.white).padding(.top, 24)
                            Text(statusText).font(.system(size: callManager.callState == .incomingRinging ? 16 : 18))
                                .foregroundColor(callManager.callState == .ended ? .red : callManager.callState == .incomingRinging ? .gray : Color(white: 192/255))
                                .padding(.top, 8)
                            Spacer(minLength: 16)
                            if callManager.callState == .incomingRinging {
                                HStack {
                                    Spacer()
                                    VStack(spacing: 8) {
                                        callButton("phone.down.fill", label: "Reject", size: 72, active: false, color: .red) { callManager.rejectCall() }
                                        Text("Decline").foregroundColor(.white).font(.system(size: 16))
                                    }
                                    Spacer()
                                    VStack(spacing: 8) {
                                        callButton("phone.fill", label: "Accept", size: 72, active: false, color: Color(red: 76/255, green: 175/255, blue: 80/255)) { callManager.acceptCall() }
                                        Text("Accept").foregroundColor(.white).font(.system(size: 16))
                                    }
                                    Spacer()
                                }
                            } else {
                                HStack {
                                    Spacer()
                                    callButton(callManager.isMuted ? "mic.slash.fill" : "mic.fill", label: callManager.isMuted ? "Unmute" : "Mute", size: 64, active: callManager.isMuted) { callManager.toggleMute() }
                                    Spacer()
                                    callButton("speaker.wave.2.fill", label: callManager.isSpeakerOn ? "Turn speaker off" : "Turn speaker on", size: 64, active: callManager.isSpeakerOn) { callManager.toggleSpeaker() }
                                    Spacer()
                                }
                                callButton("phone.down.fill", label: "End Call", size: 72, active: false, color: .red) { callManager.endCall() }.padding(.top, 40)
                            }
                            Spacer().frame(height: 60)
                        }
                        if callManager.callState != .incomingRinging {
                            HStack {
                                Button {
                                    savedPosition = CGPoint(x: max(0, geometry.size.width - 80), y: min(100, max(0, geometry.size.height - 80)))
                                    callManager.isMinimized = true
                                } label: { Image(systemName: "chevron.down").font(.system(size: 32)).foregroundColor(.white).frame(width: 48, height: 48) }
                                .buttonStyle(.plain).help("Minimize")
                                Spacer()
                                if callManager.callState == .connected {
                                    HStack(spacing: 4) {
                                        Circle().fill(qualityColor).frame(width: 8, height: 8)
                                        Text(callManager.rttMs > 0 ? "\(callManager.rttMs)ms" : "—").font(.system(size: 12)).foregroundColor(.white)
                                    }
                                }
                            }.padding(16)
                        }
                    }
                    .transition(.opacity.combined(with: .scale(scale: 0.8)))
                }
                }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: callManager.isMinimized)
        .animation(.easeInOut(duration: 0.2), value: callManager.callState != .idle)
        .onChange(of: callManager.callState) { state in if state == .idle || state == .incomingRinging { callManager.isMinimized = false } }
    }
    private func callButton(_ icon: String, label: String, size: CGFloat, active: Bool, color: Color? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: size / 2)).foregroundColor(active ? .black : .white)
                .frame(width: size, height: size).background(color ?? (active ? Color.white : Color.white.opacity(0.2)), in: Circle())
        }.buttonStyle(.plain).help(label).accessibilityLabel(label)
    }
    private var qualityColor: Color { callManager.rttMs <= 0 ? .gray : callManager.rttMs < 150 ? Color(red: 0, green: 1, blue: 0) : callManager.rttMs < 300 ? .yellow : .red }
    private var statusText: String { callManager.callState == .connected ? durationString : callManager.callState == .idle ? "" : callManager.callState.rawValue }
    private func clamped(_ value: CGFloat, max maximum: CGFloat) -> CGFloat { min(max(0, value), max(0, maximum)) }
}
