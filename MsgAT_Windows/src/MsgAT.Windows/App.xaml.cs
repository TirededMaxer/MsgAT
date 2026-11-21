using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Drawing;
using System.Threading;
using System.Windows;
using MsgAT.Windows.Core.Network;
using Forms = System.Windows.Forms;

namespace MsgAT.Windows;

[SuppressMessage("Design", "CA1001:Types that own disposable fields should be disposable", Justification = "WPF Application owns and disposes process-lifetime resources in OnExit.")]
public partial class App : System.Windows.Application
{
    private Mutex? _singleInstance;
    private bool _ownsSingleInstance;
    private Infrastructure.NativeTrayIcon? _trayIcon;
    private MainWindow? _mainWindow;
    private bool _exiting;
    private string? _restartExecutable;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        _singleInstance = new Mutex(initiallyOwned: true, MsgAT.Windows.Core.Storage.RuntimePaths.SingleInstanceName, out var createdNew);
        _ownsSingleInstance = createdNew;
        if (!createdNew)
        {
            MessageBox.Show("MsgAT is already running.", "MsgAT", MessageBoxButton.OK, MessageBoxImage.Information);
            Shutdown();
            return;
        }

        try
        {
            Infrastructure.AppStrings.SetLanguage(Infrastructure.AppLanguage.System);
            Presentation.DesktopXamlRuntime.Start();
            Infrastructure.WindowAppearance.StartObserving();
            _mainWindow = new MainWindow();
#if DEBUG
            _mainWindow.UseQtDevelopmentWorkspace = e.Args.Contains("--qt-workspace", StringComparer.Ordinal);
#endif
            MainWindow = _mainWindow;
            ConfigureTray();
            _mainWindow.Show();
        }
        catch (Exception exception)
        {
            Trace.TraceError("Application startup failed: {0}", exception);
            var reason = string.IsNullOrWhiteSpace(exception.Message)
                ? Infrastructure.AppStrings.Get("UiStartupServicesUnavailable") : exception.Message;
            MessageBox.Show($"{reason}{Environment.NewLine}{exception.GetType().Name} (0x{exception.HResult:X8})",
                Infrastructure.AppStrings.Get("UiAppStartError"), MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        Infrastructure.WindowAppearance.StopObserving();
        _trayIcon?.Dispose();
        Presentation.DesktopXamlRuntime.Stop();
        base.OnExit(e);
        if (_ownsSingleInstance) _singleInstance?.ReleaseMutex();
        _singleInstance?.Dispose();
        if (_restartExecutable is { } executable)
        {
            try { Process.Start(new ProcessStartInfo(executable) { UseShellExecute = true }); }
            catch (Exception exception) { Trace.TraceError("Restart failed: {0}", exception); }
        }
    }

    internal void ShowMessageNotification(NotificationRequest notification, Func<MessageAlertSettings> getSettings,
        Func<bool> canPresent)
    {
        var settings = getSettings();
        bool CanNotify() => !_exiting && _mainWindow is { } window &&
            (!window.IsWorkspaceActive || window.IsAppLocked) && getSettings().Enabled;
        if (!CanNotify()) return;
        _trayIcon?.ShowNotification(Infrastructure.AppStrings.Get(notification.Pinned ? "UiMessagePinned" : "UiNewMessage"),
            notification.Silent || !settings.PlaySound,
            () => CanNotify() && canPresent(),
            () => Dispatcher.BeginInvoke(() =>
            {
                if (_exiting || _mainWindow is null || !getSettings().Enabled) return;
                ShowMainWindow();
                _mainWindow.OpenNotificationMessage(notification.SenderUuid, notification.MessageId, notification.Pinned);
            }), peer: notification.SenderUuid);
    }

    internal void ShowIncomingCallNotification(string callId, bool video, Func<bool> isStillRinging)
    {
        if (_exiting) return;
        _trayIcon?.ShowNotification(Infrastructure.AppStrings.Get(video ? "UiIncomingVideoCall" : "UiIncomingVoiceCall"), true,
            () => !_exiting && _mainWindow is { } window && (!window.IsWorkspaceActive || window.IsAppLocked) && isStillRinging(),
            () => Dispatcher.BeginInvoke(() =>
            {
                if (_exiting || !isStillRinging()) return;
                ShowMainWindow();
                _mainWindow?.FocusIncomingCall(callId);
            }), isCall: true);
    }

    internal void DismissIncomingCallNotification() => _trayIcon?.DismissCallNotification();
    internal void DismissMessageNotifications(string? peer = null) => _trayIcon?.DismissMessageNotifications(peer);
    internal bool HasTrayIcon => _trayIcon?.IsRegistered == true;

    public void RestartApplication(string executable)
    {
        _restartExecutable = executable;
        ExitApplication();
    }

    public async void ExitApplication()
    {
        if (_exiting) { return; }
        _exiting = true;
        if (_trayIcon is not null) { _trayIcon.Visible = false; }
        try
        {
            if (_mainWindow is not null) { await _mainWindow.PrepareForExitAsync(); }
        }
        catch (Exception exception)
        {
            Trace.TraceError("Application cleanup failed: {0}", exception);
        }
        finally
        {
            _mainWindow?.AllowClose();
            _mainWindow?.StopQtWorkspace();
            Shutdown();
        }
    }

    private void ConfigureTray()
    {
        var menu = new Forms.ContextMenuStrip();
        var open = menu.Items.Add(Infrastructure.AppStrings.Get("UiOpen"), null, (_, _) => Dispatcher.BeginInvoke(ShowMainWindow));
        var exit = menu.Items.Add(Infrastructure.AppStrings.Get("UiExit"), null, (_, _) => Dispatcher.BeginInvoke(ExitApplication));
        menu.Opening += (_, _) =>
        {
            open.Text = Infrastructure.AppStrings.Get("UiOpen");
            exit.Text = Infrastructure.AppStrings.Get("UiExit");
        };
        using var executableIcon=Environment.ProcessPath is { } path ? Icon.ExtractAssociatedIcon(path) : null;
        _trayIcon=new Infrastructure.NativeTrayIcon(executableIcon??SystemIcons.Application,menu){Visible=true};
        _trayIcon.Activated+=(_,_)=>Dispatcher.BeginInvoke(ShowMainWindow);
    }

    private void ShowMainWindow()
    {
        if (_exiting || _mainWindow is null) { return; }
        if (_mainWindow.TryShowQtWorkspace()) return;
        _mainWindow.Show();
        if (_mainWindow.WindowState == WindowState.Minimized)
            _mainWindow.WindowState = WindowState.Normal;
        _mainWindow.Activate();
    }
}
