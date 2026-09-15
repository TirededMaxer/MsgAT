import SwiftUI

public struct MainWindow: View {
    @StateObject private var sessionViewModel = SessionListViewModel()
    @State private var selectedConversationUuid: String? = nil

    public init() {}

    public var body: some View {
        ZStack(alignment: .top) {
            NavigationSplitView {
                SessionListView(
                    viewModel: sessionViewModel,
                    selectedConversationUuid: $selectedConversationUuid
                )
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
                            .font(.title3)
                            .fontWeight(.medium)
                            .foregroundColor(.secondary)
                        Text("Select a conversation from the sidebar to start chatting.")
                            .font(.subheadline)
                            .foregroundColor(.secondary.opacity(0.8))
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color(NSColor.textBackgroundColor))
                }
            }
            .navigationSplitViewStyle(.balanced)

            CallOverlayView()
        }
        .frame(minWidth: 800, minHeight: 500)
    }
}
