// SPDX-License-Identifier: AGPL-3.0-only
using System.Security.Cryptography;
using System.Text;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;
using SkiaSharp;

namespace MsgAT.Windows.Views;

public sealed class StickerLibraryDialog : DialogWindow
{
    public StickerLibraryDialog(AppServices services) : this(services, null, null) { }

    internal StickerLibraryDialog(AppServices services,
        Func<StickerItem, CancellationToken, Task<PreparationSendResult>>? send, Func<PendingPreparation?>? preparation)
    {
        SetResourceReference(TitleProperty, "UiStickerLibrary");
        Width = 560;
        Height = Math.Min(570, SystemParameters.WorkArea.Height * .84);
        MinWidth = 375;
        MinHeight = Math.Min(350, Height);
        ResizeMode = ResizeMode.CanResize;
        var panel = new StickerLibraryPanel(services, this, Close, send, preparation);
        Content = panel;
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key != Key.Escape) return;
            panel.BackOrClose();
            e.Handled = true;
        };
        Closed += (_, _) => panel.Shutdown();
    }
}

[System.Diagnostics.CodeAnalysis.SuppressMessage("Design", "CA1001:Types that own disposable fields should be disposable",
    Justification = "Window closure and chat teardown cancel the panel and release its owned readers.")]
internal sealed class StickerLibraryPanel : DockPanel
{
    private const int PageSize = 60;
    private const int SelectionLimit = 200;
    private static readonly SemaphoreSlim Thumbnails = new(2);
    private sealed record Filter(string? Pack = null, bool Favorites = false, bool Recent = false, bool Animated = false);
    private sealed record Group(string Name, Filter Filter)
    {
        public override string ToString() => Name;
    }
    private sealed record ScrollPosition(double Offset, int Pages);
    private sealed record Thumbnail(Image View, StickerItem? Sticker, StickerSourceEntry? Candidate, ButtonBase Button)
    {
        public string? Hash { get; set; }
        public bool Loading { get; set; }
        public bool Failed { get; set; }
    }

    private readonly AppServices _services;
    private readonly Window _owner;
    private readonly Action _close;
    private readonly Func<StickerItem, CancellationToken, Task<PreparationSendResult>>? _send;
    private readonly Func<PendingPreparation?>? _preparation;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly CancellationToken _token;
    private CancellationTokenSource? _operation;
    private readonly DispatcherTimer _searchDelay = new() { Interval = TimeSpan.FromMilliseconds(180) };
    private readonly DispatcherTimer _preparationTimer = new() { Interval = TimeSpan.FromMilliseconds(200) };
    private readonly TextBlock _title = new() { FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center,
        Margin = new Thickness(5, 0, 0, 0), FontSize = 15, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(12, 4, 12, 4),
        Visibility = Visibility.Collapsed };
    private readonly ProgressBar _progress = new() { Height = 3, Visibility = Visibility.Collapsed };
    private readonly Button _back;
    private readonly Button _closeButton;
    private readonly Button _searchButton;
    private readonly Button _tools;
    private readonly Button _more;
    private readonly Button _retryRead;
    private readonly Button _import;
    private readonly Button _cancel;
    private readonly ListBox _groups = new() { BorderThickness = new Thickness(0), Background = Brushes.Transparent, MaxHeight = 64 };
    private readonly TextBox _search = IdentitySetupDialog.Field();
    private readonly TextBox _name = IdentitySetupDialog.Field();
    private readonly ComboBox _importDestination = new() { MaxDropDownHeight = 240 };
    private readonly FrameworkElement _destinationRow;
    private readonly FrameworkElement _nameRow;
    private readonly StackPanel _libraryControls;
    private readonly StackPanel _importControls;
    private readonly WrapPanel _grid;
    private readonly ScrollViewer _scroll;
    private readonly Border _preview = new() { Visibility = Visibility.Collapsed };
    private readonly List<Thumbnail> _thumbnails = [];
    private readonly Dictionary<int, StickerSelection> _selection = [];
    private readonly Dictionary<Filter, ScrollPosition> _positions = [];
    private StickerSource? _source;
    private StickerItem? _previewItem;
    private ButtonBase? _previewReturnButton;
    private double _previewReturnOffset;
    private Func<Task>? _retryLibraryRead;
    private int _pageCount = 1;
    private int _nextOffset;
    private int _candidatesShown;
    private long _generation;
    private bool _closed;
    private bool _loading;
    private bool _busy;
    private bool _sending;
    private bool _settingGroups;
    private bool _hasMore;
    private bool _searchOpen;
    private bool _searchWasEmpty = true;
    private string? _importProgress;
    private int _thumbnailLoadScheduled;
    internal event EventHandler? BusyChanged;

