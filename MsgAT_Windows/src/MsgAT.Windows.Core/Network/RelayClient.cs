using System.Net;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Threading.Channels;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Protocol;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Core.Network;

public sealed class RelayClient : IAsyncDisposable
{
    internal sealed class UnprocessedDeliveryException(Exception? cause = null)
        : Exception("The incoming envelope could not be committed.", cause) { }

    private readonly SecureSettingsStore _settings;
    private readonly SemaphoreSlim _sendGate = new(1, 1);
    private readonly SemaphoreSlim _lifecycle = new(1, 1);
    private readonly object _stateGate = new();
    private int _stopping;
    private int _disposed;
    private CancellationTokenSource? _lifetime;
    private Task? _connectionLoop;
    private volatile SslStream? _stream;
    private volatile SecureSettings? _connectedSettings;
    internal SecureSettings? ConnectedSettings => _connectedSettings;
    private SyncState _state;
    private long _epoch;
    private long _pendingBytes;
    internal long ConnectionEpoch => Interlocked.Read(ref _epoch);

    internal void CompleteSync(long epoch) { if (epoch==ConnectionEpoch && _stream is not null) State=SyncState.Synced; }
    internal void AbortConnection(long epoch)
    {
        var stream=_stream;
        if (epoch!=ConnectionEpoch) return;
        try { stream?.Dispose(); } catch (IOException) { }
    }

    public RelayClient(SecureSettingsStore settings)
    {
        _settings = settings;
    }

    public event EventHandler<SyncState>? StateChanged;
    public event Func<GhostPacket, CancellationToken, Task>? PacketReceived;

    public SyncState State
    {
        get { lock (_stateGate) { return _state; } }
        private set
        {
            lock (_stateGate)
            {
                if (_state == value) { return; }
                _state = value;
            }
            StateChanged?.Invoke(this, value);
        }
    }

    public async Task StartAsync()
    {
        await _lifecycle.WaitAsync().ConfigureAwait(false);
        try { StartConnectionLoop(); }
        finally { _lifecycle.Release(); }
    }

    private void StartConnectionLoop()
    {
        if (Volatile.Read(ref _stopping) != 0 || _connectionLoop is not null) return;
        _lifetime = new CancellationTokenSource();
        _connectionLoop = RunConnectionLoopAsync(_lifetime.Token);
    }

    public async Task ReconnectAsync()
    {
        await _lifecycle.WaitAsync().ConfigureAwait(false);
        try
        {
            if (Volatile.Read(ref _stopping) != 0) return;
            await StopConnectionLoopAsync().ConfigureAwait(false);
            StartConnectionLoop();
        }
        finally { _lifecycle.Release(); }
    }

    public async Task StopAsync()
    {
        Interlocked.Exchange(ref _stopping, 1);
        await _lifecycle.WaitAsync().ConfigureAwait(false);
        try { await StopConnectionLoopAsync().ConfigureAwait(false); }
        finally { _lifecycle.Release(); }
    }

    private async Task StopConnectionLoopAsync()
    {
        var lifetime = _lifetime;
        var loop = _connectionLoop;
        _lifetime = null;
        _connectionLoop = null;
        if (lifetime is null) { return; }
        try
        {
            var cancellation = lifetime.CancelAsync();
            try { _stream?.Dispose(); } catch (IOException) { }
            if (loop is not null)
            {
                try { await loop.ConfigureAwait(false); }
                catch (OperationCanceledException) when (lifetime.IsCancellationRequested) { }
                finally { await cancellation.ConfigureAwait(false); }
            }
            else await cancellation.ConfigureAwait(false);
        }
        finally
        {
            lifetime.Dispose();
            State = SyncState.Disconnected;
        }
    }

    public Task<bool> SendAsync(GhostPacket packet, CancellationToken cancellationToken = default) => SendCoreAsync(packet,null,cancellationToken);
    internal Task<bool> SendForEpochAsync(GhostPacket packet,long epoch,CancellationToken token) => SendCoreAsync(packet,epoch,token);

