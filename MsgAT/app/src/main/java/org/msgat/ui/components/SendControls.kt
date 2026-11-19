// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import androidx.compose.foundation.background

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.msgat.core.network.SendOptions
import org.msgat.core.network.SendQueueEntry
import org.msgat.core.network.SecureMessaging
import org.msgat.core.network.ScheduledMessageDraft
import org.msgat.R
import org.msgat.ui.chat.SyncState
import org.msgat.ui.theme.interfaceLocale
import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
private fun ScheduledMessageEditor(row: SendQueueEntry, original: ScheduledMessageDraft, onDismiss: () -> Unit,
                                   onSave: suspend (String) -> Boolean) {
    var value by remember(row.id) { mutableStateOf(original.text) }
    var saving by remember(row.id) { mutableStateOf(false) }
    var failed by remember(row.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val editorFocus = remember(row.id) { FocusRequester() }
    val tooLong = value.toByteArray(Charsets.UTF_8).size > 48 * 1024
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(uiString(if (original.isCaption) R.string.ui_editing_caption else R.string.ui_editing_message)) },
        text = {
            LaunchedEffect(row.id) {
                withFrameNanos { }
                editorFocus.requestFocus()
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sendOptionsLabel(SendOptions(row.sendAt, row.silent)), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = value, onValueChange = { value = it; failed = false },
                    modifier = Modifier.fillMaxWidth().focusRequester(editorFocus),
                    enabled = !saving, minLines = 4, maxLines = 8, isError = tooLong)
                if (failed || tooLong) Text(uiString(when {
                    !tooLong -> R.string.ui_queue_stale
                    original.isCaption -> R.string.ui_caption_too_long
                    else -> R.string.ui_queue_text_too_long
                }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(uiString(R.string.ui_cancel)) } },
        confirmButton = {
            TextButton(enabled = !saving && !tooLong && value != original.text && (original.isCaption || value.isNotBlank()),
                onClick = {
                    if (saving) return@TextButton
                    saving = true
                    val replacement = value
                    scope.launch {
                        try {
                            if (onSave(replacement)) onDismiss() else failed = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            saving = false
                        }
                    }
                }) { Text(uiString(R.string.ui_save)) }
        }
    )
}

@Composable
internal fun sendOptionsLabel(value: SendOptions): String {
    val silent = if (value.silent) uiString(R.string.ui_silent) else null
    val scheduled = value.sendAt?.let {
        val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(interfaceLocale())
            .format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
        uiString(R.string.ui_scheduled_time, date)
    }
    return listOfNotNull(silent, scheduled).joinToString(" · ")
}

