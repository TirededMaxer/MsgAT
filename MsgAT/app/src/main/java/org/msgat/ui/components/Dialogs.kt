package org.msgat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun AddConversationDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var uuid by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E), // 深灰色背景
        titleContentColor = Color.White,
        title = { Text("Add Conversation") },
        text = {
            Column {
                OutlinedTextField(
                    value = uuid, onValueChange = { uuid = it },
                    label = { Text("Target UUID", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = alias, onValueChange = { alias = it },
                    label = { Text("Alias", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
            }
        },
        confirmButton = { TextButton(onClick = { if(uuid.isNotBlank()) onConfirm(uuid, alias) }) { Text("Add", color = Color(0xFF00FF00)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } }
    )
}

@Composable
fun SettingsDialog(
    initialIp: String,
    initialPort: String,
    initialUuid: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
    onResetIdentity: () -> Unit,

    ) {
    // 完全保留你的原有状态逻辑
    var ip by remember { mutableStateOf(initialIp) }
    var port by remember { mutableStateOf(initialPort) }
    var uuid by remember { mutableStateOf(initialUuid) }

    // 自定义 Dialog（替换系统 AlertDialog）
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false // 允许自定义宽度
        )
    ) {
        // 弹窗主体容器，还原你原来的深色样式
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f) // 弹窗宽度（手机端90%）
                .background(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(24.dp) // 圆角和原弹窗一致
                )
                .padding(24.dp)
        ) {
            // 标题（和原样式一致）
            Text(
                text = "Network Settings",
                color = Color.White,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            // 输入框区域（完全不变）
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
            OutlinedTextField(
                value = uuid,
                onValueChange = { uuid = it },
                label = { Text("My UUID", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ======================
            // 自由自定义按钮区域
            // 想加几个按钮就加几个
            // ======================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                // ✅ 你可以无限加按钮
                TextButton(onClick = {
                    onResetIdentity()
                }) {
                    Text("Reset UUID", color = Color.Red)
                }

                // 取消按钮
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color.Gray)
                }

                // 保存按钮
                TextButton(onClick = { onConfirm(ip, port, uuid) }) {
                    Text("Save", color = Color(0xFF00FF00))
                }
            }
        }
    }
}

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