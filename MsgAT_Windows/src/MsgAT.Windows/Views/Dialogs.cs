using Microsoft.Win32;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;
using System.Text.RegularExpressions;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;
using CheckBox = System.Windows.Controls.CheckBox;
using TextBox = System.Windows.Controls.TextBox;

namespace MsgAT.Windows.Views;

public abstract class DialogWindow : Window
{
    protected DialogWindow()
    {
        Style = (Style)System.Windows.Application.Current.FindResource("AppWindowStyle");
        Background = (System.Windows.Media.Brush)System.Windows.Application.Current.FindResource("WindowBrush");
        Foreground = (System.Windows.Media.Brush)System.Windows.Application.Current.FindResource("TextBrush");
        ShowInTaskbar = false;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        WindowAppearance.Apply(this);
    }

    protected static Style ResourceStyle(string key) =>
        (Style)System.Windows.Application.Current.FindResource(key);
}

public sealed class IdentitySetupDialog : DialogWindow
{
    private readonly TextBox _nickname = Field();
    private byte[]? _avatar;
    private bool _removeAvatar;
    private bool _saving;
    private bool _closed;

    public IdentitySetupDialog(Func<string, ReadOnlyMemory<byte>?, bool, Task<bool>> saveProfile,
        string nickname = "", string? avatarReference = null, bool editing = false)
    {
        SetResourceReference(TitleProperty, editing ? "UiEditProfile" : "UiProfileSetupTitle");
        Width = 425;
        Height = Math.Min(365, SystemParameters.WorkArea.Height * .82);
        _nickname.Text = nickname;
        _nickname.MaxLength = 256;

        var root = new DockPanel();
        var title = new TextBlock { Text = AppStrings.Get(editing ? "UiEditProfile" : "UiProfileSetupTitle"),
            FontSize = 17, FontWeight = FontWeights.SemiBold, Margin = new Thickness(14, 9, 14, 12) };
        DockPanel.SetDock(title, Dock.Top);
        root.Children.Add(title);

        var footer = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right,
            Margin = new Thickness(0, 10, 0, 0) };
        var cancel = ActionButton(AppStrings.Get("UiCancel"), () => DialogResult = false, Brushes.Gray);
        cancel.IsCancel = true;
        var save = new Button { Content = AppStrings.Get("UiSave"), Style = ResourceStyle("PrimaryButtonStyle"),
            IsDefault = true, Margin = new Thickness(8, 0, 0, 0) };
        footer.Children.Add(cancel);
        footer.Children.Add(save);
        DockPanel.SetDock(footer, Dock.Bottom);
        root.Children.Add(footer);

        var avatarButton = new Button
        {
            Width = 92, Height = 92, Padding = new Thickness(0), Background = Brushes.Transparent,
            BorderBrush = Brushes.Transparent, HorizontalAlignment = HorizontalAlignment.Center
        };
        var choose = new Button { Margin = new Thickness(2), MinHeight = 32 };
        var remove = new Button { Content = AppStrings.Get("UiProfileRemoveAvatar"), Style = ResourceStyle("DangerButtonStyle"),
            Margin = new Thickness(2), MinHeight = 32 };
        var photoActions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 6, 0, 10) };
        photoActions.Children.Add(choose);
        photoActions.Children.Add(remove);
        var error = new TextBlock { TextWrapping = TextWrapping.Wrap };
        error.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
        var nicknameError = new TextBlock { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed,
            Text = AppStrings.Get("UiProfileNicknameTooLong") };
        nicknameError.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");

        void UpdatePhotoActions()
        {
            var hasPhoto = _avatar is not null || (!_removeAvatar && !string.IsNullOrEmpty(avatarReference));
            var label = AppStrings.Get(hasPhoto ? "UiProfileChangeAvatar" : "UiProfileChooseAvatar");
            choose.Content = label;
            avatarButton.ToolTip = label;
            System.Windows.Automation.AutomationProperties.SetName(avatarButton, label);
            remove.Visibility = hasPhoto ? Visibility.Visible : Visibility.Collapsed;
        }

        void ShowPickerIcon()
        {
            var icon = new ContentControl
            {
                Content = FindResource("ContactMediaIcon"),
                ContentTemplate = (DataTemplate)FindResource("IconTemplate"),
                Width = 88, Height = 88, HorizontalContentAlignment = HorizontalAlignment.Center,
                VerticalContentAlignment = VerticalAlignment.Center
            };
            icon.SetResourceReference(Control.ForegroundProperty, "AccentTextBrush");
            var tile = new Border { Child = icon, CornerRadius = new CornerRadius(44), Width = 88, Height = 88 };
            tile.SetResourceReference(Border.BackgroundProperty, "SurfaceBrush");
            avatarButton.Content = tile;
        }

        if (string.IsNullOrEmpty(avatarReference)) ShowPickerIcon();
        else avatarButton.Content = Avatar(avatarReference, nickname, 88, 44);
        UpdatePhotoActions();

        void PickPhoto()
        {
            var picker = new OpenFileDialog { Filter = "Images|*.png;*.jpg;*.jpeg;*.bmp;*.webp" };
            if (picker.ShowDialog(this) != true || _closed) return;
            byte[]? staged = null;
            try
            {
                var crop = new AvatarCropDialog(picker.FileName) { Owner = this };
                if (crop.ShowDialog() != true || crop.Result is null) return;
                staged = crop.Result;
                if (_closed) return;
                using var bytes = new MemoryStream(staged, writable: false);
                var image = ImageDecoder.Load(bytes, 512);
                avatarButton.Content = new System.Windows.Shapes.Ellipse
                {
                    Width = 88, Height = 88, Fill = new ImageBrush(image) { Stretch = Stretch.UniformToFill }
                };
                ReleaseAvatar();
                _avatar = staged;
                staged = null;
                _removeAvatar = false;
                error.Text = "";
                UpdatePhotoActions();
            }
            catch (Exception exception) when (exception is IOException or ArgumentException or NotSupportedException or InvalidOperationException)
            {
                error.Text = AppStrings.Get("UiProfileImageUnavailable");
            }
            finally
            {
                if (staged is not null) System.Security.Cryptography.CryptographicOperations.ZeroMemory(staged);
            }
        }

        avatarButton.Click += (_, _) => PickPhoto();
        choose.Click += (_, _) => PickPhoto();
        remove.Click += (_, _) =>
        {
            ReleaseAvatar();
            _removeAvatar = true;
            ShowPickerIcon();
            error.Text = "";
            UpdatePhotoActions();
        };

        var fields = new StackPanel();
        fields.Children.Add(avatarButton);
        fields.Children.Add(photoActions);
        fields.Children.Add(Row("UiProfileNickname", _nickname, localized: true));
        fields.Children.Add(nicknameError);
        fields.Children.Add(error);
        root.Children.Add(new ScrollViewer { Content = fields, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        Content = DialogCard(root);

        bool NicknameTooLong() => System.Text.Encoding.UTF8.GetByteCount(_nickname.Text.Trim()) > 256;
        bool CanSave() => !string.IsNullOrWhiteSpace(_nickname.Text) && !NicknameTooLong();
        void RefreshNickname()
        {
            nicknameError.Visibility = NicknameTooLong() ? Visibility.Visible : Visibility.Collapsed;
            save.IsEnabled = !_saving && CanSave();
        }
        _nickname.TextChanged += (_, _) => { error.Text = ""; RefreshNickname(); };
        RefreshNickname();
        save.Click += async (_, _) =>
        {
            if (_saving || !CanSave()) return;
            _saving = true;
            fields.IsEnabled = save.IsEnabled = cancel.IsEnabled = false;
            save.Content = AppStrings.Get("UiSaving");
            error.Text = "";
            var succeeded = false;
            try
            {
                var bytes = _avatar is { } data ? new ReadOnlyMemory<byte>(data) : (ReadOnlyMemory<byte>?)null;
                succeeded = await saveProfile(_nickname.Text.Trim(), bytes, _removeAvatar);
            }
            catch (Exception exception) when (exception is IOException or InvalidOperationException or OperationCanceledException or ArgumentException) { }
            finally
            {
                _saving = false;
                if (_closed) ReleaseAvatar();
            }
            if (_closed) return;
            if (succeeded) DialogResult = true;
            else
            {
                fields.IsEnabled = cancel.IsEnabled = true;
                save.IsEnabled = CanSave();
                save.Content = AppStrings.Get("UiSave");
                error.Text = AppStrings.Get("UiProfileSaveFailed");
            }
        };
        Loaded += (_, _) => _nickname.Focus();
        Closed += (_, _) =>
        {
            _closed = true;
            if (!_saving) ReleaseAvatar();
        };
    }

    private void ReleaseAvatar()
    {
        if (_avatar is { } bytes) System.Security.Cryptography.CryptographicOperations.ZeroMemory(bytes);
        _avatar = null;
    }

    internal static TextBox Field(string value = "") => new()
    {
        Text = value,
        MinWidth = 0,
        Height = 38,
        Style = ResourceStyle("TextBoxStyle"),
        VerticalContentAlignment = VerticalAlignment.Center
    };

    internal static FrameworkElement Row(string label, FrameworkElement field, bool localized = false)
    {
        var panel = new Grid { Margin = new Thickness(0, 0, 0, 10) };
        panel.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(label.Length == 0 ? 0 : 124) });
        panel.ColumnDefinitions.Add(new ColumnDefinition());
        var text = new TextBlock { Text = label, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center,
            Foreground = (Brush)System.Windows.Application.Current.FindResource("MutedBrush") };
        if (localized)
        {
            text.SetResourceReference(TextBlock.TextProperty, label);
            field.SetResourceReference(System.Windows.Automation.AutomationProperties.NameProperty, label);
        }
        else System.Windows.Automation.AutomationProperties.SetName(field, label);
        Grid.SetColumn(field, 1); panel.Children.Add(text); panel.Children.Add(field);
        return panel;
    }

    internal static FrameworkElement Form(params UIElement[] children)
    {
        var panel = new StackPanel();
        foreach (var child in children) panel.Children.Add(child);
        return panel;
    }

    internal static TextBlock Heading(string title, double size = 20) => new() { Text = title, Foreground = (Brush)System.Windows.Application.Current.FindResource("TextBrush"), FontSize = size, Margin = new Thickness(0, 0, 0, 20) };

    internal static Border DialogCard(UIElement child) => new()
    {
        Background = (System.Windows.Media.Brush)System.Windows.Application.Current.FindResource("PanelBrush"),
        BorderBrush = (System.Windows.Media.Brush)System.Windows.Application.Current.FindResource("BorderBrush"),
        BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(12),
        Padding = new Thickness(22),
        Margin = new Thickness(16),
        Child = child
    };

    internal static Button ActionButton(string label, Action? action, Brush color)
    {
        var style = color == Brushes.Red ? "DangerButtonStyle" : color == Brushes.Gray || color == Brushes.White ? "ButtonStyle" : "PrimaryButtonStyle";
        var button = new Button { Content = label, IsCancel = label == "Cancel", MinHeight = 36,
            Margin = new Thickness(8, 0, 0, 0), Style = ResourceStyle(style) };
        if (action is not null) button.Click += (_, _) => action();
        return button;
    }

    internal static FrameworkElement Buttons(params (string Label, Action Action, string? StyleKey)[] definitions)
    {
        var panel = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 24, 0, 0) };
        foreach (var definition in definitions)
            panel.Children.Add(ActionButton(definition.Label, definition.Action, definition.StyleKey == "PrimaryButtonStyle" ? Brushes.Lime : definition.StyleKey == "DangerButtonStyle" ? Brushes.Red : Brushes.Gray));
        return panel;
    }

    internal static FrameworkElement Avatar(string? path, string fallback, double size, double radius)
    {
        var grid = new Grid { Width = size, Height = size };
        grid.Children.Add(new Rectangle { Fill = Brushes.DarkGray, RadiusX = radius, RadiusY = radius });
        grid.Children.Add(new TextBlock { Text = string.IsNullOrEmpty(fallback) ? "" : StringInfo.GetNextTextElement(fallback).ToUpperInvariant(), FontSize = size >= 64 ? 24 : 20, Foreground = Brushes.White, VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center });
        var picture = new Rectangle { RadiusX = radius, RadiusY = radius };
        picture.SetBinding(Shape.FillProperty, new System.Windows.Data.Binding { Source = path ?? "", Converter = (System.Windows.Data.IValueConverter)System.Windows.Application.Current.FindResource("AvatarConverter") });
        grid.Children.Add(picture);
        return grid;
    }

}

