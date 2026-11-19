// SPDX-License-Identifier: AGPL-3.0-only
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Core.Network;

public sealed record ForwardTaskInfo(string Id,long CreatedAt,string State,int MessageCount,IReadOnlyList<string?> Recipients,string? ErrorCode,
    int PendingMediaMessages, string? SourcePeer, string? WaitingMessageId, SendOptions Options);
internal sealed record ForwardQueueRequest(string? SourcePeer,string[] Ids,string[] Recipients,bool HideSender,bool HideCaption,string[] Fingerprints,int Version,long? SendAt=null,bool Silent=false,bool OmitPreview=false);

/// <summary>One durable batch is atomically replaced by its ordinary message/outbox records.</summary>
public sealed class ForwardTaskQueue : IAsyncDisposable
{
    private sealed record Job(string Id,string Payload,long CreatedAt);
    private static readonly JsonSerializerOptions EncodingOptions=new() {PropertyNamingPolicy=JsonNamingPolicy.SnakeCaseLower};
    private readonly SignalProtocolStore _store;
    private readonly MessageActionsStore _actions;
    private readonly Func<string,ForwardQueueRequest,ObjectPreparation,CancellationToken,Task<bool>> _commit;
    private readonly Action _wakeDownloads;
    private readonly object _gate=new();
    private readonly HashSet<Task> _requests=[];
    private readonly CancellationTokenSource _lifetime=new();
    private readonly Channel<bool> _wake=Channel.CreateBounded<bool>(new BoundedChannelOptions(1) {SingleReader=true,FullMode=BoundedChannelFullMode.DropWrite});
    private readonly Task _worker;
    private readonly Task _timer;
    private string? _activeId;
    private CancellationTokenSource? _activeStop;
    private ObjectPreparation? _activePreparation;
    private sealed record WaitingMedia(int Count, string FirstMessageId);
    private readonly System.Collections.Concurrent.ConcurrentDictionary<string, WaitingMedia> _pendingMediaMessages = new();
    public event EventHandler? AvailabilityChanged;
    private bool _closed;
    private Task? _dispose;
    private static long Now=>DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    internal ForwardTaskQueue(SignalProtocolStore store,MessageActionsStore actions,
        Func<string,ForwardQueueRequest,ObjectPreparation,CancellationToken,Task<bool>> commit, Action wakeDownloads)
    {
        _store=store;_actions=actions;_commit=commit;
        _wakeDownloads = wakeDownloads;
        _worker=Task.Run(RunAsync);_timer=Task.Run(TickAsync);Wake();
    }
    internal void Wake(){lock(_gate)if(!_closed)_wake.Writer.TryWrite(true);}
    public PreparationProgress? Progress(string id)
    {
        ObjectPreparation? preparation;
        lock(_gate)preparation=_activeId==id?_activePreparation:null;
        return preparation?.Snapshot();
    }
    private Task<T> Owned<T>(Func<CancellationToken,T> operation,CancellationToken token=default)
    {
        lock(_gate)
        {
            ObjectDisposedException.ThrowIf(_closed,this);
            var request=Task.Run(()=>
            {
                using var linked=CancellationTokenSource.CreateLinkedTokenSource(token,_lifetime.Token);
                linked.Token.ThrowIfCancellationRequested();return operation(linked.Token);
            });
            _requests.Add(request);return ObserveAsync(request);
        }
    }
    private async Task<T> ObserveAsync<T>(Task<T> request)
    {try{return await request.ConfigureAwait(false);}finally{lock(_gate)_requests.Remove(request);}}

