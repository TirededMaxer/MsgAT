// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Views;

internal sealed class PhotoEditDialog : DialogWindow
{
    private const double Minimum = .04;
    private const double HandleRadius = 5;
    private readonly Canvas _canvas = new() { Background = Brushes.Black, ClipToBounds = true };
    private readonly Image _image = new() { Stretch = Stretch.Fill, RenderTransformOrigin = new Point(.5, .5) };
    private readonly Rectangle _outline = new() { Stroke = Brushes.White, StrokeThickness = 1 };
    private readonly Rectangle[] _shade;
    private readonly Ellipse[] _handles;
    private readonly Line[] _grid;
    private readonly TextBlock _status = new() { Text = AppStrings.Get("UiOpeningPhoto"), TextWrapping = TextWrapping.Wrap };
    private readonly Button _rotateLeft;
    private readonly Button _rotateRight;
    private readonly Button _flip;
    private readonly Button _ratio;
    private readonly Button _reset;
    private readonly Button _apply;
    private readonly Button _cancel;
    private BitmapSource? _original;
    private readonly PhotoAdjustment _initial;
    private PhotoAdjustment _adjustment;
    private Rect _fit;
    private Point _dragStart;
    private Rect _dragCrop;
    private int? _dragHandle;
    private bool _saving;
    private bool _closed;
    private CancellationTokenSource? _load;

    public (byte[] Bytes, BitmapSource Preview)? Result { get; private set; }
    public PhotoAdjustment Adjustment => _adjustment;

    public PhotoEditDialog(string? path, byte[]? bytes, string mime, PhotoAdjustment initial)
    {
        _adjustment = _initial = initial;
        Title = AppStrings.Get("UiEditPhoto");
        Width = Math.Min(670, SystemParameters.WorkArea.Width * .94);
        Height = Math.Min(530, SystemParameters.WorkArea.Height * .90);
        var panel = new DockPanel { Margin = new Thickness(16) };
        var title = IdentitySetupDialog.Heading(Title);
        DockPanel.SetDock(title, Dock.Top);
        panel.Children.Add(title);
        var footer = new StackPanel { Margin = new Thickness(0, 8, 0, 0) };
        footer.Children.Add(_status);
        var controls = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        _rotateLeft = Tool("PhotoRotateLeftIcon", "UiRotateLeft", () => Adjust(_adjustment.Rotate(false)));
        _rotateRight = Tool("PhotoRotateRightIcon", "UiRotateRight", () => Adjust(_adjustment.Rotate(true)));
        _flip = Tool("PhotoFlipIcon", "UiMirrorPhoto", () => Adjust(_adjustment.Flip()));
        var ratioMenu = new ContextMenu { Placement = PlacementMode.Top };
        _ratio = Tool("PhotoCropRatioIcon", "UiCropRatio", () => ratioMenu.IsOpen = true);
        _ratio.ContextMenu = ratioMenu;
        ratioMenu.PlacementTarget = _ratio;
        foreach (var preset in Enum.GetValues<PhotoCropRatio>())
        {
            var item = new MenuItem { Header = RatioLabel(preset), IsCheckable = true };
            item.Click += (_, _) =>
            {
                if (_fit.Height > 0 && _adjustment.Ratio != preset)
                    Adjust(_adjustment.WithRatio(preset, _fit.Width / _fit.Height));
            };
            ratioMenu.Items.Add(item);
            ratioMenu.Opened += (_, _) => item.IsChecked = _adjustment.Ratio == preset;
        }
        _reset = Tool("PhotoResetIcon", "UiResetCrop", () => Adjust(PhotoAdjustment.Original));
        _apply = Action(AppStrings.Get("UiApply"), () => _ = ApplyAsync(mime));
        _apply.IsDefault = true;
        _cancel = Action(AppStrings.Get("UiCancel"), () => DialogResult = false);
        _cancel.IsCancel = true;
        foreach (var button in new[] { _rotateLeft, _rotateRight, _flip, _ratio, _reset, _cancel, _apply })
            controls.Children.Add(button);
        footer.Children.Add(controls);
        DockPanel.SetDock(footer, Dock.Bottom);
        panel.Children.Add(footer);
        panel.Children.Add(_canvas);
        Content = panel;

        _shade = Enumerable.Range(0, 4).Select(_ => new Rectangle { Fill = Brushes.Black, Opacity = .57 }).ToArray();
        _handles = Enumerable.Range(0, 4).Select(_ => new Ellipse
        {
            Fill = Brushes.White, Width = HandleRadius * 2, Height = HandleRadius * 2
        }).ToArray();
        _grid = Enumerable.Range(0, 4).Select(_ => new Line
        {
            Stroke = Brushes.White, StrokeThickness = 1, Opacity = .3, IsHitTestVisible = false
        }).ToArray();
        _canvas.Children.Add(_image);
        foreach (var shape in _shade) _canvas.Children.Add(shape);
        foreach (var line in _grid) _canvas.Children.Add(line);
        _canvas.Children.Add(_outline);
        foreach (var handle in _handles) _canvas.Children.Add(handle);
        _canvas.SizeChanged += (_, _) => Redraw();
        _canvas.MouseLeftButtonDown += StartDrag;
        _canvas.MouseMove += Drag;
        _canvas.MouseLeftButtonUp += (_, _) => _canvas.ReleaseMouseCapture();
        _canvas.LostMouseCapture += (_, _) => { _dragHandle = null; SetGridActive(false); };
        Loaded += async (_, _) => await OpenAsync(path, bytes, mime);
        Closed += (_, _) =>
        {
            _closed = true;
            _load?.Cancel();
            _canvas.ReleaseMouseCapture();
            _image.Source = null;
            _original = null;
        };
        Redraw();
    }

