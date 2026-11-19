// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.*
import org.msgat.R
import org.msgat.core.media.*
import org.msgat.core.network.NettyClient
import org.msgat.data.db.*
import org.msgat.ui.chat.EncryptedImage
import org.msgat.ui.theme.LocalAppearanceSettings
import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString
import java.nio.ByteBuffer

private const val StickerPageSize = 60
private const val StickerSelectionLimit = 200

private enum class StickerTab { All, Favorites, Recent, Animated }
private data class StickerQuery(val pack: String?, val tab: StickerTab, val text: String)
private data class StickerPage(
    val items: List<StickerItem>,
    val more: Boolean,
    val nextOffset: Int = 0,
    val scroll: LazyGridState = LazyGridState()
)

@Composable
internal fun StickerLibraryDialog(
    client: NettyClient,
    onDismiss: () -> Unit,
    openSharedSource: (suspend () -> StickerSource)? = null
) {
    var busy by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOn, dismissOnClickOutside = false, dismissOnBackPress = !busy)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.safeDrawingPadding().imePadding()) {
                StickerLibraryPanel(client, onDismiss, openSharedSource = openSharedSource, onBusyChange = { busy = it })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StickerLibraryPanel(
    client: NettyClient,
    onDismiss: () -> Unit,
    openSharedSource: (suspend () -> StickerSource)? = null,
    canExpand: Boolean = false,
    expanded: Boolean = false,
    onExpand: (Boolean) -> Unit = {},
    onBusyChange: (Boolean) -> Unit = {},
    acceptingInput: Boolean = true,
    preparations: List<PendingPreparation> = emptyList(),
    onCancelPreparation: (String) -> Unit = {},
    onSend: (suspend (StickerItem) -> PreparationSendResult)? = null
) {
    val context = LocalContext.current
    val language = LocalAppearanceSettings.current.language
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val resources = remember(language, configuration, context) { language.resources(context, configuration) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val defaultPackName = uiString(R.string.ui_imported_stickers)
    val busyChanged by rememberUpdatedState(onBusyChange)
    val active by rememberUpdatedState(acceptingInput)
    var source by remember { mutableStateOf<StickerSource?>(null) }
    var selected by remember { mutableStateOf<Map<Int, StickerSelection>>(emptyMap()) }
    var name by remember { mutableStateOf(defaultPackName) }
    var importPack by remember { mutableStateOf<String?>(null) }
    var packs by remember { mutableStateOf<List<StickerPack>>(emptyList()) }
    var pack by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(StickerTab.All) }
    var search by remember { mutableStateOf("") }
    val query = StickerQuery(pack, tab, search.trim())
    val pages = remember { mutableStateMapOf<StickerQuery, StickerPage>() }
    val page = pages[query]
    var offset by remember(query) { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember(query, source) { mutableStateOf(true) }
    var libraryLoadFailed by remember(query, source) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("") }
    var importSummary by remember { mutableStateOf("") }
    var importOwner by remember { mutableStateOf<Any?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sharedOpenFailed by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var deletePack by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<StickerItem?>(null) }
    var remove by remember { mutableStateOf<Pair<StickerItem, String?>?>(null) }

    fun work(failure: Int, sendingSticker: Boolean = false, action: suspend () -> Unit) {
        if (busy || !active) return
        busy = true
        sending = sendingSticker
        error = null
        status = ""
        importSummary = ""
        busyChanged(true)
        operation = scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failureError: Exception) {
                error = if (failure == R.string.ui_sticker_load_error && failureError is java.io.IOException)
                    failureError.message ?: resources.getString(failure)
                else resources.getString(failure)
            } finally {
                sending = false
                busy = false
                status = ""
                operation = null
                busyChanged(false)
            }
        }
    }

    fun refresh() {
        offset = 0
        val current = StickerQuery(pack, tab, search.trim())
        val scroll = pages[current]?.scroll
        pages.clear()
        if (scroll != null) pages[current] = StickerPage(emptyList(), false, scroll = scroll)
        revision++
    }

    fun mutate(action: suspend () -> Unit) = work(R.string.ui_sticker_change_error) {
        action()
        refresh()
    }

    fun open(fromShare: Boolean = false, action: suspend () -> StickerSource) = work(R.string.ui_sticker_load_error) {
        sharedOpenFailed = false
        val opened = try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (loadError: Exception) {
            sharedOpenFailed = fromShare
            throw loadError
        }
        try {
            currentCoroutineContext().ensureActive()
            packs = try { client.stickers.packs() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { packs }
            source = opened
            selected = emptyMap()
            name = defaultPackName
            importPack = null
        } catch (failure: Throwable) {
            withContext(NonCancellable) { opened.close() }
            throw failure
        }
    }

    fun send(item: StickerItem) {
        val action = onSend ?: return
        preview = null
        focus.clearFocus()
        keyboard?.hide()
        work(R.string.ui_sticker_send_error, sendingSticker = true) {
            when (action(item)) {
                PreparationSendResult.QUEUED -> onDismiss()
                PreparationSendResult.CANCELLED -> Unit
                PreparationSendResult.FAILED -> error = resources.getString(R.string.ui_sticker_send_error)
            }
        }
    }

    fun backToLibrary() {
        if (busy) return
        focus.clearFocus()
        keyboard?.hide()
        selected = emptyMap()
        source = null
        error = null
    }

    fun openSharedImport() { openSharedSource?.let { open(fromShare = true, action = it) } }
    LaunchedEffect(Unit) { openSharedImport() }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open { StickerSource.open(context, uri) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) open { StickerSource.openFiles(context, uris) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) open { StickerSource.openTree(context, uri) }
    }
    LaunchedEffect(query, offset, revision, source) {
        if (source != null || (offset == 0 && page != null && page.nextOffset > 0)) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        libraryLoadFailed = false
        try {
            if (offset == 0 && query.text.isNotEmpty()) delay(180)
            val result = client.stickers.list(query.pack, query.tab == StickerTab.Favorites,
                query.tab == StickerTab.Recent, query.text, offset, animated = query.tab == StickerTab.Animated)
            val groups = if (offset == 0) client.stickers.packs() else packs
            ensureActive()
            packs = groups
            val previous = pages[query]
            if (pages.size >= 16 && previous == null) pages.keys.firstOrNull()?.let { pages.remove(it) }
            pages[query] = StickerPage(
                if (offset == 0) result else (previous?.items.orEmpty() + result).distinctBy { it.id },
                result.size == StickerPageSize, offset + StickerPageSize, previous?.scroll ?: LazyGridState()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            libraryLoadFailed = true
        } finally {
            loading = false
        }
    }
    DisposableEffect(source) {
        val catalog = source
        onDispose {
            if (catalog != null) CoroutineScope(Dispatchers.IO).launch { runCatching { catalog.close() } }
        }
    }
    DisposableEffect(Unit) { onDispose { busyChanged(false) } }
    androidx.activity.compose.BackHandler(enabled = !busy && source != null) { backToLibrary() }

    val colors = LocalMessagingColors.current
    Column(Modifier.fillMaxSize().background(colors.chrome)) {
        val catalog = source
        val title = if (catalog != null || (openSharedSource != null && busy)) R.string.ui_import_stickers
            else if (onSend == null) R.string.ui_sticker_library else R.string.ui_stickers
        if (canExpand && catalog == null) {
            val expandPanel by rememberUpdatedState(onExpand)
            Box(
                Modifier.fillMaxWidth().height(16.dp).pointerInput(expanded, busy) {
                    if (busy) return@pointerInput
                    val threshold = 30.dp.toPx()
                    var distance = 0f
                    detectVerticalDragGestures(
                        onDragStart = { distance = 0f },
                        onDragEnd = {
                            if (distance < -threshold && !expanded) expandPanel(true)
                            if (distance > threshold && expanded) expandPanel(false)
                        }
                    ) { change, amount ->
                        change.consume()
                        distance += amount
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(width = 30.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (catalog != null) IconButton(enabled = !busy, onClick = ::backToLibrary) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, uiString(R.string.ui_sticker_back))
            }
            Text(uiString(title), Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleMedium)
            if (canExpand && catalog == null) IconButton(enabled = !busy, onClick = { onExpand(!expanded) }) {
                Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    uiString(if (expanded) R.string.ui_sticker_collapse else R.string.ui_sticker_expand))
            }
            if (catalog == null) Box {
                IconButton(enabled = !busy, onClick = { tools = true }) {
                    Icon(Icons.Default.MoreVert, uiString(R.string.ui_sticker_manage))
                }
                DropdownMenu(expanded = tools, onDismissRequest = { tools = false }) {
                    DropdownMenuItem(text = { Text(uiString(R.string.ui_sticker_import_file)) }, onClick = {
                        tools = false
                        filePicker.launch(arrayOf("*/*"))
                    })
                    DropdownMenuItem(text = { Text(uiString(R.string.ui_sticker_import_images)) }, onClick = {
                        tools = false
                        imagePicker.launch(arrayOf("image/*", "application/octet-stream"))
                    })
                    DropdownMenuItem(text = { Text(uiString(R.string.ui_sticker_import_folder)) }, onClick = {
                        tools = false
                        folderPicker.launch(null)
                    })
                    if (pack != null) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(uiString(R.string.ui_sticker_rename)) }, onClick = {
                            tools = false
                            name = packs.firstOrNull { it.id == pack }?.name.orEmpty()
                            rename = true
                        })
                        DropdownMenuItem(text = { Text(uiString(R.string.ui_sticker_delete_group)) }, onClick = {
                            tools = false
                            deletePack = true
                        })
                    }
                }
            }
            IconButton(enabled = !busy, onClick = { focus.clearFocus(); keyboard?.hide(); onDismiss() }) {
                Icon(Icons.Default.Close, uiString(R.string.ui_sticker_close_panel))
            }
        }
        HorizontalDivider(color = colors.divider)
        if (busy && (!sending || preparations.isEmpty())) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (sending && preparations.isNotEmpty()) PreparationRows(preparations, onCancelPreparation)
        if (status.isNotEmpty()) Text(status, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (importSummary.isNotEmpty()) Text(importSummary, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error) }
        if (catalog == null && sharedOpenFailed && !busy) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::openSharedImport) { Text(uiString(R.string.ui_retry)) }
                TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Text(uiString(R.string.ui_sticker_import_file))
                }
            }
        }

        if (catalog == null) {
            if (!sending) OutlinedTextField(
                value = search, onValueChange = { search = it.take(256); error = null },
                placeholder = { Text(uiString(R.string.ui_sticker_search)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (search.isEmpty()) null else {
                    { IconButton(onClick = { search = ""; error = null }) {
                        Icon(Icons.Default.Close, uiString(R.string.ui_search_clear))
                    } }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                singleLine = true, enabled = !busy, shape = RoundedCornerShape(18.dp)
            )
            if (!sending) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems(StickerTab.entries) { mode ->
                    val nameId = when (mode) {
                        StickerTab.All -> R.string.ui_sticker_all
                        StickerTab.Favorites -> R.string.ui_sticker_favorites
                        StickerTab.Recent -> R.string.ui_sticker_recent
                        StickerTab.Animated -> R.string.ui_sticker_animated
                    }
                    FilterChip(selected = pack == null && tab == mode, enabled = !busy,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.selection), onClick = {
                        pack = null
                        tab = mode
                        error = null
                    }, label = { Text(uiString(nameId)) })
                }
                rowItems(packs, key = { it.id }) { group ->
                    FilterChip(selected = pack == group.id, enabled = !busy,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.selection), onClick = {
                        pack = group.id
                        tab = StickerTab.All
                        error = null
                    }, label = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.widthIn(max = 160.dp))
                }
            }
            val entries = page?.items.orEmpty()
            if (libraryLoadFailed && !loading && !busy) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(uiString(R.string.ui_sticker_load_error), Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { libraryLoadFailed = false; revision++ }) { Text(uiString(R.string.ui_retry)) }
                }
            }
            if (entries.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(24.dp))
                        !busy && !libraryLoadFailed && !sharedOpenFailed ->
                            Text(uiString(R.string.ui_sticker_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (page != null) {
                LazyVerticalGrid(
                    columns = if (onSend != null) GridCells.Fixed(5) else GridCells.Adaptive(72.dp),
                    modifier = Modifier.weight(1f), state = page.scroll,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    items(entries, key = { it.id }) { item ->
                        StickerTile(item, enabled = !busy, onClick = {
                            if (onSend != null) send(item) else preview = item
                        }, onPreview = { preview = item })
                    }
                    if (page.more) item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(enabled = !busy && !loading && !libraryLoadFailed, onClick = {
                            offset = page.nextOffset
                        }) { Text(uiString(R.string.ui_sticker_more)) }
                    }
                }
            }
        } else {
            val validName = name.trim().let { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 128 }
            val validDestination = importPack?.let { id -> packs.any { it.id == id } } ?: validName
            Text(uiString(R.string.ui_sticker_candidates, catalog.entries.size, selected.size),
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            if (packs.isNotEmpty()) {
                Text(uiString(R.string.ui_sticker_import_destination), Modifier.padding(horizontal = 14.dp),
                    style = MaterialTheme.typography.labelMedium)
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(selected = importPack == null, enabled = !busy, onClick = { importPack = null },
                            label = { Text(uiString(R.string.ui_sticker_create_group)) })
                    }
                    rowItems(packs, key = { it.id }) { group ->
                        FilterChip(selected = importPack == group.id, enabled = !busy, onClick = { importPack = group.id },
                            label = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.widthIn(max = 180.dp))
                    }
                }
            }
            if (importPack == null) {
                OutlinedTextField(name, { name = it.take(128) }, label = { Text(uiString(R.string.ui_sticker_new_group)) },
                    supportingText = if (name.isNotBlank() && !validName) {
                        { Text(uiString(R.string.ui_sticker_group_name_limit)) }
                    } else null,
                    enabled = !busy, isError = name.isNotBlank() && !validName, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            }
            key(catalog) {
                LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                    items(catalog.entries, key = { it.id }) { entry ->
                        StickerCandidate(catalog, entry, entry.id in selected, !busy) { choice ->
                            if (choice == null) selected = selected - entry.id
                            else if (selected.size < StickerSelectionLimit) selected = selected + (entry.id to choice)
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.divider)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                if (busy) TextButton(onClick = { operation?.cancel() }) { Text(uiString(R.string.ui_sticker_cancel_operation)) }
                else Button(enabled = selected.isNotEmpty() && validDestination, onClick = {
                    focus.clearFocus()
                    keyboard?.hide()
                    val destination = importPack
                    work(R.string.ui_sticker_change_error) {
                        val owner = Any()
                        importOwner = owner
                        val result = try {
                            client.stickers.import(catalog, selected.values.sortedBy { it.entry.id }, name,
                                ::inspectStickerPixels, packId = destination, progress = { value ->
                                    scope.launch {
                                        if (importOwner === owner) status = resources.getString(
                                            if (value.phase == "Scanning") R.string.ui_sticker_scan_progress else R.string.ui_sticker_encrypt_progress,
                                            value.done, value.total)
                                    }
                                })
                        } finally {
                            importOwner = null
                        }
                        source = null
                        selected = emptyMap()
                        pack = result.packId
                        tab = StickerTab.All
                        search = ""
                        importSummary = resources.getString(R.string.ui_sticker_imported_count, result.added, result.duplicates)
                        refresh()
                    }
                }) { Text(uiString(R.string.ui_sticker_import_selected)) }
            }
        }
        if (busy && !sending && catalog == null) TextButton(onClick = { operation?.cancel() }, modifier = Modifier.align(Alignment.End)) {
            Text(uiString(R.string.ui_sticker_cancel_operation))
        }
    }

    preview?.let { item ->
        StickerPreview(item, inPack = pack != null, onDismiss = { preview = null },
            onSend = onSend?.let { { send(item) } },
            onFavorite = {
                preview = null
                mutate { client.stickers.favorite(item.id, !item.favorite) }
            },
                onRemove = {
                preview = null
                remove = item to pack
            })
    }
    remove?.let { (item, group) ->
        AlertDialog(onDismissRequest = { remove = null },
            title = { Text(uiString(if (group == null) R.string.ui_sticker_delete_item_confirm else R.string.ui_sticker_remove_from_pack_confirm)) },
            confirmButton = { TextButton(onClick = {
                remove = null
                mutate {
                    if (group == null) client.stickers.delete(item.id)
                    else client.stickers.removeFromPack(group, item.id)
                }
            }) { Text(uiString(R.string.ui_sticker_remove)) } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text(uiString(R.string.ui_cancel)) } },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn))
    }
    if (rename) AlertDialog(onDismissRequest = { rename = false },
        title = { Text(uiString(R.string.ui_sticker_rename)) },
        text = { OutlinedTextField(name, { name = it.take(128) }, label = { Text(uiString(R.string.ui_sticker_group_name)) }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && name.trim().toByteArray(Charsets.UTF_8).size <= 128, onClick = {
                val id = pack ?: return@TextButton
                rename = false
                mutate { client.stickers.renamePack(id, name) }
            }) { Text(uiString(R.string.ui_save)) }
        },
        dismissButton = { TextButton(onClick = { rename = false }) { Text(uiString(R.string.ui_cancel)) } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn))
    if (deletePack) AlertDialog(onDismissRequest = { deletePack = false },
        title = { Text(uiString(R.string.ui_sticker_delete_group_confirm)) },
        text = { Text(uiString(R.string.ui_sticker_other_groups_kept)) },
        confirmButton = { TextButton(onClick = {
            val id = pack ?: return@TextButton
            deletePack = false
            mutate { client.stickers.deletePack(id); pack = null }
        }) { Text(uiString(R.string.ui_sticker_delete)) } },
        dismissButton = { TextButton(onClick = { deletePack = false }) { Text(uiString(R.string.ui_cancel)) } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn))
}

