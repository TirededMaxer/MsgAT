// SPDX-License-Identifier: AGPL-3.0-only
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.Data.Sqlite;
using MsgAT.Windows.Core.Crypto;

namespace MsgAT.Windows.Core.Storage;

public sealed class IdentityStartupException(Exception cause, bool canReset) : Exception(cause.Message, cause)
{
    public bool CanReset { get; } = canReset;
}

public sealed class VaultStartupException(Exception cause) : Exception(cause.Message, cause) { }

public static class ClientVault
{
    public static (SecureSettingsStore Settings, EncryptedDatabase Database) Open(string dataDirectory)
    {
        Directory.CreateDirectory(dataDirectory);
        SecurityCore.RequireAvailable();
        SecureSettingsStore identity;
        try
        {
            identity = new SecureSettingsStore(dataDirectory);
        }
        catch (Exception error) when (error is IOException or InvalidDataException or UnauthorizedAccessException or
            InvalidOperationException or ArgumentException or CryptographicException or JsonException)
        {
            throw new IdentityStartupException(error, IdentityLifecycle.CanDiscardUnreadableIdentity(dataDirectory));
        }

        var key = identity.DatabaseKey;
        try
        {
            var database = new EncryptedDatabase(Path.Combine(dataDirectory, "msgat_v2_vault.db"), key);
            return (identity, database);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or
            InvalidOperationException or ArgumentException or CryptographicException or SqliteException)
        {
            throw new VaultStartupException(error);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
        }
    }
}
