// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.data.db

import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName="message_pin_clocks",primaryKeys=["peer","message_id","author","actor"],foreignKeys=[ForeignKey(entity=Conversation::class,parentColumns=["uuid"],childColumns=["peer"],onDelete=ForeignKey.CASCADE)],indices=[Index(value=["message_id","peer"],name="message_pin_target")])
data class MessagePinClock(val peer:String,@ColumnInfo(name="message_id")val messageId:String,val author:String,val actor:String,val sequence:Long,val addition:Long,val removed:Long)
data class PinnedMessageItem(val id:String,val timestamp:Long,val kind:String,val summary:String)
data class PinnedMessagePage(val items:List<PinnedMessageItem>,val hasMore:Boolean)

object MessagePinsStore {
    val schema=listOf(
        "CREATE TABLE IF NOT EXISTS message_pin_clocks(peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,message_id TEXT NOT NULL,author TEXT NOT NULL,actor TEXT NOT NULL,sequence INTEGER NOT NULL,addition INTEGER NOT NULL,removed INTEGER NOT NULL,PRIMARY KEY(peer,message_id,author,actor))",
        "CREATE INDEX IF NOT EXISTS message_pin_target ON message_pin_clocks(message_id,peer)",
        "CREATE TRIGGER IF NOT EXISTS message_pin_delete AFTER DELETE ON messages BEGIN DELETE FROM message_pin_clocks WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId; END",
        "CREATE TRIGGER IF NOT EXISTS message_pin_revoke AFTER UPDATE OF isRevoked ON messages WHEN NEW.isRevoked=1 BEGIN DELETE FROM message_pin_clocks WHERE peer=NEW.conversationUuid AND message_id=NEW.messageId; END"
    )
    private fun has(db:SupportSQLiteDatabase,sql:String,vararg args:Any)=db.query(SimpleSQLiteQuery(sql,args)).use{it.moveToFirst()}
    private fun scalar(db:SupportSQLiteDatabase,sql:String,vararg args:Any):Long=db.query(SimpleSQLiteQuery(sql,args)).use{if(it.moveToFirst())it.getLong(0)else 0L}
    fun body(db:SupportSQLiteDatabase,peer:String,local:String,target:String,pinned:Boolean,notify:Boolean=false):JSONObject {
        val author = db.query(SimpleSQLiteQuery("""
            SELECT CASE WHEN isMine=1 THEN ? ELSE conversationUuid END
            FROM messages m WHERE messageId=? AND conversationUuid=? AND isRevoked=0
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?4)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?4-r.duration_ms)
            """, arrayOf(local, target, peer, System.currentTimeMillis()))).use {
            check(it.moveToFirst()) { "This message is no longer available" }
            it.getString(0)
        }
        val next=scalar(db,"SELECT sequence FROM message_pin_clocks WHERE peer=? AND message_id=? AND author=? AND actor=?",peer,target,author,local)+1
        check(next<=0xffffffffL){"The pin revision limit was reached"}
        val observed=JSONArray()
        if(!pinned)db.query(SimpleSQLiteQuery("SELECT actor,addition FROM message_pin_clocks WHERE peer=? AND message_id=? AND author=? AND addition>removed ORDER BY actor",arrayOf(peer,target,author))).use{c->while(c.moveToNext())observed.put(JSONObject().put("actor",c.getString(0)).put("sequence",c.getLong(1)))}
        return JSONObject().put("message_id",target).put("author",author).put("sequence",next).put("pinned",pinned).put("observed",observed).put("notify",pinned && notify)
    }
    fun apply(db:SupportSQLiteDatabase,peer:String,local:String,sender:String,body:JSONObject,onNotify:((String)->Unit)?=null):Boolean {
        val target=body.getString("message_id");val author=body.getString("author");val sequence=body.getLong("sequence");val pinned=body.getBoolean("pinned");val observed=body.getJSONArray("observed")
        if(sender !in listOf(local,peer) || author !in listOf(local,peer) || sequence !in 1..0xffffffffL || observed.length()>2)return false
        if(!has(db,"SELECT 1 FROM conversations WHERE uuid=?",peer))return false
        val now = System.currentTimeMillis()
        if (has(db, "SELECT 1 FROM message_expiration WHERE message_id=? AND expires_at<=?", target, now)) return false
        val known = db.query(SimpleSQLiteQuery("""
            SELECT m.conversationUuid,m.isMine,m.isRevoked OR EXISTS(
                SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?-r.duration_ms)
            FROM messages m WHERE m.messageId=?
            """, arrayOf(now, target))).use { c ->
            if (c.moveToFirst()) Triple(c.getString(0), c.getInt(1) != 0, c.getInt(2) != 0) else null
        }
        if(known!=null && (known.first!=peer || known.third || author!=(if(known.second)local else peer)))return false
        if(known==null && !has(db,"SELECT 1 FROM message_pin_clocks WHERE peer=? AND message_id=? AND author=? AND actor=?",peer,target,author,sender)){
            // Only unknown targets consume the bounded reordering budget; known history remains unrestricted.
            if(scalar(db,"SELECT COUNT(*) FROM message_pin_clocks p WHERE p.peer=? AND NOT EXISTS(SELECT 1 FROM messages m WHERE m.messageId=p.message_id AND m.conversationUuid=p.peer)",peer)>=256L ||
                scalar(db,"SELECT COUNT(*) FROM message_pin_clocks p WHERE NOT EXISTS(SELECT 1 FROM messages m WHERE m.messageId=p.message_id AND m.conversationUuid=p.peer)")>=8192L)return false
        }
        val notify = onNotify != null && known != null && pinned && body.optBoolean("notify") &&
            sequence > scalar(db,"SELECT sequence FROM message_pin_clocks WHERE peer=? AND message_id=? AND author=? AND actor=?",peer,target,author,sender)
        // A participant can advance only its own sequence. Older adds cannot revive a later unpin.
        db.execSQL("""INSERT INTO message_pin_clocks(peer,message_id,author,actor,sequence,addition,removed) VALUES(?,?,?,?,?,?,0)
            ON CONFLICT(peer,message_id,author,actor) DO UPDATE SET
            addition=CASE WHEN excluded.sequence>sequence THEN excluded.addition ELSE addition END,sequence=MAX(sequence,excluded.sequence)""",arrayOf(peer,target,author,sender,sequence,if(pinned)sequence else 0L))
        for(i in 0 until observed.length()){
            val clock=observed.getJSONObject(i);val actor=clock.getString("actor");val removed=clock.getLong("sequence")
            if(actor !in listOf(local,peer))continue
            // Clamp to already-authenticated history: a peer cannot erase future local pins.
            db.execSQL("UPDATE message_pin_clocks SET removed=MAX(removed,MIN(sequence,?)) WHERE peer=? AND message_id=? AND author=? AND actor=?",arrayOf(removed,peer,target,author,actor))
        }
        if (notify) onNotify?.invoke(target)
        return true
    }
    fun list(db:SupportSQLiteDatabase,peer:String,local:String,offset:Int=0):PinnedMessagePage {
        require(offset>=0)
        val rows=db.query(query(peer,local,offset)).use{c->buildList{while(c.moveToNext())add(PinnedMessageItem(c.getString(0),c.getLong(1),c.getString(2),c.getString(3)))}}
        return PinnedMessagePage(rows.take(50),rows.size>50)
    }
    fun query(peer: String, local: String, offset: Int = 0, limit: Int = 51, through: PinnedMessageItem? = null): SimpleSQLiteQuery {
        val values = mutableListOf<Any?>(peer, local)
        val anchorOrder = if (through == null) "" else {
            values.add(through.timestamp)
            values.add(through.id)
            "CASE WHEN (m.timestamp,m.messageId)<=(?,?) THEN 0 ELSE 1 END,"
        }
        values.add(limit)
        values.add(offset)
        return SimpleSQLiteQuery("""
            WITH active_pins AS (
                SELECT DISTINCT message_id,author FROM message_pin_clocks
                WHERE peer=?1 AND addition>removed
            )
            SELECT m.messageId AS id,m.timestamp,m.ext AS kind,
            CASE WHEN m.ext='txt' THEN substr(m.content,1,240) WHEN m.ext='contact' THEN 'Contact: '||substr(m.content,1,230) WHEN m.ext IN ('location','live_location') THEN substr(m.content,1,240) WHEN m.ext='attachment' THEN COALESCE(NULLIF(substr(a.caption,1,240),''),json_extract(o.manifest_json,'$.filename'),'[Attachment]') ELSE '[Call]' END AS summary
            FROM active_pins p CROSS JOIN messages m ON m.messageId=p.message_id
            LEFT JOIN message_attachments a ON a.message_id=m.messageId
            LEFT JOIN media_objects o ON o.object_id=a.object_id
            WHERE m.conversationUuid=?1 AND m.isRevoked=0
              AND p.author=CASE WHEN m.isMine=1 THEN ?2 ELSE m.conversationUuid END
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId
                AND e.expires_at<=CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=
                    CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)-r.duration_ms)
            ORDER BY ${anchorOrder}m.timestamp DESC,m.messageId DESC LIMIT ? OFFSET ?""", values.toTypedArray())
    }
}