private fun StickerItem.previewMetadata() = AttachmentMetadata(reference, label, mime, size, true, "", true)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerTile(item: StickerItem, enabled: Boolean, onClick: () -> Unit, onPreview: () -> Unit) {
    val previewLabel = uiString(R.string.ui_sticker_preview)
    val animatedLabel = uiString(R.string.ui_sticker_animated)
    val colors = LocalMessagingColors.current
    Box(
        Modifier.padding(2.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
            .background(colors.timeline)
            .semantics { contentDescription = if (item.frames > 1) "${item.label}, $animatedLabel" else item.label }
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClickLabel = previewLabel, onLongClick = onPreview)
            .padding(5.dp)
    ) {
        EncryptedImage(item.previewMetadata(), Modifier.fillMaxSize(), playStickerAnimation = false, thumbnailEdge = 192)
        if (item.frames > 1) Icon(Icons.Default.PlayArrow, null,
            Modifier.align(Alignment.BottomEnd).size(14.dp).background(MaterialTheme.colorScheme.surface, CircleShape))
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun StickerPreview(item: StickerItem, inPack: Boolean, onDismiss: () -> Unit, onSend: (() -> Unit)?,
                           onFavorite: () -> Unit, onRemove: () -> Unit) {
    var attempt by remember(item.reference) { mutableIntStateOf(0) }
    var ready by remember(item.reference, attempt) { mutableStateOf(false) }
    var failed by remember(item.reference, attempt) { mutableStateOf(false) }
    val colors = LocalMessagingColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiString(R.string.ui_sticker_preview)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.widthIn(max = 256.dp).fillMaxWidth().aspectRatio(1f)
                    .background(colors.timeline, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                    if (failed) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(uiString(R.string.ui_sticker_invalid_image), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { attempt++ }) { Text(uiString(R.string.ui_retry)) }
                        }
                    } else key(attempt) {
                        EncryptedImage(item.previewMetadata(), Modifier.fillMaxSize(),
                            onImageSize = { ready = true },
                            onImageFailure = { failed = true })
                    }
                    if (!ready && !failed) CircularProgressIndicator(Modifier.size(24.dp))
                }
                Text(item.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onFavorite) {
                        Text(uiString(if (item.favorite) R.string.ui_sticker_unfavorite else R.string.ui_sticker_favorite))
                    }
                    TextButton(onClick = onRemove) {
                        Text(uiString(if (inPack) R.string.ui_sticker_remove_from_pack else R.string.ui_sticker_delete_item),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSend ?: onDismiss, enabled = onSend == null || ready && !failed) {
                Text(uiString(if (onSend == null) R.string.ui_close else R.string.ui_send))
            }
        },
        dismissButton = if (onSend == null) null else {
            { TextButton(onClick = onDismiss) { Text(uiString(R.string.ui_cancel)) } }
        },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
    )
}

