using Microsoft.Win32;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
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

    public IdentitySetupDialog()
    {
        Title = "Set up MsgAT";
        Width = 480;
        SizeToContent = SizeToContent.Height;
        Content = DialogCard(Form(
            Row("Nickname", _nickname),
            Row("Avatar", BrowseRow(_avatar)),
            Buttons(("Continue", Save, "PrimaryButtonStyle"), ("Cancel", () => DialogResult = false, null))));
    }

    public string Nickname => _nickname.Text.Trim();
    public string AvatarPath => _avatar.Text.Trim();

    private void Save()
    {
        if (Nickname.Length == 0 || !File.Exists(AvatarPath))
        {
            MessageBox.Show(this, "Nickname and avatar are required.", "MsgAT", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        DialogResult = true;
    }

    private DockPanel BrowseRow(TextBox target)
    {
        var panel = new DockPanel();
        var button = new Button
        {
            Content = "Browse…",
            Margin = new Thickness(8, 0, 0, 0),
            Style = ResourceStyle("ButtonStyle")
        };
        DockPanel.SetDock(button, Dock.Right);
        button.Click += (_, _) =>
        {
            var picker = new OpenFileDialog { Filter = "Images|*.png;*.jpg;*.jpeg;*.bmp" };
            if (picker.ShowDialog(this) == true) { target.Text = picker.FileName; }
        };
        panel.Children.Add(button);
        panel.Children.Add(target);
        return panel;
    }

    internal static TextBox Field(string value = "") => new()
    {
        Text = value,
        MinWidth = 280,
        Height = 38,
        VerticalContentAlignment = VerticalAlignment.Center
    };

    internal static FrameworkElement Row(string label, FrameworkElement field)
    {
        var panel = new Grid { Margin = new Thickness(0, 0, 0, 10) };
        panel.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(124) });
        panel.ColumnDefinitions.Add(new ColumnDefinition());
        var text = new TextBlock
        {
            Text = label,
            VerticalAlignment = VerticalAlignment.Center,
            Foreground = (System.Windows.Media.Brush)System.Windows.Application.Current.FindResource("MutedBrush")
        };
        Grid.SetColumn(field, 1);
        panel.Children.Add(text);
        panel.Children.Add(field);
        return panel;
    }

    internal static FrameworkElement Form(params UIElement[] children)
    {
        var panel = new StackPanel();
        foreach (var child in children) { panel.Children.Add(child); }
        return panel;
    }

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

    internal static FrameworkElement Buttons(params (string Label, Action Action, string? StyleKey)[] definitions)
    {
        var panel = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
            Margin = new Thickness(0, 8, 0, 0)
        };
        foreach (var definition in definitions)
        {
            var button = new Button
            {
                Content = definition.Label,
                IsCancel = definition.Label == "Cancel",
                Margin = new Thickness(8, 0, 0, 0),
                MinWidth = 82,
                Style = ResourceStyle(definition.StyleKey ?? "ButtonStyle")
            };
            button.Click += (_, _) => definition.Action();
            panel.Children.Add(button);
        }
        return panel;
    }
}

public sealed class TextPromptDialog : DialogWindow
{
    private readonly TextBox _value = IdentitySetupDialog.Field();

    public TextPromptDialog(string title, string label, string initialValue = "")
    {
        Title = title;
        Width = 500;
        SizeToContent = SizeToContent.Height;
        _value.Text = initialValue;
        Content = IdentitySetupDialog.DialogCard(IdentitySetupDialog.Form(
            IdentitySetupDialog.Row(label, _value),
            IdentitySetupDialog.Buttons(
                ("OK", Save, "PrimaryButtonStyle"),
                ("Cancel", () => DialogResult = false, null))));
    }

    public string Value => _value.Text.Trim();

    private void Save()
    {
        if (Value.Length > 0) { DialogResult = true; }
    }
}

public sealed class SettingsDialog : DialogWindow
{
    private readonly TextBox _server = IdentitySetupDialog.Field();
    private readonly TextBox _port = IdentitySetupDialog.Field();
    private readonly TextBox _turnServer = IdentitySetupDialog.Field();
    private readonly TextBox _turnPort = IdentitySetupDialog.Field();
    private readonly TextBox _turnUser = IdentitySetupDialog.Field();
    private readonly PasswordBox _turnPassword = new() { MinWidth = 280, Height = 38 };
    private readonly CheckBox _turnTls = new() { Content = "Use TLS" };
    private readonly TextBox _certificate = IdentitySetupDialog.Field();
    private readonly CheckBox _developmentCertificate = new() { Content = "Allow untrusted development certificate" };
    private readonly SecureSettings _original;

