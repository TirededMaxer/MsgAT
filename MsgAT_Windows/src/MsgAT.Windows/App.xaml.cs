using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Drawing;
using System.Threading;
using System.Windows;
using Forms = System.Windows.Forms;

namespace MsgAT.Windows;

[SuppressMessage("Design", "CA1001:Types that own disposable fields should be disposable", Justification = "WPF Application owns and disposes process-lifetime resources in OnExit.")]
public partial class App : System.Windows.Application
{
    private Mutex? _singleInstance;
    private Forms.NotifyIcon? _trayIcon;
    private MainWindow? _mainWindow;
    private bool _exiting;
    private string? _restartExecutable;

    protected override void OnStartup(StartupEventArgs e)
    {
        System.Windows.Media.RenderOptions.ProcessRenderMode = System.Windows.Interop.RenderMode.SoftwareOnly;
        base.OnStartup(e);
        _singleInstance = new Mutex(initiallyOwned: true, "Local\\MsgAT.Windows.Singleton", out var createdNew);
        if (!createdNew)
        {
            MessageBox.Show("MsgAT is already running.", "MsgAT", MessageBoxButton.OK, MessageBoxImage.Information);
            Shutdown();
            return;
        }

        try
        {
            _mainWindow = new MainWindow();
            MainWindow = _mainWindow;
            ConfigureTray();
            _mainWindow.Show();
        }
        catch (Exception exception)
        {
            Trace.TraceError("Application startup failed: {0}", exception);
            MessageBox.Show(exception.Message, "MsgAT could not start", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _trayIcon?.Dispose();
        _singleInstance?.Dispose();
        base.OnExit(e);
        if (_restartExecutable is { } executable)
        {
            try { Process.Start(new ProcessStartInfo(executable) { UseShellExecute = true }); }
            catch (Exception exception) { Trace.TraceError("Restart failed: {0}", exception); }
        }
    }

    public void ShowNotification(string title, string message)
    {
        if (_mainWindow?.IsActive == true) { return; }
        _trayIcon?.ShowBalloonTip(4000, title, message, Forms.ToolTipIcon.Info);
    }

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
            Shutdown();
        }
    }

    private void ConfigureTray()
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Open", null, (_, _) => Dispatcher.Invoke(ShowMainWindow));
        menu.Items.Add("Exit", null, (_, _) => Dispatcher.Invoke(ExitApplication));
        var executableIcon = Environment.ProcessPath is { } path ? Icon.ExtractAssociatedIcon(path) : null;
        _trayIcon = new Forms.NotifyIcon
        {
            Icon = executableIcon ?? SystemIcons.Application,
            Text = "MsgAT",
            Visible = true,
            ContextMenuStrip = menu
        };
        _trayIcon.DoubleClick += (_, _) => Dispatcher.Invoke(ShowMainWindow);
        _trayIcon.BalloonTipClicked += (_, _) => Dispatcher.Invoke(ShowMainWindow);
    }

    private void ShowMainWindow()
    {
        if (_exiting || _mainWindow is null) { return; }
        _mainWindow.Show();
        _mainWindow.WindowState = WindowState.Normal;
        _mainWindow.Activate();
    }
}
