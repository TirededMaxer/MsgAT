// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Presentation;

internal sealed partial class QtWorkspaceSession
{
    private sealed class AttachmentView(string peer, string message, long generation)
    {
        internal readonly string Peer = peer;
        internal readonly string Message = message;
        internal readonly long Generation = generation;
        internal readonly CancellationTokenSource Lifetime = new();
        internal string Status = "";
        internal string? ImageReference;
        internal AttachmentPlayback? Playback;
        internal string? PlaybackFailure;
        internal bool HasImage;
        internal bool Working;
        internal AttachmentViewFrame? LastFrame;
    }

    private sealed record AttachmentViewFrame(long generation, string id, string filename, string name, string info, string status,
        string download, string save, string close, string previous, string next, bool clearImage, bool working,
        bool dismiss = false, bool playback = false);
    private sealed record PhotoPixels(byte[] Bytes, int Width, int Height, int Stride);

    private AttachmentView? _attachmentView;

    [LibraryImport(Bridge, EntryPoint = "msgat_workspace_image")]
    [DefaultDllImportSearchPaths(DllImportSearchPath.SafeDirectories)]
    private static unsafe partial void PresentPhoto(nint view, long generation, byte* pixels, int width, int height, int stride);

    private bool HandleAttachmentPreview(int action, JsonElement payload)
    {
        var peer = payload.GetProperty("peer").GetString();
        var id = payload.GetProperty("id").GetString();
        var generation = payload.GetProperty("generation").GetInt64();
        if (action == 22)
        {
            if (peer is null || id is null || FindPreviewItem(peer, id) is null) return false;
            ClearAttachmentPreview();
            _attachmentView = new AttachmentView(peer, id, generation);
            UpdateAttachmentPreview();
            return true;
        }
        if (_attachmentView is not { } view || view.Peer != peer || view.Message != id || view.Generation != generation)
            return false;
        if (action == 24) { ClearAttachmentPreview(); return true; }
        if (FindPreviewItem(view.Peer, view.Message) is not { } item) return false;
        if (action == 28) return PresentPlaybackSurface(view, item, payload);
        if (view.Working) return false;

        if (action is 26 or 27)
            return MoveAttachmentView(view, item, action == 26 ? -1 : 1);

        if (action == 23 && (item.CanPauseDownload || item.CanRequestDownload))
        {
            view.Working = true;
            view.Status = "";
            PublishAttachmentView(view, item);
            TrackAttachmentWork(ChangeDownloadAsync(view, item));
            return true;
        }

        if (action == 25 && item.IsAvailable && !item.HasExpiration &&
            payload.GetProperty("path").GetString() is { } destination && Path.IsPathFullyQualified(destination))
        {
            view.Working = true;
            view.Status = AppStrings.Get("UiSaving");
            PublishAttachmentView(view, item);
            TrackAttachmentWork(ExportPreviewAsync(view, item, destination));
            return true;
        }
        return false;
    }

    private bool MoveAttachmentView(AttachmentView view, MessageItem item, int direction)
    {
        if (!ReferenceEquals(_attachmentView, view) || view.Working) return false;
        var (previous, next) = AlbumNeighbors(item);
        var target = direction < 0 ? previous : next;
        if (target is null || FindPreviewItem(view.Peer, target) is null) return false;
        ClearAttachmentPreview();
        _attachmentView = new AttachmentView(view.Peer, target, view.Generation);
        UpdateAttachmentPreview();
        return true;
    }

    private void DismissAttachmentView(AttachmentView view)
    {
        if (!ReferenceEquals(_attachmentView, view)) return;
        ClearAttachmentPreview();
        PresentAttachmentView(new(view.Generation, view.Message, "", "", "", "", "", "", "", "", "", true, false, dismiss: true));
    }

    private void ClearReceivedAttachmentPreview()
    {
        if (_attachmentView is { } view && FindPreviewItem(view.Peer, view.Message) is { IsMine: false })
            DismissAttachmentView(view);
    }

    private static (string? Previous, string? Next) AlbumNeighbors(MessageItem item)
    {
        var members = item.AlbumAnchor?.AlbumMembers ?? item.AlbumMembers;
        for (var i = 0; i < members.Count; i++)
        {
            if (members[i].MessageId != item.MessageId) continue;
            return (i > 0 ? members[i - 1].MessageId : null,
                i + 1 < members.Count ? members[i + 1].MessageId : null);
        }
        return (null, null);
    }

    private MessageItem? FindPreviewItem(string peer, string id)
    {
        if (_view == 0 || !IsVisible(_view) || !_attachmentModel.IsChatContentVisible ||
            _attachmentModel.SelectedConversation?.Uuid != peer) return null;
        var item = _attachmentModel.Messages.FirstOrDefault(row => row.MessageId == id &&
            row.Source.ConversationUuid == peer && !row.IsRevoked &&
            EncryptedObjectStore.IsReference(row.Content) && row.AttachmentInfo is not null);
        if (item?.Source.ExpiresAt is { } expiry && expiry <= DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()) return null;
        return item;
    }

    private void UpdateAttachmentPreview()
    {
        if (_attachmentView is not { } view) return;
        if (FindPreviewItem(view.Peer, view.Message) is not { } item)
        {
            DismissAttachmentView(view);
            return;
        }

        if (view.Playback is { } playback && (!IsPlayable(item) || playback.Reference != item.Content))
            ClearAttachmentPlayback(view);
        if (item.IsAvailable && item.IsImage && !IsPlayable(item) && view.ImageReference != item.Content && !view.Working)
        {
            view.ImageReference = item.Content;
            view.HasImage = false;
            view.Working = true;
            view.Status = AppStrings.Get("UiOpeningPhoto");
            PublishAttachmentView(view, item);
            TrackAttachmentWork(DecodePhotoAsync(view, item));
        }
        else
        {
            if (!item.IsAvailable)
            {
                view.ImageReference = null;
                view.HasImage = false;
            }
            PublishAttachmentView(view, item);
        }
    }

