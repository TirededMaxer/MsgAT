// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

struct PinnedMessageItem:Identifiable,Sendable,Equatable {let id:String;let timestamp:Int64;let kind:String;let summary:String}
struct PinnedMessagePage:Sendable,Equatable {let items:[PinnedMessageItem];let hasMore:Bool}

enum MessagePinsStore {
    static let schema=[
        "CREATE TABLE IF NOT EXISTS message_pin_clocks(peer TEXT NOT NULL REFERENCES conversations(uuid) ON DELETE CASCADE,message_id TEXT NOT NULL,author TEXT NOT NULL,actor TEXT NOT NULL,sequence INTEGER NOT NULL,addition INTEGER NOT NULL,removed INTEGER NOT NULL,PRIMARY KEY(peer,message_id,author,actor))",
        "CREATE INDEX IF NOT EXISTS message_pin_target ON message_pin_clocks(message_id,peer)",
        "CREATE TRIGGER IF NOT EXISTS message_pin_delete AFTER DELETE ON messages BEGIN DELETE FROM message_pin_clocks WHERE peer=OLD.conversationUuid AND message_id=OLD.messageId; END",
        "CREATE TRIGGER IF NOT EXISTS message_pin_revoke AFTER UPDATE OF isRevoked ON messages WHEN NEW.isRevoked=1 BEGIN DELETE FROM message_pin_clocks WHERE peer=NEW.conversationUuid AND message_id=NEW.messageId; END"
    ]
    private static func scalar(_ db:AppDatabase.Transaction,_ sql:String,_ values:[DatabaseValue]=[])throws->Int64 {try db.query(sql,values:values,map:{sqlite3_column_int64($0,0)}).first ?? 0}
    static func body(_ db:AppDatabase.Transaction,peer:String,local:String,target:String,pinned:Bool,notify:Bool=false)throws->[String:Any] {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard let author = try db.query("""
            SELECT CASE WHEN isMine=1 THEN ? ELSE conversationUuid END
            FROM messages m WHERE messageId=? AND conversationUuid=? AND isRevoked=0
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?4)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?4-r.duration_ms)
            """, values: [.text(local), .text(target), .text(peer), .integer(now)],
            map: { String(cString: sqlite3_column_text($0, 0)) }).first else {
            throw MsgATDatabaseError(operation: "Pinned messages", detail: "This message is no longer available.")
        }
        let next=try scalar(db,"SELECT sequence FROM message_pin_clocks WHERE peer=? AND message_id=? AND author=? AND actor=?",[.text(peer),.text(target),.text(author),.text(local)])+1
        guard next<=Int64(UInt32.max) else {throw MsgATDatabaseError(operation:"Pinned messages",detail:"The pin revision limit was reached.")}
        let observed:[[String:Any]]=pinned ? [] : try db.query("SELECT actor,addition FROM message_pin_clocks WHERE peer=? AND message_id=? AND author=? AND addition>removed ORDER BY actor",values:[.text(peer),.text(target),.text(author)],map:{["actor":String(cString:sqlite3_column_text($0,0)),"sequence":sqlite3_column_int64($0,1)]})
        return ["message_id":target,"author":author,"sequence":next,"pinned":pinned,"observed":observed,"notify":pinned && notify]
    }
    @discardableResult static func apply(_ db:AppDatabase.Transaction,peer:String,local:String,sender:String,body:[String:Any],onNotify:((String)->Void)?=nil)throws->Bool {
        guard let target=body["message_id"] as? String,let author=body["author"] as? String,let sequence=body["sequence"] as? Int64,let pinned=body["pinned"] as? Bool,let observed=body["observed"] as? [[String:Any]],observed.count<=2,
              [local,peer].contains(sender),[local,peer].contains(author),(1...Int64(UInt32.max)).contains(sequence) else {return false}
        guard try !db.query("SELECT 1 FROM conversations WHERE uuid=?",values:[.text(peer)],map:{_ in true}).isEmpty else{return false}
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if let deadline = try MessageExpirationStore.deadline(db, id: target), deadline <= now { return false }
        let known = try db.query("""
            SELECT m.conversationUuid,m.isMine,m.isRevoked OR EXISTS(
                SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?-r.duration_ms)
            FROM messages m WHERE m.messageId=?
            """, values: [.integer(now), .text(target)]) {
                (String(cString: sqlite3_column_text($0, 0)), sqlite3_column_int($0, 1) != 0, sqlite3_column_int($0, 2) != 0)
            }.first
        if let known,known.0 != peer || known.2 || author != (known.1 ? local : peer) {return false}
        if known==nil,try db.query("SELECT 1 FROM message_pin_clocks WHERE peer=? AND message_id=? AND author=? AND actor=?",values:[.text(peer),.text(target),.text(author),.text(sender)],map:{_ in true}).isEmpty {
            let peerCount=try scalar(db,"SELECT COUNT(*) FROM message_pin_clocks p WHERE p.peer=? AND NOT EXISTS(SELECT 1 FROM messages m WHERE m.messageId=p.message_id AND m.conversationUuid=p.peer)",[.text(peer)])
            let total=try scalar(db,"SELECT COUNT(*) FROM message_pin_clocks p WHERE NOT EXISTS(SELECT 1 FROM messages m WHERE m.messageId=p.message_id AND m.conversationUuid=p.peer)")
            if peerCount>=256 || total>=8192{return false}
        }
        let notify = try onNotify != nil && known != nil && pinned && body["notify"] as? Bool == true &&
            sequence > scalar(db,"SELECT sequence FROM message_pin_clocks WHERE peer=? AND message_id=? AND author=? AND actor=?",[.text(peer),.text(target),.text(author),.text(sender)])
        try db.execute("""
            INSERT INTO message_pin_clocks(peer,message_id,author,actor,sequence,addition,removed) VALUES(?,?,?,?,?,?,0)
            ON CONFLICT(peer,message_id,author,actor) DO UPDATE SET
            addition=CASE WHEN excluded.sequence>sequence THEN excluded.addition ELSE addition END,sequence=MAX(sequence,excluded.sequence)
            """,values:[.text(peer),.text(target),.text(author),.text(sender),.integer(sequence),.integer(pinned ? sequence : 0)])
        for clock in observed {
            guard let actor=clock["actor"] as? String,[local,peer].contains(actor),let removed=clock["sequence"] as? Int64 else{continue}
            try db.execute("UPDATE message_pin_clocks SET removed=MAX(removed,MIN(sequence,?)) WHERE peer=? AND message_id=? AND author=? AND actor=?",values:[.integer(removed),.text(peer),.text(target),.text(author),.text(actor)])
        }
        if notify { onNotify?(target) }
        return true
    }
    static func states(_ db:AppDatabase.Transaction,peer:String,local:String,ids:[String])throws->Set<String> {
        var result=Set<String>()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        for start in stride(from:0,to:ids.count,by:400){
            let chunk=Array(ids[start..<min(start+400,ids.count)])
            let placeholders=Array(repeating:"?",count:chunk.count).joined(separator:",")
            let rows=try db.query("""
                SELECT m.messageId FROM messages m WHERE m.conversationUuid=? AND m.isRevoked=0 AND m.messageId IN (\(placeholders))
                 AND EXISTS(SELECT 1 FROM message_pin_clocks p WHERE p.peer=m.conversationUuid AND p.message_id=m.messageId
                  AND p.author=CASE WHEN m.isMine=1 THEN ? ELSE m.conversationUuid END AND p.addition>p.removed)
                 AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?\(chunk.count + 3))
                 AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                     AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?\(chunk.count + 3)-r.duration_ms)
                """,values:[.text(peer)]+chunk.map(DatabaseValue.text)+[.text(local), .integer(now)],map:{String(cString:sqlite3_column_text($0,0))})
            result.formUnion(rows)
        }
        return result
    }
    static func list(_ db:AppDatabase.Transaction,peer:String,local:String,offset:Int=0,pageSize:Int=50,
                     through: PinnedMessageItem? = nil)throws->PinnedMessagePage {
        guard offset >= 0, (1...50).contains(pageSize) else {
            throw MsgATDatabaseError(operation:"Pinned messages",detail:"Invalid page bounds.")
        }
        var values: [DatabaseValue] = [.text(peer), .text(local), .integer(Int64(Date().timeIntervalSince1970 * 1000))]
        let anchorOrder: String
        if let through {
            anchorOrder = "CASE WHEN (m.timestamp,m.messageId)<=(?,?) THEN 0 ELSE 1 END,"
            values.append(contentsOf: [.integer(through.timestamp), .text(through.id)])
        } else {
            anchorOrder = ""
        }
        values.append(contentsOf: [.integer(Int64(pageSize + 1)), .integer(Int64(offset))])
        let rows=try db.query("""
            WITH active_pins AS (
                SELECT DISTINCT message_id,author FROM message_pin_clocks
                WHERE peer=?1 AND addition>removed
            )
            SELECT m.messageId,m.timestamp,m.ext,
             CASE WHEN m.ext='txt' THEN substr(m.content,1,240) WHEN m.ext='contact' THEN 'Contact: '||substr(m.content,1,230) WHEN m.ext IN ('location','live_location') THEN substr(m.content,1,240) WHEN m.ext='attachment' THEN COALESCE(NULLIF(substr(a.caption,1,240),''),json_extract(o.manifest_json,'$.filename'),'[Attachment]') ELSE '[Call]' END
            FROM active_pins p CROSS JOIN messages m ON m.messageId=p.message_id
            LEFT JOIN message_attachments a ON a.message_id=m.messageId
            LEFT JOIN media_objects o ON o.object_id=a.object_id
            WHERE m.conversationUuid=?1 AND m.isRevoked=0
              AND p.author=CASE WHEN m.isMine=1 THEN ?2 ELSE m.conversationUuid END
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=m.messageId AND e.expires_at<=?3)
              AND NOT EXISTS(SELECT 1 FROM conversation_retention r WHERE r.peer=m.conversationUuid
                  AND (m.isMine=0 OR m.status>=2) AND m.timestamp<=?3-r.duration_ms)
            ORDER BY \(anchorOrder)m.timestamp DESC,m.messageId DESC LIMIT ? OFFSET ?
            """,values:values,
            map:{PinnedMessageItem(id:String(cString:sqlite3_column_text($0,0)),timestamp:sqlite3_column_int64($0,1),kind:String(cString:sqlite3_column_text($0,2)),summary:String(cString:sqlite3_column_text($0,3)))})
        return PinnedMessagePage(items:Array(rows.prefix(pageSize)),hasMore:rows.count>pageSize)
    }
}
