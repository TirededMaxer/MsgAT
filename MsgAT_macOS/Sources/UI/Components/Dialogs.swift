import SwiftUI
import AppKit
import UniformTypeIdentifiers
import UserNotifications
import Combine

struct DialogSurface<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        content.padding(20)
            .background(Color(NSColor.windowBackgroundColor))
            .overlay(NoticeView())
    }
}

private struct SettingsSheetLayout<Content: View, Footer: View>: View {
    @Environment(\.colorScheme) private var colorScheme
    let title: String
    let width: CGFloat
    @ViewBuilder var content: Content
    @ViewBuilder var footer: Footer

    var body: some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        let screen = NSApp.keyWindow?.screen ?? NSScreen.main
        let maxBodyHeight = min(438, max(160, (screen?.visibleFrame.height ?? 720) - 190))
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
            ViewThatFits(in: .vertical) {
                content.fixedSize(horizontal: false, vertical: true)
                ScrollView { content.frame(maxWidth: .infinity, alignment: .leading) }
            }
            .frame(maxHeight: maxBodyHeight, alignment: .topLeading)
            Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
            footer
        }
        .padding(18)
        .frame(width: width)
        .background(colors.chrome)
        .overlay(NoticeView())
    }
}

struct DialogField: View {
    let label: String
    @Binding var value: String
    var secure = false
    var hasError = false
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(.secondary)
            Group {
                if secure { SecureField(label, text: $value) }
                else { TextField(label, text: $value) }
            }.textFieldStyle(.roundedBorder).accessibilityLabel(label)
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(hasError ? Color.red : .clear, lineWidth: 1))
        }
    }
}

private struct SettingsField: View {
    let label: String
    @Binding var value: String
    var secure = false
    var error: String? = nil
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label).font(.caption).foregroundColor(.secondary)
                .frame(width: 100, alignment: .trailing)
            VStack(alignment: .leading, spacing: 4) {
                Group {
                    if secure { SecureField(label, text: $value) }
                    else { TextField(label, text: $value) }
                }
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel(label)
                if let error {
                    Text(error).foregroundStyle(.red).font(.caption)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

struct ConfirmationDialog: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    let message: String
    var title: String? = nil
    var confirmColor: Color = .red
    var confirmLabel: String? = nil
    let onCancel: () -> Void
    let onConfirm: () -> Void
    var body: some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        VStack(alignment: .leading, spacing: 14) {
            Text(title ?? interfaceText("warning")).font(.headline).accessibilityAddTraits(.isHeader)
            Text(message).fixedSize(horizontal: false, vertical: true)
            Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
            HStack {
                Spacer()
                Button(interfaceText("cancel"), action: onCancel).buttonStyle(.bordered).keyboardShortcut(.cancelAction)
                Button(confirmLabel ?? interfaceText("confirm"), action: onConfirm)
                    .buttonStyle(.borderedProminent).tint(confirmColor)
            }
        }
        .padding(20)
        .frame(width: 400)
        .background(colors.chrome)
    }
}

public struct AddConversationDialog: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @FocusState private var fieldFocused: Bool
    @Binding var isPresented: Bool
    var myUuid: String
    var onConfirm: (String) -> Void
    @State private var targetUuid = ""
    @State private var error: String?
    public var body: some View {
        DialogSurface {
            VStack(alignment: .leading, spacing: 24) {
                Text(interfaceText("add_friend")).font(.headline)
                VStack(alignment: .leading, spacing: 4) {
                    DialogField(label: interfaceText("contact_public_id"), value: $targetUuid, hasError: error != nil)
                        .focused($fieldFocused)
                        .onChange(of: targetUuid) {
                            if $0.count > 256 { targetUuid = String($0.prefix(256)) }
                            error = nil
                        }
                    if let error { Text(error).font(.system(size: 12)).foregroundColor(MsgATTheme.errorColor).fixedSize(horizontal: false, vertical: true) }
                }
                HStack {
                    Spacer()
                    Button(interfaceText("cancel")) { isPresented = false }.keyboardShortcut(.cancelAction)
                    Button(interfaceText("contact_send_request")) {
                        let uuid = targetUuid.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                        guard uuid.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
                            error = interfaceText("contact_invalid_id")
                            return
                        }
                        guard uuid != myUuid.lowercased() else { error = interfaceText("contact_self_id"); return }
                        onConfirm(uuid)
                        isPresented = false
                    }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                    .disabled(targetUuid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }.frame(width: 400).onAppear { fieldFocused = true }
    }
}

public struct FriendRequestsDialog: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Binding var isPresented: Bool
    let requests: [FriendRequest]
    var onAccept: (String, @escaping @Sendable (Bool) -> Void) -> Void
    var onDecline: (String, @escaping @Sendable (Bool) -> Void) -> Void
    @State private var pending = Set<String>()
    @State private var failed = false

    public var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(interfaceText("friend_requests")).font(.headline)
                Spacer()
                Button { isPresented = false } label: { Image(systemName: "xmark").frame(width: 28, height: 28) }
                    .buttonStyle(.borderless).keyboardShortcut(.cancelAction)
                    .help(interfaceText("close")).accessibilityLabel(interfaceText("close"))
            }
            .padding(.horizontal, 16).frame(minHeight: 48)
            Divider()
            if failed {
                Text(interfaceText("contact_action_failed")).foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(12)
            }
            if requests.isEmpty {
                Text(interfaceText("contact_no_requests")).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(requests) { request in
                            HStack(spacing: 10) {
                                AvatarView(avatarPath: request.avatarPath, fallbackText: request.nickname, size: 44, cornerRadius: 22)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(request.nickname).font(.body.weight(.medium)).lineLimit(1)
                                    Text(request.senderUuid).font(.caption.monospaced()).foregroundStyle(.secondary)
                                        .lineLimit(1).truncationMode(.middle)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                if pending.contains(request.id) {
                                    ProgressView().controlSize(.small).frame(width: 72, height: 32)
                                } else {
                                    decisionButton("contact_accept", icon: "checkmark", color: .accentColor) {
                                        decide(request.id, accept: true)
                                    }
                                    decisionButton("contact_decline", icon: "xmark", color: .red) {
                                        decide(request.id, accept: false)
                                    }
                                }
                            }
                            .padding(.horizontal, 16).padding(.vertical, 7)
                            Divider().padding(.leading, 70)
                        }
                    }
                }
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .frame(width: 430, height: min(420, (NSScreen.main?.visibleFrame.height ?? 720) * 0.72))
    }

    @MainActor private func decide(_ peer: String, accept: Bool) {
        guard pending.insert(peer).inserted else { return }
        failed = false
        let operation = accept ? onAccept : onDecline
        operation(peer) { saved in
            Task { @MainActor in
                pending.remove(peer)
                if !saved { failed = true }
            }
        }
    }

    private func decisionButton(_ key: String, icon: String, color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 15, weight: .semibold)).frame(width: 31, height: 32)
        }
        .buttonStyle(.borderless)
        .foregroundStyle(color)
        .help(interfaceText(key))
        .accessibilityLabel(interfaceText(key))
    }
}

