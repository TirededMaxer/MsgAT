import SwiftUI
import AppKit
import CoreAudio

private enum CallColors {
    static let surface = Color(red: 34 / 255.0, green: 43 / 255.0, blue: 39 / 255.0)
    static let background = Color(red: 23 / 255.0, green: 30 / 255.0, blue: 27 / 255.0)
    static let controls = Color(red: 53 / 255.0, green: 71 / 255.0, blue: 61 / 255.0)
    static let selected = Color(red: 189 / 255.0, green: 231 / 255.0, blue: 205 / 255.0)
    static let secondary = Color(red: 169 / 255.0, green: 189 / 255.0, blue: 179 / 255.0)
    static let accept = Color(red: 18 / 255.0, green: 103 / 255.0, blue: 65 / 255.0)
    static let decline = Color(red: 173 / 255.0, green: 53 / 255.0, blue: 67 / 255.0)
}

public struct CallOverlayView: View {
    @ObservedObject var callManager = CallManager.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.interfaceStrings) private var interfaceText
    @State private var compactCorner = CallPreviewCorner.topRight
    public init() {}
    public var body: some View {
        GeometryReader { geometry in
            if callManager.callState != .idle {
                ZStack(alignment: .topLeading) {
                if callManager.isMinimized {
                    DockedCallPreview(bounds: geometry.size, size: CGSize(width: 158, height: 70), corner: $compactCorner) {
                        Button { callManager.isMinimized = false } label: {
                            HStack(spacing: 8) {
                                AvatarView(avatarPath: callManager.currentPeerAvatarPath,
                                           fallbackText: callManager.currentPeerAlias, size: 34, cornerRadius: 17)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(callManager.currentPeerAlias).font(.caption.weight(.semibold))
                                        .foregroundStyle(.white).lineLimit(1)
                                    HStack(spacing: 4) {
                                        Text(statusText).lineLimit(1)
                                        if callManager.isMuted {
                                            Image(systemName: "mic.slash.fill").font(.system(size: 11))
                                        }
                                    }
                                    .font(.caption).foregroundStyle(CallColors.secondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .padding(.horizontal, 10)
                            .frame(width: 158, height: 70)
                            .background(CallColors.surface, in: RoundedRectangle(cornerRadius: 14))
                            .overlay {
                                RoundedRectangle(cornerRadius: 14).strokeBorder(CallColors.controls)
                                    .allowsHitTesting(false)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .help(interfaceText("restore_call"))
                        .accessibilityLabel(interfaceText("restore_call"))
                        .accessibilityValue(compactStatus)
                    }
                    .transition(.opacity.combined(with: .scale(scale: 0.8)))
                } else if let session = callManager.videoSession {
                    VideoCallStage(session: session, call: callManager, status: statusText) {
                        callManager.isMinimized = true
                    }
                    .transition(.opacity.combined(with: .scale(scale: 0.8)))
                } else {
                    let compact = geometry.size.height < 480
                    let avatarSize: CGFloat = compact ? 64 : 104
                    let actionSize: CGFloat = compact ? 46 : 60
                    ZStack(alignment: .top) {
                        LinearGradient(colors: [CallColors.surface, CallColors.background],
                                       startPoint: .top, endPoint: .bottom).ignoresSafeArea()
                        VStack(spacing: 0) {
                            Spacer().frame(height: compact ? 40 : 76)
                            AvatarView(avatarPath: callManager.currentPeerAvatarPath, fallbackText: callManager.currentPeerAlias,
                                       size: avatarSize, cornerRadius: avatarSize / 2)
                            Text(callManager.currentPeerAlias).font(.system(size: compact ? 22 : 28, weight: .semibold))
                                .foregroundStyle(.white).lineLimit(1).padding(.top, compact ? 8 : 14)
                            Text(statusText).font(.system(size: 16))
                                .foregroundStyle(callManager.callState == .ended ? Color(red: 1, green: 0.67, blue: 0.69) : CallColors.secondary)
                                .multilineTextAlignment(.center).padding(.top, 5)
                            Spacer(minLength: 8)
                            if callManager.callState == .incomingRinging {
                                HStack(spacing: 16) {
                                    incomingAction("phone.down.fill", label: interfaceText("reject_call"),
                                                   size: actionSize, color: CallColors.decline) { callManager.rejectCall() }
                                    incomingAction(callManager.isVideoCall ? "video.fill" : "phone.fill",
                                                   label: interfaceText("accept_call"), size: actionSize,
                                                   color: CallColors.accept) { callManager.acceptCall() }
                                }
                            } else {
                                HStack {
                                    Spacer()
                                    callButton(callManager.isMuted ? "mic.slash.fill" : "mic.fill",
                                               label: interfaceText(callManager.isMuted ? "unmute" : "mute"), size: actionSize,
                                               active: callManager.isMuted) { callManager.toggleMute() }
                                    Spacer()
                                    callButton("speaker.wave.2.fill", label: interfaceText(callManager.isSpeakerOn ? "call_speaker_off" : "call_speaker_on"),
                                               size: actionSize, active: callManager.isSpeakerOn) { callManager.toggleSpeaker() }
                                    Spacer()
                                    CallAudioDeviceMenu(call: callManager)
                                    Spacer()
                                }
                                callButton("phone.down.fill", label: interfaceText("end_call"), size: actionSize,
                                           active: false, color: CallColors.decline) {
                                    callManager.endCall()
                                }.padding(.top, compact ? 12 : 22)
                            }
                            Spacer().frame(height: compact ? 12 : 28)
                        }
                        .padding(.horizontal, 16)
                        .frame(maxWidth: 440)
                        if callManager.callState != .incomingRinging {
                            HStack {
                                Button {
                                    callManager.isMinimized = true
                                } label: { Image(systemName: "chevron.down").font(.system(size: 32)).foregroundColor(.white).frame(width: 48, height: 48) }
                                .buttonStyle(.plain)
                                .help(interfaceText("call_minimize")).accessibilityLabel(interfaceText("call_minimize"))
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
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.8), value: callManager.isMinimized)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: callManager.callState != .idle)
        .onChange(of: callManager.callState) { state in
            if state == .idle || state == .incomingRinging {
                callManager.isMinimized = false
                compactCorner = .topRight
            }
        }
    }
    private func incomingAction(_ icon: String, label: String, size: CGFloat, color: Color,
                                action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon).font(.system(size: 24))
                    .frame(width: size, height: size).background(color, in: Circle())
                Text(label).font(.callout.weight(.medium)).multilineTextAlignment(.center)
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func callButton(_ icon: String, label: String, size: CGFloat, active: Bool, color: Color? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: size / 2)).foregroundColor(active ? .black : .white)
                .frame(width: size, height: size)
                .background(color ?? (active ? CallColors.selected : CallColors.controls), in: Circle())
        }.buttonStyle(.plain).help(label).accessibilityLabel(label)
    }
    private var qualityColor: Color { callManager.rttMs <= 0 ? .gray : callManager.rttMs < 150 ? Color(red: 0, green: 1, blue: 0) : callManager.rttMs < 300 ? .yellow : .red }
    private var compactStatus: String {
        var details = [callManager.currentPeerAlias, statusText]
        if callManager.isMuted { details.append(interfaceText("call_microphone_muted")) }
        return details.filter { !$0.isEmpty }.joined(separator: ", ")
    }

    private var statusText: String {
        callStatus(callManager, strings: interfaceText)
    }
}

private func callStatus(_ call: CallManager, strings: InterfaceStrings) -> String {
    switch call.callState {
    case .connected:
        call.isRecoveringConnection ? strings("call_reconnecting") :
            String(format: "%02d:%02d", call.callDurationSeconds / 60, call.callDurationSeconds % 60)
    case .idle: ""
    case .incomingRinging: strings(call.isVideoCall ? "incoming_video_call" : "incoming_voice_call")
    case .outgoingRinging: strings(call.isVideoCall ? "video_calling" : "calling")
    case .connecting: strings("call_connecting")
    case .ended:
        switch call.endedOutcome {
        case .completed: strings("call_ended")
        case .missed: strings("call_outcome_missed")
        case .declined: strings("call_outcome_declined")
        case .busy: strings("call_outcome_busy")
        case .canceled: strings("call_outcome_canceled")
        case .failed: strings("call_outcome_failed")
        }
    }
}

@MainActor
struct ModalCallLayer: View {
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var presenter = ModalCallPresenter()
    @ObservedObject private var call = CallManager.shared
    @Environment(\.interfaceStrings) private var strings

    var body: some View {
        Color.clear
            .onAppear {
                presenter.setColorScheme(colorScheme)
                presenter.start(strings: strings)
            }
            .onChange(of: colorScheme) { presenter.setColorScheme($0) }
            .onDisappear { presenter.stop() }
            .onReceive(call.$callState) { _ in presenter.refreshSoon(strings: strings) }
            .onReceive(NotificationCenter.default.publisher(for: NSWindow.willBeginSheetNotification)) { _ in
                presenter.refreshSoon(strings: strings)
            }
            .onReceive(NotificationCenter.default.publisher(for: NSWindow.didEndSheetNotification)) { _ in
                presenter.refreshSoon(strings: strings)
            }
            .onReceive(NotificationCenter.default.publisher(for: NSWindow.didBecomeKeyNotification)) { _ in
                presenter.refreshSoon(strings: strings)
            }
            .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
                presenter.refreshSoon(strings: strings)
            }
            .onReceive(NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)) { _ in
                presenter.close()
            }
            .onReceive(NotificationCenter.default.publisher(for: NotificationHelper.callFocusRequested)) { event in
                if let id = event.object as? String { presenter.focusIncomingCall(id, strings: strings) }
            }
            .onReceive(NotificationCenter.default.publisher(for: AppLockSession.changed)) { _ in
                if !AppLockSession.shared.canShowContent { presenter.close() }
            }
    }
}

@MainActor
private final class ModalCallPresenter: NSObject, NSWindowDelegate, ObservableObject {
    private var mounted = false
    private var panel: NSPanel?
    private var panelCallID: String?
    private var eligibleCallID: String?
    private var dismissedCallID: String?
    private var appearance: NSAppearance?

    func setColorScheme(_ colorScheme: ColorScheme) {
        appearance = NSAppearance(named: colorScheme == .dark ? .darkAqua : .aqua)
        panel?.appearance = appearance
    }

    func start(strings: InterfaceStrings) {
        mounted = true
        refreshSoon(strings: strings)
    }

    func stop() {
        mounted = false
        close()
    }

    func refreshSoon(strings: InterfaceStrings) {
        DispatchQueue.main.async { [weak self] in self?.refresh(strings: strings) }
    }

    func focusIncomingCall(_ id: String, strings: InterfaceStrings) {
        DispatchQueue.main.async { [weak self] in
            guard let self, CallManager.shared.currentCallId == id,
                  CallManager.shared.callState == .incomingRinging else { return }
            self.dismissedCallID = nil
            self.refresh(strings: strings)
            if self.panelCallID == id { self.panel?.makeKeyAndOrderFront(nil) }
        }
    }

    private func refresh(strings: InterfaceStrings) {
        let call = CallManager.shared
        guard mounted, NSApp.isActive, AppLockSession.shared.canShowContent,
              let id = call.currentCallId, call.callState != .idle, call.callState != .ended,
              id != dismissedCallID,
              let main = NotificationHelper.mainWindow, main.isVisible,
              let owner = modalOwner(in: main) else {
            close()
            return
        }

        if call.callState == .incomingRinging { eligibleCallID = id }
        guard eligibleCallID == id else { close(); return }

        if let panel, panelCallID == id {
            if panel.parent !== owner {
                panel.parent?.removeChildWindow(panel)
                owner.addChildWindow(panel, ordered: .above)
                place(panel, relativeTo: owner)
            }
            return
        }

        close()
        let window = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 350, height: 138),
                             styleMask: [.titled, .closable, .nonactivatingPanel], backing: .buffered, defer: false)
        window.title = "MsgAT"
        window.isReleasedWhenClosed = false
        window.isFloatingPanel = true
        window.becomesKeyOnlyIfNeeded = true
        window.worksWhenModal = true
        window.hidesOnDeactivate = true
        window.sharingType = .none
        window.appearance = appearance
        window.delegate = self
        window.contentView = NSHostingView(rootView: ModalCallActions(callID: id, strings: strings))
        panelCallID = id
        panel = window
        place(window, relativeTo: owner)
        owner.addChildWindow(window, ordered: .above)
        window.orderFront(nil)
    }

    private func modalOwner(in main: NSWindow) -> NSWindow? {
        if let modal = NSApp.modalWindow, modal !== main, modal !== panel, modal.isVisible { return modal }
        guard var sheet = main.attachedSheet, sheet.isVisible else { return nil }
        while let next = sheet.attachedSheet, next.isVisible { sheet = next }
        return sheet
    }

    private func place(_ window: NSPanel, relativeTo owner: NSWindow) {
        let screen = owner.screen?.visibleFrame ?? owner.frame
        let right = owner.frame.maxX + 12
        let left = owner.frame.minX - window.frame.width - 12
        let rightLimit = screen.maxX - window.frame.width
        let x = right <= rightLimit ? right : (left >= screen.minX ? left : max(screen.minX, rightLimit))
        let y = min(screen.maxY, max(screen.minY + window.frame.height, owner.frame.maxY))
        window.setFrameTopLeftPoint(NSPoint(x: x, y: y))
    }

    func close() {
        guard let window = panel else { return }
        panel = nil
        panelCallID = nil
        window.parent?.removeChildWindow(window)
        window.close()
    }

    func windowWillClose(_ notification: Notification) {
        guard let window = notification.object as? NSPanel, window === panel else { return }
        panel = nil
        panelCallID = nil
        window.parent?.removeChildWindow(window)
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        if sender === panel { dismissedCallID = panelCallID }
        return true
    }
}

