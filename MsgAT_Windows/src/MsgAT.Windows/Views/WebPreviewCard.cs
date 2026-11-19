// SPDX-License-Identifier: AGPL-3.0-only
using System.Security.Cryptography;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Views;

public sealed class WebPreviewCard:Border
{
    public static readonly DependencyProperty MessageIdProperty=DependencyProperty.Register(nameof(MessageId),typeof(string),typeof(WebPreviewCard),new PropertyMetadata("",Changed));
    public static readonly DependencyProperty MessageTextProperty=DependencyProperty.Register(nameof(MessageText),typeof(string),typeof(WebPreviewCard),new PropertyMetadata("",Changed));
    public static readonly DependencyProperty ServicesProperty=DependencyProperty.Register(nameof(Services),typeof(AppServices),typeof(WebPreviewCard),new PropertyMetadata(null,Changed));
    public static readonly DependencyProperty IsSavedProperty=DependencyProperty.Register(nameof(IsSaved),typeof(bool),typeof(WebPreviewCard),new PropertyMetadata(false,Changed));
    public static readonly DependencyProperty DownloadPeerProperty = DependencyProperty.Register(nameof(DownloadPeer),
        typeof(string), typeof(WebPreviewCard), new PropertyMetadata(null, Changed));
    public string MessageId {get=>(string)GetValue(MessageIdProperty);set=>SetValue(MessageIdProperty,value);}
    public string MessageText {get=>(string)GetValue(MessageTextProperty);set=>SetValue(MessageTextProperty,value);}
    public AppServices? Services {get=>(AppServices?)GetValue(ServicesProperty);set=>SetValue(ServicesProperty,value);}
    public bool IsSaved {get=>(bool)GetValue(IsSavedProperty);set=>SetValue(IsSavedProperty,value);}
    public string? DownloadPeer { get => (string?)GetValue(DownloadPeerProperty); set => SetValue(DownloadPeerProperty, value); }
    private AppServices? _watching;
    private Uri? _target;
    private string? _imageReference;
    private Button? _downloadButton;
    private TextBlock? _downloadStatus;
    private AttachmentMetadata? _pendingCover;
    private AttachmentProgress? _coverTransfer;
    private bool _coverReadFailed;
    private bool _canPauseCover;
    private bool _requesting;
    private long _generation;
    public WebPreviewCard()
    {
        MaxWidth = 280;
        HorizontalAlignment = HorizontalAlignment.Left;
        WebPreviewContent.StyleCard(this);
        Focusable = true;
        Cursor = Cursors.Hand;
        Visibility = Visibility.Collapsed;
        Loaded+=(_,_)=>Reload();Unloaded+=(_,_)=>{++_generation;_coverTransfer=null;Watch(null);ClearPreview();};
        MouseLeftButtonUp+=(_,args)=>
        {
            if(Keyboard.Modifiers!=ModifierKeys.None || _downloadButton?.IsMouseOver == true)return;
            if (_target is { } uri) args.Handled = MessageLinks.Open(uri, this);
        };
        KeyDown += (_, args) =>
        {
            if (IsKeyboardFocused && (args.Key is Key.Enter or Key.Space) && _target is { } uri &&
                Keyboard.Modifiers == ModifierKeys.None)
                args.Handled = MessageLinks.Open(uri, this);
        };
    }
    private static void Changed(DependencyObject value, DependencyPropertyChangedEventArgs args)
    {
        var card = (WebPreviewCard)value;
        ++card._generation;
        card._coverTransfer = null;
        card.Watch(null);
        card.ClearPreview();
        card.Reload();
    }
    private void ClearPreview()
    {
        _target = null;
        _imageReference = null;
        _downloadButton = null;
        _downloadStatus = null;
        _pendingCover = null;
        _coverReadFailed = false;
        _canPauseCover = false;
        Child = null;
        ContextMenu = null;
        ToolTip = null;
        AutomationProperties.SetName(this, "");
        Visibility = Visibility.Collapsed;
    }
    private void Available(object? sender, EventArgs args)
    {
        if (args is MessageDeliveryChangedEventArgs or VisibleMessagesReadEventArgs) return;
        Dispatcher.BeginInvoke(() =>
        {
            if (args is AttachmentCacheChangedEventArgs change)
            {
                if (!IsSaved && (change.MessageId is null || change.MessageId == MessageId)) Reload();
            }
            else if (_downloadButton is not null) Reload();
        });
    }
    private void Watch(AppServices? services)
    {
        if (_watching == services) return;
        if (_watching is { } old)
        {
            old.Database.Changed -= Available;
            old.Messaging.AttachmentProgressChanged -= CoverProgressChanged;
        }
        _watching = services;
        if (services is null) return;
        services.Database.Changed += Available;
        services.Messaging.AttachmentProgressChanged += CoverProgressChanged;
    }

