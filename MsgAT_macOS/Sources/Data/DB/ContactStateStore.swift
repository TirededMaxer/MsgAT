// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

enum ContactStateStore {
    static func read(_ db:AppDatabase.Transaction,peer:String)throws->[String:Any]{
        var state=[String:Any]()
        if let text=try db.query("SELECT state_json FROM contact_links WHERE uuid=?",values:[.text(peer)],map:{String(cString:sqlite3_column_text($0,0))}).first {
            guard let decoded=try JSONSerialization.jsonObject(with:Data(text.utf8)) as? [String:Any] else {throw SecurityCore.Failure.invalidResponse};state=decoded
        }
        if let flags=try db.query("SELECT outgoing,incoming,accepted,blocked FROM contact_states WHERE uuid=?",values:[.text(peer)],map:{r in (0..<4).map{sqlite3_column_int(r,Int32($0)) != 0}}).first {
            if state["accepted"] as? Bool == true,!flags[2] {state["accepted_ids"]=[String]()}
            for (index,key) in ["outgoing","incoming","accepted","blocked"].enumerated(){state[key]=flags[index]}
        }
        return state
    }
    static func apply(_ db:AppDatabase.Transaction,peer:String,action:[String:Any])throws->[String:Any]{
        guard peer.count==64,peer.allSatisfy({$0.isHexDigit && !$0.isUppercase}) else {throw SecurityCore.Failure.operation("Invalid contact identity")}
        if ["request","accept","unblock"].contains(action["kind"] as? String ?? ""),try !db.query("SELECT 1 FROM protocol_revocations WHERE peer=?",values:[.text(peer)],map:{_ in true}).isEmpty {throw SecurityCore.Failure.operation("This identity was revoked")}
        let result=try SecurityCore.call(["operation":"contact_transition","state":read(db,peer:peer),"action":action])
        guard let state=result["state"] as? [String:Any],let previous=result["previous"] as? [String:Any] else {throw SecurityCore.Failure.invalidResponse}
        func flag(_ key:String)->Bool {state[key] as? Bool == true}
        try db.execute("INSERT INTO contact_links(uuid,state_json) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET state_json=excluded.state_json",values:[.text(peer),.text(String(decoding:try JSONSerialization.data(withJSONObject:state,options:.sortedKeys),as:UTF8.self))])
        try db.execute("INSERT INTO contact_states(uuid,outgoing,incoming,accepted,blocked) VALUES(?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET outgoing=excluded.outgoing,incoming=excluded.incoming,accepted=excluded.accepted,blocked=excluded.blocked",values:[.text(peer),.integer(flag("outgoing") ? 1:0),.integer(flag("incoming") ? 1:0),.integer(flag("accepted") ? 1:0),.integer(flag("blocked") ? 1:0)])
        if !flag("incoming"){try db.execute("DELETE FROM friend_requests WHERE senderUuid=?",values:[.text(peer)])}
        if flag("blocked") || (previous["accepted"] as? Bool == true && !flag("accepted")) {
            try db.execute("UPDATE messages SET status=-1 WHERE conversationUuid=? AND isMine=1 AND status IN (0,1)",values:[.text(peer)])
            try db.execute("DELETE FROM protocol_pending_events WHERE recipient=?",values:[.text(peer)])
            try db.execute("UPDATE protocol_outbox SET delivered=-2 WHERE delivered>=0 AND recipient=?",values:[.text(peer)])
            try db.execute("DELETE FROM protocol_key_requests WHERE recipient=?",values:[.text(peer)])
            try db.execute("UPDATE protocol_receipts SET pending=0 WHERE peer=?",values:[.text(peer)])
            try db.execute("DELETE FROM activity_watchers WHERE peer=?",values:[.text(peer)])
            try db.execute("UPDATE activity_peers SET state_json=json_set(state_json,'$.inflight',NULL,'$.published',NULL) WHERE peer=?",values:[.text(peer)])
            try db.execute("DELETE FROM object_transfers WHERE owner_peer=? AND direction=0",values:[.text(peer)])
        }
        return result
    }
}
