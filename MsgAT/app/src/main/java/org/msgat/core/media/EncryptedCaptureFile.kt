// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.util.Base64
import kotlinx.coroutines.*
import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Owns admission and drains every encoder before a vault can close or lose identity protection. */
internal class EncryptedCaptureStore(private val objects:EncryptedObjectStore,private val directory:java.io.File){
    private val gate=Any()
    private var closed=false
    private val files=mutableSetOf<EncryptedCaptureFile>()
    private val stopScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    suspend fun open(filename:String,mime:String,capacity:Long=100L*1024*1024,stopWriter:suspend()->Unit):EncryptedCaptureFile {
        var created:EncryptedCaptureFile?=null
        try{return withContext(NonCancellable+Dispatchers.IO){
        synchronized(gate){
            check(!closed){"Recording storage is closed"};require(capacity in 1..100L*1024*1024)
            val candidate=objects.recordingCandidate(capacity,filename,mime)
            val id=candidate.getString("object_id")
            try{
                val result=SecurityCore.call(JSONObject().put("operation","open_capture_file").put("manifest",candidate).put("directory",directory.absolutePath))
                EncryptedCaptureFile(this@EncryptedCaptureStore,objects,id,result.getString("capture_id"),filename,mime,capacity,stopWriter).also{files.add(it);created=it}
            }catch(error:Exception){objects.discardRecording(id);throw error}
        }
        }}catch(cancelled:CancellationException){withContext(NonCancellable){created?.close()};throw cancelled}
    }
    internal fun stop(action:suspend()->Unit):Deferred<Unit> = stopScope.async{action()}
    internal fun removed(file:EncryptedCaptureFile){synchronized(gate){files.remove(file)}}
    suspend fun close()=withContext(NonCancellable){
        val active=withContext(Dispatchers.IO){synchronized(gate){closed=true;files.toList()}}
        var failure: Exception? = null
        try {
            for (file in active) {
                try { file.forceClose() }
                catch (error: Exception) {
                    if (failure == null) failure = error
                }
            }
        } finally {
            stopScope.coroutineContext[Job]?.cancelAndJoin()
        }
        failure?.let { throw it }
    }
}

/** stopWriter only stops/releases encoder/device/FD ownership; it must not call freeze or close on this file. */
internal class EncryptedCaptureFile internal constructor(
    private val store:EncryptedCaptureStore,private val objects:EncryptedObjectStore,private val objectId:String,private val handle:String,
    val filename:String,val mime:String,val capacity:Long,private val stopWriter:suspend()->Unit
){
    private val io=Any()
    private var stopped:Deferred<Unit>?=null
    private var disposed=false
    private var draining=false
    private var frozen=false
    private var producerReleased=false
    private var owners=1
    private var length=0L
    val size:Long get()=synchronized(io){check(!disposed){"The recording is closed"};length}
    private fun call(operation:String,body:JSONObject=JSONObject()):JSONObject=SecurityCore.call(body.put("operation",operation).put("capture_id",handle))
    fun write(offset:Long,bytes:ByteArray,start:Int=0,count:Int=bytes.size-start){synchronized(io){
        check(!disposed && !frozen){"The recording is not writable"};require(offset>=0 && start>=0 && count>=0 && start<=bytes.size-count && offset<=capacity-count)
        var done=0
        while(done<count){val n=minOf(256*1024,count-done)
            val result=call("write_capture_file",JSONObject().put("offset",offset+done).put("bytes",Base64.encodeToString(bytes,start+done,n,Base64.NO_WRAP)))
            check(result.getInt("written")==n){"Incomplete recording write"};length=result.getLong("size");done+=n
        }
    }}
    fun read(offset:Long,count:Int):ByteArray=synchronized(io){
        check(!disposed){"The recording is closed"};require(offset>=0 && count in 0..256*1024)
        val result=call("read_capture_file",JSONObject().put("offset",offset).put("length",count));val bytes=Base64.decode(result.getString("bytes"),Base64.NO_WRAP)
        check(bytes.size==result.getInt("length")){"Incomplete recording read"};bytes
    }
    fun sync(){synchronized(io){check(!disposed){"The recording is closed"};call("sync_capture_file")}}
    fun resize(size: Long) = synchronized(io) {
        check(!disposed && !frozen)
        require(size in 0..capacity)
        length = call("resize_capture_file", JSONObject().put("size", size)).getLong("size")
    }
    internal suspend fun stopHardware(){
        val task=synchronized(io){stopped?:store.stop(stopWriter).also{stopped=it}}
        try{withContext(NonCancellable){task.await()}}catch(error:Exception){synchronized(io){if(stopped===task)stopped=null};throw error}
    }
    suspend fun freeze():Long {
        synchronized(io){check(!disposed && !draining){"Recording storage is closing"}}
        stopHardware()
        return withContext(NonCancellable+Dispatchers.IO){synchronized(io){
            check(!disposed && !draining){"Recording storage is closing"}
            val result=call("freeze_capture_file");length=result.getLong("size");frozen=true;length
        }}
    }
    fun openInputStream():InputStream {
        synchronized(io){check(!disposed && frozen && !draining){"Finish the recording before reading it"};owners++}
        return object:InputStream(){
            private var position=0L
            private val closed=AtomicBoolean(false)
            override fun read():Int{val byte=ByteArray(1);return try{if(read(byte,0,1)<0)-1 else byte[0].toInt() and 255}finally{byte.fill(0)}}
            override fun read(target:ByteArray,offset:Int,count:Int):Int{
                check(!closed.get()){ "The recording reader is closed" };require(offset>=0 && count>=0 && offset<=target.size-count)
                if(count==0)return 0
                val bytes=this@EncryptedCaptureFile.read(position,minOf(count,256*1024))
                try{if(bytes.isEmpty())return -1;bytes.copyInto(target,offset);position+=bytes.size;return bytes.size}finally{bytes.fill(0)}
            }
            override fun close(){if(closed.compareAndSet(false,true))releaseReader()}
        }
    }
    internal fun retainFrozen():AutoCloseable {
        synchronized(io){check(!disposed && frozen && !draining){"Finish the recording before reading it"};owners++}
        val closed=AtomicBoolean(false)
        return AutoCloseable{if(closed.compareAndSet(false,true))releaseReader()}
    }
    internal fun importTo(objectsDirectory:java.io.File,manifest:JSONObject,preparation:ObjectPreparation?):JSONObject=synchronized(io){
        check(!disposed && frozen && !draining){"Finish the recording before importing it"}
        SecurityCore.call(JSONObject().put("operation","import_capture_file").put("capture_id",handle).put("manifest",manifest)
            .put("directory",objectsDirectory.absolutePath).put("maximum_size",100L*1024*1024).put("work_id",preparation?.workID()?:JSONObject.NULL)).getJSONObject("manifest")
    }
    private fun disposeNative(){
        if(disposed)return
        call("close_capture_file");disposed=true
        try{objects.discardRecording(objectId)}finally{store.removed(this)}
    }
    private fun releaseReader(){synchronized(io){check(owners>0);owners--;if(owners==0 && !disposed)disposeNative()}}
    suspend fun close(){
        stopHardware()
        withContext(NonCancellable+Dispatchers.IO){synchronized(io){if(!producerReleased){producerReleased=true;owners--;if(owners==0 && !disposed)disposeNative()}}}
    }
    internal suspend fun forceClose(){
        synchronized(io){draining=true}
        stopHardware()
        withContext(NonCancellable+Dispatchers.IO){synchronized(io){disposeNative()}}
    }
}
