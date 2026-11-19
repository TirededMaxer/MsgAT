// SPDX-License-Identifier: AGPL-3.0-only
using System.Globalization;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Threading;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Views;

[System.Diagnostics.CodeAnalysis.SuppressMessage("Design", "CA1001", Justification = "The window Closed handler disposes the media preview.")]
internal sealed class SharedMediaDialog : DialogWindow
{
    public static readonly DependencyProperty GalleryTileExtentProperty = DependencyProperty.Register(
        nameof(GalleryTileExtent), typeof(double), typeof(SharedMediaDialog), new PropertyMetadata(132d));

    public double GalleryTileExtent => (double)GetValue(GalleryTileExtentProperty);

    public static readonly DependencyProperty IsGallerySelectingProperty = DependencyProperty.Register(
        nameof(IsGallerySelecting), typeof(bool), typeof(SharedMediaDialog), new PropertyMetadata(false));

    public bool IsGallerySelecting => (bool)GetValue(IsGallerySelectingProperty);

    private readonly AppServices _services;
    private readonly string _peer;
    private sealed record CategoryTab(SharedMediaCategory Category, string Label);
    private readonly record struct ScrollPage(SharedMediaCategory Category, SharedMediaCursor? Cursor);

    private readonly ListBox _category = new() { HorizontalContentAlignment = HorizontalAlignment.Center };
    private readonly ListBox _list = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch };
    private readonly System.Windows.Controls.TextBox _search = IdentitySetupDialog.Field();
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 8) };
    private readonly Button _retryPage = new() { Visibility = Visibility.Collapsed };
    private readonly ProgressBar _pageProgress = new()
    {
        Height = 3, IsIndeterminate = true, Visibility = Visibility.Hidden, Margin = new Thickness(0, 4, 0, 4)
    };
    private readonly Button _cancelCheck = new() { Visibility = Visibility.Collapsed };
    private CancellationTokenSource? _attachmentCheck;
    private readonly Button _chooseDate = new();
    private readonly Button _allDates = new() { Visibility = Visibility.Collapsed };
    private readonly TextBlock _dateStatus = new() { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(8, 0, 8, 0) };
    private readonly Button _newer = new();
    private readonly Button _older = new();
    private readonly Button _open = new();
    private readonly Button _save = new();
    private readonly Button _jump = new();
    private readonly Button _select = new();
    private readonly Button _forwardSelection = new() { Visibility = Visibility.Collapsed };
    private readonly Button _saveSelection = new() { Visibility = Visibility.Collapsed };
    private readonly DockPanel _saveProgressPanel = new() { Visibility = Visibility.Collapsed };
    private readonly TextBlock _saveProgressText = new();
    private readonly ProgressBar _saveProgressBar = new() { Height = 4, Margin = new Thickness(0, 4, 0, 0) };
    private readonly Button _cancelSave = new();
    private readonly DispatcherTimer _saveProgressTimer = new() { Interval = TimeSpan.FromMilliseconds(200) };
    private ObjectPreparation? _savePreparation;
    private readonly TextBlock _selectedCount = new() { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(4) };
    private readonly MenuItem _tileOpen = new();
    private readonly MenuItem _tileSave = new();
    private readonly MenuItem _tileJump = new();
    private readonly ContextMenu _tileMenu = new();
    private readonly DockPanel _browser = new();
    private readonly Grid _viewer = new() { IsHitTestVisible = false };
    private readonly DispatcherTimer _refresh = new() { Interval = TimeSpan.FromMilliseconds(160) };
    private readonly DispatcherTimer _searchDelay = new() { Interval = TimeSpan.FromMilliseconds(220) };
    private readonly Dictionary<SharedMediaCategory, List<SharedMediaCursor?>> _categoryPages = new();
    private readonly Dictionary<ScrollPage, double> _scrollPositions = new();
    private ScrollPage? _displayedScrollPage;
    private SharedMediaCategory? _layoutCategory;
    private SharedMediaPage? _page;
    private MediaOverlay? _preview;
    private string? _previewId;
    private IInputElement? _focusBeforePreview;
    private bool _closed, _loading, _reload, _opening;
    private bool _selecting, _saving;
    private bool _restoringSelection;
    private readonly HashSet<string> _selectedAcrossPages = new(StringComparer.Ordinal);
    private int _pageVersion;
    private int _previewGeneration;
    private string _query = "";
    private DateTime? _selectedDay;
    private SharedMediaCursor? _firstCursor;
    private CancellationTokenSource? _pageWork;

    public string? SelectedId { get; private set; }

    public SharedMediaDialog(AppServices services, string peer, SharedMediaCategory initialCategory = SharedMediaCategory.Media)
    {
        _services = services;
        _peer = peer;
        Title = AppStrings.Get("UiSharedMedia");
        Width = 660;
        Height = 570;
        MinWidth = 450;
        MinHeight = 340;
        ResizeMode = ResizeMode.CanResize;
        SetResourceReference(BackgroundProperty, "MessagingChromeBrush");
        _category.SetResourceReference(Control.BackgroundProperty, "MessagingSidebarBrush");
        _list.SetResourceReference(Control.BackgroundProperty, "MessagingTimelineBrush");
        _list.SetResourceReference(Control.BorderBrushProperty, "MessagingDividerBrush");

        var header = new StackPanel();
        var searchLabel = new Label { Content = AppStrings.Get("UiSearchSharedMedia"), Target = _search };
        _search.MaxLength = 256;
        AutomationProperties.SetName(_search, AppStrings.Get("UiSearchSharedMedia"));
        header.Children.Add(searchLabel);
        header.Children.Add(_search);
        var dateControls = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 4, 0, 4) };
        _chooseDate.Content = new TextBlock { Text = "\uE787", FontFamily = new System.Windows.Media.FontFamily("Segoe MDL2 Assets") };
        _chooseDate.ToolTip = AppStrings.Get("UiJumpToDate");
        AutomationProperties.SetName(_chooseDate, AppStrings.Get("UiJumpToDate"));
        _allDates.Content = AppStrings.Get("UiSharedMediaAllDates");
        dateControls.Children.Add(_chooseDate);
        dateControls.Children.Add(_dateStatus);
        dateControls.Children.Add(_allDates);
        header.Children.Add(dateControls);
        var categoryPanel = new FrameworkElementFactory(typeof(UniformGrid));
        categoryPanel.SetValue(UniformGrid.RowsProperty, 1);
        _category.ItemsPanel = new ItemsPanelTemplate(categoryPanel);
        CategoryTab[] categories =
        {
            new CategoryTab(SharedMediaCategory.Media, AppStrings.Get("UiSharedMediaMedia")),
            new CategoryTab(SharedMediaCategory.Photos, AppStrings.Get("UiSharedMediaPhotos")),
            new CategoryTab(SharedMediaCategory.Videos, AppStrings.Get("UiSharedMediaVideos")),
            new CategoryTab(SharedMediaCategory.Files, AppStrings.Get("UiFiles")),
            new CategoryTab(SharedMediaCategory.Links, AppStrings.Get("UiSharedMediaLinks")),
            new CategoryTab(SharedMediaCategory.Audio, AppStrings.Get("UiAudio"))
        };
        _category.ItemsSource = categories;
        _category.DisplayMemberPath = nameof(CategoryTab.Label);
        AutomationProperties.SetName(_category, Title);
        header.Children.Add(_category);
        var feedback = new DockPanel();
        _cancelCheck.Content = AppStrings.Get("UiCancel");
        _cancelCheck.Click += (_, _) => _attachmentCheck?.Cancel();
        DockPanel.SetDock(_cancelCheck, Dock.Right);
        feedback.Children.Add(_cancelCheck);
        _retryPage.Content = AppStrings.Get("UiRetry");
        _retryPage.Click += async (_, _) => await LoadAsync();
        DockPanel.SetDock(_retryPage, Dock.Right);
        feedback.Children.Add(_retryPage);
        feedback.Children.Add(_status);
        header.Children.Add(feedback);
        AutomationProperties.SetName(_pageProgress, AppStrings.Get("UiLoading"));
        header.Children.Add(_pageProgress);
        DockPanel.SetDock(header, Dock.Top);
        _browser.Children.Add(header);
        var footer = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        var close = new Button { Content = AppStrings.Get("UiClose"), IsCancel = true };
        _newer.Content = AppStrings.Get("UiMediaNewer");
        _older.Content = AppStrings.Get("UiMediaOlder");
        _save.Content = AppStrings.Get("UiSave");
        _forwardSelection.Content = AppStrings.Get("UiForward");
        _saveSelection.Content = AppStrings.Get("UiSharedMediaSaveSelection");
        footer.Children.Add(_selectedCount);
        foreach (var button in new[] { _newer, _older, _jump, _open, _save, _select, _forwardSelection, _saveSelection, close })
        {
            button.Margin = new Thickness(3);
            footer.Children.Add(button);
        }
        var footerPanel = new Border
        {
            Child = footer, BorderThickness = new Thickness(0, 1, 0, 0),
            Padding = new Thickness(0, 6, 0, 0), Margin = new Thickness(0, 8, 0, 0)
        };
        footerPanel.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        DockPanel.SetDock(footerPanel, Dock.Bottom);
        _browser.Children.Add(footerPanel);
        _cancelSave.Content = AppStrings.Get("UiPreparationCancel");
        _cancelSave.Margin = new Thickness(8, 0, 0, 0);
        _cancelSave.Click += (_, _) =>
        {
            _savePreparation?.Cancel();
            UpdateSaveProgress();
        };
        DockPanel.SetDock(_cancelSave, Dock.Right);
        _saveProgressPanel.Children.Add(_cancelSave);
        var saveProgressDetails = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        saveProgressDetails.Children.Add(_saveProgressText);
        saveProgressDetails.Children.Add(_saveProgressBar);
        _saveProgressPanel.Children.Add(saveProgressDetails);
        DockPanel.SetDock(_saveProgressPanel, Dock.Bottom);
        _browser.Children.Add(_saveProgressPanel);
        _saveProgressTimer.Tick += (_, _) => UpdateSaveProgress();
        ScrollViewer.SetHorizontalScrollBarVisibility(_list, ScrollBarVisibility.Disabled);
        VirtualizingPanel.SetVirtualizationMode(_list, VirtualizationMode.Recycling);
        _browser.Children.Add(_list);

        var root = new Grid { Margin = new Thickness(16) };
        root.Children.Add(_browser);
        root.Children.Add(_viewer);
        Content = root;
        _category.SelectedItem = categories.FirstOrDefault(candidate => candidate.Category == initialCategory) ?? categories[0];
        ConfigureLayout();
        _category.SelectionChanged += async (_, _) => await ChangePageAsync();
        _chooseDate.Click += async (_, _) =>
        {
            var picker = new ChatHistoryDialog(_selectedDay ?? DateTime.Today, throughDate: true) { Owner = this };
            if (picker.ShowDialog() == true && !_closed && picker.DayStart is { } limit && picker.SelectedDay is { } date)
                await ChangeDateAsync(date, new SharedMediaCursor(limit, ""));
        };
        _allDates.Click += async (_, _) => await ChangeDateAsync(null, null);
        _newer.Click += async (_, _) =>
        {
            if (Pages.Count <= 1) return;
            Pages.RemoveAt(Pages.Count - 1);
            await ChangePageAsync(keepSelection: true);
        };
        _older.Click += async (_, _) =>
        {
            if (_page?.Older is not { } cursor) return;
            Pages.Add(cursor);
            await ChangePageAsync(keepSelection: true);
        };
        close.Click += (_, _) => Close();
        _open.Click += async (_, _) => await OpenAsync(save: false);
        _save.Click += async (_, _) => await OpenAsync(save: true);
        _jump.Click += (_, _) => Jump();
        _jump.SetResourceReference(ContentControl.ContentProperty, "UiShowInChat");
        _tileSave.SetResourceReference(HeaderedItemsControl.HeaderProperty, "UiSaveMediaAttachment");
        _tileJump.SetResourceReference(HeaderedItemsControl.HeaderProperty, "UiShowInChat");
        _tileMenu.Items.Add(_tileOpen);
        _tileMenu.Items.Add(_tileSave);
        _tileMenu.Items.Add(_tileJump);
        var selectTile = new MenuItem { Header = AppStrings.Get("UiSelect") };
        selectTile.Click += (_, _) =>
        {
            if (_loading || _opening || _list.SelectedItem is not MessageItem item) return;
            SetSelectionMode(true);
            _list.SelectedItems.Add(item);
        };
        _tileMenu.Items.Add(selectTile);
        _tileOpen.Click += async (_, _) => await OpenAsync(save: false);
        _tileSave.Click += async (_, _) => await OpenAsync(save: true);
        _tileJump.Click += (_, _) => Jump();
        _list.PreviewMouseRightButtonDown += (_, args) =>
        {
            if (_list.ContextMenu != _tileMenu || args.OriginalSource is not DependencyObject source) return;
            var tile = ItemsControl.ContainerFromElement(_list, source) as ListBoxItem;
            _list.SelectedItem = tile?.DataContext;
        };
        _list.ContextMenuOpening += (_, args) =>
        {
            if (_list.SelectedItem is not MessageItem) args.Handled = true;
            else UpdateActions();
        };
        _list.SelectionChanged += SelectionChanged;
        _select.Click += (_, _) => SetSelectionMode(!_selecting);
        _forwardSelection.Click += (_, _) =>
        {
            if (SelectedIds() is { Length: > 0 } ids && !_saving && !_loading &&
                new ForwardMessagesDialog(_services, _peer, ids) { Owner = this }.ShowDialog() == true)
            {
                SetSelectionMode(false);
                TransientNotice.Show(this, AppStrings.Get("UiForwardingQueued"));
            }
        };
        _saveSelection.Click += async (_, _) => await SaveSelectionAsync();
        _list.SizeChanged += (_, _) => UpdateGalleryTileExtent();
        _list.MouseDoubleClick += async (_, args) =>
        {
            if (_selecting || args.ChangedButton != MouseButton.Left || args.OriginalSource is not DependencyObject source ||
                ItemsControl.ContainerFromElement(_list, source) is not ListBoxItem)
                return;
            await OpenAsync(save: SelectedCategory == SharedMediaCategory.Files);
        };
        _list.KeyDown += async (_, args) =>
        {
            if (_selecting || args.Key != Key.Enter) return;
            args.Handled = true;
            if (SelectedCategory == SharedMediaCategory.Links) Jump();
            else await OpenAsync(save: SelectedCategory == SharedMediaCategory.Files);
        };
        PreviewKeyDown += (_, args) =>
        {
            if (_preview is { } preview)
            {
                preview.HandleViewShortcut(args);
                return;
            }
            if (args.Key == Key.Escape && _selecting && !_saving)
            {
                args.Handled = true;
                SetSelectionMode(false);
                return;
            }
            if (args.Key == Key.F && Keyboard.Modifiers == ModifierKeys.Control)
            {
                args.Handled = true;
                _search.Focus();
                return;
            }
        };
        _refresh.Tick += async (_, _) => { _refresh.Stop(); await LoadAsync(); };
        _searchDelay.Tick += async (_, _) => { _searchDelay.Stop(); await LoadAsync(); };
        _search.TextChanged += (_, _) =>
        {
            var query = _search.Text.Trim();
            if (query == _query) return;
            _query = query;
            if (_selecting) SetSelectionMode(false);
            _categoryPages.Clear();
            _scrollPositions.Clear();
            _displayedScrollPage = null;
            _pageVersion++;
            _pageWork?.Cancel();
            _page = null;
            _list.ItemsSource = null;
            _status.Text = "";
            _retryPage.Visibility = Visibility.Collapsed;
            UpdateActions();
            _searchDelay.Stop();
            _searchDelay.Start();
        };
        _services.Database.Changed += DatabaseChanged;
        _services.Messaging.AttachmentProgressChanged += AttachmentProgressChanged;
        Loaded += async (_, _) => await LoadAsync();
        Closed += (_, _) =>
        {
            _closed = true;
            _saveProgressTimer.Stop();
            _savePreparation?.Cancel();
            _attachmentCheck?.Cancel();
            _pageWork?.Cancel();
            _refresh.Stop();
            _searchDelay.Stop();
            _services.Database.Changed -= DatabaseChanged;
            _services.Messaging.AttachmentProgressChanged -= AttachmentProgressChanged;
            ClosePreview();
        };
        UpdateActions();
    }

    private SharedMediaCategory SelectedCategory => (_category.SelectedItem as CategoryTab)?.Category ?? SharedMediaCategory.Media;

    private static bool IsVisualCategory(SharedMediaCategory category) =>
        category is SharedMediaCategory.Media or SharedMediaCategory.Photos or SharedMediaCategory.Videos;

    private string? SelectedMessageId => _list.SelectedItem switch
    {
        MessageItem item => item.MessageId,
        SharedLink link => link.Id,
        _ => null
    };

    private List<SharedMediaCursor?> Pages
    {
        get
        {
            var category = SelectedCategory;
            if (!_categoryPages.TryGetValue(category, out var pages))
                _categoryPages[category] = pages = [_firstCursor];
            return pages;
        }
    }

    private void ConfigureLayout()
    {
        var category = SelectedCategory;
        if (_layoutCategory == category) return;
        _layoutCategory = category;
        var visual = IsVisualCategory(category);
        _list.ContextMenu = visual && !_selecting ? _tileMenu : null;
        _list.ItemContainerStyle = visual ? CreateTileContainerStyle() : null;
        var panel = new FrameworkElementFactory(visual ? typeof(WrapPanel) : typeof(StackPanel));
        if (visual)
        {
            var extent = new Binding(nameof(GalleryTileExtent)) { Source = this };
            panel.SetBinding(WrapPanel.ItemWidthProperty, extent);
            panel.SetBinding(WrapPanel.ItemHeightProperty, extent);
            UpdateGalleryTileExtent();
        }
        var header = new FrameworkElementFactory(typeof(TextBlock));
        header.SetBinding(TextBlock.TextProperty, new Binding(nameof(CollectionViewGroup.Name)));
        header.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        header.SetValue(TextBlock.FontWeightProperty, FontWeights.SemiBold);
        header.SetValue(MarginProperty, new Thickness(7, 9, 7, 5));
        _list.GroupStyle.Clear();
        _list.GroupStyle.Add(new GroupStyle
        {
            Panel = new ItemsPanelTemplate(new FrameworkElementFactory(typeof(StackPanel))),
            HeaderTemplate = new DataTemplate { VisualTree = header }
        });
        _list.ItemsPanel = new ItemsPanelTemplate(panel);
        _list.ItemTemplate = category == SharedMediaCategory.Links ? CreateLinkTemplate() : CreateItemTemplate(visual);
        ScrollViewer.SetCanContentScroll(_list, false);
    }

    private static Style CreateTileContainerStyle()
    {
        var style = new Style(typeof(ListBoxItem));
        style.Setters.Add(new Setter(Control.HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch));
        style.Setters.Add(new Setter(Control.VerticalContentAlignmentProperty, VerticalAlignment.Stretch));
        style.Setters.Add(new Setter(AutomationProperties.NameProperty, new Binding(nameof(MessageItem.Filename))));
        return style;
    }

    private void UpdateGalleryTileExtent()
    {
        if (!IsVisualCategory(SelectedCategory)) return;
        var horizontalInsets = _list.BorderThickness.Left + _list.BorderThickness.Right +
            _list.Padding.Left + _list.Padding.Right + SystemParameters.VerticalScrollBarWidth;
        var available = _list.ActualWidth - horizontalInsets;
        if (available <= 0) return;
        const double minimumTileWidth = 120;
        var columns = Math.Max(1, (int)(available / minimumTileWidth));
        SetValue(GalleryTileExtentProperty, Math.Floor(available / columns));
    }

    private static DataTemplate CreateLinkTemplate()
    {
        var text = new FrameworkElementFactory(typeof(MessageTextBlock));
        text.SetBinding(MessageTextBlock.MessageTextProperty, new Binding(nameof(SharedLink.Text)));
        text.SetValue(TextBlock.TextWrappingProperty, TextWrapping.Wrap);
        text.SetValue(MarginProperty, new Thickness(6));
        return new DataTemplate { VisualTree = text };
    }

    private DataTemplate CreateItemTemplate(bool visual)
    {
        if (visual)
        {
            var tile = new FrameworkElementFactory(typeof(Grid));
            var picture = new FrameworkElementFactory(typeof(AttachmentPreview));
            picture.SetBinding(AttachmentPreview.MessageProperty, new Binding());
            picture.SetValue(AttachmentPreview.GalleryTileProperty, true);
            picture.SetValue(MarginProperty, new Thickness(2));
            picture.SetBinding(ToolTipProperty, new Binding(nameof(MessageItem.Filename)));
            tile.AppendChild(picture);

            var selectionBadge = new FrameworkElementFactory(typeof(Border));
            selectionBadge.SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Right);
            selectionBadge.SetValue(VerticalAlignmentProperty, VerticalAlignment.Top);
            selectionBadge.SetValue(MarginProperty, new Thickness(7));
            selectionBadge.SetValue(WidthProperty, 24d);
            selectionBadge.SetValue(HeightProperty, 24d);
            selectionBadge.SetValue(Border.CornerRadiusProperty, new CornerRadius(12));
            selectionBadge.SetValue(Border.BorderThicknessProperty, new Thickness(1.5));
            selectionBadge.SetValue(Border.BorderBrushProperty, System.Windows.Media.Brushes.White);
            selectionBadge.SetValue(Border.BackgroundProperty, System.Windows.Media.Brushes.Black);
            selectionBadge.SetValue(OpacityProperty, 0.8d);
            selectionBadge.SetValue(IsHitTestVisibleProperty, false);
            selectionBadge.SetBinding(VisibilityProperty, new Binding(nameof(IsGallerySelecting))
            {
                Source = this,
                Converter = new BooleanToVisibilityConverter()
            });
            var check = new FrameworkElementFactory(typeof(TextBlock));
            check.SetValue(TextBlock.TextProperty, "\uE73E");
            check.SetValue(TextBlock.FontFamilyProperty, new System.Windows.Media.FontFamily("Segoe MDL2 Assets"));
            check.SetValue(TextBlock.FontSizeProperty, 13d);
            check.SetValue(TextBlock.ForegroundProperty, System.Windows.Media.Brushes.White);
            check.SetValue(HorizontalAlignmentProperty, HorizontalAlignment.Center);
            check.SetValue(VerticalAlignmentProperty, VerticalAlignment.Center);
            check.SetBinding(VisibilityProperty, new Binding(nameof(ListBoxItem.IsSelected))
            {
                RelativeSource = new RelativeSource(RelativeSourceMode.FindAncestor, typeof(ListBoxItem), 1),
                Converter = new BooleanToVisibilityConverter()
            });
            selectionBadge.AppendChild(check);
            tile.AppendChild(selectionBadge);
            return new DataTemplate { VisualTree = tile };
        }
        var labels = new FrameworkElementFactory(typeof(StackPanel));
        labels.SetValue(MarginProperty, new Thickness(4));
        labels.SetValue(VerticalAlignmentProperty, VerticalAlignment.Center);
        var filename = new FrameworkElementFactory(typeof(TextBlock));
        filename.SetBinding(TextBlock.TextProperty, new Binding(nameof(MessageItem.Filename)));
        filename.SetValue(TextBlock.TextTrimmingProperty, TextTrimming.CharacterEllipsis);
        filename.SetBinding(ToolTipProperty, new Binding(nameof(MessageItem.Filename)));
        labels.AppendChild(filename);
        var date = new FrameworkElementFactory(typeof(TextBlock));
        date.SetBinding(TextBlock.TextProperty, new Binding(nameof(MessageItem.TimeText)));
        date.SetValue(TextBlock.OpacityProperty, .7);
        labels.AppendChild(date);
        var transfer = new FrameworkElementFactory(typeof(TextBlock));
        transfer.SetBinding(TextBlock.TextProperty, new Binding(nameof(MessageItem.TransferText)));
        transfer.SetValue(TextBlock.OpacityProperty, .7);
        labels.AppendChild(transfer);
        var progressBar = new FrameworkElementFactory(typeof(System.Windows.Controls.ProgressBar));
        progressBar.SetValue(System.Windows.Controls.ProgressBar.MaximumProperty, 100d);
        progressBar.SetValue(HeightProperty, 3d);
        progressBar.SetValue(MarginProperty, new Thickness(0, 4, 0, 0));
        progressBar.SetValue(IsHitTestVisibleProperty, false);
        progressBar.SetBinding(System.Windows.Controls.ProgressBar.ValueProperty,
            new Binding(nameof(MessageItem.TransferPercent)) { Mode = BindingMode.OneWay });
        progressBar.SetBinding(VisibilityProperty, new Binding(nameof(MessageItem.HasTransferProgress))
        {
            Converter = new BooleanToVisibilityConverter()
        });
        progressBar.SetBinding(AutomationProperties.NameProperty, new Binding(nameof(MessageItem.TransferText)));
        labels.AppendChild(progressBar);
        return new DataTemplate { VisualTree = labels };
    }

    private void UpdateActions()
    {
        _pageProgress.Visibility = _loading ? Visibility.Visible : Visibility.Hidden;
        var ready = !_loading && !_opening && !_saving;
        _search.IsEnabled = _category.IsEnabled = _chooseDate.IsEnabled = _allDates.IsEnabled = !_opening && !_saving;
        _list.IsEnabled = !_saving;
        _select.Content = AppStrings.Get(_selecting ? "UiCancel" : "UiSelect");
        _select.IsEnabled = ready;
        var count = _selectedAcrossPages.Count;
        _selectedCount.Text = _selecting ? AppStrings.Format("UiMessageSelectionCount", count) : "";
        _forwardSelection.Visibility = _saveSelection.Visibility = _selecting ? Visibility.Visible : Visibility.Collapsed;
        _forwardSelection.IsEnabled = _saveSelection.IsEnabled = ready && count > 0;
        var selected = _list.SelectedItem as MessageItem;
        var links = SelectedCategory == SharedMediaCategory.Links;
        _open.Visibility = _save.Visibility = links || _selecting ? Visibility.Collapsed : Visibility.Visible;
        _newer.IsEnabled = ready && Pages.Count > 1;
        _older.IsEnabled = ready && _page?.Older is not null;
        var opensPreview = selected is { } media && (media.IsImage || media.IsVideo || media.IsAudio);
        var action = !opensPreview && selected is { } item && (item.CanPauseDownload || item.CanRequestDownload)
            ? item.CanRetryDownload ? "UiRetry" : item.DownloadActionKey : "UiPreviewMediaAttachment";
        _open.SetResourceReference(ContentControl.ContentProperty, action);
        _open.IsEnabled = ready && (selected?.CanPauseDownload == true || selected?.CanRequestDownload == true ||
            (selected is { IsAvailable: true } && opensPreview));
        _save.IsEnabled = ready && selected is { IsAvailable: true };
        _jump.IsEnabled = ready && !_selecting && SelectedMessageId is not null;
        _tileOpen.SetResourceReference(HeaderedItemsControl.HeaderProperty, action);
        _tileOpen.IsEnabled = _open.IsEnabled;
        _tileSave.IsEnabled = _save.IsEnabled;
        _tileJump.IsEnabled = _jump.IsEnabled;
    }

    private void SetSelectionMode(bool enabled)
    {
        _selecting = enabled;
        _selectedAcrossPages.Clear();
        SetValue(IsGallerySelectingProperty, enabled);
        _list.UnselectAll();
        _list.SelectionMode = enabled ? SelectionMode.Multiple : SelectionMode.Single;
        _list.ContextMenu = !enabled && IsVisualCategory(SelectedCategory)
            ? _tileMenu : null;
        UpdateActions();
    }

    private static string? MessageId(object value) => value switch
    {
        MessageItem message => message.MessageId,
        SharedLink link => link.Id,
        _ => null
    };

    private string[] SelectedIds() => _selectedAcrossPages.ToArray();

    private void SelectionChanged(object sender, SelectionChangedEventArgs args)
    {
        if (_restoringSelection) return;
        if (_selecting)
        {
            foreach (var item in args.RemovedItems)
                if (MessageId(item) is { } id) _selectedAcrossPages.Remove(id);
            var overflow = false;
            _restoringSelection = true;
            try
            {
                foreach (var item in args.AddedItems)
                {
                    if (MessageId(item) is not { } id || _selectedAcrossPages.Contains(id)) continue;
                    if (_selectedAcrossPages.Count < 100) _selectedAcrossPages.Add(id);
                    else
                    {
                        _list.SelectedItems.Remove(item);
                        overflow = true;
                    }
                }
            }
            finally { _restoringSelection = false; }
            if (overflow) TransientNotice.Show(this, AppStrings.Get("UiMessageSelectionLimit"));
        }
        UpdateActions();
    }

    private void ReplaceItems(IEnumerable<object> items, ScrollPage scrollPage)
    {
        var offset = _scrollPositions.GetValueOrDefault(scrollPage);
        if (_displayedScrollPage == scrollPage && MainWindow.GetScrollViewer(_list) is { } previous)
            offset = previous.VerticalOffset;
        var rows = items.ToArray();
        var view = new ListCollectionView(rows);
        view.GroupDescriptions.Add(new PropertyGroupDescription(null, new MediaMonthConverter()));
        var selected = SelectedMessageId;
        _restoringSelection = true;
        try
        {
            _list.ItemsSource = view;
            foreach (var item in rows)
            {
                if (MessageId(item) is not { } id) continue;
                if (_selecting && _selectedAcrossPages.Contains(id)) _list.SelectedItems.Add(item);
                else if (!_selecting && id == selected) { _list.SelectedItem = item; break; }
            }
        }
        finally { _restoringSelection = false; }
        _displayedScrollPage = scrollPage;
        var version = _pageVersion;
        Dispatcher.BeginInvoke(DispatcherPriority.Loaded, new Action(() =>
        {
            if (!_closed && version == _pageVersion && ReferenceEquals(_list.ItemsSource, view))
                MainWindow.GetScrollViewer(_list)?.ScrollToVerticalOffset(offset);
        }));
        UpdateActions();
    }

    private sealed class MediaMonthConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            var time = value switch
            {
                MessageItem item => item.Source.Timestamp,
                SharedLink link => link.Timestamp,
                _ => 0
            };
            return time is >= 0 and <= 253402214399999L
                ? DateTimeOffset.FromUnixTimeMilliseconds(time).LocalDateTime.ToString("Y", AppStrings.InterfaceCulture)
                : "";
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) => Binding.DoNothing;
    }

    private async Task SaveSelectionAsync()
    {
        var ids = SelectedIds();
        if (_closed || _loading || _opening || _saving || ids.Length == 0) return;
        using var preparation = new ObjectPreparation();
        _savePreparation = preparation;
        _saving = true;
        UpdateActions();
        _saveProgressPanel.Visibility = Visibility.Visible;
        UpdateSaveProgress();
        _saveProgressTimer.Start();
        try
        {
            await _services.Messaging.Actions.SaveAsync(_peer, ids, preparation: preparation);
            if (!_closed)
            {
                SetSelectionMode(false);
                TransientNotice.Show(this, AppStrings.Get("UiSharedMediaSaved"));
            }
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or ArgumentException
                                      or Microsoft.Data.Sqlite.SqliteException or System.Security.Cryptography.CryptographicException)
        {
            if (!_closed && !preparation.WasCancelled) _status.Text = AppStrings.Get("UiSharedMediaSaveFailed");
        }
        catch (OperationCanceledException) when (_closed || _services.IsDisposed || preparation.WasCancelled) { }
        finally
        {
            _saveProgressTimer.Stop();
            _savePreparation = null;
            _saveProgressPanel.Visibility = Visibility.Collapsed;
            _saving = false;
            if (!_closed) UpdateActions();
        }
    }

    private void UpdateSaveProgress()
    {
        if (_savePreparation is not { } preparation) return;
        var progress = preparation.Snapshot();
        var row = new PendingPreparation("", _peer, null, 1, false, progress, SavingMessages: true);
        _saveProgressText.Text = row.Status;
        _saveProgressBar.Maximum = Math.Max(progress.Total, 1);
        _saveProgressBar.Value = progress.Completed;
        _saveProgressBar.IsIndeterminate = row.Indeterminate;
        _cancelSave.Visibility = progress.CanCancel ? Visibility.Visible : Visibility.Collapsed;
    }

    private Task ChangeDateAsync(DateTime? day, SharedMediaCursor? cursor)
    {
        if (_selectedDay == day) return Task.CompletedTask;
        _selectedDay = day;
        _firstCursor = cursor;
        _dateStatus.Text = day is { } date
            ? AppStrings.Format("UiSharedMediaThroughDate", date.ToString("d", System.Globalization.CultureInfo.CurrentCulture))
            : "";
        _allDates.Visibility = day is null ? Visibility.Collapsed : Visibility.Visible;
        _categoryPages.Clear();
        _scrollPositions.Clear();
        _displayedScrollPage = null;
        return ChangePageAsync();
    }

    private Task ChangePageAsync(bool keepSelection = false)
    {
        if (_displayedScrollPage is { } displayed && MainWindow.GetScrollViewer(_list) is { } scroll)
        {
            if (!_scrollPositions.ContainsKey(displayed) && _scrollPositions.Count >= 24)
                _scrollPositions.Remove(_scrollPositions.Keys.First());
            _scrollPositions[displayed] = scroll.VerticalOffset;
        }
        _displayedScrollPage = null;
        if (_selecting && !keepSelection) SetSelectionMode(false);
        _searchDelay.Stop();
        _pageVersion++;
        _pageWork?.Cancel();
        _page = null;
        _restoringSelection = true;
        try { _list.ItemsSource = null; }
        finally { _restoringSelection = false; }
        _status.Text = "";
        _retryPage.Visibility = Visibility.Collapsed;
        ConfigureLayout();
        return LoadAsync();
    }

    private void DatabaseChanged(object? sender, EventArgs args)
    {
        if (_closed || Dispatcher.HasShutdownStarted || args is MessageDeliveryChangedEventArgs or VisibleMessagesReadEventArgs) return;
        Dispatcher.BeginInvoke(() => { if (!_closed && !_refresh.IsEnabled) _refresh.Start(); });
    }

    private void AttachmentProgressChanged(object? sender, AttachmentProgress progress)
    {
        if (progress.PreviewReference is not null || _closed || Dispatcher.HasShutdownStarted) return;
        Dispatcher.BeginInvoke(() =>
        {
            if (_closed) return;
            if (_list.Items.OfType<MessageItem>().FirstOrDefault(value => value.MessageId == progress.MessageId) is { } item)
            {
                var previous = (item.DownloadActionKey, item.CanRetryDownload);
                item.UpdateProgress(progress);
                if (ReferenceEquals(_list.SelectedItem, item) && previous != (item.DownloadActionKey, item.CanRetryDownload))
                    UpdateActions();
            }
            _preview?.UpdateDownloadProgress(progress);
            if (progress.Phase is (AttachmentPhase.Complete or AttachmentPhase.Unavailable) && !_refresh.IsEnabled) _refresh.Start();
        });
    }

    private async Task LoadAsync()
    {
        if (_closed || _services.IsDisposed || _searchDelay.IsEnabled) return;
        if (_loading) { _reload = true; return; }
        _loading = true;
        _retryPage.Visibility = Visibility.Collapsed;
        UpdateActions();
        try
        {
            do
            {
                _reload = false;
                var version = _pageVersion;
                var category = SelectedCategory;
                var cursor = Pages[^1];
                var query = _query;
                var before = _firstCursor;
                var viewer = _preview;
                using var work = new CancellationTokenSource();
                _pageWork = work;
                (SharedMediaPage Page, SharedMediaItem? Preview, VisualMediaNeighbors? Neighbors) result;
                try
                {
                    result = await Task.Run(() =>
                    {
                        var page = _services.Objects.SharedMedia(_peer, category, cursor, query, work.Token);
                        work.Token.ThrowIfCancellationRequested();
                        var preview = viewer is null ? null : _services.Objects.SharedMediaItemAt(_peer, viewer.MessageId);
                        var neighbors = preview is not null && EncryptedObjectStore.IsAlbumMedia(preview.Attachment.Mime)
                            ? _services.Objects.GetVisualMediaNeighbors(_peer, new SharedMediaCursor(preview.Timestamp, preview.Id),
                                category, query, before) : null;
                        return (page, preview, neighbors);
                    });
                }
                catch (OperationCanceledException) when (work.IsCancellationRequested)
                {
                    continue;
                }
                catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
                {
                    if (!_closed && version == _pageVersion && ReferenceEquals(_preview, viewer))
                    {
                        _status.Text = AppStrings.Get("UiSharedMediaLoadFailed");
                        _retryPage.Visibility = Visibility.Visible;
                        viewer?.SetRefreshFailure(() => { _ = LoadAsync(); });
                    }
                    continue;
                }
                finally
                {
                    _pageWork = null;
                }
                if (_closed) return;
                if (version != _pageVersion) continue;
                if (viewer is { IsClosing: false } && ReferenceEquals(_preview, viewer))
                {
                    if (result.Preview is { } active && active.Reference == viewer.Reference)
                    {
                        if (viewer.IsAvailable != active.Attachment.Available) InstallPreview(active, result.Neighbors);
                        else
                        {
                            viewer.SetRefreshFailure(null);
                            viewer.SetCaption(active.Attachment.Caption);
                            viewer.SetNavigation(PreviewNavigation(active, result.Neighbors));
                            if (!viewer.IsAvailable) viewer.UpdatePendingDownload(ToMessage(active));
                        }
                    }
                    else ClosePreview();
                }
                if (category == SharedMediaCategory.Links)
                {
                    var links = result.Page.Links;
                    if (_page is null || !_page.Links.Select(link => (link.Id, link.Timestamp, link.Text))
                            .SequenceEqual(links.Select(link => (link.Id, link.Timestamp, link.Text))))
                    {
                        ReplaceItems(links, new ScrollPage(category, cursor));
                    }
                }
                else if (_page is null || !_page.Items.SequenceEqual(result.Page.Items))
                {
                    var items = result.Page.Items.Select(ToMessage).ToArray();
                    ReplaceItems(items, new ScrollPage(category, cursor));
                }
                _page = result.Page;
                _retryPage.Visibility = Visibility.Collapsed;
                _status.Text = result.Page.Items.Count == 0 && result.Page.Links.Count == 0
                    ? AppStrings.Get(query.Length == 0 ? "UiSharedMediaEmpty" : "UiSharedMediaNoMatches")
                    : "";
            } while (_reload && !_closed && !_services.IsDisposed && !_searchDelay.IsEnabled);
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_closed)
            {
                _status.Text = AppStrings.Get("UiSharedMediaLoadFailed");
                _retryPage.Visibility = Visibility.Visible;
            }
        }
        finally
        {
            _loading = false;
            if (!_closed)
            {
                UpdateActions();
                if (_reload && !_services.IsDisposed && !_searchDelay.IsEnabled && !_refresh.IsEnabled)
                    _refresh.Start();
            }
        }
    }

    private MessageItem ToMessage(SharedMediaItem item)
    {
        var source = new ChatMessage(item.Id, _peer, item.IsMine, item.Reference, "attachment", item.Timestamp);
        return new MessageItem(source, attachmentInfo: item.Attachment,
            openMedia: () => _services.Objects.OpenRead(item.Reference),
            progress: _services.Messaging.GetAttachmentProgress(item.Id), thumbnailStore: _services.Objects);
    }

    private async Task OpenAsync(bool save)
    {
        if (_closed || _loading || _opening || _selecting || _list.SelectedItem is not MessageItem selected) return;
        _opening = true;
        UpdateActions();
        var version = _pageVersion;
        try
        {
            var current = await Task.Run(() => _services.Objects.SharedMediaItemAt(_peer, selected.MessageId));
            if (_closed || version != _pageVersion || SelectedMessageId != selected.MessageId) return;
            if (current is null || current.Reference != selected.Content)
            {
                _status.Text = AppStrings.Get("UiFileDetailsUnavailable");
                if (!_refresh.IsEnabled) _refresh.Start();
                return;
            }
            var message = ToMessage(current);
            if (!save && (message.IsImage || message.IsVideo || message.IsAudio))
            {
                await PresentPreviewAsync(current, version);
                return;
            }
            if (!save && (message.CanPauseDownload || message.CanRequestDownload))
            {
                // Keep an earlier click from reversing an action already committed by another view.
                var requestedPause = selected.CanPauseDownload;
                var changed = requestedPause != message.CanPauseDownload ||
                    await (requestedPause
                        ? _services.Messaging.PauseAttachmentDownloadAsync(_peer, current.Id)
                        : _services.Messaging.RequestAttachmentDownloadAsync(_peer, current.Id));
                if (!_closed && version == _pageVersion) await LoadAsync();
                if (!changed && !_closed && version == _pageVersion && SelectedMessageId == current.Id)
                    _status.Text = AppStrings.Get("UiFileDetailsUnavailable");
                return;
            }
            if (!current.Attachment.Available)
            {
                _status.Text = AppStrings.Get("UiFileDetailsUnavailable");
                if (!_refresh.IsEnabled) _refresh.Start();
                return;
            }
            if (save)
            {
                var complete = current.IsMine || await CheckCiphertextAsync(current.Reference);
                if (_closed || version != _pageVersion || SelectedMessageId != current.Id) return;
                if (!complete)
                {
                    if (await _services.Messaging.ReplaceAttachmentCacheAsync(_peer, current.Id, current.Reference) &&
                        !_closed && version == _pageVersion && SelectedMessageId == current.Id)
                        _status.Text = AppStrings.Get("UiDownloadQueued");
                    return;
                }
                await AttachmentExporter.ExportAsync(this, message, () => !_closed && _services.Database.CanExportAttachment(message.Source),
                    preferredFolder: _services.Settings.Snapshot.AttachmentExportFolder);
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_closed && version == _pageVersion && SelectedMessageId == selected.MessageId)
                _status.Text = AppStrings.Get("UiSharedMediaLoadFailed");
        }
        finally
        {
            _opening = false;
            if (!_closed) UpdateActions();
        }
    }

    private async Task PresentPreviewAsync(SharedMediaItem requested, int pageVersion)
    {
        var generation = _previewGeneration;
        var previousViewer = _preview;
        var category = SelectedCategory;
        var query = _query;
        var before = _firstCursor;
        var result = await Task.Run(() =>
        {
            var media = requested.Attachment.Mime;
            var neighbors = EncryptedObjectStore.IsAlbumMedia(media)
                ? _services.Objects.GetVisualMediaNeighbors(_peer, new SharedMediaCursor(requested.Timestamp, requested.Id),
                    category, query, before) : null;
            var current = _services.Objects.SharedMediaItemAt(_peer, requested.Id);
            return (Neighbors: neighbors, Current: current);
        });
        if (_closed || generation != _previewGeneration || pageVersion != _pageVersion ||
            previousViewer?.IsClosing == true || previousViewer is null && SelectedMessageId != requested.Id) return;
        if (result.Current is not { } current || current.Reference != requested.Reference)
        {
            TransientNotice.Show(this, AppStrings.Get("UiFileDetailsUnavailable"));
            if (!_refresh.IsEnabled) _refresh.Start();
            return;
        }
        if (current.Attachment.Available && !current.IsMine && !await CheckCiphertextAsync(current.Reference))
        {
            if (!IsCurrentRequest()) return;
            if (!await _services.Messaging.ReplaceAttachmentCacheAsync(_peer, current.Id, current.Reference)) return;
            var pending = await Task.Run(() => _services.Objects.SharedMediaItemAt(_peer, requested.Id));
            if (pending is not { Attachment.Available: false } || pending.Reference != requested.Reference) return;
            current = pending;
        }
        if (IsCurrentRequest()) InstallPreview(current, result.Neighbors);

        bool IsCurrentRequest() => !_closed && generation == _previewGeneration && pageVersion == _pageVersion &&
            (previousViewer is null ? SelectedMessageId == requested.Id : !previousViewer.IsClosing);
    }

    private MediaOverlay.PreviewNavigation? PreviewNavigation(SharedMediaItem current, VisualMediaNeighbors? adjacent) =>
        adjacent is { } neighbors && (neighbors.Newer is not null || neighbors.Older is not null)
            ? new MediaOverlay.PreviewNavigation(neighbors.Newer is not null, neighbors.Older is not null,
                delta => MovePreview(current, delta)) : null;

    private void InstallPreview(SharedMediaItem current, VisualMediaNeighbors? neighbors)
    {
        var openingPhoto = _preview is null
            ? AttachmentPreview.FindIn(_list, current.Id)?.CapturePhotoThumbnail(_viewer)
            : null;
        var previousFocus = _preview is null ? Keyboard.FocusedElement : _focusBeforePreview;
        ClosePreview(restoreFocus: false);
        _focusBeforePreview = previousFocus;
        _previewId = current.Id;
        var message = ToMessage(current);
        _preview = new MediaOverlay(message, () => ClosePreview(), PreviewNavigation(current, neighbors), showMessage: () =>
        {
            if (!_closed && _previewId == current.Id) JumpTo(current.Id);
        }, changeDownload: async item =>
        {
            if (_closed || _previewId != item.MessageId || _preview?.Reference != item.Content) return false;
            var changed = await (item.CanPauseDownload
                ? _services.Messaging.PauseAttachmentDownloadAsync(_peer, item.MessageId)
                : _services.Messaging.RequestAttachmentDownloadAsync(_peer, item.MessageId));
            if (changed && !_closed) await LoadAsync();
            return changed;
        }, save: () => AttachmentExporter.ExportAsync(this, message, CanSave,
            preferredFolder: _services.Settings.Snapshot.AttachmentExportFolder), canSave: CanSave, forward: () =>
        {
            if (_closed || _previewId != current.Id || _saving) return;
            ClosePreview();
            if (new ForwardMessagesDialog(_services, _peer, [current.Id]) { Owner = this }.ShowDialog() == true)
                TransientNotice.Show(this, AppStrings.Get("UiForwardingQueued"));
        });
        bool CanSave() => !_closed && _previewId == current.Id && _services.Database.CanExportAttachment(message.Source);
        if (openingPhoto is not null)
        {
            _preview.SetOpeningPhoto(openingPhoto);
            _preview.SetPhotoReturnFrame(() =>
                AttachmentPreview.FindIn(_list, current.Id)?.CapturePhotoThumbnail(_viewer)?.Bounds);
        }
        _viewer.Children.Add(_preview);
        _browser.IsHitTestVisible = false;
        _browser.IsEnabled = false;
        _viewer.IsHitTestVisible = true;
    }

    private async Task<bool> CheckCiphertextAsync(string reference)
    {
        using var check = new CancellationTokenSource();
        _attachmentCheck = check;
        var text = AppStrings.Get("UiCheckingAttachment");
        _status.Text = text;
        _cancelCheck.Visibility = Visibility.Visible;
        try
        {
            var complete = await Task.Run(() => _services.Objects.HasCompleteCiphertext(reference, check.Token), check.Token);
            check.Token.ThrowIfCancellationRequested();
            return complete;
        }
        finally
        {
            _attachmentCheck = null;
            _cancelCheck.Visibility = Visibility.Collapsed;
            if (_status.Text == text) _status.Text = "";
        }
    }

    private async void MovePreview(SharedMediaItem from, int delta)
    {
        if (_closed || _opening || _previewId != from.Id) return;
        _opening = true;
        var generation = _previewGeneration;
        var category = SelectedCategory;
        var query = _query;
        var before = _firstCursor;
        try
        {
            var neighbors = await Task.Run(() => _services.Objects.GetVisualMediaNeighbors(_peer,
                new SharedMediaCursor(from.Timestamp, from.Id), category, query, before));
            if (_closed || generation != _previewGeneration) return;
            var target = delta < 0 ? neighbors.Newer : neighbors.Older;
            if (target is not null) await PresentPreviewAsync(target, _pageVersion);
        }
        catch (OperationCanceledException) { }
        catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_closed && generation == _previewGeneration)
                TransientNotice.Show(this, AppStrings.Get("UiFileDetailsUnavailable"));
        }
        finally
        {
            _opening = false;
            if (!_closed) UpdateActions();
        }
    }

    private void ClosePreview(bool restoreFocus = true)
    {
        _attachmentCheck?.Cancel();
        _previewGeneration++;
        var previousFocus = _focusBeforePreview;
        _focusBeforePreview = null;
        _preview?.Dispose();
        _preview = null;
        _previewId = null;
        _viewer.Children.Clear();
        _viewer.IsHitTestVisible = false;
        _browser.IsHitTestVisible = true;
        _browser.IsEnabled = true;
        if (restoreFocus && !_closed && IsActive)
        {
            if (previousFocus is UIElement { IsEnabled: true, IsVisible: true, Focusable: true } element)
                Keyboard.Focus(element);
            else if (previousFocus is not null)
                _list.Focus();
        }
    }

    private void Jump()
    {
        if (_loading || _opening || SelectedMessageId is not { } id) return;
        JumpTo(id);
    }

    private void JumpTo(string id)
    {
        SelectedId = id;
        DialogResult = true;
    }
}
