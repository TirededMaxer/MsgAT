// SPDX-License-Identifier: AGPL-3.0-only
using System.Collections.Concurrent;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Core.Network;

public enum AttachmentPhase { Waiting, Uploading, Downloading, Complete, Retry, Unavailable }
public sealed record AttachmentProgress(string MessageId, AttachmentPhase Phase, long Bytes, long Total, string? PreviewReference = null);

/// <summary>Durable bulk jobs run outside the Signal actor and its TLS connection.</summary>
internal sealed class AttachmentQueue : IAsyncDisposable
{
    private sealed class TransferPausedException:Exception {}
    private sealed record Job(string MessageId,string ObjectId,JsonObject Manifest,int Direction,int Attempts,bool Preview);
    private sealed class ProgressSink(Action<long> changed) : IProgress<long> { public void Report(long value)=>changed(value); }
    private readonly SignalProtocolStore _store;
    private readonly EncryptedObjectStore _objects;
    private readonly RelayClient _relay;
    private readonly AttachmentTransport _transport;
    private readonly Func<Task> _reconsider;
    private readonly string _instance=Guid.NewGuid().ToString("N");
    private readonly ConcurrentDictionary<string,AttachmentProgress> _progress=new(StringComparer.Ordinal);
    private static Channel<bool> WakeChannel()=>Channel.CreateBounded<bool>(new BoundedChannelOptions(1) { SingleReader=true,FullMode=BoundedChannelFullMode.DropWrite });
    private readonly Channel<bool>[] _wake=[WakeChannel(),WakeChannel()];
    private readonly CancellationTokenSource _lifetime=new();
    private readonly Task _worker;
    private readonly Task _retry;
    private int _disposed;
    private string Session=>$"{_instance}:{_relay.ConnectionEpoch}";
    private static long Now=>DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    private const long LeaseMargin=7L*86400000+3600000;
    internal string LeaseSession=>Session;
    internal static long MinimumLease=>Now+LeaseMargin;

    internal AttachmentQueue(SignalProtocolStore store,EncryptedObjectStore objects,RelayClient relay,SecureSettingsStore settings,Func<Task> reconsider)
    {
        _store=store; _objects=objects; _relay=relay; _transport=new AttachmentTransport(settings); _reconsider=reconsider;
        _relay.StateChanged+=StateChanged;
        _worker=Task.WhenAll(Task.Run(()=>RunAsync(0)),Task.Run(()=>RunAsync(1))); _retry=Task.Run(RetryAsync);
    }
    internal event EventHandler<AttachmentProgress>? ProgressChanged;

    internal static void Record(VaultTransaction db,string eventId,string objectId,bool outgoing,string peer,long created,string ownerKind="message") =>
        db.Execute("INSERT INTO object_transfers(event_id,object_id,direction,owner_peer,owner_kind,created_at,lease_session,remote_expiry) VALUES($p0,$p1,$p2,$p3,$p4,$p5,COALESCE((SELECT lease_session FROM object_leases WHERE object_id=$p1),''),COALESCE((SELECT remote_expiry FROM object_leases WHERE object_id=$p1),0)) ON CONFLICT(event_id,object_id,direction) DO NOTHING",eventId,objectId,outgoing?0:1,peer,ownerKind,created);

    internal AttachmentProgress? GetProgress(string messageId) => _progress.TryGetValue(messageId,out var progress)?progress:null;
    internal void Wake() { if(Volatile.Read(ref _disposed)==0) foreach(var wake in _wake) wake.Writer.TryWrite(true); }
    private void StateChanged(object? sender,SyncState state) { if(state==SyncState.Synced) Wake(); }

