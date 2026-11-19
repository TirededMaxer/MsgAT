package org.msgat

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.compose.runtime.*
import androidx.lifecycle.withStateAtLeast
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.msgat.core.network.NettyClient
import org.msgat.core.media.StickerSource
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.AppDatabase
import org.msgat.data.db.MessageSearchCursor
import org.msgat.data.db.MessageSearchHit
import org.msgat.data.db.MessageNotificationStore
import org.msgat.ui.chat.ChatScreen
import org.msgat.ui.chat.ChatViewModel
import org.msgat.ui.chat.ChatMessageNavigation
import org.msgat.ui.components.MessageSearchInput
import org.msgat.ui.components.IncomingShare
import org.msgat.ui.components.IncomingShareDialog
import org.msgat.ui.components.MAX_SHARED_ATTACHMENTS
import org.msgat.ui.components.MAX_SHARED_TEXT_BYTES
import org.msgat.ui.sessionlist.SessionListScreen
import org.msgat.ui.sessionlist.SessionListViewModel
import org.msgat.ui.theme.MsgAtTheme
import org.msgat.ui.theme.AppLanguage
import org.msgat.ui.call.CallOverlay
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.paging.compose.collectAsLazyPagingItems

private const val MAX_SHARED_STICKERS = 200

private data class SearchReturn(
    val chatEntryId: String,
    val origin: String?,
    val input: MessageSearchInput,
    val anchor: MessageSearchCursor,
    val revealRevision: Long,
    val showControls: Boolean = true
)

private class IncomingShareState : ViewModel() {
    var pending by mutableStateOf<IncomingShare?>(null)
    var routed by mutableStateOf<Pair<String, IncomingShare>?>(null)
    var stickers by mutableStateOf<List<Uri>?>(null)
}

class MainActivity : ComponentActivity() {

    private lateinit var securePrefs: SecurePrefs
    private lateinit var db: AppDatabase
    private lateinit var nettyClient: NettyClient
    private val notificationTarget = MutableStateFlow<String?>(null)
    private val backgroundReceptionRequested = MutableStateFlow(false)
    private val answerCallRequested = MutableStateFlow<String?>(null)
    private val appLock = AppLockController(this)
    private val incomingShares by lazy {
        ViewModelProvider(this, viewModelFactory { initializer { IncomingShareState() } })[IncomingShareState::class.java]
    }
    private var initialPermissionsRequested = false

    private fun acceptNotificationIntent(intent: Intent?) {
        if (intent?.action == NotificationHelper.ACTION_ANSWER_CALL) {
            answerCallRequested.value = intent.getStringExtra("callToken")
            intent.action = null
            intent.data = null
            intent.removeExtra("callToken")
            return
        }
        if (intent?.action == NotificationHelper.ACTION_OPEN_BACKGROUND_RECEPTION) {
            intent.action = null
            backgroundReceptionRequested.value = true
            return
        }
        val data = intent?.data ?: return
        if (data.scheme != "msgat" || data.authority != "notification") return
        val token = data.pathSegments.singleOrNull() ?: return
        if (!Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}").matches(token)) return
        notificationTarget.value = token
        intent.data = null
    }

