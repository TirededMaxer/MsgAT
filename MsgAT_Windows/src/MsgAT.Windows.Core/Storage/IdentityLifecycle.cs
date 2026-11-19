// SPDX-License-Identifier: AGPL-3.0-only
using System.Security.Cryptography;
using System.Text.Json;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Network;

namespace MsgAT.Windows.Core.Storage;

/// Public signed reset intents survive the root they destroy. They contain no account secrets.
public static class IdentityLifecycle
{
    private sealed record Endpoint(string Host,int Port,string? Pin,bool Development);
    private sealed record Intent(string Proof,Endpoint? Relay);
    private const string Marker="identity.reset.pending";
    private static ReadOnlySpan<byte> LocalOnly => "msgat-local-only-reset-v2"u8;
    private static readonly JsonSerializerOptions JsonOptions=new(JsonSerializerDefaults.Web);
    private static readonly object Gate=new();
    private static int _pendingCount;
    public static int PendingCount => Volatile.Read(ref _pendingCount);
    public static event EventHandler? PendingCountChanged;

    private static void ReportPending(int count)
    {
        if (Interlocked.Exchange(ref _pendingCount, count) != count)
            PendingCountChanged?.Invoke(null, EventArgs.Empty);
    }
    public static bool HasPendingReset(string directory) => File.Exists(Path.Combine(directory, Marker));

    public static bool CanDiscardUnreadableIdentity(string directory) => !HasPendingReset(directory) &&
        (File.Exists(Path.Combine(directory, "identity.initialized")) ||
         File.Exists(Path.Combine(directory, "identity.hardware-v2")) ||
         File.Exists(Path.Combine(directory, "msgat_vault.sec")) ||
         File.Exists(Path.Combine(directory, "msgat_v2_vault.db")));

    public static void PrepareLocalOnlyReset(string directory)
    {
        lock (Gate)
        {
            if (!CanDiscardUnreadableIdentity(directory)) throw new IOException("There is no unreadable identity eligible for reset.");
            WriteAtomic(Path.Combine(directory, Marker), LocalOnly, overwrite: false);
        }
    }

    public static void PrepareReset(string directory,SecureSettingsStore settings)
    {
        lock(Gate)
        {
            if (HasPendingReset(directory)) throw new IOException("A reset is already pending.");
            var queue=Path.Combine(directory,"revocations"); Directory.CreateDirectory(queue);
            if(Directory.EnumerateFiles(queue,"*.json").Take(257).Count()>=256) throw new IOException("Pending identity revocations must be delivered before another reset.");
            var proof=settings.WithRootSeed(seed=>SecurityCore.Call(new { operation="create_revocation",seed=Convert.ToBase64String(seed) }).GetProperty("proof").GetString()!);
            var snapshot=settings.Snapshot;
            var endpoint=string.IsNullOrWhiteSpace(snapshot.ServerAddress) ? null : new Endpoint(snapshot.ServerAddress,snapshot.ServerPort,string.IsNullOrWhiteSpace(snapshot.ServerCertificateSha256) ? null : new string(snapshot.ServerCertificateSha256.Where(Uri.IsHexDigit).Select(char.ToLowerInvariant).ToArray()),snapshot.AllowDevelopmentCertificate);
            var intent=new Intent(proof,endpoint);
            _=Validate(intent);
            WriteAtomic(Path.Combine(directory,Marker),JsonSerializer.SerializeToUtf8Bytes(intent,JsonOptions), overwrite: false);
        }
    }
    public static void ResumePendingReset(string directory)
    {
        lock(Gate)
        {
            var marker=Path.Combine(directory,Marker);
            if(!File.Exists(marker)) return;
            var bytes = ReadBytes(marker);
            if (!bytes.AsSpan().SequenceEqual(LocalOnly))
            {
                var intent = Parse(bytes);
                var hash = Validate(intent);
                if (intent.Relay is not null)
                {
                    var queue = Path.Combine(directory, "revocations");
                    Directory.CreateDirectory(queue);
                    WriteAtomic(Path.Combine(queue, hash + ".json"), bytes);
                }
            }
            // Destroy the TPM key before removing ciphertext. Never create a replacement here.
            new TpmSecretProtector(directory).DestroyKey();
            foreach(var file in new[]{"msgat_vault.sec","msgat_vault.sec.tmp","identity.initialized","identity.hardware-v2","msgat_vault.db","msgat_v2_vault.db"})
            {
                File.Delete(Path.Combine(directory,file));
                if(file.EndsWith(".db",StringComparison.Ordinal)) foreach(var suffix in new[]{"-wal","-shm","-journal"}) File.Delete(Path.Combine(directory,file+suffix));
            }
            foreach(var name in new[]{"media","objects","webview2-media","WebView2"})
            {
                var path=Path.Combine(directory,name); if(Directory.Exists(path)) Directory.Delete(path,true);
            }
            File.Delete(marker); // Final commit: normal startup may now provision a fresh identity.
        }
    }
    private static Intent Read(string file) => Parse(ReadBytes(file));