public sealed class ConfirmationDialog : DialogWindow
{
    public ConfirmationDialog(string message, string? title = null, bool destructive = true,
        string? dismissLabel = null, string? confirmLabel = null)
    {
        title ??= AppStrings.Get("UiWarning");
        Title = title;
        Width = Math.Min(400, SystemParameters.WorkArea.Width * .94);
        MaxHeight = SystemParameters.WorkArea.Height * .90;
        SizeToContent = SizeToContent.Height;
        var heading = IdentitySetupDialog.Heading(title);
        heading.TextWrapping = TextWrapping.Wrap;
        var cancel = IdentitySetupDialog.ActionButton(dismissLabel ?? AppStrings.Get("UiCancel"), () => DialogResult = false, Brushes.Gray);
        cancel.IsCancel = true;
        var confirm = IdentitySetupDialog.ActionButton(confirmLabel ?? AppStrings.Get("UiConfirm"), () => DialogResult = true,
            destructive ? Brushes.Red : Brushes.Lime);
        var actions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        actions.Children.Add(cancel);
        actions.Children.Add(confirm);
        var layout = new DockPanel();
        DockPanel.SetDock(heading, Dock.Top);
        layout.Children.Add(heading);
        DockPanel.SetDock(actions, Dock.Bottom);
        layout.Children.Add(actions);
        layout.Children.Add(new ScrollViewer
        {
            Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap },
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled
        });
        Content = IdentitySetupDialog.DialogCard(layout);
        Loaded += (_, _) => cancel.Focus();
    }
}

internal sealed class StartupIssueDialog : DialogWindow
{
    public StartupIssueDialog(string title, string summary, string diagnostic, bool canReset, string? resetError)
    {
        Title = title;
        Width = Math.Min(460, SystemParameters.WorkArea.Width * .94);
        MaxHeight = SystemParameters.WorkArea.Height * .90;
        SizeToContent = SizeToContent.Height;
        var body = new StackPanel();
        body.Children.Add(new TextBlock { Text = summary, TextWrapping = TextWrapping.Wrap });
        var details = new Expander { Header = AppStrings.Get("UiStartupTechnicalDetails"), Margin = new Thickness(0, 12, 0, 0) };
        details.Content = new TextBox
        {
            Text = diagnostic, TextWrapping = TextWrapping.Wrap, IsReadOnly = true, MaxHeight = 145,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Margin = new Thickness(0, 8, 0, 0)
        };
        body.Children.Add(details);
        if (resetError is not null)
        {
            var error = new TextBlock { Text = resetError, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 10, 0, 0) };
            error.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
            body.Children.Add(error);
        }
        var actions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        if (canReset)
            actions.Children.Add(IdentitySetupDialog.ActionButton(AppStrings.Get("UiResetIdentity"), () => DialogResult = true, Brushes.Red));
        var quit = IdentitySetupDialog.ActionButton(AppStrings.Get("UiQuit"), () => DialogResult = false, Brushes.Gray);
        quit.IsCancel = true;
        actions.Children.Add(quit);

        var layout = new DockPanel();
        var heading = IdentitySetupDialog.Heading(title);
        DockPanel.SetDock(heading, Dock.Top);
        layout.Children.Add(heading);
        DockPanel.SetDock(actions, Dock.Bottom);
        layout.Children.Add(actions);
        layout.Children.Add(new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        Content = IdentitySetupDialog.DialogCard(layout);
        Loaded += (_, _) => quit.Focus();
    }
}

internal sealed class DeleteMessagesDialog : DialogWindow
{
    private readonly CheckBox _revoke = new()
    {
        Content = new TextBlock { Text = AppStrings.Get("UiRevokeSelected"), TextWrapping = TextWrapping.Wrap },
        Margin = new Thickness(0, 12, 0, 0)
    };

    internal bool RevokeForBoth => _revoke.IsChecked == true;

    internal DeleteMessagesDialog()
    {
        Title = AppStrings.Get("UiDeleteMessagesTitle");
        Width = 400;
        SizeToContent = SizeToContent.Height;
        var explanation = new TextBlock { Text = AppStrings.Get("UiLocalDeleteMessage"), TextWrapping = TextWrapping.Wrap };
        var cancel = IdentitySetupDialog.ActionButton(AppStrings.Get("UiCancel"), () => DialogResult = false, Brushes.Gray);
        cancel.IsCancel = true;
        var confirm = IdentitySetupDialog.ActionButton(AppStrings.Get("UiLocalDelete"), () => DialogResult = true, Brushes.Red);
        void RefreshChoice()
        {
            explanation.Text = AppStrings.Get(RevokeForBoth ? "UiRevokeSelectedMessage" : "UiLocalDeleteMessage");
            confirm.Content = AppStrings.Get(RevokeForBoth ? "UiRevoke" : "UiLocalDelete");
        }
        _revoke.Checked += (_, _) => RefreshChoice();
        _revoke.Unchecked += (_, _) => RefreshChoice();
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 24, 0, 0) };
        actions.Children.Add(cancel);
        actions.Children.Add(confirm);
        Content = IdentitySetupDialog.DialogCard(IdentitySetupDialog.Form(
            IdentitySetupDialog.Heading(Title), explanation, _revoke, actions));
    }
}

internal sealed class PinMessageDialog : DialogWindow
{
    private readonly CheckBox _notify = new() { Content = AppStrings.Get("UiPinNotifyPeer") };

    internal bool NotifyPeer => _notify.IsChecked == true;

    internal PinMessageDialog()
    {
        Title = AppStrings.Get("UiPinMessage");
        Width = 360;
        SizeToContent = SizeToContent.Height;
        Content = IdentitySetupDialog.DialogCard(IdentitySetupDialog.Form(
            IdentitySetupDialog.Heading(Title),
            _notify,
            IdentitySetupDialog.Buttons(
                (AppStrings.Get("UiCancel"), () => DialogResult = false, null),
                (Title, () => DialogResult = true, "PrimaryButtonStyle"))));
    }
}

public sealed class TextPromptDialog : DialogWindow
{
    private readonly TextBox _value = IdentitySetupDialog.Field();
    private readonly TextBlock _error = new() { Foreground = (Brush)System.Windows.Application.Current.FindResource("DangerBrush"), FontSize = 12, TextWrapping = TextWrapping.Wrap };
    private readonly string? _myUuid;
    public TextPromptDialog(string title, string label, string initialValue = "", string? myUuid = null,bool multiline=true)
    {
        _myUuid = myUuid; Title = title; Width = 400; SizeToContent = SizeToContent.Height;
        _value.Text = initialValue;
        if (myUuid is not null) _value.MaxLength = 256;
        if (myUuid is null && multiline) { _value.AcceptsReturn = true; _value.TextWrapping = TextWrapping.Wrap; _value.Height = 90; }
        _value.TextChanged += (_, _) => { _error.Text = ""; };
        var cancel = IdentitySetupDialog.ActionButton(AppStrings.Get("UiCancel"), () => DialogResult = false, Brushes.Gray);
        cancel.IsCancel = true;
        var submit = IdentitySetupDialog.ActionButton(AppStrings.Get(myUuid is null ? "UiSave" : "UiContactSendRequest"), Save, Brushes.Lime);
        submit.IsDefault = myUuid is not null || !multiline;
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right,
            Margin = new Thickness(0, 16, 0, 0) };
        actions.Children.Add(cancel);
        actions.Children.Add(submit);
        Content = IdentitySetupDialog.DialogCard(IdentitySetupDialog.Form(IdentitySetupDialog.Heading(title),
            IdentitySetupDialog.Row(label, _value), _error, actions));
        Loaded += (_, _) => _value.Focus();
    }
    public string Value => _myUuid is null ? _value.Text : _value.Text.Trim().ToLowerInvariant();
    private void Save()
    {
        if (_myUuid is not null)
        {
            if (!Regex.IsMatch(Value, "\\A[a-f0-9]{64}\\z", RegexOptions.CultureInvariant)) { _error.Text = AppStrings.Get("UiContactInvalidId"); return; }
            if (string.Equals(Value, _myUuid, StringComparison.OrdinalIgnoreCase)) { _error.Text = AppStrings.Get("UiContactSelfId"); return; }
        }
        if (!string.IsNullOrWhiteSpace(Value)) DialogResult = true;
    }
}

