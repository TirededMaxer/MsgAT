// SPDX-License-Identifier: AGPL-3.0-only
using System.Text.Json.Nodes;
namespace MsgAT.Windows.Core.Storage;

public sealed record PinnedMessageItem(string Id,long Timestamp,string Kind,string Summary);
public sealed record PinnedMessagePage(IReadOnlyList<PinnedMessageItem> Items,bool HasMore);

internal static class MessagePinsStore
{
    internal static readonly string[] Schema=[
        "CREATE TABLE IF NOT EXISTS message_pin_clocks(peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,message_id TEXT NOT NULL,author TEXT NOT NULL,actor TEXT NOT NULL,sequence INTEGER NOT NULL,addition INTEGER NOT NULL,removed INTEGER NOT NULL,PRIMARY KEY(peer,message_id,author,actor))",
        "CREATE INDEX IF NOT EXISTS message_pin_target ON message_pin_clocks(message_id,peer)",
        "CREATE TRIGGER IF NOT EXISTS message_pin_delete AFTER DELETE ON messages BEGIN DELETE FROM message_pin_clocks WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId; END",
        "CREATE TRIGGER IF NOT EXISTS message_pin_revoke AFTER UPDATE OF isRevoked ON messages WHEN NEW.isRevoked=1 BEGIN DELETE FROM message_pin_clocks WHERE peer=NEW.conversationUuid AND message_id=NEW.messageId; END"
    ];
    private static long Scalar(VaultTransaction db,string sql,params object?[] args)=>db.Query(sql,r=>r.GetInt64(0),args).FirstOrDefault();
    internal static JsonObject Body(VaultTransaction db,string peer,string local,string target,bool pinned,bool notify=false)
    {
        var author = db.Query("""
            SELECT CASE WHEN isMine=1 THEN $p0 ELSE conversationUuid END
            FROM messages m WHERE messageId=$p1 AND conversationUuid=$p2 AND isRevoked=0
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p3)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p3-r.duration_ms)
            """, row => row.GetString(0), local, target, peer, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()).FirstOrDefault()
            ??throw new InvalidOperationException("This message is no longer available.");
        var next=Scalar(db,"SELECT sequence FROM message_pin_clocks WHERE peer=$p0 AND message_id=$p1 AND author=$p2 AND actor=$p3",peer,target,author,local)+1;
        if(next>uint.MaxValue)throw new InvalidOperationException("The pin revision limit was reached.");
        var observed=new JsonArray();
        if(!pinned)foreach(var clock in db.Query("SELECT actor,addition FROM message_pin_clocks WHERE peer=$p0 AND message_id=$p1 AND author=$p2 AND addition>removed ORDER BY actor",r=>(Actor:r.GetString(0),Sequence:r.GetInt64(1)),peer,target,author))
            observed.Add(new JsonObject {["actor"]=clock.Actor,["sequence"]=clock.Sequence});
        return new JsonObject {["message_id"]=target,["author"]=author,["sequence"]=next,["pinned"]=pinned,["observed"]=observed,["notify"]=pinned && notify};
    }
    internal static bool Apply(VaultTransaction db,string peer,string local,string sender,JsonObject body,Action<string>? onNotify=null)
    {
        var target=body["message_id"]!.GetValue<string>();var author=body["author"]!.GetValue<string>();var sequence=body["sequence"]!.GetValue<long>();var pinned=body["pinned"]!.GetValue<bool>();var observed=body["observed"]!.AsArray();
        if((sender!=local && sender!=peer) || (author!=local && author!=peer) || sequence<1 || sequence>uint.MaxValue || observed.Count>2)return false;
        if(!db.Exists("SELECT 1 FROM conversations WHERE uuid=$p0",peer))return false;
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (db.Exists("SELECT 1 FROM message_expiration WHERE message_id=$p0 AND expires_at<=$p1", target, now)) return false;
        var known = db.Query("""
            SELECT m.conversationUuid,m.isMine,m.isRevoked OR EXISTS(
                SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p1-r.duration_ms)
            FROM messages m WHERE m.messageId=$p0
            """, r => new { Peer = r.GetString(0), Mine = r.GetInt32(1) != 0, Unavailable = r.GetInt32(2) != 0 },
            target, now).FirstOrDefault();
        if (known is not null && (known.Peer != peer || known.Unavailable || author != (known.Mine ? local : peer))) return false;
        if(known is null && !db.Exists("SELECT 1 FROM message_pin_clocks WHERE peer=$p0 AND message_id=$p1 AND author=$p2 AND actor=$p3",peer,target,author,sender))
        {
            if(Scalar(db,"SELECT COUNT(*) FROM message_pin_clocks p WHERE p.peer=$p0 AND NOT EXISTS(SELECT 1 FROM messages m WHERE m.messageId=p.message_id AND m.conversationUuid=p.peer)",peer)>=256 ||
                Scalar(db,"SELECT COUNT(*) FROM message_pin_clocks p WHERE NOT EXISTS(SELECT 1 FROM messages m WHERE m.messageId=p.message_id AND m.conversationUuid=p.peer)")>=8192)return false;
        }
        var notify = onNotify is not null && known is not null && pinned && body["notify"]?.GetValue<bool>() == true &&
            sequence > Scalar(db,"SELECT sequence FROM message_pin_clocks WHERE peer=$p0 AND message_id=$p1 AND author=$p2 AND actor=$p3",peer,target,author,sender);
        db.Execute("""
            INSERT INTO message_pin_clocks(peer,message_id,author,actor,sequence,addition,removed) VALUES($p0,$p1,$p2,$p3,$p4,$p5,0)
            ON CONFLICT(peer,message_id,author,actor) DO UPDATE SET
            addition=CASE WHEN excluded.sequence>sequence THEN excluded.addition ELSE addition END,sequence=MAX(sequence,excluded.sequence)
            """,peer,target,author,sender,sequence,pinned?sequence:0L);
        foreach(var item in observed)
        {
            var actor=item!["actor"]!.GetValue<string>();var removed=item["sequence"]!.GetValue<long>();
            if(actor!=local && actor!=peer)continue;
            db.Execute("UPDATE message_pin_clocks SET removed=MAX(removed,MIN(sequence,$p0)) WHERE peer=$p1 AND message_id=$p2 AND author=$p3 AND actor=$p4",removed,peer,target,author,actor);
        }
        if (notify) onNotify?.Invoke(target);
        return true;
    }
    internal static PinnedMessagePage List(VaultTransaction db,string peer,string local,int offset=0,int pageSize=50,
                                           PinnedMessageItem? through = null)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(offset);
        if (pageSize is < 1 or > 50) throw new ArgumentOutOfRangeException(nameof(pageSize));
        var values = new List<object?> { peer, local, offset, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), pageSize + 1 };
        var anchorOrder = "";
        if (through is not null)
        {
            values.Add(through.Timestamp);
            values.Add(through.Id);
            anchorOrder = "CASE WHEN (m.timestamp,m.messageId)<=($p5,$p6) THEN 0 ELSE 1 END,";
        }
        var rows=db.Query($$"""
            WITH active_pins AS (
                SELECT DISTINCT message_id,author FROM message_pin_clocks
                WHERE peer=$p0 AND addition>removed
            )
            SELECT m.messageId,m.timestamp,m.ext,
             CASE WHEN m.ext='txt' THEN substr(m.content,1,240) WHEN m.ext='contact' THEN 'Contact: '||substr(m.content,1,230) WHEN m.ext IN ('location','live_location') THEN substr(m.content,1,240) WHEN m.ext='attachment' THEN COALESCE(NULLIF(substr(a.caption,1,240),''),json_extract(o.manifest_json,'$.filename'),'[Attachment]') ELSE '[Call]' END
            FROM active_pins p CROSS JOIN messages m ON m.messageId=p.message_id
            LEFT JOIN message_attachments a ON a.message_id=m.messageId
            LEFT JOIN media_objects o ON o.object_id=a.object_id
            WHERE m.conversationUuid=$p0 AND m.isRevoked=0
              AND p.author=CASE WHEN m.isMine=1 THEN $p1 ELSE m.conversationUuid END
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p3)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p3-r.duration_ms)
            ORDER BY {{anchorOrder}}m.timestamp DESC,m.messageId DESC LIMIT $p4 OFFSET $p2
            """,r=>new PinnedMessageItem(r.GetString(0),r.GetInt64(1),r.GetString(2),r.GetString(3)), values.ToArray());
        return new(rows.Take(pageSize).ToArray(),rows.Count>pageSize);
    }
}
