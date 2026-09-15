package org.msgat.core.crypto.securestorage

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.MessageDigest
import kotlin.collections.take

class SecurePrefs(private val context: Context) {

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

    init {
        if (!sharedPreferences.getBoolean("relay_tls_compatibility_v1", false)) {
            sharedPreferences.edit {
                remove("server_certificate_sha256")
                putBoolean("allow_development_certificate", true)
                putBoolean("relay_tls_compatibility_v1", true)
            }
        }
    }

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

    var serverCertificateSha256: String?
        get() = sharedPreferences.getString("server_certificate_sha256", null)
        set(value) = sharedPreferences.edit { putString("server_certificate_sha256", value) }

    var allowDevelopmentCertificate: Boolean
        get() = sharedPreferences.getBoolean("allow_development_certificate", true)
        set(value) = sharedPreferences.edit { putBoolean("allow_development_certificate", value) }

    var myNickname: String?
        get() = sharedPreferences.getString("my_nickname", null)
        set(value) = sharedPreferences.edit { putString("my_nickname", value) }

    // Optional TURN relay configuration (empty = pure P2P, ICE always prefers direct)
    var turnServer: String?
        get() = sharedPreferences.getString("turn_server", null)
        set(value) = sharedPreferences.edit { putString("turn_server", value) }

    var turnPort: Int
        get() = sharedPreferences.getInt("turn_port", 3478)
        set(value) = sharedPreferences.edit { putInt("turn_port", value) }

    var turnUsername: String?
        get() = sharedPreferences.getString("turn_username", null)
        set(value) = sharedPreferences.edit { putString("turn_username", value) }

    var turnPassword: String?
        get() = sharedPreferences.getString("turn_password", null)
        set(value) = sharedPreferences.edit { putString("turn_password", value) }

    var turnUseTls: Boolean
        get() = sharedPreferences.getBoolean("turn_use_tls", false)
        set(value) = sharedPreferences.edit { putBoolean("turn_use_tls", value) }

    var myAvatarPath: String?
        get() = sharedPreferences.getString("my_avatar_path", null)
        set(value) = sharedPreferences.edit { putString("my_avatar_path", value) }

    fun profilePayload(): ByteArray {
        val json = org.json.JSONObject().apply {
            put("uuid", myUuid)
            put("nickname", myNickname ?: "Unknown")
            put("avatarPath", "")
            put("avatarData", "")
        }
        try {
        myAvatarPath?.takeIf { it.isNotEmpty() }?.let { path ->
            val uri = if (path.startsWith("/")) android.net.Uri.fromFile(java.io.File(path)) else android.net.Uri.parse(path)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                require(bytes.size <= 180000) { "Avatar exceeds the profile packet limit" }
                json.put("avatarData", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
        } catch (error: Exception) {
            android.util.Log.w("MsgAT", "Avatar unavailable; publishing remaining profile", error)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    // Checks if the required server settings and local identity have been established.
    fun isConfigured(): Boolean = myUuid.isNotEmpty() && !serverIp.isNullOrEmpty() && serverPort in 1..65535

    @Synchronized
    private fun getSeed(): ByteArray {
        val savedSeedBase64 = sharedPreferences.getString(privateKey, null)
        return if (savedSeedBase64 != null) {
            Base64.decode(savedSeedBase64, Base64.NO_WRAP).also { require(it.size == 32) { "Invalid identity seed" } }
        } else {
            val secureRandom = java.security.SecureRandom()
            val seed = ByteArray(32)
            secureRandom.nextBytes(seed)
            val seedBase64 = Base64.encodeToString(seed, Base64.NO_WRAP)
            check(sharedPreferences.edit().putString(privateKey, seedBase64).commit()) { "Cannot persist identity seed" }
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
    @SuppressLint("ApplySharedPref")
    fun burnIdentityAndGenerateNew() {
        context.deleteDatabase("msgat_vault.db")

        val secureRandom = java.security.SecureRandom()
        val newSeed = ByteArray(32)
        secureRandom.nextBytes(newSeed)

        val newSeedBase64 = Base64.encodeToString(newSeed, Base64.NO_WRAP)
        // Synchronous commit is required because identity reset immediately terminates the process.
        sharedPreferences.edit(commit = true) {
            putString(privateKey, newSeedBase64)
        }
    }

    @Synchronized
    fun getDatabasePassphrase(): ByteArray {
        val encoded = sharedPreferences.getString("DB_PASSPHRASE", null)
        if (encoded != null) {
            return Base64.decode(encoded, Base64.DEFAULT).also { require(it.size == 32) { "Invalid database key" } }
        }
        val newKey = ByteArray(32)
        java.security.SecureRandom().nextBytes(newKey)
        check(sharedPreferences.edit().putString("DB_PASSPHRASE", Base64.encodeToString(newKey, Base64.DEFAULT)).commit()) { "Cannot persist database key" }
        return newKey
    }

    // Computes the SHA-256 hash of the provided byte array.
    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.take(32).joinToString("") { "%02x".format(it) }
    }
}
