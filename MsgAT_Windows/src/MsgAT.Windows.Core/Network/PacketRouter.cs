using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using System.Text;
using System.Text.Json;
using MsgAT.Windows.Core.Calls;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Protocol;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Core.Network;

public sealed class PacketRouter : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private readonly RelayClient _relay;
    private readonly SecureSettingsStore _settings;
    private readonly EncryptedDatabase _database;
    private readonly MediaStore _media;
    private readonly ICallSignalingSink _calls;
    private readonly ConcurrentDictionary<string, ChunkAssembly> _chunks = new(StringComparer.Ordinal);
    private bool _disposed;

    public PacketRouter(
        RelayClient relay,
        SecureSettingsStore settings,
        EncryptedDatabase database,
        MediaStore media,
        ICallSignalingSink calls)
    {
        _relay = relay;
        _settings = settings;
        _database = database;
        _media = media;
        _calls = calls;
        _relay.PacketReceived += RouteAsync;
    }

    public event EventHandler<NotificationRequest>? NotificationRequested;

    public void Dispose()
    {
        if (_disposed) { return; }
        _relay.PacketReceived -= RouteAsync;
        _disposed = true;
    }

    private async Task RouteAsync(GhostPacket packet, CancellationToken cancellationToken)
    {
        switch (packet.Type)
        {
            case GhostPacket.Message:
                await HandleMessageAsync(packet, cancellationToken).ConfigureAwait(false);
                break;
            case GhostPacket.SearchUser:
                await HandleFriendRequestAsync(packet, cancellationToken).ConfigureAwait(false);
                break;
            case GhostPacket.ProfileResponse:
                HandleProfileResponse(packet);
                break;
            case GhostPacket.FriendAccepted:
                await HandleFriendAcceptedAsync(packet, cancellationToken).ConfigureAwait(false);
                break;
            case GhostPacket.FriendDeleted:
                await HandleFriendDeletedAsync(packet, cancellationToken).ConfigureAwait(false);
                break;
            case GhostPacket.RequestRevoked:
                await HandleRequestRevokedAsync(packet, cancellationToken).ConfigureAwait(false);
                break;
            case GhostPacket.AccountReset:
                await HandleAccountResetAsync(packet, cancellationToken).ConfigureAwait(false);
                break;
            case >= GhostPacket.CallOffer and <= GhostPacket.CallSync:
                await HandleCallSignalingAsync(packet, cancellationToken).ConfigureAwait(false);
                break;
        }
    }

    private async Task HandleMessageAsync(GhostPacket packet, CancellationToken cancellationToken)
    {
        var payload = TryDecryptPayload<MessagePayload>(packet);
        if (payload is null || !Guid.TryParseExact(payload.FileId, "D", out _) ||
            payload.Ext is null || !Regex.IsMatch(payload.Ext, "^[A-Za-z0-9_]{1,10}$") ||
            payload.Data is null || payload.Data.Length > 200000 || payload.Timestamp is < 0 or > 253402300799999L ||
            payload.Total is < 1 or > 4096 || payload.Index < 0 || payload.Index >= payload.Total) { return; }

        if (payload.Action is null && _database.GetMessage(payload.FileId) is { } existing)
        {
            if (!existing.IsMine && existing.ConversationUuid == payload.SenderUuid)
            {
                await SendDeliveryAcknowledgementAsync(payload, cancellationToken).ConfigureAwait(false);
                await SendTransportAckAsync(packet, cancellationToken).ConfigureAwait(false);
            }
            return;
        }

        var assemblyKey = $"{payload.SenderUuid}:{payload.FileId}";
        var receipt = SHA256.HashData(packet.EncryptedData);
        var receipts = new List<byte[]> { receipt };
        var completeData = payload.Data;
        if (payload.Total > 1)
        {
            if (payload.Action is not null || payload.Ext.StartsWith("ack_", StringComparison.Ordinal)) { return; }
            var assembly = AddChunk(payload, receipt);
            if (assembly is null) { return; }
            completeData = string.Concat(Enumerable.Range(0, payload.Total).Select(index => assembly.Chunks[index]));
            receipts = assembly.Receipts.Values.ToList();
        }

        if (payload.Ext is "ack_deliv" or "ack_read")
        {
            var target = _database.GetMessage(completeData);
            if (target is { IsMine: true } && target.ConversationUuid == payload.SenderUuid)
            {
                if (payload.Ext == "ack_deliv") { _database.UpdateMessageStatus(target.MessageId, 2); }
                else { _database.UpdateMessageStatusThrough(payload.SenderUuid, target.Timestamp, 3); }
            }
        }
        else if (payload.Action is "revoke" or "edit")
        {
            var target = payload.TargetMessageId is null ? null : _database.GetMessage(payload.TargetMessageId);
            if (target is null) { return; }
            if (target is { IsMine: false } && target.ConversationUuid == payload.SenderUuid &&
                target.Timestamp >= 0 && payload.Timestamp >= target.Timestamp && payload.Timestamp - target.Timestamp <= 120000)
            {
                if (payload.Action == "revoke")
                {
                    _database.RevokeMessage(target.MessageId);
                    if (target.Extension != "txt") { _media.Delete(target.MessageId, target.Extension); }
                }
                else if (target.Extension == "txt" && !target.IsRevoked)
                {
                    _database.EditMessage(target.MessageId, DecodeText(completeData));
                }
            }
        }
        else if (payload.Action is not null) { return; }
        else
        {
            var bytes = DecodeBase64(completeData);
            if (bytes.LongLength > MediaStore.MaximumFileSize) { return; }
            var content = payload.Ext == "txt" ? Encoding.UTF8.GetString(bytes) : _media.Save(payload.FileId, payload.Ext, bytes);
            _database.UpsertMessage(new ChatMessage(
                payload.FileId, payload.SenderUuid, IsMine: false, content, payload.Ext, payload.Timestamp,
                ReplyToId: payload.ReplyToId, ReplyToSender: payload.ReplyToSender, ReplyToContent: payload.ReplyToContent));
            var conversation = _database.GetConversation(payload.SenderUuid);
            NotificationRequested?.Invoke(this, new NotificationRequest(
                conversation?.Alias ?? "MsgAT", payload.Ext == "txt" ? content : $"[{payload.Ext.ToUpperInvariant()}]", payload.SenderUuid));
        }

        if (!payload.Ext.StartsWith("ack_", StringComparison.Ordinal))
        {
            await SendDeliveryAcknowledgementAsync(payload, cancellationToken).ConfigureAwait(false);
        }
        foreach (var digest in receipts) { await SendTransportAckDigestAsync(digest, cancellationToken).ConfigureAwait(false); }
        _chunks.TryRemove(assemblyKey, out _);
    }

    private async Task HandleFriendRequestAsync(GhostPacket packet, CancellationToken cancellationToken)
    {
        var payload = DecryptFallback<FriendIdentityPayload>(packet);
        if (payload is null || !ValidFriendIdentity(payload)) { return; }
        if (_database.GetConversation(payload.SenderUuid) is { PeerPublicKey.Length: > 0 })
        {
            await SendTransportAckAsync(packet, cancellationToken).ConfigureAwait(false);
            return;
        }
        _database.UpsertFriendRequest(new FriendRequest(
            payload.SenderUuid,
            string.IsNullOrWhiteSpace(payload.Nickname) ? payload.SenderUuid[..12] : payload.Nickname.Trim(),
            "",
            payload.SenderPublicKey ?? "",
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
        await RequestProfileAsync(payload.SenderUuid, cancellationToken).ConfigureAwait(false);
        await SendTransportAckAsync(packet, cancellationToken).ConfigureAwait(false);
    }

    private void HandleProfileResponse(GhostPacket packet)
    {
        var profile = JsonSerializer.Deserialize<ProfilePayload>(packet.EncryptedData, JsonOptions);
        if (profile is null || string.IsNullOrWhiteSpace(profile.Uuid)) { return; }
        var avatarPath = "";
        if (!string.IsNullOrWhiteSpace(profile.AvatarData))
        {
            avatarPath = _media.SaveAvatar(profile.Uuid, DecodeBase64(profile.AvatarData));
        }
        if (_database.GetFriendRequest(profile.Uuid) is { } request)
        {
            _database.UpsertFriendRequest(request with { Nickname = profile.Nickname ?? "Unknown", AvatarPath = avatarPath });
        }
        if (_database.GetConversation(profile.Uuid) is { } conversation)
        {
            _database.UpsertConversation(conversation with { Alias = profile.Nickname ?? "Unknown", AvatarPath = avatarPath });
        }
    }

    private async Task HandleFriendAcceptedAsync(GhostPacket packet, CancellationToken cancellationToken)
    {
        var payload = DecryptFallback<FriendIdentityPayload>(packet);
        if (payload is null || !ValidFriendIdentity(payload)) { return; }
        var existing = _database.GetConversation(payload.SenderUuid);
        if (existing is null || (existing.PeerPublicKey.Length > 0 && existing.PeerPublicKey != payload.SenderPublicKey))
        {
            await SendTransportAckAsync(packet, cancellationToken).ConfigureAwait(false);
            return;
        }
        _database.UpsertConversation(existing with { PeerPublicKey = payload.SenderPublicKey! });
        _database.DeleteFriendRequest(payload.SenderUuid);
        await RequestProfileAsync(payload.SenderUuid, cancellationToken).ConfigureAwait(false);
        await SendTransportAckAsync(packet, cancellationToken).ConfigureAwait(false);
    }

    private async Task HandleFriendDeletedAsync(GhostPacket packet, CancellationToken cancellationToken)
    {
        var payload = TryDecryptPayload<SenderPayload>(packet);
        if (payload is not null && !string.IsNullOrWhiteSpace(payload.SenderUuid))
        {
            DeleteConversationData(payload.SenderUuid);
        }
        await SendTransportAckAsync(packet, cancellationToken).ConfigureAwait(false);
    }

    private async Task HandleRequestRevokedAsync(GhostPacket packet, CancellationToken cancellationToken)
    {
        var payload = DecryptFallback<SenderPayload>(packet);
        if (payload is not null && !string.IsNullOrWhiteSpace(payload.SenderUuid))
        {
            _database.DeleteFriendRequest(payload.SenderUuid);
        }
        await SendTransportAckAsync(packet, cancellationToken).ConfigureAwait(false);
    }

    private async Task HandleAccountResetAsync(GhostPacket packet, CancellationToken cancellationToken)
    {
        var payload = TryDecryptPayload<SenderPayload>(packet);
        if (payload is not null && _database.GetConversation(payload.SenderUuid) is { } conversation)
        {
            _database.UpsertConversation(conversation with
            {
                Alias = conversation.Alias + " [Identity deleted]",
                PeerPublicKey = ""
            });
        }
        await SendTransportAckAsync(packet, cancellationToken).ConfigureAwait(false);
    }

    private async Task HandleCallSignalingAsync(GhostPacket packet, CancellationToken cancellationToken)
    {
        var payload = TryDecryptPayload<CallPayload>(packet);
        if (payload is null || string.IsNullOrWhiteSpace(payload.SenderUuid)) { return; }
        switch (packet.Type)
        {
            case GhostPacket.CallOffer: await _calls.OfferReceivedAsync(payload.SenderUuid, payload.Sdp ?? "").ConfigureAwait(false); break;
            case GhostPacket.CallAnswer: await _calls.AnswerReceivedAsync(payload.SenderUuid, payload.Sdp ?? "").ConfigureAwait(false); break;
            case GhostPacket.CallIce: await _calls.IceCandidateReceivedAsync(payload.SenderUuid, payload.SdpMid ?? "0", payload.SdpMLineIndex, payload.Sdp ?? "").ConfigureAwait(false); break;
            case GhostPacket.CallReject: await _calls.RejectedAsync(payload.SenderUuid).ConfigureAwait(false); break;
            case GhostPacket.CallEnd: await _calls.EndedAsync(payload.SenderUuid, payload.Duration).ConfigureAwait(false); break;
            case GhostPacket.CallBusy: await _calls.BusyAsync(payload.SenderUuid).ConfigureAwait(false); break;
            case GhostPacket.CallSync: await _calls.SyncReceivedAsync(payload.SenderUuid, payload.Duration).ConfigureAwait(false); break;
        }
    }

    private T? TryDecryptPayload<T>(GhostPacket packet)
    {
        foreach (var item in _database.GetConversations())
        {
            if (string.IsNullOrWhiteSpace(item.Conversation.PeerPublicKey)) { continue; }
            try
            {
                var plaintext = CryptoService.DecryptEcdh(
                    _settings.IdentitySeed,
                    item.Conversation.PeerPublicKey,
                    packet.NonceOrIv,
                    packet.EncryptedData,
                    packet.AuthTag);
                using var document = JsonDocument.Parse(plaintext);
                if (!document.RootElement.TryGetProperty("senderUuid", out var sender) ||
                    sender.GetString() != item.Conversation.Uuid) { return default; }
                return JsonSerializer.Deserialize<T>(plaintext, JsonOptions);
            }
            catch (Exception exception) when (exception is System.Security.Cryptography.CryptographicException or JsonException or FormatException)
            {
                // Try the next known peer key.
            }
        }
        return default;
    }

    private T? DecryptFallback<T>(GhostPacket packet)
    {
        try
        {
            var plaintext = CryptoService.DecryptFallback(
                _settings.MyUuid,
                _settings.MyUuid,
                packet.NonceOrIv,
                packet.EncryptedData,
                packet.AuthTag);
            return JsonSerializer.Deserialize<T>(plaintext, JsonOptions);
        }
        catch (Exception exception) when (exception is System.Security.Cryptography.CryptographicException or JsonException)
        {
            return default;
        }
    }

    private ChunkAssembly? AddChunk(MessagePayload payload, byte[] receipt)
    {
        var now = DateTimeOffset.UtcNow;
        foreach (var stale in _chunks.Where(pair => now - pair.Value.UpdatedAt > TimeSpan.FromMinutes(10)).ToArray())
        {
            _chunks.TryRemove(stale.Key, out _);
        }
        var key = $"{payload.SenderUuid}:{payload.FileId}";
        if (!_chunks.ContainsKey(key) && _chunks.Count >= 16) { return null; }
        var metadata = payload with { Index = 0, Data = "" };
        var assembly = _chunks.GetOrAdd(key, _ => new ChunkAssembly(metadata));
        if (assembly.Metadata != metadata) { return null; }
        assembly.Chunks[payload.Index] = payload.Data;
        assembly.Receipts[payload.Index] = receipt;
        assembly.UpdatedAt = now;
        if (_chunks.Values.Sum(value => value.Chunks.Values.Sum(chunk => (long)chunk.Length)) > 140000000L)
        {
            _chunks.TryRemove(key, out _);
            return null;
        }
        return assembly.Chunks.Count == payload.Total ? assembly : null;
    }

    private async Task SendDeliveryAcknowledgementAsync(MessagePayload received, CancellationToken cancellationToken)
    {
        var conversation = _database.GetConversation(received.SenderUuid);
        if (conversation is null || string.IsNullOrWhiteSpace(conversation.PeerPublicKey)) { return; }
        var acknowledgement = new MessagePayload(
            _settings.MyUuid,
            Guid.NewGuid().ToString(),
            1,
            0,
            "ack_deliv",
            received.FileId,
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        await SendEncryptedAsync(received.SenderUuid, conversation.PeerPublicKey, GhostPacket.Message, acknowledgement, cancellationToken)
            .ConfigureAwait(false);
    }

    private async Task RequestProfileAsync(string uuid, CancellationToken cancellationToken)
    {
        var payload = JsonSerializer.SerializeToUtf8Bytes(new { targetUuid = uuid }, JsonOptions);
        await _relay.SendAsync(new GhostPacket(
            GhostPacket.GetProfile,
            _settings.MyMailboxId,
            new byte[16],
            payload,
            new byte[16]), cancellationToken).ConfigureAwait(false);
    }

    private Task SendTransportAckAsync(GhostPacket packet, CancellationToken cancellationToken) =>
        SendTransportAckDigestAsync(SHA256.HashData(packet.EncryptedData), cancellationToken);

    private async Task SendTransportAckDigestAsync(byte[] digest, CancellationToken cancellationToken) =>
        _ = await _relay.SendAsync(new GhostPacket(GhostPacket.Ack, _settings.MyMailboxId,
            new byte[16], digest, new byte[16]), cancellationToken).ConfigureAwait(false);

    private async Task SendEncryptedAsync<T>(
        string peerUuid,
        string peerPublicKey,
        byte packetType,
        T payload,
        CancellationToken cancellationToken)
    {
        var iv = System.Security.Cryptography.RandomNumberGenerator.GetBytes(16);
        var encrypted = CryptoService.EncryptEcdh(
            _settings.IdentitySeed,
            peerPublicKey,
            iv,
            JsonSerializer.SerializeToUtf8Bytes(payload, JsonOptions));
        await _relay.SendAsync(new GhostPacket(
            packetType,
            CryptoService.CalculateMailboxId(peerUuid),
            iv,
            encrypted.CipherText,
            encrypted.AuthTag), cancellationToken).ConfigureAwait(false);
    }

    private void DeleteConversationData(string uuid)
    {
        foreach (var message in _database.GetAllMessages(uuid).Where(static message => message.Extension != "txt"))
        {
            _media.Delete(message.MessageId, message.Extension);
        }
        _database.ClearMessages(uuid);
        _database.DeleteConversation(uuid);
    }

    private static string DecodeText(string data)
    {
        return Encoding.UTF8.GetString(DecodeBase64(data));
    }

    private static byte[] DecodeBase64(string data)
    {
        var normalized = string.Concat(data.Where(static character => !char.IsWhiteSpace(character))).TrimEnd('=');
        normalized += new string('=', (4 - normalized.Length % 4) % 4);
        return Convert.FromBase64String(normalized);
    }

    private sealed class ChunkAssembly(MessagePayload metadata)
    {
        public MessagePayload Metadata { get; } = metadata;
        public Dictionary<int, byte[]> Receipts { get; } = [];
        public Dictionary<int, string> Chunks { get; } = [];
        public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
    }

    private bool ValidFriendIdentity(FriendIdentityPayload payload)
    {
        try
        {
            return payload.SenderUuid != _settings.MyUuid && payload.SenderUuid is not null &&
                Regex.IsMatch(payload.SenderUuid, "^[a-f0-9]{64}$") && payload.SenderPublicKey is not null &&
                Convert.FromBase64String(payload.SenderPublicKey).Length == 32;
        }
        catch (FormatException) { return false; }
    }

    private sealed record SenderPayload(string SenderUuid);
    private sealed record FriendIdentityPayload(string SenderUuid, string? SenderPublicKey, string? Nickname);
    private sealed record ProfilePayload(string Uuid, string? Nickname, string? AvatarPath, string? AvatarData);
    private sealed record CallPayload(
        string SenderUuid,
        string? Sdp,
        string? SdpMid,
        int SdpMLineIndex,
        long Duration);
}

public sealed record NotificationRequest(string Title, string Body, string SenderUuid);
