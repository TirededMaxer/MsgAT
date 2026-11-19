import SwiftUI

@main
struct MsgATApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @AppStorage(MsgATTheme.themePreference, store: RuntimePaths.defaults) private var theme = AppThemeMode.system
    @AppStorage(MsgATTheme.languagePreference, store: RuntimePaths.defaults) private var language = AppLanguage.system
    @Environment(\.colorScheme) private var currentColorScheme
    @State private var confirmLocalReset = false
    @State private var preparingLocalReset = false
    @State private var localResetFailed = false

    var body: some Scene {
        let interfaceText = InterfaceStrings(language)
        Window("MsgAT", id: "main") {
            Group {
                if let failure = KeychainManager.startupFailure ?? AppDatabase.startupFailure {
                    let resetPending = IdentityLifecycle.hasPendingReset
                    let summary = resetPending ? "reset_incomplete" :
                        KeychainManager.startupFailureIsIdentity ? "startup_identity_unavailable" : "startup_vault_unavailable"
                    let colors = MsgATTheme.messagingColors(for: theme.colorScheme ?? currentColorScheme)
                    VStack(alignment: .leading, spacing: 16) {
                        Label(interfaceText("storage_unavailable"), systemImage: "lock.shield")
                            .font(.title2).accessibilityAddTraits(.isHeader)
                        Text(interfaceText(summary)).fixedSize(horizontal: false, vertical: true)
                        DisclosureGroup(interfaceText("startup_technical_details")) {
                            ScrollView {
                                Text(failure).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                                    .frame(maxWidth: .infinity, alignment: .leading).padding(8)
                            }
                            .frame(maxHeight: 145)
                        }
                        if localResetFailed {
                            Text(interfaceText("identity_reset_prepare_failed")).foregroundStyle(.red)
                        }
                        HStack {
                            if KeychainManager.canResetUnreadableIdentity && !resetPending {
                                Button(interfaceText("reset_identity"), role: .destructive) { confirmLocalReset = true }
                            }
                            Spacer()
                            Button(interfaceText("quit")) { NSApplication.shared.terminate(nil) }
                                .keyboardShortcut(.defaultAction)
                        }.disabled(preparingLocalReset)
                    }
                    .padding(28)
                    .frame(minWidth: 460, maxWidth: 560)
                    .background(colors.chrome, in: RoundedRectangle(cornerRadius: 12))
                    .overlay { RoundedRectangle(cornerRadius: 12).strokeBorder(colors.divider).allowsHitTesting(false) }
                } else {
                    AppLockRoot()
                }
            }
            .preferredColorScheme(theme.colorScheme)
            .alert(interfaceText("reset_identity"), isPresented: $confirmLocalReset) {
                Button(interfaceText("cancel"), role: .cancel) {}
                Button(interfaceText("reset_identity"), role: .destructive) { resetUnreadableIdentity() }
            } message: {
                Text(interfaceText("identity_reset_unreadable_confirm"))
            }
            .environment(\.interfaceStrings, interfaceText)
            .defaultAppStorage(RuntimePaths.defaults)
        }
        .defaultSize(width: 1040, height: 680)
        .windowStyle(.titleBar)
        .windowToolbarStyle(.unified)
        .commands {
            SidebarCommands()
            ConversationNavigationCommands(interfaceText: interfaceText)
            ChatAttachmentCommands(interfaceText: interfaceText)
        }
    }

    @MainActor private func resetUnreadableIdentity() {
        guard KeychainManager.canResetUnreadableIdentity, !preparingLocalReset else { return }
        preparingLocalReset = true
        localResetFailed = false
        Task { @MainActor in
            do {
                try await Task.detached(priority: .utility) { try IdentityLifecycle.prepareLocalOnlyReset() }.value
                NSApplication.shared.terminate(nil)
            } catch {
                preparingLocalReset = false
                localResetFailed = true
            }
        }
    }
}

struct ChatFilePickerCommand {
    let enabled: Bool
    let open: @MainActor () -> Void
}

private struct ChatFilePickerCommandKey: FocusedValueKey {
    typealias Value = ChatFilePickerCommand
}

extension FocusedValues {
    var chatFilePicker: ChatFilePickerCommand? {
        get { self[ChatFilePickerCommandKey.self] }
        set { self[ChatFilePickerCommandKey.self] = newValue }
    }
}

private struct ChatAttachmentCommands: Commands {
    let interfaceText: InterfaceStrings
    @FocusedValue(\.chatFilePicker) private var picker

    var body: some Commands {
        CommandGroup(after: .newItem) {
            Button(interfaceText("files")) { picker?.open() }
                .keyboardShortcut("o", modifiers: .command)
                .disabled(picker?.enabled != true)
        }
    }
}

private struct ConversationNavigationCommands: Commands {
    let interfaceText: InterfaceStrings
    @FocusedValue(\.moveToAdjacentChat) private var moveToAdjacentChat

    var body: some Commands {
        CommandMenu(interfaceText("chats_menu")) {
            Button(interfaceText("previous_chat")) { moveToAdjacentChat?(false) }
                .keyboardShortcut(.pageUp, modifiers: .control)
                .disabled(moveToAdjacentChat == nil)
            Button(interfaceText("next_chat")) { moveToAdjacentChat?(true) }
                .keyboardShortcut(.pageDown, modifiers: .control)
                .disabled(moveToAdjacentChat == nil)
        }
    }
}
