package org.msgat.core.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jcajce.provider.digest.Blake2b
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {

    init {

        Security.addProvider(BouncyCastleProvider())
    }

    // Computes a blake2b hash of the UUID to serve as the server routing mailbox ID.
    fun calculateMailboxId(uuid: String): String {
        val digest = Blake2b.Blake2b512()
        val hash = digest.digest(uuid.toByteArray())

        return hash.take(32).joinToString("") { "%02x".format(it) }
    }

    // Signs a network challenge nonce using the local Ed25519 private key for authentication.
    fun signChallenge(privateKey: Ed25519PrivateKeyParameters, nonce: ByteArray): Pair<ByteArray, ByteArray> {

        val publicKey = privateKey.generatePublicKey().encoded

        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(nonce, 0, nonce.size)
        val signature = signer.generateSignature()

        return Pair(publicKey, signature)
    }

    // Fallback encryption for friend requests when the peer's public key is unknown.
    private fun getSymmetricKeyFallback(myUuid: String, targetUuid: String): SecretKeySpec {
        val sorted = listOf(myUuid, targetUuid).sorted()
        val combined = "${sorted[0]}|MSGAT|${sorted[1]}"
        val digest = Blake2b.Blake2b256()
        return SecretKeySpec(digest.digest(combined.toByteArray()), "AES")
    }

    // True ECDH Key Agreement using X25519
    private fun getSymmetricKeyECDH(myPrivateKey: org.bouncycastle.crypto.params.X25519PrivateKeyParameters, peerPublicKeyBase64: String): SecretKeySpec {
        val peerPubBytes = android.util.Base64.decode(peerPublicKeyBase64, android.util.Base64.NO_WRAP)
        val peerPublicKey = org.bouncycastle.crypto.params.X25519PublicKeyParameters(peerPubBytes, 0)

        val agreement = org.bouncycastle.crypto.agreement.X25519Agreement()
        agreement.init(myPrivateKey)

        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(peerPublicKey, sharedSecret, 0)

        val digest = org.bouncycastle.crypto.digests.SHA256Digest()
        val hkdf = org.bouncycastle.crypto.generators.HKDFBytesGenerator(digest)
        hkdf.init(org.bouncycastle.crypto.params.HKDFParameters(sharedSecret, ByteArray(0), "MSGAT_E2EE".toByteArray()))

        val aesKey = ByteArray(32)
        hkdf.generateBytes(aesKey, 0, 32)

        return SecretKeySpec(aesKey, "AES")
    }

    // Encrypts a payload using ECDH.
    fun encryptPayloadECDH(myPrivateKey: org.bouncycastle.crypto.params.X25519PrivateKeyParameters, peerPublicKeyBase64: String, iv: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val key = getSymmetricKeyECDH(myPrivateKey, peerPublicKeyBase64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val cipherTextWithTag = cipher.doFinal(plaintext)

        val cipherText = cipherTextWithTag.copyOfRange(0, cipherTextWithTag.size - 16)
        val authTag = cipherTextWithTag.copyOfRange(cipherTextWithTag.size - 16, cipherTextWithTag.size)
        return Pair(cipherText, authTag)
    }

    // Decrypts a payload using ECDH.
    fun decryptPayloadECDH(myPrivateKey: org.bouncycastle.crypto.params.X25519PrivateKeyParameters, peerPublicKeyBase64: String, iv: ByteArray, cipherText: ByteArray, authTag: ByteArray): ByteArray {
        val key = getSymmetricKeyECDH(myPrivateKey, peerPublicKeyBase64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        val combined = cipherText + authTag
        return cipher.doFinal(combined)
    }

    // Encrypts a payload using fallback (for friend requests).
    fun encryptPayloadFallback(myUuid: String, targetUuid: String, iv: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val key = getSymmetricKeyFallback(myUuid, targetUuid)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val cipherTextWithTag = cipher.doFinal(plaintext)

        val cipherText = cipherTextWithTag.copyOfRange(0, cipherTextWithTag.size - 16)
        val authTag = cipherTextWithTag.copyOfRange(cipherTextWithTag.size - 16, cipherTextWithTag.size)
        return Pair(cipherText, authTag)
    }

    // Decrypts a payload using fallback.
    fun decryptPayloadFallback(myUuid: String, targetUuid: String, iv: ByteArray, cipherText: ByteArray, authTag: ByteArray): ByteArray {
        val key = getSymmetricKeyFallback(myUuid, targetUuid)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        val combined = cipherText + authTag
        return cipher.doFinal(combined)
    }

    // Encrypts a payload using a shared symmetric group key.
    fun encryptGroupPayload(symmetricKeyBase64: String, iv: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val keyBytes = android.util.Base64.decode(symmetricKeyBase64, android.util.Base64.NO_WRAP)
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val cipherTextWithTag = cipher.doFinal(plaintext)

        val cipherText = cipherTextWithTag.copyOfRange(0, cipherTextWithTag.size - 16)
        val authTag = cipherTextWithTag.copyOfRange(cipherTextWithTag.size - 16, cipherTextWithTag.size)
        return Pair(cipherText, authTag)
    }

    // Decrypts a payload using a shared symmetric group key.
    fun decryptGroupPayload(symmetricKeyBase64: String, iv: ByteArray, cipherText: ByteArray, authTag: ByteArray): ByteArray {
        val keyBytes = android.util.Base64.decode(symmetricKeyBase64, android.util.Base64.NO_WRAP)
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        val combined = cipherText + authTag
        return cipher.doFinal(combined)
    }
}