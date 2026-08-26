package org.msgat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.msgat.core.media.MediaStorageManager
import org.msgat.utils.AsyncImage
import org.msgat.utils.AvatarCompressor
import org.msgat.utils.DesktopUtils
import java.io.File

@Composable
// Displays a dialog allowing the user to configure their initial nickname and avatar.
fun IdentitySetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (nickname: String, avatarPath: String) -> Unit
) {
    var nickname by remember { mutableStateOf("") }
    var avatarPath by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(400.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Setup Identity",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.DarkGray)
                    .clickable {
                        val file = DesktopUtils.openFileDialog("Select Avatar Image")
                        if (file != null && file.exists()) {
                            try {
                                val destFile = File(MediaStorageManager.getMediaDir(), "my_avatar.jpg")
                                AvatarCompressor.compressAndSaveAvatar(file, destFile, maxSize = 128)
                                avatarPath = destFile.absolutePath
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (avatarPath.isNotEmpty()) {
                    AsyncImage(
                        filePath = avatarPath,
                        contentDescription = "Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = "Tap to Pick Avatar",
                        color = Color.White,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("Nickname", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color.Gray)
                }

                TextButton(
                    onClick = {
                        if (nickname.isNotBlank() && avatarPath.isNotEmpty()) {
                            onConfirm(nickname.trim(), avatarPath)
                        }
                    },
                    enabled = nickname.isNotBlank() && avatarPath.isNotEmpty()
                ) {
                    val color = if (nickname.isNotBlank() && avatarPath.isNotEmpty()) Color(0xFF00FF00) else Color.DarkGray
                    Text("Confirm", color = color)
                }
            }
        }
    }
}
