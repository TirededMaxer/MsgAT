using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using global::Windows.UI.ViewManagement;

namespace MsgAT.Windows.Infrastructure;

public enum AppThemeMode { System, Light, Dark }

internal static class WindowAppearance
{
    private const int UseImmersiveDarkMode = 20;
    private const int WindowCornerPreference = 33;
    private const int RoundCornerPreference = 2;
    private const int CaptionColor = 35;
    private const int CaptionTextColor = 36;
    private const uint ExcludeFromCapture = 0x00000011;

    private static readonly (string Resource, int Rgb)[] LightPalette =
    [
        ("WindowColor", 0xF6F8FA), ("PanelColor", 0xEDF2F5), ("SurfaceColor", 0xFFFFFF),
        ("RaisedColor", 0xE0EAF0), ("BorderColor", 0xC3D0DA), ("TextColor", 0x172832),
        ("MutedColor", 0x4E6371), ("AccentColor", 0xA9E9D3), ("AccentHoverColor", 0x8BDEC1),
        ("AccentTextColor", 0x08664A), ("ConnectionDotColor", 0x169257), ("ConnectionColor", 0x0B6A45),
        ("DangerColor", 0xA62339),
        ("MessagingTimelineColor", 0xF6F8F7), ("MessagingChromeColor", 0xFFFFFF),
        ("MessagingSidebarColor", 0xEDF2EF), ("MessagingSelectionColor", 0xE4EFE9),
        ("MessagingDividerColor", 0xD5DFDA), ("MessagingSelectionMarkColor", 0x126741),
        ("MessagingIncomingBubbleColor", 0xFFFFFF), ("MessagingOutgoingBubbleColor", 0xDCEFE4),
        ("MessagingMetadataColor", 0x4E6159)
    ];
    private static Color[]? _darkPalette;
    private static UISettings? _systemSettings;
    private static bool _dark = true;
    public static AppThemeMode Theme { get; private set; }

    public static void StartObserving()
    {
        try
        {
            _systemSettings = new UISettings();
            _systemSettings.ColorValuesChanged += SystemColorsChanged;
        }
        catch (COMException) { _systemSettings = null; }
        SetTheme(Theme);
    }

    public static void StopObserving()
    {
        if (_systemSettings is not null) _systemSettings.ColorValuesChanged -= SystemColorsChanged;
        _systemSettings = null;
    }

    private static void SystemColorsChanged(UISettings sender, object args)
    {
        _ = sender;
        _ = args;
        _ = Application.Current?.Dispatcher.InvokeAsync(() =>
        {
            if (Theme == AppThemeMode.System) SetTheme(Theme);
        });
    }

    public static void SetTheme(AppThemeMode mode)
    {
        Theme = Enum.IsDefined(mode) ? mode : AppThemeMode.System;
        var dark = Theme == AppThemeMode.Dark || (Theme == AppThemeMode.System && IsSystemDark());
        if (dark == _dark) return;
        var app = Application.Current;
        var resources = app.Resources;
        var darkPalette = _darkPalette ??= LightPalette.Select(entry => (Color)resources[entry.Resource]).ToArray();
        _dark = dark;
        for (var index = 0; index < LightPalette.Length; index++)
        {
            var (resource, rgb) = LightPalette[index];
            resources[resource] = dark ? darkPalette[index] : Color.FromRgb((byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb);
        }
        foreach (Window window in app.Windows) Refresh(window);
    }

    private static bool IsSystemDark()
    {
        if (_systemSettings is { } settings)
        {
            var foreground = settings.GetColorValue(UIColorType.Foreground);
            return 5 * foreground.G + 2 * foreground.R + foreground.B > 8 * 128;
        }
        var background = SystemColors.WindowColor;
        return 5 * background.G + 2 * background.R + background.B < 8 * 128;
    }

    public static void Apply(Window window)
    {
        window.SourceInitialized += (_, _) =>
        {
            var handle = new WindowInteropHelper(window).Handle;
            _ = SetWindowDisplayAffinity(handle, ExcludeFromCapture);
            Refresh(window);
            var corners = RoundCornerPreference;
            _ = DwmSetWindowAttribute(handle, WindowCornerPreference, ref corners, sizeof(int));
        };
    }

    private static void Refresh(Window window)
    {
        var handle = new WindowInteropHelper(window).Handle;
        if (handle == 0) return;
        var resources = Application.Current.Resources;
        var background = (Color)resources["WindowColor"];
        var foreground = (Color)resources["TextColor"];
        if (HwndSource.FromHwnd(handle)?.CompositionTarget is { } target) target.BackgroundColor = background;
        var dark = _dark ? 1 : 0;
        var caption = ColorRef(background);
        var captionText = ColorRef(foreground);
        _ = DwmSetWindowAttribute(handle, UseImmersiveDarkMode, ref dark, sizeof(int));
        _ = DwmSetWindowAttribute(handle, CaptionColor, ref caption, sizeof(int));
        _ = DwmSetWindowAttribute(handle, CaptionTextColor, ref captionText, sizeof(int));
    }

    private static int ColorRef(Color color) => color.R | (color.G << 8) | (color.B << 16);

    [DllImport("dwmapi.dll", ExactSpelling = true)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static extern int DwmSetWindowAttribute(nint window, int attribute, ref int value, int valueSize);

    [DllImport("user32.dll", ExactSpelling = true)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowDisplayAffinity(nint window, uint affinity);
}
