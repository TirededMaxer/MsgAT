// SPDX-License-Identifier: AGPL-3.0-only
using Microsoft.Win32;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.Views;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Threading;
using global::Windows.Security.Credentials.UI;

namespace MsgAT.Windows;

public partial class MainWindow
{
    private readonly DispatcherTimer _appLockTimer = new() { Interval = TimeSpan.FromMinutes(1) };
    private bool _appLockLoaded;
    private bool _appLockEnabled;
    private bool _appLocked = true;
    private bool _authenticating;
    private long _appLockGeneration;
    private long? _awaySince;
    private (string Peer, string Id, bool Pinned)? _deferredNotification;
    private long _notificationNavigationRevision;
    internal bool IsAppLocked => _appLocked;

    private void AttachAppLock()
    {
        _appLockTimer.Tick += (_, _) =>
        {
            _appLockTimer.Stop();
            LockApp();
        };
        SystemEvents.SessionSwitch += AppLock_SessionSwitch;
        SystemEvents.PowerModeChanged += AppLock_PowerModeChanged;
        Closed += (_, _) =>
        {
            _appLockTimer.Stop();
            SystemEvents.SessionSwitch -= AppLock_SessionSwitch;
            SystemEvents.PowerModeChanged -= AppLock_PowerModeChanged;
        };
    }

    private void AppLock_SessionSwitch(object sender, SessionSwitchEventArgs args)
    {
        if (args.Reason == SessionSwitchReason.SessionLock && !Dispatcher.HasShutdownStarted)
            Dispatcher.BeginInvoke(DispatcherPriority.Send, new Action(LockApp));
    }

    private void AppLock_PowerModeChanged(object sender, PowerModeChangedEventArgs args)
    {
        if (args.Mode == PowerModes.Suspend && !Dispatcher.HasShutdownStarted)
            Dispatcher.BeginInvoke(DispatcherPriority.Send, new Action(LockApp));
    }

    private void UpdateAppLockPresence(bool present)
    {
        if (present)
        {
            var lockOnReturn = _awaySince is { } since && Environment.TickCount64 - since >= 60_000;
            _awaySince = null;
            _appLockTimer.Stop();
            if (lockOnReturn && _appLockEnabled) LockApp();
        }
        else if (_appLockEnabled && !_appLocked && !_appLockTimer.IsEnabled)
        {
            _awaySince = Environment.TickCount64;
            _appLockTimer.Start();
        }
    }

    private void LockApp()
    {
        ++_appLockGeneration;
        if (!_appLockEnabled || _appLocked || _exiting) return;
        _appLocked = true;
        CloseModalCallWindow();
        ClearArchiveUndo();
        _focusBeforeCallOverlay = null;
        _peerBeforeCallOverlay = null;
        CloseStickerPanel(immediate: true);
        SetDropRegion(null, immediate: true);
        if (_viewModel is { } model) model.ShowAttachments = false;
        CloseReplyQuotePicker();
        CloseInlineAudio();
        DiscardInterruptedRecording();
        ClearJumpHighlight();
        ClearFloatingTimelineDate();
        _previousSearches.Clear();
        _searchOrder.Clear();
        _searchReturn = null;
        UpdateReturnToSearchButton();
        _viewModel?.ClearHistoryPositions();
        _appLockTimer.Stop();
        AppLockError.Text = "";
        ApplyAppLockVisibility();
        foreach (var window in OwnedWindows.Cast<Window>().ToArray())
        {
            window.Close();
            if (window.IsVisible) window.Hide();
        }
        CloseChatMedia();
        _services?.Messaging.SetActiveChat(null);
        _reportedActivityPeer = null;
    }

