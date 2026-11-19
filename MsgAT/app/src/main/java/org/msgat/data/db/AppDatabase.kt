package org.msgat.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.io.RandomAccessFile
import java.nio.channels.FileLock

@Database(entities = [Conversation::class, ChatMessage::class, FriendRequest::class,ConversationPreference::class,ConversationFolder::class,ConversationFolderMember::class,ConversationFolderPin::class,DraftIndexRow::class,DraftActivityRow::class,MessagePinClock::class], version = 13, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    internal val storageOwner=java.util.UUID.randomUUID().toString()

    // SQLCipher's configuration retains this array for later WAL/read connections.
    // It is a database key, never the root seed; clear it only after the pool closes.
    private var poolPassphrase: ByteArray? = null
    override fun close() {
        super.close() // Keep the key if the live connection pool could not close.
        poolPassphrase?.fill(0)
        poolPassphrase = null
    }

    // Provides access to the data access object for database operations.
    abstract fun msgDao(): MsgDao

    internal fun reclaimUnusedPages() {
        val db = openHelper.writableDatabase as SQLiteDatabase
        if (db.pragmaValue("PRAGMA auto_vacuum") != 2L || db.pragmaValue("PRAGMA freelist_count") < 128L) return
        db.rawExecSQL("PRAGMA incremental_vacuum(64)")
        db.rawExecSQL("PRAGMA wal_checkpoint(PASSIVE)")
    }

    companion object {
        const val DATABASE_NAME = "msgat_v2_vault.db"
        @Volatile
        private var INSTANCE: AppDatabase? = null
        // Startup identity recovery and Room share this lease until the process exits.
        private var profileLease: Pair<RandomAccessFile, FileLock>? = null

        @Synchronized
        internal fun requireProfileLease(context: Context) {
            if (profileLease != null) return
            val location = context.getDatabasePath(DATABASE_NAME)
            check(location.parentFile?.isDirectory == true || location.parentFile?.mkdirs() == true) {
                "Cannot create the encrypted vault directory"
            }
            val file = RandomAccessFile(location.path + ".lock", "rw")
            try {
                val lock = file.channel.tryLock() ?: error("The encrypted vault is already open")
                profileLease = file to lock
            } catch (error: Exception) {
                file.close()
                throw IllegalStateException("The encrypted vault is already open or could not be locked", error)
            }
        }

        // Returns a singleton instance of the Room database.
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE=it }
            }
        }

        // Constructs and configures the encrypted Room database instance.
        private fun buildDatabase(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")

            val location=context.getDatabasePath(DATABASE_NAME)
            requireProfileLease(context)
            var passphrase: ByteArray?=null
            var instance: AppDatabase?=null
            try {
            check(location.exists() || listOf("-wal", "-shm", "-journal").none { java.io.File(location.path + it).exists() }) {
                "The encrypted vault file is missing but recovery files remain"
            }
            val key=getSecureDbKey(context).also {passphrase=it}
            val opened=Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(VaultOpenHelperFactory(key))
                .addMigrations(object:androidx.room.migration.Migration(10,11){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){for(sql in ConversationIndexStore.schema)db.execSQL(sql)}})
                .addMigrations(object:androidx.room.migration.Migration(11,12){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){for(sql in MessagePinsStore.schema)db.execSQL(sql)}})
                .addMigrations(object : androidx.room.migration.Migration(12, 13) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        for (sql in ConversationIndexStore.schema) db.execSQL(sql)
                    }
                })
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                        db.execSQL("PRAGMA synchronous=FULL")
                        org.msgat.core.crypto.SignalProtocolStore.createSchema(db)
                    }
                })
                .build()
            instance=opened
            opened.poolPassphrase=key
            opened.openHelper.writableDatabase
            return opened
            } catch (error: Exception) {
                instance?.close()
                passphrase?.fill(0)
                throw error
            }
        }

        // Retrieves or generates the secure 256-bit key used for SQLCipher encryption.
        private fun getSecureDbKey(context: Context): ByteArray {
            return org.msgat.core.crypto.securestorage.SecurePrefs(context).getDatabasePassphrase()
        }

        @Synchronized
        fun closeCurrent() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}

private fun SQLiteDatabase.pragmaValue(sql: String): Long = query(sql).use {
    check(it.moveToFirst()) { "Encrypted database settings are unavailable" }
    it.getLong(0)
}

private class VaultOpenHelperFactory(private val passphrase: ByteArray) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val callback = configuration.callback
        val preserve = DatabaseErrorHandler { _, _ ->
            throw SQLiteDatabaseCorruptException("The encrypted vault is damaged. The database files were preserved.")
        }
        val hook = object : SQLiteDatabaseHook {
            override fun preKey(connection: SQLiteConnection) = Unit
            override fun postKey(connection: SQLiteConnection) {
                connection.execute("PRAGMA cipher_memory_security=ON", null, null)
                connection.execute("PRAGMA temp_store=MEMORY", null, null)
            }
        }
        return object : SQLiteOpenHelper(configuration.context, configuration.name, passphrase,
            null, callback.version, 0, preserve, hook, false) {
            override fun onConfigure(db: SQLiteDatabase) {
                callback.onConfigure(db)
                if (db.version == 0 && db.pragmaValue("SELECT count(*) FROM sqlite_master") == 0L) {
                    db.rawExecSQL("PRAGMA auto_vacuum=INCREMENTAL")
                }
            }
            override fun onCreate(db: SQLiteDatabase) = callback.onCreate(db)
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
                callback.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SQLiteDatabase) = callback.onOpen(db)
        }
    }
}
