// SPDX-License-Identifier: AGPL-3.0-only
using System.Globalization;
using System.Diagnostics.CodeAnalysis;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Infrastructure;
using CheckBox=System.Windows.Controls.CheckBox;

namespace MsgAT.Windows.Views;

public sealed class SendOptionsDialog : DialogWindow
{
    private readonly CheckBox _silent=new(){Content=AppStrings.Get("UiSendWithoutSound"),Margin=new Thickness(0,8,0,8)};
    private readonly CheckBox _scheduled=new(){Content=AppStrings.Get("UiScheduleNextMessage"),Margin=new Thickness(0,8,0,8)};
    private readonly DatePicker _date=new(){Margin=new Thickness(0,4,0,4)};
    private readonly System.Windows.Controls.TextBox _time=IdentitySetupDialog.Field();
    private readonly TextBlock _error=new(){TextWrapping=TextWrapping.Wrap,Margin=new Thickness(0,8,0,8)};
    private readonly TimeZoneInfo _zone=TimeZoneInfo.Local;
    public SendOptions Result {get;private set;}
    public SendOptionsDialog(SendOptions initial,bool timeOnly=false,bool startScheduled=false)
    {
        Result=initial;Title=AppStrings.Get(timeOnly ? "UiChangeScheduledTime" : "UiSendOptions");Width=440;SizeToContent=SizeToContent.Height;
        var local=initial.SendAt is { } due?TimeZoneInfo.ConvertTime(DateTimeOffset.FromUnixTimeMilliseconds(due),_zone).DateTime:DateTime.Now.AddMinutes(5);
        _date.DisplayDateEnd=new DateTime(9999,12,31);_date.SelectedDate=local.Date;_time.Text=local.ToString("HH:mm",CultureInfo.InvariantCulture);
        AutomationProperties.SetName(_date, AppStrings.Get("UiDateLabel"));
        AutomationProperties.SetName(_time, AppStrings.Get("UiTimeLabel"));
        _date.SelectedDateChanged += (_, _) => _error.Text = "";
        _time.TextChanged += (_, _) => _error.Text = "";
        _silent.IsChecked=initial.Silent;_scheduled.IsChecked=timeOnly || startScheduled || initial.SendAt is not null;
        var timeFields=new StackPanel();timeFields.Children.Add(_date);timeFields.Children.Add(_time);
        timeFields.Children.Add(new TextBlock{Text=_zone.DisplayName,TextWrapping=TextWrapping.Wrap,FontSize=11,Margin=new Thickness(0,4,0,4)});
        void Update()=>timeFields.IsEnabled=_scheduled.IsChecked==true;
        _scheduled.Checked+=(_,_)=>Update();_scheduled.Unchecked+=(_,_)=>Update();Update();
        var form=new StackPanel{Margin=new Thickness(20)};form.Children.Add(IdentitySetupDialog.Heading(Title));
        if(!timeOnly){form.Children.Add(_silent);if (!startScheduled) form.Children.Add(_scheduled);form.Children.Add(new TextBlock{Text=AppStrings.Get("UiScheduleConnected"),TextWrapping=TextWrapping.Wrap});}
        form.Children.Add(timeFields);form.Children.Add(_error);
        var buttons=new WrapPanel{HorizontalAlignment=HorizontalAlignment.Right};
        buttons.Children.Add(IdentitySetupDialog.ActionButton(AppStrings.Get("UiCancel"),()=>DialogResult=false,Brushes.Gray));
        buttons.Children.Add(IdentitySetupDialog.ActionButton(AppStrings.Get(startScheduled ? "UiScheduleNextMessage" : "UiApply"),Apply,Brushes.Gray));form.Children.Add(buttons);
        Content=new ScrollViewer{Content=form,VerticalScrollBarVisibility=ScrollBarVisibility.Auto};MaxHeight=SystemParameters.WorkArea.Height*.9;
    }
    private void Apply()
    {
        long? due=null;
        if(_scheduled.IsChecked==true)
        {
            if(_date.SelectedDate is not { } date || !TimeOnly.TryParseExact(_time.Text.Trim(),"HH:mm",CultureInfo.InvariantCulture,DateTimeStyles.None,out var time))
            {_error.Text=AppStrings.Get(_date.SelectedDate is null ? "UiChooseDate" : "UiEnterTime");return;}
            var local=DateTime.SpecifyKind(date.Date.Add(time.ToTimeSpan()),DateTimeKind.Unspecified);
            if(_zone.IsInvalidTime(local)){_error.Text=AppStrings.Get("UiScheduleMissingTime");return;}
            try
            {
                var utc=_zone.IsAmbiguousTime(local)?new DateTimeOffset(local,_zone.GetAmbiguousTimeOffsets(local).Max()).ToUniversalTime():new DateTimeOffset(TimeZoneInfo.ConvertTimeToUtc(local,_zone));
                due=utc.ToUnixTimeMilliseconds();
            }
            catch(ArgumentException){_error.Text=AppStrings.Get("UiChooseFuture");return;}
            if(due<=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() || due>253402214399999L){_error.Text=AppStrings.Get("UiChooseFuture");return;}
        }
        Result=new SendOptions(due,_silent.IsChecked==true);DialogResult=true;
    }
}

