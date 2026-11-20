// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class VideoRecordingController(context:Context,private val captures:EncryptedCaptureStore,
    private val stopCamera:suspend()->Unit) {
    private val context=context.applicationContext
    private val mutex=Mutex()
    private var file:EncryptedCaptureFile?=null
    private var pipe:CapturePipe?=null
    private var recording:Recording?=null
    private var microphone: MicrophoneOwnership.RecordingLease? = null
    private var finalized:CompletableDeferred<Unit>?=null
    private var failure:String?=null
    val phase=MutableStateFlow(RecordingPhase.IDLE)
    val elapsedMillis=MutableStateFlow(0L)
    val autoFinished=MutableStateFlow(false)
    val error=MutableStateFlow<String?>(null)

    @SuppressLint("MissingPermission") // The capture dialog obtains camera/microphone access first.
    suspend fun start(recorder:Recorder,sound:Boolean)=withContext(NonCancellable+Dispatchers.Main.immediate){
        try{mutex.withLock{
            check(phase.value==RecordingPhase.IDLE)
            if (sound) microphone = MicrophoneOwnership.startRecording(::yieldMicrophone)
            val output=captures.open("Video-${System.currentTimeMillis()}.mp4","video/mp4",stopWriter={stopDevice()})
            file=output
            val bridge=CapturePipe({ input ->
                val buffer=ByteArray(64*1024)
                var offset=0L
                try {
                    while(true) {
                        val count=input.read(buffer)
                        if(count<0) break
                        output.write(offset,buffer,0,count)
                        offset+=count
                    }
                } finally { buffer.fill(0) }
            }, { error.value="The video recording could not be written";autoFinished.value=true })
            pipe=bridge
            val completed=CompletableDeferred<Unit>();finalized=completed
            val options=FileDescriptorOutputOptions.Builder(bridge.writer).setFileSizeLimit(95L*1024*1024).setDurationLimitMillis(10*60*1000L).build()
            var pending=recorder.prepareRecording(context,options)
            if(sound)pending=pending.withAudioEnabled()
            recording=pending.start(ContextCompat.getMainExecutor(context)){event->
                elapsedMillis.value=event.recordingStats.recordedDurationNanos/1_000_000
                when(event){
                    is VideoRecordEvent.Start->phase.value=RecordingPhase.RECORDING
                    is VideoRecordEvent.Pause->phase.value=RecordingPhase.PAUSED
                    is VideoRecordEvent.Resume->phase.value=RecordingPhase.RECORDING
                    is VideoRecordEvent.Finalize->{
                        if(event.hasError() && event.error!=VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED && event.error!=VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED){
                            failure="Video recording could not be finalized";error.value=failure
                        }
                        completed.complete(Unit);autoFinished.value=true
                    }
                    else->Unit
                }
            }
        }}catch(failed:Exception){close();throw failed}
    }
    suspend fun pause()=withContext(Dispatchers.Main.immediate){mutex.withLock{check(phase.value==RecordingPhase.RECORDING);recording!!.pause()}}
    suspend fun resume()=withContext(Dispatchers.Main.immediate){mutex.withLock{check(phase.value==RecordingPhase.PAUSED);recording!!.resume()}}
    private suspend fun yieldMicrophone() {
        val output = mutex.withLock { file }
        if (output != null) output.stopHardware() else stopDevice()
        autoFinished.value = true
    }

    private suspend fun stopDevice()=withContext(NonCancellable){
        val completion=withContext(Dispatchers.Main.immediate){mutex.withLock{recording?.let{it.stop();finalized}}}
        if (completion != null && withTimeoutOrNull(8_000) { completion.await(); true } == null) {
            failure="Video recording could not be finalized"
            withContext(Dispatchers.Main.immediate) { stopCamera() }
        }
        withContext(Dispatchers.Main.immediate){mutex.withLock{
            recording?.close();recording=null
            microphone?.close();microphone=null
            stopCamera()
        }}
        val bridge=mutex.withLock{val value=pipe;pipe=null;value}
        try { bridge?.finish() } catch (_: Exception) { failure="Video recording could not be finalized" }
    }
    suspend fun finish():EncryptedCaptureFile {
        val output=mutex.withLock{file?:kotlin.error("No recording is active")}
        output.freeze()
        mutex.withLock{(failure ?: error.value)?.let{throw java.io.IOException(it)};phase.value=RecordingPhase.READY}
        return output
    }
    suspend fun close()=withContext(NonCancellable){
        val output=mutex.withLock{file}
        if(output!=null)output.close()else stopDevice()
        mutex.withLock{file=null;phase.value=RecordingPhase.CLOSED}
    }
}
