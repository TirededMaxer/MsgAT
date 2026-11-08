// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.Versioning;
using System.Buffers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MsgAT.Windows.Core.Crypto;

namespace MsgAT.Windows.Core.Storage;

/// <summary>Read-only compatibility for an existing local V1 vault, never new identities.</summary>
[SupportedOSPlatform("windows")]
internal static class LegacyDpapiVault
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("MsgAT.Windows.v2");
    public static byte[] Open(byte[] ciphertext) => ProtectedData.Unprotect(ciphertext,Entropy,DataProtectionScope.CurrentUser);
}

/// <summary>Ordinary settings only. Identity secrets never enter UI snapshots.</summary>
public sealed record SecureSettings
{
    public int FormatVersion { get; init; } = 3;
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
    private const int MaximumVaultBytes = 66560;
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private readonly object _gate = new();
    private readonly string _vaultPath;
    private readonly string _identityMarkerPath;
    private readonly string _hardwareMarkerPath;
    private readonly TpmSecretProtector _protector;
    // The only long-lived identity cache is authenticated hardware ciphertext.
    private byte[] _sealedVault = [];
    private SecureSettings _settings = new();
    private string _uuid = "";

    public SecureSettingsStore(string dataDirectory)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(dataDirectory);
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("The identity vault requires Windows TPM protection.");
        Directory.CreateDirectory(dataDirectory);
        _vaultPath = Path.Combine(dataDirectory,"msgat_vault.sec");
        _identityMarkerPath = Path.Combine(dataDirectory,"identity.initialized");
        _hardwareMarkerPath = Path.Combine(dataDirectory,"identity.hardware-v2");
        _protector = new TpmSecretProtector(dataDirectory);
        LoadOrCreate(dataDirectory);
        WriteMarker(_identityMarkerPath,"MsgAT identity initialized v2"u8);
    }

    public event EventHandler? Changed;
    public SecureSettings Snapshot { get { lock (_gate) return _settings with { }; } }
    public string MyUuid { get { lock (_gate) return _uuid; } }
    public string MyMailboxId => CryptoService.CalculateMailboxId(MyUuid);
    public bool IsConfigured
    {
        get { var settings=Snapshot; return !string.IsNullOrWhiteSpace(settings.ServerAddress) && settings.ServerPort is > 0 and <= 65535; }
    }

    public byte[] DatabaseKey => OpenIdentity((seed,envelope) => RootKeyHierarchy.Unwrap(seed,"database",envelope));

    internal T WithRootSeed<T>(Func<byte[],T> operation)
    {
        ArgumentNullException.ThrowIfNull(operation);
        return OpenIdentity((seed,_) => operation(seed));
    }

    public void Update(Func<SecureSettings,SecureSettings> update)
    {
        ArgumentNullException.ThrowIfNull(update);
        lock (_gate)
        {
            var updated=update(_settings) ?? throw new InvalidOperationException("Settings update returned null.");
            ValidateSettings(updated);
            OpenIdentity((seed,envelope) => { Save(updated,seed,envelope); return true; });
        }
        Changed?.Invoke(this,EventArgs.Empty);
    }

    public void SetInitialIdentity(string nickname,string avatarPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(nickname);
        lock (_gate)
        {
            if (!string.IsNullOrWhiteSpace(_settings.Nickname)) throw new InvalidOperationException("Identity profile is already initialized.");
            var settings=_settings with { Nickname=nickname.Trim(),AvatarPath=avatarPath };
            OpenIdentity((seed,envelope) => { Save(settings,seed,envelope); return true; });
        }
        Changed?.Invoke(this,EventArgs.Empty);
    }

    public void RotateIdentity()
    {
        lock (_gate)
        {
            var seed=RandomNumberGenerator.GetBytes(32);
            var key=RandomNumberGenerator.GetBytes(32);
            try
            {
                var settings=_settings with { Nickname=null,AvatarPath=null };
                var envelope=RootKeyHierarchy.Wrap(seed,"database",key);
                _protector.DestroyKey();
                _protector.EnsureAvailable(requireExistingKey:false);
                Save(settings,seed,envelope);
                _uuid=CryptoService.CalculateUuid(seed);
            }
            finally { CryptographicOperations.ZeroMemory(seed); CryptographicOperations.ZeroMemory(key); }
        }
        Changed?.Invoke(this,EventArgs.Empty);
    }

    private T OpenIdentity<T>(Func<byte[],byte[],T> operation)
    {
        lock (_gate)
        {
            var plaintext=_protector.Unprotect(_sealedVault);
            byte[]? seed=null;
            byte[]? envelope=null;
            try
            {
                using var document=ReadDocument(plaintext);
                seed=ReadSeed(document.RootElement);
                envelope=ReadEnvelope(document.RootElement);
                return operation(seed,envelope);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(plaintext);
                if (seed is not null) CryptographicOperations.ZeroMemory(seed);
                if (envelope is not null) CryptographicOperations.ZeroMemory(envelope);
            }
        }
    }

    private void LoadOrCreate(string dataDirectory)
    {
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("The identity vault requires Windows TPM protection.");
        var exists=File.Exists(_vaultPath);
        var initialized=File.Exists(_identityMarkerPath) || File.Exists(_hardwareMarkerPath)
            || File.Exists(Path.Combine(dataDirectory,"msgat_v2_vault.db"));
        if (!exists)
        {
            if (initialized) throw new InvalidDataException("The identity vault is missing. Refusing to replace the identity.");
            _protector.EnsureAvailable(requireExistingKey:false);
            var seed=RandomNumberGenerator.GetBytes(32);
            var key=RandomNumberGenerator.GetBytes(32);
            try
            {
                Save(new SecureSettings { RelayTlsSecurityV2=true },seed,RootKeyHierarchy.Wrap(seed,"database",key));
                _uuid=CryptoService.CalculateUuid(seed);
            }
            finally { CryptographicOperations.ZeroMemory(seed); CryptographicOperations.ZeroMemory(key); }
            return;
        }
        if (new FileInfo(_vaultPath).Length>MaximumVaultBytes) throw new InvalidDataException("Identity vault exceeds its size limit.");
        var ciphertext=File.ReadAllBytes(_vaultPath);
        var hardware=TpmSecretProtector.IsDeviceEnvelope(ciphertext);
        if (!hardware && File.Exists(_hardwareMarkerPath)) throw new InvalidDataException("Refusing to downgrade the hardware-protected identity vault.");
        // Never create a replacement TPM key when an existing hardware vault needs one.
        _protector.EnsureAvailable(requireExistingKey:hardware);
        var plaintext=hardware ? _protector.Unprotect(ciphertext) : LegacyDpapiVault.Open(ciphertext);
        byte[]? seedBytes=null;
        byte[]? databaseEnvelope=null;
        try
        {
            using var document=ReadDocument(plaintext);
            var root=document.RootElement;
            var settings=root.Deserialize<SecureSettings>(JsonOptions) ?? throw new InvalidDataException("Invalid settings vault.");
            if (settings.FormatVersion is not (2 or 3)) throw new InvalidDataException("Unsupported identity vault version.");
            seedBytes=ReadSeed(root);
            if (settings.FormatVersion==2)
            {
                // V1's database is not reused by V2. The root itself is preserved.
                var databaseKey=RandomNumberGenerator.GetBytes(32);
                try { databaseEnvelope=RootKeyHierarchy.Wrap(seedBytes,"database",databaseKey); }
                finally { CryptographicOperations.ZeroMemory(databaseKey); }
            }
            else databaseEnvelope=ReadEnvelope(root);
            settings=settings with { FormatVersion=3 };
            var resetTls=!settings.RelayTlsSecurityV2;
            if (resetTls) settings=settings with { AllowDevelopmentCertificate=false,RelayTlsSecurityV2=true };
            ValidateSettings(settings);
            var opened=RootKeyHierarchy.Unwrap(seedBytes,"database",databaseEnvelope);
            CryptographicOperations.ZeroMemory(opened);
            _uuid=CryptoService.CalculateUuid(seedBytes);
            if (!hardware || resetTls || root.GetProperty("formatVersion").GetInt32()==2) Save(settings,seedBytes,databaseEnvelope);
            else { _settings=settings; _sealedVault=ciphertext; WriteMarker(_hardwareMarkerPath,"TPM device envelope v2"u8); }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(plaintext);
            if (seedBytes is not null) CryptographicOperations.ZeroMemory(seedBytes);
            if (databaseEnvelope is not null) CryptographicOperations.ZeroMemory(databaseEnvelope);
        }
    }

    private void Save(SecureSettings settings,byte[] seed,byte[] envelope)
    {
        ValidateSettings(settings);
        var memory=new ArrayBufferWriter<byte>(65536);
        var temporaryPath=_vaultPath+".tmp-"+Guid.NewGuid().ToString("N");
        try
        {
            using (var writer=new Utf8JsonWriter(memory))
            {
                writer.WriteStartObject();
                foreach (var property in JsonSerializer.SerializeToElement(settings,JsonOptions).EnumerateObject()) property.WriteTo(writer);
                // Reserve room before writing secrets, avoiding growth/copies of
                // a buffer after root material has entered it.
                if (writer.BytesCommitted+writer.BytesPending>64512) throw new InvalidDataException("Identity settings exceed their size limit.");
                // Keep the on-disk v3 shape so ordinary upgrades preserve existing data.
                // Base64 is written directly from bytes, with no cached seed string.
                writer.WriteBase64String("identitySeedBase64",seed);
                writer.WriteBase64String("databaseKeyEnvelopeBase64",envelope);
                writer.WriteEndObject();
            }
            if (memory.WrittenCount>65536) throw new InvalidDataException("Identity vault exceeds its size limit.");
            var plaintext=memory.WrittenSpan.ToArray();
            byte[] ciphertext;
            try { ciphertext=_protector.Protect(plaintext); }
            finally { CryptographicOperations.ZeroMemory(plaintext); }
            using (var stream=new FileStream(temporaryPath,FileMode.CreateNew,FileAccess.Write,FileShare.None))
            {
                stream.Write(ciphertext);
                stream.Flush(flushToDisk:true);
            }
            File.Move(temporaryPath,_vaultPath,overwrite:true);
            _sealedVault=ciphertext;
            _settings=settings;
            WriteMarker(_hardwareMarkerPath,"TPM device envelope v2"u8);
        }
        finally
        {
            memory.Clear();
            File.Delete(temporaryPath);
        }
    }

    private static JsonDocument ReadDocument(byte[] plaintext)
    {
        var document=JsonDocument.Parse(plaintext,new JsonDocumentOptions { MaxDepth=8 });
        try
        {
            if (document.RootElement.ValueKind!=JsonValueKind.Object) throw new InvalidDataException("Invalid identity vault.");
            var names=new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var property in document.RootElement.EnumerateObject())
                if (!names.Add(property.Name)) throw new InvalidDataException("Duplicate identity-vault field.");
            return document;
        }
        catch { document.Dispose(); throw; }
    }
    private static byte[] ReadSeed(JsonElement root)
    {
        var seed=root.GetProperty("identitySeedBase64").GetBytesFromBase64();
        if (seed.Length==32) return seed;
        CryptographicOperations.ZeroMemory(seed);
        throw new InvalidDataException("Invalid identity seed length.");
    }
    private static byte[] ReadEnvelope(JsonElement root)
    {
        var envelope=root.GetProperty("databaseKeyEnvelopeBase64").GetBytesFromBase64();
        if (envelope.Length==60) return envelope;
        CryptographicOperations.ZeroMemory(envelope);
        throw new InvalidDataException("Invalid database key envelope.");
    }
    private static void ValidateSettings(SecureSettings settings)
    {
        if (settings.FormatVersion!=3) throw new InvalidDataException("Unsupported settings version.");
        if (settings.ServerPort is < 0 or > 65535 || settings.TurnPort is < 0 or > 65535) throw new InvalidDataException("A stored port is outside the valid range.");
    }
    private static void WriteMarker(string path,ReadOnlySpan<byte> value)
    {
        using var marker=new FileStream(path,FileMode.OpenOrCreate,FileAccess.Write,FileShare.Read);
        marker.Write(value);
        marker.Flush(flushToDisk:true);
    }
}
