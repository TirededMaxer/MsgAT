// SPDX-License-Identifier: AGPL-3.0-only
using System.Text;
using System.Text.Json.Nodes;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Network;

#pragma warning disable CA1068 // Keep the existing cancellation parameter positions for the saved-message API.
namespace MsgAT.Windows.Core.Storage;

public sealed record SavedMessageItem(string Id,string SourcePeer,string SourceId,string Kind,string Text,string? Reference,long SavedAt,string Author,long OriginalAt,string? ContactAccount=null);
public sealed record SavedMessageSource(string Peer, string? Alias, string? Avatar, int Count);
internal sealed record MessageActionSnapshot(bool Saved,string Peer,string Id,string Kind,string Text,string Caption,string? Manifest,long Timestamp,string Author,string? ForwardName,long ForwardAt,bool Available,string? PreviewJson=null,string? PreviewManifest=null,bool IsSticker=false,string? ContactAccount=null,LocationPoint? Location=null,bool PreviewAvailable=true);
internal sealed record PreparedMessageAction(MessageActionSnapshot Source,JsonObject? Object,JsonObject? PreviewObject=null);
internal sealed class DamagedForwardCache(MessageActionSnapshot source, bool previewOnly) :
    IOException("The forwarding source cache is damaged.")
{
    internal MessageActionSnapshot SourceMessage { get; } = source;
    internal bool PreviewOnly { get; } = previewOnly;
}

public sealed class MessageActionsStore(EncryptedDatabase database,SecureSettingsStore settings,EncryptedObjectStore objects) : IAsyncDisposable
{
    private readonly object _gate=new();
    private readonly CancellationTokenSource _lifetime=new();
    private readonly HashSet<Task> _jobs=[];
    private bool _closed;
    private Task? _dispose;

