// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.msgat.core.media.EncryptedObjectStore
import org.msgat.core.network.AttachmentQueue

data class WebPreviewSnapshot(val url: String,val title: String,val summary: String,val site: String,val imageReference: String?,
                              val aboveText: Boolean, val compactImage: Boolean)
internal object WebPreviewStore {
    fun enabled(db: SupportSQLiteDatabase)=db.query("SELECT 1 FROM web_preview_preferences WHERE id=1 AND enabled=0").use {!it.moveToFirst()}
    fun setEnabled(db: SupportSQLiteDatabase,value: Boolean) {db.execSQL("INSERT INTO web_preview_preferences(id,enabled) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET enabled=excluded.enabled",arrayOf(if(value)1 else 0))}
    fun read(db: SupportSQLiteDatabase, id: String, saved: Boolean = false): WebPreviewSnapshot? =
        db.query(SimpleSQLiteQuery("""
            SELECT url,title,summary,site,image_object,above_text,compact_image
            FROM message_previews WHERE owner_kind=? AND owner_id=?
        """.trimIndent(), arrayOf(if (saved) "saved" else "message", id))).use { cursor ->
            if (!cursor.moveToFirst()) null else WebPreviewSnapshot(cursor.getString(0), cursor.getString(1),
                cursor.getString(2), cursor.getString(3), cursor.getString(4)?.let { EncryptedObjectStore.reference(it) },
                cursor.getInt(5) != 0, cursor.getInt(6) != 0)
        }
    fun body(db: SupportSQLiteDatabase,id: String,saved: Boolean=false): JSONObject? {
        val row=read(db,id,saved)?:return null
        val image=row.imageReference?.let {ref ->db.query(SimpleSQLiteQuery("SELECT manifest_json FROM media_objects WHERE object_id=?",arrayOf(ref.removePrefix(EncryptedObjectStore.REFERENCE_PREFIX)))).use {check(it.moveToFirst());JSONObject(it.getString(0))}}
        return JSONObject().put("url",row.url).put("title",row.title).put("summary",row.summary).put("site",row.site).put("image",image?:JSONObject.NULL)
            .apply {
                if (row.aboveText) put("above_text", true)
                if (row.compactImage && image != null) put("compact_image", true)
            }
    }
    fun record(db: SupportSQLiteDatabase,id: String,preview: JSONObject?,text: String,peer: String,outgoing: Boolean,created: Long,saved: Boolean=false) {
        if(preview==null)return
        if(!saved && !db.query(SimpleSQLiteQuery("SELECT 1 FROM messages WHERE messageId=? AND ext='txt' AND isRevoked=0 AND content=?",arrayOf(id,text))).use {it.moveToFirst()})return
        val image=preview.optJSONObject("image");val imageID=image?.getString("object_id")
        if(image!=null)EncryptedObjectStore.remember(image,db,outgoing)
        db.execSQL("""
            INSERT INTO message_previews(owner_kind,owner_id,url,title,summary,site,image_object,above_text,compact_image)
            VALUES(?,?,?,?,?,?,?,?,?)
        """.trimIndent(), arrayOf(if (saved) "saved" else "message", id, preview.getString("url"), preview.getString("title"),
            preview.getString("summary"), preview.getString("site"), imageID, if (preview.optBoolean("above_text")) 1 else 0,
            if (imageID != null && preview.optBoolean("compact_image")) 1 else 0))
        if(!saved && imageID!=null && (outgoing || !db.query(SimpleSQLiteQuery("SELECT 1 FROM media_objects WHERE object_id=? AND local_state=1",arrayOf(imageID))).use {it.moveToFirst()}))AttachmentQueue.record(db,id,imageID,outgoing,peer,created)
    }
}
