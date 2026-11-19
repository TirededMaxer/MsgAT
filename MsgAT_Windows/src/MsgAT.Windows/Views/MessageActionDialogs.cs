// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Controls;
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
    private readonly TextBlock _status=new() {TextWrapping=TextWrapping.Wrap,Margin=new Thickness(0,6,0,6)};
    private readonly TextBlock _heading;
    private readonly System.Windows.Controls.TextBox _search = IdentitySetupDialog.Field();
    private readonly Button _clearSearch;
    private readonly Button _back;
    private readonly System.Windows.Controls.Primitives.ToggleButton _messagesView;
    private readonly System.Windows.Controls.Primitives.ToggleButton _chatsView;
    private readonly DispatcherTimer _typing = new() { Interval = TimeSpan.FromMilliseconds(180) };
    private readonly Button _older;
    private readonly Button _refreshPage;
    private readonly WrapPanel _actions=new() {HorizontalAlignment=HorizontalAlignment.Right};
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
        Width = Math.Min(700, SystemParameters.WorkArea.Width * .95);
        Height = Math.Min(600, SystemParameters.WorkArea.Height * .9);
        MinWidth = Math.Min(480, Width);
        MinHeight = Math.Min(400, Height);
        ResizeMode = ResizeMode.CanResize;
        SetResourceReference(BackgroundProperty, "MessagingChromeBrush");
        _list.SetResourceReference(Control.BackgroundProperty, "MessagingTimelineBrush");
        _list.SetResourceReference(Control.BorderBrushProperty, "MessagingDividerBrush");
        _list.ItemContainerStyle = ResourceStyle("ConversationItemStyle");
        var panel=new DockPanel {Margin=new Thickness(20)};
        var top = new StackPanel();
        var heading = new DockPanel();
        _back = IconButton("PreviousMediaIcon", "UiSavedViewChats", () => ChangeScope(null, true));
        DockPanel.SetDock(_back, Dock.Left);
        heading.Children.Add(_back);
        _heading = IdentitySetupDialog.Heading(Title);
        heading.Children.Add(_heading);
        top.Children.Add(heading);
        var modes = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 8) };
        _messagesView = new System.Windows.Controls.Primitives.ToggleButton { Content = AppStrings.Get("UiSavedViewMessages"), IsChecked = true, Padding = new Thickness(9, 5, 9, 5) };
        _chatsView = new System.Windows.Controls.Primitives.ToggleButton { Content = AppStrings.Get("UiSavedViewChats"), Padding = new Thickness(9, 5, 9, 5) };
        _messagesView.Click += (_, _) => ChangeScope(null, false);
        _chatsView.Click += (_, _) => ChangeScope(null, true);
        modes.Children.Add(_messagesView);
        modes.Children.Add(_chatsView);
        top.Children.Add(modes);
        var searchRow = new DockPanel();
        _clearSearch = IconButton("CloseIcon", "UiForwardClearSearch", () => { _search.Clear(); _search.Focus(); });
        DockPanel.SetDock(_clearSearch, Dock.Right);
        searchRow.Children.Add(_clearSearch);
        var searchName = AppStrings.Get("UiSavedSearch");
        System.Windows.Automation.AutomationProperties.SetName(_search, searchName);
        _search.ToolTip = searchName;
        searchRow.Children.Add(_search);
        top.Children.Add(new Label { Content = searchName, Target = _search, Padding = new Thickness(0, 0, 0, 3) });
        top.Children.Add(searchRow);
        top.Children.Add(_status);
        DockPanel.SetDock(top,Dock.Top);panel.Children.Add(top);
        var footer=new StackPanel();
        _older = IdentitySetupDialog.ActionButton(AppStrings.Get("UiSavedOlder"), () =>
            _ = LoadAsync(_pageFailed && _failedReset, keepVisible: true), Brushes.Gray);
        _refreshPage = IdentitySetupDialog.ActionButton(AppStrings.Get("UiQueueRefresh"), () =>
            _ = LoadAsync(true, keepVisible: true), Brushes.Gray);
        _older.HorizontalAlignment = HorizontalAlignment.Left;
        var pages = new WrapPanel();
        pages.Children.Add(_refreshPage);
        pages.Children.Add(_older);
        footer.Children.Add(pages);
        footer.Children.Add(_actions);
        var footerFrame = new Border { Child = footer, BorderThickness = new Thickness(0, 1, 0, 0),
            Margin = new Thickness(0, 8, 0, 0), Padding = new Thickness(0, 6, 0, 0) };
        footerFrame.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        DockPanel.SetDock(footerFrame, Dock.Bottom);
        panel.Children.Add(footerFrame);
        panel.Children.Add(_list);
        Content = panel;
        AddAction("Open", Open, "UiOpen");
        AddAction("Forward", Forward, "UiForward");
        AddAction("Copy text", () => _ = CopyAsync(), "UiCopyMessageText");
        AddAction("Export", Export, "UiSaveAttachment");
        AddAction("Original", () => _ = OriginalAsync(), "UiShowInChat");
        AddAction("Delete", () => _ = DeleteAsync(), "UiSavedDelete");
        AddAction("Close", Close, "UiClose");
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
            else if (e.Key == Key.Escape && (_search.Text.Length > 0 || _source is not null))
            {
                if (_search.Text.Length > 0) _search.Clear(); else ChangeScope(null, true);
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
        System.Windows.Automation.AutomationProperties.SetName(button, text);
        button.Click += (_, _) => action();
        return button;
    }

    private void AddAction(string label,Action action,string key)
    {
        var button = IdentitySetupDialog.ActionButton(AppStrings.Get(key),action,Brushes.Gray);
        button.Tag = label;
        _actions.Children.Add(button);
    }
    private Row[] Selected()=>_list.SelectedItems.OfType<ListBoxItem>().Select(item => item.Tag).OfType<Row>().ToArray();
    private void UpdateActions()
    {
        var selected=Selected();
        _back.Visibility = _source is null ? Visibility.Collapsed : Visibility.Visible;
        _messagesView.Visibility = _chatsView.Visibility = _source is null ? Visibility.Visible : Visibility.Collapsed;
        _messagesView.IsChecked = !_byChat;
        _chatsView.IsChecked = _byChat;
        _clearSearch.Visibility = _search.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        _back.IsEnabled = _messagesView.IsEnabled = _chatsView.IsEnabled = _search.IsEnabled = _clearSearch.IsEnabled = !_busy;
        _list.IsEnabled = !_busy && !_loading;
        _older.Content = AppStrings.Get(_pageFailed ? "UiRetry" : "UiSavedOlder");
        _older.Visibility = _cursor is not null || _pageFailed ? Visibility.Visible : Visibility.Collapsed;
        _older.IsEnabled=!_busy && !_loading;
        _refreshPage.Visibility = _pageFailed ? Visibility.Collapsed : Visibility.Visible;
        _refreshPage.IsEnabled = !_busy && !_loading && selected.Length == 0;
        foreach(Button button in _actions.Children) button.IsEnabled=(string)button.Tag switch
        {
            "Close"=>!_busy,"Open" when ShowingSources => !_busy && !_loading && _list.SelectedItem is ListBoxItem,
            "Forward" or "Delete"=>!_busy && !_loading && selected.Length is >=1 and <=100,
            "Copy text"=>!_busy && !_loading && selected.Length==1 && selected[0].Item.Text.Length>0,
            "Export"=>!_busy && !_loading && selected.Length==1 && selected[0].Media?.IsAvailable == true,
            _=>!_busy && !_loading && selected.Length==1
        };
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
        _status.Text = AppStrings.Get("UiLoading");
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
        _status.Text = AppStrings.Get("UiLoading");
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
                    var row = new DockPanel { Margin = new Thickness(8) };
                    var avatar = IdentitySetupDialog.Avatar(entry.Avatar, label, 38, 19);
                    avatar.Margin = new Thickness(0, 0, 10, 0);
                    DockPanel.SetDock(avatar, Dock.Left);
                    row.Children.Add(avatar);
                    var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                    text.Children.Add(new TextBlock { Text = label, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
                    text.Children.Add(new TextBlock { Text = AppStrings.Format("UiSavedSourceCount", entry.Count), Opacity = .75, FontSize = 12 });
                    row.Children.Add(text);
                    _list.Items.Add(new ListBoxItem { Content = row, Tag = entry });
                }
                _status.Text = entries.Count == 0 ? AppStrings.Get("UiSavedEmpty") : "";
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
                var content=new StackPanel {Margin=new Thickness(6)};
                content.Children.Add(new TextBlock {Text=row.Item.Author,FontWeight=FontWeights.SemiBold});
                if(row.Media is not null)content.Children.Add(new TextBlock {Text=row.Media.Filename});
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
            _status.Text = _list.Items.Count == 0 ? AppStrings.Get(query.Length == 0 ? "UiSavedEmpty" : "UiSavedNoMatches") : "";
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
        catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException or ArgumentException or OperationCanceledException)
        {
            if (!_closed && generation == _generation)
            {
                _status.Text = AppStrings.Get("UiSavedLoadFailed");
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
        _status.Text = "";
        UpdateActions();
        var available = false;
        try { available = await HasOriginalAsync(row.Item); }
        finally { _busy = false; if (!_closed) UpdateActions(); }
        if (_closed) return;
        if (!available) { _status.Text = AppStrings.Get("UiSavedOriginalUnavailable"); return; }
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
                _status.Text = _list.Items.Count == 0 && _cursor is null ? AppStrings.Get(
                    string.IsNullOrWhiteSpace(_search.Text) ? "UiSavedEmpty" : "UiSavedNoMatches") : "";
            }
        }
        catch(Exception error) when(error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
        {if(!_closed)_status.Text=AppStrings.Get("UiSavedDeleteFailed");}
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
            var body = new Grid { Margin = new Thickness(16) };
            for (var row = 0; row < 3; row++)
                body.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var text = new MessageTextBlock { MessageText = item.Text, TextWrapping = TextWrapping.Wrap };
            var preview = new WebPreviewCard { Services = services, MessageId = item.Id, MessageText = item.Text, IsSaved = true };
            Grid.SetRow(text, 1);
            Grid.SetRow(preview, 2);
            body.Children.Add(text);
            body.Children.Add(preview);
            Content = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = body };
        }
    }
}