    private async Task RunAsync(int direction)
    {
        while(!_lifetime.IsCancellationRequested)
        {
        try
        {
            await foreach(var _ in _wake[direction].Reader.ReadAllAsync(_lifetime.Token).ConfigureAwait(false))
            {
                if(_relay.State!=SyncState.Synced || _relay.ConnectedSettings is not { } endpoint) continue;
                var session=Session;
                var jobs=_store.Transaction(db=>db.Query($"""
                    SELECT t.event_id,t.object_id,o.manifest_json,t.direction,t.attempts,
                       EXISTS(SELECT 1 FROM message_previews p
                         WHERE p.owner_kind='message' AND p.owner_id=t.event_id AND p.image_object=t.object_id)
                    FROM object_transfers t JOIN media_objects o ON o.object_id=t.object_id
                    LEFT JOIN messages m ON m.messageId=t.event_id AND t.owner_kind='message'
                    WHERE t.next_retry<=$p0 AND t.direction=$p3
                      AND {DownloadEligible}
                      AND {EncryptedObjectStore.AutomaticDownloadBudget}
                      AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=t.event_id AND e.expires_at<=$p0)
                      AND ((t.owner_kind='message' AND m.isRevoked=0) OR t.owner_kind='profile')
                      AND EXISTS (SELECT 1 FROM contact_states c WHERE c.uuid=t.owner_peer AND c.blocked=0 AND (c.accepted=1 OR (t.owner_kind='profile' AND (c.incoming=1 OR c.outgoing=1))))
                      AND ((t.direction=0 AND (t.lease_session!=$p1 OR t.remote_expiry<=$p2)
                        AND (EXISTS(SELECT 1 FROM protocol_pending_events p WHERE p.event_id=t.event_id)
                          OR EXISTS(SELECT 1 FROM protocol_outbox b WHERE b.event_id=t.event_id AND b.delivered=0)))
                        OR (t.direction=1 AND o.local_state=0))
                    ORDER BY t.download_requested DESC,t.created_at,t.event_id,t.object_id LIMIT 8
                    """,r=>new Job(r.GetString(0),r.GetString(1),JsonNode.Parse(r.GetString(2))!.AsObject(),r.GetInt32(3),r.GetInt32(4),r.GetInt32(5)!=0),Now,session,Now+LeaseMargin,direction));
                foreach(var job in jobs)
                {
                    if(_lifetime.IsCancellationRequested || _relay.State!=SyncState.Synced || Session!=session) break;
                    if(direction==0 && _store.Transaction(db=>db.Exists("SELECT 1 FROM object_transfers WHERE event_id=$p0 AND object_id=$p1 AND direction=0 AND lease_session=$p2 AND remote_expiry>$p3",job.MessageId,job.ObjectId,session,MinimumLease)))continue;
                    if(direction==1 && _store.Transaction(db=>db.Exists("SELECT 1 FROM media_objects WHERE object_id=$p0 AND local_state=1",job.ObjectId)))
                    {
                        _store.Transaction(db=>db.Execute("DELETE FROM object_transfers WHERE event_id=$p0 AND object_id=$p1 AND direction=1",job.MessageId,job.ObjectId));
                        continue;
                    }
                    using var pin = _objects.PinTransfer(job.ObjectId, direction, db => Allowed(db, job, checkBudget: true));
                    if(pin is null)continue;
                    var total=job.Manifest["size"]!.GetValue<long>();
                    var phase=job.Direction==0?AttachmentPhase.Uploading:AttachmentPhase.Downloading;
                    var last=-1L; var lastUpdate=Environment.TickCount64;
                    var progress=new ProgressSink(bytes=>
                    {
                        var percent=total==0?100:bytes*100/total;
                        if(percent!=last && (Environment.TickCount64-lastUpdate>=100 || bytes==total))
                        { last=percent; lastUpdate=Environment.TickCount64; Publish(job, new(job.MessageId,phase,bytes,total)); }
                    });
                    Publish(job, new(job.MessageId,phase,0,total));
                    void Checkpoint(){_lifetime.Token.ThrowIfCancellationRequested();if(_relay.State!=SyncState.Synced || Session!=session || !_store.Transaction(db=>Allowed(db,job)))throw new TransferPausedException();}
                    try
                    {
                        if(job.Direction==0)
                        {
                            var expiry=await _transport.UploadAsync(job.Manifest,_objects.PathFor(job.ObjectId),endpoint,progress,_lifetime.Token,Checkpoint).ConfigureAwait(false);
                            if(checked(expiry*1000)<=MinimumLease) throw new InvalidDataException("Attachment lease is too short or the local clock is incorrect.");
                            _store.Transaction(db=>
                            {
                                if(!Allowed(db,job))throw new TransferPausedException();
                                db.Execute("INSERT INTO object_leases(object_id,lease_session,remote_expiry) SELECT $p0,$p1,$p2 WHERE EXISTS(SELECT 1 FROM media_objects WHERE object_id=$p0) ON CONFLICT(object_id) DO UPDATE SET lease_session=excluded.lease_session,remote_expiry=excluded.remote_expiry",job.ObjectId,session,checked(expiry*1000));
                                return db.Execute("UPDATE object_transfers SET lease_session=$p1,remote_expiry=$p2,attempts=0,next_retry=0 WHERE object_id=$p0 AND direction=0",job.ObjectId,session,checked(expiry*1000));
                            });
                        }
                        else
                        {
                            _objects.RequireDownloadSpace(job.Manifest);
                            await _transport.DownloadAsync(job.Manifest,_objects.PathFor(job.ObjectId),endpoint,progress,_lifetime.Token,Checkpoint).ConfigureAwait(false);
                            _store.Transaction(db=>
                            {
                                if(!Allowed(db,job))throw new TransferPausedException();
                                db.Execute("UPDATE media_objects SET local_state=1 WHERE object_id=$p0",job.ObjectId);
                                db.Execute("DELETE FROM media_cache_evictions WHERE object_id=$p0", job.ObjectId);
                                return db.Execute("DELETE FROM object_transfers WHERE event_id=$p0 AND object_id=$p1 AND direction=1",job.MessageId,job.ObjectId);
                            },notify:true);
                        }
                        Publish(job, new(job.MessageId,AttachmentPhase.Complete,total,total));
                    }
                    catch(OperationCanceledException) when(_lifetime.IsCancellationRequested) { break; }
                    catch(TransferPausedException){Publish(job, new(job.MessageId,AttachmentPhase.Waiting,0,total));}
                    catch(AttachmentUnavailableException)
                    {
                        var changed = _store.Transaction(db => db.Execute("""
                            UPDATE object_transfers SET download_unavailable=1,download_requested=0
                            WHERE event_id=$p0 AND object_id=$p1 AND direction=1 AND download_paused=0
                            """, job.MessageId, job.ObjectId), notify:true) > 0;
                        Publish(job, new(job.MessageId,changed ? AttachmentPhase.Unavailable : AttachmentPhase.Waiting,0,total));
                    }
                    catch(Exception error)
                    {
                        var delay=Math.Min(300000L,30000L*(1L<<Math.Min(job.Attempts,4)))+Random.Shared.Next(0,3000);
                        var retrying = _store.Transaction(db => db.Execute("UPDATE object_transfers SET attempts=MIN(attempts+1,16),next_retry=$p1 WHERE event_id=$p0 AND object_id=$p2 AND direction=$p3 AND download_paused=0", job.MessageId, Now+delay, job.ObjectId, direction)) > 0;
                        if (retrying) System.Diagnostics.Trace.TraceWarning("Attachment transfer will retry ({0})", error.GetType().Name);
                        Publish(job, new(job.MessageId, retrying ? AttachmentPhase.Retry : AttachmentPhase.Waiting, 0, total));
                    }
                    // Observer/actor saturation cannot undo a successfully committed transfer.
                    try { await _reconsider().ConfigureAwait(false); }
                    catch(Exception error) { System.Diagnostics.Trace.TraceWarning("Attachment message scheduling will retry ({0})",error.GetType().Name); }
                }
                if(jobs.Count==8) _wake[direction].Writer.TryWrite(true);
            }
            return;
        }
        catch(OperationCanceledException) when(_lifetime.IsCancellationRequested) { return; }
        catch(Exception error)
        {
            System.Diagnostics.Trace.TraceWarning("Attachment queue will retry ({0})",error.GetType().Name);
            try { await Task.Delay(TimeSpan.FromSeconds(30),_lifetime.Token).ConfigureAwait(false); }
            catch(OperationCanceledException) when(_lifetime.IsCancellationRequested) { return; }
            Wake();
        }
        }
    }