private fun stickerBitmap(bytes: ByteArray): Bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))){decoder,info,_->
    require(info.size.width in 1..2048 && info.size.height in 1..2048)
    val factor=minOf(1.0,128.0/maxOf(info.size.width,info.size.height));decoder.setTargetSize(maxOf(1,(info.size.width*factor).toInt()),maxOf(1,(info.size.height*factor).toInt()));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
}
private fun inspectStickerPixels(bytes: ByteArray){stickerBitmap(bytes).recycle()}
@Composable private fun StickerCandidate(source: StickerSource,entry: StickerSourceEntry,selected: Boolean,enabled: Boolean,onSelect: (StickerSelection?)->Unit){
    var bitmap by remember(source,entry.id){mutableStateOf<Bitmap?>(null)};var hash by remember(source,entry.id){mutableStateOf<String?>(null)};var failed by remember(source,entry.id){mutableStateOf(false)}
    LaunchedEffect(source,entry.id){var pixels: Bitmap?=null;try {val image=source.read(entry);try {withContext(Dispatchers.IO){pixels=stickerBitmap(image.bytes)};ensureActive();bitmap=pixels;pixels=null;hash=image.sha256}finally {image.clear()}}catch(cancelled: CancellationException){throw cancelled}catch(_: Exception){failed=true}finally{pixels?.recycle()}}
    DisposableEffect(bitmap){val pixels=bitmap;onDispose{pixels?.recycle()}}
    val colors = LocalMessagingColors.current
    Column(Modifier.padding(3.dp).clip(RoundedCornerShape(8.dp))
        .toggleable(value = selected, enabled = enabled && hash != null, role = Role.Checkbox,
            onValueChange = { onSelect(if (it) StickerSelection(entry, hash) else null) })
        .background(if (selected) colors.selection else colors.timeline)
        .padding(5.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize())
            else Text(uiString(if (failed) R.string.ui_sticker_invalid_image else R.string.ui_loading),
                style = MaterialTheme.typography.labelSmall)
            if (selected) Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopEnd).size(20.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
}
