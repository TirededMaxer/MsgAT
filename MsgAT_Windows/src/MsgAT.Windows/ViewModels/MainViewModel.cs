using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows.Threading;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.ViewModels;

public sealed class MainViewModel : ObservableObject, IDisposable
{
    private const int PageSize = 50;
    private readonly AppServices _services;
    private readonly Dispatcher _dispatcher;
    private ConversationItem? _selectedConversation;
    private string _draft = "";
    private bool _showAttachments;
    private bool _isViewingLatest = true;
    private string _syncText = "Disconnected";
    private bool _hasOlderMessages;
    private MessageItem? _replyTarget;
    private bool _disposed;
    private bool _refreshQueued;

    public MainViewModel(AppServices services, Dispatcher dispatcher)
    {
        _services = services;
        _dispatcher = dispatcher;
        _services.Database.Changed += DatabaseChanged;
        _services.Relay.StateChanged += RelayStateChanged;
        RefreshConversations();
        RefreshFriendRequests();
        RelayStateChanged(this, _services.Relay.State);
    }

    public event EventHandler<string>? ErrorRequested;
    public event EventHandler? InitialMessagesLoaded;
    public event EventHandler? LatestMessagesChanged;

    public ObservableCollection<ConversationItem> Conversations { get; } = [];
    public ObservableCollection<MessageItem> Messages { get; } = [];
    public ObservableCollection<FriendRequest> FriendRequests { get; } = [];

    public ConversationItem? SelectedConversation
    {
        get => _selectedConversation;
        set
        {
            if (!SetProperty(ref _selectedConversation, value)) { return; }
            ReplyTarget = null;
            Draft = "";
            ShowAttachments = false;
            IsViewingLatest = true;
            OnPropertyChanged(nameof(HasConversation));
            LoadInitialMessages();
        }
    }

    public string Draft
    {
        get => _draft;
        set
        {
            if (!SetProperty(ref _draft, value)) { return; }
            OnPropertyChanged(nameof(HasDraft));
            if (!string.IsNullOrEmpty(Draft)) { ShowAttachments = false; }
        }
    }
    public bool HasDraft => !string.IsNullOrWhiteSpace(Draft);
    public bool ShowAttachments { get => _showAttachments; set => SetProperty(ref _showAttachments, value); }
    public string SyncText { get => _syncText; private set => SetProperty(ref _syncText, value); }
    public bool HasConversation => SelectedConversation is not null;
    public bool IsViewingLatest { get => _isViewingLatest; set { if (SetProperty(ref _isViewingLatest, value)) OnPropertyChanged(nameof(ShowLatestButton)); } }
    public bool IsChatContentVisible { get; set; } = true;
    public bool ShowLatestButton => !IsViewingLatest;
    public bool HasNewUnread => Messages.LastOrDefault() is { IsMine: false, IsRead: false };
    public MessageItem? ReplyTarget { get => _replyTarget; private set { if (SetProperty(ref _replyTarget, value)) { OnPropertyChanged(nameof(HasReplyTarget)); OnPropertyChanged(nameof(ReplyPreview)); OnPropertyChanged(nameof(ReplyTitle)); } } }
    public bool HasReplyTarget => ReplyTarget is not null;
    public string ReplyPreview => ReplyTarget?.QuoteContent ?? "";
    public string ReplyTitle => ReplyTarget is null ? "" : $"Replying to {(ReplyTarget.IsMine ? "Me" : SelectedConversation?.Alias ?? "User")}";
    public bool HasOlderMessages { get => _hasOlderMessages; private set => SetProperty(ref _hasOlderMessages, value); }
    public SecureSettings Settings => _services.Settings.Snapshot;
    public string MyUuid => _services.Settings.MyUuid;
    public AppServices Services => _services;

    public async Task SendDraftAsync()
    {
        if (SelectedConversation is null || string.IsNullOrWhiteSpace(Draft)) { return; }
        var text = Draft;
        var peerUuid = SelectedConversation.Uuid;
        var reply = ReplyTarget;
        Draft = "";
        ReplyTarget = null;
        try
        {
            await _services.Messaging.SendTextAsync(
                peerUuid,
                text,
                reply?.MessageId,
                reply is null ? null : reply.IsMine ? MyUuid : peerUuid,
                reply?.QuoteContent);
        }
        catch (Exception exception) when (exception is InvalidOperationException or IOException)
        {
            if (SelectedConversation?.Uuid == peerUuid && string.IsNullOrEmpty(Draft)) Draft = text;
            ErrorRequested?.Invoke(this, "Send content failed");
        }
    }

