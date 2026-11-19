// SPDX-License-Identifier: AGPL-3.0-only
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Windows.Threading;
using Windows.Media;
using Windows.Media.Capture;
using Windows.Media.Capture.Frames;
using Windows.Media.MediaProperties;
using Windows.Storage.Streams;
using MsgAT.Windows.Core.Storage;
using WinRT;

namespace MsgAT.Windows.Media;

internal enum RecordingPhase { Idle,Recording,Paused,Ready,Closed }

/// <summary>All MediaCapture ownership is marshalled to the window dispatcher.</summary>
internal sealed class RecordingController(EncryptedCaptureStore captures,Dispatcher dispatcher):IAsyncDisposable
{
    private readonly SemaphoreSlim _operations=new(1,1);
    private readonly Stopwatch _duration=new();
    private MediaCapture? _device;
    private MicrophoneOwnership.RecordingLease? _microphone;
    private EncryptedCaptureFile? _file;
    private Stream? _encoderStream;
    private IRandomAccessStream? _nativeStream;
    private MediaFrameReader? _levelReader;
    private volatile bool _readingLevels;
    private float _latestPeak = -1;
    private bool _started;
    private string? _failure;
    public RecordingPhase Phase {get;private set;}
    public TimeSpan Duration=>_duration.Elapsed;
    public event Action? FinishRequested;
    public event Action<string>? Failed;

    public async Task StartAsync(string? audioDevice)
    {
        await _operations.WaitAsync();
        try
        {
            if(Phase!=RecordingPhase.Idle || _device is not null)throw new InvalidOperationException("A recording device is already active.");
            _microphone = MicrophoneOwnership.StartRecording(DrainForCallAsync);
            var device=new MediaCapture();_device=device;
            await device.InitializeAsync(new MediaCaptureInitializationSettings{
                AudioDeviceId=audioDevice??"",StreamingCaptureMode=StreamingCaptureMode.Audio});
            device.Failed+=DeviceFailed;device.RecordLimitationExceeded+=DeviceLimit;
            var name=$"Voice-{DateTime.Now:yyyyMMdd-HHmmss}.m4a";
            _file=await captures.OpenAsync(name,"audio/mp4",StopHardwareAsync);
            _encoderStream=_file.OpenEncoderStream();_nativeStream=_encoderStream.AsRandomAccessStream();
            var profile=MediaEncodingProfile.CreateM4a(AudioEncodingQuality.High);
            profile.Audio.ChannelCount=1;profile.Audio.SampleRate=48_000;profile.Audio.Bitrate=64_000;
            await device.StartRecordToStreamAsync(profile,_nativeStream);
            _started=true;Phase=RecordingPhase.Recording;_duration.Start();
            await StartLevelReaderAsync(device);
            _readingLevels = _levelReader is not null;
        }
        finally{_operations.Release();}
    }
    public async Task PauseAsync()
    {
        await _operations.WaitAsync();
        try
        {
            if (_device is null || Phase != RecordingPhase.Recording)
                throw new InvalidOperationException("No recording is active.");
            await _device.PauseRecordAsync(MediaCapturePauseBehavior.RetainHardwareResources);
            _readingLevels = false;
            TakeInputPeak();
            _duration.Stop();
            Phase = RecordingPhase.Paused;
        }
        finally { _operations.Release(); }
    }
    public async Task ResumeAsync()
    {
        await _operations.WaitAsync();
        try
        {
            if (_device is null || Phase != RecordingPhase.Paused)
                throw new InvalidOperationException("No paused recording is active.");
            await _device.ResumeRecordAsync();
            TakeInputPeak();
            _readingLevels = _levelReader is not null;
            _duration.Start();
            Phase = RecordingPhase.Recording;
        }
        finally { _operations.Release(); }
    }
    public async Task<EncryptedCaptureFile> FinishAsync()
    {
        var file=_file??throw new InvalidOperationException("No recording is active.");
        await file.FreezeAsync();
        if(_failure is not null)throw new IOException(_failure);
        Phase=RecordingPhase.Ready;return file;
    }
    public bool ReachedLimit=>_file is not null && (Duration.TotalMinutes>=30 || _file.Size>=90L*1024*1024);
    public float TakeInputPeak() => Interlocked.Exchange(ref _latestPeak, -1);

    private async Task StartLevelReaderAsync(MediaCapture device)
    {
        MediaFrameReader? reader = null;
        try
        {
            var source = device.FrameSources.Values.FirstOrDefault(value => value.Info.MediaStreamType == MediaStreamType.Audio);
            if (source is null) return;
            reader = await device.CreateFrameReaderAsync(source);
            reader.FrameArrived += LevelFrameArrived;
            if (await reader.StartAsync() != MediaFrameReaderStartStatus.Success) return;
            _levelReader = reader;
            reader = null;
        }
        catch (Exception error) when (error is COMException or InvalidOperationException or NotSupportedException or UnauthorizedAccessException)
        {
            // Devices that cannot expose a reader alongside recording still record normally.
        }
        finally
        {
            if (reader is not null)
            {
                reader.FrameArrived -= LevelFrameArrived;
                reader.Dispose();
            }
        }
    }

