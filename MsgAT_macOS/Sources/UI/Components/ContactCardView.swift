// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import Combine

struct ContactCardSendRequest: Identifiable {
    let id = UUID()
    let recipient: String
    let initial: SendOptions
    let send: @MainActor (String, String, SendOptions) async -> Bool
}

private struct ContactCardChoice: Identifiable {
    let id: String
    let name: String
    let avatar: String?
    var isLocal = false
}

@MainActor
struct ContactCardPickerView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    let request: ContactCardSendRequest
    @State private var entries: [ContactCardChoice] = []
    @State private var loaded = false
    @State private var query = ""
    @State private var selected: ContactCardChoice?
    @State private var options: SendOptions
    @State private var showOptions = false
    @State private var sending = false
    @State private var loadFailed = false
    @State private var sendFailed = false
    @State private var retry = 0

    init(request: ContactCardSendRequest) {
        self.request = request
        _options = State(initialValue: request.initial)
    }

    var body: some View {
        DialogSurface {
            VStack(alignment: .leading, spacing: 12) {
                Text(interfaceText("share_contact")).font(.headline)
                Text(interfaceText.format("recipient", request.recipient)).lineLimit(1).foregroundStyle(.secondary)
                if let selected {
                    contactRow(selected)
                    Text(selected.id).font(.caption.monospaced()).textSelection(.enabled)
                    let summary = sendOptionsLabel(options)
                    Button(summary.isEmpty ? interfaceText("send_options") : summary) {
                        showOptions = true
                    }.disabled(sending)
                } else {
                    TextField(interfaceText("contact_search"), text: $query).textFieldStyle(.roundedBorder)
                    if loadFailed {
                        HStack {
                            Text(interfaceText("contact_load_failed")).foregroundStyle(.red)
                            Spacer()
                            Button(interfaceText("retry")) { retry += 1 }
                        }
                    }
                    if loaded {
                        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
                        let matches = entries.filter {
                            term.isEmpty || $0.name.localizedCaseInsensitiveContains(term) || $0.id.localizedCaseInsensitiveContains(term)
                        }
                        if matches.isEmpty {
                            Text(interfaceText("contact_no_results")).foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, minHeight: 130)
                        } else {
                            ScrollView {
                                LazyVStack(alignment: .leading, spacing: 2) {
                                    ForEach(matches) { item in
                                        Button { selected = item; sendFailed = false } label: {
                                            contactRow(item).frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                                        }.buttonStyle(.plain)
                                    }
                                }
                            }.frame(height: 280)
                        }
                    } else {
                        ProgressView().frame(maxWidth: .infinity, minHeight: 130)
                    }
                }
                if sendFailed { Text(interfaceText("contact_card_send_failed")).font(.callout).foregroundStyle(.red) }
                Divider()
                HStack {
                    Spacer()
                    Button(interfaceText("cancel")) { dismiss() }.keyboardShortcut(.cancelAction).disabled(sending)
                    if let selected {
                        Button(interfaceText("contact_back")) { self.selected = nil; sendFailed = false }.disabled(sending)
                        Button(interfaceText(sending ? "receipt_sending" : "send")) { submit(selected) }
                            .buttonStyle(.borderedProminent).keyboardShortcut(.defaultAction).disabled(sending)
                    }
                }
            }
        }
        .frame(width: 475)
        .interactiveDismissDisabled(sending)
        .task(id: retry) { await load() }
        .sheet(isPresented: $showOptions) { SendOptionsView(initial: options) { options = $0 } }
    }

    private func contactRow(_ item: ContactCardChoice) -> some View {
        HStack(spacing: 10) {
            AvatarView(avatarPath: item.avatar, fallbackText: item.name, size: 34, cornerRadius: 17, fallbackSymbol: "person")
            VStack(alignment: .leading, spacing: 3) {
                Text(item.name).lineLimit(1)
                Text(item.isLocal ? interfaceText("contact_public_identity") : String(item.id.suffix(12)))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }.padding(.vertical, 4)
    }

    private func load() async {
        loadFailed = false
        loaded = false
        let keychain = KeychainManager.shared
        let local = ContactCardChoice(id: keychain.myUuid, name: sharedContactName(keychain.myNickname ?? interfaceText("contact_public_identity")),
                                      avatar: keychain.myAvatarPath, isLocal: true)
        do {
            let contacts = try await EncryptedMediaWork.run { try MsgDao.shared.getForwardTargets() }
            guard !Task.isCancelled else { return }
            entries = [local] + contacts.filter { $0.uuid != local.id }.map {
                ContactCardChoice(id: $0.uuid, name: sharedContactName($0.alias), avatar: $0.avatarPath)
            }
        } catch {
            guard !Task.isCancelled else { return }
            entries = [local]
            loadFailed = true
        }
        loaded = true
    }

    private func submit(_ item: ContactCardChoice) {
        guard !sending else { return }
        sending = true
        sendFailed = false
        let selectedOptions = options
        Task { @MainActor in
            defer { sending = false }
            if await request.send(item.id, item.name, selectedOptions) { dismiss() }
            else { sendFailed = true }
        }
    }
}

