// SPDX-License-Identifier: AGPL-3.0-only
import AppKit
import LocalAuthentication
import SwiftUI

@MainActor
final class AppLockSession: ObservableObject {
    enum State { case loading, disabled, locked, unlocked, unavailable }
    private enum Purpose { case unlock, enable, disable }

    static let shared = AppLockSession()
    static let changed = Notification.Name("MsgAT.AppLockChanged")

    @Published private(set) var state: State = .loading
    @Published private(set) var authenticating = false
    @Published private(set) var errorKey: String?
    private var context: LAContext?
    private var awaySince: TimeInterval?
    private var deadline: Task<Void, Never>?

    var enabled: Bool { state == .locked || state == .unlocked }
    var canShowContent: Bool { state == .disabled || state == .unlocked }

    private init() {}

    func load() {
        guard state == .loading else { return }
        do {
            let enabled = try !AppDatabase.shared.query(
                "SELECT 1 FROM privacy_preferences WHERE id=1 AND app_lock_enabled=1",
                map: { _ in true }).isEmpty
            setState(enabled ? .locked : .disabled)
        } catch {
            errorKey = "app_lock_read_error"
            setState(.unavailable)
        }
    }

    func updatePresence(_ foreground: Bool) {
        let now = ProcessInfo.processInfo.systemUptime
        if foreground {
            let elapsed = awaySince.map { now - $0 } ?? 0
            awaySince = nil
            deadline?.cancel()
            deadline = nil
            if elapsed >= 60 { lockNow() }
        } else {
            if awaySince == nil { awaySince = now }
            guard state == .unlocked, deadline == nil, let awaySince else { return }
            let remaining = max(0, 60 - (now - awaySince))
            deadline = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(remaining))
                guard !Task.isCancelled, let self, self.awaySince != nil else { return }
                self.deadline = nil
                self.lockNow()
            }
        }
    }

    func lockNow() {
        let pending = context
        context = nil
        pending?.invalidate()
        authenticating = false
        guard enabled else { return }
        deadline?.cancel()
        deadline = nil
        errorKey = nil
        setState(.locked)
    }

    func toggle() {
        guard canShowContent else { return }
        authenticate(enabled ? .disable : .enable)
    }

    func unlock() {
        guard state == .locked else { return }
        authenticate(.unlock)
    }

    private func authenticate(_ purpose: Purpose) {
        guard !authenticating, NSApp.isActive else { return }
        let request = LAContext()
        request.touchIDAuthenticationAllowableReuseDuration = 0
        guard request.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else {
            showError("mac_auth_unavailable")
            return
        }
        errorKey = nil
        context = request
        authenticating = true
        Task { @MainActor in
            let verified = (try? await request.evaluatePolicy(.deviceOwnerAuthentication,
                localizedReason: InterfaceStrings.current()("verify_app"))) == true
            guard context === request else { return }
            context = nil
            authenticating = false
            guard verified else {
                showError("verify_incomplete")
                return
            }
            if purpose == .unlock {
                setState(.unlocked)
                return
            }
            let enable = purpose == .enable
            do {
                try AppDatabase.shared.execute("UPDATE privacy_preferences SET app_lock_enabled=? WHERE id=1",
                                               values: [.integer(enable ? 1 : 0)])
                if enable { NotificationHelper.shared.clearMessageNotifications(onlyWhenActive: false) }
                setState(enable ? .unlocked : .disabled)
            } catch {
                showError("app_lock_save_error")
            }
        }
    }

    private func setState(_ value: State) {
        guard state != value else { return }
        state = value
        if !canShowContent { AttachmentExportPanel.cancelActive() }
        NotificationCenter.default.post(name: Self.changed, object: nil)
    }

    private func showError(_ key: String) {
        if canShowContent { NoticeCenter.shared.show(InterfaceStrings.current()(key)) }
        else { errorKey = key }
    }
}

@MainActor
struct AppLockRoot: View {
    @ObservedObject private var lock = AppLockSession.shared
    @Environment(\.openWindow) private var openWindow
    @Environment(\.interfaceStrings) private var interfaceText

    var body: some View {
        Group {
            if lock.canShowContent {
                MainWindow()
            } else if lock.state == .loading {
                ProgressView()
            } else {
                VStack(spacing: 14) {
                    Label(interfaceText("app_locked"), systemImage: "lock.fill").font(.title2)
                    if lock.state == .locked {
                        Button(interfaceText("unlock"), action: lock.unlock).keyboardShortcut(.defaultAction)
                            .disabled(lock.authenticating)
                    }
                    if let error = lock.errorKey { Text(interfaceText(error)).foregroundStyle(.secondary) }
                }.padding(28)
            }
        }
        .frame(minWidth: 800, minHeight: 500)
        .background(MainWindowHost().allowsHitTesting(false))
        .onAppear {
            let open = openWindow
            NotificationHelper.openMainWindow = {
                if let window = NotificationHelper.mainWindow, window.isVisible || window.isMiniaturized {
                    window.deminiaturize(nil)
                    window.makeKeyAndOrderFront(nil)
                } else { open(id: "main") }
            }
            lock.load()
        }
    }
}