    private void LevelFrameArrived(MediaFrameReader sender, MediaFrameArrivedEventArgs args)
    {
        if (!_readingLevels) return;
        try
        {
            using var frame = sender.TryAcquireLatestFrame();
            if (frame?.AudioMediaFrame is not { } audio) return;
            var format = audio.AudioEncodingProperties;
            var isFloat = format.Subtype == MediaEncodingSubtypes.Float && format.BitsPerSample == 32;
            var isPcm16 = format.Subtype == MediaEncodingSubtypes.Pcm && format.BitsPerSample == 16;
            if (!isFloat && !isPcm16) return;
            using var samples = audio.GetAudioFrame();
            using var buffer = samples.LockBuffer(AudioBufferAccessMode.Read);
            using var reference = buffer.CreateReference();
            reference.As<IMemoryBufferByteAccess>().GetBuffer(out var address, out var capacity);
            var sampleBytes = isFloat ? sizeof(float) : sizeof(short);
            var count = (int)(Math.Min(capacity, buffer.Length) / (uint)sampleBytes);
            if (address == 0 || count == 0) return;
            var peak = 0f;
            var stride = Math.Max(1, count / 192);
            for (var index = 0; index < count; index += stride)
            {
                var value = isFloat
                    ? BitConverter.Int32BitsToSingle(Marshal.ReadInt32(address, index * sampleBytes))
                    : Marshal.ReadInt16(address, index * sampleBytes) / 32768f;
                if (float.IsFinite(value)) peak = Math.Max(peak, Math.Abs(value));
            }
            if (!_readingLevels) return;
            peak = Math.Min(peak, 1f);
            var previous = Volatile.Read(ref _latestPeak);
            while (peak > previous)
            {
                var observed = Interlocked.CompareExchange(ref _latestPeak, peak, previous);
                if (observed == previous) break;
                previous = observed;
            }
        }
        catch (Exception error) when (error is COMException or InvalidOperationException or InvalidCastException)
        {
            // An audio frame can become invalid as the device is stopped.
        }
    }

    [ComImport]
    [Guid("5B0D3235-4DBA-4D44-865E-8F1D0E4FD04D")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMemoryBufferByteAccess
    {
        void GetBuffer(out nint address, out uint capacity);
    }

    private Task StopHardwareAsync()=>dispatcher.InvokeAsync(StopDeviceAsync).Task.Unwrap();

    private async Task DrainForCallAsync()
    {
        await StopHardwareAsync();
        if (Phase is RecordingPhase.Recording or RecordingPhase.Paused) FinishRequested?.Invoke();
    }

    private async Task StopDeviceAsync()
    {
        await _operations.WaitAsync();
        try
        {
            _readingLevels = false;
            _duration.Stop();
            if (_levelReader is { } reader)
            {
                _levelReader = null;
                reader.FrameArrived -= LevelFrameArrived;
                try { await reader.StopAsync(); }
                catch (Exception error) when (error is COMException or InvalidOperationException) { }
                finally
                {
                    try { reader.Dispose(); }
                    catch (Exception error) when (error is COMException or InvalidOperationException) { }
                }
            }
            if(_started && _device is not null)
            {try{await _device.StopRecordAsync();}catch(Exception){_failure="The recording could not be finalized.";}finally{_started=false;}}
            if(_device is { } device){device.Failed-=DeviceFailed;device.RecordLimitationExceeded-=DeviceLimit;device.Dispose();_device=null;}
            _microphone?.Dispose();_microphone=null;
            _nativeStream?.Dispose();_nativeStream=null;_encoderStream?.Dispose();_encoderStream=null;
        }
        finally{_operations.Release();}
    }
    private void DeviceFailed(MediaCapture sender,MediaCaptureFailedEventArgs args)
    {_ = dispatcher.InvokeAsync(()=>{_failure="Recording device stopped unexpectedly.";Failed?.Invoke(_failure);FinishRequested?.Invoke();});}
    private void DeviceLimit(MediaCapture sender)=>_ = dispatcher.InvokeAsync(()=>FinishRequested?.Invoke());
    public async ValueTask DisposeAsync()
    {
        // Startup may still be creating the capture. Wait for the hardware operation before taking its file.
        await StopHardwareAsync();
        if (_file is { } file) await file.DisposeAsync();
        _file = null;
        Phase = RecordingPhase.Closed;
    }
}
