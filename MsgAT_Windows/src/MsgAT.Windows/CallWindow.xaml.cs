using System.ComponentModel;
using System.Windows;
using System.Windows.Input;
using MsgAT.Windows.Calls;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows;

public partial class CallWindow : Window
{
    private readonly CallService _calls;
    private bool _minimizedOverlay;

    public CallWindow(CallService calls)
    {
        InitializeComponent();
        WindowAppearance.Apply(this);
        _calls = calls;
        DataContext = calls;
        _calls.PropertyChanged += Calls_PropertyChanged;
        UpdateActions();
    }

    private void Calls_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        _ = sender;
        if (e.PropertyName == nameof(CallService.State))
        {
            Dispatcher.BeginInvoke(() =>
            {
                UpdateActions();
                if (_calls.State == CallState.Idle) { Hide(); }
            });
        }
    }

    private void UpdateActions()
    {
        IncomingActions.Visibility = _calls.State == CallState.IncomingRinging ? Visibility.Visible : Visibility.Collapsed;
        ActiveActions.Visibility = _calls.State == CallState.IncomingRinging ? Visibility.Collapsed : Visibility.Visible;
    }

    private async void Accept_Click(object sender, RoutedEventArgs e) { _ = sender; _ = e; await RunCallActionAsync(_calls.AcceptAsync); }
    private async void Reject_Click(object sender, RoutedEventArgs e) { _ = sender; _ = e; await RunCallActionAsync(_calls.RejectAsync); }
    private async void End_Click(object sender, RoutedEventArgs e) { _ = sender; _ = e; await RunCallActionAsync(_calls.EndAsync); }
    private async void Mute_Click(object sender, RoutedEventArgs e) { _ = sender; _ = e; await RunCallActionAsync(_calls.ToggleMuteAsync); }

    private async Task RunCallActionAsync(Func<Task> action)
    {
        try { await action(); }
        catch (Exception error) { MessageBox.Show(this, error.Message, "MsgAT", MessageBoxButton.OK, MessageBoxImage.Warning); }
    }

    private void Minimize_Click(object sender, RoutedEventArgs e)
    {
        _ = sender;
        _ = e;
        _minimizedOverlay = !_minimizedOverlay;
        Width = _minimizedOverlay ? 220 : 420;
        Height = _minimizedOverlay ? 140 : 500;
        FullPanel.Visibility = _minimizedOverlay ? Visibility.Collapsed : Visibility.Visible;
        CompactPanel.Visibility = _minimizedOverlay ? Visibility.Visible : Visibility.Collapsed;
        Topmost = _minimizedOverlay;
        ClampToDesktop();
    }

    private void Window_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        _ = sender;
        if (e.ClickCount == 2 && _minimizedOverlay) { Minimize_Click(this, e); return; }
        if (_minimizedOverlay && e.ButtonState == MouseButtonState.Pressed) { DragMove(); ClampToDesktop(); }
    }

    private void ClampToDesktop()
    {
        Left = Math.Max(SystemParameters.VirtualScreenLeft, Math.Min(Left, SystemParameters.VirtualScreenLeft + SystemParameters.VirtualScreenWidth - Width));
        Top = Math.Max(SystemParameters.VirtualScreenTop, Math.Min(Top, SystemParameters.VirtualScreenTop + SystemParameters.VirtualScreenHeight - Height));
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        _ = sender;
        e.Cancel = true;
        Hide();
    }
}
