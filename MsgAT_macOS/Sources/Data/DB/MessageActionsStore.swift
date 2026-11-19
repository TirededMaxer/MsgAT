// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

struct SavedMessageItem: Identifiable,Sendable {
    let id: String;let sourcePeer: String;let sourceId: String;let kind: String;let text: String
    let reference: String?;let savedAt: Int64;let author: String;let originalAt: Int64
    var contactAccount: String? = nil
}
struct SavedMessageSource: Identifiable, Sendable {
    let peer: String
    let alias: String?
    let avatar: String?
    let count: Int
    var id: String { peer }
}
struct MessageActionSnapshot: Sendable {
    let saved: Bool;let peer: String;let id: String;let kind: String;let text: String;let caption: String
    let manifest: String?;let timestamp: Int64;let author: String;let forwardName: String?;let forwardAt: Int64;var available: Bool;var previewJSON: String?=nil;var previewManifest: String?=nil;var isSticker=false
    var contactAccount: String? = nil
    var location: LocationPoint? = nil
    var previewAvailable = true
}
struct PreparedMessageAction: Sendable {let source: MessageActionSnapshot;let objectJSON: String?;var previewObjectJSON: String?=nil}
struct DamagedForwardCache: Error {
    let source: MessageActionSnapshot
    let previewOnly: Bool
}

final class MessageActionsStore: @unchecked Sendable {
    static let shared=MessageActionsStore()
    private let queue=DispatchQueue(label:"org.msgat.message-actions",qos:.utility)
    private let gate=NSLock()
    private var closed=false
    private func requireOpen() throws {gate.lock();defer {gate.unlock()};if closed {throw failure("Message actions are closed.")}}
    private func failure(_ text: String)->Error {MsgATDatabaseError(operation:"Message action",detail:text)}
    func close() {gate.lock();closed=true;gate.unlock();queue.sync {}}

