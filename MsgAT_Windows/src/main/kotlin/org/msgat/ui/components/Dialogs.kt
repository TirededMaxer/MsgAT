package org.msgat.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.msgat.utils.DesktopUtils
import org.msgat.utils.ImageUtils
import java.io.File

@Composable
fun AddConversationDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var uuid by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color.White,
        title = { Text("Add Friend") },
        text = {
            Column {
                OutlinedTextField(
                    value = uuid, onValueChange = { uuid = it },
                    label = { Text("Target UUID", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (uuid.isNotBlank()) onConfirm(uuid.trim()) }) { Text("Add", color = Color(0xFF00FF00)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } }
    )
}

@Composable
fun SettingsDialog(
    initialIp: String,
    initialPort: String,
    initialUuid: String,
    initialAvatarPath: String? = null,
    initialNickname: String? = null,
    initialTurnServer: String? = null,
    initialTurnPort: String = "",
    initialTurnUsername: String? = null,
    initialTurnPassword: String = "",
    initialTurnUseTls: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (ServerSettings) -> Unit,
    onResetIdentity: () -> Unit
) {
    var ip by remember { mutableStateOf(initialIp) }
    var port by remember { mutableStateOf(initialPort) }
    var turnServer by remember { mutableStateOf(initialTurnServer ?: "") }
    var turnPort by remember { mutableStateOf(initialTurnPort) }
    var turnUsername by remember { mutableStateOf(initialTurnUsername ?: "") }
    var turnPassword by remember { mutableStateOf(initialTurnPassword) }
    var turnUseTls by remember { mutableStateOf(initialTurnUseTls) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(color = Color(0xFF1E1E1E), shape = RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Text(
                text = "Settings",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            // Read-Only Identity Profile Row (Locked after initial setup)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.DarkGray,
                    modifier = Modifier.size(56.dp)
                ) {
                    val avatarFile = if (!initialAvatarPath.isNullOrEmpty()) File(initialAvatarPath) else null
                    val bitmap = remember(initialAvatarPath) { ImageUtils.loadBitmap(avatarFile) }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(initialUuid.take(1).uppercase(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(initialNickname ?: "Me", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("UUID: ${initialUuid.take(12)}...", color = Color.Gray, fontSize = 12.sp)
                }
            }

            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("Server IP / Domain", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Server Port", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    DesktopUtils.copyToClipboard(initialUuid)
                    DesktopUtils.showNotification("Copied", "UUID copied to clipboard")
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Copy My UUID", fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "TURN Relay (Optional)",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "留空即纯 P2P 直连；当双端对称 NAT 打洞失败时可填一台自有 coturn 兜底。",
                color = Color.Gray,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            OutlinedTextField(
                value = turnServer,
                onValueChange = { turnServer = it },
                label = { Text("TURN Server (e.g. turn.example.com)", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = turnPort,
                onValueChange = { turnPort = it },
                label = { Text("TURN Port (default 3478)", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = turnUsername,
                    onValueChange = { turnUsername = it },
                    label = { Text("Username", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = turnPassword,
                    onValueChange = { turnPassword = it },
                    label = { Text("Password", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Checkbox(
                    checked = turnUseTls,
                    onCheckedChange = { turnUseTls = it },
                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FF00), uncheckedColor = Color.Gray)
                )
                Text("使用 TLS (turns://)", color = Color.Gray, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onResetIdentity) {
                    Text("Reset UUID", color = Color.Red, fontWeight = FontWeight.Bold)
                }

                Row {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = {
                        onConfirm(
                            ServerSettings(
                                ip = ip,
                                port = port,
                                turnServer = turnServer.trim().ifEmpty { null },
                                turnPort = turnPort.toIntOrNull() ?: 0,
                                turnUsername = turnUsername.trim().ifEmpty { null },
                                turnPassword = turnPassword,
                                turnUseTls = turnUseTls
                            )
                        )
                    }) {
                        Text("Save", color = Color(0xFF00FF00), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** 服务器连接 + 可选 TURN 中继设置（与 Android/macOS 一致） */
data class ServerSettings(
    val ip: String,
    val port: String,
    val turnServer: String?,
    val turnPort: Int,
    val turnUsername: String?,
    val turnPassword: String?,
    val turnUseTls: Boolean
)

@Composable
fun ConfirmDialog(message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("Warning") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Confirm", color = Color.Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } }
    )
}
