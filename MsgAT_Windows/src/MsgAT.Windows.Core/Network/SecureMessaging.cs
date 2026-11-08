// SPDX-License-Identifier: AGPL-3.0-only
using System.Text;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Protocol;
using MsgAT.Windows.Core.Storage;
using static MsgAT.Windows.Core.Crypto.SignalProtocolStore;

namespace MsgAT.Windows.Core.Network;

/// <summary>One bounded actor owns all V2 state transitions and their commit order.</summary>
internal sealed class SecureMessaging : IAsyncDisposable
{
    private sealed class VisibleFailure(string message) : InvalidOperationException(message) { }
    private sealed record Work(Func<CancellationToken,Task> Run,TaskCompletionSource<bool> Result,bool NotifyFailure,CancellationToken Token);
    private readonly RelayClient _relay;
    private readonly SecureSettingsStore _settings;
    private readonly SignalProtocolStore _store;
    private readonly CancellationTokenSource _lifetime=new();
    private readonly Channel<Work> _commands=Channel.CreateBounded<Work>(new BoundedChannelOptions(64)
    { SingleReader=true,SingleWriter=false,FullMode=BoundedChannelFullMode.Wait });
    private readonly Task _worker;
    private readonly Task _maintenance;
    private readonly HashSet<string> _inFlight=new(StringComparer.Ordinal);
    private readonly Dictionary<string,(string Id,long Time)> _requested=new(StringComparer.Ordinal);
    private readonly Dictionary<string,long> _unavailableUntil=new(StringComparer.Ordinal);
    private long _epoch=-1;
    private long _nextMaintenance;
    private string? _publishedBody;
    private volatile bool _ready;
    private int _disposed;

    public SecureMessaging(RelayClient relay,SecureSettingsStore settings,EncryptedDatabase database)
    {
        _relay=relay; _settings=settings; _store=new SignalProtocolStore(database,settings);
        _worker=Task.Run(RunAsync);
        _maintenance=Task.Run(MaintainAsync);
        _relay.PacketReceived+=HandleAsync;
        _relay.StateChanged+=StateChanged;
    }
    public event EventHandler<NotificationRequest>? NotificationRequested;
    public event EventHandler<string>? OperationFailed;

    public Task<bool> SendTextAsync(string recipient,string text,string? replyId,CancellationToken token=default)
    {
        if (string.IsNullOrWhiteSpace(text) || Encoding.UTF8.GetByteCount(text)>48*1024)
            throw new InvalidOperationException("Message text is empty or too large.");
        return UserAsync(() =>
        {
            _store.PublicBundle();
            var id=Guid.NewGuid().ToString("D"); var created=Now;
            var body=new JsonObject { ["text"]=text };
            _store.Transaction(db =>
            {
                RequireAccepted(db,recipient);
                if (replyId is not null)
                {
                    var quote=db.Query("SELECT isMine,content,ext FROM messages WHERE messageId=$p0 AND conversationUuid=$p1",
                        r => (Mine:r.GetInt64(0)!=0,Content:r.GetString(1),Extension:r.GetString(2)),replyId,recipient);
                    if (quote.Count==0) throw new VisibleFailure("The quoted message is unavailable.");
                    var original=quote[0];
                    var preview=original.Extension=="txt" ? original.Content : "["+original.Extension+"]";
                    body["reply"]=new JsonObject { ["event_id"]=replyId,["author"]=original.Mine ? _settings.MyUuid : recipient,["preview"]=Preview(preview) };
                }
                if (Enqueue(new Pending(id,recipient,"chat.text",body,created),db)) InsertText(db,id,recipient,true,text,created,body["reply"] as JsonObject);
                return true;
            },notify:true);
        },token);
    }

