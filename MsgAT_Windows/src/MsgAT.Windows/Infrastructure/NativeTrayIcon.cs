// SPDX-License-Identifier: AGPL-3.0-only
using System.Diagnostics;
using System.Drawing;
using System.Runtime.InteropServices;
using Forms=System.Windows.Forms;

namespace MsgAT.Windows.Infrastructure;

/// <summary>Owns one Shell icon; native NIIF_NOSOUND preserves a visible quiet notification.</summary>
internal sealed class NativeTrayIcon : Forms.NativeWindow,IDisposable
{
    private const int Callback=0x8000+44;
    private readonly uint _taskbarCreated;
    private readonly Icon _icon;
    private readonly Forms.ContextMenuStrip _menu;
    private bool _visible;
    private bool _added;
    private bool _disposed;
    private sealed record Balloon(string Text, bool Silent, Func<bool> CanPresent, Action Open, bool IsCall, string? Peer);
    private Balloon? _currentBalloon;
    private Balloon? _waitingBalloon;
    private bool _balloonShown;
    private bool _balloonCancelled;
    public event EventHandler? Activated;
    public NativeTrayIcon(Icon icon,Forms.ContextMenuStrip menu)
    {
        _icon=(Icon)icon.Clone();_menu=menu;_taskbarCreated=RegisterWindowMessageW("TaskbarCreated");
        try {CreateHandle(new Forms.CreateParams{Caption="MsgAT notification owner",ExStyle=0x80});}
        catch {_icon.Dispose();_menu.Dispose();throw;}
        _menu.Closed+=(_,_)=>{if(!_disposed && _added){var data=Data();_ = Shell_NotifyIconW(3,ref data);}};
    }
    public bool Visible
    {
        get=>_visible;
        set {if(_disposed || _visible==value)return;_visible=value;if(value)Add();else Remove();}
    }
    internal bool IsRegistered => _added && !_disposed;
    private IconData Data()=>new(){Size=(uint)Marshal.SizeOf<IconData>(),Window=Handle,Id=1,Tip="MsgAT",Info="",InfoTitle="",State=0,StateMask=0,Guid=Guid.Empty,BalloonIcon=0};
    private void Add()
    {
        if(_disposed || !_visible)return;
        var data=Data();data.Flags=1|2|4|0x80;data.Message=Callback;data.Icon=_icon.Handle;
        _added=Shell_NotifyIconW(0,ref data);
        if(_added){data.TimeoutOrVersion=4;_ = Shell_NotifyIconW(4,ref data);}
        else Trace.TraceWarning("Notification area icon is temporarily unavailable.");
    }
    private void Remove()
    {
        ResetBalloons();
        if(!_added)return;
        var data=Data();_ = Shell_NotifyIconW(2,ref data);_added=false;
    }
    public void ShowNotification(string message, bool silent, Func<bool> canPresent, Action open, bool isCall = false,
        string? peer = null)
    {
        if (_disposed || !_visible) return;
        var balloon = new Balloon(message, silent, canPresent, open, isCall, peer);
        if (_currentBalloon is null) ShowBalloon(balloon);
        else if (isCall || _waitingBalloon?.IsCall != true) _waitingBalloon = balloon;
    }
    public void DismissCallNotification() => DismissNotification(isCall: true);
    public void DismissMessageNotifications(string? peer = null) => DismissNotification(isCall: false, peer);