    public SettingsDialog(SecureSettings settings, string uuid)
    {
        _original = settings;
        Title = "Settings";
        Width = 540;
        MinWidth = 500;
        MinHeight = 280;
        ResizeMode = ResizeMode.CanResize;
        MaxHeight = Math.Max(280, SystemParameters.WorkArea.Height - 80);
        Height = Math.Min(560, MaxHeight);
        _server.Text = settings.ServerAddress ?? "";
        _port.Text = settings.ServerPort == 0 ? "" : settings.ServerPort.ToString(CultureInfo.InvariantCulture);
        _turnServer.Text = settings.TurnServer ?? "";
        _turnPort.Text = settings.TurnPort.ToString(CultureInfo.InvariantCulture);
        _turnUser.Text = settings.TurnUsername ?? "";
        _turnPassword.Password = settings.TurnPassword ?? "";
        _turnTls.IsChecked = settings.TurnUseTls;
        _certificate.Text = settings.ServerCertificateSha256 ?? "";
        _developmentCertificate.IsChecked = settings.AllowDevelopmentCertificate;

        var advanced = new StackPanel { Margin = new Thickness(0, 12, 0, 0) };
        advanced.Children.Add(IdentitySetupDialog.Row("TURN server", _turnServer));
        advanced.Children.Add(IdentitySetupDialog.Row("TURN port", _turnPort));
        advanced.Children.Add(IdentitySetupDialog.Row("TURN user", _turnUser));
        advanced.Children.Add(IdentitySetupDialog.Row("TURN password", _turnPassword));
        _turnTls.Margin = new Thickness(124, 0, 0, 12);
        advanced.Children.Add(_turnTls);
        advanced.Children.Add(IdentitySetupDialog.Row("TLS SHA-256 pin", _certificate));
        _developmentCertificate.Margin = new Thickness(124, 0, 0, 4);
        advanced.Children.Add(_developmentCertificate);

        var uuidField = IdentitySetupDialog.Field(uuid);
        uuidField.IsReadOnly = true;
        uuidField.ToolTip = uuid;

        var actions = new Grid { Margin = new Thickness(0, 10, 0, 0) };
        actions.ColumnDefinitions.Add(new ColumnDefinition());
        actions.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var reset = new Button
        {
            Content = "Reset identity",
            HorizontalAlignment = HorizontalAlignment.Left,
            Style = ResourceStyle("DangerButtonStyle")
        };
        reset.Click += (_, _) => RequestReset();
        var rightActions = (StackPanel)IdentitySetupDialog.Buttons(
            ("Save", Save, "PrimaryButtonStyle"),
            ("Cancel", () => DialogResult = false, null));
        rightActions.Margin = new Thickness(0);
        Grid.SetColumn(rightActions, 1);
        actions.Children.Add(reset);
        actions.Children.Add(rightActions);

        var profile = new Grid { Margin = new Thickness(0, 0, 0, 18) };
        profile.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(64) });
        profile.ColumnDefinitions.Add(new ColumnDefinition());
        var avatar = new Grid { Width = 48, Height = 48, HorizontalAlignment = HorizontalAlignment.Left };
        avatar.Children.Add(new System.Windows.Shapes.Ellipse { Fill = (System.Windows.Media.Brush)FindResource("SurfaceBrush") });
        avatar.Children.Add(new TextBlock
        {
            Text = string.IsNullOrWhiteSpace(settings.Nickname) ? "" : StringInfo.GetNextTextElement(settings.Nickname),
            FontSize = 20, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center
        });
        var picture = new System.Windows.Shapes.Ellipse();
        picture.SetBinding(System.Windows.Shapes.Shape.FillProperty, new System.Windows.Data.Binding(nameof(SecureSettings.AvatarPath))
        {
            Source = settings,
            Converter = (System.Windows.Data.IValueConverter)FindResource("AvatarConverter")
        });
        avatar.Children.Add(picture);
        var nickname = new TextBlock
        {
            Text = settings.Nickname ?? "", ToolTip = settings.Nickname,
            FontSize = 17, FontWeight = FontWeights.SemiBold,
            VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis
        };
        Grid.SetColumn(nickname, 1);
        profile.Children.Add(avatar);
        profile.Children.Add(nickname);

        var form = IdentitySetupDialog.Form(
            profile,
            IdentitySetupDialog.Row("UUID", uuidField),
            IdentitySetupDialog.Row("Server", _server),
            IdentitySetupDialog.Row("Port", _port),
            new Expander
            {
                Header = "Advanced Network Settings",
                Content = advanced,
                IsExpanded = false,
                Margin = new Thickness(0, 3, 0, 8)
            });

        var layout = new DockPanel();
        actions.Margin = new Thickness(24, 12, 24, 20);
        DockPanel.SetDock(actions, Dock.Bottom);
        layout.Children.Add(actions);
        layout.Children.Add(new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = IdentitySetupDialog.DialogCard(form)
        });
        Content = layout;
    }

    public SecureSettings? Result { get; private set; }
    public bool ResetRequested { get; private set; }

    private void RequestReset()
    {
        ResetRequested = true;
        Close();
    }

    private void Save()
    {
        if (string.IsNullOrWhiteSpace(_server.Text) || !ValidPort(_port.Text, out var serverPort) ||
            !ValidPort(_turnPort.Text, out var turnPort))
        {
            MessageBox.Show(this, "Server and valid ports are required.", "MsgAT", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        var fingerprint = new string(_certificate.Text.Where(Uri.IsHexDigit).ToArray());
        if (fingerprint.Length is not (0 or 64))
        {
            MessageBox.Show(this, "The TLS SHA-256 pin must contain 64 hexadecimal characters.", "MsgAT", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        Result = _original with
        {
            ServerAddress = _server.Text.Trim(),
            ServerPort = serverPort,
            TurnServer = EmptyToNull(_turnServer.Text),
            TurnPort = turnPort,
            TurnUsername = EmptyToNull(_turnUser.Text),
            TurnPassword = EmptyToNull(_turnPassword.Password),
            TurnUseTls = _turnTls.IsChecked == true,
            ServerCertificateSha256 = EmptyToNull(fingerprint),
            AllowDevelopmentCertificate = _developmentCertificate.IsChecked == true
        };
        DialogResult = true;
    }

    private static bool ValidPort(string value, out int port) =>
        int.TryParse(value, out port) && port is > 0 and <= 65535;

    private static string? EmptyToNull(string value) =>
        string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}

public sealed class FriendRequestsDialog : DialogWindow
{
    public FriendRequestsDialog(MainViewModel viewModel)
    {
        Title = "Friend requests";
        Width = 540;
        Height = 380;
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
            foreach (var request in viewModel.FriendRequests)
            {
                var row = new Grid();
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(46) });
                row.ColumnDefinitions.Add(new ColumnDefinition());
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var avatarHost = new Grid { Width = 36, Height = 36 };
                avatarHost.Children.Add(new Border
                {
                    CornerRadius = new CornerRadius(18),
                    Background = (System.Windows.Media.Brush)System.Windows.Application.Current.FindResource("SurfaceBrush")
                });
                avatarHost.Children.Add(new TextBlock
                {
                    Text = string.IsNullOrWhiteSpace(request.Nickname) ? "" : StringInfo.GetNextTextElement(request.Nickname.Trim()),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                    FontWeight = FontWeights.SemiBold
                });
                var avatar = new System.Windows.Shapes.Ellipse { Width = 36, Height = 36 };
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
                    FontWeight = FontWeights.SemiBold,
                    Margin = new Thickness(0, 0, 12, 0)
                };
                Grid.SetColumn(nickname, 1);
                row.Children.Add(nickname);
                var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
                var accept = new Button
                {
                    Content = System.Windows.Application.Current.FindResource("CheckIcon"), ToolTip = "Accept",
                    Width = 32, Height = 32, MinHeight = 32,
                    Padding = new Thickness(0),
                    Style = ResourceStyle("PrimaryIconButtonStyle")
                };
                var decline = new Button
                {
                    Content = System.Windows.Application.Current.FindResource("CloseIcon"), ToolTip = "Decline",
                    Width = 32, Height = 32, MinHeight = 32,
                    Padding = new Thickness(0),
                    Margin = new Thickness(8, 0, 0, 0), Style = ResourceStyle("IconButtonStyle")
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
                    Background = (System.Windows.Media.Brush)System.Windows.Application.Current.FindResource("SurfaceBrush"),
                    CornerRadius = new CornerRadius(8), Padding = new Thickness(12),
                    Margin = new Thickness(0, 0, 0, 8), Child = row
                });
            }
        }

        System.Collections.Specialized.NotifyCollectionChangedEventHandler changed = (_, _) => RefreshRequests();
        viewModel.FriendRequests.CollectionChanged += changed;
        Closed += (_, _) => viewModel.FriendRequests.CollectionChanged -= changed;
        RefreshRequests();
        Content = IdentitySetupDialog.DialogCard(scroll);
    }
}
