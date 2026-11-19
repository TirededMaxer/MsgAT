// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

struct WebPreviewSnapshot: Sendable {
    let url: String
    let title: String
    let summary: String
    let site: String
    let imageReference: String?
    let aboveText: Bool
    let compactImage: Bool
}
enum WebPreviewStore {
    static func enabled() throws -> Bool {try AppDatabase.shared.query("SELECT 1 FROM web_preview_preferences WHERE id=1 AND enabled=0",map:{_ in true}).isEmpty}
    static func setEnabled(_ enabled: Bool) throws {try AppDatabase.shared.execute("INSERT INTO web_preview_preferences(id,enabled) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET enabled=excluded.enabled",values:[.integer(enabled ? 1:0)])}
    static func read(_ db: AppDatabase.Transaction,id: String,saved: Bool=false) throws -> WebPreviewSnapshot? {
        try db.query("""
            SELECT url,title,summary,site,image_object,above_text,compact_image
            FROM message_previews WHERE owner_kind=? AND owner_id=?
            """, values: [.text(saved ? "saved" : "message"), .text(id)]) { row in
            WebPreviewSnapshot(url: String(cString: sqlite3_column_text(row, 0)), title: String(cString: sqlite3_column_text(row, 1)),
                summary: String(cString: sqlite3_column_text(row, 2)), site: String(cString: sqlite3_column_text(row, 3)),
                imageReference: sqlite3_column_text(row, 4).map { EncryptedObjectStore.reference(String(cString: $0)) },
                aboveText: sqlite3_column_int(row, 5) != 0, compactImage: sqlite3_column_int(row, 6) != 0)
        }.first
    }
    static func body(_ db: AppDatabase.Transaction,id: String,saved: Bool=false) throws -> [String:Any]? {
        guard let row=try read(db,id:id,saved:saved) else {return nil}
        var result: [String:Any]=["url":row.url,"title":row.title,"summary":row.summary,"site":row.site,"image":NSNull()]
        if row.aboveText { result["above_text"] = true }
        if let ref=row.imageReference {
            guard let encoded=try db.query("SELECT manifest_json FROM media_objects WHERE object_id=?",values:[.text(String(ref.dropFirst(EncryptedObjectStore.referencePrefix.count)))],map:{String(cString:sqlite3_column_text($0,0))}).first else {throw SecurityCore.Failure.invalidResponse}
            result["image"]=try JSONSerialization.jsonObject(with:Data(encoded.utf8))
            if row.compactImage { result["compact_image"] = true }
        }
        return result
    }
    static func record(_ db: AppDatabase.Transaction,id: String,preview: [String:Any]?,text: String,peer: String,outgoing: Bool,created: Int64,saved: Bool=false) throws {
        guard let preview else {return}
        if !saved,try db.query("SELECT 1 FROM messages WHERE messageId=? AND ext='txt' AND isRevoked=0 AND content=?",values:[.text(id),.text(text)],map:{_ in true}).isEmpty {return}
        let image=preview["image"] as? [String:Any];let imageID=image?["object_id"] as? String
        if let image {try EncryptedObjectStore.remember(image,in:db,available:outgoing)}
        try db.execute("""
            INSERT INTO message_previews(owner_kind,owner_id,url,title,summary,site,image_object,above_text,compact_image)
            VALUES(?,?,?,?,?,?,?,?,?)
            """, values: [.text(saved ? "saved" : "message"), .text(id), .text(preview["url"] as? String ?? ""),
                .text(preview["title"] as? String ?? ""), .text(preview["summary"] as? String ?? ""), .text(preview["site"] as? String ?? ""),
                imageID.map(DatabaseValue.text) ?? .null, .integer(preview["above_text"] as? Bool == true ? 1 : 0),
                .integer(imageID != nil && preview["compact_image"] as? Bool == true ? 1 : 0)])
        if !saved,let imageID {
            let ready=try !db.query("SELECT 1 FROM media_objects WHERE object_id=? AND local_state=1",values:[.text(imageID)],map:{_ in true}).isEmpty
            if outgoing || !ready {try AttachmentQueue.record(db,event:id,objectID:imageID,outgoing:outgoing,peer:peer,created:created)}
        }
    }
}
