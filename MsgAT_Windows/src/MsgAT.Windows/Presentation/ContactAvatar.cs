// SPDX-License-Identifier: AGPL-3.0-only
using System.Globalization;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Security.Cryptography;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Microsoft.UI.Xaml.Controls;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Presentation;

internal sealed class ContactAvatar
{
    private static readonly AvatarConverter Decoder = new();
    internal PersonPicture View { get; }
    private ImageBrush? _pending;
    private string? _path;

    internal ContactAvatar(double size) => View = new PersonPicture { Width = size, Height = size };

    internal void Set(string name, string? path)
    {
        View.DisplayName = name;
        if (path == _path) return;
        Clear();
        _path = path;
        if (string.IsNullOrEmpty(path)) return;
        _pending = Decoder.Convert(path, typeof(ImageBrush), null!, CultureInfo.InvariantCulture) as ImageBrush;
        if (_pending is null) return;
        _pending.Changed += ImageChanged;
        ImageChanged(null, EventArgs.Empty);
    }

    internal void Clear()
    {
        if (_pending is not null) _pending.Changed -= ImageChanged;
        _pending = null;
        _path = null;
        View.ProfilePicture = null;
    }

    private void ImageChanged(object? sender, EventArgs args)
    {
        if (_pending?.ImageSource is not BitmapSource source) return;
        var bitmap = source.Format == PixelFormats.Pbgra32 ? source :
            new FormatConvertedBitmap(source, PixelFormats.Pbgra32, null, 0);
        var stride = checked(bitmap.PixelWidth * 4);
        var pixels = new byte[checked(stride * bitmap.PixelHeight)];
        try
        {
            bitmap.CopyPixels(pixels, stride, 0);
            var picture = new Microsoft.UI.Xaml.Media.Imaging.WriteableBitmap(bitmap.PixelWidth, bitmap.PixelHeight);
            using (var output = picture.PixelBuffer.AsStream()) output.Write(pixels);
            picture.Invalidate();
            View.ProfilePicture = picture;
            _pending.Changed -= ImageChanged;
            _pending = null;
        }
        finally { CryptographicOperations.ZeroMemory(pixels); }
    }
}
