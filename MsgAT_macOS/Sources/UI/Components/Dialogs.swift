import SwiftUI
import AppKit
import UniformTypeIdentifiers

struct DialogSurface<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        content.padding(20)
            .background(Color(NSColor.windowBackgroundColor))
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
    var body: some View {
        HStack(spacing: 12) {
            Text(label).font(.caption).foregroundColor(.secondary)
                .frame(width: 100, alignment: .trailing)
            Group {
                if secure { SecureField(label, text: $value) }
                else { TextField(label, text: $value) }
            }
            .textFieldStyle(.roundedBorder).accessibilityLabel(label)
        }
    }
}

struct ConfirmationDialog: View {
    let message: String
    var title = "Warning"
    var confirmColor: Color = .red
    let onCancel: () -> Void
    let onConfirm: () -> Void
    var body: some View {
        DialogSurface {
            VStack(alignment: .leading, spacing: 24) {
                Text(title).font(.headline)
                Text(message).font(.system(size: 16)).fixedSize(horizontal: false, vertical: true)
                HStack {
                    Spacer()
                    Button("Cancel", action: onCancel).buttonStyle(.bordered)
                    Button("Confirm", action: onConfirm).buttonStyle(.borderedProminent).tint(confirmColor)
                }
            }
        }.frame(width: 400)
    }
}

public struct AddConversationDialog: View {
    @Binding var isPresented: Bool
    var myUuid: String
    var onConfirm: (String) -> Void
    @State private var targetUuid = ""
    @State private var error: String?
    public var body: some View {
        DialogSurface {
            VStack(alignment: .leading, spacing: 24) {
                Text("Add Friend").font(.headline)
                VStack(alignment: .leading, spacing: 4) {
                    DialogField(label: "Target UUID", value: $targetUuid, hasError: error != nil).onChange(of: targetUuid) { _ in error = nil }
                    if let error { Text(error).font(.system(size: 12)).foregroundColor(MsgATTheme.errorColor).fixedSize(horizontal: false, vertical: true) }
                }
                HStack {
                    Spacer()
                    Button("Cancel") { isPresented = false }.buttonStyle(.bordered)
                    Button("Add") {
                        let uuid = targetUuid.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                        guard uuid.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
                            error = "A friend UUID must contain exactly 64 hexadecimal characters."; return
                        }
                        guard uuid != myUuid.lowercased() else { error = "You cannot add your own UUID."; return }
                        onConfirm(uuid); isPresented = false
                    }.buttonStyle(.borderedProminent)
                }
            }
        }.frame(width: 400)
    }
}

