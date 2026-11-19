// SPDX-License-Identifier: AGPL-3.0-only
using System.Text;

namespace MsgAT.Windows.Core.Storage;

public enum MessageSearchKind { All, Text, Attachments }
public sealed record MessageSearchHit(string MessageId, string Peer, string Alias, string Preview, long Timestamp, bool IsAttachment);
public sealed record MessageSearchCursor(long Timestamp, string MessageId);
public sealed record MessageSearchPage(IReadOnlyList<MessageSearchHit> Hits, MessageSearchCursor? Next);

public sealed class MessageSearchStore(EncryptedDatabase database)
{
    private readonly object _gate = new();
    private bool _ready;

    public MessageSearchPage Search(string input, string? peer = null, MessageSearchCursor? before = null,
        MessageSearchKind kind = MessageSearchKind.All, int limit = 50, bool newer = false, DateTime? day = null)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(limit);
        var query = input.Trim();
        if (query.Length == 0) return new([], null);
        if (query.EnumerateRunes().Count() > 256 || query.Contains('\0')) throw new ArgumentException("Search text is too long or invalid.", nameof(input));
        long? dayStart = null, dayEnd = null;
        if (day is { } value)
        {
            var midnight = DateTime.SpecifyKind(value.Date, DateTimeKind.Unspecified);
            dayStart = new DateTimeOffset(midnight).ToUnixTimeMilliseconds();
            dayEnd = new DateTimeOffset(midnight.AddDays(1)).ToUnixTimeMilliseconds();
        }
        lock (_gate)
        {
            var result = database.ProtocolTransaction(db =>
            {
                if (!_ready)
                {
                    using var stream = typeof(MessageSearchStore).Assembly.GetManifestResourceStream("MsgAT.MessageSearch.sql")
                        ?? throw new InvalidOperationException("The search resource is unavailable.");
                    using var reader = new StreamReader(stream);
                    foreach (var statement in reader.ReadToEnd().Split("\n-- statement\n", StringSplitOptions.None)) db.Execute(statement);
                }
                var indexed = query.EnumerateRunes().Count() >= 3;
                var condition = indexed ? "local_message_search MATCH $p0" : "instr(lower(s.body),lower($p0))>0";
                var extensionFilter = kind switch
                {
                    MessageSearchKind.Text => "txt",
                    MessageSearchKind.Attachments => "attachment",
                    _ => null
                };
                var comparison = newer ? ">" : "<";
                var order = newer ? "ASC" : "DESC";
                var rows = db.Query($"""
                    SELECT m.messageId,m.conversationUuid,c.alias,s.body,m.timestamp,m.ext
                    FROM local_message_search s JOIN messages m ON m.rowid=s.rowid
                    JOIN conversations c ON c.uuid=m.conversationUuid
                    WHERE {condition} AND m.isRevoked=0 AND ($p1 IS NULL OR m.conversationUuid=$p1)
                     AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p6)
                     AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                         AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p6-r.duration_ms)
                     AND ($p4 IS NULL OR m.ext=$p4)
                     AND ($p7 IS NULL OR (m.timestamp>=$p7 AND m.timestamp<$p8))
                     AND ($p2 IS NULL OR m.timestamp{comparison}$p2 OR (m.timestamp=$p2 AND m.messageId{comparison}$p3))
                    ORDER BY m.timestamp {order},m.messageId {order} LIMIT $p5
                    """, r => new MessageSearchHit(r.GetString(0),r.GetString(1),r.GetString(2),Preview(r.GetString(3),query),r.GetInt64(4),r.GetString(5) == "attachment"),
                    indexed ? "\""+query.Replace("\"","\"\"",StringComparison.Ordinal)+"\"" : query, peer, before?.Timestamp, before?.MessageId,
                    extensionFilter, (long)limit + 1, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), dayStart, dayEnd);
                var hits = rows.Take(limit).ToArray();
                return new MessageSearchPage(hits, rows.Count>limit ? new(hits[^1].Timestamp,hits[^1].MessageId) : null);
            });
            _ready = true;
            return result;
        }
    }

    private static string Preview(string source,string query)
    {
        var body=MsgAT.Windows.Core.Crypto.SecurityCore.MessageSummary(source,48*1024);
        var at=body.IndexOf(query,StringComparison.OrdinalIgnoreCase);
        var start=Math.Max(0,at-70);
        if(start>0 && char.IsLowSurrogate(body[start])) start--;
        var end=Math.Min(body.Length,start+280);
        if(end<body.Length && end>0 && char.IsHighSurrogate(body[end-1])) end--;
        return (start>0?"…":"")+body[start..end]+(end<body.Length?"…":"");
    }
}
