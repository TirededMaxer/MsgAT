// SPDX-License-Identifier: AGPL-3.0-only
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using TextBox = System.Windows.Controls.TextBox;
using CheckBox = System.Windows.Controls.CheckBox;

namespace MsgAT.Windows.Views;

public sealed record MessageSearchInput(string Query, bool All, MessageSearchKind Kind,
    string? SelectedMessage = null, int LoadedCount = 0, DateTime? Day = null, int? SelectedIndex = null);

public sealed class MessageSearchDialog : DialogWindow
{
    private sealed record SearchKindOption(MessageSearchKind Kind, string Label);
    private readonly AppServices _services;
    private readonly string? _peer;
    private readonly TextBox _query = IdentitySetupDialog.Field();
    private readonly CheckBox _all;
    private readonly ComboBox _kind = new() { DisplayMemberPath = nameof(SearchKindOption.Label), MinWidth = 108 };
    private readonly DatePicker _date = new() { DisplayDateEnd = new DateTime(9999, 12, 30) };
    private readonly ListBox _results = new() { HorizontalContentAlignment=HorizontalAlignment.Stretch };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 8) };
    private readonly TextBlock _openError = new() { TextWrapping = TextWrapping.Wrap };
    private readonly ProgressBar _progress = new() { Height = 3, IsIndeterminate = true, Visibility = Visibility.Hidden };
    private readonly Button _retrySearch = new() { Visibility = Visibility.Collapsed };
    private readonly Button _previousResult = new() { IsEnabled = false };
    private readonly Button _nextResult = new() { IsEnabled = false };
    private readonly Button _open;
    private readonly TextBlock _position = new() { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(8, 0, 0, 0) };
    private readonly DispatcherTimer _debounce = new() { Interval=TimeSpan.FromMilliseconds(250) };
    private MessageSearchCursor? _cursor;
    private long _generation;
    private bool _closed;
    private bool _queryComposing;
    private bool _loading;
    private bool _opening;
    private bool _refreshPending;
    private bool _retryOlder;
    private (ListBoxItem Row, bool Focus)? _pageAdvance;
    private MessageSearchInput? _loadedInput;
    private MessageSearchInput? _resume;

    public MessageSearchDialog(AppServices services, string? peer = null, MessageSearchInput? initial = null)
    {
        _services=services; _peer=peer; _resume=initial;
        Title=AppStrings.Get("UiSearchMessages"); Width=640; Height=580; MinWidth=420; MinHeight=400; ResizeMode=ResizeMode.CanResize;
        SetResourceReference(BackgroundProperty, "MessagingChromeBrush");
        _results.SetResourceReference(Control.BackgroundProperty, "MessagingTimelineBrush");
        _results.SetResourceReference(Control.BorderBrushProperty, "MessagingDividerBrush");
        _results.ItemContainerStyle = ResourceStyle("ConversationItemStyle");
        _openError.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
        System.Windows.Automation.AutomationProperties.SetLiveSetting(_openError, System.Windows.Automation.AutomationLiveSetting.Polite);
        System.Windows.Automation.AutomationProperties.SetName(_progress, AppStrings.Get("UiLoading"));
        _query.Text = initial?.Query ?? "";
        SearchKindOption[] options =
        [
            new(MessageSearchKind.All, AppStrings.Get("UiSearchAllTypes")),
            new(MessageSearchKind.Text, AppStrings.Get("UiSearchTextType")),
            new(MessageSearchKind.Attachments, AppStrings.Get("UiAttachments"))
        ];
        _kind.ItemsSource = options;
        _kind.SelectedItem = options.FirstOrDefault(option => option.Kind == initial?.Kind) ?? options[0];
        _date.SelectedDate = initial?.Day;
        _all = new CheckBox
        {
            Content = AppStrings.Get("UiSearchAllConversations"), IsChecked = peer is null || initial?.All == true,
            Visibility = peer is null ? Visibility.Collapsed : Visibility.Visible, VerticalAlignment = VerticalAlignment.Center
        };
        System.Windows.Automation.AutomationProperties.SetName(_query,Title);
        System.Windows.Automation.AutomationProperties.SetName(_kind,AppStrings.Get("UiSearchMessageType"));
        _kind.SelectionChanged += (_, _) => Schedule();
        _results.MouseDoubleClick += (_, args) =>
        {
            if (args.OriginalSource is not DependencyObject source ||
                ItemsControl.ContainerFromElement(_results, source) is not ListBoxItem row) return;
            _results.SelectedItem = row;
            args.Handled = true;
            SelectResult();
        };
        _results.PreviewKeyDown += async (_, args) =>
        {
            if (args.Key == Key.Return) { args.Handled = true; SelectResult(); }
            else if (args.Key == Key.Down && Keyboard.Modifiers == ModifierKeys.None &&
                     _results.SelectedIndex == _results.Items.Count - 1 && _nextResult.IsEnabled)
            {
                args.Handled = true;
                await MoveSelectionAsync(1, focusResult: true);
            }
        };
        TextCompositionManager.AddPreviewTextInputStartHandler(_query, (_, _) => _queryComposing = true);
        TextCompositionManager.AddPreviewTextInputUpdateHandler(_query, (_, _) => _queryComposing = true);
        TextCompositionManager.AddPreviewTextInputHandler(_query, (_, _) => _queryComposing = false);
        _query.LostKeyboardFocus += (_, _) => _queryComposing = false;
        _query.PreviewKeyDown += async (_, args) =>
        {
            if (_queryComposing || Keyboard.Modifiers != ModifierKeys.None) return;
            if (args.Key is Key.Up or Key.Down)
            {
                args.Handled = true;
                await MoveSelectionAsync(args.Key == Key.Up ? -1 : 1, focusResult: false);
            }
            else if (args.Key == Key.Return) { args.Handled = true; SelectResult(); }
        };
        PreviewKeyDown += (_, args) =>
        {
            if (args.Key != Key.Escape || _kind.IsDropDownOpen || _date.IsDropDownOpen ||
                (_query.IsKeyboardFocusWithin && _queryComposing)) return;
            args.Handled = true;
            Close();
        };
        _query.TextChanged+=(_,_)=>Schedule();
        _all.Checked+=(_,_)=>Schedule();_all.Unchecked+=(_,_)=>Schedule();
        ConfigureResultButton(_previousResult, "\uE70E", "UiSearchPreviousResult");
        ConfigureResultButton(_nextResult, "\uE70D", "UiSearchNextResult");
        _previousResult.Click += async (_, _) => await MoveSelectionAsync(-1, focusResult: false);
        _nextResult.Click += async (_, _) => await MoveSelectionAsync(1, focusResult: false);
        _debounce.Tick += async (_, _) =>
        {
            _debounce.Stop();
            if (HasFailedPage) return;
            await LoadAsync(reset: true, refresh: _refreshPending && _loadedInput == Criteria);
        };
        var controls = new StackPanel();
        controls.Children.Add(_query);
        var filters = new DockPanel { Margin = new Thickness(0, 8, 0, 0) };
        DockPanel.SetDock(_kind, Dock.Right);
        filters.Children.Add(_kind);
        filters.Children.Add(_all);
        controls.Children.Add(filters);
        var dates = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 8, 0, 0) };
        var dateLabel = AppStrings.Get("UiDateLabel");
        dates.Children.Add(new System.Windows.Controls.Label { Content = dateLabel, Target = _date });
        System.Windows.Automation.AutomationProperties.SetName(_date, dateLabel);
        dates.Children.Add(_date);
        var clearDate = new Button { Margin = new Thickness(4, 0, 0, 0), IsEnabled = _date.SelectedDate.HasValue };
        ConfigureResultButton(clearDate, "\uE711", "UiSearchClearDate");
        clearDate.Click += (_, _) => _date.SelectedDate = null;
        _date.SelectedDateChanged += (_, _) =>
        {
            clearDate.IsEnabled = _date.SelectedDate.HasValue;
            Schedule();
        };
        dates.Children.Add(clearDate);
        controls.Children.Add(dates);
        var feedback = new DockPanel();
        _retrySearch.Content = AppStrings.Get("UiRetry");
        _retrySearch.Click += async (_, _) =>
        {
            if (!_loading && !_closed)
                await LoadAsync(reset: !_retryOlder, refresh: !_retryOlder && _loadedInput == Criteria);
        };
        DockPanel.SetDock(_retrySearch, Dock.Right);
        feedback.Children.Add(_retrySearch);
        feedback.Children.Add(_status);
        controls.Children.Add(feedback);
        controls.Children.Add(_openError);
        controls.Children.Add(_progress);
        var panel=new DockPanel { LastChildFill=true,Margin=new Thickness(20) };
        DockPanel.SetDock(controls,Dock.Top);panel.Children.Add(controls);
        var footer = new DockPanel { Margin = new Thickness(0, 8, 0, 0) };
        var navigation = new StackPanel { Orientation = Orientation.Horizontal };
        navigation.Children.Add(_previousResult);
        navigation.Children.Add(_nextResult);
        navigation.Children.Add(_position);
        DockPanel.SetDock(navigation, Dock.Left);
        footer.Children.Add(navigation);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        _open = IdentitySetupDialog.ActionButton(AppStrings.Get("UiOpen"), SelectResult, Brushes.Lime);
        _open.IsEnabled = false;
        _results.SelectionChanged += (_, _) =>
        {
            if (_pageAdvance is { } advance && !ReferenceEquals(_results.SelectedItem, advance.Row))
                _pageAdvance = null;
            _openError.Text = "";
            UpdateResultNavigation();
        };
        actions.Children.Add(IdentitySetupDialog.ActionButton(AppStrings.Get("UiClose"), Close, Brushes.Gray));
        actions.Children.Add(_open);
        footer.Children.Add(actions);
        var footerFrame = new Border { Child = footer, BorderThickness = new Thickness(0, 1, 0, 0),
            Margin = new Thickness(0, 8, 0, 0) };
        footerFrame.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        DockPanel.SetDock(footerFrame, Dock.Bottom);
        panel.Children.Add(footerFrame);
        panel.Children.Add(_results);
        Content = panel;
        _services.Database.Changed+=DatabaseChanged;
        Closed+=(_,_)=>{_closed=true;++_generation;_debounce.Stop();_services.Database.Changed-=DatabaseChanged;};
        Loaded += (_, _) => { _query.Focus(); _query.CaretIndex = _query.Text.Length; Schedule(); };
    }

    private static void ConfigureResultButton(Button button, string glyph, string label)
    {
        button.Width = 34;
        button.Content = new TextBlock { Text = glyph, FontFamily = new FontFamily("Segoe MDL2 Assets") };
        button.ToolTip = AppStrings.Get(label);
        System.Windows.Automation.AutomationProperties.SetName(button, AppStrings.Get(label));
    }

    private void UpdateResultNavigation()
    {
        var count = _results.Items.Count;
        var index = _results.SelectedIndex;
        var hasResults = _loadedInput == Criteria && count > 0;
        _progress.Visibility = _loading || _opening ? Visibility.Visible : Visibility.Hidden;
        _open.IsEnabled = hasResults && index >= 0 && !_opening;
        _previousResult.IsEnabled = hasResults && index > 0 && !_opening;
        _nextResult.IsEnabled = hasResults && !_opening && (index < count - 1 || (!_loading && _cursor is not null));
        _position.Text = hasResults
            ? AppStrings.Format("UiSearchResultPosition", index + 1, count)
            : "";
    }

    public MessageSearchHit? SelectedHit { get; private set; }
    internal Func<MessageSearchHit, bool>? ConfirmOpen { get; set; }
    private MessageSearchInput Criteria => new(_query.Text, _all.IsChecked == true,
        _kind.SelectedItem is SearchKindOption option ? option.Kind : MessageSearchKind.All, Day: _date.SelectedDate?.Date);
    public MessageSearchInput Input => _loadedInput == Criteria
        ? Criteria with
        {
            SelectedMessage = (_results.SelectedItem as ListBoxItem)?.Tag is MessageSearchHit hit ? hit.MessageId : null,
            LoadedCount = _results.Items.Count,
            SelectedIndex = _results.SelectedIndex >= 0 ? _results.SelectedIndex : null
        }
        : _resume ?? Criteria;

    private void DatabaseChanged(object? sender,EventArgs args)
    {
        if (_closed || Dispatcher.HasShutdownStarted) return;
        Dispatcher.BeginInvoke(() =>
        {
            if (_closed || string.IsNullOrWhiteSpace(_query.Text)) return;
            _refreshPending = true;
            if (!_loading && !HasFailedPage && !_debounce.IsEnabled) _debounce.Start();
        });
    }

    private void Schedule()
    {
        ++_generation;
        _loading = false;
        _refreshPending = false;
        _pageAdvance = null;
        _retrySearch.Visibility = Visibility.Collapsed;
        _cursor = null;
        if (_resume is { } resume && (resume with { SelectedMessage = null, LoadedCount = 0, SelectedIndex = null }) != Criteria) _resume = null;
        if (_loadedInput != Criteria) _results.Items.Clear();
        _status.Text=string.IsNullOrWhiteSpace(_query.Text)?"":AppStrings.Get("UiSearchSearching");
        UpdateResultNavigation();
        _debounce.Stop();
        if (!string.IsNullOrWhiteSpace(_query.Text)) _debounce.Start();
    }

    private bool HasFailedPage => _retryOlder && _retrySearch.Visibility == Visibility.Visible;

    private async Task LoadAsync(bool reset, bool refresh = false)
    {
        if (_closed || _loading) return;
        var generation = _generation;
        var input = Criteria;
        var peer = input.All ? null : _peer;
        var cursor = reset ? null : _cursor;
        if (string.IsNullOrWhiteSpace(input.Query)) { _status.Text = ""; return; }
        if (!reset && cursor is null) return;
        var limit = reset
            ? Math.Max(50, Math.Max(_resume?.LoadedCount ?? 0, refresh ? _results.Items.Count : 0))
            : 50;
        if (reset) _refreshPending = false;
        _loading = true;
        _retrySearch.Visibility = Visibility.Collapsed;
        UpdateResultNavigation();
        try
        {
            var page = await Task.Run(() => _services.Search.Search(input.Query, peer, cursor, input.Kind, limit, day: input.Day));
            if (_closed || generation != _generation || Criteria != input) return;
            ApplyResults(page.Hits, replace: reset);
            _loadedInput = input;
            if (reset && _resume is { } resume)
            {
                _resume = null;
                var row = _results.Items.Cast<ListBoxItem>().FirstOrDefault(item =>
                    item.Tag is MessageSearchHit hit && hit.MessageId == resume.SelectedMessage);
                if (row is not null)
                {
                    _results.SelectedItem = row;
                    _results.ScrollIntoView(row);
                }
            }
            _cursor = page.Next;
            if (!reset && _pageAdvance is { } advance)
            {
                _pageAdvance = null;
                if (ReferenceEquals(_results.SelectedItem, advance.Row))
                    SelectResultAt(_results.SelectedIndex + 1, advance.Focus);
            }
            _status.Text = _results.Items.Count == 0 ? AppStrings.Get("UiSearchNoResults") :
                AppStrings.Format("UiSearchResultsCount", _results.Items.Count);
        }
        catch (Exception error) when (error is InvalidOperationException or ArgumentException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_closed && generation == _generation)
            {
                _retryOlder = !reset;
                _status.Text = AppStrings.Get("UiSearchFailed");
                _retrySearch.Visibility = Visibility.Visible;
            }
        }
        finally
        {
            if (!_closed && generation == _generation)
            {
                _loading = false;
                UpdateResultNavigation();
                if (_refreshPending && !HasFailedPage && !_debounce.IsEnabled) _debounce.Start();
            }
        }
    }

    private void ApplyResults(IReadOnlyList<MessageSearchHit> hits, bool replace)
    {
        var selected = _results.SelectedItem as ListBoxItem;
        var focusInResults = _results.IsKeyboardFocusWithin;
        if (replace)
        {
            var retained = hits.Select(hit => hit.MessageId).ToHashSet(StringComparer.Ordinal);
            for (var index = _results.Items.Count - 1; index >= 0; index--)
            {
                var hit = (MessageSearchHit)((ListBoxItem)_results.Items[index]).Tag;
                if (!retained.Contains(hit.MessageId)) _results.Items.RemoveAt(index);
            }
        }
        var existing = _results.Items.Cast<ListBoxItem>().ToDictionary(row => ((MessageSearchHit)row.Tag).MessageId, StringComparer.Ordinal);
        var position = replace ? 0 : _results.Items.Count;
        foreach (var hit in hits)
        {
            if (!existing.TryGetValue(hit.MessageId, out var row))
            {
                _results.Items.Insert(position++, new ListBoxItem { Content = BuildResult(hit), Tag = hit });
                continue;
            }
            if (!replace) continue;
            if (!ReferenceEquals(_results.Items[position], row))
            {
                _results.Items.Remove(row);
                _results.Items.Insert(position, row);
            }
            position++;
            if ((MessageSearchHit)row.Tag != hit)
            {
                row.Tag = hit;
                row.Content = BuildResult(hit);
            }
        }
        if (selected is not null && _results.Items.Contains(selected)) _results.SelectedItem = selected;
        else if (_results.SelectedIndex < 0 && _results.Items.Count > 0) _results.SelectedIndex = 0;
        if (focusInResults && !_results.IsKeyboardFocusWithin && _results.SelectedItem is ListBoxItem focused) focused.Focus();
    }

    private StackPanel BuildResult(MessageSearchHit hit)
    {
        var content = new StackPanel { Margin = new Thickness(6) };
        var header = new DockPanel();
        var timestamp = hit.Timestamp is >= 0 and <= 253402300799999L
            ? DateTimeOffset.FromUnixTimeMilliseconds(hit.Timestamp).LocalDateTime.ToString("g", CultureInfo.CurrentCulture) : "";
        var time = new TextBlock { Text = timestamp, FontSize = 11, Margin = new Thickness(8, 0, 0, 0),
            Foreground = (Brush)FindResource("MutedBrush") };
        DockPanel.SetDock(time, Dock.Right);
        header.Children.Add(time);
        header.Children.Add(new TextBlock { Text = hit.Alias, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
        content.Children.Add(header);
        if (hit.IsAttachment) content.Children.Add(new TextBlock
        {
            Text = AppStrings.Get("UiSearchAttachment"), FontSize = 11, Foreground = (Brush)FindResource("AccentBrush")
        });
        var summary = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap, TextTrimming = TextTrimming.CharacterEllipsis, MaxHeight = 56
        };
        var preview = hit.Preview;
        var needle = _query.Text.Trim();
        var start = 0;
        if (needle.Length > 0)
        {
            for (var match = preview.IndexOf(needle, start, StringComparison.OrdinalIgnoreCase); match >= 0;
                 match = preview.IndexOf(needle, start, StringComparison.OrdinalIgnoreCase))
            {
                if (match > start) summary.Inlines.Add(new Run(preview[start..match]));
                start = match + needle.Length;
                summary.Inlines.Add(new Run(preview[match..start]) { FontWeight = FontWeights.SemiBold });
            }
        }
        summary.Inlines.Add(new Run(preview[start..]));
        content.Children.Add(summary);
        return content;
    }

    private async void SelectResult()
    {
        var criteria = Criteria;
        if (_opening || _loadedInput != criteria || _results.SelectedItem is not ListBoxItem { Tag: MessageSearchHit hit }) return;
        _opening = true;
        _openError.Text = "";
        UpdateResultNavigation();
        try
        {
            bool IsCurrent() => !_closed && Criteria == criteria &&
                _results.SelectedItem is ListBoxItem { Tag: MessageSearchHit selected } && selected.MessageId == hit.MessageId;
            var available = await Task.Run(() => _services.Database.HasHistoryAnchor(hit.Peer, hit.MessageId, includeRevoked: false));
            if (!IsCurrent()) return;
            if (!available)
            {
                _openError.Text = AppStrings.Get("UiSearchTargetUnavailable");
                return;
            }
            if (ConfirmOpen?.Invoke(hit) == false || !IsCurrent()) return;
            if (!_services.Database.HasHistoryAnchor(hit.Peer, hit.MessageId, includeRevoked: false))
            {
                _openError.Text = AppStrings.Get("UiSearchTargetUnavailable");
                return;
            }
            SelectedHit = hit;
            DialogResult = true;
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_closed && Criteria == criteria) _openError.Text = AppStrings.Get("UiSearchFailed");
        }
        finally
        {
            _opening = false;
            if (!_closed) UpdateResultNavigation();
        }
    }

    private async Task MoveSelectionAsync(int delta, bool focusResult)
    {
        if (_loadedInput != Criteria || _results.Items.Count == 0) return;
        var index = _results.SelectedIndex;
        var next = (index >= 0 ? index : delta > 0 ? -1 : _results.Items.Count) + delta;
        if (next == _results.Items.Count && !_loading && _cursor is not null &&
            _results.SelectedItem is ListBoxItem anchor)
        {
            _pageAdvance = (anchor, focusResult);
            await LoadAsync(reset: false);
            return;
        }
        if (SelectResultAt(next, focusResult)) _pageAdvance = null;
    }

    private bool SelectResultAt(int index, bool focusResult)
    {
        if (index < 0 || index >= _results.Items.Count || _results.Items[index] is not ListBoxItem row) return false;
        _results.SelectedItem = row;
        _results.ScrollIntoView(row);
        if (focusResult) row.Focus();
        return true;
    }
}

