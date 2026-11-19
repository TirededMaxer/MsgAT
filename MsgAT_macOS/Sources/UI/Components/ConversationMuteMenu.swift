// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI

struct ConversationMuteMenu: View {
    @Environment(\.interfaceStrings) private var interfaceText
    let muteUntil: Int64
    let onChange: (String, Int64?) -> Void

    var body: some View {
        let now = Date()
        let expiry = Date(timeIntervalSince1970: Double(muteUntil) / 1_000)
        let dates = expiry > now && muteUntil != ConversationIndexStore.permanentMuteUntil ? [now, expiry] : [now]
        TimelineView(.explicit(dates)) { timeline in
            let muted = expiry > timeline.date
            let label = interfaceText(muted ? "conversation_muted" : "conversation_notifications")
            Menu {
                ConversationMuteMenuItems(muteUntil: muteUntil, onChange: onChange)
            } label: {
                Image(systemName: muted ? "bell.slash" : "bell")
            }
            .menuIndicator(.hidden)
            .help(label)
            .accessibilityLabel(label)
        }
    }
}

struct ConversationMuteMenuItems: View {
    @Environment(\.interfaceStrings) private var interfaceText
    let muteUntil: Int64
    let onChange: (String, Int64?) -> Void

    var body: some View {
        if muteUntil > Int64(Date().timeIntervalSince1970 * 1_000) {
            let expiry = Date(timeIntervalSince1970: Double(muteUntil) / 1_000)
            let status = muteUntil == ConversationIndexStore.permanentMuteUntil ? interfaceText("conversation_muted") :
                String(format: interfaceText("conversation_muted_until"), expiry.formatted(date: .abbreviated, time: .shortened))
            Button(status) {}.disabled(true)
            Button(interfaceText("unmute")) { onChange("unmute", nil) }
            Divider()
        }
        Button(interfaceText("conversation_mute_one_hour")) { mute(hours: 1) }
        Button(interfaceText("conversation_mute_eight_hours")) { mute(hours: 8) }
        Button(interfaceText("conversation_mute_two_days")) { mute(hours: 48) }
        Button(interfaceText("conversation_mute_until_enabled")) { onChange("mute", nil) }
    }

    private func mute(hours: Int64) {
        onChange("mute", Int64(Date().timeIntervalSince1970 * 1_000) + hours * 3_600_000)
    }
}