    private void DismissNotification(bool isCall, string? peer = null)
    {
        bool Matches(Balloon? balloon) => balloon is not null && balloon.IsCall == isCall &&
            (peer is null || StringComparer.Ordinal.Equals(balloon.Peer, peer));
        if (Matches(_waitingBalloon)) _waitingBalloon = null;
        if (!Matches(_currentBalloon) || !_added) return;
        _balloonCancelled = true;
        var data = Data();
        data.Flags = 0x10; // Empty szInfo removes the currently displayed balloon.
        _ = Shell_NotifyIconW(1, ref data);
    }
    private void ShowBalloon(Balloon balloon)
    {
        if (_disposed || !_visible || !balloon.CanPresent() ||
            SHQueryUserNotificationState(out var state)!=0 || state!=5) return;
        if(!_added)Add();if(!_added)return;
        var data=Data();data.Flags=0x10;data.InfoTitle="MsgAT";data.Info=balloon.Text;
        data.InfoFlags=1|0x80u|(balloon.Silent?0x10u:0u); // NIIF_INFO | RESPECT_QUIET_TIME | optional NOSOUND.
        _currentBalloon = balloon;
        _balloonShown = _balloonCancelled = false;
        if (!Shell_NotifyIconW(1,ref data)) _currentBalloon = null;
    }
    private void FinishBalloon(bool clicked)
    {
        if (_currentBalloon is not { } current) return;
        var open = clicked && _balloonShown && !_balloonCancelled;
        var waiting = _waitingBalloon;
        ResetBalloons();
        if (open)
        {
            current.Open();
            if (waiting?.IsCall == true) ShowBalloon(waiting);
        }
        else if (waiting is not null) ShowBalloon(waiting);
    }
    private void ResetBalloons()
    {
        _currentBalloon = _waitingBalloon = null;
        _balloonShown = _balloonCancelled = false;
    }
    protected override void WndProc(ref Forms.Message message)
    {
        if(!_disposed && _taskbarCreated!=0 && (uint)message.Msg==_taskbarCreated){ResetBalloons();_added=false;if(_visible)Add();}
        if(!_disposed && message.Msg==Callback)
        {
            var code=(int)(message.LParam.ToInt64()&0xffff);
            switch(code)
            {
                case 0x400:case 0x401:case 0x203:Activated?.Invoke(this,EventArgs.Empty);break;
                case 0x402: _balloonShown = _currentBalloon is not null; break; // NIN_BALLOONSHOW
                case 0x403: case 0x404: FinishBalloon(clicked:false); break;
                case 0x405: FinishBalloon(clicked:true); break;
                case 0x7b:case 0x205:
                    _ = SetForegroundWindow(Handle);_menu.Show(Forms.Cursor.Position);break;
            }
        }
        base.WndProc(ref message);
    }
    public void Dispose()
    {
        if(_disposed)return;Remove();_disposed=true;_visible=false;
        try {_menu.Dispose();}finally{try{DestroyHandle();}finally{_icon.Dispose();}}
        GC.SuppressFinalize(this);
    }
    // https://learn.microsoft.com/windows/win32/api/shellapi/ns-shellapi-notifyicondataw
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]
    private struct IconData
    {
        public uint Size;public nint Window;public uint Id;public uint Flags;public uint Message;public nint Icon;
        [MarshalAs(UnmanagedType.ByValTStr,SizeConst=128)]public string Tip;
        public uint State;public uint StateMask;
        [MarshalAs(UnmanagedType.ByValTStr,SizeConst=256)]public string Info;
        public uint TimeoutOrVersion;
        [MarshalAs(UnmanagedType.ByValTStr,SizeConst=64)]public string InfoTitle;
        public uint InfoFlags;public Guid Guid;public nint BalloonIcon;
    }
    [DllImport("shell32.dll",ExactSpelling=true,CharSet=CharSet.Unicode)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [return:MarshalAs(UnmanagedType.Bool)]private static extern bool Shell_NotifyIconW(uint operation,ref IconData data);
    [DllImport("shell32.dll",ExactSpelling=true)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static extern int SHQueryUserNotificationState(out uint state);
    [DllImport("user32.dll",ExactSpelling=true,CharSet=CharSet.Unicode)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    private static extern uint RegisterWindowMessageW(string name);
    [DllImport("user32.dll",ExactSpelling=true)]
    [DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
    [return:MarshalAs(UnmanagedType.Bool)]private static extern bool SetForegroundWindow(nint window);
}