    public Task SendMediaAsync(string path) => CaptureMediaSender()?.Invoke(path) ?? Task.CompletedTask;

    public Func<string, Task>? CaptureMediaSender()
    {
        if (SelectedConversation is null) return null;
        var peerUuid = SelectedConversation.Uuid;
        var reply = ReplyTarget;
        ReplyTarget = null;
        return path => SendMediaToAsync(path, peerUuid, reply);
    }

    private async Task SendMediaToAsync(string path, string peerUuid, MessageItem? reply)
    {
        try
        {
            await _services.Messaging.SendMediaAsync(peerUuid, path, reply?.MessageId,
                reply is null ? null : reply.IsMine ? MyUuid : peerUuid, reply?.QuoteContent);
        }
        catch (Exception exception) when (exception is InvalidOperationException or IOException or ArgumentException)
        {
            ErrorRequested?.Invoke(this, exception is InvalidDataException && exception.Message.Contains("100 MB", StringComparison.Ordinal) ? "Files cannot exceed 100 MB." : "Send content failed");
        }
    }

    public async Task AddFriendAsync(string uuid)
    {
        try { await _services.Messaging.AddFriendAsync(uuid); }
        catch (Exception exception) when (exception is InvalidOperationException or ArgumentException)
        {
            ErrorRequested?.Invoke(this, exception.Message);
        }
    }

    public async Task AcceptFriendRequestAsync(FriendRequest request)
    {
        try { await _services.Messaging.AcceptFriendRequestAsync(request.SenderUuid); }
        catch (InvalidOperationException exception) { ErrorRequested?.Invoke(this, exception.Message); }
    }

    public void DeclineFriendRequest(FriendRequest request) => _services.Messaging.DeclineFriendRequest(request.SenderUuid);

    public async Task DeleteSelectedFriendAsync()
    {
        if (SelectedConversation is not null) { await DeleteFriendAsync(SelectedConversation.Uuid); }
    }

    public async Task DeleteFriendAsync(string uuid)
    {
        await _services.Messaging.DeleteFriendAsync(uuid);
        if (SelectedConversation?.Uuid == uuid) { SelectedConversation = null; }
    }

    public void ClearMessages(string uuid) => _services.Messaging.ClearMessages(uuid);

    public void ClearSelectedMessages()
    {
        if (SelectedConversation is not null) { ClearMessages(SelectedConversation.Uuid); }
    }

    public async Task RevokeAsync(MessageItem item)
    {
        if (SelectedConversation is null || !item.IsMine || item.IsRevoked) { return; }
        try { await _services.Messaging.RevokeMessageAsync(SelectedConversation.Uuid, item.MessageId); }
        catch (InvalidOperationException) { ErrorRequested?.Invoke(this, "The message change could not be sent."); }
    }

    public void SetReply(MessageItem item) { if (!item.IsRevoked) { ReplyTarget = item; } }
    public void CancelReply() => ReplyTarget = null;

    public async Task EditAsync(MessageItem item, string newText)
    {
        if (SelectedConversation is null || !item.IsMine || item.IsRevoked || item.Extension != "txt") { return; }
        try { await _services.Messaging.EditMessageAsync(SelectedConversation.Uuid, item.MessageId, newText); }
        catch (InvalidOperationException) { ErrorRequested?.Invoke(this, "The message change could not be sent."); }
    }

    public void LoadOlderMessages()
    {
        if (SelectedConversation is null || !HasOlderMessages) { return; }
        var older = _services.Database.GetMessages(SelectedConversation.Uuid, PageSize, Messages.Count);
        for (var index = older.Count - 1; index >= 0; index--)
        {
            Messages.Insert(0, CreateMessageItem(older[index]));
        }
        HasOlderMessages = older.Count == PageSize;
    }

