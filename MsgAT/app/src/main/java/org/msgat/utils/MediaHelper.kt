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
        val chunks = mutableListOf<String>()
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return chunks
            val bytes = inputStream.readBytes()
            inputStream.close()

            var offset = 0
            while (offset < bytes.size) {
                val length = CHUNK_SIZE.coerceAtMost(bytes.size - offset)
                val chunkBytes = bytes.copyOfRange(offset, offset + length)
                chunks.add(Base64.encodeToString(chunkBytes, Base64.NO_WRAP))
                offset += length
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return chunks
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