    private Task<T> Owned<T>(Func<CancellationToken,Task<T>> action,CancellationToken token=default)
    {
        lock(_gate)
        {
            ObjectDisposedException.ThrowIf(_closed,this);
            var task=Task.Run(async ()=>
            {
                using var linked=CancellationTokenSource.CreateLinkedTokenSource(token,_lifetime.Token);
                linked.Token.ThrowIfCancellationRequested();
                return await action(linked.Token).ConfigureAwait(false);
            });
            _jobs.Add(task);
            return ObserveAsync(task);
        }
    }
    private async Task<T> ObserveAsync<T>(Task<T> task)
    {try{return await task.ConfigureAwait(false);}finally{lock(_gate)_jobs.Remove(task);}}
    public ValueTask DisposeAsync() {lock(_gate)return new(_dispose??=CloseAsync());}
    private async Task CloseAsync()
    {
        _closed=true;
        var jobs=_jobs.ToArray();
        await _lifetime.CancelAsync().ConfigureAwait(false);
        try {await Task.WhenAll(jobs).ConfigureAwait(false);}
        catch(Exception error) {System.Diagnostics.Trace.TraceWarning("Message action stopped ({0})",error.GetType().Name);}
        finally {_lifetime.Dispose();}
    }
    internal IReadOnlyList<MessageActionSnapshot> Capture(string? peer,IReadOnlyList<string> ids,bool allowWaiting=false, bool includePreview=true)
    {
        if(ids.Count is <1 or >100 || ids.Distinct(StringComparer.Ordinal).Count()!=ids.Count) throw new InvalidOperationException("Select between 1 and 100 different messages.");
        return database.ProtocolTransaction(db=>
        {
            var result=new List<MessageActionSnapshot>();
            foreach(var id in ids)
            {
                if (peer is not null) RequireCopyableSource(db, peer, id);
                var rows=peer is null ? db.Query("""
                    SELECT s.source_peer,s.id,CASE WHEN EXISTS(SELECT 1 FROM saved_locations l WHERE l.id=s.id) THEN 'location' WHEN card.account IS NOT NULL THEN 'contact' ELSE s.kind END,
                     s.body,s.body,o.manifest_json,s.original_at,s.author_name,NULL,s.original_at,o.local_state,card.account
                    FROM saved_messages s LEFT JOIN media_objects o ON o.object_id=s.object_id
                    LEFT JOIN saved_contact_cards card ON card.id=s.id WHERE s.id=$p0
                    """,r=>Read(r,allowWaiting),id) : db.Query("""
                    SELECT m.conversationUuid,m.messageId,m.ext,m.content,COALESCE(a.caption,''),o.manifest_json,m.timestamp,
                     CASE WHEN m.isMine=1 THEN $p2 ELSE c.alias END,f.name,COALESCE(f.original_at,m.timestamp),o.local_state,card.account
                    FROM messages m JOIN conversations c ON c.uuid=m.conversationUuid
                    LEFT JOIN message_attachments a ON a.message_id=m.messageId LEFT JOIN media_objects o ON o.object_id=a.object_id
                    LEFT JOIN message_forwards f ON f.message_id=m.messageId
                    LEFT JOIN message_contact_cards card ON card.message_id=m.messageId
                    WHERE m.messageId=$p0 AND m.conversationUuid=$p1 AND m.isRevoked=0 AND m.ext IN ('txt','attachment','contact','location')
                    """,r=>Read(r,allowWaiting),id,peer,settings.Snapshot.Nickname??"Me");
                if(rows.Count!=1) throw new InvalidOperationException("A selected message is no longer available.");
                var row=rows[0] with {Saved=peer is null,IsSticker=db.Exists(peer is null?"SELECT 1 FROM saved_stickers WHERE id=$p0":"SELECT 1 FROM message_stickers WHERE message_id=$p0",id)};
                if (row.Kind == "location")
                    row = row with { Location = ReadLocation(db, id, row.Saved)
                        ?? throw new InvalidOperationException("The location is no longer available.") };
                var preview=WebPreviewStore.Body(db,id,peer is null);
                var image=preview?["image"] as JsonObject;
                var previewAvailable = image is null || db.Exists("SELECT 1 FROM media_objects WHERE object_id=$p0 AND local_state=1",image["object_id"]!.GetValue<string>());
                row = row with { PreviewJson = preview?.ToJsonString(), PreviewManifest = image?.ToJsonString(),
                    PreviewAvailable = previewAvailable, Available = row.Available && (!includePreview || previewAvailable) };
                if(!allowWaiting && !row.Available)throw new InvalidOperationException("Wait for the selected preview images to finish downloading.");
                result.Add(row);
            }
            return (IReadOnlyList<MessageActionSnapshot>)result.OrderBy(row=>row.Timestamp).ThenBy(row=>row.Id,StringComparer.Ordinal).ToArray();
        });
    }

    private static MessageActionSnapshot Read(Microsoft.Data.Sqlite.SqliteDataReader r,bool allowWaiting)
    {
        var kind=r.GetString(2);
        var account = r.IsDBNull(11) ? null : r.GetString(11);
        if ((kind == "contact") != (account is not null)) throw new InvalidOperationException("The contact card is no longer available.");
        var available=kind!="attachment" || (!r.IsDBNull(10) && r.GetInt64(10)==1);
        if(kind=="attachment" && (r.IsDBNull(5) || (!allowWaiting && !available)))
            throw new InvalidOperationException("Wait for the selected attachments to finish downloading.");
        return new(false,r.GetString(0),r.GetString(1),kind,r.GetString(3),r.GetString(4),r.IsDBNull(5)?null:r.GetString(5),r.GetInt64(6),Name(r.GetString(7)),r.IsDBNull(8)?null:Name(r.GetString(8)),r.GetInt64(9),available,ContactAccount:account);
    }