    private async Task<bool> SendCoreAsync(GhostPacket packet,long? expectedEpoch,CancellationToken cancellationToken)
    {
        if (Volatile.Read(ref _stopping) != 0) return false;
        var stream = _stream;
        if (stream is null || !stream.CanWrite || (expectedEpoch is not null && expectedEpoch!=ConnectionEpoch)) { return false; }
        byte[] frame;
        try { frame = GhostPacketCodec.Encode(packet); }
        catch (ArgumentException exception)
        {
            System.Diagnostics.Trace.TraceWarning("Outbound packet was rejected ({0})", exception.GetType().Name);
            return false;
        }
        if (Interlocked.Add(ref _pendingBytes,frame.Length)>4L*1024*1024)
        {
            Interlocked.Add(ref _pendingBytes,-frame.Length);
            AbortConnection(ConnectionEpoch);
            return false;
        }
        using var deadline=CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(10));
        var entered=false;
        try
        {
            await _sendGate.WaitAsync(deadline.Token).ConfigureAwait(false);
            entered=true;
            if (!ReferenceEquals(stream, _stream) || !stream.CanWrite || (expectedEpoch is not null && expectedEpoch!=ConnectionEpoch)) { return false; }
            await stream.WriteAsync(frame, deadline.Token).ConfigureAwait(false);
            await stream.FlushAsync(deadline.Token).ConfigureAwait(false);
            return true;
        }
        catch (Exception error) when (error is IOException or ObjectDisposedException) { return false; }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        { if (ReferenceEquals(stream,_stream)) AbortConnection(ConnectionEpoch); return false; }
        finally
        {
            if (entered) _sendGate.Release();
            Interlocked.Add(ref _pendingBytes,-frame.Length);
        }
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        if (Interlocked.Exchange(ref _disposed, 1) == 0) _sendGate.Dispose();
    }

    private async Task RunConnectionLoopAsync(CancellationToken cancellationToken)
    {
        var pathChanges = Channel.CreateBounded<bool>(1);
        NetworkAddressChangedEventHandler changed = (_, _) => pathChanges.Writer.TryWrite(true);
        NetworkChange.NetworkAddressChanged += changed;
        try
        {
            await MaintainConnectionsAsync(pathChanges.Reader, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            NetworkChange.NetworkAddressChanged -= changed;
        }
    }

    private async Task MaintainConnectionsAsync(ChannelReader<bool> pathChanges, CancellationToken cancellationToken)
    {
        var retry = 0;
        while (!cancellationToken.IsCancellationRequested)
        {
            if (!_settings.IsConfigured)
            {
                State = SyncState.Disconnected;
                await Task.Delay(TimeSpan.FromSeconds(2), cancellationToken).ConfigureAwait(false);
                continue;
            }

            try
            {
                await ConnectAndReceiveAsync(pathChanges, cancellationToken).ConfigureAwait(false);
                retry = 0;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { break; }
            catch (OperationCanceledException)
            {
                System.Diagnostics.Trace.TraceWarning("Relay connection, authentication or initial mailbox sync timed out.");
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                System.Diagnostics.Trace.TraceWarning("Relay connection failed ({0})", exception.GetType().Name);
            }
            finally
            {
                _stream?.Dispose();
                _stream = null;
                _connectedSettings = null;
                State = SyncState.Disconnected;
            }

            var delaySeconds = Math.Min(30, 1 << Math.Min(retry++, 5));
            var jitter = Random.Shared.NextDouble();
            using var backoff = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            backoff.CancelAfter(TimeSpan.FromSeconds(delaySeconds + jitter));
            try
            {
                await pathChanges.ReadAsync(backoff.Token).ConfigureAwait(false);
                retry = 0;
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { }
        }
    }

    private async Task ConnectAndReceiveAsync(ChannelReader<bool> pathChanges, CancellationToken cancellationToken)
    {
        var snapshot = _settings.Snapshot;
        var host = snapshot.ServerAddress ?? throw new InvalidOperationException("Relay server is not configured.");
        using var tcp = new TcpClient { NoDelay = true };
        using var tlsDeadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        tlsDeadline.CancelAfter(TimeSpan.FromSeconds(20));
        await tcp.ConnectAsync(host, snapshot.ServerPort, tlsDeadline.Token).ConfigureAwait(false);
        using var ssl = new SslStream(
            tcp.GetStream(),
            leaveInnerStreamOpen: false,
            (_, certificate, chain, errors) => ValidateCertificate(snapshot, certificate, chain, errors));
        await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = host,
            EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
            CertificateRevocationCheckMode = X509RevocationMode.Online
        }, tlsDeadline.Token).ConfigureAwait(false);
        tlsDeadline.CancelAfter(Timeout.InfiniteTimeSpan);
        Interlocked.Increment(ref _epoch);
        _stream = ssl;
        _connectedSettings = snapshot;
        State = SyncState.Syncing;
        var certificate = ssl.RemoteCertificate ?? throw new AuthenticationException("TLS certificate is required.");
        var authentication = new RelayAuthentication(_settings.MyMailboxId, SHA256.HashData(certificate.GetRawCertData()));
        await SendAsync(authentication.Request(), cancellationToken)
            .ConfigureAwait(false);

        using var connectionLifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var heartbeat = HeartbeatLoopAsync(connectionLifetime.Token);
        var source = (tcp.Client.LocalEndPoint as IPEndPoint)?.Address;
        var monitor = source is not null && !IPAddress.IsLoopback(source)
            ? MonitorSourceAddressAsync(source, pathChanges, connectionLifetime) : Task.CompletedTask;
        try { await ReceiveLoopAsync(ssl, authentication, connectionLifetime.Token).ConfigureAwait(false); }
        catch (OperationCanceledException) when (connectionLifetime.IsCancellationRequested && !cancellationToken.IsCancellationRequested) { }
        finally
        {
            await connectionLifetime.CancelAsync().ConfigureAwait(false);
            try { await Task.WhenAll(heartbeat, monitor).ConfigureAwait(false); } catch (OperationCanceledException) { }
        }
    }

    private static async Task MonitorSourceAddressAsync(IPAddress source, ChannelReader<bool> pathChanges,
                                                        CancellationTokenSource connection)
    {
        if (source.IsIPv4MappedToIPv6) source = source.MapToIPv4();
        while (!connection.IsCancellationRequested)
        {
            await pathChanges.ReadAsync(connection.Token).ConfigureAwait(false);
            bool present;
            try
            {
                present = NetworkInterface.GetAllNetworkInterfaces().Any(adapter =>
                    adapter.OperationalStatus == OperationalStatus.Up &&
                    adapter.GetIPProperties().UnicastAddresses.Any(address => address.Address.Equals(source)));
            }
            catch (NetworkInformationException) { continue; }
            if (present) continue;
            await connection.CancelAsync().ConfigureAwait(false);
            return;
        }
    }

    private async Task ReceiveLoopAsync(SslStream stream, RelayAuthentication authentication, CancellationToken cancellationToken)
    {
        using var authenticationDeadline = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        using var authenticationLifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, authenticationDeadline.Token);
        using var initialSyncDeadline = new CancellationTokenSource();
        using var initialSyncLifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, initialSyncDeadline.Token);
        var initialSyncTimeout = TimeSpan.FromSeconds(40);
        var pending = new byte[GhostPacketCodec.MaximumFrameSize * 2];
        var pendingLength = 0;
        while (!cancellationToken.IsCancellationRequested)
        {
            if (pendingLength == pending.Length)
            {
                throw new InvalidDataException("Relay sent more than two maximum-size frames without a parse boundary.");
            }
            var readToken = !authentication.Authenticated ? authenticationLifetime.Token
                : State == SyncState.Synced ? cancellationToken : initialSyncLifetime.Token;
            var read = await stream.ReadAsync(pending.AsMemory(pendingLength), readToken).ConfigureAwait(false);
            if (read == 0) { return; }
            pendingLength += read;

            var consumedTotal = 0;
            string? protocolError = null;
            while (GhostPacketCodec.TryDecode(
                pending.AsSpan(consumedTotal, pendingLength - consumedTotal),
                out var packet,
                out var consumed,
                out protocolError))
            {
                consumedTotal += consumed;
                try
                {
                    await HandlePacketAsync(packet!, authentication, cancellationToken).ConfigureAwait(false);
                }
                catch (UnprocessedDeliveryException error) when (packet!.Type == GhostPacket.V2Delivery)
                {
                    if (error.InnerException is { } cause)
                        System.Diagnostics.Trace.TraceWarning("Incoming envelope left unacknowledged ({0}).", cause.GetType().Name);
                    continue;
                }
                if (!authentication.Authenticated) continue;
                if (packet!.Type == GhostPacket.OfflineEnd)
                    authenticationDeadline.CancelAfter(Timeout.InfiniteTimeSpan);
                if (State == SyncState.Synced)
                    initialSyncDeadline.CancelAfter(Timeout.InfiniteTimeSpan);
                else if (packet.Type is GhostPacket.OfflineEnd or GhostPacket.V2KeyPublished or GhostPacket.V2Delivery)
                    initialSyncDeadline.CancelAfter(initialSyncTimeout);
            }
            if (protocolError is not null)
            {
                throw new InvalidDataException(protocolError);
            }
            if (consumedTotal > 0)
            {
                Buffer.BlockCopy(pending, consumedTotal, pending, 0, pendingLength - consumedTotal);
                pendingLength -= consumedTotal;
            }
        }
    }

    private async Task HandlePacketAsync(GhostPacket packet, RelayAuthentication authentication, CancellationToken cancellationToken)
    {
        if (!authentication.Authenticated && packet.Type is not GhostPacket.AuthChallenge and not GhostPacket.OfflineEnd)
            throw new InvalidDataException("Application packet arrived before authentication.");
        switch (packet.Type)
        {
            case GhostPacket.AuthChallenge:
                var transcript = authentication.Transcript(packet);
                var signed = _settings.WithRootSeed(seed => CryptoService.SignChallenge(seed, transcript));
                var payload = new byte[signed.PublicKey.Length + signed.Signature.Length];
                signed.PublicKey.CopyTo(payload, 0);
                signed.Signature.CopyTo(payload, signed.PublicKey.Length);
                await SendAsync(new GhostPacket(
                    GhostPacket.AuthResponse,
                    _settings.MyMailboxId,
                    packet.NonceOrIv,
                    payload,
                    new byte[16]), cancellationToken).ConfigureAwait(false);
                break;
            case GhostPacket.OfflineEnd:
                authentication.Complete(packet);
                if (PacketReceived is { } sync) await sync(packet,cancellationToken).ConfigureAwait(false);
                break;
            case GhostPacket.Heartbeat:
                break;
            default:
                if (PacketReceived is { } handler)
                {
                    await handler(packet, cancellationToken).ConfigureAwait(false);
                }
                break;
        }
    }

    private async Task HeartbeatLoopAsync(CancellationToken cancellationToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(180));
        while (await timer.WaitForNextTickAsync(cancellationToken).ConfigureAwait(false))
        {
            if (!await SendAsync(GhostPacket.Empty(GhostPacket.Heartbeat), cancellationToken).ConfigureAwait(false))
            {
                return;
            }
        }
    }

    internal static bool ValidateCertificate(
        SecureSettings settings,
        X509Certificate? certificate,
        X509Chain? chain,
        SslPolicyErrors errors)
    {
        _ = chain;
        if (certificate is null) { return false; }
        if (!string.IsNullOrWhiteSpace(settings.ServerCertificateSha256))
        {
            var expected = NormalizeFingerprint(settings.ServerCertificateSha256);
            var actual = Convert.ToHexStringLower(SHA256.HashData(certificate.GetRawCertData()));
            return expected.Length == actual.Length
                && CryptographicOperations.FixedTimeEquals(Convert.FromHexString(expected), Convert.FromHexString(actual));
        }
        if (errors == SslPolicyErrors.None) { return true; }
        return settings.AllowDevelopmentCertificate;
    }

    private static string NormalizeFingerprint(string value) =>
        new(value.Where(Uri.IsHexDigit).Select(char.ToLowerInvariant).ToArray());
}
