using Microsoft.Win32;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;
using System.Text.RegularExpressions;
using MsgAT.Windows.Core.Models;
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
    private readonly TextBox _avatar = Field();
    public byte[]? AvatarData { get; private set; }

    public IdentitySetupDialog()
    {
        Title = "Setup Identity";
        Width = 400;
        SizeToContent = SizeToContent.Height;
        var pick = new Button { Width = 100, Height = 100, Content = "Tap to Pick Avatar", Style = ResourceStyle("ButtonStyle"), Background = Brushes.DarkGray, FontSize = 12 };
        var confirm = ActionButton("Confirm", Save, Brushes.Lime);
        confirm.IsEnabled = false;
        void Validate() => confirm.IsEnabled = !string.IsNullOrWhiteSpace(_nickname.Text) && File.Exists(AvatarPath);
        _nickname.TextChanged += (_, _) => Validate();
        pick.Click += (_, _) =>
        {
            var picker = new OpenFileDialog { Filter = "Images|*.png;*.jpg;*.jpeg;*.bmp;*.webp" };
            if (picker.ShowDialog(this) != true) return;
            try
            {
                var crop = new AvatarCropDialog(picker.FileName) { Owner = this };
                if (crop.ShowDialog() != true || crop.Result is null) return;
                _avatar.Text = picker.FileName; AvatarData = crop.Result;
                using var bytes = new MemoryStream(AvatarData);
                var image = new System.Windows.Media.Imaging.BitmapImage(); image.BeginInit(); image.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad; image.StreamSource = bytes; image.EndInit(); image.Freeze();
                pick.Content = new Image { Source = image, Width = 100, Height = 100, Stretch = Stretch.UniformToFill };
                Validate();
            }
            catch (Exception error) when (error is IOException or NotSupportedException or InvalidOperationException) { TransientNotice.Show(this, "Failed to load image"); }
        };
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 24, 0, 0) };
        var cancel = ActionButton("Cancel", () => {}, Brushes.Gray); cancel.IsCancel = false; actions.Children.Add(cancel); actions.Children.Add(confirm);
        Content = DialogCard(Form(Heading("Setup Identity", 16), pick, Row("Nickname", _nickname), actions));
    }

    public string Nickname => _nickname.Text;
    public string AvatarPath => _avatar.Text;
    private void Save()
    {
        if (!string.IsNullOrWhiteSpace(Nickname) && File.Exists(AvatarPath)) DialogResult = true;
    }

    internal static TextBox Field(string value = "") => new()
    {
        Text = value,
        MinWidth = 0,
        Height = 38,
        Style = ResourceStyle("TextBoxStyle"),
        VerticalContentAlignment = VerticalAlignment.Center
    };

    internal static FrameworkElement Row(string label, FrameworkElement field)
    {
        var panel = new Grid { Margin = new Thickness(0, 0, 0, 10) };
        panel.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(label.Length == 0 ? 0 : 124) });
        panel.ColumnDefinitions.Add(new ColumnDefinition());
        var text = new TextBlock { Text = label, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center,
            Foreground = (Brush)System.Windows.Application.Current.FindResource("MutedBrush") };
        System.Windows.Automation.AutomationProperties.SetName(field, label);
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

    internal static Button ActionButton(string label, Action action, Brush color)
    {
        var style = color == Brushes.Red ? "DangerButtonStyle" : color == Brushes.Gray || color == Brushes.White ? "ButtonStyle" : "PrimaryButtonStyle";
        var button = new Button { Content = label, IsCancel = label == "Cancel", MinHeight = 36,
            Margin = new Thickness(8, 0, 0, 0), Style = ResourceStyle(style) };
        button.Click += (_, _) => action();
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
    public ConfirmationDialog(string message, string title = "Warning", bool destructive = true)
    {
        Title = title; Width = 400; SizeToContent = SizeToContent.Height;
        Content = IdentitySetupDialog.DialogCard(IdentitySetupDialog.Form(
            IdentitySetupDialog.Heading(title),
            new TextBlock { Text = message, Foreground = (Brush)System.Windows.Application.Current.FindResource("TextBrush"), TextWrapping = TextWrapping.Wrap },
            IdentitySetupDialog.Buttons(("Cancel", () => DialogResult = false, null), ("Confirm", () => DialogResult = true, destructive ? "DangerButtonStyle" : "PrimaryButtonStyle"))));
    }
}