    internal async Task<IReadOnlyList<PreparedMessageAction>> PrepareAsync(IReadOnlyList<MessageActionSnapshot> source,CancellationToken token,
        ObjectPreparation? preparation=null,Action? onObject=null,bool includePreview=true,bool detectDamagedCache=false)
    {
        var copies=new List<PreparedMessageAction>();
        async Task<JsonObject> CopyAsync(MessageActionSnapshot row, string encoded, bool previewOnly)
        {
            var manifest = JsonNode.Parse(encoded)!.AsObject();
            var reference = EncryptedObjectStore.Reference(manifest["object_id"]!.GetValue<string>());
            onObject?.Invoke();
            try
            {
                if (row.IsSticker && !previewOnly) objects.ValidateSticker(reference);
                return await objects.CopyAsync(manifest, token, preparation).ConfigureAwait(false);
            }
            catch (Exception error) when (detectDamagedCache && !row.Saved &&
                error is not (OperationCanceledException or InsufficientObjectSpaceException))
            {
                token.ThrowIfCancellationRequested();
                preparation?.Check();
                var complete = await Task.Run(() => objects.HasCompleteCiphertext(reference, token), token).ConfigureAwait(false);
                if (!complete) throw new DamagedForwardCache(row, previewOnly);
                throw;
            }
        }
        try
        {
            foreach(var row in source)
            {
                token.ThrowIfCancellationRequested();
                var copy = row.Manifest is null ? null : await CopyAsync(row, row.Manifest, previewOnly: false).ConfigureAwait(false);
                copies.Add(new(row,copy));
                if (includePreview && row.PreviewManifest is { } preview)
                    copies[^1] = copies[^1] with { PreviewObject = await CopyAsync(row, preview, previewOnly: true).ConfigureAwait(false) };
            }
            return copies;
        }
        catch {Discard(copies);throw;}
    }

    internal void Discard(IEnumerable<PreparedMessageAction> prepared)
    {
        foreach(var row in prepared)foreach(var manifest in new[]{row.Object,row.PreviewObject})
        {if(manifest is null)continue;try {objects.DiscardCopy(manifest);}catch(Exception error) when(error is IOException or UnauthorizedAccessException) { /* Durable staging remains recoverable. */ }}
    }

    private static void RequireCopyableSource(VaultTransaction db, string peer, string id)
    {
        if (MessageExpirationStore.IsExpiring(db, id))
            throw new InvalidOperationException("Messages set to auto-delete cannot be saved or forwarded.");
        if (db.Exists("""
            SELECT 1 FROM messages m JOIN conversation_retention r ON r.peer=m.conversationUuid
            WHERE m.messageId=$p0 AND m.conversationUuid=$p1 AND (m.isMine=0 OR m.status>=2)
              AND m.timestamp<=$p2-r.duration_ms
            """, id, peer, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()))
            throw new InvalidOperationException("A selected message is no longer available.");
    }

    internal static void RequireCurrent(VaultTransaction db,MessageActionSnapshot source)
    {
        if (!source.Saved) RequireCopyableSource(db, source.Peer, source.Id);
        var valid=source.Saved ? db.Exists("SELECT 1 FROM saved_messages s LEFT JOIN media_objects o ON o.object_id=s.object_id WHERE s.id=$p0 AND s.kind=$p1 AND s.body=$p2 AND COALESCE(o.manifest_json,'')=COALESCE($p3,'')",source.Id,source.Kind=="attachment"?"attachment":"txt",source.Text,source.Manifest) :
            db.Exists("""
                SELECT 1 FROM messages m LEFT JOIN message_attachments a ON a.message_id=m.messageId LEFT JOIN media_objects o ON o.object_id=a.object_id
                WHERE m.messageId=$p0 AND m.conversationUuid=$p1 AND m.isRevoked=0 AND m.ext=$p2 AND m.content=$p3
                 AND COALESCE(a.caption,'')=$p4 AND COALESCE(o.manifest_json,'')=COALESCE($p5,'')
                """,source.Id,source.Peer,source.Kind,source.Text,source.Caption,source.Manifest);
        valid &= db.Exists(source.Saved?"SELECT 1 FROM saved_stickers WHERE id=$p0":"SELECT 1 FROM message_stickers WHERE message_id=$p0",source.Id)==source.IsSticker;
        valid &= db.Text(source.Saved ? "SELECT account FROM saved_contact_cards WHERE id=$p0" :
            "SELECT account FROM message_contact_cards WHERE message_id=$p0", source.Id)==source.ContactAccount;
        valid &= ReadLocation(db, source.Id, source.Saved) == source.Location;
        valid &= WebPreviewStore.Body(db,source.Id,source.Saved)?.ToJsonString()==source.PreviewJson;
        if(!valid) throw new InvalidOperationException("A selected message changed while preparing. Select it again.");
    }

