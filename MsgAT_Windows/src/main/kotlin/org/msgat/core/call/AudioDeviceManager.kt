package org.msgat.core.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioDeviceManager private constructor() {

    private val _isMicrophoneMuted = MutableStateFlow(false)
    val isMicrophoneMuted: StateFlow<Boolean> = _isMicrophoneMuted.asStateFlow()

    private val _isSpeakerphoneOn = MutableStateFlow(false)
    val isSpeakerphoneOn: StateFlow<Boolean> = _isSpeakerphoneOn.asStateFlow()

    fun setMicrophoneMute(muted: Boolean) {
        _isMicrophoneMuted.value = muted
    }

    fun toggleSpeakerphone() {
        _isSpeakerphoneOn.value = !_isSpeakerphoneOn.value
    }

    fun startCallMode() {
        // Desktop audio mode initialization
    }

    fun endCallMode() {
        _isSpeakerphoneOn.value = false
    }

    companion object {
        @Volatile
        private var INSTANCE: AudioDeviceManager? = null

        fun getInstance(): AudioDeviceManager {
            return INSTANCE ?: synchronized(this) {
                val instance = AudioDeviceManager()
                INSTANCE = instance
                instance
            }
        }
    }
}
