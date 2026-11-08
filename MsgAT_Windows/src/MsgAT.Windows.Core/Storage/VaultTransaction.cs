// SPDX-License-Identifier: AGPL-3.0-only
using Microsoft.Data.Sqlite;

namespace MsgAT.Windows.Core.Storage;

/// <summary>Only valid inside EncryptedDatabase.ProtocolTransaction.</summary>
internal sealed class VaultTransaction(SqliteConnection connection, SqliteTransaction transaction)
{
    public int Execute(string sql, params object?[] parameters)
    {
        using var command = Command(sql, parameters);
        return command.ExecuteNonQuery();
    }

    public List<T> Query<T>(string sql, Func<SqliteDataReader, T> read, params object?[] parameters)
    {
        using var command = Command(sql, parameters);
        using var reader = command.ExecuteReader();
        var result = new List<T>();
        while (reader.Read()) result.Add(read(reader));
        return result;
    }

    public string? Text(string sql, params object?[] parameters)
    {
        using var command = Command(sql, parameters);
        return command.ExecuteScalar() is string value ? value : null;
    }

    public long Number(string sql, params object?[] parameters)
    {
        using var command = Command(sql, parameters);
        return command.ExecuteScalar() is long value ? value : 0;
    }

    public bool Exists(string sql, params object?[] parameters)
    {
        using var command = Command(sql, parameters);
        return command.ExecuteScalar() is not null;
    }

    private SqliteCommand Command(string sql, object?[] parameters)
    {
        var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = sql;
        for (var index = 0; index < parameters.Length; index++)
            command.Parameters.AddWithValue("$p" + index.ToString(System.Globalization.CultureInfo.InvariantCulture), parameters[index] ?? DBNull.Value);
        return command;
    }
}
