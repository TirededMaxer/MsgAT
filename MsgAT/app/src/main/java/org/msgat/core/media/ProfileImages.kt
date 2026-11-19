// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.nio.ByteBuffer
import android.net.Uri
import java.io.ByteArrayOutputStream

internal object ProfileImages {
    /** User-picked images are bounded and decoded in RAM, with orientation applied by ImageDecoder. */
    fun bitmap(context: Context,uri: Uri,edge: Int=2048): Bitmap {
        require(edge in 1..2048)
        val encoded=context.contentResolver.openInputStream(uri)?.use {input->
            val output=object: ByteArrayOutputStream(){override fun close(){buf.fill(0);reset();super.close()}}
            output.use {val block=ByteArray(65536);try {var total=0;while(true){val count=input.read(block);if(count<0)break;total+=count;require(total<=16*1024*1024){"Image exceeds 16 MiB"};output.write(block,0,count)};output.toByteArray()}finally {block.fill(0)}}
        }?:throw java.io.IOException("Cannot open image")
        try {return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))){decoder,info,_->
            require(info.size.width>0 && info.size.height>0 && info.size.width.toLong()*info.size.height<=64L*1024*1024){"Image dimensions exceed their limit"}
            val ratio=minOf(1.0,edge.toDouble()/maxOf(info.size.width,info.size.height));decoder.setTargetSize(maxOf(1,(info.size.width*ratio).toInt()),maxOf(1,(info.size.height*ratio).toInt()));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }}finally {encoded.fill(0)}
    }

    /** Bounded decode and RAM-only normalization, shared by initial setup and profile editing. */
    fun jpeg(context: Context,uri: Uri): ByteArray {
        fun open()=context.contentResolver.openInputStream(uri)?:error("Cannot open profile image")
        return normalize(::open)
    }
    fun jpeg(bytes: ByteArray): ByteArray=normalize {java.io.ByteArrayInputStream(bytes)}
    private fun normalize(open: () -> java.io.InputStream): ByteArray {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        open().use {BitmapFactory.decodeStream(it,null,bounds)}
        require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=64L*1024*1024)
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1024)sample*=2
        val decoded=open().use {BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply {inSampleSize=sample})}?:error("Cannot decode profile image")
        val longest=maxOf(decoded.width,decoded.height)
        val image=if(longest>512)Bitmap.createScaledBitmap(decoded,maxOf(1,decoded.width*512/longest),maxOf(1,decoded.height*512/longest),true) else decoded
        try {
            val output=ByteArrayOutputStream()
            check(image.compress(Bitmap.CompressFormat.JPEG,82,output))
            return output.toByteArray().also {require(it.size in 1..512*1024)}
        } finally {if(image!==decoded)image.recycle();decoded.recycle()}
    }
}
