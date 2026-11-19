// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.msgat.R
import org.msgat.core.network.SendOptions
import org.msgat.data.db.Conversation
import org.msgat.ui.chat.ChatActionSheet
import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString
import java.text.BreakIterator

data class ForwardMessageRequest(val sourcePeer: String?, val ids: List<String>, val onCommitted: () -> Unit = {})

private const val maxForwardRecipients = 20

@Composable
fun ForwardMessagesDialog(
    count: Int,
    loadRecipients: suspend () -> List<Conversation>,
    onForward: suspend (List<String>, Boolean, Boolean, SendOptions, Boolean) -> Boolean,
    onDismiss: () -> Unit,
    onCommitted: () -> Unit
) {
    var contacts by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var selected by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var hideSender by remember { mutableStateOf(false) }
    var hideCaption by remember { mutableStateOf(false) }
    var omitPreview by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(SendOptions()) }
    var showOptions by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var sendFailed by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var queued by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheet = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss by rememberUpdatedState(onDismiss)
    val commit by rememberUpdatedState(onCommitted)
    val ready = !busy && !closing

    LaunchedEffect(reload) {
        loading = true
        loadFailed = false
        try {
            contacts = loadRecipients()
            val current = contacts.associateBy(Conversation::uuid)
            selected = selected.mapNotNull { current[it.uuid] }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        } finally { loading = false }
    }
    LaunchedEffect(closing, sheet.isIdle, sheet.currentState) {
        if (closing && sheet.isIdle && !sheet.currentState) {
            if (queued) commit() else dismiss()
        }
    }

    fun close() {
        closing = true
        sheet.targetState = false
    }

    fun toggle(peer: Conversation) {
        if (!ready) return
        selected = if (selected.any { it.uuid == peer.uuid }) selected.filterNot { it.uuid == peer.uuid }
            else if (selected.size < maxForwardRecipients) selected + peer else selected
        sendFailed = false
    }

    val term = filter.trim()
    val visible = remember(contacts, term) {
        if (term.isEmpty()) contacts else contacts.filter { it.alias.contains(term, true) || it.uuid.contains(term, true) }
    }
    val chosenIds = remember(selected) { selected.mapTo(HashSet()) { it.uuid } }
    val selectedScroll = rememberLazyListState()
    LaunchedEffect(selected.lastOrNull()?.uuid) {
        if (selected.isNotEmpty()) selectedScroll.scrollToItem(selected.lastIndex)
    }
    val messagingColors = LocalMessagingColors.current
    ChatActionSheet(sheet, uiString(R.string.ui_close), onDismiss = ::close, dismissEnabled = ready) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Text(if (count == 1) uiString(R.string.ui_forward_message) else uiString(R.string.ui_forward_messages_count, count),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            val searchLabel = uiString(R.string.ui_find_recipient)
            OutlinedTextField(filter, { filter = it }, placeholder = { Text(searchLabel) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (filter.isEmpty()) null else {
                    { IconButton(onClick = { filter = "" }, enabled = ready) { Icon(Icons.Default.Close, uiString(R.string.ui_search_clear)) } }
                }, singleLine = true, enabled = ready, shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { contentDescription = searchLabel })
            if (selected.isNotEmpty()) {
                LazyRow(state = selectedScroll, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(selected, key = { it.uuid }) { peer ->
                        InputChip(selected = true, onClick = { toggle(peer) }, enabled = ready,
                            colors = InputChipDefaults.inputChipColors(selectedContainerColor = messagingColors.selection),
                            label = { Text(peer.alias, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp)) },
                            avatar = { ForwardAvatar(peer, Modifier.size(24.dp)) },
                            trailingIcon = { Icon(Icons.Default.Close, uiString(R.string.ui_forward_remove_recipient, peer.alias), Modifier.size(16.dp)) })
                    }
                }
            }
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (loadFailed || visible.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (!loading) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                        Text(uiString(when {
                            loadFailed -> R.string.ui_forward_recipients_failed
                            contacts.isEmpty() -> R.string.ui_forward_no_recipients
                            else -> R.string.ui_forward_no_matches
                        }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (loadFailed) TextButton(onClick = { reload++ }, enabled = ready) { Text(uiString(R.string.ui_retry)) }
                    }
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(78.dp), modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    items(visible, key = { it.uuid }) { peer ->
                        val chosen = peer.uuid in chosenIds
                        ForwardRecipientTile(peer, chosen, ready && (chosen || selected.size < maxForwardRecipients)) { toggle(peer) }
                    }
                }
            }
            if (sendFailed) {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    color = messagingColors.timeline, shape = MaterialTheme.shapes.small) {
                    Text(uiString(R.string.ui_forward_queue_failed), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(9.dp))
                }
            }
            HorizontalDivider(color = messagingColors.divider)
            val optionSummary = sendOptionsLabel(options)
            if (optionSummary.isNotEmpty()) Text(optionSummary, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    IconButton(onClick = { showMenu = true }, enabled = ready) { Icon(Icons.Default.MoreVert, uiString(R.string.ui_forward_options)) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text(uiString(R.string.ui_forward_hide_sender)) },
                            leadingIcon = { Checkbox(hideSender, onCheckedChange = null) },
                            onClick = { hideSender = !hideSender; showMenu = false })
                        DropdownMenuItem(text = { Text(uiString(R.string.ui_forward_hide_caption)) },
                            leadingIcon = { Checkbox(hideCaption, onCheckedChange = null) },
                            onClick = { hideCaption = !hideCaption; showMenu = false })
                        DropdownMenuItem(text = { Text(uiString(R.string.ui_forward_omit_preview)) },
                            leadingIcon = { Checkbox(omitPreview, onCheckedChange = null) },
                            onClick = { omitPreview = !omitPreview; showMenu = false })
                        DropdownMenuItem(text = { Text(uiString(R.string.ui_send_options)) },
                            onClick = { showMenu = false; showOptions = true })
                    }
                }
                Text(uiString(R.string.ui_forward_recipient_count, selected.size, maxForwardRecipients),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 2)
                Button(enabled = ready && !loading && !loadFailed && selected.isNotEmpty(), onClick = {
                    val targets = selected.map(Conversation::uuid)
                    val withoutSender = hideSender
                    val withoutCaption = hideCaption
                    val sendOptions = options
                    val withoutPreview = omitPreview
                    busy = true
                    sendFailed = false
                    scope.launch {
                        try {
                            if (onForward(targets, withoutSender, withoutCaption, sendOptions, withoutPreview)) {
                                queued = true
                                close()
                            } else sendFailed = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) { sendFailed = true }
                        finally { busy = false }
                    }
                }) {
                    if (!busy) Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, Modifier.size(17.dp))
                    if (!busy) Spacer(Modifier.width(6.dp))
                    Text(uiString(if (busy) R.string.ui_forward_task_queuing else R.string.ui_forward_action))
                }
            }
        }
    }
    if (showOptions && ready) SendOptionsDialog(options, onDismiss = { showOptions = false }, onConfirm = {
        options = it
        showOptions = false
    })
}

@Composable
private fun ForwardRecipientTile(peer: Conversation, chosen: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val messagingColors = LocalMessagingColors.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().heightIn(min = 88.dp).clip(shape)
        .background(if (chosen) messagingColors.selection else messagingColors.timeline)
        .toggleable(chosen, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
        .padding(horizontal = 4.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp)) {
            ForwardAvatar(peer, Modifier.size(50.dp).align(Alignment.TopCenter)
                .then(if (chosen) Modifier.border(2.dp, colors.primary, CircleShape) else Modifier))
            if (chosen) Box(Modifier.align(Alignment.BottomEnd).size(19.dp).background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = colors.onPrimary)
            }
        }
        Text(peer.alias, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ForwardAvatar(peer: Conversation, modifier: Modifier) {
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        val initial = remember(peer.alias) {
            val boundary = BreakIterator.getCharacterInstance().apply { setText(peer.alias) }.next()
            if (boundary > 0) peer.alias.substring(0, boundary).uppercase() else ""
        }
        Text(initial, color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelMedium)
        if (peer.avatarPath.isNotBlank()) Image(rememberAvatarPainter(peer.avatarPath), null,
            Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}
