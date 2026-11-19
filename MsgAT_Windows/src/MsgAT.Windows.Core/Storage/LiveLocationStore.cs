// SPDX-License-Identifier: AGPL-3.0-only
using System.Text.Json.Nodes;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Network;

namespace MsgAT.Windows.Core.Storage;

public sealed record LiveLocationSnapshot(long StartedAt, long ExpiresAt, int Revision, bool Ended, LocationPoint Point);

internal static class LiveLocationStore
{
    public static bool Apply(VaultTransaction db, string peer, bool mine, string kind, string eventId,
        JsonObject body, long createdAt, long now)
    {
        var start = kind == "chat.location.start";
        var messageId = start ? eventId : body["message_id"]!.GetValue<string>();
        var direction = mine ? 1 : 0;
        var existing = db.Query("SELECT conversationUuid,isMine,ext,isRevoked FROM messages WHERE messageId=$p0",
            row => (Peer: row.GetString(0), Mine: row.GetInt32(1), Type: row.GetString(2), Revoked: row.GetInt32(3)), messageId);
        if (existing.Count > 0)
        {
            var known = existing[0];
            if (known.Peer != peer || known.Mine != direction || known.Type != "live_location" || known.Revoked != 0) return false;
        }
        else if (mine && !start) return false;

        PruneOrphans(db, now);
        var previous = db.Text("SELECT state_json FROM live_location_states WHERE peer=$p0 AND is_mine=$p1 AND message_id=$p2",
            peer, direction, messageId);
        if (!start && previous is null && (mine || CountOrphans(db, peer) >= 16 || CountOrphans(db, null) >= 256)) return false;
        var result = SignalProtocolStore.Call(new JsonObject
        {
            ["operation"] = "live_location_transition", ["state"] = previous is null ? null : JsonNode.Parse(previous),
            ["event_kind"] = kind, ["event_id"] = eventId, ["body"] = body.DeepClone(),
            ["created_at"] = createdAt, ["now"] = now
        });
        if (result["changed"]?.GetValue<bool>() != true) return false;
        var state = result["state"]!.AsObject();
        db.Execute("""
            INSERT INTO live_location_states(peer,is_mine,message_id,expires_at,anchored,state_json)
            VALUES($p0,$p1,$p2,$p3,$p4,$p5)
            ON CONFLICT(peer,is_mine,message_id) DO UPDATE SET
            expires_at=excluded.expires_at,anchored=excluded.anchored,state_json=excluded.state_json
            """, peer, direction, messageId, state["expires_at"]!.GetValue<long>(),
            state["anchored"]!.GetValue<bool>() ? 1 : 0, state.ToJsonString());
        return true;
    }

    public static LiveLocationSnapshot? Read(VaultTransaction db, string peer, string messageId, bool mineOnly = false)
    {
        var json = db.Text("""
            SELECT s.state_json FROM messages m JOIN live_location_states s
              ON s.message_id=m.messageId AND s.peer=m.conversationUuid AND s.is_mine=m.isMine
            WHERE m.conversationUuid=$p0 AND m.messageId=$p1 AND m.ext='live_location' AND m.isRevoked=0 AND s.anchored=1
              AND ($p2=0 OR m.isMine=1)
            """, peer, messageId, mineOnly ? 1 : 0);
        if (json is null) return null;
        var state = JsonNode.Parse(json)!.AsObject();
        return new LiveLocationSnapshot(state["started_at"]!.GetValue<long>(), state["expires_at"]!.GetValue<long>(),
            state["revision"]!.GetValue<int>(), state["ended"]!.GetValue<bool>(), LocationPoint.Parse(state["point"]!.AsObject()));
    }

    public static bool HasActiveShare(VaultTransaction db, string peer, long now) =>
        db.Exists("""
            SELECT 1 FROM live_location_states
            WHERE peer=$p0 AND is_mine=1 AND anchored=1 AND expires_at>$p1 AND json_extract(state_json,'$.ended')=0
            LIMIT 1
            """, peer, now);

    public static void ReplaceOutgoing(VaultTransaction db, string eventId, string peer, string messageId, long expiresAt, bool end)
    {
        if (end)
            db.Execute("DELETE FROM live_location_outgoing WHERE message_id=$p0", messageId);
        else
            db.Execute("""
                DELETE FROM live_location_outgoing WHERE message_id=$p0 AND NOT EXISTS (
                    SELECT 1 FROM protocol_outbox b WHERE b.event_id=live_location_outgoing.event_id AND b.delivered=0
                )
                """, messageId);
        db.Execute("INSERT INTO live_location_outgoing(event_id,peer,message_id,expires_at) VALUES($p0,$p1,$p2,$p3)",
            eventId, peer, messageId, expiresAt);
    }

    public static void PruneOutgoing(VaultTransaction db, long now) =>
        db.Execute("""
            DELETE FROM live_location_outgoing WHERE expires_at<=$p0 OR NOT EXISTS (
                SELECT 1 FROM contact_states c WHERE c.uuid=live_location_outgoing.peer AND c.accepted=1 AND c.blocked=0
            )
            """, now);

    public static void PruneOrphans(VaultTransaction db, long now) =>
        db.Execute("DELETE FROM live_location_states WHERE anchored=0 AND expires_at<=$p0", now);

    private static long CountOrphans(VaultTransaction db, string? peer) =>
        peer is null ? db.Query("SELECT COUNT(*) FROM live_location_states WHERE anchored=0", row => row.GetInt64(0)).Single()
        : db.Query("SELECT COUNT(*) FROM live_location_states WHERE anchored=0 AND peer=$p0", row => row.GetInt64(0), peer).Single();
}
