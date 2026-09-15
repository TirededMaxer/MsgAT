using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows.Threading;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.ViewModels;

public sealed class MainViewModel : ObservableObject, IDisposable
{
    private const int PageSize = 100;
    private readonly AppServices _services;
    private readonly Dispatcher _dispatcher;
    private ConversationItem? _selectedConversation;
    private string _draft = "";
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
            OnPropertyChanged(nameof(HasConversation));
            LoadInitialMessages();
        }
    }

    public string Draft { get => _draft; set => SetProperty(ref _draft, value); }
    public string SyncText { get => _syncText; private set => SetProperty(ref _syncText, value); }
    public bool HasConversation => SelectedConversation is not null;
    public MessageItem? ReplyTarget { get => _replyTarget; private set { if (SetProperty(ref _replyTarget, value)) { OnPropertyChanged(nameof(HasReplyTarget)); OnPropertyChanged(nameof(ReplyPreview)); } } }
    public bool HasReplyTarget => ReplyTarget is not null;
    public string ReplyPreview => ReplyTarget?.QuoteContent ?? "";
    public bool HasOlderMessages { get => _hasOlderMessages; private set => SetProperty(ref _hasOlderMessages, value); }
    public SecureSettings Settings => _services.Settings.Snapshot;
    public string MyUuid => _services.Settings.MyUuid;
    public AppServices Services => _services;

    public async Task SendDraftAsync()
    {
        if (SelectedConversation is null || string.IsNullOrWhiteSpace(Draft)) { return; }
        var text = Draft;
        Draft = "";
        try
        {
            await _services.Messaging.SendTextAsync(
                SelectedConversation.Uuid,
                text,
                ReplyTarget?.MessageId,
                ReplyTarget is null ? null : ReplyTarget.IsMine ? MyUuid : SelectedConversation.Uuid,
                ReplyTarget?.QuoteContent);
            ReplyTarget = null;
        }
        catch (Exception exception) when (exception is InvalidOperationException or IOException)
        {
            Draft = text;
            ErrorRequested?.Invoke(this, exception.Message);
        }
    }

    public async Task SendMediaAsync(string path)
    {
        if (SelectedConversation is null) { return; }
        try
        {
            await _services.Messaging.SendMediaAsync(SelectedConversation.Uuid, path, ReplyTarget?.MessageId,
                ReplyTarget is null ? null : ReplyTarget.IsMine ? MyUuid : SelectedConversation.Uuid, ReplyTarget?.QuoteContent);
            ReplyTarget = null;
        }
        catch (Exception exception) when (exception is InvalidOperationException or IOException or ArgumentException)
        {
            ErrorRequested?.Invoke(this, exception.Message);
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
        if (SelectedConversation is null) { return; }
        await _services.Messaging.DeleteFriendAsync(SelectedConversation.Uuid);
        SelectedConversation = null;
    }

    public void ClearSelectedMessages()
    {
        if (SelectedConversation is not null) { _services.Messaging.ClearMessages(SelectedConversation.Uuid); }
    }

    public async Task RevokeAsync(MessageItem item)
    {
        if (SelectedConversation is null || !item.IsMine || item.IsRevoked) { return; }
        try { await _services.Messaging.RevokeMessageAsync(SelectedConversation.Uuid, item.MessageId); }
        catch (InvalidOperationException error) { ErrorRequested?.Invoke(this, error.Message); }
    }

    public void SetReply(MessageItem item) { if (!item.IsRevoked) { ReplyTarget = item; } }
    public void CancelReply() => ReplyTarget = null;

    public async Task EditAsync(MessageItem item, string newText)
    {
        if (SelectedConversation is null || !item.IsMine || item.IsRevoked || item.Extension != "txt") { return; }
        try { await _services.Messaging.EditMessageAsync(SelectedConversation.Uuid, item.MessageId, newText); }
        catch (InvalidOperationException error) { ErrorRequested?.Invoke(this, error.Message); }
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
        Messages.Clear();
        foreach (var message in _services.Database.GetAllMessages(SelectedConversation.Uuid)) Messages.Add(CreateMessageItem(message));
        HasOlderMessages = false;
        return Messages.FirstOrDefault(message => message.MessageId == messageId);
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
        _services.Database.MarkMessagesRead(SelectedConversation.Uuid);
        var lastIncoming = rows.LastOrDefault(static item => !item.IsMine);
        if (lastIncoming is not null)
        {
            _ = SendReadAckSafelyAsync(SelectedConversation.Uuid, lastIncoming.MessageId);
        }
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
        if (previousLast != Messages.LastOrDefault()?.MessageId) { LatestMessagesChanged?.Invoke(this, EventArgs.Empty); }
    }

    private async Task SendReadAckSafelyAsync(string peer, string messageId)
    {
        try { await _services.Messaging.SendReadAcknowledgementAsync(peer, messageId); }
        catch (InvalidOperationException) { }
    }

    public void MarkActiveConversationRead()
    {
        if (_disposed || _services.IsDisposed || SelectedConversation is null ||
            System.Windows.Application.Current.MainWindow is not { IsActive: true, IsVisible: true }) { return; }
        var unread = _services.Database.GetMessages(SelectedConversation.Uuid, PageSize)
            .LastOrDefault(message => !message.IsMine && !message.IsRead);
        if (unread is null) { return; }
        _services.Database.MarkMessagesRead(SelectedConversation.Uuid);
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
    public bool IsMine { get; } = source.IsMine;
    public string Content { get; } = source.Content;
    public string Extension { get; } = source.Extension;
    public bool IsRevoked { get; } = source.IsRevoked;
    public bool IsEdited { get; } = source.IsEdited;
    public string? ReplyToId { get; } = source.ReplyToId;
    public string ReplyPreview { get; } = string.IsNullOrEmpty(source.ReplyToId) ? "" : $"{replyAuthor}: {source.ReplyToContent ?? ""}";
    public string DisplayContent => IsRevoked ? "Message revoked" : Extension == "txt" ? Content : $"[{Extension.ToUpperInvariant()}]  {Path.GetFileName(Content)}";
    public string QuoteContent => Extension == "txt" ? Content : $"[{Extension.ToUpperInvariant()}]";
    public string EditedText => IsEdited && !IsRevoked ? " · edited" : "";
    public string TimeText => DateTimeOffset.FromUnixTimeMilliseconds(source.Timestamp).LocalDateTime.ToString("HH:mm", CultureInfo.CurrentCulture);
    public bool IsDelivered => source.Status >= 2;
    public bool IsAttachment => !IsRevoked && Extension != "txt";
    public string StatusText => !IsMine || IsRevoked ? "" : source.Status switch
    {
        0 => "○",
        1 => "✓",
        2 => "✓",
        3 => "✓✓",
        _ => ""
    };
}
