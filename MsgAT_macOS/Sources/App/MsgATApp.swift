import SwiftUI

@main
struct MsgATApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        Window("MsgAT", id: "main") {
            MainWindow()
        }
        .defaultSize(width: 1040, height: 680)
        .windowStyle(.titleBar)
        .windowToolbarStyle(.unified)
        .commands { SidebarCommands() }
    }
}