public struct ServerSettings {
    public var ip: String
    public var port: String
    public var turnServer: String
    public var turnPort: String
    public var turnUsername: String
    public var turnPassword: String
    public var turnUseTls: Bool
    public var serverCertificateSha256: String = ""
    public var allowDevelopmentCertificate: Bool = false

    public init(ip: String, port: String, turnServer: String, turnPort: String, turnUsername: String, turnPassword: String, turnUseTls: Bool, serverCertificateSha256: String = "", allowDevelopmentCertificate: Bool = false) {
        self.ip = ip
        self.port = port
        self.turnServer = turnServer
        self.turnPort = turnPort
        self.turnUsername = turnUsername
        self.turnPassword = turnPassword
        self.turnUseTls = turnUseTls
        self.serverCertificateSha256 = serverCertificateSha256
        self.allowDevelopmentCertificate = allowDevelopmentCertificate
    }
}

public struct SettingsDialog: View {
    private enum NetworkTarget: Hashable { case advanced, turn, certificate }

    private enum Topic: String, CaseIterable {
        case identity, profile, network, advanced, turn, certificate, notifications, privacy, language, theme, size, storage, export_folder, reset

        var title: String {
            switch self {
            case .identity: return "copy_my_uuid"
            case .profile: return "edit_profile"
            case .advanced: return "advanced_network"
            case .turn: return "turn_relay"
            case .certificate: return "certificate_pin"
            case .notifications: return "notification_settings"
            case .size: return "text_size"
            case .storage: return "storage_usage"
            case .reset: return "reset_identity"
            default: return rawValue
            }
        }
    }

    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    @AppStorage(MsgATTheme.languagePreference) private var language = AppLanguage.system
    @AppStorage(MsgATTheme.themePreference) private var theme = AppThemeMode.system
    @AppStorage(MsgATTheme.chatTextPreference) private var chatTextPercentage = 100
    @Binding var isPresented: Bool
    var onSave: (ServerSettings) -> Void
    var onResetIdentity: () -> Void
    var onSaveIdentity: ((String, Data?, Bool, @escaping (Bool) -> Void) -> Void)?
    @State private var serverIp: String
    @State private var serverPort: String
    @State private var nickname: String
    let myUuid: String
    private let originalNetwork: ServerSettings
    @State private var currentAvatarPath = KeychainManager.shared.myAvatarPath
    @State private var showResetConfirm = false
    @State private var pendingRevocations = IdentityRevocationWorker.shared.pendingCount
    @State private var showNetwork = false
    @State private var showProfile = false
    @State private var showNotifications = false
    @State private var showPrivacy = false
    @State private var showStorage = false
    @State private var showExportFolder = false
    @State private var turnServer: String
    @State private var turnPort: String
    @State private var turnUsername: String
    @State private var turnPassword: String
    @State private var turnUseTls: Bool
    @State private var certificatePin: String
    @State private var allowDevelopmentCertificate: Bool
    @State private var showAdvancedNetwork = false
    @State private var networkTarget: NetworkTarget?
    @State private var search = ""
    @FocusState private var searchFocused: Bool

