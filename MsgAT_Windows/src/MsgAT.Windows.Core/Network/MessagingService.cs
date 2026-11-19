using MsgAT.Windows.Core.Calls;
using System.Text.Json.Nodes;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Protocol;
using MsgAT.Windows.Core.Storage;

#pragma warning disable CA1068 // Preserve existing positional cancellation arguments across appended send options.
namespace MsgAT.Windows.Core.Network;

public sealed class MessagingService : IAsyncDisposable
{
    private readonly RelayClient _relay;
    private readonly SecureSettingsStore _settings;
    private readonly EncryptedDatabase _database;
    private readonly MediaStore _media;
    private readonly SecureMessaging _secure;

    public event EventHandler? CallHistoryChanged;
    public async Task<bool> RecordCallHistoryAsync(string peer, string callId, bool outgoing, bool video,
        CallOutcome outcome, long duration, long endedAt)
    {
        var recorded = await _secure.RecordCallHistoryAsync(peer,callId,outgoing,video,outcome,duration,endedAt).ConfigureAwait(false);
        if (recorded) CallHistoryChanged?.Invoke(this,EventArgs.Empty);
        return recorded;
    }

    public IReadOnlyList<CallHistoryEntry> CallHistoryPage(CallHistoryEntry? before, int count, bool missedOnly) =>
        _database.ProtocolTransaction(db => CallHistory.Page(db, before, count, missedOnly));

    public async Task<bool> RemoveCallHistoryAsync(CallHistoryEntry? entry)
    {
        var removed = await _secure.RemoveCallHistoryAsync(entry).ConfigureAwait(false);
        if (removed) CallHistoryChanged?.Invoke(this, EventArgs.Empty);
        return removed;
    }

    public MessagingService(
        RelayClient relay,
        SecureSettingsStore settings,
        EncryptedDatabase database,
        MediaStore media,
        EncryptedObjectStore objects)
    {
        _relay = relay;_settings=settings;
        _database = database;
        _media = media;
        Actions=new MessageActionsStore(database,settings,objects);
        _secure = new SecureMessaging(relay,settings,database,objects,Actions);
        _secure.CallReceived += signal => _ = DeliverCallAsync(signal);
    }

    public event EventHandler<NotificationRequest>? NotificationRequested
    {
        add => _secure.NotificationRequested += value;
        remove => _secure.NotificationRequested -= value;
    }
    public event EventHandler? SendQueueChanged {add=>_secure.SendQueueChanged+=value;remove=>_secure.SendQueueChanged-=value;}
    public event EventHandler? ContactsChanged {add=>_secure.ContactsChanged+=value;remove=>_secure.ContactsChanged-=value;}
    public event EventHandler<string>? ContactUnavailable {add=>_secure.ContactUnavailable+=value;remove=>_secure.ContactUnavailable-=value;}
    public event EventHandler<string>? PeerRevoked
    {
        add=>_secure.PeerRevoked+=value;
        remove=>_secure.PeerRevoked-=value;
    }
    public event EventHandler<string>? OperationFailed
    {
        add => _secure.OperationFailed += value;
        remove => _secure.OperationFailed -= value;
    }
    public MessageActionsStore Actions { get; }
    public ForwardTaskQueue Forwarding=>_secure.Forwarding;
    public async ValueTask DisposeAsync()
    {
        try {await _secure.DisposeAsync().ConfigureAwait(false);}
        finally {await Actions.DisposeAsync().ConfigureAwait(false);}
    }
    public event EventHandler<AttachmentProgress>? AttachmentProgressChanged
    {
        add=>_secure.AttachmentProgressChanged+=value;
        remove=>_secure.AttachmentProgressChanged-=value;
    }
    public AttachmentProgress? GetAttachmentProgress(string id)=>_secure.GetAttachmentProgress(id);
    public Task<bool> ForwardAsync(string? sourcePeer,IReadOnlyList<string> ids,IReadOnlyList<string> recipients,bool hideSender=false,bool hideCaption=false,CancellationToken token=default,SendOptions? options=null,bool omitPreview=false) =>
        _secure.ForwardAsync(sourcePeer,ids,recipients,hideSender,hideCaption,token,options,omitPreview);

