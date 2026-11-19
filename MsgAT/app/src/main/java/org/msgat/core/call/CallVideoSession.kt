// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.call

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Views attach to a router so recomposition never touches a disposed WebRTC track. */
internal class CallVideoFrames : VideoSink {
    private val sinks = mutableSetOf<VideoSink>()
    fun attach(sink: VideoSink) = synchronized(sinks) { sinks.add(sink) }
    fun detach(sink: VideoSink) = synchronized(sinks) { sinks.remove(sink) }
    override fun onFrame(frame: VideoFrame) {
        synchronized(sinks) { sinks.forEach { it.onFrame(frame) } }
    }
}

internal class CallVideoSession(
    private val context: Context,
    private val scope: CoroutineScope,
    factory: PeerConnectionFactory,
    val eglContext: EglBase.Context
) {
    private val source = factory.createVideoSource(false)
    val track: VideoTrack = factory.createVideoTrack("msgat-video", source)
    val localFrames = CallVideoFrames()
    val remoteFrames = CallVideoFrames()
    private var remote: VideoTrack? = null
    private val device = Mutex()
    private val closed = AtomicBoolean(false)
    private val generation = AtomicInteger()
    private var capturer: CameraVideoCapturer? = null
    private var texture: SurfaceTextureHelper? = null
    private var capturing = false
    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()
    private val _front = MutableStateFlow(true)
    val front = _front.asStateFlow()
    private val _remoteAvailable = MutableStateFlow(false)
    val remoteAvailable = _remoteAvailable.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        track.setEnabled(false)
        track.addSink(localFrames)
    }

    fun attachRemote(track: VideoTrack) {
        if (closed.get() || remote?.id() == track.id()) return
        remote?.removeSink(remoteFrames)
        remote = track
        track.addSink(remoteFrames)
        _remoteAvailable.value = true
    }

    fun enable(enabled: Boolean) {
        if (closed.get() || _enabled.value == enabled) return
        _enabled.value = enabled
        if (enabled) _error.value = null
        track.setEnabled(enabled)
        val attempt = generation.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            try {
                device.withLock {
                    if (closed.get() || generation.get() != attempt) return@withLock
                    if (enabled && !capturing) {
                        val camera = capturer ?: createCapturer()
                        if (closed.get() || generation.get() != attempt) return@withLock
                        capturing = true
                        camera.startCapture(640, 480, 24)
                    } else if (!enabled) {
                        stopCapture()
                    }
                }
            } catch (_: Exception) {
                scope.launch { if (generation.get() == attempt) cameraFailed() }
            }
        }
    }

    fun switchCamera() {
        if (!_enabled.value || closed.get()) return
        val attempt = generation.get()
        scope.launch(Dispatchers.IO) {
            try {
                device.withLock {
                    if (!capturing || closed.get() || generation.get() != attempt) return@withLock
                    capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                        override fun onCameraSwitchDone(front: Boolean) {
                            if (generation.get() == attempt && !closed.get()) _front.value = front
                        }
                        override fun onCameraSwitchError(message: String) {
                            if (generation.get() == attempt && !closed.get()) _error.value = "Could not switch camera."
                        }
                    })
                }
            } catch (_: Exception) {
                if (generation.get() == attempt && !closed.get()) _error.value = "Could not switch camera."
            }
        }
    }

    /** Called before the peer connection frees its remote receiver. */
    fun detachTracks() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        _enabled.value = false
        track.setEnabled(false)
        track.removeSink(localFrames)
        remote?.removeSink(remoteFrames)
        remote = null
        _remoteAvailable.value = false
    }

    fun releaseCamera(): Job =
        scope.launch(Dispatchers.IO) {
            device.withLock {
                runCatching { stopCapture() }
                runCatching { capturer?.dispose() }
                runCatching { texture?.dispose() }
                runCatching { track.dispose() }
                runCatching { source.dispose() }
            }
        }

    private fun createCapturer(): CameraVideoCapturer {
        val enumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: error("No camera")
        val camera = enumerator.createCapturer(name, object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(message: String) { scope.launch { cameraFailed() } }
            override fun onCameraDisconnected() { scope.launch { cameraFailed() } }
            override fun onCameraFreezed(message: String) { scope.launch { cameraFailed() } }
            override fun onCameraOpening(name: String) = Unit
            override fun onFirstFrameAvailable() = Unit
            override fun onCameraClosed() = Unit
        }) ?: error("No camera")
        _front.value = enumerator.isFrontFacing(name)
        var helper: SurfaceTextureHelper? = null
        try {
            helper = SurfaceTextureHelper.create("MsgAT call camera", eglContext) ?: error("No camera texture")
            camera.initialize(helper, context, source.capturerObserver)
            texture = helper
            capturer = camera
            return camera
        } catch (failure: Exception) {
            try { camera.dispose() } finally { helper?.dispose() }
            throw failure
        }
    }

    private fun stopCapture() {
        if (!capturing) return
        capturer?.stopCapture()
        capturing = false
    }

    private fun cameraFailed() {
        if (closed.get() || !_enabled.value) return
        enable(false)
        _error.value = "Camera unavailable. Audio is still active."
    }
}
