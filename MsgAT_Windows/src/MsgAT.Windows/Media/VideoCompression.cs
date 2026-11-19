// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Foundation;
using Windows.Media.MediaProperties;
using Windows.Media.Transcoding;
using Windows.Storage;
using Windows.Storage.FileProperties;
using Windows.Storage.Streams;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Media;

internal static class VideoCompression
{
    private const ulong MaximumInput = 100UL * 1024 * 1024;
    private const uint AudioBitrate = 96_000;

    public static async Task<TimeSpan?> GetTrimDurationAsync(string path, CancellationToken token)
    {
        var file = await DrainAsync(StorageFile.GetFileFromPathAsync(path), token).ConfigureAwait(false);
        using var input = await DrainAsync(file.OpenAsync(FileAccessMode.Read), token).ConfigureAwait(false);
        var source = await InspectAsync(file, input, token).ConfigureAwait(false);
        return source?.Duration;
    }

    public static async Task<EncryptedCaptureFile?> EncodeFileAsync(EncryptedCaptureStore staging, string path,
        VideoTrim? trim, bool compress,
        IProgress<double>? progress, CancellationToken token)
    {
        EncryptedCaptureFile? output = null;
        var transferred = false;
        using var lifetime = new EncoderLifetime(token);
        try
        {
            var file = await DrainAsync(StorageFile.GetFileFromPathAsync(path), token).ConfigureAwait(false);
            using var input = await DrainAsync(file.OpenAsync(FileAccessMode.Read), token).ConfigureAwait(false);
            var inspected = await InspectAsync(file, input, token).ConfigureAwait(false);
            if (inspected is not { } source || trim is not null && !trim.Fits(source.Duration)) return null;
            var originalSize = (long)input.Size;
            var outputLimit = trim is null ? originalSize - 1 : (long)MaximumInput;
            var clipDuration = trim is null ? source.Duration : trim.End - trim.Start;
            var muteAudio = trim?.MuteAudio == true;
            var profile = TargetProfile(source.Profile, originalSize, source.Duration, outputLimit, clipDuration, compress, muteAudio);
            if (profile is null) return null;
            input.Seek(0);
            token.ThrowIfCancellationRequested();

            var filename = trim is null ? Path.ChangeExtension(Path.GetFileName(path), ".mp4")
                : string.Concat(Path.GetFileNameWithoutExtension(path).Take(160)) + (muteAudio ? "-muted.mp4" : "-trimmed.mp4");
            var staged = await staging.OpenAsync(filename, "video/mp4", lifetime.StopAsync, outputLimit)
                .ConfigureAwait(false);
            output = staged;
            var encoded = await lifetime.RunAsync(ct => EncodeAsync(input, staged, profile, trim, progress, ct)).ConfigureAwait(false);
            token.ThrowIfCancellationRequested();
            if (!encoded || staged.Size == 0) return null;
            await staged.FreezeAsync().ConfigureAwait(false);
            if (muteAudio)
            {
                using var readback = staged.OpenRead();
                using var outputStream = readback.AsRandomAccessStream();
                var exported = await DrainAsync(MediaEncodingProfile.CreateFromStreamAsync(outputStream), token).ConfigureAwait(false);
                if (exported.Audio is not null || exported.GetAudioTracks().Count != 0) return null;
            }
            token.ThrowIfCancellationRequested();
            transferred = true;
            return staged;
        }
        catch (Exception error) when (error is COMException or IOException or ArgumentException or NotSupportedException or UnauthorizedAccessException)
        {
            token.ThrowIfCancellationRequested();
            return null;
        }
        finally
        {
            if (!transferred && output is not null) await output.DisposeAsync().ConfigureAwait(false);
        }
    }

    private static async Task<(MediaEncodingProfile Profile, TimeSpan Duration)?> InspectAsync(
        StorageFile file, IRandomAccessStream input, CancellationToken token)
    {
        if (input.Size is <= 1 or > MaximumInput) return null;
        var properties = await DrainAsync(file.Properties.GetVideoPropertiesAsync(), token).ConfigureAwait(false);
        if (properties.Duration <= TimeSpan.Zero || properties.Duration > TimeSpan.FromMinutes(10) ||
            properties.Orientation != VideoOrientation.Normal) return null;
        var source = await DrainAsync(MediaEncodingProfile.CreateFromStreamAsync(input), token).ConfigureAwait(false);
        token.ThrowIfCancellationRequested();
        if (source.Video is not { } video || video.Width < 2 || video.Height < 2 ||
            (ulong)video.Width * video.Height > 4096UL * 2304 ||
            source.GetVideoTracks().Count > 1 || source.GetAudioTracks().Count > 1 ||
            source.GetTimedMetadataTracks().Count > 0) return null;
        return (source, properties.Duration);
    }