public struct FriendRequestsDialog: View {
    @Binding var isPresented: Bool
    let requests: [FriendRequest]
    var onAccept: (String, String, String) -> Void
    var onDecline: (String) -> Void
    public var body: some View {
        DialogSurface {
            VStack(alignment: .leading, spacing: 16) {
                Text("Friend Requests").font(.headline)
                if requests.isEmpty {
                    Text("No pending requests.").foregroundColor(.secondary).frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollView {
                        LazyVStack(spacing: 16) {
                            ForEach(requests) { req in
                                HStack(spacing: 12) {
                                    AvatarView(avatarPath: req.avatarPath, fallbackText: req.nickname, size: 48, cornerRadius: 12)
                                    VStack(alignment: .leading, spacing: 0) {
                                        Text(req.nickname).font(.system(size: 16, weight: .medium)).lineLimit(1)
                                        Text("UUID: \(req.senderUuid.prefix(8))...").font(.system(size: 12)).foregroundColor(.secondary)
                                    }.frame(maxWidth: .infinity, alignment: .leading)
                                    Button { onAccept(req.senderUuid, req.nickname, req.avatarPath) } label: {
                                        Image(systemName: "checkmark").font(.system(size: 16)).foregroundColor(.green).frame(width: 24, height: 24)
                                    }.buttonStyle(.bordered).accessibilityLabel("Accept")
                                    Button { onDecline(req.senderUuid) } label: {
                                        Image(systemName: "xmark").font(.system(size: 16)).foregroundColor(.red).frame(width: 24, height: 24)
                                    }.buttonStyle(.bordered).accessibilityLabel("Decline")
                                }.padding(12).background(Color(NSColor.controlBackgroundColor), in: RoundedRectangle(cornerRadius: 8))
                            }
                        }
                    }
                }
                HStack { Spacer(); Button("Close") { isPresented = false }.buttonStyle(.bordered) }
            }
        }.frame(width: 480, height: min(500, (NSScreen.main?.visibleFrame.height ?? 720) * 0.7))
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
    let nickname: String
    let myUuid: String
    let currentAvatarPath = KeychainManager.shared.myAvatarPath
    @State private var showResetConfirm = false
    @State private var turnServer: String
    @State private var turnPort: String
    @State private var turnUsername: String
    @State private var turnPassword: String
    @State private var turnUseTls: Bool
    @State private var certificatePin = KeychainManager.shared.serverCertificateSha256 ?? ""
    @State private var allowDevelopmentCertificate = KeychainManager.shared.allowDevelopmentCertificate
    @State private var showAdvancedNetwork = false
    private var normalizedPin: String { certificatePin.replacingOccurrences(of: ":", with: "").replacingOccurrences(of: " ", with: "").lowercased() }
    private var validSettings: Bool {
        !serverIp.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        (Int(serverPort).map { (1...65535).contains($0) } ?? false) &&
        (turnServer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || (Int(turnPort).map { (1...65535).contains($0) } ?? false)) &&
        (normalizedPin.isEmpty || normalizedPin.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil)
    }
    public init(isPresented: Binding<Bool>, initialIp: String, initialPort: String, initialNickname: String,
                myUuid: String, initialTurnServer: String = "", initialTurnPort: String = "3478",
                initialTurnUsername: String = "", initialTurnPassword: String = "", initialTurnUseTls: Bool = false,
                onSave: @escaping (ServerSettings) -> Void, onResetIdentity: @escaping () -> Void) {
        self._isPresented = isPresented
        _serverIp = State(initialValue: initialIp); _serverPort = State(initialValue: initialPort)
        nickname = initialNickname; self.myUuid = myUuid
        _turnServer = State(initialValue: initialTurnServer); _turnPort = State(initialValue: initialTurnPort)
        _turnUsername = State(initialValue: initialTurnUsername); _turnPassword = State(initialValue: initialTurnPassword)
        _turnUseTls = State(initialValue: initialTurnUseTls)
        self.onSave = onSave; self.onResetIdentity = onResetIdentity
    }
    public var body: some View {
        DialogSurface {
            VStack(alignment: .leading, spacing: 16) {
                Text("Settings").font(.headline)
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        GroupBox("My Profile") {
                            HStack(spacing: 12) {
                                AvatarView(avatarPath: currentAvatarPath, fallbackText: myUuid, size: 48, cornerRadius: 10)
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(nickname.isEmpty ? "Me" : nickname).fontWeight(.medium).lineLimit(1)
                                    Text(myUuid).font(.system(.caption, design: .monospaced))
                                        .foregroundColor(.secondary).lineLimit(1).truncationMode(.middle).help(myUuid)
                                }.frame(maxWidth: .infinity, alignment: .leading)
                                Button {
                                    NSPasteboard.general.clearContents()
                                    NSPasteboard.general.setString(myUuid, forType: .string)
                                    NoticeCenter.shared.show("UUID Copied")
                                } label: { Image(systemName: "doc.on.doc") }
                                .buttonStyle(.bordered).help("Copy My UUID").accessibilityLabel("Copy My UUID")
                            }.padding(8)
                        }
                        GroupBox("Server Relay Settings") {
                            VStack(spacing: 10) {
                                SettingsField(label: "Server IP / Domain", value: $serverIp)
                                SettingsField(label: "Server Port", value: $serverPort)
                            }.padding(8)
                        }
                        DisclosureGroup("Advanced Network Settings", isExpanded: $showAdvancedNetwork) {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("TURN Relay (Optional — P2P fallback. Leave empty for pure P2P.)")
                                    .font(.caption).foregroundColor(.secondary).fixedSize(horizontal: false, vertical: true)
                                SettingsField(label: "TURN Server", value: $turnServer)
                                SettingsField(label: "TURN Port", value: $turnPort)
                                SettingsField(label: "TURN Username", value: $turnUsername)
                                SettingsField(label: "TURN Password", value: $turnPassword, secure: true)
                                Toggle("Use TLS (turns://)", isOn: $turnUseTls).toggleStyle(.switch)
                                SettingsField(label: "TLS SHA-256 pin", value: $certificatePin)
                                Toggle("Allow untrusted development certificate", isOn: $allowDevelopmentCertificate)
                            }.padding(.top, 12)
                        }
                        GroupBox("Danger Zone") {
                            HStack {
                                Button("Reset UUID", role: .destructive) { showResetConfirm = true }
                                    .buttonStyle(.bordered)
                                Spacer()
                            }.padding(8)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                        .padding(2)
                }
                Divider()
                HStack(spacing: 8) {
                    Spacer()
                    Button("Cancel") { isPresented = false }.buttonStyle(.bordered)
                        .keyboardShortcut(.cancelAction)
                    Button("Save") {
                        onSave(ServerSettings(ip: serverIp.trimmingCharacters(in: .whitespacesAndNewlines), port: serverPort,
                            turnServer: turnServer, turnPort: turnPort, turnUsername: turnUsername, turnPassword: turnPassword,
                            turnUseTls: turnUseTls, serverCertificateSha256: normalizedPin, allowDevelopmentCertificate: allowDevelopmentCertificate))
                        isPresented = false
                    }.buttonStyle(.borderedProminent).disabled(!validSettings)
                        .keyboardShortcut(.defaultAction)
                }
            }
        }.frame(width: 520, height: min(showAdvancedNetwork ? 640 : 450, max(280, (NSScreen.main?.visibleFrame.height ?? 720) - 100)))
        .sheet(isPresented: $showResetConfirm) {
            ConfirmationDialog(message: "Reset your identity?", onCancel: { showResetConfirm = false }, onConfirm: {
                showResetConfirm = false; isPresented = false; onResetIdentity()
            })
        }
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
    @Binding var isPresented: Bool
    var onConfirm: (String, URL?) -> Bool
    @State private var nickname = ""
    @State private var selectedAvatarUrl: URL?
    @State private var cropSource: CropSource?
    public var body: some View {
        DialogSurface {
            VStack(spacing: 16) {
                Text("Setup Identity").font(.headline).padding(.bottom, 4)
                Button(action: pickAvatar) {
                    if let url = selectedAvatarUrl, let image = NSImage(contentsOf: url) {
                        Image(nsImage: image).resizable().scaledToFill().frame(width: 100, height: 100).clipped()
                    } else {
                        Text("Tap to Pick Avatar").font(.system(size: 12)).multilineTextAlignment(.center).frame(width: 100, height: 100)
                    }
                }.buttonStyle(.plain).background(Color(white: 68/255)).clipShape(RoundedRectangle(cornerRadius: 16))
                DialogField(label: "Nickname", value: $nickname)
                HStack {
                    Spacer()
                    Button("Cancel") {}.buttonStyle(.bordered)
                    Button("Confirm") { if onConfirm(nickname, selectedAvatarUrl) { isPresented = false } }
                        .buttonStyle(.borderedProminent)
                        .disabled(!canConfirm)
                }.padding(.top, 8)
            }
        }.frame(width: 400).interactiveDismissDisabled()
        .sheet(item: $cropSource) { source in
            AvatarCropDialog(source: source.url, onCancel: { cropSource = nil }) { url in
                if let previous = selectedAvatarUrl { try? FileManager.default.removeItem(at: previous) }
                selectedAvatarUrl = url; cropSource = nil
            }
        }
    }
    private var canConfirm: Bool { !nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && selectedAvatarUrl != nil }
    private func pickAvatar() {
        let panel = NSOpenPanel(); panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false; panel.canChooseDirectories = false
        panel.begin { response in
            if response == .OK, let url = panel.url { cropSource = CropSource(url: url) }
        }
    }
}

