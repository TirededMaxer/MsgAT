package org.msgat.core.crypto.securestorage

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.MessageDigest
import org.msgat.core.crypto.RootKeyHierarchy
import kotlin.collections.take

internal data class AttachmentExportLocation(val uri: String, val name: String)

class SecurePrefs(private val context: Context) {
    companion object {
        private val identityLock = Any()
        private const val preferencesName = "MSGAT_SECURE_PREFS"
    }

    private val privateKey = "PRIVATE_KEY"

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .setRequestStrongBoxBacked(context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE))
        .build()
        .also { requireHardwareProtection(MasterKey.DEFAULT_MASTER_KEY_ALIAS) }

    @Suppress("DEPRECATION")
    private fun requireHardwareProtection(alias: String) {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(alias, null) as? javax.crypto.SecretKey
            ?: throw HardwareProtectionUnavailableException()
        val factory = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
        val info = factory.getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
        val hardware = if (android.os.Build.VERSION.SDK_INT >= 31) {
            info.securityLevel == android.security.keystore.KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                info.securityLevel == android.security.keystore.KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else info.isInsideSecureHardware
        if (!hardware) throw HardwareProtectionUnavailableException()
    }

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        preferencesName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        if (!sharedPreferences.getBoolean("relay_tls_security_v2", false)) {
            sharedPreferences.edit {
                putBoolean("allow_development_certificate", false)
                putBoolean("relay_tls_security_v2", true)
            }
        }
    }

    val myUuid: String
        get() {
            val priv = getOrCreatePrivateKey()
            val pubBytes = priv.generatePublicKey().encoded
            return sha256(pubBytes)
        }

    val myMailboxId: String get() = org.msgat.core.crypto.CryptoManager.calculateMailboxId(myUuid)

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
        get() = sharedPreferences.getInt("server_port", 34478)
        set(value) = sharedPreferences.edit { putInt("server_port", value) }

    var serverCertificateSha256: String?
        get() = sharedPreferences.getString("server_certificate_sha256", null)
        set(value) = sharedPreferences.edit { putString("server_certificate_sha256", value) }

    internal var attachmentExportFolder: AttachmentExportLocation?
        get() = sharedPreferences.getString("attachment_export_folder", null)?.let { uri ->
            AttachmentExportLocation(uri, sharedPreferences.getString("attachment_export_folder_name", "").orEmpty())
        }
        set(value) = sharedPreferences.edit {
            putString("attachment_export_folder", value?.uri)
            putString("attachment_export_folder_name", value?.name)
        }

    var allowDevelopmentCertificate: Boolean
        get() = sharedPreferences.getBoolean("allow_development_certificate", false)
        set(value) = sharedPreferences.edit { putBoolean("allow_development_certificate", value) }

    internal fun readPushSubscription(): String? = sharedPreferences.getString("android_push_v2", null)

    internal var persistentReceptionEnabled: Boolean
        get() = sharedPreferences.getBoolean("persistent_reception_enabled", true)
        set(value) = sharedPreferences.edit { putBoolean("persistent_reception_enabled", value) }

    internal fun writePushSubscription(value: String?) {
        check(sharedPreferences.edit().putString("android_push_v2", value).commit()) { "Cannot save push registration" }
    }

    @Volatile private var localProfile: org.msgat.data.db.UserProfileSnapshot?=null
    internal val legacyNickname: String? get()=sharedPreferences.getString("my_nickname",null)
    internal val legacyAvatarPath: String? get()=sharedPreferences.getString("my_avatar_path",null)
    internal fun installProfile(profile: org.msgat.data.db.UserProfileSnapshot?) {
        localProfile=profile
        if(profile!=null)sharedPreferences.edit {remove("my_nickname");remove("my_avatar_path")}
    }
    val myNickname: String? get()=localProfile?.nickname

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

    val myAvatarPath: String? get()=localProfile?.avatarReference

    // Checks if the required server settings and local identity have been established.
    fun isConfigured(): Boolean = myUuid.isNotEmpty() && !serverIp.isNullOrEmpty() && serverPort in 1..65535

    private fun getSeed(): ByteArray = synchronized(identityLock) {
        val marker = java.io.File(context.noBackupFilesDir, "identity.initialized")
        val savedSeedBase64 = sharedPreferences.getString(privateKey, null)
        val seed = if (savedSeedBase64 != null) {
            Base64.decode(savedSeedBase64, Base64.NO_WRAP).also { require(it.size == 32) { "Invalid identity seed" } }
        } else {
            check(!marker.exists() && !context.getDatabasePath("msgat_vault.db").exists() &&
                !context.getDatabasePath(org.msgat.data.db.AppDatabase.DATABASE_NAME).exists()) {
                "Identity seed is missing; refusing to replace the existing identity"
            }
            val secureRandom = java.security.SecureRandom()
            val seed = ByteArray(32)
            secureRandom.nextBytes(seed)
            val seedBase64 = Base64.encodeToString(seed, Base64.NO_WRAP)
            check(sharedPreferences.edit().putString(privateKey, seedBase64).commit()) { "Cannot persist identity seed" }
            seed
        }
        if (!marker.exists()) {
            val atomic = android.util.AtomicFile(marker)
            val output = atomic.startWrite()
            try {
                output.write("MsgAT identity initialized v2".toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (error: Exception) { atomic.failWrite(output); seed.fill(0); throw error }
        }
        seed
    }

    // Ed25519 uses the local wrapped root seed; Keystore protects its storage key.
    fun getOrCreatePrivateKey(): Ed25519PrivateKeyParameters {
        val seed = getSeed()
        return try { Ed25519PrivateKeyParameters(seed, 0) } finally { seed.fill(0) }
    }

    fun getX25519PrivateKey(): org.bouncycastle.crypto.params.X25519PrivateKeyParameters {
        val seed = getSeed()
        return try { org.bouncycastle.crypto.params.X25519PrivateKeyParameters(seed, 0) } finally { seed.fill(0) }
    }

    internal fun <T> withRootSeed(operation: (ByteArray) -> T): T {
        val seed = getSeed()
        return try { operation(seed) } finally { seed.fill(0) }
    }



    @Synchronized
    fun getDatabasePassphrase(): ByteArray {
        val seed = getSeed()
        try {
            val encoded = sharedPreferences.getString("DB_KEY_ENVELOPE_V2", null)
            if (encoded != null) {
                return RootKeyHierarchy.unwrap(seed, "database", Base64.decode(encoded, Base64.NO_WRAP))
            }
            check(!context.getDatabasePath(org.msgat.data.db.AppDatabase.DATABASE_NAME).exists()) {
                "Database key is missing; refusing to replace the existing vault key"
            }
            val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            try {
                val envelope = RootKeyHierarchy.wrap(seed, "database", key)
                check(sharedPreferences.edit().putString("DB_KEY_ENVELOPE_V2", Base64.encodeToString(envelope, Base64.NO_WRAP)).commit()) {
                    "Cannot persist wrapped database key"
                }
                return key
            } catch (error: Exception) {
                key.fill(0)
                throw error
            }
        } finally {
            seed.fill(0)
        }
    }

    // Computes the SHA-256 hash of the provided byte array.
    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.take(32).joinToString("") { "%02x".format(it) }
    }
}

class HardwareProtectionUnavailableException : IllegalStateException(
    "MsgAT requires hardware-backed TEE or StrongBox key storage. This device cannot create an identity."
)
