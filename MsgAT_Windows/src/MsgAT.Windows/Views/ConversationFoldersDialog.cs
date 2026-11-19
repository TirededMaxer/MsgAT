// SPDX-License-Identifier: AGPL-3.0-only
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Threading;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using System.Text;
using MsgAT.Windows.ViewModels;
using CheckBox=System.Windows.Controls.CheckBox;

namespace MsgAT.Windows.Views;

public sealed class ConversationFoldersDialog:DialogWindow
{
    private readonly AppServices _services;
    private readonly Func<bool> _ownerAllowsEditing;
    private readonly IReadOnlyList<ConversationItem> _peers;
    private readonly ListBox _folders=new(){MinWidth=150};
    private readonly System.Windows.Controls.TextBox _name=IdentitySetupDialog.Field();
    private readonly System.Windows.Controls.TextBox _memberSearch = IdentitySetupDialog.Field();
    private readonly HashSet<string> _selectedMembers = new(StringComparer.Ordinal);
    private readonly Dictionary<string, string[]> _savedMembers = new(StringComparer.Ordinal);
    private readonly CheckBox _all=new(){Content=AppStrings.Get("UiFolderIncludeAll")},_unread=new(){Content=AppStrings.Get("UiFolderUnreadOnly")},_muted=new(){Content=AppStrings.Get("UiFolderExcludeMuted")},_archived=new(){Content=AppStrings.Get("UiFolderIncludeArchived")};
    private readonly ListBox _members=new(){SelectionMode=SelectionMode.Multiple};
    private readonly TextBlock _status=new(){TextWrapping=TextWrapping.Wrap};
    private readonly StackPanel _form=new();
    private readonly WrapPanel _buttons=new();
    private string? _id;
    private bool _busy, _closed, _closeAfter, _loading, _confirmingDiscard;
    private bool _refreshingMembers;
    private readonly Button _save;
    private FolderDraft? _baseline;
    private sealed record FolderDraft(string Name, bool All, bool Unread, bool ExcludeMuted, bool Archived, string[] Members);
    private FolderDraft ReadDraft() => new(_name.Text.Trim(), _all.IsChecked == true, _unread.IsChecked == true,
        _muted.IsChecked == true, _archived.IsChecked == true,
        _selectedMembers.ToArray());
    private bool HasChanges
    {
        get
        {
            var current = ReadDraft();
            return _baseline is { } baseline &&
                (current.Name != baseline.Name || current.All != baseline.All || current.Unread != baseline.Unread ||
                 current.ExcludeMuted != baseline.ExcludeMuted || current.Archived != baseline.Archived ||
                 !baseline.Members.ToHashSet(StringComparer.Ordinal).SetEquals(current.Members));
        }
    }
    private bool ValidName => _name.Text.Trim() is { Length: > 0 } name &&
        !name.Contains((char)0) && Encoding.UTF8.GetByteCount(name) <= 128;
    private const string FolderDragFormat = "MsgAT.ConversationFolder";
    private string? _pressedFolder;
    private string? _draggedFolder;
    private System.Windows.Point _pressPoint;
    private System.Windows.Point _dropPoint;
    private Border? _dropIndicator;
    private ScrollViewer? _folderScroller;
    private readonly DispatcherTimer _dragScroll = new() { Interval = TimeSpan.FromMilliseconds(125) };

