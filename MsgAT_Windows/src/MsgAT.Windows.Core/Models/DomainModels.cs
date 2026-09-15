namespace MsgAT.Windows.Core.Models;

public sealed record Conversation(
    string Uuid,
    string Alias,
    string AvatarPath = "",
    string PeerPublicKey = "");

public sealed record ChatMessage(
    string MessageId,
    string ConversationUuid,
    bool IsMine,
    string Content,
    string Extension,
    long Timestamp,
    bool IsRead = false,
    string? ReplyToId = null,
    string? ReplyToSender = null,
    string? ReplyToContent = null,
    int Status = 1,
    bool IsRevoked = false,
    bool IsEdited = false);

public sealed record FriendRequest(
    string SenderUuid,
    string Nickname,
    string AvatarPath,
    string PeerPublicKey,
    long Timestamp);

public sealed record ConversationWithUnread(Conversation Conversation, int UnreadCount);

public sealed record MessagePayload(
    string SenderUuid,
    string FileId,
    int Total,
    int Index,
    string Ext,
    string Data,
    long Timestamp,
    string? ReplyToId = null,
    string? ReplyToSender = null,
    string? ReplyToContent = null,
    string? Action = null,
    string? TargetMessageId = null);

public enum SyncState
{
    Disconnected,
    Syncing,
    Synced
}

public enum CallState
{
    Idle,
    OutgoingRinging,
    IncomingRinging,
    Connecting,
    Connected,
    Ended
}