    func capture(peer: String?,ids: [String],allowWaiting: Bool=false,includePreview: Bool=true) throws -> [MessageActionSnapshot] {
        try requireOpen()
        guard (1...100).contains(ids.count),Set(ids).count==ids.count else {throw failure("Select between 1 and 100 different messages.")}
        return try AppDatabase.shared.transaction {db in
            try ids.map {id in
                let sql: String
                let args: [DatabaseValue]
                if let peer {
                    try requireCopyableSource(db, peer: peer, id: id)
                    sql="""
                        SELECT m.conversationUuid,m.messageId,m.ext,m.content,COALESCE(a.caption,''),o.manifest_json,m.timestamp,
                         CASE WHEN m.isMine=1 THEN ? ELSE c.alias END,f.name,COALESCE(f.original_at,m.timestamp),o.local_state,card.account
                        FROM messages m JOIN conversations c ON c.uuid=m.conversationUuid
                        LEFT JOIN message_attachments a ON a.message_id=m.messageId LEFT JOIN media_objects o ON o.object_id=a.object_id
                        LEFT JOIN message_forwards f ON f.message_id=m.messageId
                        LEFT JOIN message_contact_cards card ON card.message_id=m.messageId
                        WHERE m.messageId=? AND m.conversationUuid=? AND m.isRevoked=0 AND m.ext IN ('txt','attachment','contact','location')
                        """
                    args=[.text(KeychainManager.shared.myNickname ?? "Me"),.text(id),.text(peer)]
                } else {
                    sql="""
                        SELECT s.source_peer,s.id,CASE WHEN EXISTS(SELECT 1 FROM saved_locations l WHERE l.id=s.id) THEN 'location' WHEN card.account IS NOT NULL THEN 'contact' ELSE s.kind END,
                         s.body,s.body,o.manifest_json,s.original_at,s.author_name,NULL,s.original_at,o.local_state,card.account
                        FROM saved_messages s LEFT JOIN media_objects o ON o.object_id=s.object_id
                        LEFT JOIN saved_contact_cards card ON card.id=s.id WHERE s.id=?
                        """
                    args=[.text(id)]
                }
                guard let result=try db.query(sql,values:args,map:{row -> MessageActionSnapshot in
                    let kind=String(cString:sqlite3_column_text(row,2))
                    let account = sqlite3_column_text(row,11).map { String(cString: $0) }
                    guard (kind == "contact") == (account != nil) else { throw self.failure("The contact card is no longer available.") }
                    let available=kind != "attachment" || (sqlite3_column_type(row,10) != SQLITE_NULL && sqlite3_column_int(row,10)==1)
                    if kind=="attachment" && (sqlite3_column_type(row,5)==SQLITE_NULL || (!allowWaiting && !available)) {
                        throw self.failure("Wait for the selected attachments to finish downloading.")
                    }
                    return MessageActionSnapshot(saved:peer==nil,peer:String(cString:sqlite3_column_text(row,0)),id:String(cString:sqlite3_column_text(row,1)),kind:kind,
                        text:String(cString:sqlite3_column_text(row,3)),caption:String(cString:sqlite3_column_text(row,4)),manifest:sqlite3_column_type(row,5)==SQLITE_NULL ? nil : String(cString:sqlite3_column_text(row,5)),
                        timestamp:sqlite3_column_int64(row,6),author:self.name(String(cString:sqlite3_column_text(row,7))),forwardName:sqlite3_column_type(row,8)==SQLITE_NULL ? nil : self.name(String(cString:sqlite3_column_text(row,8))),forwardAt:sqlite3_column_int64(row,9),available:available,contactAccount:account)
                }).first else {throw failure("A selected message is no longer available.")}
                var snapshot=result
                if snapshot.kind == "location" {
                    guard let point = try locationPoint(db, id: id, saved: peer == nil)
                    else { throw failure("The location is no longer available.") }
                    snapshot.location = point
                }
                snapshot.isSticker=try !db.query(peer==nil ? "SELECT 1 FROM saved_stickers WHERE id=?":"SELECT 1 FROM message_stickers WHERE message_id=?",values:[.text(id)],map:{_ in true}).isEmpty
                if let preview=try WebPreviewStore.body(db,id:id,saved:peer==nil) {
                    snapshot.previewJSON=String(decoding:try JSONSerialization.data(withJSONObject:preview,options:.sortedKeys),as:UTF8.self)
                    if let image=preview["image"] as? [String:Any],let objectID=image["object_id"] as? String {
                        snapshot.previewManifest=String(decoding:try JSONSerialization.data(withJSONObject:image,options:.sortedKeys),as:UTF8.self)
                        let ready=try !db.query("SELECT 1 FROM media_objects WHERE object_id=? AND local_state=1",values:[.text(objectID)],map:{_ in true}).isEmpty
                        snapshot.previewAvailable = ready
                        snapshot.available = snapshot.available && (!includePreview || ready)
                    }
                }
                guard allowWaiting || snapshot.available else {throw self.failure("Wait for the selected preview images to finish downloading.")}
                return snapshot
            }.sorted {$0.timestamp==$1.timestamp ? $0.id<$1.id : $0.timestamp<$1.timestamp}
        }
    }

