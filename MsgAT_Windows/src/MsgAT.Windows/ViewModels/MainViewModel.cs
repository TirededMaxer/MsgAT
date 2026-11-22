using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows;
using System.Windows.Threading;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.ViewModels;

internal readonly record struct ChatHistoryPosition(string MessageId, double Offset);
internal enum HistoryPage { Older, Newer, Latest }

internal static class CallSummaryText
{
    private static readonly System.Text.RegularExpressions.Regex Pattern = new(
        @"\A\[(Voice|Video) Call: (Missed|Declined|Busy|Canceled|Failed|[0-9]{2,12}:[0-5][0-9])\]\z");

    internal static string Localize(string text)
    {
        var match = Pattern.Match(text);
        if (!match.Success) return text;
        var type = AppStrings.Get(match.Groups[1].Value == "Video" ? "UiVideoCall" : "UiVoiceCall");
        var detail = match.Groups[2].Value;
        var key = detail switch
        {
            "Missed" => "UiCallMissed",
            "Declined" => "UiCallDeclined",
            "Busy" => "UiCallBusy",
            "Canceled" => "UiCallCanceled",
            "Failed" => "UiCallFailed",
            _ => null
        };
        return $"{type} · {(key is null ? detail : AppStrings.Get(key))}";
    }
}

public sealed record PendingPreparation(string Id, string Peer, string? Filename, int FileCount, bool IsSticker,
    PreparationProgress Progress, bool SavingMessages = false)
{
    public string Label => SavingMessages ? AppStrings.Get("UiSavedMessages") :
        Filename ?? (IsSticker ? AppStrings.Get("UiStickers") : AppStrings.Format("UiPreparationBatch", FileCount));
    private string PhaseStatus => Progress.Phase switch
    {
        "canceling" => AppStrings.Get("UiPreparationCanceling"),
        "committing" => AppStrings.Get(SavingMessages ? "UiSaving" : "UiPreparationQueuing"),
        "finished" => AppStrings.Get("UiPreparationFinishing"),
        _ when Progress.Step == PreparationStep.EncodingPhoto => AppStrings.Get("UiPreparationPhotoWaiting"),
        _ when Progress.Total > 0 => AppStrings.Format(
            Progress.Step == PreparationStep.EncodingVideo ? "UiPreparationVideoProgress" : "UiPreparationProgress",
            (int)(Math.Clamp((double)Progress.Completed / Progress.Total, 0, 1) * 100)),
        _ => AppStrings.Get(Progress.Step == PreparationStep.EncodingVideo ? "UiPreparationVideoWaiting" : "UiPreparationWaiting")
    };
    private int AttachmentCount => SavingMessages ? Progress.AttachmentCount : FileCount;
    public string Status => Progress.Phase == "preparing" && AttachmentCount > 1
        ? AppStrings.Format("UiPreparationAttachment", Progress.Attachment, AttachmentCount) + " · " + PhaseStatus
        : PhaseStatus;
    public bool Indeterminate=>Progress.Total==0 || Progress.Phase!="preparing";
}

public sealed class MainViewModel : ObservableObject, IDisposable
{
    private sealed record Preparing(string Id, ObjectPreparation Owner);
    private readonly Dictionary<string,Preparing> _preparing=new(StringComparer.Ordinal);
    private readonly DispatcherTimer _preparationTimer=new(){Interval=TimeSpan.FromMilliseconds(200)};
    private IReadOnlyList<PendingPreparation> _preparationRows=[];
    private bool _preparationPollBusy;
    public IReadOnlyList<PendingPreparation> PendingPreparations=>_preparationRows.Where(row=>row.Peer==SelectedConversation?.Uuid).ToArray();
    public bool HasPendingPreparations=>PendingPreparations.Count>0;
    public bool HasLiveLocationShare => _services.LiveLocations.Current(SelectedConversation?.Uuid) is not null;
    public string LiveLocationShareStatus => _services.LiveLocations.Foreground ? AppStrings.Get("UiLiveLocationActive") : AppStrings.Get("UiLiveLocationPaused");

    private void LiveLocationsChanged(object? sender, EventArgs args)
    {
        OnPropertyChanged(nameof(HasLiveLocationShare));
        OnPropertyChanged(nameof(LiveLocationShareStatus));
        foreach (var item in Messages.Where(item => item.IsLiveLocation && item.IsMine))
            item.RefreshLiveStatus(_services.LiveLocations.Foreground &&
                _services.LiveLocations.Current(item.Source.ConversationUuid)?.MessageId == item.MessageId);
    }

    internal void RefreshMessageLanguage()
    {
        OnPropertyChanged(nameof(LiveLocationShareStatus));
        foreach (var item in Messages)
        {
            item.RefreshMessageLanguage();
        }
    }

    public Task<bool> StopCurrentLiveLocationAsync()
    {
        var peer = SelectedConversation?.Uuid;
        return peer is not null && _services.LiveLocations.Current(peer) is { } share
            ? _services.LiveLocations.StopAsync(peer, share.MessageId) : Task.FromResult(true);
    }
    private void NotifyPreparations(){if(_disposed)return;OnPropertyChanged(nameof(PendingPreparations));OnPropertyChanged(nameof(HasPendingPreparations));}
    private Preparing BeginPreparation(string peer, string? filename = null, int fileCount = 1, bool isSticker = false,
        bool savingMessages = false)
    {
        if(_preparing.Count>=4)throw new InvalidOperationException("Wait until an attachment has finished preparing.");
        var value = new Preparing(Guid.NewGuid().ToString("D"), new ObjectPreparation());
        _preparing.Add(value.Id, value);
        _preparationRows = _preparationRows.Append(new PendingPreparation(value.Id, peer, filename,
            fileCount, isSticker, value.Owner.Snapshot(), savingMessages)).ToArray();
        NotifyPreparations();_preparationTimer.Start();return value;
    }
    private void EndPreparation(Preparing? value)
    {
        if(value is null)return;value.Owner.Dispose();_preparing.Remove(value.Id);_preparationRows=_preparationRows.Where(row=>row.Id!=value.Id).ToArray();
        if(_preparing.Count==0)_preparationTimer.Stop();NotifyPreparations();
    }
    public void CancelPreparation(string id)
    {
        if(!_preparing.TryGetValue(id,out var value))return;value.Owner.Cancel();var next=value.Owner.Snapshot();
        _preparationRows=_preparationRows.Select(row=>row.Id==id?row with{Progress=next}:row).ToArray();NotifyPreparations();
    }
    private async Task RefreshPreparationsAsync()
    {
        if(_disposed || _preparationPollBusy)return;_preparationPollBusy=true;var owners=_preparing.Values.ToArray();
        try
        {
            var snapshots = await Task.Run(() => owners.ToDictionary(value => value.Id,
                value => value.Owner.Snapshot(), StringComparer.Ordinal));
            if(_disposed)return;
            _preparationRows = _preparationRows.Select(row =>
                snapshots.TryGetValue(row.Id, out var next) &&
                (next.Version > row.Progress.Version ||
                 next.Version == row.Progress.Version && next.Completed >= row.Progress.Completed)
                    ? row with { Progress = next }
                    : row).ToArray();
            NotifyPreparations();
        }
        finally{_preparationPollBusy=false;}
    }
    public sealed class FolderChoice(string key, string icon) : ObservableObject
    {
        private string _title = "";
        private int _unreadCount;

        public string Key { get; } = key;
        public string Icon { get; } = icon;
        public string Title => _title;
        public bool HasUnread => _unreadCount > 0;
        public string UnreadLabel => _unreadCount > 99 ? "99+" : _unreadCount.ToString(CultureInfo.CurrentCulture);
        public string AccessibleName => HasUnread ? $"{Title}, {AppStrings.Format("UiChatUnreadCount", _unreadCount)}" : Title;

        public void Update(string title, int unreadCount)
        {
            var titleChanged = SetProperty(ref _title, title, nameof(Title));
            var unreadChanged = SetProperty(ref _unreadCount, unreadCount, nameof(UnreadLabel));
            if (unreadChanged) OnPropertyChanged(nameof(HasUnread));
            if (titleChanged || unreadChanged) OnPropertyChanged(nameof(AccessibleName));
        }
    }
    public ObservableCollection<ConversationItem> VisibleConversations {get;}=[];
    public ObservableCollection<FolderChoice> FolderChoices {get;}=[];
    public FolderChoice ArchiveEntry { get; } = new("archive", "\uE7B8");
    public bool ShowArchiveEntry => FolderKey == "all" && Conversations.Any(item => item.Archived);
    private IReadOnlyList<ConversationFolder> _conversationFolders=[];
    private readonly DispatcherTimer _conversationClock=new(){Interval=TimeSpan.FromMinutes(1)};
    private bool _filteringConversations;
    private bool _updatingFolderChoices;
    private string _folderKey="all";
    public string FolderKey
    {
        get => _folderKey;
        set
        {
            if (_updatingFolderChoices) return;
            var archiveTabChanged = _folderKey == "archive" || value == "archive";
            if (string.IsNullOrEmpty(value) || !SetProperty(ref _folderKey, value)) return;
            OnPropertyChanged(nameof(ShowArchiveEntry));
            if (archiveTabChanged) RefreshFolderChoices();
            else RefreshVisibleConversations();
        }
    }
    public string? CurrentCustomFolderId => _conversationFolders.FirstOrDefault(folder => folder.Id == FolderKey)?.Id;
    private bool Includes(ConversationItem row,string key)
    {
        if(key=="all")return !row.Archived;if(key=="archive")return row.Archived;if(key=="unread")return !row.Archived && row.HasUnread;
        var folder=_conversationFolders.FirstOrDefault(value=>value.Id==key);return folder is not null && (folder.IncludeAll || row.FolderIDs.Contains(key)) && (folder.IncludeArchived || !row.Archived) && (!folder.UnreadOnly || row.HasUnread) && (!folder.ExcludeMuted || !row.IsMuted);
    }
    private void RefreshVisibleConversations()
    {
        _filteringConversations=true;
        try{
            var folderId = CurrentCustomFolderId;
            var included = Conversations.Where(row => Includes(row, FolderKey));
            var rows = (folderId is null ? included : included
                .OrderByDescending(row => row.PinOrderIn(folderId))
                .ThenByDescending(row => row.ActivityAt)
                .ThenBy(row => row.Uuid, StringComparer.Ordinal)).ToArray();
            foreach (var row in rows) row.ShowPinOrderFor(folderId);
            for(var index=0;index<rows.Length;index++){var row=rows[index];var old=VisibleConversations.IndexOf(row);if(old<0)VisibleConversations.Insert(index,row);else if(old!=index)VisibleConversations.Move(old,index);}
            for(var index=VisibleConversations.Count-1;index>=0;index--)if(!rows.Contains(VisibleConversations[index]))VisibleConversations.RemoveAt(index);
        }finally{_filteringConversations=false;}
    }
    internal void RefreshFolderChoices()
    {
        ArchiveEntry.Update(AppStrings.Get("UiArchive"), Conversations.Count(item => item.Archived && item.HasUnread));
        OnPropertyChanged(nameof(ShowArchiveEntry));
        var choices = new[]
        {
            (Key: "all", Title: AppStrings.Get("UiAllChats"), Icon: "\uE8F2"),
            (Key: "unread", Title: AppStrings.Get("UiUnreadChats"), Icon: "\uE715"),
            (Key: "archive", Title: AppStrings.Get("UiArchive"), Icon: "\uE7B8")
        }.Where(choice => choice.Key != "archive" || FolderKey == "archive")
            .Concat(_conversationFolders.Select(folder => (Key: folder.Id, Title: folder.Name, Icon: "\uE8B7"))).ToArray();
        var selectedKey = choices.Any(choice => choice.Key == FolderKey) ? FolderKey : "all";
        _updatingFolderChoices = true;
        try
        {
            for (var index = 0; index < choices.Length; index++)
            {
                var choice = choices[index];
                var row = FolderChoices.FirstOrDefault(item => item.Key == choice.Key);
                var count = Conversations.Count(item => item.HasUnread && Includes(item, choice.Key));
                if (row is null)
                {
                    row = new FolderChoice(choice.Key, choice.Icon);
                    row.Update(choice.Title, count);
                    FolderChoices.Insert(index, row);
                }
                else
                {
                    row.Update(choice.Title, count);
                    var previousIndex = FolderChoices.IndexOf(row);
                    if (previousIndex != index) FolderChoices.Move(previousIndex, index);
                }
            }
            while (FolderChoices.Count > choices.Length) FolderChoices.RemoveAt(FolderChoices.Count - 1);
            _folderKey = selectedKey;
        }
        finally
        {
            _updatingFolderChoices = false;
        }
        OnPropertyChanged(nameof(FolderKey));
        OnPropertyChanged(nameof(ShowArchiveEntry));
        RefreshVisibleConversations();
    }

    public async Task ChangeConversationAsync(string peer,string action,long? until=null,string? folderId=null)
    {
        try
        {
            if (action == "read") await _services.Messaging.MarkAllReadAsync(peer);
            else if (await _services.ConversationIndex.ChangeAsync(peer, action, until, folderId) &&
                     action == "mute" && folderId is null && !_disposed &&
                     (until is null || until > DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()))
                (System.Windows.Application.Current as App)?.DismissMessageNotifications(peer);
        }
        catch(OperationCanceledException) when(_disposed || _services.IsDisposed){}
        catch(Exception error) when(error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException or ArgumentException){if(!_disposed)ErrorRequested?.Invoke(this,"Conversation could not be updated.");}
    }

