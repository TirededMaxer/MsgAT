package org.msgat.core.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object MediaStorageManager {

    private const val TAG = "MediaStorageManager"

    fun getMediaDir(context: Context): File {
        val dir = File(context.filesDir, "media")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Returns the dedicated private file for a given message ID and extension.
     */
    fun getMediaFile(context: Context, msgId: String, ext: String): File {
        val cleanExt = ext.lowercase().removePrefix(".")
        return File(getMediaDir(context), "$msgId.$cleanExt")
    }

    /**
     * Saves raw byte array directly into internal sandbox storage.
     */
    fun saveMediaBytes(context: Context, msgId: String, ext: String, bytes: ByteArray): File {
        val file = getMediaFile(context, msgId, ext)
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    /**
     * Copies media from a content/file Uri into internal sandbox storage.
     */
    fun saveMediaFromUri(context: Context, msgId: String, ext: String, uri: Uri): File {
        val targetFile = getMediaFile(context, msgId, ext)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Cannot open stream for Uri: $uri")
        return targetFile
    }

    /**
     * Resolves a media file from stored content string.
     * Supports both new file paths and legacy Base64 strings seamlessly.
     */
    fun getOrExtractMediaFile(context: Context, msgId: String, ext: String, content: String): File? {
        if (content.isBlank()) return null

        // 1. If content is already a local file path
        if (content.startsWith("/")) {
            val file = File(content)
            if (file.exists() && file.length() > 0) {
                return file
            }
            // Fallback check in default media dir
            val mediaFile = getMediaFile(context, msgId, ext)
            if (mediaFile.exists() && mediaFile.length() > 0) {
                return mediaFile
            }
        }

        // 2. If it's a legacy Base64 string or file not yet extracted
        return try {
            val targetFile = getMediaFile(context, msgId, ext)
            if (targetFile.exists() && targetFile.length() > 0) {
                return targetFile
            }

            var cleanBase64 = content.replace(Regex("\\s+"), "").replace("=", "")
            val padding = cleanBase64.length % 4
            if (padding > 0) {
                cleanBase64 += "=".repeat(4 - padding)
            }
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            saveMediaBytes(context, msgId, ext, bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode/extract media for msgId: $msgId", e)
            null
        }
    }

    /**
     * Saves a local sandbox file into the device public gallery (MediaStore).
     */
    fun saveToGallery(context: Context, file: File, ext: String): Uri? {
        return try {
            if (!file.exists()) return null

            val cleanExt = ext.lowercase().removePrefix(".")
            val fileName = "${System.currentTimeMillis()}.$cleanExt"
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(cleanExt)
                ?: "application/octet-stream"
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
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(file).use { input ->
                        input.copyTo(output)
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                uri
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file to gallery", e)
            null
        }
    }

    /**
     * Deletes a specific media file.
     */
    fun deleteMediaFile(context: Context, msgId: String, ext: String): Boolean {
        return try {
            val file = getMediaFile(context, msgId, ext)
            if (file.exists()) file.delete() else true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete media file for msg: $msgId", e)
            false
        }
    }

    /**
     * Cleans up all media files (e.g. during identity reset).
     */
    fun deleteAllMedia(context: Context) {
        try {
            val dir = getMediaDir(context)
            if (dir.exists()) {
                dir.deleteRecursively()
                dir.mkdirs()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete all media files", e)
        }
    }
}