private struct CropSource: Identifiable {
    let id = UUID()
    let url: URL
}
private struct AvatarCropDialog: View {
    let source: URL
    let onCancel: () -> Void
    let onConfirm: (URL) -> Void
    @State private var zoom: Double = 1
    @State private var offset = CGSize.zero
    @State private var dragOrigin = CGSize.zero
    private var image: NSImage? { NSImage(contentsOf: source) }
    var body: some View {
        DialogSurface {
            VStack(spacing: 16) {
                Text("Crop").font(.headline).frame(maxWidth: .infinity, alignment: .leading)
                if let image {
                    let ratio = max(300 / image.size.width, 300 / image.size.height)
                    ZStack {
                        Color.black
                        Image(nsImage: image).resizable().frame(width: image.size.width * ratio * zoom, height: image.size.height * ratio * zoom).offset(offset)
                    }.frame(width: 300, height: 300).clipped()
                    .gesture(DragGesture().onChanged { value in
                        offset = clamped(CGSize(width: dragOrigin.width + value.translation.width, height: dragOrigin.height + value.translation.height), image: image, ratio: ratio)
                    }.onEnded { _ in dragOrigin = offset })
                    Slider(value: $zoom, in: 1...5).onChange(of: zoom) { _ in offset = clamped(offset, image: image, ratio: ratio); dragOrigin = offset }
                } else { Text("Corrupted Image").foregroundColor(.primary) }
                HStack {
                    Spacer()
                    Button("Cancel", action: onCancel).buttonStyle(.bordered)
                    Button("Confirm", action: save).buttonStyle(.borderedProminent).disabled(image == nil)
                }
            }
        }.frame(width: 400)
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
        guard let bytes = bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.9]) else { return }
        do {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("msgat-avatar-\(UUID().uuidString).jpg")
            try bytes.write(to: url, options: .atomic); onConfirm(url)
        } catch { NoticeCenter.shared.show("Failed to save") }
    }
}
