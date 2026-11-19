package org.msgat.ui.sessionlist

import org.msgat.ui.theme.uiString
import org.msgat.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.msgat.data.db.ConversationFolder
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Unarchive
import org.msgat.ui.theme.LocalAppearanceSettings
import org.msgat.ui.theme.LocalMessagingColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import org.msgat.ui.chat.SyncState
import org.msgat.ui.chat.MessageReceiptMark
import org.msgat.ui.components.AddConversationDialog
import org.msgat.ui.components.ConfirmDialog
import org.msgat.ui.components.SettingsDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// Renders the primary session list screen containing all active conversations.
fun SessionListScreen(
    syncState: SyncState,
    allConversations: List<ConversationUIModel>,
    folders: List<ConversationFolder>?,
    onConversationAction: (String, String, Long?, String?) -> Unit,
    onSetArchived: suspend (String, Boolean, Boolean) -> Boolean,
    onMovePin: (String, String, String, Boolean) -> Unit,
    onSaveFolder:suspend(org.msgat.data.db.ConversationFolder,Set<String>)->Boolean,onDeleteFolder:suspend(String)->Boolean,onMoveFolder:suspend(String,String,Boolean)->Boolean,
    friendRequests: List<org.msgat.data.db.FriendRequest>,
    onNavigateToChat: (String) -> Unit,
    onOpenQueuedMessage: suspend (String, String?) -> Boolean,
    onSearch: () -> Unit,
    onSavedMessages: () -> Unit,
    onForwardTasks: () -> Unit,
    onCallHistory: () -> Unit,
    appLockEnabled: Boolean,
    onToggleAppLock: () -> Unit,
    onLockNow: () -> Unit,
    onAddConversation: (uuid: String) -> Unit,
    onSaveSettings: (org.msgat.ui.components.ServerSettings) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onClearMessages: (String) -> Unit,
    onResetIdentity: () -> Unit,
    onSaveIdentity: (String, ByteArray?, Boolean, (Boolean) -> Unit) -> Unit,
    initialIp: String,
    initialPort: String,
    initialUuid: String,
    initialAvatarPath: String? = null,
    initialNickname: String? = null,
    initialTurnServer: String = "",
    initialTurnPort: String = "3478",
    initialTurnUsername: String = "",
    initialTurnPassword: String = "",
    initialTurnUseTls: Boolean = false,
    isIdentityMissing: Boolean = false,
    onAcceptFriendRequest: suspend (String) -> Boolean = { false },
    onDeclineFriendRequest: suspend (String) -> Boolean = { false }
) {
    var reorderPage by remember { mutableStateOf<String?>(null) }
    var showFolders by remember{mutableStateOf(false)}
    var showMenu by remember { mutableStateOf(false) }
    var showContacts by remember {mutableStateOf(false)}
    var showStickerLibrary by remember { mutableStateOf(false) }
    var showPrivacy by remember {mutableStateOf(false)}
    var showStorage by remember { mutableStateOf(false) }
    var showSendQueue by remember {mutableStateOf(false)}
    var showBackgroundReception by remember {mutableStateOf(false)}
    if(showBackgroundReception)org.msgat.ui.components.BackgroundReceptionDialog{showBackgroundReception=false}
    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showIdentityDialog by remember { mutableStateOf(isIdentityMissing) }
    var showFriendRequestsDialog by remember { mutableStateOf(false) }
    val archiveScope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val archiveNotice = remember { SnackbarHostState() }
    var archiveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var archivePending by remember { mutableStateOf(false) }
    val archiveDone = uiString(R.string.ui_chat_archived)
    val unarchiveDone = uiString(R.string.ui_chat_unarchived)
    val undo = uiString(R.string.ui_archive_undo)
    val archiveError = uiString(R.string.ui_archive_change_failed)
    fun changeArchive(peer: String, archived: Boolean) {
        if (archivePending) return
        archiveJob?.cancel()
        archivePending = true
        archiveJob = archiveScope.launch {
            suspend fun apply(expected: Boolean, value: Boolean): Boolean = try {
                onSetArchived(peer, expected, value)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            val changed = try { apply(!archived, archived) } finally { archivePending = false }
            if (!changed) {
                archiveNotice.showSnackbar(archiveError, duration = SnackbarDuration.Short)
                return@launch
            }
            if (archiveNotice.showSnackbar(if (archived) archiveDone else unarchiveDone, actionLabel = undo,
                    duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                archivePending = true
                val restored = try { apply(archived, !archived) } finally { archivePending = false }
                if (!restored) archiveNotice.showSnackbar(archiveError, duration = SnackbarDuration.Short)
            }
        }
    }

    Scaffold(
        containerColor = LocalMessagingColors.current.sidebar,
        snackbarHost = { SnackbarHost(archiveNotice) },
        floatingActionButton = {
            if (!isIdentityMissing && reorderPage == null) FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = uiString(R.string.ui_add_friend))
            }
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LocalMessagingColors.current.chrome,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = { Text("MsgAT") },
                actions = {

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                        when (syncState) {
                            SyncState.DISCONNECTED -> {
                                Box(modifier = Modifier.size(10.dp).background(colors.error, CircleShape))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(uiString(R.string.ui_disconnected), color = colors.onSurfaceVariant, fontSize = 12.sp)
                            }
                            SyncState.SYNCING -> {
                                CircularProgressIndicator(color = colors.primary, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(uiString(R.string.ui_syncing), color = colors.onSurfaceVariant, fontSize = 12.sp)
                            }
                            SyncState.SYNCED -> {
                                Box(modifier = Modifier.size(10.dp).background(colors.primary, CircleShape))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(uiString(R.string.ui_connected), color = colors.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = uiString(R.string.ui_search_messages))
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = uiString(R.string.ui_menu))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                        DropdownMenuItem(text={Text(uiString(R.string.ui_chat_folders))},onClick={showMenu=false;showFolders=true})
                        DropdownMenuItem(text={Text(uiString(R.string.ui_contacts))},onClick={showMenu=false;showContacts=true})
                        DropdownMenuItem(text = { Text(uiString(R.string.ui_sticker_library)) }, onClick = {
                            showMenu = false
                            showStickerLibrary = true
                        })
                        DropdownMenuItem(text={Text(uiString(R.string.ui_privacy))},onClick={showMenu=false;showPrivacy=true})
                        DropdownMenuItem(
                            text = { Text(uiString(if (appLockEnabled) R.string.ui_disable_app_lock else R.string.ui_enable_app_lock)) },
                            onClick = { showMenu = false; onToggleAppLock() }
                        )
                        if (appLockEnabled) {
                            DropdownMenuItem(text = { Text(uiString(R.string.ui_lock_now)) }, onClick = { showMenu = false; onLockNow() })
                        }
                        DropdownMenuItem(text={Text(uiString(R.string.ui_storage_usage))},onClick={showMenu=false;showStorage=true})
                        DropdownMenuItem(text={Text(uiString(R.string.ui_background_reception))},onClick={showMenu=false;showBackgroundReception=true})
                        DropdownMenuItem(text={Text(uiString(R.string.ui_sending_queue))},onClick={showMenu=false;showSendQueue=true})
                        DropdownMenuItem(text = { Text(uiString(R.string.ui_friend_requests), color = MaterialTheme.colorScheme.onSurface) }, onClick = { showMenu = false; showFriendRequestsDialog = true })
                        DropdownMenuItem(text={Text(uiString(R.string.ui_edit_profile))},onClick={showMenu=false;showIdentityDialog=true})
                        DropdownMenuItem(text = { Text(uiString(R.string.ui_settings), color = MaterialTheme.colorScheme.onSurface) }, onClick = { showMenu = false; showSettingsDialog = true })
                        DropdownMenuItem(text = { Text(uiString(R.string.ui_saved_messages)) }, onClick = { showMenu = false; onSavedMessages() })
                        DropdownMenuItem(text = { Text(uiString(R.string.ui_call_history)) }, onClick = { showMenu = false; onCallHistory() })
                        DropdownMenuItem(text = { Text(uiString(R.string.ui_forwarding_queue)) }, onClick = { showMenu = false; onForwardTasks() })
                    }
                }
            )
        }
    ) { padding ->
        ConversationPages(
            modifier = Modifier.padding(padding).fillMaxSize(),
            allConversations = allConversations,
            folders = folders,
            onNavigateToChat = onNavigateToChat,
            onDelete = onDeleteConversation,
            onClear = onClearMessages,
            onAction = { peer, action, until, folder ->
                if (action == "archive" || action == "unarchive") changeArchive(peer, action == "archive")
                else onConversationAction(peer, action, until, folder)
            },
            onMovePin = onMovePin,
            reorderPage = reorderPage,
            onReorderPage = { reorderPage = it }
        )
    }
    if(showFolders)org.msgat.ui.components.ConversationFoldersDialog(folders.orEmpty(),allConversations,onSaveFolder,onDeleteFolder,onMoveFolder){showFolders=false}
    if (showSendQueue) org.msgat.ui.components.SendQueueDialog(
        org.msgat.MsgATApp.instance.nettyClient.secureMessaging,
        org.msgat.MsgATApp.instance.nettyClient.connectionState,
        onOpenChat = onOpenQueuedMessage
    ) { showSendQueue = false }
    if(showPrivacy)org.msgat.ui.components.ReadReceiptPrivacyDialog(org.msgat.MsgATApp.instance.nettyClient.secureMessaging){showPrivacy=false}
    if (showStorage) org.msgat.ui.components.AttachmentCacheDialog(org.msgat.MsgATApp.instance.nettyClient.secureMessaging) {
        showStorage = false
    }
    if(showContacts)org.msgat.ui.components.ContactsDialog(org.msgat.MsgATApp.instance.nettyClient,onDismiss={showContacts=false},onOpen=onNavigateToChat)
    if (showStickerLibrary) org.msgat.ui.components.StickerLibraryDialog(
        org.msgat.MsgATApp.instance.nettyClient,
        onDismiss = { showStickerLibrary = false }
    )
    if (showAddDialog) AddConversationDialog(myUuid = initialUuid, onDismiss = { showAddDialog = false }, onConfirm = { u -> onAddConversation(u); showAddDialog = false })
    if (showSettingsDialog) SettingsDialog(initialIp = initialIp, initialPort = initialPort, initialUuid = initialUuid, initialAvatarPath = initialAvatarPath, initialNickname = initialNickname, initialTurnServer = initialTurnServer, initialTurnPort = initialTurnPort, initialTurnUsername = initialTurnUsername, initialTurnPassword = initialTurnPassword, initialTurnUseTls = initialTurnUseTls, onResetIdentity = {showSettingsDialog = false; showResetConfirm = true;}, onDismiss = { showSettingsDialog = false }, onConfirm = { s -> onSaveSettings(s); showSettingsDialog = false }, onSaveIdentity = onSaveIdentity)
    if (showResetConfirm) ConfirmDialog(uiString(R.string.ui_reset_warning_close), onDismiss = { showResetConfirm = false }, onConfirm = { onResetIdentity(); showResetConfirm = false })
    if (showIdentityDialog) {
        org.msgat.ui.components.IdentitySetupDialog(
            onDismiss={if(!isIdentityMissing)showIdentityDialog=false},
            initialNickname=initialNickname?:"",initialAvatar=initialAvatarPath,editing=!isIdentityMissing,
            onConfirm = { n, a, remove, complete ->
                onSaveIdentity(n,a,remove) {saved->complete(saved);if(saved)showIdentityDialog=false}
            }
        )
    }

    if (showFriendRequestsDialog) {
        org.msgat.ui.components.FriendRequestsDialog(
            requests = friendRequests,
            onDismiss = { showFriendRequestsDialog = false },
            onAccept = onAcceptFriendRequest,
            onDecline = onDeclineFriendRequest
        )
    }
}

