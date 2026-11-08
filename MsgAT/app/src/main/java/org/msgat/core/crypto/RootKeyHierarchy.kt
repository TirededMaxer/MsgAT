// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Versioned local key hierarchy. Wire-session keys are managed separately by libsignal. */
object RootKeyHierarchy {
    private val salt = "MsgAT/v2/root-key-hierarchy".toByteArray(Charsets.UTF_8)
    private val validPurpose = Regex("[a-z0-9/-]{1,64}")

    fun derive(seed: ByteArray, purpose: String): ByteArray {
        require(seed.size == 32) { "Invalid root seed" }
        require(validPurpose.matches(purpose)) { "Invalid key purpose" }
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(seed, salt, purpose.toByteArray(Charsets.UTF_8)))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, it.size) }
    }

    fun wrap(seed: ByteArray, purpose: String, secret: ByteArray): ByteArray {
        require(secret.size == 32) { "Invalid data key" }
        val key = derive(seed, "wrap/$purpose")
        try {
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD("MsgAT/v2/wrapped-key/$purpose".toByteArray(Charsets.UTF_8))
            return nonce + cipher.doFinal(secret)
        } finally {
            key.fill(0)
        }
    }

    fun unwrap(seed: ByteArray, purpose: String, envelope: ByteArray): ByteArray {
        require(envelope.size == 60) { "Invalid wrapped data key" }
        val key = derive(seed, "wrap/$purpose")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, envelope.copyOfRange(0, 12)))
            cipher.updateAAD("MsgAT/v2/wrapped-key/$purpose".toByteArray(Charsets.UTF_8))
            return cipher.doFinal(envelope, 12, envelope.size - 12)
        } finally {
            key.fill(0)
        }
    }
}
