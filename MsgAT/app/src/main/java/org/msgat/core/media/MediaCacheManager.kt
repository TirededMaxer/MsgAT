package org.msgat.core.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

object MediaCacheManager {

    private fun getMediaCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "media_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Extracts and caches a video file from base64 content if it doesn't already exist.
     * Returns the cached File.
     */
    fun getVideoFileFromBase64(context: Context, msgId: String, base64Content: String): File? {
        try {
            val cacheDir = getMediaCacheDir(context)
            val videoFile = File(cacheDir, "$msgId.mp4")
            
            if (videoFile.exists() && videoFile.length() > 0) {
                return videoFile
            }
            
            val cleanBase64 = cleanBase64String(base64Content)
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            FileOutputStream(videoFile).use { it.write(bytes) }
            return videoFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Generates a video thumbnail (first frame) from a cached video file.
     */
    fun getVideoThumbnail(context: Context, msgId: String, base64Content: String): Bitmap? {
        try {
            val videoFile = getVideoFileFromBase64(context, msgId, base64Content) ?: return null
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            return bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun cleanBase64String(base64: String): String {
        var clean = base64.replace(Regex("\\s+"), "")
        clean = clean.replace("=", "")
        val padding = clean.length % 4
        if (padding > 0) {
            clean += "=".repeat(4 - padding)
        }
        return clean
    }
}
