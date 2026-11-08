// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.core.crypto

import android.util.Base64
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.msgat.core.crypto.securestorage.SecurePrefs
import org.msgat.data.db.AppDatabase
import java.security.MessageDigest
import java.util.UUID

/** SQLCipher/Room owns all libsignal snapshots and durable event mailboxes. */
class SignalProtocolStore(private val database: AppDatabase, private val prefs: SecurePrefs) {
    class Pending(val eventID: String, val recipient: String, val kind: String, val body: JSONObject, val createdAt: Long)
    class Outgoing(val eventID: String, val recipient: String, val wire: ByteArray)
    class Incoming(val sender: String, val eventID: String, val kind: String, val body: JSONObject, val createdAt: Long)

    companion object {
        fun createSchema(db: SupportSQLiteDatabase) {
            for (sql in listOf(
                "CREATE TABLE IF NOT EXISTS protocol_local (id INTEGER PRIMARY KEY CHECK(id=1), identity_json TEXT NOT NULL, bundle_json TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_keys (kind TEXT NOT NULL,id INTEGER NOT NULL,record TEXT NOT NULL,last_resort INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(kind,id))",
                "CREATE TABLE IF NOT EXISTS protocol_key_counters (kind TEXT PRIMARY KEY NOT NULL,next_id INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_key_ages (kind TEXT NOT NULL,id INTEGER NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(kind,id))",
                "CREATE TABLE IF NOT EXISTS protocol_peers (uuid TEXT PRIMARY KEY NOT NULL,state_json TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_pq_uses (fingerprint TEXT PRIMARY KEY NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_outbox (event_id TEXT PRIMARY KEY NOT NULL,recipient TEXT NOT NULL,payload_hash TEXT NOT NULL,wire TEXT NOT NULL,delivered INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS protocol_pending_events (event_id TEXT PRIMARY KEY NOT NULL,recipient TEXT NOT NULL,kind TEXT NOT NULL,body_json TEXT NOT NULL,created_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_key_requests (recipient TEXT PRIMARY KEY NOT NULL,request_id TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_inbox (sender TEXT NOT NULL,event_id TEXT NOT NULL,payload_hash TEXT NOT NULL,PRIMARY KEY(sender,event_id))",
                "CREATE TABLE IF NOT EXISTS protocol_received_wires (wire_hash TEXT PRIMARY KEY NOT NULL,sender TEXT NOT NULL,event_id TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS contact_states (uuid TEXT PRIMARY KEY NOT NULL,outgoing INTEGER NOT NULL DEFAULT 0,incoming INTEGER NOT NULL DEFAULT 0,accepted INTEGER NOT NULL DEFAULT 0,blocked INTEGER NOT NULL DEFAULT 0)"
            )) db.execSQL(sql)
        }
    }

    suspend fun publicBundle(forceRefresh: Boolean = false): JSONObject = transaction { db ->
        prefs.withRootSeed { seed ->
            val request = JSONObject().put("operation", "provision_identity").put("seed", encode(seed)).put("prekey_count", 100)
            val saved = local(db)
            if (saved != null) {
                check(saved.first.getString("uuid") == prefs.myUuid) { "Vault identity mismatch" }
                val body = JSONObject(String(decode(saved.second.getString("body")), Charsets.UTF_8))
                val keys = body.getJSONArray("prekeys")
                check(keys.length() in 1..100) { "Invalid stored prekey range" }
                val ids = (0 until keys.length()).map { keys.getJSONObject(it).getLong("id") }
                val available = db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM protocol_keys WHERE kind='ec' AND id>=? AND id<=?", arrayOf(ids.min(), ids.max()))).use { it.moveToFirst(); it.getLong(0) }
                if (!forceRefresh && body.getLong("expires_at") > seconds + 2 * 86400 && available >= 20) return@withRootSeed saved.second
                request.put("operation", "refresh_identity").put("local", saved.first)
                for ((kind, field) in listOf("ec" to "first_prekey_id", "signed" to "signed_prekey_id", "pq" to "pq_prekey_id")) {
                    val next = db.query(SimpleSQLiteQuery("SELECT next_id FROM protocol_key_counters WHERE kind=?", arrayOf(kind))).use { if (it.moveToFirst()) it.getLong(0) else 0 }
                    check(next in 1..0xffffffffL) { "Missing or exhausted prekey counter" }
                    request.put(field, next)
                }
            } else {
                check(!exists(db, "SELECT 1 FROM protocol_peers UNION ALL SELECT 1 FROM protocol_keys LIMIT 1")) { "Protocol identity is missing from an existing vault" }
            }
            val result = SecurityCore.call(request)
            check(result.getString("uuid") == prefs.myUuid) { "Invalid provisioned identity" }
            val bundle = result.getJSONObject("public_bundle")
            val state = result.getJSONObject("private_state")
            val identity = state.getJSONObject("local_identity")
            check(saved == null || canonical(saved.first) == canonical(identity)) { "Prekey refresh cannot replace identity" }
            db.execSQL("INSERT INTO protocol_local(id,identity_json,bundle_json) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET identity_json=excluded.identity_json,bundle_json=excluded.bundle_json", arrayOf(canonical(identity), canonical(bundle)))
            for ((field, kind) in listOf("prekeys" to "ec", "signed_prekeys" to "signed", "pq_prekeys" to "pq")) {
                val records = state.getJSONArray(field)
                check(records.length() > 0) { "Missing provisioned keys" }
                var next = 1L
                for (index in 0 until records.length()) {
                    val key = records.getJSONObject(index)
                    val id = key.getLong("id")
                    check(id in 1..0xffffffffL) { "Invalid provisioned key identifier" }
                    db.execSQL("INSERT INTO protocol_keys(kind,id,record,last_resort) VALUES(?,?,?,?)", arrayOf(kind,id,key.getString("record"),if (key.optBoolean("last_resort")) 1 else 0))
                    db.execSQL("INSERT INTO protocol_key_ages(kind,id,created_at) VALUES(?,?,?)", arrayOf(kind,id,seconds))
                    next = maxOf(next,id+1)
                }
                db.execSQL("INSERT INTO protocol_key_counters(kind,next_id) VALUES(?,?) ON CONFLICT(kind) DO UPDATE SET next_id=MAX(next_id,excluded.next_id)", arrayOf(kind,next))
            }
            db.execSQL("DELETE FROM protocol_keys WHERE (kind,id) IN (SELECT kind,id FROM protocol_key_ages WHERE created_at<?)", arrayOf(seconds-15*86400))
            db.execSQL("DELETE FROM protocol_key_ages WHERE NOT EXISTS (SELECT 1 FROM protocol_keys k WHERE k.kind=protocol_key_ages.kind AND k.id=protocol_key_ages.id)")
            bundle
        }
    }

