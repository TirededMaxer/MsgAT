// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class VideoTrim(val startMs: Long, val endMs: Long, val muteAudio: Boolean = false) {
    init {
        require(startMs >= 0 && endMs > startMs && endMs - startMs >= 250)
    }
}

@OptIn(UnstableApi::class)
internal object VideoCompression {
    private const val MaximumInput = 100L * 1024 * 1024
    private const val OutputTag = "msgat-encrypted-output"

    suspend fun import(
        context: Context,
        objects: EncryptedObjectStore,
        uri: Uri,
        filename: String,
        preparation: ObjectPreparation?,
        trim: VideoTrim? = null,
        compress: Boolean = true,
        asFile: Boolean = false
    ): JSONObject? {
        preparation?.check()
        val input = try {
            inspect(context, uri)
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return null
        if (trim != null && trim.endMs > input.durationMs) return null
        val outputLimit = if (trim == null) input.size - 1 else MaximumInput
        val durationMs = trim?.let { it.endMs - it.startMs } ?: input.durationMs
        val maxEdge = if (compress) 1280 else 2160
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(input.width, input.height))
        fun scaled(value: Int) = maxOf(2, (value * scale).toInt() and -2)
        val audioAllowance = if (input.hasAudio && trim?.muteAudio != true) 192_000 else 0
        val maximumBitrate = outputLimit * 8.0 * .82 * 1000 / durationMs - audioAllowance
        val originalBitrate = input.size * 8.0 * 1000 / input.durationMs - audioAllowance
        val requestedBitrate = if (compress) minOf(2_500_000.0, originalBitrate * .82)
            else originalBitrate.coerceIn(400_000.0, 10_000_000.0)
        val bitrate = minOf(requestedBitrate, maximumBitrate).toInt()
        if (bitrate < 150_000) return null
        val plan = Plan(scaled(input.width), scaled(input.height), bitrate, trim)
        preparation?.setStep(PreparationStep.ENCODING_VIDEO)

        val owner = EncoderOwner()
        val basename = filename.substringBeforeLast('.').ifBlank { "Video" }
        val label = when {
            trim == null -> "$basename.mp4"
            trim.muteAudio -> "${basename.take(160)}-muted.mp4"
            else -> "${basename.take(160)}-trimmed.mp4"
        }
        val output = objects.captures.open(label, "video/mp4", outputLimit, stopWriter = { owner.stop() })
        var imported: JSONObject? = null
        try {
            val encoded = owner.start(context, uri, plan, output, preparation)
            preparation?.check()
            if (!encoded || output.size == 0L) return null
            output.freeze()
            preparation?.setStep(PreparationStep.IMPORTING)
            val manifest = objects.importRecording(output, preparation, asFile)
            imported = manifest
            return manifest
        } finally {
            try {
                output.close()
            } catch (error: Exception) {
                imported?.let { objects.discardCopy(it) }
                throw error
            }
        }
    }

    private data class Plan(val width: Int, val height: Int, val bitrate: Int, val trim: VideoTrim?)
    private data class Input(val size: Long, val width: Int, val height: Int, val durationMs: Long, val hasAudio: Boolean)

    fun trimDuration(context: Context, uri: Uri): Long? = inspect(context, uri)?.durationMs

