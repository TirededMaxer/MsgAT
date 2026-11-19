// SPDX-License-Identifier: AGPL-3.0-only
using System.ComponentModel;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Views;

[System.Diagnostics.CodeAnalysis.SuppressMessage("Design", "CA1001:Types that own disposable fields should be disposable",
    Justification = "The window cancels work during closure and disposes its cancellation source.")]
public sealed class ContactsDialog : DialogWindow
{
    internal Func<string, bool>? ConfirmOpenChat { get; set; }
    private const int PageSize = 50;
    private readonly AppServices _services;
    private readonly Action<string> _open;
    private readonly string? _focusedPeer;
    private readonly Action<string>? _openSharedMedia;
    private readonly Action<bool>? _startCall;
    private readonly Action<ItemCollection>? _addNotificationOptions;
    private readonly Button _back;
    private readonly Button _menu;
    private readonly Button? _add;
    private readonly TextBlock _title = new() { FontSize = 17, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBox _query = IdentitySetupDialog.Field();
    private readonly TextBlock _filter = new() { Margin = new Thickness(12, 0, 12, 8), Visibility = Visibility.Collapsed };
    private readonly ListBox _list = new() { BorderThickness = new Thickness(0), Background = Brushes.Transparent };
    private readonly TextBlock _directoryState = new() { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center,
        TextAlignment = TextAlignment.Center, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(10) };
    private readonly TextBlock _error = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed, Margin = new Thickness(14, 6, 14, 6) };
    private readonly ProgressBar _progress = new() { Height = 2, IsIndeterminate = true, Visibility = Visibility.Hidden };
    private readonly StackPanel _detail = new() { Margin = new Thickness(16) };
    private readonly ScrollViewer _profile;
    private readonly Button _more;
    private readonly DispatcherTimer _refresh = new() { Interval = TimeSpan.FromMilliseconds(150) };
    private readonly CancellationTokenSource _lifetime = new();
    private ContactEntry? _selected;
    private (ContactSafetyInfo Info, BitmapSource Image)? _safety;
    private string _comparison = "";
    private int _limit = PageSize;
    private long _generation;
    private bool _blockedOnly;
    private bool _loading;
    private bool _busy;
    private bool _closed;
    private bool _closeAfter;
    private bool _restoringSelection;

