// SPDX-License-Identifier: AGPL-3.0-only
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using MsgAT.Windows.Core.Crypto;

namespace MsgAT.Windows.Core.Storage;

public sealed record AlbumPosition(string Id, int Index, int Count);
public enum AttachmentDownloadIntent { Automatic, Requested, Paused }
public sealed record AttachmentMetadata(string Filename,string Mime,long Size,bool Available,string Caption,bool IsSticker=false,
    AlbumPosition? Album=null,bool RemoteUnavailable=false,AttachmentDownloadIntent DownloadIntent=AttachmentDownloadIntent.Automatic,
    bool HasPendingDownload=false);
public enum SharedMediaCategory { Photos, Videos, Audio, Files, Links, Media }
public sealed record SharedMediaCursor(long Timestamp, string MessageId);
public sealed record SharedMediaItem(string Id, long Timestamp, bool IsMine, string Reference, AttachmentMetadata Attachment);
public sealed record VisualMediaNeighbors(SharedMediaItem? Newer, SharedMediaItem? Older);
public sealed record SharedLink(string Id, long Timestamp, IReadOnlyList<string> Urls)
{
    public string Text => string.Join("\n", Urls);
}
public sealed record SharedMediaPage(IReadOnlyList<SharedMediaItem> Items, SharedMediaCursor? Older)
{
    public IReadOnlyList<SharedLink> Links { get; init; } = [];
}

public sealed class InsufficientObjectSpaceException : IOException
{public InsufficientObjectSpaceException():base("Not enough free storage to prepare encrypted attachments.") {}}

/// <summary>Ciphertext-only object files; manifests and keys belong to SQLCipher.</summary>
public sealed partial class EncryptedObjectStore
{
    public const int ChunkBytes = 65_552;
    public const string ReferencePrefix = "msgat-object://";
    private readonly SecureSettingsStore _settings;
    private readonly EncryptedDatabase _database;
    private readonly string _directory;
    private readonly object _thumbnailGate = new();
    private bool _thumbnailsClosing;
    private int _thumbnailWorkers;
    private TaskCompletionSource? _thumbnailsDrained;
    public EncryptedCaptureStore Captures { get; }

    public EncryptedObjectStore(string dataDirectory, SecureSettingsStore settings, EncryptedDatabase database)
    {
        _settings = settings; _database = database;
        _directory = Path.Combine(dataDirectory, "objects", "v2");
        _database.ProtocolTransaction(db => {foreach(var sql in LifecycleSchema)db.Execute(sql);return true;});
        Captures=new EncryptedCaptureStore(this,_directory);
    }

    internal JsonObject RecordingCandidate(long size,string filename,string mime)
    {
        var value=_settings.WithRootSeed(seed=>SecurityCore.Call(new {operation="create_object",seed=Convert.ToBase64String(seed),size}));
        var candidate=JsonNode.Parse(value.GetRawText())!.AsObject();
        candidate["filename"]=filename;candidate["mime"]=mime;
        candidate["sha256"]=new string('0',64);candidate["cipher_sha256"]=new string('0',64);
        Stage(candidate["object_id"]!.GetValue<string>(),candidate["cipher_size"]!.GetValue<long>());
        return candidate;
    }
    internal void DiscardRecording(string id)=>DiscardStaged(id);
    public Task<JsonObject> ImportRecordingAsync(EncryptedCaptureFile source,CancellationToken token=default,ObjectPreparation? preparation=null,bool asFile=false)=>Task.Run(() =>
    {
        token.ThrowIfCancellationRequested();preparation?.Check();
        using var sourceLease=source.RetainFrozen();
        using var registration=token.Register(()=>preparation?.Cancel());
        var candidate=RecordingCandidate(source.Size,source.Filename,asFile?"application/octet-stream":source.Mime);
        try
        {
            var manifest=source.ImportTo(_directory,candidate,preparation);
            token.ThrowIfCancellationRequested();preparation?.Check();return manifest;
        }
        catch {DiscardCopy(candidate);throw;}
    },token);

