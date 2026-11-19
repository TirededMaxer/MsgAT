// SPDX-License-Identifier: AGPL-3.0-only
using System.Text;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Views;

internal sealed class ContactCardPickerDialog : DialogWindow
{
    private sealed record Choice(string Account, string Name, bool Local = false)
    {
        public string Label => Local ? $"{Name} · {AppStrings.Get("UiContactPublicIdentity")}" : $"{Name} · {Account[^12..]}";
    }

    private readonly TextBox _search = IdentitySetupDialog.Field();
    private readonly ListBox _list = new() { DisplayMemberPath = nameof(Choice.Label), MinHeight = 130, MaxHeight = 260 };
    private readonly TextBlock _selected = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _directoryStatus = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Button _retry;
    private readonly Button _send;
    private readonly Button _options;
    private readonly Button _cancel;
    private readonly Func<string, string, SendOptions, Task<bool>> _sendCard;
    private IReadOnlyList<Choice> _entries = [];
    private SendOptions _selection;
    private bool _loading;
    private bool _loadFailed;
    private bool _sending;
    private bool _closed;

    public ContactCardPickerDialog(AppServices services, string recipient, SendOptions initial,
                                   Func<string, string, SendOptions, Task<bool>> sendCard)
    {
        _sendCard = sendCard;
        _selection = initial;
        Title = AppStrings.Get("UiShareContact");
        Width = Math.Min(480, SystemParameters.WorkArea.Width * .94);
        SizeToContent = SizeToContent.Height;
        MaxHeight = SystemParameters.WorkArea.Height * .92;
        AutomationProperties.SetName(_search, AppStrings.Get("UiContactSearch"));
        var header = IdentitySetupDialog.Form(IdentitySetupDialog.Heading(Title),
            new TextBlock { Text = AppStrings.Format("UiRecipient", recipient), TextTrimming = TextTrimming.CharacterEllipsis });
        var buttons = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 10, 0, 0) };
        _options = Action(AppStrings.Get("UiSendOptions"), ChooseOptions);
        _cancel = Action(AppStrings.Get("UiCancel"), Close);
        _cancel.IsCancel = true;
        _send = Action(AppStrings.Get("UiSend"), () => _ = SendAsync());
        _send.Style = ResourceStyle("PrimaryButtonStyle");
        _retry = Action(AppStrings.Get("UiRetry"), () => _ = LoadAsync(services));
        _retry.Visibility = Visibility.Collapsed;
        buttons.Children.Add(_options);
        buttons.Children.Add(_cancel);
        buttons.Children.Add(_send);
        var root = new DockPanel();
        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);
        var bottom = IdentitySetupDialog.Form(_status, buttons);
        DockPanel.SetDock(bottom, Dock.Bottom);
        root.Children.Add(bottom);
        var directoryStatus = new DockPanel();
        DockPanel.SetDock(_retry, Dock.Right);
        directoryStatus.Children.Add(_retry);
        directoryStatus.Children.Add(_directoryStatus);
        root.Children.Add(new ScrollViewer
        {
            Content = IdentitySetupDialog.Form(_search, directoryStatus, _list, _selected),
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled
        });
        Content = IdentitySetupDialog.DialogCard(root);
        _list.SelectionChanged += (_, _) => UpdateSelection();
        _search.TextChanged += (_, _) => Filter();
        Closing += (_, args) => args.Cancel = _sending;
        Closed += (_, _) => _closed = true;
        Loaded += async (_, _) => await LoadAsync(services);
        UpdateSelection();
        UpdateOptionsLabel();
    }

    private static Button Action(string text, Action work) => IdentitySetupDialog.ActionButton(text, work, Brushes.Gray);

    private async Task LoadAsync(AppServices services)
    {
        if (_closed || _loading || _sending) return;
        _loading = true;
        _loadFailed = false;
        Filter();
        var local = new Choice(services.Settings.MyUuid,
            NameForCard(services.Settings.Snapshot.Nickname ?? AppStrings.Get("UiContactPublicIdentity")), Local: true);
        try
        {
            var contacts = await Task.Run(services.Database.GetForwardTargets);
            if (_closed) return;
            _entries = [local, .. contacts.Where(contact => contact.Uuid != local.Account)
                .Select(contact => new Choice(contact.Uuid, NameForCard(contact.Alias)))];
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (_closed) return;
            _entries = [local];
            _loadFailed = true;
        }
        finally { _loading = false; }
        Filter();
        _search.Focus();
    }

    private void Filter()
    {
        var match = _search.Text.Trim();
        var previous = (_list.SelectedItem as Choice)?.Account;
        var matches = _entries.Where(item => item.Name.Contains(match, StringComparison.CurrentCultureIgnoreCase) ||
            item.Account.Contains(match, StringComparison.OrdinalIgnoreCase)).ToArray();
        _list.ItemsSource = matches;
        _list.SelectedItem = matches.FirstOrDefault(item => item.Account == previous);
        _directoryStatus.Text = _loading ? AppStrings.Get("UiLoading") :
            _loadFailed ? AppStrings.Get("UiContactLoadFailed") :
            matches.Length == 0 ? AppStrings.Get("UiContactNoResults") : "";
        _retry.Visibility = _loadFailed ? Visibility.Visible : Visibility.Collapsed;
        UpdateSelection();
    }

    private void UpdateSelection()
    {
        var selected = _list.SelectedItem as Choice;
        _selected.Text = selected is null ? "" : $"{selected.Name}\n{selected.Account}";
        _options.IsEnabled = _send.IsEnabled = selected is not null && !_sending;
        _search.IsEnabled = _list.IsEnabled = _cancel.IsEnabled = !_sending;
        _retry.IsEnabled = !_sending;
    }

    private void ChooseOptions()
    {
        var dialog = new SendOptionsDialog(_selection) { Owner = this };
        if (dialog.ShowDialog() != true) return;
        _selection = dialog.Result;
        UpdateOptionsLabel();
    }

    private void UpdateOptionsLabel()
    {
        var summary = SendOptionsText.Format(_selection);
        _options.Content = summary.Length == 0 ? AppStrings.Get("UiSendOptions") : summary;
    }

    private async Task SendAsync()
    {
        if (_sending || _list.SelectedItem is not Choice item) return;
        _sending = true;
        _status.Text = AppStrings.Get("UiReceiptSending");
        UpdateSelection();
        var saved = false;
        try { saved = await _sendCard(item.Account, item.Name, _selection); }
        finally { _sending = false; }
        if (saved) DialogResult = true;
        else
        {
            _status.Text = AppStrings.Get("UiContactCardSendFailed");
            UpdateSelection();
        }
    }

    private static string NameForCard(string name)
    {
        var output = new StringBuilder();
        var remaining = 256;
        foreach (var rune in name.Trim().EnumerateRunes())
        {
            if (Rune.IsControl(rune)) continue;
            remaining -= rune.Utf8SequenceLength;
            if (remaining < 0) break;
            output.Append(rune.ToString());
        }
        var result = output.ToString().Trim();
        return result.Length == 0 ? "User" : result;
    }
}

