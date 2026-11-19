// SPDX-License-Identifier: AGPL-3.0-only
using System.Buffers.Binary;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json.Nodes;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Protocol;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Core.Network;

internal sealed class AttachmentUnavailableException() : IOException("The relay could not provide this attachment.") { }

/// <summary>One serialized bulk transfer on an independently authenticated TLS connection.</summary>
internal sealed class AttachmentTransport(SecureSettingsStore settings)
{
    internal static async Task RevokeAsync(SecureSettings endpoint,byte[] proof,CancellationToken token)
    {
        if(proof.Length!=113) throw new InvalidDataException("Invalid revocation proof.");
        await using var connection=await Connection.OpenAsync(null,endpoint,token).ConfigureAwait(false);
        var nonce=RandomNumberGenerator.GetBytes(16);
        var response=await connection.ExchangeAsync(new GhostPacket(GhostPacket.V2Revoke,null,nonce,proof,new byte[16]),GhostPacket.V2Revoked,token).ConfigureAwait(false);
        if(!CryptographicOperations.FixedTimeEquals(response.EncryptedData,SHA256.HashData(proof))) throw new InvalidDataException("Invalid revocation acknowledgment.");
    }

    internal async Task<long> UploadAsync(JsonObject manifest, string encryptedPath, SecureSettings endpoint, IProgress<long>? progress, CancellationToken token,Action? checkpoint=null)
    {
        SecurityCore.Call(new { operation = "validate_object_manifest", manifest });checkpoint?.Invoke();
        await using var connection = await Connection.OpenAsync(settings, endpoint, token).ConfigureAwait(false);
        var id = Text(manifest, "object_id");
        var begin = new JsonObject { ["cipher_size"] = Number(manifest,"cipher_size"),
            ["cipher_sha256"] = Text(manifest,"cipher_sha256"), ["read_token_hash"] = Text(manifest,"read_token_hash") };
        checkpoint?.Invoke();
        var status = Status(await connection.ExchangeAsync(Packet(GhostPacket.ObjectBegin,id,Json(begin)),GhostPacket.ObjectStatus,token).ConfigureAwait(false));
        var count = Number(manifest,"chunk_count");
        CheckProgress(status, count);
        if (status.Complete) return status.Expiry;
        await using var source = new FileStream(encryptedPath,FileMode.Open,FileAccess.Read,FileShare.Read,65552,FileOptions.Asynchronous|FileOptions.RandomAccess);
        if(source.Length!=Number(manifest,"cipher_size")) throw new InvalidDataException("Encrypted attachment has an incorrect size.");
        source.Position=status.Next*EncryptedObjectStore.ChunkBytes;
        var buffer=new byte[EncryptedObjectStore.ChunkBytes];
        try
        {
            for(var index=status.Next; index<count; index++)
            {
                await source.ReadExactlyAsync(buffer,token).ConfigureAwait(false);
                checkpoint?.Invoke();
                status=Status(await connection.ExchangeAsync(Packet(GhostPacket.ObjectPut,id,buffer,index),GhostPacket.ObjectStatus,token).ConfigureAwait(false));
                if(status.Next!=index+1 || status.Complete) throw new InvalidDataException("Invalid attachment progress acknowledgement.");
                progress?.Report(Math.Min((index+1)*65536,Number(manifest,"size")));
            }
            checkpoint?.Invoke();
            status=Status(await connection.ExchangeAsync(Packet(GhostPacket.ObjectFinish,id,[]),GhostPacket.ObjectStatus,token).ConfigureAwait(false));
            CheckProgress(status,count);
            if(!status.Complete) throw new InvalidDataException("Attachment publication is incomplete.");
            return status.Expiry;
        }
        finally { CryptographicOperations.ZeroMemory(buffer); }
    }