    public Task<bool> SendTextAsync(string peerUuid,string text,string? replyToId=null,
        string? replyToSender=null,string? replyToContent=null,DraftSendCommit? draft=null,WebPreviewDraft? preview=null,CancellationToken cancellationToken=default,SendOptions? options=null,ObjectPreparation? preparation=null) =>
        _secure.SendTextAsync(peerUuid,text,replyToId,draft,preview,cancellationToken,options,preparation:preparation);

    public Task<bool> SendContactCardAsync(string peer, string account, string name, string? replyId,
        DraftSendCommit? draft = null, SendOptions? options = null, CancellationToken token = default) =>
        _secure.SendTextAsync(peer, name, replyId, draft, token: token, options: options, contactAccount: account);

    public Task<bool> SendLocationAsync(string peer, LocationPoint point, string? label, string? replyId,
        DraftSendCommit? draft = null, SendOptions? options = null, CancellationToken token = default) =>
        _secure.SendTextAsync(peer, label ?? "Location", replyId, draft, token: token, options: options, location: point);

    public Task<LiveLocationShare?> StartLiveLocationAsync(string peer, LocationPoint point, TimeSpan duration,
        string? label, string? replyId, DraftSendCommit? draft = null, bool silent = false, CancellationToken token = default) =>
        _secure.StartLiveLocationAsync(peer, point, duration, label, replyId, draft, silent, token);

    public LiveLocationSnapshot? LiveLocation(string peer, string messageId) => _secure.LiveLocation(peer, messageId);

    public Task<bool> UpdateLiveLocationAsync(string peer, string messageId, LocationPoint point, CancellationToken token = default) =>
        _secure.UpdateLiveLocationAsync(peer, messageId, point, token);

    public Task<bool> StopLiveLocationAsync(string peer, string messageId, CancellationToken token = default) =>
        _secure.StopLiveLocationAsync(peer, messageId, token);

    public async Task<bool> SendMediaAsync(
        string peerUuid,
        string sourcePath,
        string? replyToId = null,
        string? replyToSender = null,
        string? replyToContent = null,
        DraftSendCommit? draft = null,
        CancellationToken cancellationToken = default,
        SendOptions? options = null,ObjectPreparation? preparation=null,string caption="",bool asFile=false)
    {
        try
        {
            return await _secure.SendMediaAsync(peerUuid,sourcePath,asFile?"application/octet-stream":EncryptedObjectStore.MimeForPath(sourcePath),replyToId,draft,cancellationToken,options,preparation,caption).ConfigureAwait(false);
        }
        catch
        {
            if (draft is not null) draft.Owner.ObserveFailedSend(draft);
            throw;
        }
    }
    public Task<bool> SendMediaBatchAsync(string peer, IReadOnlyList<string> paths, string? replyId,
        DraftSendCommit? draft = null, CancellationToken token = default, SendOptions? options = null,
        ObjectPreparation? preparation = null, string caption = "", bool asFile = false, bool asAlbum = false,
        Func<string, string, CancellationToken, Task<byte[]?>>? compressPhoto = null, VideoEncoder? videoEncoder = null,
        bool compressVideos = false, IReadOnlyDictionary<string, VideoTrim>? videoTrims = null,
        IReadOnlyDictionary<string, EditedPhotoContent>? editedPhotos = null,
        Func<ReadOnlyMemory<byte>, string, CancellationToken, Task<byte[]?>>? compressEditedPhoto = null,
        IReadOnlyList<string>? itemCaptions = null) =>
        _secure.SendMediaBatchAsync(peer, paths, replyId, draft, token, options, preparation, caption, asFile, asAlbum,
            compressPhoto, videoEncoder, compressVideos, videoTrims, editedPhotos, compressEditedPhoto, itemCaptions);

