using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Data;
using MsgAT.Windows.Media;
using MsgAT.Windows.Views;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Infrastructure;

public sealed class AttachmentPreview : Border
{
    public static readonly DependencyProperty MessageProperty = DependencyProperty.Register(
        nameof(Message), typeof(MessageItem), typeof(AttachmentPreview),
        new PropertyMetadata(null, (owner, _) => ((AttachmentPreview)owner).Refresh()));
    public static readonly DependencyProperty CompactProperty = DependencyProperty.Register(
        nameof(Compact), typeof(bool), typeof(AttachmentPreview),
        new PropertyMetadata(false, (owner, _) => ((AttachmentPreview)owner).Refresh()));
    public static readonly DependencyProperty GalleryTileProperty = DependencyProperty.Register(
        nameof(GalleryTile), typeof(bool), typeof(AttachmentPreview),
        new PropertyMetadata(false, (owner, _) => ((AttachmentPreview)owner).Refresh()));
    public static readonly DependencyProperty EnableUploadRetryProperty = DependencyProperty.Register(
        nameof(EnableUploadRetry), typeof(bool), typeof(AttachmentPreview),
        new PropertyMetadata(false, (owner, _) => ((AttachmentPreview)owner).Refresh()));
    public static readonly RoutedEvent RetryUploadRequestedEvent = EventManager.RegisterRoutedEvent(
        nameof(RetryUploadRequested), RoutingStrategy.Bubble, typeof(RoutedEventHandler), typeof(AttachmentPreview));
    public static readonly RoutedEvent FileActionRequestedEvent = EventManager.RegisterRoutedEvent(
        nameof(FileActionRequested), RoutingStrategy.Bubble, typeof(RoutedEventHandler), typeof(AttachmentPreview));

    public bool EnableUploadRetry
    {
        get => (bool)GetValue(EnableUploadRetryProperty);
        set => SetValue(EnableUploadRetryProperty, value);
    }

    public event RoutedEventHandler RetryUploadRequested
    {
        add => AddHandler(RetryUploadRequestedEvent, value);
        remove => RemoveHandler(RetryUploadRequestedEvent, value);
    }

    public event RoutedEventHandler FileActionRequested
    {
        add => AddHandler(FileActionRequestedEvent, value);
        remove => RemoveHandler(FileActionRequestedEvent, value);
    }

    public bool Compact
    {
        get => (bool)GetValue(CompactProperty);
        set => SetValue(CompactProperty, value);
    }

    public bool GalleryTile
    {
        get => (bool)GetValue(GalleryTileProperty);
        set => SetValue(GalleryTileProperty, value);
    }

    private int _generation;
    private CancellationTokenSource? _thumbnailWork;
    private EncryptedMediaView? _inlineAudio;
    private Panel? _inlineAudioControls;
    private MessageItem? _inlineAudioMessage;

    public AttachmentPreview()
    {
        Background = Brushes.Transparent;
        Loaded += (_, _) => Refresh();
        Unloaded += (_, _) => { _generation++; _thumbnailWork?.Cancel(); DisposeInlineAudio(); Child = null; };
    }

    public MessageItem? Message
    {
        get => (MessageItem?)GetValue(MessageProperty);
        set => SetValue(MessageProperty, value);
    }

    protected override void OnRenderSizeChanged(SizeChangedInfo sizeInfo)
    {
        base.OnRenderSizeChanged(sizeInfo);
        var radius = GalleryTile ? 4 : 12;
        Clip = new RectangleGeometry(new Rect(RenderSize), radius, radius);
    }