    private async Task DecodePhotoAsync(AttachmentView view, MessageItem item)
    {
        var token = view.Lifetime.Token;
        PhotoPixels? photo = null;
        try
        {
            photo = await Task.Run(() =>
            {
                token.ThrowIfCancellationRequested();
                if (!_attachmentModel.CanReadAttachment(item)) return null;
                using var input = item.OpenMedia();
                var decoded = ImageDecoder.Load(input);
                token.ThrowIfCancellationRequested();
                if (!_attachmentModel.CanReadAttachment(item)) return null;
                var bitmap = new FormatConvertedBitmap(decoded, PixelFormats.Pbgra32, null, 0);
                var stride = checked(bitmap.PixelWidth * 4);
                var pixels = new byte[checked(stride * bitmap.PixelHeight)];
                try
                {
                    bitmap.CopyPixels(pixels, stride, 0);
                    return new PhotoPixels(pixels, bitmap.PixelWidth, bitmap.PixelHeight, stride);
                }
                catch
                {
                    CryptographicOperations.ZeroMemory(pixels);
                    throw;
                }
            }, token);
            if (!ReferenceEquals(_attachmentView, view) ||
                FindPreviewItem(view.Peer, view.Message) is not { IsAvailable: true } current ||
                current.Content != item.Content) return;
            view.Status = photo is null ? AppStrings.Get("UiPhotoUnavailable") : "";
            if (photo is not null)
            {
                PublishPhoto(view, photo);
                view.HasImage = true;
            }
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        catch (Exception error) when (error is IOException or InvalidOperationException or NotSupportedException or
            ArgumentException or COMException or CryptographicException or Microsoft.Data.Sqlite.SqliteException)
        {
            view.Status = AppStrings.Get("UiPhotoOpenError");
        }
        finally
        {
            if (photo is not null) CryptographicOperations.ZeroMemory(photo.Bytes);
            if (ReferenceEquals(_attachmentView, view))
            {
                view.Working = false;
                if (FindPreviewItem(view.Peer, view.Message) is { } current) PublishAttachmentView(view, current);
            }
        }
    }

    private unsafe void PublishPhoto(AttachmentView view, PhotoPixels photo)
    {
        fixed (byte* pixels = photo.Bytes)
            PresentPhoto(_view, view.Generation, pixels, photo.Width, photo.Height, photo.Stride);
    }

    private async Task ChangeDownloadAsync(AttachmentView view, MessageItem item)
    {
        var token = view.Lifetime.Token;
        try
        {
            if (!await _attachmentModel.ChangeAttachmentDownloadAsync(item, item.CanRequestDownload, token))
                view.Status = AppStrings.Get("UiFileDetailsUnavailable");
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
        {
            view.Status = AppStrings.Get("UiFileDetailsUnavailable");
        }
        finally
        {
            view.Working = false;
            if (ReferenceEquals(_attachmentView, view)) UpdateAttachmentPreview();
        }
    }

    private async Task ExportPreviewAsync(AttachmentView view, MessageItem item, string destination)
    {
        var token = view.Lifetime.Token;
        var saved = await AttachmentExporter.ExportToAsync(item, destination,
            () => !token.IsCancellationRequested && _attachmentModel.CanExportAttachment(item), token);
        if (!ReferenceEquals(_attachmentView, view)) return;
        view.Working = false;
        view.Status = AppStrings.Get(saved ? "UiAttachmentExported" : "UiSharedMediaSaveFailed");
        UpdateAttachmentPreview();
    }

    private void PublishAttachmentView(AttachmentView view, MessageItem item)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var filename = string.Concat(item.Filename.Select(ch => invalid.Contains(ch) ? '_' : ch)).TrimEnd(' ', '.');
        var (previous, next) = AlbumNeighbors(item);
        var playbackFailed = view.PlaybackFailure == item.Content;
        var status = playbackFailed ? AppStrings.Get("UiMediaPlaybackUnavailable") : item.TransferText;
        var frame = new AttachmentViewFrame(view.Generation, view.Message, string.IsNullOrWhiteSpace(filename) ? "attachment.bin" : filename,
            item.Filename, item.FileSizeText, view.Status.Length == 0 ? status : view.Status,
            !item.IsAvailable && (item.CanRequestDownload || item.CanPauseDownload) ? item.FileActionText : "",
            item.IsAvailable && !item.HasExpiration ? AppStrings.Get("UiSaveAttachment") : "",
            AppStrings.Get("UiClose"), previous is null ? "" : AppStrings.Get("UiMediaPreviousAttachment"),
            next is null ? "" : AppStrings.Get("UiMediaNextAttachment"), !view.HasImage, view.Working,
            playback: IsPlayable(item) && !playbackFailed);
        if (view.LastFrame == frame) return;
        view.LastFrame = frame;
        PresentAttachmentView(frame);
    }

    private unsafe void PresentAttachmentView(AttachmentViewFrame frame)
    {
        if (_view == 0) return;
        var bytes = JsonSerializer.SerializeToUtf8Bytes(frame);
        try { fixed (byte* pointer = bytes) Present(_view, pointer, bytes.Length, 4); }
        finally { CryptographicOperations.ZeroMemory(bytes); }
    }

    private void ClearAttachmentPreview()
    {
        var view = _attachmentView;
        _attachmentView = null;
        if (view is null) return;
        view.Lifetime.Cancel();
        try { ClearAttachmentPlayback(view); }
        finally { view.Lifetime.Dispose(); }
    }
}
