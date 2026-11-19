// SPDX-License-Identifier: AGPL-3.0-only
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using MsgAT.Windows.Core.Crypto;

namespace MsgAT.Windows.Core.Storage;

public sealed record StickerSourceEntry(int Id,string Name,int Size,int CompressedSize,int Method,uint? Crc32);
public sealed record StickerImageInfo(int Width,int Height,int Frames)
{
    public void Validate(){if(Width is <1 or >2048 || Height is <1 or >2048 || Frames is <1 or >300)throw new InvalidDataException("Sticker dimensions or frame count exceed their limits.");}
}
public sealed record StickerSourceImage(byte[] Bytes,string Mime,string Extension,string Sha256,StickerImageInfo Info)
{public void Clear()=>CryptographicOperations.ZeroMemory(Bytes);}

/// <summary>A bounded read-only source catalog. Disposing waits for readers before clearing native source bytes.</summary>
public sealed class StickerSource : IAsyncDisposable
{
    private readonly string _session;
    private readonly object _gate=new();
    private readonly SemaphoreSlim _slots=new(2);
    private readonly CancellationTokenSource _lifetime=new();
    private readonly HashSet<Task> _jobs=[];
    private bool _closed;
    private Task? _dispose;
    private StickerSource(string session,string format,IReadOnlyList<StickerSourceEntry> entries){_session=session;Format=format;Entries=entries;}
    public string Format {get;}
    public IReadOnlyList<StickerSourceEntry> Entries {get;}
    private static JsonObject Core(object request)=>JsonNode.Parse(SecurityCore.Call(request).GetRawText())!.AsObject();
    public static Task<StickerSource> OpenAsync(string path,CancellationToken token=default)=>Task.Run(()=>
    {
        token.ThrowIfCancellationRequested();var value=Core(new {operation="open_sticker_source",source=path});var session=value["session"]!.GetValue<string>();
        try
        {
            var entries=value["entries"]!.AsArray().Select(v=>new StickerSourceEntry(v!["id"]!.GetValue<int>(),v["name"]!.GetValue<string>(),v["size"]!.GetValue<int>(),v["compressed_size"]!.GetValue<int>(),v["method"]!.GetValue<int>(),v["crc32"]?.GetValue<uint>())).ToArray();
            token.ThrowIfCancellationRequested();return new StickerSource(session,value["format"]!.GetValue<string>(),entries);
        }
        catch {Core(new {operation="close_sticker_source",session});throw;}
    },token);
    public Task<StickerSourceImage> ReadAsync(StickerSourceEntry entry,CancellationToken token=default)
    {
        lock(_gate)
        {
            ObjectDisposedException.ThrowIf(_closed,this);
            if(!Entries.Contains(entry))throw new ArgumentException("The sticker belongs to another import.",nameof(entry));
            var task=ReadOwnedAsync(entry,token);_jobs.Add(task);return ObserveAsync(task);
        }
    }
    private async Task<StickerSourceImage> ObserveAsync(Task<StickerSourceImage> job)
    {try{return await job.ConfigureAwait(false);}finally{lock(_gate)_jobs.Remove(job);}}
    private async Task<StickerSourceImage> ReadOwnedAsync(StickerSourceEntry entry,CancellationToken token)
    {
        using var lifetime=CancellationTokenSource.CreateLinkedTokenSource(token,_lifetime.Token);
        await _slots.WaitAsync(lifetime.Token).ConfigureAwait(false);
        try {return await Task.Run(()=>ReadCore(entry,lifetime.Token),lifetime.Token).ConfigureAwait(false);}
        finally {_slots.Release();}
    }
    private StickerSourceImage ReadCore(StickerSourceEntry entry,CancellationToken token)
    {
        var packed=new byte[entry.CompressedSize];byte[]? image=null;var returned=false;var inspected=false;
        try
        {
            for(var offset=0;offset<packed.Length;)
            {
                token.ThrowIfCancellationRequested();var count=Math.Min(1024*1024,packed.Length-offset);
                var bytes=Convert.FromBase64String(Core(new {operation="read_sticker_source",session=_session,id=entry.Id,offset,length=count})["data"]!.GetValue<string>());
                try {if(bytes.Length!=count)throw new InvalidDataException("Truncated sticker source.");bytes.CopyTo(packed,offset);offset+=count;}
                finally {CryptographicOperations.ZeroMemory(bytes);}
            }
            if(entry.Method==0) {if(packed.Length!=entry.Size)throw new InvalidDataException("Sticker size mismatch.");image=packed;}
            else if(entry.Method==8)
            {
                image=new byte[entry.Size];using var input=new MemoryStream(packed,writable:false);using var inflate=new DeflateStream(input,CompressionMode.Decompress);
                inflate.ReadExactly(image);if(inflate.ReadByte()!=-1)throw new InvalidDataException("Sticker exceeds its declared size.");
            }
            else throw new InvalidDataException("Unsupported sticker compression.");
            JsonObject? identity=null;
            for(var offset=0;offset<image.Length;offset+=1024*1024)
            {
                token.ThrowIfCancellationRequested();var count=Math.Min(1024*1024,image.Length-offset);
                identity=Core(new {operation="inspect_sticker_chunk",session=_session,id=entry.Id,offset,bytes=Convert.ToBase64String(image,offset,count),finish=offset+count==image.Length});
            }
            if(identity is null)throw new InvalidDataException("Empty sticker image.");inspected=true;
            var xor=identity["xor"]!.GetValue<byte>();if(xor!=0)for(var i=0;i<image.Length;i++)image[i]^=xor;
            token.ThrowIfCancellationRequested();
            var result=new StickerSourceImage(image,identity["mime"]!.GetValue<string>(),identity["extension"]!.GetValue<string>(),identity["sha256"]!.GetValue<string>(),new StickerImageInfo(identity["width"]!.GetValue<int>(),identity["height"]!.GetValue<int>(),identity["frames"]!.GetValue<int>()));returned=true;return result;
        }
        finally {try {if(!inspected)Core(new {operation="discard_sticker_inspection",session=_session,id=entry.Id});}finally {if(!ReferenceEquals(packed,image) || !returned)CryptographicOperations.ZeroMemory(packed);if(!returned && image is not null && !ReferenceEquals(packed,image))CryptographicOperations.ZeroMemory(image);}}
    }
    public ValueTask DisposeAsync(){lock(_gate)return new(_dispose??=DisposeCoreAsync());}
    private async Task DisposeCoreAsync()
    {
        _closed=true;var jobs=_jobs.ToArray();await _lifetime.CancelAsync().ConfigureAwait(false);
        try {await Task.WhenAll(jobs).ConfigureAwait(false);}catch(Exception){ /* Every reader clears its own unsuccessful plaintext buffer. */ }
        finally {try {Core(new {operation="close_sticker_source",session=_session});}finally {_lifetime.Dispose();_slots.Dispose();}}
    }
}
