// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlin.math.floor
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.msgat.R
import org.msgat.ui.theme.uiString
import org.msgat.data.db.ConversationFolder
import org.msgat.ui.sessionlist.ConversationUIModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationFoldersDialog(folders:List<ConversationFolder>,conversations:List<ConversationUIModel>,onSave:suspend(ConversationFolder,Set<String>)->Boolean,onDelete:suspend(String)->Boolean,onMove:suspend(String,String,Boolean)->Boolean,onDismiss:()->Unit){
    val scope=rememberCoroutineScope()
    var selected by remember{mutableStateOf<String?>(null)}
    var name by remember{mutableStateOf("")}
    var includeAll by remember{mutableStateOf(false)}
    var unreadOnly by remember{mutableStateOf(false)}
    var excludeMuted by remember{mutableStateOf(false)}
    var includeArchived by remember{mutableStateOf(false)}
    var members by remember{mutableStateOf<Set<String>>(emptySet())}
    var memberSearch by remember { mutableStateOf("") }
    val visibleMembers = remember(conversations, memberSearch) {
        val query = memberSearch.trim()
        if (query.isEmpty()) conversations else conversations.filter { it.alias.contains(query, ignoreCase = true) }
    }
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var deleting by remember{mutableStateOf<String?>(null)}
    data class Draft(val name: String, val all: Boolean, val unread: Boolean, val muted: Boolean,
                     val archived: Boolean, val members: Set<String>)
    fun draft() = Draft(name.trim(), includeAll, unreadOnly, excludeMuted, includeArchived, members)
    var baseline by remember { mutableStateOf(draft()) }
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    val updateFailed = uiString(R.string.ui_folder_update_failed)
    fun choose(folder: ConversationFolder?) {
        if (folder != null && folder.id == selected) return
        selected = folder?.id
        name = folder?.name.orEmpty()
        includeAll = folder?.includeAll ?: false
        unreadOnly = folder?.unreadOnly ?: false
        excludeMuted = folder?.excludeMuted ?: false
        includeArchived = folder?.includeArchived ?: false
        memberSearch = ""
        members = folder?.let { group -> conversations.filter { group.id in it.folderIDs.split(',') }.map { it.uuid }.toSet() }.orEmpty()
        baseline = draft()
        error = null
    }
    fun leave(action: () -> Unit) {
        if (busy || pendingExit != null) return
        if (draft() != baseline) pendingExit = action else action()
    }
    fun work(action: suspend () -> Boolean, done: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { if (action()) done() else error = updateFailed }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = updateFailed }
            finally { busy = false }
        }
    }
    pendingExit?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingExit = null },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(uiString(R.string.ui_discard_message_edit)) },
            confirmButton = { TextButton(onClick = { pendingExit = null; action() }) { Text(uiString(R.string.ui_discard)) } },
            dismissButton = { TextButton(onClick = { pendingExit = null }) { Text(uiString(R.string.ui_keep_editing)) } }
        )
    }
    deleting?.let { id ->
        ConfirmDialog(uiString(R.string.ui_folder_delete_confirm), onDismiss = { deleting = null },
            onConfirm = { deleting = null; work({ onDelete(id) }) { choose(null) } })
    }
    Dialog(onDismissRequest = { leave(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.safeDrawingPadding().imePadding().fillMaxWidth(.96f).fillMaxHeight(.9f),shape=MaterialTheme.shapes.large){
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text(uiString(R.string.ui_chat_folders),style=MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FolderOrderList(folders, selected, enabled = !busy, onSelect = { folder ->
                            if (folder == null || folder.id != selected) leave { choose(folder) }
                        }) { id, targetId, before -> work({ onMove(id, targetId, before) }) }
                    }
                    item {
                        OutlinedTextField(name,{name=it},label={Text(uiString(R.string.ui_folder_name))},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                    }
                    item {
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(includeAll,{includeAll=it},enabled=!busy);Text(uiString(R.string.ui_folder_include_all))}
                        FlowRow {
                            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(unreadOnly,{unreadOnly=it},enabled=!busy);Text(uiString(R.string.ui_folder_unread_only))}
                            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(excludeMuted,{excludeMuted=it},enabled=!busy);Text(uiString(R.string.ui_folder_exclude_muted))}
                            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(includeArchived,{includeArchived=it},enabled=!busy);Text(uiString(R.string.ui_folder_include_archived))}
                        }
                    }
                    item {
                        OutlinedTextField(
                            memberSearch, { memberSearch = it },
                            label = { Text(uiString(R.string.ui_folder_search_members)) },
                            singleLine = true, enabled = !busy && !includeAll, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    items(visibleMembers,key={it.uuid}){row->Row(Modifier.fillMaxWidth().clickable(enabled=!busy && !includeAll){members=if(row.uuid in members)members-row.uuid else members+row.uuid}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(row.uuid in members,{members=if(it)members+row.uuid else members-row.uuid},enabled=!busy && !includeAll);Text(row.alias,maxLines=1)}}
                }
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    selected?.let { id ->
                        val index = folders.indexOfFirst { it.id == id }
                        val above = folders.getOrNull(index - 1)
                        val below = if (index < 0) null else folders.getOrNull(index + 1)
                        TextButton(enabled = !busy && above != null, onClick = {
                            above?.let { work({ onMove(id, it.id, true) }) }
                        }) { Text(uiString(R.string.ui_folder_move_up)) }
                        TextButton(enabled = !busy && below != null, onClick = {
                            below?.let { work({ onMove(id, it.id, false) }) }
                        }) { Text(uiString(R.string.ui_folder_move_down)) }
                        TextButton(enabled = !busy, onClick = { deleting = id }) { Text(uiString(R.string.ui_folder_delete)) }
                    }
                    TextButton(enabled=!busy,onClick={leave(onDismiss)}){Text(uiString(R.string.ui_close))}
                    Button(enabled=!busy && name.isNotBlank() && name.trim().toByteArray().size <= 128 && !name.contains(0.toChar()),onClick={val id=selected?:java.util.UUID.randomUUID().toString();val folder=ConversationFolder(id,name,includeAll=includeAll,unreadOnly=unreadOnly,excludeMuted=excludeMuted,includeArchived=includeArchived);val peers=members.toSet();work({onSave(folder,peers)}){selected=id;baseline=draft()}}){Text(uiString(R.string.ui_save))}
                }
            }
        }
    }
}


