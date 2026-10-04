using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
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
        var isImage = message.Extension is "jpg" or "jpeg" or "png" or "webp";
        var placeholder = new Grid { Width = 250, Height = 200, Background = isImage ? Brushes.DarkGray : Brushes.Black };
        if (!isImage)
        {
            var play = new Border { Width = 48, Height = 48, CornerRadius = new CornerRadius(24), Background = new SolidColorBrush(Color.FromArgb(128, 0, 0, 0)), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            play.Child = new TextBlock { Text = "▶", FontSize = 32, Foreground = Brushes.White, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            placeholder.Children.Add(play);
        }
        var fallback = placeholder;
        Child = fallback;
        if (!File.Exists(message.Content)) { return; }
        if (!isImage)
        {
            var panel = new Grid { Width = 250, Height = 200, Background = Brushes.Black, ClipToBounds = true };
            var video = new MediaElement
            {
                Source = new Uri(message.Content), LoadedBehavior = MediaState.Manual,
                UnloadedBehavior = MediaState.Close, ScrubbingEnabled = true,
                IsMuted = true, Stretch = Stretch.UniformToFill, IsHitTestVisible = false
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
            var play = new Border { Width = 48, Height = 48, CornerRadius = new CornerRadius(24), Background = new SolidColorBrush(Color.FromArgb(128, 0, 0, 0)), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, IsHitTestVisible = false };
            play.Child = new TextBlock { Text = "▶", FontSize = 32, Foreground = Brushes.White, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            panel.Children.Add(play);
            Child = panel;
            video.Play();
            return;
        }
        try
        {
            var bitmap = await Task.Run(() => ImageDecoder.Load(message.Content, 560));
            if (generation != _generation || !IsLoaded) { return; }
            Child = new Image { Source = bitmap, Width = 250, Height = 200, Stretch = Stretch.UniformToFill };
        }
        catch (Exception error) when (error is IOException or NotSupportedException or InvalidOperationException or ArgumentException)
        {
            // Keep the same fixed media surface if decoding fails.
            if (generation == _generation) { Child = fallback; }
        }
    }
}
