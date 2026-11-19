// SPDX-License-Identifier: AGPL-3.0-only
using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using MsgAT.Windows.Core.Network;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using Windows.Devices.Geolocation;

namespace MsgAT.Windows.Views;

internal static class LocationCoordinates
{
    public static LocationPoint? FromDegrees(double latitude, double longitude)
    {
        if (!double.IsFinite(latitude) || latitude is < -90 or > 90 ||
            !double.IsFinite(longitude) || longitude is < -180 or > 180) return null;
        return new LocationPoint((int)Math.Round(latitude * 1_000_000), (int)Math.Round(longitude * 1_000_000));
    }

    public static string Format(int value) => (value / 1_000_000d).ToString("F6", CultureInfo.InvariantCulture);
}

internal sealed class LocationSendDialog : DialogWindow
{
    private readonly TextBox _latitude = IdentitySetupDialog.Field();
    private readonly TextBox _longitude = IdentitySetupDialog.Field();
    private readonly TextBox _label = IdentitySetupDialog.Field();
    private readonly TextBlock _preview = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Button _locate;
    private readonly Button _options;
    private readonly Button _send;
    private readonly Button _cancel;
    private readonly CheckBox _live = new() { Content = AppStrings.Get("UiLiveLocationShare") };
    private readonly ComboBox _duration = new() { ItemsSource = new[] { AppStrings.Get("UiLocationDuration15"), AppStrings.Get("UiLocationDuration60"), AppStrings.Get("UiLocationDuration480") }, SelectedIndex = 0 };
    private readonly CheckBox _silent = new() { Content = AppStrings.Get("UiLocationSilent") };
    private readonly StackPanel _manualFields = new();
    private readonly StackPanel _liveFields = new();
    private readonly Func<LocationPoint, string?, SendOptions, Task<bool>> _queue;
    private readonly Func<LocationPoint, TimeSpan, string?, bool, Task<bool>> _share;
    private SendOptions _selection;
    private CancellationTokenSource? _locationRequest;
    private int? _accuracy;
    private bool _sending;
    private bool _closed;