    private void CoverProgressChanged(object? sender, AttachmentProgress update)
    {
        if (update.PreviewReference is null || Dispatcher.HasShutdownStarted) return;
        var generation = Volatile.Read(ref _generation);
        Dispatcher.BeginInvoke(() =>
        {
            if (!IsLoaded || generation != _generation || MessageId != update.MessageId ||
                _imageReference != update.PreviewReference) return;
            _coverTransfer = update;
            if (update.Phase is AttachmentPhase.Downloading or AttachmentPhase.Retry) UpdateCoverControls();
            else Reload();
        });
    }

    private void UpdateCoverControls()
    {
        if (_downloadButton is not { } button || _downloadStatus is not { } status) return;
        var transfer = _coverTransfer is { } current && current.MessageId == MessageId && current.PreviewReference == _imageReference
            ? current : null;
        var paused = _pendingCover?.DownloadIntent == AttachmentDownloadIntent.Paused;
        var retry = !paused && (_coverReadFailed || _pendingCover?.RemoteUnavailable == true || transfer?.Phase == AttachmentPhase.Retry);
        var queued = !paused && !retry && _pendingCover?.DownloadIntent == AttachmentDownloadIntent.Requested;
        var downloading = !paused && !retry && transfer?.Phase == AttachmentPhase.Downloading ? transfer : null;
        _canPauseCover = queued || downloading is not null;
        status.Text = downloading is not null
            ? AppStrings.Format("UiAlbumDownloadProgress", Math.Clamp(100 * downloading.Bytes / Math.Max(1, downloading.Total), 0, 100))
            : queued ? AppStrings.Get("UiDownloadQueued") : "";
        status.Visibility = status.Text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        button.Content = AppStrings.Get(_canPauseCover ? "UiPauseDownload" : paused ? "UiResumeDownload" : retry ? "UiRetry" : "UiPreviewDownload");
        button.IsEnabled = !_requesting;
    }

    private async Task SetCoverDownloadAsync(AppServices services, string peer, string id, string reference)
    {
        if (_requesting || !IsLoaded || !IsVisible || IsSaved || Services != services ||
            MessageId != id || DownloadPeer != peer || _imageReference != reference) return;
        _requesting = true;
        var requested = !_canPauseCover;
        var replacingCache = _coverReadFailed;
        UpdateCoverControls();
        try
        {
            var changed = await services.Messaging.SetPreviewDownloadRequestedAsync(peer, id, reference,
                requested, replacingCache: replacingCache);
            if (!changed) ShowDownloadFailure(services, id, reference);
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            ShowDownloadFailure(services, id, reference);
        }
        finally
        {
            _requesting = false;
            UpdateCoverControls();
        }
    }

    private void ShowDownloadFailure(AppServices services, string id, string reference)
    {
        if (IsLoaded && IsVisible && Services == services && MessageId == id && _imageReference == reference &&
            Window.GetWindow(this) is { } owner)
            TransientNotice.Show(owner, AppStrings.Get("UiPreviewDownloadUnavailable"));
    }

