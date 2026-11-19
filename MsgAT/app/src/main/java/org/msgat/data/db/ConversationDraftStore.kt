// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.data.db

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

data class ConversationDraft(
    val peer: String, val token: String, val revision: Long, val text: String, val replyId: String?,
    val replyQuote: String? = null
)
class DraftSendCommit internal constructor(val draft: ConversationDraft, val clearRevision: Long, internal val owner: ConversationDraftStore, val clearsText: Boolean, val updatesDraft: Boolean) {
    internal val finished = CompletableDeferred<Unit>()
}

/** Drafts share the root-protected SQLCipher vault; no preferences or plaintext sidecar. */
class ConversationDraftStore(private val database: AppDatabase, scope: CoroutineScope) {
    private val gate = Any()
    private val pending = linkedMapOf<Pair<String, String>, ConversationDraft>()
    private val latest = mutableMapOf<Pair<String, String>, Long>()
    private val sending = mutableMapOf<String, MutableSet<DraftSendCommit>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val ready = CompletableDeferred<Unit>()
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val errors = _errors.asSharedFlow()
    private var revision = 0L
    private var closed = false

    private val worker = scope.launch(Dispatchers.IO) {
        try {
            val maximum = transaction { db -> db.query("SELECT COALESCE(MAX(revision),0) FROM conversation_drafts").use { it.moveToFirst(); it.getLong(0) } }
            synchronized(gate) { revision = maximum }
            ready.complete(Unit)
            for (ignored in wake) {
                var reported = false
                while (true) {
                    val batch = synchronized(gate) { pending.values.toList() }
                    if (batch.isEmpty()) break
                    try {
                        for (value in batch) {
                            transaction { db -> db.execSQL(
                                "UPDATE conversation_drafts SET text=?,reply_id=?,reply_quote=?,revision=? WHERE peer=? AND token=? AND revision<?",
                                arrayOf(value.text, value.replyId, value.replyQuote, value.revision, value.peer, value.token, value.revision)) }
                            synchronized(gate) {
                                val key = value.peer to value.token
                                if (pending[key]?.revision == value.revision) pending.remove(key)
                            }
                        }
                        reported = false
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        if (!reported) { reportSaveFailure(); reported = true }
                        delay(2000)
                    }
                    currentCoroutineContext().ensureActive()
                }
            }
        } catch (cancelled: CancellationException) {
            ready.completeExceptionally(cancelled)
            throw cancelled
        } catch (error: Exception) {
            ready.completeExceptionally(error)
            reportSaveFailure()
        }
    }

    suspend fun open(peer: String): ConversationDraft {
        ready.await()
        while (true) {
            val sends = synchronized(gate) {
                check(!closed) { "The draft store is closed" }
                sending[peer]?.toList() ?: emptyList()
            }
            for (send in sends) send.finished.await()
            val value = transaction { db ->
                db.execSQL("INSERT OR IGNORE INTO conversation_drafts(peer,token,revision,text,reply_id) SELECT uuid,?,0,'',NULL FROM conversations WHERE uuid=?", arrayOf(UUID.randomUUID().toString(), peer))
                db.query(SimpleSQLiteQuery("SELECT token,revision,text,reply_id,reply_quote FROM conversation_drafts WHERE peer=?", arrayOf(peer))).use { c ->
                    check(c.moveToFirst()) { "The conversation is no longer available" }
                    ConversationDraft(peer, c.getString(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4))
                }
            }
            val result = synchronized(gate) {
                check(!closed) { "The draft store is closed" }
                revision = maxOf(revision, value.revision)
                val key = peer to value.token
                val local = pending[key]
                when {
                    !sending[peer].isNullOrEmpty() -> null
                    local != null && local.revision > value.revision -> local
                    (latest[key] ?: 0) > value.revision -> null
                    else -> value
                }
            }
            if (result != null) return result
        }
    }

    fun update(previous: ConversationDraft, text: String, replyId: String?, replyQuote: String? = null): ConversationDraft {
        check(text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "The draft is too large to save" }
        val quote = replyQuote?.takeIf { replyId != null && it.length <= 128 }
        if (previous.text == text && previous.replyId == replyId && previous.replyQuote == quote) return previous
        return synchronized(gate) {
            check(!closed) { "The draft store is closed" }
            revision = Math.addExact(revision, 1)
            val value = previous.copy(text = text, replyId = replyId, replyQuote = quote, revision = revision)
            val key = value.peer to value.token
            pending[key] = value
            latest[key] = value.revision
            wake.trySend(Unit)
            value
        }
    }

    fun prepareSend(draft: ConversationDraft, clearsText: Boolean = true): DraftSendCommit = synchronized(gate) {
        check(!closed) { "The draft store is closed" }
        revision = Math.addExact(revision, 1)
        val updatesDraft = (latest[draft.peer to draft.token] ?: 0) <= draft.revision
        DraftSendCommit(draft, revision, this, clearsText, updatesDraft).also { sending.getOrPut(draft.peer) { mutableSetOf() }.add(it) }
    }

    fun observeCommittedSend(commit: DraftSendCommit) = synchronized(gate) {
        val key = commit.draft.peer to commit.draft.token
        if (commit.updatesDraft) {
            latest[key] = maxOf(latest[key] ?: 0, commit.clearRevision)
            if ((pending[key]?.revision ?: Long.MAX_VALUE) <= commit.draft.revision) pending.remove(key)
        }
        finishSend(commit)
        Unit
    }

    internal fun observeFailedSend(commit: DraftSendCommit) = synchronized(gate) { finishSend(commit) }

    private fun finishSend(commit: DraftSendCommit) {
        sending[commit.draft.peer]?.let { active ->
            active.remove(commit)
            if (active.isEmpty()) sending.remove(commit.draft.peer)
        }
        commit.finished.complete(Unit)
    }

    internal fun reportSaveFailure() { _errors.tryEmit("The draft could not be saved. It will be retried.") }
    internal fun reportOpenFailure() { _errors.tryEmit("The saved draft could not be opened.") }

    // NettyClient.close is identity-reset teardown; normal disconnect keeps drafts alive.
    fun close() {
        synchronized(gate) {
            closed = true
            sending.values.flatten().forEach { it.finished.complete(Unit) }
            sending.clear()
        }
        wake.close()
        ready.cancel()
        worker.cancel()
    }

    private suspend fun <T> transaction(block: (SupportSQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        database.withTransaction { block(database.openHelper.writableDatabase) }
    }

    companion object {
        fun clearForCommittedSend(db: SupportSQLiteDatabase, commit: DraftSendCommit) {
            if (!commit.updatesDraft) return
            db.execSQL("UPDATE conversation_drafts SET text=CASE WHEN ?=1 THEN '' WHEN revision<=? THEN ? ELSE text END,reply_id=NULL,reply_quote=NULL,revision=? WHERE peer=? AND token=? AND revision<?",
                arrayOf(if (commit.clearsText) 1 else 0, commit.draft.revision, commit.draft.text, commit.clearRevision, commit.draft.peer, commit.draft.token, commit.clearRevision))
        }
    }
}
