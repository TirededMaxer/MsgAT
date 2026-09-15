using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Protocol;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Core.Network;

public sealed class MessagingService : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private readonly RelayClient _relay;
    private readonly SecureSettingsStore _settings;
    private readonly EncryptedDatabase _database;
    private readonly MediaStore _media;
    private bool _disposed;

    public MessagingService(
        RelayClient relay,
        SecureSettingsStore settings,
        EncryptedDatabase database,
        MediaStore media)
    {
        _relay = relay;
        _settings = settings;
        _database = database;
        _media = media;
        _relay.StateChanged += RelayStateChanged;
    }

    public void Dispose()
    {
        if (_disposed) { return; }
        _relay.StateChanged -= RelayStateChanged;
        _disposed = true;
    }

    public async Task<bool> SendTextAsync(
        string peerUuid,
        string text,
        string? replyToId = null,
        string? replyToSender = null,
        string? replyToContent = null,
        CancellationToken cancellationToken = default)
    {
        var trimmed = text.Trim();
        if (trimmed.Length == 0) { return false; }
        var conversation = RequireSendableConversation(peerUuid);
        var id = Guid.NewGuid().ToString();
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        _database.UpsertMessage(new ChatMessage(
            id, peerUuid, true, trimmed, "txt", timestamp, IsRead: true,
            ReplyToId: replyToId, ReplyToSender: replyToSender, ReplyToContent: replyToContent, Status: 0));
        var payload = new MessagePayload(
            _settings.MyUuid, id, 1, 0, "txt",
            Convert.ToBase64String(Encoding.UTF8.GetBytes(trimmed)), timestamp,
            replyToId, replyToSender, replyToContent);
        var sent = await SendEncryptedAsync(peerUuid, conversation.PeerPublicKey, GhostPacket.Message, payload, cancellationToken)
            .ConfigureAwait(false);
        if (sent) { _database.UpdateMessageStatus(id, 1); }
        return sent;
    }

    public async Task<bool> SendMediaAsync(
        string peerUuid,
        string sourcePath,
        string? replyToId = null,
        string? replyToSender = null,
        string? replyToContent = null,
        CancellationToken cancellationToken = default)
    {
        var conversation = RequireSendableConversation(peerUuid);
        var id = Guid.NewGuid().ToString();
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var extension = MediaStore.NormalizeExtension(Path.GetExtension(sourcePath));
        var storedPath = _media.Import(id, sourcePath);
        _database.UpsertMessage(new ChatMessage(
            id, peerUuid, true, storedPath, extension, timestamp, IsRead: true,
            ReplyToId: replyToId, ReplyToSender: replyToSender, ReplyToContent: replyToContent, Status: 0));

        var chunks = MediaStore.ReadBase64Chunks(storedPath).ToArray();
        if (chunks.Length is < 1 or > 4096)
        {
            throw new InvalidDataException("Media chunk count is outside the protocol limit.");
        }
        for (var index = 0; index < chunks.Length; index++)
        {
            var payload = new MessagePayload(
                _settings.MyUuid, id, chunks.Length, index, extension, chunks[index], timestamp,
                replyToId, replyToSender, replyToContent);
            if (!await SendEncryptedAsync(peerUuid, conversation.PeerPublicKey, GhostPacket.Message, payload, cancellationToken)
                .ConfigureAwait(false))
            {
                return false;
            }
            if (index + 1 < chunks.Length)
            {
                await Task.Delay(50, cancellationToken).ConfigureAwait(false);
            }
        }
        _database.UpdateMessageStatus(id, 1);
        return true;
    }

    public Task SendReadAcknowledgementAsync(string peerUuid, string messageId, CancellationToken cancellationToken = default) =>
        SendControlMessageAsync(peerUuid, "ack_read", messageId, cancellationToken);

    public async Task RevokeMessageAsync(string peerUuid, string messageId, CancellationToken cancellationToken = default)
    {
        var target = RequireModifiableMessage(peerUuid, messageId);
        await SendActionAsync(peerUuid, "revoke", messageId, "", cancellationToken).ConfigureAwait(false);
        _database.RevokeMessage(messageId);
        if (target.Extension != "txt") { _media.Delete(target.MessageId, target.Extension); }
    }

    public async Task EditMessageAsync(string peerUuid, string messageId, string newContent, CancellationToken cancellationToken = default)
    {
        var trimmed = newContent.Trim();
        if (trimmed.Length == 0) { return; }
        if (RequireModifiableMessage(peerUuid, messageId).Extension != "txt") { return; }
        await SendActionAsync(peerUuid, "edit", messageId, Convert.ToBase64String(Encoding.UTF8.GetBytes(trimmed)), cancellationToken).ConfigureAwait(false);
        _database.EditMessage(messageId, trimmed);
    }

    private ChatMessage RequireModifiableMessage(string peerUuid, string messageId)
    {
        var target = _database.GetMessage(messageId);
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (target is not { IsMine: true, IsRevoked: false } || target.ConversationUuid != peerUuid ||
            target.Timestamp > now || now - target.Timestamp > 120000)
            throw new InvalidOperationException("Only your messages from the last two minutes can be edited or revoked.");
        return target;
    }

    public async Task AddFriendAsync(string rawUuid, CancellationToken cancellationToken = default)
    {
        if (_relay.State != SyncState.Synced) throw new InvalidOperationException("Connect to the relay before adding a friend.");
        var uuid = NormalizeUuid(rawUuid);
        if (string.Equals(uuid, _settings.MyUuid, StringComparison.Ordinal))
        {
            throw new InvalidOperationException("You cannot add your own UUID.");
        }
        if (_database.GetConversation(uuid) is not null) { return; }
        _database.UpsertConversation(new Conversation(uuid, "Unknown"));
        var payload = new
        {
            senderUuid = _settings.MyUuid,
            nickname = _settings.Snapshot.Nickname ?? "Unknown",
            senderPublicKey = _settings.MyX25519PublicKeyBase64
        };
        if (!await SendFallbackAsync(uuid, GhostPacket.SearchUser, payload, cancellationToken).ConfigureAwait(false))
        {
            _database.DeleteConversation(uuid);
            throw new InvalidOperationException("The friend request could not be sent.");
        }
    }

    public async Task AcceptFriendRequestAsync(string senderUuid, CancellationToken cancellationToken = default)
    {
        var request = _database.GetFriendRequest(senderUuid)
            ?? throw new InvalidOperationException("Friend request no longer exists.");
        if (_relay.State != SyncState.Synced) throw new InvalidOperationException("Connect to the relay before accepting a friend.");
        if (!await SendFallbackAsync(senderUuid, GhostPacket.FriendAccepted, new
        {
            senderUuid = _settings.MyUuid,
            senderPublicKey = _settings.MyX25519PublicKeyBase64
        }, cancellationToken).ConfigureAwait(false)) throw new InvalidOperationException("The friend acceptance could not be sent.");
        _database.UpsertConversation(new Conversation(request.SenderUuid, request.Nickname, request.AvatarPath, request.PeerPublicKey));
        _database.DeleteFriendRequest(senderUuid);
    }

    public void DeclineFriendRequest(string senderUuid) => _database.DeleteFriendRequest(senderUuid);

    public async Task DeleteFriendAsync(string peerUuid, CancellationToken cancellationToken = default)
    {
        var conversation = _database.GetConversation(peerUuid);
        if (conversation is not null)
        {
            if (!string.IsNullOrWhiteSpace(conversation.PeerPublicKey))
            {
                await SendEncryptedAsync(peerUuid, conversation.PeerPublicKey, GhostPacket.FriendDeleted,
                    new { senderUuid = _settings.MyUuid }, cancellationToken).ConfigureAwait(false);
            }
            else
            {
                await SendFallbackAsync(peerUuid, GhostPacket.RequestRevoked,
                    new { senderUuid = _settings.MyUuid }, cancellationToken).ConfigureAwait(false);
            }
        }
        ClearConversationData(peerUuid, deleteConversation: true);
    }

    public void ClearMessages(string peerUuid) => ClearConversationData(peerUuid, deleteConversation: false);

    public Task SendProfileAsync(CancellationToken cancellationToken = default) => _relay.SendProfileAsync(cancellationToken);

    public async Task<bool> PrepareIdentityResetAsync(CancellationToken cancellationToken = default)
    {
        if (_relay.State != SyncState.Synced) { return false; }
        foreach (var item in _database.GetConversations())
        {
            var conversation = item.Conversation;
            if (string.IsNullOrWhiteSpace(conversation.PeerPublicKey)) { continue; }
            _ = await SendEncryptedAsync(
                conversation.Uuid,
                conversation.PeerPublicKey,
                GhostPacket.AccountReset,
                new { senderUuid = _settings.MyUuid },
                cancellationToken).ConfigureAwait(false);
        }
        return await _relay.DeleteAccountAsync(cancellationToken).ConfigureAwait(false);
    }

    public Task<bool> SendCallOfferAsync(string peerUuid, string sdp, CancellationToken cancellationToken = default) =>
        SendCallSignalAsync(peerUuid, GhostPacket.CallOffer, new { type = "offer", sdp }, cancellationToken);

    public Task<bool> SendCallAnswerAsync(string peerUuid, string sdp, CancellationToken cancellationToken = default) =>
        SendCallSignalAsync(peerUuid, GhostPacket.CallAnswer, new { type = "answer", sdp }, cancellationToken);

    public Task<bool> SendCallIceAsync(
        string peerUuid,
        string sdpMid,
        int sdpMLineIndex,
        string candidate,
        CancellationToken cancellationToken = default) =>
        SendCallSignalAsync(peerUuid, GhostPacket.CallIce,
            new { type = "ice", sdpMid, sdpMLineIndex, sdp = candidate }, cancellationToken);

    public Task<bool> SendCallRejectAsync(string peerUuid, CancellationToken cancellationToken = default) =>
        SendCallSignalAsync(peerUuid, GhostPacket.CallReject, new { }, cancellationToken);

    public Task<bool> SendCallEndAsync(string peerUuid, long duration, CancellationToken cancellationToken = default) =>
        SendCallSignalAsync(peerUuid, GhostPacket.CallEnd, new { duration }, cancellationToken);

    public Task<bool> SendCallBusyAsync(string peerUuid, CancellationToken cancellationToken = default) =>
        SendCallSignalAsync(peerUuid, GhostPacket.CallBusy, new { }, cancellationToken);

    public Task<bool> SendCallSyncAsync(string peerUuid, long duration, CancellationToken cancellationToken = default) =>
        SendCallSignalAsync(peerUuid, GhostPacket.CallSync, new { duration }, cancellationToken);

    private async void RelayStateChanged(object? sender, SyncState state)
    {
        _ = sender;
        if (state != SyncState.Synced) { return; }
        try { await ResendPendingMessagesAsync().ConfigureAwait(false); }
        catch (Exception exception) { System.Diagnostics.Trace.TraceError("Message resend failed: {0}", exception); }
    }

    private async Task ResendPendingMessagesAsync()
    {
        foreach (var message in _database.GetPendingMessages())
        {
            if (_database.GetConversation(message.ConversationUuid) is not { PeerPublicKey.Length: > 0 }) { continue; }
            if (message.Extension == "txt")
            {
                var conversation = RequireSendableConversation(message.ConversationUuid);
                var payload = new MessagePayload(
                    _settings.MyUuid, message.MessageId, 1, 0, "txt",
                    Convert.ToBase64String(Encoding.UTF8.GetBytes(message.Content)), message.Timestamp,
                    message.ReplyToId, message.ReplyToSender, message.ReplyToContent);
                if (await SendEncryptedAsync(message.ConversationUuid, conversation.PeerPublicKey, GhostPacket.Message, payload, default)
                    .ConfigureAwait(false))
                {
                    _database.UpdateMessageStatus(message.MessageId, 1);
                }
            }
            else if (File.Exists(message.Content))
            {
                await ResendMediaAsync(message).ConfigureAwait(false);
            }
        }
    }

    private async Task ResendMediaAsync(ChatMessage message)
    {
        var conversation = RequireSendableConversation(message.ConversationUuid);
        var chunks = MediaStore.ReadBase64Chunks(message.Content).ToArray();
        for (var index = 0; index < chunks.Length; index++)
        {
            var payload = new MessagePayload(
                _settings.MyUuid, message.MessageId, chunks.Length, index, message.Extension,
                chunks[index], message.Timestamp, message.ReplyToId, message.ReplyToSender, message.ReplyToContent);
            if (!await SendEncryptedAsync(
                message.ConversationUuid, conversation.PeerPublicKey, GhostPacket.Message, payload, default).ConfigureAwait(false))
            {
                return;
            }
        }
        _database.UpdateMessageStatus(message.MessageId, 1);
    }

    private Task<bool> SendControlMessageAsync(
        string peerUuid,
        string extension,
        string data,
        CancellationToken cancellationToken)
    {
        var conversation = RequireSendableConversation(peerUuid);
        var payload = new MessagePayload(
            _settings.MyUuid, Guid.NewGuid().ToString(), 1, 0, extension,
            data, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        return SendEncryptedAsync(peerUuid, conversation.PeerPublicKey, GhostPacket.Message, payload, cancellationToken);
    }

    private async Task SendActionAsync(
        string peerUuid,
        string action,
        string targetMessageId,
        string data,
        CancellationToken cancellationToken)
    {
        var conversation = RequireSendableConversation(peerUuid);
        var payload = new MessagePayload(
            _settings.MyUuid, Guid.NewGuid().ToString(), 1, 0, "txt", data,
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), Action: action, TargetMessageId: targetMessageId);
        if (!await SendEncryptedAsync(peerUuid, conversation.PeerPublicKey, GhostPacket.Message, payload, cancellationToken).ConfigureAwait(false))
            throw new InvalidOperationException("The message change could not be sent. Reconnect and try again.");
    }

    private Task<bool> SendCallSignalAsync<T>(
        string peerUuid,
        byte packetType,
        T payload,
        CancellationToken cancellationToken)
    {
        var conversation = RequireSendableConversation(peerUuid);
        return SendEncryptedObjectWithSenderAsync(
            peerUuid, conversation.PeerPublicKey, packetType, payload, cancellationToken);
    }

    private async Task<bool> SendEncryptedObjectWithSenderAsync<T>(
        string peerUuid,
        string peerPublicKey,
        byte packetType,
        T payload,
        CancellationToken cancellationToken)
    {
        using var document = JsonDocument.Parse(JsonSerializer.SerializeToUtf8Bytes(payload, JsonOptions));
        var values = new Dictionary<string, object?> { ["senderUuid"] = _settings.MyUuid };
        foreach (var property in document.RootElement.EnumerateObject())
        {
            values[property.Name] = property.Value.Clone();
        }
        return await SendEncryptedAsync(peerUuid, peerPublicKey, packetType, values, cancellationToken).ConfigureAwait(false);
    }

    private async Task<bool> SendEncryptedAsync<T>(
        string peerUuid,
        string peerPublicKey,
        byte packetType,
        T payload,
        CancellationToken cancellationToken)
    {
        var iv = RandomNumberGenerator.GetBytes(16);
        var encrypted = CryptoService.EncryptEcdh(
            _settings.IdentitySeed, peerPublicKey, iv, JsonSerializer.SerializeToUtf8Bytes(payload, JsonOptions));
        return await _relay.SendAsync(new GhostPacket(
            packetType,
            CryptoService.CalculateMailboxId(peerUuid),
            iv,
            encrypted.CipherText,
            encrypted.AuthTag), cancellationToken).ConfigureAwait(false);
    }

    private async Task<bool> SendFallbackAsync<T>(
        string peerUuid,
        byte packetType,
        T payload,
        CancellationToken cancellationToken)
    {
        var iv = RandomNumberGenerator.GetBytes(16);
        var encrypted = CryptoService.EncryptFallback(
            peerUuid, peerUuid, iv, JsonSerializer.SerializeToUtf8Bytes(payload, JsonOptions));
        return await _relay.SendAsync(new GhostPacket(
            packetType, CryptoService.CalculateMailboxId(peerUuid), iv,
            encrypted.CipherText, encrypted.AuthTag), cancellationToken).ConfigureAwait(false);
    }

    private void ClearConversationData(string peerUuid, bool deleteConversation)
    {
        foreach (var message in _database.GetAllMessages(peerUuid).Where(static message => message.Extension != "txt"))
        {
            _media.Delete(message.MessageId, message.Extension);
        }
        _database.ClearMessages(peerUuid);
        if (deleteConversation) { _database.DeleteConversation(peerUuid); }
    }

    private Conversation RequireSendableConversation(string peerUuid)
    {
        var conversation = _database.GetConversation(peerUuid)
            ?? throw new InvalidOperationException("Conversation does not exist.");
        if (string.IsNullOrWhiteSpace(conversation.PeerPublicKey))
        {
            throw new InvalidOperationException("The peer identity is unavailable or has been deleted.");
        }
        return conversation;
    }

    private static string NormalizeUuid(string value)
    {
        var uuid = value.Trim().ToLowerInvariant();
        if (uuid.Length != 64 || uuid.Any(static character => !Uri.IsHexDigit(character)))
        {
            throw new ArgumentException("A friend UUID must contain exactly 64 hexadecimal characters.");
        }
        return uuid;
    }
}
