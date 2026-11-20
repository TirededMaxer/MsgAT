// SPDX-License-Identifier: AGPL-3.0-only
using System.ComponentModel;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;
using Xaml = Microsoft.UI.Xaml;

namespace MsgAT.Windows.Presentation;

public sealed class WorkspaceBarHost : WorkspaceHost
{
    private Grid? _bar;
    private TextBlock? _status;
    private Button? _menu;
    internal Func<System.Windows.Controls.ContextMenu?>? MenuFor { get; set; }

    protected override FrameworkElement CreateContent()
    {
        var grid = new Grid { Padding = new Thickness(12,0,6,0), ColumnSpacing = 6 };
        _bar = grid;
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var labels = new StackPanel { VerticalAlignment = Xaml.VerticalAlignment.Center };
        labels.Children.Add(new TextBlock { Text = "MsgAT", FontSize = 18, FontWeight = FontWeights.SemiBold });
        _status = new TextBlock { FontSize = 12, MaxLines = 1, TextTrimming = TextTrimming.CharacterEllipsis };
        labels.Children.Add(_status);
        grid.Children.Add(labels);
        _menu = new Button { Content = new FontIcon { Glyph = "\uE700", FontSize = 18 }, Width = 36, Height = 36,
            Padding = new Thickness(0), BorderThickness = new Thickness(0), Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
        _menu.Click += (_, _) => { if (MenuFor?.Invoke() is { } menu) NativeMenu.From(menu.Items).ShowAt(_menu); };
        Grid.SetColumn(_menu, 1);
        grid.Children.Add(_menu);
        return grid;
    }

    protected override void BindWorkspace() => RefreshPresentation();
    protected override void ModelChanged(object? sender, PropertyChangedEventArgs args)
    {
        if (args.PropertyName is nameof(MainViewModel.IsWorkspaceReady) or nameof(MainViewModel.SyncText)) RefreshPresentation();
    }
    protected override void RefreshPresentation()
    {
        if (_bar is null || _menu is null || _status is null) return;
        Label(_menu, "UiMenu");
        _menu.IsEnabled = Model?.IsWorkspaceReady == true;
        var key = Model?.SyncText switch { "Connected" => "UiConnected", "Syncing..." => "UiSyncing", _ => "UiDisconnected" };
        _status.Text = AppStrings.Get(key);
        AutomationProperties.SetName(_status, _status.Text);
        if (System.Windows.SystemParameters.HighContrast)
        {
            _status.ClearValue(TextBlock.ForegroundProperty);
            _bar.ClearValue(Panel.BackgroundProperty);
        }
        else
        {
            _bar.Background = ColorBrush("MessagingSidebarColor");
            _status.Foreground = ColorBrush(key == "UiConnected" ? "ConnectionColor" : "MutedColor");
        }
    }
}
