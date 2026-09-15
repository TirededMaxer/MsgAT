using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace MsgAT.Windows.Views;

public sealed class AttachmentWindow : Window
{
    public AttachmentWindow(string path, string extension)
    {
        Title = Path.GetFileName(path);
        Width = 900;
        Height = 700;
        Background = Brushes.Black;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        MsgAT.Windows.Infrastructure.WindowAppearance.Apply(this);
        if (extension is "mp4" or "mov" or "webm")
        {
            var media = new MediaElement { Source = new Uri(path), LoadedBehavior = MediaState.Manual, ScrubbingEnabled = true, UnloadedBehavior = MediaState.Close, Stretch = Stretch.Uniform };
            var panel = new DockPanel();
            var controls = new Grid { Margin = new Thickness(12), Background = (Brush)FindResource("WindowBrush") };
            controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            controls.ColumnDefinitions.Add(new ColumnDefinition());
            controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var play = new Button { Content = Geometry.Parse("M 7,4 L 20,12 L 7,20 Z"), ToolTip = "Play", Style = (Style)FindResource("IconButtonStyle") };
            var pause = new Button { Content = Geometry.Parse("M 8,4 L 8,20 M 16,4 L 16,20"), ToolTip = "Pause", Style = (Style)FindResource("IconButtonStyle") };
            System.Windows.Automation.AutomationProperties.SetName(play, "Play");
            System.Windows.Automation.AutomationProperties.SetName(pause, "Pause");
            var progress = new Slider { Minimum = 0, Maximum = 1, IsEnabled = false, IsMoveToPointEnabled = true, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 12, 0) };
            System.Windows.Automation.AutomationProperties.SetName(progress, "Playback position");
            var time = new TextBlock { Text = "00:00 / 00:00", Foreground = (Brush)FindResource("TextBrush"), VerticalAlignment = VerticalAlignment.Center, MinWidth = 105 };
            var updating = false;
            var scrubbing = false;
            string FormatTime(TimeSpan value) => value.TotalHours >= 1 ? value.ToString(@"h\:mm\:ss", System.Globalization.CultureInfo.InvariantCulture) : value.ToString(@"mm\:ss", System.Globalization.CultureInfo.InvariantCulture);
            void RefreshPosition()
            {
                if (!media.NaturalDuration.HasTimeSpan) return;
                if (!scrubbing) { updating = true; progress.Value = media.Position.TotalSeconds; updating = false; }
                time.Text = $"{FormatTime(TimeSpan.FromSeconds(progress.Value))} / {FormatTime(media.NaturalDuration.TimeSpan)}";
            }
            var timer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(200) };
            timer.Tick += (_, _) => RefreshPosition();
            progress.AddHandler(System.Windows.Controls.Primitives.Thumb.DragStartedEvent, new System.Windows.Controls.Primitives.DragStartedEventHandler((_, _) => scrubbing = true));
            progress.AddHandler(System.Windows.Controls.Primitives.Thumb.DragCompletedEvent, new System.Windows.Controls.Primitives.DragCompletedEventHandler((_, _) => { scrubbing = false; media.Position = TimeSpan.FromSeconds(progress.Value); RefreshPosition(); }));
            progress.ValueChanged += (_, _) => { if (!updating && !scrubbing && progress.IsEnabled) media.Position = TimeSpan.FromSeconds(progress.Value); };
            media.MediaOpened += (_, _) =>
            {
                if (media.NaturalDuration.HasTimeSpan) { progress.Maximum = media.NaturalDuration.TimeSpan.TotalSeconds; progress.IsEnabled = progress.Maximum > 0; }
                RefreshPosition(); timer.Start();
            };
            media.MediaEnded += (_, _) => { media.Pause(); RefreshPosition(); };
            play.Click += (_, _) => { if (media.NaturalDuration.HasTimeSpan && media.Position >= media.NaturalDuration.TimeSpan) media.Position = TimeSpan.Zero; media.Play(); };
            pause.Click += (_, _) => media.Pause();
            Closed += (_, _) => timer.Stop();
            controls.Children.Add(play);
            Grid.SetColumn(pause, 1); controls.Children.Add(pause);
            Grid.SetColumn(progress, 2); controls.Children.Add(progress);
            Grid.SetColumn(time, 3); controls.Children.Add(time);
            DockPanel.SetDock(controls, Dock.Bottom);
            panel.Children.Add(controls);
            panel.Children.Add(media);
            Content = panel;
            Loaded += (_, _) => media.Play();
            Closed += (_, _) => media.Close();
            media.MediaFailed += (_, args) => MessageBox.Show(this, args.ErrorException.Message, "MsgAT");
        }
        else
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.UriSource = new Uri(path);
            bitmap.EndInit();
            bitmap.Freeze();
            var scale = new ScaleTransform(1, 1);
            var image = new Image { Source = bitmap, Stretch = Stretch.Uniform, Width = bitmap.Width, Height = bitmap.Height, LayoutTransform = scale };
            var scroll = new ScrollViewer { Content = image, HorizontalContentAlignment = HorizontalAlignment.Center, VerticalContentAlignment = VerticalAlignment.Center, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            scroll.SizeChanged += (_, args) =>
            {
                var fit = Math.Min(1, Math.Min(Math.Max(1, args.NewSize.Width - 24) / bitmap.Width, Math.Max(1, args.NewSize.Height - 24) / bitmap.Height));
                image.Width = bitmap.Width * fit;
                image.Height = bitmap.Height * fit;
            };
            scroll.PreviewMouseWheel += (_, args) =>
            {
                scale.ScaleX = scale.ScaleY = Math.Clamp(scale.ScaleX * (args.Delta > 0 ? 1.15 : 1 / 1.15), 0.1, 8);
                args.Handled = true;
            };
            Content = scroll;
        }
        PreviewKeyDown += (_, args) => { if (args.Key == Key.Escape) Close(); };
    }
}
