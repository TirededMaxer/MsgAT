// SPDX-License-Identifier: AGPL-3.0-only
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Windows.Devices.Enumeration;
using MsgAT.Windows.Core.Storage;
using MsgAT.Windows.Infrastructure;
using MsgAT.Windows.Media;

namespace MsgAT.Windows.Views;

internal sealed class CapturedRecording(EncryptedCaptureFile file,IDisposable lease):IDisposable
{
    public EncryptedCaptureFile File {get;}=file;
    private IDisposable? _lease=lease;
    public void Dispose()=>Interlocked.Exchange(ref _lease,null)?.Dispose();
}
[System.Diagnostics.CodeAnalysis.SuppressMessage("Design", "CA1001", Justification = "The async window-closing path disposes recording playback before closing.")]
internal sealed class RecordingDialog:DialogWindow
{
    private sealed record DeviceChoice(string Id,string Name);
    private readonly EncryptedCaptureStore _captures;
    private RecordingController _controller;
    private readonly ComboBox _microphones=new(){DisplayMemberPath=nameof(DeviceChoice.Name),MinWidth=220};
    private readonly Button _start = new() { Margin = new Thickness(4) };
    private readonly Button _pause = new() { Margin = new Thickness(4), IsEnabled = false };
    private readonly Button _finish = new() { Margin = new Thickness(4), IsEnabled = false };
    private readonly Button _again = new() { Margin = new Thickness(4), Visibility = Visibility.Collapsed };
    private readonly Button _use = new() { Content = AppStrings.Get("UiRecordingUse"), Margin = new Thickness(4), IsEnabled = false };
    private readonly Button _cancel = new() { Content = AppStrings.Get("UiCancel"), Margin = new Thickness(4) };
    private readonly TextBlock _status=new(){TextWrapping=TextWrapping.Wrap,Margin=new Thickness(0,8,0,8)};
    private readonly TextBlock _duration=new(){Text="00:00",FontSize=28,HorizontalAlignment=HorizontalAlignment.Center};
    private readonly RecordingWaveform _waveform = new() { Height = 30, Visibility = Visibility.Collapsed };
    private readonly Grid _preview = new() { Height = 64, Visibility = Visibility.Collapsed };
    private readonly TextBlock _indicator = new() { Text = "●", Margin = new Thickness(0, 0, 8, 0), VerticalAlignment = VerticalAlignment.Center };
    private readonly DispatcherTimer _timer=new(){Interval=TimeSpan.FromMilliseconds(100)};
    private EncryptedMediaView? _playback;
    private EncryptedCaptureFile? _ready;
    private Task? _closeTask;
    private Task? _resetTask;
    private bool _allowClose,_closing,_busy;
    private bool _starting,_finishAfterOperation;
    private bool _resetting, _finishFailed;
    internal bool YieldedToCall { get; private set; }
    private readonly Func<bool> _canRecord;
    public CapturedRecording? Result {get;private set;}
    public RecordingDialog(EncryptedCaptureStore captures,Func<bool>? canRecord=null)
    {
        _canRecord=canRecord??(()=>true);
        _captures = captures;
        _controller = CreateController();
        Title = AppStrings.Get("UiVoiceMessage");
        Width = 440;
        SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.NoResize;
        SetIcon(_start, "\uE720", "UiRecordingStart");
        SetIcon(_finish, "\uE71A", "UiRecordingFinish");
        SetIcon(_again, "\uE72C", "UiRecordingAgain");
        var root=new DockPanel{Margin=new Thickness(20)};
        var devices = new StackPanel();
        devices.Children.Add(new Label { Content = AppStrings.Get("UiRecordingMicrophone"), Target = _microphones });
        devices.Children.Add(_microphones);
        DockPanel.SetDock(devices,Dock.Top);root.Children.Add(devices);
        var bottom = new StackPanel { Margin = new Thickness(0, 12, 0, 0) };
        var clock = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
        clock.Children.Add(_indicator);
        clock.Children.Add(_duration);
        bottom.Children.Add(clock);
        bottom.Children.Add(_waveform);
        bottom.Children.Add(_preview);
        bottom.Children.Add(_status);
        var buttons=new WrapPanel{HorizontalAlignment=HorizontalAlignment.Right};
        _cancel.Click += async (_, _) => await CloseAsync(false);
        buttons.Children.Add(_cancel);
        foreach(var button in new[]{_start,_pause,_finish,_again,_use})buttons.Children.Add(button);
        bottom.Children.Add(buttons);
        root.Children.Add(bottom);
        Content = root;
        _start.Click+=async(_,_)=>await StartAsync();
        _again.Click += async (_, _) => await (_resetTask = RecordAgainAsync());
        _pause.Click+=async(_,_)=>
        {
            if(_busy || _closing)return;_busy=true;UpdateButtons();
            try{if(_controller.Phase==RecordingPhase.Recording)await _controller.PauseAsync();else await _controller.ResumeAsync();}
            catch(Exception){_status.Text="Could not change recording state.";}
            finally{_busy=false;UpdateButtons();}
            if (YieldedToCall) ContinueCallHandoff();
            else if (_finishAfterOperation) await FinishAsync();
        };
        _finish.Click+=async(_,_)=>await FinishAsync();_use.Click+=async(_,_)=>await CloseAsync(true);
        _timer.Tick+=(_,_)=>
        {
            _duration.Text=_controller.Duration.ToString(@"mm\:ss", AppStrings.InterfaceCulture);
            var level = _controller.TakeInputPeak();
            if (_controller.Phase == RecordingPhase.Recording && level >= 0)
            {
                _waveform.Push(level);
                _waveform.Visibility = Visibility.Visible;
            }
            if(!_busy && !_closing && !_finishFailed && _controller.Phase is RecordingPhase.Recording or RecordingPhase.Paused)
                try{if(_controller.ReachedLimit || !_canRecord())_ = FinishAsync();}catch(Exception){_status.Text="Recording storage is unavailable.";_ = CloseAsync(false);}
        };
        Loaded+=async(_,_)=>{
            System.Windows.Application.Current.Deactivated+=AppDeactivated;
            await LoadDevicesAsync();
        };
        Closed+=(_,_)=>System.Windows.Application.Current.Deactivated-=AppDeactivated;
        PreviewKeyDown += async (_, args) =>
        {
            if (args.Key != System.Windows.Input.Key.Escape || _microphones.IsDropDownOpen) return;
            args.Handled = true;
            await CloseAsync(false);
        };
        Closing+=(_,e)=>{if(!_allowClose){e.Cancel=true;_ = CloseAsync(false);}};
        UpdateButtons();
    }
    private RecordingController CreateController()
    {
        var controller = new RecordingController(_captures, Dispatcher);
        controller.Failed += message =>
        {
            if (ReferenceEquals(_controller, controller) && !_closing && !_resetting) _status.Text = message;
        };
        controller.FinishRequested += () =>
        {
            if (!ReferenceEquals(_controller, controller) || _closing || _resetting || _finishFailed) return;
            if (_busy) _finishAfterOperation = true;
            else _ = FinishAsync();
        };
        return controller;
    }

