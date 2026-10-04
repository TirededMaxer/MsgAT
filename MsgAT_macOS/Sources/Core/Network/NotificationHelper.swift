import Foundation
import UserNotifications
import AppKit

public final class NotificationHelper: NSObject, UNUserNotificationCenterDelegate, Sendable {
    public static let shared = NotificationHelper()
    @MainActor public static var openMainWindow: (() -> Void)?
    @MainActor public static weak var mainWindow: NSWindow?

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
        let body = ext == "txt" ? content : "[Media]"

        let notificationContent = UNMutableNotificationContent()
        notificationContent.title = title
        notificationContent.body = body
        notificationContent.sound = .default
        notificationContent.userInfo = ["senderUuid": senderUuid, "action": "open_chat"]

        let request = UNNotificationRequest(
            identifier: "msg_\(senderUuid)",
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
        notificationContent.body = "Incoming call"
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

    public func clearMessageNotifications() {
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { notifications in
            let identifiers = notifications.map { $0.request.identifier }.filter { $0.hasPrefix("msg_") }
            Task { @MainActor in
                guard NSApp.isActive else { return }
                center.removeDeliveredNotifications(withIdentifiers: identifiers)
            }
        }
        center.getPendingNotificationRequests { requests in
            let identifiers = requests.map(\.identifier).filter { $0.hasPrefix("msg_") }
            Task { @MainActor in
                guard NSApp.isActive else { return }
                center.removePendingNotificationRequests(withIdentifiers: identifiers)
            }
        }
    }

    // MARK: - UNUserNotificationCenterDelegate
    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // Messages received in the foreground are already visible in the client.
        if notification.request.identifier.hasPrefix("msg_") { completionHandler([]) }
        else { completionHandler([.banner, .sound]) }
    }

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        DispatchQueue.main.async {
            Self.openMainWindow?()
            NSApp.activate(ignoringOtherApps: true)
            NSApp.dockTile.badgeLabel = nil
            self.clearMessageNotifications()
        }
        completionHandler()
    }
}
