// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.chat

import android.graphics.Bitmap
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import org.msgat.R
import org.msgat.ui.theme.uiString
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

internal data class MediaPhotoOrigin(val reference: String, val thumbnail: Bitmap, val bounds: Rect)
internal data class MediaPhotoFrame(val reference: String, val bounds: Rect)
internal data class MediaPhotoSnapshot(val bitmap: Bitmap, val bounds: Rect)

internal class MediaPhotoPresentation(val onImageFinished: () -> Unit) {
    var capture: (() -> MediaPhotoSnapshot?)? = null
}

private data class MediaPhotoDeparture(
    val snapshot: MediaPhotoSnapshot,
    val target: Rect,
    val backdropAlpha: Float,
    val showControls: Boolean
)

internal fun Modifier.mediaPagingGesture(
    pageId: String, rtl: Boolean, hasNewer: Boolean, hasOlder: Boolean,
    newer: () -> Unit, older: () -> Unit
): Modifier = pointerInput(pageId, rtl, hasNewer, hasOlder) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        val direction = if (rtl) -1f else 1f
        val flingSpeed = 650.dp.toPx()
        val horizontalLead = 12.dp.toPx()
        var ownsGesture = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (event.changes.any { it.id != down.id && it.pressed } || change.isConsumed) break
            tracker.addPosition(change.uptimeMillis, change.position)
            val movement = change.position - down.position
            val distance = movement.x * direction
            if (!change.pressed) {
                if (ownsGesture) {
                    val velocity = tracker.calculateVelocity().x * direction
                    val threshold = size.width / 3f
                    val fling = abs(distance) > horizontalLead && distance * velocity > 0 && abs(velocity) >= flingSpeed
                    if (abs(distance) > threshold || fling) {
                        change.consume()
                        if (distance > 0 && hasNewer) newer()
                        if (distance < 0 && hasOlder) older()
                    }
                }
                break
            }
            if (!ownsGesture) {
                if (abs(movement.y) > viewConfiguration.touchSlop && abs(movement.y) > abs(movement.x)) break
                ownsGesture = abs(movement.x) > viewConfiguration.touchSlop && abs(movement.x) > abs(movement.y) + horizontalLead
                if (!ownsGesture) continue
                if ((distance > 0 && !hasNewer) || (distance < 0 && !hasOlder)) break
            }
            change.consume()
        }
    }
}

