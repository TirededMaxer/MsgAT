package org.msgat.ui.sessionlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import org.msgat.ui.chat.SyncState
import org.msgat.ui.components.AddConversationDialog
import org.msgat.ui.components.ConfirmDialog
import org.msgat.ui.components.SettingsDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// Renders the primary session list screen containing all active conversations.
fun SessionListScreen(
    syncState: SyncState,
    conversations: List<ConversationUIModel>,
    friendRequests: List<org.msgat.data.db.FriendRequest>,
    onNavigateToChat: (String) -> Unit,
    onAddConversation: (uuid: String) -> Unit,
    onSaveSettings: (org.msgat.ui.components.ServerSettings) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onClearMessages: (String) -> Unit,
    onResetIdentity: () -> Unit,
    onSaveIdentity: (String, String) -> Unit,
    initialIp: String,
    initialPort: String,
    initialUuid: String,
    initialAvatarPath: String? = null,
    initialNickname: String? = null,
    initialTurnServer: String = "",
    initialTurnPort: String = "3478",
    initialTurnUsername: String = "",
    initialTurnPassword: String = "",
    initialTurnUseTls: Boolean = false,
    isIdentityMissing: Boolean = false,
    onAcceptFriendRequest: (String, String, String) -> Unit = {_,_,_->},
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface),
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
                items(conversations) { convo ->
                    ConversationItem(
                        convo = convo,
                        onClick = { onNavigateToChat(convo.uuid) },
                        onDelete = { onDeleteConversation(convo.uuid) },
                        onClear = { onClearMessages(convo.uuid) }
                    )
                }
            }
        }
    }

    if (showAddDialog) AddConversationDialog(myUuid = initialUuid, onDismiss = { showAddDialog = false }, onConfirm = { u -> onAddConversation(u); showAddDialog = false })
    if (showSettingsDialog) SettingsDialog(initialIp = initialIp, initialPort = initialPort, initialUuid = initialUuid, initialAvatarPath = initialAvatarPath, initialNickname = initialNickname, initialTurnServer = initialTurnServer, initialTurnPort = initialTurnPort, initialTurnUsername = initialTurnUsername, initialTurnPassword = initialTurnPassword, initialTurnUseTls = initialTurnUseTls, onResetIdentity = {showSettingsDialog = false; showResetConfirm = true;}, onDismiss = { showSettingsDialog = false }, onConfirm = { s -> onSaveSettings(s); showSettingsDialog = false })
    if (showResetConfirm) ConfirmDialog("Reset your identity?", onDismiss = { showResetConfirm = false }, onConfirm = { onResetIdentity(); showResetConfirm = false })
    if (showIdentityDialog) {
        org.msgat.ui.components.IdentitySetupDialog(
            onDismiss = {},
            onConfirm = { n, a ->
                onSaveIdentity(n, a?.toString() ?: "")
                showIdentityDialog = false
            }
        )
    }

    if (showFriendRequestsDialog) {
        org.msgat.ui.components.FriendRequestsDialog(
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
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit
) {
    var showActionMenu by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
        detectTapGestures(onLongPress = { showActionMenu = true }, onTap = { onClick() })
    }.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = Color(0xFF333333),
                    modifier = Modifier.size(48.dp)
                ) {
                    if (convo.avatarPath.isNotEmpty()) {
                        androidx.compose.foundation.Image(
                            painter = coil.compose.rememberAsyncImagePainter(convo.avatarPath),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(convo.alias.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }

            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = convo.alias, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = convo.uuid.take(12) + "...", color = Color.Gray, fontSize = 13.sp)
            }
            if (convo.unreadCount > 0) {
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFFF22626), CircleShape)
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val diameter = maxOf(placeable.width, placeable.height)
                            layout(diameter, diameter) {
                                placeable.placeRelative(
                                    (diameter - placeable.width) / 2,
                                    (diameter - placeable.height) / 2
                                )
                            }
                        }
                        .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = convo.unreadCount.toString(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
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

@Composable
// Renders a single row representing an incoming friend request in the list.
fun FriendRequestItem(
    request: org.msgat.data.db.FriendRequest,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Surface(shape = CircleShape, color = Color.DarkGray, modifier = Modifier.size(48.dp)) {
                    if (request.avatarPath.isNotEmpty()) {
                        androidx.compose.foundation.Image(
                            painter = coil.compose.rememberAsyncImagePainter(request.avatarPath),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(request.nickname.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = request.nickname, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(text = "Wants to connect", color = Color.Gray, fontSize = 13.sp)
                }
            }
            Row {
                TextButton(onClick = onDecline) { Text("Decline", color = Color.Gray) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                    Text("Accept", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }
    }
}
