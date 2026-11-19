using System.Windows;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Wpf;
using MsgAT.Windows.Calls;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows;

internal sealed class IdentityStartupException(Exception cause, bool canReset) : Exception(cause.Message, cause)
{
    public bool CanReset { get; } = canReset;
}

internal sealed class VaultStartupException(Exception cause) : Exception(cause.Message, cause) { }

public sealed class AppServices : IAsyncDisposable
{
    private readonly string _dataDirectory;
    private Task? _disposeTask;
    private bool _avatarDecoderConfigured;
    private readonly CancellationTokenSource _revocationsLifetime=new();
    private readonly Task _revocationsWorker;
    public bool IsDisposed => _disposeTask is not null;

    private AppServices(
        string dataDirectory,
        SecureSettingsStore settings,
        EncryptedDatabase database,
        MediaStore media,
        EncryptedObjectStore objects,
        RelayClient relay,
        MessagingService messaging,
        WebRtcEngine webRtc,
        CallService calls)
    {
        _dataDirectory = dataDirectory;
        Settings = settings;
        Database = database;
        Drafts = new ConversationDraftStore(database);
        Search = new MessageSearchStore(database);
        Previews=new WebPreviewStore(database);
        Media = media;
        Objects = objects;
        Stickers=new StickerLibraryStore(database,objects);
        Contacts=new ContactDirectoryStore(database,settings);
        ConversationIndex=new ConversationIndexStore(database);
        Relay = relay;
        Messaging = messaging;
        LiveLocations = new LiveLocationCoordinator(messaging);
        WebRtc = webRtc;
        Calls = calls;
        _revocationsWorker=Task.Run(async ()=>
        {
            try
            {
                string? lastAttempted = null;
                while(!_revocationsLifetime.IsCancellationRequested)
                {
                    try
                    {
                        lastAttempted = await IdentityLifecycle.DeliverPendingAsync(_dataDirectory, lastAttempted,
                            _revocationsLifetime.Token).ConfigureAwait(false);
                    }
                    catch(OperationCanceledException) when(_revocationsLifetime.IsCancellationRequested) { throw; }
                    catch(Exception) { /* Preserve public intents and retry on the next tick. */ }
                    await Task.Delay(TimeSpan.FromSeconds(30),_revocationsLifetime.Token).ConfigureAwait(false);
                }
            }
            catch(OperationCanceledException) when(_revocationsLifetime.IsCancellationRequested) { }
        });
    }

    public SecureSettingsStore Settings { get; }
    public EncryptedDatabase Database { get; }
    public ConversationDraftStore Drafts { get; }
    public MessageSearchStore Search { get; }
    public WebPreviewStore Previews {get;}
    public MediaStore Media { get; }
    public EncryptedObjectStore Objects { get; }
    public StickerLibraryStore Stickers {get;}
    public ContactDirectoryStore Contacts {get;}
    public ConversationIndexStore ConversationIndex {get;}
    public RelayClient Relay { get; }
    public MessagingService Messaging { get; }
    internal LiveLocationCoordinator LiveLocations { get; }
    public WebRtcEngine WebRtc { get; }
    public CallService Calls { get; }

    public static async Task<AppServices> CreateAsync(Dispatcher dispatcher, WebView2CompositionControl webView)
    {
        var dataDirectory = RuntimePaths.DataDirectory;
        Directory.CreateDirectory(dataDirectory);
        var opened=await Task.Run(() =>
        {
            MsgAT.Windows.Core.Crypto.SecurityCore.RequireAvailable();
            SecureSettingsStore identity;
            try { identity = new SecureSettingsStore(dataDirectory); }
            catch (Exception error) when (error is IOException or InvalidDataException or UnauthorizedAccessException or
                InvalidOperationException or ArgumentException or System.Security.Cryptography.CryptographicException or System.Text.Json.JsonException)
            {
                throw new IdentityStartupException(error, IdentityLifecycle.CanDiscardUnreadableIdentity(dataDirectory));
            }
            var key=identity.DatabaseKey;
            try { return (Settings:identity,Database:new EncryptedDatabase(Path.Combine(dataDirectory,"msgat_v2_vault.db"),key)); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException or
                ArgumentException or System.Security.Cryptography.CryptographicException or Microsoft.Data.Sqlite.SqliteException)
            {
                throw new VaultStartupException(error);
            }
            finally { System.Security.Cryptography.CryptographicOperations.ZeroMemory(key); }
        });
        var settings = opened.Settings;
        var database = opened.Database;
        RelayClient? relay = null;
        MessagingService? messaging = null;
        AppServices? services = null;
        try
        {
            var media = new MediaStore(dataDirectory);
            var objects = new EncryptedObjectStore(dataDirectory, settings, database);
            relay = new RelayClient(settings);
            messaging = new MessagingService(relay, settings, database, media, objects);
            var webRtc = new WebRtcEngine(webView);
            var calls = new CallService(dispatcher, webRtc, messaging, database, settings);
            var active = new AppServices(dataDirectory, settings, database, media, objects, relay, messaging, webRtc, calls);
            services = active;
            await active.MigrateLocalProfileAsync();
            AvatarConverter.Configure(objects, database, dispatcher);
            active._avatarDecoderConfigured = true;
            messaging.CallSink = calls;
            messaging.NotificationRequested += (_, notification) => dispatcher.BeginInvoke(() =>
            {
                if (active.IsDisposed || System.Windows.Application.Current is not App app) return;
                app.ShowMessageNotification(notification, () => active.Messaging.NotificationSettings, () =>
                {
                    try
                    {
                        return !active.IsDisposed && database.CanPresentMessageNotification(
                            notification.SenderUuid, notification.MessageId, notification.Pinned);
                    }
                    catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
                    {
                        return false;
                    }
                });
            });
            await relay.StartAsync();
            return active;
        }
        catch
        {
            if (services is not null)
                await StopStepAsync("startup rollback", () => services.DisposeAsync().AsTask());
            else
            {
                if (messaging is not null) await StopStepAsync("partial messaging startup", () => messaging.DisposeAsync().AsTask());
                if (relay is not null) await StopStepAsync("partial relay startup", () => relay.DisposeAsync().AsTask());
                await StopStepAsync("partially opened vault", () => Task.Run(database.Dispose));
            }
            throw;
        }
    }

