// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import CryptoKit
import SQLCipher

struct ForwardTaskInfo: Identifiable,Sendable {
    let id: String;let createdAt: Int64;let state: String;let messageCount: Int;let recipients: [String?];let errorCode: String?
    let pendingMediaMessages: Int
    let sourcePeer: String?
    let waitingMessageID: String?
    let options: SendOptions
}
struct ForwardQueueRequest: Codable,Sendable {
    let sourcePeer: String?;let ids: [String];let recipients: [String];let hideSender: Bool;let hideCaption: Bool;let fingerprints: [String];let version: Int;let sendAt:Int64?;let silent:Bool?;let omitPreview: Bool?
    var options:SendOptions {SendOptions(sendAt:sendAt,silent:silent==true)}
}
final class ForwardCancellation: @unchecked Sendable {
    let preparation=ObjectPreparation()
    private let lock=NSLock();private var canceled=false
    func cancel(){lock.lock();canceled=true;lock.unlock();preparation.cancel()}
    func check() throws {lock.lock();defer {lock.unlock()};if canceled {throw CancellationError()}}
}

final class ForwardTaskQueue: @unchecked Sendable {
    static let didChange=Notification.Name("org.msgat.forward-tasks-changed")
    private struct Pending: Sendable {let id: String;let payload: String;let createdAt: Int64}
    private let queue=DispatchQueue(label:"org.msgat.forward-tasks",qos:.utility)
    private let commit: @Sendable (String,ForwardQueueRequest,ForwardCancellation,@escaping @Sendable (Bool)->Void)->Void
    private let wakeDownloads: @Sendable () -> Void
    private var timer: DispatchSourceTimer?
    private var closed=false
    private var recover=true
    private var active: (id:String,cancel:ForwardCancellation)?
    private struct WaitingMedia: Equatable {
        let count: Int
        let firstMessageID: String
    }
    private var pendingMediaMessages: [String: WaitingMedia] = [:]
    private var now: Int64 {Int64(Date().timeIntervalSince1970*1000)}
    init(wakeDownloads: @escaping @Sendable () -> Void,
         commit: @escaping @Sendable (String,ForwardQueueRequest,ForwardCancellation,@escaping @Sendable (Bool)->Void)->Void) {
        self.wakeDownloads = wakeDownloads
        self.commit=commit
        let timer=DispatchSource.makeTimerSource(queue:queue);timer.schedule(deadline:.now(),repeating:30)
        timer.setEventHandler { [weak self] in self?.advance() };self.timer=timer;timer.resume()
    }
    func signal(){queue.async {if !self.closed {self.advance()}}}
    func stop(){queue.sync {self.closed=true;self.active?.cancel.cancel();self.timer?.cancel();self.timer=nil}}
    func progress(_ id: String) async -> PreparationProgress? {
        await withCheckedContinuation {continuation in
            queue.async {
                let preparation=self.active.flatMap {$0.id==id ? $0.cancel.preparation : nil}
                continuation.resume(returning:preparation?.snapshot())
            }
        }
    }
    private func changed(){NotificationCenter.default.post(name:Self.didChange,object:nil)}
    private func failure()->Error {MsgATDatabaseError(operation:"Forwarding",detail:"The forwarding request is not available.")}

