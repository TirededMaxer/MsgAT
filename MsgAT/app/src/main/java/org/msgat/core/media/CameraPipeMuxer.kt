// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.annotation.SuppressLint
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.camera.video.Recorder
import androidx.camera.video.internal.muxer.Muxer
import androidx.media3.common.C
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4OrientationData
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels

/** CameraX owns the encoder, timestamps and pause/resume. Each fragment drains into encrypted staging. */
@OptIn(UnstableApi::class)
// CameraX 1.6.2 restricts both the muxer factory and the interface it requires to the library.
// Keep this adapter paired with the pinned camera-video version.
@SuppressLint("RestrictedApi")
internal class CameraPipeMuxer private constructor() : Muxer {
    private var muxer: FragmentedMp4Muxer? = null
    private fun active() = checkNotNull(muxer)
    private inline fun <T> writing(operation: () -> T): T = try { operation() } catch (error: Exception) {
        throw androidx.camera.video.internal.muxer.MuxerException("Encrypted camera output failed", error)
    }

    override fun setOutput(path: String, format: Int): Unit = throw IOException("Capture requires a private pipe")
    override fun setOutput(parcelFileDescriptor: ParcelFileDescriptor, format: Int) {
        val output = ParcelFileDescriptor.AutoCloseOutputStream(parcelFileDescriptor)
        try {
            require(format == Muxer.MUXER_FORMAT_MPEG_4 || format == Muxer.MUXER_FORMAT_3GPP)
            muxer = FragmentedMp4Muxer.Builder(Channels.newChannel(output)).setFragmentDurationMs(1000).build()
        } catch (error: Exception) { output.close(); throw error }
    }
    override fun setOrientationDegrees(degrees: Int) = active().addMetadataEntry(Mp4OrientationData(degrees))
    override fun setLocation(latitude: Double, longitude: Double) =
        active().addMetadataEntry(Mp4LocationData(latitude.toFloat(), longitude.toFloat()))
    override fun setCaptureFps(captureFps: Int) = Unit
    override fun addTrack(format: MediaFormat) = writing { active().addTrack(MediaFormatUtil.createFormatFromMediaFormat(format)) }
    override fun start() { active() }
    override fun writeSampleData(trackIndex: Int, byteBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val view = byteBuffer.duplicate().apply { position(bufferInfo.offset); limit(bufferInfo.offset + bufferInfo.size) }.slice()
        var flags = 0
        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or C.BUFFER_FLAG_KEY_FRAME
        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) flags = flags or C.BUFFER_FLAG_END_OF_STREAM
        writing { active().writeSampleData(trackIndex, view, BufferInfo(bufferInfo.presentationTimeUs, bufferInfo.size, flags)) }
    }
    override fun stop() = release()
    override fun release() {
        val output = muxer ?: return
        muxer = null
        writing { output.close() }
    }
    override fun isInterruptionResilient() = true

    companion object {
        fun useWith(builder: Recorder.Builder): Recorder.Builder = builder.setMuxerFactory { CameraPipeMuxer() }
    }
}
