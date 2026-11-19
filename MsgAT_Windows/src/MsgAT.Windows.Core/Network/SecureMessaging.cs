// SPDX-License-Identifier: AGPL-3.0-only
#pragma warning disable CA1068 // Preserve existing positional cancellation arguments across appended send options.
using MsgAT.Windows.Core.Calls;
using System.Security.Cryptography;
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
public sealed record SendOptions(long? SendAt=null,bool Silent=false);
public sealed record LiveLocationShare(string MessageId, long ExpiresAt);
public sealed record LocationPoint
{
    public int LatitudeE6 { get; }
    public int LongitudeE6 { get; }

    public LocationPoint(int latitudeE6, int longitudeE6)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(latitudeE6, -90_000_000);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(latitudeE6, 90_000_000);
        ArgumentOutOfRangeException.ThrowIfLessThan(longitudeE6, -180_000_000);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(longitudeE6, 180_000_000);
        LatitudeE6 = latitudeE6;
        LongitudeE6 = longitudeE6;
    }

    internal static LocationPoint Parse(JsonObject body) =>
        new(body["latitude_e6"]!.GetValue<int>(), body["longitude_e6"]!.GetValue<int>());
}
public sealed record VideoTrim(TimeSpan Start, TimeSpan End, bool MuteAudio = false)
{
    public static readonly TimeSpan MinimumDuration = TimeSpan.FromMilliseconds(250);
    public bool Fits(TimeSpan duration) => Start >= TimeSpan.Zero && End <= duration && End > Start &&
        End - Start >= MinimumDuration;
}
public delegate Task<EncryptedCaptureFile?> VideoEncoder(EncryptedCaptureStore staging, string path,
    VideoTrim? trim, bool compress, IProgress<double>? progress, CancellationToken token);
public sealed record EditedPhotoContent(string Filename, byte[] Bytes);
public sealed record MediaSendChoice(string Caption, SendOptions Options, bool AsFile = false, bool AsAlbum = false,
    bool CompressPhotos = false, bool CompressVideos = false, IReadOnlyDictionary<string, VideoTrim>? VideoTrims = null,
    IReadOnlyDictionary<string, EditedPhotoContent>? EditedPhotos = null,
    IReadOnlyList<string>? ItemCaptions = null);
public sealed record SendQueueEntry(string Id,string Peer,string Summary,long? SendAt,bool Silent,string State,bool CanCancel,bool CanRetry,string? PeerAlias,string? Failure = null,long? RelayRetryAt = null,bool CanEdit = false);
public sealed record ScheduledMessageDraft(string Text, bool IsCaption);

public sealed record ActivityPrivacy(bool Online,bool Typing);
public enum PeerActivityStatus { None, Typing, Online, Offline }

internal sealed class SecureMessaging : IAsyncDisposable
{
    private sealed class VisibleFailure(string message) : InvalidOperationException(message) { }
    private sealed record Work(Func<CancellationToken,Task> Run,TaskCompletionSource<bool> Result,bool NotifyFailure,CancellationToken Token);
    private readonly RelayClient _relay;
    private readonly SecureSettingsStore _settings;
    private readonly SignalProtocolStore _store;
    private readonly EncryptedObjectStore _objects;
    private readonly MessageActionsStore _actions;
    private readonly AttachmentQueue _attachments;
    private readonly object _importGate=new();
    private readonly HashSet<Task> _imports=[];
    private readonly CancellationTokenSource _lifetime=new();
    private readonly Channel<Work> _commands=Channel.CreateBounded<Work>(new BoundedChannelOptions(64)
    { SingleReader=true,SingleWriter=false,FullMode=BoundedChannelFullMode.Wait });
    private readonly Task _worker;
    private readonly Task _maintenance;
    private readonly Task _storageMaintenance;
    private readonly SemaphoreSlim _storageWake = new(0, 1);
    private readonly HashSet<string> _inFlight=new(StringComparer.Ordinal);
    private byte[]? _syncNonce;
    private readonly Dictionary<string,(string Id,long Time)> _requested=new(StringComparer.Ordinal);
    private readonly Dictionary<string,(JsonObject Bundle,long? Selected,string Request,long ExpiresAt)> _claimedBundles=new(StringComparer.Ordinal);
    private readonly Dictionary<string,long> _unavailableUntil=new(StringComparer.Ordinal);
    private long _epoch=-1;
    private long _nextMaintenance;
    private long _nextExpiration;
    private long _nextRetentionSweep;
    private string? _publishedBody;
    private string? _directoryEpoch;
    private volatile bool _ready;
    private volatile MessageAlertSettings _notificationSettings;
    private int _disposed;

    public SecureMessaging(RelayClient relay,SecureSettingsStore settings,EncryptedDatabase database,EncryptedObjectStore objects,MessageActionsStore actions)
    {
        _relay=relay; _settings=settings; _store=new SignalProtocolStore(database,settings); _objects=objects;_actions=actions;
        _notificationSettings = _store.Transaction(db => db.Query(
            "SELECT message_alerts_enabled,message_alert_sound FROM privacy_preferences WHERE id=1",
            row => new MessageAlertSettings(row.GetInt32(0)==1,row.GetInt32(1)==1)).Single());
        _attachments=new AttachmentQueue(_store,objects,relay,settings,()=>Submit(token=>FlushAsync(token),token:_lifetime.Token));
        Forwarding=new ForwardTaskQueue(_store,actions,ProcessForwardTaskAsync,_attachments.Wake);
        _attachments.ProgressChanged+=ForwardAttachmentChanged;
        _worker=Task.Run(RunAsync);
        _maintenance=Task.Run(MaintainAsync);
        _storageMaintenance=Task.Run(MaintainObjectsAsync);
        _activityMaintenance=Task.Run(MaintainActivityAsync);
        _scheduleMaintenance=Task.Run(MaintainScheduledAsync);
        _relay.PacketReceived+=HandleAsync;
        _relay.StateChanged+=StateChanged;
    }
    public event Action<CallSignal>? CallReceived;
    public event EventHandler<string>? PeerRevoked;
    public event EventHandler<string>? ContactUnavailable;
    public event EventHandler? ContactsChanged;
    public event EventHandler? SendQueueChanged;
    public event EventHandler<IReadOnlySet<string>>? MessagesRemoved;
    private void NotifySendQueueChanged(){try{SendQueueChanged?.Invoke(this,EventArgs.Empty);}catch{/* Queue observers cannot undo a committed change. */}}
    public event EventHandler<NotificationRequest>? NotificationRequested;
    public MessageAlertSettings NotificationSettings => _notificationSettings;
    public Task<bool> SetNotificationSettingsAsync(MessageAlertSettings settings, CancellationToken token = default) => UserAsync(() =>
    {
        _store.Transaction(db =>
        {
            db.Execute("UPDATE privacy_preferences SET message_alerts_enabled=$p0,message_alert_sound=$p1 WHERE id=1",
                settings.Enabled ? 1 : 0, settings.PlaySound ? 1 : 0);
            return true;
        });
        _notificationSettings = settings;
    }, token);
    public event EventHandler<string>? OperationFailed;
    public event EventHandler<AttachmentProgress>? AttachmentProgressChanged
    {
        add=>_attachments.ProgressChanged+=value;
        remove=>_attachments.ProgressChanged-=value;
    }
    public AttachmentProgress? GetAttachmentProgress(string id)=>_attachments.GetProgress(id);
    public ForwardTaskQueue Forwarding {get;}
    private void ForwardAttachmentChanged(object? sender, AttachmentProgress value)
    {
        if (value.Phase is AttachmentPhase.Complete or AttachmentPhase.Unavailable) Forwarding.Wake();
    }

