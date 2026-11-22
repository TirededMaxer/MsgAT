// SPDX-License-Identifier: AGPL-3.0-only
using System.Buffers;
using System.Diagnostics;
using System.ComponentModel;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Views;
using MsgAT.Windows.ViewModels;
using ClipboardData = global::Windows.ApplicationModel.DataTransfer;

namespace MsgAT.Windows.Infrastructure;

[System.Diagnostics.CodeAnalysis.SuppressMessage("Design", "CA1001", Justification = "The WPF Unloaded event cancels and disposes the current parse.")]
public sealed class MessageTextBlock:TextBlock
{
    private sealed record Part(string Text,int Style,int Spoiler,string? Url);
    private sealed record QuoteFold(int Run, int End);
    private sealed record Block(string Kind,string Language,IReadOnlyList<Part> Parts, QuoteFold? Fold = null);
    private readonly HashSet<int> _revealed=[];
    private readonly HashSet<int> _expandedCodeBlocks = [];
    private readonly HashSet<int> _expandedQuotes = [];
    private const int CollapsedCodeLines = 12;
    private static readonly SearchValues<char> FormatCharacters = SearchValues.Create("*_+~`|>");
    private IReadOnlyList<Block>? _document;
    private long _generation;
    private bool _parsing;
    private CancellationTokenSource? _parseCancellation;
    private readonly record struct QuoteRange(int Block, int Part, int Start, int End);
    private Dictionary<(int Block, int Part), QuoteRange> _quoteRanges = new();
    private long _quoteRevision;
    private static readonly Brush QuoteBrush = CreateQuoteBrush();
    public MessageTextBlock()
    {
        Loaded += (_, _) => { Refresh(); RefreshQuote(); };
        Unloaded += (_, _) =>
        {
            ++_generation;
            ++_quoteRevision;
            _parseCancellation?.Cancel();
            _parseCancellation?.Dispose();
            _parseCancellation = null;
            _document = null;
            _parsing = false;
            _revealed.Clear();
            _expandedCodeBlocks.Clear();
            _expandedQuotes.Clear();
            _quoteRanges.Clear();
            Inlines.Clear();
        };
    }
    public static readonly DependencyProperty MessageTextProperty=DependencyProperty.Register(nameof(MessageText),typeof(string),typeof(MessageTextBlock),new PropertyMetadata("",(owner,_)=>((MessageTextBlock)owner).Reset()));
    public static readonly DependencyProperty LinkForegroundProperty=DependencyProperty.Register(nameof(LinkForeground),typeof(Brush),typeof(MessageTextBlock),new PropertyMetadata(Brushes.CornflowerBlue,(owner,_)=>((MessageTextBlock)owner).Refresh()));
    public static readonly DependencyProperty IsSummaryProperty=DependencyProperty.Register(nameof(IsSummary),typeof(bool),typeof(MessageTextBlock),new PropertyMetadata(false,(owner,_)=>((MessageTextBlock)owner).Reset()));
    public static readonly DependencyProperty QuoteHighlightProperty = DependencyProperty.Register(nameof(QuoteHighlight), typeof(string),
        typeof(MessageTextBlock), new PropertyMetadata(null, (owner, _) => ((MessageTextBlock)owner).RefreshQuote()));
    public bool IsSummary{get=>(bool)GetValue(IsSummaryProperty);set=>SetValue(IsSummaryProperty,value);}
    public string MessageText {get=>(string)GetValue(MessageTextProperty);set=>SetValue(MessageTextProperty,value);}
    public Brush LinkForeground {get=>(Brush)GetValue(LinkForegroundProperty);set=>SetValue(LinkForegroundProperty,value);}
    public string? QuoteHighlight { get => (string?)GetValue(QuoteHighlightProperty); set => SetValue(QuoteHighlightProperty, value); }

    internal int[] RevealedSpoilers => _revealed.ToArray();

