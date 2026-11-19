// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.msgat.R
import org.msgat.core.call.CallHistory
import org.msgat.core.call.CallHistoryEntry
import org.msgat.core.call.CallOutcome
import org.msgat.core.network.SecureMessaging
import org.msgat.ui.theme.uiString
import org.msgat.ui.theme.interfaceLocale
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CallHistoryDialog(messaging: SecureMessaging, onClose: () -> Unit,
                               onChat: (CallHistoryEntry) -> Unit, onCall: (CallHistoryEntry) -> Unit) {
    val scope = rememberCoroutineScope()
    val locale = interfaceLocale()
    var missedOnly by remember { mutableStateOf(false) }
    val listState = key(missedOnly) { rememberLazyListState() }
    var entries by remember { mutableStateOf<List<CallHistoryEntry>>(emptyList()) }
    var hasOlder by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var retryOlder by remember { mutableStateOf(false) }
    var showingRemoval by remember { mutableStateOf(false) }
    var removalEntry by remember { mutableStateOf<CallHistoryEntry?>(null) }
    var removing by remember { mutableStateOf(false) }
    var removalFailed by remember { mutableStateOf(false) }
    val removeEntryLabel = uiString(R.string.ui_call_history_remove_entry)
    suspend fun load(older: Boolean) {
        if (loading) return
        loading = true
        failed = false
        try {
            val count = if (older) 40 else maxOf(40, entries.size)
            val page = messaging.callHistory(entries.lastOrNull().takeIf { older }, count, missedOnly)
            entries = (if (older) entries else emptyList()) + page.take(count)
            hasOlder = page.size > count
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { retryOlder = older; failed = true }
        finally { loading = false }
    }
    LaunchedEffect(messaging) {
        CallHistory.revision.collect {
            snapshotFlow { loading }.first { !it }
            load(false)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false,
        securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(uiString(R.string.ui_call_history), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (!missedOnly) {
                        IconButton(enabled = !loading && !removing && entries.isNotEmpty(), onClick = {
                            removalEntry = null
                            showingRemoval = true
                        }) {
                            Icon(Icons.Default.DeleteOutline, uiString(R.string.ui_call_history_clear_all))
                        }
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, uiString(R.string.ui_close)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (missed in listOf(false, true)) {
                        FilterChip(
                            selected = missedOnly == missed,
                            enabled = !loading && !removing,
                            label = { Text(uiString(if (missed) R.string.ui_call_history_missed else R.string.ui_call_history_all)) },
                            onClick = {
                                if (missedOnly != missed && !loading) {
                                    missedOnly = missed
                                    entries = emptyList()
                                    hasOlder = false
                                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { load(false) }
                                }
                            }
                        )
                    }
                }
                if (loading && entries.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (removalFailed) {
                    Text(uiString(R.string.ui_call_history_remove_failed), color = MaterialTheme.colorScheme.error)
                }
                if (failed) {
                    Text(uiString(R.string.ui_call_history_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { scope.launch { load(retryOlder) } }) { Text(uiString(R.string.ui_call_history_retry)) }
                } else if (!loading && entries.isEmpty()) {
                    Text(uiString(if (missedOnly) R.string.ui_call_history_missed_empty else R.string.ui_call_history_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                    items(entries, key = { it.peer + "/" + it.callId }) { row ->
                        val callLabel = uiString(if (row.video) R.string.ui_video_call else R.string.ui_voice_call)
                        val tone = if (row.outcome == CallOutcome.Missed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        val time = remember(row.endedAt, locale) {
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(row.endedAt))
                        }
                        val direction = uiString(if (row.outgoing) R.string.ui_call_outgoing else R.string.ui_call_incoming)
                        val outcome = uiString(when (row.outcome) {
                            CallOutcome.Completed -> R.string.ui_call_completed
                            CallOutcome.Missed -> R.string.ui_call_missed
                            CallOutcome.Declined -> R.string.ui_call_declined
                            CallOutcome.Busy -> R.string.ui_call_busy
                            CallOutcome.Canceled -> R.string.ui_call_canceled
                            CallOutcome.Failed -> R.string.ui_call_failed
                        })
                        val status = if (row.outcome == CallOutcome.Completed)
                            "$direction · $outcome · ${android.text.format.DateUtils.formatElapsedTime(row.duration)}"
                        else "$direction · $outcome"
                        ListItem(
                            headlineContent = { Text(row.alias, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                Box(
                                    Modifier.size(42.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .clearAndSetSemantics {},
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(row.alias.trim().take(1).uppercase(locale),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    if (row.avatarPath.isNotBlank()) {
                                        Image(rememberAvatarPainter(row.avatarPath), null, Modifier.matchParentSize(),
                                            contentScale = ContentScale.Crop)
                                    }
                                }
                            },
                            supportingContent = {
                                Column {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (row.outgoing) Icons.Default.CallMade else Icons.Default.CallReceived,
                                            null, Modifier.size(15.dp), tint = tone)
                                        Text(status, color = tone, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(time, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            trailingContent = {
                                IconButton(enabled = !removing, onClick = { onCall(row) }) {
                                    Icon(if (row.video) Icons.Default.Videocam else Icons.Default.Call, callLabel)
                                }
                            },
                            modifier = Modifier.combinedClickable(
                                enabled = !removing,
                                onClick = { onChat(row) },
                                onLongClickLabel = removeEntryLabel,
                                onLongClick = {
                                    removalEntry = row
                                    showingRemoval = true
                                }
                            )
                        )
                        HorizontalDivider()
                    }
                    if (hasOlder) item {
                        TextButton(enabled = !loading && !removing, onClick = { scope.launch { load(true) } }) {
                            Text(uiString(R.string.ui_call_history_older))
                        }
                    }
                }
            }
        }
    }
    if (showingRemoval) {
        AlertDialog(
            onDismissRequest = { showingRemoval = false },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = {
                Text(uiString(if (removalEntry == null) R.string.ui_call_history_clear_all
                    else R.string.ui_call_history_remove_entry))
            },
            text = { Text(uiString(R.string.ui_call_history_remove_local)) },
            confirmButton = {
                TextButton(onClick = {
                    val entry = removalEntry
                    showingRemoval = false
                    removing = true
                    removalFailed = false
                    scope.launch {
                        try {
                            removalFailed = !messaging.removeCallHistory(entry)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            removalFailed = true
                        } finally {
                            removing = false
                        }
                    }
                }) { Text(uiString(R.string.ui_local_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showingRemoval = false }) { Text(uiString(R.string.ui_cancel)) }
            }
        )
    }
}
