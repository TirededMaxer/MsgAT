// SPDX-License-Identifier: AGPL-3.0-only
using System.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.ViewModels;

namespace MsgAT.Windows.Presentation;

public abstract class WorkspaceHost : WinUIHost
{
    protected MainViewModel? Model { get; private set; }

    protected WorkspaceHost() => DataContextChanged += (_, _) =>
    {
        if (IslandRoot is not null) Attach();
    };

    protected override System.Runtime.InteropServices.HandleRef BuildWindowCore(System.Runtime.InteropServices.HandleRef hwndParent)
    {
        var result = base.BuildWindowCore(hwndParent);
        Attach();
        return result;
    }

    private void Attach()
    {
        if (Model is not null) Model.PropertyChanged -= ModelChanged;
        Model = DataContext as MainViewModel;
        if (Model is not null) Model.PropertyChanged += ModelChanged;
        BindWorkspace();
    }

    protected abstract void BindWorkspace();
    protected abstract void ModelChanged(object? sender, PropertyChangedEventArgs args);
    protected override void ReleaseContent()
    {
        if (Model is not null) Model.PropertyChanged -= ModelChanged;
        Model = null;
        BindWorkspace();
        base.ReleaseContent();
    }

    internal static void Label(FrameworkElement element, string key)
    {
        var value = AppStrings.Get(key);
        AutomationProperties.SetName(element, value);
        ToolTipService.SetToolTip(element, value);
    }

    internal static Brush ColorBrush(string key)
    {
        var color = (System.Windows.Media.Color)System.Windows.Application.Current.FindResource(key);
        return new SolidColorBrush(global::Windows.UI.Color.FromArgb(color.A, color.R, color.G, color.B));
    }
}
