// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.InvalidationTracker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.AppDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest

data class AlbumPosition(val id: String, val index: Int, val count: Int)
enum class AttachmentDownloadIntent { AUTOMATIC, REQUESTED, PAUSED }

/** Safe to expose to views: contains neither the content key nor the relay capability. */
data class AttachmentMetadata(
    val reference: String, val filename: String, val mime: String, val size: Long,
    val available: Boolean, val caption: String, val isSticker: Boolean=false, val album: AlbumPosition?=null,
    val remoteUnavailable: Boolean=false,
    val downloadIntent: AttachmentDownloadIntent = AttachmentDownloadIntent.AUTOMATIC,
    val hasPendingDownload: Boolean = false
) {
    val isImage get() = mime.startsWith("image/")
    val isVideo get() = mime.startsWith("video/")
    val isAudio get() = mime.startsWith("audio/")
}
internal enum class SharedMediaCategory(val value: String) {
    MEDIA("media"), PHOTOS("photos"), VIDEOS("videos"), FILES("files"), LINKS("links"), AUDIO("audio");

    val visual get() = this == MEDIA || this == PHOTOS || this == VIDEOS
}

internal data class SharedMediaCursor(val timestamp: Long, val messageId: String)
internal data class SharedMediaItem(val id: String, val timestamp: Long, val isMine: Boolean, val attachment: AttachmentMetadata)
internal data class VisualMediaNeighbors(val newer: SharedMediaItem?, val older: SharedMediaItem?)
internal data class SharedLink(val id: String, val timestamp: Long, val urls: List<String>)
internal data class SharedMediaPage(
    val items: List<SharedMediaItem>, val older: SharedMediaCursor?, val links: List<SharedLink> = emptyList()
)

internal class InsufficientObjectSpaceException: IOException("Not enough free storage to prepare encrypted attachments")

