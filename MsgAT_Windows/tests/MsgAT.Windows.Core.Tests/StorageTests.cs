using System.Text;
using Microsoft.Data.Sqlite;
using MsgAT.Windows.Core.Storage;

namespace MsgAT.Windows.Core.Tests;

public sealed class StorageTests
{
    [Fact]
    public void TextAttachmentIsRejectedBeforeCreatingMedia()
    {
        using var directory = new TemporaryDirectory();
        var source = Path.Combine(directory.Path, "notes.TXT");
        File.WriteAllText(source, "This must not silently become a chat message.");
        var media = new MediaStore(directory.Path);

        Assert.Throws<InvalidDataException>(() => media.Import(Guid.NewGuid().ToString(), source));
        Assert.Empty(Directory.GetFiles(media.RootDirectory));
    }

    [Fact]
    public void PlaintextDatabaseIsMigratedToSqlCipherWithoutDataLoss()
    {
        using var directory = new TemporaryDirectory();
        var path = Path.Combine(directory.Path, "msgat_vault.db");
        SQLitePCL.Batteries_V2.Init();
        using (var plaintext = new SqliteConnection($"Data Source={path};Pooling=False"))
        {
            plaintext.Open();
            using var command = plaintext.CreateCommand();
            command.CommandText = """
                CREATE TABLE conversations (
                  uuid TEXT PRIMARY KEY NOT NULL, alias TEXT NOT NULL,
                  avatarPath TEXT NOT NULL DEFAULT '', peerPublicKey TEXT NOT NULL DEFAULT '');
                INSERT INTO conversations VALUES ('peer-id', 'Peer', '', 'public-key');
                """;
            command.ExecuteNonQuery();
        }

        Assert.Equal("SQLite format 3\0"u8.ToArray(), File.ReadAllBytes(path).AsSpan(0, 16).ToArray());
        var key = Enumerable.Repeat((byte)0xA5, 32).ToArray();
        using (var encrypted = new EncryptedDatabase(path, key))
        {
            Assert.Equal("Peer", encrypted.GetConversation("peer-id")?.Alias);
            Assert.False(string.IsNullOrWhiteSpace(encrypted.CipherVersion));
        }
        Assert.NotEqual("SQLite format 3\0"u8.ToArray(), File.ReadAllBytes(path).AsSpan(0, 16).ToArray());
        Assert.DoesNotContain(Directory.GetFiles(directory.Path), static file => file.Contains("plaintext", StringComparison.Ordinal));
    }

    [Fact]
    public void LegacyJavaVaultIsMigratedAndIdentityIsPreserved()
    {
        using var directory = new TemporaryDirectory();
        var seed = Enumerable.Range(0, 32).Select(static value => (byte)value).ToArray();
        var databaseKey = Enumerable.Range(32, 32).Select(static value => (byte)value).ToArray();
        var legacy = $"""
            #MsgAT DPAPI Encrypted Vault
            PRIVATE_KEY={Convert.ToBase64String(seed)}
            DB_PASSPHRASE={Convert.ToBase64String(databaseKey)}
            server_ip=relay.example.com
            server_port=8443
            my_nickname=Alice\u4e0e\u9c8d
            """;
        File.WriteAllBytes(Path.Combine(directory.Path, "msgat_vault.sec"), Encoding.Latin1.GetBytes(legacy));

        var store = new SecureSettingsStore(directory.Path, new IdentityProtector());

        Assert.Equal(seed, store.IdentitySeed);
        Assert.Equal(databaseKey, store.DatabaseKey);
        Assert.Equal("Alice与鲍", store.Snapshot.Nickname);
        Assert.True(store.IsConfigured);
        Assert.StartsWith("{", Encoding.UTF8.GetString(File.ReadAllBytes(Path.Combine(directory.Path, "msgat_vault.sec"))));
    }

    [Fact]
    public void ExistingCorruptVaultFailsClosed()
    {
        using var directory = new TemporaryDirectory();
        var vaultPath = Path.Combine(directory.Path, "msgat_vault.sec");
        File.WriteAllBytes(vaultPath, [0xFF, 0x00, 0x7F]);
        Assert.Throws<InvalidDataException>(() =>
            new SecureSettingsStore(directory.Path, new ThrowingProtector()));
        Assert.Equal(new byte[] { 0xFF, 0x00, 0x7F }, File.ReadAllBytes(vaultPath));
    }

    private sealed class IdentityProtector : ISecretProtector
    {
        public byte[] Protect(byte[] plaintext) => plaintext;
        public byte[] Unprotect(byte[] ciphertext) => ciphertext;
    }

    private sealed class ThrowingProtector : ISecretProtector
    {
        public byte[] Protect(byte[] plaintext) => throw new InvalidOperationException();
        public byte[] Unprotect(byte[] ciphertext) => throw new System.Security.Cryptography.CryptographicException();
    }

    private sealed class TemporaryDirectory : IDisposable
    {
        public TemporaryDirectory()
        {
            Path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "msgat-tests-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(Path);
        }

        public string Path { get; }
        public void Dispose() => Directory.Delete(Path, recursive: true);
    }
}
