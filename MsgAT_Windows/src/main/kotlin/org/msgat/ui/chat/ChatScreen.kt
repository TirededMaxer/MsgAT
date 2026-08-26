package org.msgat.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.msgat.core.media.MediaStorageManager
import org.msgat.model.SyncState
import org.msgat.utils.AsyncImage
import org.msgat.utils.DesktopUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// Renders the main chat interface including the message history and input fields.
fun ChatScreen(
    alias: String,
    syncState: SyncState,
    messages: List<MessageUIModel>,
    onBack: (() -> Unit)?,
    onSendMessage: (text: String, replyToId: String?, replyToSender: String?, replyToContent: String?, onFail: () -> Unit) -> Unit,
    onSelectMedia: (file: File, replyToId: String?, replyToSender: String?, replyToContent: String?, onFail: () -> Unit) -> Unit,
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
    val listState = rememberLazyListState()
    var selectedMedia by remember { mutableStateOf<MessageUIModel?>(null) }

    var previousMessageId by remember { mutableStateOf<String?>(null) }
    val firstMessage = messages.firstOrNull()
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
                    colors = OutlinedTextFieldDefaults.colors(
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(alias, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.width(8.dp))
                        when (syncState) {
                            SyncState.DISCONNECTED -> Box(modifier = Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                            SyncState.SYNCING -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            SyncState.SYNCED -> Icon(Icons.Default.Check, contentDescription = "Synced", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            )
        },
        bottomBar = {
            var showAttachmentMenu by remember { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                AnimatedVisibility(
                    visible = replyingToMessage != null,
                    enter = androidx.compose.animation.expandVertically(animationSpec = spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) + fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + fadeOut()
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
                                    overflow = TextOverflow.Ellipsis
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
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            focusedTextColor = MaterialTheme.colorScheme.onBackground,
                            unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
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
                                    DesktopUtils.showNotification("Failed", "Send content failed")
                                }
                            } else {
                                showAttachmentMenu = !showAttachmentMenu
                            }
                        },
                        modifier = Modifier.background(
                            if (inputText.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                            CircleShape
                        )
                    ) {
                        AnimatedContent(
                            targetState = inputText.isNotEmpty(),
                            transitionSpec = {
                                (fadeIn() + scaleIn(initialScale = 0.5f))
                                    .togetherWith(fadeOut() + scaleOut(targetScale = 0.5f))
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

                AnimatedVisibility(visible = showAttachmentMenu && inputText.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                val file = DesktopUtils.openFileDialog("Select Image / Video / Media")
                                if (file != null && file.exists()) {
                                    val replyId = replyingToMessage?.id
                                    val replySender = if (replyingToMessage?.isMine == true) "Me" else alias
                                    val replyContent = if (replyingToMessage?.ext == "txt") replyingToMessage?.content else "[${replyingToMessage?.ext?.uppercase()}]"
                                    replyingToMessage = null
                                    showAttachmentMenu = false
                                    onSelectMedia(file, replyId, replySender, replyContent) {
                                        DesktopUtils.showNotification("Failed", "Send media failed")
                                    }
                                }
                            }
                        ) {
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(60.dp)) {
                                Icon(Icons.Default.Add, contentDescription = "Media", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(16.dp))
                            }
                            Text("Media", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                onStartCall()
                                showAttachmentMenu = false
                            }
                        ) {
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
                    items(messages, key = { msg -> "${msg.id}_${msg.status}_${msg.isRevoked}_${msg.isEdited}" }) { msg ->
                        ChatBubble(
                            msg = msg,
                            onMediaClick = { selectedMedia = it },
                            onReply = { replyingToMessage = it },
                            onQuotedClick = { targetId ->
                                val targetIndex = messages.indexOfFirst { it.id == targetId }
                                if (targetIndex != -1) {
                                    highlightedMessageId = targetId
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(targetIndex)
                                        delay(1500)
                                        if (highlightedMessageId == targetId) {
                                            highlightedMessageId = null
                                        }
                                    }
                                } else {
                                    DesktopUtils.showNotification("Not Found", "Quoted message not found")
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
            }

            val showScrollToBottom = !isAtBottom

            AnimatedVisibility(
                visible = showScrollToBottom,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp, end = 8.dp),
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
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
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp)
                ) {
                    Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom")
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

@OptIn(ExperimentalFoundationApi::class)
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
            text = { Text("Do you want to open and save this media file?", color = MaterialTheme.colorScheme.onSurface) },
            confirmButton = {
                TextButton(onClick = {
                    val mediaFile = MediaStorageManager.getOrExtractMediaFile(msg.id, msg.ext, msg.content)
                    if (mediaFile != null && mediaFile.exists()) {
                        DesktopUtils.openFile(mediaFile)
                        DesktopUtils.showNotification("Opened", "Media file opened in system viewer")
                    } else {
                        DesktopUtils.showNotification("Failed", "Media file not found")
                    }
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
                        AsyncImage(
                            filePath = msg.senderAvatar,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
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
                                    DesktopUtils.copyToClipboard(msg.content)
                                    DesktopUtils.showNotification("Copied", "Copied to clipboard")
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
                                val uriHandler = LocalUriHandler.current
                                val annotatedString = buildAnnotatedString {
                                    val words = msg.content.split(Regex("(?<=\\s)|(?=\\s)"))
                                    for (word in words) {
                                        if (word.startsWith("http://") || word.startsWith("https://")) {
                                            pushStringAnnotation(tag = "URL", annotation = word)
                                            withStyle(style = SpanStyle(color = Color(0xFF64B5F6), textDecoration = TextDecoration.Underline)) {
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
                                    style = TextStyle(color = Color.White, fontSize = 16.sp),
                                    modifier = Modifier.combinedClickable(
                                        onClick = {
                                            val urlAnnotation = annotatedString.getStringAnnotations(tag = "URL", start = 0, end = annotatedString.length).firstOrNull()
                                            if (urlAnnotation != null) {
                                                try {
                                                    uriHandler.openUri(urlAnnotation.item)
                                                } catch (_: Exception) {
                                                    DesktopUtils.showNotification("Error", "Invalid URL")
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
                                    MediaStorageManager.getOrExtractMediaFile(msg.id, msg.ext, msg.content)
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
                                        AsyncImage(
                                            filePath = mediaFile.absolutePath,
                                            contentDescription = "Image",
                                            contentScale = ContentScale.Crop,
                                            showLoadingIndicator = true,
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
                                    MediaStorageManager.getOrExtractMediaFile(msg.id, msg.ext, msg.content)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .background(Color.Black)
                                        .combinedClickable(
                                            onClick = { onMediaClick(msg) },
                                            onLongClick = { showMenu = true }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
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
                            overflow = TextOverflow.Ellipsis
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
                        AsyncImage(
                            filePath = msg.senderAvatar,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
    ) {
        if (msg.ext.lowercase() in listOf("jpg", "jpeg", "png", "webp")) {
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }

            val mediaFile = remember(msg.id, msg.content) {
                MediaStorageManager.getOrExtractMediaFile(msg.id, msg.ext, msg.content)
            }

            if (mediaFile != null && mediaFile.exists()) {
                AsyncImage(
                    filePath = mediaFile.absolutePath,
                    contentDescription = "Full Screen Image",
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
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
                MediaStorageManager.getOrExtractMediaFile(msg.id, msg.ext, msg.content)
            }
            if (videoFile != null && videoFile.exists()) {
                Box(
                    modifier = Modifier.fillMaxSize().clickable {
                        DesktopUtils.openFile(videoFile)
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play Video", tint = Color.White, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Click to play video with default system player", color = Color.White, fontSize = 14.sp)
                    }
                }
            } else {
                Text("Loading Video...", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
