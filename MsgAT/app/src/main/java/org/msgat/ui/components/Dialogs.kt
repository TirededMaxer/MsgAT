package org.msgat.ui.components

import androidx.compose.foundation.background
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

@Composable
// Shows a dialog for initiating a new chat conversation using a target UUID.
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
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
            }
        },
        confirmButton = { TextButton(onClick = { if(uuid.isNotBlank()) onConfirm(uuid.trim()) }) { Text("Add", color = Color(0xFF00FF00)) } },
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
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
    onResetIdentity: () -> Unit
) {

    var ip by remember { mutableStateOf(initialIp) }
    var port by remember { mutableStateOf(initialPort) }
    var uuid by remember { mutableStateOf(initialUuid) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false 
        )
    ) {

        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f) 
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
                    unfocusedTextColor = Color.White
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
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            val context = androidx.compose.ui.platform.LocalContext.current
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

                TextButton(onClick = { onConfirm(ip, port, uuid) }) {
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