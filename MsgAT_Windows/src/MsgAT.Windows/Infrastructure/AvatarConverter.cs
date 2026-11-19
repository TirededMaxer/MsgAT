using System.Globalization;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Threading;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Infrastructure;

/// <summary>Decode authenticated avatars on workers and drain their vault access on shutdown.</summary>
public sealed class AvatarConverter : IValueConverter
{
    private sealed class Pending(string path, ImageBrush brush)
    {
        public readonly string Path = path;
        public readonly WeakReference<ImageBrush> Brush = new(brush);
        public bool Busy;
    }

    private static readonly object Gate = new();
    private static readonly List<Pending> Waiting = [];
    private static readonly SemaphoreSlim Decoders = new(3);
    private static EncryptedObjectStore? _objects;
    private static EncryptedDatabase? _database;
    private static Dispatcher? _dispatcher;
    private static int _generation;
    private static long _changeSerial;
    private static int _active;
    private static TaskCompletionSource? _drained;

    public static void Configure(EncryptedObjectStore objects, EncryptedDatabase database, Dispatcher dispatcher)
    {
        lock (Gate)
        {
            if (_database is not null || _active != 0)
                throw new InvalidOperationException("The previous avatar decoder is still active.");
            _objects = objects;
            _database = database;
            _dispatcher = dispatcher;
            ++_generation;
            database.Changed += Changed;
        }
    }

    public static Task ClearAsync()
    {
        lock (Gate)
        {
            if (_database is { } database) database.Changed -= Changed;
            ++_generation;
            _objects = null;
            _database = null;
            _dispatcher = null;
            Waiting.Clear();
            if (_active == 0) return Task.CompletedTask;
            return (_drained ??= new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously)).Task;
        }
    }

    private static void Changed(object? sender, EventArgs args)
    {
        Pending[] pending;
        lock (Gate)
        {
            if (!ReferenceEquals(sender, _database) || _database is null) return;
            ++_changeSerial;
            Waiting.RemoveAll(item => !item.Brush.TryGetTarget(out _));
            pending = [..Waiting];
        }
        foreach (var item in pending) _ = LoadAsync(item);
    }

    public object? Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is not string path || string.IsNullOrWhiteSpace(path)) return null;
        var brush = new ImageBrush { Stretch = Stretch.UniformToFill };
        var item = new Pending(path, brush);
        lock (Gate)
        {
            if (_objects is null) return null;
            Waiting.Add(item);
        }
        _ = LoadAsync(item);
        return brush;
    }

    private static async Task LoadAsync(Pending item)
    {
        Dispatcher dispatcher;
        EncryptedObjectStore objects;
        ImageBrush brush;
        int generation;
        long revision;
        lock (Gate)
        {
            if (item.Busy || _dispatcher is not { } currentDispatcher || _objects is not { } currentObjects ||
                !item.Brush.TryGetTarget(out var target)) return;
            item.Busy = true;
            ++_active;
            dispatcher = currentDispatcher;
            objects = currentObjects;
            brush = target;
            generation = _generation;
            revision = _changeSerial;
        }

        var loaded = false;
        try
        {
            var image = await Task.Run(async () =>
            {
                await Decoders.WaitAsync().ConfigureAwait(false);
                try
                {
                    if (generation != Volatile.Read(ref _generation)) return null;
                    if (!EncryptedObjectStore.IsReference(item.Path))
                        return File.Exists(item.Path) ? ImageDecoder.Load(item.Path, 512) : null;
                    var metadata = objects.Metadata(item.Path, "avatar");
                    if (metadata is not { Available: true, Size: > 0 and <= 512 * 1024 }) return null;
                    using var input = objects.OpenRead(item.Path);
                    return ImageDecoder.Load(input, 512);
                }
                finally { Decoders.Release(); }
            }).ConfigureAwait(false);

            if (image is null || generation != Volatile.Read(ref _generation)) return;
            await dispatcher.InvokeAsync(() =>
            {
                if (generation == Volatile.Read(ref _generation)) brush.ImageSource = image;
            });
            loaded = true;
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or NotSupportedException or
            FormatException or OperationCanceledException or Microsoft.Data.Sqlite.SqliteException or
            System.Runtime.InteropServices.ExternalException or System.Security.Cryptography.CryptographicException)
        {
            // Pending downloads can notify the converter when the avatar becomes available.
        }
        finally
        {
            bool retry;
            lock (Gate)
            {
                item.Busy = false;
                if (loaded) Waiting.Remove(item);
                retry = !loaded && generation == _generation && revision != _changeSerial;
                if (--_active == 0)
                {
                    _drained?.TrySetResult();
                    _drained = null;
                }
            }
            if (retry) _ = LoadAsync(item);
        }
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
