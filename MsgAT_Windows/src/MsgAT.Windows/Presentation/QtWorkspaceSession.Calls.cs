// SPDX-License-Identifier: AGPL-3.0-only
using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Threading;
using MsgAT.Windows.Calls;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Presentation;

internal sealed partial class QtWorkspaceSession
{
    private sealed class PresentedCall(CallService service, string id, FrameworkElement video, Panel parking)
    {
        internal readonly CallService Service = service;
        internal readonly string Id = id;
        internal readonly string Alias = service.PeerAlias;
        internal readonly FrameworkElement Video = video;
        internal readonly Panel Parking = parking;
        internal readonly Grid Visual = new() { IsHitTestVisible = false };
        internal HwndSource? Host;
        internal nint Surface;
        internal nint Anchor;
        internal (int Width, int Height) Size;
        internal string Notice = "";
        internal bool Activate, Compact, VideoFault, Busy, Ending;
    }

    private PresentedCall? _call;
    private DispatcherOperation? _callRefresh;
    internal bool HasCallPresentation => _call is not null;

    internal bool ShowCall(CallService service, FrameworkElement video, Panel parking, nint anchor, bool activate, bool compact)
    {
        if (_view == 0 || _isLocked() || service.CurrentCallId is not { } id) return false;
        var call = _call;
        if (call is null || call.Id != id || !ReferenceEquals(call.Service, service))
        {
            ClearCall();
            _call = call = new PresentedCall(service, id, video, parking) { Anchor = anchor, Compact = compact };
            service.PropertyChanged += PresentedCallChanged;
        }
        call.Activate |= activate;
        RefreshCall();
        return true;
    }

    internal bool ShowCallNotice(string message)
    {
        if (_call is not { } call || _isLocked()) return false;
        call.Notice = message;
        QueueCallRefresh();
        return true;
    }

    private void PresentedCallChanged(object? sender, PropertyChangedEventArgs args) => QueueCallRefresh();

    internal void UpdateCallPresentation() => QueueCallRefresh();

    private void QueueCallRefresh()
    {
        if (_view == 0 || _call is null || _callRefresh is not null || _dispatcher.HasShutdownStarted) return;
        _callRefresh = _dispatcher.BeginInvoke(DispatcherPriority.DataBind, new Action(() =>
        {
            _callRefresh = null;
            RefreshCall();
        }));
    }

    private void RefreshCall()
    {
        if (_call is not { } call) return;
        var service = call.Service;
        if (_view == 0 || _isLocked() || service.State == CallState.Idle ||
            (service.State != CallState.Ended && service.CurrentCallId != call.Id))
        {
            ClearCall();
            return;
        }

        if (!HasVideoStage(service)) ReleaseCallVideo(call);
        var connected = service.State == CallState.Connected;
        var initial = call.Alias.Length == 0 ? "" : StringInfo.GetNextTextElement(call.Alias);
        PresentCallFrame(new
        {
            id = call.Id, peer = call.Alias, initial, status = service.CompactStatusText,
            title = AppStrings.Get(service.IsVideoCall ? "UiVideoCall" : "UiVoiceCall"),
            incoming = service.IsIncoming, ended = service.State == CallState.Ended, connected,
            video = service.IsVideoCall, remoteVideo = service.HasRemoteVideo, localVideo = service.CameraEnabled,
            canCamera = service.CanControlCamera, canSwitchCamera = service.CanSwitchCamera,
            videoFault = call.VideoFault, busy = call.Busy, ending = call.Ending, activate = call.Activate,
            compact = call.Compact, notice = call.Notice, latency = connected ? service.RttText : "",
            anchor = ((ulong)(nuint)call.Anchor).ToString("x", CultureInfo.InvariantCulture),
            devices = CallDevices(service),
            labels = new
            {
                answer = AppStrings.Get("UiAcceptCall"), reject = AppStrings.Get("UiRejectCall"), end = AppStrings.Get("UiEndCall"),
                mute = service.MuteText, camera = service.CameraText, switchCamera = AppStrings.Get("UiCallSwitchCamera"),
                devices = AppStrings.Get("UiCallAudioDevices"), compact = AppStrings.Get("UiMinimizeCall"), expand = AppStrings.Get("UiRestoreCall"),
                waiting = AppStrings.Get("UiCallWaitingVideo"), videoUnavailable = AppStrings.Get("UiCallVideoUnavailable")
            }
        });
        call.Activate = false;
    }

    private static object? CallDevices(CallService service)
    {
        if (!service.CanChooseAudioDevices || service.AudioDevices is not { } devices) return null;
        return new
        {
            inputLabel = AppStrings.Get("UiRecordingMicrophone"), outputLabel = AppStrings.Get("UiCallAudioOutput"),
            inputs = Options(devices.Inputs, devices.InputDevice, communications: false),
            outputs = service.CanChooseAudioOutput ? Options(devices.Outputs, devices.OutputDevice, communications: true) : Array.Empty<object>()
        };

        static object[] Options(IEnumerable<CallAudioEndpoint> endpoints, string selected, bool communications)
        {
            var result = new List<object> { Option("default", AppStrings.Get("UiCallSystemDefault")) };
            if (communications) result.Add(Option("communications", AppStrings.Get("UiCallSystemCommunications")));
            result.AddRange(endpoints.Where(endpoint => endpoint.Id is not ("default" or "communications"))
                .Select(endpoint => Option(endpoint.Id, endpoint.Name)));
            return result.ToArray();

