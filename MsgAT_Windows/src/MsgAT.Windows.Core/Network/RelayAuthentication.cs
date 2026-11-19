// SPDX-License-Identifier: AGPL-3.0-only
using System.Security.Cryptography;
using System.Text;
using MsgAT.Windows.Core.Protocol;

namespace MsgAT.Windows.Core.Network;

/// <summary>One authentication attempt on one established TLS stream.</summary>
internal sealed class RelayAuthentication(string mailbox, byte[] endpoint, bool transfer = false)
{
    private static readonly byte[] Domain = Encoding.ASCII.GetBytes("MsgAT/v2/relay-auth\0");
    private readonly byte[] _clientNonce = RandomNumberGenerator.GetBytes(16);
    private bool _challenged;
    internal bool Authenticated { get; private set; }

    internal GhostPacket Request() => new(GhostPacket.AuthRequest, mailbox, _clientNonce, transfer ? "MTB2"u8.ToArray() : "MTA2"u8.ToArray(), new byte[16]);

    internal byte[] Transcript(GhostPacket packet)
    {
        var body = packet.EncryptedData;
        if (_challenged || Authenticated || packet.TargetMailboxId != mailbox || packet.NonceOrIv.Length != 16
            || body.Length != 50 || body[0] != 2 || body[1] != (transfer ? 1 : 0) || endpoint.Length != 32
            || !CryptographicOperations.FixedTimeEquals(body.AsSpan(2, 16), _clientNonce)
            || !CryptographicOperations.FixedTimeEquals(body.AsSpan(18, 32), endpoint))
            throw new InvalidDataException("Invalid authentication challenge.");
        _challenged = true;
        var transcript = new byte[Domain.Length + 98];
        Domain.CopyTo(transcript, 0);
        var offset = Domain.Length;
        transcript[offset++] = 2;
        transcript[offset++] = (byte)(transfer ? 1 : 0);
        Convert.FromHexString(mailbox).CopyTo(transcript, offset); offset += 32;
        _clientNonce.CopyTo(transcript, offset); offset += 16;
        packet.NonceOrIv.CopyTo(transcript, offset); offset += 16;
        endpoint.CopyTo(transcript, offset);
        return transcript;
    }

    internal void Complete(GhostPacket packet)
    {
        if (!_challenged || Authenticated || packet.Type != GhostPacket.OfflineEnd ||
            packet.TargetMailboxId != mailbox)
            throw new InvalidDataException("Unexpected authentication completion.");
        Authenticated = true;
    }
}
