// SPDX-License-Identifier: AGPL-3.0-only
using System.Buffers.Binary;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;

namespace MsgAT.Windows.Core.Storage;

public interface IDeviceBoundSecretProtector : ISecretProtector
{
    void DestroyKey();
}

/// <summary>Nonexportable TPM RSA-OAEP key wrapping, with AES-256-GCM vault encryption.</summary>
[SupportedOSPlatform("windows")]
public sealed class TpmSecretProtector : IDeviceBoundSecretProtector
{
    private static readonly CngProvider Provider = new("Microsoft Platform Crypto Provider");
    private readonly string _keyName;

    public TpmSecretProtector(string dataDirectory)
    {
        var path = Path.TrimEndingDirectorySeparator(Path.GetFullPath(dataDirectory)).ToUpperInvariant();
        _keyName = "MsgAT.Identity.v3.RSA." + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(path)));
    }

    public static bool IsDeviceEnvelope(ReadOnlySpan<byte> ciphertext) =>
        ciphertext.Length >= 4 && ciphertext[..4].SequenceEqual("MTR3"u8);

    public void EnsureAvailable(bool requireExistingKey)
    {
        try
        {
            using var key = OpenKey(create: !requireExistingKey);
            using var device = new RSACng(key);
            // Check a real TPM private operation before permitting identity creation.
            // Presence/metadata alone do not establish that the key is usable.
            var challenge = RandomNumberGenerator.GetBytes(32);
            byte[]? opened = null;
            try
            {
                opened = device.Decrypt(WrapKey(device, challenge), RSAEncryptionPadding.OaepSHA256);
                if (!CryptographicOperations.FixedTimeEquals(challenge, opened)) throw new CryptographicException("TPM key validation failed.");
            }
            finally
            {
                CryptographicOperations.ZeroMemory(challenge);
                if (opened is not null) CryptographicOperations.ZeroMemory(opened);
            }
        }
        catch (CryptographicException error)
        {
            throw new InvalidOperationException("MsgAT requires a working TPM with RSA-OAEP/SHA-256 key protection. This device cannot create or unlock the identity; existing identity data was not replaced.", error);
        }
    }

    public byte[] Protect(byte[] plaintext)
    {
        ArgumentNullException.ThrowIfNull(plaintext);
        if (plaintext.Length > 65536) { throw new CryptographicException("Identity vault exceeds its size limit."); }
        using var deviceKey = OpenKey(create: true);
        using var device = new RSACng(deviceKey);
        var key = RandomNumberGenerator.GetBytes(32);
        try
        {
            var wrappedKey = WrapKey(device, key);
            var headerLength = 6 + wrappedKey.Length;
            var envelope = new byte[headerLength + 28 + plaintext.Length];
            "MTR3"u8.CopyTo(envelope);
            BinaryPrimitives.WriteUInt16BigEndian(envelope.AsSpan(4, 2), checked((ushort)wrappedKey.Length));
            wrappedKey.CopyTo(envelope, 6);
            RandomNumberGenerator.Fill(envelope.AsSpan(headerLength, 12));
            using var cipher = new AesGcm(key, 16);
            cipher.Encrypt(envelope.AsSpan(headerLength, 12), plaintext,
                envelope.AsSpan(headerLength + 28), envelope.AsSpan(headerLength + 12, 16),
                envelope.AsSpan(0, headerLength));
            return envelope;
        }
        finally { CryptographicOperations.ZeroMemory(key); }
    }

    public byte[] Unprotect(byte[] ciphertext)
    {
        ArgumentNullException.ThrowIfNull(ciphertext);
        if (ciphertext.Length is < 290 or > 66560 || !ciphertext.AsSpan(0, 4).SequenceEqual("MTR3"u8))
        {
            throw new CryptographicException("Invalid hardware-bound identity envelope.");
        }
        var wrappedLength = BinaryPrimitives.ReadUInt16BigEndian(ciphertext.AsSpan(4, 2));
        var headerLength = 6 + wrappedLength;
        if (wrappedLength is < 256 or > 512 || ciphertext.Length < headerLength + 28)
        {
            throw new CryptographicException("Invalid hardware-bound identity header.");
        }
        using var deviceKey = OpenKey(create: false);
        using var device = new RSACng(deviceKey);
        if (wrappedLength != device.KeySize / 8)
        {
            throw new CryptographicException("Invalid identity envelope key.");
        }
        var key = device.Decrypt(ciphertext.AsSpan(6, wrappedLength).ToArray(), RSAEncryptionPadding.OaepSHA256);
        var plaintext = new byte[ciphertext.Length - headerLength - 28];
        try
        {
            if (key.Length != 32) throw new CryptographicException("Invalid wrapped vault key.");
            using var cipher = new AesGcm(key, 16);
            cipher.Decrypt(ciphertext.AsSpan(headerLength, 12), ciphertext.AsSpan(headerLength + 28),
                ciphertext.AsSpan(headerLength + 12, 16), plaintext, ciphertext.AsSpan(0, headerLength));
            return plaintext;
        }
        catch { CryptographicOperations.ZeroMemory(plaintext); throw; }
        finally { CryptographicOperations.ZeroMemory(key); }
    }

    public void DestroyKey()
    {
        if (!CngKey.Exists(_keyName, Provider)) { return; }
        using var key = CngKey.Open(_keyName, Provider);
        key.Delete();
    }

    private CngKey OpenKey(bool create)
    {
        if (CngKey.Exists(_keyName, Provider))
        {
            var key = CngKey.Open(_keyName, Provider);
            if (key.Algorithm != CngAlgorithm.Rsa || key.KeySize < 2048 || key.ExportPolicy != CngExportPolicies.None)
            {
                key.Dispose();
                throw new CryptographicException("The identity protection key has an invalid algorithm or export policy.");
            }
            return key;
        }
        if (!create) { throw new CryptographicException("This device's identity protection key is missing."); }
        var parameters = new CngKeyCreationParameters
        {
            Provider = Provider,
            ExportPolicy = CngExportPolicies.None,
            KeyUsage = CngKeyUsages.Decryption
        };
        parameters.Parameters.Add(new CngProperty("Length", BitConverter.GetBytes(2048), CngPropertyOptions.None));
        return CngKey.Create(CngAlgorithm.Rsa, _keyName, parameters);
    }

    private static byte[] WrapKey(RSA device, byte[] key)
    {
        // Only the public parameters leave the TPM. Private decryption stays in CNG.
        using var publicKey = RSA.Create();
        publicKey.ImportParameters(device.ExportParameters(includePrivateParameters: false));
        return publicKey.Encrypt(key, RSAEncryptionPadding.OaepSHA256);
    }
}
