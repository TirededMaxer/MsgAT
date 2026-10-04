import AppKit
import UserNotifications
import AVFoundation

public final class AppDelegate: NSObject, NSApplicationDelegate {

    public func applicationDidFinishLaunching(_ notification: Notification) {
        NotificationHelper.shared.requestAuthorization()
        NSApp.dockTile.badgeLabel = nil

        // Request Microphone Permission for WebRTC Voice Calls
        if #available(macOS 10.14, *) {
            AVCaptureDevice.requestAccess(for: .audio) { granted in
                print("[AppDelegate] Microphone access granted: \(granted)")
            }
        }

        // 2. Wire network packet handler
        NWClient.shared.onPacketReceived = { packet in
            GhostClientHandler.shared.handlePacket(packet)
        }

        // 3. Connect to relay if already configured
        if KeychainManager.shared.isConfigured {
            NWClient.shared.connect()
        }

        // 4. Secure Window Anti-Capture (FLAG_SECURE equivalent on macOS)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            for window in NSApp.windows {
                window.sharingType = .none
            }
        }
    }

    public func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return false
    }

    public func applicationDidBecomeActive(_ notification: Notification) {
        NotificationHelper.shared.clearMessageNotifications()
    }

    public func applicationWillTerminate(_ notification: Notification) {
        CallManager.shared.endCall()
        CallManager.shared.restoreAudioOutput()
    }
}
