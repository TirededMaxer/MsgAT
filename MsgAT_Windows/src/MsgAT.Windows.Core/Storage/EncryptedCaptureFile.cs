// SPDX-License-Identifier: AGPL-3.0-only
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using MsgAT.Windows.Core.Crypto;

namespace MsgAT.Windows.Core.Storage;

/// <summary>Admission and encoder ownership remain live until the vault drains.</summary>
public sealed class EncryptedCaptureStore : IAsyncDisposable
{
    private readonly object _gate = new();
    private readonly HashSet<EncryptedCaptureFile> _files = [];
    private readonly EncryptedObjectStore _objects;
    private readonly string _directory;
    private bool _closed;
    internal EncryptedCaptureStore(EncryptedObjectStore objects,string directory) { _objects=objects;_directory=directory; }

    // stopWriter releases the encoder, camera/microphone and stream. It must not
    // recursively freeze/dispose the recording that is waiting for this callback.
    public async Task<EncryptedCaptureFile> OpenAsync(string filename,string mime,Func<Task> stopWriter,
        long capacity=100L*1024*1024,CancellationToken cancellationToken=default)
    {
        ArgumentNullException.ThrowIfNull(stopWriter);
        cancellationToken.ThrowIfCancellationRequested();
        var file=await Task.Run(() =>
        {
            lock(_gate)
            {
                ObjectDisposedException.ThrowIf(_closed,this);
                if(capacity is <=0 or >100L*1024*1024)throw new ArgumentOutOfRangeException(nameof(capacity));
                var candidate=_objects.RecordingCandidate(capacity,filename,mime);
                var id=candidate["object_id"]!.GetValue<string>();
                try
                {
                    var result=SecurityCore.Call(new {operation="open_capture_file",manifest=candidate,directory=_directory});
                    var created=new EncryptedCaptureFile(this,_objects,id,result.GetProperty("capture_id").GetString()!,filename,mime,capacity,stopWriter);
                    _files.Add(created);return created;
                }
                catch { _objects.DiscardRecording(id);throw; }
            }
        }).ConfigureAwait(false);
        if(cancellationToken.IsCancellationRequested)
        { await file.DisposeAsync().ConfigureAwait(false);cancellationToken.ThrowIfCancellationRequested(); }
        return file;
    }
    internal void Removed(EncryptedCaptureFile file) { lock(_gate)_files.Remove(file); }
    public async ValueTask DisposeAsync()
    {
        EncryptedCaptureFile[] active;
        lock(_gate) { _closed=true;active=_files.ToArray(); }
        List<Exception>? failures=null;
        foreach(var file in active)
            try { await file.ForceCloseAsync().ConfigureAwait(false); }
            catch(Exception error) { (failures??=[]).Add(error); }
        if(failures is not null)throw new AggregateException("Recording owners have not finished closing.",failures);
    }
}

public sealed class EncryptedCaptureFile : IAsyncDisposable
{
    private readonly object _io=new();
    private readonly EncryptedCaptureStore _store;
    private readonly EncryptedObjectStore _objects;
    private readonly string _objectId,_handle;
    private readonly Func<Task> _stopWriter;
    private Task? _stopped;
    private bool _nativeClosed,_cleaned,_draining,_frozen,_producerReleased;
    private int _owners=1;
    private long _length;
    public string Filename {get;}
    public string Mime {get;}
    public long Capacity {get;}
    public long Size {get {lock(_io){RequireOpen();return _length;}}}
    internal EncryptedCaptureFile(EncryptedCaptureStore store,EncryptedObjectStore objects,string objectId,string handle,
        string filename,string mime,long capacity,Func<Task> stopWriter)
    { _store=store;_objects=objects;_objectId=objectId;_handle=handle;Filename=filename;Mime=mime;Capacity=capacity;_stopWriter=stopWriter; }
    private void RequireOpen()=>ObjectDisposedException.ThrowIf(_nativeClosed,this);
    private JsonElement Call(string operation,JsonObject? body=null)
    { body??=new();body["operation"]=operation;body["capture_id"]=_handle;return SecurityCore.Call(body); }