    private void ApplyAppLockVisibility()
    {
        if (_exiting)
        {
            LayoutGrid.Visibility = Visibility.Collapsed;
            AppLockPane.Visibility = Visibility.Visible;
            AppLockTitle.SetResourceReference(System.Windows.Controls.TextBlock.TextProperty, "UiExitingApp");
            AppOpeningProgress.Visibility = Visibility.Visible;
            AppUnlockButton.Visibility = Visibility.Collapsed;
            AppLockError.Text = "";
            AllowDrop = false;
            return;
        }
        LayoutGrid.Visibility = _appLocked ? Visibility.Collapsed : Visibility.Visible;
        AppLockPane.Visibility = _appLocked ? Visibility.Visible : Visibility.Collapsed;
        AppLockTitle.SetResourceReference(System.Windows.Controls.TextBlock.TextProperty, _appLockLoaded ? "UiAppLocked" : "UiOpeningApp");
        AppOpeningProgress.Visibility = _appLockLoaded ? Visibility.Collapsed : Visibility.Visible;
        AppUnlockButton.Visibility = _appLockLoaded ? Visibility.Visible : Visibility.Collapsed;
        AppUnlockButton.IsEnabled = !_authenticating && !_exiting;
        AllowDrop = !_appLocked;
        if (_appLocked)
        {
            MsgAT.Windows.Infrastructure.AttachmentExporter.CancelActive();
            _readVisibilityTimer.Stop();
            if (IsActive) AppUnlockButton.Focus();
        }
        else if (IsActive)
        {
            QueueVisibleReads();
        }
        UpdateActivityForeground();
        UpdateChatActivity();
    }

    private async void AppUnlock_Click(object sender, RoutedEventArgs e) => await AuthenticateAppLockAsync();

    private async Task AuthenticateAppLockAsync(bool? enable = null)
    {
        if (!_appLockLoaded || _authenticating || _exiting || !IsActive || _services is not { } services) return;
        if (enable is not null && _appLocked) return;
        if (!OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000))
        {
            AppLockFeedback(AppStrings.Get("UiWindowsHelloRequired"));
            return;
        }
        var generation = _appLockGeneration;
        _authenticating = true;
        AppUnlockButton.IsEnabled = false;
        AppLockError.Text = "";
        try
        {
            var availability = await UserConsentVerifier.CheckAvailabilityAsync();
            if (_exiting || generation != _appLockGeneration) return;
            if (availability != UserConsentVerifierAvailability.Available)
            {
                AppLockFeedback(AppStrings.Get("UiWindowsHelloSetup"));
                return;
            }

            var hwnd = new WindowInteropHelper(this).Handle;
            var result = await UserConsentVerifierInterop.RequestVerificationForWindowAsync(hwnd, AppStrings.Get("UiVerifyApp"));
            if (_exiting || generation != _appLockGeneration) return;
            if (result != UserConsentVerificationResult.Verified)
            {
                AppLockFeedback(AppStrings.Get("UiVerifyIncomplete"));
                return;
            }
            if (enable is { } value)
            {
                if (!await services.Messaging.SetAppLockEnabledAsync(value))
                {
                    AppLockFeedback(AppStrings.Get("UiAppLockSaveError"));
                    return;
                }
                _appLockEnabled = value;
                if (value && generation != _appLockGeneration) LockApp();
                if (!value && generation == _appLockGeneration) _appLocked = false;
            }
            else
            {
                _appLocked = false;
            }

            if (_exiting) return;
            ApplyAppLockVisibility();
            if (_appLocked) return;
            InitializeUnlockedWorkspace();
            if (_appLocked || _exiting) return;
            if (services.Calls.IsIncoming) Calls_ShowRequested(services.Calls, EventArgs.Empty);
            OpenDeferredNotification();
        }
        catch (Exception error) when (error is InvalidOperationException or System.Runtime.InteropServices.COMException or OperationCanceledException)
        {
            if (!_exiting) AppLockFeedback(AppStrings.Get("UiVerifyIncomplete"));
        }
        finally
        {
            _authenticating = false;
            AppUnlockButton.IsEnabled = !_exiting;
        }
    }

    private void OpenDeferredNotification()
    {
        if (_appLocked || _callWindow?.CoversChat == true || _viewModel is not { IsWorkspaceReady: true } ||
            _deferredNotification is not { } target) return;
        _deferredNotification = null;
        OpenNotificationMessage(target.Peer, target.Id, target.Pinned);
    }

    private void AppLockFeedback(string message)
    {
        if (_appLocked) AppLockError.Text = message;
        else TransientNotice.Show(this, message);
    }
}
