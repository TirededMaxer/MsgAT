import SwiftUI

public struct MainWindow: View {
    @Environment(\.openWindow) private var openWindow
    @StateObject private var sessionViewModel = SessionListViewModel()
    @State private var selectedConversationUuid: String? = nil
    @State private var columnVisibility: NavigationSplitViewVisibility = .all

    public init() {}

    public var body: some View {
        ZStack(alignment: .top) {
            NavigationSplitView(columnVisibility: $columnVisibility) {
                SessionListView(viewModel: sessionViewModel, selectedConversationUuid: $selectedConversationUuid)
                    .navigationSplitViewColumnWidth(min: 260, ideal: 300, max: 400)
            } detail: {
                if let uuid = selectedConversationUuid {
                    ChatView(targetUuid: uuid)
                        .id(uuid)
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "shield.checkered")
                            .font(.system(size: 48))
                            .foregroundColor(.secondary.opacity(0.5))
                        Text("MsgAT Secure Messaging")
                            .font(.title3).fontWeight(.medium).foregroundColor(.secondary)
                        Text("Select a conversation from the sidebar to start chatting.")
                            .font(.subheadline).foregroundColor(.secondary.opacity(0.8))
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color(NSColor.textBackgroundColor))
                    .navigationTitle("MsgAT")
                }
            }
            .navigationSplitViewStyle(.balanced)
            CallOverlayView()
            NoticeView()
        }
        .background(Color(NSColor.windowBackgroundColor))
        .background(MainWindowHost().allowsHitTesting(false))
        .frame(minWidth: 800, minHeight: 500)
        .onAppear {
            let action = openWindow
            NotificationHelper.openMainWindow = {
                if let window = NotificationHelper.mainWindow, window.isVisible || window.isMiniaturized {
                    window.deminiaturize(nil)
                    window.makeKeyAndOrderFront(nil)
                } else { action(id: "main") }
            }
        }
    }
}

private struct MainWindowHost: NSViewRepresentable {
    func makeNSView(context: Context) -> HostView { HostView() }
    func updateNSView(_ nsView: HostView, context: Context) {}

    final class HostView: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            if let window {
                NotificationHelper.mainWindow = window
                window.sharingType = .none
            }
        }
    }
}
