package org.msgat.ui.components

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString
import org.msgat.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import org.msgat.data.db.MessageNotificationStore
import org.msgat.core.media.AttachmentCacheCategory
import org.msgat.core.network.BackgroundReception
import org.msgat.core.crypto.securestorage.AttachmentExportLocation
import org.msgat.core.crypto.securestorage.SecurePrefs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.msgat.ui.theme.AppThemeMode
import org.msgat.ui.theme.AppLanguage
import org.msgat.ui.theme.AppearanceSettings
import org.msgat.ui.theme.LocalAppearanceSettings

// Bundles all connection settings (relay server + optional TURN fallback).
data class ServerSettings(
    val ip: String,
    val port: String,
    val uuid: String,
    val turnServer: String,
    val turnPort: String,
    val turnUsername: String,
    val turnPassword: String,
    val turnUseTls: Boolean,
    val serverCertificateSha256: String = "",
    val allowDevelopmentCertificate: Boolean = false
)

@Composable
// Shows a dialog for initiating a new chat conversation using a target UUID.
fun AddConversationDialog(myUuid: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var uuid by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    fun submit() {
        val normalized = uuid.trim().lowercase()
        error = when {
            !normalized.matches(Regex("[a-f0-9]{64}")) -> R.string.ui_contact_invalid_id
            normalized == myUuid.lowercase() -> R.string.ui_contact_self_id
            else -> null
        }
        if (error == null) onConfirm(normalized)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiString(R.string.ui_add_friend)) },
        text = {
            OutlinedTextField(
                value = uuid, onValueChange = { uuid = it.take(256); error = null },
                label = { Text(uiString(R.string.ui_contact_public_id)) },
                isError = error != null,
                supportingText = error?.let { message -> { Text(uiString(message)) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
            LaunchedEffect(focus) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = uuid.isNotBlank()) { Text(uiString(R.string.ui_contact_send_request)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiString(R.string.ui_cancel)) } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
    )
}

private enum class SettingsPage { Overview, Network, Profile, Notifications, Privacy, Storage, Background }
private enum class SettingsNetworkTarget { Advanced, Turn, Certificate }
private enum class SettingsTopic(val title: Int, val terms: Int) {
    Identity(R.string.ui_copy_my_uuid, R.string.settings_terms_identity),
    Profile(R.string.ui_edit_profile, R.string.settings_terms_profile),
    Network(R.string.ui_network, R.string.settings_terms_network),
    Advanced(R.string.ui_advanced_network, R.string.settings_terms_advanced),
    Turn(R.string.ui_turn_relay, R.string.settings_terms_turn),
    Certificate(R.string.ui_certificate_pin, R.string.settings_terms_certificate),
    Notifications(R.string.ui_notification_settings, R.string.settings_terms_notifications),
    Privacy(R.string.ui_privacy, R.string.settings_terms_privacy),
    Language(R.string.ui_language, R.string.settings_terms_language),
    Theme(R.string.ui_theme, R.string.settings_terms_theme),
    Size(R.string.ui_text_size, R.string.settings_terms_size),
    Swipe(R.string.ui_chat_list_swipe, R.string.settings_terms_chat_list_swipe),
    Storage(R.string.ui_storage_usage, R.string.settings_terms_storage),
    ExportFolder(R.string.ui_export_folder, R.string.settings_terms_export_folder),
    Background(R.string.ui_background_reception, R.string.settings_terms_background),
    Reset(R.string.ui_reset_identity, R.string.settings_terms_reset)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsDialog(
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
    onDismiss: () -> Unit,
    onConfirm: (ServerSettings) -> Unit,
    onResetIdentity: () -> Unit,
    onSaveIdentity: (String, ByteArray?, Boolean, (Boolean) -> Unit) -> Unit
) {
    val app = org.msgat.MsgATApp.instance
    val prefs = app.securePrefs
    val pendingRevocations by org.msgat.core.crypto.securestorage.IdentityLifecycle.pendingRevocations.collectAsState()
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var page by remember { mutableStateOf(SettingsPage.Overview) }
    var searchOpen by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var focusSearchOnOpen by remember { mutableStateOf(false) }
    val overviewScroll = rememberScrollState()
    val networkScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    fun updateSearch(value: String) {
        if (search == value) return
        search = value
        scope.launch { overviewScroll.scrollTo(0) }
    }
    val appearance = LocalAppearanceSettings.current
    var showTextScale by remember { mutableStateOf(false) }
    var showSwipeAction by remember { mutableStateOf(false) }
    var showThemeMode by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf(initialIp) }
    var port by remember { mutableStateOf(initialPort) }
    var turnServer by remember { mutableStateOf(initialTurnServer) }
    var turnPort by remember { mutableStateOf(initialTurnPort) }
    var turnUsername by remember { mutableStateOf(initialTurnUsername) }
    var turnPassword by remember { mutableStateOf(initialTurnPassword) }
    var turnUseTls by remember { mutableStateOf(initialTurnUseTls) }
    val initialPin = remember { prefs.serverCertificateSha256.orEmpty() }
    val initialDevelopmentCertificate = remember { prefs.allowDevelopmentCertificate }
    var certificatePin by remember { mutableStateOf(initialPin) }
    var allowDevelopmentCertificate by remember { mutableStateOf(initialDevelopmentCertificate) }
    var advanced by remember { mutableStateOf(false) }
    var networkTarget by remember { mutableStateOf<SettingsNetworkTarget?>(null) }
    val normalizedPin = certificatePin.replace(":", "").replace(" ", "").lowercase()
    val portValid = (port.toIntOrNull() ?: 0) in 1..65535
    val turnPortValid = (turnPort.toIntOrNull() ?: 0) in 1..65535
    val pinValid = normalizedPin.isEmpty() || normalizedPin.matches(Regex("[a-f0-9]{64}"))
    val validSettings = ip.isNotBlank() && portValid && (turnServer.isBlank() || turnPortValid) && pinValid
    val overview = { page = SettingsPage.Overview }
    fun cancelNetwork() {
        ip = initialIp
        port = initialPort
        turnServer = initialTurnServer
        turnPort = initialTurnPort
        turnUsername = initialTurnUsername
        turnPassword = initialTurnPassword
        turnUseTls = initialTurnUseTls
        certificatePin = initialPin
        allowDevelopmentCertificate = initialDevelopmentCertificate
        networkTarget = null
        overview()
    }
    fun openAdvanced(target: SettingsNetworkTarget) {
        advanced = true
        networkTarget = target
        page = SettingsPage.Network
    }
    when (page) {
        SettingsPage.Profile -> {
            IdentitySetupDialog(onDismiss = overview, initialNickname = initialNickname.orEmpty(), initialAvatar = initialAvatarPath,
                editing = true, onConfirm = { name, avatar, remove, completed ->
                    onSaveIdentity(name, avatar, remove) { saved ->
                        completed(saved)
                        if (saved) overview()
                    }
                })
            return
        }
        SettingsPage.Notifications -> { MessageNotificationsDialog(app.nettyClient.secureMessaging, overview); return }
        SettingsPage.Privacy -> { ReadReceiptPrivacyDialog(app.nettyClient.secureMessaging, overview); return }
        SettingsPage.Storage -> { AttachmentCacheDialog(app.nettyClient.secureMessaging, overview); return }
        SettingsPage.Background -> { BackgroundReceptionDialog(overview); return }
        SettingsPage.Overview, SettingsPage.Network -> Unit
    }

    val network = page == SettingsPage.Network
    val words = remember(search) { search.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }
    val searching = searchOpen && words.isNotEmpty()
    val matches = SettingsTopic.entries.filter { topic ->
        val index = uiString(topic.title) + " " + uiString(topic.terms)
        words.all { index.contains(it, ignoreCase = true) }
    }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f
    Dialog(
        onDismissRequest = {
            when {
                network -> overview()
                searchOpen -> { searchOpen = false; updateSearch("") }
                else -> onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)
    ) {
        val focusManager = LocalFocusManager.current
        val colors = LocalMessagingColors.current
        Surface(
            Modifier.safeDrawingPadding().imePadding().widthIn(max = 530.dp).fillMaxWidth(0.94f).heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.large,
            color = colors.chrome,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (network) IconButton(onClick = overview) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, uiString(R.string.ui_back_to_settings))
                    }
                    if (!network && searchOpen) {
                        val focus = remember { FocusRequester() }
                        OutlinedTextField(
                            value = search,
                            onValueChange = ::updateSearch,
                            placeholder = { Text(uiString(R.string.ui_search_settings)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).focusRequester(focus)
                        )
                        IconButton(onClick = { updateSearch(""); searchOpen = false; focusManager.clearFocus() }) {
                            Icon(Icons.Default.Close, uiString(R.string.ui_close_settings_search))
                        }
                        LaunchedEffect(focusSearchOnOpen) {
                            if (focusSearchOnOpen) {
                                focus.requestFocus()
                                focusSearchOnOpen = false
                            }
                        }
                    } else {
                        Text(uiString(if (network) R.string.ui_network else R.string.ui_settings),
                            Modifier.weight(1f).padding(start = if (network) 0.dp else 8.dp), style = MaterialTheme.typography.titleLarge)
                        if (!network) IconButton(onClick = {
                            focusSearchOnOpen = true
                            searchOpen = true
                        }) {
                            Icon(Icons.Default.Search, uiString(R.string.ui_search_settings))
                        }
                    }
                }
                HorizontalDivider(color = colors.divider)
                key(network) {
                    Column(
                        Modifier.weight(1f, fill = false).fillMaxWidth()
                            .verticalScroll(if (network) networkScroll else overviewScroll)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (network) {
                            val resultLocation = remember { BringIntoViewRequester() }
                            fun Modifier.networkAnchor(target: SettingsNetworkTarget): Modifier =
                                if (networkTarget == target) bringIntoViewRequester(resultLocation) else this
                            LaunchedEffect(networkTarget) {
                                if (networkTarget != null) {
                                    withFrameNanos { }
                                    resultLocation.bringIntoView()
                                    networkTarget = null
                                }
                            }
                            OutlinedTextField(ip, { ip = it }, label = { Text(uiString(R.string.ui_server_address)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                isError = ip.isBlank(),
                                supportingText = if (ip.isBlank()) { { Text(uiString(R.string.ui_network_address_required)) } } else null)
                            OutlinedTextField(port, { port = it }, label = { Text(uiString(R.string.ui_server_port)) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = !portValid,
                                supportingText = if (!portValid) { { Text(uiString(R.string.ui_network_port_range)) } } else null)
                            TextButton(onClick = { advanced = !advanced },
                                modifier = Modifier.networkAnchor(SettingsNetworkTarget.Advanced)) {
                                Text(uiString(if (advanced) R.string.ui_hide_advanced_network else R.string.ui_advanced_network))
                            }
                            androidx.compose.animation.AnimatedVisibility(advanced) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(uiString(R.string.ui_turn_relay),
                                        modifier = Modifier.networkAnchor(SettingsNetworkTarget.Turn),
                                        style = MaterialTheme.typography.titleSmall)
                                    OutlinedTextField(turnServer, { turnServer = it }, label = { Text(uiString(R.string.ui_turn_server)) },
                                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    OutlinedTextField(turnPort, { turnPort = it }, label = { Text(uiString(R.string.ui_turn_port)) },
                                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = turnServer.isNotBlank() && !turnPortValid,
                                        supportingText = if (turnServer.isNotBlank() && !turnPortValid) {
                                            { Text(uiString(R.string.ui_network_port_range)) }
                                        } else null)
                                    OutlinedTextField(turnUsername, { turnUsername = it }, label = { Text(uiString(R.string.ui_turn_username)) },
                                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    OutlinedTextField(turnPassword, { turnPassword = it }, label = { Text(uiString(R.string.ui_turn_password)) },
                                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(uiString(R.string.ui_turn_tls), Modifier.weight(1f))
                                        Switch(checked = turnUseTls, onCheckedChange = { turnUseTls = it })
                                    }
                                    HorizontalDivider()
                                    OutlinedTextField(certificatePin, { certificatePin = it }, label = { Text(uiString(R.string.ui_certificate_pin)) },
                                        modifier = Modifier.fillMaxWidth().networkAnchor(SettingsNetworkTarget.Certificate),
                                        singleLine = true, isError = !pinValid,
                                        supportingText = if (!pinValid) { { Text(uiString(R.string.ui_network_fingerprint_length)) } } else null)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(allowDevelopmentCertificate, { allowDevelopmentCertificate = it })
                                        Text(uiString(R.string.ui_allow_development_certificate))
                                    }
                                }
                            }
                        } else {
                            if (SettingsTopic.Identity in matches) {
                                val name = initialNickname.orEmpty().ifBlank { initialUuid.take(12) }
                                val copiedNotice = uiString(R.string.ui_message_copied)
                                Row(Modifier.fillMaxWidth().background(colors.sidebar, MaterialTheme.shapes.medium)
                                    .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(52.dp)) {
                                    if (initialAvatarPath.isNullOrEmpty()) {
                                        Box(contentAlignment = Alignment.Center) { Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium) }
                                    } else {
                                        androidx.compose.foundation.Image(rememberAvatarPainter(initialAvatarPath), null,
                                            Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                    Text(initialUuid, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(initialUuid))
                                    android.widget.Toast.makeText(context, copiedNotice,
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }) { Icon(Icons.Default.ContentCopy, uiString(R.string.ui_copy_my_uuid)) }
                                }
                            }
                            if (SettingsTopic.Profile in matches) SettingsLink(uiString(R.string.ui_edit_profile), Icons.Default.Edit) {
                                focusManager.clearFocus(); page = SettingsPage.Profile
                            }
                            if (!searching) HorizontalDivider(color = colors.divider)
                            if (SettingsTopic.Network in matches) SettingsLink(uiString(R.string.ui_network), Icons.Default.Dns, initialIp.takeIf { it.isNotBlank() }) {
                                focusManager.clearFocus(); page = SettingsPage.Network
                            }
                            if (searching && SettingsTopic.Advanced in matches) SettingsLink(uiString(R.string.ui_advanced_network), Icons.Default.Dns) {
                                focusManager.clearFocus()
                                openAdvanced(SettingsNetworkTarget.Advanced)
                            }
                            if (searching && SettingsTopic.Turn in matches) SettingsLink(uiString(R.string.ui_turn_relay), Icons.Default.Dns) {
                                focusManager.clearFocus()
                                openAdvanced(SettingsNetworkTarget.Turn)
                            }
                            if (searching && SettingsTopic.Certificate in matches) SettingsLink(uiString(R.string.ui_certificate_pin), Icons.Default.Security) {
                                focusManager.clearFocus()
                                openAdvanced(SettingsNetworkTarget.Certificate)
                            }
                            if (SettingsTopic.Notifications in matches) SettingsLink(uiString(R.string.ui_notification_settings), Icons.Default.Notifications) {
                                focusManager.clearFocus(); page = SettingsPage.Notifications
                            }
                            if (SettingsTopic.Privacy in matches) SettingsLink(uiString(R.string.ui_privacy), Icons.Default.Security) {
                                focusManager.clearFocus(); page = SettingsPage.Privacy
                            }
                            if (SettingsTopic.Language in matches) Box(Modifier.fillMaxWidth()) {
                                SettingsLink(uiString(R.string.ui_language), Icons.Default.Language, uiString(appearance.language.label)) { showLanguage = true }
                                DropdownMenu(expanded = showLanguage, onDismissRequest = { showLanguage = false }) {
                                    for (language in AppLanguage.entries) {
                                        DropdownMenuItem(text = { Text(uiString(language.label)) }, onClick = {
                                            appearance.updateLanguage(language)
                                            org.msgat.NotificationHelper.createChannels(context)
                                            showLanguage = false
                                        })
                                    }
                                }
                            }
                            if (SettingsTopic.Theme in matches) Box(Modifier.fillMaxWidth()) {
                                SettingsLink(uiString(R.string.ui_theme), Icons.Default.Brightness6, uiString(appearance.themeMode.label)) { showThemeMode = true }
                                DropdownMenu(expanded = showThemeMode, onDismissRequest = { showThemeMode = false }) {
                                    for (mode in AppThemeMode.entries) {
                                        DropdownMenuItem(text = { Text(uiString(mode.label)) }, onClick = {
                                            appearance.updateTheme(mode)
                                            showThemeMode = false
                                        })
                                    }
                                }
                            }
                            if (SettingsTopic.Size in matches) Box(Modifier.fillMaxWidth()) {
                                SettingsLink(uiString(R.string.ui_text_size), Icons.Default.FormatSize, "${appearance.percentage}%") { showTextScale = true }
                                DropdownMenu(expanded = showTextScale, onDismissRequest = { showTextScale = false }) {
                                    for (percentage in AppearanceSettings.choices) {
                                        DropdownMenuItem(text = { Text("$percentage%") }, onClick = {
                                            appearance.update(percentage)
                                            showTextScale = false
                                        })
                                    }
                                }
                            }
                            if (SettingsTopic.Swipe in matches) Box(Modifier.fillMaxWidth()) {
                                val archive = uiString(R.string.ui_archive_chat)
                                val switchFolder = uiString(R.string.ui_swipe_switch_folder)
                                SettingsLink(uiString(R.string.ui_chat_list_swipe), Icons.Default.Swipe,
                                    if (appearance.swipeToArchive) archive else switchFolder) { showSwipeAction = true }
                                DropdownMenu(expanded = showSwipeAction, onDismissRequest = { showSwipeAction = false }) {
                                    DropdownMenuItem(text = { Text(switchFolder) }, onClick = {
                                        appearance.updateSwipeToArchive(false)
                                        showSwipeAction = false
                                    })
                                    DropdownMenuItem(text = { Text(archive) }, onClick = {
                                        appearance.updateSwipeToArchive(true)
                                        showSwipeAction = false
                                    })
                                }
                            }
                            if (SettingsTopic.Storage in matches) SettingsLink(uiString(R.string.ui_storage_usage), Icons.Default.Storage) {
                                focusManager.clearFocus(); page = SettingsPage.Storage
                            }
                            if (SettingsTopic.ExportFolder in matches) ExportFolderSetting(prefs) { focusManager.clearFocus() }
                            if (SettingsTopic.Background in matches) SettingsLink(uiString(R.string.ui_background_reception), Icons.Default.Notifications) {
                                focusManager.clearFocus(); page = SettingsPage.Background
                            }
                            if (!searching) HorizontalDivider(color = colors.divider)
                            if (SettingsTopic.Reset in matches) {
                                if (pendingRevocations > 0) {
                                    Text(uiString(R.string.ui_pending_identity_revocations, pendingRevocations),
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = onResetIdentity) {
                                    Text(uiString(R.string.ui_reset_identity), color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (matches.isEmpty()) Text(uiString(R.string.ui_no_settings_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(color = colors.divider)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.End) {
                    if (network) {
                        TextButton(onClick = { focusManager.clearFocus(); cancelNetwork() }) { Text(uiString(R.string.ui_cancel)) }
                        Button(enabled = validSettings, onClick = {
                            onConfirm(ServerSettings(ip.trim(), port, initialUuid, turnServer, turnPort, turnUsername,
                                turnPassword, turnUseTls, normalizedPin, allowDevelopmentCertificate))
                        }) { Text(uiString(R.string.ui_save)) }
                    } else TextButton(onClick = onDismiss) { Text(uiString(R.string.ui_close)) }
                }
            }
        }
    }
}

@Composable
private fun ExportFolderSetting(prefs: SecurePrefs, clearFocus: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf(prefs.attachmentExportFolder) }
    var reading by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { selected ->
        if (selected != null && DocumentsContract.isTreeUri(selected)) {
            reading = true
            scope.launch {
                try {
                    val id = DocumentsContract.getTreeDocumentId(selected)
                    val doc = DocumentsContract.buildDocumentUriUsingTree(selected, id)
                    val name = withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                                null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                        } catch (_: SecurityException) { null }
                        catch (_: IllegalArgumentException) { null }
                    }
                    val location = AttachmentExportLocation(selected.toString(),
                        name?.takeIf { it.isNotBlank() } ?: id.substringAfterLast('/').substringAfterLast(':'))
                    prefs.attachmentExportFolder = location
                    folder = location
                } finally {
                    reading = false
                }
            }
        }
    }
    val summary = folder?.name?.takeIf { it.isNotEmpty() } ?: uiString(R.string.ui_export_folder_default)
    SettingsLink(uiString(R.string.ui_export_folder), Icons.Default.FolderOpen, summary) {
        clearFocus()
        visible = true
    }
    if (visible) {
        AlertDialog(
            onDismissRequest = { visible = false },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(uiString(R.string.ui_export_folder)) },
            text = {
                Column {
                    Text(summary)
                    if (folder != null) TextButton(enabled = !reading, onClick = {
                        prefs.attachmentExportFolder = null
                        folder = null
                        visible = false
                    }) { Text(uiString(R.string.ui_export_folder_default)) }
                }
            },
            confirmButton = {
                TextButton(enabled = !reading, onClick = { picker.launch(folder?.uri?.let(Uri::parse)) }) {
                    Text(uiString(R.string.ui_export_folder_choose))
                }
            },
            dismissButton = {
                TextButton(onClick = { visible = false }) { Text(uiString(R.string.ui_close)) }
            }
        )
    }
}

@Composable
private fun SettingsLink(title: String, icon: ImageVector, summary: String? = null, action: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = summary?.let { value -> { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = action)
    )
}

@Composable
private fun SettingsToggle(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
            value = checked, enabled = enabled, role = Role.Switch, onValueChange = change
        ).padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
// Shows a generic confirmation dialog for destructive or critical actions.
fun ConfirmDialog(
    message: String,
    title: String? = null,
    confirmLabel: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(title ?: uiString(R.string.ui_warning)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) {
            Text(confirmLabel ?: uiString(R.string.ui_confirm), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiString(R.string.ui_cancel)) } }
    )
}


@Composable
private fun MessageNotificationsDialog(messaging: org.msgat.core.network.SecureMessaging, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<MessageNotificationStore.Settings?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var systemNotificationsEnabled by remember(context) {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var systemSettingsFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    DisposableEffect(context, lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                systemNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(reload) {
        try { settings = messaging.messageNotificationSettings(); error = null }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = R.string.ui_notification_load_error }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(uiString(R.string.ui_notification_settings)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = settings
                if (current != null) {
                    SettingsToggle(uiString(R.string.ui_message_alerts), current.enabled, !busy) {
                        settings = current.copy(enabled = it)
                    }
                    SettingsToggle(uiString(R.string.ui_message_alert_sound), current.playSound, current.enabled && !busy) {
                        settings = current.copy(playSound = it)
                    }
                    SettingsToggle(uiString(R.string.ui_notification_previews), current.showPreviews, current.enabled && !busy) {
                        settings = current.copy(showPreviews = it)
                    }
                    if (current.enabled && current.showPreviews) {
                        Text(uiString(R.string.ui_unlocked_previews), style = MaterialTheme.typography.bodySmall)
                    }
                } else if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                HorizontalDivider(color = LocalMessagingColors.current.divider)
                if (!systemNotificationsEnabled) {
                    Text(uiString(R.string.ui_bg_notifications_blocked), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(enabled = !busy, onClick = {
                    systemSettingsFailed = !BackgroundReception.notificationSettings(context)
                }) { Text(uiString(R.string.ui_bg_manage_notifications)) }
                if (systemSettingsFailed) {
                    Text(uiString(R.string.ui_bg_settings_failed), style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error) }
                if (current == null && error != null) {
                    TextButton(onClick = { error = null; reload++ }) { Text(uiString(R.string.ui_retry)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = settings != null && !busy, onClick = {
                val current = settings ?: return@TextButton
                busy = true
                error = null
                scope.launch {
                    try {
                        if (messaging.setMessageNotificationSettings(current)) onDismiss()
                        else error = R.string.ui_notification_save_error
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { error = R.string.ui_notification_save_error }
                    finally { busy = false }
                }
            }) { Text(uiString(if (busy) R.string.ui_saving else R.string.ui_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(uiString(R.string.ui_cancel)) } }
    )
}

@Composable
fun ReadReceiptPrivacyDialog(messaging:org.msgat.core.network.SecureMessaging,onDismiss:()->Unit) {
    val scope=rememberCoroutineScope()
    var enabled by remember {mutableStateOf<Boolean?>(null)}
    var activity by remember {mutableStateOf<org.msgat.core.network.SecureMessaging.ActivityPrivacy?>(null)}
    var autoDownload by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<Int?>(null)}
    var generation by remember {mutableIntStateOf(0)}
    LaunchedEffect(generation) {
        try {
            enabled=messaging.readReceiptsEnabled();activity=messaging.activityPrivacy()
            autoDownload=messaging.automaticAttachmentDownloads();error=null
        }
        catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
        catch(_:Exception){error=R.string.ui_privacy_load_error}
    }
    AlertDialog(
        onDismissRequest={if(!busy)onDismiss()},
        properties=androidx.compose.ui.window.DialogProperties(securePolicy=androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
        title={Text(uiString(R.string.ui_privacy))},
        text={Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SettingsToggle(uiString(R.string.ui_read_receipts), enabled == true, enabled != null && !busy) {
                enabled = it
            }
            SettingsToggle(uiString(R.string.ui_share_online), activity?.online == true, activity != null && !busy) {
                activity = activity?.copy(online = it)
            }
            SettingsToggle(uiString(R.string.ui_share_typing), activity?.typing == true, activity != null && !busy) {
                activity = activity?.copy(typing = it)
            }
            SettingsToggle(uiString(R.string.ui_automatic_downloads), autoDownload == true, autoDownload != null && !busy) {
                autoDownload = it
            }
            Text(uiString(R.string.ui_activity_privacy_note))
            Text(uiString(R.string.ui_read_privacy_note))
            error?.let {Text(uiString(it),color=MaterialTheme.colorScheme.error)}
            if((enabled==null || activity==null || autoDownload==null) && error!=null)TextButton(onClick={generation++}){Text(uiString(R.string.ui_retry))}
        }},
        dismissButton={TextButton(onClick=onDismiss,enabled=!busy){Text(uiString(R.string.ui_cancel))}},
        confirmButton={TextButton(enabled=enabled!=null && activity!=null && autoDownload!=null && !busy,onClick={
            val value=enabled?:return@TextButton
            val flags=activity?:return@TextButton
            val downloads=autoDownload?:return@TextButton
            busy=true
            scope.launch {
                try {if(messaging.setReadReceiptsEnabled(value,flags,autoDownload=downloads))onDismiss() else error=R.string.ui_privacy_save_error}
                catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
                catch(_:Exception){error=R.string.ui_privacy_save_error}
                finally {busy=false}
            }
        }){Text(uiString(if(busy) R.string.ui_saving else R.string.ui_save))}}
    )
}

@Composable
internal fun AttachmentCacheDialog(messaging: org.msgat.core.network.SecureMessaging, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var usage by remember { mutableStateOf<org.msgat.core.media.AttachmentCacheUsage?>(null) }
    var pendingClear by remember { mutableStateOf<Pair<Set<AttachmentCacheCategory>, Long>?>(null) }
    var excluded by remember { mutableStateOf(emptySet<AttachmentCacheCategory>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var choosingLimit by remember { mutableStateOf(false) }

    LaunchedEffect(messaging) {
        combine(messaging.attachmentProgress, messaging.previewProgress) { attachments, previews ->
            attachments.mapValues { it.value.phase } to previews.mapValues { it.value.phase }
        }.distinctUntilChanged().drop(1).collect { revision++ }
    }

    LaunchedEffect(revision) {
        try {
            do {
                val current = messaging.attachmentCacheUsage()
                usage = current
                if (error == R.string.ui_cache_load_error) error = null
                if (current.pendingBytes == 0L) break
                kotlinx.coroutines.delay(2_000)
            } while (true)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = R.string.ui_cache_load_error }
    }

    fun size(bytes: Long) = android.text.format.Formatter.formatFileSize(context, bytes)
    @Composable fun limitLabel(bytes: Long) =
        if (bytes == 0L) uiString(R.string.ui_cache_no_limit) else size(bytes)
    val currentSelection = usage?.bytesByCategory.orEmpty().filterKeys { it !in excluded }
    val confirming = pendingClear != null
    AlertDialog(
        onDismissRequest = { if (!busy) { if (confirming) pendingClear = null else dismiss() } },
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
        title = { Text(uiString(if (confirming) R.string.ui_cache_clear_title else R.string.ui_storage_usage)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val pending = pendingClear
                if (pending != null) {
                    Text(uiString(R.string.ui_cache_clear_selected_size, size(pending.second)))
                    Text(uiString(R.string.ui_cache_clear_warning))
                } else {
                    val current = usage
                    if (current == null && error == null) CircularProgressIndicator()
                    if (current != null) {
                        Text(uiString(R.string.ui_cache_received_size, size(current.bytes)))
                        AttachmentCacheCategory.entries.forEach { category ->
                            val bytes = current.bytesByCategory[category] ?: return@forEach
                            val title = uiString(when (category) {
                                AttachmentCacheCategory.PHOTOS -> R.string.ui_shared_media_photos
                                AttachmentCacheCategory.VIDEOS -> R.string.ui_shared_media_videos
                                AttachmentCacheCategory.AUDIO -> R.string.ui_audio
                                AttachmentCacheCategory.FILES -> R.string.ui_files
                                AttachmentCacheCategory.LINK_PREVIEWS -> R.string.ui_cache_link_previews
                            })
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                                    value = category !in excluded, enabled = !busy, role = Role.Checkbox,
                                    onValueChange = { checked ->
                                        excluded = if (checked) excluded - category else excluded + category
                                    }
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = category !in excluded, onCheckedChange = null, enabled = !busy)
                                Text(title, Modifier.weight(1f).padding(horizontal = 10.dp))
                                Text(size(bytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (current.pendingBytes > 0L) Text(uiString(R.string.ui_cache_removing, size(current.pendingBytes)))
                        Box {
                            TextButton(enabled = !busy, onClick = { choosingLimit = true }) {
                                Text(uiString(R.string.ui_cache_auto_limit, limitLabel(current.automaticLimitBytes)))
                            }
                            DropdownMenu(expanded = choosingLimit, onDismissRequest = { choosingLimit = false }) {
                                org.msgat.core.media.EncryptedObjectLifecycle.automaticLimits.forEach { bytes ->
                                    DropdownMenuItem(text = { Text(limitLabel(bytes)) }, onClick = {
                                        choosingLimit = false
                                        if (bytes != current.automaticLimitBytes) {
                                            busy = true
                                            error = null
                                            scope.launch {
                                                try {
                                                    if (messaging.setAutomaticCacheLimit(bytes)) revision++
                                                    else error = R.string.ui_cache_limit_error
                                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                                catch (_: Exception) { error = R.string.ui_cache_limit_error }
                                                finally { busy = false }
                                            }
                                        }
                                    })
                                }
                            }
                        }
                        Text(uiString(R.string.ui_cache_limit_note), style = MaterialTheme.typography.bodySmall)
                        if (current.automaticLimitBytes > 0) {
                            val fraction = 1f - (current.automaticBudgetRemainingBytes.toDouble() /
                                current.automaticLimitBytes).toFloat()
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            Text(uiString(R.string.ui_cache_auto_remaining, size(current.automaticBudgetRemainingBytes)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error) }
                if (!confirming && error == R.string.ui_cache_load_error) {
                    TextButton(enabled = !busy, onClick = { error = null; revision++ }) {
                        Text(uiString(R.string.ui_retry))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { if (confirming) pendingClear = null else dismiss() }) {
                Text(uiString(if (confirming) R.string.ui_cancel else R.string.ui_close))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (confirming || currentSelection.isNotEmpty()), onClick = {
                val pending = pendingClear
                if (pending == null) {
                    pendingClear = currentSelection.keys.toSet() to currentSelection.values.sum()
                } else {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            if (messaging.clearAttachmentCache(pending.first)) {
                                pendingClear = null
                                revision++
                            } else error = R.string.ui_cache_clear_error
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { error = R.string.ui_cache_clear_error }
                        finally { busy = false }
                    }
                }
            }) { Text(uiString(R.string.ui_cache_clear)) }
        }
    )
}

@Composable
internal fun AppLockScreen(busy: Boolean, error: Int?, unlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Text(uiString(R.string.ui_app_locked), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = unlock, enabled = !busy) { Text(uiString(R.string.ui_unlock)) }
            error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error) }
        }
    }
}
