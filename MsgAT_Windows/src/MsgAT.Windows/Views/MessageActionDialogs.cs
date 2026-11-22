// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Views;

public sealed class SavedMessagesDialog : DialogWindow
{
    private sealed record Row(SavedMessageItem Item,MessageItem? Media);
    private readonly AppServices _services;
    private readonly Action<SavedMessageItem> _original;
    private readonly Action<string> _openContact;
    internal Func<string, bool>? ConfirmOpenChat { get; set; }
    private readonly ListBox _list=new() {SelectionMode=SelectionMode.Extended,HorizontalContentAlignment=HorizontalAlignment.Stretch};
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(2, 8, 2, 0), Visibility = Visibility.Collapsed };
    private readonly TextBlock _empty = new() { HorizontalAlignment = HorizontalAlignment.Center,
        VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(16), TextAlignment = TextAlignment.Center,
        TextWrapping = TextWrapping.Wrap, IsHitTestVisible = false, Visibility = Visibility.Collapsed };
    private readonly TextBlock _heading;
    private readonly System.Windows.Controls.TextBox _search = IdentitySetupDialog.Field();
    private readonly TextBlock _searchHint;
    private readonly Button _clearSearch;
    private readonly Button _back;
    private readonly ToggleButton _messagesView;
    private readonly ToggleButton _chatsView;
    private readonly Border _modes;
    private readonly Button _more;
    private readonly Button _close;
    private readonly Button _open;
    private readonly Button _forward;
    private readonly MenuItem _copy;
    private readonly MenuItem _export;
    private readonly MenuItem _originalAction;
    private readonly MenuItem _delete;
    private readonly DispatcherTimer _typing = new() { Interval = TimeSpan.FromMilliseconds(180) };
    private readonly Button _older;
    private readonly Button _refreshPage;
    private MessageSearchCursor? _cursor;
    private bool _closed;
    private bool _busy;
    private bool _loading;
    private bool _pageFailed;
    private bool _failedReset;
    private bool _byChat;
    private bool _changingScope;
    private SavedMessageSource? _source;
    private CancellationTokenSource? _load;
    private long _generation;
    private bool ShowingSources => _byChat && _source is null && string.IsNullOrWhiteSpace(_search.Text);

    public SavedMessagesDialog(AppServices services,Action<SavedMessageItem> original,Action<string> openContact)
    {
        _services=services;_original=original;_openContact=openContact;
        Title = AppStrings.Get("UiSavedMessages");
        Width = Math.Min(680, SystemParameters.WorkArea.Width * .95);
        Height = Math.Min(540, SystemParameters.WorkArea.Height * .9);
        MinWidth = Math.Min(440, Width);
        MinHeight = Math.Min(360, Height);
        ResizeMode = ResizeMode.CanResize;
        SetResourceReference(BackgroundProperty, "MessagingChromeBrush");
        _list.Background = Brushes.Transparent;
        _list.BorderThickness = new Thickness(0);
        _list.Padding = new Thickness(0, 4, 0, 4);
        ScrollViewer.SetHorizontalScrollBarVisibility(_list, ScrollBarVisibility.Disabled);
        _list.ItemContainerStyle = ResourceStyle("ConversationItemStyle");
        AutomationProperties.SetName(_list, Title);
        AutomationProperties.SetLiveSetting(_status, AutomationLiveSetting.Polite);
        _status.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        AutomationProperties.SetLiveSetting(_empty, AutomationLiveSetting.Polite);
        _empty.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        var panel = new DockPanel { Margin = new Thickness(18, 16, 18, 14) };
        var top = new StackPanel { Margin = new Thickness(0, 0, 0, 12) };
        var heading = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
        _back = IconButton("HistoryReturnIcon", "UiContactBack", () => ChangeScope(null, true));
        DockPanel.SetDock(_back, Dock.Left);
        heading.Children.Add(_back);
        _heading = new TextBlock { Text = Title, FontSize = 23, FontWeight = FontWeights.SemiBold,
            TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
        heading.Children.Add(_heading);
        top.Children.Add(heading);
        var modes = new StackPanel { Orientation = Orientation.Horizontal };
        _messagesView = new ToggleButton { Content = AppStrings.Get("UiSavedViewMessages"), Style = ResourceStyle("SegmentButtonStyle") };
        _chatsView = new ToggleButton { Content = AppStrings.Get("UiSavedViewChats"), Style = ResourceStyle("SegmentButtonStyle") };
        _messagesView.Click += (_, _) => ChangeScope(null, false);
        _chatsView.Click += (_, _) => ChangeScope(null, true);
        modes.Children.Add(_messagesView);
        modes.Children.Add(_chatsView);
        _modes = new Border { Child = modes, Padding = new Thickness(3), CornerRadius = new CornerRadius(9),
            Margin = new Thickness(0, 0, 0, 10), HorizontalAlignment = HorizontalAlignment.Left };
        _modes.SetResourceReference(Border.BackgroundProperty, "MessagingTimelineBrush");
        top.Children.Add(_modes);
        var searchRow = new DockPanel { Margin = new Thickness(9, 2, 2, 2) };
        _clearSearch = IconButton("CloseIcon", "UiForwardClearSearch", () => { _search.Clear(); _search.Focus(); });
        _clearSearch.Background = Brushes.Transparent;
        _clearSearch.BorderBrush = Brushes.Transparent;
        _clearSearch.Margin = new Thickness(0);
        DockPanel.SetDock(_clearSearch, Dock.Right);
        searchRow.Children.Add(_clearSearch);
        var searchIcon = IconGlyph("\uE721");
        searchIcon.Margin = new Thickness(2, 0, 2, 0);
        searchIcon.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        DockPanel.SetDock(searchIcon, Dock.Left);
        searchRow.Children.Add(searchIcon);
        var searchName = AppStrings.Get("UiSavedSearch");
        AutomationProperties.SetName(_search, searchName);
        _search.Background = Brushes.Transparent;
        _search.BorderThickness = new Thickness(0);
        _search.Padding = new Thickness(8, 4, 4, 4);
        _searchHint = new TextBlock { Text = searchName, Margin = new Thickness(8, 0, 4, 0),
            VerticalAlignment = VerticalAlignment.Center, IsHitTestVisible = false };
        _searchHint.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        var searchText = new Grid();
        searchText.Children.Add(_search);
        searchText.Children.Add(_searchHint);
        searchRow.Children.Add(searchText);
        var searchFrame = new Border { Child = searchRow, CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1) };
        searchFrame.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
        searchFrame.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        searchFrame.IsKeyboardFocusWithinChanged += (_, _) => searchFrame.SetResourceReference(Border.BorderBrushProperty,
            searchFrame.IsKeyboardFocusWithin ? "AccentTextBrush" : "MessagingDividerBrush");
        top.Children.Add(searchFrame);
        top.Children.Add(_status);
        DockPanel.SetDock(top,Dock.Top);panel.Children.Add(top);
        var footer = new DockPanel { LastChildFill = false };
        _older = new Button { Style = ResourceStyle("ButtonStyle"), Margin = new Thickness(0, 0, 5, 0) };
        _older.Click += (_, _) => _ = LoadAsync(_pageFailed && _failedReset, keepVisible: true);
        _refreshPage = ActionButton("\uE72C", "UiQueueRefresh", () => _ = LoadAsync(true, keepVisible: true), showLabel: false);
        _open = ActionButton("\uE8A7", "UiOpen", Open, style: "PrimaryButtonStyle");
        _forward = ActionButton("\uE72A", "UiForward", Forward);
        _close = ActionButton("\uE711", "UiClose", Close);
        var menu = new ContextMenu { Placement = PlacementMode.Top };
        _copy = MenuAction(menu, "UiCopyMessageText", () => _ = CopyAsync());
        _export = MenuAction(menu, "UiSaveAttachment", Export);
        _originalAction = MenuAction(menu, "UiShowInChat", () => _ = OriginalAsync());
        menu.Items.Add(new Separator());
        _delete = MenuAction(menu, "UiSavedDelete", () => _ = DeleteAsync());
        _delete.SetResourceReference(Control.ForegroundProperty, "DangerBrush");
        _more = IconButton("MoreIcon", "UiSavedMoreActions", () => menu.IsOpen = true);
        menu.PlacementTarget = _more;
        _more.ContextMenu = menu;
        DockPanel.SetDock(_close, Dock.Right);
        footer.Children.Add(_close);
        var pages = new StackPanel { Orientation = Orientation.Horizontal };
        pages.Children.Add(_refreshPage);
        pages.Children.Add(_older);
        DockPanel.SetDock(pages, Dock.Left);
        footer.Children.Add(pages);
        var actions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        actions.Children.Add(_open);
        actions.Children.Add(_forward);
        actions.Children.Add(_more);
        DockPanel.SetDock(actions, Dock.Right);
        footer.Children.Add(actions);
        var footerFrame = new Border { Child = footer, BorderThickness = new Thickness(0, 1, 0, 0),
            Margin = new Thickness(0, 8, 0, 0), Padding = new Thickness(0, 6, 0, 0) };
        footerFrame.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        DockPanel.SetDock(footerFrame, Dock.Bottom);
        panel.Children.Add(footerFrame);
        var results = new Grid();
        results.Children.Add(_list);
        results.Children.Add(_empty);
        var listFrame = new Border { Child = results, CornerRadius = new CornerRadius(9), BorderThickness = new Thickness(1) };
        listFrame.SetResourceReference(Border.BackgroundProperty, "MessagingSidebarBrush");
        listFrame.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        panel.Children.Add(listFrame);
        Content = panel;
        _list.MouseDoubleClick+=(_,_)=>Open();_list.KeyDown+=(_,e)=>{if(e.Key==Key.Return){e.Handled=true;Open();}};
        _list.SelectionChanged+=(_,_)=>UpdateActions();
        _search.TextChanged += (_, _) =>
        {
            if (_changingScope || _closed) return;
            InvalidatePage();
            _typing.Stop();
            _typing.Start();
        };
        _typing.Tick += async (_, _) => { _typing.Stop(); await LoadAsync(true); };
        PreviewKeyDown += (_, e) =>
        {
            if (_busy) return;
            if (e.Key == Key.F && Keyboard.Modifiers == ModifierKeys.Control)
            {
                _search.Focus(); _search.SelectAll(); e.Handled = true;
            }
            else if (e.Key == Key.Escape && _more.ContextMenu?.IsOpen != true)
            {
                if (_search.Text.Length > 0) _search.Clear();
                else if (_source is not null) ChangeScope(null, true);
                else Close();
                e.Handled = true;
            }
        };
        Loaded+=async (_,_)=>await LoadAsync(true);
        Closed+=(_,_)=>{_closed=true;++_generation;_load?.Cancel();_typing.Stop();};UpdateActions();
    }

    private static Button IconButton(string icon, string label, Action action)
    {
        var text = AppStrings.Get(label);
        var button = new Button { ToolTip = text, Margin = new Thickness(0, 0, 5, 0) };
        button.SetResourceReference(StyleProperty, "IconButtonStyle");
        button.SetResourceReference(ContentControl.ContentProperty, icon);
        AutomationProperties.SetName(button, text);
        button.Click += (_, _) => action();
        return button;
    }

    private static TextBlock IconGlyph(string glyph) => new()
    {
        Text = glyph, FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets"),
        FontSize = 15, VerticalAlignment = VerticalAlignment.Center
    };

    private static Button ActionButton(string glyph, string label, Action action, bool showLabel = true, string style = "ButtonStyle")
    {
        var name = AppStrings.Get(label);
        var icon = IconGlyph(glyph);
        var button = new Button { Style = ResourceStyle(style), ToolTip = name, Margin = new Thickness(0, 0, 5, 0) };
        AutomationProperties.SetName(button, name);
        if (!showLabel)
        {
            button.Width = 36;
            button.Padding = new Thickness(0);
            button.Content = icon;
        }
        else
        {
            var content = new StackPanel { Orientation = Orientation.Horizontal };
            icon.Margin = new Thickness(0, 0, 7, 0);
            content.Children.Add(icon);
            content.Children.Add(new TextBlock { Text = name, VerticalAlignment = VerticalAlignment.Center });
            button.Content = content;
        }
        button.Click += (_, _) => action();
        return button;
    }

    private static MenuItem MenuAction(ContextMenu menu, string key, Action action)
    {
        var option = new MenuItem { Header = AppStrings.Get(key) };
        option.Click += (_, _) => action();
        menu.Items.Add(option);
        return option;
    }

    private void SetStatus(string value)
    {
        _status.Text = value;
        _status.Visibility = value.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        _empty.Visibility = Visibility.Collapsed;
    }

    private void ShowResults(bool hasQuery)
    {
        SetStatus("");
        if (_list.Items.Count != 0 || _cursor is not null) return;
        _empty.Text = AppStrings.Get(hasQuery ? "UiSavedNoMatches" : "UiSavedEmpty");
        _empty.Visibility = Visibility.Visible;
    }
    private Row[] Selected()=>_list.SelectedItems.OfType<ListBoxItem>().Select(item => item.Tag).OfType<Row>().ToArray();
    private void UpdateActions()
    {
        var selected=Selected();
        _back.Visibility = _source is null ? Visibility.Collapsed : Visibility.Visible;
        _modes.Visibility = _source is null ? Visibility.Visible : Visibility.Collapsed;
        _messagesView.IsChecked = !_byChat;
        _chatsView.IsChecked = _byChat;
        _clearSearch.Visibility = _search.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        _searchHint.Visibility = _search.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        _back.IsEnabled = _messagesView.IsEnabled = _chatsView.IsEnabled = _search.IsEnabled = _clearSearch.IsEnabled = !_busy;
        _list.IsEnabled = !_busy && !_loading;
        _older.Content = AppStrings.Get(_pageFailed ? "UiRetry" : "UiSavedOlder");
        _older.Visibility = _cursor is not null || _pageFailed ? Visibility.Visible : Visibility.Collapsed;
        _older.IsEnabled=!_busy && !_loading;
        _refreshPage.Visibility = _pageFailed ? Visibility.Collapsed : Visibility.Visible;
        _refreshPage.IsEnabled = !_busy && !_loading && selected.Length == 0;
        var canUseSelection = !_busy && !_loading;
        var single = canUseSelection && selected.Length == 1;
        _close.IsEnabled = !_busy;
        _open.IsEnabled = ShowingSources ? canUseSelection && _list.SelectedItem is ListBoxItem : single;
        _forward.IsEnabled = _delete.IsEnabled = canUseSelection && selected.Length is >= 1 and <= 100;
        _copy.IsEnabled = single && selected[0].Item.Text.Length > 0;
        _export.IsEnabled = single && selected[0].Media?.IsAvailable == true;
        _originalAction.IsEnabled = single;
        _more.IsEnabled = _copy.IsEnabled || _export.IsEnabled || _originalAction.IsEnabled || _delete.IsEnabled;
        _open.Visibility = _list.SelectedItem is null ? Visibility.Collapsed : Visibility.Visible;
        _forward.Visibility = _more.Visibility = selected.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        _originalAction.Visibility = _copy.Visibility = _export.Visibility = selected.Length == 1 ? Visibility.Visible : Visibility.Collapsed;
    }

    private static string SourceName(SavedMessageSource source) => source.Alias ?? AppStrings.Format("UiSavedRemovedChat", source.Peer[..Math.Min(8, source.Peer.Length)]);

    private void ChangeScope(SavedMessageSource? source, bool byChat)
    {
        if (_busy || _closed) return;
        _source = source;
        _byChat = byChat;
        _changingScope = true;
        _search.Clear();
        _changingScope = false;
        _typing.Stop();
        _heading.Text = source is null ? Title : SourceName(source);
        _ = LoadAsync(true);
    }

    private void InvalidatePage()
    {
        _load?.Cancel();
        ++_generation;
        _loading = true;
        _pageFailed = false;
        _cursor = null;
        _list.Items.Clear();
        SetStatus(AppStrings.Get("UiLoading"));
        UpdateActions();
    }

    private async Task LoadAsync(bool reset, bool keepVisible = false)
    {
        if (_closed || (!reset && (_loading || _busy || _cursor is null))) return;
        if (reset && !keepVisible) InvalidatePage();
        using var cancellation = new CancellationTokenSource();
        _load = cancellation;
        var generation = ++_generation;
        var byChat = ShowingSources;
        var peer = _source?.Peer;
        var query = _search.Text.Trim();
        _loading = true;
        _pageFailed = false;
        SetStatus(AppStrings.Get("UiLoading"));
        UpdateActions();
        try
        {
            if (byChat)
            {
                var entries = await _services.Messaging.Actions.SourcesAsync(cancellation.Token);
                if (_closed || generation != _generation) return;
                if (reset) _list.Items.Clear();
                _list.SelectionMode = SelectionMode.Single;
                foreach (var entry in entries)
                {
                    var label = SourceName(entry);
                    var row = new DockPanel { Margin = new Thickness(4, 2, 4, 2) };
                    var avatar = IdentitySetupDialog.Avatar(entry.Avatar, label, 38, 19);
                    avatar.Margin = new Thickness(0, 0, 10, 0);
                    DockPanel.SetDock(avatar, Dock.Left);
                    row.Children.Add(avatar);
                    var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                    text.Children.Add(new TextBlock { Text = label, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
                    var count = new TextBlock { Text = AppStrings.Format("UiSavedSourceCount", entry.Count), FontSize = 12, Margin = new Thickness(0, 2, 0, 0) };
                    count.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
                    text.Children.Add(count);
                    row.Children.Add(text);
                    _list.Items.Add(new ListBoxItem { Content = row, Tag = entry });
                }
                ShowResults(hasQuery: false);
                return;
            }
            var page=await _services.Messaging.Actions.ListAsync(reset ? null : _cursor, cancellation.Token, peer, query);
            var data=await Task.Run(()=>page.Take(50).Select(item=>
            {
                cancellation.Token.ThrowIfCancellationRequested();
                MessageItem? media=null;
                if(item.Reference is { } reference && _services.Objects.Metadata(reference,item.Id) is { } metadata)
                {
                    var source=new ChatMessage(item.Id,item.SourcePeer,true,reference,"attachment",item.SavedAt,true,null,null,null,3,false,false);
                    media=new MessageItem(source,attachmentInfo:metadata with {Caption=item.Text},openMedia:()=>_services.Objects.OpenRead(reference),readSticker:()=>_services.Objects.ReadStickerBytes(reference),thumbnailStore:_services.Objects);
                }
                return new Row(item,media);
            }).ToArray(), cancellation.Token);
            if(_closed || generation!=_generation)return;
            var selectedIds = reset ? Selected().Select(row => row.Item.Id).ToHashSet(StringComparer.Ordinal) : null;
            if (reset) _list.Items.Clear();
            _list.SelectionMode = SelectionMode.Extended;
            foreach(var row in data)
            {
                var content = new StackPanel { Margin = new Thickness(4, 2, 4, 2) };
                content.Children.Add(new TextBlock { Text = row.Item.Author, FontWeight = FontWeights.SemiBold,
                    TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, 0, 0, 4) });
                if(row.Media is not null)content.Children.Add(new TextBlock { Text = row.Media.Filename, TextTrimming = TextTrimming.CharacterEllipsis });
                if(row.Item.ContactAccount is not null)content.Children.Add(new TextBlock {Text=AppStrings.Get("UiContactCard"),FontSize=11});
                if(row.Item.Kind=="location" && row.Item.Text!="Location")content.Children.Add(new TextBlock {Text=AppStrings.Get("UiLocation"),FontSize=11});
                var text = new MsgAT.Windows.Infrastructure.MessageTextBlock {MessageText=row.Item.Text,IsSummary=true,TextWrapping=TextWrapping.Wrap,MaxHeight=90};
                if (row.Item.Kind == "txt")
                {
                    var body = new Grid();
                    for (var index = 0; index < 3; index++) body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                    var preview = new WebPreviewCard {Services=_services,MessageId=row.Item.Id,MessageText=row.Item.Text,IsSaved=true};
                    Grid.SetRow(text, 1);
                    Grid.SetRow(preview, 2);
                    body.Children.Add(text);
                    body.Children.Add(preview);
                    content.Children.Add(body);
                }
                else content.Children.Add(text);
                var item = new ListBoxItem { Content = content, Tag = row };
                _list.Items.Add(item);
                if (selectedIds?.Contains(row.Item.Id) == true) _list.SelectedItems.Add(item);
            }
            _cursor=page.Count>50 && data.Length>0?new(data[^1].Item.SavedAt,data[^1].Item.Id):null;
            ShowResults(query.Length > 0);
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
        catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException or ArgumentException or OperationCanceledException)
        {
            if (!_closed && generation == _generation)
            {
                SetStatus(AppStrings.Get("UiSavedLoadFailed"));
                _pageFailed = true;
                _failedReset = reset;
            }
        }
        finally
        {
            if (ReferenceEquals(_load, cancellation)) _load = null;
            if (!_closed && generation == _generation) { _loading = false; UpdateActions(); }
        }
    }
    private void Open()
    {
        if (!_busy && !_loading && ShowingSources && _list.SelectedItem is ListBoxItem { Tag: SavedMessageSource source })
        {
            ChangeScope(source, true);
            return;
        }
        if(_busy || _loading || Selected() is not [var row])return;
        if(row.Item.ContactAccount is not null)
        {
            var detail = new ContactCardDetailsDialog(_services, row.Item.Text,
                () => _services.Database.GetSavedContactCardAccount(row.Item.Id)) { Owner = this };
            if (detail.ShowDialog() == true && detail.OpenAccount is { } account &&
                ConfirmOpenChat?.Invoke(account) != false && !_closed)
            {
                Close();
                _openContact(account);
            }
        }
        else if (row.Item.Kind == "location")
            new LocationDetailsDialog(row.Item.Text, () => _services.Database.GetSavedLocation(row.Item.Id)) { Owner = this }.ShowDialog();
        else if(row.Media is { IsAvailable: true } media)
        {
            if(!media.IsImage && !media.IsVideo && !media.IsAudio){Export();return;}
            var preview = new SavedPreviewDialog(media, () => CanExport(media), _services.Settings.Snapshot.AttachmentExportFolder, async window =>
            {
                if (!await HasOriginalAsync(row.Item))
                {
                    if (window.IsLoaded) TransientNotice.Show(window, AppStrings.Get("UiSavedOriginalUnavailable"));
                    return;
                }
                if (window.IsLoaded) window.DialogResult = true;
            }) { Owner = this };
            if (preview.ShowDialog() == true) ShowOriginal(row.Item);
        }
        else if(row.Item.Reference is not null)TransientNotice.Show(this,AppStrings.Get("UiSavedAttachmentUnavailable"));
        else new SavedTextDialog(_services, row.Item) { Owner = this }.ShowDialog();
    }
    private async Task CopyAsync()
    {
        if(_busy || _loading || Selected() is not [var row])return;
        try
        {
            var text = await MessageTextBlock.CopyableTextAsync(row.Item.Text);
            if (!_closed && Selected() is [var current] && current.Item.Id == row.Item.Id)
                MessageTextBlock.CopyToClipboard(text, this);
        }
        catch (Exception error) when (error is InvalidOperationException or System.Text.Json.JsonException or KeyNotFoundException)
        {
            if (!_closed) TransientNotice.Show(this, AppStrings.Get("UiMessageCopyFailed"));
        }
    }
    private bool CanExport(MessageItem media) => !_closed && !_services.IsDisposed &&
        _services.Messaging.Actions.CanExport(media.MessageId, media.Content);

    private async void Export()
    {
        if (!_busy && !_loading && Selected() is [var row] && row.Media is { IsAvailable: true } media)
            await AttachmentExporter.ExportAsync(this, media, () => CanExport(media),
                preferredFolder: _services.Settings.Snapshot.AttachmentExportFolder);
    }

    private async Task OriginalAsync()
    {
        if (_busy || _loading || Selected() is not [var row]) return;
        _busy = true;
        SetStatus("");
        UpdateActions();
        var available = false;
        try { available = await HasOriginalAsync(row.Item); }
        finally { _busy = false; if (!_closed) UpdateActions(); }
        if (_closed) return;
        if (!available) { SetStatus(AppStrings.Get("UiSavedOriginalUnavailable")); return; }
        ShowOriginal(row.Item);
    }

    private async Task<bool> HasOriginalAsync(SavedMessageItem item)
    {
        try { return await _services.Messaging.Actions.HasOriginalAsync(item); }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException or OperationCanceledException)
        { return false; }
    }

    private void ShowOriginal(SavedMessageItem item)
    {
        if (_closed) return;
        if (ConfirmOpenChat?.Invoke(item.SourcePeer) == false || _closed) return;
        Close();
        _original(item);
    }
    private void Forward()
    {
        var selected=Selected();if(_busy || _loading || selected.Length is <1 or >100)return;
        var dialog=new ForwardMessagesDialog(_services,null,selected.Select(row=>row.Item.Id).ToArray()){Owner=this};
        if (dialog.ShowDialog() == true)
        {
            _list.SelectedItems.Clear();
            TransientNotice.Show(this, AppStrings.Get("UiForwardingQueued"));
        }
    }
    private async Task DeleteAsync()
    {
        var ids=Selected().Select(row=>row.Item.Id).ToArray();if(_busy || _loading || ids.Length is <1 or >100)return;
        if(new ConfirmationDialog(AppStrings.Get("UiSavedDeleteConfirm"), confirmLabel: AppStrings.Get("UiSavedDelete")){Owner=this}.ShowDialog()!=true)return;
        _busy=true;_list.IsEnabled=false;UpdateActions();
        try
        {
            await _services.Messaging.Actions.DeleteAsync(ids);
            if (!_closed)
            {
                var removed = ids.ToHashSet(StringComparer.Ordinal);
                for (var i = _list.Items.Count - 1; i >= 0; i--)
                    if (_list.Items[i] is ListBoxItem { Tag: Row row } && removed.Contains(row.Item.Id)) _list.Items.RemoveAt(i);
                ShowResults(!string.IsNullOrWhiteSpace(_search.Text));
            }
        }
        catch(Exception error) when(error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
        {if(!_closed)SetStatus(AppStrings.Get("UiSavedDeleteFailed"));}
        finally {if(!_closed){_busy=false;_list.IsEnabled=true;UpdateActions();}}
    }
    private sealed class SavedPreviewDialog : DialogWindow
    {
        public SavedPreviewDialog(MessageItem media, Func<bool> canSave, string? preferredFolder, Func<Window, Task> showOriginal)
        {
            Title = AppStrings.Get("UiSavedMedia");
            Width = Math.Min(900, SystemParameters.WorkArea.Width * .95);
            Height = Math.Min(650, SystemParameters.WorkArea.Height * .9);
            ResizeMode = ResizeMode.CanResize;
            var jumping = false;
            Content = new MediaOverlay(media, Close, showMessage: async () =>
            {
                if (jumping) return;
                jumping = true;
                try { await showOriginal(this); }
                finally { jumping = false; }
            }, save: () => AttachmentExporter.ExportAsync(this, media, canSave,
                preferredFolder: preferredFolder), canSave: canSave);
        }
    }
    private sealed class SavedTextDialog : DialogWindow
    {
        public SavedTextDialog(AppServices services, SavedMessageItem item)
        {
            Title = AppStrings.Get("UiSavedMessage");
            Width = Math.Min(600, SystemParameters.WorkArea.Width * .95);
            Height = Math.Min(440, SystemParameters.WorkArea.Height * .9);
            ResizeMode = ResizeMode.CanResize;
            SetResourceReference(BackgroundProperty, "MessagingChromeBrush");
            var body = new StackPanel { Margin = new Thickness(14) };
            var text = new MessageTextBlock { MessageText = item.Text, TextWrapping = TextWrapping.Wrap };
            var preview = new WebPreviewCard { Services = services, MessageId = item.Id, MessageText = item.Text, IsSaved = true };
            body.Children.Add(text);
            body.Children.Add(preview);
            var view = new Border { CornerRadius = new CornerRadius(9), BorderThickness = new Thickness(1),
                Child = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = body } };
            view.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
            view.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
            var root = new DockPanel { Margin = new Thickness(16) };
            var close = ActionButton("\uE711", "UiClose", Close);
            close.HorizontalAlignment = HorizontalAlignment.Right;
            close.Margin = new Thickness(0, 12, 0, 0);
            DockPanel.SetDock(close, Dock.Bottom);
            root.Children.Add(close);
            root.Children.Add(view);
            PreviewKeyDown += (_, args) =>
            {
                if (args.Key != Key.Escape) return;
                args.Handled = true;
                Close();
            };
            Content = root;
        }
    }
}
