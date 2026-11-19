// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.msgat.core.crypto.SignalProtocolStore
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.core.media.EncryptedObjectStore
import org.msgat.core.media.EncryptedObjectLifecycle
import java.io.Closeable
import java.util.UUID

enum class AttachmentPhase { WAITING, UPLOADING, DOWNLOADING, COMPLETE, RETRY, UNAVAILABLE }
data class AttachmentProgress(val messageID: String, val phase: AttachmentPhase, val bytes: Long, val total: Long, val reference: String? = null)

/** Independent, bounded upload/download workers; Signal state stays in its own actor. */
internal class AttachmentQueue(
    private val store: SignalProtocolStore,
    private val objects: EncryptedObjectStore,
    prefs: SecurePrefs,
    private val connection: () -> Pair<Long, RelayEndpoint?>,
    private val connected: () -> Boolean,
    private val reconsider: () -> Unit
) : Closeable {
    private class TransferPaused: java.io.IOException()
    private data class Job(val id: String, val objectID: String, val manifest: JSONObject, val attempts: Int, val preview: Boolean)
    private val transport = AttachmentTransport(prefs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = arrayOf(Channel<Unit>(1), Channel<Unit>(1))
    private val instance = UUID.randomUUID().toString()
    private val _progress = MutableStateFlow<Map<String, AttachmentProgress>>(emptyMap())
    val progress = _progress.asStateFlow()
    private val _previewProgress = MutableStateFlow<Map<String, AttachmentProgress>>(emptyMap())
    val previewProgress = _previewProgress.asStateFlow()
    val leaseSession get() = "$instance:${connection().first}"
    val minimumLease get() = now + LEASE_MARGIN
    @Volatile private var closed = false

    init {
        for (direction in 0..1) scope.launch {
            while (isActive) {
                try {
                    for (ignored in wake[direction]) process(direction)
                    return@launch
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    android.util.Log.w("MsgAT_Media", "Attachment queue will retry: ${error.javaClass.simpleName}")
                    delay(30000); signal()
                }
            }
        }
        scope.launch { while (isActive) { delay(30000); signal() } }
    }

    fun signal() { if (!closed) for (channel in wake) channel.trySend(Unit) }

    private suspend fun process(direction: Int) {
        if (!connected()) return
        val (epoch, endpoint) = connection()
        if (endpoint == null) return
        val session = "$instance:$epoch"
        val jobs = store.transaction { db ->
            db.query(SimpleSQLiteQuery("""
                SELECT t.event_id,t.object_id,o.manifest_json,t.attempts,
                       EXISTS(SELECT 1 FROM message_previews p
                         WHERE p.owner_kind='message' AND p.owner_id=t.event_id AND p.image_object=t.object_id)
                FROM object_transfers t JOIN media_objects o ON o.object_id=t.object_id
                LEFT JOIN messages m ON m.messageId=t.event_id AND t.owner_kind='message'
                WHERE t.next_retry<=? AND t.direction=?
                  AND $downloadEligible
                  AND ${EncryptedObjectLifecycle.automaticDownloadBudget}
                  AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=t.event_id AND e.expires_at<=?)
                  AND ((t.owner_kind='message' AND m.isRevoked=0) OR t.owner_kind='profile')
                  AND EXISTS(SELECT 1 FROM contact_states c WHERE c.uuid=t.owner_peer AND c.blocked=0 AND (c.accepted=1 OR (t.owner_kind='profile' AND (c.incoming=1 OR c.outgoing=1))))
                  AND ((t.direction=0 AND (t.lease_session!=? OR t.remote_expiry<=?)
                    AND (EXISTS(SELECT 1 FROM protocol_pending_events p WHERE p.event_id=t.event_id)
                      OR EXISTS(SELECT 1 FROM protocol_outbox b WHERE b.event_id=t.event_id AND b.delivered=0)))
                    OR (t.direction=1 AND o.local_state=0))
                ORDER BY t.download_requested DESC,t.created_at,t.event_id,t.object_id LIMIT 8
            """.trimIndent(), arrayOf(now, direction, now, session, minimumLease))).use { cursor ->
                buildList { while (cursor.moveToNext()) add(Job(cursor.getString(0), cursor.getString(1), JSONObject(cursor.getString(2)), cursor.getInt(3), cursor.getInt(4) != 0)) }
            }
        }
        for (job in jobs) {
            if (!connected() || connection().first != epoch) break
            if(direction==0 && store.transaction {exists(it,"SELECT 1 FROM object_transfers WHERE event_id=? AND object_id=? AND direction=0 AND lease_session=? AND remote_expiry>?",arrayOf(job.id,job.objectID,session,minimumLease))})continue
            if (direction == 1 && store.transaction { exists(it, "SELECT 1 FROM media_objects WHERE object_id=? AND local_state=1", arrayOf(job.objectID)) }) {
                store.transaction { it.execSQL("DELETE FROM object_transfers WHERE event_id=? AND object_id=? AND direction=1", arrayOf(job.id,job.objectID)) }
                continue
            }
            val pin = objects.pinTransfer(job.objectID, direction) {
                allowed(it, job, direction, checkBudget = true)
            } ?: continue
            try {
            val total = job.manifest.getLong("size")
            val phase = if (direction == 0) AttachmentPhase.UPLOADING else AttachmentPhase.DOWNLOADING
            var last = -1L; var lastTime = android.os.SystemClock.elapsedRealtime()
            val changed: (Long) -> Unit = { bytes ->
                val percent = if (total == 0L) 100 else bytes * 100 / total
                val elapsed = android.os.SystemClock.elapsedRealtime()
                if (percent != last && (elapsed - lastTime >= 100 || bytes == total)) {
                    last = percent; lastTime = elapsed
                    publish(job, AttachmentProgress(job.id, phase, bytes, total))
                }
            }
            publish(job, AttachmentProgress(job.id, phase, 0, total))
            val checkpoint: suspend ()->Unit = {
                currentCoroutineContext().ensureActive()
                if(closed || !connected() || connection().first!=epoch || !store.transaction {allowed(it,job,direction)})throw TransferPaused()
            }
            try {
                if (direction == 0) {
                    val expiry = transport.upload(job.manifest, objects.path(job.objectID), endpoint, changed,checkpoint)
                    check(expiry in 0..Long.MAX_VALUE / 1000 && expiry * 1000 > minimumLease) { "Attachment lease is too short or the local clock is incorrect" }
                    store.transaction {
                        if(!allowed(it,job,direction))throw TransferPaused()
                        it.execSQL("INSERT INTO object_leases(object_id,lease_session,remote_expiry) SELECT ?,?,? WHERE EXISTS(SELECT 1 FROM media_objects WHERE object_id=?) ON CONFLICT(object_id) DO UPDATE SET lease_session=excluded.lease_session,remote_expiry=excluded.remote_expiry",arrayOf(job.objectID,session,expiry*1000,job.objectID))
                        it.execSQL("UPDATE object_transfers SET lease_session=?,remote_expiry=?,attempts=0,next_retry=0 WHERE object_id=? AND direction=0",arrayOf(session,expiry*1000,job.objectID))
                    }
                } else {
                    objects.requireDownloadSpace(job.manifest)
                    transport.download(job.manifest, objects.path(job.objectID), endpoint, changed,checkpoint)
                    store.transaction { db ->
                        if(!allowed(db,job,direction))throw TransferPaused()
                        db.execSQL("UPDATE media_objects SET local_state=1 WHERE object_id=?", arrayOf(job.objectID))
                        db.execSQL("DELETE FROM media_cache_evictions WHERE object_id=?", arrayOf(job.objectID))
                        db.execSQL("DELETE FROM object_transfers WHERE event_id=? AND object_id=? AND direction=1", arrayOf(job.id,job.objectID))
                        db.execSQL("UPDATE messages SET content=content WHERE messageId=?", arrayOf(job.id))
                    }
                    objects.signalAvailable()
                }
                publish(job, AttachmentProgress(job.id, AttachmentPhase.COMPLETE, total, total))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch(_: TransferPaused){publish(job, AttachmentProgress(job.id,AttachmentPhase.WAITING,0,total))}
            catch (_: AttachmentUnavailable) {
                val changed = store.transaction { db ->
                    db.execSQL("""
                        UPDATE object_transfers SET download_unavailable=1,download_requested=0
                        WHERE event_id=? AND object_id=? AND direction=1 AND download_paused=0
                        """, arrayOf(job.id,job.objectID))
                    val unavailable = exists(db, "SELECT 1 WHERE changes()>0", emptyArray())
                    if (unavailable) db.execSQL("UPDATE messages SET content=content WHERE messageId=?", arrayOf(job.id))
                    unavailable
                }
                publish(job, AttachmentProgress(job.id,if(changed) AttachmentPhase.UNAVAILABLE else AttachmentPhase.WAITING,0,total))
                if (changed) objects.signalAvailable()
            }
            catch (error: Exception) {
                val delay = minOf(300000L, 30000L * (1L shl minOf(job.attempts, 4))) + kotlin.random.Random.nextLong(0, 3000)
                val retrying = store.transaction { db ->
                    db.execSQL("UPDATE object_transfers SET attempts=MIN(attempts+1,16),next_retry=? WHERE event_id=? AND object_id=? AND direction=? AND download_paused=0", arrayOf(now + delay, job.id, job.objectID, direction))
                    exists(db, "SELECT 1 WHERE changes()>0", emptyArray())
                }
                if (retrying) android.util.Log.w("MsgAT_Media", "Attachment transfer will retry: ${error.javaClass.simpleName}")
                publish(job, AttachmentProgress(job.id, if (retrying) AttachmentPhase.RETRY else AttachmentPhase.WAITING, 0, total))
            }
            reconsider()
            } finally {pin.close()}
        }
        if (jobs.size == 8) wake[direction].trySend(Unit)
    }

    private fun allowed(db: SupportSQLiteDatabase,job: Job,direction: Int,checkBudget: Boolean=false)=exists(db,"""
        SELECT 1 FROM object_transfers t JOIN contact_states c ON c.uuid=t.owner_peer LEFT JOIN messages m ON m.messageId=t.event_id AND t.owner_kind='message'
JOIN media_objects o ON o.object_id=t.object_id
WHERE t.event_id=? AND t.object_id=? AND t.direction=? AND c.blocked=0
AND (c.accepted=1 OR (t.owner_kind='profile' AND (c.incoming=1 OR c.outgoing=1)))
AND (t.owner_kind='profile' OR m.isRevoked=0)
AND (t.direction=0 OR o.local_state=0)
AND $downloadEligible
${if(checkBudget) "AND ${EncryptedObjectLifecycle.automaticDownloadBudget}" else ""}
AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=t.event_id AND e.expires_at<=?)
AND (t.direction=1 OR EXISTS(SELECT 1 FROM protocol_pending_events p WHERE p.event_id=t.event_id) OR EXISTS(SELECT 1 FROM protocol_outbox b WHERE b.event_id=t.event_id AND b.delivered=0))
    """.trimIndent(),arrayOf(job.id,job.objectID,direction,now))

    private fun publish(job: Job, value: AttachmentProgress) {
        val stream = if (job.preview) _previewProgress else _progress
        val progress = if (job.preview) value.copy(reference = EncryptedObjectStore.reference(job.objectID)) else value
        stream.update { previous ->
            val result = previous.toMutableMap()
            if (result.size >= 256) result.entries.filter {
                it.value.phase == AttachmentPhase.COMPLETE || it.value.phase == AttachmentPhase.RETRY ||
                    it.value.phase == AttachmentPhase.WAITING || it.value.phase == AttachmentPhase.UNAVAILABLE
            }.take(128).forEach { result.remove(it.key) }
            result[value.messageID] = progress
            result
        }
    }

    suspend fun stop() {
        close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.join()
    }

    override fun close() {
        closed = true; for (channel in wake) channel.close()
        scope.cancel(); transport.close()
    }
    companion object {
        private const val LEASE_MARGIN = 7L * 86400000 + 3600000
        private val now get() = System.currentTimeMillis()
        private const val downloadEligible = """
            (t.direction=0 OR (t.download_paused=0 AND t.download_unavailable=0))
            AND (t.direction=0 OR NOT EXISTS(
                SELECT 1 FROM media_cache_evictions e WHERE e.object_id=t.object_id AND e.file_pending=1))
            AND (t.direction=0 OR t.download_requested=1 OR (NOT EXISTS(
                SELECT 1 FROM media_cache_evictions e WHERE e.object_id=t.object_id) AND (
                (SELECT auto_download_attachments FROM privacy_preferences WHERE id=1)=1 OR NOT EXISTS(
                SELECT 1 FROM message_attachments a WHERE a.message_id=t.event_id AND a.object_id=t.object_id))))
        """
        fun autoDownloadEnabled(db: SupportSQLiteDatabase): Boolean =
            exists(db, "SELECT 1 FROM privacy_preferences WHERE id=1 AND auto_download_attachments=1", emptyArray())

        fun setDownloadRequested(db: SupportSQLiteDatabase, peer: String, id: String, requested: Boolean,
                                 previewOnly: Boolean = false): Boolean {
            db.execSQL("""
                UPDATE object_transfers SET download_requested=?,download_paused=?,
                    download_unavailable=CASE WHEN ?=1 THEN 0 ELSE download_unavailable END,next_retry=0,attempts=0
                WHERE event_id=? AND owner_peer=? AND owner_kind='message' AND direction=1
                  AND EXISTS(SELECT 1 FROM media_objects o WHERE o.object_id=object_transfers.object_id AND o.local_state=0)
                  AND EXISTS(SELECT 1 FROM messages m
                    JOIN contact_states c ON c.uuid=m.conversationUuid
                    WHERE m.messageId=object_transfers.event_id AND m.conversationUuid=object_transfers.owner_peer
                      AND m.isMine=0 AND m.isRevoked=0 AND c.accepted=1 AND c.blocked=0
                      AND ((?6=0 AND EXISTS(SELECT 1 FROM message_attachments a WHERE a.message_id=m.messageId AND a.object_id=object_transfers.object_id))
                        OR (?6=1 AND EXISTS(SELECT 1 FROM message_previews p WHERE p.owner_kind='message'
                            AND p.owner_id=m.messageId AND p.image_object=object_transfers.object_id))))
                  AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=object_transfers.event_id AND e.expires_at<=?)
                """, arrayOf(if (requested) 1 else 0, if (requested) 0 else 1, if (requested) 1 else 0,
                    id, peer, if (previewOnly) 1 else 0, now))
            return db.query("SELECT changes()").use { it.moveToFirst() && it.getInt(0) > 0 }
        }
        fun record(db: SupportSQLiteDatabase, event: String, objectID: String, outgoing: Boolean,peer: String,created: Long,ownerKind: String="message") {
            db.execSQL("INSERT INTO object_transfers(event_id,object_id,direction,owner_peer,owner_kind,created_at,lease_session,remote_expiry) VALUES(?,?,?,?,?,?,COALESCE((SELECT lease_session FROM object_leases WHERE object_id=?),''),COALESCE((SELECT remote_expiry FROM object_leases WHERE object_id=?),0)) ON CONFLICT(event_id,object_id,direction) DO NOTHING",arrayOf(event,objectID,if(outgoing)0 else 1,peer,ownerKind,created,objectID,objectID))
        }
        private fun exists(db: SupportSQLiteDatabase, sql: String, args: Array<Any?>) = db.query(SimpleSQLiteQuery(sql, args)).use { it.moveToFirst() }
    }
}
