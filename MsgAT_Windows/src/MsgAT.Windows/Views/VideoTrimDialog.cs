// SPDX-License-Identifier: AGPL-3.0-only
using MsgAT.Windows.Infrastructure;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Media;
using Path = System.IO.Path;

namespace MsgAT.Windows.Views;

internal sealed class VideoTrimDialog : DialogWindow
{
    private readonly MediaElement _player = new()
    {
        LoadedBehavior = MediaState.Manual,
        UnloadedBehavior = MediaState.Close,
        ScrubbingEnabled = true,
        Stretch = Stretch.Uniform
    };
    private readonly DispatcherTimer _clock = new() { Interval = TimeSpan.FromMilliseconds(20) };
    private readonly Slider _start = new() { IsEnabled = false, SmallChange = .1, LargeChange = 1, IsMoveToPointEnabled = true };
    private readonly Slider _end = new() { IsEnabled = false, SmallChange = .1, LargeChange = 1, IsMoveToPointEnabled = true };
    private readonly CheckBox _muteAudio = new() { Content = AppStrings.Get("UiRemoveAudio"), IsEnabled = false, Margin = new Thickness(0, 6, 0, 4) };
    private readonly TextBlock _startLabel = new();
    private readonly TextBlock _endLabel = new();
    private readonly TextBlock _status = new() { Text = AppStrings.Get("UiOpeningVideo"), TextWrapping = TextWrapping.Wrap };
    private readonly Grid _filmstrip = new() { Height = 46, Background = Brushes.Black, ClipToBounds = true, Margin = new Thickness(0, 0, 0, 8) };
    private readonly UniformGrid _frames = new() { Rows = 1, Columns = 6, IsHitTestVisible = false };
    private readonly Canvas _timeline = new() { IsHitTestVisible = false };
    private readonly Rectangle _leftShade = new() { Fill = Brushes.Black, Opacity = .62 };
    private readonly Rectangle _rightShade = new() { Fill = Brushes.Black, Opacity = .62 };
    private readonly Rectangle _selection = new() { StrokeThickness = 2 };
    private readonly Rectangle _leftHandle = new() { Width = 5 };
    private readonly Rectangle _rightHandle = new() { Width = 5 };
    private readonly Button _play;
    private readonly Button _apply;
    private CancellationTokenSource? _loading;
    private TimeSpan _duration;
    private bool _loaded;
    private bool _changingBounds;
    private bool _closed;
    private (bool AtStart, double Pointer, double Position)? _drag;

    public VideoTrim? Result { get; private set; }

