using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Protocol;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Core.Network;

public sealed class RelayClient : IAsyncDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private readonly SecureSettingsStore _settings;
    private readonly SemaphoreSlim _sendGate = new(1, 1);
    private readonly object _stateGate = new();
    private CancellationTokenSource? _lifetime;
    private Task? _connectionLoop;
    private SslStream? _stream;
    private SyncState _state;

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

    private (byte[] Nonce, TaskCompletionSource<bool> Result)? _deletionReply;

    public async Task<bool> DeleteAccountAsync(CancellationToken cancellationToken = default)
    {
        if (State != SyncState.Synced) return false;
        var nonce = RandomNumberGenerator.GetBytes(16);
        var result = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _deletionReply = (nonce, result);
        try
        {
            if (!await SendAsync(new GhostPacket(GhostPacket.DeleteAccount, _settings.MyMailboxId, nonce, [], new byte[16]), cancellationToken).ConfigureAwait(false)) return false;
            return await result.Task.WaitAsync(TimeSpan.FromSeconds(10), cancellationToken).ConfigureAwait(false);
        }
        catch (TimeoutException) { return false; }
        finally { _deletionReply = null; }
    }

    public Task StartAsync()
    {
        if (_connectionLoop is not null) { return Task.CompletedTask; }
        _lifetime = new CancellationTokenSource();
        _connectionLoop = RunConnectionLoopAsync(_lifetime.Token);
        return Task.CompletedTask;
    }

    public async Task ReconnectAsync()
    {
        await StopAsync().ConfigureAwait(false);
        await StartAsync().ConfigureAwait(false);
    }

    public async Task StopAsync()
    {
        var lifetime = _lifetime;
        var loop = _connectionLoop;
        _lifetime = null;
        _connectionLoop = null;
        if (lifetime is null) { return; }
        await lifetime.CancelAsync().ConfigureAwait(false);
        try { _stream?.Dispose(); } catch (IOException) { }
        if (loop is not null)
        {
            try { await loop.ConfigureAwait(false); }
            catch (OperationCanceledException) when (lifetime.IsCancellationRequested) { }
        }
        lifetime.Dispose();
        State = SyncState.Disconnected;
    }

    public async Task<bool> SendAsync(GhostPacket packet, CancellationToken cancellationToken = default)
    {
        var stream = _stream;
        if (stream is null || !stream.CanWrite) { return false; }
        byte[] frame;
        try { frame = GhostPacketCodec.Encode(packet); }
        catch (ArgumentException exception)
        {
            System.Diagnostics.Trace.TraceWarning("Outbound packet was rejected: {0}", exception.Message);
            return false;
        }
        await _sendGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (!ReferenceEquals(stream, _stream) || !stream.CanWrite) { return false; }
            await stream.WriteAsync(frame, cancellationToken).ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            return true;
        }
        catch (Exception error) when (error is IOException or ObjectDisposedException) { return false; }
        finally { _sendGate.Release(); }
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        _sendGate.Dispose();
    }

    private async Task RunConnectionLoopAsync(CancellationToken cancellationToken)
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
                await ConnectAndReceiveAsync(cancellationToken).ConfigureAwait(false);
                retry = 0;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { break; }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                System.Diagnostics.Trace.TraceWarning("Relay connection failed: {0}", exception.Message);
            }
            finally
            {
                _stream?.Dispose();
                _stream = null;
                State = SyncState.Disconnected;
            }

            var delaySeconds = Math.Min(30, 1 << Math.Min(retry++, 5));
            var jitter = Random.Shared.NextDouble();
            await Task.Delay(TimeSpan.FromSeconds(delaySeconds + jitter), cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task ConnectAndReceiveAsync(CancellationToken cancellationToken)
    {
        var snapshot = _settings.Snapshot;
        var host = snapshot.ServerAddress ?? throw new InvalidOperationException("Relay server is not configured.");
        using var tcp = new TcpClient { NoDelay = true };
        await tcp.ConnectAsync(host, snapshot.ServerPort, cancellationToken).ConfigureAwait(false);
        using var ssl = new SslStream(
            tcp.GetStream(),
            leaveInnerStreamOpen: false,
            (_, certificate, chain, errors) => ValidateCertificate(snapshot, certificate, chain, errors));
        await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = host,
            EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
            CertificateRevocationCheckMode = X509RevocationMode.Online
        }, cancellationToken).ConfigureAwait(false);
        _stream = ssl;
        State = SyncState.Syncing;
        await SendAsync(GhostPacket.Empty(GhostPacket.AuthRequest, _settings.MyMailboxId), cancellationToken)
            .ConfigureAwait(false);

        using var connectionLifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var heartbeat = HeartbeatLoopAsync(connectionLifetime.Token);
        try { await ReceiveLoopAsync(ssl, connectionLifetime.Token).ConfigureAwait(false); }
        finally
        {
            await connectionLifetime.CancelAsync().ConfigureAwait(false);
            try { await heartbeat.ConfigureAwait(false); } catch (OperationCanceledException) { }
        }
    }

    private async Task ReceiveLoopAsync(SslStream stream, CancellationToken cancellationToken)
    {
        var pending = new byte[GhostPacketCodec.MaximumFrameSize * 2];
        var pendingLength = 0;
        while (!cancellationToken.IsCancellationRequested)
        {
            if (pendingLength == pending.Length)
            {
                throw new InvalidDataException("Relay sent more than two maximum-size frames without a parse boundary.");
            }
            var read = await stream.ReadAsync(pending.AsMemory(pendingLength), cancellationToken).ConfigureAwait(false);
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
                await HandlePacketAsync(packet!, cancellationToken).ConfigureAwait(false);
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

    private async Task HandlePacketAsync(GhostPacket packet, CancellationToken cancellationToken)
    {
        switch (packet.Type)
        {
            case GhostPacket.AuthChallenge:
                var signed = CryptoService.SignChallenge(_settings.IdentitySeed, packet.NonceOrIv);
                var payload = new byte[signed.PublicKey.Length + signed.Signature.Length];
                signed.PublicKey.CopyTo(payload, 0);
                signed.Signature.CopyTo(payload, signed.PublicKey.Length);
                await SendAsync(new GhostPacket(
                    GhostPacket.AuthResponse,
                    _settings.MyMailboxId,
                    packet.NonceOrIv,
                    payload,
                    new byte[16]), cancellationToken).ConfigureAwait(false);
                await SendProfileAsync(cancellationToken).ConfigureAwait(false);
                break;
            case GhostPacket.DeleteAccount:
                if (_deletionReply is { } deletion && packet.NonceOrIv.AsSpan().SequenceEqual(deletion.Nonce)) deletion.Result.TrySetResult(true);
                break;
            case GhostPacket.OfflineEnd:
                State = SyncState.Synced;
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

    public async Task SendProfileAsync(CancellationToken cancellationToken = default)
    {
        var settings = _settings.Snapshot;
        string? avatarData = null;
        if (!string.IsNullOrWhiteSpace(settings.AvatarPath) && File.Exists(settings.AvatarPath))
        {
            avatarData = Convert.ToBase64String(await File.ReadAllBytesAsync(settings.AvatarPath, cancellationToken).ConfigureAwait(false));
        }
        var profile = JsonSerializer.SerializeToUtf8Bytes(new
        {
            uuid = _settings.MyUuid,
            nickname = settings.Nickname ?? "Unknown",
            avatarPath = settings.AvatarPath ?? "",
            avatarData
        }, JsonOptions);
        await SendAsync(new GhostPacket(
            GhostPacket.UpdateProfile,
            _settings.MyMailboxId,
            new byte[16],
            profile,
            new byte[16]), cancellationToken).ConfigureAwait(false);
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

    private static bool ValidateCertificate(
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