    public Task<bool> SendMediaBytesAsync(string peerUuid, ReadOnlyMemory<byte> bytes, string filename, string mime,
        string? replyToId = null, DraftSendCommit? draft = null, CancellationToken cancellationToken = default,
        SendOptions? options = null, ObjectPreparation? preparation = null, string caption = "", bool asFile = false,
        Func<ReadOnlyMemory<byte>, string, CancellationToken, Task<byte[]?>>? compressPhoto = null) =>
        _secure.SendMediaBytesAsync(peerUuid, bytes, filename, asFile ? "application/octet-stream" : mime,
            replyToId, draft, cancellationToken, options, preparation, caption, asFile ? null : compressPhoto);
    public Task<bool> SendRecordingAsync(string peer,EncryptedCaptureFile source,string? replyId=null,DraftSendCommit? draft=null,CancellationToken token=default,SendOptions? options=null,ObjectPreparation? preparation=null,string caption="",bool asFile=false)=>
        _secure.SendRecordingAsync(peer,source,replyId,draft,token,options,preparation,caption,asFile);

    public Task<bool> SendStickerAsync(string peer,string id,string? replyId=null,DraftSendCommit? draft=null,CancellationToken token=default,SendOptions? options=null,ObjectPreparation? preparation=null)=>_secure.SendStickerAsync(peer,id,replyId,draft,token,options,preparation);

    public Task<IReadOnlyList<SendQueueEntry>> SendQueueAsync(string? peer=null,int offset=0,CancellationToken token=default,string? messageId=null,bool? scheduled=null,int limit=50)=>_secure.SendQueueAsync(peer,offset,token,messageId,scheduled,limit);
    public Task<bool> ManageSendAsync(string id, string action, long? sendAt = null, CancellationToken token = default,
        string? expectedPeer = null, long? expectedScheduledAt = null) =>
        _secure.ManageSendAsync(id, action, sendAt, token, expectedPeer, expectedScheduledAt);
    public Task<ScheduledMessageDraft?> ScheduledMessageDraftAsync(string id, string peer, long at, CancellationToken token) =>
        _secure.ScheduledMessageDraftAsync(id, peer, at, token);
    public Task<bool> EditScheduledMessageAsync(string id, string peer, long at, ScheduledMessageDraft original,
        string replacement, CancellationToken token) =>
        _secure.EditScheduledMessageAsync(id, peer, at, original, replacement, token);
    public Task<bool?> DeletionCanRevokeAsync(string peer, IReadOnlySet<string> ids, CancellationToken token = default) =>
        _secure.DeletionCanRevokeAsync(peer, ids, token);
    public Task<bool> DeleteLocalMessagesAsync(string peer, IReadOnlyList<string> ids, CancellationToken token = default) =>
        _secure.DeleteLocalMessagesAsync(peer, ids, token);

    public Task<bool> RevokeSentMessagesAsync(string peer, IReadOnlyList<string> ids, CancellationToken token = default) =>
        _secure.RevokeSentMessagesAsync(peer, ids, token);

