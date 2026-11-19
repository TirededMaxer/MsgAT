// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

struct ContactEntry:Identifiable,Sendable {let id:String;let name:String;let avatar:String?;let accepted:Bool;let incoming:Bool;let outgoing:Bool;let blocked:Bool;let outcome:String;let revoked:Bool}
struct ContactSafetyInfo:Sendable {let display:String;let scannable:String;let verified:Bool;var matches:Bool?=nil}
private final class ContactDirectoryOwner:@unchecked Sendable {
    private let condition=NSCondition();private var active=0;private var closed=false
    func run<T>(_ action:()throws->T)throws->T {
        condition.lock();guard !closed else {condition.unlock();throw CancellationError()};active+=1;condition.unlock()
        defer {condition.lock();active-=1;if active==0 {condition.broadcast()};condition.unlock()}
        return try action()
    }
    func close(){condition.lock();closed=true;while active>0 {condition.wait()};condition.unlock()}
}
enum ContactDirectoryStore {
    private static let owner=ContactDirectoryOwner()
    static func close(){owner.close()}
    static let changed=Notification.Name("org.msgat.contacts-changed")
    static func invalidate(){NotificationCenter.default.post(name:changed,object:nil)}
    static func list(search:String="",blockedOnly:Bool=false,offset:Int=0,exactPeer:String?=nil)throws->[ContactEntry]{
        let query=String(search.trimmingCharacters(in:.whitespacesAndNewlines).prefix(256))
        return try owner.run {try AppDatabase.shared.query("""
            SELECT c.uuid,COALESCE(p.nickname,v.alias,c.uuid),p.avatar_object,c.accepted,c.incoming,c.outgoing,c.blocked,l.state_json,EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=c.uuid)
            FROM contact_states c LEFT JOIN user_profiles p ON p.peer=c.uuid LEFT JOIN conversations v ON v.uuid=c.uuid LEFT JOIN contact_links l ON l.uuid=c.uuid
            WHERE (c.accepted=1 OR c.incoming=1 OR c.outgoing=1 OR c.blocked=1 OR p.peer IS NOT NULL OR v.uuid IS NOT NULL) AND (?=0 OR c.blocked=1) AND (?='' OR instr(lower(COALESCE(p.nickname,v.alias,'')),lower(?))>0 OR instr(c.uuid,lower(?))>0)
            AND (?='' OR c.uuid=?)
            ORDER BY lower(COALESCE(p.nickname,v.alias,c.uuid)),c.uuid LIMIT 50 OFFSET ?
            """,values:[.integer(blockedOnly ? 1:0),.text(query),.text(query),.text(query),.text(exactPeer ?? ""),.text(exactPeer ?? ""),.integer(Int64(max(0,offset)))]) {r in
            let json=sqlite3_column_text(r,7).map{String(cString:$0)}
            let state=try json.flatMap {try JSONSerialization.jsonObject(with:Data($0.utf8)) as? [String:Any]}
            return ContactEntry(id:String(cString:sqlite3_column_text(r,0)),name:String(cString:sqlite3_column_text(r,1)),avatar:sqlite3_column_text(r,2).map{EncryptedObjectStore.reference(String(cString:$0))},accepted:sqlite3_column_int(r,3) != 0,incoming:sqlite3_column_int(r,4) != 0,outgoing:sqlite3_column_int(r,5) != 0,blocked:sqlite3_column_int(r,6) != 0,outcome:state?["last_outcome"] as? String ?? "none",revoked:sqlite3_column_int(r,8) != 0)
        }
        }
    }
    private static func safety(_ db:AppDatabase.Transaction,peer:String,scan:String?=nil)throws->ContactSafetyInfo{
        guard try db.query("SELECT 1 FROM protocol_revocations WHERE peer=?",values:[.text(peer)],map:{_ in true}).isEmpty else {throw SecurityCore.Failure.operation("This identity was revoked")}
        guard let encoded=try db.query("SELECT bundle_json FROM protocol_local WHERE id=1",map:{String(cString:sqlite3_column_text($0,0))}).first,
              let bundle=try JSONSerialization.jsonObject(with:Data(encoded.utf8)) as? [String:Any],let body=bundle["body"] as? String,let bytes=Data(base64Encoded:body),
              let decoded=try JSONSerialization.jsonObject(with:bytes) as? [String:Any],let local=decoded["certificate"] as? [String:Any],
              let remote=try db.query("SELECT json_extract(state_json,'$.certificate') FROM protocol_peers WHERE uuid=?",values:[.text(peer)],map:{String(cString:sqlite3_column_text($0,0))}).first,
              let certificate=try JSONSerialization.jsonObject(with:Data(remote.utf8)) as? [String:Any] else {throw SecurityCore.Failure.operation("Exchange a contact request before checking its safety code")}
        let result=try SecurityCore.call(["operation":"contact_safety","local_id":KeychainManager.shared.myUuid,"local":local,"peer_id":peer,"peer":certificate,"scan":scan.map{$0 as Any} ?? NSNull()])
        guard let display=result["display"] as? String,let fingerprint=result["scannable"] as? String else {throw SecurityCore.Failure.invalidResponse}
        let verified=try !db.query("SELECT 1 FROM contact_verifications WHERE uuid=? AND display=?",values:[.text(peer),.text(display)],map:{_ in true}).isEmpty
        return ContactSafetyInfo(display:display,scannable:fingerprint,verified:verified,matches:result["matches"] as? Bool)
    }
    static func safety(_ peer:String)throws->ContactSafetyInfo {try owner.run {try AppDatabase.shared.transaction{try safety($0,peer:peer)}}}
    static func isVerified(_ peer: String) throws -> Bool {
        try owner.run {
            try AppDatabase.shared.transaction { db in
                let verification = try db.query("""
                    SELECT 1 FROM contact_verifications v JOIN contact_states c ON c.uuid=v.uuid
                    WHERE v.uuid=? AND c.accepted=1 AND c.blocked=0
                      AND NOT EXISTS(SELECT 1 FROM protocol_revocations r WHERE r.peer=v.uuid)
                    """, values: [.text(peer)], map: { _ in true })
                guard !verification.isEmpty else { return false }
                return try safety(db, peer: peer).verified
            }
        }
    }
    static func verify(_ peer: String, expected: String) throws {
        try changeVerification { db in
            let current = try safety(db, peer: peer)
            guard expected.utf8.count == 60, expected.utf8.allSatisfy({ (48...57).contains($0) }),
                  current.display == expected else {
                throw SecurityCore.Failure.operation("Safety code changed; compare it again")
            }
            try db.execute("INSERT INTO contact_verifications(uuid,display,verified_at) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET display=excluded.display,verified_at=excluded.verified_at",
                           values: [.text(peer), .text(expected), .integer(Int64(Date().timeIntervalSince1970 * 1000))])
        }
    }
    static func verifyScan(_ peer: String, scan: String) throws {
        try changeVerification { db in
            let current = try safety(db, peer: peer, scan: scan)
            guard current.matches == true else {
                throw SecurityCore.Failure.operation("Safety QR code does not match this contact")
            }
            try db.execute("INSERT INTO contact_verifications(uuid,display,verified_at) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET display=excluded.display,verified_at=excluded.verified_at",
                           values: [.text(peer), .text(current.display), .integer(Int64(Date().timeIntervalSince1970 * 1000))])
        }
    }
    static func clearVerification(_ peer: String) throws {
        try changeVerification { db in
            try db.execute("DELETE FROM contact_verifications WHERE uuid=?", values: [.text(peer)])
        }
    }

    private static func changeVerification(_ change: (AppDatabase.Transaction) throws -> Void) throws {
        try owner.run { try AppDatabase.shared.transaction(notifyChanged: true, change) }
        invalidate()
    }
}
