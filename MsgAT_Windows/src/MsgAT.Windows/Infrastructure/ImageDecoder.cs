using System.Windows.Media;
using System.Windows.Media.Imaging;
using SkiaSharp;

namespace MsgAT.Windows.Infrastructure;

public static class ImageDecoder
{
    public static BitmapSource Load(string path, int maximumDimension = 4096)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(maximumDimension, 1);
        using var input = File.OpenRead(path);
        Span<byte> header = stackalloc byte[12];
        var headerSize = input.Read(header);
        input.Position = 0;
        var isWebP = headerSize == 12 && header[..4].SequenceEqual("RIFF"u8) && header[8..12].SequenceEqual("WEBP"u8);
        if (!isWebP)
        {
            var metadata = BitmapDecoder.Create(input, BitmapCreateOptions.DelayCreation, BitmapCacheOption.None).Frames[0];
            var ratio = Math.Min(1d, (double)maximumDimension / Math.Max(metadata.PixelWidth, metadata.PixelHeight));
            input.Position = 0;
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            if (ratio < 1) image.DecodePixelWidth = Math.Max(1, (int)(metadata.PixelWidth * ratio));
            image.StreamSource = input;
            image.EndInit();
            image.Freeze();
            return image;
        }

        using var codec = SKCodec.Create(input) ?? throw new InvalidDataException("Image could not be decoded.");
        var longestSide = Math.Max(codec.Info.Width, codec.Info.Height);
        if (longestSide < 1) throw new InvalidDataException("Image has invalid dimensions.");
        var dimensions = codec.GetScaledDimensions(Math.Min(1f, (float)maximumDimension / longestSide));
        var info = new SKImageInfo(dimensions.Width, dimensions.Height, SKColorType.Bgra8888, SKAlphaType.Premul);
        using var decoded = SKBitmap.Decode(codec, info) ?? throw new InvalidDataException("Image could not be decoded.");
        var bitmap = BitmapSource.Create(decoded.Width, decoded.Height, 96, 96, PixelFormats.Pbgra32,
            null, decoded.GetPixels(), decoded.ByteCount, decoded.RowBytes);
        bitmap.Freeze();
        return bitmap;
    }
}