    public Task<bool> MarkAllReadAsync(string peer,CancellationToken token=default)=>_secure.MarkAllReadAsync(peer,token);
    public Task<bool> MarkConversationReadAsync(string peerUuid,IReadOnlyCollection<string> visibleIds,CancellationToken cancellationToken=default) =>
        _secure.MarkReadAsync(peerUuid,visibleIds,cancellationToken);
    public Task<bool> ReadReceiptsEnabledAsync(CancellationToken token=default)=>_secure.ReadReceiptsEnabledAsync(token);
    public MessageAlertSettings NotificationSettings => _secure.NotificationSettings;
    public Task<bool> SetNotificationSettingsAsync(MessageAlertSettings settings, CancellationToken token = default) =>
        _secure.SetNotificationSettingsAsync(settings, token);
    public Task<MessageRetentionSettings> MessageRetentionAsync(string peer, CancellationToken token = default) =>
        _secure.MessageRetentionAsync(peer, token);
    public Task<bool> SetMessageRetentionAsync(string peer, MessageRetentionSettings settings, CancellationToken token = default) =>
        _secure.SetMessageRetentionAsync(peer, settings, token);
    public event EventHandler<IReadOnlySet<string>>? MessagesRemoved
    {
        add => _secure.MessagesRemoved += value;
        remove => _secure.MessagesRemoved -= value;
    }
    public Task<bool> SetReadReceiptsEnabledAsync(bool enabled,CancellationToken token=default)=>_secure.SetReadReceiptsEnabledAsync(enabled,token:token);
    public Task<ActivityPrivacy> ActivityPrivacyAsync(CancellationToken token=default)=>_secure.ActivityPrivacyAsync(token);
    public Task<bool> SetPrivacyAsync(bool read,bool online,bool typing,CancellationToken token=default,bool? autoDownload=null)=>
        _secure.SetReadReceiptsEnabledAsync(read,new ActivityPrivacy(online,typing),token,autoDownload);
    public Task<bool> AutomaticAttachmentDownloadsAsync(CancellationToken token=default) =>
        _secure.AutomaticAttachmentDownloadsAsync(token);
    public Task<bool> AppLockEnabledAsync(CancellationToken token=default) => _secure.AppLockEnabledAsync(token);
    public Task<bool> SetAppLockEnabledAsync(bool enabled, CancellationToken token=default) =>
        _secure.SetAppLockEnabledAsync(enabled, token);
    public Task<AttachmentCacheUsage> AttachmentCacheUsageAsync(CancellationToken token=default) =>
        _secure.AttachmentCacheUsageAsync(token);
    public Task<bool> ClearAttachmentCacheAsync(IReadOnlyCollection<AttachmentCacheCategory> categories, CancellationToken token=default) =>
        _secure.ClearAttachmentCacheAsync(categories, token);
    public Task<bool> SetAutomaticCacheLimitAsync(long bytes, CancellationToken token=default) =>
        _secure.SetAutomaticCacheLimitAsync(bytes, token);
    public Task<bool> RequestAttachmentDownloadAsync(string peer, string messageId, CancellationToken token=default) =>
        _secure.SetAttachmentDownloadRequestedAsync(peer, messageId, true, token);
    public Task<bool> PauseAttachmentDownloadAsync(string peer, string messageId, CancellationToken token=default) =>
        _secure.SetAttachmentDownloadRequestedAsync(peer, messageId, false, token);
    public Task<bool> SetPreviewDownloadRequestedAsync(string peer, string messageId, string reference, bool requested,
        bool replacingCache = false, CancellationToken token = default) =>
        _secure.SetPreviewDownloadRequestedAsync(peer, messageId, reference, requested, replacingCache, token);
    public Task<bool> ReplaceAttachmentCacheAsync(string peer, string messageId, CancellationToken token=default) =>
        _secure.ReplaceAttachmentCacheAsync(peer, messageId, null, token);
    public Task<bool> ReplaceAttachmentCacheAsync(string peer, string messageId, string expectedReference, CancellationToken token=default) =>
        _secure.ReplaceAttachmentCacheAsync(peer, messageId, expectedReference, token);
    public void SetForeground(bool value)=>_secure.SetForeground(value);
    public void SetActiveChat(string? peer,bool typing=false)=>_secure.SetActiveChat(peer,typing);
    public event EventHandler<string?>? PeerActivityChanged
    {
        add => _secure.PeerActivityChanged += value;
        remove => _secure.PeerActivityChanged -= value;
    }
    public (PeerActivityStatus Status, long Expires) GetPeerActivity(string peer) => _secure.GetPeerActivity(peer);

    public async Task RevokeMessageAsync(string peerUuid,string messageId,CancellationToken cancellationToken=default)
    {
        if(!await _secure.RevokeMessageAsync(peerUuid,messageId,cancellationToken).ConfigureAwait(false))
            throw new InvalidOperationException("The message could not be revoked.");
    }
    public Task<PinnedMessagePage> PinnedMessagesAsync(string peer,int offset=0,CancellationToken token=default)=>_secure.PinnedMessagesAsync(peer,offset,token);
    public async Task SetMessagePinnedAsync(string peer,string messageId,bool pinned,CancellationToken token=default,bool notify=false)
    {
        if(!await _secure.SetMessagePinnedAsync(peer,messageId,pinned,token,notify).ConfigureAwait(false))throw new InvalidOperationException("The pin change could not be queued.");
    }
    public async Task EditMessageAsync(string peerUuid,string messageId,string newContent,CancellationToken cancellationToken=default)
    {
        if(!await _secure.EditMessageAsync(peerUuid,messageId,newContent,cancellationToken).ConfigureAwait(false))
            throw new InvalidOperationException("The message could not be edited.");
    }

