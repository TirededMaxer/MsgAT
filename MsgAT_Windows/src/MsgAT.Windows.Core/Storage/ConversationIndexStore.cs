// SPDX-License-Identifier: AGPL-3.0-only
using System.Text;
namespace MsgAT.Windows.Core.Storage;
public sealed record ConversationFolder(string Id,string Name,long SortOrder=0,bool IncludeAll=false,bool UnreadOnly=false,bool ExcludeMuted=false,bool IncludeArchived=false);
public sealed class ConversationIndexStore(EncryptedDatabase database):IAsyncDisposable {
    public const long PermanentMuteUntil = 253402214399999L;
    private readonly object _gate=new();private bool _closed;private readonly HashSet<Task> _tasks=[];private readonly CancellationTokenSource _lifetime=new();
    public static readonly string[] Schema=[
        "CREATE TABLE IF NOT EXISTS conversation_drafts (peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,token TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,text TEXT NOT NULL DEFAULT '',reply_id TEXT)",
        "CREATE TABLE IF NOT EXISTS conversation_preferences(peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,pin_order INTEGER NOT NULL DEFAULT 0 CHECK(pin_order>=0),archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)),mute_until INTEGER NOT NULL DEFAULT 0 CHECK(mute_until>=0),manual_unread INTEGER NOT NULL DEFAULT 0 CHECK(manual_unread IN (0,1)))",
        "CREATE INDEX IF NOT EXISTS conversation_preferences_order ON conversation_preferences(archived,pin_order,peer)",
        "CREATE TABLE IF NOT EXISTS conversation_folders(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,sort_order INTEGER NOT NULL DEFAULT 0,include_all INTEGER NOT NULL DEFAULT 0 CHECK(include_all IN (0,1)),unread_only INTEGER NOT NULL DEFAULT 0 CHECK(unread_only IN (0,1)),exclude_muted INTEGER NOT NULL DEFAULT 0 CHECK(exclude_muted IN (0,1)),include_archived INTEGER NOT NULL DEFAULT 0 CHECK(include_archived IN (0,1)))",
        "CREATE TABLE IF NOT EXISTS conversation_folder_members(folder_id TEXT NOT NULL REFERENCES conversation_folders(id) ON DELETE CASCADE,peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,PRIMARY KEY(folder_id,peer))",
        "CREATE INDEX IF NOT EXISTS conversation_folder_members_peer ON conversation_folder_members(peer)",
        "CREATE TABLE IF NOT EXISTS conversation_folder_pins(folder_id TEXT NOT NULL REFERENCES conversation_folders(id) ON DELETE CASCADE,peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,pin_order INTEGER NOT NULL CHECK(pin_order>0),PRIMARY KEY(folder_id,peer))",
        "CREATE INDEX IF NOT EXISTS conversation_folder_pins_peer ON conversation_folder_pins(peer)",
        "CREATE TABLE IF NOT EXISTS conversation_draft_activity(peer TEXT PRIMARY KEY NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,last_at INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_messages_conversationUuid_timestamp_messageId ON messages(conversationUuid,timestamp,messageId)",
        "CREATE INDEX IF NOT EXISTS index_messages_conversationUuid_isMine_isRead_timestamp_messageId ON messages(conversationUuid,isMine,isRead,timestamp,messageId)",
        "CREATE TRIGGER IF NOT EXISTS conversation_unarchive_message AFTER INSERT ON messages WHEN NEW.isMine=0 AND NEW.isRead=0 AND NEW.isRevoked=0 BEGIN UPDATE conversation_preferences SET archived=0 WHERE peer=NEW.conversationUuid AND archived=1 AND mute_until<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)); END",
        "CREATE TRIGGER IF NOT EXISTS conversation_clear_marker AFTER UPDATE OF isRead ON messages WHEN NEW.isRead=1 AND OLD.isRead=0 BEGIN UPDATE conversation_preferences SET manual_unread=0 WHERE peer=NEW.conversationUuid AND manual_unread=1; END",
        "CREATE TRIGGER IF NOT EXISTS conversation_draft_insert AFTER INSERT ON conversation_drafts BEGIN INSERT INTO conversation_draft_activity(peer,last_at) SELECT NEW.peer,(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)) WHERE NEW.text!='' OR NEW.reply_id IS NOT NULL ON CONFLICT(peer) DO UPDATE SET last_at=excluded.last_at; DELETE FROM conversation_draft_activity WHERE peer=NEW.peer AND NEW.text='' AND NEW.reply_id IS NULL; END",
        "CREATE TRIGGER IF NOT EXISTS conversation_draft_update AFTER UPDATE ON conversation_drafts BEGIN INSERT INTO conversation_draft_activity(peer,last_at) SELECT NEW.peer,(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)) WHERE NEW.text!='' OR NEW.reply_id IS NOT NULL ON CONFLICT(peer) DO UPDATE SET last_at=excluded.last_at; DELETE FROM conversation_draft_activity WHERE peer=NEW.peer AND NEW.text='' AND NEW.reply_id IS NULL; END",
        "CREATE TRIGGER IF NOT EXISTS conversation_draft_delete AFTER DELETE ON conversation_drafts BEGIN DELETE FROM conversation_draft_activity WHERE peer=OLD.peer; END",
        "INSERT OR IGNORE INTO conversation_draft_activity(peer,last_at) SELECT peer,(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)) FROM conversation_drafts WHERE text!='' OR reply_id IS NOT NULL",
        "DROP VIEW IF EXISTS conversation_summaries",
        "CREATE VIEW IF NOT EXISTS conversation_summaries AS SELECT c.uuid,c.alias,c.avatarPath,c.peerPublicKey,\n(SELECT COUNT(*) FROM messages u WHERE u.conversationUuid=c.uuid AND u.isMine=0 AND u.isRead=0 AND u.isRevoked=0 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=u.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER)))) AS unreadCount,\nCOALESCE(p.pin_order,0) AS pinOrder,COALESCE(p.archived,0) AS archived,COALESCE(p.mute_until,0) AS muteUntil,COALESCE(p.manual_unread,0) AS manualUnread,\nCOALESCE((SELECT CASE WHEN m.isRevoked=1 THEN '[Deleted message]' WHEN m.ext='txt' THEN substr(m.content,1,240) WHEN m.ext='contact' THEN 'Contact: '||substr(m.content,1,230) WHEN m.ext IN ('location','live_location') THEN substr(m.content,1,240) WHEN m.ext='attachment' THEN COALESCE((SELECT NULLIF(substr(a.caption,1,240),'') FROM message_attachments a WHERE a.message_id=m.messageId),(SELECT json_extract(o.manifest_json,'$.filename') FROM message_attachments a JOIN media_objects o ON o.object_id=a.object_id WHERE a.message_id=m.messageId),'[Attachment]') ELSE '[Call]' END FROM messages m WHERE m.conversationUuid=c.uuid AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 1),'') AS summary,\nCOALESCE(substr(d.text,1,240),'') AS draftText,\nCOALESCE((SELECT m.timestamp FROM messages m WHERE m.conversationUuid=c.uuid AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 1),0) AS lastAt,\nCOALESCE((SELECT m.messageId FROM messages m WHERE m.conversationUuid=c.uuid AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 1),'') AS lastID,\nCOALESCE((SELECT u.messageId FROM messages u WHERE u.conversationUuid=c.uuid AND u.isMine=0 AND u.isRead=0 AND u.isRevoked=0 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=u.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY u.timestamp,u.messageId LIMIT 1),'') AS firstUnreadID,\nCOALESCE((SELECT group_concat(f.folder_id,',') FROM conversation_folder_members f WHERE f.peer=c.uuid),'') AS folderIDs,\nCASE WHEN COALESCE(d.text,'')!='' OR d.reply_id IS NOT NULL THEN 1 ELSE 0 END AS hasDraft,\nMAX(COALESCE(a.last_at,0),COALESCE((SELECT m.timestamp FROM messages m WHERE m.conversationUuid=c.uuid AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=(CAST(strftime('%s','now') AS INTEGER)*1000+CAST(substr(strftime('%f','now'),4,3) AS INTEGER))) ORDER BY m.timestamp DESC,m.messageId DESC LIMIT 1),0)) AS activityAt,\nCOALESCE((SELECT json_group_object(fp.folder_id,fp.pin_order) FROM conversation_folder_pins fp WHERE fp.peer=c.uuid),'{}') AS folderPinOrders\nFROM conversations c LEFT JOIN conversation_preferences p ON p.peer=c.uuid LEFT JOIN conversation_drafts d ON d.peer=c.uuid LEFT JOIN conversation_draft_activity a ON a.peer=c.uuid",
    ];
    private Task<T> Owned<T>(Func<VaultTransaction,T> action,bool notify=true){lock(_gate){ObjectDisposedException.ThrowIf(_closed,this);var task=Task.Run(()=>database.ProtocolTransaction(db=>{_lifetime.Token.ThrowIfCancellationRequested();return action(db);},notify:notify));_tasks.Add(task);return Observe(task);}}
    private async Task<T> Observe<T>(Task<T> task){try{return await task.ConfigureAwait(false);}finally{lock(_gate)_tasks.Remove(task);}}
    public async ValueTask DisposeAsync(){Task[] jobs;lock(_gate){if(_closed)return;_closed=true;jobs=_tasks.ToArray();}_lifetime.Cancel();try{await Task.WhenAll(jobs).ConfigureAwait(false);}catch{/* Original callers observe operation errors; teardown still drains every owner. */}finally{_lifetime.Dispose();}}
    public static bool IsMuted(VaultTransaction db,string peer,long now)=>db.Exists("SELECT 1 FROM conversation_preferences WHERE peer=$p0 AND mute_until>$p1",peer,now);
    public Task<bool> SetArchivedAsync(string peer, bool expected, bool archived) => Owned(db =>
    {
        db.Execute("INSERT OR IGNORE INTO conversation_preferences(peer) SELECT uuid FROM conversations WHERE uuid=$p0", peer);
        return db.Execute("UPDATE conversation_preferences SET archived=$p0 WHERE peer=$p1 AND archived=$p2",
            archived ? 1 : 0, peer, expected ? 1 : 0) == 1;
    });
    public IReadOnlyList<ConversationFolder> Folders()=>database.ProtocolTransaction(db=>db.Query("SELECT id,name,sort_order,include_all,unread_only,exclude_muted,include_archived FROM conversation_folders ORDER BY sort_order,id",r=>new ConversationFolder(r.GetString(0),r.GetString(1),r.GetInt64(2),r.GetInt32(3)!=0,r.GetInt32(4)!=0,r.GetInt32(5)!=0,r.GetInt32(6)!=0)));
    public Task<IReadOnlyList<ConversationFolder>> FoldersAsync()=>Owned<IReadOnlyList<ConversationFolder>>(db=>db.Query("SELECT id,name,sort_order,include_all,unread_only,exclude_muted,include_archived FROM conversation_folders ORDER BY sort_order,id",r=>new ConversationFolder(r.GetString(0),r.GetString(1),r.GetInt64(2),r.GetInt32(3)!=0,r.GetInt32(4)!=0,r.GetInt32(5)!=0,r.GetInt32(6)!=0)),notify:false);
    public Task<bool> ChangeAsync(string peer,string action,long? until=null,string? folderId=null)=>Owned(db=>{
        if(!db.Exists("SELECT 1 FROM conversations WHERE uuid=$p0",peer))throw new InvalidOperationException("Conversation is unavailable.");
        if (folderId is not null)
        {
            ChangeFolderPin(db, folderId, peer, action);
            return true;
        }
        db.Execute("INSERT OR IGNORE INTO conversation_preferences(peer) VALUES($p0)",peer);
        switch(action){
            case "pin":db.Execute("UPDATE conversation_preferences SET pin_order=(SELECT COALESCE(MAX(pin_order),0)+1 FROM conversation_preferences) WHERE peer=$p0",peer);break;
            case "unpin":db.Execute("UPDATE conversation_preferences SET pin_order=0 WHERE peer=$p0",peer);break;
            case "archive":case "unarchive":db.Execute("UPDATE conversation_preferences SET archived=$p0 WHERE peer=$p1",action=="archive"?1:0,peer);break;
            case "unread":case "clear_unread":db.Execute("UPDATE conversation_preferences SET manual_unread=$p0 WHERE peer=$p1",action=="unread"?1:0,peer);break;
            case "mute":case "unmute":var value=action=="unmute"?0:until??PermanentMuteUntil;if(value!=0 && (value<=DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() || value>PermanentMuteUntil))throw new ArgumentOutOfRangeException(nameof(until));db.Execute("UPDATE conversation_preferences SET mute_until=$p0 WHERE peer=$p1",value,peer);break;
            default:throw new ArgumentException("Unsupported conversation action.",nameof(action));
        }return true;
    });
    private static void ChangeFolderPin(VaultTransaction db, string folderId, string peer, string action)
    {
        switch (action)
        {
            case "pin":
                db.Execute("""
                    INSERT INTO conversation_folder_pins(folder_id, peer, pin_order)
                    SELECT $p0, $p1, COALESCE(MAX(pin_order), 0) + 1 FROM conversation_folder_pins WHERE folder_id = $p0
                    ON CONFLICT(folder_id, peer) DO NOTHING
                    """, folderId, peer);
                break;
            case "unpin":
                db.Execute("DELETE FROM conversation_folder_pins WHERE folder_id = $p0 AND peer = $p1", folderId, peer);
                break;
            default:
                throw new ArgumentException("Unsupported folder pin action.", nameof(action));
        }
    }

