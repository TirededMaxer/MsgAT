// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import org.msgat.ui.theme.uiString
import org.msgat.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.msgat.core.media.MemoryMediaSource
import org.msgat.core.media.MemoryMediaStore
import org.msgat.core.media.PhotoCompression
import kotlin.math.roundToInt

private val fullImage = Rect(0f, 0f, 1f, 1f)

internal data class EditedPhoto(
    val source: MemoryMediaSource, val preview: Bitmap, val name: String, val size: Int,
    val adjustment: PhotoAdjustment
)

internal enum class PhotoCropRatio(val width: Int = 0, val height: Int = 0) {
    Free, Original, Square(1, 1), LandscapePhoto(3, 2), PortraitPhoto(2, 3),
    LandscapeClassic(4, 3), PortraitClassic(3, 4), Wide(16, 9), Tall(9, 16);

    fun rotated() = if (width == height) this else entries.first { it.width == height && it.height == width }

    fun normalized(imageAspect: Float): Float? = when (this) {
        Free -> null
        Original -> 1f
        else -> (width.toFloat() / height) / imageAspect
    }
}

internal data class PhotoAdjustment(
    val crop: Rect = fullImage,
    val turns: Int = 0,
    val mirrored: Boolean = false,
    val ratio: PhotoCropRatio = PhotoCropRatio.Free
) {
    val changesPixels: Boolean get() = crop != fullImage || turns != 0 || mirrored

    fun imageAspect(image: Bitmap): Float = if (turns % 2 == 0) image.width.toFloat() / image.height
        else image.height.toFloat() / image.width

    fun rotated(clockwise: Boolean): PhotoAdjustment = copy(
        crop = if (clockwise) Rect(1f - crop.bottom, crop.left, 1f - crop.top, crop.right)
            else Rect(crop.top, 1f - crop.right, crop.bottom, 1f - crop.left),
        turns = (turns + if (clockwise != mirrored) 1 else 3) % 4,
        ratio = ratio.rotated()
    )

    fun flipped(): PhotoAdjustment = copy(
        crop = Rect(1f - crop.right, crop.top, 1f - crop.left, crop.bottom), mirrored = !mirrored
    )

    fun withRatio(value: PhotoCropRatio, image: Bitmap): PhotoAdjustment {
        val aspect = value.normalized(imageAspect(image)) ?: return copy(ratio = value)
        val width = minOf(1f, aspect)
        val height = minOf(1f, 1f / aspect)
        val left = (crop.center.x - width / 2).coerceIn(0f, 1f - width)
        val top = (crop.center.y - height / 2).coerceIn(0f, 1f - height)
        return copy(crop = Rect(Offset(left, top), Size(width, height)), ratio = value)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotoEditDialog(
    selection: MediaConfirmation,
    uri: Uri,
    name: String,
    mime: String,
    initial: PhotoAdjustment,
    onDismiss: () -> Unit,
    onApply: (EditedPhoto?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var adjustment by remember(uri) { mutableStateOf(initial) }
    var showRatios by remember(uri) { mutableStateOf(false) }
    var busy by remember(uri) { mutableStateOf(false) }
    var error by remember(uri) { mutableStateOf<Int?>(null) }
    var editJob by remember(uri) { mutableStateOf<Job?>(null) }

    LaunchedEffect(selection, uri) {
        val owner = selection.retain()
        var decoded: Bitmap? = null
        try {
            withContext(Dispatchers.IO) { decoded = PhotoCompression.openForEditing(context, uri, mime) }
            ensureActive()
            bitmap = decoded
            decoded = null
            if (bitmap == null) error = R.string.ui_photo_format_limit
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = R.string.ui_photo_open_error
        } finally {
            decoded?.recycle()
            owner.close()
        }
    }

    DisposableEffect(bitmap) {
        val image = bitmap
        onDispose {
            if (image != null) {
                val work = editJob
                if (work == null) image.recycle()
                else work.invokeOnCompletion { image.recycle() }
            }
        }
    }

    val image = bitmap
    val changed = adjustment != initial
    fun applyEdit(source: Bitmap) {
        if (busy || !changed) return
        val edit = adjustment
        if (!edit.changesPixels) {
            onApply(null)
            return
        }
        busy = true
        error = null
        editJob = scope.launch {
            var result: EditedPhoto? = null
            try {
                withContext(Dispatchers.Default) {
                    result = encodeEdit(context, source, edit, name, mime)
                }
                ensureActive()
                onApply(checkNotNull(result))
                result = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = R.string.ui_photo_edit_error
            } finally {
                result?.let { it.source.close(); it.preview.recycle() }
                busy = false
            }
        }
    }

    SecureEditorFrame(
        title = uiString(R.string.ui_edit_photo),
        busy = busy,
        dismiss = onDismiss,
        actions = {
            TextButton(
                enabled = image != null && !busy && changed && (adjustment.changesPixels || initial.changesPixels),
                onClick = { image?.let(::applyEdit) }
            ) {
                Text(uiString(if (busy) R.string.ui_applying else R.string.ui_apply))
            }
        }
    ) {
        EditorPreviewLayout(preview = {
            Box(
                Modifier.fillMaxSize().background(Color.Black).padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (image != null) CropCanvas(image, adjustment, enabled = !busy, onChange = { adjustment = adjustment.copy(crop = it) })
                else if (error == null) CircularProgressIndicator(color = Color.White)
            }
        }, controls = {
            error?.let {
                Text(uiString(it), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error)
            }
            if (image != null) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    IconButton(enabled = !busy, onClick = { adjustment = adjustment.rotated(false) }) {
                        Icon(Icons.Default.RotateLeft, contentDescription = uiString(R.string.ui_rotate_left))
                    }
                    IconButton(enabled = !busy, onClick = { adjustment = adjustment.rotated(true) }) {
                        Icon(Icons.Default.RotateRight, contentDescription = uiString(R.string.ui_rotate_right))
                    }
                    IconToggleButton(checked = adjustment.mirrored, enabled = !busy, onCheckedChange = { adjustment = adjustment.flipped() }) {
                        Icon(Icons.Default.Flip, uiString(R.string.ui_mirror_photo),
                            tint = if (adjustment.mirrored) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                    Box {
                        val ratioLabel = cropRatioLabel(adjustment.ratio)
                        IconButton(enabled = !busy, onClick = { showRatios = true },
                            modifier = Modifier.semantics { stateDescription = ratioLabel }) {
                            Icon(Icons.Default.AspectRatio, uiString(R.string.ui_crop_ratio),
                                tint = if (adjustment.ratio != PhotoCropRatio.Free) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                        DropdownMenu(expanded = showRatios, onDismissRequest = { showRatios = false }) {
                            for (ratio in PhotoCropRatio.entries) {
                                val active = ratio == adjustment.ratio
                                DropdownMenuItem(
                                    text = { Text(cropRatioLabel(ratio)) },
                                    modifier = Modifier.semantics { selected = active },
                                    leadingIcon = { if (active) Icon(Icons.Default.Check, contentDescription = null) },
                                    onClick = {
                                        if (!active) adjustment = adjustment.withRatio(ratio, image)
                                        showRatios = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(enabled = !busy && adjustment != PhotoAdjustment(), onClick = { adjustment = PhotoAdjustment(); error = null }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = uiString(R.string.ui_reset_crop))
                    }
                }
            }
        })
    }
}

@Composable
private fun cropRatioLabel(ratio: PhotoCropRatio): String = when (ratio) {
    PhotoCropRatio.Free -> uiString(R.string.ui_crop_free)
    PhotoCropRatio.Original -> uiString(R.string.ui_crop_original)
    PhotoCropRatio.Square -> uiString(R.string.ui_crop_square)
    else -> "${ratio.width}:${ratio.height}"
}

@Composable
private fun CropCanvas(image: Bitmap, adjustment: PhotoAdjustment, enabled: Boolean, onChange: (Rect) -> Unit) {
    val turns = adjustment.turns
    val crop = adjustment.crop
    val pixels = remember(image) { image.asImageBitmap() }
    val currentCrop by rememberUpdatedState(crop)
    val change by rememberUpdatedState(onChange)
    var dragging by remember(image, turns, enabled) { mutableStateOf(false) }
    val gridAlpha by animateFloatAsState(if (dragging) .80f else .30f, tween(150), label = "Crop grid")
    val sideways = turns % 2 != 0
    val width = if (sideways) image.height else image.width
    val height = if (sideways) image.width else image.height
    val aspect = adjustment.ratio.normalized(adjustment.imageAspect(image))
    Canvas(Modifier.fillMaxSize().pointerInput(image, turns, adjustment.mirrored, aspect, enabled) {
        if (!enabled) return@pointerInput
        var target = Rect.Zero
        var region = currentCrop
        var distance = Offset.Zero
        var handle: CropHandle? = null
        detectDragGestures(onDragStart = { point ->
            target = fittedBounds(Size(size.width.toFloat(), size.height.toFloat()), width, height)
            if (target.width <= 0f || target.height <= 0f) return@detectDragGestures
            region = currentCrop
            distance = Offset.Zero
            val selection = target.position(region)
            handle = CropHandle.entries.filter { it != CropHandle.Move }
                .minByOrNull { (it.position(selection) - point).getDistance() }
                ?.takeIf { (it.position(selection) - point).getDistance() <= 28.dp.toPx() }
                ?: CropHandle.Move.takeIf { selection.contains(point) }
            dragging = handle != null
        }, onDragEnd = { handle = null; dragging = false }, onDragCancel = { handle = null; dragging = false }) { event, delta ->
            val active = handle ?: return@detectDragGestures
            event.consume()
            distance += delta
            change(active.drag(region, distance.x / target.width, distance.y / target.height, aspect, target.width / target.height))
        }
    }) {
        val target = fittedBounds(size, width, height)
        if (target.width <= 0f || target.height <= 0f) return@Canvas
        val drawWidth = if (sideways) target.height else target.width
        val drawHeight = if (sideways) target.width else target.height
        scale(if (adjustment.mirrored) -1f else 1f, 1f, target.center) {
            rotate(turns * 90f, target.center) {
                drawImage(
                    pixels,
                    dstOffset = IntOffset((target.center.x - drawWidth / 2).roundToInt(), (target.center.y - drawHeight / 2).roundToInt()),
                    dstSize = IntSize(drawWidth.roundToInt().coerceAtLeast(1), drawHeight.roundToInt().coerceAtLeast(1))
                )
            }
        }
        val selection = target.position(crop)
        val shade = Color.Black.copy(alpha = .56f)
        drawRect(shade, target.topLeft, Size(target.width, selection.top - target.top))
        drawRect(shade, Offset(target.left, selection.bottom), Size(target.width, target.bottom - selection.bottom))
        drawRect(shade, Offset(target.left, selection.top), Size(selection.left - target.left, selection.height))
        drawRect(shade, Offset(selection.right, selection.top), Size(target.right - selection.right, selection.height))
        drawRect(Color.White, selection.topLeft, selection.size, style = Stroke(1.dp.toPx()))
        val grid = Color.White.copy(alpha = gridAlpha)
        for (part in 1..2) {
            val x = selection.left + selection.width * part / 3
            val y = selection.top + selection.height * part / 3
            drawLine(grid, Offset(x, selection.top), Offset(x, selection.bottom), 1.dp.toPx())
            drawLine(grid, Offset(selection.left, y), Offset(selection.right, y), 1.dp.toPx())
        }
        val markWidth = minOf(16.dp.toPx(), selection.width / 3f)
        val markHeight = minOf(16.dp.toPx(), selection.height / 3f)
        for (handle in CropHandle.entries) {
            if (handle == CropHandle.Move) continue
            val corner = handle.position(selection)
            val x = if (corner.x < selection.center.x) markWidth else -markWidth
            val y = if (corner.y < selection.center.y) markHeight else -markHeight
            drawLine(Color.White, corner, corner + Offset(x, 0f), strokeWidth = 3.dp.toPx())
            drawLine(Color.White, corner, corner + Offset(0f, y), strokeWidth = 3.dp.toPx())
        }
    }
}

private fun fittedBounds(space: Size, width: Int, height: Int): Rect {
    val scale = minOf(space.width / width, space.height / height)
    val fitted = Size(width * scale, height * scale)
    return Rect(Offset((space.width - fitted.width) / 2, (space.height - fitted.height) / 2), fitted)
}

private fun Rect.position(crop: Rect): Rect = Rect(
    left + crop.left * width, top + crop.top * height,
    left + crop.right * width, top + crop.bottom * height
)

private enum class CropHandle(private val left: Boolean, private val top: Boolean) {
    TopLeft(true, true), TopRight(false, true), BottomLeft(true, false), BottomRight(false, false), Move(false, false);

    fun position(rect: Rect) = Offset(if (left) rect.left else rect.right, if (top) rect.top else rect.bottom)

    fun drag(rect: Rect, dx: Float, dy: Float, ratio: Float?, imageAspect: Float): Rect {
        if (this == Move) return rect.translate(
            Offset(dx.coerceIn(-rect.left, maxOf(-rect.left, 1f - rect.right)),
                dy.coerceIn(-rect.top, maxOf(-rect.top, 1f - rect.bottom)))
        )
        val minimum = .04f
        if (ratio != null) {
            val anchorX = if (left) rect.right else rect.left
            val anchorY = if (top) rect.bottom else rect.top
            val unitWidth = minOf(1f, ratio)
            val unitHeight = minOf(1f, 1f / ratio)
            val limit = minOf((if (left) anchorX else 1f - anchorX) / unitWidth,
                (if (top) anchorY else 1f - anchorY) / unitHeight).coerceAtLeast(0f)
            val directionX = unitWidth * imageAspect
            val candidateX = (rect.width + if (left) -dx else dx) * imageAspect
            val candidateY = rect.height + if (top) -dy else dy
            val length = ((candidateX * directionX + candidateY * unitHeight) /
                (directionX * directionX + unitHeight * unitHeight)).coerceIn(minOf(minimum, limit), limit)
            val width = unitWidth * length
            val height = unitHeight * length
            return Rect(Offset(if (left) anchorX - width else anchorX, if (top) anchorY - height else anchorY), Size(width, height))
        }
        return Rect(
            if (left) (rect.left + dx).coerceIn(0f, (rect.right - minimum).coerceAtLeast(0f)) else rect.left,
            if (top) (rect.top + dy).coerceIn(0f, (rect.bottom - minimum).coerceAtLeast(0f)) else rect.top,
            if (left) rect.right else (rect.right + dx).coerceIn((rect.left + minimum).coerceAtMost(1f), 1f),
            if (top) rect.bottom else (rect.bottom + dy).coerceIn((rect.top + minimum).coerceAtMost(1f), 1f)
        )
    }
}

private fun encodeEdit(context: Context, image: Bitmap, adjustment: PhotoAdjustment, name: String, mime: String): EditedPhoto {
    val region = adjustment.crop
    val rotated = if (adjustment.turns == 0 && !adjustment.mirrored) image else Bitmap.createBitmap(
        image, 0, 0, image.width, image.height,
        Matrix().apply {
            setRotate(adjustment.turns * 90f)
            if (adjustment.mirrored) postScale(-1f, 1f)
        }, true
    )
    try {
        val x = (region.left * rotated.width).roundToInt().coerceIn(0, rotated.width - 1)
        val y = (region.top * rotated.height).roundToInt().coerceIn(0, rotated.height - 1)
        val right = (region.right * rotated.width).roundToInt().coerceIn(x + 1, rotated.width)
        val bottom = (region.bottom * rotated.height).roundToInt().coerceIn(y + 1, rotated.height)
        val cropped = Bitmap.createBitmap(rotated, x, y, right - x, bottom - y)
        try {
            val scale = minOf(1f, 512f / maxOf(cropped.width, cropped.height))
            val preview = Bitmap.createScaledBitmap(cropped,
                (cropped.width * scale).roundToInt().coerceAtLeast(1),
                (cropped.height * scale).roundToInt().coerceAtLeast(1), true)
            val thumbnail = if (preview === cropped) checkNotNull(preview.copy(Bitmap.Config.ARGB_8888, false)) else preview
            try {
                val bytes = PhotoCompression.ErasingBuffer().use { buffer ->
                    check(cropped.compress(PhotoCompression.format(mime), 95, buffer)) { "Photo could not be encoded" }
                    buffer.toByteArray()
                }
                try {
                    val extension = if (mime == "image/png") "png" else "jpg"
                    val editedName = name.substringBeforeLast('.').take(160).ifBlank { "Photo" } + "-edited." + extension
                    val source = MemoryMediaStore.image(context, bytes, editedName, mime)
                    return EditedPhoto(source, thumbnail, editedName, bytes.size, adjustment)
                } catch (failure: Exception) {
                    bytes.fill(0)
                    throw failure
                }
            } catch (failure: Exception) {
                thumbnail.recycle()
                throw failure
            }
        } finally {
            if (cropped !== rotated) cropped.recycle()
        }
    } finally {
        if (rotated !== image) rotated.recycle()
    }
}
