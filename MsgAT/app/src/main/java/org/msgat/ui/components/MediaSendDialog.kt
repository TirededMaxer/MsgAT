// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString
import org.msgat.R
import android.content.Context
import android.media.MediaPlayer
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.msgat.core.media.MemoryMediaSource
import org.msgat.core.media.PendingPreparation
import org.msgat.core.media.PreparationSendResult
import org.msgat.core.media.RecordingMediaStore
import org.msgat.core.media.RecordingReader
import org.msgat.core.media.VideoTrim
import org.msgat.core.network.MediaSendChoice
import org.msgat.core.network.SendOptions
import java.util.concurrent.atomic.AtomicBoolean

@Composable
private fun AttachmentOption(label: Int, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(
        text = { Text(uiString(label)) },
        onClick = onToggle,
        modifier = Modifier.semantics { selected = checked },
        trailingIcon = { Checkbox(checked = checked, onCheckedChange = null) }
    )
}

/** Preview and send retain the source independently of the confirmation window. */
internal class MediaConfirmation(
    val uris: List<Uri>,
    val recipient: String,
    val initial: SendOptions,
    val initialAsFile: Boolean,
    private val send: (List<Uri>, MediaSendChoice, () -> Unit, (PreparationSendResult) -> Unit, (String) -> Unit) -> Unit,
    private val released: () -> Unit,
    initialCaption: String = ""
) : AutoCloseable {
    val initialCaptions = if (initialCaption.isEmpty()) emptyMap() else mapOf(uris.first() to initialCaption)
    private val gate = Any()
    private var owners = 1
    private val editedSources = mutableMapOf<Uri, MemoryMediaSource>()
    private val closed = AtomicBoolean(false)

    fun retain(): AutoCloseable {
        synchronized(gate) {
            check(owners > 0)
            owners++
        }
        val once = AtomicBoolean(false)
        return AutoCloseable { if (once.compareAndSet(false, true)) release() }
    }

    private fun release() {
        val edits = synchronized(gate) {
            if (--owners != 0) return
            editedSources.values.toList().also { editedSources.clear() }
        }
        edits.forEach { it.close() }
        released()
    }

    fun replaceEdit(uri: Uri, source: MemoryMediaSource?) {
        val previous = synchronized(gate) {
            check(!closed.get())
            if (source == null) editedSources.remove(uri) else editedSources.put(uri, source)
        }
        previous?.close()
    }

    fun submit(items: List<Uri>, choice: MediaSendChoice, started: (String) -> Unit, onResult: (PreparationSendResult) -> Unit) {
        val owner = retain()
        try {
            val sources = synchronized(gate) { items.map { editedSources[it]?.uri ?: it } }
            send(sources, choice, { owner.close() }, onResult, started)
        } catch (error: Exception) {
            owner.close()
            throw error
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

private data class SelectedMedia(
    val uri: Uri,
    val name: String = "Attachment",
    val size: Long? = null,
    val image: Bitmap? = null,
    val error: Int? = null,
    val mime: String = "",
    val visualMedia: Boolean = false,
    val compressiblePhoto: Boolean = false,
    val video: Boolean = false
)

private fun inspectMedia(context: Context, uri: Uri, thumbnailLimit: Int? = null): SelectedMedia {
    val resolver = context.contentResolver
    var name = "Attachment"
    var size: Long? = null
    try {
        if (RecordingMediaStore.isRecording(context, uri)) {
            val details = RecordingMediaStore.details(context, uri)
            val video = details.mime.startsWith("video/")
            val image = if (video && thumbnailLimit != null) runCatching {
                RecordingReader(context, uri).use { input ->
                    android.media.MediaMetadataRetriever().use { media ->
                        media.setDataSource(input)
                        media.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            thumbnailLimit, thumbnailLimit)
                    }
                }
            }.getOrNull() else null
            return SelectedMedia(uri, details.name, details.size, image, mime = details.mime, visualMedia = video, video = video)
        }
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        resolver.query(uri, columns, null, null, null)?.use { row ->
            if (row.moveToFirst()) {
                val nameColumn = row.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !row.isNull(nameColumn)) {
                    name = row.getString(nameColumn).substringAfterLast('/').substringAfterLast('\\')
                }
                val sizeColumn = row.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !row.isNull(sizeColumn)) {
                    size = row.getLong(sizeColumn).takeIf { it >= 0 }
                }
            }
        }
        if ((size ?: 0) > 100L * 1024 * 1024) {
            return SelectedMedia(uri, name, size, error = R.string.ui_attachment_size_limit)
        }
        resolver.openInputStream(uri)?.use { } ?: return SelectedMedia(uri, name, size, error = R.string.ui_file_details_unavailable)
        val mime = resolver.getType(uri).orEmpty()
        val image = if (thumbnailLimit != null && mime.startsWith("image/")) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    require(width > 0 && height > 0 && width <= 64_000_000 / height)
                    val scale = minOf(1.0, thumbnailLimit.toDouble() / maxOf(width, height))
                    decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        } else if (thumbnailLimit != null && mime.startsWith("video/")) {
            runCatching { resolver.loadThumbnail(uri, android.util.Size(thumbnailLimit, thumbnailLimit), null) }.getOrNull()
        } else null
        return SelectedMedia(uri, name, size, image, mime = mime, visualMedia = mime.startsWith("image/") || mime.startsWith("video/"),
            compressiblePhoto = org.msgat.core.media.PhotoCompression.supports(mime), video = mime.startsWith("video/"))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return SelectedMedia(uri, name, size, error = R.string.ui_file_details_unavailable)
    }
}

