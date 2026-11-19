// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.data.db

import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*

@Entity(tableName="conversation_preferences",foreignKeys=[ForeignKey(entity=Conversation::class,parentColumns=["uuid"],childColumns=["peer"],onDelete=ForeignKey.CASCADE)],indices=[Index(value=["archived","pin_order","peer"],name="conversation_preferences_order")])
data class ConversationPreference(@PrimaryKey val peer:String,@ColumnInfo(name="pin_order",defaultValue="0")val pinOrder:Long=0,@ColumnInfo(defaultValue="0")val archived:Boolean=false,@ColumnInfo(name="mute_until",defaultValue="0")val muteUntil:Long=0,@ColumnInfo(name="manual_unread",defaultValue="0")val manualUnread:Boolean=false)
@Entity(tableName="conversation_folders")
data class ConversationFolder(@PrimaryKey val id:String,val name:String,@ColumnInfo(name="sort_order",defaultValue="0")val sortOrder:Long=0,@ColumnInfo(name="include_all",defaultValue="0")val includeAll:Boolean=false,@ColumnInfo(name="unread_only",defaultValue="0")val unreadOnly:Boolean=false,@ColumnInfo(name="exclude_muted",defaultValue="0")val excludeMuted:Boolean=false,@ColumnInfo(name="include_archived",defaultValue="0")val includeArchived:Boolean=false)
@Entity(tableName="conversation_folder_members",primaryKeys=["folder_id","peer"],foreignKeys=[ForeignKey(entity=ConversationFolder::class,parentColumns=["id"],childColumns=["folder_id"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=Conversation::class,parentColumns=["uuid"],childColumns=["peer"],onDelete=ForeignKey.CASCADE)],indices=[Index(value=["peer"],name="conversation_folder_members_peer")])
data class ConversationFolderMember(@ColumnInfo(name="folder_id")val folderId:String,val peer:String)
@Entity(
    tableName = "conversation_folder_pins",
    primaryKeys = ["folder_id", "peer"],
    foreignKeys = [
        ForeignKey(entity = ConversationFolder::class, parentColumns = ["id"], childColumns = ["folder_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Conversation::class, parentColumns = ["uuid"], childColumns = ["peer"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["peer"], name = "conversation_folder_pins_peer")]
)
data class ConversationFolderPin(@ColumnInfo(name = "folder_id") val folderId: String, val peer: String, @ColumnInfo(name = "pin_order") val pinOrder: Long)
@Entity(tableName="conversation_drafts",foreignKeys=[ForeignKey(entity=Conversation::class,parentColumns=["uuid"],childColumns=["peer"],onDelete=ForeignKey.CASCADE)])
data class DraftIndexRow(@PrimaryKey val peer:String,val token:String,@ColumnInfo(defaultValue="0")val revision:Long=0,@ColumnInfo(defaultValue="''")val text:String="",@ColumnInfo(name="reply_id")val replyId:String?=null)
@Entity(tableName="conversation_draft_activity",foreignKeys=[ForeignKey(entity=Conversation::class,parentColumns=["uuid"],childColumns=["peer"],onDelete=ForeignKey.CASCADE)])
data class DraftActivityRow(@PrimaryKey val peer:String,@ColumnInfo(name="last_at")val lastAt:Long)

class ConversationIndexStore(
    private val database: AppDatabase,
    private val onNotificationMuted: (String) -> Unit = {}
){
    private val gate=Any();private var closed=false
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private suspend fun <T> owned(block:(SupportSQLiteDatabase)->T):T {
        val job=synchronized(gate){check(!closed){"Conversation settings are closed"};scope.async{database.withTransaction{block(database.openHelper.writableDatabase)}.also{database.invalidationTracker.refreshVersionsAsync()}}}
        return withContext(NonCancellable){job.await()}
    }
    suspend fun close(){synchronized(gate){closed=true};scope.coroutineContext[Job]?.cancelAndJoin()}
    internal suspend fun setArchived(peer: String, expected: Boolean, archived: Boolean): Boolean = owned { db ->
        db.execSQL("INSERT OR IGNORE INTO conversation_preferences(peer) SELECT uuid FROM conversations WHERE uuid=?", arrayOf(peer))
        db.execSQL("UPDATE conversation_preferences SET archived=? WHERE peer=? AND archived=?",
            arrayOf(if (archived) 1 else 0, peer, if (expected) 1 else 0))
        db.query("SELECT changes()").use { it.moveToFirst() && it.getInt(0) == 1 }
    }
    suspend fun change(peer: String, action: String, until: Long? = null, folderId: String? = null) {
        val token = owned { db ->
            check(db.query(SimpleSQLiteQuery("SELECT 1 FROM conversations WHERE uuid=?",arrayOf(peer))).use{it.moveToFirst()}){"Conversation is unavailable"}
            if (folderId != null) {
                changeFolderPin(db, folderId, peer, action)
                return@owned null
            }
            db.execSQL("INSERT OR IGNORE INTO conversation_preferences(peer) VALUES(?)",arrayOf(peer))
            when(action){
                "pin"->db.execSQL("UPDATE conversation_preferences SET pin_order=(SELECT COALESCE(MAX(pin_order),0)+1 FROM conversation_preferences) WHERE peer=?",arrayOf(peer))
                "unpin"->db.execSQL("UPDATE conversation_preferences SET pin_order=0 WHERE peer=?",arrayOf(peer))
                "archive","unarchive"->db.execSQL("UPDATE conversation_preferences SET archived=? WHERE peer=?",arrayOf(if(action=="archive")1 else 0,peer))
                "unread","clear_unread"->db.execSQL("UPDATE conversation_preferences SET manual_unread=? WHERE peer=?",arrayOf(if(action=="unread")1 else 0,peer))
                "mute","unmute"->{val value=if(action=="unmute")0L else until?:PERMANENT_MUTE;require(value==0L || value>System.currentTimeMillis() && value<=PERMANENT_MUTE);db.execSQL("UPDATE conversation_preferences SET mute_until=? WHERE peer=?",arrayOf(value,peer))}
                else->error("Unsupported conversation action")
            }
            if (action == "mute" && isMuted(db, peer, System.currentTimeMillis()))
                MessageNotificationStore.tokenForConversation(db, peer)
            else null
        }
        if (token != null) onNotificationMuted(token)
    }
    private fun changeFolderPin(db: SupportSQLiteDatabase, folderId: String, peer: String, action: String) {
        when (action) {
            "pin" -> db.execSQL(
                """INSERT INTO conversation_folder_pins(folder_id, peer, pin_order)
                   SELECT ?, ?, COALESCE(MAX(pin_order), 0) + 1 FROM conversation_folder_pins WHERE folder_id = ?
                   ON CONFLICT(folder_id, peer) DO NOTHING""",
                arrayOf(folderId, peer, folderId)
            )
            "unpin" -> db.execSQL("DELETE FROM conversation_folder_pins WHERE folder_id = ? AND peer = ?", arrayOf(folderId, peer))
            else -> error("Unsupported folder pin action")
        }
    }

    suspend fun movePin(scope: String, peer: String, target: String, before: Boolean): Boolean = owned { db ->
        val custom = scope !in listOf("all", "archive", "unread")
        val query = if (custom) SimpleSQLiteQuery(
            """
            SELECT fp.peer, fp.pin_order
            FROM conversation_folder_pins fp
            JOIN conversation_folders f ON f.id = fp.folder_id
            JOIN conversation_summaries s ON s.uuid = fp.peer
            WHERE fp.folder_id = ?
              AND (f.include_all = 1 OR EXISTS (
                  SELECT 1 FROM conversation_folder_members fm WHERE fm.folder_id = f.id AND fm.peer = fp.peer
              ))
              AND (f.include_archived = 1 OR s.archived = 0)
              AND (f.unread_only = 0 OR s.unreadCount > 0 OR s.manualUnread = 1)
              AND (f.exclude_muted = 0 OR s.muteUntil <= ?)
            ORDER BY fp.pin_order DESC, fp.peer
            """.trimIndent(), arrayOf(scope, System.currentTimeMillis())
        ) else SimpleSQLiteQuery(
            """
            SELECT uuid, pinOrder FROM conversation_summaries
            WHERE pinOrder > 0 AND archived = ?
              AND (? = 0 OR unreadCount > 0 OR manualUnread = 1)
            ORDER BY pinOrder DESC, uuid
            """.trimIndent(), arrayOf(if (scope == "archive") 1 else 0, if (scope == "unread") 1 else 0)
        )
        val pins = db.query(query).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getLong(1)) }
        }
        val start = pins.indexOfFirst { it.first == peer }
        if (start < 0 || peer == target) return@owned false
        val order = pins.map { it.first }.toMutableList()
        order.removeAt(start)
        val reference = order.indexOf(target)
        if (reference < 0) return@owned false
        val destination = reference + if (before) 0 else 1
        order.add(destination, peer)
        for (index in minOf(start, destination)..maxOf(start, destination)) {
            if (custom) db.execSQL(
                "UPDATE conversation_folder_pins SET pin_order = ? WHERE folder_id = ? AND peer = ?",
                arrayOf(pins[index].second, scope, order[index])
            )
            else db.execSQL(
                "UPDATE conversation_preferences SET pin_order = ? WHERE peer = ?", arrayOf(pins[index].second, order[index])
            )
        }
        true
    }

    suspend fun save(folder:ConversationFolder,peers:Collection<String>)=owned{db->
        val id=java.util.UUID.fromString(folder.id).toString();val name=folder.name.trim();require(name.isNotEmpty() && !name.contains('\u0000') && name.toByteArray().size<=128)
        val members=peers.distinct();require(members.size<=10000)
        check(db.query(SimpleSQLiteQuery("SELECT 1 FROM conversation_folders WHERE id=? UNION ALL SELECT 1 WHERE (SELECT COUNT(*) FROM conversation_folders)<20",arrayOf(id))).use{it.moveToFirst()}){"At most 20 folders are supported"}
        db.execSQL("INSERT INTO conversation_folders(id,name,sort_order,include_all,unread_only,exclude_muted,include_archived) VALUES(?,?,(SELECT COALESCE(MAX(sort_order),0)+1 FROM conversation_folders),?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,include_all=excluded.include_all,unread_only=excluded.unread_only,exclude_muted=excluded.exclude_muted,include_archived=excluded.include_archived",arrayOf(id,name,folder.includeAll,folder.unreadOnly,folder.excludeMuted,folder.includeArchived))
        db.execSQL("DELETE FROM conversation_folder_members WHERE folder_id=?",arrayOf(id))
        members.forEach{db.execSQL("INSERT INTO conversation_folder_members(folder_id,peer) VALUES(?,?)",arrayOf(id,it))}
    }
    suspend fun delete(id:String)=owned{it.execSQL("DELETE FROM conversation_folders WHERE id=?",arrayOf(id))}
    suspend fun move(id: String, targetId: String, before: Boolean): Boolean = owned { db ->
        val ids = db.query("SELECT id FROM conversation_folders ORDER BY sort_order, id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        if (id == targetId || id !in ids || targetId !in ids) return@owned false
        val reordered = ids.toMutableList()
        reordered.remove(id)
        reordered.add(reordered.indexOf(targetId) + if (before) 0 else 1, id)
        for ((index, folderId) in reordered.withIndex()) {
            db.execSQL("UPDATE conversation_folders SET sort_order = ? WHERE id = ?", arrayOf(index + 1, folderId))
        }
        true
    }
    companion object {
        internal const val PERMANENT_MUTE = 253402214399999L
        fun isMuted(db:SupportSQLiteDatabase,peer:String,now:Long)=db.query(SimpleSQLiteQuery("SELECT 1 FROM conversation_preferences WHERE peer=? AND mute_until>?",arrayOf(peer,now))).use{it.moveToFirst()}
        val schema=listOf(
            "CREATE TABLE IF NOT EXISTS conversation_drafts (peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,token TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,text TEXT NOT NULL DEFAULT '',reply_id TEXT)" ,
            "CREATE TABLE IF NOT EXISTS conversation_preferences(peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,pin_order INTEGER NOT NULL DEFAULT 0 CHECK(pin_order>=0),archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)),mute_until INTEGER NOT NULL DEFAULT 0 CHECK(mute_until>=0),manual_unread INTEGER NOT NULL DEFAULT 0 CHECK(manual_unread IN (0,1)))" ,
            "CREATE INDEX IF NOT EXISTS conversation_preferences_order ON conversation_preferences(archived,pin_order,peer)" ,
            "CREATE TABLE IF NOT EXISTS conversation_folders(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,sort_order INTEGER NOT NULL DEFAULT 0,include_all INTEGER NOT NULL DEFAULT 0 CHECK(include_all IN (0,1)),unread_only INTEGER NOT NULL DEFAULT 0 CHECK(unread_only IN (0,1)),exclude_muted INTEGER NOT NULL DEFAULT 0 CHECK(exclude_muted IN (0,1)),include_archived INTEGER NOT NULL DEFAULT 0 CHECK(include_archived IN (0,1)))" ,
            "CREATE TABLE IF NOT EXISTS conversation_folder_members(folder_id TEXT NOT NULL REFERENCES conversation_folders(id) ON DELETE CASCADE,peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,PRIMARY KEY(folder_id,peer))" ,
            "CREATE INDEX IF NOT EXISTS conversation_folder_members_peer ON conversation_folder_members(peer)" ,
            "CREATE TABLE IF NOT EXISTS conversation_folder_pins(folder_id TEXT NOT NULL REFERENCES conversation_folders(id) ON DELETE CASCADE,peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,pin_order INTEGER NOT NULL CHECK(pin_order>0),PRIMARY KEY(folder_id,peer))" ,
            "CREATE INDEX IF NOT EXISTS conversation_folder_pins_peer ON conversation_folder_pins(peer)" ,
            "CREATE TABLE IF NOT EXISTS conversation_draft_activity(peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,last_at INTEGER NOT NULL)" ,
            "CREATE INDEX IF NOT EXISTS index_messages_conversationUuid_timestamp_messageId ON messages(conversationUuid,timestamp,messageId)" ,
            "CREATE INDEX IF NOT EXISTS index_messages_conversationUuid_isMine_isRead_timestamp_messageId ON messages(conversationUuid,isMine,isRead,timestamp,messageId)" ,
            "CREATE TRIGGER IF NOT EXISTS conversation_unarchive_message AFTER INSERT ON messages WHEN NEW.isMine=0 AND NEW.isRead=0 AND NEW.isRevoked=0 BEGIN UPDATE conversation_preferences SET archived=0 WHERE peer=NEW.conversationUuid AND archived=1 AND mute_until<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)); END" ,
            "CREATE TRIGGER IF NOT EXISTS conversation_clear_marker AFTER UPDATE OF isRead ON messages WHEN NEW.isRead=1 AND OLD.isRead=0 BEGIN UPDATE conversation_preferences SET manual_unread=0 WHERE peer=NEW.conversationUuid AND manual_unread=1; END" ,
            "CREATE TRIGGER IF NOT EXISTS conversation_draft_insert AFTER INSERT ON conversation_drafts BEGIN INSERT INTO conversation_draft_activity(peer,last_at) SELECT NEW.peer,(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)) WHERE NEW.text!='' OR NEW.reply_id IS NOT NULL ON CONFLICT(peer) DO UPDATE SET last_at=excluded.last_at; DELETE FROM conversation_draft_activity WHERE peer=NEW.peer AND NEW.text='' AND NEW.reply_id IS NULL; END" ,
            "CREATE TRIGGER IF NOT EXISTS conversation_draft_update AFTER UPDATE ON conversation_drafts BEGIN INSERT INTO conversation_draft_activity(peer,last_at) SELECT NEW.peer,(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)) WHERE NEW.text!='' OR NEW.reply_id IS NOT NULL ON CONFLICT(peer) DO UPDATE SET last_at=excluded.last_at; DELETE FROM conversation_draft_activity WHERE peer=NEW.peer AND NEW.text='' AND NEW.reply_id IS NULL; END" ,
            "CREATE TRIGGER IF NOT EXISTS conversation_draft_delete AFTER DELETE ON conversation_drafts BEGIN DELETE FROM conversation_draft_activity WHERE peer=OLD.peer; END" ,
            "INSERT OR IGNORE INTO conversation_draft_activity(peer,last_at) SELECT peer,(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)) FROM conversation_drafts WHERE text!='' OR reply_id IS NOT NULL" ,
            "DROP VIEW IF EXISTS conversation_summaries",
            "CREATE VIEW IF NOT EXISTS conversation_summaries AS SELECT c.uuid,c.alias,c.avatarPath,c.peerPublicKey,\n(SELECT COUNT(*) FROM messages u WHERE u.conversationUuid=c.uuid AND u.isMine=0 AND u.isRead=0 AND u.isRevoked=0 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=u.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)))) AS unreadCount,\nCOALESCE(p.pin_order,0) AS pinOrder,COALESCE(p.archived,0) AS archived,COALESCE(p.mute_until,0) AS muteUntil,COALESCE(p.manual_unread,0) AS manualUnread,\nCOALESCE((SELECT CASE WHEN m.isRevoked=1 THEN '[Deleted message]' WHEN m.ext='txt' THEN substr(m.content,1,240) WHEN m.ext='contact' THEN 'Contact: '||substr(m.content,1,230) WHEN m.ext IN ('location','live_location') THEN substr(m.content,1,240) WHEN m.ext='attachment' THEN COALESCE((SELECT NULLIF(substr(a.caption,1,240),'') FROM message_attachments a WHERE a.message_id=m.messageId),(SELECT json_extract(o.manifest_json,'$.filename') FROM message_attachments a JOIN media_objects o ON o.object_id=a.object_id WHERE a.message_id=m.messageId),'[Attachment]') ELSE '[Call]' END FROM messages m WHERE m.conversationUuid=c.uuid AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 1),'') AS summary,\nCOALESCE(substr(d.text,1,240),'') AS draftText,\nCOALESCE((SELECT m.timestamp FROM messages m WHERE m.conversationUuid=c.uuid AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 1),0) AS lastAt,\nCOALESCE((SELECT m.messageId FROM messages m WHERE m.conversationUuid=c.uuid AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 1),'') AS lastID,\nCOALESCE((SELECT u.messageId FROM messages u WHERE u.conversationUuid=c.uuid AND u.isMine=0 AND u.isRead=0 AND u.isRevoked=0 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=u.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY u.timestamp,u.messageId LIMIT 1),'') AS firstUnreadID,\nCOALESCE((SELECT group_concat(f.folder_id,',') FROM conversation_folder_members f WHERE f.peer=c.uuid),'') AS folderIDs,\nCASE WHEN COALESCE(d.text,'')!='' OR d.reply_id IS NOT NULL THEN 1 ELSE 0 END AS hasDraft,\nMAX(COALESCE(a.last_at,0),COALESCE((SELECT m.timestamp FROM messages m WHERE m.conversationUuid=c.uuid AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 1),0)) AS activityAt,\nCOALESCE((SELECT json_group_object(fp.folder_id,fp.pin_order) FROM conversation_folder_pins fp WHERE fp.peer=c.uuid),'{}') AS folderPinOrders\nFROM conversations c LEFT JOIN conversation_preferences p ON p.peer=c.uuid LEFT JOIN conversation_drafts d ON d.peer=c.uuid LEFT JOIN conversation_draft_activity a ON a.peer=c.uuid" ,
        )
    }
}
