// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.msgat.R
import org.msgat.core.network.SendOptions
import org.msgat.data.db.ContactEntry
import org.msgat.data.db.Conversation
import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContactCardPicker(
    recipient: String,
    local: Conversation,
    initial: SendOptions,
    load: suspend () -> List<Conversation>,
    send: suspend (String, String, SendOptions) -> Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf<List<Conversation>?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Conversation?>(null) }
    var options by remember { mutableStateOf(initial) }
    var choosingOptions by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var sendFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(local.uuid, retry) {
        loadFailed = false
        contacts = try {
            (listOf(local) + load()).distinctBy { it.uuid }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
            listOf(local)
        }
    }
    Dialog(
        onDismissRequest = { if (!sending) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)
    ) {
        Surface(Modifier.safeDrawingPadding().imePadding().widthIn(max = 520.dp).fillMaxWidth(.92f),
            shape = MaterialTheme.shapes.large, color = LocalMessagingColors.current.chrome) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(uiString(R.string.ui_share_contact), style = MaterialTheme.typography.titleLarge)
                Text(uiString(R.string.ui_recipient, recipient), style = MaterialTheme.typography.bodyMedium)
                val contact = selected
                if (contact == null) {
                    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(uiString(R.string.ui_contact_search)) },
                        modifier = Modifier.fillMaxWidth())
                    if (loadFailed) ContactCardReadError(R.string.ui_contact_load_failed) { retry++ }
                    val entries = contacts
                    if (entries == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        val match = query.trim()
                        val results = entries.filter { it.alias.contains(match, true) || it.uuid.contains(match, true) }
                        if (results.isEmpty() && !loadFailed) Text(uiString(R.string.ui_contact_no_results))
                        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 300.dp)) {
                            items(results, key = { it.uuid }) { item ->
                                ListItem(headlineContent = { Text(item.alias, maxLines = 1) },
                                    leadingContent = { ContactAvatar(item.avatarPath, item.alias, 36) },
                                    supportingContent = { Text(if (item.uuid == local.uuid)
                                        uiString(R.string.ui_contact_public_identity) else item.uuid.takeLast(12)) },
                                    modifier = Modifier.clickable { selected = item; sendFailed = false })
                            }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ContactAvatar(contact.avatarPath, contact.alias, 36)
                            Text(cardName(contact.alias), style = MaterialTheme.typography.titleMedium)
                        }
                        SelectionContainer { Text(contact.uuid, style = MaterialTheme.typography.bodySmall) }
                        TextButton(enabled = !sending, onClick = { choosingOptions = true }) {
                            Text(sendOptionsLabel(options).ifEmpty { uiString(R.string.ui_send_options) })
                        }
                    }
                }
                if (sendFailed) Text(uiString(R.string.ui_contact_card_send_failed), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                HorizontalDivider(color = LocalMessagingColors.current.divider)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !sending, onClick = onDismiss) { Text(uiString(R.string.ui_cancel)) }
                    if (contact != null) {
                        TextButton(enabled = !sending, onClick = { selected = null; sendFailed = false }) {
                            Text(uiString(R.string.ui_contact_back))
                        }
                        Button(enabled = !sending, onClick = {
                            sending = true
                            sendFailed = false
                            scope.launch {
                                try {
                                    if (send(contact.uuid, cardName(contact.alias), options)) onDismiss()
                                    else sendFailed = true
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    sendFailed = true
                                } finally { sending = false }
                            }
                        }) { Text(uiString(if (sending) R.string.ui_receipt_sending else R.string.ui_send)) }
                    }
                }
            }
        }
    }
    if (choosingOptions) SendOptionsDialog(options, onDismiss = { choosingOptions = false }, onConfirm = {
        options = it
        choosingOptions = false
    })
}

@Composable
internal fun ContactCardDetails(
    name: String,
    account: String,
    myUuid: String,
    isCurrent: suspend () -> Boolean,
    onOpenChat: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val client = org.msgat.MsgATApp.instance.nettyClient
    val scope = rememberCoroutineScope()
    val changes by client.contacts.changes.collectAsState()
    var entry by remember(account) { mutableStateOf<ContactEntry?>(null) }
    var loaded by remember(account) { mutableStateOf(false) }
    var sending by remember(account) { mutableStateOf(false) }
    var queued by remember(account) { mutableStateOf(false) }
    var readError by remember(account) { mutableStateOf<Int?>(null) }
    var requestFailed by remember(account) { mutableStateOf(false) }
    var retry by remember(account) { mutableIntStateOf(0) }
    LaunchedEffect(account, changes, retry) {
        readError = null
        loaded = false
        try {
            if (!isCurrent()) {
                readError = R.string.ui_contact_card_unavailable
                return@LaunchedEffect
            }
            entry = client.contacts.list(account).firstOrNull { it.id == account }
            loaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readError = R.string.ui_contact_load_failed
        }
    }
    val contact = entry
    val canOpen = loaded && contact?.accepted == true && !contact.blocked && !contact.revoked
    val canRequest = loaded && !queued && account != myUuid && contact?.accepted != true &&
        contact?.incoming != true && contact?.outgoing != true && contact?.blocked != true && contact?.revoked != true
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loaded) SelectionContainer { Text(account, style = MaterialTheme.typography.bodySmall) }
                if (loaded) {
                    val status = when {
                        account == myUuid -> R.string.ui_contact_public_identity
                        contact?.revoked == true -> R.string.ui_contact_identity_reset
                        contact?.blocked == true -> R.string.ui_contact_blocked
                        contact?.accepted == true -> R.string.ui_contact_accepted
                        contact?.outgoing == true || queued -> R.string.ui_contact_pending
                        contact?.incoming == true -> R.string.ui_contact_incoming
                        else -> R.string.ui_contact_not_connected
                    }
                    Text(uiString(status), style = MaterialTheme.typography.labelMedium)
                }
                if (!loaded && readError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (val error = readError) {
                    R.string.ui_contact_load_failed -> ContactCardReadError(error) { retry++ }
                    null -> if (requestFailed) Text(uiString(R.string.ui_contact_action_failed), color = MaterialTheme.colorScheme.error)
                    else -> Text(uiString(error), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (canOpen) TextButton(enabled = !sending, onClick = { onDismiss(); onOpenChat(account) }) {
                Text(uiString(R.string.ui_contact_open_chat))
            }
            else if (canRequest) TextButton(enabled = !sending, onClick = {
                sending = true
                requestFailed = false
                scope.launch {
                    try {
                        if (!isCurrent()) {
                            loaded = false
                            readError = R.string.ui_contact_card_unavailable
                            return@launch
                        }
                        queued = client.secureMessaging.requestContact(account)
                        requestFailed = !queued
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        requestFailed = true
                    } finally { sending = false }
                }
            }) { Text(uiString(if (sending) R.string.ui_receipt_sending else R.string.ui_contact_send_request)) }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text(uiString(R.string.ui_close)) } }
    )
}

@Composable
private fun ContactCardReadError(message: Int, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(uiString(message), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = onRetry) { Text(uiString(R.string.ui_retry)) }
    }
}

private fun cardName(alias: String): String {
    val result = StringBuilder()
    var remaining = 256
    for (codePoint in alias.trim().codePoints().toArray()) {
        if (Character.isISOControl(codePoint)) continue
        val character = String(Character.toChars(codePoint))
        remaining -= character.toByteArray(Charsets.UTF_8).size
        if (remaining < 0) break
        result.append(character)
    }
    return result.toString().trim().ifEmpty { "User" }
}