    public Task<bool> MovePinAsync(string scope, string peer, string target, bool before) => Owned(db =>
    {
        var custom = scope is not ("all" or "archive" or "unread");
        static (string Peer, long Rank) ReadPin(Microsoft.Data.Sqlite.SqliteDataReader row) => (row.GetString(0), row.GetInt64(1));
        var pins = (custom ? db.Query("""
            SELECT fp.peer, fp.pin_order
            FROM conversation_folder_pins fp
            JOIN conversation_folders f ON f.id = fp.folder_id
            JOIN conversation_summaries s ON s.uuid = fp.peer
            WHERE fp.folder_id = $p0
              AND (f.include_all = 1 OR EXISTS (
                  SELECT 1 FROM conversation_folder_members fm WHERE fm.folder_id = f.id AND fm.peer = fp.peer
              ))
              AND (f.include_archived = 1 OR s.archived = 0)
              AND (f.unread_only = 0 OR s.unreadCount > 0 OR s.manualUnread = 1)
              AND (f.exclude_muted = 0 OR s.muteUntil <= $p1)
            ORDER BY fp.pin_order DESC, fp.peer
            """, ReadPin, scope, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()) : db.Query("""
            SELECT uuid, pinOrder FROM conversation_summaries
            WHERE pinOrder > 0 AND archived = $p0
              AND ($p1 = 0 OR unreadCount > 0 OR manualUnread = 1)
            ORDER BY pinOrder DESC, uuid
            """, ReadPin, scope == "archive" ? 1 : 0, scope == "unread" ? 1 : 0)).ToList();
        var source = pins.FindIndex(pin => pin.Peer == peer);
        if (source < 0 || peer == target) return false;
        var order = pins.Select(pin => pin.Peer).ToList();
        order.RemoveAt(source);
        var reference = order.IndexOf(target);
        if (reference < 0) return false;
        var destination = reference + (before ? 0 : 1);
        order.Insert(destination, peer);
        for (var index = Math.Min(source, destination); index <= Math.Max(source, destination); index++)
        {
            if (custom)
                db.Execute("UPDATE conversation_folder_pins SET pin_order = $p0 WHERE folder_id = $p1 AND peer = $p2", pins[index].Rank, scope, order[index]);
            else
                db.Execute("UPDATE conversation_preferences SET pin_order = $p0 WHERE peer = $p1", pins[index].Rank, order[index]);
        }
        return true;
    });

