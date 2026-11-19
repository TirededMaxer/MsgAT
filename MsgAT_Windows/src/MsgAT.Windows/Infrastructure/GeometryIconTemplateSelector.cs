// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace MsgAT.Windows.Infrastructure;

public sealed class GeometryIconTemplateSelector : DataTemplateSelector
{
    public DataTemplate? GeometryTemplate { get; set; }

    public override DataTemplate? SelectTemplate(object item, DependencyObject container) =>
        item is Geometry ? GeometryTemplate : null;
}