    private async void Reload()
    {
        if(!IsLoaded || Services is not { } services || string.IsNullOrEmpty(MessageId))return;
        var generation=++_generation;var id=MessageId;var saved=IsSaved;
        Watch(services); // Subscribe before reading availability, so completion cannot be missed.
        try
        {
            var result=await Task.Run(()=>
            {
                var row=services.Previews.Read(id,saved);ImageSource? image=null;AttachmentMetadata? pendingCover = null;
                var availableCover = false;
                if(row?.ImageReference is { } reference)
                {
                    try
                    {
                        var metadata=services.Objects.Metadata(reference,id);
                        if (metadata is { Mime: "image/jpeg", Size: > 0 and <= 512 * 1024 })
                        {
                            if (!metadata.Available && metadata.HasPendingDownload) pendingCover = metadata;
                            if (metadata.Available)
                            {
                                availableCover = true;
                                using var input = services.Objects.OpenRead(reference);
                                image = ImageDecoder.Load(input, 512);
                            }
                        }
                    }
                    catch(Exception error) when(error is IOException or InvalidOperationException or NotSupportedException or FormatException or System.Runtime.InteropServices.ExternalException or System.Security.Cryptography.CryptographicException){ }
                }
                return (row,image,pendingCover,availableCover);
            });
            if(!IsLoaded || generation!=_generation)return;
            Watch(!saved && DownloadPeer is { Length: > 0 } && result.row?.ImageReference is not null ? services : null);
            ClearPreview();
            if (result.row is not { } preview || MessageLinks.Parse(preview.Url) is not { } target) return;
            Grid.SetRow(this, preview.AboveText ? 0 : 2);
            Margin = preview.AboveText ? new Thickness(0,0,0,5) : new Thickness(0,5,0,0);
            _target = target;
            _imageReference = preview.ImageReference;
            ContextMenu = MessageLinks.CreateMenu(target, this);
            ToolTip = target.AbsoluteUri;
            AutomationProperties.SetName(this, string.IsNullOrEmpty(preview.Title) ? target.AbsoluteUri : preview.Title);
            var content = new WebPreviewContent(target.IdnHost,
                preview.Title, preview.Summary, result.image, preview.CompactImage, 132);
            var unreadableCover = result.availableCover && result.image is null;
            if ((unreadableCover || result.pendingCover is not null) && !saved && DownloadPeer is { Length: > 0 } peer &&
                preview.ImageReference is { } cover)
            {
                _pendingCover = result.pendingCover;
                _coverReadFailed = unreadableCover;
                _downloadStatus = new TextBlock { FontSize = 11, TextWrapping = TextWrapping.Wrap };
                _downloadStatus.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
                content.Children.Add(_downloadStatus);
                var download = _downloadButton = IdentitySetupDialog.ActionButton("", () =>
                    _ = SetCoverDownloadAsync(services, peer, id, cover), Brushes.Gray);
                download.HorizontalAlignment = HorizontalAlignment.Left;
                download.Margin = new Thickness(0, 4, 0, 0);
                content.Children.Add(download);
                UpdateCoverControls();
            }
            Child = content;
            Visibility = Visibility.Visible;
        }
        catch(Exception error) when(error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException){if(generation==_generation){ClearPreview();Watch(null);}}
    }
}

public sealed class WebPreviewDraftCard : Border
{
    public static readonly DependencyProperty PreviewProperty = DependencyProperty.Register(nameof(Preview),
        typeof(WebPreviewDraft), typeof(WebPreviewDraftCard), new PropertyMetadata(null, PreviewChanged));
    public static readonly DependencyProperty TargetUrlProperty = DependencyProperty.Register(nameof(TargetUrl),
        typeof(string), typeof(WebPreviewDraftCard), new PropertyMetadata(null, LayoutChanged));
    public static readonly DependencyProperty CompactImageProperty = DependencyProperty.Register(nameof(CompactImage),
        typeof(bool), typeof(WebPreviewDraftCard), new PropertyMetadata(false, LayoutChanged));

    public WebPreviewDraft? Preview { get => (WebPreviewDraft?)GetValue(PreviewProperty); set => SetValue(PreviewProperty, value); }
    public string? TargetUrl { get => (string?)GetValue(TargetUrlProperty); set => SetValue(TargetUrlProperty, value); }
    public bool CompactImage { get => (bool)GetValue(CompactImageProperty); set => SetValue(CompactImageProperty, value); }