    private static void SetIcon(Button button, string glyph, string label)
    {
        var title = AppStrings.Get(label);
        button.Content = new TextBlock { Text = glyph, FontFamily = new FontFamily("Segoe MDL2 Assets") };
        button.Width = 36;
        button.ToolTip = title;
        AutomationProperties.SetName(button, title);
    }
    private void AppDeactivated(object? sender,EventArgs args)
    {
        _playback?.PausePlayback();
        if(_closing || (!_starting && _controller.Phase is not (RecordingPhase.Recording or RecordingPhase.Paused)))return;
        if(_busy)_finishAfterOperation=true;
        else _ = FinishAsync();
    }

    internal void YieldForIncomingCall()
    {
        if (_closing || YieldedToCall) return;
        YieldedToCall = true;
        _playback?.PausePlayback();
        UpdateButtons();
        ContinueCallHandoff();
    }

    private void ContinueCallHandoff()
    {
        if (!YieldedToCall || _busy || _closing) return;
        if (_ready is null && !_finishFailed && _controller.Phase is (RecordingPhase.Recording or RecordingPhase.Paused))
            _ = FinishAsync();
        else _ = CloseAsync(_ready is not null);
    }
    private void UpdateButtons()
    {
        var idle = _controller.Phase == RecordingPhase.Idle && !_finishFailed;
        _start.IsEnabled=!_busy && !_closing && idle;_microphones.IsEnabled=_start.IsEnabled;
        var recording = !_finishFailed && _controller.Phase is RecordingPhase.Recording or RecordingPhase.Paused;
        _pause.IsEnabled = _finish.IsEnabled = !_busy && !_closing && recording;
        var paused = _controller.Phase == RecordingPhase.Paused;
        SetIcon(_pause, paused ? "\uE768" : "\uE769", paused ? "UiRecordingResume" : "UiPause");
        _indicator.Visibility = recording ? Visibility.Visible : Visibility.Collapsed;
        _indicator.Foreground = paused ? Brushes.Gray : Brushes.Red;
        _waveform.SetPaused(paused);
        _waveform.Visibility = recording && _waveform.HasSamples ? Visibility.Visible : Visibility.Collapsed;
        _start.Visibility = idle ? Visibility.Visible : Visibility.Collapsed;
        _pause.Visibility = _finish.Visibility = _indicator.Visibility;
        _use.Visibility = _ready is null ? Visibility.Collapsed : Visibility.Visible;
        _use.IsEnabled = !_busy && !_closing && _ready is not null;
        _again.Visibility = _ready is not null || _finishFailed || _controller.Phase == RecordingPhase.Closed
            ? Visibility.Visible : Visibility.Collapsed;
        _again.IsEnabled = !_busy && !_closing;
        _cancel.IsEnabled = !_resetting && !_closing;
        if (YieldedToCall) _start.IsEnabled = _pause.IsEnabled = _finish.IsEnabled = _again.IsEnabled = _use.IsEnabled = false;
    }
    private async Task LoadDevicesAsync()
    {
        _busy=true;UpdateButtons();
        try
        {
            foreach(var microphone in await DeviceInformation.FindAllAsync(DeviceClass.AudioCapture))_microphones.Items.Add(new DeviceChoice(microphone.Id,microphone.Name));
            if(_microphones.Items.Count>0)_microphones.SelectedIndex=0;
        }
        catch(Exception){_status.Text="Capture devices could not be listed. Check Windows privacy settings.";}
        finally{_busy=false;UpdateButtons();}
        ContinueCallHandoff();
    }
    private async Task StartAsync()
    {
        if(_busy || _closing)return;_busy=true;_starting=true;UpdateButtons();
        try
        {
            if(!_canRecord())throw new InvalidOperationException("Finish the call before recording.");
            if(_microphones.SelectedItem is null)throw new InvalidOperationException("No microphone is available.");
            await _controller.StartAsync((_microphones.SelectedItem as DeviceChoice)?.Id);
            if (_closing) return;
            _timer.Start();_status.Text="";
        }
        catch(Exception)
        {
            _finishFailed = true;
            _status.Text = "Recording could not start. Check device access in Windows privacy settings.";
            try { await _controller.DisposeAsync(); }
            catch (Exception) { _status.Text = "Recording resources have not finished closing."; }
        }
        finally{_starting=false;_busy=false;UpdateButtons();}
        if (YieldedToCall) ContinueCallHandoff();
        else if (_finishAfterOperation) await FinishAsync();
    }
    private async Task FinishAsync()
    {
        _finishAfterOperation=false;
        if(_busy || _closing || _finishFailed || _ready is not null || _controller.Phase is not (RecordingPhase.Recording or RecordingPhase.Paused))return;
        _busy=true;UpdateButtons();
        try
        {
            var recording = await _controller.FinishAsync();
            if (_closing) return;
            _ready = recording;
            _timer.Stop();
            _preview.Children.Clear();
            _duration.Text = _controller.Duration.ToString(@"mm\:ss", AppStrings.InterfaceCulture);
            _playback = new EncryptedMediaView(_ready.Mime, _ready.OpenRead, _ready.Size, () => { },
                showClose: false, inlineAudio: true, autoplay: false);
            _preview.Children.Add(_playback);
            _preview.Visibility = Visibility.Visible;
        }
        catch(Exception)
        {
            _finishFailed = true;
            _timer.Stop();
            _status.Text = "The recording could not be finalized.";
        }
        finally{_busy=false;UpdateButtons();}
        ContinueCallHandoff();
    }
    private async Task RecordAgainAsync()
    {
        if (_busy || _closing || _again.Visibility != Visibility.Visible) return;
        _busy = _resetting = true;
        _finishFailed = true;
        _ready = null;
        UpdateButtons();
        _timer.Stop();
        _playback?.Dispose();
        _playback = null;
        _preview.Children.Clear();
        _preview.Visibility = Visibility.Collapsed;
        try
        {
            await _controller.DisposeAsync();
            _controller = CreateController();
            _waveform.Clear();
            _finishFailed = _finishAfterOperation = false;
            _duration.Text = "00:00";
            _status.Text = "";
        }
        catch (Exception)
        {
            _status.Text = "Recording resources have not finished closing.";
        }
        finally
        {
            _busy = _resetting = false;
            UpdateButtons();
        }
        ContinueCallHandoff();
    }