    func prepare(_ source: [MessageActionSnapshot],check: () throws -> Void = {},
                 preparation: ObjectPreparation? = nil,includePreview: Bool=true,detectDamagedCache: Bool = false,
                 onObject: () throws -> Void = {}) throws -> [PreparedMessageAction] {
        var result: [PreparedMessageAction]=[]
        func copyObject(_ row: MessageActionSnapshot, _ encoded: String, previewOnly: Bool) throws -> [String:Any] {
            let manifest = try object(encoded)
            guard let id = manifest["object_id"] as? String else { throw SecurityCore.Failure.invalidResponse }
            let reference = EncryptedObjectStore.reference(id)
            try onObject()
            do {
                if row.isSticker && !previewOnly { try EncryptedObjectStore.shared.validateSticker(reference) }
                return try EncryptedObjectStore.shared.copyObject(manifest, preparation: preparation)
            } catch {
                guard detectDamagedCache, !row.saved, !(error is CancellationError), !(error is InsufficientObjectSpaceError) else { throw error }
                try check()
                try preparation?.check()
                let complete = try EncryptedObjectStore.shared.hasCompleteCiphertext(reference, check: check)
                if !complete { throw DamagedForwardCache(source: row, previewOnly: previewOnly) }
                throw error
            }
        }
        do {
            for row in source {
                try requireOpen()
                try check()
                var encoded: String?
                if let text=row.manifest {
                    let copy=try copyObject(row, text, previewOnly: false)
                    do {encoded=String(decoding:try JSONSerialization.data(withJSONObject:copy,options:.sortedKeys),as:UTF8.self)}
                    catch {try? EncryptedObjectStore.shared.discardCopy(copy);throw error}
                }
                result.append(PreparedMessageAction(source:row,objectJSON:encoded))
                if includePreview, let preview=row.previewManifest {
                    let copy=try copyObject(row, preview, previewOnly: true)
                    do {result[result.count-1].previewObjectJSON=String(decoding:try JSONSerialization.data(withJSONObject:copy,options:.sortedKeys),as:UTF8.self)}
                    catch {try? EncryptedObjectStore.shared.discardCopy(copy);throw error}
                }
            }
            return result
        } catch {discard(result);throw error}
    }
    func discard(_ prepared: [PreparedMessageAction]) {
        for row in prepared {for text in [row.objectJSON,row.previewObjectJSON].compactMap({$0}) {if let value=try? object(text) {try? EncryptedObjectStore.shared.discardCopy(value)}}}
    }
    func requireCurrent(_ db: AppDatabase.Transaction,_ source: MessageActionSnapshot) throws {
        let sql: String;let args: [DatabaseValue]
        if source.saved {
            sql="SELECT 1 FROM saved_messages s LEFT JOIN media_objects o ON o.object_id=s.object_id WHERE s.id=? AND s.kind=? AND s.body=? AND COALESCE(o.manifest_json,'')=COALESCE(?,'')"
            args=[.text(source.id),.text(source.kind == "attachment" ? "attachment" : "txt"),.text(source.text),source.manifest.map(DatabaseValue.text) ?? .null]
        } else {
            try requireCopyableSource(db, peer: source.peer, id: source.id)
            sql="""
                SELECT 1 FROM messages m LEFT JOIN message_attachments a ON a.message_id=m.messageId LEFT JOIN media_objects o ON o.object_id=a.object_id
                WHERE m.messageId=? AND m.conversationUuid=? AND m.isRevoked=0 AND m.ext=? AND m.content=?
                 AND COALESCE(a.caption,'')=? AND COALESCE(o.manifest_json,'')=COALESCE(?,'')
                """
            args=[.text(source.id),.text(source.peer),.text(source.kind),.text(source.text),.text(source.caption),source.manifest.map(DatabaseValue.text) ?? .null]
        }
        let sticker=try !db.query(source.saved ? "SELECT 1 FROM saved_stickers WHERE id=?":"SELECT 1 FROM message_stickers WHERE message_id=?",values:[.text(source.id)],map:{_ in true}).isEmpty
        guard sticker==source.isSticker else {throw failure("A selected sticker changed while preparing.")}
        let account = try db.query(source.saved ? "SELECT account FROM saved_contact_cards WHERE id=?" :
            "SELECT account FROM message_contact_cards WHERE message_id=?", values: [.text(source.id)]) {
                String(cString: sqlite3_column_text($0,0))
            }.first
        let current=try WebPreviewStore.body(db,id:source.id,saved:source.saved).map {String(decoding:try JSONSerialization.data(withJSONObject:$0,options:.sortedKeys),as:UTF8.self)}
        let point = try locationPoint(db, id: source.id, saved: source.saved)
        guard try !db.query(sql,values:args,map:{_ in true}).isEmpty,current==source.previewJSON,
              account==source.contactAccount,point==source.location else {throw failure("A selected message changed while preparing. Select it again.")}
    }

