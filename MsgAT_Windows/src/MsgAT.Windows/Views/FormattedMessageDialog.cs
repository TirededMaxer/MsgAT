// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Views;

internal sealed class FormattedMessageDialog : DialogWindow
{
    internal FormattedMessageDialog(string text)
    {
        Title = AppStrings.Get("UiFormatting");
        Width = Math.Min(540, SystemParameters.WorkArea.Width * .88);
        MaxHeight = SystemParameters.WorkArea.Height * .82;
        SizeToContent = SizeToContent.Height;
        var panel = new DockPanel { Margin = new Thickness(16) };
        var close = IdentitySetupDialog.ActionButton(AppStrings.Get("UiClose"), Close, Brushes.Gray);
        close.IsCancel = true;
        close.HorizontalAlignment = HorizontalAlignment.Right;
        close.Margin = new Thickness(0, 12, 0, 0);
        DockPanel.SetDock(close, Dock.Bottom);
        panel.Children.Add(close);
        var body = new MessageTextBlock { MessageText = text, TextWrapping = TextWrapping.Wrap };
        body.SetResourceReference(MessageTextBlock.LinkForegroundProperty, "AccentTextBrush");
        panel.Children.Add(new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        Content = panel;
    }
}