    public Task<bool> SaveAsync(ConversationFolder folder,IReadOnlyCollection<string> peers)=>Owned(db=>{
        var id=Guid.Parse(folder.Id).ToString("D");var name=folder.Name.Trim();if(name.Length==0 || name.Contains('\0') || Encoding.UTF8.GetByteCount(name)>128 || peers.Count>10000)throw new ArgumentException("Invalid folder.",nameof(folder));
        if(!db.Exists("SELECT 1 FROM conversation_folders WHERE id=$p0",id) && db.Number("SELECT COUNT(*) FROM conversation_folders")>=20)throw new InvalidOperationException("At most 20 folders are supported.");
        db.Execute("INSERT INTO conversation_folders(id,name,sort_order,include_all,unread_only,exclude_muted,include_archived) VALUES($p0,$p1,(SELECT COALESCE(MAX(sort_order),0)+1 FROM conversation_folders),$p2,$p3,$p4,$p5) ON CONFLICT(id) DO UPDATE SET name=excluded.name,include_all=excluded.include_all,unread_only=excluded.unread_only,exclude_muted=excluded.exclude_muted,include_archived=excluded.include_archived",id,name,folder.IncludeAll?1:0,folder.UnreadOnly?1:0,folder.ExcludeMuted?1:0,folder.IncludeArchived?1:0);
        db.Execute("DELETE FROM conversation_folder_members WHERE folder_id=$p0",id);foreach(var peer in peers.Distinct(StringComparer.Ordinal))db.Execute("INSERT INTO conversation_folder_members(folder_id,peer) VALUES($p0,$p1)",id,peer);return true;
    });
    public Task<bool> DeleteAsync(string id)=>Owned(db=>db.Execute("DELETE FROM conversation_folders WHERE id=$p0",id)>0);
    public Task<bool> MoveAsync(string id, string targetId, bool before) => Owned(db =>
    {
        var ids = db.Query("SELECT id FROM conversation_folders ORDER BY sort_order, id", row => row.GetString(0)).ToList();
        if (id == targetId || !ids.Contains(id) || !ids.Contains(targetId)) return false;
        ids.Remove(id);
        ids.Insert(ids.IndexOf(targetId) + (before ? 0 : 1), id);
        for (var index = 0; index < ids.Count; index++)
        {
            db.Execute("UPDATE conversation_folders SET sort_order = $p0 WHERE id = $p1", index + 1, ids[index]);
        }
        return true;
    });
}
