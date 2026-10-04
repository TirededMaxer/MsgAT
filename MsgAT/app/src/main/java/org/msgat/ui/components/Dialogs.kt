package org.msgat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.PasswordVisualTransformation

// Bundles all connection settings (relay server + optional TURN fallback).
data class ServerSettings(
    val ip: String,
    val port: String,
    val uuid: String,
    val turnServer: String,
    val turnPort: String,
    val turnUsername: String,
    val turnPassword: String,
    val turnUseTls: Boolean,
    val serverCertificateSha256: String = "",
    val allowDevelopmentCertificate: Boolean = true
)

@Composable
// Shows a dialog for initiating a new chat conversation using a target UUID.
fun AddConversationDialog(myUuid: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var uuid by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color.White,
        title = { Text("Add Friend") },
        text = {
            Column {
                OutlinedTextField(
                    value = uuid, onValueChange = { uuid = it; error = null },
                    label = { Text("Target UUID", color = Color.Gray) },
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.Gray)
                )
            }
        },
        confirmButton = { TextButton(onClick = {
            val normalized = uuid.trim().lowercase()
            error = when {
                !normalized.matches(Regex("[a-f0-9]{64}")) -> "A friend UUID must contain exactly 64 hexadecimal characters."
                normalized == myUuid.lowercase() -> "You cannot add your own UUID."
                else -> null
            }
            if (error == null) onConfirm(normalized)
        }) { Text("Add", color = Color(0xFF00FF00)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } }
    )
}

@Composable
// Presents a settings menu to configure the remote server IP and port.
fun SettingsDialog(
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
    onDismiss: () -> Unit,
    onConfirm: (ServerSettings) -> Unit,
    onResetIdentity: () -> Unit
) {

    var ip by remember { mutableStateOf(initialIp) }
    var port by remember { mutableStateOf(initialPort) }
    var uuid by remember { mutableStateOf(initialUuid) }
    var turnServer by remember { mutableStateOf(initialTurnServer) }
    var turnPort by remember { mutableStateOf(initialTurnPort) }
    var turnUsername by remember { mutableStateOf(initialTurnUsername) }
    var turnPassword by remember { mutableStateOf(initialTurnPassword) }
    var turnUseTls by remember { mutableStateOf(initialTurnUseTls) }
    val prefs = org.msgat.MsgATApp.instance.securePrefs
    var certificatePin by remember { mutableStateOf(prefs.serverCertificateSha256.orEmpty()) }
    var allowDevelopmentCertificate by remember { mutableStateOf(prefs.allowDevelopmentCertificate) }
    val normalizedPin = certificatePin.replace(":", "").replace(" ", "").lowercase()
    val validSettings = ip.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535 &&
        (turnServer.isBlank() || (turnPort.toIntOrNull() ?: 0) in 1..65535) &&
        (normalizedPin.isEmpty() || normalizedPin.matches(Regex("[a-f0-9]{64}")))
    var showAdvancedNetwork by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {

        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .background(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(24.dp)
        ) {

            Text(
                text = "Settings",
                color = Color.White,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.DarkGray,
                    modifier = Modifier.size(64.dp)
                ) {
                    if (!initialAvatarPath.isNullOrEmpty()) {
                        androidx.compose.foundation.Image(
                            painter = coil.compose.rememberAsyncImagePainter(initialAvatarPath),
                            contentDescription = "My Avatar",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(initialUuid.take(1).uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(initialNickname ?: "Me", color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    Text("UUID: ${initialUuid.take(8)}...", color = Color.Gray, fontSize = 12.sp)
                }
            }

            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("Server IP / Domain", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.Gray
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Server Port", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.Gray
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = { showAdvancedNetwork = !showAdvancedNetwork }) {
                Text(if (showAdvancedNetwork) "Hide Advanced Network Settings" else "Advanced Network Settings")
            }

            androidx.compose.animation.AnimatedVisibility(visible = showAdvancedNetwork) {
                Column {
                    Text(
                        text = "TURN Relay (Optional — P2P fallback. Leave empty for pure P2P.)",
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = turnServer,
                    onValueChange = { turnServer = it },
                    label = { Text("TURN Server", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.weight(0.6f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = turnPort,
                    onValueChange = { turnPort = it },
                    label = { Text("TURN Port", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.weight(0.4f)
                )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = turnUsername,
                    onValueChange = { turnUsername = it },
                    label = { Text("TURN Username", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.weight(0.5f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = turnPassword,
                    onValueChange = { turnPassword = it },
                    label = { Text("TURN Password", color = Color.Gray) },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.weight(0.5f)
                )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Use TLS (turns://)", color = Color.Gray, fontSize = 14.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = turnUseTls, onCheckedChange = { turnUseTls = it })
                    }
                    OutlinedTextField(value = certificatePin, onValueChange = { certificatePin = it },
                        label = { Text("TLS SHA-256 pin") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = allowDevelopmentCertificate, onCheckedChange = { allowDevelopmentCertificate = it })
                        Text("Allow untrusted development certificate", color = Color.Gray)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            val context = androidx.compose.ui.platform.LocalContext.current
            @Suppress("DEPRECATION")
            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

            Button(
                onClick = {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(initialUuid))
                    android.widget.Toast.makeText(context, "UUID Copied", android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Copy My UUID", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {

                TextButton(onClick = {
                    onResetIdentity()
                }) {
                    Text("Reset UUID", color = Color.Red)
                }

                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color.Gray)
                }

                TextButton(enabled = validSettings, onClick = { onConfirm(ServerSettings(ip.trim(), port, uuid, turnServer, turnPort, turnUsername, turnPassword, turnUseTls, normalizedPin, allowDevelopmentCertificate)) }) {
                    Text("Save", color = Color(0xFF00FF00))
                }
            }
        }
    }
}

@Composable
// Shows a generic confirmation dialog for destructive or critical actions.
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
