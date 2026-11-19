using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.ViewModels;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.Media;

namespace MsgAT.Windows.Views;

public sealed class MediaOverlay : UserControl, IDisposable
{
    private static readonly Brush PreviewSurfaceBrush = CreatePreviewSurfaceBrush();
    private readonly Action _dismiss;
    private readonly EncryptedMediaView? _encrypted;
    private readonly Button? _zoomOut;
    private readonly Button? _fit;
    private readonly Button? _zoomIn;
    private readonly StackPanel _navigationPanel;
    private readonly Button _back;
    private readonly Button _forward;
    private readonly TextBlock _position;
    private readonly Grid _toolbar;
    private readonly Button _fullScreenButton;
    private Action? _toggleFullScreen;
    private bool _fullScreenActive;
    private readonly ScrollViewer _albumStrip = new() { Visibility = Visibility.Collapsed };
    private readonly ScrollViewer _captionScroll;
    private readonly Border _captionPanel;
    private readonly Border _refreshFailure;
    private readonly Button _retryRefresh;
    private readonly MessageTextBlock _caption;
    private Action? _retryMediaRefresh;
    private readonly CancellationTokenSource _thumbnailsLifetime = new();
    private readonly DispatcherTimer _toolbarIdle;
    private readonly bool _canHidePlaybackToolbar;
    private PreviewNavigation? _navigation;
    private Action<double, Point?>? _zoom;
    private (BitmapSource Thumbnail, Rect Bounds)? _openingPhoto;
    private Canvas? _photoTransition;
    private Image? _photoImage;
    private Func<Rect?>? _returnFrame;
    private bool _photoEntranceRunning;
    private bool _photoReady;
    private bool _toolbarVisible = true;
    private bool _usingKeyboard;
    private bool _disposed;
    private StackPanel? _downloadPanel;
    private bool _downloadBusy;
    private bool _downloadFailed;
    private MessageItem? _pendingDownload;
    private Button? _downloadAction;
    private TextBlock? _downloadStatus;
    private System.Windows.Controls.ProgressBar? _downloadProgress;
    private TextBlock? _downloadError;
    private ContextMenu? _mediaActions;
    private readonly Func<MessageItem, Task<bool>>? _changeDownload;
    internal string MessageId { get; }
    internal string Peer { get; }
    internal string Reference { get; }
    internal bool IsMine { get; }
    internal bool IsAvailable { get; }
    internal bool IsClosing { get; private set; }
    public sealed record PreviewNavigation(bool CanMoveBack, bool CanMoveForward, Action<int> Move, string? Position = null,
        IReadOnlyList<MessageItem>? Album = null, Action<string>? Select = null);