    public VideoTrimDialog(string path, VideoTrim? initial)
    {
        Title = AppStrings.Get("UiEditVideo");
        Width = Math.Min(700, SystemParameters.WorkArea.Width * .94);
        Height = Math.Min(560, SystemParameters.WorkArea.Height * .90);
        var panel = new DockPanel { Margin = new Thickness(16) };
        var heading = IdentitySetupDialog.Heading(Title);
        DockPanel.SetDock(heading, Dock.Top);
        panel.Children.Add(heading);
        var controls = new StackPanel { Margin = new Thickness(0, 8, 0, 0) };
        _selection.SetResourceReference(Shape.StrokeProperty, "AccentBrush");
        _leftHandle.SetResourceReference(Shape.FillProperty, "AccentBrush");
        _rightHandle.SetResourceReference(Shape.FillProperty, "AccentBrush");
        _filmstrip.Children.Add(_frames);
        _filmstrip.Children.Add(_timeline);
        foreach (var part in new[] { _leftShade, _rightShade, _selection, _leftHandle, _rightHandle })
            _timeline.Children.Add(part);
        controls.Children.Add(_filmstrip);
        controls.Children.Add(_startLabel);
        controls.Children.Add(_start);
        controls.Children.Add(_endLabel);
        controls.Children.Add(_end);
        controls.Children.Add(_muteAudio);
        controls.Children.Add(_status);
        AutomationProperties.SetName(_start, AppStrings.Get("UiSelectionStart"));
        AutomationProperties.SetName(_end, AppStrings.Get("UiSelectionEnd"));
        var buttons = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        _play = IdentitySetupDialog.ActionButton(AppStrings.Get("UiPlaySelection"), TogglePlayback, Brushes.Gray);
        _play.IsEnabled = false;
        buttons.Children.Add(_play);
        buttons.Children.Add(IdentitySetupDialog.ActionButton(AppStrings.Get("UiCancel"), () => DialogResult = false, Brushes.Gray));
        _apply = IdentitySetupDialog.ActionButton(AppStrings.Get("UiApply"), Apply, Brushes.Gray);
        _apply.IsEnabled = false;
        buttons.Children.Add(_apply);
        controls.Children.Add(buttons);
        DockPanel.SetDock(controls, Dock.Bottom);
        panel.Children.Add(controls);
        panel.Children.Add(new Border { Background = Brushes.Black, Child = _player });
        Content = panel;

        _filmstrip.SizeChanged += (_, _) => DrawTimeline();
        _filmstrip.MouseLeftButtonDown += BeginTimelineDrag;
        _filmstrip.MouseMove += MoveTimelineHandle;
        _filmstrip.MouseLeftButtonUp += (_, _) => _filmstrip.ReleaseMouseCapture();
        _filmstrip.LostMouseCapture += (_, _) =>
        {
            if (_drag is { } drag && _loaded && !_closed)
                _player.Position = drag.AtStart ? Start : End - TimeSpan.FromMilliseconds(1);
            _drag = null;
        };
        _start.ValueChanged += (_, _) => ChangeBound(atStart: true);
        _end.ValueChanged += (_, _) => ChangeBound(atStart: false);
        _muteAudio.Checked += (_, _) => _player.IsMuted = true;
        _muteAudio.Unchecked += (_, _) => _player.IsMuted = false;
        _player.MediaOpened += (_, _) =>
        {
            if (_closed || !_player.NaturalDuration.HasTimeSpan) return;
            _loaded = true;
            _start.IsEnabled = _end.IsEnabled = _play.IsEnabled = _apply.IsEnabled = true;
            _muteAudio.IsEnabled = true;
            _player.Pause();
            _player.Position = Start;
            UpdateLabels();
        };
        _player.MediaEnded += (_, _) => StopPlayback();
        _player.MediaFailed += (_, _) => ShowError(AppStrings.Get("UiVideoPreviewError"));
        _clock.Tick += (_, _) =>
        {
            if (_player.Position < End) return;
            StopPlayback();
            _player.Position = End;
        };
        Loaded += async (_, _) => await OpenAsync(path, initial);
        Deactivated += (_, _) => { if (_clock.IsEnabled) StopPlayback(); };
        Closed += (_, _) =>
        {
            _closed = true;
            _loading?.Cancel();
            _filmstrip.ReleaseMouseCapture();
            _frames.Children.Clear();
            _clock.Stop();
            _player.Close();
            _player.Source = null;
        };
    }

    private TimeSpan Start => TimeSpan.FromSeconds(_start.Value);
    private TimeSpan End => _end.Value == _end.Maximum ? _duration : TimeSpan.FromSeconds(_end.Value);
    internal static string DisplayTime(TimeSpan time) => time.ToString(@"m\:ss\.f", CultureInfo.CurrentCulture);

    private async Task OpenAsync(string path, VideoTrim? initial)
    {
        using var cancellation = new CancellationTokenSource();
        _loading = cancellation;
        try
        {
            var duration = await VideoCompression.GetTrimDurationAsync(path, cancellation.Token);
            if (_closed) return;
            if (duration is null || duration.Value <= VideoTrim.MinimumDuration)
            {
                ShowError(AppStrings.Get("UiCannotTrim"));
                return;
            }
            _duration = duration.Value;
            if (initial?.Fits(_duration) != true) initial = null;
            _muteAudio.IsChecked = initial?.MuteAudio == true;
            _changingBounds = true;
            _start.Maximum = _end.Maximum = _duration.TotalSeconds;
            _start.Value = (initial?.Start ?? TimeSpan.Zero).TotalSeconds;
            _end.Value = (initial?.End ?? _duration).TotalSeconds;
            _changingBounds = false;
            _player.Source = new Uri(Path.GetFullPath(path), UriKind.Absolute);
            _player.Pause();
            DrawTimeline();
            var frames = await VideoThumbnail.LoadTrimFramesAsync(Path.GetFullPath(path), _duration, cancellation.Token);
            if (_closed) return;
            foreach (var frame in frames)
            {
                _frames.Children.Add(new Border
                {
                    Margin = new Thickness(0, 0, 1, 0),
                    Background = frame is null ? Brushes.Black : new ImageBrush(frame) { Stretch = Stretch.UniformToFill }
                });
            }
        }
        catch (OperationCanceledException) when (_closed) { }
        catch (Exception error) when (error is COMException or IOException or ArgumentException or NotSupportedException or UnauthorizedAccessException)
        {
            if (!_closed) ShowError(AppStrings.Get("UiVideoOpenError"));
        }
        finally { _loading = null; }
    }