    private static Button Action(string label, Action action) => IdentitySetupDialog.ActionButton(label, action, Brushes.Gray);

    private static Button Tool(string icon, string key, Action action)
    {
        var label = AppStrings.Get(key);
        var button = new Button { Margin = new Thickness(2), ToolTip = label };
        button.SetResourceReference(StyleProperty, "IconButtonStyle");
        button.SetResourceReference(ContentControl.ContentProperty, icon);
        System.Windows.Automation.AutomationProperties.SetName(button, label);
        button.Click += (_, _) => action();
        return button;
    }

    private static string RatioLabel(PhotoCropRatio ratio)
    {
        switch (ratio)
        {
            case PhotoCropRatio.Free: return AppStrings.Get("UiCropFree");
            case PhotoCropRatio.Original: return AppStrings.Get("UiCropOriginal");
            case PhotoCropRatio.Square: return AppStrings.Get("UiCropSquare");
            default:
                var (width, height) = PhotoAdjustment.RatioDimensions(ratio);
                return $"{width}:{height}";
        }
    }

    private async Task OpenAsync(string? path, byte[]? bytes, string mime)
    {
        using var cancellation = new CancellationTokenSource();
        _load = cancellation;
        try
        {
            var image = await Task.Run(() => PhotoCompression.OpenForEditingAsync(path, bytes, mime, cancellation.Token));
            if (_closed) return;
            _original = image;
            _image.Source = image;
            _status.Text = image is null ? AppStrings.Get("UiPhotoFormatLimit") : "";
            Redraw();
        }
        catch (OperationCanceledException) when (_closed) { }
        catch (Exception error) when (error is COMException or IOException or InvalidOperationException or ArgumentException or NotSupportedException or UnauthorizedAccessException)
        {
            if (!_closed) _status.Text = AppStrings.Get("UiPhotoOpenError");
        }
        finally { _load = null; }
    }

