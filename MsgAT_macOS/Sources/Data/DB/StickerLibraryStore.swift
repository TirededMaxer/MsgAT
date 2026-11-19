// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

struct StickerPack:Identifiable,Sendable {let id:String;let name:String;let count:Int}
struct StickerItem:Identifiable,Sendable {let id:String;let reference:String;let sha256:String;let mime:String;let size:Int64;let width:Int;let height:Int;let frames:Int;let label:String;let favorite:Bool;let addedAt:Int64;let lastUsed:Int64}
struct StickerSelection:Sendable {let entry:StickerSourceEntry;var expectedSha256:String?=nil}
struct StickerImportResult:Sendable {let packId:String;let added:Int;let duplicates:Int}
struct StickerImportProgress:Sendable {let phase:String;let done:Int;let total:Int}
final class StickerImportCancellation:@unchecked Sendable {
    private let lock=NSLock();private var cancelled=false
    func cancel(){lock.lock();cancelled=true;lock.unlock()}
    func check() throws {lock.lock();defer {lock.unlock()};if cancelled {throw CancellationError()}}
}
private struct PreparedSticker {let entry:StickerSourceEntry;let info:StickerImageInfo;let mime:String;let sha:String;let needsImport:Bool;let size:Int;var manifest:[String:Any]?=nil}
final class StickerLibraryStore:@unchecked Sendable {
    static let shared=StickerLibraryStore()
    private let queue=DispatchQueue(label:"org.msgat.sticker-library",qos:.utility);private let gate=NSLock();private var closed=false
    private func requireOpen() throws {gate.lock();defer {gate.unlock()};if closed {throw CancellationError()}}
    func close(){gate.lock();closed=true;gate.unlock();queue.sync{}}
    private func failure(_ message:String)->Error {SecurityCore.Failure.operation(message)}
    func packs() throws -> [StickerPack] {try AppDatabase.shared.query("SELECT p.id,p.name,COUNT(m.sticker_id) FROM sticker_packs p LEFT JOIN sticker_pack_items m ON m.pack_id=p.id GROUP BY p.id ORDER BY p.created_at,p.id"){StickerPack(id:String(cString:sqlite3_column_text($0,0)),name:String(cString:sqlite3_column_text($0,1)),count:Int(sqlite3_column_int($0,2)))}}
    func list(pack: String? = nil, favorites: Bool = false, recent: Bool = false,
              search: String = "", offset: Int = 0, animated: Bool = false) throws -> [StickerItem] {
        try AppDatabase.shared.query("""
            SELECT s.id,s.object_id,s.sha256,s.mime,s.size,s.width,s.height,s.frames,s.label,s.favorite,s.added_at,s.last_used FROM sticker_items s
            WHERE (? IS NULL OR EXISTS(SELECT 1 FROM sticker_pack_items m WHERE m.pack_id=? AND m.sticker_id=s.id)) AND (?=0 OR s.favorite=1) AND (?=0 OR s.last_used>0) AND (?='' OR instr(lower(s.label),lower(?))>0)
            AND (?=0 OR s.frames>1)
            ORDER BY CASE WHEN ?=1 THEN s.last_used ELSE s.added_at END DESC,s.id DESC LIMIT 60 OFFSET ?
            """, values: [pack.map(DatabaseValue.text) ?? .null, pack.map(DatabaseValue.text) ?? .null,
                           .integer(favorites ? 1 : 0), .integer(recent ? 1 : 0), .text(search), .text(search),
                           .integer(animated ? 1 : 0), .integer(recent ? 1 : 0), .integer(Int64(max(0, min(offset, 2000))))]) { r in
            StickerItem(id:String(cString:sqlite3_column_text(r,0)),reference:EncryptedObjectStore.reference(String(cString:sqlite3_column_text(r,1))),sha256:String(cString:sqlite3_column_text(r,2)),mime:String(cString:sqlite3_column_text(r,3)),size:sqlite3_column_int64(r,4),width:Int(sqlite3_column_int(r,5)),height:Int(sqlite3_column_int(r,6)),frames:Int(sqlite3_column_int(r,7)),label:String(cString:sqlite3_column_text(r,8)),favorite:sqlite3_column_int(r,9) != 0,addedAt:sqlite3_column_int64(r,10),lastUsed:sqlite3_column_int64(r,11))
        }
    }
    static func source(_ db:AppDatabase.Transaction,id:String) throws -> (manifest:String,sha:String)? {try db.query("SELECT o.manifest_json,s.sha256 FROM sticker_items s JOIN media_objects o ON o.object_id=s.object_id AND o.local_state=1 WHERE s.id=?",values:[.text(id)]){(String(cString:sqlite3_column_text($0,0)),String(cString:sqlite3_column_text($0,1)))}.first}
    func importItems(source:StickerSource,selection:[StickerSelection],packName:String,packId:String?=nil,cancellation:StickerImportCancellation,inspect:@escaping @Sendable(Data,StickerImageInfo)throws->Void,progress:@escaping @Sendable(StickerImportProgress)->Void,completion:@escaping @Sendable(Result<StickerImportResult,Error>)->Void){
        queue.async {
            var prepared=[PreparedSticker]();var committed=false
            defer {if !committed {for row in prepared {if let object=row.manifest {try? EncryptedObjectStore.shared.discardCopy(object)}}}}
            do {
                let name=packName.trimmingCharacters(in:.whitespacesAndNewlines)
                guard (packId != nil || !name.isEmpty && name.utf8.count <= 128),
                      (1...200).contains(selection.count), Set(selection.map { $0.entry.id }).count == selection.count
                else { throw self.failure("Choose 1–200 stickers and a valid group name.") }
                var hashes=Set<String>();var duplicates=0;var budget:Int64=0
                for (index,chosen) in selection.enumerated(){try self.requireOpen();try cancellation.check();var image=try source.readOnImportWorker(chosen.entry);defer {image.clear()}
                    guard chosen.expectedSha256==nil || chosen.expectedSha256==image.sha256 else {throw self.failure("Selected sticker changed; select its source again.")}
                    try image.info.validate();try inspect(image.bytes,image.info)
                    if hashes.insert(image.sha256).inserted {
                        let exists=try !AppDatabase.shared.query("SELECT 1 FROM sticker_items WHERE sha256=?",values:[.text(image.sha256)],map:{_ in true}).isEmpty
                        prepared.append(PreparedSticker(entry:chosen.entry,info:image.info,mime:image.mime,sha:image.sha256,needsImport:!exists,size:image.bytes.count))
                        if exists {duplicates+=1}else {budget+=Int64(image.bytes.count)+(Int64(image.bytes.count)+65535)/65536*16}
                    } else {duplicates+=1}
                    progress(StickerImportProgress(phase:"Scanning",done:index+1,total:selection.count))
                }
                try EncryptedObjectStore.shared.requireFreeSpace(budget)
                for index in prepared.indices {try self.requireOpen();try cancellation.check();let row=prepared[index]
                    if row.needsImport {var image=try source.readOnImportWorker(row.entry);defer {image.clear()}
                        guard image.sha256==row.sha else {throw self.failure("Selected sticker changed during preparation.")}
                        prepared[index].manifest=try EncryptedObjectStore.shared.importBytes(image.bytes,filename:"sticker.\(image.ext)",mime:image.mime)
                    }
                    progress(StickerImportProgress(phase:"Encrypting",done:index+1,total:prepared.count))
                }
                try self.requireOpen();try cancellation.check()
                let result=try AppDatabase.shared.transaction(notifyChanged:true){db in
                    let now=Int64(Date().timeIntervalSince1970*1000);let destination=packId ?? UUID().uuidString.lowercased()
                    if packId==nil {try db.execute("INSERT INTO sticker_packs(id,name,created_at) VALUES(?,?,?)",values:[.text(destination),.text(name),.integer(now)])}
                    else {guard try !db.query("SELECT 1 FROM sticker_packs WHERE id=?",values:[.text(destination)],map:{_ in true}).isEmpty else {throw self.failure("Selected sticker group was deleted.")}}
                    var position=try self.number(db,"SELECT COALESCE(MAX(position),-1)+1 FROM sticker_pack_items WHERE pack_id=?",[.text(destination)]);var added=0
                    for row in prepared {
                        var id=try db.query("SELECT id FROM sticker_items WHERE sha256=?",values:[.text(row.sha)],map:{String(cString:sqlite3_column_text($0,0))}).first
                        if id==nil {
                            guard let object=row.manifest else {throw self.failure("A duplicate sticker changed during import.")}
                            let count=try self.number(db,"SELECT COUNT(*) FROM sticker_items");let bytes=try self.number(db,"SELECT COALESCE(SUM(size),0) FROM sticker_items")
                            guard count<2000,bytes+Int64(row.size)<=2*1024*1024*1024 else {throw self.failure("Sticker library is full.")}
                            try EncryptedObjectStore.remember(object,in:db,available:true);id=UUID().uuidString.lowercased()
                            let label=String(row.entry.name.split(whereSeparator:{$0=="/" || $0=="\\"}).last ?? "sticker")
                            try db.execute("INSERT INTO sticker_items(id,object_id,sha256,mime,size,width,height,frames,label,added_at) VALUES(?,?,?,?,?,?,?,?,?,?)",values:[.text(id!),.text(object["object_id"] as? String ?? ""),.text(row.sha),.text(row.mime),.integer(Int64(row.size)),.integer(Int64(row.info.width)),.integer(Int64(row.info.height)),.integer(Int64(row.info.frames)),.text(label),.integer(now)]);added+=1
                        }
                        try db.execute("INSERT OR IGNORE INTO sticker_pack_items(pack_id,sticker_id,position) VALUES(?,?,?)",values:[.text(destination),.text(id!),.integer(position)]);position+=1
                    }
                    return StickerImportResult(packId:destination,added:added,duplicates:duplicates)
                }
                committed=true;completion(.success(result))
            }catch{completion(.failure(error))}
        }
    }
    private func number(_ db:AppDatabase.Transaction,_ sql:String,_ values:[DatabaseValue]=[]) throws -> Int64 {try db.query(sql,values:values,map:{sqlite3_column_int64($0,0)}).first ?? 0}
    private func mutate(_ sql:String,_ values:[DatabaseValue],completion:@escaping @Sendable(Bool)->Void){queue.async {do {try self.requireOpen();try AppDatabase.shared.transaction(notifyChanged:true){try $0.execute(sql,values:values)};completion(true)}catch{completion(false)}}}
    func favorite(_ id:String,value:Bool,completion:@escaping @Sendable(Bool)->Void){mutate("UPDATE sticker_items SET favorite=? WHERE id=?",[.integer(value ? 1:0),.text(id)],completion:completion)}
    func delete(_ id:String,completion:@escaping @Sendable(Bool)->Void){mutate("DELETE FROM sticker_items WHERE id=?",[.text(id)],completion:completion)}
    func removeFromPack(_ pack:String,id:String,completion:@escaping @Sendable(Bool)->Void){mutate("DELETE FROM sticker_pack_items WHERE pack_id=? AND sticker_id=?",[.text(pack),.text(id)],completion:completion)}
    func deletePack(_ id:String,completion:@escaping @Sendable(Bool)->Void){mutate("DELETE FROM sticker_packs WHERE id=?",[.text(id)],completion:completion)}
    func renamePack(_ id:String,name:String,completion:@escaping @Sendable(Bool)->Void){let value=name.trimmingCharacters(in:.whitespacesAndNewlines);guard !value.isEmpty,value.utf8.count<=128 else {completion(false);return};mutate("UPDATE sticker_packs SET name=? WHERE id=?",[.text(value),.text(id)],completion:completion)}
}