    public LocationSendDialog(string recipient, SendOptions options, Func<LocationPoint, string?, SendOptions, Task<bool>> queue,
        Func<LocationPoint, TimeSpan, string?, bool, Task<bool>> share)
    {
        _queue = queue;
        _share = share;
        _selection = options;
        Title = AppStrings.Get("UiLocationShare");
        Width = Math.Min(455, SystemParameters.WorkArea.Width * .94);
        SizeToContent = SizeToContent.Height;
        MaxHeight = SystemParameters.WorkArea.Height * .92;
        var header = IdentitySetupDialog.Form(IdentitySetupDialog.Heading(Title),
            new TextBlock { Text = AppStrings.Format("UiRecipient", recipient), TextTrimming = TextTrimming.CharacterEllipsis });
        var panel = new StackPanel();
        _live.Margin = new Thickness(0, 12, 0, 6);
        panel.Children.Add(_live);
        _locate = Action(AppStrings.Get("UiLocationFind"), () => _ = LocateAsync(share: false));
        _manualFields.Children.Add(_locate);
        AddField(_manualFields, _latitude, AppStrings.Get("UiLocationLatitude"), 20);
        AddField(_manualFields, _longitude, AppStrings.Get("UiLocationLongitude"), 20);
        panel.Children.Add(_manualFields);
        _liveFields.Children.Add(new TextBlock { Text = AppStrings.Get("UiLiveLocationExplain"), TextWrapping = TextWrapping.Wrap });
        _liveFields.Children.Add(new TextBlock { Text = AppStrings.Get("UiLocationDuration"), Margin = new Thickness(0, 6, 0, 0) });
        AutomationProperties.SetName(_duration, AppStrings.Get("UiLocationDuration"));
        _liveFields.Children.Add(_duration);
        _silent.IsChecked = options.Silent;
        _silent.Margin = new Thickness(0, 6, 0, 0);
        _liveFields.Children.Add(_silent);
        panel.Children.Add(_liveFields);
        AddField(panel, _label, AppStrings.Get("UiLocationLabel"), 256);
        panel.Children.Add(_preview);
        panel.Children.Add(_status);
        _options = Action(AppStrings.Get("UiSendOptions"), ChooseOptions);
        panel.Children.Add(_options);
        var buttons = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 10, 0, 0) };
        _cancel = Action(AppStrings.Get("UiCancel"), Close);
        _cancel.IsCancel = true;
        _send = Action(AppStrings.Get("UiLocationSend"), () => _ = SendAsync());
        _send.Style = ResourceStyle("PrimaryButtonStyle");
        _send.IsDefault = true;
        buttons.Children.Add(_cancel);
        buttons.Children.Add(_send);
        var root = new DockPanel();
        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);
        DockPanel.SetDock(buttons, Dock.Bottom);
        root.Children.Add(buttons);
        root.Children.Add(new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        Content = IdentitySetupDialog.DialogCard(root);
        _latitude.TextChanged += CoordinatesChanged;
        _longitude.TextChanged += CoordinatesChanged;
        _label.TextChanged += (_, _) => Update();
        _live.Checked += (_, _) => { _status.Text = ""; Update(); };
        _live.Unchecked += (_, _) => { _status.Text = ""; Update(); };
        Loaded += (_, _) => _latitude.Focus();
        Closing += (_, args) => args.Cancel = _sending;
        Closed += (_, _) =>
        {
            _closed = true;
            _locationRequest?.Cancel();
        };
        Update();
        UpdateOptionsLabel();
    }

    private static Button Action(string text, Action action) => IdentitySetupDialog.ActionButton(text, action, Brushes.Gray);

    private static void AddField(StackPanel panel, TextBox field, string title, int maximum)
    {
        AutomationProperties.SetName(field, title);
        panel.Children.Add(new TextBlock { Text = title, Margin = new Thickness(0, 6, 0, 0) });
        field.MaxLength = maximum;
        panel.Children.Add(field);
    }

    private LocationPoint? Point
    {
        get
        {
            if (!double.TryParse(_latitude.Text.Replace(',', '.'), NumberStyles.Float, CultureInfo.InvariantCulture, out var lat) ||
                !double.TryParse(_longitude.Text.Replace(',', '.'), NumberStyles.Float, CultureInfo.InvariantCulture, out var lon))
                return null;
            return LocationCoordinates.FromDegrees(lat, lon);
        }
    }

    private bool ValidLabel => Encoding.UTF8.GetByteCount(_label.Text.Trim()) <= 256 && !_label.Text.Trim().Any(char.IsControl);
    private bool IsLive => _live.IsChecked == true;

    private void CoordinatesChanged(object sender, TextChangedEventArgs args)
    {
        _accuracy = null;
        _status.Text = "";
        Update();
    }

    private void Update()
    {
        var point = IsLive ? null : Point;
        _preview.Text = !ValidLabel ? AppStrings.Get("UiLocationLabelInvalid") : point is not null
            ? $"{LocationCoordinates.Format(point.LatitudeE6)}, {LocationCoordinates.Format(point.LongitudeE6)}" +
                (_accuracy is { } metres ? Environment.NewLine + AppStrings.Format("UiLocationAccuracy", metres) : "")
            : !IsLive && _latitude.Text.Length != 0 && _longitude.Text.Length != 0
                ? AppStrings.Get("UiLocationCoordinatesInvalid")
                : "";
        _manualFields.Visibility = _options.Visibility = IsLive ? Visibility.Collapsed : Visibility.Visible;
        _liveFields.Visibility = IsLive ? Visibility.Visible : Visibility.Collapsed;
        var editable = !_sending && _locationRequest is null;
        _manualFields.IsEnabled = _liveFields.IsEnabled = _live.IsEnabled = _options.IsEnabled = _label.IsEnabled = editable;
        _send.Content = _sending ? AppStrings.Get("UiLocationSending") : IsLive ? AppStrings.Get("UiLiveLocationShare") : AppStrings.Get("UiLocationSend");
        _send.IsEnabled = (IsLive || point is not null) && ValidLabel && editable;
        _cancel.IsEnabled = !_sending;
    }

    private void ChooseOptions()
    {
        var dialog = new SendOptionsDialog(_selection) { Owner = this };
        if (dialog.ShowDialog() != true) return;
        _selection = dialog.Result;
        UpdateOptionsLabel();
    }

    private void UpdateOptionsLabel()
    {
        var summary = SendOptionsText.Format(_selection);
        _options.Content = summary.Length == 0 ? AppStrings.Get("UiSendOptions") : summary;
    }

    private async Task LocateAsync(bool share)
    {
        if (_sending || _locationRequest is not null) return;
        using var cancellation = new CancellationTokenSource();
        _locationRequest = cancellation;
        _status.Text = AppStrings.Get("UiLocationFinding");
        Update();
        try
        {
            var access = await Geolocator.RequestAccessAsync().AsTask(cancellation.Token);
            if (access != GeolocationAccessStatus.Allowed)
            {
                if (!_closed) _status.Text = share ? AppStrings.Get("UiLocationAccess") : AppStrings.Get("UiLocationAccessManual");
                return;
            }
            var locator = new Geolocator { DesiredAccuracy = PositionAccuracy.High };
            var sample = await locator.GetGeopositionAsync(TimeSpan.Zero, TimeSpan.FromSeconds(15)).AsTask(cancellation.Token);
            if (_closed) return;
            var coordinates = sample.Coordinate.Point.Position;
            var point = LocationCoordinates.FromDegrees(coordinates.Latitude, coordinates.Longitude);
            if (point is null || share && Math.Abs((DateTimeOffset.UtcNow - sample.Coordinate.Timestamp).TotalSeconds) > 30)
            {
                _status.Text = share ? AppStrings.Get("UiLocationUnavailable") : AppStrings.Get("UiLocationUnavailableManual");
                return;
            }
            if (share)
            {
                var duration = TimeSpan.FromMinutes(_duration.SelectedIndex switch { 1 => 60, 2 => 480, _ => 15 });
                var silent = _silent.IsChecked == true;
                await QueueAsync(label => _share(point, duration, label, silent), AppStrings.Get("UiLiveLocationStartError"));
                return;
            }
            _latitude.Text = LocationCoordinates.Format(point.LatitudeE6);
            _longitude.Text = LocationCoordinates.Format(point.LongitudeE6);
            var accuracy = sample.Coordinate.Accuracy;
            _accuracy = double.IsFinite(accuracy) && accuracy is >= 0 and <= 10_000_000 ? (int)Math.Round(accuracy) : null;
            _status.Text = "";
        }
        catch (OperationCanceledException) when (_closed) { }
        catch (Exception error) when (error is COMException or UnauthorizedAccessException or InvalidOperationException or
            TimeoutException or OperationCanceledException)
        {
            if (!_closed) _status.Text = share ? AppStrings.Get("UiLocationUnavailable") : AppStrings.Get("UiLocationUnavailableManual");
        }
        finally
        {
            _locationRequest = null;
            if (!_closed) Update();
        }
    }

    private async Task SendAsync()
    {
        if (_sending || _locationRequest is not null || !ValidLabel) return;
        if (IsLive) { await LocateAsync(share: true); return; }
        if (Point is { } point) await QueueAsync(label => _queue(point, label, _selection), AppStrings.Get("UiLocationQueueError"));
    }

    private async Task QueueAsync(Func<string?, Task<bool>> queue, string failure)
    {
        _sending = true;
        _status.Text = AppStrings.Get("UiLocationSending");
        Update();
        var label = _label.Text.Trim();
        var saved = false;
        try { saved = await queue(label.Length == 0 ? null : label); }
        finally { _sending = false; }
        if (saved) DialogResult = true;
        else
        {
            _status.Text = failure;
            Update();
        }
    }
}

