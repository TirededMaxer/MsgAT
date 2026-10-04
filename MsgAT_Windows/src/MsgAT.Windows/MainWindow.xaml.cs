using System.ComponentModel;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.ViewModels;
using MsgAT.Windows.Views;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows;

[SuppressMessage("Design", "CA1001:Types that own disposable fields should be disposable", Justification = "Application awaits service cleanup before closing the WPF window.")]
public partial class MainWindow : Window
{
    private AppServices? _services;
    private MainViewModel? _viewModel;
    private CallOverlay? _callWindow;
    private bool _allowClose;
    private bool _loadingOlderMessages;
    private bool _exiting;
    private Task<AppServices>? _initialization;

    public MainWindow()
    {
        InitializeComponent();
        CommandManager.AddPreviewCanExecuteHandler(Composer, Composer_PreviewCanExecute);
        CommandManager.AddPreviewExecutedHandler(Composer, Composer_PreviewExecuted);
        LayoutGrid.SizeChanged += (_, args) =>
            SidebarColumn.MaxWidth = Math.Clamp(args.NewSize.Width - 455, 260, 400);
        WindowAppearance.Apply(this);
        RestoreWindowPlacement();
        Activated += (_, _) => _viewModel?.MarkActiveConversationRead();
    }

    public void AllowClose() => _allowClose = true;

