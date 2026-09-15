import SwiftUI
import AppKit
import UniformTypeIdentifiers

// MARK: - Add Conversation Dialog
public struct AddConversationDialog: View {
    @Binding var isPresented: Bool
    var myUuid: String
    var onConfirm: (String) -> Void

    @State private var targetUuid = ""
    @State private var error: String?

    public var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Add Friend")
                .font(.headline)

            VStack(alignment: .leading, spacing: 6) {
                Text("Target UUID:")
                    .font(.caption)
                    .foregroundColor(.secondary)
                TextField("Enter 64-char UUID", text: $targetUuid)
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: targetUuid) { _ in error = nil }
                if let error {
                    Text(error)
                        .font(.caption)
                        .foregroundColor(.red)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            HStack {
                Spacer()
                Button("Cancel") {
                    isPresented = false
                }
                .keyboardShortcut(.cancelAction)

                Button("Add") {
                    let uuid = targetUuid.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                    guard uuid.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
                        error = "A friend UUID must contain exactly 64 hexadecimal characters."
                        return
                    }
                    guard uuid != myUuid.lowercased() else {
                        error = "You cannot add your own UUID."
                        return
                    }
                    onConfirm(uuid)
                    isPresented = false
                }
                .buttonStyle(.borderedProminent)
                .disabled(targetUuid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 420)
    }
}

// MARK: - Friend Requests Dialog
public struct FriendRequestsDialog: View {
    @Binding var isPresented: Bool
    let requests: [FriendRequest]
    var onAccept: (String, String, String) -> Void
    var onDecline: (String) -> Void

    public var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("Friend Requests")
                    .font(.headline)
                Spacer()
                Button("Close") {
                    isPresented = false
                }
            }

            if requests.isEmpty {
                VStack(spacing: 8) {
                    Spacer()
                    Image(systemName: "person.crop.circle.badge.checkmark")
                        .font(.system(size: 40))
                        .foregroundColor(.secondary)
                    Text("No pending friend requests")
                        .foregroundColor(.secondary)
                    Spacer()
                }
                .frame(maxWidth: .infinity, minHeight: 200)
            } else {
                List(requests) { req in
                    HStack(spacing: 12) {
                        AvatarView(
                            avatarPath: req.avatarPath,
                            fallbackText: req.nickname,
                            size: 40,
                            cornerRadius: 12
                        )

                        VStack(alignment: .leading, spacing: 2) {
                            Text(req.nickname)
                                .font(.body)
                                .fontWeight(.medium)
                            Text(String(req.senderUuid.prefix(16)) + "...")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }

                        Spacer()

                        Button("Decline") {
                            onDecline(req.senderUuid)
                        }
                        .buttonStyle(.bordered)

                        Button("Accept") {
                            onAccept(req.senderUuid, req.nickname, req.avatarPath)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding(.vertical, 4)
                }
                .frame(minHeight: 250, maxHeight: 400)
            }
        }
        .padding(20)
        .frame(width: 480)
    }
}

// MARK: - Settings Dialog

public struct ServerSettings {
    public var ip: String
    public var port: String
    public var turnServer: String
    public var turnPort: String
    public var turnUsername: String
    public var turnPassword: String
    public var turnUseTls: Bool
    public var serverCertificateSha256: String = ""
    public var allowDevelopmentCertificate: Bool = true

