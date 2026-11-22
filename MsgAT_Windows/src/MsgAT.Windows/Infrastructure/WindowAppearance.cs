using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Data;
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
        ("WindowColor", 0xF7F9FB), ("PanelColor", 0xEFF3F7), ("SurfaceColor", 0xFFFFFF),
        ("RaisedColor", 0xE9EFF4), ("BorderColor", 0xD4DDE5), ("TextColor", 0x192531),
        ("MutedColor", 0x526070), ("AccentColor", 0x80CDBA), ("AccentHoverColor", 0x6BC2AC),
        ("AccentTextColor", 0x006957), ("ConnectionDotColor", 0x117B65), ("ConnectionColor", 0x006957),
        ("DangerColor", 0xA62339),
        ("MessagingTimelineColor", 0xEFF3F7), ("MessagingChromeColor", 0xFFFFFF),
        ("MessagingSidebarColor", 0xF7F9FB), ("MessagingSelectionColor", 0xD5E8E7),
        ("MessagingDividerColor", 0xD4DDE5), ("MessagingSelectionMarkColor", 0x006957),
        ("MessagingIncomingBubbleColor", 0xFFFFFF), ("MessagingOutgoingBubbleColor", 0xD9EEE8),
        ("MessagingMetadataColor", 0x526070)
    ];
    private static (Color Dark, PaletteColor Source)[]? _palette;
    private static UISettings? _systemSettings;
    private static bool _dark = true;
    public static AppThemeMode Theme { get; private set; }
    internal static event EventHandler? Changed;

    public static void StartObserving()
    {
        _palette = BindPalette(Application.Current.Resources);
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
        if (dark == _dark) { Changed?.Invoke(null, EventArgs.Empty); return; }
        var app = Application.Current;
        var resources = app.Resources;
        var palette = _palette ??= BindPalette(resources);
        _dark = dark;
        for (var index = 0; index < LightPalette.Length; index++)
        {
            var (resource, rgb) = LightPalette[index];
            var (darkColor, source) = palette[index];
            var color = dark ? darkColor : Color.FromRgb((byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb);
            source.Value = color;
            resources[resource] = color;
        }
        foreach (Window window in app.Windows) Refresh(window);
        Changed?.Invoke(null, EventArgs.Empty);
    }

    private static (Color Dark, PaletteColor Source)[] BindPalette(ResourceDictionary resources)
    {
        return LightPalette.Select(entry =>
        {
            var color = (Color)resources[entry.Resource];
            var source = new PaletteColor { Value = color };
            var brush = (SolidColorBrush)resources[entry.Resource[..^5] + "Brush"];
            // Keep the same brush instances for controls that already resolved their resources.
            BindingOperations.SetBinding(brush, SolidColorBrush.ColorProperty, new Binding
            {
                Path = new PropertyPath(PaletteColor.ValueProperty),
                Source = source,
                Mode = BindingMode.OneWay
            });
            return (color, source);
        }).ToArray();
    }

    private sealed class PaletteColor : DependencyObject
    {
        public static readonly DependencyProperty ValueProperty =
            DependencyProperty.Register(nameof(Value), typeof(Color), typeof(PaletteColor));

        public Color Value
        {
            get => (Color)GetValue(ValueProperty);
            set => SetValue(ValueProperty, value);
        }
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
