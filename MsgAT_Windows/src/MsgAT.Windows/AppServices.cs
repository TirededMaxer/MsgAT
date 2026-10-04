using System.Windows;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Wpf;
using MsgAT.Windows.Calls;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows;

public sealed class AppServices : IAsyncDisposable
{
    private readonly string _dataDirectory;
    private Task? _disposeTask;
    public bool IsDisposed => _disposeTask is not null;

    private AppServices(
        string dataDirectory,
        SecureSettingsStore settings,
        EncryptedDatabase database,
        MediaStore media,
        RelayClient relay,
        MessagingService messaging,
        WebRtcEngine webRtc,
        CallService calls,
        PacketRouter router)
    {
        _dataDirectory = dataDirectory;
        Settings = settings;
        Database = database;
        Media = media;
        Relay = relay;
        Messaging = messaging;
        WebRtc = webRtc;
        Calls = calls;
        Router = router;
    }

    public SecureSettingsStore Settings { get; }
    public EncryptedDatabase Database { get; }
    public MediaStore Media { get; }
    public RelayClient Relay { get; }
    public MessagingService Messaging { get; }
    public WebRtcEngine WebRtc { get; }
    public CallService Calls { get; }
    public PacketRouter Router { get; }

    public static async Task<AppServices> CreateAsync(Dispatcher dispatcher, WebView2 webView)
    {
        var localData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var dataDirectory = Path.Combine(localData, "MsgAT");
        Directory.CreateDirectory(dataDirectory);
        var settings = new SecureSettingsStore(dataDirectory);
        var database = new EncryptedDatabase(Path.Combine(dataDirectory, "msgat_vault.db"), settings.DatabaseKey);
        var media = new MediaStore(dataDirectory);
        if (!string.IsNullOrWhiteSpace(settings.Snapshot.AvatarPath) && File.Exists(settings.Snapshot.AvatarPath))
        {
            var normalizedAvatar = media.SaveAvatar(settings.MyUuid, AvatarProcessor.EncodeJpeg(settings.Snapshot.AvatarPath));
            if (!string.Equals(normalizedAvatar, settings.Snapshot.AvatarPath, StringComparison.OrdinalIgnoreCase))
            {
                settings.Update(current => current with { AvatarPath = normalizedAvatar });
            }
        }
        var relay = new RelayClient(settings);
        var messaging = new MessagingService(relay, settings, database, media);
        var webRtc = new WebRtcEngine(webView);
        var calls = new CallService(dispatcher, webRtc, messaging, database, settings);
        var router = new PacketRouter(relay, settings, database, media, calls);
        router.NotificationRequested += (_, notification) =>
        {
            dispatcher.BeginInvoke(() =>
                ((App)System.Windows.Application.Current).ShowNotification(notification.Title, notification.Body));
        };
        await relay.StartAsync();
        return new AppServices(dataDirectory, settings, database, media, relay, messaging, webRtc, calls, router);
    }

    public async Task SaveNetworkSettingsAsync(SecureSettings updated)
    {
        Settings.Update(current => current with
        {
            ServerAddress = updated.ServerAddress,
            ServerPort = updated.ServerPort,
            TurnServer = updated.TurnServer,
            TurnPort = updated.TurnPort,
            TurnUsername = updated.TurnUsername,
            TurnPassword = updated.TurnPassword,
            TurnUseTls = updated.TurnUseTls,
            ServerCertificateSha256 = updated.ServerCertificateSha256,
            AllowDevelopmentCertificate = updated.AllowDevelopmentCertificate
        });
        await Relay.ReconnectAsync();
    }

    public async Task ResetIdentityAndRestartAsync()
    {
        var executable = Environment.ProcessPath;
        if (string.IsNullOrWhiteSpace(executable))
        {
            throw new InvalidOperationException("The application executable path is unavailable.");
        }
        if (!await Messaging.PrepareIdentityResetAsync())
        {
            throw new InvalidOperationException("Must be online to reset");
        }

        await DisposeAsync();
        DeleteIfExists(Path.Combine(_dataDirectory, "msgat_vault.db"));
        DeleteIfExists(Path.Combine(_dataDirectory, "msgat_vault.db-wal"));
        DeleteIfExists(Path.Combine(_dataDirectory, "msgat_vault.db-shm"));
        Media.DeleteAll();
        Settings.RotateIdentity();

        ((App)System.Windows.Application.Current).RestartApplication(executable);
    }

    public ValueTask DisposeAsync() => new(_disposeTask ??= DisposeCoreAsync());

    private async Task DisposeCoreAsync()
    {
        Router.Dispose();
        Calls.Dispose();
        Messaging.Dispose();
        try { await Relay.DisposeAsync(); }
        finally { Database.Dispose(); }
    }

    private static void DeleteIfExists(string path)
    {
        try { File.Delete(path); }
        catch (IOException exception) { throw new IOException($"Unable to remove identity data: {path}", exception); }
    }
}
