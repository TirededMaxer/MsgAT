// SPDX-License-Identifier: AGPL-3.0-only
using System.ComponentModel;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Presentation;

internal sealed class ConversationRow : Grid
{
    private readonly ContactAvatar _avatar = new(44);
    private readonly TextBlock _alias = Line(15);
    private readonly TextBlock _preview = Line(13);
    private readonly TextBlock _time = Line(12);
    private readonly TextBlock _status = Line(12);
    private readonly TextBlock _pin = Line(12);
    private readonly TextBlock _mute = Line(12);
    private readonly TextBlock _draft = Line(13);
    private readonly TextBlock _count = Line(12);
    private readonly Border _badge = new() { CornerRadius = new CornerRadius(12), Padding = new Thickness(7,1,7,1) };
    private ConversationItem? _item;
    private (string Source, string Label)? _renderedPreview;

    internal ConversationRow()
    {
        MinHeight = 60;
        ColumnSpacing = 10;
        ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Children.Add(_avatar.View);
        _alias.FontWeight = FontWeights.SemiBold;
        _mute.FontFamily = _pin.FontFamily = new FontFamily("Segoe Fluent Icons");
        _mute.Text = "\uE7ED";
        _pin.Text = "\uE718";
        var center = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
        var title = Pair(_alias, _mute, fixedFirst: false);
        var preview = Pair(_draft, _preview, fixedFirst: true);
        center.Children.Add(title);
        center.Children.Add(preview);
        SetColumn(center, 1);
        Children.Add(center);
        var end = new StackPanel { Spacing = 4, VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Right };
        StackPanel RightLine() => new() { Orientation = Orientation.Horizontal, Spacing = 4, HorizontalAlignment = HorizontalAlignment.Right };
        var metadata = RightLine();
        metadata.Children.Add(_status);
        metadata.Children.Add(_time);
        var markers = RightLine();
        _badge.Child = _count;
        markers.Children.Add(_pin);
        markers.Children.Add(_badge);
        end.Children.Add(metadata);
        end.Children.Add(markers);
        SetColumn(end, 2);
        Children.Add(end);
    }

    private static TextBlock Line(double size) => new()
    {
        FontSize = size, VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis,
        MaxLines = 1
    };

    private static Grid Pair(FrameworkElement left, FrameworkElement right, bool fixedFirst)
    {
        var grid = new Grid { ColumnSpacing = 3 };
        var flexible = new GridLength(1, GridUnitType.Star);
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = fixedFirst ? GridLength.Auto : flexible });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = fixedFirst ? flexible : GridLength.Auto });
        grid.Children.Add(left);
        SetColumn(right, 1);
        grid.Children.Add(right);
        return grid;
    }

    internal void Present(ConversationItem? item)
    {
        if (_item is not null) _item.PropertyChanged -= Changed;
        _item = item;
        if (item is null) { _avatar.Clear(); return; }
        item.PropertyChanged += Changed;
        RefreshColors();
        Update();
    }

    internal void RefreshColors()
    {
        if (_item is null) return;
        if (System.Windows.SystemParameters.HighContrast)
        {
            foreach (var text in new[] { _alias, _preview, _time, _mute, _pin, _draft, _status, _count })
                text.ClearValue(TextBlock.ForegroundProperty);
            _badge.ClearValue(Border.BackgroundProperty);
        }
        else
        {
            var secondary = WorkspaceHost.ColorBrush("MutedColor");
            _preview.Foreground = _time.Foreground = _mute.Foreground = _pin.Foreground = secondary;
            _draft.Foreground = WorkspaceHost.ColorBrush("DangerColor");
            _badge.Background = WorkspaceHost.ColorBrush("MessagingSelectionColor");
            _count.Foreground = WorkspaceHost.ColorBrush("MessagingSelectionMarkColor");
        }
        Update();
    }

    private void Changed(object? sender, PropertyChangedEventArgs args) => Update();
    private void Update()
    {
        if (_item is not { } item) return;
        _avatar.Set(item.Alias, item.AvatarPath);
        _alias.Text = item.Alias;
        var source = item.Preview;
        var label = AppStrings.Get("UiHiddenMessageText");
        if (_renderedPreview != (source, label))
        {
            _preview.Text = ConversationSummary.Render(source, label);
            _renderedPreview = (source, label);
        }
        _time.Text = item.ActivityTime;
        _draft.Text = AppStrings.Get("UiDraftPrefix");
        _draft.Visibility = item.IsDraftPreview ? Visibility.Visible : Visibility.Collapsed;
        _mute.Visibility = item.IsMuted ? Visibility.Visible : Visibility.Collapsed;
        _pin.Visibility = item.IsPinned ? Visibility.Visible : Visibility.Collapsed;
        _count.Text = item.UnreadLabel;
        _badge.Visibility = item.HasUnread ? Visibility.Visible : Visibility.Collapsed;
        _status.Text = ConversationSummary.ReceiptMark(item.Receipt);
        if (!System.Windows.SystemParameters.HighContrast)
            _status.Foreground = WorkspaceHost.ColorBrush(item.Receipt.ReceiptStatus switch
            { -1 => "DangerColor", 3 => "MessagingSelectionMarkColor", _ => "MutedColor" });
        AutomationProperties.SetName(_status, item.Receipt.StatusName);
        AutomationProperties.SetName(this, string.Join(", ", new[] { item.Alias, _preview.Text, item.ActivityTime,
            item.Receipt.StatusName, item.HasUnread ? item.UnreadDescription : "", item.IsMuted ? AppStrings.Get("UiConversationMuted") : "" }
            .Where(value => value.Length > 0)));
    }
}