    func enqueue(sourcePeer: String?,ids: [String],recipients: [String],hideSender: Bool,hideCaption: Bool,options:SendOptions=SendOptions(),omitPreview:Bool=false,completion: @escaping @Sendable (Bool)->Void) {
        queue.async {
            guard !self.closed else {completion(false);return}
            do {
                guard options.sendAt.map({$0>self.now && $0<=253402214399999}) ?? true else {throw self.failure()}
                guard (1...20).contains(recipients.count),Set(recipients).count==recipients.count else {throw self.failure()}
                let source=try MessageActionsStore.shared.capture(peer:sourcePeer,ids:ids,allowWaiting:true)
                let request=ForwardQueueRequest(sourcePeer:sourcePeer,ids:source.map(\.id),recipients:recipients,hideSender:hideSender,hideCaption:hideCaption,fingerprints:source.map(Self.fingerprint),version:omitPreview ? 3 : 2,sendAt:options.sendAt,silent:options.silent,omitPreview:omitPreview ? true : nil)
                let encoder=JSONEncoder();encoder.keyEncodingStrategy = .convertToSnakeCase
                let bytes=try encoder.encode(request);guard bytes.count<=65536 else {throw self.failure()}
                let payload=String(decoding:bytes,as:UTF8.self)
                try AppDatabase.shared.transaction(notifyChanged:true) {db in
                    let count=try db.query("SELECT COUNT(*) FROM forward_jobs",values:[],map:{sqlite3_column_int64($0,0)}).first ?? 0
                    guard count<20 else {throw self.failure()}
                    try Self.requireRecipients(db,recipients)
                    for row in source {try MessageActionsStore.shared.requireCurrent(db,row)}
                    try db.execute("INSERT INTO forward_jobs(id,request_json,created_at,state) VALUES(?,?,?,'waiting')",values:[.text(UUID().uuidString.lowercased()),.text(payload),.integer(self.now)])
                }
                self.changed();completion(true);self.advance()
            } catch {completion(false)}
        }
    }
    func list(completion: @escaping @Sendable (Result<[ForwardTaskInfo],Error>)->Void) {
        queue.async {do {
            guard !self.closed else {throw self.failure()}
            let rows=try AppDatabase.shared.query("""
                SELECT id,request_json,created_at,state,error_code,
                       (SELECT json_group_array(alias) FROM (
                            SELECT NULLIF(c.alias,'') AS alias
                            FROM json_each(CASE WHEN json_valid(job.request_json) THEN job.request_json END,'$.recipients') target
                            LEFT JOIN conversations c ON c.uuid=target.value
                            ORDER BY target.key
                       ))
                FROM forward_jobs job ORDER BY created_at,id LIMIT 20
                """,values:[]) {r in
                let request=try? Self.decode(String(cString:sqlite3_column_text(r,1)))
                let aliases=String(cString:sqlite3_column_text(r,5))
                let names=try? JSONDecoder().decode([String?].self,from:Data(aliases.utf8))
                let recipients=(request?.recipients ?? []).indices.map {index -> String? in
                    guard let names,names.indices.contains(index) else {return nil}
                    return names[index]
                }
                let id = String(cString: sqlite3_column_text(r, 0))
                let state = String(cString: sqlite3_column_text(r, 3))
                let pending = state == "waiting" ? self.pendingMediaMessages[id] : nil
                return ForwardTaskInfo(id:id,createdAt:sqlite3_column_int64(r,2),state:state,messageCount:request?.ids.count ?? 0,recipients:recipients,errorCode:sqlite3_column_type(r,4)==SQLITE_NULL ? nil : String(cString:sqlite3_column_text(r,4)),
                    pendingMediaMessages: pending?.count ?? 0, sourcePeer: request?.sourcePeer,
                    waitingMessageID: pending?.firstMessageID, options: request?.options ?? SendOptions())
            };completion(.success(rows))
        } catch {completion(.failure(error))}}
    }
    func cancel(_ id: String,completion: @escaping @Sendable (Bool)->Void) {
        queue.async {do {
            guard !self.closed else {throw self.failure()}
            let removed=try AppDatabase.shared.transaction(notifyChanged:true) {db in
                let exists=try !db.query("SELECT 1 FROM forward_jobs WHERE id=?",values:[.text(id)],map:{_ in true}).isEmpty
                try db.execute("DELETE FROM forward_jobs WHERE id=?",values:[.text(id)]);return exists
            }
            if removed,self.active?.id==id {self.active?.cancel.cancel()}
            if removed { self.pendingMediaMessages[id] = nil }
            self.changed();completion(removed);self.advance()
        } catch {completion(false)}}
    }
    func retry(_ id: String,completion: @escaping @Sendable (Bool)->Void) {
        queue.async {do {
            guard !self.closed else {throw self.failure()}
            let payload = try AppDatabase.shared.query("""
                SELECT request_json FROM forward_jobs WHERE id=? AND state='failed' AND error_code='source_download_unavailable'
                """, values: [.text(id)]) { String(cString: sqlite3_column_text($0, 0)) }.first
            let request = try payload.map(Self.decode)
            let source = try request.map { request in
                let rows = try MessageActionsStore.shared.capture(peer: request.sourcePeer, ids: request.ids,
                    allowWaiting: true, includePreview: request.omitPreview != true)
                try Self.requireSnapshot(rows, request)
                return rows
            }
            let updated=try AppDatabase.shared.transaction(notifyChanged:true) {db in
                if let payload, let request, let source {
                    let matching = try db.query("""
                        SELECT 1 FROM forward_jobs WHERE id=? AND request_json=?
                            AND state='failed' AND error_code='source_download_unavailable'
                        """, values: [.text(id), .text(payload)], map: { _ in true })
                    guard !matching.isEmpty else { return false }
                    let unavailable = try self.unavailableDownloads(db, source: source, omitPreview: request.omitPreview == true)
                    if !unavailable.isEmpty,
                       try !self.requestDownloads(db, request: request, source: source, downloads: unavailable) { return false }
                } else {
                    let matching = try db.query("""
                        SELECT 1 FROM forward_jobs WHERE id=? AND state='failed'
                            AND error_code IS NOT 'source_changed' AND error_code IS NOT 'source_download_unavailable'
                        """, values: [.text(id)], map: { _ in true })
                    guard !matching.isEmpty else { return false }
                }
                try db.execute("UPDATE forward_jobs SET state='waiting',error_code=NULL,created_at=? WHERE id=?",
                               values: [.integer(self.now), .text(id)])
                return true
            }
            if updated, request != nil { self.wakeDownloads() }
            self.changed();completion(updated);self.advance()
        } catch {completion(false)}}
    }
    func requestMissingDownloads(_ id: String, completion: @escaping @Sendable (Result<Bool, Error>) -> Void) {
        queue.async {
            do {
                guard !self.closed else { throw self.failure() }
                guard let payload = try AppDatabase.shared.query(
                    "SELECT request_json FROM forward_jobs WHERE id=? AND state='waiting'", values: [.text(id)],
                    map: { String(cString: sqlite3_column_text($0, 0)) }
                ).first else { completion(.success(false)); return }
                let request = try Self.decode(payload)
                guard let peer = request.sourcePeer else { completion(.success(false)); return }
                let source = try MessageActionsStore.shared.capture(peer: peer, ids: request.ids, allowWaiting: true, includePreview: request.omitPreview != true)
                try Self.requireSnapshot(source, request)
                let updated = try AppDatabase.shared.transaction(notifyChanged: true) { db in
                    let task = try db.query("SELECT 1 FROM forward_jobs WHERE id=? AND request_json=? AND state='waiting'",
                                            values: [.text(id), .text(payload)], map: { _ in true })
                    guard !task.isEmpty else { return false }
                    return try self.requestDownloads(db, request: request, source: source)
                }
                if updated { self.wakeDownloads() }
                completion(.success(updated))
                self.advance()
            } catch {
                completion(.failure(error))
            }
        }
    }
    private struct SourceDownload {
        let row: MessageActionSnapshot
        let manifest: String
        let previewOnly: Bool
    }

