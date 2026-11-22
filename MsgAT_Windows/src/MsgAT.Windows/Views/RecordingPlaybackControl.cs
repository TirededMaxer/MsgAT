// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Threading;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Storage.Streams;
using PlaybackPlayer = Windows.Media.Playback.MediaPlayer;

namespace MsgAT.Windows.Views;

internal sealed class RecordingPlaybackControl : Border, IDisposable
{
    private readonly EncryptedCaptureFile _recording;
    private readonly Func<bool> _canPlay;
    private readonly Button _toggle = new() { Width = 36, Padding = new Thickness(0), IsEnabled = false };
    private readonly Slider _position = new()
    {
        Maximum = 1, SmallChange = 0.25, LargeChange = 1, IsMoveToPointEnabled = true, IsEnabled = false,
        VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(10, 0, 10, 0)
    };
    private readonly TextBlock _time = new() { VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center };
    private readonly Button _retry = new() { Content = AppStrings.Get("UiRetry"), Margin = new Thickness(0, 0, 10, 0) };
    private readonly Grid _controls = new();
    private readonly DockPanel _feedback = new();
    private readonly DispatcherTimer _clock = new() { Interval = TimeSpan.FromMilliseconds(100) };
    private PlaybackPlayer? _player;
    private MediaSource? _source;
    private IRandomAccessStream? _stream;
    private Stream? _input;
    private bool _ready, _playing, _scrubbing, _updating, _ended, _disposed;

    internal RecordingPlaybackControl(EncryptedCaptureFile recording, Func<bool> canPlay)
    {
        _recording = recording;
        _canPlay = canPlay;
        CornerRadius = new CornerRadius(9);
        BorderThickness = new Thickness(1);
        Margin = new Thickness(0, 8, 0, 0);
        Padding = new Thickness(8);
        SetResourceReference(BackgroundProperty, "SurfaceBrush");
        SetResourceReference(BorderBrushProperty, "BorderBrush");
        _time.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        AutomationProperties.SetName(_position, AppStrings.Get("UiRecordingPosition"));

        _controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _controls.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _controls.Children.Add(_toggle);
        Grid.SetColumn(_position, 1);
        _controls.Children.Add(_position);
        Grid.SetColumn(_time, 2);
        _controls.Children.Add(_time);
        _feedback.Children.Add(_retry);
        _feedback.Children.Add(_status);
        var content = new Grid();
        content.Children.Add(_controls);
        content.Children.Add(_feedback);
        Child = content;

        _toggle.Click += (_, _) => Toggle();
        _retry.Click += (_, _) => Open();
        _clock.Tick += (_, _) => RefreshPosition();
        _position.ValueChanged += (_, args) =>
        {
            if (_updating) return;
            if (_scrubbing) UpdateTime(args.NewValue);
            else Seek(args.NewValue);
        };
        _position.AddHandler(Thumb.DragStartedEvent, new DragStartedEventHandler((_, _) => _scrubbing = true));
        _position.AddHandler(Thumb.DragCompletedEvent, new DragCompletedEventHandler((_, args) =>
        {
            _scrubbing = false;
            if (args.Canceled) RefreshPosition();
            else Seek(_position.Value);
        }));
        Loaded += StartOnLoad;
    }

    private void StartOnLoad(object sender, RoutedEventArgs args)
    {
        Loaded -= StartOnLoad;
        Open();
    }

    private void Open()
    {
        if (_disposed) return;
        _controls.Visibility = Visibility.Collapsed;
        _feedback.Visibility = Visibility.Visible;
        _retry.Visibility = Visibility.Collapsed;
        _status.Text = AppStrings.Get("UiLoading");
        try
        {
            _input = _recording.OpenRead();
            _stream = _input.AsRandomAccessStream();
            _source = MediaSource.CreateFromStream(_stream, _recording.Mime);
            _player = new PlaybackPlayer { AutoPlay = false };
            _player.CommandManager.IsEnabled = false;
            _player.MediaOpened += MediaOpened;
            _player.MediaEnded += MediaEnded;
            _player.MediaFailed += MediaFailed;
            _player.Source = _source;
        }
        catch (Exception ex) when (ex is IOException or InvalidOperationException or ArgumentException or COMException)
        {
            ShowFailure();
        }
    }

