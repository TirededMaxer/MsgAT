package org.msgat.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.msgat.utils.MediaHelper.saveBase64ToGallery
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SyncState { DISCONNECTED, SYNCING, SYNCED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// Renders the main chat interface including the message history and input fields.
fun ChatScreen(
    alias: String,
    syncState: SyncState,
    messages: androidx.paging.compose.LazyPagingItems<MessageUIModel>,
    onBack: () -> Unit,
    onSendMessage: (String, () -> Unit) -> Unit,
    onSelectMedia: (android.net.Uri, () -> Unit) -> Unit,
    onStartCall: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val firstMessageId = messages.itemSnapshotList.items.firstOrNull()?.id
    LaunchedEffect(firstMessageId) {
        if (firstMessageId != null) {
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
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface) }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {

                        Text(alias, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        Spacer(modifier = Modifier.width(8.dp))
                        when (syncState) {
                            SyncState.DISCONNECTED -> Box(modifier = Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, RoundedCornerShape(50)))
                            SyncState.SYNCING -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            SyncState.SYNCED -> Icon(Icons.Default.Check, contentDescription = "Synced", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                // actions removed
            )
        },
        bottomBar = {
            var showAttachmentMenu by remember { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it; if (it.isNotEmpty()) showAttachmentMenu = false },
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.background, unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            focusedTextColor = MaterialTheme.colorScheme.onBackground, unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                        ),
                        maxLines = 5,
                        shape = RoundedCornerShape(20.dp),
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
                                showAttachmentMenu = !showAttachmentMenu
                            }
                        },
                        modifier = Modifier.background(if (inputText.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
                    ) {
                        if (inputText.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                        } else {
                            Icon(Icons.Default.Add, contentDescription = "Add Media", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(visible = showAttachmentMenu && inputText.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                            photoPickerLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                            showAttachmentMenu = false
                        }) {
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(60.dp)) {
                                Icon(Icons.Default.Add, contentDescription = "Media", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(16.dp))
                            }
                            Text("Photos", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                            onStartCall()
                            showAttachmentMenu = false
                        }) {
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(60.dp)) {
                                Icon(Icons.Default.Call, contentDescription = "Voice Call", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(16.dp))
                            }
                            Text("Voice Call", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 8.dp)) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(messages.itemCount, key = { index -> messages[index]?.id ?: index }) { index ->
                    val msg = messages[index]
                    if (msg != null) {
                        ChatBubble(msg)
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
                if (messages.loadState.append is androidx.paging.LoadState.Loading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
// Renders an individual chat message bubble with dynamic styling based on sender.
fun ChatBubble(msg: MessageUIModel) {
    val context = LocalContext.current
    val isMe = msg.isMine
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeString = formatter.format(Date(msg.timestamp))

    var showSaveDialog by remember { mutableStateOf(false) }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Save Media") },
            text = { Text("Do you want to save this media to your gallery?", color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = {
                TextButton(onClick = {
                    val uri = saveBase64ToGallery(context, msg.content, msg.ext)
                    if (uri != null) android.widget.Toast.makeText(context, "Saved to Gallery", android.widget.Toast.LENGTH_SHORT).show()
                    else android.widget.Toast.makeText(context, "Failed to save", android.widget.Toast.LENGTH_SHORT).show()
                    showSaveDialog = false
                }) { Text("Confirm", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel", color = Color.Gray) }
            }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isMe) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.DarkGray,
                modifier = Modifier.padding(end = 8.dp).size(40.dp)
            ) {
                if (msg.senderAvatar.isNotEmpty()) {
                    androidx.compose.foundation.Image(
                        painter = coil.compose.rememberAsyncImagePainter(msg.senderAvatar),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White) 
                }
            }
        }

        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
            modifier = Modifier.weight(1f, fill = false)
        ) {

            Box(
                modifier = Modifier
                    .background(
                        color = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        shape = if (isMe) RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
                                else RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
                    )
                    .padding(12.dp)
                    .widthIn(max = 250.dp)
            ) {
                if (msg.ext == "txt") {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                        val annotatedString = androidx.compose.ui.text.buildAnnotatedString {
                            val words = msg.content.split(Regex("(?<=\\s)|(?=\\s)"))
                            for (word in words) {
                                if (word.startsWith("http://") || word.startsWith("https://")) {
                                    pushStringAnnotation(tag = "URL", annotation = word)
                                    withStyle(style = androidx.compose.ui.text.SpanStyle(color = Color(0xFF64B5F6), textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                                        append(word)
                                    }
                                    pop()
                                } else {
                                    append(word)
                                }
                            }
                        }

                        androidx.compose.foundation.text.ClickableText(
                            text = annotatedString,
                            style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                            onClick = { offset ->
                                annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                    .firstOrNull()?.let { annotation ->
                                        try {
                                            uriHandler.openUri(annotation.item)
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "Invalid URL", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                            }
                        )
                    }
                } else if (msg.ext.lowercase() in listOf("jpg", "jpeg", "png", "webp")) {
                    val bitmap = remember(msg.content) {
                        try {
                            var cleanBase64 = msg.content.replace(Regex("\\s+"), "")
                            cleanBase64 = cleanBase64.replace("=", "")
                            val padding = cleanBase64.length % 4
                            if (padding > 0) {
                                cleanBase64 += "=".repeat(4 - padding)
                            }
                            val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        } catch(e:Exception) { null }
                    }
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap,
                            contentDescription = "Image",
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .combinedClickable(
                                    onClick = { 
                                        val uri = saveBase64ToGallery(context, msg.content, msg.ext)
                                        if (uri != null) {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                setDataAndType(uri, "image/*")
                                                flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            }
                                            context.startActivity(intent)
                                        }
                                    },
                                    onLongClick = {
                                        showSaveDialog = true
                                    }
                                )
                        )
                    } else {
                        Text("🖼️ Corrupted Image", color = Color.Red)
                    }
                } else {
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(Color.Black)
                        .combinedClickable(
                            onClick = {
                                val uri = saveBase64ToGallery(context, msg.content, msg.ext)
                                if (uri != null) {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "video/*")
                                        flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    }
                                    context.startActivity(intent)
                                } else {
                                    android.widget.Toast.makeText(context, "Failed to preview video", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            onLongClick = {
                                showSaveDialog = true
                            }
                        ), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Check, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Video - Tap to Play", color = Color.White)
                        }
                    }
                }
            }

            Text(
                text = timeString,
                color = Color.DarkGray, 
                fontSize = 10.sp,

                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
            )
        }

        if (isMe) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.DarkGray,
                modifier = Modifier.padding(start = 8.dp).size(40.dp)
            ) {
                if (msg.senderAvatar.isNotEmpty()) {
                    androidx.compose.foundation.Image(
                        painter = coil.compose.rememberAsyncImagePainter(msg.senderAvatar),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White) 
                }
            }
        }
    }
}