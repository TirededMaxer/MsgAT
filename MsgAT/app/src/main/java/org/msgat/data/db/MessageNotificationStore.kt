// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.data.db

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore

internal object MessageNotificationStore {
    data class Settings(val enabled: Boolean, val playSound: Boolean, val showPreviews: Boolean)
    data class Target(val peer: String, val messageId: String)
    data class Notice(val token: String, val replacedToken: String?, val title: String, val text: String?,
                      val silent: Boolean, val expiresAt: Long?, val pinned: Boolean)

    fun settings(db: SupportSQLiteDatabase): Settings =
        db.query("SELECT message_alerts_enabled,message_alert_sound,show_notification_previews FROM privacy_preferences WHERE id=1").use {
            check(it.moveToFirst())
            Settings(it.getInt(0) == 1, it.getInt(1) == 1, it.getInt(2) == 1)
        }

    fun setSettings(db: SupportSQLiteDatabase, settings: Settings) {
        db.execSQL("UPDATE privacy_preferences SET message_alerts_enabled=?,message_alert_sound=?,show_notification_previews=? WHERE id=1",
            arrayOf(if (settings.enabled) 1 else 0, if (settings.playSound) 1 else 0, if (settings.showPreviews) 1 else 0))
    }

    fun tokenForReadMessage(db: SupportSQLiteDatabase, peer: String): String? =
        db.query(SimpleSQLiteQuery("""
            SELECT n.token FROM notification_targets n
            JOIN messages m ON m.messageId=n.message_id AND m.conversationUuid=n.peer
            WHERE n.peer=? AND m.isMine=0 AND m.isRead=1
        """, arrayOf(peer))).use { if (it.moveToFirst()) it.getString(0) else null }

    fun tokenForConversation(db: SupportSQLiteDatabase, peer: String): String? =
        db.query(SimpleSQLiteQuery("SELECT token FROM notification_targets WHERE peer=?", arrayOf(peer)))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    suspend fun prepare(database: AppDatabase, peer: String, id: String, silent: Boolean, pinned: Boolean = false): Notice? =
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            val now = System.currentTimeMillis()
            val settings = settings(db)
            if (!settings.enabled || ConversationIndexStore.isMuted(db, peer, now)) return@withTransaction null
            val deadline = MessageExpirationStore.deadline(db, id)
            if (deadline != null && deadline <= now) return@withTransaction null
            val preview = settings.showPreviews && !pinned && deadline == null && db.query("""
                SELECT 1 FROM privacy_preferences WHERE id=1 AND app_lock_enabled=0
                """).use { it.moveToFirst() }
            val columns = if (preview) "c.alias,m.ext,m.content" else "1"
            val target = if (pinned) """
                EXISTS(SELECT 1 FROM message_pin_clocks p WHERE p.peer=m.conversationUuid
                    AND p.message_id=m.messageId AND p.actor=m.conversationUuid AND p.addition>p.removed)
                """ else "m.isMine=0 AND m.isRead=0"
            val display = db.query(SimpleSQLiteQuery("""
                SELECT $columns FROM messages m JOIN conversations c ON c.uuid=m.conversationUuid
                JOIN contact_states s ON s.uuid=c.uuid
                WHERE m.conversationUuid=? AND m.messageId=? AND m.isRevoked=0 AND ($target)
                  AND s.accepted=1 AND s.blocked=0
                """, arrayOf(peer, id))).use { row ->
                if (!row.moveToFirst()) return@withTransaction null
                if (preview) {
                    val title = clean(row.getString(0), 60).ifEmpty { "MsgAT" }
                    val body = if (row.getString(1) == "txt") {
                        val summary = runCatching {
                            SecurityCore.call(JSONObject().put("operation", "summarize_message_text")
                                .put("text", row.getString(2)).put("limit", 120)).getString("text")
                        }.getOrDefault("")
                        clean(summary, 120)
                    } else ""
                    title to body.takeIf { it.isNotEmpty() }
                } else "MsgAT" to null
            }
            val replacedToken = tokenForConversation(db, peer)
            val token = UUID.randomUUID().toString()
            db.execSQL("""
                INSERT INTO notification_targets(peer,token,message_id,is_pin) VALUES(?,?,?,?)
                ON CONFLICT(peer) DO UPDATE SET token=excluded.token,message_id=excluded.message_id,is_pin=excluded.is_pin
                """, arrayOf(peer, token, id, if (pinned) 1 else 0))
            Notice(token, replacedToken, display.first, display.second, silent || !settings.playSound, deadline, pinned)
        }

    suspend fun resolve(database: AppDatabase, token: String): Target? = database.withTransaction {
        database.openHelper.writableDatabase.query(SimpleSQLiteQuery("""
            SELECT n.peer,n.message_id FROM notification_targets n
            JOIN messages m ON m.messageId=n.message_id AND m.conversationUuid=n.peer
            JOIN contact_states s ON s.uuid=n.peer
            WHERE n.token=? AND s.accepted=1 AND s.blocked=0 AND m.isRevoked=0
              AND (n.is_pin=0 OR EXISTS (SELECT 1 FROM message_pin_clocks p WHERE p.peer=n.peer
                  AND p.message_id=n.message_id AND p.actor=n.peer AND p.addition>p.removed))
              AND EXISTS(SELECT 1 FROM privacy_preferences WHERE id=1 AND message_alerts_enabled=1)
              AND NOT EXISTS (SELECT 1 FROM message_expiration e WHERE e.message_id=n.message_id AND e.expires_at<=?2)
              AND NOT EXISTS (SELECT 1 FROM conversation_retention r WHERE r.peer=n.peer
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?2-r.duration_ms)
            """, arrayOf(token, System.currentTimeMillis()))).use { row -> if (row.moveToFirst()) Target(row.getString(0), row.getString(1)) else null }
    }

    private fun clean(value: String, limit: Int): String {
        val text = buildString {
            var offset = 0
            var count = 0
            while (offset < value.length && count < limit) {
                val point = value.codePointAt(offset)
                offset += Character.charCount(point)
                if (Character.isWhitespace(point)) {
                    if (isNotEmpty() && last() != ' ') { append(' '); count++ }
                } else if (!Character.isISOControl(point) && Character.getType(point) != Character.FORMAT.toInt()) {
                    appendCodePoint(point)
                    count++
                }
            }
        }
        return text.trim()
    }
}
