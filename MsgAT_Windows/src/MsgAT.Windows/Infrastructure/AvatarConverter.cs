using System.Globalization;
using System.Windows.Data;
using System.Windows.Media;

namespace MsgAT.Windows.Infrastructure;

public sealed class AvatarConverter : IValueConverter
{
    public object? Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        _ = targetType;
        _ = parameter;
        _ = culture;
        if (value is not string path || !File.Exists(path)) { return null; }
        try
        {
            var image = ImageDecoder.Load(path, 256);
            return new ImageBrush(image) { Stretch = Stretch.UniformToFill };
        }
        catch (Exception exception) when (exception is IOException or NotSupportedException) { return null; }
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
