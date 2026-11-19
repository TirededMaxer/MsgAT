// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import androidx.compose.foundation.background

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.msgat.R
import org.msgat.core.media.PreparationProgress
import org.msgat.core.network.ForwardTaskInfo
import org.msgat.core.network.ForwardTaskQueue
import org.msgat.ui.theme.interfaceLocale
import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ForwardTasksDialog(
    queue: ForwardTaskQueue,
    onOpenOriginal: suspend (String, String) -> Boolean,
    onDismiss: () -> Unit
) {
    val scope=rememberCoroutineScope()
    val revision by queue.revision.collectAsState()
    var rows by remember {mutableStateOf<List<ForwardTaskInfo>>(emptyList())}
    var selected by remember {mutableStateOf<String?>(null)}
    var loading by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<Int?>(null)}
    var downloadQueued by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var cancelTarget by remember {mutableStateOf<ForwardTaskInfo?>(null)}
    val row=rows.firstOrNull {it.id==selected}
    LaunchedEffect(revision, busy, refresh, cancelTarget) {
        if (busy || cancelTarget != null) return@LaunchedEffect
        loading=true
        try {rows=queue.list();if(rows.none {it.id==selected})selected=null;loadFailed=false}
        catch(cancelled: CancellationException){throw cancelled}
        catch(_: Exception){loadFailed=true}
        finally {loading=false}
    }
    val locale = interfaceLocale()
    val dateFormat = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
    fun change(id: String, cancel: Boolean) {
        if (busy) return
        busy=true;error=null;cancelTarget=null;downloadQueued=false
        scope.launch {
            try {if(!(if(cancel)queue.cancel(id) else queue.retry(id)))error=R.string.ui_forward_task_changed}
            catch(cancelled: CancellationException){throw cancelled}
            catch(_: Exception){error=R.string.ui_forward_task_change_failed}
            finally {busy=false}
        }
    }
    Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn,dismissOnBackPress=!busy,dismissOnClickOutside=!busy)) {
        val colors = LocalMessagingColors.current
        Surface(Modifier.fillMaxSize(), color = colors.chrome, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Text(uiString(R.string.ui_forwarding_queue),style=MaterialTheme.typography.titleLarge)
                if(loading || busy)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp))
                error?.let {Text(uiString(it),color=MaterialTheme.colorScheme.error)}
                if (downloadQueued) Text(uiString(R.string.ui_download_queued))
                if (loadFailed) Text(uiString(R.string.ui_forward_task_load_failed), color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.weight(1f)) {
                    items(rows,key={it.id}) {item ->
                        Row(Modifier.fillMaxWidth()
                            .background(if (selected == item.id) colors.selection else colors.chrome)
                            .clickable(enabled = !busy && !loading) { selected = item.id }
                            .padding(vertical = 12.dp)) {
                            RadioButton(selected==item.id,{selected=item.id},enabled=!busy && !loading)
                            Column(Modifier.weight(1f)) {
                                Text(uiString(R.string.ui_forward_task_summary,item.messageCount,item.recipients.size),style=MaterialTheme.typography.titleSmall)
                                if(item.recipients.isNotEmpty()) {
                                    val unavailable = uiString(R.string.ui_forward_task_unknown_recipient)
                                    Text(item.recipients.joinToString(" · ") {it ?: unavailable},style=MaterialTheme.typography.bodySmall)
                                }
                                if(item.state=="preparing")ForwardTaskProgress(queue,item)
                                else if (item.state == "waiting" && item.pendingMediaMessages > 0) {
                                    Text(uiString(R.string.ui_forward_task_waiting_media, item.pendingMediaMessages))
                                }
                                else Text(uiString(forwardTaskStatus(item)))
                                val options = sendOptionsLabel(item.options)
                                if (options.isNotEmpty()) Text(options, style = MaterialTheme.typography.bodySmall)
                                Text(dateFormat.format(Date(item.createdAt)), style=MaterialTheme.typography.labelSmall)
                            }
                        }
                        HorizontalDivider(color = colors.divider)
                    }
                }
                if(!loading && !loadFailed && rows.isEmpty())Text(uiString(R.string.ui_forward_task_empty))
                HorizontalDivider(color = colors.divider)
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(enabled = !busy && !loading, onClick = { error = null; downloadQueued = false; refresh++ }) {
                        Text(uiString(if (loadFailed) R.string.ui_retry else R.string.ui_queue_refresh))
                    }
                    val peer = row?.sourcePeer
                    val message = row?.waitingMessageId
                    if (row != null && peer != null && message != null) {
                        TextButton(enabled = !busy && !loading, onClick = {
                            val id = row.id
                            busy = true
                            error = null
                            downloadQueued = false
                            scope.launch {
                                try {
                                    downloadQueued = queue.requestMissingDownloads(id)
                                    if (!downloadQueued) error = R.string.ui_forward_task_download_unavailable
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = R.string.ui_forward_task_change_failed
                                } finally { busy = false }
                            }
                        }) { Text(uiString(R.string.ui_forward_task_download)) }
                        TextButton(enabled = !busy && !loading, onClick = {
                            busy = true
                            error = null
                            downloadQueued = false
                            scope.launch {
                                try {
                                    if (!onOpenOriginal(peer, message)) error = R.string.ui_history_message_unavailable
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = R.string.ui_history_load_failed
                                } finally { busy = false }
                            }
                        }) { Text(uiString(R.string.ui_forward_task_open_source)) }
                    }
                    if (row?.errorCode != "source_changed") {
                        TextButton(enabled = !busy && !loading && row?.state == "failed",
                            onClick = { row?.let { change(it.id, false) } }) { Text(uiString(R.string.ui_retry)) }
                    }
                    TextButton(enabled=!busy && !loading && row!=null,onClick={cancelTarget=row}){
                        Text(uiString(if(row?.state=="failed")R.string.ui_forward_task_remove else R.string.ui_forward_task_cancel))
                    }
                    TextButton(enabled=!busy,onClick=onDismiss){Text(uiString(R.string.ui_close))}
                }
            }
        }
    }
    cancelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(uiString(if (target.state == "failed") R.string.ui_forward_task_remove_confirm else R.string.ui_forward_task_cancel_confirm)) },
            confirmButton = { TextButton(onClick = { change(target.id, true) }, enabled = !busy) { Text(uiString(R.string.ui_confirm)) } },
            dismissButton = { TextButton(onClick = { cancelTarget = null }) { Text(uiString(R.string.ui_cancel)) } }
        )
    }
}