    public MediaOverlay(MessageItem message, Action dismiss, PreviewNavigation? navigation = null, Action? showMessage = null,
        Func<MessageItem, Task<bool>>? changeDownload = null, Func<Task>? save = null, Func<bool>? canSave = null,
        Action? forward = null, Action? reply = null, Action? openSharedMedia = null, int albumStep = 0)
    {
        MessageId = message.MessageId;
        Peer = message.Source.ConversationUuid;
        Reference = message.Content;
        IsMine = message.IsMine;
        IsAvailable = message.IsAvailable;
        _dismiss = dismiss;
        _changeDownload = changeDownload;
        var stillPhoto = message.IsAvailable && message.IsImage && message.Mime != "image/gif";
        _canHidePlaybackToolbar = message.IsAvailable && (message.IsVideo || message.Mime == "image/gif");
        _toolbar = new Grid { Margin = new Thickness(12, 6, 12, 6) };
        _toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _toolbar.ColumnDefinitions.Add(new ColumnDefinition());
        var actions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        if (stillPhoto)
        {
            _zoomOut = ToolbarButton("ZoomOutIcon", AppStrings.Get("UiMediaZoomOut"), () => _zoom?.Invoke(1 / 1.25, null), false);
            _fit = ToolbarButton("FitMediaIcon", AppStrings.Get("UiMediaFitWindow"), () => _zoom?.Invoke(0, null), false);
            _zoomIn = ToolbarButton("MenuPlusIcon", AppStrings.Get("UiMediaZoomIn"), () => _zoom?.Invoke(1.25, null), false);
            actions.Children.Add(_zoomOut);
            actions.Children.Add(_fit);
            actions.Children.Add(_zoomIn);
        }
        if (message.IsAvailable && (message.IsVideo || message.IsAudio))
        {
            _encrypted = new EncryptedMediaView(message, HandleEscape, showClose: false, movePreview: MovePreview);
            actions.Children.Add(_encrypted.CreatePlaybackSpeedButton(Brushes.White));
        }
        if (CreateMediaActions(showMessage, openSharedMedia, message.IsRevoked ? null : reply,
                message.IsAvailable ? forward : null, message.IsAvailable ? save : null, canSave) is { } menu)
            actions.Children.Add(menu);
        _fullScreenButton = ToolbarButton("MediaFullScreenIcon", AppStrings.Get("UiMediaFullScreen"), () => _toggleFullScreen?.Invoke());
        _fullScreenButton.Visibility = Visibility.Collapsed;
        actions.Children.Add(_fullScreenButton);
        actions.Children.Add(ToolbarButton("CloseIcon", AppStrings.Get("UiCloseMediaPreview"), RequestDismissal));
        _navigationPanel = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
        _back = ToolbarButton("PreviousMediaIcon", AppStrings.Get("UiMediaNewer"), () => MovePreview(-1));
        _forward = ToolbarButton("NextMediaIcon", AppStrings.Get("UiMediaOlder"), () => MovePreview(1));
        _position = new TextBlock
        {
            Foreground = Brushes.White, Background = PreviewSurfaceBrush, VerticalAlignment = VerticalAlignment.Center,
            Padding = new Thickness(6, 3, 6, 3), Margin = new Thickness(4, 0, 4, 0)
        };
        _navigationPanel.Children.Add(_back);
        _navigationPanel.Children.Add(_position);
        _navigationPanel.Children.Add(_forward);
        Grid.SetColumn(actions, 1);
        _toolbar.Children.Add(_navigationPanel);
        _toolbar.Children.Add(actions);
        SetNavigation(navigation);
        _caption = new MessageTextBlock
        {
            Foreground = Brushes.White,
            LinkForeground = Brushes.LightSkyBlue,
            TextWrapping = TextWrapping.Wrap,
            Padding = new Thickness(12, 6, 12, 6)
        };
        _caption.SetResourceReference(TextBlock.FontSizeProperty, "ChatMessageFontSize");
        _captionScroll = new ScrollViewer { Content = _caption, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        _captionPanel = new Border
        {
            Background = new SolidColorBrush(Color.FromArgb(230, 0, 0, 0)),
            CornerRadius = new CornerRadius(8),
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(12, 0, 12, 6),
            MaxWidth = 600,
            Child = _captionScroll
        };
        SetCaption(message.Caption);
        var footer = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom };
        _retryRefresh = new Button { Content = AppStrings.Get("UiRetry"), Margin = new Thickness(12, 0, 0, 0) };
        _retryRefresh.Click += (_, _) => _retryMediaRefresh?.Invoke();
        var refreshError = new DockPanel();
        DockPanel.SetDock(_retryRefresh, Dock.Right);
        refreshError.Children.Add(_retryRefresh);
        refreshError.Children.Add(new TextBlock
        {
            Text = AppStrings.Get("UiSharedMediaLoadFailed"), Foreground = Brushes.White,
            TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center
        });
        _refreshFailure = new Border
        {
            Child = refreshError, Background = PreviewSurfaceBrush,
            Margin = new Thickness(12, 0, 12, 6), Padding = new Thickness(12, 6, 12, 6), CornerRadius = new CornerRadius(8),
            HorizontalAlignment = HorizontalAlignment.Center, MaxWidth = 500, Visibility = Visibility.Collapsed
        };
        footer.Children.Add(_refreshFailure);
        footer.Children.Add(_captionPanel);
        footer.Children.Add(_albumStrip);
        _albumStrip.PreviewMouseWheel += (_, e) =>
        {
            if (_albumStrip.ScrollableWidth <= 0 || IsClosing) return;
            const double tileWidth = 54;
            var offset = _albumStrip.HorizontalOffset - tileWidth * e.Delta / 120d;
            _albumStrip.ScrollToHorizontalOffset(Math.Clamp(offset, 0, _albumStrip.ScrollableWidth));
            e.Handled = true;
        };
        SizeChanged += (_, e) => _captionScroll.MaxHeight = Math.Min(150, e.NewSize.Height * 0.25);
        var content = new Grid
        {
            Background = stillPhoto ? new SolidColorBrush(Colors.Black) : Brushes.Black,
            ClipToBounds = true
        };
        content.MouseLeftButtonUp += (_, e) =>
        {
            if (e.OriginalSource != content) return;
            e.Handled = true;
            RequestDismissal();
        };
        if (stillPhoto)
        {
            var root = new Grid { Background = Brushes.Transparent, ClipToBounds = true };
            _toolbar.VerticalAlignment = VerticalAlignment.Top;
            root.Children.Add(content);
            root.Children.Add(_toolbar);
            root.Children.Add(footer);
            Content = root;
        }
        else
        {
            var root = new DockPanel { Background = Brushes.Black, ClipToBounds = true };
            DockPanel.SetDock(_toolbar, Dock.Top);
            root.Children.Add(_toolbar);
            DockPanel.SetDock(footer, Dock.Bottom);
            root.Children.Add(footer);
            root.Children.Add(content);
            Content = root;
        }
        _toolbarIdle = new DispatcherTimer(DispatcherPriority.Background)
        {
            Interval = TimeSpan.FromMilliseconds(1400)
        };
        _toolbarIdle.Tick += ToolbarIdle_Tick;
        PreviewMouseMove += (_, _) => RegisterPointerActivity();
        PreviewMouseDown += (_, _) => RegisterPointerActivity();
        PreviewMouseUp += (_, _) => RegisterPointerActivity();
        PreviewMouseWheel += (_, _) => RegisterPointerActivity();
        Unloaded += (_, _) => Dispose();
        PreviewKeyDown += (_, e) =>
        {
            if (IsClosing) { e.Handled = true; return; }
            if (HandleViewShortcut(e)) return;
            _usingKeyboard = true;
            RevealToolbar();
            if (_navigation is not null && Keyboard.Modifiers == ModifierKeys.None &&
                !_captionPanel.IsKeyboardFocusWithin && !_albumStrip.IsKeyboardFocusWithin &&
                Keyboard.FocusedElement is not Slider && e.Key is (Key.Left or Key.Right))
            {
                e.Handled = true;
                if (!e.IsRepeat) MovePreview(e.Key == Key.Left ? -1 : 1);
            }
            else if (_zoom is { } zoom && Keyboard.Modifiers == ModifierKeys.Control)
            {
                var factor = e.Key switch
                {
                    Key.Add or Key.OemPlus => 1.25,
                    Key.Subtract or Key.OemMinus => 1 / 1.25,
                    Key.D0 or Key.NumPad0 => 0,
                    _ => -1
                };
                if (factor >= 0) { zoom(factor, null); e.Handled = true; }
            }
        };
        Focusable = true;
        KeyboardNavigation.SetTabNavigation(this, KeyboardNavigationMode.Cycle);
        Loaded += (_, _) => { Focus(); RevealToolbar(); };
        if (!message.IsAvailable)
        {
            _downloadPanel = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            content.Children.Add(_downloadPanel);
            UpdatePendingDownload(message);
        }
        else if (message.IsVideo || message.IsAudio || message.Mime == "image/gif")
        {
            _encrypted ??= new EncryptedMediaView(message, HandleEscape, showClose: false, movePreview: MovePreview);
            if (_canHidePlaybackToolbar) _encrypted.ReportViewerActivity = keyboard =>
            {
                _usingKeyboard = keyboard;
                RevealToolbar();
            };
            content.Children.Add(_encrypted);
        }
        else
        {
            var loading = false;
            Loaded += async (_, _) =>
            {
                if (loading) return;
                loading = true;
                var feedback = PreviewLoading();
                content.Children.Add(feedback);
                BeginPhotoEntrance(content);
                try { await LoadImageAsync(message, content, albumStep); }
                finally { content.Children.Remove(feedback); }
            };
        }
    }