@MainActor
private struct ModalCallActions: View {
    let callID: String
    let strings: InterfaceStrings
    @ObservedObject private var call = CallManager.shared
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                AvatarView(avatarPath: call.currentPeerAvatarPath, fallbackText: call.currentPeerAlias,
                           size: 42, cornerRadius: 21)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(call.currentPeerAlias).font(.headline).lineLimit(1)
                    Text(callStatus(call, strings: strings)).font(.callout).lineLimit(2)
                        .foregroundStyle(.secondary)
                }
            }
            Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
            HStack(spacing: 8) {
                Spacer(minLength: 0)
                if call.callState == .incomingRinging {
                    Button { act(when: .incomingRinging, call.rejectCall) } label: {
                        Label(strings("reject_call"), systemImage: "phone.down")
                    }
                    Button { act(when: .incomingRinging, call.acceptCall) } label: {
                        Label(strings("accept_call"), systemImage: call.isVideoCall ? "video.fill" : "phone.fill")
                    }
                        .buttonStyle(.borderedProminent)
                        .tint(CallColors.accept)
                } else {
                    Button(strings(call.isMuted ? "unmute" : "mute")) { act(when: .connected, call.toggleMute) }
                        .disabled(call.callState != .connected)
                    Button(strings("end_call"), role: .destructive) {
                        if call.callState == .connecting || call.callState == .connected, current { call.endCall() }
                    }
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(colors.chrome)
    }

    private var current: Bool { AppLockSession.shared.canShowContent && call.currentCallId == callID }

    private func act(when state: CallState, _ action: () -> Void) {
        guard current, call.callState == state else { return }
        action()
    }
}