private data class ConversationPage(val id: String, val title: String, val folder: ConversationFolder? = null) {
    fun includes(row: ConversationUIModel, now: Long): Boolean {
        val custom = folder
        if (custom != null) {
            return (custom.includeAll || id in row.folderIDs.split(',')) &&
                (custom.includeArchived || !row.archived) &&
                (!custom.unreadOnly || row.hasUnread) &&
                (!custom.excludeMuted || row.muteUntil <= now)
        }
        return when (id) {
            "archive" -> row.archived
            "unread" -> !row.archived && row.hasUnread
            else -> !row.archived
        }
    }
}

@Composable
private fun ConversationPages(
    modifier: Modifier,
    allConversations: List<ConversationUIModel>,
    folders: List<ConversationFolder>?,
    onNavigateToChat: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: (String) -> Unit,
    onAction: (String, String, Long?, String?) -> Unit,
    onMovePin: (String, String, String, Boolean) -> Unit,
    reorderPage: String?,
    onReorderPage: (String?) -> Unit
) {
    val allTitle = uiString(R.string.ui_all_chats)
    val unreadTitle = uiString(R.string.ui_unread_chats)
    val archiveTitle = uiString(R.string.ui_archive)
    val showArchiveEntry = allConversations.any { it.archived }
    var selectedFolder by rememberSaveable { mutableStateOf("all") }
    var animateFolderChange by remember { mutableStateOf(false) }
    val archiveOpen = selectedFolder == "archive"
    val pages = remember(folders, allTitle, unreadTitle, archiveTitle, archiveOpen) {
        buildList {
            add(ConversationPage("all", allTitle))
            add(ConversationPage("unread", unreadTitle))
            folders.orEmpty().forEach { add(ConversationPage(it.id, it.name, it)) }
            if (archiveOpen) add(ConversationPage("archive", archiveTitle))
        }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val unreadCounts = remember(allConversations, pages, now) {
        val unread = allConversations.filter { it.hasUnread }
        pages.associate { page -> page.id to unread.count { page.includes(it, now) } }
    }
    val pager = rememberPagerState { pages.size }
    val swipeToArchive = LocalAppearanceSettings.current.swipeToArchive
    val tabs = rememberLazyListState()
    val pageIDs = remember(pages) { pages.map(ConversationPage::id) }
    val activeIndex = pageIDs.indexOf(selectedFolder).coerceAtLeast(0)
    val activePage = pages[activeIndex]
    val reordering = reorderPage == activePage.id
    LaunchedEffect(selectedFolder, pageIDs, folders != null) {
        val selected = pageIDs.indexOf(selectedFolder)
        if (selected < 0 && folders != null) {
            selectedFolder = "all"
            return@LaunchedEffect
        }
        val destination = selected.coerceAtLeast(0)
        val animated = animateFolderChange
        animateFolderChange = false
        if (pager.settledPage != destination || pager.isScrollInProgress) {
            if (animated) pager.animateScrollToPage(destination) else pager.scrollToPage(destination)
        }
        snapshotFlow { pager.settledPage }.collect { index ->
            val id = pageIDs.getOrNull(index)
            if (selected >= 0 && id != null && id != selectedFolder) selectedFolder = id
        }
    }
    BackHandler(enabled = reordering || archiveOpen) {
        if (reordering) onReorderPage(null)
        else selectedFolder = "all"
    }
    LaunchedEffect(activeIndex, pages) {
        if (reorderPage != null && !reordering) onReorderPage(null)
        if (tabs.layoutInfo.visibleItemsInfo.none { it.index == activeIndex }) {
            tabs.animateScrollToItem(activeIndex)
        }
    }

    Column(modifier) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            state = tabs,
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            items(pages, key = { it.id }) { page ->
                val selected = activePage.id == page.id
                val tint by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "Folder tab color"
                )
                val line by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    label = "Folder tab indicator"
                )
                val count = unreadCounts.getValue(page.id)
                Box(
                    modifier = Modifier.heightIn(min = 46.dp).widthIn(max = 200.dp)
                        .selectable(selected = selected, enabled = !reordering, role = Role.Tab, onClick = {
                            if (!selected) {
                                animateFolderChange = !archiveOpen && page.id != "archive"
                                selectedFolder = page.id
                            }
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(page.title, color = tint, style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (count > 0) Badge { Text(if (count > 99) "99+" else count.toString()) }
                    }
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(2.dp).background(line, CircleShape))
                    }
                }
            }
        }
        HorizontalDivider(color = LocalMessagingColors.current.divider)
        if (activePage.id == "archive" && !reordering) {
            TextButton(onClick = { selectedFolder = "all" }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(allTitle)
            }
        }
        if (reordering) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(uiString(R.string.ui_reorder_pinned_chats), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { onReorderPage(null) }) { Text(uiString(R.string.ui_done)) }
            }
        }
        HorizontalPager(state = pager, key = { pages[it].id }, userScrollEnabled = !reordering && !swipeToArchive && !archiveOpen,
            modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
            val page = pages[index]
            val rows = remember(allConversations, page, now) {
                val included = allConversations.filter { page.includes(it, now) }
                if (page.folder == null) included else included
                    .map { it.copy(pinOrder = it.folderPinOrders[page.id] ?: 0) }
                    .sortedWith(compareByDescending<ConversationUIModel> { it.pinOrder }.thenByDescending { it.activityAt }.thenBy { it.uuid })
            }
            val editing = reorderPage == page.id
            val pinned = rows.takeWhile { it.pinOrder > 0 }
            val displayed = if (editing) pinned else rows
            val latestPins by rememberUpdatedState(pinned)
            val movePin by rememberUpdatedState(onMovePin)
            val list = rememberLazyListState()
            val edge = with(LocalDensity.current) { 36.dp.toPx() }
            var dragged by remember(editing) { mutableStateOf<String?>(null) }
            var fingerY by remember(editing) { mutableFloatStateOf(0f) }

            fun destination(): String? {
                val visible = list.layoutInfo.visibleItemsInfo
                return visible.firstOrNull { fingerY >= it.offset && fingerY < it.offset + it.size }?.key as? String
            }

            LaunchedEffect(editing) {
                if (editing) list.scrollToItem(0)
            }
            LaunchedEffect(dragged) {
                if (dragged == null) return@LaunchedEffect
                while (true) {
                    val viewport = list.layoutInfo.viewportEndOffset
                    val delta = when {
                        fingerY < edge -> -12f
                        fingerY > viewport - edge -> 12f
                        else -> 0f
                    }
                    if (delta != 0f) list.scrollBy(delta)
                    delay(16)
                }
            }
            val dragModifier = if (editing) Modifier.pointerInput(page.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start ->
                        fingerY = start.y
                        dragged = destination()
                    },
                    onDragCancel = { dragged = null },
                    onDrag = { change, delta ->
                        if (dragged != null) {
                            change.consume()
                            fingerY += delta.y
                        }
                    },
                    onDragEnd = {
                        val source = dragged
                        val target = destination()
                        dragged = null
                        if (source != null && target != null && source != target) {
                            val from = latestPins.indexOfFirst { it.uuid == source }
                            val to = latestPins.indexOfFirst { it.uuid == target }
                            if (from >= 0 && to >= 0) movePin(page.id, source, target, to < from)
                        }
                    }
                )
            } else Modifier

            LazyColumn(
                Modifier.fillMaxSize().then(dragModifier), state = list,
                contentPadding = PaddingValues(bottom = if (editing) 0.dp else 80.dp)
            ) {
                if (page.id == "all" && showArchiveEntry && !editing) item(key = "archive_entry") {
                    val count = unreadCounts["archive"] ?: 0
                    val unreadLabel = if (count > 0) uiString(R.string.ui_chat_unread_count, count) else ""
                    Row(
                        Modifier.animateItem().fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(role = Role.Button) {
                                selectedFolder = "archive"
                            }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Archive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(archiveTitle, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (count > 0) {
                            org.msgat.ui.components.UnreadBadge(
                                if (count > 99) "99+" else count.toString(),
                                Modifier.clearAndSetSemantics { contentDescription = unreadLabel }
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (displayed.isEmpty() && !editing && (page.id != "all" || !showArchiveEntry)) item {
                    Box(Modifier.fillParentMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                        val label = when (page.id) {
                            "unread" -> R.string.ui_no_unread_chats
                            "archive" -> R.string.ui_no_archived_chats
                            else -> R.string.ui_no_chats
                        }
                        Text(uiString(label), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                items(displayed, key = { it.uuid }) { row ->
                    val pinIndex = pinned.indexOfFirst { it.uuid == row.uuid }
                    val hover = if (editing && dragged != null) destination() else null
                    val dragIndex = if (hover == row.uuid) pinned.indexOfFirst { it.uuid == dragged } else -1
                    val before = dragIndex > pinIndex
                    Box(Modifier.animateItem().fillMaxWidth()) {
                        ArchiveSwipe(swipeToArchive && !editing, row.archived, onArchive = {
                            onAction(row.uuid, if (row.archived) "unarchive" else "archive", null, null)
                        }) {
                        ConversationItem(
                            convo = row,
                            now = now,
                            reordering = editing,
                            dragging = dragged == row.uuid,
                            onClick = { onNavigateToChat(row.uuid) },
                            onDelete = { onDelete(row.uuid) },
                            onClear = { onClear(row.uuid) },
                            onReorderPins = if (pinned.size > 1) ({ onReorderPage(page.id) }) else null,
                            canMoveUp = pinIndex > 0,
                            canMoveDown = pinIndex >= 0 && pinIndex < pinned.lastIndex,
                            onAction = { action, until ->
                                when (action) {
                                    "pin_up", "pin_down" -> {
                                        val up = action == "pin_up"
                                        pinned.getOrNull(pinIndex + if (up) -1 else 1)?.let {
                                            onMovePin(page.id, row.uuid, it.uuid, up)
                                        }
                                    }
                                    else -> onAction(row.uuid, action, until,
                                        page.folder?.id?.takeIf { action == "pin" || action == "unpin" })
                                }
                            }
                        )
                        }
                        if (dragIndex >= 0 && hover != dragged) {
                            HorizontalDivider(
                                modifier = Modifier.align(if (before) Alignment.TopCenter else Alignment.BottomCenter),
                                thickness = 2.dp, color = MaterialTheme.colorScheme.primary
                            )
                        } else if (!editing) {
                            HorizontalDivider(
                                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 76.dp),
                                thickness = Dp.Hairline, color = LocalMessagingColors.current.divider
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveSwipe(enabled: Boolean, archived: Boolean, onArchive: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.38f })
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onArchive()
            state.reset()
        }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    Icon(if (archived) Icons.Default.Unarchive else Icons.Default.Archive, null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(uiString(if (archived) R.string.ui_unarchive_chat else R.string.ui_archive_chat),
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    ) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    convo: ConversationUIModel,
    now: Long,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onAction: (String, Long?) -> Unit,
    reordering: Boolean = false,
    dragging: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onReorderPins: (() -> Unit)? = null
) {
    var showActionMenu by remember { mutableStateOf(false) }
    var showMuteOptions by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val rowGesture = if (reordering) Modifier else Modifier.combinedClickable(
        onClick = onClick,
        onLongClickLabel = "Chat actions",
        onLongClick = { showActionMenu = true }
    )
    val time = conversationTime(if (convo.hasDraft) convo.activityAt else convo.lastAt, now)
    val muted = convo.muteUntil > now
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier.fillMaxWidth().then(rowGesture)
            .background(if (dragging) LocalMessagingColors.current.selection else Color.Transparent)
            .padding(start = 11.dp, end = 14.dp, top = 9.dp, bottom = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(52.dp)) {
                if (convo.avatarPath.isNotEmpty()) {
                    androidx.compose.foundation.Image(
                        painter = org.msgat.ui.components.rememberAvatarPainter(convo.avatarPath),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Text(convo.alias.take(1).uppercase(), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(convo.alias, modifier = Modifier.weight(1f, fill = false), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        if (muted) Icon(Icons.Default.NotificationsOff, uiString(R.string.ui_conversation_muted), Modifier.size(13.dp), tint = secondary)
                    }
                    if (time.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (!convo.hasDraft) convo.lastReceiptStatus?.let { status ->
                                key(convo.lastMessageId) { MessageReceiptMark(status) }
                            }
                            Text(time, modifier = Modifier.widthIn(max = 104.dp), color = secondary,
                                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(Modifier.heightIn(min = 21.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (convo.hasDraft) {
                            Text(uiString(R.string.ui_draft_prefix), color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        val preview = if (convo.hasDraft) convo.draftText.ifEmpty { uiString(R.string.ui_reply) }
                            else when (convo.summaryKind) {
                                "revoked" -> uiString(R.string.ui_quote_revoked)
                                "attachment" -> uiString(R.string.ui_quote_attachment)
                                "call" -> uiString(R.string.ui_call_history)
                                "contact" -> uiString(R.string.ui_contact_card) + " · " + convo.summary.removePrefix("Contact: ")
                                else -> org.msgat.core.call.displayCallSummary(convo.summary)
                                    .ifEmpty { convo.uuid.take(12) + "…" }
                            }
                        org.msgat.ui.chat.MessageSummaryText(
                            text = preview, modifier = Modifier.weight(1f), color = secondary,
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (convo.pinOrder > 0) Icon(Icons.Default.PushPin, uiString(R.string.ui_chat_pinned), Modifier.size(13.dp), tint = secondary)
                    if (convo.hasUnread) {
                        val unreadDescription = if (convo.unreadCount > 0) uiString(R.string.ui_chat_unread_count, convo.unreadCount)
                            else uiString(R.string.ui_chat_marked_unread)
                        org.msgat.ui.components.UnreadBadge(
                            if (convo.unreadCount > 0) convo.unreadCount.toString() else "•",
                            Modifier.clearAndSetSemantics { contentDescription = unreadDescription }
                        )
                    }
                }
            }
            if (reordering) Icon(Icons.Default.DragHandle, null, tint = secondary)
        }
        DropdownMenu(expanded = showActionMenu, onDismissRequest = { showActionMenu = false; showMuteOptions = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            if (showMuteOptions) {
                DropdownMenuItem(text = { Text(uiString(R.string.ui_conversation_notifications)) }, onClick = { showMuteOptions = false })
                HorizontalDivider()
                org.msgat.ui.components.ConversationMuteMenuItems(convo.muteUntil) { action, until ->
                    showActionMenu = false
                    showMuteOptions = false
                    onAction(action, until)
                }
            } else {
                DropdownMenuItem(text={Text(uiString(if(convo.pinOrder>0) R.string.ui_unpin_chat else R.string.ui_pin_chat))},onClick={showActionMenu=false;onAction(if(convo.pinOrder>0)"unpin"else "pin",null)})
                if (convo.pinOrder > 0) {
                    if (canMoveUp) DropdownMenuItem(text = { Text(uiString(R.string.ui_move_pin_up)) }, onClick = { showActionMenu = false; onAction("pin_up", null) })
                    if (canMoveDown) DropdownMenuItem(text = { Text(uiString(R.string.ui_move_pin_down)) }, onClick = { showActionMenu = false; onAction("pin_down", null) })
                    if (onReorderPins != null) DropdownMenuItem(text = { Text(uiString(R.string.ui_reorder_pinned_chats)) },
                        onClick = { showActionMenu = false; onReorderPins() })
                }
                DropdownMenuItem(text={Text(uiString(if(convo.archived) R.string.ui_unarchive_chat else R.string.ui_archive_chat))},onClick={showActionMenu=false;onAction(if(convo.archived)"unarchive"else "archive",null)})
                DropdownMenuItem(text={Text(uiString(if(convo.hasUnread) R.string.ui_mark_chat_read else R.string.ui_mark_chat_unread))},onClick={showActionMenu=false;onAction(if(convo.hasUnread)"read"else "unread",null)})
                DropdownMenuItem(text = { Text(uiString(R.string.ui_conversation_notifications)) }, onClick = { showMuteOptions = true })
                HorizontalDivider()
                DropdownMenuItem(text = { Text(uiString(R.string.ui_clear_messages), color = MaterialTheme.colorScheme.error) }, onClick = { showActionMenu = false; showClearConfirm = true })
                DropdownMenuItem(text = { Text(uiString(R.string.ui_delete_friend), color = MaterialTheme.colorScheme.error) }, onClick = { showActionMenu = false; showDeleteConfirm = true })
            }
        }
    }

    if (showClearConfirm) {
        val action = uiString(R.string.ui_clear_messages)
        ConfirmDialog(uiString(R.string.ui_clear_messages_confirm, convo.alias), title = action, confirmLabel = action,
            onDismiss = { showClearConfirm = false }, onConfirm = { showClearConfirm = false; onClear() })
    }
    if (showDeleteConfirm) {
        val action = uiString(R.string.ui_delete_friend)
        ConfirmDialog(uiString(R.string.ui_delete_friend_confirm, convo.alias), title = action, confirmLabel = action,
            onDismiss = { showDeleteConfirm = false }, onConfirm = { showDeleteConfirm = false; onDelete() })
    }
}

@Composable
// Renders a single row representing an incoming friend request in the list.
fun FriendRequestItem(
    request: org.msgat.data.db.FriendRequest,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Surface(shape = CircleShape, color = Color.DarkGray, modifier = Modifier.size(48.dp)) {
                    if (request.avatarPath.isNotEmpty()) {
                        androidx.compose.foundation.Image(
                            painter = org.msgat.ui.components.rememberAvatarPainter(request.avatarPath),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(request.nickname.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = request.nickname, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(text = "Wants to connect", color = Color.Gray, fontSize = 13.sp)
                }
            }
            Row {
                TextButton(onClick = onDecline) { Text("Decline", color = Color.Gray) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                    Text("Accept", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }
    }
}


@Composable
private fun conversationTime(timestamp: Long, now: Long): String {
    if (timestamp <= 0) return ""
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val locale = configuration.locales[0] ?: Locale.getDefault()
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    val use24Hour = DateFormat.is24HourFormat(context)
    return remember(timestamp, today, zone, locale, use24Hour) {
        when {
            day == today -> DateFormat.getTimeFormat(context).format(Date(timestamp))
            day.isAfter(today.minusDays(7)) && day.isBefore(today) ->
                day.format(DateTimeFormatter.ofPattern("EEE", locale))
            day.year == today.year ->
                day.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale))
            else -> DateFormat.getDateFormat(context).format(Date(timestamp))
        }
    }
}