@Composable
internal fun SendOptionsDialog(initial:SendOptions,timeOnly:Boolean=false,startScheduled:Boolean=false,onDismiss:()->Unit,onConfirm:(SendOptions)->Unit){
    val context=LocalContext.current
    val locale = interfaceLocale()
    val zone=remember{ZoneId.systemDefault()}
    var silent by remember {mutableStateOf(initial.silent)}
    var scheduled by remember {mutableStateOf(timeOnly || startScheduled || initial.sendAt!=null)}
    var local by remember {mutableStateOf(initial.sendAt?.let{Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime()}?:LocalDateTime.now(zone).plusMinutes(5).withSecond(0).withNano(0))}
    var error by remember {mutableStateOf<Int?>(null)}
    AlertDialog(onDismissRequest=onDismiss,properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),
        title={Text(uiString(if(timeOnly) R.string.ui_change_scheduled_time else R.string.ui_send_options))},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(!timeOnly){
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(silent,{silent=it});Text(uiString(R.string.ui_send_without_sound))}
                if (!startScheduled) Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(scheduled,{scheduled=it});Text(uiString(R.string.ui_schedule_next_message))}
                Text(uiString(R.string.ui_schedule_connected),style=MaterialTheme.typography.bodySmall)
            }
            if(scheduled){
                OutlinedButton(onClick={
                    val picker=DatePickerDialog(context,{_,year,month,day->local=LocalDateTime.of(year,month+1,day,local.hour,local.minute);error=null},local.year,local.monthValue-1,local.dayOfMonth)
                    picker.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);picker.show()
                }){
                    val dateLabel = uiString(R.string.ui_date_label)
                    val date = local.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                    Text("$dateLabel · $date")
                }
                OutlinedButton(onClick={
                    val picker=TimePickerDialog(context,{_,hour,minute->local=local.withHour(hour).withMinute(minute).withSecond(0).withNano(0);error=null},local.hour,local.minute,true)
                    picker.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);picker.show()
                }){
                    val timeLabel = uiString(R.string.ui_time_label)
                    val time = local.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm", locale))
                    Text("$timeLabel · $time")
                }
                Text(zone.id,style=MaterialTheme.typography.bodySmall)
            }
            error?.let{Text(uiString(it),color=MaterialTheme.colorScheme.error)}
        }},dismissButton={TextButton(onClick=onDismiss){Text(uiString(R.string.ui_cancel))}},confirmButton={TextButton(onClick={
            val due=if(scheduled){
                val offsets=zone.rules.getValidOffsets(local)
                if(offsets.isEmpty()){error=R.string.ui_schedule_missing_time;return@TextButton}
                local.toInstant(offsets.maxBy{it.totalSeconds}).toEpochMilli()
            }else null
            if(due!=null && (due<=System.currentTimeMillis() || due>253402214399999L)){error=R.string.ui_choose_future;return@TextButton}
            onConfirm(SendOptions(due,silent))
        }){Text(uiString(if (startScheduled) R.string.ui_schedule_next_message else R.string.ui_apply))}})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SendQueueDialog(
    messaging: SecureMessaging,
    connectionState: StateFlow<SyncState>,
    peer: String? = null,
    onOpenChat: (suspend (String, String?) -> Boolean)? = null,
    onDismiss: () -> Unit
) {
    val scope=rememberCoroutineScope()
    var rows by remember{mutableStateOf<List<SendQueueEntry>>(emptyList())}
    val connection by connectionState.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rows) {
        while (true) {
            val current = System.currentTimeMillis()
            now = current
            val next = rows.asSequence().filter { it.state != "failed" }
                .mapNotNull { it.sendAt ?: it.relayRetryAt }.filter { it > current }.minOrNull() ?: break
            delay((next - current).coerceIn(1L, 60_000L))
        }
    }
    var offset by remember{mutableIntStateOf(0)}
    var scheduled by remember { mutableStateOf<Boolean?>(null) }
    var hasNextPage by remember { mutableStateOf(false) }
    var loading by remember{mutableStateOf(true)}
    var busy by remember{mutableStateOf(false)}
    var loadFailed by remember { mutableStateOf(false) }
    var failedPage by remember { mutableStateOf<Pair<Int, Boolean?>?>(null) }
    var actionError by remember { mutableStateOf<Int?>(null) }
    val reloads = remember(messaging, peer) { Channel<Unit>(Channel.CONFLATED) }
    val listState = rememberLazyListState()
    var cancel by remember{mutableStateOf<SendQueueEntry?>(null)}
    var reschedule by remember{mutableStateOf<SendQueueEntry?>(null)}
    var edit by remember(peer) { mutableStateOf<Pair<SendQueueEntry, ScheduledMessageDraft>?>(null) }
    fun requestPage(page: Int, filter: Boolean?) {
        loading = true
        failedPage = null
        loadFailed = false
        offset = page
        scheduled = filter
        reloads.trySend(Unit)
    }
    fun openEdit(row: SendQueueEntry) {
        val at = row.sendAt ?: return
        if (busy || loading || !row.canEdit) return
        busy = true
        actionError = null
        scope.launch {
            try {
                val draft = messaging.scheduledMessageDraft(row.id, row.peer, at)
                if (draft != null) edit = row to draft
                else actionError = R.string.ui_queue_stale
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionError = R.string.ui_queue_change
            } finally {
                busy = false
                if (edit == null) requestPage(offset, scheduled)
            }
        }
    }
    fun open(row: SendQueueEntry) {
        val navigate = onOpenChat ?: return
        if (busy || loading) return
        busy = true
        actionError = null
        scope.launch {
            try {
                val message = row.id.takeIf { row.sendAt == null }
                if (navigate(row.peer, message)) onDismiss()
                else actionError = R.string.ui_queue_stale
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionError = R.string.ui_history_load_failed
            } finally {
                busy = false
                requestPage(offset, scheduled)
            }
        }
    }
    fun change(row:SendQueueEntry,action:String,at:Long?=null){
        if (busy) return
        if (rows.none { it.id == row.id }) {
            actionError = R.string.ui_queue_stale
            requestPage(offset, scheduled)
            return
        }
        busy = true
        actionError = null
        scope.launch {
            try {
                if (!messaging.manageSend(row.id, action, at, expectedPeer = row.peer, expectedScheduledAt = row.sendAt)) {
                    actionError = R.string.ui_queue_stale
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionError = R.string.ui_queue_change
            } finally {
                busy = false
                requestPage(offset, scheduled)
            }
        }
    }
    LaunchedEffect(messaging, peer, reloads) {
        var displayedOffset = 0
        var displayedFilter: Boolean? = null
        reloads.trySend(Unit)
        for (request in reloads) {
            if (busy || cancel != null || reschedule != null || edit != null || failedPage != null) continue
            loading = true
            val requestedPage = offset to scheduled
            try {
                var page = offset
                var result = messaging.sendQueue(peer, page, scheduled = scheduled, limit = 51)
                while (result.isEmpty() && page > 0) {
                    page = maxOf(0, page - 50)
                    result = messaging.sendQueue(peer, page, scheduled = scheduled, limit = 51)
                }
                if ((page != displayedOffset || scheduled != displayedFilter) && result.isNotEmpty())
                    listState.requestScrollToItem(0)
                hasNextPage = result.size > 50
                rows = result.take(50)
                offset = page
                displayedOffset = page
                displayedFilter = scheduled
                loadFailed = false
                failedPage = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                offset = displayedOffset
                scheduled = displayedFilter
                failedPage = requestedPage
                loadFailed = true
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(messaging, reloads) {
        messaging.sendQueueChanges.conflate().collect { reloads.trySend(Unit); delay(150) }
    }
    LaunchedEffect(cancel, reschedule, edit) {
        if (cancel == null && reschedule == null && edit == null) reloads.trySend(Unit)
    }
    cancel?.let{row->AlertDialog(onDismissRequest={cancel=null},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),title={Text(uiString(R.string.ui_queue_cancel_title))},text={Text(uiString(R.string.ui_queue_cancel_body))},dismissButton={TextButton(onClick={cancel=null}){Text(uiString(R.string.ui_queue_keep))}},confirmButton={TextButton(onClick={cancel=null;change(row,"cancel")}){Text(uiString(R.string.ui_queue_cancel))}})}
    reschedule?.let{row->SendOptionsDialog(SendOptions(row.sendAt,row.silent),timeOnly=true,onDismiss={reschedule=null},onConfirm={reschedule=null;change(row,"reschedule",it.sendAt)})}
    edit?.let { (row, draft) ->
        ScheduledMessageEditor(row, draft, onDismiss = { edit = null }) { replacement ->
            messaging.editScheduledMessage(row.id, row.peer, checkNotNull(row.sendAt), draft, replacement)
        }
    }
    val maxHeight=LocalConfiguration.current.screenHeightDp.dp*0.85f
    Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn)){
        val colors = LocalMessagingColors.current
        Surface(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).widthIn(max=760.dp).fillMaxWidth(0.94f).heightIn(max=maxHeight),
            shape = MaterialTheme.shapes.large, color = colors.chrome, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(uiString(R.string.ui_sending_queue),style=MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in listOf(null to R.string.ui_queue_filter_all,
                        true to R.string.ui_queue_filter_scheduled, false to R.string.ui_queue_filter_pending)) {
                        FilterChip(selected = scheduled == value, enabled = !loading && !busy,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.selection), onClick = {
                            if (scheduled != value) {
                                actionError = null
                                requestPage(0, value)
                            }
                        }, label = { Text(uiString(label)) })
                    }
                }
                actionError?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error) }
                if (loadFailed) Text(uiString(R.string.ui_queue_loading), color = MaterialTheme.colorScheme.error)
                if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f,false), state = listState, verticalArrangement=Arrangement.spacedBy(12.dp)){
                    if(rows.isEmpty() && !loading && !loadFailed)item{Text(uiString(R.string.ui_queue_empty))}
                    items(rows,key={it.id}){row->
                        Column(Modifier.fillMaxWidth().background(colors.timeline, MaterialTheme.shapes.medium).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            org.msgat.ui.chat.MessageSummaryText(row.summary,maxLines=3)
                            if (peer == null) {
                                Text(
                                    row.peerAlias?.takeIf { it.isNotBlank() } ?: row.peer.take(12),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            val stateLabel = when {
                                row.state == "failed" -> uiString(when (row.failure) {
                                    "recipient" -> R.string.ui_queue_recipient_unavailable
                                    "media" -> R.string.ui_queue_media_unavailable
                                    else -> R.string.ui_queue_attention
                                })
                                row.relayRetryAt?.let { it > now } == true -> uiString(R.string.ui_queue_relay_full)
                                row.state == "sending" -> uiString(R.string.ui_queue_unconfirmed)
                                else -> when {
                                    row.sendAt == null -> uiString(R.string.ui_queue_waiting)
                                    row.sendAt > now -> ""
                                    connection != SyncState.SYNCED -> uiString(R.string.ui_queue_waiting_connection)
                                    else -> uiString(R.string.ui_queue_waiting)
                                }
                            }
                            val optionsLabel = sendOptionsLabel(SendOptions(row.sendAt, row.silent))
                            Text(listOf(stateLabel, optionsLabel).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (row.state == "failed") MaterialTheme.colorScheme.error else LocalContentColor.current)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                if (onOpenChat != null && (peer == null || row.sendAt == null)) {
                                    TextButton(enabled = !busy && !loading, onClick = { open(row) }) {
                                        Text(uiString(if (row.sendAt == null) R.string.ui_show_in_chat else R.string.ui_contact_open_chat))
                                    }
                                }
                                if (row.canEdit) TextButton(enabled = !busy && !loading, onClick = { openEdit(row) }) {
                                    Text(uiString(R.string.ui_queue_edit))
                                }
                                if(row.sendAt!=null && row.failure==null){TextButton(enabled=!busy && !loading,onClick={change(row,"send_now")}){Text(uiString(R.string.ui_queue_now))};TextButton(enabled=!busy && !loading,onClick={reschedule=row}){Text(uiString(R.string.ui_change_scheduled_time))}}
                                if(row.canRetry)TextButton(enabled=!busy && !loading,onClick={change(row,"retry")}){Text(uiString(R.string.ui_retry))}
                                if(row.canCancel)TextButton(enabled=!busy && !loading,onClick={cancel=row}){Text(uiString(R.string.ui_cancel))}
                            }
                        }
                    }
                }
                Text(uiString(R.string.ui_queue_online), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = colors.divider)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    TextButton(enabled = !busy && !loading, onClick = {
                        actionError = null
                        val (page, filter) = failedPage ?: (offset to scheduled)
                        requestPage(page, filter)
                    }) { Text(uiString(if (loadFailed) R.string.ui_retry else R.string.ui_queue_refresh)) }
                    TextButton(enabled=!busy && !loading && offset>0,onClick={
                        requestPage(maxOf(0, offset - 50), scheduled)
                    }){Text(uiString(R.string.ui_queue_previous))}
                    TextButton(enabled=!busy && !loading && hasNextPage,onClick={
                        requestPage(offset + 50, scheduled)
                    }){Text(uiString(R.string.ui_queue_next))}
                    TextButton(enabled=!busy,onClick=onDismiss){Text(uiString(R.string.ui_close))}
                }
            }
        }
    }
}


