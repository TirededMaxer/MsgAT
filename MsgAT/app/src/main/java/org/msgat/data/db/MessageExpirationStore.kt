// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

data class MessageRetentionSettings(val outgoing: Long, val local: Long) {
    internal fun shortensLocalHistory(previous: MessageRetentionSettings): Boolean =
        local > 0 && (previous.local == 0L || local < previous.local)
}

internal object MessageExpirationStore {
    val choices = listOf(0L, 60_000L, 3_600_000L, 86_400_000L, 604_800_000L, 2_592_000_000L)
    val retentionChoices = listOf(0L, 86_400_000L, 604_800_000L, 2_592_000_000L, 7_776_000_000L)
    private val retentionKinds = setOf("chat.text", "chat.attachment", "chat.contact", "chat.location", "chat.location.start")

    data class Removed(val next: Long, val messages: Map<String, String>, val notificationTokens: List<String>)

    val schema = listOf(
        """CREATE TABLE IF NOT EXISTS conversation_expiration(
            peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,
            duration_ms INTEGER NOT NULL CHECK(duration_ms IN (60000,3600000,86400000,604800000,2592000000)))""",
        """CREATE TABLE IF NOT EXISTS conversation_retention(
            peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,
            duration_ms INTEGER NOT NULL CHECK(duration_ms IN (86400000,604800000,2592000000,7776000000)))""",
        """CREATE TABLE IF NOT EXISTS message_expiration(
            message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(messageId) ON DELETE CASCADE,
            expires_at INTEGER NOT NULL CHECK(expires_at>0))""",
        "CREATE INDEX IF NOT EXISTS message_expiration_due ON message_expiration(expires_at,message_id)",
        """CREATE TRIGGER IF NOT EXISTS message_expiration_outgoing AFTER INSERT ON messages
            WHEN NEW.isMine=1 BEGIN
            INSERT INTO message_expiration(message_id,expires_at)
            SELECT NEW.messageId,NEW.timestamp+json_extract(p.body_json,'$.expire_after_ms')
            FROM protocol_pending_events p WHERE p.event_id=NEW.messageId
              AND p.recipient=NEW.conversationUuid AND json_type(p.body_json,'$.expire_after_ms')='integer';
            END""",
        """CREATE TRIGGER IF NOT EXISTS message_expiration_cleanup BEFORE DELETE ON messages
            WHEN EXISTS(SELECT 1 FROM message_expiration WHERE message_id=OLD.messageId) BEGIN
            DELETE FROM protocol_outbox WHERE recipient=OLD.conversationUuid AND event_id IN (
                SELECT OLD.messageId WHERE OLD.isMine=1
                UNION ALL SELECT event_id FROM send_related_events WHERE message_id=OLD.messageId
                UNION ALL SELECT event_id FROM protocol_receipts WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId);
            DELETE FROM protocol_pending_events WHERE recipient=OLD.conversationUuid AND event_id IN (
                SELECT OLD.messageId WHERE OLD.isMine=1
                UNION ALL SELECT event_id FROM send_related_events WHERE message_id=OLD.messageId
                UNION ALL SELECT event_id FROM protocol_receipts WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId);
            DELETE FROM protocol_receipts WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId;
            DELETE FROM read_receipt_suppression WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId;
            DELETE FROM message_changes WHERE conversation_uuid=OLD.conversationUuid AND message_id=OLD.messageId;
            UPDATE messages SET replyToContent=NULL WHERE conversationUuid=OLD.conversationUuid AND replyToId=OLD.messageId;
            END"""
    )

    fun duration(db: SupportSQLiteDatabase, peer: String): Long =
        db.query(SimpleSQLiteQuery("SELECT duration_ms FROM conversation_expiration WHERE peer=?", arrayOf(peer))).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    fun settings(db: SupportSQLiteDatabase, peer: String) = MessageRetentionSettings(
        duration(db, peer), localDuration(db, peer))