    private func requireCopyableSource(_ db: AppDatabase.Transaction, peer: String, id: String) throws {
        if try MessageExpirationStore.deadline(db, id: id) != nil {
            throw failure("Messages set to auto-delete cannot be saved or forwarded.")
        }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let retentionDue = try db.query("""
            SELECT 1 FROM messages m JOIN conversation_retention r ON r.peer=m.conversationUuid
            WHERE m.messageId=? AND m.conversationUuid=? AND (m.isMine=0 OR m.status>=2)
              AND m.timestamp<=?-r.duration_ms
            """, values: [.text(id), .text(peer), .integer(now)], map: { _ in true })
        if !retentionDue.isEmpty { throw failure("A selected message is no longer available.") }
    }

    private func locationPoint(_ db: AppDatabase.Transaction, id: String, saved: Bool) throws -> LocationPoint? {
        let sql = saved ? "SELECT latitude_e6,longitude_e6 FROM saved_locations WHERE id=?" :
            "SELECT latitude_e6,longitude_e6 FROM message_locations WHERE message_id=?"
        return try db.query(sql, values: [.text(id)]) {
            LocationPoint(latitudeE6: Int(sqlite3_column_int64($0,0)), longitudeE6: Int(sqlite3_column_int64($0,1)))
        }.compactMap { $0 }.first
    }
    func previewBody(_ row: PreparedMessageAction) throws -> [String:Any]? {
        guard let source=row.source.previewJSON else {return nil};var preview=try object(source)
        if let cover=row.previewObjectJSON {preview["image"]=try object(cover)} else {preview["image"]=NSNull()};return preview
    }
    func forwardHeader(_ source: MessageActionSnapshot)->[String:Any] { ["name":source.forwardName ?? source.author,"original_at":source.forwardAt] }
    func requireCopySpace(_ source: [MessageActionSnapshot],copies: Int,includePreview: Bool=true) throws {
        guard (1...20).contains(copies) else {throw failure("Invalid copy count.")}
        var copyBytes: Int64 = 0
        var downloadBytes: Int64 = 0
        var pendingObjects = Set<String>()
        for row in source {
            for encoded in [row.manifest, includePreview ? row.previewManifest : nil].compactMap({ $0 }) {
                let manifest = try object(encoded)
                guard let size = manifest["cipher_size"] as? Int64, size > 0,
                      copyBytes <= Int64.max - size else { throw failure("Invalid storage budget.") }
                copyBytes += size
                if !row.saved, !row.available, let id = manifest["object_id"] as? String,
                   pendingObjects.insert(id).inserted {
                    let remaining = try EncryptedObjectStore.shared.remainingDownloadBytes(manifest)
                    guard downloadBytes <= Int64.max - remaining else { throw failure("Invalid storage budget.") }
                    downloadBytes += remaining
                }
            }
        }
        guard copyBytes <= (Int64.max - downloadBytes) / Int64(copies) else { throw failure("Invalid storage budget.") }
        try EncryptedObjectStore.shared.requireFreeSpace(copyBytes * Int64(copies) + downloadBytes)
    }
    func object(_ text: String) throws -> [String:Any] {
        guard let value=try JSONSerialization.jsonObject(with:Data(text.utf8)) as? [String:Any] else {throw SecurityCore.Failure.invalidResponse};return value
    }

