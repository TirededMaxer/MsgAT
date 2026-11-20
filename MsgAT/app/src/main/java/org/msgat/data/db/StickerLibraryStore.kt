// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.data.db

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.msgat.core.media.*
import java.util.UUID

internal data class StickerPack(val id: String,val name: String,val count: Int)
internal data class StickerItem(val id: String,val reference: String,val sha256: String,val mime: String,val size: Long,val width: Int,val height: Int,val frames: Int,val label: String,val favorite: Boolean,val addedAt: Long,val lastUsed: Long)
internal data class StickerSelection(val entry: StickerSourceEntry,val expectedSha256: String?=null)
internal data class StickerImportResult(val packId: String,val added: Int,val duplicates: Int)
internal data class StickerImportProgress(val phase: String,val done: Int,val total: Int)
private data class PreparedSticker(val entry: StickerSourceEntry,val info: StickerImageInfo,val mime: String,val sha: String,val needsImport: Boolean,val size: Int,var manifest: JSONObject?=null)
internal class StickerLibraryStore(private val database: AppDatabase,private val objects: EncryptedObjectStore) {
    private val gate=Any();private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);private val mutation=Mutex();private var closed=false
    private suspend fun <T> owned(block: suspend ()->T): T {
        val job=synchronized(gate){check(!closed);scope.async {mutation.withLock {block()}}}
        try {return job.await()}
        catch(cancelled: CancellationException){job.cancel();withContext(NonCancellable){job.join()};throw cancelled}
    }
    suspend fun close()=withContext(NonCancellable){synchronized(gate){closed=true};scope.coroutineContext[Job]?.cancelAndJoin()}
    suspend fun packs(): List<StickerPack> = withContext(Dispatchers.IO) {
        database.openHelper.readableDatabase.query("SELECT p.id,p.name,COUNT(m.sticker_id) FROM sticker_packs p LEFT JOIN sticker_pack_items m ON m.pack_id=p.id GROUP BY p.id ORDER BY p.created_at,p.id").use {c->buildList {while(c.moveToNext())add(StickerPack(c.getString(0),c.getString(1),c.getInt(2)))}}
    }
    suspend fun list(pack: String? = null, favorites: Boolean = false, recent: Boolean = false,
                     search: String = "", offset: Int = 0, animated: Boolean = false): List<StickerItem> = withContext(Dispatchers.IO) {
        database.openHelper.readableDatabase.query(SimpleSQLiteQuery("""
            SELECT s.id,s.object_id,s.sha256,s.mime,s.size,s.width,s.height,s.frames,s.label,s.favorite,s.added_at,s.last_used FROM sticker_items s
            WHERE (? IS NULL OR EXISTS(SELECT 1 FROM sticker_pack_items m WHERE m.pack_id=? AND m.sticker_id=s.id))
            AND (?=0 OR s.favorite=1) AND (?=0 OR s.last_used>0) AND (?='' OR instr(lower(s.label),lower(?))>0)
            AND (?=0 OR s.frames>1)
            ORDER BY CASE WHEN ?=1 THEN s.last_used ELSE s.added_at END DESC,s.id DESC LIMIT 60 OFFSET ?
        """.trimIndent(), arrayOf(pack, pack, if (favorites) 1 else 0, if (recent) 1 else 0, search, search,
            if (animated) 1 else 0, if (recent) 1 else 0, offset.coerceIn(0, 2000)))).use { c ->
            buildList {
                while (c.moveToNext()) add(StickerItem(c.getString(0), EncryptedObjectStore.reference(c.getString(1)),
                    c.getString(2), c.getString(3), c.getLong(4), c.getInt(5), c.getInt(6), c.getInt(7),
                    c.getString(8), c.getInt(9) != 0, c.getLong(10), c.getLong(11)))
            }
        }
    }
    suspend fun import(source: StickerSource,selection: List<StickerSelection>,packName: String,inspect: (ByteArray)->Unit,packId: String?=null,progress: (StickerImportProgress)->Unit={}): StickerImportResult {
        val selected=selection.toList()
        return owned {
            val name = packName.trim()
            require(packId != null || name.isNotEmpty() && name.toByteArray(Charsets.UTF_8).size <= 128) {
                "Group name is empty or too long"
            }
            require(selected.size in 1..200 && selected.map {it.entry.id}.distinct().size==selected.size)
            val prepared=mutableListOf<PreparedSticker>();val hashes=mutableSetOf<String>();var duplicates=0;var budget=0L;var committed=false
            try {
                selected.forEachIndexed {index,chosen->
                    currentCoroutineContext().ensureActive();val image=source.read(chosen.entry)
                    try {
                        check(chosen.expectedSha256==null || chosen.expectedSha256==image.sha256){"Selected sticker changed; select its source again"}
                        image.info.validate();inspect(image.bytes)
                        if(!hashes.add(image.sha256)){duplicates++;return@forEachIndexed}
                        val exists=database.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT 1 FROM sticker_items WHERE sha256=?",arrayOf(image.sha256))).use {it.moveToFirst()}
                        prepared+=PreparedSticker(chosen.entry,image.info,image.mime,image.sha256,!exists,image.bytes.size)
                        if(exists)duplicates++ else budget=Math.addExact(budget,image.bytes.size.toLong()+((image.bytes.size+65535L)/65536)*16)
                    } finally {image.clear();progress(StickerImportProgress("Scanning",index+1,selected.size))}
                }
                objects.requireFreeSpace(budget)
                prepared.forEachIndexed {index,row->
                    currentCoroutineContext().ensureActive()
                    if(row.needsImport){val image=source.read(row.entry);try {
                        check(image.sha256==row.sha){"Selected sticker changed during preparation"}
                        withContext(NonCancellable){row.manifest=objects.importBytes(image.bytes,"sticker.${image.extension}",image.mime)}
                    } finally {image.clear()}}
                    progress(StickerImportProgress("Encrypting",index+1,prepared.size))
                }
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable){database.withTransaction {
                    val db=database.openHelper.writableDatabase
                    val now=System.currentTimeMillis()
                    val existing=packId ?: text(db,"SELECT id FROM sticker_packs WHERE name=? ORDER BY created_at,id LIMIT 1",arrayOf(name))
                    val destination=existing ?: UUID.randomUUID().toString()
                    if(existing==null)db.execSQL("INSERT INTO sticker_packs(id,name,created_at) VALUES(?,?,?)",arrayOf(destination,name,now))
                    else check(exists(db,"SELECT 1 FROM sticker_packs WHERE id=?",arrayOf(destination))){"Selected sticker group was deleted"}
                    var position=number(db,"SELECT COALESCE(MAX(position),-1)+1 FROM sticker_pack_items WHERE pack_id=?",arrayOf(destination));var added=0
                    for(row in prepared){var id=text(db,"SELECT id FROM sticker_items WHERE sha256=?",arrayOf(row.sha))
                        if(id==null){val manifest=checkNotNull(row.manifest){"Duplicate sticker changed during import"}
                            check(number(db,"SELECT COUNT(*) FROM sticker_items")<2000 && number(db,"SELECT COALESCE(SUM(size),0) FROM sticker_items")+row.size<=2L*1024*1024*1024){"Sticker library is full"}
                            EncryptedObjectStore.remember(manifest,db,true);id=UUID.randomUUID().toString()
                            db.execSQL("INSERT INTO sticker_items(id,object_id,sha256,mime,size,width,height,frames,label,added_at) VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf(id,manifest.getString("object_id"),row.sha,row.mime,row.size,row.info.width,row.info.height,row.info.frames,row.entry.name.substringAfterLast('/').substringAfterLast('\\').filterNot {it.isISOControl()},now));added++
                        }
                        db.execSQL("INSERT OR IGNORE INTO sticker_pack_items(pack_id,sticker_id,position) VALUES(?,?,?)",arrayOf(destination,id,position++))
                    }
                    StickerImportResult(destination,added,duplicates)
                }.also {committed=true}}
            } finally {if(!committed)for(row in prepared)row.manifest?.let(objects::discardCopy)}
        }
    }
    suspend fun favorite(id: String,value: Boolean)=mutate("UPDATE sticker_items SET favorite=? WHERE id=?",arrayOf(if(value)1 else 0,id))
    suspend fun delete(id: String)=mutate("DELETE FROM sticker_items WHERE id=?",arrayOf(id))
    suspend fun removeFromPack(pack: String,id: String)=mutate("DELETE FROM sticker_pack_items WHERE pack_id=? AND sticker_id=?",arrayOf(pack,id))
    suspend fun deletePack(pack: String)=mutate("DELETE FROM sticker_packs WHERE id=?",arrayOf(pack))
    suspend fun renamePack(pack: String,name: String): Boolean {val value=name.trim();require(value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size<=128);return mutate("UPDATE sticker_packs SET name=? WHERE id=?",arrayOf(value,pack))}
    private suspend fun mutate(sql: String,args: Array<Any?>)=owned {database.withTransaction {val db=database.openHelper.writableDatabase;db.execSQL(sql,args);number(db,"SELECT changes()")>0}}
    companion object {
        fun source(db: SupportSQLiteDatabase,id: String): Pair<String,String>?=db.query(SimpleSQLiteQuery("SELECT o.manifest_json,s.sha256 FROM sticker_items s JOIN media_objects o ON o.object_id=s.object_id AND o.local_state=1 WHERE s.id=?",arrayOf(id))).use {if(it.moveToFirst())it.getString(0) to it.getString(1) else null}
        private fun exists(db: SupportSQLiteDatabase,sql: String,args: Array<Any?>)=db.query(SimpleSQLiteQuery(sql,args)).use {it.moveToFirst()}
        private fun number(db: SupportSQLiteDatabase,sql: String,args: Array<Any?> = emptyArray())=db.query(SimpleSQLiteQuery(sql,args)).use {if(it.moveToFirst())it.getLong(0) else 0L}
        private fun text(db: SupportSQLiteDatabase,sql: String,args: Array<Any?>)=db.query(SimpleSQLiteQuery(sql,args)).use {if(it.moveToFirst())it.getString(0) else null}
    }
}