    internal async Task DownloadAsync(JsonObject manifest, string target, SecureSettings endpoint, IProgress<long>? progress, CancellationToken token,Action? checkpoint=null)
    {
        SecurityCore.Call(new { operation = "validate_object_manifest", manifest });checkpoint?.Invoke();
        var expected=Number(manifest,"cipher_size");
        var hash=Convert.FromHexString(Text(manifest,"cipher_sha256"));
        if(File.Exists(target))
        {
            try { await VerifyFileAsync(target,expected,hash,token).ConfigureAwait(false); return; }
            catch(InvalidDataException) { File.Delete(target); }
        }
        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        var partial=target+".part";
        var capability=Convert.FromBase64String(Text(manifest,"read_token"));
        try
        {
            await using(var output=new FileStream(partial,FileMode.OpenOrCreate,FileAccess.Write,FileShare.None,65552,FileOptions.Asynchronous))
            {
                if(output.Length>expected) output.SetLength(0);
                var start=output.Length/EncryptedObjectStore.ChunkBytes;
                output.SetLength(start*EncryptedObjectStore.ChunkBytes);
                output.Position=output.Length;
                var count = Number(manifest, "chunk_count");
                if (start < count)
                {
                    await using var connection = await Connection.OpenAsync(settings, endpoint, token).ConfigureAwait(false);
                    for (var index = start; index < count; index++)
                    {
                        checkpoint?.Invoke();
                        var packet = await connection.ExchangeAsync(Packet(GhostPacket.ObjectGet, Text(manifest, "object_id"), capability, index), GhostPacket.ObjectData, token).ConfigureAwait(false);
                        if (packet.EncryptedData.Length != EncryptedObjectStore.ChunkBytes) throw new InvalidDataException("Invalid encrypted attachment chunk size.");
                        SecurityCore.Call(new { operation = "check_object_chunk", chunk = new {
                            key = Text(manifest, "key"), object_id = Text(manifest, "object_id"), size = Number(manifest, "size"), index,
                            data = Convert.ToBase64String(packet.EncryptedData) } });
                        await output.WriteAsync(packet.EncryptedData, token).ConfigureAwait(false);
                        output.Flush(flushToDisk: true);
                        progress?.Report(Math.Min((index + 1) * 65536, Number(manifest, "size")));
                    }
                }
            }
            try { await VerifyFileAsync(partial,expected,hash,token).ConfigureAwait(false); }
            catch(InvalidDataException) { File.Delete(partial); throw; }
            checkpoint?.Invoke();
            File.Move(partial,target,overwrite:false);
        }
        finally { CryptographicOperations.ZeroMemory(capability); }
    }

