// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.msgat.core.media.EncryptedObjectStore

/** Authenticated profile images stay in RAM and bypass Coil's disk cache. */
@Composable
internal fun rememberAvatarPainter(value: Any?): Painter {
    val path=value?.toString().orEmpty()
    if(!path.startsWith(EncryptedObjectStore.REFERENCE_PREFIX))return coil.compose.rememberAsyncImagePainter(value)
    val objects=org.msgat.MsgATApp.instance.nettyClient.objects
    val availability by objects.availability.collectAsState()
    var painter by remember(path) {mutableStateOf<Painter?>(null)}
    LaunchedEffect(path,availability) {
        if(painter!=null)return@LaunchedEffect
        val loaded=withContext(Dispatchers.IO) {
            try {
                val media=objects.metadata(path,"avatar")?:return@withContext null
                if(!media.available || media.size<=0 || media.size>512L*1024)return@withContext null
                val bytes=objects.open(path).use {it.readBytes()}
                try {
                    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth.toLong()*bounds.outHeight>64L*1024*1024)return@withContext null
                    var sample=1
                    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>512)sample*=2
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})?.asImageBitmap()
                } finally {bytes.fill(0)}
            } catch(_: Exception) {null}
        }
        loaded?.let {painter=BitmapPainter(it)}
    }
    return painter?:remember {ColorPainter(Color.Transparent)}
}
