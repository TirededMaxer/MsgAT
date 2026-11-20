// SPDX-License-Identifier: AGPL-3.0-only
using System.ComponentModel;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;
using Xaml = Microsoft.UI.Xaml;

namespace MsgAT.Windows.Presentation;

internal enum ChatAction { Profile, Voice, Video, Search, Notifications, History, Queue, Forward, Save, Copy, Delete, Cancel }

public sealed class ChatHeaderHost : WorkspaceHost
{
    private static readonly (ChatAction Action, string Text, string Glyph)[] Actions =
    [
        (ChatAction.Voice, "UiVoiceCall", "\uE717"), (ChatAction.Video, "UiVideoCall", "\uE714"),
        (ChatAction.Search, "UiSearchMessages", "\uE721"), (ChatAction.Notifications, "UiConversationNotifications", "\uEA8F"),
        (ChatAction.History, "UiHistoryNavigation", "\uE787"), (ChatAction.Queue, "UiSendingQueue", "\uE823"),
        (ChatAction.Forward, "UiForwardSelectedMessages", "\uE72A"), (ChatAction.Save, "UiSaveSelectedMessages", "\uE734"),
        (ChatAction.Copy, "UiCopySelectedMessages", "\uE8C8"), (ChatAction.Delete, "UiDeleteMessages", "\uE74D"),
        (ChatAction.Cancel, "UiCancelMessageSelection", "\uE711")
    ];
    private readonly Dictionary<ChatAction, AppBarButton> _actions = [];
    private Grid? _root;
    private Button? _profile;
    private ContactAvatar? _avatar;
    private TextBlock? _title, _status, _verified, _selection;
    private CommandBar? _commands;
    private ConversationItem? _peer;
    private (bool Selecting, bool Compact)? _arrangement;
    internal Action<ChatAction>? InvokeAction { get; set; }
    internal Func<System.Windows.Controls.ContextMenu?>? NotificationMenu { get; set; }

