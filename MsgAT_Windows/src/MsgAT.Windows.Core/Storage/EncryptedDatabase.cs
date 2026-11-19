using Microsoft.Data.Sqlite;
using MsgAT.Windows.Core.Models;
using MsgAT.Windows.Core.Network;

namespace MsgAT.Windows.Core.Storage;

public sealed record MessageHistoryWindow(IReadOnlyList<ChatMessage> Messages, bool HasOlder, bool HasNewer);
public sealed record ForwardedMessageInfo(string? Name,long OriginalAt);

public sealed class MessageDeliveryChangedEventArgs(string conversationUuid, string messageId) : EventArgs
{
    public string ConversationUuid { get; } = conversationUuid;
    public string MessageId { get; } = messageId;
}

public sealed class VisibleMessagesReadEventArgs(string conversationUuid, IReadOnlySet<string> messageIds) : EventArgs
{
    public string ConversationUuid { get; } = conversationUuid;
    public IReadOnlySet<string> MessageIds { get; } = messageIds;
}

public sealed class AttachmentCacheChangedEventArgs(string? messageId = null) : EventArgs
{
    public string? MessageId { get; } = messageId;
}

public sealed class EncryptedDatabase : IDisposable
{
    private readonly object _gate = new();
    private readonly string _databasePath;
    private readonly string _password;
    private SqliteConnection _connection;
    private readonly FileStream _profileLease;
    internal string StorageOwner {get;}=Guid.NewGuid().ToString("N");
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

