using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Windows.Media;
using System.Windows.Threading;
using MsgAT.Windows.Core.Calls;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.Media;

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
    private bool _isVideoCall;
    private bool _hasRemoteVideo;
    private bool _cameraEnabled;
    private bool _cameraPending;
    private bool _cameraForeground;
    private bool _engineStarted;
    private CallAudioDevices? _audioDevices;
    private CancellationTokenSource? _ringTimeout;
    private readonly DispatcherTimer _incomingAlert;
    private bool _isCaller;
    private string _engineConnectionState = "new";
    private CancellationTokenSource? _disconnectGrace;
    private bool IsRecoveringConnection => _disconnectGrace is not null;
    private bool _disposed;
    private string? _callId;
    private MicrophoneOwnership.CallLease? _microphone;
    private int _pendingSignals;
    private sealed class Invitation(string peer,string id,string sdp,bool video,string nonce)
    {
        public string Peer { get; }=peer;
        public string Id { get; }=id;
        public string Sdp { get; }=sdp;
        public bool Video { get; }=video;
        public string Nonce { get; }=nonce;
        public long Started { get; }=Stopwatch.GetTimestamp();
        public List<IceCandidateInfo> Ice { get; }=[];
    }
    private Invitation? _invitation;
    private bool Current(string peer,string id)=>PeerUuid==peer && _callId==id && State is not (CallState.Idle or CallState.Ended);

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
        _incomingAlert = new DispatcherTimer(DispatcherPriority.Background, dispatcher) { Interval = TimeSpan.FromSeconds(3) };
        _incomingAlert.Tick += (_, _) => PlayIncomingAlert();
        _messaging.PeerRevoked+=PeerUnavailable;
        _messaging.ContactUnavailable+=PeerUnavailable;
        _engine.OfferCreated += OfferCreated;
        _engine.AnswerCreated += AnswerCreated;
        _engine.IceCandidateCreated += IceCandidateCreated;
        _engine.ConnectionStateChanged += ConnectionStateChanged;
        _engine.RttMeasured += RttMeasured;
        _engine.MediaSetupFailed += MediaSetupFailed;
        _engine.CameraChanged += CameraChanged;
        _engine.RemoteVideoChanged += RemoteVideoChanged;
        _engine.AudioDevicesChanged += AudioDevicesChanged;
    }

    public event EventHandler? ShowRequested;
    public event EventHandler<string>? NoticeRequested;

    public CallState State
    {
        get => _state;
        private set
        {
            if (!SetProperty(ref _state, value)) return;
            if (value == CallState.IncomingRinging)
            {
                PlayIncomingAlert();
                _incomingAlert.Start();
            }
            else _incomingAlert.Stop();
            NotifyCallStatus();
            OnPropertyChanged(nameof(IsIncoming));
            OnPropertyChanged(nameof(CanChooseAudioDevices));
            OnPropertyChanged(nameof(CanChooseAudioOutput));
            NotifyCameraControls();
        }
    }

    private void PlayIncomingAlert()
    {
        if (!_disposed && State == CallState.IncomingRinging) System.Media.SystemSounds.Asterisk.Play();
    }
    public string? PeerUuid { get => _peerUuid; private set { if (SetProperty(ref _peerUuid, value)) { OnPropertyChanged(nameof(PeerAlias)); OnPropertyChanged(nameof(PeerAvatarPath)); OnPropertyChanged(nameof(PeerInitial)); } } }
    public string PeerAlias => (PeerUuid is { } peer ? _database.GetConversation(peer)?.Alias : null)
        ?? AppStrings.Get("UiCallUnknownContact");
    public string? PeerAvatarPath => PeerUuid is null ? null : _database.GetConversation(PeerUuid)?.AvatarPath;
    public string PeerInitial => string.IsNullOrWhiteSpace(PeerAlias) ? "" : System.Globalization.StringInfo.GetNextTextElement(PeerAlias);
    public long DurationSeconds { get => _durationSeconds; private set { if (SetProperty(ref _durationSeconds, value)) { OnPropertyChanged(nameof(DurationText)); NotifyCallStatus(); } } }
    public long? RttMilliseconds { get => _rttMilliseconds; private set { if (SetProperty(ref _rttMilliseconds, value)) { OnPropertyChanged(nameof(RttText)); OnPropertyChanged(nameof(QualityBrush)); } } }
    public bool IsMuted
    {
        get => _isMuted;
        private set
        {
            if (!SetProperty(ref _isMuted, value)) return;
            OnPropertyChanged(nameof(MuteText));
            OnPropertyChanged(nameof(CompactStatusText));
        }
    }
    public bool UsesCommunicationsOutput => _audioDevices?.OutputDevice == "communications";
    public string OutputShortcutText => AppStrings.Get(UsesCommunicationsOutput
        ? "UiCallUseSystemOutput" : "UiCallUseCommunicationsOutput");
    public Brush QualityBrush => RttMilliseconds is not > 0 ? Brushes.Gray : RttMilliseconds < 150 ? Brushes.Lime : RttMilliseconds < 300 ? Brushes.Yellow : Brushes.Red;
    public string MuteText => AppStrings.Get(IsMuted ? "UiUnmute" : "UiMute");
    public bool IsVideoCall
    {
        get => _isVideoCall;
        private set
        {
            if (!SetProperty(ref _isVideoCall, value)) return;
            NotifyCallStatus();
            NotifyCameraControls();
        }
    }
    public bool CameraEnabled => _cameraEnabled;
    public bool HasRemoteVideo
    {
        get => _hasRemoteVideo;
        private set => SetProperty(ref _hasRemoteVideo, value);
    }
    public bool CanControlCamera => IsVideoCall && State == CallState.Connected && _cameraForeground && !_cameraPending;
    public bool CanSwitchCamera => CanControlCamera && CameraEnabled;
    public string CameraText => AppStrings.Get(CameraEnabled ? "UiCallCameraOff" : "UiCallCameraOn");
    public CallAudioDevices? AudioDevices => _audioDevices;
    public bool CanChooseAudioDevices => State == CallState.Connected && _audioDevices is not null;
    public bool CanChooseAudioOutput => CanChooseAudioDevices && _audioDevices?.CanSelectOutput == true;
    public bool IsIncoming => State == CallState.IncomingRinging;
    internal string? CurrentCallId => _callId;
    internal string? IncomingCallId => IsIncoming ? _callId : null;
    public bool IsVisible => State != CallState.Idle;
    public string DurationText => $"{DurationSeconds / 60:00}:{DurationSeconds % 60:00}";
    public string RttText => RttMilliseconds is > 0 ? $"{RttMilliseconds}ms" : "—";
    private CallOutcome _endedOutcome = CallOutcome.Completed;
    public string StatusText => State switch
    {
        CallState.OutgoingRinging => AppStrings.Get(IsVideoCall ? "UiVideoCalling" : "UiCalling"),
        CallState.IncomingRinging => AppStrings.Get(IsVideoCall ? "UiIncomingVideoCall" : "UiIncomingVoiceCall"),
        CallState.Connecting => AppStrings.Get("UiCallConnecting"),
        CallState.Connected => IsRecoveringConnection ? AppStrings.Get("UiCallReconnecting") : DurationText,
        CallState.Ended => AppStrings.Get(_endedOutcome switch
        {
            CallOutcome.Missed => "UiCallMissed",
            CallOutcome.Declined => "UiCallDeclined",
            CallOutcome.Busy => "UiCallBusy",
            CallOutcome.Canceled => "UiCallCanceled",
            CallOutcome.Failed => "UiCallFailed",
            _ => "UiCallEnded"
        }),
        _ => ""
    };
    public string CompactStatusText => IsMuted ? $"{StatusText}, {AppStrings.Get("UiCallMicrophoneMuted")}" : StatusText;

    private void NotifyCallStatus()
    {
        OnPropertyChanged(nameof(StatusText));
        OnPropertyChanged(nameof(CompactStatusText));
    }

    public async Task StartOutgoingAsync(string peerUuid, bool video = false)
    {
        await RunOnUiAsync(async () =>
        {
            if (State != CallState.Idle) return;
            State=CallState.Connecting; // Reserve the slot while the messaging actor checks the contact.
            try
            {
                if (!await _messaging.RequireCallableAsync(peerUuid)) { State=CallState.Idle; return; }
            }
            catch { State=CallState.Idle; throw; }
            if (_disposed || State!=CallState.Connecting || _callId is not null) return;
            var id = Guid.NewGuid().ToString();
            BeginCall(peerUuid, id, isCaller: true, CallState.OutgoingRinging, video);
            try
            {
                if (!await PrepareMicrophoneAsync(peerUuid, id)) return;
                await _engine.CreateOfferAsync(BuildIceServers(), video);
                if (Current(peerUuid, id))
                {
                    _engineStarted = true;
                    await FlushPendingIceAsync();
                }
            }
            catch { if (Current(peerUuid, id)) await FinishAsync(false, publishSummary: false); throw; }
        });
    }

    public Task AcceptAsync() => RunOnUiAsync(async () =>
    {
        if (_disposed || State != CallState.IncomingRinging || PeerUuid is not { } peer || _callId is not { } id ||
            _pendingOffer is not { } offer || string.IsNullOrWhiteSpace(offer)) return;
        State = CallState.Connecting;
        StartRingTimeout();
        OnPropertyChanged(nameof(IsIncoming));
        try
        {
            if (!await PrepareMicrophoneAsync(peer, id)) return;
            await _engine.AcceptOfferAsync(offer, BuildIceServers(), IsVideoCall);
            if (Current(peer, id))
            {
                _engineStarted = true;
                await FlushPendingIceAsync();
            }
        }
        catch { if (Current(peer, id)) await FinishAsync(true); throw; }
    });

    private async Task<bool> PrepareMicrophoneAsync(string peer, string id)
    {
        if (_microphone is not null) await CloseMediaAsync();
        if (_disposed || !Current(peer, id)) return false;
        var lease = MicrophoneOwnership.ReserveCall();
        _microphone = lease;
        var available = await lease.DrainAsync();
        if (_disposed || !Current(peer, id) || !ReferenceEquals(_microphone, lease)) return false;
        if (!available) throw new InvalidOperationException("Could not switch the microphone to the call.");
        return true;
    }

    internal async Task CloseMediaAsync()
    {
        var lease = _microphone;
        await _engine.CloseAsync();
        if (!ReferenceEquals(_microphone, lease)) return;
        lease?.Dispose();
        _microphone = null;
    }

    public Task RejectAsync() => RunOnUiAsync(async () =>
    {
        if (State != CallState.IncomingRinging || PeerUuid is null) { return; }
        SendTerminal(PeerUuid,_callId!,"call.reject");
        await FinishAsync(sendEnd: false, outcome: CallOutcome.Declined);
    });

    public Task EndAsync() => RunOnUiAsync(async () =>
    {
        if (State is CallState.Idle or CallState.Ended || PeerUuid is null) { return; }
        SendTerminal(PeerUuid,_callId!,"call.end");
        await FinishAsync(sendEnd: false, outcome: CallOutcome.Canceled);
    });

    public Task ToggleMuteAsync() => RunOnUiAsync(async () =>
    {
        var muted = !IsMuted;
        await _engine.SetMutedAsync(muted);
        IsMuted = muted;
    });

    public Task SelectMicrophoneAsync(string id) => RunOnUiAsync(() =>
        CanChooseAudioDevices && (id == "default" || AudioDevices!.Inputs.Any(device => device.Id == id))
            ? _engine.SelectMicrophoneAsync(id) : Task.CompletedTask);

    public Task SelectAudioOutputAsync(string id) => RunOnUiAsync(() =>
        CanChooseAudioOutput && (id is "default" or "communications" || AudioDevices!.Outputs.Any(device => device.Id == id))
            ? _engine.SelectOutputAsync(id) : Task.CompletedTask);

    private void AudioDevicesChanged(object? sender, CallAudioDevices devices)
    {
        if (State is CallState.Idle or CallState.Ended) return;
        _audioDevices = devices;
        OnPropertyChanged(nameof(AudioDevices));
        OnPropertyChanged(nameof(UsesCommunicationsOutput));
        OnPropertyChanged(nameof(OutputShortcutText));
        OnPropertyChanged(nameof(CanChooseAudioDevices));
        OnPropertyChanged(nameof(CanChooseAudioOutput));
        ShowMediaNotice(devices.Notice);
    }

    public Task ToggleCameraAsync() => RunOnUiAsync(() => RequestCameraAsync(!CameraEnabled));
    public Task SwitchCameraAsync() => RunOnUiAsync(() => RequestCameraAsync(true, switchDevice: true));

    public void SetCameraForeground(bool foreground)
    {
        if (_cameraForeground == foreground) return;
        _cameraForeground = foreground;
        if (!foreground && IsVideoCall && (_cameraEnabled || _cameraPending))
        {
            _cameraEnabled = false;
            _cameraPending = true;
            FireAndTrace(() => SendCameraCommandAsync(false, false));
        }
        NotifyCameraControls();
    }

    private Task RequestCameraAsync(bool enabled, bool switchDevice = false)
    {
        if (!CanControlCamera || switchDevice && !CameraEnabled) return Task.CompletedTask;
        _cameraPending = true;
        NotifyCameraControls();
        return SendCameraCommandAsync(enabled, switchDevice);
    }

    private async Task SendCameraCommandAsync(bool enabled, bool switchDevice)
    {
        var callId = _callId;
        try { await _engine.SetCameraAsync(enabled, switchDevice); }
        catch (Exception error) when (error is InvalidOperationException or TimeoutException or System.Runtime.InteropServices.COMException)
        {
            if (callId != _callId) return;
            _cameraPending = false;
            NotifyCameraControls();
            ShowMediaNotice("camera-open-failed");
        }
    }

    private void CameraChanged(object? sender, CameraStatus status)
    {
        if (!IsVideoCall || State is CallState.Idle or CallState.Ended) return;
        if (status.Enabled && !_cameraForeground)
        {
            FireAndTrace(() => SendCameraCommandAsync(false, false));
            return;
        }
        _cameraEnabled = status.Enabled;
        _cameraPending = false;
        NotifyCameraControls();
        ShowMediaNotice(status.Notice);
    }

    private void NotifyCameraControls()
    {
        OnPropertyChanged(nameof(CameraEnabled));
        OnPropertyChanged(nameof(CameraText));
        OnPropertyChanged(nameof(CanControlCamera));
        OnPropertyChanged(nameof(CanSwitchCamera));
    }

    public Task ToggleOutputRoleAsync() =>
        SelectAudioOutputAsync(UsesCommunicationsOutput ? "default" : "communications");

    public Task HandleCallSignalAsync(CallSignal signal) => RunOnUiAsync(async () =>
    {
        if (_disposed) return;
        var peer=signal.Sender;
        var body=signal.Body;
        var id=body["call_id"]!.GetValue<string>();
        if (_invitation is { } expired && Stopwatch.GetElapsedTime(expired.Started).TotalSeconds>=10) _invitation=null;
        switch (signal.Kind)
        {
            case "call.offer":
                if (Current(peer,id) || (_invitation?.Peer==peer && _invitation.Id==id)) return;
                if (State!=CallState.Idle || _invitation is not null) { SendTerminal(peer,id,"call.busy"); return; }
                var pending=new Invitation(peer,id,body["sdp"]!.GetValue<string>(),body["video"]!.GetValue<bool>(),
                    Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)));
                _invitation=pending;
                _=ExpireInvitationAsync(pending);
                await SendCheckedAsync(peer,id,"call.challenge",new JsonObject { ["nonce"]=pending.Nonce });
                break;
            case "call.challenge":
                if (Current(peer,id) && _isCaller && State is CallState.OutgoingRinging or CallState.Connecting)
                    await SendCheckedAsync(peer,id,"call.confirm",new JsonObject { ["nonce"]=body["nonce"]!.GetValue<string>() });
                break;
            case "call.confirm":
                var offer=_invitation;
                if (offer is null || offer.Peer!=peer || offer.Id!=id || offer.Nonce!=body["nonce"]!.GetValue<string>()) return;
                _invitation=null;
                if (State!=CallState.Idle) { SendTerminal(peer,id,"call.busy"); return; }
                _pendingOffer=offer.Sdp;
                BeginCall(peer,id,isCaller:false,CallState.IncomingRinging,offer.Video);
                _pendingIce.AddRange(offer.Ice);
                break;
            case "call.ice":
                var item=new IceCandidateInfo(body["sdp_mid"]!.GetValue<string>(),body["mline_index"]!.GetValue<int>(),body["candidate"]!.GetValue<string>());
                if (Current(peer,id))
                {
                    if (!_engineStarted) { if (_pendingIce.Count<256) _pendingIce.Add(item); }
                    else await _engine.AddIceCandidateAsync(item.SdpMid,item.SdpMLineIndex,item.Candidate);
                }
                else if (_invitation is { } inviting && inviting.Peer==peer && inviting.Id==id && inviting.Ice.Count<256) inviting.Ice.Add(item);
                break;
            case "call.answer":
                if (!Current(peer,id) || !_isCaller || State!=CallState.OutgoingRinging) return;
                var answer = body["sdp"]!.GetValue<string>();
                if (answer.Split('\n').Any(line => line.StartsWith("m=video ", StringComparison.Ordinal)) != IsVideoCall)
                {
                    await FinishAsync(true);
                    return;
                }
                State=CallState.Connecting; StartRingTimeout();
                try { await _engine.ApplyAnswerAsync(answer); await FlushPendingIceAsync(); }
                catch { await FinishAsync(true); }
                break;
            case "call.reject": case "call.end": case "call.busy":
                if (_invitation?.Peer==peer && _invitation.Id==id) _invitation=null;
                if (!Current(peer,id)) return;
                var outcome = signal.Kind switch
                {
                    "call.reject" => CallOutcome.Declined,
                    "call.busy" => CallOutcome.Busy,
                    _ => State == CallState.IncomingRinging ? CallOutcome.Missed : CallOutcome.Failed
                };
                await FinishAsync(false, outcome);
                break;
        }
    });

    private async Task ExpireInvitationAsync(Invitation pending)
    {
        await Task.Delay(10000);
        await RunOnUiAsync(()=> { if (ReferenceEquals(_invitation,pending)) _invitation=null; return Task.CompletedTask; });
    }

    private async Task SendCheckedAsync(string peer,string id,string kind,JsonObject? body=null)
    {
        try
        {
            if (await _messaging.SendCallSignalAsync(peer,id,kind,body)) return;
        }
        catch { /* Signaling failures never retain active audio or log peer content. */ }
        if (_invitation?.Peer==peer && _invitation.Id==id) _invitation=null;
        if (Current(peer,id))
        {
            NoticeRequested?.Invoke(this, AppStrings.Get("UiCallSignalingUnavailable"));
            await FinishAsync(false);
        }
    }
    private void SendTerminal(string peer,string id,string kind) => FireAndTrace(()=>SendCheckedAsync(peer,id,kind));

    public void Dispose()
    {
        if (_disposed) { return; }
        _disposed = true;
        _incomingAlert.Stop();
        _messaging.PeerRevoked-=PeerUnavailable;
        _messaging.ContactUnavailable-=PeerUnavailable;
        _invitation=null;
        if (ReferenceEquals(_messaging.CallSink,this)) _messaging.CallSink=null;
        _callLifetime?.Cancel();
        _callLifetime?.Dispose();
        _ringTimeout?.Cancel();
        _ringTimeout?.Dispose();
        ClearDisconnectGrace();
        _engine.OfferCreated -= OfferCreated;
        _engine.AnswerCreated -= AnswerCreated;
        _engine.IceCandidateCreated -= IceCandidateCreated;
        _engine.ConnectionStateChanged -= ConnectionStateChanged;
        _engine.RttMeasured -= RttMeasured;
        _engine.MediaSetupFailed -= MediaSetupFailed;
        _engine.CameraChanged -= CameraChanged;
        _engine.RemoteVideoChanged -= RemoteVideoChanged;
        _engine.AudioDevicesChanged -= AudioDevicesChanged;
    }

    private void PeerUnavailable(object? sender,string peer) => FireAndTrace(()=>RunOnUiAsync(async ()=>
    {
        if(PeerUuid==peer && State is not (CallState.Idle or CallState.Ended)) await FinishAsync(false, publishSummary: false);
    }));

    private void BeginCall(string peerUuid, string callId, bool isCaller, CallState state, bool video)
    {
        _callLifetime?.Cancel();
        _callLifetime?.Dispose();
        _callLifetime = new CancellationTokenSource();
        _pendingIce.Clear();
        _connectedTime = null;
        ClearDisconnectGrace();
        _engineConnectionState = "new";
        _engineStarted = false;
        _audioDevices = null;
        _isCaller = isCaller;
        _callId=callId;
        _invitation=null;
        PeerUuid = peerUuid;
        DurationSeconds = 0;
        RttMilliseconds = null;
        IsMuted = false;
        OnPropertyChanged(nameof(UsesCommunicationsOutput));
        OnPropertyChanged(nameof(OutputShortcutText));
        _cameraEnabled = _cameraPending = false;
        HasRemoteVideo = false;
        IsVideoCall = video;
        State = state;
        OnPropertyChanged(nameof(IsIncoming));
        OnPropertyChanged(nameof(IsVisible));
        ShowRequested?.Invoke(this, EventArgs.Empty);
        StartRingTimeout();
    }

    private void StartRingTimeout()
    {
        _ringTimeout?.Cancel(); _ringTimeout?.Dispose();
        _ringTimeout = CancellationTokenSource.CreateLinkedTokenSource(_callLifetime?.Token ?? CancellationToken.None);
        _ = RingTimeoutAsync(_ringTimeout.Token);
    }

    private async Task RingTimeoutAsync(CancellationToken cancellationToken)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(30), cancellationToken);
            await RunOnUiAsync(async () =>
            {
                if (cancellationToken.IsCancellationRequested ||
                    State is not (CallState.IncomingRinging or CallState.OutgoingRinging or CallState.Connecting)) return;
                NoticeRequested?.Invoke(this, AppStrings.Get("UiCallTimedOut"));
                if (PeerUuid is not null)
                {
                    SendTerminal(PeerUuid,_callId!,State==CallState.IncomingRinging ? "call.reject" : "call.end");
                }
                var outcome = State == CallState.Connecting ? CallOutcome.Failed : CallOutcome.Missed;
                await FinishAsync(false, outcome);
            });
        }
        catch (OperationCanceledException) { }
    }

    private void QueueSignal(Func<string,string,Task> send)
    {
        var peer = PeerUuid;
        var lifetime = _callLifetime;
        var id=_callId;
        if (peer is null || id is null || lifetime is null || _disposed) { return; }
        if (_pendingSignals>=128) { SendTerminal(peer,id,"call.end"); FireAndTrace(()=>FinishAsync(false)); return; }
        ++_pendingSignals;
        var previous = _signalingQueue;
        _signalingQueue = SendInOrderAsync();
        async Task SendInOrderAsync()
        {
            try
            {
                await previous;
                if (_disposed || _callLifetime != lifetime || lifetime.IsCancellationRequested) { return; }
                await send(peer,id);
            }
            catch { if (Current(peer,id)) await FinishAsync(false, publishSummary: false); }
            finally { --_pendingSignals; }
        }
    }

    private void OfferCreated(object? sender, string sdp)
    {
        var video = IsVideoCall;
        QueueSignal((peer,id) => SendCheckedAsync(peer,id,"call.offer",new JsonObject { ["sdp"]=sdp,["video"]=video }));
    }

    private void AnswerCreated(object? sender, string sdp) =>
        QueueSignal((peer,id) => SendCheckedAsync(peer,id,"call.answer",new JsonObject { ["sdp"]=sdp }));

    private void IceCandidateCreated(object? sender, IceCandidateInfo candidate) =>
        QueueSignal((peer,id) => SendCheckedAsync(peer,id,"call.ice",new JsonObject { ["sdp_mid"]=candidate.SdpMid,["mline_index"]=candidate.SdpMLineIndex,["candidate"]=candidate.Candidate }));

    private void ConnectionStateChanged(object? sender, string state) => FireAndTrace(() => RunOnUiAsync(async () =>
    {
        _ = sender;
        if (State is CallState.Idle or CallState.Ended) { return; }
        _engineConnectionState = state;
        if (state == "connected")
        {
            ClearDisconnectGrace();
            if (State == CallState.Connected) return;
            State = CallState.Connected;
            _connectedTime = Stopwatch.StartNew();
            _ = ConnectedLoopAsync(_callLifetime?.Token ?? CancellationToken.None);
            return;
        }
        if (state is "failed" or "closed")
        {
            await FinishAsync(true);
        }
        else if (state == "disconnected" && State == CallState.Connected && _disconnectGrace is null &&
                 _callLifetime is { } lifetime)
        {
            var grace = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
            _disconnectGrace = grace;
            RttMilliseconds = null;
            NotifyCallStatus();
            _ = DisconnectGraceAsync(grace);
        }
    }));

    private void RttMeasured(object? sender, long? value)
    {
        _ = sender;
        RttMilliseconds = State == CallState.Connected && !IsRecoveringConnection && value is > 0 ? value : null;
    }

    private void MediaSetupFailed(object? sender, string reason) => ShowMediaNotice(reason);

    private void RemoteVideoChanged(object? sender, bool visible)
    {
        if (!_disposed && IsVideoCall && State is (CallState.OutgoingRinging or CallState.Connecting or CallState.Connected))
            HasRemoteVideo = visible;
    }

    private void ShowMediaNotice(string? reason)
    {
        var key = reason switch
        {
            "microphone-permission" => "UiCallMicrophonePermission",
            "microphone-unavailable" => "UiCallMicrophoneUnavailable",
            "microphone-open-failed" => "UiCallMicrophoneOpenFailed",
            "microphone-switch-failed" => "UiCallMicrophoneSwitchFailed",
            "microphone-fallback" => "UiCallMicrophoneFallback",
            "output-selection-unavailable" => "UiCallOutputSelectionUnavailable",
            "output-switch-failed" => "UiCallOutputSwitchFailed",
            "output-fallback" => "UiCallOutputFallback",
            "camera-permission" => "UiCallCameraPermission",
            "camera-unavailable" => "UiCallCameraUnavailable",
            "camera-open-failed" => "UiCallCameraOpenFailed",
            "no-other-camera" => "UiCallNoOtherCamera",
            "video-unavailable" => "UiCallVideoUnavailable",
            _ => null
        };
        if (key is not null) NoticeRequested?.Invoke(this, AppStrings.Get(key));
    }

    private async Task ConnectedLoopAsync(CancellationToken cancellationToken)
    {
        var tick = 0;
        try
        {
            while (!cancellationToken.IsCancellationRequested && State == CallState.Connected)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(500), cancellationToken);
                await RunOnUiAsync(async () =>
                {
                    if (State != CallState.Connected || cancellationToken.IsCancellationRequested) { return; }
                    DurationSeconds = (long)(_connectedTime?.Elapsed.TotalSeconds ?? 0);
                    ++tick;
                    if (tick % 4 == 0) await _engine.RequestStatsAsync();
                });
            }
        }
        catch (OperationCanceledException) { }
    }

    private void ClearDisconnectGrace()
    {
        if (_disconnectGrace is not { } grace) return;
        _disconnectGrace = null;
        grace.Cancel();
        grace.Dispose();
        NotifyCallStatus();
    }

    private async Task DisconnectGraceAsync(CancellationTokenSource grace)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(10), grace.Token);
            await RunOnUiAsync(async () =>
            {
                if (ReferenceEquals(_disconnectGrace, grace) && State == CallState.Connected &&
                    _engineConnectionState != "connected")
                {
                    await FinishAsync(true);
                }
            });
        }
        catch (OperationCanceledException) { }
    }

    private async Task FinishAsync(bool sendEnd, CallOutcome outcome = CallOutcome.Failed, bool publishSummary = true)
    {
        if (State is CallState.Idle or CallState.Ended) { return; }
        var finalOutcome = _connectedTime is not null ? CallOutcome.Completed : outcome;
        var seconds = (long)(_connectedTime?.Elapsed.TotalSeconds ?? 0);
        var record = publishSummary && _isCaller && _callId is not null ? FormatRecord(finalOutcome, seconds) : null;
        _endedOutcome = finalOutcome;
        if (PeerUuid is { } historyPeer && _callId is { } historyCall)
        {
            var outgoing = _isCaller;
            var video = IsVideoCall;
            var endedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            FireAndTrace(async () => { await _messaging.RecordCallHistoryAsync(historyPeer,historyCall,outgoing,video,finalOutcome,seconds,endedAt); });
        }
        State = CallState.Ended;
        ClearDisconnectGrace();
        _cameraEnabled = _cameraPending = false;
        NotifyCameraControls();
        OnPropertyChanged(nameof(IsIncoming));
        var peer = PeerUuid;
        if (sendEnd && peer is not null && _callId is { } id) SendTerminal(peer,id,"call.end");
        _callId=null;
        _callLifetime?.Cancel();
        _connectedTime?.Stop();
        try { await CloseMediaAsync(); }
        catch (Exception error) when (error is InvalidOperationException or TimeoutException or System.Runtime.InteropServices.COMException)
        {
            Trace.TraceWarning("Could not confirm call microphone release: {0}", error.GetType().Name);
            NoticeRequested?.Invoke(this, AppStrings.Get("UiCallMicrophoneReleaseFailed"));
        }
        if (record is not null && peer is not null)
        {
            FireAndTrace(async ()=> { await _messaging.SendTextAsync(peer,record); });
        }
        State = CallState.Ended;
        OnPropertyChanged(nameof(IsIncoming));
        await Task.Delay(1500);
        State = CallState.Idle;
        IsVideoCall = false;
        PeerUuid = null;
        _pendingOffer = null;
        _pendingIce.Clear();
        RttMilliseconds = null;
        OnPropertyChanged(nameof(IsVisible));
    }

    private async Task FlushPendingIceAsync()
    {
        var pending = _pendingIce.ToArray();
        _pendingIce.Clear();
        var callId = _callId;
        foreach (var item in pending)
        {
            if (_callId != callId || callId is null) return;
            await _engine.AddIceCandidateAsync(item.SdpMid, item.SdpMLineIndex, item.Candidate);
        }
    }

    private List<IceServer> BuildIceServers()
    {
        var values = new List<IceServer>
        {
            new(["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302", "stun:stun.cloudflare.com:3478", "stun:stun.miwifi.com:3478", "stun:stun.chat.bilibili.com:3478"])
        };
        var snapshot = _settings.Snapshot;
        if (!string.IsNullOrWhiteSpace(snapshot.TurnServer))
        {
            var urls = new List<string> { $"turn:{snapshot.TurnServer}:{snapshot.TurnPort}", $"turn:{snapshot.TurnServer}:{snapshot.TurnPort}?transport=tcp" };
            if (snapshot.TurnUseTls) urls.Add($"turns:{snapshot.TurnServer}:{snapshot.TurnPort}?transport=tcp");
            values.Add(new(urls, snapshot.TurnUsername, snapshot.TurnPassword));
        }
        return values;
    }

    private string FormatRecord(CallOutcome outcome, long duration)
    {
        var kind = IsVideoCall ? "Video Call" : "Voice Call";
        var result = outcome == CallOutcome.Completed ? FormattableString.Invariant($"{duration / 60:00}:{duration % 60:00}")
            : outcome.ToString();
        return $"[{kind}: {result}]";
    }

    private Task RunOnUiAsync(Func<Task> action)
    {
        if (_dispatcher.CheckAccess()) { return action(); }
        return _dispatcher.InvokeAsync(action).Task.Unwrap();
    }

    private static async void FireAndTrace(Func<Task> action)
    {
        try { await action(); }
        catch { Trace.TraceError("Call operation failed; peer details omitted."); }
    }
}
