// SPDX-License-Identifier: AGPL-3.0-only
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using MsgAT.Windows.Infrastructure;
using Wpf = System.Windows;
using Xaml = Microsoft.UI.Xaml;

namespace MsgAT.Windows.Presentation;

public sealed class ChatEditorHost : WinUIHost
{
    private TextBox? _editor;
    private Grid? _surface;
    private bool _nativeUpdate;
    public static readonly Wpf.DependencyProperty TextProperty = Wpf.DependencyProperty.Register(nameof(Text),
        typeof(string), typeof(ChatEditorHost), new Wpf.FrameworkPropertyMetadata("", Wpf.FrameworkPropertyMetadataOptions.BindsTwoWayByDefault, UpdateValue));
    public static readonly Wpf.DependencyProperty IsReadOnlyProperty = Wpf.DependencyProperty.Register(nameof(IsReadOnly),
        typeof(bool), typeof(ChatEditorHost), new Wpf.PropertyMetadata(false, UpdateValue));
    public static readonly Wpf.DependencyProperty EditorFontSizeProperty = Wpf.DependencyProperty.Register(nameof(EditorFontSize),
        typeof(double), typeof(ChatEditorHost), new Wpf.PropertyMetadata(16d, UpdateValue));
    public string Text { get => (string)GetValue(TextProperty); set => SetValue(TextProperty, value); }
    public bool IsReadOnly { get => (bool)GetValue(IsReadOnlyProperty); set => SetValue(IsReadOnlyProperty, value); }
    public double EditorFontSize { get => (double)GetValue(EditorFontSizeProperty); set => SetValue(EditorFontSizeProperty, value); }
    public string PlaceholderKey { get; set; } = "UiMessagePlaceholder";
    internal bool Composing { get; private set; }
    internal bool EditorFocused => _editor is { FocusState: not Xaml.FocusState.Unfocused };
    internal int SelectionStart => _editor?.SelectionStart ?? 0;
    internal int SelectionLength => _editor?.SelectionLength ?? 0;
    internal string SelectedText => _editor?.SelectedText ?? "";
    internal int CaretIndex { set => _editor?.Select(Math.Clamp(value, 0, Text.Length), 0); }
    internal Action? FocusChanged { get; set; }
    internal Action? Typed { get; set; }
    internal Func<string, bool>? SecureCopy { get; set; }
    internal Func<bool>? HasMediaPaste { get; set; }
    internal Action? PasteMedia { get; set; }
    internal Func<bool>? CanFormat { get; set; }
    internal Action<string>? Format { get; set; }

    protected override Xaml.FrameworkElement CreateContent()
    {
        var editor = new TextBox
        {
            AcceptsReturn = true, TextWrapping = Xaml.TextWrapping.Wrap, MinHeight = 38,
            CornerRadius = new Xaml.CornerRadius(12), Padding = new Xaml.Thickness(12, 7, 12, 7),
            IsSpellCheckEnabled = false, IsTextPredictionEnabled = false
        };
        _editor = editor;
        editor.TextChanged += (_, _) =>
        {
            if (editor.Text == Text) return;
            _nativeUpdate = true;
            try { SetCurrentValue(TextProperty, editor.Text); }
            finally { _nativeUpdate = false; }
            InvalidateMeasure();
            if (EditorFocused) Typed?.Invoke();
        };
        editor.TextCompositionStarted += (_, _) => Composing = true;
        editor.TextCompositionChanged += (_, _) => Composing = true;
        editor.TextCompositionEnded += (_, _) => Composing = false;
        editor.GotFocus += (_, _) => FocusChanged?.Invoke();
        editor.LostFocus += (_, _) => { Composing = false; FocusChanged?.Invoke(); };
        editor.CopyingToClipboard += (_, args) => { args.Handled = true; Copy(cut: false); };
        editor.CuttingToClipboard += (_, args) => { args.Handled = true; Copy(cut: true); };
        editor.Paste += (_, args) =>
        {
            if (IsReadOnly || !IsEnabled) { args.Handled = true; return; }
            if (HasMediaPaste?.Invoke() != true) return;
            args.Handled = true;
            PasteMedia?.Invoke();
        };
        var flyout = new MenuFlyout();
        flyout.Opening += (_, _) => PopulateMenu(flyout, editor);
        editor.ContextFlyout = flyout;
        editor.SelectionFlyout = flyout;
        UpdateEditor();
        _surface = new Grid();
        _surface.Children.Add(editor);
        return _surface;
    }