@Composable
private fun ForwardTaskProgress(queue: ForwardTaskQueue,row: ForwardTaskInfo) {
    var progress by remember(row.id) {mutableStateOf<PreparationProgress?>(null)}
    LaunchedEffect(queue,row.id) {
        while(isActive) {
            progress=queue.progress(row.id)
            delay(200)
        }
    }
    val value=progress
    val fraction=value?.takeIf {it.total>0}?.let {it.completed.toDouble()/it.total}
    val modifier=Modifier.fillMaxWidth().padding(vertical=4.dp)
    when {
        value?.phase=="committing" -> {
            Text(uiString(R.string.ui_forward_task_queuing))
            LinearProgressIndicator(modifier)
        }
        value!=null && fraction!=null -> {
            val copiesPerRecipient = value.attachmentCount / row.recipients.size.coerceAtLeast(1)
            val recipientIndex = (value.attachment - 1) / copiesPerRecipient.coerceAtLeast(1)
            val recipient = row.recipients.getOrNull(recipientIndex)
                ?: uiString(R.string.ui_forward_task_unknown_recipient)
            val overall = ((value.attachment - 1 + fraction) / value.attachmentCount).coerceIn(0.0, 1.0)
            Text(uiString(R.string.ui_forward_task_copy, recipient, value.attachment, value.attachmentCount, (fraction*100).toInt()))
            LinearProgressIndicator(progress={overall.toFloat()},modifier=modifier)
        }
        else -> {
            Text(uiString(R.string.ui_forward_task_preparing))
            LinearProgressIndicator(modifier)
        }
    }
}

private fun forwardTaskStatus(row: ForwardTaskInfo): Int = when(row.state) {
    "failed" -> when(row.errorCode) {
        "source_changed" -> R.string.ui_forward_task_source_changed
        "recipient_unavailable" -> R.string.ui_forward_task_recipient_unavailable
        "source_download_unavailable" -> R.string.ui_forward_task_source_download_unavailable
        "expired" -> R.string.ui_forward_task_expired
        "insufficient_space" -> R.string.ui_forward_task_no_space
        else -> R.string.ui_forward_task_prepare_failed
    }
    else -> R.string.ui_forward_task_waiting
}