@MainActor
struct ContactCardDetailsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    let name: String
    let readAccount: () throws -> String?
    let openChat: (String) -> Void
    @State private var account: String?
    @State private var contact: ContactEntry?
    @State private var version = 0
    @State private var loaded = false
    @State private var sending = false
    @State private var queued = false
    @State private var readError: String?
    @State private var requestFailed = false

    init(message: ChatMessage, openChat: @escaping (String) -> Void) {
        self.init(name: message.content, readAccount: {
            try MsgDao.shared.getContactCardAccount(peer: message.conversationUuid, messageID: message.id)
        }, openChat: openChat)
    }

    init(name: String, readAccount: @escaping () throws -> String?, openChat: @escaping (String) -> Void) {
        self.name = name
        self.readAccount = readAccount
        self.openChat = openChat
    }

    private var isLocal: Bool { account == KeychainManager.shared.myUuid }
    private var canOpen: Bool {
        loaded && contact?.accepted == true && contact?.blocked != true && contact?.revoked != true
    }
    private var canRequest: Bool {
        loaded && account != nil && !isLocal && !queued && contact?.accepted != true && contact?.blocked != true &&
            contact?.revoked != true && contact?.incoming != true && contact?.outgoing != true
    }

    var body: some View {
        DialogSurface {
            VStack(alignment: .leading, spacing: 12) {
                Text(name.isEmpty ? interfaceText("contact_card") : name).font(.headline)
                if loaded, let account {
                    Text(account).font(.caption.monospaced()).textSelection(.enabled)
                    Text(interfaceText(statusKey)).font(.callout).foregroundStyle(.secondary)
                } else if readError == nil {
                    ProgressView().controlSize(.small)
                }
                if let readError {
                    HStack {
                        Text(interfaceText(readError)).font(.callout).foregroundStyle(.red)
                        if readError == "contact_load_failed" {
                            Spacer()
                            Button(interfaceText("retry")) { version += 1 }.disabled(sending)
                        }
                    }
                } else if requestFailed {
                    Text(interfaceText("contact_action_failed")).font(.callout).foregroundStyle(.red)
                }
                Divider()
                HStack {
                    Spacer()
                    Button(interfaceText("close")) { dismiss() }.keyboardShortcut(.cancelAction).disabled(sending)
                    if canOpen, let account {
                        Button(interfaceText("contact_open_chat")) { dismiss(); openChat(account) }
                            .keyboardShortcut(.defaultAction).disabled(sending)
                    } else if canRequest, let account {
                        Button(interfaceText(sending ? "receipt_sending" : "contact_send_request")) { request(account) }
                            .buttonStyle(.borderedProminent).disabled(sending)
                    }
                }
            }
        }
        .frame(width: 475)
        .interactiveDismissDisabled(sending)
        .task(id: version) { await load() }
        .onReceive(NotificationCenter.default.publisher(for: ContactDirectoryStore.changed).receive(on: RunLoop.main)) { _ in
            version += 1
        }
    }

    private var statusKey: String {
        if isLocal { return "contact_public_identity" }
        if contact?.revoked == true { return "contact_identity_reset" }
        if contact?.blocked == true { return "contact_blocked" }
        if contact?.accepted == true { return "contact_accepted" }
        if queued || contact?.outgoing == true { return "contact_pending" }
        if contact?.incoming == true { return "contact_incoming" }
        return "contact_not_connected"
    }

    private func load() async {
        guard !Task.isCancelled else { return }
        readError = nil
        loaded = false
        let read = readAccount
        do {
            let value = try await EncryptedMediaWork.run {
                guard let id = try read()
                else { return (nil, nil) as (String?, ContactEntry?) }
                return (id, try ContactDirectoryStore.list(search: id).first(where: { $0.id == id }))
            }
            guard !Task.isCancelled else { return }
            account = value.0
            contact = value.1
            loaded = account != nil
            if account == nil { readError = "contact_card_unavailable" }
        } catch {
            if !Task.isCancelled { readError = "contact_load_failed" }
        }
    }

    private func request(_ id: String) {
        guard !sending else { return }
        sending = true
        requestFailed = false
        let read = readAccount
        Task { @MainActor in
            defer { sending = false }
            do {
                let current = try await EncryptedMediaWork.run { try read() }
                guard current == id else { version += 1; return }
                queued = await withCheckedContinuation { continuation in
                    SecureMessaging.shared.requestContact(id) { continuation.resume(returning: $0) }
                }
                requestFailed = !queued
            } catch { version += 1 }
        }
    }
}

private func sharedContactName(_ value: String) -> String {
    var result = ""
    var remaining = 256
    for scalar in value.trimmingCharacters(in: .whitespacesAndNewlines).unicodeScalars {
        if CharacterSet.controlCharacters.contains(scalar) { continue }
        remaining -= String(scalar).utf8.count
        if remaining < 0 { break }
        result.unicodeScalars.append(scalar)
    }
    let name = result.trimmingCharacters(in: .whitespacesAndNewlines)
    return name.isEmpty ? "User" : name
}
