// SPDX-License-Identifier: AGPL-3.0-only
using System.Buffers.Binary;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Security.Cryptography;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Windows.Foundation;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;
using Rect = System.Windows.Rect;
using NativeDecoder = Windows.Graphics.Imaging.BitmapDecoder;
using NativeEncoder = Windows.Graphics.Imaging.BitmapEncoder;

namespace MsgAT.Windows.Infrastructure;

internal enum PhotoCropRatio
{
    Free, Original, Square, LandscapePhoto, PortraitPhoto, LandscapeClassic, PortraitClassic, Wide, Tall
}

internal readonly record struct PhotoAdjustment(Rect Crop, int Turns, bool Mirrored, PhotoCropRatio Ratio)
{
    private static readonly Rect WholePhoto = new(0, 0, 1, 1);
    public static PhotoAdjustment Original => new(WholePhoto, 0, false, PhotoCropRatio.Free);
    public bool ChangesPixels => Crop != WholePhoto || Turns != 0 || Mirrored;

    public PhotoAdjustment Rotate(bool clockwise) => this with
    {
        Crop = clockwise ? new Rect(1 - Crop.Bottom, Crop.Left, Crop.Height, Crop.Width)
            : new Rect(Crop.Top, 1 - Crop.Right, Crop.Height, Crop.Width),
        Turns = (Turns + (clockwise != Mirrored ? 1 : 3)) % 4,
        Ratio = Ratio switch
        {
            PhotoCropRatio.LandscapePhoto => PhotoCropRatio.PortraitPhoto,
            PhotoCropRatio.PortraitPhoto => PhotoCropRatio.LandscapePhoto,
            PhotoCropRatio.LandscapeClassic => PhotoCropRatio.PortraitClassic,
            PhotoCropRatio.PortraitClassic => PhotoCropRatio.LandscapeClassic,
            PhotoCropRatio.Wide => PhotoCropRatio.Tall,
            PhotoCropRatio.Tall => PhotoCropRatio.Wide,
            _ => Ratio
        }
    };

    public PhotoAdjustment Flip() => this with
    {
        Crop = new Rect(1 - Crop.Right, Crop.Top, Crop.Width, Crop.Height),
        Mirrored = !Mirrored
    };

    public static (int Width, int Height) RatioDimensions(PhotoCropRatio ratio) => ratio switch
    {
        PhotoCropRatio.Square => (1, 1),
        PhotoCropRatio.LandscapePhoto => (3, 2),
        PhotoCropRatio.PortraitPhoto => (2, 3),
        PhotoCropRatio.LandscapeClassic => (4, 3),
        PhotoCropRatio.PortraitClassic => (3, 4),
        PhotoCropRatio.Wide => (16, 9),
        PhotoCropRatio.Tall => (9, 16),
        _ => throw new ArgumentOutOfRangeException(nameof(ratio))
    };

    public double? NormalizedRatio(double imageAspect)
    {
        if (Ratio == PhotoCropRatio.Free) return null;
        if (Ratio == PhotoCropRatio.Original) return 1;
        var (width, height) = RatioDimensions(Ratio);
        return width / (height * imageAspect);
    }

    public PhotoAdjustment WithRatio(PhotoCropRatio selection, double imageAspect)
    {
        var updated = this with { Ratio = selection };
        if (updated.NormalizedRatio(imageAspect) is not { } aspect) return updated;
        var width = Math.Min(1, aspect);
        var height = Math.Min(1, 1 / aspect);
        return updated with
        {
            Crop = new Rect(Math.Clamp(Crop.Left + (Crop.Width - width) / 2, 0, 1 - width),
                Math.Clamp(Crop.Top + (Crop.Height - height) / 2, 0, 1 - height), width, height)
        };
    }

    public Matrix DisplayTransform()
    {
        var matrix = Matrix.Identity;
        matrix.Rotate(Turns * 90);
        if (Mirrored) matrix.Scale(-1, 1);
        return matrix;
    }
}

internal static class PhotoCompression
{
    public const int MaximumInput = 32 * 1024 * 1024;
    private const int MaximumEdge = 2048;

    public static bool Supports(string mime) => mime is "image/jpeg" or "image/png";

