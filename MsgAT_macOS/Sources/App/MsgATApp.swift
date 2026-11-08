import SwiftUI

@main
struct MsgATApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        Window("MsgAT", id: "main") {
            if let failure = KeychainManager.startupFailure {
                VStack(alignment: .leading, spacing: 16) {
                    Label("Identity unavailable", systemImage: "lock.shield")
                        .font(.title2)
                    Text(failure).textSelection(.enabled)
                    Button("Quit") { NSApplication.shared.terminate(nil) }
                        .keyboardShortcut(.defaultAction)
                }
                .padding(28)
                .frame(minWidth: 460, maxWidth: 560)
            } else {
                MainWindow()
                    .defaultAppStorage(RuntimePaths.defaults)
            }
        }
        .defaultSize(width: 1040, height: 680)
        .windowStyle(.titleBar)
        .windowToolbarStyle(.unified)
        .commands { SidebarCommands() }
    }
}
