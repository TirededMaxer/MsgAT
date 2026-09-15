import SwiftUI

public struct SessionListView: View {
    @ObservedObject var viewModel: SessionListViewModel
    @Binding var selectedConversationUuid: String?

    @State private var searchText = ""
    @State private var showAddDialog = false
    @State private var showRequestsDialog = false
    @State private var showSettingsDialog = false
    @State private var showIdentityDialog = false

    @State private var conversationToDelete: String? = nil
    @State private var conversationToClear: String? = nil

    public init(viewModel: SessionListViewModel, selectedConversationUuid: Binding<String?>) {
        self.viewModel = viewModel
        self._selectedConversationUuid = selectedConversationUuid
    }

    private var filteredConversations: [ConversationWithUnread] {
        if searchText.isEmpty {
            return viewModel.conversations
        }
        return viewModel.conversations.filter {
            $0.conversation.alias.localizedCaseInsensitiveContains(searchText) ||
            $0.conversation.uuid.localizedCaseInsensitiveContains(searchText)
        }
    }

    public var body: some View {
        VStack(spacing: 0) {
            // Header & Status
            HStack(spacing: 10) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(statusColor)
                        .frame(width: 9, height: 9)
                    Text(viewModel.syncState.rawValue)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()

                // Action Buttons
                Button(action: { showAddDialog = true }) {
                    Image(systemName: "person.badge.plus")
                }
                .buttonStyle(.plain)
                .help("Add Friend")

                Button(action: { showRequestsDialog = true }) {
                    ZStack(alignment: .topTrailing) {
                        Image(systemName: "bell")
                        if !viewModel.friendRequests.isEmpty {
                            Circle()
                                .fill(Color.red)
                                .frame(width: 7, height: 7)
                                .offset(x: 2, y: -2)
                        }
                    }
                }
                .buttonStyle(.plain)
                .help("Friend Requests")

                Button(action: { showSettingsDialog = true }) {
                    Image(systemName: "gearshape")
                }
                .buttonStyle(.plain)
                .help("Settings")
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color(NSColor.controlBackgroundColor))

            Divider()

            // Search Bar
            HStack {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.secondary)
                TextField("Search friends...", text: $searchText)
                    .textFieldStyle(.plain)
                if !searchText.isEmpty {
                    Button(action: { searchText = "" }) {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(7)
            .background(Color(NSColor.textBackgroundColor))
            .cornerRadius(8)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)

            // Conversations List
            if filteredConversations.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "bubble.left.and.bubble.right")
                        .font(.system(size: 32))
                        .foregroundColor(.secondary.opacity(0.6))
                    Text("No Conversations")
                        .font(.callout)
                        .foregroundColor(.secondary)
                    Button("Add Friend") {
                        showAddDialog = true
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    Spacer()
                }
            } else {
                List(filteredConversations, id: \.conversation.uuid, selection: $selectedConversationUuid) { item in
                    let conv = item.conversation
                    HStack(spacing: 12) {
                        AvatarView(
                            avatarPath: conv.avatarPath,
                            fallbackText: conv.alias,
                            size: 42,
                            cornerRadius: 10
                        )
                        .frame(width: 46, height: 46, alignment: .center)

                        VStack(alignment: .leading, spacing: 3) {
                            Text(conv.alias)
                                .font(.system(size: 14, weight: .medium))
                                .lineLimit(1)

                            Text(String(conv.uuid.prefix(12)) + "...")
                                .font(.system(size: 11))
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        if item.unreadCount > 0 {
                            Text(String(item.unreadCount))
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 6)
                                .frame(minWidth: 20, minHeight: 20)
                                .background(Color(red: 0.95, green: 0.15, blue: 0.15), in: Capsule())
                                .fixedSize()
                        }
                    }
                    .padding(.vertical, 4)
                    .tag(conv.uuid)
                    .contextMenu {
                        Button("Clear Messages") {
                            conversationToClear = conv.uuid
                        }
                        Divider()
                        Button("Delete Friend", role: .destructive) {
                            conversationToDelete = conv.uuid
                        }
                    }
                }
                .listStyle(.sidebar)
            }
        }
        .frame(minWidth: 260)
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
        .alert(
            "Reset Failed",
            isPresented: Binding(
                get: { viewModel.resetErrorMessage != nil },
                set: { if !$0 { viewModel.resetErrorMessage = nil } }
            )
        ) {
            Button("OK", role: .cancel) { viewModel.resetErrorMessage = nil }
        } message: {
            Text(viewModel.resetErrorMessage ?? "Unknown error")
        }
        .alert(
            "Clear Messages?",
            isPresented: Binding(
                get: { conversationToClear != nil },
                set: { if !$0 { conversationToClear = nil } }
            )
        ) {
            Button("Clear All", role: .destructive) {
                if let uuid = conversationToClear {
                    viewModel.clearMessages(uuid: uuid)
                }
                conversationToClear = nil
            }
            Button("Cancel", role: .cancel) { conversationToClear = nil }
        } message: {
            Text("Are you sure you want to clear all message history for this contact?")
        }
        .alert(
            "Delete Friend?",
            isPresented: Binding(
                get: { conversationToDelete != nil },
                set: { if !$0 { conversationToDelete = nil } }
            )
        ) {
            Button("Delete", role: .destructive) {
                if let uuid = conversationToDelete {
                    viewModel.deleteConversation(uuid: uuid)
                    if selectedConversationUuid == uuid {
                        selectedConversationUuid = nil
                    }
                }
                conversationToDelete = nil
            }
            Button("Cancel", role: .cancel) { conversationToDelete = nil }
        } message: {
            Text("This will delete the contact and all associated chat and media history.")
        }
    }

    private var statusColor: Color {
        switch viewModel.syncState {
        case .synced:
            return .green
        case .syncing:
            return .orange
        case .disconnected:
            return .red
        }
    }
}
