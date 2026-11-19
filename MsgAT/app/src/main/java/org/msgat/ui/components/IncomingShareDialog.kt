// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.msgat.R
import org.msgat.data.db.Conversation
import org.msgat.ui.theme.uiString

internal const val MAX_SHARED_ATTACHMENTS = 10
internal const val MAX_SHARED_TEXT_BYTES = 48 * 1024

internal class IncomingShare(
    val files: List<Uri>,
    val text: String?,
    val asFile: Boolean,
    val canImportStickers: Boolean
)

@Composable
internal fun IncomingShareDialog(
    share: IncomingShare,
    loadRecipients: suspend () -> List<Conversation>,
    onSelect: suspend (String) -> Boolean,
    onImportStickers: () -> Unit,
    onDismiss: () -> Unit
) {
    var contacts by remember { mutableStateOf<List<Conversation>?>(null) }
    var filter by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canSend = share.files.size <= MAX_SHARED_ATTACHMENTS

    LaunchedEffect(retry, canSend) {
        if (!canSend) return@LaunchedEffect
        loadFailed = false
        try { contacts = loadRecipients() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
    }
    Dialog(onDismissRequest = { if (!opening) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.safeDrawingPadding().widthIn(max = 460.dp).fillMaxWidth(.94f),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(uiString(R.string.ui_share_to_chat), style = MaterialTheme.typography.titleLarge)
                if (canSend) {
                    OutlinedTextField(filter, { filter = it }, enabled = !opening, singleLine = true,
                        label = { Text(uiString(R.string.ui_find_recipient)) }, modifier = Modifier.fillMaxWidth())
                    val visible = contacts.orEmpty().filter { it.alias.contains(filter.trim(), ignoreCase = true) }
                    when {
                        loadFailed -> {
                            Text(uiString(R.string.ui_forward_recipients_failed))
                            TextButton(onClick = { retry++ }) { Text(uiString(R.string.ui_retry)) }
                        }
                        contacts == null || opening -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        visible.isEmpty() -> Text(uiString(if (contacts.orEmpty().isEmpty())
                            R.string.ui_forward_no_recipients else R.string.ui_forward_no_matches))
                    }
                    if (openFailed) Text(uiString(R.string.ui_shared_chat_unavailable), color = MaterialTheme.colorScheme.error)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(visible, key = { it.uuid }) { peer ->
                            TextButton(enabled = !opening, modifier = Modifier.fillMaxWidth(), onClick = {
                                opening = true
                                openFailed = false
                                scope.launch {
                                    try { openFailed = !onSelect(peer.uuid) }
                                    finally { opening = false }
                                }
                            }) { Text(peer.alias, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                } else Text(uiString(R.string.ui_attachment_limit))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (share.canImportStickers) TextButton(enabled = !opening, onClick = onImportStickers) {
                        Text(uiString(R.string.ui_import_stickers))
                    }
                    TextButton(enabled = !opening, onClick = onDismiss) { Text(uiString(R.string.ui_cancel)) }
                }
            }
        }
    }
}
