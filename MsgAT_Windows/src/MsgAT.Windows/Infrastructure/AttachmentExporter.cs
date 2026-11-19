// SPDX-License-Identifier: AGPL-3.0-only
using System.Security.Cryptography;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;
using MsgAT.Windows.ViewModels;
using MsgAT.Windows.Views;

namespace MsgAT.Windows.Infrastructure;

internal static class AttachmentExporter
{
    private enum Result { Saved, Cancelled, Failed }
    private static ExportProgressWindow? _active;
    private static Task<Result>? _write;
    private static bool _stopping;

    internal static void CancelActive() => _active?.RequestCancellation();

    internal static async Task StopAsync()
    {
        _stopping = true;
        CancelActive();
        if (_write is { } write) await write;
    }

    internal static async Task ExportAsync(Window owner, MessageItem item, Func<bool>? allowed = null,
        string? preferredFolder = null)
    {
        if (_stopping || _active is not null || !owner.IsLoaded) return;
        if (!item.IsAvailable)
        {
            TransientNotice.Show(owner, AppStrings.Get("UiFileDetailsUnavailable"));
            return;
        }
        var invalid = Path.GetInvalidFileNameChars();
        var name = string.Concat(item.Filename.Select(c => invalid.Contains(c) ? '_' : c)).TrimEnd(' ', '.');
        var picker = new SaveFileDialog
        {
            Title = AppStrings.Get("UiSaveAttachment"),
            FileName = string.IsNullOrWhiteSpace(name) ? "attachment.bin" : name,
            Filter = "All files|*.*",
            OverwritePrompt = true,
            AddExtension = false
        };
        if (!string.IsNullOrEmpty(preferredFolder)) picker.InitialDirectory = preferredFolder;
        if (picker.ShowDialog(owner) != true || _stopping || _active is not null || !owner.IsLoaded) return;
        var destination = picker.FileName;

        using var cancellation = new CancellationTokenSource();
        var progress = new ExportProgressWindow(cancellation, item.AttachmentInfo?.Size ?? 0) { Owner = owner };
        _active = progress;
        var result = Result.Cancelled;
        Task<Result>? write = null;
        progress.Loaded += async (_, _) =>
        {
            if (write is not null) return;
            var operation = Task.Run(() => WriteAsync(item, destination, allowed, progress.Report, cancellation.Token));
            write = _write = operation;
            try { await operation; }
            finally { progress.Finish(); }
        };
        try
        {
            progress.ShowDialog();
            if (write is { } operation) result = await operation;
        }
        finally { _active = null; _write = null; }
        if (owner.IsLoaded && !_stopping && result != Result.Cancelled)
            TransientNotice.Show(owner, AppStrings.Get(result == Result.Saved ? "UiAttachmentExported" : "UiSharedMediaSaveFailed"));
    }

    private static async Task<Result> WriteAsync(MessageItem item, string destination, Func<bool>? allowed,
        Action<long> report, CancellationToken token)
    {
        string? temporary = null;
        var buffer = new byte[64 * 1024];
        try
        {
            token.ThrowIfCancellationRequested();
            if (allowed?.Invoke() == false) return Result.Failed;
            var staging = Path.Combine(Path.GetDirectoryName(destination)!, ".msgat-export-" + Guid.NewGuid().ToString("N") + ".tmp");
            using var input = item.OpenMedia();
            await using (var output = new FileStream(staging, FileMode.CreateNew, FileAccess.Write,
                    FileShare.None, buffer.Length, FileOptions.Asynchronous))
            {
                temporary = staging;
                long completed = 0;
                while (true)
                {
                    token.ThrowIfCancellationRequested();
                    var length = await input.ReadAsync(buffer.AsMemory(), token).ConfigureAwait(false);
                    if (length == 0) break;
                    await output.WriteAsync(buffer.AsMemory(0, length), token).ConfigureAwait(false);
                    completed += length;
                    report(completed);
                }
                token.ThrowIfCancellationRequested();
                output.Flush(flushToDisk: true);
            }
            if (allowed?.Invoke() == false) return Result.Failed;
            token.ThrowIfCancellationRequested();
            File.Move(staging, destination, overwrite: true);
            return Result.Saved;
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { return Result.Cancelled; }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException or
            CryptographicException or Microsoft.Data.Sqlite.SqliteException or ArgumentException or NotSupportedException)
        {
            return Result.Failed;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(buffer);
            try { if (temporary is not null) File.Delete(temporary); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
        }
    }

    private sealed class ExportProgressWindow : DialogWindow
    {
        private readonly CancellationTokenSource _cancellation;
        private readonly TextBlock _status = new() { Text = AppStrings.Get("UiSaving") };
        private readonly System.Windows.Controls.ProgressBar _progress;
        private readonly Button _cancel;
        private readonly DispatcherTimer _timer = new(DispatcherPriority.Background) { Interval = TimeSpan.FromMilliseconds(100) };
        private long _completed;
        private bool _finished;

        internal ExportProgressWindow(CancellationTokenSource cancellation, long size)
        {
            _cancellation = cancellation;
            Title = AppStrings.Get("UiSaveAttachment");
            Width = 360;
            SizeToContent = SizeToContent.Height;
            _progress = new System.Windows.Controls.ProgressBar
            {
                Height = 5, Maximum = Math.Max(1, size), IsIndeterminate = size <= 0,
                Margin = new Thickness(0, 10, 0, 10)
            };
            _cancel = IdentitySetupDialog.ActionButton(AppStrings.Get("UiCancel"), RequestCancellation, Brushes.Gray);
            Content = IdentitySetupDialog.DialogCard(IdentitySetupDialog.Form(_status, _progress, _cancel));
            _timer.Tick += (_, _) => _progress.Value = Math.Min(_progress.Maximum, Interlocked.Read(ref _completed));
            Loaded += (_, _) => _timer.Start();
            Closing += (_, args) =>
            {
                if (_finished) return;
                args.Cancel = true;
                RequestCancellation();
            };
            Closed += (_, _) => _timer.Stop();
            PreviewKeyDown += (_, args) =>
            {
                if (args.Key != System.Windows.Input.Key.Escape) return;
                args.Handled = true;
                RequestCancellation();
            };
        }

        internal void Report(long completed) => Interlocked.Exchange(ref _completed, completed);

        internal void RequestCancellation()
        {
            if (_finished || _cancellation.IsCancellationRequested) return;
            _cancel.IsEnabled = false;
            _status.Text = AppStrings.Get("UiPreparationCanceling");
            _cancellation.Cancel();
        }

        internal void Finish()
        {
            _finished = true;
            _timer.Stop();
            if (IsLoaded) Close();
        }
    }
}
