using System.Diagnostics;
using System.Windows.Threading;
using MsgAT.Windows.Core.Calls;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Calls;

public sealed class CallService : ObservableObject, ICallSignalingSink, IDisposable
{
    private readonly Dispatcher _dispatcher;
    private readonly WebRtcEngine _engine;
    private readonly MessagingService _messaging;
    private readonly EncryptedDatabase _database;
    private readonly SecureSettingsStore _settings;
    private readonly List<IceCandidateInfo> _pendingIce = [];
    private Task _signalingQueue = Task.CompletedTask;
    private CancellationTokenSource? _callLifetime;
    private Stopwatch? _connectedTime;
    private string? _pendingOffer;
    private CallState _state;
    private string? _peerUuid;
    private long _durationSeconds;
    private long? _rttMilliseconds;
    private bool _isMuted;
    private bool _isCaller;
    private string _engineConnectionState = "new";
    private bool _disposed;

    public CallService(
        Dispatcher dispatcher,
        WebRtcEngine engine,
        MessagingService messaging,
        EncryptedDatabase database,
        SecureSettingsStore settings)
    {
        _dispatcher = dispatcher;
        _engine = engine;
        _messaging = messaging;
        _database = database;
        _settings = settings;
        _engine.OfferCreated += OfferCreated;
        _engine.AnswerCreated += AnswerCreated;
        _engine.IceCandidateCreated += IceCandidateCreated;
        _engine.ConnectionStateChanged += ConnectionStateChanged;
        _engine.RttMeasured += RttMeasured;
    }

    public event EventHandler? ShowRequested;

    public CallState State { get => _state; private set { if (SetProperty(ref _state, value)) { OnPropertyChanged(nameof(StatusText)); } } }
    public string? PeerUuid { get => _peerUuid; private set { if (SetProperty(ref _peerUuid, value)) { OnPropertyChanged(nameof(PeerAlias)); OnPropertyChanged(nameof(PeerAvatarPath)); OnPropertyChanged(nameof(PeerInitial)); } } }
    public string PeerAlias => PeerUuid is null ? "Unknown" : _database.GetConversation(PeerUuid)?.Alias ?? "Unknown";
    public string? PeerAvatarPath => PeerUuid is null ? null : _database.GetConversation(PeerUuid)?.AvatarPath;
    public string PeerInitial => string.IsNullOrWhiteSpace(PeerAlias) ? "" : System.Globalization.StringInfo.GetNextTextElement(PeerAlias);
    public long DurationSeconds { get => _durationSeconds; private set { if (SetProperty(ref _durationSeconds, value)) { OnPropertyChanged(nameof(DurationText)); OnPropertyChanged(nameof(StatusText)); } } }
    public long? RttMilliseconds { get => _rttMilliseconds; private set { if (SetProperty(ref _rttMilliseconds, value)) { OnPropertyChanged(nameof(RttText)); } } }
    public bool IsMuted { get => _isMuted; private set { if (SetProperty(ref _isMuted, value)) OnPropertyChanged(nameof(MuteText)); } }
    public string MuteText => IsMuted ? "Unmute" : "Mute";
    public bool IsIncoming => State == CallState.IncomingRinging;
    public bool IsVisible => State != CallState.Idle;
    public string DurationText => $"{DurationSeconds / 60:00}:{DurationSeconds % 60:00}";
    public string RttText => RttMilliseconds is > 0 ? $"{RttMilliseconds} ms" : "—";
    public string StatusText => State switch
    {
        CallState.OutgoingRinging => "Calling…",
        CallState.IncomingRinging => "Incoming voice call…",
        CallState.Connecting => "Connecting…",
        CallState.Connected => DurationText,
        CallState.Ended => "Call ended",
        _ => ""
    };

    public async Task StartOutgoingAsync(string peerUuid)
    {
        await RunOnUiAsync(async () =>
        {
            EnsureIdle();
            RequireCallablePeer(peerUuid);
            BeginCall(peerUuid, isCaller: true, CallState.OutgoingRinging);
            try { await _engine.CreateOfferAsync(BuildIceServers()); }
            catch { await FinishAsync(false, null); throw; }
        });
    }