    private async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        _ = sender;
        _ = e;
        try
        {
            _initialization = AppServices.CreateAsync(Dispatcher, WebRtcView);
            _services = await _initialization;
            if (_exiting) { return; }
            if (string.IsNullOrWhiteSpace(_services.Settings.Snapshot.Nickname) || string.IsNullOrWhiteSpace(_services.Settings.Snapshot.AvatarPath))
            {
                var setup = new IdentitySetupDialog { Owner = this };
                if (setup.ShowDialog() != true)
                {
                    ((App)System.Windows.Application.Current).ExitApplication();
                    return;
                }
                var avatar = _services.Media.SaveAvatar(_services.Settings.MyUuid, setup.AvatarData ?? AvatarProcessor.EncodeJpeg(setup.AvatarPath));
                _services.Settings.SetInitialIdentity(setup.Nickname, avatar);
                await _services.Messaging.SendProfileAsync();
            }
            if (_exiting) { return; }
            _viewModel = new MainViewModel(_services, Dispatcher);
            _viewModel.ErrorRequested += (_, message) => TransientNotice.Show(this, message);
            _viewModel.InitialMessagesLoaded += (_, _) =>
                Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Loaded, new Action(ScrollToLatest));
            _viewModel.LatestMessagesChanged += (_, _) => ScrollToLatestIfNearBottom();
            DataContext = _viewModel;
            _services.Calls.ShowRequested += Calls_ShowRequested;
            _services.Calls.NoticeRequested += (_, message) => TransientNotice.Show(this, message);
        }
        catch (Exception exception)
        {
            Trace.TraceError("Initialization failed: {0}", exception);
            MessageBox.Show(this, exception.Message, "MsgAT could not initialize", MessageBoxButton.OK, MessageBoxImage.Error);
            ((App)System.Windows.Application.Current).ExitApplication();
        }
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        _ = sender;
        SaveWindowPlacement();
        if (!_allowClose)
        {
            e.Cancel = true;
            Hide();
        }
    }

    public async Task PrepareForExitAsync()
    {
        _exiting = true;
        IsEnabled = false;
        _viewModel?.Dispose();
        if (_callWindow is not null) { _callWindow.IsEnabled = false; _callWindow.Visibility = Visibility.Collapsed; _callWindow.Dispose(); }
        if (_initialization is not null)
        {
            _services = await _initialization;
        }
        if (_services is not null)
        {
            _services.Calls.ShowRequested -= Calls_ShowRequested;
            await _services.DisposeAsync();
        }
    }

    private void Window_Closed(object? sender, EventArgs e)
    {
        _ = sender;
        _ = e;
        WebRtcView.Dispose();
    }

    private void MainMenu_Click(object sender, RoutedEventArgs e)
    {
        _ = e;
        if (sender is not Button button || _exiting) { return; }
        var menu = new ContextMenu { PlacementTarget = button };
        var add = new MenuItem { Header = "Add Friend" };
        add.Click += AddFriend_Click;
        var requests = new MenuItem { Header = "Friend Requests" };
        requests.Click += Requests_Click;
        var settings = new MenuItem { Header = "Settings" };
        settings.Click += Settings_Click;
        menu.Items.Add(add);
        menu.Items.Add(requests);
        menu.Items.Add(settings);
        menu.IsOpen = true;
    }

    private async void Send_Click(object sender, RoutedEventArgs e)
    {
        _ = sender;
        _ = e;
        if (_viewModel is null) { return; }
        if (_viewModel.HasDraft) { await _viewModel.SendDraftAsync(); ScrollToLatest(); }
        else { _viewModel.ShowAttachments = !_viewModel.ShowAttachments; }
    }

    private static string? ClipboardImageFile(IDataObject data) =>
        data.GetDataPresent(DataFormats.FileDrop) && data.GetData(DataFormats.FileDrop) is string[] files
            ? files.FirstOrDefault(path => Path.GetExtension(path).ToLowerInvariant() is ".png" or ".jpg" or ".jpeg" or ".gif" or ".webp")
            : null;

    private void Composer_PreviewCanExecute(object sender, CanExecuteRoutedEventArgs e)
    {
        if (e.Command != ApplicationCommands.Paste || _exiting || _viewModel?.SelectedConversation is null) return;
        try
        {
            var data = Clipboard.GetDataObject();
            if (data is not null && (data.GetDataPresent(DataFormats.Bitmap) || data.GetDataPresent("PNG") || ClipboardImageFile(data) is not null))
            {
                e.CanExecute = true;
                e.Handled = true;
            }
        }
        catch (System.Runtime.InteropServices.ExternalException) { }
    }

    private async void Composer_PreviewExecuted(object sender, ExecutedRoutedEventArgs e)
    {
        if (e.Command != ApplicationCommands.Paste || _exiting || _viewModel?.SelectedConversation is null) return;
        try
        {
            var data = Clipboard.GetDataObject();
            if (data is null) return;
            if (ClipboardImageFile(data) is { } file)
            {
                e.Handled = true;
                _viewModel.ShowAttachments = false;
                await _viewModel.SendMediaAsync(file);
                return;
            }
            var pngData = data.GetData("PNG");
            if (pngData is MemoryStream oversizedStream && oversizedStream.Length > 100L * 1024 * 1024 ||
                pngData is byte[] oversizedBytes && oversizedBytes.Length > 100L * 1024 * 1024)
            {
                e.Handled = true;
                TransientNotice.Show(this, "Files cannot exceed 100 MB.");
                return;
            }
            byte[]? png = pngData switch
            {
                MemoryStream stream when stream.Length <= 100L * 1024 * 1024 => stream.ToArray(),
                byte[] bytes when bytes.Length <= 100L * 1024 * 1024 => bytes,
                _ => null
            };
            var bitmap = png is null && data.GetDataPresent(DataFormats.Bitmap) ? Clipboard.GetImage() : null;
            if (png is null && bitmap is null) return;
            e.Handled = true;
            var send = _viewModel.CaptureMediaSender();
            if (send is null) return;
            _viewModel.ShowAttachments = false;
            bitmap?.Freeze();
            var path = Path.Combine(Path.GetTempPath(), $"msgat-clipboard-{Guid.NewGuid():N}.png");
            try
            {
                await Task.Run(() =>
                {
                    using var stream = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None);
                    if (png is not null) stream.Write(png);
                    else
                    {
                        var encoder = new PngBitmapEncoder();
                        encoder.Frames.Add(BitmapFrame.Create(bitmap!));
                        encoder.Save(stream);
                    }
                });
                await send(path);
            }
            finally
            {
                try { File.Delete(path); }
                catch (IOException error) { Trace.TraceWarning("Clipboard cleanup failed: {0}", error.Message); }
            }
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or NotSupportedException or ArgumentException or System.Runtime.InteropServices.ExternalException)
        {
            e.Handled = true;
            TransientNotice.Show(this, "Failed to paste image");
        }
    }

    private async void Media_Click(object sender, RoutedEventArgs e)
    {
        _ = sender;
        _ = e;
        if (_viewModel is null) { return; }
        _viewModel.ShowAttachments = false;
        await PickAndSendMediaAsync();
    }

    private async void VoiceCall_Click(object sender, RoutedEventArgs e)
    {
        _ = sender;
        _ = e;
        if (_viewModel?.SelectedConversation is null || _services is null) { return; }
        _viewModel.ShowAttachments = false;
        try { await _services.Calls.StartOutgoingAsync(_viewModel.SelectedConversation.Uuid); }
        catch (InvalidOperationException exception) { TransientNotice.Show(this, exception.Message); }
    }

    private async Task PickAndSendMediaAsync()
    {
        var picker = new OpenFileDialog { Filter = "Media|*.png;*.jpg;*.jpeg;*.gif;*.webp;*.mp4;*.mov;*.webm" };
        if (picker.ShowDialog(this) == true && _viewModel is not null) { await _viewModel.SendMediaAsync(picker.FileName); }
    }

    private async void AddFriend_Click(object sender, RoutedEventArgs e)
    {
        _ = sender;
        _ = e;
        if (_viewModel is null) { return; }
        var dialog = new TextPromptDialog("Add Friend", "Target UUID", myUuid: _viewModel.MyUuid) { Owner = this };
        if (dialog.ShowDialog() == true) { await _viewModel.AddFriendAsync(dialog.Value); }
    }

    private void Requests_Click(object sender, RoutedEventArgs e)
    {
        _ = sender;
        _ = e;
        if (_viewModel is not null) { new FriendRequestsDialog(_viewModel) { Owner = this }.ShowDialog(); }
    }

    private async void Settings_Click(object sender, RoutedEventArgs e)
    {
        _ = sender;
        _ = e;
        if (_viewModel is null || _services is null) { return; }
        var dialog = new SettingsDialog(_services.Settings.Snapshot, _services.Settings.MyUuid) { Owner = this };
        var accepted = dialog.ShowDialog() == true;
        if (_exiting) { return; }
        try
        {
            if (dialog.ResetRequested)
            {
                if (Confirm("Reset your identity?"))
                {
                    IsEnabled = false;
                    await _services.ResetIdentityAndRestartAsync();
                }
            }
            else if (accepted && dialog.Result is not null)
            {
                await _services.SaveNetworkSettingsAsync(dialog.Result);
            }
        }
        catch (Exception exception)
        {
            Trace.TraceError("Settings operation failed: {0}", exception);
            if (!_exiting) { TransientNotice.Show(this, exception.Message); }
            if (_services.IsDisposed) { ((App)System.Windows.Application.Current).ExitApplication(); }
        }
        finally
        {
            if (!_exiting) { IsEnabled = true; }
        }
    }

    private void Conversation_PreviewMouseRightButtonDown(object sender, MouseButtonEventArgs e)
    {
        _ = e;
        if (sender is not ListBoxItem container || container.DataContext is not ConversationItem conversation || _viewModel is null) { return; }
        var menu = new ContextMenu();
        var clear = new MenuItem { Header = "Clear Message" };
        clear.Click += (_, _) =>
        {
            if (Confirm("Clear all messages in this conversation?")) { _viewModel.ClearMessages(conversation.Uuid); }
        };
        var delete = new MenuItem { Header = "Delete Friend" };
        delete.Click += async (_, _) =>
        {
            if (Confirm("Delete this friend and clear history?")) { await _viewModel.DeleteFriendAsync(conversation.Uuid); }
        };
        menu.Items.Add(clear);
        menu.Items.Add(delete);
        container.ContextMenu = menu;
        menu.PlacementTarget = container;
        menu.IsOpen = true;
        e.Handled = true;
    }

    private void Message_PreviewMouseRightButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (sender is not ListBoxItem container || container.DataContext is not MessageItem item || _viewModel is null) { return; }
        var menu = new ContextMenu();
        if (!item.IsRevoked)
        {
            var reply = new MenuItem { Header = "Reply" };
            reply.Click += (_, _) => { _viewModel.SetReply(item); Composer.Focus(); };
            menu.Items.Add(reply);
        }
        if (!item.IsRevoked && item.Extension == "txt")
        {
            var copy = new MenuItem { Header = "Copy Text" };
            copy.Click += (_, _) => { Clipboard.SetText(item.Content); TransientNotice.Show(this, "Copied to clipboard"); };
            menu.Items.Add(copy);
        }
        if (item.CanModify)
        {
            var revoke = new MenuItem { Header = "Revoke" };
            revoke.Click += async (_, _) => await _viewModel.RevokeAsync(item); menu.Items.Add(revoke);
            if (item.Extension == "txt")
            {
                var edit = new MenuItem { Header = "Edit" };
                edit.Click += async (_, _) =>
                {
                    var dialog = new TextPromptDialog("Edit Message", "", item.Content) { Owner = this };
                    if (dialog.ShowDialog() == true) await _viewModel.EditAsync(item, dialog.Value);
                };
                menu.Items.Add(edit);
            }
        }
        if (!item.IsRevoked && item.Extension is "jpg" or "jpeg" or "png" or "webp" or "mp4" or "m4a")
        {
            var save = new MenuItem { Header = "Save Media" }; save.Click += (_, _) => SaveAttachment(item); menu.Items.Add(save);
        }
        if (menu.Items.Count > 0)
        {
            container.ContextMenu = menu;
            menu.PlacementTarget = container;
            menu.IsOpen = true;
            e.Handled = true;
        }
    }

    private void ReEdit_Click(object sender, RoutedEventArgs e)
    {
        if (_viewModel is null || sender is not FrameworkElement { DataContext: MessageItem { CanReEdit: true } item }) { return; }
        _viewModel.Draft = item.Content;
        Composer.Focus();
        Composer.CaretIndex = Composer.Text.Length;
        e.Handled = true;
    }

    private async void ReplyPreview_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is not FrameworkElement { DataContext: MessageItem { ReplyToId: { } id } } || _viewModel is null) return;
        e.Handled = true;
        if (_viewModel.RevealMessage(id) is not { } target) { TransientNotice.Show(this, "Quoted message not found"); return; }
        MessageList.SelectedItem = target; MessageList.ScrollIntoView(target);
        await Task.Delay(1500);
        if (ReferenceEquals(MessageList.SelectedItem, target)) MessageList.SelectedItem = null;
    }

    private void CancelReply_Click(object sender, RoutedEventArgs e)
    {
        _ = sender;
        _ = e;
        _viewModel?.CancelReply();
    }

    private void AttachmentPreview_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: MessageItem { IsAttachment: true } item })
        {
            e.Handled = true;
            OpenAttachment(item);
        }
    }

    private void OpenAttachment(MessageItem item)
    {
        try
        {
            var viewer = new MediaOverlay(item.Content, item.Extension, () => { if (MediaOverlayHost.Content is IDisposable disposable) disposable.Dispose(); MediaOverlayHost.Content = null; UpdateChatVisibility(); });
            MediaOverlayHost.Content = viewer; UpdateChatVisibility();
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or NotSupportedException or System.ComponentModel.Win32Exception)
        {
            TransientNotice.Show(this, item.Extension is "jpg" or "jpeg" or "png" or "webp" ? "Failed to load image" : "Failed to load video");
        }
    }

    private void SaveAttachment(MessageItem item)
    {
        if (new ConfirmationDialog("Do you want to save this media to your gallery?", "Save Media", false) { Owner = this }.ShowDialog() != true) return;
        try
        {
            var folder = item.Extension == "mp4" ? Environment.SpecialFolder.MyVideos : item.Extension == "m4a" ? Environment.SpecialFolder.MyMusic : Environment.SpecialFolder.MyPictures;
            var directory = Path.Combine(Environment.GetFolderPath(folder), "MsgAT"); Directory.CreateDirectory(directory);
            var destination = Path.Combine(directory, $"{item.MessageId}.{item.Extension}");
            if (File.Exists(destination)) destination = Path.Combine(directory, $"{item.MessageId}_{Guid.NewGuid():N}.{item.Extension}");
            File.Copy(item.Content, destination); TransientNotice.Show(this, "Saved to Gallery");
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { TransientNotice.Show(this, "Failed to save"); }
    }

    private void MessageList_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        _ = sender;
        if (GetScrollViewer(MessageList) is not { } scroll) { return; }
        e.Handled = true;
        if (e.Delta > 0 && scroll.VerticalOffset <= 1)
        {
            LoadOlderMessagesAtTop();
            return;
        }
        const double pixelsPerNotch = 32;
        scroll.ScrollToVerticalOffset(Math.Clamp(
            scroll.VerticalOffset - e.Delta / 120d * pixelsPerNotch, 0, scroll.ScrollableHeight));
    }

    private void MessageList_ScrollChanged(object sender, ScrollChangedEventArgs e)
    {
        _ = sender;
        UpdateViewingLatest();
        if (e.VerticalChange < 0 && e.VerticalOffset <= 1 &&
            ReferenceEquals(e.OriginalSource, GetScrollViewer(MessageList)))
        {
            LoadOlderMessagesAtTop();
        }
    }

    private void UpdateViewingLatest()
    {
        if (_viewModel is null || GetScrollViewer(MessageList) is not { } viewport) { return; }
        var bounds = new Rect(0, 0, viewport.ActualWidth, viewport.ActualHeight);
        _viewModel.IsViewingLatest = _viewModel.Messages.TakeLast(3).Any(item =>
        {
            if (MessageList.ItemContainerGenerator.ContainerFromItem(item) is not FrameworkElement row) { return false; }
            return row.TransformToAncestor(viewport).TransformBounds(new Rect(row.RenderSize)).IntersectsWith(bounds);
        });
        if (_viewModel.IsViewingLatest) { _viewModel.MarkActiveConversationRead(); }
    }

    private void LoadOlderMessagesAtTop()
    {
        if (_loadingOlderMessages || _viewModel is not { HasOlderMessages: true }) return;
        _loadingOlderMessages = true;
        try
        {
            var anchor = _viewModel.Messages.FirstOrDefault();
            _viewModel.LoadOlderMessages();
            if (anchor is not null) MessageList.ScrollIntoView(anchor);
        }
        finally { _loadingOlderMessages = false; }
    }

    private void Window_DragOver(object sender, System.Windows.DragEventArgs e)
    {
        _ = sender;
        e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
        e.Handled = true;
    }

    private async void Window_Drop(object sender, System.Windows.DragEventArgs e)
    {
        _ = sender;
        if (_viewModel is null || !e.Data.GetDataPresent(DataFormats.FileDrop)) { return; }
        if (e.Data.GetData(DataFormats.FileDrop) is string[] { Length: > 0 } files) { await _viewModel.SendMediaAsync(files[0]); }
    }

    private void Calls_ShowRequested(object? sender, EventArgs e)
    {
        _ = sender;
        _ = e;
        if (_services is null) { return; }
        if (_callWindow is null) { _callWindow = new CallOverlay(_services.Calls); _callWindow.CoverageChanged += (_, _) => UpdateChatVisibility(); CallOverlayHost.Content = _callWindow; }
        UpdateChatVisibility();
        Show(); WindowState = WindowState.Normal; Activate();
    }

    private void UpdateChatVisibility()
    {
        if (_viewModel is null) return;
        _viewModel.IsChatContentVisible = MediaOverlayHost.Content is null && _callWindow?.CoversChat != true;
        _viewModel.MarkActiveConversationRead();
    }

    private void Latest_Click(object sender, RoutedEventArgs e) => ScrollToLatest();

    private void ScrollToLatest()
    {
        if (_viewModel?.Messages.LastOrDefault() is { } last) { MessageList.ScrollIntoView(last); }
    }

    private void ScrollToLatestIfNearBottom()
    {
        var scroll = GetScrollViewer(MessageList);
        if (scroll is null || _viewModel?.IsViewingLatest == true || _viewModel?.Messages.LastOrDefault()?.IsMine == true) { ScrollToLatest(); }
    }

    private static ScrollViewer? GetScrollViewer(DependencyObject root)
    {
        for (var index = 0; index < System.Windows.Media.VisualTreeHelper.GetChildrenCount(root); index++)
        {
            var child = System.Windows.Media.VisualTreeHelper.GetChild(root, index);
            if (child is ScrollViewer viewer) { return viewer; }
            if (GetScrollViewer(child) is { } nested) { return nested; }
        }
        return null;
    }

    private bool Confirm(string message) => new ConfirmationDialog(message) { Owner = this }.ShowDialog() == true;

    private void RestoreWindowPlacement()
    {
        try
        {
            var path = PlacementPath();
            if (!File.Exists(path)) { return; }
            var value = JsonSerializer.Deserialize<WindowPlacement>(File.ReadAllText(path));
            if (value is null || value.Width < MinWidth || value.Height < MinHeight) { return; }
            Width = value.Width;
            Height = value.Height;
            Left = Math.Max(SystemParameters.VirtualScreenLeft, Math.Min(value.Left, SystemParameters.VirtualScreenLeft + SystemParameters.VirtualScreenWidth - Width));
            Top = Math.Max(SystemParameters.VirtualScreenTop, Math.Min(value.Top, SystemParameters.VirtualScreenTop + SystemParameters.VirtualScreenHeight - Height));
        }
        catch (Exception exception) when (exception is IOException or JsonException) { Trace.TraceWarning("Window placement was ignored: {0}", exception.Message); }
    }

    private void SaveWindowPlacement()
    {
        if (WindowState != WindowState.Normal) { return; }
        var path = PlacementPath();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, JsonSerializer.Serialize(new WindowPlacement(Left, Top, Width, Height)));
    }

    private static string PlacementPath() => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MsgAT", "window-v2.json");
    private sealed record WindowPlacement(double Left, double Top, double Width, double Height);
}