public sealed class TextPromptDialog : DialogWindow
{
    private readonly TextBox _value = IdentitySetupDialog.Field();
    private readonly TextBlock _error = new() { Foreground = (Brush)System.Windows.Application.Current.FindResource("DangerBrush"), FontSize = 12, TextWrapping = TextWrapping.Wrap };
    private readonly string? _myUuid;
    public TextPromptDialog(string title, string label, string initialValue = "", string? myUuid = null)
    {
        _myUuid = myUuid; Title = title; Width = 400; SizeToContent = SizeToContent.Height;
        _value.Text = initialValue;
        if (myUuid is null) { _value.AcceptsReturn = true; _value.TextWrapping = TextWrapping.Wrap; _value.Height = 90; }
        _value.TextChanged += (_, _) => { _error.Text = ""; };
        Content = IdentitySetupDialog.DialogCard(IdentitySetupDialog.Form(IdentitySetupDialog.Heading(title),
            IdentitySetupDialog.Row(label, _value), _error,
            IdentitySetupDialog.Buttons(("Cancel", () => DialogResult = false, null), (myUuid is null ? "Save" : "Add", Save, "PrimaryButtonStyle"))));
    }
    public string Value => _myUuid is null ? _value.Text : _value.Text.Trim().ToLowerInvariant();
    private void Save()
    {
        if (_myUuid is not null)
        {
            if (!Regex.IsMatch(Value, "\\A[a-f0-9]{64}\\z", RegexOptions.CultureInvariant)) { _error.Text = "A friend UUID must contain exactly 64 hexadecimal characters."; return; }
            if (string.Equals(Value, _myUuid, StringComparison.OrdinalIgnoreCase)) { _error.Text = "You cannot add your own UUID."; return; }
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
    private readonly TextBox _server = IdentitySetupDialog.Field();
    private readonly TextBox _port = IdentitySetupDialog.Field();
    private readonly TextBox _turnServer = IdentitySetupDialog.Field();
    private readonly TextBox _turnPort = IdentitySetupDialog.Field();
    private readonly TextBox _turnUser = IdentitySetupDialog.Field();
    private readonly PasswordBox _turnPassword = new() { Height = 38, Padding = new Thickness(11, 8, 11, 8), Background = (Brush)System.Windows.Application.Current.FindResource("SurfaceBrush"), Foreground = (Brush)System.Windows.Application.Current.FindResource("TextBrush") };
    private readonly CheckBox _turnTls = new() { Content = "Use TLS (turns://)", Foreground = (Brush)System.Windows.Application.Current.FindResource("TextBrush") };
    private readonly TextBox _certificate = IdentitySetupDialog.Field();
    private readonly CheckBox _developmentCertificate = new() { Content = "Allow untrusted development certificate", Foreground = (Brush)System.Windows.Application.Current.FindResource("MutedBrush") };
    private readonly SecureSettings _original;
    public SettingsDialog(SecureSettings settings, string uuid)
    {
        _original = settings; Title = "Settings"; Width = 520;
        Height = Math.Min(540, SystemParameters.WorkArea.Height - 80);
        _server.Text = settings.ServerAddress ?? "";
        _port.Text = settings.ServerPort == 0 ? "" : settings.ServerPort.ToString(CultureInfo.InvariantCulture);
        _turnServer.Text = settings.TurnServer ?? ""; _turnPort.Text = settings.TurnPort.ToString(CultureInfo.InvariantCulture);
        _turnUser.Text = settings.TurnUsername ?? ""; _turnPassword.Password = settings.TurnPassword ?? "";
        _turnTls.IsChecked = settings.TurnUseTls; _certificate.Text = settings.ServerCertificateSha256 ?? "";
        _developmentCertificate.IsChecked = settings.AllowDevelopmentCertificate;
        var advanced = IdentitySetupDialog.Form(
            new TextBlock { Text = "TURN Relay (Optional — P2P fallback. Leave empty for pure P2P.)", Foreground = (Brush)FindResource("MutedBrush"), FontSize = 12, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 12) },
            IdentitySetupDialog.Row("TURN Server", _turnServer), IdentitySetupDialog.Row("TURN Port", _turnPort),
            IdentitySetupDialog.Row("TURN Username", _turnUser), IdentitySetupDialog.Row("TURN Password", _turnPassword),
            _turnTls, IdentitySetupDialog.Row("TLS SHA-256 pin", _certificate), _developmentCertificate);
        _turnTls.Margin = new Thickness(0, 4, 0, 12); _developmentCertificate.Margin = new Thickness(0, 4, 0, 0);
        var expand = new Expander { Header = "Advanced Network Settings", Content = advanced,
            Foreground = (Brush)FindResource("TextBrush"), Margin = new Thickness(0, 0, 0, 16) };
        advanced.Margin = new Thickness(8, 12, 0, 0);
        expand.Expanded += (_, _) => Height = Math.Min(680, SystemParameters.WorkArea.Height - 80);
        expand.Collapsed += (_, _) => Height = Math.Min(540, SystemParameters.WorkArea.Height - 80);
        var profile = new Grid();
        profile.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(48) });
        profile.ColumnDefinitions.Add(new ColumnDefinition());
        profile.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        profile.Children.Add(IdentitySetupDialog.Avatar(settings.AvatarPath, uuid, 48, 10));
        var identity = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 12, 0) };
        identity.Children.Add(new TextBlock { Text = settings.Nickname ?? "Me", FontSize = 14, FontWeight = FontWeights.Medium, TextTrimming = TextTrimming.CharacterEllipsis });
        identity.Children.Add(new TextBlock { Text = uuid, Foreground = (Brush)FindResource("MutedBrush"), FontFamily = new FontFamily("Consolas"), FontSize = 11, TextTrimming = TextTrimming.CharacterEllipsis, ToolTip = uuid, Margin = new Thickness(0, 6, 0, 0) });
        Grid.SetColumn(identity, 1); profile.Children.Add(identity);
        var copy = new Button { Content = Geometry.Parse("M 8,8 L 20,8 L 20,22 L 8,22 Z M 16,8 L 16,2 L 4,2 L 4,16 L 8,16"),
            Style = ResourceStyle("IconButtonStyle"), Width = 32, Height = 32, MinHeight = 32, VerticalAlignment = VerticalAlignment.Center, ToolTip = "Copy My UUID" };
        System.Windows.Automation.AutomationProperties.SetName(copy, "Copy My UUID");
        copy.Click += (_, _) => { System.Windows.Clipboard.SetText(uuid); TransientNotice.Show(this, "UUID Copied"); };
        Grid.SetColumn(copy, 2); profile.Children.Add(copy);
        var save = IdentitySetupDialog.ActionButton("Save", Save, Brushes.Lime);
        save.IsDefault = true;
        void Validate() => save.IsEnabled = IsValid();
        foreach (var field in new[] { _server, _port, _turnServer, _turnPort, _certificate }) field.TextChanged += (_, _) => Validate();
        var reset = IdentitySetupDialog.ActionButton("Reset UUID", () => { ResetRequested = true; Close(); }, Brushes.Red);
        reset.HorizontalAlignment = HorizontalAlignment.Left; reset.Margin = new Thickness(0);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        actions.Children.Add(IdentitySetupDialog.ActionButton("Cancel", () => DialogResult = false, Brushes.Gray)); actions.Children.Add(save);
        GroupBox Section(string title, UIElement content) => new() { Header = title, Content = content,
            BorderBrush = (Brush)FindResource("BorderBrush"), Foreground = (Brush)FindResource("TextBrush"),
            Padding = new Thickness(12), Margin = new Thickness(0, 0, 0, 16) };
        var layout = new Grid();
        layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        layout.RowDefinitions.Add(new RowDefinition());
        layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        layout.Children.Add(IdentitySetupDialog.Heading("Settings", 18));
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = IdentitySetupDialog.Form(Section("My Profile", profile),
                Section("Server Relay Settings", IdentitySetupDialog.Form(IdentitySetupDialog.Row("Server IP / Domain", _server), IdentitySetupDialog.Row("Server Port", _port))),
                expand, Section("Danger Zone", reset)) };
        Grid.SetRow(scroll, 1); layout.Children.Add(scroll);
        var footer = new Border { BorderBrush = (Brush)FindResource("BorderBrush"), BorderThickness = new Thickness(0, 1, 0, 0),
            Padding = new Thickness(0, 14, 0, 0), Margin = new Thickness(0, 14, 0, 0), Child = actions };
        Grid.SetRow(footer, 2); layout.Children.Add(footer);
        var card = IdentitySetupDialog.DialogCard(layout);
        card.Margin = new Thickness(12); card.Padding = new Thickness(16);
        Content = card;
        Validate();
    }
    public SecureSettings? Result { get; private set; }
    public bool ResetRequested { get; private set; }
    private string NormalizedPin => _certificate.Text.Replace(":", "", StringComparison.Ordinal).Replace(" ", "", StringComparison.Ordinal).ToLowerInvariant();
    private bool IsValid() => !string.IsNullOrWhiteSpace(_server.Text) && ValidPort(_port.Text, out _) &&
        (string.IsNullOrWhiteSpace(_turnServer.Text) || ValidPort(_turnPort.Text, out _)) &&
        (NormalizedPin.Length == 0 || Regex.IsMatch(NormalizedPin, "\\A[a-f0-9]{64}\\z", RegexOptions.CultureInvariant));
    private void Save()
    {
        if (!IsValid()) return;
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
    public FriendRequestsDialog(MainViewModel viewModel)
    {
        Title = "Friend Requests";
        Width = 480;
        Height = Math.Min(500, SystemParameters.WorkArea.Height * 0.7);
        MinWidth = 480;
        MinHeight = 280;
        ResizeMode = ResizeMode.CanResize;
        var list = new StackPanel();
        var scroll = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = list
        };

        void RefreshRequests()
        {
            list.Children.Clear();
            if (viewModel.FriendRequests.Count == 0) list.Children.Add(new TextBlock { Text = "No pending requests.", Foreground = (Brush)System.Windows.Application.Current.FindResource("MutedBrush"), HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 80, 0, 0) });
            foreach (var request in viewModel.FriendRequests)
            {
                var row = new Grid();
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(60) });
                row.ColumnDefinitions.Add(new ColumnDefinition());
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var avatarHost = new Grid { Width = 48, Height = 48 };
                avatarHost.Children.Add(new Border
                {
                    CornerRadius = new CornerRadius(12),
                    Background = (System.Windows.Media.Brush)System.Windows.Application.Current.FindResource("SurfaceBrush")
                });
                avatarHost.Children.Add(new TextBlock
                {
                    Text = string.IsNullOrWhiteSpace(request.Nickname) ? "" : StringInfo.GetNextTextElement(request.Nickname.Trim()),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                    FontWeight = FontWeights.SemiBold
                });
                var avatar = new Rectangle { Width = 48, Height = 48, RadiusX = 12, RadiusY = 12 };
                avatar.SetBinding(System.Windows.Shapes.Shape.FillProperty, new System.Windows.Data.Binding(nameof(FriendRequest.AvatarPath))
                {
                    Source = request,
                    Converter = (System.Windows.Data.IValueConverter)System.Windows.Application.Current.FindResource("AvatarConverter")
                });
                avatarHost.Children.Add(avatar);
                row.Children.Add(avatarHost);
                var nickname = new TextBlock
                {
                    Text = request.Nickname,
                    ToolTip = request.Nickname,
                    VerticalAlignment = VerticalAlignment.Center,
                    TextTrimming = TextTrimming.CharacterEllipsis,
                    FontWeight = FontWeights.Medium,
                    FontSize = 16,
                    Margin = new Thickness(0, 0, 12, 0)
                };
                Grid.SetColumn(nickname, 1);
                var labels = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                labels.Children.Add(nickname);
                labels.Children.Add(new TextBlock { Text = $"UUID: {request.SenderUuid[..Math.Min(8, request.SenderUuid.Length)]}...", FontSize = 12, Foreground = (Brush)System.Windows.Application.Current.FindResource("MutedBrush") });
                Grid.SetColumn(labels, 1); row.Children.Add(labels);
                var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
                var accept = new Button
                {
                    Content = System.Windows.Application.Current.FindResource("CheckIcon"), ToolTip = "Accept",
                    Width = 36, Height = 36, MinHeight = 36,
                    Background = Brushes.Transparent, BorderThickness = new Thickness(0), Foreground = (Brush)System.Windows.Application.Current.FindResource("AccentBrush"),
                    Padding = new Thickness(0),
                    Style = ResourceStyle("IconButtonStyle")
                };
                var decline = new Button
                {
                    Content = System.Windows.Application.Current.FindResource("CloseIcon"), ToolTip = "Decline",
                    Width = 36, Height = 36, MinHeight = 36,
                    Background = Brushes.Transparent, BorderThickness = new Thickness(0), Foreground = Brushes.Red,
                    Padding = new Thickness(0),
                    Margin = new Thickness(0), Style = ResourceStyle("IconButtonStyle")
                };
                System.Windows.Automation.AutomationProperties.SetName(accept, "Accept");
                System.Windows.Automation.AutomationProperties.SetName(decline, "Decline");
                accept.Click += async (_, _) =>
                {
                    actions.IsEnabled = false;
                    try { await viewModel.AcceptFriendRequestAsync(request); }
                    finally { actions.IsEnabled = true; }
                };
                decline.Click += (_, _) => viewModel.DeclineFriendRequest(request);
                actions.Children.Add(accept);
                actions.Children.Add(decline);
                Grid.SetColumn(actions, 2);
                row.Children.Add(actions);
                list.Children.Add(new Border
                {
                    Background = new SolidColorBrush(Color.FromRgb(44, 44, 44)),
                    CornerRadius = new CornerRadius(12), Padding = new Thickness(12),
                    Margin = new Thickness(0, 8, 0, 8), Child = row
                });
            }
        }

        System.Collections.Specialized.NotifyCollectionChangedEventHandler changed = (_, _) => RefreshRequests();
        viewModel.FriendRequests.CollectionChanged += changed;
        Closed += (_, _) => viewModel.FriendRequests.CollectionChanged -= changed;
        RefreshRequests();
        var layout = new DockPanel();
        var title = IdentitySetupDialog.Heading("Friend Requests", 20); title.FontWeight = FontWeights.Bold; DockPanel.SetDock(title, Dock.Top); layout.Children.Add(title);
        var close = IdentitySetupDialog.Buttons(("Close", () => Close(), null)); DockPanel.SetDock(close, Dock.Bottom); layout.Children.Add(close); layout.Children.Add(scroll);
        Content = IdentitySetupDialog.DialogCard(layout);
    }
}

