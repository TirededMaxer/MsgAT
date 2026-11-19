// SPDX-License-Identifier: AGPL-3.0-only
using MsgAT.Windows.Core.Network;

namespace MsgAT.Windows.Infrastructure;

internal static class SendOptionsText
{
    internal static string Format(SendOptions options)
    {
        var parts = new List<string>(2);
        if (options.Silent) parts.Add(AppStrings.Get("UiSilent"));
        if (options.SendAt is { } due)
        {
            var date = DateTimeOffset.FromUnixTimeMilliseconds(due).ToLocalTime()
                .ToString("g", AppStrings.InterfaceCulture);
            parts.Add(AppStrings.Format("UiScheduledTime", date));
        }
        return string.Join(" · ", parts);
    }
}
