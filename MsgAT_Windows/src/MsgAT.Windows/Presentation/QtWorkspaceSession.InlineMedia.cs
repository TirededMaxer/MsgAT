// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.Media;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Presentation;

internal sealed partial class QtWorkspaceSession
{
    private sealed class InlineWork(string reference, bool animate) : IDisposable
    {
        private readonly CancellationTokenSource _lifetime = new();
        internal string Reference { get; } = reference;
        internal bool Animate { get; } = animate;
        internal CancellationToken Token => _lifetime.Token;
        public void Dispose()
        {
            _lifetime.Cancel();
            _lifetime.Dispose();
        }
    }

    private readonly Dictionary<string, InlineWork> _inlineMedia = new(StringComparer.Ordinal);
    private (string Peer, long Generation)? _inlineScope;

    [LibraryImport(Bridge, EntryPoint = "msgat_workspace_inline_image", StringMarshalling = StringMarshalling.Utf8)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.SafeDirectories)]
    private static unsafe partial void PresentInlinePhoto(nint view, long generation, string id, byte* pixels,
        int width, int height, int stride);

    private void UpdateInlineMedia(string peer, long generation, JsonElement candidates, bool animate)
    {
        if (_inlineScope != (peer, generation))
        {
            ClearInlineMedia();
            _inlineScope = (peer, generation);
        }
        var ids = candidates.EnumerateArray().Take(16).Select(id => id.GetString()).OfType<string>().ToHashSet(StringComparer.Ordinal);
        foreach (var id in _inlineMedia.Keys.Where(id => !ids.Contains(id)).ToArray()) RemoveInlineMedia(id);

        var animatedSlots = 2;
        foreach (var id in ids)
        {
            if (FindPreviewItem(peer, id) is not { IsAvailable: true } item || !QtWorkspaceFrame.HasInlineMedia(item))
            {
                RemoveInlineMedia(id);
                continue;
            }
            var animateItem = animate && item.IsSticker && animatedSlots-- > 0;
            if (_inlineMedia.TryGetValue(id, out var previous) && previous.Reference == item.Content &&
                previous.Animate == animateItem) continue;
            RemoveInlineMedia(id);
            var work = new InlineWork(item.Content, animateItem);
            _inlineMedia.Add(id, work);
            TrackAttachmentWork(LoadInlineMediaAsync(peer, generation, item, work));
        }
    }

    private void RemoveInlineMedia(string id)
    {
        if (_inlineMedia.Remove(id, out var previous)) previous.Dispose();
    }

    private void ClearInlineMedia()
    {
        foreach (var work in _inlineMedia.Values) work.Dispose();
        _inlineMedia.Clear();
        _inlineScope = null;
    }

    private async Task LoadInlineMediaAsync(string peer, long generation, MessageItem item, InlineWork work)
    {
        var token = work.Token;
        StickerImageView.Decoder? animation = null;
        try
        {
            if (work.Animate)
                animation = await Task.Run(() => _model.CanReadAttachment(item)
                    ? StickerImageView.TryOpenDecoder(item.ReadStickerBytes) : null, token);
            if (!Current()) return;
            if (animation is null)
            {
                if (!await Task.Run(() => _model.CanReadAttachment(item), token)) return;
                var bitmap = await AttachmentThumbnail.LoadAsync(item, item.IsSticker ? 512 : 640, token);
                if (bitmap is not null)
                    await SendFrameAsync(bitmap);
                return;
            }
            while (Current())
            {
                var frame = await Task.Run(animation.Next, token);
                await SendFrameAsync(frame.Image);
                if (!frame.Animated || !IsActive) break;
                await Task.Delay(Math.Max(40, frame.Delay), token);
            }
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        catch (Exception error) when (error is IOException or InvalidOperationException or NotSupportedException or
            ArgumentException or ExternalException or CryptographicException or Microsoft.Data.Sqlite.SqliteException or UnauthorizedAccessException)
        {
            System.Diagnostics.Trace.TraceInformation("Inline media preview unavailable ({0}).", error.GetType().Name);
        }
        finally
        {
            if (animation is not null) await Task.Run(animation.Dispose);
        }

        bool Current() => !token.IsCancellationRequested && _inlineScope == (peer, generation) &&
            _inlineMedia.TryGetValue(item.MessageId, out var current) && ReferenceEquals(current, work) &&
            FindPreviewItem(peer, item.MessageId) is { IsAvailable: true } visible && visible.Content == work.Reference;

        async Task SendFrameAsync(BitmapSource source)
        {
            var photo = await Task.Run(() =>
            {
                token.ThrowIfCancellationRequested();
                var bitmap = new FormatConvertedBitmap(source, PixelFormats.Pbgra32, null, 0);
                var stride = checked(bitmap.PixelWidth * 4);
                var bytes = new byte[checked(stride * bitmap.PixelHeight)];
                try
                {
                    bitmap.CopyPixels(bytes, stride, 0);
                    return new PhotoPixels(bytes, bitmap.PixelWidth, bitmap.PixelHeight, stride);
                }
                catch
                {
                    CryptographicOperations.ZeroMemory(bytes);
                    throw;
                }
            }, token);
            try
            {
                if (Current()) PublishInlineMedia(generation, item.MessageId, photo);
            }
            finally { CryptographicOperations.ZeroMemory(photo.Bytes); }
        }
    }

    private unsafe void PublishInlineMedia(long generation, string id, PhotoPixels photo)
    {
        fixed (byte* data = photo.Bytes) PresentInlinePhoto(_view, generation, id, data, photo.Width, photo.Height, photo.Stride);
    }
}
