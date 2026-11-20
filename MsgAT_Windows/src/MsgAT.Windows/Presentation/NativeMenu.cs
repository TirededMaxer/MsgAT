// SPDX-License-Identifier: AGPL-3.0-only
using System.Globalization;
using Microsoft.UI.Xaml.Controls;
using Wpf = System.Windows.Controls;

namespace MsgAT.Windows.Presentation;

internal static class NativeMenu
{
    // The same authorized actions are also used by the remaining WPF dialogs.
    internal static MenuFlyout From(Wpf.ItemCollection actions)
    {
        var flyout = new MenuFlyout();
        Populate(actions, flyout.Items);
        return flyout;
    }

    private static void Populate(Wpf.ItemCollection actions, IList<MenuFlyoutItemBase> output)
    {
        foreach (var action in actions)
        {
            if (action is Wpf.Separator) { output.Add(new MenuFlyoutSeparator()); continue; }
            if (action is not Wpf.MenuItem command) continue;
            var title = Convert.ToString(command.Header, CultureInfo.CurrentCulture) ?? "";
            if (command.HasItems)
            {
                var sub = new MenuFlyoutSubItem { Text = title, IsEnabled = command.IsEnabled };
                Populate(command.Items, sub.Items);
                output.Add(sub);
            }
            else
            {
                var item = command.IsCheckable
                    ? new ToggleMenuFlyoutItem { IsChecked = command.IsChecked } : new MenuFlyoutItem();
                item.Text = title;
                item.IsEnabled = command.IsEnabled;
                item.Click += (_, _) => command.RaiseEvent(new System.Windows.RoutedEventArgs(Wpf.MenuItem.ClickEvent, command));
                output.Add(item);
            }
        }
    }
}
