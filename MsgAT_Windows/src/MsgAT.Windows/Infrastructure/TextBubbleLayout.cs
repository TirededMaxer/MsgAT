// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Controls;

namespace MsgAT.Windows.Infrastructure;

public sealed class TextBubbleLayout : Panel
{
    public static readonly DependencyProperty AllowInlineProperty = DependencyProperty.Register(
        nameof(AllowInline), typeof(bool), typeof(TextBubbleLayout),
        new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsMeasure));

    public bool AllowInline
    {
        get => (bool)GetValue(AllowInlineProperty);
        set => SetValue(AllowInlineProperty, value);
    }

    private const double InlineGap = 8;
    private const double StackedGap = 3;
    private bool _inline;

    protected override Size MeasureOverride(Size availableSize)
    {
        var constraint = new Size(availableSize.Width, double.PositiveInfinity);
        foreach (UIElement child in InternalChildren) child.Measure(constraint);
        if (InternalChildren.Count < 2) return InternalChildren.Count == 0 ? new Size() : InternalChildren[0].DesiredSize;
        var text = InternalChildren[0].DesiredSize;
        var footer = InternalChildren[1].DesiredSize;
        if (footer.Height == 0) return text;

        var line = text;
        var canShareLine = AllowInline && FlowDirection == FlowDirection.LeftToRight;
        if (canShareLine && InternalChildren[0] is MessageTextBlock body &&
            body.MessageText.IndexOfAny(['\r', '\n']) >= 0)
        {
            canShareLine = body.TryMeasureLastPlainLine(availableSize.Width, out line) && footer.Height <= line.Height;
        }
        var inlineWidth = Math.Max(text.Width, line.Width + InlineGap + footer.Width);
        _inline = canShareLine && inlineWidth <= availableSize.Width;
        return _inline ? new Size(inlineWidth, Math.Max(text.Height, footer.Height))
            : new Size(Math.Max(text.Width, footer.Width), text.Height + StackedGap + footer.Height);
    }

    protected override Size ArrangeOverride(Size finalSize)
    {
        if (InternalChildren.Count == 0) return finalSize;
        var text = InternalChildren[0];
        text.Arrange(new Rect(0, 0, Math.Min(text.DesiredSize.Width, finalSize.Width), text.DesiredSize.Height));
        if (InternalChildren.Count >= 2 && InternalChildren[1].Visibility != Visibility.Collapsed)
        {
            var footer = InternalChildren[1];
            var width = Math.Min(finalSize.Width, footer.DesiredSize.Width);
            var top = _inline ? Math.Max(0, text.DesiredSize.Height - footer.DesiredSize.Height) : text.DesiredSize.Height + StackedGap;
            footer.Arrange(new Rect(finalSize.Width - width, top, width, footer.DesiredSize.Height));
        }
        return finalSize;
    }
}
