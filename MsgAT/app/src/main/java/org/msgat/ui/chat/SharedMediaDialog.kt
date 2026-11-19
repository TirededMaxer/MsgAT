// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.msgat.MsgATApp
import org.msgat.R
import org.msgat.core.media.PendingPreparation
import org.msgat.core.media.SharedMediaCategory
import org.msgat.core.media.SharedMediaCursor
import org.msgat.core.media.SharedMediaItem
import org.msgat.core.media.SharedMediaPage
import org.msgat.core.media.VisualMediaNeighbors
import org.msgat.core.network.AttachmentProgress
import org.msgat.ui.components.PreparationRows
import org.msgat.ui.theme.uiString
import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.interfaceLocale
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun SharedMediaDialog(
    peer: String,
    progress: Map<String, AttachmentProgress>,
    preparations: List<PendingPreparation>,
    onCancelPreparation: (String) -> Unit,
    onDismiss: () -> Unit,
    onJump: (String) -> Unit,
    onForward: (List<String>) -> Unit,
    onSave: (List<String>, () -> Unit, () -> Unit, () -> Unit, (String) -> Unit) -> Unit,
    initialCategory: SharedMediaCategory = SharedMediaCategory.MEDIA
) {
    val store = MsgATApp.instance.nettyClient.objects
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var category by remember(peer, initialCategory) { mutableStateOf(initialCategory) }
    var searchText by remember(peer) { mutableStateOf("") }
    val query = searchText.trim()
    var dateLimit by remember(peer) { mutableStateOf<java.time.LocalDate?>(null) }
    val firstCursor = dateLimit?.let {
        SharedMediaCursor(it.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), "")
    }
    val categoryPages = remember(peer) { mutableStateMapOf<SharedMediaCategory, List<SharedMediaCursor?>>() }
    val pages = categoryPages[category] ?: listOf(firstCursor)
    var selecting by remember(peer, category, query, dateLimit) { mutableStateOf(false) }
    var selected by remember(peer, category, query, dateLimit) { mutableStateOf<Set<String>>(emptySet()) }
    var saving by remember(peer) { mutableStateOf(false) }
    var savePreparationId by remember(peer) { mutableStateOf<String?>(null) }
    var page by remember(peer) { mutableStateOf<SharedMediaPage?>(null) }
    var changingDownloads by remember(peer) { mutableStateOf(emptySet<String>()) }
    var preview by remember(peer) { mutableStateOf<MessageUIModel?>(null) }
    var previewOrigin by remember(peer) { mutableStateOf<MediaPhotoOrigin?>(null) }
    var previewBackAction by remember(peer) { mutableStateOf<(() -> Unit)?>(null) }
    val photoFrames = remember(peer) { mutableStateMapOf<String, MediaPhotoFrame>() }
    var neighbors by remember(peer) { mutableStateOf<VisualMediaNeighbors?>(null) }
    var previewRequest by remember(peer) { mutableStateOf<Job?>(null) }
    var previewGeneration by remember(peer) { mutableIntStateOf(0) }
    var previewReload by remember(peer) { mutableIntStateOf(0) }
    var previewRefreshFailed by remember(peer) { mutableStateOf(false) }
    var failedPreviewOpen by remember(peer) { mutableStateOf<SharedMediaItem?>(null) }
    var checkingAttachment by remember(peer) { mutableStateOf(false) }
    fun cancelPreviewRequest() {
        previewGeneration++
        previewRequest?.cancel()
        previewRequest = null
        checkingAttachment = false
    }
    var revision by remember(peer) { mutableIntStateOf(0) }
    var loading by remember(peer) { mutableStateOf(true) }
    var error by remember(peer) { mutableStateOf<String?>(null) }
    val saveFailed = uiString(R.string.ui_shared_media_save_failed)
    val saved = uiString(R.string.ui_shared_media_saved)
    val loadFailed = uiString(R.string.ui_shared_media_load_failed)
    val exportFailed = uiString(R.string.ui_file_details_unavailable)
    val attachmentExport = rememberAttachmentExport(peer) {
        error = exportFailed
        if (preview != null) android.widget.Toast.makeText(context, exportFailed, android.widget.Toast.LENGTH_SHORT).show()
    }
    val exportAttachment: (String, org.msgat.core.media.AttachmentMetadata) -> Unit = { id, media ->
        attachmentExport.save(media) { store.canExportMessage(peer, id, media.reference) }
    }
    val downloadQueued = uiString(R.string.ui_download_queued)
    fun toggleSelection(id: String) {
        if (id in selected) selected = selected - id
        else if (selected.size < 100) selected = selected + id
        else android.widget.Toast.makeText(context, R.string.ui_message_selection_limit,
            android.widget.Toast.LENGTH_SHORT).show()
    }
    var datePicker by remember(peer) { mutableStateOf<android.app.DatePickerDialog?>(null) }
    DisposableEffect(peer) {
        onDispose {
            cancelPreviewRequest()
            savePreparationId?.let(onCancelPreparation)
            datePicker?.dismiss()
        }
    }

    fun changeDate(date: java.time.LocalDate?) {
        if (dateLimit == date) return
        cancelPreviewRequest()
        dateLimit = date
        categoryPages.clear()
        page = null
        loading = true
        error = null
    }

    fun chooseDate() {
        if (datePicker?.isShowing == true) return
        val day = dateLimit ?: java.time.LocalDate.now()
        val picker = android.app.DatePickerDialog(context, { _, year, month, date ->
            changeDate(java.time.LocalDate.of(year, month + 1, date))
        }, day.year, day.monthValue - 1, day.dayOfMonth)
        datePicker = picker
        picker.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        picker.setOnDismissListener { if (datePicker === picker) datePicker = null }
        picker.show()
    }

    suspend fun currentItem(id: String): SharedMediaItem? = try {
        store.sharedMediaItem(peer, id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    fun closePreview() {
        cancelPreviewRequest()
        previewRefreshFailed = false
        failedPreviewOpen = null
        neighbors = null
        previewOrigin = null
        preview = null
    }

    fun open(item: SharedMediaItem, origin: MediaPhotoOrigin? = null, save: Boolean = false) {
        cancelPreviewRequest()
        failedPreviewOpen = null
        val generation = previewGeneration
        val selectedCategory = category
        checkingAttachment = !item.isMine
        previewRequest = scope.launch {
            val current = try {
                val current = store.sharedMediaItem(peer, item.id)
                val complete = current == null || current.isMine || !current.attachment.available ||
                    current.attachment.reference != item.attachment.reference ||
                    store.hasCompleteCiphertext(current.attachment.reference)
                if (!complete && current != null) {
                    if (!MsgATApp.instance.nettyClient.secureMessaging.replaceAttachmentCache(peer, current.id, current.attachment.reference)) {
                        if (generation == previewGeneration) {
                            if (preview != null) failedPreviewOpen = item else error = exportFailed
                        }
                        return@launch
                    }
                    val pending = if (!save && (selectedCategory.visual || current.attachment.isAudio))
                        store.sharedMediaItem(peer, current.id) else null
                    if (pending == null || pending.attachment.reference != current.attachment.reference || pending.attachment.available) {
                        if (generation == previewGeneration)
                            android.widget.Toast.makeText(context, downloadQueued, android.widget.Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    pending
                } else current
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == previewGeneration) {
                    if (preview != null) failedPreviewOpen = item else error = loadFailed
                }
                return@launch
            } finally {
                if (generation == previewGeneration) checkingAttachment = false
            }
            if (generation != previewGeneration || selectedCategory != category) return@launch
            if (current == null || current.attachment.reference != item.attachment.reference ||
                !current.attachment.available && (save || !selectedCategory.visual && !current.attachment.isAudio)) {
                if (preview == null) error = exportFailed
                else android.widget.Toast.makeText(context, exportFailed, android.widget.Toast.LENGTH_SHORT).show()
                revision++
                return@launch
            }
            if (save || category == SharedMediaCategory.FILES) {
                exportAttachment(current.id, current.attachment)
            } else {
                neighbors = null
                previewRefreshFailed = false
                previewOrigin = origin?.takeIf { it.reference == current.attachment.reference }
                preview = MessageUIModel(current.id, current.isMine, current.attachment.reference, "attachment",
                    current.timestamp, attachment = current.attachment)
            }
        }
    }

    fun save(item: SharedMediaItem) {
        open(item, save = true)
    }

    fun changeDownload(item: SharedMediaItem, requested: Boolean) {
        if (item.id in changingDownloads) return
        changingDownloads = changingDownloads + item.id
        scope.launch {
            try {
                val changed = MsgATApp.instance.nettyClient.secureMessaging
                    .requestAttachmentDownload(peer, item.id, requested)
                val current = store.sharedMediaItem(peer, item.id)
                if (current != null && current.attachment.reference == item.attachment.reference) {
                    page = page?.let { displayed ->
                        displayed.copy(items = displayed.items.map { row ->
                            if (row.id == current.id && row.attachment.reference == current.attachment.reference) current else row
                        })
                    }
                }
                if (!changed) android.widget.Toast.makeText(context, exportFailed, android.widget.Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, loadFailed, android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                changingDownloads = changingDownloads - item.id
            }
        }
    }

    LaunchedEffect(store, peer) {
        merge(store.observeSharedMediaChanges(), store.availability.map { Unit }).conflate().collect {
            delay(150)
            revision++
        }
    }
    LaunchedEffect(peer, category, query, pages, revision) {
        loading = true
        try {
            if (query.isNotEmpty() && page == null) delay(220)
            val result = store.sharedMedia(peer, category, pages.last(), query)
            page = result
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = loadFailed
        } finally {
            loading = false
        }
    }

    LaunchedEffect(peer, preview?.id, preview?.content, revision, previewReload) {
        val shown = preview ?: return@LaunchedEffect
        fun stillShowing() = preview?.id == shown.id && preview?.content == shown.content
        val current = try {
            store.sharedMediaItem(peer, shown.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (stillShowing()) previewRefreshFailed = true
            return@LaunchedEffect
        }
        if (!stillShowing()) return@LaunchedEffect
        if (current == null || current.attachment.reference != shown.content) {
            closePreview()
            return@LaunchedEffect
        }
        val media = current.attachment
        if (shown.attachment != media) preview = shown.copy(attachment = media)
        if (category.visual) {
            val adjacent = try {
                store.visualMediaNeighbors(peer, SharedMediaCursor(current.timestamp, current.id), category, query, firstCursor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (stillShowing()) previewRefreshFailed = true
                return@LaunchedEffect
            }
            if (stillShowing()) neighbors = adjacent
        }
        if (stillShowing()) previewRefreshFailed = false
    }

    val scrollStates = remember(peer, query, firstCursor) {
        LinkedHashMap<Pair<SharedMediaCategory, SharedMediaCursor?>, LazyGridState>(24, 0.75f, true)
    }
    val gridState = remember(peer, query, firstCursor, category, pages.last()) {
        val state = scrollStates.getOrPut(category to pages.last()) { LazyGridState() }
        if (scrollStates.size > 24) scrollStates.remove(scrollStates.keys.first())
        state
    }
    val dismissGallery by rememberUpdatedState(onDismiss)

    Dialog(
        onDismissRequest = {
            if (preview != null) previewBackAction?.invoke() ?: closePreview()
            else if (!saving && selecting) {
                selecting = false
                selected = emptySet()
            } else if (!saving) dismissGallery()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn
        )
    ) {
        val colors = LocalMessagingColors.current
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val currentPreview = preview
            if (currentPreview == null) {
                Box(Modifier.matchParentSize().pointerInput(Unit) {
                    detectTapGestures { if (!saving) dismissGallery() }
                })
            }
            Surface(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).widthIn(max = 720.dp).fillMaxWidth(.96f)
                    .heightIn(max = maxHeight * .9f)
                    .then(if (currentPreview != null) Modifier.clearAndSetSemantics { } else Modifier),
                shape = MaterialTheme.shapes.large,
                color = colors.chrome,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(uiString(R.string.ui_shared_media), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(enabled = !saving, onClick = ::chooseDate) {
                            Icon(Icons.Default.DateRange, uiString(R.string.ui_jump_to_date))
                        }
                        TextButton(enabled = !saving && !loading, onClick = {
                            selecting = !selecting
                            selected = emptySet()
                        }) { Text(uiString(if (selecting) R.string.ui_cancel else R.string.ui_select)) }
                        TextButton(enabled = !saving, onClick = onDismiss) { Text(uiString(R.string.ui_close)) }
                    }
                    OutlinedTextField(value = searchText, onValueChange = { value ->
                        if (!saving && value.length <= 256) {
                            if (value.trim() != query) {
                                cancelPreviewRequest()
                                categoryPages.clear()
                                page = null
                                loading = true
                                error = null
                            }
                            searchText = value
                        }
                    }, label = { Text(uiString(R.string.ui_search_shared_media)) },
                        enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                    dateLimit?.let { date ->
                        val locale = interfaceLocale()
                        val formatted = date.format(java.time.format.DateTimeFormatter
                            .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(uiString(R.string.ui_shared_media_through_date, formatted), Modifier.weight(1f))
                            TextButton(enabled = !saving, onClick = { changeDate(null) }) { Text(uiString(R.string.ui_shared_media_all_dates)) }
                        }
                    }
                    ScrollableTabRow(selectedTabIndex = category.ordinal, edgePadding = 0.dp,
                        containerColor = colors.chrome, divider = { HorizontalDivider(color = colors.divider) }) {
                        for (choice in SharedMediaCategory.entries) {
                            Tab(selected = category == choice, enabled = !saving, onClick = {
                                if (category != choice) {
                                    cancelPreviewRequest()
                                    category = choice
                                    page = null
                                    loading = true
                                    error = null
                                }
                            }, text = { Text(uiString(choice.titleResource), maxLines = 1) })
                        }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (checkingAttachment) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(uiString(R.string.ui_checking_attachment), Modifier.weight(1f))
                            TextButton(onClick = ::cancelPreviewRequest) { Text(uiString(R.string.ui_cancel)) }
                        }
                    }
                    error?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = { error = null; revision++ }) { Text(uiString(R.string.ui_retry)) }
                        }
                    }
                    val entries = page?.items.orEmpty()
                    val links = page?.links.orEmpty()
                    if (entries.isEmpty() && links.isEmpty() && !loading && error == null) {
                        val emptyText = uiString(if (query.isEmpty()) R.string.ui_shared_media_empty else R.string.ui_shared_media_no_matches)
                        Text(emptyText, Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    key(category, query, pages.last()) {
                        LazyVerticalGrid(
                            columns = if (category.visual) GridCells.Adaptive(96.dp) else GridCells.Fixed(1),
                            modifier = Modifier.weight(1f),
                            state = gridState,
                            verticalArrangement = Arrangement.spacedBy(if (category.visual) 3.dp else 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            mediaMonthSections(links, { it.timestamp }, { it.id }) { item ->
                                if (selecting) {
                                    SharedMediaSelectionRow(item.urls.joinToString("\n"), item.id in selected, !saving) {
                                        toggleSelection(item.id)
                                    }
                                    return@mediaMonthSections
                                }
                                Column(Modifier.fillMaxWidth()) {
                                    RichMessageText(item.urls.joinToString("\n"))
                                    TextButton(onClick = { onJump(item.id) }) { Text(uiString(R.string.ui_show_in_chat)) }
                                }
                            }
                            mediaMonthSections(entries, { it.timestamp }, { it.id }) { item ->
                                val media = item.attachment
                                if (selecting && !category.visual) {
                                    SharedMediaSelectionRow(media.filename, item.id in selected, !saving) {
                                        toggleSelection(item.id)
                                    }
                                    return@mediaMonthSections
                                }
                                val download: ((Boolean) -> Unit)? = if (item.isMine) null else {
                                    { requested -> changeDownload(item, requested) }
                                }
                                if (category.visual) {
                                    var menuOpen by remember { mutableStateOf(false) }
                                    Box {
                                        AttachmentCard(media, progress[item.id], { origin -> open(item, origin) }, { menuOpen = true },
                                            onDownload = download.takeUnless { selecting }, galleryTile = true,
                                            downloadActionBusy = item.id in changingDownloads,
                                            gallerySelected = if (selecting) item.id in selected else null,
                                            onToggleGallerySelection = if (selecting && !saving) ({
                                                toggleSelection(item.id)
                                            }) else null,
                                            onLongClickLabel = uiString(R.string.ui_attachment_actions),
                                            onPhotoFrame = { frame ->
                                                if (frame == null) photoFrames.remove(item.id) else photoFrames[item.id] = frame
                                            })
                                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                            DropdownMenuItem(text = { Text(uiString(R.string.ui_select)) }, onClick = {
                                                menuOpen = false
                                                selected = setOf(item.id)
                                                selecting = true
                                            })
                                            DropdownMenuItem(text = { Text(uiString(R.string.ui_show_in_chat)) }, onClick = {
                                                menuOpen = false
                                                onJump(item.id)
                                            })
                                            DropdownMenuItem(text = { Text(uiString(R.string.ui_save_attachment)) },
                                                enabled = media.available, onClick = {
                                                    menuOpen = false
                                                    save(item)
                                                })
                                        }
                                    }
                                } else {
                                    Column {
                                        AttachmentCard(media, progress[item.id], { open(item) }, {},
                                            onDownload = download, downloadActionBusy = item.id in changingDownloads)
                                        Text(media.filename, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { onJump(item.id) }) { Text(uiString(R.string.ui_show_in_chat)) }
                                            if (category != SharedMediaCategory.FILES) {
                                                IconButton(enabled = media.available, onClick = { save(item) }) {
                                                    Icon(Icons.Default.Download, contentDescription = uiString(R.string.ui_save_attachment))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (selecting) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(uiString(R.string.ui_message_selection_count, selected.size),
                                modifier = Modifier.weight(1f))
                            TextButton(enabled = !loading && !saving && selected.isNotEmpty(),
                                onClick = { onForward(selected.toList()) }) { Text(uiString(R.string.ui_forward)) }
                            TextButton(enabled = !loading && !saving && !checkingAttachment && selected.isNotEmpty(), onClick = {
                                saving = true
                                error = null
                                onSave(selected.toList(), {
                                    saving = false
                                    savePreparationId = null
                                    selecting = false
                                    selected = emptySet()
                                    android.widget.Toast.makeText(context, saved, android.widget.Toast.LENGTH_SHORT).show()
                                }, {
                                    saving = false
                                    savePreparationId = null
                                    error = saveFailed
                                }, {
                                    saving = false
                                    savePreparationId = null
                                }, { savePreparationId = it })
                            }) { Text(uiString(R.string.ui_shared_media_save_selection)) }
                        }
                    }
                    if (saving) {
                        val active = preparations.filter { it.id == savePreparationId }
                        if (active.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else PreparationRows(active, onCancelPreparation)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = !saving && !loading && pages.size > 1, onClick = {
                            cancelPreviewRequest()
                            categoryPages[category] = pages.dropLast(1)
                            page = null
                            loading = true
                            error = null
                        }) { Text(uiString(R.string.ui_media_newer)) }
                        TextButton(enabled = !saving && !loading && page?.older != null, onClick = {
                            val cursor = page?.older ?: return@TextButton
                            cancelPreviewRequest()
                            categoryPages[category] = pages + cursor
                            page = null
                            loading = true
                            error = null
                        }) { Text(uiString(R.string.ui_media_older)) }
                    }
                }
            }
            if (currentPreview != null) {
                val view = LocalView.current
                val window = (view.parent as? DialogWindowProvider)?.window
                if (window != null) {
                    DisposableEffect(window, view) {
                        val bars = WindowCompat.getInsetsController(window, view)
                        val lightStatus = bars.isAppearanceLightStatusBars
                        val lightNavigation = bars.isAppearanceLightNavigationBars
                        bars.isAppearanceLightStatusBars = false
                        bars.isAppearanceLightNavigationBars = false
                        onDispose {
                            bars.isAppearanceLightStatusBars = lightStatus
                            bars.isAppearanceLightNavigationBars = lightNavigation
                        }
                    }
                }
                ConversationMediaViewer(currentPreview, progress[currentPreview.id],
                    newer = neighbors?.newer?.let { item -> { open(item) } },
                    older = neighbors?.older?.let { item -> { open(item) } },
                    photoOrigin = previewOrigin,
                    returnFrame = photoFrames[currentPreview.id],
                    onCloseStarted = ::cancelPreviewRequest,
                    registerBackAction = { previewBackAction = it },
                    onShowMessage = { closePreview(); onJump(currentPreview.id) },
                    onForward = { closePreview(); onForward(listOf(currentPreview.id)) },
                    conversationPeer = peer,
                    onExportAttachment = exportAttachment,
                    onRetryRefresh = failedPreviewOpen?.let { failed -> { open(failed) } }
                        ?: if (previewRefreshFailed) ({ previewReload++ }) else null,
                    onDismiss = ::closePreview)
            }
        }
    }
}

private val SharedMediaCategory.titleResource: Int get() = when (this) {
    SharedMediaCategory.MEDIA -> R.string.ui_shared_media_all_visual
    SharedMediaCategory.PHOTOS -> R.string.ui_shared_media_photos
    SharedMediaCategory.VIDEOS -> R.string.ui_shared_media_videos
    SharedMediaCategory.FILES -> R.string.ui_files
    SharedMediaCategory.LINKS -> R.string.ui_shared_media_links
    SharedMediaCategory.AUDIO -> R.string.ui_audio
}

private fun <T> androidx.compose.foundation.lazy.grid.LazyGridScope.mediaMonthSections(
    entries: List<T>, timestamp: (T) -> Long, id: (T) -> String, content: @Composable (T) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val months = entries.groupBy { YearMonth.from(Instant.ofEpochMilli(timestamp(it)).atZone(zone)) }
    for ((month, rows) in months) {
        item(key = "month-$month", span = { GridItemSpan(maxLineSpan) }) {
            val locale = interfaceLocale()
            val monthName = remember(month, locale) {
                android.icu.text.DateFormat.getInstanceForSkeleton("yMMMM", locale)
                    .format(java.util.Date.from(month.atDay(1).atStartOfDay(zone).toInstant()))
            }
            Text(monthName, Modifier.fillMaxWidth().padding(vertical = 7.dp).semantics { heading() },
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
        items(rows, key = id) { content(it) }
    }
}

@Composable
private fun SharedMediaSelectionRow(label: String, checked: Boolean, enabled: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = toggle)
        .background(if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { toggle() }, enabled = enabled)
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
