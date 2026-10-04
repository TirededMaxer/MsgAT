using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace MsgAT.Windows.Infrastructure;

public static class AvatarProcessor
{
    private const int MaximumDimension = 256;
    private const int MaximumBytes = 128 * 1024;

    public static byte[] EncodeJpeg(string sourcePath)
    {
        var frame = ImageDecoder.Load(sourcePath);
        var scale = Math.Min(1d, Math.Min((double)MaximumDimension / frame.PixelWidth, (double)MaximumDimension / frame.PixelHeight));
        BitmapSource bitmap = scale < 1d
            ? new TransformedBitmap(frame, new ScaleTransform(scale, scale))
            : frame;

        foreach (var quality in new[] { 86, 72, 58 })
        {
            var encoder = new JpegBitmapEncoder { QualityLevel = quality };
            encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using var output = new MemoryStream();
            encoder.Save(output);
            if (output.Length <= MaximumBytes) { return output.ToArray(); }
        }
        throw new InvalidDataException("The selected avatar cannot be reduced to the profile size limit.");
    }
}