    private void ChangeBound(bool atStart)
    {
        if (!_loaded || _changingBounds) return;
        _changingBounds = true;
        var minimum = VideoTrim.MinimumDuration.TotalSeconds;
        if (atStart) _start.Value = Math.Min(_start.Value, Math.Max(0, _end.Value - minimum));
        else _end.Value = Math.Max(_end.Value, Math.Min(_duration.TotalSeconds, _start.Value + minimum));
        _changingBounds = false;
        StopPlayback();
        if (_drag is null) _player.Position = atStart ? Start : End - TimeSpan.FromMilliseconds(1);
        UpdateLabels();
    }

    private void BeginTimelineDrag(object sender, MouseButtonEventArgs e)
    {
        if (!_loaded || _filmstrip.ActualWidth <= 0) return;
        var pointer = e.GetPosition(_filmstrip).X;
        var left = Math.Abs(pointer - TimelineX(_start.Value));
        var right = Math.Abs(pointer - TimelineX(_end.Value));
        if (Math.Min(left, right) > 20 || !_filmstrip.CaptureMouse()) return;
        var atStart = left <= right;
        _drag = (atStart, pointer, atStart ? _start.Value : _end.Value);
        StopPlayback();
        e.Handled = true;
    }

    private void MoveTimelineHandle(object sender, MouseEventArgs e)
    {
        if (_drag is not { } drag || _filmstrip.ActualWidth <= 0) return;
        var point = e.GetPosition(_filmstrip).X;
        var position = drag.Position + (point - drag.Pointer) * _duration.TotalSeconds / _filmstrip.ActualWidth;
        var slider = drag.AtStart ? _start : _end;
        slider.Value = Math.Clamp(position, slider.Minimum, slider.Maximum);
    }

    private double TimelineX(double seconds) => seconds / _duration.TotalSeconds * _filmstrip.ActualWidth;

    private void DrawTimeline()
    {
        var width = _filmstrip.ActualWidth;
        var height = _filmstrip.ActualHeight;
        if (_duration <= TimeSpan.Zero || width <= 0 || height <= 0) return;
        var left = Math.Clamp(TimelineX(_start.Value), 0, width);
        var right = Math.Clamp(TimelineX(_end.Value), left, width);
        SetPart(_leftShade, 0, left);
        SetPart(_rightShade, right, width - right);
        SetPart(_selection, left, right - left);
        SetPart(_leftHandle, Math.Clamp(left - 2.5, 0, Math.Max(0, width - 5)), 5);
        SetPart(_rightHandle, Math.Clamp(right - 2.5, 0, Math.Max(0, width - 5)), 5);

        void SetPart(Rectangle part, double x, double length)
        {
            Canvas.SetLeft(part, x);
            part.Width = length;
            part.Height = height;
        }
    }

    private void TogglePlayback()
    {
        if (_clock.IsEnabled)
        {
            StopPlayback();
            return;
        }
        if (!_loaded) return;
        if (_player.Position < Start || _player.Position >= End - TimeSpan.FromMilliseconds(20))
            _player.Position = Start;
        _player.Play();
        _clock.Start();
        _play.Content = AppStrings.Get("UiPause");
    }

    private void StopPlayback()
    {
        _clock.Stop();
        _player.Pause();
        _play.Content = AppStrings.Get("UiPlaySelection");
    }

    private void UpdateLabels()
    {
        _startLabel.Text = $"{AppStrings.Get("UiSelectionStart")}  {DisplayTime(Start)}";
        _endLabel.Text = $"{AppStrings.Get("UiSelectionEnd")}  {DisplayTime(End)}";
        _status.Text = AppStrings.Format("UiDurationSelected", DisplayTime(End - Start));
        DrawTimeline();
    }

    private void ShowError(string message)
    {
        _clock.Stop();
        _loaded = false;
        _start.IsEnabled = _end.IsEnabled = _play.IsEnabled = _apply.IsEnabled = false;
        _muteAudio.IsEnabled = false;
        _status.Text = message;
    }

    private void Apply()
    {
        if (!_loaded) return;
        var trim = new VideoTrim(Start, End, _muteAudio.IsChecked == true);
        if (!trim.Fits(_duration)) return;
        Result = _start.Value == 0 && _end.Value == _end.Maximum && !trim.MuteAudio ? null : trim;
        DialogResult = true;
    }
}
