using System.Reflection;
using System.IO;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;

namespace MsgAT.Windows.Calls;

public sealed class WebRtcEngine
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private const string HostPage = "https://msgat.invalid/index.html";
    private readonly WebView2CompositionControl _view;
    private TaskCompletionSource _ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private long _generation;

    public WebRtcEngine(WebView2CompositionControl view)
    {
        _view = view;
    }

    public event EventHandler<string>? OfferCreated;
    public event EventHandler<string>? AnswerCreated;
    public event EventHandler<IceCandidateInfo>? IceCandidateCreated;
    public event EventHandler<string>? ConnectionStateChanged;
    public event EventHandler<long?>? RttMeasured;
    public event EventHandler<string>? MediaSetupFailed;
    public event EventHandler<CameraStatus>? CameraChanged;
    public event EventHandler<bool>? RemoteVideoChanged;
    public event EventHandler<CallAudioDevices>? AudioDevicesChanged;

    private Task? _initialization;
    private TaskCompletionSource? _mediaReleased;
    private Task? _closeTask;
    private bool _microphoneAllowed;
    private bool _cameraAllowed;
    public async Task InitializeAsync()
    {
        try { await (_initialization ??= InitializeCoreAsync()); }
        catch (Exception error)
        {
            _initialization = null;
            throw new InvalidOperationException("Voice calls could not initialize WebView2.", error);
        }
    }

    private async Task InitializeCoreAsync()
    {
        _ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
        var userDataDirectory = Path.Combine(
            MsgAT.Windows.Core.Storage.RuntimePaths.DataDirectory,
            "WebView2");
        Directory.CreateDirectory(userDataDirectory);
        // Native Android peers may not resolve Chromium's multicast .local ICE names across VM adapters.
        var options = new CoreWebView2EnvironmentOptions(
            "--disable-features=WebRtcHideLocalIpsWithMdns --autoplay-policy=no-user-gesture-required");
        var environment = await CoreWebView2Environment.CreateAsync(userDataFolder: userDataDirectory, options: options);
        await _view.EnsureCoreWebView2Async(environment);
        _view.CoreWebView2.Settings.AreDevToolsEnabled = false;
        _view.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
        _view.CoreWebView2.NavigationStarting += (_, args) => args.Cancel = args.Uri != HostPage;
        _view.CoreWebView2.NewWindowRequested += (_, args) => args.Handled = true;
        _view.CoreWebView2.PermissionRequested += (_, args) =>
        {
            var allowed = TrustedOrigin(args.Uri) && (args.PermissionKind switch
            {
                CoreWebView2PermissionKind.Microphone => _microphoneAllowed,
                CoreWebView2PermissionKind.Camera => _cameraAllowed,
                _ => false
            });
            args.SavesInProfile = false;
            args.State = allowed ? CoreWebView2PermissionState.Allow : CoreWebView2PermissionState.Deny;
        };
        _view.CoreWebView2.WebMessageReceived -= WebMessageReceived;
        _view.CoreWebView2.WebMessageReceived += WebMessageReceived;
        var hostDirectory = Path.Combine(userDataDirectory, "Host");
        Directory.CreateDirectory(hostDirectory);
        await File.WriteAllTextAsync(Path.Combine(hostDirectory, "index.html"), ReadEmbeddedHtml());
        _view.CoreWebView2.SetVirtualHostNameToFolderMapping("msgat.invalid", hostDirectory, CoreWebView2HostResourceAccessKind.DenyCors);
        _view.CoreWebView2.Navigate(HostPage);
        await _ready.Task.WaitAsync(TimeSpan.FromSeconds(15));
    }

    public Task CreateOfferAsync(IReadOnlyList<IceServer> iceServers, bool video)
    {
        ++_generation;
        _microphoneAllowed = true;
        return SendCommandAsync("offer", new { iceServers, video });
    }

    public Task AcceptOfferAsync(string sdp, IReadOnlyList<IceServer> iceServers, bool video)
    {
        ++_generation;
        _microphoneAllowed = true;
        return SendCommandAsync("accept", new { sdp, iceServers, video });
    }

    public Task ApplyAnswerAsync(string sdp) => SendCommandAsync("answer", new { sdp });

    public Task AddIceCandidateAsync(string sdpMid, int sdpMLineIndex, string candidate) =>
        SendCommandAsync("ice", new { sdpMid, sdpMLineIndex, candidate });

    public Task SetMutedAsync(bool muted) => SendCommandAsync("mute", new { muted });
    public Task SelectMicrophoneAsync(string id) => SendCommandAsync("audio-input", new { id });
    public Task SelectOutputAsync(string id) => SendCommandAsync("audio-output", new { id });
    public Task SetCameraAsync(bool enabled, bool switchDevice = false)
    {
        _cameraAllowed = enabled;
        return SendCommandAsync("camera", new { enabled, switchDevice });
    }
    public Task CloseAsync() => _closeTask is { IsCompleted: false } pending ? pending : _closeTask = CloseCoreAsync();

    private async Task CloseCoreAsync()
    {
        ++_generation;
        _microphoneAllowed = false;
        _cameraAllowed = false;
        if (!_ready.Task.IsCompletedSuccessfully) return;
        var released = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _mediaReleased = released;
        try
        {
            await SendCloseAndAwaitReleaseAsync().WaitAsync(TimeSpan.FromSeconds(5));
        }
        finally
        {
            if (ReferenceEquals(_mediaReleased, released)) _mediaReleased = null;
            released.TrySetCanceled();
        }

        async Task SendCloseAndAwaitReleaseAsync()
        {
            await SendCommandAsync("close", new { });
            await released.Task;
        }
    }
    public Task RequestStatsAsync() => SendCommandAsync("stats", new { });

    private async Task SendCommandAsync<T>(string type, T payload)
    {
        var generation = _generation;
        await InitializeAsync();
        await _ready.Task.WaitAsync(TimeSpan.FromSeconds(15));
        if (generation != _generation) return;
        var command = JsonSerializer.Serialize(new { type, payload, generation }, JsonOptions);
        await _view.ExecuteScriptAsync($"window.msgat.command({command})");
    }

    private void WebMessageReceived(object? sender, CoreWebView2WebMessageReceivedEventArgs args)
    {
        _ = sender;
        if (!TrustedOrigin(args.Source)) return;
        using var document = JsonDocument.Parse(args.WebMessageAsJson);
        var root = document.RootElement;
        var type = root.GetProperty("type").GetString();
        if (type != "ready" && (!root.TryGetProperty("generation", out var generation) || generation.GetInt64() != _generation)) return;
        switch (type)
        {
            case "media-released": _mediaReleased?.TrySetResult(); break;
            case "audio-devices":
                if (root.Deserialize<CallAudioDevices>(JsonOptions) is { } devices) AudioDevicesChanged?.Invoke(this, devices);
                break;
            case "camera":
                CameraChanged?.Invoke(this, new CameraStatus(root.GetProperty("enabled").GetBoolean(),
                    root.TryGetProperty("notice", out var notice) ? notice.GetString() : null));
                break;
            case "remote-video":
                RemoteVideoChanged?.Invoke(this, root.GetProperty("visible").GetBoolean());
                break;
            case "ready": _ready.TrySetResult(); break;
            case "offer": OfferCreated?.Invoke(this, root.GetProperty("sdp").GetString() ?? ""); break;
            case "answer": AnswerCreated?.Invoke(this, root.GetProperty("sdp").GetString() ?? ""); break;
            case "ice":
                IceCandidateCreated?.Invoke(this, new IceCandidateInfo(
                    root.GetProperty("sdpMid").GetString() ?? "0",
                    root.GetProperty("sdpMLineIndex").GetInt32(),
                    root.GetProperty("candidate").GetString() ?? ""));
                break;
            case "ice-warning":
                System.Diagnostics.Trace.TraceWarning("ICE candidate rejected: {0}", root.GetProperty("detail").GetString());
                break;
            case "state":
                if (root.TryGetProperty("reason", out var reason) && reason.GetString() is { } code)
                    MediaSetupFailed?.Invoke(this, code);
                if (root.TryGetProperty("detail", out var detail))
                {
                    System.Diagnostics.Trace.TraceWarning("WebRTC state detail: {0}", detail.GetString());
                }
                ConnectionStateChanged?.Invoke(this, root.GetProperty("state").GetString() ?? "failed");
                break;
            case "rtt":
                RttMeasured?.Invoke(this,
                    root.TryGetProperty("value", out var value) && value.ValueKind == JsonValueKind.Number
                        ? value.GetInt64()
                        : null);
                break;
        }
    }

    private static bool TrustedOrigin(string uri) => Uri.TryCreate(uri, UriKind.Absolute, out var origin) &&
        origin.Scheme == Uri.UriSchemeHttps && origin.Host == "msgat.invalid" && origin.IsDefaultPort;

    private static string ReadEmbeddedHtml()
    {
        using var stream = Assembly.GetExecutingAssembly()
            .GetManifestResourceStream("MsgAT.Windows.Assets.webrtc-host.html")
            ?? throw new InvalidOperationException("Embedded WebRTC host is missing.");
        using var reader = new StreamReader(stream);
        return reader.ReadToEnd();
    }
}

public sealed record IceCandidateInfo(string SdpMid, int SdpMLineIndex, string Candidate);
public sealed record CameraStatus(bool Enabled, string? Notice);
public sealed record CallAudioEndpoint(string Id, string Name);
public sealed record CallAudioDevices(string InputDevice, string OutputDevice, CallAudioEndpoint[] Inputs,
    CallAudioEndpoint[] Outputs, string? Notice, bool CanSelectOutput);

public sealed record IceServer(IReadOnlyList<string> Urls, string? Username = null, string? Credential = null);