    suspend fun hasSession(recipient: String): Boolean = transaction { db -> peer(db,recipient)?.opt("session_record") is String }

    suspend fun enqueue(event: Pending, apply: (SupportSQLiteDatabase) -> Unit = {}) = transaction { db ->
        if (enqueue(event,db)) apply(db)
    }

    fun enqueue(event: Pending, db: SupportSQLiteDatabase): Boolean {
        validate(event.eventID,event.kind,event.createdAt)
        check(event.recipient.matches(Regex("[a-f0-9]{64}"))) { "Invalid recipient identity" }
        val body = canonical(event.body)
        check(body.toByteArray(Charsets.UTF_8).size <= 60*1024) { "Event body exceeds its size limit" }
        db.query(SimpleSQLiteQuery("SELECT recipient,kind,body_json,created_at FROM protocol_pending_events WHERE event_id=?", arrayOf(event.eventID))).use {
            if (it.moveToFirst()) {
                check(it.getString(0)==event.recipient && it.getString(1)==event.kind && it.getString(2)==body && it.getLong(3)==event.createdAt) { "Conflicting pending event" }
                return false
            }
        }
        db.query(SimpleSQLiteQuery("SELECT recipient,payload_hash FROM protocol_outbox WHERE event_id=?", arrayOf(event.eventID))).use {
            if (it.moveToFirst()) {
                check(it.getString(0)==event.recipient && it.getString(1)==digest(payload(event))) { "Conflicting committed event" }
                return false
            }
        }
        db.execSQL("INSERT INTO protocol_pending_events(event_id,recipient,kind,body_json,created_at) VALUES(?,?,?,?,?)", arrayOf(event.eventID,event.recipient,event.kind,body,event.createdAt))
        return true
    }