    private void MediaOpened(PlaybackPlayer sender, object args) => ApplyEvent(sender, player =>
    {
        var session = player.PlaybackSession;
        _ready = true;
        _ended = false;
        _position.Maximum = Math.Max(0.001, session.NaturalDuration.TotalSeconds);
        _position.IsEnabled = session.CanSeek && session.NaturalDuration > TimeSpan.Zero;
        _toggle.IsEnabled = true;
        _feedback.Visibility = Visibility.Collapsed;
        _controls.Visibility = Visibility.Visible;
        SetPlaying(false);
        DisplayPosition(0);
    });

    private void MediaEnded(PlaybackPlayer sender, object args) => ApplyEvent(sender, _ =>
    {
        _ended = true;
        SetPlaying(false);
        if (!_scrubbing) DisplayPosition(_position.Maximum);
    });

    private void MediaFailed(PlaybackPlayer sender, MediaPlayerFailedEventArgs args) => ApplyEvent(sender, _ => ShowFailure());

    private void ApplyEvent(PlaybackPlayer sender, Action<PlaybackPlayer> action) => _ = Dispatcher.InvokeAsync(() =>
    {
        if (!_disposed && ReferenceEquals(sender, _player)) action(sender);
    });

    private void Toggle()
    {
        if (!_ready || _player is not { } player) return;
        if (_playing) { PausePlayback(); return; }
        if (!_canPlay()) return;
        try
        {
            if (_ended && player.PlaybackSession.CanSeek) player.PlaybackSession.Position = TimeSpan.Zero;
            _ended = false;
            player.Play();
            SetPlaying(true);
            RefreshPosition();
        }
        catch (COMException) { ShowFailure(); }
    }

    internal void PausePlayback()
    {
        if (!_playing || _player is not { } player) return;
        try
        {
            player.Pause();
            SetPlaying(false);
            RefreshPosition();
        }
        catch (COMException) { ShowFailure(); }
    }

    private void Seek(double seconds)
    {
        if (!_position.IsEnabled || _player is not { } player) return;
        try
        {
            player.PlaybackSession.Position = TimeSpan.FromSeconds(seconds);
            _ended = false;
            UpdateTime(seconds);
        }
        catch (COMException) { ShowFailure(); }
    }

    private void RefreshPosition()
    {
        if (_scrubbing || _ended || !_ready || _player is not { } player) return;
        try { DisplayPosition(player.PlaybackSession.Position.TotalSeconds); }
        catch (COMException) { ShowFailure(); }
    }

    private void DisplayPosition(double seconds)
    {
        _updating = true;
        _position.Value = seconds;
        _updating = false;
        UpdateTime(_position.Value);
    }

    private void UpdateTime(double seconds) => _time.Text =
        $"{TimeSpan.FromSeconds(seconds):mm\\:ss} / {TimeSpan.FromSeconds(_position.Maximum):mm\\:ss}";

    private void SetPlaying(bool playing)
    {
        _playing = playing;
        var title = AppStrings.Get(playing ? "UiPause" : "UiRecordingPlay");
        _toggle.Content = new TextBlock
        {
            Text = playing ? "\uE769" : "\uE768", FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets")
        };
        _toggle.ToolTip = title;
        AutomationProperties.SetName(_toggle, title);
        if (playing) _clock.Start();
        else _clock.Stop();
    }

    private void ShowFailure()
    {
        ReleaseMedia();
        _controls.Visibility = Visibility.Collapsed;
        _status.Text = AppStrings.Get("UiMediaPlaybackUnavailable");
        _retry.Visibility = Visibility.Visible;
        _feedback.Visibility = Visibility.Visible;
    }

    private void ReleaseMedia()
    {
        _clock.Stop();
        _ready = _playing = _scrubbing = false;
        _toggle.IsEnabled = _position.IsEnabled = false;
        var player = _player;
        _player = null;
        if (player is not null)
        {
            player.MediaOpened -= MediaOpened;
            player.MediaEnded -= MediaEnded;
            player.MediaFailed -= MediaFailed;
            player.Dispose();
        }
        _source?.Dispose();
        _source = null;
        _stream?.Dispose();
        _stream = null;
        _input?.Dispose();
        _input = null;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Loaded -= StartOnLoad;
        ReleaseMedia();
    }
}