    public Task<JsonObject> ImportAsync(string source, string mime, CancellationToken token = default,ObjectPreparation? preparation=null) => Task.Run(() =>
    {
        token.ThrowIfCancellationRequested();preparation?.Check();
        using var registration=token.Register(()=>preparation?.Cancel());
        var size = new FileInfo(source).Length;
        if (size > MediaStore.MaximumFileSize) throw new InvalidDataException("Attachment exceeds the file size limit.");
        var created = _settings.WithRootSeed(seed => SecurityCore.Call(new
        {
            operation = "create_object", seed = Convert.ToBase64String(seed), size
        }));
        var metadata = JsonNode.Parse(created.GetRawText())!.AsObject();
        metadata["filename"] = Path.GetFileName(source); metadata["mime"] = mime;
        metadata["sha256"] = new string('0',64); metadata["cipher_sha256"] = new string('0',64);
        var id=metadata["object_id"]!.GetValue<string>();Stage(id,metadata["cipher_size"]!.GetValue<long>());
        try
        {
            var result=SecurityCore.Call(new {operation="import_object_file",manifest=metadata,source,directory=_directory,maximum_size=MediaStore.MaximumFileSize,work_id=preparation?.WorkId()});
            var manifest=JsonNode.Parse(result.GetProperty("manifest").GetRawText())!.AsObject();
            token.ThrowIfCancellationRequested();preparation?.Check();
            return manifest;
        }
        catch {DiscardStaged(id);throw;}
    }, token);