    internal bool TryMeasureLastPlainLine(double availableWidth, out Size lastLine)
    {
        lastLine = default;
        if (_document is not [{ Kind: "paragraph", Parts: [{ Style: 0, Spoiler: 0, Url: null } part] }] ||
            part.Text.Length > 4_096 || part.Text.Contains('\t')) return false;

        // The shared parser normalizes line endings. Explicit lines can share the footer
        // when the native text formatter confirms that none will wrap at this width.
        var lines = part.Text.Split('\n');
        if (lines.Length is < 2 or > 16 || lines[^1].Length == 0) return false;
        var culture = Language.GetSpecificCulture();
        var typeface = new Typeface(FontFamily, FontStyle, FontWeight, FontStretch);
        var pixelsPerDip = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        var formatting = TextOptions.GetTextFormattingMode(this);
        var numbers = new NumberSubstitution(NumberSubstitution.GetCultureSource(this),
            NumberSubstitution.GetCultureOverride(this), NumberSubstitution.GetSubstitution(this));
        foreach (var line in lines)
        {
            if (line.Length == 0) continue;
            var formatted = new FormattedText(line, culture, FlowDirection, typeface, FontSize, Foreground,
                numbers, formatting, pixelsPerDip);
            var width = formatted.WidthIncludingTrailingWhitespace;
            if (width > availableWidth) return false;
            lastLine = new Size(width, formatted.Height);
        }
        return true;
    }

    internal static Task<string> CopyableTextAsync(string text, int[]? revealed = null)
    {
        var placeholder = AppStrings.Get("UiHiddenMessageText");
        return Task.Run(() => CopyableText(text, placeholder, revealed ?? []));
    }

    internal static string CopyableText(string text, string placeholder, int[] revealed) =>
        SecurityCore.Call(new
        {
            operation = "copy_message_text",
            text,
            revealed,
            placeholder
        }).GetProperty("text").GetString() ?? "";

    internal static bool CopyToClipboard(string text, Window owner, bool showSuccess = true)
    {
        if (text.Length == 0) return false;
        try
        {
            var data = new ClipboardData.DataPackage();
            data.SetText(text);
            if (ClipboardData.Clipboard.SetContentWithOptions(data, new ClipboardData.ClipboardContentOptions
                { IsAllowedInHistory = false, IsRoamable = false }))
            {
                ClipboardData.Clipboard.Flush();
                if (showSuccess) TransientNotice.Show(owner, AppStrings.Get("UiMessageCopied"));
                return true;
            }
        }
        catch (Exception error) when (error is System.Runtime.InteropServices.ExternalException or UnauthorizedAccessException) { }
        TransientNotice.Show(owner, AppStrings.Get("UiClipboardUnavailable"));
        return false;
    }
    public static DataTemplate SummaryTemplate(string path)
    {
        var text=new FrameworkElementFactory(typeof(MessageTextBlock));
        text.SetBinding(MessageTextProperty,new System.Windows.Data.Binding(path));text.SetValue(IsSummaryProperty,true);
        text.SetValue(TextWrappingProperty,TextWrapping.Wrap);text.SetValue(MarginProperty,new Thickness(4));
        return new DataTemplate{VisualTree=text};
    }
    private void Reset(){++_generation;_parseCancellation?.Cancel();_parseCancellation?.Dispose();_parseCancellation=null;_parsing=false;_document=null;_revealed.Clear();_expandedCodeBlocks.Clear();_expandedQuotes.Clear();RefreshQuote();Refresh();}

    private static SolidColorBrush CreateQuoteBrush()
    {
        var brush = new SolidColorBrush(Color.FromArgb(88, 234, 173, 50));
        brush.Freeze();
        return brush;
    }

    private void RefreshQuote()
    {
        var revision = ++_quoteRevision;
        if (_quoteRanges.Count != 0)
        {
            _quoteRanges.Clear();
            Refresh();
        }
        if (IsLoaded && !IsSummary && QuoteHighlight is { Length: > 0 } preview)
            _ = LoadQuoteAsync(MessageText, preview, revision);
    }