    public async Task MovePinAsync(string scope, string peer, string target, bool before)
    {
        try
        {
            if (!await _services.ConversationIndex.MovePinAsync(scope, peer, target, before) && !_disposed)
                ErrorRequested?.Invoke(this, "Pinned chats could not be reordered.");
        }
        catch (OperationCanceledException) when (_disposed || _services.IsDisposed) { }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException or ArgumentException)
        {
            if (!_disposed) ErrorRequested?.Invoke(this, "Pinned chats could not be reordered.");
        }
    }

    private const int PageSize = 50;
    private readonly AppServices _services;
    private readonly Dispatcher _dispatcher;
    private ConversationItem? _selectedConversation;
    private string _draft = "";
    private bool _showAttachments;
    private bool _isViewingLatest = true;
    private string _syncText = "Disconnected";
    private bool _hasOlderMessages;
    private bool _historyLoadFailed;
    public bool HistoryLoadFailed
    {
        get => _historyLoadFailed;
        private set => SetProperty(ref _historyLoadFailed, value);
    }
    private Action? _retryHistoryLoad;
    private bool _initialHistoryLoading;
    private bool _historyRefreshPending;
    private CancellationTokenSource? _initialHistoryCancellation;
    private Task _initialHistoryLoad = Task.CompletedTask;
    public bool InitialHistoryLoading
    {
        get => _initialHistoryLoading;
        private set => SetProperty(ref _initialHistoryLoading, value);
    }
    private CancellationTokenSource? _historyPageCancellation;
    private Task<bool> _historyPageLoad = Task.FromResult(false);
    public bool HistoryPageLoading => _historyPageCancellation is not null;
    internal Task WorkspaceSearchCompletion { get; set; } = Task.CompletedTask;
    internal Task WorkspaceAttachmentCompletion { get; set; } = Task.CompletedTask;
    internal Task WorkspaceMessageActionCompletion { get; set; } = Task.CompletedTask;
    internal Task HistoryLoadCompletion => Task.WhenAll(_initialHistoryLoad, _historyPageLoad,
        WorkspaceSearchCompletion, WorkspaceAttachmentCompletion, WorkspaceMessageActionCompletion);
    private bool _hasNewerMessages;
    private MessageItem? _replyTarget;
    private bool _disposed;
    private bool _refreshQueued;
    private long _changeRevision;
    private long _appliedChangeRevision;
    private Task? _initialWorkspaceLoad;
    private bool _workspaceReady;
    private bool _workspaceLoadFailed;
    public bool IsWorkspaceReady { get => _workspaceReady; private set => SetProperty(ref _workspaceReady, value); }
    public bool WorkspaceLoadFailed { get => _workspaceLoadFailed; private set => SetProperty(ref _workspaceLoadFailed, value); }
    internal Task InitialWorkspaceLoadCompletion => _initialWorkspaceLoad ?? Task.CompletedTask;
    private readonly DispatcherTimer _dataRefreshTimer=new(DispatcherPriority.DataBind){Interval=TimeSpan.FromMilliseconds(16)};
    private ConversationDraft? _draftState;
    private long _draftGeneration;
    private bool _restoringDraft;
    private readonly HashSet<(string Peer, string Token, long Revision)> _sendingDrafts = [];
    private readonly HashSet<string> _pendingTextSends = new(StringComparer.Ordinal);
    public bool TextSendPending => SelectedConversation is { } conversation && _pendingTextSends.Contains(conversation.Uuid);
    private readonly HashSet<string> _selectedMessages=new(StringComparer.Ordinal);
    private string? _selectionAnchor;
    private long _selectionVersion;
    private bool _savingSelection;

    private CancellationTokenSource? _previewCancellation;
    private WebPreviewDraft? _webPreview;
    private IReadOnlyList<string> _previewChoices = [];
    private string? _previewTarget;
    private bool _previewAboveText;
    private bool _previewCompactImage;
    private bool _previewLoading;
    private string? _chosenPreview;
    private string? _removedPreview;
    private bool _previewEnabled;
    public WebPreviewDraft? WebPreview
    {
        get => _webPreview;
        private set { if (SetProperty(ref _webPreview, value)) OnPropertyChanged(nameof(CanRetryWebPreview)); }
    }
    public IReadOnlyList<string> PreviewChoices => _previewChoices;
    public string? PreviewTarget => _previewTarget;
    public bool PreviewAboveText { get => _previewAboveText; private set => SetProperty(ref _previewAboveText, value); }
    public bool PreviewCompactImage { get => _previewCompactImage; private set => SetProperty(ref _previewCompactImage, value); }
    public bool PreviewLoading
    {
        get => _previewLoading;
        private set { if (SetProperty(ref _previewLoading, value)) OnPropertyChanged(nameof(CanRetryWebPreview)); }
    }
    public bool HasPreviewLinks => PreviewChoices.Count > 0;
    public bool HasWebPreview => PreviewTarget is not null;
    public bool CanRetryWebPreview => PreviewEnabled && HasWebPreview && !PreviewLoading && WebPreview is null;
    public bool CanChooseWebPreview => PreviewEnabled && HasPreviewLinks && (PreviewChoices.Count > 1 || !HasWebPreview);

    public bool PreviewEnabled {
        get=>_previewEnabled;
        set {
            if(_previewEnabled==value)return;
            try {_services.Previews.Enabled=value;_previewEnabled=value;OnPropertyChanged(nameof(PreviewEnabled));RefreshWebPreview();}
            catch(Exception){ErrorRequested?.Invoke(this,"Link preview preference could not be saved.");}
        }
    }
    public void SelectWebPreview(string url)
    {
        if (_disposed || !PreviewEnabled || !PreviewChoices.Contains(url)) return;
        _chosenPreview = url;
        _removedPreview = null;
        RefreshWebPreview();
    }

    public void ToggleWebPreviewPlacement()
    {
        if (HasWebPreview) PreviewAboveText = !PreviewAboveText;
    }

    public void ToggleWebPreviewImageSize()
    {
        if (WebPreview?.Image is not null) PreviewCompactImage = !PreviewCompactImage;
    }

    public void RemoveWebPreview()
    {
        if (PreviewTarget is null) return;
        _removedPreview = PreviewTarget;
        RefreshWebPreview();
    }

    public void RetryWebPreview()
    {
        if (_disposed || !CanRetryWebPreview || PreviewTarget is not { } url) return;
        StartWebPreviewLoad(url);
    }

    private void StartWebPreviewLoad(string url)
    {
        _previewCancellation?.Dispose();
        _previewCancellation = new CancellationTokenSource();
        PreviewLoading = true;
        _ = LoadWebPreviewAsync(url, _previewCancellation.Token);
    }

    private void CancelWebPreview() {
        _previewCancellation?.Cancel();_previewCancellation?.Dispose();_previewCancellation=null;
        WebPreview?.ClearImage();WebPreview=null;
        PreviewLoading = false;
    }
    private void RefreshWebPreview(string? text = null)
    {
        if (_disposed) return;
        string[] choices;
        try { choices = WebPreviewFetcher.Candidates(text ?? Draft); }
        catch { choices = []; }
        if (choices.Length == 0)
        {
            PreviewAboveText = false;
            PreviewCompactImage = false;
        }
        if (!_previewChoices.SequenceEqual(choices))
        {
            _previewChoices = choices;
            OnPropertyChanged(nameof(PreviewChoices));
            OnPropertyChanged(nameof(HasPreviewLinks));
        }
        if (_chosenPreview is { } chosen && !choices.Contains(chosen)) _chosenPreview = null;
        if (_removedPreview is { } removed && !choices.Contains(removed)) _removedPreview = null;
        var selection = _chosenPreview ?? choices.FirstOrDefault();
        var url = PreviewEnabled && selection != _removedPreview ? selection : null;
        if (url != _previewTarget)
        {
            CancelWebPreview();
            SetProperty(ref _previewTarget, url, nameof(PreviewTarget));
            OnPropertyChanged(nameof(HasWebPreview));
            if (url is not null) StartWebPreviewLoad(url);
        }
        OnPropertyChanged(nameof(CanChooseWebPreview));
        OnPropertyChanged(nameof(CanRetryWebPreview));
    }
    private async Task LoadWebPreviewAsync(string url,CancellationToken token) {
        try {
            await Task.Delay(600,token);
            var value=await WebPreviewFetcher.FetchUrlAsync(url,bytes=>{using var input=new MemoryStream(bytes);return AvatarProcessor.EncodeJpeg(input);},token);
            if(token.IsCancellationRequested || _disposed || PreviewTarget!=url){value?.ClearImage();return;}
            WebPreview=value;
            PreviewLoading = false;
        } catch(OperationCanceledException) { }
    }

    public MainViewModel(AppServices services, Dispatcher dispatcher)
    {
        _services = services;
        _dispatcher = dispatcher;
        _dataRefreshTimer.Tick+=(_,_)=>{
            _dataRefreshTimer.Stop();_refreshQueued=false;
            if(_disposed || _services.IsDisposed)return;
            var target=Volatile.Read(ref _changeRevision);
            try{RefreshMessages();MessagesRefreshed?.Invoke(this,EventArgs.Empty);}finally{_appliedChangeRevision=target;if(!_disposed && !_services.IsDisposed && Volatile.Read(ref _changeRevision)!=_appliedChangeRevision)QueueDataRefresh();}
        };
        _conversationClock.Tick+=(_,_)=>{if(!_disposed){foreach(var row in Conversations)row.RefreshClock();RefreshFolderChoices();foreach(var row in Messages.Where(row=>row.IsLiveLocation))row.RefreshLiveClock();}};_conversationClock.Start();
        _services.LiveLocations.Changed += LiveLocationsChanged;
        _preparationTimer.Tick+=async(_,_)=>await RefreshPreparationsAsync();
        _previewEnabled=services.Previews.Enabled;
        _services.Database.Changed += DatabaseChanged;
        _services.Messaging.ContactsChanged += ContactsChanged;
        _services.Relay.StateChanged += RelayStateChanged;
        _services.Messaging.OperationFailed += SecureOperationFailed;
        _services.Messaging.AttachmentProgressChanged += AttachmentProgressChanged;
        _services.Drafts.SaveFailed += DraftSaveFailed;
        RelayStateChanged(this, _services.Relay.State);
    }

    internal Task LoadInitialWorkspaceAsync()
    {
        if (IsWorkspaceReady || _disposed || _services.IsDisposed) return Task.CompletedTask;
        if (_initialWorkspaceLoad is { IsCompleted: false } active) return active;
        return _initialWorkspaceLoad = ReadInitialWorkspaceAsync();
    }

    private async Task ReadInitialWorkspaceAsync()
    {
        WorkspaceLoadFailed = false;
        var requestedRevision = Volatile.Read(ref _changeRevision);
        try
        {
            var snapshot = await Task.Run(() => (Folders: _services.ConversationIndex.Folders(),
                Chats: _services.Database.GetConversations(), Requests: _services.Database.GetFriendRequests()));
            if (_disposed || _services.IsDisposed) return;
            _conversationFolders = snapshot.Folders;
            ApplyConversations(snapshot.Chats);
            ApplyFriendRequests(snapshot.Requests);
            _appliedChangeRevision = requestedRevision;
            IsWorkspaceReady = true;
            if (Volatile.Read(ref _changeRevision) != requestedRevision) QueueDataRefresh();
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_disposed && !_services.IsDisposed) WorkspaceLoadFailed = true;
        }
    }

    public event EventHandler<string>? ErrorRequested;
    public event EventHandler? InitialMessagesLoaded;
    private PeerActivityStatus _peerActivity;
    public PeerActivityStatus PeerActivity
    {
        get => _peerActivity;
        set => SetProperty(ref _peerActivity, value);
    }
    public event EventHandler? LatestMessagesChanged;
    public event EventHandler? MessagesRefreshed;
    public event EventHandler? VisibleReadsApplied;

    public ObservableCollection<ConversationItem> Conversations { get; } = [];
    public ObservableCollection<MessageItem> Messages { get; } = [];
    public ObservableCollection<FriendRequest> FriendRequests { get; } = [];
    private PinnedMessageItem? _pinnedMessage;
    private PinnedMessageItem? _pinnedPreviewAnchor;
    private PinnedMessageItem? _nextPinnedPreview;
    public PinnedMessageItem? PinnedMessage=>_pinnedMessage;
    public bool HasPinnedMessage=>_pinnedMessage is not null;
    public bool CanCyclePinnedPreview { get; private set; }

    internal void CyclePinnedPreview()
    {
        if (CanCyclePinnedPreview) RefreshPinnedMessage(_nextPinnedPreview);
    }

    private void RefreshPinnedMessage(PinnedMessageItem? anchor)
    {
        var previousCanCycle = CanCyclePinnedPreview;
        PinnedMessagePage? page = null;
        if (SelectedConversation is { } peer)
        {
            var local = _services.Settings.MyUuid;
            page = _services.Database.GetPinnedMessages(peer.Uuid, local, pageSize: 2, through: anchor);
            if (anchor is not null && (page.Items.Count == 0 || page.Items[0].Id != anchor.Id))
            {
                anchor = null;
                page = _services.Database.GetPinnedMessages(peer.Uuid, local, pageSize: 2);
            }
        }
        _pinnedPreviewAnchor = page is null ? null : anchor;
        _nextPinnedPreview = null;
        var value = page is { Items.Count: > 0 } ? page.Items[0] : null;
        if (page is not null && value is not null && page.Items.Count > 1)
        {
            var candidate = page.Items[1];
            if (candidate.Timestamp < value.Timestamp ||
                (candidate.Timestamp == value.Timestamp && StringComparer.Ordinal.Compare(candidate.Id, value.Id) < 0))
                _nextPinnedPreview = candidate;
        }
        CanCyclePinnedPreview = page?.Items.Count > 1;
        if (!Equals(value, _pinnedMessage))
        {
            _pinnedMessage = value;
            OnPropertyChanged(nameof(PinnedMessage));
            OnPropertyChanged(nameof(HasPinnedMessage));
        }
        if (previousCanCycle != CanCyclePinnedPreview) OnPropertyChanged(nameof(CanCyclePinnedPreview));
    }

    public ConversationItem? SelectedConversation
    {
        get => _selectedConversation;
        set
        {
            if(value is null && _filteringConversations)return;
            if (EqualityComparer<ConversationItem?>.Default.Equals(_selectedConversation, value)) return;
            if (_selectedConversation is { } previous) SaveHistoryPosition(previous.Uuid);
            if (!SetProperty(ref _selectedConversation, value)) { return; }
            RefreshChatPeerVerification();
            OnPropertyChanged(nameof(TextSendPending));
            _unreadTimelineAnchor = null;
            CancelMessageEdit();
            OnPropertyChanged(nameof(HasLiveLocationShare));
            OnPropertyChanged(nameof(LiveLocationShareStatus));
            RefreshWebPreview("");
            ClearSelection();
            _draftState = null;
            var generation = ++_draftGeneration;
            _restoringDraft = true;
            ReplyTarget = null;
            Draft = "";
            _restoringDraft = false;
            SetSendOptions(new());NotifyPreparations();
            ShowAttachments = false;
            IsViewingLatest = true;
            OnPropertyChanged(nameof(HasConversation));
            OnPropertyChanged(nameof(ShowLatestButton));
            OnPropertyChanged(nameof(CanCompose));
            LoadInitialMessages();
            RefreshPinnedMessage(null);
            if (value is not null){_ = ChangeConversationAsync(value.Uuid,"clear_unread");_ = LoadDraftAsync(value.Uuid,generation);}
        }
    }

    private bool _chatPeerVerified;
    private long _verificationRevision;
    public bool ChatPeerVerified
    {
        get => _chatPeerVerified;
        private set => SetProperty(ref _chatPeerVerified, value);
    }

    private void RefreshChatPeerVerification()
    {
        ChatPeerVerified = false;
        var revision = ++_verificationRevision;
        if (!_disposed && !_services.IsDisposed && SelectedConversation is { } conversation)
            _ = ReadChatPeerVerificationAsync(conversation.Uuid, revision);
    }

    private async Task ReadChatPeerVerificationAsync(string peer, long revision)
    {
        bool verified;
        try { verified = await Task.Run(() => _services.Contacts.IsVerified(peer)); }
        catch (Exception) { return; }
        if (!_disposed && !_services.IsDisposed && revision == _verificationRevision &&
            SelectedConversation?.Uuid == peer)
            ChatPeerVerified = verified;
    }

    private void ContactsChanged(object? sender, EventArgs args) => QueueChatPeerVerification();

    private void QueueChatPeerVerification(string? peer = null)
    {
        if (_disposed || _dispatcher.HasShutdownStarted) return;
        _dispatcher.BeginInvoke(DispatcherPriority.DataBind, new Action(() =>
        {
            if (!_disposed && (peer is null || SelectedConversation?.Uuid == peer))
                RefreshChatPeerVerification();
        }));
    }

    public string Draft
    {
        get => _draft;
        set
        {
            if (!SetProperty(ref _draft, value)) { return; }
            OnPropertyChanged(nameof(HasDraft));
            if (!string.IsNullOrEmpty(Draft)) { ShowAttachments = false; }
            PersistDraft();RefreshWebPreview();
        }
    }
    public bool HasDraft => !string.IsNullOrWhiteSpace(Draft);
    public bool ShowAttachments { get => _showAttachments; set => SetProperty(ref _showAttachments, value); }
    public string SyncText
    {
        get => _syncText;
        private set => SetProperty(ref _syncText, value);
    }
    public bool HasConversation => SelectedConversation is not null;
    private bool HasComposer => HasConversation && _draftState is not null && !IsSelecting;
    public bool CanCompose => HasComposer && !HasMessageEdit;
    public bool IsSelecting=>_selectedMessages.Count>0;
    public bool CanUseSelection=>IsSelecting && !_savingSelection;
    public bool CanKeepSelection => CanUseSelection &&
        !Messages.Any(item => item.HasExpiration && _selectedMessages.Contains(item.MessageId));
    public bool CanChangeSelection=>!_savingSelection;
    public string SelectionText => AppStrings.Format("UiMessageSelectionCount", _selectedMessages.Count);
    public long SelectionVersion=>_selectionVersion;
    public IReadOnlyList<string> SelectedMessageIds=>_selectedMessages.ToArray();
    public bool IsViewingLatest { get => _isViewingLatest && !HasNewerMessages; set { if (SetProperty(ref _isViewingLatest, value)) OnPropertyChanged(nameof(ShowLatestButton)); } }
    public bool IsChatContentVisible { get; set; } = true;
    public bool ShowLatestButton => HasConversation && !IsViewingLatest && !IsSelecting;
    private string? _replyQuote;
    private string? _replyQuoteText;
    internal string? DraftQuotePreview => _replyQuoteText;
    public MessageItem? ReplyTarget
    {
        get => _replyTarget;
        private set => UpdateReplyTarget(value, null, null);
    }

    private void UpdateReplyTarget(MessageItem? target, string? quote, string? excerpt)
    {
        var excerptChanged = _replyQuoteText != excerpt;
        _replyQuote = quote;
        _replyQuoteText = excerpt;
        var targetChanged = SetProperty(ref _replyTarget, target, nameof(ReplyTarget));
        if (targetChanged)
        {
            OnPropertyChanged(nameof(HasReplyTarget));
            OnPropertyChanged(nameof(ReplyTitle));
        }
        if (targetChanged || excerptChanged) OnPropertyChanged(nameof(ReplyPreview));
        PersistDraft();
    }
    public bool HasReplyTarget => ReplyTarget is not null;
    public string ReplyPreview => _replyQuoteText ?? ((ReplyTarget is { } original
        ? Messages.FirstOrDefault(message => message.MessageId == original.MessageId) ?? original
        : null) switch
    {
        { Extension: "txt" or "location" or "live_location" } target => target.Content,
        { Extension: "contact" } => AppStrings.Get("UiContactCard"),
        { AttachmentInfo: { } attachment } => string.IsNullOrWhiteSpace(attachment.Caption) ? attachment.Filename : attachment.Caption,
        { } => AppStrings.Get("UiQuoteAttachment"),
        _ => ""
    });
    public string ReplyTitle => ReplyTarget is { } target
        ? AppStrings.Format("UiReplyingTo", target.IsMine ? AppStrings.Get("UiQuotedYou") : SelectedConversation?.Alias ?? target.Source.ConversationUuid)
        : "";
    public bool HasOlderMessages { get => _hasOlderMessages; private set => SetProperty(ref _hasOlderMessages, value); }
    public bool HasNewerMessages { get => _hasNewerMessages; private set {if(SetProperty(ref _hasNewerMessages,value)){OnPropertyChanged(nameof(IsViewingLatest));OnPropertyChanged(nameof(ShowLatestButton));}} }
    public SecureSettings Settings => _services.Settings.Snapshot;
    public string MyUuid => _services.Settings.MyUuid;
    public AppServices Services => _services;

    private MsgAT.Windows.Core.Network.SendOptions _sendOptions=new();
    private long _sendOptionsRevision;
    public MsgAT.Windows.Core.Network.SendOptions SendOptions=>_sendOptions;
    public bool HasSendOptions=>_sendOptions.Silent || _sendOptions.SendAt is not null;
    public string SendOptionsSummary => SendOptionsText.Format(_sendOptions);
    internal void RefreshSendOptionsLanguage()
    {
        OnPropertyChanged(nameof(SendOptionsSummary));
        NotifyPreparations();
    }
    public void SetSendOptions(MsgAT.Windows.Core.Network.SendOptions value){_sendOptions=value;_sendOptionsRevision++;OnPropertyChanged(nameof(SendOptions));OnPropertyChanged(nameof(SendOptionsSummary));OnPropertyChanged(nameof(HasSendOptions));}
    private (MsgAT.Windows.Core.Network.SendOptions Options,long Revision) CaptureSendOptions()=> (_sendOptions,_sendOptionsRevision);
    private static void ValidateSendOptions(MsgAT.Windows.Core.Network.SendOptions value){if(value.SendAt is { } due && due<=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds())throw new InvalidOperationException("Choose a future scheduled time.");}
    private void CompleteSendOptions(long revision,string peer){if(SelectedConversation?.Uuid==peer && _sendOptionsRevision==revision)SetSendOptions(new());}

    public async Task<bool> SendDraftAsync(SendOptions? optionsOverride = null)
    {
        if (!CanCompose || TextSendPending || SelectedConversation is null ||
            _draftState is not { } state || string.IsNullOrWhiteSpace(Draft)) return false;
        var text = Draft;
        var peerUuid = SelectedConversation.Uuid;
        var reply = ReplyTarget;
        var key = (peerUuid, state.Token, state.Revision);
        if (!_sendingDrafts.Add(key)) return false;
        _pendingTextSends.Add(peerUuid);
        OnPropertyChanged(nameof(TextSendPending));
        Preparing? preparation = null;
        try
        {
            var selection = CaptureSendOptions();
            var options = optionsOverride ?? selection.Options;
            ValidateSendOptions(options);
            var selectedPreview = WebPreview is { } preview && preview.Url == PreviewTarget
                ? preview with { AboveText = PreviewAboveText, CompactImage = PreviewCompactImage } : null;
            if (selectedPreview?.Image is not null)
                preparation = BeginPreparation(peerUuid, AppStrings.Get("UiLinkPreviewCover"));
            var commit = _services.Drafts.PrepareSend(state);
            if(!await _services.Messaging.SendTextAsync(
                peerUuid,
                text,
                reply?.MessageId,
                reply is null ? null : reply.IsMine ? MyUuid : peerUuid,
                reply?.QuoteContent,
                draft:commit,preview:selectedPreview,
                options:options,preparation:preparation?.Owner))throw new InvalidOperationException("Message could not be queued.");
            CompleteSendOptions(selection.Revision,peerUuid);
            _services.Drafts.ObserveCommittedSend(commit);
            if (commit.UpdatesDraft && !_disposed && SelectedConversation?.Uuid == peerUuid &&
                _draftState?.Token == state.Token && _draftState.Revision == state.Revision &&
                Draft == text && ReplyTarget?.MessageId == reply?.MessageId && _replyQuote == state.ReplyQuote)
            {
                _restoringDraft = true;
                Draft = "";
                ReplyTarget = null;
                _draftState = state with { Text = "", ReplyId = null, ReplyQuote = null, Revision = commit.ClearRevision };
                _restoringDraft = false;
            }
            return true;
        }
        catch (Exception exception) when (exception is InvalidOperationException or IOException or ArgumentException
            or System.Security.Cryptography.CryptographicException or System.Text.Json.JsonException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_disposed && preparation?.Owner.WasCancelled != true)
                ErrorRequested?.Invoke(this, AppStrings.Get("UiSendFailed"));
        }
        catch (OperationCanceledException) when (preparation?.Owner.WasCancelled == true || _disposed || _services.IsDisposed) { }
        finally
        {
            EndPreparation(preparation);
            _sendingDrafts.Remove(key);
            _pendingTextSends.Remove(peerUuid);
            if (!_disposed) OnPropertyChanged(nameof(TextSendPending));
        }
        return false;
    }

    private async Task LoadDraftAsync(string peer, long generation)
    {
        try
        {
            var value = await _services.Drafts.OpenAsync(peer);
            var reply = value.ReplyId is null ? null : await Task.Run(() => _services.Database.GetMessage(value.ReplyId));
            if (_disposed || generation != _draftGeneration || SelectedConversation?.Uuid != peer) return;
            _restoringDraft = true;
            _draftState = value;
            Draft = value.Text;
            var target = reply is { IsRevoked: false } && reply.ConversationUuid == peer ? CreateMessageItem(reply) : null;
            var quote = value.ReplyQuote;
            string? excerpt = null;
            if (quote is not null)
            {
                if (target is not null && _services.Database.CanQuoteText(target.Source)) excerpt = ResolveReplyQuote(target.Content, quote);
                if (excerpt is null) { target = null; quote = null; }
            }
            UpdateReplyTarget(target, quote, excerpt);
            _restoringDraft = false;
            PersistDraft(); // Remove an unavailable/revoked reply without losing its text.
            OnPropertyChanged(nameof(CanCompose));
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_disposed && generation == _draftGeneration)
            {
                _restoringDraft = false;
                _draftState = null;
                OnPropertyChanged(nameof(CanCompose));
                ErrorRequested?.Invoke(this, "The saved draft could not be opened.");
            }
        }
    }

    private void PersistDraft()
    {
        if (_disposed || _restoringDraft || _draftState is not { } state) return;
        try { _draftState = _services.Drafts.Update(state, Draft, ReplyTarget?.MessageId, _replyQuote); }
        catch (Exception error) when (error is InvalidOperationException or OverflowException)
        { ErrorRequested?.Invoke(this, "The draft could not be saved."); }
    }

    private void DraftSaveFailed(object? sender, EventArgs args)
    {
        _dispatcher.BeginInvoke(() => { if (!_disposed) ErrorRequested?.Invoke(this, "The draft could not be saved. It will be retried."); });
    }

    public Task SendMediaAsync(string path) => CaptureMediaSender()?.Invoke(path) ?? Task.CompletedTask;

    internal static bool CanSelectMessage(MessageItem item) =>
        !item.IsRevoked && item.Extension is ("txt" or "attachment" or "contact" or "location");

    public void ToggleSelection(MessageItem item, bool extending = false)
    {
        if (_savingSelection || !CanSelectMessage(item)) return;

        if (extending && _selectionAnchor is { } anchor && _selectedMessages.Contains(anchor) &&
            Messages.FirstOrDefault(message => message.MessageId == anchor) is { } anchorItem)
        {
            var start = Messages.IndexOf(anchorItem);
            var end = Messages.IndexOf(item);
            if (end >= 0)
            {
                var selection = new HashSet<string>(_selectedMessages, StringComparer.Ordinal);
                for (var index = Math.Min(start, end); index <= Math.Max(start, end); index++)
                {
                    var message = Messages[index];
                    if (!CanSelectMessage(message)) continue;
                    selection.Add(message.MessageId);
                    if (selection.Count <= 100) continue;
                    ErrorRequested?.Invoke(this, AppStrings.Get("UiMessageSelectionLimit"));
                    return;
                }
                if (selection.Count != _selectedMessages.Count)
                {
                    _selectedMessages.UnionWith(selection);
                    SelectionChanged();
                }
                return;
            }
        }

        if (!_selectedMessages.Contains(item.MessageId) && _selectedMessages.Count >= 100)
        {
            ErrorRequested?.Invoke(this, AppStrings.Get("UiMessageSelectionLimit"));
            return;
        }
        if(!_selectedMessages.Remove(item.MessageId))_selectedMessages.Add(item.MessageId);
        _selectionAnchor = _selectedMessages.Contains(item.MessageId) ? item.MessageId : null;
        SelectionChanged();
    }
    public void ClearSelection()
    {
        _selectionAnchor = null;
        _selectedMessages.Clear();
        SelectionChanged();
    }
    private void RefreshAlbumPresentation()
    {
        var presentations = new Dictionary<MessageItem, (MessageItem Anchor, IReadOnlyList<MessageItem> Members)>();
        var groups = Messages.Where(item => !item.IsRevoked && item.AttachmentInfo?.Album is not null)
            .GroupBy(item => (item.IsMine, item.AttachmentInfo!.Album!.Id));
        foreach (var group in groups)
        {
            var members = group.OrderBy(item => item.AttachmentInfo!.Album!.Index).ToArray();
            if (members.Length < 2) continue;
            var anchor = group.First();
            foreach (var member in members) presentations.Add(member, (anchor, members));
        }
        DateTime? previousDay = null;
        MessageItem? previous = null;
        var previousJoinsOlder = false;
        foreach (var item in Messages)
        {
            if (presentations.TryGetValue(item, out var presentation))
                item.SetAlbumPresentation(presentation.Anchor, ReferenceEquals(item, presentation.Anchor) ? presentation.Members : []);
            else
                item.SetAlbumPresentation(null, []);

            if (item.HiddenByAlbum)
            {
                item.SetTimelineDay(null);
                item.StartsUnread = false;
                item.SetTimelineCluster(false, false);
                continue;
            }
            var day = item.LocalTimestamp?.Date;
            item.SetTimelineDay(day == previousDay ? null : day);
            item.StartsUnread = _unreadTimelineAnchor is { } unread &&
                (item.MessageId == unread || item.AlbumMembers.Any(member => member.MessageId == unread));
            var joinsOlder = previous is not null && day.HasValue && day == previousDay &&
                !item.StartsUnread && CanJoinTimelineMessages(previous, item);
            previous?.SetTimelineCluster(previousJoinsOlder, joinsOlder);
            previous = item;
            previousJoinsOlder = joinsOlder;
            previousDay = day;
        }
        previous?.SetTimelineCluster(previousJoinsOlder, false);
    }

    private static bool CanJoinTimelineMessages(MessageItem older, MessageItem newer)
    {
        static bool IsChatContent(MessageItem item) =>
            !item.IsRevoked && !item.HasForward && item.AttachmentInfo?.Album is null &&
            item.Source.Extension is ("txt" or "attachment" or "contact" or "location" or "live_location");

        return older.IsMine == newer.IsMine && IsChatContent(older) && IsChatContent(newer) &&
            older.Source.Timestamp >= 0 && newer.Source.Timestamp >= older.Source.Timestamp &&
            newer.Source.Timestamp - older.Source.Timestamp <= 5 * 60 * 1000;
    }

    private void SelectionChanged()
    {
        ++_selectionVersion;
        if (IsSelecting) CancelMessageEdit();
        foreach(var row in Messages)row.IsSelected=_selectedMessages.Contains(row.MessageId);
        OnPropertyChanged(nameof(IsSelecting));OnPropertyChanged(nameof(SelectionText));OnPropertyChanged(nameof(CanUseSelection));OnPropertyChanged(nameof(CanCompose));
        OnPropertyChanged(nameof(CanKeepSelection));
        OnPropertyChanged(nameof(ShowLatestButton));
    }
    public void ClearSelectionIfCurrent(long version){if(version==_selectionVersion)ClearSelection();}
    internal void RemoveSelectedMessages(IReadOnlySet<string> ids)
    {
        var selected = _selectedMessages.Count;
        _selectedMessages.ExceptWith(ids);
        if (_selectedMessages.Count != selected) SelectionChanged();
        if (ReplyTarget is { } reply && ids.Contains(reply.MessageId)) CancelReply();
        if (_editingMessage is { } edit && ids.Contains(edit.MessageId)) CancelMessageEdit();
    }

    private void SetSelectionBusy(bool value)
    {
        _savingSelection = value;
        OnPropertyChanged(nameof(CanUseSelection));
        OnPropertyChanged(nameof(CanKeepSelection));
        OnPropertyChanged(nameof(CanChangeSelection));
    }

    internal async Task<bool> DeleteMessagesAsync(string peer, IReadOnlyList<string> ids, bool revokeForBoth)
    {
        if (_savingSelection || SelectedConversation?.Uuid != peer) return false;
        SetSelectionBusy(true);
        try
        {
            var removed = revokeForBoth
                ? await _services.Messaging.RevokeSentMessagesAsync(peer, ids)
                : await _services.Messaging.DeleteLocalMessagesAsync(peer, ids);
            if (removed && !_disposed && SelectedConversation?.Uuid == peer)
                RemoveSelectedMessages(ids.ToHashSet(StringComparer.Ordinal));
            return removed;
        }
        finally { SetSelectionBusy(false); }
    }

    public async Task<bool> SaveMessagesAsync(IReadOnlyList<string> ids)
    {
        if(_savingSelection || SelectedConversation is null)return false;
        var peer=SelectedConversation.Uuid;var snapshot=ids.ToArray();var version=_selectionVersion;
        var clearSelection=_selectedMessages.SetEquals(snapshot);
        SetSelectionBusy(true);
        Preparing? preparation = null;
        try
        {
            preparation = BeginPreparation(peer, savingMessages: true);
            await _services.Messaging.Actions.SaveAsync(peer, snapshot, preparation: preparation.Owner);
            if(!_disposed && clearSelection && SelectedConversation?.Uuid==peer)ClearSelectionIfCurrent(version);
            return true;
        }
        catch(OperationCanceledException) when(_disposed || _services.IsDisposed || preparation?.Owner.WasCancelled == true){return false;}
        catch(Exception error) when(error is InvalidOperationException or IOException or ArgumentException or Microsoft.Data.Sqlite.SqliteException or System.Security.Cryptography.CryptographicException)
        {
            if (!_disposed && preparation?.Owner.WasCancelled != true)
                ErrorRequested?.Invoke(this, AppStrings.Get("UiSharedMediaSaveFailed"));
            return false;
        }
        finally { EndPreparation(preparation); SetSelectionBusy(false); }
    }

    public Func<StickerItem,CancellationToken,Task<PreparationSendResult>>? CaptureStickerSender()
    {
        if(SelectedConversation is null || !CanCompose)return null;
        var peer=SelectedConversation.Uuid;var reply=ReplyTarget;var state=_draftState!;var selection=CaptureSendOptions();
        return async (item,token)=>{
            var key=(peer,state.Token,state.Revision);if(!_sendingDrafts.Add(key))return PreparationSendResult.Failed;
            Preparing? preparation=null;
            try
            {
                ValidateSendOptions(selection.Options);preparation=BeginPreparation(peer,isSticker:true);var commit=_services.Drafts.PrepareSend(state,clearsText:false);
                if(!await _services.Messaging.SendStickerAsync(peer,item.Id,reply?.MessageId,commit,token,selection.Options,preparation.Owner))return preparation.Owner.WasCancelled?PreparationSendResult.Cancelled:PreparationSendResult.Failed;
                CompleteSendOptions(selection.Revision,peer);CompleteAttachmentDraft(commit);return PreparationSendResult.Queued;
            }
            catch(OperationCanceledException) when(preparation?.Owner.WasCancelled==true || token.IsCancellationRequested){return PreparationSendResult.Cancelled;}
            finally {EndPreparation(preparation);_sendingDrafts.Remove(key);}
        };
    }

    public Func<string,Task>? CaptureMediaSender()
    {
        var options=_sendOptions;var send=CaptureConfirmedMediaSender();return send is null?null:path=>send(path,new MediaSendChoice("",options));
    }

    public Func<string, string, SendOptions, Task<bool>>? CaptureContactCardSender()
    {
        var send = CaptureStructuredSender<(string Account, string Name)>((peer, card, reply, draft, options) =>
            _services.Messaging.SendContactCardAsync(peer, card.Account, card.Name, reply, draft, options));
        return send is null ? null : (account, name, options) => send((account, name), options);
    }

    public Func<LocationPoint, string?, SendOptions, Task<bool>>? CaptureLocationSender()
    {
        var send = CaptureStructuredSender<(LocationPoint Point, string? Label)>((peer, location, reply, draft, options) =>
            _services.Messaging.SendLocationAsync(peer, location.Point, location.Label, reply, draft, options));
        return send is null ? null : (point, label, options) => send((point, label), options);
    }

    public Func<LocationPoint, TimeSpan, string?, bool, Task<bool>>? CaptureLiveLocationSender()
    {
        var send = CaptureStructuredSender<(LocationPoint Point, TimeSpan Duration, string? Label)>((peer, location, reply, draft, options) =>
            _services.LiveLocations.StartAsync(peer, location.Point, location.Duration, location.Label, reply, draft, options.Silent),
            canQueue: _services.LiveLocations.CanStart);
        return send is null ? null : (point, duration, label, silent) => send((point, duration, label), new SendOptions(Silent: silent));
    }

    private Func<T, SendOptions, Task<bool>>? CaptureStructuredSender<T>(
        Func<string, T, string?, DraftSendCommit, SendOptions, Task<bool>> queue, Func<string, bool>? canQueue = null)
    {
        if (SelectedConversation is not { } conversation || !CanCompose || _draftState is not { } draft) return null;
        var peer = conversation.Uuid;
        var reply = ReplyTarget;
        var selection = CaptureSendOptions();
        return async (content, options) =>
        {
            var key = (peer, draft.Token, draft.Revision);
            if (!_sendingDrafts.Add(key)) return false;
            try
            {
                ValidateSendOptions(options);
                if (canQueue is not null && !canQueue(peer)) return false;
                var commit = _services.Drafts.PrepareSend(draft, clearsText: false);
                if (!await queue(peer, content, reply?.MessageId, commit, options))
                    return false;
                CompleteSendOptions(selection.Revision, peer);
                CompleteAttachmentDraft(commit);
                return true;
            }
            catch (Exception error) when (error is IOException or InvalidOperationException or ArgumentException or
                OperationCanceledException or System.Security.Cryptography.CryptographicException or System.Text.Json.JsonException or Microsoft.Data.Sqlite.SqliteException)
            {
                return false;
            }
            finally { _sendingDrafts.Remove(key); }
        };
    }
    public Func<string, MediaSendChoice, Task>? CaptureConfirmedMediaSender()
    {
        var send = CaptureConfirmedMediaBatchSender();
        return send is null ? null : async (path, choice) =>
        {
            if (await send([path], choice, null) == PreparationSendResult.Failed)
                ErrorRequested?.Invoke(this, AppStrings.Get("UiAttachmentSendFailed"));
        };
    }

    public Func<IReadOnlyList<string>, MediaSendChoice, Action<string>?, Task<PreparationSendResult>>? CaptureConfirmedMediaBatchSender()
    {
        if (SelectedConversation is null || !CanCompose) return null;
        var peer = SelectedConversation.Uuid;
        var reply = ReplyTarget;
        var draft = _draftState!;
        var selection = CaptureSendOptions();
        return (paths, choice, started) => SendMediaToAsync(paths, peer, reply, draft,
            (choice.Options, selection.Revision), choice, started);
    }

    public Func<ReadOnlyMemory<byte>,Task>? CaptureImageSender()
    {
        var options=_sendOptions;var send=CaptureConfirmedImageSender();return send is null?null:bytes=>send(bytes,new MediaSendChoice("",options));
    }
    public Func<ReadOnlyMemory<byte>,MediaSendChoice,Task>? CaptureConfirmedImageSender(string filename="Clipboard.png",string mime="image/png")
    {
        if(SelectedConversation is null || !CanCompose) return null;
        var peer=SelectedConversation.Uuid; var reply=ReplyTarget; var state=_draftState!;var selection=CaptureSendOptions();
        return async (bytes,choice)=>
        {
            var key = (peer, state.Token, state.Revision);
            if (!_sendingDrafts.Add(key)) { ErrorRequested?.Invoke(this,"Wait until the current send has been queued."); return; }
            Preparing? preparation=null;
            try
            {
                ValidateSendOptions(choice.Options);
                preparation=BeginPreparation(peer,filename);
                var commit = _services.Drafts.PrepareSend(state, clearsText: false);
                if(!await _services.Messaging.SendMediaBytesAsync(peer,bytes,filename,mime,reply?.MessageId,draft:commit,options:choice.Options,preparation:preparation.Owner,caption:choice.Caption,asFile:choice.AsFile,
                    compressPhoto:choice.CompressPhotos ? PhotoCompression.CompressBytesAsync : null))throw new InvalidOperationException("Image could not be queued.");
                CompleteSendOptions(selection.Revision,peer);
                CompleteAttachmentDraft(commit);
            }
            catch(Exception error) when(error is IOException or InvalidOperationException or ArgumentException or System.Security.Cryptography.CryptographicException or System.Text.Json.JsonException or Microsoft.Data.Sqlite.SqliteException)
            { if(preparation?.Owner.WasCancelled!=true)ErrorRequested?.Invoke(this,"Send content failed"); }
            catch (OperationCanceledException) when (preparation?.Owner.WasCancelled==true || _disposed || _services.IsDisposed) { }
            finally { EndPreparation(preparation);_sendingDrafts.Remove(key); }
        };
    }

    public Func<EncryptedCaptureFile, MediaSendChoice, Action<string>, Task<PreparationSendResult>>? CaptureConfirmedRecordingSender()
    {
        if(SelectedConversation is null || !CanCompose)return null;
        var peer=SelectedConversation.Uuid;var reply=ReplyTarget;var state=_draftState!;var selection=CaptureSendOptions();
        return async(source,choice,started)=>
        {
            var key=(peer,state.Token,state.Revision);
            if (!_sendingDrafts.Add(key)) return PreparationSendResult.Failed;
            Preparing? preparation=null;
            try
            {
                ValidateSendOptions(choice.Options);
                preparation = BeginPreparation(peer, source.Filename);
                started(preparation.Id);
                var commit=_services.Drafts.PrepareSend(state,clearsText:false);
                if(!await _services.Messaging.SendRecordingAsync(peer,source,reply?.MessageId,draft:commit,options:choice.Options,
                    preparation:preparation.Owner,caption:choice.Caption,asFile:choice.AsFile))throw new InvalidOperationException("Recording could not be queued.");
                CompleteSendOptions(selection.Revision,peer);CompleteAttachmentDraft(commit);
                return PreparationSendResult.Queued;
            }
            catch(Exception error) when(error is IOException or InvalidOperationException or ArgumentException or System.Security.Cryptography.CryptographicException or System.Text.Json.JsonException or Microsoft.Data.Sqlite.SqliteException)
            { return preparation?.Owner.WasCancelled == true ? PreparationSendResult.Cancelled : PreparationSendResult.Failed; }
            catch(OperationCanceledException) when(preparation?.Owner.WasCancelled==true || _disposed || _services.IsDisposed)
            { return PreparationSendResult.Cancelled; }
            finally{EndPreparation(preparation);_sendingDrafts.Remove(key);}
        };
    }

    private async Task<PreparationSendResult> SendMediaToAsync(IReadOnlyList<string> paths,string peerUuid,MessageItem? reply,ConversationDraft state,(MsgAT.Windows.Core.Network.SendOptions Options,long Revision) selection,MediaSendChoice choice, Action<string>? started)
    {
        var key = (peerUuid, state.Token, state.Revision);
        if (!_sendingDrafts.Add(key)) return PreparationSendResult.Failed;
        Preparing? preparation=null;
        try
        {
            ValidateSendOptions(selection.Options);
            preparation = BeginPreparation(peerUuid, paths.Count == 1 ? Path.GetFileName(paths[0]) : null, paths.Count);
            started?.Invoke(preparation.Id);
            var commit = _services.Drafts.PrepareSend(state, clearsText: false);
            if(!await _services.Messaging.SendMediaBatchAsync(peerUuid, paths, reply?.MessageId,
                draft:commit,options:selection.Options,preparation:preparation.Owner,caption:choice.Caption,asFile:choice.AsFile,asAlbum:choice.AsAlbum,
                compressPhoto:choice.CompressPhotos ? PhotoCompression.CompressFileAsync : null,
                videoEncoder:MsgAT.Windows.Media.VideoCompression.EncodeFileAsync,
                compressVideos:choice.CompressVideos,videoTrims:choice.VideoTrims,editedPhotos:choice.EditedPhotos,
                compressEditedPhoto:choice.CompressPhotos ? PhotoCompression.CompressBytesAsync : null,
                itemCaptions:choice.ItemCaptions))throw new InvalidOperationException("File could not be queued.");
            CompleteSendOptions(selection.Revision,peerUuid);
            CompleteAttachmentDraft(commit);
            return PreparationSendResult.Queued;
        }
        catch (Exception exception) when (exception is InvalidOperationException or IOException or ArgumentException or System.Security.Cryptography.CryptographicException or System.Text.Json.JsonException or Microsoft.Data.Sqlite.SqliteException)
        {
            return preparation?.Owner.WasCancelled == true ? PreparationSendResult.Cancelled : PreparationSendResult.Failed;
        }
        catch (OperationCanceledException) when (preparation?.Owner.WasCancelled==true || _disposed || _services.IsDisposed)
        {
            return PreparationSendResult.Cancelled;
        }
        finally { EndPreparation(preparation);_sendingDrafts.Remove(key); }
    }

    private void CompleteAttachmentDraft(DraftSendCommit commit)
    {
        var state = commit.Draft;
        if (!commit.UpdatesDraft || _disposed || SelectedConversation?.Uuid != state.Peer ||
            _draftState?.Token != state.Token || _draftState.Revision != state.Revision) return;
        _restoringDraft = true;
        ReplyTarget = null;
        _draftState = state with { ReplyId = null, ReplyQuote = null, Revision = commit.ClearRevision };
        _restoringDraft = false;
    }

    public async Task AddFriendAsync(string uuid)
    {
        try { await _services.Messaging.AddFriendAsync(uuid); }
        catch (Exception exception) when (exception is InvalidOperationException or ArgumentException)
        {
            ErrorRequested?.Invoke(this, exception.Message);
        }
    }

    public Task AcceptFriendRequestAsync(FriendRequest request) =>
        _services.Messaging.AcceptFriendRequestAsync(request.SenderUuid);

    public Task DeclineFriendRequestAsync(FriendRequest request) =>
        _services.Messaging.DeclineFriendRequestAsync(request.SenderUuid);

    public async Task DeleteSelectedFriendAsync()
    {
        if (SelectedConversation is not null) { await DeleteFriendAsync(SelectedConversation.Uuid); }
    }

    public async Task DeleteFriendAsync(string uuid)
    {
        try
        {
            await _services.Messaging.DeleteFriendAsync(uuid);
            if (SelectedConversation?.Uuid == uuid) SelectedConversation = null;
        }
        catch (Exception error) when (error is InvalidOperationException or System.IO.IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            ErrorRequested?.Invoke(this, AppStrings.Get("UiContactActionFailed"));
        }
    }

    public async Task ClearMessagesAsync(string uuid)
    {
        try
        {
            await _services.Messaging.ClearMessagesAsync(uuid);
            if(SelectedConversation?.Uuid==uuid) ReplyTarget=null;
        }
        catch (Exception error) when (error is InvalidOperationException or System.IO.IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            ErrorRequested?.Invoke(this, AppStrings.Get("UiClearMessagesFailed"));
        }
    }

    public async Task RevokeAsync(MessageItem item)
    {
        var peer = item.Source.ConversationUuid;
        if (_disposed || _services.IsDisposed || SelectedConversation?.Uuid != peer || !item.CanModify) return;
        try { await _services.Messaging.RevokeMessageAsync(peer, item.MessageId); }
        catch (OperationCanceledException) when (_disposed || _services.IsDisposed) { }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_disposed) ErrorRequested?.Invoke(this, AppStrings.Get("UiRevokeFailed"));
        }
    }

    public void SetReply(MessageItem item) { if (CanCompose && !item.IsRevoked && item.Source.ConversationUuid == SelectedConversation?.Uuid) { ReplyTarget = item; } }
    internal void SetReplyQuote(MessageItem item, string selection, string excerpt)
    {
        if (CanCompose && item.IsText && !item.IsRevoked && item.Source.ConversationUuid == SelectedConversation?.Uuid)
            UpdateReplyTarget(item, selection, excerpt);
    }

    private static string? ResolveReplyQuote(string text, string selection)
    {
        try
        {
            return MsgAT.Windows.Core.Crypto.SecurityCore.Call(new { operation = "reply_quote_selection", text, selection })
                .GetProperty("preview").GetString();
        }
        catch (Exception error) when (error is System.Security.Cryptography.CryptographicException or
            InvalidOperationException or IOException or System.Text.Json.JsonException or KeyNotFoundException)
        {
            return null;
        }
    }
    public void CancelReply() => ReplyTarget = null;

    internal MessageItem? LatestEditableMessage()
    {
        if (!CanCompose || SelectedConversation is not { } peer) return null;
        var message = _services.Database.GetLatestEditableMessage(peer.Uuid);
        return message is null ? null : CreateMessageItem(message);
    }

    internal bool MoveReply(bool newer, out MessageItem? target)
    {
        target = null;
        if (!CanCompose) return false;
        var current = ReplyTarget;
        if (newer && current is null) return false;
        var anchor = current ?? Messages.LastOrDefault();
        if (anchor is null) return false;

        var message = _services.Database.GetAdjacentReplyMessage(anchor.Source, newer, includingAnchor: current is null);
        if (message is null)
        {
            if (!newer) return false;
            CancelReply();
            return true;
        }
        if (RevealMessage(message.MessageId) is not { IsRevoked: false } revealed) return false;
        SetReply(revealed);
        target = revealed;
        return true;
    }

    private MessageItem? _editingMessage;
    private string _editText = "";
    private bool _savingEdit;

    public bool HasMessageEdit => _editingMessage is not null;
    internal bool EditingAttachment => _editingMessage?.IsAttachment == true;
    public bool CanEditText => HasMessageEdit && HasComposer && !_savingEdit;
    public bool IsSavingEdit => _savingEdit;
    public bool CanCancelEdit => HasMessageEdit && !_savingEdit;
    internal bool HasUnsavedEdit => _editingMessage is { } target && EditText != target.EditableText;
    public bool CanSubmitEdit => CanEditText && _editingMessage is { } target &&
        (target.IsAttachment || !string.IsNullOrWhiteSpace(EditText)) && EditText != target.EditableText;
    public string EditingPreview => _editingMessage is { } target ?
        (target.IsAttachment && string.IsNullOrEmpty(target.EditableText) ? target.Filename : target.EditableText) : "";
    public string EditingTitle => AppStrings.Get(_editingMessage?.IsAttachment == true ? "UiEditingCaption" : "UiEditingMessage");
    public string? EditTargetId => _editingMessage?.MessageId;
    public string EditText
    {
        get => _editText;
        set { if (SetProperty(ref _editText, value)) OnPropertyChanged(nameof(CanSubmitEdit)); }
    }

    private void NotifyMessageEdit()
    {
        OnPropertyChanged(nameof(HasMessageEdit));
        OnPropertyChanged(nameof(CanCompose));
        OnPropertyChanged(nameof(CanEditText));
        OnPropertyChanged(nameof(IsSavingEdit));
        OnPropertyChanged(nameof(CanCancelEdit));
        OnPropertyChanged(nameof(CanSubmitEdit));
        OnPropertyChanged(nameof(EditingPreview));
        OnPropertyChanged(nameof(EditingTitle));
    }

    public void BeginMessageEdit(MessageItem item)
    {
        if (!HasComposer || _savingEdit || !item.CanEditContent ||
            SelectedConversation?.Uuid != item.Source.ConversationUuid) return;
        ShowAttachments = false;
        _editingMessage = item;
        EditText = item.EditableText;
        NotifyMessageEdit();
    }

    public void CancelMessageEdit()
    {
        if (_editingMessage is null) return;
        _editingMessage = null;
        EditText = "";
        NotifyMessageEdit();
    }

    public async Task SubmitMessageEditAsync()
    {
        if (!CanSubmitEdit || _editingMessage is not { } item) return;
        var text = EditText;
        _savingEdit = true;
        NotifyMessageEdit();
        var saved = false;
        try
        {
            await _services.Messaging.EditMessageAsync(item.Source.ConversationUuid, item.MessageId, text);
            saved = true;
        }
        catch (OperationCanceledException) when (_disposed || _services.IsDisposed) { }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException or ArgumentException)
        {
            if (_disposed) return;
        }
        finally
        {
            _savingEdit = false;
            NotifyMessageEdit();
        }
        if (_disposed || !ReferenceEquals(_editingMessage, item)) return;
        if (saved) CancelMessageEdit();
        else ErrorRequested?.Invoke(this, AppStrings.Get("UiEditFailed"));
    }

    internal Task<bool> LoadHistoryPageAsync(HistoryPage direction, Action retry,
        Action? willApply = null, Action? loaded = null)
    {
        var latest = direction == HistoryPage.Latest;
        var older = direction == HistoryPage.Older;
        if (_disposed || !IsChatContentVisible || HistoryPageLoading || InitialHistoryLoading ||
            SelectedConversation is not { } conversation || (!latest && !(older ? HasOlderMessages : HasNewerMessages)))
            return Task.FromResult(false);
        var anchor = (older ? Messages.FirstOrDefault() : Messages.LastOrDefault())?.Source;
        if (!latest && anchor is null) return Task.FromResult(false);
        var peer = conversation.Uuid;
        var navigation = HistoryNavigationVersion;
        var alias = conversation.Alias;
        var quotedMessage = AppStrings.Get("UiQuotedMessage");
        var quotedYou = AppStrings.Get("UiQuotedYou");
        var owner = new CancellationTokenSource();
        _historyPageCancellation = owner;
        OnPropertyChanged(nameof(HistoryPageLoading));
        _historyPageLoad = LoadAsync();
        return _historyPageLoad;

        async Task<bool> LoadAsync()
        {
            var token = owner.Token;
            bool IsCurrent() => !token.IsCancellationRequested && !_disposed && IsChatContentVisible &&
                HistoryNavigationVersion == navigation && SelectedConversation?.Uuid == peer &&
                (latest || (older ? Messages.FirstOrDefault() : Messages.LastOrDefault())?.MessageId == anchor?.MessageId);
            try
            {
                while (IsCurrent())
                {
                    var revision = Volatile.Read(ref _changeRevision);
                    var page = await Task.Run(() =>
                    {
                        var rows = direction switch
                        {
                            HistoryPage.Older => _services.Database.GetMessagesBefore(anchor!, PageSize + 1),
                            HistoryPage.Newer => _services.Database.GetMessagesAfter(anchor!, PageSize + 1),
                            _ => _services.Database.GetMessages(peer, PageSize + 1)
                        };
                        var shown = (direction == HistoryPage.Newer ? rows.Take(PageSize) : rows.TakeLast(PageSize)).ToArray();
                        var metadata = _services.Database.GetHistoryMetadata(shown);
                        var items = new List<MessageItem>(shown.Length);
                        foreach (var row in shown)
                        {
                            token.ThrowIfCancellationRequested();
                            items.Add(ReadMessageItem(row, metadata.GetValueOrDefault(row.MessageId),
                                alias, quotedYou, quotedMessage));
                        }
                        return (Items: items, HasMore: rows.Count > PageSize);
                    }, token);
                    if (!IsCurrent()) return false;
                    if (revision != Volatile.Read(ref _changeRevision)) continue;
                    HistoryLoadFailed = false;
                    _retryHistoryLoad = null;
                    if (latest)
                    {
                        ++HistoryNavigationVersion;
                        InitialScrollTarget = null;
                        InitialHistoryPosition = null;
                        Messages.Clear();
                        HasNewerMessages = false;
                        IsViewingLatest = true;
                    }
                    var changed = latest || page.Items.Count != 0;
                    if (changed)
                    {
                        willApply?.Invoke();
                        foreach (var item in page.Items) ApplyMessageUiState(item);
                        if (older)
                            for (var i = page.Items.Count - 1; i >= 0; i--) Messages.Insert(0, page.Items[i]);
                        else
                            foreach (var item in page.Items) Messages.Add(item);
                        RefreshAlbumPresentation();
                    }
                    if (direction == HistoryPage.Newer) HasNewerMessages = page.HasMore;
                    else HasOlderMessages = page.HasMore;
                    if (latest) InitialMessagesLoaded?.Invoke(this, EventArgs.Empty);
                    if (changed) loaded?.Invoke();
                    return changed;
                }
                return false;
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested) { return false; }
            catch (Exception error) when (error is IOException or Microsoft.Data.Sqlite.SqliteException)
            {
                if (IsCurrent())
                {
                    _retryHistoryLoad = retry;
                    HistoryLoadFailed = true;
                }
                return false;
            }
            finally
            {
                _historyPageCancellation = null;
                owner.Dispose();
                OnPropertyChanged(nameof(HistoryPageLoading));
            }
        }
    }

    public void RetryHistoryLoad() => _retryHistoryLoad?.Invoke();

    public void LoadLatestMessages(Action? loaded = null) =>
        _ = LoadHistoryPageAsync(HistoryPage.Latest, () => LoadLatestMessages(loaded), loaded: loaded);

    private string? _unreadTimelineAnchor;

    public void SetUnreadTimelineAnchor(string messageId)
    {
        if (_unreadTimelineAnchor == messageId) return;
        _unreadTimelineAnchor = messageId;
        RefreshAlbumPresentation();
    }

    public string? InitialScrollTarget {get;private set;}
    internal ChatHistoryPosition? InitialHistoryPosition { get; private set; }
    internal Func<ChatHistoryPosition?>? CaptureHistoryPosition { get; set; }
    private readonly Dictionary<string, (ChatHistoryPosition Position, long Order)> _historyPositions = new(StringComparer.Ordinal);
    private long _historyOrder;

    internal void ClearHistoryPositions() => _historyPositions.Clear();

    private void SaveHistoryPosition(string peer)
    {
        if (InitialHistoryLoading || (HistoryLoadFailed && Messages.Count == 0)) return;
        if (IsViewingLatest) { _historyPositions.Remove(peer); return; }
        if (CaptureHistoryPosition?.Invoke() is not { } position) return;
        _historyPositions[peer] = (position, ++_historyOrder);
        if (_historyPositions.Count > 64) _historyPositions.Remove(_historyPositions.MinBy(item => item.Value.Order).Key);
    }

    public long HistoryNavigationVersion {get;private set;}
    private string? _jumpHighlightedId;
    private string? _jumpQuote;

    internal void SetJumpHighlight(string? id, string? quote = null)
    {
        if (_jumpHighlightedId == id && _jumpQuote == quote) return;
        var previous = _jumpHighlightedId;
        _jumpHighlightedId = id;
        _jumpQuote = quote;
        foreach (var message in Messages)
        {
            if (message.MessageId == previous || message.MessageId == id)
            {
                message.IsJumpHighlighted = message.MessageId == id && !message.IsRevoked;
                message.JumpQuote = message.IsJumpHighlighted ? quote : null;
            }
        }
    }

    public MessageItem? RevealMessage(string messageId)
    {
        if (SelectedConversation is null) return null;
        var existing = Messages.FirstOrDefault(message => message.MessageId == messageId);
        if (existing is not null)
        {
            if (!_services.Database.HasHistoryAnchor(SelectedConversation.Uuid, messageId)) return null;
            ++HistoryNavigationVersion;
            CancelInitialHistoryLoad();
            IsViewingLatest = false;
            return existing;
        }
        var window=_services.Database.GetMessageWindow(SelectedConversation.Uuid,messageId);
        if(window is null) return null;
        ++HistoryNavigationVersion;
        CancelInitialHistoryLoad();
        IsViewingLatest=false;
        var metadata = _services.Database.GetHistoryMetadata(window.Messages);
        Messages.Clear();
        foreach(var row in window.Messages) Messages.Add(CreateMessageItem(row, metadata));
        RefreshAlbumPresentation();
        HasOlderMessages=window.HasOlder;HasNewerMessages=window.HasNewer;
        return Messages.FirstOrDefault(message=>message.MessageId==messageId);
    }

    internal MessageSearchPage SearchMessages(string query, string? peer, MessageSearchCursor? before,
        MessageSearchKind kind, DateTime? day, int limit) =>
        _services.Search.Search(query, peer, before, kind, limit, day: day);

    internal bool CanReadAttachment(MessageItem item) => !_disposed && !_services.IsDisposed &&
        _services.Database.HasHistoryAnchor(item.Source.ConversationUuid, item.MessageId, includeRevoked: false);

    internal bool CanExportAttachment(MessageItem item) => !_disposed && !_services.IsDisposed &&
        _services.Database.CanExportAttachment(item.Source);

    internal Task<bool> ChangeAttachmentDownloadAsync(MessageItem item, bool download, CancellationToken token) =>
        download ? _services.Messaging.RequestAttachmentDownloadAsync(item.Source.ConversationUuid, item.MessageId, token)
            : _services.Messaging.PauseAttachmentDownloadAsync(item.Source.ConversationUuid, item.MessageId, token);

    internal async Task<bool> RevealWorkspaceMessageAsync(string peer, string id, Func<bool> isCurrent,
        Action<ConversationItem> choose, bool includeRevoked = false)
    {
        if (!isCurrent() || Conversations.FirstOrDefault(row => row.Uuid == peer) is not { } conversation)
            return false;
        var alias = conversation.Alias;
        var quotedYou = AppStrings.Get("UiQuotedYou");
        var quotedMessage = AppStrings.Get("UiQuotedMessage");
        while (!_disposed && isCurrent())
        {
            var revision = Volatile.Read(ref _changeRevision);
            var snapshot = await Task.Run(() =>
            {
                var window = _services.Database.GetMessageWindow(peer, id);
                if (window is null || !window.Messages.Any(row => row.MessageId == id && (includeRevoked || !row.IsRevoked)))
                    return (Window: (MessageHistoryWindow?)null, Items: Array.Empty<MessageItem>());
                var metadata = _services.Database.GetHistoryMetadata(window.Messages);
                var items = window.Messages.Select(row => ReadMessageItem(row,
                    metadata.GetValueOrDefault(row.MessageId), alias, quotedYou, quotedMessage)).ToArray();
                return (Window: (MessageHistoryWindow?)window, Items: items);
            });
            if (_disposed || !isCurrent()) return false;
            if (revision != Volatile.Read(ref _changeRevision)) continue;
            if (snapshot.Window is not { } window) return false;
            if (SelectedConversation?.Uuid != peer) choose(conversation);
            if (_disposed || !isCurrent() || SelectedConversation?.Uuid != peer) return false;
            CancelInitialHistoryLoad();
            ++HistoryNavigationVersion;
            InitialScrollTarget = id;
            InitialHistoryPosition = null;
            IsViewingLatest = false;
            HistoryLoadFailed = false;
            _retryHistoryLoad = null;
            SetJumpHighlight(id);
            Messages.Clear();
            foreach (var item in snapshot.Items)
            {
                ApplyMessageUiState(item);
                Messages.Add(item);
            }
            RefreshAlbumPresentation();
            HasOlderMessages = window.HasOlder;
            HasNewerMessages = window.HasNewer;
            return true;
        }
        return false;
    }

    public void Dispose()
    {
        if (_disposed) { return; }
        _disposed = true;_dataRefreshTimer.Stop();_conversationClock.Stop();CancelWebPreview();_preparationTimer.Stop();foreach(var value in _preparing.Values)value.Owner.Cancel();
        _initialHistoryCancellation?.Cancel();
        _historyPageCancellation?.Cancel();
        ++_draftGeneration;
        _services.Database.Changed -= DatabaseChanged;
        _services.Messaging.ContactsChanged -= ContactsChanged;
        _services.LiveLocations.Changed -= LiveLocationsChanged;
        _services.Relay.StateChanged -= RelayStateChanged;
        _services.Messaging.OperationFailed -= SecureOperationFailed;
        _services.Messaging.AttachmentProgressChanged -= AttachmentProgressChanged;
        _services.Drafts.SaveFailed -= DraftSaveFailed;
    }

    private MessageItem CreateMessageItem(ChatMessage message, IReadOnlyDictionary<string, HistoryMetadata>? metadata = null)
    {
        var quotedMessage = AppStrings.Get("UiQuotedMessage");
        metadata ??= _services.Database.GetHistoryMetadata([message]);
        var item = ReadMessageItem(message, metadata.GetValueOrDefault(message.MessageId), SelectedConversation?.Alias ?? quotedMessage, AppStrings.Get("UiQuotedYou"), quotedMessage);
        ApplyMessageUiState(item);
        return item;
    }

    private MessageItem ReadMessageItem(ChatMessage message, HistoryMetadata metadata, string peerAlias, string quotedYou, string quotedMessage)
    {
        var author = metadata.OriginalIsMine is bool mine
            ? (mine ? MyUuid : message.ConversationUuid) : message.ReplyToSender;
        var displayAuthor = message.ReplyToId is null ? "" : author == MyUuid ? quotedYou :
            author == message.ConversationUuid ? peerAlias : quotedMessage;
        var info=message.Extension=="attachment"?_services.Objects.Metadata(message.Content,message.MessageId):null;
        Func<Stream>? reader=info is null?null:()=>_services.Objects.OpenRead(message.Content);
        var item = new MessageItem(message, displayAuthor, info, reader, forwardInfo: metadata.Forward,
            readSticker: () => _services.Objects.ReadStickerBytes(message.Content), thumbnailStore: _services.Objects,
            linkPreview: metadata.Preview);
        if (item.IsLiveLocation) item.SetLiveSnapshot(_services.Messaging.LiveLocation(message.ConversationUuid, message.MessageId));
        return item;
    }

    private void ApplyMessageUiState(MessageItem item)
    {
        var message = item.Source;
        if (_services.Messaging.GetAttachmentProgress(message.MessageId) is { } progress) item.UpdateProgress(progress);
        item.IsSelected = _selectedMessages.Contains(message.MessageId);
        item.IsJumpHighlighted = message.MessageId == _jumpHighlightedId && !message.IsRevoked;
        item.JumpQuote = item.IsJumpHighlighted ? _jumpQuote : null;
        if (item.IsLiveLocation)
            item.RefreshLiveStatus(_services.LiveLocations.Foreground &&
                _services.LiveLocations.Current(message.ConversationUuid)?.MessageId == message.MessageId);
    }

    private void CancelInitialHistoryLoad()
    {
        _initialHistoryCancellation?.Cancel();
        _historyPageCancellation?.Cancel();
        InitialHistoryLoading = false;
        _historyRefreshPending = false;
    }

    private void LoadInitialMessages()
    {
        CancelInitialHistoryLoad();
        var version = ++HistoryNavigationVersion;
        InitialScrollTarget = null;
        InitialHistoryPosition = null;
        _unreadTimelineAnchor = null;
        _retryHistoryLoad = null;
        HistoryLoadFailed = false;
        HasOlderMessages = false;
        HasNewerMessages = false;
        InitialHistoryLoading = SelectedConversation is not null;
        Messages.Clear();
        if (SelectedConversation is not { } conversation) return;

        var peer = conversation.Uuid;
        var alias = conversation.Alias;
        var saved = _historyPositions.TryGetValue(peer, out var previous)
            ? (ChatHistoryPosition?)previous.Position : null;
        var quotedMessage = AppStrings.Get("UiQuotedMessage");
        var quotedYou = AppStrings.Get("UiQuotedYou");
        var owner = new CancellationTokenSource();
        _initialHistoryCancellation = owner;
        _initialHistoryLoad = LoadAsync(_initialHistoryLoad);

        async Task LoadAsync(Task previousLoad)
        {
            var token = owner.Token;
            var loaded = false;
            bool IsCurrent() => !token.IsCancellationRequested && !_disposed &&
                HistoryNavigationVersion == version && SelectedConversation?.Uuid == peer;
            try
            {
                await previousLoad;
                token.ThrowIfCancellationRequested();
                var snapshot = await Task.Run(() =>
                {
                    var unread = _services.Database.GetFirstUnreadId(peer);
                    var target = unread ?? saved?.MessageId;
                    var window = target is null ? null : _services.Database.GetMessageWindow(peer, target);
                    if (window is not null && !window.Messages.Any(message => message.MessageId == target && !message.IsRevoked))
                        window = null;
                    var rows = window?.Messages ?? _services.Database.GetMessages(peer, PageSize);
                    var metadata = _services.Database.GetHistoryMetadata(rows);
                    var items = new List<MessageItem>(rows.Count);
                    foreach (var row in rows)
                    {
                        token.ThrowIfCancellationRequested();
                        items.Add(ReadMessageItem(row, metadata.GetValueOrDefault(row.MessageId), alias, quotedYou, quotedMessage));
                    }
                    return (Items: items, Target: window is null ? null : target, Unread: unread,
                        HasOlder: window?.HasOlder ?? rows.Count == PageSize, HasNewer: window?.HasNewer == true);
                }, token);
                if (!IsCurrent()) return;
                if (snapshot.Target is null && snapshot.Unread is null && saved is not null) _historyPositions.Remove(peer);
                InitialScrollTarget = snapshot.Target;
                InitialHistoryPosition = snapshot.Unread is null && snapshot.Target is not null ? saved : null;
                _unreadTimelineAnchor = snapshot.Target is not null ? snapshot.Unread : null;
                IsViewingLatest = snapshot.Target is null;
                foreach (var item in snapshot.Items)
                {
                    ApplyMessageUiState(item);
                    Messages.Add(item);
                }
                RefreshAlbumPresentation();
                HasOlderMessages = snapshot.HasOlder;
                HasNewerMessages = snapshot.HasNewer;
                loaded = true;
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested) { }
            catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
            {
                if (IsCurrent())
                {
                    _retryHistoryLoad = LoadInitialMessages;
                    HistoryLoadFailed = true;
                }
            }
            finally
            {
                if (ReferenceEquals(_initialHistoryCancellation, owner))
                {
                    _initialHistoryCancellation = null;
                    if (IsCurrent())
                    {
                        InitialHistoryLoading = false;
                        if (loaded)
                        {
                            InitialMessagesLoaded?.Invoke(this, EventArgs.Empty);
                            if (_historyRefreshPending) QueueDataRefresh();
                        }
                        _historyRefreshPending = false;
                    }
                }
                owner.Dispose();
            }
        }
    }

    private void DatabaseChanged(object? sender, EventArgs args)
    {
        if (args is ContactVerificationChangedEventArgs verification)
        {
            QueueChatPeerVerification(verification.Peer);
            return;
        }
        if (args is VisibleMessagesReadEventArgs reads)
        {
            if (!_disposed && !_dispatcher.HasShutdownStarted)
                _dispatcher.BeginInvoke(DispatcherPriority.DataBind, new Action(() =>
                {
                    if (IsWorkspaceReady) RefreshVisibleReads(reads);
                    else Interlocked.Increment(ref _changeRevision);
                }));
            return;
        }
        if (args is MessageDeliveryChangedEventArgs delivery)
        {
            if (!_disposed && !_dispatcher.HasShutdownStarted)
                _dispatcher.BeginInvoke(DispatcherPriority.DataBind, new Action(() =>
                {
                    if (IsWorkspaceReady) RefreshDelivery(delivery);
                    else Interlocked.Increment(ref _changeRevision);
                }));
            return;
        }
        Interlocked.Increment(ref _changeRevision);
        if(_disposed || _dispatcher.HasShutdownStarted)return;
        _dispatcher.BeginInvoke(DispatcherPriority.DataBind,new Action(QueueDataRefresh));
    }

    private void RefreshVisibleReads(VisibleMessagesReadEventArgs change)
    {
        if (_disposed || _services.IsDisposed) return;
        try
        {
            if (Conversations.FirstOrDefault(item => item.Uuid == change.ConversationUuid) is { } conversation)
            {
                if (_services.Database.GetUnreadState(change.ConversationUuid) is { } unread)
                {
                    if (conversation.ApplyUnreadState(unread.Count, unread.FirstId, unread.Marked)) RefreshFolderChoices();
                }
                else QueueDataRefresh();
            }
        }
        catch (Exception error) when (error is IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            QueueDataRefresh();
        }

        if (SelectedConversation?.Uuid == change.ConversationUuid)
        {
            if (InitialHistoryLoading) _historyRefreshPending = true;
            foreach (var item in Messages)
                if (!item.IsMine && !item.IsRead && change.MessageIds.Contains(item.MessageId))
                    item.ApplyReceiptState(item.Source with { IsRead = true });
            VisibleReadsApplied?.Invoke(this, EventArgs.Empty);
        }
    }

    private void RefreshDelivery(MessageDeliveryChangedEventArgs delivery)
    {
        if (_disposed || _services.IsDisposed) return;
        if (InitialHistoryLoading && SelectedConversation?.Uuid == delivery.ConversationUuid)
            _historyRefreshPending = true;
        var conversation = Conversations.FirstOrDefault(item => item.Uuid == delivery.ConversationUuid && item.LastMessageId == delivery.MessageId);
        var existing = SelectedConversation?.Uuid == delivery.ConversationUuid
            ? Messages.FirstOrDefault(item => item.IsMine && item.MessageId == delivery.MessageId)
            : null;
        if (conversation is null && existing is null) return;
        ChatMessage? stored;
        try { stored = _services.Database.GetMessage(delivery.MessageId); }
        catch (Exception error) when (error is IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            QueueDataRefresh();
            return;
        }
        if (stored is not { IsMine: true, IsRevoked: false } || stored.ConversationUuid != delivery.ConversationUuid)
        {
            QueueDataRefresh();
            return;
        }
        conversation?.ApplyReceiptState(stored);
        if (existing is not null)
        {
            if ((existing.Source with { Status = stored.Status, IsRead = stored.IsRead }) == stored)
                existing.ApplyReceiptState(stored);
            else
                QueueDataRefresh();
        }
    }

    private void QueueDataRefresh()
    {
        if(_disposed || _services.IsDisposed || _refreshQueued || !IsWorkspaceReady)return;
        _refreshQueued=true;_dataRefreshTimer.Start();
    }

    private void RefreshMessages()
    {
        _refreshQueued = false;
        if (_disposed || _services.IsDisposed) { return; }
        var previousLast = Messages.LastOrDefault()?.Source;
        var wasViewingLatest = IsViewingLatest;
        RefreshConversations();
        RefreshFriendRequests();
        RefreshPinnedMessage(_pinnedPreviewAnchor);
        if (SelectedConversation is null) { Messages.Clear(); HasOlderMessages = false; ReplyTarget = null; return; }
        if (InitialHistoryLoading) { _historyRefreshPending = true; return; }
        var limit = Math.Max(PageSize, Messages.Count);
        var historyStart = wasViewingLatest ? null : Messages.FirstOrDefault()?.Source;
        var rows = historyStart is { } anchor
            ? _services.Database.GetMessagesFrom(anchor, limit)
            : _services.Database.GetMessages(SelectedConversation.Uuid, limit);
        if (historyStart is not null && rows.Count == 0)
            rows = _services.Database.GetMessages(SelectedConversation.Uuid, limit);

        var metadata = _services.Database.GetHistoryMetadata(rows);
        var rowIds = rows.Select(row => row.MessageId).ToHashSet(StringComparer.Ordinal);
        for (var index = Messages.Count - 1; index >= 0; index--)
        {
            if (!rowIds.Contains(Messages[index].MessageId)) Messages.RemoveAt(index);
        }
        var previousItems = Messages.ToDictionary(item => item.MessageId, StringComparer.Ordinal);
        var arrivalStart = rows.Count;
        if (wasViewingLatest && previousLast is not null && rows.Count > 0 &&
            rows[^1].Timestamp >= previousLast.Timestamp)
        {
            // Keep the old latest row as a boundary so pagination and history replacement stay static.
            for (var index = rows.Count - 1; index >= Math.Max(0, rows.Count - 13); index--)
            {
                if (rows[index].MessageId != previousLast.MessageId) continue;
                arrivalStart = index + 1;
                break;
            }
        }
        var arrivalTime = Environment.TickCount64;
        for (var index = 0; index < rows.Count; index++)
        {
            var candidate = CreateMessageItem(rows[index], metadata);
            previousItems.TryGetValue(candidate.MessageId, out var existing);
            if (existing is null)
            {
                if (index >= arrivalStart && !candidate.IsRevoked) candidate.ArrivalTime = arrivalTime;
                Messages.Insert(index, candidate);
            }
            else
            {
                if (!ReferenceEquals(Messages[index], existing))
                    Messages.Move(Messages.IndexOf(existing), index);
                var samePresentation = existing.AttachmentInfo == candidate.AttachmentInfo &&
                    existing.ForwardInfo == candidate.ForwardInfo;
                if (existing.Source != candidate.Source || !samePresentation)
                {
                    if (samePresentation && (existing.Source with { Status = candidate.Source.Status, IsRead = candidate.Source.IsRead }) == candidate.Source)
                        existing.ApplyReceiptState(candidate.Source);
                    else Messages[index]=candidate;
                }
                if (ReferenceEquals(Messages[index], existing))
                {
                    existing.ReplyQuoteAuthor = candidate.ReplyQuoteAuthor;
                    if (existing.IsLiveLocation) existing.SetLiveSnapshot(candidate.LiveSnapshot);
                }
            }
        }
        HasOlderMessages = rows.Count>0 && _services.Database.GetMessagesBefore(rows[0],1).Count>0;
        HasNewerMessages = rows.Count>0 && _services.Database.GetMessagesAfter(rows[^1],1).Count>0;
        var selectionChanged = false;
        foreach (var row in rows.Where(row => row.IsRevoked))
        {
            selectionChanged |= _selectedMessages.Remove(row.MessageId);
            if (_editingMessage?.MessageId == row.MessageId) CancelMessageEdit();
            if (ReplyTarget?.MessageId == row.MessageId) CancelReply();
        }
        RefreshDraftReply();
        if(selectionChanged)SelectionChanged();
        else
        {
            RefreshAlbumPresentation();
            if (IsSelecting) OnPropertyChanged(nameof(CanKeepSelection));
        }
        if (wasViewingLatest && !HasNewerMessages && Messages.LastOrDefault()?.Source is { } newest &&
            (previousLast is null || newest.MessageId != previousLast.MessageId && newest.Timestamp >= previousLast.Timestamp))
            LatestMessagesChanged?.Invoke(this, EventArgs.Empty);
    }

    private void RefreshDraftReply()
    {
        if (ReplyTarget is not { } previous || SelectedConversation is not { } conversation) return;
        var visible = Messages.FirstOrDefault(message => message.MessageId == previous.MessageId);
        var latest = visible?.Source ?? _services.Database.GetMessage(previous.MessageId);
        if (latest is null || latest.IsRevoked || latest.ConversationUuid != conversation.Uuid ||
            _replyQuote is not null && latest.Content != previous.Content)
        {
            CancelReply();
            return;
        }
        var refreshed = visible ?? CreateMessageItem(latest);
        if (_replyTarget?.Source != refreshed.Source || _replyTarget?.AttachmentInfo != refreshed.AttachmentInfo)
            SetProperty(ref _replyTarget, refreshed, nameof(ReplyTarget));
        OnPropertyChanged(nameof(ReplyTitle));
        OnPropertyChanged(nameof(ReplyPreview));
    }

    private readonly HashSet<string> _readPending = new(StringComparer.Ordinal);
    private void AttachmentProgressChanged(object? sender,AttachmentProgress value)
    {
        if(value.PreviewReference is not null || _disposed || _dispatcher.HasShutdownStarted) return;
        _dispatcher.BeginInvoke(()=>
        {
            if(!_disposed) Messages.FirstOrDefault(m=>m.MessageId==value.MessageId)?.UpdateProgress(value);
        });
    }

    public async Task<bool> MarkVisibleMessagesReadAsync(IReadOnlyCollection<string> visibleIds, Func<bool>? isSurfaceActive = null)
    {
        if(_disposed || _services.IsDisposed || InitialHistoryLoading || !IsChatContentVisible || SelectedConversation is null ||
           !(isSurfaceActive?.Invoke() ??
             (System.Windows.Application.Current.MainWindow is { IsActive: true, IsVisible: true }))) return true;
        var wanted=visibleIds.ToHashSet(StringComparer.Ordinal);
        var peer=SelectedConversation.Uuid;
        var ids=Messages.Where(m=>wanted.Contains(m.MessageId) && !m.IsMine && !m.IsRead && !m.IsRevoked && m.Source.ConversationUuid==peer)
            .Select(m=>m.MessageId).Where(id=>_readPending.Add(id)).Take(100).ToArray();
        if (ids.Length == 0) return true;
        try { return await _services.Messaging.MarkConversationReadAsync(peer, ids); }
        catch (Exception error) when (error is IOException or Microsoft.Data.Sqlite.SqliteException or
            InvalidOperationException or OperationCanceledException) { return false; }
        finally { foreach (var id in ids) _readPending.Remove(id); }
    }

    public void SelectContact(string peer)
    {
        if (_disposed) return;
        RefreshConversations();
        if (Conversations.FirstOrDefault(conversation => conversation.Uuid == peer) is { } target)
            SelectedConversation = target;
    }

    private void RefreshConversations()
    {
        _conversationFolders = _services.ConversationIndex.Folders();
        ApplyConversations(_services.Database.GetConversations());
    }

    private void ApplyConversations(IReadOnlyList<ConversationWithUnread> rows)
    {
        var currentIds = rows.Select(row => row.Conversation.Uuid).ToHashSet(StringComparer.Ordinal);
        for (var index = Conversations.Count - 1; index >= 0; index--)
        {
            if (!currentIds.Contains(Conversations[index].Uuid)) Conversations.RemoveAt(index);
        }
        var existingById = Conversations.ToDictionary(item => item.Uuid, StringComparer.Ordinal);
        for (var index = 0; index < rows.Count; index++)
        {
            var row = rows[index];
            if (!existingById.TryGetValue(row.Conversation.Uuid, out var existing))
                Conversations.Insert(index, new ConversationItem(row));
            else
            {
                existing.Update(row);
                if (!ReferenceEquals(Conversations[index], existing))
                    Conversations.Move(Conversations.IndexOf(existing), index);
            }
        }
        if (SelectedConversation is not null && !Conversations.Contains(SelectedConversation)) { SelectedConversation = null; }
        RefreshFolderChoices();
    }

    private void RefreshFriendRequests() => ApplyFriendRequests(_services.Database.GetFriendRequests());

    private void ApplyFriendRequests(IReadOnlyList<FriendRequest> requests)
    {
        if (FriendRequests.SequenceEqual(requests)) return;
        FriendRequests.Clear();
        foreach (var request in requests) FriendRequests.Add(request);
    }

    private void RelayStateChanged(object? sender, SyncState state)
    {
        _ = sender;
        _dispatcher.BeginInvoke(() => SyncText = state switch
        {
            SyncState.Synced => "Connected",
            SyncState.Syncing => "Syncing...",
            _ => "Disconnected"
        });
    }

    private void SecureOperationFailed(object? sender,string message)
    {
        _dispatcher.BeginInvoke(() => { if (!_disposed) ErrorRequested?.Invoke(this,message); });
    }
}