public static class TransientNotice
{
    public static void Show(Window owner, string text)
    {
        if (!owner.IsVisible) return;
        var notice = new Window { Owner = owner, WindowStyle = WindowStyle.None, AllowsTransparency = true, Background = Brushes.Transparent, ShowInTaskbar = false, ShowActivated = false, ResizeMode = ResizeMode.NoResize, SizeToContent = SizeToContent.WidthAndHeight };
        notice.Content = new Border { Background = new SolidColorBrush(Color.FromRgb(44, 44, 44)), CornerRadius = new CornerRadius(24), Padding = new Thickness(16, 10, 16, 10), Child = new TextBlock { Text = text, Foreground = Brushes.White, FontSize = 14, MaxWidth = 400, TextWrapping = TextWrapping.Wrap } };
        notice.Loaded += (_, _) => { notice.Left = owner.Left + (owner.ActualWidth - notice.ActualWidth) / 2; notice.Top = owner.Top + owner.ActualHeight - notice.ActualHeight - 32; };
        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        timer.Tick += (_, _) => { timer.Stop(); notice.Close(); };
        notice.Closed += (_, _) => timer.Stop();
        notice.Show(); timer.Start();
    }
}

public sealed class SettingsDialog : DialogWindow
{
    internal static IReadOnlyList<int> ChatTextPercentages { get; } = [85, 100, 115, 130];
    private readonly TextBox _server = IdentitySetupDialog.Field();
    private readonly TextBox _port = IdentitySetupDialog.Field();
    private readonly TextBox _turnServer = IdentitySetupDialog.Field();
    private readonly TextBox _turnPort = IdentitySetupDialog.Field();
    private readonly TextBox _turnUser = IdentitySetupDialog.Field();
    private readonly PasswordBox _turnPassword = new() { Height = 38, Padding = new Thickness(11, 8, 11, 8), Background = (Brush)System.Windows.Application.Current.FindResource("SurfaceBrush"), Foreground = (Brush)System.Windows.Application.Current.FindResource("TextBrush") };
    private readonly CheckBox _turnTls = new() { Foreground = (Brush)System.Windows.Application.Current.FindResource("TextBrush") };
    private readonly TextBox _certificate = IdentitySetupDialog.Field();
    private readonly CheckBox _developmentCertificate = new() { Foreground = (Brush)System.Windows.Application.Current.FindResource("MutedBrush") };
    private readonly SecureSettings _original;
    public SettingsDialog(SecureSettings settings, string uuid,
        Func<Window, SecureSettings?>? editProfile = null, Action<Window>? openPrivacy = null, Action<Window>? openStorage = null,
        int chatTextPercentage = 100, Action<int>? changeChatTextPercentage = null,
        Action<AppThemeMode>? changeTheme = null, Action<AppLanguage>? changeLanguage = null,
        Action<Window>? openNotifications = null, bool closeToTray = true, Func<bool, bool>? changeCloseToTray = null,
        Action<Window>? openExportFolder = null)
    {
        var pendingRevocations = new TextBlock
        {
            Foreground = (Brush)FindResource("MutedBrush"), TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(8, 0, 8, 5)
        };
        void UpdatePendingRevocations()
        {
            var count = IdentityLifecycle.PendingCount;
            pendingRevocations.Text = count > 0 ? AppStrings.Format("UiPendingIdentityRevocations", count) : "";
            pendingRevocations.Visibility = count > 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        void PendingRevocationsChanged(object? sender, EventArgs args) =>
            Dispatcher.BeginInvoke(new Action(UpdatePendingRevocations));
        Loaded += (_, _) =>
        {
            IdentityLifecycle.PendingCountChanged += PendingRevocationsChanged;
            UpdatePendingRevocations();
        };
        Closed += (_, _) => IdentityLifecycle.PendingCountChanged -= PendingRevocationsChanged;
        _original = settings;
        SetResourceReference(TitleProperty, "UiSettings");
        Width = 510;
        Height = Math.Min(480, SystemParameters.WorkArea.Height * .82);
        void ResetNetworkDraft()
        {
            _server.Text = settings.ServerAddress ?? "";
            _port.Text = settings.ServerPort == 0 ? "" : settings.ServerPort.ToString(CultureInfo.InvariantCulture);
            _turnServer.Text = settings.TurnServer ?? "";
            _turnPort.Text = settings.TurnPort.ToString(CultureInfo.InvariantCulture);
            _turnUser.Text = settings.TurnUsername ?? "";
            _turnPassword.Password = settings.TurnPassword ?? "";
            _turnTls.IsChecked = settings.TurnUseTls;
            _certificate.Text = settings.ServerCertificateSha256 ?? "";
            _developmentCertificate.IsChecked = settings.AllowDevelopmentCertificate;
        }
        ResetNetworkDraft();
        _turnTls.SetResourceReference(ContentControl.ContentProperty, "UiTurnTls");
        _developmentCertificate.SetResourceReference(ContentControl.ContentProperty, "UiAllowDevelopmentCertificate");

        var turnHeading = new TextBlock { FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 8) };
        turnHeading.SetResourceReference(TextBlock.TextProperty, "UiTurnRelay");
        var advanced = IdentitySetupDialog.Form(
            turnHeading,
            IdentitySetupDialog.Row("UiTurnServer", _turnServer, localized: true), IdentitySetupDialog.Row("UiTurnPort", _turnPort, localized: true),
            IdentitySetupDialog.Row("UiTurnUsername", _turnUser, localized: true), IdentitySetupDialog.Row("UiTurnPassword", _turnPassword, localized: true),
            _turnTls, new Separator { Margin = new Thickness(0, 8, 0, 8) },
            IdentitySetupDialog.Row("UiCertificatePin", _certificate, localized: true), _developmentCertificate);
        _turnTls.Margin = new Thickness(0, 4, 0, 5);
        _developmentCertificate.Margin = new Thickness(0, 4, 0, 0);
        var expand = new Expander
        {
            Content = advanced,
            Foreground = (Brush)FindResource("TextBrush"), Margin = new Thickness(0, 2, 0, 10)
        };
        expand.SetResourceReference(HeaderedContentControl.HeaderProperty, "UiAdvancedNetwork");
        advanced.Margin = new Thickness(8, 10, 0, 0);
        var network = CreateScroll(IdentitySetupDialog.Form(
            IdentitySetupDialog.Row("UiServerAddress", _server, localized: true),
            IdentitySetupDialog.Row("UiServerPort", _port, localized: true), expand));

        var profile = new Border { Child = ProfileCard(settings, uuid), Margin = new Thickness(0, 0, 0, 8) };
        profile.Padding = new Thickness(10);
        profile.CornerRadius = new CornerRadius(8);
        profile.SetResourceReference(Border.BackgroundProperty, "MessagingSidebarBrush");
        var menu = new StackPanel();
        var overview = CreateScroll(menu);
        var search = IdentitySetupDialog.Field();
        var noResults = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(8),
            Foreground = (Brush)FindResource("MutedBrush"), Visibility = Visibility.Collapsed
        };
        noResults.SetResourceReference(TextBlock.TextProperty, "UiNoSettingsResults");
        var topics = new List<(FrameworkElement Row, string Title, string Terms, bool SearchOnly)>();
        void AddTopic(FrameworkElement row, string title, string topic, bool searchOnly = false)
        {
            topics.Add((row, title, "UiSettingsTerms" + topic, searchOnly));
            menu.Children.Add(row);
        }
        void ApplySearch(bool resetScroll = true)
        {
            var words = search.Text.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            var active = words.Length != 0;
            var compare = CultureInfo.CurrentCulture.CompareInfo;
            var any = false;
            foreach (var (row, title, terms, searchOnly) in topics)
            {
                var index = AppStrings.Get(title) + " " + AppStrings.Get(terms);
                var visible = (active || !searchOnly) &&
                    words.All(word => compare.IndexOf(index, word, CompareOptions.IgnoreCase | CompareOptions.IgnoreNonSpace) >= 0);
                row.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
                any |= visible;
            }
            foreach (var separator in menu.Children.OfType<Separator>())
                separator.Visibility = active ? Visibility.Collapsed : Visibility.Visible;
            noResults.Visibility = any ? Visibility.Collapsed : Visibility.Visible;
            if (resetScroll) overview.ScrollToTop();
        }
        AddTopic(profile, "UiCopyMyUuid", "Identity");
        if (editProfile is not null)
        {
            var edit = SettingsRow("UiEditProfile", "\uE77B");
            edit.Click += (_, _) =>
            {
                if (editProfile(this) is { } updated && IsVisible) profile.Child = ProfileCard(updated, uuid);
            };
            AddTopic(edit, "UiEditProfile", "Profile");
        }
        menu.Children.Add(new Separator { Margin = new Thickness(0, 6, 0, 6) });
        var networkLink = SettingsRow("UiNetwork", "\uE968");
        AddTopic(networkLink, "UiNetwork", "Network");
        var advancedLink = SettingsRow("UiAdvancedNetwork", "\uE968");
        AddTopic(advancedLink, "UiAdvancedNetwork", "Advanced", searchOnly: true);
        var turnLink = SettingsRow("UiTurnRelay", "\uE968");
        AddTopic(turnLink, "UiTurnRelay", "Turn", searchOnly: true);
        var certificateLink = SettingsRow("UiCertificatePin", "\uE72E");
        AddTopic(certificateLink, "UiCertificatePin", "Certificate", searchOnly: true);
        if (openNotifications is not null)
        {
            var notifications = SettingsRow("UiNotificationSettings", "\uEA8F");
            notifications.Click += (_, _) => openNotifications(this);
            AddTopic(notifications, "UiNotificationSettings", "Notifications");
        }
        if (openPrivacy is not null)
        {
            var privacy = SettingsRow("UiPrivacy", "\uE72E");
            privacy.Click += (_, _) => openPrivacy(this);
            AddTopic(privacy, "UiPrivacy", "Privacy");
        }
        if (changeLanguage is not null)
        {
            var languages = LocalizedChoices(AppStrings.Language, value => { changeLanguage(value); UpdatePendingRevocations(); ApplySearch(resetScroll: false); },
                (AppLanguage.System, "UiFollowSystem"), (AppLanguage.English, "UiEnglish"),
                (AppLanguage.SimplifiedChinese, "UiSimplifiedChinese"));
            var languageRow = IdentitySetupDialog.Row("UiLanguage", languages, localized: true);
            languageRow.Margin = new Thickness(8, 4, 0, 4);
            AddTopic(languageRow, "UiLanguage", "Language");
        }
        if (changeTheme is not null)
        {
            var themes = LocalizedChoices(WindowAppearance.Theme, changeTheme,
                (AppThemeMode.System, "UiFollowSystem"), (AppThemeMode.Light, "UiLight"), (AppThemeMode.Dark, "UiDark"));
            var themeRow = IdentitySetupDialog.Row("UiTheme", themes, localized: true);
            themeRow.Margin = new Thickness(8, 4, 0, 4);
            AddTopic(themeRow, "UiTheme", "Theme");
        }
        if (changeChatTextPercentage is not null)
        {
            var sizes = new ComboBox { HorizontalAlignment = HorizontalAlignment.Left, MinWidth = 88 };
            foreach (var percentage in ChatTextPercentages)
            {
                var item = new ComboBoxItem
                {
                    Content = (percentage / 100d).ToString("P0", CultureInfo.CurrentCulture),
                    Tag = percentage
                };
                sizes.Items.Add(item);
                if (percentage == chatTextPercentage) sizes.SelectedItem = item;
            }
            sizes.SelectionChanged += (_, _) =>
            {
                if (sizes.SelectedItem is ComboBoxItem { Tag: int percentage }) changeChatTextPercentage(percentage);
            };
            var sizeRow = IdentitySetupDialog.Row("UiTextSize", sizes, localized: true);
            sizeRow.Margin = new Thickness(8, 4, 0, 4);
            AddTopic(sizeRow, "UiTextSize", "Size");
        }
        if (changeCloseToTray is not null)
        {
            var keepRunning = new CheckBox
            {
                IsChecked = closeToTray,
                Margin = new Thickness(8, 5, 8, 5),
                VerticalContentAlignment = VerticalAlignment.Center
            };
            var label = new TextBlock { TextWrapping = TextWrapping.Wrap };
            label.SetResourceReference(TextBlock.TextProperty, "UiCloseToTray");
            keepRunning.Content = label;
            keepRunning.SetResourceReference(System.Windows.Automation.AutomationProperties.NameProperty, "UiCloseToTray");
            keepRunning.Click += (_, _) =>
            {
                var enabled = keepRunning.IsChecked == true;
                if (changeCloseToTray(enabled)) return;
                keepRunning.IsChecked = !enabled;
                TransientNotice.Show(this, AppStrings.Get("UiCloseToTraySaveFailed"));
            };
            AddTopic(keepRunning, "UiCloseToTray", "Tray");
        }
        if (openStorage is not null)
        {
            var storage = SettingsRow("UiStorageUsage", "\uEDA2");
            storage.Click += (_, _) => openStorage(this);
            AddTopic(storage, "UiStorageUsage", "Storage");
        }
        if (openExportFolder is not null)
        {
            var exportFolder = SettingsRow("UiExportFolder", "\uE8B7");
            exportFolder.Click += (_, _) => openExportFolder(this);
            AddTopic(exportFolder, "UiExportFolder", "ExportFolder");
        }
        menu.Children.Add(new Separator { Margin = new Thickness(0, 6, 0, 6) });
        var reset = IdentitySetupDialog.ActionButton("Reset identity", () => { ResetRequested = true; Close(); }, Brushes.Red);
        reset.SetResourceReference(ContentControl.ContentProperty, "UiResetIdentity");
        reset.HorizontalAlignment = HorizontalAlignment.Left;
        reset.Margin = new Thickness(0);
        var resetSection = new StackPanel();
        resetSection.Children.Add(pendingRevocations);
        resetSection.Children.Add(reset);
        AddTopic(resetSection, "UiResetIdentity", "Reset");
        menu.Children.Add(noResults);

