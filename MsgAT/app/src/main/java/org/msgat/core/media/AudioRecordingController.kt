// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class RecordingPhase { IDLE,RECORDING,PAUSED,READY,CLOSED }

/** Native AAC/MP4 encoder writes only through the encrypted seekable descriptor. */
internal class AudioRecordingController(context:Context,private val captures:EncryptedCaptureStore) {
    private val context=context.applicationContext
    private val mutex=Mutex()
    private var recorder:MediaRecorder?=null
    private var descriptor:CaptureDescriptor?=null
    private var file:EncryptedCaptureFile?=null
    private var microphone: MicrophoneOwnership.RecordingLease? = null
    private var started=false
    private var failure:String?=null
    val phase=MutableStateFlow(RecordingPhase.IDLE)
    val limitReached=MutableStateFlow(false)
    val error=MutableStateFlow<String?>(null)
    suspend fun start() {
        try{withContext(NonCancellable+Dispatchers.IO){mutex.withLock{
            check(phase.value==RecordingPhase.IDLE)
            microphone = MicrophoneOwnership.startRecording(::yieldMicrophone)
            val output=captures.open("Voice-${System.currentTimeMillis()}.m4a","audio/mp4",stopWriter={stopDevice()})
            file=output
            val bridge=CaptureDescriptor.open(context,output,true);descriptor=bridge
            @Suppress("DEPRECATION")
            val encoder=if(android.os.Build.VERSION.SDK_INT>=31)MediaRecorder(context)else MediaRecorder()
            recorder=encoder
            encoder.setAudioSource(MediaRecorder.AudioSource.MIC)
            encoder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            encoder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            encoder.setAudioEncodingBitRate(64_000);encoder.setAudioSamplingRate(48_000);encoder.setAudioChannels(1)
            encoder.setOutputFile(bridge.descriptor.fileDescriptor)
            encoder.setMaxFileSize(95L*1024*1024);encoder.setMaxDuration(30*60*1000)
            encoder.setOnInfoListener{_,what,_->if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)limitReached.value=true}
            encoder.setOnErrorListener{_,_,_->error.value="The microphone recording stopped unexpectedly";limitReached.value=true}
            encoder.prepare();encoder.start();started=true;phase.value=RecordingPhase.RECORDING
        }}}catch(error:Exception){close();throw error}
    }
    suspend fun pause()=withContext(Dispatchers.IO){mutex.withLock{check(phase.value==RecordingPhase.RECORDING);recorder!!.pause();phase.value=RecordingPhase.PAUSED}}
    suspend fun resume()=withContext(Dispatchers.IO){mutex.withLock{check(phase.value==RecordingPhase.PAUSED);recorder!!.resume();phase.value=RecordingPhase.RECORDING}}

    suspend fun inputLevel(): Float? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val encoder = recorder ?: return@withLock null
            if (!started || phase.value != RecordingPhase.RECORDING) return@withLock null
            try {
                kotlin.math.sqrt((encoder.maxAmplitude / 32767f).coerceIn(0f, 1f))
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    private suspend fun yieldMicrophone() {
        val output = mutex.withLock { file }
        if (output != null) output.stopHardware() else stopDevice()
        limitReached.value = true
    }

    private suspend fun stopDevice()=withContext(NonCancellable+Dispatchers.IO){mutex.withLock{
        val encoder=recorder
        try {
            if(encoder!=null && started)try{encoder.stop()}catch(_:RuntimeException){failure="The recording is too short or could not be finalized"}
        }finally {
            started=false
            try {
                encoder?.release()
                recorder=null
                microphone?.close()
                microphone = null
            } finally {
                val bridge=descriptor;descriptor=null;bridge?.closeAndDrain()
            }
        }
    }}
    suspend fun finish():EncryptedCaptureFile {
        val output=mutex.withLock{file?:kotlin.error("No recording is active")}
        output.freeze()
        mutex.withLock{failure?.let{throw java.io.IOException(it)};error.value?.let{throw java.io.IOException(it)};phase.value=RecordingPhase.READY}
        return output
    }
    suspend fun close()=withContext(NonCancellable){
        val output=mutex.withLock{file}
        if(output!=null)output.close()else stopDevice()
        mutex.withLock{file=null;phase.value=RecordingPhase.CLOSED}
    }
}
