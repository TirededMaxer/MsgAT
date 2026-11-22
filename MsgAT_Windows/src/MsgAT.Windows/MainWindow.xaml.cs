using System.ComponentModel;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Text.Json;
using System.Security.Cryptography;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using MsgAT.Windows.Calls;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.ViewModels;
using MsgAT.Windows.Presentation;
using MsgAT.Windows.Views;
using MsgAT.Windows.Infrastructure;
using ScaleTransform = System.Windows.Media.ScaleTransform;
using TranslateTransform = System.Windows.Media.TranslateTransform;
using VisualTreeHelper = System.Windows.Media.VisualTreeHelper;

namespace MsgAT.Windows;

[SuppressMessage("Design", "CA1001:Types that own disposable fields should be disposable", Justification = "Application awaits service cleanup before closing the WPF window.")]
public partial class MainWindow : Window
{
    private int _stickerPanelTransition;
    private (string Peer, bool Archived)? _archiveUndo;
    private bool _archivePending;
    private string _lastFolderKey = "all";
    private int _archiveUndoAnimation;
    private readonly System.Windows.Threading.DispatcherTimer _archiveUndoTimer = new() { Interval = TimeSpan.FromSeconds(6) };

    private void Stickers_Click(object sender, RoutedEventArgs e)
    {
        if (StickerHost.Child is StickerLibraryPanel && StickerHost.IsHitTestVisible)
        {
            CloseStickerPanel(restoreFocus: true);
            return;
        }
        if (_appLocked || _exiting || MediaOverlayHost.Content is not null || _callWindow?.CoversChat == true ||
            _services is not { } services || _viewModel is not { CanCompose: true, IsSelecting: false, SelectedConversation: { } peer } model)
            return;

        CloseStickerPanel(immediate: true);
        CloseReplyQuotePicker();
        model.ShowAttachments = false;
        var lockGeneration = _appLockGeneration;
        var panel = new StickerLibraryPanel(services, this, () => CloseStickerPanel(restoreFocus: true), async (item, token) =>
        {
            if (_appLocked || _exiting || lockGeneration != _appLockGeneration ||
                model.SelectedConversation?.Uuid != peer.Uuid || model.CaptureStickerSender() is not { } send)
                return PreparationSendResult.Failed;
            return await send(item, token);
        }, () => model.PendingPreparations.LastOrDefault(row => row.IsSticker));
        panel.BusyChanged += StickerPanelBusyChanged;
        StickerHost.Child = panel;
        StickerHost.Visibility = StickerScrim.Visibility = Visibility.Visible;
        StickerImageView.SetPlaybackPaused(MessageList, true);
        StickerHost.IsHitTestVisible = true;
        StickerToggle.SetResourceReference(FrameworkElement.ToolTipProperty, "UiStickerClosePanel");
        StickerToggle.SetResourceReference(System.Windows.Automation.AutomationProperties.NameProperty, "UiStickerClosePanel");
        StickerToggle.SetResourceReference(Control.ForegroundProperty, "AccentTextBrush");
        SizeStickerPanel();
        AnimateStickerPanel(open: true, immediate: false);
        UpdateChatVisibility();
    }

    private void SizeStickerPanel()
    {
        if (StickerHost.Visibility != Visibility.Visible) return;
        StickerHost.Width = Math.Max(0, Math.Min(345, ChatPane.ActualWidth - 18));
        var height = MessageList.ActualHeight;
        StickerHost.Height = Math.Max(0, Math.Min(height - 8, Math.Clamp(height * .78, 278, 520)));
    }

    private void UpdateChatColumnLayout(double width)
    {
        const double maximumChatColumnWidth = 856;
        var compact = width < 600;
        var inset = Math.Max(0, (width - maximumChatColumnWidth) / 2);
        var margin = (compact ? 10 : 16) + inset;
        MessageList.Padding = new Thickness(margin, 8, margin, 8);
        ComposerLayout.Margin = new Thickness(inset, 0, inset, 0);
        AttachmentPanel.Margin = new Thickness(8 + inset, 0, 8 + inset, 6);
        StickerHost.Margin = new Thickness(8 + inset, 0, 10 + inset, 7);
        TimelineActions.Margin = new Thickness(0, 0, 16 + inset, 16);
    }

    private void CloseStickerPanel_Click(object sender, RoutedEventArgs e) => CloseStickerPanel(restoreFocus: true);

    private void CloseStickerPanel(bool restoreFocus = false, bool immediate = false)
    {
        if (StickerHost.Child is not StickerLibraryPanel panel || (!immediate && panel.IsBusy)) return;
        StickerHost.IsHitTestVisible = false;
        StickerToggle.SetResourceReference(FrameworkElement.ToolTipProperty, "UiStickers");
        StickerToggle.SetResourceReference(System.Windows.Automation.AutomationProperties.NameProperty, "UiStickers");
        StickerToggle.SetResourceReference(Control.ForegroundProperty, "TextBrush");
        AnimateStickerPanel(open: false, immediate: immediate);
        if (restoreFocus && IsActive && !_appLocked && !_exiting && Composer.IsEnabled)
            Composer.Focus();
    }