    private async Task MigrateLocalProfileAsync()
    {
        var snapshot = Settings.Snapshot;
        var local = await Task.Run(() => Messaging.LocalProfile);
        if (local is null && !string.IsNullOrWhiteSpace(snapshot.Nickname))
        {
            var old = snapshot.AvatarPath;
            var bytes = await Task.Run(() => !string.IsNullOrWhiteSpace(old) && File.Exists(old) ? AvatarProcessor.EncodeJpeg(old) : null);
            try
            {
                if (!await Messaging.UpdateProfileAsync(snapshot.Nickname, bytes is null ? null : new ReadOnlyMemory<byte>(bytes)))
                    throw new IOException("Profile could not be secured.");
                if (old is not null && Path.GetDirectoryName(Path.GetFullPath(old)) == Path.GetFullPath(Media.RootDirectory) &&
                    Path.GetFileName(old).StartsWith("avatar_", StringComparison.Ordinal))
                    await Task.Run(() => File.Delete(old));
            }
            finally
            {
                if (bytes is not null) System.Security.Cryptography.CryptographicOperations.ZeroMemory(bytes);
            }
            return;
        }
        if (local is not null)
            await Task.Run(() => Settings.Update(current => current with
            {
                Nickname = local.Nickname,
                AvatarPath = local.AvatarReference ?? ""
            }));
    }

    public async Task SaveNetworkSettingsAsync(SecureSettings updated)
    {
        await Task.Run(() => Settings.Update(current => current with
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
        }));
        await Relay.ReconnectAsync();
    }

    public async Task ResetIdentityAndRestartAsync()
    {
        var executable = Environment.ProcessPath;
        if (string.IsNullOrWhiteSpace(executable))
        {
            throw new InvalidOperationException("The application executable path is unavailable.");
        }
        // The durable marker is the commit. The normal exit path drains services,
        // and the next process performs erasure before opening the vault.
        await Task.Run(() => IdentityLifecycle.PrepareReset(_dataDirectory, Settings));
        ((App)System.Windows.Application.Current).RestartApplication(executable);
    }

    public ValueTask DisposeAsync() => new(_disposeTask ??= DisposeCoreAsync());

    private async Task DisposeCoreAsync()
    {
        var avatars = _avatarDecoderConfigured ? AvatarConverter.ClearAsync() : Task.CompletedTask;
        var thumbnails = Objects.CloseThumbnailWorkAsync();
        await StopStepAsync("revocation delivery", async () =>
        {
            try
            {
                try { await _revocationsLifetime.CancelAsync(); }
                finally { await _revocationsWorker; }
            }
            finally { _revocationsLifetime.Dispose(); }
        });
        await StopStepAsync("live location", LiveLocations.CloseAsync);
        await StopStepAsync("call media", async () =>
        {
            Calls.Dispose();
            await Calls.CloseMediaAsync();
        });
        await StopStepAsync("relay connection", () => Task.Run(Relay.StopAsync));
        await StopStepAsync("messaging and transfers", () => Messaging.DisposeAsync().AsTask());
        await StopStepAsync("relay resources", () => Relay.DisposeAsync().AsTask());
        await StopStepAsync("recordings", () => Objects.Captures.DisposeAsync().AsTask());
        await StopStepAsync("avatars", () => avatars);
        await StopStepAsync("thumbnails", () => thumbnails);
        await StopStepAsync("conversation changes", () => ConversationIndex.DisposeAsync().AsTask());
        await StopStepAsync("contact directory", () => Contacts.DisposeAsync().AsTask());
        await StopStepAsync("sticker library", () => Stickers.DisposeAsync().AsTask());
        await StopStepAsync("drafts", () => Drafts.DisposeAsync().AsTask());
        await Task.Run(Database.Dispose);
    }

    private static async Task StopStepAsync(string component, Func<Task> stop)
    {
        try { await stop(); }
        catch (Exception error)
        {
            System.Diagnostics.Trace.TraceWarning("Cleanup stage {0} failed ({1})", component, error.GetType().Name);
        }
    }
}
