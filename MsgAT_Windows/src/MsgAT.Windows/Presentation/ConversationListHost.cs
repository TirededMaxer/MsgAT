// SPDX-License-Identifier: AGPL-3.0-only
using System.Collections.Specialized;
using System.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;
using global::Windows.ApplicationModel.DataTransfer;
using NativeList = Microsoft.UI.Xaml.Controls.ListView;

namespace MsgAT.Windows.Presentation;

public sealed class ConversationListHost : WorkspaceHost
{
    private NativeList? _list;
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);
    private (string Scope, string Peer)? _drag;
    private bool _updating;
    private bool _refreshQueued;
    private MainViewModel? _subscribed;
    private static ConversationItem? Item(object? key) => (key as Entry)?.Item;
    private Entry? SelectedEntry() => Model?.SelectedConversation is { } selected ? _entries.GetValueOrDefault(selected.Uuid) : null;
    internal Action<ConversationItem>? Choose { get; set; }
    internal Func<ConversationItem, System.Windows.Controls.ContextMenu?>? MenuFor { get; set; }

    protected override FrameworkElement CreateContent()
    {
        var list = new NativeList { SelectionMode = ListViewSelectionMode.Single,
            IsItemClickEnabled = true, CanDragItems = true, AllowDrop = true, Padding = new Thickness(2, 2, 2, 8) };
        _list = list;
        list.ItemClick += (_, args) => { if (Item(args.ClickedItem) is { } target) ChooseItem(target); };
        list.SelectionChanged += (_, args) =>
        {
            if (!_updating && Item(args.AddedItems.FirstOrDefault()) is { } item) ChooseItem(item);
        };
        list.DragItemsStarting += (_, args) =>
        {
            if (Model is not { } model || Item(args.Items.FirstOrDefault()) is not { IsPinned: true } item ||
                model.VisibleConversations.Count(candidate => candidate.IsPinned) < 2)
            { args.Cancel = true; return; }
            _drag = (model.FolderKey, item.Uuid);
            args.Data.RequestedOperation = DataPackageOperation.Move;
        };
        list.DragItemsCompleted += (_, _) => _drag = null;
        list.DragOver += (_, args) =>
        {
            if (_drag is null) return;
            args.AcceptedOperation = DropRow(args) is null ? DataPackageOperation.None : DataPackageOperation.Move;
            args.Handled = true;
        };
        list.Drop += async (_, args) =>
        {
            if (_drag is not { } source || Model is not { } model || DropRow(args) is not { } target) return;
            args.Handled = true;
            args.AcceptedOperation = DataPackageOperation.Move;
            await model.MovePinAsync(source.Scope, source.Peer, target.Item.Uuid, args.GetPosition(target.View).Y < target.View.ActualHeight / 2);
        };
        list.ContextRequested += RequestMenu;
        ScrollViewer.SetHorizontalScrollBarVisibility(list, ScrollBarVisibility.Disabled);
        return list;
    }

    private void ChooseItem(ConversationItem target)
    {
        if (_updating || Model is not { } model || ReferenceEquals(target, model.SelectedConversation)) return;
        Choose?.Invoke(target);
        if (ReferenceEquals(target, model.SelectedConversation)) return;
        SyncSelection();
    }

    private void SyncSelection()
    {
        if (_list is not { } list) return;
        _updating = true;
        try { list.SelectedItem = SelectedEntry(); }
        finally { _updating = false; }
    }

    internal void ScrollIntoView(ConversationItem item)
    {
        if (_entries.TryGetValue(item.Uuid, out var entry)) _list?.ScrollIntoView(entry);
    }

    // Keep the native container stable across selection and reorder. Create the
    // avatar and formatted preview only while the row occupies the viewport.
    private sealed class Entry : ListViewItem
    {
        internal ConversationItem Item { get; private set; }

        internal Entry(ConversationItem item)
        {
            Item = item;
            MinHeight = 64;
            HorizontalContentAlignment = Microsoft.UI.Xaml.HorizontalAlignment.Stretch;
            Padding = new Thickness(8, 2, 8, 2);
            AutomationProperties.SetName(this, item.Alias);
            Loaded += (_, _) =>
            {
                if (Content is not ConversationRow)
                {
                    var row = new ConversationRow();
                    row.Present(Item);
                    Content = row;
                }
            };
            Unloaded += (_, _) => Detach();
        }

        internal void Update(ConversationItem item)
        {
            if (!ReferenceEquals(Item, item) && Content is ConversationRow row) row.Present(item);
            Item = item;
            AutomationProperties.SetName(this, item.Alias);
        }

        internal void Detach()
        {
            if (Content is ConversationRow row) row.Present(null);
            Content = null;
        }
    }

    private ListViewItem? FindRow(object source)
    {
        for (var current = source as DependencyObject; current is not null && current != _list; current = VisualTreeHelper.GetParent(current))
            if (current is ListViewItem container) return container;
        return null;
    }

    private (ConversationItem Item, ListViewItem View)? DropRow(DragEventArgs args)
    {
        if (_drag is not { } drag || Model?.FolderKey != drag.Scope || FindRow(args.OriginalSource) is not { } row ||
            Item(_list?.ItemFromContainer(row)) is not { IsPinned: true } item || item.Uuid == drag.Peer) return null;
        return (item, row);
    }

    private void RequestMenu(UIElement sender, ContextRequestedEventArgs args)
    {
        if (_list is not { } list) return;
        var row = FindRow(args.OriginalSource) ?? list.ContainerFromItem(list.SelectedItem) as ListViewItem;
        if (row is null || Item(list.ItemFromContainer(row)) is not { } item || MenuFor?.Invoke(item) is not { } source) return;
        var menu = NativeMenu.From(source.Items);
        if (args.TryGetPosition(row, out var point)) menu.ShowAt(row, point);
        else menu.ShowAt(row);
        args.Handled = true;
    }

    protected override void BindWorkspace()
    {
        if (_subscribed is not null) _subscribed.VisibleConversations.CollectionChanged -= ConversationsChanged;
        _subscribed = Model;
        if (_subscribed is not null) _subscribed.VisibleConversations.CollectionChanged += ConversationsChanged;
        _drag = null;
        RefreshList();
    }

    private void ConversationsChanged(object? sender, NotifyCollectionChangedEventArgs args)
    {
        if (_refreshQueued) return;
        _refreshQueued = true;
        Dispatcher.BeginInvoke(() => { _refreshQueued = false; RefreshList(); });
    }

    private void RefreshList()
    {
        if (_list is not { } list) return;
        var visible = Model?.VisibleConversations.ToArray() ?? [];
        _updating = true;
        try
        {
            var retained = visible.Select(item => item.Uuid).ToHashSet(StringComparer.Ordinal);
            foreach (var (id, entry) in _entries.ToArray())
            {
                if (retained.Contains(id)) continue;
                list.Items.Remove(entry);
                entry.Detach();
                _entries.Remove(id);
            }
            for (var index = 0; index < visible.Length; index++)
            {
                var item = visible[index];
                if (!_entries.TryGetValue(item.Uuid, out var entry))
                    _entries[item.Uuid] = entry = new Entry(item);
                else entry.Update(item);
                if (index < list.Items.Count && ReferenceEquals(list.Items[index], entry)) continue;
                int previous = list.Items.IndexOf(entry);
                if (previous >= 0) list.Items.RemoveAt(previous);
                list.Items.Insert(index, entry);
            }
            list.SelectedItem = SelectedEntry();
        }
        finally { _updating = false; }
    }

    protected override void ModelChanged(object? sender, PropertyChangedEventArgs args)
    {
        if (args.PropertyName == nameof(MainViewModel.SelectedConversation)) SyncSelection();
    }

    protected override void RefreshPresentation()
    {
        if (_list is null) return;
        AutomationProperties.SetName(_list, AppStrings.Get("UiAllChats"));
        if (!System.Windows.SystemParameters.HighContrast) _list.Background = ColorBrush("MessagingSidebarColor");
        else _list.ClearValue(Control.BackgroundProperty);
        foreach (var entry in _entries.Values) (entry.Content as ConversationRow)?.RefreshColors();
    }

    protected override void ReleaseContent()
    {
        base.ReleaseContent();
        foreach (var entry in _entries.Values) entry.Detach();
        _entries.Clear();
        _list = null;
    }
}
