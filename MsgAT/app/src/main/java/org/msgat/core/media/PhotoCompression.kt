// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

internal object PhotoCompression {
    private const val MaximumInput = 32 * 1024 * 1024
    private const val MaximumEdge = 2048

    fun supports(mime: String): Boolean = mime == "image/jpeg" || mime == "image/png"

    internal class ErasingBuffer : ByteArrayOutputStream() {
        override fun close() {
            buf.fill(0)
            reset()
        }
    }

    /** Returns an owned encoded image only when it is smaller than the original. */
    fun compress(context: Context, uri: Uri, mime: String, preparation: ObjectPreparation?): ByteArray? {
        if (!supports(mime)) return null
        val encoded = readEncoded(context, uri, preparation) ?: return null
        try {
            preparation?.check()
            val bitmap = decodeStaticPhoto(encoded, mime, MaximumEdge) ?: return null
            try {
                preparation?.check()
                return ErasingBuffer().use { output ->
                    if (bitmap.compress(format(mime), 82, output) && output.size() in 1 until encoded.size) {
                        preparation?.check()
                        output.toByteArray()
                    } else null
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            encoded.fill(0)
        }
    }

    fun openForEditing(context: Context, uri: Uri, mime: String): Bitmap? {
        if (!supports(mime)) return null
        val encoded = readEncoded(context, uri, null) ?: return null
        try {
            return decodeStaticPhoto(encoded, mime, 2560)
        } finally {
            encoded.fill(0)
        }
    }

    fun format(mime: String): Bitmap.CompressFormat =
        if (mime == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG

    private fun readEncoded(context: Context, uri: Uri, preparation: ObjectPreparation?): ByteArray? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            ErasingBuffer().use { output ->
                val block = ByteArray(64 * 1024)
                try {
                    while (true) {
                        preparation?.check()
                        val count = input.read(block)
                        if (count < 0) break
                        if (output.size() + count > MaximumInput) return null
                        output.write(block, 0, count)
                    }
                    output.toByteArray()
                } finally {
                    block.fill(0)
                }
            }
        }
    }

    private fun decodeStaticPhoto(encoded: ByteArray, mime: String, edge: Int): Bitmap? {
        if (mime == "image/png" && !isStaticPng(encoded)) return null
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                require(info.mimeType == mime && !info.isAnimated &&
                    width > 0 && height > 0 && width.toLong() * height <= 64_000_000)
                val scale = minOf(1.0, edge.toDouble() / maxOf(width, height))
                decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isStaticPng(bytes: ByteArray): Boolean {
        val input = ByteBuffer.wrap(bytes)
        if (input.remaining() < 8 || input.long != 0x89504E470D0A1A0AUL.toLong()) return false
        while (input.remaining() >= 12) {
            val length = input.int
            val type = input.int
            if (length < 0 || length > input.remaining() - 4) return false
            if (type == 0x6163544C) return false // acTL precedes the first IDAT in APNG.
            if (type == 0x49444154) return true
            input.position(input.position() + length + 4)
        }
        return false
    }
}
