package org.msgat.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.msgat.utils.MediaHelper.saveBase64ToGallery
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SyncState { DISCONNECTED, SYNCING, SYNCED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    alias: String,
    syncState: SyncState,
    messages: List<MessageUIModel>,
    onBack: () -> Unit,
    onSendMessage: (String, () -> Unit) -> Unit,
    onSelectMedia: (android.net.Uri, () -> Unit) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onSelectMedia(uri) {
                android.widget.Toast.makeText(context, "Send content Failed", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(alias)
                        Spacer(modifier = Modifier.width(8.dp))
                        when (syncState) {
                            SyncState.DISCONNECTED -> Box(modifier = Modifier.size(10.dp).background(Color.Red, RoundedCornerShape(50)))
                            SyncState.SYNCING -> CircularProgressIndicator(color = Color.Green, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            SyncState.SYNCED -> Icon(Icons.Default.Check, contentDescription = "Synced", tint = Color.Green, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            )
        },
        bottomBar = {
            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).navigationBarsPadding().imePadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Black, unfocusedContainerColor = Color.Black,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                    ),
                    maxLines = 5,
                    shape = RoundedCornerShape(8.dp),
                    placeholder = { Text("Message...", color = Color.Gray) }
                )

                IconButton(
                    onClick = {
                        if (inputText.isNotEmpty()) {
                            val textToSend = inputText
                            inputText = ""
                            onSendMessage(textToSend) {
                                inputText = textToSend
                                android.widget.Toast.makeText(context, "Send content failed", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            // 【核心修改】：把 ImageOnly 改成了 ImageAndVideo，允许选视频！
                            photoPickerLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        }
                    },
                    modifier = Modifier.background(if (inputText.isNotEmpty()) Color(0xFF00C853) else Color.Transparent, RoundedCornerShape(50))
                ) {
                    if (inputText.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                    } else {
                        Icon(Icons.Default.Add, contentDescription = "Add Media", tint = Color.White)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 8.dp),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(msg)
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun ChatBubble(msg: MessageUIModel) {
    val isMe = msg.isMine
    val context = LocalContext.current

    // 格式化时间戳
    val timeString = remember(msg.timestamp) {
        val sdf = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
        sdf.format(Date(msg.timestamp))
    }

    // 外层 Row：负责把这坨内容整体推向屏幕左边(对方)或右边(我)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        // 内层 Column：负责让气泡和时间戳严格上下排列
        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
        ) {
            // 1. 消息气泡主体 (在上面)
            Box(
                modifier = Modifier
                    .background(
                        color = if (isMe) Color(0xFF00C853) else Color(0xFF2A2A2A),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
                    .widthIn(max = 250.dp)
            ) {
                if (msg.ext == "txt") {
                    Text(text = msg.content, color = Color.White, fontSize = 16.sp)
                } else {
                    Button(
                        onClick = {
                            val success = saveBase64ToGallery(context, msg.content, msg.ext)
                            if (success) {
                                android.widget.Toast.makeText(context, "Saved to Gallery", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("🖼️ Save Media", color = Color.White)
                    }
                }
            }

            // 2. 时间戳 (在下面)
            Text(
                text = timeString,
                color = Color.DarkGray, // 用深灰色，降低视觉干扰
                fontSize = 10.sp,
                // 这里加一点 padding 把它和上面的气泡稍微隔开一点点
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}