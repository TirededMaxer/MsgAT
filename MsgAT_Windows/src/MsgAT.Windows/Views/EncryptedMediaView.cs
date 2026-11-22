// SPDX-License-Identifier: AGPL-3.0-only
using System.Globalization;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Views;

/// <summary>Serves authenticated memory ranges to a private renderer; opens no HTTP port.</summary>
internal sealed class EncryptedMediaView : Grid, IDisposable
{
    private static Task<CoreWebView2Environment>? _environment;
    private static readonly HashSet<EncryptedMediaView> ActiveViewers = [];
    private static bool _callActive;
    private readonly Func<Stream> _openMedia;
    private readonly string _mime;
    private readonly long? _size;
    private readonly Action _dismiss;
    private readonly bool _showClose;
    private readonly bool _inlineAudio;
    private readonly bool _autoplay;
    private readonly Action<int>? _movePreview;
    private readonly HashSet<Task> _resourceReads = [];
    internal Action? TogglePreviewFullScreen { get; set; }
    internal Action<bool>? ReportViewerActivity { get; set; }
    internal bool ShowsPlaybackStatus => _status is not null || _nativeAudio?.ShowsStatus == true;
    internal Task ResourceReadsCompletion => Task.WhenAll(_resourceReads);
    private sealed class PlaybackSession(FrameworkElement surface)
    {
        internal FrameworkElement Surface { get; } = surface;
        internal IWebView2 View => (IWebView2)Surface;
        internal string Source { get; } =
            "https://msgat-media.invalid/" + Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32));
        internal string Page => Source + "/viewer";
        internal byte[]? Markup { get; set; }
        internal Queue<Stream> Streams { get; } = new();
        internal bool DocumentReady { get; set; }
    }
    private PlaybackSession? _session;
    private RecordingPlaybackControl? _nativeAudio;
    private FrameworkElement? _status;
    private bool _disposed;
    private bool _suppressAutoplay;
    private double _playbackSpeed = 1;
    private ContextMenu? _speedMenu;
    internal event Action<double>? PlaybackSpeedChanged;

    internal EncryptedMediaView(MessageItem message, Action dismiss, bool showClose = true, Action<int>? movePreview = null, bool inlineAudio = false)
        : this(message.Mime, message.OpenMedia, message.AttachmentInfo?.Size, dismiss, showClose, movePreview, inlineAudio) { }

    internal EncryptedMediaView(string mime,Func<Stream> openMedia,long? size,Action dismiss, bool showClose = true,
        Action<int>? movePreview = null, bool inlineAudio = false, bool autoplay = true)
    {
        _mime=mime;_openMedia=openMedia;_size=size;_dismiss=dismiss;_showClose=showClose;_movePreview=movePreview;_inlineAudio=inlineAudio;
        _autoplay = autoplay;
        Background=Brushes.Black;
        Loaded+=async(_,_)=>
        {
            if (_disposed) return;
            ActiveViewers.Add(this);
            if (_mime is "audio/mp4" or "audio/m4a" or "audio/x-m4a" or "audio/aac" or "audio/mpeg"
                or "audio/wav" or "audio/x-wav" or "audio/flac") ShowNativeAudio();
            else await StartAsync();
        };
        Unloaded+=(_,_)=>Dispose();
    }
    private bool IsCurrent(PlaybackSession session) => !_disposed && ReferenceEquals(_session, session);

    private void ShowNativeAudio()
    {
        if (_nativeAudio is not null) return;
        Background = Brushes.Transparent;
        var playback = new RecordingPlaybackControl(_mime, _openMedia, () => !_callActive,
            autoplay: _autoplay && !_suppressAutoplay && !_callActive)
        {
            Margin = new Thickness(0), VerticalAlignment = VerticalAlignment.Center, MaxWidth = 600
        };
        playback.SetSpeed(_playbackSpeed);
        _nativeAudio = playback;
        var panel = new DockPanel { VerticalAlignment = VerticalAlignment.Center };
        if (_showClose)
        {
            var closeLabel = AppStrings.Get("UiClose");
            var close = new Button { Content = "×", ToolTip = closeLabel, Padding = new Thickness(0), Width = 36, Margin = new Thickness(6, 0, 0, 0) };
            System.Windows.Automation.AutomationProperties.SetName(close, closeLabel);
            close.Click += (_, _) => _dismiss();
            DockPanel.SetDock(close, Dock.Right);
            panel.Children.Add(close);
        }
        panel.Children.Add(playback);
        Children.Add(panel);
        void ReportActivity() => ReportViewerActivity?.Invoke(IsMouseOver || IsKeyboardFocusWithin);
        playback.StatusChanged += ReportActivity;
        IsMouseOverChanged += (_, _) => ReportActivity();
        IsKeyboardFocusWithinChanged += (_, _) => ReportActivity();
        PreviewKeyDown += (_, args) =>
        {
            if (args.Key == Key.Escape) { args.Handled = true; _dismiss(); }
            else if (args.Key == Key.F11 && TogglePreviewFullScreen is { } fullscreen) { args.Handled = true; fullscreen(); }
            else if ((args.Key is Key.Left or Key.Right) && Keyboard.FocusedElement is not Slider && _movePreview is { } move)
            {
                args.Handled = true;
                move(args.Key == Key.Left ? -1 : 1);
            }
        };
    }

    private async Task StartAsync()
    {
        if (_disposed || _session is not null || !IsLoaded) return;
        PlaybackSession? attempt = null;
        Task<CoreWebView2Environment>? environmentRequest = null;
        try
        {
            FrameworkElement surface = _inlineAudio
                ? new WebView2CompositionControl { DefaultBackgroundColor = System.Drawing.Color.Black }
                : new WebView2 { DefaultBackgroundColor = System.Drawing.Color.Black };
            var session = new PlaybackSession(surface);
            attempt = _session = session;
            surface.Visibility = Visibility.Hidden;
            Children.Add(surface);
            SetStatus(_inlineAudio ? StatusLabel("UiLoading") : MediaOverlay.PreviewLoading());

            var environmentTask = _environment ??= CoreWebView2Environment.CreateAsync(null,Path.Combine(RuntimePaths.DataDirectory,"webview2-media"),
                new CoreWebView2EnvironmentOptions("--disable-disk-cache --disk-cache-size=0 --media-cache-size=0 --disable-background-networking --autoplay-policy=no-user-gesture-required"));
            environmentRequest = environmentTask;
            var environment = await environmentTask;
            if (!IsCurrent(session)) return;
            var options=environment.CreateCoreWebView2ControllerOptions();
            options.IsInPrivateModeEnabled=true;
            await session.View.EnsureCoreWebView2Async(environment,options);
            if (!IsCurrent(session)) return;
            var core=session.View.CoreWebView2;
            if(!core.Profile.IsInPrivateModeEnabled) throw new InvalidOperationException("Private media rendering is unavailable.");
            core.Settings.AreDevToolsEnabled=false; core.Settings.AreDefaultContextMenusEnabled=false;
            core.Settings.AreHostObjectsAllowed=false; core.Settings.AreBrowserAcceleratorKeysEnabled=false;
            core.Settings.IsStatusBarEnabled=false; core.Settings.IsPasswordAutosaveEnabled=false;
            core.Settings.IsGeneralAutofillEnabled=false; core.Settings.IsZoomControlEnabled=false;
            core.PermissionRequested+=(_,e)=>e.State=CoreWebView2PermissionState.Deny;
            core.DownloadStarting+=(_,e)=>e.Cancel=true;
            core.NewWindowRequested+=(_,e)=>e.Handled=true;
            core.NavigationStarting+=(_,e)=>e.Cancel=e.Uri!=session.Page;
            core.NavigationCompleted += (_, e) =>
            {
                if (!IsCurrent(session)) return;
                if (!e.IsSuccess)
                {
                    _ = Dispatcher.InvokeAsync(() => ShowFailure(session));
                    return;
                }
                session.DocumentReady = true;
                SetStatus(null);
                surface.Visibility = Visibility.Visible;
                if (_playbackSpeed != 1) ApplyPlaybackSpeed();
                ApplyCallPlaybackState();
            };
            core.ProcessFailed += (_, e) =>
            {
                var kind = e.ProcessFailedKind;
                if (kind is not (CoreWebView2ProcessFailedKind.BrowserProcessExited or CoreWebView2ProcessFailedKind.RenderProcessExited))
                    return;
                _ = Dispatcher.InvokeAsync(() =>
                {
                    if (!IsCurrent(session)) return;
                    if (kind == CoreWebView2ProcessFailedKind.BrowserProcessExited && ReferenceEquals(_environment, environmentTask))
                        _environment = null;
                    ShowFailure(session);
                });
            };
            core.AddWebResourceRequestedFilter("https://msgat-media.invalid/*",CoreWebView2WebResourceContext.All,CoreWebView2WebResourceRequestSourceKinds.All);
            core.WebResourceRequested += (_, e) => ResourceRequested(session, environment, e);
            core.WebMessageReceived+=(_,e)=>
            {
                if (!IsCurrent(session)) return;
                try
                {
                    var action = e.TryGetWebMessageAsString();
                    _ = Dispatcher.InvokeAsync(() =>
                    {
                        if (!IsCurrent(session)) return;
                        switch (action)
                        {
                            case "close": _dismiss(); break;
                            case "previous": _movePreview?.Invoke(-1); break;
                            case "next": _movePreview?.Invoke(1); break;
                            case "toggle-full-screen": TogglePreviewFullScreen?.Invoke(); break;
                            case "viewer-pointer": ReportViewerActivity?.Invoke(false); break;
                            case "viewer-keyboard": ReportViewerActivity?.Invoke(true); break;
                            case string value when value.StartsWith("rate:", StringComparison.Ordinal) &&
                                double.TryParse(value.AsSpan(5), NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out var speed) &&
                                speed is >= 0.5 and <= 2:
                                _playbackSpeed = speed;
                                PlaybackSpeedChanged?.Invoke(speed);
                                break;
                        }
                    });
                }
                catch(System.Runtime.InteropServices.COMException) { }
            };
            var element=_mime.StartsWith("image/",StringComparison.Ordinal)?"img":_mime.StartsWith("audio/",StringComparison.Ordinal)?"audio":"video";
            var control = element == "img" ? "" :
                " controlslist=\"nodownload noremoteplayback\" playsinline preload=\"metadata\"";
            session.Markup = Encoding.UTF8.GetBytes(Document.Replace("%ELEMENT%",element,StringComparison.Ordinal)
                .Replace("%CONTROL%",control,StringComparison.Ordinal).Replace("%SOURCE%",session.Source,StringComparison.Ordinal)
                .Replace("%AUTOPLAY%", _autoplay && !_callActive && !_suppressAutoplay ? "true" : "false", StringComparison.Ordinal)
                .Replace("%HIDE_CLOSE%", _showClose ? "" : " hidden", StringComparison.Ordinal)
                .Replace("%BODY_CLASS%", _inlineAudio ? "inline-audio" : "", StringComparison.Ordinal)
                .Replace("%CAN_MOVE%", _movePreview is null ? "false" : "true", StringComparison.Ordinal)
                .Replace("%CAN_TOGGLE_FULL_SCREEN%", TogglePreviewFullScreen is null ? "false" : "true", StringComparison.Ordinal)
                .Replace("%REPORT_VIEWER_ACTIVITY%", ReportViewerActivity is null ? "false" : "true", StringComparison.Ordinal)
                .Replace("%LOADING%", WebUtility.HtmlEncode(AppStrings.Get("UiLoading")), StringComparison.Ordinal)
                .Replace("%RETRY%", WebUtility.HtmlEncode(AppStrings.Get("UiRetry")), StringComparison.Ordinal)
                .Replace("%UNAVAILABLE%", WebUtility.HtmlEncode(AppStrings.Get("UiMediaPlaybackUnavailable")), StringComparison.Ordinal));
            core.Navigate(session.Page);
        }
        catch(Exception error) when(error is not OutOfMemoryException)
        {
            if (_disposed || !ReferenceEquals(_session, attempt)) return;
            System.Diagnostics.Trace.TraceWarning("Private media initialization failed with {0} (0x{1:X8}).", error.GetType().Name, error.HResult);
            if (environmentRequest is not null && ReferenceEquals(_environment, environmentRequest))
                _environment = null;
            ShowFailure(attempt);
        }
    }

    private static TextBlock StatusLabel(string key) => new()
    {
        Text = AppStrings.Get(key), Foreground = Brushes.White, TextWrapping = TextWrapping.Wrap,
        HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center
    };

    private void SetStatus(FrameworkElement? status)
    {
        if (_status is not null) Children.Remove(_status);
        _status = status;
        if (status is not null) Children.Add(status);
        ReportViewerActivity?.Invoke(IsKeyboardFocusWithin);
    }

    private void ShowFailure(PlaybackSession? session)
    {
        if (_disposed || !ReferenceEquals(_session, session)) return;
        var restoreFocus = IsKeyboardFocusWithin;
        CloseSession();
        var panel = new StackPanel
        {
            Orientation = _inlineAudio ? Orientation.Horizontal : Orientation.Vertical,
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center
        };
        panel.Children.Add(StatusLabel("UiMediaPlaybackUnavailable"));
        var retry = new Button
        {
            Content = AppStrings.Get("UiRetry"), HorizontalAlignment = HorizontalAlignment.Center,
            Margin = _inlineAudio ? new Thickness(8, 0, 0, 0) : new Thickness(0, 8, 0, 0)
        };
        retry.Click += async (_, _) => await StartAsync();
        panel.Children.Add(retry);
        SetStatus(_inlineAudio ? panel : MediaOverlay.PreviewFeedback(panel));
        if (restoreFocus) retry.Focus();
    }

    private void CloseSession()
    {
        var session = _session;
        _session = null;
        if (session is null) return;
        try { ((IDisposable)session.Surface).Dispose(); }
        catch (Exception error) when (error is InvalidOperationException or System.Runtime.InteropServices.COMException) { }
        finally
        {
            Children.Remove(session.Surface);
            while (session.Streams.TryDequeue(out var stream)) stream.Dispose();
        }
    }

    internal void SetPlaybackSpeed(double speed)
    {
        _playbackSpeed = speed;
        ApplyPlaybackSpeed();
        PlaybackSpeedChanged?.Invoke(speed);
    }

    internal Button CreatePlaybackSpeedButton(Brush? foreground = null)
    {
        var button = new Button { MinWidth = 46, Height = 36, Margin = new Thickness(2, 0, 2, 0) };
        if (foreground is not null) button.Foreground = foreground;
        button.SetResourceReference(StyleProperty, "ButtonStyle");
        button.SetResourceReference(ToolTipProperty, "UiMediaPlaybackSpeed");
        button.SetResourceReference(System.Windows.Automation.AutomationProperties.NameProperty, "UiMediaPlaybackSpeed");
        var menu = new ContextMenu { Placement = _inlineAudio ? PlacementMode.Top : PlacementMode.Bottom, PlacementTarget = button };
        _speedMenu = menu;
        button.ContextMenu = menu;
        static string RateText(double rate) => rate.ToString("0.##", CultureInfo.CurrentCulture) + "×";
        void ShowRate(double rate)
        {
            button.Content = RateText(rate);
            System.Windows.Automation.AutomationProperties.SetItemStatus(button, RateText(rate));
        }

        foreach (var rate in new[] { 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2 })
        {
            var option = new MenuItem { Header = RateText(rate), IsCheckable = true };
            option.Click += (_, _) => SetPlaybackSpeed(rate);
            menu.Items.Add(option);
            menu.Opened += (_, _) => option.IsChecked = rate == _playbackSpeed;
        }
        ShowRate(_playbackSpeed);
        PlaybackSpeedChanged += ShowRate;
        button.Click += (_, _) => menu.IsOpen = true;
        return button;
    }

    private void ApplyPlaybackSpeed()
    {
        _nativeAudio?.SetSpeed(_playbackSpeed);
        if (_disposed || _session is not { DocumentReady: true } session) return;
        try { session.View.CoreWebView2?.PostWebMessageAsJson(_playbackSpeed.ToString(CultureInfo.InvariantCulture)); }
        catch (Exception error) when (error is InvalidOperationException or System.Runtime.InteropServices.COMException)
        {
            _ = Dispatcher.InvokeAsync(() => ShowFailure(session));
        }
    }

    internal void PausePlayback()
    {
        _suppressAutoplay = true;
        _nativeAudio?.PausePlayback();
        SendPlaybackCommand("pause");
    }

    internal static void SetCallActive(bool active)
    {
        if (_callActive == active) return;
        _callActive = active;
        foreach (var viewer in ActiveViewers.ToArray()) viewer.ApplyCallPlaybackState();
    }

    private void ApplyCallPlaybackState()
    {
        if (_callActive)
        {
            _suppressAutoplay = true;
            _nativeAudio?.PausePlayback();
        }
        SendPlaybackCommand(_callActive ? "playback-blocked" : _suppressAutoplay ? "playback-unblocked" : "playback-allowed");
    }

    private void SendPlaybackCommand(string command)
    {
        if (_disposed || _session is not { DocumentReady: true } session) return;
        try { session.View.CoreWebView2?.PostWebMessageAsString(command); }
        catch (Exception error) when (error is InvalidOperationException or System.Runtime.InteropServices.COMException)
        {
            _ = Dispatcher.InvokeAsync(() => ShowFailure(session));
        }
    }

    private async void ResourceRequested(PlaybackSession session, CoreWebView2Environment environment,
        CoreWebView2WebResourceRequestedEventArgs e)
    {
        var read = ReadResourceAsync(session, environment, e);
        if (read.IsCompletedSuccessfully) return;
        _resourceReads.Add(read);
        try { await read; }
        finally { _resourceReads.Remove(read); }
    }

    private async Task ReadResourceAsync(PlaybackSession session, CoreWebView2Environment environment,
        CoreWebView2WebResourceRequestedEventArgs e)
    {
        if (!IsCurrent(session)) return;
        CoreWebView2Deferral? deferral = null;
        Stream? content=null;
        try
        {
            deferral = e.GetDeferral();
            if (e.Request.Uri == session.Page && e.Request.Method == "GET" && session.Markup is { } markup)
            {
                var document = new MemoryStream(markup, writable: false);
                session.Streams.Enqueue(document);
                e.Response = environment.CreateWebResourceResponse(document, 200, "OK",
                    "Content-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n");
                return;
            }
            if(e.Request.Uri!=session.Source || e.Request.Method is not ("GET" or "HEAD"))
            {
                e.Response=environment.CreateWebResourceResponse(null!,403,"Forbidden","Cache-Control: no-store\r\n");
                return;
            }
            var total=_size;
            if(total is null)
            {
                using var original=await Task.Run(_openMedia);
                total=original.Length;
            }
            if (!IsCurrent(session)) return;
            var range=e.Request.Headers.Contains("Range")?e.Request.Headers.GetHeader("Range"):"";
            var valid=Range(range,total.Value,out var start,out var length);
            if(!valid)
            {
                e.Response=environment.CreateWebResourceResponse(null!,416,"Range Not Satisfiable",$"Content-Range: bytes */{total}\r\nCache-Control: no-store\r\n");
                return;
            }
            if(e.Request.Method!="HEAD")
            {
                var input=await Task.Run(_openMedia);
                if (!IsCurrent(session)) { input.Dispose(); return; }
                try { content = new RangeStream(input, start, length); }
                catch { input.Dispose(); throw; }
            }
            var headers=$"Content-Type: {_mime}\r\nContent-Length: {length}\r\nAccept-Ranges: bytes\r\nCache-Control: no-store, no-cache, must-revalidate\r\nX-Content-Type-Options: nosniff\r\n";
            if(range.Length>0) headers+=$"Content-Range: bytes {start}-{start+length-1}/{total}\r\n";
            e.Response=environment.CreateWebResourceResponse(content!,range.Length>0?206:200,range.Length>0?"Partial Content":"OK",headers);
            if (content is not null)
            {
                while (session.Streams.Count >= 8) session.Streams.Dequeue().Dispose();
                session.Streams.Enqueue(content);
            }
            content=null; // The view retains the bounded stream until replacement/disposal.
        }
        catch(Exception error) when(error is not OutOfMemoryException)
        {
            content?.Dispose();
            if (IsCurrent(session))
            {
                try { e.Response=environment.CreateWebResourceResponse(null!,404,"Unavailable","Cache-Control: no-store\r\n"); }
                catch (Exception responseError) when (responseError is InvalidOperationException or System.Runtime.InteropServices.COMException) { }
            }
        }
        finally
        {
            try { deferral?.Dispose(); }
            catch (Exception error) when (error is InvalidOperationException or System.Runtime.InteropServices.COMException) { }
        }
    }

    private static bool Range(string value,long total,out long start,out long length)
    {
        start=0; length=total;
        if(value.Length==0) return true;
        if(total==0 || value.Length>128 || !value.StartsWith("bytes=",StringComparison.Ordinal)) return false;
        var pair=value[6..].Split('-');
        if(pair.Length!=2) return false;
        if(pair[0].Length==0)
        {
            if(!long.TryParse(pair[1],NumberStyles.None,CultureInfo.InvariantCulture,out var suffix) || suffix<=0) return false;
            length=Math.Min(total,suffix); start=total-length; return true;
        }
        if(!long.TryParse(pair[0],NumberStyles.None,CultureInfo.InvariantCulture,out start) || start<0 || start>=total) return false;
        var end=total-1;
        if(pair[1].Length!=0 && (!long.TryParse(pair[1],NumberStyles.None,CultureInfo.InvariantCulture,out end) || end<start)) return false;
        end=Math.Min(end,total-1); length=end-start+1; return true;
    }

    private sealed class RangeStream : Stream
    {
        private readonly Stream _input;
        private readonly long _start;
        private readonly long _length;
        private readonly object _gate=new();
        private long _position;
        private bool _disposed;
        internal RangeStream(Stream input,long start,long length) { _input=input; _start=start; _length=length; _input.Position=start; }
        public override bool CanRead=>!_disposed;
        public override bool CanWrite=>false;
        public override bool CanSeek=>!_disposed;
        public override long Length=>_length;
        public override long Position { get { lock(_gate) return _position; } set=>Seek(value,SeekOrigin.Begin); }
        public override int Read(byte[] buffer,int offset,int count)
        {
            lock(_gate)
            {
                ObjectDisposedException.ThrowIf(_disposed,this);
                if(_position>=_length) return 0;
                var read=_input.Read(buffer,offset,(int)Math.Min(count,_length-_position)); _position+=read; return read;
            }
        }
        public override long Seek(long offset,SeekOrigin origin)
        {
            lock(_gate)
            {
                ObjectDisposedException.ThrowIf(_disposed,this);
                var position=checked((origin switch { SeekOrigin.Begin=>0,SeekOrigin.Current=>_position,SeekOrigin.End=>_length,_=>throw new ArgumentOutOfRangeException(nameof(origin)) })+offset);
                if(position<0 || position>_length) throw new IOException("Invalid media range position.");
                _input.Position=_start+position; _position=position; return position;
            }
        }
        protected override void Dispose(bool disposing) { lock(_gate) { if(!_disposed && disposing) _input.Dispose(); _disposed=true; } base.Dispose(disposing); }
        public override void Flush() { }
        public override void SetLength(long value)=>throw new NotSupportedException();
        public override void Write(byte[] buffer,int offset,int count)=>throw new NotSupportedException();
    }

    public void Dispose()
    {
        if(_disposed) return;
        _disposed = true;
        ActiveViewers.Remove(this);
        if (_speedMenu is { } menu) menu.IsOpen = false;
        _speedMenu = null;
        TogglePreviewFullScreen = null;
        ReportViewerActivity = null;
        PlaybackSpeedChanged = null;
        _nativeAudio?.Dispose();
        _nativeAudio = null;
        CloseSession();
        SetStatus(null);
        GC.SuppressFinalize(this);
    }

    private const string Document="""
        <!doctype html><html><head><meta charset="utf-8">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; media-src 'self'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
        <style>html,body{margin:0;background:#000;color:#fff;height:100%;overflow:hidden;font:14px system-ui}body{display:flex;align-items:center;justify-content:center}video,img{max-width:100%;max-height:100%;object-fit:contain}audio{width:min(90%,600px)}button{position:fixed;top:14px;right:14px;width:36px;height:36px;border:0;border-radius:18px;background:#333b;color:white;font:26px system-ui;cursor:pointer}p{padding:0 16px;text-align:center}</style>
        <style>body.inline-audio{justify-content:flex-start;padding:0 8px;box-sizing:border-box}body.inline-audio audio{width:calc(100% - 34px);height:42px}body.inline-audio button{right:6px;top:50%;transform:translateY(-50%);width:26px;height:26px;font-size:18px}#loading{position:absolute;pointer-events:none;background:#000;padding:4px 8px;border-radius:6px}</style>
        <style>#error{text-align:center;max-width:90%}#error p{margin:0 0 8px}#retry{position:static;transform:none;width:auto;height:auto;padding:6px 14px;font:inherit}body.inline-audio #error{display:flex;gap:6px;align-items:center}body.inline-audio #error[hidden]{display:none}body.inline-audio #error p{margin:0;padding:0}#retry:focus-visible{outline:2px solid white}</style>
        </head><body class="%BODY_CLASS%"><%ELEMENT% id="media" src="%SOURCE%"%CONTROL%></%ELEMENT%><button id="close" aria-label="Close"%HIDE_CLOSE%>×</button>
        <p id="loading" role="status" hidden>%LOADING%</p>
        <div id="error" hidden><p role="status">%UNAVAILABLE%</p><button id="retry" type="button">%RETRY%</button></div>
        <script>
          const media = document.getElementById('media');
          const stillImage = media instanceof HTMLImageElement;
          const canMove = %CAN_MOVE%;
          const canToggleFullScreen = %CAN_TOGGLE_FULL_SCREEN%;
          const reportViewerActivity = %REPORT_VIEWER_ACTIVITY%;
          const loading = document.getElementById('loading');
          const failure = document.getElementById('error');
          const retry = document.getElementById('retry');
          const source = media.getAttribute('src');
          const playAfterReload = %AUTOPLAY%;
          let initialPlayback = playAfterReload;
          let playbackAllowed = false;
          let loadingTimer;
          let lastPosition = 0;
          let speed = 1;
          let resumeAt = null;
          function hideLoading() {
            clearTimeout(loadingTimer);
            loading.hidden = true;
          }
          function showLoading() {
            if (media.error || media.paused && media.readyState >= HTMLMediaElement.HAVE_METADATA) return;
            clearTimeout(loadingTimer);
            loadingTimer = setTimeout(() => { loading.hidden = false; }, 180);
          }
          function showError() {
            const hadFocus = media.contains(document.activeElement);
            hideLoading();
            media.hidden = true;
            failure.hidden = false;
            if (hadFocus) retry.focus();
          }
          media.addEventListener('error', showError);
          retry.onclick = () => {
            if (failure.hidden) return;
            failure.hidden = true;
            media.hidden = false;
            if (stillImage) {
              media.removeAttribute('src');
              media.setAttribute('src', source);
            } else {
              resumeAt = lastPosition;
              media.autoplay = false;
              media.load();
              media.focus();
            }
            showLoading();
          };
          if (stillImage) media.addEventListener('load', hideLoading);
          if (!stillImage) {
            if (media.readyState < HTMLMediaElement.HAVE_METADATA) showLoading();
            media.addEventListener('waiting', showLoading);
            for (const event of ['canplay', 'playing', 'pause', 'ended']) media.addEventListener(event, hideLoading);
            const trackPosition = () => {
              if (!media.error && resumeAt === null && Number.isFinite(media.currentTime)) lastPosition = media.currentTime;
            };
            media.addEventListener('timeupdate', trackPosition);
            media.addEventListener('seeked', trackPosition);
            media.addEventListener('loadedmetadata', () => {
              if (resumeAt === null) return;
              const target = Number.isFinite(media.duration) ? Math.min(resumeAt, media.duration) : resumeAt;
              media.playbackRate = speed;
              if (target > 0) media.currentTime = target;
              resumeAt = null;
              if (playAfterReload && playbackAllowed) media.play().catch(() => {});
            });
            media.addEventListener('play', () => { if (!playbackAllowed) media.pause(); });
            chrome.webview.addEventListener('message', ({ data: rate }) => {
              if (rate === 'playback-allowed' || rate === 'playback-unblocked') {
                playbackAllowed = true;
                media.controls = true;
                if (initialPlayback && rate === 'playback-allowed') media.play().catch(() => {});
                initialPlayback = false;
              } else if (rate === 'playback-blocked') {
                playbackAllowed = false;
                initialPlayback = false;
                media.pause();
                media.controls = false;
              } else if (rate === 'pause') {
                initialPlayback = false;
                media.pause();
              } else if (Number.isFinite(rate) && rate >= 0.5 && rate <= 2) {
                speed = rate;
                media.playbackRate = rate;
              }
            });
            media.addEventListener('ratechange', () => {
              if (resumeAt !== null || media.error) return;
              speed = media.playbackRate;
              chrome.webview.postMessage(`rate:${speed}`);
            });
          }
          if (media.error || (stillImage && media.complete && media.naturalWidth === 0)) showError();
          if (reportViewerActivity) {
            let lastPointerActivity = -100;
            document.addEventListener('pointermove', event => {
              const now = performance.now();
              if (event.isTrusted && now - lastPointerActivity >= 100) {
                lastPointerActivity = now;
                chrome.webview.postMessage('viewer-pointer');
              }
            }, { passive: true });
            document.addEventListener('pointerdown', event => {
              if (event.isTrusted) chrome.webview.postMessage('viewer-pointer');
            }, { passive: true });
          }
          document.getElementById('close').onclick = () => chrome.webview.postMessage('close');
          document.addEventListener('keydown', event => {
            if (reportViewerActivity && event.isTrusted && !event.repeat)
              chrome.webview.postMessage('viewer-keyboard');
            if (event.key === 'Escape' && !document.fullscreenElement) {
              event.preventDefault();
              if (!event.repeat) chrome.webview.postMessage('close');
            } else if (canToggleFullScreen && event.key === 'F11' && !event.altKey && !event.ctrlKey &&
                       !event.metaKey && !event.shiftKey && !document.fullscreenElement) {
              event.preventDefault();
              if (!event.repeat) chrome.webview.postMessage('toggle-full-screen');
            } else if (canMove && !event.ctrlKey && !event.metaKey && !event.shiftKey &&
                       (event.altKey || stillImage) && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) {
              event.preventDefault();
              if (!event.repeat) chrome.webview.postMessage(event.key === 'ArrowLeft' ? 'previous' : 'next');
            } else if (!stillImage && !media.hidden && event.key === ' ' && event.target === document.body) {
              event.preventDefault();
              if (!event.repeat) {
                if (media.paused) media.play().catch(() => {});
                else media.pause();
              }
            }
          });
        </script></body></html>
        """;
}
