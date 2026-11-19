// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.network

import android.util.Log
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore
import org.msgat.core.crypto.SignalProtocolStore
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.ContactStateStore
import org.msgat.data.db.UserProfileStore
import org.msgat.data.db.AppDatabase
import org.msgat.data.db.ConversationDraftStore
import org.msgat.data.db.DraftSendCommit
import org.msgat.data.db.LiveLocationSnapshot
import org.msgat.data.db.LiveLocationStore
import org.msgat.data.db.MessageExpirationStore
import org.msgat.data.db.MessageRetentionSettings
import org.msgat.data.db.WebPreviewStore
import org.msgat.model.GhostPacket
import org.msgat.ui.chat.SyncState
import org.msgat.core.media.EncryptedObjectStore
import org.msgat.core.media.AttachmentCacheUsage
import org.msgat.core.media.AttachmentCacheCategory
import org.msgat.core.media.EncryptedObjectLifecycle
import org.msgat.core.media.ObjectPreparation
import org.msgat.core.media.VideoTrim
import java.nio.ByteBuffer
import java.util.UUID

data class SendOptions(val sendAt:Long?=null,val silent:Boolean=false)
data class LocationPoint(val latitudeE6: Int, val longitudeE6: Int) {
    init {
        require(latitudeE6 in -90_000_000..90_000_000 && longitudeE6 in -180_000_000..180_000_000)
    }
}

internal data class LiveLocationShare(val messageId: String, val expiresAt: Long)
internal enum class PeerActivityStatus { NONE, TYPING, ONLINE, OFFLINE }

private fun locationPoint(body: JSONObject) = LocationPoint(body.getInt("latitude_e6"), body.getInt("longitude_e6"))
private const val DELIVERY_RETRY_DELAY = 86_400_000L
private const val DELIVERY_RETRY_WINDOW = 6 * DELIVERY_RETRY_DELAY

data class MediaSendChoice(
    val caption: String = "",
    val options: SendOptions = SendOptions(),
    val asFile: Boolean = false,
    val asAlbum: Boolean = false,
    val compressPhotos: Boolean = false,
    val compressVideos: Boolean = false,
    val videoTrims: Map<Uri, VideoTrim> = emptyMap(),
    val itemCaptions: List<String> = emptyList()
)
data class SendQueueEntry(val id:String,val peer:String,val summary:String,val sendAt:Long?,val silent:Boolean,val state:String,val canCancel:Boolean,val canRetry:Boolean,val peerAlias:String?,val failure:String?=null,val relayRetryAt:Long?=null,val canEdit:Boolean=false)
internal data class ScheduledMessageDraft(val text: String, val isCaption: Boolean)