        var header = new DockPanel { Margin = new Thickness(0, 0, 0, 8) };
        var back = new Button
        {
            Content = Geometry.Parse("M 16,4 L 8,12 L 16,20"),
            Style = ResourceStyle("IconButtonStyle"), Width = 32, Height = 32, MinHeight = 32,
            Margin = new Thickness(0, 0, 6, 0)
        };
        back.SetResourceReference(ToolTipProperty, "UiBackToSettings");
        back.SetResourceReference(System.Windows.Automation.AutomationProperties.NameProperty, "UiBackToSettings");
        DockPanel.SetDock(back, Dock.Left);
        header.Children.Add(back);
        var heading = IdentitySetupDialog.Heading("Settings", 18);
        heading.VerticalAlignment = VerticalAlignment.Center;
        heading.Margin = new Thickness(0);
        header.Children.Add(heading);

        var searchBar = new DockPanel { Margin = new Thickness(0, 2, 0, 10) };
        var clearSearch = new Button
        {
            Content = "×", Style = ResourceStyle("IconButtonStyle"), Width = 32, Height = 32,
            Margin = new Thickness(4, 0, 0, 0), Visibility = Visibility.Collapsed
        };
        clearSearch.SetResourceReference(ToolTipProperty, "UiClearSettingsSearch");
        clearSearch.SetResourceReference(System.Windows.Automation.AutomationProperties.NameProperty, "UiClearSettingsSearch");
        DockPanel.SetDock(clearSearch, Dock.Right);
        searchBar.Children.Add(clearSearch);
        var searchField = new Grid();
        search.SetResourceReference(System.Windows.Automation.AutomationProperties.NameProperty, "UiSearchSettings");
        searchField.Children.Add(search);
        var placeholder = new TextBlock
        {
            Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center, IsHitTestVisible = false,
            Foreground = (Brush)FindResource("MutedBrush")
        };
        placeholder.SetResourceReference(TextBlock.TextProperty, "UiSearchSettings");
        searchField.Children.Add(placeholder);
        searchBar.Children.Add(searchField);
        search.TextChanged += (_, _) =>
        {
            clearSearch.Visibility = search.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
            placeholder.Visibility = search.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
            ApplySearch();
        };
        clearSearch.Click += (_, _) => { search.Clear(); search.Focus(); };

