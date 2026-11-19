// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;

namespace MsgAT.Windows.Views;

internal sealed class AttachmentExportFolderDialog : DialogWindow
{
    internal AttachmentExportFolderDialog(SecureSettingsStore settings)
    {
        Title = AppStrings.Get("UiExportFolder");
        Width = 462;
        SizeToContent = SizeToContent.Height;
        var current = new TextBlock { TextWrapping = TextWrapping.Wrap, MaxHeight = 84, TextTrimming = TextTrimming.CharacterEllipsis };
        var error = new TextBlock { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
        var defaultLabel = AppStrings.Get("UiExportFolderDefault");
        void Update() => current.Text = settings.Snapshot.AttachmentExportFolder ?? defaultLabel;
        var choose = IdentitySetupDialog.ActionButton(AppStrings.Get("UiExportFolderChoose"), null, Brushes.Gray);
        var reset = IdentitySetupDialog.ActionButton(defaultLabel, null, Brushes.Gray);
        var close = IdentitySetupDialog.ActionButton(AppStrings.Get("UiClose"), Close, Brushes.Gray);
        var saving = false;
        async Task SaveAsync(string? path)
        {
            if (saving) return;
            saving = true;
            choose.IsEnabled = reset.IsEnabled = false;
            error.Visibility = Visibility.Collapsed;
            try
            {
                await Task.Run(() => settings.Update(value => value with { AttachmentExportFolder = path }));
                if (IsVisible) Update();
            }
            catch (Exception failure) when (failure is IOException or UnauthorizedAccessException or
                System.Security.Cryptography.CryptographicException or InvalidOperationException)
            {
                if (IsVisible)
                {
                    error.Text = AppStrings.Get("UiExportFolderFailed");
                    error.Visibility = Visibility.Visible;
                }
            }
            finally
            {
                saving = false;
                choose.IsEnabled = true;
                reset.IsEnabled = settings.Snapshot.AttachmentExportFolder is not null;
            }
        }
        choose.Click += async (_, _) =>
        {
            var picker = new OpenFolderDialog { Title = AppStrings.Get("UiExportFolderChoose") };
            if (settings.Snapshot.AttachmentExportFolder is { } path) picker.InitialDirectory = path;
            if (picker.ShowDialog(this) == true && IsVisible) await SaveAsync(picker.FolderName);
        };
        reset.Click += async (_, _) => await SaveAsync(null);
        reset.IsEnabled = settings.Snapshot.AttachmentExportFolder is not null;
        Update();
        var buttons = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        buttons.Children.Add(reset);
        buttons.Children.Add(close);
        buttons.Children.Add(choose);
        Content = IdentitySetupDialog.DialogCard(IdentitySetupDialog.Form(IdentitySetupDialog.Heading(Title), current, error, buttons));
    }
}