    private async Task LoadQuoteAsync(string text, string preview, long revision)
    {
        try
        {
            var ranges = await Task.Run(() => SecurityCore.Call(new { operation = "locate_reply_quote", text, preview })
                .GetProperty("ranges").EnumerateArray().Select(range => new QuoteRange(range.GetProperty("block").GetInt32(),
                    range.GetProperty("run").GetInt32(), range.GetProperty("start").GetInt32(), range.GetProperty("end").GetInt32()))
                .ToDictionary(range => (range.Block, range.Part)));
            if (!IsLoaded || revision != _quoteRevision) return;
            _quoteRanges = ranges;
            Refresh();
        }
        catch (Exception error) when (error is System.Security.Cryptography.CryptographicException or
            InvalidOperationException or IOException or JsonException or KeyNotFoundException or ArgumentException) { }
    }
    internal static bool HasFormatting(string text) =>
        text.AsSpan().IndexOfAny(FormatCharacters) >= 0 &&
        Parse(text, false, null).Any(block => block.Kind != "paragraph" ||
            block.Parts.Any(part => part.Style != 0 || part.Spoiler != 0));

    private static Block[] Parse(string text,bool summary,string? hiddenLabel)
    {
        if(summary){var value=SecurityCore.Call(new{operation="summarize_message_text",text=text,limit=240,hidden_label=hiddenLabel});return [new Block("paragraph","",[new Part(value.GetProperty("text").GetString()!,0,0,null)])];}
        var result=SecurityCore.Call(new {operation="render_message_text",text=text});
        if(result.GetProperty("version").GetInt32()!=1)throw new InvalidOperationException("Unknown text renderer.");
        return result.GetProperty("blocks").EnumerateArray().Select(block=>new Block(block.GetProperty("kind").GetString()!,block.GetProperty("language").GetString()!,
            block.GetProperty("runs").EnumerateArray().Select(part=>new Part(part.GetProperty("text").GetString()!,part.GetProperty("style").GetInt32(),part.GetProperty("spoiler").GetInt32(),part.GetProperty("url").ValueKind==JsonValueKind.Null?null:part.GetProperty("url").GetString())).ToArray(),
            block.TryGetProperty("quote_fold", out var fold) ? new QuoteFold(fold.GetProperty("run").GetInt32(), fold.GetProperty("utf16_end").GetInt32()) : null)).ToArray();
    }
    private void Refresh()
    {
        Inlines.Clear();
        if(_document is null){
            Inlines.Add(new Run("…"));
            if(IsLoaded && !_parsing){_parsing=true;_parseCancellation=new CancellationTokenSource();_ = LoadAsync(MessageText??"",IsSummary,_generation,_parseCancellation.Token);}
            return;
        }
        var first=true;
        for (var blockIndex = 0; blockIndex < _document!.Count; blockIndex++)
        {
            var block = _document[blockIndex];
            if(!first)Inlines.Add(new LineBreak());first=false;
            if(block.Kind=="quote")Inlines.Add(new Run("│ "){Foreground=LinkForeground});
            if(block.Kind=="code")
            {
                block = RenderCodeHeader(block, blockIndex);
            }
            var fold = block.Fold;
            var showQuoteToggle = fold is not null && !_quoteRanges.Keys.Any(key => key.Block == blockIndex);
            var quoteExpanded = _expandedQuotes.Contains(blockIndex);
            var collapsed = showQuoteToggle && !quoteExpanded ? fold : null;
            var previousHidden=0;
            for (var partIndex = 0; partIndex < block.Parts.Count; partIndex++)
            {
                var part = block.Parts[partIndex];
                if (collapsed is not null)
                {
                    if (partIndex > collapsed.Run) break;
                    if (partIndex == collapsed.Run && collapsed.End < part.Text.Length)
                        part = part with { Text = part.Text[..collapsed.End], Url = null };
                    if (part.Text.Length == 0) continue;
                }
                var hidden=part.Spoiler!=0 && !_revealed.Contains(part.Spoiler);
                if(hidden && previousHidden==part.Spoiler)continue;
                previousHidden=hidden?part.Spoiler:0;
                var run=new Run(hidden?AppStrings.Get("UiHiddenMessageText"):part.Text);
                if(hidden)
                {
                    run.Background=new SolidColorBrush(Color.FromArgb(45,128,128,128));
                    var reveal=new Hyperlink(run){Foreground=LinkForeground};
                    System.Windows.Automation.AutomationProperties.SetName(reveal,AppStrings.Get("UiRevealMessageText"));
                    reveal.Click+=(_,e)=>{e.Handled=true;_revealed.Add(part.Spoiler);Refresh();};Inlines.Add(reveal);continue;
                }
                if((part.Style&1)!=0)run.FontWeight=FontWeights.Bold;
                if((part.Style&2)!=0)run.FontStyle=FontStyles.Italic;
                if((part.Style&16)!=0){run.FontFamily=new FontFamily("Consolas");run.Background=new SolidColorBrush(Color.FromArgb(25,128,128,128));}
                if ((part.Style & 12) != 0)
                {
                    var decorations = new TextDecorationCollection();
                    if ((part.Style & 4) != 0)
                        foreach (var decoration in System.Windows.TextDecorations.Underline) decorations.Add(decoration);
                    if ((part.Style & 8) != 0)
                        foreach (var decoration in System.Windows.TextDecorations.Strikethrough) decorations.Add(decoration);
                    run.TextDecorations = decorations;
                }
                Inline[] runs = [run];
                if (part.Spoiler == 0 && _quoteRanges.TryGetValue((blockIndex, partIndex), out var range) &&
                    range.Start >= 0 && range.End > range.Start && range.End <= part.Text.Length)
                {
                    var pieces = new List<Inline>(3);
                    Run CopyStyle(string text, Brush? background) => new(text)
                    {
                        FontWeight = run.FontWeight,
                        FontStyle = run.FontStyle,
                        FontFamily = run.FontFamily,
                        TextDecorations = run.TextDecorations,
                        Background = background
                    };
                    if (range.Start > 0) pieces.Add(CopyStyle(part.Text[..range.Start], run.Background));
                    pieces.Add(CopyStyle(part.Text[range.Start..range.End], QuoteBrush));
                    if (range.End < part.Text.Length) pieces.Add(CopyStyle(part.Text[range.End..], run.Background));
                    runs = pieces.ToArray();
                }
                if (MessageLinks.Parse(part.Url) is { } uri)
                {
                    var link = new Hyperlink { NavigateUri = uri, Foreground = LinkForeground, ToolTip = uri.AbsoluteUri };
                    link.Inlines.AddRange(runs);
                    link.ContextMenu = MessageLinks.CreateMenu(uri, this);
                    link.RequestNavigate += (_, args) => { args.Handled = true; MessageLinks.Open(uri, this); };
                    Inlines.Add(link);
                }else Inlines.AddRange(runs);
            }
            if (showQuoteToggle)
            {
                Inlines.Add(new LineBreak());
                var index = blockIndex;
                var toggle = new Hyperlink(new Run(AppStrings.Get(quoteExpanded ? "UiCollapseQuote" : "UiExpandQuote"))) { Foreground = LinkForeground };
                toggle.Click += (_, e) =>
                {
                    e.Handled = true;
                    if (Window.GetWindow(this)?.DataContext is MainViewModel { IsSelecting: true }) return;
                    if (!_expandedQuotes.Remove(index)) _expandedQuotes.Add(index);
                    Refresh();
                };
                Inlines.Add(toggle);
            }
        }
    }