    internal static LocationPoint? ReadLocation(VaultTransaction db, string id, bool saved) =>
        db.Query(saved ? "SELECT latitude_e6,longitude_e6 FROM saved_locations WHERE id=$p0" :
            "SELECT latitude_e6,longitude_e6 FROM message_locations WHERE message_id=$p0",
            row => new LocationPoint(row.GetInt32(0), row.GetInt32(1)), id).FirstOrDefault();

    internal static JsonObject? PreviewBody(PreparedMessageAction row)
    {
        if(row.Source.PreviewJson is null)return null;
        var body=JsonNode.Parse(row.Source.PreviewJson)!.AsObject();body["image"]=row.PreviewObject?.DeepClone();return body;
    }
    internal static JsonObject ForwardHeader(MessageActionSnapshot source) => new()
    { ["name"]=source.ForwardName??source.Author,["original_at"]=source.ForwardAt };
    internal void RequireCopySpace(IReadOnlyList<MessageActionSnapshot> source,int copies,bool includePreview=true)
    {
        if(copies is <1 or >20)throw new ArgumentOutOfRangeException(nameof(copies));
        long copyBytes = 0;
        long downloadBytes = 0;
        var pendingObjects = new HashSet<string>(StringComparer.Ordinal);
        foreach (var row in source)
        {
            foreach (var encoded in new[] { row.Manifest, includePreview ? row.PreviewManifest : null })
            {
                if (encoded is null) continue;
                var manifest = JsonNode.Parse(encoded)!.AsObject();
                copyBytes = checked(copyBytes + manifest["cipher_size"]!.GetValue<long>());
                if (!row.Saved && !row.Available && pendingObjects.Add(manifest["object_id"]!.GetValue<string>()))
                    downloadBytes = checked(downloadBytes + objects.RemainingDownloadBytes(manifest));
            }
        }
        objects.RequireFreeSpace(checked(copyBytes * copies + downloadBytes));
    }

    public Task SaveAsync(string sourcePeer, IReadOnlyList<string> ids, CancellationToken token = default,
        ObjectPreparation? preparation = null)
    {
        var snapshot=ids.ToArray();
        return Owned(async ct=>{await SaveCoreAsync(sourcePeer,snapshot,ct,preparation).ConfigureAwait(false);return true;},token);
    }