public sealed class ChatHistoryDialog : DialogWindow
{
    private readonly DatePicker _date=new() {SelectedDate=DateTime.Today,Margin=new Thickness(0,12,0,16)};
    private readonly TextBlock _status=new() {TextWrapping=TextWrapping.Wrap};
    public bool UnreadOnly {get;private set;}
    public bool PinnedMessages {get;private set;}
    public bool ConfigureExpiration { get; private set; }
    public bool SharedMedia { get; private set; }
    public long? DayStart {get;private set;}
    public DateTime? SelectedDay { get; private set; }
    public ChatHistoryDialog(DateTime? initialDate = null, bool throughDate = false,
        Func<long?, long?, Task<string?>>? validateTarget = null)
    {
        var calendar = initialDate is { } date
            ? new System.Windows.Controls.Calendar
            {
                DisplayDate = date.Date,
                SelectedDate = date.Date,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 12, 0, 16)
            }
            : null;
        var titleKey = calendar is null ? "UiHistoryNavigation" : "UiJumpToDate";
        var jumpTitle = AppStrings.Get("UiJumpToDate");
        SetResourceReference(TitleProperty, titleKey);
        Width = calendar is null ? 390 : 340;
        MaxHeight = Math.Min(530, SystemParameters.WorkArea.Height * .84);
        SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.NoResize;
        var panel = new StackPanel();
        FrameworkElement dateControl = calendar is not null ? calendar : _date;
        System.Windows.Automation.AutomationProperties.SetName(dateControl, jumpTitle);
        panel.Children.Add(dateControl);
        _status.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
        panel.Children.Add(_status);
        var progress = new ProgressBar { Height = 3, IsIndeterminate = true, Visibility = Visibility.Collapsed,
            Margin = new Thickness(0, 6, 0, 6) };
        System.Windows.Automation.AutomationProperties.SetName(progress, AppStrings.Get("UiLoading"));
        panel.Children.Add(progress);
        var jump = IdentitySetupDialog.ActionButton(AppStrings.Get("UiJump"), null, Brushes.Lime);
        jump.IsDefault = true;
        var checking = false;
        var closed = false;
        Closed += (_, _) => closed = true;
        Button? unread = null;
        async Task<bool> CanOpenAsync(long? start, long? end)
        {
            if (checking || closed) return false;
            _status.Text = "";
            if (validateTarget is null) return true;
            checking = true;
            jump.IsEnabled = dateControl.IsEnabled = false;
            if (unread is not null) unread.IsEnabled = false;
            progress.Visibility = Visibility.Visible;
            try
            {
                var error = await validateTarget(start, end);
                if (closed) return false;
                if (error is null) return true;
                _status.Text = error;
                return false;
            }
            finally
            {
                checking = false;
                if (!closed)
                {
                    jump.IsEnabled = dateControl.IsEnabled = true;
                    if (unread is not null) unread.IsEnabled = true;
                    progress.Visibility = Visibility.Collapsed;
                }
            }
        }
        jump.Click += async (_, _) =>
        {
            var selection = calendar is null ? _date.SelectedDate : calendar.SelectedDate;
            if (selection is not { } selected) { _status.Text = AppStrings.Get("UiChooseDate"); return; }
            var selectedDay = selected.Date;
            if ((throughDate || validateTarget is not null) && selectedDay == DateTime.MaxValue.Date)
            {
                _status.Text = AppStrings.Get("UiChooseDate");
                return;
            }
            try
            {
                var zone = TimeZoneInfo.Local;
                var start = LocalDayStart(throughDate ? selectedDay.AddDays(1) : selectedDay, zone);
                if (validateTarget is not null && !await CanOpenAsync(start, LocalDayStart(selectedDay.AddDays(1), zone))) return;
                DayStart = start;
                SelectedDay = selectedDay;
                DialogResult = true;
            }
            catch (ArgumentOutOfRangeException) { _status.Text = AppStrings.Get("UiChooseDate"); }
        };
        _date.SelectedDateChanged += (_, _) => _status.Text = "";
        if (calendar is not null) calendar.SelectedDatesChanged += (_, _) => _status.Text = "";
        if (calendar is null)
        {
            unread = IdentitySetupDialog.ActionButton(AppStrings.Get("UiFirstUnreadMessage"), null, Brushes.Gray);
            unread.Click += async (_, _) =>
            {
                if (!await CanOpenAsync(null, null)) return;
                UnreadOnly = true;
                DialogResult = true;
            };
            panel.Children.Add(unread);
            panel.Children.Add(IdentitySetupDialog.ActionButton(AppStrings.Get("UiSharedMedia"), () =>
            {
                SharedMedia = true;
                DialogResult = true;
            }, Brushes.Gray));
            panel.Children.Add(IdentitySetupDialog.ActionButton(AppStrings.Get("UiPinnedMessages"), () =>
            {
                PinnedMessages = true;
                DialogResult = true;
            }, Brushes.Gray));
            panel.Children.Add(IdentitySetupDialog.ActionButton(AppStrings.Get("UiMessageRetention"), () =>
            {
                ConfigureExpiration = true;
                DialogResult = true;
            }, Brushes.Gray));
        }
        var cancel = IdentitySetupDialog.ActionButton(AppStrings.Get("UiCancel"), Close, Brushes.Gray);
        cancel.IsCancel = true;
        var actions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right, Children = { cancel, jump } };
        Content = SettingsDialog.SettingsPage(titleKey, panel, actions);
    }

    private static long LocalDayStart(DateTime date, TimeZoneInfo zone)
    {
        var day = DateTime.SpecifyKind(date, DateTimeKind.Unspecified);
        var limit = day >= DateTime.MaxValue.Date.AddDays(-2) ? DateTime.MaxValue.Date : day.AddDays(2);
        while (day < limit && zone.IsInvalidTime(day)) day = day.AddMinutes(1);
        if (zone.IsInvalidTime(day)) throw new ArgumentOutOfRangeException(nameof(date));
        var offset = zone.IsAmbiguousTime(day) ? zone.GetAmbiguousTimeOffsets(day).Max() : zone.GetUtcOffset(day);
        return new DateTimeOffset(day, offset).ToUnixTimeMilliseconds();
    }
}