    private var searchWords: [Substring] { search.split(whereSeparator: \.isWhitespace) }
    private var matchingTopics: Set<Topic> {
        let words = searchWords
        return Set(Topic.allCases.filter { topic in
            let index = interfaceText(topic.title) + " " + interfaceText("settings_terms_\(topic.rawValue)")
            return words.allSatisfy { index.localizedStandardContains(String($0)) }
        })
    }
    private var normalizedPin: String { certificatePin.replacingOccurrences(of: ":", with: "").replacingOccurrences(of: " ", with: "").lowercased() }
    private var hasServerAddress: Bool { !serverIp.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var hasTurnServer: Bool { !turnServer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var validPin: Bool { normalizedPin.isEmpty || normalizedPin.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil }
    private func validPort(_ value: String) -> Bool { Int(value).map { (1...65535).contains($0) } ?? false }
    private var validSettings: Bool {
        hasServerAddress && validPort(serverPort) && (!hasTurnServer || validPort(turnPort)) && validPin
    }
    public init(isPresented: Binding<Bool>, initialIp: String, initialPort: String, initialNickname: String,
                myUuid: String, initialTurnServer: String = "", initialTurnPort: String = "3478",
                initialTurnUsername: String = "", initialTurnPassword: String = "", initialTurnUseTls: Bool = false,
                onSave: @escaping (ServerSettings) -> Void, onResetIdentity: @escaping () -> Void,
                onSaveIdentity: ((String, Data?, Bool, @escaping (Bool) -> Void) -> Void)? = nil) {
        self._isPresented = isPresented
        _serverIp = State(initialValue: initialIp); _serverPort = State(initialValue: initialPort)
        _nickname = State(initialValue: initialNickname); self.myUuid = myUuid
        _turnServer = State(initialValue: initialTurnServer); _turnPort = State(initialValue: initialTurnPort)
        _turnUsername = State(initialValue: initialTurnUsername); _turnPassword = State(initialValue: initialTurnPassword)
        _turnUseTls = State(initialValue: initialTurnUseTls)
        let initialPin = KeychainManager.shared.serverCertificateSha256 ?? ""
        let initialDevelopmentCertificate = KeychainManager.shared.allowDevelopmentCertificate
        _certificatePin = State(initialValue: initialPin)
        _allowDevelopmentCertificate = State(initialValue: initialDevelopmentCertificate)
        originalNetwork = ServerSettings(ip: initialIp, port: initialPort,
            turnServer: initialTurnServer, turnPort: initialTurnPort, turnUsername: initialTurnUsername,
            turnPassword: initialTurnPassword, turnUseTls: initialTurnUseTls, serverCertificateSha256: initialPin,
            allowDevelopmentCertificate: initialDevelopmentCertificate)
        self.onSave = onSave
        self.onResetIdentity = onResetIdentity
        self.onSaveIdentity = onSaveIdentity
    }
    public var body: some View {
        let screen = NSApp.keyWindow?.screen ?? NSScreen.main
        let height = min(480, max(260, (screen?.visibleFrame.height ?? 720) - 100))
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        VStack {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    if showNetwork {
                        Button(action: backToOverview) { Image(systemName: "chevron.left") }
                            .buttonStyle(.borderless).help(interfaceText("back_to_settings")).accessibilityLabel(interfaceText("back_to_settings"))
                    }
                    Text(interfaceText(showNetwork ? "network" : "settings")).font(.title2.weight(.semibold))
                }
                if !showNetwork {
                    HStack(spacing: 7) {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
                        TextField(interfaceText("search_settings"), text: $search)
                            .textFieldStyle(.plain)
                            .focused($searchFocused)
                            .accessibilityLabel(interfaceText("search_settings"))
                        if !search.isEmpty {
                            Button { search = ""; searchFocused = true } label: { Image(systemName: "xmark.circle.fill") }
                                .buttonStyle(.plain)
                                .help(interfaceText("clear_settings_search"))
                                .accessibilityLabel(interfaceText("clear_settings_search"))
                        }
                    }
                    .padding(8)
                    .background(colors.timeline, in: RoundedRectangle(cornerRadius: 7))
                    .overlay { RoundedRectangle(cornerRadius: 7).strokeBorder(colors.divider).allowsHitTesting(false) }
                }
                Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
                ZStack(alignment: .topLeading) {
                    ScrollViewReader { scroll in
                        ScrollView {
                            overview.id("overview")
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(3)
                        }
                        .onChange(of: search) { _ in
                            if !showNetwork { scroll.scrollTo("overview", anchor: .top) }
                        }
                    }
                    .opacity(showNetwork ? 0 : 1)
                    .disabled(showNetwork)
                    .allowsHitTesting(!showNetwork)
                    .accessibilityHidden(showNetwork)
                    ScrollViewReader { scroll in
                        ScrollView {
                            networkFields
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(3)
                        }
                        .task(id: networkTarget) {
                            guard showNetwork, let networkTarget else { return }
                            await Task.yield()
                            guard !Task.isCancelled else { return }
                            scroll.scrollTo(networkTarget, anchor: .top)
                            self.networkTarget = nil
                        }
                    }
                    .opacity(showNetwork ? 1 : 0)
                    .disabled(!showNetwork)
                    .allowsHitTesting(showNetwork)
                    .accessibilityHidden(!showNetwork)
                }
                Rectangle().fill(colors.divider).frame(height: 1).accessibilityHidden(true)
                HStack(spacing: 8) {
                    Spacer()
                    if showNetwork {
                        Button(interfaceText("cancel"), action: cancelNetwork)
                        Button(interfaceText("save"), action: saveNetwork)
                            .buttonStyle(.borderedProminent)
                            .disabled(!validSettings)
                            .keyboardShortcut(.defaultAction)
                    } else {
                        Button(interfaceText("close")) { isPresented = false }
                    }
                }
            }
        }
        .padding(18)
        .frame(width: 500, height: height)
        .background(colors.chrome)
        .overlay(NoticeView())
        .onExitCommand {
            if showNetwork { backToOverview() }
            else if !search.isEmpty { search = ""; searchFocused = true }
            else { isPresented = false }
        }
        .task(id: showNetwork) {
            guard !showNetwork, !searchWords.isEmpty else { return }
            await Task.yield()
            if !Task.isCancelled { searchFocused = true }
        }
        .onAppear { pendingRevocations = IdentityRevocationWorker.shared.pendingCount }
        .onReceive(NotificationCenter.default.publisher(for: IdentityRevocationWorker.pendingChanged).receive(on: RunLoop.main)) { _ in
            pendingRevocations = IdentityRevocationWorker.shared.pendingCount
        }
        .sheet(isPresented: $showProfile) {
            if let onSaveIdentity {
                IdentitySetupDialog(isPresented: $showProfile, initialNickname: nickname,
                                    initialAvatar: currentAvatarPath, editing: true) { name, avatar, remove, finished in
                    onSaveIdentity(name, avatar, remove) { saved in
                        if saved {
                            nickname = name.trimmingCharacters(in: .whitespacesAndNewlines)
                            currentAvatarPath = KeychainManager.shared.myAvatarPath
                        }
                        finished(saved)
                    }
                }
            }
        }
        .sheet(isPresented: $showPrivacy) { ReadReceiptPrivacyView() }
        .sheet(isPresented: $showNotifications) { MessageNotificationsView() }
        .sheet(isPresented: $showStorage) { AttachmentCacheView() }
        .sheet(isPresented: $showExportFolder) { AttachmentExportFolderView() }
        .sheet(isPresented: $showResetConfirm) {
            ConfirmationDialog(
                message: interfaceText("reset_warning_close"),
                onCancel: { showResetConfirm = false },
                onConfirm: { showResetConfirm = false; isPresented = false; onResetIdentity() }
            )
        }
    }