private struct CallPreviewCorner: Equatable {
    let right: Bool
    let bottom: Bool

    static let topRight = Self(right: true, bottom: false)
    static let bottomRight = Self(right: true, bottom: true)
}

private struct DockedCallPreview<Content: View>: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @GestureState private var tracking = false
    @State private var draggedPosition: CGPoint?
    let bounds: CGSize
    let size: CGSize
    @Binding var corner: CallPreviewCorner
    @ViewBuilder let content: () -> Content

    init(bounds: CGSize, size: CGSize, corner: Binding<CallPreviewCorner>, @ViewBuilder content: @escaping () -> Content) {
        self.bounds = bounds
        self.size = size
        _corner = corner
        self.content = content
    }

    var body: some View {
        let maxX = max(0, bounds.width - size.width)
        let maxY = max(0, bounds.height - size.height)
        let insetX = min(12, maxX / 2)
        let insetY = min(12, maxY / 2)
        let docked = CGPoint(x: corner.right ? maxX - insetX : insetX,
                             y: corner.bottom ? maxY - insetY : insetY)
        let position = draggedPosition ?? docked
        content()
            .frame(width: size.width, height: size.height)
            .offset(x: position.x, y: position.y)
            .highPriorityGesture(DragGesture(minimumDistance: 4, coordinateSpace: .global)
                .updating($tracking) { _, state, _ in state = true }
                .onChanged { value in
                    draggedPosition = CGPoint(
                        x: min(maxX, max(0, docked.x + value.translation.width)),
                        y: min(maxY, max(0, docked.y + value.translation.height))
                    )
                }
                .onEnded { _ in
                    guard let draggedPosition else { return }
                    withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.88)) {
                        corner = CallPreviewCorner(right: draggedPosition.x >= maxX / 2,
                                                   bottom: draggedPosition.y >= maxY / 2)
                        self.draggedPosition = nil
                    }
                })
            .onChange(of: tracking) { active in
                if !active { draggedPosition = nil }
            }
    }
}