    public ContactsDialog(AppServices services, Action<string> open, string? focusedPeer = null,
        Action<string>? openSharedMedia = null, Action<bool>? startCall = null,
        Action<ItemCollection>? addNotificationOptions = null)
    {
        _services = services;
        _open = open;
        _focusedPeer = focusedPeer;
        _openSharedMedia = openSharedMedia;
        _startCall = startCall;
        _addNotificationOptions = addNotificationOptions;
        Title = AppStrings.Get(focusedPeer is null ? "UiContacts" : "UiContactProfile");
        Width = focusedPeer is null ? 735 : 480;
        Height = Math.Min(focusedPeer is null ? 570 : 530, SystemParameters.WorkArea.Height * .84);
        MinWidth = focusedPeer is null ? 580 : 360;
        MinHeight = Math.Min(320, Height);
        ResizeMode = ResizeMode.CanResize;

        var root = new DockPanel();
        var bar = new DockPanel { Margin = new Thickness(10, 5, 10, 5), MinHeight = 40 };
        var close = IconButton("CloseIcon", "UiClose", Close);
        DockPanel.SetDock(close, Dock.Right);
        bar.Children.Add(close);
        _menu = IconButton("MoreIcon", "UiContactActions", OpenMenu);
        DockPanel.SetDock(_menu, Dock.Right);
        bar.Children.Add(_menu);
        if (focusedPeer is null)
        {
            _add = IconButton("AddFriendIcon", "UiAddFriend", AddContact);
            DockPanel.SetDock(_add, Dock.Right);
            bar.Children.Add(_add);
        }
        _back = IconButton("HistoryReturnIcon", "UiContactBack", LeaveSafety);
        DockPanel.SetDock(_back, Dock.Left);
        bar.Children.Add(_back);
        _title.Margin = new Thickness(5, 0, 0, 0);
        bar.Children.Add(_title);
        DockPanel.SetDock(bar, Dock.Top);
        root.Children.Add(bar);
        var divider = new Border { Height = 1 };
        divider.SetResourceReference(Border.BackgroundProperty, "BorderBrush");
        DockPanel.SetDock(divider, Dock.Top);
        root.Children.Add(divider);
        DockPanel.SetDock(_progress, Dock.Top);
        root.Children.Add(_progress);
        _error.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
        DockPanel.SetDock(_error, Dock.Top);
        root.Children.Add(_error);

        _profile = new ScrollViewer { Content = _detail, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        _more = TextButton("UiContactMore", () =>
        {
            _limit += PageSize;
            _ = LoadAsync();
        });
        _more.Visibility = Visibility.Collapsed;
        if (focusedPeer is null)
        {
            var body = new Grid();
            body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(230) });
            body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1) });
            body.ColumnDefinitions.Add(new ColumnDefinition());
            body.Children.Add(DirectoryPane());
            var separator = new Border();
            separator.SetResourceReference(Border.BackgroundProperty, "BorderBrush");
            Grid.SetColumn(separator, 1);
            body.Children.Add(separator);
            Grid.SetColumn(_profile, 2);
            body.Children.Add(_profile);
            root.Children.Add(body);
        }
        else root.Children.Add(_profile);

        SetResourceReference(BackgroundProperty, "PanelBrush");
        Content = root;
        UpdateHeader();
        _services.Messaging.ContactsChanged += ContactsChanged;
        _refresh.Tick += async (_, _) =>
        {
            _refresh.Stop();
            await LoadAsync();
        };
        Loaded += async (_, _) => await LoadAsync();
        Closing += ClosingDialog;
        Closed += (_, _) =>
        {
            _closed = true;
            ++_generation;
            _refresh.Stop();
            _services.Messaging.ContactsChanged -= ContactsChanged;
            _lifetime.Cancel();
            _lifetime.Dispose();
        };
    }

    private DockPanel DirectoryPane()
    {
        var column = new DockPanel();
        column.SetResourceReference(Panel.BackgroundProperty, "WindowBrush");
        _query.MaxLength = 256;
        _query.Padding = new Thickness(11, 8, 34, 8);
        AutomationProperties.SetName(_query, AppStrings.Get("UiContactSearch"));
        var search = new Grid { Margin = new Thickness(10, 8, 10, 7) };
        search.Children.Add(_query);
        var placeholder = new TextBlock
        {
            Text = AppStrings.Get("UiContactSearch"), IsHitTestVisible = false, VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(12, 0, 32, 0), TextTrimming = TextTrimming.CharacterEllipsis
        };
        placeholder.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        search.Children.Add(placeholder);
        var clear = IconButton("CloseIcon", "UiContactClearSearch", () => { _query.Clear(); _query.Focus(); });
        clear.Visibility = Visibility.Collapsed;
        clear.HorizontalAlignment = HorizontalAlignment.Right;
        clear.Margin = new Thickness(0, 0, 2, 0);
        search.Children.Add(clear);
        _query.TextChanged += (_, _) =>
        {
            placeholder.Visibility = _query.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
            clear.Visibility = _query.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
            _limit = PageSize;
            ++_generation;
            QueueRefresh();
        };
        DockPanel.SetDock(search, Dock.Top);
        column.Children.Add(search);
        _filter.Text = AppStrings.Get("UiContactBlockedList");
        _filter.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        DockPanel.SetDock(_filter, Dock.Top);
        column.Children.Add(_filter);
        _more.Margin = new Thickness(8);
        DockPanel.SetDock(_more, Dock.Bottom);
        column.Children.Add(_more);

        _list.SelectionChanged += (_, _) =>
        {
            if (_restoringSelection || _list.SelectedItem is not ListBoxItem { Tag: ContactEntry peer }) return;
            if (_selected?.Id == peer.Id) return;
            _selected = peer;
            _safety = null;
            _comparison = "";
            RefreshProfile(resetScroll: true);
        };
        ScrollViewer.SetHorizontalScrollBarVisibility(_list, ScrollBarVisibility.Disabled);
        var results = new Grid();
        results.Children.Add(_list);
        _directoryState.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        _directoryState.IsHitTestVisible = false;
        results.Children.Add(_directoryState);
        column.Children.Add(results);
        return column;
    }

    private void QueueRefresh()
    {
        _refresh.Stop();
        _refresh.Start();
    }

    private void ContactsChanged(object? sender, EventArgs args)
    {
        if (Dispatcher.HasShutdownStarted) return;
        Dispatcher.BeginInvoke(() =>
        {
            if (!_closed) QueueRefresh();
        });
    }

    private async Task LoadAsync()
    {
        if (_closed || _busy) return;
        var generation = ++_generation;
        var token = _lifetime.Token;
        var query = _query.Text;
        var blockedOnly = _blockedOnly;
        var limit = _limit;
        var peerId = _focusedPeer ?? _selected?.Id;
        _loading = true;
        UpdateBusyState();
        if (_list.Items.Count == 0) _directoryState.Text = AppStrings.Get("UiLoading");
        try
        {
            var result = await Task.Run(() =>
            {
                var entries = new List<ContactEntry>();
                var hasMore = false;
                if (_focusedPeer is null)
                {
                    for (var offset = 0; offset < limit; offset += PageSize)
                    {
                        token.ThrowIfCancellationRequested();
                        var entriesPage = _services.Contacts.List(query, blockedOnly, offset);
                        entries.AddRange(entriesPage);
                        hasMore = entriesPage.Count == PageSize;
                        if (!hasMore) break;
                    }
                }
                var current = peerId is null ? null : _services.Contacts.List(exactPeer: peerId).FirstOrDefault();
                return (entries, hasMore, current);
            }, token);
            if (_closed || generation != _generation) return;
            if (_focusedPeer is null) ApplyDirectory(result.entries, result.hasMore);
            if (_focusedPeer is not null || _selected?.Id == peerId)
            {
                var changed = _selected != result.current;
                _selected = result.current;
                if (_selected is null || _selected.Revoked)
                {
                    _safety = null;
                    _comparison = "";
                }
                if (changed || _detail.Children.Count == 0) RefreshProfile();
            }
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        catch (Exception) when (!_closed && generation == _generation)
        {
            ShowError("UiContactLoadFailed");
            _directoryState.Text = "";
        }
        catch (Exception) when (_closed || generation != _generation) { }
        finally
        {
            if (!_closed && generation == _generation)
            {
                _loading = false;
                UpdateBusyState();
            }
        }
    }

    private void ApplyDirectory(List<ContactEntry> entries, bool hasMore)
    {
        if (!_list.Items.OfType<ListBoxItem>().Select(item => (ContactEntry)item.Tag).SequenceEqual(entries))
        {
            var scroll = FindListScroll(_list);
            var scrollOffset = scroll?.VerticalOffset ?? 0;
            _restoringSelection = true;
            _list.Items.Clear();
            foreach (var peer in entries)
            {
                var row = new ListBoxItem
                {
                    Content = ContactRow(peer), Tag = peer, IsSelected = peer.Id == _selected?.Id,
                    Style = ResourceStyle("ConversationItemStyle")
                };
                AutomationProperties.SetName(row, $"{peer.Name}, {Status(peer)}");
                _list.Items.Add(row);
            }
            _restoringSelection = false;
            scroll?.ScrollToVerticalOffset(scrollOffset);
        }
        _directoryState.Text = entries.Count == 0 ? AppStrings.Get("UiContactNoResults") : "";
        _directoryState.Visibility = entries.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        _more.Visibility = hasMore ? Visibility.Visible : Visibility.Collapsed;
    }

    private static ScrollViewer? FindListScroll(DependencyObject root)
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
        {
            var child = VisualTreeHelper.GetChild(root, index);
            if (child is ScrollViewer scroll) return scroll;
            if (FindListScroll(child) is { } nested) return nested;
        }
        return null;
    }

    private static DockPanel ContactRow(ContactEntry peer)
    {
        var row = new DockPanel();
        var avatar = IdentitySetupDialog.Avatar(peer.Avatar, peer.Name, 38, 19);
        avatar.Margin = new Thickness(0, 0, 8, 0);
        DockPanel.SetDock(avatar, Dock.Left);
        row.Children.Add(avatar);
        var labels = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        labels.Children.Add(new TextBlock { Text = peer.Name, FontWeight = FontWeights.Medium, TextTrimming = TextTrimming.CharacterEllipsis,
            ToolTip = peer.Name });
        var status = new TextBlock { Text = Status(peer), FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis };
        status.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        labels.Children.Add(status);
        row.Children.Add(labels);
        return row;
    }

    private void ClosingDialog(object? sender, CancelEventArgs args)
    {
        if (!_busy) return;
        args.Cancel = true;
        _closeAfter = true;
        _lifetime.Cancel();
    }

    private async Task RunAsync(Func<Task> action, string errorKey = "UiContactActionFailed")
    {
        if (_busy || _closed) return;
        _busy = true;
        _loading = false;
        ++_generation;
        _error.Visibility = Visibility.Collapsed;
        UpdateBusyState();
        try { await action(); }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
        catch (Exception) { ShowError(errorKey); }
        finally { _busy = false; }
        if (_closeAfter) Close();
        if (_closed) return;
        RefreshProfile();
        UpdateBusyState();
        await LoadAsync();
    }

    private void UpdateBusyState()
    {
        _query.IsEnabled = _list.IsEnabled = !_busy && _safety is null;
        _detail.IsEnabled = !_busy;
        _more.IsEnabled = !_busy && !_loading && _safety is null;
        _progress.Visibility = _busy || (_loading && _detail.Children.Count == 0) ? Visibility.Visible : Visibility.Hidden;
        if (_add is not null) _add.IsEnabled = !_busy;
        _menu.IsEnabled = _back.IsEnabled = !_busy;
    }

    private void ShowError(string key)
    {
        _error.Text = AppStrings.Get(key);
        _error.Visibility = Visibility.Visible;
    }

    private void UpdateHeader()
    {
        _title.Text = _safety is not null ? AppStrings.Get("UiContactSafety") : Title;
        _back.Visibility = _safety is null ? Visibility.Collapsed : Visibility.Visible;
        _menu.Visibility = _safety is null && (_focusedPeer is null || _selected is not null) ? Visibility.Visible : Visibility.Collapsed;
        if (_add is not null) _add.Visibility = _safety is null ? Visibility.Visible : Visibility.Collapsed;
    }

    private void RefreshProfile(bool resetScroll = false)
    {
        _detail.Children.Clear();
        UpdateHeader();
        if (_selected is { } peer)
        {
            if (_safety is { } safety && !peer.Revoked) ShowSafety(peer, safety.Info, safety.Image);
            else ShowProfile(peer);
        }
        else if (_error.Visibility != Visibility.Visible)
        {
            var key = _focusedPeer is null ? "UiContactChoose" : "UiContactUnavailable";
            var empty = new TextBlock { Text = AppStrings.Get(key), TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center,
                Margin = new Thickness(0, 70, 0, 0) };
            empty.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
            _detail.Children.Add(empty);
        }
        if (!resetScroll) return;
        _profile.ScrollToTop();
        if (SystemParameters.ClientAreaAnimation)
            _detail.BeginAnimation(OpacityProperty, new DoubleAnimation(0.4, 1, TimeSpan.FromMilliseconds(160)));
    }

    private void ShowProfile(ContactEntry peer)
    {
        var avatar = IdentitySetupDialog.Avatar(peer.Avatar, peer.Name, 80, 40);
        if (peer.Avatar is { Length: > 0 } path)
        {
            var preview = new Button { Content = avatar, Width = 80, Height = 80, Padding = new Thickness(0),
                HorizontalAlignment = HorizontalAlignment.Center, Background = Brushes.Transparent, BorderBrush = Brushes.Transparent };
            AutomationProperties.SetName(preview, peer.Name);
            preview.Click += (_, _) => new ContactImageDialog(path, peer.Name) { Owner = this }.ShowDialog();
            _detail.Children.Add(preview);
        }
        else
        {
            avatar.HorizontalAlignment = HorizontalAlignment.Center;
            _detail.Children.Add(avatar);
        }
        _detail.Children.Add(new TextBlock { Text = peer.Name, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center,
            FontSize = 21, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 9, 0, 3) });
        var status = new TextBlock { Text = Status(peer), TextAlignment = TextAlignment.Center };
        status.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        _detail.Children.Add(status);

        var connected = peer.Accepted && !peer.Blocked && !peer.Revoked;
        if (connected)
        {
            var bar = new System.Windows.Controls.Primitives.UniformGrid { Rows = 1, Margin = new Thickness(0, 14, 0, 0) };
            if (_focusedPeer is null)
                bar.Children.Add(ProfileAction("ShowMediaMessageIcon", "UiContactOpenChat", () =>
                {
                    if (ConfirmOpenChat?.Invoke(peer.Id) == false || _closed) return;
                    Close();
                    _open(peer.Id);
                }));
            if (_startCall is not null)
            {
                var startCall = _startCall;
                bar.Children.Add(ProfileAction("CallIcon", "UiVoiceCall", () => { startCall(false); Close(); }));
                bar.Children.Add(ProfileAction("VideoCallIcon", "UiVideoCall", () => { startCall(true); Close(); }));
            }
            if (_openSharedMedia is not null)
            {
                var openMedia = _openSharedMedia;
                bar.Children.Add(ProfileAction("ContactMediaIcon", "UiSharedMedia", () => { openMedia(peer.Id); Close(); }));
            }
            if (bar.Children.Count != 0) _detail.Children.Add(bar);
        }
        if (!peer.Blocked && !peer.Revoked && (peer.Incoming || !connected))
        {
            var actions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 12, 0, 0) };
            if (peer.Incoming)
            {
                actions.Children.Add(TextButton("UiContactAccept",
                    () => _ = RunAsync(() => _services.Messaging.AcceptFriendRequestAsync(peer.Id, _lifetime.Token)), "PrimaryButtonStyle"));
                actions.Children.Add(TextButton("UiContactDecline",
                    () => _ = RunAsync(() => _services.Messaging.DeclineFriendRequestAsync(peer.Id, _lifetime.Token))));
            }
            else
            {
                actions.Children.Add(TextButton(peer.Outgoing ? "UiContactResendRequest" : "UiContactSendRequest",
                    () => _ = RunAsync(() => _services.Messaging.AddFriendAsync(peer.Id, _lifetime.Token)), "PrimaryButtonStyle"));
            }
            if (peer.Outgoing && !connected)
                actions.Children.Add(TextButton("UiContactWithdrawRequest", () => _ = RunAsync(async () =>
                {
                    if (!await _services.Messaging.WithdrawContactAsync(peer.Id, _lifetime.Token))
                        throw new InvalidOperationException();
                })));
            _detail.Children.Add(actions);
        }

        var info = new StackPanel();
        var publicId = new DockPanel { Margin = new Thickness(11, 9, 8, 9) };
        var copy = IconButton("ContactCopyIcon", "UiContactCopyId", () => CopyPublicId(this, peer.Id));
        DockPanel.SetDock(copy, Dock.Right);
        publicId.Children.Add(copy);
        var identity = new StackPanel();
        var label = new TextBlock { Text = AppStrings.Get("UiContactPublicId"), FontSize = 12, Margin = new Thickness(3, 0, 0, 0) };
        label.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        identity.Children.Add(label);
        identity.Children.Add(SelectableText(peer.Id));
        publicId.Children.Add(identity);
        info.Children.Add(publicId);
        if (!peer.Revoked)
        {
            var safety = TextButton("UiContactSafety", () => _ = RunAsync(async () =>
            {
                await LoadSafetyAsync(peer.Id);
                _profile.ScrollToTop();
            }));
            safety.HorizontalContentAlignment = HorizontalAlignment.Left;
            safety.Margin = new Thickness(8, 0, 8, 8);
            info.Children.Add(safety);
        }
        if (connected && _addNotificationOptions is { } addOptions)
        {
            var notifications = new Button
            {
                Content = AppStrings.Get("UiConversationNotifications"),
                HorizontalContentAlignment = HorizontalAlignment.Left, Margin = new Thickness(8, 0, 8, 8)
            };
            notifications.Click += (_, _) =>
            {
                var menu = new ContextMenu { PlacementTarget = notifications,
                    Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom };
                addOptions(menu.Items);
                if (menu.Items.Count == 0) return;
                notifications.ContextMenu = menu;
                menu.IsOpen = true;
            };
            info.Children.Add(notifications);
        }
        var section = new Border { Child = info, CornerRadius = new CornerRadius(8), Margin = new Thickness(0, 16, 0, 0) };
        section.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
        _detail.Children.Add(section);
    }

    private async Task LoadSafetyAsync(string peer, string? scan = null)
    {
        var token = _lifetime.Token;
        var safety = await Task.Run(() =>
        {
            if (scan is not null) _services.Contacts.VerifyScan(peer, scan);
            var current = _services.Contacts.Safety(peer);
            return (current, ContactCodes.Image(ContactCodes.Verification(current.Scannable)));
        }, token);
        if (_selected?.Id != peer || _closeAfter) return;
        _safety = safety;
        _comparison = "";
    }

    private void ShowSafety(ContactEntry peer, ContactSafetyInfo code, BitmapSource qr)
    {
        _detail.Children.Add(QrImage(qr));
        _detail.Children.Add(new TextBlock { Text = AppStrings.Get(code.Verified ? "UiContactVerified" : "UiContactUnverified"),
            FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 9, 0, 3) });
        _detail.Children.Add(SelectableText(string.Join(" ", Enumerable.Range(0, code.Display.Length / 5).Select(i => code.Display.Substring(i * 5, 5)))));
        _detail.Children.Add(new TextBlock { Text = AppStrings.Get("UiContactCompare"), TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 6, 0, 9) });
        var input = IdentitySetupDialog.Field(_comparison);
        input.MaxLength = 100;
        _detail.Children.Add(IdentitySetupDialog.Row(AppStrings.Get("UiContactMatchingCode"), input));
        var buttons = new WrapPanel();
        var verify = TextButton("UiContactVerify", () => _ = RunAsync(async () =>
        {
            await Task.Run(() => _services.Contacts.Verify(peer.Id, code.Display), _lifetime.Token);
            await LoadSafetyAsync(peer.Id);
        }), "PrimaryButtonStyle");
        void ValidateInput()
        {
            _comparison = input.Text;
            verify.IsEnabled = string.Concat(input.Text.Where(character => !char.IsWhiteSpace(character))) == code.Display;
        }
        input.TextChanged += (_, _) => ValidateInput();
        ValidateInput();
        buttons.Children.Add(verify);
        buttons.Children.Add(TextButton("UiContactScanImage", () => ScanImage(peer.Id)));
        if (code.Verified)
            buttons.Children.Add(TextButton("UiContactClearVerification", () => _ = RunAsync(async () =>
            {
                await Task.Run(() => _services.Contacts.ClearVerification(peer.Id), _lifetime.Token);
                await LoadSafetyAsync(peer.Id);
            })));
        _detail.Children.Add(buttons);
    }

    private void LeaveSafety()
    {
        _safety = null;
        _comparison = "";
        _error.Visibility = Visibility.Collapsed;
        RefreshProfile(resetScroll: true);
        UpdateBusyState();
    }

    private void AddContact()
    {
        var prompt = new TextPromptDialog(AppStrings.Get("UiAddFriend"), AppStrings.Get("UiContactPublicId"), myUuid: _services.Settings.MyUuid)
            { Owner = this };
        if (prompt.ShowDialog() == true) _ = RunAsync(() => RequestAsync(prompt.Value));
    }

    private async Task RequestAsync(string id)
    {
        var token = _lifetime.Token;
        await _services.Messaging.AddFriendAsync(id, token);
        _selected = await Task.Run(() => _services.Contacts.List(exactPeer: id).FirstOrDefault(), token);
        _safety = null;
        _comparison = "";
        _profile.ScrollToTop();
    }

    private void OpenMenu()
    {
        var menu = new ContextMenu { PlacementTarget = _menu, Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom };
        void Add(string key, Action action)
        {
            var item = new MenuItem { Header = AppStrings.Get(key) };
            item.Click += (_, _) => action();
            menu.Items.Add(item);
        }
        if (_focusedPeer is null)
        {
            Add("UiContactMyQr", () => _ = RunAsync(async () =>
            {
                var id = _services.Settings.MyUuid;
                var qr = await Task.Run(() => ContactCodes.Image("msgat://contact/" + id), _lifetime.Token);
                if (!_closeAfter) new PublicIdentityDialog(qr, id) { Owner = this }.ShowDialog();
            }, "UiContactQrUnavailable"));
            var blocked = new MenuItem { Header = AppStrings.Get("UiContactBlockedList"), IsCheckable = true, IsChecked = _blockedOnly };
            blocked.Click += (_, _) =>
            {
                _blockedOnly = blocked.IsChecked;
                _filter.Visibility = _blockedOnly ? Visibility.Visible : Visibility.Collapsed;
                _limit = PageSize;
                _ = LoadAsync();
            };
            menu.Items.Add(blocked);
            Add("UiContactScanImage", () => ScanImage(null));
        }
        if (_selected is { } peer)
        {
            if (menu.Items.Count > 0) menu.Items.Add(new Separator());
            if (!peer.Blocked || !peer.Revoked)
                Add(peer.Blocked ? "UiContactUnblock" : "UiContactBlock", () =>
                {
                    if (!Confirm(peer.Blocked ? "UiContactConfirmUnblock" : "UiContactConfirmBlock")) return;
                    _ = RunAsync(async () =>
                    {
                        if (!await _services.Messaging.SetContactBlockedAsync(peer.Id, !peer.Blocked, _lifetime.Token))
                            throw new InvalidOperationException();
                    });
                });
            Add("UiContactDelete", () =>
            {
                if (!Confirm("UiContactConfirmDelete")) return;
                _ = RunAsync(async () =>
                {
                    await _services.Messaging.DeleteFriendAsync(peer.Id, _lifetime.Token);
                    _selected = null;
                    _safety = null;
                    _list.SelectedItem = null;
                });
            });
        }
        _menu.ContextMenu = menu;
        menu.IsOpen = true;
    }

    private bool Confirm(string key)
    {
        var dialog = new ConfirmationDialog(AppStrings.Get(key), destructive: key != "UiContactConfirmUnblock") { Owner = this };
        return dialog.ShowDialog() == true;
    }

    private void ScanImage(string? expectedPeer)
    {
        if (_busy) return;
        var picker = new Microsoft.Win32.OpenFileDialog
        {
            Title = AppStrings.Get("UiContactScanImage"), Filter = "Images|*.png;*.jpg;*.jpeg;*.bmp;*.webp"
        };
        if (picker.ShowDialog(this) != true) return;
        _ = RunAsync(async () =>
        {
            var code = await Task.Run(() => SecurityCore.Call(new { operation = "parse_contact_code", value = ContactCodes.Read(picker.FileName) }),
                _lifetime.Token);
            if (_closeAfter) return;
            if (code.GetProperty("kind").GetString() == "contact")
            {
                if (expectedPeer is not null || _focusedPeer is not null) throw new InvalidOperationException();
                var id = code.GetProperty("id").GetString()!;
                var confirmation = new ConfirmationDialog(id, title: AppStrings.Get("UiContactConfirmRequest"), destructive: false,
                    confirmLabel: AppStrings.Get("UiContactSendRequest")) { Owner = this };
                if (confirmation.ShowDialog() == true) await RequestAsync(id);
            }
            else
            {
                if (expectedPeer is null || _selected is not { Revoked: false } peer || peer.Id != expectedPeer)
                    throw new InvalidOperationException();
                await LoadSafetyAsync(peer.Id, code.GetProperty("scannable").GetString()!);
            }
        }, "UiContactScanFailed");
    }

    internal static Button IconButton(string glyph, string key, Action? action = null, string color = "TextBrush")
    {
        var button = new Button
        {
            Content = System.Windows.Application.Current.FindResource(glyph),
            Style = ResourceStyle("IconButtonStyle"), ToolTip = AppStrings.Get(key),
            Background = Brushes.Transparent, BorderBrush = Brushes.Transparent, BorderThickness = new Thickness(1)
        };
        button.SetResourceReference(Control.ForegroundProperty, color);
        AutomationProperties.SetName(button, AppStrings.Get(key));
        if (action is not null) button.Click += (_, _) => action();
        return button;
    }

    private static Button TextButton(string key, Action action, string style = "ButtonStyle")
    {
        var button = new Button { Content = AppStrings.Get(key), Style = ResourceStyle(style), Margin = new Thickness(3), Padding = new Thickness(9, 6, 9, 6) };
        button.Click += (_, _) => action();
        return button;
    }

    private static Button ProfileAction(string icon, string key, Action action)
    {
        var content = new StackPanel();
        var image = new ContentControl { Content = System.Windows.Application.Current.FindResource(icon), HorizontalAlignment = HorizontalAlignment.Center,
            ContentTemplate = (DataTemplate)System.Windows.Application.Current.FindResource("IconTemplate") };
        image.SetResourceReference(Control.ForegroundProperty, "AccentTextBrush");
        content.Children.Add(image);
        var text = new TextBlock { Text = AppStrings.Get(key), TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center,
            FontSize = 12, Margin = new Thickness(0, 4, 0, 0) };
        text.SetResourceReference(TextBlock.ForegroundProperty, "AccentTextBrush");
        content.Children.Add(text);
        var button = new Button { Content = content, MinHeight = 54, Padding = new Thickness(4), Margin = new Thickness(2),
            ToolTip = text.Text, HorizontalContentAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetName(button, text.Text);
        button.Click += (_, _) => action();
        return button;
    }

    private static TextBox SelectableText(string value) => new()
    {
        Text = value, IsReadOnly = true, TextWrapping = TextWrapping.Wrap, FontFamily = new FontFamily("Consolas"),
        BorderThickness = new Thickness(0), MinWidth = 0, Padding = new Thickness(2)
    };

    private static Image QrImage(BitmapSource bitmap)
    {
        var picture = new Image { Source = bitmap, Width = 238, Height = 238, HorizontalAlignment = HorizontalAlignment.Center };
        AutomationProperties.SetName(picture, AppStrings.Get("UiContactQrDescription"));
        RenderOptions.SetBitmapScalingMode(picture, BitmapScalingMode.NearestNeighbor);
        return picture;
    }

    private static void CopyPublicId(Window owner, string id)
    {
        try
        {
            System.Windows.Clipboard.SetText(id);
            TransientNotice.Show(owner, AppStrings.Get("UiContactIdCopied"));
        }
        catch (System.Runtime.InteropServices.ExternalException)
        {
            TransientNotice.Show(owner, AppStrings.Get("UiContactActionFailed"));
        }
    }

    private sealed class PublicIdentityDialog : DialogWindow
    {
        public PublicIdentityDialog(BitmapSource qr, string id)
        {
            Title = AppStrings.Get("UiContactPublicIdentity");
            Width = 380;
            SizeToContent = SizeToContent.Height;
            Content = IdentitySetupDialog.DialogCard(IdentitySetupDialog.Form(IdentitySetupDialog.Heading(Title), QrImage(qr), SelectableText(id),
                TextButton("UiContactCopyId", () => CopyPublicId(this, id)), TextButton("UiClose", Close)));
        }
    }

    private sealed class ContactImageDialog : DialogWindow
    {
        public ContactImageDialog(string avatar, string name)
        {
            Title = name;
            Width = 380;
            SizeToContent = SizeToContent.Height;
            Content = IdentitySetupDialog.DialogCard(IdentitySetupDialog.Form(IdentitySetupDialog.Avatar(avatar, name, 292, 8),
                TextButton("UiClose", Close)));
        }
    }

    private static string Status(ContactEntry peer) => AppStrings.Get(peer.Revoked ? "UiContactIdentityReset"
        : peer.Blocked ? "UiContactBlocked"
        : peer.Accepted ? "UiContactAccepted"
        : peer.Incoming ? "UiContactIncoming"
        : peer.Outgoing ? "UiContactPending"
        : peer.Outcome switch
        {
            "declined" => "UiContactDeclined",
            "withdrawn" => "UiContactWithdrawn",
            "deleted" => "UiContactRemoved",
            _ => "UiContactNotConnected"
        });
}
