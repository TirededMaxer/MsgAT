// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using MsgAT.Windows.Calls;
using MsgAT.Windows.Core.Models;

namespace MsgAT.Windows;

public partial class MainWindow
{
    private bool TryPresentQtCall(CallService calls, bool activate = false)
    {
        if (_appLocked || _exiting || _qtWorkspace is not { IsOpen: true } workspace || calls.CurrentCallId is not { } id)
            return false;
        var modal = Application.Current.Windows.OfType<Window>().FirstOrDefault(window => window.IsActive && window.IsVisible &&
            (IsQtWorkspaceDialog(window) || ReferenceEquals(window, _recordingDialog)));
        if (!activate && calls.IsIncoming && !workspace.IsForeground && !IsActive && modal is null)
        {
            ((App)Application.Current).ShowIncomingCallNotification(id, calls.IsVideoCall, () => calls.IncomingCallId == id);
            return true;
        }

        CloseModalCallWindow();
        ParkLegacyCallSurface();
        var anchor = modal is not null ? new WindowInteropHelper(modal).Handle :
            IsActive && IsVisible ? new WindowInteropHelper(this).Handle : workspace.HostWindow;
        var focus = activate || calls.State == CallState.OutgoingRinging && modal is null && !workspace.HasCallPresentation;
        return workspace.ShowCall(calls, WebRtcView, WebRtcParkingHost, anchor, focus, compact: modal is not null);
    }

    private void ParkLegacyCallSurface()
    {
        if (_callWindow is not { } previous) return;
        _callWindow = null;
        previous.Dispose();
        if (WebRtcView.Parent is Panel parent && !ReferenceEquals(parent, WebRtcParkingHost))
        {
            parent.Children.Remove(WebRtcView);
            WebRtcParkingHost.Children.Add(WebRtcView);
        }
        CallOverlayHost.Content = null;
        UpdateChatVisibility();
    }
}