    public MessageItem? RevealMessage(string messageId)
    {
        if (SelectedConversation is null) return null;
        var existing = Messages.FirstOrDefault(message => message.MessageId == messageId);
        if (existing is not null) return existing;
        var target = _services.Database.GetMessage(messageId);
        if (target?.ConversationUuid != SelectedConversation.Uuid) return null;
        while (HasOlderMessages)
        {
            LoadOlderMessages();
            var loaded = Messages.FirstOrDefault(message => message.MessageId == messageId);
            if (loaded is not null) return loaded;
        }
        return null;
    }

    public void Dispose()
    {
        if (_disposed) { return; }
        _disposed = true;
        _services.Database.Changed -= DatabaseChanged;
        _services.Relay.StateChanged -= RelayStateChanged;
    }

    private MessageItem CreateMessageItem(ChatMessage message)
    {
        var original = message.ReplyToId is { } id ? _services.Database.GetMessage(id) : null;
        if (original?.ConversationUuid != message.ConversationUuid) original = null;
        var author = original is null ? message.ReplyToSender : original.IsMine ? MyUuid : message.ConversationUuid;
        var displayAuthor = author == MyUuid ? "Me" :
            author == message.ConversationUuid ? SelectedConversation?.Alias ?? "User" : "User";
        return new MessageItem(message, displayAuthor);
    }

    private void LoadInitialMessages()
    {
        Messages.Clear();
        if (SelectedConversation is null)
        {
            HasOlderMessages = false;
            return;
        }
        var rows = _services.Database.GetMessages(SelectedConversation.Uuid, PageSize);
        foreach (var message in rows) { Messages.Add(CreateMessageItem(message)); }
        HasOlderMessages = rows.Count == PageSize;
        InitialMessagesLoaded?.Invoke(this, EventArgs.Empty);
    }

    private void DatabaseChanged(object? sender, EventArgs args)
    {
        _ = sender;
        _ = args;
        if (_disposed || _dispatcher.HasShutdownStarted) { return; }
        _dispatcher.BeginInvoke(() =>
        {
            if (_disposed || _services.IsDisposed || _refreshQueued) { return; }
            _refreshQueued = true;
            _dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(RefreshMessages));
        });
    }

    private void RefreshMessages()
    {
        _refreshQueued = false;
        if (_disposed || _services.IsDisposed) { return; }
        MarkActiveConversationRead();
        var previousLast = Messages.LastOrDefault()?.MessageId;
        RefreshConversations();
        RefreshFriendRequests();
        if (SelectedConversation is null) { Messages.Clear(); HasOlderMessages = false; ReplyTarget = null; return; }
        var limit = Math.Max(PageSize, Messages.Count);
        var rows = _services.Database.GetMessages(SelectedConversation.Uuid, limit);
        for (var index = 0; index < rows.Count; index++)
        {
            var candidate = CreateMessageItem(rows[index]);
            var existing = Messages.FirstOrDefault(item => item.MessageId == candidate.MessageId);
            if (existing is null) { Messages.Insert(index, candidate); }
            else
            {
                var oldIndex = Messages.IndexOf(existing);
                if (oldIndex != index) { Messages.Move(oldIndex, index); }
                if (existing.Source != candidate.Source || existing.ReplyPreview != candidate.ReplyPreview)
                {
                    Messages[index] = candidate;
                }
            }
        }
        while (Messages.Count > rows.Count) { Messages.RemoveAt(Messages.Count - 1); }
        HasOlderMessages = rows.Count >= limit;
        OnPropertyChanged(nameof(HasNewUnread));
        if (previousLast != Messages.LastOrDefault()?.MessageId) { LatestMessagesChanged?.Invoke(this, EventArgs.Empty); }
    }

    private async Task SendReadAckSafelyAsync(string peer, string messageId)
    {
        try { await _services.Messaging.SendReadAcknowledgementAsync(peer, messageId); }
        catch (InvalidOperationException) { }
    }

    public void MarkActiveConversationRead()
    {
        if (_disposed || _services.IsDisposed || !IsViewingLatest || !IsChatContentVisible || SelectedConversation is null ||
            System.Windows.Application.Current.MainWindow is not { IsActive: true, IsVisible: true }) { return; }
        var unread = _services.Database.MarkConversationRead(SelectedConversation.Uuid);
        if (unread is null) { return; }
        _ = SendReadAckSafelyAsync(SelectedConversation.Uuid, unread.MessageId);
    }

    private void RefreshConversations()
    {
        var rows = _services.Database.GetConversations();
        for (var index = 0; index < rows.Count; index++)
        {
            var row = rows[index];
            var existing = Conversations.FirstOrDefault(item => item.Uuid == row.Conversation.Uuid);
            if (existing is null) { Conversations.Insert(index, new ConversationItem(row)); }
            else
            {
                existing.Update(row);
                var oldIndex = Conversations.IndexOf(existing);
                if (oldIndex != index) { Conversations.Move(oldIndex, index); }
            }
        }
        while (Conversations.Count > rows.Count) { Conversations.RemoveAt(Conversations.Count - 1); }
        if (SelectedConversation is not null && !Conversations.Contains(SelectedConversation)) { SelectedConversation = null; }
    }

    private void RefreshFriendRequests()
    {
        FriendRequests.Clear();
        foreach (var request in _services.Database.GetFriendRequests()) { FriendRequests.Add(request); }
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
}

