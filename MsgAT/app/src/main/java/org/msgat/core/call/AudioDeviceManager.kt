package org.msgat.core.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.msgat.MsgATApp
import org.msgat.R

@Suppress("DEPRECATION") // Android 10 and 11 require the legacy SCO and speakerphone APIs.
class AudioDeviceManager private constructor(context: Context) {
    private val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
    private val power = checkNotNull(context.getSystemService(PowerManager::class.java))
    private val handler = Handler(Looper.getMainLooper())
    private val proximity = if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
        power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "MsgAT:ProximityLock")
    } else null

    private var inCall = false
    private var videoCall = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var focus: AudioFocusRequest? = null
    private var manualId: Int? = null
    private var pendingSco: AudioDeviceInfo? = null
    private var scoActive = false
    private var automaticScoAttempt: Int? = null
    private val scoTimeout = Runnable { if (pendingSco != null) scoFailed() }
    private var pendingCommunicationId: Int? = null
    private val communicationTimeout = Runnable {
        if (inCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && pendingCommunicationId != null) {
            reportCommunicationRoute(audio.communicationDevice)
            if (pendingCommunicationId != null) {
                clearCommunicationPending()
                selectDevice(null)
                _errors.tryEmit(R.string.ui_call_audio_fallback)
            }
        }
    }

    private val _availableDevices = MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val availableDevices = _availableDevices.asStateFlow()
    private val _selectedDevice = MutableStateFlow<AudioDeviceInfo?>(null)
    val selectedDevice = _selectedDevice.asStateFlow()
    private val _automatic = MutableStateFlow(true)
    val automatic = _automatic.asStateFlow()
    private val _switching = MutableStateFlow(false)
    val switching = _switching.asStateFlow()
    private val _errors = MutableSharedFlow<Int>(extraBufferCapacity = 2)
    val errors = _errors.asSharedFlow()
    private val _isMicrophoneMuted = MutableStateFlow(false)
    val isMicrophoneMuted = _isMicrophoneMuted.asStateFlow()

    init {
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
        }, handler)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(context)) { selected ->
                if (inCall) reportCommunicationRoute(selected)
            }
        } else {
            ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!inCall || intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                    when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            val device = pendingSco ?: return
                            audio.isBluetoothScoOn = true
                            scoActive = true
                            clearScoPending()
                            setActual(device)
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED, AudioManager.SCO_AUDIO_STATE_ERROR ->
                            if (pendingSco != null || scoActive) scoFailed()
                    }
                }
            }, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        refresh()
    }

    fun startCallMode(video: Boolean = false) {
        if (inCall) return
        inCall = true
        videoCall = video
        previousMode = audio.mode
        previousSpeaker = audio.isSpeakerphoneOn
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }
            .build()
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
        refresh()
    }

    fun endCallMode() {
        if (!inCall) return
        inCall = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            clearCommunicationPending()
            audio.clearCommunicationDevice()
        } else {
            stopSco()
            audio.isSpeakerphoneOn = previousSpeaker
        }
        if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = previousMode
        focus?.let(audio::abandonAudioFocusRequest)
        focus = null
        manualId = null
        automaticScoAttempt = null
        _automatic.value = true
        _isMicrophoneMuted.value = false
        setActual(null)
    }

    fun setMicrophoneMute(muted: Boolean) { _isMicrophoneMuted.value = muted }

    fun selectDevice(device: AudioDeviceInfo?) {
        if (!inCall) return
        val candidate = device?.let { requested -> _availableDevices.value.firstOrNull { it.id == requested.id } }
        if (device != null && candidate == null) {
            _errors.tryEmit(R.string.ui_call_audio_device_unavailable)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val accepted = try {
                if (candidate == null) { audio.clearCommunicationDevice(); true }
                else audio.setCommunicationDevice(candidate)
            } catch (_: SecurityException) { false }
            if (!accepted) { _errors.tryEmit(R.string.ui_call_audio_switch_failed); return }
            clearCommunicationPending()
            manualId = candidate?.id
            _automatic.value = candidate == null
            pendingCommunicationId = candidate?.id
            reportCommunicationRoute(audio.communicationDevice)
            if (pendingCommunicationId != null) {
                _switching.value = true
                handler.postDelayed(communicationTimeout, 5_000)
            }
            return
        }

        manualId = candidate?.id
        _automatic.value = candidate == null
        if (candidate?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            if (scoActive && _selectedDevice.value?.id == candidate.id) return
            requestSco(candidate)
        } else {
            stopSco()
            audio.isSpeakerphoneOn = candidate?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            setActual(candidate ?: legacyDefault())
            if (candidate == null) {
                automaticScoAttempt = null
                refresh()
            }
        }
    }

    private fun refresh() {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.availableCommunicationDevices else {
            val legacy = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { device ->
                device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || wired(device.type)
            }
            if (legacy.any { wired(it.type) }) legacy.filterNot { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } else legacy
        }
        _availableDevices.value = devices
        if (!inCall) return
        val scoDevice = pendingSco ?: _selectedDevice.value?.takeIf { scoActive }
        if (scoDevice != null && devices.none { it.id == scoDevice.id }) {
            scoFailed()
            return
        }
        if (manualId != null && devices.none { it.id == manualId }) {
            selectDevice(null)
            _errors.tryEmit(R.string.ui_call_audio_fallback)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) reportCommunicationRoute(audio.communicationDevice)
        else if (pendingSco == null && !scoActive) {
            setActual(devices.firstOrNull { it.id == manualId } ?: legacyDefault())
            val bluetooth = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (bluetooth == null) automaticScoAttempt = null
            else if (manualId == null && automaticScoAttempt != bluetooth.id && devices.none { wired(it.type) }) {
                automaticScoAttempt = bluetooth.id
                requestSco(bluetooth)
            }
        }
    }

    private fun reportCommunicationRoute(device: AudioDeviceInfo?) {
        if (pendingCommunicationId != null && device?.id == pendingCommunicationId) clearCommunicationPending()
        setActual(device)
    }

    private fun clearCommunicationPending() {
        handler.removeCallbacks(communicationTimeout)
        pendingCommunicationId = null
        _switching.value = false
    }

    private fun requestSco(device: AudioDeviceInfo) {
        stopSco()
        try {
            audio.isSpeakerphoneOn = false
            pendingSco = device
            _switching.value = true
            setActual(legacyDefault())
            audio.startBluetoothSco()
            handler.postDelayed(scoTimeout, 5_000)
        } catch (_: SecurityException) { scoFailed() }
    }

    private fun legacyDefault(): AudioDeviceInfo? {
        val devices = _availableDevices.value
        val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        return if (audio.isSpeakerphoneOn) speaker else devices.firstOrNull { wired(it.type) }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } ?: speaker
    }

    private fun setActual(device: AudioDeviceInfo?) {
        _selectedDevice.value = device
        val hold = inCall && !videoCall && device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        if (hold && proximity?.isHeld == false) proximity.acquire(4 * 60 * 60 * 1000L)
        else if (!hold && proximity?.isHeld == true) proximity.release()
    }

    private fun clearScoPending() {
        handler.removeCallbacks(scoTimeout)
        pendingSco = null
        _switching.value = false
    }

    private fun stopSco() {
        val requested = pendingSco != null || scoActive
        clearScoPending()
        scoActive = false
        if (requested) runCatching { audio.isBluetoothScoOn = false; audio.stopBluetoothSco() }
    }

    private fun scoFailed() {
        stopSco()
        manualId = null
        _automatic.value = true
        setActual(legacyDefault())
        _errors.tryEmit(R.string.ui_call_bluetooth_unavailable)
    }

    fun getDeviceName(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        else -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Audio device"
    }

    private fun wired(type: Int): Boolean = type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_USB_HEADSET

    companion object {
        private val instance by lazy { AudioDeviceManager(MsgATApp.instance.applicationContext) }
        fun getInstance() = instance
    }
}
