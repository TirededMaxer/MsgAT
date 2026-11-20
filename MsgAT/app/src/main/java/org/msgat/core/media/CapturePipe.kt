// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Drain the codec's bounded kernel pipe before freezing or discarding its encrypted destination. */
internal class CapturePipe(consume: (InputStream) -> Unit, onFailure: (Exception) -> Unit) {
    private val descriptors = ParcelFileDescriptor.createReliablePipe()
    val writer = descriptors[1]
    private val stopped = AtomicBoolean()
    private val drain = CoroutineScope(Dispatchers.IO).async {
        descriptors[0].use { reader ->
            val poll = StructPollfd().apply { fd = reader.fileDescriptor; events = OsConstants.POLLIN.toShort() }
            val input = object : InputStream() {
                override fun read(): Int {
                    val value = ByteArray(1)
                    return if (read(value, 0, 1) < 0) -1 else value[0].toInt() and 255
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    while (true) {
                        if (Os.poll(arrayOf(poll), 150) == 0) {
                            if (stopped.get()) return -1
                            continue
                        }
                        val count = Os.read(reader.fileDescriptor, buffer, offset, length)
                        if (count > 0) return count
                        reader.checkError()
                        return -1
                    }
                }
            }
            try { consume(input) }
            catch (error: Exception) { onFailure(error); throw error }
        }
    }

    suspend fun finish() = withContext(NonCancellable + Dispatchers.IO) {
        try { writer.close() } finally { stopped.set(true) }
        drain.await()
    }
}
