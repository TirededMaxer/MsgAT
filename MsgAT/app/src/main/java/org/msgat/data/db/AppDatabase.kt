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

@Database(entities = [Conversation::class, ChatMessage::class, FriendRequest::class], version = 10, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    // Provides access to the data access object for database operations.
    abstract fun msgDao(): MsgDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Returns a singleton instance of the Room database.
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = buildDatabase(context)
                INSTANCE = instance
                instance
            }
        }

        // Constructs and configures the encrypted Room database instance.
        private fun buildDatabase(context: Context): AppDatabase {
            SQLiteDatabase.loadLibs(context)

            val passphrase = getSecureDbKey(context)

            val factory = SupportFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "msgat_vault.db" 
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }

        // Retrieves or generates the secure 256-bit key used for SQLCipher encryption.
        private fun getSecureDbKey(context: Context): ByteArray {
            return org.msgat.core.crypto.securestorage.SecurePrefs(context).getDatabasePassphrase()
        }
    }
}