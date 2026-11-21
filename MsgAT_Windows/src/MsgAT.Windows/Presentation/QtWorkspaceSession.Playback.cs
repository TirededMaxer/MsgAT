// SPDX-License-Identifier: AGPL-3.0-only
using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows.Input;
using System.Windows.Interop;
using MsgAT.Windows.ViewModels;
using MsgAT.Windows.Views;

namespace MsgAT.Windows.Presentation;

internal sealed partial class QtWorkspaceSession
{
    private static bool IsPlayable(MessageItem item) => item.IsAvailable &&
        (item.IsAudio || item.IsVideo || item.Mime == "image/gif");

    private bool PresentPlaybackSurface(AttachmentView view, MessageItem item, JsonElement payload)
    {
        if (!IsPlayable(item) || view.PlaybackFailure == item.Content || ReadWindowHandle(payload) is not { } parent)
            return false;
        if (!GetClientRect(parent, out var bounds) || bounds.Right <= 0 || bounds.Bottom <= 0) return false;

        try
        {
            if (view.Playback is { } previous && (previous.Parent != parent || previous.Reference != item.Content))
                ClearAttachmentPlayback(view);

            if (view.Playback is null)
            {
                var token = view.Lifetime.Token;
                var player = new EncryptedMediaView(item.Mime, () =>
                {
                    token.ThrowIfCancellationRequested();
                    if (!_attachmentModel.CanReadAttachment(item)) throw new FileNotFoundException();
                    return item.OpenMedia();
                }, item.AttachmentInfo?.Size, () => DismissAttachmentView(view), showClose: false,
                    movePreview: direction =>
                    {
                        if (FindPreviewItem(view.Peer, view.Message) is { } current)
                            MoveAttachmentView(view, current, direction);
                    });
                view.Playback = new AttachmentPlayback(parent, item.Content, player, bounds.Right, bounds.Bottom);
            }

            view.Playback.Layout(bounds.Right, bounds.Bottom);
            if (payload.GetProperty("focus").GetBoolean()) view.Playback.Focus();
            return true;
        }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException or ExternalException)
        {
            Trace.TraceWarning("Qt media surface failed ({0}, 0x{1:X8}).", error.GetType().Name, error.HResult);
            ClearAttachmentPlayback(view);
            view.PlaybackFailure = item.Content;
            PublishAttachmentView(view, item);
            return false;
        }
    }

    private static nint? ReadWindowHandle(JsonElement payload) =>
        ulong.TryParse(payload.GetProperty("hwnd").GetString(), NumberStyles.AllowHexSpecifier,
            CultureInfo.InvariantCulture, out var handle) && handle != 0 ? unchecked((nint)handle) : null;

    private void ClearAttachmentPlayback(AttachmentView view)
    {
        var playback = view.Playback;
        view.Playback = null;
        if (playback is null) return;
        try { playback.Dispose(); }
        finally
        {
            var reads = playback.ResourceReadsCompletion;
            if (!reads.IsCompletedSuccessfully) TrackAttachmentWork(reads);
        }
    }

    private sealed class AttachmentPlayback : IDisposable
    {
        private readonly HwndSource _window;
        private readonly EncryptedMediaView _player;
        private int _width, _height;

        internal AttachmentPlayback(nint parent, string reference, EncryptedMediaView player, int width, int height)
        {
            Parent = parent;
            Reference = reference;
            _player = player;
            var parameters = new HwndSourceParameters("MsgAT media", width, height)
            {
                ParentWindow = parent,
                WindowStyle = 0x56000000 // WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS | WS_CLIPCHILDREN
            };
            HwndSource? window = null;
            try
            {
                window = new HwndSource(parameters) { SizeToContent = System.Windows.SizeToContent.Manual };
                window.RootVisual = _player;
                _window = window;
                _width = width;
                _height = height;
            }
            catch
            {
                try { _player.Dispose(); }
                finally { window?.Dispose(); }
                throw;
            }
        }

        internal nint Parent { get; }
        internal string Reference { get; }
        internal Task ResourceReadsCompletion => _player.ResourceReadsCompletion;

        internal void Layout(int width, int height)
        {
            if ((_width, _height) == (width, height)) return;
            const uint noZOrderOrActivation = 0x0014;
            if (!SetWindowPos(_window.Handle, 0, 0, 0, width, height, noZOrderOrActivation)) return;
            (_width, _height) = (width, height);
        }

        internal void Focus() => ((IKeyboardInputSink)_window).TabInto(new TraversalRequest(FocusNavigationDirection.First));

        public void Dispose()
        {
            try { _player.Dispose(); }
            finally { _window.Dispose(); }
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Win32Rect
    {
        internal int Left, Top, Right, Bottom;
    }

    [LibraryImport("user32.dll")]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetClientRect(nint window, out Win32Rect rect);

    [LibraryImport("user32.dll")]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetWindowPos(nint window, nint after, int x, int y, int width, int height, uint flags);
}