@Composable
internal fun MediaViewerFrame(
    pageId: String,
    dragEnabled: Boolean,
    onDismiss: () -> Unit,
    photoOrigin: MediaPhotoOrigin? = null,
    returnBounds: Rect? = null,
    onCloseStarted: () -> Unit = {},
    registerBackAction: (((() -> Unit)?) -> Unit)? = null,
    media: @Composable (Modifier, MediaPhotoPresentation, Boolean, () -> Unit) -> Unit,
    controls: @Composable BoxScope.(Modifier, () -> Unit, Boolean, (() -> Unit)?) -> Unit
) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.findMediaActivity()?.window
    var fullScreen by remember(window) { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    val controlsAlpha by animateFloatAsState(
        if (controlsVisible) 1f else 0f,
        tween(if (controlsVisible) 180 else 220), label = "Media controls"
    )
    val toggleControlsLabel = uiString(if (controlsVisible) R.string.ui_media_hide_controls else R.string.ui_media_show_controls)
    if (fullScreen && window != null) {
        DisposableEffect(window, view) {
            val controller = WindowCompat.getInsetsController(window, view)
            val status = WindowInsetsCompat.Type.statusBars()
            val navigation = WindowInsetsCompat.Type.navigationBars()
            val insets = ViewCompat.getRootWindowInsets(view)
            val statusWasVisible = insets?.isVisible(status) != false
            val navigationWasVisible = insets?.isVisible(navigation) != false
            val behavior = controller.systemBarsBehavior
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(status or navigation)
            onDispose {
                controller.systemBarsBehavior = behavior
                if (statusWasVisible) controller.show(status) else controller.hide(status)
                if (navigationWasVisible) controller.show(navigation) else controller.hide(navigation)
            }
        }
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var frameOrigin by remember { mutableStateOf<Offset?>(null) }
    val entrance = remember(pageId, photoOrigin) { Animatable(if (photoOrigin == null) 1f else 0f) }
    var entranceFinished by remember(pageId, photoOrigin) { mutableStateOf(false) }
    var imageFinished by remember(pageId, photoOrigin) { mutableStateOf(false) }
    val presentation = remember(pageId, photoOrigin) { MediaPhotoPresentation { imageFinished = true } }
    val sourceBounds = localPhotoBounds(photoOrigin?.bounds, frameOrigin, viewport)
    val openingFromThumbnail = photoOrigin != null && (frameOrigin == null || sourceBounds != null)
    val showThumbnail = openingFromThumbnail && (!entranceFinished || !imageFinished)
    LaunchedEffect(pageId, photoOrigin, sourceBounds != null) {
        if (sourceBounds == null) return@LaunchedEffect
        entrance.animateTo(1f, tween(durationMillis = 200, easing = FastOutSlowInEasing))
        entranceFinished = true
        if (!imageFinished) {
            delay(500)
            imageFinished = true
        }
    }
    var targetOffset by remember(pageId) { mutableFloatStateOf(0f) }
    var dragging by remember(pageId) { mutableStateOf(false) }
    var closing by remember(pageId) { mutableStateOf(false) }
    var departure by remember(pageId) { mutableStateOf<MediaPhotoDeparture?>(null) }
    val exit = remember(pageId) { Animatable(0f) }
    val dismiss by rememberUpdatedState(onDismiss)
    val closeStarted by rememberUpdatedState(onCloseStarted)
    val latestReturnBounds by rememberUpdatedState(returnBounds)
    val offset by animateFloatAsState(
        targetValue = targetOffset,
        animationSpec = if (dragging) snap() else tween(if (closing) 200 else 250),
        label = "Media vertical dismissal",
        finishedListener = { finalOffset -> if (closing && departure == null && finalOffset == targetOffset) dismiss() }
    )
    fun backdropAlpha(): Float =
        (1f - abs(offset) / (viewport.height.coerceAtLeast(1) * .5f)).coerceIn(0f, 1f) *
            if (openingFromThumbnail) entrance.value else 1f

    fun requestDismissal(fromSwipe: Boolean = false) {
        if (closing) return
        closeStarted()
        val target = localPhotoBounds(latestReturnBounds, frameOrigin, viewport)
        val snapshot = if (showThumbnail) null else presentation.capture?.invoke()
        if (target != null && snapshot != null) {
            departure = MediaPhotoDeparture(
                snapshot.copy(bounds = snapshot.bounds.translate(0f, offset)),
                target,
                backdropAlpha(),
                showControls = !fromSwipe && controlsVisible
            )
            closing = true
        } else if (fromSwipe) {
            closing = true
            targetOffset = sign(targetOffset) * viewport.height
        } else {
            dismiss()
        }
    }
    fun goBack() {
        if (fullScreen) {
            fullScreen = false
            controlsVisible = true
        } else requestDismissal()
    }
    BackHandler(enabled = registerBackAction == null) { goBack() }
    if (registerBackAction != null) {
        val backAction by rememberUpdatedState<() -> Unit>(::goBack)
        val register by rememberUpdatedState(registerBackAction)
        DisposableEffect(pageId) {
            register { backAction() }
            onDispose { register(null) }
        }
    }
    LaunchedEffect(departure) {
        if (departure == null) return@LaunchedEffect
        exit.animateTo(1f, tween(durationMillis = 200, easing = FastOutSlowInEasing))
        dismiss()
    }
    val gesture = Modifier.pointerInput(pageId, dragEnabled, showThumbnail, viewport) {
        if (!dragEnabled || showThumbnail || closing) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var movement = Offset.Zero
            var ownsGesture = false
            var dragOrigin = 0f
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.none { it.pressed }) {
                        released = true
                        break
                    }
                    if (event.changes.count { it.pressed } > 1 || event.changes.any { it.isConsumed }) break
                    movement += event.calculatePan()
                    if (!ownsGesture) {
                        if (abs(movement.x) > viewConfiguration.touchSlop && abs(movement.x) >= abs(movement.y) / 2f) break
                        ownsGesture = abs(movement.y) > viewConfiguration.touchSlop && abs(movement.y) / 2f > abs(movement.x)
                        if (!ownsGesture) continue
                    }
                    if (!dragging && abs(movement.y) >= 30.dp.toPx()) {
                        dragging = true
                        dragOrigin = movement.y
                    }
                    if (dragging) targetOffset = movement.y - dragOrigin
                    event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                }
            } finally {
                if (dragging) {
                    val shouldClose = released && abs(targetOffset) > viewport.height / 6f
                    dragging = false
                    if (shouldClose) requestDismissal(fromSwipe = true) else targetOffset = 0f
                }
            }
        }
    }

    val interceptViewerTouches = Modifier.pointerInput(pageId, closing) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (closing) event.changes.forEach { it.consume() }
            }
        }
    }
    val toggleControls: () -> Unit = { if (!closing && !dragging) controlsVisible = !controlsVisible }
    Box(Modifier.fillMaxSize().onGloballyPositioned {
        viewport = it.size
        frameOrigin = it.positionInRoot()
    }.then(interceptViewerTouches).semantics {
        if (!closing) customActions = listOf(CustomAccessibilityAction(toggleControlsLabel) {
            toggleControls()
            true
        })
    }) {
        Box(Modifier.matchParentSize().graphicsLayer {
            alpha = departure?.let { it.backdropAlpha * (1f - exit.value) } ?: backdropAlpha()
        }.background(Color.Black))
        media(Modifier.fillMaxSize().then(gesture).graphicsLayer {
            translationY = offset
            alpha = if (showThumbnail || departure != null) 0f else 1f
        }, presentation, controlsVisible && !dragging && !closing, toggleControls)
        val leaving = departure
        if (leaving != null) {
            AnimatedPhotoFrame(leaving.snapshot.bitmap, leaving.snapshot.bounds, leaving.target,
                { exit.value }, Modifier.matchParentSize())
        } else if (showThumbnail && photoOrigin != null && sourceBounds != null) {
            val fitted = fittedPhotoFrame(IntSize(photoOrigin.thumbnail.width, photoOrigin.thumbnail.height), viewport)
            AnimatedPhotoFrame(photoOrigin.thumbnail, sourceBounds, fitted, { entrance.value }, Modifier.matchParentSize())
        }
        if (!dragging && (!closing || leaving?.showControls == true) && controlsAlpha > 0) {
            val chrome = Modifier.graphicsLayer {
                val shade = (1f - abs(offset) / (viewport.height.coerceAtLeast(1) / 6f)).coerceIn(0f, 1f)
                alpha = controlsAlpha * shade * (if (openingFromThumbnail) entrance.value else 1f) * (1f - exit.value)
            }.then(if (closing || !controlsVisible) Modifier.clearAndSetSemantics { } else Modifier)
            controls(chrome, { requestDismissal() }, fullScreen, window?.let { { fullScreen = !fullScreen } })
        }
    }
}

