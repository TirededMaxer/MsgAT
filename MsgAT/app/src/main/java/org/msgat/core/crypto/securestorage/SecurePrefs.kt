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

    var serverIp: String?
        get() = sharedPreferences.getString("server_ip", null)
        set(value) = sharedPreferences.edit { putString("server_ip", value) }

    var serverPort: Int
        get() = sharedPreferences.getInt("server_port", 0)
        set(value) = sharedPreferences.edit { putInt("server_port", value) }

    fun isConfigured(): Boolean = myUuid.isNotEmpty() && !serverIp.isNullOrEmpty() && serverPort > 0

    fun getOrCreatePrivateKey(): Ed25519PrivateKeyParameters {
        val savedSeedBase64 = sharedPreferences.getString(privateKey, null)
        return if (savedSeedBase64 != null) {
            // 从存储中恢复
            val seed = Base64.decode(savedSeedBase64, Base64.NO_WRAP)
            Ed25519PrivateKeyParameters(seed, 0)
        } else {
            // 首次启动：生成 32 字节高强度随机种子
            val secureRandom = java.security.SecureRandom()
            val seed = ByteArray(32)
            secureRandom.nextBytes(seed)

            // 立即存入 EncryptedSharedPreferences
            val seedBase64 = Base64.encodeToString(seed, Base64.NO_WRAP)
            sharedPreferences.edit { putString(privateKey, seedBase64) }

            Ed25519PrivateKeyParameters(seed, 0)
        }
    }

    fun burnIdentityAndGenerateNew() {
        // 1. 生成全新的 32 字节高强度随机种子
        val secureRandom = java.security.SecureRandom()
        val newSeed = ByteArray(32)
        secureRandom.nextBytes(newSeed)

        // 2. 覆盖写入 EncryptedSharedPreferences。
        val newSeedBase64 = Base64.encodeToString(newSeed, Base64.NO_WRAP)
        sharedPreferences.edit {
            putString(privateKey, newSeedBase64)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.take(32).joinToString("") { "%02x".format(it) }
    }
}