    private async Task SaveCoreAsync(string sourcePeer, IReadOnlyList<string> ids, CancellationToken token,
        ObjectPreparation? preparation)
    {
        preparation?.Check();
        var source = await Task.Run(() => Capture(sourcePeer, ids, includePreview: false)
            .Select(row => row.PreviewAvailable ? row : row with { PreviewManifest = null }).ToArray(), token).ConfigureAwait(false);
        RequireCopySpace(source,1);
        var objectCount = source.Count(row => row.Manifest is not null) + source.Count(row => row.PreviewManifest is not null);
        var objectIndex = 0;
        var prepared = await PrepareAsync(source, token, preparation,
            () => preparation?.StartAttachment(++objectIndex, objectCount)).ConfigureAwait(false);
        var newIds=prepared.Select(_=>Guid.NewGuid().ToString("D")).Order(StringComparer.Ordinal).ToArray();
        var created=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var committed=false;
        try
        {
            token.ThrowIfCancellationRequested();
            database.ProtocolTransaction(db=>
            {
                token.ThrowIfCancellationRequested();
                preparation?.BeginCommit();
                var index=0;
                foreach(var row in prepared)
                {
                    RequireCurrent(db,row.Source);
                    if(row.Object is not null) EncryptedObjectStore.Remember(row.Object,db,true);
                    var id=newIds[index++];
                    db.Execute("INSERT INTO saved_messages(id,source_peer,source_id,kind,body,object_id,saved_at,author_name,original_at) VALUES($p0,$p1,$p2,$p3,$p4,$p5,$p6,$p7,$p8)",
                        id,row.Source.Peer,row.Source.Id,row.Source.Kind=="attachment"?"attachment":"txt",row.Source.Kind=="attachment"?row.Source.Caption:row.Source.Text,
                        row.Object is null?null:row.Object["object_id"]!.GetValue<string>(),created,row.Source.ForwardName??row.Source.Author,row.Source.ForwardAt);
                    if(row.Source.IsSticker)db.Execute("INSERT INTO saved_stickers(id) VALUES($p0)",id);
                    if(row.Source.ContactAccount is { } account)
                        db.Execute("INSERT INTO saved_contact_cards(id,account) VALUES($p0,$p1)",id,account);
                    if(row.Source.Location is { } point)
                        db.Execute("INSERT INTO saved_locations(id,latitude_e6,longitude_e6) VALUES($p0,$p1,$p2)",
                            id,point.LatitudeE6,point.LongitudeE6);
                    WebPreviewStore.Record(db,id,PreviewBody(row),row.Source.Text,"",true,created,saved:true);
                }
                return true;
            },notify:true);
            committed=true;
        }
        finally {if(!committed) Discard(prepared);}
    }

    public Task<IReadOnlyList<SavedMessageSource>> SourcesAsync(CancellationToken token) => Owned<IReadOnlyList<SavedMessageSource>>(ct =>
    {
        ct.ThrowIfCancellationRequested();
        IReadOnlyList<SavedMessageSource> sources = database.ProtocolTransaction(db => db.Query("""
            SELECT s.source_peer, NULLIF(c.alias,''), c.avatarPath, COUNT(*)
            FROM saved_messages s LEFT JOIN conversations c ON c.uuid=s.source_peer
            GROUP BY s.source_peer,c.alias,c.avatarPath ORDER BY MAX(s.saved_at) DESC,s.source_peer
            """, row => new SavedMessageSource(row.GetString(0), row.IsDBNull(1) ? null : row.GetString(1),
                row.IsDBNull(2) ? null : row.GetString(2), row.GetInt32(3))));
        return Task.FromResult(sources);
    }, token);

    public Task<IReadOnlyList<SavedMessageItem>> ListAsync(MessageSearchCursor? before = null, CancellationToken token = default,
        string? sourcePeer = null, string input = "") => Owned<IReadOnlyList<SavedMessageItem>>(ct =>
    {
        var query = input.Trim();
        var scalars = 0;
        foreach (var rune in query.EnumerateRunes())
            if (++scalars > 256 || rune.Value == 0) throw new ArgumentException("Invalid search text.", nameof(input));
        var limit = query.Length == 0 ? 51 : 64;
        var matches = new List<SavedMessageItem>(51);
        var cursor = before;
        while (matches.Count < 51)
        {
            ct.ThrowIfCancellationRequested();
            var batch = SavedPage(cursor, sourcePeer, limit);
            foreach (var (item, filename) in batch)
            {
                ct.ThrowIfCancellationRequested();
                if (query.Length == 0 || item.Author.Contains(query, StringComparison.OrdinalIgnoreCase) ||
                    filename?.Contains(query, StringComparison.OrdinalIgnoreCase) == true ||
                    SecurityCore.MessageSummary(item.Text, 48 * 1024).Contains(query, StringComparison.OrdinalIgnoreCase))
                {
                    matches.Add(item);
                    if (matches.Count == 51) break;
                }
            }
            if (query.Length == 0 || batch.Count < limit) break;
            var last = batch[^1].Item;
            cursor = new MessageSearchCursor(last.SavedAt, last.Id);
        }
        return Task.FromResult<IReadOnlyList<SavedMessageItem>>(matches);
    }, token);