    public Task<bool> RequestContactAsync(string recipient,CancellationToken token=default) => UserAsync(() =>
    {
        if (!IsAccountId(recipient) || recipient==_settings.MyUuid) throw new VisibleFailure("Invalid contact identity.");
        _store.PublicBundle();
        _store.Transaction(db =>
        {
            if (Blocked(db,recipient)) throw new VisibleFailure("This contact is blocked.");
            if (db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND (accepted=1 OR outgoing=1)",recipient)) return false;
            if (Enqueue(PendingEvent(recipient,"contact.request",Profile()),db))
            {
                db.Execute("INSERT INTO contact_states(uuid,outgoing) VALUES($p0,1) ON CONFLICT(uuid) DO UPDATE SET outgoing=1",recipient);
                db.Execute("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES($p0,'Pending request','','') ON CONFLICT(uuid) DO NOTHING",recipient);
            }
            return true;
        },notify:true);
    },token);

    public Task<bool> AcceptContactAsync(string recipient,CancellationToken token=default) => UserAsync(() =>
    {
        _store.Transaction(db =>
        {
            if (!db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND incoming=1 AND blocked=0",recipient)) throw new VisibleFailure("No contact request awaits acceptance.");
            var profile=db.Query("SELECT nickname,avatarPath FROM friend_requests WHERE senderUuid=$p0",r => (Name:r.GetString(0),Avatar:r.GetString(1)),recipient);
            if (profile.Count==0) throw new VisibleFailure("The contact request is unavailable.");
            if (Enqueue(PendingEvent(recipient,"contact.accept",Profile()),db))
            {
                db.Execute("UPDATE contact_states SET incoming=0,accepted=1 WHERE uuid=$p0",recipient);
                db.Execute("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES($p0,$p1,$p2,'') ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias,avatarPath=excluded.avatarPath",recipient,profile[0].Name,profile[0].Avatar);
                db.Execute("DELETE FROM friend_requests WHERE senderUuid=$p0",recipient);
            }
            return true;
        },notify:true);
    },token);

    public Task<bool> DeclineContactAsync(string recipient,CancellationToken token=default) => UserAsync(() =>
        _store.Transaction(db =>
        {
            db.Execute("DELETE FROM friend_requests WHERE senderUuid=$p0",recipient);
            return db.Execute("UPDATE contact_states SET incoming=0 WHERE uuid=$p0",recipient);
        },notify:true),token);

    public Task<bool> DeleteContactAsync(string recipient,CancellationToken token=default) => UserAsync(() =>
        _store.Transaction(db =>
        {
            if (!IsAccountId(recipient)) throw new VisibleFailure("Invalid contact identity.");
            Enqueue(PendingEvent(recipient,"contact.delete",new JsonObject()),db);
            db.Execute("UPDATE contact_states SET accepted=0,outgoing=0,incoming=0 WHERE uuid=$p0",recipient);
            db.Execute("DELETE FROM conversations WHERE uuid=$p0",recipient);
            db.Execute("DELETE FROM messages WHERE conversationUuid=$p0",recipient);
            db.Execute("DELETE FROM friend_requests WHERE senderUuid=$p0",recipient);
            db.Execute("DELETE FROM protocol_pending_events WHERE recipient=$p0 AND kind LIKE 'chat.%'",recipient);
            return db.Execute("DELETE FROM protocol_outbox WHERE recipient=$p0 AND delivered=0",recipient);
        },notify:true),token);

    public Task<bool> MarkReadAsync(string recipient,CancellationToken token=default) => UserAsync(() =>
    {
        var changed=_store.Transaction(db =>
        {
            var id=db.Text("SELECT messageId FROM messages WHERE conversationUuid=$p0 AND isMine=0 AND isRead=0 ORDER BY timestamp DESC,messageId DESC LIMIT 1",recipient);
            if (id is not null)
            {
                db.Execute("UPDATE messages SET isRead=1 WHERE conversationUuid=$p0 AND isMine=0",recipient);
                Receipt(db,recipient,id,"read");
            }
            return id is not null;
        });
        if (changed) _store.NotifyBusinessChanges();
    },token);

    public Task<bool> BroadcastProfileAsync(CancellationToken token=default) => UserAsync(() =>
        _store.Transaction(db =>
        {
            foreach (var peer in db.Query("SELECT uuid FROM contact_states WHERE accepted=1 AND blocked=0",r => r.GetString(0)))
                Enqueue(PendingEvent(peer,"contact.profile",Profile()),db);
            return true;
        }),token);

    private Task<bool> UserAsync(Action operation,CancellationToken token) => Submit(async _ =>
    {
        operation(); // A thrown business/crypto error rolls its transaction back.
        try { await FlushAsync(_lifetime.Token).ConfigureAwait(false); }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
        catch (Exception error) { Report(error); } // Already committed: preserve success and retry later.
    },notifyFailure:false,token:token);

    private async Task HandleAsync(GhostPacket packet,CancellationToken token)
    {
        var connection=_relay.ConnectionEpoch;
        await Submit(async ct =>
        {
            if (connection!=_relay.ConnectionEpoch) return;
            if (packet.Type==GhostPacket.OfflineEnd) { await BeginSyncAsync(connection,ct).ConfigureAwait(false); return; }
            if (connection!=_epoch) return;
            switch (packet.Type)
            {
                case GhostPacket.V2KeyPublished:
                    if (packet.EncryptedData.Length>=1024 || JsonNode.Parse(packet.EncryptedData)?["published"]?.GetValue<bool>()!=true)
                        throw new InvalidDataException("Invalid key publication acknowledgement.");
                    _ready=true;
                    await PullAsync(ct).ConfigureAwait(false);
                    await FlushAsync(ct).ConfigureAwait(false);
                    break;
                case GhostPacket.V2KeyResponse: await KeyResponseAsync(packet,ct).ConfigureAwait(false); break;
                case GhostPacket.V2Stored:
                    var recipient=packet.TargetMailboxId ?? throw new InvalidDataException("Missing acknowledgement target.");
                    var id=EventId(packet.NonceOrIv);
                    _store.Transaction(db =>
                    {
                        db.Execute("UPDATE protocol_outbox SET delivered=1 WHERE event_id=$p0 AND recipient=$p1",id,recipient);
                        return db.Execute("UPDATE messages SET status=MAX(status,1) WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=1",id,recipient);
                    },notify:true);
                    _inFlight.Remove(id);
                    await FlushAsync(ct).ConfigureAwait(false);
                    break;
                case GhostPacket.V2Delivery:
                    var sender=packet.TargetMailboxId ?? throw new InvalidDataException("Missing authenticated sender.");
                    var eventId=EventId(packet.NonceOrIv);
                    var notify=false;
                    var fresh=_store.Receive(packet.EncryptedData,sender,eventId,(incoming,db) =>
                    {
                        Apply(incoming,db);
                        notify=incoming.Kind=="chat.text" && db.Exists("SELECT 1 FROM messages WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=0 AND isRead=0",eventId,sender);
                    });
                    await SendAsync(GhostPacket.V2Received,sender,EventBytes(eventId),[],ct).ConfigureAwait(false);
                    if (fresh && notify) PublishNotification(sender);
                    await FlushAsync(ct).ConfigureAwait(false);
                    break;
                case GhostPacket.V2SyncEnd:
                    _relay.CompleteSync(connection);
                    await FlushAsync(ct).ConfigureAwait(false);
                    break;
            }
        },token:token).ConfigureAwait(false);
    }

    private async Task BeginSyncAsync(long connection,CancellationToken token)
    {
        if (connection<_epoch) return;
        _epoch=connection; _ready=false; _inFlight.Clear(); _requested.Clear();
        var bundle=_store.PublicBundle();
        _publishedBody=Text(bundle,"body"); _nextMaintenance=Now+3600000;
        await SendAsync(GhostPacket.V2KeyPublish,_settings.MyMailboxId,new byte[16],Json(bundle),token).ConfigureAwait(false);
    }

    private void Apply(Incoming incoming,VaultTransaction db)
    {
        var sender=incoming.Sender;
        switch (incoming.Kind)
        {
            case "contact.request":
                var name=Nickname(incoming.Body);
                if (Blocked(db,sender) || db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1",sender)) return;
                db.Execute("INSERT INTO contact_states(uuid,incoming) VALUES($p0,1) ON CONFLICT(uuid) DO UPDATE SET incoming=1",sender);
                db.Execute("INSERT INTO friend_requests(senderUuid,nickname,avatarPath,peerPublicKey,timestamp) VALUES($p0,$p1,'','',$p2) ON CONFLICT(senderUuid) DO UPDATE SET nickname=excluded.nickname,timestamp=excluded.timestamp",sender,name,incoming.CreatedAt);
                break;
            case "contact.accept":
                var acceptedName=Nickname(incoming.Body);
                if (!db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND blocked=0 AND (outgoing=1 OR accepted=1)",sender)) return;
                db.Execute("UPDATE contact_states SET outgoing=0,accepted=1 WHERE uuid=$p0",sender);
                db.Execute("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES($p0,$p1,'','') ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias",sender,acceptedName);
                db.Execute("DELETE FROM friend_requests WHERE senderUuid=$p0",sender);
                break;
            case "contact.profile":
                db.Execute("UPDATE conversations SET alias=$p0 WHERE uuid=$p1 AND EXISTS (SELECT 1 FROM contact_states WHERE uuid=$p1 AND accepted=1 AND blocked=0)",Nickname(incoming.Body),sender);
                break;
            case "contact.delete":
                db.Execute("UPDATE contact_states SET accepted=0,outgoing=0,incoming=0 WHERE uuid=$p0",sender);
                db.Execute("DELETE FROM friend_requests WHERE senderUuid=$p0",sender);
                break;
            case "chat.text":
                var text=Text(incoming.Body,"text");
                if (text.Length==0 || Encoding.UTF8.GetByteCount(text)>48*1024) throw new InvalidDataException("Invalid text message size.");
                if (Blocked(db,sender) || !db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1",sender)) return;
                var reply=incoming.Body["reply"] as JsonObject;
                if (reply is not null)
                {
                    Validate(Text(reply,"event_id"),"reply",0);
                    if (Text(reply,"author")!=sender && Text(reply,"author")!=_settings.MyUuid) throw new InvalidDataException("Invalid quote author.");
                    if (Encoding.UTF8.GetByteCount(Text(reply,"preview"))>4096) throw new InvalidDataException("Quote preview is too large.");
                }
                InsertText(db,incoming.EventId,sender,false,text,incoming.CreatedAt,reply);
                Receipt(db,sender,incoming.EventId,"delivered");
                break;
            case "chat.receipt":
                var messageId=Text(incoming.Body,"message_id"); var state=Text(incoming.Body,"state");
                if (state=="delivered") db.Execute("UPDATE messages SET status=MAX(status,2) WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=1",messageId,sender);
                else if (state=="read") db.Execute("UPDATE messages SET status=MAX(status,3) WHERE conversationUuid=$p1 AND isMine=1 AND timestamp<=(SELECT timestamp FROM messages WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=1)",messageId,sender);
                else throw new InvalidDataException("Invalid encrypted receipt state.");
                break;
            default: throw new VisibleFailure("An encrypted event requires a newer MsgAT implementation.");
        }
    }

    private async Task FlushAsync(CancellationToken token)
    {
        if (!_ready || _epoch!=_relay.ConnectionEpoch) return;
        foreach (var pending in _store.PendingEvents())
        {
            if (_store.HasSession(pending.Recipient)) _store.Prepare(pending);
            else if (!_requested.ContainsKey(pending.Recipient) && _unavailableUntil.GetValueOrDefault(pending.Recipient)<Now)
            {
                var id=_store.KeyRequestId(pending.Recipient);
                await SendAsync(GhostPacket.V2KeyClaim,_settings.MyMailboxId,new byte[16],Json(new JsonObject { ["request_id"]=id,["uuid"]=pending.Recipient }),token).ConfigureAwait(false);
                _requested[pending.Recipient]=(id,Now);
            }
        }
        foreach (var pending in _store.PendingOutgoing(8))
        {
            if (_inFlight.Contains(pending.EventId)) continue;
            await SendAsync(GhostPacket.V2Submit,pending.Recipient,EventBytes(pending.EventId),pending.Wire,token).ConfigureAwait(false);
            _inFlight.Add(pending.EventId);
        }
    }

    private async Task KeyResponseAsync(GhostPacket packet,CancellationToken token)
    {
        if (packet.EncryptedData.Length>192*1024) throw new InvalidDataException("Directory response exceeds its limit.");
        var response=JsonNode.Parse(packet.EncryptedData)?.AsObject() ?? throw new InvalidDataException("Missing directory response.");
        var recipient=Text(response,"uuid"); var id=Text(response,"request_id");
        if (!_requested.TryGetValue(recipient,out var request) || request.Id!=id) return;
        _requested.Remove(recipient);
        if (response["found"]?.GetValue<bool>()!=true)
        {
            _unavailableUntil[recipient]=Now+300000;
            _store.FinishKeyRequest(recipient,id);
            throw new VisibleFailure("The recipient has no available key bundle. Your message remains protected in the local vault.");
        }
        var bundle=response["bundle"]!.AsObject();
        Call(new JsonObject { ["operation"]="verify_bundle",["bundle"]=bundle.DeepClone(),["expected_uuid"]=recipient });
        var selected=response["selected_prekey"]?.GetValue<long>();
        foreach (var pending in _store.PendingEvents(256).Where(e => e.Recipient==recipient)) _store.Prepare(pending,bundle,selected);
        _store.FinishKeyRequest(recipient,id);
        await FlushAsync(token).ConfigureAwait(false);
    }

    private async Task MaintainAsync()
    {
        using var timer=new PeriodicTimer(TimeSpan.FromSeconds(30));
        try
        {
            while (await timer.WaitForNextTickAsync(_lifetime.Token).ConfigureAwait(false))
            {
                try { await Submit(async token =>
                {
                    if (!_ready || _epoch!=_relay.ConnectionEpoch) return;
                    _inFlight.Clear();
                    foreach (var key in _requested.Where(p => Now-p.Value.Time>=30000).Select(p => p.Key).ToArray()) _requested.Remove(key);
                    if (Now>=_nextMaintenance)
                    {
                        var bundle=_store.PublicBundle(); _nextMaintenance=Now+3600000;
                        if (_publishedBody!=Text(bundle,"body"))
                        {
                            await SendAsync(GhostPacket.V2KeyPublish,_settings.MyMailboxId,new byte[16],Json(bundle),token).ConfigureAwait(false);
                            _publishedBody=Text(bundle,"body");
                        }
                    }
                    await FlushAsync(token).ConfigureAwait(false);
                    await PullAsync(token).ConfigureAwait(false);
                },token:_lifetime.Token).ConfigureAwait(false); }
                catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { break; }
                catch (Exception) { _relay.AbortConnection(_epoch); }
            }
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
    }

    private Task<bool> Submit(Func<CancellationToken,Task> operation,bool notifyFailure=true,CancellationToken token=default)
    {
        if (Volatile.Read(ref _disposed)!=0) return Task.FromException<bool>(new ObjectDisposedException(nameof(SecureMessaging)));
        var result=new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!_commands.Writer.TryWrite(new Work(operation,result,notifyFailure,token)))
        {
            _relay.AbortConnection(_relay.ConnectionEpoch);
            result.TrySetException(new InvalidOperationException("The secure-operation queue is busy. Try again shortly."));
        }
        return result.Task;
    }
    private async Task RunAsync()
    {
        try
        {
            await foreach (var work in _commands.Reader.ReadAllAsync(_lifetime.Token).ConfigureAwait(false))
            {
                if (_lifetime.IsCancellationRequested) { work.Result.TrySetCanceled(); break; }
                try
                {
                    work.Token.ThrowIfCancellationRequested();
                    await work.Run(work.Token).ConfigureAwait(false);
                    work.Result.TrySetResult(true);
                }
                catch (OperationCanceledException) { work.Result.TrySetCanceled(); }
                catch (Exception error)
                {
                    Report(error,work.NotifyFailure);
                    work.Result.TrySetException(new InvalidOperationException(error is VisibleFailure ? error.Message : "Secure operation failed; local state was retained."));
                }
            }
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
        finally { while (_commands.Reader.TryRead(out var pending)) pending.Result.TrySetCanceled(); }
    }
    private void StateChanged(object? sender,SyncState state)
    {
        if (state!=SyncState.Synced) _ready=false;
    }
    private async Task SendAsync(byte type,string target,byte[] nonce,byte[] data,CancellationToken token)
    {
        if (!await _relay.SendForEpochAsync(new GhostPacket(type,target,nonce,data,new byte[16]),_epoch,token).ConfigureAwait(false))
        { _ready=false; _relay.AbortConnection(_epoch); throw new IOException("Relay write was not accepted."); }
    }
    private Task PullAsync(CancellationToken token) => SendAsync(GhostPacket.V2Sync,_settings.MyUuid,new byte[16],[],token);
    private static void InsertText(VaultTransaction db,string id,string peer,bool mine,string text,long created,JsonObject? reply) =>
        db.Execute("INSERT INTO messages(messageId,conversationUuid,isMine,content,ext,timestamp,isRead,replyToId,replyToSender,replyToContent,status,isRevoked,isEdited) VALUES($p0,$p1,$p2,$p3,'txt',$p4,$p5,$p6,$p7,$p8,0,0,0)",id,peer,mine ? 1 : 0,text,created,mine ? 1 : 0,reply?["event_id"]?.GetValue<string>(),reply?["author"]?.GetValue<string>(),reply?["preview"]?.GetValue<string>());
    private static void Receipt(VaultTransaction db,string recipient,string id,string state) => Enqueue(PendingEvent(recipient,"chat.receipt",new JsonObject { ["message_id"]=id,["state"]=state }),db);
    private static Pending PendingEvent(string recipient,string kind,JsonObject body) => new(Guid.NewGuid().ToString("D"),recipient,kind,body,Now);
    private JsonObject Profile() => new() { ["nickname"]=_settings.Snapshot.Nickname ?? "Unknown" };
    private static string Nickname(JsonObject body)
    {
        var name=Text(body,"nickname");
        if (string.IsNullOrWhiteSpace(name) || Encoding.UTF8.GetByteCount(name)>256) throw new InvalidDataException("Invalid nickname.");
        return name;
    }
    private static void RequireAccepted(VaultTransaction db,string recipient)
    {
        if (!IsAccountId(recipient) || !db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1 AND blocked=0",recipient))
            throw new VisibleFailure("Accept the contact request before sending messages.");
    }
    private static bool Blocked(VaultTransaction db,string recipient) => db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND blocked=1",recipient);
    private static string Preview(string value) => string.Concat(value.EnumerateRunes().Take(512));
    private static byte[] Json(JsonObject value) => Encoding.UTF8.GetBytes(value.ToJsonString());
    private static long Now => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    private static string EventId(byte[] bytes)
    {
        if (bytes.Length!=16) throw new InvalidDataException("Invalid event identifier.");
        var value=new Guid(bytes,bigEndian:true).ToString("D"); Validate(value,"event",0); return value;
    }
    private static byte[] EventBytes(string value) => Guid.ParseExact(value,"D").ToByteArray(bigEndian:true);
    private void PublishNotification(string sender)
    {
        try { NotificationRequested?.Invoke(this,new NotificationRequest("MsgAT","New message",sender)); }
        catch (Exception error) { System.Diagnostics.Trace.TraceWarning("Notification observer failed ({0})",error.GetType().Name); }
    }
    private void Report(Exception error,bool notify=true)
    {
        System.Diagnostics.Trace.TraceWarning("Secure operation failed ({0})",error.GetType().Name);
        if (!notify) return;
        try { OperationFailed?.Invoke(this,error is VisibleFailure ? error.Message : "Secure operation failed; local state was retained."); }
        catch (Exception observer) { System.Diagnostics.Trace.TraceWarning("Secure operation observer failed ({0})",observer.GetType().Name); }
    }
    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed,1)!=0) return;
        _relay.PacketReceived-=HandleAsync; _relay.StateChanged-=StateChanged;
        _ready=false; _commands.Writer.TryComplete();
        _relay.AbortConnection(_epoch);
        await _lifetime.CancelAsync().ConfigureAwait(false);
        await Task.WhenAll(_worker,_maintenance).ConfigureAwait(false);
        _lifetime.Dispose();
    }
}
