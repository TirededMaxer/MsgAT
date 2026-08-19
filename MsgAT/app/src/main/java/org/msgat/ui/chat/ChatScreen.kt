package org.msgat.ui.chat

import android.content.Context
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material.icons.filled.Pause
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.togetherWith

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
    onSendMessage: (text: String, replyToId: String?, replyToSender: String?, replyToContent: String?, onFail: () -> Unit) -> Unit,
    onSelectMedia: (uri: android.net.Uri, replyToId: String?, replyToSender: String?, replyToContent: String?, onFail: () -> Unit) -> Unit,
    onStartCall: () -> Unit,
    onMarkAsRead: () -> Unit,
    onSendReadAck: (messageId: String) -> Unit = {},
    onRevokeMessage: (messageId: String) -> Unit = {},
    onEditMessage: (messageId: String, newContent: String) -> Unit = { _, _ -> }
) {
    var inputText by remember { mutableStateOf("") }
    var replyingToMessage by remember { mutableStateOf<MessageUIModel?>(null) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var editingMsg by remember { mutableStateOf<MessageUIModel?>(null) }
    var editingText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var selectedMedia by remember { mutableStateOf<MessageUIModel?>(null) }

    var previousMessageId by remember { mutableStateOf<String?>(null) }
    val firstMessage = messages.itemSnapshotList.items.firstOrNull()
    val isNewUnread = firstMessage != null && !firstMessage.isMine && !firstMessage.isRead
    val isAtBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 2 } }

    LaunchedEffect(isAtBottom, isNewUnread, firstMessage?.id) {
        if (isAtBottom && firstMessage != null && !firstMessage.isMine) {
            onMarkAsRead()
            onSendReadAck(firstMessage.id)
        }
    }
    
    LaunchedEffect(firstMessage?.id) {
        if (firstMessage != null) {
            if (previousMessageId == null) {
                previousMessageId = firstMessage.id
            } else if (previousMessageId != firstMessage.id) {
                previousMessageId = firstMessage.id
                if (firstMessage.isMine) {
                    if (isAtBottom) {
                        listState.animateScrollToItem(0)
                    } else {
                        listState.scrollToItem(0)
                    }
                } else if (isAtBottom) {
                    listState.animateScrollToItem(0)
                }
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val replyId = replyingToMessage?.id
            val replySender = if (replyingToMessage?.isMine == true) "Me" else alias
            val replyContent = if (replyingToMessage?.ext == "txt") replyingToMessage?.content else "[${replyingToMessage?.ext?.uppercase()}]"
            replyingToMessage = null
            onSelectMedia(uri, replyId, replySender, replyContent) {
                android.widget.Toast.makeText(context, "Send content Failed", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (editingMsg != null) {
        AlertDialog(
            onDismissRequest = { editingMsg = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Edit Message", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                OutlinedTextField(
                    value = editingText,
                    onValueChange = { editingText = it },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetMsg = editingMsg
                        if (targetMsg != null && editingText.isNotBlank()) {
                            onEditMessage(targetMsg.id, editingText)
                        }
                        editingMsg = null
                    }
                ) { Text("Save", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { editingMsg = null }) { Text("Cancel", color = Color.Gray) }
            }
        )
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
            Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = replyingToMessage != null,
                    enter = androidx.compose.animation.expandVertically(animationSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
                ) {
                    replyingToMessage?.let { replyMsg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(32.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Replying to ${if (replyMsg.isMine) "Me" else alias}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                val previewText = if (replyMsg.ext == "txt") replyMsg.content else "[${replyMsg.ext.uppercase()}]"
                                Text(
                                    text = previewText,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { replyingToMessage = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel reply", tint = Color.Gray)
                            }
                        }
                    }
                }

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
                                val replyId = replyingToMessage?.id
                                val replySender = if (replyingToMessage?.isMine == true) "Me" else alias
                                val replyContent = if (replyingToMessage?.ext == "txt") replyingToMessage?.content else "[${replyingToMessage?.ext?.uppercase()}]"
                                inputText = ""
                                replyingToMessage = null
                                onSendMessage(textToSend, replyId, replySender, replyContent) {
                                    inputText = textToSend
                                    android.widget.Toast.makeText(context, "Send content failed", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                showAttachmentMenu = !showAttachmentMenu
                            }
                        },
                        modifier = Modifier.background(if (inputText.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
                    ) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = inputText.isNotEmpty(),
                            transitionSpec = {
                                (androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.5f))
                                    .togetherWith(androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.5f))
                            },
                            label = "SendButtonMorph"
                        ) { hasText ->
                            if (hasText) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                            } else {
                                Icon(Icons.Default.Add, contentDescription = "Add Media", tint = MaterialTheme.colorScheme.onSurface)
                            }
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
                            Text("Media", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
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
        val coroutineScope = rememberCoroutineScope()
        Box(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 8.dp)) {
            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(messages.itemCount, key = { index -> 
                        val msg = messages.peek(index)
                        if (msg != null) "${msg.id}_${msg.status}_${msg.isRevoked}_${msg.isEdited}" else index 
                    }) { index ->
                        val msg = messages[index]
                        if (msg != null) {
                            ChatBubble(
                                msg = msg,
                                onMediaClick = { selectedMedia = it },
                                onReply = { replyingToMessage = it },
                                onQuotedClick = { targetId ->
                                    val items = messages.itemSnapshotList.items
                                    val targetIndex = items.indexOfFirst { it.id == targetId }
                                    if (targetIndex != -1) {
                                        highlightedMessageId = targetId
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(targetIndex)
                                            kotlinx.coroutines.delay(1500)
                                            if (highlightedMessageId == targetId) {
                                                highlightedMessageId = null
                                            }
                                        }
                                    } else {
                                        android.widget.Toast.makeText(context, "Quoted message not found", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                isHighlighted = msg.id == highlightedMessageId,
                                onRevoke = { onRevokeMessage(it.id) },
                                onEdit = { msgToEdit -> editingMsg = msgToEdit; editingText = msgToEdit.content },
                                onReEdit = { textToRestore -> inputText = textToRestore }
                            )
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

            val showScrollToBottom = !isAtBottom
            
            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollToBottom,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp, end = 8.dp),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it })
            ) {
                val fabContainerColor = if (isNewUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                val fabContentColor = if (isNewUnread) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                FloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    modifier = Modifier.size(40.dp),
                    containerColor = fabContainerColor,
                    contentColor = fabContentColor,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll to bottom"
                    )
                }
            }
        }
    }

    if (selectedMedia != null) {
        InAppMediaViewer(
            msg = selectedMedia!!,
            onDismiss = { selectedMedia = null }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
// Renders an individual chat message bubble with dynamic styling based on sender.
fun ChatBubble(
    msg: MessageUIModel,
    onMediaClick: (MessageUIModel) -> Unit = {},
    onReply: (MessageUIModel) -> Unit = {},
    onQuotedClick: (String) -> Unit = {},
    isHighlighted: Boolean = false,
    onRevoke: (MessageUIModel) -> Unit = {},
    onEdit: (MessageUIModel) -> Unit = {},
    onReEdit: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val isMe = msg.isMine
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeString = formatter.format(Date(msg.timestamp))

    var showSaveDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Save Media") },
            text = { Text("Do you want to save this media to your gallery?", color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = {
                TextButton(onClick = {
                    val mediaFile = org.msgat.core.media.MediaStorageManager.getOrExtractMediaFile(context, msg.id, msg.ext, msg.content)
                    val uri = if (mediaFile != null) org.msgat.core.media.MediaStorageManager.saveToGallery(context, mediaFile, msg.ext) else null
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

    if (msg.isRevoked) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isMe) "You revoked a message" else "Peer revoked a message",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                if (isMe && msg.ext == "txt" && msg.content.isNotEmpty()) {
                    Text(
                        text = "Re-edit",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable { onReEdit(msg.content) }
                    )
                }
            }
        }
    } else {
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
            Box {
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Reply") },
                        onClick = {
                            showMenu = false
                            onReply(msg)
                        }
                    )
                    if (msg.ext == "txt") {
                        DropdownMenuItem(
                            text = { Text("Copy Text") },
                            onClick = {
                                showMenu = false
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("MsgAT", msg.content)
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    val canRevokeOrEdit = isMe && (System.currentTimeMillis() - msg.timestamp <= 120_000L)
                    if (canRevokeOrEdit && !msg.isRevoked) {
                        DropdownMenuItem(
                            text = { Text("Revoke") },
                            onClick = {
                                showMenu = false
                                onRevoke(msg)
                            }
                        )
                        if (msg.ext == "txt") {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = {
                                    showMenu = false
                                    onEdit(msg)
                                }
                            )
                        }
                    }
                    if (msg.ext.lowercase() in listOf("jpg", "jpeg", "png", "webp", "mp4", "m4a")) {
                        DropdownMenuItem(
                            text = { Text("Save Media") },
                            onClick = {
                                showMenu = false
                                showSaveDialog = true
                            }
                        )
                    }
                }

                Column(
                    horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                ) {

                    Box(
                        modifier = Modifier
                            .run {
                                if (msg.ext == "txt") {
                                    val normalBg = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                                    background(
                                        color = normalBg,
                                        shape = if (isMe) RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
                                                else RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
                                    )
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { showMenu = true }
                                    )
                                    .padding(12.dp)
                                } else {
                                    clip(RoundedCornerShape(12.dp))
                                }
                            }
                            .widthIn(max = 250.dp)
                    ) {
                if (msg.ext == "txt") {
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

                    Text(
                        text = annotatedString,
                        style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                val urlAnnotation = annotatedString.getStringAnnotations(tag = "URL", start = 0, end = annotatedString.length).firstOrNull()
                                if (urlAnnotation != null) {
                                    try {
                                        uriHandler.openUri(urlAnnotation.item)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "Invalid URL", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onLongClick = {
                                showMenu = true
                            }
                        )
                    )
                } else if (msg.ext.lowercase() in listOf("jpg", "jpeg", "png", "webp")) {
                    val mediaFile = remember(msg.id, msg.content) {
                        org.msgat.core.media.MediaStorageManager.getOrExtractMediaFile(context, msg.id, msg.ext, msg.content)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(Color.DarkGray)
                            .combinedClickable(
                                onClick = { onMediaClick(msg) },
                                onLongClick = { showMenu = true }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (mediaFile != null && mediaFile.exists()) {
                            coil.compose.AsyncImage(
                                model = coil.request.ImageRequest.Builder(context)
                                    .data(mediaFile)
                                    .size(400, 400)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Image",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                } else {
                    val mediaFile = remember(msg.id, msg.content) {
                        org.msgat.core.media.MediaStorageManager.getOrExtractMediaFile(context, msg.id, msg.ext, msg.content)
                    }
                    val thumbnail by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(initialValue = null, mediaFile) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            if (mediaFile != null && mediaFile.exists()) {
                                try {
                                    val retriever = android.media.MediaMetadataRetriever()
                                    retriever.setDataSource(mediaFile.absolutePath)
                                    val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    retriever.release()
                                    frame
                                } catch (_: Exception) { null }
                            } else null
                        }
                    }
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.Black)
                        .combinedClickable(
                            onClick = { onMediaClick(msg) },
                            onLongClick = { showMenu = true }
                        ), contentAlignment = Alignment.Center) {
                        val currentThumbnail = thumbnail
                        if (currentThumbnail != null) {
                            androidx.compose.foundation.Image(
                                bitmap = currentThumbnail.asImageBitmap(),
                                contentDescription = "Video Thumbnail",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
            }
            }

            if (msg.replyToId != null) {
                val senderName = msg.replyToSender ?: "User"
                val rawContent = msg.replyToContent ?: ""
                val displayContent = if (rawContent.startsWith("[") && rawContent.endsWith("]")) {
                    "[Media]"
                } else {
                    rawContent
                }
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF38383A))
                        .clickable { msg.replyToId?.let { onQuotedClick(it) } }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .widthIn(max = 250.dp)
                ) {
                    Text(
                        text = "$senderName: $displayContent",
                        fontSize = 11.sp,
                        color = Color(0xFFD0D0D0),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
            ) {
                Text(
                    text = if (msg.isEdited) "$timeString (edited)" else timeString,
                    color = Color.DarkGray, 
                    fontSize = 10.sp
                )
                if (isMe) {
                    Spacer(modifier = Modifier.width(4.dp))
                    when (msg.status) {
                        0 -> CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Color.Gray)
                        1 -> Icon(Icons.Default.Check, contentDescription = "Sent", tint = Color.Gray, modifier = Modifier.size(12.dp))
                        2 -> Box(modifier = Modifier.width(16.dp), contentAlignment = Alignment.CenterStart) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                            Icon(Icons.Default.Check, contentDescription = "Delivered", tint = Color.Gray, modifier = Modifier.size(12.dp).offset(x = 4.dp))
                        }
                        else -> Box(modifier = Modifier.width(16.dp), contentAlignment = Alignment.CenterStart) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                            Icon(Icons.Default.Check, contentDescription = "Read", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp).offset(x = 4.dp))
                        }
                    }
                }
            }
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
}

@Composable
fun InAppMediaViewer(msg: MessageUIModel, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
    ) {
        if (msg.ext.lowercase() in listOf("jpg", "jpeg", "png", "webp")) {
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

            val mediaFile = remember(msg.id, msg.content) {
                org.msgat.core.media.MediaStorageManager.getOrExtractMediaFile(context, msg.id, msg.ext, msg.content)
            }

            if (mediaFile != null && mediaFile.exists()) {
                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(context)
                        .data(mediaFile)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Full Screen Image",
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            } else {
                Text("Corrupted Image", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        } else {
            val videoFile = remember(msg.id, msg.content) {
                org.msgat.core.media.MediaStorageManager.getOrExtractMediaFile(context, msg.id, msg.ext, msg.content)
            }
            if (videoFile != null && videoFile.exists()) {
                VideoPlayer(videoFile = videoFile, onDismiss = onDismiss)
            } else {
                Text("Loading Video...", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
fun VideoPlayer(videoFile: java.io.File, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val exoPlayer = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            val mediaItem = androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(videoFile))
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var totalDuration by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(isPlaying) {
        while (isActive && isPlaying) {
            currentPosition = exoPlayer.currentPosition
            delay(100)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                androidx.media3.ui.PlayerView(context).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .clickable { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                val currentText = String.format("%02d:%02d", java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(currentPosition), java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(currentPosition) % 60)
                val totalText = String.format("%02d:%02d", java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(totalDuration), java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(totalDuration) % 60)
                Text("$currentText / $totalText", color = Color.White, fontSize = 12.sp)
            }
            androidx.compose.material3.Slider(
                value = if (totalDuration > 0) currentPosition.toFloat() / totalDuration.toFloat() else 0f,
                onValueChange = { percent ->
                    val seekTo = (percent * totalDuration).toLong()
                    exoPlayer.seekTo(seekTo)
                    currentPosition = seekTo
                },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.Gray
                )
            )
        }
    }
}