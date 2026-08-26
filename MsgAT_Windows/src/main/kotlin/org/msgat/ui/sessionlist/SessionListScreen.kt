package org.msgat.ui.sessionlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.msgat.data.db.FriendRequest
import org.msgat.model.SyncState
import org.msgat.ui.components.AddConversationDialog
import org.msgat.ui.components.ConfirmDialog
import org.msgat.ui.components.FriendRequestsDialog
import org.msgat.ui.components.IdentitySetupDialog
import org.msgat.ui.components.SettingsDialog
import org.msgat.utils.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// Renders the primary session list screen containing all active conversations.
fun SessionListScreen(
    syncState: SyncState,
    conversations: List<ConversationUIModel>,
    friendRequests: List<FriendRequest>,
    selectedUuid: String?,
    onSelectConversation: (String) -> Unit,
    onAddConversation: (uuid: String) -> Unit,
    onSaveSettings: (ip: String, port: String, turnServer: String?, turnPort: Int, turnUsername: String?, turnPassword: String?, turnUseTls: Boolean) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onClearMessages: (String) -> Unit,
    onResetIdentity: () -> Unit,
    onSaveIdentity: (String, String) -> Unit,
    initialIp: String,
    initialPort: String,
    initialUuid: String,
    initialAvatarPath: String? = null,
    initialNickname: String? = null,
    isIdentityMissing: Boolean = false,
    initialTurnServer: String? = null,
    initialTurnPort: String = "",
    initialTurnUsername: String? = null,
    initialTurnPassword: String = "",
    initialTurnUseTls: Boolean = false,
    onAcceptFriendRequest: (String, String, String) -> Unit = { _, _, _ -> },
    onDeclineFriendRequest: (String) -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showIdentityDialog by remember { mutableStateOf(isIdentityMissing) }
    var showFriendRequestsDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = { Text("MsgAT") },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                        when (syncState) {
                            SyncState.DISCONNECTED -> {
                                Box(modifier = Modifier.size(10.dp).background(Color.Red, CircleShape))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Disconnected", color = Color.Red, fontSize = 12.sp)
                            }
                            SyncState.SYNCING -> {
                                CircularProgressIndicator(color = Color.Green, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Syncing...", color = Color.Green, fontSize = 12.sp)
                            }
                            SyncState.SYNCED -> {
                                Box(modifier = Modifier.size(10.dp).background(Color.Green, CircleShape))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Connected", color = Color.Green, fontSize = 12.sp)
                            }
                        }
                    }

                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Menu", tint = MaterialTheme.colorScheme.onSurface)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                        DropdownMenuItem(text = { Text("Add Friend", color = MaterialTheme.colorScheme.onSurface) }, onClick = { showMenu = false; showAddDialog = true })
                        DropdownMenuItem(text = { Text("Friend Requests", color = MaterialTheme.colorScheme.onSurface) }, onClick = { showMenu = false; showFriendRequestsDialog = true })
                        DropdownMenuItem(text = { Text("Settings", color = MaterialTheme.colorScheme.onSurface) }, onClick = { showMenu = false; showSettingsDialog = true })
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (conversations.isNotEmpty()) {
                item { Text("Friends", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                items(conversations, key = { it.uuid }) { convo ->
                    ConversationItem(
                        convo = convo,
                        isSelected = convo.uuid == selectedUuid,
                        onClick = { onSelectConversation(convo.uuid) },
                        onDelete = { onDeleteConversation(convo.uuid) },
                        onClear = { onClearMessages(convo.uuid) }
                    )
                }
            }
        }
    }

    if (showAddDialog) AddConversationDialog(onDismiss = { showAddDialog = false }, onConfirm = { u -> onAddConversation(u); showAddDialog = false })
    if (showSettingsDialog) SettingsDialog(
        initialIp = initialIp,
        initialPort = initialPort,
        initialUuid = initialUuid,
        initialAvatarPath = initialAvatarPath,
        initialNickname = initialNickname,
        initialTurnServer = initialTurnServer,
        initialTurnPort = initialTurnPort,
        initialTurnUsername = initialTurnUsername,
        initialTurnPassword = initialTurnPassword,
        initialTurnUseTls = initialTurnUseTls,
        onResetIdentity = { showSettingsDialog = false; showResetConfirm = true },
        onDismiss = { showSettingsDialog = false },
        onConfirm = { s -> onSaveSettings(s.ip, s.port, s.turnServer, s.turnPort, s.turnUsername, s.turnPassword, s.turnUseTls); showSettingsDialog = false }
    )
    if (showResetConfirm) ConfirmDialog("Reset your identity?", onDismiss = { showResetConfirm = false }, onConfirm = { onResetIdentity(); showResetConfirm = false })
    if (isIdentityMissing) {
        IdentitySetupDialog(
            onDismiss = {},
            onConfirm = { n, a ->
                onSaveIdentity(n, a)
                showIdentityDialog = false
            }
        )
    }

    if (showFriendRequestsDialog) {
        FriendRequestsDialog(
            requests = friendRequests,
            onDismiss = { showFriendRequestsDialog = false },
            onAccept = { senderUuid, nickname, avatarPath -> onAcceptFriendRequest(senderUuid, nickname, avatarPath) },
            onDecline = { senderUuid -> onDeclineFriendRequest(senderUuid) }
        )
    }
}

@Composable
// Renders a single row representing a chat conversation in the list.
fun ConversationItem(
    convo: ConversationUIModel,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit
) {
    var showActionMenu by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val bgColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { showActionMenu = true },
                    onTap = { onClick() }
                )
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF333333),
                    modifier = Modifier.size(48.dp)
                ) {
                    if (convo.avatarPath.isNotEmpty()) {
                        AsyncImage(
                            filePath = convo.avatarPath,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(convo.alias.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (convo.hasUnread) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .align(Alignment.TopEnd)
                            .offset(x = 2.dp, y = (-2).dp)
                            .background(Color.Red, CircleShape)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = convo.alias, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = convo.uuid.take(12) + "...", color = Color.Gray, fontSize = 13.sp)
            }
        }
        DropdownMenu(expanded = showActionMenu, onDismissRequest = { showActionMenu = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            DropdownMenuItem(text = { Text("Clear Message", color = MaterialTheme.colorScheme.onSurface) }, onClick = { showActionMenu = false; showClearConfirm = true })
            DropdownMenuItem(text = { Text("Delete Friend", color = MaterialTheme.colorScheme.error) }, onClick = { showActionMenu = false; showDeleteConfirm = true })
        }
    }

    if (showClearConfirm) ConfirmDialog("Clear all messages in this conversation?", onDismiss = { showClearConfirm = false }, onConfirm = { onClear(); showClearConfirm = false })
    if (showDeleteConfirm) ConfirmDialog("Delete this friend and clear history?", onDismiss = { showDeleteConfirm = false }, onConfirm = { onDelete(); showDeleteConfirm = false })
}
