// SPDX-License-Identifier: AGPL-3.0-only
using System.Collections.Concurrent;
using System.Globalization;
using System.Text;
using System.Windows;

namespace MsgAT.Windows.Infrastructure;

public enum AppLanguage { System, English, SimplifiedChinese }

internal static class AppStrings
{
    private static readonly ConcurrentDictionary<string, CompositeFormat> Formats = new(StringComparer.Ordinal);
    private static ResourceDictionary? _strings;
    private static string? _locale;
    public static AppLanguage Language { get; private set; }
    public static CultureInfo InterfaceCulture => Language switch
    {
        AppLanguage.English => CultureInfo.GetCultureInfo("en"),
        AppLanguage.SimplifiedChinese => CultureInfo.GetCultureInfo("zh-CN"),
        _ => CultureInfo.CurrentCulture
    };

    public static void SetLanguage(AppLanguage language)
    {
        Language = Enum.IsDefined(language) ? language : AppLanguage.System;
        var culture = CultureInfo.CurrentUICulture;
        var simplified = culture.Name is "zh" or "zh-CN" or "zh-SG" ||
            culture.Name.StartsWith("zh-Hans", StringComparison.OrdinalIgnoreCase);
        var locale = Language == AppLanguage.SimplifiedChinese || Language == AppLanguage.System && simplified ? "zh-Hans" : "en";
        if (_locale == locale) return;
        var next = new ResourceDictionary
        {
            Source = new Uri($"/MsgAT;component/Resources/Interface.{locale}.xaml", UriKind.Relative)
        };
        var dictionaries = Application.Current.Resources.MergedDictionaries;
        if (_strings is null) dictionaries.Add(next);
        else dictionaries[dictionaries.IndexOf(_strings)] = next;
        _strings = next;
        _locale = locale;
    }

    public static string Get(string key) => (string)Application.Current.FindResource(key);

    internal static string TimelineDate(DateTime day)
    {
        var today = DateTime.Today;
        if (day.Date == today) return Get("UiTimelineToday");
        if (day.Date == today.AddDays(-1)) return Get("UiTimelineYesterday");
        return day.ToString("d", InterfaceCulture);
    }

    internal static string FileSize(long bytes)
    {
        const long mebibyte = 1024L * 1024;
        if (bytes >= mebibyte) return (bytes / (double)mebibyte).ToString("0.#", InterfaceCulture) + " MiB";
        if (bytes >= 1024) return (bytes / 1024d).ToString("0.#", InterfaceCulture) + " KiB";
        return bytes.ToString("N0", InterfaceCulture) + " B";
    }

    public static string Format(string key, params object[] arguments) =>
        string.Format(CultureInfo.CurrentCulture, Formats.GetOrAdd(Get(key), CompositeFormat.Parse), arguments);
}
