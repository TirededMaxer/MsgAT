// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore
import org.msgat.data.db.AppDatabase
import java.io.File

internal enum class AttachmentCacheCategory(val key: String) {
    PHOTOS("photos"), VIDEOS("videos"), AUDIO("audio"), FILES("files"), LINK_PREVIEWS("link_previews")
}

internal data class AttachmentCacheUsage(
    val bytesByCategory: Map<AttachmentCacheCategory, Long>,
    val pendingBytes: Long,
    val automaticLimitBytes: Long,
    val automaticBudgetUsedBytes: Long
) {
    val bytes: Long get() = bytesByCategory.values.sum()
    val automaticBudgetRemainingBytes: Long get() = (automaticLimitBytes - automaticBudgetUsedBytes).coerceAtLeast(0)
}

/** The vault's exclusive process lease makes prior-owner staging recoverable. */
internal class EncryptedObjectLifecycle(private val database: AppDatabase,private val directory: File) {
    private val owner get()=database.storageOwner
    private val maintenance=Mutex()
    private var recovered=false
    private var sweptLegacy=false
    private var nextPageReclaim = 0L
    private val now get()=System.currentTimeMillis()
    fun stage(id: String) {
        require(id.matches(Regex("[a-f0-9]{64}")))
        database.runInTransaction {
            val db=database.openHelper.writableDatabase
            check(!exists(db,"SELECT 1 FROM media_objects WHERE object_id=?",arrayOf(id))){"Encrypted object identity collision"}
            db.execSQL("INSERT INTO media_staging(object_id,owner,created_at) VALUES(?,?,?)",arrayOf(id,owner,now))
        }
    }
    fun discard(id: String) {
        require(id.matches(Regex("[a-f0-9]{64}")))
        try {database.runInTransaction {
            val db=database.openHelper.writableDatabase
            if(!exists(db,"SELECT 1 FROM media_objects WHERE object_id=? UNION ALL SELECT 1 FROM media_object_references WHERE object_id=?",arrayOf(id,id))) {
                SecurityCore.call(JSONObject().put("operation","remove_object_files").put("directory",directory.absolutePath).put("object_id",id))
                db.execSQL("DELETE FROM media_staging WHERE object_id=? AND owner=?",arrayOf(id,owner))
                db.execSQL("DELETE FROM media_garbage WHERE object_id=?",arrayOf(id))
            }
        }} catch(error: Exception) {database.runInTransaction {
            val db=database.openHelper.writableDatabase
            db.execSQL("INSERT OR IGNORE INTO media_garbage(object_id,attempts,next_retry) VALUES(?,1,?)",arrayOf(id,now+30000))
            db.execSQL("DELETE FROM media_staging WHERE object_id=? AND owner=?",arrayOf(id,owner))
        }}
    }
    suspend fun collect(): Int=maintenance.withLock {
        if(!recovered) {
            database.withTransaction {
                val db=database.openHelper.writableDatabase
                db.execSQL("INSERT OR IGNORE INTO media_garbage(object_id) SELECT object_id FROM media_staging WHERE owner<>?",arrayOf(owner))
                db.execSQL("DELETE FROM media_staging WHERE owner<>?",arrayOf(owner))
                db.execSQL("DELETE FROM object_transfer_pins WHERE owner<>?",arrayOf(owner))
            }
            recovered=true
        }
        if(!sweptLegacy) {
            val removed=database.withTransaction {
                val db=database.openHelper.writableDatabase
                db.execSQL("DELETE FROM media_objects WHERE object_id IN (SELECT o.object_id FROM media_objects o WHERE NOT EXISTS(SELECT 1 FROM media_object_references r WHERE r.object_id=o.object_id) AND NOT EXISTS(SELECT 1 FROM media_staging s WHERE s.object_id=o.object_id) LIMIT 64)")
                db.query("SELECT changes()").use {it.moveToFirst();it.getInt(0)}
            }
            sweptLegacy=removed<64
        }
        val cache = database.openHelper.readableDatabase.query(SimpleSQLiteQuery("""
            SELECT object_id,attempts FROM media_cache_evictions
            WHERE file_pending=1 AND next_retry<=? ORDER BY next_retry,object_id LIMIT 64
            """, arrayOf(now))).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0) to rows.getInt(1)) }
        }
        for ((id, attempts) in cache) {
            currentCoroutineContext().ensureActive()
            evictOne(id, attempts)
        }
        val jobs=database.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT object_id,attempts FROM media_garbage WHERE next_retry<=? ORDER BY next_retry,object_id LIMIT 64",arrayOf(now))).use {c ->
            buildList {while(c.moveToNext())add(c.getString(0) to c.getInt(1))}
        }
        for((id,attempts) in jobs){currentCoroutineContext().ensureActive();collectOne(id,attempts)}
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (elapsed >= nextPageReclaim) {
            nextPageReclaim = elapsed + 30_000
            try { database.reclaimUnusedPages() }
            catch (error: Exception) { android.util.Log.w("MsgAT_Storage", "Database page maintenance deferred: ${error.javaClass.simpleName}") }
        }
        cache.size + jobs.size
    }
    private suspend fun evictOne(id: String, attempts: Int) {
        try {
            database.withTransaction {
                val db = database.openHelper.writableDatabase
                if (!exists(db, "SELECT 1 FROM media_cache_evictions WHERE object_id=? AND file_pending=1", arrayOf(id)))
                    return@withTransaction
                SecurityCore.call(JSONObject().put("operation", "remove_object_files")
                    .put("directory", directory.absolutePath).put("object_id", id))
                db.execSQL("UPDATE media_cache_evictions SET file_pending=0,attempts=0,next_retry=0 WHERE object_id=?", arrayOf(id))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val delay = minOf(300000L, 30000L * (1L shl attempts.coerceIn(0, 4)))
            database.withTransaction {
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE media_cache_evictions SET attempts=MIN(attempts+1,16),next_retry=? WHERE object_id=?",
                    arrayOf(now + delay, id))
            }
        }
    }
    private fun collectOne(id: String,attempts: Int) {
        try {database.runInTransaction {
            val db=database.openHelper.writableDatabase
            if(!exists(db,"SELECT 1 FROM media_staging WHERE object_id=?",arrayOf(id))) {
                if(!exists(db,"SELECT 1 FROM media_objects WHERE object_id=? UNION ALL SELECT 1 FROM media_object_references WHERE object_id=?",arrayOf(id,id)))
                    SecurityCore.call(JSONObject().put("operation","remove_object_files").put("directory",directory.absolutePath).put("object_id",id))
                db.execSQL("DELETE FROM media_garbage WHERE object_id=?",arrayOf(id))
            }
        }} catch(error: Exception) {
            val delay=minOf(300000L,30000L*(1L shl attempts.coerceIn(0,4)))
            database.openHelper.writableDatabase.execSQL("UPDATE media_garbage SET attempts=MIN(attempts+1,16),next_retry=? WHERE object_id=?",arrayOf(now+delay,id))
        }
    }
    private fun exists(db: SupportSQLiteDatabase,sql: String,args: Array<Any?>)=db.query(SimpleSQLiteQuery(sql,args)).use {it.moveToFirst()}

    companion object {
        private const val cacheSources = """
            SELECT message_id,object_id,0 AS is_preview FROM message_attachments
            UNION ALL SELECT owner_id AS message_id,image_object AS object_id,1 AS is_preview
              FROM message_previews WHERE owner_kind='message' AND image_object IS NOT NULL
        """
        private const val receivedCache = """
            o.local_state=1
            AND EXISTS(SELECT 1 FROM ($cacheSources) a JOIN messages m ON m.messageId=a.message_id
                WHERE a.object_id=o.object_id AND m.isMine=0 AND m.isRevoked=0)
            AND NOT EXISTS(SELECT 1 FROM ($cacheSources) a JOIN messages m ON m.messageId=a.message_id
                WHERE a.object_id=o.object_id AND m.isMine=1)
        """
        private const val cacheCandidate = """
            $receivedCache
            AND NOT EXISTS(SELECT 1 FROM media_cache_evictions e WHERE e.object_id=o.object_id)
            AND NOT EXISTS(SELECT 1 FROM media_staging s WHERE s.object_id=o.object_id)
            AND (SELECT COUNT(*) FROM media_object_references r WHERE r.object_id=o.object_id)=
                (SELECT COUNT(*) FROM ($cacheSources) a WHERE a.object_id=o.object_id)
        """
        private const val cachedObjectBytes = """
            json_extract(o.manifest_json,'$.cipher_size') +
            (SELECT COALESCE(SUM(json_extract(preview.manifest_json,'$.cipher_size')),0)
             FROM media_thumbnail_cache t JOIN media_objects preview ON preview.object_id=t.thumbnail_object
             WHERE t.object_id=o.object_id AND preview.local_state=1)
        """
        private const val categorizedCache = """
            SELECT o.object_id,$cachedObjectBytes AS bytes,
                CASE
                    WHEN NOT EXISTS(SELECT 1 FROM message_attachments a WHERE a.object_id=o.object_id) THEN 'link_previews'
                    WHEN json_extract(o.manifest_json,'$.mime') LIKE 'image/%' THEN 'photos'
                    WHEN json_extract(o.manifest_json,'$.mime') LIKE 'video/%' THEN 'videos'
                    WHEN json_extract(o.manifest_json,'$.mime') LIKE 'audio/%' THEN 'audio'
                    ELSE 'files'
                END AS category
            FROM media_objects o WHERE $cacheCandidate
        """
        val automaticLimits = listOf(0L, 512_000_000L, 1_000_000_000L, 2_000_000_000L, 5_000_000_000L)
        private const val pendingCacheBytes = """
            (SELECT COALESCE(SUM(json_extract(p.manifest_json,'$.cipher_size')),0)
             FROM media_cache_evictions e JOIN media_objects p ON p.object_id=e.object_id WHERE e.file_pending=1)
        """
        private const val receivedCacheBytes = """
            (SELECT COALESCE(SUM($cachedObjectBytes),0)
             FROM media_objects o WHERE $receivedCache)
        """
        private const val downloadReservations = """
            SELECT pin.object_id,json_extract(reserved.manifest_json,'$.cipher_size') AS bytes
            FROM object_transfer_pins pin JOIN media_objects reserved ON reserved.object_id=pin.object_id
            WHERE pin.direction=1 AND reserved.local_state=0
              AND NOT EXISTS(SELECT 1 FROM media_cache_evictions e
                             WHERE e.object_id=pin.object_id AND e.file_pending=1)
              AND EXISTS(SELECT 1 FROM object_transfers active
                         WHERE active.object_id=pin.object_id AND active.direction=1
                           AND active.owner_kind='message')
        """
        const val automaticDownloadBudget = """
            CASE
                WHEN t.direction=0 OR t.download_requested=1 OR t.owner_kind<>'message' THEN 1
                WHEN (SELECT auto_download_limit_bytes FROM privacy_preferences WHERE id=1)=0 THEN 1
                ELSE json_extract(o.manifest_json,'$.cipher_size') <=
                    (SELECT auto_download_limit_bytes FROM privacy_preferences WHERE id=1)
                    - $receivedCacheBytes
                    - $pendingCacheBytes
                    - (SELECT COALESCE(SUM(bytes),0) FROM ($downloadReservations) reserved
                       WHERE reserved.object_id<>o.object_id)
            END
        """

        fun cacheUsage(db: SupportSQLiteDatabase): AttachmentCacheUsage {
            val bytesByCategory = db.query("SELECT category,SUM(bytes) FROM ($categorizedCache) GROUP BY category").use { rows ->
                buildMap {
                    while (rows.moveToNext()) {
                        val category = AttachmentCacheCategory.entries.first { it.key == rows.getString(0) }
                        put(category, rows.getLong(1))
                    }
                }
            }
            return db.query("""
                SELECT $pendingCacheBytes,auto_download_limit_bytes,
                       CASE WHEN auto_download_limit_bytes>0 THEN $receivedCacheBytes+$pendingCacheBytes+
                           (SELECT COALESCE(SUM(bytes),0) FROM ($downloadReservations)) ELSE 0 END
                FROM privacy_preferences WHERE id=1
            """.trimIndent()).use { row ->
                check(row.moveToFirst())
                AttachmentCacheUsage(bytesByCategory, row.getLong(0), row.getLong(1), row.getLong(2))
            }
        }

        fun queueCacheReplacement(db: SupportSQLiteDatabase, peer: String, messageId: String, now: Long,
                                  previewOnly: Boolean = false): Boolean {
            val id = db.query(SimpleSQLiteQuery("""
                SELECT o.object_id FROM media_objects o
                JOIN ($cacheSources) a ON a.object_id=o.object_id
                JOIN messages m ON m.messageId=a.message_id
                JOIN contact_states c ON c.uuid=m.conversationUuid
                WHERE m.messageId=? AND m.conversationUuid=? AND a.is_preview=? AND m.isMine=0 AND m.isRevoked=0
                  AND c.accepted=1 AND c.blocked=0 AND $cacheCandidate
                  AND NOT EXISTS(SELECT 1 FROM object_transfer_pins p WHERE p.object_id=o.object_id)
                  AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?)
                """, arrayOf(messageId, peer, if (previewOnly) 1 else 0, now))).use { if (it.moveToFirst()) it.getString(0) else null }
                ?: return false
            db.execSQL("INSERT INTO media_cache_evictions(object_id) VALUES(?)", arrayOf(id))
            db.execSQL("UPDATE media_objects SET local_state=0 WHERE object_id=?", arrayOf(id))
            db.execSQL("""
                INSERT OR IGNORE INTO object_transfers(event_id,object_id,direction,owner_peer,owner_kind,created_at)
                SELECT m.messageId,a.object_id,1,m.conversationUuid,'message',m.timestamp
                FROM ($cacheSources) a JOIN messages m ON m.messageId=a.message_id
                WHERE a.object_id=? AND m.isMine=0 AND m.isRevoked=0
                """, arrayOf(id))
            return true
        }

        fun queueCacheClear(db: SupportSQLiteDatabase, categories: Set<AttachmentCacheCategory>): Int {
            db.execSQL("""
                INSERT INTO media_cache_evictions(object_id)
                SELECT object_id FROM ($categorizedCache)
                WHERE category IN (SELECT value FROM json_each(?))
                """, arrayOf(org.json.JSONArray(categories.map { it.key }).toString()))
            val count = db.query("SELECT changes()").use { rows -> check(rows.moveToFirst()); rows.getInt(0) }
            if (count == 0) return 0
            db.execSQL("""UPDATE media_objects SET local_state=0 WHERE local_state=1
                AND object_id IN (SELECT object_id FROM media_cache_evictions WHERE file_pending=1)""")
            db.execSQL("""
                INSERT OR IGNORE INTO object_transfers(event_id,object_id,direction,owner_peer,owner_kind,created_at)
                SELECT m.messageId,a.object_id,1,m.conversationUuid,'message',m.timestamp
                FROM media_cache_evictions e JOIN ($cacheSources) a ON a.object_id=e.object_id
                JOIN messages m ON m.messageId=a.message_id WHERE e.file_pending=1 AND m.isMine=0 AND m.isRevoked=0
                """)
            return count
        }
    }
}
