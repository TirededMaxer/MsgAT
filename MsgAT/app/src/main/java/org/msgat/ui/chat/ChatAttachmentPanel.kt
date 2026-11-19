// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.chat

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.msgat.R
import org.msgat.ui.theme.LocalMessagingColors
import org.msgat.ui.theme.uiString

private const val MaxAttachments = 10
private const val RecentMediaLimit = 90
private val GalleryThumbnailSize = Size(256, 256)
private val GalleryPreviewSize = Size(1280, 1280)
private val attachmentEaseOut = Easing { progress ->
    val remainder = 1f - progress
    1f - remainder * remainder * remainder * remainder * remainder
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun ChatActionSheet(
    visibility: MutableTransitionState<Boolean>,
    closeLabel: String,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!visibility.currentState && !visibility.targetState && visibility.isIdle) return
    val dismiss by rememberUpdatedState(onDismiss)
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOn, dismissOnClickOutside = false)
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        AnimatedVisibility(visibleState = visibility, enter = EnterTransition.None, exit = ExitTransition.None) {
            var dragging by remember { mutableStateOf(false) }
            var dragOffset by remember { mutableFloatStateOf(0f) }
            val translated by animateFloatAsState(if (dragging) dragOffset else 0f,
                animationSpec = if (dragging) snap() else spring(dampingRatio = .75f, stiffness = 350f),
                label = "Attachment drag")
            LaunchedEffect(visibility.targetState) {
                if (visibility.targetState) { dragOffset = 0f; dragging = false }
            }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.matchParentSize()
                    .animateEnterExit(
                        enter = fadeIn(tween(400, delayMillis = 20, easing = attachmentEaseOut)),
                        exit = fadeOut(tween(180)))
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = .40f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        enabled = dismissEnabled, onClickLabel = closeLabel, onClick = onDismiss))
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
                    val height = (maxHeight * .82f).coerceAtMost(640.dp)
                    Surface(
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().height(height)
                            .animateEnterExit(
                                enter = slideInVertically(spring(dampingRatio = .75f, stiffness = 350f)) { it },
                                exit = slideOutVertically(tween(200, easing = attachmentEaseOut)) { it })
                            .graphicsLayer { translationY = translated }
                            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        color = LocalMessagingColors.current.chrome,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().height(42.dp)) {
                                Box(Modifier.matchParentSize().pointerInput(visibility.targetState, dismissEnabled) {
                                    if (!visibility.targetState || !dismissEnabled) return@pointerInput
                                    val threshold = 72.dp.toPx()
                                    detectVerticalDragGestures(
                                        onDragStart = { dragging = true },
                                        onDragCancel = { dragging = false; dragOffset = 0f },
                                        onDragEnd = {
                                            if (dragOffset >= threshold) dismiss()
                                            else { dragging = false; dragOffset = 0f }
                                        }
                                    ) { change, delta ->
                                        change.consume()
                                        dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                                    }
                                }, contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(32.dp, 4.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
                                }
                                IconButton(onClick = onDismiss, enabled = visibility.targetState && dismissEnabled, modifier = Modifier.align(Alignment.CenterEnd)) {
                                    Icon(Icons.Default.Close, contentDescription = closeLabel)
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                        }
                    }
                }
            }
        }
    }
}

private data class LocalAttachment(val uri: Uri, val addedAt: Long, val video: Boolean, val name: String, val duration: Long)
private data class LocalAudio(val uri: Uri, val title: String, val artist: String, val duration: Long)
internal enum class AttachmentSection { Gallery, Files, Audio }

internal class AttachmentPanelState {
    var section by mutableStateOf(AttachmentSection.Gallery)
    var gallerySelection by mutableStateOf<List<Uri>>(emptyList())
    var audioSelection by mutableStateOf<List<Uri>>(emptyList())
    val galleryScroll = LazyGridState()
    val audioScroll = LazyListState()
    var galleryPreview by mutableStateOf<Uri?>(null)
    var galleryRestore: Pair<Int, Int>? = null
    var audioRestore: Pair<Int, Int>? = null

    fun rememberReviewPosition() {
        when (section) {
            AttachmentSection.Gallery -> galleryRestore = galleryScroll.firstVisibleItemIndex to galleryScroll.firstVisibleItemScrollOffset
            AttachmentSection.Audio -> audioRestore = audioScroll.firstVisibleItemIndex to audioScroll.firstVisibleItemScrollOffset
            AttachmentSection.Files -> Unit
        }
    }
}