        var close = IdentitySetupDialog.ActionButton("Close", () => DialogResult = false, Brushes.Gray);
        close.IsCancel = true;
        var cancel = IdentitySetupDialog.ActionButton("Cancel", ResetNetworkDraft, Brushes.Gray);
        cancel.IsCancel = false;
        var save = IdentitySetupDialog.ActionButton("Save", Save, Brushes.Lime);
        close.SetResourceReference(ContentControl.ContentProperty, "UiClose");
        cancel.SetResourceReference(ContentControl.ContentProperty, "UiCancel");
        save.SetResourceReference(ContentControl.ContentProperty, "UiSave");
        save.IsDefault = true;
        var networkIssue = new TextBlock { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 6) };
        networkIssue.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
        void Validate()
        {
            var issue = NetworkValidationIssue();
            save.IsEnabled = issue is null;
            networkIssue.Visibility = save.Visibility == Visibility.Visible && issue is not null
                ? Visibility.Visible : Visibility.Collapsed;
            if (issue is not null) networkIssue.SetResourceReference(TextBlock.TextProperty, issue);
        }
        foreach (var field in new[] { _server, _port, _turnServer, _turnPort, _certificate })
            field.TextChanged += (_, _) => Validate();
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        actions.Children.Add(close);
        actions.Children.Add(cancel);
        actions.Children.Add(save);
        var footer = new Border
        {
            BorderThickness = new Thickness(0, 1, 0, 0),
            Padding = new Thickness(0, 10, 0, 0), Margin = new Thickness(0, 10, 0, 0),
            Child = IdentitySetupDialog.Form(networkIssue, actions)
        };
        footer.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");

        void SelectNetwork(bool selected)
        {
            overview.Visibility = selected ? Visibility.Collapsed : Visibility.Visible;
            searchBar.Visibility = selected ? Visibility.Collapsed : Visibility.Visible;
            network.Visibility = selected ? Visibility.Visible : Visibility.Collapsed;
            back.Visibility = selected ? Visibility.Visible : Visibility.Collapsed;
            close.Visibility = selected ? Visibility.Collapsed : Visibility.Visible;
            cancel.Visibility = save.Visibility = selected ? Visibility.Visible : Visibility.Collapsed;
            save.IsDefault = selected;
            heading.SetResourceReference(TextBlock.TextProperty, selected ? "UiNetwork" : "UiSettings");
            Validate();
        }
        double overviewOffset = 0;
        FrameworkElement? returnControl = null;
        void OpenNetwork(FrameworkElement source)
        {
            overviewOffset = overview.VerticalOffset;
            returnControl = source;
            SelectNetwork(true);
        }
        networkLink.Click += (_, _) => OpenNetwork(networkLink);
        void OpenAdvanced(FrameworkElement source, FrameworkElement destination)
        {
            expand.IsExpanded = true;
            OpenNetwork(source);
            Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Loaded, new Action(() =>
            {
                if (network.Visibility == Visibility.Visible)
                    destination.BringIntoView(new Rect(0, 0, destination.ActualWidth, Math.Min(destination.ActualHeight, 48)));
            }));
        }
        advancedLink.Click += (_, _) => OpenAdvanced(advancedLink, expand);
        turnLink.Click += (_, _) => OpenAdvanced(turnLink, turnHeading);
        certificateLink.Click += (_, _) => OpenAdvanced(certificateLink, _certificate);
        void BackToOverview(bool focusSearch = false)
        {
            SelectNetwork(false);
            Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Loaded, new Action(() =>
            {
                if (!IsVisible || network.Visibility == Visibility.Visible) return;
                overview.ScrollToVerticalOffset(overviewOffset);
                if (focusSearch)
                {
                    search.Focus();
                    search.SelectAll();
                }
                else if (returnControl is { IsVisible: true } previous) previous.Focus();
            }));
        }
        back.Click += (_, _) => BackToOverview();
        cancel.Click += (_, _) => BackToOverview();
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == System.Windows.Input.Key.Escape && network.Visibility == Visibility.Visible)
            {
                BackToOverview();
                e.Handled = true;
            }
            else if (e.Key == System.Windows.Input.Key.Escape && search.Text.Length != 0)
            {
                search.Clear();
                search.Focus();
                e.Handled = true;
            }
            else if (e.Key == System.Windows.Input.Key.F && System.Windows.Input.Keyboard.Modifiers == System.Windows.Input.ModifierKeys.Control)
            {
                if (network.Visibility == Visibility.Visible) BackToOverview(focusSearch: true);
                else { search.Focus(); search.SelectAll(); }
                e.Handled = true;
            }
        };
        SelectNetwork(false);
        ApplySearch();

        var layout = new DockPanel();
        DockPanel.SetDock(header, Dock.Top); layout.Children.Add(header);
        DockPanel.SetDock(searchBar, Dock.Top); layout.Children.Add(searchBar);
        DockPanel.SetDock(footer, Dock.Bottom); layout.Children.Add(footer);
        var pages = new Grid();
        pages.Children.Add(overview);
        pages.Children.Add(network);
        layout.Children.Add(pages);
        var card = IdentitySetupDialog.DialogCard(layout);
        card.Margin = new Thickness(12);
        card.Padding = new Thickness(16);
        card.SetResourceReference(Border.BackgroundProperty, "MessagingChromeBrush");
        card.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        Content = card;
        Validate();
    }

    private static ScrollViewer CreateScroll(UIElement content) => new()
    {
        Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
        HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled
    };

    internal static Border SettingsPage(string title, UIElement body, UIElement actions)
    {
        var heading = IdentitySetupDialog.Heading("", 20);
        heading.SetResourceReference(TextBlock.TextProperty, title);
        heading.FontWeight = FontWeights.SemiBold;
        heading.Margin = new Thickness(0, 0, 0, 12);
        var footer = new Border
        {
            BorderThickness = new Thickness(0, 1, 0, 0),
            Margin = new Thickness(0, 12, 0, 0),
            Padding = new Thickness(0, 9, 0, 0),
            Child = actions
        };
        footer.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        var layout = new DockPanel();
        DockPanel.SetDock(heading, Dock.Top);
        DockPanel.SetDock(footer, Dock.Bottom);
        layout.Children.Add(heading);
        layout.Children.Add(footer);
        layout.Children.Add(CreateScroll(body));
        var card = IdentitySetupDialog.DialogCard(layout);
        card.Padding = new Thickness(16);
        card.Margin = new Thickness(12);
        card.SetResourceReference(Border.BackgroundProperty, "MessagingChromeBrush");
        card.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        return card;
    }

    private static ComboBox LocalizedChoices<T>(T current, Action<T> change, params (T Value, string Resource)[] choices)
        where T : struct, Enum
    {
        var control = new ComboBox { HorizontalAlignment = HorizontalAlignment.Left, MinWidth = 120 };
        foreach (var (value, resource) in choices)
        {
            var item = new ComboBoxItem { Tag = value };
            item.SetResourceReference(ContentControl.ContentProperty, resource);
            control.Items.Add(item);
            if (EqualityComparer<T>.Default.Equals(value, current)) control.SelectedItem = item;
        }
        control.SelectionChanged += (_, _) =>
        {
            if (control.SelectedItem is ComboBoxItem { Tag: T value }) change(value);
        };
        return control;
    }

    private Button SettingsRow(string titleResource, string glyph)
    {
        var content = new DockPanel();
        var icon = new TextBlock
        {
            Text = glyph, FontFamily = new FontFamily("Segoe MDL2 Assets"),
            Width = 26, Foreground = (Brush)FindResource("AccentTextBrush"),
            VerticalAlignment = VerticalAlignment.Center
        };
        DockPanel.SetDock(icon, Dock.Left);
        content.Children.Add(icon);
        var arrow = new TextBlock { Text = "›", Foreground = (Brush)FindResource("MutedBrush"), VerticalAlignment = VerticalAlignment.Center };
        DockPanel.SetDock(arrow, Dock.Right);
        content.Children.Add(arrow);
        var title = new TextBlock { VerticalAlignment = VerticalAlignment.Center };
        title.SetResourceReference(TextBlock.TextProperty, titleResource);
        content.Children.Add(title);
        var button = new Button
        {
            Content = content, Style = ResourceStyle("SettingsNavigationButtonStyle")
        };
        button.SetResourceReference(System.Windows.Automation.AutomationProperties.NameProperty, titleResource);
        return button;
    }

    private Grid ProfileCard(SecureSettings settings, string uuid)
    {
        var profile = new Grid();
        profile.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(48) });
        profile.ColumnDefinitions.Add(new ColumnDefinition());
        profile.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        profile.Children.Add(IdentitySetupDialog.Avatar(settings.AvatarPath, settings.Nickname ?? uuid, 48, 24));
        var identity = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 12, 0) };
        identity.Children.Add(new TextBlock
        {
            Text = string.IsNullOrWhiteSpace(settings.Nickname) ? uuid[..Math.Min(uuid.Length, 12)] : settings.Nickname,
            FontSize = 14, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis
        });
        identity.Children.Add(new TextBlock
        {
            Text = uuid, Foreground = (Brush)FindResource("MutedBrush"), FontFamily = new FontFamily("Consolas"),
            FontSize = 11, TextTrimming = TextTrimming.CharacterEllipsis, ToolTip = uuid, Margin = new Thickness(0, 4, 0, 0)
        });
        Grid.SetColumn(identity, 1);
        profile.Children.Add(identity);
        var copy = new Button
        {
            Content = Geometry.Parse("M 8,8 L 20,8 L 20,22 L 8,22 Z M 16,8 L 16,2 L 4,2 L 4,16 L 8,16"),
            Style = ResourceStyle("IconButtonStyle"), Width = 32, Height = 32, MinHeight = 32,
            VerticalAlignment = VerticalAlignment.Center
        };
        copy.SetResourceReference(ToolTipProperty, "UiCopyMyUuid");
        copy.SetResourceReference(System.Windows.Automation.AutomationProperties.NameProperty, "UiCopyMyUuid");
        copy.Click += (_, _) => { System.Windows.Clipboard.SetText(uuid); TransientNotice.Show(this, AppStrings.Get("UiMessageCopied")); };
        Grid.SetColumn(copy, 2);
        profile.Children.Add(copy);
        return profile;
    }

    public SecureSettings? Result { get; private set; }
    public bool ResetRequested { get; private set; }
    private string NormalizedPin => _certificate.Text.Replace(":", "", StringComparison.Ordinal).Replace(" ", "", StringComparison.Ordinal).ToLowerInvariant();
    private string? NetworkValidationIssue()
    {
        if (string.IsNullOrWhiteSpace(_server.Text)) return "UiNetworkAddressRequired";
        if (!ValidPort(_port.Text, out _)) return "UiNetworkServerPortRange";
        if (!string.IsNullOrWhiteSpace(_turnServer.Text) && !ValidPort(_turnPort.Text, out _))
            return "UiNetworkTurnPortRange";
        var pin = NormalizedPin;
        if (pin.Length != 0 && !Regex.IsMatch(pin, "\\A[a-f0-9]{64}\\z", RegexOptions.CultureInvariant))
            return "UiNetworkFingerprintLength";
        return null;
    }
    private void Save()
    {
        if (NetworkValidationIssue() is not null) return;
        _ = ValidPort(_port.Text, out var serverPort); var turnPort = ValidPort(_turnPort.Text, out var port) ? port : 3478;
        Result = _original with { ServerAddress = _server.Text.Trim(), ServerPort = serverPort, TurnServer = EmptyToNull(_turnServer.Text), TurnPort = turnPort,
            TurnUsername = EmptyToNull(_turnUser.Text), TurnPassword = string.IsNullOrEmpty(_turnPassword.Password) ? null : _turnPassword.Password,
            TurnUseTls = _turnTls.IsChecked == true, ServerCertificateSha256 = EmptyToNull(NormalizedPin), AllowDevelopmentCertificate = _developmentCertificate.IsChecked == true };
        DialogResult = true;
    }
    private static bool ValidPort(string value, out int port) => int.TryParse(value, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out port) && port is > 0 and <= 65535;
    private static string? EmptyToNull(string value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}

public sealed class FriendRequestsDialog : DialogWindow
{
    private readonly HashSet<string> _pending = new(StringComparer.Ordinal);
    private readonly MainViewModel _viewModel;
    private readonly StackPanel _list = new();
    private readonly TextBlock _empty = new() { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, Margin = new Thickness(14) };
    private readonly TextBlock _error = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(14, 6, 14, 6),
        Visibility = Visibility.Collapsed };
    private bool _refreshPending;
    private bool _closed;

    public FriendRequestsDialog(MainViewModel viewModel)
    {
        _viewModel = viewModel;
        SetResourceReference(TitleProperty, "UiFriendRequests");
        SetResourceReference(BackgroundProperty, "PanelBrush");
        Width = 440;
        Height = Math.Min(440, SystemParameters.WorkArea.Height * .76);
        MinWidth = 350;
        MinHeight = Math.Min(270, Height);
        ResizeMode = ResizeMode.CanResize;

        var root = new DockPanel();
        var header = new DockPanel { Margin = new Thickness(12, 6, 12, 6), MinHeight = 40 };
        var close = ContactsDialog.IconButton("CloseIcon", "UiClose", Close);
        DockPanel.SetDock(close, Dock.Right);
        header.Children.Add(close);
        var heading = new TextBlock { FontSize = 17, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center,
            TextTrimming = TextTrimming.CharacterEllipsis };
        heading.SetResourceReference(TextBlock.TextProperty, "UiFriendRequests");
        header.Children.Add(heading);
        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);
        var separator = new Border { Height = 1 };
        separator.SetResourceReference(Border.BackgroundProperty, "BorderBrush");
        DockPanel.SetDock(separator, Dock.Top);
        root.Children.Add(separator);
        _error.SetResourceReference(TextBlock.TextProperty, "UiContactActionFailed");
        _error.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
        DockPanel.SetDock(_error, Dock.Top);
        root.Children.Add(_error);

        var body = new Grid();
        body.Children.Add(new ScrollViewer { Content = _list, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        _empty.SetResourceReference(TextBlock.TextProperty, "UiContactNoRequests");
        _empty.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        body.Children.Add(_empty);
        root.Children.Add(body);
        Content = root;

        _viewModel.FriendRequests.CollectionChanged += RequestsChanged;
        Closed += (_, _) =>
        {
            _closed = true;
            _viewModel.FriendRequests.CollectionChanged -= RequestsChanged;
        };
        RefreshRequests();
    }

    private void RequestsChanged(object? sender, System.Collections.Specialized.NotifyCollectionChangedEventArgs args)
    {
        if (_refreshPending || _closed) return;
        _refreshPending = true;
        Dispatcher.BeginInvoke(DispatcherPriority.DataBind, new Action(() =>
        {
            _refreshPending = false;
            if (!_closed) RefreshRequests();
        }));
    }

    private void RefreshRequests()
    {
        _list.Children.Clear();
        _empty.Visibility = _viewModel.FriendRequests.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        foreach (var request in _viewModel.FriendRequests)
        {
            var row = new DockPanel { Margin = new Thickness(14, 7, 10, 7), MinHeight = 44 };
            var avatar = IdentitySetupDialog.Avatar(request.AvatarPath, request.Nickname, 44, 22);
            avatar.Margin = new Thickness(0, 0, 10, 0);
            DockPanel.SetDock(avatar, Dock.Left);
            row.Children.Add(avatar);

            var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            var accept = ContactsDialog.IconButton("CheckIcon", "UiContactAccept", color: "AccentTextBrush");
            var decline = ContactsDialog.IconButton("CloseIcon", "UiContactDecline", color: "DangerBrush");
            actions.IsEnabled = !_pending.Contains(request.SenderUuid);
            accept.Click += async (_, _) => await DecideAsync(request, actions, accept: true);
            decline.Click += async (_, _) => await DecideAsync(request, actions, accept: false);
            actions.Children.Add(accept);
            actions.Children.Add(decline);
            DockPanel.SetDock(actions, Dock.Right);
            row.Children.Add(actions);

            var labels = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 6, 0) };
            labels.Children.Add(new TextBlock { Text = request.Nickname, ToolTip = request.Nickname, FontWeight = FontWeights.SemiBold,
                TextTrimming = TextTrimming.CharacterEllipsis });
            var id = new TextBlock { Text = request.SenderUuid, ToolTip = request.SenderUuid,
                FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis };
            id.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
            labels.Children.Add(id);
            row.Children.Add(labels);
            _list.Children.Add(row);

            var divider = new Border { Height = 1, Margin = new Thickness(68, 0, 0, 0) };
            divider.SetResourceReference(Border.BackgroundProperty, "BorderBrush");
            _list.Children.Add(divider);
        }
    }

    private async Task DecideAsync(FriendRequest request, StackPanel actions, bool accept)
    {
        if (!_pending.Add(request.SenderUuid)) return;
        actions.IsEnabled = false;
        _error.Visibility = Visibility.Collapsed;
        try
        {
            if (accept) await _viewModel.AcceptFriendRequestAsync(request);
            else await _viewModel.DeclineFriendRequestAsync(request);
        }
        catch (Exception exception) when (exception is InvalidOperationException or OperationCanceledException)
        {
            if (!_closed) _error.Visibility = Visibility.Visible;
        }
        finally
        {
            _pending.Remove(request.SenderUuid);
            if (!_closed) RefreshRequests();
        }
    }
}

