namespace MsgAT.Windows.Core.Protocol;

public sealed record GhostPacket(
    byte Type,
    string? TargetMailboxId,
    byte[] NonceOrIv,
    byte[] EncryptedData,
    byte[] AuthTag)
{
    public const int MaxPacketSize = 256 * 1024;

    public const byte AuthRequest = 0x00;
    public const byte AuthChallenge = 0x01;
    public const byte AuthResponse = 0x02;
    public const byte Message = 0x03;
    public const byte Heartbeat = 0x04;
    public const byte Ack = 0x05;
    public const byte OfflineEnd = 0x06;
    public const byte SearchUser = 0x07;
    public const byte UpdateProfile = 0x08;
    public const byte GetProfile = 0x0C;
    public const byte ProfileResponse = 0x0D;
    public const byte FriendAccepted = 0x0E;
    public const byte FriendDeleted = 0x0F;
    public const byte RequestRevoked = 0x10;
    public const byte AccountReset = 0x11;
    public const byte DeleteAccount = 0x12;
    public const byte CallOffer = 0x13;
    public const byte CallAnswer = 0x14;
    public const byte CallIce = 0x15;
    public const byte CallReject = 0x16;
    public const byte CallEnd = 0x17;
    public const byte CallBusy = 0x18;
    public const byte CallSync = 0x19;
    public const byte V2Submit = 0x40;
    public const byte V2KeyPublish = 0x41;
    public const byte V2KeyPublished = 0x42;
    public const byte V2KeyClaim = 0x43;
    public const byte V2KeyResponse = 0x44;
    public const byte V2Stored = 0x45;
    public const byte V2Delivery = 0x46;
    public const byte V2Received = 0x47;
    public const byte V2Sync = 0x48;
    public const byte V2SyncEnd = 0x49;
    public const byte V2Revoke = 0x4A;
    public const byte V2Revoked = 0x4B;
    public const byte V2Rejected = 0x4C;
    public const byte V2Deferred = 0x4F;
    public const byte ObjectBegin = 0x50;
    public const byte ObjectStatus = 0x51;
    public const byte ObjectPut = 0x52;
    public const byte ObjectFinish = 0x53;
    public const byte ObjectGet = 0x54;
    public const byte ObjectData = 0x55;
    public const byte ObjectDelete = 0x56;
    public const byte ObjectDeleted = 0x57;
    public const byte ObjectError = 0x58;

    public static GhostPacket Empty(byte type, string? mailboxId = null) =>
        new(type, mailboxId, new byte[16], [], new byte[16]);
}