    private var overview: some View {
        let matches = matchingTopics
        let searching = !searchWords.isEmpty
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        return VStack(alignment: .leading, spacing: 8) {
            if matches.contains(.identity) {
                HStack(spacing: 12) {
                AvatarView(avatarPath: currentAvatarPath, fallbackText: nickname, size: 50, cornerRadius: 25)
                VStack(alignment: .leading, spacing: 5) {
                    Text(nickname.isEmpty ? String(myUuid.prefix(12)) : nickname).fontWeight(.semibold).lineLimit(1)
                    Text(myUuid).font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle).help(myUuid)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(myUuid, forType: .string)
                    NoticeCenter.shared.show(interfaceText("message_copied"))
                } label: { Image(systemName: "doc.on.doc") }
                    .help(interfaceText("copy_my_uuid")).accessibilityLabel(interfaceText("copy_my_uuid"))
                }
                .padding(10)
                .background(colors.sidebar, in: RoundedRectangle(cornerRadius: 8))
            }
            if matches.contains(.profile), onSaveIdentity != nil {
                settingRow(interfaceText("edit_profile"), icon: "person.crop.circle") { showProfile = true }
            }
            if !searching { Divider() }
            if matches.contains(.network) {
                settingRow(interfaceText("network"), icon: "network") { showNetwork = true }
            }
            if searching, matches.contains(.advanced) {
                settingRow(interfaceText("advanced_network"), icon: "network") {
                    openAdvanced(.advanced)
                }
            }
            if searching, matches.contains(.turn) {
                settingRow(interfaceText("turn_relay"), icon: "network") {
                    openAdvanced(.turn)
                }
            }
            if searching, matches.contains(.certificate) {
                settingRow(interfaceText("certificate_pin"), icon: "lock.shield") {
                    openAdvanced(.certificate)
                }
            }
            if matches.contains(.notifications) {
                settingRow(interfaceText("notification_settings"), icon: "bell") { showNotifications = true }
            }
            if matches.contains(.privacy) {
                settingRow(interfaceText("privacy"), icon: "lock.shield") { showPrivacy = true }
            }
            if matches.contains(.language) {
                Picker(selection: $language) {
                ForEach(AppLanguage.allCases, id: \.self) { option in
                    Text(interfaceText(option.labelKey)).tag(option)
                }
            } label: {
                Label(interfaceText("language"), systemImage: "globe")
            }
            .pickerStyle(.menu)
                .padding(.horizontal, 7)
            }
            if matches.contains(.theme) {
                Picker(selection: $theme) {
                ForEach(AppThemeMode.allCases, id: \.self) { mode in
                    Text(interfaceText(mode.labelKey)).tag(mode)
                }
            } label: {
                Label(interfaceText("theme"), systemImage: "circle.lefthalf.filled")
            }
            .pickerStyle(.menu)
                .padding(.horizontal, 7)
            }
            if matches.contains(.size) {
                Picker(selection: Binding(get: { MsgATTheme.chatTextPercentage(chatTextPercentage) },
                                      set: { chatTextPercentage = $0 })) {
                ForEach(MsgATTheme.chatTextPercentages, id: \.self) { percentage in
                    Text("\(percentage)%").tag(percentage)
                }
            } label: {
                Label(interfaceText("text_size"), systemImage: "textformat.size")
            }
            .pickerStyle(.menu)
                .padding(.horizontal, 7)
            }
            if matches.contains(.storage) {
                settingRow(interfaceText("storage_usage"), icon: "internaldrive") { showStorage = true }
            }
            if matches.contains(.export_folder) {
                settingRow(interfaceText("export_folder"), icon: "folder") { showExportFolder = true }
            }
            if !searching { Divider() }
            if matches.contains(.reset) {
                if pendingRevocations > 0 {
                    Text(interfaceText.format("pending_identity_revocations", Int64(pendingRevocations)))
                        .font(.caption).foregroundStyle(.secondary).padding(.horizontal, 7)
                }
                Button(interfaceText("reset_identity"), role: .destructive) { showResetConfirm = true }
                    .padding(.horizontal, 7)
            }
            if matches.isEmpty || (matches == [.profile] && onSaveIdentity == nil) {
                Text(interfaceText("no_settings_results")).foregroundStyle(.secondary).padding(8)
            }
        }
    }

    private var networkFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            SettingsField(label: interfaceText("server_address"), value: $serverIp,
                          error: hasServerAddress ? nil : interfaceText("network_address_required"))
            SettingsField(label: interfaceText("server_port"), value: $serverPort,
                          error: validPort(serverPort) ? nil : interfaceText("network_port_range"))
            DisclosureGroup(interfaceText("advanced_network"), isExpanded: $showAdvancedNetwork) {
                VStack(alignment: .leading, spacing: 10) {
                    Text(interfaceText("turn_relay")).font(.subheadline.weight(.medium))
                        .id(NetworkTarget.turn)
                    SettingsField(label: interfaceText("turn_server"), value: $turnServer)
                    SettingsField(label: interfaceText("turn_port"), value: $turnPort,
                                  error: hasTurnServer && !validPort(turnPort) ? interfaceText("network_port_range") : nil)
                    SettingsField(label: interfaceText("turn_username"), value: $turnUsername)
                    SettingsField(label: interfaceText("turn_password"), value: $turnPassword, secure: true)
                    Toggle(interfaceText("turn_tls"), isOn: $turnUseTls)
                    Divider()
                    SettingsField(label: interfaceText("certificate_pin"), value: $certificatePin,
                                  error: validPin ? nil : interfaceText("network_fingerprint_length"))
                        .id(NetworkTarget.certificate)
                    Toggle(interfaceText("allow_development_certificate"), isOn: $allowDevelopmentCertificate)
                }
                .padding(.top, 10)
            }
            .id(NetworkTarget.advanced)
        }
    }

    private func settingRow(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        return Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: icon).foregroundStyle(colors.selectionMark).frame(width: 20)
                Text(title)
                Spacer(minLength: 4)
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 8).padding(.vertical, 7)
            .background(colors.timeline, in: RoundedRectangle(cornerRadius: 6))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func openAdvanced(_ target: NetworkTarget) {
        showAdvancedNetwork = true
        networkTarget = target
        showNetwork = true
    }

    private func backToOverview() {
        networkTarget = nil
        showNetwork = false
    }

    private func cancelNetwork() {
        serverIp = originalNetwork.ip
        serverPort = originalNetwork.port
        turnServer = originalNetwork.turnServer
        turnPort = originalNetwork.turnPort
        turnUsername = originalNetwork.turnUsername
        turnPassword = originalNetwork.turnPassword
        turnUseTls = originalNetwork.turnUseTls
        certificatePin = originalNetwork.serverCertificateSha256
        allowDevelopmentCertificate = originalNetwork.allowDevelopmentCertificate
        backToOverview()
    }

    private func saveNetwork() {
        guard validSettings else { return }
        onSave(ServerSettings(ip: serverIp.trimmingCharacters(in: .whitespacesAndNewlines), port: serverPort,
            turnServer: turnServer, turnPort: turnPort, turnUsername: turnUsername, turnPassword: turnPassword,
            turnUseTls: turnUseTls, serverCertificateSha256: normalizedPin,
            allowDevelopmentCertificate: allowDevelopmentCertificate))
        isPresented = false
    }
}

@MainActor final class NoticeCenter: ObservableObject {
    static let shared = NoticeCenter()
    @Published var message: String?
    private var dismissTask: Task<Void, Never>?
    func show(_ text: String) {
        dismissTask?.cancel(); message = text
        dismissTask = Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if !Task.isCancelled { message = nil }
        }
    }
}
struct NoticeView: View {
    @ObservedObject private var center = NoticeCenter.shared
    var body: some View {
        VStack {
            Spacer()
            if let message = center.message {
                Text(message).font(.body).foregroundColor(.primary)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8)).padding(.bottom, 32)
            }
        }.allowsHitTesting(false)
    }
}

