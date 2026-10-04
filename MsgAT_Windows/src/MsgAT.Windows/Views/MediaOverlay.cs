using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Views;

public sealed class MediaOverlay : UserControl, IDisposable
{
    private readonly Action _dismiss;
    private readonly MediaElement? _media;
    private readonly DispatcherTimer? _timer;
    private bool _disposed;
    private bool _playing = true;
    public MediaOverlay(string path, string extension, Action dismiss)
    {
        _dismiss = dismiss;
        var root = new Grid { Background = Brushes.Black, ClipToBounds = true };
        root.MouseLeftButtonUp += (_, e) => { e.Handled = true; _dismiss(); };
        Content = root;
        Unloaded += (_, _) => Dispose();
        PreviewKeyDown += (_, e) => { if (e.Key == Key.Escape) { e.Handled = true; _dismiss(); } };
        Focusable = true; Loaded += (_, _) => Focus();
        if (extension is not ("jpg" or "jpeg" or "png" or "webp"))
        {
            if (!File.Exists(path)) { root.Children.Add(Label("Loading Video...")); return; }
            _media = new MediaElement { Source = new Uri(path), LoadedBehavior = MediaState.Manual, UnloadedBehavior = MediaState.Close, ScrubbingEnabled = true, Stretch = Stretch.Uniform, IsHitTestVisible = false };
            var media = _media; root.Children.Add(media);
            var controls = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0) };
            controls.MouseLeftButtonUp += (_, e) => e.Handled = true;
            var row = new StackPanel { Orientation = Orientation.Horizontal };
            var toggle = new Button { Content = PauseIcon, ToolTip = "Pause", Style = (Style)FindResource("IconButtonStyle"), Background = Brushes.Transparent, BorderThickness = new Thickness(0), Foreground = Brushes.White, Width = 40, Height = 40 };
            var time = new TextBlock { Text = "00:00 / 00:00", Foreground = Brushes.White, FontSize = 12, Margin = new Thickness(8, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            row.Children.Add(toggle); row.Children.Add(time); controls.Children.Add(row);
            var progress = new Slider { Minimum = 0, Maximum = 1, IsEnabled = false, IsMoveToPointEnabled = true, Margin = new Thickness(0, 8, 0, 0), Foreground = (Brush)FindResource("AccentBrush") };
            controls.Children.Add(progress);
            var card = new Border { Child = controls, Padding = new Thickness(16), VerticalAlignment = VerticalAlignment.Bottom, Background = new SolidColorBrush(Color.FromArgb(153, 0, 0, 0)) };
            card.MouseLeftButtonUp += (_, e) => e.Handled = true; root.Children.Add(card);
            var updating = false; var scrubbing = false;
            static string Format(TimeSpan value) => $"{(long)value.TotalMinutes:00}:{value.Seconds:00}";
            void Refresh()
            {
                if (!media.NaturalDuration.HasTimeSpan) return;
                if (!scrubbing) { updating = true; progress.Value = media.Position.TotalSeconds; updating = false; }
                time.Text = $"{Format(TimeSpan.FromSeconds(progress.Value))} / {Format(media.NaturalDuration.TimeSpan)}";
                toggle.Content = _playing ? PauseIcon : PlayIcon; toggle.ToolTip = _playing ? "Pause" : "Play";
            }
            _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) }; _timer.Tick += (_, _) => Refresh();
            media.MediaOpened += (_, _) => { if (media.NaturalDuration.HasTimeSpan) { progress.Maximum = media.NaturalDuration.TimeSpan.TotalSeconds; progress.IsEnabled = progress.Maximum > 0; } Refresh(); _timer.Start(); };
            media.MediaEnded += (_, _) => { media.Pause(); _playing = false; progress.Value = progress.Maximum; Refresh(); };
            media.MediaFailed += (_, _) => { _timer.Stop(); if (Window.GetWindow(this) is { } owner) TransientNotice.Show(owner, "Failed to load video"); };
            toggle.Click += (_, _) => { if (_playing) media.Pause(); else { if (media.NaturalDuration.HasTimeSpan && media.Position >= media.NaturalDuration.TimeSpan) media.Position = TimeSpan.Zero; media.Play(); } _playing = !_playing; Refresh(); };
            progress.AddHandler(System.Windows.Controls.Primitives.Thumb.DragStartedEvent, new System.Windows.Controls.Primitives.DragStartedEventHandler((_, _) => scrubbing = true));
            progress.AddHandler(System.Windows.Controls.Primitives.Thumb.DragCompletedEvent, new System.Windows.Controls.Primitives.DragCompletedEventHandler((_, _) => { scrubbing = false; media.Position = TimeSpan.FromSeconds(progress.Value); Refresh(); }));
            progress.ValueChanged += (_, _) => { if (!updating && !scrubbing && progress.IsEnabled) media.Position = TimeSpan.FromSeconds(progress.Value); };
            Loaded += (_, _) => media.Play();
        }
        else
        {
            var loading = false;
            Loaded += async (_, _) => { if (!loading) { loading = true; await LoadImageAsync(path, root); } };
        }
    }

    private async Task LoadImageAsync(string path, Grid root)
    {
        try
        {
            var bitmap = await Task.Run(() => ImageDecoder.Load(path));
            if (_disposed || !IsLoaded) return;
            var image = new Image { Source = bitmap, Stretch = Stretch.Uniform };
            var scale = new ScaleTransform(1, 1); var offset = new TranslateTransform();
            var transform = new TransformGroup(); transform.Children.Add(scale); transform.Children.Add(offset);
            image.RenderTransform = transform; image.RenderTransformOrigin = new Point(0.5, 0.5); root.Children.Add(image);
            Point? drag = null; Point origin = default; var moved = false;
            image.PreviewMouseWheel += (_, e) => { scale.ScaleX = scale.ScaleY = Math.Clamp(scale.ScaleX * Math.Pow(1.15, e.Delta / 120d), 1, 5); if (scale.ScaleX == 1) { offset.X = 0; offset.Y = 0; } e.Handled = true; };
            image.MouseLeftButtonDown += (_, e) => { if (scale.ScaleX <= 1) return; drag = e.GetPosition(root); origin = new Point(offset.X, offset.Y); moved = false; image.CaptureMouse(); e.Handled = true; };
            image.MouseMove += (_, e) => { if (drag is not { } start) return; var delta = e.GetPosition(root) - start; if (delta.Length < 4 && !moved) return; moved = true; offset.X = origin.X + delta.X; offset.Y = origin.Y + delta.Y; };
            image.MouseLeftButtonUp += (_, e) => { if (drag is null) return; drag = null; image.ReleaseMouseCapture(); e.Handled = true; if (!moved) _dismiss(); };
        }
        catch (Exception error) when (error is IOException or NotSupportedException or InvalidOperationException or ArgumentException)
        { if (!_disposed && IsLoaded) root.Children.Add(Label("Corrupted Image")); }
    }
    private static Geometry PlayIcon => Geometry.Parse("M 7,4 L 20,12 L 7,20 Z");
    private static Geometry PauseIcon => Geometry.Parse("M 7,4 L 7,20 L 10,20 L 10,4 Z M 14,4 L 14,20 L 17,20 L 17,4 Z");
    private static TextBlock Label(string text) => new() { Text = text, Foreground = Brushes.White, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    public void Dispose()
    {
        if (!_disposed) { _disposed = true; _timer?.Stop(); _media?.Close(); }
        GC.SuppressFinalize(this);
    }
}
