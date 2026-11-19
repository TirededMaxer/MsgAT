// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.msgat.core.crypto.SecurityCore

internal object ContactStateStore {
    fun read(db: SupportSQLiteDatabase,peer: String): JSONObject {
        val state=db.query(SimpleSQLiteQuery("SELECT state_json FROM contact_links WHERE uuid=?",arrayOf(peer))).use {if(it.moveToFirst())JSONObject(it.getString(0)) else JSONObject()}
        db.query(SimpleSQLiteQuery("SELECT outgoing,incoming,accepted,blocked FROM contact_states WHERE uuid=?",arrayOf(peer))).use {c->if(c.moveToFirst()){
            if(state.optBoolean("accepted") && c.getInt(2)==0)state.put("accepted_ids",org.json.JSONArray())
            listOf("outgoing","incoming","accepted","blocked").forEachIndexed {index,key->state.put(key,c.getInt(index)!=0)}
        }}
        return state
    }
    fun apply(db: SupportSQLiteDatabase,peer: String,action: JSONObject): JSONObject {
        require(peer.matches(Regex("[0-9a-f]{64}"))){"Invalid contact identity"}
        if(action.getString("kind") in listOf("request","accept","unblock"))db.query(SimpleSQLiteQuery("SELECT 1 FROM protocol_revocations WHERE peer=?",arrayOf(peer))).use {check(!it.moveToFirst()){"This identity was revoked"}}
        val result=SecurityCore.call(JSONObject().put("operation","contact_transition").put("state",read(db,peer)).put("action",action))
        val state=result.getJSONObject("state")
        db.execSQL("INSERT INTO contact_links(uuid,state_json) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET state_json=excluded.state_json",arrayOf(peer,state.toString()))
        db.execSQL("INSERT INTO contact_states(uuid,outgoing,incoming,accepted,blocked) VALUES(?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET outgoing=excluded.outgoing,incoming=excluded.incoming,accepted=excluded.accepted,blocked=excluded.blocked",arrayOf(peer,if(state.getBoolean("outgoing"))1 else 0,if(state.getBoolean("incoming"))1 else 0,if(state.getBoolean("accepted"))1 else 0,if(state.getBoolean("blocked"))1 else 0))
        if(!state.getBoolean("incoming"))db.execSQL("DELETE FROM friend_requests WHERE senderUuid=?",arrayOf(peer))
        if(state.getBoolean("blocked") || result.getJSONObject("previous").getBoolean("accepted") && !state.getBoolean("accepted")) {
            db.execSQL("UPDATE messages SET status=-1 WHERE conversationUuid=? AND isMine=1 AND status IN (0,1)",arrayOf(peer))
            db.execSQL("DELETE FROM protocol_pending_events WHERE recipient=?",arrayOf(peer))
            db.execSQL("UPDATE protocol_outbox SET delivered=-2 WHERE delivered>=0 AND recipient=?",arrayOf(peer))
            db.execSQL("DELETE FROM protocol_key_requests WHERE recipient=?",arrayOf(peer))
            db.execSQL("UPDATE protocol_receipts SET pending=0 WHERE peer=?",arrayOf(peer))
            db.execSQL("DELETE FROM activity_watchers WHERE peer=?",arrayOf(peer))
            db.execSQL("UPDATE activity_peers SET state_json=json_set(state_json,'$.inflight',NULL,'$.published',NULL) WHERE peer=?",arrayOf(peer))
            db.execSQL("DELETE FROM object_transfers WHERE owner_peer=? AND direction=0",arrayOf(peer))
        }
        return result
    }
}
