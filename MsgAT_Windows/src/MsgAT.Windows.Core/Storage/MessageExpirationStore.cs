// SPDX-License-Identifier: AGPL-3.0-only
namespace MsgAT.Windows.Core.Storage;

public sealed record MessageRetentionSettings(long Outgoing, long Local)
{
    public bool ShortensLocalHistory(MessageRetentionSettings previous) =>
        Local > 0 && (previous.Local == 0 || Local < previous.Local);
}

internal static class MessageExpirationStore
{
    private static readonly HashSet<long> Durations = [0, 60_000, 3_600_000, 86_400_000, 604_800_000, 2_592_000_000];
    private static readonly HashSet<long> LocalDurations = [0, 86_400_000, 604_800_000, 2_592_000_000, 7_776_000_000];

    internal static readonly string[] Schema =
    [
        """
        CREATE TABLE IF NOT EXISTS conversation_expiration(
        peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,
        duration_ms INTEGER NOT NULL CHECK(duration_ms IN (60000,3600000,86400000,604800000,2592000000)))
        """,
        """
        CREATE TABLE IF NOT EXISTS conversation_retention(
            peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,
            duration_ms INTEGER NOT NULL CHECK(duration_ms IN (86400000,604800000,2592000000,7776000000)))
        """,
        """
        CREATE TABLE IF NOT EXISTS message_expiration(
        message_id TEXT PRIMARY KEY NOT NULL REFERENCES messages(messageId) ON DELETE CASCADE,
        expires_at INTEGER NOT NULL CHECK(expires_at>0))
        """,
        """
        CREATE INDEX IF NOT EXISTS message_expiration_due ON message_expiration(expires_at,message_id)
        """,
        """
        CREATE TRIGGER IF NOT EXISTS message_expiration_outgoing AFTER INSERT ON messages
        WHEN NEW.isMine=1 BEGIN
        INSERT INTO message_expiration(message_id,expires_at)
        SELECT NEW.messageId,NEW.timestamp+json_extract(p.body_json,'$.expire_after_ms')
        FROM protocol_pending_events p WHERE p.event_id=NEW.messageId
          AND p.recipient=NEW.conversationUuid AND json_type(p.body_json,'$.expire_after_ms')='integer';
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS message_expiration_cleanup BEFORE DELETE ON messages
        WHEN EXISTS(SELECT 1 FROM message_expiration WHERE message_id=OLD.messageId) BEGIN
        DELETE FROM protocol_outbox WHERE recipient=OLD.conversationUuid AND event_id IN (
            SELECT OLD.messageId WHERE OLD.isMine=1
            UNION ALL SELECT event_id FROM send_related_events WHERE message_id=OLD.messageId
            UNION ALL SELECT event_id FROM protocol_receipts WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId);
        DELETE FROM protocol_pending_events WHERE recipient=OLD.conversationUuid AND event_id IN (
            SELECT OLD.messageId WHERE OLD.isMine=1
            UNION ALL SELECT event_id FROM send_related_events WHERE message_id=OLD.messageId
            UNION ALL SELECT event_id FROM protocol_receipts WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId);
        DELETE FROM protocol_receipts WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId;
        DELETE FROM read_receipt_suppression WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId;
        DELETE FROM message_changes WHERE conversation_uuid=OLD.conversationUuid AND message_id=OLD.messageId;
        UPDATE messages SET replyToContent=NULL WHERE conversationUuid=OLD.conversationUuid AND replyToId=OLD.messageId;
        END
        """
    ];

    internal static long Duration(VaultTransaction db, string peer) =>
        db.Query("SELECT duration_ms FROM conversation_expiration WHERE peer=$p0",
            row => row.GetInt64(0), peer).FirstOrDefault();

    internal static MessageRetentionSettings Settings(VaultTransaction db, string peer) =>
        new(Duration(db, peer), LocalDuration(db, peer));

    internal static void SetSettings(VaultTransaction db, string peer, MessageRetentionSettings settings)
    {
        var duration = settings.Outgoing;
        if (!Durations.Contains(duration) || !LocalDurations.Contains(settings.Local))
            throw new ArgumentOutOfRangeException(nameof(settings));
        if (duration == 0) db.Execute("DELETE FROM conversation_expiration WHERE peer=$p0", peer);
        else db.Execute("""
            INSERT INTO conversation_expiration(peer,duration_ms) VALUES($p0,$p1)
            ON CONFLICT(peer) DO UPDATE SET duration_ms=excluded.duration_ms
            """, peer, duration);
        if (settings.Local == 0) db.Execute("DELETE FROM conversation_retention WHERE peer=$p0", peer);
        else db.Execute("""
            INSERT INTO conversation_retention(peer,duration_ms) VALUES($p0,$p1)
            ON CONFLICT(peer) DO UPDATE SET duration_ms=excluded.duration_ms
            """, peer, settings.Local);
    }

    private static long LocalDuration(VaultTransaction db, string peer) =>
        db.Number("SELECT duration_ms FROM conversation_retention WHERE peer=$p0", peer);

    internal static bool DiscardIncoming(VaultTransaction db, string peer, string kind, long created, long now)
    {
        if (kind is not ("chat.text" or "chat.attachment" or "chat.contact" or "chat.location" or "chat.location.start")) return false;
        var duration = LocalDuration(db, peer);
        return duration > 0 && created <= now - duration;
    }

    internal static int MarkDueRetention(VaultTransaction db, long now, int limit = 64) => db.Execute("""
        INSERT INTO message_expiration(message_id,expires_at)
        SELECT m.messageId,$p0 FROM conversation_retention r
        JOIN messages m ON m.conversationUuid=r.peer AND m.timestamp<=$p0-r.duration_ms
        WHERE m.isMine=0 OR m.status>=2
        ORDER BY m.timestamp,m.messageId LIMIT $p1
        ON CONFLICT(message_id) DO UPDATE SET expires_at=MIN(message_expiration.expires_at,excluded.expires_at)
        """, now, limit);

    internal static bool IsExpiring(VaultTransaction db, string id) =>
        db.Exists("SELECT 1 FROM message_expiration WHERE message_id=$p0", id);

    internal static void Record(VaultTransaction db, string id, long deadline) =>
        db.Execute("INSERT INTO message_expiration(message_id,expires_at) VALUES($p0,$p1)", id, deadline);

    internal static (long Next, IReadOnlySet<string> Ids) RemoveDue(VaultTransaction db, long now)
    {
        var ids = db.Query("""
            SELECT message_id FROM message_expiration WHERE expires_at<=$p0 ORDER BY expires_at,message_id LIMIT 64
            """, row => row.GetString(0), now).ToHashSet(StringComparer.Ordinal);
        if (ids.Count > 0)
            db.Execute("""
                DELETE FROM messages WHERE messageId IN (
                    SELECT message_id FROM message_expiration WHERE expires_at<=$p0 ORDER BY expires_at,message_id LIMIT 64)
                """, now);
        var next = db.Query("SELECT MIN(expires_at) FROM message_expiration",
            row => row.IsDBNull(0) ? long.MaxValue : row.GetInt64(0)).Single();
        return (next, ids);
    }
}