    internal Task<bool> EnqueueAsync(string? peer,IReadOnlyList<string> ids,IReadOnlyList<string> recipients,bool hideSender,bool hideCaption,CancellationToken token,SendOptions? options=null,bool omitPreview=false)
    {
        var selected=ids.ToArray();var targets=recipients.ToArray();
        return Owned(ct=>
        {
            if(options?.SendAt is { } due && (due<=Now || due>253402214399999L))throw new InvalidOperationException("Choose a future scheduled time.");
            if(targets.Length is <1 or >20 || targets.Distinct(StringComparer.Ordinal).Count()!=targets.Length)
                throw new InvalidOperationException("Select between 1 and 20 different recipients.");
            var source=_actions.Capture(peer,selected,allowWaiting:true);
            var request=new ForwardQueueRequest(peer,source.Select(row=>row.Id).ToArray(),targets,hideSender,hideCaption,source.Select(Fingerprint).ToArray(),omitPreview ? 3 : 2,options?.SendAt,options?.Silent??false,omitPreview);
            var payload=JsonSerializer.Serialize(request,EncodingOptions);
            if(Encoding.UTF8.GetByteCount(payload)>65536)throw new InvalidOperationException("The forwarding request is too large.");
            ct.ThrowIfCancellationRequested();
            _store.Transaction(db=>
            {
                ct.ThrowIfCancellationRequested();
                if(db.Number("SELECT COUNT(*) FROM forward_jobs")>=20)throw new InvalidOperationException("The forwarding queue is full. Remove or complete an existing task.");
                RequireRecipients(db,targets);
                foreach(var row in source)MessageActionsStore.RequireCurrent(db,row);
                db.Execute("INSERT INTO forward_jobs(id,request_json,created_at,state) VALUES($p0,$p1,$p2,'waiting')",Guid.NewGuid().ToString("D"),payload,Now);
                return true;
            },notify:true);
            Wake();return true;
        },token);
    }
    public Task<IReadOnlyList<ForwardTaskInfo>> ListAsync(CancellationToken token=default)=>Owned<IReadOnlyList<ForwardTaskInfo>>(_=>_store.Transaction(db=>db.Query(
        """
        SELECT id,request_json,created_at,state,error_code,
               (SELECT json_group_array(alias) FROM (
                    SELECT NULLIF(c.alias,'') AS alias
                    FROM json_each(CASE WHEN json_valid(job.request_json) THEN job.request_json END,'$.recipients') target
                    LEFT JOIN conversations c ON c.uuid=target.value
                    ORDER BY target.key
               ))
        FROM forward_jobs job ORDER BY created_at,id LIMIT 20
        """,r=>
        {
            ForwardQueueRequest? request=null;
            try{request=Decode(r.GetString(1));}catch(InvalidDataException){}
            var aliases=JsonSerializer.Deserialize<string?[]>(r.GetString(5))??[];
            var recipients=Enumerable.Range(0,request?.Recipients.Length??0).Select(index=>aliases.ElementAtOrDefault(index)).ToArray();
            var id = r.GetString(0);
            var state = r.GetString(3);
            var pending = state == "waiting" ? _pendingMediaMessages.GetValueOrDefault(id) : null;
            return new ForwardTaskInfo(id, r.GetInt64(2), state, request?.Ids.Length ?? 0, recipients,
                r.IsDBNull(4) ? null : r.GetString(4), pending?.Count ?? 0, request?.SourcePeer,
                pending?.FirstMessageId, new SendOptions(request?.SendAt, request?.Silent == true));
        })),token);
    public Task<bool> CancelAsync(string id,CancellationToken token=default)=>Owned(_=>
    {
        var removed=_store.Transaction(db=>db.Execute("DELETE FROM forward_jobs WHERE id=$p0",id)>0,notify:true);
        if (removed) _pendingMediaMessages.TryRemove(id, out _);
        if(removed)lock(_gate){if(_activeId==id)_activeStop?.Cancel();}
        Wake();return removed;
    },token);
    public Task<bool> RetryAsync(string id,CancellationToken token=default)=>Owned(operationToken=>
    {
        var payload = _store.Transaction(db => db.Text("""
            SELECT request_json FROM forward_jobs WHERE id=$p0 AND state='failed' AND error_code='source_download_unavailable'
            """, id));
        var request = payload is null ? null : Decode(payload);
        IReadOnlyList<MessageActionSnapshot>? source = null;
        if (request is not null)
        {
            source = _actions.Capture(request.SourcePeer, request.Ids, allowWaiting: true, includePreview: !request.OmitPreview);
            RequireSnapshot(source, request);
        }
        operationToken.ThrowIfCancellationRequested();
        var changed = _store.Transaction(db =>
        {
            if (request is not null && source is not null)
            {
                if (!db.Exists("""
                    SELECT 1 FROM forward_jobs WHERE id=$p0 AND request_json=$p1
                      AND state='failed' AND error_code='source_download_unavailable'
                    """, id, payload)) return false;
                var unavailable = UnavailableDownloads(db, source, request.OmitPreview);
                if (unavailable.Length > 0 && !RequestDownloads(db, request, source, unavailable)) return false;
            }
            else if (!db.Exists("""
                SELECT 1 FROM forward_jobs WHERE id=$p0 AND state='failed'
                  AND error_code IS NOT 'source_changed' AND error_code IS NOT 'source_download_unavailable'
                """, id)) return false;
            return db.Execute("UPDATE forward_jobs SET state='waiting',error_code=NULL,created_at=$p1 WHERE id=$p0", id, Now) > 0;
        }, notify: true);
        if (changed && request is not null) _wakeDownloads();
        Wake();return changed;
    },token);

