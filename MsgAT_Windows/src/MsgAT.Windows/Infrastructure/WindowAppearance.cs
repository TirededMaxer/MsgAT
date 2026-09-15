using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace MsgAT.Windows.Infrastructure;

internal static class WindowAppearance
{
    private const int UseImmersiveDarkMode = 20;
    private const int WindowCornerPreference = 33;
    private const int RoundCornerPreference = 2;

    public static void Apply(Window window)
    {
        window.SourceInitialized += (_, _) =>
        {
            var handle = new WindowInteropHelper(window).Handle;
            if (HwndSource.FromHwnd(handle)?.CompositionTarget is { } target &&
                window.Background is System.Windows.Media.SolidColorBrush background)
            {
                target.BackgroundColor = background.Color;
            }
            var enabled = 1;
            _ = DwmSetWindowAttribute(handle, UseImmersiveDarkMode, ref enabled, sizeof(int));
            var corners = RoundCornerPreference;
            _ = DwmSetWindowAttribute(handle, WindowCornerPreference, ref corners, sizeof(int));
        };
    }

    [DllImport("dwmapi.dll", ExactSpelling = true)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static extern int DwmSetWindowAttribute(nint window, int attribute, ref int value, int valueSize);
}
