package org.msgat.core.media

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Base64

object MediaStorageManager {

    private var customBaseDir: File? = null

    fun init(baseDir: File) {
        customBaseDir = File(baseDir, "media")
        if (customBaseDir?.exists() == false) {
            customBaseDir?.mkdirs()
        }
    }

    fun getMediaDir(): File {
        val dir = customBaseDir ?: File(org.msgat.core.crypto.securestorage.SecurePrefs.getDefaultDataDirectory(), "media")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getMediaFile(msgId: String, ext: String): File {
        val cleanExt = ext.lowercase().removePrefix(".")
        return File(getMediaDir(), "$msgId.$cleanExt")
    }

    fun saveMediaBytes(msgId: String, ext: String, bytes: ByteArray): File {
        val file = getMediaFile(msgId, ext)
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    fun saveMediaFromFile(msgId: String, ext: String, sourceFile: File): File {
        val targetFile = getMediaFile(msgId, ext)
        FileInputStream(sourceFile).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        return targetFile
    }

    fun getOrExtractMediaFile(msgId: String, ext: String, content: String): File? {
        if (content.isBlank()) return null

        // 1. If content is already a local file path
        if (content.startsWith("/") || content.contains(":\\") || content.startsWith("\\")) {
            val file = File(content)
            if (file.exists() && file.length() > 0) {
                return file
            }
            // Fallback check in default media dir
            val mediaFile = getMediaFile(msgId, ext)
            if (mediaFile.exists() && mediaFile.length() > 0) {
                return mediaFile
            }
        }

        // 2. If it's a Base64 string or file not yet extracted
        return try {
            val targetFile = getMediaFile(msgId, ext)
            if (targetFile.exists() && targetFile.length() > 0) {
                return targetFile
            }

            var cleanBase64 = content.replace(Regex("\\s+"), "").replace("=", "")
            val padding = cleanBase64.length % 4
            if (padding > 0) {
                cleanBase64 += "=".repeat(4 - padding)
            }
            val bytes = Base64.getDecoder().decode(cleanBase64)
            saveMediaBytes(msgId, ext, bytes)
        } catch (e: Exception) {
            println("Failed to decode/extract media for msgId: $msgId: ${e.message}")
            null
        }
    }

    fun deleteMediaFile(msgId: String, ext: String): Boolean {
        return try {
            val file = getMediaFile(msgId, ext)
            if (file.exists()) file.delete() else true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteAllMedia() {
        try {
            val dir = getMediaDir()
            if (dir.exists()) {
                dir.deleteRecursively()
                dir.mkdirs()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