    private List<(SavedMessageItem Item, string? Filename)> SavedPage(MessageSearchCursor? before, string? peer, int limit)
    {
        var args = new List<object?>();
        var predicates = new List<string>();
        if (peer is not null) { predicates.Add("s.source_peer=$p0"); args.Add(peer); }
        if (before is not null)
        {
            predicates.Add($"(s.saved_at,s.id)<($p{args.Count},$p{args.Count + 1})");
            args.Add(before.Timestamp);
            args.Add(before.MessageId);
        }
        var filter = predicates.Count == 0 ? "" : "WHERE " + string.Join(" AND ", predicates);
        var limitIndex = args.Count;
        args.Add(limit);
        return database.ProtocolTransaction(db => db.Query($"""
            SELECT s.id,s.source_peer,s.source_id,
                CASE WHEN EXISTS(SELECT 1 FROM saved_locations l WHERE l.id=s.id) THEN 'location'
                     WHEN card.account IS NOT NULL THEN 'contact' ELSE s.kind END,
                s.body,s.object_id,s.saved_at,s.author_name,s.original_at,card.account,
                CASE WHEN json_valid(o.manifest_json) THEN json_extract(o.manifest_json,'$.filename') END
            FROM saved_messages s LEFT JOIN saved_contact_cards card ON card.id=s.id
                LEFT JOIN media_objects o ON o.object_id=s.object_id
            {filter} ORDER BY s.saved_at DESC,s.id DESC LIMIT $p{limitIndex}
            """, row =>
        {
            var item = new SavedMessageItem(row.GetString(0), row.GetString(1), row.GetString(2), row.GetString(3), row.GetString(4),
                row.IsDBNull(5) ? null : EncryptedObjectStore.Reference(row.GetString(5)), row.GetInt64(6), row.GetString(7), row.GetInt64(8),
                row.IsDBNull(9) ? null : row.GetString(9));
            return (item, row.IsDBNull(10) ? null : row.GetString(10));
        }, args.ToArray()));
    }

    public Task<bool> HasOriginalAsync(SavedMessageItem item, CancellationToken token = default) => Owned(ct =>
    {
        ct.ThrowIfCancellationRequested();
        return Task.FromResult(database.ProtocolTransaction(db => db.Exists("""
            SELECT 1 FROM saved_messages s JOIN messages m ON m.messageId=s.source_id AND m.conversationUuid=s.source_peer
            JOIN conversations c ON c.uuid=s.source_peer
            WHERE s.id=$p0 AND s.source_id=$p1 AND s.source_peer=$p2 AND m.isRevoked=0
            """, item.Id, item.SourceId, item.SourcePeer)));
    }, token);

    public bool CanExport(string id, string reference) => database.ProtocolTransaction(db => db.Exists("""
        SELECT 1 FROM saved_messages s JOIN media_objects o ON o.object_id=s.object_id
        WHERE s.id=$p0 AND ($p1 || s.object_id)=$p2 AND o.local_state=1
        """, id, EncryptedObjectStore.ReferencePrefix, reference));

    public Task DeleteAsync(IReadOnlyList<string> ids,CancellationToken token=default)
    {
        var snapshot=ids.ToArray();
        return Owned(ct=>{ct.ThrowIfCancellationRequested();DeleteCore(snapshot);return Task.FromResult(true);},token);
    }

    private void DeleteCore(string[] ids)
    {
        if(ids.Length is <1 or >100)throw new ArgumentException("Select between 1 and 100 saved messages.",nameof(ids));
        database.ProtocolTransaction(db=>
        {
            foreach(var id in ids)db.Execute("DELETE FROM saved_messages WHERE id=$p0",id);
            return true;
        },notify:true);
    }

    private static string Name(string value)
    {
        var name=new string(value.Where(character=>!char.IsControl(character)).ToArray()).Trim();
        while(Encoding.UTF8.GetByteCount(name)>256) {var end=name.Length-1;if(end>0 && char.IsLowSurrogate(name[end])) end--;name=name[..end];}
        return name.Length==0?"User":name;
    }
}
