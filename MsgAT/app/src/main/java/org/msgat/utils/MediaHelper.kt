package org.msgat.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64

object MediaHelper {

    private const val CHUNK_SIZE = 45000

    // Reads a file from a Uri and splits it into manageable Base64 encoded chunks.
    fun uriToBase64Chunks(context: Context, uri: Uri): List<String> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= 100L * 1024 * 1024) { "Files cannot exceed 100 MB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("Cannot open media")
        if (bytes.isEmpty()) return listOf("")
        return (bytes.indices step CHUNK_SIZE).map { offset ->
            Base64.encodeToString(bytes, offset, minOf(CHUNK_SIZE, bytes.size - offset), Base64.NO_WRAP)
        }
    }

    // Determines the total byte size of a file referenced by a Uri.
    fun getFileSize(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    return cursor.getLong(sizeIndex)
                }
            }

            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                return it.statSize
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return 0L
    }

    // Extracts the MIME type or file extension from a given Uri.
    fun getExtensionFromUri(context: Context, uri: Uri): String {
        return android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(
            context.contentResolver.getType(uri)
        ) ?: "bin"
    }
}