private data class AttachmentDrag(val source: Uri, val pointer: Offset)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaSendDialog(
    selection: MediaConfirmation,
    preparations: List<PendingPreparation>,
    onCancelPreparation: (String) -> Unit,
    onDismiss: () -> Unit,
    onQueued: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var uris by remember(selection) { mutableStateOf(selection.uris) }
    val captions = remember(selection) { mutableStateMapOf<Uri, String>().apply { putAll(selection.initialCaptions) } }
    var selectedCaption by remember(selection) { mutableStateOf<Uri?>(null) }
    var showCaptionTargets by remember(selection) { mutableStateOf(false) }
    var confirmDiscard by remember(selection) { mutableStateOf(false) }
    var sending by remember(selection) { mutableStateOf(false) }
    var preparationFailed by remember(selection) { mutableStateOf(false) }
    var activePreparationId by remember(selection) { mutableStateOf<String?>(null) }
    var options by remember(selection) { mutableStateOf(selection.initial) }
    var asFile by remember(selection) { mutableStateOf(selection.initialAsFile) }
    var asAlbum by remember(selection) { mutableStateOf(true) }
    var compressPhotos by remember(selection) { mutableStateOf(false) }
    var compressVideos by remember(selection) { mutableStateOf(false) }
    var showMediaOptions by remember(selection) { mutableStateOf(false) }
    var showOptions by remember(selection) { mutableStateOf(false) }
    var error by remember(selection) { mutableStateOf<Int?>(null) }
    var editing by remember(selection) { mutableStateOf<SelectedMedia?>(null) }
    var trimming by remember(selection) { mutableStateOf<Uri?>(null) }
    val edits = remember(selection) { mutableStateMapOf<Uri, EditedPhoto>() }
    val trims = remember(selection) { mutableStateMapOf<Uri, VideoTrim>() }
    for ((uri, edit) in edits) {
        key(uri) {
            DisposableEffect(edit.preview) {
                onDispose { edit.preview.recycle() }
            }
        }
    }
    fun showPhotoEditor(photo: SelectedMedia) {
        focusManager.clearFocus()
        keyboard?.hide()
        editing = photo
    }
    fun showVideoEditor(uri: Uri) {
        focusManager.clearFocus()
        keyboard?.hide()
        trimming = uri
    }
    val attachmentScroll = rememberScrollState()
    val attachmentBounds = remember(selection) { mutableStateMapOf<Uri, Rect>() }
    var attachmentViewport by remember(selection) { mutableStateOf(Rect.Zero) }
    var drag by remember(selection) { mutableStateOf<AttachmentDrag?>(null) }
    val draggedUri by remember(selection) { derivedStateOf { drag?.source } }
    val dropTarget by remember(selection) {
        derivedStateOf {
            val current = drag ?: return@derivedStateOf null
            if (!attachmentViewport.contains(current.pointer)) return@derivedStateOf null
            val targets = uris.mapNotNull { uri -> attachmentBounds[uri]?.let { uri to it } }
            if (targets.isEmpty()) return@derivedStateOf null
            val pointer = current.pointer
            if (pointer.y < targets.minOf { it.second.top } || pointer.y > targets.maxOf { it.second.bottom })
                return@derivedStateOf null
            targets.firstOrNull { (_, bounds) -> bounds.contains(pointer) }?.first
                ?: targets.minByOrNull { (_, bounds) ->
                    val dx = pointer.x - pointer.x.coerceIn(bounds.left, bounds.right)
                    val dy = pointer.y - pointer.y.coerceIn(bounds.top, bounds.bottom)
                    dx * dx + dy * dy
                }?.first
        }
    }
    val edgeSize = with(density) { 36.dp.toPx() }
    val scrollDirection by remember(selection, edgeSize) {
        derivedStateOf {
            val pointer = drag?.pointer ?: return@derivedStateOf 0
            if (!attachmentViewport.contains(pointer)) return@derivedStateOf 0
            val edge = minOf(edgeSize, attachmentViewport.height / 3f)
            when {
                pointer.y < attachmentViewport.top + edge -> -1
                pointer.y > attachmentViewport.bottom - edge -> 1
                else -> 0
            }
        }
    }
    LaunchedEffect(attachmentScroll, scrollDirection, density) {
        if (scrollDirection == 0) return@LaunchedEffect
        val pixelsPerMs = with(density) { .45.dp.toPx() }
        var lastFrame = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            val elapsedMs = ((frame - lastFrame) / 1_000_000f).coerceAtMost(32f)
            lastFrame = frame
            if (attachmentScroll.scrollBy(elapsedMs * pixelsPerMs * scrollDirection) == 0f) break
        }
    }
    fun appendSources(added: List<Uri>) {
        if (added.isEmpty()) return
        val combined = (uris + added).distinct()
        if (combined.size > 10) error = R.string.ui_attachment_limit
        else {
            uris = combined
            error = null
        }
    }
    val addFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::appendSources)
    val addMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10), ::appendSources)
    val cancel: () -> Unit = {
        if (sending) {
            activePreparationId?.let(onCancelPreparation)
        } else {
            val changed = captions.toMap() != selection.initialCaptions || uris != selection.uris || edits.isNotEmpty() ||
                trims.isNotEmpty() || options != selection.initial || asFile != selection.initialAsFile ||
                !asAlbum || compressPhotos || compressVideos
            if (changed) confirmDiscard = true else onDismiss()
        }
    }
    val loaded = remember(selection) { mutableStateMapOf<Uri, SelectedMedia>() }
    var previewRetry by remember(selection) { mutableIntStateOf(0) }
    var failedSendSources by remember(selection) { mutableStateOf(emptyList<Uri>()) }
    var mounted by remember(selection) { mutableStateOf(true) }
    var replacementTarget by remember(selection) { mutableStateOf<Uri?>(null) }
    fun clearSourceEdits(uri: Uri) {
        selection.replaceEdit(uri, null)
        edits.remove(uri)
        trims.remove(uri)
        attachmentBounds.remove(uri)
    }
    fun removeSource(uri: Uri) {
        clearSourceEdits(uri)
        captions.remove(uri)
        if (selectedCaption == uri) selectedCaption = null
        uris = uris - uri
        error = null
    }
    val replaceFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { replacement ->
        val previous = replacementTarget
        replacementTarget = null
        if (replacement == null || previous == null || !mounted || sending) return@rememberLauncherForActivityResult
        val position = uris.indexOf(previous)
        if (position < 0) return@rememberLauncherForActivityResult
        if (replacement != previous && replacement in uris) {
            error = R.string.ui_attachment_already_selected
            return@rememberLauncherForActivityResult
        }
        clearSourceEdits(previous)
        if (replacement != previous) {
            captions.remove(previous)?.let { captions[replacement] = it }
            if (selectedCaption == previous) selectedCaption = replacement
        }
        loaded.remove(previous)
        loaded.remove(replacement)
        uris = uris.toMutableList().apply { set(position, replacement) }
        previewRetry++
        error = null
    }
    LaunchedEffect(selection, uris.toSet(), previewRetry) {
        loaded.keys.filterNot { it in uris }.forEach { loaded.remove(it) }
        val owner = selection.retain()
        try {
            for (uri in uris) {
                if (!loaded.containsKey(uri)) {
                    val details = withContext(Dispatchers.IO) { inspectMedia(context, uri, 512) }
                    loaded[uri] = details
                }
            }
        } finally {
            owner.close()
        }
    }
    LaunchedEffect(selection, failedSendSources) {
        if (failedSendSources.isEmpty()) return@LaunchedEffect
        val owner = selection.retain()
        try {
            for (uri in failedSendSources) {
                val previous = loaded[uri] ?: continue
                val current = withContext(Dispatchers.IO) { inspectMedia(context, uri) }
                if (current.error != null && uri in uris && loaded[uri] === previous && uri !in edits) {
                    loaded[uri] = current.copy(name = previous.name, size = current.size ?: previous.size)
                }
            }
        } finally {
            owner.close()
            failedSendSources = emptyList()
        }
    }
    fun reportPreparationFailure() {
        preparationFailed = true
        error = R.string.ui_attachment_send_failed
        failedSendSources = uris.filterNot { it in edits }
    }
    val revalidating = failedSendSources.isNotEmpty()
    val loading = uris.any { loaded[it] == null } || revalidating
    val unavailable = uris.filter { loaded[it]?.error != null }
    val sourceSizes = uris.mapNotNull { uri ->
        edits[uri]?.size?.toLong() ?: loaded[uri]?.takeIf { it.error == null }?.size
    }
    val ready = !sending && !revalidating && uris.isNotEmpty() && uris.all { loaded[it]?.let { media -> media.error == null } == true }
    val albumEligible = !asFile && uris.size >= 2 && uris.all { loaded[it]?.visualMedia == true }
    val albumPreview = albumEligible && asAlbum
    val canCompressPhotos = !asFile && uris.any { loaded[it]?.compressiblePhoto == true }
    val canCompressVideos = !asFile && uris.any { loaded[it]?.video == true }
    val captionUri = selectedCaption?.takeIf { it in uris } ?: uris.firstOrNull()

    fun move(index: Int, destination: Int) {
        if (index !in uris.indices || destination !in uris.indices || index == destination) return
        uris = uris.toMutableList().apply { add(destination, removeAt(index)) }
    }

    DisposableEffect(selection) { onDispose { mounted = false; selection.close() } }
    if (showOptions) SendOptionsDialog(options, onDismiss = { showOptions = false }, onConfirm = {
        options = it
        showOptions = false
    })
    Dialog(
        onDismissRequest = cancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn)
    ) {
        val colors = LocalMessagingColors.current
        Surface(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).widthIn(max = 680.dp).fillMaxWidth(.94f)
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .86f),
            shape = MaterialTheme.shapes.large,
            color = colors.chrome,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(Modifier.imePadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (uris.size <= 1) uiString(R.string.ui_send_attachment) else uiString(R.string.ui_send_attachments, uris.size),
                        style = MaterialTheme.typography.titleLarge)
                    Text(uiString(R.string.ui_recipient, selection.recipient), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (!loading && uris.size > 1) {
                        val total = if (sourceSizes.isEmpty()) null
                            else android.text.format.Formatter.formatShortFileSize(context, sourceSizes.sum())
                        val summary = when {
                            total == null -> uiString(R.string.ui_attachment_size_unknown)
                            sourceSizes.size < uris.size -> uiString(R.string.ui_attachment_source_size_partial, total)
                            else -> uiString(R.string.ui_attachment_source_size, total)
                        }
                        Text(summary, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val recording = uris.singleOrNull()?.takeIf {
                    loaded[it]?.mime?.startsWith("audio/") == true && RecordingMediaStore.isRecording(context, it)
                }
                Column(
                    Modifier.weight(1f, fill = false)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(colors.timeline)
                        .padding(8.dp)
                        .onGloballyPositioned { attachmentViewport = it.boundsInRoot() }
                        .verticalScroll(attachmentScroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (loading) {
                        if (revalidating) Text(uiString(R.string.ui_attachment_rechecking_sources),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (recording != null && !sending) {
                        AttachmentRecordingPlayback(selection, recording, optionsOpen = showOptions)
                    }
                    val attachmentRow: @Composable (Int, Uri, Modifier) -> Unit = { index, uri, rowModifier ->
                        key(uri) {
                            val media = loaded[uri]
                            val edited = edits[uri]
                            val trim = trims[uri]
                            val preview = edited?.preview ?: media?.image
                            val name = edited?.name ?: media?.name ?: "Attachment"
                            val photo = media?.takeIf {
                                it.compressiblePhoto && it.image != null && (it.size ?: 0) <= 32L * 1024 * 1024
                            }
                            val video = media?.video == true && media.error == null
                            val openEditor: (() -> Unit)? = when {
                                photo != null -> { { showPhotoEditor(photo) } }
                                video -> { { showVideoEditor(uri) } }
                                else -> null
                            }
                            val previewAction = if (openEditor != null) {
                                val label = uiString(if (video) R.string.ui_edit_video else R.string.ui_edit_photo)
                                Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = openEditor)
                                    .semantics { contentDescription = name }
                            } else Modifier
                            Surface(
                                modifier = rowModifier
                                    .onGloballyPositioned {
                                        val origin = it.positionInRoot()
                                        attachmentBounds[uri] = Rect(
                                            origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height
                                        )
                                    }
                                    .alpha(if (draggedUri == uri) .55f else 1f),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = when {
                                    dropTarget == uri && draggedUri != uri -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                    selectedCaption == uri && uris.size > 1 -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                    else -> null
                                }
                            ) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
                                    if (albumPreview) {
                                        AttachmentThumbnail(preview, video, index + 1,
                                            uiString(R.string.ui_attachment_preview_position, index + 1, uris.size),
                                            Modifier.fillMaxWidth().height(
                                                if (index == 0 && uris.size % 2 != 0) 156.dp else 108.dp
                                            ).then(previewAction))
                                        Spacer(Modifier.height(5.dp))
                                    } else if (uris.size == 1 && preview != null) {
                                        Image(preview.asImageBitmap(), contentDescription = null,
                                            modifier = Modifier.fillMaxWidth().heightIn(max = 224.dp)
                                                .clip(MaterialTheme.shapes.small).then(previewAction),
                                            contentScale = ContentScale.Fit)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (uris.size > 1) {
                                            var handleOrigin by remember { mutableStateOf(Offset.Zero) }
                                            Icon(
                                                Icons.Default.DragHandle, uiString(R.string.ui_drag_to_reorder),
                                                Modifier.size(40.dp).padding(8.dp)
                                                    .onGloballyPositioned { handleOrigin = it.positionInRoot() }
                                                    .pointerInput(uri, haptic) {
                                                        try {
                                                            detectDragGesturesAfterLongPress(
                                                                onDragStart = { touch ->
                                                                    drag = AttachmentDrag(uri, handleOrigin + touch)
                                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                },
                                                                onDrag = { change, delta ->
                                                                    change.consume()
                                                                    drag?.takeIf { it.source == uri }?.let {
                                                                        drag = it.copy(pointer = it.pointer + delta)
                                                                    }
                                                                },
                                                                onDragEnd = {
                                                                    if (drag?.source == uri) {
                                                                        val destination = dropTarget
                                                                        drag = null
                                                                        if (destination != null) move(uris.indexOf(uri), uris.indexOf(destination))
                                                                    }
                                                                },
                                                                onDragCancel = { if (drag?.source == uri) drag = null }
                                                            )
                                                        } finally {
                                                            if (drag?.source == uri) drag = null
                                                        }
                                                    }
                                            )
                                        }
                                        if (!albumPreview && (uris.size > 1 || preview == null)) {
                                            val position = if (uris.size > 1) {
                                                uiString(R.string.ui_attachment_preview_position, index + 1, uris.size)
                                            } else null
                                            AttachmentThumbnail(preview, video, index + 1, position,
                                                Modifier.size(52.dp).then(previewAction))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            val details = when {
                                                trim != null -> "${videoTrimTime(trim.startMs)} – ${videoTrimTime(trim.endMs)}" +
                                                    if (trim.muteAudio) " · ${uiString(R.string.ui_audio_removed)}" else ""
                                                else -> (edited?.size?.toLong() ?: media?.size)?.let {
                                                    android.text.format.Formatter.formatShortFileSize(context, it)
                                                }
                                            }
                                            if (details != null) Text(details, style = MaterialTheme.typography.bodySmall)
                                            media?.error?.let { Text(uiString(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                                        }
                                        if (!albumPreview) {
                                            IconButton(onClick = { removeSource(uri) }) {
                                                Icon(Icons.Default.Close, uiString(R.string.ui_remove_attachment))
                                            }
                                        }
                                    }
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        if (media?.error != null) {
                                            TextButton(onClick = { loaded.remove(uri); previewRetry++ }) { Text(uiString(R.string.ui_retry)) }
                                            TextButton(enabled = replacementTarget == null, onClick = {
                                                replacementTarget = uri
                                                replaceFile.launch(arrayOf("*/*"))
                                            }) { Text(uiString(R.string.ui_replace_attachment)) }
                                        }
                                        if (uris.size > 1) {
                                            IconButton(enabled = index > 0, onClick = { move(index, index - 1) }) {
                                                Icon(Icons.Default.KeyboardArrowUp, uiString(R.string.ui_move_up))
                                            }
                                            IconButton(enabled = index < uris.lastIndex, onClick = { move(index, index + 1) }) {
                                                Icon(Icons.Default.KeyboardArrowDown, uiString(R.string.ui_move_down))
                                            }
                                        }
                                        if (albumPreview) {
                                            IconButton(onClick = { removeSource(uri) }) {
                                                Icon(Icons.Default.Close, uiString(R.string.ui_remove_attachment))
                                            }
                                        }
                                        if (photo != null) {
                                            TextButton(onClick = { showPhotoEditor(photo) }) {
                                                Text(uiString(R.string.ui_edit_photo))
                                            }
                                            if (edited != null) TextButton(onClick = {
                                                selection.replaceEdit(uri, null)
                                                edits.remove(uri)
                                            }) { Text(uiString(R.string.ui_undo_edit)) }
                                        }
                                        if (video) {
                                            TextButton(onClick = { showVideoEditor(uri) }) { Text(uiString(R.string.ui_edit_video)) }
                                            if (trim != null) TextButton(onClick = { trims.remove(uri) }) { Text(uiString(R.string.ui_undo_video_edits)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().animateContentSize(tween(180)),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (albumPreview) {
                            val wideFirst = uris.size % 2 != 0
                            if (wideFirst) attachmentRow(0, uris.first(), Modifier.fillMaxWidth())
                            uris.withIndex().drop(if (wideFirst) 1 else 0).chunked(2).forEach { pair ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { (index, uri) -> attachmentRow(index, uri, Modifier.weight(1f)) }
                                }
                            }
                        } else {
                            uris.forEachIndexed { index, uri -> attachmentRow(index, uri, Modifier.fillMaxWidth()) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (unavailable.isNotEmpty()) {
                            TextButton(onClick = { unavailable.forEach(::removeSource) }) { Text(uiString(R.string.ui_remove_unavailable_attachments)) }
                        }
                        TextButton(enabled = uris.size < 10, onClick = {
                            addMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }) { Text(uiString(R.string.ui_add_photos_and_videos)) }
                        TextButton(enabled = uris.size < 10, onClick = { addFiles.launch(arrayOf("*/*")) }) { Text(uiString(R.string.ui_add_files)) }
                    }
                }
                HorizontalDivider(color = colors.divider)
                if (uris.size > 1 && captionUri != null) {
                    Box {
                        TextButton(onClick = { showCaptionTargets = true }) {
                            Text(uiString(R.string.ui_caption_for_attachment, uris.indexOf(captionUri) + 1,
                                loaded[captionUri]?.name ?: uiString(R.string.ui_attachment_preview_position,
                                    uris.indexOf(captionUri) + 1, uris.size)),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = showCaptionTargets, onDismissRequest = { showCaptionTargets = false },
                            properties = PopupProperties(focusable = true, securePolicy = SecureFlagPolicy.SecureOn)) {
                            uris.forEachIndexed { index, uri ->
                                DropdownMenuItem(
                                    modifier = Modifier.semantics { selected = uri == captionUri },
                                    text = { Text("${index + 1}. ${loaded[uri]?.name.orEmpty()}",
                                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        selectedCaption = uri
                                        showCaptionTargets = false
                                    }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    captions[captionUri].orEmpty(), { text ->
                        captionUri?.let {
                            selectedCaption = it
                            if (text.isEmpty()) captions.remove(it) else captions[it] = text
                        }
                    }, enabled = captionUri != null, label = { Text(uiString(R.string.ui_caption)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3
                )
                error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { showMediaOptions = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = uiString(R.string.ui_attachment_options))
                            }
                            DropdownMenu(
                                expanded = showMediaOptions,
                                onDismissRequest = { showMediaOptions = false },
                                properties = PopupProperties(focusable = true, securePolicy = SecureFlagPolicy.SecureOn)
                            ) {
                                AttachmentOption(R.string.ui_send_as_file, asFile) { asFile = !asFile }
                                if (albumEligible) AttachmentOption(R.string.ui_send_as_album, asAlbum) { asAlbum = !asAlbum }
                                if (canCompressPhotos) AttachmentOption(R.string.ui_compress_photos, compressPhotos) { compressPhotos = !compressPhotos }
                                if (canCompressVideos) AttachmentOption(R.string.ui_compress_videos, compressVideos) { compressVideos = !compressVideos }
                            }
                        }
                        TextButton(onClick = { showOptions = true }, modifier = Modifier.widthIn(max = 165.dp)) {
                            Text(sendOptionsLabel(options).ifEmpty { uiString(R.string.ui_send_options) },
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = cancel) { Text(uiString(R.string.ui_cancel)) }
                        Button(enabled = ready && draggedUri == null, onClick = {
                            val longCaption = uris.firstOrNull { captions[it].orEmpty().toByteArray(Charsets.UTF_8).size > 48 * 1024 }
                            if (longCaption != null) {
                                selectedCaption = longCaption
                                error = R.string.ui_caption_too_long
                                return@Button
                            }
                            if (options.sendAt?.let { it <= System.currentTimeMillis() } == true) {
                                error = R.string.ui_choose_future
                                return@Button
                            }
                            error = null
                            preparationFailed = false
                            activePreparationId = null
                            sending = true
                            try { selection.submit(uris, MediaSendChoice(
                                options = options, asFile = asFile, asAlbum = asAlbum && albumEligible,
                                compressPhotos = compressPhotos && canCompressPhotos, compressVideos = compressVideos && canCompressVideos,
                                videoTrims = trims.toMap(), itemCaptions = uris.map { captions[it].orEmpty() }
                            ), started = { activePreparationId = it }) { result ->
                                if (mounted) {
                                    sending = false
                                    when (result) {
                                        PreparationSendResult.QUEUED -> onQueued()
                                        PreparationSendResult.FAILED -> reportPreparationFailure()
                                        PreparationSendResult.CANCELLED -> Unit
                                    }
                                }
                            } } catch (_: Exception) {
                                sending = false
                                reportPreparationFailure()
                            }
                        }) {
                            Text(when {
                                preparationFailed -> uiString(R.string.ui_send_retry)
                                uris.size > 1 -> uiString(R.string.ui_send_count, uris.size)
                                else -> uiString(R.string.ui_send)
                            })
                        }
                    }
                }
            }
        }
    }
    if (sending) {
        Dialog(
            onDismissRequest = cancel,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
                securePolicy = SecureFlagPolicy.SecureOn
            )
        ) {
            Surface(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                    .widthIn(max = 480.dp).fillMaxWidth(.9f),
                shape = MaterialTheme.shapes.large
            ) {
                val active = preparations.filter { it.id == activePreparationId }
                Column(Modifier.padding(16.dp)) {
                    if (active.isNotEmpty()) {
                        active.first().progress.takeIf { it.phase == "preparing" && uris.size > 1 }
                            ?.let { progress -> uris.getOrNull(progress.attachment - 1) }
                            ?.let { uri -> edits[uri]?.name ?: loaded[uri]?.name }?.let { name ->
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge)
                            }
                        PreparationRows(active, onCancelPreparation)
                    } else {
                        Text(uiString(R.string.ui_preparation_waiting))
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(uiString(R.string.ui_discard_attachment_caption)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(uiString(R.string.ui_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(uiString(R.string.ui_keep_editing)) }
            }
        )
    }
    editing?.let { media ->
        PhotoEditDialog(selection, media.uri, media.name, media.mime, edits[media.uri]?.adjustment ?: PhotoAdjustment(),
            onDismiss = { editing = null }, onApply = { edit ->
            selection.replaceEdit(media.uri, edit?.source)
            if (edit == null) edits.remove(media.uri) else edits[media.uri] = edit
            editing = null
        })
    }
    trimming?.let { uri ->
        VideoTrimDialog(selection, uri, trims[uri], onDismiss = { trimming = null }, onApply = { trim ->
            if (trim == null) trims.remove(uri) else trims[uri] = trim
            trimming = null
        })
    }
}

@Composable
private fun AttachmentThumbnail(
    preview: Bitmap?,
    video: Boolean,
    number: Int,
    position: String?,
    modifier: Modifier
) {
    Box(
        modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        if (preview != null) {
            Image(preview.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (video) {
                Icon(Icons.Default.PlayArrow, null,
                    modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).size(16.dp)
                        .background(Color.Black.copy(alpha = .55f), CircleShape),
                    tint = Color.White)
            }
        } else {
            Icon(if (video) Icons.Default.Videocam else Icons.AutoMirrored.Filled.InsertDriveFile, null)
        }
        if (position != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .semantics { contentDescription = position },
                contentAlignment = Alignment.Center
            ) {
                Text(number.toString(), color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

@Composable
private fun AttachmentRecordingPlayback(selection: MediaConfirmation, uri: Uri, optionsOpen: Boolean) {
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val manager = remember { org.msgat.core.call.CallManager.getInstance() }
    val callState by manager.callState.collectAsState()
    val callActive = callState != org.msgat.core.call.CallState.IDLE && callState != org.msgat.core.call.CallState.ENDED
    val previewBlocked = callActive || optionsOpen
    var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(uri) { mutableStateOf(false) }
    var duration by remember(uri) { mutableIntStateOf(0) }
    var position by remember(uri) { mutableIntStateOf(0) }
    var scrub by remember(uri) { mutableStateOf<Float?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    var attempt by remember(uri) { mutableIntStateOf(0) }

    DisposableEffect(selection, uri, lifecycle, attempt) {
        val owner = selection.retain()
        val media = MediaPlayer()
        var reader: RecordingReader? = null
        player = media
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && playing) {
                media.pause()
                playing = false
            }
        }
        lifecycle.addObserver(observer)
        media.setOnPreparedListener {
            if (player === it) {
                duration = it.duration.coerceAtLeast(0)
                position = position.coerceIn(0, duration)
                if (position > 0) it.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
            }
        }
        media.setOnCompletionListener {
            if (player === it) { playing = false; position = 0; it.seekTo(0) }
        }
        media.setOnErrorListener { failedPlayer, _, _ ->
            if (player === failedPlayer) { failed = true; playing = false }
            true
        }
        try {
            media.setDataSource(RecordingReader(context, uri).also { reader = it })
            media.prepareAsync()
        } catch (_: Exception) { failed = true }
        onDispose {
            lifecycle.removeObserver(observer)
            player = null
            try { media.release() } finally { try { reader?.close() } finally { owner.close() } }
        }
    }
    LaunchedEffect(previewBlocked) {
        if (previewBlocked && playing) {
            player?.pause()
            playing = false
        }
    }
    LaunchedEffect(player, playing) {
        val media = player ?: return@LaunchedEffect
        while (playing) {
            position = media.currentPosition
            kotlinx.coroutines.delay(150)
        }
    }
    if (failed) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(uiString(R.string.ui_record_preview_unavailable), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !previewBlocked, onClick = {
                failed = false
                duration = 0
                scrub = null
                attempt++
            }) { Text(uiString(R.string.ui_retry)) }
        }
    } else Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = duration > 0 && !previewBlocked, onClick = {
            player?.let { media ->
                if (playing) {
                    media.pause()
                    position = media.currentPosition
                } else media.start()
                playing = !playing
            }
        }) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                uiString(if (playing) R.string.ui_pause else R.string.ui_play_audio))
        }
        val seekLabel = uiString(R.string.ui_record_playback_position)
        Column(Modifier.weight(1f)) {
        Slider(
            value = (scrub ?: position.toFloat()).coerceIn(0f, duration.toFloat()),
            valueRange = 0f..maxOf(1, duration).toFloat(),
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                scrub?.let { target ->
                    position = target.toInt()
                    player?.seekTo(position)
                }
                scrub = null
            },
            enabled = duration > 0 && !previewBlocked,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = seekLabel }
        )
        if (duration > 0) {
            val elapsed = scrub?.toLong() ?: position.toLong()
            Text("${recordingTime(elapsed)} / ${recordingTime(duration.toLong())}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
    }
}
