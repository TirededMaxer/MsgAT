using System.Security.Cryptography;
using System.Runtime.Versioning;
using System.Text;
using System.Text.Json;
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
        try
        {
            return ProtectedData.Unprotect(ciphertext, Entropy, DataProtectionScope.CurrentUser);
        }
        catch (CryptographicException)
        {
            // Legacy Kotlin/JNA vaults used DPAPI without optional entropy.
            return ProtectedData.Unprotect(ciphertext, null, DataProtectionScope.CurrentUser);
        }
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
    public int FormatVersion { get; init; } = 2;
    public required string IdentitySeedBase64 { get; init; }
    public required string DatabaseKeyBase64 { get; init; }
    public string? ServerAddress { get; init; }
    public int ServerPort { get; init; }
    public string? Nickname { get; init; }
    public string? AvatarPath { get; init; }
    public string? TurnServer { get; init; }
    public int TurnPort { get; init; } = 3478;
    public string? TurnUsername { get; init; }
    public string? TurnPassword { get; init; }
    public bool TurnUseTls { get; init; }
    public string? ServerCertificateSha256 { get; init; }
    public bool AllowDevelopmentCertificate { get; init; } = true;
    public bool RelayTlsCompatibilityV1 { get; init; }
}

public sealed class SecureSettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = false
    };

    private readonly object _gate = new();
    private readonly string _vaultPath;
    private readonly ISecretProtector _protector;
    private SecureSettings _settings;

    public SecureSettingsStore(string dataDirectory, ISecretProtector? protector = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(dataDirectory);
        Directory.CreateDirectory(dataDirectory);
        _vaultPath = Path.Combine(dataDirectory, "msgat_vault.sec");
        _protector = protector ?? CreatePlatformProtector();
        _settings = LoadOrCreate();
        if (!_settings.RelayTlsCompatibilityV1)
        {
            _settings = _settings with
            {
                AllowDevelopmentCertificate = true,
                ServerCertificateSha256 = null,
                RelayTlsCompatibilityV1 = true
            };
            SaveLocked();
        }
    }

    public event EventHandler? Changed;

    public SecureSettings Snapshot
    {
        get { lock (_gate) { return _settings with { }; } }
    }

    public byte[] IdentitySeed => Decode32(Snapshot.IdentitySeedBase64, "identity seed");
    public byte[] DatabaseKey => Decode32(Snapshot.DatabaseKeyBase64, "database key");
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
        var newDatabaseKey = RandomNumberGenerator.GetBytes(32);
        lock (_gate)
        {
            _settings = _settings with
            {
                IdentitySeedBase64 = Convert.ToBase64String(newSeed),
                DatabaseKeyBase64 = Convert.ToBase64String(newDatabaseKey),
                Nickname = null,
                AvatarPath = null
            };
            SaveLocked();
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private SecureSettings LoadOrCreate()
    {
        if (!File.Exists(_vaultPath))
        {
            var created = CreateDefaults();
            _settings = created;
            SaveLocked();
            return created;
        }

        try
        {
            var plaintext = _protector.Unprotect(File.ReadAllBytes(_vaultPath));
            try
            {
                var json = JsonSerializer.Deserialize<SecureSettings>(plaintext, JsonOptions);
                if (json is not null && json.FormatVersion == 2)
                {
                    Validate(json);
                    return json;
                }
            }
            catch (JsonException)
            {
                // Try the Java Properties format used by the previous Windows client.
            }

            var migrated = MigrateLegacyProperties(Encoding.Latin1.GetString(plaintext));
            _settings = migrated;
            SaveLocked();
            return migrated;
        }
        catch (Exception exception) when (exception is IOException or CryptographicException or FormatException or JsonException)
        {
            throw new InvalidDataException(
                "The MsgAT identity vault exists but could not be unlocked. Refusing to create a replacement identity.",
                exception);
        }
    }

    private static SecureSettings MigrateLegacyProperties(string source)
    {
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var rawLine in source.Split('\n'))
        {
            var line = rawLine.TrimEnd('\r').Trim();
            if (line.Length == 0 || line[0] is '#' or '!')
            {
                continue;
            }
            var separator = line.IndexOfAny(['=', ':']);
            if (separator <= 0)
            {
                continue;
            }
            values[UnescapeProperty(line[..separator].Trim())] = UnescapeProperty(line[(separator + 1)..].Trim());
        }

        values.TryGetValue("PRIVATE_KEY", out var seed);
        values.TryGetValue("DB_PASSPHRASE", out var databaseKey);
        var migrated = new SecureSettings
        {
            IdentitySeedBase64 = seed ?? Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)),
            DatabaseKeyBase64 = databaseKey ?? Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)),
            ServerAddress = ValueOrNull(values, "server_ip"),
            ServerPort = ParseInt(values, "server_port"),
            Nickname = ValueOrNull(values, "my_nickname"),
            AvatarPath = ValueOrNull(values, "my_avatar_path"),
            TurnServer = ValueOrNull(values, "turn_server"),
            TurnPort = ParseInt(values, "turn_port", 3478),
            TurnUsername = ValueOrNull(values, "turn_username"),
            TurnPassword = ValueOrNull(values, "turn_password"),
            TurnUseTls = ParseBool(values, "turn_use_tls")
        };
        Validate(migrated);
        return migrated;
    }

    private void SaveLocked()
    {
        var plaintext = JsonSerializer.SerializeToUtf8Bytes(_settings, JsonOptions);
        var ciphertext = _protector.Protect(plaintext);
        var temporaryPath = _vaultPath + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            using (var stream = new FileStream(temporaryPath, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                stream.Write(ciphertext);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temporaryPath, _vaultPath, overwrite: true);
        }
        finally
        {
            File.Delete(temporaryPath);
        }
    }

    private static SecureSettings CreateDefaults() => new()
    {
        IdentitySeedBase64 = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)),
        DatabaseKeyBase64 = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
    };

    private static void Validate(SecureSettings settings)
    {
        _ = Decode32(settings.IdentitySeedBase64, "identity seed");
        _ = Decode32(settings.DatabaseKeyBase64, "database key");
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

    private static string? ValueOrNull(Dictionary<string, string> values, string key) =>
        values.TryGetValue(key, out var value) && !string.IsNullOrWhiteSpace(value) ? value : null;

    private static int ParseInt(Dictionary<string, string> values, string key, int fallback = 0) =>
        values.TryGetValue(key, out var value) && int.TryParse(value, out var parsed) ? parsed : fallback;

    private static bool ParseBool(Dictionary<string, string> values, string key) =>
        values.TryGetValue(key, out var value) && bool.TryParse(value, out var parsed) && parsed;

    private static string UnescapeProperty(string value)
    {
        var result = new StringBuilder(value.Length);
        for (var index = 0; index < value.Length; index++)
        {
            if (value[index] != '\\' || ++index >= value.Length)
            {
                result.Append(value[Math.Min(index, value.Length - 1)]);
                continue;
            }
            var escaped = value[index];
            if (escaped == 'u' && index + 4 < value.Length &&
                int.TryParse(value.AsSpan(index + 1, 4), System.Globalization.NumberStyles.HexNumber,
                    System.Globalization.CultureInfo.InvariantCulture, out var scalar))
            {
                result.Append((char)scalar);
                index += 4;
            }
            else
            {
                result.Append(escaped switch { 'n' => '\n', 'r' => '\r', 't' => '\t', 'f' => '\f', _ => escaped });
            }
        }
        return result.ToString();
    }

    private static DpapiSecretProtector CreatePlatformProtector()
    {
        if (!OperatingSystem.IsWindows())
        {
            throw new PlatformNotSupportedException("The production settings vault requires Windows DPAPI.");
        }
        return new DpapiSecretProtector();
    }
}
