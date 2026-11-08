using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Protocol;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Core.Network;

public sealed class MessagingService : IAsyncDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private readonly RelayClient _relay;
    private readonly SecureSettingsStore _settings;
    private readonly EncryptedDatabase _database;
    private readonly MediaStore _media;
    private readonly SecureMessaging _secure;

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
        _secure = new SecureMessaging(relay,settings,database);
    }

    public event EventHandler<NotificationRequest>? NotificationRequested
    {
        add => _secure.NotificationRequested += value;
        remove => _secure.NotificationRequested -= value;
    }
    public event EventHandler<string>? OperationFailed
    {
        add => _secure.OperationFailed += value;
        remove => _secure.OperationFailed -= value;
    }
    public ValueTask DisposeAsync() => _secure.DisposeAsync();

    public Task<bool> SendTextAsync(string peerUuid,string text,string? replyToId=null,
        string? replyToSender=null,string? replyToContent=null,CancellationToken cancellationToken=default) =>
        _secure.SendTextAsync(peerUuid,text,replyToId,cancellationToken);

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

    public Task MarkConversationReadAsync(string peerUuid,CancellationToken cancellationToken=default) =>
        _secure.MarkReadAsync(peerUuid,cancellationToken);

    public async Task RevokeMessageAsync(string peerUuid, string messageId, CancellationToken cancellationToken = default)
    {
        var target = RequireModifiableMessage(peerUuid, messageId);
        await SendActionAsync(peerUuid, "revoke", messageId, "", cancellationToken).ConfigureAwait(false);
        _database.RevokeMessage(messageId);
        if (target.Extension != "txt") { _media.Delete(target.MessageId, target.Extension); }
    }

    public async Task EditMessageAsync(string peerUuid, string messageId, string newContent, CancellationToken cancellationToken = default)
    {
        var trimmed = newContent;
        if (string.IsNullOrWhiteSpace(trimmed)) { return; }
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

    public Task AddFriendAsync(string rawUuid,CancellationToken cancellationToken=default) =>
        _secure.RequestContactAsync(NormalizeUuid(rawUuid),cancellationToken);

    public Task AcceptFriendRequestAsync(string senderUuid,CancellationToken cancellationToken=default) =>
        _secure.AcceptContactAsync(senderUuid,cancellationToken);

    public Task DeclineFriendRequestAsync(string senderUuid,CancellationToken cancellationToken=default) =>
        _secure.DeclineContactAsync(senderUuid,cancellationToken);

    public async Task DeleteFriendAsync(string peerUuid,CancellationToken cancellationToken=default)
    {
        var messages=_database.GetAllMessages(peerUuid);
        await _secure.DeleteContactAsync(peerUuid,cancellationToken).ConfigureAwait(false);
        foreach (var message in messages) if (message.Extension!="txt") _media.Delete(message.MessageId,message.Extension);
    }

    public void ClearMessages(string peerUuid) => ClearConversationData(peerUuid, deleteConversation: false);

    public Task SendProfileAsync(CancellationToken cancellationToken = default) => _secure.BroadcastProfileAsync(cancellationToken);

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
        var plaintext = JsonSerializer.SerializeToUtf8Bytes(payload,JsonOptions);
        (byte[] CipherText,byte[] AuthTag) encrypted;
        try { encrypted=_settings.WithRootSeed(seed => CryptoService.EncryptEcdh(seed,peerPublicKey,iv,plaintext)); }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
        return await _relay.SendAsync(new GhostPacket(
            packetType,
            CryptoService.CalculateMailboxId(peerUuid),
            iv,
            encrypted.CipherText,
            encrypted.AuthTag), cancellationToken).ConfigureAwait(false);
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
