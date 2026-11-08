package org.msgat.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [Conversation::class, ChatMessage::class, FriendRequest::class], version = 10, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    // Provides access to the data access object for database operations.
    abstract fun msgDao(): MsgDao

    companion object {
        const val DATABASE_NAME = "msgat_v2_vault.db"
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
            System.loadLibrary("sqlcipher")

            val passphrase = getSecureDbKey(context)

            val factory = SupportOpenHelperFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(factory)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA synchronous=FULL")
                        db.execSQL("PRAGMA cipher_memory_security=ON")
                        org.msgat.core.crypto.SignalProtocolStore.createSchema(db)
                    }
                })
                .build()
        }

        // Retrieves or generates the secure 256-bit key used for SQLCipher encryption.
        private fun getSecureDbKey(context: Context): ByteArray {
            return org.msgat.core.crypto.securestorage.SecurePrefs(context).getDatabasePassphrase()
        }

        @Synchronized
        fun closeForIdentityReset() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