private fun galleryPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun Context.grants(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun readRecentAttachments(context: Context): List<LocalAttachment> {
    val partial = Build.VERSION.SDK_INT >= 34 && context.grants(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    val legacy = Build.VERSION.SDK_INT < 33 && context.grants(Manifest.permission.READ_EXTERNAL_STORAGE)
    val imageAccess = legacy || partial || (Build.VERSION.SDK_INT >= 33 && context.grants(Manifest.permission.READ_MEDIA_IMAGES))
    val videoAccess = legacy || partial || (Build.VERSION.SDK_INT >= 33 && context.grants(Manifest.permission.READ_MEDIA_VIDEO))
    val results = buildList {
        if (imageAccess) addAll(readMediaCollection(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, video = false))
        if (videoAccess) addAll(readMediaCollection(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video = true))
    }
    return results.sortedByDescending { it.addedAt }.take(RecentMediaLimit)
}

private fun readMediaCollection(resolver: ContentResolver, collection: Uri, video: Boolean): List<LocalAttachment> {
    val columns = arrayOf(android.provider.BaseColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME)
    val projection = if (video) columns + MediaStore.Video.VideoColumns.DURATION else columns
    val arguments = Bundle().apply {
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED, android.provider.BaseColumns._ID))
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        putInt(ContentResolver.QUERY_ARG_LIMIT, RecentMediaLimit)
    }
    return resolver.query(collection, projection, arguments, null)?.use { cursor ->
        buildList {
            while (size < RecentMediaLimit && cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                add(LocalAttachment(uri, cursor.getLong(1), video, cursor.getString(2).orEmpty(), if (video) cursor.getLong(3) else 0L))
            }
        }
    } ?: emptyList()
}

private fun readRecentAudio(resolver: ContentResolver): List<LocalAudio> {
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val columns = arrayOf(android.provider.BaseColumns._ID, MediaStore.Audio.AudioColumns.TITLE,
        MediaStore.Audio.AudioColumns.ARTIST, MediaStore.Audio.AudioColumns.DURATION, MediaStore.MediaColumns.DISPLAY_NAME)
    val arguments = Bundle().apply {
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED, android.provider.BaseColumns._ID))
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        putInt(ContentResolver.QUERY_ARG_LIMIT, RecentMediaLimit)
    }
    return resolver.query(collection, columns, arguments, null)?.use { cursor ->
        buildList {
            while (size < RecentMediaLimit && cursor.moveToNext()) {
                add(LocalAudio(ContentUris.withAppendedId(collection, cursor.getLong(0)),
                    cursor.getString(1).orEmpty().ifBlank { cursor.getString(4).orEmpty() },
                    cursor.getString(2).orEmpty().takeUnless { it == "<unknown>" }.orEmpty(), cursor.getLong(3)))
            }
        }
    }.orEmpty()
}