    public Task<bool> ForwardAsync(string? sourcePeer,IReadOnlyList<string> ids,IReadOnlyList<string> recipients,bool hideSender,bool hideCaption,CancellationToken token=default,SendOptions? options=null,bool omitPreview=false)
        =>Forwarding.EnqueueAsync(sourcePeer,ids,recipients,hideSender,hideCaption,token,options,omitPreview);
    private Task<bool> ProcessForwardTaskAsync(string jobId,ForwardQueueRequest request,ObjectPreparation preparation,CancellationToken token)
    {
        lock(_importGate)
        {
            ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed)!=0,this);
            var job=ForwardCoreAsync(jobId,request,preparation,token);
            _imports.Add(job);
            return ObserveForwardAsync(job);
        }
    }
    private async Task<bool> ObserveForwardAsync(Task<bool> job)
    {try{return await job.ConfigureAwait(false);}finally{lock(_importGate)_imports.Remove(job);}}
    private async Task<bool> ForwardCoreAsync(string jobId,ForwardQueueRequest request,ObjectPreparation preparation,CancellationToken token)
    {
        var peer=request.SourcePeer;var ids=request.Ids;var recipients=request.Recipients;var hideSender=request.HideSender;var hideCaption=request.HideCaption;
        using var lifetime=CancellationTokenSource.CreateLinkedTokenSource(token,_lifetime.Token);
        var prepared=new List<(string Recipient,IReadOnlyList<PreparedMessageAction> Rows)>();var committed=false;
        try
        {
            var source=await Task.Run(()=>
            {
                if(recipients.Length is <1 or >20 || recipients.Distinct(StringComparer.Ordinal).Count()!=recipients.Length)
                    throw new InvalidOperationException("Select between 1 and 20 different recipients.");
                _store.Transaction(db=>
                {
                    if(!db.Exists("SELECT 1 FROM forward_jobs WHERE id=$p0 AND state='preparing'",jobId))
                        throw new OperationCanceledException("Forwarding task is no longer pending.");
                    foreach(var recipient in recipients) RequireAccepted(db,recipient);
                    return true;
                });
                var captured=_actions.Capture(peer,ids,includePreview:!request.OmitPreview);ForwardTaskQueue.RequireSnapshot(captured,request);_actions.RequireCopySpace(captured,recipients.Length,includePreview:!request.OmitPreview);return captured;
            },lifetime.Token).ConfigureAwait(false);
            var count=recipients.Length*(source.Count(row=>row.Manifest is not null)+source.Count(row=>!request.OmitPreview && row.PreviewManifest is not null));
            var objectIndex=0;
            foreach(var recipient in recipients)
            {
                var rows = await _actions.PrepareAsync(source, lifetime.Token, preparation,
                    () => preparation.StartAttachment(++objectIndex, count),
                    includePreview: !request.OmitPreview, detectDamagedCache: true).ConfigureAwait(false);
                prepared.Add((recipient,rows));
            }
            return await UserAsync(()=>
            {
                _store.PublicBundle();
                _store.Transaction(db=>
                {
                    lifetime.Token.ThrowIfCancellationRequested();
                    if(!db.Exists("SELECT 1 FROM forward_jobs WHERE id=$p0 AND state='preparing'",jobId))throw new OperationCanceledException("Forwarding task is no longer pending.");
                    preparation.BeginCommit();
                    var created=Now;
                    foreach(var target in prepared)
                    {
                        RequireAccepted(db,target.Recipient);
                        var newIds=target.Rows.Select(_=>Guid.NewGuid().ToString("D")).Order(StringComparer.Ordinal).ToArray();
                        var index=0;
                        foreach(var row in target.Rows)
                        {
                            MessageActionsStore.RequireCurrent(db,row.Source);
                            var id=newIds[index++];
                            var card = row.Source.ContactAccount;
                            var point = row.Source.Location;
                            var kind = point is not null ? "chat.location" : card is not null ? "chat.contact" : row.Object is not null ? "chat.attachment" : "chat.text";
                            var body = kind switch
                            {
                                "chat.location" => new JsonObject { ["latitude_e6"] = point!.LatitudeE6,
                                    ["longitude_e6"] = point.LongitudeE6, ["label"] = row.Source.Text },
                                "chat.contact" => new JsonObject { ["account"] = card, ["name"] = row.Source.Text },
                                "chat.attachment" => new JsonObject { ["object"] = row.Object!.DeepClone(), ["caption"] = hideCaption ? "" : row.Source.Caption },
                                _ => new JsonObject { ["text"] = row.Source.Text }
                            };
                            if(request.Silent)body["silent"]=true;
                            if(row.Source.IsSticker)body["sticker"]=true;
                            if(!request.OmitPreview && MessageActionsStore.PreviewBody(row) is { } preview)body["preview"]=preview;
                            if(!hideSender) body["forward"]=MessageActionsStore.ForwardHeader(row.Source);
                            StampExpiration(db, target.Recipient, body);
                            var pending=new Pending(id,target.Recipient,kind,body,created);
                            if(!Enqueue(pending,db)) throw new VisibleFailure("The forwarded message could not be queued.");
                            if(!HoldScheduled(pending,new SendOptions(request.SendAt,request.Silent),db)) {
                                if(row.Object is null) InsertText(db,id,target.Recipient,true,row.Source.Text,created,null,body["preview"] as JsonObject,card,point);
                                else
                                {
                                    EncryptedObjectStore.Remember(row.Object,db,true);
                                    InsertAttachment(db,id,target.Recipient,true,row.Object,hideCaption?"":row.Source.Caption,created,null,row.Source.IsSticker);
                                }
                                SaveForward(db,id,body["forward"] as JsonObject);
                            }
                        }
                    }
                    db.Execute("DELETE FROM forward_jobs WHERE id=$p0",jobId);
                    return true;
                },notify:true);
                committed=true;_attachments.Wake();
            },lifetime.Token).ConfigureAwait(false);
        }
        catch (DamagedForwardCache damage)
        {
            return await RecoverForwardCacheAsync(jobId, damage, recipients, lifetime.Token).ConfigureAwait(false);
        }
        finally {if(!committed) foreach(var target in prepared)_actions.Discard(target.Rows);}
    }

    private Task<bool> RecoverForwardCacheAsync(string jobId, DamagedForwardCache damage, IReadOnlyList<string> recipients,
        CancellationToken token) => UserAsync(() =>
    {
        var source = damage.SourceMessage;
        _store.Transaction(db =>
        {
            token.ThrowIfCancellationRequested();
            if (!db.Exists("SELECT 1 FROM forward_jobs WHERE id=$p0 AND state='preparing' AND error_code IS NULL", jobId))
                throw new VisibleFailure("The forwarding cache could not be restored.");
            ForwardTaskQueue.RequireRecipients(db, recipients);
            MessageActionsStore.RequireCurrent(db, source);
            if (!EncryptedObjectStore.QueueCacheReplacement(db, source.Peer, source.Id, Now, previewOnly: damage.PreviewOnly) ||
                !AttachmentQueue.SetDownloadRequested(db, source.Peer, source.Id, requested: true, previewOnly: damage.PreviewOnly))
                throw new VisibleFailure("The forwarding cache could not be restored.");
            return db.Execute("UPDATE forward_jobs SET state='waiting',error_code='cache_repair_attempted' WHERE id=$p0", jobId);
        }, notify: true);
        _store.NotifyBusinessChanges(new AttachmentCacheChangedEventArgs(source.Id));
        if (_storageWake.CurrentCount == 0) _storageWake.Release();
    }, token);

    public Task<bool> SendMediaAsync(string recipient,string source,string mime,string? replyId,DraftSendCommit? draft=null,CancellationToken token=default,SendOptions? options=null,ObjectPreparation? preparation=null,string caption="") =>
        TrackImportAndSendAsync(recipient,ct=>_objects.ImportAsync(source,mime,ct,preparation),replyId,draft,token,options:options,preparation:preparation,caption:caption);
    public Task<bool> SendMediaBytesAsync(string recipient, ReadOnlyMemory<byte> bytes, string filename, string mime,
        string? replyId, DraftSendCommit? draft = null, CancellationToken token = default, SendOptions? options = null,
        ObjectPreparation? preparation = null, string caption = "",
        Func<ReadOnlyMemory<byte>, string, CancellationToken, Task<byte[]?>>? compressPhoto = null) =>
        TrackImportAndSendAsync(recipient, ct => ImportOptionalCompressedAsync(
            filename, mime, ct, preparation,
            current => _objects.ImportBytesAsync(bytes, filename, mime, current, preparation),
            compressPhoto is null ? null : current => compressPhoto(bytes, mime, current)),
            replyId, draft, token, options: options, preparation: preparation, caption: caption);

    public Task<bool> SendRecordingAsync(string recipient,EncryptedCaptureFile source,string? replyId,DraftSendCommit? draft=null,CancellationToken token=default,SendOptions? options=null,ObjectPreparation? preparation=null,string caption="",bool asFile=false)=>
        TrackImportAndSendAsync(recipient,ct=>_objects.ImportRecordingAsync(source,ct,preparation,asFile),replyId,draft,token,options:options,preparation:preparation,caption:caption);
    public Task<bool> SendStickerAsync(string recipient,string stickerId,string? replyId,DraftSendCommit? draft=null,CancellationToken token=default,SendOptions? options=null,ObjectPreparation? preparation=null)
    {
        var source=_store.Transaction(db=>{RequireAccepted(db,recipient);return StickerLibraryStore.Source(db,stickerId)??throw new VisibleFailure("Sticker is no longer available.");});
        return TrackImportAndSendAsync(recipient,ct=>_objects.CopyAsync(JsonNode.Parse(source.Object)!.AsObject(),ct,preparation),replyId,draft,token,(stickerId,source.Sha256),options,preparation);
    }
    public Task<bool> SendMediaBatchAsync(string recipient, IReadOnlyList<string> sources, string? replyId,
        DraftSendCommit? draft = null, CancellationToken token = default, SendOptions? options = null,
        ObjectPreparation? preparation = null, string caption = "", bool asFile = false, bool asAlbum = false,
        Func<string, string, CancellationToken, Task<byte[]?>>? compressPhoto = null, VideoEncoder? videoEncoder = null,
        bool compressVideos = false, IReadOnlyDictionary<string, VideoTrim>? videoTrims = null,
        IReadOnlyDictionary<string, EditedPhotoContent>? editedPhotos = null,
        Func<ReadOnlyMemory<byte>, string, CancellationToken, Task<byte[]?>>? compressEditedPhoto = null,
        IReadOnlyList<string>? itemCaptions = null)
    {
        var paths = sources.ToArray();
        var captions = itemCaptions?.ToArray();
        if (paths.Length is < 1 or > 10 || paths.Distinct(StringComparer.OrdinalIgnoreCase).Count() != paths.Length)
            throw new ArgumentException("Choose up to 10 different attachments.", nameof(sources));
        if (videoTrims is { Count: > 0 } && (videoEncoder is null ||
            videoTrims.Keys.Any(path => !paths.Contains(path, StringComparer.Ordinal))))
            throw new ArgumentException("The selected videos have changed.", nameof(videoTrims));
        if (editedPhotos is not null && editedPhotos.Keys.Any(path => !paths.Contains(path, StringComparer.Ordinal)))
            throw new ArgumentException("The selected photos have changed.", nameof(editedPhotos));
        var imports = paths.Select(path =>
        {
            var sourceMime = EncryptedObjectStore.MimeForPath(path);
            var mime = asFile ? "application/octet-stream" : sourceMime;
            var trim = videoTrims?.GetValueOrDefault(path);
            if (editedPhotos?.GetValueOrDefault(path) is { } edit)
            {
                if (trim is not null || sourceMime is not ("image/jpeg" or "image/png") ||
                    edit.Bytes.Length is <= 0 or > 32 * 1024 * 1024)
                    throw new ArgumentException("The edited photo is unavailable.");
                return new Func<CancellationToken, Task<JsonObject>>(ct => ImportOptionalCompressedAsync(
                    edit.Filename, mime, ct, preparation,
                    current => _objects.ImportBytesAsync(edit.Bytes, edit.Filename, mime, current, preparation),
                    asFile || compressEditedPhoto is null ? null : current => compressEditedPhoto(edit.Bytes, mime, current)));
            }
            var isVideo = sourceMime.StartsWith("video/", StringComparison.Ordinal);
            if (trim is not null && !isVideo) throw new ArgumentException("The selected video is unavailable.");
            if (isVideo && videoEncoder is not null && (trim is not null || compressVideos && !asFile))
                return new Func<CancellationToken, Task<JsonObject>>(ct => ImportVideoAsync(
                    path, mime, ct, preparation, videoEncoder, trim, compressVideos && !asFile, asFile));
            return new Func<CancellationToken, Task<JsonObject>>(ct => ImportOptionalCompressedAsync(
                Path.GetFileName(path), mime, ct, preparation, current => _objects.ImportAsync(path, mime, current, preparation),
                asFile || compressPhoto is null ? null : current => compressPhoto(path, mime, current)));
        }).ToArray();
        return TrackImportsAndSendAsync(recipient, imports, replyId, draft, token, null, options, preparation, caption, asAlbum, captions);
    }

    private async Task<JsonObject> ImportVideoAsync(string path, string mime,
        CancellationToken token, ObjectPreparation? preparation, VideoEncoder encoder, VideoTrim? trim, bool compress, bool asFile)
    {
        preparation?.Check();
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(token, preparation?.Cancellation ?? CancellationToken.None);
        var progress = preparation is null ? null : new EncoderProgress(preparation, preparation.SetStep(PreparationStep.EncodingVideo));
        var encoded = await encoder(_objects.Captures, path, trim, compress, progress, lifetime.Token).ConfigureAwait(false);
        JsonObject manifest;
        try
        {
            lifetime.Token.ThrowIfCancellationRequested();
            if (encoded is null && trim is not null) throw new VisibleFailure("The edited video could not be exported.");
            preparation?.SetStep(PreparationStep.Importing);
            manifest = encoded is null
                ? await _objects.ImportAsync(path, mime, lifetime.Token, preparation).ConfigureAwait(false)
                : await _objects.ImportRecordingAsync(encoded, lifetime.Token, preparation, asFile).ConfigureAwait(false);
        }
        catch
        {
            if (encoded is not null)
            {
                try { await encoded.DisposeAsync().ConfigureAwait(false); }
                catch (Exception error) { System.Diagnostics.Trace.TraceWarning("Video preparation cleanup failed ({0}).", error.GetType().Name); }
            }
            throw;
        }
        if (encoded is not null)
        {
            try { await encoded.DisposeAsync().ConfigureAwait(false); }
            catch { _objects.DiscardCopy(manifest); throw; }
        }
        return manifest;
    }

    private sealed class EncoderProgress(ObjectPreparation preparation, long version) : IProgress<double>
    {
        private int _last = -1;
        public void Report(double value)
        {
            if (!double.IsFinite(value)) return;
            var percent = (int)Math.Clamp(value, 0, 100);
            if (Interlocked.Exchange(ref _last, percent) == percent) return;
            try { preparation.ReportVideoProgress(version, percent); }
            catch (Exception error) when (error is OperationCanceledException or InvalidOperationException or IOException or
                System.Security.Cryptography.CryptographicException or System.Text.Json.JsonException) { }
        }
    }

    private async Task<JsonObject> ImportOptionalCompressedAsync(string name, string mime, CancellationToken token,
        ObjectPreparation? preparation, Func<CancellationToken, Task<JsonObject>> original,
        Func<CancellationToken, Task<byte[]?>>? compressor)
    {
        preparation?.Check();
        if (compressor is null || mime is not ("image/jpeg" or "image/png"))
            return await original(token).ConfigureAwait(false);
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(token, preparation?.Cancellation ?? CancellationToken.None);
        preparation?.SetStep(PreparationStep.EncodingPhoto);
        var compressed = await compressor(lifetime.Token).ConfigureAwait(false);
        try
        {
            lifetime.Token.ThrowIfCancellationRequested();
            preparation?.SetStep(PreparationStep.Importing);
            return compressed is null
                ? await original(lifetime.Token).ConfigureAwait(false)
                : await _objects.ImportBytesAsync(compressed, name, mime, lifetime.Token, preparation).ConfigureAwait(false);
        }
        finally
        {
            if (compressed is not null) System.Security.Cryptography.CryptographicOperations.ZeroMemory(compressed);
        }
    }

    private Task<bool> TrackImportAndSendAsync(string recipient, Func<CancellationToken, Task<JsonObject>> importer,
        string? replyId, DraftSendCommit? draft, CancellationToken token, (string Id, string Sha)? sticker = null,
        SendOptions? options = null, ObjectPreparation? preparation = null, string caption = "") =>
        TrackImportsAndSendAsync(recipient, [importer], replyId, draft, token, sticker, options, preparation, caption);

    private Task<bool> TrackImportsAndSendAsync(string recipient, IReadOnlyList<Func<CancellationToken, Task<JsonObject>>> importers,
        string? replyId, DraftSendCommit? draft, CancellationToken token, (string Id, string Sha)? sticker,
        SendOptions? options, ObjectPreparation? preparation, string caption, bool asAlbum = false, string[]? itemCaptions = null)
    {
        lock (_importGate)
        {
            ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
            var task = ImportAndSendAsync(recipient, importers, replyId, draft, token, sticker, options ?? new SendOptions(),
                preparation, caption, asAlbum, itemCaptions);
            _imports.Add(task);
            return ObserveForwardAsync(task);
        }
    }

    private async Task<bool> ImportAndSendAsync(string recipient, IReadOnlyList<Func<CancellationToken, Task<JsonObject>>> importers,
        string? replyId, DraftSendCommit? draft, CancellationToken token, (string Id, string Sha)? sticker,
        SendOptions options, ObjectPreparation? preparation, string caption, bool asAlbum, string[]? itemCaptions)
    {
        var imported = new List<JsonObject>(importers.Count);
        var committed = false;
        try
        {
            if (itemCaptions is not null && itemCaptions.Length != importers.Count)
                throw new InvalidOperationException("The selected attachments have changed.");
            if (Encoding.UTF8.GetByteCount(caption) > 48 * 1024 ||
                itemCaptions?.Any(text => Encoding.UTF8.GetByteCount(text) > 48 * 1024) == true)
                throw new InvalidOperationException("Caption is too long.");
            preparation?.Check();
            if (draft is not null && (draft.ClearsText || draft.Draft.Peer != recipient ||
                draft.Draft.ReplyId != replyId || draft.ClearRevision <= draft.Draft.Revision))
                throw new InvalidOperationException("The draft no longer matches the attachments.");

            _store.Transaction(db => { RequireAccepted(db, recipient); return true; });
            using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(token, _lifetime.Token);
            for (var index = 0; index < importers.Count; index++)
            {
                lifetime.Token.ThrowIfCancellationRequested();
                preparation?.StartAttachment(index + 1, importers.Count);
                Task<JsonObject> import;
                lock (_importGate)
                {
                    ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
                    import = importers[index](lifetime.Token);
                    _imports.Add(import);
                }
                try { imported.Add(await import.ConfigureAwait(false)); }
                finally { lock (_importGate) _imports.Remove(import); }
            }

            string? albumId = null;
            if (asAlbum)
            {
                if (imported.Count < 2 || imported.Any(item => !EncryptedObjectStore.IsAlbumMedia(Text(item, "mime"))))
                    throw new VisibleFailure("An album needs two or more photos or videos.");
                albumId = Guid.NewGuid().ToString("D");
            }

            return await UserAsync(() =>
            {
                _store.PublicBundle();
                var ids = imported.Select(_ => Guid.NewGuid().ToString("D")).OrderBy(id => id, StringComparer.Ordinal).ToArray();
                var created = Now;
                lifetime.Token.ThrowIfCancellationRequested();
                preparation?.BeginCommit();
                _store.Transaction(db =>
                {
                    RequireAccepted(db, recipient);
                    if (draft is not null && !db.Exists("SELECT 1 FROM conversation_drafts WHERE peer=$p0 AND token=$p1", recipient, draft.Draft.Token))
                        throw new VisibleFailure("The conversation changed before the attachments were queued.");
                    if (sticker is { } selected && !db.Exists("SELECT 1 FROM sticker_items WHERE id=$p0 AND sha256=$p1", selected.Id, selected.Sha))
                        throw new VisibleFailure("Sticker changed while preparing.");

                    var reply = Quote(db, recipient, replyId, draft?.Draft.ReplyQuote);
                    for (var index = 0; index < imported.Count; index++)
                    {
                        var manifest = imported[index];
                        var itemCaption = itemCaptions?[index] ?? (index == 0 ? caption : "");
                        var itemReply = index == 0 ? reply : null;
                        var body = new JsonObject { ["object"] = manifest.DeepClone(), ["caption"] = itemCaption };
                        var album = albumId is null ? null : new JsonObject
                        {
                            ["id"] = albumId, ["index"] = index, ["count"] = imported.Count
                        };
                        if (album is not null) body["album"] = album;
                        if (options.Silent) body["silent"] = true;
                        if (sticker is not null) body["sticker"] = true;
                        if (itemReply is not null) body["reply"] = itemReply;
                        StampExpiration(db, recipient, body);
                        var pending = new Pending(ids[index], recipient, "chat.attachment", body, created);
                        if (!Enqueue(pending, db)) throw new VisibleFailure("The attachments could not be queued.");
                        if (!HoldScheduled(pending, options, db))
                        {
                            EncryptedObjectStore.Remember(manifest, db, true);
                            InsertAttachment(db, ids[index], recipient, true, manifest, itemCaption, created, itemReply, sticker is not null, album);
                        }
                    }
                    if (sticker is { } used) db.Execute("UPDATE sticker_items SET last_used=$p1 WHERE id=$p0", used.Id, created);
                    if (draft is not null) ConversationDraftStore.ClearForCommittedSend(db, draft);
                    return true;
                }, notify: true);
                committed = true;
                if (draft is not null) draft.Owner.ObserveCommittedSend(draft);
                _attachments.Wake();
            }, lifetime.Token).ConfigureAwait(false);
        }
        catch
        {
            if (draft is not null) draft.Owner.ObserveFailedSend(draft);
            preparation?.Check();
            throw;
        }
        finally
        {
            try
            {
                if (!committed)
                {
                    foreach (var manifest in imported)
                    {
                        try { _objects.DiscardCopy(manifest); }
                        catch (Exception) { System.Diagnostics.Trace.TraceWarning("Staged attachment cleanup failed."); }
                    }
                }
            }
            finally { preparation?.Dispose(); }
        }
    }

    public Task<bool> RequireCallableAsync(string recipient,CancellationToken token=default) => UserAsync(()=>
    {
        if (!_ready || _relay.State!=SyncState.Synced) throw new VisibleFailure("Connect before calling.");
        _store.Transaction(db=> { RequireAccepted(db,recipient); return true; });
    },token);

    public Task<bool> SendCallSignalAsync(string recipient,string kind,JsonObject body,long createdAt,CancellationToken token=default) => UserAsync(()=>
    {
        if (!_ready || !kind.StartsWith("call.",StringComparison.Ordinal) || body["expires_at"]!.GetValue<long>()<=Now)
            throw new VisibleFailure("Call signaling expired or disconnected.");
        _store.Transaction(db=>
        {
            RequireAccepted(db,recipient);
            Enqueue(new Pending(Guid.NewGuid().ToString(),recipient,kind,body,createdAt),db);
            return true;
        });
    },token);

    public Task<bool> SendTextAsync(string recipient,string text,string? replyId,DraftSendCommit? draft=null,WebPreviewDraft? preview=null,CancellationToken token=default,SendOptions? options=null,string? contactAccount=null,LocationPoint? location=null,ObjectPreparation? preparation=null)
    {
        lock(_importGate)
        {
            ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed)!=0,this);
            var task=SendTextCoreAsync(recipient,text,replyId,draft,preview,token,options??new SendOptions(),contactAccount,location,preparation);_imports.Add(task);return ObserveForwardAsync(task);
        }
    }
    private async Task<bool> SendTextCoreAsync(string recipient,string text,string? replyId,DraftSendCommit? draft,WebPreviewDraft? preview,CancellationToken token,SendOptions options,string? contactAccount,LocationPoint? location,ObjectPreparation? preparation)
    {
        using var lifetime=CancellationTokenSource.CreateLinkedTokenSource(token,_lifetime.Token);
        var image=preview?.Image?.ToArray();JsonObject? cover=null;var committed=false;
        try
        {
            var plainText = contactAccount is null && location is null;
            if (contactAccount is not null && location is not null || !plainText && preview is not null)
                throw new ArgumentException("Invalid message content.");
            if(string.IsNullOrWhiteSpace(text) || Encoding.UTF8.GetByteCount(text)>48*1024)throw new InvalidOperationException("Message text is empty or too large.");
            if(draft is not null && (draft.ClearsText!=plainText || draft.Draft.Peer!=recipient ||
                plainText && draft.Draft.Text!=text || draft.Draft.ReplyId!=replyId || draft.ClearRevision<=draft.Draft.Revision))throw new InvalidOperationException("The draft no longer matches the message.");
            if (image is not null)
                cover = await _objects.ImportBytesAsync(image, "preview.jpg", "image/jpeg", lifetime.Token, preparation).ConfigureAwait(false);
            return await UserAsync(()=>
            {
                _store.PublicBundle();var id=Guid.NewGuid().ToString("D");var created=Now;
                var kind=location is not null?"chat.location":contactAccount is not null?"chat.contact":"chat.text";
                var body=kind switch
                {
                    "chat.location" => new JsonObject {["latitude_e6"]=location!.LatitudeE6,["longitude_e6"]=location.LongitudeE6,["label"]=text},
                    "chat.contact" => new JsonObject {["account"]=contactAccount,["name"]=text},
                    _ => new JsonObject {["text"]=text}
                };
                if(options.Silent)body["silent"]=true;if(preview is not null)body["preview"]=preview.Body(cover);
                _store.Transaction(db=>
                {
                    RequireAccepted(db,recipient);
                    if(contactAccount is not null && contactAccount!=_settings.MyUuid) RequireAccepted(db,contactAccount);
                    if(draft is not null && !db.Exists("SELECT 1 FROM conversation_drafts WHERE peer=$p0 AND token=$p1",recipient,draft.Draft.Token))throw new VisibleFailure("The conversation changed before the message was queued.");
                    var quote = Quote(db, recipient, replyId, draft?.Draft.ReplyQuote);
                    if (quote is not null) body["reply"] = quote;
                    StampExpiration(db, recipient, body);
                    preparation?.BeginCommit();
                    var pending=new Pending(id,recipient,kind,body,created);
                    if(!Enqueue(pending,db))throw new VisibleFailure("The message could not be queued.");
                    if(!HoldScheduled(pending,options,db))InsertText(db,id,recipient,true,text,created,quote,body["preview"] as JsonObject,contactAccount,location);
                    if(draft is not null)ConversationDraftStore.ClearForCommittedSend(db,draft);
                    return true;
                },notify:true);
                committed=true;_attachments.Wake();if(draft is not null)draft.Owner.ObserveCommittedSend(draft);
            },lifetime.Token).ConfigureAwait(false);
        }
        catch {if(draft is not null)draft.Owner.ObserveFailedSend(draft);throw;}
        finally {if(image is not null)System.Security.Cryptography.CryptographicOperations.ZeroMemory(image);if(!committed && cover is not null)_objects.DiscardCopy(cover);}
    }

    public async Task<LiveLocationShare?> StartLiveLocationAsync(string recipient, LocationPoint point,
        TimeSpan duration, string? label, string? replyId, DraftSendCommit? draft, bool silent, CancellationToken token)
    {
        LiveLocationShare? share = null;
        try
        {
            var committed = await UserAsync(() =>
            {
                if (duration < TimeSpan.FromMilliseconds(1) || duration > TimeSpan.FromHours(8))
                    throw new VisibleFailure("Choose a live location duration of up to eight hours.");
                if (draft is not null && (draft.ClearsText || draft.Draft.Peer != recipient ||
                    draft.Draft.ReplyId != replyId || draft.ClearRevision <= draft.Draft.Revision))
                    throw new VisibleFailure("The draft no longer matches the message.");
                _store.PublicBundle();
                var id = Guid.NewGuid().ToString("D");
                var timestamp = Now;
                var expiresAt = timestamp + (long)duration.TotalMilliseconds;
                var body = new JsonObject
                {
                    ["latitude_e6"] = point.LatitudeE6, ["longitude_e6"] = point.LongitudeE6, ["expires_at"] = expiresAt
                };
                if (!string.IsNullOrWhiteSpace(label)) body["label"] = label;
                if (silent) body["silent"] = true;
                _store.Transaction(db =>
                {
                    RequireAccepted(db, recipient);
                    if (LiveLocationStore.HasActiveShare(db, recipient, timestamp))
                        throw new VisibleFailure("Stop the current live location before sharing again.");
                    if (draft is not null && !db.Exists("SELECT 1 FROM conversation_drafts WHERE peer=$p0 AND token=$p1",
                        recipient, draft.Draft.Token))
                        throw new VisibleFailure("The conversation changed before the location was queued.");
                    var reply = Quote(db, recipient, replyId, draft?.Draft.ReplyQuote);
                    if (reply is not null) body["reply"] = reply;
                    var expiration = StampExpiration(db, recipient, body);
                    if (expiration > 0)
                    {
                        expiresAt = Math.Min(expiresAt, timestamp + expiration);
                        body["expires_at"] = expiresAt;
                    }
                    const string kind = "chat.location.start";
                    if (!Enqueue(new Pending(id, recipient, kind, body, timestamp), db) ||
                        !LiveLocationStore.Apply(db, recipient, true, kind, id, body, timestamp, timestamp))
                        throw new VisibleFailure("The live location could not be queued.");
                    InsertText(db, id, recipient, true, body["label"]?.GetValue<string>() ?? "Location", timestamp,
                        reply, location: point, live: true);
                    if (draft is not null) ConversationDraftStore.ClearForCommittedSend(db, draft);
                    return true;
                }, notify: true);
                share = new LiveLocationShare(id, expiresAt);
                if (draft is not null) draft.Owner.ObserveCommittedSend(draft);
            }, token).ConfigureAwait(false);
            return committed ? share : null;
        }
        finally
        {
            if (share is null && draft is not null) draft.Owner.ObserveFailedSend(draft);
        }
    }

    public LiveLocationSnapshot? LiveLocation(string recipient, string messageId) =>
        _store.Transaction(db => LiveLocationStore.Read(db, recipient, messageId));

    public Task<bool> UpdateLiveLocationAsync(string recipient, string messageId, LocationPoint point, CancellationToken token) =>
        ChangeLiveLocationAsync(recipient, messageId, point, token);

    public Task<bool> StopLiveLocationAsync(string recipient, string messageId, CancellationToken token) =>
        ChangeLiveLocationAsync(recipient, messageId, null, token);

    private async Task<bool> ChangeLiveLocationAsync(string recipient, string messageId, LocationPoint? point, CancellationToken token)
    {
        var active = point is null;
        var completed = await UserAsync(() =>
        {
            var changed = _store.Transaction(db =>
            {
                var state = LiveLocationStore.Read(db, recipient, messageId, mineOnly: true);
                var clock = Now;
                if (state is null || state.Ended || clock >= state.ExpiresAt) return false;
                if (!db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1 AND blocked=0", recipient)) return false;
                active = true;
                var end = point is null;
                if (end && db.Exists("SELECT 1 FROM protocol_pending_events WHERE event_id=$p0 AND recipient=$p1 AND kind='chat.location.start'",
                    messageId, recipient))
                {
                    db.Execute("DELETE FROM protocol_pending_events WHERE event_id=$p0 AND recipient=$p1", messageId, recipient);
                    db.Execute("DELETE FROM messages WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=1", messageId, recipient);
                    return true;
                }
                if (!end && (point == state.Point || state.Revision >= 4095)) return false;
                var id = Guid.NewGuid().ToString("D");
                var kind = end ? "chat.location.end" : "chat.location.update";
                var timestamp = Math.Max(clock, state.StartedAt);
                var body = new JsonObject
                {
                    ["message_id"] = messageId, ["started_at"] = state.StartedAt, ["expires_at"] = state.ExpiresAt,
                    ["revision"] = state.Revision + 1
                };
                if (point is not null)
                {
                    body["latitude_e6"] = point.LatitudeE6;
                    body["longitude_e6"] = point.LongitudeE6;
                }
                if (!LiveLocationStore.Apply(db, recipient, true, kind, id, body, timestamp, clock)) return false;
                if (!Enqueue(new Pending(id, recipient, kind, body, timestamp), db))
                    throw new VisibleFailure("The live location could not be queued.");
                LiveLocationStore.ReplaceOutgoing(db, id, recipient, messageId, state.ExpiresAt, end);
                return true;
            });
            if (changed) _store.NotifyBusinessChanges();
        }, token).ConfigureAwait(false);
        return completed && active;
    }

    public Task<bool> EditMessageAsync(string recipient,string messageId,string text,CancellationToken token=default) =>
        ChangeMessageAsync(recipient,messageId,text,token);
    public Task<bool> RevokeMessageAsync(string recipient,string messageId,CancellationToken token=default) =>
        ChangeMessageAsync(recipient,messageId,null,token);

    public Task<bool> RevokeSentMessagesAsync(string recipient, IReadOnlyList<string> messageIds, CancellationToken token = default)
    {
        var ids = messageIds.ToHashSet(StringComparer.Ordinal);
        return UserAsync(() =>
        {
            if (ids.Count is < 1 or > 100) throw new VisibleFailure("Choose up to 100 messages.");
            _store.Transaction(db =>
            {
                RequireAccepted(db, recipient);
                var timestamp = Now;
                foreach (var id in ids)
                {
                    if (!db.Exists("""
                        SELECT 1 FROM messages WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=1
                        AND isRevoked=0 AND status BETWEEN 1 AND 3 AND ext<>'live_location'
                        AND NOT EXISTS(SELECT 1 FROM message_expiration
                            WHERE message_id=messages.messageId AND expires_at<=$p2)
                        """, id, recipient, timestamp))
                        throw new VisibleFailure("Some selected messages can no longer be revoked.");
                    EnqueueMessageChange(db, recipient, id, text: null, caption: false);
                }
                return true;
            }, notify: true);
        }, token);
    }

    public Task<bool> SetMessagePinnedAsync(string peer,string messageId,bool pinned,CancellationToken token=default,bool notify=false)=>UserAsync(()=>
    {
        _store.Transaction(db=>{
            RequireAccepted(db,peer);
            var body=MessagePinsStore.Body(db,peer,_settings.MyUuid,messageId,pinned,notify);
            if(Enqueue(PendingEvent(peer,"chat.pin",body),db) && !MessagePinsStore.Apply(db,peer,_settings.MyUuid,_settings.MyUuid,body))throw new VisibleFailure("This message cannot be pinned.");
            return true;
        },notify:true);
    },token);
    public async Task<PinnedMessagePage> PinnedMessagesAsync(string peer,int offset=0,CancellationToken token=default)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(offset);PinnedMessagePage? page=null;
        await Submit(_=>{page=_store.Transaction(db=>MessagePinsStore.List(db,peer,_settings.MyUuid,offset));return Task.CompletedTask;},token:token).ConfigureAwait(false);
        return page??throw new InvalidOperationException("Pinned messages are unavailable.");
    }

    private Task<bool> ChangeMessageAsync(string recipient,string messageId,string? text,CancellationToken token) => UserAsync(()=>
    {
        if(text is not null && Encoding.UTF8.GetByteCount(text)>48*1024)
            throw new VisibleFailure("Message text is too large.");
        _store.Transaction(db=>
        {
            RequireAccepted(db,recipient);
            var kind = db.Text("SELECT ext FROM messages WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=1 AND isRevoked=0",messageId,recipient)
                ?? throw new VisibleFailure("This message cannot be changed.");
            var caption = text is not null && kind == "attachment" && db.Exists("""
                SELECT 1 FROM message_attachments WHERE message_id=$p0
                AND NOT EXISTS(SELECT 1 FROM message_stickers WHERE message_id=$p0)
                """,messageId);
            if (text is not null && !caption && (kind != "txt" || string.IsNullOrWhiteSpace(text)))
                throw new VisibleFailure("This message cannot be edited.");
            if (text is not null && MessageExpirationStore.IsExpiring(db, messageId))
                throw new VisibleFailure("Messages set to auto-delete cannot be edited.");
            EnqueueMessageChange(db, recipient, messageId, text, caption);
            return true;
        },notify:true);
    },token);

    private void EnqueueMessageChange(VaultTransaction db, string recipient, string messageId, string? text, bool caption)
    {
        var previous = SavedChange(db, _settings.MyUuid, messageId);
        var revision = previous is null ? 1 : Number(previous, "revision") + 1;
        if (revision > uint.MaxValue) throw new VisibleFailure("The message revision limit was reached.");
        var body = new JsonObject { ["message_id"] = messageId, ["revision"] = revision };
        if (text is not null) body["text"] = text;
        var change = new JsonObject { ["revision"] = revision, ["revoked"] = text is null, ["text"] = text };
        if (caption) { body["caption"] = true; change["caption"] = true; }
        if (!Enqueue(PendingEvent(recipient, text is null ? "chat.revoke" : "chat.edit", body), db))
            throw new VisibleFailure("The message change could not be queued.");
        SaveChange(db, recipient, _settings.MyUuid, true, messageId, change);
    }

    public Task<bool> RequestContactAsync(string recipient,CancellationToken token=default)=>ContactOperationAsync(()=>
    {
        if(!IsAccountId(recipient) || recipient==_settings.MyUuid)throw new VisibleFailure("Invalid contact identity.");
        _store.PublicBundle();
        _store.Transaction(db=>{
            var state=ContactStateStore.Apply(db,recipient,new JsonObject{["kind"]="request",["id"]=Guid.NewGuid().ToString("D")})["state"]!.AsObject();
            db.Execute("DELETE FROM object_transfers WHERE direction=0 AND event_id IN (SELECT event_id FROM protocol_pending_events WHERE recipient=$p0 AND kind='contact.request')",recipient);
            db.Execute("DELETE FROM protocol_pending_events WHERE recipient=$p0 AND kind='contact.request'",recipient);
            var body=Profile(db);body["request_id"]=state["outbound_id"]!.DeepClone();body["request_revision"]=state["outbound_revision"]!.DeepClone();
            if(!Enqueue(PendingEvent(recipient,"contact.request",body),db))throw new VisibleFailure("Contact request could not be queued.");
            db.Execute("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES($p0,'Pending request','','') ON CONFLICT(uuid) DO NOTHING",recipient);return true;
        },notify:true);
    },token);

    public Task<bool> AcceptContactAsync(string recipient,CancellationToken token=default)=>ContactOperationAsync(()=>_store.Transaction(db=>{
        var profile=UserProfileStore.Read(db,recipient)??throw new VisibleFailure("The contact request is unavailable.");
        var state=ContactStateStore.Apply(db,recipient,new JsonObject{["kind"]="accept"})["state"]!.AsObject();
        var body=Profile(db);body["request_id"]=state["inbound_id"]!.DeepClone();
        if(!Enqueue(PendingEvent(recipient,"contact.accept",body),db))throw new VisibleFailure("Contact acceptance could not be queued.");
        db.Execute("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES($p0,$p1,$p2,'') ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias,avatarPath=excluded.avatarPath",recipient,profile.Nickname,profile.AvatarReference??"");return true;
    },notify:true),token);

    public Task<bool> DeclineContactAsync(string recipient,CancellationToken token=default)=>ContactOperationAsync(()=>_store.Transaction(db=>{
        var state=ContactStateStore.Apply(db,recipient,new JsonObject{["kind"]="decline"})["state"]!.AsObject();
        if(!Enqueue(PendingEvent(recipient,"contact.decline",new JsonObject{["request_id"]=state["inbound_id"]!.DeepClone()}),db))throw new VisibleFailure("Contact decision could not be queued.");return true;
    },notify:true),token);

    public Task<bool> WithdrawContactAsync(string recipient,CancellationToken token=default)=>ContactOperationAsync(()=>_store.Transaction(db=>{
        var state=ContactStateStore.Apply(db,recipient,new JsonObject{["kind"]="withdraw"})["state"]!.AsObject();CancelContactQueue(db,recipient);
        if(!Enqueue(PendingEvent(recipient,"contact.withdraw",new JsonObject{["request_id"]=state["outbound_id"]!.DeepClone(),["request_revision"]=state["outbound_revision"]!.DeepClone()}),db))throw new VisibleFailure("Request withdrawal could not be queued.");return true;
    },notify:true),token);

    public Task<bool> SetContactBlockedAsync(string recipient,bool value,CancellationToken token=default)=>ContactOperationAsync(()=>{
        _store.Transaction(db=>{ContactStateStore.Apply(db,recipient,new JsonObject{["kind"]=value?"block":"unblock"});return true;},notify:true);
        _requested.Remove(recipient);_unavailableUntil.Remove(recipient);if(value)NotifyContactUnavailable(recipient);_attachments.Wake();
    },token);

    public Task<bool> DeleteContactAsync(string recipient,CancellationToken token=default)=>ContactOperationAsync(()=>{_store.Transaction(db=>{
        var old=ContactStateStore.Apply(db,recipient,new JsonObject{["kind"]="delete"})["previous"]!.AsObject();CancelContactQueue(db,recipient);
        var kind="";JsonObject? body=null;
        if(old["accepted"]!.GetValue<bool>()){kind="contact.delete";body=new JsonObject{["relationship_ids"]=old["accepted_ids"]!.DeepClone()};}
        else if(old["outgoing"]!.GetValue<bool>() && old["outbound_id"] is { } outgoing){kind="contact.withdraw";body=new JsonObject{["request_id"]=outgoing.DeepClone(),["request_revision"]=old["outbound_revision"]!.DeepClone()};}
        else if(old["incoming"]!.GetValue<bool>() && old["inbound_id"] is { } incoming){kind="contact.decline";body=new JsonObject{["request_id"]=incoming.DeepClone()};}
        if(body is not null && !db.Exists("SELECT 1 FROM protocol_revocations WHERE peer=$p0",recipient) && !Enqueue(PendingEvent(recipient,kind,body),db))throw new VisibleFailure("Contact change could not be queued.");
        db.Execute("DELETE FROM user_profiles WHERE peer=$p0",recipient);db.Execute("DELETE FROM conversations WHERE uuid=$p0",recipient);ClearHistory(db,recipient);return true;
    },notify:true);_requested.Remove(recipient);_unavailableUntil.Remove(recipient);NotifyContactUnavailable(recipient);_attachments.Wake();},token);

    private Task<bool> ContactOperationAsync(Action work,CancellationToken token)=>UserAsync(()=>{work();NotifyContactsChanged();},token);
    private void NotifyContactsChanged(){try{ContactsChanged?.Invoke(this,EventArgs.Empty);}catch{/* Observers cannot undo a committed transition. */}}

    private void NotifyContactUnavailable(string peer){_claimedBundles.Remove(peer);RemovePeerActivity(peer);try{ContactUnavailable?.Invoke(this,peer);}catch{/* State is already committed. */}}

    private static void CancelContactQueue(VaultTransaction db,string peer){
        db.Execute("DELETE FROM protocol_pending_events WHERE recipient=$p0",peer);db.Execute("DELETE FROM protocol_outbox WHERE recipient=$p0",peer);
        db.Execute("DELETE FROM protocol_key_requests WHERE recipient=$p0",peer);db.Execute("DELETE FROM object_transfers WHERE owner_peer=$p0",peer);
        db.Execute("UPDATE protocol_receipts SET pending=0 WHERE peer=$p0",peer);
    }

    public Task<bool> ClearMessagesAsync(string recipient,CancellationToken token=default) => UserAsync(()=>
        _store.Transaction(db=> { ClearHistory(db,recipient); return true; },notify:true),token);

    // Null means the selection cannot be deleted. False means local deletion only.
    public async Task<bool?> DeletionCanRevokeAsync(string peer, IReadOnlySet<string> ids, CancellationToken token = default)
    {
        if (ids.Count is < 1 or > 100) return null;
        var targets = System.Text.Json.JsonSerializer.Serialize(ids);
        var count = ids.Count;
        int result = 0;
        await Submit(_ =>
        {
            result = _store.Transaction(db => db.Query("""
                SELECT CASE
                    WHEN COUNT(*)<>$p0 OR MAX(m.ext='live_location' OR (m.isMine=1 AND m.status=0))=1 THEN 0
                    WHEN MIN(m.isMine=1 AND m.isRevoked=0 AND m.status BETWEEN 1 AND 3)=1
                        AND EXISTS(SELECT 1 FROM contact_states WHERE uuid=$p2 AND accepted=1 AND blocked=0) THEN 2
                    ELSE 1 END
                FROM messages m JOIN json_each($p1) s ON s.value=m.messageId
                WHERE m.conversationUuid=$p2 AND NOT EXISTS(SELECT 1 FROM message_expiration
                    WHERE message_id=m.messageId AND expires_at<=$p3)
                """, row => row.GetInt32(0), count, targets, peer, Now).Single());
            return Task.CompletedTask;
        }, token: token).ConfigureAwait(false);
        return result == 0 ? null : result == 2;
    }

    public Task<bool> DeleteLocalMessagesAsync(string peer, IReadOnlyList<string> messageIds, CancellationToken token) => UserAsync(() =>
    {
        var ids = messageIds.ToHashSet(StringComparer.Ordinal);
        if (ids.Count is < 1 or > 100) throw new VisibleFailure("Choose up to 100 messages.");
        var targets = System.Text.Json.JsonSerializer.Serialize(ids);
        _store.Transaction(db =>
        {
            var rows = db.Query("""
                SELECT m.isMine, m.status, m.ext FROM messages m
                JOIN json_each($p1) s ON s.value=m.messageId WHERE m.conversationUuid=$p0
                """, row => (Pending: row.GetInt64(0) != 0 && row.GetInt64(1) == 0, Extension: row.GetString(2)), peer, targets);
            if (rows.Count != ids.Count) throw new VisibleFailure("Some selected messages are no longer available.");
            if (rows.Any(row => row.Pending || row.Extension == "live_location"))
                throw new VisibleFailure("A pending send or live location cannot be deleted from history.");
            db.Execute("UPDATE messages SET replyToContent=NULL WHERE conversationUuid=$p0 AND replyToId IN (SELECT value FROM json_each($p1))", peer, targets);
            db.Execute("DELETE FROM message_changes WHERE conversation_uuid=$p0 AND message_id IN (SELECT value FROM json_each($p1))", peer, targets);
            return db.Execute("DELETE FROM messages WHERE conversationUuid=$p0 AND messageId IN (SELECT value FROM json_each($p1))", peer, targets);
        }, notify: true);
        NotifyMessagesRemoved(ids);
    }, token);

    public async Task<MessageRetentionSettings> MessageRetentionAsync(string peer, CancellationToken token)
    {
        MessageRetentionSettings? settings = null;
        if (!await Submit(_ =>
        {
            settings = _store.Transaction(db => MessageExpirationStore.Settings(db, peer));
            return Task.CompletedTask;
        }, token: token).ConfigureAwait(false) || settings is null)
            throw new InvalidOperationException("Could not load message retention settings.");
        return settings;
    }

    public Task<bool> SetMessageRetentionAsync(string peer, MessageRetentionSettings settings, CancellationToken token) => UserAsync(() =>
    {
        _store.Transaction(db =>
        {
            RequireAccepted(db, peer);
            MessageExpirationStore.SetSettings(db, peer, settings);
            return true;
        });
        Volatile.Write(ref _nextRetentionSweep, 0);
    }, token);

    public async Task<bool> ReadReceiptsEnabledAsync(CancellationToken token=default)
    {
        var enabled=false;
        if(!await Submit(_=>{enabled=_store.Transaction(ReadReceiptsEnabled);return Task.CompletedTask;},token:token).ConfigureAwait(false))
            throw new InvalidOperationException("Privacy settings could not be read.");
        return enabled;
    }
    public async Task<bool> AppLockEnabledAsync(CancellationToken token)
    {
        var enabled = false;
        if (!await Submit(_ =>
        {
            enabled = _store.Transaction(db => db.Exists("SELECT 1 FROM privacy_preferences WHERE id=1 AND app_lock_enabled=1"));
            return Task.CompletedTask;
        }, token: token).ConfigureAwait(false))
            throw new InvalidOperationException("App lock settings could not be loaded.");
        return enabled;
    }

    public Task<bool> SetAppLockEnabledAsync(bool enabled, CancellationToken token) => UserAsync(() =>
    {
        _store.Transaction(db => db.Execute("UPDATE privacy_preferences SET app_lock_enabled=$p0 WHERE id=1", enabled ? 1 : 0));
    }, token);

    public async Task<bool> AutomaticAttachmentDownloadsAsync(CancellationToken token)
    {
        var enabled = false;
        if (!await Submit(_ =>
        {
            enabled = _store.Transaction(AttachmentQueue.AutoDownloadEnabled);
            return Task.CompletedTask;
        }, token: token).ConfigureAwait(false))
            throw new InvalidOperationException("Download settings could not be loaded.");
        return enabled;
    }

    public async Task<AttachmentCacheUsage> AttachmentCacheUsageAsync(CancellationToken token)
    {
        AttachmentCacheUsage? usage = null;
        if (!await Submit(_ =>
        {
            usage = _store.Transaction(EncryptedObjectStore.CacheUsage);
            return Task.CompletedTask;
        }, token: token).ConfigureAwait(false) || usage is null)
            throw new InvalidOperationException("Storage usage could not be loaded.");
        return usage;
    }

    public Task<bool> ClearAttachmentCacheAsync(IReadOnlyCollection<AttachmentCacheCategory> categories, CancellationToken token) => UserAsync(() =>
    {
        var count = _store.Transaction(db => EncryptedObjectStore.QueueCacheClear(db, categories));
        if (count == 0) return;
        _store.NotifyBusinessChanges(new AttachmentCacheChangedEventArgs());
        if (_storageWake.CurrentCount == 0) _storageWake.Release();
    }, token);

    public Task<bool> SetAutomaticCacheLimitAsync(long bytes, CancellationToken token) => UserAsync(() =>
    {
        if (!EncryptedObjectStore.AutomaticCacheLimits.Contains(bytes)) throw new ArgumentOutOfRangeException(nameof(bytes));
        _store.Transaction(db => db.Execute("UPDATE privacy_preferences SET auto_download_limit_bytes=$p0 WHERE id=1", bytes));
        _attachments.Wake();
    }, token);

    public Task<bool> SetAttachmentDownloadRequestedAsync(string peer, string messageId, bool requested, CancellationToken token) => UserAsync(() =>
    {
        var changed = _store.Transaction(db => AttachmentQueue.SetDownloadRequested(db, peer, messageId, requested));
        if (!changed && requested)
            throw new VisibleFailure("This attachment is no longer waiting to download.");
        if (changed) _store.NotifyBusinessChanges();
        _attachments.Wake();
    }, token);

    internal Task<bool> SetPreviewDownloadRequestedAsync(string peer, string messageId, string reference, bool requested, bool replacingCache,
        CancellationToken token) => UserAsync(() =>
    {
        if (replacingCache && !requested) throw new ArgumentException("Cache replacement requires a download request.", nameof(requested));
        _store.Transaction(db =>
        {
            if (WebPreviewStore.Read(db, messageId)?.ImageReference != reference ||
                replacingCache && !EncryptedObjectStore.QueueCacheReplacement(db, peer, messageId, Now, previewOnly: true) ||
                !AttachmentQueue.SetDownloadRequested(db, peer, messageId, requested: requested, previewOnly: true))
                throw new VisibleFailure("The preview image is no longer waiting to download.");
            return true;
        });
        _store.NotifyBusinessChanges(new AttachmentCacheChangedEventArgs(messageId));
        if (!replacingCache) _attachments.Wake();
        else if (_storageWake.CurrentCount == 0) _storageWake.Release();
    }, token);

    public Task<bool> ReplaceAttachmentCacheAsync(string peer, string messageId, string? expectedReference, CancellationToken token) => UserAsync(() =>
    {
        _store.Transaction(db =>
        {
            if (expectedReference is not null && !db.Exists(
                    "SELECT 1 FROM messages WHERE messageId=$p0 AND conversationUuid=$p1 AND content=$p2",
                    messageId, peer, expectedReference))
                throw new VisibleFailure("This attachment cannot be downloaded again.");
            if (!EncryptedObjectStore.QueueCacheReplacement(db, peer, messageId, Now) ||
                !AttachmentQueue.SetDownloadRequested(db, peer, messageId, true))
                throw new VisibleFailure("This attachment cannot be downloaded again.");
            return true;
        }, notify: true);
        if (_storageWake.CurrentCount == 0) _storageWake.Release();
    }, token);

    public Task<bool> SetReadReceiptsEnabledAsync(bool enabled,ActivityPrivacy? activity=null,CancellationToken token=default,bool? autoDownload=null)=>UserAsync(()=>
    {
        List<string> dirty=[];
        _store.Transaction(db=>
        {
            db.Execute("UPDATE privacy_preferences SET read_receipts=$p0 WHERE id=1",enabled ? 1 : 0);
            if (autoDownload is { } download)
                db.Execute("UPDATE privacy_preferences SET auto_download_attachments=$p0 WHERE id=1", download ? 1 : 0);
            if(!enabled)
            {
                db.Execute("WITH undisclosed AS (SELECT event_id FROM protocol_receipts WHERE state='read' AND pending=1 UNION SELECT p.event_id FROM protocol_pending_events p JOIN protocol_receipts r ON r.event_id=p.event_id WHERE r.state='read' UNION SELECT b.event_id FROM protocol_outbox b JOIN protocol_receipts r ON r.event_id=b.event_id WHERE b.delivered IN (0,-2) AND r.state='read') INSERT OR IGNORE INTO read_receipt_suppression(peer,message_id) SELECT r.peer,r.message_id FROM undisclosed u JOIN protocol_receipts r ON r.event_id=u.event_id");
                db.Execute("WITH undisclosed AS (SELECT event_id FROM protocol_receipts WHERE state='read' AND pending=1 UNION SELECT p.event_id FROM protocol_pending_events p JOIN protocol_receipts r ON r.event_id=p.event_id WHERE r.state='read' UNION SELECT b.event_id FROM protocol_outbox b JOIN protocol_receipts r ON r.event_id=b.event_id WHERE b.delivered IN (0,-2) AND r.state='read') UPDATE protocol_receipts SET pending=1 WHERE event_id IN (SELECT d.event_id FROM undisclosed u JOIN protocol_receipts r ON r.event_id=u.event_id JOIN protocol_receipts d ON d.peer=r.peer AND d.message_id=r.message_id AND d.state='delivered')");
                db.Execute("DELETE FROM protocol_pending_events WHERE event_id IN (SELECT p.event_id FROM protocol_pending_events p JOIN protocol_receipts r ON r.event_id=p.event_id WHERE r.state='read')");
                db.Execute("UPDATE protocol_outbox SET delivered=-1 WHERE delivered IN (0,-2) AND EXISTS(SELECT 1 FROM protocol_receipts r WHERE r.event_id=protocol_outbox.event_id AND r.state='read')");
                db.Execute("UPDATE protocol_receipts SET pending=0 WHERE state='read' AND pending=1");
            }
            if(activity is not null){
                db.Execute("UPDATE privacy_preferences SET share_online=$p0,share_typing=$p1 WHERE id=1",activity.Online?1:0,activity.Typing?1:0);
                dirty=db.Query("SELECT peer FROM activity_peers WHERE json_extract(state_json,'$.published.body.online') IS NOT NULL OR json_extract(state_json,'$.published.body.typing')=1 LIMIT 128",r=>r.GetString(0));
            }
            return true;
        });
        if (activity is not null)
        {
            lock (_activityGate)
            {
                foreach (var peer in dirty) _activityDirty.Add(peer);
                while (_activityDirty.Count > 128) _activityDirty.Remove(_activityDirty.First());
            }
            _activityOnline = activity.Online;
            _activityTyping = activity.Typing;
            _activityLoaded = true;
            NotifyPeerActivityChanged(null);
        }
        if (autoDownload is not null) _attachments.Wake();
    },token);

    public Task<bool> MarkAllReadAsync(string recipient,CancellationToken token=default)=>UserAsync(()=>{
        _store.Transaction(db =>
        {
            var disclose = ReadReceiptsEnabled(db);
            while (true)
            {
                var ids = db.Query("""
                    SELECT m.messageId FROM messages m
                    WHERE m.conversationUuid=$p0 AND m.isMine=0 AND m.isRead=0 AND m.isRevoked=0
                      AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p1)
                      AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                          AND m.timestamp<=$p1-r.duration_ms)
                    ORDER BY m.timestamp,m.messageId LIMIT 100
                    """, row => row.GetString(0), recipient, Now);
                if (ids.Count == 0) break;
                foreach (var id in ids) RecordRead(db, recipient, id, disclose);
            }
            db.Execute("UPDATE conversation_preferences SET manual_unread=0 WHERE peer=$p0", recipient);
            return true;
        }, notify: true);
    },token);

    public Task<bool> MarkReadAsync(string recipient,IReadOnlyCollection<string> visibleIds,CancellationToken token=default)
    {
        ArgumentNullException.ThrowIfNull(visibleIds);
        if(visibleIds.Count>100)throw new ArgumentOutOfRangeException(nameof(visibleIds));
        var ids=visibleIds.Distinct(StringComparer.Ordinal).ToArray();
        if(ids.Length==0)return Task.FromResult(true);
        return UserAsync(()=>
        {
            var readIds = _store.Transaction(db =>
            {
                var disclose = ReadReceiptsEnabled(db);
                var read = new HashSet<string>(StringComparer.Ordinal);
                var at = Now;
                foreach (var id in ids)
                {
                    if (!db.Exists("""
                        SELECT 1 FROM messages m
                        WHERE m.messageId=$p0 AND m.conversationUuid=$p1 AND m.isMine=0 AND m.isRead=0 AND m.isRevoked=0
                          AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p2)
                          AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                              AND m.timestamp<=$p2-r.duration_ms)
                        """, id, recipient, at)) continue;
                    RecordRead(db, recipient, id, disclose);
                    read.Add(id);
                }
                return read;
            });
            if (readIds.Count != 0) _store.NotifyBusinessChanges(new VisibleMessagesReadEventArgs(recipient, readIds));
        },token);
    }

    private static void RecordRead(VaultTransaction db, string peer, string id, bool disclose)
    {
        if (!disclose)
            db.Execute("INSERT OR IGNORE INTO read_receipt_suppression(peer,message_id) VALUES($p0,$p1)", peer, id);
        Receipt(db, peer, id, "read");
        db.Execute("UPDATE messages SET isRead=1 WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=0", id, peer);
    }

    public Task<bool> BroadcastProfileAsync(CancellationToken token=default) => UserAsync(() =>
        _store.Transaction(db =>
        {
            foreach (var peer in db.Query("SELECT uuid FROM contact_states WHERE accepted=1 AND blocked=0",r => r.GetString(0)))
                Enqueue(PendingEvent(peer,"contact.profile",Profile(db)),db);
            return true;
        }),token);

    public UserProfileSnapshot? LocalProfile => _store.Transaction(db=>UserProfileStore.Read(db,_settings.MyUuid));
    public Task<bool> UpdateProfileAsync(string nickname,ReadOnlyMemory<byte>? jpeg=null,bool removeAvatar=false,CancellationToken token=default)
    {
        lock(_importGate)
        {
            ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed)!=0,this);
            var task=UpdateProfileCoreAsync(nickname,jpeg,removeAvatar,token);_imports.Add(task);return ObserveForwardAsync(task);
        }
    }
    private async Task<bool> UpdateProfileCoreAsync(string nickname,ReadOnlyMemory<byte>? jpeg,bool removeAvatar,CancellationToken token)
    {
        using var lifetime=CancellationTokenSource.CreateLinkedTokenSource(token,_lifetime.Token);
        JsonObject? avatar=null;var committed=false;
        try
        {
            if(jpeg is { } bytes)
            {
                if(bytes.Length is <1 or >512*1024)throw new InvalidDataException("Profile image exceeds its size limit.");
                avatar=await _objects.ImportBytesAsync(bytes,"avatar.jpg","image/jpeg",lifetime.Token).ConfigureAwait(false);
            }
            return await UserAsync(()=>
            {
                _store.Transaction(db=>
                {
                    lifetime.Token.ThrowIfCancellationRequested();
                    UserProfileStore.SaveLocal(db,_settings.MyUuid,nickname,avatar,removeAvatar||avatar is not null);
                    foreach(var peer in db.Query("SELECT uuid FROM contact_states WHERE accepted=1 AND blocked=0",r=>r.GetString(0)))
                        Enqueue(PendingEvent(peer,"contact.profile",Profile(db)),db);
                    return true;
                },notify:true);
                committed=true;_attachments.Wake();
            },lifetime.Token).ConfigureAwait(false);
        }
        finally {if(!committed && avatar is not null)_objects.DiscardCopy(avatar);}
    }

    private Task<bool> UserAsync(Action operation,CancellationToken token=default) => Submit(async _ =>
    {
        operation();NotifySendQueueChanged(); // A thrown business/crypto error rolls its transaction back.
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
            if (packet.Type==GhostPacket.OfflineEnd) { await BeginSyncAsync(connection,packet.NonceOrIv,ct).ConfigureAwait(false); return; }
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
                case GhostPacket.V2Deferred:
                    if (packet.EncryptedData.Length != 0) throw new InvalidDataException("Invalid relay deferral.");
                    var deferredRecipient = packet.TargetMailboxId ?? throw new InvalidDataException("Missing deferral recipient.");
                    var deferredId = EventId(packet.NonceOrIv);
                    if (_inFlight.Contains(deferredId) && _store.Transaction(db => db.Execute("""
                        INSERT INTO protocol_relay_deferrals(recipient,retry_at)
                        SELECT recipient,$p0 FROM protocol_outbox WHERE event_id=$p1 AND recipient=$p2 AND delivered=0
                        ON CONFLICT(recipient) DO UPDATE SET retry_at=excluded.retry_at
                        """, Now + 30_000, deferredId, deferredRecipient) > 0))
                    {
                        _inFlight.Remove(deferredId);
                        NotifySendQueueChanged();
                        await FlushAsync(ct).ConfigureAwait(false);
                    }
                    break;
                case GhostPacket.V2Rejected:
                    var rejectedPeer=packet.TargetMailboxId ?? throw new InvalidDataException("Missing rejection target.");
                    var rejectedId=EventId(packet.NonceOrIv);
                    if(packet.EncryptedData.Length!=113) throw new InvalidDataException("Invalid revocation proof.");
                    if(_store.Transaction(db=>db.Exists("SELECT 1 FROM protocol_outbox WHERE event_id=$p0 AND recipient=$p1",rejectedId,rejectedPeer)))
                    {
                        MarkPeerRevoked(rejectedPeer,Convert.ToBase64String(packet.EncryptedData));
                        await FlushAsync(ct).ConfigureAwait(false);
                    }
                    break;
                case GhostPacket.V2Stored:
                    if (packet.EncryptedData.Length != 0) throw new InvalidDataException("Invalid durable acknowledgement.");
                    var recipient=packet.TargetMailboxId ?? throw new InvalidDataException("Missing acknowledgement target.");
                    var id=EventId(packet.NonceOrIv);
                    var businessStored = _store.Transaction<int?>(db =>
                    {
                        var matched = db.Execute("UPDATE protocol_outbox SET delivered=1 WHERE event_id=$p0 AND recipient=$p1 AND delivered>=0",id,recipient);
                        if (matched == 0) return null;
                        db.Execute("DELETE FROM object_transfers WHERE event_id=$p0 AND direction=0", id);
                        db.Execute("DELETE FROM live_location_outgoing WHERE event_id=$p0 AND peer=$p1", id, recipient);
                        var stored = db.Execute("UPDATE messages SET status=1 WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=1 AND isRevoked=0 AND status=0",id,recipient);
                        if (stored > 0)
                            db.Execute("""
                                INSERT OR IGNORE INTO message_delivery_retries(message_id,retry_at)
                                SELECT message_id,$p0 FROM message_sends WHERE message_id=$p1 AND kind<>'chat.location.start'
                                """, Now + DeliveryRetryDelay, id);
                        return stored;
                    });
                    if (businessStored is null) break;
                    if (businessStored > 0)
                    {
                        _store.NotifyBusinessChanges(new MessageDeliveryChangedEventArgs(recipient, id));
                        NotifySendQueueChanged();
                    }
                    _inFlight.Remove(id);
                    await FlushAsync(ct).ConfigureAwait(false);
                    break;
                case GhostPacket.V2Delivery:
                    var sender=packet.TargetMailboxId ?? throw new InvalidDataException("Missing authenticated sender.");
                    var eventId=EventId(packet.NonceOrIv);
                    var notify=false;var silent=false;
                    string? pinTarget = null;
                    CallSignal? call=null;var contactUnavailable=false;var contactsChanged=false;var businessChanged=false;JsonObject? activityObservation=null;
                    MessageDeliveryChangedEventArgs? deliveryChange = null;
                    Delivery CommitDelivery() => _store.Receive(packet.EncryptedData,sender,eventId,(incoming,db) =>
                    {
                        if (incoming.Kind == "chat.activity") activityObservation = ReceiveActivity(incoming, db);
                        else
                        {
                            Apply(incoming, db, out deliveryChange, target => pinTarget = target);
                            businessChanged = incoming.Kind != "chat.receipt";
                        }
                        contactsChanged=incoming.Kind.StartsWith("contact.",StringComparison.Ordinal);
                        contactUnavailable=contactsChanged && !db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1 AND blocked=0",sender);
                        if (incoming.Kind.StartsWith("call.",StringComparison.Ordinal) && db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1 AND blocked=0",sender))
                            call=new CallSignal(sender,incoming.Kind,incoming.Body);
                        silent=incoming.Body["silent"]?.GetValue<bool>()==true;
                        notify=incoming.Kind is "chat.text" or "chat.contact" or "chat.location" or "chat.location.start" or "chat.attachment" && db.Exists("SELECT 1 FROM messages WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=0 AND isRead=0",eventId,sender);
                    },RescheduleReceipt,notifyBusiness:false);
                    Delivery delivery;
                    try { delivery = CommitDelivery(); }
                    catch (Exception error) when (error is InvalidDataException or CryptographicException)
                    {
                        throw new RelayClient.UnprocessedDeliveryException(error);
                    }
                    if (delivery == Delivery.Deferred) throw new RelayClient.UnprocessedDeliveryException();
                    var fresh = delivery == Delivery.Applied;
                    if (fresh && (businessChanged || deliveryChange is not null))
                    {
                        _store.NotifyBusinessChanges(deliveryChange);
                        NotifySendQueueChanged();
                    }
                    if(fresh && activityObservation is not null)PublishActivity(sender,activityObservation);
                    if(fresh && contactsChanged)NotifyContactsChanged();
                    if(fresh && contactUnavailable)NotifyContactUnavailable(sender);
                    if (fresh && call is not null) { try { CallReceived?.Invoke(call); } catch { /* Observer failures cannot roll back Signal state. */ } }
                    if (fresh && (notify || pinTarget is not null)) PublishNotification(sender,pinTarget ?? eventId,silent,pinTarget is not null);
                    _attachments.Wake();
                    // A failed acknowledgement can only replay a duplicate of the committed event.
                    await SendAsync(GhostPacket.V2Received,sender,EventBytes(eventId),[],ct).ConfigureAwait(false);
                    await FlushAsync(ct).ConfigureAwait(false);
                    break;
                case GhostPacket.V2SyncEnd:
                    if (packet.EncryptedData.Length != 0 || packet.TargetMailboxId != _settings.MyUuid)
                        throw new InvalidDataException("Invalid synchronization acknowledgement.");
                    if (_syncNonce is not { } nonce || !nonce.AsSpan().SequenceEqual(packet.NonceOrIv)) break;
                    _syncNonce = null;
                    _relay.CompleteSync(connection);
                    await FlushAsync(ct).ConfigureAwait(false);
                    break;
            }
        },token:token).ConfigureAwait(false);
    }

    private async Task BeginSyncAsync(long connection,byte[] directoryNonce,CancellationToken token)
    {
        if (connection<_epoch) return;
        _epoch=connection; _ready=false; _inFlight.Clear(); _requested.Clear();_claimedBundles.Clear();
        _syncNonce = null;
        if (directoryNonce.Length != 16 || !directoryNonce.Any(value => value != 0))
            throw new InvalidDataException("Missing relay directory epoch.");
        _directoryEpoch = Convert.ToBase64String(directoryNonce);
        var bundle=_store.PublicBundle(directoryEpoch: _directoryEpoch);
        _publishedBody=Text(bundle,"body"); _nextMaintenance=Now+3600000;
        await SendAsync(GhostPacket.V2KeyPublish,_settings.MyMailboxId,new byte[16],Json(bundle),token).ConfigureAwait(false);
    }

    private static MessageDeliveryChangedEventArgs? ApplyDeliveryReceipt(Incoming incoming, VaultTransaction db)
    {
        var sender = incoming.Sender;
        var id = Text(incoming.Body, "message_id");
        var status = Text(incoming.Body, "state") switch
        {
            "delivered" => 2,
            "read" => 3,
            _ => throw new InvalidDataException("Invalid encrypted receipt state.")
        };
        AcknowledgeActivity(db, sender, id);
        if (!db.Exists("SELECT 1 FROM messages WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=1", id, sender))
            return null;
        db.Execute("UPDATE protocol_outbox SET delivered=1 WHERE event_id=$p0 AND recipient=$p1 AND delivered>=0", id, sender);
        db.Execute("DELETE FROM object_transfers WHERE event_id=$p0 AND owner_peer=$p1 AND direction=0", id, sender);
        var changed = db.Execute("UPDATE messages SET status=$p2 WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=1 AND isRevoked=0 AND status<$p2",
            id, sender, status);
        return changed > 0 ? new MessageDeliveryChangedEventArgs(sender, id) : null;
    }

    private void Apply(Incoming incoming, VaultTransaction db, out MessageDeliveryChangedEventArgs? deliveryChange, Action<string> onPinNotify)
    {
        deliveryChange = null;
        var sender=incoming.Sender;
        if (incoming.Kind != "chat.receipt" && db.Exists("SELECT 1 FROM protocol_revocations WHERE peer=$p0", sender)) return;
        var arrival = Now;
        var deadline = incoming.Body["expire_after_ms"] is JsonValue ttl
            ? Math.Min(incoming.CreatedAt, arrival) + ttl.GetValue<long>() : (long?)null;
        if (deadline is { } expires)
        {
            db.Execute("""
                DELETE FROM message_changes WHERE author=$p0 AND conversation_uuid=$p0 AND message_id=$p1
                  AND ($p2<=$p3 OR json_extract(state_json,'$.revoked')=0)
                """, sender, incoming.EventId, expires, arrival);
            if (expires <= arrival) return;
        }
        if (MessageExpirationStore.DiscardIncoming(db, sender, incoming.Kind, incoming.CreatedAt, arrival))
        {
            db.Execute("DELETE FROM message_changes WHERE author=$p0 AND conversation_uuid=$p0 AND message_id=$p1",
                sender, incoming.EventId);
            Receipt(db, sender, incoming.EventId, "delivered");
            return;
        }
        switch (incoming.Kind)
        {
            case "call.offer": case "call.answer": case "call.ice": case "call.challenge": case "call.confirm": case "call.reject": case "call.end": case "call.busy":
                break; // Dispatch only after Signal and database commit.

            case "contact.request":
                if(!ContactStateStore.Apply(db,sender,new JsonObject{["kind"]="receive_request",["id"]=incoming.Body["request_id"]!.DeepClone(),["revision"]=incoming.Body["request_revision"]!.DeepClone()})["applied"]!.GetValue<bool>())return;
                var requestedProfile=UserProfileStore.Apply(db,sender,incoming.Body,incoming.EventId,incoming.CreatedAt);
                db.Execute("INSERT INTO friend_requests(senderUuid,nickname,avatarPath,peerPublicKey,timestamp) VALUES($p0,$p1,$p2,'',$p3) ON CONFLICT(senderUuid) DO UPDATE SET nickname=excluded.nickname,avatarPath=excluded.avatarPath,timestamp=excluded.timestamp",sender,requestedProfile.Nickname,requestedProfile.AvatarReference??"",incoming.CreatedAt);break;
            case "contact.accept":
                if(!ContactStateStore.Apply(db,sender,new JsonObject{["kind"]="receive_accept",["id"]=incoming.Body["request_id"]!.DeepClone()})["applied"]!.GetValue<bool>())return;
                var acceptedProfile=UserProfileStore.Apply(db,sender,incoming.Body,incoming.EventId,incoming.CreatedAt);
                db.Execute("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES($p0,$p1,$p2,'') ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias,avatarPath=excluded.avatarPath",sender,acceptedProfile.Nickname,acceptedProfile.AvatarReference??"");break;
            case "contact.decline":
                ContactStateStore.Apply(db,sender,new JsonObject{["kind"]="receive_decline",["id"]=incoming.Body["request_id"]!.DeepClone()});break;
            case "contact.withdraw":
                ContactStateStore.Apply(db,sender,new JsonObject{["kind"]="receive_withdraw",["id"]=incoming.Body["request_id"]!.DeepClone(),["revision"]=incoming.Body["request_revision"]!.DeepClone()});break;
            case "contact.profile":
                if(!db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1 AND blocked=0",sender))return;
                var changedProfile=UserProfileStore.Apply(db,sender,incoming.Body,incoming.EventId,incoming.CreatedAt);
                db.Execute("UPDATE conversations SET alias=$p0,avatarPath=$p1 WHERE uuid=$p2",changedProfile.Nickname,changedProfile.AvatarReference??"",sender);
                break;
            case "contact.delete":
                ContactStateStore.Apply(db,sender,new JsonObject{["kind"]="receive_delete",["ids"]=incoming.Body["relationship_ids"]?.DeepClone()??new JsonArray()});break;
            case "chat.text":
            case "chat.contact":
            case "chat.location":
            case "chat.location.start":
                var isContact=incoming.Kind=="chat.contact";
                var isLive=incoming.Kind=="chat.location.start";
                var location=incoming.Kind=="chat.location"?LocationPoint.Parse(incoming.Body):null;
                var text=location is not null || isLive ? incoming.Body["label"]?.GetValue<string>() ?? (isLive ? "Live location" : "Location")
                    : Text(incoming.Body,isContact?"name":"text");
                if (text.Length==0 || Encoding.UTF8.GetByteCount(text)>48*1024) throw new InvalidDataException("Invalid text message size.");
                if (Blocked(db,sender) || !db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1",sender)) return;
                var reply=incoming.Body["reply"] as JsonObject;
                if (reply is not null)
                {
                    Validate(Text(reply,"event_id"),"reply",0);
                    if (Text(reply,"author")!=sender && Text(reply,"author")!=_settings.MyUuid) throw new InvalidDataException("Invalid quote author.");
                    if (Encoding.UTF8.GetByteCount(Text(reply,"preview"))>4096) throw new InvalidDataException("Quote preview is too large.");
                }
                if (isLive && !LiveLocationStore.Apply(db,sender,false,incoming.Kind,incoming.EventId,incoming.Body,incoming.CreatedAt,Now)) return;
                InsertText(db,incoming.EventId,sender,false,text,Math.Min(incoming.CreatedAt,arrival),reply,incoming.Body["preview"] as JsonObject,
                    isContact?Text(incoming.Body,"account"):null,location,isLive,allowEdit:deadline is null);
                SaveForward(db,incoming.EventId,incoming.Body["forward"] as JsonObject);
                Receipt(db,sender,incoming.EventId,"delivered");
                break;
            case "chat.location.update":
            case "chat.location.end":
                if (Blocked(db,sender) || !db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1",sender)) return;
                LiveLocationStore.Apply(db,sender,false,incoming.Kind,incoming.EventId,incoming.Body,incoming.CreatedAt,Now);
                break;
            case "chat.attachment":
                if(Blocked(db,sender) || !db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1",sender)) return;
                var attachment=incoming.Body["object"]?.AsObject()??throw new InvalidDataException("Missing encrypted attachment manifest.");
                var caption=incoming.Body["caption"]?.GetValue<string>()??"";
                if(Encoding.UTF8.GetByteCount(caption)>48*1024) throw new InvalidDataException("Attachment caption is too large.");
                var attachmentReply=incoming.Body["reply"] as JsonObject;
                ValidateQuote(attachmentReply,sender);
                InsertAttachment(db,incoming.EventId,sender,false,attachment,caption,Math.Min(incoming.CreatedAt,arrival),attachmentReply,incoming.Body["sticker"]?.GetValue<bool>()==true,incoming.Body["album"] as JsonObject,allowEdit:deadline is null);
                SaveForward(db,incoming.EventId,incoming.Body["forward"] as JsonObject);
                Receipt(db,sender,incoming.EventId,"delivered");
                break;
            case "chat.receipt":
                deliveryChange = ApplyDeliveryReceipt(incoming, db);
                break;
            case "chat.pin":
                if(Blocked(db,sender) || !db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1",sender))return;
                MessagePinsStore.Apply(db,sender,_settings.MyUuid,sender,incoming.Body,onPinNotify);
                break;
            case "chat.edit":
            case "chat.revoke":
                if(Blocked(db,sender) || !db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1",sender)) return;
                var target=Text(incoming.Body,"message_id");
                if (incoming.Kind == "chat.edit" && MessageExpirationStore.IsExpiring(db, target)) return;
                var change=new JsonObject { ["revision"]=Number(incoming.Body,"revision"),
                    ["revoked"]=incoming.Kind=="chat.revoke", ["text"]=incoming.Kind=="chat.edit"?Text(incoming.Body,"text"):null };
                if (incoming.Body["caption"]?.GetValue<bool>() == true) change["caption"] = true;
                SaveChange(db,sender,sender,false,target,change);
                break;
            default: throw new VisibleFailure("An encrypted event requires a newer MsgAT implementation.");
        }
        if (deadline is { } due)
        {
            MessageExpirationStore.Record(db, incoming.EventId, due);
            Volatile.Write(ref _nextExpiration, Math.Min(_nextExpiration, due));
        }
    }

    private long StampExpiration(VaultTransaction db, string peer, JsonObject body)
    {
        var duration = MessageExpirationStore.Duration(db, peer);
        if (duration > 0)
        {
            body["expire_after_ms"] = duration;
            Volatile.Write(ref _nextExpiration, 0);
        }
        return duration;
    }

    private void RemoveExpiredMessages()
    {
        var time = Now;
        var sweep = _nextRetentionSweep <= time;
        var marked = 0;
        var (next, ids) = _store.Transaction(db =>
        {
            if (sweep) marked = MessageExpirationStore.MarkDueRetention(db, time);
            return MessageExpirationStore.RemoveDue(db, time);
        });
        if (sweep) Volatile.Write(ref _nextRetentionSweep, marked >= 64 ? time : time + 60_000);
        Volatile.Write(ref _nextExpiration, next);
        if (ids.Count == 0) return;
        _store.NotifyBusinessChanges();
        NotifySendQueueChanged();
        NotifyMessagesRemoved(ids);
    }

    private void NotifyMessagesRemoved(IReadOnlySet<string> ids)
    {
        try { MessagesRemoved?.Invoke(this, ids); }
        catch (Exception error) { System.Diagnostics.Trace.TraceWarning("Message removal observer failed ({0})", error.GetType().Name); }
    }

    private async Task FlushAsync(CancellationToken token, bool recoverReceipts = false)
    {
        if (Math.Min(_nextExpiration, _nextRetentionSweep) <= Now) RemoveExpiredMessages();
        if (_nextExpiration <= Now) return;
        if (!_ready || _epoch!=_relay.ConnectionEpoch) return;
        if (recoverReceipts) RecoverMissingDeliveryReceipts();
        Volatile.Write(ref _nextRelayRetryAt, _store.Transaction(db =>
        {
            var time = Now;
            LiveLocationStore.PruneOutgoing(db, time);
            db.Execute("DELETE FROM protocol_relay_deferrals WHERE retry_at<=$p0 OR retry_at>$p1", time, time + 30_000);
            return db.Query("SELECT MIN(retry_at) FROM protocol_relay_deferrals",
                row => row.IsDBNull(0) ? long.MaxValue : row.GetInt64(0)).Single();
        }));
        if(_inFlight.Count>0){var active=_store.Transaction(db=>_inFlight.Where(id=>db.Exists("SELECT 1 FROM protocol_outbox WHERE event_id=$p0 AND delivered=0",id)).ToArray());_inFlight.IntersectWith(active);}
        if(ShouldPrepareActivity())PrepareActivity();
        ReleaseScheduledSends();
        PrepareReceipts();
        _attachments.Wake();
        var cached=_store.PendingOutgoing(_attachments.LeaseSession,AttachmentQueue.MinimumLease,8);
        foreach(var pending in cached)if(!_inFlight.Contains(pending.EventId))await AttemptSendAsync(pending,token).ConfigureAwait(false);
        var slots=8-cached.Count;
        if(slots<=0)return;
        var waitingForKeys = _requested.Keys.Concat(_unavailableUntil.Where(peer => peer.Value > Now).Select(peer => peer.Key))
            .Except(_claimedBundles.Keys);
        foreach(var pending in _store.PendingEvents(_attachments.LeaseSession,AttachmentQueue.MinimumLease,waitingForKeys:waitingForKeys))
        {
            if(slots<=0)break;
            var claimed=_claimedBundles.TryGetValue(pending.Recipient,out var claim);
            if (claimed && claim.ExpiresAt <= Now / 1000)
            {
                _store.FinishKeyRequest(pending.Recipient, claim.Request);
                _claimedBundles.Remove(pending.Recipient);
                claimed = false;
            }
            if(_store.HasSession(pending.Recipient) || claimed)
            {
                var sealedMessage=_store.Prepare(pending,claimed?claim.Bundle:null,claimed?claim.Selected:null);
                if(claimed){_store.FinishKeyRequest(pending.Recipient,claim.Request);_claimedBundles.Remove(pending.Recipient);}
                await AttemptSendAsync(sealedMessage,token).ConfigureAwait(false);slots--;
            }
            else if(!_requested.ContainsKey(pending.Recipient) && _unavailableUntil.GetValueOrDefault(pending.Recipient)<Now)
            {
                var id=_store.KeyRequestId(pending.Recipient);
                await SendAsync(GhostPacket.V2KeyClaim,_settings.MyMailboxId,new byte[16],Json(new JsonObject{["request_id"]=id,["uuid"]=pending.Recipient}),token).ConfigureAwait(false);
                _requested[pending.Recipient]=(id,Now);
            }
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
            if(response["revocation"] is JsonValue proof) { MarkPeerRevoked(recipient,proof.GetValue<string>()); await FlushAsync(token).ConfigureAwait(false); return; }
            _unavailableUntil[recipient]=Now+300000;
            Report(new VisibleFailure("The recipient has no available key bundle. Your message remains protected in the local vault."));
            return;
        }
        var bundle=response["bundle"]!.AsObject();
        var verified = Call(new JsonObject { ["operation"]="verify_bundle",["bundle"]=bundle.DeepClone(),["expected_uuid"]=recipient });
        var verifiedBody = verified["bundle"]!.AsObject();
        var expiry = Number(verifiedBody, "expires_at");
        var selected=response["selected_prekey"]?.GetValue<long>();
        if (selected is not null && verifiedBody["directory_epoch"]?.GetValue<string>() != _directoryEpoch)
            throw new InvalidDataException("One-time key is from an older directory epoch.");
        _claimedBundles[recipient]=(bundle.DeepClone().AsObject(),selected,id,expiry);
        while(_claimedBundles.Count>128)_claimedBundles.Remove(_claimedBundles.Keys.First());
        await FlushAsync(token).ConfigureAwait(false);
    }

    private void MarkPeerRevoked(string peer,string proof)
    {
        if(proof.Length!=152) throw new InvalidDataException("Invalid revocation proof.");
        var checkedProof=Call(new JsonObject { ["operation"]="verify_revocation",["proof"]=proof });
        if(Text(checkedProof,"uuid")!=peer) throw new InvalidDataException("Revocation does not match recipient.");
        var fresh=_store.Transaction(db=>
        {
            var fresh=!db.Exists("SELECT 1 FROM protocol_revocations WHERE peer=$p0",peer);
            db.Execute("INSERT OR IGNORE INTO protocol_revocations(peer,proof) VALUES($p0,$p1)",peer,proof);
            db.Execute("DELETE FROM message_sends WHERE message_id IN (SELECT messageId FROM messages WHERE conversationUuid=$p0)",peer);
            db.Execute("DELETE FROM activity_watchers WHERE peer=$p0",peer);
            db.Execute("UPDATE activity_peers SET state_json=json_set(state_json,'$.inflight',NULL,'$.published',NULL) WHERE peer=$p0",peer);
            db.Execute("UPDATE contact_states SET accepted=0,incoming=0,outgoing=0 WHERE uuid=$p0",peer);
            db.Execute("DELETE FROM friend_requests WHERE senderUuid=$p0",peer);
            db.Execute("UPDATE messages SET status=-1 WHERE conversationUuid=$p0 AND isMine=1 AND status IN (0,1)",peer);
            db.Execute("UPDATE protocol_outbox SET delivered=-1 WHERE recipient=$p0 AND delivered IN (0,-2)",peer);
            db.Execute("DELETE FROM protocol_pending_events WHERE recipient=$p0",peer);
            db.Execute("DELETE FROM protocol_key_requests WHERE recipient=$p0",peer);
            db.Execute("""
                DELETE FROM protocol_outbox WHERE recipient=$p0 AND event_id IN
                    (SELECT event_id FROM protocol_receipts WHERE peer=$p0)
                """,peer);
            db.Execute("DELETE FROM protocol_receipts WHERE peer=$p0",peer);
            db.Execute("DELETE FROM live_location_outgoing WHERE peer=$p0",peer);
            db.Execute("DELETE FROM object_transfers WHERE owner_peer=$p0",peer);
            return fresh;
        },notify:true);
        _claimedBundles.Remove(peer);_requested.Remove(peer); _unavailableUntil.Remove(peer); _inFlight.Clear();
        if(fresh)
        {
            NotifyContactsChanged();
            Report(new VisibleFailure("The recipient reset their identity. Pending delivery is unconfirmed; retries stopped."));
            RemovePeerActivity(peer);
            try { PeerRevoked?.Invoke(this,peer); } catch { /* Observer errors cannot roll back a verified revocation. */ }
        }
    }

    private async Task MaintainObjectsAsync()
    {
        try
        {
            while(!_lifetime.IsCancellationRequested)
            {
                var count=0;
                try{count=_objects.CollectGarbage();}
                catch(Exception error){System.Diagnostics.Trace.TraceWarning("Encrypted storage maintenance will retry ({0})",error.GetType().Name);}
                if (count > 0) _attachments.Wake();
                await _storageWake.WaitAsync(count>=64?TimeSpan.FromMilliseconds(250):TimeSpan.FromSeconds(30),_lifetime.Token).ConfigureAwait(false);
            }
        }
        catch(OperationCanceledException) when(_lifetime.IsCancellationRequested){}
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
                    _store.Transaction(db => { PruneIncoming(db, Now); return true; });
                    if (Now>=_nextMaintenance)
                    {
                        _store.Transaction(db=>{LiveLocationStore.PruneOrphans(db,Now);return true;});
                        var bundle=_store.PublicBundle(directoryEpoch: _directoryEpoch ?? throw new InvalidOperationException("Missing relay directory epoch."));
                        _nextMaintenance=Now+3600000;
                        if (_publishedBody!=Text(bundle,"body"))
                        {
                            await SendAsync(GhostPacket.V2KeyPublish,_settings.MyMailboxId,new byte[16],Json(bundle),token).ConfigureAwait(false);
                            _publishedBody=Text(bundle,"body");
                        }
                    }
                    await FlushAsync(token, recoverReceipts: true).ConfigureAwait(false);
                    await PullAsync(token).ConfigureAwait(false);
                },token:_lifetime.Token).ConfigureAwait(false); }
                catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { break; }
                catch (Exception) { _relay.AbortConnection(_epoch); }
            }
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
    }

    private const long DeliveryRetryDelay = 86_400_000;
    private const long DeliveryRetryWindow = 6 * DeliveryRetryDelay;

    private void RecoverMissingDeliveryReceipts()
    {
        var time = Now;
        _store.Transaction(db =>
        {
            var due = db.Query("""
                SELECT r.message_id,m.conversationUuid,a.created_at,
                       r.retry_at>$p0 AND COALESCE(b.delivered,0)=1 AND m.status=1 AND m.isMine=1 AND m.isRevoked=0
                       AND EXISTS(SELECT 1 FROM contact_states c WHERE c.uuid=m.conversationUuid AND c.accepted=1 AND c.blocked=0)
                       AND NOT EXISTS(SELECT 1 FROM protocol_revocations v WHERE v.peer=m.conversationUuid)
                       AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=r.message_id AND e.expires_at<=$p1)
                       AND NOT EXISTS(SELECT 1 FROM message_send_objects ref WHERE ref.message_id=r.message_id
                           AND NOT EXISTS(SELECT 1 FROM media_objects o WHERE o.object_id=ref.object_id AND o.local_state=1))
                FROM message_delivery_retries r
                JOIN message_sends a ON a.message_id=r.message_id
                JOIN messages m ON m.messageId=r.message_id
                LEFT JOIN protocol_outbox b ON b.event_id=r.message_id AND b.recipient=m.conversationUuid
                WHERE r.retry_at>0 AND r.retry_at<=$p1
                ORDER BY r.retry_at,r.message_id LIMIT 8
                """, row => (Message: row.GetString(0), Peer: row.GetString(1),
                    Created: row.GetInt64(2), Eligible: row.GetInt32(3) != 0), time - DeliveryRetryWindow, time);
            foreach (var job in due)
            {
                if (!job.Eligible)
                {
                    db.Execute("UPDATE message_delivery_retries SET retry_at=0 WHERE message_id=$p0", job.Message);
                    continue;
                }
                var objects = db.Query("SELECT object_id FROM message_send_objects WHERE message_id=$p0", row => row.GetString(0), job.Message);
                foreach (var objectId in objects)
                    AttachmentQueue.Record(db, job.Message, objectId, outgoing: true, peer: job.Peer, created: job.Created);
                db.Execute("UPDATE protocol_outbox SET delivered=0 WHERE event_id=$p0 AND recipient=$p1 AND delivered=1", job.Message, job.Peer);
            }
            return true;
        });
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
                catch (RelayClient.UnprocessedDeliveryException error) { work.Result.TrySetException(error); }
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
    private Task PullAsync(CancellationToken token)
    {
        _syncNonce ??= Guid.NewGuid().ToByteArray(bigEndian: true);
        return SendAsync(GhostPacket.V2Sync, _settings.MyUuid, _syncNonce, [], token);
    }
    private void InsertText(VaultTransaction db,string id,string peer,bool mine,string text,long created,JsonObject? reply,JsonObject? preview=null,string? contactAccount=null,LocationPoint? location=null,bool live=false,bool allowEdit=true)
    {
        var contentType = live ? "live_location" : location is not null ? "location" : contactAccount is not null ? "contact" : "txt";
        db.Execute("INSERT INTO messages(messageId,conversationUuid,isMine,content,ext,timestamp,isRead,replyToId,replyToSender,replyToContent,status,isRevoked,isEdited) VALUES($p0,$p1,$p2,$p3,$p4,$p5,$p6,$p7,$p8,$p9,0,0,0)",id,peer,mine ? 1 : 0,text,contentType,created,mine ? 1 : 0,reply?["event_id"]?.GetValue<string>(),reply?["author"]?.GetValue<string>(),reply?["preview"]?.GetValue<string>());
        if (contactAccount is not null)
            db.Execute("INSERT INTO message_contact_cards(message_id,account) VALUES($p0,$p1)",id,contactAccount);
        if (location is not null)
            db.Execute("INSERT INTO message_locations(message_id,latitude_e6,longitude_e6) VALUES($p0,$p1,$p2)",
                id,location.LatitudeE6,location.LongitudeE6);
        ApplySavedChange(db,peer,mine?_settings.MyUuid:peer,mine,id,allowEdit);
        WebPreviewStore.Record(db,id,preview,text,peer,mine,created);
    }
    private void InsertAttachment(VaultTransaction db,string id,string peer,bool mine,JsonObject manifest,string caption,long created,JsonObject? reply,bool sticker=false,JsonObject? album=null,bool allowEdit=true)
    {
        EncryptedObjectStore.Remember(manifest,db);
        var objectId=Text(manifest,"object_id");
        db.Execute("INSERT INTO messages(messageId,conversationUuid,isMine,content,ext,timestamp,isRead,replyToId,replyToSender,replyToContent,status,isRevoked,isEdited) VALUES($p0,$p1,$p2,$p3,'attachment',$p4,$p5,$p6,$p7,$p8,0,0,0)",id,peer,mine?1:0,EncryptedObjectStore.Reference(objectId),created,mine?1:0,reply?["event_id"]?.GetValue<string>(),reply?["author"]?.GetValue<string>(),reply?["preview"]?.GetValue<string>());
        db.Execute("INSERT INTO message_attachments(message_id,object_id,caption) VALUES($p0,$p1,$p2)",id,objectId,caption);
        if (album is not null)
        {
            var albumId = Text(album, "id");
            var count = album["count"]!.GetValue<int>();
            var direction = mine ? 1 : 0;
            db.Execute("""
                INSERT OR IGNORE INTO message_album_items(message_id,peer,is_mine,album_id,position,item_count)
                SELECT $p0,$p1,$p2,$p3,$p4,$p5 WHERE NOT EXISTS (
                    SELECT 1 FROM message_album_items WHERE peer=$p1 AND is_mine=$p2 AND album_id=$p3 AND item_count<>$p5
                )
                """, id, peer, direction, albumId, album["index"]!.GetValue<int>(), count);
        }
        if(mine || !db.Exists("SELECT 1 FROM media_objects WHERE object_id=$p0 AND local_state=1",objectId))
            AttachmentQueue.Record(db,id,objectId,mine,peer,created);
        if(sticker && !db.Exists("SELECT 1 FROM messages WHERE messageId=$p0 AND isRevoked=1",id))db.Execute("INSERT OR IGNORE INTO message_stickers(message_id) VALUES($p0)",id);
        ApplySavedChange(db,peer,mine?_settings.MyUuid:peer,mine,id,allowEdit);
    }
    private static void SaveForward(VaultTransaction db,string id,JsonObject? forward)
    {
        if(forward is null) return;
        db.Execute("INSERT INTO message_forwards(message_id,name,original_at) VALUES($p0,$p1,$p2)",id,forward["name"]?.GetValue<string>(),forward["original_at"]!.GetValue<long>());
    }

    private static void ClearHistory(VaultTransaction db,string peer)
    {
        db.Execute("DELETE FROM call_history WHERE peer=$p0",peer);
        db.Execute("DELETE FROM live_location_states WHERE peer=$p0",peer);
        db.Execute("DELETE FROM message_pin_clocks WHERE peer=$p0",peer);
        db.Execute("DELETE FROM object_transfers WHERE event_id IN (SELECT messageId FROM messages WHERE conversationUuid=$p0)",peer);
        db.Execute("DELETE FROM message_attachments WHERE message_id IN (SELECT messageId FROM messages WHERE conversationUuid=$p0)",peer);
        db.Execute("DELETE FROM message_changes WHERE conversation_uuid=$p0",peer);
        db.Execute("DELETE FROM messages WHERE conversationUuid=$p0",peer);
        db.Execute("DELETE FROM protocol_pending_events WHERE recipient=$p0 AND kind IN ('chat.text','chat.contact','chat.location','chat.location.start','chat.location.update','chat.location.end','chat.attachment','chat.edit','chat.revoke','chat.pin')",peer);
    }

    internal Task<bool> RecordCallHistoryAsync(string peer, string callId, bool outgoing, bool video,
        CallOutcome outcome, long duration, long endedAt) => UserAsync(() => _store.Transaction(db =>
        {
            CallHistory.Insert(db,peer,callId,outgoing,video,outcome,duration,endedAt);
            return true;
        },notify:true));

    internal Task<bool> RemoveCallHistoryAsync(CallHistoryEntry? entry) => UserAsync(() => _store.Transaction(db =>
    {
        CallHistory.Remove(db, entry);
        return true;
    }));

    private static JsonObject? SavedChange(VaultTransaction db,string author,string target)
    {
        var encoded=db.Text("SELECT state_json FROM message_changes WHERE author=$p0 AND message_id=$p1",author,target);
        return encoded is null?null:JsonNode.Parse(encoded)!.AsObject();
    }
    private static void SaveChange(VaultTransaction db,string peer,string author,bool mine,string target,JsonObject change)
    {
        var existing=db.Query("SELECT conversationUuid,isMine,ext FROM messages WHERE messageId=$p0",r=>(Peer:r.GetString(0),Mine:r.GetInt64(1)!=0,Ext:r.GetString(2)),target);
        var expectedKind = change["caption"]?.GetValue<bool>() == true ? "attachment" : "txt";
        if(existing.Count>0 && (existing[0].Peer!=peer || existing[0].Mine!=mine || (change["revoked"]!.GetValue<bool>()==false && existing[0].Ext!=expectedKind))) return;
        if(existing.Count==0 && !mine && db.Exists("SELECT 1 FROM protocol_inbox WHERE sender=$p0 AND event_id=$p1",author,target)) return;
        var previous=SavedChange(db,author,target);
        var reduced=Call(new JsonObject { ["operation"]="reduce_message_change", ["previous"]=previous?.DeepClone(), ["change"]=change.DeepClone(), ["incoming"]=!mine });
        if (reduced["conflict"]?.GetValue<bool>() == true) return;
        var state=reduced["change"]!.AsObject();
        if(reduced["changed"]!.GetValue<bool>())
        {
            var encoded=state.ToJsonString();
            if(existing.Count==0)
            {
                var count=db.Number("SELECT COUNT(*) FROM message_changes WHERE pending=1 AND author=$p0",author);
                var bytes=db.Number("SELECT COALESCE(SUM(stored_bytes+1024),0) FROM message_changes WHERE pending=1");
                var previousBytes=db.Number("SELECT COALESCE((SELECT stored_bytes+1024 FROM message_changes WHERE author=$p0 AND message_id=$p1 AND pending=1),0)",author,target);
                if ((previous is null && count>=256) || bytes-previousBytes+Encoding.UTF8.GetByteCount(encoded)+1024>16*1024*1024)
                {
                    if (!mine) throw new PendingChangeLimit();
                    throw new InvalidDataException("Pending message changes exceed the local history budget.");
                }
            }
            db.Execute("INSERT INTO message_changes(author,message_id,conversation_uuid,state_json,pending,stored_bytes,pending_since) VALUES($p0,$p1,$p2,$p3,$p4,$p5,$p6) ON CONFLICT(author,message_id) DO UPDATE SET state_json=excluded.state_json,pending=excluded.pending,stored_bytes=excluded.stored_bytes",author,target,peer,encoded,existing.Count==0?1:0,Encoding.UTF8.GetByteCount(encoded),Now);
        }
        ApplySavedChange(db,peer,author,mine,target);
    }
    private static void ApplySavedChange(VaultTransaction db,string peer,string author,bool mine,string target,bool allowEdit=true)
    {
        var state=SavedChange(db,author,target);
        if(state is null) return;
        var originals = db.Query("""
            SELECT ext,isRevoked,EXISTS(SELECT 1 FROM message_stickers WHERE message_id=messages.messageId)
            FROM messages WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=$p2
            """, row => (Kind:row.GetString(0), Revoked:row.GetInt64(1)!=0, Sticker:row.GetInt64(2)!=0), target, peer, mine?1:0);
        if (originals.Count == 0) return;
        var original = originals[0];
        var caption = state["caption"]?.GetValue<bool>() == true;
        if (!state["revoked"]!.GetValue<bool>() && (!allowEdit || original.Revoked || original.Sticker || original.Kind != (caption ? "attachment" : "txt")))
        {
            db.Execute("DELETE FROM message_changes WHERE author=$p0 AND message_id=$p1", author, target);
            return;
        }
        db.Execute("UPDATE message_changes SET pending=0 WHERE author=$p0 AND message_id=$p1",author,target);
        if(state["revoked"]!.GetValue<bool>())
        {

            db.Execute("UPDATE messages SET content='',isRevoked=1,isEdited=0 WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=$p2",target,peer,mine?1:0);
            db.Execute("UPDATE messages SET replyToContent='[Message revoked]' WHERE conversationUuid=$p0 AND replyToId=$p1",peer,target);
            db.Execute("DELETE FROM object_transfers WHERE event_id=$p0",target);
            db.Execute("DELETE FROM message_attachments WHERE message_id=$p0",target);
            db.Execute("DELETE FROM message_contact_cards WHERE message_id=$p0",target);
            db.Execute("DELETE FROM message_locations WHERE message_id=$p0",target);
            if(mine)
            {
                db.Execute("DELETE FROM protocol_pending_events WHERE event_id=$p0 AND recipient=$p1",target,peer);
            }
        }
        else if (caption)
        {
            db.Execute("UPDATE message_attachments SET caption=$p0 WHERE message_id=$p1",Text(state,"text"),target);
            db.Execute("UPDATE messages SET isEdited=1 WHERE messageId=$p0",target);
        }
        else db.Execute("UPDATE messages SET content=$p0,isEdited=1 WHERE messageId=$p1 AND conversationUuid=$p2 AND isMine=$p3 AND ext='txt' AND isRevoked=0",Text(state,"text"),target,peer,mine?1:0);
    }

    private JsonObject? Quote(VaultTransaction db, string recipient, string? replyId, string? selection)
    {
        if(replyId is null) return null;
        var quote=db.Query("SELECT isMine,content,ext FROM messages WHERE messageId=$p0 AND conversationUuid=$p1 AND isRevoked=0",r=>(Mine:r.GetInt64(0)!=0,Content:r.GetString(1),Extension:r.GetString(2)),replyId,recipient);
        if(quote.Count==0) throw new VisibleFailure("The quoted message is unavailable.");
        var original=quote[0];
        var expiring = MessageExpirationStore.IsExpiring(db, replyId);
        if (selection is not null && (expiring || original.Extension != "txt"))
            throw new VisibleFailure("The selected quote is unavailable.");
        var summary = expiring ? "Auto-deleting message" : original.Extension switch
        {
            "txt" or "location" or "live_location" => original.Content,
            "contact" => "Contact: " + original.Content,
            _ => "[" + original.Extension + "]"
        };
        var preview = selection is null ? Preview(summary) : SecurityCore.Call(new
        {
            operation = "reply_quote_selection",
            text = original.Content,
            selection
        }).GetProperty("preview").GetString() ?? throw new VisibleFailure("The selected quote is unavailable.");
        return new JsonObject { ["event_id"]=replyId,["author"]=original.Mine?_settings.MyUuid:recipient,["preview"]=preview };
    }
    private void ValidateQuote(JsonObject? reply,string sender)
    {
        if(reply is null) return;
        Validate(Text(reply,"event_id"),"reply",0);
        if(Text(reply,"author")!=sender && Text(reply,"author")!=_settings.MyUuid) throw new InvalidDataException("Invalid quote author.");
        if(Encoding.UTF8.GetByteCount(Text(reply,"preview"))>4096) throw new InvalidDataException("Quote preview is too large.");
    }
    private const string EligibleRead="(SELECT read_receipts FROM privacy_preferences WHERE id=1)=1 AND NOT EXISTS(SELECT 1 FROM read_receipt_suppression s WHERE s.peer=r.peer AND s.message_id=r.message_id)";
    private static bool ReadReceiptsEnabled(VaultTransaction db)=>db.Exists("SELECT 1 FROM privacy_preferences WHERE id=1 AND read_receipts=1");
    private static bool CanSendRead(VaultTransaction db,string peer,string id)=>ReadReceiptsEnabled(db) &&
        !db.Exists("SELECT 1 FROM read_receipt_suppression WHERE peer=$p0 AND message_id=$p1",peer,id);
    private static void Receipt(VaultTransaction db,string recipient,string id,string state)
    {
        if(!db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1 AND blocked=0",recipient)) return;
        if(state=="read" && !CanSendRead(db,recipient,id))return;
        db.Execute("INSERT OR IGNORE INTO protocol_receipts(peer,message_id,state,event_id,created_at) VALUES($p0,$p1,$p2,$p3,$p4)",recipient,id,state,Guid.NewGuid().ToString(),Now);
    }
    private static void RescheduleReceipt(string peer,string id,VaultTransaction db)
    {
        if(!db.Exists("SELECT 1 FROM contact_states WHERE uuid=$p0 AND accepted=1 AND blocked=0",peer)) return;
        var messages=db.Query("SELECT isRead FROM messages WHERE messageId=$p0 AND conversationUuid=$p1 AND isMine=0",r=>r.GetInt32(0),id,peer);
        if(messages.Count>0) Receipt(db,peer,id,messages[0]!=0 && CanSendRead(db,peer,id) ? "read" : "delivered");
        var receipts=db.Query($"SELECT event_id,pending,next_retry FROM protocol_receipts r WHERE peer=$p0 AND message_id=$p1 AND (state!='read' OR ({EligibleRead})) ORDER BY CASE state WHEN 'read' THEN 0 ELSE 1 END LIMIT 1",r=>(Event:r.GetString(0),Pending:r.GetInt32(1),Next:r.GetInt64(2)),peer,id);
        if(receipts.Count==0 || receipts[0].Pending!=0) return;
        var receipt=receipts[0]; var time=Now;
        if(receipt.Next>time && receipt.Next-time<=30000) return;
        db.Execute("UPDATE protocol_receipts SET pending=1 WHERE event_id=$p0",receipt.Event);
    }
    private void PrepareReceipts() => _store.Transaction(db=>
    {
        db.Execute($"UPDATE protocol_receipts SET pending=0 WHERE pending=1 AND state='delivered' AND EXISTS(SELECT 1 FROM protocol_receipts r WHERE r.peer=protocol_receipts.peer AND r.message_id=protocol_receipts.message_id AND r.state='read' AND ({EligibleRead}))");
        var jobs=db.Query($"SELECT r.peer,r.message_id,r.state,r.event_id,r.created_at FROM protocol_receipts r JOIN contact_states c ON c.uuid=r.peer WHERE r.pending=1 AND c.accepted=1 AND c.blocked=0 AND (r.state!='read' OR ({EligibleRead})) ORDER BY r.rowid LIMIT 64",r=>(Peer:r.GetString(0),Id:r.GetString(1),State:r.GetString(2),Event:r.GetString(3),Created:r.GetInt64(4)));
        foreach(var job in jobs)
        {
            if(db.Exists("SELECT 1 FROM protocol_outbox WHERE event_id=$p0 AND recipient=$p1",job.Event,job.Peer))
                db.Execute("UPDATE protocol_outbox SET delivered=0 WHERE event_id=$p0 AND recipient=$p1 AND (delivered>=0 OR delivered=-2)",job.Event,job.Peer);
            else Enqueue(new Pending(job.Event,job.Peer,"chat.receipt",new JsonObject { ["message_id"]=job.Id,["state"]=job.State },job.Created),db);
            db.Execute("UPDATE protocol_receipts SET pending=0,next_retry=$p0 WHERE event_id=$p1",Now+30000,job.Event);
        }
        return true;
    });
    private static Pending PendingEvent(string recipient,string kind,JsonObject body) => new(Guid.NewGuid().ToString("D"),recipient,kind,body,Now);
    private JsonObject Profile(VaultTransaction db) => UserProfileStore.Body(db,_settings.MyUuid,_settings.Snapshot.Nickname ?? "Unknown");
    private static void RequireAccepted(VaultTransaction db,string recipient)
    {
        if(db.Exists("SELECT 1 FROM protocol_revocations WHERE peer=$p0",recipient)) throw new VisibleFailure("The recipient identity was reset and cannot receive messages.");
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
    private void PublishNotification(string sender,string messageId,bool silent,bool pinned)
    {
        try
        {
            if (!NotificationSettings.Enabled || _store.Transaction(db => ConversationIndexStore.IsMuted(db,sender,Now) ||
                (pinned && db.Exists("SELECT 1 FROM message_expiration WHERE message_id=$p0 AND expires_at<=$p1",messageId,Now))))
                return;
            NotificationRequested?.Invoke(this,new NotificationRequest(sender,messageId,silent,pinned));
        }
        catch (Exception error) { System.Diagnostics.Trace.TraceWarning("Notification dispatch failed ({0})",error.GetType().Name); }
    }
    private sealed record PeerActivity(bool? Online,long Expires,long TypingExpires);
    private readonly object _activityGate=new();
    private volatile bool _activityForeground;
    private volatile bool _activityOnline;
    private volatile bool _activityTyping;
    private bool _activityLoaded;
    private int _activityCursor;
    private long _activityRevision;
    private string? _activityChat;
    private long _activityTypingUntil;
    private readonly HashSet<string> _activityDirty=new(StringComparer.Ordinal);
    private volatile Dictionary<string,PeerActivity> _activityReceived=new(StringComparer.Ordinal);
    private int _activityQueued;
    private readonly Task _activityMaintenance;
    internal event EventHandler<string?>? PeerActivityChanged;

    internal (PeerActivityStatus Status, long Expires) GetPeerActivity(string peer)
    {
        if (!_activityReceived.TryGetValue(peer, out var value)) return default;
        var time = Environment.TickCount64;
        if (value.TypingExpires > time) return (PeerActivityStatus.Typing, value.TypingExpires);
        if (_activityOnline && value.Online is { } online && value.Expires > time)
            return (online ? PeerActivityStatus.Online : PeerActivityStatus.Offline, value.Expires);
        return default;
    }

    private void NotifyPeerActivityChanged(string? peer)
    {
        try { PeerActivityChanged?.Invoke(this, peer); }
        catch { /* Observers cannot undo committed activity. */ }
    }

    private void RemovePeerActivity(string peer)
    {
        var current = _activityReceived;
        if (!current.ContainsKey(peer)) return;
        _activityReceived = current.Where(pair => pair.Key != peer).ToDictionary(pair => pair.Key, pair => pair.Value, StringComparer.Ordinal);
        NotifyPeerActivityChanged(peer);
    }
    public void SetForeground(bool value){lock(_activityGate){if(_activityForeground==value)return;_activityForeground=value;_activityRevision++;if(!value)_activityTypingUntil=0;}if(_activityOnline || _activityTyping)SignalActivity();}
    public void SetActiveChat(string? peer,bool typing=false)
    {
        bool changed;
        lock(_activityGate){var time=Environment.TickCount64;changed=_activityChat!=peer || (_activityTypingUntil>time)!=typing;
            if((_activityOnline || _activityTyping) && _activityChat is not null && _activityChat!=peer)_activityDirty.Add(_activityChat);
            _activityRevision++;_activityChat=peer;_activityTypingUntil=typing && peer is not null ? time+8000:0;
            while(_activityDirty.Count>128)_activityDirty.Remove(_activityDirty.First());}
        if(changed && (_activityOnline || _activityTyping))SignalActivity();
    }
    private void SignalActivity()
    {
        if(Volatile.Read(ref _disposed)!=0 || Interlocked.CompareExchange(ref _activityQueued,1,0)!=0)return;
        var result=new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _=result.Task.ContinueWith(task=>{_ = task.Exception;},CancellationToken.None,TaskContinuationOptions.OnlyOnFaulted,TaskScheduler.Default);
        if(!_commands.Writer.TryWrite(new Work(async token=>{long revision;lock(_activityGate)revision=_activityRevision;try{await FlushAsync(token).ConfigureAwait(false);}finally{Interlocked.Exchange(ref _activityQueued,0);bool changed;lock(_activityGate)changed=_activityRevision!=revision;if(changed)SignalActivity();}},result,false,_lifetime.Token)))Interlocked.Exchange(ref _activityQueued,0);
    }
    private async Task MaintainActivityAsync()
    {
        using var timer=new PeriodicTimer(TimeSpan.FromSeconds(2));
        try {while(await timer.WaitForNextTickAsync(_lifetime.Token).ConfigureAwait(false)){if(_activityForeground && (_activityOnline || _activityTyping))SignalActivity();}}
        catch(OperationCanceledException)when(_lifetime.IsCancellationRequested) { }
    }
    public async Task<ActivityPrivacy> ActivityPrivacyAsync(CancellationToken token=default)
    {
        ActivityPrivacy? value=null;
        await Submit(_=>{value=_store.Transaction(db=>{LoadActivityPrivacy(db);return new ActivityPrivacy(_activityOnline,_activityTyping);});return Task.CompletedTask;},token:token).ConfigureAwait(false);
        return value??throw new InvalidOperationException("Privacy settings could not be read.");
    }
    private void LoadActivityPrivacy(VaultTransaction db)
    {
        var flags=db.Query("SELECT share_online,share_typing FROM privacy_preferences WHERE id=1",r=>(Online:r.GetInt32(0)!=0,Typing:r.GetInt32(1)!=0)).Single();_activityOnline=flags.Online;_activityTyping=flags.Typing;_activityLoaded=true;
    }
    private static JsonObject ActivityState(VaultTransaction db,string peer)=>db.Text("SELECT state_json FROM activity_peers WHERE peer=$p0",peer) is { } text ? JsonNode.Parse(text)!.AsObject() : new JsonObject();
    private static JsonObject ChangeActivity(VaultTransaction db,string peer,JsonObject state,JsonObject action)
    {
        var result=JsonNode.Parse(SecurityCore.Call(new JsonObject{["operation"]="activity_transition",["state"]=state.DeepClone(),["action"]=action}).GetRawText())!.AsObject();
        db.Execute("INSERT INTO activity_peers(peer,state_json) VALUES($p0,$p1) ON CONFLICT(peer) DO UPDATE SET state_json=excluded.state_json",peer,result["state"]!.ToJsonString());return result;
    }
    private static void DeleteActivityReceipt(VaultTransaction db,string peer,string id)
    {
        db.Execute("DELETE FROM protocol_pending_events WHERE event_id IN (SELECT event_id FROM protocol_receipts WHERE peer=$p0 AND message_id=$p1 AND state='delivered')",peer,id);
        db.Execute("DELETE FROM protocol_outbox WHERE event_id IN (SELECT event_id FROM protocol_receipts WHERE peer=$p0 AND message_id=$p1 AND state='delivered')",peer,id);
        db.Execute("DELETE FROM protocol_receipts WHERE peer=$p0 AND message_id=$p1 AND state='delivered'",peer,id);
    }
    private JsonObject? ReceiveActivity(Incoming incoming,VaultTransaction db)
    {
        var peer=incoming.Sender;
        if(!db.Exists("SELECT 1 FROM contact_states c WHERE c.uuid=$p0 AND c.accepted=1 AND c.blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=c.uuid)",peer))return null;
        var result=ChangeActivity(db,peer,ActivityState(db,peer),new JsonObject{["kind"]="receive",["event_id"]=incoming.EventId,["body"]=incoming.Body.DeepClone(),["created_at"]=incoming.CreatedAt,["now"]=Now});
        if(result["state"]!["received_id"]?.GetValue<string>()==incoming.EventId)Receipt(db,peer,incoming.EventId,"delivered");
        if(result["replaced"] is { } replaced)DeleteActivityReceipt(db,peer,replaced.GetValue<string>());
        if(result["observation"] is not JsonObject observation)return null;
        if(!_activityLoaded)LoadActivityPrivacy(db);
        if(_activityOnline && observation["request"]!.GetValue<bool>())
        {
            db.Execute("INSERT INTO activity_watchers(peer,until) VALUES($p0,$p1) ON CONFLICT(peer) DO UPDATE SET until=excluded.until",peer,Now+45000);
            db.Execute("DELETE FROM activity_watchers WHERE peer IN (SELECT peer FROM activity_watchers ORDER BY until DESC,peer LIMIT -1 OFFSET 128)");
        }
        return observation;
    }
    private void PublishActivity(string peer,JsonObject value)
    {
        var time=Environment.TickCount64;
        var entry=new PeerActivity(value["online"]?.GetValue<bool>(),time+Number(value,"valid_for"),time+Number(value,"typing_for"));
        var current = _activityReceived;
        _activityReceived = current.Where(pair => pair.Key != peer).Append(new KeyValuePair<string, PeerActivity>(peer, entry))
            .TakeLast(128).ToDictionary(pair => pair.Key, pair => pair.Value, StringComparer.Ordinal);
        NotifyPeerActivityChanged(current.Count == 128 && !current.ContainsKey(peer) ? null : peer);
    }
    private static void AcknowledgeActivity(VaultTransaction db,string peer,string id)
    {
        var state=ActivityState(db,peer);
        if(state["inflight"]?["event_id"]?.GetValue<string>()!=id)return;
        var result=ChangeActivity(db,peer,state,new JsonObject{["kind"]="acknowledge",["event_id"]=id});
        if(result["acknowledged"] is not null)db.Execute("DELETE FROM protocol_outbox WHERE event_id=$p0 AND recipient=$p1",id,peer);
    }
    private bool ShouldPrepareActivity(){lock(_activityGate)return !_activityLoaded || _activityOnline || _activityTyping || _activityDirty.Count>0;}
    private void PrepareActivity()=>_store.Transaction(db=>
    {
        if(!_activityLoaded)LoadActivityPrivacy(db);
        var time=Now;
        string? chat;long typingUntil;long revision;string[] dirty;bool foreground;
        lock(_activityGate){foreground=_activityForeground;chat=_activityChat;typingUntil=_activityTypingUntil;dirty=[.._activityDirty];revision=_activityRevision;}
        db.Execute("DELETE FROM activity_watchers WHERE until<=$p0 OR until>$p1",time,time+45000);
        List<string> watchers=_activityOnline ? db.Query("SELECT w.peer FROM activity_watchers w LEFT JOIN activity_peers a ON a.peer=w.peer ORDER BY COALESCE(json_extract(a.state_json,'$.published.created_at'),0),w.peer LIMIT 128",r=>r.GetString(0)) : [];
        var rest=dirty.Concat(watchers).Distinct(StringComparer.Ordinal).Where(peer=>peer!=chat).ToArray();
        var start=rest.Length==0?0:_activityCursor%rest.Length;
        var peers=new List<string>();if(chat is not null)peers.Add(chat);
        for(var index=0;index<Math.Min(rest.Length,31);index++)peers.Add(rest[(start+index)%rest.Length]);
        if(rest.Length>0)_activityCursor=(start+31)%rest.Length;
        foreach(var peer in peers)
        {
            if(!db.Exists("SELECT 1 FROM contact_states c WHERE c.uuid=$p0 AND c.accepted=1 AND c.blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=c.uuid)",peer)){lock(_activityGate){if(_activityRevision==revision)_activityDirty.Remove(peer);}continue;}
            bool? online=_activityOnline?foreground:null;
            var typing=foreground && _activityTyping && chat==peer && typingUntil>Environment.TickCount64;
            var request=foreground && _activityOnline && chat==peer;
            var state=ActivityState(db,peer);var inflight=state["inflight"] as JsonObject;
            if(inflight is not null && !db.Exists("SELECT 1 FROM protocol_outbox WHERE event_id=$p0 AND recipient=$p1",Text(inflight,"event_id"),peer) &&
               (Number(inflight["body"]!.AsObject(),"expires_at")<=time || inflight["body"]!["online"]?.GetValue<bool>()!=online || inflight["body"]!["typing"]!.GetValue<bool>()!=typing || inflight["body"]!["request"]!.GetValue<bool>()!=request))
            {
                db.Execute("DELETE FROM protocol_pending_events WHERE event_id=$p0 AND recipient=$p1",Text(inflight,"event_id"),peer);
                state=ChangeActivity(db,peer,state,new JsonObject{["kind"]="unprepare"})["state"]!.AsObject();inflight=null;
            }
            if(inflight is not null)
            {
                var body=inflight["body"]!.AsObject();
                var permitted=(_activityOnline || (body["online"] is null && !body["request"]!.GetValue<bool>())) && (_activityTyping || !body["typing"]!.GetValue<bool>());
                var retry=Number(inflight,"retry_at");
                if(permitted && (retry<=time || retry-time>30000)){db.Execute("UPDATE protocol_outbox SET delivered=0 WHERE event_id=$p0 AND recipient=$p1 AND delivered>=0",Text(inflight,"event_id"),peer);ChangeActivity(db,peer,state,new JsonObject{["kind"]="retried",["now"]=time});}
                continue;
            }
            var result=ChangeActivity(db,peer,state,new JsonObject{["kind"]="publish",["event_id"]=Guid.NewGuid().ToString("D"),["online"]=JsonValue.Create(online),["typing"]=typing,["request"]=request,["now"]=time});
            if(result["send"] is JsonObject send)Enqueue(new Pending(Text(send,"event_id"),peer,"chat.activity",send["body"]!.DeepClone().AsObject(),Number(send,"created_at")),db);
            lock(_activityGate){if(_activityRevision==revision)_activityDirty.Remove(peer);}
        }
        return true;
    });

    private long _nextScheduledSend=long.MaxValue;
    private long _nextRelayRetryAt = long.MaxValue;
    private bool _scheduleDeadlineLoaded;
    private int _scheduleQueued;
    private readonly Task _scheduleMaintenance;
    private void SignalScheduledSends()
    {
        if(Volatile.Read(ref _disposed)!=0 || Interlocked.CompareExchange(ref _scheduleQueued,1,0)!=0)return;
        var result=new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _=result.Task.ContinueWith(task=>{_ = task.Exception;},CancellationToken.None,TaskContinuationOptions.OnlyOnFaulted,TaskScheduler.Default);
        if (!_commands.Writer.TryWrite(new Work(async token =>
        {
            try { await FlushAsync(token).ConfigureAwait(false); }
            catch (OperationCanceledException) { throw; }
            catch (Exception error)
            {
                var time = Now;
                if (_nextScheduledSend <= time)
                {
                    _nextScheduledSend = time + 30_000;
                    _scheduleDeadlineLoaded = false;
                }
                if (_nextRelayRetryAt <= time) Volatile.Write(ref _nextRelayRetryAt, time + 30_000);
                Report(error, false);
            }
            finally { Interlocked.Exchange(ref _scheduleQueued, 0); }
        }, result, false, _lifetime.Token))) Interlocked.Exchange(ref _scheduleQueued, 0);
    }
    private async Task MaintainScheduledAsync()
    {
        using var timer=new PeriodicTimer(TimeSpan.FromSeconds(1));
        try
        {
            while (await timer.WaitForNextTickAsync(_lifetime.Token).ConfigureAwait(false))
                if (Math.Min(Volatile.Read(ref _nextExpiration), Volatile.Read(ref _nextRetentionSweep)) <= Now ||
                    (_ready && Math.Min(Volatile.Read(ref _nextScheduledSend), Volatile.Read(ref _nextRelayRetryAt)) <= Now))
                    SignalScheduledSends();
        }
        catch(OperationCanceledException)when(_lifetime.IsCancellationRequested) { }
    }
    private bool HoldScheduled(Pending message,SendOptions options,VaultTransaction db)
    {
        if(options.SendAt is not { } due)return false;
        if(due is <0 or >253402214399999L)throw new InvalidOperationException("Invalid scheduled time.");
        db.Execute("INSERT INTO scheduled_sends(event_id,recipient,send_at,created_at) VALUES($p0,$p1,$p2,$p3)",message.EventId,message.Recipient,due,message.CreatedAt);
        db.Execute("UPDATE protocol_pending_events SET held=1 WHERE event_id=$p0",message.EventId);
        foreach(var manifest in new[]{message.Body["object"] as JsonObject,message.Body["preview"]?["image"] as JsonObject}.OfType<JsonObject>())
        {
            EncryptedObjectStore.Remember(manifest,db,true);
            db.Execute("INSERT OR IGNORE INTO scheduled_objects(event_id,object_id) VALUES($p0,$p1)",message.EventId,Text(manifest,"object_id"));
        }
        _scheduleDeadlineLoaded=false;return true;
    }
    private static void RequireScheduledMedia(VaultTransaction db, string id)
    {
        if (db.Exists("""
            SELECT 1 FROM protocol_pending_events p,
                json_each(json_array(json_extract(p.body_json,'$.object.object_id'),
                                     json_extract(p.body_json,'$.preview.image.object_id'))) ref
            WHERE p.event_id=$p0 AND ref.value IS NOT NULL
                AND NOT EXISTS(SELECT 1 FROM media_objects o WHERE o.object_id=ref.value AND o.local_state=1)
            """, id))
            throw new VisibleFailure("Attachment is unavailable.");
    }

    private void ReleaseScheduledSends()
    {
        if(_scheduleDeadlineLoaded && _nextScheduledSend>Now)return;
        var changed=_store.Transaction(db=>
        {
            var changed=false;
            var rows=db.Query("SELECT s.event_id,s.recipient,p.kind,p.body_json FROM scheduled_sends s JOIN protocol_pending_events p ON p.event_id=s.event_id WHERE s.state='waiting' AND s.send_at<=$p0 ORDER BY s.send_at,s.created_at,s.event_id LIMIT 16",r=>(Id:r.GetString(0),Peer:r.GetString(1),Kind:r.GetString(2),Body:JsonNode.Parse(r.GetString(3))!.AsObject()),Now);
            foreach(var row in rows)
            {
                db.Execute("SAVEPOINT release_scheduled");
                try
                {
                    RequireAccepted(db,row.Peer);
                    if(db.Exists("SELECT 1 FROM protocol_outbox WHERE event_id=$p0",row.Id))throw new InvalidOperationException("Scheduled message may already have been transmitted.");
                    RequireScheduledMedia(db, row.Id);
                    var created=Now;db.Execute("UPDATE protocol_pending_events SET held=0,created_at=$p0 WHERE event_id=$p1",created,row.Id);
                    if(row.Kind=="chat.text")InsertText(db,row.Id,row.Peer,true,Text(row.Body,"text"),created,row.Body["reply"] as JsonObject,row.Body["preview"] as JsonObject);
                    else if(row.Kind=="chat.contact")InsertText(db,row.Id,row.Peer,true,Text(row.Body,"name"),created,row.Body["reply"] as JsonObject,contactAccount:Text(row.Body,"account"));
                    else if(row.Kind=="chat.location")InsertText(db,row.Id,row.Peer,true,row.Body["label"]?.GetValue<string>()??"Location",
                        created,row.Body["reply"] as JsonObject,location:LocationPoint.Parse(row.Body));
                    else {if(row.Kind!="chat.attachment")throw new InvalidDataException("Invalid scheduled kind.");InsertAttachment(db,row.Id,row.Peer,true,row.Body["object"]!.AsObject(),Text(row.Body,"caption"),created,row.Body["reply"] as JsonObject,row.Body["sticker"]?.GetValue<bool>()==true,row.Body["album"] as JsonObject);}
                    SaveForward(db,row.Id,row.Body["forward"] as JsonObject);
                    if (row.Body["expire_after_ms"] is JsonValue ttl)
                        Volatile.Write(ref _nextExpiration, Math.Min(_nextExpiration, created + ttl.GetValue<long>()));
                    db.Execute("DELETE FROM scheduled_sends WHERE event_id=$p0",row.Id);db.Execute("RELEASE SAVEPOINT release_scheduled");changed=true;
                }
                catch(Exception error)
                {
                    db.Execute("ROLLBACK TO SAVEPOINT release_scheduled");db.Execute("RELEASE SAVEPOINT release_scheduled");if(error is OperationCanceledException)throw;
                    db.Execute("UPDATE scheduled_sends SET state='failed' WHERE event_id=$p0",row.Id);Report(error,false);
                }
            }
            _nextScheduledSend=db.Query("SELECT MIN(send_at) FROM scheduled_sends WHERE state='waiting'",r=>r.IsDBNull(0)?long.MaxValue:r.GetInt64(0)).Single();_scheduleDeadlineLoaded=true;return changed;
        });
        if(changed)_store.NotifyBusinessChanges();
        NotifySendQueueChanged();
    }
    private async Task AttemptSendAsync(Outgoing message,CancellationToken token)
    {
        var changed = _store.Transaction<int?>(db =>
        {
            var at = Now;
            if (db.Exists("""
                SELECT 1 FROM message_expiration WHERE expires_at<=$p0 AND message_id IN (
                    SELECT $p1 UNION ALL SELECT message_id FROM send_related_events WHERE event_id=$p1
                    UNION ALL SELECT message_id FROM protocol_receipts WHERE event_id=$p1 AND peer=$p2
                )
                """, at, message.EventId, message.Recipient)) return null;
            return db.Execute("UPDATE message_sends SET first_attempt_at=$p0 WHERE first_attempt_at IS NULL AND message_id IN (SELECT $p1 UNION ALL SELECT message_id FROM send_related_events WHERE event_id=$p1)", at, message.EventId);
        });
        if (changed is null) return;
        if(changed>0)NotifySendQueueChanged();
        await SendAsync(GhostPacket.V2Submit,message.Recipient,EventBytes(message.EventId),message.Wire,token).ConfigureAwait(false);_inFlight.Add(message.EventId);
    }
    public async Task<IReadOnlyList<SendQueueEntry>> SendQueueAsync(string? peer=null,int offset=0,CancellationToken token=default,string? messageId=null,bool? scheduled=null,int limit=50)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(offset);
        if (limit is < 1 or > 51) throw new ArgumentOutOfRangeException(nameof(limit));
        int? filter = scheduled is null ? null : scheduled.Value ? 1 : 0;IReadOnlyList<SendQueueEntry>? rows=null;
        await Submit(_=>{rows=_store.Transaction(db=>db.Query("SELECT q.id,q.peer,q.kind,q.body,q.send_at,q.state,q.cancel,q.retry,c.alias,CASE WHEN q.state='failed' THEN CASE WHEN q.retry=0 THEN 'recipient' WHEN EXISTS(SELECT 1 FROM json_each(json_array(json_extract(q.body,'$.object.object_id'),json_extract(q.body,'$.preview.image.object_id'))) ref WHERE ref.value IS NOT NULL AND NOT EXISTS(SELECT 1 FROM media_objects o WHERE o.object_id=ref.value AND o.local_state=1)) THEN 'media' END END AS failure,q.relay_retry_at FROM (SELECT s.event_id AS id,s.recipient AS peer,p.kind AS kind,p.body_json AS body,s.send_at AS send_at,s.state AS state,1 AS cancel,CASE WHEN s.state='failed' AND EXISTS(SELECT 1 FROM contact_states cs WHERE cs.uuid=s.recipient AND cs.accepted=1 AND cs.blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations rv WHERE rv.peer=cs.uuid)) THEN 1 ELSE 0 END AS retry,s.created_at AS created,NULL AS relay_retry_at FROM scheduled_sends s JOIN protocol_pending_events p ON p.event_id=s.event_id UNION ALL SELECT a.message_id,m.conversationUuid,a.kind,a.body_json,NULL,CASE WHEN m.status<0 THEN 'failed' WHEN a.first_attempt_at IS NULL THEN 'queued' ELSE 'sending' END,CASE WHEN a.first_attempt_at IS NULL AND NOT EXISTS(SELECT 1 FROM protocol_outbox b WHERE b.event_id=a.message_id UNION ALL SELECT 1 FROM send_related_events r JOIN protocol_outbox b ON b.event_id=r.event_id WHERE r.message_id=a.message_id) THEN 1 ELSE 0 END,CASE WHEN EXISTS(SELECT 1 FROM contact_states cs WHERE cs.uuid=m.conversationUuid AND cs.accepted=1 AND cs.blocked=0 AND NOT EXISTS(SELECT 1 FROM protocol_revocations rv WHERE rv.peer=cs.uuid)) THEN 1 ELSE 0 END,a.created_at,CASE WHEN m.status=0 THEN d.retry_at END FROM message_sends a JOIN messages m ON m.messageId=a.message_id LEFT JOIN protocol_relay_deferrals d ON d.recipient=m.conversationUuid WHERE m.isMine=1 AND m.status<2 AND m.isRevoked=0 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p5)) q LEFT JOIN conversations c ON c.uuid=q.peer WHERE ($p0 IS NULL OR q.peer=$p0) AND ($p2 IS NULL OR q.id=$p2) AND ($p3 IS NULL OR (q.send_at IS NOT NULL)=$p3) ORDER BY COALESCE(q.send_at,q.created),q.id LIMIT $p4 OFFSET $p1",r=>
        {
            var body=JsonNode.Parse(r.GetString(3))!.AsObject();
            var text=r.GetString(2) switch
            {
                "chat.text" => SecurityCore.MessageSummary(Text(body, "text"), 160),
                "chat.attachment" => string.Join(" · ", new[] { Text(body["object"]!.AsObject(), "filename"),
                    SecurityCore.MessageSummary(Text(body, "caption"), 160) }.Where(value => !string.IsNullOrWhiteSpace(value))),
                "chat.contact" => Text(body,"name"),
                "chat.location" => body["label"]?.GetValue<string>()??"Location",
                "chat.location.start" => body["label"]?.GetValue<string>()??"Live location",
                _ => Text(body["object"]!.AsObject(),"filename")
            };
            var failure = r.IsDBNull(9) ? null : r.GetString(9);
            return new SendQueueEntry(r.GetString(0),r.GetString(1),string.Concat(text.EnumerateRunes().Take(160)),
                r.IsDBNull(4)?null:r.GetInt64(4),body["silent"]?.GetValue<bool>()==true,r.GetString(5),
                r.GetInt32(6)!=0,r.GetInt32(7)!=0 && failure is null,r.IsDBNull(8)?null:r.GetString(8),failure,
                r.IsDBNull(10)?null:r.GetInt64(10), !r.IsDBNull(4) && ScheduledEditField(r.GetString(2), body) is not null);
        },peer,offset,messageId,filter,limit,Now));return Task.CompletedTask;},token:token).ConfigureAwait(false);
        return rows??throw new InvalidOperationException("Send queue is unavailable.");
    }
    private static string? ScheduledEditField(string kind, JsonObject body) => kind switch
    {
        "chat.text" => "text",
        "chat.attachment" when body["sticker"]?.GetValue<bool>() != true => "caption",
        _ => null
    };

    private static (string Kind, JsonObject Body)? ScheduledBody(VaultTransaction db, string id, string peer, long at)
    {
        var rows = db.Query("""
            SELECT p.kind,p.body_json FROM scheduled_sends s
            JOIN protocol_pending_events p ON p.event_id=s.event_id AND p.recipient=s.recipient
            WHERE s.event_id=$p0 AND s.recipient=$p1 AND s.send_at=$p2 AND p.held=1
                AND NOT EXISTS(SELECT 1 FROM protocol_outbox o WHERE o.event_id=p.event_id)
            """, row => (row.GetString(0), JsonNode.Parse(row.GetString(1))!.AsObject()), id, peer, at);
        return rows.Count == 0 ? null : rows[0];
    }

    public async Task<ScheduledMessageDraft?> ScheduledMessageDraftAsync(string id, string peer, long at, CancellationToken token)
    {
        ScheduledMessageDraft? draft = null;
        await Submit(_ =>
        {
            draft = _store.Transaction<ScheduledMessageDraft?>(db =>
                ScheduledBody(db, id, peer, at) is { } row && ScheduledEditField(row.Kind, row.Body) is { } field
                    ? new ScheduledMessageDraft(Text(row.Body, field), field == "caption") : null);
            return Task.CompletedTask;
        }, notifyFailure: false, token: token).ConfigureAwait(false);
        return draft;
    }

    public Task<bool> EditScheduledMessageAsync(string id, string peer, long at, ScheduledMessageDraft original,
        string replacement, CancellationToken token)
    {
        return Submit(_ =>
        {
            if (Encoding.UTF8.GetByteCount(replacement) > 48 * 1024 ||
                !original.IsCaption && string.IsNullOrWhiteSpace(replacement))
                throw new VisibleFailure("Invalid scheduled message text.");
            _store.Transaction(db =>
            {
                if (ScheduledBody(db, id, peer, at) is not { } row ||
                    ScheduledEditField(row.Kind, row.Body) is not { } field ||
                    (field == "caption") != original.IsCaption || Text(row.Body, field) != original.Text)
                    throw new VisibleFailure("The scheduled message has changed.");
                if (replacement == original.Text) return 0;
                if (row.Body["preview"] is JsonObject preview &&
                    !WebPreviewFetcher.Candidates(replacement).Contains(Text(preview, "url"), StringComparer.Ordinal))
                {
                    row.Body.Remove("preview");
                    if (preview["image"]?["object_id"]?.GetValue<string>() is { } objectId)
                        db.Execute("DELETE FROM scheduled_objects WHERE event_id=$p0 AND object_id=$p1", id, objectId);
                }
                row.Body[field] = replacement;
                return db.Execute("UPDATE protocol_pending_events SET body_json=$p0 WHERE event_id=$p1 AND recipient=$p2 AND held=1",
                    row.Body.ToJsonString(), id, peer);
            });
            NotifySendQueueChanged();
            return Task.CompletedTask;
        }, notifyFailure: false, token: token);
    }

    public Task<bool> ManageSendAsync(string id, string action, long? sendAt = null, CancellationToken token = default,
        string? expectedPeer = null, long? expectedScheduledAt = null) => UserAsync(() =>
    {
        _store.Transaction(db=>
        {
            var planned = db.Query("SELECT recipient,send_at FROM scheduled_sends WHERE event_id=$p0",
                row => (Peer: row.GetString(0), SendAt: (long?)row.GetInt64(1)), id).FirstOrDefault();
            if (expectedScheduledAt is not null && planned.SendAt != expectedScheduledAt)
                throw new VisibleFailure("The scheduled message has changed.");
            var scheduled = planned.Peer;
            if(scheduled is not null)
            {
                if (expectedPeer is not null && scheduled != expectedPeer) throw new VisibleFailure("Message is no longer pending in this conversation.");
                if(action=="cancel")db.Execute("DELETE FROM protocol_pending_events WHERE event_id=$p0 AND recipient=$p1",id,scheduled);
                else
                {
                    RequireAccepted(db,scheduled);if(db.Exists("SELECT 1 FROM protocol_outbox WHERE event_id=$p0",id))throw new VisibleFailure("Message may already have been transmitted.");
                    RequireScheduledMedia(db, id);
                    switch(action)
                    {
                        case "send_now":db.Execute("UPDATE scheduled_sends SET send_at=$p0,state='waiting' WHERE event_id=$p1",Now,id);break;
                        case "reschedule":if(sendAt is null || sendAt<=Now || sendAt>253402214399999L)throw new VisibleFailure("Choose a future time.");db.Execute("UPDATE scheduled_sends SET send_at=$p0,state='waiting' WHERE event_id=$p1",sendAt.Value,id);break;
                        case "retry":db.Execute("UPDATE scheduled_sends SET state='waiting' WHERE event_id=$p0",id);break;
                        default:throw new VisibleFailure("Unsupported send action.");
                    }
                }
            }
            else
            {
                var row=db.Query("SELECT m.conversationUuid,a.kind,a.body_json,a.created_at,a.first_attempt_at FROM message_sends a JOIN messages m ON m.messageId=a.message_id WHERE a.message_id=$p0 AND m.isMine=1 AND m.isRevoked=0 AND m.status<2 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p1)",r=>(Peer:r.GetString(0),Kind:r.GetString(1),Body:JsonNode.Parse(r.GetString(2))!.AsObject(),Created:r.GetInt64(3),Attempted:!r.IsDBNull(4)),id,Now).FirstOrDefault();
                if(row.Peer is null)throw new VisibleFailure("Message is no longer pending.");
                if (expectedPeer is not null && row.Peer != expectedPeer) throw new VisibleFailure("Message is no longer pending in this conversation.");
                switch(action)
                {
                    case "cancel":
                        if(row.Attempted || db.Exists("SELECT 1 FROM protocol_outbox b WHERE b.event_id=$p0 UNION ALL SELECT 1 FROM send_related_events r JOIN protocol_outbox b ON b.event_id=r.event_id WHERE r.message_id=$p0",id))throw new VisibleFailure("This message may already have been sent; it cannot be cancelled.");
                        db.Execute("DELETE FROM protocol_pending_events WHERE event_id IN (SELECT event_id FROM send_related_events WHERE message_id=$p0)",id);
                        db.Execute("DELETE FROM protocol_pending_events WHERE event_id=$p0 AND recipient=$p1",id,row.Peer);db.Execute("DELETE FROM object_transfers WHERE event_id=$p0 AND direction=0",id);db.Execute("DELETE FROM messages WHERE messageId=$p0 AND isMine=1",id);break;
                    case "retry":
                        RequireAccepted(db,row.Peer);Enqueue(new Pending(id,row.Peer,row.Kind,row.Body,row.Created),db);
                        db.Execute("UPDATE protocol_outbox SET delivered=0 WHERE event_id=$p0 AND recipient=$p1",id,row.Peer);db.Execute("UPDATE messages SET status=MAX(status,0) WHERE messageId=$p0",id);
                        foreach(var manifest in new[]{row.Body["object"] as JsonObject,row.Body["preview"]?["image"] as JsonObject}.OfType<JsonObject>())
                        {var objectId=Text(manifest,"object_id");if(!db.Exists("SELECT 1 FROM media_objects WHERE object_id=$p0 AND local_state=1",objectId))throw new VisibleFailure("Attachment is unavailable.");AttachmentQueue.Record(db,id,objectId,true,row.Peer,row.Created);}
                        db.Execute("UPDATE object_transfers SET attempts=0,next_retry=0 WHERE event_id=$p0 AND direction=0",id);
                        if (SavedChange(db, _settings.MyUuid, id) is { } change && change["revoked"]?.GetValue<bool>() != true)
                        {
                            var edit = new JsonObject { ["message_id"] = id, ["revision"] = Number(change, "revision"), ["text"] = Text(change, "text") };
                            if (change["caption"]?.GetValue<bool>() == true) edit["caption"] = true;
                            Enqueue(PendingEvent(row.Peer, "chat.edit", edit), db);
                        }
                        break;
                    default:throw new VisibleFailure("Unsupported send action.");
                }
                _requested.Remove(row.Peer);_unavailableUntil.Remove(row.Peer);
            }
            _scheduleDeadlineLoaded=false;return true;
        },notify:true);_attachments.Wake();
    },token);

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
        _attachments.ProgressChanged-=ForwardAttachmentChanged;
        await Drain("coordinator cancellation", () => _lifetime.CancelAsync()).ConfigureAwait(false);
        await Drain("forwarding", () => Forwarding.DisposeAsync().AsTask()).ConfigureAwait(false);
        await Drain("attachment transfer", () => _attachments.DisposeAsync().AsTask()).ConfigureAwait(false);
        Task[] imports;
        lock(_importGate) imports=[.._imports];
        await Drain("imports and maintenance", () => Task.WhenAll(imports.Concat(
            [_worker, _maintenance, _storageMaintenance, _activityMaintenance, _scheduleMaintenance]))).ConfigureAwait(false);
        _storageWake.Dispose();
        _activityReceived=new(StringComparer.Ordinal);
        _lifetime.Dispose();

        static async Task Drain(string owner, Func<Task> complete)
        {
            try { await complete().ConfigureAwait(false); }
            catch (Exception error)
            {
                System.Diagnostics.Trace.TraceWarning("Messaging shutdown: {0} ended ({1})", owner, error.GetType().Name);
            }
        }
    }
}