    suspend fun prepare(event: Pending, bundle: JSONObject? = null, selectedPrekey: Long? = null): Outgoing = transaction { db ->
        validate(event.eventID,event.kind,event.createdAt)
        val plaintext = payload(event)
        try {
            check(plaintext.size <= 64*1024) { "Event exceeds encrypted message limit" }
            val hash = digest(plaintext)
            db.query(SimpleSQLiteQuery("SELECT recipient,payload_hash,wire FROM protocol_outbox WHERE event_id=?", arrayOf(event.eventID))).use {
                if (it.moveToFirst()) {
                    check(it.getString(0)==event.recipient && it.getString(1)==hash) { "Conflicting event identifier" }
                    return@transaction Outgoing(event.eventID,event.recipient,decode(it.getString(2)))
                }
            }
            val identity = requiredLocal(db)
            var remote = peer(db,event.recipient)
            if (remote == null) {
                check(bundle != null) { "A contact key bundle is required" }
                val verified = SecurityCore.call(JSONObject().put("operation","verify_bundle").put("bundle",bundle).put("expected_uuid",event.recipient))
                remote = JSONObject().put("uuid",event.recipient).put("certificate",verified.getJSONObject("bundle").getJSONObject("certificate")).put("session_record",JSONObject.NULL).put("trusted_identity_key",JSONObject.NULL)
            }
            val request = JSONObject().put("operation","encrypt").put("local",identity).put("peer",remote).put("plaintext",encode(plaintext))
            if (bundle != null) request.put("bundle",bundle)
            if (selectedPrekey != null) request.put("selected_prekey",selectedPrekey)
            val result = SecurityCore.call(request)
            savePeer(db,result.getJSONObject("peer_state"),event.recipient)
            val wire = result.getString("wire_bytes")
            db.execSQL("INSERT INTO protocol_outbox(event_id,recipient,payload_hash,wire) VALUES(?,?,?,?)",arrayOf(event.eventID,event.recipient,hash,wire))
            db.execSQL("DELETE FROM protocol_pending_events WHERE event_id=?",arrayOf(event.eventID))
            Outgoing(event.eventID,event.recipient,decode(wire))
        } finally { plaintext.fill(0) }
    }

    suspend fun receive(wire: ByteArray, sender: String, expectedEventID: String, apply: (Incoming,SupportSQLiteDatabase) -> Unit): Boolean = transaction { db ->
        check(wire.size in 1..262000) { "Invalid transport envelope size" }
        val wireHash = digest(wire)
        db.query(SimpleSQLiteQuery("SELECT sender,event_id FROM protocol_received_wires WHERE wire_hash=?",arrayOf(wireHash))).use {
            if (it.moveToFirst()) {
                check(it.getString(0)==sender && it.getString(1)==expectedEventID) { "Relay sender/event mismatch" }
                return@transaction false
            }
        }
        val decoded = SecurityCore.call(JSONObject().put("operation","decode_wire").put("bytes",encode(wire)).put("expected_recipient",prefs.myUuid))
        check(decoded.getString("sender_uuid")==sender) { "Relay sender mismatch" }
        val message = decoded.getJSONObject("wire_message")
        val description = decoded.getJSONObject("prekeys")
        val remote = peer(db,sender) ?: JSONObject().put("uuid",sender).put("certificate",message.getJSONObject("sender_certificate")).put("session_record",JSONObject.NULL).put("trusted_identity_key",JSONObject.NULL)
        val keys = JSONObject()
        for ((field, kind, output) in listOf(Triple("prekey_id","ec","prekey"),Triple("signed_prekey_id","signed","signed_prekey"),Triple("pq_prekey_id","pq","pq_prekey"))) {
            if (description.isNull(field)) continue
            db.query(SimpleSQLiteQuery("SELECT record,last_resort FROM protocol_keys WHERE kind=? AND id=?",arrayOf(kind,description.getLong(field)))).use {
                if (it.moveToFirst()) { keys.put(output,it.getString(0)); if (kind=="pq") keys.put("pq_last_resort",it.getInt(1)!=0) }
            }
        }
        description.optJSONObject("pq_key_use")?.let { use -> keys.put("pq_already_used",exists(db,"SELECT 1 FROM protocol_pq_uses WHERE fingerprint=?",arrayOf(use.getString("fingerprint")))) }
        val result = SecurityCore.call(JSONObject().put("operation","decrypt").put("local",requiredLocal(db)).put("peer",remote).put("wire",message).put("keys",keys))
        val plaintext = decode(result.getString("plaintext"))
        try {
            check(plaintext.size <= 64*1024) { "Invalid decrypted event size" }
            val event = JSONObject(String(plaintext,Charsets.UTF_8))
            check(event.getInt("version")==2) { "Unsupported event version" }
            val id = event.getString("event_id"); val kind=event.getString("kind"); val created=event.getLong("created_at")
            validate(id,kind,created)
            check(id==expectedEventID) { "Relay event identifier mismatch" }
            val hash=digest(plaintext)
            val existing = db.query(SimpleSQLiteQuery("SELECT payload_hash FROM protocol_inbox WHERE sender=? AND event_id=?",arrayOf(sender,id))).use { if (it.moveToFirst()) it.getString(0) else null }
            check(existing==null || existing==hash) { "Conflicting received event" }
            if (existing==null) {
                apply(Incoming(sender,id,kind,event.getJSONObject("body"),created),db)
                db.execSQL("INSERT INTO protocol_inbox(sender,event_id,payload_hash) VALUES(?,?,?)",arrayOf(sender,id,hash))
            }
            savePeer(db,result.getJSONObject("peer_state"),sender)
            if (!result.isNull("consumed_prekey_id")) db.execSQL("DELETE FROM protocol_keys WHERE kind='ec' AND id=?",arrayOf(result.getLong("consumed_prekey_id")))
            if (result.optBoolean("consumed_pq_prekey")) db.execSQL("DELETE FROM protocol_keys WHERE kind='pq' AND id=?",arrayOf(description.getLong("pq_prekey_id")))
            result.optJSONObject("pq_key_use")?.let { db.execSQL("INSERT INTO protocol_pq_uses(fingerprint) VALUES(?)",arrayOf(it.getString("fingerprint"))) }
            db.execSQL("INSERT INTO protocol_received_wires(wire_hash,sender,event_id) VALUES(?,?,?)",arrayOf(wireHash,sender,id))
            existing==null
        } finally { plaintext.fill(0) }
    }

