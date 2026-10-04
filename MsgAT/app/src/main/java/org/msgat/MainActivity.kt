package org.msgat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.msgat.core.network.NettyClient
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.AppDatabase
import org.msgat.ui.chat.ChatScreen
import org.msgat.ui.chat.ChatViewModel
import org.msgat.ui.sessionlist.SessionListScreen
import org.msgat.ui.sessionlist.SessionListViewModel
import org.msgat.ui.theme.MsgAtTheme
import org.msgat.ui.call.CallOverlay
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.paging.compose.collectAsLazyPagingItems

class MainActivity : ComponentActivity() {

    private lateinit var securePrefs: SecurePrefs
    private lateinit var db: AppDatabase
    private lateinit var nettyClient: NettyClient

    // Initializes the main activity, sets up navigation routing, and applies the Compose theme.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        val app = MsgATApp.instance
        securePrefs = app.securePrefs
        db = app.db
        nettyClient = app.nettyClient

        val permissionsToRequest = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), 1001)
        }

        val serviceIntent = android.content.Intent(this, org.msgat.core.network.MsgATService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val viewModelFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(SessionListViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return SessionListViewModel(db.msgDao(), nettyClient, securePrefs) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }

        setContent {
            MsgAtTheme {
                val navController = rememberNavController()

                Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    NavHost(navController = navController, startDestination = "session_list") {

                        composable("session_list") {

                            val viewModel: SessionListViewModel = viewModel(factory = viewModelFactory)

                            val syncState = viewModel.connectionState.collectAsState().value
                            val conversations = viewModel.conversations.collectAsState().value
                            val friendRequests = viewModel.friendRequests.collectAsState().value
                            SessionListScreen(
                                syncState = syncState,
                                conversations = conversations,
                                friendRequests = friendRequests,
                                initialIp = viewModel.currentIp,
                                initialPort = viewModel.currentPort,
                                initialUuid = viewModel.currentUuid,
                                initialAvatarPath = viewModel.currentAvatarPath.collectAsState().value,
                                initialNickname = viewModel.currentNickname.collectAsState().value,
                                initialTurnServer = viewModel.currentTurnServer,
                                initialTurnPort = viewModel.currentTurnPort,
                                initialTurnUsername = viewModel.currentTurnUsername,
                                initialTurnPassword = viewModel.currentTurnPassword,
                                initialTurnUseTls = viewModel.currentTurnUseTls,
                                isIdentityMissing = viewModel.isIdentityMissing,
                                onNavigateToChat = { uuid -> navController.navigate("chat/$uuid") },
                                onAddConversation = viewModel::addConversation,
                                onSaveSettings = viewModel::saveSettings,
                                onDeleteConversation = viewModel::deleteConversation,
                                onClearMessages = viewModel::clearMessages,
                                onResetIdentity = {
                                    viewModel.resetIdentity(onFail = {
                                        android.widget.Toast.makeText(this@MainActivity, "Must be online to reset", android.widget.Toast.LENGTH_SHORT).show()
                                    })
                                },
                                onSaveIdentity = viewModel::saveIdentity,
                                onAcceptFriendRequest = viewModel::acceptFriendRequest,
                                onDeclineFriendRequest = viewModel::declineFriendRequest
                            )
                        }

                        composable("chat/{uuid}") { backStackEntry ->
                            val targetUuid = backStackEntry.arguments?.getString("uuid") ?: ""

                            val chatViewModelFactory = object : ViewModelProvider.Factory {
                                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                    @Suppress("UNCHECKED_CAST")
                                    return ChatViewModel(targetUuid, db.msgDao(), nettyClient, securePrefs) as T
                                }
                            }
                            val chatViewModel: ChatViewModel = viewModel(factory = chatViewModelFactory)

                            val syncState = chatViewModel.syncState.collectAsState().value
                            val messages = chatViewModel.messagesPagingData.collectAsLazyPagingItems()



                            val alias = chatViewModel.alias.collectAsState().value
                            val context = androidx.compose.ui.platform.LocalContext.current

                            ChatScreen(
                                alias = alias,
                                myUuid = securePrefs.myUuid,
                                peerUuid = targetUuid,
                                syncState = syncState,
                                messages = messages,
                                onBack = { navController.popBackStack() },
                                onSendMessage = { text, replyToId, replyToSender, replyToContent, onFail ->
                                    chatViewModel.sendMessage(text, replyToId, replyToSender, replyToContent, onFail)
                                },
                                onSelectMedia = { uri, replyToId, replyToSender, replyToContent, onFail, onComplete ->
                                    chatViewModel.sendMedia(context, uri, replyToId, replyToSender, replyToContent, onFail, onComplete)
                                },
                                onStartCall = {
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        org.msgat.core.call.CallSignalingHandler.getInstance()
                                        org.msgat.core.call.CallManager.getInstance().startOutgoingCall(targetUuid)
                                    } else {
                                        android.widget.Toast.makeText(this@MainActivity, "Microphone permission is required for voice calls.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onMarkAsRead = {
                                    chatViewModel.markAsRead()
                                },
                                onRevokeMessage = chatViewModel::revokeMessage,
                                onEditMessage = chatViewModel::editMessage
                            )
                        }
                    }

                    CallOverlay()
                }
            }
        }
    }
}
