// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import SQLCipher

/// Real V2 messaging coordinator. All cryptographic state transitions run serially;
/// database effects and pending receipts are committed before transport ACKs.
final class SecureMessaging: @unchecked Sendable {
    static let shared = SecureMessaging()
    private let queue = DispatchQueue(label: "org.msgat.secure-messaging", qos: .userInitiated)
    private let store = SignalProtocolStore()
    private var ready = false
    private var inFlight = Set<String>()
    private var requested = [String: (id: String, sent: Date)]()
    private var unavailable = [String: Date]()
    private var timer: DispatchSourceTimer?
    private var publishedBody: String?
    private var nextMaintenance = Date.distantPast
    private init() {}

    func beginSync() { perform { [self] in
        self.ready = false
        self.inFlight.removeAll()
        self.requested.removeAll()
        let bundle = try self.store.publicBundle()
        self.publishedBody = bundle["body"] as? String
        self.nextMaintenance = Date().addingTimeInterval(3600)
        guard NWClient.shared.sendPacket(GhostPacket(type: GhostPacket.typeV2KeyPublish,
            targetMailboxId: KeychainManager.shared.myMailboxId, encryptedData: try JSONSerialization.data(withJSONObject: bundle))) else { return }
        if self.timer == nil {
            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now() + 30, repeating: 30)
            timer.setEventHandler { [weak self] in self?.retry() }
            self.timer = timer
            timer.resume()
        }
    } }

    func disconnected() { queue.async {
        self.ready = false
        self.inFlight.removeAll()
        self.requested.removeAll()
        self.timer?.cancel()
        self.timer = nil
    } }

    func handle(_ packet: GhostPacket) { perform {
        switch packet.type {
        case GhostPacket.typeV2KeyPublished:
            guard let bytes = packet.encryptedData, bytes.count < 1024,
                  let response = try JSONSerialization.jsonObject(with: bytes) as? [String: Any], response["published"] as? Bool == true else { throw self.failure("Invalid key publication acknowledgement") }
            self.ready = true
            self.pull()
            try self.flush()
        case GhostPacket.typeV2KeyResponse:
            try self.keyResponse(packet)
        case GhostPacket.typeV2Stored:
            guard let recipient = packet.targetMailboxId, let event = self.eventID(packet.nonceOrIv) else { throw self.failure("Invalid durable acknowledgement") }
            try self.store.markRelayed(eventID: event, recipient: recipient)
            try AppDatabase.shared.execute("UPDATE messages SET status=MAX(status,1) WHERE messageId=? AND conversationUuid=? AND isMine=1", values: [.text(event),.text(recipient)])
            NotificationCenter.default.post(name: MsgDao.didChangeNotification, object: nil)
            self.inFlight.remove(event)
            try self.flush()
        case GhostPacket.typeV2Delivery:
            guard let sender = packet.targetMailboxId, let event = self.eventID(packet.nonceOrIv), let bytes = packet.encryptedData else { throw self.failure("Invalid delivery envelope") }
            try self.store.receive(bytes, authenticatedSender: sender, expectedEventID: event) { incoming, transaction in
                try self.apply(incoming, transaction: transaction)
            }
            _ = NWClient.shared.sendPacket(GhostPacket(type: GhostPacket.typeV2Received, targetMailboxId: sender, nonceOrIv: self.eventBytes(event)))
            try self.flush()
        case GhostPacket.typeV2SyncEnd:
            DispatchQueue.main.async { NWClient.shared.connectionState = .synced }
            try self.flush()
        default: break
        }
    } }

    func sendText(to recipient: String, text: String, reply: ChatMessage?, completion: @escaping @Sendable (Bool) -> Void) { perform(onFailure: { completion(false) }) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.utf8.count <= 48 * 1024 else { throw self.failure("Message text is empty or too large") }
        _ = try self.store.publicBundle()
        let id = UUID().uuidString.lowercased()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var body: [String: Any] = ["text": text]
        if let reply {
            guard reply.conversationUuid == recipient else { throw self.failure("Reply belongs to another conversation") }
            body["reply"] = ["event_id": reply.messageId, "author": reply.isMine ? KeychainManager.shared.myUuid : recipient,
                             "preview": String((reply.ext == "txt" ? reply.content : "[\(reply.ext)]").prefix(512))]
        }
        try self.store.enqueue(.init(eventID: id, recipient: recipient, kind: "chat.text", body: body, createdAt: now)) { transaction in
            let accepted = try transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0", values: [.text(recipient)], map: { _ in true })
            guard !accepted.isEmpty else { throw self.failure("Accept the contact request before sending messages") }
            try self.insertText(transaction, id: id, peer: recipient, mine: true, text: text, timestamp: now, reply: body["reply"] as? [String: Any])
        }
        completion(true)
        do { try self.flush() } catch { self.report(error) }
    } }

    func requestContact(_ recipient: String) { perform {
        _ = try self.store.publicBundle()
        try self.store.enqueue(.init(eventID: UUID().uuidString.lowercased(), recipient: recipient, kind: "contact.request",
            body: ["nickname": KeychainManager.shared.myNickname ?? "Unknown"], createdAt: self.now)) { transaction in
            try transaction.execute("INSERT INTO contact_states(uuid,outgoing) VALUES(?,1) ON CONFLICT(uuid) DO UPDATE SET outgoing=1 WHERE blocked=0", values: [.text(recipient)])
            try transaction.execute("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES(?,'Pending request','','') ON CONFLICT(uuid) DO NOTHING", values: [.text(recipient)])
        }
        try self.flush()
    } }

    func acceptContact(_ recipient: String, nickname: String, avatarPath: String) { perform {
        try self.store.enqueue(.init(eventID: UUID().uuidString.lowercased(), recipient: recipient, kind: "contact.accept",
            body: ["nickname": KeychainManager.shared.myNickname ?? "Unknown"], createdAt: self.now)) { transaction in
            let pending = try transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND incoming=1 AND blocked=0", values: [.text(recipient)], map: { _ in true })
            guard !pending.isEmpty else { throw self.failure("No contact request is awaiting acceptance") }
            try transaction.execute("UPDATE contact_states SET incoming=0,accepted=1 WHERE uuid=?", values: [.text(recipient)])
            try transaction.execute("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES(?,?,?,'') ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias,avatarPath=excluded.avatarPath", values: [.text(recipient),.text(nickname),.text(avatarPath)])
            try transaction.execute("DELETE FROM friend_requests WHERE senderUuid=?", values: [.text(recipient)])
        }
        try self.flush()
        if self.ready { self.pull() }
    } }

    func markRead(_ recipient: String) { perform {
        let changed = try AppDatabase.shared.transaction { transaction in
            guard let message = try transaction.query("SELECT messageId FROM messages WHERE conversationUuid=? AND isMine=0 AND isRead=0 ORDER BY timestamp DESC,messageId DESC LIMIT 1", values: [.text(recipient)], map: { self.text($0,0) }).first else { return false }
            try transaction.execute("UPDATE messages SET isRead=1 WHERE conversationUuid=? AND isMine=0", values: [.text(recipient)])
            try self.receipt(to: recipient, message: message, state: "read", in: transaction)
            return true
        }
        if changed { NotificationCenter.default.post(name: MsgDao.didChangeNotification, object: nil) }
        try self.flush()
    } }

    func broadcastProfile() { perform {
        let peers = try AppDatabase.shared.query("SELECT uuid FROM contact_states WHERE accepted=1 AND blocked=0", map: { self.text($0,0) })
        for peer in peers {
            try self.store.enqueue(.init(eventID: UUID().uuidString.lowercased(), recipient: peer, kind: "contact.profile", body: ["nickname": KeychainManager.shared.myNickname ?? "Unknown"], createdAt: self.now))
        }
        try self.flush()
    } }

    func declineContact(_ recipient: String) { perform {
        try AppDatabase.shared.transaction(notifyChanged: true) { transaction in
            try transaction.execute("DELETE FROM friend_requests WHERE senderUuid=?", values: [.text(recipient)])
            try transaction.execute("UPDATE contact_states SET incoming=0 WHERE uuid=?", values: [.text(recipient)])
        }
    } }

    func deleteContact(_ recipient: String) { perform {
        try self.store.enqueue(.init(eventID: UUID().uuidString.lowercased(), recipient: recipient, kind: "contact.delete", body: [:], createdAt: self.now)) { transaction in
            try transaction.execute("UPDATE contact_states SET accepted=0,outgoing=0,incoming=0 WHERE uuid=?", values: [.text(recipient)])
            try transaction.execute("DELETE FROM conversations WHERE uuid=?", values: [.text(recipient)])
            try transaction.execute("DELETE FROM messages WHERE conversationUuid=?", values: [.text(recipient)])
            try transaction.execute("DELETE FROM friend_requests WHERE senderUuid=?", values: [.text(recipient)])
            try transaction.execute("DELETE FROM protocol_pending_events WHERE recipient=? AND kind LIKE 'chat.%'", values: [.text(recipient)])
            try transaction.execute("DELETE FROM protocol_outbox WHERE recipient=? AND delivered=0", values: [.text(recipient)])
        }
        try self.flush()
    } }

    private func apply(_ event: SignalProtocolStore.Incoming, transaction: AppDatabase.Transaction) throws {
        switch event.kind {
        case "contact.request":
            let nickname = try name(event.body)
            if try isBlocked(event.sender, transaction) { return }
            let friend = try transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1", values: [.text(event.sender)], map: { _ in true })
            if !friend.isEmpty { return }
            try transaction.execute("INSERT INTO contact_states(uuid,incoming) VALUES(?,1) ON CONFLICT(uuid) DO UPDATE SET incoming=1", values: [.text(event.sender)])
            try transaction.execute("INSERT INTO friend_requests(senderUuid,nickname,avatarPath,peerPublicKey,timestamp) VALUES(?,?,'','',?) ON CONFLICT(senderUuid) DO UPDATE SET nickname=excluded.nickname,timestamp=excluded.timestamp", values: [.text(event.sender),.text(nickname),.integer(event.createdAt)])
        case "contact.accept":
            let nickname = try name(event.body)
            let expected = try transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND blocked=0 AND (outgoing=1 OR accepted=1)", values: [.text(event.sender)], map: { _ in true })
            guard !expected.isEmpty else { return }
            try transaction.execute("UPDATE contact_states SET outgoing=0,accepted=1 WHERE uuid=?", values: [.text(event.sender)])
            try transaction.execute("INSERT INTO conversations(uuid,alias,avatarPath,peerPublicKey) VALUES(?,?,'','') ON CONFLICT(uuid) DO UPDATE SET alias=excluded.alias", values: [.text(event.sender),.text(nickname)])
            try transaction.execute("DELETE FROM friend_requests WHERE senderUuid=?", values: [.text(event.sender)])
        case "contact.profile":
            let nickname = try name(event.body)
            try transaction.execute("UPDATE conversations SET alias=? WHERE uuid=? AND EXISTS (SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1 AND blocked=0)", values: [.text(nickname),.text(event.sender),.text(event.sender)])
        case "contact.delete":
            try transaction.execute("UPDATE contact_states SET accepted=0,outgoing=0,incoming=0 WHERE uuid=?", values: [.text(event.sender)])
            try transaction.execute("DELETE FROM friend_requests WHERE senderUuid=?", values: [.text(event.sender)])
        case "chat.text":
            guard let value = event.body["text"] as? String, !value.isEmpty, value.utf8.count <= 48 * 1024 else { throw failure("Invalid text event") }
            if try isBlocked(event.sender, transaction) { return }
            let known = try transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND accepted=1", values: [.text(event.sender)], map: { _ in true })
            guard !known.isEmpty else { return }
            let reply = event.body["reply"] as? [String: Any]
            if let reply {
                guard let id = reply["event_id"] as? String, UUID(uuidString: id) != nil,
                      let author = reply["author"] as? String, [event.sender, KeychainManager.shared.myUuid].contains(author),
                      let preview = reply["preview"] as? String, preview.utf8.count <= 4096 else { throw failure("Invalid reply metadata") }
            }
            try insertText(transaction, id: event.eventID, peer: event.sender, mine: false, text: value, timestamp: event.createdAt, reply: reply)
            try receipt(to: event.sender, message: event.eventID, state: "delivered", in: transaction)
        case "chat.receipt":
            guard let id = event.body["message_id"] as? String, let state = event.body["state"] as? String,
                  state == "delivered" || state == "read" else { throw failure("Invalid receipt event") }
            if state == "delivered" {
                try transaction.execute("UPDATE messages SET status=MAX(status,2) WHERE messageId=? AND conversationUuid=? AND isMine=1", values: [.text(id),.text(event.sender)])
            } else if let timestamp = try transaction.query("SELECT timestamp FROM messages WHERE messageId=? AND conversationUuid=? AND isMine=1", values: [.text(id),.text(event.sender)], map: { sqlite3_column_int64($0,0) }).first {
                try transaction.execute("UPDATE messages SET status=MAX(status,3) WHERE conversationUuid=? AND isMine=1 AND timestamp<=?", values: [.text(event.sender),.integer(timestamp)])
            }
        default: throw failure("Unsupported encrypted event")
        }
    }

    private func insertText(_ transaction: AppDatabase.Transaction, id: String, peer: String, mine: Bool, text: String, timestamp: Int64, reply: [String: Any]?) throws {
        try transaction.execute("INSERT INTO messages(messageId,conversationUuid,isMine,content,ext,timestamp,isRead,replyToId,replyToSender,replyToContent,status,isRevoked,isEdited) VALUES(?,?,?,?,'txt',?,?,?,?,?,0,0,0)", values: [.text(id),.text(peer),.integer(mine ? 1 : 0),.text(text),.integer(timestamp),.integer(mine ? 1 : 0),optional(reply?["event_id"]),optional(reply?["author"]),optional(reply?["preview"])])
    }

    private func isBlocked(_ uuid: String, _ transaction: AppDatabase.Transaction) throws -> Bool {
        try !transaction.query("SELECT 1 FROM contact_states WHERE uuid=? AND blocked=1", values: [.text(uuid)], map: { _ in true }).isEmpty
    }

    private func receipt(to recipient: String, message: String, state: String, in transaction: AppDatabase.Transaction) throws {
        try store.enqueue(.init(eventID: UUID().uuidString.lowercased(), recipient: recipient, kind: "chat.receipt", body: ["message_id": message, "state": state], createdAt: now), in: transaction)
    }

    private func flush() throws {
        guard ready else { return }
        for event in try store.pendingEvents() {
            if try store.hasSession(with: event.recipient) {
                _ = try store.prepare(eventID: event.eventID, recipient: event.recipient, kind: event.kind, body: event.body, createdAt: event.createdAt)
            } else if requested[event.recipient] == nil, (unavailable[event.recipient] ?? .distantPast) < Date() {
                let id = try store.keyRequestID(for: event.recipient)
                let bytes = try JSONSerialization.data(withJSONObject: ["request_id": id, "uuid": event.recipient])
                if NWClient.shared.sendPacket(GhostPacket(type: GhostPacket.typeV2KeyClaim, targetMailboxId: KeychainManager.shared.myMailboxId, encryptedData: bytes)) {
                    requested[event.recipient] = (id,Date())
                }
            }
        }
        for event in try store.pendingOutgoing(limit: 8) where !inFlight.contains(event.eventID) {
            guard NWClient.shared.sendPacket(GhostPacket(type: GhostPacket.typeV2Submit, targetMailboxId: event.recipient, nonceOrIv: eventBytes(event.eventID), encryptedData: event.wire)) else { return }
            inFlight.insert(event.eventID)
        }
    }

    private func keyResponse(_ packet: GhostPacket) throws {
        guard let bytes = packet.encryptedData, bytes.count <= 192 * 1024,
              let response = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              let recipient = response["uuid"] as? String, let id = response["request_id"] as? String,
              requested[recipient]?.id == id else { throw failure("Unexpected key directory response") }
        requested.removeValue(forKey: recipient)
        if response["found"] as? Bool != true {
            unavailable[recipient] = Date().addingTimeInterval(300)
            try store.finishKeyRequest(recipient: recipient, id: id)
            throw failure("The recipient has no currently available key bundle. Your message remains encrypted in the local vault.")
        }
        guard let bundle = response["bundle"] as? [String: Any] else { throw failure("Missing public key bundle") }
        _ = try SecurityCore.call(["operation": "verify_bundle", "bundle": bundle, "expected_uuid": recipient])
        for event in try store.pendingEvents(limit: 256) where event.recipient == recipient {
            _ = try store.prepare(eventID: event.eventID, recipient: recipient, kind: event.kind, body: event.body,
                createdAt: event.createdAt, bundle: bundle, selectedPrekey: response["selected_prekey"] as? Int64)
        }
        try store.finishKeyRequest(recipient: recipient, id: id)
        try flush()
    }

    private func retry() {
        guard ready else { return }
        inFlight.removeAll()
        requested = requested.filter { Date().timeIntervalSince($0.value.sent) < 30 }
        do {
            if Date() >= nextMaintenance {
                let bundle = try store.publicBundle()
                nextMaintenance = Date().addingTimeInterval(3600)
                if publishedBody != bundle["body"] as? String {
                    let bytes = try JSONSerialization.data(withJSONObject: bundle)
                    if NWClient.shared.sendPacket(GhostPacket(type: GhostPacket.typeV2KeyPublish, targetMailboxId: KeychainManager.shared.myMailboxId, encryptedData: bytes)) {
                        publishedBody = bundle["body"] as? String
                    }
                }
            }
            try flush(); pull()
        } catch { report(error) }
    }
    private func pull() { _ = NWClient.shared.sendPacket(GhostPacket(type: GhostPacket.typeV2Sync, targetMailboxId: KeychainManager.shared.myUuid)) }
    private var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    private func name(_ body: [String: Any]) throws -> String {
        guard let name = body["nickname"] as? String, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, name.utf8.count <= 256 else { throw failure("Invalid contact profile") }
        return name
    }
    private func eventID(_ data: Data?) -> String? {
        guard let data, data.count == 16, data[6] >> 4 == 4, data[8] >> 6 == 2 else { return nil }
        return data.withUnsafeBytes { bytes in
            let uuid = bytes.loadUnaligned(as: uuid_t.self)
            return UUID(uuid: uuid).uuidString.lowercased()
        }
    }
    private func eventBytes(_ id: String) -> Data? { guard var uuid = UUID(uuidString: id)?.uuid else { return nil }; return withUnsafeBytes(of: &uuid) { Data($0) } }
    private func optional(_ value: Any?) -> DatabaseValue { (value as? String).map(DatabaseValue.text) ?? .null }
    private func text(_ statement: OpaquePointer, _ column: Int32) -> String { sqlite3_column_text(statement,column).map { String(cString:$0) } ?? "" }
    private func failure(_ detail: String) -> MsgATDatabaseError { MsgATDatabaseError(operation: "Secure messaging", detail: detail) }
    private func perform(onFailure: (@Sendable () -> Void)? = nil, _ work: @escaping @Sendable () throws -> Void) {
        queue.async { do { try work() } catch { onFailure?(); self.report(error) } }
    }
    private func report(_ error: Error) { Task { @MainActor in NoticeCenter.shared.show(error.localizedDescription) } }
}