    public init(ip: String, port: String, turnServer: String, turnPort: String, turnUsername: String, turnPassword: String, turnUseTls: Bool, serverCertificateSha256: String = "", allowDevelopmentCertificate: Bool = true) {
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
    @Binding var isPresented: Bool
    var onSave: (ServerSettings) -> Void
    var onResetIdentity: () -> Void

    @State private var serverIp: String
    @State private var serverPort: String
    @State private var nickname: String
    @State private var myUuid: String
    @State private var currentAvatarPath: String?
    @State private var showResetConfirm = false
    @State private var copiedUuid = false
    @State private var turnServer: String
    @State private var turnPort: String
    @State private var turnUsername: String
    @State private var turnPassword: String
    @State private var turnUseTls: Bool
    @State private var certificatePin = KeychainManager.shared.serverCertificateSha256 ?? ""
    @State private var allowDevelopmentCertificate = KeychainManager.shared.allowDevelopmentCertificate
    @State private var showAdvancedNetwork = false

    private var validationError: String? {
        let trimmedIp = serverIp.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedIp.isEmpty else { return "Server address is required." }
        guard let port = Int(serverPort), (1...65535).contains(port) else {
            return "Server port must be between 1 and 65535."
        }
        if !turnServer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           !turnPort.isEmpty,
           (Int(turnPort).map { !(1...65535).contains($0) } ?? true) {
            return "TURN port must be between 1 and 65535."
        }
        let pin = certificatePin.replacingOccurrences(of: ":", with: "").replacingOccurrences(of: " ", with: "")
        if !pin.isEmpty && pin.range(of: "^[A-Fa-f0-9]{64}$", options: .regularExpression) == nil {
            return "The TLS SHA-256 pin must contain 64 hexadecimal characters."
        }
        return nil
    }

    public init(
        isPresented: Binding<Bool>,
        initialIp: String,
        initialPort: String,
        initialNickname: String,
        myUuid: String,
        initialTurnServer: String = "",
        initialTurnPort: String = "3478",
        initialTurnUsername: String = "",
        initialTurnPassword: String = "",
        initialTurnUseTls: Bool = false,
        onSave: @escaping (ServerSettings) -> Void,
        onResetIdentity: @escaping () -> Void
    ) {
        self._isPresented = isPresented
        self._serverIp = State(initialValue: initialIp)
        self._serverPort = State(initialValue: initialPort)
        self._nickname = State(initialValue: initialNickname)
        self._myUuid = State(initialValue: myUuid)
        self._currentAvatarPath = State(initialValue: KeychainManager.shared.myAvatarPath)
        self._turnServer = State(initialValue: initialTurnServer)
        self._turnPort = State(initialValue: initialTurnPort)
        self._turnUsername = State(initialValue: initialTurnUsername)
        self._turnPassword = State(initialValue: initialTurnPassword)
        self._turnUseTls = State(initialValue: initialTurnUseTls)
        self.onSave = onSave
        self.onResetIdentity = onResetIdentity
    }

    private var dialogHeight: CGFloat {
        let available = (NSApp.keyWindow?.screen ?? NSScreen.main)?.visibleFrame.height ?? 720
        return min(560, max(300, available - 160))
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Settings")
                .font(.headline)
                .padding(20)
            Divider()
            ScrollView(.vertical, showsIndicators: true) {
                VStack(alignment: .leading, spacing: 18) {
            // Profile & Avatar Section (Read-only)
            GroupBox(label: Text("My Profile").fontWeight(.medium)) {
                HStack(spacing: 16) {
                    AvatarView(
                        avatarPath: currentAvatarPath,
                        fallbackText: nickname.isEmpty ? "Me" : nickname,
                        size: 56,
                        cornerRadius: 14
                    )

                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Nickname:")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Text(nickname.isEmpty ? "Unknown" : nickname)
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(.primary)
                        }

                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("My UUID:")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                Text(myUuid)
                                    .font(.system(.caption2, design: .monospaced))
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                            Spacer()
                            Button(copiedUuid ? "Copied!" : "Copy") {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(myUuid, forType: .string)
                                copiedUuid = true
                                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                                    copiedUuid = false
                                }
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    }
                }
                .padding(8)
            }

            // Server Section
            GroupBox(label: Text("Server Relay Settings").fontWeight(.medium)) {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Server IP:")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        TextField("e.g. 192.168.1.100 or domain", text: $serverIp)
                            .textFieldStyle(.roundedBorder)
                    }

                    HStack {
                        Text("Server Port:")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        TextField("e.g. 8443", text: $serverPort)
                            .textFieldStyle(.roundedBorder)
                    }
                }
                .padding(8)
            }

            DisclosureGroup("Advanced Network Settings", isExpanded: $showAdvancedNetwork) {
                GroupBox(label: Text("TURN Relay (Optional — P2P fallback)").fontWeight(.medium)) {
                    VStack(alignment: .leading, spacing: 8) {
                    Text("Leave empty for pure P2P. TURN is only used as a last resort when direct paths fail.")
                        .font(.caption2)
                        .foregroundColor(.secondary)

                    HStack {
                        Text("Server:")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        TextField("e.g. turn.example.com", text: $turnServer)
                            .textFieldStyle(.roundedBorder)
                    }
                    HStack {
                        Text("Port:")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        TextField("e.g. 3478", text: $turnPort)
                            .textFieldStyle(.roundedBorder)
                    }
                    HStack {
                        Text("Username:")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        TextField("TURN username", text: $turnUsername)
                            .textFieldStyle(.roundedBorder)
                    }
                    HStack {
                        Text("Password:")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        SecureField("TURN password", text: $turnPassword)
                            .textFieldStyle(.roundedBorder)
                    }
                    TextField("TLS SHA-256 pin", text: $certificatePin).textFieldStyle(.roundedBorder)
                    Toggle("Allow untrusted development certificate", isOn: $allowDevelopmentCertificate)
                    Toggle("Use TLS (turns://)", isOn: $turnUseTls)
                        .toggleStyle(.switch)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    }
                    .padding(8)
                }
            }

