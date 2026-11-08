// SPDX-License-Identifier: AGPL-3.0-only
using System.Security.Cryptography;
using System.Text;

namespace MsgAT.Windows.Core.Crypto;

public static class RootKeyHierarchy
{
    private static readonly byte[] Salt = Encoding.UTF8.GetBytes("MsgAT/v2/root-key-hierarchy");

    public static byte[] Derive(byte[] seed, string purpose)
    {
        ArgumentNullException.ThrowIfNull(seed);
        ArgumentException.ThrowIfNullOrWhiteSpace(purpose);
        if (seed.Length != 32 || purpose.Length > 64 ||
            purpose.Any(static c => c is not (>= 'a' and <= 'z') and not (>= '0' and <= '9') and not '-' and not '/'))
        {
            throw new CryptographicException("Invalid root key derivation context.");
        }
        return HKDF.DeriveKey(HashAlgorithmName.SHA256, seed, 32, Salt, Encoding.UTF8.GetBytes(purpose));
    }

    public static byte[] Wrap(byte[] seed, string purpose, ReadOnlySpan<byte> secret)
    {
        if (secret.Length != 32) { throw new CryptographicException("Invalid data key."); }
        var key = Derive(seed, "wrap/" + purpose);
        try
        {
            var envelope = new byte[60];
            RandomNumberGenerator.Fill(envelope.AsSpan(0, 12));
            using var cipher = new AesGcm(key, 16);
            cipher.Encrypt(envelope.AsSpan(0, 12), secret, envelope.AsSpan(12, 32),
                envelope.AsSpan(44, 16), Encoding.UTF8.GetBytes("MsgAT/v2/wrapped-key/" + purpose));
            return envelope;
        }
        finally { CryptographicOperations.ZeroMemory(key); }
    }

    public static byte[] Unwrap(byte[] seed, string purpose, ReadOnlySpan<byte> envelope)
    {
        if (envelope.Length != 60) { throw new CryptographicException("Invalid wrapped data key."); }
        var key = Derive(seed, "wrap/" + purpose);
        var plaintext = new byte[32];
        try
        {
            using var cipher = new AesGcm(key, 16);
            cipher.Decrypt(envelope[..12], envelope.Slice(12, 32), envelope.Slice(44, 16),
                plaintext, Encoding.UTF8.GetBytes("MsgAT/v2/wrapped-key/" + purpose));
            return plaintext;
        }
        catch { CryptographicOperations.ZeroMemory(plaintext); throw; }
        finally { CryptographicOperations.ZeroMemory(key); }
    }
}
