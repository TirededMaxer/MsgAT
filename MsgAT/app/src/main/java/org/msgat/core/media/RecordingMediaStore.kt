// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileNotFoundException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

internal class RecordingMediaSource internal constructor(val uri:Uri,private val token:String):AutoCloseable {
    private val closed=AtomicBoolean(false)
    override fun close(){if(closed.compareAndSet(false,true))RecordingMediaStore.release(token)}
}
/** Capabilities only live in this process. Registry ownership never creates a media database row. */
internal object RecordingMediaStore {
    private data class Entry(val file:EncryptedCaptureFile,val lease:AutoCloseable,val identity:String)
    private val gate=Any()
    private val entries=mutableMapOf<String,Entry>()
    private val random=SecureRandom()
    fun isRecording(context:Context,uri:Uri)=uri.scheme=="content" && uri.authority==context.packageName+".memory-media" && uri.pathSegments.firstOrNull()=="recording"
    fun register(context:Context,file:EncryptedCaptureFile):RecordingMediaSource {
        val app=org.msgat.MsgATApp.instance;check(app.isReady)
        val lease=file.retainFrozen()
        try {
            val randomBytes=ByteArray(32).also(random::nextBytes)
            val token=try{randomBytes.joinToString(""){"%02x".format(it)}}finally{randomBytes.fill(0)}
            synchronized(gate){check(entries.size<4){"Discard an existing recording first"};entries[token]=Entry(file,lease,app.securePrefs.myUuid)}
            return RecordingMediaSource(Uri.Builder().scheme("content").authority(context.packageName+".memory-media").appendPath("recording").appendPath(token).build(),token)
        }catch(error:Exception){lease.close();throw error}
    }
    internal fun release(token:String){val entry=synchronized(gate){entries.remove(token)};entry?.lease?.close()}
    private fun entry(context:Context,uri:Uri):Entry=synchronized(gate){
        if(Binder.getCallingUid()!=Process.myUid() || !isRecording(context,uri) || uri.pathSegments.size!=2)throw FileNotFoundException("Private recording source")
        val entry=entries[uri.pathSegments[1]]?:throw FileNotFoundException("Recording is no longer available")
        val app=org.msgat.MsgATApp.instance
        if(!app.isReady || entry.identity!=app.securePrefs.myUuid)throw FileNotFoundException("Recording identity is no longer available")
        entry
    }
    fun details(context:Context,uri:Uri):MemoryMediaStore.Details {
        val file=entry(context,uri).file;return MemoryMediaStore.Details(file.filename,file.mime,file.size)
    }
    fun open(context:Context,uri:Uri):ParcelFileDescriptor=CaptureDescriptor.open(context,entry(context,uri).file,false).descriptor
    fun importSource(context:Context,uri:Uri):Pair<EncryptedCaptureFile,AutoCloseable> {
        val file=entry(context,uri).file;return file to file.retainFrozen()
    }
}