    public Task<JsonObject> ImportBytesAsync(ReadOnlyMemory<byte> bytes,string filename,string mime,CancellationToken token=default,ObjectPreparation? preparation=null) => Task.Run(()=>
    {
        if(bytes.Length>MediaStore.MaximumFileSize) throw new InvalidDataException("Attachment exceeds the file size limit.");
        token.ThrowIfCancellationRequested();preparation?.Check();
        using var registration=token.Register(()=>preparation?.Cancel());
        var created=_settings.WithRootSeed(seed=>SecurityCore.Call(new { operation="create_object",seed=Convert.ToBase64String(seed),size=bytes.Length }));
        var manifest=JsonNode.Parse(created.GetRawText())!.AsObject();
        manifest["filename"]=filename; manifest["mime"]=mime;
        manifest["sha256"]=new string('0',64); manifest["cipher_sha256"]=new string('0',64);
        SecurityCore.Call(new { operation="validate_object_manifest",manifest });
        var id=manifest["object_id"]!.GetValue<string>(); var target=PathFor(id);
        Stage(id,manifest["cipher_size"]!.GetValue<long>());
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            using var plain=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            using var encrypted=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            using(var output=new FileStream(target,FileMode.CreateNew,FileAccess.Write,FileShare.None))
            {
                preparation?.Report(bytes.Length,0);
                var chunks=manifest["chunk_count"]!.GetValue<int>();
                for(var index=0;index<chunks;index++)
                {
                    token.ThrowIfCancellationRequested();preparation?.Check();
                    var offset=index*65536; var count=Math.Min(65536,bytes.Length-offset);
                    plain.AppendData(bytes.Span.Slice(offset,count));
                    var result=SecurityCore.Call(new { operation="seal_object_chunk",chunk=new {
                        key=manifest["key"]!.GetValue<string>(),object_id=id,size=bytes.Length,index,
                        data=Convert.ToBase64String(bytes.Span.Slice(offset,count)) } });
                    var data=result.GetProperty("data").GetBytesFromBase64();
                    try { encrypted.AppendData(data); output.Write(data); }
                    finally { CryptographicOperations.ZeroMemory(data); }
                    preparation?.Report(bytes.Length,Math.Min(bytes.Length,(index+1L)*65536));
                }
                preparation?.Check();
                output.Flush(flushToDisk:true);
                preparation?.Check();
            }
            manifest["sha256"]=Convert.ToHexStringLower(plain.GetHashAndReset());
            manifest["cipher_sha256"]=Convert.ToHexStringLower(encrypted.GetHashAndReset());
            token.ThrowIfCancellationRequested();preparation?.Check();
            return manifest;
        }
        catch {DiscardStaged(id);throw;}
    },token);

    internal static void Remember(JsonObject candidate, VaultTransaction db, bool available = false)
    {
        var result = SecurityCore.Call(new { operation = "validate_object_manifest", manifest = candidate });
        var manifest = JsonNode.Parse(result.GetProperty("manifest").GetRawText())!.AsObject();
        var id = manifest["object_id"]!.GetValue<string>();
        var encoded = SignalProtocolStore.Canonical(manifest);
        var previous = db.Text("SELECT manifest_json FROM media_objects WHERE object_id=$p0", id);
        if (previous is not null && previous != encoded) throw new InvalidDataException("Conflicting encrypted object identity.");
        db.Execute("INSERT INTO media_objects(object_id,manifest_json,local_state) VALUES($p0,$p1,$p2) ON CONFLICT(object_id) DO UPDATE SET local_state=MAX(local_state,excluded.local_state)", id, encoded, available ? 1 : 0);
        if(available)db.Execute("DELETE FROM media_staging WHERE object_id=$p0",id);
    }

    // The caller owns this new file until its manifest and message/saved item commit together.
    internal Task<JsonObject> CopyAsync(JsonObject source,CancellationToken token=default,ObjectPreparation? preparation=null) => Task.Run(()=>
    {
        token.ThrowIfCancellationRequested();preparation?.Check();
        using var registration=token.Register(()=>preparation?.Cancel());
        var original=SecurityCore.Call(new {operation="validate_object_manifest",manifest=source});
        var valid=JsonNode.Parse(original.GetProperty("manifest").GetRawText())!.AsObject();
        var created=_settings.WithRootSeed(seed=>SecurityCore.Call(new {operation="create_object",seed=Convert.ToBase64String(seed),size=valid["size"]!.GetValue<long>()}));
        var candidate=JsonNode.Parse(created.GetRawText())!.AsObject();
        candidate["filename"]=valid["filename"]!.DeepClone();candidate["mime"]=valid["mime"]!.DeepClone();
        candidate["sha256"]=new string('0',64);candidate["cipher_sha256"]=new string('0',64);
        var id=candidate["object_id"]!.GetValue<string>();Stage(id,candidate["cipher_size"]!.GetValue<long>());
        try {
            var result=SecurityCore.Call(new {operation="rekey_object_file",source=valid,manifest=candidate,directory=_directory,maximum_size=MediaStore.MaximumFileSize,work_id=preparation?.WorkId()});
            var manifest=JsonNode.Parse(result.GetProperty("manifest").GetRawText())!.AsObject();
            token.ThrowIfCancellationRequested();preparation?.Check();return manifest;
        } catch {DiscardStaged(id);throw;}
    },token);

    internal void DiscardCopy(JsonObject manifest)=>DiscardStaged(manifest["object_id"]!.GetValue<string>());
    internal void RequireFreeSpace(long cipherBytes)
    {
        if(cipherBytes==0)return;
        if(cipherBytes<0)throw new InvalidDataException("Invalid encrypted storage budget.");
        const long reserve=256L*1024*1024;
        if(new DriveInfo(Path.GetPathRoot(_directory)!).AvailableFreeSpace<checked(cipherBytes+reserve))throw new InsufficientObjectSpaceException();
    }
    internal void RequireDownloadSpace(JsonObject manifest) => RequireFreeSpace(RemainingDownloadBytes(manifest));

    internal long RemainingDownloadBytes(JsonObject manifest)
    {
        var expected=manifest["cipher_size"]!.GetValue<long>();var target=PathFor(manifest["object_id"]!.GetValue<string>());
        var complete = new FileInfo(target);
        if (complete.Exists && complete.Length == expected) return 0;
        var partial = new FileInfo(target + ".part");
        var length = partial.Exists ? partial.Length : 0;
        var retained = length > expected ? 0 : length - length % ChunkBytes;
        return expected - retained;
    }

    public bool HasCompleteCiphertext(string reference, CancellationToken token = default)
    {
        var id = Id(reference);
        var expected = _database.ProtocolTransaction(db => db.Query("""
            SELECT json_extract(manifest_json,'$.cipher_size'),json_extract(manifest_json,'$.cipher_sha256')
            FROM media_objects
            WHERE object_id=$p0 AND local_state=1
            """, row => (Size: row.GetInt64(0), Hash: row.GetString(1)), id).FirstOrDefault());
        if (expected.Hash is null) return false;
        var path = PathFor(id);
        try
        {
            const FileAttributes nonRegular = FileAttributes.Directory | FileAttributes.ReparsePoint;
            if ((File.GetAttributes(path) & nonRegular) != 0) throw new IOException("Invalid encrypted attachment file.");
            using var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read,
                64 * 1024, FileOptions.SequentialScan);
            if (input.Length != expected.Size) return false;
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            var buffer = new byte[64 * 1024];
            long remaining = expected.Size;
            while (remaining > 0)
            {
                token.ThrowIfCancellationRequested();
                var count = input.Read(buffer, 0, (int)Math.Min(remaining, buffer.Length));
                if (count == 0) return false;
                hash.AppendData(buffer.AsSpan(0, count));
                remaining -= count;
            }
            token.ThrowIfCancellationRequested();
            return input.ReadByte() == -1 &&
                CryptographicOperations.FixedTimeEquals(hash.GetHashAndReset(), Convert.FromHexString(expected.Hash));
        }
        catch (IOException error) when (error is FileNotFoundException or DirectoryNotFoundException)
        {
            return false;
        }
    }

    public IDisposable? RetainThumbnailWork()
    {
        lock (_thumbnailGate)
        {
            if (_thumbnailsClosing) return null;
            _thumbnailWorkers++;
            return new ThumbnailWorker(this);
        }
    }

    public Task CloseThumbnailWorkAsync()
    {
        lock (_thumbnailGate)
        {
            _thumbnailsClosing = true;
            if (_thumbnailWorkers == 0) return Task.CompletedTask;
            return (_thumbnailsDrained ??= new(TaskCreationOptions.RunContinuationsAsynchronously)).Task;
        }
    }

    private sealed class ThumbnailWorker(EncryptedObjectStore owner) : IDisposable
    {
        private EncryptedObjectStore? _owner = owner;

        public void Dispose()
        {
            var store = Interlocked.Exchange(ref _owner, null);
            if (store is null) return;
            lock (store._thumbnailGate)
            {
                if (--store._thumbnailWorkers == 0) store._thumbnailsDrained?.TrySetResult();
            }
        }
    }

    public byte[]? ReadThumbnail(string reference, int edge)
    {
        var id = Id(reference);
        var thumbnail = _database.ProtocolTransaction(db => db.Text("""
            SELECT t.thumbnail_object FROM media_thumbnail_cache t JOIN media_objects o ON o.object_id=t.object_id
            JOIN media_objects preview ON preview.object_id=t.thumbnail_object
            WHERE t.object_id=$p0 AND t.edge=$p1 AND o.local_state=1 AND preview.local_state=1
            AND json_extract(preview.manifest_json,'$.size') BETWEEN 1 AND 131072
            AND EXISTS(SELECT 1 FROM media_object_references r WHERE r.object_id=o.object_id)
            """, id, edge));
        if (thumbnail is null) return null;
        using var input = OpenRead(ReferencePrefix + thumbnail);
        var pixels = new byte[(int)input.Length];
        try { input.ReadExactly(pixels); return pixels; }
        catch { CryptographicOperations.ZeroMemory(pixels); throw; }
    }

    public async Task CacheThumbnailAsync(string reference, int edge, byte[] encoded, string mime, CancellationToken token)
    {
        if (edge is < 1 or > 640 || encoded.Length is < 1 or > 131_072 || mime is not ("image/jpeg" or "image/png")) return;
        var id = Id(reference);
        var filename = mime == "image/jpeg" ? "thumbnail.jpg" : "thumbnail.png";
        var staged = await ImportBytesAsync(encoded, filename, mime, token).ConfigureAwait(false);
        var committed = false;
        try
        {
            committed = _database.ProtocolTransaction(db =>
            {
                token.ThrowIfCancellationRequested();
                if (!db.Exists("""
                    SELECT 1 FROM media_objects o WHERE o.object_id=$p0 AND o.local_state=1
                    AND EXISTS(SELECT 1 FROM media_object_references r WHERE r.object_id=o.object_id)
                    """, id)) return false;
                Remember(staged, db, available: true);
                db.Execute("""
                    INSERT INTO media_thumbnail_cache(object_id,edge,thumbnail_object) VALUES($p0,$p1,$p2)
                    ON CONFLICT(object_id,edge) DO UPDATE SET thumbnail_object=excluded.thumbnail_object
                    """, id, edge, staged["object_id"]!.GetValue<string>());
                return true;
            });
        }
        finally { if (!committed) DiscardCopy(staged); }
    }

    public Stream OpenRead(string reference)
    {
        var id = Id(reference);
        var manifest = _database.ProtocolTransaction(db =>
        {
            var value = db.Text("SELECT manifest_json FROM media_objects WHERE object_id=$p0 AND local_state=1", id)
                ?? throw new FileNotFoundException("The encrypted attachment has not been downloaded.");
            return JsonNode.Parse(value)!.AsObject();
        });
        return new ObjectReadStream(manifest, _directory);
    }

    public AttachmentMetadata? Metadata(string reference,string messageId)
    {
        if(!IsReference(reference)) return null;
        var id=Id(reference);
        return _database.ProtocolTransaction(db=>db.Query("""
            SELECT o.manifest_json,o.local_state,COALESCE(a.caption,s.body,''),
                   EXISTS(SELECT 1 FROM message_stickers WHERE message_id=$p1 UNION ALL SELECT 1 FROM saved_stickers WHERE id=$p1),
                   g.album_id,g.position,g.item_count,
                   COALESCE(t.download_unavailable,0),
                   CASE WHEN t.download_paused=1 THEN 2 WHEN t.download_requested=1 THEN 1 ELSE 0 END,t.event_id IS NOT NULL
            FROM media_objects o LEFT JOIN message_attachments a ON a.object_id=o.object_id AND a.message_id=$p1
            LEFT JOIN object_transfers t ON t.object_id=o.object_id AND t.event_id=$p1 AND t.direction=1 AND t.owner_kind='message'
            LEFT JOIN message_album_items g ON g.message_id=a.message_id
            LEFT JOIN saved_messages s ON s.object_id=o.object_id AND s.id=$p1 WHERE o.object_id=$p0
            """,r=>
        {
            using var value=JsonDocument.Parse(r.GetString(0));
            var root=value.RootElement;
            return new AttachmentMetadata(root.GetProperty("filename").GetString()!,root.GetProperty("mime").GetString()!,root.GetProperty("size").GetInt64(),r.GetInt64(1)==1,r.GetString(2),r.GetInt64(3)!=0,
                r.IsDBNull(4) ? null : new AlbumPosition(r.GetString(4), r.GetInt32(5), r.GetInt32(6)),r.GetInt64(7)!=0,
                (AttachmentDownloadIntent)r.GetInt32(8), r.GetInt64(9)!=0);
        },id,messageId).FirstOrDefault());
    }

    public byte[] ReadStickerBytes(string reference) {
        ValidateSticker(reference);var id=Id(reference);
        var encoded=_database.ProtocolTransaction(db=>db.Text("SELECT manifest_json FROM media_objects WHERE object_id=$p0 AND local_state=1",id))??throw new IOException("Sticker is unavailable.");
        using var document=JsonDocument.Parse(encoded);var value=document.RootElement;var size=value.GetProperty("size").GetInt32();if(size is <1 or >8*1024*1024)throw new IOException("Invalid sticker size.");
        var bytes=new byte[size];try {using var input=OpenRead(reference);input.ReadExactly(bytes);if(Convert.ToHexStringLower(SHA256.HashData(bytes))!=value.GetProperty("sha256").GetString())throw new IOException("Sticker content changed.");return bytes;}catch{CryptographicOperations.ZeroMemory(bytes);throw;}
    }
    public void ValidateSticker(string reference) {
        var id=Id(reference);var manifest=_database.ProtocolTransaction(db=>db.Text("SELECT manifest_json FROM media_objects WHERE object_id=$p0 AND local_state=1",id))??throw new IOException("Sticker is unavailable.");
        SecurityCore.Call(new {operation="inspect_sticker_object",manifest=JsonNode.Parse(manifest),directory=_directory});
    }
    private const string SharedMediaColumns = """
        SELECT message_id,created_at,is_mine,object_id,filename,mime,size,available,caption,
               EXISTS(SELECT 1 FROM object_transfers t WHERE t.object_id=media.object_id AND t.event_id=media.message_id
                      AND t.direction=1 AND t.download_unavailable=1),
               COALESCE((SELECT CASE WHEN t.download_paused=1 THEN 2 WHEN t.download_requested=1 THEN 1 ELSE 0 END
                      FROM object_transfers t WHERE t.object_id=media.object_id AND t.event_id=media.message_id AND t.direction=1),0)
        FROM conversation_media media
        """;

    private static string SharedMediaVisible(int timeParameter) => $"""
        NOT EXISTS (SELECT 1 FROM message_expiration e WHERE e.message_id=media.message_id AND e.expires_at<=$p{timeParameter})
        AND NOT EXISTS (SELECT 1 FROM conversation_retention r JOIN messages m ON m.messageId=media.message_id
            WHERE r.peer=m.conversationUuid AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p{timeParameter}-r.duration_ms)
        """;

    public SharedMediaPage SharedMedia(string peer, SharedMediaCategory category, SharedMediaCursor? before, string query = "",
        CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (category == SharedMediaCategory.Links) return SharedLinks(peer, before, query, cancellationToken);
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var metadata = new List<object?> { peer, query, now, before?.Timestamp, before?.MessageId };
        var categoryFilter = "category IN ('photos','videos')";
        if (category != SharedMediaCategory.Media)
        {
            categoryFilter = "category=$p5";
            metadata.Add(category.ToString().ToLowerInvariant());
        }
        var rows = _database.ProtocolTransaction(db => db.Query(SharedMediaColumns + $"""

             WHERE peer=$p0 AND {categoryFilter}
             AND ($p1='' OR instr(lower(filename),lower($p1))>0)
             AND {SharedMediaVisible(2)}
             AND ($p3 IS NULL OR (created_at,message_id)<($p3,$p4))
             ORDER BY created_at DESC,message_id DESC LIMIT 41
            """, ReadSharedMedia, metadata.ToArray()));
        var items = rows.Take(40).ToArray();
        var older = rows.Count > items.Length ? new SharedMediaCursor(items[^1].Timestamp, items[^1].Id) : null;
        return new SharedMediaPage(items, older);
    }

    private SharedMediaPage SharedLinks(string peer, SharedMediaCursor? before, string query, CancellationToken cancellationToken)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var links = new List<SharedLink>(40);
        var after = before;
        SharedMediaCursor? lastMatch = null;
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var rows = _database.ProtocolTransaction(db => db.Query("""
            SELECT m.messageId,m.timestamp,CASE WHEN m.ext='txt' THEN m.content ELSE a.caption END AS body
            FROM messages m LEFT JOIN message_attachments a ON a.message_id=m.messageId AND m.ext='attachment'
            LEFT JOIN message_expiration e ON e.message_id=m.messageId
            WHERE m.conversationUuid=$p0 AND m.isRevoked=0 AND (e.expires_at IS NULL OR e.expires_at>$p1)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p1-r.duration_ms)
              AND (m.ext='txt' OR a.message_id IS NOT NULL)
              AND (instr(lower(body),'https://')>0 OR instr(lower(body),'http://')>0 OR instr(lower(body),'www.')>0)
              AND ($p2 IS NULL OR m.timestamp<$p2 OR (m.timestamp=$p2 AND m.messageId<$p3))
            ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 80
            """, row => (Cursor: new SharedMediaCursor(row.GetInt64(1), row.GetString(0)), Body: row.GetString(2)),
                peer, now, after?.Timestamp, after?.MessageId));
            foreach (var (cursor, body) in rows)
            {
                cancellationToken.ThrowIfCancellationRequested();
                var result = SecurityCore.Call(new { operation = "visible_message_urls", text = body });
                var urls = result.GetProperty("urls").EnumerateArray().Select(value => value.GetString()!)
                    .Where(url => url.Contains(query, StringComparison.OrdinalIgnoreCase)).ToArray();
                if (urls.Length == 0) continue;
                if (links.Count == 40) return new SharedMediaPage([], lastMatch) { Links = links };
                links.Add(new SharedLink(cursor.MessageId, cursor.Timestamp, urls));
                lastMatch = cursor;
            }
            if (rows.Count < 80) return new SharedMediaPage([], null) { Links = links };
            after = rows[^1].Cursor;
        }
    }

    public SharedMediaItem? SharedMediaItemAt(string peer, string messageId) =>
        _database.ProtocolTransaction(db => db.Query(SharedMediaColumns + " WHERE peer=$p0 AND message_id=$p1 AND " + SharedMediaVisible(2),
            ReadSharedMedia, peer, messageId, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()).FirstOrDefault());

    public VisualMediaNeighbors GetVisualMediaNeighbors(string peer, SharedMediaCursor anchor,
        SharedMediaCategory category = SharedMediaCategory.Media, string query = "", SharedMediaCursor? before = null) =>
        _database.ProtocolTransaction(db =>
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var categoryName = category.ToString().ToLowerInvariant();
            SharedMediaItem? Adjacent(bool newer)
            {
                var comparison = newer ? ">" : "<";
                var order = newer ? "ASC" : "DESC";
                var filter = $"""
                     WHERE peer=$p0 AND category IN ('photos','videos') AND available=1
                     AND ($p4='media' OR category=$p4) AND ($p5='' OR instr(lower(filename),lower($p5))>0)
                     AND ($p6 IS NULL OR (created_at,message_id)<($p6,$p7))
                     AND {SharedMediaVisible(1)}
                     AND (created_at{comparison}$p2 OR (created_at=$p2 AND message_id{comparison}$p3))
                     ORDER BY created_at {order},message_id {order} LIMIT 1
                    """;
                return db.Query(SharedMediaColumns + filter, ReadSharedMedia, peer, now, anchor.Timestamp, anchor.MessageId,
                    categoryName, query, before?.Timestamp, before?.MessageId).FirstOrDefault();
            }
            return new VisualMediaNeighbors(Adjacent(newer: true), Adjacent(newer: false));
        });

    private static SharedMediaItem ReadSharedMedia(Microsoft.Data.Sqlite.SqliteDataReader row) =>
        new(row.GetString(0), row.GetInt64(1), row.GetInt64(2) != 0, Reference(row.GetString(3)),
            new AttachmentMetadata(row.GetString(4), row.GetString(5), row.GetInt64(6), row.GetInt64(7) == 1, row.GetString(8),
                RemoteUnavailable: row.GetInt64(9)!=0, DownloadIntent: (AttachmentDownloadIntent)row.GetInt32(10)));

    public static bool IsAlbumMedia(string mime) =>
        mime.StartsWith("image/", StringComparison.Ordinal) || mime.StartsWith("video/", StringComparison.Ordinal);

    public static string MimeForPath(string path)=>Path.GetExtension(path).ToLowerInvariant() switch
    {
        ".jpg" or ".jpeg"=>"image/jpeg", ".png"=>"image/png", ".webp"=>"image/webp", ".gif"=>"image/gif",
        ".bmp"=>"image/bmp", ".tif" or ".tiff"=>"image/tiff", ".avif"=>"image/avif",
        ".mp4" or ".m4v"=>"video/mp4", ".mov"=>"video/quicktime", ".webm"=>"video/webm",
        ".mp3"=>"audio/mpeg", ".m4a"=>"audio/mp4", ".ogg" or ".opus"=>"audio/ogg", ".wav"=>"audio/wav", ".flac"=>"audio/flac",
        ".pdf"=>"application/pdf", ".zip"=>"application/zip", ".txt"=>"text/plain", _=>"application/octet-stream"
    };

    internal string PathFor(string id)
    {
        ValidateId(id);
        return Path.Combine(_directory, id[..2], id[2..] + ".obj");
    }

    public static bool IsReference(string value) => value.StartsWith(ReferencePrefix, StringComparison.Ordinal);
    public static string Reference(string id) { ValidateId(id); return ReferencePrefix + id; }
    private static string Id(string reference)
    {
        if (!IsReference(reference)) throw new InvalidDataException("Invalid encrypted object reference.");
        var id = reference[ReferencePrefix.Length..]; ValidateId(id); return id;
    }
    private static void ValidateId(string value)
    {
        if (value.Length != 64 || value.Any(c => c is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')))
            throw new InvalidDataException("Invalid encrypted object identity.");
    }

    private sealed class ObjectReadStream(JsonObject manifest, string directory) : Stream
    {
        private long _position;
        private bool _disposed;
        private byte[]? _cached;
        private long _cachedStart;
        public override bool CanRead => !_disposed;
        public override bool CanSeek => !_disposed;
        public override bool CanWrite => false;
        public override long Length => manifest["size"]!.GetValue<long>();
        public override long Position { get => _position; set { ObjectDisposedException.ThrowIf(_disposed, this); ArgumentOutOfRangeException.ThrowIfNegative(value); _position = value; } }
        public override int Read(byte[] buffer, int offset, int count) => Read(buffer.AsSpan(offset, count));
        public override int Read(Span<byte> buffer)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_position >= Length || buffer.IsEmpty) return 0;
            if (_cached is null || _position < _cachedStart || _position >= _cachedStart + _cached.Length)
            {
                ClearCache();
                _cachedStart = _position / 65536 * 65536;
                var length = (int)Math.Min(65536, Length - _cachedStart);
                var result = SecurityCore.Call(new { operation = "read_object_range", manifest, directory, offset = _cachedStart, length });
                _cached = result.GetProperty("data").GetBytesFromBase64();
                if (_cached.Length != length) { ClearCache(); throw new InvalidDataException("Incorrect decrypted object length."); }
            }
            var start = (int)(_position - _cachedStart);
            var count = Math.Min(buffer.Length, _cached.Length - start);
            _cached.AsSpan(start, count).CopyTo(buffer); _position += count;
            return count;
        }
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) =>
            new(Task.Run(() => Read(buffer.Span), cancellationToken));
        public override long Seek(long offset, SeekOrigin origin)
        {
            Position = checked((origin switch { SeekOrigin.Begin => 0, SeekOrigin.Current => _position,
                SeekOrigin.End => Length, _ => throw new ArgumentOutOfRangeException(nameof(origin)) }) + offset);
            return _position;
        }
        private void ClearCache() { if (_cached is not null) CryptographicOperations.ZeroMemory(_cached); _cached = null; }
        protected override void Dispose(bool disposing) { _disposed = true; ClearCache(); base.Dispose(disposing); }
        public override void Flush() { }
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