    private func missingDownloads(_ source: [MessageActionSnapshot], omitPreview: Bool) -> [SourceDownload] {
        var downloads: [SourceDownload] = []
        for row in source where !row.saved && !row.available {
            if let manifest = row.manifest { downloads.append(SourceDownload(row: row, manifest: manifest, previewOnly: false)) }
            if !omitPreview, !row.previewAvailable, let manifest = row.previewManifest {
                downloads.append(SourceDownload(row: row, manifest: manifest, previewOnly: true))
            }
        }
        return downloads
    }

    private func unavailableDownloads(_ db: AppDatabase.Transaction, source: [MessageActionSnapshot], omitPreview: Bool) throws -> [SourceDownload] {
        try missingDownloads(source, omitPreview: omitPreview).filter { download in
            let owner = download.previewOnly
                ? "SELECT 1 FROM message_previews p WHERE p.owner_kind='message' AND p.owner_id=t.event_id AND p.image_object=t.object_id"
                : "SELECT 1 FROM message_attachments a WHERE a.message_id=t.event_id AND a.object_id=t.object_id"
            guard let objectID = try MessageActionsStore.shared.object(download.manifest)["object_id"] as? String
            else { throw SecurityCore.Failure.invalidResponse }
            return try !db.query("""
                SELECT 1 FROM object_transfers t JOIN media_objects o ON o.object_id=t.object_id
                WHERE t.owner_kind='message' AND t.direction=1 AND t.download_unavailable=1 AND o.local_state=0
                  AND t.event_id=? AND t.owner_peer=? AND t.object_id=? AND EXISTS(\(owner))
                """, values: [.text(download.row.id), .text(download.row.peer), .text(objectID)], map: { _ in true }).isEmpty
        }
    }

