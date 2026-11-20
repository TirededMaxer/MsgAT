// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import java.io.IOException
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
internal class CaptureMuxerOutput(private val file: EncryptedCaptureFile) : SeekableMuxerOutput {
    private var cursor = 0L
    private var active = true
    private val buffer = ByteArray(64 * 1024)

    override fun isOpen() = active
    override fun getPosition() = cursor
    override fun getSize() = file.size
    override fun setPosition(position: Long) {
        if (!active || position !in 0..file.capacity) throw IOException("Invalid recording offset")
        cursor = position
    }
    override fun truncate(size: Long) {
        if (!active || size !in 0..file.capacity) throw IOException("Invalid recording size")
        if (size < file.size) file.resize(size)
        cursor = minOf(cursor, size)
    }
    override fun write(data: ByteBuffer): Int {
        val size = data.remaining()
        if (!active || cursor > file.capacity - size) throw IOException("Recording size limit exceeded")
        while (data.hasRemaining()) {
            val count = minOf(data.remaining(), buffer.size)
            data.get(buffer, 0, count)
            try { file.write(cursor, buffer, 0, count) } finally { buffer.fill(0, 0, count) }
            cursor += count
        }
        return size
    }
    override fun close() { active = false; buffer.fill(0) }

    companion object {
        fun muxer(file: EncryptedCaptureFile): Mp4Muxer = Mp4Muxer.Builder(CaptureMuxerOutput(file))
            .setSampleBatchingEnabled(false)
            .setAttemptStreamableOutputEnabled(false)
            .build()
    }
}
