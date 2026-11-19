// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Views;

internal sealed class ReplyQuotePicker : StackPanel
{
    internal ReplyQuotePicker(string visibleText, string digest, Func<string, Task<bool>> submit, Action cancel)
    {
        MinWidth = 170;
        var title = AppStrings.Get("UiQuoteSelectedText");
        var source = new TextBox
        {
            Text = visibleText,
            IsReadOnly = true,
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            MaxHeight = 180,
            MinHeight = 32
        };
        source.SetResourceReference(FontSizeProperty, "ChatMessageFontSize");
        AutomationProperties.SetName(source, title);
        var error = new TextBlock { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
        var confirm = new Button { Content = title, IsEnabled = false, Margin = new Thickness(6, 0, 0, 0) };
        confirm.SetResourceReference(StyleProperty, "PrimaryButtonStyle");
        var dismiss = new Button { Content = AppStrings.Get("UiCancel") };
        dismiss.Click += (_, _) => cancel();
        var busy = false;
        source.SelectionChanged += (_, _) =>
        {
            confirm.IsEnabled = !busy && source.SelectionLength > 0;
            error.Visibility = Visibility.Collapsed;
        };
        confirm.Click += async (_, _) =>
        {
            if (busy || source.SelectionLength == 0) return;
            var text = source.Text;
            if (source.SelectionStart > text.Length) return;
            var start = NormalizedLength(text[..source.SelectionStart]);
            var end = start + NormalizedLength(source.SelectedText);
            var selection = FormattableString.Invariant($"{start}:{end}:{digest}");
            busy = true;
            confirm.IsEnabled = false;
            try
            {
                if (await submit(selection)) return;
            }
            catch (Exception exception) when (exception is System.Security.Cryptography.CryptographicException or
                InvalidOperationException or IOException or System.Text.Json.JsonException or KeyNotFoundException or Microsoft.Data.Sqlite.SqliteException)
            {
                // The message may have changed while the selection was open.
            }
            finally
            {
                busy = false;
                confirm.IsEnabled = source.SelectionLength > 0;
            }
            if (!IsLoaded) return;
            error.Text = AppStrings.Get("UiQuoteSelectionUnavailable");
            error.Visibility = Visibility.Visible;
        };
        var actions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 6, 0, 0) };
        actions.Children.Add(dismiss);
        actions.Children.Add(confirm);
        Children.Add(source);
        Children.Add(error);
        Children.Add(actions);
        Loaded += (_, _) => source.Focus();
    }

    private static int NormalizedLength(string text) => text.Replace("\r\n", "\n", StringComparison.Ordinal).Length;
}