    private static void UpdateValue(Wpf.DependencyObject target, Wpf.DependencyPropertyChangedEventArgs args) =>
        ((ChatEditorHost)target).UpdateEditor();

    private void UpdateEditor()
    {
        if (_nativeUpdate || _editor is not { } editor) return;
        if (!string.Equals(editor.Text, Text, StringComparison.Ordinal))
        {
            editor.Text = Text ?? "";
            editor.ClearUndoRedoHistory();
        }
        editor.IsReadOnly = IsReadOnly;
        editor.FontSize = EditorFontSize;
        InvalidateMeasure();
    }

    protected override Wpf.Size MeasureOverride(Wpf.Size constraint)
    {
        if (_editor is not { } editor || !double.IsFinite(constraint.Width)) return base.MeasureOverride(constraint);
        editor.Measure(new global::Windows.Foundation.Size(constraint.Width, Math.Min(constraint.Height, MaxHeight)));
        return new Wpf.Size(Math.Min(constraint.Width, editor.DesiredSize.Width), Math.Max(MinHeight, editor.DesiredSize.Height));
    }

    private void Copy(bool cut)
    {
        if (_editor is not { SelectionLength: > 0 } editor || !IsEnabled || (cut && (IsReadOnly || CanFormat?.Invoke() != true))) return;
        if (SecureCopy?.Invoke(editor.SelectedText) == true && cut) editor.SelectedText = "";
    }

    private void PopulateMenu(MenuFlyout menu, TextBox editor)
    {
        menu.Items.Clear();
        void Add(string key, bool enabled, Action invoke)
        {
            var item = new MenuFlyoutItem { Text = AppStrings.Get(key), IsEnabled = enabled };
            item.Click += (_, _) => invoke();
            menu.Items.Add(item);
        }
        var writable = !IsReadOnly && IsEnabled;
        Add("UiUndo", writable && editor.CanUndo, editor.Undo);
        Add("UiRedo", writable && editor.CanRedo, editor.Redo);
        Add("UiCut", writable && editor.SelectionLength > 0, () => Copy(cut: true));
        Add("UiCopy", editor.SelectionLength > 0, () => Copy(cut: false));
        Add("UiPaste", writable && (editor.CanPasteClipboardContent || HasMediaPaste?.Invoke() == true), () =>
        {
            if (HasMediaPaste?.Invoke() == true) PasteMedia?.Invoke(); else editor.PasteFromClipboard();
        });
        Add("UiSelectAll", editor.Text.Length > 0, editor.SelectAll);
        menu.Items.Add(new MenuFlyoutSeparator());
        var formats = new MenuFlyoutSubItem { Text = AppStrings.Get("UiFormatting"), IsEnabled = CanFormat?.Invoke() == true };
        foreach (var (key, kind) in new[] { ("UiFormatBold","bold"), ("UiFormatItalic","italic"), ("UiFormatUnderline","underline"),
            ("UiFormatStrike","strike"), ("UiFormatCode","code"), ("UiFormatSpoiler","spoiler"), ("UiFormatQuote","quote"), ("UiFormatCodeBlock","code_block") })
        {
            var item = new MenuFlyoutItem { Text = AppStrings.Get(key) };
            item.Click += (_, _) => Format?.Invoke(kind);
            formats.Items.Add(item);
        }
        menu.Items.Add(formats);
    }

    internal void ApplyFormatting(string value, int start, int length)
    {
        if (_editor is not { } editor) return;
        editor.SelectAll();
        editor.SelectedText = value;
        editor.Select(start, length);
        Focus();
    }

    public new bool Focus() => IsEnabled && IsVisible && (_editor?.Focus(Xaml.FocusState.Programmatic) ?? base.Focus());
    internal void ClearUndoHistory() => _editor?.ClearUndoRedoHistory();

    protected override void RefreshPresentation()
    {
        if (_editor is not { } editor) return;
        if (_surface is { } surface)
        {
            surface.Background = Wpf.SystemParameters.HighContrast
                ? new Microsoft.UI.Xaml.Media.SolidColorBrush(new global::Windows.UI.ViewManagement.UISettings().UIElementColor(global::Windows.UI.ViewManagement.UIElementType.Window))
                : WorkspaceHost.ColorBrush("MessagingChromeColor");
        }
        editor.PlaceholderText = AppStrings.Get(PlaceholderKey);
        AutomationProperties.SetName(editor, editor.PlaceholderText);
    }

    protected override void ReleaseContent()
    {
        Composing = false;
        _editor = null;
        _surface = null;
    }
}
