using System.Security.Cryptography;
using System.Runtime.Versioning;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using MsgAT.Windows.Core.Crypto;

namespace MsgAT.Windows.Core.Storage;

public interface ISecretProtector
{
    byte[] Protect(byte[] plaintext);
    byte[] Unprotect(byte[] ciphertext);
}

[SupportedOSPlatform("windows")]
public sealed class DpapiSecretProtector : ISecretProtector
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("MsgAT.Windows.v2");

    public byte[] Protect(byte[] plaintext)
    {
        EnsureWindows();
        return ProtectedData.Protect(plaintext, Entropy, DataProtectionScope.CurrentUser);
    }

    public byte[] Unprotect(byte[] ciphertext)
    {
        EnsureWindows();
        return ProtectedData.Unprotect(ciphertext, Entropy, DataProtectionScope.CurrentUser);
    }

    private static void EnsureWindows()
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("DPAPI secure settings are available only on Windows.");
        }
    }
}

public sealed record SecureSettings
{
    public int FormatVersion { get; init; } = 3;
    public required string IdentitySeedBase64 { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? DatabaseKeyBase64 { get; init; }
    public string? DatabaseKeyEnvelopeBase64 { get; init; }
    public string? ServerAddress { get; init; }
    public int ServerPort { get; init; } = 34478;
    public string? Nickname { get; init; }
    public string? AvatarPath { get; init; }
    public string? TurnServer { get; init; }
    public int TurnPort { get; init; } = 3478;
    public string? TurnUsername { get; init; }
    public string? TurnPassword { get; init; }
    public bool TurnUseTls { get; init; }
    public string? ServerCertificateSha256 { get; init; }
    public bool AllowDevelopmentCertificate { get; init; }
    public bool RelayTlsCompatibilityV1 { get; init; }
    public bool RelayTlsSecurityV2 { get; init; }

    public override string ToString() => $"MsgAT settings (format {FormatVersion}; secrets redacted)";
}

public sealed class SecureSettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = false
    };

    private readonly object _gate = new();
    private readonly string _vaultPath;
    private readonly string _identityMarkerPath;
    private readonly string _hardwareMarkerPath;
    private readonly ISecretProtector _protector;
    private SecureSettings _settings;

    public SecureSettingsStore(string dataDirectory, ISecretProtector? protector = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(dataDirectory);
        Directory.CreateDirectory(dataDirectory);
        _vaultPath = Path.Combine(dataDirectory, "msgat_vault.sec");
        _identityMarkerPath = Path.Combine(dataDirectory, "identity.initialized");
        _hardwareMarkerPath = Path.Combine(dataDirectory, "identity.hardware-v2");
        _protector = protector ?? CreatePlatformProtector(dataDirectory, _vaultPath);
        _settings = LoadOrCreate();
        if (!_settings.RelayTlsSecurityV2)
        {
            _settings = _settings with
            {
                AllowDevelopmentCertificate = false,
                RelayTlsSecurityV2 = true
            };
            SaveLocked();
        }
        WriteIdentityMarker();
    }

    public event EventHandler? Changed;

    public SecureSettings Snapshot
    {
        get { lock (_gate) { return _settings with { }; } }
    }

    public byte[] IdentitySeed => Decode32(Snapshot.IdentitySeedBase64, "identity seed");
    public byte[] DatabaseKey
    {
        get
        {
            var snapshot = Snapshot;
            var seed = Decode32(snapshot.IdentitySeedBase64, "identity seed");
            try
            {
                return RootKeyHierarchy.Unwrap(seed, "database",
                    Convert.FromBase64String(snapshot.DatabaseKeyEnvelopeBase64 ??
                        throw new InvalidDataException("The wrapped database key is missing.")));
            }
            finally { CryptographicOperations.ZeroMemory(seed); }
        }
    }
    public string MyUuid => CryptoService.CalculateUuid(IdentitySeed);
    public string MyMailboxId => CryptoService.CalculateMailboxId(MyUuid);
    public string MyX25519PublicKeyBase64 => CryptoService.GetX25519PublicKeyBase64(IdentitySeed);
    public bool IsConfigured => !string.IsNullOrWhiteSpace(Snapshot.ServerAddress) && Snapshot.ServerPort is > 0 and <= 65535;

    public void Update(Func<SecureSettings, SecureSettings> update)
    {
        ArgumentNullException.ThrowIfNull(update);
        lock (_gate)
        {
            var updated = update(_settings) ?? throw new InvalidOperationException("Settings update returned null.");
            if (updated.IdentitySeedBase64 != _settings.IdentitySeedBase64 ||
                updated.DatabaseKeyEnvelopeBase64 != _settings.DatabaseKeyEnvelopeBase64)
            {
                throw new InvalidOperationException("Identity keys can only be replaced by Reset Identity.");
            }
            Validate(updated);
            _settings = updated;
            SaveLocked();
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void SetInitialIdentity(string nickname, string avatarPath)
    {
        if (string.IsNullOrWhiteSpace(nickname))
        {
            throw new ArgumentException("Nickname is required.", nameof(nickname));
        }
        lock (_gate)
        {
            if (!string.IsNullOrWhiteSpace(_settings.Nickname))
            {
                throw new InvalidOperationException("Identity profile is immutable after initial setup.");
            }
            _settings = _settings with { Nickname = nickname.Trim(), AvatarPath = avatarPath };
            SaveLocked();
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void RotateIdentity()
    {
        var newSeed = RandomNumberGenerator.GetBytes(32);
        lock (_gate)
        {
            var replacement = CreateDefaults(newSeed);
            if (_protector is IDeviceBoundSecretProtector device) device.DestroyKey();
            _settings = _settings with
            {
                FormatVersion = 3,
                IdentitySeedBase64 = replacement.IdentitySeedBase64,
                DatabaseKeyBase64 = null,
                DatabaseKeyEnvelopeBase64 = replacement.DatabaseKeyEnvelopeBase64,
                Nickname = null,
                AvatarPath = null
            };
            SaveLocked();
        }
        CryptographicOperations.ZeroMemory(newSeed);
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private SecureSettings LoadOrCreate()
    {
        if (!File.Exists(_vaultPath))
        {
            if (File.Exists(_identityMarkerPath))
            {
                throw new InvalidDataException("The identity vault is missing. Refusing to silently replace the identity.");
            }
            var created = CreateDefaults();
            _settings = created;
            SaveLocked();
            return created;
        }

        try
        {
            if (new FileInfo(_vaultPath).Length > 66560) throw new InvalidDataException("Identity vault exceeds its size limit.");
            var sealedBytes = File.ReadAllBytes(_vaultPath);
            var migrateDeviceProtection = _protector is IDeviceBoundSecretProtector && !sealedBytes.AsSpan(0, Math.Min(4,sealedBytes.Length)).SequenceEqual("MTR3"u8);
            if (migrateDeviceProtection && File.Exists(_hardwareMarkerPath))
            {
                throw new InvalidDataException("Refusing to downgrade the hardware-protected identity vault.");
            }
            var plaintext = migrateDeviceProtection ? UnprotectLegacyOnWindows(sealedBytes) : _protector.Unprotect(sealedBytes);
            try
            {
                var json = JsonSerializer.Deserialize<SecureSettings>(plaintext, JsonOptions);
                if (json is not null && json.FormatVersion is 2 or 3)
                {
                    var upgradeFormat = json.FormatVersion == 2;
                    if (json.FormatVersion == 2)
                    {
                        var seed = Decode32(json.IdentitySeedBase64, "identity seed");
                        try
                        {
                            var replacement = CreateDefaults(seed);
                            json = json with
                            {
                                FormatVersion = 3,
                                DatabaseKeyBase64 = null,
                                DatabaseKeyEnvelopeBase64 = replacement.DatabaseKeyEnvelopeBase64
                            };
                        }
                        finally { CryptographicOperations.ZeroMemory(seed); }
                    }
                    Validate(json);
                    if (migrateDeviceProtection || upgradeFormat)
                    {
                        _settings = json;
                        SaveLocked();
                    }
                    return json;
                }
            }
            catch (JsonException)
            {
                throw new InvalidDataException("The identity vault is not a supported JSON vault.");
            }
            finally { CryptographicOperations.ZeroMemory(plaintext); }
            throw new InvalidDataException("Unsupported identity vault version. Refusing identity replacement.");
        }
        catch (Exception exception) when (exception is IOException or CryptographicException or FormatException or JsonException)
        {
            throw new InvalidDataException(
                "The MsgAT identity vault exists but could not be unlocked. Refusing to create a replacement identity.",
                exception);
        }
    }

    private void SaveLocked()
    {
        var plaintext = JsonSerializer.SerializeToUtf8Bytes(_settings, JsonOptions);
        var temporaryPath = _vaultPath + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            var ciphertext = _protector.Protect(plaintext);
            using (var stream = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                stream.Write(ciphertext);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temporaryPath, _vaultPath, overwrite: true);
            if (_protector is IDeviceBoundSecretProtector)
            {
                using var marker = new FileStream(_hardwareMarkerPath, FileMode.OpenOrCreate, FileAccess.Write, FileShare.Read);
                marker.Write("TPM device envelope v2"u8);
                marker.Flush(flushToDisk: true);
            }
        }
        finally
        {
            File.Delete(temporaryPath);
            CryptographicOperations.ZeroMemory(plaintext);
        }
    }

    private static SecureSettings CreateDefaults()
    {
        var seed = RandomNumberGenerator.GetBytes(32);
        try { return CreateDefaults(seed); }
        finally { CryptographicOperations.ZeroMemory(seed); }
    }

    private static SecureSettings CreateDefaults(byte[] seed)
    {
        var databaseKey = RandomNumberGenerator.GetBytes(32);
        try
        {
            return new SecureSettings
            {
                IdentitySeedBase64 = Convert.ToBase64String(seed),
                DatabaseKeyEnvelopeBase64 = Convert.ToBase64String(RootKeyHierarchy.Wrap(seed, "database", databaseKey))
            };
        }
        finally { CryptographicOperations.ZeroMemory(databaseKey); }
    }

    private static void Validate(SecureSettings settings)
    {
        if (settings.FormatVersion != 3)
        {
            throw new InvalidDataException("Unsupported secure settings version.");
        }
        var seed = Decode32(settings.IdentitySeedBase64, "identity seed");
        try
        {
            var key = RootKeyHierarchy.Unwrap(seed, "database",
                Convert.FromBase64String(settings.DatabaseKeyEnvelopeBase64 ?? throw new InvalidDataException("Missing database key envelope.")));
            CryptographicOperations.ZeroMemory(key);
        }
        finally { CryptographicOperations.ZeroMemory(seed); }
        if (settings.ServerPort is < 0 or > 65535 || settings.TurnPort is < 0 or > 65535)
        {
            throw new InvalidDataException("A stored network port is outside the valid range.");
        }
    }

    private static byte[] Decode32(string value, string label)
    {
        byte[] decoded;
        try { decoded = Convert.FromBase64String(value); }
        catch (FormatException exception) { throw new InvalidDataException($"Invalid {label} encoding.", exception); }
        return decoded.Length == 32
            ? decoded
            : throw new InvalidDataException($"Invalid {label} length.");
    }

    private void WriteIdentityMarker()
    {
        using var marker = new FileStream(_identityMarkerPath, FileMode.OpenOrCreate, FileAccess.Write, FileShare.Read);
        marker.Write("MsgAT identity initialized v2"u8);
        marker.Flush(flushToDisk: true);
    }

    private static TpmSecretProtector CreatePlatformProtector(string dataDirectory, string vaultPath)
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("The production settings vault requires Windows TPM protection.");
        }
        var protector = new TpmSecretProtector(dataDirectory);
        var existing = new byte[4];
        if (File.Exists(vaultPath))
        {
            using var stream = File.OpenRead(vaultPath);
            if (stream.Length > 66560) throw new InvalidDataException("Identity vault exceeds its size limit.");
            _ = stream.ReadAtLeast(existing, existing.Length, throwOnEndOfStream: false);
        }
        protector.EnsureAvailable(TpmSecretProtector.IsDeviceEnvelope(existing));
        return protector;
    }

    private static byte[] UnprotectLegacyOnWindows(byte[] ciphertext)
    {
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("Legacy vault migration is Windows-only.");
        return new DpapiSecretProtector().Unprotect(ciphertext);
    }
}