    internal void UpdatePendingDownload(MessageItem message)
    {
        if (_disposed || _downloadPanel is not { } panel) return;
        if (!ReferenceEquals(_pendingDownload, message))
        {
            if (_pendingDownload is not null) _pendingDownload.PropertyChanged -= PendingDownloadChanged;
            message.PropertyChanged += PendingDownloadChanged;
        }
        _pendingDownload = message;
        if (_downloadAction is null)
        {
            _downloadStatus = Label("");
            _downloadProgress = new System.Windows.Controls.ProgressBar
            {
                Maximum = 100, Height = 4, Width = 184, Margin = new Thickness(0, 5, 0, 0),
                HorizontalAlignment = HorizontalAlignment.Center, IsHitTestVisible = false
            };
            _downloadError = Label(AppStrings.Get("UiMediaPlaybackUnavailable"));
            _downloadAction = new Button { HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 8, 0, 0) };
            _downloadAction.Click += ChangePendingDownload;
            panel.Children.Add(_downloadStatus);
            panel.Children.Add(_downloadProgress);
            panel.Children.Add(_downloadError);
            panel.Children.Add(_downloadAction);
        }
        var canChange = _changeDownload is not null && (message.CanPauseDownload || message.CanRequestDownload);
        _downloadStatus!.Text = canChange ? message.AlbumDownloadStatus : AppStrings.Get("UiMediaPlaybackUnavailable");
        _downloadProgress!.Value = message.TransferPercent;
        _downloadProgress.Visibility = canChange && message.HasTransferProgress ? Visibility.Visible : Visibility.Collapsed;
        System.Windows.Automation.AutomationProperties.SetName(_downloadProgress, _downloadStatus.Text);
        _downloadError!.Visibility = canChange && _downloadFailed ? Visibility.Visible : Visibility.Collapsed;
        _downloadAction.Visibility = canChange ? Visibility.Visible : Visibility.Collapsed;
        _downloadAction.Content = AppStrings.Get(message.CanRetryDownload ? "UiRetry" : message.DownloadActionKey);
        _downloadAction.IsEnabled = canChange && !_downloadBusy;
    }

    internal void UpdateDownloadProgress(AttachmentProgress progress)
    {
        if (!_disposed && !IsClosing && _pendingDownload is { } pending && progress.MessageId == pending.MessageId)
            pending.UpdateProgress(progress);
    }

    internal void SetRefreshFailure(Action? retry)
    {
        if (_disposed || IsClosing) return;
        _retryMediaRefresh = retry;
        if (retry is null && _retryRefresh.IsKeyboardFocused) Focus();
        _refreshFailure.Visibility = retry is null ? Visibility.Collapsed : Visibility.Visible;
        RevealToolbar();
    }

    private void PendingDownloadChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs args)
    {
        if (args.PropertyName == nameof(MessageItem.AlbumDownloadStatus) && _pendingDownload is { } message) UpdatePendingDownload(message);
    }

    private async void ChangePendingDownload(object sender, RoutedEventArgs args)
    {
        if (_downloadBusy || _disposed || IsClosing || _pendingDownload is not { } message || _changeDownload is not { } change) return;
        _downloadBusy = true;
        UpdatePendingDownload(message);
        try { _downloadFailed = !await change(message); }
        catch (Exception error) when (error is IOException or InvalidOperationException or OperationCanceledException or Microsoft.Data.Sqlite.SqliteException)
        { _downloadFailed = true; }
        finally
        {
            _downloadBusy = false;
            if (_pendingDownload is { } current) UpdatePendingDownload(current);
        }
    }

    internal void SetOpeningPhoto((BitmapSource Thumbnail, Rect Bounds)? photo) => _openingPhoto = photo;

    private void MovePreview(int delta)
    {
        if (_disposed || IsClosing || _navigation is not { } navigation) return;
        if (delta < 0 ? navigation.CanMoveBack : navigation.CanMoveForward) navigation.Move(delta);
    }

    internal bool HandleViewShortcut(KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            e.Handled = true;
            if (!e.IsRepeat) HandleEscape();
            return true;
        }
        if (e.Key != Key.F11 || Keyboard.Modifiers != ModifierKeys.None || _toggleFullScreen is null) return false;
        e.Handled = true;
        if (!e.IsRepeat && !_disposed && !IsClosing) _toggleFullScreen();
        return true;
    }

    private void HandleEscape()
    {
        if (_disposed || IsClosing) return;
        if (_mediaActions is { IsOpen: true } menu) menu.IsOpen = false;
        else if (_fullScreenActive) _toggleFullScreen?.Invoke();
        else RequestDismissal();
    }

    internal void SetPhotoReturnFrame(Func<Rect?> lookup) => _returnFrame = lookup;

    private void BeginPhotoEntrance(Grid root)
    {
        var opening = _openingPhoto;
        _openingPhoto = null;
        if (opening is not { } photo || !SystemParameters.ClientAreaAnimation ||
            root.ActualWidth <= 0 || root.ActualHeight <= 0 || photo.Bounds.IsEmpty ||
            !new Rect(root.RenderSize).Contains(photo.Bounds) || root.Background is not SolidColorBrush backdrop)
            return;

        var tile = AddPhotoTransition(root, photo.Thumbnail);
        var layer = _photoTransition!;
        _photoEntranceRunning = true;

        Rect FittedFrame()
        {
            var ratio = Math.Min(root.ActualWidth / photo.Thumbnail.PixelWidth,
                root.ActualHeight / photo.Thumbnail.PixelHeight);
            var width = photo.Thumbnail.PixelWidth * ratio;
            var height = photo.Thumbnail.PixelHeight * ratio;
            return new Rect((root.ActualWidth - width) / 2, (root.ActualHeight - height) / 2, width, height);
        }

        layer.SizeChanged += (_, _) =>
        {
            if (!_photoEntranceRunning) SetPhotoFrame(tile, FittedFrame());
        };
        var duration = new Duration(TimeSpan.FromMilliseconds(200));
        AnimatePhotoFrame(tile, photo.Bounds, FittedFrame(), duration, EasingMode.EaseOut, () =>
        {
            if (_disposed || !ReferenceEquals(_photoTransition, layer)) return;
            _photoEntranceRunning = false;
            if (_photoReady) RemovePhotoTransition();
            else SetPhotoFrame(tile, FittedFrame());
        });
        backdrop.BeginAnimation(Brush.OpacityProperty, new DoubleAnimation(0, 1, duration) { FillBehavior = FillBehavior.Stop });
    }

    internal void RequestDismissal()
    {
        if (_disposed || IsClosing) return;
        if (!SystemParameters.ClientAreaAnimation || _photoEntranceRunning ||
            _photoImage is not { Source: { } imageSource, Parent: Grid root } image ||
            root.Background is not SolidColorBrush backdrop || _returnFrame?.Invoke() is not { } target ||
            target.IsEmpty || !new Rect(root.RenderSize).Contains(target))
        {
            _dismiss();
            return;
        }

        var start = image.TransformToAncestor(root).TransformBounds(new Rect(image.RenderSize));
        if (start.IsEmpty || start.Width <= 0 || start.Height <= 0)
        {
            _dismiss();
            return;
        }

        SetRefreshFailure(null);
        IsClosing = true;
        if (_mediaActions is { } menu) menu.IsOpen = false;
        _toolbarIdle.Stop();
        _toolbar.IsHitTestVisible = false;
        _albumStrip.IsHitTestVisible = false;
        _albumStrip.Opacity = 0;
        _captionPanel.IsHitTestVisible = false;
        _captionPanel.Opacity = 0;
        image.ReleaseMouseCapture();
        image.Visibility = Visibility.Hidden;
        RemovePhotoTransition();
        var tile = AddPhotoTransition(root, imageSource);
        var duration = new Duration(TimeSpan.FromMilliseconds(200));
        AnimatePhotoFrame(tile, start, target, duration, EasingMode.EaseInOut, () =>
        {
            if (!_disposed && IsClosing) _dismiss();
        });
        var backdropOpacity = backdrop.Opacity;
        backdrop.Opacity = 0;
        backdrop.BeginAnimation(Brush.OpacityProperty,
            new DoubleAnimation(backdropOpacity, 0, duration) { FillBehavior = FillBehavior.Stop });
        var toolbarOpacity = _toolbar.Opacity;
        _toolbar.Opacity = 0;
        _toolbar.BeginAnimation(OpacityProperty,
            new DoubleAnimation(toolbarOpacity, 0, duration) { FillBehavior = FillBehavior.Stop });
    }

    private Border AddPhotoTransition(Grid root, ImageSource image)
    {
        var layer = new Canvas { IsHitTestVisible = false, ClipToBounds = true };
        var tile = new Border { Background = new ImageBrush(image) { Stretch = Stretch.UniformToFill } };
        layer.Children.Add(tile);
        Panel.SetZIndex(layer, 1);
        root.Children.Add(layer);
        _photoTransition = layer;
        return tile;
    }

    private static void SetPhotoFrame(Border tile, Rect frame)
    {
        tile.Width = frame.Width;
        tile.Height = frame.Height;
        Canvas.SetLeft(tile, frame.X);
        Canvas.SetTop(tile, frame.Y);
    }

    private static void AnimatePhotoFrame(Border tile, Rect from, Rect to, Duration duration, EasingMode easing,
        Action completed)
    {
        DoubleAnimation Travel(double start, double end) => new(start, end, duration)
        {
            FillBehavior = FillBehavior.Stop,
            EasingFunction = new CubicEase { EasingMode = easing }
        };
        SetPhotoFrame(tile, to);
        var width = Travel(from.Width, to.Width);
        width.Completed += (_, _) => completed();
        tile.BeginAnimation(WidthProperty, width);
        tile.BeginAnimation(HeightProperty, Travel(from.Height, to.Height));
        tile.BeginAnimation(Canvas.LeftProperty, Travel(from.X, to.X));
        tile.BeginAnimation(Canvas.TopProperty, Travel(from.Y, to.Y));
    }

    private void RemovePhotoTransition()
    {
        var transition = _photoTransition;
        _photoTransition = null;
        _photoEntranceRunning = false;
        if (transition?.Parent is Panel parent) parent.Children.Remove(transition);
        transition?.Children.Clear();
    }

    private void RegisterPointerActivity()
    {
        _usingKeyboard = false;
        RevealToolbar();
    }

    private void RevealToolbar()
    {
        if (_disposed || IsClosing) return;
        SetToolbarVisible(true);
        _toolbarIdle.Stop();
        if (CanAutoHideToolbar) _toolbarIdle.Start();
    }

    private bool CanAutoHideToolbar => _refreshFailure.Visibility != Visibility.Visible && _encrypted?.ShowsPlaybackStatus != true &&
        (_photoReady || _fullScreenActive && _canHidePlaybackToolbar);

    private void ToolbarIdle_Tick(object? sender, EventArgs e)
    {
        if (!CanAutoHideToolbar)
        {
            _toolbarIdle.Stop();
            return;
        }
        if (_toolbar.IsMouseOver || _albumStrip.IsMouseOver || _captionPanel.IsMouseOver || _mediaActions?.IsOpen == true ||
            (_usingKeyboard && (_toolbar.IsKeyboardFocusWithin || _albumStrip.IsKeyboardFocusWithin || _captionPanel.IsKeyboardFocusWithin)) ||
            Mouse.LeftButton == MouseButtonState.Pressed || Mouse.RightButton == MouseButtonState.Pressed)
            return;
        _toolbarIdle.Stop();
        if (_toolbar.IsKeyboardFocusWithin || _albumStrip.IsKeyboardFocusWithin || _captionPanel.IsKeyboardFocusWithin) Focus();
        SetToolbarVisible(false);
    }

    private void SetToolbarVisible(bool visible)
    {
        if (_toolbarVisible == visible) return;
        _toolbarVisible = visible;
        foreach (var part in new FrameworkElement[] { _toolbar, _albumStrip, _captionPanel })
        {
            part.IsHitTestVisible = part.IsEnabled = visible;
            var from = part.Opacity;
            var target = visible ? 1d : 0d;
            part.Opacity = target;
            part.BeginAnimation(OpacityProperty, SystemParameters.ClientAreaAnimation
                ? new DoubleAnimation(from, target, TimeSpan.FromMilliseconds(visible ? 160 : 240))
                    { FillBehavior = FillBehavior.Stop }
                : null);
        }
    }

    internal void SetCaption(string caption)
    {
        var text = string.IsNullOrWhiteSpace(caption) ? "" : caption;
        if (_caption.MessageText != text)
        {
            _caption.MessageText = text;
            _captionScroll.ScrollToTop();
        }
        _captionPanel.Visibility = text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    internal void SetNavigation(PreviewNavigation? navigation)
    {
        _navigation = navigation;
        _navigationPanel.Visibility = navigation is null || navigation.Album is { Count: < 2 }
            ? Visibility.Collapsed : Visibility.Visible;
        _back.IsEnabled = navigation?.CanMoveBack == true;
        _forward.IsEnabled = navigation?.CanMoveForward == true;
        _position.Text = navigation?.Position ?? "";
        _position.Visibility = navigation?.Position is null ? Visibility.Collapsed : Visibility.Visible;
        var backLabel = AppStrings.Get(navigation?.Position is null ? "UiMediaNewer" : "UiMediaPreviousAttachment");
        var forwardLabel = AppStrings.Get(navigation?.Position is null ? "UiMediaOlder" : "UiMediaNextAttachment");
        _back.ToolTip = backLabel;
        _forward.ToolTip = forwardLabel;
        System.Windows.Automation.AutomationProperties.SetName(_back, backLabel);
        System.Windows.Automation.AutomationProperties.SetName(_forward, forwardLabel);
        PopulateAlbumStrip(navigation);
    }

    internal void RefreshAlbum(IReadOnlyList<MessageItem> members)
    {
        if (_navigation is not { Album: { } previous } navigation || previous.SequenceEqual(members)) return;
        var index = -1;
        for (var position = 0; position < members.Count; position++)
        {
            if (members[position].MessageId != MessageId) continue;
            index = position;
            break;
        }
        if (index < 0) return;
        SetNavigation(navigation with
        {
            Album = members,
            CanMoveBack = index > 0,
            CanMoveForward = index < members.Count - 1,
            Position = members.Count > 1 ? $"{index + 1} / {members.Count}" : null
        });
    }

    internal void SetFullScreenControl(Action toggle, bool active)
    {
        _toggleFullScreen = toggle;
        _fullScreenActive = active;
        if (_encrypted is { } renderer)
            renderer.TogglePreviewFullScreen = () => { if (!_disposed && !IsClosing) _toggleFullScreen?.Invoke(); };
        var label = AppStrings.Get(active ? "UiMediaExitFullScreen" : "UiMediaFullScreen");
        _fullScreenButton.Visibility = Visibility.Visible;
        _fullScreenButton.ToolTip = label;
        _fullScreenButton.Content = FindResource(active ? "MediaExitFullScreenIcon" : "MediaFullScreenIcon");
        System.Windows.Automation.AutomationProperties.SetName(_fullScreenButton, label);
        RevealToolbar();
    }

    private void PopulateAlbumStrip(PreviewNavigation? navigation)
    {
        _albumStrip.Content = null;
        _albumStrip.Visibility = Visibility.Collapsed;
        if (navigation?.Album is not { Count: > 1 } album || navigation.Select is not { } select) return;
        var tiles = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
        for (var index = 0; index < album.Count; index++)
        {
            var item = album[index];
            var current = item.MessageId == MessageId;
            var tile = new Grid { ClipToBounds = true };
            var picture = new Image { Stretch = Stretch.UniformToFill };
            var glyph = Label(item.IsVideo ? "▶" : "▧");
            tile.Children.Add(picture);
            tile.Children.Add(glyph);
            var label = AppStrings.Format("UiMediaAttachmentNumber", index + 1);
            var button = new Button
            {
                Width = 48, Height = 44, Margin = new Thickness(3, 4, 3, 4), Padding = new Thickness(0),
                Background = Brushes.DimGray, BorderBrush = current ? Brushes.DeepSkyBlue : Brushes.Transparent,
                BorderThickness = new Thickness(2), Content = tile, ToolTip = label,
                HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch
            };
            System.Windows.Automation.AutomationProperties.SetName(button, label);
            void UpdateStatus()
            {
                var status = item.AlbumDownloadStatus;
                button.ToolTip = status.Length == 0 ? label : $"{label}\n{status}";
                System.Windows.Automation.AutomationProperties.SetItemStatus(button,
                    status.Length > 0 ? status : current ? _position.Text : "");
                if (status.Length > 0)
                    glyph.Text = item.CanRetryDownload ? "↻" : item.DownloadActionKey switch
                    {
                        "UiPauseDownload" => "◷",
                        "UiResumeDownload" => "Ⅱ",
                        _ => "↓"
                    };
            }
            void OnTransferChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs args)
            {
                if (args.PropertyName == nameof(MessageItem.AlbumDownloadStatus)) UpdateStatus();
            }
            button.Loaded += (_, _) =>
            {
                item.PropertyChanged += OnTransferChanged;
                UpdateStatus();
            };
            button.Unloaded += (_, _) => item.PropertyChanged -= OnTransferChanged;
            button.Click += (_, _) => { if (!_disposed && !IsClosing && !current) select(item.MessageId); };
            tiles.Children.Add(button);
            if (item.IsAvailable && item.AttachmentInfo is not null)
                button.Loaded += (_, _) => _ = LoadAlbumThumbnailAsync(item, picture, glyph);
            if (current) button.Loaded += (_, _) => button.BringIntoView();
        }
        _albumStrip.HorizontalScrollBarVisibility = ScrollBarVisibility.Auto;
        _albumStrip.VerticalScrollBarVisibility = ScrollBarVisibility.Disabled;
        _albumStrip.Background = new SolidColorBrush(Color.FromArgb(192, 0, 0, 0));
        _albumStrip.Content = tiles;
        _albumStrip.Visibility = Visibility.Visible;
    }

    private async Task LoadAlbumThumbnailAsync(MessageItem item, Image picture, TextBlock glyph)
    {
        if (_disposed) return;
        var token = _thumbnailsLifetime.Token;
        try
        {
            var bitmap = await AttachmentThumbnail.LoadAsync(item, 128, token);
            if (token.IsCancellationRequested || !picture.IsLoaded || bitmap is null) return;
            picture.Source = bitmap;
            if (!item.IsVideo) glyph.Visibility = Visibility.Collapsed;
        }
        catch (Exception error) when (error is OperationCanceledException or IOException or InvalidOperationException or ArgumentException or
            NotSupportedException or System.Security.Cryptography.CryptographicException) { }
    }

    private Button? CreateMediaActions(Action? showMessage, Action? openSharedMedia, Action? reply,
        Action? forward, Func<Task>? save, Func<bool>? canSave)
    {
        var menu = new ContextMenu { Placement = PlacementMode.Bottom };
        var exportItems = new List<MenuItem>();
        void AddAction(string resource, Action? action, bool needsExport = false)
        {
            if (action is null) return;
            var row = new MenuItem();
            row.SetResourceReference(HeaderedItemsControl.HeaderProperty, resource);
            row.Click += (_, _) => { if (!_disposed && !IsClosing) action(); };
            menu.Items.Add(row);
            if (needsExport) exportItems.Add(row);
        }

        AddAction("UiShowInChat", showMessage);
        AddAction("UiSharedMedia", openSharedMedia);
        AddAction("UiReply", reply);
        AddAction("UiForwardMessage", forward, needsExport: true);
        if (save is not null)
        {
            var saving = false;
            AddAction("UiSaveAttachment", async () =>
            {
                if (saving) return;
                saving = true;
                try { await save(); }
                finally { saving = false; }
            }, needsExport: true);
        }
        if (menu.Items.Count == 0) return null;

        if (canSave is not null && exportItems.Count != 0)
        {
            var generation = 0;
            menu.Opened += async (_, _) =>
            {
                var expected = ++generation;
                foreach (var row in exportItems) row.IsEnabled = false;
                try
                {
                    var allowed = await Task.Run(canSave);
                    if (_disposed || IsClosing || !menu.IsOpen || generation != expected) return;
                    foreach (var row in exportItems) row.IsEnabled = allowed;
                }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException or
                    Microsoft.Data.Sqlite.SqliteException or System.Security.Cryptography.CryptographicException) { }
            };
        }
        var button = ToolbarButton("MoreIcon", AppStrings.Get("UiMediaActions"), () => menu.IsOpen = true);
        button.SetResourceReference(ToolTipProperty, "UiMediaActions");
        button.SetResourceReference(System.Windows.Automation.AutomationProperties.NameProperty, "UiMediaActions");
        menu.PlacementTarget = button;
        button.ContextMenu = menu;
        _mediaActions = menu;
        return button;
    }

    private static Button ToolbarButton(string icon, string label, Action click, bool enabled = true)
    {
        var button = new Button
        {
            Width = 36, Height = 36, Margin = new Thickness(2, 0, 2, 0),
            ToolTip = label, IsEnabled = enabled, Foreground = Brushes.White,
            Background = PreviewSurfaceBrush
        };
        button.SetResourceReference(StyleProperty, "IconButtonStyle");
        button.SetResourceReference(ContentControl.ContentProperty, icon);
        System.Windows.Automation.AutomationProperties.SetName(button, label);
        button.Click += (_, _) => click();
        return button;
    }

    private async Task LoadImageAsync(MessageItem message, Grid root, int albumStep = 0)
    {
        try
        {
            var bitmap = await Task.Run(() => { using var input=message.OpenMedia(); return ImageDecoder.Load(input); });
            if (_disposed || IsClosing || !IsLoaded) return;
            var image = new Image { Source = bitmap, Stretch = Stretch.Uniform,
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            var scale = new ScaleTransform(1, 1); var offset = new TranslateTransform();
            var transform = new TransformGroup(); transform.Children.Add(scale); transform.Children.Add(offset);
            image.RenderTransform = transform; image.RenderTransformOrigin = new Point(0.5, 0.5); root.Children.Add(image);
            if (albumStep != 0 && SystemParameters.ClientAreaAnimation)
            {
                var entry = new TranslateTransform();
                transform.Children.Add(entry);
                var duration = new Duration(TimeSpan.FromMilliseconds(180));
                entry.BeginAnimation(TranslateTransform.XProperty,
                    new DoubleAnimation(albumStep * 22d, 0, duration) { FillBehavior = FillBehavior.Stop });
                image.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, duration) { FillBehavior = FillBehavior.Stop });
            }
            _photoImage = image;
            _photoReady = true;
            RevealToolbar();
            if (!_photoEntranceRunning) RemovePhotoTransition();
            Point? drag = null;
            Point origin = default;

            void Move(double x, double y)
            {
                var maxX = Math.Max(0, (image.Width * scale.ScaleX - root.ActualWidth) / 2);
                var maxY = Math.Max(0, (image.Height * scale.ScaleY - root.ActualHeight) / 2);
                offset.X = Math.Clamp(x, -maxX, maxX);
                offset.Y = Math.Clamp(y, -maxY, maxY);
            }

            void Fit()
            {
                var ratio = Math.Min(root.ActualWidth / bitmap.PixelWidth, root.ActualHeight / bitmap.PixelHeight);
                image.Width = bitmap.PixelWidth * ratio;
                image.Height = bitmap.PixelHeight * ratio;
                Move(offset.X, offset.Y);
            }

            void Zoom(double factor, Point? anchor)
            {
                if (factor != 1) RemovePhotoTransition();
                var oldScale = scale.ScaleX;
                scale.ScaleX = scale.ScaleY = Math.Clamp(oldScale * factor, 1, 5);
                var relative = scale.ScaleX / oldScale;
                var x = anchor is { } point ? point.X - root.ActualWidth / 2 : 0;
                var y = anchor is { } position ? position.Y - root.ActualHeight / 2 : 0;
                Move(x + (offset.X - x) * relative, y + (offset.Y - y) * relative);
                if (_zoomOut is not null) _zoomOut.IsEnabled = scale.ScaleX > 1;
                if (_fit is not null) _fit.IsEnabled = scale.ScaleX > 1;
                if (_zoomIn is not null) _zoomIn.IsEnabled = scale.ScaleX < 5;
            }

            Fit();
            root.SizeChanged += (_, _) => Fit();
            _zoom = Zoom;
            Zoom(1, null);
            image.PreviewMouseWheel += (_, e) => { Zoom(Math.Pow(1.15, e.Delta / 120d), e.GetPosition(root)); e.Handled = true; };
            image.MouseLeftButtonDown += (_, e) =>
            {
                if (e.ClickCount == 2)
                {
                    Zoom(scale.ScaleX > 1 ? 0 : 2, e.GetPosition(root));
                    e.Handled = true;
                    return;
                }
                if (scale.ScaleX <= 1 || !image.CaptureMouse()) return;
                drag = e.GetPosition(root);
                origin = new Point(offset.X, offset.Y);
                e.Handled = true;
            };
            image.MouseMove += (_, e) =>
            {
                if (drag is not { } start) return;
                var delta = e.GetPosition(root) - start;
                Move(origin.X + delta.X, origin.Y + delta.Y);
            };
            image.MouseLeftButtonUp += (_, e) =>
            {
                if (drag is null) return;
                image.ReleaseMouseCapture();
                e.Handled = true;
            };
            image.LostMouseCapture += (_, _) => drag = null;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or NotSupportedException or
            InvalidOperationException or ArgumentException or System.Security.Cryptography.CryptographicException)
        {
            RemovePhotoTransition();
            if (_disposed || IsClosing || !IsLoaded) return;
            var errorPanel = new StackPanel();
            var status = Label(AppStrings.Get("UiMediaPlaybackUnavailable"));
            status.TextWrapping = TextWrapping.Wrap;
            status.TextAlignment = TextAlignment.Center;
            errorPanel.Children.Add(status);
            var feedback = PreviewFeedback(errorPanel);
            var retry = new Button
            {
                Content = AppStrings.Get("UiRetry"), HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 8, 0, 0)
            };
            retry.Click += async (_, _) =>
            {
                if (_disposed || IsClosing || !root.Children.Contains(feedback)) return;
                root.Children.Remove(feedback);
                var loading = PreviewLoading();
                root.Children.Add(loading);
                try { await LoadImageAsync(message, root); }
                finally { root.Children.Remove(loading); }
            };
            errorPanel.Children.Add(retry);
            root.Children.Add(feedback);
        }
    }
    private static Brush CreatePreviewSurfaceBrush()
    {
        var brush = new SolidColorBrush(Color.FromRgb(34, 43, 39));
        brush.Freeze();
        return brush;
    }

    internal static Border PreviewFeedback(UIElement content) => new()
    {
        Child = content, Background = PreviewSurfaceBrush, CornerRadius = new CornerRadius(8),
        MaxWidth = 360, Padding = new Thickness(14, 10, 14, 10), Margin = new Thickness(12),
        HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center
    };

    internal static Border PreviewLoading()
    {
        var status = Label(AppStrings.Get("UiLoading"));
        System.Windows.Automation.AutomationProperties.SetLiveSetting(status, System.Windows.Automation.AutomationLiveSetting.Polite);
        var progress = new System.Windows.Controls.ProgressBar
        {
            Height = 4, MinWidth = 96, Margin = new Thickness(0, 8, 0, 0), IsIndeterminate = true
        };
        return PreviewFeedback(new StackPanel { Children = { status, progress } });
    }
    private static TextBlock Label(string text) => new() { Text = text, Foreground = Brushes.White, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    public void Dispose()
    {
        if (!_disposed)
        {
            _disposed = true;
            if (_mediaActions is { } menu) menu.IsOpen = false;
            _mediaActions = null;
            _toggleFullScreen = null;
            _retryMediaRefresh = null;
            if (_pendingDownload is not null) _pendingDownload.PropertyChanged -= PendingDownloadChanged;
            _pendingDownload = null;
            _thumbnailsLifetime.Cancel();
            _thumbnailsLifetime.Dispose();
            _albumStrip.Content = null;
            _caption.MessageText = "";
            _captionScroll.Content = null;
            _zoom = null;
            _openingPhoto = null;
            _photoImage = null;
            _returnFrame = null;
            RemovePhotoTransition();
            _toolbarIdle.Stop();
            _toolbarIdle.Tick -= ToolbarIdle_Tick;
            _toolbar.BeginAnimation(OpacityProperty, null);
            _albumStrip.BeginAnimation(OpacityProperty, null);
            _captionPanel.BeginAnimation(OpacityProperty, null);
            _encrypted?.Dispose();
        }
        GC.SuppressFinalize(this);
    }
}