    private Block RenderCodeHeader(Block block, int index)
    {
        var code = string.Concat(block.Parts.Select(part => part.Text));
        var cutoff = -1;
        for (var line = 0; line < CollapsedCodeLines; line++)
        {
            cutoff = code.IndexOf('\n', cutoff + 1);
            if (cutoff < 0) break;
        }
        var showingQuote = _quoteRanges.Keys.Any(key => key.Block == index);
        var expanded = showingQuote || _expandedCodeBlocks.Contains(index);
        var language = string.IsNullOrEmpty(block.Language) ? AppStrings.Get("UiFormatCodeBlock") : block.Language;
        Inlines.Add(new Run(language + "  ") { FontSize = Math.Max(10, FontSize - 2) });
        var copy = new Hyperlink(new Run(AppStrings.Get("UiCopy"))) { Foreground = LinkForeground, IsEnabled = code.Length > 0 };
        copy.Click += (_, e) =>
        {
            e.Handled = true;
            if (Window.GetWindow(this) is { } owner && owner.DataContext is not MainViewModel { IsSelecting: true })
                CopyToClipboard(code, owner);
        };
        Inlines.Add(copy);
        if (cutoff >= 0 && !showingQuote)
        {
            Inlines.Add(new Run("  "));
            var toggle = new Hyperlink(new Run(AppStrings.Get(expanded ? "UiCollapseCode" : "UiExpandCode"))) { Foreground = LinkForeground };
            toggle.Click += (_, e) =>
            {
                e.Handled = true;
                if (Window.GetWindow(this)?.DataContext is MainViewModel { IsSelecting: true }) return;
                if (!_expandedCodeBlocks.Remove(index)) _expandedCodeBlocks.Add(index);
                Refresh();
            };
            Inlines.Add(toggle);
        }
        Inlines.Add(new LineBreak());
        if (expanded || cutoff < 0) return block;
        return block with { Parts = [new Part(code[..cutoff], 16, 0, null)] };
    }

