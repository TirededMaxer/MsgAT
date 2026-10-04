using Microsoft.Data.Sqlite;
using MsgAT.Windows.Core.Models;

namespace MsgAT.Windows.Core.Storage;

public sealed class EncryptedDatabase : IDisposable
{
    private readonly object _gate = new();
    private readonly string _databasePath;
    private readonly string _password;
    private SqliteConnection _connection;
    private bool _disposed;

    static EncryptedDatabase()
    {
        SQLitePCL.Batteries_V2.Init();
    }

    public EncryptedDatabase(string databasePath, byte[] key)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(databasePath);
        if (key.Length != 32)
        {
            throw new ArgumentException("Database key must be exactly 32 bytes.", nameof(key));
        }

        _databasePath = databasePath;
        _password = Convert.ToBase64String(key);
        Directory.CreateDirectory(Path.GetDirectoryName(databasePath)
            ?? throw new ArgumentException("Database path has no parent directory.", nameof(databasePath)));

        if (IsPlaintextSqlite(databasePath))
        {
            MigratePlaintextDatabase(databasePath, _password);
        }

        _connection = OpenEncrypted(databasePath, _password);
        Configure(_connection);
        CreateSchema();
    }

    public event EventHandler? Changed;

    public IReadOnlyList<ConversationWithUnread> GetConversations()
    {
        lock (_gate)
        {
            using var command = Command("""
                SELECT c.uuid, c.alias, c.avatarPath, c.peerPublicKey,
                  (SELECT COUNT(*) FROM messages m
                   WHERE m.conversationUuid = c.uuid AND m.isMine = 0 AND m.isRead = 0)
                FROM conversations c ORDER BY c.rowid
                """);
            using var reader = command.ExecuteReader();
            var rows = new List<ConversationWithUnread>();
            while (reader.Read())
            {
                rows.Add(new ConversationWithUnread(ReadConversation(reader), reader.GetInt32(4)));
            }
            return rows;
        }
    }

    public Conversation? GetConversation(string uuid)
    {
        lock (_gate)
        {
            using var command = Command(
                "SELECT uuid, alias, avatarPath, peerPublicKey FROM conversations WHERE uuid = $uuid LIMIT 1");
            command.Parameters.AddWithValue("$uuid", uuid);
            using var reader = command.ExecuteReader();
            return reader.Read() ? ReadConversation(reader) : null;
        }
    }

    public void UpsertConversation(Conversation conversation)
    {
        lock (_gate)
        {
            using var command = Command("""
                INSERT INTO conversations(uuid, alias, avatarPath, peerPublicKey) VALUES($uuid, $alias, $avatar, $key)
                ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias,
                  avatarPath=excluded.avatarPath, peerPublicKey=excluded.peerPublicKey
                """);
            command.Parameters.AddWithValue("$uuid", conversation.Uuid);
            command.Parameters.AddWithValue("$alias", conversation.Alias);
            command.Parameters.AddWithValue("$avatar", conversation.AvatarPath);
            command.Parameters.AddWithValue("$key", conversation.PeerPublicKey);
            command.ExecuteNonQuery();
        }
        OnChanged();
    }

    public void DeleteConversation(string uuid)
    {
        ExecuteChanged("DELETE FROM conversations WHERE uuid = $uuid", ("$uuid", uuid));
    }

    public IReadOnlyList<ChatMessage> GetMessages(string conversationUuid, int limit = 100, int offset = 0)
    {
        if (limit < 1 || offset < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(limit));
        }
        lock (_gate)
        {
            using var command = Command("""
                SELECT messageId, conversationUuid, isMine, content, ext, timestamp, isRead,
                       replyToId, replyToSender, replyToContent, status, isRevoked, isEdited
                FROM messages WHERE conversationUuid = $uuid
                ORDER BY timestamp DESC, messageId DESC LIMIT $limit OFFSET $offset
                """);
            command.Parameters.AddWithValue("$uuid", conversationUuid);
            command.Parameters.AddWithValue("$limit", limit);
            command.Parameters.AddWithValue("$offset", offset);
            using var reader = command.ExecuteReader();
            var messages = new List<ChatMessage>();
            while (reader.Read()) { messages.Add(ReadMessage(reader)); }
            messages.Reverse();
            return messages;
        }
    }

    public IReadOnlyList<ChatMessage> GetAllMessages(string conversationUuid)
    {
        lock (_gate)
        {
            using var command = Command("""
                SELECT messageId, conversationUuid, isMine, content, ext, timestamp, isRead,
                       replyToId, replyToSender, replyToContent, status, isRevoked, isEdited
                FROM messages WHERE conversationUuid = $uuid ORDER BY timestamp
                """);
            command.Parameters.AddWithValue("$uuid", conversationUuid);
            using var reader = command.ExecuteReader();
            var messages = new List<ChatMessage>();
            while (reader.Read()) { messages.Add(ReadMessage(reader)); }
            return messages;
        }
    }

    public ChatMessage? GetMessage(string messageId)
    {
        lock (_gate)
        {
            using var command = Command("""
                SELECT messageId, conversationUuid, isMine, content, ext, timestamp, isRead,
                       replyToId, replyToSender, replyToContent, status, isRevoked, isEdited
                FROM messages WHERE messageId = $id LIMIT 1
                """);
            command.Parameters.AddWithValue("$id", messageId);
            using var reader = command.ExecuteReader();
            return reader.Read() ? ReadMessage(reader) : null;
        }
    }

    public void UpsertMessage(ChatMessage message)
    {
        lock (_gate)
        {
            using var command = Command("""
                INSERT INTO messages(messageId, conversationUuid, isMine, content, ext, timestamp,
                  isRead, replyToId, replyToSender, replyToContent, status, isRevoked, isEdited)
                VALUES($id, $conversation, $mine, $content, $ext, $time, $read, $replyId,
                  $replySender, $replyContent, $status, $revoked, $edited)
                ON CONFLICT(messageId) DO NOTHING
                """);
            command.Parameters.AddWithValue("$id", message.MessageId);
            command.Parameters.AddWithValue("$conversation", message.ConversationUuid);
            command.Parameters.AddWithValue("$mine", message.IsMine);
            command.Parameters.AddWithValue("$content", message.Content);
            command.Parameters.AddWithValue("$ext", message.Extension);
            command.Parameters.AddWithValue("$time", message.Timestamp);
            command.Parameters.AddWithValue("$read", message.IsRead);
            AddNullable(command, "$replyId", message.ReplyToId);
            AddNullable(command, "$replySender", message.ReplyToSender);
            AddNullable(command, "$replyContent", message.ReplyToContent);
            command.Parameters.AddWithValue("$status", message.Status);
            command.Parameters.AddWithValue("$revoked", message.IsRevoked);
            command.Parameters.AddWithValue("$edited", message.IsEdited);
            command.ExecuteNonQuery();
        }
        OnChanged();
    }

    public void UpdateMessageStatus(string messageId, int status)
    {
        ExecuteChanged(
            "UPDATE messages SET status = $status WHERE messageId = $id AND status < $status",
            ("$status", status), ("$id", messageId));
    }

    public void UpdateMessageStatusThrough(string conversationUuid, long timestamp, int status)
    {
        ExecuteChanged("""
            UPDATE messages SET status = $status
            WHERE conversationUuid = $uuid AND timestamp <= $timestamp AND isMine = 1 AND status < $status
            """, ("$status", status), ("$uuid", conversationUuid), ("$timestamp", timestamp));
    }

    public ChatMessage? MarkConversationRead(string conversationUuid)
    {
        ChatMessage? unread;
        lock (_gate)
        {
            using (var command = Command("SELECT messageId FROM messages WHERE conversationUuid = $uuid AND isMine = 0 AND isRead = 0 ORDER BY timestamp DESC, messageId DESC LIMIT 1"))
            {
                command.Parameters.AddWithValue("$uuid", conversationUuid);
                unread = command.ExecuteScalar() is string id ? GetMessage(id) : null;
            }
            if (unread is null) { return null; }
            using var update = Command("UPDATE messages SET isRead = 1 WHERE conversationUuid = $uuid AND isMine = 0 AND isRead = 0");
            update.Parameters.AddWithValue("$uuid", conversationUuid);
            update.ExecuteNonQuery();
        }
        OnChanged();
        return unread;
    }

    public IReadOnlyList<ChatMessage> GetPendingMessages()
    {
        lock (_gate)
        {
            using var command = Command("""
                SELECT messageId, conversationUuid, isMine, content, ext, timestamp, isRead,
                       replyToId, replyToSender, replyToContent, status, isRevoked, isEdited
                FROM messages WHERE status < 2 AND isMine = 1 AND isRevoked = 0 ORDER BY timestamp
                """);
            using var reader = command.ExecuteReader();
            var messages = new List<ChatMessage>();
            while (reader.Read()) { messages.Add(ReadMessage(reader)); }
            return messages;
        }
    }

    public void RevokeMessage(string messageId) =>
        ExecuteChanged("UPDATE messages SET isRevoked = 1 WHERE messageId = $id", ("$id", messageId));

    public void EditMessage(string messageId, string content) =>
        ExecuteChanged(
            "UPDATE messages SET content = $content, isEdited = 1 WHERE messageId = $id",
            ("$content", content), ("$id", messageId));

    public void DeleteMessage(string messageId) =>
        ExecuteChanged("DELETE FROM messages WHERE messageId = $id", ("$id", messageId));

    public void ClearMessages(string conversationUuid) =>
        ExecuteChanged("DELETE FROM messages WHERE conversationUuid = $uuid", ("$uuid", conversationUuid));

    public IReadOnlyList<FriendRequest> GetFriendRequests()
    {
        lock (_gate)
        {
            using var command = Command("""
                SELECT senderUuid, nickname, avatarPath, peerPublicKey, timestamp
                FROM friend_requests ORDER BY timestamp DESC
                """);
            using var reader = command.ExecuteReader();
            var requests = new List<FriendRequest>();
            while (reader.Read()) { requests.Add(ReadFriendRequest(reader)); }
            return requests;
        }
    }

    public FriendRequest? GetFriendRequest(string senderUuid)
    {
        lock (_gate)
        {
            using var command = Command("""
                SELECT senderUuid, nickname, avatarPath, peerPublicKey, timestamp
                FROM friend_requests WHERE senderUuid = $uuid LIMIT 1
                """);
            command.Parameters.AddWithValue("$uuid", senderUuid);
            using var reader = command.ExecuteReader();
            return reader.Read() ? ReadFriendRequest(reader) : null;
        }
    }

    public void UpsertFriendRequest(FriendRequest request)
    {
        lock (_gate)
        {
            using var command = Command("""
                INSERT INTO friend_requests(senderUuid, nickname, avatarPath, peerPublicKey, timestamp)
                VALUES($uuid, $nickname, $avatar, $key, $time)
                ON CONFLICT(senderUuid) DO UPDATE SET nickname=excluded.nickname,
                  avatarPath=excluded.avatarPath, peerPublicKey=excluded.peerPublicKey,
                  timestamp=excluded.timestamp
                """);
            command.Parameters.AddWithValue("$uuid", request.SenderUuid);
            command.Parameters.AddWithValue("$nickname", request.Nickname);
            command.Parameters.AddWithValue("$avatar", request.AvatarPath);
            command.Parameters.AddWithValue("$key", request.PeerPublicKey);
            command.Parameters.AddWithValue("$time", request.Timestamp);
            command.ExecuteNonQuery();
        }
        OnChanged();
    }

    public void DeleteFriendRequest(string senderUuid) =>
        ExecuteChanged("DELETE FROM friend_requests WHERE senderUuid = $uuid", ("$uuid", senderUuid));

    public void ClearAll()
    {
        lock (_gate)
        {
            using var transaction = _connection.BeginTransaction();
            foreach (var table in new[] { "messages", "conversations", "friend_requests" })
            {
                using var command = Command($"DELETE FROM {table}");
                command.Transaction = transaction;
                command.ExecuteNonQuery();
            }
            transaction.Commit();
        }
        OnChanged();
    }

    public string CipherVersion
    {
        get
        {
            lock (_gate)
            {
                using var command = Command("PRAGMA cipher_version");
                return command.ExecuteScalar()?.ToString() ?? "";
            }
        }
    }

    public void Dispose()
    {
        if (_disposed) { return; }
        lock (_gate)
        {
            if (_disposed) { return; }
            _connection.Dispose();
            _disposed = true;
        }
        GC.SuppressFinalize(this);
    }

    private void CreateSchema()
    {
        ExecuteSchema("""
            CREATE TABLE IF NOT EXISTS conversations (
              uuid TEXT PRIMARY KEY NOT NULL, alias TEXT NOT NULL,
              avatarPath TEXT NOT NULL DEFAULT '', peerPublicKey TEXT NOT NULL DEFAULT '')
            """);
        ExecuteSchema("""
            CREATE TABLE IF NOT EXISTS messages (
              messageId TEXT PRIMARY KEY NOT NULL, conversationUuid TEXT NOT NULL,
              isMine INTEGER NOT NULL, content TEXT NOT NULL, ext TEXT NOT NULL,
              timestamp INTEGER NOT NULL, isRead INTEGER NOT NULL DEFAULT 0,
              replyToId TEXT, replyToSender TEXT, replyToContent TEXT,
              status INTEGER NOT NULL DEFAULT 1, isRevoked INTEGER NOT NULL DEFAULT 0,
              isEdited INTEGER NOT NULL DEFAULT 0)
            """);
        ExecuteSchema("CREATE INDEX IF NOT EXISTS index_messages_conversationUuid_timestamp ON messages(conversationUuid, timestamp)");
        ExecuteSchema("CREATE INDEX IF NOT EXISTS index_messages_isRead ON messages(isRead)");
        ExecuteSchema("""
            CREATE TABLE IF NOT EXISTS friend_requests (
              senderUuid TEXT PRIMARY KEY NOT NULL, nickname TEXT NOT NULL,
              avatarPath TEXT NOT NULL DEFAULT '', peerPublicKey TEXT NOT NULL DEFAULT '',
              timestamp INTEGER NOT NULL)
            """);
    }

    private void ExecuteSchema(string sql)
    {
        using var command = Command(sql);
        command.ExecuteNonQuery();
    }

    private void ExecuteChanged(string sql, params (string Name, object Value)[] parameters)
    {
        int affected;
        lock (_gate)
        {
            using var command = Command(sql);
            foreach (var parameter in parameters)
            {
                command.Parameters.AddWithValue(parameter.Name, parameter.Value);
            }
            affected = command.ExecuteNonQuery();
        }
        if (affected > 0) { OnChanged(); }
    }

    private SqliteCommand Command(string sql)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        var command = _connection.CreateCommand();
        command.CommandText = sql;
        return command;
    }

    private void OnChanged() => Changed?.Invoke(this, EventArgs.Empty);

    private static void AddNullable(SqliteCommand command, string name, string? value) =>
        command.Parameters.AddWithValue(name, (object?)value ?? DBNull.Value);

    private static Conversation ReadConversation(SqliteDataReader reader) => new(
        reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetString(3));

    private static ChatMessage ReadMessage(SqliteDataReader reader) => new(
        reader.GetString(0), reader.GetString(1), reader.GetBoolean(2), reader.GetString(3),
        reader.GetString(4), reader.GetInt64(5), reader.GetBoolean(6), GetNullableString(reader, 7),
        GetNullableString(reader, 8), GetNullableString(reader, 9), reader.GetInt32(10),
        reader.GetBoolean(11), reader.GetBoolean(12));

    private static FriendRequest ReadFriendRequest(SqliteDataReader reader) => new(
        reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetString(3), reader.GetInt64(4));

    private static string? GetNullableString(SqliteDataReader reader, int ordinal) =>
        reader.IsDBNull(ordinal) ? null : reader.GetString(ordinal);

    private static SqliteConnection OpenEncrypted(string path, string password)
    {
        var builder = new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Pooling = false,
            Password = password
        };
        var connection = new SqliteConnection(builder.ConnectionString);
        connection.Open();
        using var validation = connection.CreateCommand();
        validation.CommandText = "SELECT count(*) FROM sqlite_master; PRAGMA cipher_version";
        _ = validation.ExecuteScalar();
        return connection;
    }

    private static void Configure(SqliteConnection connection)
    {
        using var command = connection.CreateCommand();
        command.CommandText = """
            PRAGMA cipher_memory_security = ON;
            PRAGMA foreign_keys = ON;
            PRAGMA journal_mode = WAL;
            PRAGMA busy_timeout = 5000;
            """;
        command.ExecuteNonQuery();
    }

    private static bool IsPlaintextSqlite(string path)
    {
        if (!File.Exists(path)) { return false; }
        Span<byte> header = stackalloc byte[16];
        using var stream = File.OpenRead(path);
        return stream.Read(header) == header.Length
            && header.SequenceEqual("SQLite format 3\0"u8);
    }

    private static void MigratePlaintextDatabase(string path, string password)
    {
        var encryptedPath = path + ".encrypted-" + Guid.NewGuid().ToString("N");
        try
        {
            using (File.Create(encryptedPath)) { }
            var sourceBuilder = new SqliteConnectionStringBuilder
            {
                DataSource = path,
                Mode = SqliteOpenMode.ReadWrite,
                Pooling = false
            };
            using (var source = new SqliteConnection(sourceBuilder.ConnectionString))
            {
                source.Open();
                using var checkpoint = source.CreateCommand();
                checkpoint.CommandText = "PRAGMA wal_checkpoint(TRUNCATE)";
                checkpoint.ExecuteNonQuery();

                using var export = source.CreateCommand();
                export.CommandText = """
                    ATTACH DATABASE $path AS encrypted KEY $key;
                    SELECT sqlcipher_export('encrypted');
                    DETACH DATABASE encrypted;
                    """;
                export.Parameters.AddWithValue("$path", encryptedPath);
                export.Parameters.AddWithValue("$key", password);
                export.ExecuteNonQuery();
            }

            using (var validation = OpenEncrypted(encryptedPath, password))
            {
                using var command = validation.CreateCommand();
                command.CommandText = "PRAGMA integrity_check";
                if (!string.Equals(command.ExecuteScalar()?.ToString(), "ok", StringComparison.OrdinalIgnoreCase))
                {
                    throw new InvalidDataException("The encrypted database migration failed integrity validation.");
                }
            }

            File.Replace(encryptedPath, path, null, ignoreMetadataErrors: true);
            File.Delete(path + "-wal");
            File.Delete(path + "-shm");
        }
        finally
        {
            File.Delete(encryptedPath);
        }
    }
}
