using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Diagnostics;
using Microsoft.Web.WebView2.Wpf;
using MsgAT.Windows.Calls;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.Views;

namespace MsgAT.Windows;

public partial class CallOverlay : UserControl, IDisposable
{
    private readonly CallService _calls;
    private bool _minimized;
    private bool _dragging;
    private Point _dragStart;
    private Point _startOffset;
    private bool _moved;
    private bool _dockRight = true;
    private bool _dockBottom;
    private CallState? _lastState;
    public CallOverlay(CallService calls, WebView2CompositionControl video)
    {
        InitializeComponent(); _calls = calls; DataContext = calls;
        video.Width = video.Height = double.NaN;
        video.HorizontalAlignment = HorizontalAlignment.Stretch;
        video.VerticalAlignment = VerticalAlignment.Stretch;
        video.Opacity = 1;
        VideoHost.Children.Add(video);
        _calls.PropertyChanged += Calls_PropertyChanged;
        Loaded += (_, _) => FocusCallControls();
        UpdateActions();
    }
    public event EventHandler? CoverageChanged;
    public bool CoversChat => _calls.State != CallState.Idle && !_minimized;
    public void Dispose()
    {
        _calls.PropertyChanged -= Calls_PropertyChanged;
        StopCompactMotion();
        if (CompactPanel.IsMouseCaptured) CompactPanel.ReleaseMouseCapture();
        GC.SuppressFinalize(this);
    }
    private void Calls_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(CallService.State) or nameof(CallService.IsVideoCall)) Dispatcher.BeginInvoke(UpdateActions);
    }
    private void UpdateActions()
    {
        var stateChanged = _lastState != _calls.State;
        _lastState = _calls.State;
        var visible = _calls.State != CallState.Idle;
        Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
        IsHitTestVisible = visible;
        if (_calls.State == CallState.Idle || _calls.State == CallState.IncomingRinging)
        {
            _minimized = false;
            _dockRight = true;
            _dockBottom = false;
        }
        FullPanel.Visibility = _minimized ? Visibility.Collapsed : Visibility.Visible;
        CompactPanel.Visibility = _minimized ? Visibility.Visible : Visibility.Collapsed;
        IncomingActions.Visibility = _calls.IsIncoming ? Visibility.Visible : Visibility.Collapsed;
        var video = _calls.IsVideoCall && _calls.State is CallState.OutgoingRinging or CallState.Connecting or CallState.Connected;
        PeerDetails.Visibility = video ? Visibility.Collapsed : Visibility.Visible;
        VideoHeader.Visibility = VideoActions.Visibility = RemoteVideoStatus.Visibility =
            video ? Visibility.Visible : Visibility.Collapsed;
        ActiveActions.Visibility = _calls.IsIncoming || video ? Visibility.Collapsed : Visibility.Visible;
        VideoHost.Opacity = video ? 1 : 0;
        VideoHost.Width = VideoHost.Height = video ? double.NaN : 1;
        VideoHost.Margin = video ? new Thickness(16, 63, 16, 78) : new Thickness(0);
        MinimizeButton.Visibility = _calls.IsIncoming ? Visibility.Collapsed : Visibility.Visible;
        Quality.Visibility = _calls.State == CallState.Connected ? Visibility.Visible : Visibility.Collapsed;
        CoverageChanged?.Invoke(this, EventArgs.Empty);
        if (stateChanged && IsLoaded && CoversChat && !IsKeyboardFocusWithin) FocusCallControls();
    }

    private void FocusCallControls()
    {
        if (!CoversChat || Window.GetWindow(this)?.IsActive != true) return;
        if (_calls.IsIncoming) RejectButton.Focus();
        else Focus();
    }

    internal void FocusIncomingControls()
    {
        if (_calls.IsIncoming) FocusCallControls();
    }

    internal bool MinimizeFromKeyboard()
    {
        if (!CoversChat || _calls.IsIncoming) return false;
        MinimizeCall();
        return true;
    }
    private async void Accept_Click(object sender, RoutedEventArgs e) => await RunAsync(_calls.AcceptAsync);
    private async void Reject_Click(object sender, RoutedEventArgs e) => await RunAsync(_calls.RejectAsync);
    private async void End_Click(object sender, RoutedEventArgs e) => await RunAsync(_calls.EndAsync);
    private async void Mute_Click(object sender, RoutedEventArgs e) => await RunAsync(_calls.ToggleMuteAsync);
    private async void OutputRole_Click(object sender, RoutedEventArgs e) => await RunAsync(_calls.ToggleOutputRoleAsync);
    private async void Camera_Click(object sender, RoutedEventArgs e) => await RunAsync(_calls.ToggleCameraAsync);
    private async void SwitchCamera_Click(object sender, RoutedEventArgs e) => await RunAsync(_calls.SwitchCameraAsync);

    private void AudioDevices_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button || !_calls.CanChooseAudioDevices || _calls.AudioDevices is not { } devices) return;
        var menu = new ContextMenu
        {
            PlacementTarget = button,
            Placement = System.Windows.Controls.Primitives.PlacementMode.Top
        };
        var input = new MenuItem { Header = AppStrings.Get("UiRecordingMicrophone") };
        AddAudioOption(input, AppStrings.Get("UiCallSystemDefault"), "default", devices.InputDevice, _calls.SelectMicrophoneAsync);
        foreach (var endpoint in devices.Inputs)
            AddAudioOption(input, endpoint.Name, endpoint.Id, devices.InputDevice, _calls.SelectMicrophoneAsync);
        var output = new MenuItem { Header = AppStrings.Get("UiCallAudioOutput"), IsEnabled = devices.CanSelectOutput };
        AddAudioOption(output, AppStrings.Get("UiCallSystemDefault"), "default", devices.OutputDevice, _calls.SelectAudioOutputAsync);
        AddAudioOption(output, AppStrings.Get("UiCallSystemCommunications"), "communications", devices.OutputDevice, _calls.SelectAudioOutputAsync);
        foreach (var endpoint in devices.Outputs)
            AddAudioOption(output, endpoint.Name, endpoint.Id, devices.OutputDevice, _calls.SelectAudioOutputAsync);
        menu.Items.Add(input);
        menu.Items.Add(output);
        menu.IsOpen = true;
    }

    private static void AddAudioOption(MenuItem group, string name, string id, string selected, Func<string, Task> select)
    {
        var item = new MenuItem { Header = new TextBlock { Text = name }, IsCheckable = true, IsChecked = id == selected };
        item.Click += async (_, _) => await RunAsync(() => select(id));
        group.Items.Add(item);
    }
    private static async Task RunAsync(Func<Task> action)
    {
        try { await action(); }
        catch (Exception error) { Trace.TraceWarning("Call operation failed: {0}", error); }
    }
    private void Minimize_Click(object sender, RoutedEventArgs e)
    {
        MinimizeCall();
    }

    private void MinimizeCall()
    {
        _minimized = true;
        DockCompactPanel(animate: false);
        UpdateActions();
        if (IsKeyboardFocusWithin || Keyboard.FocusedElement is null) CompactPanel.Focus();
    }
    private void Overlay_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (PeerDetails is not null && PeerAvatar is not null && IncomingActions is not null &&
            ActiveActions is not null && VoiceEndButton is not null)
        {
            var compact = ActualHeight < 480;
            PeerDetails.Margin = new Thickness(12, compact ? 46 : 76, 12, 0);
            PeerAvatar.Width = PeerAvatar.Height = compact ? 64 : 104;
            IncomingActions.Margin = ActiveActions.Margin = new Thickness(0, 0, 0, compact ? 12 : 28);
            VoiceEndButton.Margin = new Thickness(0, compact ? 12 : 22, 0, 0);
        }
        if (CompactPosition is null || !_minimized) return;
        if (_dragging) Clamp();
        else DockCompactPanel(animate: false);
    }
    private double CompactMaxX => Math.Max(0, ActualWidth - CompactPanel.Width);
    private double CompactMaxY => Math.Max(0, ActualHeight - CompactPanel.Height);

    private void Clamp()
    {
        CompactPosition.X = Math.Clamp(CompactPosition.X, 0, CompactMaxX);
        CompactPosition.Y = Math.Clamp(CompactPosition.Y, 0, CompactMaxY);
    }

    private void StopCompactMotion()
    {
        var x = CompactPosition.X;
        var y = CompactPosition.Y;
        CompactPosition.BeginAnimation(TranslateTransform.XProperty, null);
        CompactPosition.BeginAnimation(TranslateTransform.YProperty, null);
        CompactPosition.X = x;
        CompactPosition.Y = y;
    }

    private void DockCompactPanel(bool animate)
    {
        StopCompactMotion();
        var startX = CompactPosition.X;
        var startY = CompactPosition.Y;
        var insetX = Math.Min(12, CompactMaxX / 2);
        var insetY = Math.Min(12, CompactMaxY / 2);
        CompactPosition.X = _dockRight ? CompactMaxX - insetX : insetX;
        CompactPosition.Y = _dockBottom ? CompactMaxY - insetY : insetY;
        if (!animate || !SystemParameters.ClientAreaAnimation) return;
        DoubleAnimation Move(double start, double end) => new(start, end, new Duration(TimeSpan.FromMilliseconds(350)))
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
            FillBehavior = FillBehavior.Stop
        };
        CompactPosition.BeginAnimation(TranslateTransform.XProperty, Move(startX, CompactPosition.X));
        CompactPosition.BeginAnimation(TranslateTransform.YProperty, Move(startY, CompactPosition.Y));
    }
    private void Compact_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        StopCompactMotion();
        _dragStart = e.GetPosition(this); _startOffset = new Point(CompactPosition.X, CompactPosition.Y); _dragging = true; _moved = false;
        CompactPanel.Focus();
        _dragging = CompactPanel.CaptureMouse();
        e.Handled = _dragging;
    }
    private void Compact_MouseMove(object sender, MouseEventArgs e)
    {
        if (!_dragging) return;
        var delta = e.GetPosition(this) - _dragStart;
        if (!_moved && delta.Length < 4) return;
        _moved = true; CompactPosition.X = _startOffset.X + delta.X; CompactPosition.Y = _startOffset.Y + delta.Y; Clamp();
    }
    private void Compact_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (!_dragging) return;
        _dragging = false; CompactPanel.ReleaseMouseCapture();
        if (!_moved) RestoreCompactCall();
        else
        {
            _dockRight = CompactPosition.X >= CompactMaxX / 2;
            _dockBottom = CompactPosition.Y >= CompactMaxY / 2;
            DockCompactPanel(animate: true);
        }
        e.Handled = true;
    }

    private void Compact_LostMouseCapture(object sender, MouseEventArgs e)
    {
        if (!_dragging) return;
        _dragging = false;
        if (_minimized) DockCompactPanel(animate: true);
    }

    private void Compact_Click(object sender, RoutedEventArgs e) => RestoreCompactCall();

    private void RestoreCompactCall()
    {
        if (!_minimized) return;
        _minimized = false;
        UpdateActions();
        MinimizeButton.Focus();
    }
}