    public Task AcceptAsync() => RunOnUiAsync(async () =>
    {
        if (State != CallState.IncomingRinging || PeerUuid is null || string.IsNullOrWhiteSpace(_pendingOffer)) { return; }
        State = CallState.Connecting;
        OnPropertyChanged(nameof(IsIncoming));
        await _engine.AcceptOfferAsync(_pendingOffer, BuildIceServers());
        await FlushPendingIceAsync();
    });

    public Task RejectAsync() => RunOnUiAsync(async () =>
    {
        if (State != CallState.IncomingRinging || PeerUuid is null) { return; }
        await _messaging.SendCallRejectAsync(PeerUuid);
        await FinishAsync(sendEnd: false, record: null);
    });

    public Task EndAsync() => RunOnUiAsync(async () =>
    {
        if (State is CallState.Idle or CallState.Ended || PeerUuid is null) { return; }
        var duration = DurationSeconds;
        await _messaging.SendCallEndAsync(PeerUuid, duration);
        await FinishAsync(sendEnd: false, record: _isCaller ? FormatRecord(duration) : null);
    });

    public Task ToggleMuteAsync() => RunOnUiAsync(async () =>
    {
        IsMuted = !IsMuted;
        await _engine.SetMutedAsync(IsMuted);
    });

    public Task OfferReceivedAsync(string senderUuid, string sdp) => RunOnUiAsync(async () =>
    {
        if (State != CallState.Idle)
        {
            await _messaging.SendCallBusyAsync(senderUuid);
            return;
        }
        RequireCallablePeer(senderUuid);
        _pendingOffer = sdp;
        BeginCall(senderUuid, isCaller: false, CallState.IncomingRinging);
    });

    public Task AnswerReceivedAsync(string senderUuid, string sdp) => RunOnUiAsync(async () =>
    {
        if (!_isCaller || PeerUuid != senderUuid || State != CallState.OutgoingRinging) { return; }
        State = CallState.Connecting;
        await _engine.ApplyAnswerAsync(sdp);
        await FlushPendingIceAsync();
    });

    public Task IceCandidateReceivedAsync(string senderUuid, string sdpMid, int sdpMLineIndex, string candidate) =>
        RunOnUiAsync(async () =>
        {
            if (PeerUuid != senderUuid || State == CallState.Idle || string.IsNullOrWhiteSpace(candidate)) { return; }
            var item = new IceCandidateInfo(sdpMid, sdpMLineIndex, candidate);
            if (State == CallState.IncomingRinging) { if (_pendingIce.Count < 256) _pendingIce.Add(item); }
            else { await _engine.AddIceCandidateAsync(sdpMid, sdpMLineIndex, candidate); }
        });

    public Task RejectedAsync(string senderUuid) => FinishRemoteAsync(senderUuid, "[Voice Call: Declined]");
    public Task BusyAsync(string senderUuid) => FinishRemoteAsync(senderUuid, "[Voice Call: Busy]");

    public Task EndedAsync(string senderUuid, long durationSeconds) => RunOnUiAsync(async () =>
    {
        if (PeerUuid != senderUuid || State is CallState.Idle or CallState.Ended) { return; }
        var duration = durationSeconds >= 0 ? durationSeconds : DurationSeconds;
        await FinishAsync(sendEnd: false, record: _isCaller ? FormatRecord(duration) : null);
    });

    public Task SyncReceivedAsync(string senderUuid, long durationSeconds)
    {
        // Advisory only. A sync packet cannot establish media or start the call clock.
        _ = senderUuid;
        _ = durationSeconds;
        return Task.CompletedTask;
    }

    public void Dispose()
    {
        if (_disposed) { return; }
        _disposed = true;
        _callLifetime?.Cancel();
        _callLifetime?.Dispose();
        _engine.OfferCreated -= OfferCreated;
        _engine.AnswerCreated -= AnswerCreated;
        _engine.IceCandidateCreated -= IceCandidateCreated;
        _engine.ConnectionStateChanged -= ConnectionStateChanged;
        _engine.RttMeasured -= RttMeasured;
    }