@MainActor
private struct VideoCallStage: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @ObservedObject var session: CallVideoSession
    @ObservedObject var call: CallManager
    let status: String
    let minimize: () -> Void
    @State private var cameraCorner = CallPreviewCorner.bottomRight

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Button(action: minimize) {
                    Image(systemName: "chevron.down").frame(width: 24, height: 24)
                }.buttonStyle(.borderless)
                    .help(interfaceText("call_minimize")).accessibilityLabel(interfaceText("call_minimize"))
                VStack(alignment: .leading, spacing: 2) {
                    Text(call.currentPeerAlias).font(.headline).lineLimit(1)
                    Text(status).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
            }
            GeometryReader { geometry in
                ZStack(alignment: .topLeading) {
                    Color.black
                    if let track = session.remoteTrack {
                        CallVideoView(track: track).accessibilityLabel(interfaceText("call_remote_video"))
                    } else {
                        Text(interfaceText("call_waiting_video")).foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    if session.cameraEnabled {
                        let width = min(156, max(1, min(geometry.size.width * 0.45, geometry.size.height * 0.45 * 156 / 112)))
                        DockedCallPreview(bounds: geometry.size, size: CGSize(width: width, height: width * 112 / 156),
                                          corner: $cameraCorner) {
                            CallVideoView(track: session.localTrack, mirrored: true)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                                .contentShape(Rectangle())
                                .accessibilityLabel(interfaceText("call_camera_preview"))
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            if let key = session.cameraErrorKey {
                Text(interfaceText(key)).font(.callout).foregroundStyle(.secondary)
            }
            HStack(spacing: 10) {
                control(call.isMuted ? "mic.slash.fill" : "mic.fill", interfaceText(call.isMuted ? "unmute" : "mute"), call.toggleMute)
                control("speaker.wave.2.fill", interfaceText(call.isSpeakerOn ? "call_speaker_off" : "call_speaker_on"), call.toggleSpeaker)
                CallAudioDeviceMenu(call: call)
                control(session.cameraEnabled ? "video.fill" : "video.slash",
                        interfaceText(session.cameraEnabled ? "call_camera_off" : "call_camera_on")) {
                    if session.cameraEnabled { session.disableCamera() }
                    else { session.enableCamera() }
                }.disabled(session.cameraBusy || call.callState != .connected)
                if session.cameraEnabled {
                    Menu {
                        ForEach(session.devices, id: \.uniqueID) { camera in
                            Button {
                                session.enableCamera(deviceID: camera.uniqueID)
                            } label: {
                                if camera.uniqueID == session.selectedDevice { Label(camera.localizedName, systemImage: "checkmark") }
                                else { Text(camera.localizedName) }
                            }
                        }
                    } label: {
                        Image(systemName: "arrow.triangle.2.circlepath").frame(width: 20, height: 22)
                    }.menuStyle(.borderlessButton).fixedSize()
                        .help(interfaceText("call_switch_camera")).accessibilityLabel(interfaceText("call_switch_camera"))
                        .disabled(session.cameraBusy)
                }
                control("phone.down.fill", interfaceText("end_call"), call.endCall).tint(CallColors.decline)
            }
        }
        .padding(16)
        .foregroundStyle(.white)
        .background(CallColors.surface)
        .environment(\.colorScheme, .dark)
    }

    private func control(_ icon: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).frame(width: 30, height: 24)
        }.buttonStyle(.bordered).help(label).accessibilityLabel(label)
    }
}

private struct CallAudioDeviceMenu: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @ObservedObject var call: CallManager

    var body: some View {
        Menu {
            Section(interfaceText("recording_microphone")) {
                option(interfaceText("call_system_default"), id: nil, input: true)
                ForEach(call.microphones) { option($0.name, id: $0.id, input: true) }
            }
            Section(interfaceText("call_audio_output")) {
                option(interfaceText("call_system_default"), id: nil, input: false)
                ForEach(call.outputs) { option($0.name, id: $0.id, input: false) }
            }
        } label: {
            Image(systemName: "headphones").font(.system(size: 18)).frame(width: 24, height: 24)
        }
        .menuStyle(.borderlessButton).fixedSize()
        .help(interfaceText("call_audio_devices")).accessibilityLabel(interfaceText("call_audio_devices"))
        .disabled(call.callState != .connected || call.isSwitchingAudio)
    }

    private func option(_ title: String, id: AudioDeviceID?, input: Bool) -> some View {
        let selected = input ? call.audioSelection.input : call.audioSelection.output
        return Button {
            call.chooseAudioDevice(id, input: input)
        } label: {
            if selected == id { Label(title, systemImage: "checkmark") } else { Text(title) }
        }
    }
}
