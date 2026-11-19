// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

/** Call reservation blocks new recorders while the current encoder drains. */
internal object MicrophoneOwnership {
    private val gate = Any()
    private var recording: RecordingLease? = null
    private var call: CallLease? = null

    fun startRecording(drain: suspend () -> Unit): RecordingLease = synchronized(gate) {
        check(call == null && recording == null) { "The microphone is already in use." }
        RecordingLease(drain).also { recording = it }
    }

    fun reserveCall(): CallLease = synchronized(gate) {
        check(call == null) { "A call already owns the microphone." }
        CallLease(recording).also { call = it }
    }

    class RecordingLease internal constructor(internal val drain: suspend () -> Unit) : AutoCloseable {
        override fun close() {
            synchronized(gate) { if (recording === this) recording = null }
        }
    }

    class CallLease internal constructor(private val previous: RecordingLease?) : AutoCloseable {
        suspend fun drainRecording() {
            previous?.drain?.invoke()
            synchronized(gate) {
                check(call === this && recording == null) { "The microphone handoff was interrupted." }
            }
        }

        override fun close() {
            synchronized(gate) { if (call === this) call = null }
        }
    }
}
