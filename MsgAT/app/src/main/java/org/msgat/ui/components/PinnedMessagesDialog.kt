// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.msgat.R
import org.msgat.data.db.MessagePinsStore
import org.msgat.data.db.PinnedMessageItem
import org.msgat.ui.theme.interfaceLocale
import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PinnedMessagesDialog(peer: String, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val app = org.msgat.MsgATApp.instance
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val locale = interfaceLocale()
    val date = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
    var requestedOffset by remember(peer) { mutableIntStateOf(0) }
    var displayedOffset by remember(peer) { mutableIntStateOf(0) }
    var refresh by remember(peer) { mutableIntStateOf(0) }
    var rows by remember(peer) { mutableStateOf<List<PinnedMessageItem>>(emptyList()) }
    var loading by remember(peer) { mutableStateOf(true) }
    var busy by remember(peer) { mutableStateOf(false) }
    var hasMore by remember(peer) { mutableStateOf(false) }
    var loadFailed by remember(peer) { mutableStateOf(false) }
    var changeFailed by remember(peer) { mutableStateOf(false) }
    var openError by remember(peer) { mutableStateOf<Int?>(null) }
    var confirmingUnpin by remember(peer) { mutableStateOf<String?>(null) }
    fun load(offset: Int) {
        loading = true
        requestedOffset = offset
        refresh++
    }
    LaunchedEffect(peer, requestedOffset, refresh) {
        val offset = requestedOffset
        loading = true
        try {
            app.db.msgDao().observePinnedMessages(MessagePinsStore.query(peer, app.securePrefs.myUuid, offset)).collect {
                if (requestedOffset != offset) return@collect
                if (it.isEmpty() && offset > 0) {
                    load(maxOf(0, offset - 50))
                } else {
                    if (offset != displayedOffset && it.isNotEmpty()) listState.requestScrollToItem(0)
                    rows = it.take(50)
                    hasMore = it.size > 50
                    displayedOffset = offset
                    loadFailed = false
                    loading = false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
            loading = false
        }
    }
    fun unpin(id: String) {
        if (busy || loading) return
        busy = true
        changeFailed = false
        scope.launch {
            try {
                changeFailed = !app.nettyClient.secureMessaging.setMessagePinned(peer, id, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                changeFailed = true
            } finally {
                busy = false
            }
        }
    }
    fun open(id: String) {
        if (busy || loading) return
        busy = true
        openError = null
        scope.launch {
            try {
                if (app.db.msgDao().hasHistoryAnchor(peer, id, includeRevoked = false)) onOpen(id)
                else openError = R.string.ui_search_target_unavailable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                openError = R.string.ui_history_load_failed
            } finally {
                busy = false
            }
        }
    }
    val colors = LocalMessagingColors.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        containerColor = colors.chrome,
        title = { Text(uiString(R.string.ui_pinned_messages)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (loadFailed) Text(uiString(R.string.ui_pins_load_failed), color = MaterialTheme.colorScheme.error)
                if (changeFailed) Text(uiString(R.string.ui_pin_change_failed), color = MaterialTheme.colorScheme.error)
                openError?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error) }
                if (!loading && !loadFailed && rows.isEmpty()) Text(uiString(R.string.ui_pins_empty))
                LazyColumn(Modifier.heightIn(max = 420.dp), state = listState) {
                    items(rows, key = { it.id }) { pin ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Column(Modifier.weight(1f).clickable(enabled = !busy && !loading) { open(pin.id) }) {
                                org.msgat.ui.chat.MessageSummaryText(pin.summary, maxLines = 3)
                                Text(date.format(Date(pin.timestamp)), style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(enabled = !busy && !loading, onClick = { confirmingUnpin = pin.id }) {
                                Text(uiString(R.string.ui_unpin_message))
                            }
                        }
                        HorizontalDivider(color = colors.divider)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loadFailed) TextButton(enabled = !busy && !loading, onClick = { load(requestedOffset) }) {
                        Text(uiString(R.string.ui_retry))
                    }
                    TextButton(enabled = displayedOffset > 0 && !loading && !busy,
                        onClick = { load(maxOf(0, displayedOffset - 50)) }) {
                        Text(uiString(R.string.ui_queue_previous))
                    }
                    TextButton(enabled = hasMore && !loading && !busy, onClick = { load(displayedOffset + 50) }) {
                        Text(uiString(R.string.ui_queue_next))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(uiString(R.string.ui_close)) } }
    )
    confirmingUnpin?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmingUnpin = null },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(uiString(R.string.ui_unpin_message)) },
            text = { Text(uiString(R.string.ui_unpin_for_both)) },
            confirmButton = {
                TextButton(enabled = !busy && !loading, onClick = {
                    confirmingUnpin = null
                    unpin(id)
                }) { Text(uiString(R.string.ui_unpin_message)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnpin = null }) { Text(uiString(R.string.ui_cancel)) }
            }
        )
    }
}
