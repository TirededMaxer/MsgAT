// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore
import org.msgat.core.crypto.SignalProtocolStore
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.AppDatabase
import org.msgat.model.GhostPacket
import org.msgat.ui.chat.SyncState
import java.nio.ByteBuffer
import java.util.UUID

/** Serial, bounded coordinator for real V2 events. Connection epochs fence stale callbacks. */
class SecureMessaging(
    database: AppDatabase,
    private val prefs: SecurePrefs,
    private val send: (Long, GhostPacket) -> Boolean,
    private val stateChanged: (Long, SyncState) -> Unit,
    private val closeTransport: (Long) -> Unit,
    private val onMessageCommitted: (String) -> Unit
) {
    private class VisibleFailure(message: String) : IllegalStateException(message)
    private class Work(val block: suspend () -> Unit, val failed: (() -> Unit)? = null)
    private val store = SignalProtocolStore(database,prefs)
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val commands = Channel<Work>(64)
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity=8)
    val errors = _errors.asSharedFlow()
    @Volatile private var epoch = -1L
    private var ready = false
    private var retry: Job? = null
    private val inFlight = mutableSetOf<String>()
    private val requested = mutableMapOf<String,Pair<String,Long>>()
    private val unavailableUntil = mutableMapOf<String,Long>()
    private var publishedBody: String? = null
    private var nextMaintenance = 0L

    init {
        scope.launch {
            for (work in commands) {
                try { work.block() }
                catch (cancelled: CancellationException) { work.failed?.invoke(); throw cancelled }
                catch (error: Exception) { work.failed?.invoke(); report(error) }
            }
        }
    }

    fun beginSync(connection: Long) = network(connection) {
        if (connection<epoch) return@network
        epoch=connection; ready=false; inFlight.clear(); requested.clear()
        val bundle=store.publicBundle()
        publishedBody=bundle.getString("body")
        nextMaintenance=now+3600000
        if (!sendPacket(GhostPacket(GhostPacket.TYPE_V2_KEY_PUBLISH,prefs.myMailboxId, null,json(bundle),null))) return@network
        retry?.cancel()
        retry=scope.launch {
            while (isActive) {
                delay(30000)
                network(connection) { if (epoch==connection && ready) retryPending() }
            }
        }
    }

    fun disconnected(connection: Long) = network(connection) {
        if (epoch!=connection) return@network
        ready=false; inFlight.clear(); requested.clear(); retry?.cancel(); retry=null
    }

    fun handle(connection: Long, packet: GhostPacket) = network(connection) {
        if (connection!=epoch) return@network
        when (packet.type) {
            GhostPacket.TYPE_V2_KEY_PUBLISHED -> {
                require(packet.encryptedData!=null && packet.encryptedData.size<1024)
                require(JSONObject(String(packet.encryptedData,Charsets.UTF_8)).optBoolean("published"))
                ready=true; pull(); flush()
            }
            GhostPacket.TYPE_V2_KEY_RESPONSE -> keyResponse(packet)
            GhostPacket.TYPE_V2_STORED -> {
                val recipient=packet.targetMailboxId ?: error("Missing receipt target")
                val id=eventID(packet.nonceOrIv)
                store.transaction { db ->
                    db.execSQL("UPDATE protocol_outbox SET delivered=1 WHERE event_id=? AND recipient=?",arrayOf(id,recipient))
                    db.execSQL("UPDATE messages SET status=MAX(status,1) WHERE messageId=? AND conversationUuid=? AND isMine=1",arrayOf(id,recipient))
                }
                inFlight.remove(id); flush()
            }
            GhostPacket.TYPE_V2_DELIVERY -> {
                val sender=packet.targetMailboxId ?: error("Missing sender")
                val id=eventID(packet.nonceOrIv)
                var notify = false
                val fresh = store.receive(packet.encryptedData ?: error("Missing ciphertext"),sender,id) { event,db ->
                    apply(event,db)
                    notify = event.kind=="chat.text" && exists(db,
                        "SELECT 1 FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=0 AND isRead=0",
                        arrayOf(id,sender))
                }
                sendPacket(GhostPacket(GhostPacket.TYPE_V2_RECEIVED,sender,eventBytes(id),null,null))
                // Only committed, newly inserted messages may alert. Notification
                // permission failure must not roll back state or prevent the ACK.
                if (fresh && notify) runCatching { onMessageCommitted(sender) }
                flush()
            }
            GhostPacket.TYPE_V2_SYNC_END -> {
                stateChanged(epoch,SyncState.SYNCED); flush()
            }
        }
    }

    suspend fun sendText(recipient: String, text: String, replyID: String?): Boolean = userOperation {
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size>48*1024) throw VisibleFailure("Message text is empty or too large")
        store.publicBundle()
        val id=UUID.randomUUID().toString(); val timestamp=now
        val body=JSONObject().put("text",text)
        store.transaction { db ->
            if (!exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(recipient)))
                throw VisibleFailure("Accept the contact request before sending messages")
            if (replyID!=null) {
                db.query(SimpleSQLiteQuery("SELECT isMine,content,ext FROM messages WHERE messageId=? AND conversationUuid=?",arrayOf(replyID,recipient))).use { cursor ->
                    if (!cursor.moveToFirst()) throw VisibleFailure("The quoted message is unavailable")
                    val author=if (cursor.getInt(0)!=0) prefs.myUuid else recipient
                    val preview=if (cursor.getString(2)=="txt") cursor.getString(1) else "[${cursor.getString(2)}]"
                    val end = preview.offsetByCodePoints(0,minOf(512,preview.codePointCount(0,preview.length)))
                    body.put("reply",JSONObject().put("event_id",replyID).put("author",author).put("preview",preview.substring(0,end)))
                }
            }
            val event=SignalProtocolStore.Pending(id,recipient,"chat.text",body,timestamp)
            if (store.enqueue(event,db)) insertText(db,id,recipient,true,text,timestamp,body.optJSONObject("reply"))
        }
    }

    suspend fun requestContact(recipient: String): Boolean = userOperation {
        store.publicBundle()
        store.enqueue(pending(recipient,"contact.request",profile())) { db ->
            if (blocked(db,recipient)) throw VisibleFailure("This contact is blocked")
            db.execSQL("INSERT INTO contact_states(uuid,outgoing) VALUES(?,1) ON CONFLICT(uuid) DO UPDATE SET outgoing=1",arrayOf(recipient))
            db.execSQL("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES(?,'Pending request','','') ON CONFLICT(uuid) DO NOTHING",arrayOf(recipient))
        }
    }

    suspend fun acceptContact(recipient: String, nickname: String, avatarPath: String): Boolean = userOperation {
        store.enqueue(pending(recipient,"contact.accept",profile())) { db ->
            if (!exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND incoming=1 AND blocked=0",arrayOf(recipient)))
                throw VisibleFailure("No contact request is awaiting acceptance")
            db.execSQL("UPDATE contact_states SET incoming=0,accepted=1 WHERE uuid=?",arrayOf(recipient))
            db.execSQL("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES(?,?,?,'') ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias,avatarPath=excluded.avatarPath",arrayOf(recipient,nickname,avatarPath))
            db.execSQL("DELETE FROM friend_requests WHERE senderUuid=?",arrayOf(recipient))
        }
        if (ready) pull()
    }

    suspend fun declineContact(recipient: String): Boolean = userOperation {
        store.transaction { db ->
            db.execSQL("DELETE FROM friend_requests WHERE senderUuid=?",arrayOf(recipient))
            db.execSQL("UPDATE contact_states SET incoming=0 WHERE uuid=?",arrayOf(recipient))
        }
    }

    suspend fun deleteContact(recipient: String): Boolean = userOperation {
        store.enqueue(pending(recipient,"contact.delete",JSONObject())) { db ->
            db.execSQL("UPDATE contact_states SET accepted=0,outgoing=0,incoming=0 WHERE uuid=?",arrayOf(recipient))
            db.execSQL("DELETE FROM conversations WHERE uuid=?",arrayOf(recipient))
            db.execSQL("DELETE FROM messages WHERE conversationUuid=?",arrayOf(recipient))
            db.execSQL("DELETE FROM friend_requests WHERE senderUuid=?",arrayOf(recipient))
            db.execSQL("DELETE FROM protocol_pending_events WHERE recipient=? AND kind LIKE 'chat.%'",arrayOf(recipient))
            db.execSQL("DELETE FROM protocol_outbox WHERE recipient=? AND delivered=0",arrayOf(recipient))
        }
    }

    suspend fun markRead(recipient: String): Boolean = userOperation {
        store.transaction { db ->
            val latest=db.query(SimpleSQLiteQuery("SELECT messageId FROM messages WHERE conversationUuid=? AND isMine=0 AND isRead=0 ORDER BY timestamp DESC,messageId DESC LIMIT 1",arrayOf(recipient))).use { if (it.moveToFirst()) it.getString(0) else null }
            if (latest!=null) {
                db.execSQL("UPDATE messages SET isRead=1 WHERE conversationUuid=? AND isMine=0",arrayOf(recipient))
                receipt(db,recipient,latest,"read")
            }
        }
    }

    fun broadcastProfile() = network(epoch) {
        val peers=store.transaction { db -> db.query("SELECT uuid FROM contact_states WHERE accepted=1 AND blocked=0").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } } }
        for (peer in peers) store.enqueue(pending(peer,"contact.profile",profile()))
        flush()
    }

    private fun apply(event: SignalProtocolStore.Incoming, db: SupportSQLiteDatabase) {
        when (event.kind) {
            "contact.request" -> {
                val name=nickname(event.body)
                if (blocked(db,event.sender) || exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1",arrayOf(event.sender))) return
                db.execSQL("INSERT INTO contact_states(uuid,incoming) VALUES(?,1) ON CONFLICT(uuid) DO UPDATE SET incoming=1",arrayOf(event.sender))
                db.execSQL("INSERT INTO friend_requests(senderUuid,nickname,avatarPath,peerPublicKey,timestamp) VALUES(?,?,'','',?) ON CONFLICT(senderUuid) DO UPDATE SET nickname=excluded.nickname,timestamp=excluded.timestamp",arrayOf(event.sender,name,event.createdAt))
            }
            "contact.accept" -> {
                val name=nickname(event.body)
                if (!exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND blocked=0 AND (outgoing=1 OR accepted=1)",arrayOf(event.sender))) return
                db.execSQL("UPDATE contact_states SET outgoing=0,accepted=1 WHERE uuid=?",arrayOf(event.sender))
                db.execSQL("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES(?,?,'','') ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias",arrayOf(event.sender,name))
                db.execSQL("DELETE FROM friend_requests WHERE senderUuid=?",arrayOf(event.sender))
            }
            "contact.profile" -> db.execSQL("UPDATE conversations SET alias=? WHERE uuid=? AND EXISTS (SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0)",arrayOf(nickname(event.body),event.sender,event.sender))
            "contact.delete" -> {
                db.execSQL("UPDATE contact_states SET accepted=0,outgoing=0,incoming=0 WHERE uuid=?",arrayOf(event.sender))
                db.execSQL("DELETE FROM friend_requests WHERE senderUuid=?",arrayOf(event.sender))
            }
            "chat.text" -> {
                val text=event.body.getString("text")
                require(text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size<=48*1024)
                if (blocked(db,event.sender) || !exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1",arrayOf(event.sender))) return
                val reply=event.body.optJSONObject("reply")
                if (reply!=null) {
                    UUID.fromString(reply.getString("event_id"))
                    require(reply.getString("author") in listOf(event.sender,prefs.myUuid))
                    require(reply.getString("preview").toByteArray(Charsets.UTF_8).size<=4096)
                }
                insertText(db,event.eventID,event.sender,false,text,event.createdAt,reply)
                receipt(db,event.sender,event.eventID,"delivered")
            }
            "chat.receipt" -> {
                val id=event.body.getString("message_id"); val status=event.body.getString("state")
                require(status=="delivered" || status=="read")
                if (status=="delivered") db.execSQL("UPDATE messages SET status=MAX(status,2) WHERE messageId=? AND conversationUuid=? AND isMine=1",arrayOf(id,event.sender))
                else {
                    val timestamp=db.query(SimpleSQLiteQuery("SELECT timestamp FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=1",arrayOf(id,event.sender))).use { if (it.moveToFirst()) it.getLong(0) else null }
                    if (timestamp!=null) db.execSQL("UPDATE messages SET status=MAX(status,3) WHERE conversationUuid=? AND isMine=1 AND timestamp<=?",arrayOf(event.sender,timestamp))
                }
            }
            else -> throw VisibleFailure("Unsupported encrypted event")
        }
    }

    private suspend fun flush() {
        if (!ready) return
        for (event in store.pendingEvents()) {
            if (store.hasSession(event.recipient)) store.prepare(event)
            else if (!requested.containsKey(event.recipient) && (unavailableUntil[event.recipient] ?: 0)<now) {
                val id=store.keyRequestID(event.recipient)
                if (sendPacket(GhostPacket(GhostPacket.TYPE_V2_KEY_CLAIM,prefs.myMailboxId,null,json(JSONObject().put("request_id",id).put("uuid",event.recipient)),null))) requested[event.recipient]=id to now
            }
        }
        for (event in store.pendingOutgoing(8)) {
            if (event.eventID in inFlight) continue
            if (!sendPacket(GhostPacket(GhostPacket.TYPE_V2_SUBMIT,event.recipient,eventBytes(event.eventID),event.wire,null))) return
            inFlight.add(event.eventID)
        }
    }

    private suspend fun keyResponse(packet: GhostPacket) {
        val bytes=packet.encryptedData ?: error("Missing directory response")
        require(bytes.size<=192*1024)
        val response=JSONObject(String(bytes,Charsets.UTF_8))
        val recipient=response.getString("uuid"); val id=response.getString("request_id")
        require(requested[recipient]?.first==id)
        requested.remove(recipient)
        if (!response.optBoolean("found")) {
            unavailableUntil[recipient]=now+300000
            store.finishKeyRequest(recipient,id)
            throw VisibleFailure("The recipient has no available key bundle. Your message remains protected in the local vault.")
        }
        val bundle=response.getJSONObject("bundle")
        SecurityCore.call(JSONObject().put("operation","verify_bundle").put("bundle",bundle).put("expected_uuid",recipient))
        val selected=if (response.isNull("selected_prekey")) null else response.getLong("selected_prekey")
        for (event in store.pendingEvents(256)) if (event.recipient==recipient) store.prepare(event,bundle,selected)
        store.finishKeyRequest(recipient,id); flush()
    }

    private suspend fun retryPending() {
        inFlight.clear()
        requested.entries.removeAll { now-it.value.second>=30000 }
        if (now>=nextMaintenance) {
            val bundle=store.publicBundle(); nextMaintenance=now+3600000
            if (publishedBody!=bundle.getString("body") && sendPacket(GhostPacket(GhostPacket.TYPE_V2_KEY_PUBLISH,prefs.myMailboxId,null,json(bundle),null))) publishedBody=bundle.getString("body")
        }
        flush(); pull()
    }
    private fun insertText(db: SupportSQLiteDatabase,id: String,peer: String,mine: Boolean,text: String,created: Long,reply: JSONObject?) {
        db.execSQL("INSERT INTO messages(messageId,conversationUuid,isMine,content,ext,timestamp,isRead,replyToId,replyToSender,replyToContent,status,isRevoked,isEdited) VALUES(?,?,?,?,'txt',?,?,?,?,?,0,0,0)",arrayOf(id,peer,if (mine) 1 else 0,text,created,if (mine) 1 else 0,reply?.getString("event_id"),reply?.getString("author"),reply?.getString("preview")))
    }
    private fun receipt(db: SupportSQLiteDatabase,recipient: String,id: String,state: String) {
        store.enqueue(pending(recipient,"chat.receipt",JSONObject().put("message_id",id).put("state",state)),db)
    }
    private fun nickname(body: JSONObject): String = body.getString("nickname").also { require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size<=256) }
    private fun blocked(db: SupportSQLiteDatabase,uuid: String)=exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND blocked=1",arrayOf(uuid))
    private fun exists(db: SupportSQLiteDatabase,sql: String,args: Array<Any?>)=db.query(SimpleSQLiteQuery(sql,args)).use { it.moveToFirst() }
    private fun pending(recipient: String,kind: String,body: JSONObject)=SignalProtocolStore.Pending(UUID.randomUUID().toString(),recipient,kind,body,now)
    private fun profile()=JSONObject().put("nickname",prefs.myNickname ?: "Unknown")
    private fun json(value: JSONObject)=value.toString().toByteArray(Charsets.UTF_8)
    private fun eventID(bytes: ByteArray?): String {
        require(bytes!=null && bytes.size==16)
        val buffer=ByteBuffer.wrap(bytes); val id=UUID(buffer.long,buffer.long)
        require(id.version()==4 && id.variant()==2)
        return id.toString()
    }
    private fun eventBytes(value: String): ByteArray { val id=UUID.fromString(value); return ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array() }
    private fun pull() { sendPacket(GhostPacket(GhostPacket.TYPE_V2_SYNC,prefs.myUuid,null,null,null)) }
    private fun sendPacket(packet: GhostPacket)=send(epoch,packet)
    private val now get()=System.currentTimeMillis()
    private fun network(connection: Long,block: suspend () -> Unit) {
        if (commands.trySend(Work(block)).isFailure) closeTransport(connection)
    }
    private suspend fun userOperation(block: suspend () -> Unit): Boolean {
        val answer=CompletableDeferred<Boolean>()
        commands.send(Work({
            block(); answer.complete(true)
            try { flush() } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { report(error) }
        },{ answer.complete(false) }))
        return answer.await()
    }
    private fun report(error: Exception) {
        // JSON/parser exceptions can include message bodies. Never log their values.
        Log.w("MsgAT_Secure","Secure operation failed: ${error.javaClass.simpleName}")
        _errors.tryEmit(if (error is VisibleFailure) error.message ?: "Secure operation failed" else "Secure operation failed; local state was retained")
    }
    fun close() {
        commands.close()
        while (true) { val work=commands.tryReceive().getOrNull() ?: break; work.failed?.invoke() }
        retry?.cancel(); scope.cancel()
    }
}
