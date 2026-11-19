// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.chat

import android.graphics.Bitmap
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.msgat.R
import org.msgat.core.media.AttachmentMetadata
import org.msgat.ui.theme.uiString

private data class ImageTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    fun around(point: Offset, pan: Offset, nextScale: Float, viewport: IntSize): ImageTransform {
        val pivot = point - Offset(viewport.width / 2f, viewport.height / 2f)
        return ImageTransform(nextScale, pivot + pan + (offset - pivot) * (nextScale / scale))
    }

    fun bounded(viewport: IntSize, image: IntSize): ImageTransform {
        if (scale <= 1f || viewport == IntSize.Zero || image == IntSize.Zero) {
            return copy(offset = Offset.Zero)
        }
        val fitted = minOf(viewport.width / image.width.toFloat(), viewport.height / image.height.toFloat())
        val x = ((image.width * fitted * scale - viewport.width) / 2).coerceAtLeast(0f)
        val y = ((image.height * fitted * scale - viewport.height) / 2).coerceAtLeast(0f)
        return copy(offset = Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y)))
    }
}

@Composable
internal fun ZoomableMediaImage(
    media: AttachmentMetadata,
    active: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    presentation: MediaPhotoPresentation? = null,
    onToggleControls: (() -> Unit)? = null
) {
    var attempt by remember(media.reference) { mutableIntStateOf(0) }
    var failed by remember(media.reference, active, attempt) { mutableStateOf(false) }
    var finished by remember(media.reference, active, attempt) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        key(attempt) {
            ZoomablePhoto(media.reference, active, onZoomChanged, presentation, onToggleControls) { modifier, onSize, onBitmap ->
                EncryptedImage(media, modifier, fullSize = active, fitToViewport = true, onImageSize = onSize,
                    onStillBitmap = onBitmap, onImageFinished = {
                        finished = true
                        presentation?.onImageFinished?.invoke()
                    },
                    onImageFailure = { failed = true })
            }
        }
        if (active && failed) {
            MediaPreviewRetry(Modifier.align(Alignment.Center)) { attempt++ }
        } else if (active && !finished) {
            MediaPreviewLoading(uiString(R.string.ui_loading), Modifier.align(Alignment.Center))
        }
    }
}

@Composable
internal fun ZoomablePhoto(
    identity: Any,
    active: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    presentation: MediaPhotoPresentation? = null,
    onTap: (() -> Unit)? = null,
    image: @Composable (Modifier, (IntSize) -> Unit, ((Bitmap) -> Unit)?) -> Unit
) {
    var transform by remember(identity) { mutableStateOf(ImageTransform()) }
    var imageSize by remember(identity) { mutableStateOf(IntSize.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var displayedBitmap by remember(identity) { mutableStateOf<Bitmap?>(null) }
    var animation by remember(identity) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val notifyZoom by rememberUpdatedState(onZoomChanged)
    val tap by rememberUpdatedState(onTap)
    val zoomed by remember(active, identity) { derivedStateOf { active && transform.scale > 1f } }

    LaunchedEffect(zoomed) { notifyZoom(zoomed) }
    LaunchedEffect(active, viewport, imageSize) {
        animation?.cancel()
        transform = if (active) transform.bounded(viewport, imageSize) else ImageTransform()
    }
    DisposableEffect(identity) {
        onDispose {
            animation?.cancel()
            notifyZoom(false)
        }
    }
    if (presentation != null) {
        DisposableEffect(presentation, identity) {
            presentation.capture = capture@{
                val bitmap = displayedBitmap ?: return@capture null
                if (viewport == IntSize.Zero) return@capture null
                val fitted = fittedPhotoFrame(IntSize(bitmap.width, bitmap.height), viewport)
                val center = fitted.center + transform.offset
                val halfWidth = fitted.width * transform.scale / 2f
                val halfHeight = fitted.height * transform.scale / 2f
                MediaPhotoSnapshot(bitmap, Rect(center.x - halfWidth, center.y - halfHeight,
                    center.x + halfWidth, center.y + halfHeight))
            }
            onDispose { presentation.capture = null }
        }
    }

    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }) {
        image(
            Modifier.fillMaxSize()
                .pointerInput(identity, active) {
                    if (!active) return@pointerInput
                    detectTapGestures(onTap = { tap?.invoke() }, onDoubleTap = { point ->
                        if (imageSize != IntSize.Zero) {
                            val start = transform
                            val target = if (start.scale > 1f) ImageTransform()
                            else start.around(point, Offset.Zero, 3f, viewport).bounded(viewport, imageSize)
                            animation?.cancel()
                            animation = scope.launch {
                                animate(0f, 1f, animationSpec = tween(200)) { fraction, _ ->
                                    transform = ImageTransform(
                                        start.scale + (target.scale - start.scale) * fraction,
                                        start.offset + (target.offset - start.offset) * fraction
                                    ).bounded(viewport, imageSize)
                                }
                            }
                        }
                    })
                }
                .pointerInput(identity, active) {
                    if (!active) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var ownsGesture = false
                        var totalPan = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed } || event.changes.any { it.isConsumed }) break
                            if (imageSize == IntSize.Zero) continue
                            val pan = event.calculatePan()
                            totalPan += pan
                            if (!ownsGesture) {
                                ownsGesture = event.changes.count { it.pressed } > 1 ||
                                    (transform.scale > 1f && totalPan.getDistance() > viewConfiguration.touchSlop)
                                if (!ownsGesture) continue
                                animation?.cancel()
                            }
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (centroid.isSpecified) {
                                val nextScale = (transform.scale * event.calculateZoom()).coerceIn(1f, 5f)
                                transform = transform.around(centroid, pan, nextScale, viewport).bounded(viewport, imageSize)
                            }
                            event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationX = transform.offset.x
                    translationY = transform.offset.y
                },
            { imageSize = it },
            if (presentation == null) null else { bitmap -> displayedBitmap = bitmap }
        )
    }
}
