// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.InteropServices;
using System.Windows.Interop;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Markup;
using Microsoft.UI.Xaml.XamlTypeInfo;

namespace MsgAT.Windows.Presentation;

internal static partial class DesktopXamlRuntime
{
    private static DispatcherQueueController? _queue;
    private static IslandApplication? _app;

    internal static void Start()
    {
        _queue = DispatcherQueueController.CreateOnCurrentThread();
        _app = new IslandApplication();
        ComponentDispatcher.ThreadFilterMessage += FilterMessage;
    }

    internal static void Stop()
    {
        ComponentDispatcher.ThreadFilterMessage -= FilterMessage;
        _app?.Manager.Dispose();
        _app = null;
        _queue?.ShutdownQueue();
        _queue = null;
    }

    private sealed class IslandApplication : Microsoft.UI.Xaml.Application, IXamlMetadataProvider
    {
        private readonly XamlControlsXamlMetaDataProvider _types = new();
        internal WindowsXamlManager Manager { get; } = WindowsXamlManager.InitializeForCurrentThread();
        protected override void OnLaunched(Microsoft.UI.Xaml.LaunchActivatedEventArgs args) =>
            Resources.MergedDictionaries.Add(new XamlControlsResources());
        IXamlType IXamlMetadataProvider.GetXamlType(Type type) => _types.GetXamlType(type);
        IXamlType IXamlMetadataProvider.GetXamlType(string fullName) => _types.GetXamlType(fullName);
        XmlnsDefinition[] IXamlMetadataProvider.GetXmlnsDefinitions() => _types.GetXmlnsDefinitions();
    }

    private static void FilterMessage(ref MSG message, ref bool handled)
    {
        if (handled) return;
        var native = new NativeMessage
        {
            Window = message.hwnd, Message = (uint)message.message, WParam = message.wParam,
            LParam = message.lParam, Time = (uint)message.time, X = message.pt_x, Y = message.pt_y
        };
        handled = ContentPreTranslateMessage(ref native);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeMessage
    {
        internal nint Window;
        internal uint Message;
        internal nint WParam, LParam;
        internal uint Time;
        internal int X, Y;
        internal uint Private;
    }

    [LibraryImport("Microsoft.UI.Windowing.Core.dll")]
    [DefaultDllImportSearchPaths(DllImportSearchPath.SafeDirectories)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool ContentPreTranslateMessage(ref NativeMessage message);
}
