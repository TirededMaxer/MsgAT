// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.msgat.core.media.ProfileImages
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.msgat.R
import org.msgat.ui.theme.uiString

internal object ContactCodes {
    fun verification(scan: String)="msgat://verify/"+scan.replace('+','-').replace('/','_').trimEnd('=')
    fun bitmap(value: String): Bitmap {
        require(value.length<=1024)
        val matrix=MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,384,384,mapOf(EncodeHintType.MARGIN to 4,EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
        val pixels=IntArray(matrix.width*matrix.height){index->if(matrix[index%matrix.width,index/matrix.width])android.graphics.Color.BLACK else android.graphics.Color.WHITE}
        return Bitmap.createBitmap(pixels,matrix.width,matrix.height,Bitmap.Config.ARGB_8888).also {pixels.fill(0)}
    }
    fun read(context: Context,uri: Uri): String {val pixels=ProfileImages.bitmap(context,uri);try{return read(pixels)}finally{pixels.recycle()}}
    fun read(image: Bitmap): String {
        require(image.width in 1..2048 && image.height in 1..2048)
        val pixels=IntArray(image.width*image.height)
        try {image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
            val source=RGBLuminanceSource(image.width,image.height,pixels)
            val result=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)),mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true))
            require(result.text.length<=1024){"QR code exceeds its limit"};return result.text
        }finally{pixels.fill(0)}
    }
}
@Composable internal fun ContactCodeImage(value: String){
    var image by remember(value){mutableStateOf<Bitmap?>(null)}
    var failed by remember(value){mutableStateOf(false)}
    LaunchedEffect(value){var generated: Bitmap?=null;try {withContext(Dispatchers.Default){generated=ContactCodes.bitmap(value)};kotlinx.coroutines.currentCoroutineContext().ensureActive();image=generated;generated=null}catch(cancelled: kotlinx.coroutines.CancellationException){throw cancelled}catch(_: Exception){failed=true}finally{generated?.recycle()}}
    DisposableEffect(image){val held=image;onDispose{held?.recycle()}}
    if (failed) androidx.compose.material3.Text(uiString(R.string.ui_contact_qr_unavailable))
    image?.let {
        Image(it.asImageBitmap(), uiString(R.string.ui_contact_qr_description),
            Modifier.widthIn(max = 288.dp).fillMaxWidth().aspectRatio(1f).background(Color.White).padding(16.dp))
    }
}
