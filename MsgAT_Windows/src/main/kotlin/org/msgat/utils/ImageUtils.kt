package org.msgat.utils

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File

object ImageUtils {

    fun loadBitmap(file: File?): ImageBitmap? {
        if (file == null || !file.exists() || file.length() == 0L) return null
        return try {
            val bytes = file.readBytes()
            Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}