    private static MediaEncodingProfile? TargetProfile(MediaEncodingProfile source, long size, TimeSpan duration,
        long outputLimit, TimeSpan clipDuration, bool compress, bool muteAudio)
    {
        var video = source.Video;
        var audioRate = source.Audio is null || muteAudio ? 0 : AudioBitrate;
        var estimatedRate = size * 8d / duration.TotalSeconds;
        var requestedRate = compress ? Math.Min(2_500_000, estimatedRate * .82 - audioRate)
            : Math.Clamp(estimatedRate - audioRate, 400_000, 10_000_000);
        var bitrate = Math.Min(requestedRate, outputLimit * 8d * .82 / clipDuration.TotalSeconds - audioRate);
        if (bitrate < 150_000) return null;
        var maximumEdge = compress ? 1280 : 2160;
        var scale = Math.Min(1d, (double)maximumEdge / Math.Max(video.Width, video.Height));
        var width = Math.Max(2u, (uint)(video.Width * scale) & ~1u);
        var height = Math.Max(2u, (uint)(video.Height * scale) & ~1u);

        var output = MediaEncodingProfile.CreateMp4(compress ? VideoEncodingQuality.HD720p : VideoEncodingQuality.HD1080p);
        output.Video.Width = width;
        output.Video.Height = height;
        output.Video.Bitrate = (uint)bitrate;
        var sourceRate = video.FrameRate;
        if (sourceRate.Denominator > 0 && sourceRate.Numerator > 0 &&
            sourceRate.Numerator / (double)sourceRate.Denominator <= 30)
        {
            output.Video.FrameRate.Numerator = sourceRate.Numerator;
            output.Video.FrameRate.Denominator = sourceRate.Denominator;
        }
        if (video.PixelAspectRatio.Numerator > 0 && video.PixelAspectRatio.Denominator > 0)
        {
            output.Video.PixelAspectRatio.Numerator = video.PixelAspectRatio.Numerator;
            output.Video.PixelAspectRatio.Denominator = video.PixelAspectRatio.Denominator;
        }
        if (source.Audio is null || muteAudio) output.Audio = null;
        else
        {
            output.Audio.Bitrate = AudioBitrate;
            output.Audio.ChannelCount = source.Audio.ChannelCount == 1 ? 1u : 2u;
        }
        return output;
    }

    private static async Task<bool> EncodeAsync(IRandomAccessStream input, EncryptedCaptureFile staged,
        MediaEncodingProfile profile, VideoTrim? trim, IProgress<double>? progress, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        using var writer = staged.OpenEncoderStream();
        using var destination = writer.AsRandomAccessStream();
        var transcoder = new MediaTranscoder { AlwaysReencode = true };
        if (trim is not null)
        {
            transcoder.TrimStartTime = trim.Start;
            transcoder.TrimStopTime = trim.End;
        }
        var prepared = await DrainAsync(transcoder.PrepareStreamTranscodeAsync(input, destination, profile), token)
            .ConfigureAwait(false);
        if (!prepared.CanTranscode) return false;
        token.ThrowIfCancellationRequested();
        var operation = prepared.TranscodeAsync();
        using var cancel = token.Register(() => RequestCancel(operation));
        // Await native completion even after a cancellation request.
        await operation.AsTask(CancellationToken.None, progress).ConfigureAwait(false);
        token.ThrowIfCancellationRequested();
        return true;
    }

    private static async Task<T> DrainAsync<T>(IAsyncOperation<T> operation, CancellationToken token)
    {
        using var cancel = token.Register(() => RequestCancel(operation));
        var result = await operation.AsTask().ConfigureAwait(false);
        return result;
    }

    private static void RequestCancel(IAsyncInfo operation)
    {
        try { operation.Cancel(); }
        catch (Exception error) when (error is COMException or InvalidOperationException) { }
    }

    private sealed class EncoderLifetime(CancellationToken parent) : IDisposable
    {
        private readonly object _gate = new();
        private readonly CancellationTokenSource _stop = CancellationTokenSource.CreateLinkedTokenSource(parent);
        private Task<bool>? _active;
        private bool _closing;

        public Task<bool> RunAsync(Func<CancellationToken, Task<bool>> encode)
        {
            lock (_gate)
            {
                if (_closing) throw new OperationCanceledException();
                return _active = Task.Run(() => encode(_stop.Token));
            }
        }

        public async Task StopAsync()
        {
            Task? active;
            lock (_gate)
            {
                _closing = true;
                active = _active;
            }
            _stop.Cancel();
            if (active is not null)
            {
                try { await active.ConfigureAwait(false); }
                catch (Exception) { /* The sender observes the worker result. Shutdown only waits for its release. */ }
            }
        }

        public void Dispose() => _stop.Dispose();
    }
}
