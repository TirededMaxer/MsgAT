// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import org.msgat.R
import org.msgat.data.db.*
import org.msgat.ui.theme.interfaceLocale
import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

internal data class MessageSearchInput(
    val query: String = "",
    val all: Boolean,
    val kind: MessageSearchKind = MessageSearchKind.All,
    val selectedMessage: String? = null,
    val loadedCount: Int = 0,
    val day: LocalDate? = null,
    val selectedIndex: Int? = null
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessageSearchDialog(
    database: AppDatabase, store: MessageSearchStore, peer: String?,
    initial: MessageSearchInput = MessageSearchInput(all = peer == null),
    openingResult: Boolean,
    openError: Int?,
    onCriteriaChanged: () -> Unit,
    onDismiss: (MessageSearchInput) -> Unit,
    onSelect: (MessageSearchHit, MessageSearchInput) -> Unit
) {
    var field by remember(peer) { mutableStateOf(TextFieldValue(initial.query, TextRange(initial.query.length))) }
    val query = field.text
    var all by remember(peer) { mutableStateOf(peer == null || initial.all) }
    var kind by remember(peer) { mutableStateOf(initial.kind) }
    var day by remember(peer) { mutableStateOf(initial.day) }
    var choosingDate by remember(peer) { mutableStateOf(false) }
    val input = MessageSearchInput(query, all, kind, day = day)
    val needle = query.trim()
    val resume = remember(peer) { initial.copy(all = peer == null || initial.all) }
    var restorePending by remember(peer) { mutableStateOf(true) }
    var hits by remember(input, peer) { mutableStateOf<List<MessageSearchHit>>(emptyList()) }
    var next by remember(input, peer) { mutableStateOf<MessageSearchCursor?>(null) }
    var loading by remember(input, peer) { mutableStateOf(input.query.isNotBlank()) }
    var failed by remember(input, peer) { mutableStateOf(false) }
    var retryOlder by remember(input, peer) { mutableStateOf(false) }
    var selected by remember(input, peer) { mutableStateOf<String?>(null) }
    var advanceAfterPage by remember(input, peer) { mutableStateOf<String?>(null) }
    val invalidations = remember(database) { Channel<Unit>(Channel.CONFLATED) }
    val pagination = remember(input, peer) { Channel<Unit>(Channel.CONFLATED) }
    val retry = remember(input, peer) { Channel<Unit>(Channel.CONFLATED) }
    val resultList = remember(input, peer) { LazyListState() }
    val resumeCriteria = resume.copy(selectedMessage = null, loadedCount = 0, selectedIndex = null)
    fun snapshot(selectedMessage: String?): MessageSearchInput {
        if (restorePending && hits.isEmpty() && input == resumeCriteria) return resume
        return input.copy(selectedMessage = selectedMessage, loadedCount = hits.size,
            selectedIndex = hits.indexOfFirst { it.messageId == selectedMessage }.takeIf { it >= 0 })
    }
    val close = { onDismiss(snapshot(selected)) }
    val criteriaChanged by rememberUpdatedState(onCriteriaChanged)
    LaunchedEffect(input) { criteriaChanged() }
    val dateContext = LocalContext.current
    val dateTitle = uiString(R.string.ui_date_label)
    val applyDate = uiString(R.string.ui_apply)
    val cancelDate = uiString(R.string.ui_cancel)
    val dateLocale = interfaceLocale()
    val dateFormat = remember(dateLocale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(dateLocale) }
    if (choosingDate) {
        DisposableEffect(dateContext) {
            val initialDate = day ?: LocalDate.now()
            val picker = android.app.DatePickerDialog(dateContext, null, initialDate.year,
                initialDate.monthValue - 1, initialDate.dayOfMonth)
            picker.setTitle(dateTitle)
            picker.setButton(android.content.DialogInterface.BUTTON_POSITIVE, applyDate,
                null as android.content.DialogInterface.OnClickListener?)
            picker.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, cancelDate,
                null as android.content.DialogInterface.OnClickListener?)
            picker.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            picker.setOnDismissListener { choosingDate = false }
            picker.show()
            picker.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val date = picker.datePicker
                date.clearFocus()
                val chosen = LocalDate.of(date.year, date.month + 1, date.dayOfMonth)
                if (day != chosen) {
                    restorePending = false
                    day = chosen
                }
                picker.dismiss()
            }
            onDispose {
                picker.setOnDismissListener(null)
                picker.dismiss()
            }
        }
    }
    val selectedIndex = hits.indexOfFirst { it.messageId == selected }
    fun openSelection() {
        if (openingResult) return
        val hit = hits.getOrNull(selectedIndex) ?: return
        onSelect(hit, snapshot(hit.messageId))
    }
    fun moveSelection(delta: Int) {
        if (openingResult || hits.isEmpty()) return
        val index = hits.indexOfFirst { it.messageId == selected }
        val destination = if (index < 0) if (delta > 0) 0 else hits.lastIndex else index + delta
        if (destination in hits.indices) {
            advanceAfterPage = null
            selected = hits[destination].messageId
        } else if (destination == hits.size && next != null && !loading) {
            advanceAfterPage = selected
            pagination.trySend(Unit)
        }
    }
    LaunchedEffect(selected, selectedIndex, resultList) {
        val layout = resultList.layoutInfo
        val visible = layout.visibleItemsInfo.any { row ->
            row.key == selected && row.offset >= layout.viewportStartOffset &&
                (row.offset + row.size <= layout.viewportEndOffset || row.size >= layout.viewportSize.height)
        }
        if (selectedIndex >= 0 && !visible) {
            resultList.scrollToItem(selectedIndex)
        }
    }
    DisposableEffect(database) {
        val observer=object: InvalidationTracker.Observer("messages", "conversations") {
            override fun onInvalidated(tables: Set<String>) { invalidations.trySend(Unit) }
        }
        database.invalidationTracker.addObserver(observer)
        onDispose {database.invalidationTracker.removeObserver(observer)}
    }
    LaunchedEffect(input, peer, store) {
        if (input.query.isBlank()) return@LaunchedEffect
        val target = if (input.all) null else peer
        var refreshPending = false
        suspend fun load(older: Boolean) {
            val cursor = if (older) next ?: return else null
            val restoring = !older && restorePending && input == resumeCriteria
            val limit = if (older) 50 else maxOf(50, hits.size, if (restoring) resume.loadedCount else 0)
            loading = true
            failed = false
            try {
                val page = store.search(input.query, target, cursor, input.kind, limit, day = input.day)
                hits = if (older) (hits + page.hits).distinctBy { it.messageId } else page.hits
                next = page.next
                if (restoring) selected = hits.firstOrNull { it.messageId == resume.selectedMessage }?.messageId
                if (hits.none { it.messageId == selected }) selected = hits.firstOrNull()?.messageId
                if (older) {
                    val anchor = advanceAfterPage
                    advanceAfterPage = null
                    if (anchor != null && selected == anchor) {
                        val index = hits.indexOfFirst { it.messageId == anchor }
                        if (index >= 0) hits.getOrNull(index + 1)?.let { selected = it.messageId }
                    }
                }
                restorePending = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                retryOlder = older
                failed = true
            } finally {
                loading = false
            }
        }
        delay(250)
        invalidations.tryReceive()
        load(older = false)
        while (isActive) {
            val refresh = select<Boolean?> {
                pagination.onReceive { true }
                invalidations.onReceive { false }
                retry.onReceive { null }
            }
            if (refresh == false) {
                delay(250)
                invalidations.tryReceive()
            }
            if (refresh == false && failed && retryOlder) {
                refreshPending = true
                continue
            }
            load(refresh ?: retryOlder)
            if (refreshPending && !failed) {
                refreshPending = false
                load(older = false)
            }
        }
    }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn)) {
        val colors = LocalMessagingColors.current
        Surface(modifier = Modifier.fillMaxSize(), color = colors.chrome, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp)) {
                val fieldFocus = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                val title = uiString(R.string.ui_search_messages)
                Text(title, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = field, onValueChange = {
                        if (it.text != query) restorePending = false
                        field = it
                    }, label = { Text(title) }, singleLine = true,
                    enabled = !openingResult,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(fieldFocus).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || field.composition != null ||
                            event.isAltPressed || event.isCtrlPressed || event.isMetaPressed || event.isShiftPressed) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> { moveSelection(-1); true }
                            Key.DirectionDown -> { moveSelection(1); true }
                            Key.Enter, Key.NumPadEnter -> {
                                openSelection()
                                true
                            }
                            else -> false
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(enabled = !openingResult, onClick = {
                                restorePending = false
                                field = TextFieldValue("")
                                fieldFocus.requestFocus()
                                keyboard?.show()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = uiString(R.string.ui_search_clear))
                            }
                        }
                    } else null
                )
                LaunchedEffect(fieldFocus) { fieldFocus.requestFocus() }
                if (peer != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(all, { restorePending = false; all = it }, enabled = !openingResult)
                        Text(uiString(R.string.ui_search_all_conversations))
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MessageSearchKind.entries.forEach { option ->
                        FilterChip(selected = kind == option, enabled = !openingResult,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.selection), onClick = {
                            if (kind != option) restorePending = false
                            kind = option
                        }, label = {
                            Text(uiString(when (option) {
                                MessageSearchKind.All -> R.string.ui_search_all_types
                                MessageSearchKind.Text -> R.string.ui_search_text_type
                                MessageSearchKind.Attachments -> R.string.ui_attachments
                            }))
                        })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = day != null,
                        enabled = !openingResult,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.selection),
                        onClick = { keyboard?.hide(); choosingDate = true },
                        leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                        label = { Text(day?.format(dateFormat) ?: dateTitle) }
                    )
                    if (day != null) {
                        IconButton(enabled = !openingResult, onClick = { restorePending = false; day = null }) {
                            Icon(Icons.Default.Close, contentDescription = uiString(R.string.ui_search_clear_date))
                        }
                    }
                }
                if (openingResult || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(uiString(R.string.ui_search_failed), color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f))
                        TextButton(enabled = !openingResult, onClick = { retry.trySend(Unit) }) { Text(uiString(R.string.ui_retry)) }
                    }
                }
                openError?.let {
                    Text(uiString(it), Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = resultList,
                    contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(hits,key={it.messageId}) {hit ->
                        val isSelected = selected == hit.messageId
                        val highlight = if (isSelected) colors.selection else colors.timeline
                        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(highlight)
                            .clickable(enabled = !openingResult) {
                                advanceAfterPage = null
                                selected = hit.messageId
                                onSelect(hit, snapshot(hit.messageId))
                            }
                            .semantics { this.selected = isSelected }.padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(hit.alias, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall)
                                Text(remember(hit.timestamp) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(hit.timestamp)) },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (hit.isAttachment) Text(uiString(R.string.ui_search_attachment), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                            Text(remember(hit.preview, needle) { highlightedPreview(hit.preview, needle) }, maxLines = 3,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (!loading && hits.isEmpty() && needle.isNotEmpty() && !failed) Text(uiString(R.string.ui_search_no_results))
                HorizontalDivider(color = colors.divider)
                FlowRow(Modifier.fillMaxWidth(),
                    horizontalArrangement = if (hits.isEmpty()) Arrangement.End else Arrangement.SpaceBetween) {
                    if (hits.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { moveSelection(-1) }, enabled = selectedIndex > 0 && !openingResult) {
                                Icon(Icons.Default.KeyboardArrowUp, uiString(R.string.ui_search_previous_result))
                            }
                            IconButton(onClick = { moveSelection(1) },
                                enabled = !openingResult && (selectedIndex < hits.lastIndex || next != null && !loading)) {
                                Icon(Icons.Default.KeyboardArrowDown, uiString(R.string.ui_search_next_result))
                            }
                            Text(uiString(R.string.ui_search_result_position, selectedIndex + 1, hits.size),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Row {
                        if (selectedIndex >= 0) Button(enabled = !openingResult, onClick = ::openSelection) {
                            Text(uiString(R.string.ui_open))
                        }
                        TextButton(onClick = close) { Text(uiString(R.string.ui_close)) }
                    }
                }
            }
        }
    }
}

private fun highlightedPreview(preview: String, needle: String) = buildAnnotatedString {
    var start = 0
    if (needle.isNotEmpty()) {
        var match = preview.indexOf(needle, startIndex = start, ignoreCase = true)
        while (match >= 0) {
            append(preview.substring(start, match))
            start = match + needle.length
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(preview.substring(match, start)) }
            match = preview.indexOf(needle, startIndex = start, ignoreCase = true)
        }
    }
    append(preview.substring(start))
}
