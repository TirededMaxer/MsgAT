import AppKit
import UserNotifications
import AVFoundation

@MainActor
public final class AppDelegate: NSObject, NSApplicationDelegate {
    private(set) static weak var running: AppDelegate?
    private var activityObservers=[NSObjectProtocol]()
    private var workspaceObservers = [NSObjectProtocol]()
    private var drainingTermination=false
    private var terminationDrained=false
    private var awaitingTerminationReply = false
    private var requestedMicrophone = false
    private func updateActivityForeground(){
        guard !drainingTermination,KeychainManager.startupFailure == nil,AppDatabase.startupFailure == nil else {return}
        let visible=NSApp.windows.contains{$0.isVisible && !$0.isMiniaturized && $0.level == .normal}
        let present = NSApp.isActive && visible && !NSApp.isHidden
        AppLockSession.shared.updatePresence(present)
        let foreground = present && AppLockSession.shared.canShowContent
        SecureMessaging.shared.setForeground(foreground)
        LiveLocationCoordinator.shared.setForeground(foreground)
        if !foreground { CallManager.shared.pauseVideoCamera() }
        if foreground && !requestedMicrophone {
            requestedMicrophone = true
            AVCaptureDevice.requestAccess(for: .audio) { _ in }
        }
    }


    public func applicationDidFinishLaunching(_ notification: Notification) {
        Self.running = self
        guard KeychainManager.startupFailure == nil,AppDatabase.startupFailure == nil else { return }
        AppLockSession.shared.load()
        IdentityRevocationWorker.shared.start()
        NotificationHelper.shared.requestAuthorization()
        NSApp.dockTile.badgeLabel = nil

        // 2. Wire network packet handler
        NWClient.shared.onPacketReceived = { packet, epoch in
            GhostClientHandler.shared.handlePacket(packet, epoch: epoch)
        }

        for name in [NSWindow.didMiniaturizeNotification,NSWindow.didDeminiaturizeNotification,NSWindow.didBecomeKeyNotification,NSWindow.willCloseNotification,AppLockSession.changed] {
            activityObservers.append(NotificationCenter.default.addObserver(forName:name,object:nil,queue:.main){[weak self] _ in
                DispatchQueue.main.async{self?.updateActivityForeground()}
            })
        }
        for name in [NSWorkspace.willSleepNotification, NSWorkspace.screensDidSleepNotification,
                     NSWorkspace.sessionDidResignActiveNotification] {
            workspaceObservers.append(NSWorkspace.shared.notificationCenter.addObserver(forName: name, object: nil, queue: .main) { _ in
                DispatchQueue.main.async { AppLockSession.shared.lockNow() }
            })
        }
        updateActivityForeground()

        // 3. Connect to relay if already configured
        if KeychainManager.shared.isConfigured {
            NWClient.shared.connect()
        }

        // Legacy sharing preference only; it does not block modern screen capture.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            for window in NSApp.windows {
                window.sharingType = .none
            }
        }
    }

    public func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return false
    }

    public func applicationShouldTerminate(_ sender:NSApplication)->NSApplication.TerminateReply {
        guard KeychainManager.startupFailure == nil,AppDatabase.startupFailure == nil,!terminationDrained else{return .terminateNow}
        awaitingTerminationReply = true
        drainForTermination(sender)
        return .terminateLater
    }

    func quitAfterIdentityReset() {
        // Finish asynchronous owners before AppKit can enter its nested termination loop.
        drainForTermination(NSApp)
    }

    private func drainForTermination(_ sender: NSApplication) {
        guard !drainingTermination else { return }
        drainingTermination=true
        activityObservers.forEach(NotificationCenter.default.removeObserver)
        activityObservers.removeAll()
        workspaceObservers.forEach(NSWorkspace.shared.notificationCenter.removeObserver)
        workspaceObservers.removeAll()
        Task { @MainActor in
            WebPreviewFetcher.stop()
            AttachmentExportPanel.cancelActive()
            await LiveLocationCoordinator.shared.close()
            await CallManager.shared.endCallAndDrainVideo()
            CallManager.shared.restoreAudioOutput()
            await AttachmentExportPanel.stop()

            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                DispatchQueue.global(qos: .userInitiated).async {
                    NWClient.shared.stopForTermination()
                    SecureMessaging.shared.shutdown()
                    IdentityRevocationWorker.shared.close()
                    EncryptedMediaWork.stop()
                    ContactDirectoryStore.close()
                    StickerLibraryStore.shared.close()
                    MessageActionsStore.shared.close()
                    do { try ConversationDraftStore.shared.close() }
                    catch { NSLog("Draft flush failed (%@)", String(describing: type(of: error))) }
                    continuation.resume()
                }
            }
            do {
                try await EncryptedCaptureStore.shared.close()
            } catch {
                NSLog("Recording shutdown failed (%@)", String(describing: type(of: error)))
            }
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                DispatchQueue.global(qos: .userInitiated).async {
                    AppDatabase.shared.closeForTermination()
                    continuation.resume()
                }
            }
            terminationDrained=true
            if awaitingTerminationReply {
                sender.reply(toApplicationShouldTerminate: true)
            } else {
                sender.terminate(nil)
            }
        }
    }

    public func applicationDidBecomeActive(_ notification: Notification) {
        NotificationHelper.shared.clearMessageNotifications()
        updateActivityForeground()
    }

    public func applicationDidHide(_ notification:Notification){updateActivityForeground()}
    public func applicationDidUnhide(_ notification:Notification){updateActivityForeground()}

    public func applicationDidResignActive(_ notification: Notification) {
        if !drainingTermination, KeychainManager.startupFailure == nil, AppDatabase.startupFailure == nil {
            AppLockSession.shared.updatePresence(false)
            SecureMessaging.shared.setForeground(false)
            LiveLocationCoordinator.shared.setForeground(false)
            CallManager.shared.pauseVideoCamera()
        }
    }

}