@Composable
internal fun ChatAttachmentPanel(
    state: AttachmentPanelState,
    foreground: Boolean,
    acceptingInput: Boolean,
    captureAllowed: Boolean,
    onTakePhoto: () -> Unit,
    onRecordVideo: () -> Unit,
    onBrowseGallery: () -> Unit,
    onPickFiles: () -> Unit,
    onPickAudio: () -> Unit,
    onRecordVoice: () -> Unit,
    onStickers: () -> Unit,
    onContact: () -> Unit,
    onLocation: () -> Unit,
    onReview: (List<Uri>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shift = with(LocalDensity.current) { 78.dp.toPx() }
    val sectionOffset = remember { Animatable(0f) }
    val section = state.section
    var requestedSection by remember(state) { mutableStateOf(state.section) }
    val switching = section != requestedSection || sectionOffset.isRunning || sectionOffset.value != 0f
    val interactive = acceptingInput && !switching
    fun openSection(value: AttachmentSection) {
        if (!interactive) return
        if (value != section) requestedSection = value
        else when(value) {
            AttachmentSection.Gallery -> scope.launch { state.galleryScroll.animateScrollToItem(0) }
            AttachmentSection.Audio -> scope.launch { state.audioScroll.animateScrollToItem(0) }
            AttachmentSection.Files -> Unit
        }
    }
    LaunchedEffect(requestedSection, shift) {
        if (requestedSection != section) {
            sectionOffset.animateTo(shift, tween(180))
            state.section = requestedSection
        }
        sectionOffset.animateTo(0f, spring(dampingRatio = .75f, stiffness = 500f))
    }
    val permissions = remember { galleryPermissions() }
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var refresh by remember { mutableIntStateOf(0) }
    val requestAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    var audioRefresh by remember { mutableIntStateOf(0) }
    val requestAudioAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { audioRefresh++ }
    val selection = state.gallerySelection
    val audioSelection = state.audioSelection
    var entries by remember { mutableStateOf<List<LocalAttachment>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var galleryLoaded by remember { mutableStateOf(false) }
    var galleryError by remember { mutableStateOf(false) }
    var canChooseAccess by remember { mutableStateOf(true) }
    var audioEntries by remember { mutableStateOf<List<LocalAudio>>(emptyList()) }
    var audioLoading by remember { mutableStateOf(false) }
    var audioError by remember { mutableStateOf(false) }
    var audioGranted by remember { mutableStateOf(false) }
    LaunchedEffect(foreground, refresh) {
        if (!foreground) {
            loading = false
            galleryLoaded = false
            return@LaunchedEffect
        }
        val anyGrant = permissions.any { context.grants(it) }
        canChooseAccess = permissions.filter { it != Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED }.any { !context.grants(it) }
        loading = anyGrant
        galleryError = false
        galleryLoaded = false
        val current = try {
            withContext(Dispatchers.IO) { readRecentAttachments(context) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            galleryError = true
            loading = false
            return@LaunchedEffect
        }
        entries = current
        val accessible = current.mapTo(HashSet()) { it.uri }
        state.gallerySelection = state.gallerySelection.filter { it in accessible }
        state.galleryPreview?.let { if (it !in accessible) state.galleryPreview = null }
        loading = false
        galleryLoaded = true
    }
    LaunchedEffect(section, foreground, galleryLoaded, entries) {
        if (section == AttachmentSection.Gallery && foreground && galleryLoaded) state.galleryRestore?.let { (index, offset) ->
            withFrameNanos { }
            state.galleryScroll.scrollToItem(index.coerceAtMost(entries.size + 2), offset)
            state.galleryRestore = null
        }
    }
    LaunchedEffect(section, foreground, audioRefresh, refresh) {
        if (!foreground || section != AttachmentSection.Audio) return@LaunchedEffect
        audioGranted = context.grants(audioPermission)
        audioError = false
        if (!audioGranted) {
            audioEntries = emptyList()
            state.audioSelection = emptyList()
            return@LaunchedEffect
        }
        audioLoading = true
        try {
            audioEntries = withContext(Dispatchers.IO) { readRecentAudio(context.contentResolver) }
            val accessible = audioEntries.mapTo(HashSet()) { it.uri }
            state.audioSelection = state.audioSelection.filter { it in accessible }
            state.audioRestore?.let { (index, offset) ->
                withFrameNanos { }
                state.audioScroll.scrollToItem(index.coerceAtMost(audioEntries.size), offset)
                state.audioRestore = null
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { audioError = true }
        finally { audioLoading = false }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            Column(Modifier.fillMaxSize().graphicsLayer { translationY = sectionOffset.value }) {
                when (section) {
                    AttachmentSection.Gallery -> {
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth().weight(1f), state = state.galleryScroll,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            item(key = "access", span = { GridItemSpan(maxLineSpan) }) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item { TextButton(enabled = interactive, onClick = onBrowseGallery) { Text(uiString(R.string.ui_attachment_browse_gallery)) } }
                                    if (canChooseAccess) item {
                                        TextButton(enabled = interactive, onClick = { requestAccess.launch(permissions) }) {
                                            Text(uiString(R.string.ui_attachment_gallery_access))
                                        }
                                    }
                                }
                            }
                            if (galleryError) item(key = "gallery-error", span = { GridItemSpan(maxLineSpan) }) {
                                Column {
                                    AttachmentPanelHint(uiString(R.string.ui_attachment_gallery_unavailable))
                                    TextButton(enabled = interactive, onClick = { refresh++ }) { Text(uiString(R.string.ui_retry)) }
                                }
                            }
                            item(key = "camera") {
                                CaptureTile(uiString(R.string.ui_attachment_camera), Icons.Default.CameraAlt,
                                    captureAllowed && interactive && selection.size < MaxAttachments, onTakePhoto)
                            }
                            item(key = "record-video") {
                                CaptureTile(uiString(R.string.ui_attachment_record_video), Icons.Default.Videocam,
                                    captureAllowed && interactive && selection.size < MaxAttachments, onRecordVideo)
                            }
                            items(entries, key = { it.uri.toString() }) { entry ->
                                val position = selection.indexOf(entry.uri)
                                GalleryThumbnail(entry, position, canPreview = interactive && galleryLoaded,
                                    canSelect = interactive && galleryLoaded && (position >= 0 || selection.size < MaxAttachments),
                                    onPreview = { state.galleryPreview = entry.uri },
                                    onSelect = { toggleGallerySelection(state, entry.uri) })
                            }
                        }
                    }
                    AttachmentSection.Files -> {
                        AttachmentListAction(uiString(R.string.ui_attachment_browse_files), Icons.Default.FolderOpen, interactive, onPickFiles)
                        HorizontalDivider()
                    }
                    AttachmentSection.Audio -> {
                        if (audioLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = state.audioScroll) {
                            item {
                                AttachmentListAction(uiString(R.string.ui_attachment_browse_audio), Icons.Default.FolderOpen, interactive, onPickAudio)
                                AttachmentListAction(uiString(R.string.ui_attachment_record_voice), Icons.Default.Mic, captureAllowed && interactive, onRecordVoice)
                                if (!audioGranted) {
                                    AttachmentListAction(uiString(R.string.ui_attachment_audio_access), Icons.Default.AudioFile, interactive) {
                                        requestAudioAccess.launch(audioPermission)
                                    }
                                }
                                HorizontalDivider()
                            }
                            if (audioError) item {
                                Column {
                                    AttachmentPanelHint(uiString(R.string.ui_attachment_audio_unavailable))
                                    TextButton(enabled = interactive, onClick = { audioRefresh++ }) { Text(uiString(R.string.ui_retry)) }
                                }
                            }
                            else if (audioGranted && !audioLoading && audioEntries.isEmpty()) {
                                item { AttachmentPanelHint(uiString(R.string.ui_attachment_no_audio)) }
                            }
                            items(audioEntries, key = { it.uri.toString() }) { entry ->
                                val position = audioSelection.indexOf(entry.uri)
                                AudioSelectionRow(entry, position, interactive && !audioLoading && !audioError && (position >= 0 || audioSelection.size < MaxAttachments)) {
                                    state.audioSelection = if (position >= 0) audioSelection - entry.uri else audioSelection + entry.uri
                                }
                                HorizontalDivider(Modifier.padding(start = 56.dp))
                            }
                        }
                    }
                }
            }
        }
        val currentSelection = when (section) {
            AttachmentSection.Gallery -> selection
            AttachmentSection.Audio -> audioSelection
            AttachmentSection.Files -> emptyList()
        }
        AnimatedVisibility(
            visible = currentSelection.isNotEmpty(),
            enter = fadeIn(tween(120)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(100)) + slideOutVertically(tween(160)) { it / 2 }
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.CenterEnd) {
                FilledTonalButton(enabled = interactive && currentSelection.isNotEmpty() && when (section) {
                    AttachmentSection.Gallery -> galleryLoaded
                    AttachmentSection.Audio -> !audioLoading && !audioError
                    AttachmentSection.Files -> false
                },
                    onClick = {
                        state.rememberReviewPosition()
                        onReview(currentSelection.toList())
                    }) {
                    Text(uiString(R.string.ui_attachment_review_count, currentSelection.size))
                }
            }
        }
        HorizontalDivider()
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            item { AttachmentAction(uiString(R.string.ui_attachment_gallery), Icons.Default.ImageIcon, interactive, section == AttachmentSection.Gallery) { openSection(AttachmentSection.Gallery) } }
            item { AttachmentAction(uiString(R.string.ui_files), Icons.AutoMirrored.Filled.InsertDriveFile, interactive, section == AttachmentSection.Files) { openSection(AttachmentSection.Files) } }
            item { AttachmentAction(uiString(R.string.ui_audio), Icons.Default.AudioFile, interactive, section == AttachmentSection.Audio) { openSection(AttachmentSection.Audio) } }
            item { AttachmentAction(uiString(R.string.ui_contact_card), Icons.Default.Person, enabled = interactive, onClick = onContact) }
            item { AttachmentAction(uiString(R.string.ui_location), Icons.Default.LocationOn, enabled = interactive, onClick = onLocation) }
            item { AttachmentAction(uiString(R.string.ui_stickers), Icons.Default.Star, enabled = interactive, onClick = onStickers) }
        }
    }

    val preview = state.galleryPreview
    val previewStart = entries.indexOfFirst { it.uri == preview }
    if (preview != null && previewStart >= 0 && acceptingInput && galleryLoaded) {
        GallerySelectionPreview(entries, previewStart, state.gallerySelection, foreground,
            onToggle = { toggleGallerySelection(state, it) },
            onDismiss = { state.galleryPreview = null },
            onReview = { uris ->
                state.galleryPreview = null
                state.rememberReviewPosition()
                onReview(uris)
            })
    }
}

