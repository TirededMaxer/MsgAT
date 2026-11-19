// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Views;

internal sealed class CallHistoryDialog : DialogWindow
{
    private readonly MessagingService _messaging;
    private readonly StackPanel _rows = new();
    private readonly TextBlock _status = new() { Margin = new Thickness(0, 8, 0, 8), TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _removalStatus = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Button _older;
    private readonly Button _retry;
    private readonly Button _clear;
    private CallHistoryEntry? _before;
    private bool _loading;
    private bool _closed;
    private bool _refreshPending;
    private bool _retryOlder;
    private bool _missedOnly;
    private bool _removing;
    private readonly StackPanel _filters = new() { Orientation = Orientation.Horizontal };
    private readonly ScrollViewer _scroll = new() { VerticalScrollBarVisibility = ScrollBarVisibility.Auto };

    internal CallHistoryEntry? SelectedCall { get; private set; }
    internal Func<string, bool>? ConfirmOpenChat { get; set; }
    internal bool Redial { get; private set; }

    internal CallHistoryDialog(MessagingService messaging)
    {
        _messaging = messaging;
        Title = AppStrings.Get("UiCallHistory");
        Width = 560; Height = 530; MinWidth = 400; MinHeight = 350;
        ResizeMode = ResizeMode.CanResize;
        var panel = new DockPanel { Margin = new Thickness(20) };
        var header = new StackPanel();
        var titleRow = new DockPanel();
        var clearLabel = AppStrings.Get("UiCallHistoryClearAll");
        _clear = IdentitySetupDialog.ActionButton("\uE74D", () => _ = ConfirmRemovalAsync(null), Brushes.Gray);
        _clear.FontFamily = new FontFamily("Segoe MDL2 Assets");
        _clear.ToolTip = clearLabel;
        _clear.IsEnabled = false;
        System.Windows.Automation.AutomationProperties.SetName(_clear, clearLabel);
        DockPanel.SetDock(_clear, Dock.Right);
        titleRow.Children.Add(_clear);
        titleRow.Children.Add(IdentitySetupDialog.Heading(Title));
        header.Children.Add(titleRow);
        var all = new RadioButton { Content = AppStrings.Get("UiCallHistoryAll"), Margin = new Thickness(0, 0, 12, 0) };
        var missed = new RadioButton { Content = AppStrings.Get("UiCallHistoryMissed") };
        all.IsChecked = true;
        all.Checked += (_, _) => ChangeFilter(false);
        missed.Checked += (_, _) => ChangeFilter(true);
        _filters.Children.Add(all);
        _filters.Children.Add(missed);
        header.Children.Add(_filters);
        _removalStatus.Foreground = (Brush)FindResource("DangerBrush");
        header.Children.Add(_removalStatus);
        header.Children.Add(_status);
        DockPanel.SetDock(header, Dock.Top);
        panel.Children.Add(header);
        var footer = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        _older = IdentitySetupDialog.ActionButton(AppStrings.Get("UiCallHistoryOlder"), () => _ = LoadAsync(append:true), Brushes.Gray);
        _retry = IdentitySetupDialog.ActionButton(AppStrings.Get("UiCallHistoryRetry"), () => _ = LoadAsync(_retryOlder), Brushes.Gray);
        _older.Visibility = _retry.Visibility = Visibility.Collapsed;
        footer.Children.Add(_older);
        footer.Children.Add(_retry);
        var close = IdentitySetupDialog.ActionButton(AppStrings.Get("UiClose"), Close, Brushes.Gray);
        close.IsCancel = true;
        footer.Children.Add(close);
        DockPanel.SetDock(footer, Dock.Bottom);
        panel.Children.Add(footer);
        _scroll.Content = _rows;
        panel.Children.Add(_scroll);
        Content = panel;
        Loaded += async (_, _) => await LoadAsync();
        _messaging.CallHistoryChanged += HistoryChanged;
        Closed += (_, _) => { _closed = true; _messaging.CallHistoryChanged -= HistoryChanged; };
    }

    private void ChangeFilter(bool missedOnly)
    {
        if (_closed || _loading || _removing || _missedOnly == missedOnly) return;
        _missedOnly = missedOnly;
        _rows.Children.Clear();
        _before = null;
        _older.Visibility = Visibility.Collapsed;
        _scroll.ScrollToTop();
        _ = LoadAsync();
    }

    private void HistoryChanged(object? sender, EventArgs args)
    {
        if (Dispatcher.HasShutdownStarted) return;
        Dispatcher.BeginInvoke(() =>
        {
            if (_closed) return;
            if (_loading) _refreshPending = true;
            else _ = LoadAsync();
        });
    }

    private async Task LoadAsync(bool append = false)
    {
        if (_loading || _closed) return;
        _loading = true;
        RefreshActions();
        _retry.Visibility = Visibility.Collapsed;
        _status.Text = AppStrings.Get("UiLoading");
        var before = append ? _before : null;
        var count = append ? 40 : Math.Max(40, _rows.Children.Count);
        var missedOnly = _missedOnly;
        try
        {
            var page = await Task.Run(() => _messaging.CallHistoryPage(before, count, missedOnly));
            if (_closed) return;
            var offset = _scroll.VerticalOffset;
            if (!append)
            {
                _rows.Children.Clear();
                _before = null;
            }
            foreach (var entry in page.Take(count))
            {
                _rows.Children.Add(BuildRow(entry));
                _before = entry;
            }
            if (!append) _scroll.ScrollToVerticalOffset(offset);
            _older.Visibility = page.Count > count ? Visibility.Visible : Visibility.Collapsed;
            _status.Text = _rows.Children.Count == 0
                ? AppStrings.Get(missedOnly ? "UiCallHistoryMissedEmpty" : "UiCallHistoryEmpty")
                : "";
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (_closed) return;
            _retryOlder = append;
            _status.Text = AppStrings.Get("UiCallHistoryFailed");
            _retry.Visibility = Visibility.Visible;
        }
        finally
        {
            if (!_closed)
            {
                _loading = false;
                RefreshActions();
                if (_refreshPending) { _refreshPending = false; _ = LoadAsync(); }
            }
        }
    }

    private void RefreshActions()
    {
        _rows.IsEnabled = !_removing;
        _filters.IsEnabled = _older.IsEnabled = _retry.IsEnabled = !_loading && !_removing;
        _clear.Visibility = _missedOnly ? Visibility.Collapsed : Visibility.Visible;
        _clear.IsEnabled = !_loading && !_removing && _rows.Children.Count > 0;
    }

    private async Task ConfirmRemovalAsync(CallHistoryEntry? entry)
    {
        if (_closed || _removing) return;
        var title = AppStrings.Get(entry is null ? "UiCallHistoryClearAll" : "UiCallHistoryRemoveEntry");
        var confirm = new ConfirmationDialog(AppStrings.Get("UiCallHistoryRemoveLocal"), title,
            confirmLabel: AppStrings.Get("UiLocalDelete")) { Owner = this };
        if (confirm.ShowDialog() != true || _closed) return;
        _removing = true;
        _removalStatus.Text = "";
        RefreshActions();
        try
        {
            if (!await _messaging.RemoveCallHistoryAsync(entry) && !_closed)
                _removalStatus.Text = AppStrings.Get("UiCallHistoryRemoveFailed");
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or
            Microsoft.Data.Sqlite.SqliteException or OperationCanceledException)
        {
            if (!_closed) _removalStatus.Text = AppStrings.Get("UiCallHistoryRemoveFailed");
        }
        finally
        {
            _removing = false;
            if (!_closed) RefreshActions();
        }
    }

    private FrameworkElement BuildRow(CallHistoryEntry entry)
    {
        var panel = new DockPanel { Margin = new Thickness(0, 3, 0, 3) };
        var call = IdentitySetupDialog.ActionButton(entry.Video ? "\uE714" : "\uE717", () => Select(entry,true), Brushes.Gray);
        call.FontFamily = new FontFamily("Segoe MDL2 Assets");
        call.ToolTip = AppStrings.Get(entry.Video ? "UiVideoCall" : "UiVoiceCall");
        System.Windows.Automation.AutomationProperties.SetName(call, (string)call.ToolTip);
        DockPanel.SetDock(call, Dock.Right);
        panel.Children.Add(call);
        var body = new DockPanel { LastChildFill = true };
        var avatar = IdentitySetupDialog.Avatar(entry.AvatarPath, entry.Alias, 40, 20);
        avatar.Margin = new Thickness(0, 0, 10, 0);
        avatar.VerticalAlignment = VerticalAlignment.Center;
        DockPanel.SetDock(avatar, Dock.Left);
        body.Children.Add(avatar);
        var text = new StackPanel { Margin = new Thickness(2, 4, 2, 4) };
        text.Children.Add(new TextBlock { Text = entry.Alias, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
        var duration = entry.Outcome == CallOutcome.Completed
            ? $" · {entry.Duration / 60:00}:{entry.Duration % 60:00}" : "";
        var direction = AppStrings.Get(entry.Outgoing ? "UiCallOutgoing" : "UiCallIncoming");
        var summary = direction + " · " + AppStrings.Get("UiCall" + entry.Outcome) + duration;
        var tone = (Brush)FindResource(entry.Outcome == CallOutcome.Missed ? "DangerBrush" : "MutedBrush");
        var detail = new DockPanel();
        var arrow = new System.Windows.Shapes.Path
        {
            Data = Geometry.Parse(entry.Outgoing ? "M 2,10 L 10,2 M 4,2 L 10,2 L 10,8" : "M 10,2 L 2,10 M 2,4 L 2,10 L 8,10"),
            Width = 10, Height = 10, Stretch = Stretch.Uniform, Stroke = tone, StrokeThickness = 1.4,
            VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 5, 0)
        };
        detail.Children.Add(arrow);
        detail.Children.Add(new TextBlock { Text = summary, Foreground = tone, TextWrapping = TextWrapping.Wrap });
        text.Children.Add(detail);
        text.Children.Add(new TextBlock
        {
            Text = DateTimeOffset.FromUnixTimeMilliseconds(entry.EndedAt).ToLocalTime().ToString("g", AppStrings.InterfaceCulture),
            FontSize = 11, Foreground = (Brush)FindResource("MutedBrush")
        });
        body.Children.Add(text);
        var open = new Button
        {
            Content = body, HorizontalContentAlignment = HorizontalAlignment.Stretch, Background = Brushes.Transparent,
            BorderThickness = new Thickness(0), Padding = new Thickness(6, 0, 6, 0)
        };
        System.Windows.Automation.AutomationProperties.SetName(open, entry.Alias);
        System.Windows.Automation.AutomationProperties.SetHelpText(open, summary);
        open.Click += (_, _) => Select(entry,false);
        var menu = new ContextMenu();
        var remove = new MenuItem { Header = AppStrings.Get("UiCallHistoryRemoveEntry") };
        remove.Click += async (_, _) => await ConfirmRemovalAsync(entry);
        menu.Items.Add(remove);
        open.ContextMenu = menu;
        panel.Children.Add(open);
        return panel;
    }

    private void Select(CallHistoryEntry entry, bool redial)
    {
        if (_removing) return;
        if (!redial && (ConfirmOpenChat?.Invoke(entry.Peer) == false || !IsVisible)) return;
        SelectedCall = entry;
        Redial = redial;
        DialogResult = true;
    }
}