        try {_profileLease=new FileStream(databasePath+".lock",FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None);}
        catch(Exception error) when(error is IOException or UnauthorizedAccessException)
        {throw new InvalidOperationException("The encrypted vault is already open or could not be locked.");}
        SqliteConnection? opened=null;
        try
        {
            if (!File.Exists(databasePath) && new[] {"-wal","-shm","-journal"}.Any(suffix=>File.Exists(databasePath+suffix)))
                throw new InvalidDataException("The encrypted vault file is missing. Recovery files were preserved.");
            if(IsPlaintextSqlite(databasePath))throw new InvalidDataException("Refusing an unauthenticated plaintext database in the encrypted V2 vault location.");
            opened=OpenEncrypted(databasePath,_password);_connection=opened;
            Configure(_connection);CreateSchema();
        }
        catch {opened?.Dispose();_profileLease.Dispose();throw;}
    }

    public event EventHandler? Changed;

    // Signal state and application effects must share this exact connection and
    // transaction. A separate protocol connection could acknowledge half a commit.
    internal T ProtocolTransaction<T>(Func<VaultTransaction, T> operation, bool notify = false)
    {
        T result;
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            using var transaction = _connection.BeginTransaction();
            result = operation(new VaultTransaction(_connection, transaction));
            transaction.Commit();
        }
        if (notify) OnChanged();
        return result;
    }

    internal void ReclaimUnusedPages()
    {
        lock (_gate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            using var command = Command("PRAGMA auto_vacuum");
            if (command.ExecuteScalar() is not long mode || mode != 2) return;
            command.CommandText = "PRAGMA freelist_count";
            if (command.ExecuteScalar() is not long free || free < 128) return;
            command.CommandText = "PRAGMA incremental_vacuum(64)";
            using (var rows = command.ExecuteReader())
            {
                while (rows.Read()) { }
            }
            command.CommandText = "PRAGMA wal_checkpoint(PASSIVE)";
            using var checkpoint = command.ExecuteReader();
            while (checkpoint.Read()) { }
        }
    }

    public IReadOnlyList<ConversationWithUnread> GetConversations()
    {
        lock (_gate)
        {
            using var command = Command("""
                SELECT s.*, CASE WHEN m.isMine=1 AND m.isRevoked=0 AND m.status BETWEEN -1 AND 3
                    THEN m.status END AS lastReceiptStatus,
                CASE
                    WHEN m.isRevoked=1 THEN 'revoked'
                    WHEN m.ext='contact' THEN 'contact'
                    WHEN m.ext='attachment' AND NOT EXISTS (
                        SELECT 1 FROM message_attachments a LEFT JOIN media_objects o ON o.object_id=a.object_id
                        WHERE a.message_id=m.messageId AND
                            (COALESCE(a.caption,'')!='' OR COALESCE(json_extract(o.manifest_json,'$.filename'),'')!='')
                    ) THEN 'attachment'
                    WHEN m.ext NOT IN ('txt','location','live_location','attachment') THEN 'call'
                    ELSE ''
                END AS summaryKind
                FROM conversation_summaries s LEFT JOIN messages m ON m.messageId=s.lastID
                ORDER BY s.pinOrder DESC,s.activityAt DESC,s.uuid
                """);
            using var reader = command.ExecuteReader();
            var rows = new List<ConversationWithUnread>();
            while (reader.Read())
            {
                rows.Add(new ConversationWithUnread(ReadConversation(reader),reader.GetInt32(4),reader.GetInt64(5),reader.GetInt32(6)!=0,reader.GetInt64(7),reader.GetInt32(8)!=0,reader.GetString(9),reader.GetString(10),reader.GetInt64(11),reader.GetString(12),reader.GetString(13),reader.GetString(14),reader.GetInt32(15)!=0,reader.GetInt64(16),reader.GetString(17),
                    reader.IsDBNull(18) ? null : reader.GetInt32(18), reader.GetString(19)));
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

    public PinnedMessagePage GetPinnedMessages(string peer, string local, int offset = 0, int pageSize = 50,
                                                PinnedMessageItem? through = null) =>
        ProtocolTransaction(db => MessagePinsStore.List(db, peer, local, offset, pageSize, through));

    public bool IsMessagePinned(string peer,string local,string id)=>ProtocolTransaction(db=>db.Exists("""
        SELECT 1 FROM messages m JOIN message_pin_clocks p ON p.peer=m.conversationUuid AND p.message_id=m.messageId
        WHERE m.conversationUuid=$p0 AND m.messageId=$p1 AND m.isRevoked=0 AND p.author=CASE WHEN m.isMine=1 THEN $p2 ELSE $p0 END AND p.addition>p.removed
          AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p3)
          AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
              AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p3-r.duration_ms)
        """,peer,id,local,DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));

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
                       replyToId, replyToSender, replyToContent, status, isRevoked, isEdited, e.expires_at
                FROM messages m LEFT JOIN message_expiration e ON e.message_id=m.messageId
                WHERE conversationUuid = $uuid AND (e.expires_at IS NULL OR e.expires_at>$at)
                  AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                      AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$at-r.duration_ms)
                ORDER BY timestamp DESC, messageId DESC LIMIT $limit OFFSET $offset
                """);
            command.Parameters.AddWithValue("$uuid", conversationUuid);
            command.Parameters.AddWithValue("$at", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
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
                       replyToId, replyToSender, replyToContent, status, isRevoked, isEdited, e.expires_at
                FROM messages m LEFT JOIN message_expiration e ON e.message_id=m.messageId
                WHERE messageId = $id LIMIT 1
                """);
            command.Parameters.AddWithValue("$id", messageId);
            using var reader = command.ExecuteReader();
            return reader.Read() ? ReadMessage(reader) : null;
        }
    }

    public IReadOnlyList<string> GetSelectedMessageText(string peer, IReadOnlyList<string> ids)
    {
        if (ids.Count is 0 or > 100) return [];
        var parameters = new object?[ids.Count + 1];
        parameters[0] = peer;
        for (var index = 0; index < ids.Count; index++) parameters[index + 1] = ids[index];
        var placeholders = string.Join(",", Enumerable.Range(1, ids.Count).Select(index => $"$p{index}"));
        return ProtocolTransaction(db => db.Query($"""
            SELECT content FROM messages
            WHERE conversationUuid=$p0 AND messageId IN ({placeholders}) AND ext='txt' AND isRevoked=0
            ORDER BY timestamp,messageId
            """, reader => reader.GetString(0), parameters));
    }

    public bool CanQuoteText(ChatMessage message) => ProtocolTransaction(db => db.Exists("""
        SELECT 1 FROM messages m WHERE m.messageId=$p0 AND m.conversationUuid=$p1 AND m.ext='txt' AND m.isRevoked=0
          AND m.content=$p2 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId)
        """, message.MessageId, message.ConversationUuid, message.Content));

    public bool CanExportAttachment(ChatMessage message) => ProtocolTransaction(db => db.Exists("""
        SELECT 1 FROM messages m JOIN message_attachments a ON a.message_id=m.messageId
        JOIN media_objects o ON o.object_id=a.object_id
        WHERE m.messageId=$p0 AND m.conversationUuid=$p1 AND m.content=$p2 AND m.isRevoked=0 AND o.local_state=1
          AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId)
          AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
              AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p3-r.duration_ms)
        """, message.MessageId, message.ConversationUuid, message.Content, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));

    public IReadOnlyList<ChatMessage> GetMessagesBefore(ChatMessage anchor,int limit=50) =>
        ProtocolTransaction(db=>SeekMessages(db,anchor,newer:false,inclusive:false,limit));

    public ForwardedMessageInfo? GetForwardInfo(string id) => ProtocolTransaction(db=>db.Query(
        "SELECT name,original_at FROM message_forwards WHERE message_id=$p0",r=>new ForwardedMessageInfo(r.IsDBNull(0)?null:r.GetString(0),r.GetInt64(1)),id).FirstOrDefault());

    public IReadOnlyList<Conversation> GetForwardTargets() => ProtocolTransaction(db=>db.Query("""
        SELECT c.uuid,c.alias,c.avatarPath,c.peerPublicKey FROM conversations c JOIN contact_states s ON s.uuid=c.uuid
        WHERE s.accepted=1 AND s.blocked=0 ORDER BY c.alias COLLATE NOCASE,c.uuid
        """,ReadConversation));

    public string? GetContactCardAccount(string peer, string messageId) => ProtocolTransaction(db => db.Text("""
        SELECT c.account FROM message_contact_cards c JOIN messages m ON m.messageId=c.message_id
        WHERE m.conversationUuid=$p0 AND m.messageId=$p1 AND m.ext='contact' AND m.isRevoked=0
        """, peer, messageId));

    public string? GetSavedContactCardAccount(string id) => ProtocolTransaction(db =>
        db.Text("SELECT account FROM saved_contact_cards WHERE id=$p0", id));

    public LocationPoint? GetSavedLocation(string id) => ProtocolTransaction(db => MessageActionsStore.ReadLocation(db,id,saved:true));

    public LocationPoint? GetLocationPoint(string peer, string messageId) => ProtocolTransaction(db => db.Query("""
        SELECT l.latitude_e6,l.longitude_e6 FROM message_locations l JOIN messages m ON m.messageId=l.message_id
        WHERE m.conversationUuid=$p0 AND m.messageId=$p1 AND m.ext='location' AND m.isRevoked=0
        """, row => new LocationPoint(row.GetInt32(0), row.GetInt32(1)), peer, messageId).FirstOrDefault());

    public IReadOnlyList<ChatMessage> GetMessagesAfter(ChatMessage anchor,int limit=50) =>
        ProtocolTransaction(db=>SeekMessages(db,anchor,newer:true,inclusive:false,limit));

    public IReadOnlyList<ChatMessage> GetMessagesFrom(ChatMessage anchor,int limit) =>
        ProtocolTransaction(db=>SeekMessages(db,anchor,newer:true,inclusive:true,limit));

    public string? GetMessageAtDate(string peer, long dayStart, long nextDayStart) => ProtocolTransaction(db => db.Query("""
        SELECT m.messageId FROM messages m
        WHERE m.conversationUuid=$p0 AND m.timestamp>=$p1 AND m.timestamp<$p2 AND m.isRevoked=0
          AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p3)
          AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
              AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p3-r.duration_ms)
        ORDER BY m.timestamp,m.messageId LIMIT 1
        """, row => row.GetString(0), peer, dayStart, nextDayStart, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()).FirstOrDefault());

    public bool CanOpenNotification(string peer, string messageId, bool pinned = false) => ProtocolTransaction(db => db.Exists("""
        SELECT 1 FROM messages m JOIN contact_states s ON s.uuid=m.conversationUuid
        WHERE m.conversationUuid=$p0 AND m.messageId=$p1 AND m.isRevoked=0 AND (m.isMine=0 OR $p3=1)
          AND s.accepted=1 AND s.blocked=0
          AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p2)
          AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
              AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p2-r.duration_ms)
          AND ($p3=0 OR EXISTS(SELECT 1 FROM message_pin_clocks p WHERE p.peer=m.conversationUuid
              AND p.message_id=m.messageId AND p.actor=m.conversationUuid AND p.addition>p.removed))
        """, peer, messageId, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), pinned ? 1 : 0));

    public bool CanPresentMessageNotification(string peer, string messageId, bool pinned) =>
        ProtocolTransaction(db =>
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            return !ConversationIndexStore.IsMuted(db, peer, now) && db.Exists("""
                SELECT 1 FROM messages m JOIN contact_states s ON s.uuid=m.conversationUuid
                WHERE m.conversationUuid=$p0 AND m.messageId=$p1 AND m.isRevoked=0
                  AND s.accepted=1 AND s.blocked=0
                  AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p2)
                  AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                      AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p2-r.duration_ms)
                  AND (($p3=0 AND m.isMine=0 AND m.isRead=0) OR ($p3=1 AND EXISTS(
                      SELECT 1 FROM message_pin_clocks p WHERE p.peer=m.conversationUuid
                          AND p.message_id=m.messageId AND p.actor=m.conversationUuid AND p.addition>p.removed)))
                """, peer, messageId, now, pinned ? 1 : 0);
        });

    public string? GetFirstUnreadId(string peer) => ProtocolTransaction(db => db.Query("""
        SELECT m.messageId FROM messages m
        WHERE m.conversationUuid=$p0 AND m.isMine=0 AND m.isRead=0 AND m.isRevoked=0
          AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p1)
          AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
              AND m.timestamp<=$p1-r.duration_ms)
        ORDER BY m.timestamp,m.messageId LIMIT 1
        """, row => row.GetString(0), peer, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()).FirstOrDefault());

    public (int Count, string FirstId, bool Marked)? GetUnreadState(string peer) =>
        ProtocolTransaction<(int, string, bool)?>(db =>
        {
            var rows = db.Query("""
                SELECT unreadCount,firstUnreadID,manualUnread FROM conversation_summaries WHERE uuid=$p0
                """, row => (row.GetInt32(0), row.GetString(1), row.GetInt32(2) != 0), peer);
            return rows.Count == 0 ? null : rows[0];
        });

    public ChatMessage? GetLatestEditableMessage(string peer) => ProtocolTransaction(db => db.Query("""
        SELECT m.messageId, m.conversationUuid, m.isMine, m.content, m.ext, m.timestamp, m.isRead,
               m.replyToId, m.replyToSender, m.replyToContent, m.status, m.isRevoked, m.isEdited
        FROM messages m
        WHERE m.conversationUuid=$p0 AND m.isMine=1 AND m.isRevoked=0
          AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId)
          AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
              AND m.status>=2 AND m.timestamp<=$p1-r.duration_ms)
          AND (m.ext='txt' OR (m.ext='attachment'
               AND EXISTS(SELECT 1 FROM message_attachments a WHERE a.message_id=m.messageId)
               AND NOT EXISTS(SELECT 1 FROM message_stickers s WHERE s.message_id=m.messageId)))
        ORDER BY m.timestamp DESC, m.messageId DESC LIMIT 1
        """, ReadMessage, peer, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()).FirstOrDefault());

    public ChatMessage? GetAdjacentReplyMessage(ChatMessage anchor, bool newer, bool includingAnchor)
    {
        var comparison = newer ? ">" : includingAnchor ? "<=" : "<";
        var order = newer ? "ASC" : "DESC";
        return ProtocolTransaction(db => db.Query($"""
            SELECT m.messageId, m.conversationUuid, m.isMine, m.content, m.ext, m.timestamp, m.isRead,
                   m.replyToId, m.replyToSender, m.replyToContent, m.status, m.isRevoked, m.isEdited
            FROM messages m
            WHERE m.conversationUuid=$p0 AND m.isRevoked=0 AND (m.timestamp,m.messageId) {comparison} ($p1,$p2)
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p3)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p3-r.duration_ms)
            ORDER BY m.timestamp {order},m.messageId {order} LIMIT 1
            """, ReadMessage, anchor.ConversationUuid, anchor.Timestamp, anchor.MessageId,
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()).FirstOrDefault());
    }

    public MessageHistoryWindow? GetMessageWindow(string peer,string messageId) => ProtocolTransaction<MessageHistoryWindow?>(db=>
    {
        var at = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var target=db.Query("""
            SELECT messageId,conversationUuid,isMine,content,ext,timestamp,isRead,
             replyToId,replyToSender,replyToContent,status,isRevoked,isEdited,e.expires_at
            FROM messages m LEFT JOIN message_expiration e ON e.message_id=m.messageId
            WHERE messageId=$p0 AND conversationUuid=$p1 AND (e.expires_at IS NULL OR e.expires_at>$p2)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p2-r.duration_ms)
            """,ReadMessage,messageId,peer,at).FirstOrDefault();
        if(target is null) return null;
        var rows=SeekMessages(db,target,newer:false,inclusive:false,25,at)
            .Concat(SeekMessages(db,target,newer:true,inclusive:true,26,at)).ToArray();
        if (!rows.Any(row => row.MessageId == messageId)) return null;
        return new MessageHistoryWindow(rows,
            SeekMessages(db,rows[0],newer:false,inclusive:false,1,at).Count>0,
            SeekMessages(db,rows[^1],newer:true,inclusive:false,1,at).Count>0);
    });

    public bool HasHistoryAnchor(string peer, string messageId, bool includeRevoked = true) => ProtocolTransaction(db => db.Exists("""
        SELECT 1 FROM messages m WHERE conversationUuid=$p0 AND messageId=$p1
          AND (m.isRevoked=0 OR $p3=1)
          AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=$p2)
          AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
              AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p2-r.duration_ms)
        """, peer, messageId, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), includeRevoked ? 1 : 0));

    private static IReadOnlyList<ChatMessage> SeekMessages(VaultTransaction db,ChatMessage anchor,bool newer,bool inclusive,int limit,long? at = null)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(limit,1);
        var comparison=newer?">":"<";
        var idComparison=inclusive?comparison+"=":comparison;
        var direction=newer?"ASC":"DESC";
        var rows=db.Query($"""
            SELECT messageId,conversationUuid,isMine,content,ext,timestamp,isRead,
             replyToId,replyToSender,replyToContent,status,isRevoked,isEdited,e.expires_at
            FROM messages m LEFT JOIN message_expiration e ON e.message_id=m.messageId
            WHERE conversationUuid=$p0 AND
             (timestamp {comparison} $p1 OR (timestamp=$p1 AND messageId {idComparison} $p2))
              AND (e.expires_at IS NULL OR e.expires_at>$p4)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=$p4-r.duration_ms)
            ORDER BY timestamp {direction},messageId {direction} LIMIT $p3
            """,ReadMessage,anchor.ConversationUuid,anchor.Timestamp,anchor.MessageId,limit,at ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        return newer?rows:rows.AsEnumerable().Reverse().ToArray();
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
            try {_connection.Dispose();}
            finally {_profileLease.Dispose();_disposed=true;}
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
            CREATE TABLE IF NOT EXISTS conversation_drafts (
              peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,
              token TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0,
              text TEXT NOT NULL DEFAULT '', reply_id TEXT)
            """);
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

    internal void NotifyBusinessChanges(EventArgs? change = null) => OnChanged(change);

    private void OnChanged(EventArgs? change = null)
    {
        // Observer failures cannot turn a committed crypto transaction into an
        // apparent failure, or prevent a recipient's transport acknowledgement.
        foreach (EventHandler observer in Changed?.GetInvocationList() ?? [])
        {
            try { observer(this, change ?? EventArgs.Empty); }
            catch (Exception error) { System.Diagnostics.Trace.TraceWarning("Database observer failed ({0})",error.GetType().Name); }
        }
    }

    private static void AddNullable(SqliteCommand command, string name, string? value) =>
        command.Parameters.AddWithValue(name, (object?)value ?? DBNull.Value);

    private static Conversation ReadConversation(SqliteDataReader reader) => new(
        reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetString(3));

    private static ChatMessage ReadMessage(SqliteDataReader reader) => new(
        reader.GetString(0), reader.GetString(1), reader.GetBoolean(2), reader.GetString(3),
        reader.GetString(4), reader.GetInt64(5), reader.GetBoolean(6), GetNullableString(reader, 7),
        GetNullableString(reader, 8), GetNullableString(reader, 9), reader.GetInt32(10),
        reader.GetBoolean(11), reader.GetBoolean(12),
        reader.FieldCount > 13 && !reader.IsDBNull(13) ? reader.GetInt64(13) : null);

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
        command.CommandText = "SELECT count(*) FROM sqlite_master";
        if (command.ExecuteScalar() is long schemaEntries && schemaEntries == 0)
        {
            command.CommandText = "PRAGMA auto_vacuum=INCREMENTAL";
            command.ExecuteNonQuery();
        }
        command.CommandText = """
            PRAGMA cipher_memory_security = ON;
            PRAGMA temp_store = MEMORY;
            PRAGMA foreign_keys = ON;
            PRAGMA journal_mode = WAL;
            PRAGMA synchronous = FULL;
            PRAGMA busy_timeout = 5000;
            PRAGMA journal_size_limit = 4194304;
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

}
