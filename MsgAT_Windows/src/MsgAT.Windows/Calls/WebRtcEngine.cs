using System.Reflection;
using System.IO;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;

namespace MsgAT.Windows.Calls;

public sealed class WebRtcEngine
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private readonly WebView2 _view;
    private TaskCompletionSource _ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private long _generation;

    public WebRtcEngine(WebView2 view)
    {
        _view = view;
    }

    public event EventHandler<string>? OfferCreated;
    public event EventHandler<string>? AnswerCreated;
    public event EventHandler<IceCandidateInfo>? IceCandidateCreated;
    public event EventHandler<string>? ConnectionStateChanged;
    public event EventHandler<long?>? RttMeasured;
    public event EventHandler? MicrophonePermissionDenied;

    private Task? _initialization;
    private TaskCompletionSource<bool>? _speakerRequest;
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
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "MsgAT",
            "WebView2");
        Directory.CreateDirectory(userDataDirectory);
        // Native Android peers may not resolve Chromium's multicast .local ICE names across VM adapters.
        var options = new CoreWebView2EnvironmentOptions(
            "--disable-features=WebRtcHideLocalIpsWithMdns --autoplay-policy=no-user-gesture-required");
        var environment = await CoreWebView2Environment.CreateAsync(userDataFolder: userDataDirectory, options: options);
        await _view.EnsureCoreWebView2Async(environment);
        _view.CoreWebView2.Settings.AreDevToolsEnabled = false;
        _view.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
        _view.CoreWebView2.PermissionRequested += (_, args) =>
        {
            if (args.PermissionKind == CoreWebView2PermissionKind.Microphone &&
                Uri.TryCreate(args.Uri, UriKind.Absolute, out var origin) && origin.Host == "msgat.invalid")
            {
                args.State = CoreWebView2PermissionState.Allow;
            }
        };
        _view.CoreWebView2.WebMessageReceived -= WebMessageReceived;
        _view.CoreWebView2.WebMessageReceived += WebMessageReceived;
        var hostDirectory = Path.Combine(userDataDirectory, "Host");
        Directory.CreateDirectory(hostDirectory);
        await File.WriteAllTextAsync(Path.Combine(hostDirectory, "index.html"), ReadEmbeddedHtml());
        _view.CoreWebView2.SetVirtualHostNameToFolderMapping("msgat.invalid", hostDirectory, CoreWebView2HostResourceAccessKind.DenyCors);
        _view.CoreWebView2.Navigate("https://msgat.invalid/index.html");
        await _ready.Task.WaitAsync(TimeSpan.FromSeconds(15));
    }

    public Task CreateOfferAsync(IReadOnlyList<IceServer> iceServers)
    {
        ++_generation;
        return SendCommandAsync("offer", new { iceServers });
    }

    public Task AcceptOfferAsync(string sdp, IReadOnlyList<IceServer> iceServers)
    {
        ++_generation;
        return SendCommandAsync("accept", new { sdp, iceServers });
    }

    public Task ApplyAnswerAsync(string sdp) => SendCommandAsync("answer", new { sdp });

    public Task AddIceCandidateAsync(string sdpMid, int sdpMLineIndex, string candidate) =>
        SendCommandAsync("ice", new { sdpMid, sdpMLineIndex, candidate });

    public Task SetMutedAsync(bool muted) => SendCommandAsync("mute", new { muted });
    public async Task<bool> SetSpeakerAsync(bool enabled)
    {
        if (_speakerRequest is not null) throw new InvalidOperationException("Audio routing is already changing.");
        var request = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _speakerRequest = request;
        try { await SendCommandAsync("speaker", new { enabled }); return await request.Task.WaitAsync(TimeSpan.FromSeconds(5)); }
        finally { if (_speakerRequest == request) _speakerRequest = null; }
    }
    public Task CloseAsync()
    {
        ++_generation;
        _speakerRequest?.TrySetCanceled();
        return _ready.Task.IsCompletedSuccessfully ? SendCommandAsync("close", new { }) : Task.CompletedTask;
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
        using var document = JsonDocument.Parse(args.WebMessageAsJson);
        var root = document.RootElement;
        var type = root.GetProperty("type").GetString();
        if (type != "ready" && (!root.TryGetProperty("generation", out var generation) || generation.GetInt64() != _generation)) return;
        switch (type)
        {
            case "speaker": _speakerRequest?.TrySetResult(root.GetProperty("enabled").GetBoolean()); break;
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
                if (root.TryGetProperty("reason", out var reason) && reason.GetString() == "microphone-permission")
                    MicrophonePermissionDenied?.Invoke(this, EventArgs.Empty);
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

public sealed record IceServer(IReadOnlyList<string> Urls, string? Username = null, string? Credential = null);