    private const string DownloadEligible = """
        (t.direction=0 OR (t.download_paused=0 AND t.download_unavailable=0))
        AND (t.direction=0 OR NOT EXISTS(
            SELECT 1 FROM media_cache_evictions e WHERE e.object_id=t.object_id AND e.file_pending=1))
        AND (t.direction=0 OR t.download_requested=1 OR (NOT EXISTS(
            SELECT 1 FROM media_cache_evictions e WHERE e.object_id=t.object_id) AND (
            (SELECT auto_download_attachments FROM privacy_preferences WHERE id=1)=1 OR NOT EXISTS(
            SELECT 1 FROM message_attachments a WHERE a.message_id=t.event_id AND a.object_id=t.object_id))))
        """;

    internal static bool AutoDownloadEnabled(VaultTransaction db) =>
        db.Exists("SELECT 1 FROM privacy_preferences WHERE id=1 AND auto_download_attachments=1");

    internal static bool SetDownloadRequested(VaultTransaction db, string peer, string messageId, bool requested,
        bool previewOnly = false) => db.Execute("""
        UPDATE object_transfers SET download_requested=$p3,download_paused=$p4,
            download_unavailable=CASE WHEN $p3=1 THEN 0 ELSE download_unavailable END,next_retry=0,attempts=0
        WHERE event_id=$p0 AND owner_peer=$p1 AND owner_kind='message' AND direction=1
          AND EXISTS(SELECT 1 FROM media_objects o WHERE o.object_id=object_transfers.object_id AND o.local_state=0)
          AND EXISTS(SELECT 1 FROM messages m
            JOIN contact_states c ON c.uuid=m.conversationUuid
            WHERE m.messageId=object_transfers.event_id AND m.conversationUuid=object_transfers.owner_peer
              AND m.isMine=0 AND m.isRevoked=0 AND c.accepted=1 AND c.blocked=0
              AND (($p5=0 AND EXISTS(SELECT 1 FROM message_attachments a WHERE a.message_id=m.messageId AND a.object_id=object_transfers.object_id))
                OR ($p5=1 AND EXISTS(SELECT 1 FROM message_previews p WHERE p.owner_kind='message'
                    AND p.owner_id=m.messageId AND p.image_object=object_transfers.object_id))))
          AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=object_transfers.event_id AND e.expires_at<=$p2)
        """, messageId, peer, Now, requested ? 1 : 0, requested ? 0 : 1, previewOnly ? 1 : 0) > 0;

