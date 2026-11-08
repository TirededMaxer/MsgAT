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
        CallService calls)
    {
        _dataDirectory = dataDirectory;
        Settings = settings;
        Database = database;
        Media = media;
        Relay = relay;
        Messaging = messaging;
        WebRtc = webRtc;
        Calls = calls;
    }

    public SecureSettingsStore Settings { get; }
    public EncryptedDatabase Database { get; }
    public MediaStore Media { get; }
    public RelayClient Relay { get; }
    public MessagingService Messaging { get; }
    public WebRtcEngine WebRtc { get; }
    public CallService Calls { get; }

    public static async Task<AppServices> CreateAsync(Dispatcher dispatcher, WebView2 webView)
    {
        var dataDirectory = RuntimePaths.DataDirectory;
        Directory.CreateDirectory(dataDirectory);
        MsgAT.Windows.Core.Crypto.SecurityCore.RequireAvailable();
        var settings = new SecureSettingsStore(dataDirectory);
        var databaseKey=settings.DatabaseKey;
        EncryptedDatabase database;
        try { database=new EncryptedDatabase(Path.Combine(dataDirectory,"msgat_v2_vault.db"),databaseKey); }
        finally { System.Security.Cryptography.CryptographicOperations.ZeroMemory(databaseKey); }
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
        messaging.NotificationRequested += (_, notification) =>
        {
            dispatcher.BeginInvoke(() =>
                ((App)System.Windows.Application.Current).ShowNotification(notification.Title, notification.Body));
        };
        await relay.StartAsync();
        return new AppServices(dataDirectory, settings, database, media, relay, messaging, webRtc, calls);
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
        foreach (var name in new[] { "msgat_vault.db", "msgat_v2_vault.db" })
        {
            foreach (var suffix in new[] { "", "-wal", "-shm" })
            {
                DeleteIfExists(Path.Combine(_dataDirectory, name + suffix));
            }
        }
        Media.DeleteAll();
        Settings.RotateIdentity();

        ((App)System.Windows.Application.Current).RestartApplication(executable);
    }

    public ValueTask DisposeAsync() => new(_disposeTask ??= DisposeCoreAsync());

    private async Task DisposeCoreAsync()
    {
        Calls.Dispose();
        try
        {
            await Relay.StopAsync();
            await Messaging.DisposeAsync();
            await Relay.DisposeAsync();
        }
        finally { Database.Dispose(); }
    }

    private static void DeleteIfExists(string path)
    {
        try { File.Delete(path); }
        catch (IOException exception) { throw new IOException($"Unable to remove identity data: {path}", exception); }
    }
}