public sealed class ConversationItem : ObservableObject
{
    private ConversationWithUnread _source;
    private Dictionary<string, long> _folderPinOrders;
    private string? _displayFolderId;

    public ConversationItem(ConversationWithUnread source)
    {
        _source = source;
        _folderPinOrders = ReadFolderPinOrders(source);
    }

    private static Dictionary<string, long> ReadFolderPinOrders(ConversationWithUnread source) =>
        System.Text.Json.JsonSerializer.Deserialize<Dictionary<string, long>>(source.FolderPinOrders) ?? [];

    public long ActivityAt => _source.ActivityAt;
    public long PinOrderIn(string? folderId) => folderId is null ? _source.PinOrder : _folderPinOrders.GetValueOrDefault(folderId);

    public void ShowPinOrderFor(string? folderId)
    {
        if (_displayFolderId == folderId) return;
        _displayFolderId = folderId;
        OnPropertyChanged(nameof(PinOrder));
        OnPropertyChanged(nameof(IsPinned));
    }
    public string Uuid => _source.Conversation.Uuid;
    public string UuidPreview => Uuid[..Math.Min(12, Uuid.Length)] + "...";
    public string Alias => _source.Conversation.Alias;
    public string AvatarPath => _source.Conversation.AvatarPath;
    public string PeerPublicKey => _source.Conversation.PeerPublicKey;
    public int UnreadCount=>_source.UnreadCount;
    public bool HasUnread=>UnreadCount>0 || _source.ManualUnread;
    public string UnreadLabel=>UnreadCount>0?UnreadCount.ToString(CultureInfo.CurrentCulture):"•";
    public long PinOrder => PinOrderIn(_displayFolderId);
    public bool IsPinned=>PinOrder>0;
    public bool Archived=>_source.Archived;
    public bool IsMuted=>_source.MuteUntil>DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    internal long MuteUntil => _source.MuteUntil;
    public string FirstUnreadID=>_source.FirstUnreadID;
    public string Preview => _source.HasDraft
        ? (_source.DraftText.Length == 0 ? AppStrings.Get("UiReply") : _source.DraftText)
        : _source.SummaryKind switch
        {
            "revoked" => AppStrings.Get("UiQuoteRevoked"),
            "attachment" => AppStrings.Get("UiQuoteAttachment"),
            "call" => AppStrings.Get("UiCallHistory"),
            "contact" => AppStrings.Get("UiContactCard") + " · " +
                (_source.Summary.StartsWith("Contact: ", StringComparison.Ordinal) ? _source.Summary[9..] : _source.Summary),
            _ => _source.Summary.Length == 0 ? UuidPreview : CallSummaryText.Localize(_source.Summary)
        };
    public bool IsDraftPreview => _source.HasDraft;
    internal string LastMessageId => _source.LastID;
    public MessageReceiptPresentation Receipt => new(_source.HasDraft ? 4 : _source.LastReceiptStatus ?? 4, MessageId: _source.LastID);

