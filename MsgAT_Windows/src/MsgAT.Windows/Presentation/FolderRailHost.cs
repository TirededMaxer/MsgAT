// SPDX-License-Identifier: AGPL-3.0-only
using System.Collections.Specialized;
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

public sealed class FolderRailHost : WorkspaceHost
{
    private ListView? _list;
    private MainViewModel? _subscribed;
    private readonly Dictionary<MainViewModel.FolderChoice, FolderEntry> _entries = [];
    private bool _changing;

    protected override FrameworkElement CreateContent()
    {
        var list = new ListView { Padding = new Thickness(2, 8, 2, 8), SelectionMode = ListViewSelectionMode.Single };
        _list = list;
        list.SelectionChanged += (_, _) =>
        {
            if (!_changing && Model is { } model && list.SelectedItem is FolderEntry folder) model.FolderKey = folder.Choice.Key;
        };
        ScrollViewer.SetHorizontalScrollBarVisibility(list, ScrollBarVisibility.Disabled);
        ScrollViewer.SetVerticalScrollBarVisibility(list, ScrollBarVisibility.Hidden);
        return list;
    }

    protected override void BindWorkspace()
    {
        if (_subscribed is not null) _subscribed.FolderChoices.CollectionChanged -= FoldersChanged;
        _subscribed = Model;
        if (Model is not null) Model.FolderChoices.CollectionChanged += FoldersChanged;
        SyncFolders();
    }

    private void FoldersChanged(object? sender, NotifyCollectionChangedEventArgs args) => SyncFolders();
    private void SyncFolders()
    {
        if (_list is not { } list) return;
        _changing = true;
        try
        {
            var choices = Model?.FolderChoices;
            foreach (var (choice, entry) in _entries.ToArray())
            {
                if (choices?.Contains(choice) == true) continue;
                entry.Unbind();
                list.Items.Remove(entry);
                _entries.Remove(choice);
            }
            if (choices is null) return;
            for (var index = 0; index < choices.Count; index++)
            {
                var choice = choices[index];
                if (!_entries.TryGetValue(choice, out var entry)) _entries[choice] = entry = new FolderEntry(choice);
                if (index < list.Items.Count && ReferenceEquals(list.Items[index], entry)) continue;
                list.Items.Remove(entry);
                list.Items.Insert(index, entry);
            }
            SetSelection();
        }
        finally { _changing = false; }
    }

    private void SetSelection()
    {
        if (_list is { } list) list.SelectedItem = _entries.Values.FirstOrDefault(entry => entry.Choice.Key == Model?.FolderKey);
    }

    internal void FocusSelected()
    {
        if (_list?.SelectedItem is FolderEntry selected)
        {
            _list.ScrollIntoView(selected);
            selected.Focus(FocusState.Keyboard);
        }
        else _list?.Focus(FocusState.Keyboard);
    }

    protected override void ModelChanged(object? sender, PropertyChangedEventArgs args)
    {
        if (args.PropertyName != nameof(MainViewModel.FolderKey)) return;
        _changing = true;
        try { SetSelection(); }
        finally { _changing = false; }
    }

    protected override void RefreshPresentation()
    {
        if (_list is null) return;
        AutomationProperties.SetName(_list, AppStrings.Get("UiChatFolders"));
        if (System.Windows.SystemParameters.HighContrast) _list.ClearValue(Control.BackgroundProperty);
        else _list.Background = ColorBrush("PanelColor");
        foreach (var entry in _entries.Values) entry.Update();
    }

    private sealed class FolderEntry : ListViewItem
    {
        internal MainViewModel.FolderChoice Choice { get; }
        private readonly TextBlock _title = new() { FontSize = 12, MaxLines = 1, TextTrimming = TextTrimming.CharacterEllipsis,
            HorizontalAlignment = Xaml.HorizontalAlignment.Center };
        private readonly InfoBadge _count = new() { MinWidth = 14, MinHeight = 14, FontSize = 10,
            HorizontalAlignment = Xaml.HorizontalAlignment.Right, VerticalAlignment = Xaml.VerticalAlignment.Top };

        internal FolderEntry(MainViewModel.FolderChoice choice)
        {
            Choice = choice;
            Padding = new Thickness(2);
            MinHeight = 58;
            HorizontalContentAlignment = Xaml.HorizontalAlignment.Stretch;
            var pane = new Grid { RowSpacing = 2, Padding = new Thickness(0,4,0,4) };
            pane.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            pane.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            pane.Children.Add(new FontIcon { Glyph = choice.Icon, FontSize = 20, Height = 24, FontFamily = new FontFamily("Segoe Fluent Icons") });
            pane.Children.Add(_count);
            Grid.SetRow(_title, 1);
            pane.Children.Add(_title);
            Content = pane;
            choice.PropertyChanged += RowChanged;
            Update();
        }

        internal void Unbind() => Choice.PropertyChanged -= RowChanged;
        private void RowChanged(object? sender, PropertyChangedEventArgs args) => Update();
        internal void Update()
        {
            _title.Text = Choice.Title;
            _title.FontWeight = FontWeights.Medium;
            _count.Visibility = Choice.HasUnread ? Xaml.Visibility.Visible : Xaml.Visibility.Collapsed;
            _count.Value = int.TryParse(Choice.UnreadLabel, System.Globalization.CultureInfo.InvariantCulture, out var unread) ? unread : 99;
            AutomationProperties.SetName(this, Choice.AccessibleName);
            ToolTipService.SetToolTip(this, Choice.AccessibleName);
        }
    }
}