public struct IdentitySetupDialog: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.colorScheme) private var colorScheme
    @Binding var isPresented: Bool
    var initialNickname=""
    var initialAvatar:String?=nil
    var editing=false
    var onConfirm: (String, Data?, Bool, @escaping (Bool)->Void) -> Void
    @State private var removeAvatar=false
    @State private var loadedProfile=false
    @State private var saving=false
    @State private var failed = false
    @FocusState private var nicknameFocused: Bool
    @State private var nickname = ""
    @State private var avatarBytes:Data?
    @State private var cropSource: CropSource?
    private var hasPhoto: Bool { avatarBytes != nil || (!removeAvatar && !(initialAvatar ?? "").isEmpty) }
    private var photoAction: String { interfaceText(hasPhoto ? "profile_change_avatar" : "profile_choose_avatar") }
    private var cleanNickname: String { nickname.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var nicknameTooLong: Bool { cleanNickname.utf8.count > 256 }
    private var canConfirm: Bool { !cleanNickname.isEmpty && !nicknameTooLong }
    public var body: some View {
        let colors = MsgATTheme.messagingColors(for: colorScheme)
        VStack(spacing: 0) {
            Text(interfaceText(editing ? "edit_profile" : "profile_setup_title")).font(.headline)
                .frame(maxWidth: .infinity, alignment: .leading).padding(16)
            Divider()
            ScrollView {
                VStack(spacing: 14) {
                    Button(action: pickAvatar) {
                        Group {
                            if let bytes = avatarBytes, let image = NSImage(data: bytes) {
                                Image(nsImage: image).resizable().scaledToFill().frame(width: 94, height: 94).clipped()
                            } else if !removeAvatar, let initialAvatar, !initialAvatar.isEmpty {
                                AvatarView(avatarPath: initialAvatar, fallbackText: nickname, size: 94, cornerRadius: 47)
                            } else {
                                Image(systemName: "camera").font(.system(size: 25, weight: .medium))
                                    .foregroundStyle(Color.accentColor).frame(width: 94, height: 94)
                                    .background(Color.accentColor.opacity(0.12))
                            }
                        }
                        .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .help(photoAction)
                    .accessibilityLabel(photoAction)
                    ViewThatFits(in: .horizontal) {
                        HStack { photoActions }
                        VStack { photoActions }
                    }
                    DialogField(label: interfaceText("profile_nickname"), value: $nickname,
                                hasError: nicknameTooLong)
                        .focused($nicknameFocused)
                        .onChange(of: nickname) { value in
                            if value.count > 256 { nickname = String(value.prefix(256)) }
                            failed = false
                        }
                    if nicknameTooLong {
                        Text(interfaceText("profile_nickname_too_long")).foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    } else if failed {
                        Text(interfaceText("profile_save_failed")).foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(16)
            }
            Divider()
            HStack {
                Spacer()
                if editing {
                    Button(interfaceText("cancel")) { isPresented = false }.keyboardShortcut(.cancelAction)
                }
                Button(interfaceText(saving ? "saving" : "save")) {
                    guard !saving, canConfirm else { return }
                    saving = true
                    failed = false
                    onConfirm(cleanNickname, avatarBytes, removeAvatar) { saved in
                        saving = false
                        failed = !saved
                        if saved { isPresented = false }
                    }
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(!canConfirm || saving)
            }
            .padding(14)
        }
        .frame(width: 410, height: min(350, (NSScreen.main?.visibleFrame.height ?? 640) * 0.76))
        .background(colors.chrome)
        .interactiveDismissDisabled(!editing || saving)
        .disabled(saving)
        .onAppear {
            if !loadedProfile { nickname = initialNickname; loadedProfile = true; nicknameFocused = true }
        }
        .onDisappear {if var previous=avatarBytes {previous.resetBytes(in:0..<previous.count)};avatarBytes=nil}
        .sheet(item: $cropSource) { source in
            AvatarCropDialog(source:source.url,onCancel:{cropSource=nil}) {bytes in
                if var previous=avatarBytes {previous.resetBytes(in:0..<previous.count)}
                avatarBytes=bytes;removeAvatar=false;failed=false;cropSource=nil
            }
        }
    }
    @ViewBuilder private var photoActions: some View {
        Button(photoAction, action: pickAvatar).buttonStyle(.borderless)
        if hasPhoto {
            Button(interfaceText("profile_remove_avatar"), role: .destructive) {
                avatarBytes = nil
                removeAvatar = true
                failed = false
            }
            .buttonStyle(.borderless)
            .foregroundStyle(.red)
        }
    }

    private func pickAvatar() {
        let panel = NSOpenPanel(); panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false; panel.canChooseDirectories = false
        let receive: (NSApplication.ModalResponse) -> Void = { response in
            if response == .OK, let url = panel.url { cropSource = CropSource(url: url) }
        }
        if let owner = NSApp.keyWindow { panel.beginSheetModal(for: owner, completionHandler: receive) }
        else { panel.begin(completionHandler: receive) }
    }
}

private struct CropSource: Identifiable {
    let id = UUID()
    let url: URL
}
private struct AvatarCropDialog: View {
    @Environment(\.interfaceStrings) private var interfaceText
    let source: URL
    let onCancel: () -> Void
    let onConfirm: (Data) -> Void
    @State private var zoom: Double = 1
    @State private var offset = CGSize.zero
    @State private var dragOrigin = CGSize.zero
    @State private var image:NSImage?
    @State private var loadError=false
    var body: some View {
        DialogSurface {
            VStack(spacing: 16) {
                Text(interfaceText("crop_avatar")).font(.headline).frame(maxWidth: .infinity, alignment: .leading)
                if let image {
                    let ratio = max(300 / image.size.width, 300 / image.size.height)
                    ZStack {
                        Color.black
                        Image(nsImage: image).resizable().frame(width: image.size.width * ratio * zoom, height: image.size.height * ratio * zoom).offset(offset)
                    }.frame(width: 300, height: 300).clipShape(Circle())
                    .gesture(DragGesture().onChanged { value in
                        offset = clamped(CGSize(width: dragOrigin.width + value.translation.width, height: dragOrigin.height + value.translation.height), image: image, ratio: ratio)
                    }.onEnded { _ in dragOrigin = offset })
                    Slider(value: $zoom, in: 1...5).onChange(of: zoom) { _ in offset = clamped(offset, image: image, ratio: ratio); dragOrigin = offset }
                } else {if loadError {Text(interfaceText("profile_image_unavailable"))} else {ProgressView()}}
                HStack {
                    Spacer()
                    Button(interfaceText("cancel"), action: onCancel).keyboardShortcut(.cancelAction)
                    Button(interfaceText("use_avatar"), action: save).buttonStyle(.borderedProminent)
                        .keyboardShortcut(.defaultAction).disabled(image == nil)
                }
            }
        }.frame(width:400)
        .task(id:source){
            do {let loaded=try await EncryptedMediaWork.run {() throws -> NSImage in
                let access=source.startAccessingSecurityScopedResource();defer {if access {source.stopAccessingSecurityScopedResource()}}
                guard (try source.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? Int.max)<=16*1024*1024,
                      let image=MediaStorageManager.shared.generateImageThumbnail(fileUrl:source,maxDimension:2048) else {throw CocoaError(.fileReadCorruptFile)}
                return image
            };if !Task.isCancelled {image=loaded}}
            catch {if !Task.isCancelled {loadError=true}}
        }
    }
    private func clamped(_ value: CGSize, image: NSImage, ratio: CGFloat) -> CGSize {
        let x = max(0, (image.size.width * ratio * zoom - 300) / 2)
        let y = max(0, (image.size.height * ratio * zoom - 300) / 2)
        return CGSize(width: min(max(value.width, -x), x), height: min(max(value.height, -y), y))
    }
    private func save() {
        guard let image, let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 512, pixelsHigh: 512, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0), let context = NSGraphicsContext(bitmapImageRep: bitmap) else { return }
        let ratio = max(300 / image.size.width, 300 / image.size.height) * zoom
        let side = 300 / ratio
        let crop = CGRect(x: (image.size.width - side) / 2 - offset.width / ratio,
                          y: (image.size.height - side) / 2 + offset.height / ratio, width: side, height: side)
        NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = context
        image.draw(in: CGRect(x: 0, y: 0, width: 512, height: 512), from: crop, operation: .copy, fraction: 1)
        NSGraphicsContext.restoreGraphicsState()
        guard let bytes=bitmap.representation(using:.jpeg,properties:[.compressionFactor:0.82]),bytes.count<=512*1024 else {NoticeCenter.shared.show(interfaceText("profile_crop_failed"));return}
        onConfirm(bytes)
    }
}


private struct MessageNotificationsView: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.dismiss) private var dismiss
    @State private var enabled = false
    @State private var sound = true
    @State private var previews = false
    @State private var loaded = false
    @State private var busy = false
    @State private var mounted = false
    @State private var error: String?
    @State private var systemAuthorization: UNAuthorizationStatus?
    @State private var systemPreviews: UNShowPreviewsSetting?
    @State private var systemSettingsRevision = 0
    @State private var systemSettingsFailed = false

    var body: some View {
        SettingsSheetLayout(title: interfaceText("notification_settings"), width: 440) {
            VStack(alignment: .leading, spacing: 12) {
                if !loaded && busy { ProgressView().controlSize(.small) }
                Toggle(interfaceText("message_alerts"), isOn: $enabled).disabled(!loaded || busy)
                Toggle(interfaceText("message_alert_sound"), isOn: $sound).disabled(!loaded || !enabled || busy)
                Toggle(interfaceText("notification_previews"), isOn: $previews).disabled(!loaded || !enabled || busy)
                Divider()
                if systemAuthorization == .denied || systemAuthorization == .notDetermined {
                    Text(interfaceText("system_notifications_disabled")).foregroundStyle(.secondary)
                }
                if enabled && previews, let systemPreviews {
                    Text(interfaceText(systemPreviews == .whenAuthenticated ? "unlocked_previews" : "mac_unlocked_previews"))
                        .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                Button(interfaceText("open_notification_system_settings"), action: openSystemSettings).disabled(busy)
                if systemSettingsFailed { Text(interfaceText("notification_system_open_failed")).foregroundStyle(.red) }
                if let error { Text(interfaceText(error)).foregroundStyle(.red) }
            }
        } footer: {
            HStack {
                if !loaded && error != nil { Button(interfaceText("retry"), action: load) }
                Spacer()
                Button(interfaceText("cancel")) { dismiss() }.disabled(busy).keyboardShortcut(.cancelAction)
                Button(interfaceText(busy && loaded ? "saving" : "save"), action: save)
                    .buttonStyle(.borderedProminent).disabled(!loaded || busy).keyboardShortcut(.defaultAction)
            }
        }
        .interactiveDismissDisabled(busy)
        .onAppear { mounted = true; load() }
        .onDisappear { mounted = false }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification).receive(on: RunLoop.main)) { _ in
            systemSettingsRevision += 1
        }
        .task(id: systemSettingsRevision) {
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            guard !Task.isCancelled else { return }
            systemAuthorization = settings.authorizationStatus
            systemPreviews = settings.showPreviewsSetting
        }
    }

    private func openSystemSettings() {
        let workspace = NSWorkspace.shared
        guard let url = workspace.urlForApplication(withBundleIdentifier: "com.apple.systempreferences") else {
            systemSettingsFailed = true
            return
        }
        systemSettingsFailed = !workspace.open(url)
    }

    private func load() {
        guard !busy else { return }
        busy = true
        SecureMessaging.shared.messageNotificationSettings { settings in
            Task { @MainActor in
                guard mounted else { return }
                busy = false
                guard let settings else { error = "notification_load_error"; return }
                enabled = settings.enabled
                sound = settings.playSound
                previews = settings.showPreviews
                loaded = true
                error = nil
            }
        }
    }

    private func save() {
        guard loaded, !busy else { return }
        busy = true
        SecureMessaging.shared.setMessageNotificationSettings(.init(enabled: enabled, playSound: sound, showPreviews: previews)) { saved in
            Task { @MainActor in
                guard mounted else { return }
                busy = false
                if saved { dismiss() } else { error = "notification_save_error" }
            }
        }
    }
}

