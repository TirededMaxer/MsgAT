package org.msgat.core.crypto.securestorage

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecureStorageHelper {

    // Encrypts arbitrary sensitive bytes using Windows DPAPI (or platform fallback).
    fun protectData(plaintext: ByteArray): ByteArray {
        return if (Platform.isWindows()) {
            try {
                Crypt32Util.cryptProtectData(plaintext)
            } catch (e: Exception) {
                fallbackEncrypt(plaintext)
            }
        } else {
            fallbackEncrypt(plaintext)
        }
    }

    // Decrypts data using Windows DPAPI (or platform fallback).
    fun unprotectData(ciphertext: ByteArray): ByteArray {
        return if (Platform.isWindows()) {
            try {
                Crypt32Util.cryptUnprotectData(ciphertext)
            } catch (e: Exception) {
                fallbackDecrypt(ciphertext)
            }
        } else {
            fallbackDecrypt(ciphertext)
        }
    }

    private fun getPlatformKey(): SecretKeySpec {
        val userHome = System.getProperty("user.home")
        val userName = System.getProperty("user.name")
        val combined = "MSGAT_VAULT_KEY_${userName}_$userHome"
        val sha = MessageDigest.getInstance("SHA-256").digest(combined.toByteArray())
        return SecretKeySpec(sha, "AES")
    }

    private fun fallbackEncrypt(plaintext: ByteArray): ByteArray {
        val key = getPlatformKey()
        val iv = ByteArray(12) { 0x4D.toByte() } // Fixed platform IV
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        return cipher.doFinal(plaintext)
    }

    private fun fallbackDecrypt(ciphertext: ByteArray): ByteArray {
        val key = getPlatformKey()
        val iv = ByteArray(12) { 0x4D.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }
}
