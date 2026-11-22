// SPDX-License-Identifier: AGPL-3.0-only
using System.ComponentModel;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using Microsoft.Win32;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.Media;
using MsgAT.Windows.ViewModels;
using MenuItem = System.Windows.Controls.MenuItem;
using TextBox = System.Windows.Controls.TextBox;

namespace MsgAT.Windows.Views;

[System.Diagnostics.CodeAnalysis.SuppressMessage("Design", "CA1001", Justification = "The Closed handler disposes recording playback and the preview lifetime.")]
public sealed class MediaSendDialog : DialogWindow
{
    private sealed record PhotoEdit(EditedPhotoContent Content, ImageSource Preview, PhotoAdjustment Adjustment);
    private sealed record Attachment(string Name, long Size, ImageSource? Image, string? Path = null, string? Error = null,
        string? Mime = null, VideoTrim? Trim = null, PhotoEdit? Edit = null, bool Pending = false)
    {
        public Guid Id { get; init; } = Guid.NewGuid();
    }
    private sealed record CaptionTarget(Guid Id, string Label);

    private readonly TextBox _caption = IdentitySetupDialog.Field();
    private readonly MenuItem _asFile = new() { Header = AppStrings.Get("UiSendAsFile"), IsCheckable = true };
    private readonly MenuItem _compressVideos = new() { Header = AppStrings.Get("UiCompressVideos"), IsCheckable = true };
    private readonly MenuItem _compressPhotos = new() { Header = AppStrings.Get("UiCompressPhotos"), IsCheckable = true };
    private readonly MenuItem _asAlbum = new() { Header = AppStrings.Get("UiSendAsAlbum"), IsCheckable = true, IsChecked = true };
    private readonly TextBlock _error = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 8) };
    private readonly TextBlock _recheckingSources = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private readonly TextBlock _heading;
    private readonly TextBlock _batchSize = new() { Margin = new Thickness(0, 0, 0, 8) };
    private readonly TextBlock _captionLabel = new();
    private readonly ComboBox _captionTarget = new() { MinWidth = 120, MaxWidth = 330, Margin = new Thickness(8, 0, 0, 4) };
    private readonly Dictionary<Guid, string> _captions = new();
    private readonly Dictionary<Guid, Border> _captionCards = new();
    private CaptionTarget[] _captionTargets = [];
    private Guid? _captionId;
    private bool _updatingCaptionTarget;
    private bool _albumLayout;
    private readonly StackPanel _files = new();
    private readonly Grid _recordingPreview = new() { Visibility = Visibility.Collapsed };
    private readonly EncryptedCaptureFile? _recording;
    private EncryptedMediaView? _recordingPlayback;
    private readonly Button _options;
    private readonly Button _send;
    private readonly List<Attachment> _items;
    private readonly string?[] _initialPaths;
    private readonly SendOptions _initialOptions;
    private readonly bool _initialAsFile;
    private readonly bool _canAddFiles;
    private readonly CancellationTokenSource _previewLifetime = new();
    private bool _confirmingDiscard;
    private const string ReorderFormat = "MsgAT.AttachmentOrder";
    private Guid? _draggedAttachment;
    private Guid? _attachmentDropTarget;
    private SendOptions _selection;
    private bool _composing;
    private bool _loading;
    private bool _closed;
    private bool _sending;
    private bool _preparationFailed;
    private Func<MediaSendChoice, Action<string>, Task<PreparationSendResult>>? _submit;
    private MainViewModel? _preparationModel;
    private readonly TextBlock _preparationStatus = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _preparationFile = new() { TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly System.Windows.Controls.ProgressBar _preparationProgress = new() { Height = 5, Maximum = 100, Margin = new Thickness(0, 8, 0, 8) };
    private readonly Button _cancelActivePreparation;
    private readonly Border _preparationOverlay;
    private readonly DockPanel _editor;
    private readonly TextBlock _optionsLabel = new() { TextTrimming = TextTrimming.CharacterEllipsis };
    private string? _activePreparationId;

    public MediaSendChoice Result { get; private set; }

    private MediaSendDialog(Window owner, IEnumerable<Attachment> items, string recipient, SendOptions initial,
        IReadOnlyList<string>? initialPaths = null, bool initialAsFile = false, EncryptedCaptureFile? recording = null)
    {
        Owner = owner;
        _items = items.ToList();
        _initialPaths = _items.Select(item => item.Path).ToArray();
        _initialOptions = initial;
        _initialAsFile = initialAsFile;
        _recording = recording;
        _canAddFiles = initialPaths is not null;
        _loading = initialPaths is not null;
        _selection = initial;
        _asFile.IsChecked = initialAsFile;
        Result = new MediaSendChoice("", initial);
        Title = _items.Count <= 1 ? AppStrings.Get("UiSendAttachment") : AppStrings.Format("UiSendAttachments", _items.Count);
        Width = 520;
        MinWidth = 440;
        SizeToContent = SizeToContent.Height;
        var monitor = System.Windows.Forms.Screen.FromHandle(new WindowInteropHelper(owner).Handle);
        var availableHeight = monitor.WorkingArea.Height / VisualTreeHelper.GetDpi(owner).DpiScaleY;
        MaxHeight = availableHeight * .88;
        SetResourceReference(BackgroundProperty, "MessagingChromeBrush");

        var panel = new DockPanel { Margin = new Thickness(16) };
        _editor = panel;
        _cancelActivePreparation = Action(AppStrings.Get("UiPreparationCancel"), RequestPreparationCancellation);
        var preparationBody = new StackPanel { Margin = new Thickness(16), Width = 300 };
        preparationBody.Children.Add(_preparationFile);
        preparationBody.Children.Add(_preparationStatus);
        preparationBody.Children.Add(_preparationProgress);
        preparationBody.Children.Add(_cancelActivePreparation);
        _preparationOverlay = new Border
        {
            Child = preparationBody, HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center, CornerRadius = new CornerRadius(8), Focusable = true,
            Background = (Brush)FindResource("SurfaceBrush"), Visibility = Visibility.Collapsed
        };
        var header = new StackPanel();
        _heading = IdentitySetupDialog.Heading(Title);
        _heading.Margin = new Thickness(0, 0, 0, 3);
        header.Children.Add(_heading);
        header.Children.Add(new TextBlock
        {
            Text = AppStrings.Format("UiRecipient", recipient), TextTrimming = TextTrimming.CharacterEllipsis,
            Foreground = (Brush)FindResource("MutedBrush"), Margin = new Thickness(0, 0, 0, 3)
        });
        _batchSize.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        header.Children.Add(_batchSize);
        DockPanel.SetDock(header, Dock.Top);
        panel.Children.Add(header);

        var footer = new StackPanel();
        var divider = new Border { Height = 1, Margin = new Thickness(0, 8, 0, 8) };
        divider.SetResourceReference(Border.BackgroundProperty, "MessagingDividerBrush");
        footer.Children.Add(divider);
        var captionHeading = new DockPanel { LastChildFill = false };
        _captionLabel.Text = AppStrings.Get("UiCaption");
        _captionLabel.VerticalAlignment = VerticalAlignment.Center;
        System.Windows.Automation.AutomationProperties.SetLabeledBy(_captionTarget, _captionLabel);
        System.Windows.Automation.AutomationProperties.SetLabeledBy(_caption, _captionLabel);
        captionHeading.Children.Add(_captionLabel);
        _captionTarget.DisplayMemberPath = nameof(CaptionTarget.Label);
        _captionTarget.SelectedValuePath = nameof(CaptionTarget.Id);
        _captionTarget.SelectionChanged += (_, _) =>
        {
            if (!_updatingCaptionTarget && _captionTarget.SelectedValue is Guid id) ShowCaption(id);
        };
        captionHeading.Children.Add(_captionTarget);
        footer.Children.Add(captionHeading);
        _caption.Height = double.NaN;
        _caption.MinHeight = 48;
        _caption.MaxHeight = 96;
        _caption.AcceptsReturn = true;
        _caption.TextWrapping = TextWrapping.Wrap;
        _caption.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
        _caption.TextChanged += (_, _) =>
        {
            if (_updatingCaptionTarget || _captionId is not { } id) return;
            if (_caption.Text.Length == 0) _captions.Remove(id);
            else _captions[id] = _caption.Text;
        };
        footer.Children.Add(_caption);
        _error.Foreground = (Brush)FindResource("DangerBrush");
        footer.Children.Add(_error);
        _recheckingSources.SetResourceReference(TextBlock.TextProperty, "UiAttachmentRecheckingSources");
        _recheckingSources.SetResourceReference(TextBlock.ForegroundProperty, "MutedBrush");
        footer.Children.Add(_recheckingSources);

        var buttons = new Grid { Margin = new Thickness(0, 4, 0, 0) };
        buttons.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        buttons.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        buttons.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var mediaOptionsLabel = AppStrings.Get("UiAttachmentOptions");
        var mediaOptions = new Button { ToolTip = mediaOptionsLabel, Margin = new Thickness(0, 0, 4, 0) };
        mediaOptions.SetResourceReference(StyleProperty, "IconButtonStyle");
        mediaOptions.SetResourceReference(ContentControl.ContentProperty, "MoreIcon");
        System.Windows.Automation.AutomationProperties.SetName(mediaOptions, mediaOptionsLabel);
        var mediaMenu = new System.Windows.Controls.ContextMenu { Placement = PlacementMode.Top, PlacementTarget = mediaOptions };
        foreach (var item in new[] { _asFile, _asAlbum, _compressPhotos, _compressVideos })
            mediaMenu.Items.Add(item);
        mediaOptions.ContextMenu = mediaMenu;
        mediaOptions.Click += (_, _) => mediaMenu.IsOpen = true;
        buttons.Children.Add(mediaOptions);
        _options = Action(AppStrings.Get("UiSendOptions"), ChooseOptions);
        _options.Margin = new Thickness(0);
        _options.Content = _optionsLabel;
        _options.HorizontalAlignment = HorizontalAlignment.Left;
        Grid.SetColumn(_options, 1);
        buttons.Children.Add(_options);
        var confirmation = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        var cancel = Action(AppStrings.Get("UiCancel"), RequestCancel);
        confirmation.Children.Add(cancel);
        _send = IdentitySetupDialog.ActionButton(AppStrings.Get("UiSend"), Save, Brushes.Lime);
        _send.IsDefault = true;
        confirmation.Children.Add(_send);
        Grid.SetColumn(confirmation, 2);
        buttons.Children.Add(confirmation);
        footer.Children.Add(buttons);
        DockPanel.SetDock(footer, Dock.Bottom);
        panel.Children.Add(footer);

        var content = new StackPanel();
        content.Margin = new Thickness(8);
        content.Children.Add(_files);
        content.Children.Add(_recordingPreview);
        _asFile.Checked += (_, _) => RenderFiles();
        _asFile.Unchecked += (_, _) => RenderFiles();
        _asAlbum.Checked += (_, _) => RenderFiles();
        _asAlbum.Unchecked += (_, _) => RenderFiles();
        UpdateOptions();
        RenderFiles();

        var scroll = new ScrollViewer
        {
            Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            MaxHeight = Math.Min(390, availableHeight * .52)
        };
        scroll.PreviewDragOver += (_, args) =>
        {
            if (!IsAttachmentDrag(args)) return;
            const double edge = 28;
            var position = args.GetPosition(scroll).Y;
            if (position < edge) scroll.ScrollToVerticalOffset(scroll.VerticalOffset - 14);
            else if (position > scroll.ActualHeight - edge) scroll.ScrollToVerticalOffset(scroll.VerticalOffset + 14);
        };
        var fileDropFrame = new Border
        {
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Child = scroll
        };
        fileDropFrame.SetResourceReference(Border.BackgroundProperty, "MessagingTimelineBrush");
        fileDropFrame.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
        panel.Children.Add(fileDropFrame);
        if (_canAddFiles)
        {
            AllowDrop = true;
            PreviewDragOver += (_, args) =>
            {
                if (!args.Data.GetDataPresent(DataFormats.FileDrop, false)) return;
                args.Effects = _sending || _loading || _items.Count >= 10 ? DragDropEffects.None : args.AllowedEffects & DragDropEffects.Copy;
                fileDropFrame.SetResourceReference(Border.BorderBrushProperty,
                    args.Effects == DragDropEffects.Copy ? "AccentBrush" : "MessagingDividerBrush");
                args.Handled = true;
            };
            DragLeave += (_, _) => fileDropFrame.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
            PreviewDrop += async (_, args) =>
            {
                if (!args.Data.GetDataPresent(DataFormats.FileDrop, false)) return;
                args.Handled = true;
                fileDropFrame.SetResourceReference(Border.BorderBrushProperty, "MessagingDividerBrush");
                if (_closed || _sending || _loading || (args.AllowedEffects & DragDropEffects.Copy) == 0 ||
                    args.Data.GetData(DataFormats.FileDrop, false) is not string[] paths)
                {
                    args.Effects = DragDropEffects.None;
                    return;
                }
                args.Effects = DragDropEffects.Copy;
                await AppendFilesAsync(paths);
            };
        }
        KeyboardNavigation.SetTabNavigation(panel, KeyboardNavigationMode.Cycle);
        var root = new Grid();
        root.Children.Add(panel);
        root.Children.Add(_preparationOverlay);
        Content = root;
        void SetComposition(bool active)
        {
            _composing = active;
            _send.IsDefault = !active;
        }
        TextCompositionManager.AddPreviewTextInputStartHandler(_caption, (_, _) => SetComposition(true));
        TextCompositionManager.AddPreviewTextInputUpdateHandler(_caption, (_, _) => SetComposition(true));
        TextCompositionManager.AddPreviewTextInputHandler(_caption, (_, _) => SetComposition(false));
        _caption.LostKeyboardFocus += (_, _) => SetComposition(false);
        _caption.PreviewKeyDown += (_, e) =>
        {
            if (e.Key != Key.Return || Keyboard.Modifiers != ModifierKeys.None || _composing) return;
            e.Handled = true;
            Save();
        };
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key != Key.Escape || _composing) return;
            e.Handled = true;
            if (mediaMenu.IsOpen)
            {
                mediaMenu.IsOpen = false;
                return;
            }
            RequestCancel();
        };
        SourceInitialized += (_, _) =>
        {
            if (PresentationSource.FromVisual(this) is HwndSource source) source.AddHook(HandleSystemClose);
        };
        Loaded += async (_, _) =>
        {
            _caption.Focus();
            if (_recording is not null)
                System.Windows.Application.Current.Deactivated += PauseRecordingPreview;
            OpenRecordingPreview();
            if (initialPaths is not null) await LoadFilesAsync(initialPaths, startIndex: 0);
        };
        Closed += (_, _) =>
        {
            _closed = true;
            System.Windows.Application.Current.Deactivated -= PauseRecordingPreview;
            CloseRecordingPreview();
            if (_preparationModel is { } model) model.PropertyChanged -= PreparationChanged;
            _previewLifetime.Cancel();
            _previewLifetime.Dispose();
        };
    }

    private static Button Action(string text, Action action) => IdentitySetupDialog.ActionButton(text, action, Brushes.Gray);

    private IntPtr HandleSystemClose(IntPtr handle, int message, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        const int systemCommand = 0x0112;
        const int closeCommand = 0xF060;
        if (message == systemCommand && (wParam.ToInt64() & 0xFFF0) == closeCommand)
        {
            handled = true;
            Dispatcher.BeginInvoke(new Action(RequestCancel));
        }
        return IntPtr.Zero;
    }

    private void RequestCancel()
    {
        if (_closed || !IsVisible || _confirmingDiscard) return;
        if (_sending)
        {
            RequestPreparationCancellation();
            return;
        }
        var changed = _captions.Count > 0 || !_items.Select(item => item.Path).SequenceEqual(_initialPaths) ||
            _items.Any(item => item.Edit is not null || item.Trim is not null) ||
            _selection != _initialOptions || (_asFile.IsChecked == true) != _initialAsFile ||
            _asAlbum.IsChecked != true || _compressPhotos.IsChecked == true || _compressVideos.IsChecked == true;
        if (!changed)
        {
            DialogResult = false;
            return;
        }
        _confirmingDiscard = true;
        try
        {
            var dialog = new ConfirmationDialog(AppStrings.Get("UiDiscardAttachmentCaption"),
                title: Title, dismissLabel: AppStrings.Get("UiKeepEditing"), confirmLabel: AppStrings.Get("UiDiscard")) { Owner = this };
            if (dialog.ShowDialog() == true && !_closed && IsVisible) DialogResult = false;
            else if (!_closed && IsVisible) _caption.Focus();
        }
        finally { _confirmingDiscard = false; }
    }

    private void RenderFiles()
    {
        _files.Children.Clear();
        _captionCards.Clear();
        var album = AlbumEligible && _asAlbum.IsChecked == true;
        var wideFirst = album && _items.Count % 2 != 0;
        Panel rows = album ? new ChatAlbumPanel() : _files;
        if (album) _files.Children.Add(rows);
        for (var index = 0; index < _items.Count; index++)
        {
            var item = _items[index];
            var position = index;
            var row = new StackPanel();
            var image = item.Edit?.Preview ?? item.Image;
            var name = item.Edit?.Content.Filename ?? item.Name;
            var canEdit = item.Error is null && item.Image is not null && item.Size <= PhotoCompression.MaximumInput &&
                PhotoCompression.Supports(item.Mime ?? "") && item.Path is not null;
            var canTrim = item.Error is null && item.Path is not null && item.Mime?.StartsWith("video/", StringComparison.Ordinal) == true;
            var editorTitle = canEdit ? AppStrings.Get("UiEditPhoto") : canTrim ? AppStrings.Get("UiEditVideo") : null;
            void OpenEditor()
            {
                if (_loading || _sending) return;
                if (canEdit) ChoosePhotoEdit(position);
                else if (canTrim) ChooseTrim(position);
            }
            if (album)
            {
                row.Children.Add(EditablePreview(AlbumPreview(item, image, position, wideFirst && position == 0),
                    name, editorTitle, OpenEditor));
            }
            else if (_items.Count == 1 && image is not null)
            {
                var preview = new Image { Source = image, MaxHeight = 210, Stretch = Stretch.Uniform };
                var cover = EditablePreview(preview, name, editorTitle, OpenEditor);
                cover.Margin = new Thickness(0, 0, 0, 7);
                row.Children.Add(cover);
            }
            var summary = new DockPanel { LastChildFill = true };
            if (_canAddFiles && _items.Count > 1)
            {
                var handle = CreateDragHandle(item);
                DockPanel.SetDock(handle, Dock.Left);
                summary.Children.Add(handle);
            }
            if (!album && (_items.Count > 1 || image is null))
            {
                var tile = new Border
                {
                    Width = 42, Height = 42, CornerRadius = new CornerRadius(5),
                    Background = (Brush)FindResource("WindowBrush")
                };
                var thumbnail = new Grid();
                thumbnail.Children.Add(image is not null
                    ? new Image { Source = image, Stretch = Stretch.Uniform }
                    : new TextBlock
                    {
                        Text = item.Mime?.StartsWith("video/", StringComparison.Ordinal) == true ? "\uE714" : "\uE8A5",
                        FontFamily = new FontFamily("Segoe MDL2 Assets"), FontSize = 16,
                        VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center
                    });
                if (_items.Count > 1) thumbnail.Children.Add(AttachmentNumber(position, 2));
                tile.Child = thumbnail;
                var cover = EditablePreview(tile, name, editorTitle, OpenEditor);
                cover.Margin = new Thickness(0, 0, 8, 0);
                DockPanel.SetDock(cover, Dock.Left);
                summary.Children.Add(cover);
            }

            if (_canAddFiles)
            {
                var reorder = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
                if (_items.Count > 1)
                {
                    reorder.Children.Add(RowAction("↑", AppStrings.Get("UiMoveUp"), () => Move(position, position - 1), !_loading && position > 0));
                    reorder.Children.Add(RowAction("↓", AppStrings.Get("UiMoveDown"), () => Move(position, position + 1), !_loading && position < _items.Count - 1));
                }
                reorder.Children.Add(RowAction("×", AppStrings.Get("UiRemoveAttachment"), () =>
                {
                    ReplacePhotoEdit(position, null);
                    _captions.Remove(_items[position].Id);
                    _items.RemoveAt(position);
                    _error.Text = "";
                    RenderFiles();
                }, !_loading));
                if (item.Error is not null && item.Path is { } failedPath)
                {
                    reorder.Children.Insert(0, RowAction("⇄", AppStrings.Get("UiReplaceAttachment"),
                        () => _ = ReplaceFileAsync(position, failedPath), !_loading));
                    reorder.Children.Insert(0, RowAction("↻", AppStrings.Get("UiRetry"), () => _ = RetryFileAsync(position, failedPath), !_loading));
                }
                DockPanel.SetDock(reorder, Dock.Right);
                summary.Children.Add(reorder);
            }

            var metadata = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            metadata.Children.Add(new TextBlock { Text = name, TextTrimming = TextTrimming.CharacterEllipsis, ToolTip = name });
            var size = item.Edit?.Content.Bytes.LongLength ?? item.Size;
            metadata.Children.Add(new TextBlock
            {
                Text = item.Pending ? AppStrings.Get("UiLoading") : item.Error is { } error ? AppStrings.Get(error) : (item.Trim is { } trim
                    ? $"{VideoTrimDialog.DisplayTime(trim.Start)} – {VideoTrimDialog.DisplayTime(trim.End)}" + (trim.MuteAudio ? " · " + AppStrings.Get("UiAudioRemoved") : "")
                    : AppStrings.FileSize(size)),
                FontSize = 11,
                TextWrapping = TextWrapping.Wrap,
                Foreground = (Brush)FindResource(item.Error is null ? "MutedBrush" : "DangerBrush")
            });
            summary.Children.Add(metadata);
            row.Children.Add(summary);
            if (canEdit || canTrim)
            {
                var controls = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
                if (canEdit)
                {
                    var photo = Action(AppStrings.Get("UiEditPhoto"), OpenEditor);
                    photo.IsEnabled = !_loading;
                    controls.Children.Add(photo);
                    if (item.Edit is not null)
                    {
                        var undo = Action(AppStrings.Get("UiUndoEdit"), () => { ReplacePhotoEdit(position, null); RenderFiles(); });
                        undo.IsEnabled = !_loading;
                        controls.Children.Add(undo);
                    }
                }
                if (canTrim)
                {
                    var edit = Action(AppStrings.Get("UiEditVideo"), OpenEditor);
                    edit.IsEnabled = !_loading;
                    controls.Children.Add(edit);
                    if (item.Trim is not null)
                    {
                        var undo = Action(AppStrings.Get("UiUndoVideoEdits"), () => { _items[position] = item with { Trim = null }; RenderFiles(); });
                        undo.IsEnabled = !_loading;
                        controls.Children.Add(undo);
                    }
                }
                row.Children.Add(controls);
            }
            var card = new Border
            {
                Child = row, Margin = album ? new Thickness(3) : new Thickness(0, 0, 0, 6), Padding = new Thickness(7),
                CornerRadius = new CornerRadius(7), Background = (Brush)FindResource("SurfaceBrush")
            };
            _captionCards[item.Id] = card;
            if (_canAddFiles && _items.Count > 1) ConfigureDropTarget(card, item);
            rows.Children.Add(card);
        }

        if (_canAddFiles)
        {
            var add = Action(AppStrings.Get("UiAddFiles"), () => _ = AddFilesAsync());
            add.IsEnabled = !_loading && _items.Count < 10;
            _files.Children.Add(add);
            if (_items.Any(item => item.Error is not null))
            {
                var removeUnavailable = Action(AppStrings.Get("UiRemoveUnavailableAttachments"), () =>
                {
                    for (var index = _items.Count - 1; index >= 0; index--)
                    {
                        if (_items[index].Error is null) continue;
                        ReplacePhotoEdit(index, null);
                        _captions.Remove(_items[index].Id);
                        _items.RemoveAt(index);
                    }
                    _error.Text = "";
                    RenderFiles();
                });
                removeUnavailable.IsEnabled = !_loading;
                _files.Children.Add(removeUnavailable);
            }
        }
        Title = _items.Count > 1 ? AppStrings.Format("UiSendAttachments", _items.Count) : AppStrings.Get("UiSendAttachment");
        _heading.Text = Title;
        UpdateBatchSize();
        UpdateCaptionTargets();
        RefreshSendButton();
        UpdateMediaOptions();
        if (_albumLayout != album && IsLoaded && SystemParameters.ClientAreaAnimation)
            _files.BeginAnimation(OpacityProperty, new DoubleAnimation(0.7, 1, TimeSpan.FromMilliseconds(160))
            {
                FillBehavior = FillBehavior.Stop
            });
        _albumLayout = album;
    }

    private void RefreshSendButton()
    {
        _send.Content = _preparationFailed ? AppStrings.Get("UiSendRetry") :
            _items.Count > 1 ? AppStrings.Format("UiSendCount", _items.Count) : AppStrings.Get("UiSend");
        _send.IsEnabled = !_sending && !_loading && _items.Count > 0 && _items.All(item => item.Error is null);
    }

    private void UpdateCaptionTargets()
    {
        var targets = _items.Select((item, index) =>
            new CaptionTarget(item.Id, AppStrings.Format("UiAttachmentCaptionTarget", index + 1, item.Edit?.Content.Filename ?? item.Name))).ToArray();
        _captionTarget.Visibility = targets.Length > 1 ? Visibility.Visible : Visibility.Collapsed;
        _caption.IsEnabled = targets.Length > 0;
        if (!_captionTargets.SequenceEqual(targets))
        {
            _updatingCaptionTarget = true;
            _captionTargets = targets;
            _captionTarget.ItemsSource = _captionTargets;
            _updatingCaptionTarget = false;
        }
        var current = _captionId;
        ShowCaption(current is { } id && _captionTargets.Any(target => target.Id == id)
            ? current : _captionTargets.FirstOrDefault()?.Id);
    }

    private void ShowCaption(Guid? id)
    {
        _updatingCaptionTarget = true;
        _captionTarget.SelectedValue = id;
        if (_captionId != id)
        {
            _captionId = id;
            _caption.Text = id is { } target ? _captions.GetValueOrDefault(target, "") : "";
        }
        _updatingCaptionTarget = false;
        foreach (var (itemId, card) in _captionCards)
            card.BorderBrush = CaptionOutline(itemId);
    }

    private Brush CaptionOutline(Guid id) => _items.Count > 1 && _captionId == id
        ? (Brush)FindResource("MutedBrush") : Brushes.Transparent;

    private void UpdateBatchSize()
    {
        _batchSize.Visibility = !_loading && _items.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
        if (_batchSize.Visibility != Visibility.Visible) return;
        var sizes = _items.Where(item => !item.Pending && item.Error is null)
            .Select(item => item.Edit?.Content.Bytes.LongLength ?? item.Size).ToArray();
        _batchSize.Text = sizes.Length == 0 ? AppStrings.Get("UiAttachmentSizeUnknown") :
            AppStrings.Format(sizes.Length == _items.Count ? "UiAttachmentSourceSize" : "UiAttachmentSourceSizePartial",
                AppStrings.FileSize(sizes.Sum()));
    }

    private FrameworkElement EditablePreview(FrameworkElement preview, string name, string? actionTitle, Action action)
    {
        if (actionTitle is null) return preview;
        var button = new Button
        {
            Content = preview, ToolTip = actionTitle, IsEnabled = !_loading, Padding = new Thickness(0),
            BorderBrush = Brushes.Transparent, Background = Brushes.Transparent, MinHeight = 0,
            HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch
        };
        System.Windows.Automation.AutomationProperties.SetName(button, $"{actionTitle} · {name}");
        void Activate()
        {
            action();
            if (!_closed) _caption.Focus();
        }
        button.Click += (_, _) => Activate();
        button.PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Return && Keyboard.Modifiers == ModifierKeys.None) e.Handled = true;
        };
        button.PreviewKeyUp += (_, e) =>
        {
            if (e.Key != Key.Return || Keyboard.Modifiers != ModifierKeys.None) return;
            e.Handled = true;
            Activate();
        };
        return button;
    }

    private Border AlbumPreview(Attachment item, ImageSource? image, int position, bool wide)
    {
        var isVideo = item.Mime?.StartsWith("video/", StringComparison.Ordinal) == true;
        var preview = new Grid { Height = wide ? 168 : 116, ClipToBounds = true, Background = (Brush)FindResource("WindowBrush") };
        if (image is not null) preview.Children.Add(new Image { Source = image, Stretch = Stretch.UniformToFill });
        if (image is null || isVideo)
            preview.Children.Add(new TextBlock
            {
                Text = isVideo ? "\uE768" : "\uEB9F", FontFamily = new FontFamily("Segoe MDL2 Assets"), FontSize = 20,
                Foreground = image is null ? (Brush)FindResource("MutedBrush") : Brushes.White,
                VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center
            });
        preview.Children.Add(AttachmentNumber(position, 5));
        return new Border { Child = preview, Margin = new Thickness(0, 0, 0, 6) };
    }

    private static Border AttachmentNumber(int position, double margin)
    {
        return new Border
        {
            CornerRadius = new CornerRadius(9), Background = new SolidColorBrush(Color.FromArgb(170, 0, 0, 0)),
            VerticalAlignment = VerticalAlignment.Top, HorizontalAlignment = HorizontalAlignment.Right,
            Margin = new Thickness(margin), Padding = new Thickness(5, 1, 5, 1),
            Child = new TextBlock { Text = (position + 1).ToString(CultureInfo.CurrentCulture), Foreground = Brushes.White, FontSize = 11 }
        };
    }

    private static Button RowAction(string glyph, string title, Action action, bool enabled)
    {
        var button = Action(glyph, action);
        button.MinWidth = 24;
        button.MinHeight = 28;
        button.Padding = new Thickness(4, 2, 4, 2);
        button.Margin = new Thickness(2, 0, 0, 0);
        button.ToolTip = title;
        button.IsEnabled = enabled;
        System.Windows.Automation.AutomationProperties.SetName(button, title);
        return button;
    }

    private bool AlbumEligible => _asFile.IsChecked != true && _items.Count >= 2 &&
        _items.All(item => item.Error is null && item.Path is { } path &&
            EncryptedObjectStore.IsAlbumMedia(EncryptedObjectStore.MimeForPath(path)));

    private bool CanCompressPhotos => _asFile.IsChecked != true && _items.Any(item =>
        item.Error is null && PhotoCompression.Supports(item.Mime ?? ""));

    private bool CanCompressVideos => _asFile.IsChecked != true && _items.Any(item =>
        item.Error is null && item.Mime?.StartsWith("video/", StringComparison.Ordinal) == true);

    private void UpdateMediaOptions()
    {
        _asAlbum.Visibility = AlbumEligible ? Visibility.Visible : Visibility.Collapsed;
        _compressPhotos.Visibility = CanCompressPhotos ? Visibility.Visible : Visibility.Collapsed;
        _compressVideos.Visibility = CanCompressVideos ? Visibility.Visible : Visibility.Collapsed;
    }

    private void Move(int from, int to)
    {
        if (_loading || from == to || from < 0 || from >= _items.Count || to < 0 || to >= _items.Count) return;
        var item = _items[from];
        _items.RemoveAt(from);
        _items.Insert(to, item);
        RenderFiles();
    }

    private Border CreateDragHandle(Attachment item)
    {
        var handle = new Border
        {
            Width = 24, Background = Brushes.Transparent, Cursor = Cursors.SizeNS,
            ToolTip = AppStrings.Get("UiDragToReorder"), IsEnabled = !_loading,
            Child = new TextBlock
            {
                Text = "☰", FontFamily = new FontFamily("Segoe UI Symbol"), FontSize = 14,
                Foreground = (Brush)FindResource("MutedBrush"), HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            }
        };
        Point? pressedAt = null;
        handle.PreviewMouseLeftButtonDown += (_, args) =>
        {
            if (handle.CaptureMouse()) pressedAt = args.GetPosition(handle);
        };
        handle.PreviewMouseLeftButtonUp += (_, _) => handle.ReleaseMouseCapture();
        handle.LostMouseCapture += (_, _) => pressedAt = null;
        handle.PreviewMouseMove += (_, args) =>
        {
            if (_loading || _draggedAttachment is not null || args.LeftButton != MouseButtonState.Pressed ||
                pressedAt is not { } start) return;
            var delta = args.GetPosition(handle) - start;
            if (Math.Abs(delta.X) < SystemParameters.MinimumHorizontalDragDistance &&
                Math.Abs(delta.Y) < SystemParameters.MinimumVerticalDragDistance) return;
            handle.ReleaseMouseCapture();
            args.Handled = true;
            DragAttachment(handle, item);
        };
        return handle;
    }

    private void DragAttachment(Border handle, Attachment item)
    {
        _draggedAttachment = item.Id;
        _attachmentDropTarget = null;
        try
        {
            var result = DragDrop.DoDragDrop(handle, new DataObject(ReorderFormat, "reorder"), DragDropEffects.Move);
            if (result == DragDropEffects.Move && _attachmentDropTarget is { } target)
                Move(_items.FindIndex(value => value.Id == item.Id), _items.FindIndex(value => value.Id == target));
        }
        finally
        {
            _draggedAttachment = _attachmentDropTarget = null;
        }
    }

    private bool IsAttachmentDrag(System.Windows.DragEventArgs args) =>
        !_loading && _draggedAttachment is not null && args.Data.GetDataPresent(ReorderFormat, false);

    private void ConfigureDropTarget(Border card, Attachment item)
    {
        card.AllowDrop = true;
        card.BorderThickness = new Thickness(1);
        card.BorderBrush = Brushes.Transparent;
        card.DragOver += (_, args) =>
        {
            var accepts = IsAttachmentDrag(args) && _draggedAttachment != item.Id;
            card.BorderBrush = accepts ? (Brush)FindResource("AccentBrush") : CaptionOutline(item.Id);
            args.Effects = accepts ? DragDropEffects.Move : DragDropEffects.None;
            args.Handled = true;
        };
        card.DragLeave += (_, _) => card.BorderBrush = CaptionOutline(item.Id);
        card.Drop += (_, args) =>
        {
            card.BorderBrush = CaptionOutline(item.Id);
            if (IsAttachmentDrag(args) && _draggedAttachment != item.Id)
            {
                _attachmentDropTarget = item.Id;
                args.Effects = DragDropEffects.Move;
            }
            else args.Effects = DragDropEffects.None;
            args.Handled = true;
        };
    }

    private async Task AddFilesAsync()
    {
        var picker = new OpenFileDialog { Multiselect = true, Filter = "All files|*.*" };
        if (_loading || picker.ShowDialog(this) != true) return;
        await AppendFilesAsync(picker.FileNames);
    }

    private async Task AppendFilesAsync(IReadOnlyList<string> paths)
    {
        if (_closed || _loading || _sending) return;
        var existing = _items.Select(item => item.Path).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var additions = paths.Where(path => existing.Add(path)).ToArray();
        if (additions.Length == 0) return;
        if (_items.Count + additions.Length > 10)
        {
            _error.Text = AppStrings.Get("UiAttachmentLimit");
            return;
        }
        _loading = true;
        _error.Text = "";
        var startIndex = _items.Count;
        _items.AddRange(additions.Select(CreatePendingAttachment));
        RenderFiles();
        await LoadFilesAsync(additions, startIndex);
    }

    private Task RetryFileAsync(int position, string path)
    {
        if (_closed || _loading || position < 0 || position >= _items.Count || _items[position].Path != path)
            return Task.CompletedTask;
        _loading = true;
        _error.Text = "";
        _items[position] = _items[position] with { Pending = true, Error = null };
        RenderFiles();
        return LoadFilesAsync([path], position);
    }

    private async Task ReplaceFileAsync(int position, string previous)
    {
        bool CanReplace() => !_closed && !_loading && !_sending && position >= 0 && position < _items.Count &&
            _items[position].Error is not null && StringComparer.OrdinalIgnoreCase.Equals(_items[position].Path, previous);
        if (!CanReplace()) return;
        var picker = new OpenFileDialog
        {
            Title = AppStrings.Get("UiReplaceAttachment"),
            Filter = $"{AppStrings.Get("UiFiles")}|*.*"
        };
        if (picker.ShowDialog(this) != true || !CanReplace()) return;
        var replacement = picker.FileName;
        if (_items.Where((_, index) => index != position)
            .Any(item => StringComparer.OrdinalIgnoreCase.Equals(item.Path, replacement)))
        {
            _error.Text = AppStrings.Get("UiAttachmentAlreadySelected");
            return;
        }
        _loading = true;
        _error.Text = "";
        ReplacePhotoEdit(position, null);
        _items[position] = CreatePendingAttachment(replacement) with { Id = _items[position].Id };
        RenderFiles();
        await LoadFilesAsync([replacement], position);
    }

    private async Task LoadFilesAsync(IReadOnlyList<string> paths, int startIndex)
    {
        var token = _previewLifetime.Token;
        Attachment[] described;
        try
        {
            described = await Task.Run(() => paths.Select(path =>
            {
                token.ThrowIfCancellationRequested();
                return DescribeFile(path);
            }).ToArray(), token);
            if (_closed) return;
            for (var index = 0; index < described.Length; index++)
            {
                var position = startIndex + index;
                var previous = _items[position];
                var current = described[index];
                var sameVideo = StringComparer.OrdinalIgnoreCase.Equals(previous.Path, current.Path) &&
                    current.Mime?.StartsWith("video/", StringComparison.Ordinal) == true;
                _items[position] = current with { Id = previous.Id, Trim = sameVideo ? previous.Trim : null };
            }
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { return; }
        finally
        {
            _loading = false;
            if (!_closed) RenderFiles();
        }
        foreach (var item in described)
        {
            if (_closed) return;
            if (item.Error is not null || item.Path is not { } path || item.Mime is not { } mime ||
                !mime.StartsWith("video/", StringComparison.Ordinal)) continue;
            try
            {
                var image = await VideoThumbnail.LoadAsync(() => new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read),
                    mime, token);
                if (_closed || image is null) continue;
                var index = _items.FindIndex(current => current.Id == item.Id &&
                    StringComparer.OrdinalIgnoreCase.Equals(current.Path, path));
                if (index < 0) continue;
                _items[index] = _items[index] with { Image = image };
                if (_draggedAttachment is null && !_loading) RenderFiles();
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested) { return; }
        }
    }

    private void ChooseOptions()
    {
        _recordingPlayback?.PausePlayback();
        var dialog = new SendOptionsDialog(_selection) { Owner = this };
        if (dialog.ShowDialog() == true && !_closed && IsVisible)
        {
            _selection = dialog.Result;
            UpdateOptions();
        }
        if (!_closed && IsVisible) _caption.Focus();
    }

    private void ChooseTrim(int position)
    {
        var item = _items[position];
        if (_loading || item.Path is not { } path) return;
        var dialog = new VideoTrimDialog(path, item.Trim) { Owner = this };
        if (dialog.ShowDialog() != true) return;
        _items[position] = item with { Trim = dialog.Result };
        _error.Text = "";
        RenderFiles();
    }

    private void ChoosePhotoEdit(int position)
    {
        var item = _items[position];
        if (_loading || item.Mime is not { } mime || item.Path is not { } path) return;
        var editor = new PhotoEditDialog(path, null, mime, item.Edit?.Adjustment ?? PhotoAdjustment.Original) { Owner = this };
        if (editor.ShowDialog() != true) return;
        if (editor.Result is not { } result)
        {
            ReplacePhotoEdit(position, null);
            _error.Text = "";
            RenderFiles();
            return;
        }
        var existing = _items.Where((_, index) => index != position).Sum(source => source.Edit?.Content.Bytes.LongLength ?? 0);
        if (existing + result.Bytes.Length > 64L * 1024 * 1024)
        {
            CryptographicOperations.ZeroMemory(result.Bytes);
            _error.Text = AppStrings.Get("UiUndoOtherEdit");
            return;
        }
        var filename = item.Path is null ? item.Name : string.Concat(Path.GetFileNameWithoutExtension(item.Name).Take(160)) +
            (mime == "image/png" ? "-edited.png" : "-edited.jpg");
        ReplacePhotoEdit(position, new PhotoEdit(new EditedPhotoContent(filename, result.Bytes), result.Preview, editor.Adjustment));
        _error.Text = "";
        RenderFiles();
    }

    private void ReplacePhotoEdit(int position, PhotoEdit? replacement)
    {
        var item = _items[position];
        if (item.Edit is not null) CryptographicOperations.ZeroMemory(item.Edit.Content.Bytes);
        _items[position] = item with { Edit = replacement };
    }

    private void ClearPhotoEdits()
    {
        for (var index = 0; index < _items.Count; index++) ReplacePhotoEdit(index, null);
    }

    private void UpdateOptions()
    {
        var summary = SendOptionsText.Format(_selection);
        var label = summary.Length == 0 ? AppStrings.Get("UiSendOptions") : summary;
        _optionsLabel.Text = label;
        _options.ToolTip = label;
        System.Windows.Automation.AutomationProperties.SetName(_options, label);
    }

    private async void Save()
    {
        if (_sending || !_send.IsEnabled) return;
        var longCaption = _items.FirstOrDefault(item =>
            Encoding.UTF8.GetByteCount(_captions.GetValueOrDefault(item.Id, "")) > 48 * 1024);
        if (longCaption is not null)
        {
            ShowCaption(longCaption.Id);
            _caption.Focus();
            _error.Text = AppStrings.Get("UiCaptionTooLong");
            return;
        }
        if (_selection.SendAt is { } due && due <= DateTimeOffset.UtcNow.ToUnixTimeMilliseconds())
        {
            _error.Text = AppStrings.Get("UiChooseFuture");
            return;
        }
        var captions = _items.Select(item => _captions.GetValueOrDefault(item.Id, "")).ToArray();
        Result = new MediaSendChoice(captions[0], _selection, _asFile.IsChecked == true, AlbumEligible && _asAlbum.IsChecked == true,
            CanCompressPhotos && _compressPhotos.IsChecked == true,
            CanCompressVideos && _compressVideos.IsChecked == true,
            _items.Where(item => item.Trim is not null).ToDictionary(item => item.Path!, item => item.Trim!, StringComparer.Ordinal),
            _items.Where(item => item.Path is not null && item.Edit is not null)
                .ToDictionary(item => item.Path!, item => item.Edit!.Content, StringComparer.Ordinal), captions);
        if (_submit is not { } send) return;
        _activePreparationId = null;
        _preparationFailed = false;
        _sending = true;
        RefreshSendButton();
        CloseRecordingPreview();
        _error.Text = "";
        _editor.IsEnabled = false;
        _preparationOverlay.Visibility = Visibility.Visible;
        RefreshPreparation();
        _preparationOverlay.Focus();
        if (_preparationModel is { } model) model.PropertyChanged += PreparationChanged;
        var revalidate = false;
        try
        {
            var result = await send(Result, id =>
            {
                _activePreparationId = id;
                if (_closed) return;
                RefreshPreparation();
                if (_cancelActivePreparation.IsVisible) _cancelActivePreparation.Focus();
            });
            if (_closed) return;
            if (result == PreparationSendResult.Queued) DialogResult = true;
            else if (result == PreparationSendResult.Failed)
            {
                _preparationFailed = true;
                _error.Text = AppStrings.Get("UiAttachmentSendFailed");
                revalidate = true;
            }
        }
        catch (OperationCanceledException)
        {
            // The batch remains in the editor after the worker has released its sources.
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or UnauthorizedAccessException)
        {
            if (!_closed)
            {
                _preparationFailed = true;
                _error.Text = AppStrings.Get("UiAttachmentSendFailed");
                revalidate = true;
            }
        }
        finally
        {
            _sending = false;
            _activePreparationId = null;
            if (_preparationModel is { } preparationModel) preparationModel.PropertyChanged -= PreparationChanged;
            if (_closed) ClearPhotoEdits();
            else
            {
                _preparationOverlay.Visibility = Visibility.Collapsed;
                _editor.IsEnabled = true;
                RefreshSendButton();
                OpenRecordingPreview();
                _caption.Focus();
            }
        }
        if (revalidate && !_closed) await RevalidateFileSourcesAsync();
    }

    private async Task RevalidateFileSourcesAsync()
    {
        if (_loading || _closed) return;
        var sources = _items.Select((item, index) => (Item: item, Index: index))
            .Where(entry => entry.Item.Path is not null && entry.Item.Edit is null).ToArray();
        if (sources.Length == 0) return;

        var token = _previewLifetime.Token;
        _loading = true;
        _recheckingSources.Visibility = Visibility.Visible;
        RenderFiles();
        try
        {
            var failed = await Task.Run(() => sources.Select(entry =>
            {
                token.ThrowIfCancellationRequested();
                var current = DescribeFile(entry.Item.Path!, createPreview: false);
                return (entry.Index, entry.Item, Current: current);
            }).Where(entry => entry.Current.Error is not null).ToArray(), token);
            if (_closed) return;
            foreach (var entry in failed)
            {
                if (entry.Index < _items.Count && ReferenceEquals(_items[entry.Index], entry.Item))
                {
                    _items[entry.Index] = entry.Item with
                    {
                        Error = entry.Current.Error,
                        Size = entry.Current.Size,
                        Image = null
                    };
                }
            }
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        finally
        {
            _loading = false;
            if (!_closed)
            {
                _recheckingSources.Visibility = Visibility.Collapsed;
                RenderFiles();
            }
        }
    }

    private void RequestPreparationCancellation()
    {
        if (_sending && _activePreparationId is { } id) _preparationModel?.CancelPreparation(id);
    }

    private void PreparationChanged(object? sender, PropertyChangedEventArgs args)
    {
        if (!_closed && _sending && args.PropertyName == nameof(MainViewModel.PendingPreparations))
            RefreshPreparation();
    }

    private void RefreshPreparation()
    {
        var active = _activePreparationId is { } id
            ? _preparationModel?.PendingPreparations.FirstOrDefault(row => row.Id == id) : null;
        var index = active is { Progress.Phase: "preparing" } ? active.Progress.Attachment - 1 : -1;
        _preparationFile.Text = _items.Count > 1 && index >= 0 && index < _items.Count
            ? _items[index].Edit?.Content.Filename ?? _items[index].Name : "";
        _preparationFile.Visibility = _preparationFile.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        _preparationStatus.Text = active?.Status ?? AppStrings.Get("UiPreparationWaiting");
        _preparationProgress.IsIndeterminate = active?.Indeterminate != false;
        _preparationProgress.Value = active is { Progress.Total: > 0 } row
            ? 100 * Math.Clamp((double)row.Progress.Completed / row.Progress.Total, 0, 1) : 0;
        var canCancel = active?.Progress.CanCancel == true;
        if (!canCancel && _cancelActivePreparation.IsKeyboardFocused) _preparationOverlay.Focus();
        _cancelActivePreparation.Visibility = canCancel ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OpenRecordingPreview()
    {
        if (_recording is not { } file || _recordingPlayback is not null || _closed) return;
        _recordingPlayback = new EncryptedMediaView(file.Mime, file.OpenRead, file.Size, () => { },
            showClose: false, inlineAudio: true, autoplay: false);
        System.Windows.Automation.AutomationProperties.SetName(_recordingPlayback, AppStrings.Get("UiRecordingPreview"));
        _recordingPreview.Children.Add(_recordingPlayback);
        _recordingPreview.Visibility = Visibility.Visible;
    }

    private void PauseRecordingPreview(object? sender, EventArgs args) => _recordingPlayback?.PausePlayback();

    private void CloseRecordingPreview()
    {
        if (_recordingPlayback is not { } playback) return;
        _recordingPlayback = null;
        playback.Dispose();
        _recordingPreview.Children.Clear();
    }

    private static Attachment DescribeFile(string path, bool createPreview = true)
    {
        try
        {
            var file = new FileInfo(path);
            if (!file.Exists || (file.Attributes & FileAttributes.Directory) != 0)
                return new Attachment(file.Name, 0, null, path, "UiSelectedFileOpenError");
            var size = file.Length;
            if (size > 100L * 1024 * 1024)
                return new Attachment(file.Name, size, null, path, "UiAttachmentSizeLimit");
            using var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            ImageSource? image = null;
            var mime = EncryptedObjectStore.MimeForPath(path);
            if (createPreview && mime.StartsWith("image/", StringComparison.Ordinal))
            {
                try
                {
                    image = LoadPreview(input);
                }
                catch (Exception error) when (error is IOException or InvalidOperationException or NotSupportedException or ArgumentException)
                {
                    // The original file remains available when its thumbnail cannot be decoded.
                }
            }
            return new Attachment(file.Name, size, image, path, Mime: mime);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return new Attachment(Path.GetFileName(path), 0, null, path, "UiSelectedFileOpenError");
        }
    }

    private static System.Windows.Media.Imaging.BitmapSource LoadPreview(Stream input)
    {
        var image = ImageDecoder.Load(input, 512);
        if (image.CanFreeze) image.Freeze();
        return image;
    }

    public static void ReviewAndSendFiles(Window owner, IReadOnlyList<string> paths, string recipient, SendOptions initial,
        Func<IReadOnlyList<string>, MediaSendChoice, Action<string>, Task<PreparationSendResult>> send,
        MainViewModel preparationModel, bool initialAsFile = false)
    {
        var distinct = paths.Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        if (distinct.Length == 0 || !owner.IsLoaded) return;
        if (distinct.Length > 10)
        {
            TransientNotice.Show(owner, AppStrings.Get("UiAttachmentLimit"));
            return;
        }
        var placeholders = distinct.Select(CreatePendingAttachment);
        var dialog = new MediaSendDialog(owner, placeholders, recipient, initial, initialPaths: distinct, initialAsFile: initialAsFile)
        {
            _preparationModel = preparationModel
        };
        dialog._submit = (choice, started) => send(dialog._items.Select(item => item.Path!).ToArray(), choice, started);
        try
        {
            dialog.ShowDialog();
        }
        finally { if (!dialog._sending) dialog.ClearPhotoEdits(); }
    }

    private static Attachment CreatePendingAttachment(string path) =>
        new(Path.GetFileName(path), 0, null, path, Mime: EncryptedObjectStore.MimeForPath(path), Pending: true);

    public static void ReviewAndSendRecording(Window owner, EncryptedCaptureFile file, string recipient, SendOptions initial,
        Func<MediaSendChoice, Action<string>, Task<PreparationSendResult>> send, MainViewModel preparationModel,
        Action<Window>? show = null)
    {
        if (!owner.IsLoaded) return;
        var dialog = new MediaSendDialog(owner, [new Attachment(file.Filename, file.Size, null, Mime: file.Mime)],
            recipient, initial, recording: file)
        {
            _submit = async (choice, started) =>
            {
                using var lease = file.RetainFrozen();
                return await send(choice, started);
            },
            _preparationModel = preparationModel
        };
        if (show is null) dialog.ShowDialog();
        else show(dialog);
    }

}
