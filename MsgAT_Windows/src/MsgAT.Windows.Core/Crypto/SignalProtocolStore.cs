// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Core.Crypto;

/// <summary>SQLCipher owns durable Signal snapshots; native code never owns a database.</summary>
[SupportedOSPlatform("windows")]
internal sealed class SignalProtocolStore
{
    internal sealed record Pending(string EventId, string Recipient, string Kind, JsonObject Body, long CreatedAt);
    internal sealed record Outgoing(string EventId, string Recipient, byte[] Wire);
    internal sealed record Incoming(string Sender, string EventId, string Kind, JsonObject Body, long CreatedAt);
    internal enum Delivery { Applied, Duplicate, Discarded, Deferred }
    internal sealed class PendingChangeLimit() : Exception("Pending message changes exceed the local history budget.") { }
    private readonly EncryptedDatabase _database;
    private readonly SecureSettingsStore _settings;
    private static readonly JsonWriterOptions JsonOptions = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };
    private static readonly (string Field, string Kind)[] KeyKinds =
        [("prekeys", "ec"), ("signed_prekeys", "signed"), ("pq_prekeys", "pq")];

    private static readonly string[] Schema =
    [
    ..ConversationIndexStore.Schema,
    ..MessagePinsStore.Schema,
    "CREATE TABLE IF NOT EXISTS protocol_local(id INTEGER PRIMARY KEY CHECK(id=1),identity_json TEXT NOT NULL,bundle_json TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS protocol_keys(kind TEXT NOT NULL,id INTEGER NOT NULL,record TEXT NOT NULL,last_resort INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(kind,id))",
    "CREATE TABLE IF NOT EXISTS protocol_key_counters(kind TEXT PRIMARY KEY NOT NULL,next_id INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS protocol_key_ages(kind TEXT NOT NULL,id INTEGER NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(kind,id))",
    "CREATE TABLE IF NOT EXISTS protocol_peers(uuid TEXT PRIMARY KEY NOT NULL,state_json TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS protocol_pq_uses(fingerprint TEXT PRIMARY KEY NOT NULL)",
    "CREATE TABLE IF NOT EXISTS protocol_outbox(event_id TEXT PRIMARY KEY NOT NULL,recipient TEXT NOT NULL,payload_hash TEXT NOT NULL,wire TEXT NOT NULL,delivered INTEGER NOT NULL DEFAULT 0)",
    "CREATE TABLE IF NOT EXISTS protocol_relay_deferrals(recipient TEXT PRIMARY KEY NOT NULL,retry_at INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS protocol_pending_events(event_id TEXT PRIMARY KEY NOT NULL,recipient TEXT NOT NULL,kind TEXT NOT NULL,body_json TEXT NOT NULL,created_at INTEGER NOT NULL,held INTEGER NOT NULL DEFAULT 0 CHECK(held IN (0,1)))",
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
    "CREATE TABLE IF NOT EXISTS protocol_key_requests(recipient TEXT PRIMARY KEY NOT NULL,request_id TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS protocol_revocations(peer TEXT PRIMARY KEY NOT NULL,proof TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS protocol_receipts(peer TEXT NOT NULL,message_id TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('delivered','read')),event_id TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL,pending INTEGER NOT NULL DEFAULT 1,next_retry INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(peer,message_id,state))",
    "CREATE INDEX IF NOT EXISTS protocol_receipts_pending ON protocol_receipts(pending)",
    "CREATE INDEX IF NOT EXISTS protocol_receipts_state_pending ON protocol_receipts(state,pending)",
    "CREATE TABLE IF NOT EXISTS privacy_preferences(id INTEGER PRIMARY KEY CHECK(id=1),read_receipts INTEGER NOT NULL DEFAULT 1 CHECK(read_receipts IN (0,1)),share_online INTEGER NOT NULL DEFAULT 0 CHECK(share_online IN (0,1)),share_typing INTEGER NOT NULL DEFAULT 0 CHECK(share_typing IN (0,1)))",
    "INSERT OR IGNORE INTO privacy_preferences(id) VALUES(1)",
    "CREATE TABLE IF NOT EXISTS read_receipt_suppression(peer TEXT NOT NULL,message_id TEXT NOT NULL,PRIMARY KEY(peer,message_id))",
    "CREATE INDEX IF NOT EXISTS protocol_outbox_pending ON protocol_outbox(delivered)",
    "CREATE TABLE IF NOT EXISTS activity_peers(peer TEXT PRIMARY KEY NOT NULL,state_json TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS activity_watchers(peer TEXT PRIMARY KEY NOT NULL,until INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS activity_watchers_expiry ON activity_watchers(until)",
    "CREATE TABLE IF NOT EXISTS protocol_inbox(sender TEXT NOT NULL,event_id TEXT NOT NULL,payload_hash TEXT NOT NULL,PRIMARY KEY(sender,event_id))",
    "CREATE TABLE IF NOT EXISTS protocol_received_wires(wire_hash TEXT PRIMARY KEY NOT NULL,sender TEXT NOT NULL,event_id TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS protocol_incoming_dispositions (wire_hash TEXT NOT NULL,sender TEXT NOT NULL,event_id TEXT NOT NULL,discarded INTEGER NOT NULL,attempts INTEGER NOT NULL,retry_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,PRIMARY KEY(wire_hash,sender,event_id))",
    "CREATE TABLE IF NOT EXISTS contact_states(uuid TEXT PRIMARY KEY NOT NULL,outgoing INTEGER NOT NULL DEFAULT 0,incoming INTEGER NOT NULL DEFAULT 0,accepted INTEGER NOT NULL DEFAULT 0,blocked INTEGER NOT NULL DEFAULT 0)",
    "CREATE TABLE IF NOT EXISTS contact_links(uuid TEXT PRIMARY KEY NOT NULL,state_json TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS contact_verifications(uuid TEXT PRIMARY KEY NOT NULL,display TEXT NOT NULL,verified_at INTEGER NOT NULL)",
    ..EncryptedObjectStore.LifecycleSchema,
    "CREATE TABLE IF NOT EXISTS user_profiles(peer TEXT PRIMARY KEY NOT NULL,nickname TEXT NOT NULL,revision INTEGER NOT NULL,avatar_object TEXT REFERENCES media_objects(object_id),changed_at INTEGER NOT NULL,event_id TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS message_forwards(message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(messageId) ON DELETE CASCADE,name TEXT,original_at INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS saved_messages(id TEXT PRIMARY KEY NOT NULL,source_peer TEXT NOT NULL,source_id TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('txt','attachment')),body TEXT NOT NULL,object_id TEXT REFERENCES media_objects(object_id),saved_at INTEGER NOT NULL,author_name TEXT NOT NULL,original_at INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS saved_messages_order ON saved_messages(saved_at DESC,id DESC)",
    "CREATE INDEX IF NOT EXISTS saved_messages_by_source ON saved_messages(source_peer,saved_at DESC,id DESC)",
    "CREATE TABLE IF NOT EXISTS forward_jobs(id TEXT PRIMARY KEY NOT NULL,request_json TEXT NOT NULL CHECK(length(request_json)<=65536),created_at INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN ('waiting','preparing','failed')),error_code TEXT)",
    CallHistory.Table,
    CallHistory.Index,
    "CREATE INDEX IF NOT EXISTS forward_jobs_order ON forward_jobs(created_at,id)",
    "CREATE TABLE IF NOT EXISTS object_transfers(event_id TEXT NOT NULL,object_id TEXT NOT NULL REFERENCES media_objects(object_id) ON DELETE CASCADE,owner_peer TEXT NOT NULL,owner_kind TEXT NOT NULL CHECK(owner_kind IN ('message','profile')),created_at INTEGER NOT NULL,direction INTEGER NOT NULL CHECK(direction IN (0,1)),attempts INTEGER NOT NULL DEFAULT 0,next_retry INTEGER NOT NULL DEFAULT 0,remote_expiry INTEGER NOT NULL DEFAULT 0,lease_session TEXT NOT NULL DEFAULT '',PRIMARY KEY(event_id,object_id,direction))",
    "CREATE INDEX IF NOT EXISTS object_transfers_retry ON object_transfers(next_retry,direction)",
    "CREATE TABLE IF NOT EXISTS object_leases(object_id TEXT PRIMARY KEY NOT NULL REFERENCES media_objects(object_id) ON DELETE CASCADE,lease_session TEXT NOT NULL,remote_expiry INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS message_attachments(message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(messageId) ON DELETE CASCADE,object_id TEXT NOT NULL,caption TEXT NOT NULL DEFAULT '')",
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
    ];

    public SignalProtocolStore(EncryptedDatabase database, SecureSettingsStore settings)
    {
        _database = database;
        _settings = settings;
        Transaction(db =>
        {
            var pendingColumns=db.Query("PRAGMA table_info(protocol_pending_events)",r=>r.GetString(1)).ToHashSet(StringComparer.Ordinal);
            if(pendingColumns.Contains("created_at") && !pendingColumns.Contains("held"))db.Execute("ALTER TABLE protocol_pending_events ADD COLUMN held INTEGER NOT NULL DEFAULT 0 CHECK(held IN (0,1))");
            foreach (var sql in Schema) db.Execute(sql);
            foreach (var sql in MessageExpirationStore.Schema) db.Execute(sql);
            if (!db.Query("PRAGMA table_info(conversation_drafts)", row => row.GetString(1)).Contains("reply_quote", StringComparer.Ordinal))
                db.Execute("ALTER TABLE conversation_drafts ADD COLUMN reply_quote TEXT");
            var previewColumns = db.Query("PRAGMA table_info(message_previews)", row => row.GetString(1)).ToHashSet(StringComparer.Ordinal);
            foreach (var column in new[] { "above_text", "compact_image" })
                if (!previewColumns.Contains(column))
                    db.Execute($"ALTER TABLE message_previews ADD COLUMN {column} INTEGER NOT NULL DEFAULT 0 CHECK({column} IN (0,1))");
            db.Execute("UPDATE protocol_pending_events SET held=1 WHERE held=0 AND EXISTS(SELECT 1 FROM scheduled_sends s WHERE s.event_id=protocol_pending_events.event_id)");
            var privacyColumns=db.Query("PRAGMA table_info(privacy_preferences)",r=>r.GetString(1)).ToHashSet(StringComparer.Ordinal);
            foreach (var column in new[] { "message_alerts_enabled", "message_alert_sound" })
                if (!privacyColumns.Contains(column)) db.Execute($"ALTER TABLE privacy_preferences ADD COLUMN {column} INTEGER NOT NULL DEFAULT 1 CHECK({column} IN (0,1))");
            foreach(var column in new[]{"share_online","share_typing","app_lock_enabled"})if(!privacyColumns.Contains(column))db.Execute($"ALTER TABLE privacy_preferences ADD COLUMN {column} INTEGER NOT NULL DEFAULT 0 CHECK({column} IN (0,1))");
            if (!privacyColumns.Contains("auto_download_attachments"))
                db.Execute("ALTER TABLE privacy_preferences ADD COLUMN auto_download_attachments INTEGER NOT NULL DEFAULT 1 CHECK(auto_download_attachments IN (0,1))");
            if (!privacyColumns.Contains("auto_download_limit_bytes"))
                db.Execute("ALTER TABLE privacy_preferences ADD COLUMN auto_download_limit_bytes INTEGER NOT NULL DEFAULT 0 CHECK(auto_download_limit_bytes>=0)");
            var transferColumns = db.Query("PRAGMA table_info(object_transfers)", row => row.GetString(1));
            if (!transferColumns.Contains("download_requested", StringComparer.Ordinal))
                db.Execute("ALTER TABLE object_transfers ADD COLUMN download_requested INTEGER NOT NULL DEFAULT 0 CHECK(download_requested IN (0,1))");
            if (!transferColumns.Contains("download_paused", StringComparer.Ordinal))
                db.Execute("ALTER TABLE object_transfers ADD COLUMN download_paused INTEGER NOT NULL DEFAULT 0 CHECK(download_paused IN (0,1))");
            if (!transferColumns.Contains("download_unavailable", StringComparer.Ordinal))
                db.Execute("ALTER TABLE object_transfers ADD COLUMN download_unavailable INTEGER NOT NULL DEFAULT 0 CHECK(download_unavailable IN (0,1))");
            if(db.Exists("SELECT 1 FROM sqlite_master WHERE type='table' AND name='media_transfers'"))
            {
                if(db.Exists("SELECT 1 FROM media_transfers t LEFT JOIN messages m ON m.messageId=t.message_id LEFT JOIN media_objects o ON o.object_id=t.object_id WHERE m.messageId IS NULL OR o.object_id IS NULL"))throw new InvalidDataException("Stored transfer state is inconsistent. Existing data was retained.");
                db.Execute("INSERT OR IGNORE INTO object_transfers(event_id,object_id,owner_peer,owner_kind,created_at,direction,attempts,next_retry,remote_expiry,lease_session) SELECT t.message_id,t.object_id,m.conversationUuid,'message',m.timestamp,t.direction,t.attempts,t.next_retry,t.remote_expiry,t.lease_session FROM media_transfers t JOIN messages m ON m.messageId=t.message_id JOIN media_objects o ON o.object_id=t.object_id");
                db.Execute("DROP TABLE media_transfers");
            }
            var openedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var changeColumns = db.Query("PRAGMA table_info(message_changes)", row => row.GetString(1));
            if (!changeColumns.Contains("pending_since"))
            {
                db.Execute("ALTER TABLE message_changes ADD COLUMN pending_since INTEGER NOT NULL DEFAULT 0");
                db.Execute("UPDATE message_changes SET pending_since=$p0 WHERE pending=1", openedAt);
            }
            PruneIncoming(db, openedAt);
            MessageExpirationStore.MarkDueRetention(db, openedAt, limit: -1);
            db.Execute("""
                DELETE FROM messages WHERE messageId IN (
                    SELECT message_id FROM message_expiration WHERE expires_at<=$p0)
                """, openedAt);
            return true;
        });
    }

    public T Transaction<T>(Func<VaultTransaction, T> operation, bool notify = false) =>
        _database.ProtocolTransaction(operation, notify);

    public void NotifyBusinessChanges(EventArgs? change = null) => _database.NotifyBusinessChanges(change);

    public JsonObject PublicBundle(bool forceRefresh = false, string? directoryEpoch = null) => Transaction(db => _settings.WithRootSeed(seed =>
    {
        var saved = Local(db);
        var request = new JsonObject
        {
            ["operation"] = "provision_identity", ["seed"] = Convert.ToBase64String(seed), ["prekey_count"] = 100
        };
        if (directoryEpoch is not null) request["directory_epoch"] = directoryEpoch;
        if (saved is not null)
        {
            Require(Text(saved.Value.Identity, "uuid") == _settings.MyUuid, "Vault identity mismatch");
            var body = Object(Convert.FromBase64String(Text(saved.Value.Bundle, "body")));
            var ids = body["prekeys"]?.AsArray().Select(key => Number(key!.AsObject(), "id")).ToArray()
                ?? throw new InvalidDataException("Stored bundle has no prekeys");
            Require(ids.Length is >= 1 and <= 100, "Invalid stored prekey range");
            var available = db.Number("SELECT COUNT(*) FROM protocol_keys WHERE kind='ec' AND id>=$p0 AND id<=$p1", ids.Min(), ids.Max());
            var previousEpoch = body["directory_epoch"]?.GetValue<string>();
            if (!forceRefresh && (directoryEpoch is null || directoryEpoch == previousEpoch) &&
                Number(body, "expires_at") > Seconds + 2 * 86400 && available >= 20)
                return saved.Value.Bundle;
            if (directoryEpoch is null && previousEpoch is not null) request["directory_epoch"] = previousEpoch;
            request["operation"] = "refresh_identity";
            request["local"] = saved.Value.Identity.DeepClone();
            foreach (var (kind, field) in new[] { ("ec", "first_prekey_id"), ("signed", "signed_prekey_id"), ("pq", "pq_prekey_id") })
            {
                var next = db.Number("SELECT next_id FROM protocol_key_counters WHERE kind=$p0", kind);
                Require(next is >= 1 and <= uint.MaxValue, "Missing or exhausted prekey counter");
                request[field] = next;
            }
        }
        else Require(!db.Exists("SELECT 1 FROM protocol_peers UNION ALL SELECT 1 FROM protocol_keys LIMIT 1"), "Protocol identity is missing from an existing vault");
        var result = Call(request);
        Require(Text(result, "uuid") == _settings.MyUuid, "Invalid provisioned identity");
        var bundle = result["public_bundle"]!.AsObject();
        var state = result["private_state"]!.AsObject();
        var identity = state["local_identity"]!.AsObject();
        Require(saved is null || Canonical(saved.Value.Identity) == Canonical(identity), "Prekey refresh cannot replace identity");
        db.Execute("INSERT INTO protocol_local(id,identity_json,bundle_json) VALUES(1,$p0,$p1) ON CONFLICT(id) DO UPDATE SET identity_json=excluded.identity_json,bundle_json=excluded.bundle_json", Canonical(identity), Canonical(bundle));
        foreach (var (field, kind) in KeyKinds)
        {
            var records = state[field]!.AsArray();
            Require(records.Count > 0, "Missing provisioned keys");
            long next = 1;
            foreach (var value in records)
            {
                var key = value!.AsObject();
                var id = Number(key, "id");
                Require(id is >= 1 and <= uint.MaxValue, "Invalid provisioned key identifier");
                db.Execute("INSERT INTO protocol_keys(kind,id,record,last_resort) VALUES($p0,$p1,$p2,$p3)", kind,id,Text(key,"record"),key["last_resort"]?.GetValue<bool>() == true ? 1 : 0);
                db.Execute("INSERT INTO protocol_key_ages(kind,id,created_at) VALUES($p0,$p1,$p2)",kind,id,Seconds);
                next = Math.Max(next,id+1);
            }
            db.Execute("INSERT INTO protocol_key_counters(kind,next_id) VALUES($p0,$p1) ON CONFLICT(kind) DO UPDATE SET next_id=MAX(next_id,excluded.next_id)",kind,next);
        }
        db.Execute("DELETE FROM protocol_keys WHERE (kind,id) IN (SELECT kind,id FROM protocol_key_ages WHERE created_at<$p0)",Seconds-15*86400);
        db.Execute("DELETE FROM protocol_key_ages WHERE NOT EXISTS (SELECT 1 FROM protocol_keys k WHERE k.kind=protocol_key_ages.kind AND k.id=protocol_key_ages.id)");
        return (JsonObject)bundle.DeepClone();
    }));

    public bool HasSession(string recipient) => Transaction(db => Peer(db,recipient)?["session_record"] is JsonValue);

    public static bool Enqueue(Pending message, VaultTransaction db)
    {
        Require(!db.Exists("SELECT 1 FROM protocol_revocations WHERE peer=$p0",message.Recipient),"The recipient identity was revoked");
        Validate(message.EventId,message.Kind,message.CreatedAt);
        Require(IsAccountId(message.Recipient), "Invalid recipient identity");
        var validationBytes = Payload(message);
        try { ValidateApplicationEvent(validationBytes); }
        finally { CryptographicOperations.ZeroMemory(validationBytes); }
        var body = Canonical(message.Body);
        Require(Encoding.UTF8.GetByteCount(body) <= 60*1024, "Event body exceeds its size limit");
        var pending = db.Query("SELECT recipient,kind,body_json,created_at FROM protocol_pending_events WHERE event_id=$p0",
            reader => (reader.GetString(0),reader.GetString(1),reader.GetString(2),reader.GetInt64(3)), message.EventId);
        if (pending.Count > 0)
        {
            Require(pending[0] == (message.Recipient,message.Kind,body,message.CreatedAt), "Conflicting pending event");
            return false;
        }
        var outgoing = db.Query("SELECT recipient,payload_hash FROM protocol_outbox WHERE event_id=$p0",
            reader => (reader.GetString(0),reader.GetString(1)),message.EventId);
        if (outgoing.Count > 0)
        {
            var bytes = Payload(message);
            try { Require(outgoing[0] == (message.Recipient,Digest(bytes)), "Conflicting committed event"); }
            finally { CryptographicOperations.ZeroMemory(bytes); }
            return false;
        }
        db.Execute("INSERT INTO protocol_pending_events(event_id,recipient,kind,body_json,created_at) VALUES($p0,$p1,$p2,$p3,$p4)",message.EventId,message.Recipient,message.Kind,body,message.CreatedAt);
        if (message.Kind is "contact.request" or "contact.accept" or "contact.profile" && message.Body["avatar"] is JsonObject avatar)
        {
            EncryptedObjectStore.Remember(avatar,db);
            MsgAT.Windows.Core.Network.AttachmentQueue.Record(db,message.EventId,avatar["object_id"]!.GetValue<string>(),true,message.Recipient,message.CreatedAt,"profile");
        }
        return true;
    }

    public Outgoing Prepare(Pending message, JsonObject? bundle = null, long? selectedPrekey = null) => Transaction(db =>
    {
        Require(!db.Exists("SELECT 1 FROM scheduled_sends WHERE event_id=$p0",message.EventId),"Scheduled message has not been released");
        Validate(message.EventId,message.Kind,message.CreatedAt);
        var plaintext = Payload(message);
        try
        {
            Require(plaintext.Length <= 64*1024, "Event exceeds encrypted message limit");
            ValidateApplicationEvent(plaintext);
            var hash = Digest(plaintext);
            var existing = db.Query("SELECT recipient,payload_hash,wire FROM protocol_outbox WHERE event_id=$p0",
                reader => (Recipient:reader.GetString(0),Hash:reader.GetString(1),Wire:reader.GetString(2)),message.EventId);
            if (existing.Count > 0)
            {
                Require(existing[0].Recipient==message.Recipient && existing[0].Hash==hash,"Conflicting event identifier");
                return new Outgoing(message.EventId,message.Recipient,Convert.FromBase64String(existing[0].Wire));
            }
            var remote = Peer(db,message.Recipient);
            if (remote is null)
            {
                Require(bundle is not null,"A contact key bundle is required");
                var verified = Call(new JsonObject { ["operation"]="verify_bundle",["bundle"]=bundle!.DeepClone(),["expected_uuid"]=message.Recipient });
                remote = new JsonObject { ["uuid"]=message.Recipient,["certificate"]=verified["bundle"]!["certificate"]!.DeepClone(),["session_record"]=null,["trusted_identity_key"]=null };
            }
            var request = new JsonObject { ["operation"]="encrypt",["local"]=RequiredLocal(db),["peer"]=remote,["plaintext"]=Convert.ToBase64String(plaintext) };
            if (bundle is not null) request["bundle"]=bundle.DeepClone();
            if (selectedPrekey is not null) request["selected_prekey"]=selectedPrekey.Value;
            var result = Call(request);
            SavePeer(db,result["peer_state"]!.AsObject(),message.Recipient);
            var wire = Text(result,"wire_bytes");
            db.Execute("INSERT INTO protocol_outbox(event_id,recipient,payload_hash,wire) VALUES($p0,$p1,$p2,$p3)",message.EventId,message.Recipient,hash,wire);
            db.Execute("DELETE FROM protocol_pending_events WHERE event_id=$p0",message.EventId);
            return new Outgoing(message.EventId,message.Recipient,Convert.FromBase64String(wire));
        }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
    });

    public Delivery Receive(byte[] wire, string sender, string eventId, Action<Incoming,VaultTransaction> apply,
        Action<string,string,VaultTransaction>? duplicate=null,bool notifyBusiness=true)
    {
        var hash = Digest(wire);
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        try { return Transaction(db => Receive(db, wire, sender, eventId, hash, now, apply, duplicate), notify: notifyBusiness); }
        catch (PendingChangeLimit)
        {
            return Transaction(db => RememberDelivery(db, hash, sender, eventId, now, discarded: false));
        }
    }

    private Delivery Receive(VaultTransaction db, byte[] wire, string sender, string eventId, string hash, long now,
        Action<Incoming,VaultTransaction> apply, Action<string,string,VaultTransaction>? duplicate)
    {
        Delivery Remember(bool discarded) => RememberDelivery(db,hash,sender,eventId,now,discarded);
        var disposition = db.Query("""
            SELECT discarded,retry_at FROM protocol_incoming_dispositions
            WHERE wire_hash=$p0 AND sender=$p1 AND event_id=$p2 AND expires_at>$p3
            """, reader => (Discarded:reader.GetInt64(0)==1, RetryAt:reader.GetInt64(1)), hash,sender,eventId,now);
        if (disposition.Count > 0)
        {
            if (disposition[0].Discarded) return Delivery.Discarded;
            if (disposition[0].RetryAt > now && disposition[0].RetryAt <= now+300_000) return Delivery.Deferred;
        }
        var previous = db.Query("SELECT sender,event_id FROM protocol_received_wires WHERE wire_hash=$p0",reader => (reader.GetString(0),reader.GetString(1)),hash);
        if (previous.Count > 0)
        {
            if (previous[0] != (sender,eventId)) return Remember(false);
            duplicate?.Invoke(sender,eventId,db);
            return Delivery.Duplicate;
        }
        var decoded = Call(new JsonObject { ["operation"]="decode_wire",["bytes"]=Convert.ToBase64String(wire),
            ["expected_recipient"]=_settings.MyUuid,["delivery"]=true });
        if (decoded["disposition"]?.GetValue<string>() == "retry" || Text(decoded,"sender_uuid") != sender)
            return Remember(false);
        var message = decoded["wire_message"]!.AsObject();
        var description = decoded["prekeys"]!.AsObject();
        var remote = Peer(db,sender) ?? new JsonObject { ["uuid"]=sender,["certificate"]=message["sender_certificate"]!.DeepClone(),["session_record"]=null,["trusted_identity_key"]=null };
        var keys = new JsonObject();
        foreach (var (field,kind,output) in new[] { ("prekey_id","ec","prekey"),("signed_prekey_id","signed","signed_prekey"),("pq_prekey_id","pq","pq_prekey") })
        {
            if (description[field] is null) continue;
            var found = db.Query("SELECT record,last_resort FROM protocol_keys WHERE kind=$p0 AND id=$p1",reader => (Record:reader.GetString(0),Last:reader.GetInt64(1)!=0),kind,Number(description,field));
            if (found.Count==0) continue;
            keys[output]=found[0].Record;
            if (kind=="pq") keys["pq_last_resort"]=found[0].Last;
        }
        if (description["pq_key_use"] is JsonObject use)
            keys["pq_already_used"]=db.Exists("SELECT 1 FROM protocol_pq_uses WHERE fingerprint=$p0",Text(use,"fingerprint"));
        var result = Call(new JsonObject { ["operation"]="decrypt",["local"]=RequiredLocal(db),["peer"]=remote,
            ["wire"]=message.DeepClone(),["keys"]=keys,["expected_event_id"]=eventId });
        var outcome = Text(result,"disposition");
        if (outcome == "retry") return Remember(false);
        Require(outcome is "apply" or "reject","Invalid native delivery disposition");
        var delivery = Delivery.Discarded;
        if (outcome == "apply")
        {
            var incoming = result["event"]!.AsObject();
            Require(Text(incoming,"event_id") == eventId,"Invalid native delivery event ID");
            var payloadHash = Text(result,"payload_hash");
            var existing = db.Text("SELECT payload_hash FROM protocol_inbox WHERE sender=$p0 AND event_id=$p1",sender,eventId);
            if (existing is null)
            {
                apply(new Incoming(sender,eventId,Text(incoming,"kind"),incoming["body"]!.AsObject(),Number(incoming,"created_at")),db);
                db.Execute("INSERT INTO protocol_inbox(sender,event_id,payload_hash) VALUES($p0,$p1,$p2)",sender,eventId,payloadHash);
                delivery = Delivery.Applied;
            }
            else if (existing == payloadHash)
            {
                duplicate?.Invoke(sender,eventId,db);
                delivery = Delivery.Duplicate;
            }
        }
        SavePeer(db,result["peer_state"]!.AsObject(),sender);
        if (result["consumed_prekey_id"] is not null) db.Execute("DELETE FROM protocol_keys WHERE kind='ec' AND id=$p0",Number(result,"consumed_prekey_id"));
        if (result["consumed_pq_prekey"]?.GetValue<bool>()==true) db.Execute("DELETE FROM protocol_keys WHERE kind='pq' AND id=$p0",Number(description,"pq_prekey_id"));
        if (result["pq_key_use"] is JsonObject pq) db.Execute("INSERT INTO protocol_pq_uses(fingerprint) VALUES($p0)",Text(pq,"fingerprint"));
        if (delivery == Delivery.Discarded) return Remember(true);
        db.Execute("DELETE FROM protocol_incoming_dispositions WHERE wire_hash=$p0 AND sender=$p1 AND event_id=$p2",hash,sender,eventId);
        db.Execute("INSERT INTO protocol_received_wires(wire_hash,sender,event_id) VALUES($p0,$p1,$p2)",hash,sender,eventId);
        return delivery;
    }

    internal static void PruneIncoming(VaultTransaction db, long now)
    {
        db.Execute("DELETE FROM protocol_incoming_dispositions WHERE expires_at<=$p0",now);
        db.Execute("DELETE FROM message_changes WHERE pending=1 AND pending_since<=$p0",now-15*86_400_000L);
    }

    private static Delivery RememberDelivery(VaultTransaction db, string hash, string sender, string eventId, long now, bool discarded)
    {
        db.Execute("DELETE FROM protocol_incoming_dispositions WHERE expires_at<=$p0",now);
        db.Execute("""
            INSERT INTO protocol_incoming_dispositions(wire_hash,sender,event_id,discarded,attempts,retry_at,expires_at)
            VALUES($p0,$p1,$p2,$p3,1,$p4,$p5)
            ON CONFLICT(wire_hash,sender,event_id) DO UPDATE SET
                discarded=excluded.discarded,attempts=MIN(attempts+1,5),
                retry_at=CASE WHEN excluded.discarded=1 THEN 0 ELSE excluded.retry_at-30000+MIN(300000,30000*(1<<attempts)) END,
                expires_at=CASE WHEN excluded.discarded=1 THEN excluded.expires_at ELSE MIN(expires_at,excluded.expires_at) END
            """,hash,sender,eventId,discarded?1:0,discarded?0:now+30_000,
            now + (discarded?15:7)*86_400_000L);
        db.Execute("""
            DELETE FROM protocol_incoming_dispositions WHERE rowid IN (
                SELECT rowid FROM protocol_incoming_dispositions WHERE discarded=$p0
                ORDER BY expires_at DESC,rowid DESC LIMIT -1 OFFSET $p1)
            """,discarded?1:0,discarded?3072:1024);
        return discarded ? Delivery.Discarded : Delivery.Deferred;
    }

    public List<Pending> PendingEvents(string leaseSession,long minimumLease,int limit=64,IEnumerable<string>? waitingForKeys=null) => Transaction(db => db.Query(
        """
            SELECT event_id,recipient,kind,body_json,created_at FROM protocol_pending_events p WHERE p.held=0 AND NOT EXISTS(SELECT 1 FROM scheduled_sends s WHERE s.event_id=p.event_id) AND NOT EXISTS(SELECT 1 FROM live_location_outgoing pending JOIN live_location_outgoing sealed ON sealed.message_id=pending.message_id JOIN protocol_outbox b ON b.event_id=sealed.event_id AND b.delivered=0 WHERE pending.event_id=p.event_id) AND (p.kind!='chat.activity' OR (((SELECT share_online FROM privacy_preferences WHERE id=1)=1 OR (json_extract(p.body_json,'$.online') IS NULL AND json_extract(p.body_json,'$.request')=0)) AND ((SELECT share_typing FROM privacy_preferences WHERE id=1)=1 OR json_extract(p.body_json,'$.typing')=0))) AND NOT EXISTS(SELECT 1 FROM object_transfers t WHERE t.event_id=p.event_id AND t.direction=0 AND (t.lease_session!=$p1 OR t.remote_expiry<=$p2))
            AND NOT EXISTS(
                SELECT 1 FROM message_expiration e
                WHERE e.expires_at<=$p3 AND e.message_id IN (
                    SELECT p.event_id
                    UNION ALL SELECT message_id FROM send_related_events WHERE event_id=p.event_id
                    UNION ALL SELECT message_id FROM protocol_receipts WHERE event_id=p.event_id AND peer=p.recipient
                )
            )
            AND NOT EXISTS(SELECT 1 FROM protocol_relay_deferrals d WHERE d.recipient=p.recipient)
            AND (p.recipient NOT IN (SELECT value FROM json_each($p4)) OR EXISTS(
                SELECT 1 FROM protocol_peers peer WHERE peer.uuid=p.recipient
                AND json_type(peer.state_json,'$.session_record')='text'
            ))
            ORDER BY p.rowid LIMIT $p0
            """,
        r => new Pending(r.GetString(0),r.GetString(1),r.GetString(2),Object(r.GetString(3)),r.GetInt64(4)),
        Math.Clamp(limit,1,256),leaseSession,minimumLease,DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        JsonSerializer.Serialize(waitingForKeys ?? Array.Empty<string>())));

    public List<Outgoing> PendingOutgoing(string leaseSession,long minimumLease,int limit=8) => Transaction(db => db.Query(
        """
            SELECT event_id,recipient,wire FROM protocol_outbox b WHERE delivered=0 AND NOT EXISTS(SELECT 1 FROM activity_peers a WHERE a.peer=b.recipient AND json_extract(a.state_json,'$.inflight.event_id')=b.event_id AND (((SELECT share_online FROM privacy_preferences WHERE id=1)=0 AND (json_extract(a.state_json,'$.inflight.body.online') IS NOT NULL OR json_extract(a.state_json,'$.inflight.body.request')=1)) OR ((SELECT share_typing FROM privacy_preferences WHERE id=1)=0 AND json_extract(a.state_json,'$.inflight.body.typing')=1))) AND NOT EXISTS(SELECT 1 FROM object_transfers t WHERE t.event_id=b.event_id AND t.direction=0 AND (t.lease_session!=$p1 OR t.remote_expiry<=$p2))
            AND NOT EXISTS(
                SELECT 1 FROM message_expiration e
                WHERE e.expires_at<=$p3 AND e.message_id IN (
                    SELECT b.event_id
                    UNION ALL SELECT message_id FROM send_related_events WHERE event_id=b.event_id
                    UNION ALL SELECT message_id FROM protocol_receipts WHERE event_id=b.event_id AND peer=b.recipient
                )
            )
            AND NOT EXISTS(SELECT 1 FROM protocol_relay_deferrals d WHERE d.recipient=b.recipient)
            ORDER BY b.rowid LIMIT $p0
            """,
        r => new Outgoing(r.GetString(0),r.GetString(1),Convert.FromBase64String(r.GetString(2))),Math.Clamp(limit,1,256),leaseSession, minimumLease, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));

    public string KeyRequestId(string recipient) => Transaction(db =>
    {
        var existing = db.Text("SELECT request_id FROM protocol_key_requests WHERE recipient=$p0",recipient);
        if (existing is not null) return existing;
        var id = Guid.NewGuid().ToString("D");
        db.Execute("INSERT INTO protocol_key_requests(recipient,request_id) VALUES($p0,$p1)",recipient,id);
        return id;
    });

    public void FinishKeyRequest(string recipient,string id) => Transaction(db =>
        db.Execute("DELETE FROM protocol_key_requests WHERE recipient=$p0 AND request_id=$p1",recipient,id));

    private static JsonObject? Peer(VaultTransaction db,string uuid) => db.Text("SELECT state_json FROM protocol_peers WHERE uuid=$p0",uuid) is string json ? Object(json) : null;
    private static (JsonObject Identity,JsonObject Bundle)? Local(VaultTransaction db) => db.Query("SELECT identity_json,bundle_json FROM protocol_local WHERE id=1",
        r => (Identity:Object(r.GetString(0)),Bundle:Object(r.GetString(1)))).Select(row => ((JsonObject Identity,JsonObject Bundle)?)row).FirstOrDefault();
    private JsonObject RequiredLocal(VaultTransaction db)
    {
        var identity = Local(db)?.Identity ?? throw new InvalidDataException("Protocol identity is not initialized");
        Require(Text(identity,"uuid")==_settings.MyUuid,"Vault identity mismatch");
        return identity;
    }
    private static void SavePeer(VaultTransaction db,JsonObject state,string expected)
    {
        Require(Text(state,"uuid")==expected,"Unexpected peer state");
        db.Execute("INSERT INTO protocol_peers(uuid,state_json) VALUES($p0,$p1) ON CONFLICT(uuid) DO UPDATE SET state_json=excluded.state_json",expected,Canonical(state));
    }
    private static byte[] Payload(Pending message) => Encoding.UTF8.GetBytes(Canonical(new JsonObject
    {
        ["version"]=2,["event_id"]=message.EventId,["kind"]=message.Kind,["body"]=message.Body.DeepClone(),["created_at"]=message.CreatedAt
    }));
    internal static bool IsAccountId(string value) => value.Length==64 && value.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');
    private static JsonObject ValidateApplicationEvent(byte[] bytes) =>
        Call(new JsonObject { ["operation"]="validate_application_event", ["bytes"]=Convert.ToBase64String(bytes) })["event"]!.AsObject();

    internal static void Validate(string id,string kind,long created)
    {
        Require(Guid.TryParseExact(id,"D",out var uuid) && uuid.ToString("D")==id && id[14]=='4' && "89ab".Contains(id[19])
            && kind.Length>0 && Encoding.UTF8.GetByteCount(kind)<=64 && created is >=0 and <=253402214399999,"Invalid encrypted event fields");
    }
    internal static JsonObject Call(JsonObject request) => Object(SecurityCore.Call(request).GetRawText());
    internal static string Text(JsonObject value,string name) => value[name]?.GetValue<string>() ?? throw new InvalidDataException("Missing string field");
    internal static long Number(JsonObject value,string name) => value[name]?.GetValue<long>() ?? throw new InvalidDataException("Missing integer field");
    private static string Digest(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    private static long Seconds => DateTimeOffset.UtcNow.ToUnixTimeSeconds();
    private static void Require(bool condition,string message) { if (!condition) throw new InvalidDataException(message); }
    private static JsonObject Object(string json) => Object(Encoding.UTF8.GetBytes(json));
    private static JsonObject Object(byte[] json)
    {
        using var document = JsonDocument.Parse(json,new JsonDocumentOptions { MaxDepth=32 });
        CheckUnique(document.RootElement);
        return JsonNode.Parse(document.RootElement.GetRawText())?.AsObject() ?? throw new InvalidDataException("Expected a JSON object");
    }
    private static void CheckUnique(JsonElement element)
    {
        if (element.ValueKind==JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
            {
                Require(names.Add(property.Name),"Duplicate JSON field");
                CheckUnique(property.Value);
            }
        }
        else if (element.ValueKind==JsonValueKind.Array) foreach (var item in element.EnumerateArray()) CheckUnique(item);
    }
    internal static string Canonical(JsonObject value)
    {
        using var memory = new MemoryStream();
        using (var writer = new Utf8JsonWriter(memory,JsonOptions)) WriteCanonical(writer,JsonSerializer.SerializeToElement(value));
        return Encoding.UTF8.GetString(memory.GetBuffer(),0,checked((int)memory.Length));
    }
    private static void WriteCanonical(Utf8JsonWriter writer,JsonElement element)
    {
        if (element.ValueKind==JsonValueKind.Object)
        {
            writer.WriteStartObject();
            foreach (var property in element.EnumerateObject().OrderBy(p => p.Name,StringComparer.Ordinal))
            { writer.WritePropertyName(property.Name); WriteCanonical(writer,property.Value); }
            writer.WriteEndObject();
        }
        else if (element.ValueKind==JsonValueKind.Array)
        {
            writer.WriteStartArray();
            foreach (var item in element.EnumerateArray()) WriteCanonical(writer,item);
            writer.WriteEndArray();
        }
        else element.WriteTo(writer);
    }
}
