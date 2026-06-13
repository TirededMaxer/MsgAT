package org.msgat.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64

object MediaHelper {

    // 为了适应 500KB 的总包大小，我们将单个 Base64 字符串的实际载荷控制在 250KB 左右
    private const val CHUNK_SIZE = 255999

    /**
     * 将选中的图片 Uri 转换为 Base64 分片列表 (处理发送)
     */
    fun uriToBase64Chunks(context: Context, uri: Uri): List<String> {
        val chunks = mutableListOf<String>()
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return chunks
            val bytes = inputStream.readBytes()
            inputStream.close()

            // 计算需要切多少片
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

    /**
     * 自动识别文件后缀
     */
    fun getExtensionFromUri(context: Context, uri: Uri): String {
        return android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(
            context.contentResolver.getType(uri)
        ) ?: "bin"
    }

    /**
     * 将 Base64 数据保存到系统相册 (处理接收并点击保存)
     */
    fun saveBase64ToGallery(context: Context, base64Data: String, ext: String): Boolean {
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

                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaHelper", "FATAL", e)
            false
        }
    }
}