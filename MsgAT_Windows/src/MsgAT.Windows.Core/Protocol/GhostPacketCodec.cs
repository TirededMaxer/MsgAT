using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace MsgAT.Windows.Core.Protocol;

public static class GhostPacketCodec
{
    public const int MinimumFrameSize = 105;
    public const int MaximumFrameSize = 256 * 1024;
    private const int PaddedBoundary = 4096;

    public static byte[] Encode(GhostPacket packet)
    {
        ArgumentNullException.ThrowIfNull(packet);
        if (packet.NonceOrIv.Length != 16)
        {
            throw new ArgumentException("Nonce/IV must be exactly 16 bytes.", nameof(packet));
        }
        if (packet.AuthTag.Length != 16)
        {
            throw new ArgumentException("Authentication tag must be exactly 16 bytes.", nameof(packet));
        }

        var mailbox = string.IsNullOrEmpty(packet.TargetMailboxId)
            ? []
            : Encoding.UTF8.GetBytes(packet.TargetMailboxId);
        if (mailbox.Length > 64)
        {
            throw new ArgumentException("Mailbox ID exceeds the 64-byte protocol field.", nameof(packet));
        }

        var actualSize = MinimumFrameSize + packet.EncryptedData.Length;
        var frameSize = actualSize <= PaddedBoundary ? PaddedBoundary : actualSize;
        if (frameSize is < MinimumFrameSize or > MaximumFrameSize)
        {
            throw new ArgumentOutOfRangeException(nameof(packet), "Encoded packet exceeds protocol limits.");
        }

        var frame = GC.AllocateUninitializedArray<byte>(frameSize);
        BinaryPrimitives.WriteInt32BigEndian(frame.AsSpan(0, 4), frameSize);
        frame[4] = packet.Type;
        frame.AsSpan(5, 64).Clear();
        mailbox.CopyTo(frame.AsSpan(5));
        packet.NonceOrIv.CopyTo(frame, 69);
        BinaryPrimitives.WriteInt32BigEndian(frame.AsSpan(85, 4), packet.EncryptedData.Length);
        packet.EncryptedData.CopyTo(frame, 89);
        packet.AuthTag.CopyTo(frame, 89 + packet.EncryptedData.Length);

        var padding = frame.AsSpan(actualSize);
        if (!padding.IsEmpty)
        {
            RandomNumberGenerator.Fill(padding);
        }
        return frame;
    }

    public static bool TryDecode(
        ReadOnlySpan<byte> source,
        out GhostPacket? packet,
        out int consumed,
        out string? protocolError)
    {
        packet = null;
        consumed = 0;
        protocolError = null;
        if (source.Length < 4)
        {
            return false;
        }

        var frameSize = BinaryPrimitives.ReadInt32BigEndian(source[..4]);
        if (frameSize is < MinimumFrameSize or > MaximumFrameSize)
        {
            protocolError = $"Invalid frame length: {frameSize}.";
            return false;
        }
        if (source.Length < frameSize)
        {
            return false;
        }

        var payloadLength = BinaryPrimitives.ReadInt32BigEndian(source.Slice(85, 4));
        if (payloadLength < 0 || payloadLength > frameSize - MinimumFrameSize)
        {
            protocolError = $"Invalid payload length: {payloadLength}.";
            return false;
        }

        var mailboxBytes = source.Slice(5, 64);
        var mailboxLength = mailboxBytes.IndexOf((byte)0);
        if (mailboxLength < 0)
        {
            mailboxLength = mailboxBytes.Length;
        }

        packet = new GhostPacket(
            source[4],
            Encoding.UTF8.GetString(mailboxBytes[..mailboxLength]),
            source.Slice(69, 16).ToArray(),
            source.Slice(89, payloadLength).ToArray(),
            source.Slice(89 + payloadLength, 16).ToArray());
        consumed = frameSize;
        return true;
    }
}
