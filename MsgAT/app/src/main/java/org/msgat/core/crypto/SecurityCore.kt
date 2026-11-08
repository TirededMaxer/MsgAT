// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.crypto

import org.json.JSONObject

/** Bounded JNI bridge to the same libsignal core used by both desktop clients. */
@androidx.annotation.Keep
object SecurityCore {
    init {
        System.loadLibrary("msgat_security_jni")
    }

    fun requireAvailable() { check(abiVersion() == 2) { "The native security component has an incompatible version" } }

    fun call(request: JSONObject): JSONObject {
        requireAvailable()
        val encoded = request.toString().toByteArray(Charsets.UTF_8)
        var response: ByteArray? = null
        try {
            require(encoded.isNotEmpty() && encoded.size <= 4 * 1024 * 1024) { "Security request exceeds its size limit" }
            response = nativeCall(encoded)
            check(response.isNotEmpty() && response.size <= 8 * 1024 * 1024) { "Invalid native security response" }
            val envelope = JSONObject(String(response, Charsets.UTF_8))
            check(envelope.optBoolean("ok", false)) { envelope.optString("error", "The security operation failed") }
            return envelope.getJSONObject("result")
        } finally {
            encoded.fill(0)
            response?.fill(0)
        }
    }

    private external fun abiVersion(): Int
    private external fun nativeCall(request: ByteArray): ByteArray
}