    private void AnimateStickerPanel(bool open, bool immediate)
    {
        var version = ++_stickerPanelTransition;
        var opacity = StickerHost.Opacity;
        var offset = (TranslateTransform)StickerHost.RenderTransform;
        var y = offset.Y;
        var targetOpacity = open ? 1d : 0d;
        var targetY = open ? 0d : 9d;
        void End()
        {
            if (version != _stickerPanelTransition) return;
            StickerHost.BeginAnimation(OpacityProperty, null);
            offset.BeginAnimation(TranslateTransform.YProperty, null);
            StickerHost.Opacity = targetOpacity;
            offset.Y = targetY;
            if (open) return;
            if (StickerHost.Child is StickerLibraryPanel panel)
            {
                panel.BusyChanged -= StickerPanelBusyChanged;
                panel.Shutdown();
            }
            StickerHost.Child = null;
            StickerHost.Visibility = StickerScrim.Visibility = Visibility.Collapsed;
            StickerImageView.SetPlaybackPaused(MessageList, false);
            StickerPanelBusyChanged(null, EventArgs.Empty);
            UpdateChatVisibility();
        }
        if (immediate || !SystemParameters.ClientAreaAnimation)
        {
            End();
            return;
        }
        var duration = TimeSpan.FromMilliseconds(open ? 180 : 140);
        var animation = new DoubleAnimation(opacity, targetOpacity, duration);
        animation.Completed += (_, _) => End();
        StickerHost.BeginAnimation(OpacityProperty, animation);
        offset.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(y, targetY, duration)
            { EasingFunction = new CubicEase { EasingMode = open ? EasingMode.EaseOut : EasingMode.EaseIn } });
    }

    private void StickerPanelBusyChanged(object? sender, EventArgs args)
    {
        var busy = sender is StickerLibraryPanel { IsBusy: true };
        var value = busy ? System.Windows.Input.Cursors.Wait : null;
        StickerToggle.Cursor = value;
        Composer.IsReadOnly = busy;
    }
    private void RemoveWebPreview_Click(object sender, RoutedEventArgs e)
    {
        if (_appLocked || _exiting || _viewModel is not { CanCompose: true, HasWebPreview: true } model) return;
        Composer.Focus();
        model.RemoveWebPreview();
    }
    private void RetryWebPreview_Click(object sender, RoutedEventArgs e)
    {
        if (_appLocked || _exiting || _viewModel is not { CanCompose: true, CanRetryWebPreview: true } model) return;
        Composer.Focus();
        model.RetryWebPreview();
    }
    private void ToggleWebPreviewPlacement_Click(object sender, RoutedEventArgs e) => _viewModel?.ToggleWebPreviewPlacement();
    private void ToggleWebPreviewImageSize_Click(object sender, RoutedEventArgs e) => _viewModel?.ToggleWebPreviewImageSize();

    private void ChooseWebPreview_Click(object sender, RoutedEventArgs e)
    {
        if (_appLocked || _exiting || sender is not Button button ||
            _viewModel is not { CanChooseWebPreview: true } model) return;

        var peer = model.SelectedConversation?.Uuid;
        var menu = new ContextMenu
        {
            PlacementTarget = button,
            Placement = System.Windows.Controls.Primitives.PlacementMode.Top,
            MaxHeight = 360
        };
        foreach (var url in model.PreviewChoices)
        {
            var item = new MenuItem
            {
                Header = new TextBlock { Text = url, MaxWidth = 420, TextTrimming = TextTrimming.CharacterEllipsis },
                ToolTip = url,
                IsCheckable = true,
                IsChecked = url == model.PreviewTarget
            };
            AutomationProperties.SetName(item, url);
            item.Click += (_, _) =>
            {
                if (_appLocked || _exiting || !ReferenceEquals(_viewModel, model) ||
                    peer != model.SelectedConversation?.Uuid || !model.CanCompose) return;
                model.SelectWebPreview(url);
            };
            menu.Items.Add(item);
        }
        menu.Closed += (_, _) => button.ContextMenu = null;
        button.ContextMenu = menu;
        menu.IsOpen = true;
    }

    private void ChooseWebPreview_IsVisibleChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (sender is Button { IsVisible: false, ContextMenu: { } menu }) menu.IsOpen = false;
    }
    private AppServices? _services;
    private MainViewModel? _viewModel;
    private CallOverlay? _callWindow;
    private ModalCallWindow? _modalCallWindow;
    private RecordingDialog? _recordingDialog;
    private AttachmentPreview? _inlineAudioPreview;
    private double _inlineAudioSpeed = 1;
    private bool _editImeComposing => EditComposer.Composing;
    private bool _allowClose;
    private bool _loadingOlderMessages;
    private bool _sendCancellationPending;
    private readonly HashSet<string> _retryingSends = new(StringComparer.Ordinal);
    private readonly List<QuoteReturnAnchor> _quoteReturnAnchors = [];
    private readonly record struct QuoteReturnAnchor(string MessageId, double Offset);
    private string? _reportedActivityPeer;
    private readonly System.Windows.Threading.DispatcherTimer _activityLabelTimer = new();
    private readonly System.Windows.Threading.DispatcherTimer _readVisibilityTimer=new(){Interval=TimeSpan.FromMilliseconds(150)};
    private readonly System.Windows.Threading.DispatcherTimer _jumpHighlightTimer = new() { Interval = TimeSpan.FromMilliseconds(1500) };
    private readonly System.Windows.Threading.DispatcherTimer _timelineDateTimer = new() { Interval = TimeSpan.FromMilliseconds(1600) };
    private bool _exiting;
    private Task<AppServices>? _initialization;
    private bool _imeComposing => Composer.Composing;
    private const double DefaultSidebarRatio = 330d / 1040d;
    private double _preferredSidebarRatio = DefaultSidebarRatio;
    private double _sidebarWidthBeforeKey;
    private int _chatTextPercentage = 100;
    private bool _closeToTray = true;
    private const double MaximumSidebarWidth = 460;

    public MainWindow()
    {
        InitializeComponent();
        ConversationList.Choose = SelectConversationRow;
        ConversationList.MenuFor = CreateConversationMenu;
        ChatHeader.InvokeAction = action => Dispatcher.BeginInvoke(() => PerformHeaderAction(action));
        ChatHeader.NotificationMenu = CreateNotificationMenu;
        _jumpHighlightTimer.Tick += (_, _) => ClearJumpHighlight();
        _timelineDateTimer.Tick += (_, _) =>
        {
            if (!FloatingTimelineDate.IsMouseOver && !FloatingTimelineDate.IsKeyboardFocusWithin) ClearFloatingTimelineDate(fade: true);
        };
        AttachAppLock();
        WorkspaceBar.MenuFor = CreateWorkspaceMenu;
        FolderList.Shortcut = WorkspaceBar.Shortcut = ConversationList.Shortcut = ChatHeader.Shortcut = HandleWorkspaceKey;
        foreach (var editor in new[] { Composer, EditComposer })
        {
            editor.Shortcut = HandleWorkspaceKey;
            editor.SecureCopy = text => !_appLocked && !_exiting && MessageTextBlock.CopyToClipboard(text, this, showSuccess: false);
            editor.CanFormat = () => CanFormatComposer(editor);
            editor.Format = kind => FormatComposerSelection(kind, editor);
        }
        Composer.HasMediaPaste = HasComposerMediaPaste;
        Composer.PasteMedia = PasteComposerMedia;
        Composer.FocusChanged = () =>
        {
            if (Composer.EditorFocused && StickerHost.IsHitTestVisible) CloseStickerPanel();
            UpdateChatActivity(force: true);
        };
        Composer.Typed = () => UpdateChatActivity(typing: true);
        ChatDropIndicator.IsVisibleChanged += (_, _) => SetNativeChatVisibility();
        DropPreparationIndicator.IsVisibleChanged += (_, _) => SetNativeChatVisibility();
        PreviewKeyDown += Window_PreviewKeyDown;
        LayoutGrid.SizeChanged += (_, args) => ApplySidebarWidth(args.NewSize.Width);
        SidebarSplitter.DragCompleted += (_, args) =>
        {
            if (!args.Canceled && args.HorizontalChange != 0) RememberSidebarWidth();
        };
        SidebarSplitter.KeyUp += (_, args) =>
        {
            if (args.Key is (Key.Left or Key.Right) && SidebarColumn.Width.Value != _sidebarWidthBeforeKey) RememberSidebarWidth();
        };
        SidebarSplitter.PreviewKeyDown += (_, args) =>
        {
            if (!args.IsRepeat && args.Key is (Key.Left or Key.Right)) _sidebarWidthBeforeKey = SidebarColumn.Width.Value;
        };
        SidebarSplitter.PreviewMouseLeftButtonDown += (_, args) =>
        {
            if (args.ClickCount != 2 || Keyboard.Modifiers != ModifierKeys.None) return;
            args.Handled = true;
            _preferredSidebarRatio = DefaultSidebarRatio;
            ApplySidebarWidth(LayoutGrid.ActualWidth);
        };
        WindowAppearance.Apply(this);
        RestoreWindowPlacement();
        ApplyChatTextPercentage(_chatTextPercentage);
        _readVisibilityTimer.Tick+=(_,_)=>{_readVisibilityTimer.Stop();MarkVisibleMessagesRead();};
        MessageList.LayoutUpdated += (_, _) =>
        {
            UpdateComposerHeightLimit();
            QueueVisibleReads();
        };
        MessageList.SizeChanged += (_, _) => SizeStickerPanel();
        ChatPane.SizeChanged += (_, args) =>
        {
            if (args.WidthChanged) UpdateChatColumnLayout(args.NewSize.Width);
        };
        _activityLabelTimer.Tick += (_, _) => RefreshPeerActivity();
        System.Windows.Application.Current.Activated+=ActivityAppActivated;
        System.Windows.Application.Current.Deactivated+=ActivityAppDeactivated;
        StateChanged += (_, _) => { QueueVisibleReads(); UpdateActivityForeground(); UpdateChatActivity(); };
        IsVisibleChanged += (_, _) => { QueueVisibleReads(); UpdateActivityForeground(); UpdateChatActivity(); };
        Activated += (_, _) =>
        {
            CloseModalCallWindow();
            UpdateActivityForeground();
            QueueVisibleReads();
            UpdateChatActivity();
            if (_interruptedRecording is not null) Dispatcher.BeginInvoke(new Action(ReviewInterruptedRecording));
        };
        Deactivated += (_, _) => { ResetVisibleReadCandidates(); UpdateChatActivity(); };
        _archiveUndoTimer.Tick += (_, _) => ClearArchiveUndo(animate: true);
        Closed+=(_,_)=>{_archiveUndoTimer.Stop();_readVisibilityTimer.Stop();_activityLabelTimer.Stop();System.Windows.Application.Current.Activated-=ActivityAppActivated;System.Windows.Application.Current.Deactivated-=ActivityAppDeactivated;};
    }

    public void AllowClose() => _allowClose = true;

    private async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        _ = sender;
        _ = e;
        if (_initialization is not null) return;
        try
        {
            _initialization = AppServices.CreateAsync(Dispatcher, WebRtcView);
            _services = await _initialization;
            if (_exiting) { return; }
            _appLockEnabled = await _services.Messaging.AppLockEnabledAsync();
            if (_exiting) return;
            _appLockLoaded = true;
            _appLocked = _appLockEnabled;
            ApplyAppLockVisibility();
            _services.Calls.ShowRequested += Calls_ShowRequested;
            _services.Calls.PropertyChanged += Calls_PropertyChanged;
            _services.Messaging.MessagesRemoved += Messaging_MessagesRemoved;
            _services.Messaging.AttachmentProgressChanged += Messaging_MediaDownloadProgress;
            _services.Messaging.PeerActivityChanged += Messaging_PeerActivityChanged;
            _services.Calls.NoticeRequested += (_, message) =>
            {
                if (!_appLocked && _qtWorkspace?.ShowCallNotice(message) != true) ShowWorkspaceNotice(message);
            };
            if (!_appLocked)
            {
                InitializeUnlockedWorkspace();
                OpenDeferredNotification();
            }
            if (_services.Calls.IsIncoming) Calls_ShowRequested(_services.Calls, EventArgs.Empty);
        }
        catch (Exception exception)
        {
            if (_exiting) return;
            AppOpeningProgress.Visibility = Visibility.Collapsed;
            Trace.TraceError("Initialization failed: {0}", exception.GetType().Name);
            await HandleStartupFailureAsync(exception);
        }
    }

    private async Task HandleStartupFailureAsync(Exception failure)
    {
        var app = (App)System.Windows.Application.Current;
        var reset = AppStrings.Get("UiResetIdentity");
        var failureKey = failure switch
        {
            IdentityStartupException => "UiStartupIdentityUnavailable",
            VaultStartupException => "UiStartupVaultUnavailable",
            _ => "UiStartupServicesUnavailable"
        };
        var title = AppStrings.Get(failure is IdentityStartupException or VaultStartupException
            ? "UiStorageUnavailable" : "UiAppStartError");
        var executable = Environment.ProcessPath;
        string? resetError = null;
        while (!_exiting)
        {
            var resetPending = IdentityLifecycle.HasPendingReset(RuntimePaths.DataDirectory);
            var canReset = !resetPending && failure is IdentityStartupException { CanReset: true } && !string.IsNullOrEmpty(executable);
            var summary = AppStrings.Get(resetPending ? "UiResetIncomplete" : failureKey);
            var issue = new StartupIssueDialog(title, summary, failure.Message, canReset, resetError) { Owner = this };
            if (issue.ShowDialog() != true || _exiting || !canReset) break;
            var prompt = new ConfirmationDialog(AppStrings.Get("UiIdentityResetUnreadableConfirm"),
                title: reset, confirmLabel: reset) { Owner = this };
            if (prompt.ShowDialog() != true || _exiting) continue;
            try
            {
                await Task.Run(() => IdentityLifecycle.PrepareLocalOnlyReset(RuntimePaths.DataDirectory));
                _initialization = null;
                app.RestartApplication(executable!);
                return;
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                resetError = AppStrings.Get("UiIdentityResetPrepareFailed");
            }
        }
        _initialization = null;
        app.ExitApplication();
    }

    private void ClearArchiveUndo(bool animate = false)
    {
        _archiveUndoTimer.Stop();
        _archiveUndo = null;
        var version = ++_archiveUndoAnimation;
        _qtWorkspace?.PresentArchiveUndo(version);
        ArchiveUndoBanner.IsEnabled = false;
        if (!animate || !UseSidebarMotion || !ArchiveUndoBanner.IsVisible)
        {
            ArchiveUndoBanner.BeginAnimation(OpacityProperty, null);
            ArchiveUndoBanner.Visibility = Visibility.Collapsed;
            return;
        }
        var fade = new DoubleAnimation(ArchiveUndoBanner.Opacity, 0, TimeSpan.FromMilliseconds(130))
        {
            FillBehavior = FillBehavior.Stop
        };
        fade.Completed += (_, _) =>
        {
            if (version == _archiveUndoAnimation) ArchiveUndoBanner.Visibility = Visibility.Collapsed;
        };
        ArchiveUndoBanner.BeginAnimation(OpacityProperty, fade, HandoffBehavior.SnapshotAndReplace);
    }

    private async void ArchiveUndo_Click(object sender, RoutedEventArgs e)
    {
        if (_archiveUndo is { } notice) await ChangeArchiveAsync(notice.Peer, !notice.Archived, showUndo: false);
    }

    private async Task ChangeArchiveAsync(string peer, bool archived, bool showUndo = true)
    {
        if (_archivePending || _appLocked || _exiting || _services is not { } services) return;
        var generation = _appLockGeneration;
        ClearArchiveUndo();
        _archivePending = true;
        var changed = false;
        try { changed = await services.ConversationIndex.SetArchivedAsync(peer, !archived, archived); }
        catch (Exception error) when (error is InvalidOperationException or OperationCanceledException or IOException or Microsoft.Data.Sqlite.SqliteException) { }
        finally { _archivePending = false; }
        if (_appLocked || _exiting || generation != _appLockGeneration || !ReferenceEquals(services, _services)) return;
        if (!changed)
        {
            ShowWorkspaceNotice(AppStrings.Get("UiArchiveChangeFailed"));
            return;
        }
        if (!showUndo) return;
        _archiveUndo = (peer, archived);
        PresentQtArchiveUndo();
        ArchiveUndoText.SetResourceReference(TextBlock.TextProperty, archived ? "UiChatArchived" : "UiChatUnarchived");
        ArchiveUndoBanner.IsEnabled = true;
        ArchiveUndoBanner.Visibility = Visibility.Visible;
        PlaySidebarEntrance(ArchiveUndoBanner, 0, 5);
        _archiveUndoTimer.Start();
    }

    private bool UseSidebarMotion => SystemParameters.ClientAreaAnimation && !_appLocked && !_exiting;

    private void PlaySidebarEntrance(FrameworkElement element, double x, double y)
    {
        element.BeginAnimation(OpacityProperty, null);
        element.RenderTransform = System.Windows.Media.Transform.Identity;
        if (!UseSidebarMotion || !element.IsVisible) return;

        var translation = new System.Windows.Media.TranslateTransform();
        element.RenderTransform = translation;
        DoubleAnimation Enter(double from, double to) => new(from, to, TimeSpan.FromMilliseconds(160))
        {
            EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut },
            FillBehavior = FillBehavior.Stop
        };
        element.BeginAnimation(OpacityProperty, Enter(0.72, 1));
        if (x != 0) translation.BeginAnimation(System.Windows.Media.TranslateTransform.XProperty, Enter(x, 0));
        if (y != 0) translation.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, Enter(y, 0));
    }

    private void ArchiveHeader_IsVisibleChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (e.NewValue is true && sender is FrameworkElement header) PlaySidebarEntrance(header, 0, -5);
    }

    private void ArchiveEntry_Click(object sender, RoutedEventArgs e)
    {
        if (_appLocked || _exiting || _viewModel is not { ShowArchiveEntry: true } model) return;
        SelectFolderAndFocus(model, "archive");
    }

    private void LeaveArchive_Click(object sender, RoutedEventArgs e)
    {
        if (_appLocked || _exiting || _viewModel is not { FolderKey: "archive" } model) return;
        SelectFolderAndFocus(model, "all");
    }

    private void SelectFolderAndFocus(MainViewModel model, string key)
    {
        model.FolderKey = key;
        Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Loaded, new Action(() =>
        {
            if (_appLocked || _exiting || !ReferenceEquals(_viewModel, model) || model.FolderKey != key) return;
            FolderList.FocusSelected();
        }));
    }

    private void InitializeUnlockedWorkspace()
    {
        if (_appLocked || _exiting || _viewModel is not null || _services is null) return;
        var services = _services;
        if (string.IsNullOrWhiteSpace(services.Settings.Snapshot.Nickname))
        {
            var setup = new IdentitySetupDialog((name, avatar, remove) =>
                SaveProfileAsync(services, name, avatar, remove)) { Owner = this };
            if (setup.ShowDialog() != true)
            {
                if (!_appLocked && !_exiting) ((App)System.Windows.Application.Current).ExitApplication();
                return;
            }
        }
        if (_exiting || _appLocked) { return; }
        _viewModel = new MainViewModel(services, Dispatcher);
        _viewModel.ErrorRequested += (_, message) => ShowWorkspaceNotice(message);
        _viewModel.Messages.CollectionChanged += (_, _) =>
        {
            if (MediaOverlayHost.Content is MediaOverlay viewer && !ReferenceEquals(viewer, _galleryPreview) &&
                !_viewModel.Messages.Any(item => item.MessageId == viewer.MessageId && !item.IsRevoked))
                CloseChatMedia();
            if (_inlineAudioPreview?.Message is { } audio &&
                !_viewModel.Messages.Any(item => item.MessageId == audio.MessageId && !item.IsRevoked && item.IsAvailable))
                CloseInlineAudio();
        };
        _viewModel.InitialMessagesLoaded += (_, _) =>
        {
            var peer=_viewModel.SelectedConversation?.Uuid;
            if (MediaOverlayHost.Content is MediaOverlay viewer && viewer.Peer != peer) CloseChatMedia();
            var version=_viewModel.HistoryNavigationVersion;
            var anchor=_viewModel.InitialScrollTarget;
            var saved=_viewModel.InitialHistoryPosition;
            _restoringHistoryVersion = anchor is null ? null : version;
            Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Loaded,new Action(()=>
            {
                if(_exiting || _viewModel.SelectedConversation?.Uuid!=peer || _viewModel.HistoryNavigationVersion!=version)return;
                if (anchor is null) { ScrollToLatest(); QueueVisibleReads(); return; }
                if (_viewModel.Messages.FirstOrDefault(item=>item.MessageId==anchor) is { } target)
                {
                    if (saved is { } position) RestoreHistoryViewport(target, position.Offset, version, finishInitialRestore: true);
                    else ShowJumpTarget(target, finishInitialRestore: true);
                }
                else FinishHistoryRestore(version);
            }));
        };
        _viewModel.CaptureHistoryPosition = CaptureHistoryViewport;
        _viewModel.LatestMessagesChanged += (_, _) => ScrollToLatest();
        _viewModel.MessagesRefreshed += RefreshActiveGallery;
        _viewModel.MessagesRefreshed += ChatTimelineChanged;
        _viewModel.VisibleReadsApplied += ChatTimelineChanged;
        _viewModel.PropertyChanged += ChatChromePropertyChanged;
        _lastFolderKey = _viewModel.FolderKey;
        DataContext = _viewModel;
        UpdateComposerAction(_viewModel, animate: false);
        UpdateActivityForeground();UpdateChatActivity();
        _ = LoadWorkspaceAsync(_viewModel);
    }

    private async Task LoadWorkspaceAsync(MainViewModel model)
    {
        await model.LoadInitialWorkspaceAsync();
        if (!_exiting && !_appLocked && ReferenceEquals(_viewModel, model))
        {
            OpenDeferredNotification();
            ScheduleQtWorkspace();
        }
    }

    private async void RetryWorkspace_Click(object sender, RoutedEventArgs e)
    {
        if (!_exiting && !_appLocked && _viewModel is { } model) await LoadWorkspaceAsync(model);
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        if (_allowClose) return;
        e.Cancel = true;
        if (_exiting) return;

        var app = (App)System.Windows.Application.Current;
        if (_closeToTray)
        {
            ExitMediaFullScreen();
            SaveWindowPlacement();
            if (app.HasTrayIcon) Hide();
            else WindowState = WindowState.Minimized;
        }
        else
        {
            // Shutdown may close the window on the same dispatcher. Let this Closing callback return first.
            Dispatcher.BeginInvoke(new Action(app.ExitApplication));
        }
    }

    public async Task PrepareForExitAsync()
    {
        _exiting = true;
        CloseModalCallWindow();
        ApplyAppLockVisibility();
        IsEnabled = false;
        ResetVisibleReadCandidates();
        ExitMediaFullScreen();
        SaveWindowPlacement();
        _activityLabelTimer.Stop();
        CloseStickerPanel(immediate: true);
        CloseChatMedia();
        CloseReplyQuotePicker();
        CloseInlineAudio();
        DiscardInterruptedRecording();
        ClearJumpHighlight();
        ClearFloatingTimelineDate();
        var export = AttachmentExporter.StopAsync();
        var recording = _recordingDialog?.CloseForExitAsync() ?? Task.CompletedTask;
        var workspace = Task.CompletedTask;
        if (_viewModel is { } model)
        {
            model.PropertyChanged -= ChatChromePropertyChanged;
            model.MessagesRefreshed -= RefreshActiveGallery;
            model.MessagesRefreshed -= ChatTimelineChanged;
            model.VisibleReadsApplied -= ChatTimelineChanged;
            model.Dispose();
            workspace = Task.WhenAll(model.InitialWorkspaceLoadCompletion, model.HistoryLoadCompletion);
        }
        if (_callWindow is not null) { _callWindow.IsEnabled = false; _callWindow.Visibility = Visibility.Collapsed; _callWindow.Dispose(); }
        try
        {
            await Task.WhenAll(export, recording, workspace);
        }
        finally
        {
            if (_services is null && _initialization is not null)
                _services = await _initialization;
            if (_services is { } services)
            {
                services.Calls.ShowRequested -= Calls_ShowRequested;
                services.Calls.PropertyChanged -= Calls_PropertyChanged;
                services.Messaging.MessagesRemoved -= Messaging_MessagesRemoved;
                services.Messaging.AttachmentProgressChanged -= Messaging_MediaDownloadProgress;
                services.Messaging.PeerActivityChanged -= Messaging_PeerActivityChanged;
                await services.DisposeAsync();
            }
        }
    }

    private void Window_Closed(object? sender, EventArgs e)
    {
        _ = sender;
        _ = e;
        WebRtcView.Dispose();
    }

    private ContextMenu? CreateWorkspaceMenu()
    {
        if (_appLocked || _exiting || _viewModel?.IsWorkspaceReady != true) return null;
        var menu = new ContextMenu();
        var add = new MenuItem { Header = AppStrings.Get("UiAddFriend") };
        add.Click += AddFriend_Click;
        var requests = new MenuItem { Header = AppStrings.Get("UiFriendRequests") };
        requests.Click += Requests_Click;
        var settings = new MenuItem { Header = AppStrings.Get("UiSettings") };
        settings.Click += Settings_Click;
        var contacts = new MenuItem { Header = AppStrings.Get("UiContacts") };
        contacts.Click += (_, _) =>
        {
            if (_services is { } services && _viewModel is { } model && !_appLocked && !_exiting)
            {
                var dialog = new ContactsDialog(services, peer => OpenConfirmedContactConversation(model, peer)) { Owner = this };
                dialog.ConfirmOpenChat = peer => ConfirmConversationNavigation(model, peer, dialog);
                dialog.ShowDialog();
            }
        };
        menu.Items.Add(contacts);
        menu.Items.Add(add);
        menu.Items.Add(requests);
        var profile=new MenuItem{Header=AppStrings.Get("UiEditProfile")};profile.Click+=EditProfile_Click;menu.Items.Add(profile);
        menu.Items.Add(settings);
        var folders = new MenuItem { Header = AppStrings.Get("UiChatFolders") };
        folders.Click += (_, _) =>
        {
            if (!_appLocked && !_exiting && _services is { } services && _viewModel is { } model)
                new ConversationFoldersDialog(services, model.Conversations.ToArray(), () => !_appLocked && !_exiting)
                    { Owner = this }.ShowDialog();
        };
        menu.Items.Add(folders);
        var privacy=new MenuItem{Header=AppStrings.Get("UiPrivacy")};privacy.Click+=(_,_)=>{if(_services is not null)new ReadReceiptPrivacyDialog(_services){Owner=this}.ShowDialog();};menu.Items.Add(privacy);
        var appLock = new MenuItem { Header = AppStrings.Get(_appLockEnabled ? "UiDisableAppLock" : "UiEnableAppLock"), IsEnabled = !_authenticating };
        appLock.Click += async (_, _) => await AuthenticateAppLockAsync(!_appLockEnabled);
        menu.Items.Add(appLock);
        if (_appLockEnabled)
        {
            var lockNow = new MenuItem { Header = AppStrings.Get("UiLockNow") };
            lockNow.Click += (_, _) => LockApp();
            menu.Items.Add(lockNow);
        }
        var storage = new MenuItem { Header = AppStrings.Get("UiStorageUsage") };
        storage.Click += (_, _) => OpenStorageDialog(this);
        menu.Items.Add(storage);
        var search = new MenuItem { Header=AppStrings.Get("UiSearchMessages"),InputGestureText="Ctrl+F" };
        search.Click += (_, _) => OpenMessageSearch(global: true);
        menu.Items.Add(search);
        var saved=new MenuItem {Header=AppStrings.Get("UiSavedMessages")};
        saved.Click+=(_,_)=>OpenSavedMessages();
        menu.Items.Add(saved);
        var calls = new MenuItem { Header = AppStrings.Get("UiCallHistory") };
        calls.Click += async (_, _) => await OpenCallHistoryAsync();
        menu.Items.Add(calls);
        var stickerLibrary = new MenuItem { Header = AppStrings.Get("UiStickerLibrary") };
        stickerLibrary.Click += (_, _) =>
        {
            if (!_appLocked && !_exiting && _services is { } services)
                new StickerLibraryDialog(services) { Owner = this }.ShowDialog();
        };
        menu.Items.Add(stickerLibrary);
        var sending=new MenuItem{Header=AppStrings.Get("UiSendingQueue")};sending.Click+=(_,_)=>OpenSendQueue();menu.Items.Add(sending);
        var forwarding=new MenuItem {Header=AppStrings.Get("UiForwardingQueue")};
        forwarding.Click += (_, _) => OpenForwardingQueue();
        menu.Items.Add(forwarding);
        menu.Items.Add(new Separator());
        var exit = new MenuItem { Header = AppStrings.Get("UiExit"), InputGestureText = "Ctrl+Q" };
        exit.Click += (_, _) => ((App)System.Windows.Application.Current).ExitApplication();
        menu.Items.Add(exit);
        return menu;
    }

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        _ = sender;
        if (_appLocked) return;
        if (_exiting) { return; }
        if (_callWindow is { CoversChat: true } callOverlay)
        {
            if (e.Key == Key.Escape && callOverlay.MinimizeFromKeyboard()) e.Handled = true;
            return;
        }
        if (MediaOverlayHost.Content is MediaOverlay activeMedia)
        {
            activeMedia.HandleViewShortcut(e);
            return;
        }
        if (e.OriginalSource is WinUIHost) return;
        var key = new WorkspaceKey(e.Key == Key.System ? e.SystemKey : e.Key, Keyboard.Modifiers, e.IsRepeat);
        HandleWorkspaceKey(key);
        e.Handled |= key.Handled;
    }

    private async void HandleWorkspaceKey(WorkspaceKey e)
    {
        if (_appLocked || _exiting || _callWindow?.CoversChat == true || MediaOverlayHost.Content is not null) return;
        if (e.Key == Key.Escape && _replyQuotePicker is not null)
        {
            e.Handled = true;
            CloseReplyQuotePicker();
            Composer.Focus();
            return;
        }
        if (e.Key == Key.Escape && _inlineAudioPreview?.IsKeyboardFocusWithin == true)
        {
            e.Handled = true;
            CloseInlineAudio();
            return;
        }
        if (TryConversationNavigationShortcut(e)) return;
        var canHandleComposerKeys = Composer.EditorFocused && !_imeComposing;
        if (e.Key == Key.Escape &&
            (_imeComposing && Composer.EditorFocused || _editImeComposing && EditComposer.EditorFocused))
            return;
        if (canHandleComposerKeys && (e.Key == Key.V && e.Modifiers == ModifierKeys.Control ||
            e.Key == Key.Insert && e.Modifiers == ModifierKeys.Shift) && HasComposerMediaPaste())
        {
            e.Handled = true;
            if (!e.IsRepeat) PasteComposerMedia();
            return;
        }
        if (canHandleComposerKeys && TryFormatShortcut(e)) return;
        if (e.Key == Key.Escape && _viewModel is { ShowAttachments: true } attachmentModel)
        {
            attachmentModel.ShowAttachments = false;
            AttachmentToggle.Focus();
            e.Handled = true;
            return;
        }
        if (e.Key == Key.Escape && StickerHost.IsHitTestVisible && StickerHost.Child is StickerLibraryPanel stickerPanel)
        {
            stickerPanel.BackOrClose();
            e.Handled = true;
            return;
        }
        if (_viewModel is { HasMessageEdit: true } editModel)
        {
            if (e.Key == Key.Escape && !_editImeComposing)
            {
                if (editModel.CanCancelEdit) CancelMessageEdit();
                e.Handled = true;
                return;
            }
            if (EditComposer.EditorFocused && !_editImeComposing)
            {
                if (TryFormatShortcut(e)) return;
                if (e.Key == Key.Return && e.Modifiers == ModifierKeys.None)
                {
                    e.Handled = true;
                    await SubmitMessageEditAsync();
                    return;
                }
            }
        }
        if (e.Key == Key.C && e.Modifiers == ModifierKeys.Control && _viewModel is { IsSelecting: true })
        {
            e.Handled = true;
            await CopySelectedMessagesAsync();
            return;
        }
        if(e.Key==Key.Escape && _viewModel is {IsSelecting:true})
        {
            e.Handled=true;if(_viewModel.CanChangeSelection)_viewModel.ClearSelection();return;
        }
        if (StickerHost.Child is StickerLibraryPanel { IsBusy: true } && Composer.EditorFocused)
        {
            if (e.Key == Key.Return) e.Handled = true;
            return;
        }
        if ((e.Key == Key.Escape || canHandleComposerKeys) && TryComposerHistoryShortcut(e)) return;
        if (TrySearchNavigationShortcut(e)) return;
        if (e.Key == Key.O && e.Modifiers == ModifierKeys.Control && !IsDropCoveringChat && !IsComposerSendMenuOpen &&
            !_imeComposing && !_editImeComposing && _viewModel is { CanCompose: true, IsChatContentVisible: true })
        {
            e.Handled = true;
            if (!e.IsRepeat) ChooseChatFiles("Files");
            return;
        }
        if(e.Key==Key.F && e.Modifiers==ModifierKeys.Control)
        {
            e.Handled=true;OpenMessageSearch();return;
        }
        if (e.Key==Key.Q && e.Modifiers==ModifierKeys.Control)
        {
            e.Handled=true;
            ((App)System.Windows.Application.Current).ExitApplication();
            return;
        }
        if (e.Key != Key.Return || e.Modifiers != ModifierKeys.None || !canHandleComposerKeys) return;
        e.Handled=true; // Shift+Enter and IME confirmation retain native text editing.
        if (_viewModel is { HasDraft: true } model) await SendComposerDraftAsync(model);
    }

    private bool CanFormatComposer(ChatEditorHost editor) =>
        !_appLocked && !_exiting && !editor.IsReadOnly && _viewModel is { IsChatContentVisible: true } model &&
        (editor == EditComposer ? model.CanEditText && !_editImeComposing : model.CanCompose && !_imeComposing);

    private bool TryFormatShortcut(WorkspaceKey e)
    {
        var modifiers=e.Modifiers;
        if(modifiers!=ModifierKeys.Control && modifiers!=(ModifierKeys.Control|ModifierKeys.Shift))return false;
        var kind=modifiers.HasFlag(ModifierKeys.Shift)?e.Key switch{Key.X=>"strike",Key.M=>"code",Key.P=>"spoiler",Key.D9=>"quote",_=>null}:e.Key switch{Key.B=>"bold",Key.I=>"italic",Key.U=>"underline",_=>null};
        if(kind is null)return false;e.Handled=true;
        FormatComposerSelection(kind, EditComposer.EditorFocused ? EditComposer : Composer);
        return true;
    }
    private void FormatComposerSelection(string kind, ChatEditorHost? target = null)
    {
        var editor = target ?? Composer;
        if (!CanFormatComposer(editor)) return;
        try {
            var result=MsgAT.Windows.Core.Crypto.SecurityCore.Call(new{operation="format_message_selection",text=editor.Text,start=editor.SelectionStart,end=editor.SelectionStart+editor.SelectionLength,kind=kind});
            var text=result.GetProperty("text").GetString()!;var start=result.GetProperty("start").GetInt32();var end=result.GetProperty("end").GetInt32();
            editor.ApplyFormatting(text, start, end-start);
        }catch(InvalidOperationException){TransientNotice.Show(this, AppStrings.Get("UiFormatUnavailable"));}
    }

    private void CancelPreparation_Click(object sender,RoutedEventArgs e){if(sender is FrameworkElement{Tag:string id})_viewModel?.CancelPreparation(id);}
    private void OpenSendQueue(string? peer = null, nint owner = 0)
    {
        if (_services is not { } services || _viewModel is not { } model || _exiting || _appLocked) return;
        var dialog = new SendQueueDialog(services, peer);
        dialog.ConfirmOpenChat = account => ConfirmConversationNavigation(model, account, dialog);
        if (ShowWorkspaceDialog(dialog, owner) && dialog.OpenChatPeer is { } account && !_exiting && !_appLocked &&
            ReferenceEquals(_viewModel, model))
        {
            if (owner != 0 && dialog.OpenMessageId is { } target)
            {
                _qtWorkspace?.RevealConfirmedMessage(account, target, "UiQueueStale");
                return;
            }
            try
            {
                OpenConfirmedContactConversation(model, account);
            }
            catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
            {
                ShowWorkspaceNotice(AppStrings.Get("UiHistoryLoadFailed"));
                return;
            }
            if (model.SelectedConversation?.Uuid != account)
            {
                ShowWorkspaceNotice(AppStrings.Get("UiQueueStale"));
                return;
            }
            if (dialog.OpenMessageId is { } id) JumpWithinChat(id);
        }
    }

    private void OpenForwardingQueue(nint owner = 0)
    {
        if (_appLocked || _exiting || _services is not { } services || _viewModel is not { } model) return;
        (string Peer, string Id)? destination = null;
        var dialog = new ForwardTasksDialog(services, (peer, id) =>
        {
            if (_appLocked || _exiting || !ReferenceEquals(_viewModel, model) ||
                !model.Conversations.Any(item => item.Uuid == peer) ||
                !services.Database.HasHistoryAnchor(peer, id, includeRevoked: false)) return false;
            destination = (peer, id);
            return true;
        });
        dialog.ConfirmOpenChat = peer => ConfirmConversationNavigation(model, peer, dialog);
        if (!ShowWorkspaceDialog(dialog, owner) || destination is not { } target ||
            _appLocked || _exiting || !ReferenceEquals(_viewModel, model) ||
            model.Conversations.FirstOrDefault(item => item.Uuid == target.Peer) is not { } conversation) return;
        if (owner != 0)
        {
            _qtWorkspace?.RevealConfirmedMessage(target.Peer, target.Id, "UiSearchTargetUnavailable");
            return;
        }
        if (model.SelectedConversation?.Uuid != target.Peer) model.SelectedConversation = conversation;
        JumpWithinChat(target.Id);
    }

    private void ChatSendQueue_Click(object sender, RoutedEventArgs e)
    {
        if (_viewModel?.SelectedConversation is { } conversation) OpenSendQueue(conversation.Uuid);
    }
    private async void SendOptions_Click(object sender, RoutedEventArgs e) => await OpenSendOptionsAsync(scheduleDraft: false);
    private async void ScheduleNextMessage_Click(object sender, RoutedEventArgs e) => await OpenSendOptionsAsync(scheduleDraft: true);

    private async Task OpenSendOptionsAsync(bool scheduleDraft, nint owner = 0)
    {
        if (_exiting || _appLocked || _viewModel is not { CanCompose: true, HasMessageEdit: false, SelectedConversation: { } peer } model) return;
        var lockGeneration = _appLockGeneration;
        var dialog = new SendOptionsDialog(model.SendOptions, startScheduled: scheduleDraft);
        if (ShowWorkspaceDialog(dialog, owner) && !_exiting && !_appLocked &&
            _appLockGeneration == lockGeneration && model.CanCompose && !model.HasMessageEdit &&
            _callWindow?.CoversChat != true &&
            ReferenceEquals(_viewModel, model) && ReferenceEquals(model.SelectedConversation, peer))
        {
            if (scheduleDraft && owner != 0) await TrackWorkspaceSend(owner, model.SendDraftAsync(dialog.Result));
            else if (scheduleDraft) await SendComposerDraftAsync(model, dialog.Result);
            else model.SetSendOptions(dialog.Result);
        }
        if (CanRestoreComposerFocus(model, peer.Uuid)) Composer.Focus();
    }
    private void ClearSendOptions_Click(object sender,RoutedEventArgs e)=>_viewModel?.SetSendOptions(new());

    private bool IsComposerSendMenuOpen => ComposerActionButton.ContextMenu?.IsOpen == true;

    private void ComposerSendMenu_Opening(object sender, ContextMenuEventArgs e)
    {
        e.Handled = _exiting || _appLocked || _imeComposing ||
            _viewModel is not { CanCompose: true, HasDraft: true, HasMessageEdit: false, TextSendPending: false } ||
            StickerHost.Child is StickerLibraryPanel { IsBusy: true };
    }

    private void ComposerSendMenu_Opened(object sender, RoutedEventArgs e)
    {
        ResetVisibleReadCandidates();
        UpdateChatActivity(force: true);
    }

    private void ComposerSendMenu_Closed(object sender, RoutedEventArgs e)
    {
        UpdateChatActivity(force: true);
        QueueVisibleReads();
    }

    private async void SendWithoutSound_Click(object sender, RoutedEventArgs e)
    {
        if (_exiting || _appLocked || _imeComposing || _callWindow?.CoversChat == true ||
            _viewModel is not { CanCompose: true, HasDraft: true, HasMessageEdit: false, TextSendPending: false } model ||
            StickerHost.Child is StickerLibraryPanel { IsBusy: true }) return;
        CloseStickerPanel(immediate: true);
        Composer.Focus();
        await SendComposerDraftAsync(model, model.SendOptions with { Silent = true });
    }
    private void CancelMessageEdit()
    {
        if (_viewModel is not { CanCancelEdit: true } model || !ConfirmDiscardMessageEdit(model)) return;
        model.CancelMessageEdit();
        if (CanRestoreComposerFocus(model, model.SelectedConversation?.Uuid)) Composer.Focus();
    }

    private bool ConfirmDiscardMessageEdit(MainViewModel model, Window? owner = null, nint nativeOwner = 0)
    {
        if (!model.HasUnsavedEdit) return true;
        var peer = model.SelectedConversation?.Uuid;
        var target = model.EditTargetId;
        var generation = _appLockGeneration;
        var dialog = new ConfirmationDialog(AppStrings.Get("UiDiscardMessageEdit"), title: model.EditingTitle,
            dismissLabel: AppStrings.Get("UiKeepEditing"), confirmLabel: AppStrings.Get("UiDiscard"));
        var discard = nativeOwner != 0 ? ShowWorkspaceDialog(dialog, nativeOwner) : ShowWithManagedOwner();
        if (generation != _appLockGeneration || _appLocked || _exiting || !ReferenceEquals(_viewModel, model) ||
            !model.IsChatContentVisible || model.SelectedConversation?.Uuid != peer ||
            model.EditTargetId != target || !model.CanCancelEdit) return false;
        if (!discard && IsActive) EditComposer.Focus();
        return discard;

        bool ShowWithManagedOwner()
        {
            dialog.Owner = owner ?? this;
            return dialog.ShowDialog() == true;
        }
    }

    private bool ConfirmConversationNavigation(MainViewModel model, string peer, Window? owner = null, nint nativeOwner = 0)
    {
        if (_exiting || _appLocked || _callWindow?.CoversChat == true || !ReferenceEquals(_viewModel, model)) return false;
        if (model.SelectedConversation?.Uuid == peer || !model.HasMessageEdit) return true;
        return model.CanCancelEdit && ConfirmDiscardMessageEdit(model, owner, nativeOwner);
    }

    private void OpenConfirmedContactConversation(MainViewModel model, string peer)
    {
        if (!_appLocked && !_exiting && _callWindow?.CoversChat != true &&
            ReferenceEquals(_viewModel, model) && model.SelectedConversation?.Uuid != peer)
            model.SelectContact(peer);
    }

    private void SelectConversationRow(ConversationItem target)
    {
        if (_appLocked || _exiting || _viewModel is not { } model || target.Uuid == model.SelectedConversation?.Uuid ||
            !model.CanChangeSelection || !model.VisibleConversations.Contains(target)) return;
        if (model.HasMessageEdit && (!model.CanCancelEdit || !ConfirmDiscardMessageEdit(model))) return;
        if (!_appLocked && !_exiting && model.VisibleConversations.Contains(target)) model.SelectedConversation = target;
    }

    private bool TryConversationNavigationShortcut(WorkspaceKey e)
    {
        if (_imeComposing || _editImeComposing || _replyQuotePicker is not null || IsDropCoveringChat ||
            IsComposerSendMenuOpen || StickerHost.IsHitTestVisible || !ConversationList.IsVisible ||
            _viewModel is not { ShowAttachments: false, CanChangeSelection: true } model) return false;

        var key = e.Key;
        var direction = (e.Modifiers, key) switch
        {
            (ModifierKeys.Alt, Key.Up) or (ModifierKeys.Control, Key.PageUp) => -1,
            (ModifierKeys.Alt, Key.Down) or (ModifierKeys.Control, Key.PageDown) => 1,
            _ => 0
        };
        if (direction == 0) return false;
        e.Handled = true;
        if (e.IsRepeat && model.HasMessageEdit) return true;

        var current = model.SelectedConversation is { } selected ? model.VisibleConversations.IndexOf(selected) : -1;
        var next = current < 0 ? 0 : current + direction;
        if (next >= 0 && next < model.VisibleConversations.Count)
        {
            var target = model.VisibleConversations[next];
            SelectConversationRow(target);
            if (model.SelectedConversation?.Uuid == target.Uuid) ConversationList.ScrollIntoView(target);
        }
        return true;
    }

    private async Task SubmitMessageEditAsync()
    {
        if (_viewModel is not { CanSubmitEdit: true } model) return;
        var peer = model.SelectedConversation?.Uuid;
        await model.SubmitMessageEditAsync();
        if (!CanRestoreComposerFocus(model, peer) ||
            (Keyboard.FocusedElement is not null && !Composer.EditorFocused &&
                !EditComposer.EditorFocused && !ComposerActionButton.IsKeyboardFocusWithin)) return;
        if (model.HasMessageEdit && EditComposer.IsEnabled) EditComposer.Focus();
        else if (Composer.IsEnabled) Composer.Focus();
    }

    private bool TryComposerHistoryShortcut(WorkspaceKey e)
    {
        if (e.Key is not (Key.Up or Key.Down or Key.Escape) || IsRestoringHistory || IsDropCoveringChat || IsComposerSendMenuOpen ||
            _viewModel is not { CanCompose: true, IsChatContentVisible: true } model) return false;
        var modifiers = e.Modifiers;
        if (e.Key == Key.Escape)
        {
            if (modifiers != ModifierKeys.None || model.ReplyTarget is null) return false;
            model.CancelReply();
            ClearJumpHighlight();
            e.Handled = true;
            return true;
        }

        try
        {
            if (modifiers == ModifierKeys.None && e.Key == Key.Up && !e.IsRepeat && model.Draft.Length == 0 &&
                model.ReplyTarget is null && !model.TextSendPending && model.LatestEditableMessage() is { } editable)
            {
                e.Handled = BeginMessageEdit(editable);
                if (e.Handled) JumpWithinChat(editable.MessageId);
                return e.Handled;
            }
            if (modifiers != ModifierKeys.Control || !model.MoveReply(e.Key == Key.Down, out var target)) return false;
            if (target is null) ClearJumpHighlight();
            else ShowJumpTarget(target);
            e.Handled = true;
            return true;
        }
        catch (Exception error) when (error is IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            return false;
        }
    }

    private bool BeginMessageEdit(MessageItem item)
    {
        if (_viewModel is not { } model || _appLocked || _exiting) return false;
        if (model.EditTargetId == item.MessageId)
        {
            EditComposer.Focus();
            return true;
        }
        if (model.HasMessageEdit && (!model.CanCancelEdit || !ConfirmDiscardMessageEdit(model))) return false;
        model.BeginMessageEdit(item);
        if (model.EditTargetId != item.MessageId) return false;
        var peer = item.Source.ConversationUuid;
        Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Input, new Action(() =>
        {
            if (_appLocked || _exiting || !ReferenceEquals(_viewModel, model) ||
                model.SelectedConversation?.Uuid != peer || model.EditTargetId != item.MessageId) return;
            EditComposer.Focus();
            EditComposer.CaretIndex = EditComposer.Text.Length;
        }));
        return true;
    }

    private async void Send_Click(object sender, RoutedEventArgs e)
    {
        if (_exiting || _appLocked || _viewModel is not { } model ||
            StickerHost.Child is StickerLibraryPanel { IsBusy: true }) return;
        CloseStickerPanel(immediate: true);
        if (model.HasMessageEdit)
        {
            await SubmitMessageEditAsync();
            return;
        }
        if (!model.CanCompose) return;
        if (model.HasDraft)
        {
            Composer.Focus();
            await SendComposerDraftAsync(model);
        }
        else { RecordVoice_Click(sender, e); }
    }

    private bool CanRestoreComposerFocus(MainViewModel model, string? peer) =>
        !_exiting && !_appLocked && IsActive && ReferenceEquals(_viewModel, model) &&
        model.IsChatContentVisible && model.SelectedConversation?.Uuid == peer;

    private async Task SendComposerDraftAsync(MainViewModel model, SendOptions? options = null)
    {
        var peer = model.SelectedConversation?.Uuid;
        var navigation = model.HistoryNavigationVersion;
        var wasViewingLatest = model.IsViewingLatest;
        if (await model.SendDraftAsync(options) && wasViewingLatest && model.IsViewingLatest &&
            CanRestoreComposerFocus(model, peer) && model.HistoryNavigationVersion == navigation)
            ScrollToLatest();
    }

    private void ToggleAttachments_Click(object sender, RoutedEventArgs e)
    {
        if (_exiting || _appLocked || _viewModel is not { CanCompose: true } model ||
            StickerHost.Child is StickerLibraryPanel { IsBusy: true }) return;
        CloseStickerPanel(immediate: true);
        model.ShowAttachments = !model.ShowAttachments;
    }

    private int _attachmentAnimationVersion;
    private ContentPresenter? _composerActionIcon;

    private void DismissAttachments_Click(object sender, RoutedEventArgs e)
    {
        if (_viewModel is { } model) model.ShowAttachments = false;
    }

    private void ChatChromePropertyChanged(object? sender, PropertyChangedEventArgs args)
    {
        if (_viewModel is not { } model) return;
        if (args.PropertyName == nameof(MainViewModel.FolderKey) && _lastFolderKey != model.FolderKey)
        {
            var previous = _lastFolderKey;
            _lastFolderKey = model.FolderKey;
            var direction = previous == "archive" ? -1 : 1;
            if (previous != "archive" && model.FolderKey != "archive")
            {
                var tabs = model.FolderChoices.Select(folder => folder.Key).ToList();
                direction = tabs.IndexOf(model.FolderKey) < tabs.IndexOf(previous) ? -1 : 1;
            }
            PlaySidebarEntrance(ConversationFolderContent, 8 * direction, 0);
        }
        if (args.PropertyName is nameof(MainViewModel.HasDraft) or nameof(MainViewModel.HasMessageEdit)
            or nameof(MainViewModel.TextSendPending) or nameof(MainViewModel.IsSavingEdit))
            UpdateComposerAction(model, animate: true);
        if (args.PropertyName == nameof(MainViewModel.SelectedConversation))
        {
            Composer.ClearUndoHistory();
            EditComposer.ClearUndoHistory();
            if (_interruptedRecording is { } recording && recording.Peer != model.SelectedConversation?.Uuid)
                DiscardInterruptedRecording();
            ResetVisibleReadCandidates();
            _attachmentFocusPending = null;
            CloseStickerPanel(immediate: true);
            FinishDropPreparation();
            SetDropRegion(null, immediate: true);
            CloseReplyQuotePicker();
            if (_searchReturn?.TargetPeer != model.SelectedConversation?.Uuid) _searchReturn = null;
            UpdateReturnToSearchButton();
            CloseInlineAudio();
            _inlineAudioSpeed = 1;
            ClearQuoteReturnAnchors();
            ClearJumpHighlight();
            ClearFloatingTimelineDate();
            UpdateChatActivity();
        }
        if (args.PropertyName == nameof(MainViewModel.IsSelecting))
        {
            if (model.IsSelecting) CloseStickerPanel(immediate: true);
            UpdateQuoteReturnButton();
            UpdateReturnToSearchButton();
        }
        if (args.PropertyName == nameof(MainViewModel.CanCompose) && !model.CanCompose)
        {
            CloseStickerPanel(immediate: true);
            FinishDropPreparation();
            SetDropRegion(null, immediate: true);
            model.ShowAttachments = false;
            CloseReplyQuotePicker();
        }
        if (args.PropertyName == nameof(MainViewModel.ShowAttachments)) UpdateAttachmentMenu(model.ShowAttachments);
    }

    private void UpdateComposerAction(MainViewModel model, bool animate)
    {
        var next = model.HasMessageEdit
            ? (model.IsSavingEdit ? PendingActionIcon : SaveEditActionIcon)
            : model.TextSendPending ? PendingActionIcon : model.HasDraft ? SendActionIcon : RecordActionIcon;
        if (animate && ReferenceEquals(_composerActionIcon, next)) return;
        _composerActionIcon = next;
        var useMotion = animate && SystemParameters.ClientAreaAnimation && !_appLocked && !_exiting;
        var duration = new Duration(TimeSpan.FromMilliseconds(120));

        foreach (var icon in new[] { RecordActionIcon, SendActionIcon, SaveEditActionIcon, PendingActionIcon })
        {
            var active = ReferenceEquals(icon, next);
            var targetOpacity = active ? 1.0 : 0.0;
            var targetScale = active ? 1.0 : 0.8;
            var scale = (System.Windows.Media.ScaleTransform)icon.RenderTransform;
            var previousOpacity = icon.Opacity;
            var previousScale = scale.ScaleX;
            icon.Opacity = targetOpacity;
            scale.ScaleX = scale.ScaleY = targetScale;

            DoubleAnimation? Animate(double from, double to) => useMotion
                ? new DoubleAnimation(from, to, duration) { FillBehavior = FillBehavior.Stop }
                : null;

            icon.BeginAnimation(OpacityProperty, Animate(previousOpacity, targetOpacity));
            scale.BeginAnimation(System.Windows.Media.ScaleTransform.ScaleXProperty, Animate(previousScale, targetScale));
            scale.BeginAnimation(System.Windows.Media.ScaleTransform.ScaleYProperty, Animate(previousScale, targetScale));
        }
    }

    private void UpdateAttachmentMenu(bool open)
    {
        if (open) _attachmentFocusPending = null;
        var version = ++_attachmentAnimationVersion;
        var restoreFocus = !open && AttachmentPanel.IsKeyboardFocusWithin;
        var slide = (System.Windows.Media.TranslateTransform)AttachmentPanel.RenderTransform;
        var opacity = AttachmentPanel.Opacity;
        var scrimOpacity = AttachmentScrim.Opacity;
        var offset = slide.Y;
        var rotation = AttachmentToggleRotation.Angle;
        AttachmentPanel.Visibility = AttachmentScrim.Visibility = Visibility.Visible;
        AttachmentPanel.IsEnabled = AttachmentPanel.IsHitTestVisible = open;
        AttachmentPanel.Opacity = AttachmentScrim.Opacity = open ? 1 : 0;
        slide.Y = open ? 0 : 12;
        AttachmentToggleRotation.Angle = open ? 45 : 0;
        UpdateChatVisibility();

        if (SystemParameters.ClientAreaAnimation && !_appLocked && !_exiting)
        {
            DoubleAnimation Motion(double from, double to) => new(from, to,
                new Duration(TimeSpan.FromMilliseconds(open ? 190 : 150)))
            {
                FillBehavior = FillBehavior.Stop,
                EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
            };
            var fade = Motion(opacity, open ? 1 : 0);
            fade.Completed += (_, _) => Finish();
            AttachmentPanel.BeginAnimation(OpacityProperty, fade);
            AttachmentScrim.BeginAnimation(OpacityProperty, Motion(scrimOpacity, open ? 1 : 0));
            slide.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, Motion(offset, open ? 0 : 12));
            AttachmentToggleRotation.BeginAnimation(System.Windows.Media.RotateTransform.AngleProperty, Motion(rotation, open ? 45 : 0));
        }
        else
        {
            AttachmentPanel.BeginAnimation(OpacityProperty, null);
            AttachmentScrim.BeginAnimation(OpacityProperty, null);
            slide.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, null);
            AttachmentToggleRotation.BeginAnimation(System.Windows.Media.RotateTransform.AngleProperty, null);
            Finish();
        }

        if (open)
            Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Input, new Action(() =>
            {
                if (version == _attachmentAnimationVersion && IsActive && !_appLocked && !_exiting) FirstAttachmentAction.Focus();
            }));
        else if (restoreFocus && IsActive && !_appLocked && !_exiting && AttachmentToggle.IsVisible && AttachmentToggle.IsEnabled)
            AttachmentToggle.Focus();

        void Finish()
        {
            if (open || version != _attachmentAnimationVersion) return;
            AttachmentPanel.Visibility = AttachmentScrim.Visibility = Visibility.Collapsed;
            UpdateChatVisibility();
            if (_attachmentFocusPending is { } pending)
            {
                _attachmentFocusPending = null;
                RestoreComposerAfterAttachment(pending.Model, pending.Peer, pending.LockGeneration);
            }
        }
    }

    private static string[]? ClipboardFiles(IDataObject data) =>
        data.GetDataPresent(DataFormats.FileDrop, autoConvert: false) &&
        data.GetData(DataFormats.FileDrop, autoConvert: false) is string[] { Length: > 0 } files
            ? files : null;

    private sealed class ClipboardBuffer : MemoryStream
    {
        private const long Maximum=100L*1024*1024;
        private static void Check(long size) { if(size<0 || size>Maximum) throw new InvalidDataException("Image exceeds the file size limit."); }
        public override long Position { get=>base.Position; set { Check(value); base.Position=value; } }
        public override void Write(byte[] buffer,int offset,int count) { Check(checked(Position+count)); base.Write(buffer,offset,count); }
        public override void Write(ReadOnlySpan<byte> buffer) { Check(checked(Position+buffer.Length)); base.Write(buffer); }
        public override void WriteByte(byte value) { Check(checked(Position+1)); base.WriteByte(value); }
        public override void SetLength(long value) { Check(value); base.SetLength(value); }
        public override long Seek(long offset,SeekOrigin origin)
        {
            var value=checked((origin switch { SeekOrigin.Begin=>0,SeekOrigin.Current=>Position,SeekOrigin.End=>Length,_=>throw new ArgumentOutOfRangeException(nameof(origin)) })+offset);
            Check(value); return base.Seek(offset,origin);
        }
    }

    private bool HasComposerMediaPaste()
    {
        if (_appLocked || _exiting || _imeComposing || Composer.IsReadOnly || _viewModel is not { CanCompose: true, SelectedConversation: not null }) return false;
        try
        {
            var data = Clipboard.GetDataObject();
            return data is not null && (data.GetDataPresent(DataFormats.Bitmap) ||
                data.GetDataPresent("PNG", autoConvert: false) || data.GetDataPresent(DataFormats.FileDrop, autoConvert: false));
        }
        catch (ExternalException) { return false; }
    }

    private async void PasteComposerMedia() => await PasteComposerMediaAsync();

    private async Task PasteComposerMediaAsync(bool imageOnly = false)
    {
        if (_exiting || _appLocked || _imeComposing ||
            _viewModel is not { CanCompose: true, SelectedConversation: not null } model) return;
        try
        {
            var data = Clipboard.GetDataObject();
            if (data is null) return;
            if (!imageOnly && ClipboardFiles(data) is { } files)
            {
                await ReviewExternalFilesAsync(files, asFile: false);
                return;
            }
            var pngData = data.GetData("PNG", autoConvert: false);
            if (pngData is MemoryStream oversizedStream && oversizedStream.Length > 100L * 1024 * 1024 ||
                pngData is byte[] oversizedBytes && oversizedBytes.Length > 100L * 1024 * 1024)
            {
                ShowWorkspaceNotice(AppStrings.Get("UiAttachmentSizeLimit"));
                return;
            }
            byte[]? png = pngData switch
            {
                MemoryStream stream when stream.Length <= 100L * 1024 * 1024 => stream.ToArray(),
                byte[] bytes when bytes.Length <= 100L * 1024 * 1024 => bytes.ToArray(),
                _ => null
            };
            var bitmap = png is null && data.GetDataPresent(DataFormats.Bitmap) ? Clipboard.GetImage() : null;
            if (png is null && bitmap is null) return;
            var send = model.CaptureImageSender();
            if (send is null)
            {
                if (png is not null) CryptographicOperations.ZeroMemory(png);
                return;
            }
            model.ShowAttachments = false;
            var lockGeneration = _appLockGeneration;
            bitmap?.Freeze();
            try
            {
                if(png is null) png=await Task.Run(() =>
                {
                    if((long)bitmap!.PixelWidth*bitmap.PixelHeight>64L*1024*1024) throw new InvalidDataException("Image dimensions exceed the decoding limit.");
                    using var stream = new ClipboardBuffer();
                    try
                    {
                        var encoder = new PngBitmapEncoder();
                        encoder.Frames.Add(BitmapFrame.Create(bitmap!));
                        encoder.Save(stream);
                        return stream.ToArray();
                    }
                    finally { if(stream.TryGetBuffer(out var memory)) CryptographicOperations.ZeroMemory(memory.AsSpan()); }
                });
                else
                {
                    var imageBytes = png;
                    await Task.Run(() =>
                    {
                        ReadOnlySpan<byte> signature = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
                        if (!imageBytes.AsSpan().StartsWith(signature))
                            throw new InvalidDataException("Invalid clipboard PNG.");
                        using var input = new MemoryStream(imageBytes, writable: false);
                        _ = ImageDecoder.Load(input, 64);
                    });
                }
                if (!_exiting && !_appLocked && lockGeneration == _appLockGeneration) await send(png);
            }
            finally
            {
                if(png is not null) CryptographicOperations.ZeroMemory(png);
            }
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or NotSupportedException or ArgumentException or System.Runtime.InteropServices.ExternalException)
        {
            ShowWorkspaceNotice(AppStrings.Get("UiPasteImageFailed"));
        }
    }

    private void Media_Click(object sender, RoutedEventArgs e) => ChooseChatFiles((sender as FrameworkElement)?.Tag as string);

    private void ChooseChatFiles(string? category)
    {
        if (_exiting || _appLocked || _viewModel is not { CanCompose: true, SelectedConversation: { } peer } model ||
            model.CaptureConfirmedMediaBatchSender() is not { } send) return;
        var options = model.SendOptions;
        var lockGeneration = _appLockGeneration;
        var (label, extensions) = category switch
        {
            "Visual" => ("UiPhotoVideo", "*.png;*.jpg;*.jpeg;*.gif;*.webp;*.bmp;*.heic;*.heif;*.avif;*.mp4;*.mov;*.webm;*.mkv;*.m4v"),
            "Audio" => ("UiAudio", "*.mp3;*.m4a;*.ogg;*.opus;*.wav;*.aac;*.flac;*.wma"),
            _ => ("UiFiles", "*.*")
        };
        model.ShowAttachments = false;
        var picker = new OpenFileDialog { Filter = $"{AppStrings.Get(label)}|{extensions}", Multiselect = true };
        var picked = picker.ShowDialog(this) == true;
        if (_exiting || _appLocked || lockGeneration != _appLockGeneration ||
            !ReferenceEquals(model, _viewModel) || !ReferenceEquals(model.SelectedConversation, peer)) return;
        if (picked) ReviewMedia(picker.FileNames, peer, options, send, asFile: category == "Files");
        else RestoreComposerAfterAttachment(model, peer.Uuid, lockGeneration);
    }

    private async void VoiceCall_Click(object sender, RoutedEventArgs e) => await StartCallAsync(video: false);
    private async void VideoCall_Click(object sender, RoutedEventArgs e) => await StartCallAsync(video: true);

    private async Task StartCallAsync(bool video)
    {
        if (_exiting || _appLocked || _viewModel?.SelectedConversation is not { } peer || _services is null) return;
        _viewModel.ShowAttachments = false;
        try { await _services.Calls.StartOutgoingAsync(peer.Uuid, video); }
        catch (InvalidOperationException exception) { TransientNotice.Show(this, exception.Message); }
    }

    private void ShareContact_Click(object sender, RoutedEventArgs e) => OpenShareContact();

    private void OpenShareContact(nint owner = 0)
    {
        if (_exiting || _appLocked || _services is not { } services ||
            _viewModel is not { SelectedConversation: { } peer } model ||
            model.CaptureContactCardSender() is not { } send) return;
        model.ShowAttachments = false;
        var generation = _appLockGeneration;
        var dialog = new ContactCardPickerDialog(services, peer.Alias, model.SendOptions,
            (account, name, options) => SubmitSharedCard(model, peer, generation, owner, () => send(account, name, options)));
        ShowWorkspaceDialog(dialog, owner);
    }

    private void ShareLocation_Click(object sender, RoutedEventArgs e) => OpenShareLocation();

    private void OpenShareLocation(nint owner = 0)
    {
        if (_exiting || _appLocked || _viewModel is not { SelectedConversation: { } peer } model ||
            model.CaptureLocationSender() is not { } send || model.CaptureLiveLocationSender() is not { } share) return;
        model.ShowAttachments = false;
        var generation = _appLockGeneration;
        var dialog = new LocationSendDialog(peer.Alias, model.SendOptions,
            (point, label, options) => SubmitSharedCard(model, peer, generation, owner, () => send(point, label, options)),
            (point, duration, label, silent) => SubmitSharedCard(model, peer, generation, owner, () => share(point, duration, label, silent)));
        ShowWorkspaceDialog(dialog, owner);
    }

    private Task<bool> SubmitSharedCard(MainViewModel model, ConversationItem peer, long generation, nint owner, Func<Task<bool>> send) =>
        _exiting || _appLocked || generation != _appLockGeneration || !ReferenceEquals(_viewModel, model) ||
        !ReferenceEquals(model.SelectedConversation, peer) ? Task.FromResult(false) : TrackWorkspaceSend(owner, send());

    private Task<bool> TrackWorkspaceSend(nint owner, Task<bool> operation)
    {
        if (owner != 0) _qtWorkspace?.TrackMessageAction(operation);
        return operation;
    }

    private async void StopLiveLocation_Click(object sender, RoutedEventArgs e)
    {
        if (_exiting || _appLocked || _viewModel is not { } model ||
            model.SelectedConversation is not { } conversation || sender is not Button button) return;
        button.IsEnabled = false;
        try
        {
            if (!await model.StopCurrentLiveLocationAsync() && !_exiting && !_appLocked &&
                ReferenceEquals(_viewModel, model) && ReferenceEquals(model.SelectedConversation, conversation))
                TransientNotice.Show(this, AppStrings.Get("UiLiveLocationStopError"));
        }
        finally { button.IsEnabled = true; }
    }

    private void MessageCard_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: MessageItem message }) OpenMessageCard(message);
    }

    private void OpenMessageCard(MessageItem message, nint owner = 0)
    {
        if (_exiting || _appLocked || _services is not { } services || _viewModel is not { IsSelecting: false } model ||
            model.SelectedConversation?.Uuid != message.Source.ConversationUuid) return;
        if (message.LinkPreview is not null)
        {
            ShowWorkspaceDialog(new ChatWebPreviewDialog(services, message), owner);
            return;
        }
        if (message.IsLiveLocation)
        {
            var peer = message.Source.ConversationUuid;
            var id = message.MessageId;
            var dialog = new LocationDetailsDialog(message.Content, services.Database, () => services.Messaging.LiveLocation(peer, id),
                message.IsMine ? () => services.LiveLocations.Foreground && services.LiveLocations.Current(peer)?.MessageId == id : null,
                message.IsMine ? () => TrackWorkspaceSend(owner, services.LiveLocations.StopAsync(peer, id)) : null);
            ShowWorkspaceDialog(dialog, owner);
            return;
        }
        if (message.IsLocation)
        {
            ShowWorkspaceDialog(new LocationDetailsDialog(message.Content, () =>
                services.Database.GetLocationPoint(message.Source.ConversationUuid, message.MessageId)), owner);
            return;
        }
        if (!message.IsContactCard) return;
        var detail = new ContactCardDetailsDialog(services, message.Source.ConversationUuid, message.MessageId, message.Content);
        detail.ConfirmOpenChat = account => ConfirmConversationNavigation(model, account, detail);
        if (ShowWorkspaceDialog(detail, owner) && detail.OpenAccount is { } account) OpenConfirmedContactConversation(model, account);
    }

    private void ReviewMedia(IReadOnlyList<string> paths, ConversationItem peer, SendOptions options,
        Func<IReadOnlyList<string>, MediaSendChoice, Action<string>?, Task<PreparationSendResult>> send, bool asFile)
    {
        if (_exiting || _appLocked || _viewModel is not { CanCompose: true } model ||
            !ReferenceEquals(model.SelectedConversation, peer)) return;
        var lockGeneration = _appLockGeneration;
        MediaSendDialog.ReviewAndSendFiles(this, paths, peer.Alias, options,
            (items, choice, started) =>
            {
                if (_exiting || _appLocked || lockGeneration != _appLockGeneration ||
                    !ReferenceEquals(_viewModel, model) || !ReferenceEquals(model.SelectedConversation, peer))
                    return Task.FromResult(PreparationSendResult.Cancelled);
                return send(items, choice, started);
            },
            model, initialAsFile: asFile);
        if (_exiting || _appLocked || lockGeneration != _appLockGeneration ||
            !ReferenceEquals(_viewModel, model) || !ReferenceEquals(model.SelectedConversation, peer)) return;
        RestoreComposerAfterAttachment(model, peer.Uuid, lockGeneration);
    }

    private (MainViewModel Model, string Peer, long LockGeneration)? _attachmentFocusPending;

    private void RestoreComposerAfterAttachment(MainViewModel model, string peer, long lockGeneration)
    {
        if (AttachmentPanel.Visibility == Visibility.Visible && !model.ShowAttachments &&
            lockGeneration == _appLockGeneration && model.SelectedConversation?.Uuid == peer)
        {
            _attachmentFocusPending = (model, peer, lockGeneration);
            return;
        }
        Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Input, new Action(() =>
        {
            if (lockGeneration == _appLockGeneration && CanRestoreComposerFocus(model, peer) &&
                Composer.IsVisible && Composer.IsEnabled) Composer.Focus();
        }));
    }

    private async void AddFriend_Click(object sender, RoutedEventArgs e) => await OpenAddFriendAsync();

    private async Task OpenAddFriendAsync(nint owner = 0)
    {
        if (_appLocked || _exiting || _viewModel is not { } model) return;
        var dialog = new TextPromptDialog(AppStrings.Get("UiAddFriend"), AppStrings.Get("UiContactPublicId"), myUuid: model.MyUuid);
        if (ShowWorkspaceDialog(dialog, owner) && !_appLocked && !_exiting && ReferenceEquals(_viewModel, model))
            await model.AddFriendAsync(dialog.Value);
    }

    private void Requests_Click(object sender, RoutedEventArgs e)
    {
        _ = sender;
        _ = e;
        if (_viewModel is not null) { new FriendRequestsDialog(_viewModel) { Owner = this }.ShowDialog(); }
    }

    private void OpenStorageDialog(Window owner)
    {
        if (_services is null || _exiting) return;
        var dialog = new AttachmentCacheDialog(_services) { Owner = owner };
        dialog.ShowDialog();
        if (dialog.Cleared && MediaOverlayHost.Content is MediaOverlay { IsMine: false }) CloseChatMedia();
    }

    private void EditProfile_Click(object sender, RoutedEventArgs e) => EditProfile(this);

    private SecureSettings? EditProfile(Window owner)
    {
        if (_services is not { } services || _exiting || _appLocked) return null;
        var current = services.Settings.Snapshot;
        var editor = new IdentitySetupDialog((name, avatar, remove) => SaveProfileAsync(services, name, avatar, remove),
            current.Nickname ?? "", current.AvatarPath, editing: true) { Owner = owner };
        var accepted = editor.ShowDialog() == true;
        return accepted && !_appLocked && !_exiting ? services.Settings.Snapshot : null;
    }

    private Task<bool> SaveProfileAsync(AppServices services, string nickname, ReadOnlyMemory<byte>? avatar, bool remove) =>
        _exiting || _appLocked || !ReferenceEquals(services, _services)
            ? Task.FromResult(false)
            : services.Messaging.UpdateProfileAsync(nickname, avatar, remove);

    private async void Settings_Click(object sender, RoutedEventArgs e) => await OpenSettingsAsync();

    private async Task OpenSettingsAsync(nint owner = 0)
    {
        if (_appLocked || _exiting || _viewModel is null || _services is not { } services) return;
        var dialog = new SettingsDialog(services.Settings.Snapshot, services.Settings.MyUuid, EditProfile,
            parent => new ReadReceiptPrivacyDialog(services) { Owner = parent }.ShowDialog(), OpenStorageDialog,
            _chatTextPercentage, SetInterfaceTextSize, SetInterfaceTheme, SetInterfaceLanguage,
            openNotifications: parent => new MessageNotificationsDialog(services) { Owner = parent }.ShowDialog(),
            closeToTray: _closeToTray, changeCloseToTray: SetCloseToTray, openExportFolder: parent =>
            {
                if (!_appLocked && !_exiting && ReferenceEquals(_services, services))
                    new AttachmentExportFolderDialog(services.Settings) { Owner = parent }.ShowDialog();
            });
        var accepted = ShowWorkspaceDialog(dialog, owner);
        if (_exiting || _appLocked || !ReferenceEquals(_services, services)) return;
        try
        {
            if (dialog.ResetRequested) await ResetIdentityFromSettingsAsync(services, owner);
            else if (accepted && dialog.Result is not null)
            {
                await services.SaveNetworkSettingsAsync(dialog.Result);
            }
        }
        catch (Exception exception)
        {
            Trace.TraceError("Settings operation failed: {0}", exception);
            if (!_exiting) { TransientNotice.Show(this, exception.Message); }
            if (services.IsDisposed) { ((App)System.Windows.Application.Current).ExitApplication(); }
        }
        finally
        {
            if (!_exiting) { IsEnabled = true; }
        }
    }

    private void SetInterfaceTextSize(int percentage)
    {
        ApplyChatTextPercentage(percentage);
        SaveWindowPlacement();
    }

    private void SetInterfaceTheme(AppThemeMode mode)
    {
        WindowAppearance.SetTheme(mode);
        SaveWindowPlacement();
    }

    private void SetInterfaceLanguage(AppLanguage language)
    {
        AppStrings.SetLanguage(language);
        _viewModel?.RefreshFolderChoices();
        _viewModel?.RefreshMessageLanguage();
        if (FloatingTimelineDate.Tag is DateTime timelineDate)
            FloatingTimelineDate.Content = AppStrings.TimelineDate(timelineDate);
        _viewModel?.RefreshSendOptionsLanguage();
        SaveWindowPlacement();
    }

    private async Task ResetIdentityFromSettingsAsync(AppServices services, nint owner)
    {
        var generation = _appLockGeneration;
        if (!ShowWorkspaceDialog(new ConfirmationDialog(AppStrings.Get("UiResetWarningRestart")), owner) ||
            _exiting || _appLocked || generation != _appLockGeneration || !ReferenceEquals(_services, services)) return;
        await CommitIdentityResetAsync(services);
    }

    private async Task CommitIdentityResetAsync(AppServices services)
    {
        IsEnabled = false;
        try { await services.ResetIdentityAndRestartAsync(); }
        finally { if (!_exiting) IsEnabled = true; }
    }

    private ContextMenu? CreateConversationMenu(ConversationItem conversation)
    {
        if (_appLocked || _exiting || _viewModel is not { } model) return null;
        var menu = new ContextMenu();
        bool CanUseMenu() => !_appLocked && !_exiting && ReferenceEquals(model, _viewModel);

        var folderId = model.CurrentCustomFolderId;
        var pinned = conversation.PinOrderIn(folderId) > 0;
        void Action(string title, string action, long? until = null, string? pinFolder = null)
        {
            var item = new MenuItem { Header = title };
            item.Click += async (_, _) =>
            {
                if (CanUseMenu()) await model.ChangeConversationAsync(conversation.Uuid, action, until, pinFolder);
            };
            menu.Items.Add(item);
        }
        Action(AppStrings.Get(pinned ? "UiUnpinChat" : "UiPinChat"), pinned ? "unpin" : "pin", pinFolder: folderId);
        if (pinned)
        {
            var scope = model.FolderKey;
            var pins = model.VisibleConversations.TakeWhile(row => row.IsPinned).ToList();
            var index = pins.IndexOf(conversation);
            void Move(string title, ConversationItem target, bool before)
            {
                var item = new MenuItem { Header = title };
                item.Click += async (_, _) =>
                {
                    if (CanUseMenu()) await model.MovePinAsync(scope, conversation.Uuid, target.Uuid, before);
                };
                menu.Items.Add(item);
            }
            if (index > 0) Move(AppStrings.Get("UiMovePinUp"), pins[index - 1], true);
            if (index >= 0 && index < pins.Count - 1) Move(AppStrings.Get("UiMovePinDown"), pins[index + 1], false);
        }
        var wasArchived = conversation.Archived;
        var archive = new MenuItem
        {
            Header = AppStrings.Get(wasArchived ? "UiUnarchiveChat" : "UiArchiveChat"),
            IsEnabled = !_archivePending
        };
        archive.Click += async (_, _) =>
        {
            if (CanUseMenu()) await ChangeArchiveAsync(conversation.Uuid, !wasArchived);
        };
        menu.Items.Add(archive);
        Action(AppStrings.Get(conversation.HasUnread ? "UiMarkChatRead" : "UiMarkChatUnread"), conversation.HasUnread ? "read" : "unread");
        var notifications = new MenuItem { Header = AppStrings.Get("UiConversationNotifications") };
        AddConversationMuteOptions(notifications.Items, conversation);
        menu.Items.Add(notifications);
        menu.Items.Add(new Separator());

        void AddDestructiveAction(string labelKey, string confirmationKey, Func<Task> apply)
        {
            var label = AppStrings.Get(labelKey);
            var item = new MenuItem { Header = label };
            item.SetResourceReference(Control.ForegroundProperty, "DangerBrush");
            item.Click += async (_, _) =>
            {
                if (!CanUseMenu()) return;
                var dialog = new ConfirmationDialog(AppStrings.Format(confirmationKey, conversation.Alias), label,
                    confirmLabel: label) { Owner = this };
                if (dialog.ShowDialog() == true && CanUseMenu()) await apply();
            };
            menu.Items.Add(item);
        }
        AddDestructiveAction("UiClearMessages", "UiClearMessagesConfirm", () => model.ClearMessagesAsync(conversation.Uuid));
        AddDestructiveAction("UiDeleteFriend", "UiDeleteFriendConfirm", () => model.DeleteFriendAsync(conversation.Uuid));
        return menu;
    }

    private void PerformHeaderAction(ChatAction action)
    {
        if (_appLocked || _exiting || _viewModel?.SelectedConversation is null || _callWindow?.CoversChat == true) return;
        var args = new RoutedEventArgs();
        switch (action)
        {
            case ChatAction.Profile: OpenContactProfile_Click(this, args); break;
            case ChatAction.Voice: VoiceCall_Click(this, args); break;
            case ChatAction.Video: VideoCall_Click(this, args); break;
            case ChatAction.Search: OpenMessageSearch(); break;
            case ChatAction.History: OpenHistoryNavigation(); break;
            case ChatAction.Queue: ChatSendQueue_Click(this, args); break;
            case ChatAction.Forward: ForwardSelected_Click(this, args); break;
            case ChatAction.Save: SaveSelected_Click(this, args); break;
            case ChatAction.Copy: CopySelected_Click(this, args); break;
            case ChatAction.Delete: DeleteSelected_Click(this, args); break;
            case ChatAction.Cancel: ClearSelection_Click(this, args); break;
        }
    }

    private ContextMenu? CreateNotificationMenu()
    {
        if (_appLocked || _exiting || _viewModel?.SelectedConversation is not { } peer) return null;
        var menu = new ContextMenu();
        AddConversationMuteOptions(menu.Items, peer);
        return menu;
    }

    private void AddConversationMuteOptions(ItemCollection items, ConversationItem conversation)
    {
        if (_viewModel is not { } model) return;
        void AddAction(string key, string action, TimeSpan? duration = null)
        {
            var option = new MenuItem { Header = AppStrings.Get(key) };
            option.Click += async (_, _) =>
            {
                if (_exiting || _appLocked) return;
                long? until = duration is { } period ? DateTimeOffset.UtcNow.Add(period).ToUnixTimeMilliseconds() : null;
                await model.ChangeConversationAsync(conversation.Uuid, action, until);
            };
            items.Add(option);
        }
        if (conversation.IsMuted)
        {
            var culture = System.Globalization.CultureInfo.CurrentCulture;
            var status = conversation.MuteUntil == ConversationIndexStore.PermanentMuteUntil
                ? AppStrings.Get("UiConversationMuted")
                : AppStrings.Format("UiConversationMutedUntil",
                    DateTimeOffset.FromUnixTimeMilliseconds(conversation.MuteUntil).LocalDateTime.ToString("g", culture));
            items.Add(new MenuItem { Header = status, IsEnabled = false });
            AddAction("UiUnmute", "unmute");
            items.Add(new Separator());
        }
        AddAction("UiConversationMuteOneHour", "mute", TimeSpan.FromHours(1));
        AddAction("UiConversationMuteEightHours", "mute", TimeSpan.FromHours(8));
        AddAction("UiConversationMuteTwoDays", "mute", TimeSpan.FromDays(2));
        AddAction("UiConversationMuteUntilEnabled", "mute");
    }

    private void Message_PreviewMouseRightButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (sender is ListBoxItem container)
            e.Handled = OpenMessageMenu(container, e.OriginalSource as DependencyObject, keyboard: false);
    }

    private async void Message_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (sender is not ListBoxItem { DataContext: MessageItem item } container ||
            _exiting || _appLocked || _callWindow?.CoversChat == true || MediaOverlayHost.Content is not null ||
            _viewModel is not { IsChatContentVisible: true } model) return;
        var key = e.Key == Key.System ? e.SystemKey : e.Key;
        var modifiers = Keyboard.Modifiers;
        var source = e.OriginalSource as DependencyObject;
        if ((key == Key.Apps && modifiers == ModifierKeys.None) ||
            (key == Key.F10 && modifiers == ModifierKeys.Shift))
        {
            e.Handled = e.IsRepeat || OpenMessageMenu(container, source, keyboard: true);
            return;
        }

        var attachment = source as AttachmentPreview;
        if (item.HasAlbum && modifiers == ModifierKeys.None && key is (Key.Left or Key.Right) &&
            (attachment is not null || ReferenceEquals(source, container)))
        {
            var members = item.AlbumMembers;
            var memberIndex = 0;
            if (attachment?.Message is { } focused)
            {
                while (memberIndex < members.Count && !ReferenceEquals(members[memberIndex], focused)) memberIndex++;
                memberIndex += key == Key.Left ? -1 : 1;
            }
            else if (key == Key.Left) memberIndex = members.Count - 1;
            if (memberIndex >= 0 && memberIndex < members.Count &&
                AttachmentPreview.FindIn(container, members[memberIndex].MessageId, albumOnly: true) is { } tile)
                e.Handled = tile.Focus();
            return;
        }
        if (key is not (Key.Return or Key.Space) ||
            (modifiers != ModifierKeys.None && !(model.IsSelecting && modifiers == ModifierKeys.Shift))) return;
        if (model.IsSelecting)
        {
            if (attachment?.Message is { } member)
                item = member;
            else if (!ReferenceEquals(source, container) || item.HasAlbum)
                return;
            e.Handled = true;
            if (!e.IsRepeat)
                model.ToggleSelection(item, extending: modifiers == ModifierKeys.Shift);
            return;
        }
        if (attachment is null && ReferenceEquals(source, container) && item.IsFileCard && item.HasFileAction)
            attachment = AttachmentPreview.FindIn(container, item.MessageId);
        if (attachment?.Message is not { IsAttachment: true, IsRevoked: false } media || media.IsFileCard && !media.HasFileAction) return;
        e.Handled = true;
        if (!e.IsRepeat) await OpenAttachmentAsync(media, attachment);
    }

    private static Grid? FindAlbumTile(ListBoxItem container, DependencyObject? source)
    {
        for (var element = source; element is not null && !ReferenceEquals(element, container);)
        {
            if (element is Grid { Name: "AlbumTile" } tile) return tile;
            element = element switch
            {
                System.Windows.Media.Visual visual => System.Windows.Media.VisualTreeHelper.GetParent(visual),
                FrameworkContentElement content => ContentOperations.GetParent(content) ?? content.Parent,
                _ => null
            };
        }
        return null;
    }

    private static MenuItem MessageMenuItem(string resource, string glyph, bool enabled = true)
        => new()
        {
            Header = AppStrings.Get(resource),
            IsEnabled = enabled,
            Icon = new TextBlock
            {
                Text = glyph,
                FontFamily = new System.Windows.Media.FontFamily("Segoe MDL2 Assets"),
                FontSize = 14,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            }
        };

    private bool OpenMessageMenu(ListBoxItem container, DependencyObject? source, bool keyboard)
    {
        if (_appLocked || _exiting || MediaOverlayHost.Content is not null || _callWindow?.CoversChat == true ||
            container.DataContext is not MessageItem item || _viewModel is null) return false;
        if (!_viewModel.IsSelecting && MessageLinks.HasOwnMenu(source, container)) return false;
        var rowItem = item;
        var albumTile = item.HasAlbum ? FindAlbumTile(container, source) : null;
        if (albumTile?.DataContext is MessageItem member) item = member;
        var menu = new ContextMenu();
        var destructive = new List<MenuItem>();
        if(!item.IsRevoked && item.Extension is "txt" or "attachment" or "contact" or "location")
        {
            var select = MessageMenuItem(item.IsSelected ? "UiDeselectMessage" : "UiSelect",
                item.IsSelected ? "\uE73E" : "\uE739", _viewModel.CanChangeSelection);
            select.Click += (_, _) =>
            {
                if (_appLocked || _exiting || _viewModel is not { } model ||
                    model.SelectedConversation?.Uuid != item.Source.ConversationUuid) return;
                if (model.HasMessageEdit && (!model.CanCancelEdit || !ConfirmDiscardMessageEdit(model))) return;
                model.ToggleSelection(item);
            };
            menu.Items.Add(select);
            if (!item.HasExpiration)
            {
                var forward = MessageMenuItem("UiForward", "\uE72A", _viewModel.CanChangeSelection);
                forward.Click+=(_,_)=>ForwardMessages([item.MessageId]);menu.Items.Add(forward);
                var save = MessageMenuItem("UiSaveMessage", "\uE734", _viewModel.CanChangeSelection);
                save.Click+=async (_,_)=>await SaveMessagesAsync([item.MessageId]);menu.Items.Add(save);
            }
            if(_services is not null){
                var peer=item.Source.ConversationUuid;
                var pinned=_services.Database.IsMessagePinned(peer,_services.Settings.MyUuid,item.MessageId);
                var pin = MessageMenuItem(pinned ? "UiUnpinMessage" : "UiPinMessage", pinned ? "\uE77A" : "\uE718");
                pin.Click += async (_, _) => await ChangeMessagePinAsync(item, pinned);
                menu.Items.Add(pin);
            }
        }
        if (!item.IsRevoked)
        {
            var reply = MessageMenuItem("UiReply", "\uE97A");
            reply.Click += (_, _) => TryReplyToMessage(item);
            menu.Items.Insert(0, reply);
        }
        if (!item.IsRevoked && !item.HasExpiration && item.IsText && _viewModel.CanCompose)
        {
            var quote = MessageMenuItem("UiQuoteSelectedText", "\uE8A4");
            quote.Click += async (_, _) => await QuoteMessageTextAsync(item);
            menu.Items.Add(quote);
        }
        var copyItem = rowItem.HasAlbum && albumTile is null
            ? rowItem.AlbumMembers.FirstOrDefault(member => member.AttachmentInfo?.Album?.Index == 0) ?? item
            : item;
        var textToCopy = copyItem.IsText ? copyItem.Content : copyItem.IsAttachment ? copyItem.Caption : "";
        if (!copyItem.IsRevoked && !string.IsNullOrWhiteSpace(textToCopy))
        {
            var lockGeneration = _appLockGeneration;
            var copy = MessageMenuItem(copyItem.IsText ? "UiCopyMessageText" : "UiCopyAttachmentCaption", "\uE8C8");
            copy.Click += async (_, _) =>
            {
                bool CanPublish() => !_appLocked && !_exiting && lockGeneration == _appLockGeneration &&
                    container.IsLoaded && ReferenceEquals(container.DataContext, rowItem) &&
                    (ReferenceEquals(rowItem, copyItem) || rowItem.AlbumMembers.Contains(copyItem)) &&
                    _viewModel?.SelectedConversation?.Uuid == copyItem.Source.ConversationUuid &&
                    !copyItem.IsRevoked && (copyItem.IsText ? copyItem.Content : copyItem.Caption) == textToCopy;
                if (!CanPublish()) return;
                var revealed = copyItem.IsText ? FindMessageBody(container, copyItem.DisplayContent)?.RevealedSpoilers : null;
                try
                {
                    var visible = await MessageTextBlock.CopyableTextAsync(textToCopy, revealed);
                    if (CanPublish()) MessageTextBlock.CopyToClipboard(visible, this);
                }
                catch (Exception error) when (error is InvalidOperationException or JsonException or KeyNotFoundException)
                {
                    if (CanPublish()) TransientNotice.Show(this, AppStrings.Get("UiMessageCopyFailed"));
                }
            };
            menu.Items.Add(copy);
        }
        if (item.CanModify)
        {
            if (item.CanRetrySend && !_viewModel.IsSelecting)
            {
                var retrySend = MessageMenuItem("UiSendRetry", "\uE72C", !_retryingSends.Contains(item.MessageId));
                retrySend.Click += async (_, _) => await RetrySendAsync(item);
                menu.Items.Add(retrySend);
            }
            if (item.IsSending && !_viewModel.IsSelecting)
            {
                var cancelSend = MessageMenuItem("UiSendCancelAction", "\uE711", !_sendCancellationPending);
                cancelSend.Click += async (_, _) => await CancelQueuedSendAsync(item);
                destructive.Add(cancelSend);
            }
            var revoke = MessageMenuItem("UiRevoke", "\uE7A7");
            revoke.Click += async (_, _) => await _viewModel.RevokeAsync(item); destructive.Add(revoke);
            if (item.CanEditContent)
            {
                var edit = MessageMenuItem(item.IsAttachment ? "UiEditCaption" : "UiEditMessage", "\uE70F");
                edit.Click += (_, _) => BeginMessageEdit(item);
                menu.Items.Add(edit);
            }
        }
        if (item.CanRequestDownload || item.CanPauseDownload)
        {
            var download = MessageMenuItem(item.CanRetryDownload ? "UiRetry" : item.DownloadActionKey,
                item.CanPauseDownload ? "\uE769" : item.CanRetryDownload ? "\uE72C" : "\uE896");
            download.Click += async (_, _) => await OpenAttachmentAsync(item);
            menu.Items.Add(download);
        }
        else if (item.IsAttachment && item.IsAvailable)
        {
            if (!item.HasExpiration)
            {
                var save = MessageMenuItem("UiSaveAttachment", "\uE74E");
                save.Click += (_, _) => SaveAttachment(item);
                menu.Items.Add(save);
            }
            if (!item.IsMine)
            {
                var downloadAgain = MessageMenuItem("UiDownloadAttachmentAgain", "\uE72C");
                downloadAgain.Click += async (_, _) =>
                {
                    if (_appLocked || _exiting || _services is not { } services ||
                        !Confirm(AppStrings.Get("UiDownloadAttachmentAgainConfirm")) ||
                        _appLocked || _exiting || _viewModel?.SelectedConversation?.Uuid != item.Source.ConversationUuid)
                        return;
                    await services.Messaging.ReplaceAttachmentCacheAsync(item.Source.ConversationUuid, item.MessageId);
                };
                menu.Items.Add(downloadAgain);
            }
        }
        if (item.HasExpiration) menu.Items.Insert(0, new MenuItem { Header = item.ExpirationLabel, IsEnabled = false });
        if (!_viewModel.IsSelecting && !item.IsSending && !item.IsLiveLocation)
        {
            var delete = MessageMenuItem("UiLocalDeleteAction", "\uE74D", _viewModel.CanChangeSelection);
            var peer = item.Source.ConversationUuid;
            delete.Click += async (_, _) => await DeleteLocalMessagesAsync(peer, [item.MessageId]);
            destructive.Add(delete);
        }
        if (destructive.Count > 0)
        {
            if (menu.Items.Count > 0) menu.Items.Add(new Separator());
            foreach (var action in destructive)
                menu.Items.Add(action);
        }
        if (menu.Items.Count == 0) return false;

        void CloseForRecycledRow(object sender, DependencyPropertyChangedEventArgs args) => menu.IsOpen = false;
        void CloseForUnloadedRow(object sender, RoutedEventArgs args) => menu.IsOpen = false;
        container.DataContextChanged += CloseForRecycledRow;
        container.Unloaded += CloseForUnloadedRow;
        if (albumTile is not null)
        {
            albumTile.DataContextChanged += CloseForRecycledRow;
            albumTile.Unloaded += CloseForUnloadedRow;
        }
        menu.Closed += (_, _) =>
        {
            container.DataContextChanged -= CloseForRecycledRow;
            container.Unloaded -= CloseForUnloadedRow;
            if (albumTile is not null)
            {
                albumTile.DataContextChanged -= CloseForRecycledRow;
                albumTile.Unloaded -= CloseForUnloadedRow;
            }
            if (ReferenceEquals(container.ContextMenu, menu)) container.ContextMenu = null;
        };
        container.ContextMenu = menu;
        menu.PlacementTarget = keyboard && albumTile is not null ? albumTile : container;
        menu.Placement = keyboard
            ? System.Windows.Controls.Primitives.PlacementMode.Bottom
            : System.Windows.Controls.Primitives.PlacementMode.MousePoint;
        menu.IsOpen = true;
        return true;
    }

    private async Task CancelQueuedSendAsync(MessageItem item, nint owner = 0)
    {
        if (_sendCancellationPending || _exiting || _appLocked || _services is not { } services) return;
        var peer = item.Source.ConversationUuid;
        if (_viewModel?.SelectedConversation?.Uuid != peer) return;
        _sendCancellationPending = true;
        var unavailable = AppStrings.Get("UiSendCancelUnavailable");
        try
        {
            var rows = await services.Messaging.SendQueueAsync(peer, messageId: item.MessageId);
            if (_exiting || _appLocked || _viewModel?.SelectedConversation?.Uuid != peer) return;
            if (rows.Count == 0 || !rows[0].CanCancel)
            {
                ShowWorkspaceNotice(unavailable);
                return;
            }
            var confirm = new ConfirmationDialog(AppStrings.Get("UiSendCancelMessage"), AppStrings.Get("UiSendCancelTitle"),
                dismissLabel: AppStrings.Get("UiSendCancelKeep"), confirmLabel: AppStrings.Get("UiSendCancelConfirm"));
            if (!ShowWorkspaceDialog(confirm, owner) || _exiting || _appLocked || _viewModel?.SelectedConversation?.Uuid != peer) return;
            if (!await services.Messaging.ManageSendAsync(item.MessageId, "cancel", expectedPeer: peer) && !_exiting && !_appLocked)
                ShowWorkspaceNotice(unavailable);
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException or OperationCanceledException)
        {
            if (!_exiting && !_appLocked) ShowWorkspaceNotice(unavailable);
        }
        finally { _sendCancellationPending = false; }
    }

    private async void RetryAttachmentUpload_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (sender is AttachmentPreview { Message: { } item } && item.CanRetryUpload) await RetrySendAsync(item);
    }

    private async Task RetrySendAsync(MessageItem item)
    {
        var peer = item.Source.ConversationUuid;
        if (_exiting || _appLocked || !item.CanRetrySend || _services is not { } services ||
            _viewModel is not { IsSelecting: false } model || model.SelectedConversation?.Uuid != peer ||
            !_retryingSends.Add(item.MessageId)) return;
        item.IsRetryingSend = true;
        try
        {
            if (!await services.Messaging.ManageSendAsync(item.MessageId, "retry", expectedPeer: peer) && !_exiting && !_appLocked)
                ShowWorkspaceNotice(AppStrings.Get("UiSendRetryUnavailable"));
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException or OperationCanceledException)
        {
            if (!_exiting && !_appLocked) ShowWorkspaceNotice(AppStrings.Get("UiSendRetryUnavailable"));
        }
        finally
        {
            item.IsRetryingSend = false;
            _retryingSends.Remove(item.MessageId);
        }
    }

    private void DeliveryStatus_Click(object sender, RoutedEventArgs e)
    {
        if (_exiting || _appLocked || _viewModel is not { IsSelecting: false } ||
            sender is not Button { DataContext: MessageItem item } button) return;
        e.Handled = true;
        if (!item.CanRetrySend) return;
        var retry = new MenuItem { Header = AppStrings.Get("UiSendRetry"), IsEnabled = !item.IsRetryingSend };
        retry.Click += async (_, _) => await RetrySendAsync(item);
        var menu = new ContextMenu { PlacementTarget = button };
        menu.Items.Add(new MenuItem { Header = item.StatusName, IsEnabled = false });
        menu.Items.Add(retry);
        menu.IsOpen = true;
    }

    private void ReEdit_Click(object sender, RoutedEventArgs e)
    {
        if (_viewModel is not { CanCompose: true } || sender is not FrameworkElement { DataContext: MessageItem { CanReEdit: true } item }) { return; }
        _viewModel.Draft = item.Content;
        Composer.Focus();
        Composer.CaretIndex = Composer.Text.Length;
        e.Handled = true;
    }

    private ContentControl? _replyQuotePicker;
    private MessageItem? _quotePickerMessage;

    private async Task QuoteMessageTextAsync(MessageItem item, nint owner = 0)
    {
        if (_exiting || _appLocked || _replyQuotePicker is not null ||
            _services is not { } services || _viewModel is not { CanCompose: true } model) return;
        var peer = item.Source.ConversationUuid;
        var generation = _appLockGeneration;
        bool IsCurrent() => !_exiting && !_appLocked && generation == _appLockGeneration && ReferenceEquals(_services, services) &&
            ReferenceEquals(_viewModel, model) && model.CanCompose && model.SelectedConversation?.Uuid == peer;
        if (!IsCurrent()) return;
        try
        {
            var document = await Task.Run(() =>
            {
                if (!services.Database.CanQuoteText(item.Source)) return (Text: (string?)null, Digest: (string?)null);
                var result = MsgAT.Windows.Core.Crypto.SecurityCore.Call(new { operation = "reply_quote_text", text = item.Content });
                return (Text: result.GetProperty("text").GetString(), Digest: result.GetProperty("digest").GetString());
            });
            if (!IsCurrent() || _replyQuotePicker is not null) return;
            if (document.Text is null || document.Digest is null)
            {
                ShowWorkspaceNotice(AppStrings.Get("UiQuoteSelectionUnavailable"));
                return;
            }
            Task<string?> ResolveExcerpt(string selection) => Task.Run(() =>
            {
                if (!services.Database.CanQuoteText(item.Source)) return null;
                return MsgAT.Windows.Core.Crypto.SecurityCore.Call(new
                {
                    operation = "reply_quote_selection", text = item.Content, selection
                }).GetProperty("preview").GetString();
            });
            if (owner != 0)
            {
                ReplyQuoteDialog? dialog = null;
                dialog = new ReplyQuoteDialog(document.Text, document.Digest, async selection =>
                {
                    var excerpt = await ResolveExcerpt(selection);
                    if (excerpt is null || dialog?.IsVisible != true || !IsCurrent()) return false;
                    model.SetReplyQuote(item, selection, excerpt);
                    return true;
                });
                ShowWorkspaceDialog(dialog, owner);
                return;
            }
            if (MessageList.ItemContainerGenerator.ContainerFromItem(item) is not ListBoxItem row ||
                FindMessageBody(row, item.DisplayContent) is not { Parent: TextBubbleLayout layout } ||
                layout.Parent is not Grid grid || grid.Children.OfType<ContentControl>().FirstOrDefault(child => child.Name == "QuotePickerHost") is not { } host) return;
            var picker = new ReplyQuotePicker(document.Text, document.Digest, async selection =>
            {
                var excerpt = await ResolveExcerpt(selection);
                if (excerpt is null || !IsCurrent() || !ReferenceEquals(_replyQuotePicker, host)) return false;
                model.SetReplyQuote(item, selection, excerpt);
                CloseReplyQuotePicker();
                Composer.Focus();
                return true;
            }, () => { CloseReplyQuotePicker(); Composer.Focus(); });
            picker.Unloaded += (_, _) => { if (ReferenceEquals(_replyQuotePicker, host)) CloseReplyQuotePicker(); };
            host.DataContextChanged += QuotePickerContextChanged;
            _replyQuotePicker = host;
            _quotePickerMessage = item;
            layout.Visibility = Visibility.Collapsed;
            host.Content = picker;
            host.Visibility = Visibility.Visible;
            UpdateChatVisibility();
        }
        catch (Exception error) when (error is CryptographicException or InvalidOperationException or IOException or
            JsonException or KeyNotFoundException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (IsCurrent()) ShowWorkspaceNotice(AppStrings.Get("UiQuoteSelectionUnavailable"));
        }
    }

    private void QuotePickerContextChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (ReferenceEquals(sender, _replyQuotePicker) && !ReferenceEquals(e.NewValue, _quotePickerMessage)) CloseReplyQuotePicker();
    }

    private void CloseReplyQuotePicker()
    {
        if (_replyQuotePicker is not { } host) return;
        _replyQuotePicker = null;
        _quotePickerMessage = null;
        host.DataContextChanged -= QuotePickerContextChanged;
        host.Content = null;
        host.Visibility = Visibility.Collapsed;
        if (host.Parent is Grid grid && grid.Children.OfType<TextBubbleLayout>().FirstOrDefault() is { } layout)
            layout.Visibility = Visibility.Visible;
        UpdateChatVisibility();
    }

    private void ReplyPreview_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { DataContext: MessageItem { ReplyToId: { } id } source } ||
            _viewModel is not { IsSelecting: false }) return;
        e.Handled = true;
        JumpToQuote(id, source, source.Source.ReplyToContent);
    }

    private void NavigateHistory_Click(object sender, RoutedEventArgs e) => OpenHistoryNavigation();

    private void TimelineDate_Click(object sender, RoutedEventArgs e)
    {
        if (_viewModel?.IsSelecting != false || sender is not FrameworkElement button) return;
        if (button.Tag is DateTime visibleDay) OpenHistoryNavigation(visibleDay);
        else if (button.DataContext is MessageItem item && LocalTimelineDay(item) is { } day) OpenHistoryNavigation(day);
    }

    private void OpenHistoryNavigation(DateTime? selectedDate = null, nint owner = 0)
    {
        if (_services is not { } services || _viewModel is not { SelectedConversation: { } peer } model ||
            _exiting || _appLocked) return;
        string? messageId = null;
        async Task<string?> ResolveTarget(long? start, long? end)
        {
            try
            {
                messageId = await Task.Run(() => start is { } day && end is { } next
                    ? services.Database.GetMessageAtDate(peer.Uuid, day, next)
                    : services.Database.GetFirstUnreadId(peer.Uuid));
                return messageId is null
                    ? AppStrings.Get(start is null ? "UiNoUnreadMessages" : "UiNoMessagesOnDate")
                    : null;
            }
            catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
            {
                return AppStrings.Get("UiHistoryLoadFailed");
            }
        }
        var dialog = new ChatHistoryDialog(selectedDate, validateTarget: ResolveTarget);
        if (!ShowWorkspaceDialog(dialog, owner) || model.SelectedConversation?.Uuid != peer.Uuid || _exiting || _appLocked)
            return;
        if (dialog.SharedMedia)
        {
            OpenSharedMedia(peer.Uuid, owner: owner);
            return;
        }
        if(dialog.PinnedMessages){OpenPinnedMessages(peer.Uuid, owner);return;}
        if (dialog.ConfigureExpiration)
        {
            ShowWorkspaceDialog(new MessageExpirationDialog(services, peer.Uuid), owner);
            return;
        }
        if (messageId is null)
        {
            ShowWorkspaceNotice(AppStrings.Get("UiSearchTargetUnavailable"));
            return;
        }
        if (owner != 0) _qtWorkspace?.RevealConfirmedMessage(peer.Uuid, messageId, "UiSearchTargetUnavailable");
        else JumpWithinChat(messageId, unreadAnchor: dialog.UnreadOnly);
    }

    private async Task ChangeMessagePinAsync(MessageItem item, bool? knownPin = null, nint owner = 0)
    {
        if (_services is not { } services || _viewModel is not { } model) return;
        var peer = item.Source.ConversationUuid;
        var generation = _appLockGeneration;
        bool IsCurrent() => !_appLocked && !_exiting && generation == _appLockGeneration &&
            ReferenceEquals(_services, services) && ReferenceEquals(_viewModel, model) && model.SelectedConversation?.Uuid == peer &&
            model.Messages.Any(row => row.MessageId == item.MessageId && !row.IsRevoked);
        if (!IsCurrent()) return;
        try
        {
            var pinned = knownPin ?? await Task.Run(() => services.Database.IsMessagePinned(peer, services.Settings.MyUuid, item.MessageId));
            if (!IsCurrent()) return;
            var notify = false;
            if (pinned)
            {
                var label = AppStrings.Get("UiUnpinMessage");
                if (!ShowWorkspaceDialog(new ConfirmationDialog(AppStrings.Get("UiUnpinForBoth"), label, confirmLabel: label), owner)) return;
            }
            else
            {
                var confirm = new PinMessageDialog();
                if (!ShowWorkspaceDialog(confirm, owner)) return;
                notify = confirm.NotifyPeer;
            }
            if (IsCurrent()) await services.Messaging.SetMessagePinnedAsync(peer, item.MessageId, !pinned, notify: notify);
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (IsCurrent()) ShowWorkspaceNotice(AppStrings.Get("UiPinChangeFailed"));
        }
    }

    private void PinnedMessage_Click(object sender,RoutedEventArgs e)
    {
        if(_viewModel?.PinnedMessage is not { } pin)return;
        JumpWithinChat(pin.Id);
    }
    private void PinnedMessages_Click(object sender,RoutedEventArgs e)
    {if(_viewModel?.SelectedConversation is { } peer)OpenPinnedMessages(peer.Uuid);}
    private void CyclePinnedPreview_Click(object sender, RoutedEventArgs e)
    {
        if (_appLocked || _exiting || _viewModel is not { } model) return;
        try { model.CyclePinnedPreview(); }
        catch (Exception error) when (error is IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            TransientNotice.Show(this, AppStrings.Get("UiHistoryLoadFailed"));
        }
    }
    private void OpenSharedMedia(string peer, SharedMediaCategory initialCategory = SharedMediaCategory.Media, nint owner = 0)
    {
        if (_appLocked || _exiting || _services is null || _viewModel?.SelectedConversation?.Uuid != peer) return;
        var dialog = new SharedMediaDialog(_services, peer, initialCategory);
        if (!ShowWorkspaceDialog(dialog, owner) || dialog.SelectedId is not { } id || _viewModel.SelectedConversation?.Uuid != peer) return;
        if (owner != 0) _qtWorkspace?.RevealConfirmedMessage(peer, id, "UiSearchTargetUnavailable");
        else RevealMediaMessage(peer, id);
    }

    private void RevealMediaMessage(string peer, string id)
    {
        if (_viewModel?.SelectedConversation?.Uuid == peer) JumpWithinChat(id);
    }

    private void OpenPinnedMessages(string peer, nint owner = 0)
    {
        if (_services is not { } services || _viewModel?.SelectedConversation?.Uuid != peer || _appLocked || _exiting) return;
        var dialog = new PinnedMessagesDialog(services, peer);
        if (!ShowWorkspaceDialog(dialog, owner) || dialog.SelectedId is not { } id || _viewModel?.SelectedConversation?.Uuid != peer) return;
        if (owner != 0) _qtWorkspace?.RevealConfirmedMessage(peer, id, "UiSearchTargetUnavailable");
        else JumpWithinChat(id);
    }

    private async void OpenContactProfile_Click(object sender, RoutedEventArgs e)
    {
        if (_exiting || _appLocked || _services is not { } services ||
            _viewModel?.SelectedConversation is not { } peer) return;
        string? mediaPeer = null;
        bool? video = null;
        var lockGeneration = _appLockGeneration;
        new ContactsDialog(services, _ => { }, focusedPeer: peer.Uuid, openSharedMedia: id => mediaPeer = id,
            startCall: useVideo => video = useVideo,
            addNotificationOptions: items =>
            {
                if (!_exiting && !_appLocked && _viewModel?.SelectedConversation is { } current && current.Uuid == peer.Uuid)
                    AddConversationMuteOptions(items, current);
            })
        { Owner = this }.ShowDialog();
        if (_exiting || _appLocked || lockGeneration != _appLockGeneration ||
            _viewModel?.SelectedConversation?.Uuid != peer.Uuid) return;
        if (video is { } useVideo) await StartCallAsync(useVideo);
        else if (mediaPeer == peer.Uuid) OpenSharedMedia(peer.Uuid);
    }

    private void SearchChat_Click(object sender, RoutedEventArgs e) => OpenMessageSearch();

    private async Task OpenCallHistoryAsync(nint owner = 0)
    {
        if (_appLocked || _exiting || _services is not { } services || _viewModel is not { } model) return;
        var dialog = new CallHistoryDialog(services.Messaging);
        dialog.ConfirmOpenChat = peer => ConfirmConversationNavigation(model, peer, dialog);
        if (!ShowWorkspaceDialog(dialog, owner) || _appLocked || _exiting || dialog.SelectedCall is not { } entry) return;
        if (!dialog.Redial) { OpenConfirmedContactConversation(model, entry.Peer); return; }
        try { await services.Calls.StartOutgoingAsync(entry.Peer,entry.Video); }
        catch (Exception error) when (error is InvalidOperationException or IOException or OperationCanceledException)
        {
            if (!_appLocked && !_exiting) ShowWorkspaceNotice(AppStrings.Get("UiCallFailed"));
        }
    }

    private readonly Dictionary<string, MessageSearchInput> _previousSearches = new(StringComparer.Ordinal);
    private readonly List<string> _searchOrder = [];
    private void RememberSearch(string? peer, MessageSearchInput input)
    {
        var key = peer ?? "";
        _searchOrder.Remove(key);
        _searchOrder.Add(key);
        _previousSearches[key] = input;
        if (_searchOrder.Count <= 16) return;
        _previousSearches.Remove(_searchOrder[0]);
        _searchOrder.RemoveAt(0);
    }
    private sealed record SearchReturn(string TargetPeer, string? Origin, MessageSearchInput Input, MessageSearchCursor Anchor);
    private SearchReturn? _searchReturn;
    private bool _movingSearch;
    private bool _searchDialogOpen;
    private int _searchDialogGeneration;

    private void UpdateReturnToSearchButton()
    {
        var route = _searchReturn;
        var visible = !_appLocked &&
            _viewModel is { IsSelecting: false, SelectedConversation: { } conversation } &&
            route?.TargetPeer == conversation.Uuid;
        SearchNavigationBar.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
        SearchNavigationQuery.Text = visible ? route?.Input.Query ?? "" : "";
        PreviousSearchResult.Visibility = NextSearchResult.Visibility =
            visible && route?.Input.All == false ? Visibility.Visible : Visibility.Collapsed;
        PreviousSearchResult.IsEnabled = NextSearchResult.IsEnabled = !_movingSearch;
    }

    private void CloseSearchNavigation_Click(object sender, RoutedEventArgs e)
        => CloseSearchNavigation();

    private void CloseSearchNavigation()
    {
        var returnFocus = SearchNavigationBar.IsKeyboardFocusWithin;
        _searchReturn = null;
        UpdateReturnToSearchButton();
        if (returnFocus) MessageList.Focus();
    }

    private void PreviousSearchResult_Click(object sender, RoutedEventArgs e) => _ = MoveSearchAsync(newer: true);
    private void NextSearchResult_Click(object sender, RoutedEventArgs e) => _ = MoveSearchAsync(newer: false);

    private bool TrySearchNavigationShortcut(WorkspaceKey e)
    {
        if (!SearchNavigationBar.IsVisible || IsRestoringHistory || IsDropCoveringChat ||
            IsComposerSendMenuOpen || StickerHost.IsHitTestVisible ||
            _viewModel is not { IsChatContentVisible: true, HasMessageEdit: false } model ||
            _imeComposing || _editImeComposing) return false;

        var modifiers = e.Modifiers;
        if (e.Key == Key.Escape && modifiers == ModifierKeys.None)
        {
            if (model.ReplyTarget is not null)
            {
                model.CancelReply();
                ClearJumpHighlight();
            }
            else
            {
                CloseSearchNavigation();
            }
            e.Handled = true;
            return true;
        }
        if (e.Key != Key.F3 || _searchReturn?.Input.All != false ||
            modifiers is not (ModifierKeys.None or ModifierKeys.Shift)) return false;
        e.Handled = true;
        if (!e.IsRepeat) _ = MoveSearchAsync(newer: modifiers == ModifierKeys.Shift);
        return true;
    }

    private async Task MoveSearchAsync(bool newer)
    {
        if (_movingSearch || _searchDialogOpen || _appLocked || _exiting ||
            _services is not { } services || _searchReturn is not { Input.All: false } route ||
            IsRestoringHistory || _viewModel is not
                { IsSelecting: false, HasMessageEdit: false, IsChatContentVisible: true, SelectedConversation: { } conversation } model ||
            conversation.Uuid != route.TargetPeer) return;
        _movingSearch = true;
        var generation = _searchDialogGeneration;
        UpdateReturnToSearchButton();
        bool CanApply() => !_appLocked && !_exiting && !_searchDialogOpen && generation == _searchDialogGeneration &&
            ReferenceEquals(route, _searchReturn) && ReferenceEquals(model, _viewModel) &&
            model.SelectedConversation?.Uuid == route.TargetPeer && !model.IsSelecting &&
            !model.HasMessageEdit && model.IsChatContentVisible;
        try
        {
            var hit = await Task.Run(() =>
            {
                var hits = services.Search.Search(route.Input.Query, route.TargetPeer,
                    route.Anchor, route.Input.Kind, limit: 1, newer: newer, day: route.Input.Day).Hits;
                return hits.Count == 0 ? null : hits[0];
            });
            if (!CanApply()) return;
            if (hit is null)
            {
                TransientNotice.Show(this, AppStrings.Get("UiSearchNoAdjacentResult"));
                return;
            }
            if (model.RevealMessage(hit.MessageId) is not { IsRevoked: false } target)
            {
                TransientNotice.Show(this, AppStrings.Get("UiSearchTargetUnavailable"));
                return;
            }
            var index = Math.Max(0, (route.Input.SelectedIndex ?? 0) + (newer ? -1 : 1));
            var input = route.Input with
            {
                SelectedMessage = hit.MessageId,
                SelectedIndex = index,
                LoadedCount = Math.Max(route.Input.LoadedCount, index + 1)
            };
            _searchReturn = route with { Input = input, Anchor = new MessageSearchCursor(hit.Timestamp, hit.MessageId) };
            RememberSearch(route.Origin, input);
            _restoringHistoryVersion = model.HistoryNavigationVersion;
            ResetVisibleReadCandidates();
            ShowJumpTarget(target, finishInitialRestore: true);
        }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (CanApply()) TransientNotice.Show(this, AppStrings.Get("UiSearchFailed"));
        }
        finally
        {
            _movingSearch = false;
            if (!_exiting) UpdateReturnToSearchButton();
        }
    }

    private void ReturnToSearch_Click(object sender, RoutedEventArgs e)
    {
        if (_searchReturn is { } route && route.TargetPeer == _viewModel?.SelectedConversation?.Uuid)
            OpenMessageSearch(resume: route);
    }

    private void OpenMessageSearch(bool global = false, SearchReturn? resume = null)
    {
        if (_services is not { } services || _viewModel is not { } model || _exiting || _appLocked || _searchDialogOpen) return;
        var source = model.SelectedConversation?.Uuid;
        var generation = _appLockGeneration;
        bool SearchIsCurrent() => !_appLocked && !_exiting && generation == _appLockGeneration &&
            ReferenceEquals(_viewModel, model) && model.SelectedConversation?.Uuid == source;
        var origin = resume is not null ? resume.Origin : global ? null : source;
        var initial = resume?.Input ?? _previousSearches.GetValueOrDefault(origin ?? "");
        var search = new MessageSearchDialog(services, origin, initial) { Owner = this };
        search.ConfirmOpen = hit =>
        {
            return SearchIsCurrent() && ConfirmConversationNavigation(model, hit.Peer, search) && SearchIsCurrent();
        };
        bool accepted;
        _searchDialogOpen = true;
        _searchDialogGeneration++;
        try { accepted = search.ShowDialog() == true; }
        finally { _searchDialogOpen = false; }
        if (!SearchIsCurrent()) return;
        RememberSearch(origin, search.Input);
        if (!accepted || search.SelectedHit is not { } hit) return;
        if (model.Conversations.FirstOrDefault(item => item.Uuid == hit.Peer) is not { } conversation)
        {
            TransientNotice.Show(this, AppStrings.Get("UiSearchTargetUnavailable"));
            return;
        }
        if (source != hit.Peer) model.SelectedConversation = conversation;
        model.IsViewingLatest = false;
        if (model.RevealMessage(hit.MessageId) is not { IsRevoked: false } target)
        {TransientNotice.Show(this,AppStrings.Get("UiSearchTargetUnavailable"));return;}
        _searchReturn = new SearchReturn(hit.Peer, origin, search.Input, new MessageSearchCursor(hit.Timestamp, hit.MessageId));
        UpdateReturnToSearchButton();
        _restoringHistoryVersion = model.HistoryNavigationVersion;
        ResetVisibleReadCandidates();
        ShowJumpTarget(target, finishInitialRestore: true);
    }

    internal async void OpenNotificationMessage(string peer, string messageId, bool pinned = false)
    {
        if (_exiting) return;
        var request = ++_notificationNavigationRevision;
        if (_appLocked || _callWindow?.CoversChat == true || _viewModel is not { IsWorkspaceReady: true } model)
        {
            _deferredNotification = (peer, messageId, pinned);
            return;
        }
        _deferredNotification = null;
        if (_services is not { } services) return;
        var generation = _appLockGeneration;
        var navigation = model.HistoryNavigationVersion;
        bool ContinueOpening()
        {
            if (_exiting || request != _notificationNavigationRevision || !ReferenceEquals(_viewModel, model)) return false;
            if (_appLocked || _callWindow?.CoversChat == true)
            {
                _deferredNotification = (peer, messageId, pinned);
                return false;
            }
            return generation == _appLockGeneration && navigation == model.HistoryNavigationVersion;
        }

        try
        {
            var available = await Task.Run(() => services.Database.CanOpenNotification(peer, messageId, pinned));
            if (!ContinueOpening()) return;
            if (!available)
            {
                TransientNotice.Show(this, AppStrings.Get("UiSearchTargetUnavailable"));
                return;
            }
            if (model.HasMessageEdit && model.SelectedConversation?.Uuid != peer)
            {
                if (!model.CanCancelEdit || !ConfirmDiscardMessageEdit(model) || !ContinueOpening()) return;
                if (!services.Database.CanOpenNotification(peer, messageId, pinned))
                {
                    TransientNotice.Show(this, AppStrings.Get("UiSearchTargetUnavailable"));
                    return;
                }
            }

            if (MediaOverlayHost.Content is not null) CloseChatMedia();
            if (model.SelectedConversation?.Uuid != peer) model.SelectContact(peer);
            if (model.SelectedConversation?.Uuid != peer ||
                model.RevealMessage(messageId) is not { IsRevoked: false } target)
            {
                TransientNotice.Show(this, AppStrings.Get("UiSearchTargetUnavailable"));
                return;
            }
            _restoringHistoryVersion = model.HistoryNavigationVersion;
            ResetVisibleReadCandidates();
            ShowJumpTarget(target, finishInitialRestore: true);
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_exiting && !_appLocked && request == _notificationNavigationRevision &&
                ReferenceEquals(_viewModel, model))
                TransientNotice.Show(this, AppStrings.Get("UiHistoryLoadFailed"));
        }
    }

    private void PreviewReplyTarget_Click(object sender, RoutedEventArgs e)
    {
        if (_viewModel is not { } model) return;
        var id = model.EditTargetId ?? model.ReplyTarget?.MessageId;
        if (id is null) return;
        if (model.HasMessageEdit) JumpWithinChat(id);
        else JumpToQuote(id, quote: model.DraftQuotePreview);
    }

    private void JumpToQuote(string id, MessageItem? source = null, string? quote = null)
        => JumpWithinChat(id, source, quote, allowRevoked: true);

    private void JumpWithinChat(string id, MessageItem? source = null, string? quote = null,
        bool allowRevoked = false, bool unreadAnchor = false)
    {
        if (_exiting || _appLocked || _viewModel is not { SelectedConversation: { } peer } model ||
            _services is not { } services) return;
        var origin = CaptureQuoteReturnAnchor(model, id, source);
        MessageItem? target;
        try
        {
            var row = services.Database.GetMessage(id);
            target = row is not null && row.ConversationUuid == peer.Uuid && (allowRevoked || !row.IsRevoked)
                ? model.RevealMessage(id) : null;
        }
        catch (Exception error) when (error is IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            TransientNotice.Show(this, AppStrings.Get("UiHistoryLoadFailed"));
            return;
        }
        if (target is null)
        {
            TransientNotice.Show(this, AppStrings.Get(allowRevoked ? "UiQuotedMessageUnavailable" : "UiSearchTargetUnavailable"));
            return;
        }
        if (origin is { } anchor && (_quoteReturnAnchors.Count == 0 || _quoteReturnAnchors[^1] != anchor))
        {
            if (_quoteReturnAnchors.Count == 16) _quoteReturnAnchors.RemoveAt(0);
            _quoteReturnAnchors.Add(anchor);
            UpdateQuoteReturnButton();
        }
        if (unreadAnchor) model.SetUnreadTimelineAnchor(id);
        _restoringHistoryVersion = model.HistoryNavigationVersion;
        ResetVisibleReadCandidates();
        ShowJumpTarget(target, quote, finishInitialRestore: true);
    }

    private QuoteReturnAnchor? CaptureQuoteReturnAnchor(MainViewModel model, string target, MessageItem? source)
    {
        if (GetScrollViewer(MessageList) is not { } viewport) return null;
        if (source is not null && VisibleQuoteAnchor(source, target, viewport) is { } preferred) return preferred;
        foreach (var item in model.Messages)
        {
            if (VisibleQuoteAnchor(item, target, viewport) is { } visible) return visible;
        }
        return null;
    }

    private QuoteReturnAnchor? VisibleQuoteAnchor(MessageItem item, string target, ScrollViewer viewport)
    {
        if (item.IsRevoked || item.MessageId == target || MessageRowOffset(item, viewport) is not { } bounds ||
            bounds.Top >= viewport.ActualHeight || bounds.Bottom <= 0) return null;
        return new QuoteReturnAnchor(item.MessageId, bounds.Top);
    }

    private Rect? MessageRowOffset(MessageItem item, ScrollViewer viewport)
    {
        if (MessageList.ItemContainerGenerator.ContainerFromItem(item.AlbumAnchor ?? item) is not ListBoxItem { IsVisible: true } row ||
            !row.IsDescendantOf(viewport)) return null;
        return row.TransformToAncestor(viewport).TransformBounds(new Rect(row.RenderSize));
    }

    private long? _restoringHistoryVersion;
    private bool IsRestoringHistory => _restoringHistoryVersion is { } version && _viewModel?.HistoryNavigationVersion == version;
    private (MainViewModel Model, long Version, ChatHistoryPosition Position)? _readingPosition;

    private ChatHistoryPosition? CaptureHistoryViewport()
    {
        if (_exiting || _appLocked || IsRestoringHistory || _viewModel is null ||
            GetScrollViewer(MessageList) is not { } viewport) return null;
        return FindFirstVisibleTimelineRow(viewport, skipRevoked: true) is { } row
            ? new ChatHistoryPosition(row.Item.MessageId, row.Offset) : null;
    }

    private void FinishHistoryRestore(long version)
    {
        if (_restoringHistoryVersion != version) return;
        _restoringHistoryVersion = null;
        if (_viewModel?.HistoryNavigationVersion == version) UpdateViewingLatest();
    }

    private void FinishHistoryRestoreAfterLayout(long version) =>
        Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.ContextIdle,
            new Action(() => FinishHistoryRestore(version)));

    private void RestoreHistoryViewport(MessageItem target, double offset, long version, bool finishInitialRestore = false)
    {
        MessageList.ScrollIntoView(target.AlbumAnchor ?? target);
        Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Loaded, new Action(() =>
        {
            try
            {
                if (_exiting || _appLocked || _viewModel?.HistoryNavigationVersion != version ||
                    GetScrollViewer(MessageList) is not { } viewport || MessageRowOffset(target, viewport) is not { } bounds) return;
                viewport.ScrollToVerticalOffset(Math.Clamp(viewport.VerticalOffset + bounds.Top - offset, 0, viewport.ScrollableHeight));
            }
            finally { if (finishInitialRestore) FinishHistoryRestoreAfterLayout(version); }
        }));
    }

    private void QuoteReturn_Click(object sender, RoutedEventArgs e)
    {
        if (_exiting || _appLocked || IsRestoringHistory || _quoteReturnAnchors.Count == 0 ||
            _viewModel is not { SelectedConversation: { } peer } model || _services is not { } services) return;
        var origin = _quoteReturnAnchors[^1];
        MessageItem? target;
        try
        {
            var row = services.Database.GetMessage(origin.MessageId);
            target = row is { IsRevoked: false } && row.ConversationUuid == peer.Uuid
                ? model.RevealMessage(origin.MessageId) : null;
        }
        catch (Exception error) when (error is IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            TransientNotice.Show(this, AppStrings.Get("UiHistoryLoadFailed"));
            return;
        }
        _quoteReturnAnchors.RemoveAt(_quoteReturnAnchors.Count - 1);
        UpdateQuoteReturnButton();
        if (target is null)
        {
            TransientNotice.Show(this, AppStrings.Get("UiSearchTargetUnavailable"));
            return;
        }
        var version = model.HistoryNavigationVersion;
        _restoringHistoryVersion = version;
        ResetVisibleReadCandidates();
        ClearJumpHighlight();
        RestoreHistoryViewport(target, origin.Offset, version, finishInitialRestore: true);
    }

    private void ClearQuoteReturnAnchors()
    {
        _quoteReturnAnchors.Clear();
        UpdateQuoteReturnButton();
    }

    private void UpdateQuoteReturnButton() => QuoteReturnButton.Visibility =
        _quoteReturnAnchors.Count > 0 && _viewModel is { IsSelecting: false } ? Visibility.Visible : Visibility.Collapsed;

    private void DismissComposerAccessory_Click(object sender, RoutedEventArgs e)
    {
        if (_appLocked || _exiting || _viewModel is not { IsChatContentVisible: true } model) return;
        if (model.HasMessageEdit)
        {
            CancelMessageEdit();
            return;
        }
        model.CancelReply();
        ClearJumpHighlight();
        if (model.CanCompose && CanRestoreComposerFocus(model, model.SelectedConversation?.Uuid)) Composer.Focus();
    }

    private (string Peer, CapturedRecording Capture, Action<CapturedRecording> Review)? _interruptedRecording;
    private bool _reviewingRecording;

    private void DiscardInterruptedRecording()
    {
        var pending = _interruptedRecording;
        _interruptedRecording = null;
        pending?.Capture.Dispose();
    }

    private void ReviewInterruptedRecording()
    {
        if (_exiting || _appLocked || !IsWorkspaceActive || _callWindow?.CoversChat == true ||
            _services?.Calls.State is not (CallState.Idle or CallState.Ended) ||
            _interruptedRecording is not { } pending) return;
        _interruptedRecording = null;
        using (pending.Capture) pending.Review(pending.Capture);
    }

    private void RecordVoice_Click(object sender, RoutedEventArgs e) => OpenVoiceRecorder();

    private void OpenVoiceRecorder(nint owner = 0)
    {
        if (_exiting || _appLocked || _recordingDialog is not null || _reviewingRecording ||
            _interruptedRecording is not null || _services is not { } services ||
            _viewModel is not { CanCompose: true, SelectedConversation: { } peer } model ||
            StickerHost.Child is StickerLibraryPanel { IsBusy: true }) return;
        var qtSession = owner == 0 ? null : _qtWorkspace;
        if (owner != 0 && qtSession is not { IsOpen: true }) return;
        CloseStickerPanel(immediate: true);
        var lockGeneration = _appLockGeneration;
        bool IsCurrent() => !_exiting && !_appLocked && lockGeneration == _appLockGeneration &&
            ReferenceEquals(_services, services) && ReferenceEquals(_viewModel, model) &&
            ReferenceEquals(model.SelectedConversation, peer);
        bool CanRecord() => IsCurrent() && services.Calls.State is CallState.Idle or CallState.Ended;
        if (!CanRecord())
        {
            ShowWorkspaceNotice(AppStrings.Get("UiRecordingCallActive"));
            return;
        }
        if (model.CaptureConfirmedRecordingSender() is not { } send) return;
        var options = model.SendOptions;
        model.ShowAttachments = false;
        var dialog = new RecordingDialog(services.Objects.Captures, CanRecord);
        bool accepted;
        _recordingDialog = dialog;
        try { accepted = ShowWorkspaceDialog(dialog, owner); }
        finally
        {
            _recordingDialog = null;
            if (qtSession is not null) Dispatcher.BeginInvoke(new Action(ScheduleQtWorkspace));
        }
        if (dialog.YieldedToCall || services.Calls.State is not (CallState.Idle or CallState.Ended))
        {
            if (accepted && dialog.Result is { } interrupted)
            {
                if (IsCurrent()) _interruptedRecording = (peer.Uuid, interrupted, Review);
                else interrupted.Dispose();
            }
            Dispatcher.BeginInvoke(new Action(() =>
            {
                if (IsWorkspaceActive && services.Calls.IncomingCallId is { } id) FocusIncomingCall(id);
                else ReviewInterruptedRecording();
            }));
            return;
        }
        if (accepted && dialog.Result is { } result) { using (result) Review(result); }
        else if (IsCurrent()) RestoreComposerAfterAttachment(model, peer.Uuid, lockGeneration);

        void Review(CapturedRecording capture)
        {
            if (!IsCurrent() || _reviewingRecording) return;
            var nativeOwner = IsVisible ? 0 : qtSession?.HostWindow ?? 0;
            if (!IsVisible && nativeOwner == 0) return;
            string? preparation = null;
            _reviewingRecording = true;
            try
            {
                MediaSendDialog.ReviewAndSendRecording(this, capture.File, peer.Alias, options, Submit, model,
                    show: nativeOwner == 0 ? null : window => ShowWorkspaceDialog(window, nativeOwner));
            }
            finally
            {
                _reviewingRecording = false;
                if (!IsCurrent() && preparation is not null) model.CancelPreparation(preparation);
            }
            if (!IsCurrent()) return;
            if (nativeOwner == 0) RestoreComposerAfterAttachment(model, peer.Uuid, lockGeneration);
            if (qtSession is not null) ScheduleQtWorkspace();

            Task<PreparationSendResult> Submit(MediaSendChoice choice, Action<string> started)
            {
                if (!IsCurrent()) return Task.FromResult(PreparationSendResult.Cancelled);
                var operation = send(capture.File, choice, id => { preparation = id; started(id); });
                qtSession?.TrackAttachmentWork(operation);
                return operation;
            }
        }
    }

    private void ClearJumpHighlight()
    {
        _jumpHighlightTimer.Stop();
        _viewModel?.SetJumpHighlight(null);
    }

    private void ShowJumpTarget(MessageItem item, string? quote = null, bool finishInitialRestore = false)
    {
        _jumpHighlightTimer.Stop();
        _viewModel?.SetJumpHighlight(item.MessageId, quote);
        _jumpHighlightTimer.Start();
        ScrollToMessage(item, center: true, finishInitialRestore: finishInitialRestore);
    }

    private void ScrollToMessage(MessageItem item, bool center = false, bool finishInitialRestore = false)
    {
        var anchor = item.AlbumAnchor ?? item;
        MessageList.ScrollIntoView(anchor);
        if ((!anchor.HasAlbum && !center) || _viewModel is not { } model) return;
        var version = model.HistoryNavigationVersion;
        Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Loaded, new Action(() =>
        {
            try
            {
                if (_exiting || _appLocked || model.HistoryNavigationVersion != version ||
                    MessageList.ItemContainerGenerator.ContainerFromItem(anchor) is not ListBoxItem row) return;
                var tile = anchor.HasAlbum ? AttachmentPreview.FindIn(row, item.MessageId, albumOnly: true) : null;
                if (!center) { tile?.BringIntoView(); return; }
                FrameworkElement target = tile is not null ? tile : row;
                if (GetScrollViewer(MessageList) is not { } viewport || !target.IsDescendantOf(viewport)) return;
                var bounds = target.TransformToAncestor(viewport).TransformBounds(new Rect(target.RenderSize));
                var offset = viewport.VerticalOffset + (bounds.Top + bounds.Bottom - viewport.ViewportHeight) / 2;
                viewport.ScrollToVerticalOffset(Math.Clamp(offset, 0, viewport.ScrollableHeight));
            }
            finally { if (finishInitialRestore) FinishHistoryRestoreAfterLayout(version); }
        }));
    }

    private async void AttachmentPreview_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is AttachmentPreview { DataContext: MessageItem { IsAttachment: true } item } thumbnail)
        {
            if (thumbnail.ContainsInlineAudioInput(e.OriginalSource)) return;
            if (item.IsFileCard) return; // The file card's visible action is the only pointer target.
            e.Handled = true;
            await OpenAttachmentAsync(item, thumbnail);
        }
    }

    private async void FileCardAction_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not AttachmentPreview { Message: { IsFileCard: true, HasFileAction: true } item } card ||
            _viewModel is not { IsSelecting: false, IsChatContentVisible: true } model ||
            model.SelectedConversation?.Uuid != item.Source.ConversationUuid) return;
        e.Handled = true;
        await OpenAttachmentAsync(item, card);
    }

    private async Task OpenAttachmentAsync(MessageItem item, AttachmentPreview? thumbnail = null)
    {
        if (_exiting || _appLocked || _services is not { } services) return;
        _attachmentCheck?.Cancel();
        var request = ++_mediaNavigationRevision;
        var peer = item.Source.ConversationUuid;
        var lockGeneration = _appLockGeneration;
        try
        {
            var albumIds = item.AttachmentInfo?.Album is { } album && _viewModel is { } model
                ? model.Messages.Where(member => !member.IsRevoked && member.IsMine == item.IsMine &&
                      member.AttachmentInfo?.Album?.Id == album.Id)
                    .OrderBy(member => member.AttachmentInfo!.Album!.Index)
                    .Select(member => member.MessageId).ToArray()
                : Array.Empty<string>();
            if (albumIds.Length <= 1 && !item.IsSticker && item.AttachmentInfo is not null && (item.IsImage || item.IsVideo))
            {
                await ShowGalleryMediaAsync(peer, item.MessageId, item.Content, request, thumbnail);
                return;
            }
            if (thumbnail is { Compact: true, GalleryTile: false } && albumIds.Length > 1 &&
                !item.IsAvailable && (item.IsImage || item.IsVideo) &&
                (item.CanPauseDownload || item.CanRequestDownload))
            {
                if (StillOpening()) ShowChatMedia(item, albumIds);
                return;
            }
            if (item.CanPauseDownload)
            {
                await _services.Messaging.PauseAttachmentDownloadAsync(item.Source.ConversationUuid, item.MessageId);
                return;
            }
            if (item.CanRequestDownload)
            {
                await _services.Messaging.RequestAttachmentDownloadAsync(item.Source.ConversationUuid, item.MessageId);
                return;
            }
            if (!item.IsAvailable) return;
            if (!item.IsMine)
            {
                if (!await VerifyChatAttachmentAsync(peer, item.MessageId, item.Content, StillOpening, showProgress: true)) return;
            }
            if (item.IsAudio && thumbnail is { Compact: false, GalleryTile: false })
            {
                if (_services.Calls.State is not (CallState.Idle or CallState.Ended)) return;
                CloseInlineAudio();
                if (thumbnail.OpenInlineAudio(item, _inlineAudioSpeed, speed => _inlineAudioSpeed = speed))
                    _inlineAudioPreview = thumbnail;
                return;
            }
            if(!item.IsImage && !item.IsVideo && !item.IsAudio) { SaveAttachment(item); return; }
            ShowChatMedia(item, albumIds, thumbnail);
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or NotSupportedException or System.ComponentModel.Win32Exception or Microsoft.Data.Sqlite.SqliteException)
        {
            TransientNotice.Show(this, AppStrings.Get(item.IsFileCard ? "UiFileDetailsUnavailable" : "UiMediaPlaybackUnavailable"));
        }

        bool StillOpening() => !_exiting && !_appLocked && request == _mediaNavigationRevision &&
            lockGeneration == _appLockGeneration &&
            _viewModel is { IsChatContentVisible: true, IsSelecting: false, SelectedConversation.Uuid: var currentPeer } &&
            currentPeer == peer;
    }

    private async Task<bool> VerifyChatAttachmentAsync(string peer, string id, string reference,
        Func<bool> isCurrent, bool showProgress = false)
    {
        if (_services is not { } services || !isCurrent()) return false;
        _attachmentCheck?.Cancel();
        using var check = new CancellationTokenSource();
        _attachmentCheck = check;
        AttachmentCheckIndicator.Visibility = showProgress ? Visibility.Visible : Visibility.Collapsed;
        try
        {
            var complete = await Task.Run(() => services.Objects.HasCompleteCiphertext(reference, check.Token), check.Token);
            if (check.IsCancellationRequested || !isCurrent()) return false;
            if (complete) return true;
            if (await services.Messaging.ReplaceAttachmentCacheAsync(peer, id, reference, check.Token) &&
                !check.IsCancellationRequested && isCurrent())
                TransientNotice.Show(this, AppStrings.Get("UiDownloadQueued"));
            return false;
        }
        catch (OperationCanceledException) when (check.IsCancellationRequested) { return false; }
        finally
        {
            if (ReferenceEquals(_attachmentCheck, check))
            {
                _attachmentCheck = null;
                AttachmentCheckIndicator.Visibility = Visibility.Collapsed;
            }
        }
    }

    private IInputElement? _focusBeforeMedia;
    private MediaOverlay? _galleryPreview;
    private int _mediaNavigationRevision;
    private CancellationTokenSource? _attachmentCheck;
    private void CancelAttachmentCheck_Click(object sender, RoutedEventArgs e)
    {
        ++_mediaNavigationRevision;
        _attachmentCheck?.Cancel();
    }
    private bool _galleryMoving;
    private bool _galleryRefreshing;
    private bool _galleryRefreshPending;

    private void CloseChatMedia()
    {
        _attachmentCheck?.Cancel();
        ExitMediaFullScreen();
        ++_mediaNavigationRevision;
        _galleryPreview = null;
        _chatAlbumIds = null;
        var previous = _focusBeforeMedia;
        _focusBeforeMedia = null;
        if (MediaOverlayHost.Content is IDisposable viewer) viewer.Dispose();
        MediaOverlayHost.Content = null;
        UpdateChatVisibility();
        if (!IsActive || _appLocked || _exiting) return;
        if (previous is UIElement { IsEnabled: true, IsVisible: true, Focusable: true } element && Keyboard.Focus(element) == element)
            return;
        if (previous is not null && MessageList.IsVisible && MessageList.IsEnabled) MessageList.Focus();
    }

    private void CloseInlineAudio()
    {
        var preview = _inlineAudioPreview;
        _inlineAudioPreview = null;
        preview?.CloseInlineAudio();
    }

    private void JumpFromMedia(string peer, string id)
    {
        if (MediaOverlayHost.Content is not MediaOverlay viewer || viewer.Peer != peer || viewer.MessageId != id) return;
        CloseChatMedia();
        RevealMediaMessage(peer, id);
    }

    private void ShowChatMedia(MessageItem item, IReadOnlyList<string> albumIds, AttachmentPreview? thumbnail = null, int albumStep = 0)
    {
        var peer = item.Source.ConversationUuid;
        if (_exiting || _appLocked || _viewModel?.SelectedConversation?.Uuid != peer) return;
        var members = CurrentPreviewMembers(albumIds);
        var index = members.FindIndex(member => member.MessageId == item.MessageId);
        var navigation = index >= 0 && members.Count > 1
            ? new MediaOverlay.PreviewNavigation(index > 0, index < members.Count - 1, Move, $"{index + 1} / {members.Count}", members, Select) : null;
        InstallChatMedia(new MediaOverlay(item, CloseChatMedia, navigation,
            showMessage: () => JumpFromMedia(peer, item.MessageId), changeDownload: ChangeDownload,
            save: () => ExportChatAttachmentAsync(item), canSave: () => CanExportChatAttachment(item),
            forward: () => ForwardChatMedia(peer, item.MessageId),
            reply: _viewModel?.CanCompose == true ? () => ReplyToChatMedia(peer, item.MessageId) : null,
            openSharedMedia: () => OpenSharedMediaFromPreview(item), albumStep: albumStep),
            inGallery: false, thumbnail: thumbnail);
        _chatAlbumIds = albumIds;

        Task<bool> ChangeDownload(MessageItem message)
        {
            if (_exiting || _appLocked || _services is not { } services || _viewModel?.SelectedConversation?.Uuid != peer)
                return Task.FromResult(false);
            return message.CanPauseDownload ? services.Messaging.PauseAttachmentDownloadAsync(peer, message.MessageId) :
                services.Messaging.RequestAttachmentDownloadAsync(peer, message.MessageId);
        }

        void Move(int delta)
        {
            var remaining = CurrentPreviewMembers(albumIds);
            var at = remaining.FindIndex(member => member.MessageId == item.MessageId);
            if (at < 0) { CloseChatMedia(); return; }
            var next = at + delta;
            if (next >= 0 && next < remaining.Count) Select(remaining[next].MessageId);
        }

        async void Select(string id)
        {
            if (id == item.MessageId || MediaOverlayHost.Content is not MediaOverlay viewer ||
                viewer.MessageId != item.MessageId || viewer.IsClosing ||
                CurrentPreviewMembers(albumIds).FirstOrDefault(member => member.MessageId == id) is not { } selected) return;
            var version = ++_mediaNavigationRevision;
            _attachmentCheck?.Cancel();
            try
            {
                if (selected.IsAvailable && !selected.IsMine &&
                    !await VerifyChatAttachmentAsync(peer, id, selected.Content, StillInAlbum)) return;
                if (!StillInAlbum()) return;
                var remaining = CurrentPreviewMembers(albumIds);
                var from = remaining.FindIndex(member => member.MessageId == item.MessageId);
                var to = remaining.FindIndex(member => member.MessageId == id && member.Content == selected.Content);
                if (from < 0 || to < 0) return;
                var current = remaining[to];
                if (current.IsMine || current.IsAvailable == selected.IsAvailable)
                    ShowChatMedia(current, albumIds, albumStep: to > from ? 1 : -1);
            }
            catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
            {
                if (StillInAlbum()) TransientNotice.Show(this, AppStrings.Get("UiFileDetailsUnavailable"));
            }

            bool StillInAlbum() => !_exiting && !_appLocked && !viewer.IsClosing && version == _mediaNavigationRevision &&
                ReferenceEquals(MediaOverlayHost.Content, viewer) && _viewModel?.SelectedConversation?.Uuid == peer;
        }
    }

    private IReadOnlyList<string>? _chatAlbumIds;

    private void InstallChatMedia(MediaOverlay viewer, bool inGallery, AttachmentPreview? thumbnail = null)
    {
        CloseInlineAudio();
        if (SystemParameters.ClientAreaAnimation && thumbnail?.Message is { } source &&
            source.MessageId == viewer.MessageId && source.Content == viewer.Reference)
            viewer.SetOpeningPhoto(thumbnail.CapturePhotoThumbnail(MediaOverlayHost));
        viewer.SetPhotoReturnFrame(() => _mediaFullScreen is null ? CaptureMediaReturnFrame(viewer) : null);
        viewer.SetFullScreenControl(ToggleMediaFullScreen, _mediaFullScreen is not null);
        if (MediaOverlayHost.Content is IDisposable old) old.Dispose();
        else _focusBeforeMedia = Keyboard.FocusedElement;
        _galleryPreview = inGallery ? viewer : null;
        if (inGallery) _chatAlbumIds = null;
        MediaOverlayHost.Content = viewer;
        UpdateChatVisibility();
    }

    private sealed record MediaWindowPlacement(Rect Bounds, WindowState State, WindowStyle Style, ResizeMode Resize);
    private MediaWindowPlacement? _mediaFullScreen;

    private void ToggleMediaFullScreen()
    {
        if (_mediaFullScreen is not null) { ExitMediaFullScreen(); return; }
        if (_exiting || _appLocked || MediaOverlayHost.Content is not MediaOverlay { IsClosing: false } viewer) return;
        var handle = new System.Windows.Interop.WindowInteropHelper(this).Handle;
        if (handle == IntPtr.Zero) return;
        var bounds = System.Windows.Forms.Screen.FromHandle(handle).Bounds;
        var normal = WindowState == WindowState.Normal ? new Rect(Left, Top, ActualWidth, ActualHeight) : RestoreBounds;
        if (normal.IsEmpty) return;
        _mediaFullScreen = new MediaWindowPlacement(normal, WindowState, WindowStyle, ResizeMode);
        WindowState = WindowState.Normal;
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        const uint keepOrderAndActivation = 0x0004 | 0x0010;
        if (!SetWindowPos(handle, IntPtr.Zero, bounds.Left, bounds.Top, bounds.Width, bounds.Height, keepOrderAndActivation))
        {
            ExitMediaFullScreen();
            return;
        }
        viewer.SetFullScreenControl(ToggleMediaFullScreen, active: true);
    }

    private void ExitMediaFullScreen()
    {
        if (_mediaFullScreen is not { } placement) return;
        _mediaFullScreen = null;
        WindowState = WindowState.Normal;
        WindowStyle = placement.Style;
        ResizeMode = placement.Resize;
        Left = placement.Bounds.Left;
        Top = placement.Bounds.Top;
        Width = placement.Bounds.Width;
        Height = placement.Bounds.Height;
        WindowState = placement.State;
        if (MediaOverlayHost.Content is MediaOverlay viewer) viewer.SetFullScreenControl(ToggleMediaFullScreen, active: false);
    }

    [DllImport("user32.dll", ExactSpelling = true)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowPos(IntPtr window, IntPtr insertAfter, int x, int y, int width, int height, uint flags);

    private Rect? CaptureMediaReturnFrame(MediaOverlay viewer)
    {
        if (!ReferenceEquals(MediaOverlayHost.Content, viewer) ||
            _viewModel is not { } model || model.SelectedConversation?.Uuid != viewer.Peer) return null;
        var item = model.Messages.FirstOrDefault(candidate => candidate.MessageId == viewer.MessageId &&
            candidate.Content == viewer.Reference && !candidate.IsRevoked);
        if (item is null || MessageList.ItemContainerGenerator.ContainerFromItem(item.AlbumAnchor ?? item) is not { } row)
            return null;
        return AttachmentPreview.FindIn(row, item.MessageId)?.CapturePhotoThumbnail(MediaOverlayHost)?.Bounds;
    }

    private async Task ShowGalleryMediaAsync(string peer, string id, string reference, int version,
        AttachmentPreview? thumbnail = null, MediaOverlay? previous = null)
    {
        if (_services is not { } services) return;
        var current = await Task.Run(() => services.Objects.SharedMediaItemAt(peer, id));
        if (!StillNavigating() || current is null || current.Reference != reference ||
            !EncryptedObjectStore.IsAlbumMedia(current.Attachment.Mime)) return;
        if (current.Attachment.Available && !current.IsMine &&
            !await VerifyChatAttachmentAsync(peer, id, reference, StillNavigating, showProgress: previous is null))
        {
            if (!StillNavigating()) return;
            current = await Task.Run(() => services.Objects.SharedMediaItemAt(peer, id));
            if (current is not { Attachment.Available: false } || current.Reference != reference) return;
        }
        if (!StillNavigating()) return;
        InstallGalleryItem(peer, current, services, thumbnail);
        RefreshActiveGallery(this, EventArgs.Empty);

        bool StillNavigating() => !_exiting && !_appLocked && version == _mediaNavigationRevision &&
            _viewModel is { IsSelecting: false, SelectedConversation.Uuid: var currentPeer } model && currentPeer == peer &&
            MediaOverlayHost.Content is not MediaOverlay { IsClosing: true } &&
            (previous is null ? model.IsChatContentVisible : ReferenceEquals(_galleryPreview, previous));
    }

    private void InstallGalleryItem(string peer, SharedMediaItem current, AppServices services, AttachmentPreview? thumbnail = null)
    {
        var source = new ChatMessage(current.Id, peer, current.IsMine, current.Reference, "attachment", current.Timestamp);
        var item = new MessageItem(source, attachmentInfo: current.Attachment,
            openMedia: () => services.Objects.OpenRead(current.Reference), progress: services.Messaging.GetAttachmentProgress(current.Id),
            thumbnailStore: services.Objects);
        var viewer = new MediaOverlay(item, CloseChatMedia, showMessage: () => JumpFromMedia(peer, current.Id),
            changeDownload: message => !_appLocked && !_exiting && _galleryPreview is { IsClosing: false } active &&
                active.MessageId == message.MessageId && active.Reference == message.Content
                    ? message.CanPauseDownload ? services.Messaging.PauseAttachmentDownloadAsync(peer, message.MessageId)
                        : services.Messaging.RequestAttachmentDownloadAsync(peer, message.MessageId)
                    : Task.FromResult(false),
            save: () => ExportChatAttachmentAsync(item), canSave: () => CanExportChatAttachment(item),
            forward: () => ForwardChatMedia(peer, current.Id),
            reply: _viewModel?.CanCompose == true ? () => ReplyToChatMedia(peer, current.Id) : null,
            openSharedMedia: () => OpenSharedMediaFromPreview(item));
        InstallChatMedia(viewer, inGallery: true, thumbnail: thumbnail);
    }

    private async void RefreshActiveGallery(object? sender, EventArgs args)
    {
        if (_chatAlbumIds is { } ids && !_appLocked && !_exiting && MediaOverlayHost.Content is MediaOverlay chat && !chat.IsClosing)
        {
            var current = _viewModel?.Messages.FirstOrDefault(item => item.MessageId == chat.MessageId && !item.IsRevoked);
            if (current is null || current.Content != chat.Reference) { CloseChatMedia(); return; }
            if (current.IsAvailable != chat.IsAvailable) ShowChatMedia(current, ids);
            else
            {
                chat.SetCaption(current.Caption);
                if (!current.IsAvailable) chat.UpdatePendingDownload(current);
                if (ids.Count > 1) chat.RefreshAlbum(CurrentPreviewMembers(ids));
            }
        }
        if (_galleryPreview is null || _services is not { } services || _appLocked || _exiting) return;
        if (_galleryRefreshing) { _galleryRefreshPending = true; return; }
        _galleryRefreshing = true;
        try
        {
            do
            {
                _galleryRefreshPending = false;
                if (_galleryPreview is not { IsClosing: false } viewer || _appLocked || _exiting) return;
                (SharedMediaItem? Row, VisualMediaNeighbors? Neighbors) result;
                try
                {
                    result = await Task.Run(() =>
                    {
                        var row = services.Objects.SharedMediaItemAt(viewer.Peer, viewer.MessageId);
                        var neighbors = row is not null && row.Reference == viewer.Reference
                            ? services.Objects.GetVisualMediaNeighbors(viewer.Peer, new SharedMediaCursor(row.Timestamp, row.Id)) : null;
                        return (row, neighbors);
                    });
                }
                catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
                {
                    if (ReferenceEquals(_galleryPreview, viewer) && !_appLocked && !_exiting)
                        viewer.SetRefreshFailure(() => RefreshActiveGallery(this, EventArgs.Empty));
                    continue;
                }
                if (!ReferenceEquals(_galleryPreview, viewer) || _appLocked || _exiting) continue;
                if (viewer.IsClosing) return;
                if (result.Neighbors is not { } neighbors || result.Row is not { } current) { CloseChatMedia(); return; }
                if (viewer.IsAvailable != current.Attachment.Available)
                {
                    InstallGalleryItem(viewer.Peer, current, services);
                    if (_galleryPreview is not { } refreshed) return;
                    viewer = refreshed;
                }
                viewer.SetRefreshFailure(null);
                viewer.SetCaption(current.Attachment.Caption);
                if (!viewer.IsAvailable)
                {
                    var pending = new ChatMessage(current.Id, viewer.Peer, current.IsMine, current.Reference, "attachment", current.Timestamp);
                    viewer.UpdatePendingDownload(new MessageItem(pending, attachmentInfo: current.Attachment,
                        progress: services.Messaging.GetAttachmentProgress(current.Id)));
                }
                var navigation = neighbors.Newer is null && neighbors.Older is null ? null :
                    new MediaOverlay.PreviewNavigation(neighbors.Newer is not null, neighbors.Older is not null,
                        delta => MoveInGallery(viewer, current, delta));
                viewer.SetNavigation(navigation);
            } while (_galleryRefreshPending);
        }
        finally { _galleryRefreshing = false; }
    }

    private async void MoveInGallery(MediaOverlay viewer, SharedMediaItem current, int delta)
    {
        if (_galleryMoving || _appLocked || _exiting || _services is not { } services || !ReferenceEquals(_galleryPreview, viewer)) return;
        _galleryMoving = true;
        var version = ++_mediaNavigationRevision;
        try
        {
            var neighbors = await Task.Run(() => services.Objects.GetVisualMediaNeighbors(viewer.Peer,
                new SharedMediaCursor(current.Timestamp, current.Id)));
            if (version != _mediaNavigationRevision || !ReferenceEquals(_galleryPreview, viewer)) return;
            var next = delta < 0 ? neighbors.Newer : neighbors.Older;
            if (next is not null) await ShowGalleryMediaAsync(viewer.Peer, next.Id, next.Reference, version, previous: viewer);
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_exiting && version == _mediaNavigationRevision)
                TransientNotice.Show(this, AppStrings.Get("UiFileDetailsUnavailable"));
        }
        finally { _galleryMoving = false; }
    }

    private List<MessageItem> CurrentPreviewMembers(IReadOnlyList<string> albumIds)
    {
        if (_viewModel is not { } model || albumIds.Count == 0) return [];
        var current = model.Messages.Where(member => !member.IsRevoked)
            .ToDictionary(member => member.MessageId, StringComparer.Ordinal);
        return albumIds.Select(id => current.GetValueOrDefault(id)).OfType<MessageItem>().ToList();
    }

    private void Messaging_MediaDownloadProgress(object? sender, AttachmentProgress progress)
    {
        if (progress.PreviewReference is not null || Dispatcher.HasShutdownStarted) return;
        Dispatcher.BeginInvoke(() =>
        {
            if (!_appLocked && !_exiting && _galleryPreview is { } viewer && viewer.MessageId == progress.MessageId)
            {
                viewer.UpdateDownloadProgress(progress);
                if (progress.Phase is AttachmentPhase.Complete or AttachmentPhase.Unavailable)
                    RefreshActiveGallery(this, EventArgs.Empty);
            }
        });
    }

    private void Messaging_MessagesRemoved(object? sender, IReadOnlySet<string> ids)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (_exiting) return;
            if (_quotePickerMessage is { } quote && ids.Contains(quote.MessageId)) CloseReplyQuotePicker();
            if (_inlineAudioPreview?.Message is { } audio && ids.Contains(audio.MessageId)) CloseInlineAudio();
            if (MediaOverlayHost.Content is MediaOverlay viewer && ids.Contains(viewer.MessageId))
            {
                CloseChatMedia();
            }
            _viewModel?.RemoveSelectedMessages(ids);
        });
    }

    private async void SaveAttachment(MessageItem item)
    {
        await ExportChatAttachmentAsync(item);
    }

    private bool CanExportChatAttachment(MessageItem item) => !_appLocked && !_exiting &&
        _services is { } services && services.Database.CanExportAttachment(item.Source);

    private Task ExportChatAttachmentAsync(MessageItem item) =>
        AttachmentExporter.ExportAsync(this, item, () => CanExportChatAttachment(item),
            preferredFolder: _services?.Settings.Snapshot.AttachmentExportFolder);

    private bool TryReplyToMessage(MessageItem item)
    {
        if (item.IsRevoked || _viewModel is not { CanCompose: true } model ||
            !CanRestoreComposerFocus(model, item.Source.ConversationUuid)) return false;
        model.SetReply(item);
        if (model.ReplyTarget?.MessageId != item.MessageId) return false;
        Composer.Focus();
        return true;
    }

    private void Message_PreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (sender is not ListBoxItem { DataContext: MessageItem item } container || _viewModel is not { } model) return;
        if (model.IsSelecting)
        {
            e.Handled = true;
            if (item.HasAlbum)
            {
                if (FindAlbumTile(container, e.OriginalSource as DependencyObject)?.DataContext is not MessageItem member)
                    return;
                item = member;
            }
            model.ToggleSelection(item, extending: (Keyboard.Modifiers & ModifierKeys.Shift) != 0);
        }
        else if (e.ClickCount == 2 && Keyboard.Modifiers == ModifierKeys.None && !IsRestoringHistory &&
                 e.OriginalSource is Grid { Name: "MessageRow" } && TryReplyToMessage(item))
        {
            e.Handled = true;
        }
    }
    private void ClearSelection_Click(object sender,RoutedEventArgs e) {if(_viewModel?.CanChangeSelection==true)_viewModel.ClearSelection();}
    private void ForwardSelected_Click(object sender,RoutedEventArgs e) {if(_viewModel?.CanKeepSelection==true)ForwardMessages(_viewModel.SelectedMessageIds);}
    private async void SaveSelected_Click(object sender,RoutedEventArgs e) {if(_viewModel?.CanKeepSelection==true)await SaveMessagesAsync(_viewModel.SelectedMessageIds);}
    private async void CopySelected_Click(object sender, RoutedEventArgs e) => await CopySelectedMessagesAsync();

    private async Task CopySelectedMessagesAsync(nint owner = 0)
    {
        if (_appLocked || _exiting || _services is not { } services ||
            _viewModel is not { CanUseSelection: true, SelectedConversation: { } peer } model) return;
        var ids = model.SelectedMessageIds;
        var version = model.SelectionVersion;
        var lockGeneration = _appLockGeneration;
        var placeholder = AppStrings.Get("UiHiddenMessageText");
        bool CanPublish() => !_appLocked && !_exiting && ReferenceEquals(model, _viewModel) &&
            lockGeneration == _appLockGeneration && model.SelectedConversation?.Uuid == peer.Uuid &&
            model.SelectionVersion == version;
        try
        {
            var text = await Task.Run(() => string.Join("\n\n", services.Database.GetSelectedMessageText(peer.Uuid, ids)
                .Select(value => MessageTextBlock.CopyableText(value, placeholder, []))
                .Where(value => value.Length > 0)));
            if (!CanPublish()) return;
            if (text.Length == 0) ShowWorkspaceNotice(AppStrings.Get("UiSelectedMessagesNoText"));
            else if (!MessageTextBlock.CopyToClipboard(text, this, showSuccess: owner == 0) && owner != 0)
                ShowWorkspaceNotice(AppStrings.Get("UiMessageCopyFailed"));
        }
        catch (Exception error) when (error is InvalidOperationException or JsonException or KeyNotFoundException
                                      or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (CanPublish()) ShowWorkspaceNotice(AppStrings.Get("UiMessageCopyFailed"));
        }
    }
    private async void DeleteSelected_Click(object sender, RoutedEventArgs e)
    {
        if (_viewModel is { CanUseSelection: true, SelectedConversation: { } peer } model)
            await DeleteLocalMessagesAsync(peer.Uuid, model.SelectedMessageIds, fromSelection: true);
    }

    private bool _checkingLocalDeletion;

    private async Task DeleteLocalMessagesAsync(string peer, IReadOnlyList<string> ids, bool fromSelection = false, nint owner = 0)
    {
        if (_exiting || _appLocked || _checkingLocalDeletion || _services is null ||
            _viewModel is not { CanChangeSelection: true } model ||
            model.SelectedConversation?.Uuid != peer || ids.Count == 0) return;
        var selectionVersion = model.SelectionVersion;
        var lockGeneration = _appLockGeneration;
        bool IsCurrent() => !_exiting && !_appLocked && ReferenceEquals(_viewModel, model) &&
            lockGeneration == _appLockGeneration && model.SelectedConversation?.Uuid == peer &&
            model.SelectionVersion == selectionVersion && model.CanChangeSelection;
        var unavailable = AppStrings.Get("UiLocalDeleteUnavailable");
        var targets = ids.ToHashSet(StringComparer.Ordinal);
        _checkingLocalDeletion = true;
        try
        {
            var canRevoke = await _services.Messaging.DeletionCanRevokeAsync(peer, targets);
            if (!IsCurrent()) return;
            if (canRevoke is null)
            {
                ShowWorkspaceNotice(unavailable);
                return;
            }
            Window confirm = fromSelection && canRevoke == true ? new DeleteMessagesDialog() :
                new ConfirmationDialog(AppStrings.Get("UiLocalDeleteMessage"), AppStrings.Get("UiLocalDeleteTitle"),
                    confirmLabel: AppStrings.Get("UiLocalDelete"));
            if (!ShowWorkspaceDialog(confirm, owner) || !IsCurrent()) return;
            var revokeForBoth = confirm is DeleteMessagesDialog { RevokeForBoth: true };
            if (revokeForBoth) unavailable = AppStrings.Get("UiRevokeSelectedUnavailable");
            if (!await model.DeleteMessagesAsync(peer, targets.ToArray(), revokeForBoth) && !_exiting && !_appLocked)
                ShowWorkspaceNotice(unavailable);
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException or OperationCanceledException)
        {
            if (!_exiting && !_appLocked) ShowWorkspaceNotice(unavailable);
        }
        finally { _checkingLocalDeletion = false; }
    }
    private async Task SaveMessagesAsync(IReadOnlyList<string> ids)
    {
        if (_viewModel is { } model && await model.SaveMessagesAsync(ids))
            ShowWorkspaceNotice(AppStrings.Get("UiSharedMediaSaved"));
    }
    private void ForwardMessages(IReadOnlyList<string> ids, nint owner = 0)
    {
        if (_services is not { } services || _viewModel is not { SelectedConversation: { } peer } model || _appLocked || _exiting) return;
        var version = model.SelectionVersion;
        var clearSelection = model.SelectedMessageIds.ToHashSet(StringComparer.Ordinal).SetEquals(ids);
        if (!ShowWorkspaceDialog(new ForwardMessagesDialog(services, peer.Uuid, ids), owner) || _appLocked || _exiting ||
            !ReferenceEquals(_viewModel, model)) return;
        if (clearSelection && model.SelectedConversation?.Uuid == peer.Uuid) model.ClearSelectionIfCurrent(version);
        ShowWorkspaceNotice(AppStrings.Get("UiForwardingQueued"));
    }

    private void ForwardChatMedia(string peer, string id)
    {
        if (_appLocked || _exiting || _viewModel?.SelectedConversation?.Uuid != peer ||
            MediaOverlayHost.Content is not MediaOverlay viewer || viewer.Peer != peer || viewer.MessageId != id) return;
        CloseChatMedia();
        ForwardMessages([id]);
    }

    private void OpenSharedMediaFromPreview(MessageItem item)
    {
        var peer = item.Source.ConversationUuid;
        if (_appLocked || _exiting || _viewModel?.SelectedConversation?.Uuid != peer ||
            MediaOverlayHost.Content is not MediaOverlay viewer || viewer.Peer != peer || viewer.MessageId != item.MessageId) return;
        CloseChatMedia();
        OpenSharedMedia(peer, item.IsAudio ? SharedMediaCategory.Audio : SharedMediaCategory.Media);
    }

    private void ReplyToChatMedia(string peer, string id)
    {
        if (_appLocked || _exiting || _services is not { } services ||
            _viewModel is not { CanCompose: true } model || model.SelectedConversation?.Uuid != peer ||
            MediaOverlayHost.Content is not MediaOverlay viewer || viewer.Peer != peer || viewer.MessageId != id) return;
        var message = services.Database.GetMessage(id);
        if (message is null || message.IsRevoked || message.ConversationUuid != peer) return;
        CloseChatMedia();
        model.SetReply(new MessageItem(message));
        Composer.Focus();
    }
    private void OpenSavedMessages(nint owner = 0)
    {
        if (_services is not { } services || _viewModel is not { } model || _exiting || _appLocked) return;
        var dialog = new SavedMessagesDialog(services, item =>
        {
            if (_appLocked || _exiting || !ReferenceEquals(_viewModel, model)) return;
            if (owner != 0)
            {
                _qtWorkspace?.RevealConfirmedMessage(item.SourcePeer, item.SourceId, "UiSavedOriginalUnavailable");
                return;
            }
            var conversation = model.Conversations.FirstOrDefault(peer => peer.Uuid == item.SourcePeer);
            if (conversation is null || !services.Database.HasHistoryAnchor(item.SourcePeer, item.SourceId, includeRevoked: false))
            {TransientNotice.Show(this,AppStrings.Get("UiSavedOriginalUnavailable"));return;}
            if (model.SelectedConversation?.Uuid != item.SourcePeer) model.SelectedConversation = conversation;
            model.IsViewingLatest = false;
            if (model.RevealMessage(item.SourceId) is not { IsRevoked: false } target)
            {TransientNotice.Show(this,AppStrings.Get("UiSavedOriginalUnavailable"));return;}
            _restoringHistoryVersion = model.HistoryNavigationVersion;
            ResetVisibleReadCandidates();
            ShowJumpTarget(target, finishInitialRestore: true);
        }, openContact: account => OpenConfirmedContactConversation(model, account));
        dialog.ConfirmOpenChat = peer => ConfirmConversationNavigation(model, peer, dialog);
        ShowWorkspaceDialog(dialog, owner);
    }

    private (ScrollViewer Viewport, long Navigation, double Offset)? _wheelTarget;

    private void MessageList_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        if (GetScrollViewer(MessageList) is not { } scroll || _viewModel is not { } model) return;
        e.Handled = true;
        var lines = SystemParameters.WheelScrollLines;
        if (lines == 0 || e.Delta == 0 || IsRestoringHistory || scroll.ViewportHeight <= 0) return;

        var navigation = model.HistoryNavigationVersion;
        var offset = _wheelTarget is { } pending && pending.Viewport == scroll && pending.Navigation == navigation
            ? pending.Offset : scroll.VerticalOffset;
        if (e.Delta > 0 && offset <= 1 && scroll.VerticalOffset <= 1)
        {
            LoadOlderMessagesAtTop();
            return;
        }
        if (e.Delta < 0 && scroll.ScrollableHeight - offset <= 1 &&
            scroll.ScrollableHeight - scroll.VerticalOffset <= 1 && model.HasNewerMessages)
        {
            LoadHistoryPage(newer: true);
            return;
        }

        // Keep the established 32-DIP step at the standard three-line setting.
        var step = lines < 0 ? scroll.ViewportHeight : Math.Min(lines * (32d / 3d), scroll.ViewportHeight);
        var target = Math.Clamp(offset - e.Delta / 120d * step, 0, scroll.ScrollableHeight);
        if (target == offset) return;
        if (_wheelTarget is null)
        {
            // WPF applies ScrollToVerticalOffset during layout. Keep sub-notch deltas
            // relative to the last requested position until the input and layout have drained.
            Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.ContextIdle,
                new Action(() => _wheelTarget = null));
        }
        _wheelTarget = (scroll, navigation, target);
        scroll.ScrollToVerticalOffset(target);
    }

    private void MessageList_ScrollChanged(object sender, ScrollChangedEventArgs e)
    {
        if (_qtWorkspace is { IsOpen: true } && !IsVisible || IsRestoringHistory || e.OriginalSource is not ScrollViewer viewport ||
            !ReferenceEquals(viewport, GetScrollViewer(MessageList))) return;

        var viewportResized = e.ViewportHeightChange != 0 || e.ViewportWidthChange != 0;
        if (viewportResized) ResetVisibleReadCandidates();
        if ((viewportResized || e.ExtentHeightChange != 0 && e.VerticalChange == 0) &&
            _viewModel is { IsViewingLatest: true })
        {
            _readingPosition = null;
            viewport.ScrollToEnd();
            QueueVisibleReads();
            return;
        }
        if (viewportResized)
        {
            if (_viewModel is { IsChatContentVisible: true, InitialHistoryLoading: false } model &&
                _readingPosition is { } reading && ReferenceEquals(reading.Model, model) &&
                reading.Version == model.HistoryNavigationVersion &&
                model.Messages.FirstOrDefault(item => item.MessageId == reading.Position.MessageId && !item.IsRevoked) is { } target)
            {
                _restoringHistoryVersion = reading.Version;
                RestoreHistoryViewport(target, reading.Position.Offset, reading.Version, finishInitialRestore: true);
                return;
            }
            UpdateViewingLatest();
            return;
        }
        if (e.VerticalChange != 0) _visibleReadSince.Clear();
        UpdateViewingLatest();
        if (e.VerticalChange != 0) UpdateFloatingTimelineDate(viewport);
        if (e.VerticalChange < 0 && e.VerticalOffset <= 1) LoadOlderMessagesAtTop();
        else if (e.VerticalChange > 0 && e.VerticalOffset >= e.ExtentHeight - e.ViewportHeight - 1)
            LoadHistoryPage(newer: true);
    }

    private static DateTime? LocalTimelineDay(MessageItem item) => item.Source.Timestamp is >= 0 and <= 253402300799999L
        ? DateTimeOffset.FromUnixTimeMilliseconds(item.Source.Timestamp).LocalDateTime.Date : null;

    private (MessageItem Item, double Offset)? FindFirstVisibleTimelineRow(ScrollViewer viewport, bool skipRevoked)
    {
        MessageItem? first = null;
        var top = double.PositiveInfinity;
        void FindTopRow(DependencyObject parent)
        {
            for (var index = 0; index < System.Windows.Media.VisualTreeHelper.GetChildrenCount(parent); index++)
            {
                var child = System.Windows.Media.VisualTreeHelper.GetChild(parent, index);
                if (child is not ListBoxItem row)
                {
                    FindTopRow(child);
                    continue;
                }
                if (!row.IsVisible || row.DataContext is not MessageItem { HiddenByAlbum: false } item || !row.IsDescendantOf(viewport)) continue;
                if (skipRevoked && item.IsRevoked) continue;
                var bounds = row.TransformToAncestor(viewport).TransformBounds(new Rect(row.RenderSize));
                if (bounds.Height <= 0 || bounds.Bottom <= 0 || bounds.Top >= viewport.ViewportHeight || bounds.Top >= top) continue;
                top = bounds.Top;
                first = item;
            }
        }
        FindTopRow(MessageList);
        return first is null ? null : (first, top);
    }

    private void UpdateFloatingTimelineDate(ScrollViewer viewport)
    {
        if (_exiting || _appLocked || _viewModel is not { IsViewingLatest: false, IsChatContentVisible: true, IsSelecting: false })
        {
            ClearFloatingTimelineDate();
            return;
        }
        if (FindFirstVisibleTimelineRow(viewport, skipRevoked: false) is not { } row ||
            LocalTimelineDay(row.Item) is not { } value) return;
        var dateButton = FloatingTimelineDate;
        if (!Equals(dateButton.Tag, value))
        {
            dateButton.Tag = value;
            dateButton.Content = AppStrings.TimelineDate(value);
        }
        if (dateButton.Visibility != Visibility.Visible || !dateButton.IsHitTestVisible)
        {
            dateButton.Visibility = Visibility.Visible;
            dateButton.IsHitTestVisible = true;
            dateButton.BeginAnimation(OpacityProperty, null);
            dateButton.Opacity = 1;
        }
        _timelineDateTimer.Stop();
        _timelineDateTimer.Start();
    }

    private void ClearFloatingTimelineDate(bool fade = false)
    {
        _timelineDateTimer.Stop();
        var dateButton = FloatingTimelineDate;
        if (dateButton.Visibility != Visibility.Visible || (!dateButton.IsHitTestVisible && fade)) return;
        dateButton.IsHitTestVisible = false;
        if (fade && SystemParameters.ClientAreaAnimation)
        {
            var animation = new DoubleAnimation(dateButton.Opacity, 0, TimeSpan.FromMilliseconds(200));
            animation.Completed += (_, _) =>
            {
                if (!dateButton.IsHitTestVisible) dateButton.Visibility = Visibility.Collapsed;
            };
            dateButton.BeginAnimation(OpacityProperty, animation);
            return;
        }
        dateButton.BeginAnimation(OpacityProperty, null);
        dateButton.Visibility = Visibility.Collapsed;
    }

    private void UpdateViewingLatest()
    {
        if (_viewModel is not { } model || GetScrollViewer(MessageList) is not { } viewport) return;
        model.IsViewingLatest = !model.HasNewerMessages && viewport.ScrollableHeight - viewport.VerticalOffset <= 48;
        _readingPosition = !model.IsViewingLatest && CaptureHistoryViewport() is { } position
            ? (model, model.HistoryNavigationVersion, position) : null;
        QueueVisibleReads();
    }

    private void ActivityAppActivated(object? sender, EventArgs args) =>
        Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Input, new Action(UpdateActivityForeground));
    private void ActivityAppDeactivated(object? sender, EventArgs args)
    {
        CloseInlineAudio();
        if (_qtWorkspace is { IsOpen: true })
        {
            Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Background, new Action(UpdateActivityForeground));
            return;
        }
        UpdateAppLockPresence(false);
        _services?.Messaging.SetForeground(false);
        _services?.LiveLocations.SetForeground(false);
        _services?.Calls.SetCameraForeground(false);
    }
    private void UpdateActivityForeground()
    {
        if (_services is not { } services) return;
        var present = !_exiting && (_qtWorkspace?.IsForeground == true || HasActiveQtWorkspaceDialog() ||
            IsVisible && WindowState != WindowState.Minimized &&
            System.Windows.Application.Current.Windows.Cast<Window>().Any(window => window.IsActive));
        UpdateAppLockPresence(present);
        var foreground = present && !_appLocked;
        services.Messaging.SetForeground(foreground);
        services.LiveLocations.SetForeground(foreground);
        services.Calls.SetCameraForeground(foreground);
    }
    private void UpdateChatActivity(bool typing=false,bool force=false)
    {
        if(_exiting || _services is null || _viewModel is null)return;
        var peer = !_appLocked && (_qtWorkspace?.IsActive == true || IsActive && IsVisible && WindowState != WindowState.Minimized) &&
            _viewModel.IsChatContentVisible && !IsDropCoveringChat && !IsComposerSendMenuOpen
            ? _viewModel.SelectedConversation?.Uuid : null;
        if(peer==_reportedActivityPeer && !typing && !force)return;
        _reportedActivityPeer=peer;
        _services.Messaging.SetActiveChat(peer,typing && Composer.EditorFocused && !string.IsNullOrWhiteSpace(Composer.Text));
        RefreshPeerActivity();
    }

    private void Messaging_PeerActivityChanged(object? sender, string? peer)
    {
        if (Dispatcher.HasShutdownStarted) return;
        _ = Dispatcher.InvokeAsync(() =>
        {
            if (peer is null || peer == _reportedActivityPeer) RefreshPeerActivity();
        }, System.Windows.Threading.DispatcherPriority.DataBind);
    }

    private void RefreshPeerActivity()
    {
        _activityLabelTimer.Stop();
        if (_viewModel is not { } model) return;
        if (_exiting || _appLocked || !IsActive || !IsVisible || WindowState == WindowState.Minimized ||
            !model.IsChatContentVisible || IsDropCoveringChat ||
            model.SelectedConversation is not { } selected || _services is not { } services)
        {
            model.PeerActivity = PeerActivityStatus.None;
            return;
        }
        var (status, expires) = services.Messaging.GetPeerActivity(selected.Uuid);
        model.PeerActivity = status;
        if (status == PeerActivityStatus.None) return;
        _activityLabelTimer.Interval = TimeSpan.FromMilliseconds(Math.Max(1, expires - Environment.TickCount64));
        _activityLabelTimer.Start();
    }

    private string? _visibleReadPeer;
    private readonly Dictionary<string, long> _visibleReadSince = new(StringComparer.Ordinal);

    private void ResetVisibleReadCandidates()
    {
        _readVisibilityTimer.Stop();
        _visibleReadPeer = null;
        _visibleReadSince.Clear();
    }

    private bool CanReadVisibleMessages => !_exiting && !_appLocked && !IsRestoringHistory &&
        IsActive && IsVisible && WindowState != WindowState.Minimized && !IsDropCoveringChat &&
        !IsComposerSendMenuOpen &&
        _viewModel is { IsChatContentVisible: true, InitialHistoryLoading: false, SelectedConversation: { UnreadCount: > 0 } };

    private void QueueVisibleReads()
    {
        if (!CanReadVisibleMessages)
        {
            ResetVisibleReadCandidates();
            return;
        }
        if (!_readVisibilityTimer.IsEnabled) _readVisibilityTimer.Start();
    }
    private void TimelineMessageRow_Loaded(object sender, RoutedEventArgs e)
    {
        if (sender is Grid row) AnimateTimelineArrival(row);
    }

    private void TimelineMessageRow_DataContextChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (sender is not Grid row) return;
        row.BeginAnimation(OpacityProperty, null);
        if (row.RenderTransform is ScaleTransform scale)
        {
            scale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            scale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
        }
        if (row.IsLoaded) AnimateTimelineArrival(row);
    }

    private void AnimateTimelineArrival(Grid row)
    {
        if (row.DataContext is not MessageItem message || !message.ConsumeRecentArrival() ||
            !SystemParameters.ClientAreaAnimation || !IsActive || _appLocked || _exiting || IsRestoringHistory ||
            _viewModel is not { IsSelecting: false, IsChatContentVisible: true, IsViewingLatest: true }) return;

        var scale = new ScaleTransform();
        row.RenderTransformOrigin = new Point(message.IsMine ? 1 : 0, 1);
        row.RenderTransform = scale;
        var time = TimeSpan.FromMilliseconds(200);
        var ease = new CircleEase { EasingMode = EasingMode.EaseOut };
        row.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, time)
        {
            EasingFunction = ease, FillBehavior = FillBehavior.Stop
        });
        var expand = new DoubleAnimation(0.9, 1, time)
        {
            EasingFunction = ease, FillBehavior = FillBehavior.Stop
        };
        scale.BeginAnimation(ScaleTransform.ScaleXProperty, expand);
        scale.BeginAnimation(ScaleTransform.ScaleYProperty, expand);
    }

    private void ChatTimelineChanged(object? sender, EventArgs args) => QueueVisibleReads();
    private async void MarkVisibleMessagesRead()
    {
        if (!CanReadVisibleMessages || _viewModel is not { SelectedConversation: { } peer } model ||
            GetScrollViewer(MessageList) is not { } viewport)
        {
            ResetVisibleReadCandidates();
            return;
        }
        if (_visibleReadPeer != peer.Uuid)
        {
            ResetVisibleReadCandidates();
            _visibleReadPeer = peer.Uuid;
        }
        var visible = new List<string>();
        CollectVisibleMessageIds(MessageList, viewport, visible);
        var visibleIds = visible.ToHashSet(StringComparer.Ordinal);
        foreach (var id in _visibleReadSince.Keys.Where(id => !visibleIds.Contains(id)).ToArray())
            _visibleReadSince.Remove(id);
        var now = Environment.TickCount64;
        var ready = new List<string>();
        foreach (var id in visibleIds)
        {
            if (_visibleReadSince.TryAdd(id, now)) continue;
            if (now - _visibleReadSince[id] >= 150) ready.Add(id);
        }
        if (visibleIds.Count > ready.Count) _readVisibilityTimer.Start();
        if (ready.Count == 0) return;
        var tracked = ready.ToDictionary(id => id, id => _visibleReadSince[id], StringComparer.Ordinal);
        if (await model.MarkVisibleMessagesReadAsync(ready)) return;
        await Task.Delay(500);
        if (!CanReadVisibleMessages || !ReferenceEquals(_viewModel, model) ||
            model.SelectedConversation?.Uuid != peer.Uuid || _visibleReadPeer != peer.Uuid ||
            GetScrollViewer(MessageList) is not { } currentViewport) return;
        visible.Clear();
        CollectVisibleMessageIds(MessageList, currentViewport, visible);
        var stillVisible = visible.Where(id => tracked.TryGetValue(id, out var since) &&
            _visibleReadSince.TryGetValue(id, out var currentSince) && since == currentSince).ToArray();
        if (stillVisible.Length != 0) await model.MarkVisibleMessagesReadAsync(stillVisible);
    }
    private static void CollectVisibleMessageIds(System.Windows.DependencyObject parent, ScrollViewer viewport, List<string> ids)
    {
        for (var index = 0; index < System.Windows.Media.VisualTreeHelper.GetChildrenCount(parent) && ids.Count < 100; index++)
        {
            var child = System.Windows.Media.VisualTreeHelper.GetChild(parent, index);
            if (child is ListBoxItem { DataContext: MessageItem item } row)
            {
                if (!row.IsVisible || item.IsMine) continue;
                if (item.HasAlbum) CollectVisibleMessageIds(row, viewport, ids);
                else if (!item.IsRead && !item.IsRevoked && FindMessageBody(row) is { } body) AddVisibleMessage(body, item, viewport, ids);
            }
            else if (child is AttachmentPreview { Compact: true, Message: { IsMine: false, IsRead: false, IsRevoked: false } member } tile)
            {
                AddVisibleMessage(tile, member, viewport, ids);
            }
            else
            {
                CollectVisibleMessageIds(child, viewport, ids);
            }
        }
    }

    private static Grid? FindMessageBody(DependencyObject parent)
    {
        for (var index = 0; index < System.Windows.Media.VisualTreeHelper.GetChildrenCount(parent); index++)
        {
            var child = System.Windows.Media.VisualTreeHelper.GetChild(parent, index);
            if (child is Grid { Name: "MessageRow" } body) return body;
            if (FindMessageBody(child) is { } nested) return nested;
        }
        return null;
    }

    private static void AddVisibleMessage(FrameworkElement element, MessageItem item, ScrollViewer viewport, List<string> ids)
    {
        if (!element.IsVisible) return;
        var bounds = element.TransformToAncestor(viewport).TransformBounds(new Rect(element.RenderSize));
        var overlap = Math.Min(bounds.Bottom, viewport.ActualHeight) - Math.Max(bounds.Top, 0);
        if (bounds.Height > 0 && overlap >= Math.Min(bounds.Height, 48) && bounds.Right > 0 && bounds.Left < viewport.ActualWidth)
            ids.Add(item.MessageId);
    }

    private void LoadOlderMessagesAtTop() => LoadHistoryPage(newer: false);

    private async void LoadHistoryPage(bool newer)
    {
        if (_loadingOlderMessages || IsRestoringHistory || _exiting || _appLocked ||
            _viewModel is not { IsChatContentVisible: true } model ||
            !(newer ? model.HasNewerMessages : model.HasOlderMessages)) return;

        _wheelTarget = null;
        ChatHistoryPosition? anchor = null;
        var edge = newer ? model.Messages.LastOrDefault() : model.Messages.FirstOrDefault();
        var version = model.HistoryNavigationVersion;
        _loadingOlderMessages = true;
        try
        {
            var changed = await model.LoadHistoryPageAsync(newer ? HistoryPage.Newer : HistoryPage.Older,
                () => LoadHistoryPage(newer),
                () => anchor = CaptureHistoryViewport());
            if (!changed || _appLocked || _exiting || model.HistoryNavigationVersion != version) return;
            if (anchor is { } position &&
                model.Messages.FirstOrDefault(item => item.MessageId == position.MessageId) is { } target)
            {
                _restoringHistoryVersion = version;
                ResetVisibleReadCandidates();
                RestoreHistoryViewport(target, position.Offset, version, finishInitialRestore: true);
            }
            else if (edge is not null) ScrollToMessage(edge);
        }
        finally { _loadingOlderMessages = false; }
    }

    private void RetryHistory_Click(object sender, RoutedEventArgs e)
    {
        if (!_appLocked && !_exiting) _viewModel?.RetryHistoryLoad();
    }

    private bool? _fileDropRegion;
    private int _fileDropAnimation;
    private object? _pendingDrop;
    private bool IsDropCoveringChat => _pendingDrop is not null || ChatDropIndicator.Visibility == Visibility.Visible;

    private void FinishDropPreparation()
    {
        if (_pendingDrop is null) return;
        _pendingDrop = null;
        DropPreparationIndicator.Visibility = Visibility.Collapsed;
        QueueVisibleReads();
        UpdateChatActivity();
    }

    private void SetDropRegion(bool? asFile, bool immediate = false)
    {
        var previousRegion = _fileDropRegion;
        if (previousRegion == asFile && !immediate) return;
        _fileDropRegion = asFile;
        var animation = ++_fileDropAnimation;
        var visible = asFile.HasValue;
        if (visible) ChatDropIndicator.Visibility = Visibility.Visible;
        if (ChatDropIndicator.Visibility != Visibility.Visible) return;
        var animate = !immediate && SystemParameters.ClientAreaAnimation;
        if (immediate || (previousRegion == false) != (asFile == false)) UpdateOpacity(DropMediaRegion, asFile == false ? 1 : 0);
        if (immediate || (previousRegion == true) != (asFile == true)) UpdateOpacity(DropFileRegion, asFile == true ? 1 : 0);
        if (visible)
        {
            if (!previousRegion.HasValue)
            {
                UpdateOpacity(ChatDropIndicator, 1);
                ResetVisibleReadCandidates();
                UpdateChatActivity();
            }
        }
        else
        {
            UpdateOpacity(ChatDropIndicator, 0, () =>
            {
                if (animation != _fileDropAnimation) return;
                ChatDropIndicator.Visibility = Visibility.Collapsed;
                QueueVisibleReads();
                UpdateChatActivity();
            });
        }

        void UpdateOpacity(UIElement target, double value, Action? completed = null)
        {
            var previous = target.Opacity;
            target.Opacity = value;
            if (!animate || Math.Abs(previous - value) < 0.001)
            {
                target.BeginAnimation(OpacityProperty, null);
                completed?.Invoke();
                return;
            }
            var fade = new DoubleAnimation(previous, value, TimeSpan.FromMilliseconds(200)) { FillBehavior = FillBehavior.Stop };
            if (completed is not null) fade.Completed += (_, _) => completed();
            target.BeginAnimation(OpacityProperty, fade, HandoffBehavior.SnapshotAndReplace);
        }
    }

    private void Chat_PreviewDragOver(object sender, System.Windows.DragEventArgs e)
    {
        _ = sender;
        if (!e.Data.GetDataPresent(DataFormats.FileDrop)) return;
        e.Effects = !_exiting && !_appLocked && _pendingDrop is null &&
            _viewModel is { CanCompose: true, IsChatContentVisible: true }
            ? e.AllowedEffects & DragDropEffects.Copy : DragDropEffects.None;
        SetDropRegion(e.Effects == DragDropEffects.Copy ? DropAsFile(e) : null);
        e.Handled = true;
    }

    private bool DropAsFile(System.Windows.DragEventArgs e) => e.GetPosition(ChatPane).Y >= ChatPane.ActualHeight / 2;

    private void UpdateComposerHeightLimit()
    {
        if (ChatPane.ActualHeight <= 0) return;
        const double timelineReserve = 160;
        const double editorVerticalMargin = 18;
        var header = ChatHeaderRow.ActualHeight + PinnedBannerRow.ActualHeight;
        var accessories = ComposerAccessoriesRow.ActualHeight;
        var limit = Math.Clamp(ChatPane.ActualHeight - header - accessories - timelineReserve - editorVerticalMargin, 36, 224);
        if (Math.Abs(Composer.MaxHeight - limit) < 0.5) return;
        Composer.MaxHeight = limit;
        EditComposer.MaxHeight = limit;
    }

    private void Chat_DragLeave(object sender, System.Windows.DragEventArgs e)
    {
        var point = e.GetPosition(ChatPane);
        if (!new Rect(ChatPane.RenderSize).Contains(point)) SetDropRegion(null);
    }

    private async void Chat_PreviewDrop(object sender, System.Windows.DragEventArgs e)
    {
        _ = sender;
        SetDropRegion(null, immediate: true);
        if (!e.Data.GetDataPresent(DataFormats.FileDrop)) return;
        e.Handled = true;
        if (_exiting || _appLocked || _pendingDrop is not null || (e.AllowedEffects & DragDropEffects.Copy) == 0 ||
            _viewModel is not { CanCompose: true, IsChatContentVisible: true } ||
            e.Data.GetData(DataFormats.FileDrop) is not string[] { Length: > 0 } paths)
        {
            e.Effects = DragDropEffects.None;
            return;
        }
        if (paths.Length > 10)
        {
            e.Effects = DragDropEffects.None;
            TransientNotice.Show(this, AppStrings.Get("UiAttachmentLimit"));
            return;
        }
        e.Effects = DragDropEffects.Copy;
        await ReviewExternalFilesAsync(paths, DropAsFile(e));
    }

    private async Task ReviewExternalFilesAsync(string[] paths, bool asFile)
    {
        if (_exiting || _appLocked || _pendingDrop is not null ||
            _viewModel is not { CanCompose: true, IsChatContentVisible: true, SelectedConversation: { } peer } model ||
            model.CaptureConfirmedMediaBatchSender() is not { } send) return;
        if (paths.Length > 10)
        {
            TransientNotice.Show(this, AppStrings.Get("UiAttachmentLimit"));
            return;
        }
        var options = model.SendOptions;
        var lockGeneration = _appLockGeneration;
        var token = new object();
        _pendingDrop = token;
        DropPreparationIndicator.Visibility = Visibility.Visible;
        ResetVisibleReadCandidates();
        UpdateChatActivity();
        try
        {
            var (entries, unreadable) = await Task.Run(() =>
            {
                var entries = new List<(string Path, bool IsFolder)>();
                var unreadable = false;
                foreach (var path in paths)
                {
                    try
                    {
                        var attributes = File.GetAttributes(path);
                        entries.Add((path, (attributes & FileAttributes.Directory) != 0));
                    }
                    catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException)
                    {
                        unreadable = true;
                    }
                }
                return (entries, unreadable);
            });
            if (!DropIsCurrent()) return;
            DropPreparationIndicator.Visibility = Visibility.Collapsed;
            var knownFiles = entries.Where(entry => !entry.IsFolder).Select(entry => entry.Path)
                .ToHashSet(StringComparer.OrdinalIgnoreCase);
            var files = new List<string>();
            var skippedFolderForLimit = false;
            foreach (var (path, isFolder) in entries)
            {
                if (!isFolder)
                {
                    files.Add(path);
                    continue;
                }
                if (!DropIsCurrent()) return;
                if (knownFiles.Count >= 10)
                {
                    skippedFolderForLimit = true;
                    continue;
                }
                var picker = new OpenFileDialog
                {
                    Title = AppStrings.Get("UiChooseDroppedFolderFiles"),
                    InitialDirectory = path,
                    Filter = $"{AppStrings.Get("UiFiles")}|*.*",
                    Multiselect = knownFiles.Count < 9
                };
                var chosen = picker.ShowDialog(this) == true;
                if (!DropIsCurrent()) return;
                if (!chosen) continue;
                var additions = picker.FileNames.Distinct(StringComparer.OrdinalIgnoreCase)
                    .Where(file => !knownFiles.Contains(file)).ToArray();
                if (knownFiles.Count + additions.Length > 10)
                {
                    skippedFolderForLimit = true;
                    continue;
                }
                knownFiles.UnionWith(additions);
                files.AddRange(additions);
            }
            if (!DropIsCurrent()) return;
            var warnings = new List<string>();
            if (unreadable) warnings.Add(AppStrings.Get("UiDroppedFilesOpenFailed"));
            if (skippedFolderForLimit) warnings.Add(AppStrings.Get("UiDroppedFolderLimit"));
            if (warnings.Count > 0) TransientNotice.Show(this, string.Join(Environment.NewLine, warnings));
            if (files.Count == 0) RestoreComposerAfterAttachment(model, peer.Uuid, lockGeneration);
            else ReviewMedia(files, peer, options, send, asFile);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException)
        {
            if (DropIsCurrent()) TransientNotice.Show(this, AppStrings.Get("UiDroppedFilesOpenFailed"));
        }
        finally
        {
            if (ReferenceEquals(_pendingDrop, token)) FinishDropPreparation();
        }

        bool DropIsCurrent() =>
            ReferenceEquals(_pendingDrop, token) && !_exiting && !_appLocked && lockGeneration == _appLockGeneration &&
            model.CanCompose && model.IsChatContentVisible && ReferenceEquals(_viewModel, model) &&
            ReferenceEquals(model.SelectedConversation, peer);
    }

    private void Calls_ShowRequested(object? sender, EventArgs e)
    {
        _ = sender;
        _ = e;
        if (_exiting || _services is null) { return; }
        var calls = _services.Calls;
        CloseStickerPanel(immediate: true);
        if (_appLocked)
        {
            if (calls.IncomingCallId is { } lockedCallId)
                ((App)System.Windows.Application.Current).ShowIncomingCallNotification(
                    lockedCallId, calls.IsVideoCall, () => calls.IncomingCallId == lockedCallId);
            return;
        }
        if (TryPresentQtCall(calls)) return;
        if (_callWindow is null)
        {
            CloseReplyQuotePicker();
            if (WebRtcView.Parent is Panel previous) previous.Children.Remove(WebRtcView);
            _callWindow = new CallOverlay(calls, WebRtcView);
            _callWindow.CoverageChanged += (_, _) => UpdateChatVisibility();
            CallOverlayHost.Content = _callWindow;
        }
        UpdateChatVisibility();
        if (ShowModalCallWindow(calls)) return;
        if (!IsActive && calls.IncomingCallId is { } id)
        {
            ((App)System.Windows.Application.Current).ShowIncomingCallNotification(
                id, calls.IsVideoCall, () => calls.IncomingCallId == id);
            return;
        }
        ExitMediaFullScreen();
        Show(); WindowState = WindowState.Normal; Activate();
        UpdateActivityForeground();
    }

    internal void FocusIncomingCall(string id)
    {
        if (_exiting || _appLocked || _services is not { } services || services.Calls.IncomingCallId != id) return;
        if (TryPresentQtCall(services.Calls, activate: true)) return;
        ExitMediaFullScreen();
        Calls_ShowRequested(services.Calls, EventArgs.Empty);
        _ = Dispatcher.InvokeAsync(() =>
        {
            if (!_exiting && !_appLocked && services.Calls.IncomingCallId == id)
            {
                if (_modalCallWindow is { IsVisible: true } alert)
                {
                    alert.Activate();
                    alert.FocusIncomingControls();
                }
                else _callWindow?.FocusIncomingControls();
            }
        }, System.Windows.Threading.DispatcherPriority.Loaded);
    }

    private void Calls_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName != nameof(Calls.CallService.State)) return;
        if (Dispatcher.CheckAccess()) UpdatePlayback();
        else _ = Dispatcher.InvokeAsync(UpdatePlayback);

        void UpdatePlayback()
        {
            if (_services is not { } services) return;
            var active = services.Calls.State is not (CallState.Idle or CallState.Ended);
            if (!active) CloseModalCallWindow();
            EncryptedMediaView.SetCallActive(active);
            if (active) CloseInlineAudio();
            if (services.Calls.IsIncoming) _recordingDialog?.YieldForIncomingCall();
            if (!active && _interruptedRecording is not null) Dispatcher.BeginInvoke(new Action(ReviewInterruptedRecording));
            if (!services.Calls.IsIncoming)
                ((App)System.Windows.Application.Current).DismissIncomingCallNotification();
        }
    }

    private bool ShowModalCallWindow(CallService calls)
    {
        if (_exiting || _appLocked || calls.IncomingCallId is not { } id) return false;
        if (_modalCallWindow is { IsVisible: true } existing && existing.CallId == id) return true;
        var owner = System.Windows.Application.Current.Windows.OfType<DialogWindow>()
            .FirstOrDefault(window => window.IsActive && window.IsVisible && window != _recordingDialog &&
                window is not ModalCallWindow);
        if (owner is null) return false;
        var ancestor = owner.Owner;
        while (ancestor is not null && !ReferenceEquals(ancestor, this)) ancestor = ancestor.Owner;
        if (ancestor is null && !IsQtWorkspaceDialog(owner)) return false;

        CloseModalCallWindow();
        var alert = new ModalCallWindow(owner, calls, id,
            () => !_exiting && !_appLocked && ReferenceEquals(_services?.Calls, calls));
        _modalCallWindow = alert;
        alert.ShowActivated = false;
        alert.Loaded += (_, _) => PlaceModalCallWindow(alert, owner);
        alert.Closed += (_, _) =>
        {
            if (ReferenceEquals(_modalCallWindow, alert)) _modalCallWindow = null;
        };
        alert.Show();
        return true;
    }

    private static void PlaceModalCallWindow(ModalCallWindow alert, Window owner)
    {
        var ownerHandle = new System.Windows.Interop.WindowInteropHelper(owner).Handle;
        var alertHandle = new System.Windows.Interop.WindowInteropHelper(alert).Handle;
        if (!GetWindowRect(ownerHandle, out var ownerBounds) || !GetWindowRect(alertHandle, out var alertBounds))
            return;

        var workArea = System.Windows.Forms.Screen.FromHandle(ownerHandle).WorkingArea;
        var gap = (int)Math.Round(12 * VisualTreeHelper.GetDpi(owner).DpiScaleX);
        var width = alertBounds.Right - alertBounds.Left;
        var height = alertBounds.Bottom - alertBounds.Top;
        var rightLimit = Math.Max(workArea.Left, workArea.Right - width);
        var right = ownerBounds.Right + gap;
        var left = ownerBounds.Left - width - gap;
        var x = right <= rightLimit ? right : left >= workArea.Left ? left : rightLimit;
        x = Math.Clamp(x, workArea.Left, rightLimit);
        var y = Math.Clamp(ownerBounds.Top, workArea.Top, Math.Max(workArea.Top, workArea.Bottom - height));
        const uint keepSizeOrderAndActivation = 0x0001 | 0x0004 | 0x0010;
        _ = SetWindowPos(alertHandle, IntPtr.Zero, x, y, 0, 0, keepSizeOrderAndActivation);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeWindowBounds
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [DllImport("user32.dll", ExactSpelling = true)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(IntPtr window, out NativeWindowBounds bounds);

    private void CloseModalCallWindow()
    {
        var alert = _modalCallWindow;
        _modalCallWindow = null;
        alert?.Close();
    }

    private bool _callCoveredChat;
    private string? _peerBeforeCallOverlay;
    private IInputElement? _focusBeforeCallOverlay;

    private void SetNativeChatVisibility()
    {
        var visible = MediaOverlayHost.Content is null && _callWindow?.CoversChat != true &&
            ChatDropIndicator.Visibility != Visibility.Visible && DropPreparationIndicator.Visibility != Visibility.Visible;
        ChatHeader.Visibility = EditorSurface.Visibility = visible ? Visibility.Visible : Visibility.Hidden;
    }

    private void UpdateChatVisibility()
    {
        if (_viewModel is null) return;
        var callCoversChat = _callWindow?.CoversChat == true;
        var showNativeSurfaces = MediaOverlayHost.Content is null && !callCoversChat;
        SidebarSurface.Visibility = showNativeSurfaces ? Visibility.Visible : Visibility.Hidden;
        SetNativeChatVisibility();
        if (callCoversChat && !_callCoveredChat)
        {
            _peerBeforeCallOverlay = _viewModel.SelectedConversation?.Uuid;
            _focusBeforeCallOverlay = IsKeyboardFocusWithin && _callWindow?.IsKeyboardFocusWithin != true
                ? Keyboard.FocusedElement : null;
        }
        var restoreCallFocus = _callCoveredChat && !callCoversChat;
        _callCoveredChat = callCoversChat;
        _viewModel.IsChatContentVisible = MediaOverlayHost.Content is null && !callCoversChat && _replyQuotePicker is null &&
            AttachmentPanel.Visibility != Visibility.Visible && StickerHost.Visibility != Visibility.Visible;
        if (restoreCallFocus)
        {
            if (!_appLocked && !_exiting && IsActive && _viewModel.IsChatContentVisible &&
                _viewModel.SelectedConversation?.Uuid == _peerBeforeCallOverlay &&
                _focusBeforeCallOverlay is UIElement { IsEnabled: true, IsVisible: true, Focusable: true } previous)
                Keyboard.Focus(previous);
            _focusBeforeCallOverlay = null;
            _peerBeforeCallOverlay = null;
            if (_deferredNotification is not null) Dispatcher.BeginInvoke(new Action(OpenDeferredNotification));
            if (_interruptedRecording is not null) Dispatcher.BeginInvoke(new Action(ReviewInterruptedRecording));
            if (UseQtWorkspace) Dispatcher.BeginInvoke(new Action(ScheduleQtWorkspace));
        }
        if (!_viewModel.IsChatContentVisible) { ClearFloatingTimelineDate(); CloseInlineAudio(); }
        if (!_viewModel.IsChatContentVisible)
        {
            FinishDropPreparation();
            SetDropRegion(null, immediate: true);
        }
        QueueVisibleReads();
        UpdateChatActivity();
    }

    private void Latest_Click(object sender, RoutedEventArgs e)
    {
        if (IsRestoringHistory || _appLocked || _exiting || _viewModel is not { } model) return;
        model.LoadLatestMessages(() =>
        {
            ClearQuoteReturnAnchors();
            ClearJumpHighlight();
            ClearFloatingTimelineDate();
        });
    }

    private void ScrollToLatest()
    {
        if (GetScrollViewer(MessageList) is { } scroll) scroll.ScrollToEnd();
        else if (_viewModel?.Messages.LastOrDefault() is { } last) ScrollToMessage(last);
    }

    internal static ScrollViewer? GetScrollViewer(DependencyObject root)
    {
        for (var index = 0; index < System.Windows.Media.VisualTreeHelper.GetChildrenCount(root); index++)
        {
            var child = System.Windows.Media.VisualTreeHelper.GetChild(root, index);
            if (child is ScrollViewer viewer) { return viewer; }
            if (GetScrollViewer(child) is { } nested) { return nested; }
        }
        return null;
    }

    private static MessageTextBlock? FindMessageBody(DependencyObject root, string source)
    {
        for (var index = 0; index < System.Windows.Media.VisualTreeHelper.GetChildrenCount(root); index++)
        {
            var child = System.Windows.Media.VisualTreeHelper.GetChild(root, index);
            if (child is MessageTextBlock { Name: "MessageContent", IsLoaded: true } body && body.MessageText == source)
                return body;
            if (FindMessageBody(child, source) is { } nested) return nested;
        }
        return null;
    }

    private bool Confirm(string message) => new ConfirmationDialog(message) { Owner = this }.ShowDialog() == true;

    private void RestoreWindowPlacement()
    {
        try
        {
            var path = PlacementPath();
            if (!File.Exists(path)) { return; }
            var value = JsonSerializer.Deserialize<WindowPlacement>(File.ReadAllText(path));
            if (value is null) return;
            _chatTextPercentage = value.ChatTextPercentage;
            _closeToTray = value.CloseToTray ?? true;
            _qtWindowGeometry = value.QtGeometry;
            _qtSidebarWidth = value.QtSidebarWidth;
            WindowAppearance.SetTheme(value.Theme);
            AppStrings.SetLanguage(value.Language);
            if (!double.IsFinite(value.Width) || !double.IsFinite(value.Height) ||
                value.Width < MinWidth || value.Height < MinHeight) return;
            if (value.SidebarRatio is > 0 and < 1)
                _preferredSidebarRatio = value.SidebarRatio.Value;
            else if (value.SidebarWidth is { } width && double.IsFinite(width))
                _preferredSidebarRatio = Math.Clamp(width, SidebarColumn.MinWidth, MaximumSidebarWidth) / value.Width;
            Width = value.Width;
            Height = value.Height;
            Left = Math.Max(SystemParameters.VirtualScreenLeft, Math.Min(value.Left, SystemParameters.VirtualScreenLeft + SystemParameters.VirtualScreenWidth - Width));
            Top = Math.Max(SystemParameters.VirtualScreenTop, Math.Min(value.Top, SystemParameters.VirtualScreenTop + SystemParameters.VirtualScreenHeight - Height));
        }
        catch (Exception exception) when (exception is IOException or JsonException or UnauthorizedAccessException)
        {
            Trace.TraceWarning("Window placement was ignored: {0}", exception.Message);
        }
    }

    private bool SetCloseToTray(bool enabled)
    {
        if (_exiting || _appLocked) return false;
        var previous = _closeToTray;
        _closeToTray = enabled;
        if (SaveWindowPlacement()) return true;
        _closeToTray = previous;
        return false;
    }

    private bool SaveWindowPlacement()
    {
        if (_qtWorkspace?.CaptureWindowPlacement() is { } qt)
            (_qtWindowGeometry, _qtSidebarWidth) = (qt.Geometry, qt.SidebarWidth);
        var bounds = _mediaFullScreen?.Bounds ?? (WindowState == WindowState.Normal ? new Rect(Left, Top, ActualWidth, ActualHeight) : RestoreBounds);
        if (bounds.IsEmpty || bounds.Width < MinWidth || bounds.Height < MinHeight ||
            !double.IsFinite(bounds.X) || !double.IsFinite(bounds.Y)) return false;
        try
        {
            var path = PlacementPath();
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var temporary = path + ".tmp";
            var sidebarWidth = Math.Clamp(bounds.Width * _preferredSidebarRatio, SidebarColumn.MinWidth, MaximumSidebarWidth);
            File.WriteAllText(temporary, JsonSerializer.Serialize(new WindowPlacement(bounds.X, bounds.Y, bounds.Width, bounds.Height,
                sidebarWidth, _chatTextPercentage, WindowAppearance.Theme, AppStrings.Language, _closeToTray, _preferredSidebarRatio,
                _qtWindowGeometry, _qtSidebarWidth)));
            File.Move(temporary, path, overwrite: true);
            return true;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            Trace.TraceWarning("Window placement was not saved: {0}", error.Message);
            return false;
        }
    }

    private void RememberSidebarWidth()
    {
        if (LayoutGrid.ActualWidth > 0)
            _preferredSidebarRatio = SidebarColumn.Width.Value / LayoutGrid.ActualWidth;
    }

    private void ApplyChatTextPercentage(int percentage)
    {
        _chatTextPercentage = SettingsDialog.ChatTextPercentages.Contains(percentage) ? percentage : 100;
        Resources["ChatMessageFontSize"] = 14 * _chatTextPercentage / 100d;
        Resources["ChatComposerFontSize"] = 16 * _chatTextPercentage / 100d;
        _qtWorkspace?.SetChatTextPercentage(_chatTextPercentage);
    }

    private void ApplySidebarWidth(double layoutWidth)
    {
        if (layoutWidth <= 0) return;
        var minimum = SidebarColumn.MinWidth;
        var maximum = Math.Clamp(layoutWidth - ChatColumn.MinWidth - SidebarSplitter.Width, minimum, MaximumSidebarWidth);
        SidebarColumn.MaxWidth = maximum;
        SidebarColumn.Width = new GridLength(Math.Clamp(Math.Round(layoutWidth * _preferredSidebarRatio), minimum, maximum));
    }

    private static string PlacementPath() => Path.Combine(MsgAT.Windows.Core.Storage.RuntimePaths.DataDirectory, "window-v2.json");
    private sealed record WindowPlacement(double Left, double Top, double Width, double Height,
        double? SidebarWidth = null, int ChatTextPercentage = 100, AppThemeMode Theme = AppThemeMode.System,
        AppLanguage Language = AppLanguage.System, bool? CloseToTray = null, double? SidebarRatio = null,
        string? QtGeometry = null, int QtSidebarWidth = 0);
}