    public static async Task<BitmapSource?> OpenForEditingAsync(string? path, byte[]? bytes, string mime, CancellationToken token)
    {
        if (!Supports(mime) || path is null && bytes is null) return null;
        using Stream source = path is not null
            ? new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read)
            : new MemoryStream(bytes!, writable: false);
        if (!CanDecode(source, mime, token)) return null;
        using var input = source.AsRandomAccessStream();
        var inspected = await InspectAsync(input, mime, 2560, token).ConfigureAwait(false);
        if (inspected is not { } photo) return null;
        var (decoder, transform) = photo;
        var png = mime == "image/png";
        var data = await decoder.GetPixelDataAsync(BitmapPixelFormat.Bgra8, png ? BitmapAlphaMode.Straight : BitmapAlphaMode.Ignore,
            transform, ExifOrientationMode.RespectExifOrientation, ColorManagementMode.ColorManageToSRgb)
            .AsTask(token).ConfigureAwait(false);
        var sideways = decoder.OrientedPixelWidth != decoder.PixelWidth;
        var width = (int)(sideways ? transform.ScaledHeight : transform.ScaledWidth);
        var height = (int)(sideways ? transform.ScaledWidth : transform.ScaledHeight);
        var stride = checked(width * 4);
        var pixels = data.DetachPixelData();
        try
        {
            if (pixels.Length != checked(stride * height)) return null;
            token.ThrowIfCancellationRequested();
            var format = png ? PixelFormats.Bgra32 : PixelFormats.Bgr32;
            var output = new WriteableBitmap(width, height, 96, 96, format, null);
            output.WritePixels(new Int32Rect(0, 0, width, height), pixels, stride, 0);
            output.Freeze();
            return output;
        }
        finally { CryptographicOperations.ZeroMemory(pixels); }
    }

    public static (byte[] Bytes, BitmapSource Preview) Edit(BitmapSource original, PhotoAdjustment adjustment, string mime)
    {
        var crop = adjustment.Crop;
        var transform = adjustment.DisplayTransform();
        var rotated = transform.IsIdentity ? original : new TransformedBitmap(original, new MatrixTransform(transform));
        var left = Math.Clamp((int)Math.Round(crop.Left * rotated.PixelWidth), 0, rotated.PixelWidth - 1);
        var top = Math.Clamp((int)Math.Round(crop.Top * rotated.PixelHeight), 0, rotated.PixelHeight - 1);
        var right = Math.Clamp((int)Math.Round(crop.Right * rotated.PixelWidth), left + 1, rotated.PixelWidth);
        var bottom = Math.Clamp((int)Math.Round(crop.Bottom * rotated.PixelHeight), top + 1, rotated.PixelHeight);
        var selected = new CroppedBitmap(rotated, new Int32Rect(left, top, right - left, bottom - top));
        System.Windows.Media.Imaging.BitmapEncoder encoder = mime == "image/png"
            ? new PngBitmapEncoder() : new JpegBitmapEncoder { QualityLevel = 95 };
        encoder.Frames.Add(BitmapFrame.Create(selected));
        using var output = new MemoryStream();
        try
        {
            encoder.Save(output);
            if (output.Length is <= 0 or > MaximumInput) throw new IOException("The edited photo is too large.");
            output.Position = 0;
            var preview = ImageDecoder.Load(output, 512);
            preview.Freeze();
            return (output.ToArray(), preview);
        }
        finally
        {
            if (output.TryGetBuffer(out var buffer)) CryptographicOperations.ZeroMemory(buffer.AsSpan());
        }
    }

    public static Task<byte[]?> CompressFileAsync(string path, string mime, CancellationToken token) =>
        Task.Run(async () =>
        {
            if (!Supports(mime)) return null;
            using var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            return await EncodeAsync(input, mime, token).ConfigureAwait(false);
        }, token);

    public static Task<byte[]?> CompressBytesAsync(ReadOnlyMemory<byte> source, string mime, CancellationToken token) =>
        Task.Run(async () =>
        {
            if (!Supports(mime) || source.Length is < 1 or > MaximumInput) return null;
            if (MemoryMarshal.TryGetArray(source, out var segment) && segment.Array is { } array)
            {
                using var input = new MemoryStream(array, segment.Offset, segment.Count, writable: false);
                return await EncodeAsync(input, mime, token).ConfigureAwait(false);
            }
            var bytes = source.ToArray();
            try
            {
                using var input = new MemoryStream(bytes, writable: false);
                return await EncodeAsync(input, mime, token).ConfigureAwait(false);
            }
            finally { CryptographicOperations.ZeroMemory(bytes); }
        }, token);

    private static async Task<byte[]?> EncodeAsync(Stream source, string mime, CancellationToken token)
    {
        var originalSize = source.Length;
        if (originalSize is < 1 or > MaximumInput) return null;
        var png = mime == "image/png";
        try
        {
            if (!CanDecode(source, mime, token)) return null;
            using var input = source.AsRandomAccessStream();
            var inspected = await InspectAsync(input, mime, MaximumEdge, token).ConfigureAwait(false);
            if (inspected is not { } photo) return null;
            using var bitmap = await photo.Decoder.GetSoftwareBitmapAsync(
                BitmapPixelFormat.Bgra8, png ? BitmapAlphaMode.Straight : BitmapAlphaMode.Ignore, photo.Transform,
                ExifOrientationMode.RespectExifOrientation, ColorManagementMode.ColorManageToSRgb)
                .AsTask(token).ConfigureAwait(false);

            using var output = new InMemoryRandomAccessStream();
            var encoder = png
                ? await NativeEncoder.CreateAsync(NativeEncoder.PngEncoderId, output).AsTask(token).ConfigureAwait(false)
                : await NativeEncoder.CreateAsync(NativeEncoder.JpegEncoderId, output, new BitmapPropertySet
                {
                    ["ImageQuality"] = new BitmapTypedValue(0.82f, PropertyType.Single)
                }).AsTask(token).ConfigureAwait(false);
            encoder.SetSoftwareBitmap(bitmap);
            await encoder.FlushAsync().AsTask(token).ConfigureAwait(false);
            if (output.Size == 0 || output.Size >= (ulong)originalSize) return null;
            output.Seek(0);
            using var stream = output.AsStreamForRead();
            var result = new byte[checked((int)output.Size)];
            try
            {
                await stream.ReadExactlyAsync(result.AsMemory(), token).ConfigureAwait(false);
                return result;
            }
            catch
            {
                CryptographicOperations.ZeroMemory(result);
                throw;
            }
        }
        catch (Exception error) when (error is IOException or COMException or ArgumentException or NotSupportedException)
        {
            return null;
        }
    }

    private static bool CanDecode(Stream source, string mime, CancellationToken token)
    {
        if (source.Length is < 1 or > MaximumInput) return false;
        token.ThrowIfCancellationRequested();
        if (mime == "image/png" && !IsStaticPng(source, token)) return false;
        source.Position = 0;
        return true;
    }

    private static async Task<(NativeDecoder Decoder, BitmapTransform Transform)?> InspectAsync(
        IRandomAccessStream input, string mime, int maximumEdge, CancellationToken token)
    {
        var decoder = await NativeDecoder.CreateAsync(input).AsTask(token).ConfigureAwait(false);
        var codec = mime == "image/png" ? NativeDecoder.PngDecoderId : NativeDecoder.JpegDecoderId;
        if (decoder.FrameCount != 1 || decoder.DecoderInformation.CodecId != codec) return null;
        var width = decoder.PixelWidth;
        var height = decoder.PixelHeight;
        if (width == 0 || height == 0 || (ulong)width * height > 64_000_000) return null;
        var scale = Math.Min(1d, (double)maximumEdge / Math.Max(width, height));
        var transform = new BitmapTransform
        {
            ScaledWidth = Math.Max(1u, (uint)(width * scale)),
            ScaledHeight = Math.Max(1u, (uint)(height * scale)),
            InterpolationMode = BitmapInterpolationMode.Fant
        };
        return (decoder, transform);
    }

    private static bool IsStaticPng(Stream source, CancellationToken token)
    {
        Span<byte> header = stackalloc byte[8];
        source.ReadExactly(header);
        if (BinaryPrimitives.ReadUInt64BigEndian(header) != 0x89504E470D0A1A0AUL) return false;
        while (source.Length - source.Position >= 12)
        {
            token.ThrowIfCancellationRequested();
            source.ReadExactly(header);
            var length = BinaryPrimitives.ReadUInt32BigEndian(header);
            if (length > source.Length - source.Position - 4) return false;
            if (header[4..].SequenceEqual("acTL"u8)) return false;
            if (header[4..].SequenceEqual("IDAT"u8)) return true;
            source.Seek(length + 4L, SeekOrigin.Current);
        }
        return false;
    }
}
