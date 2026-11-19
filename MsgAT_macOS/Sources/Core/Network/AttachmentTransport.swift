// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Network
import Security
import CryptoKit

enum AttachmentDownloadError: Error { case relayUnavailable, invalidCiphertext }

/// Called only on the two bounded transfer workers. Each socket has a separate
/// callback queue; neither the UI nor the Signal/database actor waits on I/O.
final class AttachmentTransport: @unchecked Sendable {
    private let lock = NSLock()
    private var active: [UUID: BulkConnection] = [:]
    private var closed = false

    func revoke(endpoint: RelayEndpoint,proof: Data) throws {
        guard proof.count==113 else { throw URLError(.badServerResponse) }
        let (id, connection) = try connection(endpoint, publicOnly: true)
        defer { finish(id, connection) }
        var bytes=[UInt8](repeating:0,count:16)
        guard SecRandomCopyBytes(kSecRandomDefault,bytes.count,&bytes)==errSecSuccess else { throw URLError(.unknown) }
        let request=GhostPacket(type:GhostPacket.typeV2Revoke,nonceOrIv:Data(bytes),encryptedData:proof)
        let response=try connection.exchange(request,expecting:GhostPacket.typeV2Revoked)
        guard response.encryptedData==Data(SHA256.hash(data:proof)) else { throw URLError(.badServerResponse) }
    }

    func close() {
        lock.lock(); closed = true; let connections = Array(active.values); active.removeAll(); lock.unlock()
        for connection in connections { connection.close() }
    }

    private func connection(_ endpoint: RelayEndpoint, publicOnly: Bool = false) throws -> (UUID, BulkConnection) {
        let value = try BulkConnection(endpoint)
        let id = UUID()
        lock.lock()
        if closed { lock.unlock(); value.close(); throw CancellationError() }
        active[id] = value; lock.unlock()
        do { try value.authenticate(publicOnly: publicOnly); return (id, value) }
        catch { finish(id, value); throw error }
    }
    private func finish(_ id: UUID, _ connection: BulkConnection) {
        connection.close(); lock.lock(); active.removeValue(forKey: id); lock.unlock()
    }

    func upload(_ manifest: [String: Any], source: URL, endpoint: RelayEndpoint, progress: (Int64) -> Void,checkpoint:()throws->Void = {}) throws -> Int64 {
        _ = try SecurityCore.call(["operation": "validate_object_manifest", "manifest": manifest]);try checkpoint()
        let (id, connection) = try connection(endpoint); defer { finish(id, connection) }
        let objectID = try string(manifest, "object_id")
        let count = try number(manifest, "chunk_count"), size = try number(manifest, "size")
        let body: [String: Any] = ["cipher_size": try number(manifest, "cipher_size"),
            "cipher_sha256": try string(manifest, "cipher_sha256"), "read_token_hash": try string(manifest, "read_token_hash")]
        try checkpoint()
        var status = try readStatus(connection.exchange(packet(.typeObjectBegin, objectID, try JSONSerialization.data(withJSONObject: body)), expecting: .typeObjectStatus))
        try check(status, count: count)
        if status.complete { return status.expiry }
        let input = try FileHandle(forReadingFrom: source); defer { try? input.close() }
        guard try input.seekToEnd() == UInt64(number(manifest, "cipher_size")) else { throw unavailable() }
        try input.seek(toOffset: UInt64(status.next * 65552))
        for index in status.next..<count {
            guard let bytes = try input.read(upToCount: 65552), bytes.count == 65552 else { throw unavailable() }
            try checkpoint()
            status = try readStatus(connection.exchange(packet(.typeObjectPut, objectID, bytes, index: index), expecting: .typeObjectStatus))
            guard status.next == index + 1, !status.complete else { throw unavailable() }
            progress(min((index + 1) * 65536, size))
        }
        try checkpoint()
        status = try readStatus(connection.exchange(packet(.typeObjectFinish, objectID), expecting: .typeObjectStatus))
        try check(status, count: count)
        guard status.complete else { throw unavailable() }
        return status.expiry
    }