    public Task<bool> RequestMissingDownloadsAsync(string id, CancellationToken token = default) => Owned(operationToken =>
    {
        var payload = _store.Transaction(db => db.Text(
            "SELECT request_json FROM forward_jobs WHERE id=$p0 AND state='waiting'", id));
        if (payload is null) return false;
        var request = Decode(payload);
        if (request.SourcePeer is not { } peer) return false;
        var source = _actions.Capture(peer, request.Ids, allowWaiting: true, includePreview: !request.OmitPreview);
        RequireSnapshot(source, request);
        operationToken.ThrowIfCancellationRequested();
        var updated = _store.Transaction(db =>
        {
            if (!db.Exists("SELECT 1 FROM forward_jobs WHERE id=$p0 AND request_json=$p1 AND state='waiting'", id, payload))
                return false;
            return RequestDownloads(db, request, source);
        }, notify: true);
        if (updated) _wakeDownloads();
        Wake();
        return updated;
    }, token);

    private sealed record SourceDownload(MessageActionSnapshot Row, string Manifest, bool PreviewOnly);

    private static IEnumerable<SourceDownload> MissingDownloads(IEnumerable<MessageActionSnapshot> source, bool omitPreview)
    {
        foreach (var row in source)
        {
            if (row.Saved || row.Available) continue;
            if (row.Manifest is { } attachment) yield return new SourceDownload(row, attachment, PreviewOnly: false);
            if (!omitPreview && !row.PreviewAvailable && row.PreviewManifest is { } preview)
                yield return new SourceDownload(row, preview, PreviewOnly: true);
        }
    }

    private static SourceDownload[] UnavailableDownloads(VaultTransaction db, IEnumerable<MessageActionSnapshot> source, bool omitPreview) =>
        MissingDownloads(source, omitPreview).Where(download =>
        {
            var owner = download.PreviewOnly
                ? "SELECT 1 FROM message_previews p WHERE p.owner_kind='message' AND p.owner_id=t.event_id AND p.image_object=t.object_id"
                : "SELECT 1 FROM message_attachments a WHERE a.message_id=t.event_id AND a.object_id=t.object_id";
            using var manifest = JsonDocument.Parse(download.Manifest);
            var objectId = manifest.RootElement.GetProperty("object_id").GetString();
            return db.Exists($"""
                SELECT 1 FROM object_transfers t JOIN media_objects o ON o.object_id=t.object_id
                WHERE t.owner_kind='message' AND t.direction=1 AND t.download_unavailable=1 AND o.local_state=0
                  AND t.event_id=$p0 AND t.owner_peer=$p1 AND t.object_id=$p2 AND EXISTS({owner})
                """, download.Row.Id, download.Row.Peer, objectId);
        }).ToArray();

    private static bool RequestDownloads(VaultTransaction db, ForwardQueueRequest request, IReadOnlyList<MessageActionSnapshot> source,
        IEnumerable<SourceDownload>? downloads = null)
    {
        RequireRecipients(db, request.Recipients);
        foreach (var row in source.Where(row => !row.Available)) MessageActionsStore.RequireCurrent(db, row);
        var changed = false;
        foreach (var download in downloads ?? MissingDownloads(source, request.OmitPreview))
        {
            if (AttachmentQueue.SetDownloadRequested(db, download.Row.Peer, download.Row.Id, requested: true,
                    previewOnly: download.PreviewOnly)) changed = true;
        }
        return changed;
    }

