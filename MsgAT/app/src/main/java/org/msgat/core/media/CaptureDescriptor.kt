// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import java.util.concurrent.atomic.AtomicBoolean

/** Seekable SDK output backed by authenticated encrypted pages, not a plaintext file. */
internal class CaptureDescriptor private constructor(val descriptor:ParcelFileDescriptor,private val released:java.util.concurrent.CompletableFuture<Unit>):AutoCloseable {
    override fun close(){descriptor.close()}
    suspend fun closeAndDrain(){
        try{close()}finally{kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable+kotlinx.coroutines.Dispatchers.IO){released.get()}}
    }
    companion object {
        fun open(context:Context,file:EncryptedCaptureFile,writable:Boolean):CaptureDescriptor {
            val lease=if(writable)null else file.retainFrozen()
            val thread=HandlerThread("MsgAT-recording-io").apply{start()}
            val released=java.util.concurrent.CompletableFuture<Unit>()
            val closed=AtomicBoolean(false)
            fun finish(){if(closed.compareAndSet(false,true))try{lease?.close()}finally{thread.quitSafely();released.complete(Unit)}}
            val callback=object:ProxyFileDescriptorCallback(){
                private inline fun <T> io(action:()->T):T=try{check(!closed.get());action()}catch(error:Exception){throw ErrnoException("encrypted recording",OsConstants.EIO,error)}
                override fun onGetSize():Long=io{file.size}
                override fun onRead(offset:Long,size:Int,data:ByteArray):Int=io{
                    require(size>=0 && size<=data.size)
                    val bytes=file.read(offset,minOf(size,256*1024))
                    try{bytes.copyInto(data);bytes.size}finally{bytes.fill(0)}
                }
                override fun onWrite(offset:Long,size:Int,data:ByteArray):Int {
                    if(!writable)throw ErrnoException("recording write",OsConstants.EBADF)
                    if(size<0 || size>data.size || offset<0 || offset>file.capacity-size)throw ErrnoException("recording write",OsConstants.ENOSPC)
                    return io{try{file.write(offset,data,0,size);size}finally{data.fill(0,0,size)}}
                }
                override fun onFsync(){io{if(writable)file.sync()}}
                override fun onRelease(){finish()}
            }
            try {
                val manager=context.getSystemService(StorageManager::class.java)
                val descriptor=manager.openProxyFileDescriptor(if(writable)ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY,callback,Handler(thread.looper))
                return CaptureDescriptor(descriptor,released)
            }catch(error:Exception){finish();throw error}
        }
    }
}
