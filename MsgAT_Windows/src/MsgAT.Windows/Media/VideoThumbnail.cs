// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Windows.Media.Imaging;
using MsgAT.Windows.Infrastructure;
using Windows.Graphics.DirectX;
using Windows.Graphics.Imaging;
using Windows.Media;
using Windows.Media.Core;
using Windows.Media.Editing;
using Windows.Media.Playback;
using Windows.Storage;
using Windows.Storage.Streams;
using NativeBitmapEncoder = Windows.Graphics.Imaging.BitmapEncoder;

namespace MsgAT.Windows.Media;

internal static class VideoThumbnail
{
    internal const int MaximumEdge = 384;
    private const long MaximumBytes = 100L * 1024 * 1024;
    private static readonly SemaphoreSlim DecoderSlot = new(1);

    public static async Task<BitmapSource?> LoadAsync(Func<Stream> open, string mime, CancellationToken cancellation)
    {
        if (mime is not ("video/mp4" or "video/quicktime" or "video/webm" or "video/x-matroska")) return null;
        await DecoderSlot.WaitAsync(cancellation).ConfigureAwait(false);
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            timeout.CancelAfter(TimeSpan.FromSeconds(5));
            return await Task.Run(() => ReadFirstFrameAsync(open, mime, timeout.Token), cancellation).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!cancellation.IsCancellationRequested) { return null; }
        catch (Exception error) when (error is COMException or InvalidOperationException or ArgumentException or IOException or NotSupportedException)
        { return null; }
        finally { DecoderSlot.Release(); }
    }

    internal static async Task<BitmapSource?[]> LoadTrimFramesAsync(string path, TimeSpan duration, CancellationToken cancellation)
    {
        const int count = 6;
        const int edge = 160;
        await DecoderSlot.WaitAsync(cancellation).ConfigureAwait(false);
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            timeout.CancelAfter(TimeSpan.FromSeconds(5));
            return await Task.Run(async () =>
            {
                var token = timeout.Token;
                var file = await StorageFile.GetFileFromPathAsync(path).AsTask(token).ConfigureAwait(false);
                var clip = await MediaClip.CreateFromFileAsync(file).AsTask(token).ConfigureAwait(false);
                var composition = new MediaComposition();
                composition.Clips.Add(clip);
                var ticks = Math.Min(duration.Ticks, composition.Duration.Ticks);
                if (ticks <= 0) return Array.Empty<BitmapSource?>();
                var frames = new BitmapSource?[count];
                for (var index = 0; index < count; index++)
                {
                    token.ThrowIfCancellationRequested();
                    using var thumbnail = await composition.GetThumbnailAsync(TimeSpan.FromTicks(ticks * index / count),
                        edge, edge, VideoFramePrecision.NearestKeyFrame).AsTask(token).ConfigureAwait(false);
                    using var input = thumbnail.AsStreamForRead();
                    var image = ImageDecoder.Load(input, edge);
                    image.Freeze();
                    frames[index] = image;
                }
                return frames;
            }, cancellation).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!cancellation.IsCancellationRequested) { return []; }
        catch (Exception error) when (error is COMException or InvalidOperationException or ArgumentException or IOException or NotSupportedException or UnauthorizedAccessException)
        { return []; }
        finally { DecoderSlot.Release(); }
    }

    private static bool HasContainerHeader(Stream input, string mime)
    {
        if (!input.CanSeek || input.Length is < 12 or > MaximumBytes) return false;
        Span<byte> header = stackalloc byte[12];
        input.ReadExactly(header);
        input.Position = 0;
        return mime is "video/mp4" or "video/quicktime"
            ? header[4..8].SequenceEqual("ftyp"u8)
            : header[..4].SequenceEqual(new byte[] { 0x1A, 0x45, 0xDF, 0xA3 });
    }

    private static async Task<BitmapSource?> ReadFirstFrameAsync(Func<Stream> open, string mime, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        using var input = open();
        if (!HasContainerHeader(input, mime)) return null;
        using var stream = input.AsRandomAccessStream();
        using var source = MediaSource.CreateFromStream(stream, mime);
        using var player = new MediaPlayer { IsVideoFrameServerEnabled = true, IsMuted = true, AutoPlay = false };
        player.CommandManager.IsEnabled = false;

        var opened = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var copied = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        void Opened(MediaPlayer sender, object args) => opened.TrySetResult(true);
        void Failed(MediaPlayer sender, MediaPlayerFailedEventArgs args)
        {
            opened.TrySetResult(false);
            copied.TrySetResult(false);
        }
        player.MediaOpened += Opened;
        player.MediaFailed += Failed;
        try
        {
            player.Source = source;
            if (!await opened.Task.WaitAsync(token).ConfigureAwait(false)) return null;
            var width = player.PlaybackSession.NaturalVideoWidth;
            var height = player.PlaybackSession.NaturalVideoHeight;
            if (width is 0 or > 4096 || height is 0 or > 4096 || (ulong)width * height > 4096UL * 2304) return null;
            using var frame = VideoFrame.CreateAsDirect3D11SurfaceBacked(
                DirectXPixelFormat.B8G8R8A8UIntNormalized, (int)width, (int)height);
            var surface = frame.Direct3DSurface;
            if (surface is null) return null;
            var frameGate = new object();
            var stopped = false;
            void Copy(MediaPlayer sender, object args)
            {
                lock (frameGate)
                {
                    if (stopped || copied.Task.IsCompleted) return;
                    try
                    {
                        sender.CopyFrameToVideoSurface(surface);
                        copied.TrySetResult(true);
                    }
                    catch (Exception error) when (error is COMException or InvalidOperationException or ArgumentException)
                    { copied.TrySetResult(false); }
                }
            }

            player.VideoFrameAvailable += Copy;
            try
            {
                player.Play();
                if (!await copied.Task.WaitAsync(token).ConfigureAwait(false)) return null;
                player.Pause();
                using var bitmap = await SoftwareBitmap.CreateCopyFromSurfaceAsync(surface, BitmapAlphaMode.Ignore)
                    .AsTask().ConfigureAwait(false);
                token.ThrowIfCancellationRequested();
                using var output = new InMemoryRandomAccessStream();
                var encoder = await NativeBitmapEncoder.CreateAsync(NativeBitmapEncoder.PngEncoderId, output).AsTask().ConfigureAwait(false);
                encoder.SetSoftwareBitmap(bitmap);
                var scale = Math.Min(1d, (double)MaximumEdge / Math.Max(bitmap.PixelWidth, bitmap.PixelHeight));
                encoder.BitmapTransform.ScaledWidth = Math.Max(1u, (uint)Math.Round(bitmap.PixelWidth * scale));
                encoder.BitmapTransform.ScaledHeight = Math.Max(1u, (uint)Math.Round(bitmap.PixelHeight * scale));
                await encoder.FlushAsync().AsTask().ConfigureAwait(false);
                token.ThrowIfCancellationRequested();
                output.Seek(0);
                using var pixels = output.AsStreamForRead();
                return ImageDecoder.Load(pixels, MaximumEdge);
            }
            finally
            {
                lock (frameGate) stopped = true;
                player.VideoFrameAvailable -= Copy;
            }
        }
        finally
        {
            player.MediaOpened -= Opened;
            player.MediaFailed -= Failed;
        }
    }
}
