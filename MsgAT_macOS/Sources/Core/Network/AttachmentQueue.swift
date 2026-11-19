// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

enum AttachmentPhase: Sendable { case waiting, uploading, downloading, complete, retry, unavailable }
struct AttachmentProgress: Sendable {
    let messageID: String
    let phase: AttachmentPhase
    let bytes: Int64
    let total: Int64
    var reference: String? = nil
}

/// One upload and one download at a time. Jobs and retry deadlines survive restart.
final class AttachmentQueue: @unchecked Sendable {
    static let progressChanged = Notification.Name("org.msgat.attachment-progress")
    static let previewProgressChanged = Notification.Name("org.msgat.preview-progress")
    private let workers = [DispatchQueue(label: "org.msgat.upload", qos: .utility), DispatchQueue(label: "org.msgat.download", qos: .utility)]
    private let lock = NSLock()
    private var scheduled = [false, false]
    private var reschedule = [false, false]
    private var closed = false
    private var progressValues: [String: AttachmentProgress] = [:]
    private let instance = UUID().uuidString
    private let transport = AttachmentTransport()
    private let reconsider: @Sendable () -> Void
    private var timer: DispatchSourceTimer?
    init(reconsider: @escaping @Sendable () -> Void) {
        self.reconsider = reconsider
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + 30, repeating: 30)
        timer.setEventHandler { [weak self] in self?.signal() }; timer.resume(); self.timer = timer
    }
    var leaseSession: String { "\(instance):\(NWClient.shared.connectionSnapshot.epoch)" }
    var minimumLease: Int64 { now + 7 * 86400000 + 3600000 }
    func progress(_ id: String) -> AttachmentProgress? { lock.lock(); defer { lock.unlock() }; return progressValues[id] }
    func signal() {
        for direction in 0...1 {
            lock.lock()
            let run = !closed && !scheduled[direction]
            if run { scheduled[direction] = true }
            else if !closed { reschedule[direction] = true }
            if run { workers[direction].async { [self] in runWorker(direction) } }
            lock.unlock()
        }
    }
    private func runWorker(_ direction: Int) {
        while true {
            do { try process(direction) } catch { /* The durable retry timer stays alive. */ }
            lock.lock()
            let again = !closed && reschedule[direction]
            reschedule[direction] = false
            if !again { scheduled[direction] = false }
            lock.unlock()
            if !again { return }
        }
    }
    func close() {
        lock.lock();closed=true;lock.unlock()
        timer?.cancel();timer=nil;transport.close()
        for worker in workers {worker.sync {}}
    }
    private enum TransferPaused:Error {case ineligible}
    private struct Job { let id: String; let objectID: String; let manifest: [String: Any]; let attempts: Int64; let preview: Bool }
    private func process(_ direction: Int) throws {
        let snapshot = NWClient.shared.connectionSnapshot
        guard let endpoint = snapshot.endpoint else { return }
        let session = "\(instance):\(snapshot.epoch)"
        let jobs = try AppDatabase.shared.query("""
            SELECT t.event_id,t.object_id,o.manifest_json,t.attempts,
                       EXISTS(SELECT 1 FROM message_previews p
                         WHERE p.owner_kind='message' AND p.owner_id=t.event_id AND p.image_object=t.object_id)
            FROM object_transfers t JOIN media_objects o ON o.object_id=t.object_id LEFT JOIN messages m ON m.messageId=t.event_id AND t.owner_kind='message'
            WHERE t.next_retry<=? AND t.direction=?
              AND \(Self.downloadEligible)
              AND \(EncryptedObjectLifecycle.automaticDownloadBudget)
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=t.event_id AND e.expires_at<=?1)
              AND ((t.owner_kind='message' AND m.isRevoked=0) OR t.owner_kind='profile')
              AND EXISTS(SELECT 1 FROM contact_states c WHERE c.uuid=t.owner_peer AND c.blocked=0 AND (c.accepted=1 OR (t.owner_kind='profile' AND (c.incoming=1 OR c.outgoing=1))))
              AND ((t.direction=0 AND (t.lease_session!=? OR t.remote_expiry<=?)
                AND (EXISTS(SELECT 1 FROM protocol_pending_events p WHERE p.event_id=t.event_id)
                  OR EXISTS(SELECT 1 FROM protocol_outbox b WHERE b.event_id=t.event_id AND b.delivered=0)))
                OR (t.direction=1 AND o.local_state=0))
            ORDER BY t.download_requested DESC,t.created_at,t.event_id,t.object_id LIMIT 8
            """, values: [.integer(now), .integer(Int64(direction)), .text(session), .integer(minimumLease)]) { row in
                guard let manifest = try JSONSerialization.jsonObject(with: Data(String(cString: sqlite3_column_text(row, 2)).utf8)) as? [String: Any] else { throw SecurityCore.Failure.invalidResponse }
                return Job(id: String(cString: sqlite3_column_text(row, 0)), objectID: String(cString: sqlite3_column_text(row, 1)), manifest: manifest, attempts: sqlite3_column_int64(row, 3), preview: sqlite3_column_int(row, 4) != 0)
            }
        for job in jobs {
            lock.lock(); let stopped = closed; lock.unlock()
            guard !stopped, NWClient.shared.connectionSnapshot.epoch == snapshot.epoch else { break }
            if direction==0,try !AppDatabase.shared.query("SELECT 1 FROM object_transfers WHERE event_id=? AND object_id=? AND direction=0 AND lease_session=? AND remote_expiry>?",values:[.text(job.id),.text(job.objectID),.text(session),.integer(minimumLease)],map:{_ in true}).isEmpty {continue}
            if direction == 1, try !AppDatabase.shared.query("SELECT 1 FROM media_objects WHERE object_id=? AND local_state=1", values: [.text(job.objectID)], map: { _ in true }).isEmpty {
                try AppDatabase.shared.execute("DELETE FROM object_transfers WHERE event_id=? AND object_id=? AND direction=1", values: [.text(job.id),.text(job.objectID)]); continue
            }
            guard let release = try EncryptedObjectStore.shared.pinTransfer(id: job.objectID, direction: direction,
                eligible: { try allowed($0, job: job, direction: direction, checkBudget: true) }) else { continue }
            defer {release()}
            guard let total = job.manifest["size"] as? Int64 else { throw SecurityCore.Failure.invalidResponse }
            let phase: AttachmentPhase = direction == 0 ? .uploading : .downloading
            var lastPercent: Int64 = -1; var lastTime = ProcessInfo.processInfo.systemUptime
            publish(.init(messageID: job.id, phase: phase, bytes: 0, total: total), job: job)
            let changed: (Int64) -> Void = { bytes in
                let percent = total == 0 ? 100 : bytes * 100 / total
                let time = ProcessInfo.processInfo.systemUptime
                if percent != lastPercent && (time - lastTime >= 0.1 || bytes == total) {
                    lastPercent = percent; lastTime = time
                    self.publish(.init(messageID: job.id, phase: phase, bytes: bytes, total: total), job: job)
                }
            }
            let checkpoint:()throws->Void = {
                self.lock.lock();let stopped=self.closed;self.lock.unlock()
                guard !stopped,NWClient.shared.connectionSnapshot.epoch==snapshot.epoch,
                      try AppDatabase.shared.transaction({try self.allowed($0,job:job,direction:direction)}) else {throw TransferPaused.ineligible}
            }
            do {
                let path = try EncryptedObjectStore.shared.path(job.objectID)
                if direction == 0 {
                    let expiry = try transport.upload(job.manifest, source: path, endpoint: endpoint, progress: changed,checkpoint:checkpoint)
                    guard expiry <= Int64.max / 1000, expiry * 1000 > minimumLease else { throw URLError(.badServerResponse) }
                    try AppDatabase.shared.transaction {db in
                        guard try self.allowed(db,job:job,direction:direction) else {throw TransferPaused.ineligible}
                        try db.execute("INSERT INTO object_leases(object_id,lease_session,remote_expiry) SELECT ?,?,? WHERE EXISTS(SELECT 1 FROM media_objects WHERE object_id=?) ON CONFLICT(object_id) DO UPDATE SET lease_session=excluded.lease_session,remote_expiry=excluded.remote_expiry",values:[.text(job.objectID),.text(session),.integer(expiry*1000),.text(job.objectID)])
                        try db.execute("UPDATE object_transfers SET lease_session=?,remote_expiry=?,attempts=0,next_retry=0 WHERE object_id=? AND direction=0",values:[.text(session),.integer(expiry*1000),.text(job.objectID)])
                    }
                } else {
                    try EncryptedObjectStore.shared.requireDownloadSpace(job.manifest)
                    try transport.download(job.manifest, target: path, endpoint: endpoint, progress: changed,checkpoint:checkpoint)
                    try AppDatabase.shared.transaction(notifyChanged: true) { transaction in
                        guard try self.allowed(transaction,job:job,direction:direction) else {throw TransferPaused.ineligible}
                        try transaction.execute("UPDATE media_objects SET local_state=1 WHERE object_id=?", values: [.text(job.objectID)])
                        try transaction.execute("DELETE FROM media_cache_evictions WHERE object_id=?", values: [.text(job.objectID)])
                        try transaction.execute("DELETE FROM object_transfers WHERE event_id=? AND object_id=? AND direction=1", values: [.text(job.id),.text(job.objectID)])
                    }
                }
                publish(.init(messageID: job.id, phase: .complete, bytes: total, total: total), job: job)
            } catch TransferPaused.ineligible {
                publish(.init(messageID:job.id,phase:.waiting,bytes:0,total:total), job: job)
            } catch AttachmentDownloadError.relayUnavailable {
                let changed = try AppDatabase.shared.transaction(notifyChanged:true) { db in
                    try db.execute("""
                        UPDATE object_transfers SET download_unavailable=1,download_requested=0
                        WHERE event_id=? AND object_id=? AND direction=1 AND download_paused=0
                        """, values: [.text(job.id),.text(job.objectID)])
                    return try !db.query("SELECT 1 WHERE changes()>0", map: { _ in true }).isEmpty
                }
                publish(.init(messageID:job.id,phase:changed ? .unavailable : .waiting,bytes:0,total:total), job: job)
            } catch {
                let delay = min(300000, 30000 * (Int64(1) << min(job.attempts, 4))) + Int64.random(in: 0..<3000)
                let retrying = try AppDatabase.shared.transaction { db in
                    try db.execute("UPDATE object_transfers SET attempts=MIN(attempts+1,16),next_retry=? WHERE event_id=? AND object_id=? AND direction=? AND download_paused=0", values: [.integer(now + delay), .text(job.id), .text(job.objectID), .integer(Int64(direction))])
                    return try !db.query("SELECT 1 WHERE changes()>0", map: { _ in true }).isEmpty
                }
                publish(.init(messageID: job.id, phase: retrying ? .retry : .waiting, bytes: 0, total: total), job: job)
            }
            reconsider()
        }
    }
    private func allowed(_ db:AppDatabase.Transaction,job:Job,direction:Int,checkBudget:Bool=false)throws->Bool {try !db.query("""
        SELECT 1 FROM object_transfers t JOIN contact_states c ON c.uuid=t.owner_peer LEFT JOIN messages m ON m.messageId=t.event_id AND t.owner_kind='message'
        JOIN media_objects o ON o.object_id=t.object_id
        WHERE t.event_id=? AND t.object_id=? AND t.direction=? AND c.blocked=0
        AND (c.accepted=1 OR (t.owner_kind='profile' AND (c.incoming=1 OR c.outgoing=1)))
        AND (t.owner_kind='profile' OR m.isRevoked=0)
        AND (t.direction=0 OR o.local_state=0)
        AND \(Self.downloadEligible)
        \(checkBudget ? "AND \(EncryptedObjectLifecycle.automaticDownloadBudget)" : "")
        AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=t.event_id AND e.expires_at<=?)
        AND (t.direction=1 OR EXISTS(SELECT 1 FROM protocol_pending_events p WHERE p.event_id=t.event_id) OR EXISTS(SELECT 1 FROM protocol_outbox b WHERE b.event_id=t.event_id AND b.delivered=0))
        """,values:[.text(job.id),.text(job.objectID),.integer(Int64(direction)),.integer(now)],map:{_ in true}).isEmpty}

    private static let downloadEligible = """
        (t.direction=0 OR (t.download_paused=0 AND t.download_unavailable=0))
        AND (t.direction=0 OR NOT EXISTS(
            SELECT 1 FROM media_cache_evictions e WHERE e.object_id=t.object_id AND e.file_pending=1))
        AND (t.direction=0 OR t.download_requested=1 OR (NOT EXISTS(
            SELECT 1 FROM media_cache_evictions e WHERE e.object_id=t.object_id) AND (
            (SELECT auto_download_attachments FROM privacy_preferences WHERE id=1)=1 OR NOT EXISTS(
            SELECT 1 FROM message_attachments a WHERE a.message_id=t.event_id AND a.object_id=t.object_id))))
        """

    static func autoDownloadEnabled(_ db: AppDatabase.Transaction) throws -> Bool {
        try !db.query("SELECT 1 FROM privacy_preferences WHERE id=1 AND auto_download_attachments=1", map: { _ in true }).isEmpty
    }

    static func setDownloadRequested(_ db: AppDatabase.Transaction, peer: String, id: String, requested: Bool, now: Int64,
                                     previewOnly: Bool = false) throws -> Bool {
        try db.execute("""
            UPDATE object_transfers SET download_requested=?,download_paused=?,
                download_unavailable=CASE WHEN ?=1 THEN 0 ELSE download_unavailable END,next_retry=0,attempts=0
            WHERE event_id=? AND owner_peer=? AND owner_kind='message' AND direction=1
              AND EXISTS(SELECT 1 FROM media_objects o WHERE o.object_id=object_transfers.object_id AND o.local_state=0)
              AND EXISTS(SELECT 1 FROM messages m
                JOIN contact_states c ON c.uuid=m.conversationUuid
                WHERE m.messageId=object_transfers.event_id AND m.conversationUuid=object_transfers.owner_peer
                  AND m.isMine=0 AND m.isRevoked=0 AND c.accepted=1 AND c.blocked=0
                  AND ((?6=0 AND EXISTS(SELECT 1 FROM message_attachments a WHERE a.message_id=m.messageId AND a.object_id=object_transfers.object_id))
                    OR (?6=1 AND EXISTS(SELECT 1 FROM message_previews p WHERE p.owner_kind='message'
                        AND p.owner_id=m.messageId AND p.image_object=object_transfers.object_id))))
              AND NOT EXISTS(SELECT 1 FROM message_expiration e WHERE e.message_id=object_transfers.event_id AND e.expires_at<=?)
            """, values: [.integer(requested ? 1 : 0), .integer(requested ? 0 : 1), .integer(requested ? 1 : 0),
                          .text(id), .text(peer), .integer(previewOnly ? 1 : 0), .integer(now)])
        return try (db.query("SELECT changes()", map: { sqlite3_column_int($0, 0) }).first ?? 0) > 0
    }

    private func publish(_ value: AttachmentProgress, job: Job) {
        if job.preview {
            var cover = value
            cover.reference = EncryptedObjectStore.reference(job.objectID)
            NotificationCenter.default.post(name: Self.previewProgressChanged, object: cover)
            return
        }
        lock.lock()
        if progressValues.count >= 256 {
            for id in progressValues.filter({ $0.value.phase == .complete || $0.value.phase == .retry || $0.value.phase == .waiting || $0.value.phase == .unavailable }).prefix(128).map(\.key) { progressValues.removeValue(forKey: id) }
        }
        progressValues[value.messageID] = value
        lock.unlock()
        NotificationCenter.default.post(name: Self.progressChanged, object: value.messageID)
    }
    static func record(_ transaction: AppDatabase.Transaction, event: String, objectID: String, outgoing: Bool,peer: String,created: Int64,ownerKind: String="message") throws {
        try transaction.execute("INSERT INTO object_transfers(event_id,object_id,direction,owner_peer,owner_kind,created_at,lease_session,remote_expiry) VALUES(?,?,?,?,?,?,COALESCE((SELECT lease_session FROM object_leases WHERE object_id=?),''),COALESCE((SELECT remote_expiry FROM object_leases WHERE object_id=?),0)) ON CONFLICT(event_id,object_id,direction) DO NOTHING",values:[.text(event),.text(objectID),.integer(outgoing ? 0 : 1),.text(peer),.text(ownerKind),.integer(created),.text(objectID),.text(objectID)])
    }
    private var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
