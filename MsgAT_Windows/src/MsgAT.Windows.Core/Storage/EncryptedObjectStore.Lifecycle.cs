// SPDX-License-Identifier: AGPL-3.0-only
using MsgAT.Windows.Core.Crypto;

namespace MsgAT.Windows.Core.Storage;

public enum AttachmentCacheCategory { Photos, Videos, Audio, Files, LinkPreviews }

public sealed record AttachmentCacheUsage(
    IReadOnlyDictionary<AttachmentCacheCategory, long> BytesByCategory, long PendingBytes, long AutomaticLimitBytes,
    long AutomaticBudgetUsedBytes)
{
    public long Bytes => BytesByCategory.Values.Sum();
    public long AutomaticBudgetRemainingBytes => Math.Max(0, AutomaticLimitBytes - AutomaticBudgetUsedBytes);
}

public sealed partial class EncryptedObjectStore
{
    internal static readonly string[] LifecycleSchema=[
        "CREATE TABLE IF NOT EXISTS media_objects(object_id TEXT PRIMARY KEY NOT NULL,manifest_json TEXT NOT NULL,local_state INTEGER NOT NULL DEFAULT 0)",
        """
        CREATE TABLE IF NOT EXISTS media_thumbnail_cache(
            cache_id INTEGER PRIMARY KEY,
            object_id TEXT NOT NULL REFERENCES media_objects(object_id) ON DELETE CASCADE,
            thumbnail_object TEXT NOT NULL UNIQUE REFERENCES media_objects(object_id) ON DELETE CASCADE,
            edge INTEGER NOT NULL CHECK(edge BETWEEN 1 AND 640),
            CHECK(object_id<>thumbnail_object),
            UNIQUE(object_id,edge)
        )
        """,
        """
        CREATE TRIGGER IF NOT EXISTS media_thumbnail_evict
        AFTER UPDATE OF local_state ON media_objects WHEN NEW.local_state<>1
        BEGIN
            DELETE FROM media_thumbnail_cache WHERE object_id=NEW.object_id;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS media_thumbnail_trim
        AFTER INSERT ON media_thumbnail_cache
        BEGIN
            DELETE FROM media_thumbnail_cache WHERE cache_id IN (
                SELECT cache_id FROM media_thumbnail_cache ORDER BY cache_id DESC LIMIT -1 OFFSET 192
            );
        END
        """,
        "CREATE TABLE IF NOT EXISTS media_staging(object_id TEXT PRIMARY KEY NOT NULL,owner TEXT NOT NULL,created_at INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS media_garbage(object_id TEXT PRIMARY KEY NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_retry INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS media_cache_evictions(object_id TEXT PRIMARY KEY NOT NULL REFERENCES media_objects(object_id) ON DELETE CASCADE,file_pending INTEGER NOT NULL DEFAULT 1 CHECK(file_pending IN (0,1)),attempts INTEGER NOT NULL DEFAULT 0,next_retry INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX IF NOT EXISTS media_cache_evictions_due ON media_cache_evictions(file_pending,next_retry,object_id)",
        "CREATE INDEX IF NOT EXISTS media_garbage_due ON media_garbage(next_retry)",
        "CREATE TRIGGER IF NOT EXISTS media_objects_gc AFTER DELETE ON media_objects BEGIN INSERT OR IGNORE INTO media_garbage(object_id) VALUES(OLD.object_id); END"
    ];
    private string StagingOwner=>_database.StorageOwner;
    private readonly object _maintenanceGate=new();
    private bool _recoveredStaging;
    private bool _legacySweepComplete;
    private static long StorageNow=>DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    private void Stage(string id,long cipherBytes)
    {
        ValidateId(id);RequireFreeSpace(cipherBytes);
        _database.ProtocolTransaction(db=>
        {
            if(db.Exists("SELECT 1 FROM media_objects WHERE object_id=$p0",id))throw new InvalidOperationException("Encrypted object identity collision.");
            return db.Execute("INSERT INTO media_staging(object_id,owner,created_at) VALUES($p0,$p1,$p2)",id,StagingOwner,StorageNow);
        });
    }
    private void DiscardStaged(string id)
    {
        ValidateId(id);
        try {_database.ProtocolTransaction(db=>
        {
            if(db.Exists("SELECT 1 FROM media_objects WHERE object_id=$p0 UNION ALL SELECT 1 FROM media_object_references WHERE object_id=$p0",id))return false;
            // The staging row is already durable. Free the ciphertext before
            // attempting another journal write when a failed import filled disk.
            SecurityCore.Call(new {operation="remove_object_files",directory=_directory,object_id=id});
            db.Execute("DELETE FROM media_staging WHERE object_id=$p0 AND owner=$p1",id,StagingOwner);
            db.Execute("DELETE FROM media_garbage WHERE object_id=$p0",id);
            return true;
        });}
        catch(Exception error) when(error is IOException or UnauthorizedAccessException or InvalidOperationException or System.Security.Cryptography.CryptographicException)
        {
            _database.ProtocolTransaction(db=>
            {
                db.Execute("INSERT OR IGNORE INTO media_garbage(object_id,attempts,next_retry) VALUES($p0,1,$p1)",id,StorageNow+30000);
                return db.Execute("DELETE FROM media_staging WHERE object_id=$p0 AND owner=$p1",id,StagingOwner);
            });
        }
    }
    internal IDisposable? PinTransfer(string id, int direction, Func<VaultTransaction, bool> eligible)
    {
        var pinned=_database.ProtocolTransaction(db=>
        {
            if (!eligible(db)) return false;
            return db.Execute("""
                INSERT INTO object_transfer_pins(object_id,direction,owner) VALUES($p0,$p1,$p2)
                ON CONFLICT(object_id,direction) DO UPDATE SET owner=excluded.owner
                WHERE object_transfer_pins.owner<>excluded.owner
                """, id, direction, StagingOwner) > 0;
        });
        return pinned?new TransferPin(()=>_database.ProtocolTransaction(db=>db.Execute("DELETE FROM object_transfer_pins WHERE object_id=$p0 AND direction=$p1 AND owner=$p2",id,direction,StagingOwner))):null;
    }
    private sealed class TransferPin(Action release):IDisposable
    {private Action? _release=release;public void Dispose()=>Interlocked.Exchange(ref _release,null)?.Invoke();}
    private long _nextPageReclaim;
    internal int CollectGarbage()
    {
        lock(_maintenanceGate)
        {
            if(!_recoveredStaging)
            {
                _database.ProtocolTransaction(db=>
                {
                    db.Execute("INSERT OR IGNORE INTO media_garbage(object_id) SELECT object_id FROM media_staging WHERE owner<>$p0",StagingOwner);
                    db.Execute("DELETE FROM media_staging WHERE owner<>$p0",StagingOwner);
                    db.Execute("DELETE FROM object_transfer_pins WHERE owner<>$p0",StagingOwner);
                    return true;
                });
                _recoveredStaging=true;
            }
            if(!_legacySweepComplete)
            {
                var count=_database.ProtocolTransaction(db=>db.Execute("DELETE FROM media_objects WHERE object_id IN (SELECT o.object_id FROM media_objects o WHERE NOT EXISTS(SELECT 1 FROM media_object_references r WHERE r.object_id=o.object_id) AND NOT EXISTS(SELECT 1 FROM media_staging s WHERE s.object_id=o.object_id) LIMIT 64)"));
                _legacySweepComplete=count<64;
            }
            var cache = _database.ProtocolTransaction(db => db.Query("""
                SELECT object_id,attempts FROM media_cache_evictions
                WHERE file_pending=1 AND next_retry<=$p0 ORDER BY next_retry,object_id LIMIT 64
                """, row => (Id: row.GetString(0), Attempts: row.GetInt32(1)), StorageNow));
            foreach (var job in cache) TryEvictCache(job.Id, job.Attempts);
            var jobs=_database.ProtocolTransaction(db=>db.Query("SELECT object_id,attempts FROM media_garbage WHERE next_retry<=$p0 ORDER BY next_retry,object_id LIMIT 64",r=>(Id:r.GetString(0),Attempts:r.GetInt32(1)),StorageNow));
            foreach(var job in jobs)TryCollect(job.Id,job.Attempts);
            var elapsed = Environment.TickCount64;
            if (elapsed >= _nextPageReclaim)
            {
                _nextPageReclaim = elapsed + 30_000;
                try { _database.ReclaimUnusedPages(); }
                catch (Microsoft.Data.Sqlite.SqliteException error)
                {
                    System.Diagnostics.Trace.TraceWarning("Database page maintenance deferred ({0}).", error.GetType().Name);
                }
            }
            return jobs.Count + cache.Count;
        }
    }
    private void TryEvictCache(string id, int attempts)
    {
        try
        {
            _database.ProtocolTransaction(db =>
            {
                if (!db.Exists("SELECT 1 FROM media_cache_evictions WHERE object_id=$p0 AND file_pending=1", id)) return false;
                SecurityCore.Call(new { operation = "remove_object_files", directory = _directory, object_id = id });
                db.Execute("UPDATE media_cache_evictions SET file_pending=0,attempts=0,next_retry=0 WHERE object_id=$p0", id);
                return true;
            });
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException or System.Security.Cryptography.CryptographicException)
        {
            var delay = Math.Min(300_000L, 30_000L * (1L << Math.Clamp(attempts, 0, 4)));
            _database.ProtocolTransaction(db => db.Execute("""
                UPDATE media_cache_evictions SET attempts=MIN(attempts+1,16),next_retry=$p1 WHERE object_id=$p0
                """, id, StorageNow + delay));
        }
    }
    private void TryCollect(string id,int attempts)
    {
        try
        {
            _database.ProtocolTransaction(db=>
            {
                if(db.Exists("SELECT 1 FROM media_staging WHERE object_id=$p0",id))return false;
                if(!db.Exists("SELECT 1 FROM media_objects WHERE object_id=$p0 UNION ALL SELECT 1 FROM media_object_references WHERE object_id=$p0",id))
                    SecurityCore.Call(new {operation="remove_object_files",directory=_directory,object_id=id});
                // The native deletion is durable before its journal entry is removed.
                db.Execute("DELETE FROM media_garbage WHERE object_id=$p0",id);return true;
            });
        }
        catch(Exception error) when(error is IOException or UnauthorizedAccessException or InvalidOperationException or System.Security.Cryptography.CryptographicException)
        {
            var delay=Math.Min(300000L,30000L*(1L<<Math.Min(Math.Max(attempts,0),4)));
            _database.ProtocolTransaction(db=>db.Execute("UPDATE media_garbage SET attempts=MIN(attempts+1,16),next_retry=$p1 WHERE object_id=$p0",id,StorageNow+delay));
        }
    }
    private const string CacheSources = """
        SELECT message_id,object_id,0 AS is_preview FROM message_attachments
        UNION ALL SELECT owner_id AS message_id,image_object AS object_id,1 AS is_preview
          FROM message_previews WHERE owner_kind='message' AND image_object IS NOT NULL
        """;
    private static readonly string ReceivedCache = $"""
            o.local_state=1
            AND EXISTS(SELECT 1 FROM ({CacheSources}) a JOIN messages m ON m.messageId=a.message_id
                WHERE a.object_id=o.object_id AND m.isMine=0 AND m.isRevoked=0)
            AND NOT EXISTS(SELECT 1 FROM ({CacheSources}) a JOIN messages m ON m.messageId=a.message_id
                WHERE a.object_id=o.object_id AND m.isMine=1)
        """;
    private static readonly string CacheCandidate = $"""
            {ReceivedCache}
            AND NOT EXISTS(SELECT 1 FROM media_cache_evictions e WHERE e.object_id=o.object_id)
            AND NOT EXISTS(SELECT 1 FROM media_staging s WHERE s.object_id=o.object_id)
            AND (SELECT COUNT(*) FROM media_object_references r WHERE r.object_id=o.object_id)=
                (SELECT COUNT(*) FROM ({CacheSources}) a WHERE a.object_id=o.object_id)
        """;
    private const string CachedObjectBytes = """
        json_extract(o.manifest_json,'$.cipher_size') +
        (SELECT COALESCE(SUM(json_extract(preview.manifest_json,'$.cipher_size')),0)
         FROM media_thumbnail_cache t JOIN media_objects preview ON preview.object_id=t.thumbnail_object
         WHERE t.object_id=o.object_id AND preview.local_state=1)
        """;
    private static readonly string CategorizedCache = $"""
        SELECT o.object_id,{CachedObjectBytes} AS bytes,
            CASE
                WHEN NOT EXISTS(SELECT 1 FROM message_attachments a WHERE a.object_id=o.object_id) THEN 'LinkPreviews'
                WHEN json_extract(o.manifest_json,'$.mime') LIKE 'image/%' THEN 'Photos'
                WHEN json_extract(o.manifest_json,'$.mime') LIKE 'video/%' THEN 'Videos'
                WHEN json_extract(o.manifest_json,'$.mime') LIKE 'audio/%' THEN 'Audio'
                ELSE 'Files'
            END AS category
        FROM media_objects o WHERE {CacheCandidate}
        """;
    public static IReadOnlyList<long> AutomaticCacheLimits { get; } =
        Array.AsReadOnly(new long[] { 0, 512_000_000, 1_000_000_000, 2_000_000_000, 5_000_000_000 });
    private const string PendingCacheBytes = """
        (SELECT COALESCE(SUM(json_extract(p.manifest_json,'$.cipher_size')),0)
         FROM media_cache_evictions e JOIN media_objects p ON p.object_id=e.object_id WHERE e.file_pending=1)
        """;
    private static readonly string ReceivedCacheBytes = $"""
        (SELECT COALESCE(SUM({CachedObjectBytes}),0)
         FROM media_objects o WHERE {ReceivedCache})
        """;
    private const string DownloadReservations = """
        SELECT pin.object_id,json_extract(reserved.manifest_json,'$.cipher_size') AS bytes
        FROM object_transfer_pins pin JOIN media_objects reserved ON reserved.object_id=pin.object_id
        WHERE pin.direction=1 AND reserved.local_state=0
          AND NOT EXISTS(SELECT 1 FROM media_cache_evictions e
                         WHERE e.object_id=pin.object_id AND e.file_pending=1)
          AND EXISTS(SELECT 1 FROM object_transfers active
                     WHERE active.object_id=pin.object_id AND active.direction=1
                       AND active.owner_kind='message')
        """;
    internal static readonly string AutomaticDownloadBudget = $"""
        CASE
            WHEN t.direction=0 OR t.download_requested=1 OR t.owner_kind<>'message' THEN 1
            WHEN (SELECT auto_download_limit_bytes FROM privacy_preferences WHERE id=1)=0 THEN 1
            ELSE json_extract(o.manifest_json,'$.cipher_size') <=
                (SELECT auto_download_limit_bytes FROM privacy_preferences WHERE id=1)
                - {ReceivedCacheBytes}
                - {PendingCacheBytes}
                - (SELECT COALESCE(SUM(bytes),0) FROM ({DownloadReservations}) reserved
                   WHERE reserved.object_id<>o.object_id)
        END
        """;

