// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.IOException

/** Gives media parsers random access to an authenticated recording while retaining its vault lease. */
internal class RecordingReader(context: Context, uri: Uri) : MediaDataSource() {
    private val source = RecordingMediaStore.importSource(context, uri)
    private val length = source.first.size
    private var closed = false

    override fun getSize() = length

    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        check(!closed)
        require(position >= 0 && size >= 0 && offset >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (position >= length) return -1
        val chunk = source.first.read(position, minOf(size.toLong(), length - position, 256L * 1024).toInt())
        try {
            if (chunk.isEmpty()) throw IOException("Recording ended before its authenticated size")
            chunk.copyInto(buffer, offset)
            return chunk.size
        } finally { chunk.fill(0) }
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; source.second.close() }
    }
}

@OptIn(UnstableApi::class)
internal object RecordingPlayback {
    fun factory(context: Context, uri: Uri) = DefaultMediaSourceFactory(DataSource.Factory { Source(context, uri) })

    fun player(context: Context, uri: Uri): ExoPlayer {
        val builder = ExoPlayer.Builder(context)
        if (RecordingMediaStore.isRecording(context, uri)) builder.setMediaSourceFactory(factory(context, uri))
        return builder.build()
    }

    private class Source(private val context: Context, private val uri: Uri) : BaseDataSource(false) {
        private var reader: RecordingReader? = null
        private var position = 0L
        private var remaining = 0L

        override fun open(dataSpec: DataSpec): Long {
            check(reader == null)
            require(dataSpec.uri == uri)
            transferInitializing(dataSpec)
            val input = RecordingReader(context, uri)
            try {
                if (dataSpec.position !in 0..input.size) throw IOException("Recording seek is outside the file")
                position = dataSpec.position
                remaining = input.size - position
                if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, dataSpec.length)
                reader = input
                transferStarted(dataSpec)
                return remaining
            } catch (error: Exception) { reader = null; input.close(); throw error }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val count = checkNotNull(reader).readAt(position, buffer, offset, minOf(remaining, length.toLong()).toInt())
            if (count < 0) throw IOException("Recording ended before its authenticated size")
            position += count
            remaining -= count
            bytesTransferred(count)
            return count
        }

        override fun getUri(): Uri? = if (reader == null) null else uri
        override fun close() {
            val input = reader ?: return
            reader = null
            try { input.close() } finally { transferEnded() }
        }
    }
}