@Composable
internal fun PreparationRows(rows:List<org.msgat.core.media.PendingPreparation>,onCancel:(String)->Unit){
    Column(Modifier.fillMaxWidth().heightIn(max=170.dp).verticalScroll(rememberScrollState()).padding(horizontal=12.dp)){
        rows.forEach{row->
            val progress=row.progress
            Row(verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f).padding(vertical=6.dp)){
                    val label = when {
                        row.savingMessages -> uiString(R.string.ui_saved_messages)
                        row.isSticker -> uiString(R.string.ui_stickers)
                        row.isLinkPreview -> uiString(R.string.ui_link_preview_cover)
                        row.fileCount > 1 -> uiString(R.string.ui_preparation_batch, row.fileCount)
                        else -> uiString(R.string.ui_search_attachment)
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    val fraction = progress.total.takeIf { it > 0 }?.let { total ->
                        (progress.completed.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
                    }
                    val status = when (progress.phase) {
                        "canceling" -> uiString(R.string.ui_preparation_canceling)
                        "committing" -> uiString(if (row.savingMessages) R.string.ui_saving else R.string.ui_preparation_queuing)
                        "finished" -> uiString(R.string.ui_preparation_finishing)
                        else -> when {
                            progress.step == org.msgat.core.media.PreparationStep.ENCODING_PHOTO ->
                                uiString(R.string.ui_preparation_photo_waiting)
                            progress.step == org.msgat.core.media.PreparationStep.ENCODING_VIDEO && fraction != null ->
                                uiString(R.string.ui_preparation_video_progress, (fraction * 100).toInt())
                            progress.step == org.msgat.core.media.PreparationStep.ENCODING_VIDEO -> uiString(R.string.ui_preparation_video_waiting)
                            fraction != null -> uiString(R.string.ui_preparation_progress, (fraction * 100).toInt())
                            else -> uiString(R.string.ui_preparation_waiting)
                        }
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    val fileCount = if (row.savingMessages) progress.attachmentCount else row.fileCount
                    if (fileCount > 1 && progress.phase == "preparing") {
                        Text(uiString(R.string.ui_preparation_attachment, progress.attachment, fileCount),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if(fraction != null && progress.phase=="preparing")LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (progress.canCancel) IconButton(onClick = { onCancel(row.id) }) {
                    Icon(Icons.Default.Close, contentDescription = uiString(R.string.ui_preparation_cancel))
                }
            }
        }
    }
}
