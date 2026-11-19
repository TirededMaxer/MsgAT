// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.data.db

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

enum class MessageSearchKind(val extension: String?) { All(null), Text("txt"), Attachments("attachment") }
data class MessageSearchHit(val messageId: String, val peer: String, val alias: String, val preview: String, val timestamp: Long, val isAttachment: Boolean)
data class MessageSearchCursor(val timestamp: Long, val messageId: String)
data class MessageSearchPage(val hits: List<MessageSearchHit>, val next: MessageSearchCursor?)

class MessageSearchStore(context: Context, private val database: AppDatabase) {
    private val assets = context.applicationContext.assets
    private val gate = Mutex()
    private var ready = false

    suspend fun search(input: String, peer: String? = null, before: MessageSearchCursor? = null,
                       kind: MessageSearchKind = MessageSearchKind.All, limit: Int = 50,
                       newer: Boolean = false, day: LocalDate? = null): MessageSearchPage = withContext(Dispatchers.IO) {
        require(limit in 1 until Int.MAX_VALUE)
        val query=input.trim()
        if(query.isEmpty()) return@withContext MessageSearchPage(emptyList(),null)
        val count=query.codePointCount(0,query.length)
        require(count<=256 && '\u0000' !in query) { "Search text is too long or invalid" }
        val zone = ZoneId.systemDefault()
        val dayStart = day?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val dayEnd = day?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        gate.withLock {
            val page=database.withTransaction {
                val db=database.openHelper.writableDatabase
                if(!ready) assets.open("message-search.sql").bufferedReader().use { reader ->
                    reader.readText().split("\n-- statement\n").forEach(db::execSQL)
                }
                val indexed=count>=3
                val condition=if(indexed) "local_message_search MATCH ?" else "instr(lower(s.body),lower(?))>0"
                val needle=if(indexed) "\"${query.replace("\"","\"\"") }\"" else query
                val comparison = if (newer) ">" else "<"
                val order = if (newer) "ASC" else "DESC"
                val rows=db.query(SimpleSQLiteQuery("""
                    SELECT m.messageId,m.conversationUuid,c.alias,s.body,m.timestamp,m.ext
                    FROM local_message_search s JOIN messages m ON m.rowid=s.rowid
                    JOIN conversations c ON c.uuid=m.conversationUuid
                    WHERE $condition AND m.isRevoked=0 AND (? IS NULL OR m.conversationUuid=?)
                     AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?4)
                     AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                         AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?4-r.duration_ms)
                     AND (? IS NULL OR m.ext=?)
                     AND (? IS NULL OR (m.timestamp>=? AND m.timestamp<?))
                     AND (? IS NULL OR m.timestamp$comparison? OR (m.timestamp=? AND m.messageId$comparison?))
                    ORDER BY m.timestamp $order,m.messageId $order LIMIT ?
                    """,arrayOf(needle, peer, peer, System.currentTimeMillis(), kind.extension, kind.extension,
                        dayStart, dayStart, dayEnd,
                        before?.timestamp, before?.timestamp, before?.timestamp, before?.messageId, limit + 1))).use { c ->
                    buildList { while(c.moveToNext()) add(MessageSearchHit(c.getString(0),c.getString(1),c.getString(2),preview(c.getString(3),query),c.getLong(4),c.getString(5) == "attachment")) }
                }
                val hits=rows.take(limit)
                MessageSearchPage(hits,if(rows.size>limit) hits.last().let { MessageSearchCursor(it.timestamp,it.messageId) } else null)
            }
            ready=true
            page
        }
    }

    private fun preview(source: String, query: String): String {
        val body=org.msgat.core.crypto.SecurityCore.messageSummary(source,48*1024)
        var start=maxOf(0,body.indexOf(query,ignoreCase=true)-70)
        if(start>0 && body[start].isLowSurrogate()) start--
        var end=minOf(body.length,start+280)
        if(end<body.length && end>0 && body[end-1].isHighSurrogate()) end--
        return (if(start>0) "…" else "")+body.substring(start,end)+(if(end<body.length) "…" else "")
    }
}