private fun toggleGallerySelection(state: AttachmentPanelState, uri: Uri) {
    val selected = state.gallerySelection
    if (uri in selected) state.gallerySelection = selected - uri
    else if (selected.size < MaxAttachments) state.gallerySelection = selected + uri
}

@Composable
private fun GalleryThumbnail(entry: LocalAttachment, selectionIndex: Int, canPreview: Boolean, canSelect: Boolean,
                             onPreview: () -> Unit, onSelect: () -> Unit) {
    val bitmap = rememberGalleryImage(entry.uri, GalleryThumbnailSize)
    val selected = selectionIndex >= 0
    val fallback = uiString(if (entry.video) R.string.ui_attachment_video else R.string.ui_attachment_photo)
    val label = entry.name.ifBlank { fallback }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = canPreview, role = Role.Button, onClick = onPreview)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val thumbnail = bitmap
        if (thumbnail != null) Image(thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(if (entry.video) Icons.Default.Videocam else Icons.Default.ImageIcon, null)
        if (selected) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f)))
        GallerySelectionButton(selectionIndex, canSelect, Modifier.align(Alignment.TopEnd), onSelect)
        if (entry.video) {
            Row(Modifier.align(Alignment.BottomStart).padding(4.dp)
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .55f), RoundedCornerShape(5.dp))
                .padding(end = if (entry.duration > 0) 5.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color.White)
                if (entry.duration > 0) Text(android.text.format.DateUtils.formatElapsedTime(entry.duration / 1000),
                    style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

@Composable
private fun GallerySelectionButton(position: Int, enabled: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val action = uiString(if (position >= 0) R.string.ui_remove_attachment else R.string.ui_attachment_select_media)
    Box(modifier.size(48.dp).toggleable(position >= 0, enabled = enabled, role = Role.Checkbox) { onSelect() }
        .semantics { contentDescription = action }, contentAlignment = Alignment.Center) {
        if (position >= 0) Badge { Text((position + 1).toString()) }
        else Box(Modifier.size(21.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = .36f), CircleShape)
            .border(1.5.dp, androidx.compose.ui.graphics.Color.White, CircleShape))
    }
}