    private void Redraw()
    {
        var visible = _original is not null && _canvas.ActualWidth > 0 && _canvas.ActualHeight > 0;
        foreach (UIElement child in _canvas.Children) child.Visibility = visible ? Visibility.Visible : Visibility.Hidden;
        var enabled = _original is not null && !_saving;
        _cancel.IsEnabled = !_saving;
        _rotateLeft.IsEnabled = _rotateRight.IsEnabled = _flip.IsEnabled = _ratio.IsEnabled = enabled;
        _reset.IsEnabled = enabled && _adjustment != PhotoAdjustment.Original;
        _apply.IsEnabled = enabled && _adjustment != _initial && (_adjustment.ChangesPixels || _initial.ChangesPixels);
        _flip.SetResourceReference(ForegroundProperty, _adjustment.Mirrored ? "AccentBrush" : "TextBrush");
        _ratio.SetResourceReference(ForegroundProperty, _adjustment.Ratio != PhotoCropRatio.Free ? "AccentBrush" : "TextBrush");
        System.Windows.Automation.AutomationProperties.SetItemStatus(_ratio, RatioLabel(_adjustment.Ratio));
        System.Windows.Automation.AutomationProperties.SetItemStatus(_flip, _adjustment.Mirrored ? AppStrings.Get("UiMirrorPhoto") : "");
        if (!visible || _original is not { } bitmap) return;

        var sideways = _adjustment.Turns % 2 != 0;
        var displayWidth = sideways ? bitmap.PixelHeight : bitmap.PixelWidth;
        var displayHeight = sideways ? bitmap.PixelWidth : bitmap.PixelHeight;
        var scale = Math.Min(_canvas.ActualWidth / displayWidth, _canvas.ActualHeight / displayHeight);
        _fit = new Rect((_canvas.ActualWidth - displayWidth * scale) / 2, (_canvas.ActualHeight - displayHeight * scale) / 2,
            displayWidth * scale, displayHeight * scale);
        var imageWidth = bitmap.PixelWidth * scale;
        var imageHeight = bitmap.PixelHeight * scale;
        SetBounds(_image, new Rect(_fit.Left + (_fit.Width - imageWidth) / 2, _fit.Top + (_fit.Height - imageHeight) / 2,
            imageWidth, imageHeight));
        _image.RenderTransform = new MatrixTransform(_adjustment.DisplayTransform());

        var selection = DisplayCrop();
        SetBounds(_outline, selection);
        SetBounds(_shade[0], new Rect(_fit.Left, _fit.Top, _fit.Width, Math.Max(0, selection.Top - _fit.Top)));
        SetBounds(_shade[1], new Rect(_fit.Left, selection.Bottom, _fit.Width, Math.Max(0, _fit.Bottom - selection.Bottom)));
        SetBounds(_shade[2], new Rect(_fit.Left, selection.Top, Math.Max(0, selection.Left - _fit.Left), selection.Height));
        SetBounds(_shade[3], new Rect(selection.Right, selection.Top, Math.Max(0, _fit.Right - selection.Right), selection.Height));
        for (var index = 0; index < 2; index++)
        {
            var x = selection.Left + selection.Width * (index + 1) / 3;
            var y = selection.Top + selection.Height * (index + 1) / 3;
            var vertical = _grid[index];
            vertical.X1 = vertical.X2 = x;
            vertical.Y1 = selection.Top;
            vertical.Y2 = selection.Bottom;
            var horizontal = _grid[index + 2];
            horizontal.Y1 = horizontal.Y2 = y;
            horizontal.X1 = selection.Left;
            horizontal.X2 = selection.Right;
        }
        for (var index = 0; index < _handles.Length; index++)
        {
            var point = Corner(selection, index);
            Canvas.SetLeft(_handles[index], point.X - HandleRadius);
            Canvas.SetTop(_handles[index], point.Y - HandleRadius);
        }
    }

    private static void SetBounds(FrameworkElement element, Rect rect)
    {
        Canvas.SetLeft(element, rect.X);
        Canvas.SetTop(element, rect.Y);
        element.Width = rect.Width;
        element.Height = rect.Height;
    }

    private Rect DisplayCrop()
    {
        var crop = _adjustment.Crop;
        return new Rect(_fit.X + crop.X * _fit.Width, _fit.Y + crop.Y * _fit.Height,
            crop.Width * _fit.Width, crop.Height * _fit.Height);
    }

    private void SetGridActive(bool active)
    {
        var opacity = active ? .8 : .3;
        foreach (var line in _grid)
        {
            var from = line.Opacity;
            line.Opacity = opacity;
            line.BeginAnimation(OpacityProperty, SystemParameters.ClientAreaAnimation
                ? new System.Windows.Media.Animation.DoubleAnimation(from, opacity, new Duration(TimeSpan.FromMilliseconds(100)))
                    { FillBehavior = System.Windows.Media.Animation.FillBehavior.Stop }
                : null);
        }
    }

    private static Point Corner(Rect rect, int index) =>
        new(index % 2 == 0 ? rect.Left : rect.Right, index < 2 ? rect.Top : rect.Bottom);

    private void StartDrag(object sender, MouseButtonEventArgs e)
    {
        if (_saving || _original is null || _fit.Width <= 0 || _fit.Height <= 0) return;
        var point = e.GetPosition(_canvas);
        var selection = DisplayCrop();
        var nearest = Enumerable.Range(0, 4).MinBy(index => (Corner(selection, index) - point).LengthSquared);
        if ((Corner(selection, nearest) - point).LengthSquared <= 20 * 20) _dragHandle = nearest;
        else if (selection.Contains(point)) _dragHandle = -1;
        else return;
        _dragStart = point;
        _dragCrop = _adjustment.Crop;
        if (!_canvas.CaptureMouse()) _dragHandle = null;
        else SetGridActive(true);
        e.Handled = true;
    }