internal sealed class LocationDetailsDialog : DialogWindow
{
    private readonly Func<LocationPoint?> _load;
    private readonly Func<LiveLocationSnapshot?>? _readLive;
    private readonly EncryptedDatabase? _database;
    private readonly Func<bool>? _collecting;
    private readonly Func<Task<bool>>? _stopLive;
    private readonly DispatcherTimer? _clock;
    private readonly TextBox _coordinates = new() { IsReadOnly = true, Visibility = Visibility.Collapsed };
    private readonly TextBlock _status = new() { Text = AppStrings.Get("UiLocationLoading"), TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _liveStatus = new() { Visibility = Visibility.Collapsed };
    private readonly Button _maps;
    private readonly Button _stop;
    private readonly Button _retry;
    private LocationPoint? _point;
    private LiveLocationSnapshot? _snapshot;
    private int _generation;
    private bool _stopping;
    private bool _closed;

    public LocationDetailsDialog(string label, Func<LocationPoint?> load) : this(label, load, null, null, null, null) { }

    public LocationDetailsDialog(string label, EncryptedDatabase database, Func<LiveLocationSnapshot?> load,
        Func<bool>? collecting, Func<Task<bool>>? stop) :
        this(label == "Location" ? AppStrings.Get("UiLiveLocation") : label, () => load()?.Point, load, database, collecting, stop) { }

    private LocationDetailsDialog(string label, Func<LocationPoint?> load, Func<LiveLocationSnapshot?>? readLive,
        EncryptedDatabase? database, Func<bool>? collecting, Func<Task<bool>>? stop)
    {
        _load = load;
        _readLive = readLive;
        _database = database;
        _collecting = collecting;
        _stopLive = stop;
        Title = readLive is null ? AppStrings.Get("UiLocationShared") : AppStrings.Get("UiLiveLocation");
        Width = Math.Min(430, SystemParameters.WorkArea.Width * .94);
        MaxHeight = SystemParameters.WorkArea.Height * .90;
        SizeToContent = SizeToContent.Height;
        var heading = IdentitySetupDialog.Heading(label == "Location" ? AppStrings.Get("UiLocation") : label, 17);
        heading.TextWrapping = TextWrapping.Wrap;
        var actions = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right };
        _retry = IdentitySetupDialog.ActionButton(AppStrings.Get("UiRetry"), () => _ = LoadAsync(), Brushes.Gray);
        _retry.Visibility = Visibility.Collapsed;
        _stop = IdentitySetupDialog.ActionButton(AppStrings.Get("UiLiveLocationStop"), () => _ = StopLiveAsync(), Brushes.Gray);
        _stop.Visibility = Visibility.Collapsed;
        actions.Children.Add(_stop);
        actions.Children.Add(_retry);
        actions.Children.Add(IdentitySetupDialog.ActionButton(AppStrings.Get("UiClose"), Close, Brushes.Gray));
        _maps = IdentitySetupDialog.ActionButton(AppStrings.Get("UiLocationOpenBrowser"), () => _ = OpenMapAsync(), Brushes.Gray);
        _maps.IsEnabled = false;
        actions.Children.Add(_maps);
        var root = new DockPanel();
        DockPanel.SetDock(heading, Dock.Top);
        root.Children.Add(heading);
        DockPanel.SetDock(actions, Dock.Bottom);
        root.Children.Add(actions);
        root.Children.Add(new ScrollViewer { Content = IdentitySetupDialog.Form(_liveStatus, _coordinates, _status),
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        Content = IdentitySetupDialog.DialogCard(root);
        if (readLive is not null)
        {
            _clock = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
            _clock.Tick += (_, _) => UpdateLiveStatus();
        }
        Loaded += async (_, _) =>
        {
            if (_database is not null) _database.Changed += DatabaseChanged;
            _clock?.Start();
            await LoadAsync();
        };
        Closed += (_, _) =>
        {
            _closed = true;
            if (_database is not null) _database.Changed -= DatabaseChanged;
            _clock?.Stop();
        };
    }

    private void DatabaseChanged(object? sender, EventArgs args)
    {
        if (!Dispatcher.HasShutdownStarted) Dispatcher.BeginInvoke(new Action(() => { if (!_closed) _ = LoadAsync(); }));
    }

    private async Task LoadAsync()
    {
        var generation = ++_generation;
        if (_retry.Visibility == Visibility.Visible) _status.Text = AppStrings.Get("UiLocationLoading");
        _retry.Visibility = Visibility.Collapsed;
        try
        {
            var snapshot = _readLive is { } readLive ? await Task.Run(readLive) : null;
            var point = _readLive is null ? await Task.Run(_load) : snapshot?.Point;
            if (_closed || generation != _generation) return;
            _point = point;
            _snapshot = snapshot;
            UpdateLiveStatus();
            _maps.IsEnabled = point is not null;
            _coordinates.Visibility = point is null ? Visibility.Collapsed : Visibility.Visible;
            if (point is null)
            {
                _status.Text = AppStrings.Get("UiLocationMissing");
                return;
            }
            _coordinates.Text = $"{LocationCoordinates.Format(point.LatitudeE6)}, {LocationCoordinates.Format(point.LongitudeE6)}";
            _status.Text = "";
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException)
        {
            if (!_closed && generation == _generation)
            {
                _status.Text = AppStrings.Get("UiLocationOpenError");
                _retry.Visibility = Visibility.Visible;
            }
        }
    }

    private void UpdateLiveStatus()
    {
        _liveStatus.Visibility = _snapshot is null ? Visibility.Collapsed : Visibility.Visible;
        var active = _snapshot is { Ended: false } state && state.ExpiresAt > DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        _liveStatus.Text = !active ? AppStrings.Get("UiLiveLocationEnded") : _collecting?.Invoke() == false ? AppStrings.Get("UiLiveLocationPaused") : AppStrings.Get("UiLiveLocationActive");
        _stop.Visibility = _stopLive is not null && active ? Visibility.Visible : Visibility.Collapsed;
        _stop.IsEnabled = !_stopping;
    }

    private async Task StopLiveAsync()
    {
        if (_stopLive is null || _stopping) return;
        _stopping = true;
        UpdateLiveStatus();
        try
        {
            var stopped = await _stopLive();
            if (_closed) return;
            if (stopped) await LoadAsync();
            else _status.Text = AppStrings.Get("UiLiveLocationStopError");
        }
        finally
        {
            _stopping = false;
            if (!_closed) UpdateLiveStatus();
        }
    }

    private async Task OpenMapAsync()
    {
        _maps.IsEnabled = false;
        try
        {
            var point = await Task.Run(_load);
            if (_closed) return;
            if (point is null || _readLive is null && point != _point)
            {
                _point = null;
                _coordinates.Visibility = Visibility.Collapsed;
                _status.Text = AppStrings.Get("UiLocationMissing");
                return;
            }
            var lat = LocationCoordinates.Format(point.LatitudeE6);
            var lon = LocationCoordinates.Format(point.LongitudeE6);
            var url = $"https://www.openstreetmap.org/?mlat={lat}&mlon={lon}#map=16/{lat}/{lon}";
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (Exception error) when (error is IOException or InvalidOperationException or Microsoft.Data.Sqlite.SqliteException or System.ComponentModel.Win32Exception)
        {
            if (!_closed) _status.Text = AppStrings.Get("UiLocationMapsError");
        }
        finally { if (!_closed) _maps.IsEnabled = _point is not null; }
    }
}
