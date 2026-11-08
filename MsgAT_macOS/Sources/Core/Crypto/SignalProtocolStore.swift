// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import CryptoKit
import SQLCipher

/// The encrypted SQLCipher vault owns both libsignal state and its durable mailboxes.
/// Transport sends only committed outbox entries; transport ACKs only follow receive().
final class SignalProtocolStore {
    struct Outgoing {
        let eventID: String
        let recipient: String
        let wire: Data
    }

    struct Incoming {
        let sender: String
        let eventID: String
        let kind: String
        let body: [String: Any]
        let createdAt: Int64
    }

    struct Pending {
        let eventID: String
        let recipient: String
        let kind: String
        let body: [String: Any]
        let createdAt: Int64
    }

    static let schema = [
        "CREATE TABLE IF NOT EXISTS protocol_local (id INTEGER PRIMARY KEY CHECK(id=1), identity_json TEXT NOT NULL, bundle_json TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS protocol_keys (kind TEXT NOT NULL, id INTEGER NOT NULL, record TEXT NOT NULL, last_resort INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(kind,id))",
        "CREATE TABLE IF NOT EXISTS protocol_key_counters (kind TEXT PRIMARY KEY NOT NULL, next_id INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS protocol_key_ages (kind TEXT NOT NULL, id INTEGER NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(kind,id))",
        "CREATE TABLE IF NOT EXISTS protocol_peers (uuid TEXT PRIMARY KEY NOT NULL, state_json TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS protocol_pq_uses (fingerprint TEXT PRIMARY KEY NOT NULL)",
        "CREATE TABLE IF NOT EXISTS protocol_outbox (event_id TEXT PRIMARY KEY NOT NULL, recipient TEXT NOT NULL, payload_hash TEXT NOT NULL, wire TEXT NOT NULL, delivered INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS protocol_pending_events (event_id TEXT PRIMARY KEY NOT NULL, recipient TEXT NOT NULL, kind TEXT NOT NULL, body_json TEXT NOT NULL, created_at INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS protocol_key_requests (recipient TEXT PRIMARY KEY NOT NULL, request_id TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS protocol_inbox (sender TEXT NOT NULL, event_id TEXT NOT NULL, payload_hash TEXT NOT NULL, PRIMARY KEY(sender,event_id))",
        "CREATE TABLE IF NOT EXISTS protocol_received_wires (wire_hash TEXT PRIMARY KEY NOT NULL, sender TEXT NOT NULL, event_id TEXT NOT NULL)"
    ]

    private let database: AppDatabase
    init(database: AppDatabase = .shared) { self.database = database }

    func hasSession(with uuid: String) throws -> Bool {
        try database.transaction { transaction in
            guard let state = try peerState(transaction, uuid: uuid) else { return false }
            return state["session_record"] as? String != nil
        }
    }

    func enqueue(_ pending: Pending, apply: (AppDatabase.Transaction) throws -> Void = { _ in }) throws {
        try database.transaction(notifyChanged: true) { transaction in
            if try enqueue(pending, in: transaction) { try apply(transaction) }
        }
    }

    @discardableResult
    func enqueue(_ pending: Pending, in transaction: AppDatabase.Transaction) throws -> Bool {
        try validateEvent(id: pending.eventID, kind: pending.kind, createdAt: pending.createdAt)
        guard pending.recipient.count == 64, pending.recipient.allSatisfy({ $0.isHexDigit && !$0.isUppercase }) else { throw invalid("Invalid recipient identity") }
        let encoded = try json(pending.body)
        guard encoded.utf8.count <= 60 * 1024 else { throw invalid("Message body exceeds its size limit") }
        if let existing = try transaction.query("SELECT recipient,kind,body_json,created_at FROM protocol_pending_events WHERE event_id=?", values: [.text(pending.eventID)], map: { (text($0,0),text($0,1),text($0,2),sqlite3_column_int64($0,3)) }).first {
            guard existing.0 == pending.recipient, existing.1 == pending.kind, existing.2 == encoded, existing.3 == pending.createdAt else { throw invalid("Conflicting pending event identifier") }
            return false
        }
        let payload: [String: Any] = ["version": 2, "event_id": pending.eventID, "kind": pending.kind, "body": pending.body, "created_at": pending.createdAt]
        let hash = digest(try JSONSerialization.data(withJSONObject: payload, options: .sortedKeys))
        if let existing = try transaction.query("SELECT recipient,payload_hash FROM protocol_outbox WHERE event_id=?", values: [.text(pending.eventID)], map: { (text($0,0),text($0,1)) }).first {
            guard existing.0 == pending.recipient, existing.1 == hash else { throw invalid("Conflicting committed event identifier") }
            return false
        }
        try transaction.execute("INSERT INTO protocol_pending_events(event_id,recipient,kind,body_json,created_at) VALUES(?,?,?,?,?)", values: [.text(pending.eventID),.text(pending.recipient),.text(pending.kind),.text(encoded),.integer(pending.createdAt)])
        return true
    }