struct ReadReceiptPrivacyView:View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.dismiss) private var dismiss
    @State private var enabled=false
    @State private var online=false
    @State private var typing=false
    @State private var autoDownload=true
    @State private var loaded=false
    @State private var busy=false
    @State private var error:String?
    @State private var mounted=false
    var body:some View {
        SettingsSheetLayout(title: interfaceText("privacy"), width: 440) {
            VStack(alignment: .leading, spacing: 12) {
                if !loaded && busy { ProgressView().controlSize(.small) }
                Toggle(interfaceText("read_receipts"),isOn:$enabled).disabled(!loaded || busy)
                Toggle(interfaceText("share_online"),isOn:$online).disabled(!loaded || busy)
                Toggle(interfaceText("share_typing"),isOn:$typing).disabled(!loaded || busy)
                Toggle(interfaceText("automatic_downloads"), isOn: $autoDownload).disabled(!loaded || busy)
                Text(interfaceText("activity_privacy_note")).foregroundColor(.secondary).fixedSize(horizontal:false,vertical:true)
                Text(interfaceText("read_privacy_note")).foregroundColor(.secondary).fixedSize(horizontal:false,vertical:true)
                if let error {Text(error).foregroundColor(.red)}
            }
        } footer: {
            HStack {
                if !loaded, error != nil { Button(interfaceText("retry"), action: load) }
                Spacer()
                Button(interfaceText("cancel")) { dismiss() }.disabled(busy).keyboardShortcut(.cancelAction)
                Button(interfaceText(busy && loaded ? "saving" : "save"), action: save)
                    .buttonStyle(.borderedProminent).disabled(!loaded || busy).keyboardShortcut(.defaultAction)
            }
        }
        .interactiveDismissDisabled(busy)
            .onAppear {mounted=true;load()}.onDisappear {mounted=false}
    }
    private func load(){
        guard !busy else {return};busy=true
        Task { @MainActor in
            let value:Bool?=await withCheckedContinuation{continuation in SecureMessaging.shared.readReceiptsEnabled{continuation.resume(returning:$0)}}
            let activity:ActivityPrivacy?=await withCheckedContinuation{continuation in SecureMessaging.shared.activityPrivacy{continuation.resume(returning:$0)}}
            let downloads:Bool?=await withCheckedContinuation { continuation in
                SecureMessaging.shared.automaticAttachmentDownloads { continuation.resume(returning: $0) }
            }
            guard mounted else {return};busy=false
            if let value,let activity,let downloads {enabled=value;online=activity.online;typing=activity.typing;autoDownload=downloads;loaded=true;error=nil}else {error=interfaceText("privacy_load_error")}
        }
    }
    private func save(){
        guard loaded,!busy else {return};busy=true
        SecureMessaging.shared.setReadReceiptsEnabled(enabled,activity:ActivityPrivacy(online:online,typing:typing),autoDownload:autoDownload) {saved in Task { @MainActor in
            guard mounted else {return};busy=false
            if saved {dismiss()}else {error=interfaceText("privacy_save_error")}
        }}
    }
}