    private void BeginCall(string peerUuid, bool isCaller, CallState state)
    {
        _callLifetime?.Cancel();
        _callLifetime?.Dispose();
        _callLifetime = new CancellationTokenSource();
        _pendingIce.Clear();
        _connectedTime = null;
        _engineConnectionState = "new";
        _isCaller = isCaller;
        PeerUuid = peerUuid;
        DurationSeconds = 0;
        RttMilliseconds = null;
        IsMuted = false;
        State = state;
        OnPropertyChanged(nameof(IsIncoming));
        OnPropertyChanged(nameof(IsVisible));
        ShowRequested?.Invoke(this, EventArgs.Empty);
        _ = RingTimeoutAsync(_callLifetime.Token);
    }

    private async Task RingTimeoutAsync(CancellationToken cancellationToken)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(30), cancellationToken);
            await RunOnUiAsync(async () =>
            {
                if (State is not (CallState.IncomingRinging or CallState.OutgoingRinging or CallState.Connecting)) { return; }
                if (PeerUuid is not null)
                {
                    if (State == CallState.IncomingRinging) { await _messaging.SendCallRejectAsync(PeerUuid); }
                    else { await _messaging.SendCallEndAsync(PeerUuid, 0); }
                }
                await FinishAsync(false, _isCaller ? "[Voice Call: Missed]" : null);
            });
        }
        catch (OperationCanceledException) { }
    }

    private void QueueSignal(Func<string, Task> send)
    {
        var peer = PeerUuid;
        var lifetime = _callLifetime;
        if (peer is null || lifetime is null || _disposed) { return; }
        var previous = _signalingQueue;
        _signalingQueue = SendInOrderAsync();
        async Task SendInOrderAsync()
        {
            try
            {
                await previous;
                if (_disposed || _callLifetime != lifetime || lifetime.IsCancellationRequested) { return; }
                await send(peer);
            }
            catch (Exception error) { Trace.TraceError("Call signaling failed: {0}", error); }
        }
    }

    private void OfferCreated(object? sender, string sdp) =>
        QueueSignal(peer => _messaging.SendCallOfferAsync(peer, sdp));

    private void AnswerCreated(object? sender, string sdp) =>
        QueueSignal(peer => _messaging.SendCallAnswerAsync(peer, sdp));

    private void IceCandidateCreated(object? sender, IceCandidateInfo candidate) =>
        QueueSignal(peer => _messaging.SendCallIceAsync(peer, candidate.SdpMid, candidate.SdpMLineIndex, candidate.Candidate));

    private void ConnectionStateChanged(object? sender, string state) => FireAndTrace(() => RunOnUiAsync(async () =>
    {
        _ = sender;
        if (State is CallState.Idle or CallState.Ended) { return; }
        _engineConnectionState = state;
        if (state == "connected" && State != CallState.Connected)
        {
            State = CallState.Connected;
            _connectedTime = Stopwatch.StartNew();
            _ = ConnectedLoopAsync(_callLifetime?.Token ?? CancellationToken.None);
            return;
        }
        if (state is "failed" or "closed")
        {
            await FinishAsync(false, _isCaller ? FormatRecord(DurationSeconds) : null);
        }
        else if (state == "disconnected")
        {
            _ = DisconnectGraceAsync(_callLifetime?.Token ?? CancellationToken.None);
        }
    }));

    private void RttMeasured(object? sender, long? value)
    {
        _ = sender;
        RttMilliseconds = value is > 0 ? value : null;
    }

    private async Task ConnectedLoopAsync(CancellationToken cancellationToken)
    {
        var syncCounter = 0;
        try
        {
            while (!cancellationToken.IsCancellationRequested && State == CallState.Connected)
            {
                await Task.Delay(TimeSpan.FromSeconds(1), cancellationToken);
                await RunOnUiAsync(async () =>
                {
                    if (State != CallState.Connected) { return; }
                    DurationSeconds = (long)(_connectedTime?.Elapsed.TotalSeconds ?? 0);
                    await _engine.RequestStatsAsync();
                    if (++syncCounter >= 10 && PeerUuid is not null)
                    {
                        syncCounter = 0;
                        await _messaging.SendCallSyncAsync(PeerUuid, DurationSeconds);
                    }
                });
            }
        }
        catch (OperationCanceledException) { }
    }

    private async Task DisconnectGraceAsync(CancellationToken cancellationToken)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(10), cancellationToken);
            await RunOnUiAsync(async () =>
            {
                if (State == CallState.Connected && _engineConnectionState == "disconnected")
                {
                    await FinishAsync(false, _isCaller ? FormatRecord(DurationSeconds) : null);
                }
            });
        }
        catch (OperationCanceledException) { }
    }

    private Task FinishRemoteAsync(string senderUuid, string record) => RunOnUiAsync(async () =>
    {
        if (PeerUuid == senderUuid && State is not (CallState.Idle or CallState.Ended))
        {
            await FinishAsync(false, _isCaller ? record : null);
        }
    });

    private async Task FinishAsync(bool sendEnd, string? record)
    {
        if (State is CallState.Idle or CallState.Ended) { return; }
        State = CallState.Ended;
        OnPropertyChanged(nameof(IsIncoming));
        var peer = PeerUuid;
        if (sendEnd && peer is not null) { await _messaging.SendCallEndAsync(peer, DurationSeconds); }
        _callLifetime?.Cancel();
        _connectedTime?.Stop();
        try { await _engine.CloseAsync(); } catch (InvalidOperationException) { }
        if (record is not null && peer is not null)
        {
            try { await _messaging.SendTextAsync(peer, record); }
            catch (InvalidOperationException exception) { Trace.TraceWarning("Call record was not sent: {0}", exception.Message); }
        }
        State = CallState.Ended;
        OnPropertyChanged(nameof(IsIncoming));
        await Task.Delay(600);
        State = CallState.Idle;
        PeerUuid = null;
        _pendingOffer = null;
        _pendingIce.Clear();
        RttMilliseconds = null;
        OnPropertyChanged(nameof(IsVisible));
    }

    private async Task FlushPendingIceAsync()
    {
        foreach (var item in _pendingIce)
        {
            await _engine.AddIceCandidateAsync(item.SdpMid, item.SdpMLineIndex, item.Candidate);
        }
        _pendingIce.Clear();
    }

    private List<IceServer> BuildIceServers()
    {
        var values = new List<IceServer>
        {
            new(["stun:stun.l.google.com:19302", "stun:stun.cloudflare.com:3478"])
        };
        var snapshot = _settings.Snapshot;
        if (!string.IsNullOrWhiteSpace(snapshot.TurnServer))
        {
            var scheme = snapshot.TurnUseTls ? "turns" : "turn";
            values.Add(new([$"{scheme}:{snapshot.TurnServer}:{snapshot.TurnPort}"], snapshot.TurnUsername, snapshot.TurnPassword));
        }
        return values;
    }

    private void RequireCallablePeer(string peerUuid)
    {
        if (_database.GetConversation(peerUuid) is not { PeerPublicKey.Length: > 0 })
        {
            throw new InvalidOperationException("Voice calls require an active friend identity.");
        }
    }

    private void EnsureIdle()
    {
        if (State != CallState.Idle) { throw new InvalidOperationException("A voice call is already active."); }
    }

    private static string FormatRecord(long duration) => duration <= 0
        ? "[Voice Call: Missed]"
        : $"[Voice Call: {duration / 60:00}:{duration % 60:00}]";

    private Task RunOnUiAsync(Func<Task> action)
    {
        if (_dispatcher.CheckAccess()) { return action(); }
        return _dispatcher.InvokeAsync(action).Task.Unwrap();
    }

    private static async void FireAndTrace(Func<Task> action)
    {
        try { await action(); }
        catch (Exception exception) { Trace.TraceError("Call operation failed: {0}", exception); }
    }
}
