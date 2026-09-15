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
    private CallWindow? _callWindow;
    private bool _allowClose;
    private bool _loadingOlderMessages;
    private bool _exiting;
    private Task<AppServices>? _initialization;

    public MainWindow()
    {
        InitializeComponent();
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
            if (string.IsNullOrWhiteSpace(_services.Settings.Snapshot.Nickname))
            {
                var setup = new IdentitySetupDialog { Owner = this };
                if (setup.ShowDialog() != true)
                {
                    ((App)System.Windows.Application.Current).ExitApplication();
                    return;
                }
                var avatar = _services.Media.SaveAvatar(_services.Settings.MyUuid, AvatarProcessor.EncodeJpeg(setup.AvatarPath));
                _services.Settings.SetInitialIdentity(setup.Nickname, avatar);
                await _services.Messaging.SendProfileAsync();
            }
            if (_exiting) { return; }
            _viewModel = new MainViewModel(_services, Dispatcher);
            _viewModel.ErrorRequested += (_, message) => MessageBox.Show(this, message, "MsgAT", MessageBoxButton.OK, MessageBoxImage.Warning);
            _viewModel.InitialMessagesLoaded += (_, _) =>
                Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Loaded, new Action(ScrollToLatest));
            _viewModel.LatestMessagesChanged += (_, _) => ScrollToLatestIfNearBottom();
            DataContext = _viewModel;
            _services.Calls.ShowRequested += Calls_ShowRequested;
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
        if (_callWindow is not null) { _callWindow.IsEnabled = false; _callWindow.Hide(); }
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
        if (_viewModel is not null) { await _viewModel.SendDraftAsync(); ScrollToLatest(); }
    }

    private async void Composer_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        _ = sender;
        if (e.Key == Key.V && Keyboard.Modifiers == ModifierKeys.Control && Clipboard.ContainsImage())
        {
            e.Handled = true;
            await SendClipboardImageAsync();
            return;
        }
        if (e.Key == Key.Enter && Keyboard.Modifiers != ModifierKeys.Shift)
        {
            e.Handled = true;
            if (_viewModel is not null) { await _viewModel.SendDraftAsync(); ScrollToLatest(); }
        }
    }

    private void Attachment_Click(object sender, RoutedEventArgs e)
    {
        _ = e;
        if (sender is not Button button || _viewModel?.SelectedConversation is null) { return; }
        var menu = new ContextMenu();
        var media = new MenuItem { Header = "Photo or file…" };
        media.Click += async (_, _) => await PickAndSendMediaAsync();
        var call = new MenuItem { Header = "Voice call" };
        call.Click += async (_, _) =>
        {
            try { await _services!.Calls.StartOutgoingAsync(_viewModel.SelectedConversation.Uuid); }
            catch (InvalidOperationException exception) { MessageBox.Show(this, exception.Message, "MsgAT", MessageBoxButton.OK, MessageBoxImage.Warning); }
        };
        menu.Items.Add(media);
        menu.Items.Add(call);
        button.ContextMenu = menu;
        menu.PlacementTarget = button;
        menu.IsOpen = true;
        e.Handled = true;
    }

    private async Task PickAndSendMediaAsync()
    {
        var picker = new OpenFileDialog { Filter = "Supported files|*.png;*.jpg;*.jpeg;*.gif;*.webp;*.mp4;*.mov;*.pdf;*.zip|All files|*.*" };
        if (picker.ShowDialog(this) == true && _viewModel is not null) { await _viewModel.SendMediaAsync(picker.FileName); }
    }

    private async void AddFriend_Click(object sender, RoutedEventArgs e)
    {
        _ = sender;
        _ = e;
        if (_viewModel is null) { return; }
        var dialog = new TextPromptDialog("Add friend", "UUID") { Owner = this };
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
                if (Confirm("Reset this identity and permanently delete all local conversations?"))
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
            if (!_exiting) { MessageBox.Show(this, exception.Message, "MsgAT", MessageBoxButton.OK, MessageBoxImage.Warning); }
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
        _viewModel.SelectedConversation = conversation;
        container.IsSelected = true;
        var menu = new ContextMenu();
        var clear = new MenuItem { Header = "Clear messages" };
        clear.Click += (_, _) =>
        {
            if (Confirm("Clear all messages in this conversation?")) { _viewModel.ClearSelectedMessages(); }
        };
        var delete = new MenuItem { Header = "Delete friend" };
        delete.Click += async (_, _) =>
        {
            if (Confirm("Delete this friend and all local messages?")) { await _viewModel.DeleteSelectedFriendAsync(); }
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
        container.IsSelected = true;
        var menu = new ContextMenu();
        if (!item.IsRevoked)
        {
            var reply = new MenuItem { Header = "Reply" };
            reply.Click += (_, _) => { _viewModel.SetReply(item); Composer.Focus(); };
            menu.Items.Add(reply);
        }
        if (!item.IsRevoked && item.Extension != "txt")
        {
            var open = new MenuItem { Header = "Open" };
            open.Click += (_, _) => OpenAttachment(item);
            menu.Items.Add(open);
            var save = new MenuItem { Header = "Save as…" };
            save.Click += (_, _) => SaveAttachment(item);
            menu.Items.Add(save);
        }
        if (!item.IsRevoked && item.Extension == "txt")
        {
            var copy = new MenuItem { Header = "Copy text" };
            copy.Click += (_, _) => Clipboard.SetText(item.Content);
            menu.Items.Add(copy);
        }
        if (item.CanModify)
        {
            if (item.Extension == "txt")
            {
                var edit = new MenuItem { Header = "Edit" };
                edit.Click += async (_, _) =>
                {
                    var dialog = new TextPromptDialog("Edit message", "Message", item.Content) { Owner = this };
                    if (dialog.ShowDialog() == true) { await _viewModel.EditAsync(item, dialog.Value); }
                };
                menu.Items.Add(edit);
            }
            var revoke = new MenuItem { Header = "Revoke" };
            revoke.Click += async (_, _) => await _viewModel.RevokeAsync(item);
            menu.Items.Add(revoke);
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

    private void ReplyPreview_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: MessageItem { ReplyToId: { } id } } && _viewModel?.RevealMessage(id) is { } target)
        {
            MessageList.SelectedItem = target;
            MessageList.ScrollIntoView(target);
            e.Handled = true;
        }
    }

    private void CancelReply_Click(object sender, RoutedEventArgs e)
    {
        _ = sender;
        _ = e;
        _viewModel?.CancelReply();
    }

    private async Task SendClipboardImageAsync()
    {
        var image = Clipboard.GetImage();
        if (image is null || _viewModel is null) { return; }
        var temporaryPath = Path.Combine(Path.GetTempPath(), $"msgat-clipboard-{Guid.NewGuid():N}.png");
        try
        {
            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(image));
            await using (var stream = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                encoder.Save(stream);
                await stream.FlushAsync();
            }
            await _viewModel.SendMediaAsync(temporaryPath);
        }
        finally { File.Delete(temporaryPath); }
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
            if (item.Extension is "jpg" or "jpeg" or "png" or "bmp" or "gif" or "webp" or "mp4" or "mov" or "webm")
            {
                new AttachmentWindow(item.Content, item.Extension) { Owner = this }.Show();
            }
            else { Process.Start(new ProcessStartInfo(item.Content) { UseShellExecute = true }); }
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or NotSupportedException or System.ComponentModel.Win32Exception)
        {
            MessageBox.Show(this, error.Message, "MsgAT", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void SaveAttachment(MessageItem item)
    {
        var picker = new SaveFileDialog { FileName = Path.GetFileName(item.Content), AddExtension = true, DefaultExt = "." + item.Extension };
        if (picker.ShowDialog(this) != true) return;
        try
        {
            if (!string.Equals(Path.GetFullPath(picker.FileName), Path.GetFullPath(item.Content), StringComparison.OrdinalIgnoreCase))
                File.Copy(item.Content, picker.FileName, overwrite: true);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            MessageBox.Show(this, error.Message, "MsgAT", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
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
        if (e.VerticalChange < 0 && e.VerticalOffset <= 1 &&
            ReferenceEquals(e.OriginalSource, GetScrollViewer(MessageList)))
        {
            LoadOlderMessagesAtTop();
        }
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
        _callWindow ??= new CallWindow(_services.Calls) { Owner = this };
        _callWindow.Show();
        _callWindow.WindowState = WindowState.Normal;
        _callWindow.Activate();
    }

    private void ScrollToLatest()
    {
        if (_viewModel?.Messages.LastOrDefault() is { } last) { MessageList.ScrollIntoView(last); }
    }

    private void ScrollToLatestIfNearBottom()
    {
        var scroll = GetScrollViewer(MessageList);
        if (scroll is null || scroll.ScrollableHeight - scroll.VerticalOffset < 100) { ScrollToLatest(); }
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

    private bool Confirm(string message) => MessageBox.Show(this, message, "MsgAT", MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes;

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