struct MessageExpirationView: View {
    @Environment(\.interfaceStrings) private var interfaceText
    let peer: String
    @Environment(\.dismiss) private var dismiss
    @State private var settings = MessageRetentionSettings(outgoing: 0, local: 0)
    @State private var original = MessageRetentionSettings(outgoing: 0, local: 0)
    @State private var confirming = false
    @State private var loaded = false
    @State private var saving = false
    @State private var error: String?

    var body: some View {
        DialogSurface {
            VStack(alignment: .leading, spacing: 16) {
                Text(interfaceText("message_retention")).font(.title2).bold()
                Picker(interfaceText("auto_delete_both"), selection: $settings.outgoing) {
                    ForEach(MessageExpirationStore.choices.indices, id: \.self) { index in
                        let choice = MessageExpirationStore.choices[index]
                        Text(interfaceText(choice.labelKey)).tag(choice.duration)
                    }
                }.disabled(!loaded || saving)
                Text(interfaceText("new_outgoing_only")).font(.caption).foregroundStyle(.secondary)
                Picker(interfaceText("local_retention"), selection: $settings.local) {
                    ForEach(MessageExpirationStore.retentionChoices.indices, id: \.self) { index in
                        let choice = MessageExpirationStore.retentionChoices[index]
                        Text(interfaceText(choice.labelKey)).tag(choice.duration)
                    }
                }.disabled(!loaded || saving)
                if let error { Text(error).foregroundColor(.red) }
                HStack {
                    Spacer()
                    Button(interfaceText("cancel")) { dismiss() }.keyboardShortcut(.cancelAction).disabled(saving)
                    Button(interfaceText("save")) {
                        if settings.shortensLocalHistory(original) { confirming = true }
                        else { save() }
                    }.keyboardShortcut(.defaultAction).disabled(!loaded || saving)
                }
            }
        }
        .frame(width: 420)
        .interactiveDismissDisabled(saving)
        .task(id: peer) {
            let current: MessageRetentionSettings? = await withCheckedContinuation { continuation in
                SecureMessaging.shared.messageRetention(peer: peer) { continuation.resume(returning: $0) }
            }
            guard !Task.isCancelled else { return }
            if let current {
                settings = current
                original = current
                loaded = true
            } else { error = interfaceText("retention_load_error") }
        }
        .confirmationDialog(interfaceText("shorten_history"), isPresented: $confirming, titleVisibility: .visible) {
            Button(interfaceText("apply_and_delete"), role: .destructive, action: save)
            Button(interfaceText("cancel"), role: .cancel) {}
        } message: {
            Text(interfaceText("shorten_history_warning"))
        }
    }

    private func save() {
        saving = true
        Task { @MainActor in
            let saved: Bool = await withCheckedContinuation { continuation in
                SecureMessaging.shared.setMessageRetention(peer: peer, settings: settings) {
                    continuation.resume(returning: $0)
                }
            }
            saving = false
            if saved { dismiss() }
            else { error = interfaceText("retention_save_error") }
        }
    }
}

private struct AttachmentExportFolderView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.interfaceStrings) private var interfaceText
    @State private var path: String?
    @State private var loading = true
    @State private var saving = false
    @State private var choosing = false
    @State private var failed = false

    var body: some View {
        DialogSurface {
            VStack(alignment: .leading, spacing: 12) {
                Text(interfaceText("export_folder")).font(.headline)
                if loading {
                    ProgressView().controlSize(.small)
                } else if !failed || path != nil {
                    Text(path ?? interfaceText("export_folder_default"))
                        .font(.callout).foregroundStyle(.secondary).lineLimit(2).truncationMode(.middle)
                }
                if failed { Text(interfaceText("export_folder_failed")).foregroundStyle(.red) }
                HStack {
                    Button(interfaceText("export_folder_default")) { save(nil) }
                        .disabled(loading || saving || path == nil)
                    Spacer()
                    Button(interfaceText("close")) { dismiss() }.keyboardShortcut(.cancelAction)
                    Button(interfaceText("export_folder_choose")) { choosing = true }
                        .keyboardShortcut(.defaultAction).disabled(loading || saving)
                }
            }
        }
        .frame(width: 455)
        .interactiveDismissDisabled(saving)
        .task {
            do { path = try await EncryptedMediaWork.run { try AttachmentExportPreferences.folder() } }
            catch { failed = true }
            loading = false
        }
        .fileImporter(isPresented: $choosing, allowedContentTypes: [.folder]) { result in
            if case let .success(url) = result, url.isFileURL { save(url.path) }
        }
    }

    private func save(_ value: String?) {
        guard !saving, AppLockSession.shared.canShowContent else { return }
        saving = true
        failed = false
        Task {
            do {
                try await EncryptedMediaWork.run { try AttachmentExportPreferences.setFolder(value) }
                path = value
            } catch { failed = true }
            saving = false
        }
    }
}

