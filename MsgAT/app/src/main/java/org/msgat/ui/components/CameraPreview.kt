// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import org.msgat.R
import org.msgat.ui.theme.uiString

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.TorchState
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

private data class FocusMark(val point: Offset, val request: Any, val focused: Boolean? = null)

@Composable
internal fun CameraPreview(view: PreviewView, camera: Camera?, controlsEnabled: Boolean, modifier: Modifier = Modifier) {
    var focus by remember(camera) { mutableStateOf<FocusMark?>(null) }
    LaunchedEffect(focus) {
        val mark = focus ?: return@LaunchedEffect
        delay(if (mark.focused == null) 3500L else 900L)
        focus = null
    }
    DisposableEffect(view, camera, controlsEnabled) {
        if (camera == null || !controlsEnabled) return@DisposableEffect onDispose {}
        val control = camera.cameraControl
        val info = camera.cameraInfo
        val main = ContextCompat.getMainExecutor(view.context)
        var listening = true
        var multiplePointers = false
        var zoomRatio = 1f
        val scale = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                val zoom = info.zoomState.value ?: return false
                zoomRatio = zoom.zoomRatio
                return zoom.maxZoomRatio > zoom.minZoomRatio
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val zoom = info.zoomState.value ?: return false
                val factor = detector.scaleFactor
                if (!factor.isFinite() || factor <= 0f) return false
                val next = (zoomRatio * factor).coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
                if (next != zoomRatio) {
                    zoomRatio = next
                    control.setZoomRatio(next)
                }
                return true
            }
        }).apply { isQuickScaleEnabled = false }
        val tap = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (multiplePointers || view.width <= 0 || view.height <= 0) return false
                val point = view.meteringPointFactory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                if (!info.isFocusMeteringSupported(action)) return false
                val mark = FocusMark(Offset(event.x, event.y), Any())
                focus = mark
                val result = control.startFocusAndMetering(action)
                result.addListener({
                    if (listening && focus?.request === mark.request) {
                        focus = mark.copy(focused = runCatching { result.get().isFocusSuccessful }.getOrDefault(false))
                    }
                }, main)
                return true
            }
        })
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) multiplePointers = false
            if (event.pointerCount > 1) multiplePointers = true
            scale.onTouchEvent(event)
            tap.onTouchEvent(event)
            true
        }
        onDispose {
            listening = false
            view.setOnTouchListener(null)
        }
    }
    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        focus?.takeIf { controlsEnabled }?.let { mark ->
            Canvas(Modifier.matchParentSize()) {
                val edge = 48.dp.toPx()
                val topLeft = Offset(mark.point.x - edge / 2, mark.point.y - edge / 2)
                val corner = CornerRadius(6.dp.toPx())
                val color = if (mark.focused == false) Color.White else Color(0xFFFFD45C)
                drawRoundRect(Color.Black.copy(alpha = .55f), topLeft, Size(edge, edge), corner, style = Stroke(3.dp.toPx()))
                drawRoundRect(color, topLeft, Size(edge, edge), corner, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

@Composable
internal fun VideoTorchButton(camera: Camera, enabled: Boolean, onUnavailable: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current
    val context = LocalContext.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    var torchOn by remember(camera) { mutableStateOf(camera.cameraInfo.torchState.value == TorchState.ON) }
    var pending by remember(camera) { mutableStateOf(false) }
    val mounted = remember(camera, lifecycle) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val unavailable by rememberUpdatedState(onUnavailable)
    DisposableEffect(camera, lifecycle) {
        val observer = Observer<Int> { torchOn = it == TorchState.ON }
        val state = camera.cameraInfo.torchState
        mounted.set(true)
        state.observe(lifecycle, observer)
        onDispose {
            mounted.set(false)
            state.removeObserver(observer)
            camera.cameraControl.enableTorch(false)
        }
    }
    IconButton(enabled = enabled && !pending, onClick = {
        pending = true
        val result = camera.cameraControl.enableTorch(!torchOn)
        result.addListener({
            if (mounted.get()) {
                pending = false
                if (runCatching { result.get() }.isFailure) unavailable()
            }
        }, executor)
    }) {
        Icon(if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
            contentDescription = uiString(if (torchOn) R.string.ui_capture_torch_off else R.string.ui_capture_torch_on))
    }
}
