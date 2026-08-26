package org.msgat.core.crypto.securestorage

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties

class SecurePrefs(val dataDirectory: File? = null) {

    private val baseDir: File = dataDirectory ?: getDefaultDataDirectory()
    private val configFile: File = File(baseDir, "msgat_vault.sec")
    private val properties = Properties()

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        loadProperties()
    }

    private fun loadProperties() {
        if (configFile.exists()) {
            try {
                val encryptedBytes = configFile.readBytes()
                val decryptedBytes = SecureStorageHelper.unprotectData(encryptedBytes)
                val tempProps = Properties()
                tempProps.load(decryptedBytes.inputStream())
                properties.putAll(tempProps)
            } catch (e: Exception) {
                println("Failed to decrypt secure prefs: ${e.message}")
            }
        }
    }

    private fun saveProperties() {
        try {
            val byteStream = java.io.ByteArrayOutputStream()
            properties.store(byteStream, "MsgAT DPAPI Encrypted Vault")
            val encryptedBytes = SecureStorageHelper.protectData(byteStream.toByteArray())
            configFile.writeBytes(encryptedBytes)
        } catch (e: Exception) {
            e.printStackTrace()
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
            return Base64.getEncoder().encodeToString(pubBytes)
        }

    var serverIp: String?
        get() = properties.getProperty("server_ip")
        set(value) {
            if (value != null) properties.setProperty("server_ip", value)
            else properties.remove("server_ip")
            saveProperties()
        }

    var serverPort: Int
        get() = properties.getProperty("server_port")?.toIntOrNull() ?: 0
        set(value) {
            properties.setProperty("server_port", value.toString())
            saveProperties()
        }

    var myNickname: String?
        get() = properties.getProperty("my_nickname")
        set(value) {
            if (value != null) properties.setProperty("my_nickname", value)
            else properties.remove("my_nickname")
            saveProperties()
        }

    var myAvatarPath: String?
        get() = properties.getProperty("my_avatar_path")
        set(value) {
            if (value != null) properties.setProperty("my_avatar_path", value)
            else properties.remove("my_avatar_path")
            saveProperties()
        }

    // Optional TURN relay settings (empty = pure P2P), mirrors Android/macOS
    var turnServer: String?
        get() = properties.getProperty("turn_server")
        set(value) {
            if (value != null) properties.setProperty("turn_server", value)
            else properties.remove("turn_server")
            saveProperties()
        }

    var turnPort: Int
        get() = properties.getProperty("turn_port")?.toIntOrNull() ?: 0
        set(value) {
            properties.setProperty("turn_port", value.toString())
            saveProperties()
        }

    var turnUsername: String?
        get() = properties.getProperty("turn_username")
        set(value) {
            if (value != null) properties.setProperty("turn_username", value)
            else properties.remove("turn_username")
            saveProperties()
        }

    var turnPassword: String?
        get() = properties.getProperty("turn_password")
        set(value) {
            if (value != null) properties.setProperty("turn_password", value)
            else properties.remove("turn_password")
            saveProperties()
        }

    var turnUseTls: Boolean
        get() = properties.getProperty("turn_use_tls")?.toBoolean() ?: false
        set(value) {
            properties.setProperty("turn_use_tls", value.toString())
            saveProperties()
        }

    fun isConfigured(): Boolean = myUuid.isNotEmpty() && !serverIp.isNullOrEmpty() && serverPort > 0

    private fun getSeed(): ByteArray {
        val savedSeedBase64 = properties.getProperty("PRIVATE_KEY")
        return if (savedSeedBase64 != null) {
            Base64.getDecoder().decode(savedSeedBase64)
        } else {
            val secureRandom = SecureRandom()
            val seed = ByteArray(32)
            secureRandom.nextBytes(seed)
            val seedBase64 = Base64.getEncoder().encodeToString(seed)
            properties.setProperty("PRIVATE_KEY", seedBase64)
            saveProperties()
            seed
        }
    }

    fun getOrCreatePrivateKey(): Ed25519PrivateKeyParameters {
        return Ed25519PrivateKeyParameters(getSeed(), 0)
    }

    fun getX25519PrivateKey(): X25519PrivateKeyParameters {
        return X25519PrivateKeyParameters(getSeed(), 0)
    }

    fun burnIdentityAndGenerateNew() {
        val dbFile = File(baseDir, "msgat_vault.db")
        if (dbFile.exists()) {
            dbFile.delete()
        }

        val secureRandom = SecureRandom()
        val newSeed = ByteArray(32)
        secureRandom.nextBytes(newSeed)

        val newSeedBase64 = Base64.getEncoder().encodeToString(newSeed)
        properties.setProperty("PRIVATE_KEY", newSeedBase64)
        properties.remove("DB_PASSPHRASE")
        properties.remove("my_nickname")
        properties.remove("my_avatar_path")
        saveProperties()
    }

    fun getDatabasePassphrase(): ByteArray {
        val encoded = properties.getProperty("DB_PASSPHRASE")
        if (encoded != null) {
            return Base64.getDecoder().decode(encoded)
        }
        val newKey = ByteArray(32)
        SecureRandom().nextBytes(newKey)
        properties.setProperty("DB_PASSPHRASE", Base64.getEncoder().encodeToString(newKey))
        saveProperties()
        return newKey
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.take(32).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun getDefaultDataDirectory(): File {
            val osName = System.getProperty("os.name").lowercase()
            return when {
                osName.contains("win") -> {
                    val appData = System.getenv("LOCALAPPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Local")
                    File(appData, "MsgAT")
                }
                osName.contains("mac") -> {
                    File(System.getProperty("user.home"), "Library/Application Support/MsgAT")
                }
                else -> {
                    File(System.getProperty("user.home"), ".msgat")
                }
            }
        }
    }
}
