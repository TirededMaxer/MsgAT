// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Views;

public sealed class ForwardTasksDialog : DialogWindow
{
    private readonly AppServices _services;
    private readonly ListBox _list=new() {HorizontalContentAlignment=HorizontalAlignment.Stretch};
    private readonly TextBlock _status=new() {TextWrapping=TextWrapping.Wrap,Margin=new Thickness(0,8,0,8)};
    private readonly Button _cancel;
    private readonly Button _retry;
    private readonly Button _reloadButton;
    private readonly Button _openOriginal;
    private readonly Button _download;
    private readonly DispatcherTimer _refresh=new() {Interval=TimeSpan.FromMilliseconds(150)};
    private readonly DispatcherTimer _progressTimer=new() {Interval=TimeSpan.FromMilliseconds(200)};
    private (ForwardTaskInfo Row,TextBlock Label,ProgressBar Bar)? _progress;
    private string? _actionError;
    private bool _downloadQueued;
    private readonly Func<string, string, bool> _navigateToOriginal;
    private bool _closed;
    private bool _loading;
    private bool _busy;
    private bool _reload;
    private bool _loadFailed;
    private ForwardTaskInfo? Selected=>(_list.SelectedItem as ListBoxItem)?.Tag as ForwardTaskInfo;

    public ForwardTasksDialog(AppServices services, Func<string, string, bool> navigateToOriginal)
    {
        _navigateToOriginal = navigateToOriginal;
        _services=services;Title=AppStrings.Get("UiForwardingQueue");Width=620;Height=500;MinWidth=420;MinHeight=340;ResizeMode=ResizeMode.CanResize;
        SetResourceReference(BackgroundProperty, "MessagingChromeBrush");
        _list.SetResourceReference(Control.BackgroundProperty, "MessagingTimelineBrush");
        _list.SetResourceReference(Control.BorderBrushProperty, "MessagingDividerBrush");
        _list.ItemContainerStyle = ResourceStyle("ConversationItemStyle");
        var panel=new DockPanel {Margin=new Thickness(20)};
        var header=new StackPanel();header.Children.Add(IdentitySetupDialog.Heading(Title));header.Children.Add(_status);
        DockPanel.SetDock(header,Dock.Top);panel.Children.Add(header);
        var footer=new WrapPanel {HorizontalAlignment=HorizontalAlignment.Right};
        _reloadButton = IdentitySetupDialog.ActionButton(AppStrings.Get("UiQueueRefresh"), () =>
        {
            _actionError = null;
            _downloadQueued = false;
            _ = LoadAsync();
        }, Brushes.Gray);
        _openOriginal = IdentitySetupDialog.ActionButton(AppStrings.Get("UiForwardTaskOpenSource"), OpenOriginal, Brushes.Gray);
        _download = IdentitySetupDialog.ActionButton(AppStrings.Get("UiForwardTaskDownload"), () => _ = DownloadAsync(), Brushes.Gray);
        _retry=IdentitySetupDialog.ActionButton(AppStrings.Get("UiRetry"),()=>_ = ChangeAsync(false),Brushes.Gray);
        _cancel=IdentitySetupDialog.ActionButton(AppStrings.Get("UiForwardTaskCancel"),()=>_ = ChangeAsync(true),Brushes.Gray);
        footer.Children.Add(_reloadButton);
        footer.Children.Add(_download);
        footer.Children.Add(_openOriginal);
        footer.Children.Add(_retry);footer.Children.Add(_cancel);footer.Children.Add(IdentitySetupDialog.ActionButton(AppStrings.Get("UiClose"),Close,Brushes.Gray));
        var footerFrame = new Border { Child = footer, BorderThickness = new Thickness(0, 1, 0, 0),
            Margin = new Thickness(0, 8, 0, 0), Padding = new Thickness(0, 8, 0, 0) };
        footerFrame.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        DockPanel.SetDock(footerFrame, Dock.Bottom);
        panel.Children.Add(footerFrame);
        panel.Children.Add(_list);
        Content = panel;
        _list.SelectionChanged+=(_,_)=>UpdateActions();
        _refresh.Tick+=async (_,_)=>{_refresh.Stop();await LoadAsync();};
        _progressTimer.Tick+=(_,_)=>UpdateProgress();
        _services.Database.Changed+=Changed;
        _services.Messaging.Forwarding.AvailabilityChanged += Changed;
        Closed += (_, _) =>
        {
            _closed = true;
            _refresh.Stop();
            _progressTimer.Stop();
            _services.Database.Changed -= Changed;
            _services.Messaging.Forwarding.AvailabilityChanged -= Changed;
        };
        Loaded+=async (_,_)=>await LoadAsync();UpdateActions();
    }
    private void Changed(object? sender,EventArgs args)
    {
        if(!_closed && !Dispatcher.HasShutdownStarted)Dispatcher.BeginInvoke(()=>{if(!_closed && !_refresh.IsEnabled)_refresh.Start();});
    }
    private void UpdateActions()
    {
        _reloadButton.Content = AppStrings.Get(_loadFailed ? "UiRetry" : "UiQueueRefresh");
        var row = Selected;
        _reloadButton.IsEnabled = !_busy && !_loading;
        _openOriginal.Visibility = row is { SourcePeer: not null, WaitingMessageId: not null } ? Visibility.Visible : Visibility.Collapsed;
        _openOriginal.IsEnabled = !_busy && !_loading;
        _download.Visibility = _openOriginal.Visibility;
        _download.IsEnabled = _openOriginal.IsEnabled;
        _retry.IsEnabled = !_busy && !_loading && row is { State: "failed", ErrorCode: not "source_changed" };
        _cancel.IsEnabled = !_busy && !_loading && Selected is not null;
        _cancel.Content = AppStrings.Get(Selected?.State == "failed" ? "UiForwardTaskRemove" : "UiForwardTaskCancel");
    }
    private async Task DownloadAsync()
    {
        if (_closed || _busy || _loading || Selected is not { SourcePeer: not null, WaitingMessageId: not null } row) return;
        _busy = true;
        _actionError = null;
        _downloadQueued = false;
        UpdateActions();
        try
        {
            _downloadQueued = await _services.Messaging.Forwarding.RequestMissingDownloadsAsync(row.Id);
            if (!_downloadQueued) _actionError = AppStrings.Get("UiForwardTaskDownloadUnavailable");
        }
        catch (Exception error) when (error is InvalidOperationException or ArgumentException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            _actionError = AppStrings.Get("UiForwardTaskChangeFailed");
        }
        finally
        {
            if (!_closed)
            {
                _busy = false;
                UpdateActions();
                _refresh.Start();
            }
        }
    }
    private void OpenOriginal()
    {
        if (_closed || _busy || _loading || Selected is not { SourcePeer: { } peer, WaitingMessageId: { } id }) return;
        try
        {
            if (ConfirmOpenChat?.Invoke(peer) == false || _closed) return;
            if (_navigateToOriginal(peer, id)) DialogResult = true;
            else _status.Text = _actionError = AppStrings.Get("UiSearchTargetUnavailable");
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            _status.Text = _actionError = AppStrings.Get("UiHistoryLoadFailed");
        }
    }
    internal Func<string, bool>? ConfirmOpenChat { get; set; }
    private void UpdateProgress()
    {
        if(_progress is not { } active)return;
        var value=_services.Messaging.Forwarding.Progress(active.Row.Id);
        if(value is {Phase:"committing"})
        {
            active.Label.Text=AppStrings.Get("UiForwardTaskQueuing");
            active.Bar.IsIndeterminate=true;
        }
        else if(value is {Total:>0})
        {
            var fraction = (double)value.Completed / value.Total;
            var copiesPerRecipient = value.AttachmentCount / Math.Max(1, active.Row.Recipients.Count);
            var recipientIndex = (value.Attachment - 1) / Math.Max(1, copiesPerRecipient);
            var recipient = active.Row.Recipients.ElementAtOrDefault(recipientIndex) ?? AppStrings.Get("UiForwardTaskUnknownRecipient");
            active.Label.Text = AppStrings.Format("UiForwardTaskCopy", recipient, value.Attachment, value.AttachmentCount, (int)(fraction * 100));
            active.Bar.Value = Math.Clamp((value.Attachment - 1 + fraction) * 100 / value.AttachmentCount, 0, 100);
            active.Bar.IsIndeterminate=false;
        }
        else
        {
            active.Label.Text=AppStrings.Get("UiForwardTaskPreparing");
            active.Bar.IsIndeterminate=true;
        }
    }
    private static string State(ForwardTaskInfo row) => row.State == "waiting" && row.PendingMediaMessages > 0
        ? AppStrings.Format("UiForwardTaskWaitingMedia", row.PendingMediaMessages)
        : AppStrings.Get(row.State switch
    {
        "failed" => row.ErrorCode switch
        {
            "source_changed" => "UiForwardTaskSourceChanged",
            "recipient_unavailable" => "UiForwardTaskRecipientUnavailable",
            "source_download_unavailable" => "UiForwardTaskSourceDownloadUnavailable",
            "expired" => "UiForwardTaskExpired",
            "insufficient_space" => "UiForwardTaskNoSpace",
            _ => "UiForwardTaskPrepareFailed"
        },
        _ => "UiForwardTaskWaiting"
    });
    private async Task LoadAsync()
    {
        if(_loading || _busy){_reload=true;return;}
        _loading=true;UpdateActions();var selected=Selected?.Id;
        try
        {
            var rows=await _services.Messaging.Forwarding.ListAsync();if(_closed)return;
            _progressTimer.Stop();
            _progress=null;
            var retained = rows.Select(row => row.Id).ToHashSet(StringComparer.Ordinal);
            var restoreFocus = _list.IsKeyboardFocusWithin;
            for (var index = _list.Items.Count - 1; index >= 0; index--)
                if (_list.Items[index] is ListBoxItem { Tag: ForwardTaskInfo old } && !retained.Contains(old.Id))
                    _list.Items.RemoveAt(index);
            var visible = _list.Items.Cast<ListBoxItem>().ToDictionary(item => ((ForwardTaskInfo)item.Tag).Id, StringComparer.Ordinal);
            for (var index = 0; index < rows.Count; index++)
            {
                var row = rows[index];
                var content=new StackPanel {Margin=new Thickness(6)};
                content.Children.Add(new TextBlock {Text=AppStrings.Format("UiForwardTaskSummary",row.MessageCount,row.Recipients.Count),FontWeight=FontWeights.SemiBold});
                if(row.Recipients.Count>0)
                    content.Children.Add(new TextBlock {Text=string.Join(" · ",row.Recipients.Select(name=>name??AppStrings.Get("UiForwardTaskUnknownRecipient"))),TextWrapping=TextWrapping.Wrap,FontSize=12});
                var label=new TextBlock {Text=State(row),TextWrapping=TextWrapping.Wrap};
                content.Children.Add(label);
                if(row.State=="preparing")
                {
                    var bar=new ProgressBar {Height=4,Margin=new Thickness(0,4,0,4),IsIndeterminate=true};
                    content.Children.Add(bar);
                    _progress=(row,label,bar);
                }
                var options = SendOptionsText.Format(row.Options);
                if (options.Length != 0) content.Children.Add(new TextBlock { Text = options, TextWrapping = TextWrapping.Wrap, FontSize = 12 });
                content.Children.Add(new TextBlock {Text=DateTimeOffset.FromUnixTimeMilliseconds(row.CreatedAt).ToLocalTime().ToString("g",AppStrings.InterfaceCulture),FontSize=11,Opacity=.65});
                var item = visible.GetValueOrDefault(row.Id) ?? new ListBoxItem();
                item.Tag = row;
                item.Content = content;
                var oldIndex = _list.Items.IndexOf(item);
                if (oldIndex != index)
                {
                    if (oldIndex >= 0) _list.Items.RemoveAt(oldIndex);
                    _list.Items.Insert(index, item);
                }
                if (row.Id == selected)
                {
                    _list.SelectedItem = item;
                    if (restoreFocus && !_list.IsKeyboardFocusWithin) item.Focus();
                }
            }
            _loadFailed = false;
            if(_progress is not null){UpdateProgress();_progressTimer.Start();}
            _status.Text = _actionError ?? (_downloadQueued ? AppStrings.Get("UiDownloadQueued") :
                rows.Count == 0 ? AppStrings.Get("UiForwardTaskEmpty") : "");
        }
        catch(Exception error) when(error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_closed)
            {
                _loadFailed = true;
                _status.Text = AppStrings.Get("UiForwardTaskLoadFailed");
            }
        }
        finally {if(!_closed){_loading=false;UpdateActions();if(_reload){_reload=false;_refresh.Start();}}}
    }
    private async Task ChangeAsync(bool cancel)
    {
        if(_busy || _loading || Selected is not { } row)return;
        if(cancel && new ConfirmationDialog(AppStrings.Get(row.State=="failed"?"UiForwardTaskRemoveConfirm":"UiForwardTaskCancelConfirm")){Owner=this}.ShowDialog()!=true)return;
        if (_closed) return;
        _actionError=null;_downloadQueued=false;_busy=true;UpdateActions();
        try
        {
            var changed=cancel?await _services.Messaging.Forwarding.CancelAsync(row.Id):await _services.Messaging.Forwarding.RetryAsync(row.Id);
            if(!_closed && !changed)_status.Text=_actionError=AppStrings.Get("UiForwardTaskChanged");
        }
        catch(Exception error) when(error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {if(!_closed)_status.Text=_actionError=AppStrings.Get("UiForwardTaskChangeFailed");}
        finally{if(!_closed){_busy=false;UpdateActions();_refresh.Start();}}
    }
}
