// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.data.db

import android.util.Base64
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore
import org.msgat.core.crypto.securestorage.SecurePrefs

internal data class ContactEntry(val id: String,val name: String,val avatar: String?,val accepted: Boolean,val incoming: Boolean,val outgoing: Boolean,val blocked: Boolean,val outcome: String,val revoked: Boolean=false)
internal data class ContactSafetyInfo(val display: String,val scannable: String,val verified: Boolean,val matches: Boolean?=null)
internal class ContactDirectoryStore(private val database: AppDatabase,private val prefs: SecurePrefs){
    private val gate=Any();private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);private val slots=Semaphore(2);private var closed=false
    private suspend fun <T> owned(action:suspend()->T):T {
        val task=synchronized(gate){check(!closed){"Contact store is closed"};scope.async{slots.withPermit{action()}}}
        try{return task.await()}catch(cancelled:CancellationException){task.cancel();withContext(NonCancellable){task.join()};throw cancelled}
    }
    suspend fun close()=withContext(NonCancellable){synchronized(gate){closed=true};scope.coroutineContext[Job]?.cancelAndJoin()}
    private val _changes=MutableStateFlow(0L)
    val changes=_changes.asStateFlow()
    fun invalidate(){_changes.update{it+1}}
    suspend fun list(search: String="",blockedOnly: Boolean=false,offset: Int=0,exactPeer: String? = null): List<ContactEntry> = owned {
        val query=search.trim().take(256)
        database.openHelper.readableDatabase.query(SimpleSQLiteQuery("""
            SELECT c.uuid,COALESCE(p.nickname,v.alias,c.uuid),p.avatar_object,c.accepted,c.incoming,c.outgoing,c.blocked,l.state_json,EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=c.uuid)
            FROM contact_states c LEFT JOIN user_profiles p ON p.peer=c.uuid LEFT JOIN conversations v ON v.uuid=c.uuid LEFT JOIN contact_links l ON l.uuid=c.uuid
            WHERE (c.accepted=1 OR c.incoming=1 OR c.outgoing=1 OR c.blocked=1 OR p.peer IS NOT NULL OR v.uuid IS NOT NULL) AND (?=0 OR c.blocked=1) AND (?='' OR instr(lower(COALESCE(p.nickname,v.alias,'')),lower(?))>0 OR instr(c.uuid,lower(?))>0)
            AND (?='' OR c.uuid=?)
            ORDER BY lower(COALESCE(p.nickname,v.alias,c.uuid)),c.uuid LIMIT 50 OFFSET ?
        """.trimIndent(),arrayOf(if(blockedOnly)1 else 0,query,query,query,exactPeer.orEmpty(),exactPeer.orEmpty(),offset.coerceAtLeast(0)))).use {c->buildList {while(c.moveToNext())add(ContactEntry(c.getString(0),c.getString(1),if(c.isNull(2))null else org.msgat.core.media.EncryptedObjectStore.reference(c.getString(2)),c.getInt(3)!=0,c.getInt(4)!=0,c.getInt(5)!=0,c.getInt(6)!=0,if(c.isNull(7))"none" else JSONObject(c.getString(7)).optString("last_outcome","none"),c.getInt(8)!=0))}}
    }
    private fun safety(db: SupportSQLiteDatabase,peer: String,scan: String?=null): ContactSafetyInfo {
        check(!db.query(SimpleSQLiteQuery("SELECT 1 FROM protocol_revocations WHERE peer=?",arrayOf(peer))).use {it.moveToFirst()}){"This identity was revoked"}
        val bundle=db.query("SELECT bundle_json FROM protocol_local WHERE id=1").use {check(it.moveToFirst());JSONObject(it.getString(0))}
        val local=JSONObject(String(Base64.decode(bundle.getString("body"),Base64.NO_WRAP),Charsets.UTF_8)).getJSONObject("certificate")
        val remote=db.query(SimpleSQLiteQuery("SELECT json_extract(state_json,'$.certificate') FROM protocol_peers WHERE uuid=?",arrayOf(peer))).use {check(it.moveToFirst()){"Exchange a contact request before checking its safety code"};JSONObject(it.getString(0))}
        val result=SecurityCore.call(JSONObject().put("operation","contact_safety").put("local_id",prefs.myUuid).put("local",local).put("peer_id",peer).put("peer",remote).put("scan",scan?:JSONObject.NULL))
        val display=result.getString("display")
        val verified=db.query(SimpleSQLiteQuery("SELECT 1 FROM contact_verifications WHERE uuid=? AND display=?",arrayOf(peer,display))).use {it.moveToFirst()}
        return ContactSafetyInfo(display,result.getString("scannable"),verified,if(scan==null)null else result.getBoolean("matches"))
    }
    suspend fun safety(peer: String): ContactSafetyInfo=owned {database.withTransaction {safety(database.openHelper.writableDatabase,peer)}}
    suspend fun isVerified(peer: String): Boolean = owned {
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            val hasVerification = db.query(SimpleSQLiteQuery("""
                SELECT 1 FROM contact_verifications v JOIN contact_states c ON c.uuid=v.uuid
                WHERE v.uuid=? AND c.accepted=1 AND c.blocked=0
                  AND NOT EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=v.uuid)
            """.trimIndent(), arrayOf(peer))).use { it.moveToFirst() }
            hasVerification && safety(db, peer).verified
        }
    }
    suspend fun verify(peer: String, expected: String): Boolean {
        changeVerification { db ->
            val current = safety(db, peer)
            require(expected.matches(Regex("[0-9]{60}")) && current.display == expected) {
                "Safety code changed; compare it again"
            }
            db.execSQL("INSERT INTO contact_verifications(uuid,display,verified_at) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET display=excluded.display,verified_at=excluded.verified_at",
                arrayOf(peer, expected, System.currentTimeMillis()))
        }
        return true
    }
    suspend fun verifyScan(peer: String, scan: String): Boolean {
        changeVerification { db ->
            val current = safety(db, peer, scan)
            check(current.matches == true) { "Safety QR code does not match this contact" }
            db.execSQL("INSERT INTO contact_verifications(uuid,display,verified_at) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET display=excluded.display,verified_at=excluded.verified_at",
                arrayOf(peer, current.display, System.currentTimeMillis()))
        }
        return true
    }
    suspend fun clearVerification(peer: String) = changeVerification { db ->
        db.execSQL("DELETE FROM contact_verifications WHERE uuid=?", arrayOf(peer))
    }

    private suspend fun changeVerification(change: (SupportSQLiteDatabase) -> Unit) = owned {
        try {
            database.withTransaction { change(database.openHelper.writableDatabase) }
        } finally {
            invalidate()
        }
    }
}
