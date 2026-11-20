// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.msgat.R
import org.msgat.core.media.AttachmentDownloadIntent
import org.msgat.core.media.AttachmentMetadata
import org.msgat.core.network.AttachmentPhase
import org.msgat.core.network.AttachmentProgress
import org.msgat.ui.theme.uiString

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentCard(
    media: AttachmentMetadata?, progress: AttachmentProgress?,
    onOpen: (MediaPhotoOrigin?) -> Unit, onLongClick: () -> Unit,
    albumTile: Boolean = false, onDownload: ((Boolean) -> Unit)? = null,
    galleryTile: Boolean = false, onLongClickLabel: String? = null,
    onPhotoFrame: ((MediaPhotoFrame?) -> Unit)? = null,
    audioPlayback: InlineAudioPlayback? = null,
    onToggleAudio: ((AttachmentMetadata) -> Unit)? = null,
    onRetryUpload: (() -> Unit)? = null,
    retryingUpload: Boolean = false,
    downloadActionBusy: Boolean = false,
    visualFooter: (@Composable () -> Unit)? = null,
    gallerySelected: Boolean? = null,
    onToggleGallerySelection: (() -> Unit)? = null,
    pauseStickerPlayback: Boolean = false,
    captionLinksEnabled: Boolean = true,
    onCaptionLinks: ((List<String>) -> Unit)? = null,
    fileExportAllowed: Boolean? = null
) {
    val compact = albumTile || galleryTile
    val canAnimate = media?.let { it.isImage && !it.isSticker && it.mime != "image/gif" } == true
    var thumbnail by remember(media?.reference) { mutableStateOf<Bitmap?>(null) }
    var thumbnailBounds by remember { mutableStateOf<Rect?>(null) }
    if (onPhotoFrame != null) {
        val report by rememberUpdatedState(onPhotoFrame)
        val frame = thumbnailBounds?.takeIf { canAnimate && media?.available == true && thumbnail != null }
            ?.let { MediaPhotoFrame(checkNotNull(media).reference, it) }
        LaunchedEffect(frame) { report(frame) }
        DisposableEffect(media?.reference) { onDispose { report(null) } }
    }
    Column(if (compact) Modifier.fillMaxWidth() else Modifier.widthIn(max = 250.dp)) {
        val visual = media?.let { it.isImage || it.isVideo } == true
        val fileCard = !compact && media != null && !visual && !media.isAudio && !media.isSticker
        val canSaveFile = fileCard && fileExportAllowed == true && media?.available == true
        val pending = media?.available == false && onDownload != null
        val paused = media?.available == false && media.downloadIntent == AttachmentDownloadIntent.PAUSED
        val requested = media?.available == false && media.downloadIntent == AttachmentDownloadIntent.REQUESTED
        val canPause = pending && !paused && media?.remoteUnavailable == false &&
            (requested || progress?.phase == AttachmentPhase.DOWNLOADING)
        val canDownload = pending && !canPause
        val canRetryDownload = canDownload && !paused &&
            (media?.remoteUnavailable == true || progress?.phase == AttachmentPhase.RETRY)
        val opensPendingPreview = visual && pending && media?.isSticker == false
        val transferFraction = progress?.takeIf {
            it.total > 0 && (it.phase == AttachmentPhase.UPLOADING || it.phase == AttachmentPhase.DOWNLOADING &&
                media?.available == false && !paused && !media.remoteUnavailable)
        }?.let { (it.bytes.toDouble() / it.total).coerceIn(0.0, 1.0).toFloat() }
        val actionLabel = when {
            opensPendingPreview -> uiString(R.string.ui_open_media_preview)
            canPause -> uiString(R.string.ui_pause_download)
            paused -> uiString(R.string.ui_resume_download)
            canRetryDownload -> uiString(R.string.ui_retry)
            canDownload -> uiString(R.string.ui_download_attachment)
            canSaveFile -> uiString(R.string.ui_save_attachment)
            else -> null
        }
        val tileSize = when {
            galleryTile -> Modifier.fillMaxWidth().aspectRatio(1f).semantics {
                if (media != null) contentDescription = media.filename
                if (gallerySelected != null) selected = gallerySelected
            }
            albumTile -> Modifier.fillMaxWidth().height(112.dp)
            else -> Modifier.width(if (media?.isSticker == true) 176.dp else if (visual) 250.dp else 230.dp)
                .heightIn(min = if (media?.isSticker == true) 176.dp else if (visual) 170.dp else 72.dp)
        }
        val transferText = when {
            media?.available == true && progress?.phase != AttachmentPhase.UPLOADING -> null
            paused -> uiString(R.string.ui_download_paused)
            media?.remoteUnavailable == true -> uiString(R.string.ui_download_relay_unavailable)
            requested && progress?.phase != AttachmentPhase.DOWNLOADING && progress?.phase != AttachmentPhase.RETRY ->
                uiString(R.string.ui_download_queued)
            else -> when (progress?.phase) {
                AttachmentPhase.UPLOADING -> uiString(R.string.ui_attachment_upload_progress, percentage(progress))
                AttachmentPhase.DOWNLOADING -> uiString(
                    if (canPause && !opensPendingPreview && !fileCard) R.string.ui_download_progress_pause else R.string.ui_album_download_progress,
                    percentage(progress)
                )
                AttachmentPhase.RETRY -> uiString(if (canDownload) R.string.ui_retry else R.string.ui_attachment_waiting_retry)
                AttachmentPhase.UNAVAILABLE -> uiString(R.string.ui_download_relay_unavailable)
                else -> when {
                    canDownload -> uiString(R.string.ui_download_attachment)
                    media?.available == false -> uiString(R.string.ui_download_queued)
                    else -> null
                }
            }
        }
        val canRetryUpload = progress?.phase == AttachmentPhase.RETRY && onRetryUpload != null
        if (!compact && media?.available == true && media.isAudio && onToggleAudio != null) {
            InlineAudioCard(media, audioPlayback, { onToggleAudio(media) }, onLongClick)
        } else {
            Box(
                tileSize.background(if(media?.isSticker==true)Color.Transparent else MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(onClickLabel = actionLabel, onClick = {
                        if (gallerySelected != null) onToggleGallerySelection?.invoke()
                        else if (fileCard && fileExportAllowed == false && media?.available == true) onLongClick()
                        else if (media?.available == true) {
                            val bitmap = thumbnail
                            val bounds = thumbnailBounds
                            onOpen(if (canAnimate && bitmap != null && bounds != null)
                                MediaPhotoOrigin(media.reference, bitmap, bounds) else null)
                        }
                        else if (opensPendingPreview) onOpen(null)
                        else if (!downloadActionBusy && (canDownload || canPause)) onDownload?.invoke(canDownload)
                    }, enabled = if (gallerySelected != null) onToggleGallerySelection != null
                    else !downloadActionBusy || media?.available == true,
                        onLongClick = onLongClick.takeIf { gallerySelected == null }, onLongClickLabel = onLongClickLabel),
                contentAlignment = Alignment.Center
            ) {
                if (media?.available == true && media.isImage) {
                    val imageSize = if (galleryTile) Modifier.matchParentSize()
                    else Modifier.fillMaxWidth().height(if (albumTile) 112.dp else if (media.isSticker) 176.dp else 190.dp)
                    val trackOrigin = if (canAnimate) Modifier.onGloballyPositioned { coordinates ->
                        val start = coordinates.positionInRoot()
                        val visible = coordinates.boundsInRoot()
                        thumbnailBounds = if (coordinates.size.width > 0 && coordinates.size.height > 0 &&
                            visible.width >= coordinates.size.width - 1f && visible.height >= coordinates.size.height - 1f
                        ) Rect(start.x, start.y, start.x + coordinates.size.width, start.y + coordinates.size.height) else null
                    } else Modifier
                    EncryptedImage(media, imageSize.then(trackOrigin), onStillBitmap = if (canAnimate) {
                        { thumbnail = it }
                    } else null, pauseStickerPlayback = pauseStickerPlayback)
                } else if (media?.available == true && media.isVideo) {
                    val thumbnail by produceState<Bitmap?>(null, media.reference) {
                        value = withContext(Dispatchers.IO) { videoThumbnail(org.msgat.MsgATApp.instance.nettyClient.objects, media) }
                    }
                    val imageSize = if (galleryTile) Modifier.matchParentSize()
                    else Modifier.fillMaxWidth().height(if (albumTile) 112.dp else 190.dp)
                    thumbnail?.let { Image(it.asImageBitmap(), null, imageSize, contentScale = ContentScale.Crop) }
                    Icon(Icons.Default.PlayArrow, uiString(R.string.ui_play_video), Modifier.size(if (galleryTile) 28.dp else 44.dp), tint = Color.White)
                } else {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(when {
                            opensPendingPreview && canPause -> Icons.Default.AccessTime
                            canPause -> Icons.Default.Pause
                            canRetryDownload -> Icons.Default.Refresh
                            canDownload -> Icons.Default.Download
                            media?.isAudio == true -> Icons.Default.PlayArrow
                            else -> Icons.AutoMirrored.Filled.InsertDriveFile
                        }, null, Modifier.size(32.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(media?.filename ?: uiString(R.string.ui_search_attachment), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            media?.let { Text(android.text.format.Formatter.formatShortFileSize(org.msgat.MsgATApp.instance, it.size), fontSize = 12.sp) }
                            if (fileCard) {
                                if (!canRetryUpload && transferText != null && transferText != actionLabel) {
                                    Text(transferText, style = MaterialTheme.typography.labelSmall)
                                }
                                if (transferFraction != null) {
                                    LinearProgressIndicator(progress = { transferFraction },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                }
                                if (actionLabel != null) {
                                    Text(actionLabel, Modifier.padding(top = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (downloadActionBusy) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                if (galleryTile && transferText != null) {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = .7f))) {
                        if (transferFraction != null) {
                            LinearProgressIndicator(progress = { transferFraction },
                                modifier = Modifier.fillMaxWidth().height(3.dp))
                        }
                        Text(transferText, modifier = Modifier.padding(4.dp), color = Color.White,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (visualFooter != null) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.72f), CircleShape)
                        .padding(horizontal = 6.dp, vertical = 3.dp)) {
                        visualFooter()
                    }
                }
                if (gallerySelected != null) {
                    if (gallerySelected) {
                        Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)))
                    }
                    Icon(
                        if (gallerySelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.72f), CircleShape).padding(2.dp).size(20.dp),
                        tint = if (gallerySelected) MaterialTheme.colorScheme.inversePrimary else Color.White
                    )
                }
            }
        }
        if (!galleryTile && media != null && media.caption.isNotBlank() && (!albumTile || media.album?.index != 0)) {
            RichMessageText(media.caption, Modifier.padding(8.dp), linksEnabled = captionLinksEnabled,
                onVisibleLinks = onCaptionLinks, onLongClick = onLongClick)
        }
        if (!galleryTile && progress?.phase == AttachmentPhase.RETRY && onRetryUpload != null) {
            TextButton(enabled = !retryingUpload, onClick = onRetryUpload, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Default.Refresh, null, Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(uiString(R.string.ui_send_retry), style = MaterialTheme.typography.labelSmall)
            }
        } else if (!galleryTile && !fileCard && transferText != null) {
            Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(transferText, style = MaterialTheme.typography.labelSmall)
                if (!compact && transferFraction != null) {
                    LinearProgressIndicator(progress = { transferFraction }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InlineAudioCard(media: AttachmentMetadata, playback: InlineAudioPlayback?, toggle: () -> Unit, menu: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val playing = playback?.playing == true
    Column(Modifier.width(240.dp).background(colors.surfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().combinedClickable(
            onClickLabel = uiString(if (playing) R.string.ui_pause else R.string.ui_play_audio), onClick = toggle, onLongClick = menu
        ), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = colors.onPrimary)
            }
            Text(media.filename, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
        }
        if (playback?.failed == true) {
            Text(uiString(R.string.ui_audio_playback_failed), color = colors.error, style = MaterialTheme.typography.labelSmall)
        } else if (playback != null) {
            if (playback.loading) {
                val loadingLabel = uiString(R.string.ui_loading)
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = loadingLabel })
            }
            if (playback.duration > 0) {
                var scrub by remember(playback.activeId) { mutableStateOf<Float?>(null) }
                val fraction = scrub ?: (playback.position.toDouble() / playback.duration).coerceIn(0.0, 1.0).toFloat()
                val seek = uiString(R.string.ui_audio_playback_position)
                Slider(value = fraction, onValueChange = { scrub = it }, onValueChangeFinished = {
                    scrub?.let { playback.seek((it * playback.duration).toLong()) }
                    scrub = null
                }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = seek })
                val time = if (scrub == null) playback.position else (fraction * playback.duration).toLong()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${audioTime(time)} / ${audioTime(playback.duration)}",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    MediaSpeedButton(playback.speed, enabled = true, color = colors.onSurfaceVariant, changeSpeed = playback::changeSpeed)
                }
            }
        }
    }
}

private fun audioTime(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun percentage(value: AttachmentProgress): Long =
    if (value.total <= 0) 0 else (value.bytes.coerceIn(0, value.total) * 100 / value.total)