public sealed class AvatarCropDialog : DialogWindow
{
    private const double CropSize = 300;
    private System.Windows.Media.Imaging.BitmapSource? _source;
    private readonly ScaleTransform _scale = new();
    private readonly TranslateTransform _offset = new();
    private double _baseScale;
    public byte[]? Result { get; private set; }

    public AvatarCropDialog(string path)
    {
        Title = AppStrings.Get("UiCropAvatar");
        Width = 420;
        Height = Math.Min(495, SystemParameters.WorkArea.Height * .88);
        var image = new Image { Stretch = Stretch.Fill, RenderTransformOrigin = new Point(0.5, 0.5),
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        var transforms = new TransformGroup(); transforms.Children.Add(_scale); transforms.Children.Add(_offset); image.RenderTransform = transforms;
        var viewport = new Grid { Width = CropSize, Height = CropSize, Background = Brushes.Black, IsEnabled = false,
            Clip = new EllipseGeometry(new Point(CropSize / 2, CropSize / 2), CropSize / 2, CropSize / 2) };
        viewport.Children.Add(image);
        var status = new TextBlock
        {
            Text = AppStrings.Get("UiLoading"), Foreground = Brushes.White, TextWrapping = TextWrapping.Wrap,
            TextAlignment = TextAlignment.Center, Width = CropSize * .72, VerticalAlignment = VerticalAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Center
        };
        viewport.Children.Add(status);
        Point? start = null; Point origin = default;
        viewport.MouseLeftButtonDown += (_, e) => { start = e.GetPosition(viewport); origin = new Point(_offset.X, _offset.Y); viewport.CaptureMouse(); };
        viewport.MouseMove += (_, e) => { if (start is not { } point) return; var delta = e.GetPosition(viewport) - point; _offset.X = origin.X + delta.X; _offset.Y = origin.Y + delta.Y; Clamp(); };
        viewport.MouseLeftButtonUp += (_, _) => { start = null; viewport.ReleaseMouseCapture(); };
        viewport.LostMouseCapture += (_, _) => start = null;
        var zoom = new Slider { Minimum = 1, Maximum = 5, Value = 1, Margin = new Thickness(0, 16, 0, 0), IsEnabled = false };
        zoom.ValueChanged += (_, _) => { _scale.ScaleX = _scale.ScaleY = zoom.Value; Clamp(); };
        var cancel = IdentitySetupDialog.ActionButton(AppStrings.Get("UiCancel"), () => DialogResult = false, Brushes.Gray);
        cancel.IsCancel = true;
        var confirm = IdentitySetupDialog.ActionButton(AppStrings.Get("UiUseAvatar"), Save, Brushes.Lime);
        confirm.IsDefault = true;
        confirm.IsEnabled = false;
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right,
            Margin = new Thickness(0, 12, 0, 0) };
        actions.Children.Add(cancel);
        actions.Children.Add(confirm);
        var root = new DockPanel();
        DockPanel.SetDock(actions, Dock.Bottom);
        root.Children.Add(actions);
        root.Children.Add(new ScrollViewer { Content = IdentitySetupDialog.Form(viewport, zoom),
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        Content = IdentitySetupDialog.DialogCard(root);

        var closed = false;
        Closed += (_, _) => closed = true;
        Loaded += async (_, _) =>
        {
            if (closed || _source is not null) return;
            try
            {
                var source = await Task.Run(() =>
                {
                    using var input = File.OpenRead(path);
                    if (input.Length is < 1 or > 16 * 1024 * 1024) throw new InvalidDataException();
                    return ImageDecoder.Load(input, 2048);
                });
                if (closed) return;
                _source = source;
                _baseScale = Math.Max(CropSize / source.PixelWidth, CropSize / source.PixelHeight);
                image.Source = source;
                image.Width = source.PixelWidth * _baseScale;
                image.Height = source.PixelHeight * _baseScale;
                status.Visibility = Visibility.Collapsed;
                viewport.IsEnabled = zoom.IsEnabled = confirm.IsEnabled = true;
            }
            catch (Exception exception) when (exception is IOException or ArgumentException or InvalidOperationException or NotSupportedException)
            {
                if (!closed) status.Text = AppStrings.Get("UiProfileImageUnavailable");
            }
        };
    }

    private void Clamp()
    {
        if (_source is not { } source) return;
        var width = Math.Max(0, (source.PixelWidth * _baseScale * _scale.ScaleX - CropSize) / 2);
        var height = Math.Max(0, (source.PixelHeight * _baseScale * _scale.ScaleY - CropSize) / 2);
        _offset.X = Math.Clamp(_offset.X, -width, width); _offset.Y = Math.Clamp(_offset.Y, -height, height);
    }
    private void Save()
    {
        if (_source is not { } source) return;
        var factor = _baseScale * _scale.ScaleX;
        var side = Math.Min(Math.Min(source.PixelWidth, source.PixelHeight), Math.Max(1, (int)Math.Round(CropSize / factor)));
        var x = Math.Clamp((int)Math.Round((source.PixelWidth - side) / 2d - _offset.X / factor), 0, source.PixelWidth - side);
        var y = Math.Clamp((int)Math.Round((source.PixelHeight - side) / 2d - _offset.Y / factor), 0, source.PixelHeight - side);
        var crop = new System.Windows.Media.Imaging.CroppedBitmap(source, new Int32Rect(x, y, side, side));
        var bitmap = new System.Windows.Media.Imaging.TransformedBitmap(crop, new ScaleTransform(256d / side, 256d / side));
        var encoder = new System.Windows.Media.Imaging.JpegBitmapEncoder { QualityLevel = 86 }; encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(bitmap));
        using var output = new MemoryStream();
        try
        {
            encoder.Save(output);
            if (output.Length > 512 * 1024) throw new InvalidDataException();
            Result = output.ToArray();
            DialogResult = true;
        }
        catch (Exception exception) when (exception is IOException or InvalidOperationException or NotSupportedException)
        {
            TransientNotice.Show(this, AppStrings.Get("UiProfileCropFailed"));
        }
        finally
        {
            if (output.TryGetBuffer(out var buffer))
                System.Security.Cryptography.CryptographicOperations.ZeroMemory(buffer.AsSpan());
        }
    }
}


internal sealed class MessageNotificationsDialog : DialogWindow
{
    internal MessageNotificationsDialog(AppServices services)
    {
        var initial = services.Messaging.NotificationSettings;
        SetResourceReference(TitleProperty, "UiNotificationSettings");
        Width = 440;
        MaxHeight = Math.Min(500, SystemParameters.WorkArea.Height * .82);
        SizeToContent = SizeToContent.Height;

        var enabled = new CheckBox { IsChecked = initial.Enabled, Margin = new Thickness(0, 0, 0, 10) };
        enabled.SetResourceReference(ContentControl.ContentProperty, "UiMessageAlerts");
        var sound = new CheckBox { IsChecked = initial.PlaySound, IsEnabled = initial.Enabled };
        sound.SetResourceReference(ContentControl.ContentProperty, "UiMessageAlertSound");
        var error = new TextBlock { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 0) };
        error.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
        var systemError = new TextBlock { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
        systemError.SetResourceReference(TextBlock.TextProperty, "UiNotificationSystemOpenFailed");
        systemError.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
        var systemSettings = IdentitySetupDialog.ActionButton(AppStrings.Get("UiOpenNotificationSystemSettings"), () =>
        {
            systemError.Visibility = Visibility.Collapsed;
            try
            {
                using var process = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("ms-settings:notifications")
                {
                    UseShellExecute = true
                });
            }
            catch (Exception failure) when (failure is System.ComponentModel.Win32Exception or InvalidOperationException)
            {
                systemError.Visibility = Visibility.Visible;
            }
        }, Brushes.Gray);
        systemSettings.HorizontalAlignment = HorizontalAlignment.Left;
        systemSettings.Margin = new Thickness(0, 0, 0, 6);
        var cancel = IdentitySetupDialog.ActionButton(AppStrings.Get("UiCancel"), Close, Brushes.Gray);
        cancel.IsCancel = true;
        var save = new Button
        {
            Content = AppStrings.Get("UiSave"), Style = ResourceStyle("PrimaryButtonStyle"), IsDefault = true,
            Margin = new Thickness(8, 0, 0, 0)
        };
        var footer = new StackPanel
        {
            Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right
        };
        footer.Children.Add(cancel);
        footer.Children.Add(save);
        Content = SettingsDialog.SettingsPage("UiNotificationSettings",
            IdentitySetupDialog.Form(enabled, sound, new Separator { Margin = new Thickness(0, 10, 0, 8) },
                systemSettings, systemError, error), footer);

        var closed = false;
        Closed += (_, _) => closed = true;
        enabled.Checked += (_, _) => sound.IsEnabled = true;
        enabled.Unchecked += (_, _) => sound.IsEnabled = false;
        save.Click += async (_, _) =>
        {
            var settings = new MessageAlertSettings(enabled.IsChecked == true, sound.IsChecked == true);
            enabled.IsEnabled = sound.IsEnabled = cancel.IsEnabled = save.IsEnabled = systemSettings.IsEnabled = false;
            error.Text = "";
            try
            {
                if (!await services.Messaging.SetNotificationSettingsAsync(settings))
                {
                    if (!closed) error.Text = AppStrings.Get("UiNotificationSaveError");
                    return;
                }
                if (!settings.Enabled || (initial.PlaySound && !settings.PlaySound))
                    ((App)System.Windows.Application.Current).DismissMessageNotifications();
                if (!closed) DialogResult = true;
            }
            catch (Exception failure) when (failure is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException or OperationCanceledException)
            {
                if (!closed) error.Text = AppStrings.Get("UiNotificationSaveError");
            }
            finally
            {
                if (!closed)
                {
                    enabled.IsEnabled = cancel.IsEnabled = save.IsEnabled = systemSettings.IsEnabled = true;
                    sound.IsEnabled = enabled.IsChecked == true;
                }
            }
        };
    }
}

