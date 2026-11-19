// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.data.db

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.core.media.EncryptedObjectStore
import org.msgat.core.media.ObjectPreparation
import org.msgat.core.network.LocationPoint
import java.util.UUID

data class SavedMessageItem(val id: String,val sourcePeer: String,val sourceId: String,val kind: String,val text: String,val reference: String?,val savedAt: Long,val author: String,val originalAt: Long,val contactAccount: String?=null)
internal data class SavedMessageSource(val peer: String, val alias: String?, val avatar: String?, val count: Int)
internal data class MessageActionSnapshot(val saved: Boolean,val peer: String,val id: String,val kind: String,val text: String,val caption: String,val manifest: String?,val timestamp: Long,val author: String,val forwardName: String?,val forwardAt: Long,val available: Boolean,val previewJson: String?=null,val previewManifest: String?=null,val isSticker: Boolean=false,val contactAccount: String?=null,val location: LocationPoint?=null,val previewAvailable:Boolean=true)
internal data class PreparedMessageAction(val source: MessageActionSnapshot,val content: JSONObject?,val previewObject: JSONObject?=null)

internal class MessageActionsStore(private val database: AppDatabase,private val prefs: SecurePrefs,private val objects: EncryptedObjectStore) {
    private val ownerGate=Any()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var closed=false
    private suspend fun <T> owned(block: suspend ()->T): T {
        val task=synchronized(ownerGate) {check(!closed) {"The message actions are closed"};scope.async {block()}}
        return task.await()
    }
    suspend fun close() {
        synchronized(ownerGate) {closed=true}
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    suspend fun capture(peer: String?,ids: List<String>,allowWaiting: Boolean=false, includePreview: Boolean=true): List<MessageActionSnapshot> {
        require(ids.size in 1..100 && ids.distinct().size==ids.size) {"Select between 1 and 100 different messages"}
        return database.withTransaction {
            val db=database.openHelper.writableDatabase
            ids.map {id ->
                if (peer != null) requireCopyableSource(db, peer, id)
                val sql=if(peer==null) """SELECT s.source_peer,s.id,CASE WHEN EXISTS(SELECT 1 FROM saved_locations l WHERE l.id=s.id) THEN 'location' WHEN card.account IS NOT NULL THEN 'contact' ELSE s.kind END,s.body,s.body,o.manifest_json,s.original_at,s.author_name,NULL,s.original_at,o.local_state,card.account
                    FROM saved_messages s LEFT JOIN media_objects o ON o.object_id=s.object_id
                    LEFT JOIN saved_contact_cards card ON card.id=s.id WHERE s.id=?""" else """
                    SELECT m.conversationUuid,m.messageId,m.ext,m.content,COALESCE(a.caption,''),o.manifest_json,m.timestamp,
                     CASE WHEN m.isMine=1 THEN ? ELSE c.alias END,f.name,COALESCE(f.original_at,m.timestamp),o.local_state,card.account
                    FROM messages m JOIN conversations c ON c.uuid=m.conversationUuid
                    LEFT JOIN message_attachments a ON a.message_id=m.messageId LEFT JOIN media_objects o ON o.object_id=a.object_id
                    LEFT JOIN message_forwards f ON f.message_id=m.messageId
                    LEFT JOIN message_contact_cards card ON card.message_id=m.messageId
                    WHERE m.messageId=? AND m.conversationUuid=? AND m.isRevoked=0 AND m.ext IN ('txt','attachment','contact','location')
                    """
                val args=if(peer==null) arrayOf<Any?>(id) else arrayOf<Any?>(prefs.myNickname?:"Me",id,peer)
                db.query(SimpleSQLiteQuery(sql,args)).use {c ->
                    check(c.moveToFirst()) {"A selected message is no longer available"}
                    val kind=c.getString(2)
                    val contact = if (c.isNull(11)) null else c.getString(11)
                    check((kind=="contact")== (contact!=null)) {"The contact card is no longer available"}
                    val location = if (kind=="location") {
                        requireNotNull(locationPoint(db,id,peer==null)) {"The location is no longer available"}
                    } else null
                    val available=kind!="attachment" || (!c.isNull(10) && c.getInt(10)==1)
                    check(kind!="attachment" || (!c.isNull(5) && (allowWaiting || available))) {"Wait for the selected attachments to finish downloading"}
                    val preview=WebPreviewStore.body(db,id,peer==null);val image=preview?.optJSONObject("image")
                    val previewAvailable=image==null || db.query(SimpleSQLiteQuery("SELECT 1 FROM media_objects WHERE object_id=? AND local_state=1",arrayOf(image.getString("object_id")))).use {it.moveToFirst()}
                    val ready = available && (!includePreview || previewAvailable)
                    check(allowWaiting || ready){"Wait for the selected preview images to finish downloading"}
                    MessageActionSnapshot(peer==null,c.getString(0),c.getString(1),kind,c.getString(3),c.getString(4),if(c.isNull(5))null else c.getString(5),c.getLong(6),name(c.getString(7)),if(c.isNull(8))null else name(c.getString(8)),c.getLong(9),ready,preview?.toString(),image?.toString(),db.query(SimpleSQLiteQuery(if(peer==null)"SELECT 1 FROM saved_stickers WHERE id=?" else "SELECT 1 FROM message_stickers WHERE message_id=?",arrayOf(id))).use {it.moveToFirst()},contact,location,previewAvailable)
                }
            }.sortedWith(compareBy<MessageActionSnapshot> {it.timestamp}.thenBy {it.id})
        }
    }

    internal class DamagedForwardCache(val source: MessageActionSnapshot, val previewOnly: Boolean) :
        IllegalStateException("The forwarding source cache is damaged")

    suspend fun prepare(source: List<MessageActionSnapshot>,preparation:org.msgat.core.media.ObjectPreparation?=null,
                        includePreview: Boolean=true, detectDamagedCache: Boolean = false,
                        onObject:()->Unit={}): List<PreparedMessageAction> {
        val result=mutableListOf<PreparedMessageAction>()
        suspend fun copy(row: MessageActionSnapshot, encoded: String, previewOnly: Boolean): JSONObject {
            val manifest = JSONObject(encoded)
            val reference = EncryptedObjectStore.reference(manifest.getString("object_id"))
            onObject()
            try {
                if (row.isSticker && !previewOnly) objects.validateSticker(reference)
                return objects.copyObject(manifest, preparation)
            } catch (error: Exception) {
                if (!detectDamagedCache || row.saved || error is CancellationException ||
                    error is org.msgat.core.media.InsufficientObjectSpaceException) throw error
                preparation?.check()
                val complete = try { objects.hasCompleteCiphertext(reference) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { throw error }
                if (!complete) throw DamagedForwardCache(row, previewOnly)
                throw error
            }
        }
        try {
            for(row in source) {
                currentCoroutineContext().ensureActive()
                result += PreparedMessageAction(row, row.manifest?.let { copy(row, it, previewOnly = false) })
                if (includePreview) row.previewManifest?.let {
                    result[result.lastIndex] = result.last().copy(previewObject = copy(row, it, previewOnly = true))
                }
            }
            return result
        }
        catch(error: Exception) {discard(result);throw error}
    }
    fun discard(prepared: List<PreparedMessageAction>) {prepared.forEach {it.content?.let(objects::discardCopy);it.previewObject?.let(objects::discardCopy)}}

    private fun requireCopyableSource(db: SupportSQLiteDatabase, peer: String, id: String) {
        check(!MessageExpirationStore.isExpiring(db, id)) {
            "Messages set to auto-delete cannot be saved or forwarded"
        }
        val retentionDue = db.query(SimpleSQLiteQuery("""
            SELECT 1 FROM messages m JOIN conversation_retention r ON r.peer=m.conversationUuid
            WHERE m.messageId=? AND m.conversationUuid=? AND (m.isMine=0 OR m.status>=2)
              AND m.timestamp<=?-r.duration_ms
            """, arrayOf(id, peer, System.currentTimeMillis()))).use { it.moveToFirst() }
        check(!retentionDue) { "A selected message is no longer available" }
    }

    fun requireCurrent(db: SupportSQLiteDatabase,source: MessageActionSnapshot) {
        if (!source.saved) requireCopyableSource(db, source.peer, source.id)
        val sql=if(source.saved) """SELECT 1 FROM saved_messages s LEFT JOIN media_objects o ON o.object_id=s.object_id
            WHERE s.id=? AND s.kind=? AND s.body=? AND COALESCE(o.manifest_json,'')=COALESCE(?,'')""" else """
            SELECT 1 FROM messages m LEFT JOIN message_attachments a ON a.message_id=m.messageId LEFT JOIN media_objects o ON o.object_id=a.object_id
            WHERE m.messageId=? AND m.conversationUuid=? AND m.isRevoked=0 AND m.ext=? AND m.content=?
             AND COALESCE(a.caption,'')=? AND COALESCE(o.manifest_json,'')=COALESCE(?,'')
            """
        val args=if(source.saved) arrayOf(source.id,if(source.kind=="attachment")"attachment" else "txt",source.text,source.manifest) else arrayOf(source.id,source.peer,source.kind,source.text,source.caption,source.manifest)
        val sticker=db.query(SimpleSQLiteQuery(if(source.saved)"SELECT 1 FROM saved_stickers WHERE id=?" else "SELECT 1 FROM message_stickers WHERE message_id=?",arrayOf(source.id))).use {it.moveToFirst()}
        check(sticker==source.isSticker && contactAccount(db,source.id,source.saved)==source.contactAccount &&
            locationPoint(db,source.id,source.saved)==source.location &&
            db.query(SimpleSQLiteQuery(sql,args)).use {it.moveToFirst()} && WebPreviewStore.body(db,source.id,source.saved)?.toString()==source.previewJson) {"A selected message changed while preparing. Select it again"}
    }
    fun previewBody(row: PreparedMessageAction): JSONObject?=row.source.previewJson?.let {JSONObject(it).put("image",row.previewObject?:JSONObject.NULL)}
    fun forwardHeader(source: MessageActionSnapshot)=JSONObject().put("name",source.forwardName?:source.author).put("original_at",source.forwardAt)
    fun requireCopySpace(source: List<MessageActionSnapshot>,copies: Int, includePreview: Boolean=true) {
        require(copies in 1..20)
        var copyBytes = 0L
        var downloadBytes = 0L
        val pendingObjects = HashSet<String>()
        for (row in source) {
            for (encoded in listOfNotNull(row.manifest, row.previewManifest.takeIf { includePreview })) {
                val manifest = JSONObject(encoded)
                copyBytes = Math.addExact(copyBytes, manifest.getLong("cipher_size"))
                if (!row.saved && !row.available && pendingObjects.add(manifest.getString("object_id"))) {
                    downloadBytes = Math.addExact(downloadBytes, objects.remainingDownloadBytes(manifest))
                }
            }
        }
        val copied = Math.multiplyExact(copyBytes, copies.toLong())
        objects.requireFreeSpace(Math.addExact(copied, downloadBytes))
    }

    suspend fun save(peer: String,ids: List<String>,preparation: ObjectPreparation) {
        val selected=ids.toList()
        owned {saveCore(peer,selected,preparation)}
    }
    private suspend fun saveCore(peer: String,ids: List<String>,preparation: ObjectPreparation) {
        preparation.check()
        val source = capture(peer, ids, includePreview = false).map { row ->
            if (row.previewAvailable) row else row.copy(previewManifest = null)
        }
        requireCopySpace(source, 1)
        val objectCount = source.count { it.manifest != null } + source.count { it.previewManifest != null }
        var objectIndex = 0
        val prepared = prepare(source, preparation) {
            preparation.startAttachment(++objectIndex, objectCount)
        }
        var committed=false
        val newIds=prepared.map {UUID.randomUUID().toString()}.sorted();val created=System.currentTimeMillis()
        try {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {database.withTransaction {
                val db=database.openHelper.writableDatabase
                preparation.beginCommit()
                for((index,row) in prepared.withIndex()) {
                    requireCurrent(db,row.source)
                    row.content?.let {EncryptedObjectStore.remember(it,db,true)}
                    db.execSQL("INSERT INTO saved_messages(id,source_peer,source_id,kind,body,object_id,saved_at,author_name,original_at) VALUES(?,?,?,?,?,?,?,?,?)",
                        arrayOf(newIds[index],row.source.peer,row.source.id,if(row.source.kind=="attachment")"attachment" else "txt",if(row.source.kind=="attachment")row.source.caption else row.source.text,
                            row.content?.getString("object_id"),created,row.source.forwardName?:row.source.author,row.source.forwardAt))
                    if(row.source.isSticker)db.execSQL("INSERT INTO saved_stickers(id) VALUES(?)",arrayOf(newIds[index]))
                    row.source.contactAccount?.let { account ->
                        db.execSQL("INSERT INTO saved_contact_cards(id,account) VALUES(?,?)",arrayOf(newIds[index],account))
                    }
                    row.source.location?.let { point ->
                        db.execSQL("INSERT INTO saved_locations(id,latitude_e6,longitude_e6) VALUES(?,?,?)",
                            arrayOf(newIds[index],point.latitudeE6,point.longitudeE6))
                    }
                    WebPreviewStore.record(db,newIds[index],previewBody(row),row.source.text,"",true,created,saved=true)
                }
            };committed=true}
        } finally {if(!committed) discard(prepared)}
    }

    suspend fun sources(): List<SavedMessageSource> = owned {
        database.openHelper.readableDatabase.query("""
            SELECT s.source_peer, NULLIF(c.alias,''), c.avatarPath, COUNT(*)
            FROM saved_messages s LEFT JOIN conversations c ON c.uuid=s.source_peer
            GROUP BY s.source_peer,c.alias,c.avatarPath ORDER BY MAX(s.saved_at) DESC,s.source_peer
        """.trimIndent()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(SavedMessageSource(cursor.getString(0), cursor.getString(1),
                    cursor.getString(2), cursor.getInt(3)))
            }
        }
    }

    suspend fun list(before: MessageSearchCursor? = null, sourcePeer: String? = null, input: String = ""): List<SavedMessageItem> {
        val query = input.trim()
        require(query.codePointCount(0, query.length) <= 256 && '\u0000' !in query)
        val caller = currentCoroutineContext()
        return owned {
            val matches = ArrayList<SavedMessageItem>(51)
            var cursor = before
            while (matches.size < 51) {
                caller.ensureActive()
                currentCoroutineContext().ensureActive()
                val batch = savedPage(cursor, sourcePeer, if (query.isEmpty()) 51 else 64)
                for ((item, filename) in batch) {
                    caller.ensureActive()
                    if (query.isEmpty() || item.author.contains(query, ignoreCase = true) ||
                        filename?.contains(query, ignoreCase = true) == true ||
                        SecurityCore.messageSummary(item.text, 48 * 1024).contains(query, ignoreCase = true)) {
                        matches.add(item)
                        if (matches.size == 51) break
                    }
                }
                if (query.isEmpty() || batch.size < 64) break
                val last = batch.last().first
                cursor = MessageSearchCursor(last.savedAt, last.id)
            }
            matches
        }
    }

    private fun savedPage(before: MessageSearchCursor?, peer: String?, limit: Int): List<Pair<SavedMessageItem, String?>> {
        val args = mutableListOf<Any?>()
        val predicates = mutableListOf<String>()
        if (peer != null) { predicates += "s.source_peer=?"; args.add(peer) }
        if (before != null) {
            predicates += "(s.saved_at,s.id)<(?,?)"
            args.add(before.timestamp)
            args.add(before.messageId)
        }
        val filter = if (predicates.isEmpty()) "" else "WHERE " + predicates.joinToString(" AND ")
        args.add(limit)
        return database.openHelper.readableDatabase.query(SimpleSQLiteQuery("""
            SELECT s.id,s.source_peer,s.source_id,
                CASE WHEN EXISTS(SELECT 1 FROM saved_locations l WHERE l.id=s.id) THEN 'location'
                     WHEN card.account IS NOT NULL THEN 'contact' ELSE s.kind END,
                s.body,s.object_id,s.saved_at,s.author_name,s.original_at,card.account,
                CASE WHEN json_valid(o.manifest_json) THEN json_extract(o.manifest_json,'$.filename') END
            FROM saved_messages s LEFT JOIN saved_contact_cards card ON card.id=s.id
                LEFT JOIN media_objects o ON o.object_id=s.object_id
            $filter ORDER BY s.saved_at DESC,s.id DESC LIMIT ?
        """.trimIndent(), args.toTypedArray())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val reference = cursor.getString(5)?.let { EncryptedObjectStore.REFERENCE_PREFIX + it }
                    add(SavedMessageItem(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                        cursor.getString(4), reference, cursor.getLong(6), cursor.getString(7), cursor.getLong(8),
                        cursor.getString(9)) to cursor.getString(10))
                }
            }
        }
    }

    suspend fun hasOriginal(item: SavedMessageItem): Boolean = owned {
        database.openHelper.readableDatabase.query(SimpleSQLiteQuery("""
            SELECT 1 FROM saved_messages s JOIN messages m ON m.messageId=s.source_id AND m.conversationUuid=s.source_peer
            JOIN conversations c ON c.uuid=s.source_peer
            WHERE s.id=? AND s.source_id=? AND s.source_peer=? AND m.isRevoked=0
        """.trimIndent(), arrayOf(item.id, item.sourceId, item.sourcePeer))).use { it.moveToFirst() }
    }

    suspend fun canExport(item: SavedMessageItem): Boolean = owned {
        database.openHelper.readableDatabase.query(SimpleSQLiteQuery("""
            SELECT 1 FROM saved_messages s JOIN media_objects o ON o.object_id=s.object_id
            WHERE s.id=? AND (? || s.object_id)=? AND o.local_state=1
        """.trimIndent(), arrayOf(item.id, EncryptedObjectStore.REFERENCE_PREFIX, item.reference))).use { it.moveToFirst() }
    }

    suspend fun savedContactAccount(id: String): String? = owned { contactAccount(database.openHelper.readableDatabase,id,true) }
    suspend fun savedLocation(id: String): LocationPoint? = owned { locationPoint(database.openHelper.readableDatabase,id,true) }

    private fun contactAccount(db: SupportSQLiteDatabase,id: String,saved: Boolean): String? {
        val sql = if (saved) "SELECT account FROM saved_contact_cards WHERE id=?"
            else "SELECT account FROM message_contact_cards WHERE message_id=?"
        return db.query(SimpleSQLiteQuery(sql,arrayOf(id))).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun locationPoint(db: SupportSQLiteDatabase,id: String,saved: Boolean): LocationPoint? {
        val sql = if (saved) "SELECT latitude_e6,longitude_e6 FROM saved_locations WHERE id=?"
            else "SELECT latitude_e6,longitude_e6 FROM message_locations WHERE message_id=?"
        return db.query(SimpleSQLiteQuery(sql,arrayOf(id))).use { cursor ->
            if (cursor.moveToFirst()) LocationPoint(cursor.getInt(0),cursor.getInt(1)) else null
        }
    }

    suspend fun delete(ids: List<String>) {
        val selected=ids.toList()
        owned {deleteCore(selected)}
    }
    private suspend fun deleteCore(ids: List<String>) {
        require(ids.size in 1..100)
        database.withTransaction {
            val db=database.openHelper.writableDatabase
            for(id in ids)db.execSQL("DELETE FROM saved_messages WHERE id=?",arrayOf(id))
        }
    }

    private fun name(value: String): String {
        var text=value.filterNot {it.isISOControl()}.trim()
        while(text.toByteArray(Charsets.UTF_8).size>256) text=text.substring(0,text.offsetByCodePoints(text.length,-1))
        return text.ifEmpty {"User"}
    }
}