    private void Drag(object sender, MouseEventArgs e)
    {
        if (_dragHandle is not { } handle || _fit.Width <= 0 || _fit.Height <= 0) return;
        var delta = e.GetPosition(_canvas) - _dragStart;
        var dx = delta.X / _fit.Width;
        var dy = delta.Y / _fit.Height;
        var crop = _dragCrop;
        if (handle == -1)
        {
            crop.Offset(Math.Clamp(dx, -_dragCrop.Left, Math.Max(-_dragCrop.Left, 1 - _dragCrop.Right)),
                Math.Clamp(dy, -_dragCrop.Top, Math.Max(-_dragCrop.Top, 1 - _dragCrop.Bottom)));
        }
        else if (_adjustment.NormalizedRatio(_fit.Width / _fit.Height) is { } ratio)
        {
            var left = handle % 2 == 0;
            var top = handle < 2;
            var anchorX = left ? _dragCrop.Right : _dragCrop.Left;
            var anchorY = top ? _dragCrop.Bottom : _dragCrop.Top;
            var unitWidth = Math.Min(1, ratio);
            var unitHeight = Math.Min(1, 1 / ratio);
            var limit = Math.Max(0, Math.Min((left ? anchorX : 1 - anchorX) / unitWidth,
                (top ? anchorY : 1 - anchorY) / unitHeight));
            var imageAspect = _fit.Width / _fit.Height;
            var directionX = unitWidth * imageAspect;
            var candidateX = (_dragCrop.Width + (left ? -dx : dx)) * imageAspect;
            var candidateY = _dragCrop.Height + (top ? -dy : dy);
            var projected = (candidateX * directionX + candidateY * unitHeight) / (directionX * directionX + unitHeight * unitHeight);
            var length = Math.Clamp(projected, Math.Min(Minimum, limit), limit);
            var width = unitWidth * length;
            var height = unitHeight * length;
            crop = new Rect(left ? anchorX - width : anchorX, top ? anchorY - height : anchorY, width, height);
        }
        else
        {
            var left = handle % 2 == 0 ? Math.Clamp(_dragCrop.Left + dx, 0, Math.Max(0, _dragCrop.Right - Minimum)) : _dragCrop.Left;
            var top = handle < 2 ? Math.Clamp(_dragCrop.Top + dy, 0, Math.Max(0, _dragCrop.Bottom - Minimum)) : _dragCrop.Top;
            var right = handle % 2 != 0 ? Math.Clamp(_dragCrop.Right + dx, Math.Min(1, _dragCrop.Left + Minimum), 1) : _dragCrop.Right;
            var bottom = handle >= 2 ? Math.Clamp(_dragCrop.Bottom + dy, Math.Min(1, _dragCrop.Top + Minimum), 1) : _dragCrop.Bottom;
            crop = new Rect(left, top, right - left, bottom - top);
        }
        _adjustment = _adjustment with { Crop = crop };
        Redraw();
    }

    private void Adjust(PhotoAdjustment adjustment)
    {
        _canvas.ReleaseMouseCapture();
        _adjustment = adjustment;
        _status.Text = "";
        Redraw();
    }

    private async Task ApplyAsync(string mime)
    {
        if (_saving || !_apply.IsEnabled || _original is not { } image) return;
        var adjustment = _adjustment;
        if (!adjustment.ChangesPixels)
        {
            DialogResult = true;
            return;
        }
        _saving = true;
        _canvas.ReleaseMouseCapture();
        _status.Text = AppStrings.Get("UiApplying");
        Redraw();
        try
        {
            var edited = await Task.Run(() => PhotoCompression.Edit(image, adjustment, mime));
            if (_closed)
            {
                CryptographicOperations.ZeroMemory(edited.Bytes);
                return;
            }
            Result = edited;
            DialogResult = true;
        }
        catch (Exception error) when (error is COMException or IOException or InvalidOperationException or ArgumentException or NotSupportedException)
        {
            if (!_closed) _status.Text = AppStrings.Get("UiPhotoEditError");
        }
        finally
        {
            _saving = false;
            if (!_closed) Redraw();
        }
    }
}
