import SwiftUI

public struct SessionListView: View {
    @ObservedObject var viewModel: SessionListViewModel
    @Binding var selectedConversationUuid: String?

    @State private var showAddDialog = false
    @State private var showRequestsDialog = false
    @State private var showSettingsDialog = false

    @State private var conversationToDelete: String? = nil
    @State private var conversationToClear: String? = nil

    public init(viewModel: SessionListViewModel, selectedConversationUuid: Binding<String?>) {
        self.viewModel = viewModel
        self._selectedConversationUuid = selectedConversationUuid
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                HStack(spacing: 6) {
                    if viewModel.syncState == .syncing {
                        ProgressView().controlSize(.mini).tint(MsgATTheme.connectionDotColor)
                            .frame(width: 12, height: 12)
                    } else {
                        Circle().fill(statusColor).frame(width: 10, height: 10)
                    }
                    Text(statusText).font(.system(size: 12))
                        .foregroundColor(viewModel.syncState == .synced ? .gray : statusColor)
                }
                Spacer()
                Menu {
                    Button("Add Friend") { showAddDialog = true }
                    Button("Friend Requests") { showRequestsDialog = true }
                    Button("Settings") { showSettingsDialog = true }
                } label: {
                    Image(systemName: "plus")
                        .frame(width: 20, height: 20)
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .help("Menu")
            }
            .padding(.horizontal, 12)
            .frame(height: 36)

            if !viewModel.conversations.isEmpty {
                Text("Friends")
                    .font(.system(size: 14)).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.vertical, 8)
                List(viewModel.conversations, id: \.conversation.uuid, selection: $selectedConversationUuid) { item in
                    let conv = item.conversation
                    HStack(spacing: 16) {
                        AvatarView(
                            avatarPath: conv.avatarPath,
                            fallbackText: conv.alias,
                            size: 48,
                            cornerRadius: 8
                        )
                        .frame(width: 48, height: 48)

                        VStack(alignment: .leading, spacing: 4) {
                            Text(conv.alias)
                                .font(.body).fontWeight(.medium)
                                .lineLimit(1)

                            Text(String(conv.uuid.prefix(12)) + "...")
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        if item.unreadCount > 0 {
                            CircularUnreadLayout {
                                Text(String(item.unreadCount))
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                            }
                            .background(Color(red: 242/255, green: 38/255, blue: 38/255), in: Circle())
                            .padding(.leading, 12)
                        }
                    }
                    .padding(.vertical, 4)



                    .tag(conv.uuid)
                    .contextMenu {
                        Button("Clear Message") {
                            conversationToClear = conv.uuid
                        }
                        Button("Delete Friend", role: .destructive) {
                            conversationToDelete = conv.uuid
                        }
                    }
                }
                .listStyle(.sidebar)

            } else {
                Spacer()
            }
        }
        .frame(minWidth: 260)
        .navigationTitle("MsgAT")
        .sheet(isPresented: $showAddDialog) {
            AddConversationDialog(
                isPresented: $showAddDialog,
                myUuid: viewModel.currentUuid,
                onConfirm: { uuid in
                    viewModel.addConversation(rawUuid: uuid)
                }
            )
        }
        .sheet(isPresented: $showRequestsDialog) {
            FriendRequestsDialog(
                isPresented: $showRequestsDialog,
                requests: viewModel.friendRequests,
                onAccept: { senderUuid, nickname, avatarPath in
                    viewModel.acceptFriendRequest(senderUuid: senderUuid, nickname: nickname, avatarPath: avatarPath)
                },
                onDecline: { senderUuid in
                    viewModel.declineFriendRequest(senderUuid: senderUuid)
                }
            )
        }
        .sheet(isPresented: $showSettingsDialog) {
            SettingsDialog(
                isPresented: $showSettingsDialog,
                initialIp: viewModel.currentIp,
                initialPort: viewModel.currentPort,
                initialNickname: viewModel.currentNickname,
                myUuid: viewModel.currentUuid,
                initialTurnServer: viewModel.currentTurnServer,
                initialTurnPort: viewModel.currentTurnPort,
                initialTurnUsername: viewModel.currentTurnUsername,
                initialTurnPassword: viewModel.currentTurnPassword,
                initialTurnUseTls: viewModel.currentTurnUseTls,
                onSave: { settings in
                    viewModel.saveSettings(settings: settings)
                },
                onResetIdentity: {
                    viewModel.resetIdentity()
                }
            )
        }
        .sheet(isPresented: $viewModel.showIdentityDialog) {
            IdentitySetupDialog(
                isPresented: $viewModel.showIdentityDialog,
                onConfirm: { nick, avatarUrl in
                    viewModel.saveIdentity(nickname: nick, avatarUrl: avatarUrl)
                }
            )
        }
        .onChange(of: viewModel.resetErrorMessage) { message in
            if let message { NoticeCenter.shared.show(message); viewModel.resetErrorMessage = nil }
        }
        .sheet(isPresented: Binding(get: { conversationToClear != nil }, set: { if !$0 { conversationToClear = nil } })) {
            ConfirmationDialog(message: "Clear all messages in this conversation?", onCancel: { conversationToClear = nil }, onConfirm: {
                if let uuid = conversationToClear { viewModel.clearMessages(uuid: uuid) }; conversationToClear = nil
            })
        }
        .sheet(isPresented: Binding(get: { conversationToDelete != nil }, set: { if !$0 { conversationToDelete = nil } })) {
            ConfirmationDialog(message: "Delete this friend and clear history?", onCancel: { conversationToDelete = nil }, onConfirm: {
                if let uuid = conversationToDelete { viewModel.deleteConversation(uuid: uuid); if selectedConversationUuid == uuid { selectedConversationUuid = nil } }; conversationToDelete = nil
            })
        }
    }

    private var statusText: String {
        switch viewModel.syncState {
        case .synced: return "Connected"
        case .syncing: return "Syncing..."
        case .disconnected: return "Disconnected"
        }
    }

    private var statusColor: Color {
        switch viewModel.syncState {
        case .synced:
            return MsgATTheme.connectionDotColor
        case .syncing:
            return MsgATTheme.connectionDotColor
        case .disconnected:
            return .red
        }
    }
}

private struct CircularUnreadLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let content = subviews.first?.sizeThatFits(.unspecified) ?? .zero
        let diameter = max(20, max(content.width, content.height))
        return CGSize(width: diameter, height: diameter)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: CGPoint(x: bounds.midX, y: bounds.midY), anchor: .center, proposal: .unspecified)
    }
}