    public async Task AddFriendAsync(string rawUuid,CancellationToken cancellationToken=default){
        if(!await _secure.RequestContactAsync(NormalizeUuid(rawUuid),cancellationToken).ConfigureAwait(false))throw new InvalidOperationException("Contact request could not be queued.");
    }
    public async Task AcceptFriendRequestAsync(string senderUuid,CancellationToken cancellationToken=default){
        if(!await _secure.AcceptContactAsync(senderUuid,cancellationToken).ConfigureAwait(false))throw new InvalidOperationException("Contact request could not be accepted.");
    }
    public async Task DeclineFriendRequestAsync(string senderUuid,CancellationToken cancellationToken=default){
        if(!await _secure.DeclineContactAsync(senderUuid,cancellationToken).ConfigureAwait(false))throw new InvalidOperationException("Contact decision could not be queued.");
    }

    public async Task DeleteFriendAsync(string peerUuid,CancellationToken cancellationToken=default)
    {
        var messages=_database.GetAllMessages(peerUuid);
        if(!await _secure.DeleteContactAsync(peerUuid,cancellationToken).ConfigureAwait(false))throw new InvalidOperationException("Contact could not be removed.");
        CallHistoryChanged?.Invoke(this, EventArgs.Empty);
        foreach (var message in messages) if (message.Extension!="txt") _media.Delete(message.MessageId,message.Extension);
    }

    public async Task ClearMessagesAsync(string peerUuid,CancellationToken token=default)
    {
        if(!await _secure.ClearMessagesAsync(peerUuid,token).ConfigureAwait(false)) throw new InvalidOperationException("The conversation could not be cleared.");
        CallHistoryChanged?.Invoke(this, EventArgs.Empty);
    }

    public Task<bool> WithdrawContactAsync(string peer,CancellationToken token=default)=>_secure.WithdrawContactAsync(peer,token);
    public Task<bool> SetContactBlockedAsync(string peer,bool value,CancellationToken token=default)=>_secure.SetContactBlockedAsync(peer,value,token);

    public Task SendProfileAsync(CancellationToken cancellationToken = default) => _secure.BroadcastProfileAsync(cancellationToken);
    public UserProfileSnapshot? LocalProfile => _secure.LocalProfile;
    public async Task<bool> UpdateProfileAsync(string nickname,ReadOnlyMemory<byte>? jpeg=null,bool removeAvatar=false,CancellationToken token=default)
    {
        var saved=await _secure.UpdateProfileAsync(nickname,jpeg,removeAvatar,token).ConfigureAwait(false);
        if(saved && _secure.LocalProfile is { } profile) _settings.Update(current=>current with {Nickname=profile.Nickname,AvatarPath=profile.AvatarReference??""});
        return saved;
    }

    public ICallSignalingSink? CallSink { get; set; }
    private async Task DeliverCallAsync(CallSignal signal)
    {
        try { if (CallSink is { } sink) await sink.HandleCallSignalAsync(signal).ConfigureAwait(false); }
        catch { /* SDP, ICE addresses and peer payloads must never enter exception logs. */ }
    }
    public Task<bool> RequireCallableAsync(string peerUuid,CancellationToken token=default) => _secure.RequireCallableAsync(peerUuid,token);
    public Task<bool> SendCallSignalAsync(string peerUuid,string callId,string kind,JsonObject? values=null,CancellationToken token=default)
    {
        var now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var body=values ?? new JsonObject();
        body["call_id"]=callId; body["expires_at"]=now+120_000;
        return _secure.SendCallSignalAsync(peerUuid,kind,body,now,token);
    }

    private static string NormalizeUuid(string value)
    {
        var uuid = value.Trim().ToLowerInvariant();
        if (uuid.Length != 64 || uuid.Any(static character => !Uri.IsHexDigit(character)))
        {
            throw new ArgumentException("A friend UUID must contain exactly 64 hexadecimal characters.");
        }
        return uuid;
    }
}