    func pendingEvents(limit: Int = 64) throws -> [Pending] {
        try database.query("SELECT event_id,recipient,kind,body_json,created_at FROM protocol_pending_events ORDER BY rowid LIMIT ?", values: [.integer(Int64(max(1,min(limit,256))))]) {
            Pending(eventID: text($0,0),recipient: text($0,1),kind: text($0,2),body: try object(text($0,3)),createdAt: sqlite3_column_int64($0,4))
        }
    }

    func keyRequestID(for recipient: String) throws -> String {
        try database.transaction { transaction in
            if let id = try transaction.query("SELECT request_id FROM protocol_key_requests WHERE recipient=?", values: [.text(recipient)], map: { text($0,0) }).first { return id }
            let id = UUID().uuidString.lowercased()
            try transaction.execute("INSERT INTO protocol_key_requests(recipient,request_id) VALUES(?,?)", values: [.text(recipient),.text(id)])
            return id
        }
    }

    func finishKeyRequest(recipient: String, id: String) throws {
        try database.execute("DELETE FROM protocol_key_requests WHERE recipient=? AND request_id=?", values: [.text(recipient),.text(id)])
    }

    /// Provision only once. Reopening the app never regenerates registration/prekeys.
    func publicBundle(forceRefresh: Bool = false) throws -> [String: Any] {
        let uuid = KeychainManager.shared.myUuid
        var seed = KeychainManager.shared.getOrCreateSeed()
        defer { seed.resetBytes(in: seed.startIndex..<seed.endIndex) }
        return try database.transaction { transaction in
            var request: [String: Any] = ["operation": "provision_identity", "seed": seed.base64EncodedString(), "prekey_count": 100]
            var previous: [String: Any]?
            if let stored = try local(transaction) {
                guard stored.identity["uuid"] as? String == uuid else { throw invalid("Vault identity mismatch") }
                guard let encoded = stored.bundle["body"] as? String, let bodyBytes = Data(base64Encoded: encoded),
                      let body = try JSONSerialization.jsonObject(with: bodyBytes) as? [String: Any],
                      let expires = body["expires_at"] as? Int64 else { throw invalid("Invalid stored public bundle") }
                guard let publicKeys = body["prekeys"] as? [[String: Any]],
                      let first = publicKeys.compactMap({ $0["id"] as? Int64 }).min(),
                      let last = publicKeys.compactMap({ $0["id"] as? Int64 }).max() else { throw invalid("Invalid stored prekey range") }
                let available = try transaction.query("SELECT COUNT(*) FROM protocol_keys WHERE kind='ec' AND id>=? AND id<=?", values: [.integer(first), .integer(last)], map: { sqlite3_column_int64($0, 0) }).first ?? 0
                if !forceRefresh, expires > Int64(Date().timeIntervalSince1970) + 2 * 86400, available >= 20 { return stored.bundle }
                request["operation"] = "refresh_identity"
                request["local"] = stored.identity
                for (kind, field) in [("ec", "first_prekey_id"), ("signed", "signed_prekey_id"), ("pq", "pq_prekey_id")] {
                    guard let next = try transaction.query("SELECT next_id FROM protocol_key_counters WHERE kind=?", values: [.text(kind)], map: { sqlite3_column_int64($0, 0) }).first,
                          next > 0, next <= Int64(UInt32.max) else { throw invalid("Missing or exhausted prekey counter") }
                    request[field] = next
                }
                previous = stored.identity
            } else {
                let records = try transaction.query("SELECT 1 FROM protocol_peers UNION ALL SELECT 1 FROM protocol_keys LIMIT 1") { _ in true }
                guard records.isEmpty else { throw invalid("Protocol identity is missing from an existing vault") }
            }
            let provision = try SecurityCore.call(request)
            guard provision["uuid"] as? String == uuid,
                  let bundle = provision["public_bundle"] as? [String: Any],
                  let state = provision["private_state"] as? [String: Any],
                  let identity = state["local_identity"] as? [String: Any] else { throw invalid("Invalid identity provisioning result") }
            if let previous, try json(previous) != json(identity) { throw invalid("Prekey refresh attempted to replace the local identity") }
            try transaction.execute("INSERT INTO protocol_local(id,identity_json,bundle_json) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET identity_json=excluded.identity_json,bundle_json=excluded.bundle_json", values: [.text(try json(identity)), .text(try json(bundle))])
            let now = Int64(Date().timeIntervalSince1970)
            for (field, kind) in [("prekeys", "ec"), ("signed_prekeys", "signed"), ("pq_prekeys", "pq")] {
                guard let keys = state[field] as? [[String: Any]], !keys.isEmpty else { throw invalid("Missing provisioned prekeys") }
                var nextID: Int64 = 1
                for key in keys {
                    guard let id = key["id"] as? Int64, id > 0, let record = key["record"] as? String else { throw invalid("Invalid provisioned key") }
                    try transaction.execute("INSERT INTO protocol_keys(kind,id,record,last_resort) VALUES(?,?,?,?)", values: [.text(kind), .integer(id), .text(record), .integer(key["last_resort"] as? Bool == true ? 1 : 0)])
                    try transaction.execute("INSERT INTO protocol_key_ages(kind,id,created_at) VALUES(?,?,?)", values: [.text(kind), .integer(id), .integer(now)])
                    nextID = max(nextID, id + 1)
                }
                try transaction.execute("INSERT INTO protocol_key_counters(kind,next_id) VALUES(?,?) ON CONFLICT(kind) DO UPDATE SET next_id=MAX(next_id,excluded.next_id)", values: [.text(kind), .integer(nextID)])
            }
            // A bundle can be used for seven days, then its ciphertext can remain
            // queued for seven more. Keep private receive keys for fifteen days.
            try transaction.execute("DELETE FROM protocol_keys WHERE (kind,id) IN (SELECT kind,id FROM protocol_key_ages WHERE created_at<?)", values: [.integer(now - 15 * 86400)])
            try transaction.execute("DELETE FROM protocol_key_ages WHERE NOT EXISTS (SELECT 1 FROM protocol_keys k WHERE k.kind=protocol_key_ages.kind AND k.id=protocol_key_ages.id)")
            return bundle
        }
    }

