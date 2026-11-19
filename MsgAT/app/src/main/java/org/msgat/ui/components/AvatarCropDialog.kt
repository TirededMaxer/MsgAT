// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.msgat.R
import org.msgat.core.media.ProfileImages
import org.msgat.ui.theme.uiString
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Cropping stays in memory and uses the same source rectangle for preview and export. */
@Composable
internal fun AvatarCropDialog(uri: Uri, onDismiss: () -> Unit, onConfirm: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var image by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var error by remember(uri) { mutableStateOf<Int?>(null) }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var center by remember(uri) { mutableStateOf(Offset.Zero) }
    var busy by remember(uri) { mutableStateOf(false) }
    var editJob by remember(uri) { mutableStateOf<Job?>(null) }

    LaunchedEffect(uri) {
        var decoded: Bitmap? = null
        try {
            withContext(Dispatchers.IO) { decoded = ProfileImages.bitmap(context, uri) }
            ensureActive()
            val source = checkNotNull(decoded)
            center = imageCenter(source)
            image = source
            decoded = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = R.string.ui_profile_image_unavailable
        } finally {
            decoded?.recycle()
        }
    }

    DisposableEffect(image) {
        val source = image
        onDispose {
            if (source != null) {
                val work = editJob
                if (work == null) source.recycle()
                else work.invokeOnCompletion { source.recycle() }
            }
        }
    }

    val source = image
    fun applyCrop(bitmap: Bitmap) {
        if (busy) return
        val crop = avatarCropRect(bitmap, center, zoom)
        busy = true
        error = null
        editJob = scope.launch {
            var encoded: ByteArray? = null
            try {
                withContext(Dispatchers.Default) { encoded = encodeAvatar(bitmap, crop) }
                ensureActive()
                onConfirm(checkNotNull(encoded))
                encoded = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = R.string.ui_profile_crop_failed
            } finally {
                encoded?.fill(0)
                busy = false
            }
        }
    }

    SecureEditorFrame(
        title = uiString(R.string.ui_crop_avatar),
        busy = busy,
        dismiss = onDismiss,
        actions = {
            TextButton(enabled = source != null && !busy, onClick = { source?.let(::applyCrop) }) {
                Text(uiString(if (busy) R.string.ui_applying else R.string.ui_use_avatar))
            }
        }
    ) {
        EditorPreviewLayout(modifier = Modifier.padding(12.dp), preview = {
            if (source == null) {
                if (error == null) CircularProgressIndicator()
            } else {
                val pixels = remember(source) { source.asImageBitmap() }
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val edge = minOf(maxWidth, maxHeight)
                    Canvas(Modifier.size(edge).clip(CircleShape).pointerInput(source, busy) {
                        if (busy) return@pointerInput
                        detectTransformGestures { centroid, pan, scale, _ ->
                            val extent = size.width.coerceAtLeast(1).toFloat()
                            val dimension = minOf(source.width, source.height).toFloat()
                            val next = (zoom * scale).coerceIn(1f, 5f)
                            val pivot = centroid - Offset(size.width / 2f, size.height / 2f)
                            center = clampAvatarCenter(source, center + pivot * (dimension / (extent * zoom)) -
                                (pivot + pan) * (dimension / (extent * next)), next)
                            zoom = next
                        }
                    }) {
                        if (size.width < 1f || size.height < 1f) return@Canvas
                        val crop = avatarCropRect(source, center, zoom)
                        drawImage(pixels, srcOffset = IntOffset(crop.left, crop.top),
                            srcSize = IntSize(crop.width(), crop.height()),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                    }
                }
            }
        }, controls = {
            if (source != null) {
                val zoomLabel = uiString(R.string.ui_avatar_crop_zoom)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Slider(value = zoom, onValueChange = { next ->
                        center = clampAvatarCenter(source, center, next)
                        zoom = next
                    }, valueRange = 1f..5f, enabled = !busy,
                        modifier = Modifier.weight(1f).semantics { contentDescription = zoomLabel })
                    IconButton(enabled = !busy && (zoom != 1f || center != imageCenter(source)), onClick = {
                        zoom = 1f
                        center = imageCenter(source)
                        error = null
                    }) {
                        Icon(Icons.Default.RestartAlt, uiString(R.string.ui_reset_crop))
                    }
                }
            }
            error?.let { Text(uiString(it), color = MaterialTheme.colorScheme.error) }
        })
    }
}

private fun imageCenter(bitmap: Bitmap) = Offset(bitmap.width / 2f, bitmap.height / 2f)

private fun clampAvatarCenter(bitmap: Bitmap, center: Offset, zoom: Float): Offset {
    val radius = minOf(bitmap.width, bitmap.height) / (2f * zoom)
    return Offset(center.x.coerceIn(radius, bitmap.width - radius), center.y.coerceIn(radius, bitmap.height - radius))
}

private fun avatarCropRect(bitmap: Bitmap, center: Offset, zoom: Float): Rect {
    val side = (minOf(bitmap.width, bitmap.height) / zoom).roundToInt().coerceAtLeast(1)
    val clamped = clampAvatarCenter(bitmap, center, zoom)
    val x = (clamped.x - side / 2f).roundToInt().coerceIn(0, bitmap.width - side)
    val y = (clamped.y - side / 2f).roundToInt().coerceIn(0, bitmap.height - side)
    return Rect(x, y, x + side, y + side)
}

private fun encodeAvatar(bitmap: Bitmap, crop: Rect): ByteArray {
    val cropped = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
    try {
        val output = Bitmap.createScaledBitmap(cropped, 512, 512, true)
        try {
            val buffer = object : ByteArrayOutputStream() {
                override fun close() {
                    buf.fill(0)
                    reset()
                    super.close()
                }
            }
            return buffer.use {
                check(output.compress(Bitmap.CompressFormat.JPEG, 82, it))
                check(it.size() <= 512 * 1024)
                it.toByteArray()
            }
        } finally {
            if (output !== cropped) output.recycle()
        }
    } finally {
        if (cropped !== bitmap) cropped.recycle()
    }
}
