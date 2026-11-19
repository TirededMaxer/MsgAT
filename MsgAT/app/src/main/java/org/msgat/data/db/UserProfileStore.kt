// SPDX-License-Identifier: AGPL-3.0-only
package org.msgat.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.msgat.core.media.EncryptedObjectStore
import org.msgat.core.network.AttachmentQueue
import java.util.UUID

internal data class UserProfileSnapshot(val peer: String,val nickname: String,val avatarReference: String?,val revision: Long,val changedAt: Long,val eventId: String)
internal object UserProfileStore {
    fun read(db: SupportSQLiteDatabase,peer: String): UserProfileSnapshot?=db.query(SimpleSQLiteQuery("SELECT nickname,avatar_object,revision,changed_at,event_id FROM user_profiles WHERE peer=?",arrayOf(peer))).use {c ->
        if(!c.moveToFirst())null else UserProfileSnapshot(peer,c.getString(0),if(c.isNull(1))null else EncryptedObjectStore.reference(c.getString(1)),c.getLong(2),c.getLong(3),c.getString(4))
    }
    fun body(db: SupportSQLiteDatabase,self: String,fallback: String): JSONObject {
        val local=read(db,self)?:return JSONObject().put("nickname",fallback)
        val avatar=local.avatarReference?.let {reference ->
            db.query(SimpleSQLiteQuery("SELECT manifest_json FROM media_objects WHERE object_id=?",arrayOf(reference.removePrefix(EncryptedObjectStore.REFERENCE_PREFIX)))).use {c ->
                check(c.moveToFirst()){"Profile image is unavailable"};JSONObject(c.getString(0))
            }
        }
        return JSONObject().put("nickname",local.nickname).put("revision",local.revision).put("avatar",avatar?:JSONObject.NULL)
    }
    fun saveLocal(db: SupportSQLiteDatabase,self: String,nickname: String,avatar: JSONObject?,replaceAvatar: Boolean): UserProfileSnapshot {
        val name=nickname.trim();require(name.isNotEmpty() && name.toByteArray(Charsets.UTF_8).size<=256){"Nickname is empty or too long"}
        val old=read(db,self);val revision=Math.addExact(old?.revision?:0,1);check(revision<=0xffffffffL){"Profile revision limit reached"}
        var id=if(replaceAvatar)null else old?.avatarReference?.removePrefix(EncryptedObjectStore.REFERENCE_PREFIX)
        if(avatar!=null){EncryptedObjectStore.remember(avatar,db,true);id=avatar.getString("object_id")}
        val now=System.currentTimeMillis();val event=UUID.randomUUID().toString()
        put(db,self,name,revision,id,now,event)
        return UserProfileSnapshot(self,name,id?.let(EncryptedObjectStore::reference),revision,now,event)
    }
    fun apply(db: SupportSQLiteDatabase,peer: String,body: JSONObject,event: String,created: Long): UserProfileSnapshot {
        val revision=if(body.has("revision"))body.getLong("revision") else 0L;val old=read(db,peer)
        if(old!=null && (old.revision>revision || old.revision==revision && (revision>0 || old.changedAt>created || old.changedAt==created && old.eventId>=event)))return old
        db.execSQL("DELETE FROM object_transfers WHERE owner_peer=? AND owner_kind='profile' AND direction=1",arrayOf(peer))
        val avatar=body.optJSONObject("avatar");var id: String?=null
        if(avatar!=null) {
            EncryptedObjectStore.remember(avatar,db);val objectID=avatar.getString("object_id");id=objectID
            val ready=db.query(SimpleSQLiteQuery("SELECT 1 FROM media_objects WHERE object_id=? AND local_state=1",arrayOf(objectID))).use {it.moveToFirst()}
            if(!ready)AttachmentQueue.record(db,event,objectID,false,peer,created,"profile")
        }
        val name=body.getString("nickname");put(db,peer,name,revision,id,created,event)
        return UserProfileSnapshot(peer,name,id?.let(EncryptedObjectStore::reference),revision,created,event)
    }
    private fun put(db: SupportSQLiteDatabase,peer: String,name: String,revision: Long,id: String?,created: Long,event: String) {
        db.execSQL("INSERT INTO user_profiles(peer,nickname,revision,avatar_object,changed_at,event_id) VALUES(?,?,?,?,?,?) ON CONFLICT(peer) DO UPDATE SET nickname=excluded.nickname,revision=excluded.revision,avatar_object=excluded.avatar_object,changed_at=excluded.changed_at,event_id=excluded.event_id",arrayOf(peer,name,revision,id,created,event))
    }
}
