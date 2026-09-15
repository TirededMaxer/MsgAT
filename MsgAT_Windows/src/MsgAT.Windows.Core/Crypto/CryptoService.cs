using System.Security.Cryptography;
using System.Text;
using Org.BouncyCastle.Crypto.Agreement;
using Org.BouncyCastle.Crypto.Digests;
using Org.BouncyCastle.Crypto.Engines;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Modes;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;

namespace MsgAT.Windows.Core.Crypto;

public static class CryptoService
{
    private static readonly byte[] E2EeInfo = Encoding.UTF8.GetBytes("MSGAT_E2EE");

    public static string CalculateMailboxId(string uuid)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(uuid);
        var digest = Digest(new Blake2bDigest(512), Encoding.UTF8.GetBytes(uuid));
        return Convert.ToHexStringLower(digest.AsSpan(0, 32));
    }

    public static string CalculateUuid(byte[] seed)
    {
        var publicKey = GetEd25519PrivateKey(seed).GeneratePublicKey().GetEncoded();
        return Convert.ToHexStringLower(SHA256.HashData(publicKey));
    }

    public static string GetX25519PublicKeyBase64(byte[] seed) =>
        Convert.ToBase64String(GetX25519PrivateKey(seed).GeneratePublicKey().GetEncoded());

    public static (byte[] PublicKey, byte[] Signature) SignChallenge(byte[] seed, byte[] nonce)
    {
        var privateKey = GetEd25519PrivateKey(seed);
        var signer = new Ed25519Signer();
        signer.Init(true, privateKey);
        signer.BlockUpdate(nonce, 0, nonce.Length);
        return (privateKey.GeneratePublicKey().GetEncoded(), signer.GenerateSignature());
    }

    public static (byte[] CipherText, byte[] AuthTag) EncryptEcdh(
        byte[] seed,
        string peerPublicKeyBase64,
        byte[] iv,
        byte[] plaintext) =>
        Encrypt(GetEcdhKey(seed, peerPublicKeyBase64), iv, plaintext);

    public static byte[] DecryptEcdh(
        byte[] seed,
        string peerPublicKeyBase64,
        byte[] iv,
        byte[] cipherText,
        byte[] authTag) =>
        Decrypt(GetEcdhKey(seed, peerPublicKeyBase64), iv, cipherText, authTag);

    public static (byte[] CipherText, byte[] AuthTag) EncryptFallback(
        string firstUuid,
        string secondUuid,
        byte[] iv,
        byte[] plaintext) =>
        Encrypt(GetFallbackKey(firstUuid, secondUuid), iv, plaintext);

    public static byte[] DecryptFallback(
        string firstUuid,
        string secondUuid,
        byte[] iv,
        byte[] cipherText,
        byte[] authTag) =>
        Decrypt(GetFallbackKey(firstUuid, secondUuid), iv, cipherText, authTag);

    private static Ed25519PrivateKeyParameters GetEd25519PrivateKey(byte[] seed)
    {
        ValidateSeed(seed);
        return new Ed25519PrivateKeyParameters(seed, 0);
    }

    private static X25519PrivateKeyParameters GetX25519PrivateKey(byte[] seed)
    {
        ValidateSeed(seed);
        return new X25519PrivateKeyParameters(seed, 0);
    }

    private static byte[] GetFallbackKey(string firstUuid, string secondUuid)
    {
        var values = new[] { firstUuid, secondUuid };
        Array.Sort(values, StringComparer.Ordinal);
        return Digest(
            new Blake2bDigest(256),
            Encoding.UTF8.GetBytes($"{values[0]}|MSGAT|{values[1]}"));
    }

    private static byte[] GetEcdhKey(byte[] seed, string peerPublicKeyBase64)
    {
        var peerBytes = Convert.FromBase64String(peerPublicKeyBase64.Trim());
        if (peerBytes.Length != X25519PublicKeyParameters.KeySize)
        {
            throw new CryptographicException("Peer X25519 public key has an invalid length.");
        }

        var agreement = new X25519Agreement();
        agreement.Init(GetX25519PrivateKey(seed));
        var sharedSecret = new byte[agreement.AgreementSize];
        agreement.CalculateAgreement(new X25519PublicKeyParameters(peerBytes, 0), sharedSecret, 0);

        var hkdf = new HkdfBytesGenerator(new Sha256Digest());
        hkdf.Init(new HkdfParameters(sharedSecret, [], E2EeInfo));
        var key = new byte[32];
        hkdf.GenerateBytes(key, 0, key.Length);
        CryptographicOperations.ZeroMemory(sharedSecret);
        return key;
    }

    private static (byte[] CipherText, byte[] AuthTag) Encrypt(byte[] key, byte[] iv, byte[] plaintext)
    {
        ValidateAesInput(key, iv);
        var cipher = new GcmBlockCipher(new AesEngine());
        cipher.Init(true, new AeadParameters(new KeyParameter(key), 128, iv));
        var output = new byte[cipher.GetOutputSize(plaintext.Length)];
        var length = cipher.ProcessBytes(plaintext, 0, plaintext.Length, output, 0);
        length += cipher.DoFinal(output, length);
        if (length < 16)
        {
            throw new CryptographicException("AES-GCM produced an invalid result.");
        }
        var cipherLength = length - 16;
        return (output.AsSpan(0, cipherLength).ToArray(), output.AsSpan(cipherLength, 16).ToArray());
    }

    private static byte[] Decrypt(byte[] key, byte[] iv, byte[] cipherText, byte[] authTag)
    {
        ValidateAesInput(key, iv);
        if (authTag.Length != 16)
        {
            throw new CryptographicException("AES-GCM authentication tag must be 16 bytes.");
        }

        var combined = new byte[cipherText.Length + authTag.Length];
        cipherText.CopyTo(combined, 0);
        authTag.CopyTo(combined, cipherText.Length);
        var cipher = new GcmBlockCipher(new AesEngine());
        cipher.Init(false, new AeadParameters(new KeyParameter(key), 128, iv));
        var output = new byte[cipher.GetOutputSize(combined.Length)];
        try
        {
            var length = cipher.ProcessBytes(combined, 0, combined.Length, output, 0);
            length += cipher.DoFinal(output, length);
            return output.AsSpan(0, length).ToArray();
        }
        catch (Org.BouncyCastle.Crypto.InvalidCipherTextException exception)
        {
            throw new CryptographicException("AES-GCM authentication failed.", exception);
        }
    }

    private static byte[] Digest(Blake2bDigest digest, byte[] input)
    {
        digest.BlockUpdate(input, 0, input.Length);
        var output = new byte[digest.GetDigestSize()];
        digest.DoFinal(output, 0);
        return output;
    }

    private static void ValidateSeed(byte[] seed)
    {
        if (seed.Length != 32)
        {
            throw new CryptographicException("MsgAT identity seed must be exactly 32 bytes.");
        }
    }

    private static void ValidateAesInput(byte[] key, byte[] iv)
    {
        if (key.Length != 32)
        {
            throw new CryptographicException("AES-256 key must be exactly 32 bytes.");
        }
        if (iv.Length != 16)
        {
            throw new CryptographicException("MsgAT AES-GCM IV must be exactly 16 bytes.");
        }
    }
}
