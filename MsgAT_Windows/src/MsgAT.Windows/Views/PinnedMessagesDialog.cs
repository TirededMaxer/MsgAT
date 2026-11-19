// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Views;

public sealed class PinnedMessagesDialog : DialogWindow
{
    private readonly AppServices _services;
    private readonly string _peer;
    private readonly ListBox _list = new();
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 8) };
    private readonly TextBlock _error = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Button _older, _newer, _unpin, _open, _retry;
    private readonly DispatcherTimer _refresh = new() { Interval = TimeSpan.FromMilliseconds(100) };
    private bool _closed, _loading, _reload, _busy, _hasMore, _opening;
    private int _offset;
    private int? _failedOffset;

    public string? SelectedId { get; private set; }

    public PinnedMessagesDialog(AppServices services, string peer)
    {
        _services = services;
        _peer = peer;
        Title = AppStrings.Get("UiPinnedMessages");
        Width = 520;
        Height = 540;
        MinWidth = 360;
        MinHeight = 320;
        SetResourceReference(BackgroundProperty, "MessagingChromeBrush");
        _list.SetResourceReference(Control.BackgroundProperty, "MessagingTimelineBrush");
        _list.SetResourceReference(Control.BorderBrushProperty, "MessagingDividerBrush");
        _list.ItemContainerStyle = ResourceStyle("ConversationItemStyle");
        var panel = new DockPanel { Margin = new Thickness(20) };
        var header = new StackPanel();
        header.Children.Add(IdentitySetupDialog.Heading(Title));
        header.Children.Add(_status);
        _error.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
        header.Children.Add(_error);
        DockPanel.SetDock(header, Dock.Top);
        panel.Children.Add(header);

        var actions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        _retry = ButtonFor("UiRetry", () => { if (_failedOffset is { } offset) _ = LoadAsync(offset); });
        _newer = ButtonFor("UiQueuePrevious", () => _ = LoadAsync(Math.Max(0, _offset - 50)));
        _older = ButtonFor("UiQueueNext", () => _ = LoadAsync(_offset + 50));
        _open = IdentitySetupDialog.ActionButton(AppStrings.Get("UiShowInChat"), Open, Brushes.Lime);
        _unpin = ButtonFor("UiUnpinMessage", () => _ = UnpinAsync());
        foreach (var button in new[] { _retry, _newer, _older, _open, _unpin, ButtonFor("UiClose", Close) })
            actions.Children.Add(button);
        var footer = new Border { Child = actions, BorderThickness = new Thickness(0, 1, 0, 0),
            Padding = new Thickness(0, 8, 0, 0), Margin = new Thickness(0, 8, 0, 0) };
        footer.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        DockPanel.SetDock(footer, Dock.Bottom);
        panel.Children.Add(footer);
        panel.Children.Add(_list);
        Content = panel;

        _list.SelectionChanged += (_, _) => EnableActions();
        _list.MouseDoubleClick += (_, _) => Open();
        _list.KeyDown += (_, e) => { if (e.Key == Key.Return) { Open(); e.Handled = true; } };
        _refresh.Tick += async (_, _) => { _refresh.Stop(); await LoadAsync(); };
        _services.Database.Changed += DatabaseChanged;
        Loaded += async (_, _) => await LoadAsync();
        Closing += (_, e) => { if (_busy) e.Cancel = true; };
        Closed += (_, _) => { _closed = true; _refresh.Stop(); _services.Database.Changed -= DatabaseChanged; };
        EnableActions();
    }

    private static Button ButtonFor(string key, Action action) =>
        IdentitySetupDialog.ActionButton(AppStrings.Get(key), action, Brushes.Gray);

    private void DatabaseChanged(object? sender, EventArgs e)
    {
        if (_closed || Dispatcher.HasShutdownStarted || e is MessageDeliveryChangedEventArgs or VisibleMessagesReadEventArgs) return;
        Dispatcher.BeginInvoke(() => { if (!_closed && !_refresh.IsEnabled) _refresh.Start(); });
    }

    private PinnedMessageItem? Selection => (_list.SelectedItem as ListBoxItem)?.Tag as PinnedMessageItem;

    private void EnableActions()
    {
        var available = !_loading && !_busy && !_opening;
        _retry.Visibility = _failedOffset.HasValue ? Visibility.Visible : Visibility.Collapsed;
        _retry.IsEnabled = available;
        _newer.IsEnabled = available && _offset > 0;
        _older.IsEnabled = available && _hasMore;
        _open.IsEnabled = _unpin.IsEnabled = available && Selection is not null;
    }

    private async Task LoadAsync(int? destination = null)
    {
        if (_closed) return;
        if (_loading || _busy) { _reload = true; return; }
        if (destination is null && _failedOffset.HasValue) return;
        _loading = true;
        _status.Text = AppStrings.Get("UiLoading");
        EnableActions();
        var requested = destination ?? _offset;
        try
        {
            do
            {
                _reload = false;
                var page = await _services.Messaging.PinnedMessagesAsync(_peer, requested);
                if (_closed) return;
                if (page.Items.Count == 0 && requested > 0)
                {
                    requested -= 50;
                    _reload = true;
                    continue;
                }
                ApplyRows(page.Items, requested == _offset);
                _offset = requested;
                _hasMore = page.HasMore;
                _failedOffset = null;
                _status.Text = page.Items.Count == 0 ? AppStrings.Get("UiPinsEmpty") : "";
            } while (_reload && !_closed);
        }
        catch (Exception error) when (error is InvalidOperationException or OperationCanceledException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_closed)
            {
                _failedOffset = requested;
                _status.Text = AppStrings.Get("UiPinsLoadFailed");
            }
        }
        finally { _loading = false; if (!_closed) EnableActions(); }
    }

    private void ApplyRows(IReadOnlyList<PinnedMessageItem> rows, bool samePage)
    {
        var selected = samePage ? Selection?.Id : null;
        var restoreFocus = samePage && _list.IsKeyboardFocusWithin;
        var previous = _list.Items.Cast<ListBoxItem>().ToDictionary(item => ((PinnedMessageItem)item.Tag).Id, StringComparer.Ordinal);
        var desired = rows.Select(row => previous.TryGetValue(row.Id, out var prior) &&
            Equals(prior.Tag, row) ? prior : CreateRow(row)).ToArray();
        var retained = desired.ToHashSet();
        for (var index = _list.Items.Count - 1; index >= 0; index--)
            if (_list.Items[index] is ListBoxItem item && !retained.Contains(item)) _list.Items.RemoveAt(index);
        for (var index = 0; index < desired.Length; index++)
        {
            var item = desired[index];
            var oldIndex = _list.Items.IndexOf(item);
            if (oldIndex == index) continue;
            if (oldIndex >= 0) _list.Items.RemoveAt(oldIndex);
            _list.Items.Insert(index, item);
        }
        if (selected is not null)
        {
            _list.SelectedItem = desired.FirstOrDefault(item => ((PinnedMessageItem)item.Tag).Id == selected);
            if (restoreFocus && !_list.IsKeyboardFocusWithin && _list.SelectedItem is ListBoxItem row)
                row.Focus();
        }
        if (!samePage)
        {
            _list.SelectedItem = null;
            if (desired.Length > 0) _list.ScrollIntoView(desired[0]);
        }
    }

    private static ListBoxItem CreateRow(PinnedMessageItem row)
    {
        var content = new StackPanel { Margin = new Thickness(6) };
        content.Children.Add(new MessageTextBlock
        {
            MessageText = row.Summary, IsSummary = true, TextWrapping = TextWrapping.Wrap, MaxHeight = 64
        });
        content.Children.Add(new TextBlock
        {
            Text = DateTimeOffset.FromUnixTimeMilliseconds(row.Timestamp).ToLocalTime().ToString("g", AppStrings.InterfaceCulture),
            FontSize = 11, Opacity = .75
        });
        return new ListBoxItem { Content = content, Tag = row };
    }

    private void Open() => _ = OpenAsync();

    private async Task OpenAsync()
    {
        if (_closed || _loading || _busy || _opening || Selection is not { } item) return;
        _opening = true;
        _error.Text = "";
        EnableActions();
        try
        {
            var available = await Task.Run(() =>
                _services.Database.HasHistoryAnchor(_peer, item.Id, includeRevoked: false));
            if (_closed || Selection?.Id != item.Id) return;
            if (!available)
            {
                _error.Text = AppStrings.Get("UiSearchTargetUnavailable");
                return;
            }
            SelectedId = item.Id;
            DialogResult = true;
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_closed) _error.Text = AppStrings.Get("UiHistoryLoadFailed");
        }
        finally
        {
            _opening = false;
            if (!_closed) EnableActions();
        }
    }

    private async Task UnpinAsync()
    {
        if (_closed || _busy || _loading || Selection is not { } item) return;
        var label = AppStrings.Get("UiUnpinMessage");
        var confirm = new ConfirmationDialog(AppStrings.Get("UiUnpinForBoth"), label, confirmLabel: label)
        {
            Owner = this
        };
        if (confirm.ShowDialog() != true || _closed || _busy) return;
        _busy = true;
        _error.Text = "";
        EnableActions();
        try { await _services.Messaging.SetMessagePinnedAsync(_peer, item.Id, false); }
        catch (Exception error) when (error is InvalidOperationException or OperationCanceledException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_closed) _error.Text = AppStrings.Get("UiPinChangeFailed");
        }
        finally
        {
            _busy = false;
            if (!_closed) EnableActions();
        }
        if (!_closed) await LoadAsync(_offset);
    }
}