    fun setSettings(db: SupportSQLiteDatabase, peer: String, settings: MessageRetentionSettings) {
        val (duration, local) = settings
        require(duration in choices)
        require(local in retentionChoices)
        if (duration == 0L) db.execSQL("DELETE FROM conversation_expiration WHERE peer=?", arrayOf(peer))
        else db.execSQL("""INSERT INTO conversation_expiration(peer,duration_ms) VALUES(?,?)
            ON CONFLICT(peer) DO UPDATE SET duration_ms=excluded.duration_ms""", arrayOf(peer, duration))
        if (local == 0L) db.execSQL("DELETE FROM conversation_retention WHERE peer=?", arrayOf(peer))
        else db.execSQL("""INSERT INTO conversation_retention(peer,duration_ms) VALUES(?,?)
            ON CONFLICT(peer) DO UPDATE SET duration_ms=excluded.duration_ms""", arrayOf(peer, local))
    }

    private fun localDuration(db: SupportSQLiteDatabase, peer: String): Long =
        db.query(SimpleSQLiteQuery("SELECT duration_ms FROM conversation_retention WHERE peer=?", arrayOf(peer))).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    fun discardIncoming(db: SupportSQLiteDatabase, peer: String, kind: String, created: Long, now: Long): Boolean {
        if (kind !in retentionKinds) return false
        val local = localDuration(db, peer)
        return local > 0 && created <= now - local
    }

    fun markDueRetention(db: SupportSQLiteDatabase, now: Long, limit: Int = 64): Int {
        db.execSQL("""
            INSERT INTO message_expiration(message_id,expires_at)
            SELECT m.messageId,?1 FROM conversation_retention r
            JOIN messages m ON m.conversationUuid=r.peer AND m.timestamp<=?1-r.duration_ms
            WHERE m.isMine=0 OR m.status>=2
            ORDER BY m.timestamp,m.messageId LIMIT ?2
            ON CONFLICT(message_id) DO UPDATE SET expires_at=MIN(message_expiration.expires_at,excluded.expires_at)
            """, arrayOf(now, limit))
        return db.query("SELECT changes()").use { it.moveToFirst(); it.getInt(0) }
    }

    fun incomingDeadline(body: JSONObject, created: Long, now: Long): Long? =
        if (body.has("expire_after_ms")) minOf(created, now) + body.getLong("expire_after_ms") else null

    fun record(db: SupportSQLiteDatabase, id: String, deadline: Long) {
        db.execSQL("INSERT INTO message_expiration(message_id,expires_at) VALUES(?,?)", arrayOf(id, deadline))
    }

    fun deadline(db: SupportSQLiteDatabase, id: String): Long? =
        db.query(SimpleSQLiteQuery("SELECT expires_at FROM message_expiration WHERE message_id=?", arrayOf(id))).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }

    fun isExpiring(db: SupportSQLiteDatabase, id: String): Boolean = deadline(db, id) != null

    fun removeDue(db: SupportSQLiteDatabase, now: Long): Removed {
        val expired = db.query(SimpleSQLiteQuery("""SELECT m.messageId,m.conversationUuid,n.token
            FROM (SELECT message_id FROM message_expiration WHERE expires_at<=? ORDER BY expires_at,message_id LIMIT 64) d
            JOIN messages m ON m.messageId=d.message_id
            LEFT JOIN notification_targets n ON n.message_id=m.messageId""", arrayOf(now))).use { rows ->
            buildList {
                while (rows.moveToNext()) add(Triple(rows.getString(0), rows.getString(1), rows.getString(2)))
            }
        }
        if (expired.isNotEmpty()) {
            db.execSQL("""DELETE FROM messages WHERE messageId IN (
                SELECT message_id FROM message_expiration WHERE expires_at<=? ORDER BY expires_at,message_id LIMIT 64)""",
                arrayOf(now))
        }
        val next = db.query("SELECT MIN(expires_at) FROM message_expiration").use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else Long.MAX_VALUE
        }
        return Removed(next, expired.associate { it.first to it.second }, expired.mapNotNull { it.third })
    }
}
