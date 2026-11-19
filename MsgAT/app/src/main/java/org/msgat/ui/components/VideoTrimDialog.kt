// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import org.msgat.ui.theme.uiString
import org.msgat.R
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.msgat.core.media.VideoCompression
import org.msgat.core.media.VideoTrim
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

internal fun videoTrimTime(ms: Long): String =
    String.format(Locale.ROOT, "%d:%02d.%d", ms / 60_000, (ms / 1000) % 60, (ms / 100) % 10)

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun VideoTrimDialog(
    selection: MediaConfirmation,
    uri: Uri,
    initial: VideoTrim?,
    onDismiss: () -> Unit,
    onApply: (VideoTrim?) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var duration by remember(uri) { mutableLongStateOf(0) }
    var range by remember(uri) { mutableStateOf(0f..0f) }
    var muteAudio by remember(uri) { mutableStateOf(false) }
    var thumbnails by remember(uri) { mutableStateOf(emptyList<Bitmap?>()) }
    var error by remember(uri) { mutableStateOf<Int?>(null) }

    LaunchedEffect(selection, uri) {
        val owner = selection.retain()
        val decoded = mutableListOf<Bitmap?>()
        try {
            val length = withContext(Dispatchers.IO) { VideoCompression.trimDuration(context, uri) }
            if (length == null || length <= 250) {
                error = R.string.ui_cannot_trim
                return@LaunchedEffect
            }
            val saved = initial?.takeIf { it.startMs >= 0 && it.endMs <= length && it.endMs - it.startMs >= 250 }
            range = (saved?.startMs ?: 0).toFloat()..(saved?.endMs ?: length).toFloat()
            muteAudio = saved?.muteAudio == true
            duration = length
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    repeat(6) { index ->
                        ensureActive()
                        decoded += runCatching {
                            retriever.getScaledFrameAtTime(length * 1000 * index / 6,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
                        }.getOrNull()
                    }
                } finally {
                    retriever.release()
                }
            }
            ensureActive()
            thumbnails = decoded.toList()
            decoded.clear()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (duration == 0L) error = R.string.ui_video_open_error
        } finally {
            decoded.forEach { it?.recycle() }
            owner.close()
        }
    }

    DisposableEffect(thumbnails) {
        val current = thumbnails
        onDispose { current.forEach { it?.recycle() } }
    }

    val player = if (duration > 0L) remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            volume = if (muteAudio) 0f else 1f
            setMediaItem(VideoCompression.mediaItem(uri, null), range.start.roundToLong())
            prepare()
        }
    } else null
    var playing by remember(player) { mutableStateOf(false) }
    var clippedPreview by remember(player) { mutableStateOf(false) }
    var scrubPosition by remember(uri) { mutableLongStateOf(0) }
    if (player != null) {
        DisposableEffect(player, lifecycle, selection) {
            val owner = selection.retain()
            val listener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) player.pause()
                }
                override fun onPlayerError(failure: PlaybackException) {
                    playing = false
                    error = R.string.ui_video_preview_error
                }
            }
            val observer = object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) { player.pause() }
            }
            player.addListener(listener)
            lifecycle.lifecycle.addObserver(observer)
            onDispose {
                lifecycle.lifecycle.removeObserver(observer)
                player.removeListener(listener)
                try { player.release() } finally { owner.close() }
            }
        }
    }

    fun changeBound(value: Float, atStart: Boolean) {
        player?.pause()
        if (atStart) {
            val start = value.coerceIn(0f, (range.endInclusive - 250f).coerceAtLeast(0f))
            range = start..range.endInclusive
            scrubPosition = start.roundToLong()
        } else {
            val end = value.coerceIn((range.start + 250f).coerceAtMost(duration.toFloat()), duration.toFloat())
            range = range.start..end
            scrubPosition = (end.roundToLong() - 1).coerceAtLeast(0)
        }
    }

    fun seekToBound() {
        if (player == null) return
        if (clippedPreview) {
            player.setMediaItem(VideoCompression.mediaItem(uri, null), scrubPosition)
            player.prepare()
            clippedPreview = false
        } else player.seekTo(scrubPosition)
    }

    SecureEditorFrame(
        title = uiString(R.string.ui_edit_video),
        busy = false,
        dismiss = onDismiss,
        actions = {
            TextButton(enabled = player != null, onClick = {
                val start = range.start.roundToLong()
                val end = range.endInclusive.roundToLong()
                onApply(if (start == 0L && end == duration && !muteAudio) null else VideoTrim(start, end, muteAudio))
            }) { Text(uiString(R.string.ui_apply)) }
        }
    ) {
        EditorPreviewLayout(modifier = Modifier.padding(12.dp), preview = {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                if (player != null) {
                    AndroidView(
                        factory = { PlayerView(it).apply { useController = false } },
                        update = { it.player = player },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (error == null) CircularProgressIndicator(color = Color.White)
            }
        }, controls = {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error) }
                if (player != null) {
                    VideoTrimFilmstrip(thumbnails, duration, range, ::changeBound, ::seekToBound)
                    RangeSlider(value = range, valueRange = 0f..duration.toFloat(), onValueChange = { next ->
                        if (next.start != range.start) changeBound(next.start, atStart = true)
                        else changeBound(next.endInclusive, atStart = false)
                    }, onValueChangeFinished = ::seekToBound)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(videoTrimTime(range.start.roundToLong()))
                        Text(videoTrimTime((range.endInclusive - range.start).roundToLong()))
                        Text(videoTrimTime(range.endInclusive.roundToLong()))
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(modifier = Modifier.align(Alignment.CenterVertically), selected = muteAudio, onClick = {
                            muteAudio = !muteAudio
                            player.volume = if (muteAudio) 0f else 1f
                        }, label = { Text(uiString(R.string.ui_remove_audio)) })
                        TextButton(modifier = Modifier.align(Alignment.CenterVertically), onClick = {
                            if (playing) player.pause()
                            else if (clippedPreview && player.playbackState != Player.STATE_ENDED && player.playerError == null) {
                                player.play()
                            } else {
                                error = null
                                val trim = VideoTrim(range.start.roundToLong(), range.endInclusive.roundToLong())
                                player.setMediaItem(VideoCompression.mediaItem(uri, trim))
                                clippedPreview = true
                                player.prepare()
                                player.play()
                            }
                        }) { Text(uiString(if (playing) R.string.ui_pause else R.string.ui_play_selection)) }
                    }
                }
            }
        })
    }
}