/** Ciphertext files only. Display names, capabilities and keys remain in SQLCipher. */
internal class EncryptedObjectStore(
    context: Context,
    private val database: AppDatabase,
    private val prefs: SecurePrefs
) {
    private val _availability=kotlinx.coroutines.flow.MutableStateFlow(0L)
    val availability: kotlinx.coroutines.flow.StateFlow<Long> = _availability
    fun signalAvailable() {_availability.value+=1}
    private val _cacheRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val cacheRevision: kotlinx.coroutines.flow.StateFlow<Long> = _cacheRevision
    fun signalCacheCleared() {
        _cacheRevision.value += 1
        signalAvailable()
    }
    private val context = context.applicationContext
    private val thumbnailWork = java.util.concurrent.locks.ReentrantReadWriteLock(true)
    @Volatile private var thumbnailWorkClosing = false
    val directory = File(this.context.noBackupFilesDir, "objects/v2")
    private val lifecycle=EncryptedObjectLifecycle(database,directory)
    val captures=EncryptedCaptureStore(this,directory)
    internal fun recordingCandidate(capacity:Long,filename:String,mime:String):JSONObject=create(capacity,filename,mime)
    internal fun discardRecording(id:String){lifecycle.discard(id)}
    suspend fun importRecording(source:EncryptedCaptureFile,preparation:ObjectPreparation?=null,asFile:Boolean=false):JSONObject=withContext(Dispatchers.IO){
        source.retainFrozen().use{
            val candidate=create(source.size,source.filename,if(asFile)"application/octet-stream"else source.mime)
            try{source.importTo(directory,candidate,preparation)}catch(error:Exception){discardCopy(candidate);throw error}
        }
    }
    fun pinTransfer(id: String, direction: Int, eligible: (SupportSQLiteDatabase) -> Boolean): AutoCloseable? {
        var pinned=false
        database.runInTransaction {
            val db=database.openHelper.writableDatabase
            if (eligible(db)) {
                db.execSQL("""
                    INSERT INTO object_transfer_pins(object_id,direction,owner) VALUES(?,?,?)
                    ON CONFLICT(object_id,direction) DO UPDATE SET owner=excluded.owner
                    WHERE object_transfer_pins.owner<>excluded.owner
                    """, arrayOf(id, direction, database.storageOwner))
                pinned = db.query("SELECT changes()").use { it.moveToFirst() && it.getInt(0) != 0 }
            }
        }
        return if(!pinned)null else AutoCloseable {database.runInTransaction {
            database.openHelper.writableDatabase.execSQL("DELETE FROM object_transfer_pins WHERE object_id=? AND direction=? AND owner=?",arrayOf(id,direction,database.storageOwner))
        }}
    }
    suspend fun collectGarbage(): Int=lifecycle.collect()

    suspend fun import(uri: Uri,preparation:ObjectPreparation?=null,asFile:Boolean=false,compressPhotos:Boolean=false,compressVideos:Boolean=false,videoTrim:VideoTrim?=null): JSONObject = withContext(Dispatchers.IO) {
        preparation?.check()
        val resolver = context.contentResolver
        val details = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val length = cursor.getColumnIndex(OpenableColumns.SIZE)
            val knownSize = if (length >= 0 && !cursor.isNull(length)) cursor.getLong(length).takeIf { it >= 0 } else null
            (if (name >= 0) cursor.getString(name) else null) to knownSize
        }
        val filename = details?.first?.substringAfterLast('/')?.substringAfterLast('\\') ?: "attachment.bin"
        if (videoTrim != null) {
            require(resolver.getType(uri)?.startsWith("video/") == true) { "The selected video is no longer available" }
            return@withContext VideoCompression.import(
                context, this@EncryptedObjectStore, uri, filename, preparation,
                trim = videoTrim, compress = compressVideos && !asFile, asFile = asFile
            ) ?: throw java.io.IOException("The edited video could not be exported")
        }
        val mime = if(asFile)"application/octet-stream" else resolver.getType(uri) ?: "application/octet-stream"
        if (compressVideos && !asFile && mime.startsWith("video/")) {
            val encoded = VideoCompression.import(context, this@EncryptedObjectStore, uri, filename, preparation)
            if (encoded != null) return@withContext encoded
            preparation?.setStep(PreparationStep.IMPORTING)
        }
        if(RecordingMediaStore.isRecording(context,uri)) {
            val (source,owner)=RecordingMediaStore.importSource(context,uri)
            return@withContext owner.use{importRecording(source,preparation,asFile)}
        }
        if (compressPhotos && !asFile && PhotoCompression.supports(mime)) {
            preparation?.setStep(PreparationStep.ENCODING_PHOTO)
            val compressed = PhotoCompression.compress(context, uri, mime, preparation)
            try {
                preparation?.setStep(PreparationStep.IMPORTING)
                if (compressed != null) {
                    return@withContext importBytes(compressed, filename, mime, preparation)
                }
            } finally {
                compressed?.fill(0)
            }
        }
        // Content providers can return a pipe or a subrange of a larger file.
        // openInputStream preserves the range and the original read grant.
        importSource(filename, mime, preparation, details?.second) {
            resolver.openInputStream(uri) ?: throw IOException("Cannot open the selected attachment")
        }
    }

    private fun create(size: Long, filename: String, mime: String): JSONObject {
        val value = prefs.withRootSeed { seed ->
            SecurityCore.call(JSONObject().put("operation", "create_object").put("seed", encode(seed)).put("size", size))
        }
        value.put("filename", filename).put("mime", mime)
            .put("sha256", "0".repeat(64)).put("cipher_sha256", "0".repeat(64))
        // The root has already been cleared before file I/O begins.
        val manifest=SecurityCore.call(JSONObject().put("operation", "validate_object_manifest").put("manifest", value)).getJSONObject("manifest")
        requireFreeSpace(manifest.getLong("cipher_size"));lifecycle.stage(manifest.getString("object_id"))
        return manifest
    }

    /** Run on the import worker. Commit the returned manifest with its new message/saved item. */
    fun copyObject(source: JSONObject,preparation:ObjectPreparation?=null): JSONObject {
        preparation?.check()
        val original=SecurityCore.call(JSONObject().put("operation","validate_object_manifest").put("manifest",source)).getJSONObject("manifest")
        val candidate=create(original.getLong("size"),original.getString("filename"),original.getString("mime"))
        try {return SecurityCore.call(JSONObject().put("operation","rekey_object_file").put("source",original).put("manifest",candidate)
            .put("directory",directory.absolutePath).put("maximum_size",MAXIMUM_SIZE).put("work_id",preparation?.workID()?:JSONObject.NULL)).getJSONObject("manifest")}
        catch(error: Exception){discardCopy(candidate);throw error}
    }

    fun discardCopy(manifest: JSONObject) {lifecycle.discard(manifest.getString("object_id"))}

    suspend fun importBytes(bytes: ByteArray,filename: String,mime: String,preparation:ObjectPreparation?=null): JSONObject = withContext(Dispatchers.IO) {
        require(bytes.size.toLong()<=MAXIMUM_SIZE)
        importSource(filename,mime,preparation,bytes.size.toLong()) {java.io.ByteArrayInputStream(bytes)}
    }
    private fun importSource(filename: String,mime: String,preparation:ObjectPreparation?,knownSize: Long? = null,open: () -> java.io.InputStream): JSONObject {
        val buffer = ByteArray(65536)
        try {
            val size = knownSize ?: open().use { input ->
                var measured = 0L
                while (true) {
                    preparation?.check()
                    val read = readSome(input, buffer)
                    if (read < 0) break
                    measured += read
                    require(measured <= MAXIMUM_SIZE) { "Attachment exceeds the file size limit" }
                }
                measured
            }
            require(size in 0..MAXIMUM_SIZE) { "Attachment exceeds the file size limit" }
            preparation?.check()
            val metadata = create(size, filename, mime)
            val target = path(metadata.getString("object_id"))
            try {
                preparation?.report(size,0)
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create the encrypted object directory" }
                check(target.createNewFile()) { "Encrypted object identity collision" }
                val plainHash = MessageDigest.getInstance("SHA-256")
                val cipherHash = MessageDigest.getInstance("SHA-256")
                open().use { input ->
                    FileOutputStream(target).use { output ->
                        for (index in 0 until metadata.getInt("chunk_count")) {
                            preparation?.check()
                            val count = minOf(65536L, size - index.toLong() * 65536).toInt()
                            var filled = 0
                            while (filled < count) {
                                preparation?.check()
                                val read = input.read(buffer, filled, count - filled)
                                check(read > 0) { "The selected attachment changed while being read" }
                                filled += read
                            }
                            plainHash.update(buffer, 0, count)
                            val chunk = JSONObject().put("key", metadata.getString("key")).put("object_id", metadata.getString("object_id"))
                                .put("size", size).put("index", index).put("data", Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP))
                            val sealed = SecurityCore.call(JSONObject().put("operation", "seal_object_chunk").put("chunk", chunk))
                            val encrypted = Base64.decode(sealed.getString("data"), Base64.NO_WRAP)
                            try { output.write(encrypted); cipherHash.update(encrypted) } finally { encrypted.fill(0); buffer.fill(0) }
                            preparation?.report(size,minOf(size,(index+1L)*65536))
                        }
                        check(input.read() == -1) { "The selected attachment changed while being read" }
                        preparation?.check()
                        output.fd.sync()
                        preparation?.check()
                    }
                }
                SecurityCore.call(JSONObject().put("operation", "sync_object_publication")
                    .put("directory", directory.absolutePath).put("object_id", metadata.getString("object_id")))
                metadata.put("sha256", hex(plainHash.digest())).put("cipher_sha256", hex(cipherHash.digest()))
                return SecurityCore.call(JSONObject().put("operation", "validate_object_manifest").put("manifest", metadata)).getJSONObject("manifest")
            } catch (error: Exception) {discardCopy(metadata);throw error}
        } finally { buffer.fill(0) }
    }

    /** Caller must use a decoding/loading worker, never the main thread. */
    fun metadata(reference: String, messageID: String): AttachmentMetadata? {
        val objectID = id(reference)
        return database.openHelper.readableDatabase.query(SimpleSQLiteQuery(
            """SELECT o.manifest_json,o.local_state,COALESCE(a.caption,s.body,''),EXISTS(SELECT 1 FROM message_stickers WHERE message_id=? UNION ALL SELECT 1 FROM saved_stickers WHERE id=?),g.album_id,g.position,g.item_count,
                COALESCE(t.download_unavailable,0),
                CASE WHEN t.download_paused=1 THEN 2 WHEN t.download_requested=1 THEN 1 ELSE 0 END,t.event_id IS NOT NULL
                FROM media_objects o
                LEFT JOIN message_attachments a ON a.object_id=o.object_id AND a.message_id=?
                LEFT JOIN object_transfers t ON t.object_id=o.object_id AND t.event_id=? AND t.direction=1 AND t.owner_kind='message'
                LEFT JOIN message_album_items g ON g.message_id=a.message_id
                LEFT JOIN saved_messages s ON s.object_id=o.object_id AND s.id=?
                WHERE o.object_id=?""".trimIndent(), arrayOf(messageID,messageID,messageID,messageID,messageID,objectID))).use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val manifest = JSONObject(cursor.getString(0))
                AttachmentMetadata(reference, manifest.getString("filename"), manifest.getString("mime"),
                    manifest.getLong("size"), cursor.getInt(1) == 1, cursor.getString(2),cursor.getInt(3)!=0,
                    if (cursor.isNull(4)) null else AlbumPosition(cursor.getString(4), cursor.getInt(5), cursor.getInt(6)),
                    remoteUnavailable=cursor.getInt(7)!=0, downloadIntent=AttachmentDownloadIntent.entries[cursor.getInt(8)],
                    hasPendingDownload = cursor.getInt(9) != 0)
            }
        }
    }

    private val sharedMediaColumns = """
        SELECT message_id,created_at,is_mine,object_id,filename,mime,size,available,caption,
               EXISTS(SELECT 1 FROM object_transfers t WHERE t.object_id=media.object_id AND t.event_id=media.message_id
                      AND t.direction=1 AND t.download_unavailable=1),
               COALESCE((SELECT CASE WHEN t.download_paused=1 THEN 2 WHEN t.download_requested=1 THEN 1 ELSE 0 END
                      FROM object_transfers t WHERE t.object_id=media.object_id AND t.event_id=media.message_id AND t.direction=1),0)
        FROM conversation_media media
    """.trimIndent()

    private val sharedMediaVisible = """
        NOT EXISTS (SELECT 1 FROM message_expiration e WHERE e.message_id=media.message_id AND e.expires_at<=:at)
        AND NOT EXISTS (SELECT 1 FROM conversation_retention r JOIN messages m ON m.messageId=media.message_id
            WHERE r.peer=m.conversationUuid AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=:at-r.duration_ms)
    """.trimIndent()

    suspend fun sharedMedia(peer: String, category: SharedMediaCategory, before: SharedMediaCursor?, query: String = ""): SharedMediaPage =
        withContext(Dispatchers.IO) {
            if (category == SharedMediaCategory.LINKS) return@withContext sharedLinks(peer, before, query)
            val mediaFilter = if (category == SharedMediaCategory.MEDIA) "category IN ('photos','videos')" else "category=?"
            val filter = if (before == null) "" else " AND (created_at<? OR (created_at=? AND message_id<?))"
            val match = if (query.isEmpty()) "" else " AND instr(lower(filename),lower(?))>0"
            val args = buildList<Any> {
                add(peer)
                if (category != SharedMediaCategory.MEDIA) add(category.value)
                add(System.currentTimeMillis())
                if (before != null) addAll(listOf(before.timestamp, before.timestamp, before.messageId))
                if (query.isNotEmpty()) add(query)
            }.toTypedArray()
            val rows = readSharedMedia("""
                $sharedMediaColumns WHERE peer=? AND $mediaFilter
                AND $sharedMediaVisible
                $filter$match ORDER BY created_at DESC,message_id DESC LIMIT 41
            """.trimIndent(), args)
            val page = rows.take(40)
            SharedMediaPage(page, if (rows.size > page.size) page.lastOrNull()?.let { SharedMediaCursor(it.timestamp, it.id) } else null)
        }

    private suspend fun sharedLinks(peer: String, before: SharedMediaCursor?, query: String): SharedMediaPage {
        val now = System.currentTimeMillis()
        val links = mutableListOf<SharedLink>()
        var after = before
        var lastMatch: SharedMediaCursor? = null
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val anchor = after
            val filter = if (anchor == null) "" else " AND (m.timestamp<? OR (m.timestamp=? AND m.messageId<?))"
            val args = if (anchor == null) arrayOf(peer, now)
                else arrayOf(peer, now, anchor.timestamp, anchor.timestamp, anchor.messageId)
            val rows = database.openHelper.readableDatabase.query(SimpleSQLiteQuery("""
                SELECT m.messageId,m.timestamp,CASE WHEN m.ext='txt' THEN m.content ELSE a.caption END AS body
                FROM messages m LEFT JOIN message_attachments a ON a.message_id=m.messageId AND m.ext='attachment'
                LEFT JOIN message_expiration e ON e.message_id=m.messageId
                WHERE m.conversationUuid=? AND m.isRevoked=0 AND (e.expires_at IS NULL OR e.expires_at>?2)
                  AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                      AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?2-r.duration_ms)
                  AND (m.ext='txt' OR a.message_id IS NOT NULL)
                  AND (instr(lower(body),'https://')>0 OR instr(lower(body),'http://')>0 OR instr(lower(body),'www.')>0) $filter
                ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 80
            """.trimIndent(), args)).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(SharedMediaCursor(cursor.getLong(1), cursor.getString(0)) to cursor.getString(2))
                }
            }
            for ((cursor, body) in rows) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val urls = SecurityCore.call(JSONObject().put("operation", "visible_message_urls").put("text", body)).getJSONArray("urls")
                val visible = List(urls.length()) { urls.getString(it) }.filter { it.contains(query, ignoreCase = true) }
                if (visible.isEmpty()) continue
                if (links.size == 40) return SharedMediaPage(emptyList(), lastMatch, links)
                links.add(SharedLink(cursor.messageId, cursor.timestamp, visible))
                lastMatch = cursor
            }
            if (rows.size < 80) return SharedMediaPage(emptyList(), null, links)
            after = rows.last().first
        }
    }

    suspend fun sharedMediaItem(peer: String, messageId: String): SharedMediaItem? = withContext(Dispatchers.IO) {
        readSharedMedia("$sharedMediaColumns WHERE peer=? AND message_id=? AND $sharedMediaVisible",
            arrayOf(peer, messageId, System.currentTimeMillis())).firstOrNull()
    }

    suspend fun visualMediaNeighbors(
        peer: String, anchor: SharedMediaCursor, category: SharedMediaCategory = SharedMediaCategory.MEDIA,
        query: String = "", before: SharedMediaCursor? = null
    ): VisualMediaNeighbors = withContext(Dispatchers.IO) {
        require(category.visual)
        val now = System.currentTimeMillis()
        val filter = if (category == SharedMediaCategory.MEDIA) "category IN ('photos','videos')" else "category=?"
        val search = if (query.isEmpty()) "" else " AND instr(lower(filename),lower(?))>0"
        val boundary = if (before == null) "" else " AND (created_at,message_id)<(?,?)"
        val arguments = buildList<Any> {
            add(peer)
            if (category != SharedMediaCategory.MEDIA) add(category.value)
            add(now)
            if (query.isNotEmpty()) add(query)
            if (before != null) addAll(listOf(before.timestamp, before.messageId))
            addAll(listOf(anchor.timestamp, anchor.timestamp, anchor.messageId))
        }.toTypedArray()
        fun adjacent(newer: Boolean): SharedMediaItem? {
            val comparison = if (newer) ">" else "<"
            val order = if (newer) "ASC" else "DESC"
            val sql = """$sharedMediaColumns WHERE peer=? AND $filter AND available=1
                AND $sharedMediaVisible
                $search$boundary
                AND (created_at$comparison? OR (created_at=? AND message_id$comparison?))
                ORDER BY created_at $order,message_id $order LIMIT 1"""
            return readSharedMedia(sql, arguments).firstOrNull()
        }
        VisualMediaNeighbors(newer = adjacent(true), older = adjacent(false))
    }

    private fun readSharedMedia(sql: String, args: Array<out Any>): List<SharedMediaItem> =
        database.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val attachment = AttachmentMetadata(
                        reference(cursor.getString(3)), cursor.getString(4), cursor.getString(5), cursor.getLong(6),
                        cursor.getInt(7) == 1, cursor.getString(8), remoteUnavailable=cursor.getInt(9)!=0,
                        downloadIntent=AttachmentDownloadIntent.entries[cursor.getInt(10)]
                    )
                    add(SharedMediaItem(cursor.getString(0), cursor.getLong(1), cursor.getInt(2) != 0, attachment))
                }
            }
        }

    fun observeSharedMediaChanges() = callbackFlow {
        val observer = object : InvalidationTracker.Observer("messages") {
            override fun onInvalidated(tables: Set<String>) {
                trySend(Unit)
            }
        }
        database.invalidationTracker.addObserver(observer)
        awaitClose { database.invalidationTracker.removeObserver(observer) }
    }.flowOn(Dispatchers.IO)

    /** Explicit user-selected export is the only attachment path that writes plaintext. */
    suspend fun canExportMessage(peer: String, messageId: String, reference: String): Boolean = withContext(Dispatchers.IO) {
        database.openHelper.readableDatabase.query(androidx.sqlite.db.SimpleSQLiteQuery("""
            SELECT 1 FROM messages m JOIN message_attachments a ON a.message_id=m.messageId
            JOIN media_objects o ON o.object_id=a.object_id
            WHERE m.messageId=? AND m.conversationUuid=? AND m.content=? AND m.isRevoked=0 AND o.local_state=1
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?-r.duration_ms)
        """.trimIndent(), arrayOf(messageId, peer, reference, System.currentTimeMillis()))).use { it.moveToFirst() }
    }

    suspend fun export(reference: String, destination: Uri, onProgress: (Long) -> Unit = {},
                       allowed: suspend () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(65536)
        var written = 0L
        try {
            check(allowed()) { "The attachment can no longer be exported" }
            open(reference).use { input ->
                (context.contentResolver.openOutputStream(destination, "wt") ?: error("Cannot open the selected destination")).use { output ->
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        onProgress(written)
                    }
                    output.flush()
                }
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            check(allowed()) { "The attachment can no longer be exported" }
        } catch (error: Exception) {
            // This URI is returned by CreateDocument for this export, never an input file.
            runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, destination) }
            throw error
        } finally { buffer.fill(0) }
    }

    fun readStickerBytes(reference: String): ByteArray {
        validateSticker(reference)
        val manifest=database.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT manifest_json FROM media_objects WHERE object_id=? AND local_state=1",arrayOf(id(reference)))).use {check(it.moveToFirst());JSONObject(it.getString(0))}
        val size=manifest.getInt("size");require(size in 1..8*1024*1024);val bytes=ByteArray(size)
        try {
            open(reference).use {input->var offset=0;while(offset<size){val count=input.read(bytes,offset,size-offset);check(count>0);offset+=count}}
            check(hex(MessageDigest.getInstance("SHA-256").digest(bytes))==manifest.getString("sha256")){"Sticker content changed"};return bytes
        } catch(error: Throwable){bytes.fill(0);throw error}
    }
    fun validateSticker(reference: String) {
        val encoded=database.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT manifest_json FROM media_objects WHERE object_id=? AND local_state=1",arrayOf(id(reference)))).use {check(it.moveToFirst()){ "Sticker is unavailable" };it.getString(0)}
        SecurityCore.call(JSONObject().put("operation","inspect_sticker_object").put("manifest",JSONObject(encoded)).put("directory",directory.absolutePath))
    }

    // Keep derived-object reads and writes alive until the vault drains this worker.
    fun <T> withThumbnailWork(render: () -> T): T? {
        val permit = thumbnailWork.readLock()
        permit.lock()
        return try { if (thumbnailWorkClosing) null else render() }
        finally { permit.unlock() }
    }

    suspend fun closeThumbnailWork() = withContext(Dispatchers.IO) {
        thumbnailWorkClosing = true
        // This barrier waits for all previously admitted decodes and cache writes.
        val barrier = thumbnailWork.writeLock()
        barrier.lock()
        barrier.unlock()
    }

    fun thumbnailBytes(reference: String, edge: Int): ByteArray? {
        val thumbnail = database.openHelper.readableDatabase.query(SimpleSQLiteQuery("""
            SELECT t.thumbnail_object FROM media_thumbnail_cache t JOIN media_objects o ON o.object_id=t.object_id
            JOIN media_objects preview ON preview.object_id=t.thumbnail_object
            WHERE t.object_id=? AND t.edge=? AND o.local_state=1 AND preview.local_state=1
            AND json_extract(preview.manifest_json,'$.size') BETWEEN 1 AND 131072
            AND EXISTS(SELECT 1 FROM media_object_references r WHERE r.object_id=o.object_id)
        """.trimIndent(), arrayOf(id(reference), edge))).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return null
        return open(EncryptedObjectStore.reference(thumbnail)).use { it.readBytes() }
    }

    fun cacheThumbnail(reference: String, edge: Int, encoded: ByteArray, mime: String) {
        if (edge !in 1..640 || encoded.size !in 1..131072) return
        val filename = when (mime) {
            "image/jpeg" -> "thumbnail.jpg"
            "image/png" -> "thumbnail.png"
            else -> return
        }
        val id = id(reference)
        val staged = importSource(filename, mime, null) { java.io.ByteArrayInputStream(encoded) }
        var committed = false
        try {
            committed = database.runInTransaction(java.util.concurrent.Callable {
                val db = database.openHelper.writableDatabase
                val stillOwned = db.query(SimpleSQLiteQuery("""
                    SELECT 1 FROM media_objects o WHERE o.object_id=? AND o.local_state=1
                    AND EXISTS(SELECT 1 FROM media_object_references r WHERE r.object_id=o.object_id)
                """.trimIndent(), arrayOf(id))).use { it.moveToFirst() }
                if (!stillOwned) return@Callable false
                remember(staged, db, available = true)
                db.execSQL("""
                    INSERT INTO media_thumbnail_cache(object_id,edge,thumbnail_object) VALUES(?,?,?)
                    ON CONFLICT(object_id,edge) DO UPDATE SET thumbnail_object=excluded.thumbnail_object
                """.trimIndent(), arrayOf(id, edge, staged.getString("object_id")))
                true
            })
        } finally {
            if (!committed) discardCopy(staged)
        }
    }

    fun open(reference: String, offset: Long = 0): InputStream {
        val id = id(reference)
        val value = database.openHelper.readableDatabase.query(SimpleSQLiteQuery(
            "SELECT manifest_json FROM media_objects WHERE object_id=? AND local_state=1", arrayOf(id))).use { cursor ->
            check(cursor.moveToFirst()) { "The encrypted attachment has not been downloaded" }
            JSONObject(cursor.getString(0))
        }
        require(offset in 0..value.getLong("size"))
        return ObjectInput(value, offset)
    }

    suspend fun hasCompleteCiphertext(reference: String): Boolean = withContext(Dispatchers.IO) {
        val objectId = id(reference)
        val (expected, hash) = database.openHelper.readableDatabase.query(SimpleSQLiteQuery(
            """SELECT json_extract(manifest_json,'$.cipher_size'),json_extract(manifest_json,'$.cipher_sha256')
                FROM media_objects WHERE object_id=? AND local_state=1""",
            arrayOf(objectId)
        )).use { row -> if (row.moveToFirst()) row.getLong(0) to row.getString(1) else return@withContext false }
        try {
            val file = path(objectId).toPath()
            val info = java.nio.file.Files.readAttributes(file,
                java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            if (!info.isRegularFile) throw IOException("Invalid encrypted attachment file")
            if (info.size() != expected) return@withContext false
            val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            java.nio.file.Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
                var remaining = expected
                while (remaining > 0) {
                    if (job?.isActive == false) throw kotlinx.coroutines.CancellationException()
                    val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (read < 0) return@withContext false
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
                input.read() == -1 && hex(digest.digest()) == hash
            }
        } catch (_: java.nio.file.NoSuchFileException) {
            false
        }
    }

    fun requireFreeSpace(cipherBytes: Long) {
        if(cipherBytes==0L)return
        require(cipherBytes>0){"Invalid encrypted storage budget"}
        val required=Math.addExact(cipherBytes,256L*1024*1024)
        var location=directory
        while(!location.exists())location=location.parentFile?:throw IOException("Encrypted storage volume is unavailable")
        if(android.os.StatFs(location.path).availableBytes<required)throw InsufficientObjectSpaceException()
    }
    fun requireDownloadSpace(manifest: JSONObject) = requireFreeSpace(remainingDownloadBytes(manifest))

    fun remainingDownloadBytes(manifest: JSONObject): Long {
        val expected=manifest.getLong("cipher_size");val target=path(manifest.getString("object_id"))
        if(target.isFile && target.length()==expected)return 0L
        val partial = File(target.path + ".part")
        val length = if (partial.isFile) partial.length() else 0L
        val chunkBytes = 65_536L + 16
        val retained = if (length > expected) 0L else length - length % chunkBytes
        return expected - retained
    }
    fun path(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return File(File(directory, id.take(2)), id.drop(2) + ".obj")
    }

    private inner class ObjectInput(private val manifest: JSONObject, private var position: Long) : InputStream() {
        private var closed = false
        private var cached: ByteArray? = null
        private var cachedStart = 0L
        override fun read(): Int {
            val one = ByteArray(1)
            return try { if (read(one) < 0) -1 else one[0].toInt() and 255 } finally { one.fill(0) }
        }
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            check(!closed) { "Encrypted object stream is closed" }
            require(offset >= 0 && length >= 0 && offset <= target.size - length)
            if (length == 0) return 0
            val remaining = manifest.getLong("size") - position
            if (remaining == 0L) return -1
            val previous = cached
            if (previous == null || position < cachedStart || position >= cachedStart + previous.size) {
                previous?.fill(0); cached = null
                cachedStart = position / 65536 * 65536
                val count = minOf(65536L, manifest.getLong("size") - cachedStart).toInt()
                val result = SecurityCore.call(JSONObject().put("operation", "read_object_range").put("manifest", manifest)
                    .put("directory", directory.absolutePath).put("offset", cachedStart).put("length", count))
                val bytes = Base64.decode(result.getString("data"), Base64.NO_WRAP)
                if (bytes.size != count) { bytes.fill(0); error("Incorrect decrypted object length") }
                cached = bytes
            }
            val bytes = checkNotNull(cached)
            val start = (position - cachedStart).toInt()
            val count = minOf(length, bytes.size - start)
            bytes.copyInto(target, offset, start, start + count); position += count
            return count
        }
        override fun skip(count: Long): Long {
            check(!closed)
            val skipped = count.coerceIn(0, manifest.getLong("size") - position)
            position += skipped; return skipped
        }
        override fun available(): Int = (manifest.getLong("size") - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        override fun close() { closed = true; cached?.fill(0); cached = null }
    }

    companion object {
        const val MAXIMUM_SIZE = 100L * 1024 * 1024
        const val REFERENCE_PREFIX = "msgat-object://"
        fun reference(id: String): String { require(id.matches(Regex("[a-f0-9]{64}"))); return REFERENCE_PREFIX + id }
        private fun id(reference: String): String {
            require(reference.startsWith(REFERENCE_PREFIX))
            return reference.removePrefix(REFERENCE_PREFIX).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        }
        fun remember(candidate: JSONObject, db: SupportSQLiteDatabase, available: Boolean = false) {
            val manifest = SecurityCore.call(JSONObject().put("operation", "validate_object_manifest").put("manifest", candidate)).getJSONObject("manifest")
            val id = manifest.getString("object_id"); val encoded = manifest.toString()
            val previous = db.query(SimpleSQLiteQuery("SELECT manifest_json FROM media_objects WHERE object_id=?", arrayOf(id))).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            require(previous == null || previous == encoded) { "Conflicting encrypted object identity" }
            db.execSQL("INSERT INTO media_objects(object_id,manifest_json,local_state) VALUES(?,?,?) ON CONFLICT(object_id) DO UPDATE SET local_state=MAX(local_state,excluded.local_state)",
                arrayOf(id, encoded, if (available) 1 else 0))
            if(available)db.execSQL("DELETE FROM media_staging WHERE object_id=?",arrayOf(id))
        }
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun readSome(input: InputStream, buffer: ByteArray): Int {
            val count = input.read(buffer)
            if (count != 0) return count
            val single = input.read()
            if (single < 0) return -1
            buffer[0] = single.toByte(); return 1
        }
    }
}
