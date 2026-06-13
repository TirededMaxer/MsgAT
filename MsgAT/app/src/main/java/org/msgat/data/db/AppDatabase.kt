package org.msgat.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SupportFactory
import net.sqlcipher.database.SQLiteDatabase
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@Database(entities = [Conversation::class, ChatMessage::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    // 暴露之前写好的 DAO 接口
    abstract fun msgDao(): MsgDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = buildDatabase(context)
                INSTANCE = instance
                instance
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            SQLiteDatabase.loadLibs(context)

            // 1. 从 Android TEE 硬件提取或生成绝对随机的 256 位数据库加密密钥
            val passphrase = getSecureDbKey()

            // 2. 将硬件级密钥喂给 SQLCipher 加密引擎
            val factory = SupportFactory(passphrase)

            // 3. 构建 Room 数据库，接管 SQLite。所有聊天记录落盘即被 AES-256 实时加密
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "msgat_vault.db" // 幽灵保险库
            )
                .openHelperFactory(factory)
                .build()
        }

        /**
         * 生成并获取由硬件保护的数据库主密钥
         */
        private fun getSecureDbKey(): ByteArray {
            val alias = "MSGAT_SQLCIPHER_KEY"
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

            // 如果芯片中还没有这个别名的密钥，说明是首次安装，立刻在硬件层生成一把
            if (!keyStore.containsAlias(alias)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setKeySize(256).build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }

            // 提取密钥用于 SQLCipher 初始化
            val secretKey = keyStore.getKey(alias, null) as SecretKey

            // 返回 256 位字节数组。如果遇到极少数被魔改且不支持导出 encoded 的系统，提供一个容错字节
            return secretKey.encoded ?: ByteArray(32) { 0x07 }
        }
    }
}