    public void Write(long offset,ReadOnlySpan<byte> bytes)
    {
        lock(_io)
        {
            RequireOpen();if(_frozen)throw new InvalidOperationException("The recording is read-only.");
            if(offset<0 || offset>Capacity-bytes.Length)throw new ArgumentOutOfRangeException(nameof(offset));
            for(var done=0;done<bytes.Length;)
            {
                var count=Math.Min(256*1024,bytes.Length-done);
                var result=Call("write_capture_file",new(){["offset"]=offset+done,["bytes"]=Convert.ToBase64String(bytes.Slice(done,count))});
                if(result.GetProperty("written").GetInt32()!=count)throw new IOException("Incomplete recording write.");
                _length=result.GetProperty("size").GetInt64();done+=count;
            }
        }
    }
    public byte[] Read(long offset,int count)
    {
        lock(_io)
        {
            RequireOpen();if(offset<0 || count is <0 or >256*1024)throw new ArgumentOutOfRangeException(nameof(count));
            var result=Call("read_capture_file",new(){["offset"]=offset,["length"]=count});
            var bytes=result.GetProperty("bytes").GetBytesFromBase64();
            if(bytes.Length!=result.GetProperty("length").GetInt32())
            {CryptographicOperations.ZeroMemory(bytes);throw new IOException("Incomplete recording read.");}
            return bytes;
        }
    }
    public void Sync() { lock(_io){RequireOpen();Call("sync_capture_file");} }
    public void Resize(long size)
    {
        lock(_io)
        {
            RequireOpen();if(_frozen)throw new InvalidOperationException("The recording is read-only.");
            if(size<0 || size>Capacity)throw new ArgumentOutOfRangeException(nameof(size));
            _length=Call("resize_capture_file",new(){["size"]=size}).GetProperty("size").GetInt64();
        }
    }
    // The encoder owner must stop the SDK and close this adapter before freeze.
    public Stream OpenEncoderStream()
    {lock(_io){RequireOpen();if(_frozen || _draining)throw new InvalidOperationException("The recording is not writable.");return new RecordingWriteStream(this);}}
    private async Task StopHardwareAsync()
    {
        Task task;
        lock(_io)task=_stopped??=Task.Run(_stopWriter);
        try { await task.ConfigureAwait(false); }
        catch {lock(_io){if(ReferenceEquals(_stopped,task))_stopped=null;}throw;}
    }
    public async Task<long> FreezeAsync()
    {
        lock(_io){RequireOpen();if(_draining)throw new InvalidOperationException("Recording storage is closing.");}
        await StopHardwareAsync().ConfigureAwait(false);
        return await Task.Run(() =>
        {
            lock(_io)
            {
                RequireOpen();if(_draining)throw new InvalidOperationException("Recording storage is closing.");
                _length=Call("freeze_capture_file").GetProperty("size").GetInt64();_frozen=true;return _length;
            }
        }).ConfigureAwait(false);
    }
    public IDisposable RetainFrozen()
    {
        lock(_io){RequireOpen();if(!_frozen || _draining)throw new InvalidOperationException("Finish the recording before reading it.");_owners++;}
        return new ReadLease(this);
    }
    public Stream OpenRead()=>new RecordingReadStream(this,RetainFrozen());
    internal JsonObject ImportTo(string directory,JsonObject manifest,ObjectPreparation? preparation)
    {
        lock(_io)
        {
            RequireOpen();if(!_frozen || _draining)throw new InvalidOperationException("Finish the recording before importing it.");
            var result=SecurityCore.Call(new {operation="import_capture_file",capture_id=_handle,manifest,directory,
                maximum_size=100L*1024*1024,work_id=preparation?.WorkId()});
            return JsonNode.Parse(result.GetProperty("manifest").GetRawText())!.AsObject();
        }
    }
    private void CleanupLocked()
    {
        if(_cleaned)return;
        if(!_nativeClosed){Call("close_capture_file");_nativeClosed=true;}
        _objects.DiscardRecording(_objectId);_cleaned=true;_store.Removed(this);
    }
    private void ReleaseReader()
    { lock(_io){if(_owners<=0)throw new InvalidOperationException("Invalid recording reader ownership.");_owners--;if(_owners==0)CleanupLocked();} }
    public async ValueTask DisposeAsync()
    {
        await StopHardwareAsync().ConfigureAwait(false);
        await Task.Run(() => {lock(_io){if(!_producerReleased){_producerReleased=true;_owners--;}if(_owners==0)CleanupLocked();}}).ConfigureAwait(false);
    }
    internal async Task ForceCloseAsync()
    {
        lock(_io)_draining=true;
        await StopHardwareAsync().ConfigureAwait(false);
        await Task.Run(() => {lock(_io)CleanupLocked();}).ConfigureAwait(false);
    }
    private sealed class ReadLease(EncryptedCaptureFile owner):IDisposable
    {
        private EncryptedCaptureFile? _owner=owner;
        public void Dispose()=>Interlocked.Exchange(ref _owner,null)?.ReleaseReader();
    }
    private sealed class RecordingReadStream(EncryptedCaptureFile owner,IDisposable lease):Stream
    {
        private IDisposable? _lease=lease;
        private long _position;
        public override bool CanRead=>_lease is not null;
        public override bool CanSeek=>_lease is not null;
        public override bool CanWrite=>false;
        public override long Length {get{ObjectDisposedException.ThrowIf(_lease is null,this);return owner.Size;}}
        public override long Position {get=>_position;set=>Seek(value,SeekOrigin.Begin);}
        public override int Read(byte[] buffer,int offset,int count)=>Read(buffer.AsSpan(offset,count));
        public override int Read(Span<byte> buffer)
        {
            ObjectDisposedException.ThrowIf(_lease is null,this);
            if(buffer.Length==0)return 0;
            var bytes=owner.Read(_position,Math.Min(buffer.Length,256*1024));
            try{bytes.CopyTo(buffer);_position+=bytes.Length;return bytes.Length;}
            finally{CryptographicOperations.ZeroMemory(bytes);}
        }
        public override long Seek(long offset,SeekOrigin origin)
        {
            ObjectDisposedException.ThrowIf(_lease is null,this);
            var next=checked((origin switch {SeekOrigin.Begin=>0,SeekOrigin.Current=>_position,SeekOrigin.End=>Length,_=>throw new ArgumentOutOfRangeException(nameof(origin))})+offset);
            if(next<0)throw new IOException("Invalid recording position.");return _position=next;
        }
        public override void Flush() { }
        public override void SetLength(long value)=>throw new NotSupportedException();
        public override void Write(byte[] buffer,int offset,int count)=>throw new NotSupportedException();
        protected override void Dispose(bool disposing){if(disposing)Interlocked.Exchange(ref _lease,null)?.Dispose();base.Dispose(disposing);}
    }
    private sealed class RecordingWriteStream(EncryptedCaptureFile owner):Stream
    {
        private bool _closed;
        private long _position;
        public override bool CanRead=>!_closed;
        public override bool CanSeek=>!_closed;
        public override bool CanWrite=>!_closed;
        public override long Length {get{RequireOpen();return owner.Size;}}
        public override long Position {get=>_position;set=>Seek(value,SeekOrigin.Begin);}
        private void RequireOpen()=>ObjectDisposedException.ThrowIf(_closed,this);
        public override int Read(byte[] buffer,int offset,int count)=>Read(buffer.AsSpan(offset,count));
        public override int Read(Span<byte> buffer)
        {
            RequireOpen();if(buffer.Length==0)return 0;
            var bytes=owner.Read(_position,Math.Min(buffer.Length,256*1024));
            try{bytes.CopyTo(buffer);_position+=bytes.Length;return bytes.Length;}
            finally{CryptographicOperations.ZeroMemory(bytes);}
        }
        public override void Write(byte[] buffer,int offset,int count)=>Write(buffer.AsSpan(offset,count));
        public override void Write(ReadOnlySpan<byte> buffer){RequireOpen();owner.Write(_position,buffer);_position=checked(_position+buffer.Length);}
        public override long Seek(long offset,SeekOrigin origin)
        {
            RequireOpen();var next=checked((origin switch{SeekOrigin.Begin=>0,SeekOrigin.Current=>_position,SeekOrigin.End=>Length,_=>throw new ArgumentOutOfRangeException(nameof(origin))})+offset);
            if(next<0 || next>owner.Capacity)throw new IOException("Invalid recording position.");return _position=next;
        }
        public override void SetLength(long value){RequireOpen();owner.Resize(value);if(_position>value)_position=value;}
        public override void Flush(){RequireOpen();owner.Sync();}
        protected override void Dispose(bool disposing){_closed=true;base.Dispose(disposing);}
    }
}