internal sealed class ContactCardDetailsDialog : DialogWindow
{
    private readonly AppServices _services;
    private readonly Func<string?> _readAccount;
    private readonly TextBox _identity = new() { IsReadOnly = true, TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Button _request;
    private readonly Button _open;
    private readonly Button _retry;
    private bool _closed;
    private bool _sending;
    private bool _queued;
    private int _generation;

    public string? OpenAccount { get; private set; }
    internal Func<string, bool>? ConfirmOpenChat { get; set; }

    public ContactCardDetailsDialog(AppServices services, string peer, string messageId, string name)
        : this(services, name, () => services.Database.GetContactCardAccount(peer, messageId))
    {
    }

    public ContactCardDetailsDialog(AppServices services, string name, Func<string?> readAccount)
    {
        _services = services;
        _readAccount = readAccount;
        Title = AppStrings.Get("UiContactCard");
        Width = Math.Min(450, SystemParameters.WorkArea.Width * .94);
        MaxHeight = SystemParameters.WorkArea.Height * .90;
        SizeToContent = SizeToContent.Height;
        AutomationProperties.SetName(_identity, AppStrings.Get("UiContactPublicId"));
        var heading = IdentitySetupDialog.Heading(string.IsNullOrEmpty(name) ? Title : name);
        heading.TextWrapping = TextWrapping.Wrap;
        var buttons = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 10, 0, 0) };
        var close = IdentitySetupDialog.ActionButton(AppStrings.Get("UiClose"), Close, Brushes.Gray);
        close.IsCancel = true;
        _retry = IdentitySetupDialog.ActionButton(AppStrings.Get("UiRetry"), () => _ = LoadAsync(), Brushes.Gray);
        _request = IdentitySetupDialog.ActionButton(AppStrings.Get("UiContactSendRequest"), () => _ = SendRequestAsync(), Brushes.Gray);
        _request.Style = ResourceStyle("PrimaryButtonStyle");
        _open = IdentitySetupDialog.ActionButton(AppStrings.Get("UiContactOpenChat"), () =>
        {
            var account = _identity.Text;
            if (ConfirmOpenChat?.Invoke(account) == false || _closed) return;
            OpenAccount = account;
            DialogResult = true;
        }, Brushes.Gray);
        _request.Visibility = _open.Visibility = _retry.Visibility = Visibility.Collapsed;
        buttons.Children.Add(close);
        buttons.Children.Add(_retry);
        buttons.Children.Add(_request);
        buttons.Children.Add(_open);
        var root = new DockPanel();
        DockPanel.SetDock(heading, Dock.Top);
        root.Children.Add(heading);
        DockPanel.SetDock(buttons, Dock.Bottom);
        root.Children.Add(buttons);
        root.Children.Add(new ScrollViewer { Content = IdentitySetupDialog.Form(_identity, _status),
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        Content = IdentitySetupDialog.DialogCard(root);
        services.Messaging.ContactsChanged += ContactsChanged;
        Loaded += async (_, _) => await LoadAsync();
        Closing += (_, args) => args.Cancel = _sending;
        Closed += (_, _) =>
        {
            _closed = true;
            services.Messaging.ContactsChanged -= ContactsChanged;
        };
    }

    private void ContactsChanged(object? sender, EventArgs args)
    {
        if (!Dispatcher.HasShutdownStarted) Dispatcher.BeginInvoke(() =>
        {
            if (!_closed && !_sending) _ = LoadAsync();
        });
    }

    private async Task LoadAsync()
    {
        var generation = ++_generation;
        _request.Visibility = _open.Visibility = _retry.Visibility = _identity.Visibility = Visibility.Collapsed;
        _status.Text = AppStrings.Get("UiLoading");
        try
        {
            var result = await Task.Run(() =>
            {
                var id = _readAccount();
                return (Id: id, Contact: id is null ? null : _services.Contacts.List(id).FirstOrDefault(item => item.Id == id));
            });
            if (_closed || generation != _generation) return;
            _identity.Text = result.Id ?? "";
            _identity.Visibility = result.Id is null ? Visibility.Collapsed : Visibility.Visible;
            var contact = result.Contact;
            var local = result.Id == _services.Settings.MyUuid;
            _status.Text = AppStrings.Get(result.Id is null ? "UiContactCardUnavailable" : local ? "UiContactPublicIdentity" :
                contact?.Revoked == true ? "UiContactIdentityReset" : contact?.Blocked == true ? "UiContactBlocked" :
                contact?.Accepted == true ? "UiContactAccepted" : _queued || contact?.Outgoing == true ? "UiContactPending" :
                contact?.Incoming == true ? "UiContactIncoming" : "UiContactNotConnected");
            if (contact is { Accepted: true, Blocked: false, Revoked: false }) _open.Visibility = Visibility.Visible;
            else if (result.Id is not null && !local && !_queued &&
                (contact is null || contact is { Accepted: false, Blocked: false, Revoked: false, Incoming: false, Outgoing: false }))
                _request.Visibility = Visibility.Visible;
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_closed && generation == _generation)
            {
                _status.Text = AppStrings.Get("UiContactLoadFailed");
                _retry.Visibility = Visibility.Visible;
            }
        }
    }

    private async Task SendRequestAsync()
    {
        if (_sending || _identity.Text.Length == 0) return;
        _sending = true;
        _request.IsEnabled = false;
        _status.Text = AppStrings.Get("UiReceiptSending");
        var account = _identity.Text;
        try
        {
            var current = await Task.Run(_readAccount);
            if (current != account)
            {
                await LoadAsync();
                return;
            }
            await _services.Messaging.AddFriendAsync(account);
            _queued = true;
            await LoadAsync();
        }
        catch (Exception error) when (error is InvalidOperationException or ArgumentException or IOException or
            OperationCanceledException or Microsoft.Data.Sqlite.SqliteException)
        {
            _status.Text = AppStrings.Get("UiContactActionFailed");
        }
        finally
        {
            _sending = false;
            _request.IsEnabled = true;
        }
    }
}