    private static bool Allowed(VaultTransaction db,Job job,bool checkBudget=false)=>db.Exists($"""
        SELECT 1 FROM object_transfers t JOIN contact_states c ON c.uuid=t.owner_peer LEFT JOIN messages m ON m.messageId=t.event_id AND t.owner_kind='message'
        JOIN media_objects o ON o.object_id=t.object_id
        WHERE t.event_id=$p0 AND t.object_id=$p1 AND t.direction=$p2 AND c.blocked=0
        AND (c.accepted=1 OR (t.owner_kind='profile' AND (c.incoming=1 OR c.outgoing=1)))
        AND (t.owner_kind='profile' OR m.isRevoked=0)
        AND (t.direction=0 OR o.local_state=0)
        AND {DownloadEligible}
        {(checkBudget ? $"AND {EncryptedObjectStore.AutomaticDownloadBudget}" : "")}
        AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=t.event_id AND e.expires_at<=$p3)
        AND (t.direction=1 OR EXISTS(SELECT 1 FROM protocol_pending_events p WHERE p.event_id=t.event_id) OR EXISTS(SELECT 1 FROM protocol_outbox b WHERE b.event_id=t.event_id AND b.delivered=0))
        """,job.MessageId,job.ObjectId,job.Direction,Now);

    private void Publish(Job job, AttachmentProgress value)
    {
        if (job.Preview) value = value with { PreviewReference = EncryptedObjectStore.Reference(job.ObjectId) };
        if(_progress.Count>=256)
            foreach(var entry in _progress.Where(p=>p.Value.Phase is AttachmentPhase.Complete or AttachmentPhase.Retry or AttachmentPhase.Waiting or AttachmentPhase.Unavailable).Take(128)) _progress.TryRemove(entry.Key,out _);
        if (!job.Preview) _progress[value.MessageId]=value;
        try { ProgressChanged?.Invoke(this,value); }
        catch(Exception error) { System.Diagnostics.Trace.TraceWarning("Attachment observer failed ({0})",error.GetType().Name); }
    }
    private async Task RetryAsync()
    {
        using var timer=new PeriodicTimer(TimeSpan.FromSeconds(30));
        try { while(await timer.WaitForNextTickAsync(_lifetime.Token).ConfigureAwait(false)) Wake(); }
        catch(OperationCanceledException) when(_lifetime.IsCancellationRequested) { }
    }
    public async ValueTask DisposeAsync()
    {
        if(Interlocked.Exchange(ref _disposed,1)!=0) return;
        _relay.StateChanged-=StateChanged; foreach(var wake in _wake) wake.Writer.TryComplete();
        await _lifetime.CancelAsync().ConfigureAwait(false);
        await Task.WhenAll(_worker,_retry).ConfigureAwait(false);
        _lifetime.Dispose();
    }
}