@Composable
private fun VideoTrimFilmstrip(
    thumbnails: List<Bitmap?>,
    duration: Long,
    range: ClosedFloatingPointRange<Float>,
    changeBound: (Float, Boolean) -> Unit,
    seek: () -> Unit
) {
    val current by rememberUpdatedState(range)
    val change by rememberUpdatedState(changeBound)
    val seekToBound by rememberUpdatedState(seek)
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().height(46.dp).background(Color.Black).pointerInput(duration) {
        var atStart = true
        var active = false
        var position = 0f
        detectHorizontalDragGestures(onDragStart = { point ->
            val start = size.width * (current.start / duration)
            val end = size.width * (current.endInclusive / duration)
            atStart = abs(point.x - start) <= abs(point.x - end)
            active = minOf(abs(point.x - start), abs(point.x - end)) <= 28.dp.toPx()
            position = if (atStart) current.start else current.endInclusive
        }, onDragEnd = {
            if (active) seekToBound()
            active = false
        }, onDragCancel = {
            if (active) seekToBound()
            active = false
        }) { event, amount ->
            if (!active || size.width <= 0) return@detectHorizontalDragGestures
            event.consume()
            position += amount / size.width * duration
            change(position, atStart)
        }
    }) {
        Row(Modifier.matchParentSize()) {
            for (thumbnail in thumbnails) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val left = size.width * (range.start / duration)
            val right = size.width * (range.endInclusive / duration)
            val shade = Color.Black.copy(alpha = .65f)
            val stroke = 2.dp.toPx()
            drawRect(shade, size = Size(left, size.height))
            drawRect(shade, Offset(right, 0f), Size(size.width - right, size.height))
            drawRect(accent, Offset(left, stroke / 2), Size(right - left, size.height - stroke), style = Stroke(stroke))
            for (edge in listOf(left, right)) {
                val x = edge.coerceIn(stroke, (size.width - stroke).coerceAtLeast(stroke))
                drawLine(accent, Offset(x, 0f), Offset(x, size.height), 4.dp.toPx())
            }
        }
    }
}
