// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.msgat.R
import org.msgat.core.media.AttachmentMetadata
import org.msgat.core.media.EncryptedObjectStore
import org.msgat.core.network.AttachmentProgress
import org.msgat.ui.theme.uiString
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal val encryptedImageWorkers = Dispatchers.IO.limitedParallelism(2)

internal data class AttachmentMetadataRead(
    val metadata: AttachmentMetadata?,
    val loading: Boolean = false,
    val failed: Boolean = false
)

@Composable
internal fun rememberAttachment(msg: MessageUIModel, progress: AttachmentProgress?): State<AttachmentMetadata?> {
    val read = rememberAttachmentRead(msg, progress)
    return remember(read) { derivedStateOf { read.value.metadata } }
}

@Composable
internal fun rememberAttachmentRead(
    msg: MessageUIModel, progress: AttachmentProgress?, retry: Int = 0
): State<AttachmentMetadataRead> {
    if (msg.ext != "attachment") return remember { mutableStateOf(AttachmentMetadataRead(null)) }
    val store = org.msgat.MsgATApp.instance.nettyClient.objects
    val cacheRevision by store.cacheRevision.collectAsState()
    val availability by store.availability.collectAsState()
    return key(msg.id, msg.content) {
        val initial = msg.attachment?.takeIf { it.reference == msg.content }
        produceState(AttachmentMetadataRead(initial, loading = true), msg.attachment, progress?.phase,
            cacheRevision, availability, retry) {
            value = AttachmentMetadataRead(value.metadata ?: initial, loading = true)
            try {
                val metadata = withContext(Dispatchers.IO) { store.metadata(msg.content, msg.id) }
                value = AttachmentMetadataRead(metadata)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the last result for this attachment until the store can be read again.
                value = AttachmentMetadataRead(value.metadata ?: initial, failed = true)
            }
        }
    }
}

