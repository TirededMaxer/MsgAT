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

    // Decodes Base64 data and saves it as a media file in the device gallery.
    fun saveBase64ToGallery(context: Context, base64Data: String, ext: String): Uri? {
        return try {

            var cleanBase64 = base64Data.replace(Regex("\\s+"), "")
            cleanBase64 = cleanBase64.replace("=", "")
            val padding = cleanBase64.length % 4
            if (padding > 0) {
                cleanBase64 += "=".repeat(4 - padding)
            }

            val mediaBytes = Base64.decode(cleanBase64, Base64.DEFAULT)

            val fileName = "${System.currentTimeMillis()}.$ext"
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
            val isVideo = mimeType.startsWith("video")

            val directory = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

            val collectionUri = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/MsgAT")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(collectionUri, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(mediaBytes) }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                uri
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaHelper", "FATAL", e)
            null
        }
    }
}