    private static Intent Parse(byte[] bytes) => JsonSerializer.Deserialize<Intent>(bytes, JsonOptions)
        ?? throw new IOException("Invalid identity reset intent.");

    private static byte[] ReadBytes(string file)
    {
        if(new FileInfo(file).Length>8192) throw new IOException("Invalid identity reset intent.");
        return File.ReadAllBytes(file);
    }
    private static string Validate(Intent intent)
    {
        var result=SecurityCore.Call(new { operation="verify_revocation",proof=intent.Proof });
        if(intent.Relay is { } relay && (string.IsNullOrWhiteSpace(relay.Host) || relay.Host.Length>253 || relay.Port is <1 or >65535 || (relay.Pin is { Length:>0 } pin && (pin.Length!=64 || pin.Any(c=>!Uri.IsHexDigit(c))))))
            throw new IOException("Invalid revocation endpoint.");
        return result.GetProperty("proof_hash").GetString()!;
    }
    private static void WriteAtomic(string path, ReadOnlySpan<byte> bytes, bool overwrite = true)
    {
        var temporary=path+".tmp";
        using(var output=new FileStream(temporary,FileMode.Create,FileAccess.Write,FileShare.None,4096,FileOptions.WriteThrough)) { output.Write(bytes); output.Flush(true); }
        File.Move(temporary, path, overwrite);
    }
    public static async Task<string?> DeliverPendingAsync(string directory, string? cursor, CancellationToken token)
    {
        var queue = Path.Combine(directory, "revocations");
        var files = Directory.Exists(queue) ? Directory.GetFiles(queue, "*.json") : Array.Empty<string>();
        Array.Sort(files, StringComparer.Ordinal);
        var pending = files.Length;
        ReportPending(pending);
        if (files.Length == 0) return null;
        var start = cursor is null ? 0 :
            Math.Max(0, Array.FindIndex(files, file => string.CompareOrdinal(file, cursor) > 0));
        for (var offset = 0; offset < Math.Min(files.Length, 8); offset++)
        {
            token.ThrowIfCancellationRequested();
            var file = files[(start + offset) % files.Length];
            cursor = file;
            try
            {
                var intent=Read(file); var hash=Validate(intent);
                if(Path.GetFileName(file)!=hash+".json" || intent.Relay is not { } endpoint) continue;
                var settings=new SecureSettings { ServerAddress=endpoint.Host,ServerPort=endpoint.Port,ServerCertificateSha256=endpoint.Pin,AllowDevelopmentCertificate=endpoint.Development,RelayTlsSecurityV2=true };
                await AttachmentTransport.RevokeAsync(settings,Convert.FromBase64String(intent.Proof),token).ConfigureAwait(false);
                File.Delete(file); // Only a correlated server ACK removes the public retry intent.
                ReportPending(--pending);
            }
            catch(OperationCanceledException) when(token.IsCancellationRequested) { throw; }
            catch(Exception) { /* Keep the signed intent for the next bounded maintenance tick. */ }
        }
        return cursor;
    }
}