            // Danger Zone
            GroupBox(label: Text("Danger Zone").foregroundColor(.red).fontWeight(.medium)) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Burn Identity & Wipe Vault")
                            .font(.subheadline)
                            .fontWeight(.medium)
                        Text("Irreversibly deletes local cryptographic keys, avatar, and all chat history.")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    Button("Burn Identity") {
                        showResetConfirm = true
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                }
                .padding(8)
            }

                }
                .padding(20)
            }
            .frame(maxHeight: .infinity)
            Divider()
            VStack(alignment: .leading, spacing: 8) {
                if let validationError {
                    Text(validationError)
                        .font(.caption)
                        .foregroundColor(.red)
                }
            HStack {
                Spacer()
                Button("Cancel") {
                    isPresented = false
                }
                .keyboardShortcut(.cancelAction)

                Button("Save & Apply") {
                    onSave(ServerSettings(
                        ip: serverIp.trimmingCharacters(in: .whitespacesAndNewlines),
                        port: serverPort,
                        turnServer: turnServer.trimmingCharacters(in: .whitespacesAndNewlines),
                        turnPort: turnPort,
                        turnUsername: turnUsername,
                        turnPassword: turnPassword,
                        turnUseTls: turnUseTls,
                        serverCertificateSha256: certificatePin.replacingOccurrences(of: ":", with: "").replacingOccurrences(of: " ", with: "").lowercased(),
                        allowDevelopmentCertificate: allowDevelopmentCertificate
                    ))
                    isPresented = false
                }
                .buttonStyle(.borderedProminent)
                .disabled(validationError != nil)
                .keyboardShortcut(.defaultAction)
            }
            }
            .padding(20)
        }
        .frame(width: 520, height: dialogHeight)
        .confirmationDialog(
            "Are you sure you want to burn this identity?",
            isPresented: $showResetConfirm,
            titleVisibility: .visible
        ) {
            Button("Yes, Wipe All Data", role: .destructive) {
                onResetIdentity()
                isPresented = false
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will permanently delete your private key, conversations, messages, and restart identity.")
        }
    }
}

// MARK: - Identity Setup Dialog (First launch)
public struct IdentitySetupDialog: View {
    @Binding var isPresented: Bool
    var onConfirm: (String, URL?) -> Void

    @State private var nickname = ""
    @State private var selectedAvatarUrl: URL? = nil

    public var body: some View {
        VStack(alignment: .center, spacing: 18) {
            Text("Welcome to MsgAT")
                .font(.title2)
                .fontWeight(.bold)

            Text("Set up your cryptographic display profile.")
                .font(.subheadline)
                .foregroundColor(.secondary)

            // Avatar picker
            ZStack(alignment: .bottomTrailing) {
                if let url = selectedAvatarUrl, let img = NSImage(contentsOf: url) {
                    Image(nsImage: img)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 72, height: 72)
                        .clipShape(RoundedRectangle(cornerRadius: 18))
                } else {
                    RoundedRectangle(cornerRadius: 18)
                        .fill(Color.accentColor.opacity(0.2))
                        .frame(width: 72, height: 72)
                        .overlay(
                            Image(systemName: "person.fill")
                                .font(.system(size: 32))
                                .foregroundColor(.accentColor)
                        )
                }

                Button(action: pickAvatar) {
                    Image(systemName: "plus.circle.fill")
                        .font(.system(size: 20))
                        .foregroundColor(.accentColor)
                        .background(Color.white.clipShape(Circle()))
                }
                .buttonStyle(.plain)
                .offset(x: 4, y: 4)
            }
            .onTapGesture {
                pickAvatar()
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("Your Nickname:")
                    .font(.caption)
                    .foregroundColor(.secondary)
                TextField("Nickname (e.g. Alice)", text: $nickname)
                    .textFieldStyle(.roundedBorder)
            }
            .frame(width: 280)

            HStack {
                Spacer()
                Button("Get Started") {
                    let trimmed = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty {
                        onConfirm(trimmed, selectedAvatarUrl)
                        isPresented = false
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || selectedAvatarUrl == nil)
                .keyboardShortcut(.defaultAction)
            }
            .frame(width: 280)
        }
        .padding(26)
        .frame(width: 380)
    }

    private func pickAvatar() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.begin { response in
            if response == .OK, let url = panel.url {
                selectedAvatarUrl = url
            }
        }
    }
}