    public ConversationFoldersDialog(AppServices services,IReadOnlyList<ConversationItem> peers, Func<bool> ownerAllowsEditing)
    {
        _services=services;_peers=peers;_ownerAllowsEditing=ownerAllowsEditing;Title=AppStrings.Get("UiChatFolders");Width=700;Height=560;MinWidth=560;MinHeight=420;ResizeMode=ResizeMode.CanResize;
        var root=new DockPanel{Margin=new Thickness(20)};var heading=IdentitySetupDialog.Heading(Title);DockPanel.SetDock(heading,Dock.Top);root.Children.Add(heading);
        var footer=new StackPanel();footer.Children.Add(_status);footer.Children.Add(_buttons);DockPanel.SetDock(footer,Dock.Bottom);root.Children.Add(footer);
        var grid=new Grid{Margin=new Thickness(0,12,0,12)};grid.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(170)});grid.ColumnDefinitions.Add(new ColumnDefinition());root.Children.Add(grid);
        grid.Children.Add(_folders);var body=new DockPanel{Margin=new Thickness(16,0,0,0)};Grid.SetColumn(body,1);grid.Children.Add(body);
        _form.Children.Add(new TextBlock{Text=AppStrings.Get("UiFolderName")});_form.Children.Add(_name);foreach(var toggle in new[]{_all,_unread,_muted,_archived}){toggle.Margin=new Thickness(0,6,0,6);_form.Children.Add(toggle);}DockPanel.SetDock(_form,Dock.Top);body.Children.Add(_form);body.Children.Add(_members);
        var memberSearchLabel = AppStrings.Get("UiFolderSearchMembers");
        _form.Children.Add(new TextBlock { Text = memberSearchLabel });
        _form.Children.Add(_memberSearch);
        System.Windows.Automation.AutomationProperties.SetName(_memberSearch, memberSearchLabel);
        _memberSearch.TextChanged += (_, _) => RefreshMembers();
        _members.SelectionChanged += (_, change) =>
        {
            if (_refreshingMembers) return;
            foreach (ListBoxItem row in change.RemovedItems) _selectedMembers.Remove((string)row.Tag);
            foreach (ListBoxItem row in change.AddedItems) _selectedMembers.Add((string)row.Tag);
        };
        _all.Checked += (_, _) => UpdateMemberEditing();
        _all.Unchecked += (_, _) => UpdateMemberEditing();
        _folders.SelectionChanged += (_, _) =>
        {
            if (_loading || _busy || _confirmingDiscard) return;
            var folder = (_folders.SelectedItem as ListBoxItem)?.Tag as ConversationFolder;
            if (folder?.Id == _id) return;
            if (ConfirmDiscard()) Choose(folder);
            else SelectCurrentFolder();
        };
        _folders.AllowDrop = true;
        _folders.PreviewMouseLeftButtonDown += RememberFolderPress;
        _folders.PreviewMouseMove += StartFolderDrag;
        _folders.DragOver += ShowFolderDrop;
        _folders.DragLeave += (_, _) => { _dragScroll.Stop(); ClearDropIndicator(); };
        _folders.Drop += DropFolder;
        _dragScroll.Tick += (_, _) => ScrollDuringDrag();
        Add("+", () => { if (ConfirmDiscard()) Choose(null); }, "UiFolderNew");
        Add("↑", () => _ = MoveAsync(true), "UiFolderMoveUp");
        Add("↓", () => _ = MoveAsync(false), "UiFolderMoveDown");
        Add(AppStrings.Get("UiFolderDelete"), () => _ = DeleteAsync(), "UiFolderDelete");
        _save = Add(AppStrings.Get("UiSave"), () => _ = SaveAsync(), "UiSave");
        Add(AppStrings.Get("UiClose"), Close, "UiClose");
        _name.TextChanged += (_, _) => _save.IsEnabled = ValidName;
        Content = root;
        Choose(null);
        foreach (var peer in _peers) peer.PropertyChanged += MemberChanged;
        Loaded += async (_, _) =>
        {
            try { await ReloadAsync(); }
            catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException or OperationCanceledException)
            { if (!_closed) _status.Text = AppStrings.Get("UiFolderUpdateFailed"); }
        };
        Closing += OnClosing;
        Closed += (_, _) =>
        {
            _closed = true;
            _dragScroll.Stop();
            foreach (var peer in _peers) peer.PropertyChanged -= MemberChanged;
        };
    }

    private Button Add(string label, Action action, string hintKey)
    {
        var button = IdentitySetupDialog.ActionButton(label, action, Brushes.Gray);
        button.ToolTip = AppStrings.Get(hintKey);
        System.Windows.Automation.AutomationProperties.SetName(button, AppStrings.Get(hintKey));
        _buttons.Children.Add(button);
        return button;
    }

    private bool ConfirmDiscard()
    {
        if (_busy || _closed || _confirmingDiscard || !_ownerAllowsEditing()) return false;
        if (!HasChanges) return true;
        _confirmingDiscard = true;
        try
        {
            return new ConfirmationDialog(AppStrings.Get("UiDiscardMessageEdit"), Title,
                dismissLabel: AppStrings.Get("UiKeepEditing"), confirmLabel: AppStrings.Get("UiDiscard"))
                { Owner = this }.ShowDialog() == true && !_closed && _ownerAllowsEditing();
        }
        finally { _confirmingDiscard = false; }
    }

    private void SelectCurrentFolder()
    {
        _loading = true;
        try
        {
            _folders.SelectedItem = _folders.Items.OfType<ListBoxItem>()
                .FirstOrDefault(item => item.Tag is ConversationFolder folder && folder.Id == _id);
        }
        finally { _loading = false; }
    }

    private void UpdateMemberEditing() =>
        _members.IsEnabled = _memberSearch.IsEnabled = !_busy && _all.IsChecked != true;

    private void MemberChanged(object? sender, PropertyChangedEventArgs args)
    {
        if (_closed || args.PropertyName != nameof(ConversationItem.Alias) || sender is not ConversationItem peer) return;
        var query = _memberSearch.Text.Trim();
        if (query.Length == 0) return;
        var item = _members.Items.OfType<ListBoxItem>().FirstOrDefault(row => Equals(row.Tag, peer.Uuid));
        if ((item is not null) != peer.Alias.Contains(query, StringComparison.CurrentCultureIgnoreCase))
            RefreshMembers();
    }

    private void RefreshMembers()
    {
        var query = _memberSearch.Text.Trim();
        _refreshingMembers = true;
        try
        {
            _members.Items.Clear();
            foreach (var peer in _peers)
            {
                if (query.Length > 0 && !peer.Alias.Contains(query, StringComparison.CurrentCultureIgnoreCase)) continue;
                var item = new ListBoxItem { Tag = peer.Uuid };
                item.SetBinding(ListBoxItem.ContentProperty, new Binding(nameof(ConversationItem.Alias)) { Source = peer });
                _members.Items.Add(item);
                item.IsSelected = _selectedMembers.Contains(peer.Uuid);
            }
        }
        finally { _refreshingMembers = false; }
    }

    private void Choose(ConversationFolder? folder)
    {
        _id=folder?.Id;_name.Text=folder?.Name??"";_all.IsChecked=folder?.IncludeAll??false;_unread.IsChecked=folder?.UnreadOnly??false;_muted.IsChecked=folder?.ExcludeMuted??false;_archived.IsChecked=folder?.IncludeArchived??false;
        _selectedMembers.Clear();
        if (folder is not null)
        {
            if (_savedMembers.TryGetValue(folder.Id, out var saved)) _selectedMembers.UnionWith(saved);
            else _selectedMembers.UnionWith(_peers.Where(peer => peer.FolderIDs.Contains(folder.Id)).Select(peer => peer.Uuid));
        }
        if (_memberSearch.Text.Length > 0) _memberSearch.Clear();
        else RefreshMembers();
        UpdateMemberEditing();
        _baseline = ReadDraft();
        SelectCurrentFolder();
        _save.IsEnabled = ValidName;
        _status.Text="";
    }
    private async Task ReloadAsync()
    {
        var selected = _id;
        var groups = await _services.ConversationIndex.FoldersAsync();
        if (_closed) return;
        _loading = true;
        _folders.Items.Clear();
        foreach (var folder in groups)
        {
            var label = new TextBlock { Text = folder.Name, TextTrimming = TextTrimming.CharacterEllipsis };
            var indicator = new Border
            {
                Child = label,
                BorderBrush = (Brush)FindResource("AccentBrush"),
                Height = 34,
                Padding = new Thickness(4, 5, 4, 5)
            };
            var item = new ListBoxItem { Tag = folder, Content = indicator, HorizontalContentAlignment = HorizontalAlignment.Stretch };
            _folders.Items.Add(item);
            if (folder.Id == selected) _folders.SelectedItem = item;
        }
        _loading = false;
    }

    private ListBoxItem? FolderRow(object? source) =>
        source is DependencyObject element ? ItemsControl.ContainerFromElement(_folders, element) as ListBoxItem : null;

    private void RememberFolderPress(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        _pressedFolder = !_busy && FolderRow(e.OriginalSource)?.Tag is ConversationFolder folder ? folder.Id : null;
        _pressPoint = e.GetPosition(_folders);
    }

    private void StartFolderDrag(object sender, System.Windows.Input.MouseEventArgs e)
    {
        if (_busy || _draggedFolder is not null || _pressedFolder is not { } id ||
            e.LeftButton != System.Windows.Input.MouseButtonState.Pressed ||
            Math.Abs(e.GetPosition(_folders).Y - _pressPoint.Y) < SystemParameters.MinimumVerticalDragDistance)
        {
            return;
        }

        _pressedFolder = null;
        _draggedFolder = id;
        _folderScroller = FindScrollViewer(_folders);
        try
        {
            var data = new System.Windows.DataObject(FolderDragFormat, id);
            System.Windows.DragDrop.DoDragDrop(_folders, data, System.Windows.DragDropEffects.Move);
            e.Handled = true;
        }
        finally
        {
            _dragScroll.Stop();
            ClearDropIndicator();
            _draggedFolder = null;
            _folderScroller = null;
        }
    }

    private void ClearDropIndicator()
    {
        if (_dropIndicator is null) return;
        _dropIndicator.BorderThickness = new Thickness(0);
        _dropIndicator = null;
    }

    private bool TryGetDropTarget(System.Windows.DragEventArgs e, out string targetId, out bool before, out Border? indicator)
    {
        targetId = "";
        before = false;
        indicator = null;
        if (_busy || _draggedFolder is not { } id ||
            !string.Equals(e.Data.GetData(FolderDragFormat, false) as string, id, StringComparison.Ordinal) ||
            FolderRow(e.OriginalSource) is not { Tag: ConversationFolder target, Content: Border border } row ||
            target.Id == id)
        {
            return false;
        }

        targetId = target.Id;
        before = e.GetPosition(row).Y < row.ActualHeight / 2;
        indicator = border;
        return true;
    }

    private void ShowFolderDrop(object sender, System.Windows.DragEventArgs e)
    {
        _dropPoint = e.GetPosition(_folders);
        if (_draggedFolder is not null && (_dropPoint.Y < 26 || _dropPoint.Y > _folders.ActualHeight - 26))
            _dragScroll.Start();
        else
            _dragScroll.Stop();
        e.Effects = System.Windows.DragDropEffects.None;
        if (TryGetDropTarget(e, out _, out var before, out var indicator) && indicator is not null)
        {
            SetDropIndicator(indicator, before);
            e.Effects = System.Windows.DragDropEffects.Move;
        }
        else ClearDropIndicator();
        e.Handled = true;
    }

    private void SetDropIndicator(Border indicator, bool before)
    {
        if (_dropIndicator != indicator) ClearDropIndicator();
        _dropIndicator = indicator;
        indicator.BorderThickness = before ? new Thickness(0, 2, 0, 0) : new Thickness(0, 0, 0, 2);
    }

    private void ScrollDuringDrag()
    {
        if (_draggedFolder is not { } id || _folderScroller is not { } viewer || _busy)
        {
            _dragScroll.Stop();
            return;
        }
        if (_dropPoint.Y < 26) viewer.LineUp();
        else if (_dropPoint.Y > _folders.ActualHeight - 26) viewer.LineDown();
        _folders.UpdateLayout();
        if (FolderRow(_folders.InputHitTest(_dropPoint)) is { Tag: ConversationFolder target, Content: Border indicator } row && target.Id != id)
        {
            var localPoint = _folders.TranslatePoint(_dropPoint, row);
            SetDropIndicator(indicator, localPoint.Y < row.ActualHeight / 2);
        }
        else ClearDropIndicator();
    }

    private static ScrollViewer? FindScrollViewer(DependencyObject parent)
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
        {
            var child = VisualTreeHelper.GetChild(parent, index);
            if (child is ScrollViewer viewer) return viewer;
            if (FindScrollViewer(child) is { } nested) return nested;
        }
        return null;
    }

    private void DropFolder(object sender, System.Windows.DragEventArgs e)
    {
        _dragScroll.Stop();
        ClearDropIndicator();
        e.Handled = true;
        if (_draggedFolder is not { } id || !TryGetDropTarget(e, out var targetId, out var before, out _))
        {
            e.Effects = System.Windows.DragDropEffects.None;
            return;
        }

        e.Effects = System.Windows.DragDropEffects.Move;
        _ = RunAsync(() => _services.ConversationIndex.MoveAsync(id, targetId, before));
    }
    private async Task RunAsync(Func<Task<bool>> action,Action? done=null)
    {
        if(_busy || _closed || !_ownerAllowsEditing())return;_busy=true;_form.IsEnabled=false;_folders.IsEnabled=false;_members.IsEnabled=false;_buttons.IsEnabled=false;_status.Text=AppStrings.Get("UiLoading");
        try{var ok=await action();if(!_closed){if(ok){done?.Invoke();await ReloadAsync();_status.Text="";}else _status.Text=AppStrings.Get("UiFolderUpdateFailed");}}
        catch(Exception error) when(error is InvalidOperationException or IOException or ArgumentException or Microsoft.Data.Sqlite.SqliteException or OperationCanceledException){if(!_closed)_status.Text=AppStrings.Get("UiFolderUpdateFailed");}
        finally{_busy=false;if(!_closed){_form.IsEnabled=true;_folders.IsEnabled=true;UpdateMemberEditing();_buttons.IsEnabled=true;if(_closeAfter){_closeAfter=false;Close();}}}
    }
    private Task SaveAsync()
    {
        if (!ValidName) return Task.CompletedTask;
        var id=_id??Guid.NewGuid().ToString("D");var folder=new ConversationFolder(id,_name.Text,IncludeAll:_all.IsChecked==true,UnreadOnly:_unread.IsChecked==true,ExcludeMuted:_muted.IsChecked==true,IncludeArchived:_archived.IsChecked==true);var peers=_selectedMembers.ToArray();
        return RunAsync(() => _services.ConversationIndex.SaveAsync(folder, peers), () =>
        {
            _id = id;
            _savedMembers[id] = peers;
            _baseline = ReadDraft();
        });
    }
    private Task MoveAsync(bool up)
    {
        if (_id is not { } id) return Task.CompletedTask;
        var rows = _folders.Items.OfType<ListBoxItem>().Select(row => (ConversationFolder)row.Tag).ToList();
        var source = rows.FindIndex(row => row.Id == id);
        var target = source + (up ? -1 : 1);
        if (source < 0 || target < 0 || target >= rows.Count) return Task.CompletedTask;
        return RunAsync(() => _services.ConversationIndex.MoveAsync(id, rows[target].Id, up));
    }
    private Task DeleteAsync()
    {
        if (_id is not { } id || !_ownerAllowsEditing()) return Task.CompletedTask;
        var dialog = new ConfirmationDialog(AppStrings.Get("UiFolderDeleteConfirm"),
            confirmLabel: AppStrings.Get("UiFolderDelete")) { Owner = this };
        if (dialog.ShowDialog() != true) return Task.CompletedTask;
        return RunAsync(() => _services.ConversationIndex.DeleteAsync(id), () =>
        {
            _savedMembers.Remove(id);
            Choose(null);
        });
    }
    private void OnClosing(object? sender, CancelEventArgs e)
    {
        if (_services.IsDisposed || !_ownerAllowsEditing()) return;
        if (_busy) { e.Cancel = true; _closeAfter = true; }
        else if (!ConfirmDiscard()) e.Cancel = true;
    }
}
