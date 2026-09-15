using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Infrastructure;

public sealed class AttachmentPreview : Border
{
    public static readonly DependencyProperty MessageProperty = DependencyProperty.Register(
        nameof(Message), typeof(MessageItem), typeof(AttachmentPreview),
        new PropertyMetadata(null, (owner, _) => ((AttachmentPreview)owner).Refresh()));
    private int _generation;
    private MediaElement? _video;

    public AttachmentPreview()
    {
        Background = Brushes.Transparent;
        Loaded += (_, _) => Refresh();
        Unloaded += (_, _) => { _generation++; CloseVideo(); Child = null; };
    }

    public MessageItem? Message
    {
        get => (MessageItem?)GetValue(MessageProperty);
        set => SetValue(MessageProperty, value);
    }

    protected override void OnRenderSizeChanged(SizeChangedInfo sizeInfo)
    {
        base.OnRenderSizeChanged(sizeInfo);
        Clip = new RectangleGeometry(new Rect(RenderSize), 12, 12);
    }

    private void CloseVideo()
    {
        _video?.Close();
        _video = null;
    }

    private async void Refresh()
    {
        var generation = ++_generation;
        CloseVideo();
        Child = null;
        if (!IsLoaded || Message is not { IsAttachment: true } message) { return; }
        var fallback = new TextBlock { Text = message.DisplayContent, TextWrapping = TextWrapping.Wrap, MaxWidth = 360 };
        Child = fallback;
        if (!File.Exists(message.Content)) { return; }
        if (message.Extension is "mp4" or "mov" or "webm")
        {
            var panel = new Grid { Width = 280, Height = 180, Background = Brushes.Black, ClipToBounds = true };
            var video = new MediaElement
            {
                Source = new Uri(message.Content), LoadedBehavior = MediaState.Manual,
                UnloadedBehavior = MediaState.Close, ScrubbingEnabled = true,
                IsMuted = true, Stretch = Stretch.Uniform, IsHitTestVisible = false
            };
            _video = video;
            video.MediaOpened += (_, _) => { video.Pause(); video.Position = TimeSpan.FromMilliseconds(100); };
            video.MediaFailed += (_, _) =>
            {
                if (generation != _generation) { return; }
                CloseVideo();
                Child = fallback;
            };
            panel.Children.Add(video);
            panel.Children.Add(new TextBlock
            {
                Text = "▶", FontSize = 30, Foreground = Brushes.White,
                HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
                IsHitTestVisible = false
            });
            Child = panel;
            video.Play();
            return;
        }
        if (message.Extension is not ("jpg" or "jpeg" or "png" or "bmp" or "gif" or "webp")) { return; }
        try
        {
            var bitmap = await Task.Run(() =>
            {
                var image = new BitmapImage();
                image.BeginInit();
                image.CacheOption = BitmapCacheOption.OnLoad;
                image.DecodePixelWidth = 560;
                image.UriSource = new Uri(message.Content);
                image.EndInit();
                image.Freeze();
                return image;
            });
            if (generation != _generation || !IsLoaded) { return; }
            Child = new Image { Source = bitmap, MaxWidth = 280, MaxHeight = 240, Stretch = Stretch.Uniform };
        }
        catch (Exception error) when (error is IOException or NotSupportedException or InvalidOperationException or ArgumentException)
        {
            // Unsupported codecs keep the attachment label and the same bounded click target.
            if (generation == _generation) { Child = fallback; }
        }
    }
}
