// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore
import org.msgat.core.network.LocationPoint

internal data class LiveLocationSnapshot(
    val startedAt: Long,
    val expiresAt: Long,
    val revision: Int,
    val ended: Boolean,
    val point: LocationPoint
)

internal object LiveLocationStore {
    fun apply(
        db: SupportSQLiteDatabase, peer: String, mine: Boolean, kind: String,
        eventId: String, body: JSONObject, createdAt: Long, now: Long
    ): Boolean {
        val start = kind == "chat.location.start"
        val messageId = if (start) eventId else body.getString("message_id")
        val direction = if (mine) 1 else 0
        val scope = arrayOf(peer, direction, messageId)
        val compatible = db.query(SimpleSQLiteQuery(
            "SELECT conversationUuid,isMine,ext,isRevoked FROM messages WHERE messageId=?", arrayOf(messageId)
        )).use { row ->
            if (!row.moveToFirst()) !mine || start
            else row.getString(0) == peer && row.getInt(1) == direction &&
                row.getString(2) == "live_location" && row.getInt(3) == 0
        }
        if (!compatible) return false

        pruneOrphans(db, now)
        val previous = db.query(SimpleSQLiteQuery(
            "SELECT state_json FROM live_location_states WHERE peer=? AND is_mine=? AND message_id=?", scope
        )).use { row -> if (row.moveToFirst()) JSONObject(row.getString(0)) else null }
        if (!start && previous == null) {
            if (mine || countOrphans(db, peer) >= 16 || countOrphans(db, null) >= 256) return false
        }
        val result = SecurityCore.call(JSONObject().put("operation", "live_location_transition")
            .put("state", previous ?: JSONObject.NULL).put("event_kind", kind).put("event_id", eventId)
            .put("body", body).put("created_at", createdAt).put("now", now))
        if (!result.getBoolean("changed")) return false
        val state = result.getJSONObject("state")
        db.execSQL(
            """INSERT INTO live_location_states(peer,is_mine,message_id,expires_at,anchored,state_json) VALUES(?,?,?,?,?,?)
                ON CONFLICT(peer,is_mine,message_id) DO UPDATE SET
                expires_at=excluded.expires_at,anchored=excluded.anchored,state_json=excluded.state_json""",
            arrayOf(peer, direction, messageId, state.getLong("expires_at"),
                if (state.getBoolean("anchored")) 1 else 0, state.toString())
        )
        return true
    }

    fun read(db: SupportSQLiteDatabase, peer: String, messageId: String, mineOnly: Boolean = false): LiveLocationSnapshot? =
        db.query(SimpleSQLiteQuery("""
            SELECT s.state_json FROM messages m JOIN live_location_states s
              ON s.message_id=m.messageId AND s.peer=m.conversationUuid AND s.is_mine=m.isMine
            WHERE m.conversationUuid=? AND m.messageId=? AND m.ext='live_location' AND m.isRevoked=0 AND s.anchored=1
              AND (?=0 OR m.isMine=1)
            """, arrayOf(peer, messageId, if (mineOnly) 1 else 0))).use { row ->
            if (!row.moveToFirst()) return@use null
            val state = JSONObject(row.getString(0))
            val point = state.getJSONObject("point")
            LiveLocationSnapshot(state.getLong("started_at"), state.getLong("expires_at"), state.getInt("revision"),
                state.getBoolean("ended"), LocationPoint(point.getInt("latitude_e6"), point.getInt("longitude_e6")))
        }

    fun hasActiveShare(db: SupportSQLiteDatabase, peer: String, now: Long): Boolean =
        db.query(SimpleSQLiteQuery("""
            SELECT 1 FROM live_location_states
            WHERE peer=? AND is_mine=1 AND anchored=1 AND expires_at>? AND json_extract(state_json,'$.ended')=0
            LIMIT 1
            """, arrayOf(peer, now))).use { it.moveToFirst() }

    fun replaceOutgoing(
        db: SupportSQLiteDatabase, eventId: String, peer: String, messageId: String, expiresAt: Long, end: Boolean
    ) {
        if (end) {
            db.execSQL("DELETE FROM live_location_outgoing WHERE message_id=?", arrayOf(messageId))
        } else {
            db.execSQL("""
                DELETE FROM live_location_outgoing WHERE message_id=? AND NOT EXISTS (
                    SELECT 1 FROM protocol_outbox b WHERE b.event_id=live_location_outgoing.event_id AND b.delivered=0
                )
                """, arrayOf(messageId))
        }
        db.execSQL("INSERT INTO live_location_outgoing(event_id,peer,message_id,expires_at) VALUES(?,?,?,?)",
            arrayOf(eventId, peer, messageId, expiresAt))
    }

    fun pruneOutgoing(db: SupportSQLiteDatabase, now: Long) {
        db.execSQL("""
            DELETE FROM live_location_outgoing WHERE expires_at<=? OR NOT EXISTS (
                SELECT 1 FROM contact_states c WHERE c.uuid=live_location_outgoing.peer AND c.accepted=1 AND c.blocked=0
            )
            """, arrayOf(now))
    }

    fun pruneOrphans(db: SupportSQLiteDatabase, now: Long) {
        db.execSQL("DELETE FROM live_location_states WHERE anchored=0 AND expires_at<=?", arrayOf(now))
    }

    private fun countOrphans(db: SupportSQLiteDatabase, peer: String?): Int {
        val query = if (peer == null) SimpleSQLiteQuery("SELECT COUNT(*) FROM live_location_states WHERE anchored=0")
            else SimpleSQLiteQuery("SELECT COUNT(*) FROM live_location_states WHERE anchored=0 AND peer=?", arrayOf(peer))
        return db.query(query).use { row -> if (row.moveToFirst()) row.getInt(0) else 0 }
    }
}