    /// Retrying the same event returns its original ciphertext without advancing the ratchet.
    func prepare(eventID: String, recipient: String, kind: String, body: [String: Any], createdAt: Int64,
                 bundle: [String: Any]? = nil, selectedPrekey: Int64? = nil,
                 apply: (AppDatabase.Transaction) throws -> Void = { _ in }) throws -> Outgoing {
        try validateEvent(id: eventID, kind: kind, createdAt: createdAt)
        let payload: [String: Any] = ["version": 2, "event_id": eventID, "kind": kind, "body": body, "created_at": createdAt]
        let bytes = try JSONSerialization.data(withJSONObject: payload, options: .sortedKeys)
        guard bytes.count <= 64 * 1024 else { throw invalid("Event exceeds the encrypted message limit") }
        let payloadHash = digest(bytes)
        let uuid = KeychainManager.shared.myUuid
        return try database.transaction { transaction in
            if let previous = try transaction.query("SELECT recipient,payload_hash,wire FROM protocol_outbox WHERE event_id=?", values: [.text(eventID)], map: { (text($0, 0), text($0, 1), text($0, 2)) }).first {
                guard previous.0 == recipient, previous.1 == payloadHash, let wire = Data(base64Encoded: previous.2) else { throw invalid("Event identifier has already been used") }
                return Outgoing(eventID: eventID, recipient: recipient, wire: wire)
            }
            let identity = try requiredLocal(transaction, uuid: uuid)
            var peer = try peerState(transaction, uuid: recipient)
            if peer == nil {
                guard let bundle else { throw invalid("A contact key bundle is required") }
                let verified = try SecurityCore.call(["operation": "verify_bundle", "bundle": bundle, "expected_uuid": recipient])
                guard let publicState = verified["bundle"] as? [String: Any], let certificate = publicState["certificate"] as? [String: Any] else { throw invalid("Invalid contact bundle") }
                peer = ["uuid": recipient, "certificate": certificate, "session_record": NSNull(), "trusted_identity_key": NSNull()]
            }
            var request: [String: Any] = ["operation": "encrypt", "local": identity, "peer": peer!, "plaintext": bytes.base64EncodedString()]
            if let bundle { request["bundle"] = bundle }
            if let selectedPrekey { request["selected_prekey"] = selectedPrekey }
            let result = try SecurityCore.call(request)
            guard let encoded = result["wire_bytes"] as? String, let wire = Data(base64Encoded: encoded),
                  let state = result["peer_state"] as? [String: Any] else { throw invalid("Invalid encrypted message result") }
            try savePeer(transaction, state: state, expected: recipient)
            try apply(transaction)
            try transaction.execute("INSERT INTO protocol_outbox(event_id,recipient,payload_hash,wire) VALUES(?,?,?,?)", values: [.text(eventID), .text(recipient), .text(payloadHash), .text(encoded)])
            try transaction.execute("DELETE FROM protocol_pending_events WHERE event_id=?", values: [.text(eventID)])
            return Outgoing(eventID: eventID, recipient: recipient, wire: wire)
        }
    }

