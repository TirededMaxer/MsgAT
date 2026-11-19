// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import org.msgat.MsgATApp
import org.msgat.R
import org.msgat.core.crypto.SecurityCore
import org.msgat.core.network.NettyClient
import org.msgat.data.db.ContactEntry
import org.msgat.data.db.ContactSafetyInfo
import org.msgat.ui.theme.uiString

private enum class ContactPage { Directory, Profile, Safety }
private enum class ContactDecision { Request, Block, Unblock, Delete }

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ContactsDialog(
    client: NettyClient,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    focusedPeer: String? = null,
    onSharedMedia: (() -> Unit)? = null,
    onVoiceCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    notificationMuteUntil: Long = 0L,
    notificationsMuted: Boolean = false,
    onChangeNotifications: ((String, Long?) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val myId = MsgATApp.instance.securePrefs.myUuid
    val copied = uiString(R.string.ui_contact_id_copied)
    val listState = rememberLazyListState()
    var page by remember(focusedPeer) { mutableStateOf(if (focusedPeer == null) ContactPage.Directory else ContactPage.Profile) }
    var selectedId by remember(focusedPeer) { mutableStateOf(focusedPeer) }
    val profileScroll = key(selectedId) { rememberScrollState() }
    var selected by remember(focusedPeer) { mutableStateOf<ContactEntry?>(null) }
    var loadingPeer by remember(focusedPeer) { mutableStateOf(focusedPeer != null) }
    var query by remember { mutableStateOf("") }
    var blockedOnly by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(emptyList<ContactEntry>()) }
    var loadingRows by remember { mutableStateOf(true) }
    var offset by remember { mutableIntStateOf(0) }
    var directoryLimit by remember { mutableIntStateOf(50) }
    var more by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var safety by remember { mutableStateOf<ContactSafetyInfo?>(null) }
    var otherCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var menu by remember { mutableStateOf(false) }
    var muteMenu by remember { mutableStateOf(false) }
    var ownCode by remember { mutableStateOf(false) }
    var addContact by remember { mutableStateOf(false) }
    var avatarPreview by remember { mutableStateOf(false) }
    var scannedId by remember { mutableStateOf<String?>(null) }
    var decision by remember { mutableStateOf<ContactDecision?>(null) }

    fun work(@StringRes failure: Int = R.string.ui_contact_action_failed, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = failure
            } finally {
                busy = false
            }
        }
    }

    fun select(peer: ContactEntry) {
        focus.clearFocus()
        error = null
        safety = null
        otherCode = ""
        selected = peer
        selectedId = peer.id
        page = ContactPage.Profile
    }

    fun back() {
        menu = false
        error = null
        when {
            page == ContactPage.Safety -> {
                otherCode = ""
                page = ContactPage.Profile
            }
            page == ContactPage.Profile && focusedPeer == null -> {
                selected = null
                selectedId = null
                page = ContactPage.Directory
            }
            else -> onDismiss()
        }
    }

    fun request(id: String) = work {
        check(client.secureMessaging.requestContact(id))
        client.contacts.list(exactPeer = id).firstOrNull()?.let(::select)
    }

    fun changeSearch(value: String, showBlocked: Boolean = blockedOnly) {
        if (value == query && showBlocked == blockedOnly) return
        error = null
        query = value
        blockedOnly = showBlocked
        offset = 0
        directoryLimit = 50
        rows = emptyList()
        more = false
        loadingRows = true
    }

    suspend fun consumeCode(value: String, expectedPeer: String?) {
        val code = withContext(Dispatchers.Default) {
            SecurityCore.call(JSONObject().put("operation", "parse_contact_code").put("value", value))
        }
        if (code.getString("kind") == "contact") {
            check(expectedPeer == null && page == ContactPage.Directory)
            scannedId = code.getString("id")
            decision = ContactDecision.Request
        } else {
            check(expectedPeer != null && expectedPeer == selectedId && selected?.revoked == false)
            client.contacts.verifyScan(expectedPeer, code.getString("scannable"))
            safety = client.contacts.safety(expectedPeer)
            otherCode = ""
            page = ContactPage.Safety
        }
    }

    var scanPeer by remember { mutableStateOf<String?>(null) }
    val pickCode = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val peer = scanPeer
            work(R.string.ui_contact_scan_failed) {
                consumeCode(withContext(Dispatchers.IO) { ContactCodes.read(context, uri) }, peer)
            }
        }
    }
    val cameraCode = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { image ->
        if (image != null) {
            val peer = scanPeer
            if (busy) image.recycle()
            else work(R.string.ui_contact_scan_failed) {
                try {
                    consumeCode(withContext(Dispatchers.Default) { ContactCodes.read(image) }, peer)
                } finally {
                    image.recycle()
                }
            }
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraCode.launch(null)
        else error = R.string.ui_contact_scan_camera_permission
    }
    fun scan(camera: Boolean) {
        menu = false
        scanPeer = selectedId
        if (!camera) {
            pickCode.launch("image/*")
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraCode.launch(null)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(client, selectedId) {
        val id = selectedId ?: return@LaunchedEffect
        client.contacts.changes.collectLatest {
            try {
                val refreshed = client.contacts.list(exactPeer = id).firstOrNull()
                if (id != selectedId) return@collectLatest
                selected = refreshed
                if (refreshed == null || refreshed.revoked) {
                    safety = null
                    otherCode = ""
                    if (page == ContactPage.Safety) page = ContactPage.Profile
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = R.string.ui_contact_load_failed
            } finally {
                if (id == selectedId) loadingPeer = false
            }
        }
    }
    LaunchedEffect(client) {
        client.contacts.changes.collectLatest {
            offset = 0
            revision++
        }
    }
    LaunchedEffect(client, query, blockedOnly, offset, revision, focusedPeer) {
        if (focusedPeer != null) return@LaunchedEffect
        try {
            if (offset == 0) {
                loadingRows = rows.isEmpty()
                if (query.isNotBlank()) delay(150)
                val refreshed = mutableListOf<ContactEntry>()
                var lastPage: List<ContactEntry> = emptyList()
                for (start in 0 until directoryLimit step 50) {
                    lastPage = client.contacts.list(query, blockedOnly, start)
                    refreshed.addAll(lastPage)
                    if (lastPage.size < 50) break
                }
                ensureActive()
                rows = refreshed.distinctBy { it.id }
                more = lastPage.size == 50
            } else {
                val next = client.contacts.list(query, blockedOnly, offset)
                ensureActive()
                rows = (rows + next).distinctBy { it.id }
                more = next.size == 50
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = R.string.ui_contact_load_failed
        } finally {
            loadingRows = false
        }
    }
    LaunchedEffect(query, blockedOnly) { listState.scrollToItem(0) }

    Dialog(
        onDismissRequest = { if (!busy) back() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOn,
            dismissOnBackPress = !busy,
            dismissOnClickOutside = false
        )
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                TopAppBar(
                    title = { Text(uiString(when (page) {
                        ContactPage.Directory -> R.string.ui_contacts
                        ContactPage.Profile -> R.string.ui_contact_profile
                        ContactPage.Safety -> R.string.ui_contact_safety
                    }), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = ::back, enabled = !busy) {
                            val close = page == ContactPage.Directory || (page == ContactPage.Profile && focusedPeer != null)
                            Icon(Icons.AutoMirrored.Filled.ArrowBack,
                                uiString(if (close) R.string.ui_close else R.string.ui_contact_back))
                        }
                    },
                    actions = {
                        if (page == ContactPage.Directory) {
                            IconButton(onClick = { focus.clearFocus(); addContact = true }, enabled = !busy) {
                                Icon(Icons.Default.PersonAdd, uiString(R.string.ui_add_friend))
                            }
                        }
                        if (page == ContactPage.Directory || (page == ContactPage.Profile && selected != null)) {
                            Box {
                                IconButton(onClick = { menu = true }, enabled = !busy) {
                                    Icon(Icons.Default.MoreVert, uiString(R.string.ui_contact_actions))
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (page == ContactPage.Directory) {
                                        DropdownMenuItem(text = { Text(uiString(R.string.ui_contact_my_qr)) }, onClick = {
                                            menu = false
                                            ownCode = true
                                        })
                                        DropdownMenuItem(text = { Text(uiString(R.string.ui_contact_blocked_list)) },
                                            trailingIcon = { if (blockedOnly) Icon(Icons.Default.Check, null) },
                                            onClick = { menu = false; changeSearch(query, !blockedOnly) })
                                        HorizontalDivider()
                                        DropdownMenuItem(text = { Text(uiString(R.string.ui_contact_scan_image)) }, onClick = { scan(false) })
                                        DropdownMenuItem(text = { Text(uiString(R.string.ui_contact_scan_camera)) }, onClick = { scan(true) })
                                    } else selected?.let { peer ->
                                        if (!peer.blocked || !peer.revoked) {
                                            DropdownMenuItem(text = {
                                                Text(uiString(if (peer.blocked) R.string.ui_contact_unblock else R.string.ui_contact_block))
                                            }, onClick = {
                                                menu = false
                                                decision = if (peer.blocked) ContactDecision.Unblock else ContactDecision.Block
                                            })
                                        }
                                        DropdownMenuItem(text = {
                                            Text(uiString(R.string.ui_contact_delete), color = MaterialTheme.colorScheme.error)
                                        }, onClick = { menu = false; decision = ContactDecision.Delete })
                                    }
                                }
                            }
                        }
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
                if (busy || loadingPeer) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { message ->
                    Text(uiString(message), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error)
                }
                AnimatedContent(
                    targetState = page,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally { direction * it / 4 } + fadeIn())
                            .togetherWith(slideOutHorizontally { -direction * it / 4 } + fadeOut())
                    },
                    label = "contacts navigation"
                ) { currentPage ->
                    when (currentPage) {
                        ContactPage.Directory -> Column(Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { value -> changeSearch(value.take(256)) },
                                placeholder = { Text(uiString(R.string.ui_contact_search)) },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                trailingIcon = if (query.isBlank()) null else {
                                    { IconButton(onClick = { changeSearch("") }) {
                                        Icon(Icons.Default.Close, uiString(R.string.ui_search_clear))
                                    } }
                                },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                            if (blockedOnly) Text(uiString(R.string.ui_contact_blocked_list),
                                Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                            HorizontalDivider()
                            if (rows.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    if (loadingRows) CircularProgressIndicator(Modifier.size(24.dp))
                                    else if (error == null) Text(uiString(R.string.ui_contact_no_results))
                                }
                            } else {
                                LazyColumn(Modifier.weight(1f), state = listState) {
                                    items(rows, key = { it.id }) { peer ->
                                        ListItem(
                                            headlineContent = { Text(peer.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            supportingContent = { Text(uiString(contactStatus(peer))) },
                                            leadingContent = { ContactAvatar(peer.avatar, peer.name, 46) },
                                            modifier = Modifier.clickable(enabled = !busy && currentPage == page) { select(peer) }
                                        )
                                        HorizontalDivider(Modifier.padding(start = 76.dp))
                                    }
                                    if (more) item {
                                        TextButton(onClick = { offset = directoryLimit; directoryLimit += 50; more = false }, enabled = !busy) {
                                            Text(uiString(R.string.ui_contact_more))
                                        }
                                    }
                                }
                            }
                        }
                        ContactPage.Profile -> {
                            val peer = selected
                            if (peer == null) {
                                if (!loadingPeer && error == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(uiString(R.string.ui_contact_unavailable))
                                }
                            } else {
                                val actionsEnabled = !busy && page == currentPage
                                val connected = peer.accepted && !peer.blocked && !peer.revoked
                                Column(Modifier.fillMaxSize().verticalScroll(profileScroll).padding(bottom = 16.dp)) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        val preview = if (peer.avatar.isNullOrEmpty()) Modifier else Modifier.clip(CircleShape)
                                            .clickable(enabled = actionsEnabled) { avatarPreview = true }
                                        ContactAvatar(peer.avatar, peer.name, 88, preview)
                                        Spacer(Modifier.height(10.dp))
                                        Text(peer.name, style = MaterialTheme.typography.headlineSmall,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(uiString(contactStatus(peer)), style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (connected) {
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (focusedPeer == null) ProfileAction(
                                                Modifier.weight(1f), Icons.Default.ChatBubbleOutline,
                                                R.string.ui_contact_open_chat, actionsEnabled
                                            ) { onDismiss(); onOpen(peer.id) }
                                            onVoiceCall?.let { call -> ProfileAction(Modifier.weight(1f), Icons.Default.Call,
                                                R.string.ui_voice_call, actionsEnabled) { onDismiss(); call() } }
                                            onVideoCall?.let { call -> ProfileAction(Modifier.weight(1f), Icons.Default.Videocam,
                                                R.string.ui_video_call, actionsEnabled) { onDismiss(); call() } }
                                            onSharedMedia?.let { open -> ProfileAction(Modifier.weight(1f), Icons.Default.Collections,
                                                R.string.ui_shared_media, actionsEnabled) { onDismiss(); open() } }
                                        }
                                    }
                                    if (!peer.blocked && !peer.revoked && (peer.incoming || !connected)) {
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (peer.incoming) {
                                                Button(onClick = { work { check(client.secureMessaging.acceptContact(peer.id)) } },
                                                    enabled = actionsEnabled, modifier = Modifier.weight(1f)) {
                                                    Text(uiString(R.string.ui_contact_accept))
                                                }
                                                OutlinedButton(onClick = { work { check(client.secureMessaging.declineContact(peer.id)) } },
                                                    enabled = actionsEnabled, modifier = Modifier.weight(1f)) {
                                                    Text(uiString(R.string.ui_contact_decline))
                                                }
                                            } else {
                                                Button(onClick = { request(peer.id) }, enabled = actionsEnabled, modifier = Modifier.weight(1f)) {
                                                    Text(uiString(if (peer.outgoing) R.string.ui_contact_resend_request else R.string.ui_contact_send_request))
                                                }
                                            }
                                        }
                                        if (peer.outgoing && !connected) TextButton(
                                            onClick = { work { check(client.secureMessaging.withdrawContact(peer.id)) } },
                                            enabled = actionsEnabled, modifier = Modifier.align(Alignment.CenterHorizontally)
                                        ) { Text(uiString(R.string.ui_contact_withdraw_request)) }
                                    }
                                    Spacer(Modifier.height(20.dp))
                                    HorizontalDivider()
                                    ListItem(
                                        overlineContent = { Text(uiString(R.string.ui_contact_public_id)) },
                                        headlineContent = {
                                            SelectionContainer { Text(peer.id, fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.bodyMedium) }
                                        },
                                        trailingContent = {
                                            IconButton(onClick = {
                                                clipboard.setText(AnnotatedString(peer.id))
                                                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                                            }) { Icon(Icons.Default.ContentCopy, uiString(R.string.ui_contact_copy_id)) }
                                        }
                                    )
                                    if (!peer.revoked) {
                                        HorizontalDivider(Modifier.padding(start = 16.dp))
                                        ListItem(
                                            headlineContent = { Text(uiString(R.string.ui_contact_safety)) },
                                            leadingContent = { Icon(Icons.Default.Security, null) },
                                            modifier = Modifier.clickable(enabled = actionsEnabled) {
                                                work {
                                                    val code = client.contacts.safety(peer.id)
                                                    if (selectedId == peer.id && selected?.revoked == false) {
                                                        safety = code
                                                        page = ContactPage.Safety
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    onChangeNotifications?.takeIf { connected }?.let { change ->
                                        HorizontalDivider(Modifier.padding(start = 16.dp))
                                        Box {
                                            ListItem(
                                                headlineContent = { Text(uiString(R.string.ui_conversation_notifications)) },
                                                leadingContent = { Icon(if (notificationsMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications, null) },
                                                modifier = Modifier.clickable(enabled = actionsEnabled) { muteMenu = true }
                                            )
                                            DropdownMenu(expanded = muteMenu, onDismissRequest = { muteMenu = false }) {
                                                ConversationMuteMenuItems(notificationMuteUntil) { action, until ->
                                                    muteMenu = false
                                                    change(action, until)
                                                }
                                            }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                        ContactPage.Safety -> {
                            val code = safety
                            val peer = selected
                            if (code != null && peer != null && !peer.revoked) {
                                val enabled = !busy && page == currentPage
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        ContactCodeImage(ContactCodes.verification(code.scannable))
                                    }
                                    Text(uiString(if (code.verified) R.string.ui_contact_verified else R.string.ui_contact_unverified),
                                        style = MaterialTheme.typography.titleMedium)
                                    SelectionContainer { Text(code.display.chunked(5).joinToString(" "), fontFamily = FontFamily.Monospace) }
                                    Text(uiString(R.string.ui_contact_compare), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    OutlinedTextField(otherCode, { otherCode = it.take(100) },
                                        label = { Text(uiString(R.string.ui_contact_matching_code)) },
                                        enabled = enabled, modifier = Modifier.fillMaxWidth())
                                    Button(onClick = { work {
                                        client.contacts.verify(peer.id, code.display)
                                        safety = client.contacts.safety(peer.id)
                                        otherCode = ""
                                    } }, enabled = enabled && otherCode.filterNot { it.isWhitespace() } == code.display) {
                                        Text(uiString(R.string.ui_contact_verify))
                                    }
                                    TextButton(onClick = { scan(false) }, enabled = enabled) { Text(uiString(R.string.ui_contact_scan_image)) }
                                    TextButton(onClick = { scan(true) }, enabled = enabled) { Text(uiString(R.string.ui_contact_scan_camera)) }
                                    if (code.verified) TextButton(onClick = { work {
                                        client.contacts.clearVerification(peer.id)
                                        safety = client.contacts.safety(peer.id)
                                    } }, enabled = enabled) { Text(uiString(R.string.ui_contact_clear_verification)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (addContact) AddConversationDialog(myId, { addContact = false }) { id ->
        addContact = false
        request(id)
    }
    if (ownCode) AlertDialog(
        onDismissRequest = { ownCode = false },
        title = { Text(uiString(R.string.ui_contact_public_identity)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                ContactCodeImage("msgat://contact/$myId")
                SelectionContainer { Text(myId, fontFamily = FontFamily.Monospace) }
            }
        },
        confirmButton = { TextButton(onClick = { ownCode = false }) { Text(uiString(R.string.ui_close)) } },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(myId))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }) { Text(uiString(R.string.ui_contact_copy_id)) }
        },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
    )
    if (avatarPreview && selected != null) {
        val peer = selected ?: return
        Dialog(onDismissRequest = { avatarPreview = false }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(rememberAvatarPainter(peer.avatar), peer.name,
                        Modifier.sizeIn(maxWidth = 360.dp, maxHeight = 360.dp).fillMaxWidth().aspectRatio(1f),
                        contentScale = ContentScale.Fit)
                    TextButton(onClick = { avatarPreview = false }) { Text(uiString(R.string.ui_close)) }
                }
            }
        }
    }
    val pending = decision
    if (pending != null) {
        val id = if (pending == ContactDecision.Request) scannedId else selectedId
        fun clearDecision() { decision = null; scannedId = null }
        AlertDialog(
            onDismissRequest = ::clearDecision,
            title = { Text(uiString(when (pending) {
                ContactDecision.Request -> R.string.ui_contact_confirm_request
                ContactDecision.Block -> R.string.ui_contact_confirm_block
                ContactDecision.Unblock -> R.string.ui_contact_confirm_unblock
                ContactDecision.Delete -> R.string.ui_contact_confirm_delete
            })) },
            text = if (pending == ContactDecision.Request && id != null) {
                { SelectionContainer { Text(id, fontFamily = FontFamily.Monospace) } }
            } else null,
            confirmButton = {
                TextButton(enabled = id != null, onClick = {
                    clearDecision()
                    if (id != null && pending == ContactDecision.Request) request(id)
                    else if (id != null && selected?.id == id) work {
                        if (pending == ContactDecision.Delete) {
                            check(client.secureMessaging.deleteContact(id))
                            back()
                        } else {
                            check(client.secureMessaging.setContactBlocked(id, pending == ContactDecision.Block))
                        }
                    }
                }) {
                    Text(uiString(if (pending == ContactDecision.Request) R.string.ui_contact_send_request else R.string.ui_confirm))
                }
            },
            dismissButton = { TextButton(onClick = ::clearDecision) { Text(uiString(R.string.ui_cancel)) } },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
        )
    }
}

@Composable
internal fun ContactAvatar(reference: String?, name: String, size: Int, modifier: Modifier = Modifier) {
    Surface(modifier.size(size.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        if (!reference.isNullOrEmpty()) {
            Image(rememberAvatarPainter(reference), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else Box(contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ProfileAction(modifier: Modifier, icon: ImageVector, @StringRes title: Int, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick, modifier = modifier.heightIn(min = 58.dp), enabled = enabled,
        shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(uiString(title), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@StringRes private fun contactStatus(peer: ContactEntry): Int = when {
    peer.revoked -> R.string.ui_contact_identity_reset
    peer.blocked -> R.string.ui_contact_blocked
    peer.accepted -> R.string.ui_contact_accepted
    peer.incoming -> R.string.ui_contact_incoming
    peer.outgoing -> R.string.ui_contact_pending
    peer.outcome == "declined" -> R.string.ui_contact_declined
    peer.outcome == "withdrawn" -> R.string.ui_contact_withdrawn
    peer.outcome == "deleted" -> R.string.ui_contact_removed
    else -> R.string.ui_contact_not_connected
}
