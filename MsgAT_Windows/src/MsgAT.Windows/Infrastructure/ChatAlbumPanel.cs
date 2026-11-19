// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Controls;

namespace MsgAT.Windows.Infrastructure;

public sealed class ChatAlbumPanel : Panel
{
    protected override Size MeasureOverride(Size availableSize)
    {
        var width = double.IsFinite(availableSize.Width) ? availableSize.Width : 244;
        var height = 0d;
        for (var index = 0; index < InternalChildren.Count;)
        {
            var columns = ColumnsAt(index);
            var cellSize = new Size(width / columns, double.PositiveInfinity);
            var rowHeight = 0d;
            for (var column = 0; column < columns; column++)
            {
                var child = InternalChildren[index + column];
                child.Measure(cellSize);
                rowHeight = Math.Max(rowHeight, child.DesiredSize.Height);
            }
            height += rowHeight;
            index += columns;
        }
        return new Size(width, height);
    }

    protected override Size ArrangeOverride(Size finalSize)
    {
        var y = 0d;
        for (var index = 0; index < InternalChildren.Count;)
        {
            var columns = ColumnsAt(index);
            var width = finalSize.Width / columns;
            var rowHeight = 0d;
            for (var column = 0; column < columns; column++)
            {
                var child = InternalChildren[index + column];
                child.Arrange(new Rect(column * width, y, width, child.DesiredSize.Height));
                rowHeight = Math.Max(rowHeight, child.DesiredSize.Height);
            }
            y += rowHeight;
            index += columns;
        }
        return finalSize;
    }

    private int ColumnsAt(int index) => index == 0 && InternalChildren.Count % 2 != 0 ? 1 : 2;
}