/** No Coil disk cache, file-backed image source, plaintext temp file or content provider. */
@Composable
internal fun EncryptedImage(
    media: AttachmentMetadata,
    modifier: Modifier = Modifier,
    fullSize: Boolean = false,
    fitToViewport: Boolean = fullSize || media.isSticker,
    onImageSize: ((IntSize) -> Unit)? = null,
    onStillBitmap: ((Bitmap?) -> Unit)? = null,
    onImageFinished: (() -> Unit)? = null,
    playStickerAnimation: Boolean = true,
    thumbnailEdge: Int = 640,
    pauseStickerPlayback: Boolean = false,
    onImageFailure: (() -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = LocalLifecycleOwner.current
    val paused by rememberUpdatedState(media.isSticker && pauseStickerPlayback)
    val store = org.msgat.MsgATApp.instance.nettyClient.objects
    var loaded by remember(media.reference, fullSize, playStickerAnimation, thumbnailEdge) { mutableStateOf<DecodedMedia?>(null) }
    var failed by remember(media.reference, fullSize, playStickerAnimation, thumbnailEdge) { mutableStateOf(false) }
    var imageView by remember { mutableStateOf<ImageView?>(null) }
    LaunchedEffect(media.reference, media.available, fullSize, playStickerAnimation, thumbnailEdge) {
        if (!media.available) return@LaunchedEffect
        var pending: DecodedMedia? = null
        try {
            withContext(encryptedImageWorkers) {
                val edge = if (fullSize) 4096 else thumbnailEdge
                pending = if (media.isSticker && !playStickerAnimation) {
                    decodeImage(store, media, edge, animate = false, playStickerAnimation = false)
                } else if (!fullSize && !media.isSticker) {
                    val bitmap = stillThumbnail(store, media, edge) ?: throw IOException("Cannot decode the encrypted thumbnail")
                    DecodedMedia(BitmapDrawable(context.resources, bitmap))
                } else {
                    decodeImage(store, media, edge, fullSize || media.isSticker)
                }
                ensureActive()
            }
            loaded = pending; pending = null
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true }
        finally { pending?.close() }
    }
    DisposableEffect(loaded,owner,imageView) {
        val animation=loaded?.drawable as? AnimatedImageDrawable
        val view=imageView
        fun updateAnimation() {
            if(animation==null)return
            val visible = !paused && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                view != null && view.isShown && view.hasWindowFocus() && view.getGlobalVisibleRect(android.graphics.Rect())
            if(visible && StickerAnimationSlots.acquire(animation)){if(!animation.isRunning)animation.start()}
            else {animation.stop();StickerAnimationSlots.release(animation)}
        }
        val observer=object: DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner)=updateAnimation()
            override fun onStop(owner: LifecycleOwner)=updateAnimation()
        }
        val draw = android.view.ViewTreeObserver.OnPreDrawListener { updateAnimation(); true }
        val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { updateAnimation() }
        owner.lifecycle.addObserver(observer)
        if (animation != null) {
            view?.viewTreeObserver?.addOnPreDrawListener(draw)
            view?.viewTreeObserver?.addOnWindowFocusChangeListener(focus)
            updateAnimation()
        }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (animation != null) {
                view?.viewTreeObserver?.takeIf { it.isAlive }?.let { observerTree ->
                    observerTree.removeOnPreDrawListener(draw)
                    observerTree.removeOnWindowFocusChangeListener(focus)
                }
                animation.stop()
                StickerAnimationSlots.release(animation)
            }
        }
    }
    DisposableEffect(loaded){val value=loaded;onDispose {
        imageView?.takeIf {it.drawable===value?.drawable}?.setImageDrawable(null);value?.close()
    }}
    val drawable = loaded?.drawable
    if (onStillBitmap != null) {
        DisposableEffect(drawable) {
            onStillBitmap((drawable as? BitmapDrawable)?.bitmap)
            onDispose { onStillBitmap(null) }
        }
    }
    if (onImageFinished != null) {
        LaunchedEffect(drawable, failed) {
            if (drawable != null || failed) onImageFinished()
        }
    }
    if (onImageFailure != null) {
        LaunchedEffect(failed) {
            if (failed) onImageFailure()
        }
    }
    if (onImageSize != null) {
        LaunchedEffect(drawable) {
            if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                onImageSize(IntSize(drawable.intrinsicWidth, drawable.intrinsicHeight))
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (drawable != null) {
            AndroidView(
                factory = { ImageView(it).also { view -> imageView = view } },
                update = { view ->
                    view.scaleType = if (fitToViewport) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                    if (view.drawable !== drawable) view.setImageDrawable(drawable)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (media.isSticker) {
            if (failed) Text(uiString(R.string.ui_sticker_invalid_image), color = Color.Gray)
            else if (playStickerAnimation) Text(uiString(R.string.ui_loading), color = Color.Gray)
        } else Text(if (failed) "Image preview unavailable" else "Loading image…", color = Color.Gray)
    }
}

private object StickerAnimationSlots {
    private val active=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AnimatedImageDrawable,Boolean>())
    @Synchronized fun acquire(value: AnimatedImageDrawable): Boolean {if(value in active)return true;if(active.size>=3)return false;active.add(value);return true}
    @Synchronized fun release(value: AnimatedImageDrawable){active.remove(value)}
}

private class DecodedMedia(val drawable: Drawable, private val encoded: ByteArray? = null) : AutoCloseable {
    override fun close() { (drawable as? AnimatedImageDrawable)?.stop(); encoded?.fill(0) }
}

private fun decodeImage(store: EncryptedObjectStore, media: AttachmentMetadata, edge: Int, animate: Boolean,
                        playStickerAnimation: Boolean = true): DecodedMedia {
    if (media.isSticker || animate && media.mime == "image/gif" && media.size <= 32L * 1024 * 1024) {
        val encoded = if(media.isSticker)store.readStickerBytes(media.reference) else store.open(media.reference).use { it.readBytes() }
        try {
            if (media.isSticker && !playStickerAnimation) {
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, info, _ ->
                    val scale = minOf(1.0, edge.toDouble() / max(info.size.width, info.size.height))
                    decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                encoded.fill(0)
                return DecodedMedia(BitmapDrawable(android.content.res.Resources.getSystem(), bitmap))
            }
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, info, _ ->
                require(info.size.width > 0 && info.size.height > 0 && info.size.width.toLong() * info.size.height <= 64_000_000)
                val scale = minOf(1.0, (if(media.isSticker)512.0 else 2048.0) / max(info.size.width, info.size.height))
                decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            return DecodedMedia(drawable, encoded)
        } catch (error: Throwable) { encoded.fill(0); throw error }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    store.open(media.reference).use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000)
    val sample = ceil(max(bounds.outWidth, bounds.outHeight).toDouble() / edge).toInt().coerceAtLeast(1)
    val options = BitmapFactory.Options().apply {
        // BitmapFactory rounds down to a power of two; round up here to keep the memory bound.
        inSampleSize = Integer.highestOneBit(sample).let { if (it < sample) it shl 1 else it }
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = store.open(media.reference).use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IOException("Cannot decode the encrypted image")
    return DecodedMedia(BitmapDrawable(org.msgat.MsgATApp.instance.resources, bitmap))
}

/** MetadataRetriever receives a seekable authenticated byte stream, never a pathname. */
internal fun videoThumbnail(store: EncryptedObjectStore, media: AttachmentMetadata, edge: Int = 640): Bitmap? =
    cachedThumbnail(store, media, edge) {
        try {
            EncryptedMediaDataSource(store, media).use { source ->
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(source)
                    retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, edge, edge * 3 / 4)
                }
            }
        } catch (_: Exception) { null }
    }

private fun stillThumbnail(store: EncryptedObjectStore, media: AttachmentMetadata, edge: Int): Bitmap? =
    cachedThumbnail(store, media, edge) {
        decodeImage(store, media, edge, animate = false).use { (it.drawable as? BitmapDrawable)?.bitmap }
    }

private fun cachedThumbnail(store: EncryptedObjectStore, media: AttachmentMetadata, edge: Int,
                            render: () -> Bitmap?): Bitmap? = store.withThumbnailWork {
    val cached = try { store.thumbnailBytes(media.reference, edge) } catch (_: Exception) { null }
    if (cached != null) {
        val bitmap = try { BitmapFactory.decodeByteArray(cached, 0, cached.size) }
        catch (_: Exception) { null }
        finally { cached.fill(0) }
        if (bitmap != null) return@withThumbnailWork bitmap
    }
    val bitmap = render() ?: return@withThumbnailWork null
    try {
        val jpeg = media.isVideo || media.mime == "image/jpeg"
        val format = if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        val buffer = object : ByteArrayOutputStream() { override fun close() { buf.fill(0); super.close() } }
        buffer.use {
            if (bitmap.compress(format, 86, it) && it.size() <= 131072) {
                val encoded = it.toByteArray()
                try { store.cacheThumbnail(media.reference, edge, encoded, if (jpeg) "image/jpeg" else "image/png") }
                finally { encoded.fill(0) }
            }
        }
    } catch (_: Exception) {
        // A cache write failure still leaves the decoded thumbnail available for display.
    }
    bitmap
}

@Composable
internal fun rememberAlbumThumbnail(media: AttachmentMetadata?): State<Bitmap?> {
    val store = org.msgat.MsgATApp.instance.nettyClient.objects
    return produceState<Bitmap?>(null, media?.reference, media?.available) {
        value = null
        if (media?.available == true) value = withContext(encryptedImageWorkers) {
            try {
                if (media.isVideo) videoThumbnail(store, media, 128)
                else if (media.isImage && !media.isSticker) stillThumbnail(store, media, 128)
                else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
}

private class EncryptedMediaDataSource(private val store: EncryptedObjectStore, private val media: AttachmentMetadata) : MediaDataSource() {
    private var input: InputStream? = null
    private var position = 0L
    private var closed = false
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        check(!closed)
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (position >= media.size) return -1
        if (input == null || this.position != position) {
            input?.close(); input = null
            input = store.open(media.reference, position); this.position = position
        }
        val count = checkNotNull(input).read(buffer, offset, minOf(size.toLong(), media.size - position).toInt())
        if (count > 0) this.position += count
        return count
    }
    override fun getSize() = media.size
    @Synchronized override fun close() { closed = true; input?.close(); input = null }
}

/** Used by Media3's extractor worker. Deliberately has no network/file/cache fallback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class EncryptedPlaybackSource(private val store: EncryptedObjectStore, private val media: AttachmentMetadata) : BaseDataSource(false) {
    private var input: InputStream? = null
    private var remaining = 0L
    private var opened = false
    private var uri: Uri? = null
    override fun open(dataSpec: DataSpec): Long {
        check(!opened)
        transferInitializing(dataSpec)
        require(dataSpec.uri.toString() == media.reference && dataSpec.position in 0..media.size)
        remaining = media.size - dataSpec.position
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, dataSpec.length)
        input = store.open(media.reference, dataSpec.position)
        uri = dataSpec.uri; opened = true; transferStarted(dataSpec)
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = checkNotNull(input).read(buffer, offset, minOf(remaining, length.toLong()).toInt())
        if (count < 0) throw IOException("Encrypted media ended unexpectedly")
        remaining -= count; bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri? = uri
    override fun close() {
        try { input?.close() } finally {
            input = null; uri = null; remaining = 0
            if (opened) { opened = false; transferEnded() }
        }
    }
    class Factory(private val store: EncryptedObjectStore, private val media: AttachmentMetadata) : DataSource.Factory {
        override fun createDataSource(): DataSource = EncryptedPlaybackSource(store, media)
    }
}