    internal static AttachmentCacheUsage CacheUsage(VaultTransaction db)
    {
        var bytesByCategory = db.Query(
            $"SELECT category,SUM(bytes) FROM ({CategorizedCache}) GROUP BY category",
            row => (Category: Enum.Parse<AttachmentCacheCategory>(row.GetString(0)), Bytes: row.GetInt64(1)))
            .ToDictionary(row => row.Category, row => row.Bytes);
        return db.Query($"""
            SELECT {PendingCacheBytes},auto_download_limit_bytes,
                   CASE WHEN auto_download_limit_bytes>0 THEN {ReceivedCacheBytes}+{PendingCacheBytes}+
                       (SELECT COALESCE(SUM(bytes),0) FROM ({DownloadReservations})) ELSE 0 END
            FROM privacy_preferences WHERE id=1
            """, row => new AttachmentCacheUsage(bytesByCategory, row.GetInt64(0), row.GetInt64(1), row.GetInt64(2))).Single();
    }

    internal static bool QueueCacheReplacement(VaultTransaction db, string peer, string messageId, long now,
        bool previewOnly = false)
    {
        var id = db.Text($"""
            SELECT o.object_id FROM media_objects o
            JOIN ({CacheSources}) a ON a.object_id=o.object_id
            JOIN messages m ON m.messageId=a.message_id
            JOIN contact_states c ON c.uuid=m.conversationUuid
            WHERE m.messageId=$p0 AND m.conversationUuid=$p1 AND a.is_preview=$p2 AND m.isMine=0 AND m.isRevoked=0
              AND c.accepted=1 AND c.blocked=0 AND {CacheCandidate}
              AND NOT EXISTS(SELECT 1 FROM object_transfer_pins p WHERE p.object_id=o.object_id)
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p3)
            """, messageId, peer, previewOnly ? 1 : 0, now);
        if (id is null) return false;
        db.Execute("INSERT INTO media_cache_evictions(object_id) VALUES($p0)", id);
        db.Execute("UPDATE media_objects SET local_state=0 WHERE object_id=$p0", id);
        db.Execute($"""
            INSERT OR IGNORE INTO object_transfers(event_id,object_id,direction,owner_peer,owner_kind,created_at)
            SELECT m.messageId,a.object_id,1,m.conversationUuid,'message',m.timestamp
            FROM ({CacheSources}) a JOIN messages m ON m.messageId=a.message_id
            WHERE a.object_id=$p0 AND m.isMine=0 AND m.isRevoked=0
            """, id);
        return true;
    }

    internal static int QueueCacheClear(VaultTransaction db, IReadOnlyCollection<AttachmentCacheCategory> categories)
    {
        var keys = System.Text.Json.JsonSerializer.Serialize(categories.Select(category => category.ToString()));
        var count = db.Execute($"""
                INSERT INTO media_cache_evictions(object_id)
                SELECT object_id FROM ({CategorizedCache})
                WHERE category IN (SELECT value FROM json_each($p0))
            """, keys);
        if (count == 0) return 0;
        db.Execute("""
            UPDATE media_objects SET local_state=0 WHERE local_state=1
              AND object_id IN (SELECT object_id FROM media_cache_evictions WHERE file_pending=1)
            """);
        db.Execute($"""
                INSERT OR IGNORE INTO object_transfers(event_id,object_id,direction,owner_peer,owner_kind,created_at)
                SELECT m.messageId,a.object_id,1,m.conversationUuid,'message',m.timestamp
                FROM media_cache_evictions e JOIN ({CacheSources}) a ON a.object_id=e.object_id
                JOIN messages m ON m.messageId=a.message_id WHERE e.file_pending=1 AND m.isMine=0 AND m.isRevoked=0
            """);
        return count;
    }

}
