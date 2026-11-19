// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A private in-memory source. It has no file path and is never granted to another application. */
internal class MemoryMediaSource internal constructor(val uri:Uri,private val token:String):AutoCloseable {
    private val closed=AtomicBoolean(false)
    fun retain():MemoryMediaSource {check(!closed.get());MemoryMediaStore.retain(token);return MemoryMediaSource(uri,token)}
    override fun close(){if(closed.compareAndSet(false,true))MemoryMediaStore.release(token)}
}
internal object MemoryMediaStore {
    private data class Entry(val bytes:ByteArray,val name:String,val mime:String,val identity:String,var owners:Int=1)
    private val gate=Any()
    private val entries=HashMap<String,Entry>()
    private var total=0L
    private val random=SecureRandom()
    val writer=ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,ArrayBlockingQueue(8),{r->Thread(r,"MsgAT-memory-media").apply{isDaemon=true}}).apply{allowCoreThreadTimeOut(true)}
    /** Transfers ownership of bytes on success; the caller must erase bytes if registration fails. */
    fun photo(context:Context,bytes:ByteArray):MemoryMediaSource {
        require(bytes.isNotEmpty() && bytes.size<=16*1024*1024){"The captured image is too large"}
        return image(context,bytes,"Photo-${System.currentTimeMillis()}.jpg","image/jpeg")
    }

    /** Registers an edited image without writing a plaintext temporary file. */
    fun image(context:Context,bytes:ByteArray,name:String,mime:String):MemoryMediaSource {
        require(PhotoCompression.supports(mime))
        require(bytes.isNotEmpty() && bytes.size<=32*1024*1024){"The edited image is too large"}
        val app=org.msgat.MsgATApp.instance;check(app.isReady){"The identity is not ready"}
        val tokenBytes=ByteArray(32).also(random::nextBytes);val token=tokenBytes.joinToString(""){"%02x".format(it)};tokenBytes.fill(0)
        synchronized(gate){check(total+bytes.size<=64L*1024*1024){"Discard an edited or captured photo before adding more"};entries[token]=Entry(bytes,name,mime,app.securePrefs.myUuid);total+=bytes.size}
        return MemoryMediaSource(Uri.Builder().scheme("content").authority(context.packageName+".memory-media").appendPath("photo").appendPath(token).build(),token)
    }
    internal fun retain(token:String){synchronized(gate){val value=entries[token]?:throw FileNotFoundException("Photo is no longer available");value.owners++}}
    internal fun release(token:String){synchronized(gate){val value=entries[token]?:return;if(--value.owners==0){entries.remove(token);total-=value.bytes.size;value.bytes.fill(0)}}}
    private fun token(context:Context,uri:Uri):String {
        if(Binder.getCallingUid()!=Process.myUid() || uri.authority!=context.packageName+".memory-media" || uri.pathSegments.size!=2 || uri.pathSegments[0]!="photo")throw FileNotFoundException("Private media source")
        return uri.pathSegments[1]
    }
    data class Details(val name:String,val mime:String,val size:Long)
    fun details(context:Context,uri:Uri):Details=synchronized(gate){
        val entry=entries[token(context,uri)]?:throw FileNotFoundException("Photo is no longer available")
        val app=org.msgat.MsgATApp.instance
        if(!app.isReady || entry.identity!=app.securePrefs.myUuid)throw FileNotFoundException("Photo identity is no longer available")
        Details(entry.name,entry.mime,entry.bytes.size.toLong())
    }
    class ReadLease(val bytes:ByteArray,private val token:String):AutoCloseable {
        private val closed=AtomicBoolean(false)
        override fun close(){if(closed.compareAndSet(false,true))release(token)}
    }
    fun read(context:Context,uri:Uri):ReadLease=synchronized(gate){details(context,uri);val token=token(context,uri);val value=entries[token]!!;value.owners++;ReadLease(value.bytes,token)}
}

class MemoryMediaProvider:ContentProvider(){
    private fun details(uri:Uri):MemoryMediaStore.Details {
        val context=requireNotNull(context)
        return if(RecordingMediaStore.isRecording(context,uri))RecordingMediaStore.details(context,uri) else MemoryMediaStore.details(context,uri)
    }
    override fun onCreate()=true
    override fun getType(uri:Uri)=details(uri).mime
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val detail=details(uri)
        val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply{addRow(columns.map{when(it){OpenableColumns.DISPLAY_NAME->detail.name;OpenableColumns.SIZE->detail.size;else->null}})}
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        if(mode!="r")throw FileNotFoundException("Private media is read-only")
        if(RecordingMediaStore.isRecording(requireNotNull(context),uri))return RecordingMediaStore.open(requireNotNull(context),uri)
        val lease=MemoryMediaStore.read(requireNotNull(context),uri)
        val pipe=try{ParcelFileDescriptor.createReliablePipe()}catch(error:Exception){lease.close();throw error}
        try {
            MemoryMediaStore.writer.execute{
                try{ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use{it.write(lease.bytes)}}
                catch(_:Exception){try{pipe[1].closeWithError("Media read cancelled")}catch(_:Exception){}}
                finally{lease.close();try{pipe[1].close()}catch(_:Exception){}}
            }
        }catch(error:Exception){lease.close();pipe.forEach{it.close()};throw FileNotFoundException("Private media reader is busy")}
        return pipe[0]
    }
    override fun openAssetFile(uri:Uri,mode:String):AssetFileDescriptor {
        val length=details(uri).size
        return AssetFileDescriptor(openFile(uri,mode),0,length)
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException("Read-only")
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read-only")
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read-only")
}