public sealed class AvatarCropDialog : DialogWindow
{
    private readonly System.Windows.Media.Imaging.BitmapSource _source;
    private readonly ScaleTransform _scale = new();
    private readonly TranslateTransform _offset = new();
    private readonly double _baseScale;
    public byte[]? Result { get; private set; }
    public AvatarCropDialog(string path)
    {
        Title = "Crop"; Width = 400; SizeToContent = SizeToContent.Height;
        _source = ImageDecoder.Load(path);
        _baseScale = Math.Max(300d / _source.PixelWidth, 300d / _source.PixelHeight);
        var image = new Image { Source = _source, Width = _source.PixelWidth * _baseScale, Height = _source.PixelHeight * _baseScale, Stretch = Stretch.Fill, RenderTransformOrigin = new Point(0.5, 0.5), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        var transforms = new TransformGroup(); transforms.Children.Add(_scale); transforms.Children.Add(_offset); image.RenderTransform = transforms;
        var viewport = new Grid { Width = 300, Height = 300, ClipToBounds = true, Background = Brushes.Black }; viewport.Children.Add(image);
        Point? start = null; Point origin = default;
        viewport.MouseLeftButtonDown += (_, e) => { start = e.GetPosition(viewport); origin = new Point(_offset.X, _offset.Y); viewport.CaptureMouse(); };
        viewport.MouseMove += (_, e) => { if (start is not { } point) return; var delta = e.GetPosition(viewport) - point; _offset.X = origin.X + delta.X; _offset.Y = origin.Y + delta.Y; Clamp(); };
        viewport.MouseLeftButtonUp += (_, _) => { start = null; viewport.ReleaseMouseCapture(); };
        var zoom = new Slider { Minimum = 1, Maximum = 5, Value = 1, Margin = new Thickness(0, 16, 0, 0) };
        zoom.ValueChanged += (_, _) => { _scale.ScaleX = _scale.ScaleY = zoom.Value; Clamp(); };
        Content = IdentitySetupDialog.DialogCard(IdentitySetupDialog.Form(IdentitySetupDialog.Heading("Crop"), viewport, zoom,
            IdentitySetupDialog.Buttons(("Cancel", () => DialogResult = false, null), ("Confirm", Save, "PrimaryButtonStyle"))));
    }
    private void Clamp()
    {
        var width = Math.Max(0, (_source.PixelWidth * _baseScale * _scale.ScaleX - 300) / 2);
        var height = Math.Max(0, (_source.PixelHeight * _baseScale * _scale.ScaleY - 300) / 2);
        _offset.X = Math.Clamp(_offset.X, -width, width); _offset.Y = Math.Clamp(_offset.Y, -height, height);
    }
    private void Save()
    {
        var factor = _baseScale * _scale.ScaleX;
        var side = Math.Min(Math.Min(_source.PixelWidth, _source.PixelHeight), Math.Max(1, (int)Math.Round(300 / factor)));
        var x = Math.Clamp((int)Math.Round((_source.PixelWidth - side) / 2d - _offset.X / factor), 0, _source.PixelWidth - side);
        var y = Math.Clamp((int)Math.Round((_source.PixelHeight - side) / 2d - _offset.Y / factor), 0, _source.PixelHeight - side);
        var crop = new System.Windows.Media.Imaging.CroppedBitmap(_source, new Int32Rect(x, y, side, side));
        var bitmap = new System.Windows.Media.Imaging.TransformedBitmap(crop, new ScaleTransform(256d / side, 256d / side));
        var encoder = new System.Windows.Media.Imaging.JpegBitmapEncoder { QualityLevel = 86 }; encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(bitmap));
        using var output = new MemoryStream(); encoder.Save(output); Result = output.ToArray(); DialogResult = true;
    }
}