    private async void Refresh()
    {
        var generation = ++_generation;
        _thumbnailWork?.Cancel();
        if (_inlineAudio is not null && _inlineAudioMessage is { } playing &&
            Message is { IsAvailable: true, IsAudio: true, IsRevoked: false } current &&
            current.MessageId == playing.MessageId && current.Source.ConversationUuid == playing.Source.ConversationUuid &&
            current.Content == playing.Content && Child is StackPanel audioCard &&
            audioCard.Children.Count == 2)
        {
            if (!ReferenceEquals(playing, current))
            {
                _inlineAudioMessage = current;
                audioCard.Children.RemoveAt(1);
                audioCard.Children.Add(CreateTransferControls(current));
            }
            return;
        }
        DisposeInlineAudio();
        Child = null;
        ToolTip = null;
        System.Windows.Automation.AutomationProperties.SetHelpText(this, "");
        if (!IsLoaded || Message is not { IsAttachment: true } message) { return; }
        if(message.IsSticker && message.IsAvailable){Child=new StickerImageView(message.ReadStickerBytes);return;}
        if (!Compact && !GalleryTile && message.IsFileCard)
        {
            Child = CreateFileCard(message);
            return;
        }
        var isImage = message.IsImage;
        var visual = isImage || message.IsVideo;
        var pendingAlbum = Compact && !GalleryTile && visual && !message.IsAvailable &&
            (message.CanRequestDownload || message.CanPauseDownload);
        var action = pendingAlbum ? AppStrings.Get("UiOpenMediaPreview") : null;
        ToolTip = action;
        System.Windows.Automation.AutomationProperties.SetHelpText(this, action ?? "");
        var placeholder = new Grid
        {
            Width = GalleryTile || Compact ? double.NaN : 250,
            Height = GalleryTile ? double.NaN : Compact ? 106 : visual ? 180 : 84,
            Background = visual ? Brushes.Black : new SolidColorBrush(Color.FromRgb(24, 34, 44))
        };
        var details=new StackPanel { Margin=new Thickness(12),VerticalAlignment=VerticalAlignment.Bottom };
        if(!visual) details.Children.Add(new TextBlock { Text=message.Filename,Foreground=Brushes.White,TextTrimming=TextTrimming.CharacterEllipsis,MaxWidth=226 });
        details.Children.Add(CreateTransferControls(message, pendingAlbum));
        placeholder.Children.Add(details);
        if (message.IsAvailable && (message.IsVideo || message.IsAudio))
        {
            var play = new Border { Width = 48, Height = 48, CornerRadius = new CornerRadius(24), Background = new SolidColorBrush(Color.FromArgb(128, 0, 0, 0)), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            play.Child = new TextBlock { Text = "▶", FontSize = 32, Foreground = Brushes.White, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            placeholder.Children.Add(play);
        }
        Child = placeholder;
        if (!message.IsAvailable || !visual) return;
        using var work = new CancellationTokenSource();
        _thumbnailWork = work;
        try
        {
            if (message.IsVideo || GalleryTile) await WaitForViewportAsync(work.Token);
            var bitmap = await AttachmentThumbnail.LoadAsync(message, GalleryTile ? 320 : 560, work.Token);
            if (bitmap is null || work.IsCancellationRequested || generation != _generation || !IsLoaded) return;
            placeholder.Children.Insert(0, new Image { Source = bitmap, Stretch = Stretch.UniformToFill, IsHitTestVisible = false });
        }
        catch (OperationCanceledException) when (work.IsCancellationRequested) { }
        catch (Exception error) when (error is IOException or NotSupportedException or InvalidOperationException or ArgumentException)
        {
            // The playback action and transfer status stay available when thumbnail decoding fails.
        }
        finally
        {
            if (ReferenceEquals(_thumbnailWork, work)) _thumbnailWork = null;
        }
    }

    internal bool OpenInlineAudio(MessageItem message, double speed, Action<double> rememberSpeed)
    {
        if (!IsLoaded || !ReferenceEquals(message, Message) || !message.IsAvailable || !message.IsAudio) return false;
        _generation++;
        _thumbnailWork?.Cancel();
        DisposeInlineAudio();
        var playback = new EncryptedMediaView(message, CloseInlineAudio, inlineAudio: true)
        {
            Width = 250, Height = 64
        };
        playback.SetPlaybackSpeed(speed);
        playback.PlaybackSpeedChanged += rememberSpeed;
        System.Windows.Automation.AutomationProperties.SetName(playback, message.Filename);
        var controls = new StackPanel();
        controls.Children.Add(playback);
        var speedButton = playback.CreatePlaybackSpeedButton();
        speedButton.HorizontalAlignment = HorizontalAlignment.Right;
        controls.Children.Add(speedButton);
        var content = new StackPanel();
        content.Children.Add(controls);
        content.Children.Add(CreateTransferControls(message));
        _inlineAudio = playback;
        _inlineAudioControls = controls;
        _inlineAudioMessage = message;
        Child = content;
        return true;
    }

    private FrameworkElement CreateFileCard(MessageItem message)
    {
        var icon = new TextBlock { Text = "\uE8A5", FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 24, Margin = new Thickness(0, 3, 10, 0) };
        icon.SetResourceReference(TextBlock.ForegroundProperty, "AccentTextBrush");
        DockPanel.SetDock(icon, Dock.Left);

        var name = new TextBlock { Text = message.Filename, FontWeight = FontWeights.SemiBold,
            TextTrimming = TextTrimming.CharacterEllipsis, ToolTip = message.Filename };
        var size = new TextBlock { Text = message.FileSizeText, FontSize = 11,
            Visibility = message.FileSizeText.Length > 0 ? Visibility.Visible : Visibility.Collapsed };
        size.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        var action = new Button { Margin = new Thickness(0, 6, 0, 0), HorizontalAlignment = HorizontalAlignment.Left,
            Cursor = Cursors.Hand };
        action.SetBinding(ContentControl.ContentProperty, new Binding(nameof(MessageItem.FileActionText)) { Source = message });
        action.SetBinding(VisibilityProperty, new Binding(nameof(MessageItem.HasFileAction))
        {
            Source = message, Converter = new BooleanToVisibilityConverter()
        });
        action.Click += (_, args) =>
        {
            args.Handled = true;
            if (ReferenceEquals(Message, message) && message.HasFileAction)
                RaiseEvent(new RoutedEventArgs(FileActionRequestedEvent));
        };
        var details = new StackPanel { Children = { name, size, CreateTransferControls(message, file: true), action } };
        var content = new DockPanel { Margin = new Thickness(12), Children = { icon, details } };
        var card = new Border { Width = 250, MinHeight = 72, CornerRadius = new CornerRadius(10), Child = content,
            Cursor = Cursors.Arrow };
        card.SetResourceReference(BackgroundProperty, "SurfaceBrush");
        return card;
    }

    private StackPanel CreateTransferControls(MessageItem message, bool pendingAlbum = false, bool file = false)
    {
        var panel = new StackPanel();
        var status = new TextBlock { FontSize = 11, Foreground = Brushes.LightGray };
        if (file) status.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        var statusProperty = file ? nameof(MessageItem.FileTransferText)
            : pendingAlbum ? nameof(MessageItem.AlbumDownloadStatus) : nameof(MessageItem.TransferText);
        status.SetBinding(TextBlock.TextProperty, new Binding(statusProperty) { Source = message });
        var statusStyle = new Style(typeof(TextBlock));
        var empty = new DataTrigger { Binding = new Binding(statusProperty) { Source = message }, Value = "" };
        empty.Setters.Add(new Setter(VisibilityProperty, Visibility.Collapsed));
        statusStyle.Triggers.Add(empty);
        panel.Children.Add(status);
        if (!Compact || GalleryTile)
        {
            var progress = new System.Windows.Controls.ProgressBar
            {
                Minimum = 0, Maximum = 100, Height = GalleryTile ? 3 : 4, Margin = new Thickness(0, 4, 0, 0),
                IsHitTestVisible = false
            };
            progress.SetBinding(System.Windows.Controls.ProgressBar.ValueProperty, new Binding(nameof(MessageItem.TransferPercent))
            {
                Source = message, Mode = BindingMode.OneWay
            });
            progress.SetBinding(VisibilityProperty, new Binding(nameof(MessageItem.HasTransferProgress))
            {
                Source = message, Converter = new BooleanToVisibilityConverter()
            });
            progress.SetBinding(System.Windows.Automation.AutomationProperties.NameProperty,
                new Binding(statusProperty) { Source = message });
            panel.Children.Add(progress);
        }
        if (EnableUploadRetry)
        {
            var canRetry = new Binding(nameof(MessageItem.CanRetryUpload)) { Source = message };
            var retryVisible = new DataTrigger { Binding = canRetry, Value = true };
            retryVisible.Setters.Add(new Setter(VisibilityProperty, Visibility.Collapsed));
            statusStyle.Triggers.Add(retryVisible);
            var retry = new Button { Padding = new Thickness(8, 4, 8, 4), HorizontalAlignment = HorizontalAlignment.Left, FontSize = 11 };
            retry.SetResourceReference(ContentControl.ContentProperty, "UiSendRetry");
            retry.SetBinding(VisibilityProperty, new Binding(nameof(MessageItem.CanRetryUpload))
            {
                Source = message, Converter = new BooleanToVisibilityConverter()
            });
            var retryStyle = new Style(typeof(Button), TryFindResource(typeof(Button)) as Style);
            var busy = new DataTrigger { Binding = new Binding(nameof(MessageItem.IsRetryingSend)) { Source = message }, Value = true };
            busy.Setters.Add(new Setter(IsEnabledProperty, false));
            retryStyle.Triggers.Add(busy);
            retry.Style = retryStyle;
            retry.Click += (_, args) =>
            {
                args.Handled = true;
                RaiseEvent(new RoutedEventArgs(RetryUploadRequestedEvent));
            };
            panel.Children.Add(retry);
        }
        status.Style = statusStyle;
        return panel;
    }

    internal void CloseInlineAudio()
    {
        if (_inlineAudio is null) return;
        DisposeInlineAudio();
        Refresh();
    }

    internal bool ContainsInlineAudioInput(object? source) => _inlineAudioControls is { } controls && source is Visual visual &&
        (ReferenceEquals(visual, controls) || visual.IsDescendantOf(controls));

    private void DisposeInlineAudio()
    {
        var playback = _inlineAudio;
        _inlineAudio = null;
        _inlineAudioControls = null;
        _inlineAudioMessage = null;
        playback?.Dispose();
    }

    internal static AttachmentPreview? FindIn(DependencyObject parent, string messageId, bool albumOnly = false)
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
        {
            var child = VisualTreeHelper.GetChild(parent, index);
            if (child is AttachmentPreview { Message: { } item } tile &&
                (!albumOnly || tile.Compact) && item.MessageId == messageId)
                return tile;
            if (FindIn(child, messageId, albumOnly) is { } nested) return nested;
        }
        return null;
    }

    internal (BitmapSource Thumbnail, Rect Bounds)? CapturePhotoThumbnail(Visual coordinateSpace)
    {
        if (!IsLoaded || !IsVisible || RenderSize.Width <= 0 || RenderSize.Height <= 0 ||
            Message is not { IsImage: true, IsAvailable: true, IsRevoked: false, IsSticker: false } ||
            Child is not Grid placeholder || placeholder.Children.Count == 0 ||
            placeholder.Children[0] is not Image { Source: BitmapSource bitmap }) return null;

        var presentation = PresentationSource.FromVisual(this);
        if (presentation is null || presentation != PresentationSource.FromVisual(coordinateSpace)) return null;
        var bounds = new Rect(RenderSize);
        if (FindViewport() is { } viewport &&
            !new Rect(viewport.RenderSize).Contains(TransformToAncestor(viewport).TransformBounds(bounds)))
            return null;
        return (bitmap, TransformToVisual(coordinateSpace).TransformBounds(bounds));
    }

    private ScrollViewer? FindViewport()
    {
        for (var ancestor = VisualTreeHelper.GetParent(this); ancestor is not null; ancestor = VisualTreeHelper.GetParent(ancestor))
        {
            if (ancestor is ScrollViewer scroll) return scroll;
        }
        return null;
    }

    private async Task WaitForViewportAsync(CancellationToken cancellation)
    {
        var viewport = FindViewport();
        if (viewport is null) return;

        bool InView()
        {
            if (!IsVisible || RenderSize.Width <= 0 || RenderSize.Height <= 0) return false;
            var bounds = new Rect(0, 0, viewport.ActualWidth, viewport.ActualHeight);
            return TransformToAncestor(viewport).TransformBounds(new Rect(RenderSize)).IntersectsWith(bounds);
        }
        if (InView()) return;
        var ready = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        void Check()
        {
            if (!cancellation.IsCancellationRequested && InView()) ready.TrySetResult(true);
        }
        void Scrolled(object sender, ScrollChangedEventArgs args) => Check();
        void LaidOut(object? sender, EventArgs args) => Check();
        viewport.ScrollChanged += Scrolled;
        LayoutUpdated += LaidOut;
        try { await ready.Task.WaitAsync(cancellation); }
        finally
        {
            viewport.ScrollChanged -= Scrolled;
            LayoutUpdated -= LaidOut;
        }
    }
}
