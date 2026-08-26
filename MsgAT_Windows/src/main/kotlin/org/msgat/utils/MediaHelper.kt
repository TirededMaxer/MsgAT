package org.msgat.utils

import java.io.File
import java.io.FileInputStream
import java.util.Base64

object MediaHelper {

    private const val CHUNK_SIZE = 45000

    // Reads a file from local path and splits it into manageable Base64 encoded chunks.
    fun fileToBase64Chunks(file: File): List<String> {
        val chunks = mutableListOf<String>()
        try {
            val bytes = file.readBytes()
            var offset = 0
            while (offset < bytes.size) {
                val length = CHUNK_SIZE.coerceAtMost(bytes.size - offset)
                val chunkBytes = bytes.copyOfRange(offset, offset + length)
                chunks.add(Base64.getEncoder().encodeToString(chunkBytes))
                offset += length
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return chunks
    }

    // Extracts the file extension from a given File or file path string.
    fun getExtension(file: File): String {
        return file.extension.ifEmpty { "bin" }.lowercase()
    }
}
