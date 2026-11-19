// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

struct UserProfileSnapshot: Sendable {
    let peer: String;let nickname: String;let avatarReference: String?;let revision: Int64;let changedAt: Int64;let eventId: String
}
enum UserProfileStore {
    static func read(_ db: AppDatabase.Transaction,peer: String) throws -> UserProfileSnapshot? {
        try db.query("SELECT nickname,avatar_object,revision,changed_at,event_id FROM user_profiles WHERE peer=?",values:[.text(peer)]) {r in
            UserProfileSnapshot(peer:peer,nickname:String(cString:sqlite3_column_text(r,0)),avatarReference:sqlite3_column_type(r,1)==SQLITE_NULL ? nil : EncryptedObjectStore.reference(String(cString:sqlite3_column_text(r,1))),revision:sqlite3_column_int64(r,2),changedAt:sqlite3_column_int64(r,3),eventId:String(cString:sqlite3_column_text(r,4)))
        }.first
    }
    static func own() throws -> UserProfileSnapshot? {try AppDatabase.shared.transaction {try read($0,peer:KeychainManager.shared.myUuid)}}
    static func body(_ db: AppDatabase.Transaction,selfID: String,fallback: String) throws -> [String:Any] {
        guard let local=try read(db,peer:selfID) else {return ["nickname":fallback]}
        var avatar: Any=NSNull()
        if let ref=local.avatarReference {
            let id=String(ref.dropFirst(EncryptedObjectStore.referencePrefix.count))
            guard let encoded=try db.query("SELECT manifest_json FROM media_objects WHERE object_id=?",values:[.text(id)],map:{String(cString:sqlite3_column_text($0,0))}).first else {throw SecurityCore.Failure.invalidResponse}
            avatar=try JSONSerialization.jsonObject(with:Data(encoded.utf8))
        }
        return ["nickname":local.nickname,"revision":local.revision,"avatar":avatar]
    }
    static func saveLocal(_ db: AppDatabase.Transaction,selfID: String,nickname: String,avatar: [String:Any]?,replaceAvatar: Bool) throws -> UserProfileSnapshot {
        let name=nickname.trimmingCharacters(in:.whitespacesAndNewlines)
        guard !name.isEmpty,name.utf8.count<=256 else {throw SecurityCore.Failure.operation("Nickname is empty or too long.")}
        let old=try read(db,peer:selfID);let revision=(old?.revision ?? 0)+1
        guard revision<=Int64(UInt32.max) else {throw SecurityCore.Failure.operation("Profile revision limit reached.")}
        var id=replaceAvatar ? nil : old?.avatarReference.map {String($0.dropFirst(EncryptedObjectStore.referencePrefix.count))}
        if let avatar {try EncryptedObjectStore.remember(avatar,in:db,available:true);id=avatar["object_id"] as? String}
        let timestamp=Int64(Date().timeIntervalSince1970*1000);let event=UUID().uuidString.lowercased()
        try put(db,peer:selfID,name:name,revision:revision,id:id,created:timestamp,event:event)
        return UserProfileSnapshot(peer:selfID,nickname:name,avatarReference:id.map(EncryptedObjectStore.reference),revision:revision,changedAt:timestamp,eventId:event)
    }
    static func apply(_ db: AppDatabase.Transaction,peer: String,body: [String:Any],event: String,created: Int64) throws -> UserProfileSnapshot {
        let revision=(body["revision"] as? Int64) ?? 0
        if let old=try read(db,peer:peer),old.revision>revision || old.revision==revision && (revision>0 || old.changedAt>created || old.changedAt==created && old.eventId>=event) {return old}
        try db.execute("DELETE FROM object_transfers WHERE owner_peer=? AND owner_kind='profile' AND direction=1",values:[.text(peer)])
        var id: String?
        if let avatar=body["avatar"] as? [String:Any],let objectID=avatar["object_id"] as? String {
            try EncryptedObjectStore.remember(avatar,in:db);id=objectID
            if try db.query("SELECT 1 FROM media_objects WHERE object_id=? AND local_state=1",values:[.text(objectID)],map:{_ in true}).isEmpty {try AttachmentQueue.record(db,event:event,objectID:objectID,outgoing:false,peer:peer,created:created,ownerKind:"profile")}
        }
        guard let name=body["nickname"] as? String else {throw SecurityCore.Failure.invalidResponse}
        try put(db,peer:peer,name:name,revision:revision,id:id,created:created,event:event)
        return UserProfileSnapshot(peer:peer,nickname:name,avatarReference:id.map(EncryptedObjectStore.reference),revision:revision,changedAt:created,eventId:event)
    }
    private static func put(_ db: AppDatabase.Transaction,peer: String,name: String,revision: Int64,id: String?,created: Int64,event: String) throws {
        try db.execute("INSERT INTO user_profiles(peer,nickname,revision,avatar_object,changed_at,event_id) VALUES(?,?,?,?,?,?) ON CONFLICT(peer) DO UPDATE SET nickname=excluded.nickname,revision=excluded.revision,avatar_object=excluded.avatar_object,changed_at=excluded.changed_at,event_id=excluded.event_id",values:[.text(peer),.text(name),.integer(revision),id.map(DatabaseValue.text) ?? .null,.integer(created),.text(event)])
    }
}
