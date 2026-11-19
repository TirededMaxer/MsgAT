// SPDX-License-Identifier: AGPL-3.0-only
namespace MsgAT.Windows.Core.Storage;

public enum CallOutcome { Completed, Missed, Declined, Busy, Canceled, Failed }

public sealed record CallHistoryEntry(string Peer, string CallId, string Alias, bool Outgoing, bool Video,
    CallOutcome Outcome, long Duration, long EndedAt, string AvatarPath = "");

internal static class CallHistory
{
    internal const string Table = """
        CREATE TABLE IF NOT EXISTS call_history(
            peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,
            call_id TEXT NOT NULL, outgoing INTEGER NOT NULL CHECK(outgoing IN(0,1)),
            video INTEGER NOT NULL CHECK(video IN(0,1)), outcome TEXT NOT NULL
              CHECK(outcome IN('completed','missed','declined','busy','canceled','failed')),
            duration INTEGER NOT NULL CHECK(duration>=0), ended_at INTEGER NOT NULL,
            PRIMARY KEY(peer,call_id))
        """;
    internal const string Index = "CREATE INDEX IF NOT EXISTS call_history_timeline ON call_history(ended_at DESC,call_id DESC,peer DESC)";

    internal static void Insert(VaultTransaction db, string peer, string callId, bool outgoing, bool video,
        CallOutcome outcome, long duration, long endedAt) => db.Execute("""
        INSERT INTO call_history(peer,call_id,outgoing,video,outcome,duration,ended_at)
        SELECT uuid,$p1,$p2,$p3,$p4,$p5,$p6 FROM conversations WHERE uuid=$p0
        ON CONFLICT(peer,call_id) DO NOTHING
        """, peer,callId,outgoing ? 1 : 0,video ? 1 : 0,outcome.ToString().ToLowerInvariant(),duration,endedAt);

    internal static void Remove(VaultTransaction db, CallHistoryEntry? entry)
    {
        if (entry is null)
            db.Execute("DELETE FROM call_history");
        else
            db.Execute("DELETE FROM call_history WHERE peer=$p0 AND call_id=$p1", entry.Peer, entry.CallId);
    }

    internal static IReadOnlyList<CallHistoryEntry> Page(VaultTransaction db, CallHistoryEntry? before, int count, bool missedOnly)
    {
        var conditions = new List<string>(2);
        if (missedOnly) conditions.Add("h.outgoing=0 AND h.outcome='missed'");
        if (before is not null) conditions.Add("(h.ended_at,h.call_id,h.peer)<($p1,$p2,$p3)");
        var filter = conditions.Count == 0 ? "" : "WHERE " + string.Join(" AND ", conditions);
        return db.Query($"""
            SELECT h.peer,h.call_id,c.alias,h.outgoing,h.video,h.outcome,h.duration,h.ended_at,c.avatarPath
            FROM call_history h JOIN conversations c ON c.uuid=h.peer {filter}
            ORDER BY h.ended_at DESC,h.call_id DESC,h.peer DESC LIMIT $p0
            """, row => new CallHistoryEntry(row.GetString(0),row.GetString(1),row.GetString(2),row.GetInt64(3)!=0,
                row.GetInt64(4)!=0,Enum.Parse<CallOutcome>(row.GetString(5),ignoreCase:true),row.GetInt64(6),row.GetInt64(7),row.GetString(8)),
            before is null ? new object[] { count + 1 } : new object[] { count + 1, before.EndedAt, before.CallId, before.Peer });
    }
}
