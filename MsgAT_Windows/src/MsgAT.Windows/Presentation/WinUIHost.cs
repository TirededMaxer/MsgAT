// SPDX-License-Identifier: AGPL-3.0-only
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using Microsoft.UI.Xaml.Hosting;
using MsgAT.Windows.Infrastructure;
using WinUI = Microsoft.UI.Xaml;

namespace MsgAT.Windows.Presentation;

public abstract partial class WinUIHost : HwndHost
{
    private nint _site;
    private DesktopWindowXamlSource? _source;
    protected WinUI.FrameworkElement? IslandRoot => _source?.Content as WinUI.FrameworkElement;
    protected abstract WinUI.FrameworkElement CreateContent();
    protected virtual void ReleaseContent() { }
    protected virtual void RefreshPresentation() { }
    internal Action<WorkspaceKey>? Shortcut { get; set; }

    protected override HandleRef BuildWindowCore(HandleRef hwndParent)
    {
        const uint childStyle = 0x40000000 | 0x10000000 | 0x02000000 | 0x04000000;
        _site = CreateWindowEx(0, "STATIC", null, childStyle, 0, 0, 1, 1, hwndParent.Handle, 0, 0, 0);
        if (_site == 0) throw new Win32Exception(Marshal.GetLastPInvokeError());
        try
        {
            _source = new DesktopWindowXamlSource();
            _source.Initialize(Microsoft.UI.Win32Interop.GetWindowIdFromWindow(_site));
            _source.TakeFocusRequested += TakeFocus;
            var root = CreateContent();
            root.PreviewKeyDown += NativeKeyDown;
            _source.Content = root;
            WindowAppearance.Changed += PresentationChanged;
            AppStrings.Changed += PresentationChanged;
            PresentationChanged(null, EventArgs.Empty);
            return new HandleRef(this, _site);
        }
        catch
        {
            DestroyWindowCore(new HandleRef(this, _site));
            throw;
        }
    }

    private void NativeKeyDown(object sender, Microsoft.UI.Xaml.Input.KeyRoutedEventArgs args)
    {
        if (args.Handled || Shortcut is null) return;
        var key = new WorkspaceKey(KeyInterop.KeyFromVirtualKey((int)args.Key), Keyboard.Modifiers, args.KeyStatus.WasKeyDown);
        Shortcut(key);
        args.Handled = key.Handled;
    }

    protected override void OnWindowPositionChanged(Rect rcBoundingBox)
    {
        base.OnWindowPositionChanged(rcBoundingBox);
        if (_source is not null && GetClientRect(_site, out var bounds))
            _source.SiteBridge.MoveAndResize(new global::Windows.Graphics.RectInt32(0, 0, bounds.Right, bounds.Bottom));
    }

    protected override bool TabIntoCore(TraversalRequest request)
    {
        var reverse = request.FocusNavigationDirection is FocusNavigationDirection.Last or FocusNavigationDirection.Previous;
        var reason = reverse ? XamlSourceFocusNavigationReason.Last : XamlSourceFocusNavigationReason.First;
        return _source?.NavigateFocus(new XamlSourceFocusNavigationRequest(reason)).WasFocusMoved == true;
    }

    private void TakeFocus(DesktopWindowXamlSource sender, DesktopWindowXamlSourceTakeFocusRequestedEventArgs args)
    {
        if (args.Request.Reason is not (XamlSourceFocusNavigationReason.First or XamlSourceFocusNavigationReason.Last)) return;
        var reverse = args.Request.Reason == XamlSourceFocusNavigationReason.Last;
        ((IKeyboardInputSink)this).KeyboardInputSite?.OnNoMoreTabStops(new TraversalRequest(
            reverse ? FocusNavigationDirection.Previous : FocusNavigationDirection.Next));
    }

    private void PresentationChanged(object? sender, EventArgs args)
    {
        if (IslandRoot is not { } root) return;
        root.RequestedTheme = WindowAppearance.Theme switch
        {
            AppThemeMode.Dark => WinUI.ElementTheme.Dark,
            AppThemeMode.Light => WinUI.ElementTheme.Light,
            _ => WinUI.ElementTheme.Default
        };
        root.Language = AppStrings.InterfaceCulture.Name;
        RefreshPresentation();
    }

    protected override void DestroyWindowCore(HandleRef hwnd)
    {
        WindowAppearance.Changed -= PresentationChanged;
        AppStrings.Changed -= PresentationChanged;
        if (_source is not null)
        {
            if (IslandRoot is { } root) root.PreviewKeyDown -= NativeKeyDown;
            ReleaseContent();
            _source.TakeFocusRequested -= TakeFocus;
            _source.Content = null;
            _source.Dispose();
            _source = null;
        }
        if (hwnd.Handle != 0) _ = DestroyWindow(hwnd.Handle);
        _site = 0;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeRect { internal int Left, Top, Right, Bottom; }
    [LibraryImport("user32.dll", EntryPoint = "CreateWindowExW", StringMarshalling = StringMarshalling.Utf16, SetLastError = true)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static partial nint CreateWindowEx(uint exStyle, string className, string? title, uint style,
        int x, int y, int width, int height, nint parent, nint menu, nint instance, nint data);
    [LibraryImport("user32.dll")]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DestroyWindow(nint window);
    [LibraryImport("user32.dll")]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetClientRect(nint window, out NativeRect bounds);
}

internal sealed class WorkspaceKey(Key key, ModifierKeys modifiers, bool repeat)
{
    internal Key Key { get; } = key;
    internal ModifierKeys Modifiers { get; } = modifiers;
    internal bool IsRepeat { get; } = repeat;
    internal bool Handled { get; set; }
}
