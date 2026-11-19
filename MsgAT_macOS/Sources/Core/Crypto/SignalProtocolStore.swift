// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import CryptoKit
import SQLCipher

/// The encrypted SQLCipher vault owns both libsignal state and its durable mailboxes.
/// Transport sends only committed outbox entries; transport ACKs only follow receive().
final class SignalProtocolStore {
    enum Delivery { case applied, duplicate, discarded, deferred }
    struct PendingChangeLimit: Error {}
    struct Outgoing {
        let eventID: String
        let recipient: String
        let wire: Data
    }

    struct Incoming {
        let sender: String
        let eventID: String
        let kind: String
        let body: [String: Any]
        let createdAt: Int64
    }

    struct Pending {
        let eventID: String
        let recipient: String
        let kind: String
        let body: [String: Any]
        let createdAt: Int64
    }

    static let schema = ConversationIndexStore.schema + MessagePinsStore.schema + [
        "CREATE TABLE IF NOT EXISTS conversation_drafts (peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,token TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,text TEXT NOT NULL DEFAULT '',reply_id TEXT)",
        "CREATE TABLE IF NOT EXISTS protocol_local (id INTEGER PRIMARY KEY CHECK(id=1), identity_json TEXT NOT NULL, bundle_json TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS protocol_keys (kind TEXT NOT NULL, id INTEGER NOT NULL, record TEXT NOT NULL, last_resort INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(kind,id))",
        "CREATE TABLE IF NOT EXISTS protocol_key_counters (kind TEXT PRIMARY KEY NOT NULL, next_id INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS protocol_key_ages (kind TEXT NOT NULL, id INTEGER NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(kind,id))",
        "CREATE TABLE IF NOT EXISTS protocol_peers (uuid TEXT PRIMARY KEY NOT NULL, state_json TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS protocol_pq_uses (fingerprint TEXT PRIMARY KEY NOT NULL)",
        "CREATE TABLE IF NOT EXISTS protocol_outbox (event_id TEXT PRIMARY KEY NOT NULL, recipient TEXT NOT NULL, payload_hash TEXT NOT NULL, wire TEXT NOT NULL, delivered INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS protocol_relay_deferrals (recipient TEXT PRIMARY KEY NOT NULL, retry_at INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS protocol_pending_events (event_id TEXT PRIMARY KEY NOT NULL, recipient TEXT NOT NULL, kind TEXT NOT NULL, body_json TEXT NOT NULL, created_at INTEGER NOT NULL,held INTEGER NOT NULL DEFAULT 0 CHECK(held IN (0,1)))",
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
        "CREATE TABLE IF NOT EXISTS protocol_key_requests (recipient TEXT PRIMARY KEY NOT NULL, request_id TEXT NOT NULL)",
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
        "CREATE TABLE IF NOT EXISTS protocol_inbox (sender TEXT NOT NULL, event_id TEXT NOT NULL, payload_hash TEXT NOT NULL, PRIMARY KEY(sender,event_id))",
        "CREATE TABLE IF NOT EXISTS protocol_received_wires (wire_hash TEXT PRIMARY KEY NOT NULL, sender TEXT NOT NULL, event_id TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS protocol_incoming_dispositions (wire_hash TEXT NOT NULL,sender TEXT NOT NULL,event_id TEXT NOT NULL,discarded INTEGER NOT NULL,attempts INTEGER NOT NULL,retry_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(wire_hash,sender,event_id))",
        "CREATE TABLE IF NOT EXISTS media_objects (object_id TEXT PRIMARY KEY NOT NULL, manifest_json TEXT NOT NULL, local_state INTEGER NOT NULL DEFAULT 0)",
        """
        CREATE TABLE IF NOT EXISTS media_thumbnail_cache(
            cache_id INTEGER PRIMARY KEY,
            object_id TEXT NOT NULL REFERENCES media_objects(object_id) ON DELETE CASCADE,
            thumbnail_object TEXT NOT NULL UNIQUE REFERENCES media_objects(object_id) ON DELETE CASCADE,
            edge INTEGER NOT NULL CHECK(edge BETWEEN 1 AND 640),
            CHECK(object_id<>thumbnail_object),
            UNIQUE(object_id,edge)
        )
        """,
        """
        CREATE TRIGGER IF NOT EXISTS media_thumbnail_evict
        AFTER UPDATE OF local_state ON media_objects WHEN NEW.local_state<>1
        BEGIN
            DELETE FROM media_thumbnail_cache WHERE object_id=NEW.object_id;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS media_thumbnail_trim
        AFTER INSERT ON media_thumbnail_cache
        BEGIN
            DELETE FROM media_thumbnail_cache WHERE cache_id IN (
                SELECT cache_id FROM media_thumbnail_cache ORDER BY cache_id DESC LIMIT -1 OFFSET 192
            );
        END
        """,
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
        CallHistory.table,
        CallHistory.index,
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
        """,
        """
        CREATE TRIGGER IF NOT EXISTS media_thumbnail_gc_replace
        AFTER UPDATE OF thumbnail_object ON media_thumbnail_cache
        WHEN OLD.thumbnail_object<>NEW.thumbnail_object
        BEGIN
            DELETE FROM media_objects WHERE object_id=OLD.thumbnail_object
              AND NOT EXISTS(SELECT 1 FROM media_object_references r WHERE r.object_id=OLD.thumbnail_object)
              AND NOT EXISTS(SELECT 1 FROM media_staging s WHERE s.object_id=OLD.thumbnail_object);
        END
        """,
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
    ] + MessageExpirationStore.schema

    private let database: AppDatabase
    init(database: AppDatabase = .shared) { self.database = database }

    func hasSession(with uuid: String) throws -> Bool {
        try database.transaction { transaction in
            guard let state = try peerState(transaction, uuid: uuid) else { return false }
            return state["session_record"] as? String != nil
        }
    }

    func enqueue(_ pending: Pending, apply: (AppDatabase.Transaction) throws -> Void = { _ in }) throws {
        try database.transaction(notifyChanged: true) { transaction in
            if try enqueue(pending, in: transaction) { try apply(transaction) }
        }
    }

    @discardableResult
    func enqueue(_ pending: Pending, in transaction: AppDatabase.Transaction) throws -> Bool {
        guard try transaction.query("SELECT 1 FROM protocol_revocations WHERE peer=?",values:[.text(pending.recipient)],map:{ _ in true }).isEmpty else { throw invalid("The recipient identity was revoked") }
        try validateEvent(id: pending.eventID, kind: pending.kind, createdAt: pending.createdAt)
        guard pending.recipient.count == 64, pending.recipient.allSatisfy({ $0.isHexDigit && !$0.isUppercase }) else { throw invalid("Invalid recipient identity") }
        let encoded = try json(pending.body)
        guard encoded.utf8.count <= 60 * 1024 else { throw invalid("Message body exceeds its size limit") }
        if let existing = try transaction.query("SELECT recipient,kind,body_json,created_at FROM protocol_pending_events WHERE event_id=?", values: [.text(pending.eventID)], map: { (text($0,0),text($0,1),text($0,2),sqlite3_column_int64($0,3)) }).first {
            guard existing.0 == pending.recipient, existing.1 == pending.kind, existing.2 == encoded, existing.3 == pending.createdAt else { throw invalid("Conflicting pending event identifier") }
            return false
        }
        let payload: [String: Any] = ["version": 2, "event_id": pending.eventID, "kind": pending.kind, "body": pending.body, "created_at": pending.createdAt]
        let bytes = try JSONSerialization.data(withJSONObject: payload, options: .sortedKeys)
        _ = try validateApplicationEvent(bytes)
        let hash = digest(bytes)
        if let existing = try transaction.query("SELECT recipient,payload_hash FROM protocol_outbox WHERE event_id=?", values: [.text(pending.eventID)], map: { (text($0,0),text($0,1)) }).first {
            guard existing.0 == pending.recipient, existing.1 == hash else { throw invalid("Conflicting committed event identifier") }
            return false
        }
        try transaction.execute("INSERT INTO protocol_pending_events(event_id,recipient,kind,body_json,created_at) VALUES(?,?,?,?,?)", values: [.text(pending.eventID),.text(pending.recipient),.text(pending.kind),.text(encoded),.integer(pending.createdAt)])
        if ["contact.request","contact.accept","contact.profile"].contains(pending.kind),let avatar=pending.body["avatar"] as? [String:Any],let id=avatar["object_id"] as? String {
            try EncryptedObjectStore.remember(avatar,in:transaction)
            try AttachmentQueue.record(transaction,event:pending.eventID,objectID:id,outgoing:true,peer:pending.recipient,created:pending.createdAt,ownerKind:"profile")
        }
        return true
    }

    func pendingEvents(leaseSession: String, minimumLease: Int64, limit: Int = 64, waitingForKeys: Set<String> = []) throws -> [Pending] {
        let waiting = String(decoding: try JSONEncoder().encode(waitingForKeys), as: UTF8.self)
        return try database.query("""
            SELECT p.event_id,p.recipient,p.kind,p.body_json,p.created_at FROM protocol_pending_events p
            WHERE p.held=0 AND NOT EXISTS(SELECT 1 FROM scheduled_sends s WHERE s.event_id=p.event_id) AND NOT EXISTS(SELECT 1 FROM live_location_outgoing pending JOIN live_location_outgoing sealed ON sealed.message_id=pending.message_id JOIN protocol_outbox b ON b.event_id=sealed.event_id AND b.delivered=0 WHERE pending.event_id=p.event_id) AND (p.kind!='chat.activity' OR (((SELECT share_online FROM privacy_preferences WHERE id=1)=1 OR (json_extract(p.body_json,'$.online') IS NULL AND json_extract(p.body_json,'$.request')=0)) AND ((SELECT share_typing FROM privacy_preferences WHERE id=1)=1 OR json_extract(p.body_json,'$.typing')=0))) AND NOT EXISTS(SELECT 1 FROM object_transfers t
                WHERE t.event_id=p.event_id AND t.direction=0 AND (t.lease_session!=? OR t.remote_expiry<=?))
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
            """, values: [.text(leaseSession), .integer(minimumLease), .integer(Int64(Date().timeIntervalSince1970 * 1000)), .text(waiting), .integer(Int64(max(1,min(limit,256))))]) {
            Pending(eventID: text($0,0),recipient: text($0,1),kind: text($0,2),body: try object(text($0,3)),createdAt: sqlite3_column_int64($0,4))
        }
    }

    func keyRequestID(for recipient: String) throws -> String {
        try database.transaction { transaction in
            if let id = try transaction.query("SELECT request_id FROM protocol_key_requests WHERE recipient=?", values: [.text(recipient)], map: { text($0,0) }).first { return id }
            let id = UUID().uuidString.lowercased()
            try transaction.execute("INSERT INTO protocol_key_requests(recipient,request_id) VALUES(?,?)", values: [.text(recipient),.text(id)])
            return id
        }
    }

    func finishKeyRequest(recipient: String, id: String) throws {
        try database.execute("DELETE FROM protocol_key_requests WHERE recipient=? AND request_id=?", values: [.text(recipient),.text(id)])
    }

    /// Provision only once. Reopening the app never regenerates registration/prekeys.
    func publicBundle(forceRefresh: Bool = false, directoryEpoch: String? = nil) throws -> [String: Any] {
        let uuid = KeychainManager.shared.myUuid
        var seed = KeychainManager.shared.getOrCreateSeed()
        defer { seed.resetBytes(in: seed.startIndex..<seed.endIndex) }
        return try database.transaction { transaction in
            var request: [String: Any] = ["operation": "provision_identity", "seed": seed.base64EncodedString(), "prekey_count": 100]
            request["directory_epoch"] = directoryEpoch
            var previous: [String: Any]?
            if let stored = try local(transaction) {
                guard stored.identity["uuid"] as? String == uuid else { throw invalid("Vault identity mismatch") }
                guard let encoded = stored.bundle["body"] as? String, let bodyBytes = Data(base64Encoded: encoded),
                      let body = try JSONSerialization.jsonObject(with: bodyBytes) as? [String: Any],
                      let expires = body["expires_at"] as? Int64 else { throw invalid("Invalid stored public bundle") }
                guard let publicKeys = body["prekeys"] as? [[String: Any]],
                      let first = publicKeys.compactMap({ $0["id"] as? Int64 }).min(),
                      let last = publicKeys.compactMap({ $0["id"] as? Int64 }).max() else { throw invalid("Invalid stored prekey range") }
                let available = try transaction.query("SELECT COUNT(*) FROM protocol_keys WHERE kind='ec' AND id>=? AND id<=?", values: [.integer(first), .integer(last)], map: { sqlite3_column_int64($0, 0) }).first ?? 0
                let previousEpoch = body["directory_epoch"] as? String
                if !forceRefresh, directoryEpoch == nil || directoryEpoch == previousEpoch,
                   expires > Int64(Date().timeIntervalSince1970) + 2 * 86400, available >= 20 { return stored.bundle }
                request["directory_epoch"] = directoryEpoch ?? previousEpoch
                request["operation"] = "refresh_identity"
                request["local"] = stored.identity
                for (kind, field) in [("ec", "first_prekey_id"), ("signed", "signed_prekey_id"), ("pq", "pq_prekey_id")] {
                    guard let next = try transaction.query("SELECT next_id FROM protocol_key_counters WHERE kind=?", values: [.text(kind)], map: { sqlite3_column_int64($0, 0) }).first,
                          next > 0, next <= Int64(UInt32.max) else { throw invalid("Missing or exhausted prekey counter") }
                    request[field] = next
                }
                previous = stored.identity
            } else {
                let records = try transaction.query("SELECT 1 FROM protocol_peers UNION ALL SELECT 1 FROM protocol_keys LIMIT 1") { _ in true }
                guard records.isEmpty else { throw invalid("Protocol identity is missing from an existing vault") }
            }
            let provision = try SecurityCore.call(request)
            guard provision["uuid"] as? String == uuid,
                  let bundle = provision["public_bundle"] as? [String: Any],
                  let state = provision["private_state"] as? [String: Any],
                  let identity = state["local_identity"] as? [String: Any] else { throw invalid("Invalid identity provisioning result") }
            if let previous, try json(previous) != json(identity) { throw invalid("Prekey refresh attempted to replace the local identity") }
            try transaction.execute("INSERT INTO protocol_local(id,identity_json,bundle_json) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET identity_json=excluded.identity_json,bundle_json=excluded.bundle_json", values: [.text(try json(identity)), .text(try json(bundle))])
            let now = Int64(Date().timeIntervalSince1970)
            for (field, kind) in [("prekeys", "ec"), ("signed_prekeys", "signed"), ("pq_prekeys", "pq")] {
                guard let keys = state[field] as? [[String: Any]], !keys.isEmpty else { throw invalid("Missing provisioned prekeys") }
                var nextID: Int64 = 1
                for key in keys {
                    guard let id = key["id"] as? Int64, id > 0, let record = key["record"] as? String else { throw invalid("Invalid provisioned key") }
                    try transaction.execute("INSERT INTO protocol_keys(kind,id,record,last_resort) VALUES(?,?,?,?)", values: [.text(kind), .integer(id), .text(record), .integer(key["last_resort"] as? Bool == true ? 1 : 0)])
                    try transaction.execute("INSERT INTO protocol_key_ages(kind,id,created_at) VALUES(?,?,?)", values: [.text(kind), .integer(id), .integer(now)])
                    nextID = max(nextID, id + 1)
                }
                try transaction.execute("INSERT INTO protocol_key_counters(kind,next_id) VALUES(?,?) ON CONFLICT(kind) DO UPDATE SET next_id=MAX(next_id,excluded.next_id)", values: [.text(kind), .integer(nextID)])
            }
            // A bundle can be used for seven days, then its ciphertext can remain
            // queued for seven more. Keep private receive keys for fifteen days.
            try transaction.execute("DELETE FROM protocol_keys WHERE (kind,id) IN (SELECT kind,id FROM protocol_key_ages WHERE created_at<?)", values: [.integer(now - 15 * 86400)])
            try transaction.execute("DELETE FROM protocol_key_ages WHERE NOT EXISTS (SELECT 1 FROM protocol_keys k WHERE k.kind=protocol_key_ages.kind AND k.id=protocol_key_ages.id)")
            return bundle
        }
    }

    /// Retrying the same event returns its original ciphertext without advancing the ratchet.
    func prepare(eventID: String, recipient: String, kind: String, body: [String: Any], createdAt: Int64,
                 bundle: [String: Any]? = nil, selectedPrekey: Int64? = nil,
                 apply: (AppDatabase.Transaction) throws -> Void = { _ in }) throws -> Outgoing {
        try validateEvent(id: eventID, kind: kind, createdAt: createdAt)
        let payload: [String: Any] = ["version": 2, "event_id": eventID, "kind": kind, "body": body, "created_at": createdAt]
        let bytes = try JSONSerialization.data(withJSONObject: payload, options: .sortedKeys)
        guard bytes.count <= 64 * 1024 else { throw invalid("Event exceeds the encrypted message limit") }
        _ = try validateApplicationEvent(bytes)
        let payloadHash = digest(bytes)
        let uuid = KeychainManager.shared.myUuid
        return try database.transaction { transaction in
            guard try transaction.query("SELECT 1 FROM scheduled_sends WHERE event_id=?",values:[.text(eventID)],map:{_ in true}).isEmpty else {throw self.invalid("Scheduled message has not been released")}
            if let previous = try transaction.query("SELECT recipient,payload_hash,wire FROM protocol_outbox WHERE event_id=?", values: [.text(eventID)], map: { (text($0, 0), text($0, 1), text($0, 2)) }).first {
                guard previous.0 == recipient, previous.1 == payloadHash, let wire = Data(base64Encoded: previous.2) else { throw invalid("Event identifier has already been used") }
                return Outgoing(eventID: eventID, recipient: recipient, wire: wire)
            }
            let identity = try requiredLocal(transaction, uuid: uuid)
            var peer = try peerState(transaction, uuid: recipient)
            if peer == nil {
                guard let bundle else { throw invalid("A contact key bundle is required") }
                let verified = try SecurityCore.call(["operation": "verify_bundle", "bundle": bundle, "expected_uuid": recipient])
                guard let publicState = verified["bundle"] as? [String: Any], let certificate = publicState["certificate"] as? [String: Any] else { throw invalid("Invalid contact bundle") }
                peer = ["uuid": recipient, "certificate": certificate, "session_record": NSNull(), "trusted_identity_key": NSNull()]
            }
            var request: [String: Any] = ["operation": "encrypt", "local": identity, "peer": peer!, "plaintext": bytes.base64EncodedString()]
            if let bundle { request["bundle"] = bundle }
            if let selectedPrekey { request["selected_prekey"] = selectedPrekey }
            let result = try SecurityCore.call(request)
            guard let encoded = result["wire_bytes"] as? String, let wire = Data(base64Encoded: encoded),
                  let state = result["peer_state"] as? [String: Any] else { throw invalid("Invalid encrypted message result") }
            try savePeer(transaction, state: state, expected: recipient)
            try apply(transaction)
            try transaction.execute("INSERT INTO protocol_outbox(event_id,recipient,payload_hash,wire) VALUES(?,?,?,?)", values: [.text(eventID), .text(recipient), .text(payloadHash), .text(encoded)])
            try transaction.execute("DELETE FROM protocol_pending_events WHERE event_id=?", values: [.text(eventID)])
            return Outgoing(eventID: eventID, recipient: recipient, wire: wire)
        }
    }

    /// An authenticated discard commits Signal state and a separate exact-wire marker.
    /// A deferred event leaves its ratchet and application state untouched.
    @discardableResult
    func receive(_ wire: Data, authenticatedSender: String, expectedEventID: String, onDuplicate: ((String,String,AppDatabase.Transaction) throws -> Void)? = nil,
                 notifyBusiness:Bool=true,apply: (Incoming, AppDatabase.Transaction) throws -> Void) throws -> Delivery {
        let uuid = KeychainManager.shared.myUuid
        let wireHash = digest(wire)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let key: [DatabaseValue] = [.text(wireHash), .text(authenticatedSender), .text(expectedEventID)]
        do {
            return try database.transaction(notifyChanged:notifyBusiness) { transaction in
                func remember(_ discarded: Bool) throws -> Delivery {
                    try rememberDelivery(transaction, key: key, now: now, discarded: discarded)
                }
                if let previous = try transaction.query("""
                    SELECT discarded,retry_at FROM protocol_incoming_dispositions
                    WHERE wire_hash=? AND sender=? AND event_id=? AND expires_at>?
                    """, values: key + [.integer(now)], map: { (sqlite3_column_int($0, 0), sqlite3_column_int64($0, 1)) }).first {
                    if previous.0 == 1 { return .discarded }
                    if previous.1 > now, previous.1 <= now + 300_000 { return .deferred }
                }
                if let received = try transaction.query("SELECT sender,event_id FROM protocol_received_wires WHERE wire_hash=?", values: [.text(wireHash)], map: { (text($0, 0), text($0, 1)) }).first {
                    guard received.0 == authenticatedSender, received.1 == expectedEventID else { return try remember(false) }
                    try onDuplicate?(authenticatedSender,expectedEventID,transaction)
                    return .duplicate
                }
                let decoded = try SecurityCore.call(["operation": "decode_wire", "bytes": wire.base64EncodedString(), "expected_recipient": uuid, "delivery": true])
                if decoded["disposition"] as? String == "retry" { return try remember(false) }
                guard let sender = decoded["sender_uuid"] as? String,
                      let message = decoded["wire_message"] as? [String: Any], let certificate = message["sender_certificate"] as? [String: Any],
                      let description = decoded["prekeys"] as? [String: Any] else { throw invalid("Invalid decoded delivery") }
                guard sender == authenticatedSender else { return try remember(false) }
                let peer = try peerState(transaction, uuid: sender) ?? ["uuid": sender, "certificate": certificate, "session_record": NSNull(), "trusted_identity_key": NSNull()]
                var keys: [String: Any] = [:]
                for (field, kind, output) in [("prekey_id", "ec", "prekey"), ("signed_prekey_id", "signed", "signed_prekey"), ("pq_prekey_id", "pq", "pq_prekey")] {
                    if let id = description[field] as? Int64,
                       let key = try transaction.query("SELECT record,last_resort FROM protocol_keys WHERE kind=? AND id=?", values: [.text(kind), .integer(id)], map: { (text($0, 0), sqlite3_column_int64($0, 1) != 0) }).first {
                        keys[output] = key.0
                        if kind == "pq" { keys["pq_last_resort"] = key.1 }
                    }
                }
                if let use = description["pq_key_use"] as? [String: Any], let fingerprint = use["fingerprint"] as? String {
                    keys["pq_already_used"] = try !transaction.query("SELECT 1 FROM protocol_pq_uses WHERE fingerprint=?", values: [.text(fingerprint)], map: { _ in true }).isEmpty
                }
                let result = try SecurityCore.call(["operation": "decrypt", "local": requiredLocal(transaction, uuid: uuid),
                    "peer": peer, "wire": message, "keys": keys, "expected_event_id": expectedEventID])
                let disposition = result["disposition"] as? String
                if disposition == "retry" { return try remember(false) }
                guard disposition == "apply" || disposition == "reject",
                      let state = result["peer_state"] as? [String: Any] else { throw invalid("Invalid native delivery disposition") }
                let id = expectedEventID
                var delivery: Delivery = .discarded
                if disposition == "apply" {
                    guard let payloadHash = result["payload_hash"] as? String, let payload = result["event"] as? [String:Any],
                          payload["event_id"] as? String == id, let kind = payload["kind"] as? String,
                          let body = payload["body"] as? [String:Any], let createdAt = payload["created_at"] as? Int64
                    else { throw invalid("Invalid native delivery event") }
                    let existing = try transaction.query("SELECT payload_hash FROM protocol_inbox WHERE sender=? AND event_id=?", values: [.text(sender), .text(id)], map: { text($0, 0) }).first
                    if existing == nil {
                        try apply(Incoming(sender: sender, eventID: id, kind: kind, body: body, createdAt: createdAt), transaction)
                        try transaction.execute("INSERT INTO protocol_inbox(sender,event_id,payload_hash) VALUES(?,?,?)", values: [.text(sender), .text(id), .text(payloadHash)])
                        delivery = .applied
                    } else if existing == payloadHash {
                        try onDuplicate?(sender,id,transaction)
                        delivery = .duplicate
                    }
                }
                try savePeer(transaction, state: state, expected: sender)
                if let id = result["consumed_prekey_id"] as? Int64 {
                    try transaction.execute("DELETE FROM protocol_keys WHERE kind='ec' AND id=?", values: [.integer(id)])
                }
                if result["consumed_pq_prekey"] as? Bool == true, let id = description["pq_prekey_id"] as? Int64 {
                    try transaction.execute("DELETE FROM protocol_keys WHERE kind='pq' AND id=?", values: [.integer(id)])
                }
                if let use = result["pq_key_use"] as? [String: Any], let fingerprint = use["fingerprint"] as? String {
                    try transaction.execute("INSERT INTO protocol_pq_uses(fingerprint) VALUES(?)", values: [.text(fingerprint)])
                }
                if delivery == .discarded { return try remember(true) }
                try transaction.execute("DELETE FROM protocol_incoming_dispositions WHERE wire_hash=? AND sender=? AND event_id=?", values: key)
                try transaction.execute("INSERT INTO protocol_received_wires(wire_hash,sender,event_id) VALUES(?,?,?)", values: key)
                return delivery
            }
        } catch is PendingChangeLimit {
            return try database.transaction { try rememberDelivery($0, key: key, now: now, discarded: false) }
        }
    }

    static func pruneIncoming(_ transaction: AppDatabase.Transaction, now: Int64) throws {
        try transaction.execute("DELETE FROM protocol_incoming_dispositions WHERE expires_at<=?", values: [.integer(now)])
        try transaction.execute("DELETE FROM message_changes WHERE pending=1 AND pending_since<=?", values: [.integer(now - 15 * 86_400_000)])
    }

    private func rememberDelivery(_ transaction: AppDatabase.Transaction, key: [DatabaseValue], now: Int64, discarded: Bool) throws -> Delivery {
        try transaction.execute("DELETE FROM protocol_incoming_dispositions WHERE expires_at<=?", values: [.integer(now)])
        try transaction.execute("""
            INSERT INTO protocol_incoming_dispositions(wire_hash,sender,event_id,discarded,attempts,retry_at,expires_at)
            VALUES(?,?,?,?,1,?,?)
            ON CONFLICT(wire_hash,sender,event_id) DO UPDATE SET
                discarded=excluded.discarded,attempts=MIN(attempts+1,5),
                retry_at=CASE WHEN excluded.discarded=1 THEN 0 ELSE excluded.retry_at-30000+MIN(300000,30000*(1<<attempts)) END,
                expires_at=CASE WHEN excluded.discarded=1 THEN excluded.expires_at ELSE MIN(expires_at,excluded.expires_at) END
            """, values: key + [.integer(discarded ? 1 : 0), .integer(discarded ? 0 : now + 30_000),
                                .integer(now + (discarded ? 15 : 7) * 86_400_000)])
        try transaction.execute("""
            DELETE FROM protocol_incoming_dispositions WHERE rowid IN (
                SELECT rowid FROM protocol_incoming_dispositions WHERE discarded=?
                ORDER BY expires_at DESC,rowid DESC LIMIT -1 OFFSET ?)
            """, values: [.integer(discarded ? 1 : 0), .integer(discarded ? 3072 : 1024)])
        return discarded ? .discarded : .deferred
    }

    func pendingOutgoing(leaseSession: String, minimumLease: Int64, limit: Int = 64) throws -> [Outgoing] {
        try database.query("""
            SELECT b.event_id,b.recipient,b.wire FROM protocol_outbox b WHERE b.delivered=0 AND NOT EXISTS(SELECT 1 FROM activity_peers a WHERE a.peer=b.recipient AND json_extract(a.state_json,'$.inflight.event_id')=b.event_id AND (((SELECT share_online FROM privacy_preferences WHERE id=1)=0 AND (json_extract(a.state_json,'$.inflight.body.online') IS NOT NULL OR json_extract(a.state_json,'$.inflight.body.request')=1)) OR ((SELECT share_typing FROM privacy_preferences WHERE id=1)=0 AND json_extract(a.state_json,'$.inflight.body.typing')=1)))
            AND NOT EXISTS(SELECT 1 FROM object_transfers t WHERE t.event_id=b.event_id AND t.direction=0 AND (t.lease_session!=? OR t.remote_expiry<=?))
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
            """, values: [.text(leaseSession), .integer(minimumLease), .integer(Int64(Date().timeIntervalSince1970 * 1000)), .integer(Int64(max(1,min(limit,256))))]) {
            guard let wire = Data(base64Encoded: text($0, 2)) else { throw invalid("Invalid stored outbox message") }
            return Outgoing(eventID: text($0, 0), recipient: text($0, 1), wire: wire)
        }
    }

    /// Server durable-receipt only; recipient delivery/read receipts remain E2EE events.
    func markRelayed(eventID: String, recipient: String) throws {
        try database.execute("UPDATE protocol_outbox SET delivered=1 WHERE event_id=? AND recipient=? AND delivered>=0", values: [.text(eventID), .text(recipient)])
    }

    private func local(_ transaction: AppDatabase.Transaction) throws -> (identity: [String: Any], bundle: [String: Any])? {
        try transaction.query("SELECT identity_json,bundle_json FROM protocol_local WHERE id=1") { (try object(text($0, 0)), try object(text($0, 1))) }.first
    }

    private func requiredLocal(_ transaction: AppDatabase.Transaction, uuid: String) throws -> [String: Any] {
        guard let identity = try local(transaction)?.identity, identity["uuid"] as? String == uuid else { throw invalid("Protocol identity is not initialized") }
        return identity
    }

    private func peerState(_ transaction: AppDatabase.Transaction, uuid: String) throws -> [String: Any]? {
        try transaction.query("SELECT state_json FROM protocol_peers WHERE uuid=?", values: [.text(uuid)]) { try object(text($0, 0)) }.first
    }

    private func savePeer(_ transaction: AppDatabase.Transaction, state: [String: Any], expected: String) throws {
        guard state["uuid"] as? String == expected else { throw invalid("Unexpected peer state") }
        try transaction.execute("INSERT INTO protocol_peers(uuid,state_json) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET state_json=excluded.state_json", values: [.text(expected), .text(try json(state))])
    }

    private func validateApplicationEvent(_ bytes: Data) throws -> [String: Any] {
        let result = try SecurityCore.call(["operation": "validate_application_event", "bytes": bytes.base64EncodedString()])
        guard let event = result["event"] as? [String: Any] else { throw invalid("Invalid application event") }
        return event
    }

    private func validateEvent(id: String, kind: String, createdAt: Int64) throws {
        guard let uuid = UUID(uuidString: id), uuid.uuidString.lowercased() == id,
              uuid.uuid.6 >> 4 == 4, uuid.uuid.8 >> 6 == 2,
              !kind.isEmpty, kind.utf8.count <= 64, createdAt >= 0, createdAt <= 253402214399999 else { throw invalid("Invalid encrypted event fields") }
    }

    private func json(_ value: [String: Any]) throws -> String { String(decoding: try JSONSerialization.data(withJSONObject: value, options: .sortedKeys), as: UTF8.self) }
    private func object(_ value: String) throws -> [String: Any] {
        guard let result = try JSONSerialization.jsonObject(with: Data(value.utf8)) as? [String: Any] else { throw invalid("Invalid persisted protocol state") }
        return result
    }
    private func text(_ statement: OpaquePointer, _ column: Int32) -> String { sqlite3_column_text(statement, column).map { String(cString: $0) } ?? "" }
    private func digest(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
    private func invalid(_ detail: String) -> MsgATDatabaseError { MsgATDatabaseError(operation: "Signal protocol", detail: detail) }
}
