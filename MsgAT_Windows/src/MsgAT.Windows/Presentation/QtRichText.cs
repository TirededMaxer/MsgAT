// SPDX-License-Identifier: AGPL-3.0-only
using System.Buffers;
using System.Text.Json.Serialization;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Presentation;

internal sealed class QtRichText
{
    internal sealed record Run(
        [property: JsonPropertyName("text")] string Text,
        [property: JsonPropertyName("style")] int Style = 0,
        [property: JsonPropertyName("action")] string Action = "",
        [property: JsonPropertyName("label")] string Label = "",
        [property: JsonPropertyName("disclosure")] string Disclosure = "");

    private static readonly SearchValues<char> Special = SearchValues.Create("*_+~`|>:");
    private readonly MessageTextBlock.Block[] _blocks;
    private readonly HashSet<int> _revealed = [];
    private readonly HashSet<int> _expanded = [];
    private readonly Dictionary<string, (string Kind, int Index, string Value)> _actions = new(StringComparer.Ordinal);
    private readonly string _hidden;
    private readonly string _scope = Guid.NewGuid().ToString("N");
    private int _revision;
    internal Run[] Runs { get; private set; } = [];
    internal string[] Links { get; private set; } = [];
    internal string VisibleText { get; private set; } = "";
    internal bool HasFormatting { get; }

    private QtRichText(MessageTextBlock.Block[] blocks, string hidden)
    {
        _blocks = blocks;
        _hidden = hidden;
        HasFormatting = blocks.Any(block => block.Kind != "paragraph" ||
            block.Parts.Any(part => part.Style != 0 || part.Spoiler != 0));
        Render();
    }

    internal static QtRichText? Create(string body, string hidden)
    {
        if (body.AsSpan().IndexOfAny(Special) < 0) return null;
        var blocks = MessageTextBlock.Parse(body, false, null);
        return blocks.Any(block => block.Kind != "paragraph" ||
            block.Parts.Any(part => part.Style != 0 || part.Spoiler != 0 || part.Url is not null))
            ? new QtRichText(blocks, hidden) : null;
    }

    internal int[] Revealed => _revealed.ToArray();

    internal bool Apply(string id, Func<string, bool> copy, Func<string, bool> open, out bool changed)
    {
        changed = false;
        if (!_actions.TryGetValue(id, out var action)) return false;
        switch (action.Kind)
        {
            case "copy": return copy(action.Value);
            case "link": return open(action.Value);
            case "reveal": _revealed.Add(action.Index); break;
            case "fold":
                if (!_expanded.Remove(action.Index)) _expanded.Add(action.Index);
                break;
            default: return false;
        }
        Render();
        changed = true;
        return true;
    }

    private void Render()
    {
        _actions.Clear();
        _revision++;
        var runs = new List<Run>();
        var links = new HashSet<string>(StringComparer.Ordinal);
        void AddAction(string text, string kind, int index, string label, string value = "", int style = 0,
            string disclosure = "")
        {
            var id = FormattableString.Invariant($"{_scope}:{_revision}:{_actions.Count}");
            _actions.Add(id, (kind, index, value));
            runs.Add(new Run(text, style, id, label, disclosure));
        }
        void AddDisclosure(string title, int index, bool expanded)
        {
            runs.Add(new Run("\n"));
            AddAction(title, "fold", index, title, disclosure: expanded ? "collapse" : "expand");
        }
        for (var i = 0; i < _blocks.Length; i++)
        {
            var block = _blocks[i];
            if (i != 0) runs.Add(new Run("\n"));
            var expanded = _expanded.Contains(i);
            if (block.Kind == "code")
            {
                var code = string.Concat(block.Parts.Select(part => part.Text));
                var cutoff = MessageTextBlock.CodeFoldOffset(code);
                var heading = string.IsNullOrEmpty(block.Language) ? AppStrings.Get("UiFormatCodeBlock") : block.Language;
                runs.Add(new Run(heading + "  ", 1));
                if (code.Length > 0) AddAction(AppStrings.Get("UiCopy"), "copy", i, AppStrings.Get("UiCopy"), code);
                runs.Add(new Run("\n" + (expanded || cutoff < 0 ? code : code[..cutoff]), 16));
                if (cutoff >= 0)
                {
                    AddDisclosure(AppStrings.Get(expanded ? "UiCollapseCode" : "UiExpandCode"), i, expanded);
                }
                continue;
            }
            if (block.Kind == "quote") runs.Add(new Run("│ ", 1));
            var fold = expanded ? null : block.Fold;
            var lastHidden = 0;
            for (var j = 0; j < block.Parts.Count; j++)
            {
                if (fold is not null && j > fold.Run) break;
                var part = block.Parts[j];
                if (fold is not null && j == fold.Run && fold.End < part.Text.Length)
                    part = part with { Text = part.Text[..fold.End], Url = null };
                if (part.Text.Length == 0) continue;
                var hidden = part.Spoiler != 0 && !_revealed.Contains(part.Spoiler);
                if (hidden && lastHidden != part.Spoiler)
                    AddAction(_hidden, "reveal", part.Spoiler, AppStrings.Get("UiRevealMessageText"), style: 32);
                lastHidden = hidden ? part.Spoiler : 0;
                if (hidden) continue;
                if (MessageLinks.Parse(part.Url) is { } uri)
                {
                    links.Add(uri.AbsoluteUri);
                    AddAction(part.Text, "link", 0, AppStrings.Get("UiOpenLink"), uri.AbsoluteUri, part.Style);
                }
                else runs.Add(new Run(part.Text, part.Style));
            }
            if (block.Fold is not null)
            {
                AddDisclosure(AppStrings.Get(expanded ? "UiCollapseQuote" : "UiExpandQuote"), i, expanded);
            }
        }
        Runs = runs.ToArray();
        Links = links.ToArray();
        VisibleText = string.Concat(Runs.Select(run => run.Text));
    }
}
