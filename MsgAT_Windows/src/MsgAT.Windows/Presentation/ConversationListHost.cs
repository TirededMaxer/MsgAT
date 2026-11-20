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
    private readonly HashSet<ConversationRow> _active = [];
    private (string Scope, string Peer)? _drag;
    private bool _updating;
    private bool _refreshQueued;
    private MainViewModel? _subscribed;
    private readonly Dictionary<string, ConversationItem> _items = new(StringComparer.Ordinal);
    private ConversationItem? Item(object? key) => key is string id ? _items.GetValueOrDefault(id) : null;
    internal Action<ConversationItem>? Choose { get; set; }
    internal Func<ConversationItem, System.Windows.Controls.ContextMenu?>? MenuFor { get; set; }

    protected override FrameworkElement CreateContent()
    {
        var list = new NativeList { SelectionMode = ListViewSelectionMode.Single,
            IsItemClickEnabled = true, CanDragItems = true, AllowDrop = true, Padding = new Thickness(2, 2, 2, 8) };
        _list = list;
        list.ContainerContentChanging += ContainerChanged;
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
        try { list.SelectedItem = Model?.SelectedConversation?.Uuid; }
        finally { _updating = false; }
    }

    internal void ScrollIntoView(ConversationItem item) => _list?.ScrollIntoView(item.Uuid);

    private void ContainerChanged(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        var container = args.ItemContainer;
        if (container.Tag is not ConversationRow row)
        {
            row = new ConversationRow();
            container.Tag = row;
            container.HorizontalContentAlignment = Microsoft.UI.Xaml.HorizontalAlignment.Stretch;
            container.Padding = new Thickness(8, 0, 8, 0);
        }
        if (args.InRecycleQueue || Item(args.Item) is not { } item)
        {
            row.Present(null);
            _active.Remove(row);
            return;
        }
        _active.Add(row);
        row.Present(item);
        container.Content = row;
        AutomationProperties.SetName(container, item.Alias);
        args.Handled = true;
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
            _items.Clear();
            foreach (var item in visible) _items.Add(item.Uuid, item);
            for (var index = list.Items.Count - 1; index >= 0; index--)
                if (Item(list.Items[index]) is null) list.Items.RemoveAt(index);

            for (var index = 0; index < visible.Length; index++)
            {
                var item = visible[index];
                if (index >= list.Items.Count || !Equals(list.Items[index], item.Uuid))
                {
                    var existing = list.Items.IndexOf(item.Uuid);
                    if (existing >= 0) list.Items.RemoveAt(existing);
                    list.Items.Insert(index, item.Uuid);
                }
                if (list.ContainerFromIndex(index) is ListViewItem { Tag: ConversationRow row } container)
                {
                    row.Present(item);
                    AutomationProperties.SetName(container, item.Alias);
                }
            }
            list.SelectedItem = Model?.SelectedConversation?.Uuid;
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
        foreach (var row in _active) row.RefreshColors();
    }

    protected override void ReleaseContent()
    {
        base.ReleaseContent();
        foreach (var row in _active) row.Present(null);
        _active.Clear();
        _list = null;
    }
}