    protected override FrameworkElement CreateContent()
    {
        var grid = new Grid { Padding = new Thickness(8, 0, 6, 0), ColumnSpacing = 6 };
        _root = grid;
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var info = new Grid { ColumnSpacing = 10, HorizontalAlignment = Xaml.HorizontalAlignment.Stretch };
        info.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        info.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _avatar = new ContactAvatar(36);
        info.Children.Add(_avatar.View);
        var labels = new StackPanel { VerticalAlignment = Xaml.VerticalAlignment.Center };
        var name = new Grid { ColumnSpacing = 4 };
        name.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        name.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _title = new TextBlock { FontSize = 16, FontWeight = FontWeights.SemiBold, MaxLines = 1,
            TextTrimming = TextTrimming.CharacterEllipsis };
        _verified = new TextBlock { FontFamily = new FontFamily("Segoe Fluent Icons"), FontSize = 13,
            Text = "\uEB51", VerticalAlignment = Xaml.VerticalAlignment.Center };
        name.Children.Add(_title);
        Grid.SetColumn(_verified, 1);
        name.Children.Add(_verified);
        labels.Children.Add(name);
        _status = new TextBlock { FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis, MaxLines = 1 };
        labels.Children.Add(_status);
        Grid.SetColumn(labels, 1);
        info.Children.Add(labels);
        _profile = new Button { Content = info, HorizontalAlignment = Xaml.HorizontalAlignment.Left,
            HorizontalContentAlignment = Xaml.HorizontalAlignment.Stretch, Padding = new Thickness(4, 2, 6, 2),
            BorderThickness = new Thickness(0), Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
        _profile.Click += (_, _) => InvokeAction?.Invoke(ChatAction.Profile);
        grid.Children.Add(_profile);
        _selection = new TextBlock { FontSize = 16, FontWeight = FontWeights.SemiBold, Margin = new Thickness(8,0,0,0),
            VerticalAlignment = Xaml.VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
        grid.Children.Add(_selection);
        _commands = new CommandBar { DefaultLabelPosition = CommandBarDefaultLabelPosition.Collapsed,
            IsDynamicOverflowEnabled = true, VerticalAlignment = Xaml.VerticalAlignment.Center,
            HorizontalAlignment = Xaml.HorizontalAlignment.Right, Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
        Grid.SetColumn(_commands, 1);
        grid.Children.Add(_commands);
        foreach (var (action, _, glyph) in Actions)
        {
            var button = new AppBarButton { Icon = new FontIcon { Glyph = glyph, FontSize = 18 }, MinWidth = 36, Width = 40 };
            button.Click += (_, _) =>
            {
                if (action == ChatAction.Notifications && NotificationMenu?.Invoke() is { } menu)
                    NativeMenu.From(menu.Items).ShowAt(button);
                else InvokeAction?.Invoke(action);
            };
            _actions.Add(action, button);
        }
        grid.SizeChanged += (_, _) => ArrangeActions();
        return grid;
    }

    private void ArrangeActions()
    {
        if (_commands is null || _root is null) return;
        var selecting = Model?.IsSelecting == true;
        var compact = _root.ActualWidth < 580;
        if (_arrangement == (selecting, compact)) return;
        _arrangement = (selecting, compact);
        _commands.PrimaryCommands.Clear();
        _commands.SecondaryCommands.Clear();
        foreach (var (action, _, _) in Actions)
        {
            var selectionAction = action is ChatAction.Forward or ChatAction.Save or ChatAction.Copy or ChatAction.Delete or ChatAction.Cancel;
            if (selecting != selectionAction) continue;
            var overflow = compact && action is (ChatAction.Notifications or ChatAction.History or ChatAction.Queue or ChatAction.Copy or ChatAction.Delete);
            var button = _actions[action];
            if (overflow) button.ClearValue(FrameworkElement.WidthProperty); else button.Width = 40;
            (overflow ? _commands.SecondaryCommands : _commands.PrimaryCommands).Add(button);
        }
    }

    protected override void BindWorkspace()
    {
        if (_peer is not null) _peer.PropertyChanged -= ModelChanged;
        _peer = Model?.SelectedConversation;
        if (_peer is not null) _peer.PropertyChanged += ModelChanged;
        Update();
    }

    protected override void ModelChanged(object? sender, PropertyChangedEventArgs args)
    {
        if (args.PropertyName == nameof(MainViewModel.SelectedConversation)) { BindWorkspace(); return; }
        if (args.PropertyName is nameof(MainViewModel.PeerActivity) or nameof(MainViewModel.SyncText) or
            nameof(MainViewModel.ChatPeerVerified) or nameof(MainViewModel.IsSelecting) or nameof(MainViewModel.SelectionText) or
            nameof(MainViewModel.CanCompose) or nameof(MainViewModel.CanKeepSelection) or nameof(MainViewModel.CanUseSelection) or
            nameof(MainViewModel.CanChangeSelection) or nameof(ConversationItem.IsMuted) or nameof(ConversationItem.Alias) or
            nameof(ConversationItem.AvatarPath)) Update();
    }

    private void Update()
    {
        if (_root is null) return;
        var selected = Model?.IsSelecting == true;
        _profile!.Visibility = selected ? Xaml.Visibility.Collapsed : Xaml.Visibility.Visible;
        _selection!.Visibility = selected ? Xaml.Visibility.Visible : Xaml.Visibility.Collapsed;
        _selection.Text = selected ? Model!.SelectionText : "";
        _title!.Text = _peer?.Alias ?? "";
        _avatar!.Set(_title.Text, _peer?.AvatarPath);
        var statusKey = Model?.PeerActivity switch
        {
            PeerActivityStatus.Typing => "UiPeerTyping", PeerActivityStatus.Online => "UiPeerOnline",
            PeerActivityStatus.Offline => "UiPeerOffline", _ => Model?.SyncText switch
            { "Connected" => "UiConnected", "Syncing..." => "UiSyncing", _ => "UiDisconnected" }
        };
        _status!.Text = AppStrings.Get(statusKey);
        _verified!.Visibility = Model?.ChatPeerVerified == true ? Xaml.Visibility.Visible : Xaml.Visibility.Collapsed;
        AutomationProperties.SetName(_profile, string.Join(", ", new[] { _title.Text, _status.Text, AppStrings.Get("UiContactProfile") }));
        AutomationProperties.SetItemStatus(_profile, Model?.ChatPeerVerified == true ? AppStrings.Get("UiContactVerified") : "");
        foreach (var (action, key, _) in Actions)
        {
            var button = _actions[action];
            var text = AppStrings.Get(key);
            button.Label = text;
            AutomationProperties.SetName(button, text);
            ToolTipService.SetToolTip(button, action == ChatAction.Notifications && _peer?.IsMuted == true ?
                AppStrings.Get("UiConversationMuted") : text);
            button.IsEnabled = action switch
            {
                ChatAction.Voice or ChatAction.Video => Model?.CanCompose == true,
                ChatAction.Forward or ChatAction.Save => Model?.CanKeepSelection == true,
                ChatAction.Copy or ChatAction.Delete => Model?.CanUseSelection == true,
                ChatAction.Cancel => Model?.CanChangeSelection == true, _ => _peer is not null
            };
        }
        ArrangeActions();
    }

    protected override void RefreshPresentation()
    {
        if (_root is null) return;
        if (System.Windows.SystemParameters.HighContrast)
        {
            _root.ClearValue(Panel.BackgroundProperty);
            _status!.ClearValue(TextBlock.ForegroundProperty);
            _verified!.ClearValue(TextBlock.ForegroundProperty);
        }
        else
        {
            _root.Background = ColorBrush("MessagingChromeColor");
            _status!.Foreground = ColorBrush("MutedColor");
            _verified!.Foreground = ColorBrush("MessagingSelectionMarkColor");
        }
        Update();
    }

    protected override void ReleaseContent()
    {
        base.ReleaseContent();
        _avatar?.Clear();
        _root = null;
        _commands = null;
        _arrangement = null;
        _actions.Clear();
    }
}