    internal StickerLibraryPanel(AppServices services, Window owner, Action close,
        Func<StickerItem, CancellationToken, Task<PreparationSendResult>>? send = null,
        Func<PendingPreparation?>? preparation = null)
    {
        _services = services;
        _owner = owner;
        _close = close;
        _send = send;
        _preparation = preparation;
        _token = _lifetime.Token;
        _searchOpen = send is null;
        SetResourceReference(BackgroundProperty, "MessagingChromeBrush");
        KeyboardNavigation.SetTabNavigation(this, KeyboardNavigationMode.Cycle);
        KeyboardNavigation.SetDirectionalNavigation(this, KeyboardNavigationMode.Contained);

        var header = new DockPanel { Margin = new Thickness(7, 4, 7, 4), MinHeight = 38 };
        _back = ContactsDialog.IconButton("HistoryReturnIcon", "UiStickerBack", BackOrClose);
        _closeButton = ContactsDialog.IconButton("CloseIcon", "UiStickerClosePanel", () => { if (!_busy) _close(); });
        _searchButton = ContactsDialog.IconButton("StickerSearchIcon", "UiStickerSearch", ToggleSearch);
        _tools = ContactsDialog.IconButton("MoreIcon", "UiStickerManage", OpenTools);
        DockPanel.SetDock(_back, Dock.Left);
        header.Children.Add(_back);
        foreach (var button in new[] { _closeButton, _tools, _searchButton })
        {
            DockPanel.SetDock(button, Dock.Right);
            header.Children.Add(button);
        }
        header.Children.Add(_title);
        DockPanel.SetDock(header, Dock.Top);
        Children.Add(header);
        var line = new Border { Height = 1 };
        line.SetResourceReference(Border.BackgroundProperty, "MessagingDividerBrush");
        DockPanel.SetDock(line, Dock.Top);
        Children.Add(line);

        var groupPanel = new FrameworkElementFactory(typeof(StackPanel));
        groupPanel.SetValue(StackPanel.OrientationProperty, Orientation.Horizontal);
        _groups.ItemsPanel = new ItemsPanelTemplate(groupPanel);
        _groups.ItemContainerStyle = (Style)FindResource("ConversationItemStyle");
        _groups.SetResourceReference(Control.BackgroundProperty, "MessagingSidebarBrush");
        ScrollViewer.SetHorizontalScrollBarVisibility(_groups, ScrollBarVisibility.Auto);
        ScrollViewer.SetVerticalScrollBarVisibility(_groups, ScrollBarVisibility.Disabled);
        AutomationProperties.SetName(_groups, AppStrings.Get("UiStickerCategories"));
        _libraryControls = new StackPanel();
        _search.MaxLength = 256;
        _search.Margin = new Thickness(10, 6, 10, 3);
        AutomationProperties.SetName(_search, AppStrings.Get("UiStickerSearch"));
        _libraryControls.Children.Add(_search);
        _groups.Margin = new Thickness(6, 2, 6, 2);
        _libraryControls.Children.Add(_groups);
        DockPanel.SetDock(_libraryControls, Dock.Top);
        Children.Add(_libraryControls);

        _importControls = new StackPanel { Margin = new Thickness(10, 6, 10, 0) };
        _destinationRow = IdentitySetupDialog.Row("UiStickerImportDestination", _importDestination, localized: true);
        AutomationProperties.SetName(_importDestination, AppStrings.Get("UiStickerImportDestination"));
        _importDestination.SelectionChanged += (_, _) => { _status.Tag = false; UpdateControls(); };
        _importControls.Children.Add(_destinationRow);
        _name.MaxLength = 128;
        _name.TextChanged += (_, _) => { _status.Tag = false; UpdateControls(); };
        _nameRow = IdentitySetupDialog.Row("UiStickerNewGroup", _name, localized: true);
        _importControls.Children.Add(_nameRow);
        DockPanel.SetDock(_importControls, Dock.Top);
        Children.Add(_importControls);
        DockPanel.SetDock(_status, Dock.Top);
        Children.Add(_status);
        DockPanel.SetDock(_progress, Dock.Top);
        Children.Add(_progress);

        var footer = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(6, 2, 8, 5) };
        _more = Button("UiStickerMore", More);
        _retryRead = Button("UiRetry", RetryLibraryRead);
        _import = Button("UiStickerImportSelected", Import, primary: true);
        _cancel = Button("UiStickerCancelOperation", CancelActive);
        footer.Children.Add(_more);
        footer.Children.Add(_retryRead);
        footer.Children.Add(_cancel);
        footer.Children.Add(_import);
        DockPanel.SetDock(footer, Dock.Bottom);
        Children.Add(footer);

