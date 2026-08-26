package org.msgat.utils

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ImageCache {
    private val memoryCache = ConcurrentHashMap<String, ImageBitmap>()

    fun get(key: String): ImageBitmap? = memoryCache[key]

    fun put(key: String, bitmap: ImageBitmap) {
        if (memoryCache.size > 200) {
            val iterator = memoryCache.keys().iterator()
            if (iterator.hasNext()) {
                memoryCache.remove(iterator.next())
            }
        }
        memoryCache[key] = bitmap
    }
}

@Composable
fun AsyncImage(
    filePath: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    showLoadingIndicator: Boolean = false,
    placeholder: @Composable (() -> Unit)? = null
) {
    if (filePath.isEmpty()) {
        placeholder?.invoke()
        return
    }

    val cachedBitmap = remember(filePath) { ImageCache.get(filePath) }
    
    val bitmapState = produceState<ImageBitmap?>(initialValue = cachedBitmap, key1 = filePath) {
        if (cachedBitmap != null) {
            value = cachedBitmap
            return@produceState
        }
        val loaded = withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (file.exists() && file.length() > 0) {
                    val bytes = file.readBytes()
                    val skiaImg = Image.makeFromEncoded(bytes)
                    val composeBitmap = skiaImg.toComposeImageBitmap()
                    ImageCache.put(filePath, composeBitmap)
                    composeBitmap
                } else null
            } catch (_: Exception) {
                null
            }
        }
        value = loaded
    }

    val currentBitmap = bitmapState.value
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        if (placeholder != null) {
            placeholder()
        } else if (showLoadingIndicator) {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
