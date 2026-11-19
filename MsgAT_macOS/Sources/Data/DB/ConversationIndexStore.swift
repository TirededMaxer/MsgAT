// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher
struct ConversationFolder:Sendable,Identifiable,Equatable {let id:String;var name:String;var sortOrder:Int64=0;var includeAll=false;var unreadOnly=false;var excludeMuted=false;var includeArchived=false}
enum ConversationIndexStore {
    static let permanentMuteUntil: Int64 = 253402214399999

    static func muteUntil(_ db: AppDatabase.Transaction, peer: String) throws -> Int64 {
        try db.query("SELECT mute_until FROM conversation_preferences WHERE peer=?", values: [.text(peer)]) {
            sqlite3_column_int64($0, 0)
        }.first ?? 0
    }
    static let schema=[
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
    ]
    static func muted(_ db:AppDatabase.Transaction,peer:String,now:Int64)throws->Bool {try !db.query("SELECT 1 FROM conversation_preferences WHERE peer=? AND mute_until>?",values:[.text(peer),.integer(now)],map:{_ in true}).isEmpty}
    static func setArchived(_ db: AppDatabase.Transaction, peer: String, expected: Bool, archived: Bool) throws -> Bool {
        try db.execute("INSERT OR IGNORE INTO conversation_preferences(peer) SELECT uuid FROM conversations WHERE uuid=?",
                       values: [.text(peer)])
        try db.execute("UPDATE conversation_preferences SET archived=? WHERE peer=? AND archived=?",
                       values: [.integer(archived ? 1 : 0), .text(peer), .integer(expected ? 1 : 0)])
        return try !db.query("SELECT 1 WHERE changes()=1", map: { _ in true }).isEmpty
    }
    static func folders()throws->[ConversationFolder]{try AppDatabase.shared.query("SELECT id,name,sort_order,include_all,unread_only,exclude_muted,include_archived FROM conversation_folders ORDER BY sort_order,id",values:[]){r in ConversationFolder(id:String(cString:sqlite3_column_text(r,0)),name:String(cString:sqlite3_column_text(r,1)),sortOrder:sqlite3_column_int64(r,2),includeAll:sqlite3_column_int(r,3) != 0,unreadOnly:sqlite3_column_int(r,4) != 0,excludeMuted:sqlite3_column_int(r,5) != 0,includeArchived:sqlite3_column_int(r,6) != 0)}}
    static func change(_ db:AppDatabase.Transaction,peer:String,action:String,until:Int64?=nil,folderID:String?=nil)throws {
        guard try !db.query("SELECT 1 FROM conversations WHERE uuid=?",values:[.text(peer)],map:{_ in true}).isEmpty else{throw MsgATDatabaseError(operation:"Conversations",detail:"The conversation or folder is unavailable or invalid.")}
        if let folderID {
            try changeFolderPin(db, folderID: folderID, peer: peer, action: action)
            return
        }
        try db.execute("INSERT OR IGNORE INTO conversation_preferences(peer) VALUES(?)",values:[.text(peer)])
        switch action {
        case "pin":try db.execute("UPDATE conversation_preferences SET pin_order=(SELECT COALESCE(MAX(pin_order),0)+1 FROM conversation_preferences) WHERE peer=?",values:[.text(peer)])
        case "unpin":try db.execute("UPDATE conversation_preferences SET pin_order=0 WHERE peer=?",values:[.text(peer)])
        case "archive","unarchive":try db.execute("UPDATE conversation_preferences SET archived=? WHERE peer=?",values:[.integer(action=="archive" ? 1:0),.text(peer)])
        case "unread","clear_unread":try db.execute("UPDATE conversation_preferences SET manual_unread=? WHERE peer=?",values:[.integer(action=="unread" ? 1:0),.text(peer)])
        case "mute","unmute":let value=action=="unmute" ? 0:until ?? permanentMuteUntil;guard value==0 || value>Int64(Date().timeIntervalSince1970*1000) && value<=permanentMuteUntil else{throw MsgATDatabaseError(operation:"Conversations",detail:"The conversation or folder is unavailable or invalid.")};try db.execute("UPDATE conversation_preferences SET mute_until=? WHERE peer=?",values:[.integer(value),.text(peer)])
        default:throw MsgATDatabaseError(operation:"Conversations",detail:"The conversation or folder is unavailable or invalid.")
        }
    }
    private static func changeFolderPin(_ db: AppDatabase.Transaction, folderID: String, peer: String, action: String) throws {
        switch action {
        case "pin":
            try db.execute("""
                INSERT INTO conversation_folder_pins(folder_id, peer, pin_order)
                SELECT ?, ?, COALESCE(MAX(pin_order), 0) + 1 FROM conversation_folder_pins WHERE folder_id = ?
                ON CONFLICT(folder_id, peer) DO NOTHING
                """, values: [.text(folderID), .text(peer), .text(folderID)])
        case "unpin":
            try db.execute("DELETE FROM conversation_folder_pins WHERE folder_id = ? AND peer = ?", values: [.text(folderID), .text(peer)])
        default:
            throw MsgATDatabaseError(operation: "Conversations", detail: "Unsupported folder pin action.")
        }
    }

