// SPDX-License-Identifier: AGPL-3.0-only
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Input;
using MsgAT.Windows.Calls;
using MsgAT.Windows.Core.Models;

namespace MsgAT.Windows.Views;

internal sealed class ModalCallWindow : DialogWindow
{
    private readonly CallService _calls;
    private readonly Func<bool> _canInteract;
    private readonly Button _answer = new();
    private readonly Button _reject = new();
    private readonly Button _mute = new();
    private readonly Button _end = new();
    private bool _performing;
    private bool _closed;

    internal ModalCallWindow(Window owner, CallService calls, string callId, Func<bool> canInteract)
    {
        Owner = owner;
        Title = "MsgAT";
        Width = 350;
        SizeToContent = SizeToContent.Height;
        SetResourceReference(BackgroundProperty, "MessagingChromeBrush");
        _calls = calls;
        CallId = callId;
        _canInteract = canInteract;

        var peer = new TextBlock { FontSize = 17, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis };
        peer.SetBinding(TextBlock.TextProperty, new Binding(nameof(CallService.PeerAlias)) { Source = calls });
        var status = new TextBlock { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 3, 0, 0) };
        status.SetBinding(TextBlock.TextProperty, new Binding(nameof(CallService.StatusText)) { Source = calls });
        status.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        var identity = new DockPanel();
        var avatar = IdentitySetupDialog.Avatar(calls.PeerAvatarPath, calls.PeerAlias, 42, 21);
        avatar.Margin = new Thickness(0, 0, 10, 0);
        avatar.VerticalAlignment = VerticalAlignment.Top;
        DockPanel.SetDock(avatar, Dock.Left);
        identity.Children.Add(avatar);
        identity.Children.Add(new StackPanel
        {
            VerticalAlignment = VerticalAlignment.Center,
            Children = { peer, status }
        });
        var divider = new Border { Height = 1, Margin = new Thickness(0, 10, 0, 8) };
        divider.SetResourceReference(Border.BackgroundProperty, "MessagingDividerBrush");

        _answer.SetResourceReference(ContentControl.ContentProperty, "UiAcceptCall");
        _answer.Style = ResourceStyle("PrimaryButtonStyle");
        _reject.SetResourceReference(ContentControl.ContentProperty, "UiRejectCall");
        _end.SetResourceReference(ContentControl.ContentProperty, "UiEndCall");
        _end.Style = ResourceStyle("DangerButtonStyle");
        _mute.SetBinding(ContentControl.ContentProperty, new Binding(nameof(CallService.MuteText)) { Source = calls });
        _answer.Click += async (_, _) => await RunAsync(() => calls.IsIncoming, calls.AcceptAsync);
        _reject.Click += async (_, _) => await RunAsync(() => calls.IsIncoming, calls.RejectAsync);
        _mute.Click += async (_, _) => await RunAsync(() => calls.State == CallState.Connected, calls.ToggleMuteAsync);
        _end.Click += async (_, _) => await RunAsync(() => !calls.IsIncoming, calls.EndAsync);

        var buttons = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        foreach (var button in new[] { _reject, _answer, _mute, _end })
        {
            button.Margin = new Thickness(6, 4, 0, 0);
            buttons.Children.Add(button);
        }

        var panel = new StackPanel { Margin = new Thickness(14), Children = { identity, divider, buttons } };
        KeyboardNavigation.SetTabNavigation(panel, KeyboardNavigationMode.Cycle);
        Content = panel;
        _calls.PropertyChanged += CallChanged;
        Closed += (_, _) => { _closed = true; _calls.PropertyChanged -= CallChanged; };
        UpdateActions();
    }

    internal string CallId { get; }

    internal void FocusIncomingControls()
    {
        if (_calls.IsIncoming && _calls.CurrentCallId == CallId) _reject.Focus();
    }

    private void CallChanged(object? sender, PropertyChangedEventArgs args)
    {
        if (args.PropertyName != nameof(CallService.State)) return;
        if (Dispatcher.CheckAccess()) UpdateActions();
        else _ = Dispatcher.InvokeAsync(UpdateActions);
    }

    private void UpdateActions()
    {
        if (_closed) return;
        var ringing = _calls.IsIncoming;
        _answer.Visibility = _reject.Visibility = ringing ? Visibility.Visible : Visibility.Collapsed;
        _end.Visibility = _mute.Visibility = ringing ? Visibility.Collapsed : Visibility.Visible;
        _mute.IsEnabled = !_performing && _calls.State == CallState.Connected;
        _answer.IsEnabled = _reject.IsEnabled = _end.IsEnabled = !_performing;
    }

    private async Task RunAsync(Func<bool> allowed, Func<Task> action)
    {
        if (_closed || _performing || !_canInteract() || _calls.CurrentCallId != CallId ||
            _calls.State is CallState.Idle or CallState.Ended || !allowed()) return;
        _performing = true;
        UpdateActions();
        try { await action(); }
        catch (Exception error) { System.Diagnostics.Trace.TraceWarning("Call operation failed ({0})", error.GetType().Name); }
        finally { _performing = false; UpdateActions(); }
    }
}
