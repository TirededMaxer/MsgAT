// SPDX-License-Identifier: AGPL-3.0-only
using System.Text;
using System.Text.Json.Nodes;

namespace MsgAT.Windows.Core.Storage;

public sealed record StickerPack(string Id,string Name,int Count);
public sealed record StickerItem(string Id,string Reference,string Sha256,string Mime,long Size,int Width,int Height,int Frames,string Label,bool Favorite,long AddedAt,long LastUsed);
public sealed record StickerSelection(StickerSourceEntry Entry,string? ExpectedSha256=null);
public sealed record StickerImportResult(string PackId,int Added,int Duplicates);
public sealed record StickerImportProgress(string Phase,int Done,int Total);
internal sealed record PreparedSticker(StickerSourceEntry Entry,StickerImageInfo Info,string Mime,string Sha256,JsonObject? Object,bool NeedsImport);

public sealed class StickerLibraryStore(EncryptedDatabase database,EncryptedObjectStore objects):IAsyncDisposable
{
    private readonly object _gate=new();private readonly SemaphoreSlim _mutations=new(1);private readonly CancellationTokenSource _lifetime=new();private readonly HashSet<Task> _jobs=[];private bool _closed;private Task? _dispose;
    private Task<T> Owned<T>(Func<CancellationToken,Task<T>> operation,CancellationToken token=default)
    {
        lock(_gate)
        {
            ObjectDisposedException.ThrowIf(_closed,this);
            var task=Task.Run(async()=>{using var linked=CancellationTokenSource.CreateLinkedTokenSource(token,_lifetime.Token);await _mutations.WaitAsync(linked.Token).ConfigureAwait(false);try{return await operation(linked.Token).ConfigureAwait(false);}finally{_mutations.Release();}});
            _jobs.Add(task);return Observe(task);
        }
    }
    private async Task<T> Observe<T>(Task<T> task){try{return await task.ConfigureAwait(false);}finally{lock(_gate)_jobs.Remove(task);}}
    public IReadOnlyList<StickerPack> Packs()=>database.ProtocolTransaction(db=>db.Query("SELECT p.id,p.name,COUNT(m.sticker_id) FROM sticker_packs p LEFT JOIN sticker_pack_items m ON m.pack_id=p.id GROUP BY p.id ORDER BY p.created_at,p.id",r=>new StickerPack(r.GetString(0),r.GetString(1),r.GetInt32(2))));
    public IReadOnlyList<StickerItem> List(string? pack = null, bool favorites = false, bool recent = false,
        string search = "", int offset = 0, bool animated = false) => database.ProtocolTransaction(db => db.Query("""
        SELECT s.id,s.object_id,s.sha256,s.mime,s.size,s.width,s.height,s.frames,s.label,s.favorite,s.added_at,s.last_used FROM sticker_items s
        WHERE ($p0 IS NULL OR EXISTS(SELECT 1 FROM sticker_pack_items m WHERE m.pack_id=$p0 AND m.sticker_id=s.id))
          AND ($p1=0 OR s.favorite=1) AND ($p2=0 OR s.last_used>0) AND ($p3='' OR instr(lower(s.label),lower($p3))>0)
          AND ($p5=0 OR s.frames>1)
        ORDER BY CASE WHEN $p2=1 THEN s.last_used ELSE s.added_at END DESC,s.id DESC LIMIT 60 OFFSET $p4
        """, Read, pack, favorites ? 1 : 0, recent ? 1 : 0, search, Math.Clamp(offset, 0, 2000), animated ? 1 : 0));
    private static StickerItem Read(Microsoft.Data.Sqlite.SqliteDataReader r)=>new(r.GetString(0),EncryptedObjectStore.Reference(r.GetString(1)),r.GetString(2),r.GetString(3),r.GetInt64(4),r.GetInt32(5),r.GetInt32(6),r.GetInt32(7),r.GetString(8),r.GetInt32(9)!=0,r.GetInt64(10),r.GetInt64(11));
    internal static (string Object,string Sha256)? Source(VaultTransaction db,string id)=>db.Query("SELECT o.manifest_json,s.sha256 FROM sticker_items s JOIN media_objects o ON o.object_id=s.object_id AND o.local_state=1 WHERE s.id=$p0",r=>(r.GetString(0),r.GetString(1)),id).Select(v=>((string Object,string Sha256)?)v).FirstOrDefault();
    public Task<StickerImportResult> ImportAsync(StickerSource source,IReadOnlyList<StickerSelection> selection,string packName,Func<byte[],StickerImageInfo> inspect,string? packId=null,IProgress<StickerImportProgress>? progress=null,CancellationToken token=default)
    {
        var selected=selection.ToArray();return Owned(async ct=>
        {
            packName=packName.Trim();if(selected.Length is <1 or >200 || selected.Select(v=>v.Entry.Id).Distinct().Count()!=selected.Length)throw new InvalidOperationException("Select between 1 and 200 different stickers.");
            if (packId is null && (packName.Length == 0 || Encoding.UTF8.GetByteCount(packName) > 128))
                throw new InvalidOperationException("Group name is empty or too long.");
            long budget=0;var processed=0;
            var prepared=new List<PreparedSticker>();var hashes=new HashSet<string>(StringComparer.Ordinal);var committed=false;var duplicates=0;
            try
            {
                foreach(var chosen in selected)
                {
                    ct.ThrowIfCancellationRequested();var image=await source.ReadAsync(chosen.Entry,ct).ConfigureAwait(false);
                    try
                    {
                        if(chosen.ExpectedSha256 is { } expected && image.Sha256!=expected)throw new IOException("A selected sticker changed; select the source again.");
                        var info=image.Info;info.Validate();var decoded=inspect(image.Bytes);decoded.Validate();if(decoded!=info)throw new IOException("Sticker decoder disagrees with its image dimensions or frame count.");
                        if(!hashes.Add(image.Sha256)){duplicates++;continue;}
                        var exists=database.ProtocolTransaction(db=>db.Exists("SELECT 1 FROM sticker_items WHERE sha256=$p0",image.Sha256));
                        prepared.Add(new(chosen.Entry,info,image.Mime,image.Sha256,null,!exists));
                        if(exists)duplicates++;else budget=checked(budget+image.Bytes.Length+((image.Bytes.Length+65535L)/65536)*16);
                    }
                    finally {image.Clear();progress?.Report(new("Scanning",++processed,selected.Length));}
                }
                objects.RequireFreeSpace(budget);
                for(var index=0;index<prepared.Count;index++)
                {
                    var row=prepared[index];ct.ThrowIfCancellationRequested();
                    if(row.NeedsImport)
                    {
                        var image=await source.ReadAsync(row.Entry,ct).ConfigureAwait(false);
                        try {
                            if(image.Sha256!=row.Sha256)throw new IOException("A selected sticker changed during preparation.");
                            prepared[index]=row with {Object=await objects.ImportBytesAsync(image.Bytes,"sticker."+image.Extension,image.Mime,ct).ConfigureAwait(false)};
                        } finally {image.Clear();}
                    }
                    progress?.Report(new("Encrypting",index+1,prepared.Count));
                }
                var result=database.ProtocolTransaction(db=>
                {
                    ct.ThrowIfCancellationRequested();
                    var now=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                    var existing=packId ?? db.Text("SELECT id FROM sticker_packs WHERE name=$p0 ORDER BY created_at,id LIMIT 1",packName);
                    var destination=existing ?? Guid.NewGuid().ToString("D");
                    if(existing is null)db.Execute("INSERT INTO sticker_packs(id,name,created_at) VALUES($p0,$p1,$p2)",destination,packName,now);
                    else if(!db.Exists("SELECT 1 FROM sticker_packs WHERE id=$p0",destination))throw new InvalidOperationException("The selected sticker group was deleted.");
                    var added=0;var position=db.Number("SELECT COALESCE(MAX(position),-1)+1 FROM sticker_pack_items WHERE pack_id=$p0",destination);
                    foreach(var row in prepared)
                    {
                        var id=db.Text("SELECT id FROM sticker_items WHERE sha256=$p0",row.Sha256);
                        if(id is null)
                        {
                            if(row.Object is null)throw new InvalidOperationException("A duplicate sticker changed during import.");
                            if(db.Number("SELECT COUNT(*) FROM sticker_items")>=2000 || db.Number("SELECT COALESCE(SUM(size),0) FROM sticker_items")+row.Entry.Size>2L*1024*1024*1024)throw new IOException("Sticker library is full. Remove unused stickers first.");
                            EncryptedObjectStore.Remember(row.Object,db,true);id=Guid.NewGuid().ToString("D");
                            db.Execute("INSERT INTO sticker_items(id,object_id,sha256,mime,size,width,height,frames,label,added_at) VALUES($p0,$p1,$p2,$p3,$p4,$p5,$p6,$p7,$p8,$p9)",id,row.Object["object_id"]!.GetValue<string>(),row.Sha256,row.Mime,row.Entry.Size,row.Info.Width,row.Info.Height,row.Info.Frames,Path.GetFileName(row.Entry.Name.Replace('\\','/')),now);added++;
                        }
                        db.Execute("INSERT OR IGNORE INTO sticker_pack_items(pack_id,sticker_id,position) VALUES($p0,$p1,$p2)",destination,id,position++);
                    }
                    return new StickerImportResult(destination,added,duplicates);
                },notify:true);
                committed=true;return result;
            }
            finally {if(!committed)foreach(var row in prepared)if(row.Object is not null)objects.DiscardCopy(row.Object);}
        },token);
    }
    public Task<bool> FavoriteAsync(string id,bool value,CancellationToken token=default)=>Mutate(db=>db.Execute("UPDATE sticker_items SET favorite=$p1 WHERE id=$p0",id,value?1:0),token);
    public Task<bool> DeleteAsync(string id,CancellationToken token=default)=>Mutate(db=>db.Execute("DELETE FROM sticker_items WHERE id=$p0",id),token);
    public Task<bool> RemoveFromPackAsync(string pack,string id,CancellationToken token=default)=>Mutate(db=>db.Execute("DELETE FROM sticker_pack_items WHERE pack_id=$p0 AND sticker_id=$p1",pack,id),token);
    public Task<bool> RenamePackAsync(string pack,string name,CancellationToken token=default)=>Mutate(db=>{name=name.Trim();if(name.Length==0 || Encoding.UTF8.GetByteCount(name)>128)throw new ArgumentException("Invalid group name.");return db.Execute("UPDATE sticker_packs SET name=$p1 WHERE id=$p0",pack,name);},token);
    public Task<bool> DeletePackAsync(string pack,CancellationToken token=default)=>Mutate(db=>db.Execute("DELETE FROM sticker_packs WHERE id=$p0",pack),token);
    private Task<bool> Mutate(Func<VaultTransaction,int> write,CancellationToken token)=>Owned(ct=>{ct.ThrowIfCancellationRequested();return Task.FromResult(database.ProtocolTransaction(write,notify:true)>0);},token);
    public ValueTask DisposeAsync(){lock(_gate)return new(_dispose??=DisposeCoreAsync());}
    private async Task DisposeCoreAsync(){_closed=true;var jobs=_jobs.ToArray();await _lifetime.CancelAsync().ConfigureAwait(false);try{await Task.WhenAll(jobs).ConfigureAwait(false);}catch(Exception){}finally{_mutations.Dispose();_lifetime.Dispose();}}
}
