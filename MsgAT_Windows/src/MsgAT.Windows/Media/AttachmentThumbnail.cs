// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Windows.Media.Imaging;
using Microsoft.Data.Sqlite;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Media;

internal static class AttachmentThumbnail
{
    internal static async Task<BitmapSource?> LoadAsync(MessageItem message, int photoEdge, CancellationToken token)
    {
        var edge = message.IsVideo ? VideoThumbnail.MaximumEdge : photoEdge;
        var store = EncryptedObjectStore.IsReference(message.Content) ? message.ThumbnailStore : null;
        using var ownership = store?.RetainThumbnailWork();
        if (store is not null)
        {
            if (ownership is null) return null;
            var cached = await Task.Run(() => Read(store, message.Content, edge), token).ConfigureAwait(false);
            token.ThrowIfCancellationRequested();
            if (cached is not null) return cached;
        }

        var bitmap = message.IsVideo
            ? await VideoThumbnail.LoadAsync(message.OpenMedia, message.Mime, token).ConfigureAwait(false)
            : await Task.Run(() =>
            {
                using var input = message.OpenMedia();
                return ImageDecoder.Load(input, edge);
            }, token).ConfigureAwait(false);
        if (bitmap is not null && store is not null)
        {
            var opaque = message.IsVideo || message.Mime == "image/jpeg";
            await Task.Run(() => CacheAsync(store, message.Content, edge, bitmap, opaque, token), token).ConfigureAwait(false);
        }
        return bitmap;
    }

    private static BitmapSource? Read(EncryptedObjectStore store, string reference, int edge)
    {
        byte[]? encoded = null;
        try
        {
            encoded = store.ReadThumbnail(reference, edge);
            if (encoded is null) return null;
            using var input = new MemoryStream(encoded, writable: false);
            return ImageDecoder.Load(input, edge);
        }
        catch (Exception error) when (IsCacheFailure(error)) { return null; }
        finally { if (encoded is not null) CryptographicOperations.ZeroMemory(encoded); }
    }

    private static async Task CacheAsync(EncryptedObjectStore store, string reference, int edge, BitmapSource bitmap,
        bool opaque, CancellationToken token)
    {
        try
        {
            BitmapEncoder encoder = opaque ? new JpegBitmapEncoder { QualityLevel = 86 } : new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using var output = new MemoryStream();
            encoder.Save(output);
            token.ThrowIfCancellationRequested();
            if (output.Length > 131_072) return;
            var encoded = output.ToArray();
            try { await store.CacheThumbnailAsync(reference, edge, encoded, opaque ? "image/jpeg" : "image/png", token).ConfigureAwait(false); }
            finally { CryptographicOperations.ZeroMemory(encoded); }
        }
        catch (Exception error) when (IsCacheFailure(error)) { }
    }

    private static bool IsCacheFailure(Exception error) => error is SqliteException or IOException or COMException or
        NotSupportedException or ArgumentException or InvalidOperationException or CryptographicException or UnauthorizedAccessException;
}
