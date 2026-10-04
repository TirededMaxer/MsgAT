using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Diagnostics;
using MsgAT.Windows.Calls;
using MsgAT.Windows.Core.Models;
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
    public CallOverlay(CallService calls)
    {
        InitializeComponent(); _calls = calls; DataContext = calls;
        _calls.PropertyChanged += Calls_PropertyChanged;
        UpdateActions();
    }
    public event EventHandler? CoverageChanged;
    public bool CoversChat => _calls.State != CallState.Idle && !_minimized;
    public void Dispose() { _calls.PropertyChanged -= Calls_PropertyChanged; GC.SuppressFinalize(this); }
    private void Calls_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(CallService.State)) Dispatcher.BeginInvoke(UpdateActions);
    }
    private void UpdateActions()
    {
        var visible = _calls.State != CallState.Idle;
        Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
        IsHitTestVisible = visible;
        if (_calls.State == CallState.Idle || _calls.State == CallState.IncomingRinging) _minimized = false;
        FullPanel.Visibility = _minimized ? Visibility.Collapsed : Visibility.Visible;
        CompactPanel.Visibility = _minimized ? Visibility.Visible : Visibility.Collapsed;
        IncomingActions.Visibility = _calls.IsIncoming ? Visibility.Visible : Visibility.Collapsed;
        ActiveActions.Visibility = _calls.IsIncoming ? Visibility.Collapsed : Visibility.Visible;
        MinimizeButton.Visibility = _calls.IsIncoming ? Visibility.Collapsed : Visibility.Visible;
        Quality.Visibility = _calls.State == CallState.Connected ? Visibility.Visible : Visibility.Collapsed;
        CoverageChanged?.Invoke(this, EventArgs.Empty);
    }
    private async void Accept_Click(object sender, RoutedEventArgs e) => await RunAsync(_calls.AcceptAsync);
    private async void Reject_Click(object sender, RoutedEventArgs e) => await RunAsync(_calls.RejectAsync);
    private async void End_Click(object sender, RoutedEventArgs e) => await RunAsync(_calls.EndAsync);
    private async void Mute_Click(object sender, RoutedEventArgs e) => await RunAsync(_calls.ToggleMuteAsync);
    private async void Speaker_Click(object sender, RoutedEventArgs e) => await RunAsync(_calls.ToggleSpeakerAsync);
    private static async Task RunAsync(Func<Task> action)
    {
        try { await action(); }
        catch (Exception error) { Trace.TraceWarning("Call operation failed: {0}", error); }
    }
    private void Minimize_Click(object sender, RoutedEventArgs e)
    {
        _minimized = true; CompactPosition.X = Math.Max(0, ActualWidth - 80); CompactPosition.Y = Math.Min(100, Math.Max(0, ActualHeight - 80)); UpdateActions();
    }
    private void Overlay_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (PeerDetails is not null) PeerDetails.Margin = new Thickness(0, Math.Min(100, Math.Max(24, (ActualHeight - 440) / 2)), 0, 0);
        if (CompactPosition is not null) Clamp();
    }
    private void Clamp()
    {
        CompactPosition.X = Math.Clamp(CompactPosition.X, 0, Math.Max(0, ActualWidth - 80));
        CompactPosition.Y = Math.Clamp(CompactPosition.Y, 0, Math.Max(0, ActualHeight - 80));
    }
    private void Compact_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        _dragStart = e.GetPosition(this); _startOffset = new Point(CompactPosition.X, CompactPosition.Y); _dragging = true; _moved = false;
        CompactPanel.CaptureMouse(); e.Handled = true;
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
        if (!_moved) { _minimized = false; UpdateActions(); }
        else { CompactPosition.X = CompactPosition.X + 40 > ActualWidth / 2 ? Math.Max(0, ActualWidth - 80) : 0; Clamp(); }
        e.Handled = true;
    }
}