    suspend fun pendingEvents(limit: Int = 64): List<Pending> = transaction { db ->
        db.query(SimpleSQLiteQuery("SELECT event_id,recipient,kind,body_json,created_at FROM protocol_pending_events ORDER BY rowid LIMIT ?",arrayOf(limit.coerceIn(1,256)))).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Pending(cursor.getString(0),cursor.getString(1),cursor.getString(2),JSONObject(cursor.getString(3)),cursor.getLong(4))) }
        }
    }
    suspend fun pendingOutgoing(limit: Int = 8): List<Outgoing> = transaction { db ->
        db.query(SimpleSQLiteQuery("SELECT event_id,recipient,wire FROM protocol_outbox WHERE delivered=0 ORDER BY rowid LIMIT ?",arrayOf(limit.coerceIn(1,256)))).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Outgoing(cursor.getString(0),cursor.getString(1),decode(cursor.getString(2)))) }
        }
    }
    suspend fun markRelayed(id: String, recipient: String) = transaction { db -> db.execSQL("UPDATE protocol_outbox SET delivered=1 WHERE event_id=? AND recipient=?",arrayOf(id,recipient)) }
    suspend fun keyRequestID(recipient: String): String = transaction { db ->
        db.query(SimpleSQLiteQuery("SELECT request_id FROM protocol_key_requests WHERE recipient=?",arrayOf(recipient))).use { if (it.moveToFirst()) return@transaction it.getString(0) }
        UUID.randomUUID().toString().also { db.execSQL("INSERT INTO protocol_key_requests(recipient,request_id) VALUES(?,?)",arrayOf(recipient,it)) }
    }
    suspend fun finishKeyRequest(recipient: String, id: String) = transaction { db -> db.execSQL("DELETE FROM protocol_key_requests WHERE recipient=? AND request_id=?",arrayOf(recipient,id)) }

    suspend fun <T> transaction(block: (SupportSQLiteDatabase) -> T): T = database.withTransaction { block(database.openHelper.writableDatabase) }
    private fun local(db: SupportSQLiteDatabase): Pair<JSONObject,JSONObject>? = db.query("SELECT identity_json,bundle_json FROM protocol_local WHERE id=1").use { if (it.moveToFirst()) JSONObject(it.getString(0)) to JSONObject(it.getString(1)) else null }
    private fun requiredLocal(db: SupportSQLiteDatabase): JSONObject = local(db)?.first?.also { check(it.getString("uuid")==prefs.myUuid) { "Vault identity mismatch" } } ?: error("Protocol identity is not initialized")
    private fun peer(db: SupportSQLiteDatabase, uuid: String): JSONObject? = db.query(SimpleSQLiteQuery("SELECT state_json FROM protocol_peers WHERE uuid=?",arrayOf(uuid))).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    private fun savePeer(db: SupportSQLiteDatabase, state: JSONObject, expected: String) {
        check(state.getString("uuid")==expected) { "Unexpected peer state" }
        db.execSQL("INSERT INTO protocol_peers(uuid,state_json) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET state_json=excluded.state_json",arrayOf(expected,canonical(state)))
    }
    private fun exists(db: SupportSQLiteDatabase, sql: String, args: Array<Any?> = emptyArray()): Boolean = db.query(SimpleSQLiteQuery(sql,args)).use { it.moveToFirst() }
    private fun validate(id: String, kind: String, created: Long) {
        val uuid=UUID.fromString(id)
        check(uuid.toString()==id && uuid.version()==4 && uuid.variant()==2 && kind.isNotEmpty() && kind.toByteArray(Charsets.UTF_8).size<=64 && created>=0) { "Invalid encrypted event fields" }
    }
    private fun payload(event: Pending): ByteArray = canonical(JSONObject().put("version",2).put("event_id",event.eventID).put("kind",event.kind).put("body",event.body).put("created_at",event.createdAt)).toByteArray(Charsets.UTF_8)
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> error("Unsupported JSON value")
    }
    private val seconds: Long get() = System.currentTimeMillis()/1000
    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes,Base64.NO_WRAP)
    private fun decode(encoded: String): ByteArray = Base64.decode(encoded,Base64.NO_WRAP)
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