    static func movePin(_ db: AppDatabase.Transaction, scope: String, peer: String, target: String, before: Bool) throws -> Bool {
        let custom = !["all", "archive", "unread"].contains(scope)
        let sql: String
        let values: [DatabaseValue]
        if custom {
            sql = """
            SELECT fp.peer, fp.pin_order
            FROM conversation_folder_pins fp
            JOIN conversation_folders f ON f.id = fp.folder_id
            JOIN conversation_summaries s ON s.uuid = fp.peer
            WHERE fp.folder_id = ?
              AND (f.include_all = 1 OR EXISTS (
                  SELECT 1 FROM conversation_folder_members fm WHERE fm.folder_id = f.id AND fm.peer = fp.peer
              ))
              AND (f.include_archived = 1 OR s.archived = 0)
              AND (f.unread_only = 0 OR s.unreadCount > 0 OR s.manualUnread = 1)
              AND (f.exclude_muted = 0 OR s.muteUntil <= ?)
            ORDER BY fp.pin_order DESC, fp.peer
            """
            values = [.text(scope), .integer(Int64(Date().timeIntervalSince1970 * 1000))]
        } else {
            sql = """
            SELECT uuid, pinOrder FROM conversation_summaries
            WHERE pinOrder > 0 AND archived = ?
              AND (? = 0 OR unreadCount > 0 OR manualUnread = 1)
            ORDER BY pinOrder DESC, uuid
            """
            values = [.integer(scope == "archive" ? 1 : 0), .integer(scope == "unread" ? 1 : 0)]
        }
        let pins = try db.query(sql, values: values) { row in
            (peer: String(cString: sqlite3_column_text(row, 0)), rank: sqlite3_column_int64(row, 1))
        }
        guard peer != target, let source = pins.firstIndex(where: { $0.peer == peer }) else { return false }
        var order = pins.map { $0.peer }
        order.remove(at: source)
        guard let reference = order.firstIndex(of: target) else { return false }
        let destination = reference + (before ? 0 : 1)
        order.insert(peer, at: destination)
        for index in min(source, destination)...max(source, destination) {
            if custom {
                try db.execute("UPDATE conversation_folder_pins SET pin_order = ? WHERE folder_id = ? AND peer = ?",
                               values: [.integer(pins[index].rank), .text(scope), .text(order[index])])
            } else {
                try db.execute("UPDATE conversation_preferences SET pin_order = ? WHERE peer = ?",
                               values: [.integer(pins[index].rank), .text(order[index])])
            }
        }
        return true
    }

    static func save(_ db:AppDatabase.Transaction,folder:ConversationFolder,peers:[String])throws {
        guard let id=UUID(uuidString:folder.id)?.uuidString.lowercased() else{throw MsgATDatabaseError(operation:"Conversations",detail:"The conversation or folder is unavailable or invalid.")}
        let name=folder.name.trimmingCharacters(in:.whitespacesAndNewlines);guard !name.isEmpty,!name.contains("\0"),name.utf8.count<=128,peers.count<=10000 else{throw MsgATDatabaseError(operation:"Conversations",detail:"The conversation or folder is unavailable or invalid.")}
        let exists=try !db.query("SELECT 1 FROM conversation_folders WHERE id=?",values:[.text(id)],map:{_ in true}).isEmpty
        guard exists || (try db.query("SELECT COUNT(*) FROM conversation_folders",map:{sqlite3_column_int64($0,0)}).first ?? 0)<20 else{throw MsgATDatabaseError(operation:"Conversations",detail:"The conversation or folder is unavailable or invalid.")}
        try db.execute("INSERT INTO conversation_folders(id,name,sort_order,include_all,unread_only,exclude_muted,include_archived) VALUES(?,?,(SELECT COALESCE(MAX(sort_order),0)+1 FROM conversation_folders),?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,include_all=excluded.include_all,unread_only=excluded.unread_only,exclude_muted=excluded.exclude_muted,include_archived=excluded.include_archived",values:[.text(id),.text(name),.integer(folder.includeAll ? 1:0),.integer(folder.unreadOnly ? 1:0),.integer(folder.excludeMuted ? 1:0),.integer(folder.includeArchived ? 1:0)])
        try db.execute("DELETE FROM conversation_folder_members WHERE folder_id=?",values:[.text(id)])
        for peer in Set(peers){try db.execute("INSERT INTO conversation_folder_members(folder_id,peer) VALUES(?,?)",values:[.text(id),.text(peer)])}
    }
    static func move(_ db: AppDatabase.Transaction, id: String, targetID: String, before: Bool) throws -> Bool {
        var ids = try db.query("SELECT id FROM conversation_folders ORDER BY sort_order, id") {
            String(cString: sqlite3_column_text($0, 0))
        }
        guard id != targetID, let source = ids.firstIndex(of: id), ids.contains(targetID) else { return false }
        ids.remove(at: source)
        guard let target = ids.firstIndex(of: targetID) else { return false }
        ids.insert(id, at: target + (before ? 0 : 1))
        for (index, folderID) in ids.enumerated() {
            try db.execute("UPDATE conversation_folders SET sort_order = ? WHERE id = ?", values: [.integer(Int64(index + 1)), .text(folderID)])
        }
        return true
    }
}