    internal void ApplyReceiptState(ChatMessage message)
    {
        int? status = !message.IsRevoked && message.Status is >= -1 and <= 3 ? message.Status : null;
        if (_source.LastReceiptStatus == status) return;
        _source = _source with { LastReceiptStatus = status };
        OnPropertyChanged(nameof(Receipt));
    }

    internal bool ApplyUnreadState(int count, string firstId, bool marked)
    {
        var updated = _source with { UnreadCount = count, FirstUnreadID = firstId, ManualUnread = marked };
        if (_source == updated) return false;
        _source = updated;
        OnPropertyChanged(nameof(UnreadCount));
        OnPropertyChanged(nameof(HasUnread));
        OnPropertyChanged(nameof(UnreadLabel));
        OnPropertyChanged(nameof(FirstUnreadID));
        OnPropertyChanged(nameof(UnreadDescription));
        return true;
    }
    public string UnreadDescription => UnreadCount > 0
        ? AppStrings.Format("UiChatUnreadCount", UnreadCount)
        : AppStrings.Get("UiChatMarkedUnread");
    public string ActivityTime
    {
        get
        {
            var timestamp = _source.HasDraft ? _source.ActivityAt : _source.LastAt;
            if (timestamp <= 0 || timestamp > 253402300799999L) return "";
            var activity = DateTimeOffset.FromUnixTimeMilliseconds(timestamp).LocalDateTime;
            var today = DateTime.Today;
            var culture = CultureInfo.CurrentCulture;
            if (activity.Date == today) return activity.ToString("t", culture);
            if (activity.Date < today && (today - activity.Date).Days <= 6) return activity.ToString("ddd", culture);
            return activity.ToString(activity.Year == today.Year ? "MMM d" : "d", culture);
        }
    }
    public IReadOnlySet<string> FolderIDs=>_source.FolderIDs.Split(',',StringSplitOptions.RemoveEmptyEntries).ToHashSet(StringComparer.Ordinal);
    public void RefreshClock(){OnPropertyChanged(nameof(IsMuted));OnPropertyChanged(nameof(ActivityTime));}
    public string Initial => string.IsNullOrWhiteSpace(Alias) ? "?" : Alias[..1].ToUpperInvariant();
    public void Update(ConversationWithUnread source)
    {
        if (_source == source) { return; }
        var previous = _source;
        if (_source.FolderPinOrders != source.FolderPinOrders) _folderPinOrders = ReadFolderPinOrders(source);
        _source = source;
        if (previous.Conversation.Alias != source.Conversation.Alias)
        {
            OnPropertyChanged(nameof(Alias));
            OnPropertyChanged(nameof(Initial));
        }
        if (previous.Conversation.AvatarPath != source.Conversation.AvatarPath) OnPropertyChanged(nameof(AvatarPath));
        if (previous.Conversation.PeerPublicKey != source.Conversation.PeerPublicKey) OnPropertyChanged(nameof(PeerPublicKey));
        OnPropertyChanged(nameof(UnreadCount));
        OnPropertyChanged(nameof(Receipt));
        foreach(var property in new[]{nameof(HasUnread),nameof(UnreadLabel),nameof(PinOrder),nameof(IsPinned),nameof(Archived),nameof(IsMuted),nameof(FirstUnreadID),nameof(Preview),nameof(IsDraftPreview),nameof(ActivityTime),nameof(UnreadDescription),nameof(FolderIDs)})OnPropertyChanged(property);
    }
}

