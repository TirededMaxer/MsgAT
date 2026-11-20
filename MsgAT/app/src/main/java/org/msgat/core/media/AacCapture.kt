// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** Frame the platform's AAC-LC output as a seekable M4A while it is recorded. */
@OptIn(UnstableApi::class)
internal fun muxVoiceFrames(source: InputStream, file: EncryptedCaptureFile) {
    val input = DataInputStream(source.buffered(16 * 1024))
    val muxer = CaptureMuxerOutput.muxer(file)
    val header = ByteArray(7)
    val frame = ByteArray(8192)
    var track = -1
    var samples = 0L
    try {
        while (true) {
            val first = input.read()
            if (first < 0) break
            header[0] = first.toByte()
            input.readFully(header, 1, 6)
            fun octet(index: Int) = header[index].toInt() and 255
            val profile = (octet(2) ushr 6) + 1
            val frequency = (octet(2) ushr 2) and 15
            val channels = ((octet(2) and 1) shl 2) or (octet(3) ushr 6)
            val length = ((octet(3) and 3) shl 11) or (octet(4) shl 3) or (octet(5) ushr 5)
            val hasCrc = octet(1) and 1 == 0
            val size = length - if (hasCrc) 9 else 7
            if (first != 255 || octet(1) and 0xf6 != 0xf0 || profile != 2 || frequency != 3 ||
                channels != 1 || octet(6) and 3 != 0 || size !in 1..frame.size)
                throw IOException("Unexpected microphone AAC framing")
            if (hasCrc) input.readUnsignedShort()
            input.readFully(frame, 0, size)
            if (track < 0) {
                val config = byteArrayOf(((profile shl 3) or (frequency ushr 1)).toByte(),
                    (((frequency and 1) shl 7) or (channels shl 3)).toByte())
                track = muxer.addTrack(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
                    .setSampleRate(48_000).setChannelCount(channels).setInitializationData(listOf(config)).build())
            }
            muxer.writeSampleData(track, ByteBuffer.wrap(frame, 0, size), BufferInfo(samples * 1_000_000 / 48_000, size, C.BUFFER_FLAG_KEY_FRAME))
            samples += 1024
        }
        if (track < 0) throw IOException("No microphone samples were recorded")
        muxer.writeSampleData(track, ByteBuffer.allocate(0), BufferInfo(samples * 1_000_000 / 48_000, 0, C.BUFFER_FLAG_END_OF_STREAM))
    } finally {
        frame.fill(0)
        muxer.close()
    }
}