@Composable
private fun rememberGalleryImage(uri: Uri, size: Size, fullPhoto: Boolean = false): ImageBitmap? {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<ImageBitmap?>(null, uri, size, resolver, fullPhoto) {
        value = withContext(Dispatchers.IO) {
            try {
                val pixels = if (fullPhoto) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                        val width = info.size.width
                        val height = info.size.height
                        require(width > 0 && height > 0 && width.toLong() * height <= 64_000_000)
                        val scale = minOf(1.0, size.width.toDouble() / width, size.height.toDouble() / height)
                        decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else resolver.loadThumbnail(uri, size, null)
                pixels.asImageBitmap()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    return bitmap
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GallerySelectionPreview(
    entries: List<LocalAttachment>,
    start: Int,
    selection: List<Uri>,
    foreground: Boolean,
    onToggle: (Uri) -> Unit,
    onDismiss: () -> Unit,
    onReview: (List<Uri>) -> Unit
) {
    val pager = rememberPagerState(initialPage = start) { entries.size }
    val current = pager.currentPage.coerceIn(entries.indices)
    val entry = entries[current]
    val position = selection.indexOf(entry.uri)
    val back = uiString(R.string.ui_close_media_preview)
    val scope = rememberCoroutineScope()
    var zoomedUri by remember { mutableStateOf<Uri?>(null) }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOn, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                val barColors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Black,
                    navigationIconContentColor = androidx.compose.ui.graphics.Color.White,
                    titleContentColor = androidx.compose.ui.graphics.Color.White)
                TopAppBar(
                    title = {
                        Text(uiString(R.string.ui_attachment_preview_position, current + 1, entries.size),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, back) }
                    },
                    actions = {
                        val controlColor = androidx.compose.ui.graphics.Color.White
                        val controlColors = IconButtonDefaults.iconButtonColors(
                            contentColor = controlColor, disabledContentColor = controlColor.copy(alpha = .38f))
                        IconButton(enabled = current > 0, colors = controlColors,
                            onClick = { scope.launch { pager.animateScrollToPage(current - 1) } }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, uiString(R.string.ui_media_newer))
                        }
                        IconButton(enabled = current < entries.lastIndex, colors = controlColors,
                            onClick = { scope.launch { pager.animateScrollToPage(current + 1) } }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, uiString(R.string.ui_media_older))
                        }
                        GallerySelectionButton(position, position >= 0 || selection.size < MaxAttachments) { onToggle(entry.uri) }
                    },
                    colors = barColors,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
                HorizontalPager(state = pager, key = { entries[it].uri }, modifier = Modifier.weight(1f).fillMaxWidth(),
                    userScrollEnabled = zoomedUri != entry.uri) { page ->
                    val item = entries[page]
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (item.video && foreground && page == pager.settledPage) {
                            GalleryVideoPreview(item.uri, canPlay = !pager.isScrollInProgress)
                        } else if (foreground) {
                            val bitmap = rememberGalleryImage(item.uri, GalleryPreviewSize, fullPhoto = !item.video)
                            if (bitmap != null) {
                                if (item.video) {
                                    Image(bitmap, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                } else {
                                    ZoomablePhoto(item.uri, page == pager.settledPage, onZoomChanged = { zoomed ->
                                        if (zoomed) zoomedUri = item.uri
                                        else if (zoomedUri == item.uri) zoomedUri = null
                                    }) { modifier, onSize, _ ->
                                        LaunchedEffect(bitmap) { onSize(IntSize(bitmap.width, bitmap.height)) }
                                        Image(bitmap, item.name, modifier, contentScale = ContentScale.Fit)
                                    }
                                }
                            } else {
                                Icon(if (item.video) Icons.Default.Videocam else Icons.Default.ImageIcon, item.name,
                                    tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(40.dp))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.name, Modifier.weight(1f), color = androidx.compose.ui.graphics.Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { onReview(selection.ifEmpty { listOf(entry.uri) }) }) {
                        Text(uiString(R.string.ui_attachment_review_count, selection.size.coerceAtLeast(1)))
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryVideoPreview(uri: Uri, canPlay: Boolean) {
    val context = LocalContext.current
    var player by remember(uri) { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    DisposableEffect(uri, context) {
        val active = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true }
        }
        active.addListener(listener)
        try {
            active.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            active.prepare()
            player = active
        } catch (_: IllegalStateException) {
            failed = true
        }
        onDispose {
            player = null
            active.removeListener(listener)
            active.release()
        }
    }
    LaunchedEffect(player, canPlay) { if (!canPlay) player?.pause() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val active = player
        if (failed) Text(uiString(R.string.ui_media_playback_unavailable), color = androidx.compose.ui.graphics.Color.White)
        else if (active != null) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { viewContext -> androidx.media3.ui.PlayerView(viewContext) },
                modifier = Modifier.fillMaxSize(),
                update = { it.player = active }
            )
        } else CircularProgressIndicator()
    }
}

@Composable
private fun CaptureTile(label: String, image: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        AttachmentAction(label, image, enabled = enabled, onClick = onClick)
    }
}

@Composable
private fun AttachmentListAction(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else tint) },
        leadingContent = { Icon(icon, null, tint = tint) },
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    )
}