[SuppressMessage("Design","CA1001:Types that own disposable fields should be disposable",Justification="Window closure cancels work; the last active operation disposes its token source.")]
public sealed class SendQueueDialog : DialogWindow
{
    internal Func<string, bool>? ConfirmOpenChat { get; set; }
    private readonly AppServices _services;
    private readonly string? _peer;
    private readonly ListBox _list=new(){HorizontalContentAlignment=HorizontalAlignment.Stretch};
    private readonly TextBlock _status=new(){TextWrapping=TextWrapping.Wrap,Margin=new Thickness(0,8,0,8)};
    private readonly ProgressBar _queueProgress = new()
    {
        Height = 3, IsIndeterminate = true, Visibility = Visibility.Hidden, Margin = new Thickness(0, 6, 0, 4)
    };
    private readonly Button _now,_reschedule,_retry,_cancel,_previous,_next,_refreshButton,_edit;
    private readonly Button _openChat;
    private readonly System.Windows.Controls.ComboBox _filter = new() { MinWidth = 160, HorizontalAlignment = HorizontalAlignment.Left };
    private readonly DispatcherTimer _refresh=new(){Interval=TimeSpan.FromMilliseconds(150)};
    private readonly DispatcherTimer _dueClock = new(DispatcherPriority.Background);
    private readonly CancellationTokenSource _lifetime=new();
    private bool _closed,_loading,_busy,_reload;
    private int _offset,_displayedOffset,_displayedFilter;
    private (int Offset, int Filter)? _failedPage;
    private bool _hasNextPage;
    private string? _actionError;
    private SendQueueEntry? Selected=>(_list.SelectedItem as ListBoxItem)?.Tag as SendQueueEntry;
    public string? OpenChatPeer { get; private set; }
    public string? OpenMessageId { get; private set; }

    private sealed class QueueRow : ListBoxItem
    {
        public TextBlock? StatusLabel { get; set; }
    }