struct AttachmentCacheView: View {
    @Environment(\.interfaceStrings) private var interfaceText
    @Environment(\.dismiss) private var dismiss
    @State private var usage: AttachmentCacheUsage?
    @State private var pendingClear: [AttachmentCacheCategory: Int64]?
    @State private var excluded = Set<AttachmentCacheCategory>()
    @State private var busy = false
    @State private var error: String?
    @State private var usageLoadFailed = false
    @State private var revision = 0

    private var selection: [AttachmentCacheCategory: Int64] {
        (usage?.bytesByCategory ?? [:]).filter { !excluded.contains($0.key) }
    }

    var body: some View {
        SettingsSheetLayout(title: interfaceText("storage_usage"), width: 440) {
            VStack(alignment: .leading, spacing: 14) {
                if let usage {
                    LabeledContent(interfaceText("cache_received"), value: size(usage.bytes))
                    ForEach(AttachmentCacheCategory.allCases, id: \.self) { category in
                        if let bytes = usage.bytesByCategory[category] {
                            Toggle(isOn: Binding(
                                get: { !excluded.contains(category) },
                                set: { checked in
                                    if checked { excluded.remove(category) }
                                    else { excluded.insert(category) }
                                }
                            )) {
                                LabeledContent(categoryTitle(category), value: size(bytes))
                            }
                            .toggleStyle(.checkbox)
                            .disabled(busy)
                        }
                    }
                    if usage.pendingBytes > 0 {
                        Text(interfaceText.format("cache_removing", size(usage.pendingBytes))).foregroundStyle(.secondary)
                    }
                    Menu(interfaceText.format("cache_auto_limit", limitLabel(usage.automaticLimitBytes))) {
                        ForEach(EncryptedObjectLifecycle.automaticLimits, id: \.self) { bytes in
                            Button(limitLabel(bytes)) { if bytes != usage.automaticLimitBytes { setLimit(bytes) } }
                        }
                    }.disabled(busy)
                    Text(interfaceText("cache_limit_note")).font(.caption).foregroundStyle(.secondary)
                    if usage.automaticLimitBytes > 0 {
                        ProgressView(value: Double(usage.automaticLimitBytes - usage.automaticBudgetRemainingBytes),
                                     total: Double(usage.automaticLimitBytes)) {
                            Text(interfaceText.format("cache_auto_remaining", size(usage.automaticBudgetRemainingBytes)))
                                .font(.caption)
                        }
                    }
                } else if !usageLoadFailed {
                    ProgressView().controlSize(.small)
                }
                if usageLoadFailed {
                    Text(interfaceText("cache_load_error")).foregroundStyle(.red)
                    Button(interfaceText("retry")) { usageLoadFailed = false; revision += 1 }.disabled(busy)
                }
                if let error { Text(error).foregroundStyle(.red) }
            }
        } footer: {
            HStack {
                Button(interfaceText("cache_clear")) { pendingClear = selection }
                    .disabled(selection.isEmpty || busy)
                Spacer()
                Button(interfaceText("close")) { dismiss() }.keyboardShortcut(.cancelAction).disabled(busy)
            }
        }
        .interactiveDismissDisabled(busy)
        .onReceive(NotificationCenter.default.publisher(for: AttachmentQueue.progressChanged)
            .merge(with: NotificationCenter.default.publisher(for: AttachmentQueue.previewProgressChanged))
            .throttle(for: .seconds(2), scheduler: RunLoop.main, latest: true)) { _ in
            revision += 1
        }
        .task(id: revision) {
            while !Task.isCancelled {
                let current: AttachmentCacheUsage? = await withCheckedContinuation { continuation in
                    SecureMessaging.shared.attachmentCacheUsage { continuation.resume(returning: $0) }
                }
                guard !Task.isCancelled else { return }
                guard let current else { usageLoadFailed = true; return }
                usage = current
                usageLoadFailed = false
                if current.pendingBytes == 0 { return }
                try? await Task.sleep(for: .seconds(2))
            }
        }
        .confirmationDialog(interfaceText("cache_clear_title"), isPresented: Binding(
            get: { pendingClear != nil }, set: { if !$0 { pendingClear = nil } }
        ), titleVisibility: .visible, presenting: pendingClear) { selection in
            Button(interfaceText("cache_clear"), role: .destructive) { clear(Set(selection.keys)) }
            Button(interfaceText("cancel"), role: .cancel) { pendingClear = nil }
        } message: { selection in
            Text(interfaceText.format("cache_clear_selected_size", size(selection.values.reduce(0, +))) +
                 "\n" + interfaceText("cache_clear_warning"))
        }
    }

    private func categoryTitle(_ category: AttachmentCacheCategory) -> String {
        switch category {
        case .photos: return interfaceText("shared_media_photos")
        case .videos: return interfaceText("shared_media_videos")
        case .audio: return interfaceText("shared_media_audio")
        case .files: return interfaceText("shared_media_files")
        case .linkPreviews: return interfaceText("cache_link_previews")
        }
    }

    private func size(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private func limitLabel(_ bytes: Int64) -> String {
        bytes == 0 ? interfaceText("cache_no_limit") : size(bytes)
    }

    private func setLimit(_ bytes: Int64) {
        busy = true
        error = nil
        Task { @MainActor in
            let saved: Bool = await withCheckedContinuation { continuation in
                SecureMessaging.shared.setAutomaticCacheLimit(bytes) { continuation.resume(returning: $0) }
            }
            busy = false
            if saved { revision += 1 }
            else { error = interfaceText("cache_limit_error") }
        }
    }

    private func clear(_ categories: Set<AttachmentCacheCategory>) {
        busy = true
        pendingClear = nil
        error = nil
        Task { @MainActor in
            let result: Bool = await withCheckedContinuation { continuation in
                SecureMessaging.shared.clearAttachmentCache(categories: categories) { continuation.resume(returning: $0) }
            }
            busy = false
            if result { revision += 1 }
            else { error = interfaceText("cache_clear_error") }
        }
    }
}
