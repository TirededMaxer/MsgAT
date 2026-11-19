// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import org.msgat.core.media.ObjectPreparation
import org.msgat.core.media.PreparationProgress
import org.msgat.data.db.AppDatabase
import org.msgat.data.db.MessageActionsStore
import org.msgat.data.db.MessageActionSnapshot
import java.security.MessageDigest
import java.util.UUID

internal data class ForwardTaskInfo(val id: String,val createdAt: Long,val state: String,val messageCount: Int,val recipients: List<String?>,val errorCode: String?,
    val pendingMediaMessages: Int, val sourcePeer: String?, val waitingMessageId: String?, val options: SendOptions)
internal data class ForwardQueueRequest(val sourcePeer: String?,val ids: List<String>,val recipients: List<String>,val hideSender: Boolean,val hideCaption: Boolean,val fingerprints: List<String>,val options:SendOptions=SendOptions(), val omitPreview: Boolean=false)

internal class ForwardTaskQueue(
    private val database: AppDatabase,
    private val actions: MessageActionsStore,
    private val commit: suspend (String,ForwardQueueRequest,ObjectPreparation)->Boolean,
    private val wakeDownloads: () -> Unit
) {
    private data class Pending(val id: String,val payload: String,val createdAt: Long)
    private data class WaitingMedia(val count: Int, val firstMessageId: String)
    private val gate=Any()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private var closed=false
    private var activeId: String?=null
    private var activeTask: Deferred<Boolean>?=null
    private var activePreparation: ObjectPreparation?=null
    private val pendingMediaMessages = mutableMapOf<String, WaitingMedia>()
    private val _revision=MutableStateFlow(0L)
    val revision=_revision.asStateFlow()
    private fun changed(){_revision.update {it+1}}
    private val now get()=System.currentTimeMillis()
    init {
        scope.launch {for(signal in wake) {
            try {cycle()}
            catch(cancelled: CancellationException){throw cancelled}
            catch(error: Exception){android.util.Log.w("MsgAT_Forward","Forwarding tasks will retry: ${error.javaClass.simpleName}")}
        }}
        scope.launch {while(isActive){delay(30000);signal()}}
        signal()
    }
    fun signal(){synchronized(gate){if(!closed)wake.trySend(Unit)}}
    private suspend fun <T> owned(block: suspend ()->T): T {
        val task=synchronized(gate){check(!closed){"Forwarding is closed"};scope.async {block()}}
        return task.await()
    }
    fun progress(id: String): PreparationProgress? =
        synchronized(gate) {activePreparation.takeIf {activeId==id}}?.snapshot()
    suspend fun close(){
        synchronized(gate){closed=true;activePreparation?.cancel();wake.close()}
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
    suspend fun enqueue(peer: String?,ids: List<String>,recipients: List<String>,hideSender: Boolean,hideCaption: Boolean,options:SendOptions=SendOptions(), omitPreview: Boolean=false): Boolean {
        val selected=ids.toList();val targets=recipients.toList()
        return owned {
            require(options.sendAt?.let{it>now && it<=253402214399999L}?:true){"Choose a future scheduled time"}
            require(targets.size in 1..20 && targets.distinct().size==targets.size){"Select between 1 and 20 different recipients"}
            val source=actions.capture(peer,selected,allowWaiting=true)
            val request=ForwardQueueRequest(peer,source.map {it.id},targets,hideSender,hideCaption,source.map(::fingerprint),options,omitPreview)
            val payload=encode(request);check(payload.toByteArray(Charsets.UTF_8).size<=65536){"The forwarding request is too large"}
            currentCoroutineContext().ensureActive()
            database.withTransaction {
                val db=database.openHelper.writableDatabase
                check(db.query("SELECT COUNT(*) FROM forward_jobs").use {it.moveToFirst();it.getInt(0)}<20){"The forwarding queue is full"}
                requireRecipients(db,targets);source.forEach {actions.requireCurrent(db,it)}
                db.execSQL("INSERT INTO forward_jobs(id,request_json,created_at,state) VALUES(?,?,?,'waiting')",arrayOf(UUID.randomUUID().toString(),payload,now))
            }
            changed();signal();true
        }
    }
    suspend fun list(): List<ForwardTaskInfo> = owned {
        database.openHelper.readableDatabase.query("""
            SELECT id,request_json,created_at,state,error_code,
                   (SELECT json_group_array(alias) FROM (
                        SELECT NULLIF(c.alias,'') AS alias
                        FROM json_each(CASE WHEN json_valid(job.request_json) THEN job.request_json END,'$.recipients') target
                        LEFT JOIN conversations c ON c.uuid=target.value
                        ORDER BY target.key
                   ))
            FROM forward_jobs job ORDER BY created_at,id LIMIT 20
        """.trimIndent()).use {c ->
            buildList {while(c.moveToNext()) {
                val request=runCatching {decode(c.getString(1))}.getOrNull()
                val aliases=JSONArray(c.getString(5))
                val recipients=request?.recipients?.indices?.map {index ->
                    if(aliases.isNull(index))null else aliases.getString(index)
                }.orEmpty()
                val id = c.getString(0)
                val state = c.getString(3)
                val pending = if (state == "waiting") synchronized(gate) { pendingMediaMessages[id] } else null
                add(ForwardTaskInfo(id, c.getLong(2), state, request?.ids?.size ?: 0, recipients,
                    if (c.isNull(4)) null else c.getString(4), pending?.count ?: 0, request?.sourcePeer,
                    pending?.firstMessageId, request?.options ?: SendOptions()))
            }}
        }
    }
    suspend fun cancel(id: String): Boolean = owned {
        val removed=database.withTransaction {
            val db=database.openHelper.writableDatabase
            val present=exists(db,"SELECT 1 FROM forward_jobs WHERE id=?",arrayOf(id))
            db.execSQL("DELETE FROM forward_jobs WHERE id=?",arrayOf(id));present
        }
        if(removed)synchronized(gate){
            pendingMediaMessages.remove(id)
            if(activeId==id){activePreparation?.cancel();activeTask?.cancel()}
        }
        changed();signal();removed
    }
    suspend fun retry(id: String): Boolean = owned {
        val payload = database.openHelper.readableDatabase.query(SimpleSQLiteQuery("""
            SELECT request_json FROM forward_jobs WHERE id=? AND state='failed' AND error_code='source_download_unavailable'
            """, arrayOf(id))).use { if (it.moveToFirst()) it.getString(0) else null }
        val request = payload?.let(::decode)
        val source = request?.let {
            actions.capture(it.sourcePeer, it.ids, allowWaiting = true, includePreview = !it.omitPreview)
                .also { rows -> requireSnapshot(rows, it) }
        }
        val wasChanged=database.withTransaction {
            val db=database.openHelper.writableDatabase
            val present = if (request == null || source == null) {
                exists(db, """SELECT 1 FROM forward_jobs WHERE id=? AND state='failed'
                    AND error_code IS NOT 'source_changed' AND error_code IS NOT 'source_download_unavailable'""", arrayOf(id))
            } else {
                if (!exists(db, """SELECT 1 FROM forward_jobs WHERE id=? AND request_json=?
                        AND state='failed' AND error_code='source_download_unavailable'""", arrayOf(id, payload)))
                    return@withTransaction false
                val unavailable = unavailableDownloads(db, source, request.omitPreview)
                if (unavailable.isNotEmpty() && !requestDownloads(db, request, source, unavailable))
                    return@withTransaction false
                true
            }
            if(present)db.execSQL("UPDATE forward_jobs SET state='waiting',error_code=NULL,created_at=? WHERE id=?",arrayOf(now,id))
            present
        }
        if (wasChanged && request != null) wakeDownloads()
        if(wasChanged)changed();signal();wasChanged
    }

    suspend fun requestMissingDownloads(id: String): Boolean = owned {
        val payload = database.openHelper.readableDatabase.query(SimpleSQLiteQuery(
            "SELECT request_json FROM forward_jobs WHERE id=? AND state='waiting'", arrayOf(id)
        )).use { if (it.moveToFirst()) it.getString(0) else null } ?: return@owned false
        val request = decode(payload)
        val peer = request.sourcePeer ?: return@owned false
        val source = actions.capture(peer, request.ids, allowWaiting = true, includePreview = !request.omitPreview)
        requireSnapshot(source, request)
        val updated = database.withTransaction {
            val db = database.openHelper.writableDatabase
            if (!exists(db, "SELECT 1 FROM forward_jobs WHERE id=? AND request_json=? AND state='waiting'",
                    arrayOf(id, payload))) return@withTransaction false
            requestDownloads(db, request, source)
        }
        if (updated) wakeDownloads()
        signal()
        updated
    }

    private data class SourceDownload(val row: MessageActionSnapshot, val manifest: String, val previewOnly: Boolean)

    private fun missingDownloads(source: List<MessageActionSnapshot>, omitPreview: Boolean): List<SourceDownload> = buildList {
        for (row in source) {
            if (row.saved || row.available) continue
            row.manifest?.let { add(SourceDownload(row, it, previewOnly = false)) }
            if (!omitPreview && !row.previewAvailable)
                row.previewManifest?.let { add(SourceDownload(row, it, previewOnly = true)) }
        }
    }

    private fun unavailableDownloads(db: SupportSQLiteDatabase, source: List<MessageActionSnapshot>, omitPreview: Boolean) =
        missingDownloads(source, omitPreview).filter { download ->
            val owner = if (download.previewOnly)
                "SELECT 1 FROM message_previews p WHERE p.owner_kind='message' AND p.owner_id=t.event_id AND p.image_object=t.object_id"
            else "SELECT 1 FROM message_attachments a WHERE a.message_id=t.event_id AND a.object_id=t.object_id"
            exists(db, """
                SELECT 1 FROM object_transfers t JOIN media_objects o ON o.object_id=t.object_id
                WHERE t.owner_kind='message' AND t.direction=1 AND t.download_unavailable=1 AND o.local_state=0
                  AND t.event_id=? AND t.owner_peer=? AND t.object_id=? AND EXISTS($owner)
                """, arrayOf(download.row.id, download.row.peer, JSONObject(download.manifest).getString("object_id")))
        }

    private fun requestDownloads(db: SupportSQLiteDatabase, request: ForwardQueueRequest, source: List<MessageActionSnapshot>,
                                 downloads: List<SourceDownload> = missingDownloads(source, request.omitPreview)): Boolean {
        requireRecipients(db, request.recipients)
        source.filterNot { it.available }.forEach { actions.requireCurrent(db, it) }
        var changed = false
        for (download in downloads) {
            if (AttachmentQueue.setDownloadRequested(db, download.row.peer, download.row.id, requested = true,
                    previewOnly = download.previewOnly)) changed = true
        }
        return changed
    }

    private suspend fun fail(id: String,code: String) {database.withTransaction {
        database.openHelper.writableDatabase.execSQL("UPDATE forward_jobs SET state='failed',error_code=? WHERE id=?",arrayOf(code,id))
    };synchronized(gate) { pendingMediaMessages.remove(id) };changed()}
    private suspend fun cycle() {
        var recovered=false
        val jobs=database.withTransaction {
            val db=database.openHelper.writableDatabase
            recovered=db.query("SELECT 1 FROM forward_jobs WHERE state='preparing' LIMIT 1").use {it.moveToFirst()}
            if(recovered)db.execSQL("UPDATE forward_jobs SET state='waiting' WHERE state='preparing'")
            db.query("SELECT id,request_json,created_at FROM forward_jobs WHERE state='waiting' ORDER BY created_at,id LIMIT 20").use {c ->
                buildList {while(c.moveToNext())add(Pending(c.getString(0),c.getString(1),c.getLong(2)))}
            }
        }
        if(recovered)changed()
        val waitingIds = jobs.mapTo(HashSet()) { it.id }
        synchronized(gate) { pendingMediaMessages.keys.retainAll(waitingIds) }
        for(job in jobs) {
            currentCoroutineContext().ensureActive()
            if(now-job.createdAt>7L*86400000){fail(job.id,"expired");continue}
            val request: ForwardQueueRequest
            val source: List<MessageActionSnapshot>
            try {request=decode(job.payload);source=actions.capture(request.sourcePeer,request.ids,allowWaiting=true,includePreview=!request.omitPreview);requireSnapshot(source,request)}
            catch(cancelled: CancellationException){throw cancelled}
            catch(_: IllegalStateException){fail(job.id,"source_changed");continue}
            catch(_: IllegalArgumentException){fail(job.id,"source_changed");continue}
            catch(_: org.json.JSONException){fail(job.id,"source_changed");continue}
            try {database.withTransaction {requireRecipients(database.openHelper.writableDatabase,request.recipients)}}
            catch(cancelled: CancellationException){throw cancelled}
            catch(_: IllegalStateException){fail(job.id,"recipient_unavailable");continue}
            val missing = source.filterNot { it.available }
            if (missing.isNotEmpty()) {
                val failed = database.withTransaction {
                    val db = database.openHelper.writableDatabase
                    if (unavailableDownloads(db, missing, request.omitPreview).isEmpty() ||
                        !exists(db, "SELECT 1 FROM forward_jobs WHERE id=? AND state='waiting'", arrayOf(job.id)))
                        return@withTransaction false
                    db.execSQL("UPDATE forward_jobs SET state='failed',error_code='source_download_unavailable' WHERE id=?", arrayOf(job.id))
                    true
                }
                if (failed) {
                    synchronized(gate) { pendingMediaMessages.remove(job.id) }
                    changed()
                    continue
                }
            }
            try {actions.requireCopySpace(source,request.recipients.size,includePreview=!request.omitPreview)}
            catch(_: org.msgat.core.media.InsufficientObjectSpaceException){fail(job.id,"insufficient_space");continue}
            val pending = missing.firstOrNull()?.let { WaitingMedia(missing.size, it.id) }
            val availabilityChanged = synchronized(gate) {
                if (pending == null) pendingMediaMessages.remove(job.id) != null
                else pendingMediaMessages.put(job.id, pending) != pending
            }
            if (availabilityChanged) changed()
            if (pending != null) continue
            val claimed=database.withTransaction {
                val db=database.openHelper.writableDatabase
                val present=exists(db,"SELECT 1 FROM forward_jobs WHERE id=? AND state='waiting'",arrayOf(job.id))
                if(present)db.execSQL("UPDATE forward_jobs SET state='preparing' WHERE id=?",arrayOf(job.id))
                present
            }
            if(!claimed)continue
            val preparation=ObjectPreparation()
            val task=synchronized(gate){
                scope.async {commit(job.id,request,preparation)}.also {
                    it.invokeOnCompletion {preparation.close()}
                    activeId=job.id;activeTask=it;activePreparation=preparation
                    if(closed){preparation.cancel();it.cancel()}
                }
            }
            changed()
            try {if(!task.await())fail(job.id,"preparation_failed") else changed()}
            catch(cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                // An explicit cancellation already removed the task row.
            }
            catch(_: org.msgat.core.media.InsufficientObjectSpaceException){fail(job.id,"insufficient_space")}
            catch(_: Exception){fail(job.id,"preparation_failed")}
            finally {
                synchronized(gate){activeId=null;activeTask=null;activePreparation=null}
                withContext(NonCancellable) {database.withTransaction {
                    database.openHelper.writableDatabase.execSQL("UPDATE forward_jobs SET state='waiting' WHERE id=? AND state='preparing'",arrayOf(job.id))
                }}
            }
        }
    }
    companion object {
        private fun exists(db: SupportSQLiteDatabase,sql: String,args: Array<Any?>)=db.query(SimpleSQLiteQuery(sql,args)).use {it.moveToFirst()}
        fun requireRecipients(db: SupportSQLiteDatabase,recipients: List<String>) {
            recipients.forEach {check(exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(it))){"A selected recipient is unavailable"}}
        }
        fun requireSnapshot(source: List<MessageActionSnapshot>,request: ForwardQueueRequest) {
            check(source.map {it.id}==request.ids && source.map(::fingerprint)==request.fingerprints){"A selected message changed after forwarding was queued"}
        }
        private fun fingerprint(row: MessageActionSnapshot): String {
            val hash=MessageDigest.getInstance("SHA-256")
            listOf("msgat/forward-source/v1",if(row.saved)"1" else "0",row.peer,row.id,row.kind,row.text,row.caption,row.manifest,row.timestamp.toString(),row.forwardAt.toString()).forEach {field ->
                val bytes=field?.toByteArray(Charsets.UTF_8)
                hash.update(((bytes?.size?:-1).toString()+":").toByteArray(Charsets.US_ASCII))
                if(bytes!=null){hash.update(bytes);bytes.fill(0)}
            }
            if(row.isSticker)hash.update("1:1".toByteArray(Charsets.US_ASCII))
            row.previewJson?.let {preview ->val bytes=preview.toByteArray(Charsets.UTF_8);hash.update((bytes.size.toString()+":").toByteArray(Charsets.US_ASCII));hash.update(bytes);bytes.fill(0)}
            row.contactAccount?.let { hash.update(("contact:" + it).toByteArray(Charsets.US_ASCII)) }
            row.location?.let {
                hash.update("location:${it.latitudeE6},${it.longitudeE6}".toByteArray(Charsets.US_ASCII))
            }
            return hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        }
        private fun encode(request: ForwardQueueRequest)=JSONObject().put("version",if (request.omitPreview) 3 else 2).put("omit_preview",request.omitPreview).put("source_peer",request.sourcePeer?:JSONObject.NULL)
            .put("ids",JSONArray(request.ids)).put("recipients",JSONArray(request.recipients)).put("hide_sender",request.hideSender).put("hide_caption",request.hideCaption)
            .put("fingerprints",JSONArray(request.fingerprints)).put("send_at",request.options.sendAt?:JSONObject.NULL).put("silent",request.options.silent).toString()
        private fun decode(payload: String): ForwardQueueRequest {
            val value=JSONObject(payload)
            fun strings(name: String)=value.getJSONArray(name).let {array->List(array.length()){array.getString(it)}}
            val version = value.getInt("version")
            check(version in 1..3){"Unsupported forwarding task"}
            val result=ForwardQueueRequest(if(value.isNull("source_peer"))null else value.getString("source_peer"),strings("ids"),strings("recipients"),value.getBoolean("hide_sender"),value.getBoolean("hide_caption"),strings("fingerprints"),SendOptions(if(value.isNull("send_at"))null else value.getLong("send_at"),if(value.has("silent"))value.getBoolean("silent")else false), version == 3 && value.getBoolean("omit_preview"))
            check(result.options.sendAt?.let{it in 0..253402214399999L}?:true){"Unsupported forwarding time"}
            check(result.ids.size in 1..100 && result.ids.distinct().size==result.ids.size && result.recipients.size in 1..20 && result.recipients.distinct().size==result.recipients.size && result.fingerprints.size==result.ids.size && result.fingerprints.all {it.length==64}){"Unsupported forwarding task"}
            return result
        }
    }
}