public sealed class MessageItem(ChatMessage source, string replyAuthor = "User",AttachmentMetadata? attachmentInfo=null,
    Func<Stream>? openMedia=null,AttachmentProgress? progress=null,ForwardedMessageInfo? forwardInfo=null,Func<byte[]>? readSticker=null,
    EncryptedObjectStore? thumbnailStore=null, WebPreviewSnapshot? linkPreview=null) : ObservableObject
{
    internal EncryptedObjectStore? ThumbnailStore => thumbnailStore;
    internal WebPreviewSnapshot? LinkPreview => source.IsRevoked ? null : linkPreview;
    internal long? ArrivalTime { private get; set; }

    internal bool ConsumeRecentArrival()
    {
        var started = ArrivalTime;
        ArrivalTime = null;
        return started is { } time && Environment.TickCount64 - time < 180;
    }

    public void ApplyReceiptState(ChatMessage updated)
    {
        var statusChanged = source.Status != updated.Status;
        var readChanged = source.IsRead != updated.IsRead;
        source = updated;
        if (readChanged) OnPropertyChanged(nameof(IsRead));
        if (!statusChanged) return;
        OnPropertyChanged(nameof(ReceiptStatus));
        OnPropertyChanged(nameof(Receipt));
        OnPropertyChanged(nameof(StatusName));
        OnPropertyChanged(nameof(IsDelivered));
        OnPropertyChanged(nameof(IsDeliveryFailed));
        OnPropertyChanged(nameof(IsSending));
        OnPropertyChanged(nameof(CanRetrySend));
        OnPropertyChanged(nameof(CanRetryUpload));
        OnPropertyChanged(nameof(TransferText));
        OnPropertyChanged(nameof(FileTransferText));
    }

    internal ForwardedMessageInfo? ForwardInfo => forwardInfo;
    public bool HasForward => !source.IsRevoked && forwardInfo is not null;
    public string? PreviewDownloadPeer => !source.IsMine && !source.IsRevoked ? source.ConversationUuid : null;
    public string? ForwardLabel => !HasForward ? null : forwardInfo?.Name is { } name && !string.IsNullOrWhiteSpace(name)
        ? AppStrings.Format("UiForwardedFrom", name) : AppStrings.Get("UiForwardedMessage");
    private bool _isSelected;
    public bool IsSelected {get=>_isSelected;internal set=>SetProperty(ref _isSelected,value);}
    private bool _isJumpHighlighted;
    public bool IsJumpHighlighted { get => _isJumpHighlighted; internal set => SetProperty(ref _isJumpHighlighted, value); }
    private string? _jumpQuote;
    public string? JumpQuote
    {
        get => _jumpQuote;
        internal set
        {
            if (SetProperty(ref _jumpQuote, value)) OnPropertyChanged(nameof(DisplayContent));
        }
    }
    private AttachmentProgress? _progress=progress;
    internal ChatMessage Source => source;
    public AttachmentMetadata? AttachmentInfo { get; }=attachmentInfo;
    private MessageItem? _albumAnchor;
    private IReadOnlyList<MessageItem> _albumMembers = [];
    public MessageItem? AlbumAnchor => _albumAnchor;
    public IReadOnlyList<MessageItem> AlbumMembers => _albumMembers;
    public bool HasAlbum => _albumMembers.Count > 0;
    public bool HiddenByAlbum => _albumAnchor is not null && !ReferenceEquals(this, _albumAnchor);
    public string DisplayCaption => HasAlbum
        ? _albumMembers.FirstOrDefault(item => item.AttachmentInfo?.Album?.Index == 0)?.Caption ?? ""
        : Caption;
    public bool HasDisplayCaption => !string.IsNullOrEmpty(DisplayCaption);
    public string AlbumItemCaption => AttachmentInfo?.Album is { Index: > 0 } ? Caption : "";
    public bool HasAlbumItemCaption => !string.IsNullOrEmpty(AlbumItemCaption);

    internal void SetAlbumPresentation(MessageItem? anchor, IReadOnlyList<MessageItem> members)
    {
        if (ReferenceEquals(_albumAnchor, anchor) && _albumMembers.SequenceEqual(members)) return;
        _albumAnchor = anchor;
        _albumMembers = members;
        OnPropertyChanged(nameof(AlbumAnchor));
        OnPropertyChanged(nameof(AlbumMembers));
        OnPropertyChanged(nameof(HasAlbum));
        OnPropertyChanged(nameof(HiddenByAlbum));
        OnPropertyChanged(nameof(DisplayCaption));
        OnPropertyChanged(nameof(HasDisplayCaption));
    }

    public string Filename => AttachmentInfo?.Filename ?? (EncryptedObjectStore.IsReference(Content)
        ? AppStrings.Get("UiSearchAttachment") : Path.GetFileName(Content));
    public string Caption=>AttachmentInfo?.Caption??"";
    public bool HasCaption=>!string.IsNullOrEmpty(Caption);
    public bool IsAvailable=>AttachmentInfo?.Available??(IsAttachment && File.Exists(Content));
    private bool CanManageDownload => IsAttachment && !IsAvailable && !IsMine && !IsRevoked;
    private bool DownloadPaused => AttachmentInfo?.DownloadIntent == AttachmentDownloadIntent.Paused;
    private bool DownloadRequested => AttachmentInfo?.DownloadIntent == AttachmentDownloadIntent.Requested;
    public bool CanRequestDownload => CanManageDownload && !CanPauseDownload;
    public bool CanPauseDownload => CanManageDownload && !DownloadPaused && AttachmentInfo?.RemoteUnavailable != true &&
        (DownloadRequested || _progress?.Phase == AttachmentPhase.Downloading);
    internal bool CanRetryDownload => CanRequestDownload && !DownloadPaused &&
        (AttachmentInfo?.RemoteUnavailable == true || _progress?.Phase == AttachmentPhase.Retry);
    public string DownloadActionKey => CanPauseDownload ? "UiPauseDownload" :
        DownloadPaused ? "UiResumeDownload" : "UiDownloadMediaAttachment";
    public string AlbumDownloadStatus => !CanManageDownload ? "" :
        AttachmentInfo?.RemoteUnavailable == true ? AppStrings.Get("UiDownloadRelayUnavailable") :
        DownloadPaused ? AppStrings.Get("UiDownloadPaused") :
        _progress is { Phase: AttachmentPhase.Downloading, Total: > 0 }
            ? AppStrings.Format("UiAlbumDownloadProgress", Percent) :
        _progress?.Phase == AttachmentPhase.Retry ? AppStrings.Get("UiRetry") :
        DownloadRequested ? AppStrings.Get("UiDownloadQueued") : AppStrings.Get("UiDownloadMediaAttachment");
    public bool CanRetryUpload => IsAttachment && IsSending && _progress?.Phase == AttachmentPhase.Retry;
    private bool _isRetryingSend;
    public bool IsRetryingSend { get => _isRetryingSend; internal set => SetProperty(ref _isRetryingSend, value); }
    public bool IsText=>!IsAttachment && Extension=="txt";
    public bool HasExpiration => !IsRevoked && source.ExpiresAt.HasValue;
    public bool CanEditContent => IsMine && !IsRevoked && !HasExpiration && (IsText || IsAttachment && AttachmentInfo is { IsSticker: false });
    internal string EditableText => IsAttachment ? Caption : Content;
    public bool UsesTextFooter => IsText && !IsRevoked && !Content.Contains("https://", StringComparison.OrdinalIgnoreCase) &&
        !Content.Contains("http://", StringComparison.OrdinalIgnoreCase);
    public bool HasStandaloneTextFooter => IsText && !IsRevoked && !UsesTextFooter;
    public bool IsContactCard => !IsRevoked && Extension == "contact";
    public bool IsLocation => !IsRevoked && Extension == "location";
    public bool IsLiveLocation => !IsRevoked && Extension == "live_location";
    public string CardTitle => Content == "Location" && (IsLiveLocation || IsLocation)
        ? AppStrings.Get(IsLiveLocation ? "UiLiveLocation" : "UiLocation") : Content;

    internal void RefreshMessageLanguage()
    {
        if (HasForward) OnPropertyChanged(nameof(ForwardLabel));
        if (HasExpiration) OnPropertyChanged(nameof(ExpirationLabel));
        if (HasTimelineDay) OnPropertyChanged(nameof(TimelineDay));
        if (IsAttachment)
        {
            OnPropertyChanged(nameof(TransferText));
            OnPropertyChanged(nameof(AlbumDownloadStatus));
        }
        if (IsRevoked) OnPropertyChanged(nameof(DisplayContent));
        if (IsLocation || IsLiveLocation) OnPropertyChanged(nameof(CardTitle));
        if (IsLiveLocation) OnPropertyChanged(nameof(LiveLocationStatus));
    }
    internal LiveLocationSnapshot? LiveSnapshot { get; private set; }
    private bool _collectingLocation;
    public string LiveLocationStatus => LiveSnapshot is not { } snapshot ? AppStrings.Get("UiLiveLocation") :
        snapshot.Ended || snapshot.ExpiresAt <= DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() ? AppStrings.Get("UiLiveLocationEnded") :
        IsMine && !_collectingLocation ? AppStrings.Get("UiLiveLocationPaused") : AppStrings.Get("UiLiveLocation");

    internal void SetLiveSnapshot(LiveLocationSnapshot? snapshot)
    {
        if (LiveSnapshot == snapshot) return;
        LiveSnapshot = snapshot;
        RefreshLiveClock();
    }

    internal void RefreshLiveStatus(bool collecting)
    {
        if (_collectingLocation == collecting) return;
        _collectingLocation = collecting;
        RefreshLiveClock();
    }

    internal void RefreshLiveClock() => OnPropertyChanged(nameof(LiveLocationStatus));
    public bool IsSticker=>AttachmentInfo?.IsSticker==true;
    public bool IsImage=>AttachmentInfo?.Mime.StartsWith("image/",StringComparison.Ordinal)==true || Extension is "jpg" or "jpeg" or "png" or "webp" or "gif";
    public bool IsVideo=>AttachmentInfo?.Mime.StartsWith("video/",StringComparison.Ordinal)==true || Extension is "mp4" or "mov" or "webm";
    public bool UsesVisualMediaFooter => IsAttachment && (IsImage || IsVideo) &&
        AttachmentInfo is { Available: true, IsSticker: false, Album: null } && string.IsNullOrEmpty(Caption);
    public bool IsAudio=>AttachmentInfo?.Mime.StartsWith("audio/",StringComparison.Ordinal)==true || Extension is "m4a" or "mp3" or "ogg" or "opus" or "wav" or "flac";
    internal bool IsFileCard => IsAttachment && !IsImage && !IsVideo && !IsAudio && !IsSticker;
    private bool FileDetailsMissing => AttachmentInfo is null && EncryptedObjectStore.IsReference(Content);
    public string FileSizeText => AttachmentInfo is { } attachment ? AppStrings.FileSize(attachment.Size) : "";
    public string FileActionText => IsRevoked || FileDetailsMissing ? "" : IsAvailable
        ? HasExpiration ? "" : AppStrings.Get("UiSaveAttachment")
        : CanRetryDownload ? AppStrings.Get("UiRetry")
        : CanPauseDownload || CanRequestDownload ? AppStrings.Get(DownloadActionKey) : "";
    public bool HasFileAction => FileActionText.Length > 0;
    public string FileTransferText => FileDetailsMissing ? AppStrings.Get("UiFileDetailsUnavailable") :
        _progress?.Phase == AttachmentPhase.Downloading && CanPauseDownload ? AppStrings.Format("UiAlbumDownloadProgress", Percent) :
        TransferText == FileActionText ? "" : TransferText;
    public string Mime=>AttachmentInfo?.Mime??EncryptedObjectStore.MimeForPath(Content);
    public byte[] ReadStickerBytes()=>readSticker?.Invoke()??throw new IOException("Sticker reader is unavailable.");
    public Stream OpenMedia()=>openMedia?.Invoke()??File.OpenRead(Content);
    public string TransferText=> _progress?.Phase == AttachmentPhase.Uploading ? AppStrings.Format("UiAttachmentUploadProgress", Percent) :
        IsAvailable ? (IsMine && source.Status == 0 ? AppStrings.Get("UiQueueWaiting") : "") :
        DownloadPaused ? AppStrings.Get("UiDownloadPaused") :
        AttachmentInfo?.RemoteUnavailable == true ? AppStrings.Get("UiDownloadRelayUnavailable") :
        DownloadRequested && _progress?.Phase is not (AttachmentPhase.Downloading or AttachmentPhase.Retry)
            ? AppStrings.Get("UiDownloadQueued") : _progress?.Phase switch
    {
        AttachmentPhase.Downloading when CanPauseDownload => AppStrings.Format("UiDownloadProgressPause", Percent),
        AttachmentPhase.Downloading => AppStrings.Format("UiAlbumDownloadProgress", Percent),
        AttachmentPhase.Retry => AppStrings.Get(CanRequestDownload ? "UiRetry" : "UiAttachmentWaitingRetry"),
        AttachmentPhase.Unavailable when !IsAvailable=>AppStrings.Get("UiDownloadRelayUnavailable"),
        _=>CanRequestDownload ? AppStrings.Get("UiDownloadMediaAttachment") :
            IsMine && source.Status == 0 ? AppStrings.Get("UiQueueWaiting") : ""
    };
    public bool HasTransferProgress => _progress is { Total: > 0 } value &&
        (value.Phase == AttachmentPhase.Uploading || value.Phase == AttachmentPhase.Downloading &&
            CanManageDownload && !DownloadPaused && AttachmentInfo?.RemoteUnavailable != true);
    public double TransferPercent => HasTransferProgress && _progress is { } value
        ? 100.0 * Math.Clamp(value.Bytes, 0L, value.Total) / value.Total : 0;
    private long Percent => (long)TransferPercent;
    public void UpdateProgress(AttachmentProgress value)
    {
        _progress=value; OnPropertyChanged(nameof(TransferText)); OnPropertyChanged(nameof(CanRequestDownload));
        OnPropertyChanged(nameof(AlbumDownloadStatus));
        OnPropertyChanged(nameof(CanPauseDownload));
        OnPropertyChanged(nameof(DownloadActionKey));
        OnPropertyChanged(nameof(CanRetryUpload));
        OnPropertyChanged(nameof(HasTransferProgress));
        OnPropertyChanged(nameof(TransferPercent));
        OnPropertyChanged(nameof(FileActionText));
        OnPropertyChanged(nameof(HasFileAction));
        OnPropertyChanged(nameof(FileTransferText));
    }
    public string MessageId { get; } = source.MessageId;
    public bool CanModify => IsMine && !IsRevoked;
    public bool CanReEdit => IsMine && IsRevoked && Extension == "txt" && !string.IsNullOrEmpty(Content);
    public bool IsRead => Source.IsRead;
    public bool IsMine { get; } = source.IsMine;
    public string Content { get; } = source.Content;
    public string Extension { get; } = source.Extension;
    public bool IsRevoked { get; } = source.IsRevoked;
    public bool IsEdited { get; } = source.IsEdited;
    public string? ReplyToId { get; } = source.ReplyToId;
    public bool HasReplyQuote => !IsRevoked && !string.IsNullOrEmpty(ReplyToId);
    private string _replyQuoteAuthor = replyAuthor;
    public string ReplyQuoteAuthor
    {
        get => _replyQuoteAuthor;
        internal set => SetProperty(ref _replyQuoteAuthor, value);
    }
    public string ReplyQuoteText => source.ReplyToContent switch
    {
        "[Message revoked]" => AppStrings.Get("UiQuoteRevoked"),
        "[Media]" or "[Attachment]" or "[ATTACHMENT]" => AppStrings.Get("UiQuoteAttachment"),
        { } text when !string.IsNullOrWhiteSpace(text) => text,
        _ => AppStrings.Get("UiQuoteNoPreview")
    };
    public string DisplayContent => IsRevoked ? AppStrings.Get(IsMine ? "UiRevokedByYou" : "UiRevokedByPeer")
        : IsText ? JumpQuote is null ? CallSummaryText.Localize(Content) : Content : Filename;
    public string QuoteContent => Extension switch
    {
        "txt" or "location" => Content,
        "contact" => $"Contact: {Content}",
        _ => $"[{Extension.ToUpperInvariant()}]"
    };
    public string EditedText => IsEdited && !IsRevoked ? " " + AppStrings.Get("UiMessageEdited") : "";
    internal DateTime? LocalTimestamp => source.Timestamp is >= 0 and <= 253402300799999L
        ? DateTimeOffset.FromUnixTimeMilliseconds(source.Timestamp).LocalDateTime : null;
    public string TimeText => LocalTimestamp?.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.CurrentCulture) ?? "";
    public string ShortTimeText => LocalTimestamp?.ToString("t", CultureInfo.CurrentCulture) ?? "";
    public string ExpirationLabel => HasExpiration && source.ExpiresAt is long expiresAt &&
        expiresAt is > 0 and <= 253402300799999L
        ? AppStrings.Format("UiMessageExpirationDeadline",
            DateTimeOffset.FromUnixTimeMilliseconds(expiresAt).LocalDateTime.ToString("g", CultureInfo.CurrentCulture))
        : "";
    private bool _joinsOlder;
    private bool _joinsNewer;
    internal bool JoinsOlder => _joinsOlder;
    public bool JoinsNewer => _joinsNewer;
    public Thickness TimelineSpacing => new(0, 0, 0, _joinsNewer ? 2 : 7);
    public CornerRadius TimelineCorners => new(
        !IsMine && _joinsOlder ? 4 : 14,
        IsMine && _joinsOlder ? 4 : 14,
        IsMine && _joinsNewer ? 4 : 14,
        !IsMine && _joinsNewer ? 4 : 14);

    internal void SetTimelineCluster(bool joinsOlder, bool joinsNewer)
    {
        if (_joinsOlder == joinsOlder && _joinsNewer == joinsNewer) return;
        var newerChanged = _joinsNewer != joinsNewer;
        _joinsOlder = joinsOlder;
        _joinsNewer = joinsNewer;
        OnPropertyChanged(nameof(TimelineCorners));
        if (newerChanged)
        {
            OnPropertyChanged(nameof(JoinsNewer));
            OnPropertyChanged(nameof(TimelineSpacing));
        }
    }

    private bool _startsUnread;
    public bool StartsUnread { get => _startsUnread; internal set => SetProperty(ref _startsUnread, value); }
    private DateTime? _timelineDay;
    public bool HasTimelineDay => _timelineDay.HasValue;
    public string TimelineDay => _timelineDay is { } day ? AppStrings.TimelineDate(day) : "";

    internal void SetTimelineDay(DateTime? day)
    {
        if (_timelineDay == day) return;
        _timelineDay = day;
        OnPropertyChanged(nameof(HasTimelineDay));
        OnPropertyChanged(nameof(TimelineDay));
    }
    public bool IsDelivered => source.Status >= 2;
    public bool IsDeliveryFailed => IsMine && !IsRevoked && source.Status==-1;
    public bool IsSending => IsMine && !IsRevoked && source.Status == 0;
    public bool CanRetrySend => IsSending || IsDeliveryFailed;
    public bool IsAttachment => !IsRevoked && Extension != "txt";
    public int ReceiptStatus => IsMine && !IsRevoked && source.Status is >= -1 and <= 3 ? source.Status : 4;
    public MessageReceiptPresentation Receipt => new(ReceiptStatus, IsText, UsesVisualMediaFooter, MessageId);
    public string StatusName => Receipt.StatusName;
}

public readonly record struct MessageReceiptPresentation(int ReceiptStatus, bool IsText = false, bool UsesVisualMediaFooter = false,
    string? MessageId = null)
{
    public bool IsDelivered => ReceiptStatus is 2 or 3;
    public bool IsDeliveryFailed => ReceiptStatus == -1;
    public string StatusName => ReceiptStatus switch
    {
        -1 => AppStrings.Get("UiReceiptFailed"),
        0 => AppStrings.Get("UiReceiptSending"),
        1 => AppStrings.Get("UiReceiptSent"),
        2 => AppStrings.Get("UiReceiptDelivered"),
        3 => AppStrings.Get("UiReceiptRead"),
        _ => ""
    };
}
