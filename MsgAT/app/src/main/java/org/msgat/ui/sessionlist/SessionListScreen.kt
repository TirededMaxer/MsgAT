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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.msgat.ui.chat.SyncState
import org.msgat.ui.components.AddConversationDialog
import org.msgat.ui.components.ConfirmDialog
import org.msgat.ui.components.SettingsDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    syncState: SyncState,
    conversations: List<ConversationUIModel>,
    onNavigateToChat: (String) -> Unit,
    onAddConversation: (uuid: String, alias: String) -> Unit,
    onSaveSettings: (ip: String, port: String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onClearMessages: (String) -> Unit,
    onResetIdentity: () -> Unit,
    onUpdateAlias: (String, String) -> Unit,
    initialIp: String,      // 新增
    initialPort: String,    // 新增
    initialUuid: String,     // 新增
) {
    var showMenu by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                title = { Text("MsgAT") },
                actions = {
                    // 连接状态指示器
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
                        Icon(Icons.Default.Add, contentDescription = "Menu", tint = Color.White)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(Color.DarkGray)) {
                        DropdownMenuItem(text = { Text("Add Conversation", color = Color.White) }, onClick = { showMenu = false; showAddDialog = true })
                        DropdownMenuItem(text = { Text("Settings", color = Color.White) }, onClick = { showMenu = false; showSettingsDialog = true })
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(conversations) { convo ->
                ConversationItem(
                    convo = convo,
                    onClick = { onNavigateToChat(convo.uuid) },
                    onDelete = { onDeleteConversation(convo.uuid) },
                    onClear = { onClearMessages(convo.uuid) },
                    onUpdateAlias = { newAlias -> onUpdateAlias(convo.uuid, newAlias) }
                )
            }
        }
    }

    // --- 对话框组件分离 (为保持代码精简，此处省略具体的 AlertDialog 实现细节，逻辑上就是几个 TextField 和 Confirm Button) ---
    if (showAddDialog) AddConversationDialog(onDismiss = { showAddDialog = false }, onConfirm = { u, a -> onAddConversation(u, a); showAddDialog = false })
    if (showSettingsDialog) SettingsDialog(initialIp = initialIp, initialPort = initialPort, initialUuid = initialUuid, onResetIdentity = {showSettingsDialog = false; showResetConfirm = true;}, onDismiss = { showSettingsDialog = false }, onConfirm = { i, p, _ -> onSaveSettings(i, p); showSettingsDialog = false })
    if (showResetConfirm) ConfirmDialog("Reset your identity?", onDismiss = { showResetConfirm = false }, onConfirm = { onResetIdentity(); showResetConfirm = false })
}

@Composable
fun ConversationItem(
    convo: ConversationUIModel,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onUpdateAlias: (String) -> Unit
) {
    var showActionMenu by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) } // 新增：编辑弹窗状态

    Box(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).pointerInput(Unit) {
        detectTapGestures(onLongPress = { showActionMenu = true }, onTap = { onClick() })
    }.padding(16.dp)) {
        Column {
            Text(text = convo.alias, color = Color.White, fontSize = 18.sp)
            Text(text = convo.uuid, color = Color.Gray, fontSize = 12.sp)
        }
        DropdownMenu(expanded = showActionMenu, onDismissRequest = { showActionMenu = false }, modifier = Modifier.background(Color.DarkGray)) {
            // 点击后触发 showEditDialog
            DropdownMenuItem(text = { Text("Manage Conversation", color = Color.White) }, onClick = { showActionMenu = false; showEditDialog = true })
            DropdownMenuItem(text = { Text("Clear Message", color = Color.White) }, onClick = { showActionMenu = false; showClearConfirm = true })
            DropdownMenuItem(text = { Text("Delete Conversation", color = Color.White) }, onClick = { showActionMenu = false; showDeleteConfirm = true })
        }
    }

    // 新增：编辑备注的弹窗
    if (showEditDialog) {
        var newAlias by remember { mutableStateOf(convo.alias) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            containerColor = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            title = { Text("Edit Alias") },
            text = {
                OutlinedTextField(
                    value = newAlias, onValueChange = { newAlias = it },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
            },
            confirmButton = { TextButton(onClick = { onUpdateAlias(newAlias); showEditDialog = false }) { Text("Save", color = Color(0xFF00FF00)) } },
            dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Cancel", color = Color.Gray) } }
        )
    }

    if (showClearConfirm) ConfirmDialog("Clear all local messages?", onDismiss = { showClearConfirm = false }, onConfirm = { onClear(); showClearConfirm = false })
    if (showDeleteConfirm) ConfirmDialog("Delete conversation and all messages?", onDismiss = { showDeleteConfirm = false }, onConfirm = { onDelete(); showDeleteConfirm = false })
}