    func save(peer: String, ids: [String], preparation: ObjectPreparation? = nil,
              completion: @escaping @Sendable (Bool)->Void) {
        queue.async {
            var copies: [PreparedMessageAction]=[];var committed=false
            defer {
                if !committed { self.discard(copies) }
                completion(committed)
            }
            do {
                try preparation?.check()
                let source = try self.capture(peer: peer, ids: ids, includePreview: false).map { row in
                    var copy = row
                    if !row.previewAvailable { copy.previewManifest = nil }
                    return copy
                }
                try self.requireCopySpace(source, copies: 1)
                let objectCount = source.reduce(0) { count, row in
                    count + (row.manifest == nil ? 0 : 1) + (row.previewManifest == nil ? 0 : 1)
                }
                var objectIndex = 0
                copies = try self.prepare(source, preparation: preparation) {
                    objectIndex += 1
                    try preparation?.startAttachment(index: objectIndex, count: objectCount)
                }
                let newIds=copies.map {_ in UUID().uuidString.lowercased()}.sorted()
                let created=Int64(Date().timeIntervalSince1970*1000)
                try self.requireOpen()
                try AppDatabase.shared.transaction(notifyChanged:true) {db in
                    try preparation?.beginCommit()
                    for (index,row) in copies.enumerated() {
                        try self.requireCurrent(db,row.source)
                        var objectId: String?
                        if let encoded=row.objectJSON {
                            let manifest=try self.object(encoded);try EncryptedObjectStore.remember(manifest,in:db,available:true);objectId=manifest["object_id"] as? String
                        }
                        try db.execute("INSERT INTO saved_messages(id,source_peer,source_id,kind,body,object_id,saved_at,author_name,original_at) VALUES(?,?,?,?,?,?,?,?,?)",
                            values:[.text(newIds[index]),.text(row.source.peer),.text(row.source.id),.text(row.source.kind == "attachment" ? "attachment" : "txt"),.text(row.source.kind=="attachment" ? row.source.caption : row.source.text),
                                objectId.map(DatabaseValue.text) ?? .null,.integer(created),.text(row.source.forwardName ?? row.source.author),.integer(row.source.forwardAt)])
                        if row.source.isSticker {try db.execute("INSERT INTO saved_stickers(id) VALUES(?)",values:[.text(newIds[index])])}
                        if let account = row.source.contactAccount {
                            try db.execute("INSERT INTO saved_contact_cards(id,account) VALUES(?,?)", values: [.text(newIds[index]),.text(account)])
                        }
                        if let point = row.source.location {
                            try db.execute("INSERT INTO saved_locations(id,latitude_e6,longitude_e6) VALUES(?,?,?)",
                                values: [.text(newIds[index]),.integer(Int64(point.latitudeE6)),.integer(Int64(point.longitudeE6))])
                        }
                        try WebPreviewStore.record(db,id:newIds[index],preview:try self.previewBody(row),text:row.source.text,peer:"",outgoing:true,created:created,saved:true)
                    }
                }
                committed = true
            } catch {
                return
            }
        }
    }

    func sources() throws -> [SavedMessageSource] {
        try requireOpen()
        return try AppDatabase.shared.query("""
            SELECT s.source_peer, NULLIF(c.alias,''), c.avatarPath, COUNT(*)
            FROM saved_messages s LEFT JOIN conversations c ON c.uuid=s.source_peer
            GROUP BY s.source_peer,c.alias,c.avatarPath ORDER BY MAX(s.saved_at) DESC,s.source_peer
            """) { row in
            SavedMessageSource(peer: String(cString: sqlite3_column_text(row, 0)), alias: sqlite3_column_text(row, 1).map { String(cString: $0) },
                               avatar: sqlite3_column_text(row, 2).map { String(cString: $0) }, count: Int(sqlite3_column_int64(row, 3)))
        }
    }

    func list(before: MessageSearchCursor?, sourcePeer: String?, input: String, checkCancellation: () throws -> Void) throws -> [SavedMessageItem] {
        try requireOpen()
        let query = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard query.unicodeScalars.count <= 256, !query.contains("\0") else { throw failure("Search text is too long or invalid.") }
        let limit = query.isEmpty ? 51 : 64
        var matches: [SavedMessageItem] = []
        var cursor = before
        while matches.count < 51 {
            try checkCancellation()
            try requireOpen()
            let batch = try savedPage(before: cursor, peer: sourcePeer, limit: limit)
            for (item, filename) in batch {
                try checkCancellation()
                if query.isEmpty || item.author.localizedCaseInsensitiveContains(query) ||
                    filename?.localizedCaseInsensitiveContains(query) == true ||
                    SecurityCore.messageSummary(item.text, limit: 48 * 1024).localizedCaseInsensitiveContains(query) {
                    matches.append(item)
                    if matches.count == 51 { break }
                }
            }
            guard !query.isEmpty, batch.count == limit, let last = batch.last?.0 else { break }
            cursor = MessageSearchCursor(timestamp: last.savedAt, messageId: last.id)
        }
        return matches
    }