            object Option(string id, string name) => new { id, name, selected = id == selected };
        }
    }

    private bool HandleCallAction(JsonElement payload)
    {
        if (_call is not { } call || _view == 0 || _isLocked() || payload.GetProperty("id").GetString() != call.Id ||
            call.Service.CurrentCallId != call.Id || call.Service.State is CallState.Idle or CallState.Ended) return false;
        var action = payload.GetProperty("action").GetString();
        if (action == "surface") return PresentCallVideo(call, payload);
        if (call.Ending || (call.Busy && action != "end")) return false;
        var service = call.Service;
        Func<Task>? operation = action switch
        {
            "answer" when service.IsIncoming => service.AcceptAsync,
            "reject" when service.IsIncoming => service.RejectAsync,
            "end" when !service.IsIncoming => service.EndAsync,
            "mute" when service.State == CallState.Connected => service.ToggleMuteAsync,
            "camera" when service.CanControlCamera => service.ToggleCameraAsync,
            "switchCamera" when service.CanSwitchCamera => service.SwitchCameraAsync,
            "input" when service.CanChooseAudioDevices && payload.GetProperty("device").GetString() is { } input =>
                () => service.SelectMicrophoneAsync(input),
            "output" when service.CanChooseAudioOutput && payload.GetProperty("device").GetString() is { } output =>
                () => service.SelectAudioOutputAsync(output),
            _ => null
        };
        if (operation is null) return false;
        call.Notice = "";
        var ending = action == "end";
        if (ending) call.Ending = true;
        else call.Busy = true;
        _ownerContext.Post(_ => TrackMessageAction(RunAsync()), null);
        QueueCallRefresh();
        return true;

        async Task RunAsync()
        {
            try
            {
                if (ReferenceEquals(_call, call) && !_isLocked() && service.CurrentCallId == call.Id)
                    await operation();
            }
            catch (Exception error) when (error is InvalidOperationException or IOException or TimeoutException or ExternalException or OperationCanceledException)
            {
                Trace.TraceWarning("Qt call operation failed ({0}).", error.GetType().Name);
                if (ReferenceEquals(_call, call) && !_isLocked()) ShowCallNotice(AppStrings.Get("UiCallFailed"));
            }
            finally
            {
                if (ending) call.Ending = false;
                else call.Busy = false;
                if (ReferenceEquals(_call, call)) QueueCallRefresh();
            }
        }
    }

    private static bool HasVideoStage(CallService service) => service.IsVideoCall &&
        service.State is CallState.OutgoingRinging or CallState.Connecting or CallState.Connected;

    private bool PresentCallVideo(PresentedCall call, JsonElement payload)
    {
        if (call.VideoFault || !HasVideoStage(call.Service) || ReadWindowHandle(payload) is not { } handle ||
            !GetClientRect(handle, out var bounds) || bounds.Right <= 0 || bounds.Bottom <= 0) return false;
        try
        {
            if (call.Host is { } host && call.Surface == handle)
            {
                if (call.Size != (bounds.Right, bounds.Bottom) &&
                    SetWindowPos(host.Handle, 0, 0, 0, bounds.Right, bounds.Bottom, 0x0014))
                    call.Size = (bounds.Right, bounds.Bottom);
                return true;
            }

            ReleaseCallVideo(call);
            call.Host = new HwndSource(new HwndSourceParameters("MsgAT call video", bounds.Right, bounds.Bottom)
            {
                ParentWindow = handle,
                WindowStyle = 0x56000000 // WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS | WS_CLIPCHILDREN
            }) { SizeToContent = SizeToContent.Manual };
            call.Host.RootVisual = call.Visual;
            if (call.Video.Parent is Panel previous) previous.Children.Remove(call.Video);
            call.Visual.Children.Add(call.Video);
            call.Surface = handle;
            call.Size = (bounds.Right, bounds.Bottom);
            return true;
        }
        catch (Exception error) when (error is InvalidOperationException or ArgumentException or ExternalException)
        {
            Trace.TraceWarning("Qt call video could not be attached ({0}).", error.GetType().Name);
            ReleaseCallVideo(call);
            call.VideoFault = true;
            QueueCallRefresh();
            return false;
        }
    }

    private static void ReleaseCallVideo(PresentedCall call)
    {
        if (call.Host is not { } host) return;
        call.Host = null;
        call.Surface = 0;
        call.Visual.Children.Remove(call.Video);
        try { host.Dispose(); }
        finally
        {
            if (call.Video.Parent is null) call.Parking.Children.Add(call.Video);
        }
    }

    internal void ClearCall()
    {
        _callRefresh?.Abort();
        _callRefresh = null;
        if (_call is not { } call) return;
        _call = null;
        call.Service.PropertyChanged -= PresentedCallChanged;
        try { ReleaseCallVideo(call); }
        finally { PresentCallFrame(new { id = "" }); }
    }

    private unsafe void PresentCallFrame<T>(T frame)
    {
        if (_view == 0) return;
        var data = JsonSerializer.SerializeToUtf8Bytes(frame);
        try { fixed (byte* bytes = data) Present(_view, bytes, data.Length, 7); }
        finally { CryptographicOperations.ZeroMemory(data); }
    }
}
