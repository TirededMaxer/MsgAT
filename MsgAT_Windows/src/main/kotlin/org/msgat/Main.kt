package org.msgat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import org.jetbrains.skia.Image
import org.msgat.ui.call.CallOverlay
import org.msgat.ui.chat.ChatScreen
import org.msgat.ui.chat.ChatViewModel
import org.msgat.ui.sessionlist.SessionListScreen
import org.msgat.ui.sessionlist.SessionListViewModel
import org.msgat.ui.theme.MsgAtTheme

fun main() = application {
    // Initialize global application state (DB, Netty, Cryptography, Settings)
    remember {
        AppState.init()
    }

    val sessionListViewModel = remember {
        SessionListViewModel(AppState.msgDao, AppState.nettyClient, AppState.securePrefs)
    }

    var activeChatUuid by remember { mutableStateOf<String?>(null) }
    var chatViewModel by remember { mutableStateOf<ChatViewModel?>(null) }

    val appIcon = remember {
        try {
            val stream = object {}.javaClass.getResourceAsStream("/icons/icon.png")
            if (stream != null) {
                val bytes = stream.readBytes()
                Image.makeFromEncoded(bytes).toComposeImageBitmap()
            } else null
        } catch (_: Exception) {
            null
        }
    }

    LaunchedEffect(activeChatUuid) {
        val targetUuid = activeChatUuid
        if (targetUuid != null) {
            chatViewModel = ChatViewModel(targetUuid, AppState.msgDao, AppState.nettyClient, AppState.securePrefs)
        } else {
            chatViewModel = null
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "MsgAT",
        state = WindowState(width = 1000.dp, height = 700.dp),
        icon = appIcon?.let { BitmapPainter(it) }
    ) {
        MsgAtTheme {
            Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                // ── 左侧：会话列表（固定宽度侧栏，风格同 macOS 分栏）──
                Box(
                    modifier = Modifier
                        .width(330.dp)
                        .fillMaxHeight()
                ) {
                    val syncState by sessionListViewModel.connectionState.collectAsState()
                    val conversations by sessionListViewModel.conversations.collectAsState()
                    val friendRequests by sessionListViewModel.friendRequests.collectAsState()
                    val currentAvatarPath by sessionListViewModel.currentAvatarPath.collectAsState()
                    val currentNickname by sessionListViewModel.currentNickname.collectAsState()

                    SessionListScreen(
                        syncState = syncState,
                        conversations = conversations,
                        friendRequests = friendRequests,
                        selectedUuid = activeChatUuid,
                        onSelectConversation = { uuid ->
                            activeChatUuid = uuid
                        },
                        onAddConversation = sessionListViewModel::addConversation,
                        onSaveSettings = sessionListViewModel::saveSettings,
                        onDeleteConversation = { uuid ->
                            sessionListViewModel.deleteConversation(uuid)
                            if (activeChatUuid == uuid) activeChatUuid = null
                        },
                        onClearMessages = sessionListViewModel::clearMessages,
                        onResetIdentity = {
                            sessionListViewModel.resetIdentity()
                            activeChatUuid = null
                        },
                        onSaveIdentity = sessionListViewModel::saveIdentity,
                        initialIp = sessionListViewModel.currentIp,
                        initialPort = sessionListViewModel.currentPort,
                        initialUuid = sessionListViewModel.currentUuid,
                        initialAvatarPath = currentAvatarPath,
                        initialNickname = currentNickname,
                        isIdentityMissing = sessionListViewModel.isIdentityMissing,
                        initialTurnServer = AppState.securePrefs.turnServer,
                        initialTurnPort = if (AppState.securePrefs.turnPort > 0) AppState.securePrefs.turnPort.toString() else "",
                        initialTurnUsername = AppState.securePrefs.turnUsername,
                        initialTurnPassword = AppState.securePrefs.turnPassword ?: "",
                        initialTurnUseTls = AppState.securePrefs.turnUseTls,
                        onAcceptFriendRequest = sessionListViewModel::acceptFriendRequest,
                        onDeclineFriendRequest = sessionListViewModel::declineFriendRequest
                    )
                }

                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // ── 右侧：聊天栏（未选中会话时显示空状态提示）──
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    val currentChatVM = chatViewModel
                    if (currentChatVM != null) {
                        val syncState by currentChatVM.syncState.collectAsState()
                        val messages by currentChatVM.messages.collectAsState()
                        val alias by currentChatVM.alias.collectAsState()

                        ChatScreen(
                            alias = alias,
                            syncState = syncState,
                            messages = messages,
                            onBack = null,
                            onSendMessage = { text, replyToId, replyToSender, replyToContent, onFail ->
                                currentChatVM.sendMessage(text, replyToId, replyToSender, replyToContent, onFail)
                            },
                            onSelectMedia = { file, replyToId, replyToSender, replyToContent, onFail ->
                                currentChatVM.sendMedia(file, replyToId, replyToSender, replyToContent, onFail)
                            },
                            onStartCall = {
                                org.msgat.core.call.CallSignalingHandler.getInstance()
                                org.msgat.core.call.CallManager.getInstance().startOutgoingCall(currentChatVM.targetUuid)
                            },
                            onMarkAsRead = {
                                currentChatVM.markAsRead()
                            },
                            onSendReadAck = { msgId ->
                                currentChatVM.sendReadAck(msgId)
                            },
                            onRevokeMessage = currentChatVM::revokeMessage,
                            onEditMessage = currentChatVM::editMessage
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "MsgAT 安全加密通信",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "从左侧列表选择一个会话开始聊天",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Global Voice Call Overlay
            CallOverlay()
        }
    }
}
