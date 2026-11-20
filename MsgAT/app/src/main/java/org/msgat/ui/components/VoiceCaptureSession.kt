// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.ui.components

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.msgat.core.call.CallManager
import org.msgat.core.call.CallState
import org.msgat.core.media.AudioRecordingController
import org.msgat.core.media.EncryptedCaptureStore
import org.msgat.core.media.RecordingMediaSource
import org.msgat.core.media.RecordingMediaStore
import org.msgat.core.media.RecordingReader
import org.msgat.core.media.RecordingPhase
import org.msgat.R

internal class VoiceCaptureSession(context: Context, private val captures: EncryptedCaptureStore) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: AudioRecordingController? = null
    private var operation: Job? = null
    private var limit: Job? = null
    private var clock: Job? = null
    private var previewClock: Job? = null
    private var source: RecordingMediaSource? = null
    private var player: MediaPlayer? = null
    private var previewReader: RecordingReader? = null
    private var previewReady = false
    private var previewPlayRequested = false
    private var finishRequested = false
    private var disposed = false
    private var accumulatedMs = 0L
    private var runningSince: Long? = null
    private val recentLevels = mutableStateListOf<Float>()
    val inputLevels: List<Float> get() = recentLevels

    var phase by mutableStateOf(RecordingPhase.IDLE)
        private set
    var busy by mutableStateOf(false)
        private set
    var closing by mutableStateOf(false)
        private set
    var locked by mutableStateOf(false)
        private set
    var held by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set
    var previewDurationMs by mutableLongStateOf(0L)
        private set
    var previewPositionMs by mutableLongStateOf(0L)
        private set
    @get:StringRes
    var error by mutableStateOf<Int?>(null)
        private set

    val active: Boolean get() = controller != null || phase != RecordingPhase.IDLE || busy || closing

    fun start(hold: Boolean) {
        if (active || disposed) return
        val recorder = AudioRecordingController(context, captures)
        controller = recorder
        error = null
        held = hold
        locked = !hold
        perform {
            recorder.start()
            if (closing) return@perform
            phase = RecordingPhase.RECORDING
            resumeClock()
            limit = scope.launch {
                recorder.limitReached.first { it }
                interrupt()
            }
        }
    }

    fun release() {
        if (!held || locked) return
        held = false
        finish()
    }

    fun lock() {
        if (!held) return
        held = false
        locked = true
    }

    fun togglePause() {
        val recorder = controller ?: return
        if (busy || closing) return
        when (phase) {
            RecordingPhase.RECORDING -> perform {
                recorder.pause()
                pauseClock()
                phase = RecordingPhase.PAUSED
            }
            RecordingPhase.PAUSED -> perform {
                recorder.resume()
                phase = RecordingPhase.RECORDING
                resumeClock()
            }
            else -> Unit
        }
    }

    fun interrupt() {
        closePlayer(keepPosition = true)
        if (phase == RecordingPhase.IDLE && busy || phase == RecordingPhase.RECORDING || phase == RecordingPhase.PAUSED) {
            held = false
            finish()
        }
    }

    fun finish() {
        val recorder = controller ?: return
        if (closing || phase == RecordingPhase.READY || phase == RecordingPhase.CLOSED) return
        if (busy) {
            finishRequested = true
            return
        }
        if (phase != RecordingPhase.RECORDING && phase != RecordingPhase.PAUSED) return
        held = false
        finishRequested = false
        pauseClock()
        perform {
            val output = recorder.finish()
            source = withContext(Dispatchers.IO) { RecordingMediaStore.register(context, output) }
            if (!closing) {
                phase = RecordingPhase.READY
                preparePreview()
            }
        }
    }

    fun togglePreview() {
        if (source == null) return
        if (busy || closing || disposed) return
        try {
            val existing = player
            if (existing != null) {
                if (!previewReady) {
                    previewPlayRequested = !previewPlayRequested
                    return
                }
                if (playing) {
                    existing.pause()
                    playing = false
                    previewPositionMs = existing.currentPosition.toLong()
                    previewClock?.cancel()
                } else startPreview(existing)
                return
            }
            previewPlayRequested = true
            preparePreview()
        } catch (_: Exception) {
            closePlayer(keepPosition = true)
            error = R.string.ui_record_preview_unavailable
        }
    }

    private fun preparePreview() {
        val recording = source ?: return
        try {
            val preview = MediaPlayer()
            player = preview
            preview.setOnPreparedListener {
                if (player === it && !closing && !disposed) {
                    previewReady = true
                    previewDurationMs = it.duration.toLong().coerceAtLeast(0)
                    previewPositionMs = previewPositionMs.coerceIn(0, previewDurationMs)
                    if (previewPositionMs > 0) it.seekTo(previewPositionMs, MediaPlayer.SEEK_CLOSEST)
                    if (error == R.string.ui_record_preview_unavailable) error = null
                    if (previewPlayRequested) startPreview(it)
                    previewPlayRequested = false
                }
            }
            preview.setOnCompletionListener {
                if (player === it) {
                    playing = false
                    previewClock?.cancel()
                    previewPositionMs = 0
                    it.seekTo(0)
                }
            }
            preview.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    closePlayer(keepPosition = true)
                    error = R.string.ui_record_preview_unavailable
                }
                true
            }
            preview.setDataSource(RecordingReader(context, recording.uri).also { previewReader = it })
            preview.prepareAsync()
        } catch (_: Exception) {
            closePlayer(keepPosition = true)
            error = R.string.ui_record_preview_unavailable
        }
    }

    fun seekPreview(positionMs: Long) {
        val preview = player ?: return
        if (!previewReady || closing || previewDurationMs == 0L) return
        previewPositionMs = positionMs.coerceIn(0, previewDurationMs)
        preview.seekTo(previewPositionMs, MediaPlayer.SEEK_CLOSEST)
    }

    private fun startPreview(preview: MediaPlayer) {
        val state = CallManager.getInstance().callState.value
        if (state != CallState.IDLE && state != CallState.ENDED) return
        preview.start()
        playing = true
        previewClock?.cancel()
        previewClock = scope.launch {
            while (player === preview && playing) {
                previewPositionMs = preview.currentPosition.toLong()
                delay(100)
            }
        }
    }

    fun discard(onClosed: (() -> Unit)? = null) = closeRecording(onClosed = onClosed)

    fun use(onRecorded: (RecordingMediaSource) -> Unit) {
        if (phase == RecordingPhase.READY && source != null && !busy) closeRecording(onRecorded = onRecorded)
    }

    fun dispose() {
        disposed = true
        if (!closing) closeRecording()
    }

    private fun perform(action: suspend () -> Unit) {
        busy = true
        val work = scope.launch(start = CoroutineStart.LAZY) {
            try {
                action()
            } catch (_: Exception) {
                if (!closing) {
                    error = R.string.ui_record_unavailable
                    phase = RecordingPhase.CLOSED
                    held = false
                    pauseClock()
                }
            } finally {
                operation = null
                busy = false
                if (finishRequested && !closing) {
                    finishRequested = false
                    finish()
                }
            }
        }
        operation = work
        work.start()
    }

    private fun closeRecording(onClosed: (() -> Unit)? = null, onRecorded: ((RecordingMediaSource) -> Unit)? = null) {
        if (closing) return
        closing = true
        held = false
        finishRequested = false
        pauseClock()
        closePlayer()
        limit?.cancel()
        limit = null
        scope.launch {
            operation?.join()
            closePlayer()
            val recording = source
            try {
                controller?.close()
            } catch (_: Exception) {
                error = R.string.ui_record_close_failed
                closing = false
                if (disposed) {
                    recording?.close()
                    scope.cancel()
                }
                return@launch
            }
            controller = null
            source = null
            recentLevels.clear()
            phase = RecordingPhase.IDLE
            elapsedMs = 0
            accumulatedMs = 0
            locked = false
            error = null
            closing = false
            if (!disposed && recording != null && onRecorded != null) onRecorded(recording)
            else recording?.close()
            if (!disposed) onClosed?.invoke()
            else scope.cancel()
        }
    }

    private fun resumeClock() {
        runningSince = SystemClock.elapsedRealtime()
        clock?.cancel()
        clock = scope.launch {
            val recorder = controller ?: return@launch
            while (true) {
                elapsedMs = accumulatedMs + (SystemClock.elapsedRealtime() - (runningSince ?: break))
                delay(90)
                recorder.inputLevel()?.let { level ->
                    if (recentLevels.size == 28) recentLevels.removeAt(0)
                    recentLevels.add(level)
                }
            }
        }
    }

    private fun pauseClock() {
        runningSince?.let { accumulatedMs += SystemClock.elapsedRealtime() - it }
        runningSince = null
        elapsedMs = accumulatedMs
        clock?.cancel()
        clock = null
    }

    private fun closePlayer(keepPosition: Boolean = false) {
        previewClock?.cancel()
        previewClock = null
        try { player?.release() } finally { previewReader?.close(); previewReader = null }
        player = null
        previewReady = false
        previewPlayRequested = false
        playing = false
        previewDurationMs = 0
        if (!keepPosition) previewPositionMs = 0
    }
}