    func download(_ manifest: [String: Any], target: URL, endpoint: RelayEndpoint, progress: (Int64) -> Void,checkpoint:()throws->Void = {}) throws {
        _ = try SecurityCore.call(["operation": "validate_object_manifest", "manifest": manifest]);try checkpoint()
        let expected = try number(manifest, "cipher_size"), hash = try string(manifest, "cipher_sha256")
        func syncPublishedFile() throws {
            let directory = target.deletingLastPathComponent().deletingLastPathComponent()
            _ = try SecurityCore.call(["operation": "sync_object_publication", "directory": directory.path,
                                       "object_id": try string(manifest, "object_id")])
        }
        if FileManager.default.fileExists(atPath: target.path) {
            do { try verify(target, size: expected, hash: hash); try syncPublishedFile(); return }
            catch AttachmentDownloadError.invalidCiphertext { try FileManager.default.removeItem(at: target) }
        }
        try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let partial = target.appendingPathExtension("part")
        if !FileManager.default.fileExists(atPath: partial.path) {
            guard FileManager.default.createFile(atPath: partial.path, contents: nil, attributes: [.posixPermissions: 0o600]) else { throw unavailable() }
        }
        let output = try FileHandle(forWritingTo: partial)
        do {
            var length = try output.seekToEnd()
            if length > UInt64(expected) { try output.truncate(atOffset: 0); length = 0 }
            let start = Int64(length / 65552)
            try output.truncate(atOffset: UInt64(start * 65552)); try output.seek(toOffset: UInt64(start * 65552))
            let count = try number(manifest, "chunk_count")
            if start < count {
                guard let capability = Data(base64Encoded: try string(manifest, "read_token")), capability.count == 32 else { throw unavailable() }
                let (id, connection) = try connection(endpoint)
                defer { finish(id, connection) }
                for index in start..<count {
                    try checkpoint()
                    let response = try connection.exchange(packet(.typeObjectGet, try string(manifest, "object_id"), capability, index: index), expecting: .typeObjectData)
                    guard let bytes = response.encryptedData, bytes.count == 65552 else { throw unavailable() }
                    _ = try SecurityCore.call(["operation": "check_object_chunk", "chunk": ["key": try string(manifest, "key"),
                        "object_id": try string(manifest, "object_id"), "size": try number(manifest, "size"), "index": index, "data": bytes.base64EncodedString()]])
                    try output.write(contentsOf: bytes); try output.synchronize()
                    progress(min((index + 1) * 65536, try number(manifest, "size")))
                }
            }
            try output.close()
        } catch { try? output.close(); throw error }
        do { try verify(partial, size: expected, hash: hash) }
        catch AttachmentDownloadError.invalidCiphertext {
            try? FileManager.default.removeItem(at: partial)
            throw AttachmentDownloadError.invalidCiphertext
        }
        try checkpoint()
        try FileManager.default.moveItem(at: partial, to: target)
        try syncPublishedFile()
    }

    private func verify(_ file: URL, size: Int64, hash: String) throws {
        let input = try FileHandle(forReadingFrom: file); defer { try? input.close() }
        guard try input.seekToEnd() == UInt64(size) else { throw AttachmentDownloadError.invalidCiphertext }
        try input.seek(toOffset: 0)
        var digest = SHA256()
        while let chunk = try input.read(upToCount: 65552), !chunk.isEmpty { digest.update(data: chunk) }
        guard digest.finalize().map({ String(format: "%02x", $0) }).joined() == hash else { throw AttachmentDownloadError.invalidCiphertext }
    }
    private struct Status { let next: Int64; let complete: Bool; let expiry: Int64 }
    private func readStatus(_ packet: GhostPacket) throws -> Status {
        guard let data = packet.encryptedData, data.count <= 1024,
              let value = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let next = value["next_chunk"] as? Int64, next >= 0,
              let complete = value["complete"] as? Bool, let expiry = value["expires_at"] as? Int64, expiry >= 0 else { throw unavailable() }
        return Status(next: next, complete: complete, expiry: expiry)
    }
    private func check(_ status: Status, count: Int64) throws {
        guard status.next <= count, !status.complete || status.next == count else { throw unavailable() }
    }
    private func packet(_ type: UInt8, _ id: String, _ body: Data = Data(), index: Int64? = nil) throws -> GhostPacket {
        var nonce = Data(count: 16)
        guard nonce.withUnsafeMutableBytes({ SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }) == errSecSuccess else { throw unavailable() }
        if let index { var value = UInt64(index).bigEndian; withUnsafeBytes(of: &value) { nonce.replaceSubrange(0..<8, with: $0) } }
        return GhostPacket(type: type, targetMailboxId: id, nonceOrIv: nonce, encryptedData: body, authTag: Data(count: 16))
    }
    private func string(_ value: [String: Any], _ key: String) throws -> String {
        guard let result = value[key] as? String else { throw unavailable() }; return result
    }
    private func number(_ value: [String: Any], _ key: String) throws -> Int64 {
        guard let result = value[key] as? Int64, result >= 0 else { throw unavailable() }; return result
    }
    private func unavailable() -> Error { MsgATDatabaseError(operation: "Attachment", detail: "The encrypted attachment transfer is unavailable.") }
}