@Composable
private fun FolderOrderList(
    folders: List<ConversationFolder>,
    selected: String?,
    enabled: Boolean,
    onSelect: (ConversationFolder?) -> Unit,
    onMove: (String, String, Boolean) -> Unit
) {
    val scroll = rememberScrollState()
    val rowHeight = 44.dp
    val rowPixels = with(LocalDensity.current) { rowHeight.roundToPx().toFloat() }
    val edgePixels = with(LocalDensity.current) { 26.dp.toPx() }
    val latestFolders by rememberUpdatedState(folders)
    val submitMove by rememberUpdatedState(onMove)
    var draggedId by remember { mutableStateOf<String?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }

    fun targetIndex(): Int {
        if (latestFolders.isEmpty()) return -1
        return floor((fingerY + scroll.value) / rowPixels).toInt().coerceIn(latestFolders.indices)
    }

    LaunchedEffect(draggedId, enabled) {
        if (draggedId == null || !enabled) return@LaunchedEffect
        while (true) {
            val viewport = scroll.viewportSize.toFloat()
            val delta = when {
                fingerY < edgePixels -> -((edgePixels - fingerY) / edgePixels).coerceAtMost(1f) * 12f
                fingerY > viewport - edgePixels -> ((fingerY - viewport + edgePixels) / edgePixels).coerceAtMost(1f) * 12f
                else -> 0f
            }
            if (delta != 0f) scroll.scrollBy(delta)
            delay(16)
        }
    }

    Column {
        TextButton(enabled = enabled, onClick = { onSelect(null) }) { Text(uiString(R.string.ui_folder_new)) }
        Column(Modifier.fillMaxWidth().heightIn(max = 132.dp).verticalScroll(scroll)) {
            val source = folders.indexOfFirst { it.id == draggedId }
            val target = if (source < 0) -1 else targetIndex()
            for ((index, folder) in folders.withIndex()) {
                key(folder.id) {
                    val active = selected == folder.id
                    val showInsertion = index == target && source != target
                    Box(Modifier.fillMaxWidth().height(rowHeight)) {
                        Row(
                            modifier = Modifier.fillMaxSize().background(
                                if (active || folder.id == draggedId) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.weight(1f).fillMaxHeight()
                                .clickable(enabled = enabled) { onSelect(folder) }
                                .padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val drag = if (enabled && folders.size > 1) {
                                Modifier.pointerInput(folder.id, rowPixels) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { position ->
                                            val current = latestFolders.indexOfFirst { it.id == folder.id }
                                            if (current >= 0) {
                                                fingerY = current * rowPixels + position.y - scroll.value
                                                draggedId = folder.id
                                            }
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            if (draggedId == folder.id) fingerY += delta.y
                                        },
                                        onDragCancel = { draggedId = null },
                                        onDragEnd = {
                                            val current = latestFolders.indexOfFirst { it.id == folder.id }
                                            val destination = targetIndex()
                                            val id = draggedId
                                            draggedId = null
                                            if (id == folder.id && current >= 0 && destination >= 0 && current != destination) {
                                                submitMove(folder.id, latestFolders[destination].id, destination < current)
                                            }
                                        }
                                    )
                                }
                            } else Modifier
                            Box(Modifier.width(48.dp).fillMaxHeight().then(drag), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.DragHandle, contentDescription = uiString(R.string.ui_folder_drag, folder.name))
                            }
                        }
                        if (showInsertion) {
                            HorizontalDivider(
                                modifier = Modifier.align(if (target < source) Alignment.TopCenter else Alignment.BottomCenter),
                                thickness = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