    /// apply must use this same transaction. Any rejected event rolls back the ratchet,
    /// key consumption and business writes together. Duplicate envelopes are ACKable.
    @discardableResult
    func receive(_ wire: Data, authenticatedSender: String, expectedEventID: String,
                 apply: (Incoming, AppDatabase.Transaction) throws -> Void) throws -> Bool {
        guard wire.count <= 262000 else { throw invalid("Message exceeds the transport limit") }
        let uuid = KeychainManager.shared.myUuid
        let wireHash = digest(wire)
        return try database.transaction(notifyChanged: true) { transaction in
            if let received = try transaction.query("SELECT sender,event_id FROM protocol_received_wires WHERE wire_hash=?", values: [.text(wireHash)], map: { (text($0, 0), text($0, 1)) }).first {
                guard received.0 == authenticatedSender, received.1 == expectedEventID else { throw invalid("Relay sender/event mismatch") }
                return false
            }
            let decoded = try SecurityCore.call(["operation": "decode_wire", "bytes": wire.base64EncodedString(), "expected_recipient": uuid])
            guard let sender = decoded["sender_uuid"] as? String, sender == authenticatedSender,
                  let message = decoded["wire_message"] as? [String: Any], let certificate = message["sender_certificate"] as? [String: Any],
                  let description = decoded["prekeys"] as? [String: Any] else { throw invalid("Relay sender mismatch") }
            let peer = try peerState(transaction, uuid: sender) ?? ["uuid": sender, "certificate": certificate, "session_record": NSNull(), "trusted_identity_key": NSNull()]
            var keys: [String: Any] = [:]
            for (field, kind, output) in [("prekey_id", "ec", "prekey"), ("signed_prekey_id", "signed", "signed_prekey"), ("pq_prekey_id", "pq", "pq_prekey")] {
                if let id = description[field] as? Int64,
                   let key = try transaction.query("SELECT record,last_resort FROM protocol_keys WHERE kind=? AND id=?", values: [.text(kind), .integer(id)], map: { (text($0, 0), sqlite3_column_int64($0, 1) != 0) }).first {
                    keys[output] = key.0
                    if kind == "pq" { keys["pq_last_resort"] = key.1 }
                }
            }
            if let use = description["pq_key_use"] as? [String: Any], let fingerprint = use["fingerprint"] as? String {
                keys["pq_already_used"] = try !transaction.query("SELECT 1 FROM protocol_pq_uses WHERE fingerprint=?", values: [.text(fingerprint)], map: { _ in true }).isEmpty
            }
            let result = try SecurityCore.call(["operation": "decrypt", "local": requiredLocal(transaction, uuid: uuid), "peer": peer, "wire": message, "keys": keys])
            guard let encoded = result["plaintext"] as? String, var plaintext = Data(base64Encoded: encoded), plaintext.count <= 64 * 1024 else { throw invalid("Invalid decrypted event") }
            defer { plaintext.resetBytes(in: plaintext.startIndex..<plaintext.endIndex) }
            guard let payload = try JSONSerialization.jsonObject(with: plaintext) as? [String: Any], payload["version"] as? Int == 2,
                  let id = payload["event_id"] as? String, let kind = payload["kind"] as? String,
                  let body = payload["body"] as? [String: Any], let createdAt = payload["created_at"] as? Int64,
                  let state = result["peer_state"] as? [String: Any] else { throw invalid("Invalid encrypted event structure") }
            try validateEvent(id: id, kind: kind, createdAt: createdAt)
            guard id == expectedEventID else { throw invalid("Relay event identifier mismatch") }
            let payloadHash = digest(plaintext)
            let existing = try transaction.query("SELECT payload_hash FROM protocol_inbox WHERE sender=? AND event_id=?", values: [.text(sender), .text(id)], map: { text($0, 0) }).first
            if let existing, existing != payloadHash { throw invalid("Conflicting event identifier") }
            if existing == nil {
                try apply(Incoming(sender: sender, eventID: id, kind: kind, body: body, createdAt: createdAt), transaction)
                try transaction.execute("INSERT INTO protocol_inbox(sender,event_id,payload_hash) VALUES(?,?,?)", values: [.text(sender), .text(id), .text(payloadHash)])
            }
            try savePeer(transaction, state: state, expected: sender)
            if let id = result["consumed_prekey_id"] as? Int64 {
                try transaction.execute("DELETE FROM protocol_keys WHERE kind='ec' AND id=?", values: [.integer(id)])
            }
            if result["consumed_pq_prekey"] as? Bool == true, let id = description["pq_prekey_id"] as? Int64 {
                try transaction.execute("DELETE FROM protocol_keys WHERE kind='pq' AND id=?", values: [.integer(id)])
            }
            if let use = result["pq_key_use"] as? [String: Any], let fingerprint = use["fingerprint"] as? String {
                try transaction.execute("INSERT INTO protocol_pq_uses(fingerprint) VALUES(?)", values: [.text(fingerprint)])
            }
            try transaction.execute("INSERT INTO protocol_received_wires(wire_hash,sender,event_id) VALUES(?,?,?)", values: [.text(wireHash), .text(sender), .text(id)])
            return existing == nil
        }
    }

