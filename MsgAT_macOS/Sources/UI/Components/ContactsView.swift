// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI
import Combine
import AppKit
import UniformTypeIdentifiers

@MainActor private final class ContactsModel:ObservableObject{
    @Published var query="";@Published var blockedOnly=false;@Published var rows=[ContactEntry]();@Published var selected:ContactEntry?
    @Published var scannedID:String?
    @Published var safety:ContactSafetyInfo?;@Published var otherCode="";@Published var error:String?;@Published var busy=false;@Published var more=false
    @Published private(set) var loadingContact = false
    @Published private(set) var loadingDirectory = false
    private let focusedPeer: String?

    init(focusedPeer: String? = nil) {
        self.focusedPeer = focusedPeer
        self.loadingContact = focusedPeer != nil
    }

    private var limit = 50
    private var generation = 0
    private var closed = false
    func load(reset:Bool=true){
        guard !closed, !busy else { return }
        if reset { limit = 50 }
        generation += 1
        let version = generation
        let text = query
        let blocked = blockedOnly
        let end = limit
        let focused = focusedPeer
        let peer = focused ?? selected?.id
        loadingDirectory = focused == nil
        Task {
            defer {
                if generation == version {
                    loadingContact = false
                    loadingDirectory = false
                }
            }
            do {
                let result = try await EncryptedMediaWork.run {
                    var page: [ContactEntry] = []
                    var hasMore = false
                    if focused == nil {
                        var loaded = Set<String>()
                        for start in stride(from: 0, to: end, by: 50) {
                            let result = try ContactDirectoryStore.list(search: text, blockedOnly: blocked, offset: start)
                            page.append(contentsOf: result.filter { loaded.insert($0.id).inserted })
                            hasMore = result.count == 50
                            if !hasMore { break }
                        }
                    }
                    let current = try peer.flatMap { id in
                        try ContactDirectoryStore.list(exactPeer: id).first
                    }
                    return (page, current, hasMore)
                }
                guard !closed, generation == version else { return }
                rows = result.0
                more = result.2
                if focused != nil || selected?.id == peer {
                    if selected?.id != result.1?.id || result.1?.revoked == true {
                        safety = nil
                        otherCode = ""
                    }
                    selected = result.1
                }
            } catch {
                if !closed, generation == version {
                    self.error = InterfaceStrings.current()("contact_load_failed")
                }
            }
        }
    }
    func next() {
        guard more, !busy, !loadingDirectory else { return }
        limit += 50
        load(reset: false)
    }
    func select(_ item:ContactEntry){selected=item;safety=nil;otherCode="";error=nil}
    func shutdown(){closed=true;generation+=1}
    func work(failure: String = "contact_action_failed", _ action: @escaping () async throws -> Void) {
        guard !busy, !closed else { return }
        busy = true
        error = nil
        generation += 1
        Task {
            do {
                try await action()
            } catch {
                if !closed { self.error = InterfaceStrings.current()(failure) }
            }
            busy = false
            load(reset: false)
        }
    }
    func request(_ peer:ContactEntry){requestID(peer.id)}
    func requestID(_ id: String) {
        work {
            let success = await withCheckedContinuation { continuation in
                SecureMessaging.shared.requestContact(id) { continuation.resume(returning: $0) }
            }
            guard success else { throw SecurityCore.Failure.operation(InterfaceStrings.current()("contact_action_failed")) }
            if self.focusedPeer == nil {
                let requested = try await EncryptedMediaWork.run { try ContactDirectoryStore.list(exactPeer: id).first }
                if let requested, !self.closed { self.select(requested) }
            }
        }
    }
    func scan(directory: Bool = false) {
        guard !busy, !closed else { return }
        let expectedPeer = directory ? nil : selected?.id
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        let receive: (NSApplication.ModalResponse) -> Void = { result in
            guard result == .OK, let url = panel.url else { return }
            Task { @MainActor in
                guard directory || self.selected?.id == expectedPeer else { return }
                self.work(failure: "contact_scan_failed") {
                    let parsed = try await EncryptedMediaWork.run {
                        try SecurityCore.call(["operation": "parse_contact_code", "value": ContactCodes.read(url)])
                    }
                    guard !self.closed else { return }
                    let failure = SecurityCore.Failure.operation(InterfaceStrings.current()("contact_scan_failed"))
                    if parsed["kind"] as? String == "contact" {
                        guard directory, self.focusedPeer == nil, let id = parsed["id"] as? String else { throw failure }
                        self.scannedID = id
                    } else {
                        guard let peer = self.selected, peer.id == expectedPeer, !peer.revoked,
                              let code = parsed["scannable"] as? String else { throw failure }
                        try await EncryptedMediaWork.run { try ContactDirectoryStore.verifyScan(peer.id, scan: code) }
                        let info = try await EncryptedMediaWork.run { try ContactDirectoryStore.safety(peer.id) }
                        if !self.closed { self.safety = info }
                    }
                }
            }
        }
        if let owner = NSApp.keyWindow { panel.beginSheetModal(for: owner, completionHandler: receive) }
        else { panel.begin(completionHandler: receive) }
    }
    func withdraw(_ peer:ContactEntry){work {let success=await withCheckedContinuation{continuation in SecureMessaging.shared.withdrawContact(peer.id){continuation.resume(returning:$0)}};guard success else {throw SecurityCore.Failure.operation("Request could not be withdrawn")}}}
    func accept(_ peer:ContactEntry){work {let success=await withCheckedContinuation{continuation in SecureMessaging.shared.acceptContact(peer.id){continuation.resume(returning:$0)}};guard success else {throw SecurityCore.Failure.operation("Contact request could not be accepted")}}}
    func decline(_ peer:ContactEntry){work {let success=await withCheckedContinuation{continuation in SecureMessaging.shared.declineContact(peer.id){continuation.resume(returning:$0)}};guard success else {throw SecurityCore.Failure.operation("Contact decision could not be queued")}}}
    func block(_ peer:ContactEntry){work {let value = !peer.blocked
        let success=await withCheckedContinuation{continuation in SecureMessaging.shared.setContactBlocked(peer.id,value:value){continuation.resume(returning:$0)}}
        guard success else {throw SecurityCore.Failure.operation("Contact could not be changed")}
    }}
    func delete(_ peer:ContactEntry){work {
        let success=await withCheckedContinuation{continuation in SecureMessaging.shared.deleteContact(peer.id){continuation.resume(returning:$0)}}
        guard success else {throw SecurityCore.Failure.operation("Contact could not be removed")};self.selected=nil;self.safety=nil
    }}
    func showSafety(_ peer:ContactEntry){work {let info=try await EncryptedMediaWork.run{try ContactDirectoryStore.safety(peer.id)};if !self.closed {self.safety=info;self.otherCode=""}}}
    func verify(_ peer:ContactEntry,code:ContactSafetyInfo){work {try await EncryptedMediaWork.run{try ContactDirectoryStore.verify(peer.id,expected:code.display)};let info=try await EncryptedMediaWork.run{try ContactDirectoryStore.safety(peer.id)};if !self.closed {self.safety=info;self.otherCode=""}}}
    func clearVerification(_ peer:ContactEntry){work {try await EncryptedMediaWork.run{try ContactDirectoryStore.clearVerification(peer.id)};let info=try await EncryptedMediaWork.run{try ContactDirectoryStore.safety(peer.id)};if !self.closed {self.safety=info}}}
}

