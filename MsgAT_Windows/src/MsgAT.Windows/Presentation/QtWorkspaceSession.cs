// SPDX-License-Identifier: AGPL-3.0-only
using System.Collections.Specialized;
using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows.Threading;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Presentation;

[System.Diagnostics.CodeAnalysis.SuppressMessage("Design", "CA1001:Types that own disposable fields should be disposable",
    Justification = "Run and Close release the preview on the dispatcher before the native window loses ownership.")]
internal sealed partial class QtWorkspaceSession(MainViewModel model, Func<bool> isLocked,
    Action<ConversationItem> choose, Func<string, bool> copyText, Dispatcher dispatcher,
    Action ready, Action closeRequested, Action presenceChanged, Func<bool, Task> startCall,
    Func<string, nint, Task> openWorkspaceCommand, Func<bool> confirmDiscardEdit, Func<MessageItem, Task> retrySend,
    Action<MessageItem, nint> openCard, Func<QtConversationAction, Task> changeConversation,
    Func<QtMessageCollectionAction, Task> changeMessages)
{
    private const string Bridge = "msgat_desktop_ui";
    // The self-extracting bundle can set AppContext.BaseDirectory to its extraction directory.
    // The separately deployed Qt runtime lives beside the executable.
    private static readonly string RuntimeDirectory = Path.Combine(
        Path.GetDirectoryName(Environment.ProcessPath) ?? AppContext.BaseDirectory, "qt");
    private static readonly Lazy<nint> BridgeHandle = new(LoadBridge);
    private readonly HashSet<INotifyPropertyChanged> _observed = [];
    private readonly QtWorkspaceFrame _frames = new();
    private readonly MainViewModel _model = model;
    private readonly DispatcherSynchronizationContext _ownerContext = new(dispatcher);
    private readonly Dispatcher _dispatcher = dispatcher;
    private readonly Func<bool> _isLocked = isLocked;
    private readonly DispatcherTimer _searchRefresh = new(DispatcherPriority.Background, dispatcher)
        { Interval = TimeSpan.FromMilliseconds(250) };
    private readonly DispatcherTimer _readTimer = new(DispatcherPriority.Background, dispatcher);
    private readonly Dictionary<string, long> _readSince = new(StringComparer.Ordinal);
    private readonly HashSet<string> _readSubmitted = new(StringComparer.Ordinal);
    private readonly HashSet<string> _readRetried = new(StringComparer.Ordinal);
    private (string Peer, long Version)? _readScope;
    private nint _view;
    private long _visibilityEpoch;
    private long _externalReveal;
    internal nint HostWindow => _view == 0 ? 0 : GetWorkspaceWindow(_view);
    private volatile bool _active;
    private volatile bool _callActive;
    internal bool IsOpen => _view != 0;
    internal bool IsActive => _active;
    internal bool IsForeground => _active || _callActive;
    private DispatcherOperation? _update;
    private bool _fullUpdate;
    private bool _presentUpdates;
    private bool _observeRowsNeeded = true;
    private int _chatTextPercentage = 100;
    private bool _appLockEnabled;
    private (string Peer, long Version, ChatHistoryPosition Position)? _historyPosition;
    private sealed record SearchInput(long Generation, string? Origin, string Query, string? Peer,
        MessageSearchKind Kind, DateTime? Day);
    private readonly Dictionary<string, MessageSearchHit> _searchHits = new(StringComparer.Ordinal);
    private SearchInput? _searchInput;
    private MessageSearchCursor? _searchCursor;
    private Task _searchWork = Task.CompletedTask;

    static QtWorkspaceSession() => NativeLibrary.SetDllImportResolver(typeof(QtWorkspaceSession).Assembly, ResolveLibrary);

    private static nint ResolveLibrary(string name, Assembly assembly, DllImportSearchPath? searchPath) =>
        name == Bridge ? BridgeHandle.Value : 0;

    private static nint LoadBridge()
    {
        const uint dllDirectory = 0x00000100;
        const uint system32 = 0x00000800;
        var library = Path.Combine(RuntimeDirectory, Bridge + ".dll");
        var handle = LoadLibrary(library, 0, dllDirectory | system32);
        if (handle == 0) throw new Win32Exception(Marshal.GetLastPInvokeError());
        return handle;
    }

    [LibraryImport("kernel32.dll", EntryPoint = "LoadLibraryExW", SetLastError = true, StringMarshalling = StringMarshalling.Utf16)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static partial nint LoadLibrary(string path, nint reserved, uint flags);

    [LibraryImport(Bridge, EntryPoint = "msgat_workspace_run", StringMarshalling = StringMarshalling.Utf16)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.SafeDirectories)]
    private static unsafe partial int RunWindow(nint owner,
        delegate* unmanaged[Cdecl]<nint, nint, int, nint, int, int> notify, string runtimeDirectory, string geometry, int sidebarWidth);

    [LibraryImport("msgat_desktop_ui", EntryPoint = "msgat_workspace_present")]
    [DefaultDllImportSearchPaths(DllImportSearchPath.SafeDirectories)]
    private static unsafe partial void Present(nint view, byte* bytes, int length, int surface);

    [LibraryImport("msgat_desktop_ui", EntryPoint = "msgat_workspace_close")]
    [DefaultDllImportSearchPaths(DllImportSearchPath.SafeDirectories)]
    private static partial void CloseWindow(nint view);

    [LibraryImport(Bridge, EntryPoint = "msgat_workspace_show")]
    [DefaultDllImportSearchPaths(DllImportSearchPath.SafeDirectories)]
    private static partial void ShowWindow(nint view, int visible);

    [LibraryImport(Bridge, EntryPoint = "msgat_workspace_sample_viewport")]
    [DefaultDllImportSearchPaths(DllImportSearchPath.SafeDirectories)]
    private static partial void SampleViewport(nint view);

    [LibraryImport(Bridge, EntryPoint = "msgat_workspace_window")]
    [DefaultDllImportSearchPaths(DllImportSearchPath.SafeDirectories)]
    private static partial nint GetWorkspaceWindow(nint view);

    [LibraryImport(Bridge, EntryPoint = "msgat_workspace_geometry")]
    [DefaultDllImportSearchPaths(DllImportSearchPath.SafeDirectories)]
    private static unsafe partial int ReadGeometry(nint view, byte* output, int capacity, out int sidebarWidth);

    internal unsafe (string Geometry, int SidebarWidth)? CaptureWindowPlacement()
    {
        _dispatcher.VerifyAccess();
        if (_view == 0) return null;
        Span<byte> data = stackalloc byte[512];
        fixed (byte* buffer = data)
        {
            var length = ReadGeometry(_view, buffer, data.Length, out var sidebarWidth);
            return length > 0 && length <= data.Length
                ? (System.Text.Encoding.ASCII.GetString(data[..length]), sidebarWidth) : null;
        }
    }

    internal unsafe void Run(string? geometry, int sidebarWidth)
    {
        _dispatcher.VerifyAccess();
        var owner = GCHandle.Alloc(this);
        var previousHistoryPosition = _model.CaptureHistoryPosition;
        _model.CaptureHistoryPosition = CaptureHistoryPosition;
        _model.PropertyChanged += Changed;
        WindowAppearance.Changed += AppearanceChanged;
        _model.MessagesRefreshed += SearchSourceChanged;
        _searchRefresh.Tick += RefreshSearch;
        _readTimer.Tick += RecheckViewport;
        _model.VisibleConversations.CollectionChanged += RowsChanged;
        _model.Messages.CollectionChanged += RowsChanged;
        try
        {
            if (RunWindow(GCHandle.ToIntPtr(owner), &Notify, RuntimeDirectory, geometry ?? "", sidebarWidth) != 0)
                throw new InvalidOperationException("The Qt workspace could not start.");
        }
        finally
        {
            _view = 0;
            _presentUpdates = false;
            SetActive(false);
            ClearSearch();
            ClearAttachmentReview();
            ClearAttachmentPreview();
            ClearCall();
            ClearSettings();
            ClearVisibleReads();
            _historyPosition = null;
            _frames.Clear();
            _model.CaptureHistoryPosition = previousHistoryPosition;
            _update?.Abort();
            _update = null;
            _model.PropertyChanged -= Changed;
            WindowAppearance.Changed -= AppearanceChanged;
            _model.MessagesRefreshed -= SearchSourceChanged;
            _searchRefresh.Tick -= RefreshSearch;
            _readTimer.Tick -= RecheckViewport;
            _model.VisibleConversations.CollectionChanged -= RowsChanged;
            _model.Messages.CollectionChanged -= RowsChanged;
            foreach (var item in _observed) item.PropertyChanged -= Changed;
            _observed.Clear();
            owner.Free();
        }
    }

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvCdecl)])]
    private static unsafe int Notify(nint owner, nint view, int kind, nint bytes, int length)
    {
        var session = (QtWorkspaceSession)GCHandle.FromIntPtr(owner).Target!;
        var previousContext = SynchronizationContext.Current;
        SynchronizationContext.SetSynchronizationContext(session._ownerContext);
        try
        {
            switch (kind)
            {
                case 0:
                    session._view = view;
                    session._presentUpdates = true;
                    session.Refresh();
                    session.WindowReady();
                    break;
                case 1:
                    if (!session.IsVisible(view) || length <= 0) return 0;
                    var id = Marshal.PtrToStringUTF8(bytes, length);
                    session.ChooseConversation(id);
                    break;
                case 2:
                    session.ClearCall();
                    session.ClearSettings();
                    session._view = 0;
                    session._presentUpdates = false;
                    session.ClearAttachmentPreview();
                    session.SetActive(false);
                    break;
                case 3 or 4 or 5 or 10 or 11 or 12 or 13 or 14 or >= 18 and <= 34 or 36 or 37 or 38:
                    if (!session.IsVisible(view) || bytes == 0 || length <= 0) return 0;
                    var reader = new Utf8JsonReader(new ReadOnlySpan<byte>((void*)bytes, length));
                    using (var payload = JsonDocument.ParseValue(ref reader))
                        return (kind switch
                        {
                            10 => session.HandleHistoryViewport(payload.RootElement),
                            38 => session.ChangeLinkPreview(payload.RootElement),
                            37 => session.FormatEditor(payload.RootElement),
                            36 => session.HandleSettings(payload.RootElement),
                            34 => session.QueueConversationAction(payload.RootElement),
                            33 => session.HandleMessageAction(payload.RootElement),
                            32 => session.QueueWorkspaceCommand(payload.RootElement),
                            31 => session.QueueCall(payload.RootElement),
                            29 or 30 => session.HandleAttachment(kind, payload.RootElement),
                            >= 22 => session.HandleAttachmentPreview(kind, payload.RootElement),
                            >= 18 => session.HandleAttachment(kind, payload.RootElement),
                            >= 11 => session.HandleSearch(kind, payload.RootElement),
                            _ => session.HandleEditor(kind, payload.RootElement)
                        }) ? 1 : 0;
                case 6 or 7 or 8 or 9:
                    if (!session.IsVisible(view) || bytes == 0 || length <= 0) return 0;
                    return session.HandleHistory(kind, Marshal.PtrToStringUTF8(bytes, length)) ? 1 : 0;
                case 15:
                    session.WindowCloseRequested(view);
                    break;
                case 16:
                    if (session._view == view) session.SetActive((length & 1) != 0, (length & 2) != 0);
                    break;
                case 17:
                    if (!session.IsVisible(view) || bytes == 0 || length <= 0) return 0;
                    session.ChooseFolder(Marshal.PtrToStringUTF8(bytes, length));
                    break;
                case 35:
                    if (session._view != view || session._isLocked() || bytes == 0 || length <= 0) return 0;
                    var callReader = new Utf8JsonReader(new ReadOnlySpan<byte>((void*)bytes, length));
                    using (var callPayload = JsonDocument.ParseValue(ref callReader))
                        return session.HandleCallAction(callPayload.RootElement) ? 1 : 0;
            }
        }
        catch (Exception error)
        {
            // A managed exception cannot unwind through Qt's dispatch loop.
            session.ClearAttachmentPreview();
            session.ClearCall();
            session.ClearSettings();
            Present(view, null, 0, 0);
            Trace.TraceError("Windows workspace event failed ({0}).", error.GetType().Name);
        }
        finally { SynchronizationContext.SetSynchronizationContext(previousContext); }
        return 0;
    }

    private bool HandleEditor(int kind, JsonElement payload)
    {
        var peer = payload.GetProperty("peer").GetString();
        var text = payload.GetProperty("text").GetString();
        if (peer is null || text is null || peer != _model.SelectedConversation?.Uuid ||
            !_model.IsChatContentVisible) return false;
        if (kind == 5) return copyText(text);
        var target = payload.GetProperty("target").GetString();
        if (target != (_model.EditTargetId ?? "")) return false;
        if (_model.HasMessageEdit)
        {
            if (!_model.CanEditText) return false;
            if (kind == 3) { _model.EditText = text; return true; }
            if (!_model.CanSubmitEdit || _model.EditText != text) return false;
            TrackMessageAction(_model.SubmitMessageEditAsync());
            return true;
        }
        if (!_model.CanCompose) return false;
        if (kind == 3) { _model.Draft = text; return true; }
        if (_model.Draft != text || !_model.HasDraft || _model.TextSendPending) return false;
        TrackMessageAction(_model.SendDraftAsync());
        return true;
    }

    private bool IsVisible(nint view) => _view == view && _presentUpdates && !_isLocked() && _model.IsWorkspaceReady;

    private bool ChangeLinkPreview(JsonElement payload)
    {
        if (!_model.IsChatContentVisible || !_model.CanCompose || _model.HasMessageEdit || _model.IsSelecting ||
            !_model.HasPreviewLinks || payload.GetProperty("peer").GetString() != _model.SelectedConversation?.Uuid ||
            payload.GetProperty("text").GetString() != _model.Draft) return false;
        switch (payload.GetProperty("command").GetString())
        {
            case "toggle": _model.PreviewEnabled = !_model.PreviewEnabled; break;
            case "choose" when payload.GetProperty("value").GetString() is { } url: _model.SelectWebPreview(url); break;
            case "retry": _model.RetryWebPreview(); break;
            case "remove": _model.RemoveWebPreview(); break;
            case "placement": _model.ToggleWebPreviewPlacement(); break;
            case "resize": _model.ToggleWebPreviewImageSize(); break;
            default: return false;
        }
        QueueRefresh(editorOnly: true);
        return true;
    }

    private unsafe bool FormatEditor(JsonElement payload)
    {
        var editing = _model.HasMessageEdit;
        var text = editing ? _model.EditText : _model.Draft;
        if (!_model.IsChatContentVisible || _model.IsSelecting ||
            payload.GetProperty("peer").GetString() != _model.SelectedConversation?.Uuid ||
            payload.GetProperty("target").GetString() != (_model.EditTargetId ?? "") ||
            payload.GetProperty("text").GetString() != text || !(editing ? _model.CanEditText : _model.CanCompose)) return false;

        try
        {
            var result = MsgAT.Windows.Core.Crypto.SecurityCore.Call(new
            {
                operation = "format_message_selection", text,
                start = payload.GetProperty("start").GetInt32(), end = payload.GetProperty("end").GetInt32(),
                kind = payload.GetProperty("format").GetString()
            });
            var formatted = result.GetProperty("text").GetString()!;
            if (editing) _model.EditText = formatted;
            else _model.Draft = formatted;
            var frame = QtWorkspaceFrame.CaptureEditor(_model, (result.GetProperty("start").GetInt32(), result.GetProperty("end").GetInt32()));
            try { fixed (byte* data = frame) Present(_view, data, frame.Length, 1); }
            finally { CryptographicOperations.ZeroMemory(frame); }
            return true;
        }
        catch (InvalidOperationException)
        {
            ShowNotice(AppStrings.Get("UiFormatUnavailable"));
            return false;
        }
    }

    private bool QueueConversationAction(JsonElement payload)
    {
        if (payload.GetProperty("command").GetString() is not { } command) return false;
        var action = command == "undoArchive"
            ? new QtConversationAction(command, Version: payload.GetProperty("version").GetInt32())
            : new QtConversationAction(command, payload.GetProperty("peer").GetString(),
                payload.GetProperty("folder").GetString(), payload.GetProperty("target").GetString());
        var epoch = _visibilityEpoch;
        _ownerContext.Post(_ =>
        {
            if (_view != 0 && IsVisible(_view) && epoch == _visibilityEpoch)
                TrackMessageAction(changeConversation(action));
        }, null);
        return true;
    }

    private bool HandleMessageAction(JsonElement payload)
    {
        var peer = payload.GetProperty("peer").GetString();
        if (peer is null || _model.SelectedConversation?.Uuid != peer || !_model.IsChatContentVisible) return false;
        var command = payload.GetProperty("command").GetString();
        var id = payload.GetProperty("id").GetString();
        if (command is "toggleSelection" or "clearSelection" or "copySelected" or "save" or "forward" or "delete" or "cancelSend" or "pin" or "quoteSelection")
            return HandleSelectionAction(command, peer, id, payload.GetProperty("extending").GetBoolean());
        if (_model.IsSelecting) return false;
        if (command == "sendSilently" && _model.CanCompose && _model.HasDraft && !_model.TextSendPending)
        {
            TrackMessageAction(_model.SendDraftAsync(_model.SendOptions with { Silent = true }));
            return true;
        }
        if (command == "clearDelivery" && _model.CanCompose && _model.HasSendOptions)
        {
            _model.SetSendOptions(new());
            return true;
        }
        if (command == "cancelReply" && _model.HasReplyTarget)
        {
            _model.CancelReply();
            return true;
        }
        if (command == "cancelEdit" && id is not null && id == _model.EditTargetId && _model.CanCancelEdit)
        {
            var epoch = _visibilityEpoch;
            _ownerContext.Post(_ =>
            {
                if (epoch == _visibilityEpoch && _view != 0 && IsVisible(_view) && _model.SelectedConversation?.Uuid == peer &&
                    _model.EditTargetId == id && _model.CanCancelEdit && confirmDiscardEdit()) _model.CancelMessageEdit();
            }, null);
            return true;
        }
        var item = _model.Messages.FirstOrDefault(row => row.MessageId == id && !row.IsRevoked && row.Source.ConversationUuid == peer);
        if (item is null || item.Source.ExpiresAt is { } expiry && expiry <= DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()) return false;
        if (command == "jumpQuote" && item.ReplyToId is { } original)
        {
            RevealConfirmedMessage(peer, original, "UiQuotedMessageUnavailable", includeRevoked: true);
            return true;
        }
        if (command == "openLink" && payload.GetProperty("value").GetString() is { } url &&
            _frames.MessageLinks(item, AppStrings.Get("UiHiddenMessageText")).Contains(url, StringComparer.Ordinal) &&
            MessageLinks.Parse(url) is { } uri)
            return MessageLinks.Open(uri, () => ShowNotice(AppStrings.Get("UiLinkOpenFailed")));
        if (command == "reply" && _model.CanCompose)
        {
            _model.SetReply(item);
            return _model.ReplyTarget?.MessageId == id;
        }
        if ((command is "editMessage" or "editCaption") && _model.CanCompose && item.CanEditContent)
        {
            _model.BeginMessageEdit(item);
            return _model.EditTargetId == id;
        }
        if (command == "retry" && item.CanRetrySend && !item.IsRetryingSend)
        {
            TrackMessageAction(retrySend(item));
            return true;
        }
        if (command == "revoke" && item.CanModify)
        {
            TrackMessageAction(_model.RevokeAsync(item));
            return true;
        }
        if (command == "openCard" && (item.IsContactCard || item.IsLocation || item.IsLiveLocation || item.LinkPreview is not null))
        {
            var epoch = _visibilityEpoch;
            _ownerContext.Post(_ =>
            {
                if (epoch != _visibilityEpoch || _view == 0 || !IsVisible(_view) || _model.IsSelecting ||
                    !_model.IsChatContentVisible || _model.SelectedConversation?.Uuid != peer) return;
                if (_model.Messages.FirstOrDefault(row => row.MessageId == id && !row.IsRevoked &&
                    row.Source.ConversationUuid == peer) is { } current &&
                    (current.Source.ExpiresAt is not { } expires || expires > DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()))
                    openCard(current, HostWindow);
            }, null);
            return true;
        }
        return command == "copy" && item.IsText && copyText(_frames.MessageText(item, item.Content, AppStrings.Get("UiHiddenMessageText")));
    }

    private bool HandleSelectionAction(string command, string peer, string? id, bool extending)
    {
        if (!_model.CanChangeSelection) return false;
        if (command == "clearSelection")
        {
            if (!_model.IsSelecting) return false;
            _model.ClearSelection();
            return true;
        }

        var epoch = _visibilityEpoch;
        var version = _model.SelectionVersion;
        bool IsCurrent() => _view != 0 && IsVisible(_view) && epoch == _visibilityEpoch &&
            _model.IsChatContentVisible && _model.SelectedConversation?.Uuid == peer && version == _model.SelectionVersion && _model.CanChangeSelection;
        var item = string.IsNullOrEmpty(id) ? null : _model.Messages.FirstOrDefault(row => row.MessageId == id && row.Source.ConversationUuid == peer);
        if (command == "toggleSelection")
        {
            if (item is null || !MainViewModel.CanSelectMessage(item)) return false;
            if (!_model.HasMessageEdit) _model.ToggleSelection(item, extending);
            else
            {
                var edit = _model.EditTargetId;
                _ownerContext.Post(_ =>
                {
                    if (IsCurrent() && edit == _model.EditTargetId && _model.CanCancelEdit && confirmDiscardEdit() &&
                        IsCurrent() && _model.Messages.Contains(item)) _model.ToggleSelection(item, extending);
                }, null);
            }
            return true;
        }

        var fromSelection = string.IsNullOrEmpty(id);
        if (fromSelection && !_model.CanUseSelection || !fromSelection && (_model.IsSelecting || item is null)) return false;
        if (command is "save" or "forward")
        {
            if (fromSelection ? !_model.CanKeepSelection : item is null || !MainViewModel.CanSelectMessage(item) || item.HasExpiration)
                return false;
        }
        if (command == "delete" && item is not null && (item.IsSending || item.IsLiveLocation)) return false;
        if (command == "cancelSend" && item is not { IsSending: true }) return false;
        if (command == "pin" && item is not { IsRevoked: false, IsSending: false }) return false;
        if (command == "quoteSelection" && (!_model.CanCompose || item is not { IsRevoked: false, IsText: true })) return false;
        var action = new QtMessageCollectionAction(command, peer, fromSelection ? _model.SelectedMessageIds : [id!], version, fromSelection);
        _ownerContext.Post(_ =>
        {
            if (IsCurrent() && (item is null || _model.Messages.Contains(item))) TrackMessageAction(changeMessages(action));
        }, null);
        return true;
    }

    internal void TrackMessageAction(Task operation)
    {
        var previous = _model.WorkspaceMessageActionCompletion;
        _model.WorkspaceMessageActionCompletion = previous.IsCompletedSuccessfully ? operation : Task.WhenAll(previous, operation);
    }

    private bool QueueCall(JsonElement payload)
    {
        var peer = payload.GetProperty("peer").GetString();
        if (peer is null || !CanStartCall(peer)) return false;
        var video = payload.GetProperty("video").GetBoolean();
        _ownerContext.Post(async _ =>
        {
            if (!CanStartCall(peer)) return;
            try { await startCall(video); }
            catch (Exception error) { Trace.TraceWarning("Qt call failed ({0}).", error.GetType().Name); }
        }, null);
        return true;
    }

    private bool CanStartCall(string peer) => _view != 0 && IsVisible(_view) && _model.IsChatContentVisible &&
        !_model.IsSelecting && _model.SelectedConversation?.Uuid == peer;

    private bool QueueWorkspaceCommand(JsonElement payload)
    {
        var command = payload.GetProperty("command").GetString();
        if (command is null || ReadWindowHandle(payload) is not { } owner) return false;
        var composing = command is "record" or "sendOptions" or "scheduleDraft" or "shareContact" or "shareLocation" or "pasteImage" or "stickers";
        var chatScoped = composing || command is "sharedMedia" or "pinnedMessages" or "chatQueue" or "historyNavigation";
        if (!chatScoped && command is not ("contacts" or "addFriend" or "requests" or "settings" or "manageFolders" or
            "appLock" or "lockNow" or "saved" or "sendQueue" or "forwardQueue" or "callHistory" or "stickerLibrary")) return false;
        var peer = payload.GetProperty("peer").GetString();
        var epoch = _visibilityEpoch;
        _ownerContext.Post(async _ =>
        {
            if (_view == 0 || !IsVisible(_view) || epoch != _visibilityEpoch) return;
            if (chatScoped && (!_model.IsChatContentVisible || _model.IsSelecting ||
                string.IsNullOrEmpty(peer) || peer != _model.SelectedConversation?.Uuid || composing && !_model.CanCompose)) return;
            try { await openWorkspaceCommand(command, owner); }
            catch (Exception error) { Trace.TraceWarning("Qt workspace command failed ({0}).", error.GetType().Name); }
        }, null);
        return true;
    }

    private void WindowReady() => ready();

    private void WindowCloseRequested(nint view)
    {
        if (_view == view) closeRequested();
    }

    private void SetActive(bool active, bool callActive = false)
    {
        if (_active == active && _callActive == callActive) return;
        _active = active;
        _callActive = callActive;
        if (!active) ClearVisibleReads();
        presenceChanged();
    }

    private ChatHistoryPosition? CaptureHistoryPosition() =>
        _historyPosition is { } value && value.Peer == _model.SelectedConversation?.Uuid &&
        value.Version == _model.HistoryNavigationVersion &&
        _model.Messages.Any(row => !row.IsRevoked && row.MessageId == value.Position.MessageId)
            ? value.Position : null;

    private bool HandleHistoryViewport(JsonElement payload)
    {
        var peer = payload.GetProperty("peer").GetString();
        var version = payload.GetProperty("generation").GetInt64();
        if (peer is null || peer != _model.SelectedConversation?.Uuid || !_model.IsChatContentVisible ||
            version != _model.HistoryNavigationVersion || _model.InitialHistoryLoading) return false;
        if (!payload.GetProperty("shown").GetBoolean())
        {
            ClearVisibleReads();
            return true;
        }
        var latest = payload.GetProperty("latest").GetBoolean();
        var anchor = payload.GetProperty("anchor").GetString();
        _historyPosition = latest || string.IsNullOrEmpty(anchor) ? null :
            (peer, version, new ChatHistoryPosition(anchor, payload.GetProperty("offset").GetDouble()));
        if (_model.IsViewingLatest != latest) _model.IsViewingLatest = latest;
        TrackVisibleReads(peer, version, payload.GetProperty("readable"));
        return true;
    }

    private void ClearVisibleReads()
    {
        _readTimer.Stop();
        _readScope = null;
        _readSince.Clear();
        _readSubmitted.Clear();
        _readRetried.Clear();
    }

    private void RecheckViewport(object? sender, EventArgs args)
    {
        _readTimer.Stop();
        if (_view != 0 && IsActive && IsVisible(_view)) SampleViewport(_view);
    }

    private bool CanMarkVisibleReads(string peer, long version) =>
        _view != 0 && IsActive && IsVisible(_view) && _model.IsChatContentVisible &&
        _readScope == (peer, version) && _model.SelectedConversation?.Uuid == peer &&
        _model.HistoryNavigationVersion == version && !_model.InitialHistoryLoading;

    private void TrackVisibleReads(string peer, long version, JsonElement ids)
    {
        if (_readScope != (peer, version))
        {
            ClearVisibleReads();
            _readScope = (peer, version);
        }
        if (!CanMarkVisibleReads(peer, version)) { ClearVisibleReads(); return; }
        var visible = ids.EnumerateArray().Select(item => item.GetString()).OfType<string>().ToHashSet(StringComparer.Ordinal);
        foreach (var id in _readSince.Keys.Where(id => !visible.Contains(id)).ToArray()) _readSince.Remove(id);
        _readSubmitted.IntersectWith(visible);
        _readRetried.IntersectWith(visible);

        var now = Environment.TickCount64;
        var due = new Dictionary<string, long>(StringComparer.Ordinal);
        long? delay = null;
        foreach (var id in visible)
        {
            if (_readSubmitted.Contains(id)) continue;
            _readSince.TryAdd(id, now);
            var since = _readSince[id];
            var remaining = 150 - (now - since);
            if (remaining > 0) delay = Math.Min(delay ?? remaining, remaining);
            else
            {
                due[id] = since;
                _readSubmitted.Add(id);
            }
        }
        _readTimer.Stop();
        if (delay is { } wait)
        {
            _readTimer.Interval = TimeSpan.FromMilliseconds(wait);
            _readTimer.Start();
        }
        if (due.Count != 0) _ = MarkVisibleAsync(peer, version, due);
    }

    private async Task MarkVisibleAsync(string peer, long version, Dictionary<string, long> due)
    {
        if (await _model.MarkVisibleMessagesReadAsync(due.Keys, () => CanMarkVisibleReads(peer, version)) ||
            !CanMarkVisibleReads(peer, version)) return;
        var now = Environment.TickCount64;
        var retry = false;
        foreach (var (id, since) in due)
        {
            if (!_readSince.TryGetValue(id, out var current) || current != since || !_readRetried.Add(id)) continue;
            _readSince[id] = now + 350;
            _readSubmitted.Remove(id);
            retry = true;
        }
        if (retry && !_readTimer.IsEnabled)
        {
            _readTimer.Interval = TimeSpan.FromMilliseconds(500);
            _readTimer.Start();
        }
    }

    private bool HandleHistory(int kind, string? peer)
    {
        if (peer is null || peer != _model.SelectedConversation?.Uuid || !_model.IsChatContentVisible ||
            _model.InitialHistoryLoading || _model.HistoryPageLoading) return false;
        switch (kind)
        {
            case 6: RequestHistoryPage(newer: false); break;
            case 7: RequestHistoryPage(newer: true); break;
            case 8: _model.LoadLatestMessages(QueueFullRefresh); break;
            case 9: _model.RetryHistoryLoad(); break;
        }
        return true;
    }

    private void RequestHistoryPage(bool newer)
    {
        if (_view == 0 || _isLocked()) return;
        _ = _model.LoadHistoryPageAsync(newer ? HistoryPage.Newer : HistoryPage.Older, () => RequestHistoryPage(newer));
    }

    private void QueueFullRefresh() => QueueRefresh();

    private void ClearSearch()
    {
        _searchRefresh.Stop();
        _searchInput = null;
        _searchCursor = null;
        _searchHits.Clear();
    }

    private bool HandleSearch(int action, JsonElement payload)
    {
        if (action == 14) { ClearSearch(); return true; }
        if (!_model.IsChatContentVisible || _model.IsSelecting || _model.HasMessageEdit) return false;
        var generation = payload.GetProperty("generation").GetInt64();
        var origin = payload.GetProperty("peer").GetString();
        if (string.IsNullOrEmpty(origin)) origin = null;
        if (_model.SelectedConversation?.Uuid != origin) return false;
        if (action == 11)
        {
            ClearSearch();
            var query = payload.GetProperty("query").GetString() ?? "";
            var kind = payload.GetProperty("type").GetInt32() switch
            {
                1 => MessageSearchKind.Text,
                2 => MessageSearchKind.Attachments,
                _ => MessageSearchKind.All
            };
            DateTime? day = payload.TryGetProperty("day", out var date) &&
                DateTime.TryParseExact(date.GetString(), "yyyy-MM-dd", CultureInfo.InvariantCulture,
                    DateTimeStyles.None, out var parsed) ? parsed : null;
            var peer = payload.GetProperty("all").GetBoolean() ? null : origin;
            var criteria = new SearchInput(generation, origin, query, peer, kind, day);
            _searchInput = criteria;
            if (string.IsNullOrWhiteSpace(query))
                PresentSearch(criteria, [], replace: true);
            else QueueSearch(criteria, append: false);
            return true;
        }
        if (_searchInput is not { } current || current.Generation != generation || current.Origin != origin)
            return false;
        if (action == 12 && _searchCursor is { } cursor)
        {
            QueueSearch(current, append: true, cursor);
            return true;
        }
        if (action == 13 && payload.GetProperty("id").GetString() is { } id && _searchHits.TryGetValue(id, out var hit))
        {
            _searchWork = OpenAsync(_searchWork);
            _model.WorkspaceSearchCompletion = _searchWork;
            return true;

            async Task OpenAsync(Task previous)
            {
                await previous;
                if (!SearchIsCurrent(current)) return;
                try
                {
                    var opened = await _model.RevealWorkspaceMessageAsync(hit.Peer, hit.MessageId,
                        () => SearchIsCurrent(current, hit.Peer), choose);
                    if (!SearchIsCurrent(current, hit.Peer)) return;
                    PresentSearch(current, [], opened: opened, status: opened ? "" : AppStrings.Get("UiSearchTargetUnavailable"));
                    if (opened) QueueFullRefresh();
                }
                catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
                {
                    if (SearchIsCurrent(current, hit.Peer))
                        PresentSearch(current, [], status: AppStrings.Get("UiSearchTargetUnavailable"));
                }
            }
        }
        return false;
    }

    private bool SearchIsCurrent(SearchInput input, string? destination = null)
    {
        var selected = _model.SelectedConversation?.Uuid;
        return _view != 0 && IsVisible(_view) && _model.IsChatContentVisible && !_model.IsSelecting &&
            !_model.HasMessageEdit && ReferenceEquals(_searchInput, input) &&
            (selected == input.Origin || destination is not null && selected == destination);
    }

    private void QueueSearch(SearchInput input, bool append, MessageSearchCursor? before = null)
    {
        PresentSearch(input, [], busy: true, status: AppStrings.Get("UiSearchSearching"));
        _searchWork = ReadAsync(_searchWork);
        _model.WorkspaceSearchCompletion = _searchWork;

        async Task ReadAsync(Task previous)
        {
            await previous;
            if (!SearchIsCurrent(input)) return;
            try
            {
                var limit = append ? 50 : Math.Max(50, _searchHits.Count);
                var page = await Task.Run(() => _model.SearchMessages(input.Query, input.Peer, before, input.Kind, input.Day, limit));
                if (!SearchIsCurrent(input)) return;
                if (!append) _searchHits.Clear();
                var rows = page.Hits.Where(hit => _searchHits.TryAdd(hit.MessageId, hit)).ToArray();
                _searchCursor = page.Next;
                var status = _searchHits.Count == 0 ? AppStrings.Get("UiSearchNoResults") :
                    AppStrings.Format("UiSearchResultsCount", _searchHits.Count);
                PresentSearch(input, rows, replace: !append, status: status);
            }
            catch (Exception error) when (error is ArgumentException or IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
            {
                if (SearchIsCurrent(input)) PresentSearch(input, [], failed: true, retryAction: append ? 12 : 11,
                    status: AppStrings.Get("UiSearchFailed"));
            }
        }
    }

    private void SearchSourceChanged(object? sender, EventArgs args)
    {
        if (_searchInput is not { } input || string.IsNullOrWhiteSpace(input.Query)) return;
        _searchRefresh.Stop();
        _searchRefresh.Start();
    }

    private void RefreshSearch(object? sender, EventArgs args)
    {
        if (!_searchWork.IsCompleted) return;
        _searchRefresh.Stop();
        if (_searchInput is { } input && SearchIsCurrent(input)) QueueSearch(input, append: false);
    }

    private unsafe void PresentSearch(SearchInput input, IReadOnlyList<MessageSearchHit> hits, bool replace = false,
        bool busy = false, bool failed = false, int retryAction = 11, string status = "", bool opened = false)
    {
        var frame = JsonSerializer.SerializeToUtf8Bytes(new
        {
            generation = input.Generation, replace, busy, failed, retryAction, status, opened, more = _searchCursor is not null,
            rows = hits.Select(hit => new
            {
                id = hit.MessageId,
                text = $"{hit.Alias} · {DateTimeOffset.FromUnixTimeMilliseconds(hit.Timestamp).ToLocalTime():g}\n{hit.Preview}"
            })
        });
        try { fixed (byte* bytes = frame) Present(_view, bytes, frame.Length, 2); }
        finally { CryptographicOperations.ZeroMemory(frame); }
    }

    private void ChooseConversation(string? id)
    {
        ++_externalReveal;
        var epoch = _visibilityEpoch;
        _ownerContext.Post(_ =>
        {
            if (epoch != _visibilityEpoch || _view == 0 || !IsVisible(_view)) return;
            if (_model.VisibleConversations.FirstOrDefault(row => row.Uuid == id) is { } item)
                choose(item);
            QueueRefresh();
        }, null);
    }

    private void ChooseFolder(string? key)
    {
        ++_externalReveal;
        if (key is not null && _model.CanChangeSelection &&
            (_model.FolderChoices.Any(folder => folder.Key == key) || key == _model.ArchiveEntry.Key && _model.ShowArchiveEntry))
            _model.FolderKey = key;
        QueueRefresh();
    }

    internal void RevealConfirmedMessage(string peer, string id, string unavailableLabel, bool includeRevoked = false)
    {
        var epoch = _visibilityEpoch;
        var request = ++_externalReveal;
        var selected = _model.SelectedConversation?.Uuid;
        var navigation = _model.HistoryNavigationVersion;
        TrackMessageAction(RevealAsync());

        bool IsCurrent() => _view != 0 && IsVisible(_view) && request == _externalReveal && epoch == _visibilityEpoch &&
            _model.IsChatContentVisible && !_model.IsSelecting && _model.SelectedConversation?.Uuid == selected &&
            _model.HistoryNavigationVersion == navigation;

        async Task RevealAsync()
        {
            try
            {
                var opened = await _model.RevealWorkspaceMessageAsync(peer, id, IsCurrent, target =>
                {
                    if (!_model.CanChangeSelection || !_model.Conversations.Contains(target)) return;
                    _model.SelectedConversation = target;
                    selected = target.Uuid;
                    navigation = _model.HistoryNavigationVersion;
                }, includeRevoked);
                if (opened) QueueRefresh();
                else if (IsCurrent()) ShowNotice(AppStrings.Get(unavailableLabel));
            }
            catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
            {
                if (IsCurrent()) ShowNotice(AppStrings.Get("UiHistoryLoadFailed"));
            }
        }
    }

    internal void Close()
    {
        _dispatcher.VerifyAccess();
        ++_visibilityEpoch;
        StopPresenting();
        ClearCall();
        ClearAttachmentPreview();
        if (_view != 0) CloseWindow(_view);
    }

    internal void Show()
    {
        _dispatcher.VerifyAccess();
        if (_view == 0 || _isLocked()) return;
        _presentUpdates = true;
        Refresh();
        ShowWindow(_view, 1);
    }

    internal unsafe void ShowNotice(string message)
    {
        if (_view == 0 || !IsVisible(_view) || string.IsNullOrEmpty(message)) return;
        var frame = JsonSerializer.SerializeToUtf8Bytes(new { message });
        try { fixed (byte* data = frame) Present(_view, data, frame.Length, 5); }
        finally { CryptographicOperations.ZeroMemory(frame); }
    }

    internal unsafe void PresentArchiveUndo(int version, string message = "", string undo = "")
    {
        if (_view == 0) return;
        var frame = JsonSerializer.SerializeToUtf8Bytes(new { version, message, undo });
        try { fixed (byte* data = frame) Present(_view, data, frame.Length, 6); }
        finally { CryptographicOperations.ZeroMemory(frame); }
    }

    internal void Hide()
    {
        _dispatcher.VerifyAccess();
        if (_view == 0) return;
        ++_visibilityEpoch;
        StopPresenting();
        ClearSearch();
        ClearAttachmentReview();
        ClearAttachmentPreview();
        ShowWindow(_view, 0);
        SetActive(false, _callActive);
    }

    internal void Minimize()
    {
        _dispatcher.VerifyAccess();
        if (_view == 0) return;
        ++_visibilityEpoch;
        StopPresenting();
        ClearSearch();
        ClearAttachmentReview();
        ClearAttachmentPreview();
        ShowWindow(_view, 2);
        SetActive(false, _callActive);
    }

    // Called synchronously by the owner on lock, exit, and unlock. A pending
    // render always rechecks the current lock state before reading any rows.
    internal void Refresh() => Refresh(editorOnly: false);

    internal void SetChatTextPercentage(int percentage)
    {
        if (_chatTextPercentage == percentage) return;
        _chatTextPercentage = percentage;
        QueueRefresh();
    }

    internal void SetAppLockEnabled(bool enabled)
    {
        if (_appLockEnabled == enabled) return;
        _appLockEnabled = enabled;
        QueueRefresh();
    }

    private void AppearanceChanged(object? sender, EventArgs args) => QueueRefresh();

    private unsafe void Refresh(bool editorOnly)
    {
        _dispatcher.VerifyAccess();
        if (_view == 0) return;
        if (_isLocked() || !_model.IsWorkspaceReady)
        {
            ++_visibilityEpoch;
            _historyPosition = null;
            ClearSearch();
            ClearAttachmentReview();
            ClearAttachmentPreview();
            ClearCall();
            ClearSettings();
            ClearVisibleReads();
            _frames.Clear();
            Present(_view, null, 0, 0);
            return;
        }
        if (!_presentUpdates) return;
        if (!editorOnly && _observeRowsNeeded) ObserveRows();
        CheckAttachmentReview();
        UpdateAttachmentPreview();
        byte[]? frame = null;
        try
        {
            frame = editorOnly ? QtWorkspaceFrame.CaptureEditor(_model) :
                _frames.Capture(_model, locked: false, chatTextPercentage: _chatTextPercentage, appLockEnabled: _appLockEnabled);
            fixed (byte* data = frame) Present(_view, data, frame.Length, editorOnly ? 1 : 0);
        }
        catch (Exception error)
        {
            Present(_view, null, 0, 0);
            Trace.TraceError("Windows workspace update failed ({0}).", error.GetType().Name);
        }
        finally { if (frame is not null) CryptographicOperations.ZeroMemory(frame); }
    }

    private void ObserveRows()
    {
        _observeRowsNeeded = false;
        var current = _model.VisibleConversations.Cast<INotifyPropertyChanged>().Concat(_model.Messages).ToHashSet();
        foreach (var previous in _observed.Where(row => !current.Contains(row)).ToArray())
        {
            previous.PropertyChanged -= Changed;
            _observed.Remove(previous);
        }
        foreach (var row in current)
            if (_observed.Add(row)) row.PropertyChanged += Changed;
    }

    private void Changed(object? sender, PropertyChangedEventArgs args)
    {
        if (sender == _model && args.PropertyName == nameof(MainViewModel.PendingPreparations))
        {
            UpdateAttachmentReview();
            return;
        }
        QueueRefresh(editorOnly: sender == _model && args.PropertyName is
            (nameof(MainViewModel.Draft) or nameof(MainViewModel.HasDraft) or nameof(MainViewModel.TextSendPending)
            or nameof(MainViewModel.CanCompose) or nameof(MainViewModel.EditText) or nameof(MainViewModel.CanSubmitEdit)
            or nameof(MainViewModel.WebPreview) or nameof(MainViewModel.PreviewTarget) or nameof(MainViewModel.PreviewChoices)
            or nameof(MainViewModel.PreviewEnabled) or nameof(MainViewModel.PreviewLoading) or nameof(MainViewModel.PreviewAboveText)
            or nameof(MainViewModel.PreviewCompactImage) or nameof(MainViewModel.CanRetryWebPreview)
            or nameof(MainViewModel.HasPreviewLinks) or nameof(MainViewModel.HasWebPreview) or nameof(MainViewModel.CanChooseWebPreview)));
    }
    private void RowsChanged(object? sender, NotifyCollectionChangedEventArgs args)
    {
        _observeRowsNeeded = true;
        QueueRefresh();
    }

    private void StopPresenting()
    {
        _presentUpdates = false;
        ClearSettings();
        _update?.Abort();
        _update = null;
        _fullUpdate = false;
    }

    private void QueueRefresh(bool editorOnly = false)
    {
        if (_view == 0 || !_presentUpdates || _dispatcher.HasShutdownStarted) return;
        _fullUpdate |= !editorOnly;
        if (_update is not null) return;
        _update = _dispatcher.BeginInvoke(DispatcherPriority.DataBind, new Action(() =>
        {
            _update = null;
            var complete = _fullUpdate;
            _fullUpdate = false;
            Refresh(editorOnly: !complete);
        }));
    }
}

internal sealed record QtConversationAction(string Command, string? Peer = null, string? Folder = null,
    string? Target = null, int Version = 0);

internal sealed record QtMessageCollectionAction(string Command, string Peer, IReadOnlyList<string> Ids,
    long SelectionVersion, bool FromSelection);
