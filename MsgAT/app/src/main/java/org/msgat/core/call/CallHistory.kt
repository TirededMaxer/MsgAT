// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.call

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class CallOutcome(val stored: String) {
    Completed("completed"), Missed("missed"), Declined("declined"), Busy("busy"),
    Canceled("canceled"), Failed("failed")
}

private val callSummaryPattern = Regex("""\[(Voice|Video) Call: (Missed|Declined|Busy|Canceled|Failed|[0-9]{2,12}:[0-5][0-9])\]""")

@androidx.compose.runtime.Composable
internal fun displayCallSummary(text: String): String {
    val match = callSummaryPattern.matchEntire(text) ?: return text
    val type = org.msgat.ui.theme.uiString(if (match.groupValues[1] == "Video")
        org.msgat.R.string.ui_video_call else org.msgat.R.string.ui_voice_call)
    val result = match.groupValues[2]
    val label = when (result) {
        "Missed" -> org.msgat.R.string.ui_call_missed
        "Declined" -> org.msgat.R.string.ui_call_declined
        "Busy" -> org.msgat.R.string.ui_call_busy
        "Canceled" -> org.msgat.R.string.ui_call_canceled
        "Failed" -> org.msgat.R.string.ui_call_failed
        else -> null
    }
    return "$type · ${if (label == null) result else org.msgat.ui.theme.uiString(label)}"
}

internal data class CallHistoryEntry(
    val peer: String, val callId: String, val alias: String, val outgoing: Boolean,
    val video: Boolean, val outcome: CallOutcome, val duration: Long, val endedAt: Long,
    val avatarPath: String = ""
)

internal object CallHistory {
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    fun changed() { _revision.update { it + 1 } }
    const val table = """CREATE TABLE IF NOT EXISTS call_history(
        peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,
        call_id TEXT NOT NULL, outgoing INTEGER NOT NULL CHECK(outgoing IN(0,1)),
        video INTEGER NOT NULL CHECK(video IN(0,1)), outcome TEXT NOT NULL
          CHECK(outcome IN('completed','missed','declined','busy','canceled','failed')),
        duration INTEGER NOT NULL CHECK(duration>=0), ended_at INTEGER NOT NULL,
        PRIMARY KEY(peer,call_id))"""
    const val index = "CREATE INDEX IF NOT EXISTS call_history_timeline ON call_history(ended_at DESC,call_id DESC,peer DESC)"

    fun insert(db: SupportSQLiteDatabase, peer: String, callId: String, outgoing: Boolean,
               video: Boolean, outcome: CallOutcome, duration: Long, endedAt: Long) {
        db.execSQL("""
            INSERT INTO call_history(peer,call_id,outgoing,video,outcome,duration,ended_at)
            SELECT uuid,?,?,?,?,?,? FROM conversations WHERE uuid=?
            ON CONFLICT(peer,call_id) DO NOTHING
            """, arrayOf(callId, if(outgoing) 1 else 0, if(video) 1 else 0, outcome.stored, duration, endedAt, peer))
    }

    fun remove(db: SupportSQLiteDatabase, entry: CallHistoryEntry?) {
        if (entry == null) {
            db.execSQL("DELETE FROM call_history")
        } else {
            db.execSQL("DELETE FROM call_history WHERE peer=? AND call_id=?", arrayOf(entry.peer, entry.callId))
        }
    }

    fun page(db: SupportSQLiteDatabase, before: CallHistoryEntry?, count: Int, missedOnly: Boolean): List<CallHistoryEntry> {
        val filter = buildList {
            if (missedOnly) add("h.outgoing=0 AND h.outcome='missed'")
            if (before != null) add("(h.ended_at,h.call_id,h.peer)<(?,?,?)")
        }.let { if (it.isEmpty()) "" else it.joinToString(" AND ", prefix = "WHERE ") }
        val args = if (before == null) arrayOf<Any>(count + 1)
            else arrayOf<Any>(before.endedAt, before.callId, before.peer, count + 1)
        return db.query(SimpleSQLiteQuery("""
            SELECT h.peer,h.call_id,c.alias,h.outgoing,h.video,h.outcome,h.duration,h.ended_at,c.avatarPath
            FROM call_history h JOIN conversations c ON c.uuid=h.peer $filter
            ORDER BY h.ended_at DESC,h.call_id DESC,h.peer DESC LIMIT ?
            """,args)).use { cursor ->
            buildList {
                while(cursor.moveToNext()) add(CallHistoryEntry(cursor.getString(0),cursor.getString(1),cursor.getString(2),
                    cursor.getInt(3)!=0,cursor.getInt(4)!=0,CallOutcome.entries.first {it.stored==cursor.getString(5)},
                    cursor.getLong(6),cursor.getLong(7),cursor.getString(8)))
            }
        }
    }
}