public sealed class ConversationItem : ObservableObject
{
    private ConversationWithUnread _source;
    public ConversationItem(ConversationWithUnread source) { _source = source; }
    public string Uuid => _source.Conversation.Uuid;
    public string UuidPreview => Uuid[..Math.Min(12, Uuid.Length)] + "...";
    public string Alias => _source.Conversation.Alias;
    public string AvatarPath => _source.Conversation.AvatarPath;
    public string PeerPublicKey => _source.Conversation.PeerPublicKey;
    public int UnreadCount => _source.UnreadCount;
    public string Initial => string.IsNullOrWhiteSpace(Alias) ? "?" : Alias[..1].ToUpperInvariant();
    public void Update(ConversationWithUnread source)
    {
        if (_source == source) { return; }
        _source = source;
        OnPropertyChanged(nameof(Alias));
        OnPropertyChanged(nameof(AvatarPath));
        OnPropertyChanged(nameof(PeerPublicKey));
        OnPropertyChanged(nameof(UnreadCount));
        OnPropertyChanged(nameof(Initial));
    }
}

public sealed class MessageItem(ChatMessage source, string replyAuthor = "User")
{
    internal ChatMessage Source => source;
    public string MessageId { get; } = source.MessageId;
    public bool CanModify => IsMine && !IsRevoked && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - source.Timestamp is >= 0 and <= 120000;
    public bool CanReEdit => IsMine && IsRevoked && Extension == "txt" && !string.IsNullOrEmpty(Content);
    public bool IsRead => Source.IsRead;
    public bool IsMine { get; } = source.IsMine;
    public string Content { get; } = source.Content;
    public string Extension { get; } = source.Extension;
    public bool IsRevoked { get; } = source.IsRevoked;
    public bool IsEdited { get; } = source.IsEdited;
    public string? ReplyToId { get; } = source.ReplyToId;
    public string ReplyPreview { get; } = string.IsNullOrEmpty(source.ReplyToId) ? "" : $"{replyAuthor}: {source.ReplyToContent ?? ""}";
    public string DisplayContent => IsRevoked ? IsMine ? "You revoked a message" : "Peer revoked a message" : Extension == "txt" ? Content : $"[{Extension.ToUpperInvariant()}]  {Path.GetFileName(Content)}";
    public string QuoteContent => Extension == "txt" ? Content : $"[{Extension.ToUpperInvariant()}]";
    public string EditedText => IsEdited && !IsRevoked ? " (edited)" : "";
    public string TimeText => DateTimeOffset.FromUnixTimeMilliseconds(source.Timestamp).LocalDateTime.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.CurrentCulture);
    public bool IsDelivered => source.Status >= 2;
    public bool IsSending => IsMine && !IsRevoked && source.Status == 0;
    public bool IsAttachment => !IsRevoked && Extension != "txt";
    public string StatusText => !IsMine || IsRevoked ? "" : source.Status switch
    {
        0 => "",
        1 => "✓",
        2 => "✓",
        3 => "✓✓",
        _ => ""
    };
    public string StatusName => !IsMine || IsRevoked ? "" : source.Status switch
    {
        0 => "Sending", 1 => "Sent", 2 => "Delivered", 3 => "Read", _ => ""
    };
}