public sealed class ReadReceiptPrivacyDialog : DialogWindow
{
    private readonly AppServices _services;
    private readonly CheckBox _enabled=new(){Content=AppStrings.Get("UiReadReceipts"),Margin=new Thickness(0,12,0,12),IsEnabled=false};
    private readonly CheckBox _online=new(){Content=AppStrings.Get("UiShareOnline"),Margin=new Thickness(0,4,0,8),IsEnabled=false};
    private readonly CheckBox _typing=new(){Content=AppStrings.Get("UiShareTyping"),Margin=new Thickness(0,4,0,12),IsEnabled=false};
    private readonly CheckBox _autoDownload = new() { Content = AppStrings.Get("UiAutomaticDownloads"), Margin = new Thickness(0, 4, 0, 12), IsEnabled = false };
    private readonly TextBlock _error=new(){TextWrapping=TextWrapping.Wrap,Margin=new Thickness(0,10,0,10)};
    private readonly Button _save;
    private readonly Button _retry;
    private bool _busy;
    private bool _closed;
    public ReadReceiptPrivacyDialog(AppServices services)
    {
        _services = services;
        SetResourceReference(TitleProperty, "UiPrivacy");
        Width = 440;
        MaxHeight = Math.Min(540, SystemParameters.WorkArea.Height * .82);
        SizeToContent = SizeToContent.Height;
        _error.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
        var activityNote = new TextBlock { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 10) };
        activityNote.SetResourceReference(TextBlock.TextProperty, "UiActivityPrivacyNote");
        activityNote.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        var readNote = new TextBlock { TextWrapping = TextWrapping.Wrap };
        readNote.SetResourceReference(TextBlock.TextProperty, "UiReadPrivacyNote");
        readNote.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        var actions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        _retry = IdentitySetupDialog.ActionButton(AppStrings.Get("UiRetry"), () => _ = LoadAsync(), Brushes.Gray);
        _retry.Visibility = Visibility.Collapsed;
        actions.Children.Add(_retry);
        var cancel = IdentitySetupDialog.ActionButton(AppStrings.Get("UiCancel"), Close, Brushes.Gray);
        cancel.IsCancel = true;
        actions.Children.Add(cancel);
        _save = IdentitySetupDialog.ActionButton(AppStrings.Get("UiSave"), () => _ = SaveAsync(), Brushes.Lime);
        _save.IsEnabled = false;
        _save.IsDefault = true;
        actions.Children.Add(_save);
        var body = IdentitySetupDialog.Form(_enabled, _online, _typing, _autoDownload, activityNote, readNote, _error);
        Content = SettingsDialog.SettingsPage("UiPrivacy", body, actions);
        Closing+=(_,e)=>{if(_busy)e.Cancel=true;};Closed+=(_,_)=>_closed=true;
        Loaded+=async(_,_)=>await LoadAsync();
    }
    private async Task LoadAsync()
    {
        if(_busy || _closed)return;_busy=true;_retry.IsEnabled=false;
        try {var enabled=await _services.Messaging.ReadReceiptsEnabledAsync();var activity=await _services.Messaging.ActivityPrivacyAsync();var autoDownload=await _services.Messaging.AutomaticAttachmentDownloadsAsync();if(_closed)return;_autoDownload.IsChecked=autoDownload;_autoDownload.IsEnabled=true;_online.IsChecked=activity.Online;_typing.IsChecked=activity.Typing;_online.IsEnabled=true;_typing.IsEnabled=true;_enabled.IsChecked=enabled;_enabled.IsEnabled=true;_save.IsEnabled=true;_error.Text="";_retry.Visibility=Visibility.Collapsed;}
        catch(Exception error) when(error is InvalidOperationException or OperationCanceledException){if(!_closed){_error.Text=AppStrings.Get("UiPrivacyLoadError");_retry.Visibility=Visibility.Visible;}}
        finally {_busy=false;_retry.IsEnabled=true;}
    }
    private async Task SaveAsync()
    {
        if(_busy || _closed || !_enabled.IsEnabled)return;
        _busy=true;_autoDownload.IsEnabled=false;_enabled.IsEnabled=false;_online.IsEnabled=false;_typing.IsEnabled=false;_save.IsEnabled=false;var saved=false;
        try {saved=await _services.Messaging.SetPrivacyAsync(_enabled.IsChecked==true,_online.IsChecked==true,_typing.IsChecked==true,autoDownload:_autoDownload.IsChecked==true);if(!saved)_error.Text=AppStrings.Get("UiPrivacySaveError");}
        catch(Exception error) when(error is InvalidOperationException or OperationCanceledException){_error.Text=AppStrings.Get("UiPrivacySaveError");}
        finally {_busy=false;_autoDownload.IsEnabled=true;_enabled.IsEnabled=true;_online.IsEnabled=true;_typing.IsEnabled=true;_save.IsEnabled=true;}
        if(saved && !_closed)Close();
    }
}

internal sealed class MessageExpirationDialog : DialogWindow
{
    private static readonly (long Duration, string LabelKey)[] Choices =
    [
        (0, "UiDurationOff"), (60_000, "UiDurationMinute"), (3_600_000, "UiDurationHour"),
        (86_400_000, "UiDurationDay"), (604_800_000, "UiDurationWeek"), (2_592_000_000, "UiDuration30Days")
    ];
    private static readonly (long Duration, string LabelKey)[] LocalChoices =
    [
        (0, "UiDurationForever"), (86_400_000, "UiDurationDay"), (604_800_000, "UiDurationWeek"),
        (2_592_000_000, "UiDuration30Days"), (7_776_000_000, "UiDuration90Days")
    ];

    private readonly AppServices _services;
    private readonly string _peer;
    private readonly System.Windows.Controls.ComboBox _duration = new() { IsEnabled = false };
    private readonly System.Windows.Controls.ComboBox _localDuration = new() { IsEnabled = false };
    private readonly TextBlock _error = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Button _save;
    private bool _busy;
    private MessageRetentionSettings? _original;

    internal MessageExpirationDialog(AppServices services, string peer)
    {
        _services = services;
        _peer = peer;
        Title = AppStrings.Get("UiMessageRetention");
        Width = 420;
        SizeToContent = SizeToContent.Height;
        var panel = new StackPanel { Margin = new Thickness(20) };
        panel.Children.Add(IdentitySetupDialog.Heading(Title));
        panel.Children.Add(new TextBlock
        {
            Text = AppStrings.Get("UiAutoDeleteBoth"),
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 8, 0, 12)
        });
        _duration.ItemsSource = Choices.Select(choice => AppStrings.Get(choice.LabelKey)).ToArray();
        System.Windows.Automation.AutomationProperties.SetName(_duration, AppStrings.Get("UiAutoDeleteBoth"));
        panel.Children.Add(_duration);
        panel.Children.Add(new TextBlock
        {
            Text = AppStrings.Get("UiNewOutgoingOnly"),
            Margin = new Thickness(0, 4, 0, 0)
        });
        panel.Children.Add(new TextBlock
        {
            Text = AppStrings.Get("UiLocalRetention"),
            Margin = new Thickness(0, 12, 0, 6)
        });
        _localDuration.ItemsSource = LocalChoices.Select(choice => AppStrings.Get(choice.LabelKey)).ToArray();
        System.Windows.Automation.AutomationProperties.SetName(_localDuration, AppStrings.Get("UiLocalRetention"));
        panel.Children.Add(_localDuration);
        panel.Children.Add(_error);
        var actions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        actions.Children.Add(IdentitySetupDialog.ActionButton(AppStrings.Get("UiCancel"), Close, Brushes.Gray));
        _save = IdentitySetupDialog.ActionButton(AppStrings.Get("UiSave"), () => _ = SaveAsync(), Brushes.Gray);
        _save.IsEnabled = false;
        _save.IsDefault = true;
        actions.Children.Add(_save);
        panel.Children.Add(actions);
        Content = panel;
        Closing += (_, e) => e.Cancel = _busy;
        Loaded += async (_, _) => await LoadAsync();
    }

    private async Task LoadAsync()
    {
        _busy = true;
        try
        {
            var settings = await _services.Messaging.MessageRetentionAsync(_peer);
            _original = settings;
            _duration.SelectedIndex = Array.FindIndex(Choices, choice => choice.Duration == settings.Outgoing);
            _localDuration.SelectedIndex = Array.FindIndex(LocalChoices, choice => choice.Duration == settings.Local);
            _duration.IsEnabled = _localDuration.IsEnabled = _save.IsEnabled =
                _duration.SelectedIndex >= 0 && _localDuration.SelectedIndex >= 0;
        }
        catch (Exception error) when (error is InvalidOperationException or OperationCanceledException)
        {
            _error.Text = AppStrings.Get("UiRetentionLoadError");
        }
        finally { _busy = false; }
    }

    private async Task SaveAsync()
    {
        if (_busy || _original is null || _duration.SelectedIndex < 0 || _localDuration.SelectedIndex < 0) return;
        var settings = new MessageRetentionSettings(Choices[_duration.SelectedIndex].Duration,
            LocalChoices[_localDuration.SelectedIndex].Duration);
        if (settings.ShortensLocalHistory(_original))
        {
            var confirm = new ConfirmationDialog(AppStrings.Get("UiShortenHistoryWarning"),
                AppStrings.Get("UiShortenHistory")) { Owner = this };
            if (confirm.ShowDialog() != true) return;
        }
        _busy = true;
        _duration.IsEnabled = _localDuration.IsEnabled = _save.IsEnabled = false;
        var saved = false;
        try { saved = await _services.Messaging.SetMessageRetentionAsync(_peer, settings); }
        catch (Exception error) when (error is InvalidOperationException or OperationCanceledException) { }
        finally
        {
            _busy = false;
            _duration.IsEnabled = _localDuration.IsEnabled = _save.IsEnabled = true;
        }
        if (saved) DialogResult = true;
        else _error.Text = AppStrings.Get("UiRetentionSaveError");
    }
}

