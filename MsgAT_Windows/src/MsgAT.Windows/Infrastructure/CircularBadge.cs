using System.Windows;
using System.Windows.Controls;

namespace MsgAT.Windows.Infrastructure;

// Keep the full unread count visible while making its background a circle.
public sealed class CircularBadge : Border
{
    protected override Size MeasureOverride(Size constraint)
    {
        var measured = base.MeasureOverride(constraint);
        var diameter = Math.Max(20, Math.Max(measured.Width, measured.Height));
        return new Size(diameter, diameter);
    }

    protected override Size ArrangeOverride(Size finalSize)
    {
        CornerRadius = new CornerRadius(Math.Min(finalSize.Width, finalSize.Height) / 2);
        return base.ArrangeOverride(finalSize);
    }
}