    private Task CloseAsync(bool accept) => _resetting ? Task.CompletedTask : (_closeTask ??= CloseCoreAsync(accept));

    internal async Task CloseForExitAsync()
    {
        IsEnabled = false;
        if (_resetTask is { } reset) await reset;
        await CloseAsync(false);
    }

    internal void MaskAndClose()
    {
        Opacity = 0;
        _ = CloseForExitAsync();
    }
    private async Task CloseCoreAsync(bool accept)
    {
        _closing=true;_timer.Stop();UpdateButtons();_playback?.Dispose();_playback=null;_preview.Children.Clear();
        CapturedRecording? result=null;
        try
        {
            if(accept && _ready is { } file)result=new CapturedRecording(file,file.RetainFrozen());
            await _controller.DisposeAsync();Result=result;result=null;_allowClose=true;DialogResult=accept;
        }
        catch(Exception){result?.Dispose();_closing=false;_closeTask=null;_status.Text="Recording resources have not finished closing. Try cancel again.";UpdateButtons();}
    }

    private sealed class RecordingWaveform : FrameworkElement
    {
        private const int SampleCount = 28;
        private readonly Queue<float> _levels = new(SampleCount);
        private readonly Pen _active = PeakPen(Brushes.Red);
        private readonly Pen _paused = PeakPen(Brushes.Gray);
        private bool _isPaused;
        public bool HasSamples => _levels.Count > 0;

        public void Push(float level)
        {
            if (_levels.Count == SampleCount) _levels.Dequeue();
            _levels.Enqueue(MathF.Sqrt(level));
            InvalidateVisual();
        }

        public void Clear()
        {
            _levels.Clear();
            InvalidateVisual();
        }

        public void SetPaused(bool paused)
        {
            if (_isPaused == paused) return;
            _isPaused = paused;
            InvalidateVisual();
        }

        protected override void OnRender(DrawingContext drawingContext)
        {
            base.OnRender(drawingContext);
            var step = Math.Min(5, ActualWidth / SampleCount);
            var centerY = ActualHeight / 2;
            var x = (ActualWidth - step * SampleCount) / 2 + (SampleCount - _levels.Count + .5) * step;
            var pen = _isPaused ? _paused : _active;
            foreach (var level in _levels)
            {
                var half = 1 + level * Math.Max(0, centerY - 3);
                drawingContext.DrawLine(pen, new Point(x, centerY - half), new Point(x, centerY + half));
                x += step;
            }
        }

        private static Pen PeakPen(Brush color)
        {
            var pen = new Pen(color, 2) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round };
            pen.Freeze();
            return pen;
        }
    }
}
