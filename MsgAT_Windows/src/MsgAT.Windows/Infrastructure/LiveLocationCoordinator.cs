// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.InteropServices;
using System.Windows.Threading;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Views;
using Windows.Devices.Geolocation;

namespace MsgAT.Windows.Infrastructure;

internal sealed class LiveLocationCoordinator
{
    private sealed record Session(LiveLocationShare Share, long ElapsedDeadline);

    private readonly MessagingService _messaging;
    private readonly DispatcherTimer _timer;
    private readonly Dictionary<string, Session> _sessions = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Task<LiveLocationShare?>> _starting = new(StringComparer.Ordinal);
    private CancellationTokenSource? _locationRequest;
    private Task? _sample;
    private long _nextSample;
    private bool _foreground;
    private bool _closed;

    public LiveLocationCoordinator(MessagingService messaging)
    {
        _messaging = messaging;
        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _timer.Tick += (_, _) => Tick();
    }

    public event EventHandler? Changed;
    public LiveLocationShare? Current(string? peer) =>
        peer is not null && _sessions.TryGetValue(peer, out var session) ? session.Share : null;
    public bool Foreground => _foreground;
    public bool CanStart(string peer) => !_closed && _foreground && !_sessions.ContainsKey(peer) && !_starting.ContainsKey(peer);

    public void SetForeground(bool foreground)
    {
        if (_foreground == foreground || _closed) return;
        _foreground = foreground;
        if (!foreground) _locationRequest?.Cancel();
        else _nextSample = Environment.TickCount64;
        if (_sessions.Count != 0) Changed?.Invoke(this, EventArgs.Empty);
    }

    public async Task<bool> StartAsync(string peer, LocationPoint point, TimeSpan duration, string? label, string? reply,
        DraftSendCommit draft, bool silent)
    {
        var request = _messaging.StartLiveLocationAsync(peer, point, duration, label, reply, draft, silent);
        _starting.Add(peer, request);
        LiveLocationShare? share;
        try { share = await request; }
        finally { _starting.Remove(peer); }
        if (share is null) return false;
        if (_closed) return true;
        var remaining = Math.Clamp(share.ExpiresAt - DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), 0, (long)duration.TotalMilliseconds);
        if (remaining == 0) return true;
        _sessions.Add(peer, new Session(share, Environment.TickCount64 + remaining));
        _timer.Start();
        Changed?.Invoke(this, EventArgs.Empty);
        return true;
    }

    public Task<bool> StopAsync(string peer, string messageId)
    {
        if (_closed) return Task.FromResult(false);
        if (Current(peer)?.MessageId == messageId)
        {
            _sessions.Remove(peer);
            SessionsChanged();
        }
        return SendStopAsync(peer, messageId);
    }

    public async Task CloseAsync()
    {
        if (_closed) return;
        _closed = true;
        var stopped = _sessions.ToDictionary(entry => entry.Key, entry => entry.Value.Share, StringComparer.Ordinal);
        var starting = _starting.ToArray();
        _sessions.Clear();
        SessionsChanged();
        if (_sample is not null) await _sample;
        foreach (var (peer, request) in starting)
        {
            try { if (await request is { } share) stopped[peer] = share; }
            catch (Exception error) when (error is IOException or InvalidOperationException or OperationCanceledException or
                Microsoft.Data.Sqlite.SqliteException or System.Security.Cryptography.CryptographicException)
            {
                // A failed start has no durable share to stop.
            }
        }
        foreach (var (peer, share) in stopped) await SendStopAsync(peer, share.MessageId);
    }

    private void Tick()
    {
        var elapsed = Environment.TickCount64;
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var expired = _sessions.Where(row => row.Value.ElapsedDeadline <= elapsed || row.Value.Share.ExpiresAt <= now)
            .Select(row => row.Key).ToArray();
        foreach (var peer in expired) _sessions.Remove(peer);
        if (expired.Length > 0) SessionsChanged();
        if (_foreground && _sessions.Count > 0 && _sample is not { IsCompleted: false } && elapsed >= _nextSample)
        {
            _nextSample = elapsed + 15_000;
            _sample = SampleAsync();
        }
    }

    private async Task SampleAsync()
    {
        using var cancellation = new CancellationTokenSource();
        _locationRequest = cancellation;
        try
        {
            var locator = new Geolocator { DesiredAccuracy = PositionAccuracy.High };
            if (locator.LocationStatus == PositionStatus.Disabled) { await StopForPermissionAsync(); return; }
            var sample = await locator.GetGeopositionAsync(TimeSpan.Zero, TimeSpan.FromSeconds(10)).AsTask(cancellation.Token);
            if (cancellation.IsCancellationRequested || !_foreground || _closed ||
                Math.Abs((DateTimeOffset.UtcNow - sample.Coordinate.Timestamp).TotalSeconds) > 30) return;
            var coordinates = sample.Coordinate.Point.Position;
            if (LocationCoordinates.FromDegrees(coordinates.Latitude, coordinates.Longitude) is not { } point) return;
            foreach (var (peer, session) in _sessions.ToArray())
            {
                if (cancellation.IsCancellationRequested || !_foreground || _closed) break;
                if (Current(peer)?.MessageId != session.Share.MessageId || session.ElapsedDeadline <= Environment.TickCount64) continue;
                if (!await _messaging.UpdateLiveLocationAsync(peer, session.Share.MessageId, point) &&
                    Current(peer)?.MessageId == session.Share.MessageId)
                {
                    _sessions.Remove(peer);
                    SessionsChanged();
                }
            }
        }
        catch (UnauthorizedAccessException) { await StopForPermissionAsync(); }
        catch (COMException error) when ((uint)error.HResult == 0x80070005) { await StopForPermissionAsync(); }
        catch (Exception error) when (error is COMException or IOException or InvalidOperationException or TimeoutException or
            OperationCanceledException or Microsoft.Data.Sqlite.SqliteException or System.Security.Cryptography.CryptographicException) { }
        finally { _locationRequest = null; }
    }

    private async Task StopForPermissionAsync()
    {
        var stopped = _sessions.ToArray();
        _sessions.Clear();
        SessionsChanged();
        foreach (var (peer, session) in stopped) await SendStopAsync(peer, session.Share.MessageId);
    }

    private async Task<bool> SendStopAsync(string peer, string messageId)
    {
        try { return await _messaging.StopLiveLocationAsync(peer, messageId); }
        catch (Exception error) when (error is IOException or InvalidOperationException or OperationCanceledException or
            Microsoft.Data.Sqlite.SqliteException or System.Security.Cryptography.CryptographicException)
        {
            return false;
        }
    }

    private void SessionsChanged()
    {
        if (_sessions.Count == 0)
        {
            _timer.Stop();
            _locationRequest?.Cancel();
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }
}
