import Foundation
import UserNotifications
import AppKit

public final class NotificationHelper: NSObject, UNUserNotificationCenterDelegate, Sendable {
    public static let shared = NotificationHelper()
    @MainActor public static var openMainWindow: (() -> Void)?
    @MainActor public static weak var mainWindow: NSWindow?
    static let messageTargetChanged = Notification.Name("MsgAT.notificationMessageTargetChanged")
    static let callAnswerRequested = Notification.Name("MsgAT.notificationCallAnswerRequested")
    static let callFocusRequested = Notification.Name("MsgAT.notificationCallFocusRequested")
    @MainActor private static var pendingMessageToken: String?
    @MainActor private static var pendingCallAnswer: String?
    private static let callIdentifier = "msgat.call"
    private static let answerAction = "msgat.call.answer"

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

    func showPrivateMessageNotification(senderUuid: String, messageID: String, silent: Bool, pinned: Bool = false) {
        Task { @MainActor in
            guard !NSApp.isActive else { return }
            let center = UNUserNotificationCenter.current()
            let settings = await center.notificationSettings()
            guard !NSApp.isActive,
                  let notice = try? MessageNotificationStore.prepare(
                    peer: senderUuid, messageID: messageID,
                    allowPreview: settings.showPreviewsSetting == .whenAuthenticated, pinned: pinned
                  ) else { return }
            let content = UNMutableNotificationContent()
            content.title = notice.title
            content.body = notice.body ?? InterfaceStrings.current()(pinned ? "message_pinned" : "new_message")
            content.sound = silent || !notice.playSound ? nil : .default
            content.userInfo = ["target": notice.token]
            if let replaced = notice.replacedToken { dismissMessageNotifications(tokens: [replaced]) }
            try? await center.add(UNNotificationRequest(identifier: "msg_\(notice.token)", content: content, trigger: nil))
        }
    }

    func showIncomingCallNotification(id: String, video: Bool) {
        let strings = InterfaceStrings.current()
        let center = UNUserNotificationCenter.current()
        let answer = UNNotificationAction(identifier: Self.answerAction, title: strings("accept_call"),
                                          options: [.foreground, .authenticationRequired])
        center.setNotificationCategories([UNNotificationCategory(identifier: Self.callIdentifier,
            actions: [answer], intentIdentifiers: [], options: [])])
        let notificationContent = UNMutableNotificationContent()
        notificationContent.title = "MsgAT"
        notificationContent.body = strings(video ? "incoming_video_call" : "incoming_voice_call")
        notificationContent.sound = .default
        notificationContent.categoryIdentifier = Self.callIdentifier
        notificationContent.userInfo = ["call": id]

        let request = UNNotificationRequest(
            identifier: Self.callIdentifier,
            content: notificationContent,
            trigger: nil
        )
        center.add(request)
    }

    func clearCallNotification() {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [Self.callIdentifier])
        center.removeDeliveredNotifications(withIdentifiers: [Self.callIdentifier])
    }

    func dismissMessageNotifications(tokens: [String]) {
        guard !tokens.isEmpty else { return }
        let identifiers = tokens.map { "msg_\($0)" }
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    public func clearMessageNotifications(onlyWhenActive: Bool = true) {
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { notifications in
            let identifiers = notifications.map { $0.request.identifier }.filter { $0.hasPrefix("msg_") }
            Task { @MainActor in
                guard !onlyWhenActive || NSApp.isActive else { return }
                center.removeDeliveredNotifications(withIdentifiers: identifiers)
            }
        }
        center.getPendingNotificationRequests { requests in
            let identifiers = requests.map(\.identifier).filter { $0.hasPrefix("msg_") }
            Task { @MainActor in
                guard !onlyWhenActive || NSApp.isActive else { return }
                center.removePendingNotificationRequests(withIdentifiers: identifiers)
            }
        }
    }

    @MainActor static func messageToken() -> String? {
        guard AppLockSession.shared.canShowContent else { return nil }
        return pendingMessageToken
    }

    @MainActor static func consumeMessageToken(_ token: String) -> Bool {
        guard AppLockSession.shared.canShowContent, pendingMessageToken == token else { return false }
        pendingMessageToken = nil
        return true
    }

    @MainActor static func takeCallAnswer() -> String? {
        guard AppLockSession.shared.canShowContent else { return nil }
        defer { pendingCallAnswer = nil }
        return pendingCallAnswer
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
        let request = response.notification.request
        let token = request.content.userInfo["target"] as? String
        let call = request.identifier == Self.callIdentifier ? request.content.userInfo["call"] as? String : nil
        let answersCall = response.actionIdentifier == Self.answerAction
        DispatchQueue.main.async {
            if let call {
                let manager = CallManager.shared
                guard manager.currentCallId == call, manager.callState == .incomingRinging else { return }
                manager.isMinimized = false
                if answersCall { Self.pendingCallAnswer = call }
                NSApp.activate(ignoringOtherApps: true)
                Self.openMainWindow?()
                if answersCall { NotificationCenter.default.post(name: Self.callAnswerRequested, object: nil) }
                else { NotificationCenter.default.post(name: Self.callFocusRequested, object: call) }
                return
            }
            Self.pendingMessageToken = token
            NSApp.activate(ignoringOtherApps: true)
            Self.openMainWindow?()
            if token != nil { NotificationCenter.default.post(name: Self.messageTargetChanged, object: nil) }
            NSApp.dockTile.badgeLabel = nil
            self.clearMessageNotifications()
        }
        completionHandler()
    }
}