        _grid = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(6, 3, 6, 3),
            RenderTransform = new TranslateTransform() };
        _scroll = new ScrollViewer { Content = _grid, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        _scroll.SetResourceReference(Control.BackgroundProperty, "MessagingTimelineBrush");
        _scroll.ScrollChanged += (_, _) => UpdateThumbnails();
        _scroll.SizeChanged += (_, _) => UpdateThumbnails();
        _grid.SizeChanged += (_, _) => UpdateThumbnails();
        var content = new Grid();
        content.Children.Add(_scroll);
        content.Children.Add(_preview);
        Children.Add(content);

        _groups.SelectionChanged += (_, e) =>
        {
            if (_settingGroups || _source is not null || _closed) return;
            if (_search.Text.Length == 0 && e.RemovedItems.OfType<Group>().FirstOrDefault() is { } previous)
                _positions[previous.Filter] = new ScrollPosition(_scroll.VerticalOffset, _pageCount);
            var position = _search.Text.Length == 0 ? _positions.GetValueOrDefault(CurrentFilter) : null;
            var previousIndex = e.RemovedItems.Count == 0 ? -1 : _groups.Items.IndexOf(e.RemovedItems[0]);
            var direction = previousIndex < 0 || _groups.SelectedIndex >= previousIndex ? 1 : -1;
            _ = LoadLibraryAsync(position?.Pages ?? 1, position?.Offset ?? 0, slideDirection: direction);
        };
        _search.TextChanged += (_, _) =>
        {
            if (_closed) return;
            var wasEmpty = _searchWasEmpty;
            _searchWasEmpty = _search.Text.Length == 0;
            if (_settingGroups || _source is not null) return;
            if (wasEmpty && !_searchWasEmpty)
                _positions[CurrentFilter] = new ScrollPosition(_scroll.VerticalOffset, _pageCount);
            ResetGrid();
            ShowStatus("UiStickerLoading");
            _searchDelay.Stop();
            _searchDelay.Start();
            UpdateControls();
        };
        _searchDelay.Tick += (_, _) =>
        {
            _searchDelay.Stop();
            var position = _search.Text.Length == 0 ? _positions.GetValueOrDefault(CurrentFilter) : null;
            _ = LoadLibraryAsync(position?.Pages ?? 1, position?.Offset ?? 0);
        };
        _preparationTimer.Tick += (_, _) => RefreshPreparation();
        Loaded += async (_, _) =>
        {
            if (_closed) return;
            await LoadGroupsAsync();
            if (_send is not null && !_closed) _closeButton.Focus();
        };
        UpdateControls();
    }

    internal bool IsBusy => _busy;
    private Filter CurrentFilter => (_groups.SelectedItem as Group)?.Filter ?? new Filter();

    internal void BackOrClose()
    {
        if (_busy) return;
        if (_previewItem is not null) ClosePreview();
        else if (_source is not null) _ = LeaveImportAsync();
        else if (_searchOpen && _send is not null) ToggleSearch();
        else _close();
    }

    internal void CancelActive() => _operation?.Cancel();

    internal void Shutdown()
    {
        if (_closed) return;
        _closed = true;
        _lifetime.Cancel();
        _searchDelay.Stop();
        _preparationTimer.Stop();
        ClosePreview();
        ResetGrid();
        var source = _source;
        _source = null;
        _selection.Clear();
        _lifetime.Dispose();
        if (source is not null) _ = DisposeSourceAsync(source);
    }

    private static async Task DisposeSourceAsync(StickerSource source) => await source.DisposeAsync().ConfigureAwait(false);

    private void ToggleSearch()
    {
        if (_busy) return;
        _searchOpen = !_searchOpen;
        if (!_searchOpen) _search.Clear();
        UpdateControls();
        if (_searchOpen) _search.Focus();
        else _searchButton.Focus();
    }

    private void OpenTools()
    {
        if (_busy || _closed) return;
        var menu = new ContextMenu { PlacementTarget = _tools, Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom };
        menu.Items.Add(MenuOption("UiStickerImportFile", () => Pick(false)));
        menu.Items.Add(MenuOption("UiStickerImportFolder", () => Pick(true)));
        if (CurrentFilter.Pack is { } pack)
        {
            menu.Items.Add(new Separator());
            menu.Items.Add(MenuOption("UiStickerRename", () => Rename(pack)));
            menu.Items.Add(MenuOption("UiStickerDeleteGroup", () => DeleteGroup(pack)));
        }
        _tools.ContextMenu = menu;
        menu.IsOpen = true;
    }

    private void Pick(bool folder)
    {
        var path = PickPath(folder);
        if (path is null || _closed) return;
        _ = RunAsync(async token =>
        {
            var packs = await Task.Run(_services.Stickers.Packs, token);
            if (_closed || token.IsCancellationRequested) return;
            var source = await StickerSource.OpenAsync(path, token);
            if (_closed || token.IsCancellationRequested)
            {
                await source.DisposeAsync();
                return;
            }
            if (_search.Text.Length == 0) _positions[CurrentFilter] = new ScrollPosition(_scroll.VerticalOffset, _pageCount);
            _source = source;
            _importDestination.ItemsSource = new[] { new Group(AppStrings.Get("UiStickerCreateGroup"), new Filter()) }
                .Concat(packs.Select(pack => new Group(pack.Name, new Filter(Pack: pack.Id)))).ToArray();
            _importDestination.SelectedIndex = 0;
            _name.Text = AppStrings.Get("UiStickerImported");
            _selection.Clear();
            _candidatesShown = 0;
            ResetGrid();
            AppendCandidates();
        }, "UiStickerLoadError");
    }

    private string? PickPath(bool folder)
    {
        if (folder)
        {
            var picker = new Microsoft.Win32.OpenFolderDialog { Title = AppStrings.Get("UiStickerImportFolder") };
            return picker.ShowDialog(_owner) == true ? picker.FolderName : null;
        }
        var file = new Microsoft.Win32.OpenFileDialog { Title = AppStrings.Get("UiStickerImport"),
            Filter = "Images and packages|*.png;*.jpg;*.jpeg;*.gif;*.webp;*.bmp;*.dat;*.eif;*.eip;*.zip|All files|*.*" };
        return file.ShowDialog(_owner) == true ? file.FileName : null;
    }

    private async Task LeaveImportAsync()
    {
        if (_source is not { } source) return;
        _source = null;
        _selection.Clear();
        ResetGrid();
        UpdateControls();
        await source.DisposeAsync();
        var position = _positions.GetValueOrDefault(CurrentFilter);
        await LoadLibraryAsync(position?.Pages ?? 1, position?.Offset ?? 0);
    }

    private async Task LoadGroupsAsync(string? selectedPack = null)
    {
        _retryLibraryRead = null;
        UpdateControls();
        try
        {
            var current = selectedPack is null ? CurrentFilter : new Filter(Pack: selectedPack);
            var packs = await Task.Run(_services.Stickers.Packs, _token);
            if (_closed) return;
            var groups = new List<Group>
            {
                new(AppStrings.Get("UiStickerAll"), new Filter()),
                new(AppStrings.Get("UiStickerFavorites"), new Filter(Favorites: true)),
                new(AppStrings.Get("UiStickerRecent"), new Filter(Recent: true)),
                new(AppStrings.Get("UiStickerAnimated"), new Filter(Animated: true))
            };
            groups.AddRange(packs.Select(pack => new Group(pack.Name, new Filter(Pack: pack.Id))));
            _settingGroups = true;
            _groups.ItemsSource = groups;
            _groups.SelectedItem = groups.FirstOrDefault(group => group.Filter == current) ?? groups[0];
            _groups.ScrollIntoView(_groups.SelectedItem);
            if (selectedPack is not null) _search.Clear();
            _settingGroups = false;
            var position = selectedPack is null ? _positions.GetValueOrDefault(CurrentFilter) : null;
            await LoadLibraryAsync(position?.Pages ?? 1, position?.Offset ?? 0);
        }
        catch (OperationCanceledException) when (_closed) { }
        catch (Exception)
        {
            if (!_closed)
            {
                _retryLibraryRead = () => LoadGroupsAsync(selectedPack);
                ShowStatus("UiStickerLoadError", error: true);
                UpdateControls();
            }
        }
    }

    private async Task LoadLibraryAsync(int pages, double scrollOffset, int slideDirection = 0, bool append = false)
    {
        if (_source is not null || _closed) return;
        if (!append) ResetGrid();
        _retryLibraryRead = null;
        var generation = _generation;
        var filter = CurrentFilter;
        var query = _search.Text.Trim();
        var start = append ? _nextOffset : 0;
        _loading = true;
        ShowStatus("UiStickerLoading");
        UpdateControls();

        try
        {
            var result = await Task.Run(() =>
            {
                var found = new List<StickerItem>();
                var hasMore = false;
                var offset = start;
                for (; offset < pages * PageSize; offset += PageSize)
                {
                    _token.ThrowIfCancellationRequested();
                    var page = _services.Stickers.List(filter.Pack, filter.Favorites, filter.Recent, query, offset, filter.Animated);
                    found.AddRange(page);
                    hasMore = page.Count == PageSize;
                    if (!hasMore) break;
                }
                return (found, hasMore, offset);
            }, _token);
            if (_closed || generation != _generation) return;
            _pageCount = pages;
            _hasMore = result.hasMore;
            _nextOffset = result.offset;
            var existing = _thumbnails.Select(row => row.Sticker?.Id).OfType<string>().ToHashSet(StringComparer.Ordinal);
            foreach (var item in result.found.Where(item => existing.Add(item.Id))) AddSticker(item);
            SetStatus(!append && result.found.Count == 0 ? AppStrings.Get("UiStickerEmpty") : null);
            if (slideDirection != 0 && SystemParameters.ClientAreaAnimation)
            {
                var duration = TimeSpan.FromMilliseconds(200);
                var translation = (TranslateTransform)_grid.RenderTransform;
                _grid.BeginAnimation(OpacityProperty, new DoubleAnimation(0.35, 1, duration) { FillBehavior = FillBehavior.Stop });
                translation.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(slideDirection * 22, 0, duration)
                {
                    EasingFunction = new CircleEase { EasingMode = EasingMode.EaseOut },
                    FillBehavior = FillBehavior.Stop
                });
            }
            _ = Dispatcher.InvokeAsync(() =>
            {
                if (_closed || generation != _generation) return;
                if (!append) _scroll.ScrollToVerticalOffset(scrollOffset);
                UpdateThumbnails();
            }, DispatcherPriority.Loaded);
        }
        catch (OperationCanceledException) when (_closed) { }
        catch (Exception)
        {
            if (!_closed && generation == _generation)
            {
                _retryLibraryRead = () => LoadLibraryAsync(pages, scrollOffset, slideDirection, append);
                ShowStatus("UiStickerLoadError", error: true);
            }
        }
        finally
        {
            if (!_closed && generation == _generation)
            {
                _loading = false;
                UpdateControls();
            }
        }
    }

    private void ResetGrid()
    {
        ++_generation;
        _loading = false;
        _hasMore = false;
        _retryLibraryRead = null;
        _grid.BeginAnimation(OpacityProperty, null);
        ((TranslateTransform)_grid.RenderTransform).BeginAnimation(TranslateTransform.XProperty, null);
        _thumbnails.Clear();
        _grid.Children.Clear();
    }

    private void AddSticker(StickerItem item)
    {
        var size = _send is null ? 74 : 56;
        var image = new Image { Stretch = Stretch.Uniform, Margin = new Thickness(4) };
        var face = new Grid();
        face.Children.Add(image);
        if (item.Frames > 1)
        {
            var badge = new TextBlock { Text = "▶", FontSize = 9, HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 4, 2) };
            badge.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
            face.Children.Add(badge);
        }
        var button = new Button { Content = face, Width = size, Height = size, Padding = new Thickness(0),
            HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch,
            Margin = new Thickness(2), ToolTip = item.Label, Background = Brushes.Transparent, BorderBrush = Brushes.Transparent };
        AutomationProperties.SetName(button, item.Frames > 1 ? $"{item.Label}, {AppStrings.Get("UiStickerAnimated")}" : item.Label);
        button.Click += (_, _) =>
        {
            if (_busy) return;
            if (_send is null) Preview(item);
            else Send(item);
        };
        var menu = new ContextMenu();
        menu.Items.Add(MenuOption("UiStickerPreview", () => Preview(item)));
        menu.Items.Add(MenuOption(item.Favorite ? "UiStickerUnfavorite" : "UiStickerFavorite", () => Favorite(item)));
        menu.Items.Add(MenuOption(CurrentFilter.Pack is null ? "UiStickerDeleteItem" : "UiStickerRemoveFromPack", () => Remove(item)));
        button.ContextMenu = menu;
        _thumbnails.Add(new Thumbnail(image, item, null, button));
        _grid.Children.Add(button);
    }

    private void AppendCandidates()
    {
        if (_source is not { } source) return;
        var page = source.Entries.Skip(_candidatesShown).Take(PageSize).ToArray();
        _candidatesShown += page.Length;
        _hasMore = _candidatesShown < source.Entries.Count;
        foreach (var entry in page)
        {
            var image = new Image { Width = 68, Height = 66, Stretch = Stretch.Uniform };
            var label = new TextBlock { Text = entry.Name, TextTrimming = TextTrimming.CharacterEllipsis, MaxWidth = 82, FontSize = 11 };
            var face = new StackPanel();
            face.Children.Add(image);
            face.Children.Add(label);
            var button = new ToggleButton
            {
                Content = face, Width = 90, Height = 90, Margin = new Thickness(3), Padding = new Thickness(3),
                IsEnabled = false, ToolTip = entry.Name
            };
            var thumbnail = new Thumbnail(image, null, entry, button);
            button.Checked += (_, _) =>
            {
                if (_selection.Count >= SelectionLimit || thumbnail.Hash is not { } hash)
                {
                    button.IsChecked = false;
                    return;
                }
                _selection[entry.Id] = new StickerSelection(entry, hash);
                UpdateControls();
            };
            button.Unchecked += (_, _) => { _selection.Remove(entry.Id); UpdateControls(); };
            AutomationProperties.SetName(button, entry.Name);
            _thumbnails.Add(thumbnail);
            _grid.Children.Add(button);
        }
        UpdateControls();
        _ = Dispatcher.InvokeAsync(UpdateThumbnails, DispatcherPriority.Loaded);
    }

    private void More()
    {
        if (_busy || _loading || !_hasMore) return;
        if (_source is not null) AppendCandidates();
        else _ = LoadLibraryAsync(_pageCount + 1, _scroll.VerticalOffset, append: true);
    }

    private void RetryLibraryRead()
    {
        if (!_closed && !_busy && !_loading && _retryLibraryRead is { } retry) _ = retry();
    }

    private bool IsInViewport(Image image)
    {
        if (!image.IsLoaded || !image.IsVisible) return false;
        try
        {
            var bounds = image.TransformToAncestor(_scroll).TransformBounds(new Rect(image.RenderSize));
            return bounds.IntersectsWith(new Rect(0, 0, _scroll.ActualWidth, _scroll.ActualHeight));
        }
        catch (InvalidOperationException) { return false; }
    }

    private void UpdateThumbnails()
    {
        if (_closed || _thumbnailLoadScheduled != 0) return;
        _thumbnailLoadScheduled = 1;
        _ = Dispatcher.InvokeAsync(() =>
        {
            _thumbnailLoadScheduled = 0;
            LoadVisibleThumbnails();
        }, DispatcherPriority.Background);
    }

    private void LoadVisibleThumbnails()
    {
        if (_closed || _previewItem is not null) return;
        foreach (var thumb in _thumbnails)
        {
            if (!IsInViewport(thumb.View))
            {
                thumb.View.Source = null;
                continue;
            }
            if (thumb.View.Source is not null || thumb.Loading || thumb.Failed) continue;
            thumb.Loading = true;
            _ = LoadThumbnailAsync(thumb, _generation);
        }
    }

    private async Task LoadThumbnailAsync(Thumbnail thumbnail, long generation)
    {
        try
        {
            await Thumbnails.WaitAsync(_token);
            try
            {
                if (_closed || generation != _generation || !IsInViewport(thumbnail.View)) return;
                if (thumbnail.Sticker is { } sticker)
                {
                    var image = await Task.Run(() => DecodeThumbnail(_services.Objects.ReadStickerBytes(sticker.Reference)), _token);
                    if (!_closed && generation == _generation && IsInViewport(thumbnail.View)) thumbnail.View.Source = image;
                }
                else if (_source is { } source && thumbnail.Candidate is { } entry)
                {
                    var data = await source.ReadAsync(entry, _token);
                    try
                    {
                        var image = await Task.Run(() =>
                        {
                            Inspect(data.Bytes);
                            using var input = new MemoryStream(data.Bytes, writable: false);
                            return ImageDecoder.Load(input, 192);
                        }, _token);
                        if (_closed || generation != _generation) return;
                        thumbnail.Hash = data.Sha256;
                        thumbnail.Button.IsEnabled = true;
                        if (IsInViewport(thumbnail.View)) thumbnail.View.Source = image;
                    }
                    finally { data.Clear(); }
                }
            }
            finally { Thumbnails.Release(); }
        }
        catch (OperationCanceledException) when (_closed) { }
        catch (Exception)
        {
            if (!_closed && generation == _generation)
            {
                thumbnail.Failed = true;
                thumbnail.Button.ToolTip = AppStrings.Get("UiStickerInvalidImage");
            }
        }
        finally { thumbnail.Loading = false; }
    }

    private static BitmapSource DecodeThumbnail(byte[] bytes)
    {
        try
        {
            using var input = new MemoryStream(bytes, writable: false);
            return ImageDecoder.Load(input, 192);
        }
        finally { CryptographicOperations.ZeroMemory(bytes); }
    }

    private static StickerImageInfo Inspect(byte[] bytes)
    {
        using var stream = new MemoryStream(bytes, writable: false);
        using var codec = SKCodec.Create(stream) ?? throw new IOException("Cannot decode sticker.");
        var info = new StickerImageInfo(codec.Info.Width, codec.Info.Height, Math.Max(1, codec.FrameCount));
        info.Validate();
        using var pixels = new SKBitmap(codec.Info);
        if (codec.GetPixels(pixels.Info, pixels.GetPixels()) != SKCodecResult.Success) throw new IOException("Sticker pixels are damaged.");
        return info;
    }

    private void Preview(StickerItem item)
    {
        if (_busy || _closed) return;
        if (_previewItem is null)
        {
            _previewReturnOffset = _scroll.VerticalOffset;
            _previewReturnButton = _thumbnails.FirstOrDefault(row => row.Sticker?.Id == item.Id)?.Button;
        }
        _previewItem = item;
        foreach (var thumbnail in _thumbnails) thumbnail.View.Source = null;
        _preview.Visibility = Visibility.Visible;
        var body = new DockPanel { Margin = new Thickness(10, 5, 10, 8) };
        var label = new TextBlock { Text = item.Label, TextTrimming = TextTrimming.CharacterEllipsis, TextAlignment = TextAlignment.Center,
            Margin = new Thickness(2, 0, 2, 6) };
        DockPanel.SetDock(label, Dock.Top);
        body.Children.Add(label);
        var actions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Center };
        actions.Children.Add(Button(item.Favorite ? "UiStickerUnfavorite" : "UiStickerFavorite", () => Favorite(item)));
        actions.Children.Add(ContactsDialog.IconButton("StickerRemoveIcon",
            CurrentFilter.Pack is null ? "UiStickerDeleteItem" : "UiStickerRemoveFromPack", () => Remove(item), color: "DangerBrush"));
        var send = _send is null ? null : Button("UiSend", () => Send(item), primary: true);
        if (send is not null) actions.Children.Add(send);
        DockPanel.SetDock(actions, Dock.Bottom);
        body.Children.Add(actions);
        var sticker = new StickerImageView(() => _services.Objects.ReadStickerBytes(item.Reference)) { Width = 256, Height = 256 };
        var viewbox = new Viewbox { Stretch = Stretch.Uniform, StretchDirection = StretchDirection.DownOnly,
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Child = sticker };
        sticker.PlaybackFailed += (_, _) =>
        {
            if (_previewItem?.Id != item.Id || !ReferenceEquals(_preview.Child, body) ||
                viewbox.Visibility != Visibility.Visible) return;
            viewbox.Visibility = Visibility.Collapsed;
            if (send is not null) send.IsEnabled = false;
            var failure = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            failure.Children.Add(new TextBlock { Text = AppStrings.Get("UiStickerInvalidImage"), TextAlignment = TextAlignment.Center });
            var retry = Button("UiRetry", () => Preview(item));
            retry.HorizontalAlignment = HorizontalAlignment.Center;
            failure.Children.Add(retry);
            body.Children.Add(failure);
        };
        body.Children.Add(viewbox);
        _preview.Child = body;
        UpdateControls();
        _back.Focus();
    }

    private void ClosePreview()
    {
        var previous = _previewItem is not null ? _previewReturnButton : null;
        var offset = _previewReturnOffset;
        _previewReturnButton = null;
        _previewItem = null;
        _preview.Child = null;
        _preview.Visibility = Visibility.Collapsed;
        if (!_closed)
        {
            UpdateControls();
            _ = Dispatcher.InvokeAsync(() =>
            {
                if (_closed || _previewItem is not null) return;
                if (previous is { IsVisible: true })
                {
                    _scroll.ScrollToVerticalOffset(offset);
                    previous.Focus();
                }
                UpdateThumbnails();
            }, DispatcherPriority.Loaded);
        }
    }

    private void Send(StickerItem item)
    {
        if (_send is not { } send || _busy || _closed) return;
        ClosePreview();
        _ = RunAsync(async token =>
        {
            var result = await send(item, token);
            if (result == PreparationSendResult.Queued && !_closed) return true;
            if (result == PreparationSendResult.Failed && !_closed) ShowStatus("UiStickerSendError", error: true);
            return false;
        }, "UiStickerSendError", sending: true);
    }

    private void Favorite(StickerItem item)
    {
        ClosePreview();
        _ = RunAsync(async token =>
        {
            if (!await _services.Stickers.FavoriteAsync(item.Id, !item.Favorite, token)) throw new InvalidOperationException();
            await LoadLibraryAsync(_pageCount, _scroll.VerticalOffset);
        }, "UiStickerChangeError");
    }

    private void Remove(StickerItem item)
    {
        var pack = CurrentFilter.Pack;
        if (new ConfirmationDialog(AppStrings.Get(pack is null ? "UiStickerDeleteItemConfirm" : "UiStickerRemoveFromPackConfirm"))
            { Owner = _owner }.ShowDialog() != true || _closed) return;
        ClosePreview();
        _ = RunAsync(async token =>
        {
            var removed = pack is null ? await _services.Stickers.DeleteAsync(item.Id, token)
                : await _services.Stickers.RemoveFromPackAsync(pack, item.Id, token);
            if (!removed) throw new InvalidOperationException();
            await LoadGroupsAsync();
        }, "UiStickerChangeError");
    }

    private void Import()
    {
        if (_source is not { } source || _busy || !ValidDestination()) return;
        var name = _name.Text.Trim();
        var destination = ImportPack;
        var selection = _selection.Values.OrderBy(value => value.Entry.Id).ToArray();
        if (selection.Length == 0) return;
        _ = RunAsync(async token =>
        {
            var progress = new Progress<StickerImportProgress>(value =>
            {
                if (_closed || token != _operation?.Token) return;
                _importProgress = AppStrings.Format(value.Phase == "Scanning" ? "UiStickerScanProgress" : "UiStickerEncryptProgress",
                    value.Done, value.Total);
                UpdateControls();
            });
            var result = await _services.Stickers.ImportAsync(source, selection, name, Inspect,
                packId: destination, progress: progress, token: token);
            if (_closed) return;
            _source = null;
            _selection.Clear();
            _positions.Clear();
            await source.DisposeAsync();
            await LoadGroupsAsync(result.PackId);
            if (!_closed) SetStatus(AppStrings.Format("UiStickerImportedCount", result.Added, result.Duplicates));
        }, "UiStickerChangeError");
    }

    private void Rename(string pack)
    {
        var prompt = new TextPromptDialog(AppStrings.Get("UiStickerRename"), AppStrings.Get("UiStickerGroupName"),
            (_groups.SelectedItem as Group)?.Name ?? "", multiline: false) { Owner = _owner };
        if (prompt.ShowDialog() != true || _closed) return;
        if (Encoding.UTF8.GetByteCount(prompt.Value.Trim()) > 128)
        {
            ShowStatus("UiStickerGroupNameLimit", error: true);
            return;
        }
        _ = RunAsync(async token =>
        {
            if (!await _services.Stickers.RenamePackAsync(pack, prompt.Value, token)) throw new InvalidOperationException();
            _positions[CurrentFilter] = new ScrollPosition(_scroll.VerticalOffset, _pageCount);
            await LoadGroupsAsync();
        }, "UiStickerChangeError");
    }

    private void DeleteGroup(string pack)
    {
        if (new ConfirmationDialog(AppStrings.Get("UiStickerOtherGroupsKept"), title: AppStrings.Get("UiStickerDeleteGroupConfirm"))
            { Owner = _owner }.ShowDialog() != true || _closed) return;
        _ = RunAsync(async token =>
        {
            if (!await _services.Stickers.DeletePackAsync(pack, token)) throw new InvalidOperationException();
            _positions.Remove(new Filter(Pack: pack));
            await LoadGroupsAsync();
        }, "UiStickerChangeError");
    }

    private Task RunAsync(Func<CancellationToken, Task> action, string failure) =>
        RunAsync(async token => { await action(token); return false; }, failure, sending: false);

    private async Task RunAsync(Func<CancellationToken, Task<bool>> action, string failure, bool sending)
    {
        if (_busy || _closed) return;
        _busy = true;
        _sending = sending;
        BusyChanged?.Invoke(this, EventArgs.Empty);
        SetStatus(null);
        using var operation = CancellationTokenSource.CreateLinkedTokenSource(_token);
        _operation = operation;
        UpdateControls();
        if (sending) _preparationTimer.Start();
        var close = false;
        try { close = await action(operation.Token); }
        catch (OperationCanceledException) when (operation.IsCancellationRequested) { }
        catch (Exception)
        {
            if (!_closed) ShowStatus(failure, error: true);
        }
        finally
        {
            _operation = null;
            _importProgress = null;
            _preparationTimer.Stop();
            _busy = _sending = false;
            if (!_closed)
            {
                BusyChanged?.Invoke(this, EventArgs.Empty);
                if (sending && !close && _status.Tag is not true)
                    SetStatus(null);
                UpdateControls();
                if (close) _close();
            }
        }
    }

    private void RefreshPreparation()
    {
        if (_closed) return;
        var preparation = _preparation?.Invoke();
        _progress.IsIndeterminate = preparation?.Indeterminate != false;
        if (preparation is { } row)
        {
            _progress.Maximum = Math.Max(1, row.Progress.Total);
            _progress.Value = row.Progress.Completed;
            _cancel.IsEnabled = row.Progress.CanCancel;
            SetStatus(row.Status);
        }
    }

    private string? ImportPack => (_importDestination.SelectedItem as Group)?.Filter.Pack;
    private bool ValidDestination() => ImportPack is not null ||
        !string.IsNullOrWhiteSpace(_name.Text) && Encoding.UTF8.GetByteCount(_name.Text.Trim()) <= 128;

    private void UpdateControls()
    {
        if (_closed) return;
        var importing = _source is not null;
        var previewing = _previewItem is not null;
        var browsing = !importing && !previewing;
        _title.Text = AppStrings.Get(importing ? "UiStickerImport" : previewing ? "UiStickerPreview" :
            _send is null ? "UiStickerLibrary" : "UiStickers");
        _closeButton.IsEnabled = _back.IsEnabled = _tools.IsEnabled = _searchButton.IsEnabled = !_busy;
        _back.Visibility = browsing ? Visibility.Collapsed : Visibility.Visible;
        _searchButton.Visibility = _tools.Visibility = browsing ? Visibility.Visible : Visibility.Collapsed;
        _libraryControls.Visibility = browsing ? Visibility.Visible : Visibility.Collapsed;
        _libraryControls.IsEnabled = !_busy;
        _search.Visibility = _searchOpen ? Visibility.Visible : Visibility.Collapsed;
        _importControls.Visibility = importing ? Visibility.Visible : Visibility.Collapsed;
        _destinationRow.Visibility = _importDestination.Items.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        _importDestination.IsEnabled = !_busy;
        _nameRow.Visibility = ImportPack is null ? Visibility.Visible : Visibility.Collapsed;
        _name.IsEnabled = !_busy;
        _scroll.Visibility = previewing ? Visibility.Collapsed : Visibility.Visible;
        _grid.IsEnabled = !_busy;
        _more.Visibility = !previewing && _hasMore ? Visibility.Visible : Visibility.Collapsed;
        _more.IsEnabled = !_busy && !_loading && !_searchDelay.IsEnabled && _retryLibraryRead is null;
        _retryRead.Visibility = browsing && _retryLibraryRead is not null ? Visibility.Visible : Visibility.Collapsed;
        _retryRead.IsEnabled = !_busy && !_loading;
        _import.Visibility = importing && !_busy ? Visibility.Visible : Visibility.Collapsed;
        _import.IsEnabled = _selection.Count > 0 && ValidDestination();
        _cancel.Visibility = _busy ? Visibility.Visible : Visibility.Collapsed;
        _cancel.IsEnabled = _busy && (!_sending || _preparation?.Invoke()?.Progress.CanCancel != false);
        _progress.Visibility = _busy || _loading ? Visibility.Visible : Visibility.Collapsed;
        _progress.IsIndeterminate = true;
        if (_source is { } source)
        {
            if (_importProgress is { } progress) SetStatus(progress);
            else if (!_busy && _status.Tag is not true)
            {
                SetStatus(ValidDestination() || _name.Text.Length == 0
                    ? AppStrings.Format("UiStickerCandidates", source.Entries.Count, _selection.Count)
                    : AppStrings.Get("UiStickerGroupNameLimit"));
            }
        }
    }

    private void ShowStatus(string key, bool error = false) => SetStatus(AppStrings.Get(key), error);

    private void SetStatus(string? value, bool error = false)
    {
        _status.Text = value ?? "";
        _status.Tag = error;
        _status.SetResourceReference(TextBlock.ForegroundProperty, error ? "DangerBrush" : "MutedBrush");
        _status.Visibility = string.IsNullOrEmpty(value) ? Visibility.Collapsed : Visibility.Visible;
    }

    private static Button Button(string key, Action action, bool primary = false)
    {
        var button = new Button { Content = AppStrings.Get(key), Margin = new Thickness(3), Padding = new Thickness(8, 4, 8, 4),
            Style = (Style)Application.Current.FindResource(primary ? "PrimaryButtonStyle" : "ButtonStyle") };
        button.Click += (_, _) => action();
        return button;
    }

    private static MenuItem MenuOption(string key, Action action)
    {
        var option = new MenuItem { Header = AppStrings.Get(key) };
        option.Click += (_, _) => action();
        return option;
    }
}