    func pendingOutgoing(limit: Int = 64) throws -> [Outgoing] {
        try database.query("SELECT event_id,recipient,wire FROM protocol_outbox WHERE delivered=0 ORDER BY rowid LIMIT ?", values: [.integer(Int64(max(1, min(limit, 256))))]) {
            guard let wire = Data(base64Encoded: text($0, 2)) else { throw invalid("Invalid stored outbox message") }
            return Outgoing(eventID: text($0, 0), recipient: text($0, 1), wire: wire)
        }
    }

    /// Server durable-receipt only; recipient delivery/read receipts remain E2EE events.
    func markRelayed(eventID: String, recipient: String) throws {
        try database.execute("UPDATE protocol_outbox SET delivered=1 WHERE event_id=? AND recipient=?", values: [.text(eventID), .text(recipient)])
    }

    private func local(_ transaction: AppDatabase.Transaction) throws -> (identity: [String: Any], bundle: [String: Any])? {
        try transaction.query("SELECT identity_json,bundle_json FROM protocol_local WHERE id=1") { (try object(text($0, 0)), try object(text($0, 1))) }.first
    }

    private func requiredLocal(_ transaction: AppDatabase.Transaction, uuid: String) throws -> [String: Any] {
        guard let identity = try local(transaction)?.identity, identity["uuid"] as? String == uuid else { throw invalid("Protocol identity is not initialized") }
        return identity
    }

    private func peerState(_ transaction: AppDatabase.Transaction, uuid: String) throws -> [String: Any]? {
        try transaction.query("SELECT state_json FROM protocol_peers WHERE uuid=?", values: [.text(uuid)]) { try object(text($0, 0)) }.first
    }

    private func savePeer(_ transaction: AppDatabase.Transaction, state: [String: Any], expected: String) throws {
        guard state["uuid"] as? String == expected else { throw invalid("Unexpected peer state") }
        try transaction.execute("INSERT INTO protocol_peers(uuid,state_json) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET state_json=excluded.state_json", values: [.text(expected), .text(try json(state))])
    }

    private func validateEvent(id: String, kind: String, createdAt: Int64) throws {
        guard let uuid = UUID(uuidString: id), uuid.uuidString.lowercased() == id,
              uuid.uuid.6 >> 4 == 4, uuid.uuid.8 >> 6 == 2,
              !kind.isEmpty, kind.utf8.count <= 64, createdAt >= 0 else { throw invalid("Invalid encrypted event fields") }
    }

    private func json(_ value: [String: Any]) throws -> String { String(decoding: try JSONSerialization.data(withJSONObject: value, options: .sortedKeys), as: UTF8.self) }
    private func object(_ value: String) throws -> [String: Any] {
        guard let result = try JSONSerialization.jsonObject(with: Data(value.utf8)) as? [String: Any] else { throw invalid("Invalid persisted protocol state") }
        return result
    }
    private func text(_ statement: OpaquePointer, _ column: Int32) -> String { sqlite3_column_text(statement, column).map { String(cString: $0) } ?? "" }
    private func digest(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
    private func invalid(_ detail: String) -> MsgATDatabaseError { MsgATDatabaseError(operation: "Signal protocol", detail: detail) }
}
