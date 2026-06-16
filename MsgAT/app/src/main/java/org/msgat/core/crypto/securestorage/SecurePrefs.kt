package org.msgat.core.crypto.securestorage

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.MessageDigest
import kotlin.collections.take

class SecurePrefs(context: Context) {

    private val privateKey = "PRIVATE_KEY"

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "MSGAT_SECURE_PREFS",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    val myUuid: String
        get() {
            val priv = getOrCreatePrivateKey()
            val pubBytes = priv.generatePublicKey().encoded
            return sha256(pubBytes)
        }

    val myX25519PublicKeyBase64: String
        get() {
            val priv = getX25519PrivateKey()
            val pubBytes = priv.generatePublicKey().encoded
            return Base64.encodeToString(pubBytes, Base64.NO_WRAP)
        }

    var serverIp: String?
        get() = sharedPreferences.getString("server_ip", null)
        set(value) = sharedPreferences.edit { putString("server_ip", value) }

    var serverPort: Int
        get() = sharedPreferences.getInt("server_port", 0)
        set(value) = sharedPreferences.edit { putInt("server_port", value) }

    var myNickname: String?
        get() = sharedPreferences.getString("my_nickname", null)
        set(value) = sharedPreferences.edit { putString("my_nickname", value) }

    var myAvatarPath: String?
        get() = sharedPreferences.getString("my_avatar_path", null)
        set(value) = sharedPreferences.edit { putString("my_avatar_path", value) }

    // Checks if the required server settings and local identity have been established.
    fun isConfigured(): Boolean = myUuid.isNotEmpty() && !serverIp.isNullOrEmpty() && serverPort > 0

    private fun getSeed(): ByteArray {
        val savedSeedBase64 = sharedPreferences.getString(privateKey, null)
        return if (savedSeedBase64 != null) {
            Base64.decode(savedSeedBase64, Base64.NO_WRAP)
        } else {
            val secureRandom = java.security.SecureRandom()
            val seed = ByteArray(32)
            secureRandom.nextBytes(seed)
            val seedBase64 = Base64.encodeToString(seed, Base64.NO_WRAP)
            sharedPreferences.edit { putString(privateKey, seedBase64) }
            seed
        }
    }

    // Retrieves the existing Ed25519 private key from the Android Keystore or generates a new one.
    fun getOrCreatePrivateKey(): Ed25519PrivateKeyParameters {
        return Ed25519PrivateKeyParameters(getSeed(), 0)
    }

    fun getX25519PrivateKey(): org.bouncycastle.crypto.params.X25519PrivateKeyParameters {
        return org.bouncycastle.crypto.params.X25519PrivateKeyParameters(getSeed(), 0)
    }

    // Irreversibly deletes the current identity and generates a fresh cryptographic keypair.
    fun burnIdentityAndGenerateNew() {

        val secureRandom = java.security.SecureRandom()
        val newSeed = ByteArray(32)
        secureRandom.nextBytes(newSeed)

        val newSeedBase64 = Base64.encodeToString(newSeed, Base64.NO_WRAP)
        sharedPreferences.edit {
            putString(privateKey, newSeedBase64)
        }
    }

    // Computes the SHA-256 hash of the provided byte array.
    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.take(32).joinToString("") { "%02x".format(it) }
    }
}