    private static async Task VerifyFileAsync(string path,long expected,byte[] hash,CancellationToken token)
    {
        await using var source=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read,65536,FileOptions.Asynchronous|FileOptions.SequentialScan);
        if(source.Length!=expected || !CryptographicOperations.FixedTimeEquals(await SHA256.HashDataAsync(source,token).ConfigureAwait(false),hash))
            throw new InvalidDataException("Encrypted attachment integrity check failed.");
    }
    private static GhostPacket Packet(byte type,string id,byte[] payload,long? index=null)
    {
        var nonce=RandomNumberGenerator.GetBytes(16);
        if(index is { } value) BinaryPrimitives.WriteInt64BigEndian(nonce,value);
        return new GhostPacket(type,id,nonce,payload,new byte[16]);
    }
    private static byte[] Json(JsonObject value)=>System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(value);
    private static string Text(JsonObject value,string field)=>value[field]?.GetValue<string>()??throw new InvalidDataException("Missing attachment metadata.");
    private static long Number(JsonObject value,string field)=>value[field]?.GetValue<long>()??throw new InvalidDataException("Missing attachment metadata.");
    private static (long Next,bool Complete,long Expiry) Status(GhostPacket packet)
    {
        if(packet.EncryptedData.Length>1024) throw new InvalidDataException("Oversized attachment acknowledgement.");
        var value=JsonNode.Parse(packet.EncryptedData)?.AsObject()??throw new InvalidDataException("Invalid attachment acknowledgement.");
        return(Number(value,"next_chunk"),value["complete"]?.GetValue<bool>()??false,Number(value,"expires_at"));
    }
    private static void CheckProgress((long Next,bool Complete,long Expiry) value,long count)
    {
        if(value.Next<0 || value.Next>count || (value.Complete && value.Next!=count)) throw new InvalidDataException("Invalid attachment progress.");
    }

    private sealed class Connection(TcpClient tcp,SslStream stream) : IAsyncDisposable
    {
        internal static async Task<Connection> OpenAsync(SecureSettingsStore? settings,SecureSettings snapshot,CancellationToken token)
        {
            using var deadline=CancellationTokenSource.CreateLinkedTokenSource(token);
            deadline.CancelAfter(TimeSpan.FromSeconds(30));
            var tcp=new TcpClient { NoDelay=true };
            SslStream? stream=null;
            try
            {
                var host=snapshot.ServerAddress??throw new InvalidOperationException("The relay is not configured.");
                await tcp.ConnectAsync(host,snapshot.ServerPort,deadline.Token).ConfigureAwait(false);
                stream=new SslStream(tcp.GetStream(),false,(_,certificate,chain,errors)=>RelayClient.ValidateCertificate(snapshot,certificate,chain,errors));
                await stream.AuthenticateAsClientAsync(new SslClientAuthenticationOptions {
                    TargetHost=host,EnabledSslProtocols=SslProtocols.Tls12|SslProtocols.Tls13,CertificateRevocationCheckMode=X509RevocationMode.Online
                },deadline.Token).ConfigureAwait(false);
                if(settings is null) return new Connection(tcp,stream); // Public signed revocation does not need the destroyed root.
                var certificate=stream.RemoteCertificate??throw new AuthenticationException("TLS certificate is missing.");
                var authentication=new RelayAuthentication(settings.MyMailboxId,SHA256.HashData(certificate.GetRawCertData()),transfer:true);
                var connection=new Connection(tcp,stream);
                await connection.WriteAsync(authentication.Request(),deadline.Token).ConfigureAwait(false);
                var challenge=await connection.ReadAsync(deadline.Token).ConfigureAwait(false);
                if(challenge.Type!=GhostPacket.AuthChallenge) throw new InvalidDataException("Missing attachment authentication challenge.");
                var transcript=authentication.Transcript(challenge);
                var proof=settings.WithRootSeed(seed=>CryptoService.SignChallenge(seed,transcript));
                var payload=new byte[96]; proof.PublicKey.CopyTo(payload,0); proof.Signature.CopyTo(payload,32);
                await connection.WriteAsync(new GhostPacket(GhostPacket.AuthResponse,settings.MyMailboxId,challenge.NonceOrIv,payload,new byte[16]),deadline.Token).ConfigureAwait(false);
                var completed=await connection.ReadAsync(deadline.Token).ConfigureAwait(false);
                authentication.Complete(completed);
                return connection;
            }
            catch { if(stream is not null) await stream.DisposeAsync().ConfigureAwait(false); tcp.Dispose(); throw; }
        }

        internal async Task<GhostPacket> ExchangeAsync(GhostPacket request,byte expected,CancellationToken token)
        {
            using var deadline=CancellationTokenSource.CreateLinkedTokenSource(token);
            deadline.CancelAfter(TimeSpan.FromSeconds(30));
            await WriteAsync(request,deadline.Token).ConfigureAwait(false);
            var reply=await ReadAsync(deadline.Token).ConfigureAwait(false);
            if(reply.TargetMailboxId!=request.TargetMailboxId
                || !CryptographicOperations.FixedTimeEquals(reply.NonceOrIv,request.NonceOrIv))
                throw new IOException("The attachment request could not be completed.");
            if(expected==GhostPacket.ObjectData && reply.Type==GhostPacket.ObjectError &&
               reply.EncryptedData.AsSpan().SequenceEqual("{\"error\":\"object_unavailable\"}"u8))
                throw new AttachmentUnavailableException();
            if(reply.Type!=expected) throw new IOException("The attachment request could not be completed.");
            return reply;
        }
        private async Task WriteAsync(GhostPacket packet,CancellationToken token)
        {
            await stream.WriteAsync(GhostPacketCodec.Encode(packet),token).ConfigureAwait(false);
            await stream.FlushAsync(token).ConfigureAwait(false);
        }
        private async Task<GhostPacket> ReadAsync(CancellationToken token)
        {
            var header=new byte[4]; await stream.ReadExactlyAsync(header,token).ConfigureAwait(false);
            var length=BinaryPrimitives.ReadInt32BigEndian(header);
            if(length<105 || length>GhostPacketCodec.MaximumFrameSize) throw new InvalidDataException("Invalid attachment frame length.");
            var frame=new byte[length]; header.CopyTo(frame,0);
            await stream.ReadExactlyAsync(frame.AsMemory(4),token).ConfigureAwait(false);
            if(!GhostPacketCodec.TryDecode(frame,out var packet,out var consumed,out _) || consumed!=frame.Length || packet is null)
                throw new InvalidDataException("Invalid attachment frame.");
            return packet;
        }
        public async ValueTask DisposeAsync() { await stream.DisposeAsync().ConfigureAwait(false); tcp.Dispose(); }
    }
}