    private async Task LoadAsync(string text,bool summary,long generation,CancellationToken token)
    {
        IReadOnlyList<Block> document;
        var hiddenLabel = summary ? AppStrings.Get("UiHiddenMessageText") : null;
        try {document=await Task.Run(()=>Parse(text,summary,hiddenLabel),token);}
        catch(OperationCanceledException){return;}
        catch(Exception error) when(error is InvalidOperationException or JsonException or KeyNotFoundException){document=[new Block("paragraph","",[new Part(AppStrings.Get("UiMessageFormattingUnavailable"),0,0,null)])];}
        if(token.IsCancellationRequested || generation!=_generation || !IsLoaded)return;
        _document=document;_parsing=false;_parseCancellation?.Dispose();_parseCancellation=null;Refresh();
    }
}

internal static class MessageLinks
{
    private static readonly object MenuTag = new();

    internal static Uri? Parse(string? value) => Uri.TryCreate(value, UriKind.Absolute, out var uri) &&
        (uri.Scheme is "https" or "http") && uri.Host.Length > 0 && uri.UserInfo.Length == 0 ? uri : null;

    internal static ContextMenu CreateMenu(Uri uri, FrameworkElement owner)
    {
        var menu = new ContextMenu { Tag = MenuTag };
        menu.Items.Add(new MenuItem { Header = uri.AbsoluteUri, IsEnabled = false });
        var open = new MenuItem { Header = AppStrings.Get("UiOpenLink") };
        open.Click += (_, _) => Open(uri, owner);
        menu.Items.Add(open);
        var copy = new MenuItem { Header = AppStrings.Get("UiCopyLink") };
        copy.Click += (_, _) =>
        {
            if (CanUse(owner) && Window.GetWindow(owner) is { } window)
                MessageTextBlock.CopyToClipboard(uri.AbsoluteUri, window, showSuccess: false);
        };
        menu.Items.Add(copy);
        return menu;
    }

    internal static bool HasOwnMenu(DependencyObject? source, DependencyObject row)
    {
        for (var node = source; node is not null && !ReferenceEquals(node, row); node = node switch
        {
            FrameworkContentElement content => content.Parent ?? ContentOperations.GetParent(content),
            ContentElement content => ContentOperations.GetParent(content),
            Visual visual => VisualTreeHelper.GetParent(visual),
            _ => LogicalTreeHelper.GetParent(node)
        })
        {
            if (ContextMenuService.GetContextMenu(node)?.Tag == MenuTag) return true;
        }
        return false;
    }

    internal static bool Open(Uri uri, FrameworkElement owner)
    {
        return CanUse(owner) && Open(uri, () => Notice(owner, "UiLinkOpenFailed"));
    }

    internal static bool Open(Uri uri, Action failed)
    {
        try
        {
            Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
            return true;
        }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException or Win32Exception)
        {
            failed();
            return false;
        }
    }

    private static bool CanUse(FrameworkElement owner) => owner.IsLoaded &&
        Window.GetWindow(owner) is { IsVisible: true } window && window is not MainWindow { IsAppLocked: true } &&
        window.DataContext is not MainViewModel { IsSelecting: true };

    private static void Notice(FrameworkElement element, string resource)
    {
        if (Window.GetWindow(element) is { } owner) TransientNotice.Show(owner, AppStrings.Get(resource));
    }
}
