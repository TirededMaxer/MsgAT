package org.msgat.core.call

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.msgat.MsgATApp

class AudioDeviceManager private constructor(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    private var proximityWakeLock: android.os.PowerManager.WakeLock? = null
    private var inCallMode = false

    private val _availableDevices = MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val availableDevices: StateFlow<List<AudioDeviceInfo>> = _availableDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioDeviceInfo?>(null)
    val selectedDevice: StateFlow<AudioDeviceInfo?> = _selectedDevice.asStateFlow()

    private val _isMicrophoneMuted = MutableStateFlow(false)
    val isMicrophoneMuted: StateFlow<Boolean> = _isMicrophoneMuted.asStateFlow()

    private val _isSpeakerphoneOn = MutableStateFlow(false)
    val isSpeakerphoneOn: StateFlow<Boolean> = _isSpeakerphoneOn.asStateFlow()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            updateAvailableDevices()
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
            updateAvailableDevices()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        if (powerManager.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = powerManager.newWakeLock(
                android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "MsgAT:ProximityLock"
            )
        }
        updateAvailableDevices()
    }

    private fun updateAvailableDevices() {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }

        // Filter out non-communication devices if needed, but for simplicity we keep all valid outputs
        val validDevices = devices.filter {
            it.type in listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET
            )
        }

        _availableDevices.value = validDevices

        // Auto-select logic if current device was removed
        if (_selectedDevice.value == null || !validDevices.any { it.id == _selectedDevice.value?.id }) {
            val bluetooth = validDevices.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            val wired = validDevices.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            val earpiece = validDevices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            val speaker = validDevices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            selectDevice(bluetooth ?: wired ?: earpiece ?: speaker)
        }
    }

    private fun updateProximityWakeLock() {
        val deviceType = _selectedDevice.value?.type
        val shouldHold = inCallMode && deviceType == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

        if (shouldHold && proximityWakeLock?.isHeld == false) {
            proximityWakeLock?.acquire(4 * 60 * 60 * 1000L)
        } else if (!shouldHold && proximityWakeLock?.isHeld == true) {
            proximityWakeLock?.release()
        }
    }

    fun selectDevice(device: AudioDeviceInfo?) {
        if (device == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.setCommunicationDevice(device)
        } else {
            // Legacy fallback
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                    audioManager.isSpeakerphoneOn = true
                }
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                    audioManager.isSpeakerphoneOn = false
                }
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                    audioManager.isSpeakerphoneOn = false
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
                else -> Unit
            }
        }
        _selectedDevice.value = device
        _isSpeakerphoneOn.value = device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        updateProximityWakeLock()
    }

    fun toggleSpeakerphone() {
        if (_isSpeakerphoneOn.value) {
            // Switch to earpiece
            val earpiece = _availableDevices.value.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            selectDevice(earpiece)
        } else {
            // Switch to speaker
            val speaker = _availableDevices.value.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            selectDevice(speaker)
        }
    }

    fun setMicrophoneMute(muted: Boolean) {
        _isMicrophoneMuted.value = muted
        // WebRTC will actually handle the track muting, but we keep state here
        // The actual muting happens in CallManager by disabling the AudioTrack
    }

    fun startCallMode() {
        inCallMode = true
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }
        updateAvailableDevices()
        updateProximityWakeLock()
    }

    fun endCallMode() {
        inCallMode = false
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.isSpeakerphoneOn = false
            audioManager.stopBluetoothSco()
        }
        _isSpeakerphoneOn.value = false // Fix UI out-of-sync
        updateProximityWakeLock()
    }

    fun getDeviceName(device: AudioDeviceInfo): String {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone Earpiece"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Device"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            else -> "Unknown Device"
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AudioDeviceManager? = null

        fun getInstance(): AudioDeviceManager {
            return INSTANCE ?: synchronized(this) {
                val instance = AudioDeviceManager(MsgATApp.instance.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