    public SendQueueDialog(AppServices services,string? peer=null)
    {
        _services=services;_peer=peer;Title=AppStrings.Get("UiSendingQueue");Width=660;Height=520;MinWidth=440;MinHeight=340;ResizeMode=ResizeMode.CanResize;
        SetResourceReference(BackgroundProperty, "MessagingChromeBrush");
        _list.SetResourceReference(Control.BackgroundProperty, "MessagingTimelineBrush");
        _list.SetResourceReference(Control.BorderBrushProperty, "MessagingDividerBrush");
        _list.ItemContainerStyle = ResourceStyle("ConversationItemStyle");
        AutomationProperties.SetName(_queueProgress, AppStrings.Get("UiLoading"));
        var panel=new DockPanel{Margin=new Thickness(20)};
        var header=new StackPanel();header.Children.Add(IdentitySetupDialog.Heading(Title));header.Children.Add(_status);DockPanel.SetDock(header,Dock.Top);panel.Children.Add(header);
        foreach (var key in new[] { "UiQueueFilterAll", "UiQueueFilterScheduled", "UiQueueFilterPending" })
            _filter.Items.Add(AppStrings.Get(key));
        _filter.SelectedIndex = 0;
        AutomationProperties.SetName(_filter, Title);
        header.Children.Add(_filter);
        header.Children.Add(_queueProgress);
        var footer = new StackPanel();
        var footerFrame = new Border { Child = footer, BorderThickness = new Thickness(0, 1, 0, 0),
            Padding = new Thickness(0, 8, 0, 0), Margin = new Thickness(0, 8, 0, 0) };
        footerFrame.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        DockPanel.SetDock(footerFrame, Dock.Bottom);
        panel.Children.Add(footerFrame);
        var actions=new WrapPanel();footer.Children.Add(actions);
        _now=MakeButton(AppStrings.Get("UiQueueNow"),()=>_ = ChangeAsync("send_now"));_reschedule=MakeButton(AppStrings.Get("UiChangeScheduledTime"),()=>_ = ChangeAsync("reschedule"));
        _edit = MakeButton(AppStrings.Get("UiQueueEdit"), () => _ = EditAsync());
        _retry=MakeButton(AppStrings.Get("UiRetry"),()=>_ = ChangeAsync("retry"));_cancel=MakeButton(AppStrings.Get("UiQueueCancel"),()=>_ = ChangeAsync("cancel"));
        _openChat = MakeButton(AppStrings.Get("UiShowInChat"), () =>
        {
            if (_closed || _busy || _loading || Selected is not { } row ||
                _peer is not null && row.SendAt is not null) return;
            try
            {
                if (ConfirmOpenChat?.Invoke(row.Peer) == false || _closed) return;
                if (_services.Database.GetConversation(row.Peer) is null)
                {
                    ReportStaleDestination();
                    return;
                }
                if (row.SendAt is null)
                {
                    if (!_services.Database.HasHistoryAnchor(row.Peer, row.Id, includeRevoked: false))
                    {
                        ReportStaleDestination();
                        return;
                    }
                    OpenMessageId = row.Id;
                }
                OpenChatPeer = row.Peer;
                DialogResult = true;
            }
            catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
            {
                if (!_closed) _status.Text = _actionError = AppStrings.Get("UiHistoryLoadFailed");
            }
        });
        actions.Children.Add(_openChat);
        foreach(var button in new[]{_now,_reschedule,_edit,_retry,_cancel})actions.Children.Add(button);
        var pages=new WrapPanel{HorizontalAlignment=HorizontalAlignment.Right};footer.Children.Add(pages);
        _refreshButton = MakeButton(AppStrings.Get("UiQueueRefresh"), () =>
        {
            _actionError = null;
            _ = LoadAsync(_failedPage ?? (_displayedOffset, _displayedFilter));
        });
        _previous = MakeButton(AppStrings.Get("UiQueuePrevious"), () =>
            _ = LoadAsync((Math.Max(0, _offset - 50), _displayedFilter)));
        _next = MakeButton(AppStrings.Get("UiQueueNext"), () =>
            _ = LoadAsync((_offset + 50, _displayedFilter)));
        foreach(var button in new[]{_refreshButton,_previous,_next})pages.Children.Add(button);pages.Children.Add(MakeButton(AppStrings.Get("UiClose"),Close));
        panel.Children.Add(_list);Content=panel;_list.SelectionChanged+=(_,_)=>UpdateActions();
        _filter.SelectionChanged += (_, _) =>
        {
            if (_closed || _loading || _busy || _filter.SelectedIndex < 0) return;
            _actionError = null;
            _ = LoadAsync((0, _filter.SelectedIndex));
        };
        _refresh.Tick+=async(_,_)=>{_refresh.Stop();await LoadAsync();};_services.Messaging.SendQueueChanged+=Changed;_services.Database.Changed+=Changed;
        _dueClock.Tick += (_, _) => UpdateQueueStatus();
        _services.Relay.StateChanged += RelayChanged;
        Closed += (_, _) =>
        {
            _closed = true;
            _refresh.Stop();
            _dueClock.Stop();
            _services.Relay.StateChanged -= RelayChanged;
            _services.Messaging.SendQueueChanged -= Changed;
            _services.Database.Changed -= Changed;
            _lifetime.Cancel();
            if (!_loading && !_busy) _lifetime.Dispose();
        };
        Loaded+=async(_,_)=>await LoadAsync();UpdateActions();
    }
    private static Button MakeButton(string label,Action action)=>IdentitySetupDialog.ActionButton(label,action,Brushes.Gray);
    private void ReportStaleDestination()
    {
        _status.Text = _actionError = AppStrings.Get("UiQueueStale");
        _ = LoadAsync((_displayedOffset, _displayedFilter));
    }
    private void Changed(object? sender,EventArgs args)
    {
        if(!_closed && !Dispatcher.HasShutdownStarted)Dispatcher.BeginInvoke(()=>{if(!_closed && !_refresh.IsEnabled)_refresh.Start();});
    }
    private void RelayChanged(object? sender, SyncState state)
    {
        if (!_closed && !Dispatcher.HasShutdownStarted)
            Dispatcher.BeginInvoke(new Action(UpdateQueueStatus));
    }
    private void UpdateQueueStatus()
    {
        _dueClock.Stop();
        if (_closed) return;

        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var connected = _services.Relay.State == SyncState.Synced;
        long? nextDue = null;
        foreach (var item in _list.Items.OfType<QueueRow>())
        {
            if (item.Tag is not SendQueueEntry row || item.StatusLabel is not { } label) continue;
            label.Text = Summary(row, now, connected);
            if (row.State != "failed" && (row.SendAt ?? row.RelayRetryAt) is { } due && due > now &&
                (nextDue is null || due < nextDue.Value))
                nextDue = due;
        }
        if (nextDue is not { } pending) return;
        _dueClock.Interval = TimeSpan.FromMilliseconds(Math.Clamp(pending - now, 1L, 60_000L));
        _dueClock.Start();
    }
    private void UpdateActions()
    {
        var available=!_busy && !_loading;var row=Selected;
        _queueProgress.Visibility = _busy || _loading ? Visibility.Visible : Visibility.Hidden;
        _openChat.IsEnabled = available && row is not null && (_peer is null || row.SendAt is null);
        _openChat.Content = AppStrings.Get(row?.SendAt is null ? "UiShowInChat" : "UiContactOpenChat");
        _now.IsEnabled=_reschedule.IsEnabled=available && row is { SendAt: not null, Failure: null };
        _edit.IsEnabled = available && row?.CanEdit == true;
        _cancel.IsEnabled=available && row?.CanCancel==true;_retry.IsEnabled=available && row?.CanRetry==true;
        _filter.IsEnabled = available;
        _refreshButton.Content = AppStrings.Get(_failedPage is null ? "UiQueueRefresh" : "UiRetry");
        _refreshButton.IsEnabled=available;_previous.IsEnabled=available && _offset>0;_next.IsEnabled=available && _hasNextPage;
    }
    private static string Summary(SendQueueEntry row, long now, bool connected)
    {
        var state = row.State switch
        {
            "failed" => AppStrings.Get(row.Failure switch
            {
                "recipient" => "UiQueueRecipientUnavailable",
                "media" => "UiQueueMediaUnavailable",
                _ => "UiQueueAttention"
            }),
            _ when row.RelayRetryAt is { } retry && retry > now => AppStrings.Get("UiQueueRelayFull"),
            "sending" => AppStrings.Get("UiQueueUnconfirmed"),
            _ when row.SendAt is null => AppStrings.Get("UiQueueWaiting"),
            _ when row.SendAt <= now => AppStrings.Get(connected ? "UiQueueWaiting" : "UiQueueWaitingConnection"),
            _ => ""
        };
        var options = SendOptionsText.Format(new SendOptions(row.SendAt, row.Silent));
        return string.Join(" · ", new[] { state, options }.Where(part => part.Length > 0));
    }
    private async Task LoadAsync((int Offset, int Filter)? destination = null)
    {
        if (_closed || destination is null && _failedPage is not null) return;
        if (_loading || _busy) { _reload = true; return; }
        var requested = destination ?? (Offset: _displayedOffset, Filter: _displayedFilter);
        _failedPage = null;
        _loading = true;
        UpdateActions();
        try
        {
            var filter = requested.Filter;
            var page = requested.Offset;
            _filter.SelectedIndex = filter;
            bool? scheduled = filter switch { 1 => true, 2 => false, _ => null };
            var rows = await _services.Messaging.SendQueueAsync(_peer, page, _lifetime.Token, scheduled: scheduled, limit: 51);
            while (!_closed && rows.Count == 0 && page > 0)
            {
                page = Math.Max(0, page - 50);
                rows = await _services.Messaging.SendQueueAsync(_peer, page, _lifetime.Token, scheduled: scheduled, limit: 51);
            }
            if (_closed) return;
            _offset = page;
            ApplyRows(rows.Take(50).ToArray(), samePage: _offset == _displayedOffset && filter == _displayedFilter);
            _hasNextPage = rows.Count > 50;
            _displayedOffset = _offset;
            _displayedFilter = filter;
            _status.Text=_actionError ?? (rows.Count==0?AppStrings.Get("UiQueueEmpty"):AppStrings.Get("UiQueueOnline"));
        }
        catch(OperationCanceledException) when(_lifetime.IsCancellationRequested){}
        catch(Exception error) when(error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            _offset = _displayedOffset;
            if (!_closed)
            {
                _failedPage = requested;
                _reload = false;
                _filter.SelectedIndex = _displayedFilter;
                _status.Text = AppStrings.Get("UiQueueLoading");
            }
        }
        finally{_loading=false;if(_closed){if(!_busy)_lifetime.Dispose();}else{UpdateActions();if(_reload){_reload=false;_refresh.Start();}}}
    }
    private void ApplyRows(IReadOnlyList<SendQueueEntry> rows, bool samePage)
    {
        var selected = samePage ? _list.SelectedItem as ListBoxItem : null;
        var restoreFocus = samePage && _list.IsKeyboardFocusWithin;
        if (!samePage) _list.Items.Clear();
        var retained = rows.Select(row => row.Id).ToHashSet(StringComparer.Ordinal);
        for (var i = _list.Items.Count - 1; i >= 0; i--)
        {
            if (_list.Items[i] is ListBoxItem { Tag: SendQueueEntry old } && !retained.Contains(old.Id))
                _list.Items.RemoveAt(i);
        }
        var existing = _list.Items.Cast<QueueRow>().ToDictionary(item => ((SendQueueEntry)item.Tag).Id, StringComparer.Ordinal);
        for (var i = 0; i < rows.Count; i++)
        {
            var row = rows[i];
            if (!existing.TryGetValue(row.Id, out var item))
            {
                item = new QueueRow();
                _list.Items.Insert(i, item);
            }
            else if (!ReferenceEquals(_list.Items[i], item))
            {
                _list.Items.Remove(item);
                _list.Items.Insert(i, item);
            }
            if (item.Tag is SendQueueEntry previous && previous == row) continue;
            var content = new StackPanel { Margin = new Thickness(6) };
            content.Children.Add(new MessageTextBlock { MessageText = row.Summary, IsSummary = true, TextWrapping = TextWrapping.Wrap });
            if (_peer is null)
            {
                var name = string.IsNullOrWhiteSpace(row.PeerAlias) ? row.Peer[..Math.Min(row.Peer.Length, 12)] : row.PeerAlias;
                content.Children.Add(new TextBlock { Text = name, FontSize = 11, Opacity = .65, TextTrimming = TextTrimming.CharacterEllipsis });
            }
            var stateLabel = new TextBlock { TextWrapping = TextWrapping.Wrap, FontSize = 11 };
            if (row.State == "failed") stateLabel.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
            else stateLabel.Opacity = .75;
            content.Children.Add(stateLabel);
            item.StatusLabel = stateLabel;
            item.Tag = row;
            item.Content = content;
        }
        if (selected is not null && _list.Items.Contains(selected))
        {
            _list.SelectedItem = selected;
            if (restoreFocus && !_list.IsKeyboardFocusWithin) selected.Focus();
        }
        else if (!samePage && _list.Items.Count > 0) _list.ScrollIntoView(_list.Items[0]);
        UpdateQueueStatus();
    }
    private async Task EditAsync()
    {
        if (_closed || _busy || _loading || Selected is not { CanEdit: true, SendAt: { } scheduledAt } row) return;
        _busy = true;
        _actionError = null;
        UpdateActions();
        try
        {
            var draft = await _services.Messaging.ScheduledMessageDraftAsync(row.Id, row.Peer, scheduledAt, _lifetime.Token);
            if (_closed) return;
            if (draft is null)
            {
                _status.Text = _actionError = AppStrings.Get("UiQueueStale");
                return;
            }
            var editor = new ScheduledMessageEditor(draft, replacement =>
                _services.Messaging.EditScheduledMessageAsync(row.Id, row.Peer, scheduledAt, draft, replacement, _lifetime.Token))
            {
                Owner = this
            };
            editor.ShowDialog();
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_closed) _status.Text = _actionError = AppStrings.Get("UiQueueChange");
        }
        finally
        {
            _busy = false;
            if (_closed) { if (!_loading) _lifetime.Dispose(); }
            else { UpdateActions(); _refresh.Start(); }
        }
    }

    private async Task ChangeAsync(string action)
    {
        if (_closed || _busy || _loading || Selected is not { } row) return;
        _busy = true;
        UpdateActions();
        try
        {
            long? at = null;
            if (action == "cancel")
            {
                var confirmation = new ConfirmationDialog(AppStrings.Get("UiQueueCancelBody"),
                    AppStrings.Get("UiQueueCancelTitle"), dismissLabel: AppStrings.Get("UiQueueKeep"),
                    confirmLabel: AppStrings.Get("UiQueueCancel")) { Owner = this };
                if (confirmation.ShowDialog() != true) return;
            }
            else if (action == "reschedule")
            {
                var dialog = new SendOptionsDialog(new SendOptions(row.SendAt, row.Silent), true) { Owner = this };
                if (dialog.ShowDialog() != true) return;
                at = dialog.Result.SendAt;
            }
            if (_closed) return;
            _actionError = null;
            if (!await _services.Messaging.ManageSendAsync(row.Id, action, at, _lifetime.Token,
                    expectedPeer: row.Peer, expectedScheduledAt: row.SendAt) && !_closed)
                _status.Text = _actionError = AppStrings.Get("UiQueueStale");
        }
        catch(OperationCanceledException) when(_lifetime.IsCancellationRequested){}
        catch(Exception error) when(error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException){if(!_closed)_status.Text=_actionError=AppStrings.Get("UiQueueChange");}
        finally
        {
            _busy = false;
            if (_closed) { if (!_loading) _lifetime.Dispose(); }
            else
            {
                _failedPage = null;
                UpdateActions();
                _refresh.Start();
            }
        }
    }
}