    fun mediaItem(uri: Uri, trim: VideoTrim?): MediaItem {
        val item = MediaItem.Builder().setUri(uri)
        if (trim != null) {
            item.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(trim.startMs).setEndPositionMs(trim.endMs).build())
        }
        return item.build()
    }

    private fun inspect(context: Context, uri: Uri): Input? {
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return null
        if (size !in 2..MaximumInput) return null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var video: MediaFormat? = null
            var audioCount = 0
            for (index in 0 until extractor.trackCount) {
                val track = extractor.getTrackFormat(index)
                val mime = track.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") -> {
                        if (video != null) return null
                        video = track
                    }
                    mime.startsWith("audio/") -> if (++audioCount > 1) return null
                    MimeTypes.isText(mime) -> return null
                }
            }
            val track = video ?: return null
            if (!track.containsKey(MediaFormat.KEY_DURATION)) return null
            val duration = track.getLong(MediaFormat.KEY_DURATION)
            if (duration !in 1000..600_000_000L) return null
            val width = track.getInteger(MediaFormat.KEY_WIDTH)
            val height = track.getInteger(MediaFormat.KEY_HEIGHT)
            if (width < 2 || height < 2 || width.toLong() * height > 4096L * 2304) return null
            if (track.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
                track.getInteger(MediaFormat.KEY_COLOR_TRANSFER) in
                listOf(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_TRANSFER_ST2084)) return null

            val rotation = if (track.containsKey(MediaFormat.KEY_ROTATION)) track.getInteger(MediaFormat.KEY_ROTATION) else 0
            val swap = rotation == 90 || rotation == 270
            return Input(size, if (swap) height else width, if (swap) width else height, duration / 1000, audioCount > 0)
        } finally {
            extractor.release()
        }
    }

    private class EncoderOwner {
        private val gate = Mutex()
        private var closing = false
        private var session: EncoderSession? = null

        suspend fun start(context: Context, uri: Uri, plan: Plan, output: EncryptedCaptureFile,
                          preparation: ObjectPreparation?): Boolean {
            val active = gate.withLock {
                if (closing) throw CancellationException("The video encoder is closing")
                EncoderSession(context, uri, plan, output, preparation).also { session = it }
            }
            return active.result.await()
        }

        suspend fun stop() {
            val active = gate.withLock { closing = true; session }
            active?.stop()
            gate.withLock { session = null }
        }
    }

    private class EncoderSession(
        context: Context,
        uri: Uri,
        plan: Plan,
        private val output: EncryptedCaptureFile,
        private val preparation: ObjectPreparation?
    ) {
        val result = CompletableDeferred<Boolean>()
        private val thread = HandlerThread("MsgAT-video-transcode").apply { start() }
        private val handler = Handler(thread.looper)
        private var transformer: Transformer? = null
        private var lastProgress = -1

        private val monitor = object : Runnable {
            override fun run() {
                val active = transformer ?: return
                if (result.isCompleted) return
                if (preparation?.wasCancelled == true) {
                    try {
                        active.cancel()
                    } catch (_: RuntimeException) {
                        // The stop callback also drains the descriptor after cancellation.
                    } finally {
                        result.cancel()
                    }
                    return
                }
                try {
                    val progress = ProgressHolder()
                    if (active.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE && progress.progress != lastProgress) {
                        lastProgress = progress.progress
                        preparation?.report(100, lastProgress.toLong())
                    }
                } catch (_: Exception) {
                    // Progress reporting does not own the encoder or its output.
                }
                handler.postDelayed(this, 150)
            }
        }

        init {
            handler.post {
                try {
                    val encoder = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(plan.bitrate).build())
                        .setEnableFallback(false)
                        .build()
                    val encoderFactory = if (plan.trim == null) encoder else object : Codec.EncoderFactory by encoder {
                        override fun audioNeedsEncoding() = true
                        override fun videoNeedsEncoding() = true
                    }
                    val active = Transformer.Builder(context)
                        .setLooper(thread.looper)
                        .setEncoderFactory(encoderFactory)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setMuxerFactory(EncryptedMuxerFactory(output, plan.trim?.muteAudio == true))
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                handler.removeCallbacks(monitor)
                                transformer = null
                                result.complete(true)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                handler.removeCallbacks(monitor)
                                transformer = null
                                result.complete(false)
                            }
                        }).build()
                    transformer = active
                    val presentation = Presentation.createForWidthAndHeight(plan.width, plan.height, Presentation.LAYOUT_SCALE_TO_FIT)
                    val item = EditedMediaItem.Builder(mediaItem(uri, plan.trim))
                        .setRemoveAudio(plan.trim?.muteAudio == true)
                        .setEffects(Effects(emptyList(), listOf(presentation))).build()
                    active.start(item, OutputTag)
                    handler.post(monitor)
                } catch (_: RuntimeException) {
                    result.complete(false)
                }
            }
        }

        suspend fun stop() = withContext(NonCancellable) {
            val drained = CompletableDeferred<Unit>()
            if (handler.post {
                    handler.removeCallbacks(monitor)
                    try {
                        transformer?.cancel()
                    } catch (_: RuntimeException) {
                        // Joining this encoder thread drains all outstanding writes.
                    } finally {
                        transformer = null
                        result.cancel()
                        thread.quitSafely()
                        drained.complete(Unit)
                    }
                }) drained.await()
            withContext(Dispatchers.IO) { thread.join() }
        }
    }

    private class EncryptedMuxerFactory(private val output: EncryptedCaptureFile, private val muteAudio: Boolean) : Muxer.Factory {
        override fun create(path: String): Muxer {
            check(path == OutputTag)
            val muxer = CaptureMuxerOutput.muxer(output)
            return object : Muxer by muxer {
                override fun addTrack(format: Format): Int {
                    check(!muteAudio || MimeTypes.isVideo(format.sampleMimeType)) { "A muted video must contain only video tracks" }
                    return muxer.addTrack(format)
                }
            }
        }

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
            C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_AAC)
            C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_H264)
            else -> ImmutableList.of()
        }
    }
}
