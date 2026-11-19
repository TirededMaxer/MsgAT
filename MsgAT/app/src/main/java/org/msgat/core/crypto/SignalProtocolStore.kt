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
    enum class Delivery { APPLIED, DUPLICATE, DISCARDED, DEFERRED }
    class PendingChangeLimit : Exception("Pending message changes exceed the local history budget")

    companion object {
        fun pruneIncoming(db: SupportSQLiteDatabase, now: Long) {
            db.execSQL("DELETE FROM protocol_incoming_dispositions WHERE expires_at<=?",arrayOf(now))
            db.execSQL("DELETE FROM message_changes WHERE pending=1 AND pending_since<=?",arrayOf(now-15*86_400_000L))
        }

        fun createSchema(db: SupportSQLiteDatabase) {
            val ownsTransaction=!db.inTransaction()
            if(ownsTransaction)db.beginTransaction()
            try {
            val pendingColumns=db.query("PRAGMA table_info(protocol_pending_events)").use{c->buildSet{while(c.moveToNext())add(c.getString(1))}}
            if("created_at" in pendingColumns && "held" !in pendingColumns)db.execSQL("ALTER TABLE protocol_pending_events ADD COLUMN held INTEGER NOT NULL DEFAULT 0 CHECK(held IN (0,1))")
            for (sql in org.msgat.data.db.ConversationIndexStore.schema + org.msgat.data.db.MessagePinsStore.schema + listOf(
                "CREATE TABLE IF NOT EXISTS protocol_local (id INTEGER PRIMARY KEY CHECK(id=1), identity_json TEXT NOT NULL, bundle_json TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_keys (kind TEXT NOT NULL,id INTEGER NOT NULL,record TEXT NOT NULL,last_resort INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(kind,id))",
                "CREATE TABLE IF NOT EXISTS protocol_key_counters (kind TEXT PRIMARY KEY NOT NULL,next_id INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_key_ages (kind TEXT NOT NULL,id INTEGER NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(kind,id))",
                "CREATE TABLE IF NOT EXISTS protocol_peers (uuid TEXT PRIMARY KEY NOT NULL,state_json TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_pq_uses (fingerprint TEXT PRIMARY KEY NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_outbox (event_id TEXT PRIMARY KEY NOT NULL,recipient TEXT NOT NULL,payload_hash TEXT NOT NULL,wire TEXT NOT NULL,delivered INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS protocol_relay_deferrals (recipient TEXT PRIMARY KEY NOT NULL,retry_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_pending_events (event_id TEXT PRIMARY KEY NOT NULL,recipient TEXT NOT NULL,kind TEXT NOT NULL,body_json TEXT NOT NULL,created_at INTEGER NOT NULL,held INTEGER NOT NULL DEFAULT 0 CHECK(held IN (0,1)))",
                "CREATE INDEX IF NOT EXISTS protocol_pending_ready ON protocol_pending_events(held)",
                "CREATE TABLE IF NOT EXISTS scheduled_sends(event_id TEXT PRIMARY KEY NOT NULL REFERENCES protocol_pending_events(event_id) ON DELETE CASCADE,recipient TEXT NOT NULL,send_at INTEGER NOT NULL,created_at INTEGER NOT NULL,state TEXT NOT NULL DEFAULT 'waiting' CHECK(state IN ('waiting','failed')))",
                "CREATE INDEX IF NOT EXISTS scheduled_sends_due ON scheduled_sends(state,send_at,event_id)",
                "CREATE INDEX IF NOT EXISTS scheduled_sends_order ON scheduled_sends(created_at,event_id)",
                "CREATE INDEX IF NOT EXISTS scheduled_sends_peer ON scheduled_sends(recipient,created_at,event_id)",
                "CREATE INDEX IF NOT EXISTS protocol_pending_recipient ON protocol_pending_events(recipient)",
                "CREATE TABLE IF NOT EXISTS scheduled_objects(event_id TEXT NOT NULL REFERENCES scheduled_sends(event_id) ON DELETE CASCADE,object_id TEXT NOT NULL REFERENCES media_objects(object_id),PRIMARY KEY(event_id,object_id))",
                "CREATE INDEX IF NOT EXISTS scheduled_objects_object ON scheduled_objects(object_id)",
                "CREATE TABLE IF NOT EXISTS message_sends(message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(messageId) ON DELETE CASCADE,kind TEXT NOT NULL,body_json TEXT NOT NULL,created_at INTEGER NOT NULL,first_attempt_at INTEGER)",
                "CREATE TABLE IF NOT EXISTS message_delivery_retries(message_id TEXT PRIMARY KEY NOT NULL REFERENCES message_sends(message_id) ON DELETE CASCADE,retry_at INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS message_delivery_retries_due ON message_delivery_retries(retry_at,message_id) WHERE retry_at>0",
                "CREATE TRIGGER IF NOT EXISTS message_delivery_retry_requeued AFTER UPDATE OF delivered ON protocol_outbox WHEN OLD.delivered=1 AND NEW.delivered IN (0,-1,-2) BEGIN UPDATE message_delivery_retries SET retry_at=0 WHERE message_id=NEW.event_id; END",
                "CREATE TABLE IF NOT EXISTS send_related_events(event_id TEXT PRIMARY KEY NOT NULL,message_id TEXT NOT NULL REFERENCES message_sends(message_id) ON DELETE CASCADE)",
                "CREATE INDEX IF NOT EXISTS send_related_message ON send_related_events(message_id)",
                "DROP TRIGGER IF EXISTS send_related_track",
                "CREATE TRIGGER IF NOT EXISTS send_related_track AFTER INSERT ON protocol_pending_events WHEN NEW.kind IN ('chat.edit','chat.revoke','chat.pin') BEGIN INSERT OR IGNORE INTO send_related_events(event_id,message_id) SELECT NEW.event_id,a.message_id FROM message_sends a WHERE a.message_id=json_extract(NEW.body_json,'$.message_id'); END",
                "DROP TRIGGER IF EXISTS send_related_archive",
                "CREATE TRIGGER IF NOT EXISTS send_related_archive AFTER INSERT ON message_sends BEGIN INSERT OR IGNORE INTO send_related_events(event_id,message_id) SELECT p.event_id,NEW.message_id FROM protocol_pending_events p WHERE p.kind IN ('chat.edit','chat.revoke','chat.pin') AND json_extract(p.body_json,'$.message_id')=NEW.message_id; END",
                "INSERT OR IGNORE INTO send_related_events(event_id,message_id) SELECT p.event_id,a.message_id FROM protocol_pending_events p JOIN message_sends a ON a.message_id=json_extract(p.body_json,'$.message_id') WHERE p.kind IN ('chat.edit','chat.revoke','chat.pin')",
                "CREATE TABLE IF NOT EXISTS message_send_objects(message_id TEXT NOT NULL REFERENCES message_sends(message_id) ON DELETE CASCADE,object_id TEXT NOT NULL,PRIMARY KEY(message_id,object_id))",
                "CREATE INDEX IF NOT EXISTS message_send_objects_object ON message_send_objects(object_id)",
                "CREATE TRIGGER IF NOT EXISTS message_send_objects_track AFTER INSERT ON message_sends BEGIN INSERT OR IGNORE INTO message_send_objects(message_id,object_id) SELECT NEW.message_id,json_extract(NEW.body_json,'$.object.object_id') WHERE json_extract(NEW.body_json,'$.object.object_id') IS NOT NULL; INSERT OR IGNORE INTO message_send_objects(message_id,object_id) SELECT NEW.message_id,json_extract(NEW.body_json,'$.preview.image.object_id') WHERE json_extract(NEW.body_json,'$.preview.image.object_id') IS NOT NULL; END",
                "INSERT OR IGNORE INTO message_send_objects(message_id,object_id) SELECT message_id,json_extract(body_json,'$.object.object_id') FROM message_sends WHERE json_extract(body_json,'$.object.object_id') IS NOT NULL",
                "INSERT OR IGNORE INTO message_send_objects(message_id,object_id) SELECT message_id,json_extract(body_json,'$.preview.image.object_id') FROM message_sends WHERE json_extract(body_json,'$.preview.image.object_id') IS NOT NULL",
                "CREATE INDEX IF NOT EXISTS message_sends_order ON message_sends(created_at,message_id)",
                "DROP TRIGGER IF EXISTS message_sends_message",
                "DROP TRIGGER IF EXISTS message_sends_pending",
                "CREATE TRIGGER IF NOT EXISTS message_sends_message AFTER INSERT ON messages WHEN NEW.isMine=1 AND NEW.isRevoked=0 AND NEW.status<2 BEGIN INSERT OR IGNORE INTO message_sends(message_id,kind,body_json,created_at) SELECT NEW.messageId,p.kind,p.body_json,p.created_at FROM protocol_pending_events p WHERE p.event_id=NEW.messageId AND p.recipient=NEW.conversationUuid AND p.kind IN ('chat.text','chat.contact','chat.location','chat.location.start','chat.attachment'); END",
                "CREATE TRIGGER IF NOT EXISTS message_sends_pending AFTER INSERT ON protocol_pending_events WHEN NEW.kind IN ('chat.text','chat.contact','chat.location','chat.location.start','chat.attachment') BEGIN INSERT OR IGNORE INTO message_sends(message_id,kind,body_json,created_at) SELECT m.messageId,NEW.kind,NEW.body_json,NEW.created_at FROM messages m WHERE m.messageId=NEW.event_id AND m.conversationUuid=NEW.recipient AND m.isMine=1 AND m.isRevoked=0 AND m.status<2; END",
                "CREATE TRIGGER IF NOT EXISTS message_sends_complete AFTER UPDATE OF status,isRevoked ON messages WHEN NEW.status>=2 OR NEW.isRevoked=1 BEGIN DELETE FROM message_sends WHERE message_id=NEW.messageId; END",
                "INSERT OR IGNORE INTO message_sends(message_id,kind,body_json,created_at,first_attempt_at) SELECT m.messageId,p.kind,p.body_json,p.created_at,0 FROM messages m JOIN protocol_pending_events p ON p.event_id=m.messageId AND p.recipient=m.conversationUuid WHERE m.isMine=1 AND m.isRevoked=0 AND m.status<2 AND p.kind IN ('chat.text','chat.contact','chat.location','chat.location.start','chat.attachment')",
                "CREATE TABLE IF NOT EXISTS protocol_key_requests (recipient TEXT PRIMARY KEY NOT NULL,request_id TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_revocations (peer TEXT PRIMARY KEY NOT NULL,proof TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_receipts (peer TEXT NOT NULL,message_id TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('delivered','read')),event_id TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL,pending INTEGER NOT NULL DEFAULT 1,next_retry INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(peer,message_id,state))",
                "CREATE INDEX IF NOT EXISTS protocol_receipts_pending ON protocol_receipts(pending)",
                "CREATE INDEX IF NOT EXISTS protocol_receipts_state_pending ON protocol_receipts(state,pending)",
                "CREATE TABLE IF NOT EXISTS privacy_preferences(id INTEGER PRIMARY KEY CHECK(id=1),read_receipts INTEGER NOT NULL DEFAULT 1 CHECK(read_receipts IN (0,1)),share_online INTEGER NOT NULL DEFAULT 0 CHECK(share_online IN (0,1)),share_typing INTEGER NOT NULL DEFAULT 0 CHECK(share_typing IN (0,1)))",
                "INSERT OR IGNORE INTO privacy_preferences(id) VALUES(1)",
                "CREATE TABLE IF NOT EXISTS notification_targets(peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,token TEXT NOT NULL UNIQUE,message_id TEXT NOT NULL REFERENCES messages(messageId) ON DELETE CASCADE,is_pin INTEGER NOT NULL CHECK(is_pin IN (0,1)))",
                "CREATE INDEX IF NOT EXISTS notification_targets_message ON notification_targets(message_id)",
                "CREATE TABLE IF NOT EXISTS read_receipt_suppression(peer TEXT NOT NULL,message_id TEXT NOT NULL,PRIMARY KEY(peer,message_id))",
                "CREATE INDEX IF NOT EXISTS protocol_outbox_pending ON protocol_outbox(delivered)",
                "CREATE TABLE IF NOT EXISTS activity_peers(peer TEXT PRIMARY KEY NOT NULL,state_json TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS activity_watchers(peer TEXT PRIMARY KEY NOT NULL,until INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS activity_watchers_expiry ON activity_watchers(until)",
                "CREATE TABLE IF NOT EXISTS protocol_inbox (sender TEXT NOT NULL,event_id TEXT NOT NULL,payload_hash TEXT NOT NULL,PRIMARY KEY(sender,event_id))",
                "CREATE TABLE IF NOT EXISTS protocol_received_wires (wire_hash TEXT PRIMARY KEY NOT NULL,sender TEXT NOT NULL,event_id TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS protocol_incoming_dispositions (wire_hash TEXT NOT NULL,sender TEXT NOT NULL,event_id TEXT NOT NULL,discarded INTEGER NOT NULL,attempts INTEGER NOT NULL,retry_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(wire_hash,sender,event_id))",
                "CREATE TABLE IF NOT EXISTS contact_states (uuid TEXT PRIMARY KEY NOT NULL,outgoing INTEGER NOT NULL DEFAULT 0,incoming INTEGER NOT NULL DEFAULT 0,accepted INTEGER NOT NULL DEFAULT 0,blocked INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS contact_links(uuid TEXT PRIMARY KEY NOT NULL,state_json TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS contact_verifications(uuid TEXT PRIMARY KEY NOT NULL,display TEXT NOT NULL,verified_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS conversation_drafts (peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,token TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,text TEXT NOT NULL DEFAULT '',reply_id TEXT)",
                "CREATE TABLE IF NOT EXISTS media_objects (object_id TEXT PRIMARY KEY NOT NULL,manifest_json TEXT NOT NULL,local_state INTEGER NOT NULL DEFAULT 0)",
                """
                CREATE TABLE IF NOT EXISTS media_thumbnail_cache(
                    cache_id INTEGER PRIMARY KEY,
                    object_id TEXT NOT NULL REFERENCES media_objects(object_id) ON DELETE CASCADE,
                    thumbnail_object TEXT NOT NULL UNIQUE REFERENCES media_objects(object_id) ON DELETE CASCADE,
                    edge INTEGER NOT NULL CHECK(edge BETWEEN 1 AND 640),
                    CHECK(object_id<>thumbnail_object),
                    UNIQUE(object_id,edge)
                )
                """.trimIndent(),
                """
                CREATE TRIGGER IF NOT EXISTS media_thumbnail_evict
                AFTER UPDATE OF local_state ON media_objects WHEN NEW.local_state<>1
                BEGIN
                    DELETE FROM media_thumbnail_cache WHERE object_id=NEW.object_id;
                END
                """.trimIndent(),
                """
                CREATE TRIGGER IF NOT EXISTS media_thumbnail_trim
                AFTER INSERT ON media_thumbnail_cache
                BEGIN
                    DELETE FROM media_thumbnail_cache WHERE cache_id IN (
                        SELECT cache_id FROM media_thumbnail_cache ORDER BY cache_id DESC LIMIT -1 OFFSET 192
                    );
                END
                """.trimIndent(),
                "CREATE TABLE IF NOT EXISTS media_staging(object_id TEXT PRIMARY KEY NOT NULL,owner TEXT NOT NULL,created_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS media_garbage(object_id TEXT PRIMARY KEY NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_retry INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS media_cache_evictions(object_id TEXT PRIMARY KEY NOT NULL REFERENCES media_objects(object_id) ON DELETE CASCADE,file_pending INTEGER NOT NULL DEFAULT 1 CHECK(file_pending IN (0,1)),attempts INTEGER NOT NULL DEFAULT 0,next_retry INTEGER NOT NULL DEFAULT 0)",
                "CREATE INDEX IF NOT EXISTS media_cache_evictions_due ON media_cache_evictions(file_pending,next_retry,object_id)",
                "CREATE INDEX IF NOT EXISTS media_garbage_due ON media_garbage(next_retry)",
                "CREATE TRIGGER IF NOT EXISTS media_objects_gc AFTER DELETE ON media_objects BEGIN INSERT OR IGNORE INTO media_garbage(object_id) VALUES(OLD.object_id); END",
                "CREATE TABLE IF NOT EXISTS user_profiles(peer TEXT PRIMARY KEY NOT NULL,nickname TEXT NOT NULL,revision INTEGER NOT NULL,avatar_object TEXT REFERENCES media_objects(object_id),changed_at INTEGER NOT NULL,event_id TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS message_forwards(message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(messageId) ON DELETE CASCADE,name TEXT,original_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS saved_messages(id TEXT PRIMARY KEY NOT NULL,source_peer TEXT NOT NULL,source_id TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('txt','attachment')),body TEXT NOT NULL,object_id TEXT REFERENCES media_objects(object_id),saved_at INTEGER NOT NULL,author_name TEXT NOT NULL,original_at INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS saved_messages_order ON saved_messages(saved_at DESC,id DESC)",
                "CREATE INDEX IF NOT EXISTS saved_messages_by_source ON saved_messages(source_peer,saved_at DESC,id DESC)",
                "CREATE TABLE IF NOT EXISTS forward_jobs(id TEXT PRIMARY KEY NOT NULL,request_json TEXT NOT NULL CHECK(length(request_json)<=65536),created_at INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN ('waiting','preparing','failed')),error_code TEXT)",
                org.msgat.core.call.CallHistory.table,
                org.msgat.core.call.CallHistory.index,
                "CREATE INDEX IF NOT EXISTS forward_jobs_order ON forward_jobs(created_at,id)",
                "CREATE TABLE IF NOT EXISTS object_transfers(event_id TEXT NOT NULL,object_id TEXT NOT NULL REFERENCES media_objects(object_id) ON DELETE CASCADE,owner_peer TEXT NOT NULL,owner_kind TEXT NOT NULL CHECK(owner_kind IN ('message','profile')),created_at INTEGER NOT NULL,direction INTEGER NOT NULL CHECK(direction IN (0,1)),attempts INTEGER NOT NULL DEFAULT 0,next_retry INTEGER NOT NULL DEFAULT 0,remote_expiry INTEGER NOT NULL DEFAULT 0,lease_session TEXT NOT NULL DEFAULT '',PRIMARY KEY(event_id,object_id,direction))",
                "CREATE INDEX IF NOT EXISTS object_transfers_retry ON object_transfers(next_retry,direction)",
                "CREATE TABLE IF NOT EXISTS object_leases(object_id TEXT PRIMARY KEY NOT NULL REFERENCES media_objects(object_id) ON DELETE CASCADE,lease_session TEXT NOT NULL,remote_expiry INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS message_attachments (message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(messageId) ON DELETE CASCADE,object_id TEXT NOT NULL,caption TEXT NOT NULL DEFAULT '')",
                "CREATE TABLE IF NOT EXISTS message_album_items(message_id TEXT PRIMARY KEY NOT NULL REFERENCES message_attachments(message_id) ON DELETE CASCADE,peer TEXT NOT NULL,is_mine INTEGER NOT NULL CHECK(is_mine IN (0,1)),album_id TEXT NOT NULL,position INTEGER NOT NULL,item_count INTEGER NOT NULL CHECK(item_count BETWEEN 2 AND 10),CHECK(position>=0 AND position<item_count),UNIQUE(peer,is_mine,album_id,position))",
                "CREATE INDEX IF NOT EXISTS message_attachments_object ON message_attachments(object_id)",
                "CREATE INDEX IF NOT EXISTS object_transfers_object ON object_transfers(object_id)",
                "CREATE INDEX IF NOT EXISTS saved_messages_object ON saved_messages(object_id)",
                "CREATE TABLE IF NOT EXISTS object_transfer_pins(object_id TEXT NOT NULL REFERENCES media_objects(object_id) ON DELETE CASCADE,direction INTEGER NOT NULL,owner TEXT NOT NULL,PRIMARY KEY(object_id,direction))",
                "CREATE TABLE IF NOT EXISTS web_preview_preferences(id INTEGER PRIMARY KEY CHECK(id=1),enabled INTEGER NOT NULL CHECK(enabled IN(0,1)))",
                "CREATE TABLE IF NOT EXISTS message_previews(owner_kind TEXT NOT NULL CHECK(owner_kind IN('message','saved')),owner_id TEXT NOT NULL,url TEXT NOT NULL,title TEXT NOT NULL,summary TEXT NOT NULL,site TEXT NOT NULL,image_object TEXT REFERENCES media_objects(object_id),above_text INTEGER NOT NULL DEFAULT 0 CHECK(above_text IN (0,1)),compact_image INTEGER NOT NULL DEFAULT 0 CHECK(compact_image IN (0,1)),PRIMARY KEY(owner_kind,owner_id))",
                "CREATE INDEX IF NOT EXISTS message_previews_image ON message_previews(image_object)",
                "CREATE TABLE IF NOT EXISTS sticker_packs(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL CHECK(length(name)>0 AND length(name)<=128),created_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS sticker_items(id TEXT PRIMARY KEY NOT NULL,object_id TEXT NOT NULL UNIQUE REFERENCES media_objects(object_id),sha256 TEXT NOT NULL UNIQUE,mime TEXT NOT NULL,size INTEGER NOT NULL CHECK(size>0 AND size<=8388608),width INTEGER NOT NULL CHECK(width>0 AND width<=2048),height INTEGER NOT NULL CHECK(height>0 AND height<=2048),frames INTEGER NOT NULL CHECK(frames>0 AND frames<=300),label TEXT NOT NULL,added_at INTEGER NOT NULL,favorite INTEGER NOT NULL DEFAULT 0 CHECK(favorite IN(0,1)),last_used INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS sticker_pack_items(pack_id TEXT NOT NULL REFERENCES sticker_packs(id) ON DELETE CASCADE,sticker_id TEXT NOT NULL REFERENCES sticker_items(id) ON DELETE CASCADE,position INTEGER NOT NULL,PRIMARY KEY(pack_id,sticker_id))",
                "CREATE INDEX IF NOT EXISTS sticker_pack_items_asset ON sticker_pack_items(sticker_id)",
                "CREATE INDEX IF NOT EXISTS sticker_items_order ON sticker_items(added_at DESC,id DESC)",
                "CREATE INDEX IF NOT EXISTS sticker_items_recent ON sticker_items(last_used DESC,id DESC)",
                "CREATE TABLE IF NOT EXISTS message_contact_cards(message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(messageId) ON DELETE CASCADE,account TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS message_locations(message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(messageId) ON DELETE CASCADE,latitude_e6 INTEGER NOT NULL CHECK(latitude_e6 BETWEEN -90000000 AND 90000000),longitude_e6 INTEGER NOT NULL CHECK(longitude_e6 BETWEEN -180000000 AND 180000000))",
                "CREATE TABLE IF NOT EXISTS live_location_states(peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,is_mine INTEGER NOT NULL CHECK(is_mine IN (0,1)),message_id TEXT NOT NULL,expires_at INTEGER NOT NULL,anchored INTEGER NOT NULL CHECK(anchored IN (0,1)),state_json TEXT NOT NULL,PRIMARY KEY(peer,is_mine,message_id))",
                "CREATE INDEX IF NOT EXISTS live_location_orphan_expiry ON live_location_states(expires_at,peer) WHERE anchored=0",
                "CREATE TABLE IF NOT EXISTS live_location_outgoing(event_id TEXT PRIMARY KEY NOT NULL,peer TEXT NOT NULL,message_id TEXT NOT NULL REFERENCES messages(messageId) ON DELETE CASCADE,expires_at INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS live_location_outgoing_message ON live_location_outgoing(message_id)",
                "CREATE INDEX IF NOT EXISTS live_location_outgoing_expiry ON live_location_outgoing(expires_at)",
                "CREATE TRIGGER IF NOT EXISTS live_location_discard_outgoing AFTER DELETE ON live_location_outgoing BEGIN DELETE FROM protocol_pending_events WHERE event_id=OLD.event_id AND recipient=OLD.peer; DELETE FROM protocol_outbox WHERE event_id=OLD.event_id AND recipient=OLD.peer; END",
                "CREATE TRIGGER IF NOT EXISTS live_location_delete AFTER DELETE ON messages WHEN OLD.ext='live_location' BEGIN DELETE FROM live_location_states WHERE peer=OLD.conversationUuid AND is_mine=OLD.isMine AND message_id=OLD.messageId; END",
                "CREATE TRIGGER IF NOT EXISTS live_location_revoke AFTER UPDATE OF isRevoked ON messages WHEN NEW.ext='live_location' AND NEW.isRevoked=1 BEGIN DELETE FROM live_location_states WHERE peer=NEW.conversationUuid AND is_mine=NEW.isMine AND message_id=NEW.messageId; END",
                "CREATE TRIGGER IF NOT EXISTS live_location_revoke_outgoing AFTER UPDATE OF isRevoked ON messages WHEN NEW.ext='live_location' AND NEW.isRevoked=1 AND NEW.isMine=1 BEGIN DELETE FROM live_location_outgoing WHERE message_id=NEW.messageId; END",
                "CREATE TABLE IF NOT EXISTS message_stickers(message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(messageId) ON DELETE CASCADE)",
                "CREATE VIEW IF NOT EXISTS conversation_media AS SELECT m.messageId AS message_id,m.conversationUuid AS peer,m.timestamp AS created_at,m.isMine AS is_mine,o.object_id,json_extract(o.manifest_json,'$.filename') AS filename,json_extract(o.manifest_json,'$.mime') AS mime,json_extract(o.manifest_json,'$.size') AS size,o.local_state AS available,a.caption,CASE WHEN json_extract(o.manifest_json,'$.mime') GLOB 'image/*' THEN 'photos' WHEN json_extract(o.manifest_json,'$.mime') GLOB 'video/*' THEN 'videos' WHEN json_extract(o.manifest_json,'$.mime') GLOB 'audio/*' THEN 'audio' ELSE 'files' END AS category FROM messages m JOIN message_attachments a ON a.message_id=m.messageId JOIN media_objects o ON o.object_id=a.object_id WHERE m.ext='attachment' AND m.isRevoked=0 AND NOT EXISTS(SELECT 1 FROM message_stickers s WHERE s.message_id=m.messageId)",
                "CREATE TABLE IF NOT EXISTS saved_contact_cards(id TEXT PRIMARY KEY NOT NULL REFERENCES saved_messages(id) ON DELETE CASCADE,account TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS saved_locations(id TEXT PRIMARY KEY NOT NULL REFERENCES saved_messages(id) ON DELETE CASCADE,latitude_e6 INTEGER NOT NULL CHECK(latitude_e6 BETWEEN -90000000 AND 90000000),longitude_e6 INTEGER NOT NULL CHECK(longitude_e6 BETWEEN -180000000 AND 180000000))",
                "CREATE TABLE IF NOT EXISTS saved_stickers(id TEXT PRIMARY KEY NOT NULL REFERENCES saved_messages(id) ON DELETE CASCADE)",
                "DROP VIEW IF EXISTS media_object_references",
                "CREATE VIEW media_object_references AS SELECT object_id FROM message_attachments UNION ALL SELECT object_id FROM object_transfers UNION ALL SELECT object_id FROM saved_messages UNION ALL SELECT image_object AS object_id FROM message_previews WHERE image_object IS NOT NULL UNION ALL SELECT object_id FROM sticker_items UNION ALL SELECT object_id FROM object_transfer_pins UNION ALL SELECT avatar_object AS object_id FROM user_profiles WHERE avatar_object IS NOT NULL UNION ALL SELECT object_id FROM scheduled_objects UNION ALL SELECT object_id FROM message_send_objects UNION ALL SELECT thumbnail_object AS object_id FROM media_thumbnail_cache",
                """
                CREATE TRIGGER IF NOT EXISTS media_thumbnail_gc_delete
                AFTER DELETE ON media_thumbnail_cache
                BEGIN
                    DELETE FROM media_objects WHERE object_id=OLD.thumbnail_object
                      AND NOT EXISTS(SELECT 1 FROM media_object_references r WHERE r.object_id=OLD.thumbnail_object)
                      AND NOT EXISTS(SELECT 1 FROM media_staging s WHERE s.object_id=OLD.thumbnail_object);
                END
                """.trimIndent(),
                """
                CREATE TRIGGER IF NOT EXISTS media_thumbnail_gc_replace
                AFTER UPDATE OF thumbnail_object ON media_thumbnail_cache
                WHEN OLD.thumbnail_object<>NEW.thumbnail_object
                BEGIN
                    DELETE FROM media_objects WHERE object_id=OLD.thumbnail_object
                      AND NOT EXISTS(SELECT 1 FROM media_object_references r WHERE r.object_id=OLD.thumbnail_object)
                      AND NOT EXISTS(SELECT 1 FROM media_staging s WHERE s.object_id=OLD.thumbnail_object);
                END
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS user_profiles_avatar ON user_profiles(avatar_object)",
                "CREATE TRIGGER IF NOT EXISTS user_profiles_delete_avatar AFTER DELETE ON user_profiles WHEN OLD.avatar_object IS NOT NULL BEGIN DELETE FROM media_objects WHERE object_id=OLD.avatar_object AND NOT EXISTS(SELECT 1 FROM media_object_references WHERE object_id=OLD.avatar_object) AND NOT EXISTS(SELECT 1 FROM media_staging WHERE object_id=OLD.avatar_object); END",
                "CREATE TRIGGER IF NOT EXISTS user_profiles_update_avatar AFTER UPDATE OF avatar_object ON user_profiles WHEN OLD.avatar_object IS NOT NULL AND OLD.avatar_object IS NOT NEW.avatar_object BEGIN DELETE FROM media_objects WHERE object_id=OLD.avatar_object AND NOT EXISTS(SELECT 1 FROM media_object_references WHERE object_id=OLD.avatar_object) AND NOT EXISTS(SELECT 1 FROM media_staging WHERE object_id=OLD.avatar_object); END",
                "CREATE TRIGGER IF NOT EXISTS messages_delete_transfers AFTER DELETE ON messages BEGIN DELETE FROM object_transfers WHERE owner_kind='message' AND event_id=OLD.messageId; END",
                "CREATE TRIGGER IF NOT EXISTS object_transfer_pins_gc AFTER DELETE ON object_transfer_pins BEGIN DELETE FROM media_objects WHERE object_id=OLD.object_id AND NOT EXISTS(SELECT 1 FROM media_object_references WHERE object_id=OLD.object_id) AND NOT EXISTS(SELECT 1 FROM media_staging WHERE object_id=OLD.object_id); END",
                "CREATE TRIGGER IF NOT EXISTS message_previews_gc AFTER DELETE ON message_previews WHEN OLD.image_object IS NOT NULL BEGIN DELETE FROM media_objects WHERE object_id=OLD.image_object AND NOT EXISTS(SELECT 1 FROM media_object_references WHERE object_id=OLD.image_object) AND NOT EXISTS(SELECT 1 FROM media_staging WHERE object_id=OLD.image_object); END",
                "CREATE TRIGGER IF NOT EXISTS messages_delete_preview AFTER DELETE ON messages BEGIN DELETE FROM message_previews WHERE owner_kind='message' AND owner_id=OLD.messageId; END",
                "CREATE TRIGGER IF NOT EXISTS saved_delete_preview AFTER DELETE ON saved_messages BEGIN DELETE FROM message_previews WHERE owner_kind='saved' AND owner_id=OLD.id; END",
                "CREATE TRIGGER IF NOT EXISTS messages_change_preview AFTER UPDATE OF content,isRevoked ON messages WHEN NEW.isRevoked=1 OR OLD.content IS NOT NEW.content BEGIN DELETE FROM message_previews WHERE owner_kind='message' AND owner_id=OLD.messageId; END",
                "CREATE TRIGGER IF NOT EXISTS sticker_items_gc AFTER DELETE ON sticker_items BEGIN DELETE FROM media_objects WHERE object_id=OLD.object_id AND NOT EXISTS(SELECT 1 FROM media_object_references WHERE object_id=OLD.object_id) AND NOT EXISTS(SELECT 1 FROM media_staging WHERE object_id=OLD.object_id); END",
                "CREATE TRIGGER IF NOT EXISTS sticker_membership_gc AFTER DELETE ON sticker_pack_items BEGIN DELETE FROM sticker_items WHERE id=OLD.sticker_id AND favorite=0 AND NOT EXISTS(SELECT 1 FROM sticker_pack_items WHERE sticker_id=OLD.sticker_id); END",
                "CREATE TRIGGER IF NOT EXISTS sticker_favorite_gc AFTER UPDATE OF favorite ON sticker_items WHEN NEW.favorite=0 BEGIN DELETE FROM sticker_items WHERE id=NEW.id AND NOT EXISTS(SELECT 1 FROM sticker_pack_items WHERE sticker_id=NEW.id); END",
                "CREATE TRIGGER IF NOT EXISTS revoked_sticker_marker AFTER UPDATE OF isRevoked ON messages WHEN NEW.isRevoked=1 BEGIN DELETE FROM message_stickers WHERE message_id=NEW.messageId; END",
                "CREATE TRIGGER IF NOT EXISTS object_transfers_gc AFTER DELETE ON object_transfers BEGIN DELETE FROM media_objects WHERE object_id=OLD.object_id AND NOT EXISTS(SELECT 1 FROM media_object_references WHERE object_id=OLD.object_id) AND NOT EXISTS(SELECT 1 FROM media_staging WHERE object_id=OLD.object_id); END",
                "CREATE TRIGGER IF NOT EXISTS message_attachments_gc AFTER DELETE ON message_attachments BEGIN DELETE FROM media_objects WHERE object_id=OLD.object_id AND NOT EXISTS(SELECT 1 FROM media_object_references WHERE object_id=OLD.object_id) AND NOT EXISTS(SELECT 1 FROM media_staging WHERE object_id=OLD.object_id); END",
                "CREATE TRIGGER IF NOT EXISTS scheduled_objects_gc AFTER DELETE ON scheduled_objects BEGIN DELETE FROM media_objects WHERE object_id=OLD.object_id AND NOT EXISTS(SELECT 1 FROM media_object_references WHERE object_id=OLD.object_id) AND NOT EXISTS(SELECT 1 FROM media_staging WHERE object_id=OLD.object_id); END",
                "CREATE TRIGGER IF NOT EXISTS message_send_objects_gc AFTER DELETE ON message_send_objects BEGIN DELETE FROM media_objects WHERE object_id=OLD.object_id AND NOT EXISTS(SELECT 1 FROM media_object_references WHERE object_id=OLD.object_id) AND NOT EXISTS(SELECT 1 FROM media_staging WHERE object_id=OLD.object_id); END",
                "CREATE TRIGGER IF NOT EXISTS saved_messages_gc AFTER DELETE ON saved_messages WHEN OLD.object_id IS NOT NULL BEGIN DELETE FROM media_objects WHERE object_id=OLD.object_id AND NOT EXISTS(SELECT 1 FROM media_object_references WHERE object_id=OLD.object_id) AND NOT EXISTS(SELECT 1 FROM media_staging WHERE object_id=OLD.object_id); END",
                "CREATE TABLE IF NOT EXISTS message_changes (author TEXT NOT NULL,message_id TEXT NOT NULL,conversation_uuid TEXT NOT NULL,state_json TEXT NOT NULL,pending INTEGER NOT NULL DEFAULT 1,stored_bytes INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(author,message_id))",
                "CREATE INDEX IF NOT EXISTS message_changes_pending ON message_changes(pending,author,stored_bytes)"
            ) + org.msgat.data.db.MessageExpirationStore.schema) db.execSQL(sql)
            val notificationColumns = db.query("PRAGMA table_info(notification_targets)").use { columns ->
                generateSequence { if (columns.moveToNext()) columns.getString(1) else null }.toSet()
            }
            if ("is_pin" !in notificationColumns) {
                db.execSQL("ALTER TABLE notification_targets ADD COLUMN is_pin INTEGER NOT NULL DEFAULT 0 CHECK(is_pin IN (0,1))")
                db.execSQL("DELETE FROM notification_targets")
            }
            val previewColumns = db.query("PRAGMA table_info(message_previews)").use { columns ->
                generateSequence { if (columns.moveToNext()) columns.getString(1) else null }.toSet()
            }
            for (column in listOf("above_text", "compact_image")) {
                if (column !in previewColumns) db.execSQL("ALTER TABLE message_previews ADD COLUMN $column INTEGER NOT NULL DEFAULT 0 CHECK($column IN (0,1))")
            }
            val hasDraftQuote = db.query("PRAGMA table_info(conversation_drafts)").use { columns ->
                generateSequence { if (columns.moveToNext()) columns.getString(1) else null }.any { it == "reply_quote" }
            }
            if (!hasDraftQuote) db.execSQL("ALTER TABLE conversation_drafts ADD COLUMN reply_quote TEXT")
            db.execSQL("UPDATE protocol_pending_events SET held=1 WHERE held=0 AND EXISTS(SELECT 1 FROM scheduled_sends s WHERE s.event_id=protocol_pending_events.event_id)")
            val privacyColumns=db.query("PRAGMA table_info(privacy_preferences)").use {c->buildSet{while(c.moveToNext())add(c.getString(1))}}
            for (column in listOf("message_alerts_enabled", "message_alert_sound")) {
                if (column !in privacyColumns) db.execSQL("ALTER TABLE privacy_preferences ADD COLUMN $column INTEGER NOT NULL DEFAULT 1 CHECK($column IN (0,1))")
            }
            for(column in listOf("share_online","share_typing","show_notification_previews"))if(column !in privacyColumns)db.execSQL("ALTER TABLE privacy_preferences ADD COLUMN $column INTEGER NOT NULL DEFAULT 0 CHECK($column IN (0,1))")
            if ("auto_download_attachments" !in privacyColumns)
                db.execSQL("ALTER TABLE privacy_preferences ADD COLUMN auto_download_attachments INTEGER NOT NULL DEFAULT 1 CHECK(auto_download_attachments IN (0,1))")
            if ("auto_download_limit_bytes" !in privacyColumns)
                db.execSQL("ALTER TABLE privacy_preferences ADD COLUMN auto_download_limit_bytes INTEGER NOT NULL DEFAULT 0 CHECK(auto_download_limit_bytes>=0)")
            if ("app_lock_enabled" !in privacyColumns)
                db.execSQL("ALTER TABLE privacy_preferences ADD COLUMN app_lock_enabled INTEGER NOT NULL DEFAULT 0 CHECK(app_lock_enabled IN (0,1))")
            val transferColumns = db.query("PRAGMA table_info(object_transfers)").use { rows ->
                buildSet { while (rows.moveToNext()) add(rows.getString(1)) }
            }
            if ("download_requested" !in transferColumns)
                db.execSQL("ALTER TABLE object_transfers ADD COLUMN download_requested INTEGER NOT NULL DEFAULT 0 CHECK(download_requested IN (0,1))")
            if ("download_paused" !in transferColumns)
                db.execSQL("ALTER TABLE object_transfers ADD COLUMN download_paused INTEGER NOT NULL DEFAULT 0 CHECK(download_paused IN (0,1))")
            if ("download_unavailable" !in transferColumns)
                db.execSQL("ALTER TABLE object_transfers ADD COLUMN download_unavailable INTEGER NOT NULL DEFAULT 0 CHECK(download_unavailable IN (0,1))")
            if(db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='media_transfers'").use {it.moveToFirst()}) {
                check(!db.query("SELECT 1 FROM media_transfers t LEFT JOIN messages m ON m.messageId=t.message_id LEFT JOIN media_objects o ON o.object_id=t.object_id WHERE m.messageId IS NULL OR o.object_id IS NULL").use {it.moveToFirst()}){"Stored transfer state is inconsistent. Existing data was retained"}
                db.execSQL("INSERT OR IGNORE INTO object_transfers(event_id,object_id,owner_peer,owner_kind,created_at,direction,attempts,next_retry,remote_expiry,lease_session) SELECT t.message_id,t.object_id,m.conversationUuid,'message',m.timestamp,t.direction,t.attempts,t.next_retry,t.remote_expiry,t.lease_session FROM media_transfers t JOIN messages m ON m.messageId=t.message_id JOIN media_objects o ON o.object_id=t.object_id")
                db.execSQL("DROP TABLE media_transfers")
            }
            val now = System.currentTimeMillis()
            val changeColumns = db.query("PRAGMA table_info(message_changes)").use { rows ->
                buildSet { while (rows.moveToNext()) add(rows.getString(1)) }
            }
            if ("pending_since" !in changeColumns) {
                db.execSQL("ALTER TABLE message_changes ADD COLUMN pending_since INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE message_changes SET pending_since=? WHERE pending=1",arrayOf(now))
            }
            pruneIncoming(db,now)
            org.msgat.data.db.MessageExpirationStore.markDueRetention(db, now, limit = -1)
            db.execSQL("""DELETE FROM messages WHERE messageId IN (
                SELECT message_id FROM message_expiration WHERE expires_at<=?)""", arrayOf(now))
            if(ownsTransaction)db.setTransactionSuccessful()
            } finally {if(ownsTransaction)db.endTransaction()}
        }
    }

    suspend fun publicBundle(forceRefresh: Boolean = false, directoryEpoch: String? = null): JSONObject = transaction { db ->
        prefs.withRootSeed { seed ->
            val request = JSONObject().put("operation", "provision_identity").put("seed", encode(seed)).put("prekey_count", 100)
            if (directoryEpoch != null) request.put("directory_epoch", directoryEpoch)
            val saved = local(db)
            if (saved != null) {
                check(saved.first.getString("uuid") == prefs.myUuid) { "Vault identity mismatch" }
                val body = JSONObject(String(decode(saved.second.getString("body")), Charsets.UTF_8))
                val keys = body.getJSONArray("prekeys")
                check(keys.length() in 1..100) { "Invalid stored prekey range" }
                val ids = (0 until keys.length()).map { keys.getJSONObject(it).getLong("id") }
                val available = db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM protocol_keys WHERE kind='ec' AND id>=? AND id<=?", arrayOf(ids.min(), ids.max()))).use { it.moveToFirst(); it.getLong(0) }
                val previousEpoch = body.optString("directory_epoch").takeIf(String::isNotEmpty)
                if (!forceRefresh && (directoryEpoch == null || directoryEpoch == previousEpoch) &&
                    body.getLong("expires_at") > seconds + 2 * 86400 && available >= 20) return@withRootSeed saved.second
                if (directoryEpoch == null && previousEpoch != null) request.put("directory_epoch", previousEpoch)
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
        check(!exists(db,"SELECT 1 FROM protocol_revocations WHERE peer=?",arrayOf(event.recipient))) { "The recipient identity was revoked" }
        validate(event.eventID,event.kind,event.createdAt)
        check(event.recipient.matches(Regex("[a-f0-9]{64}"))) { "Invalid recipient identity" }
        val validationBytes = payload(event)
        try { validateApplicationEvent(validationBytes) } finally { validationBytes.fill(0) }
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
        if(event.kind in setOf("contact.request","contact.accept","contact.profile")) event.body.optJSONObject("avatar")?.let { avatar ->
            org.msgat.core.media.EncryptedObjectStore.remember(avatar,db)
            org.msgat.core.network.AttachmentQueue.record(db,event.eventID,avatar.getString("object_id"),true,event.recipient,event.createdAt,"profile")
        }
        return true
    }

    suspend fun prepare(event: Pending, bundle: JSONObject? = null, selectedPrekey: Long? = null): Outgoing = transaction { db ->
        check(!exists(db,"SELECT 1 FROM scheduled_sends WHERE event_id=?",arrayOf(event.eventID))){"Scheduled message has not been released"}
        validate(event.eventID,event.kind,event.createdAt)
        val plaintext = payload(event)
        try {
            check(plaintext.size <= 64*1024) { "Event exceeds encrypted message limit" }
            validateApplicationEvent(plaintext)
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

    suspend fun receive(wire: ByteArray, sender: String, expectedEventID: String, duplicate: (String,String,SupportSQLiteDatabase) -> Unit = { _,_,_ -> }, apply: (Incoming,SupportSQLiteDatabase) -> Unit): Delivery {
        val wireHash = digest(wire)
        val now = System.currentTimeMillis()
        return try {
            transaction { db -> receive(db,wire,sender,expectedEventID,wireHash,now,duplicate,apply) }
        } catch (_: PendingChangeLimit) {
            // The application and Signal transaction has rolled back.
            transaction { db -> rememberDelivery(db,wireHash,sender,expectedEventID,now,discarded = false) }
        }
    }

    private fun receive(db: SupportSQLiteDatabase, wire: ByteArray, sender: String, expectedEventID: String, wireHash: String, now: Long,
        duplicate: (String,String,SupportSQLiteDatabase) -> Unit, apply: (Incoming,SupportSQLiteDatabase) -> Unit): Delivery {
        fun remember(discarded: Boolean) = rememberDelivery(db,wireHash,sender,expectedEventID,now,discarded)
        db.query(SimpleSQLiteQuery("""
            SELECT discarded,retry_at FROM protocol_incoming_dispositions
            WHERE wire_hash=? AND sender=? AND event_id=? AND expires_at>?
            """, arrayOf(wireHash,sender,expectedEventID,now))).use {
            if (it.moveToFirst()) {
                if (it.getInt(0) == 1) return Delivery.DISCARDED
                if (it.getLong(1) in (now+1)..(now+300_000)) return Delivery.DEFERRED
            }
        }
        db.query(SimpleSQLiteQuery("SELECT sender,event_id FROM protocol_received_wires WHERE wire_hash=?",arrayOf(wireHash))).use {
            if (it.moveToFirst()) {
                if (it.getString(0)!=sender || it.getString(1)!=expectedEventID) return remember(false)
                duplicate(sender,expectedEventID,db)
                return Delivery.DUPLICATE
            }
        }
        val decoded = SecurityCore.call(JSONObject().put("operation","decode_wire").put("bytes",encode(wire)).put("expected_recipient",prefs.myUuid).put("delivery",true))
        if (decoded.optString("disposition") == "retry" || decoded.getString("sender_uuid") != sender) return remember(false)
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
        val result = SecurityCore.call(JSONObject().put("operation","decrypt").put("local",requiredLocal(db)).put("peer",remote)
            .put("wire",message).put("keys",keys).put("expected_event_id",expectedEventID))
        val disposition = result.getString("disposition")
        if (disposition == "retry") return remember(false)
        check(disposition == "apply" || disposition == "reject") { "Invalid native delivery disposition" }
        val hash = if (disposition == "apply") result.getString("payload_hash") else null
        val existing = if (hash != null) db.query(SimpleSQLiteQuery(
            "SELECT payload_hash FROM protocol_inbox WHERE sender=? AND event_id=?",arrayOf(sender,expectedEventID)
        )).use { if (it.moveToFirst()) it.getString(0) else null } else null
        val discarded = hash == null || (existing != null && existing != hash)
        if (!discarded) {
            if (existing == null) {
                val event = result.getJSONObject("event")
                check(event.getString("event_id") == expectedEventID)
                apply(Incoming(sender,expectedEventID,event.getString("kind"),event.getJSONObject("body"),event.getLong("created_at")),db)
                db.execSQL("INSERT INTO protocol_inbox(sender,event_id,payload_hash) VALUES(?,?,?)",arrayOf(sender,expectedEventID,hash))
            } else duplicate(sender,expectedEventID,db)
        }
        savePeer(db,result.getJSONObject("peer_state"),sender)
        if (!result.isNull("consumed_prekey_id")) db.execSQL("DELETE FROM protocol_keys WHERE kind='ec' AND id=?",arrayOf(result.getLong("consumed_prekey_id")))
        if (result.optBoolean("consumed_pq_prekey")) db.execSQL("DELETE FROM protocol_keys WHERE kind='pq' AND id=?",arrayOf(description.getLong("pq_prekey_id")))
        result.optJSONObject("pq_key_use")?.let { db.execSQL("INSERT INTO protocol_pq_uses(fingerprint) VALUES(?)",arrayOf(it.getString("fingerprint"))) }
        if (discarded) return remember(true)
        db.execSQL("DELETE FROM protocol_incoming_dispositions WHERE wire_hash=? AND sender=? AND event_id=?",arrayOf(wireHash,sender,expectedEventID))
        db.execSQL("INSERT INTO protocol_received_wires(wire_hash,sender,event_id) VALUES(?,?,?)",arrayOf(wireHash,sender,expectedEventID))
        return if (existing == null) Delivery.APPLIED else Delivery.DUPLICATE
    }

    private fun rememberDelivery(db: SupportSQLiteDatabase, hash: String, sender: String, event: String, now: Long, discarded: Boolean): Delivery {
        db.execSQL("DELETE FROM protocol_incoming_dispositions WHERE expires_at<=?",arrayOf(now))
        db.execSQL("""
            INSERT INTO protocol_incoming_dispositions(wire_hash,sender,event_id,discarded,attempts,retry_at,expires_at)
            VALUES(?,?,?,?,1,?,?)
            ON CONFLICT(wire_hash,sender,event_id) DO UPDATE SET
                discarded=excluded.discarded,attempts=MIN(attempts+1,5),
                retry_at=CASE WHEN excluded.discarded=1 THEN 0 ELSE excluded.retry_at-30000+MIN(300000,30000*(1<<attempts)) END,
                expires_at=CASE WHEN excluded.discarded=1 THEN excluded.expires_at ELSE MIN(expires_at,excluded.expires_at) END
            """,arrayOf(hash,sender,event,if (discarded) 1 else 0,if (discarded) 0 else now+30_000,
            now + (if (discarded) 15 else 7) * 86_400_000L))
        db.execSQL("""
            DELETE FROM protocol_incoming_dispositions WHERE rowid IN (
                SELECT rowid FROM protocol_incoming_dispositions WHERE discarded=?
                ORDER BY expires_at DESC,rowid DESC LIMIT -1 OFFSET ?)
            """,arrayOf(if (discarded) 1 else 0,if (discarded) 3072 else 1024))
        return if (discarded) Delivery.DISCARDED else Delivery.DEFERRED
    }

    suspend fun pendingEvents(leaseSession: String,minimumLease: Long,limit: Int = 64,waitingForKeys: Collection<String> = emptyList()): List<Pending> = transaction { db ->
        db.query(SimpleSQLiteQuery("""
            SELECT event_id,recipient,kind,body_json,created_at FROM protocol_pending_events p
            WHERE p.held=0 AND NOT EXISTS(SELECT 1 FROM scheduled_sends s WHERE s.event_id=p.event_id)
            AND NOT EXISTS(
                SELECT 1 FROM live_location_outgoing pending
                JOIN live_location_outgoing sealed ON sealed.message_id=pending.message_id
                JOIN protocol_outbox b ON b.event_id=sealed.event_id AND b.delivered=0
                WHERE pending.event_id=p.event_id
            )
            AND (p.kind!='chat.activity' OR (((SELECT share_online FROM privacy_preferences WHERE id=1)=1 OR (json_extract(p.body_json,'$.online') IS NULL AND json_extract(p.body_json,'$.request')=0)) AND ((SELECT share_typing FROM privacy_preferences WHERE id=1)=1 OR json_extract(p.body_json,'$.typing')=0)))
            AND NOT EXISTS(SELECT 1 FROM object_transfers t WHERE t.event_id=p.event_id AND t.direction=0 AND (t.lease_session!=? OR t.remote_expiry<=?))
            AND NOT EXISTS(
                SELECT 1 FROM message_expiration e
                WHERE e.expires_at<=? AND e.message_id IN (
                    SELECT p.event_id
                    UNION ALL SELECT message_id FROM send_related_events WHERE event_id=p.event_id
                    UNION ALL SELECT message_id FROM protocol_receipts WHERE event_id=p.event_id AND peer=p.recipient
                )
            )
            AND NOT EXISTS(SELECT 1 FROM protocol_relay_deferrals d WHERE d.recipient=p.recipient)
            AND (p.recipient NOT IN (SELECT value FROM json_each(?)) OR EXISTS(
                SELECT 1 FROM protocol_peers peer WHERE peer.uuid=p.recipient
                AND json_type(peer.state_json,'$.session_record')='text'
            ))
            ORDER BY p.rowid LIMIT ?
            """,arrayOf(leaseSession, minimumLease, System.currentTimeMillis(), JSONArray(waitingForKeys).toString(), limit.coerceIn(1, 256)))).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Pending(cursor.getString(0),cursor.getString(1),cursor.getString(2),JSONObject(cursor.getString(3)),cursor.getLong(4))) }
        }
    }
    suspend fun pendingOutgoing(leaseSession: String,minimumLease: Long,limit: Int = 8): List<Outgoing> = transaction { db ->
        db.query(SimpleSQLiteQuery("""
            SELECT event_id,recipient,wire FROM protocol_outbox b WHERE delivered=0 AND NOT EXISTS(SELECT 1 FROM activity_peers a WHERE a.peer=b.recipient AND json_extract(a.state_json,'$.inflight.event_id')=b.event_id AND (((SELECT share_online FROM privacy_preferences WHERE id=1)=0 AND (json_extract(a.state_json,'$.inflight.body.online') IS NOT NULL OR json_extract(a.state_json,'$.inflight.body.request')=1)) OR ((SELECT share_typing FROM privacy_preferences WHERE id=1)=0 AND json_extract(a.state_json,'$.inflight.body.typing')=1))) AND NOT EXISTS(SELECT 1 FROM object_transfers t WHERE t.event_id=b.event_id AND t.direction=0 AND (t.lease_session!=? OR t.remote_expiry<=?))
            AND NOT EXISTS(
                SELECT 1 FROM message_expiration e
                WHERE e.expires_at<=? AND e.message_id IN (
                    SELECT b.event_id
                    UNION ALL SELECT message_id FROM send_related_events WHERE event_id=b.event_id
                    UNION ALL SELECT message_id FROM protocol_receipts WHERE event_id=b.event_id AND peer=b.recipient
                )
            )
            AND NOT EXISTS(SELECT 1 FROM protocol_relay_deferrals d WHERE d.recipient=b.recipient)
            ORDER BY b.rowid LIMIT ?
            """,arrayOf(leaseSession, minimumLease, System.currentTimeMillis(), limit.coerceIn(1, 256)))).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Outgoing(cursor.getString(0),cursor.getString(1),decode(cursor.getString(2)))) }
        }
    }
    suspend fun markRelayed(id: String, recipient: String) = transaction { db -> db.execSQL("UPDATE protocol_outbox SET delivered=1 WHERE event_id=? AND recipient=? AND delivered>=0",arrayOf(id,recipient)) }
    suspend fun keyRequestID(recipient: String): String = transaction { db ->
        db.query(SimpleSQLiteQuery("SELECT request_id FROM protocol_key_requests WHERE recipient=?",arrayOf(recipient))).use { if (it.moveToFirst()) return@transaction it.getString(0) }
        UUID.randomUUID().toString().also { db.execSQL("INSERT INTO protocol_key_requests(recipient,request_id) VALUES(?,?)",arrayOf(recipient,it)) }
    }
    suspend fun finishKeyRequest(recipient: String, id: String) = transaction { db -> db.execSQL("DELETE FROM protocol_key_requests WHERE recipient=? AND request_id=?",arrayOf(recipient,id)) }

    suspend fun <T> transaction(block: (SupportSQLiteDatabase) -> T): T = database.withTransaction { block(database.openHelper.writableDatabase) }.also{database.invalidationTracker.refreshAsync()}
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
        check(uuid.toString()==id && uuid.version()==4 && uuid.variant()==2 && kind.isNotEmpty() && kind.toByteArray(Charsets.UTF_8).size<=64 && created in 0..253402214399999L) { "Invalid encrypted event fields" }
    }
    private fun validateApplicationEvent(bytes: ByteArray): JSONObject = SecurityCore.call(
        JSONObject().put("operation", "validate_application_event").put("bytes", encode(bytes))).getJSONObject("event")

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