@Composable
private fun AttachmentPanelHint(message: String) {
    Text(message, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AudioSelectionRow(entry: LocalAudio, position: Int, enabled: Boolean, onSelect: () -> Unit) {
    val isSelected = position >= 0
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) LocalMessagingColors.current.selection else Color.Transparent
        ),
        headlineContent = {
            val fallback = uiString(R.string.ui_audio)
            Text(entry.title.ifBlank { fallback }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = if (entry.artist.isNotEmpty()) {
            { Text(entry.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
        leadingContent = { Icon(Icons.Default.AudioFile, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry.duration > 0) Text(android.text.format.DateUtils.formatElapsedTime(entry.duration / 1000),
                    style = MaterialTheme.typography.labelMedium)
                if (position >= 0) Badge { Text((position + 1).toString()) }
                else Box(Modifier.size(18.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
            }
        },
        modifier = Modifier.toggleable(value = position >= 0, enabled = enabled, role = Role.Checkbox) { onSelect() }
    )
}

@Composable
private fun AttachmentAction(label: String, image: ImageVector, enabled: Boolean = true, active: Boolean? = null, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = if (active == null) Modifier else Modifier.semantics { selected = active },
        colors = ButtonDefaults.textButtonColors(containerColor = if (active == true) MaterialTheme.colorScheme.secondaryContainer
            else androidx.compose.ui.graphics.Color.Transparent),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(image, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
