// SPDX-License-Identifier: AGPL-3.0-only
using System.ComponentModel;
using System.Globalization;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Views;

public sealed class ForwardMessagesDialog : DialogWindow
{
    private const int RecipientLimit = 20;
    private readonly AppServices _services;
    private readonly string? _peer;
    private readonly string[] _ids;
    private readonly List<Conversation> _selected = [];
    private readonly System.Windows.Controls.TextBox _filter = IdentitySetupDialog.Field();
    private readonly ListBox _targets = new() { BorderThickness = new Thickness(0), SelectionMode = SelectionMode.Multiple };
    private readonly StackPanel _chips = new() { Orientation = Orientation.Horizontal };
    private readonly ScrollViewer _chosen;
    private readonly MenuItem _hideSender = new() { Header = AppStrings.Get("UiForwardHideSender"), IsCheckable = true };
    private readonly MenuItem _omitPreview = new() { Header = AppStrings.Get("UiForwardOmitPreview"), IsCheckable = true };
    private readonly MenuItem _hideCaption = new() { Header = AppStrings.Get("UiForwardHideCaption"), IsCheckable = true };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 6) };
    private readonly TextBlock _count = new() { TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(8, 0, 8, 0) };
    private readonly TextBlock _optionsSummary = new() { Visibility = Visibility.Collapsed, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 6) };
    private readonly TextBlock _emptyLabel = new() { TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center };
    private readonly StackPanel _empty = new() { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12) };
    private readonly Button _send;
    private readonly Button _cancel;
    private readonly Button _optionsButton;
    private readonly Button _retry;
    private readonly Button _clearSearch;
    private SendOptions _options = new();
    private CancellationTokenSource? _operation;
    private IReadOnlyList<Conversation> _contacts = [];
    private Conversation[] _visible = [];
    private bool _loading = true;
    private bool _loadFailed;
    private bool _updatingSelection;
    private bool _cancelRequested;
    private bool _busy;
    private bool _closed;

    public ForwardMessagesDialog(AppServices services, string? peer, IReadOnlyList<string> ids)
    {
        _services = services;
        _peer = peer;
        _ids = ids.ToArray();
        Title = _ids.Length == 1 ? AppStrings.Get("UiForwardMessage") : AppStrings.Format("UiForwardMessagesCount", _ids.Length);
        Width = 520;
        Height = Math.Min(560, SystemParameters.WorkArea.Height * .9);
        MinWidth = 400;
        MinHeight = Math.Min(360, Height);
        ResizeMode = ResizeMode.CanResize;
        SetResourceReference(BackgroundProperty, "MessagingChromeBrush");

        var panel = new DockPanel { Margin = new Thickness(16) };
        var header = new StackPanel { Margin = new Thickness(0, 0, 0, 8) };
        header.Children.Add(IdentitySetupDialog.Heading(Title));
        var search = new DockPanel();
        _clearSearch = IconButton("CloseIcon", "UiForwardClearSearch", () => { _filter.Clear(); _filter.Focus(); });
        DockPanel.SetDock(_clearSearch, Dock.Right);
        search.Children.Add(_clearSearch);
        var searchName = AppStrings.Get("UiFindRecipient");
        _filter.ToolTip = searchName;
        AutomationProperties.SetName(_filter, searchName);
        search.Children.Add(_filter);
        header.Children.Add(new Label { Content = searchName, Target = _filter, Padding = new Thickness(0, 0, 0, 4) });
        header.Children.Add(search);
        _chosen = new ScrollViewer
        {
            Content = _chips, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, Focusable = false,
            Margin = new Thickness(0, 8, 0, 0), Visibility = Visibility.Collapsed
        };
        header.Children.Add(_chosen);
        DockPanel.SetDock(header, Dock.Top);
        panel.Children.Add(header);

        var footer = new StackPanel { Margin = new Thickness(0, 8, 0, 0) };
        AutomationProperties.SetLiveSetting(_status, AutomationLiveSetting.Polite);
        footer.Children.Add(_status);
        footer.Children.Add(_optionsSummary);
        var menu = new ContextMenu { Placement = PlacementMode.Top };
        menu.Items.Add(_hideSender);
        menu.Items.Add(_hideCaption);
        menu.Items.Add(_omitPreview);
        menu.Items.Add(new Separator());
        var sendOptions = new MenuItem { Header = AppStrings.Get("UiSendOptions") };
        sendOptions.Click += (_, _) => ChooseSendOptions();
        menu.Items.Add(sendOptions);
        _optionsButton = IconButton("MoreIcon", "UiForwardOptions", () => menu.IsOpen = true);
        _optionsButton.ContextMenu = menu;
        menu.PlacementTarget = _optionsButton;
        _send = IdentitySetupDialog.ActionButton(AppStrings.Get("UiForward"), () => _ = SendAsync(), Brushes.Lime);
        _send.IsDefault = true;
        _cancel = IdentitySetupDialog.ActionButton(AppStrings.Get("UiCancel"), Cancel, Brushes.Gray);
        _cancel.IsCancel = false;
        var actions = new DockPanel();
        DockPanel.SetDock(_optionsButton, Dock.Left);
        DockPanel.SetDock(_send, Dock.Right);
        DockPanel.SetDock(_cancel, Dock.Right);
        actions.Children.Add(_optionsButton);
        actions.Children.Add(_send);
        actions.Children.Add(_cancel);
        actions.Children.Add(_count);
        footer.Children.Add(actions);
        var footerFrame = new Border { Child = footer, BorderThickness = new Thickness(0, 1, 0, 0),
            Margin = new Thickness(0, 4, 0, 0) };
        footerFrame.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        DockPanel.SetDock(footerFrame, Dock.Bottom);
        panel.Children.Add(footerFrame);

        var recipientPane = new Grid();
        recipientPane.SetResourceReference(Panel.BackgroundProperty, "MessagingTimelineBrush");
        ConfigureRecipientGrid();
        recipientPane.Children.Add(_targets);
        _empty.Children.Add(_emptyLabel);
        _retry = IdentitySetupDialog.ActionButton(AppStrings.Get("UiRetry"), () => _ = LoadAsync(), Brushes.Gray);
        _retry.Margin = new Thickness(0, 8, 0, 0);
        _retry.HorizontalAlignment = HorizontalAlignment.Center;
        _empty.Children.Add(_retry);
        recipientPane.Children.Add(_empty);
        panel.Children.Add(recipientPane);
        Content = panel;

        _filter.TextChanged += (_, _) => RenderTargets();
        _filter.PreviewKeyDown += (_, e) =>
        {
            if (e.Key != Key.Down || _visible.Length == 0 || !_targets.IsEnabled) return;
            _targets.ScrollIntoView(_visible[0]);
            _targets.UpdateLayout();
            if (_targets.ItemContainerGenerator.ContainerFromIndex(0) is ListBoxItem row)
            {
                row.Focus();
                e.Handled = true;
            }
        };
        _targets.SelectionChanged += TargetsSelectionChanged;
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key != Key.Escape) return;
            e.Handled = true;
            if (!_busy && _filter.IsKeyboardFocusWithin && _filter.Text.Length > 0) _filter.Clear();
            else Cancel();
        };
        Loaded += async (_, _) => { _filter.Focus(); await LoadAsync(); };
        Closing += OnClosing;
        Closed += (_, _) => { _closed = true; _operation?.Cancel(); };
        UpdateActions();
    }

    private void ConfigureRecipientGrid()
    {
        var panel = new FrameworkElementFactory(typeof(WrapPanel));
        panel.SetValue(WrapPanel.ItemWidthProperty, 96d);
        panel.SetValue(WrapPanel.ItemHeightProperty, 94d);
        _targets.ItemsPanel = new ItemsPanelTemplate(panel);
        _targets.SetValue(ScrollViewer.HorizontalScrollBarVisibilityProperty, ScrollBarVisibility.Disabled);
        _targets.SetValue(ScrollViewer.CanContentScrollProperty, false);
        _targets.SetResourceReference(BackgroundProperty, "WindowBrush");
        _targets.SetResourceReference(ForegroundProperty, "TextBrush");
        KeyboardNavigation.SetDirectionalNavigation(_targets, KeyboardNavigationMode.Contained);
        AutomationProperties.SetName(_targets, AppStrings.Get("UiFindRecipient"));

        var tile = new FrameworkElementFactory(typeof(StackPanel));
        tile.SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Center);
        tile.SetValue(VerticalAlignmentProperty, VerticalAlignment.Center);
        var avatar = new FrameworkElementFactory(typeof(Grid));
        avatar.SetValue(WidthProperty, 48d);
        avatar.SetValue(HeightProperty, 48d);
        var background = new FrameworkElementFactory(typeof(Ellipse));
        background.SetValue(Shape.FillProperty, FindResource("AccentBrush"));
        background.SetValue(OpacityProperty, .25);
        avatar.AppendChild(background);
        var initial = new FrameworkElementFactory(typeof(TextBlock));
        initial.SetBinding(TextBlock.TextProperty, new Binding(nameof(Conversation.Alias)) { Converter = new InitialConverter() });
        initial.SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Center);
        initial.SetValue(VerticalAlignmentProperty, VerticalAlignment.Center);
        initial.SetValue(TextBlock.FontSizeProperty, 19d);
        avatar.AppendChild(initial);
        var photo = new FrameworkElementFactory(typeof(Ellipse));
        photo.SetBinding(Shape.FillProperty, new Binding(nameof(Conversation.AvatarPath)) { Converter = (IValueConverter)FindResource("AvatarConverter") });
        avatar.AppendChild(photo);
        var ring = new FrameworkElementFactory(typeof(Ellipse), "SelectedRing");
        ring.SetValue(Shape.StrokeProperty, FindResource("AccentBrush"));
        ring.SetValue(Shape.StrokeThicknessProperty, 2.5);
        ring.SetValue(VisibilityProperty, Visibility.Hidden);
        avatar.AppendChild(ring);
        tile.AppendChild(avatar);
        var name = new FrameworkElementFactory(typeof(TextBlock));
        name.SetBinding(TextBlock.TextProperty, new Binding(nameof(Conversation.Alias)));
        name.SetValue(TextBlock.TextWrappingProperty, TextWrapping.Wrap);
        name.SetValue(MaxHeightProperty, 34d);
        name.SetValue(TextBlock.TextAlignmentProperty, TextAlignment.Center);
        name.SetValue(TextBlock.TextTrimmingProperty, TextTrimming.CharacterEllipsis);
        name.SetValue(MaxWidthProperty, 86d);
        name.SetValue(MarginProperty, new Thickness(0, 5, 0, 0));
        tile.AppendChild(name);
        var template = new DataTemplate { VisualTree = tile };
        var selected = new DataTrigger
        {
            Binding = new Binding(nameof(ListBoxItem.IsSelected)) { RelativeSource = new RelativeSource(RelativeSourceMode.FindAncestor, typeof(ListBoxItem), 1) },
            Value = true
        };
        selected.Setters.Add(new Setter(VisibilityProperty, Visibility.Visible, "SelectedRing"));
        template.Triggers.Add(selected);
        _targets.ItemTemplate = template;
        var style = new Style(typeof(ListBoxItem));
        style.Setters.Add(new Setter(PaddingProperty, new Thickness(4)));
        style.Setters.Add(new Setter(HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch));
        style.Setters.Add(new Setter(VerticalContentAlignmentProperty, VerticalAlignment.Stretch));
        style.Setters.Add(new Setter(AutomationProperties.NameProperty, new Binding(nameof(Conversation.Alias))));
        style.Setters.Add(new Setter(ToolTipProperty, new Binding(nameof(Conversation.Alias))));
        _targets.ItemContainerStyle = style;
    }

    private async Task LoadAsync()
    {
        _loading = true;
        _loadFailed = false;
        UpdateActions();
        try
        {
            var contacts = await Task.Run(_services.Database.GetForwardTargets);
            if (_closed) return;
            _contacts = contacts;
            var current = contacts.ToDictionary(contact => contact.Uuid, StringComparer.Ordinal);
            for (var index = _selected.Count - 1; index >= 0; index--)
            {
                if (current.TryGetValue(_selected[index].Uuid, out var match)) _selected[index] = match;
                else _selected.RemoveAt(index);
            }
            RenderChips();
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            _loadFailed = true;
        }
        finally
        {
            _loading = false;
            if (!_closed) RenderTargets();
        }
    }

    private void RenderTargets()
    {
        var term = _filter.Text.Trim();
        _visible = term.Length == 0 ? _contacts.ToArray() : _contacts.Where(peer =>
            peer.Alias.Contains(term, StringComparison.CurrentCultureIgnoreCase) || peer.Uuid.Contains(term, StringComparison.OrdinalIgnoreCase)).ToArray();
        UpdateVisibleSelection(() => _targets.ItemsSource = _visible);
        UpdateActions();
    }

    private void UpdateVisibleSelection(Action? replaceItems = null)
    {
        _updatingSelection = true;
        try
        {
            replaceItems?.Invoke();
            var ids = _selected.Select(peer => peer.Uuid).ToHashSet(StringComparer.Ordinal);
            var wanted = _visible.Where(peer => ids.Contains(peer.Uuid)).ToHashSet();
            foreach (var removed in _targets.SelectedItems.OfType<Conversation>().Where(peer => !wanted.Contains(peer)).ToArray())
                _targets.SelectedItems.Remove(removed);
            foreach (var added in wanted.Where(peer => !_targets.SelectedItems.Contains(peer))) _targets.SelectedItems.Add(added);
        }
        finally { _updatingSelection = false; }
    }

    private void TargetsSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_updatingSelection || _busy) return;
        foreach (var removed in e.RemovedItems.OfType<Conversation>())
            _selected.RemoveAll(peer => peer.Uuid == removed.Uuid);
        foreach (var added in e.AddedItems.OfType<Conversation>())
        {
            if (_selected.Count >= RecipientLimit) break;
            if (_selected.All(peer => peer.Uuid != added.Uuid)) _selected.Add(added);
        }
        var addedSelection = e.AddedItems.Count > 0;
        _status.Text = "";
        _ = Dispatcher.BeginInvoke(DispatcherPriority.Input, new Action(() =>
        {
            if (_closed) return;
            UpdateVisibleSelection();
            if (addedSelection) _chosen.ScrollToRightEnd();
        }));
        RenderChips();
        UpdateActions();
    }

    private void RemoveRecipient(Conversation contact)
    {
        if (_busy || _loading || !_selected.Remove(contact)) return;
        _status.Text = "";
        UpdateVisibleSelection();
        RenderChips();
        UpdateActions();
        _filter.Focus();
    }

    private void RenderChips()
    {
        _chips.Children.Clear();
        _chosen.Visibility = _selected.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        foreach (var peer in _selected)
        {
            var label = AppStrings.Format("UiForwardRemoveRecipient", peer.Alias);
            var chip = IdentitySetupDialog.ActionButton(label, () => RemoveRecipient(peer), Brushes.Gray);
            chip.Margin = new Thickness(0, 0, 5, 0);
            chip.ToolTip = label;
            AutomationProperties.SetName(chip, label);
            var content = new StackPanel { Orientation = Orientation.Horizontal };
            content.Children.Add(IdentitySetupDialog.Avatar(peer.AvatarPath, peer.Alias, 20, 10));
            content.Children.Add(new TextBlock { Text = peer.Alias, MaxWidth = 130, TextTrimming = TextTrimming.CharacterEllipsis,
                VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(6, 0, 6, 0) });
            content.Children.Add(new TextBlock { Text = "×", VerticalAlignment = VerticalAlignment.Center });
            chip.Content = content;
            _chips.Children.Add(chip);
        }
    }

    private void UpdateActions()
    {
        _targets.Visibility = _visible.Length > 0 && !_loadFailed ? Visibility.Visible : Visibility.Collapsed;
        _empty.Visibility = _targets.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
        _emptyLabel.Text = AppStrings.Get(_loading ? "UiLoading" : _loadFailed ? "UiForwardRecipientsFailed" :
            _contacts.Count == 0 ? "UiForwardNoRecipients" : "UiForwardNoMatches");
        _retry.Visibility = _loadFailed && !_loading ? Visibility.Visible : Visibility.Collapsed;
        _count.Text = AppStrings.Format("UiForwardRecipientCount", _selected.Count, RecipientLimit);
        _clearSearch.Visibility = _filter.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        _filter.IsEnabled = _clearSearch.IsEnabled = _chips.IsEnabled = _optionsButton.IsEnabled = !_busy;
        _targets.IsEnabled = !_busy && !_loading;
        _cancel.IsEnabled = !_cancelRequested;
        _send.IsEnabled = !_busy && !_loading && !_loadFailed && _selected.Count > 0;
    }

    private void ChooseSendOptions()
    {
        if (_busy || _closed) return;
        var dialog = new SendOptionsDialog(_options) { Owner = this };
        if (dialog.ShowDialog() != true) return;
        _options = dialog.Result;
        _optionsSummary.Text = SendOptionsText.Format(_options);
        _optionsSummary.Visibility = _optionsSummary.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
    }

    private void Cancel()
    {
        if (!_busy) { DialogResult = false; return; }
        if (_cancelRequested) return;
        _cancelRequested = true;
        _operation?.Cancel();
        _status.Text = AppStrings.Get("UiForwardCanceling");
        UpdateActions();
    }

    private void OnClosing(object? sender, CancelEventArgs args)
    {
        if (!_busy || _services.IsDisposed) return;
        args.Cancel = true;
        Cancel();
    }

    private async Task SendAsync()
    {
        if (_busy || _loading || _loadFailed || _selected.Count == 0) return;
        var targets = _selected.Select(peer => peer.Uuid).ToArray();
        using var operation = new CancellationTokenSource();
        _operation = operation;
        _busy = true;
        _status.Text = AppStrings.Get("UiForwardTaskQueuing");
        UpdateActions();
        var queued = false;
        try
        {
            queued = await _services.Messaging.ForwardAsync(_peer, _ids, targets, _hideSender.IsChecked == true, _hideCaption.IsChecked == true,
                operation.Token, _options, omitPreview: _omitPreview.IsChecked == true);
        }
        catch (OperationCanceledException) when (operation.IsCancellationRequested) { }
        catch (Exception error) when (error is OperationCanceledException or InvalidOperationException or IOException or ArgumentException or
            System.Security.Cryptography.CryptographicException or Microsoft.Data.Sqlite.SqliteException) { }
        finally { _operation = null; _busy = false; }
        if (_closed) return;
        if (queued || _cancelRequested) { DialogResult = queued; return; }
        _status.Text = AppStrings.Get("UiForwardQueueFailed");
        UpdateActions();
    }

    private static Button IconButton(string icon, string key, Action clicked)
    {
        var label = AppStrings.Get(key);
        var button = new Button { ToolTip = label };
        button.SetResourceReference(StyleProperty, "IconButtonStyle");
        button.SetResourceReference(ContentControl.ContentProperty, icon);
        AutomationProperties.SetName(button, label);
        button.Click += (_, _) => clicked();
        return button;
    }

    private sealed class InitialConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
            value is string { Length: > 0 } name ? StringInfo.GetNextTextElement(name).ToUpperInvariant() : "";

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => throw new NotSupportedException();
    }
}
