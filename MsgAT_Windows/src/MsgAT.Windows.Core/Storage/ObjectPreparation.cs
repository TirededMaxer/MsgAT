// SPDX-License-Identifier: AGPL-3.0-only
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using MsgAT.Windows.Core.Crypto;

namespace MsgAT.Windows.Core.Storage;

public enum PreparationSendResult { Queued,Cancelled,Failed }
public enum PreparationStep { Importing, EncodingPhoto, EncodingVideo }

public sealed record PreparationProgress(string Phase,long Completed,long Total,bool CanCancel,long Version=0,int Attachment=1,int AttachmentCount=1,PreparationStep Step=PreparationStep.Importing);

/// <summary>One send preparation. The operation owner disposes it after staged cleanup has drained.</summary>
public sealed class ObjectPreparation : IDisposable
{
    private readonly object _gate=new();
    private readonly CancellationTokenSource _cancellation = new();
    internal CancellationToken Cancellation => _cancellation.Token;
    private string? _work;
    private string _phase="preparing";
    private bool _cancelled;
    private long _completed,_total,_version;
    private int _attachment=1,_attachmentCount=1;
    private PreparationStep _step;
    public bool WasCancelled {get{lock(_gate)return _cancelled;}}
    internal void Check(){lock(_gate){if(_cancelled)throw new OperationCanceledException("Preparation cancelled.");ObjectDisposedException.ThrowIf(_phase=="finished",this);}}
    internal string WorkId()
    {
        lock(_gate){Check();return _work??=SecurityCore.Call(new{operation="create_object_work"}).GetProperty("work_id").GetString()!;}
    }
    internal void Report(long total,long completed){lock(_gate){Check();SecurityCore.Call(new{operation="report_object_work",work_id=WorkId(),total,completed});}}
    internal void StartAttachment(int index, int count)
    {
        lock (_gate)
        {
            Check();
            _attachment = index;
            _attachmentCount = count;
            SetStep(PreparationStep.Importing);
        }
    }
    internal long SetStep(PreparationStep step)
    {
        lock (_gate)
        {
            Report(0, 0);
            _step = step;
            return ++_version;
        }
    }
    internal void ReportVideoProgress(long version, int percent)
    {
        lock (_gate)
        {
            if (_version == version && _step == PreparationStep.EncodingVideo && _phase == "preparing")
                Report(100, percent);
        }
    }
    internal void BeginCommit(){lock(_gate){Check();if(_phase!="preparing")throw new InvalidOperationException("Preparation has already entered commit.");_phase="committing";_version++;}}
    public bool Cancel()
    {
        lock(_gate)
        {
            if(_phase!="preparing")return false;_cancelled=true;_phase="canceling";_version++;
            _cancellation.Cancel();
            if(_work is not null){try{SecurityCore.Call(new{operation="cancel_object_work",work_id=_work});}catch(Exception error) when(error is CryptographicException or InvalidOperationException or IOException or JsonException){Trace.TraceWarning("Preparation cancel signal failed ({0}).",error.GetType().Name);}}
            return true;
        }
    }
    public PreparationProgress Snapshot()
    {
        lock(_gate)
        {
            if(_work is not null)try{var value=SecurityCore.Call(new{operation="object_work_status",work_id=_work});_total=value.GetProperty("total").GetInt64();_completed=Math.Clamp(value.GetProperty("completed").GetInt64(),0,_total);}catch(Exception error) when(error is CryptographicException or InvalidOperationException or IOException or JsonException or KeyNotFoundException){/* The worker reports actual failure; progress does not access the vault. */}
            return new PreparationProgress(_phase,_completed,_total,_phase=="preparing",_version,_attachment,_attachmentCount,_step);
        }
    }
    public void Dispose()
    {
        lock(_gate)
        {
            if(_phase=="finished")return;_phase="finished";_version++;
            _cancellation.Dispose();
            if(_work is not null){try{SecurityCore.Call(new{operation="close_object_work",work_id=_work});}catch(Exception error) when(error is CryptographicException or InvalidOperationException or IOException or JsonException){Trace.TraceWarning("Preparation release failed ({0}).",error.GetType().Name);}_work=null;}
        }
        GC.SuppressFinalize(this);
    }
}