    private func requestDownloads(_ db: AppDatabase.Transaction, request: ForwardQueueRequest, source: [MessageActionSnapshot],
                                  downloads: [SourceDownload]? = nil) throws -> Bool {
        try Self.requireRecipients(db, request.recipients)
        for row in source where !row.available { try MessageActionsStore.shared.requireCurrent(db, row) }
        var changed = false
        for download in downloads ?? missingDownloads(source, omitPreview: request.omitPreview == true) {
            if try AttachmentQueue.setDownloadRequested(db, peer: download.row.peer, id: download.row.id, requested: true,
                                                        now: now, previewOnly: download.previewOnly) { changed = true }
        }
        return changed
    }

    private func fail(_ id: String,_ code: String) throws {
        try AppDatabase.shared.transaction(notifyChanged:true) {try $0.execute("UPDATE forward_jobs SET state='failed',error_code=? WHERE id=?",values:[.text(code),.text(id)])}
        pendingMediaMessages[id] = nil
        changed()
    }
    private func advance() {
        guard !closed,active==nil else {return}
        do {
            if recover {try AppDatabase.shared.transaction {try $0.execute("UPDATE forward_jobs SET state='waiting' WHERE state='preparing'")};recover=false;changed()}
            let jobs=try AppDatabase.shared.query("SELECT id,request_json,created_at FROM forward_jobs WHERE state='waiting' ORDER BY created_at,id LIMIT 20",values:[]) {Pending(id:String(cString:sqlite3_column_text($0,0)),payload:String(cString:sqlite3_column_text($0,1)),createdAt:sqlite3_column_int64($0,2))}
            let waitingIds = Set(jobs.map(\.id))
            pendingMediaMessages = pendingMediaMessages.filter { waitingIds.contains($0.key) }
            for job in jobs {
                if now-job.createdAt>7*86400000 {try fail(job.id,"expired");continue}
                let request: ForwardQueueRequest;let source: [MessageActionSnapshot]
                do {request=try Self.decode(job.payload);source=try MessageActionsStore.shared.capture(peer:request.sourcePeer,ids:request.ids,allowWaiting:true,includePreview:request.omitPreview != true);try Self.requireSnapshot(source,request)}
                catch let error as MsgATDatabaseError where error.operation == "Message action" || error.operation == "Forwarding" {
                    try fail(job.id, "source_changed")
                    continue
                }
                catch is DecodingError {try fail(job.id,"source_changed");continue}
                do {try AppDatabase.shared.transaction {try Self.requireRecipients($0,request.recipients)}}
                catch {try fail(job.id,"recipient_unavailable");continue}
                let missing = source.filter { !$0.available }
                if !missing.isEmpty {
                    let failed = try AppDatabase.shared.transaction { db in
                        let unavailable = try self.unavailableDownloads(db, source: missing, omitPreview: request.omitPreview == true)
                        guard !unavailable.isEmpty else { return false }
                        try db.execute("UPDATE forward_jobs SET state='failed',error_code='source_download_unavailable' WHERE id=? AND state='waiting'",
                                       values: [.text(job.id)])
                        return true
                    }
                    if failed { pendingMediaMessages[job.id] = nil; changed(); continue }
                }
                do {try MessageActionsStore.shared.requireCopySpace(source,copies:request.recipients.count,includePreview:request.omitPreview != true)}
                catch is InsufficientObjectSpaceError {try fail(job.id,"insufficient_space");continue}
                let pending = missing.first.map { WaitingMedia(count: missing.count, firstMessageID: $0.id) }
                let previous = pendingMediaMessages[job.id]
                pendingMediaMessages[job.id] = pending
                if pending != previous { changed() }
                if pending != nil { continue }
                let claimed=try AppDatabase.shared.transaction(notifyChanged:true) {db in
                    let exists=try !db.query("SELECT 1 FROM forward_jobs WHERE id=? AND state='waiting'",values:[.text(job.id)],map:{_ in true}).isEmpty
                    if exists {try db.execute("UPDATE forward_jobs SET state='preparing' WHERE id=?",values:[.text(job.id)])};return exists
                }
                guard claimed else {continue}
                let cancellation=ForwardCancellation();active=(job.id,cancellation);changed()
                commit(job.id,request,cancellation) { [weak self] ok in
                    cancellation.preparation.finish()
                    guard let self else {return}
                    self.queue.async {
                        self.active=nil
                        guard !self.closed else {return}
                        do {if !ok {try self.fail(job.id,"preparation_failed")};self.changed()}
                        catch {self.recover=true}
                        self.advance()
                    }
                }
                return
            }
        } catch {recover=true} // Bounded timer retries database access; never log request payloads.
    }
    static func requireRecipients(_ db: AppDatabase.Transaction,_ recipients: [String]) throws {
        for peer in recipients {
            guard try !db.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0",values:[.text(peer)],map:{_ in true}).isEmpty else {throw MsgATDatabaseError(operation:"Forwarding",detail:"A selected recipient is unavailable.")}
        }
    }
    static func requireSnapshot(_ source: [MessageActionSnapshot],_ request: ForwardQueueRequest) throws {
        guard source.map(\.id)==request.ids,source.map(fingerprint)==request.fingerprints else {throw MsgATDatabaseError(operation:"Forwarding",detail:"A selected message changed after forwarding was queued.")}
    }
    private static func fingerprint(_ row: MessageActionSnapshot)->String {
        var hash=SHA256()
        let fields: [String?]=["msgat/forward-source/v1",row.saved ? "1" : "0",row.peer,row.id,row.kind,row.text,row.caption,row.manifest,String(row.timestamp),String(row.forwardAt)]
        for field in fields {
            if let field {
                var bytes=Data(field.utf8);let count=bytes.count
                hash.update(data:Data("\(count):".utf8));hash.update(data:bytes);bytes.resetBytes(in:0..<count)
            } else {hash.update(data:Data("-1:".utf8))}
        }
        if row.isSticker {hash.update(data:Data("1:1".utf8))}
        if let preview=row.previewJSON {var bytes=Data(preview.utf8);hash.update(data:Data("\(bytes.count):".utf8));hash.update(data:bytes);bytes.resetBytes(in:0..<bytes.count)}
        if let account = row.contactAccount { hash.update(data: Data("contact:\(account)".utf8)) }
        if let point = row.location { hash.update(data: Data("location:\(point.latitudeE6),\(point.longitudeE6)".utf8)) }
        return hash.finalize().map {String(format:"%02x",$0)}.joined()
    }
    private static func decode(_ payload: String) throws -> ForwardQueueRequest {
        let decoder=JSONDecoder();decoder.keyDecodingStrategy = .convertFromSnakeCase
        let value=try decoder.decode(ForwardQueueRequest.self,from:Data(payload.utf8))
        guard (1...3).contains(value.version),(value.omitPreview == true) == (value.version == 3),value.sendAt.map({(0...253402214399999).contains($0)}) ?? true,(1...100).contains(value.ids.count),Set(value.ids).count==value.ids.count,(1...20).contains(value.recipients.count),Set(value.recipients).count==value.recipients.count,value.fingerprints.count==value.ids.count,value.fingerprints.allSatisfy({$0.utf8.count==64}) else {throw CocoaError(.fileReadCorruptFile)}
        return value
    }
}