    private func savedPage(before: MessageSearchCursor?, peer: String?, limit: Int) throws -> [(SavedMessageItem, String?)] {
        var args: [DatabaseValue] = []
        var predicates: [String] = []
        if let peer { predicates.append("s.source_peer=?"); args.append(.text(peer)) }
        if let before {
            predicates.append("(s.saved_at,s.id)<(?,?)")
            args += [.integer(before.timestamp), .text(before.messageId)]
        }
        let filter = predicates.isEmpty ? "" : "WHERE " + predicates.joined(separator: " AND ")
        args.append(.integer(Int64(limit)))
        return try AppDatabase.shared.query("""
            SELECT s.id,s.source_peer,s.source_id,
                CASE WHEN EXISTS(SELECT 1 FROM saved_locations l WHERE l.id=s.id) THEN 'location'
                     WHEN card.account IS NOT NULL THEN 'contact' ELSE s.kind END,
                s.body,s.object_id,s.saved_at,s.author_name,s.original_at,card.account,
                CASE WHEN json_valid(o.manifest_json) THEN json_extract(o.manifest_json,'$.filename') END
            FROM saved_messages s LEFT JOIN saved_contact_cards card ON card.id=s.id
                LEFT JOIN media_objects o ON o.object_id=s.object_id
            \(filter) ORDER BY s.saved_at DESC,s.id DESC LIMIT ?
            """, values: args) { row in
            let item = SavedMessageItem(id: String(cString: sqlite3_column_text(row, 0)), sourcePeer: String(cString: sqlite3_column_text(row, 1)),
                sourceId: String(cString: sqlite3_column_text(row, 2)), kind: String(cString: sqlite3_column_text(row, 3)),
                text: String(cString: sqlite3_column_text(row, 4)),
                reference: sqlite3_column_text(row, 5).map { EncryptedObjectStore.referencePrefix + String(cString: $0) },
                savedAt: sqlite3_column_int64(row, 6), author: String(cString: sqlite3_column_text(row, 7)), originalAt: sqlite3_column_int64(row, 8),
                contactAccount: sqlite3_column_text(row, 9).map { String(cString: $0) })
            return (item, sqlite3_column_text(row, 10).map { String(cString: $0) })
        }
    }

    func hasOriginal(_ item: SavedMessageItem) throws -> Bool {
        try requireOpen()
        return try !AppDatabase.shared.query("""
            SELECT 1 FROM saved_messages s JOIN messages m ON m.messageId=s.source_id AND m.conversationUuid=s.source_peer
            JOIN conversations c ON c.uuid=s.source_peer
            WHERE s.id=? AND s.source_id=? AND s.source_peer=? AND m.isRevoked=0
            """, values: [.text(item.id), .text(item.sourceId), .text(item.sourcePeer)]) { _ in true }.isEmpty
    }

    func canExport(id: String, reference: String) throws -> Bool {
        try requireOpen()
        return try !AppDatabase.shared.query("""
            SELECT 1 FROM saved_messages s JOIN media_objects o ON o.object_id=s.object_id
            WHERE s.id=? AND (? || s.object_id)=? AND o.local_state=1
            """, values: [.text(id), .text(EncryptedObjectStore.referencePrefix), .text(reference)]) { _ in true }.isEmpty
    }

    func delete(ids: [String],completion: @escaping @Sendable (Bool)->Void) {
        queue.async {do {
            try self.requireOpen();guard (1...100).contains(ids.count) else {throw self.failure("Select between 1 and 100 saved messages.")}
            try AppDatabase.shared.transaction(notifyChanged:true) {db in
                for id in ids {try db.execute("DELETE FROM saved_messages WHERE id=?",values:[.text(id)])}
            }
            completion(true)
        } catch {completion(false)}}
    }

    private func name(_ value: String)->String {
        var result=String(String.UnicodeScalarView(value.unicodeScalars.filter {!CharacterSet.controlCharacters.contains($0)})).trimmingCharacters(in:.whitespacesAndNewlines)
        while result.utf8.count>256 {result.removeLast()}
        return result.isEmpty ? "User" : result
    }
}
