// SPDX-License-Identifier: AGPL-3.0-only
using System.Security.Cryptography;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using SkiaSharp;

namespace MsgAT.Windows.Infrastructure;

/// <summary>Bounded visible-window GIF/WebP animation; no WebView, extracted file or full-frame cache.</summary>
[System.Diagnostics.CodeAnalysis.SuppressMessage("Design","CA1001:Types that own disposable fields should be disposable",Justification="The WPF visual lifetime releases its decoder on unload, including readers still returning from workers.")]
internal sealed class StickerImageView:Image
{
    private static readonly DependencyProperty PlaybackPausedProperty = DependencyProperty.RegisterAttached(
        "PlaybackPaused", typeof(bool), typeof(StickerImageView),
        new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.Inherits, (source, _) =>
        {
            if (source is StickerImageView view) view.UpdatePlayback();
        }));
    internal static void SetPlaybackPaused(DependencyObject scope, bool paused) => scope.SetValue(PlaybackPausedProperty, paused);
    private static readonly SemaphoreSlim Animations=new(3);
    internal sealed class Lease:IDisposable {private int _held=1;public void Dispose(){if(Interlocked.Exchange(ref _held,0)==1)Animations.Release();}}
    internal sealed class Decoder:IDisposable
    {
        private readonly object _gate=new();private readonly byte[] _bytes;private readonly MemoryStream _stream;private readonly SKCodec _codec;private readonly SKBitmap _pixels;private readonly SKCodecFrameInfo[] _frames;private readonly Lease _lease;
        private bool _closed;private int _index;private int _last=-1;
        internal Decoder(byte[] bytes,Lease lease)
        {
            _bytes=bytes;_lease=lease;_stream=new MemoryStream(bytes,writable:false);SKCodec? codec=null;SKBitmap? pixels=null;
            try {codec=SKCodec.Create(_stream)??throw new IOException("Sticker could not be decoded.");if(codec.Info.Width is <1 or >2048 || codec.Info.Height is <1 or >2048 || codec.FrameCount>300)throw new IOException("Sticker dimensions exceed their limit.");pixels=new SKBitmap(new SKImageInfo(codec.Info.Width,codec.Info.Height,SKColorType.Bgra8888,SKAlphaType.Premul));_codec=codec;_pixels=pixels;_frames=codec.FrameInfo;}
            catch {pixels?.Dispose();codec?.Dispose();_stream.Dispose();CryptographicOperations.ZeroMemory(bytes);throw;}
        }
        internal (BitmapSource Image,int Delay,bool Animated) Next()
        {
            lock(_gate)
            {
                ObjectDisposedException.ThrowIf(_closed,this);
                var count=Math.Max(1,_frames.Length);var prior=-1;
                if(_frames.Length>0 && _last>=_frames[_index].RequiredFrame && _last<_index && _last>=0 && _frames[_last].DisposalMethod!=SKCodecAnimationDisposalMethod.RestorePrevious)prior=_last;
                if(prior<0)_pixels.Erase(SKColors.Transparent);
                var status=_codec.GetPixels(_pixels.Info,_pixels.GetPixels(),new SKCodecOptions(_index,prior));if(status!=SKCodecResult.Success)throw new IOException("Sticker frame is damaged.");
                SKBitmap? scaled=null;
                try {
                    var longest=Math.Max(_pixels.Width,_pixels.Height);
                    if(longest>512)scaled=_pixels.Resize(new SKImageInfo(Math.Max(1,_pixels.Width*512/longest),Math.Max(1,_pixels.Height*512/longest),SKColorType.Bgra8888,SKAlphaType.Premul),new SKSamplingOptions(SKFilterMode.Linear));
                    var pixels=scaled??_pixels;var image=BitmapSource.Create(pixels.Width,pixels.Height,96,96,PixelFormats.Pbgra32,null,pixels.GetPixels(),pixels.ByteCount,pixels.RowBytes);image.Freeze();
                    var duration=_frames.Length==0?100:Math.Clamp(_frames[_index].Duration,20,10000);_last=_index;_index=(_index+1)%count;
                    return(image,duration,count>1);
                } finally {scaled?.Dispose();}
            }
        }
        public void Dispose(){lock(_gate){if(_closed)return;_closed=true;_pixels.Dispose();_codec.Dispose();_stream.Dispose();CryptographicOperations.ZeroMemory(_bytes);_lease.Dispose();}}
    }
    internal static Decoder? TryOpenDecoder(Func<byte[]> read)
    {
        if (!Animations.Wait(0)) return null;
        var lease = new Lease();
        try { return new Decoder(read(), lease); }
        catch { lease.Dispose(); throw; }
    }

    private readonly Func<byte[]> _read;private readonly DispatcherTimer _timer;private readonly DispatcherTimer _retry;
    internal event EventHandler? PlaybackFailed;
    private Window? _window;private ScrollViewer? _scroll;private Decoder? _decoder;private bool _loading;private bool _static;private bool _failed;private long _generation;
    internal StickerImageView(Func<byte[]> read)
    {
        _read=read;Width=176;Height=176;Stretch=Stretch.Uniform;
        _timer=new DispatcherTimer(DispatcherPriority.Background,Dispatcher);_timer.Tick+=async(_,_)=>await NextAsync();
        _retry=new DispatcherTimer(TimeSpan.FromSeconds(1),DispatcherPriority.Background,(_,_)=>UpdatePlayback(),Dispatcher);_retry.Stop();
        Loaded+=(_,_)=>{_window=Window.GetWindow(this);if(_window is not null){_window.Activated+=Changed;_window.Deactivated+=Changed;}DependencyObject? parent=this;while(parent is not null){if(parent is ScrollViewer scroll){_scroll=scroll;scroll.ScrollChanged+=Scrolled;break;}parent=VisualTreeHelper.GetParent(parent);}UpdatePlayback();};
        Unloaded+=(_,_)=>{if(_window is not null){_window.Activated-=Changed;_window.Deactivated-=Changed;}_window=null;if(_scroll is not null)_scroll.ScrollChanged-=Scrolled;_scroll=null;Stop();Source=null;_static=false;_failed=false;};
        IsVisibleChanged+=(_,_)=>UpdatePlayback();
    }
    private void Changed(object? sender,EventArgs args)=>UpdatePlayback();
    private void Scrolled(object sender,ScrollChangedEventArgs args)=>UpdatePlayback();
    private bool CanPlay(){if(!IsLoaded || !IsVisible || _window?.IsActive!=true || (bool)GetValue(PlaybackPausedProperty))return false;if(_scroll is null)return true;try{return TransformToAncestor(_scroll).TransformBounds(new Rect(RenderSize)).IntersectsWith(new Rect(0,0,_scroll.ActualWidth,_scroll.ActualHeight));}catch(InvalidOperationException){return false;}}
    private void UpdatePlayback(){if(!CanPlay()){Stop();return;}if(_decoder is null && !_loading && !_static && !_failed)_=StartAsync();}
    private async Task StartAsync()
    {
        if(!Animations.Wait(0)){_retry.Start();return;}_retry.Stop();_loading=true;var generation=_generation;var lease=new Lease();Decoder? created=null;var transferred=false;
        try {created=await Task.Run(()=>new Decoder(_read(),lease));if(generation!=_generation || !CanPlay())return;_decoder=created;created=null;transferred=true;await NextAsync();}
        catch(Exception error) when(error is IOException or InvalidOperationException or NotSupportedException or FormatException or System.Runtime.InteropServices.ExternalException or CryptographicException or ArgumentException){if(generation==_generation)ReportFailure();}
        finally {_loading=false;if(created is not null){var pending=created;_=Task.Run(pending.Dispose);}else if(!transferred)lease.Dispose();if(generation!=_generation && CanPlay() && !_failed)UpdatePlayback();}
    }
    private async Task NextAsync()
    {
        _timer.Stop();if(_decoder is not { } decoder || !CanPlay()){Stop();return;}var generation=_generation;
        try {var frame=await Task.Run(decoder.Next);if(generation!=_generation)return;Source=frame.Image;if(frame.Animated){_timer.Interval=TimeSpan.FromMilliseconds(frame.Delay);_timer.Start();}else{_static=true;_decoder=null;_=Task.Run(decoder.Dispose);}}
        catch(Exception error) when(error is IOException or InvalidOperationException or NotSupportedException){if(generation==_generation)ReportFailure();}
    }
    private void ReportFailure()
    {
        _failed = true;
        Stop();
        PlaybackFailed?.Invoke(this, EventArgs.Empty);
    }
    private void Stop(){++_generation;_timer.Stop();_retry.Stop();var previous=_decoder;_decoder=null;if(previous is not null)_=Task.Run(previous.Dispose);}
}
