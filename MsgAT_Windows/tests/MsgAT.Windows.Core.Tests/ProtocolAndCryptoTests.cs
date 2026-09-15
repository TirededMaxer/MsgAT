using System.Security.Cryptography;
using System.Text;
using MsgAT.Windows.Core.Crypto;
using MsgAT.Windows.Core.Protocol;

namespace MsgAT.Windows.Core.Tests;

public sealed class ProtocolAndCryptoTests
{
    [Fact]
    public void PacketRoundTripPreservesWireFieldsAndPaddingBoundary()
    {
        var packet = new GhostPacket(
            GhostPacket.Message,
            new string('a', 64),
            Enumerable.Range(0, 16).Select(static value => (byte)value).ToArray(),
            Encoding.UTF8.GetBytes("cross-platform payload"),
            Enumerable.Repeat((byte)0x5A, 16).ToArray());

        var encoded = GhostPacketCodec.Encode(packet);
        Assert.Equal(4096, encoded.Length);
        Assert.True(GhostPacketCodec.TryDecode(encoded, out var decoded, out var consumed, out var error));
        Assert.Null(error);
        Assert.Equal(encoded.Length, consumed);
        Assert.NotNull(decoded);
        Assert.Equal(packet.Type, decoded.Type);
        Assert.Equal(packet.TargetMailboxId, decoded.TargetMailboxId);
        Assert.Equal(packet.NonceOrIv, decoded.NonceOrIv);
        Assert.Equal(packet.EncryptedData, decoded.EncryptedData);
        Assert.Equal(packet.AuthTag, decoded.AuthTag);
    }

    [Theory]
    [InlineData(104)]
    [InlineData(262145)]
    [InlineData(-1)]
    public void DecoderRejectsInvalidFrameLengths(int frameLength)
    {
        var bytes = new byte[105];
        System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(bytes, frameLength);
        Assert.False(GhostPacketCodec.TryDecode(bytes, out _, out _, out var error));
        Assert.NotNull(error);
    }

    [Fact]
    public void EcdhEncryptionInteroperatesInBothDirections()
    {
        var aliceSeed = Enumerable.Range(0, 32).Select(static value => (byte)value).ToArray();
        var bobSeed = Enumerable.Range(32, 32).Select(static value => (byte)value).ToArray();
        var alicePublic = CryptoService.GetX25519PublicKeyBase64(aliceSeed);
        var bobPublic = CryptoService.GetX25519PublicKeyBase64(bobSeed);
        var iv = Enumerable.Repeat((byte)7, 16).ToArray();
        var plaintext = Encoding.UTF8.GetBytes("Android/macOS/.NET E2EE parity");

        var encrypted = CryptoService.EncryptEcdh(aliceSeed, bobPublic, iv, plaintext);
        var decrypted = CryptoService.DecryptEcdh(
            bobSeed,
            alicePublic,
            iv,
            encrypted.CipherText,
            encrypted.AuthTag);

        Assert.Equal(plaintext, decrypted);
    }

    [Fact]
    public void GcmRejectsTamperedAuthenticationTag()
    {
        var seed = RandomNumberGenerator.GetBytes(32);
        var peerSeed = RandomNumberGenerator.GetBytes(32);
        var encrypted = CryptoService.EncryptEcdh(
            seed,
            CryptoService.GetX25519PublicKeyBase64(peerSeed),
            new byte[16],
            [1, 2, 3]);
        encrypted.AuthTag[0] ^= 0x80;

        Assert.Throws<CryptographicException>(() => CryptoService.DecryptEcdh(
            peerSeed,
            CryptoService.GetX25519PublicKeyBase64(seed),
            new byte[16],
            encrypted.CipherText,
            encrypted.AuthTag));
    }
}