private final class BulkConnection: @unchecked Sendable {
    private final class Reply: @unchecked Sendable {
        private let lock = NSLock()
        private let semaphore = DispatchSemaphore(value: 0)
        private var result: Result<GhostPacket, Error>?
        func resolve(_ value: Result<GhostPacket, Error>) {
            lock.lock(); guard result == nil else { lock.unlock(); return }; result = value; lock.unlock(); semaphore.signal()
        }
        func wait() throws -> GhostPacket {
            guard semaphore.wait(timeout: .now() + 30) == .success else { throw URLError(.timedOut) }
            lock.lock(); let value = result!; lock.unlock(); return try value.get()
        }
    }
    private let queue = DispatchQueue(label: "org.msgat.bulk-socket", qos: .utility)
    private let connection: NWConnection
    private let ready = Reply()
    private var authentication: RelayAuthentication?
    private var buffer = Data()
    private var pending: (request: GhostPacket, expected: UInt8, reply: Reply)?
    private var ended = false
    init(_ endpoint: RelayEndpoint) throws {
        guard let parameters = endpoint.parameters(queue: queue) else { throw URLError(.badURL) }
        connection = NWConnection(host: NWEndpoint.Host(endpoint.host), port: NWEndpoint.Port(rawValue: endpoint.port)!, using: parameters)
    }
    func authenticate(publicOnly: Bool = false) throws {
        try queue.sync {
            guard !ended else { throw CancellationError() }
            connection.stateUpdateHandler = { [weak self] state in
                guard let self, !self.ended else { return }
                switch state {
                case .ready:
                    if publicOnly { self.ready.resolve(.success(GhostPacket(type:GhostPacket.typeV2Revoke))); return }
                    guard let metadata = self.connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata,
                          let auth = RelayAuthentication(mailbox: KeychainManager.shared.myMailboxId, metadata: metadata.securityProtocolMetadata, transfer: true) else {
                        self.fail(URLError(.secureConnectionFailed)); return
                    }
                    self.authentication = auth; self.ready.resolve(.success(auth.request()))
                case .failed(let error), .waiting(let error): self.fail(error)
                case .cancelled: self.fail(CancellationError())
                default: break
                }
            }
            connection.start(queue: queue)
        }
        do {
            let request = try ready.wait()
            if publicOnly { return }
            let challenge = try exchange(request, expecting: GhostPacket.typeAuthChal, correlated: false)
            guard let auth = authentication, let transcript = auth.transcript(challenge) else { throw URLError(.userAuthenticationRequired) }
            let signature = try KeychainManager.shared.withRootSeed { seed in
                try CryptoManager.signChallenge(privateKey: Curve25519.Signing.PrivateKey(rawRepresentation: seed), nonce: transcript)
            }
            let response = try exchange(GhostPacket(type: GhostPacket.typeAuthResp, targetMailboxId: KeychainManager.shared.myMailboxId,
                nonceOrIv: challenge.nonceOrIv, encryptedData: signature.publicKey + signature.signature, authTag: Data(count: 16)),
                expecting: GhostPacket.typeOfflineEnd, correlated: false)
            guard auth.complete(response) else { throw URLError(.userAuthenticationRequired) }
        } catch { close(); throw error }
    }
    func exchange(_ request: GhostPacket, expecting expected: UInt8, correlated: Bool = true) throws -> GhostPacket {
        let reply = Reply()
        queue.async {
            guard !self.ended, self.pending == nil, let bytes = GhostPacketCodec.encode(request) else { reply.resolve(.failure(URLError(.cannotConnectToHost))); return }
            self.pending = (request, expected, reply)
            self.connection.send(content: bytes, completion: .contentProcessed { error in
                if let error { self.fail(error) }
            })
            self.receive(correlated: correlated)
        }
        do { return try reply.wait() } catch { close(); throw error }
    }
    private func receive(correlated: Bool) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, complete, error in
            guard let self, !self.ended, let pending = self.pending else { return }
            if let error { self.fail(error); return }
            if let data { self.buffer.append(data) }
            guard self.buffer.count <= GhostPacket.maxPacketSize else { self.fail(URLError(.badServerResponse)); return }
            let before = self.buffer.count
            if let packet = GhostPacketCodec.decode(from: &self.buffer) {
                guard self.buffer.isEmpty,
                      (!correlated || (packet.nonceOrIv == pending.request.nonceOrIv && packet.targetMailboxId == pending.request.targetMailboxId)) else {
                    self.fail(URLError(.badServerResponse)); return
                }
                if pending.expected == GhostPacket.typeObjectData, packet.type == GhostPacket.typeObjectError,
                   packet.encryptedData == Data(#"{"error":"object_unavailable"}"#.utf8) {
                    self.fail(AttachmentDownloadError.relayUnavailable); return
                }
                guard packet.type == pending.expected else { self.fail(URLError(.badServerResponse)); return }
                self.pending = nil; pending.reply.resolve(.success(packet))
            } else if self.buffer.count != before || complete { self.fail(URLError(.badServerResponse)) }
            else { self.receive(correlated: correlated) }
        }
    }
    private func fail(_ error: Error) {
        ended = true; ready.resolve(.failure(error)); pending?.reply.resolve(.failure(error)); pending = nil
        buffer.removeAll(); connection.cancel()
    }
    func close() { queue.async { self.fail(CancellationError()) } }
}

// Keep wire constants on the protocol type; these aliases make the builder call sites readable.
private extension UInt8 {
    static let typeObjectBegin = GhostPacket.typeObjectBegin
    static let typeObjectStatus = GhostPacket.typeObjectStatus
    static let typeObjectPut = GhostPacket.typeObjectPut
    static let typeObjectFinish = GhostPacket.typeObjectFinish
    static let typeObjectGet = GhostPacket.typeObjectGet
    static let typeObjectData = GhostPacket.typeObjectData
}