    internal static void RequireRecipients(VaultTransaction db,IReadOnlyList<string> recipients)
    {
        foreach(var peer in recipients)if(!db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1 AND blocked=0",peer))
            throw new InvalidOperationException("A selected recipient is no longer available.");
    }
    internal static void RequireSnapshot(IReadOnlyList<MessageActionSnapshot> source,ForwardQueueRequest request)
    {
        if(!source.Select(row=>row.Id).SequenceEqual(request.Ids,StringComparer.Ordinal) || !source.Select(Fingerprint).SequenceEqual(request.Fingerprints,StringComparer.Ordinal))
            throw new InvalidOperationException("A selected message changed after forwarding was queued.");
    }
    private static string Fingerprint(MessageActionSnapshot row)
    {
        using var hash=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach(var field in new string?[] {"msgat/forward-source/v1",row.Saved?"1":"0",row.Peer,row.Id,row.Kind,row.Text,row.Caption,row.Manifest,row.Timestamp.ToString(CultureInfo.InvariantCulture),row.ForwardAt.ToString(CultureInfo.InvariantCulture)})
        {
            var bytes=field is null?null:Encoding.UTF8.GetBytes(field);
            hash.AppendData(Encoding.ASCII.GetBytes((bytes?.Length??-1).ToString(CultureInfo.InvariantCulture)+":"));
            if(bytes is not null){hash.AppendData(bytes);CryptographicOperations.ZeroMemory(bytes);}
        }
        if(row.IsSticker)hash.AppendData("1:1"u8);
        if(row.PreviewJson is { } preview) {
            var bytes=Encoding.UTF8.GetBytes(preview);hash.AppendData(Encoding.ASCII.GetBytes(bytes.Length.ToString(CultureInfo.InvariantCulture)+":"));hash.AppendData(bytes);CryptographicOperations.ZeroMemory(bytes);
        }
        if (row.ContactAccount is { } account) hash.AppendData(Encoding.ASCII.GetBytes("contact:" + account));
        if (row.Location is { } point)
            hash.AppendData(Encoding.ASCII.GetBytes(FormattableString.Invariant($"location:{point.LatitudeE6},{point.LongitudeE6}")));
        return Convert.ToHexStringLower(hash.GetHashAndReset());
    }
    private static ForwardQueueRequest Decode(string payload)
    {
        try
        {
            var request=JsonSerializer.Deserialize<ForwardQueueRequest>(payload,EncodingOptions);
            if(request is null || request.Version is not (1 or 2 or 3) || request.OmitPreview != (request.Version == 3) || request.SendAt is <0 or >253402214399999L || request.Ids is not {Length:>=1 and <=100} || request.Recipients is not {Length:>=1 and <=20} ||
                request.Fingerprints is null || request.Fingerprints.Length!=request.Ids.Length || request.Ids.Distinct(StringComparer.Ordinal).Count()!=request.Ids.Length ||
                request.Recipients.Distinct(StringComparer.Ordinal).Count()!=request.Recipients.Length || request.Fingerprints.Any(value=>value is null || value.Length!=64))
                throw new InvalidDataException("Unsupported forwarding task.");
            return request;
        }
        catch(JsonException){throw new InvalidDataException("Unsupported forwarding task.");}
    }
    private void Fail(string id,string code)
    {
        _pendingMediaMessages.TryRemove(id, out _);
        _store.Transaction(db=>db.Execute("UPDATE forward_jobs SET state='failed',error_code=$p1 WHERE id=$p0",id,code),notify:true);
    }

    private async Task RunAsync()
    {
        try
        {
            await foreach(var _ in _wake.Reader.ReadAllAsync(_lifetime.Token).ConfigureAwait(false))
            {
                try
                {
                    // This worker is the only claimant. A previous abrupt stop may leave a preparing row.
                    if(_store.Transaction(db=>db.Exists("SELECT 1 FROM forward_jobs WHERE state='preparing'")))
                        _store.Transaction(db=>db.Execute("UPDATE forward_jobs SET state='waiting' WHERE state='preparing'"),notify:true);
                    var jobs=_store.Transaction(db=>
                    {
                        return db.Query("SELECT id,request_json,created_at FROM forward_jobs WHERE state='waiting' ORDER BY created_at,id LIMIT 20",r=>new Job(r.GetString(0),r.GetString(1),r.GetInt64(2)));
                    });
                    var waitingIds = jobs.Select(job => job.Id).ToHashSet(StringComparer.Ordinal);
                    foreach (var id in _pendingMediaMessages.Keys)
                        if (!waitingIds.Contains(id)) _pendingMediaMessages.TryRemove(id, out var staleCount);
                    foreach(var job in jobs)
                    {
                        _lifetime.Token.ThrowIfCancellationRequested();
                        if(Now-job.CreatedAt>7L*86400000){Fail(job.Id,"expired");continue;}
                        ForwardQueueRequest request;IReadOnlyList<MessageActionSnapshot> source;
                        try
                        {
                            request=Decode(job.Payload);source=_actions.Capture(request.SourcePeer,request.Ids,allowWaiting:true,includePreview:!request.OmitPreview);RequireSnapshot(source,request);
                        }
                        catch(Exception error) when(error is InvalidOperationException or InvalidDataException or ArgumentException)
                        {Fail(job.Id,"source_changed");continue;}
                        try{_store.Transaction(db=>{RequireRecipients(db,request.Recipients);return true;});}
                        catch(InvalidOperationException){Fail(job.Id,"recipient_unavailable");continue;}
                        var missing = source.Where(row => !row.Available).ToArray();
                        if (missing.Length > 0 && _store.Transaction(db =>
                            UnavailableDownloads(db, missing, request.OmitPreview).Length > 0 &&
                            db.Execute("""
                                UPDATE forward_jobs SET state='failed',error_code='source_download_unavailable'
                                WHERE id=$p0 AND state='waiting'
                                """, job.Id) > 0))
                        {
                            _pendingMediaMessages.TryRemove(job.Id, out _);
                            AvailabilityChanged?.Invoke(this, EventArgs.Empty);
                            continue;
                        }
                        try{_actions.RequireCopySpace(source,request.Recipients.Length,includePreview:!request.OmitPreview);}
                        catch(InsufficientObjectSpaceException){Fail(job.Id,"insufficient_space");continue;}
                        var pending = missing.Length == 0 ? null : new WaitingMedia(missing.Length, missing[0].Id);
                        _pendingMediaMessages.TryGetValue(job.Id, out var previousPending);
                        if (pending is null) _pendingMediaMessages.TryRemove(job.Id, out _);
                        else _pendingMediaMessages[job.Id] = pending;
                        if (pending != previousPending) AvailabilityChanged?.Invoke(this, EventArgs.Empty);
                        if (pending is not null) continue;
                        if(!_store.Transaction(db=>db.Execute("UPDATE forward_jobs SET state='preparing' WHERE id=$p0 AND state='waiting'",job.Id)>0,notify:true))continue;
                        using var stop=CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
                        using var preparation=new ObjectPreparation();
                        lock(_gate){_activeId=job.Id;_activeStop=stop;_activePreparation=preparation;}
                        try{if(!await _commit(job.Id,request,preparation,stop.Token).ConfigureAwait(false))Fail(job.Id,"preparation_failed");}
                        catch(OperationCanceledException)
                        {
                            if(_lifetime.IsCancellationRequested)_store.Transaction(db=>db.Execute("UPDATE forward_jobs SET state='waiting' WHERE id=$p0 AND state='preparing'",job.Id));
                            // Explicit cancellation deleted the row before requesting worker cancellation.
                        }
                        catch(InsufficientObjectSpaceException){Fail(job.Id,"insufficient_space");}
                        catch(Exception error) when(error is IOException or InvalidOperationException or CryptographicException or ArgumentException)
                        {Fail(job.Id,"preparation_failed");}
                        finally{lock(_gate){_activeId=null;_activeStop=null;_activePreparation=null;}}
                    }
                }
                catch(OperationCanceledException) when(_lifetime.IsCancellationRequested){break;}
                catch(Exception error){System.Diagnostics.Trace.TraceWarning("Forwarding tasks will retry ({0})",error.GetType().Name);}
            }
        }
        catch(OperationCanceledException) when(_lifetime.IsCancellationRequested){}
    }
    private async Task TickAsync()
    {
        using var timer=new PeriodicTimer(TimeSpan.FromSeconds(30));
        try{while(await timer.WaitForNextTickAsync(_lifetime.Token).ConfigureAwait(false))Wake();}
        catch(OperationCanceledException) when(_lifetime.IsCancellationRequested){}
    }
    public ValueTask DisposeAsync(){lock(_gate)return new(_dispose??=CloseAsync());}
    private async Task CloseAsync()
    {
        _closed=true;var pending=_requests.ToArray();_wake.Writer.TryComplete();
        await _lifetime.CancelAsync().ConfigureAwait(false);
        try{await Task.WhenAll(pending.Append(_worker).Append(_timer)).ConfigureAwait(false);}
        catch(Exception error){System.Diagnostics.Trace.TraceWarning("Forwarding queue stopped ({0})",error.GetType().Name);}
        finally{_lifetime.Dispose();}
    }
}
