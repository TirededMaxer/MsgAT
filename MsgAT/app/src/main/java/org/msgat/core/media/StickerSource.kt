// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore
import java.util.zip.Inflater

internal data class StickerSourceEntry(val id: Int,val name: String,val size: Int,val compressedSize: Int,val method: Int)
internal data class StickerImageInfo(val width: Int,val height: Int,val frames: Int) {
    fun validate(){require(width in 1..2048 && height in 1..2048 && frames in 1..300){"Sticker dimensions or frame count exceed their limits"}}
}
internal data class StickerSourceImage(val bytes: ByteArray,val mime: String,val extension: String,val sha256: String,val info: StickerImageInfo) {fun clear(){bytes.fill(0)}}
internal class StickerSource private constructor(private val session: String?,val format: String,val entries: List<StickerSourceEntry>,private val context: Context?=null,private val documents: Map<Int,Uri> = emptyMap()) {
    private val gate=Any();private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);private val slots=Semaphore(2);private var closed=false
    suspend fun read(entry: StickerSourceEntry): StickerSourceImage {
        val task=synchronized(gate){check(!closed){"Sticker import is closed"};require(entries.contains(entry));scope.async {slots.withPermit {
            val uri=documents[entry.id]
            if(uri==null)readCore(entry) else {
                val source=open(checkNotNull(context),uri)
                try {require(source.format=="image" && source.entries.size==1){"Select image files, not nested archives"};source.read(source.entries.single())}
                finally {source.close()}
            }
        }}}
        return withContext(NonCancellable){task.await()}
    }
    private suspend fun readCore(entry: StickerSourceEntry): StickerSourceImage {
        val packed=ByteArray(entry.compressedSize);var image: ByteArray?=null;var returned=false;var inspected=false
        try {
            var offset=0
            while(offset<packed.size) {
                currentCoroutineContext().ensureActive();val count=minOf(1024*1024,packed.size-offset)
                val bytes=android.util.Base64.decode(SecurityCore.call(JSONObject().put("operation","read_sticker_source").put("session",session).put("id",entry.id).put("offset",offset).put("length",count)).getString("data"),android.util.Base64.NO_WRAP)
                try {check(bytes.size==count);bytes.copyInto(packed,offset);offset+=count} finally {bytes.fill(0)}
            }
            image=when(entry.method) {
                0->{check(packed.size==entry.size);packed}
                8->{
                    val decoded=ByteArray(entry.size);val inflater=Inflater(true)
                    try {
                        inflater.setInput(packed);var count=0
                        while(count<decoded.size && !inflater.finished()) {currentCoroutineContext().ensureActive();val n=inflater.inflate(decoded,count,decoded.size-count);check(n>0){"Truncated sticker compression"};count+=n}
                        // Consume the end marker even when the output exactly filled the buffer.
                        val probe=ByteArray(1)
                        try {val extra=inflater.inflate(probe);check(extra==0 && inflater.finished() && inflater.remaining==0 && count==decoded.size){"Sticker size mismatch"}} finally {probe.fill(0)}
                        decoded
                    } catch(error: Exception){decoded.fill(0);throw error} finally {inflater.end()}
                }
                else->error("Unsupported sticker compression")
            }
            var identity: JSONObject?=null;offset=0
            while(offset<image.size) {
                currentCoroutineContext().ensureActive();val count=minOf(1024*1024,image.size-offset)
                identity=SecurityCore.call(JSONObject().put("operation","inspect_sticker_chunk").put("session",session).put("id",entry.id).put("offset",offset)
                    .put("bytes",android.util.Base64.encodeToString(image,offset,count,android.util.Base64.NO_WRAP)).put("finish",offset+count==image.size))
                offset+=count
            }
            val value=checkNotNull(identity){"Empty sticker image"};inspected=true
            val xor=value.getInt("xor");if(xor!=0)for(i in image.indices)image[i]=(image[i].toInt() xor xor).toByte()
            currentCoroutineContext().ensureActive()
            val info=StickerImageInfo(value.getInt("width"),value.getInt("height"),value.getInt("frames"));info.validate()
            val result=StickerSourceImage(image,value.getString("mime"),value.getString("extension"),value.getString("sha256"),info);returned=true;return result
        } finally {
            try {if(!inspected)SecurityCore.call(JSONObject().put("operation","discard_sticker_inspection").put("session",session).put("id",entry.id))}
            finally {if(packed!==image || !returned)packed.fill(0);if(!returned && image!==packed)image?.fill(0)}
        }
    }
    suspend fun close() {
        withContext(NonCancellable+Dispatchers.IO){
            val first=synchronized(gate){if(closed)false else {closed=true;true}}
            if(first){scope.coroutineContext[Job]?.cancelAndJoin();session?.let {SecurityCore.call(JSONObject().put("operation","close_sticker_source").put("session",it))}}
        }
    }
    companion object {
        private val imageExtensions=setOf("png","jpg","jpeg","gif","webp","bmp","dat")
        /** Only user-granted SAF entries are enumerated; no other application's private directories. */
        suspend fun openFiles(context: Context,uris: List<Uri>): StickerSource=withContext(NonCancellable+Dispatchers.IO){
            require(uris.isNotEmpty() && uris.size<=4096){"Select at most 4096 image files"}
            val unique=uris.distinct()
            val entries=unique.mapIndexed {index,uri->
                val (name,size)=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use {c->
                    if(c.moveToFirst())(c.getString(0)?:"image") to (if(c.isNull(1))0L else c.getLong(1)) else null
                }?:error("Cannot read selected image")
                require(size in 0..8388608){"Sticker file exceeds 8 MiB"}
                StickerSourceEntry(index,name,size.toInt(),size.toInt(),0)
            }
            StickerSource(null,"files",entries,context.applicationContext,entries.indices.associateWith {unique[it]})
        }
        suspend fun openTree(context: Context,tree: Uri): StickerSource=withContext(NonCancellable+Dispatchers.IO){
            val pending=java.util.ArrayDeque<Triple<String,String,Int>>();pending.add(Triple(DocumentsContract.getTreeDocumentId(tree),"",0))
            val entries=mutableListOf<StickerSourceEntry>();val uris=mutableMapOf<Int,Uri>();val visited=mutableSetOf<String>();var examined=0
            while(pending.isNotEmpty()){
                val (parent,prefix,depth)=pending.removeFirst();check(visited.add(parent)){"Folder contains a directory cycle"}
                val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
                context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE),null,null,null)?.use {c->
                    while(c.moveToNext()){
                        check(++examined<=20000){"Selected folder contains too many files"}
                        val id=c.getString(0);val name=c.getString(1)?:"image";val mime=c.getString(2);val size=if(c.isNull(3))0L else c.getLong(3)
                        if(mime==DocumentsContract.Document.MIME_TYPE_DIR){check(depth<12){"Selected folder is too deeply nested"};pending.add(Triple(id,"$prefix$name/",depth+1))}
                        else if((mime?.startsWith("image/")==true || name.substringAfterLast('.',"").lowercase() in imageExtensions) && size in 0..8388608){
                            check(entries.size<4096){"Select a smaller folder (at most 4096 stickers)"};val index=entries.size
                            entries+=StickerSourceEntry(index,"$prefix$name",size.toInt(),size.toInt(),0);uris[index]=DocumentsContract.buildDocumentUriUsingTree(tree,id)
                        }
                    }
                }?:error("Cannot read selected folder")
            }
            check(entries.isNotEmpty()){"No supported images in selected folder"}
            StickerSource(null,"folder",entries,context.applicationContext,uris)
        }

        suspend fun open(context: Context,uri: Uri): StickerSource=withContext(NonCancellable) {withContext(Dispatchers.IO) {
            var session: String?=null
            try {
                val display=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0) else null}
                val catalog=withContext(NonCancellable) {
                    context.contentResolver.openFileDescriptor(uri,"r")?.use {fd ->
                        SecurityCore.call(JSONObject().put("operation","open_sticker_source").put("source","/proc/self/fd/${fd.fd}")).also {session=it.getString("session")}
                    }?:error("Cannot open selected sticker source")
                }
                currentCoroutineContext().ensureActive()
                val list=catalog.getJSONArray("entries");val format=catalog.getString("format")
                StickerSource(checkNotNull(session),format,List(list.length()){index->val e=list.getJSONObject(index);StickerSourceEntry(e.getInt("id"),if(format=="image")display?:e.getString("name") else e.getString("name"),e.getInt("size"),e.getInt("compressed_size"),e.getInt("method"))})
            } catch(error: Exception){session?.let {runCatching {SecurityCore.call(JSONObject().put("operation","close_sticker_source").put("session",it))}};throw error}
        }}
    }
}