internal sealed class AttachmentCacheDialog : DialogWindow
{
    private sealed record CacheLimit(long Bytes, string Label);
    private readonly AppServices _services;
    private readonly TextBlock _size = new() { Text = AppStrings.Get("UiCalculating"), TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 0) };
    private readonly TextBlock _budget = new() { Visibility = Visibility.Collapsed, TextWrapping = TextWrapping.Wrap };
    private readonly ProgressBar _budgetMeter = new() { Height = 6, Maximum = 1, Visibility = Visibility.Collapsed };
    private readonly DispatcherTimer _refresh = new() { Interval = TimeSpan.FromSeconds(2) };
    private readonly Button _clear;
    private readonly Button _retry;
    private readonly ComboBox _limit = new() { DisplayMemberPath = nameof(CacheLimit.Label), IsEnabled = false };
    private readonly StackPanel _categoryRows = new() { Visibility = Visibility.Collapsed };
    private readonly Dictionary<AttachmentCacheCategory, CheckBox> _categories = new();
    private IReadOnlyDictionary<AttachmentCacheCategory, long> _bytesByCategory = new Dictionary<AttachmentCacheCategory, long>();
    private bool _loading, _confirming, _clearing, _savingLimit, _closed;
    private long _storedLimit;
    internal bool Cleared { get; private set; }

    internal AttachmentCacheDialog(AppServices services)
    {
        _services = services;
        SetResourceReference(TitleProperty, "UiStorageUsage");
        Width = 440;
        MaxHeight = Math.Min(560, SystemParameters.WorkArea.Height * .82);
        SizeToContent = SizeToContent.Height;
        _clear = IdentitySetupDialog.ActionButton(AppStrings.Get("UiCacheClear"), () => _ = ClearAsync(), Brushes.Gray);
        _clear.IsEnabled = false;
        _retry = IdentitySetupDialog.ActionButton(AppStrings.Get("UiRetry"), () => _ = LoadAsync(), Brushes.Gray);
        _retry.Visibility = Visibility.Collapsed;
        var limitTitle = AppStrings.Get("UiCacheAutoLimit");
        System.Windows.Automation.AutomationProperties.SetName(_limit, limitTitle);
        _limit.ItemsSource = EncryptedObjectStore.AutomaticCacheLimits.Select(bytes => new CacheLimit(bytes, LimitLabel(bytes))).ToArray();
        _limit.SelectionChanged += (_, _) =>
        {
            if (!_loading && !_savingLimit && _limit.SelectedItem is CacheLimit selected && selected.Bytes != _storedLimit)
                _ = SaveLimitAsync(selected.Bytes);
        };
        foreach (var category in Enum.GetValues<AttachmentCacheCategory>())
        {
            var check = new CheckBox { IsChecked = true, Visibility = Visibility.Collapsed, Margin = new Thickness(0, 4, 0, 4) };
            check.Checked += (_, _) => UpdateClearButton();
            check.Unchecked += (_, _) => UpdateClearButton();
            _categoryRows.Children.Add(check);
            _categories.Add(category, check);
        }
        var actions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        actions.Children.Add(_retry);
        actions.Children.Add(_clear);
        actions.Children.Add(IdentitySetupDialog.ActionButton(AppStrings.Get("UiClose"), Close, Brushes.Gray));
        var limitNote = new TextBlock { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 8) };
        limitNote.SetResourceReference(TextBlock.TextProperty, "UiCacheLimitNote");
        limitNote.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        var usageBody = IdentitySetupDialog.Form(_size, _categoryRows,
            new TextBlock { Text = limitTitle, Margin = new Thickness(0, 10, 0, 6) }, _limit,
            limitNote, _budgetMeter, _budget, _status);
        Content = SettingsDialog.SettingsPage("UiStorageUsage", usageBody, actions);
        _refresh.Tick += async (_, _) =>
        {
            if (_loading || _confirming || _clearing || _savingLimit) return;
            _refresh.Stop();
            await LoadAsync();
        };
        Loaded += async (_, _) => await LoadAsync();
        Closing += (_, e) => e.Cancel = _clearing;
        _services.Messaging.AttachmentProgressChanged += CacheTransferChanged;
        Closed += (_, _) =>
        {
            _closed = true;
            _refresh.Stop();
            _services.Messaging.AttachmentProgressChanged -= CacheTransferChanged;
        };
    }

    private void CacheTransferChanged(object? sender, AttachmentProgress progress)
    {
        if (progress.Phase == AttachmentPhase.Uploading ||
            (progress.Phase == AttachmentPhase.Downloading && progress.Bytes != 0) ||
            Dispatcher.HasShutdownStarted) return;
        _ = Dispatcher.BeginInvoke(() =>
        {
            if (!_closed && !_refresh.IsEnabled) _refresh.Start();
        });
    }

    private static string Size(long bytes) => bytes switch
    {
        < 1_000_000 => string.Format(CultureInfo.CurrentCulture, "{0:N1} KB", bytes / 1_000d),
        < 1_000_000_000 => string.Format(CultureInfo.CurrentCulture, "{0:N1} MB", bytes / 1_000_000d),
        _ => string.Format(CultureInfo.CurrentCulture, "{0:N1} GB", bytes / 1_000_000_000d)
    };

    private static string CategoryLabel(AttachmentCacheCategory category) => AppStrings.Get(category switch
    {
        AttachmentCacheCategory.Photos => "UiSharedMediaPhotos",
        AttachmentCacheCategory.Videos => "UiSharedMediaVideos",
        AttachmentCacheCategory.Audio => "UiAudio",
        AttachmentCacheCategory.LinkPreviews => "UiCacheLinkPreviews",
        _ => "UiFiles"
    });

    private bool IsSelected(AttachmentCacheCategory category) => _categories[category].IsChecked == true;

    private void UpdateClearButton() => _clear.IsEnabled =
        !_loading && !_confirming && !_clearing && !_savingLimit && _bytesByCategory.Keys.Any(IsSelected);

    private static string LimitLabel(long bytes) => bytes switch
    {
        0 => AppStrings.Get("UiCacheNoLimit"),
        < 1_000_000_000 => string.Format(CultureInfo.CurrentCulture, "{0:N0} MB", bytes / 1_000_000d),
        _ => string.Format(CultureInfo.CurrentCulture, "{0:N0} GB", bytes / 1_000_000_000d)
    };

    private async Task LoadAsync()
    {
        if (_closed || _loading || _confirming || _clearing || _savingLimit) return;
        _loading = true;
        _retry.IsEnabled = false;
        UpdateClearButton();
        try
        {
            var usage = await _services.Messaging.AttachmentCacheUsageAsync();
            if (_closed) return;
            _size.Text = AppStrings.Format("UiCacheReceivedSize", Size(usage.Bytes));
            _bytesByCategory = usage.BytesByCategory;
            _categoryRows.Visibility = _bytesByCategory.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
            foreach (var (category, check) in _categories)
            {
                var available = _bytesByCategory.TryGetValue(category, out var bytes);
                check.Visibility = available ? Visibility.Visible : Visibility.Collapsed;
                if (available) check.Content = $"{CategoryLabel(category)}  ·  {Size(bytes)}";
            }
            _storedLimit = usage.AutomaticLimitBytes;
            _limit.SelectedItem = _limit.Items.Cast<CacheLimit>().FirstOrDefault(item => item.Bytes == _storedLimit);
            _limit.IsEnabled = true;
            _budget.Visibility = _budgetMeter.Visibility = _storedLimit > 0 ? Visibility.Visible : Visibility.Collapsed;
            if (_storedLimit > 0)
            {
                _budget.Text = AppStrings.Format("UiCacheAutoRemaining", Size(usage.AutomaticBudgetRemainingBytes));
                _budgetMeter.Value = 1 - (double)usage.AutomaticBudgetRemainingBytes / _storedLimit;
                System.Windows.Automation.AutomationProperties.SetName(_budgetMeter, _budget.Text);
            }
            _status.Text = usage.PendingBytes > 0 ? AppStrings.Format("UiCacheRemoving", Size(usage.PendingBytes)) : "";
            _retry.Visibility = Visibility.Collapsed;
            if (usage.PendingBytes > 0) _refresh.Start();
        }
        catch (Exception error) when (error is InvalidOperationException or OperationCanceledException or Microsoft.Data.Sqlite.SqliteException or IOException)
        {
            if (!_closed)
            {
                _status.Text = AppStrings.Get("UiCacheLoadError");
                _retry.Visibility = Visibility.Visible;
            }
            _refresh.Stop();
        }
        finally
        {
            _loading = false;
            if (!_closed)
            {
                _retry.IsEnabled = true;
                UpdateClearButton();
            }
        }
    }

    private async Task SaveLimitAsync(long bytes)
    {
        _savingLimit = true;
        UpdateClearButton();
        _limit.IsEnabled = false;
        var saved = false;
        try { saved = await _services.Messaging.SetAutomaticCacheLimitAsync(bytes); }
        catch (Exception error) when (error is InvalidOperationException or OperationCanceledException) { }
        finally
        {
            if (!_closed)
            {
                if (saved) _storedLimit = bytes;
                else
                {
                    _limit.SelectedItem = _limit.Items.Cast<CacheLimit>().FirstOrDefault(item => item.Bytes == _storedLimit);
                    _status.Text = AppStrings.Get("UiCacheLimitError");
                }
                _limit.IsEnabled = true;
            }
            _savingLimit = false;
            if (!_closed) UpdateClearButton();
        }
        if (saved && !_closed) await LoadAsync();
    }

    private async Task ClearAsync()
    {
        if (_closed || _loading || _confirming || _clearing || _savingLimit) return;
        var categories = _bytesByCategory.Keys.Where(IsSelected).ToArray();
        if (categories.Length == 0) return;
        var size = Size(categories.Sum(category => _bytesByCategory[category]));
        var confirm = new ConfirmationDialog(
            AppStrings.Format("UiCacheClearSelectedSize", size) + "\n" + AppStrings.Get("UiCacheClearWarning"),
            AppStrings.Get("UiCacheClearTitle")) { Owner = this };
        _confirming = true;
        UpdateClearButton();
        bool confirmed;
        try { confirmed = confirm.ShowDialog() == true; }
        finally { _confirming = false; UpdateClearButton(); }
        if (!confirmed || _closed) return;
        _clearing = true;
        UpdateClearButton();
        _categoryRows.IsEnabled = false;
        _limit.IsEnabled = false;
        var saved = false;
        try { saved = await _services.Messaging.ClearAttachmentCacheAsync(categories); }
        catch (Exception error) when (error is InvalidOperationException or OperationCanceledException) { }
        finally
        {
            _clearing = false;
            _categoryRows.IsEnabled = true;
            _limit.IsEnabled = true;
            UpdateClearButton();
        }
        if (saved)
        {
            Cleared = true;
            await LoadAsync();
        }
        else
        {
            _status.Text = AppStrings.Get("UiCacheClearError");
        }
    }
}
