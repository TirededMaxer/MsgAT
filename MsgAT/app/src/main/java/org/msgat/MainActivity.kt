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
import org.msgat.app.ConnectionManager
import org.msgat.core.network.NettyClient
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.AppDatabase // 你的 Room Database 类
import org.msgat.ui.chat.ChatScreen
import org.msgat.ui.chat.ChatViewModel
import org.msgat.ui.sessionlist.SessionListScreen
import org.msgat.ui.sessionlist.SessionListViewModel
import org.msgat.ui.theme.MsgAtTheme

class MainActivity : ComponentActivity() {

    private lateinit var securePrefs: SecurePrefs

    private lateinit var db: AppDatabase

    private lateinit var nettyClient: NettyClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        securePrefs = SecurePrefs(applicationContext)
        // 注意：这里的 AppDatabase 需要你利用 Room.databaseBuilder 构建，并配置 SQLCipher
        // db = AppDatabase.getDatabase(applicationContext, getDbPassword())
        db = AppDatabase.getInstance(applicationContext)

        nettyClient = NettyClient(securePrefs, db.msgDao())
        ProcessLifecycleOwner.get().lifecycle.addObserver(ConnectionManager(nettyClient))

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

                NavHost(navController = navController, startDestination = "session_list") {

                    composable("session_list") {
                        // 使用我们自定义的工厂获取 ViewModel
                        val viewModel: SessionListViewModel = viewModel(factory = viewModelFactory)

                        // 收集状态流
                        val syncState = viewModel.connectionState.collectAsState().value
                        val conversations = viewModel.conversations.collectAsState().value

                        SessionListScreen(
                            syncState = syncState,
                            conversations = conversations,
                            initialIp = viewModel.currentIp,
                            initialPort = viewModel.currentPort,
                            initialUuid = viewModel.currentUuid,
                            onNavigateToChat = { uuid -> navController.navigate("chat/$uuid") },
                            onAddConversation = viewModel::addConversation,
                            onSaveSettings = viewModel::saveSettings,
                            onDeleteConversation = viewModel::deleteConversation,
                            onClearMessages = viewModel::clearMessages,
                            onResetIdentity = viewModel::resetIdentity,
                            onUpdateAlias = viewModel::updateAlias
                        )
                    }

                    composable("chat/{uuid}") { backStackEntry ->
                        val targetUuid = backStackEntry.arguments?.getString("uuid") ?: ""

                        // 为每个聊天界面动态创建一个 ChatViewModel
                        val chatViewModelFactory = object : ViewModelProvider.Factory {
                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                @Suppress("UNCHECKED_CAST")
                                return ChatViewModel(targetUuid, db.msgDao(), nettyClient, securePrefs) as T
                            }
                        }
                        val chatViewModel: ChatViewModel = viewModel(factory = chatViewModelFactory)

                        val syncState = chatViewModel.syncState.collectAsState().value
                        val messages = chatViewModel.messages.collectAsState().value
                        val alias = chatViewModel.alias.collectAsState().value
                        val context = androidx.compose.ui.platform.LocalContext.current

                        ChatScreen(
                            alias = alias,
                            syncState = syncState,
                            messages = messages,
                            onBack = { navController.popBackStack() },
                            onSendMessage = { text, onFail ->
                                chatViewModel.sendMessage(text, onFail)
                            },
                            onSelectMedia = { uri, onFail ->
                                chatViewModel.sendMedia(context, uri, onFail)
                            }
                        )
                    }
                }
            }
        }
    }
}