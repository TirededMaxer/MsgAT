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

    public static GhostPacket Empty(byte type, string? mailboxId = null) =>
        new(type, mailboxId, new byte[16], [], new byte[16]);
}