private tailrec fun Context.findMediaActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findMediaActivity() else null
    else -> null
}

private fun localPhotoBounds(bounds: Rect?, origin: Offset?, viewport: IntSize): Rect? {
    if (bounds == null || origin == null) return null
    return bounds.translate(-origin.x, -origin.y).takeIf {
        it.width > 0f && it.height > 0f && it.left >= -1f && it.top >= -1f &&
            it.right <= viewport.width + 1f && it.bottom <= viewport.height + 1f
    }
}

internal fun fittedPhotoFrame(image: IntSize, viewport: IntSize): Rect {
    val fit = minOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
    val width = image.width * fit
    val height = image.height * fit
    val left = (viewport.width - width) / 2f
    val top = (viewport.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

@Composable
private fun AnimatedPhotoFrame(bitmap: Bitmap, from: Rect, to: Rect, progress: () -> Float, modifier: Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier.clipToBounds()) {
        val fraction = progress()
        fun interpolate(start: Float, end: Float) = start + (end - start) * fraction
        val width = interpolate(from.width, to.width).coerceAtLeast(1f)
        val height = interpolate(from.height, to.height).coerceAtLeast(1f)
        val left = interpolate(from.left, to.left)
        val top = interpolate(from.top, to.top)
        val fill = maxOf(width / image.width, height / image.height)
        val cropWidth = (width / fill).roundToInt().coerceIn(1, image.width)
        val cropHeight = (height / fill).roundToInt().coerceIn(1, image.height)
        drawImage(
            image,
            srcOffset = IntOffset((image.width - cropWidth) / 2, (image.height - cropHeight) / 2),
            srcSize = IntSize(cropWidth, cropHeight),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(width.roundToInt(), height.roundToInt())
        )
    }
}