/** Serial, bounded coordinator for real V2 events. Connection epochs fence stale callbacks. */
class SecureMessaging internal constructor(
    database: AppDatabase,
    private val prefs: SecurePrefs,
    private val send: (Long, GhostPacket) -> Boolean,
    private val stateChanged: (Long, SyncState) -> Unit,
    private val closeTransport: (Long) -> Unit,
    private val onMessageCommitted: suspend (String,String,Boolean,Boolean) -> Unit,
    private val objects: EncryptedObjectStore,
    private val actions: org.msgat.data.db.MessageActionsStore,
    connection: () -> Pair<Long, RelayEndpoint?>,
    connected: () -> Boolean,
    private val onContactsChanged: ()->Unit = {},
    private val mailboxProgress: (Long) -> Unit = {}
) {
    private class VisibleFailure(message: String) : IllegalStateException(message)
    private class Work(val block: suspend () -> Unit, val failed: (() -> Unit)? = null)
    private val store = SignalProtocolStore(database,prefs)
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val commands = Channel<Work>(64)
    private val storageWake = Channel<Unit>(1)
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity=8)
    val errors = _errors.asSharedFlow()
    private val _sendQueueChanges=MutableSharedFlow<Unit>(extraBufferCapacity=8)
    val sendQueueChanges=_sendQueueChanges.asSharedFlow()
    private val _liveLocationChanges = MutableSharedFlow<String>(extraBufferCapacity = 16)
    internal val liveLocationChanges = _liveLocationChanges.asSharedFlow()
    private val _removedMessages = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)
    internal val removedMessages = _removedMessages.asSharedFlow()
    @Volatile private var nextExpiration = 0L
    @Volatile private var nextRetentionSweep = 0L
    private val expirationQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var epoch = -1L
    @Volatile private var ready = false
    private var retry: Job? = null
    private val inFlight = mutableSetOf<String>()
    @Volatile private var nextRelayRetryAt = Long.MAX_VALUE
    private val requested = mutableMapOf<String,Pair<String,Long>>()
    private data class ClaimedBundle(val bundle: JSONObject, val selected: Long?, val request: String, val expiresAt: Long)
    private val claimedBundles=linkedMapOf<String,ClaimedBundle>()
    private val unavailableUntil = mutableMapOf<String,Long>()
    private var publishedBody: String? = null
    private var directoryEpoch: String? = null
    private data class PushPull(val nonce: ByteArray, val reply: CompletableDeferred<Boolean>)
    private var pushPull: PushPull? = null
    private var syncNonce: ByteArray? = null
    private var nextMaintenance = 0L
    private val attachments = AttachmentQueue(store,objects,prefs,connection,connected) { transferCompleted() }
    internal val forwarding = ForwardTaskQueue(database, actions, ::processForwardTask) {
        objects.signalAvailable()
        attachments.signal()
    }
    val attachmentProgress = attachments.progress
    val previewProgress = attachments.previewProgress
    private fun transferCompleted() {forwarding.signal();network(epoch) {flush()}}

    init {
        scope.launch {
            while (isActive) {
                if (minOf(nextExpiration, nextRetentionSweep) <= now) signalExpiration()
                if (ready && minOf(nextScheduledSend, nextRelayRetryAt) <= now) signalScheduledSends()
                delay(1_000)
            }
        }
        scope.launch {
            while(isActive){delay(2_000);if(activityForeground && (activityOnline || activityTyping))signalActivity()}
        }
        scope.launch {
            while(isActive) {
                val count=try {objects.collectGarbage()}
                catch(cancelled: CancellationException){throw cancelled}
                catch(error: Exception){Log.w("MsgAT_Storage","Encrypted maintenance will retry: ${error.javaClass.simpleName}");0}
                if (count > 0) attachments.signal()
                withTimeoutOrNull(if (count >= 64) 250L else 30_000L) { storageWake.receive() }
            }
        }
        scope.launch {
            try {
            for (work in commands) {
                try { work.block() }
                catch (cancelled: CancellationException) {
                    reject(work)
                    if(!currentCoroutineContext().isActive)throw cancelled
                }
                catch (error: Exception) { reject(work); if(error !is org.msgat.core.media.PreparationCancelledException)report(error) }
            }
            } finally {commands.close();while(true) {val pending=commands.tryReceive().getOrNull()?:break;reject(pending)}}
        }
    }
    private fun reject(work: Work) {
        try {work.failed?.invoke()}
        catch(error: Exception){Log.w("MsgAT_Secure","Secure operation cleanup failed: ${error.javaClass.simpleName}")}
    }

    fun beginSync(connection: Long, directoryEpoch: String) = network(connection) {
        if (connection<epoch) return@network
        endPushPull()
        epoch=connection; ready=false; inFlight.clear(); requested.clear();claimedBundles.clear()
        syncNonce = null
        this.directoryEpoch = directoryEpoch
        val bundle=store.publicBundle(directoryEpoch = directoryEpoch)
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
        endPushPull()
        ready=false; inFlight.clear(); requested.clear();claimedBundles.clear(); retry?.cancel(); retry=null
        syncNonce = null
    }

    private fun endPushPull(success: Boolean = false) {
        pushPull?.reply?.complete(success)
        pushPull = null
    }

    internal suspend fun pullForPush(connection: Long): Boolean {
        val reply = CompletableDeferred<Boolean>()
        try {
            commands.send(Work({
                if (!reply.isActive || !ready || connection != epoch) {
                    reply.complete(false)
                } else {
                    endPushPull()
                    val nonce = eventBytes(UUID.randomUUID().toString())
                    pushPull = PushPull(nonce, reply)
                    if (!sendPacket(GhostPacket(GhostPacket.TYPE_V2_SYNC, prefs.myUuid, nonce, null, null))) endPushPull()
                }
            }, { reply.complete(false) }))
            return reply.await()
        } finally { reply.cancel() }
    }

    fun handle(connection: Long, packet: GhostPacket) = network(connection) {
        if (connection!=epoch) return@network
        when (packet.type) {
            GhostPacket.TYPE_V2_KEY_PUBLISHED -> {
                require(packet.encryptedData!=null && packet.encryptedData.size<1024)
                require(JSONObject(String(packet.encryptedData,Charsets.UTF_8)).optBoolean("published"))
                mailboxProgress(connection)
                ready=true; pull(); flush()
            }
            GhostPacket.TYPE_V2_KEY_RESPONSE -> keyResponse(packet)
            GhostPacket.TYPE_V2_DEFERRED -> {
                require(packet.payloadLen == 0)
                val recipient = packet.targetMailboxId ?: error("Missing deferral recipient")
                val id = eventID(packet.nonceOrIv)
                if (id in inFlight && store.transaction { db ->
                    db.execSQL("""
                        INSERT INTO protocol_relay_deferrals(recipient,retry_at)
                        SELECT recipient,? FROM protocol_outbox WHERE event_id=? AND recipient=? AND delivered=0
                        ON CONFLICT(recipient) DO UPDATE SET retry_at=excluded.retry_at
                    """, arrayOf(now + 30_000, id, recipient))
                    db.query("SELECT changes()").use { it.moveToFirst() && it.getInt(0) > 0 }
                }) {
                    inFlight.remove(id)
                    _sendQueueChanges.tryEmit(Unit)
                    flush()
                }
            }
            GhostPacket.TYPE_V2_REJECTED -> {
                val peer=packet.targetMailboxId ?: error("Missing rejection target")
                val id=eventID(packet.nonceOrIv)
                if(store.transaction { exists(it,"SELECT 1 FROM protocol_outbox WHERE event_id=? AND recipient=?",arrayOf(id,peer)) }) {
                    val proof=packet.encryptedData ?: error("Missing revocation proof")
                    require(proof.size==113)
                    markPeerRevoked(peer,android.util.Base64.encodeToString(proof,android.util.Base64.NO_WRAP))
                    flush()
                }
            }
            GhostPacket.TYPE_V2_STORED -> {
                require(packet.payloadLen == 0)
                val recipient=packet.targetMailboxId ?: error("Missing receipt target")
                val id=eventID(packet.nonceOrIv)
                val messageStored = store.transaction { db ->
                    db.execSQL("UPDATE protocol_outbox SET delivered=1 WHERE event_id=? AND recipient=? AND delivered>=0",arrayOf(id,recipient))
                    val matched = db.query("SELECT changes()").use { it.moveToFirst() && it.getInt(0) > 0 }
                    if (!matched) return@transaction null
                    db.execSQL("DELETE FROM object_transfers WHERE event_id=? AND direction=0",arrayOf(id))
                    db.execSQL("DELETE FROM live_location_outgoing WHERE event_id=? AND peer=?", arrayOf(id,recipient))
                    db.execSQL("UPDATE messages SET status=1 WHERE messageId=? AND conversationUuid=? AND isMine=1 AND isRevoked=0 AND status=0",arrayOf(id,recipient))
                    val stored = db.query("SELECT changes()").use { it.moveToFirst() && it.getInt(0) > 0 }
                    if (stored) db.execSQL("""
                        INSERT OR IGNORE INTO message_delivery_retries(message_id,retry_at)
                        SELECT message_id,? FROM message_sends WHERE message_id=? AND kind<>'chat.location.start'
                    """, arrayOf(now + DELIVERY_RETRY_DELAY, id))
                    stored
                } ?: return@network
                if (messageStored) _sendQueueChanges.tryEmit(Unit)
                inFlight.remove(id); flush()
            }
            GhostPacket.TYPE_V2_DELIVERY -> {
                val sender=packet.targetMailboxId ?: error("Missing sender")
                val id=eventID(packet.nonceOrIv)
                var notify = false;var silent=false
                var pinTarget: String? = null
                var call: SignalProtocolStore.Incoming? = null
                var contactUnavailable=false;var contactsChanged=false;var activityObservation:JSONObject?=null
                var locationChanged = false
                var captionChanged = false
                var messageReceipt = false
                val delivery = store.receive(packet.encryptedData ?: ByteArray(0),sender,id,duplicate=::rescheduleReceipt) { event,db ->
                    when (event.kind) {
                        "chat.activity" -> activityObservation = receiveActivity(event, db)
                        "chat.receipt" -> messageReceipt = applyDeliveryReceipt(event, db)
                        else -> apply(event, db) { pinTarget = it }
                    }
                    captionChanged = event.kind == "chat.edit" && event.body.optBoolean("caption")
                    locationChanged = event.kind == "chat.location.start" || event.kind == "chat.location.update" ||
                        event.kind == "chat.location.end" || event.kind == "chat.revoke"
                    contactsChanged=event.kind.startsWith("contact.")
                    contactUnavailable=contactsChanged && !exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(sender))
                    if (event.kind.startsWith("call.") && exists(db,
                        "SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0", arrayOf(sender))) call=event
                    silent=event.body.optBoolean("silent",false)
                    notify = !org.msgat.data.db.ConversationIndexStore.isMuted(db,sender,now) && event.kind in listOf("chat.text","chat.contact","chat.location","chat.location.start","chat.attachment") && exists(db,
                        "SELECT 1 FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=0 AND isRead=0",
                        arrayOf(id,sender))
                }
                if (delivery == SignalProtocolStore.Delivery.DEFERRED) return@network
                val fresh = delivery == SignalProtocolStore.Delivery.APPLIED
                if (fresh) activityObservation?.let { publishActivity(sender, it) }
                sendPacket(GhostPacket(GhostPacket.TYPE_V2_RECEIVED,sender,eventBytes(id),null,null))
                mailboxProgress(connection)
                // Only committed, newly inserted messages may alert. Notification
                // permission failure must not roll back state or prevent the ACK.
                if (fresh && messageReceipt) _sendQueueChanges.tryEmit(Unit)
                if(fresh && locationChanged)_liveLocationChanges.tryEmit(sender)
                if(fresh && captionChanged)objects.signalAvailable()
                if(fresh && contactsChanged){onContactsChanged();pruneActivity();_sendQueueChanges.tryEmit(Unit)}
                if(fresh && contactUnavailable){claimedBundles.remove(sender);scope.launch(Dispatchers.Main){org.msgat.core.call.CallManager.endUnavailablePeer(sender)}}
                val pinnedMessage = pinTarget
                if (fresh && (notify || pinnedMessage != null)) {
                    try { onMessageCommitted(sender,pinnedMessage ?: id,silent,pinnedMessage != null) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A committed message remains readable if notification delivery fails. */ }
                }
                if (fresh) call?.let { event -> withContext(Dispatchers.Main) {
                    org.msgat.core.call.CallSignalingHandler.getInstance().receive(event.sender,event.kind,event.body)
                } }
                attachments.signal()
                flush()
            }
            GhostPacket.TYPE_V2_SYNC_END -> {
                require(packet.payloadLen == 0 && packet.targetMailboxId == prefs.myUuid)
                val ordinary = syncNonce?.contentEquals(packet.nonceOrIv) == true
                val pushed = pushPull?.nonce?.contentEquals(packet.nonceOrIv) == true
                if (!ordinary && !pushed) return@network
                if (ordinary) syncNonce = null
                mailboxProgress(connection)
                stateChanged(epoch, SyncState.SYNCED)
                try { flush() }
                finally { if (pushed) endPushPull(success = true) }
            }
        }
    }

    suspend fun requireCallable(recipient: String): Boolean = userOperation {
        if (!ready || !store.transaction { exists(it,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(recipient)) })
            throw VisibleFailure("Connect and accept the contact before calling")
    }

    suspend fun sendCallSignal(recipient: String, kind: String, body: JSONObject, timestamp: Long): Boolean = userOperation {
        if (!ready || !kind.startsWith("call.") || body.getLong("expires_at")<=now)
            throw VisibleFailure("Call signaling expired or disconnected")
        store.transaction { db ->
            if (!exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(recipient)))
                throw VisibleFailure("Accept the contact before calling")
            store.enqueue(SignalProtocolStore.Pending(UUID.randomUUID().toString(),recipient,kind,body,timestamp),db)
        }
    }

    suspend fun sendContactCard(recipient: String,account: String,name: String,replyID: String?,
                                draft: org.msgat.data.db.DraftSendCommit?=null,options:SendOptions=SendOptions()): Boolean =
        sendText(recipient,name,replyID,draft,options=options,contactAccount=account)

    suspend fun sendLocation(recipient: String, point: LocationPoint, label: String?, replyID: String?,
                             draft: org.msgat.data.db.DraftSendCommit?=null, options: SendOptions=SendOptions()): Boolean =
        sendText(recipient,label ?: "Location",replyID,draft,options=options,location=point)

    internal suspend fun startLiveLocation(
        recipient: String, point: LocationPoint, durationMs: Long, label: String?, replyID: String?,
        draft: DraftSendCommit? = null, silent: Boolean = false
    ): LiveLocationShare? {
        var share: LiveLocationShare? = null
        val committed = userOperation(onFailure = { draft?.let { it.owner.observeFailedSend(it) } }) {
            require(durationMs in 1..(8L * 60 * 60 * 1000))
            if (draft != null && (draft.clearsText || draft.draft.peer != recipient ||
                    draft.draft.replyId != replyID || draft.clearRevision <= draft.draft.revision)) {
                throw VisibleFailure("The draft no longer matches the message")
            }
            store.publicBundle()
            val id = UUID.randomUUID().toString()
            val timestamp = now
            var expiresAt = timestamp + durationMs
            val body = JSONObject().put("latitude_e6", point.latitudeE6)
                .put("longitude_e6", point.longitudeE6).put("expires_at", expiresAt)
            if (!label.isNullOrBlank()) body.put("label", label)
            if (silent) body.put("silent", true)
            store.transaction { db ->
                if (!exists(db, "SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0", arrayOf(recipient))) {
                    throw VisibleFailure("Accept the contact request before sharing live location")
                }
                if (LiveLocationStore.hasActiveShare(db, recipient, timestamp)) {
                    throw VisibleFailure("Stop the current live location before sharing again")
                }
                if (draft != null && !exists(db, "SELECT 1 FROM conversation_drafts WHERE peer=? AND token=?",
                        arrayOf(recipient, draft.draft.token))) {
                    throw VisibleFailure("The conversation changed before the location was queued")
                }
                quote(db, recipient, replyID, draft?.draft?.replyQuote)?.let { body.put("reply", it) }
                val expiration = stampExpiration(db, recipient, body)
                if (expiration != 0L) {
                    expiresAt = minOf(expiresAt, timestamp + expiration)
                    body.put("expires_at", expiresAt)
                }
                val kind = "chat.location.start"
                check(store.enqueue(SignalProtocolStore.Pending(id, recipient, kind, body, timestamp), db))
                check(LiveLocationStore.apply(db, recipient, true, kind, id, body, timestamp, timestamp))
                insertText(db, id, recipient, true, body.optString("label", "Location"), timestamp,
                    body.optJSONObject("reply"), location = point, live = true)
                if (draft != null) ConversationDraftStore.clearForCommittedSend(db, draft)
            }
            share = LiveLocationShare(id, expiresAt)
            _liveLocationChanges.tryEmit(recipient)
            draft?.let { it.owner.observeCommittedSend(it) }
        }
        return if (committed) share else null
    }

    internal suspend fun liveLocation(recipient: String, messageId: String): LiveLocationSnapshot? =
        store.transaction { db -> LiveLocationStore.read(db, recipient, messageId) }

    internal suspend fun updateLiveLocation(recipient: String, messageId: String, point: LocationPoint): Boolean =
        changeLiveLocation(recipient, messageId, point)

    internal suspend fun stopLiveLocation(recipient: String, messageId: String): Boolean =
        changeLiveLocation(recipient, messageId, null)

    private suspend fun changeLiveLocation(recipient: String, messageId: String, point: LocationPoint?): Boolean {
        var active = point == null
        val completed = userOperation {
            val changed = store.transaction { db ->
                val state = LiveLocationStore.read(db, recipient, messageId, mineOnly = true) ?: return@transaction false
                val clock = now
                if (state.ended || clock >= state.expiresAt) return@transaction false
                if (!exists(db, "SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0", arrayOf(recipient))) {
                    return@transaction false
                }
                active = true
                val end = point == null
                if (end && exists(db, "SELECT 1 FROM protocol_pending_events WHERE event_id=? AND recipient=? AND kind='chat.location.start'",
                        arrayOf(messageId, recipient))) {
                    db.execSQL("DELETE FROM protocol_pending_events WHERE event_id=? AND recipient=?", arrayOf(messageId, recipient))
                    db.execSQL("DELETE FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=1",
                        arrayOf(messageId, recipient))
                    return@transaction true
                }
                if (!end && (point == state.point || state.revision >= 4095)) return@transaction false
                val id = UUID.randomUUID().toString()
                val kind = if (end) "chat.location.end" else "chat.location.update"
                val timestamp = maxOf(clock, state.startedAt)
                val body = JSONObject().put("message_id", messageId).put("started_at", state.startedAt)
                    .put("expires_at", state.expiresAt).put("revision", state.revision + 1)
                if (point != null) body.put("latitude_e6", point.latitudeE6).put("longitude_e6", point.longitudeE6)
                if (!LiveLocationStore.apply(db, recipient, true, kind, id, body, timestamp, clock)) {
                    return@transaction false
                }
                check(store.enqueue(SignalProtocolStore.Pending(id, recipient, kind, body, timestamp), db))
                LiveLocationStore.replaceOutgoing(db, id, recipient, messageId, state.expiresAt, end)
                true
            }
            if (changed) _liveLocationChanges.tryEmit(recipient)
        }
        return completed && active
    }

    suspend fun sendText(recipient: String,text: String,replyID: String?,draft: org.msgat.data.db.DraftSendCommit?=null,preview: WebPreviewDraft?=null,options:SendOptions=SendOptions(),contactAccount:String?=null,location:LocationPoint?=null,preparation:ObjectPreparation?=null): Boolean {
        val plainText = contactAccount == null && location == null
        require((contactAccount == null || location == null) && (plainText || preview == null))
        val image=preview?.image?.copyOf()
        val task=scope.async {
            var cover: JSONObject?=null;var committed=false
            try {
                if(image!=null)withContext(NonCancellable) {cover=objects.importBytes(image,"preview.jpg","image/jpeg",preparation)}
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {userOperation {
                    if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size>48*1024) throw VisibleFailure("Message text is empty or too large")
                    if (draft != null && (draft.clearsText != plainText || draft.draft.peer != recipient ||
                            plainText && draft.draft.text != text || draft.draft.replyId != replyID || draft.clearRevision <= draft.draft.revision))
                        throw VisibleFailure("The draft no longer matches the message")
                    store.publicBundle()
                    val id=UUID.randomUUID().toString(); val timestamp=now
                    val (kind, body) = when {
                        location != null -> "chat.location" to JSONObject().put("latitude_e6",location.latitudeE6)
                            .put("longitude_e6",location.longitudeE6).put("label",text)
                        contactAccount != null -> "chat.contact" to JSONObject().put("account",contactAccount).put("name",text)
                        else -> "chat.text" to JSONObject().put("text",text)
                    }
                    if(options.silent)body.put("silent",true)
                    if(preview!=null)body.put("preview",preview.body(cover))
                    store.transaction { db ->
                        if (!exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(recipient)))
                            throw VisibleFailure("Accept the contact request before sending messages")
                        if (contactAccount != null && contactAccount != prefs.myUuid &&
                            !exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(contactAccount)))
                            throw VisibleFailure("The shared contact is no longer available")
                        if (draft != null && !exists(db,"SELECT 1 FROM conversation_drafts WHERE peer=? AND token=?",arrayOf(recipient,draft.draft.token)))
                            throw VisibleFailure("The conversation changed before the message was queued")
                        quote(db, recipient, replyID, draft?.draft?.replyQuote)?.let { body.put("reply", it) }
                        stampExpiration(db, recipient, body)
                        preparation?.beginCommit()
                        val event=SignalProtocolStore.Pending(id,recipient,kind,body,timestamp)
                        check(store.enqueue(event,db)) { "The message could not be queued" }
                        if(!holdScheduled(event,options,db))insertText(db,id,recipient,true,text,timestamp,body.optJSONObject("reply"),body.optJSONObject("preview"),contactAccount,location)
                        if (draft != null) org.msgat.data.db.ConversationDraftStore.clearForCommittedSend(db, draft)
                    }
                    committed=true;attachments.signal()
                    draft?.let { it.owner.observeCommittedSend(it) }
                }}
            } finally {
                image?.fill(0)
                if (!committed) {
                    draft?.let { it.owner.observeFailedSend(it) }
                    cover?.let(objects::discardCopy)
                }
            }
        }
        return withContext(NonCancellable) {task.await()}
    }

    suspend fun sendSticker(recipient: String,stickerID: String,replyID: String?,draft: org.msgat.data.db.DraftSendCommit?=null,options:SendOptions=SendOptions(),preparation:ObjectPreparation?=null): Boolean {
        val job=scope.async {
            var copy: JSONObject?=null;var committed=false
            try {
                preparation?.check()
                if(draft!=null && (draft.clearsText || draft.draft.peer!=recipient || draft.draft.replyId!=replyID || draft.clearRevision<=draft.draft.revision))throw VisibleFailure("The draft no longer matches the sticker")
                val original=store.transaction {db ->
                    if(!exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(recipient)))throw VisibleFailure("Accept the contact before sending")
                    org.msgat.data.db.StickerLibraryStore.source(db,stickerID)?:throw VisibleFailure("Sticker is no longer available")
                }
                withContext(NonCancellable){copy=objects.copyObject(JSONObject(original.first),preparation)}
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable){userOperation(onFailure={draft?.let {it.owner.observeFailedSend(it)}}) {
                    store.publicBundle();val manifest=checkNotNull(copy);val id=UUID.randomUUID().toString();val created=now
                    preparation?.beginCommit()
                    store.transaction {db ->
                        if(!exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(recipient)))throw VisibleFailure("Contact changed before sending")
                        check(org.msgat.data.db.StickerLibraryStore.source(db,stickerID)?.second==original.second){"Sticker changed while preparing"}
                        if(draft!=null && !exists(db,"SELECT 1 FROM conversation_drafts WHERE peer=? AND token=?",arrayOf(recipient,draft.draft.token)))throw VisibleFailure("Conversation changed before sending")
                        val body=JSONObject().put("object",manifest).put("caption","").put("sticker",true)
                        val reply = quote(db, recipient, replyID, draft?.draft?.replyQuote)
                        if (reply != null) body.put("reply", reply)
                        if(options.silent)body.put("silent",true)
                        stampExpiration(db, recipient, body)
                        val event=SignalProtocolStore.Pending(id,recipient,"chat.attachment",body,created)
                        check(store.enqueue(event,db))
                        if(!holdScheduled(event,options,db)){EncryptedObjectStore.remember(manifest,db,true);insertAttachment(db,id,recipient,true,manifest,"",created,reply,true)}
                        db.execSQL("UPDATE sticker_items SET last_used=? WHERE id=?",arrayOf(created,stickerID));if(draft!=null)org.msgat.data.db.ConversationDraftStore.clearForCommittedSend(db,draft)
                    }
                    committed=true;attachments.signal();draft?.let {it.owner.observeCommittedSend(it)}
                }}
            } catch(error:Exception){preparation?.check();throw error}
            finally {try{if(!committed){try{draft?.let {it.owner.observeFailedSend(it)}}finally{copy?.let(objects::discardCopy)}}}finally{preparation?.close()}}
        }
        return withContext(NonCancellable){job.await()}
    }

    suspend fun sendMedia(
        recipient: String,
        uris: List<Uri>,
        replyID: String?,
        draft: org.msgat.data.db.DraftSendCommit? = null,
        options: SendOptions = SendOptions(),
        preparation: ObjectPreparation? = null,
        caption: String = "",
        asFile: Boolean = false,
        asAlbum: Boolean = false,
        compressPhotos: Boolean = false,
        compressVideos: Boolean = false,
        videoTrims: Map<Uri, VideoTrim> = emptyMap(),
        itemCaptions: List<String> = emptyList()
    ): Boolean {
        val captions = itemCaptions.toList()
        val task = scope.async {
            val imported = mutableListOf<JSONObject>()
            var committed = false
            try {
                require(uris.size in 1..10 && uris.distinct().size == uris.size) {
                    "Choose up to 10 different attachments"
                }
                require(videoTrims.keys.all { it in uris }) { "The selected videos have changed" }
                require(captions.isEmpty() || captions.size == uris.size) { "The selected attachments have changed" }
                require(caption.toByteArray(Charsets.UTF_8).size <= 48 * 1024 &&
                    captions.all { it.toByteArray(Charsets.UTF_8).size <= 48 * 1024 }) { "Caption is too long" }
                preparation?.check()
                if (draft != null && (draft.clearsText || draft.draft.peer != recipient ||
                        draft.draft.replyId != replyID || draft.clearRevision <= draft.draft.revision)) {
                    throw VisibleFailure("The draft no longer matches the attachments")
                }
                val acceptedContact = "SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0"
                if (!store.transaction { exists(it, acceptedContact, arrayOf(recipient)) }) {
                    throw VisibleFailure("Accept the contact request before sending messages")
                }
                for ((index, uri) in uris.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    preparation?.startAttachment(index + 1, uris.size)
                    withContext(NonCancellable) {
                        imported.add(objects.import(uri, preparation, asFile, compressPhotos, compressVideos, videoTrims[uri]))
                    }
                }
                currentCoroutineContext().ensureActive()
                val albumId = if (asAlbum) {
                    require(!asFile && imported.size >= 2 && imported.all { item ->
                        val mime = item.getString("mime")
                        mime.startsWith("image/") || mime.startsWith("video/")
                    }) { "An album needs two or more photos or videos" }
                    UUID.randomUUID().toString()
                } else null
                withContext(NonCancellable) {
                    userOperation(onFailure = { draft?.let { it.owner.observeFailedSend(it) } }) {
                        store.publicBundle()
                        val ids = imported.map { UUID.randomUUID().toString() }.sorted()
                        val timestamp = now
                        preparation?.beginCommit()
                        store.transaction { db ->
                            if (!exists(db, acceptedContact, arrayOf(recipient))) {
                                throw VisibleFailure("Accept the contact request before sending messages")
                            }
                            if (draft != null && !exists(db, "SELECT 1 FROM conversation_drafts WHERE peer=? AND token=?",
                                    arrayOf(recipient, draft.draft.token))) {
                                throw VisibleFailure("The conversation changed before the attachments were queued")
                            }
                            val firstReply = quote(db, recipient, replyID, draft?.draft?.replyQuote)
                            imported.forEachIndexed { index, manifest ->
                                val id = ids[index]
                                val itemCaption = captions.getOrNull(index) ?: if (index == 0) caption else ""
                                val itemReply = if (index == 0) firstReply else null
                                val body = JSONObject().put("object", manifest).put("caption", itemCaption)
                                val album = albumId?.let {
                                    JSONObject().put("id", it).put("index", index).put("count", imported.size)
                                }
                                if (album != null) body.put("album", album)
                                if (options.silent) body.put("silent", true)
                                if (itemReply != null) body.put("reply", itemReply)
                                stampExpiration(db, recipient, body)
                                val event = SignalProtocolStore.Pending(id, recipient, "chat.attachment", body, timestamp)
                                check(store.enqueue(event, db)) { "The attachments could not be queued" }
                                if (!holdScheduled(event, options, db)) {
                                    EncryptedObjectStore.remember(manifest, db, true)
                                    insertAttachment(db, id, recipient, true, manifest, itemCaption, timestamp, itemReply, album = album)
                                }
                            }
                            if (draft != null) org.msgat.data.db.ConversationDraftStore.clearForCommittedSend(db, draft)
                        }
                        committed = true
                        draft?.let { it.owner.observeCommittedSend(it) }
                        attachments.signal()
                    }
                }
            } catch (error: Exception) {
                preparation?.check()
                throw error
            } finally {
                try {
                    if (!committed) {
                        try {
                            draft?.let { it.owner.observeFailedSend(it) }
                        } finally {
                            imported.forEach { manifest ->
                                try {
                                    objects.discardCopy(manifest)
                                } catch (_: Exception) {
                                    android.util.Log.w("MsgAT_Preparation", "Staged attachment cleanup failed")
                                }
                            }
                        }
                    }
                } finally {
                    preparation?.close()
                }
            }
        }
        // Wait for all readers to finish before releasing transient document grants.
        return withContext(NonCancellable) { task.await() }
    }

    suspend fun forward(sourcePeer: String?,ids: List<String>,recipients: List<String>,hideSender: Boolean=false,hideCaption: Boolean=false,options:SendOptions=SendOptions(),omitPreview: Boolean=false): Boolean {
        return forwarding.enqueue(sourcePeer,ids,recipients,hideSender,hideCaption,options,omitPreview)
    }
    private suspend fun processForwardTask(jobID: String,request: ForwardQueueRequest,preparation:ObjectPreparation): Boolean {
            val sourcePeer=request.sourcePeer;val selected=request.ids;val targets=request.recipients;val hideSender=request.hideSender;val hideCaption=request.hideCaption
            val prepared=mutableListOf<Pair<String,List<org.msgat.data.db.PreparedMessageAction>>>()
            var committed=false
            try {
                require(targets.size in 1..20 && targets.distinct().size==targets.size) {"Select between 1 and 20 different recipients"}
                store.transaction {db ->
                    if(!exists(db,"SELECT 1 FROM forward_jobs WHERE id=? AND state='preparing'",arrayOf(jobID)))throw CancellationException("Forwarding task is no longer pending")
                    for(peer in targets) {
                        if(!exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(peer)))throw VisibleFailure("Accept the contact before forwarding")
                    }
                }
                val source=actions.capture(sourcePeer,selected,includePreview=!request.omitPreview)
                ForwardTaskQueue.requireSnapshot(source,request)
                actions.requireCopySpace(source,targets.size,includePreview=!request.omitPreview)
                val count=targets.size*(source.count {it.manifest!=null}+source.count {!request.omitPreview && it.previewManifest!=null})
                var index=0
                for(peer in targets) {
                    prepared += peer to actions.prepare(source, preparation, includePreview = !request.omitPreview, detectDamagedCache = true) {
                        preparation.startAttachment(++index, count)
                    }
                }
                currentCoroutineContext().ensureActive()
                return withContext(NonCancellable) {userOperation {
                    store.publicBundle()
                    withContext(NonCancellable) {
                        store.transaction {db ->
                            check(exists(db,"SELECT 1 FROM forward_jobs WHERE id=? AND state='preparing'",arrayOf(jobID))){"Forwarding task is no longer pending"}
                            preparation.beginCommit()
                            val timestamp=now
                            for((peer,rows) in prepared) {
                                if(!exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(peer))) throw VisibleFailure("Accept the contact before forwarding")
                                val newIds=rows.map {UUID.randomUUID().toString()}.sorted()
                                rows.forEachIndexed {index,row ->
                                    actions.requireCurrent(db,row.source)
                                    val id=newIds[index]
                                    val card = row.source.contactAccount
                                    val point = row.source.location
                                    val (kind, body) = when {
                                        point != null -> "chat.location" to JSONObject().put("latitude_e6",point.latitudeE6)
                                            .put("longitude_e6",point.longitudeE6).put("label",row.source.text)
                                        card != null -> "chat.contact" to JSONObject().put("account",card).put("name",row.source.text)
                                        row.content != null -> "chat.attachment" to JSONObject().put("object",row.content)
                                            .put("caption",if(hideCaption)"" else row.source.caption)
                                        else -> "chat.text" to JSONObject().put("text",row.source.text)
                                    }
                                    if(request.options.silent)body.put("silent",true)
                                    if(row.source.isSticker)body.put("sticker",true)
                                    if (!request.omitPreview) actions.previewBody(row)?.let {body.put("preview",it)}
                                    if(!hideSender) body.put("forward",actions.forwardHeader(row.source))
                                    stampExpiration(db, peer, body)
                                    val event=SignalProtocolStore.Pending(id,peer,kind,body,timestamp)
                                    check(store.enqueue(event,db)) {"The forwarded message could not be queued"}
                                    if(!holdScheduled(event,request.options,db)) {
                                        if(row.content==null) insertText(db,id,peer,true,row.source.text,timestamp,null,body.optJSONObject("preview"),card,point)
                                        else {
                                            EncryptedObjectStore.remember(row.content,db,true)
                                            insertAttachment(db,id,peer,true,row.content,if(hideCaption)"" else row.source.caption,timestamp,null,row.source.isSticker)
                                        }
                                        saveForward(db,id,body.optJSONObject("forward"))
                                    }
                                }
                            }
                            db.execSQL("DELETE FROM forward_jobs WHERE id=?",arrayOf(jobID))
                        }
                        committed=true
                    }
                    attachments.signal()
                }}
            } catch (damage: MessageActionsStore.DamagedForwardCache) {
                currentCoroutineContext().ensureActive()
                preparation.check()
                return withContext(NonCancellable) { userOperation {
                    store.transaction { db ->
                        check(exists(db, """SELECT 1 FROM forward_jobs
                            WHERE id=? AND state='preparing' AND error_code IS NULL""", arrayOf(jobID))) {
                            "The forwarding cache could not be restored"
                        }
                        ForwardTaskQueue.requireRecipients(db, targets)
                        actions.requireCurrent(db, damage.source)
                        check(EncryptedObjectLifecycle.queueCacheReplacement(db, damage.source.peer, damage.source.id, now,
                                previewOnly = damage.previewOnly) &&
                            AttachmentQueue.setDownloadRequested(db, damage.source.peer, damage.source.id, requested = true,
                                previewOnly = damage.previewOnly)) { "The forwarding cache could not be restored" }
                        db.execSQL("UPDATE forward_jobs SET state='waiting',error_code='cache_repair_attempted' WHERE id=?", arrayOf(jobID))
                    }
                    objects.signalCacheCleared()
                    storageWake.trySend(Unit)
                }}
            } finally {if(!committed) prepared.forEach {actions.discard(it.second)}}
    }

    suspend fun editMessage(recipient: String, messageID: String, text: String): Boolean = changeMessage(recipient, messageID, text)
    suspend fun revokeMessage(recipient: String, messageID: String): Boolean = changeMessage(recipient, messageID, null)
    internal suspend fun revokeSentMessages(recipient: String, messageIds: List<String>): Boolean = userOperation {
        val ids = messageIds.toSet()
        require(ids.size in 1..100)
        store.transaction { db ->
            requireMessageChangeContact(db, recipient)
            val timestamp = now
            for (id in ids) {
                if (!exists(db, """
                    SELECT 1 FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=1
                    AND isRevoked=0 AND status BETWEEN 1 AND 3 AND ext<>'live_location'
                    AND NOT EXISTS(SELECT 1 FROM message_expiration
                        WHERE message_id=messages.messageId AND expires_at<=?)
                """.trimIndent(), arrayOf(id, recipient, timestamp)))
                    throw VisibleFailure("Some selected messages can no longer be revoked")
                enqueueMessageChange(db, recipient, id, text = null, caption = false)
            }
        }
    }
    suspend fun setMessagePinned(recipient:String,messageID:String,pinned:Boolean,notify:Boolean=false):Boolean=userOperation {
        store.transaction{db->
            if(!exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(recipient)))throw VisibleFailure("Accept the contact request before pinning messages")
            val body=org.msgat.data.db.MessagePinsStore.body(db,recipient,prefs.myUuid,messageID,pinned,notify)
            if(store.enqueue(pending(recipient,"chat.pin",body),db) && !org.msgat.data.db.MessagePinsStore.apply(db,recipient,prefs.myUuid,prefs.myUuid,body))throw VisibleFailure("This message cannot be pinned")
        }
    }
    suspend fun pinnedMessages(recipient:String,offset:Int=0):org.msgat.data.db.PinnedMessagePage {
        require(offset>=0)
        val result=CompletableDeferred<org.msgat.data.db.PinnedMessagePage>()
        commands.send(Work({result.complete(store.transaction{db->org.msgat.data.db.MessagePinsStore.list(db,recipient,prefs.myUuid,offset)})},
            {result.completeExceptionally(IllegalStateException("Pinned messages are unavailable"))}))
        return result.await()
    }
    private suspend fun changeMessage(recipient: String, messageID: String, text: String?): Boolean = userOperation {
        if (text != null && text.toByteArray(Charsets.UTF_8).size > 48 * 1024)
            throw VisibleFailure("Message text is too large")
        var captionChanged = false
        store.transaction { db ->
            requireMessageChangeContact(db, recipient)
            val kind = db.query(SimpleSQLiteQuery(
                "SELECT ext FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=1 AND isRevoked=0",
                arrayOf(messageID, recipient)
            )).use { if (it.moveToFirst()) it.getString(0) else null } ?: throw VisibleFailure("This message cannot be changed")
            val caption = text != null && kind == "attachment" &&
                exists(db, """SELECT 1 FROM message_attachments WHERE message_id=?1
                    AND NOT EXISTS(SELECT 1 FROM message_stickers WHERE message_id=?1)""", arrayOf(messageID))
            if (text != null && !caption && (kind != "txt" || text.isBlank()))
                throw VisibleFailure("This message cannot be edited")
            if (text != null && MessageExpirationStore.isExpiring(db, messageID))
                throw VisibleFailure("Messages set to auto-delete cannot be edited")
            enqueueMessageChange(db, recipient, messageID, text, caption)
            captionChanged = caption
        }
        if (text == null) _liveLocationChanges.tryEmit(recipient)
        if (captionChanged) objects.signalAvailable()
    }
    private fun requireMessageChangeContact(db: SupportSQLiteDatabase, recipient: String) {
        if (!exists(db, "SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0", arrayOf(recipient)))
            throw VisibleFailure("Accept the contact request before changing messages")
    }

    private fun enqueueMessageChange(db: SupportSQLiteDatabase, recipient: String, messageId: String, text: String?, caption: Boolean) {
        val previous = savedChange(db, prefs.myUuid, messageId)
        val revision = (previous?.getLong("revision") ?: 0L) + 1
        if (revision > 0xffffffffL) throw VisibleFailure("The message revision limit was reached")
        val body = JSONObject().put("message_id", messageId).put("revision", revision)
        if (text != null) body.put("text", text)
        val change = JSONObject().put("revision", revision).put("revoked", text == null).put("text", text ?: JSONObject.NULL)
        if (caption) { body.put("caption", true); change.put("caption", true) }
        if (!store.enqueue(pending(recipient, if (text == null) "chat.revoke" else "chat.edit", body), db))
            throw VisibleFailure("The message change could not be queued")
        saveChange(db, recipient, prefs.myUuid, true, messageId, change)
    }

    suspend fun requestContact(recipient: String): Boolean = contactOperation {
        if(recipient==prefs.myUuid)throw VisibleFailure("You cannot add your own identity")
        store.publicBundle()
        store.transaction {db->
            val state=ContactStateStore.apply(db,recipient,JSONObject().put("kind","request").put("id",UUID.randomUUID().toString())).getJSONObject("state")
            db.execSQL("DELETE FROM object_transfers WHERE direction=0 AND event_id IN (SELECT event_id FROM protocol_pending_events WHERE recipient=? AND kind='contact.request')",arrayOf(recipient))
            db.execSQL("DELETE FROM protocol_pending_events WHERE recipient=? AND kind='contact.request'",arrayOf(recipient))
            val body=UserProfileStore.body(db,prefs.myUuid,prefs.myNickname?:"Unknown").put("request_id",state.getString("outbound_id")).put("request_revision",state.getLong("outbound_revision"))
            check(store.enqueue(pending(recipient,"contact.request",body),db))
            db.execSQL("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES(?,'Pending request','','') ON CONFLICT(uuid) DO NOTHING",arrayOf(recipient))
        }
    }

    suspend fun acceptContact(recipient: String): Boolean = contactOperation {
        store.transaction {db->
            val current=UserProfileStore.read(db,recipient)?:throw VisibleFailure("The contact request is unavailable")
            val state=ContactStateStore.apply(db,recipient,JSONObject().put("kind","accept")).getJSONObject("state")
            val body=UserProfileStore.body(db,prefs.myUuid,prefs.myNickname?:"Unknown").put("request_id",state.getString("inbound_id"))
            check(store.enqueue(pending(recipient,"contact.accept",body),db))
            db.execSQL("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES(?,?,?,'') ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias,avatarPath=excluded.avatarPath",arrayOf(recipient,current.nickname,current.avatarReference?:""))
        }
        if(ready)pull()
    }

    suspend fun declineContact(recipient: String): Boolean = contactOperation {
        store.transaction {db->
            val state=ContactStateStore.apply(db,recipient,JSONObject().put("kind","decline")).getJSONObject("state")
            check(store.enqueue(pending(recipient,"contact.decline",JSONObject().put("request_id",state.getString("inbound_id"))),db))
        }
    }

    suspend fun withdrawContact(recipient: String): Boolean = contactOperation {
        store.transaction {db->
            val state=ContactStateStore.apply(db,recipient,JSONObject().put("kind","withdraw")).getJSONObject("state")
            cancelContactQueue(db,recipient)
            check(store.enqueue(pending(recipient,"contact.withdraw",JSONObject().put("request_id",state.getString("outbound_id")).put("request_revision",state.getLong("outbound_revision"))),db))
        }
        attachments.signal()
    }

    suspend fun setContactBlocked(recipient: String,value: Boolean): Boolean = contactOperation {
        store.transaction {db->ContactStateStore.apply(db,recipient,JSONObject().put("kind",if(value)"block" else "unblock"))}
        claimedBundles.remove(recipient);requested.remove(recipient);unavailableUntil.remove(recipient)
        if(value)activityReceived.value=activityReceived.value-recipient
        if(value)scope.launch(Dispatchers.Main){org.msgat.core.call.CallManager.endUnavailablePeer(recipient)}
        attachments.signal()
    }

    suspend fun deleteContact(recipient: String): Boolean = contactOperation {
        store.transaction {db->
            val previous=ContactStateStore.apply(db,recipient,JSONObject().put("kind","delete")).getJSONObject("previous")
            cancelContactQueue(db,recipient)
            when {
                exists(db,"SELECT 1 FROM protocol_revocations WHERE peer=?",arrayOf(recipient))->Unit
                previous.getBoolean("accepted")->check(store.enqueue(pending(recipient,"contact.delete",JSONObject().put("relationship_ids",previous.getJSONArray("accepted_ids"))),db))
                previous.getBoolean("outgoing") && !previous.isNull("outbound_id")->check(store.enqueue(pending(recipient,"contact.withdraw",JSONObject().put("request_id",previous.getString("outbound_id")).put("request_revision",previous.getLong("outbound_revision"))),db))
                previous.getBoolean("incoming") && !previous.isNull("inbound_id")->check(store.enqueue(pending(recipient,"contact.decline",JSONObject().put("request_id",previous.getString("inbound_id"))),db))
            }
            db.execSQL("DELETE FROM user_profiles WHERE peer=?",arrayOf(recipient))
            db.execSQL("DELETE FROM conversations WHERE uuid=?",arrayOf(recipient));clearHistory(db,recipient)
        }
        org.msgat.core.call.CallHistory.changed()
        claimedBundles.remove(recipient);requested.remove(recipient);unavailableUntil.remove(recipient)
        scope.launch(Dispatchers.Main){org.msgat.core.call.CallManager.endUnavailablePeer(recipient)}
        attachments.signal()
    }

    private suspend fun contactOperation(block:suspend()->Unit)=userOperation {block();onContactsChanged();pruneActivity()}
    private suspend fun pruneActivity(){
        val current=activityReceived.value
        val allowed=store.transaction {db->current.keys.filter{exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(it))}.toSet()}
        activityReceived.value=current.filterKeys{it in allowed}
    }

    private fun cancelContactQueue(db: SupportSQLiteDatabase,peer: String){
        db.execSQL("DELETE FROM protocol_pending_events WHERE recipient=?",arrayOf(peer));db.execSQL("DELETE FROM protocol_outbox WHERE recipient=?",arrayOf(peer))
        db.execSQL("DELETE FROM protocol_key_requests WHERE recipient=?",arrayOf(peer));db.execSQL("DELETE FROM object_transfers WHERE owner_peer=?",arrayOf(peer))
        db.execSQL("UPDATE protocol_receipts SET pending=0 WHERE peer=?",arrayOf(peer))
    }

    suspend fun clearMessages(recipient: String): Boolean = userOperation {
        store.transaction { clearHistory(it, recipient) }
        org.msgat.core.call.CallHistory.changed()
        _liveLocationChanges.tryEmit(recipient)
    }

    // Null means the selection cannot be deleted. False means local deletion only.
    internal suspend fun deletionCanRevoke(peer: String, ids: Set<String>): Boolean? {
        require(ids.size in 1..100)
        return store.transaction { db ->
            db.query(SimpleSQLiteQuery("""
                SELECT CASE
                    WHEN COUNT(*)<>?1 OR MAX(m.ext='live_location' OR (m.isMine=1 AND m.status=0))=1 THEN 0
                    WHEN MIN(m.isMine=1 AND m.isRevoked=0 AND m.status BETWEEN 1 AND 3)=1
                        AND EXISTS(SELECT 1 FROM contact_states WHERE uuid=?3 AND accepted=1 AND blocked=0) THEN 2
                    ELSE 1 END
                FROM messages m JOIN json_each(?2) s ON s.value=m.messageId
                WHERE m.conversationUuid=?3 AND NOT EXISTS(SELECT 1 FROM message_expiration
                    WHERE message_id=m.messageId AND expires_at<=?4)
            """.trimIndent(), arrayOf(ids.size, org.json.JSONArray(ids.toList()).toString(), peer, now))).use { row ->
                check(row.moveToFirst())
                when (row.getInt(0)) { 0 -> null; 2 -> true; else -> false }
            }
        }
    }

    internal suspend fun deleteLocalMessages(peer: String, messageIds: List<String>): Boolean = userOperation {
        val ids = messageIds.toSet()
        require(ids.size in 1..100)
        val targets = org.json.JSONArray(ids.toList()).toString()
        val tokens = store.transaction { db ->
            val rows = db.query(SimpleSQLiteQuery("""
                SELECT m.isMine, m.status, m.ext, n.token
                FROM messages m JOIN json_each(?) s ON s.value=m.messageId
                LEFT JOIN notification_targets n ON n.message_id=m.messageId
                WHERE m.conversationUuid=?
            """.trimIndent(), arrayOf(targets, peer))).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        if ((rows.getInt(0) != 0 && rows.getInt(1) == 0) || rows.getString(2) == "live_location")
                            throw VisibleFailure("A pending send or live location cannot be deleted from history.")
                        add(if (rows.isNull(3)) null else rows.getString(3))
                    }
                }
            }
            if (rows.size != ids.size) throw VisibleFailure("Some selected messages are no longer available.")
            val arguments = arrayOf<Any?>(peer, targets)
            db.execSQL("UPDATE messages SET replyToContent=NULL WHERE conversationUuid=? AND replyToId IN (SELECT value FROM json_each(?))", arguments)
            db.execSQL("DELETE FROM message_changes WHERE conversation_uuid=? AND message_id IN (SELECT value FROM json_each(?))", arguments)
            db.execSQL("DELETE FROM messages WHERE conversationUuid=? AND messageId IN (SELECT value FROM json_each(?))", arguments)
            rows.filterNotNull()
        }
        notifyRemovedMessages(ids, tokens)
    }

    suspend fun readReceiptsEnabled(): Boolean {
        val result=CompletableDeferred<Boolean>()
        commands.send(Work({result.complete(store.transaction { readReceiptsEnabled(it) })},
            {result.completeExceptionally(IllegalStateException("Messaging is unavailable"))}))
        return result.await()
    }

    internal suspend fun messageNotificationSettings() =
        store.transaction { org.msgat.data.db.MessageNotificationStore.settings(it) }

    internal suspend fun setMessageNotificationSettings(settings: org.msgat.data.db.MessageNotificationStore.Settings): Boolean = userOperation {
        val previous = store.transaction { db ->
            val value = org.msgat.data.db.MessageNotificationStore.settings(db)
            org.msgat.data.db.MessageNotificationStore.setSettings(db, settings)
            value
        }
        if (!settings.enabled || (previous.showPreviews && !settings.showPreviews)) {
            runCatching { org.msgat.NotificationHelper.clearMessageNotifications(org.msgat.MsgATApp.instance.applicationContext) }
        }
    }

    internal suspend fun automaticAttachmentDownloads(): Boolean = store.transaction { AttachmentQueue.autoDownloadEnabled(it) }

    internal suspend fun appLockEnabled(): Boolean = store.transaction {
        exists(it, "SELECT 1 FROM privacy_preferences WHERE id=1 AND app_lock_enabled=1", emptyArray())
    }

    internal suspend fun setAppLockEnabled(enabled: Boolean): Boolean = userOperation {
        store.transaction { db ->
            db.execSQL("UPDATE privacy_preferences SET app_lock_enabled=? WHERE id=1", arrayOf(if (enabled) 1 else 0))
        }
        if (enabled) {
            runCatching { org.msgat.NotificationHelper.clearMessageNotifications(org.msgat.MsgATApp.instance.applicationContext) }
        }
    }

    internal suspend fun attachmentCacheUsage(): AttachmentCacheUsage =
        store.transaction { EncryptedObjectLifecycle.cacheUsage(it) }

    internal suspend fun setAutomaticCacheLimit(bytes: Long): Boolean = userOperation {
        require(bytes in EncryptedObjectLifecycle.automaticLimits)
        store.transaction { db ->
            db.execSQL("UPDATE privacy_preferences SET auto_download_limit_bytes=? WHERE id=1", arrayOf(bytes))
        }
        attachments.signal()
    }

    internal suspend fun clearAttachmentCache(categories: Set<AttachmentCacheCategory>): Boolean = userOperation {
        val count = store.transaction { EncryptedObjectLifecycle.queueCacheClear(it, categories) }
        if (count > 0) {
            objects.signalCacheCleared()
            storageWake.trySend(Unit)
        }
    }

    internal suspend fun requestAttachmentDownload(peer: String, messageId: String, requested: Boolean = true): Boolean = userOperation {
        val changed = store.transaction { AttachmentQueue.setDownloadRequested(it, peer, messageId, requested) }
        if (!changed && requested) throw VisibleFailure("This attachment is no longer waiting to download.")
        if (changed) objects.signalAvailable()
        attachments.signal()
    }

    internal suspend fun setPreviewDownloadRequested(peer: String, messageId: String, reference: String,
                                                     requested: Boolean, replacingCache: Boolean = false): Boolean = userOperation {
        require(requested || !replacingCache)
        store.transaction { db ->
            if (WebPreviewStore.read(db, messageId)?.imageReference != reference ||
                replacingCache && !EncryptedObjectLifecycle.queueCacheReplacement(db, peer, messageId, now, previewOnly = true) ||
                !AttachmentQueue.setDownloadRequested(db, peer, messageId, requested = requested, previewOnly = true)) {
                throw VisibleFailure("The preview image is no longer waiting to download.")
            }
        }
        if (replacingCache) {
            objects.signalCacheCleared()
            storageWake.trySend(Unit)
        } else {
            objects.signalAvailable()
            attachments.signal()
        }
    }

    internal suspend fun replaceAttachmentCache(peer: String, messageId: String, expectedReference: String? = null): Boolean = userOperation {
        store.transaction { db ->
            if (expectedReference != null && !exists(db,
                    "SELECT 1 FROM messages WHERE messageId=? AND conversationUuid=? AND content=?",
                    arrayOf(messageId, peer, expectedReference))) {
                throw VisibleFailure("This attachment cannot be downloaded again.")
            }
            if (!EncryptedObjectLifecycle.queueCacheReplacement(db, peer, messageId, now) ||
                !AttachmentQueue.setDownloadRequested(db, peer, messageId, true)) {
                throw VisibleFailure("This attachment cannot be downloaded again.")
            }
        }
        objects.signalCacheCleared()
        storageWake.trySend(Unit)
    }

    suspend fun setReadReceiptsEnabled(enabled: Boolean,activity:ActivityPrivacy?=null, autoDownload: Boolean? = null): Boolean = userOperation {
        val dirty=mutableListOf<String>()
        store.transaction { db ->
            db.execSQL("UPDATE privacy_preferences SET read_receipts=? WHERE id=1",arrayOf(if(enabled)1 else 0))
            if (autoDownload != null)
                db.execSQL("UPDATE privacy_preferences SET auto_download_attachments=? WHERE id=1", arrayOf(if (autoDownload) 1 else 0))
            if(!enabled) {
                // A local read must never become a delayed disclosure after the switch is enabled again.
                db.execSQL("WITH undisclosed AS (SELECT event_id FROM protocol_receipts WHERE state='read' AND pending=1 UNION SELECT p.event_id FROM protocol_pending_events p JOIN protocol_receipts r ON r.event_id=p.event_id WHERE r.state='read' UNION SELECT b.event_id FROM protocol_outbox b JOIN protocol_receipts r ON r.event_id=b.event_id WHERE b.delivered IN (0,-2) AND r.state='read') INSERT OR IGNORE INTO read_receipt_suppression(peer,message_id) SELECT r.peer,r.message_id FROM undisclosed u JOIN protocol_receipts r ON r.event_id=u.event_id")
                db.execSQL("WITH undisclosed AS (SELECT event_id FROM protocol_receipts WHERE state='read' AND pending=1 UNION SELECT p.event_id FROM protocol_pending_events p JOIN protocol_receipts r ON r.event_id=p.event_id WHERE r.state='read' UNION SELECT b.event_id FROM protocol_outbox b JOIN protocol_receipts r ON r.event_id=b.event_id WHERE b.delivered IN (0,-2) AND r.state='read') UPDATE protocol_receipts SET pending=1 WHERE event_id IN (SELECT d.event_id FROM undisclosed u JOIN protocol_receipts r ON r.event_id=u.event_id JOIN protocol_receipts d ON d.peer=r.peer AND d.message_id=r.message_id AND d.state='delivered')")
                db.execSQL("DELETE FROM protocol_pending_events WHERE event_id IN (SELECT p.event_id FROM protocol_pending_events p JOIN protocol_receipts r ON r.event_id=p.event_id WHERE r.state='read')")
                db.execSQL("UPDATE protocol_outbox SET delivered=-1 WHERE delivered IN (0,-2) AND EXISTS(SELECT 1 FROM protocol_receipts r WHERE r.event_id=protocol_outbox.event_id AND r.state='read')")
                db.execSQL("UPDATE protocol_receipts SET pending=0 WHERE state='read' AND pending=1")
            }
            if(activity!=null){
                db.execSQL("UPDATE privacy_preferences SET share_online=?,share_typing=? WHERE id=1",arrayOf(if(activity.online)1 else 0,if(activity.typing)1 else 0))
                db.query("SELECT peer FROM activity_peers WHERE json_extract(state_json,'$.published.body.online') IS NOT NULL OR json_extract(state_json,'$.published.body.typing')=1 LIMIT 128").use {c->while(c.moveToNext())dirty.add(c.getString(0))}
            }
        }
        if (autoDownload != null) attachments.signal()
        if(activity!=null){synchronized(activityGate){activityDirty.addAll(dirty);while(activityDirty.size>128)activityDirty.remove(activityDirty.first())};activityOnline=activity.online;activityTyping=activity.typing;activityLoaded=true}
    }

    suspend fun markAllRead(recipient:String):Boolean=userOperation {
        val token = store.transaction{db->
            val disclose=readReceiptsEnabled(db)
            while(true){
                val ids = db.query(SimpleSQLiteQuery("""
                    SELECT m.messageId FROM messages m
                    WHERE m.conversationUuid=? AND m.isMine=0 AND m.isRead=0 AND m.isRevoked=0
                      AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?2)
                      AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                          AND m.timestamp<=?2-r.duration_ms)
                    ORDER BY m.timestamp,m.messageId LIMIT 100
                    """, arrayOf(recipient, now))).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                if(ids.isEmpty())break
                for(id in ids){if(!disclose)db.execSQL("INSERT OR IGNORE INTO read_receipt_suppression(peer,message_id) VALUES(?,?)",arrayOf(recipient,id));receipt(db,recipient,id,"read");db.execSQL("UPDATE messages SET isRead=1 WHERE messageId=? AND conversationUuid=? AND isMine=0",arrayOf(id,recipient))}
            }
            db.execSQL("UPDATE conversation_preferences SET manual_unread=0 WHERE peer=?",arrayOf(recipient))
            org.msgat.data.db.MessageNotificationStore.tokenForReadMessage(db, recipient)
        }
        dismissReadNotification(token)
    }

    suspend fun markRead(recipient: String, visibleIds: Collection<String>): Set<String>? {
        require(visibleIds.size<=100)
        val ids=visibleIds.distinct().toList()
        if (ids.isEmpty()) return emptySet()
        var committed: Set<String> = emptySet()
        val succeeded = userOperation {
            val (read, token) = store.transaction { db ->
                val disclose=readReceiptsEnabled(db)
                val read = mutableSetOf<String>()
                val at = now
                for(id in ids) {
                    if (!exists(db, """
                        SELECT 1 FROM messages m
                        WHERE m.messageId=? AND m.conversationUuid=? AND m.isMine=0 AND m.isRead=0 AND m.isRevoked=0
                          AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?3)
                          AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                              AND m.timestamp<=?3-r.duration_ms)
                        """, arrayOf(id, recipient, at))) continue
                    if(!disclose)db.execSQL("INSERT OR IGNORE INTO read_receipt_suppression(peer,message_id) VALUES(?,?)",arrayOf(recipient,id))
                    receipt(db,recipient,id,"read")
                    db.execSQL("UPDATE messages SET isRead=1 WHERE messageId=? AND conversationUuid=? AND isMine=0 AND isRead=0",arrayOf(id,recipient))
                    read.add(id)
                }
                read to org.msgat.data.db.MessageNotificationStore.tokenForReadMessage(db, recipient)
            }
            committed = read
            dismissReadNotification(token)
        }
        return if (succeeded) committed else null
    }

    private fun dismissReadNotification(token: String?) {
        if (token != null) runCatching {
            org.msgat.NotificationHelper.dismissMessageNotifications(org.msgat.MsgATApp.instance.applicationContext, listOf(token))
        }
    }

    suspend fun updateProfile(nickname: String,jpeg: ByteArray?=null,removeAvatar: Boolean=false): Boolean {
        val task=scope.async {
            var avatar: JSONObject?=null;var committed=false
            try {
                if(jpeg!=null) {require(jpeg.size in 1..512*1024);withContext(NonCancellable) {avatar=objects.importBytes(jpeg,"avatar.jpg","image/jpeg")}}
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {userOperation {
                    store.transaction {db ->
                        val profile=UserProfileStore.saveLocal(db,prefs.myUuid,nickname,avatar,removeAvatar || avatar!=null)
                        val body=UserProfileStore.body(db,prefs.myUuid,nickname)
                        val peers=db.query("SELECT uuid FROM contact_states WHERE accepted=1 AND blocked=0").use {c -> buildList {while(c.moveToNext())add(c.getString(0))}}
                        for(peer in peers)store.enqueue(pending(peer,"contact.profile",body),db)
                        profile
                    }.also {prefs.installProfile(it)}
                    committed=true;attachments.signal()
                }}
            } finally {if(!committed)avatar?.let(objects::discardCopy)}
        }
        return withContext(NonCancellable) {task.await()}
    }

    fun broadcastProfile() = network(epoch) {
        val peers=store.transaction { db -> db.query("SELECT uuid FROM contact_states WHERE accepted=1 AND blocked=0").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } } }
        for (peer in peers) store.enqueue(pending(peer,"contact.profile",profile()))
        flush()
    }

    private fun apply(event: SignalProtocolStore.Incoming, db: SupportSQLiteDatabase, onPinNotify:(String)->Unit) {
        if (exists(db,"SELECT 1 FROM protocol_revocations WHERE peer=?",arrayOf(event.sender))) return
        val arrival = now
        val deadline = MessageExpirationStore.incomingDeadline(event.body, event.createdAt, arrival)
        if (deadline != null) {
            db.execSQL("""DELETE FROM message_changes WHERE author=? AND conversation_uuid=? AND message_id=?
                AND (?<=? OR json_extract(state_json,'$.revoked')=0)""",
                arrayOf(event.sender, event.sender, event.eventID, deadline, arrival))
            if (deadline <= arrival) return
        }
        if (MessageExpirationStore.discardIncoming(db, event.sender, event.kind, event.createdAt, arrival)) {
            db.execSQL("DELETE FROM message_changes WHERE author=? AND conversation_uuid=? AND message_id=?",
                arrayOf(event.sender, event.sender, event.eventID))
            receipt(db, event.sender, event.eventID, "delivered")
            return
        }
        when (event.kind) {
            "call.offer", "call.answer", "call.ice", "call.challenge", "call.confirm", "call.reject", "call.end", "call.busy" -> Unit // Dispatch only after Signal and database commit.
            "contact.request" -> {
                val result=ContactStateStore.apply(db,event.sender,JSONObject().put("kind","receive_request").put("id",event.body.getString("request_id")).put("revision",event.body.getLong("request_revision")))
                if(!result.getBoolean("applied"))return
                val profile=UserProfileStore.apply(db,event.sender,event.body,event.eventID,event.createdAt)
                db.execSQL("INSERT INTO friend_requests(senderUuid,nickname,avatarPath,peerPublicKey,timestamp) VALUES(?,?,?,'',?) ON CONFLICT(senderUuid) DO UPDATE SET nickname=excluded.nickname,avatarPath=excluded.avatarPath,timestamp=excluded.timestamp",arrayOf(event.sender,profile.nickname,profile.avatarReference?:"",event.createdAt))
            }
            "contact.accept" -> {
                if(!ContactStateStore.apply(db,event.sender,JSONObject().put("kind","receive_accept").put("id",event.body.getString("request_id"))).getBoolean("applied"))return
                val profile=UserProfileStore.apply(db,event.sender,event.body,event.eventID,event.createdAt)
                db.execSQL("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES(?,?,?,'') ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias,avatarPath=excluded.avatarPath",arrayOf(event.sender,profile.nickname,profile.avatarReference?:""))
            }
            "contact.decline" -> {ContactStateStore.apply(db,event.sender,JSONObject().put("kind","receive_decline").put("id",event.body.getString("request_id")))}
            "contact.withdraw" -> {ContactStateStore.apply(db,event.sender,JSONObject().put("kind","receive_withdraw").put("id",event.body.getString("request_id")).put("revision",event.body.getLong("request_revision")))}
            "contact.profile" -> {
                if(!exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(event.sender)))return
                val profile=UserProfileStore.apply(db,event.sender,event.body,event.eventID,event.createdAt)
                db.execSQL("UPDATE conversations SET alias=?,avatarPath=? WHERE uuid=?",arrayOf(profile.nickname,profile.avatarReference?:"",event.sender))
            }
            "contact.delete" -> {ContactStateStore.apply(db,event.sender,JSONObject().put("kind","receive_delete").put("ids",event.body.optJSONArray("relationship_ids")?:org.json.JSONArray()))}
            "chat.text", "chat.contact", "chat.location", "chat.location.start" -> {
                val contact = event.kind == "chat.contact"
                val live = event.kind == "chat.location.start"
                val location = if (event.kind == "chat.location") locationPoint(event.body) else null
                val text = when {
                    live -> event.body.optString("label","Live location")
                    location != null -> event.body.optString("label","Location")
                    contact -> event.body.getString("name")
                    else -> event.body.getString("text")
                }
                require(text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size<=48*1024)
                if (blocked(db,event.sender) || !exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1",arrayOf(event.sender))) return
                val reply=event.body.optJSONObject("reply")
                if (reply!=null) {
                    UUID.fromString(reply.getString("event_id"))
                    require(reply.getString("author") in listOf(event.sender,prefs.myUuid))
                    require(reply.getString("preview").toByteArray(Charsets.UTF_8).size<=4096)
                }
                if (live && !org.msgat.data.db.LiveLocationStore.apply(db,event.sender,false,event.kind,
                        event.eventID,event.body,event.createdAt,now)) return
                insertText(db,event.eventID,event.sender,false,text,minOf(event.createdAt, arrival),reply,event.body.optJSONObject("preview"),
                    if (contact) event.body.getString("account") else null,location,live,allowEdit=deadline==null)
                saveForward(db,event.eventID,event.body.optJSONObject("forward"))
                receipt(db,event.sender,event.eventID,"delivered")
            }
            "chat.location.update", "chat.location.end" -> {
                if (blocked(db,event.sender) || !exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1",arrayOf(event.sender))) return
                org.msgat.data.db.LiveLocationStore.apply(db,event.sender,false,event.kind,event.eventID,event.body,event.createdAt,now)
            }
            "chat.pin" -> {
                if(blocked(db,event.sender) || !exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1",arrayOf(event.sender)))return
                org.msgat.data.db.MessagePinsStore.apply(db,event.sender,prefs.myUuid,event.sender,event.body,onPinNotify)
            }
            "chat.edit", "chat.revoke" -> {
                if (blocked(db, event.sender) || !exists(db, "SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1", arrayOf(event.sender))) return
                if (event.kind == "chat.edit" && MessageExpirationStore.isExpiring(db, event.body.getString("message_id"))) return
                val change = JSONObject().put("revision", event.body.getLong("revision"))
                    .put("revoked", event.kind == "chat.revoke")
                    .put("text", if (event.kind == "chat.edit") event.body.getString("text") else JSONObject.NULL)
                if (event.body.optBoolean("caption")) change.put("caption", true)
                saveChange(db, event.sender, event.sender, false, event.body.getString("message_id"), change)
            }
            "chat.attachment" -> {
                if (blocked(db,event.sender) || !exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1",arrayOf(event.sender))) return
                val manifest=event.body.getJSONObject("object")
                val caption=event.body.optString("caption","")
                require(caption.toByteArray(Charsets.UTF_8).size<=48*1024)
                val reply=event.body.optJSONObject("reply")
                if (reply!=null) {
                    val value=UUID.fromString(reply.getString("event_id"))
                    require(value.toString()==reply.getString("event_id") && value.version()==4 && value.variant()==2)
                    require(reply.getString("author") in listOf(event.sender,prefs.myUuid))
                    require(reply.getString("preview").toByteArray(Charsets.UTF_8).size<=4096)
                }
                insertAttachment(db,event.eventID,event.sender,false,manifest,caption,minOf(event.createdAt, arrival),reply,event.body.optBoolean("sticker",false),event.body.optJSONObject("album"),allowEdit=deadline==null)
                saveForward(db,event.eventID,event.body.optJSONObject("forward"))
                receipt(db,event.sender,event.eventID,"delivered")
            }
            else -> throw VisibleFailure("Unsupported encrypted event")
        }
        if (deadline != null) {
            MessageExpirationStore.record(db, event.eventID, deadline)
            nextExpiration = minOf(nextExpiration, deadline)
        }
    }

    internal suspend fun messageRetention(peer: String): MessageRetentionSettings =
        store.transaction { MessageExpirationStore.settings(it, peer) }

    internal suspend fun setMessageRetention(peer: String, settings: MessageRetentionSettings): Boolean = userOperation {
        store.transaction { db ->
            if (!exists(db, "SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0", arrayOf(peer)))
                throw VisibleFailure("The conversation is no longer available")
            MessageExpirationStore.setSettings(db, peer, settings)
        }
        nextRetentionSweep = 0
    }

    private fun stampExpiration(db: SupportSQLiteDatabase, peer: String, body: JSONObject): Long {
        val duration = MessageExpirationStore.duration(db, peer)
        if (duration > 0L) {
            body.put("expire_after_ms", duration)
            nextExpiration = 0L
        }
        return duration
    }

    private fun signalExpiration() {
        if (!expirationQueued.compareAndSet(false, true)) return
        val work = Work({
            try { flush() }
            finally { expirationQueued.set(false) }
        }, { expirationQueued.set(false) })
        if (commands.trySend(work).isFailure) expirationQueued.set(false)
    }

    private suspend fun removeExpiredMessages() {
        val time = now
        var marked = 0
        val sweep = nextRetentionSweep <= time
        val (next, expired, notificationTokens) = store.transaction { db ->
            if (sweep) marked = MessageExpirationStore.markDueRetention(db, time)
            MessageExpirationStore.removeDue(db, time)
        }
        if (sweep) nextRetentionSweep = if (marked >= 64) time else time + 60_000L
        nextExpiration = next
        if (expired.isEmpty()) return
        notifyRemovedMessages(expired.keys, notificationTokens)
        _sendQueueChanges.tryEmit(Unit)
        expired.values.toSet().forEach { _liveLocationChanges.tryEmit(it) }
    }

    private fun notifyRemovedMessages(ids: Set<String>, tokens: List<String>) {
        if (tokens.isNotEmpty()) runCatching {
            org.msgat.NotificationHelper.dismissMessageNotifications(org.msgat.MsgATApp.instance.applicationContext, tokens)
        }
        _removedMessages.tryEmit(ids)
    }

    private suspend fun flush(recoverReceipts: Boolean = false) {
        if (minOf(nextExpiration, nextRetentionSweep) <= now) removeExpiredMessages()
        if (nextExpiration <= now) return
        if (!ready) return
        if (recoverReceipts) recoverMissingDeliveryReceipts()
        nextRelayRetryAt = store.transaction { db ->
            val time = now
            LiveLocationStore.pruneOutgoing(db, time)
            db.execSQL("DELETE FROM protocol_relay_deferrals WHERE retry_at<=? OR retry_at>?",
                arrayOf(time, time + 30_000))
            db.query("SELECT MIN(retry_at) FROM protocol_relay_deferrals").use { row ->
                if (row.moveToFirst() && !row.isNull(0)) row.getLong(0) else Long.MAX_VALUE
            }
        }
        if(inFlight.isNotEmpty()){
            val active=store.transaction {db->inFlight.filter {id->exists(db,"SELECT 1 FROM protocol_outbox WHERE event_id=? AND delivered=0",arrayOf(id))}.toSet()}
            inFlight.retainAll(active)
        }
        if(shouldPrepareActivity())prepareActivity()
        releaseScheduledSends()
        prepareReceipts()
        attachments.signal()
        val cached=store.pendingOutgoing(attachments.leaseSession,attachments.minimumLease,8)
        for(event in cached)if(event.eventID !in inFlight && !attemptSend(event))return
        var slots=8-cached.size
        if(slots<=0)return
        val waitingForKeys = (requested.keys + unavailableUntil.filterValues { it > now }.keys) - claimedBundles.keys
        for(event in store.pendingEvents(attachments.leaseSession,attachments.minimumLease,waitingForKeys = waitingForKeys)){
            if(slots<=0)break
            var claim = claimedBundles[event.recipient]
            if (claim != null && claim.expiresAt <= now / 1000) {
                store.finishKeyRequest(event.recipient, claim.request)
                claimedBundles.remove(event.recipient)
                claim = null
            }
            if(store.hasSession(event.recipient) || claim!=null){
                val sealed=store.prepare(event,claim?.bundle,claim?.selected)
                if(claim!=null){store.finishKeyRequest(event.recipient,claim.request);claimedBundles.remove(event.recipient)}
                if(!attemptSend(sealed))return
                slots--
            }else if(!requested.containsKey(event.recipient) && (unavailableUntil[event.recipient]?:0)<now){
                val id=store.keyRequestID(event.recipient)
                if(sendPacket(GhostPacket(GhostPacket.TYPE_V2_KEY_CLAIM,prefs.myMailboxId,null,json(JSONObject().put("request_id",id).put("uuid",event.recipient)),null)))requested[event.recipient]=id to now
            }
        }
    }

    private suspend fun keyResponse(packet: GhostPacket) {
        val bytes=packet.encryptedData ?: error("Missing directory response")
        require(bytes.size<=192*1024)
        val response=JSONObject(String(bytes,Charsets.UTF_8))
        val recipient=response.getString("uuid"); val id=response.getString("request_id")
        if (requested[recipient]?.first != id) return
        requested.remove(recipient)
        if (!response.optBoolean("found")) {
            if(response.has("revocation")) { markPeerRevoked(recipient,response.getString("revocation")); flush(); return }
            unavailableUntil[recipient]=now+300000
            throw VisibleFailure("The recipient has no available key bundle. Your message remains protected in the local vault.")
        }
        val bundle=response.getJSONObject("bundle")
        val verified = SecurityCore.call(JSONObject().put("operation","verify_bundle").put("bundle",bundle).put("expected_uuid",recipient))
        val selected=if (response.isNull("selected_prekey")) null else response.getLong("selected_prekey")
        check(selected == null || verified.getJSONObject("bundle").optString("directory_epoch") == directoryEpoch) {
            "One-time key is from an older directory epoch"
        }
        claimedBundles[recipient]=ClaimedBundle(bundle,selected,id,verified.getJSONObject("bundle").getLong("expires_at"))
        while(claimedBundles.size>128)claimedBundles.remove(claimedBundles.keys.first())
        // Keep the persistent request ID until preparation commits; reconnects can reclaim it idempotently.
        flush()
    }

    private suspend fun markPeerRevoked(peer: String,proof: String) {
        require(proof.length==152)
        val checked=SecurityCore.call(JSONObject().put("operation","verify_revocation").put("proof",proof))
        require(checked.getString("uuid")==peer) { "Revocation does not match recipient" }
        val fresh=store.transaction { db ->
            val fresh=!exists(db,"SELECT 1 FROM protocol_revocations WHERE peer=?",arrayOf(peer))
            db.execSQL("INSERT OR IGNORE INTO protocol_revocations(peer,proof) VALUES(?,?)",arrayOf(peer,proof))
            db.execSQL("DELETE FROM message_sends WHERE message_id IN (SELECT messageId FROM messages WHERE conversationUuid=?)",arrayOf(peer))
            db.execSQL("DELETE FROM activity_watchers WHERE peer=?",arrayOf(peer))
            db.execSQL("UPDATE activity_peers SET state_json=json_set(state_json,'$.inflight',NULL,'$.published',NULL) WHERE peer=?",arrayOf(peer))
            db.execSQL("UPDATE contact_states SET accepted=0,incoming=0,outgoing=0 WHERE uuid=?",arrayOf(peer))
            db.execSQL("DELETE FROM friend_requests WHERE senderUuid=?",arrayOf(peer))
            db.execSQL("UPDATE messages SET status=-1 WHERE conversationUuid=? AND isMine=1 AND status IN (0,1)",arrayOf(peer))
            db.execSQL("UPDATE protocol_outbox SET delivered=-1 WHERE recipient=? AND delivered IN (0,-2)",arrayOf(peer))
            db.execSQL("DELETE FROM protocol_pending_events WHERE recipient=?",arrayOf(peer))
            db.execSQL("DELETE FROM protocol_key_requests WHERE recipient=?",arrayOf(peer))
            db.execSQL("""
                DELETE FROM protocol_outbox WHERE recipient=?1 AND event_id IN
                    (SELECT event_id FROM protocol_receipts WHERE peer=?1)
            """,arrayOf(peer))
            db.execSQL("DELETE FROM protocol_receipts WHERE peer=?",arrayOf(peer))
            db.execSQL("DELETE FROM live_location_outgoing WHERE peer=?",arrayOf(peer))
            db.execSQL("DELETE FROM object_transfers WHERE owner_peer=?",arrayOf(peer))
            fresh
        }
        activityReceived.value=activityReceived.value-peer
        claimedBundles.remove(peer);requested.remove(peer); unavailableUntil.remove(peer); inFlight.clear()
        if(fresh) {
            onContactsChanged()
            _sendQueueChanges.tryEmit(Unit)
            _errors.tryEmit("The recipient reset their identity. Pending delivery is unconfirmed; retries stopped.")
            scope.launch(Dispatchers.Main) { org.msgat.core.call.CallManager.endUnavailablePeer(peer) }
        }
    }

    private suspend fun retryPending() {
        store.transaction { db -> SignalProtocolStore.pruneIncoming(db,now) }
        inFlight.clear()
        requested.entries.removeAll { now-it.value.second>=30000 }
        if (now>=nextMaintenance) {
            store.transaction { db -> org.msgat.data.db.LiveLocationStore.pruneOrphans(db,now) }
            val bundle=store.publicBundle(directoryEpoch = checkNotNull(directoryEpoch)); nextMaintenance=now+3600000
            if (publishedBody!=bundle.getString("body") && sendPacket(GhostPacket(GhostPacket.TYPE_V2_KEY_PUBLISH,prefs.myMailboxId,null,json(bundle),null))) publishedBody=bundle.getString("body")
        }
        flush(recoverReceipts = true); pull()
    }

    private suspend fun recoverMissingDeliveryReceipts() = store.transaction { db ->
        val time = now
        data class Recovery(val message: String, val peer: String, val created: Long, val eligible: Boolean)
        val due = db.query(SimpleSQLiteQuery("""
            SELECT r.message_id,m.conversationUuid,a.created_at,
                   r.retry_at>?1 AND COALESCE(b.delivered,0)=1 AND m.status=1 AND m.isMine=1 AND m.isRevoked=0
                   AND EXISTS(SELECT 1 FROM contact_states c WHERE c.uuid=m.conversationUuid AND c.accepted=1 AND c.blocked=0)
                   AND NOT EXISTS(SELECT 1 FROM protocol_revocations v WHERE v.peer=m.conversationUuid)
                   AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=r.message_id AND e.expires_at<=?2)
                   AND NOT EXISTS(SELECT 1 FROM message_send_objects ref WHERE ref.message_id=r.message_id
                       AND NOT EXISTS(SELECT 1 FROM media_objects o WHERE o.object_id=ref.object_id AND o.local_state=1))
            FROM message_delivery_retries r
            JOIN message_sends a ON a.message_id=r.message_id
            JOIN messages m ON m.messageId=r.message_id
            LEFT JOIN protocol_outbox b ON b.event_id=r.message_id AND b.recipient=m.conversationUuid
            WHERE r.retry_at>0 AND r.retry_at<=?2
            ORDER BY r.retry_at,r.message_id LIMIT 8
        """, arrayOf(time - DELIVERY_RETRY_WINDOW, time))).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Recovery(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getInt(3) != 0)) }
        }
        for (job in due) {
            if (!job.eligible) {
                db.execSQL("UPDATE message_delivery_retries SET retry_at=0 WHERE message_id=?", arrayOf(job.message))
                continue
            }
            val objects = db.query(SimpleSQLiteQuery("SELECT object_id FROM message_send_objects WHERE message_id=?", arrayOf(job.message))).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            for (objectID in objects) AttachmentQueue.record(db, job.message, objectID, true, job.peer, job.created)
            db.execSQL("UPDATE protocol_outbox SET delivered=0 WHERE event_id=? AND recipient=? AND delivered=1", arrayOf(job.message, job.peer))
        }
    }
    private fun insertText(db: SupportSQLiteDatabase,id: String,peer: String,mine: Boolean,text: String,created: Long,reply: JSONObject?,preview: JSONObject?=null,contactAccount: String?=null,location: LocationPoint?=null,live:Boolean=false,allowEdit:Boolean=true) {
        val extension = when {
            live -> "live_location"
            location != null -> "location"
            contactAccount != null -> "contact"
            else -> "txt"
        }
        db.execSQL("INSERT INTO messages(messageId,conversationUuid,isMine,content,ext,timestamp,isRead,replyToId,replyToSender,replyToContent,status,isRevoked,isEdited) VALUES(?,?,?,?,?,?,?,?,?,?,0,0,0)",arrayOf(id,peer,if (mine) 1 else 0,text,extension,created,if (mine) 1 else 0,reply?.getString("event_id"),reply?.getString("author"),reply?.getString("preview")))
        if (contactAccount != null) db.execSQL("INSERT INTO message_contact_cards(message_id,account) VALUES(?,?)", arrayOf(id,contactAccount))
        if (location != null) db.execSQL("INSERT INTO message_locations(message_id,latitude_e6,longitude_e6) VALUES(?,?,?)",
            arrayOf(id,location.latitudeE6,location.longitudeE6))
        applySavedChange(db,peer,if (mine) prefs.myUuid else peer,mine,id,allowEdit)
        org.msgat.data.db.WebPreviewStore.record(db,id,preview,text,peer,mine,created)
    }
    private fun insertAttachment(db: SupportSQLiteDatabase,id: String,peer: String,mine: Boolean,manifest: JSONObject,caption: String,created: Long,reply: JSONObject?,sticker: Boolean=false,album: JSONObject?=null,allowEdit:Boolean=true) {
        EncryptedObjectStore.remember(manifest,db)
        val objectID=manifest.getString("object_id")
        db.execSQL("INSERT INTO messages(messageId,conversationUuid,isMine,content,ext,timestamp,isRead,replyToId,replyToSender,replyToContent,status,isRevoked,isEdited) VALUES(?,?,?,?,'attachment',?,?,?,?,?,0,0,0)",arrayOf(id,peer,if(mine)1 else 0,EncryptedObjectStore.reference(objectID),created,if(mine)1 else 0,reply?.getString("event_id"),reply?.getString("author"),reply?.getString("preview")))
        db.execSQL("INSERT INTO message_attachments(message_id,object_id,caption) VALUES(?,?,?)",arrayOf(id,objectID,caption))
        if (album != null) {
            val albumId = album.getString("id")
            val position = album.getInt("index")
            val count = album.getInt("count")
            val direction = if (mine) 1 else 0
            db.execSQL(
                """INSERT OR IGNORE INTO message_album_items(message_id,peer,is_mine,album_id,position,item_count)
                   SELECT ?,?,?,?,?,? WHERE NOT EXISTS (
                       SELECT 1 FROM message_album_items
                       WHERE peer=? AND is_mine=? AND album_id=? AND item_count<>?
                   )""".trimIndent(),
                arrayOf(id, peer, direction, albumId, position, count, peer, direction, albumId, count)
            )
        }
        if (mine || !exists(db,"SELECT 1 FROM media_objects WHERE object_id=? AND local_state=1",arrayOf(objectID)))
            AttachmentQueue.record(db,id,objectID,mine,peer,created)
        if(sticker)db.execSQL("INSERT OR IGNORE INTO message_stickers(message_id) VALUES(?)",arrayOf(id))
        applySavedChange(db,peer,if (mine) prefs.myUuid else peer,mine,id,allowEdit)
    }
    private fun saveForward(db: androidx.sqlite.db.SupportSQLiteDatabase,id: String,forward: JSONObject?) {
        if(forward==null) return
        db.execSQL("INSERT INTO message_forwards(message_id,name,original_at) VALUES(?,?,?)",arrayOf(id,forward.optString("name").takeIf {forward.has("name")},forward.getLong("original_at")))
    }

    private fun clearHistory(db: SupportSQLiteDatabase, peer: String) {
        db.execSQL("DELETE FROM call_history WHERE peer=?",arrayOf(peer))
        db.execSQL("DELETE FROM live_location_states WHERE peer=?",arrayOf(peer))
        db.execSQL("DELETE FROM message_pin_clocks WHERE peer=?",arrayOf(peer))
        db.execSQL("DELETE FROM object_transfers WHERE event_id IN (SELECT messageId FROM messages WHERE conversationUuid=?)", arrayOf(peer))
        db.execSQL("DELETE FROM message_attachments WHERE message_id IN (SELECT messageId FROM messages WHERE conversationUuid=?)", arrayOf(peer))
        db.execSQL("DELETE FROM message_changes WHERE conversation_uuid=?", arrayOf(peer))
        db.execSQL("DELETE FROM messages WHERE conversationUuid=?", arrayOf(peer))
        db.execSQL("DELETE FROM protocol_pending_events WHERE recipient=? AND kind IN ('chat.text','chat.contact','chat.location','chat.location.start','chat.location.update','chat.location.end','chat.attachment','chat.edit','chat.revoke','chat.pin')", arrayOf(peer))
    }

    internal suspend fun recordCallHistory(peer: String, callId: String, outgoing: Boolean, video: Boolean,
                                           outcome: org.msgat.core.call.CallOutcome, duration: Long, endedAt: Long) {
        store.transaction { db -> org.msgat.core.call.CallHistory.insert(db,peer,callId,outgoing,video,outcome,duration,endedAt) }
        org.msgat.core.call.CallHistory.changed()
    }

    internal suspend fun callHistory(before: org.msgat.core.call.CallHistoryEntry?, count: Int, missedOnly: Boolean) =
        store.transaction { db -> org.msgat.core.call.CallHistory.page(db, before, count, missedOnly) }

    internal suspend fun removeCallHistory(entry: org.msgat.core.call.CallHistoryEntry?): Boolean = userOperation {
        store.transaction { db -> org.msgat.core.call.CallHistory.remove(db, entry) }
        org.msgat.core.call.CallHistory.changed()
    }

    private fun savedChange(db: SupportSQLiteDatabase, author: String, target: String): JSONObject? =
        db.query(SimpleSQLiteQuery("SELECT state_json FROM message_changes WHERE author=? AND message_id=?", arrayOf(author, target))).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) else null
        }
    private fun saveChange(db: SupportSQLiteDatabase, peer: String, author: String, mine: Boolean, target: String, change: JSONObject) {
        val existing = db.query(SimpleSQLiteQuery("SELECT conversationUuid,isMine,ext FROM messages WHERE messageId=?", arrayOf(target))).use {
            if (it.moveToFirst()) Triple(it.getString(0), it.getInt(1) != 0, it.getString(2)) else null
        }
        val expectedKind = if (change.optBoolean("caption")) "attachment" else "txt"
        if (existing != null && (existing.first != peer || existing.second != mine || (!change.getBoolean("revoked") && existing.third != expectedKind))) return
        if (existing == null && !mine && exists(db, "SELECT 1 FROM protocol_inbox WHERE sender=? AND event_id=?", arrayOf(author, target))) return
        val previous = savedChange(db, author, target)
        val result = SecurityCore.call(JSONObject().put("operation", "reduce_message_change")
            .put("previous", previous ?: JSONObject.NULL).put("change", change).put("incoming", !mine))
        if (result.optBoolean("conflict")) return
        val state = result.getJSONObject("change")
        if (result.getBoolean("changed")) {
            val encoded = state.toString()
            if (existing == null) {
                val count = db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM message_changes WHERE pending=1 AND author=?", arrayOf(author))).use { it.moveToFirst(); it.getLong(0) }
                val bytes = db.query("SELECT COALESCE(SUM(stored_bytes+1024),0) FROM message_changes WHERE pending=1").use { it.moveToFirst(); it.getLong(0) }
                val previousBytes = db.query(SimpleSQLiteQuery("SELECT COALESCE((SELECT stored_bytes+1024 FROM message_changes WHERE author=? AND message_id=? AND pending=1),0)", arrayOf(author, target))).use { it.moveToFirst(); it.getLong(0) }
                if ((previous == null && count >= 256) || bytes - previousBytes + encoded.toByteArray(Charsets.UTF_8).size + 1024 > 16 * 1024 * 1024) {
                    if (!mine) throw SignalProtocolStore.PendingChangeLimit()
                    error("Pending message changes exceed the local history budget")
                }
            }
            db.execSQL("INSERT INTO message_changes(author,message_id,conversation_uuid,state_json,pending,stored_bytes,pending_since) VALUES(?,?,?,?,?,?,?) ON CONFLICT(author,message_id) DO UPDATE SET state_json=excluded.state_json,pending=excluded.pending,stored_bytes=excluded.stored_bytes", arrayOf(author, target, peer, encoded, if (existing == null) 1 else 0, encoded.toByteArray(Charsets.UTF_8).size, now))
        }
        applySavedChange(db, peer, author, mine, target)
    }
    private fun applySavedChange(db: SupportSQLiteDatabase, peer: String, author: String, mine: Boolean, target: String, allowEdit: Boolean = true) {
        val state = savedChange(db, author, target) ?: return
        val original = db.query(SimpleSQLiteQuery("""SELECT ext,isRevoked,
            EXISTS(SELECT 1 FROM message_stickers WHERE message_id=messages.messageId)
            FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=?""",
            arrayOf(target, peer, if (mine) 1 else 0))).use {
            if (it.moveToFirst()) Triple(it.getString(0),it.getInt(1)!=0,it.getInt(2)!=0) else null
        } ?: return
        val caption = state.optBoolean("caption")
        if (!state.getBoolean("revoked") && (!allowEdit || original.second || original.third || original.first != (if (caption) "attachment" else "txt"))) {
            db.execSQL("DELETE FROM message_changes WHERE author=? AND message_id=?", arrayOf(author,target))
            return
        }
        db.execSQL("UPDATE message_changes SET pending=0 WHERE author=? AND message_id=?", arrayOf(author, target))
        if (state.getBoolean("revoked")) {

            db.execSQL("UPDATE messages SET content='',isRevoked=1,isEdited=0 WHERE messageId=? AND conversationUuid=? AND isMine=?", arrayOf(target, peer, if (mine) 1 else 0))
            db.execSQL("UPDATE messages SET replyToContent='[Message revoked]' WHERE conversationUuid=? AND replyToId=?", arrayOf(peer, target))
            db.execSQL("DELETE FROM object_transfers WHERE event_id=?", arrayOf(target))
            db.execSQL("DELETE FROM message_attachments WHERE message_id=?", arrayOf(target))
            db.execSQL("DELETE FROM message_contact_cards WHERE message_id=?", arrayOf(target))
            db.execSQL("DELETE FROM message_locations WHERE message_id=?", arrayOf(target))
            if (mine) {
                db.execSQL("DELETE FROM protocol_pending_events WHERE event_id=? AND recipient=?", arrayOf(target, peer))
            }
        } else if (caption) {
            db.execSQL("UPDATE message_attachments SET caption=? WHERE message_id=?", arrayOf(state.getString("text"),target))
            db.execSQL("UPDATE messages SET isEdited=1 WHERE messageId=?", arrayOf(target))
        } else db.execSQL("UPDATE messages SET content=?,isEdited=1 WHERE messageId=? AND conversationUuid=? AND isMine=? AND ext='txt' AND isRevoked=0", arrayOf(state.getString("text"), target, peer, if (mine) 1 else 0))
    }

    private fun quote(db: SupportSQLiteDatabase, recipient: String, replyID: String?, selection: String?): JSONObject? {
        if(replyID==null) return null
        return db.query(SimpleSQLiteQuery("SELECT isMine,content,ext FROM messages WHERE messageId=? AND conversationUuid=? AND isRevoked=0",arrayOf(replyID,recipient))).use { cursor ->
            if(!cursor.moveToFirst()) throw VisibleFailure("The quoted message is unavailable")
            val expiring = MessageExpirationStore.isExpiring(db, replyID)
            val kind = cursor.getString(2)
            if (selection != null && (expiring || kind != "txt")) throw VisibleFailure("The selected quote is unavailable")
            val excerpt = if (selection == null) null else SecurityCore.call(JSONObject().put("operation", "reply_quote_selection")
                .put("text", cursor.getString(1)).put("selection", selection)).getString("preview")
            val preview=if (expiring) "Auto-deleting message" else when(kind) {
                "txt", "location", "live_location" -> cursor.getString(1)
                "contact" -> "Contact: ${cursor.getString(1)}"
                else -> "[${cursor.getString(2)}]"
            }
            val end=preview.offsetByCodePoints(0,minOf(512,preview.codePointCount(0,preview.length)))
            JSONObject().put("event_id",replyID).put("author",if(cursor.getInt(0)!=0)prefs.myUuid else recipient)
                .put("preview", excerpt ?: preview.substring(0,end))
        }
    }
    private val eligibleRead="(SELECT read_receipts FROM privacy_preferences WHERE id=1)=1 AND NOT EXISTS(SELECT 1 FROM read_receipt_suppression s WHERE s.peer=r.peer AND s.message_id=r.message_id)"
    private fun readReceiptsEnabled(db:SupportSQLiteDatabase)=exists(db,"SELECT 1 FROM privacy_preferences WHERE id=1 AND read_receipts=1",emptyArray())
    private fun canSendRead(db:SupportSQLiteDatabase,peer:String,id:String)=readReceiptsEnabled(db) &&
        !exists(db,"SELECT 1 FROM read_receipt_suppression WHERE peer=? AND message_id=?",arrayOf(peer,id))
    private fun receipt(db: SupportSQLiteDatabase,recipient: String,id: String,state: String) {
        if(!exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(recipient))) return
        if(state=="read" && !canSendRead(db,recipient,id))return
        db.execSQL("INSERT OR IGNORE INTO protocol_receipts(peer,message_id,state,event_id,created_at) VALUES(?,?,?,?,?)",arrayOf(recipient,id,state,UUID.randomUUID().toString(),now))
    }
    private fun rescheduleReceipt(peer: String,id: String,db: SupportSQLiteDatabase) {
        if(!exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",arrayOf(peer))) return
        // Backfill existing V2 history without recreating a cleared message.
        db.query(SimpleSQLiteQuery("SELECT isRead FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=0",arrayOf(id,peer))).use { cursor ->
            if(cursor.moveToFirst()) receipt(db,peer,id,if(cursor.getInt(0)!=0 && canSendRead(db,peer,id)) "read" else "delivered")
        }
        db.query(SimpleSQLiteQuery("SELECT event_id,pending,next_retry FROM protocol_receipts r WHERE peer=? AND message_id=? AND (state!='read' OR ($eligibleRead)) ORDER BY CASE state WHEN 'read' THEN 0 ELSE 1 END LIMIT 1",arrayOf(peer,id))).use { cursor ->
            if(!cursor.moveToFirst() || cursor.getInt(1)!=0) return
            val deadline=cursor.getLong(2); val time=now
            if(deadline>time && deadline-time<=30_000) return
            db.execSQL("UPDATE protocol_receipts SET pending=1 WHERE event_id=?",arrayOf(cursor.getString(0)))
        }
    }
    private suspend fun prepareReceipts() = store.transaction { db ->
        db.execSQL("UPDATE protocol_receipts SET pending=0 WHERE pending=1 AND state='delivered' AND EXISTS(SELECT 1 FROM protocol_receipts r WHERE r.peer=protocol_receipts.peer AND r.message_id=protocol_receipts.message_id AND r.state='read' AND ($eligibleRead))")
        data class Work(val peer: String,val id: String,val state: String,val event: String,val created: Long)
        val jobs=db.query("SELECT r.peer,r.message_id,r.state,r.event_id,r.created_at FROM protocol_receipts r JOIN contact_states c ON c.uuid=r.peer WHERE r.pending=1 AND c.accepted=1 AND c.blocked=0 AND (r.state!='read' OR ($eligibleRead)) ORDER BY r.rowid LIMIT 64").use { cursor -> buildList { while(cursor.moveToNext()) add(Work(cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getLong(4))) } }
        for(job in jobs) {
            if(exists(db,"SELECT 1 FROM protocol_outbox WHERE event_id=? AND recipient=?",arrayOf(job.event,job.peer)))
                db.execSQL("UPDATE protocol_outbox SET delivered=0 WHERE event_id=? AND recipient=? AND (delivered>=0 OR delivered=-2)",arrayOf(job.event,job.peer))
            else store.enqueue(SignalProtocolStore.Pending(job.event,job.peer,"chat.receipt",JSONObject().put("message_id",job.id).put("state",job.state),job.created),db)
            db.execSQL("UPDATE protocol_receipts SET pending=0,next_retry=? WHERE event_id=?",arrayOf(now+30_000,job.event))
        }
    }
    private fun blocked(db: SupportSQLiteDatabase,uuid: String)=exists(db,"SELECT 1 FROM contact_states WHERE uuid=? AND blocked=1",arrayOf(uuid))
    private fun exists(db: SupportSQLiteDatabase,sql: String,args: Array<Any?>)=db.query(SimpleSQLiteQuery(sql,args)).use { it.moveToFirst() }

    private fun applyDeliveryReceipt(event: SignalProtocolStore.Incoming, db: SupportSQLiteDatabase): Boolean {
        val peer = event.sender
        val id = event.body.getString("message_id")
        val status = when (event.body.getString("state")) {
            "delivered" -> 2
            "read" -> 3
            else -> error("Invalid receipt state")
        }
        acknowledgeActivity(db, peer, id)
        val target = arrayOf(id, peer)
        if (!exists(db, "SELECT 1 FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=1", target))
            return false
        db.execSQL("UPDATE protocol_outbox SET delivered=1 WHERE event_id=? AND recipient=? AND delivered>=0", target)
        db.execSQL("DELETE FROM object_transfers WHERE event_id=? AND owner_peer=? AND direction=0", target)
        db.execSQL("UPDATE messages SET status=?1 WHERE messageId=?2 AND conversationUuid=?3 AND isMine=1 AND isRevoked=0 AND status<?1",
            arrayOf(status, id, peer))
        return db.query("SELECT changes()").use { it.moveToFirst() && it.getInt(0) > 0 }
    }
    private fun pending(recipient: String,kind: String,body: JSONObject)=SignalProtocolStore.Pending(UUID.randomUUID().toString(),recipient,kind,body,now)
    private suspend fun profile()=store.transaction {UserProfileStore.body(it,prefs.myUuid,prefs.myNickname ?: "Unknown")}
    private fun json(value: JSONObject)=value.toString().toByteArray(Charsets.UTF_8)
    private fun eventID(bytes: ByteArray?): String {
        require(bytes!=null && bytes.size==16)
        val buffer=ByteBuffer.wrap(bytes); val id=UUID(buffer.long,buffer.long)
        require(id.version()==4 && id.variant()==2)
        return id.toString()
    }
    private fun eventBytes(value: String): ByteArray { val id=UUID.fromString(value); return ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array() }
    private fun pull() {
        if (pushPull?.reply?.isActive == true) return
        val nonce = syncNonce ?: eventBytes(UUID.randomUUID().toString())
        if (sendPacket(GhostPacket(GhostPacket.TYPE_V2_SYNC, prefs.myUuid, nonce, null, null))) syncNonce = nonce
    }
    private fun sendPacket(packet: GhostPacket)=send(epoch,packet)
    private val now get()=System.currentTimeMillis()
    private fun network(connection: Long,block: suspend () -> Unit) {
        if (commands.trySend(Work(block)).isFailure) closeTransport(connection)
    }
    private suspend fun userOperation(onFailure: (() -> Unit)? = null, block: suspend () -> Unit): Boolean {
        val answer=CompletableDeferred<Boolean>()
        val work = Work({
            block();_sendQueueChanges.tryEmit(Unit); answer.complete(true)
            try { flush() } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { report(error) }
        },{ try {onFailure?.invoke()} finally {answer.complete(false)} })
        try { commands.send(work) }
        catch (error: Exception) { try {onFailure?.invoke()} finally {answer.complete(false)};throw error }
        return answer.await()
    }
    data class ActivityPrivacy(val online:Boolean,val typing:Boolean)
    private data class PeerActivity(val online:Boolean?,val expires:Long,val typingExpires:Long)
    private val activityGate=Any()
    @Volatile private var activityForeground=false
    private val activityOnlineState=MutableStateFlow(false)
    private var activityOnline: Boolean
        get() = activityOnlineState.value
        set(value) { activityOnlineState.value = value }
    @Volatile private var activityTyping=false
    private var activityLoaded=false
    private var activityCursor=0
    private var activityRevision=0L
    private var activityChat:String?=null
    private var activityTypingUntil=0L
    private val activityDirty=linkedSetOf<String>()
    private val activityReceived=MutableStateFlow<Map<String,PeerActivity>>(emptyMap())
    private val activityQueued=java.util.concurrent.atomic.AtomicBoolean(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observePeerActivity(peer: String) =
        combine(activityReceived.map { it[peer] }.distinctUntilChanged(), activityOnlineState) { activity, showOnline ->
            activity to showOnline
        }.transformLatest { (activity, showOnline) ->
            if (activity != null) {
                val typingTime = activity.typingExpires - android.os.SystemClock.elapsedRealtime()
                if (typingTime > 0) {
                    emit(PeerActivityStatus.TYPING)
                    delay(typingTime)
                }
                val onlineTime = activity.expires - android.os.SystemClock.elapsedRealtime()
                if (showOnline && activity.online != null && onlineTime > 0) {
                    emit(if (activity.online) PeerActivityStatus.ONLINE else PeerActivityStatus.OFFLINE)
                    delay(onlineTime)
                }
            }
            emit(PeerActivityStatus.NONE)
        }.distinctUntilChanged()
    fun setForeground(value:Boolean){synchronized(activityGate){if(activityForeground==value)return;activityForeground=value;activityRevision++;if(!value)activityTypingUntil=0};if(activityOnline || activityTyping)signalActivity()}
    fun setActiveChat(peer:String?,typing:Boolean=false){
        val changed=synchronized(activityGate){
            val time=android.os.SystemClock.elapsedRealtime()
            val changed=activityChat!=peer || (activityTypingUntil>time)!=typing
            if(activityOnline || activityTyping)activityChat?.takeIf{it!=peer}?.let {activityDirty.add(it)}
            activityRevision++;activityChat=peer;activityTypingUntil=if(typing && peer!=null)time+8_000 else 0
            if(activityDirty.size>128)activityDirty.remove(activityDirty.first())
            changed
        };if(changed && (activityOnline || activityTyping))signalActivity()
    }
    fun clearActiveChat(peer:String){synchronized(activityGate){if(activityChat!=peer)return;activityRevision++;if(activityOnline || activityTyping)activityDirty.add(peer);while(activityDirty.size>128)activityDirty.remove(activityDirty.first());activityChat=null;activityTypingUntil=0};if(activityOnline || activityTyping)signalActivity()}
    private fun signalActivity(){
        if(!activityQueued.compareAndSet(false,true))return
        if(commands.trySend(Work({
            val revision=synchronized(activityGate){activityRevision}
            var completed=false
            try{flush();completed=true}finally{activityQueued.set(false);if(completed && synchronized(activityGate){activityRevision!=revision})signalActivity()}
        },{activityQueued.set(false)})).isFailure)activityQueued.set(false)
    }
    suspend fun activityPrivacy():ActivityPrivacy {
        val result=CompletableDeferred<ActivityPrivacy>()
        commands.send(Work({result.complete(store.transaction {db->loadActivityPrivacy(db);ActivityPrivacy(activityOnline,activityTyping)})},{result.completeExceptionally(IllegalStateException("Messaging is unavailable"))}))
        return result.await()
    }
    private fun loadActivityPrivacy(db:SupportSQLiteDatabase){
        db.query("SELECT share_online,share_typing FROM privacy_preferences WHERE id=1").use {c->check(c.moveToFirst());activityOnline=c.getInt(0)!=0;activityTyping=c.getInt(1)!=0};activityLoaded=true
    }
    private fun activityState(db:SupportSQLiteDatabase,peer:String):JSONObject=db.query(SimpleSQLiteQuery("SELECT state_json FROM activity_peers WHERE peer=?",arrayOf(peer))).use {c->if(c.moveToFirst())JSONObject(c.getString(0))else JSONObject()}
    private fun changeActivity(db:SupportSQLiteDatabase,peer:String,state:JSONObject,action:JSONObject):JSONObject {
        val result=SecurityCore.call(JSONObject().put("operation","activity_transition").put("state",state).put("action",action))
        db.execSQL("INSERT INTO activity_peers(peer,state_json) VALUES(?,?) ON CONFLICT(peer) DO UPDATE SET state_json=excluded.state_json",arrayOf(peer,result.getJSONObject("state").toString()));return result
    }
    private fun deleteActivityReceipt(db:SupportSQLiteDatabase,peer:String,id:String){
        db.execSQL("DELETE FROM protocol_pending_events WHERE event_id IN (SELECT event_id FROM protocol_receipts WHERE peer=? AND message_id=? AND state='delivered')",arrayOf(peer,id))
        db.execSQL("DELETE FROM protocol_outbox WHERE event_id IN (SELECT event_id FROM protocol_receipts WHERE peer=? AND message_id=? AND state='delivered')",arrayOf(peer,id))
        db.execSQL("DELETE FROM protocol_receipts WHERE peer=? AND message_id=? AND state='delivered'",arrayOf(peer,id))
    }
    private fun receiveActivity(event:SignalProtocolStore.Incoming,db:SupportSQLiteDatabase):JSONObject? {
        if(!exists(db,"SELECT 1 FROM contact_states c WHERE c.uuid=? AND c.accepted=1 AND c.blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=c.uuid)",arrayOf(event.sender)))return null
        val result=changeActivity(db,event.sender,activityState(db,event.sender),JSONObject().put("kind","receive").put("event_id",event.eventID).put("body",event.body).put("created_at",event.createdAt).put("now",now))
        if(result.getJSONObject("state").optString("received_id")==event.eventID)receipt(db,event.sender,event.eventID,"delivered")
        if(!result.isNull("replaced"))deleteActivityReceipt(db,event.sender,result.getString("replaced"))
        val observation=result.optJSONObject("observation")?:return null
        if(!activityLoaded)loadActivityPrivacy(db)
        if(activityOnline && observation.getBoolean("request")){
            db.execSQL("INSERT INTO activity_watchers(peer,until) VALUES(?,?) ON CONFLICT(peer) DO UPDATE SET until=excluded.until",arrayOf(event.sender,now+45_000))
            db.execSQL("DELETE FROM activity_watchers WHERE peer IN (SELECT peer FROM activity_watchers ORDER BY until DESC,peer LIMIT -1 OFFSET 128)")
        }
        return observation
    }
    private fun publishActivity(peer:String,value:JSONObject){
        val time=android.os.SystemClock.elapsedRealtime()
        val entry=PeerActivity(if(value.isNull("online"))null else value.getBoolean("online"),time+value.getLong("valid_for"),time+value.getLong("typing_for"))
        activityReceived.value=(activityReceived.value.filterKeys{it!=peer}+ (peer to entry)).entries.toList().takeLast(128).associate{it.key to it.value}
    }
    private fun acknowledgeActivity(db:SupportSQLiteDatabase,peer:String,id:String){
        val state=activityState(db,peer)
        if(state.optJSONObject("inflight")?.optString("event_id")!=id)return
        val result=changeActivity(db,peer,state,JSONObject().put("kind","acknowledge").put("event_id",id))
        if(!result.isNull("acknowledged"))db.execSQL("DELETE FROM protocol_outbox WHERE event_id=? AND recipient=?",arrayOf(id,peer))
    }
    private fun shouldPrepareActivity()= !activityLoaded || activityOnline || activityTyping || synchronized(activityGate){activityDirty.isNotEmpty()}
    private suspend fun prepareActivity()=store.transaction {db->
        if(!activityLoaded)loadActivityPrivacy(db)
        val time=now
        data class Snapshot(val foreground:Boolean,val chat:String?,val typingUntil:Long,val dirty:List<String>,val revision:Long)
        val snapshot=synchronized(activityGate){Snapshot(activityForeground,activityChat,activityTypingUntil,activityDirty.toList(),activityRevision)}
        val foreground=snapshot.foreground;val chat=snapshot.chat;val typingUntil=snapshot.typingUntil;val dirty=snapshot.dirty
        db.execSQL("DELETE FROM activity_watchers WHERE until<=? OR until>?",arrayOf(time,time+45_000))
        val watchers=if(activityOnline)db.query(SimpleSQLiteQuery("SELECT w.peer FROM activity_watchers w LEFT JOIN activity_peers a ON a.peer=w.peer ORDER BY COALESCE(json_extract(a.state_json,'$.published.created_at'),0),w.peer LIMIT 128")).use {c->buildList{while(c.moveToNext())add(c.getString(0))}}else emptyList()
        val rest=(dirty+watchers).distinct().filter{it!=chat}
        val start=if(rest.isEmpty())0 else activityCursor%rest.size
        val peers=buildList{if(chat!=null)add(chat);for(index in 0 until minOf(rest.size,31))add(rest[(start+index)%rest.size])}
        if(rest.isNotEmpty())activityCursor=(start+31)%rest.size
        for(peer in peers){
            if(!exists(db,"SELECT 1 FROM contact_states c WHERE c.uuid=? AND c.accepted=1 AND c.blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=c.uuid)",arrayOf(peer))){synchronized(activityGate){if(activityRevision==snapshot.revision)activityDirty.remove(peer)};continue}
            val online:Any=if(activityOnline)foreground else JSONObject.NULL
            val typing=foreground && activityTyping && chat==peer && typingUntil>android.os.SystemClock.elapsedRealtime()
            val request=foreground && activityOnline && chat==peer
            var state=activityState(db,peer);var inflight=state.optJSONObject("inflight")
            if(inflight!=null && !exists(db,"SELECT 1 FROM protocol_outbox WHERE event_id=? AND recipient=?",arrayOf(inflight.getString("event_id"),peer)) &&
                (inflight.getJSONObject("body").getLong("expires_at")<=time || inflight.getJSONObject("body").opt("online")!=online || inflight.getJSONObject("body").getBoolean("typing")!=typing || inflight.getJSONObject("body").getBoolean("request")!=request)){
                db.execSQL("DELETE FROM protocol_pending_events WHERE event_id=? AND recipient=?",arrayOf(inflight.getString("event_id"),peer))
                state=changeActivity(db,peer,state,JSONObject().put("kind","unprepare")).getJSONObject("state");inflight=null
            }
            if(inflight!=null){
                val body=inflight.getJSONObject("body")
                val permitted=(activityOnline || (body.isNull("online") && !body.getBoolean("request"))) && (activityTyping || !body.getBoolean("typing"))
                val retryAt=inflight.getLong("retry_at")
                if(permitted && (retryAt<=time || retryAt-time>30_000)){
                    db.execSQL("UPDATE protocol_outbox SET delivered=0 WHERE event_id=? AND recipient=? AND delivered>=0",arrayOf(inflight.getString("event_id"),peer))
                    changeActivity(db,peer,state,JSONObject().put("kind","retried").put("now",time))
                }
                continue
            }
            val result=changeActivity(db,peer,state,JSONObject().put("kind","publish").put("event_id",UUID.randomUUID().toString()).put("online",online).put("typing",typing).put("request",request).put("now",time))
            result.optJSONObject("send")?.let {event->store.enqueue(SignalProtocolStore.Pending(event.getString("event_id"),peer,"chat.activity",event.getJSONObject("body"),event.getLong("created_at")),db)}
            synchronized(activityGate){if(activityRevision==snapshot.revision)activityDirty.remove(peer)}
        }
    }

    @Volatile private var nextScheduledSend=Long.MAX_VALUE
    private var scheduleDeadlineLoaded=false
    private val scheduleQueued=java.util.concurrent.atomic.AtomicBoolean(false)
    private fun signalScheduledSends(){
        if(!scheduleQueued.compareAndSet(false,true))return
        if (commands.trySend(Work({
            try {
                flush()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val time = now
                if (nextScheduledSend <= time) {
                    nextScheduledSend = time + 30_000
                    scheduleDeadlineLoaded = false
                }
                if (nextRelayRetryAt <= time) nextRelayRetryAt = time + 30_000
                Log.w("MsgAT_Send", "Pending sends retained: ${error.javaClass.simpleName}")
            } finally {
                scheduleQueued.set(false)
            }
        }, { scheduleQueued.set(false) })).isFailure) scheduleQueued.set(false)
    }
    private fun holdScheduled(event:SignalProtocolStore.Pending,options:SendOptions,db:SupportSQLiteDatabase):Boolean {
        val due=options.sendAt?:return false
        require(due in 0..253402214399999L){"Invalid scheduled time"}
        db.execSQL("INSERT INTO scheduled_sends(event_id,recipient,send_at,created_at) VALUES(?,?,?,?)",arrayOf(event.eventID,event.recipient,due,event.createdAt))
        db.execSQL("UPDATE protocol_pending_events SET held=1 WHERE event_id=?",arrayOf(event.eventID))
        val objects=listOfNotNull(event.body.optJSONObject("object"),event.body.optJSONObject("preview")?.optJSONObject("image"))
        for(manifest in objects){
            EncryptedObjectStore.remember(manifest,db,true)
            db.execSQL("INSERT OR IGNORE INTO scheduled_objects(event_id,object_id) VALUES(?,?)",arrayOf(event.eventID,manifest.getString("object_id")))
        }
        scheduleDeadlineLoaded=false;return true
    }
    private fun requireScheduledMedia(db: SupportSQLiteDatabase, id: String) {
        if (exists(db, """
            SELECT 1 FROM protocol_pending_events p,
                json_each(json_array(json_extract(p.body_json,'$.object.object_id'),
                                     json_extract(p.body_json,'$.preview.image.object_id'))) ref
            WHERE p.event_id=? AND ref.value IS NOT NULL
                AND NOT EXISTS(SELECT 1 FROM media_objects o WHERE o.object_id=ref.value AND o.local_state=1)
        """.trimIndent(), arrayOf(id))) throw VisibleFailure("Attachment is unavailable")
    }

    private suspend fun releaseScheduledSends(){
        if(scheduleDeadlineLoaded && nextScheduledSend>now)return
        store.transaction {db->
            data class Due(val id:String,val peer:String,val kind:String,val body:JSONObject)
            val rows=db.query(SimpleSQLiteQuery("SELECT s.event_id,s.recipient,p.kind,p.body_json FROM scheduled_sends s JOIN protocol_pending_events p ON p.event_id=s.event_id WHERE s.state='waiting' AND s.send_at<=? ORDER BY s.send_at,s.created_at,s.event_id LIMIT 16",arrayOf(now))).use {c->buildList{while(c.moveToNext())add(Due(c.getString(0),c.getString(1),c.getString(2),JSONObject(c.getString(3))))}}
            for(row in rows){
                db.execSQL("SAVEPOINT release_scheduled")
                try {
                    check(exists(db,"SELECT 1 FROM contact_states c WHERE uuid=? AND accepted=1 AND blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=c.uuid)",arrayOf(row.peer)))
                    check(!exists(db,"SELECT 1 FROM protocol_outbox WHERE event_id=?",arrayOf(row.id)))
                    requireScheduledMedia(db, row.id)
                    val created=now
                    db.execSQL("UPDATE protocol_pending_events SET held=0,created_at=? WHERE event_id=?",arrayOf(created,row.id))
                    if(row.kind=="chat.text")insertText(db,row.id,row.peer,true,row.body.getString("text"),created,row.body.optJSONObject("reply"),row.body.optJSONObject("preview"))
                    else if(row.kind=="chat.contact")insertText(db,row.id,row.peer,true,row.body.getString("name"),created,row.body.optJSONObject("reply"),contactAccount=row.body.getString("account"))
                    else if(row.kind=="chat.location")insertText(db,row.id,row.peer,true,row.body.optString("label","Location"),created,row.body.optJSONObject("reply"),location=locationPoint(row.body))
                    else {check(row.kind=="chat.attachment");insertAttachment(db,row.id,row.peer,true,row.body.getJSONObject("object"),row.body.getString("caption"),created,row.body.optJSONObject("reply"),row.body.optBoolean("sticker",false),row.body.optJSONObject("album"))}
                    if (row.body.has("expire_after_ms")) nextExpiration = minOf(nextExpiration, created + row.body.getLong("expire_after_ms"))
                    saveForward(db,row.id,row.body.optJSONObject("forward"))
                    db.execSQL("DELETE FROM scheduled_sends WHERE event_id=?",arrayOf(row.id))
                    db.execSQL("RELEASE SAVEPOINT release_scheduled")
                } catch(error:Exception){
                    db.execSQL("ROLLBACK TO SAVEPOINT release_scheduled");db.execSQL("RELEASE SAVEPOINT release_scheduled")
                    if(error is CancellationException)throw error
                    db.execSQL("UPDATE scheduled_sends SET state='failed' WHERE event_id=?",arrayOf(row.id))
                    Log.w("MsgAT_Send","Scheduled release retained: ${error.javaClass.simpleName}")
                }
            }
            nextScheduledSend=db.query("SELECT MIN(send_at) FROM scheduled_sends WHERE state='waiting'").use{c->if(c.moveToFirst() && !c.isNull(0))c.getLong(0)else Long.MAX_VALUE}
            scheduleDeadlineLoaded=true
        }
        _sendQueueChanges.tryEmit(Unit)
    }
    private suspend fun attemptSend(event:SignalProtocolStore.Outgoing):Boolean {
        val changed = store.transaction { db ->
            val at = now
            if (exists(db, """
                SELECT 1 FROM message_expiration WHERE expires_at<=?1 AND message_id IN (
                    SELECT ?2 UNION ALL SELECT message_id FROM send_related_events WHERE event_id=?2
                    UNION ALL SELECT message_id FROM protocol_receipts WHERE event_id=?2 AND peer=?3
                )
                """, arrayOf(at, event.eventID, event.recipient))) return@transaction null
            db.execSQL("UPDATE message_sends SET first_attempt_at=?1 WHERE first_attempt_at IS NULL AND message_id IN (SELECT ?2 UNION ALL SELECT message_id FROM send_related_events WHERE event_id=?2)", arrayOf(at, event.eventID))
            db.query("SELECT changes()").use { it.moveToFirst(); it.getInt(0) }
        } ?: return true
        if(changed>0)_sendQueueChanges.tryEmit(Unit)
        if(!sendPacket(GhostPacket(GhostPacket.TYPE_V2_SUBMIT,event.recipient,eventBytes(event.eventID),event.wire,null)))return false
        inFlight.add(event.eventID);return true
    }
    suspend fun sendQueue(peer: String? = null, offset: Int = 0, messageId: String? = null, scheduled: Boolean? = null, limit: Int = 50): List<SendQueueEntry>{
        require(offset >= 0 && limit in 1..51);val result=CompletableDeferred<List<SendQueueEntry>>()
        commands.send(Work({result.complete(store.transaction {db->
            val sql="SELECT q.id,q.peer,q.kind,q.body,q.send_at,q.state,q.cancel,q.retry,c.alias,CASE WHEN q.state='failed' THEN CASE WHEN q.retry=0 THEN 'recipient' WHEN EXISTS(SELECT 1 FROM json_each(json_array(json_extract(q.body,'$.object.object_id'),json_extract(q.body,'$.preview.image.object_id'))) ref WHERE ref.value IS NOT NULL AND NOT EXISTS(SELECT 1 FROM media_objects o WHERE o.object_id=ref.value AND o.local_state=1)) THEN 'media' END END AS failure,q.relay_retry_at FROM (SELECT s.event_id AS id,s.recipient AS peer,p.kind AS kind,p.body_json AS body,s.send_at AS send_at,s.state AS state,1 AS cancel,CASE WHEN s.state='failed' AND EXISTS(SELECT 1 FROM contact_states cs WHERE cs.uuid=s.recipient AND cs.accepted=1 AND cs.blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations rv WHERE rv.peer=cs.uuid)) THEN 1 ELSE 0 END AS retry,s.created_at AS created,NULL AS relay_retry_at FROM scheduled_sends s JOIN protocol_pending_events p ON p.event_id=s.event_id UNION ALL SELECT a.message_id,m.conversationUuid,a.kind,a.body_json,NULL,CASE WHEN m.status<0 THEN 'failed' WHEN a.first_attempt_at IS NULL THEN 'queued' ELSE 'sending' END,CASE WHEN a.first_attempt_at IS NULL AND NOT EXISTS(SELECT 1 FROM protocol_outbox b WHERE b.event_id=a.message_id UNION ALL SELECT 1 FROM send_related_events r JOIN protocol_outbox b ON b.event_id=r.event_id WHERE r.message_id=a.message_id) THEN 1 ELSE 0 END,CASE WHEN EXISTS(SELECT 1 FROM contact_states cs WHERE cs.uuid=m.conversationUuid AND cs.accepted=1 AND cs.blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations rv WHERE rv.peer=cs.uuid)) THEN 1 ELSE 0 END,a.created_at,CASE WHEN m.status=0 THEN d.retry_at END FROM message_sends a JOIN messages m ON m.messageId=a.message_id LEFT JOIN protocol_relay_deferrals d ON d.recipient=m.conversationUuid WHERE m.isMine=1 AND m.status<2 AND m.isRevoked=0 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?6)) q LEFT JOIN conversations c ON c.uuid=q.peer WHERE (?1 IS NULL OR q.peer=?1) AND (?2 IS NULL OR q.id=?2) AND (?3 IS NULL OR (q.send_at IS NOT NULL)=?3) ORDER BY COALESCE(q.send_at,q.created),q.id LIMIT ?4 OFFSET ?5"
            db.query(SimpleSQLiteQuery(sql,arrayOf(peer, messageId, scheduled?.let { if (it) 1 else 0 }, limit, offset, now))).use {c->buildList{while(c.moveToNext()){
                val body=JSONObject(c.getString(3));val raw=when(c.getString(2)) {
                    "chat.text" -> SecurityCore.messageSummary(body.getString("text"), 160)
                    "chat.attachment" -> listOf(body.getJSONObject("object").getString("filename"),
                        SecurityCore.messageSummary(body.optString("caption"), 160)).filter { it.isNotBlank() }.joinToString(" · ")
                    "chat.contact" -> body.getString("name")
                    "chat.location" -> body.optString("label","Location")
                    "chat.location.start" -> body.optString("label","Live location")
                    else -> body.getJSONObject("object").getString("filename")
                }
                val end=raw.offsetByCodePoints(0,minOf(160,raw.codePointCount(0,raw.length)))
                val failure = if (c.isNull(9)) null else c.getString(9)
                add(SendQueueEntry(c.getString(0),c.getString(1),raw.substring(0,end),if(c.isNull(4))null else c.getLong(4),
                    body.optBoolean("silent",false),c.getString(5),c.getInt(6)!=0,c.getInt(7)!=0 && failure == null,
                    if(c.isNull(8))null else c.getString(8),failure,
                    if(c.isNull(10))null else c.getLong(10),
                    !c.isNull(4) && scheduledEditField(c.getString(2), body) != null))
            }}}
        })},{result.completeExceptionally(IllegalStateException("Send queue is unavailable"))}));return result.await()
    }
    private fun scheduledEditField(kind: String, body: JSONObject): String? = when {
        kind == "chat.text" -> "text"
        kind == "chat.attachment" && !body.optBoolean("sticker") -> "caption"
        else -> null
    }

    private fun getScheduledBody(db: SupportSQLiteDatabase, id: String, peer: String, at: Long): Pair<String, JSONObject>? =
        db.query(SimpleSQLiteQuery("""
            SELECT p.kind,p.body_json FROM scheduled_sends s
            JOIN protocol_pending_events p ON p.event_id=s.event_id AND p.recipient=s.recipient
            WHERE s.event_id=? AND s.recipient=? AND s.send_at=? AND p.held=1
                AND NOT EXISTS(SELECT 1 FROM protocol_outbox o WHERE o.event_id=p.event_id)
            """, arrayOf(id, peer, at))).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to JSONObject(cursor.getString(1)) else null
        }

    internal suspend fun scheduledMessageDraft(id: String, peer: String, at: Long): ScheduledMessageDraft? {
        val answer = CompletableDeferred<ScheduledMessageDraft?>()
        commands.send(Work({
            answer.complete(store.transaction { db ->
                val (kind, body) = getScheduledBody(db, id, peer, at) ?: return@transaction null
                val field = scheduledEditField(kind, body) ?: return@transaction null
                ScheduledMessageDraft(body.getString(field), field == "caption")
            })
        }, { answer.completeExceptionally(IllegalStateException("Send queue is unavailable")) }))
        return answer.await()
    }

    internal suspend fun editScheduledMessage(id: String, peer: String, at: Long, original: ScheduledMessageDraft,
                                              replacement: String): Boolean = userOperation {
        require(replacement.toByteArray(Charsets.UTF_8).size <= 48 * 1024 &&
            (original.isCaption || replacement.isNotBlank()))
        store.transaction { db ->
            val (kind, body) = getScheduledBody(db, id, peer, at)
                ?: throw VisibleFailure("The scheduled message has changed.")
            val field = scheduledEditField(kind, body)
            if (field == null || (field == "caption") != original.isCaption || body.getString(field) != original.text)
                throw VisibleFailure("The scheduled message has changed.")
            if (replacement == original.text) return@transaction
            val preview = body.optJSONObject("preview")
            if (preview != null) {
                val links = SecurityCore.call(JSONObject().put("operation", "visible_message_urls")
                    .put("text", replacement)).getJSONArray("urls")
                if ((0 until links.length()).none { links.getString(it) == preview.getString("url") }) {
                    body.remove("preview")
                    preview.optJSONObject("image")?.getString("object_id")?.let { objectId ->
                        db.execSQL("DELETE FROM scheduled_objects WHERE event_id=? AND object_id=?", arrayOf(id, objectId))
                    }
                }
            }
            body.put(field, replacement)
            db.execSQL("UPDATE protocol_pending_events SET body_json=? WHERE event_id=? AND recipient=? AND held=1",
                arrayOf(body.toString(), id, peer))
        }
    }

    suspend fun manageSend(
        id: String, action: String, sendAt: Long? = null, expectedPeer: String? = null,
        expectedScheduledAt: Long? = null
    ): Boolean = userOperation {
        store.transaction {db->
            val planned = db.query(SimpleSQLiteQuery(
                "SELECT recipient,send_at FROM scheduled_sends WHERE event_id=?", arrayOf(id)
            )).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null }
            if (expectedScheduledAt != null && planned?.second != expectedScheduledAt)
                throw VisibleFailure("The scheduled message has changed.")
            val scheduled = planned?.first
            if(scheduled!=null){
                if (expectedPeer != null && scheduled != expectedPeer) throw VisibleFailure("Message is no longer pending in this conversation.")
                if(action=="cancel")db.execSQL("DELETE FROM protocol_pending_events WHERE event_id=? AND recipient=?",arrayOf(id,scheduled))
                else {
                    check(exists(db,"SELECT 1 FROM contact_states c WHERE uuid=? AND accepted=1 AND blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=c.uuid)",arrayOf(scheduled))){"Recipient is unavailable"}
                    check(!exists(db,"SELECT 1 FROM protocol_outbox WHERE event_id=?",arrayOf(id)))
                    requireScheduledMedia(db, id)
                    when(action){
                        "send_now"->db.execSQL("UPDATE scheduled_sends SET send_at=?,state='waiting' WHERE event_id=?",arrayOf(now,id))
                        "reschedule"->{require(sendAt!=null && sendAt>now && sendAt<=253402214399999L);db.execSQL("UPDATE scheduled_sends SET send_at=?,state='waiting' WHERE event_id=?",arrayOf(sendAt,id))}
                        "retry"->db.execSQL("UPDATE scheduled_sends SET state='waiting' WHERE event_id=?",arrayOf(id))
                        else->error("Unsupported send action")
                    }
                }
            } else {
                data class Item(val peer:String,val kind:String,val body:JSONObject,val created:Long,val attempted:Boolean)
                val row=db.query(SimpleSQLiteQuery("SELECT m.conversationUuid,a.kind,a.body_json,a.created_at,a.first_attempt_at FROM message_sends a JOIN messages m ON m.messageId=a.message_id WHERE a.message_id=? AND m.isMine=1 AND m.isRevoked=0 AND m.status<2 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?)",arrayOf(id, now))).use{c->if(c.moveToFirst())Item(c.getString(0),c.getString(1),JSONObject(c.getString(2)),c.getLong(3),!c.isNull(4))else null}?:throw VisibleFailure("Message is no longer pending")
                if (expectedPeer != null && row.peer != expectedPeer) throw VisibleFailure("Message is no longer pending in this conversation.")
                when(action){
                    "cancel"->{if(row.attempted || exists(db,"SELECT 1 FROM protocol_outbox b WHERE b.event_id=?1 UNION ALL SELECT 1 FROM send_related_events r JOIN protocol_outbox b ON b.event_id=r.event_id WHERE r.message_id=?1",arrayOf(id)))throw VisibleFailure("This message may already have been sent; it cannot be cancelled")
                        db.execSQL("DELETE FROM protocol_pending_events WHERE event_id IN (SELECT event_id FROM send_related_events WHERE message_id=?)",arrayOf(id))
                        db.execSQL("DELETE FROM protocol_pending_events WHERE event_id=? AND recipient=?",arrayOf(id,row.peer));db.execSQL("DELETE FROM object_transfers WHERE event_id=? AND direction=0",arrayOf(id));db.execSQL("DELETE FROM messages WHERE messageId=? AND isMine=1",arrayOf(id))}
                    "retry"->{check(exists(db,"SELECT 1 FROM contact_states c WHERE uuid=? AND accepted=1 AND blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=c.uuid)",arrayOf(row.peer))){"Recipient is unavailable"}
                        store.enqueue(SignalProtocolStore.Pending(id,row.peer,row.kind,row.body,row.created),db)
                        db.execSQL("UPDATE protocol_outbox SET delivered=0 WHERE event_id=? AND recipient=?",arrayOf(id,row.peer))
                        db.execSQL("UPDATE messages SET status=MAX(status,0) WHERE messageId=?",arrayOf(id))
                        for(manifest in listOfNotNull(row.body.optJSONObject("object"),row.body.optJSONObject("preview")?.optJSONObject("image"))){
                            val objectID=manifest.getString("object_id");check(exists(db,"SELECT 1 FROM media_objects WHERE object_id=? AND local_state=1",arrayOf(objectID))){"Attachment is unavailable"};AttachmentQueue.record(db,id,objectID,true,row.peer,row.created)
                        }
                        db.execSQL("UPDATE object_transfers SET attempts=0,next_retry=0 WHERE event_id=? AND direction=0",arrayOf(id))
                        savedChange(db, prefs.myUuid, id)?.let { change ->
                            if (!change.optBoolean("revoked", false)) {
                                val edit = JSONObject().put("message_id", id).put("revision", change.getLong("revision"))
                                    .put("text", change.getString("text"))
                                if (change.optBoolean("caption")) edit.put("caption", true)
                                store.enqueue(pending(row.peer, "chat.edit", edit), db)
                            }
                        }
                    }
                    else->error("Unsupported send action")
                }
                requested.remove(row.peer);unavailableUntil.remove(row.peer)
            }
            scheduleDeadlineLoaded=false
        };attachments.signal()
    }

    private fun report(error: Exception) {
        // JSON/parser exceptions can include message bodies. Never log their values.
        Log.w("MsgAT_Secure","Secure operation failed: ${error.javaClass.simpleName}")
        _errors.tryEmit(if (error is VisibleFailure) error.message ?: "Secure operation failed" else "Secure operation failed; local state was retained")
    }
    suspend fun close() {
        try {
            try { forwarding.close() }
            finally { attachments.stop() }
        } finally {
            commands.close()
            while (true) { val work = commands.tryReceive().getOrNull() ?: break; reject(work) }
            retry?.cancel()
            scope.coroutineContext[Job]?.cancelAndJoin()
            activityReceived.value = emptyMap()
        }
    }
}
