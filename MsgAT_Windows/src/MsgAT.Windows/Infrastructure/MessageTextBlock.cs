using System.Diagnostics;
using System.ComponentModel;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using MsgAT.Windows.Views;

namespace MsgAT.Windows.Infrastructure;

public sealed partial class MessageTextBlock : TextBlock
{
    public static readonly DependencyProperty MessageTextProperty = DependencyProperty.Register(
        nameof(MessageText), typeof(string), typeof(MessageTextBlock),
        new PropertyMetadata("", (owner, _) => ((MessageTextBlock)owner).Refresh()));
    public static readonly DependencyProperty LinkForegroundProperty = DependencyProperty.Register(
        nameof(LinkForeground), typeof(Brush), typeof(MessageTextBlock),
        new PropertyMetadata(Brushes.CornflowerBlue, (owner, _) => ((MessageTextBlock)owner).Refresh()));
    private string? _firstUrl;
    public string MessageText { get => (string)GetValue(MessageTextProperty); set => SetValue(MessageTextProperty, value); }
    public Brush LinkForeground { get => (Brush)GetValue(LinkForegroundProperty); set => SetValue(LinkForegroundProperty, value); }

    public MessageTextBlock() { MouseLeftButtonUp += OpenFirstUrl; }

    [GeneratedRegex(@"(?<!\S)https?://\S*", RegexOptions.CultureInvariant)]
    private static partial Regex Links();

    private void Refresh()
    {
        Inlines.Clear(); _firstUrl = null;
        var content = MessageText ?? "";
        var offset = 0;
        foreach (Match match in Links().Matches(content))
        {
            if (match.Index > offset) Inlines.Add(new Run(content[offset..match.Index]));
            Inlines.Add(new Run(match.Value) { Foreground = LinkForeground, TextDecorations = System.Windows.TextDecorations.Underline });
            _firstUrl ??= match.Value;
            offset = match.Index + match.Length;
        }
        if (offset < content.Length) Inlines.Add(new Run(content[offset..]));
        Cursor = _firstUrl is null ? Cursors.Arrow : Cursors.Hand;
    }

    private void OpenFirstUrl(object sender, MouseButtonEventArgs args)
    {
        if (_firstUrl is null) return;
        args.Handled = true;
        try
        {
            if (!Uri.TryCreate(_firstUrl, UriKind.Absolute, out var uri) || uri.Host.Length == 0 || uri.Scheme is not ("http" or "https"))
                throw new ArgumentException("Invalid URL");
            Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
        }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException or Win32Exception)
        {
            if (Window.GetWindow(this) is { } owner) TransientNotice.Show(owner, "Invalid URL");
        }
    }
}
