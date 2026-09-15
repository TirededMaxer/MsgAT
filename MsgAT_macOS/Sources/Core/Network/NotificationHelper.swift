import Foundation
import UserNotifications
import AppKit

public final class NotificationHelper: NSObject, UNUserNotificationCenterDelegate, Sendable {
    public static let shared = NotificationHelper()

    private override init() {
        super.init()
        UNUserNotificationCenter.current().delegate = self
    }

    public func requestAuthorization() {
        // Only request alert and sound - NO badge / NO red dot on app icon
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
            if let error = error {
                print("[NotificationHelper] Authorization error: \(error)")
            }
        }
        DispatchQueue.main.async {
            NSApp.dockTile.badgeLabel = nil
        }
    }

    public func showMessageNotification(senderUuid: String, senderAlias: String, ext: String, content: String) {
        let title = (!senderAlias.isEmpty && senderAlias != "Unknown") ? senderAlias : String(senderUuid.prefix(8))
        let body = ext == "txt" ? content : "[\(ext.uppercased())]"

        let notificationContent = UNMutableNotificationContent()
        notificationContent.title = title
        notificationContent.body = body
        notificationContent.sound = .default
        notificationContent.userInfo = ["senderUuid": senderUuid, "action": "open_chat"]

        let request = UNNotificationRequest(
            identifier: "msg_\(UUID().uuidString)",
            content: notificationContent,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)

        DispatchQueue.main.async {
            NSApp.dockTile.badgeLabel = nil
        }
    }

    public func showIncomingCallNotification(peerUuid: String, alias: String) {
        let title = (!alias.isEmpty && alias != "Unknown") ? alias : String(peerUuid.prefix(8))

        let notificationContent = UNMutableNotificationContent()
        notificationContent.title = title
        notificationContent.body = "Incoming Voice Call..."
        notificationContent.sound = .default
        notificationContent.userInfo = ["peerUuid": peerUuid, "action": "show_call"]

        let request = UNNotificationRequest(
            identifier: "call_\(peerUuid)",
            content: notificationContent,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    public func clearCallNotification(peerUuid: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["call_\(peerUuid)"])
    }

    // MARK: - UNUserNotificationCenterDelegate
    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // Deliver banner and sound, strictly NO badge
        completionHandler([.banner, .sound])
    }

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        DispatchQueue.main.async {
            NSApp.activate(ignoringOtherApps: true)
            NSApp.dockTile.badgeLabel = nil
        }
        completionHandler()
    }
}