internal sealed class ScheduledMessageEditor : DialogWindow
{
    internal ScheduledMessageEditor(ScheduledMessageDraft draft, Func<string, Task<bool>> save)
    {
        Title = AppStrings.Get(draft.IsCaption ? "UiEditingCaption" : "UiEditingMessage");
        Width = 520;
        SizeToContent = SizeToContent.Height;
        var editor = IdentitySetupDialog.Field();
        editor.Height = 156;
        editor.AcceptsReturn = true;
        editor.TextWrapping = TextWrapping.Wrap;
        editor.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
        editor.Text = draft.Text;
        AutomationProperties.SetName(editor, Title);
        var feedback = new TextBlock { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 4, 0, 8) };
        var confirm = IdentitySetupDialog.ActionButton(AppStrings.Get("UiSave"), null, Brushes.Lime);
        var cancel = IdentitySetupDialog.ActionButton(AppStrings.Get("UiCancel"), Close, Brushes.Gray);
        cancel.IsCancel = true;
        var saving = false;
        var failed = false;
        void Update()
        {
            var tooLong = System.Text.Encoding.UTF8.GetByteCount(editor.Text) > 48 * 1024;
            confirm.IsEnabled = !saving && !tooLong && editor.Text != draft.Text &&
                (draft.IsCaption || !string.IsNullOrWhiteSpace(editor.Text));
            editor.IsReadOnly = saving;
            cancel.IsEnabled = !saving;
            feedback.Text = tooLong ? AppStrings.Get(draft.IsCaption ? "UiCaptionTooLong" : "UiQueueTextTooLong") :
                failed ? AppStrings.Get("UiQueueStale") : "";
            feedback.Visibility = feedback.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        }
        editor.TextChanged += (_, _) => { failed = false; Update(); };
        confirm.Click += async (_, _) =>
        {
            if (saving || !confirm.IsEnabled) return;
            saving = true;
            failed = false;
            Update();
            try
            {
                if (await save(editor.Text))
                {
                    if (IsVisible) DialogResult = true;
                    return;
                }
                failed = true;
            }
            catch (Exception error) when (error is OperationCanceledException or InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
            { failed = true; }
            finally
            {
                saving = false;
                if (IsVisible)
                {
                    Update();
                    editor.Focus();
                }
            }
        };
        Loaded += (_, _) => editor.Focus();
        Update();
        var buttons = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        buttons.Children.Add(cancel);
        buttons.Children.Add(confirm);
        Content = IdentitySetupDialog.DialogCard(IdentitySetupDialog.Form(IdentitySetupDialog.Heading(Title), editor, feedback, buttons));
    }
}
