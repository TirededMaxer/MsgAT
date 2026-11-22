// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.msgat.R
import org.msgat.core.media.AttachmentMetadata
import org.msgat.core.network.NettyClient
import org.msgat.data.db.MessageSearchCursor
import org.msgat.data.db.SavedMessageItem
import org.msgat.data.db.SavedMessageSource
import org.msgat.ui.chat.ConversationMediaViewer
import org.msgat.ui.chat.rememberAttachmentExport
import org.msgat.ui.chat.MessageUIModel
import org.msgat.ui.chat.putMessageOnClipboard
import org.msgat.ui.chat.visibleMessageCopy
import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString

private data class SavedRow(val item: SavedMessageItem,val media: AttachmentMetadata?)

@OptIn(ExperimentalFoundationApi::class,ExperimentalLayoutApi::class)
@Composable
internal fun SavedMessagesDialog(client: NettyClient, myUuid: String, onOpenContactChat: (String) -> Unit,
                                 onDismiss: ()->Unit,onForward: (List<String>,()->Unit)->Unit,onOriginal: (SavedMessageItem)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val hiddenText = uiString(R.string.ui_hidden_message_text)
    val copyFailed = uiString(R.string.ui_message_copy_failed)
    var rows by remember {mutableStateOf<List<SavedRow>>(emptyList())}
    var next by remember {mutableStateOf<MessageSearchCursor?>(null)}
    var selected by remember {mutableStateOf<Set<String>>(emptySet())}
    var sources by remember { mutableStateOf<List<SavedMessageSource>>(emptyList()) }
    var byChat by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<SavedMessageSource?>(null) }
    var filter by remember { mutableStateOf("") }
    var openedCard by remember { mutableStateOf<SavedMessageItem?>(null) }
    var openedLocation by remember { mutableStateOf<SavedMessageItem?>(null) }
    var loading by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var pageFailed by remember { mutableStateOf(false) }
    var failedReset by remember { mutableStateOf(true) }
    var generation by remember {mutableLongStateOf(0)}
    var pageJob by remember { mutableStateOf<Job?>(null) }
    var deleteTarget by remember { mutableStateOf<Set<String>?>(null) }
    var preview by remember { mutableStateOf<SavedRow?>(null) }
    var previewBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    var detail by remember {mutableStateOf<SavedMessageItem?>(null)}
    var jumping by remember { mutableStateOf(false) }
    val search = filter.trim()
    val showingSources = byChat && source == null && search.isEmpty()
    val listState = rememberLazyListState()
    val unavailable = uiString(R.string.ui_saved_attachment_unavailable)
    val loadFailed = uiString(R.string.ui_saved_load_failed)
    val deleteFailed = uiString(R.string.ui_saved_delete_failed)
    val originalUnavailable = uiString(R.string.ui_saved_original_unavailable)
    val exportFailed = uiString(R.string.ui_shared_media_save_failed)
    val attachmentExport = rememberAttachmentExport {
        error = exportFailed
        if (preview != null) android.widget.Toast.makeText(context, exportFailed, android.widget.Toast.LENGTH_SHORT).show()
    }
    val exportBusy = attachmentExport.busy
    val dismissSaved by rememberUpdatedState(onDismiss)
    val showOriginal by rememberUpdatedState(onOriginal)
    val attachmentLabel = uiString(R.string.ui_quote_attachment)
    suspend fun load(reset: Boolean, waitForTyping: Boolean = false, keepVisible: Boolean = false) {
        if (!reset && (loading || busy || next == null)) return
        val version = ++generation
        val peer = source?.peer
        val input = search
        val browseSources = showingSources
        loading = true
        pageFailed = false
        if (reset && !keepVisible) {
            rows = emptyList()
            sources = emptyList()
            next = null
            selected = emptySet()
        }
        try {
            if (waitForTyping && input.isNotEmpty()) delay(180)
            if (browseSources) {
                val loaded = client.actions.sources()
                if (version == generation) sources = loaded
                return
            }
            val page=client.actions.list(if(reset)null else next, peer, input)
            val data = withContext(Dispatchers.IO) {
                page.take(50).map { hit ->
                    val media = hit.reference?.let { reference ->
                        try { client.objects.metadata(reference, hit.id)?.copy(caption = hit.text) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    }
                    SavedRow(hit, media)
                }
            }
            if(version==generation) {
                rows=if(reset)data else rows+data
                if (reset) selected = selected.intersect(data.map { it.item.id }.toSet())
                next=if(page.size>50)data.lastOrNull()?.item?.let {MessageSearchCursor(it.savedAt,it.id)} else null
            }
        } catch(cancelled: CancellationException){throw cancelled}
        catch(_: Exception){if(version==generation){error=loadFailed;pageFailed=true;failedReset=reset}}
        finally {if(version==generation)loading=false}
    }
    LaunchedEffect(search, showingSources, source?.peer) {
        pageJob?.cancel()
        error = null
        load(true, waitForTyping = true)
        listState.scrollToItem(0)
    }
    fun loadOlder() {
        if (loading || busy || next == null) return
        error = null
        pageJob = scope.launch { load(false) }
    }
    fun refreshCurrent() {
        if (loading || busy || jumping) return
        error = null
        pageJob = scope.launch { load(reset = true, keepVisible = true) }
    }
    fun changeScope(newSource: SavedMessageSource?, grouped: Boolean = byChat) {
        if (busy || jumping) return
        source = newSource
        byChat = grouped
        filter = ""
        selected = emptySet()
        error = null
    }
    fun toggle(id: String) {
        if (!busy && !jumping && !loading) selected = if (id in selected) selected - id else if (selected.size < 100) selected + id else selected
    }
    fun close() {
        if (busy || jumping) return
        when {
            preview != null -> { previewBack?.invoke() ?: run { preview = null } }
            selected.isNotEmpty() -> selected = emptySet()
            filter.isNotEmpty() -> filter = ""
            source != null -> changeScope(null)
            else -> dismissSaved()
        }
    }
    fun original(item: SavedMessageItem) {
        if (busy || jumping || loading) return
        jumping = true
        error = null
        fun unavailable() {
            error = originalUnavailable
            if (preview != null) android.widget.Toast.makeText(context, originalUnavailable, android.widget.Toast.LENGTH_SHORT).show()
        }
        scope.launch {
            try {
                if (!client.actions.hasOriginal(item)) unavailable()
                else showOriginal(item)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { unavailable() }
            finally { jumping = false }
        }
    }
    fun exportRow(row: SavedRow) {
        val media = row.media ?: return
        if (row.item.reference != media.reference) return
        attachmentExport.save(media) { client.actions.canExport(row.item) }
    }
    Dialog(onDismissRequest=::close,properties=DialogProperties(usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn,dismissOnBackPress=!busy,dismissOnClickOutside=!busy)) {
        val colors = LocalMessagingColors.current
        Surface(Modifier.fillMaxSize(), color = colors.chrome, contentColor = MaterialTheme.colorScheme.onSurface) {
            Box(Modifier.fillMaxSize()) {
                if (preview == null) Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (source != null) IconButton(onClick = { changeScope(null) }, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, uiString(R.string.ui_saved_view_chats))
                        }
                        Text(if (selected.isNotEmpty()) uiString(R.string.ui_message_selection_count, selected.size)
                            else source?.let { sourceName(it) } ?: uiString(R.string.ui_saved_messages),
                            style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { dismissSaved() }, enabled = !busy && !jumping) { Icon(Icons.Default.Close, uiString(R.string.ui_close)) }
                    }
                    if (source == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!byChat, onClick = { if (byChat) changeScope(null, false) }, enabled = !busy,
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.selection),
                                label = { Text(uiString(R.string.ui_saved_view_messages)) })
                            FilterChip(byChat, onClick = { if (!byChat) changeScope(null, true) }, enabled = !busy,
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.selection),
                                label = { Text(uiString(R.string.ui_saved_view_chats)) })
                        }
                    }
                    OutlinedTextField(filter, { filter = it; error = null }, singleLine = true, enabled = !busy && !jumping,
                        placeholder = { Text(uiString(R.string.ui_saved_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = if (filter.isEmpty()) null else {
                            { IconButton(onClick = { filter = "" }, enabled = !busy) { Icon(Icons.Default.Close, uiString(R.string.ui_search_clear)) } }
                        }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    if(loading || busy || jumping) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            if (showingSources) {
                                items(sources, key = { it.peer }) { entry ->
                                    val label = sourceName(entry)
                                    ListItem(colors = ListItemDefaults.colors(containerColor = colors.chrome),
                                        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text(uiString(R.string.ui_saved_source_count, entry.count)) },
                                        leadingContent = {
                                            if (!entry.avatar.isNullOrEmpty()) androidx.compose.foundation.Image(
                                                rememberAvatarPainter(entry.avatar), null, Modifier.size(40.dp)
                                                    .clip(androidx.compose.foundation.shape.CircleShape),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                            else Icon(Icons.Default.Forum, null, Modifier.size(38.dp))
                                        }, modifier = Modifier.clickable(enabled = !busy && !loading, onClick = { changeScope(entry, true) }))
                                    HorizontalDivider(color = colors.divider)
                                }
                            } else items(rows,key={it.item.id}) {row ->
                                val item=row.item
                                Row(Modifier.fillMaxWidth().combinedClickable(enabled=!busy && !jumping && !loading,onLongClick={toggle(item.id)},onClick={
                                    if(selected.isNotEmpty())toggle(item.id)
                                    else if(item.contactAccount != null) openedCard=item
                                    else if(item.kind=="location") openedLocation=item
                                    else if(item.kind=="txt")detail=item
                                    else if(row.media?.let {it.available && (it.isImage || it.isVideo || it.isAudio)}==true && item.reference!=null) {
                                        preview = row
                                    } else if(row.media?.available==true) exportRow(row)
                                    else error=unavailable
                                }).padding(vertical=10.dp)) {
                                    if(selected.isNotEmpty()) Checkbox(item.id in selected,{toggle(item.id)},enabled=!busy && !jumping && !loading)
                                    Column(Modifier.weight(1f)) {
                                        Text(item.author,style=MaterialTheme.typography.labelLarge)
                                        if(row.media!=null) Text(row.media.filename,style=MaterialTheme.typography.titleSmall)
                                        if(item.contactAccount!=null) Text(uiString(R.string.ui_contact_card),style=MaterialTheme.typography.labelSmall)
                                        if(item.kind=="location" && item.text!="Location") Text(uiString(R.string.ui_location),style=MaterialTheme.typography.labelSmall)
                                        if (item.kind == "txt") {
                                            org.msgat.ui.chat.LinkPreviewMessage(item.id,item.text,saved=true,canOpen=!busy && selected.isEmpty()) {
                                                org.msgat.ui.chat.MessageSummaryText(item.text,maxLines=4)
                                            }
                                        } else {
                                            org.msgat.ui.chat.MessageSummaryText(item.text.ifBlank {if(item.kind=="attachment") attachmentLabel else ""},maxLines=4)
                                        }
                                    }
                                }
                                HorizontalDivider(color = colors.divider)
                            }
                        }
                        if (!loading && !pageFailed && (if (showingSources) sources.isEmpty() else rows.isEmpty() && next == null)) {
                            Text(
                                    uiString(if (search.isEmpty()) R.string.ui_saved_empty else R.string.ui_saved_no_matches),
                                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                )
                        }
                    }
                    HorizontalDivider(color = colors.divider)
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        if (pageFailed) TextButton(enabled = !loading && !busy, onClick = {
                            if (failedReset) refreshCurrent() else loadOlder()
                        }) { Text(uiString(R.string.ui_retry)) }
                        else TextButton(enabled = !loading && !busy && selected.isEmpty(), onClick = ::refreshCurrent) {
                            Text(uiString(R.string.ui_queue_refresh))
                        }
                        if (!pageFailed && next!=null)TextButton(enabled=!loading && !busy,onClick=::loadOlder){Text(uiString(R.string.ui_saved_older))}
                        if(selected.isNotEmpty()) {
                            TextButton(enabled=!busy && !loading,onClick={val snapshot=selected;onForward(snapshot.toList()){if(selected==snapshot)selected=emptySet()}}){Text(uiString(R.string.ui_forward_action))}
                            TextButton(enabled=!busy && !loading,onClick={deleteTarget=selected.toSet()}){Text(uiString(R.string.ui_saved_delete))}
                            TextButton(enabled=!busy,onClick={selected=emptySet()}){Text(uiString(R.string.ui_cancel_message_selection))}
                        }
                    }
                    if(selected.size==1) {
                        val row=rows.firstOrNull {it.item.id in selected}
                        FlowRow {
                            TextButton(enabled=row!=null && !busy && !loading && !jumping,onClick={row?.let {original(it.item)}}){Text(uiString(R.string.ui_show_in_chat))}
                            if(row?.media!=null) TextButton(enabled=!busy && !loading && !exportBusy && row.media.available,onClick={exportRow(row)}){Text(uiString(R.string.ui_save_attachment))}
                            if(row?.item?.text?.isNotEmpty()==true) TextButton(enabled=!busy && !loading,onClick={
                                scope.launch {
                                    try {
                                        val text = visibleMessageCopy(row.item.text, hiddenText)
                                        if (selected.singleOrNull() == row.item.id && rows.any { it.item.id == row.item.id }) {
                                            putMessageOnClipboard(context, text)
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = copyFailed }
                                }
                            }){Text(uiString(R.string.ui_copy_message_text))}
                        }
                    }
                }
                preview?.let { row ->
                    val item = row.item
                    val reference = item.reference ?: return@let
                    ConversationMediaViewer(MessageUIModel(item.id, true, reference, "attachment", item.savedAt, attachment = row.media),
                        progress = null, newer = null, older = null,
                        registerBackAction = { previewBack = it }, onShowMessage = { original(item) },
                        onSaveAttachment = if (exportBusy) null else ({ exportRow(row) }), onDismiss = { preview = null })
                }
            }
        }
    }
    openedCard?.let { card ->
        card.contactAccount?.let { account ->
            ContactCardDetails(card.text, account, myUuid,
                isCurrent = { client.actions.savedContactAccount(card.id) == account }, onOpenChat = onOpenContactChat) {
                openedCard = null
            }
        }
    }
    openedLocation?.let { location ->
        LocationDetailsDialog(location.text, { client.actions.savedLocation(location.id) }) { openedLocation = null }
    }
    detail?.let { item ->
        AlertDialog(
            onDismissRequest = { detail = null },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(uiString(R.string.ui_saved_message)) },
            text = {
                LazyColumn {
                    item {
                        org.msgat.ui.chat.LinkPreviewMessage(item.id, item.text, saved = true) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                org.msgat.ui.chat.RichMessageText(item.text)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text(uiString(R.string.ui_close)) } }
        )
    }
    deleteTarget?.let { target ->
    AlertDialog(onDismissRequest={if(!busy)deleteTarget=null},properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn),
        title={Text(uiString(R.string.ui_saved_delete_confirm))},confirmButton={TextButton(enabled=!busy && !loading,onClick={
        if(busy || loading) return@TextButton
        val ids=target.toList();busy=true;error=null
        scope.launch {
            try {
                client.actions.delete(ids)
                val removed = ids.toSet()
                rows = rows.filterNot { it.item.id in removed }
                selected = selected - removed
                deleteTarget = null
            }
            catch(cancelled: CancellationException){throw cancelled}
            catch(_: Exception){error=deleteFailed;deleteTarget=null}
            finally {busy=false}
        }
    }){Text(uiString(R.string.ui_saved_delete))}},dismissButton={TextButton(enabled=!busy,onClick={deleteTarget=null}){Text(uiString(R.string.ui_cancel))}})
    }
}

@Composable
private fun sourceName(source: SavedMessageSource): String = source.alias ?: uiString(R.string.ui_saved_removed_chat, source.peer.take(8))