    private long _generation;
    private ImageSource? _image;

    public WebPreviewDraftCard()
    {
        WebPreviewContent.StyleCard(this);
        Loaded += (_, _) => LoadPreview();
        Unloaded += (_, _) => { ++_generation; _image = null; Child = null; };
    }

    private static void PreviewChanged(DependencyObject sender, DependencyPropertyChangedEventArgs args) =>
        ((WebPreviewDraftCard)sender).LoadPreview();

    private static void LayoutChanged(DependencyObject sender, DependencyPropertyChangedEventArgs args) =>
        ((WebPreviewDraftCard)sender).Render();

    private void Render()
    {
        if (!IsLoaded || MessageLinks.Parse(TargetUrl) is not { } url)
        {
            Child = null;
            return;
        }

        var draft = Preview?.Url == TargetUrl ? Preview : null;
        Child = new WebPreviewContent(url.IdnHost, draft?.Title ?? "", draft?.Summary ?? "",
            draft is null ? null : _image, CompactImage, 104, url.AbsoluteUri);
    }

    private async void LoadPreview()
    {
        var generation = ++_generation;
        _image = null;
        Render();
        if (!IsLoaded || Preview?.Image is not { Length: > 0 and <= 512 * 1024 } cover) return;

        var bytes = cover.ToArray();
        try
        {
            var image = await Task.Run(() =>
            {
                using var stream = new MemoryStream(bytes, writable: false);
                return ImageDecoder.Load(stream, 512);
            });
            if (!IsLoaded || generation != _generation) return;
            _image = image;
            Render();
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or NotSupportedException
            or FormatException or System.Runtime.InteropServices.ExternalException) { }
        finally { CryptographicOperations.ZeroMemory(bytes); }
    }
}

internal sealed class WebPreviewContent : StackPanel
{
    internal static void StyleCard(Border card)
    {
        card.Padding = new Thickness(9, 7, 9, 7);
        card.BorderThickness = new Thickness(3, 0, 0, 0);
        card.CornerRadius = new CornerRadius(6);
        card.SetResourceReference(Border.BorderBrushProperty, "AccentBrush");
        card.SetResourceReference(Border.BackgroundProperty, "RaisedBrush");
    }

    internal WebPreviewContent(string host, string title, string summary, ImageSource? image, bool compact,
        double coverHeight, string? address = null)
    {
        var labels = new StackPanel();
        var destination = Label(host, 12, true, "AccentTextBrush");
        destination.FlowDirection = FlowDirection.LeftToRight;
        labels.Children.Add(destination);
        if (title.Length != 0) labels.Children.Add(Label(title, 13, true, "TextBrush", 38));
        if (summary.Length != 0) labels.Children.Add(Label(summary, 12, false, "MutedBrush", 48));

        var row = new DockPanel();
        if (image is not null && compact)
        {
            var thumbnail = new Image { Source = image, Width = 56, Height = 56,
                Stretch = Stretch.UniformToFill, ClipToBounds = true, VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(8, 0, 0, 0) };
            DockPanel.SetDock(thumbnail, Dock.Right);
            row.Children.Add(thumbnail);
        }
        row.Children.Add(labels);
        Children.Add(row);
        if (image is not null && !compact)
            Children.Add(new Image { Source = image, Height = coverHeight, Stretch = Stretch.UniformToFill,
                ClipToBounds = true, Margin = new Thickness(0, 5, 0, 0) });
        if (address is not null)
        {
            var url = Label(address, 11, false, "MutedBrush");
            url.TextWrapping = TextWrapping.NoWrap;
            Children.Add(url);
        }
    }

    private static TextBlock Label(string text, double size, bool bold, string brush,
        double height = double.PositiveInfinity)
    {
        var label = new TextBlock { Text = text, FontSize = size, FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal,
            TextWrapping = TextWrapping.Wrap, MaxHeight = height, TextTrimming = TextTrimming.CharacterEllipsis };
        label.SetResourceReference(TextBlock.ForegroundProperty, brush);
        return label;
    }
}