    private fun acceptIncomingShare(intent: Intent?) {
        if (intent == null) return
        val multiple = when (intent.action) {
            Intent.ACTION_SEND -> false
            Intent.ACTION_SEND_MULTIPLE -> true
            else -> return
        }
        val mime = intent.type.orEmpty().substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
        val image = mime.startsWith("image/")
        val stickerCandidate = image || (!multiple && mime in setOf("application/zip", "application/x-zip-compressed",
            "application/x-qq-eif", "application/octet-stream"))

        fun report(message: Int) {
            val strings = AppLanguage.restore(this).resources(this)
            android.widget.Toast.makeText(this, strings.getString(message), android.widget.Toast.LENGTH_SHORT).show()
        }

        try {
            val clip = intent.clipData
            val limit = if (image) MAX_SHARED_STICKERS else MAX_SHARED_ATTACHMENTS
            val limitMessage = if (image) R.string.ui_shared_stickers_limit else R.string.ui_attachment_limit
            if ((clip?.itemCount ?: 0) > limit) {
                report(limitMessage)
                return
            }
            val uris = when {
                clip != null && (0 until clip.itemCount).any { clip.getItemAt(it).uri != null } ->
                    (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                multiple -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            }
            if (uris.size > limit) {
                report(limitMessage)
                return
            }
            val readGrant = Intent.FLAG_GRANT_READ_URI_PERMISSION
            if ((!multiple && uris.size > 1) || (uris.isNotEmpty() && (intent.flags and readGrant) == 0) || uris.any { uri ->
                    uri.scheme != "content" || uri.authority.isNullOrBlank() ||
                        checkUriPermission(uri, Process.myPid(), Process.myUid(), readGrant) != PackageManager.PERMISSION_GRANTED
                }) {
                report(R.string.ui_share_unavailable)
                return
            }
            val sharedText = if (multiple) null else intent.getCharSequenceExtra(Intent.EXTRA_TEXT) ?: clip?.takeIf {
                it.itemCount == 1
            }?.getItemAt(0)?.text
            if (sharedText != null && (sharedText.length > MAX_SHARED_TEXT_BYTES ||
                    sharedText.toString().toByteArray(Charsets.UTF_8).size > MAX_SHARED_TEXT_BYTES)) {
                report(R.string.ui_share_text_too_long)
                return
            }
            val text = sharedText?.toString()?.takeIf { it.isNotBlank() }
            if (uris.isEmpty() && text == null) {
                report(R.string.ui_share_unavailable)
                return
            }
            if (incomingShares.pending != null || incomingShares.routed != null || incomingShares.stickers != null) {
                report(R.string.ui_share_in_progress)
                return
            }
            incomingShares.pending = IncomingShare(uris.distinct(), text, !image && !mime.startsWith("video/") &&
                !mime.startsWith("audio/"), uris.isNotEmpty() && stickerCandidate)
        } catch (_: RuntimeException) {
            report(R.string.ui_share_unavailable)
        } finally {
            intent.action = null
            intent.clipData = null
            runCatching {
                intent.removeExtra(Intent.EXTRA_STREAM)
                intent.removeExtra(Intent.EXTRA_TEXT)
                intent.removeExtra(Intent.EXTRA_HTML_TEXT)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptNotificationIntent(intent)
        acceptIncomingShare(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        appLock.onStart()
    }

    override fun onStop() {
        appLock.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        appLock.close()
        super.onDestroy()
    }

    // Initializes the main activity, sets up navigation routing, and applies the Compose theme.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLock.start()
        acceptNotificationIntent(intent)
        acceptIncomingShare(intent)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            MsgAtTheme {
                Box(modifier=androidx.compose.ui.Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
        }
        lifecycleScope.launch {
            val app=MsgATApp.instance
            val state=app.awaitStartup()
            lifecycle.withStateAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) {
                if (state==MsgATApp.StartupState.FAILED) {
                    showStartupFailure(app)
                } else lifecycleScope.launch {
                    try {
                        appLock.load(app.nettyClient.secureMessaging)
                        lifecycle.withStateAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) {
                            appLock.onStart()
                            showReadyInterface(app)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        lifecycle.withStateAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) {
                            val strings = AppLanguage.restore(this@MainActivity).resources(this@MainActivity)
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle(strings.getString(R.string.ui_app_start_error))
                                .setMessage(strings.getString(R.string.ui_app_lock_read_error))
                                .setCancelable(false)
                                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                                .show()
                        }
                    }
                }
            }
        }
    }

    private fun showStartupFailure(app: MsgATApp) {
        val strings = AppLanguage.restore(this).resources(this)
        val lifecycle = org.msgat.core.crypto.securestorage.IdentityLifecycle
        val pendingReset = lifecycle.hasPendingReset(applicationContext)
        val message = strings.getString(if (pendingReset) R.string.ui_reset_incomplete
            else app.startupFailure ?: R.string.ui_startup_vault_unavailable)
        val alert = android.app.AlertDialog.Builder(this)
            .setTitle(strings.getString(R.string.ui_app_start_error))
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(strings.getString(R.string.ui_close)) { _, _ -> finish() }
        if (app.canResetUnreadableIdentity && !pendingReset) {
            alert.setNeutralButton(strings.getString(R.string.ui_reset_identity)) { _, _ ->
                android.app.AlertDialog.Builder(this)
                    .setTitle(strings.getString(R.string.ui_reset_identity))
                    .setMessage(strings.getString(R.string.ui_identity_reset_unreadable_confirm))
                    .setNegativeButton(strings.getString(R.string.ui_cancel)) { _, _ -> showStartupFailure(app) }
                    .setPositiveButton(strings.getString(R.string.ui_reset_identity)) { _, _ ->
                        lifecycleScope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                val prepared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { lifecycle.prepareLocalOnlyReset(applicationContext) }.isSuccess
                                }
                                if (prepared) {
                                    finishAndRemoveTask()
                                    android.os.Process.killProcess(android.os.Process.myPid())
                                } else if (!isFinishing && !isDestroyed) {
                                    android.app.AlertDialog.Builder(this@MainActivity)
                                        .setMessage(strings.getString(R.string.ui_identity_reset_prepare_failed))
                                        .setPositiveButton(android.R.string.ok) { _, _ -> showStartupFailure(app) }
                                        .show()
                                }
                            }
                        }
                    }
                    .setOnCancelListener { showStartupFailure(app) }
                    .show()
            }
        }
        alert.show()
    }

    private var interfaceReady=false

    override fun onResume() {
        super.onResume()
        if (interfaceReady) startConnection()
    }

    private fun startConnection() {
        val app = MsgATApp.instance
        if (!app.isReady) return
        app.requestForegroundReception()
        if (nettyClient.isConfigured() && app.securePrefs.persistentReceptionEnabled)
            org.msgat.core.network.BackgroundReception.start(this)
        else org.msgat.core.network.BackgroundReception.stop(this)
    }

    private fun requestInitialPermissions() {
        if (initialPermissionsRequested || appLock.locked) return
        initialPermissionsRequested = true
        val permissionsToRequest = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissionsToRequest.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1001)
    }

    private fun showReadyInterface(app: MsgATApp) {
        interfaceReady=true
        securePrefs = app.securePrefs
        db = app.db
        nettyClient = app.nettyClient
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.merge(nettyClient.secureMessaging.errors, nettyClient.drafts.errors).collect { message ->
                    if (!appLock.locked)
                        android.widget.Toast.makeText(this@MainActivity,message,android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        startConnection()

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
                if (appLock.locked) {
                    org.msgat.ui.components.AppLockScreen(appLock.busy, appLock.error, appLock::unlock)
                    return@MsgAtTheme
                }
                val navController = rememberNavController()
                var showBackgroundReception by remember { mutableStateOf(false) }
                var notificationNavigation by remember {
                    mutableStateOf<Pair<String, ChatMessageNavigation>?>(null)
                }
                suspend fun resolveNotification(token: String): MessageNotificationStore.Target? =
                    try { MessageNotificationStore.resolve(db, token) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                fun showUnavailableNotification() {
                    val resources = AppLanguage.restore(this@MainActivity).resources(this@MainActivity)
                    android.widget.Toast.makeText(this@MainActivity,
                        resources.getString(R.string.ui_history_message_unavailable), android.widget.Toast.LENGTH_SHORT).show()
                }
                LaunchedEffect(Unit) {
                    if (!backgroundReceptionRequested.value && answerCallRequested.value == null && incomingShares.pending == null)
                        requestInitialPermissions()
                    backgroundReceptionRequested.collect { requested ->
                        if (requested && !appLock.locked && backgroundReceptionRequested.compareAndSet(true, false)) {
                            showBackgroundReception = true
                        }
                    }
                }
                LaunchedEffect(navController) {
                    notificationTarget.collectLatest { token ->
                        if (token == null) return@collectLatest
                        val target = resolveNotification(token)
                        if (appLock.locked || notificationTarget.value != token) return@collectLatest
                        if (target == null) {
                            if (notificationTarget.compareAndSet(token, null)) {
                                notificationNavigation = null
                                showUnavailableNotification()
                            }
                            return@collectLatest
                        }
                        fun navigate() {
                            navController.navigate("chat/${target.peer}?message=${target.messageId}") {
                                popUpTo("session_list")
                                launchSingleTop = true
                            }
                        }
                        val entry = navController.currentBackStackEntry
                        val currentPeer = entry?.arguments?.getString("uuid")
                        if (entry != null && entry.destination.route?.startsWith("chat/") == true && currentPeer != null) {
                            notificationNavigation = entry.id to ChatMessageNavigation(
                                peer = target.peer, messageId = target.messageId,
                                open = {
                                    val resolved = resolveNotification(token)
                                    if (appLock.locked || navController.currentBackStackEntry?.id != entry.id ||
                                        !notificationTarget.compareAndSet(token, null)) false
                                    else {
                                        notificationNavigation = null
                                        if (resolved != target) {
                                            showUnavailableNotification()
                                            false
                                        } else {
                                            if (target.peer != currentPeer) navigate()
                                            true
                                        }
                                    }
                                },
                                cancel = {
                                    if (notificationTarget.compareAndSet(token, null)) notificationNavigation = null
                                }
                            )
                        } else if (notificationTarget.compareAndSet(token, null)) {
                            navigate()
                        }
                    }
                }
                val searchStore=remember(db) {org.msgat.data.db.MessageSearchStore(this@MainActivity,db)}
                var showSearch by remember {mutableStateOf(false)}
                var searchPeer by remember {mutableStateOf<String?>(null)}
                var searchRequest by remember { mutableLongStateOf(0L) }
                val previousSearches = remember { LinkedHashMap<String?, MessageSearchInput>() }
                var searchInitial by remember { mutableStateOf(MessageSearchInput(all = true)) }
                var panelNavigation by remember {
                    mutableStateOf<Pair<String, ChatMessageNavigation>?>(null)
                }
                fun rememberSearch(peer: String?, input: MessageSearchInput) {
                    previousSearches.remove(peer)
                    previousSearches[peer] = input
                    if (previousSearches.size > 16) previousSearches.remove(previousSearches.keys.first())
                }
                fun openSearch(peer: String?, resume: MessageSearchInput? = null) {
                    panelNavigation = null
                    searchPeer = peer
                    searchInitial = resume ?: previousSearches[peer] ?: MessageSearchInput(all = peer == null)
                    searchRequest++
                    showSearch = true
                }
                var searchReturn by remember { mutableStateOf<SearchReturn?>(null) }
                fun revealSearchHit(origin: String?, input: MessageSearchInput, hit: MessageSearchHit, request: Long) {
                    rememberSearch(origin, input)
                    if (navController.currentBackStackEntry?.arguments?.getString("uuid") != hit.peer) {
                        navController.navigate("chat/${hit.peer}?message=${hit.messageId}") {
                            popUpTo("session_list")
                            launchSingleTop = true
                        }
                    }
                    searchReturn = navController.currentBackStackEntry?.let { entry ->
                        SearchReturn(entry.id, origin, input, MessageSearchCursor(hit.timestamp, hit.messageId), request)
                    }
                }
                var openingShare by remember { mutableStateOf<IncomingShare?>(null) }
                DisposableEffect(navController) {
                    val listener = NavController.OnDestinationChangedListener { controller, _, _ ->
                        notificationNavigation?.takeIf { it.first != controller.currentBackStackEntry?.id }
                            ?.second?.cancel?.invoke()
                        if (panelNavigation?.first != controller.currentBackStackEntry?.id) panelNavigation = null
                        if (incomingShares.routed?.first != controller.currentBackStackEntry?.id) incomingShares.routed = null
                        if (panelNavigation == null) openingShare = null
                        if (searchReturn?.chatEntryId != controller.currentBackStackEntry?.id) {
                            searchReturn = null
                        }
                    }
                    navController.addOnDestinationChangedListener(listener)
                    onDispose { navController.removeOnDestinationChangedListener(listener) }
                }
                val searchScope=rememberCoroutineScope()
                var openingSearchResult by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                var movingSearch by remember { mutableStateOf(false) }
                fun moveSearch(route: SearchReturn, newer: Boolean) {
                    if (movingSearch || showSearch || searchReturn != route || route.input.all) return
                    movingSearch = true
                    val request = searchRequest
                    searchScope.launch {
                        try {
                            val hit = searchStore.search(route.input.query, route.origin, route.anchor,
                                route.input.kind, limit = 1, newer = newer, day = route.input.day).hits.firstOrNull()
                            val available = hit != null && hit.peer == route.origin &&
                                db.msgDao().hasHistoryAnchor(hit.peer, hit.messageId, includeRevoked = false)
                            if (searchReturn != route || showSearch || request != searchRequest) return@launch
                            if (hit == null) {
                                android.widget.Toast.makeText(this@MainActivity, R.string.ui_search_no_adjacent_result,
                                    android.widget.Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            if (!available) {
                                android.widget.Toast.makeText(this@MainActivity, R.string.ui_search_target_unavailable,
                                    android.widget.Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val index = ((route.input.selectedIndex ?: 0) + if (newer) -1 else 1).coerceAtLeast(0)
                            val input = route.input.copy(selectedMessage = hit.messageId, selectedIndex = index,
                                loadedCount = maxOf(route.input.loadedCount, index + 1))
                            searchReturn = route.copy(input = input, anchor = MessageSearchCursor(hit.timestamp, hit.messageId))
                            rememberSearch(route.origin, input)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (searchReturn == route && !showSearch && request == searchRequest)
                                android.widget.Toast.makeText(this@MainActivity, R.string.ui_search_failed, android.widget.Toast.LENGTH_SHORT).show()
                        } finally {
                            movingSearch = false
                        }
                    }
                }
                val historyPositions = remember { LinkedHashMap<String, org.msgat.ui.chat.ChatHistoryPosition>() }
                var showSaved by remember {mutableStateOf(false)}
                var showForwardTasks by remember {mutableStateOf(false)}
                var showCallHistory by remember {mutableStateOf(false)}
                var forwarding by remember {mutableStateOf<org.msgat.ui.components.ForwardMessageRequest?>(null)}

                suspend fun openChatTarget(
                    peer: String,
                    messageId: String? = null,
                    sourceIsOpen: () -> Boolean = { true },
                    onAccepted: () -> Unit = {},
                    onKeep: () -> Unit = {},
                    unavailableText: Int = R.string.ui_history_message_unavailable,
                    canOpen: suspend () -> Boolean = { true },
                    onOpened: (String) -> Unit = {},
                    onUnavailable: () -> Unit = {}
                ): Boolean {
                    val source = navController.currentBackStackEntry
                    fun current() = !appLock.locked && navController.currentBackStackEntry?.id == source?.id
                    suspend fun available(): Boolean = try {
                        canOpen() && db.msgDao().getConversationByUuid(peer) != null &&
                            (messageId == null || db.msgDao().hasHistoryAnchor(peer, messageId, includeRevoked = false))
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    fun openDifferentChat() {
                        val route = if (messageId == null) "chat/$peer" else "chat/$peer?message=$messageId"
                        navController.navigate(route) { popUpTo("session_list"); launchSingleTop = true }
                    }
                    if (!current() || !sourceIsOpen() || !available() || !current() || !sourceIsOpen()) return false
                    onAccepted()
                    val currentPeer = source?.arguments?.getString("uuid")
                    if (source != null && currentPeer != null) {
                        val request = ChatMessageNavigation(peer, messageId, open = {
                            val valid = available()
                            if (!current()) false
                            else {
                                panelNavigation = null
                                if (valid) {
                                    if (peer != currentPeer) openDifferentChat()
                                    navController.currentBackStackEntry?.id?.let(onOpened)
                                }
                                if (!valid) {
                                    onUnavailable()
                                    android.widget.Toast.makeText(this@MainActivity, unavailableText,
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }
                                valid
                            }
                        }, cancel = {
                            if (current()) {
                                panelNavigation = null
                                onKeep()
                            }
                        })
                        panelNavigation = source.id to request
                    } else {
                        openDifferentChat()
                        navController.currentBackStackEntry?.id?.let(onOpened)
                    }
                    return true
                }

                val sessionListViewModel: SessionListViewModel = viewModel(factory = viewModelFactory)
                Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    NavHost(navController = navController, startDestination = "session_list") {

                        composable("session_list") { entry ->

                            val viewModel = sessionListViewModel

                            val syncState = viewModel.connectionState.collectAsState().value
                            val friendRequests = viewModel.friendRequests.collectAsState().value
                            val navigationScope = rememberCoroutineScope()
                            var openChatJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                            SessionListScreen(
                                syncState = syncState,
                                allConversations=viewModel.allConversations.collectAsState().value,
                                folders=viewModel.folders.collectAsState().value,
                                onConversationAction=viewModel::conversationAction,onMovePin=viewModel::movePin,
                                onSetArchived = viewModel::setArchived,
                                onSaveFolder=viewModel::saveFolder,onDeleteFolder=viewModel::deleteFolder,onMoveFolder=viewModel::moveFolder,
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
                                onNavigateToChat = { uuid ->
                                    openChatJob?.cancel()
                                    openChatJob = navigationScope.launch {
                                        val unread = try {
                                            db.msgDao().getFirstUnreadId(uuid)
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            null
                                        }
                                        if (appLock.locked || navController.currentBackStackEntry != entry) return@launch
                                        viewModel.conversationAction(uuid, "clear_unread")
                                        navController.navigate(
                                            if (unread == null) "chat/$uuid" else "chat/$uuid?message=$unread&unread=true"
                                        )
                                    }
                                },
                                onSearch = { openSearch(null) },
                                onOpenQueuedMessage = { peer, id ->
                                    openChatTarget(peer, id, sourceIsOpen = {
                                        navController.currentBackStackEntry?.id == entry.id
                                    }, onAccepted = { viewModel.conversationAction(peer, "clear_unread") })
                                },
                                onSavedMessages={showSaved=true},
                                onForwardTasks={showForwardTasks=true},
                                onCallHistory={showCallHistory=true},
                                appLockEnabled = appLock.enabled,
                                onToggleAppLock = appLock::toggle,
                                onLockNow = appLock::lockNow,
                                onAddConversation = viewModel::addConversation,
                                onSaveSettings = { settings -> viewModel.saveSettings(settings); startConnection() },
                                onDeleteConversation = { peer ->
                                    viewModel.deleteConversation(peer) {
                                        val strings = AppLanguage.restore(this@MainActivity).resources(this@MainActivity)
                                        android.widget.Toast.makeText(this@MainActivity,
                                            strings.getString(R.string.ui_contact_action_failed), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onClearMessages = { peer ->
                                    viewModel.clearMessages(peer) {
                                        val strings = AppLanguage.restore(this@MainActivity).resources(this@MainActivity)
                                        android.widget.Toast.makeText(this@MainActivity,
                                            strings.getString(R.string.ui_clear_messages_failed), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onResetIdentity = {
                                    val strings = AppLanguage.restore(this@MainActivity).resources(this@MainActivity)
                                    val closing = android.app.AlertDialog.Builder(this@MainActivity)
                                        .setMessage(strings.getString(R.string.ui_resetting_identity))
                                        .setCancelable(false)
                                        .show()
                                    viewModel.resetIdentity(onFail = {
                                        closing.dismiss()
                                        android.widget.Toast.makeText(this@MainActivity,
                                            strings.getString(R.string.ui_identity_reset_prepare_failed), android.widget.Toast.LENGTH_SHORT).show()
                                    })
                                },
                                onSaveIdentity = viewModel::saveIdentity,
                                onAcceptFriendRequest = viewModel::acceptFriendRequest,
                                onDeclineFriendRequest = viewModel::declineFriendRequest
                            )
                        }

                        composable(
                            "chat/{uuid}?message={message}&unread={unread}",
                            arguments = listOf(
                                androidx.navigation.navArgument("message") {
                                    type = androidx.navigation.NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                                androidx.navigation.navArgument("unread") {
                                    type = androidx.navigation.NavType.BoolType
                                    defaultValue = false
                                }
                            )
                        ) { backStackEntry ->
                            val targetUuid = backStackEntry.arguments?.getString("uuid") ?: ""
                            val searchRoute = searchReturn?.takeIf { it.chatEntryId == backStackEntry.id }
                            val activeSearch = searchRoute?.takeIf { it.showControls }
                            val initialMessageId = searchRoute?.anchor?.messageId ?: backStackEntry.arguments?.getString("message")
                            val historyPosition = remember(backStackEntry) { historyPositions[targetUuid].takeIf { initialMessageId == null } }

                            val chatViewModelFactory = object : ViewModelProvider.Factory {
                                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                    @Suppress("UNCHECKED_CAST")
                                    return ChatViewModel(targetUuid, db.msgDao(), nettyClient, securePrefs,initialMessageId ?: historyPosition?.messageId) as T
                                }
                            }
                            val chatViewModel: ChatViewModel = viewModel(factory = chatViewModelFactory)

                            val syncState = chatViewModel.syncState.collectAsState().value
                            val messages = chatViewModel.messagesPagingData.collectAsLazyPagingItems()
                            val attachmentProgress by chatViewModel.attachmentProgress.collectAsState()
                            val initialDraft by chatViewModel.initialDraft.collectAsState()



                            val conversation by chatViewModel.conversation.collectAsStateWithLifecycle()
                            val unreadCount by chatViewModel.unreadCount.collectAsStateWithLifecycle(initialValue = 0)
                            val recentReadIds by chatViewModel.recentReadIds.collectAsStateWithLifecycle()
                            val peerActivity by chatViewModel.peerActivity.collectAsStateWithLifecycle()
                            val verificationRevision by nettyClient.contacts.changes.collectAsStateWithLifecycle()
                            val chatLocked = appLock.locked
                            var peerVerified by remember(targetUuid, verificationRevision, chatLocked) {
                                mutableStateOf(false)
                            }
                            LaunchedEffect(targetUuid, verificationRevision, chatLocked) {
                                if (!chatLocked) {
                                    peerVerified = try {
                                        nettyClient.contacts.isVerified(targetUuid)
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        false
                                    }
                                }
                            }
                            val sendOptions by chatViewModel.sendOptions.collectAsStateWithLifecycle()
                            val preparations by chatViewModel.pendingPreparations.collectAsStateWithLifecycle()
                            val callManager = remember { org.msgat.core.call.CallManager.getInstance() }
                            var pendingCallVideo by remember(targetUuid) { mutableStateOf<Boolean?>(null) }
                            val requestCallPermissions = rememberLauncherForActivityResult(
                                ActivityResultContracts.RequestMultiplePermissions()
                            ) {
                                val video = pendingCallVideo
                                pendingCallVideo = null
                                if (video != null && !appLock.locked &&
                                    navController.currentBackStackEntry?.id == backStackEntry.id) {
                                    if (callManager.missingCallPermissions(video).isEmpty()) {
                                        org.msgat.core.call.CallSignalingHandler.getInstance()
                                        callManager.startOutgoingCall(targetUuid, video)
                                    } else {
                                        android.widget.Toast.makeText(this@MainActivity,
                                            if (video) R.string.ui_call_outgoing_media_permission else R.string.ui_call_outgoing_microphone_permission,
                                            android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            fun startChatCall(video: Boolean) {
                                if (pendingCallVideo != null || appLock.locked) return
                                val missing = callManager.missingCallPermissions(video)
                                if (missing.isEmpty()) {
                                    org.msgat.core.call.CallSignalingHandler.getInstance()
                                    callManager.startOutgoingCall(targetUuid, video)
                                } else {
                                    pendingCallVideo = video
                                    requestCallPermissions.launch(missing.toTypedArray())
                                }
                            }
                            ChatScreen(
                                alias = conversation?.alias ?: targetUuid.take(12),
                                avatarPath = conversation?.avatarPath.orEmpty(),
                                peerActivity = peerActivity,
                                peerVerified = peerVerified,
                                sendOptions=sendOptions,
                                preparations=preparations,onCancelPreparation=chatViewModel::cancelPreparation,
                                onSetSendOptions=chatViewModel::setSendOptions,
                                onChatActivity = chatViewModel::chatActivity,
                                myUuid = securePrefs.myUuid,
                                peerUuid = targetUuid,
                                muteUntil = remember(targetUuid) { db.msgDao().observeConversationMuteUntil(targetUuid) }.collectAsState(0L).value,
                                onChangeConversationMute = { action, until -> sessionListViewModel.conversationAction(targetUuid, action, until) },
                                initialMessageId = initialMessageId,
                                searchRevealRevision = searchRoute?.revealRevision,
                                incomingShare = incomingShares.routed?.takeIf { it.first == backStackEntry.id }?.second,
                                onIncomingShareHandled = { share ->
                                    if (incomingShares.routed?.second === share) incomingShares.routed = null
                                },
                                workspacePanelCoveringChat = showSearch || showSaved || showForwardTasks || showCallHistory ||
                                    forwarding != null || (incomingShares.pending != null && openingShare == null) || incomingShares.stickers != null,
                                messageNavigation = (panelNavigation ?: notificationNavigation)?.takeIf { (entryId, _) ->
                                    entryId == backStackEntry.id
                                }?.second,
                                initialUnreadMessageId = backStackEntry.arguments?.getString("message")
                                    .takeIf { backStackEntry.arguments?.getBoolean("unread") == true },
                                historyPosition = historyPosition,
                                hasHistoryMessage = chatViewModel::hasHistoryMessage,
                                onSaveHistoryPosition = { position ->
                                    historyPositions.remove(targetUuid)
                                    if (position != null) {
                                        if (historyPositions.size >= 64) historyPositions.remove(historyPositions.keys.first())
                                        historyPositions[targetUuid] = position
                                    }
                                },
                                unreadCount = unreadCount,
                                recentReadIds = recentReadIds,
                                onSearch = { openSearch(targetUuid) },
                                searchQuery = activeSearch?.input?.query,
                                searchMoving = movingSearch,
                                onReturnToSearch = activeSearch?.let { route -> { openSearch(route.origin, route.input) } },
                                onMoveSearch = activeSearch?.takeUnless { it.input.all }?.let { route ->
                                    { newer -> moveSearch(route, newer) }
                                },
                                onCloseSearch = activeSearch?.let { route ->
                                    { if (searchReturn == route) searchReturn = route.copy(showControls = false) }
                                },
                                onRevealMessage=chatViewModel::revealMessage,
                                onNewestMessage=chatViewModel::newestMessageId,
                                onLatestEditable=chatViewModel::latestEditableMessage,
                                onAdjacentReply=chatViewModel::adjacentReplyMessage,
                                onMessageAtDate=chatViewModel::messageAtDate,
                                onFirstUnread=chatViewModel::firstUnreadMessage,
                                onLoadExpiration={nettyClient.secureMessaging.messageRetention(targetUuid)},
                                onSetExpiration={settings->nettyClient.secureMessaging.setMessageRetention(targetUuid,settings)},
                                removedMessages=nettyClient.secureMessaging.removedMessages,
                                observeMessageReceipts=chatViewModel::observeMessageReceipts,
                                observeReplySource=chatViewModel::observeReplySource,
                                pinnedPreview = chatViewModel.pinnedPreview.collectAsState().value,
                                onCyclePinnedPreview = chatViewModel::cyclePinnedPreview,
                                onMessagePinState=chatViewModel::messagePinState,
                                onSetMessagePinned=chatViewModel::setMessagePinned,
                                onForwardMessages={ids,complete->forwarding=org.msgat.ui.components.ForwardMessageRequest(targetUuid,ids.toList(),complete)},
                                onSaveMessages=chatViewModel::saveMessages,
                                onLoadSelectedText = { ids ->
                                    db.msgDao().getSelectedMessageText(targetUuid, ids)
                                },
                                canQuoteMessage = chatViewModel::canQuoteMessage,
                                syncState = syncState,
                                messages = messages,
                                attachmentProgress = attachmentProgress,
                                initialDraft = initialDraft,
                                webPreview=chatViewModel.webPreview.collectAsState().value,
                                previewEnabled=chatViewModel.previewEnabled.collectAsState().value,
                                previewChoices=chatViewModel.previewChoices.collectAsState().value,
                                previewTarget=chatViewModel.previewTarget.collectAsState().value,
                                previewAboveText=chatViewModel.previewAboveText.collectAsState().value,
                                previewCompactImage=chatViewModel.previewCompactImage.collectAsState().value,
                                previewLoading=chatViewModel.previewLoading.collectAsState().value,
                                onTogglePreviewImageSize=chatViewModel::togglePreviewImageSize,
                                onTogglePreviewPlacement=chatViewModel::togglePreviewPlacement,
                                onSelectPreview=chatViewModel::selectWebPreview,
                                onPreviewEnabled=chatViewModel::setPreviewEnabled,
                                onRemovePreview=chatViewModel::removeWebPreview,
                                onRetryPreview=chatViewModel::retryWebPreview,
                                onDraftChanged = chatViewModel::updateDraft,
                                onBack = { navController.popBackStack() },
                                onSendMessage = chatViewModel::sendMessage,
                                textSendPending = chatViewModel.textSendPending.collectAsStateWithLifecycle().value,
                                onSelectMedia = { uris, text, replyToId, replyQuote, choice, onSuccess, onFail, onComplete, onResult, started ->
                                    chatViewModel.sendMedia(uris, text, replyToId, replyQuote, onSuccess, onFail, onComplete, choice, onResult, started)
                                },
                                onSendSticker = chatViewModel::sendSticker,
                                localContactName = securePrefs.myNickname ?: "My identity",
                                onLoadCardContacts = { db.msgDao().getForwardTargets() },
                                onSendContactCard = chatViewModel::sendContactCard,
                                onReadCardAccount = { id -> db.msgDao().getContactCardAccount(targetUuid, id) },
                                onSendLocation = chatViewModel::sendLocation,
                                onReadLocation = { id -> db.msgDao().getLocationPoint(targetUuid, id) },
                                onStartLiveLocation = chatViewModel::startLiveLocation,
                                onReadLiveLocation = chatViewModel::readLiveLocation,
                                onStopLiveLocation = chatViewModel::stopLiveLocation,
                                liveLocationChanges = chatViewModel.liveLocationChanges,
                                activeLiveLocations = chatViewModel.activeLiveLocations,
                                onOpenContactChat = { account, onKeep ->
                                    if (account != targetUuid) searchScope.launch {
                                        openChatTarget(account, sourceIsOpen = {
                                            navController.currentBackStackEntry?.id == backStackEntry.id
                                        }, onKeep = onKeep)
                                    }
                                },
                                onStartCall = { startChatCall(video = false) },
                                onStartVideoCall = { startChatCall(video = true) },
                                onMarkAsRead = chatViewModel::markAsRead,
                                onRevokeMessage = chatViewModel::revokeMessage,
                                onEditMessage = chatViewModel::editMessage
                            )
                        }
                    }

                    if (showCallHistory) {
                        val manager = remember { org.msgat.core.call.CallManager.getInstance() }
                        var pendingCall by remember { mutableStateOf<org.msgat.core.call.CallHistoryEntry?>(null) }
                        fun dial(row: org.msgat.core.call.CallHistoryEntry) {
                            if (!showCallHistory || appLock.locked) return
                            showCallHistory = false
                            org.msgat.core.call.CallSignalingHandler.getInstance()
                            manager.startOutgoingCall(row.peer, video = row.video)
                        }
                        val callPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                            val row = pendingCall
                            pendingCall = null
                            if (row != null && grants.isNotEmpty() && grants.values.all { it }) dial(row)
                        }
                        org.msgat.ui.components.CallHistoryDialog(nettyClient.secureMessaging,
                            onClose = { showCallHistory = false },
                            onChat = { row ->
                                searchScope.launch {
                                    openChatTarget(row.peer, sourceIsOpen = { showCallHistory },
                                        onAccepted = { showCallHistory = false }, onKeep = { showCallHistory = true })
                                }
                            },
                            onCall = { row ->
                                val required = if (row.video) arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
                                    else arrayOf(android.Manifest.permission.RECORD_AUDIO)
                                val missing = required.filter { permission ->
                                    androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, permission) !=
                                        android.content.pm.PackageManager.PERMISSION_GRANTED
                                }
                                if (missing.isEmpty()) dial(row)
                                else if (pendingCall == null) {
                                    pendingCall = row
                                    callPermissions.launch(missing.toTypedArray())
                                }
                            })
                    }
                    if (showSearch) {
                        val origin = searchPeer
                        val request = searchRequest
                        var checkingResult by remember(request) { mutableStateOf(false) }
                        var resultError by remember(request) { mutableStateOf<Int?>(null) }
                        org.msgat.ui.components.MessageSearchDialog(
                            db, searchStore, origin,
                            initial = searchInitial,
                            openingResult = checkingResult,
                            openError = resultError,
                            onCriteriaChanged = { resultError = null },
                            onDismiss = { input ->
                                if (request == searchRequest && showSearch) {
                                    openingSearchResult?.cancel()
                                    rememberSearch(origin, input)
                                    showSearch = false
                                }
                            },
                            onSelect = open@{ hit, input ->
                                if (checkingResult) return@open
                                checkingResult = true
                                resultError = null
                                val source = navController.currentBackStackEntry
                                openingSearchResult?.cancel()
                                openingSearchResult = searchScope.launch {
                                    fun current() = !appLock.locked && request == searchRequest &&
                                        source?.id == navController.currentBackStackEntry?.id
                                    fun notice(id: Int, inline: Boolean) {
                                        if (inline && showSearch) resultError = id
                                        else android.widget.Toast.makeText(this@MainActivity, id, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    suspend fun isAvailable(inline: Boolean = false): Boolean = try {
                                        val valid = db.msgDao().hasHistoryAnchor(hit.peer, hit.messageId, includeRevoked = false) &&
                                            db.msgDao().getConversationByUuid(hit.peer) != null
                                        if (!valid && current()) notice(R.string.ui_search_target_unavailable, inline)
                                        valid
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        if (current()) notice(R.string.ui_search_failed, inline)
                                        false
                                    }
                                    val available = try { isAvailable(inline = true) }
                                    finally { checkingResult = false }
                                    if (!available || !current() || !showSearch) return@launch
                                    showSearch = false
                                    val currentPeer = source?.arguments?.getString("uuid")
                                    if (source != null && currentPeer != null && currentPeer != hit.peer) {
                                        panelNavigation = source.id to ChatMessageNavigation(
                                            peer = hit.peer, messageId = hit.messageId,
                                            open = {
                                                val valid = isAvailable()
                                                if (!current()) false
                                                else {
                                                    panelNavigation = null
                                                    if (valid) revealSearchHit(origin, input, hit, request)
                                                    else openSearch(origin, input)
                                                    valid
                                                }
                                            },
                                            cancel = { if (current()) openSearch(origin, input) }
                                        )
                                    } else {
                                        revealSearchHit(origin, input, hit, request)
                                    }
                                }
                            }
                        )
                    }
                    if(showSaved) org.msgat.ui.components.SavedMessagesDialog(nettyClient, myUuid = securePrefs.myUuid,
                        onOpenContactChat = { account ->
                            searchScope.launch {
                                openChatTarget(account, sourceIsOpen = { showSaved },
                                    onAccepted = { showSaved = false }, onKeep = { showSaved = true })
                            }
                        }, onDismiss={showSaved=false},onForward={ids,complete->
                        forwarding=org.msgat.ui.components.ForwardMessageRequest(null,ids.toList(),complete)
                    },onOriginal={saved ->
                        searchScope.launch {
                            val opened = openChatTarget(saved.sourcePeer, saved.sourceId, sourceIsOpen = { showSaved },
                                onAccepted = { showSaved = false }, onKeep = { showSaved = true },
                                unavailableText = R.string.ui_saved_original_unavailable)
                            if (!opened && showSaved && !appLock.locked) {
                                android.widget.Toast.makeText(this@MainActivity, R.string.ui_saved_original_unavailable,
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                    if (showForwardTasks) org.msgat.ui.components.ForwardTasksDialog(
                        nettyClient.secureMessaging.forwarding,
                        onOpenOriginal = { peer, id ->
                            openChatTarget(peer, id, sourceIsOpen = { showForwardTasks },
                                onAccepted = { showForwardTasks = false }, onKeep = { showForwardTasks = true })
                        }
                    ) { showForwardTasks = false }
                    forwarding?.let {request -> org.msgat.ui.components.ForwardMessagesDialog(request.ids.size,
                        loadRecipients={db.msgDao().getForwardTargets()},
                        onForward={targets,hideSender,hideCaption,options,omitPreview->nettyClient.secureMessaging.forward(request.sourcePeer,request.ids,targets,hideSender,hideCaption,options,omitPreview)},
                        onDismiss={forwarding=null},onCommitted={
                            request.onCommitted()
                            forwarding=null
                            val strings = AppLanguage.restore(this@MainActivity).resources(this@MainActivity)
                            android.widget.Toast.makeText(this@MainActivity, strings.getString(R.string.ui_forwarding_queued), android.widget.Toast.LENGTH_SHORT).show()
                        })}
                    incomingShares.pending?.takeIf { openingShare == null }?.let { share ->
                        key(share) {
                            IncomingShareDialog(share, loadRecipients = { db.msgDao().getForwardTargets() },
                                onSelect = { peer ->
                                    openChatTarget(peer, sourceIsOpen = { incomingShares.pending === share },
                                        onAccepted = { openingShare = share }, onKeep = { openingShare = null },
                                        unavailableText = R.string.ui_shared_chat_unavailable,
                                        canOpen = { db.msgDao().getForwardTargets().any { it.uuid == peer } },
                                        onOpened = { entryId ->
                                            if (incomingShares.pending === share) {
                                                incomingShares.pending = null
                                                incomingShares.routed = entryId to share
                                            }
                                            openingShare = null
                                        }, onUnavailable = { openingShare = null })
                                },
                                onImportStickers = { incomingShares.stickers = share.files; incomingShares.pending = null },
                                onDismiss = { incomingShares.pending = null })
                        }
                    }
                    incomingShares.stickers?.let { uris ->
                        key(uris) {
                            org.msgat.ui.components.StickerLibraryDialog(
                                nettyClient,
                                onDismiss = { incomingShares.stickers = null },
                                openSharedSource = {
                                    try {
                                        if (uris.size == 1) StickerSource.open(this@MainActivity, uris.single())
                                        else StickerSource.openFiles(this@MainActivity, uris)
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        val strings = AppLanguage.restore(this@MainActivity).resources(this@MainActivity)
                                        throw java.io.IOException(strings.getString(R.string.ui_shared_stickers_unavailable))
                                    }
                                }
                            )
                        }
                    }
                    if (showBackgroundReception) {
                        org.msgat.ui.components.BackgroundReceptionDialog(onDismiss = { showBackgroundReception = false })
                    }
                    val answerCall by answerCallRequested.collectAsStateWithLifecycle()
                    CallOverlay(
                        answerCallId = answerCall,
                        onAnswerHandled = { id -> answerCallRequested.compareAndSet(id, null) }
                    )
                }
            }
        }
    }
}