private enum ContactDecision {
    case request(String)
    case block(ContactEntry)
    case remove(ContactEntry)

    var titleKey: String {
        switch self {
        case .request: return "contact_confirm_request"
        case .block(let peer): return peer.blocked ? "contact_confirm_unblock" : "contact_confirm_block"
        case .remove: return "contact_confirm_delete"
        }
    }

    var isDestructive: Bool {
        if case .remove = self { return true }
        return false
    }

    var confirmKey: String {
        if case .request = self { return "contact_send_request" }
        return "confirm"
    }
}

struct ContactsView: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var model: ContactsModel
    @FocusState private var searchFocused: Bool
    @State private var confirmation: ContactDecision?
    @State private var showOwnCode = false
    @State private var showAddContact = false
    @State private var showAvatar = false
    let focusedPeer: String?
    let onSharedMedia: (() -> Void)?
    let onCall: ((Bool) -> Void)?
    let muteUntil: Int64
    let onChangeNotifications: ((String, Int64?) -> Void)?
    let onOpen: (String) -> Void
    private var showSafety: Bool { model.safety != nil }

    init(focusedPeer: String? = nil, onSharedMedia: (() -> Void)? = nil,
         onCall: ((Bool) -> Void)? = nil, muteUntil: Int64 = 0,
         onChangeNotifications: ((String, Int64?) -> Void)? = nil, onOpen: @escaping (String) -> Void) {
        self.focusedPeer = focusedPeer
        self.onSharedMedia = onSharedMedia
        self.onCall = onCall
        self.muteUntil = muteUntil
        self.onChangeNotifications = onChangeNotifications
        self.onOpen = onOpen
        _model = StateObject(wrappedValue: ContactsModel(focusedPeer: focusedPeer))
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            if model.busy || model.loadingContact {
                ProgressView().controlSize(.small).frame(maxWidth: .infinity).padding(8)
            }
            if let error = model.error {
                Text(error)
                    .foregroundStyle(.red)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
            }
            HStack(alignment: .top, spacing: 0) {
                if focusedPeer == nil {
                    directory
                        .frame(width: 240)
                        .disabled(model.busy)
                    Divider()
                }
                profilePane
            }
            .frame(maxHeight: .infinity)
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .frame(width: focusedPeer == nil ? 730 : 470,
               height: min(560, (NSScreen.main?.visibleFrame.height ?? 720) * 0.78))
        .onChange(of: model.blockedOnly) { _ in model.load() }
        .task(id: model.query) {
            if focusedPeer == nil { try? await Task.sleep(nanoseconds: 150_000_000) }
            if !Task.isCancelled { model.load() }
        }
        .onReceive(NotificationCenter.default.publisher(for: ContactDirectoryStore.changed)
            .debounce(for: .milliseconds(80), scheduler: RunLoop.main)) { _ in
            model.load(reset: false)
        }
        .onChange(of: model.scannedID) { value in
            if let id = value { confirmation = .request(id) }
        }
        .onDisappear { model.shutdown() }
        .interactiveDismissDisabled(model.busy)
        .sheet(isPresented: $showAddContact) {
            AddConversationDialog(isPresented: $showAddContact, myUuid: KeychainManager.shared.myUuid,
                                  onConfirm: model.requestID)
        }
        .sheet(isPresented: $showOwnCode) { ownCodeSheet }
        .sheet(isPresented: $showAvatar) {
            if let peer = model.selected, let avatar = peer.avatar {
                DialogSurface {
                    VStack(spacing: 12) {
                        AvatarView(avatarPath: avatar, fallbackText: peer.name, size: 300, cornerRadius: 8)
                        Button(interfaceText("close")) { showAvatar = false }.keyboardShortcut(.cancelAction)
                    }
                }
            }
        }
        .alert(confirmation.map { interfaceText($0.titleKey) } ?? "",
               isPresented: Binding(get: { confirmation != nil }, set: { if !$0 { confirmation = nil; model.scannedID = nil } }),
               presenting: confirmation) { decision in
            Button(interfaceText(decision.confirmKey), role: decision.isDestructive ? .destructive : nil) {
                switch decision {
                case .request(let id): model.requestID(id)
                case .block(let peer): model.block(peer)
                case .remove(let peer): model.delete(peer)
                }
                model.scannedID = nil
                confirmation = nil
            }
            Button(interfaceText("cancel"), role: .cancel) { confirmation = nil; model.scannedID = nil }
        } message: { decision in
            if case .request(let id) = decision { Text(id) }
        }
        .overlay(NoticeView())
    }

    private var header: some View {
        HStack(spacing: 10) {
            if showSafety {
                toolbarButton("contact_back", icon: "chevron.backward") {
                    model.safety = nil
                    model.otherCode = ""
                }
            }
            Text(interfaceText(showSafety ? "contact_safety" : focusedPeer == nil ? "contacts" : "contact_profile"))
                .font(.headline).lineLimit(1)
            Spacer()
            if !showSafety {
                if focusedPeer == nil {
                    toolbarButton("add_friend", icon: "person.badge.plus") { showAddContact = true }
                }
                if focusedPeer == nil || model.selected != nil {
                    Menu {
                        if focusedPeer == nil {
                            Button { showOwnCode = true } label: {
                                Label(interfaceText("contact_my_qr"), systemImage: "qrcode")
                            }
                            Toggle(interfaceText("contact_blocked_list"), isOn: $model.blockedOnly)
                            Button { model.scan(directory: true) } label: {
                                Label(interfaceText("contact_scan_image"), systemImage: "qrcode.viewfinder")
                            }
                        }
                        if let peer = model.selected {
                            if focusedPeer == nil { Divider() }
                            if !peer.blocked || !peer.revoked {
                                Button(role: peer.blocked ? nil : .destructive) { confirmation = .block(peer) } label: {
                                    Label(interfaceText(peer.blocked ? "contact_unblock" : "contact_block"), systemImage: "nosign")
                                }
                            }
                            Button(role: .destructive) { confirmation = .remove(peer) } label: {
                                Label(interfaceText("contact_delete"), systemImage: "trash")
                            }
                        }
                    } label: { Image(systemName: "ellipsis.circle").frame(width: 28, height: 28) }
                    .menuStyle(.borderlessButton)
                    .menuIndicator(.hidden)
                    .fixedSize()
                    .help(interfaceText("contact_actions"))
                    .accessibilityLabel(interfaceText("contact_actions"))
                }
            }
            toolbarButton("close", icon: "xmark") { dismiss() }
                .keyboardShortcut(.cancelAction)
        }
        .padding(.horizontal, 14).frame(minHeight: 48)
        .disabled(model.busy)
    }

    private var directory: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
                TextField(interfaceText("contact_search"), text: $model.query)
                    .textFieldStyle(.plain).focused($searchFocused)
                if !model.query.isEmpty {
                    toolbarButton("contact_clear_search", icon: "xmark.circle.fill") { model.query = "" }
                }
            }
            .padding(.horizontal, 10).frame(height: 32)
            .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 8))
            .padding(10)
            if model.blockedOnly {
                Text(interfaceText("contact_blocked_list")).font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading).padding([.leading, .bottom], 10)
            }
            Divider()
            if model.rows.isEmpty {
                ZStack {
                    if model.loadingDirectory { ProgressView().controlSize(.small) }
                    else if model.error == nil {
                        Text(interfaceText("contact_no_results")).foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 1) {
                        ForEach(model.rows) { peer in
                            Button { model.select(peer) } label: {
                                HStack(spacing: 10) {
                                    AvatarView(avatarPath: peer.avatar, fallbackText: peer.name, size: 38, cornerRadius: 19)
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(peer.name).lineLimit(1).fontWeight(.medium)
                                        Text(status(peer)).lineLimit(1).font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer(minLength: 0)
                                }
                                .padding(.horizontal, 9).padding(.vertical, 6)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                                .background(model.selected?.id == peer.id ? Color.accentColor.opacity(0.14) : .clear,
                                            in: RoundedRectangle(cornerRadius: 7))
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(model.selected?.id == peer.id ? .isSelected : [])
                        }
                        if model.more {
                            Button(interfaceText("contact_more"), action: model.next)
                                .buttonStyle(.borderless).padding(8).disabled(model.loadingDirectory)
                        }
                    }
                    .padding(5)
                }
            }
        }
        .background(Color(nsColor: .controlBackgroundColor))
        .disabled(showSafety)
    }

    @ViewBuilder
    private var profilePane: some View {
        if let peer = model.selected {
            ZStack {
                ScrollView { detail(peer) }
                    .opacity(showSafety ? 0 : 1)
                    .allowsHitTesting(!showSafety)
                    .accessibilityHidden(showSafety)
                if showSafety, let code = model.safety, !peer.revoked {
                    ScrollView { safetyView(peer, code: code) }
                        .background(Color(nsColor: .windowBackgroundColor))
                        .transition(reduceMotion ? .identity : .opacity)
                }
            }
            .id(peer.id)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: showSafety)
            .disabled(model.busy)
        } else {
            ZStack {
                if !model.loadingContact && model.error == nil {
                    Text(interfaceText(focusedPeer == nil ? "contact_choose" : "contact_unavailable"))
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func detail(_ peer: ContactEntry) -> some View {
        let connected = peer.accepted && !peer.blocked && !peer.revoked
        return VStack(spacing: 16) {
            VStack(spacing: 7) {
                if let avatar = peer.avatar {
                    Button { showAvatar = true } label: {
                        AvatarView(avatarPath: avatar, fallbackText: peer.name, size: 82, cornerRadius: 41)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(peer.name))
                } else {
                    AvatarView(avatarPath: nil, fallbackText: peer.name, size: 82, cornerRadius: 41)
                }
                Text(peer.name).font(.title2.weight(.semibold)).multilineTextAlignment(.center).lineLimit(2)
                Text(status(peer)).font(.callout).foregroundStyle(.secondary)
            }
            if connected {
                HStack(spacing: 8) {
                    if focusedPeer == nil {
                        profileAction("contact_open_chat", icon: "bubble.left") { dismiss(); onOpen(peer.id) }
                    }
                    if let onCall {
                        profileAction("voice_call", icon: "phone") { onCall(false); dismiss() }
                        profileAction("video_call", icon: "video") { onCall(true); dismiss() }
                    }
                    if let onSharedMedia {
                        profileAction("shared_media", icon: "photo.on.rectangle.angled") { onSharedMedia(); dismiss() }
                    }
                }
            }
            if !peer.blocked && !peer.revoked && (peer.incoming || !connected) {
                VStack(spacing: 6) {
                    HStack {
                        if peer.incoming {
                            Button(interfaceText("contact_accept")) { model.accept(peer) }.buttonStyle(.borderedProminent)
                            Button(interfaceText("contact_decline")) { model.decline(peer) }
                        } else {
                            Button(interfaceText(peer.outgoing ? "contact_resend_request" : "contact_send_request")) {
                                model.request(peer)
                            }.buttonStyle(.borderedProminent)
                        }
                    }
                    if peer.outgoing && !connected {
                        Button(interfaceText("contact_withdraw_request")) { model.withdraw(peer) }.buttonStyle(.borderless)
                    }
                }
            }
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(interfaceText("contact_public_id")).font(.caption).foregroundStyle(.secondary)
                        Text(peer.id).font(.callout.monospaced()).textSelection(.enabled)
                    }
                    Spacer(minLength: 0)
                    toolbarButton("contact_copy_id", icon: "doc.on.doc") { copy(peer.id) }
                }
                .padding(12)
                if !peer.revoked {
                    Divider().padding(.leading, 12)
                    Button { model.showSafety(peer) } label: {
                        HStack {
                            Label(interfaceText("contact_safety"), systemImage: "lock.shield")
                            Spacer()
                            Image(systemName: "chevron.forward").foregroundStyle(.secondary).accessibilityHidden(true)
                        }
                        .padding(12)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                if connected, let onChangeNotifications {
                    Divider().padding(.leading, 12)
                    HStack {
                        Text(interfaceText("conversation_notifications"))
                        Spacer()
                        ConversationMuteMenu(muteUntil: muteUntil, onChange: onChangeNotifications)
                    }
                    .padding(12)
                }
            }
            .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 9))
        }
        .padding(18)
        .frame(maxWidth: .infinity)
    }

    private func safetyView(_ peer: ContactEntry, code: ContactSafetyInfo) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            ContactCodeImage(value: ContactCodes.verification(code.scannable))
                .frame(maxWidth: .infinity, alignment: .center)
            Text(interfaceText(code.verified ? "contact_verified" : "contact_unverified")).font(.headline)
            let digits = Array(code.display)
            Text(stride(from: 0, to: digits.count, by: 5).map { String(digits[$0..<min($0 + 5, digits.count)]) }.joined(separator: " "))
                .font(.body.monospaced()).textSelection(.enabled)
            Text(interfaceText("contact_compare")).font(.caption).foregroundStyle(.secondary)
            TextField(interfaceText("contact_matching_code"), text: $model.otherCode).textFieldStyle(.roundedBorder)
                .onChange(of: model.otherCode) { if $0.count > 100 { model.otherCode = String($0.prefix(100)) } }
            HStack {
                Button(interfaceText("contact_verify")) { model.verify(peer, code: code) }
                    .disabled(model.otherCode.filter { !$0.isWhitespace } != code.display)
                Button(interfaceText("contact_scan_image")) { model.scan() }
            }
            if code.verified {
                Button(interfaceText("contact_clear_verification")) { model.clearVerification(peer) }.buttonStyle(.borderless)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var ownCodeSheet: some View {
        DialogSurface {
            VStack(spacing: 12) {
                Text(interfaceText("contact_public_identity")).font(.headline)
                ContactCodeImage(value: "msgat://contact/" + KeychainManager.shared.myUuid)
                Text(KeychainManager.shared.myUuid).font(.caption.monospaced()).textSelection(.enabled)
                HStack {
                    Button(interfaceText("contact_copy_id")) { copy(KeychainManager.shared.myUuid) }
                    Button(interfaceText("close")) { showOwnCode = false }.keyboardShortcut(.cancelAction)
                }
            }
            .frame(width: 368)
        }
    }

    private func copy(_ id: String) {
        NSPasteboard.general.clearContents()
        if NSPasteboard.general.setString(id, forType: .string) {
            NoticeCenter.shared.show(interfaceText("contact_id_copied"))
        }
    }

    private func toolbarButton(_ key: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).frame(width: 28, height: 28) }
            .buttonStyle(.borderless)
            .help(interfaceText(key))
            .accessibilityLabel(interfaceText(key))
    }

    private func profileAction(_ key: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon).font(.system(size: 17, weight: .medium))
                Text(interfaceText(key)).font(.caption).lineLimit(2).multilineTextAlignment(.center)
            }
            .padding(6).frame(maxWidth: .infinity, minHeight: 52).contentShape(Rectangle())
        }
        .buttonStyle(.bordered)
        .foregroundStyle(Color.accentColor)
        .accessibilityLabel(interfaceText(key))
    }

    private func status(_ peer: ContactEntry) -> String {
        let key: String
        if peer.revoked { key = "contact_identity_reset" }
        else if peer.blocked { key = "contact_blocked" }
        else if peer.accepted { key = "contact_accepted" }
        else if peer.incoming { key = "contact_incoming" }
        else if peer.outgoing { key = "contact_pending" }
        else {
            switch peer.outcome {
            case "declined": key = "contact_declined"
            case "withdrawn": key = "contact_withdrawn"
            case "deleted": key = "contact_removed"
            default: key = "contact_not_connected"
            }
        }
        return interfaceText(key)
    }
}
