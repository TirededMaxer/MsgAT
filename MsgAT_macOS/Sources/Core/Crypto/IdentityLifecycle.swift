// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import CryptoKit
import Darwin

/// Public cancellation intents outlive their destroyed root; no seed or database key is retained.
enum IdentityLifecycle {
    private struct Endpoint: Codable { let host: String; let port: UInt16; let pin: String; let development: Bool }
    private struct Intent: Codable { let proof: String; let endpoint: Endpoint? }
    private static let lock=NSRecursiveLock()
    private static let localOnly = Data("msgat-local-only-reset-v2".utf8)
    private static var marker: URL { RuntimePaths.applicationSupport.appendingPathComponent("identity.reset.pending") }
    private static var queue: URL { RuntimePaths.applicationSupport.appendingPathComponent("revocations",isDirectory:true) }
    static var hasPendingReset: Bool { FileManager.default.fileExists(atPath: marker.path) }
    static var canDiscardUnreadableIdentity: Bool {
        !hasPendingReset && (RuntimePaths.defaults.bool(forKey: "identity_initialized_v2") ||
            FileManager.default.fileExists(atPath: RuntimePaths.applicationSupport.appendingPathComponent("msgat_v2_vault.sqlite").path))
    }
    static func prepareLocalOnlyReset() throws {
        lock.lock(); defer { lock.unlock() }
        guard canDiscardUnreadableIdentity else { throw failure() }
        try write(localOnly, to: marker)
    }
    static func prepare() throws {
        lock.lock(); defer { lock.unlock() }
        guard !hasPendingReset else { throw failure() }
        let existing=(try? FileManager.default.contentsOfDirectory(at:queue,includingPropertiesForKeys:nil)) ?? []
        guard existing.filter({ $0.pathExtension=="json" }).count<256 else { throw failure() }
        let result=try KeychainManager.shared.withRootSeed { seed in try SecurityCore.call(["operation":"create_revocation","seed":seed.base64EncodedString()]) }
        guard let proof=result["proof"] as? String else { throw failure() }
        let configured=RelayEndpoint.configured()
        let endpoint=configured.map { Endpoint(host:$0.host,port:$0.port,pin:$0.certificatePin,development:$0.allowDevelopmentCertificate) }
        let intent=Intent(proof:proof,endpoint:endpoint)
        _=try validate(intent)
        try write(JSONEncoder().encode(intent),to:marker)
    }
    static func resume() throws {
        lock.lock(); defer { lock.unlock() }
        guard FileManager.default.fileExists(atPath:marker.path) else { return }
        let bytes = try readBytes(marker)
        if bytes != localOnly {
            let intent = try JSONDecoder().decode(Intent.self, from: bytes)
            let hash = try validate(intent)
            if intent.endpoint != nil { try write(bytes, to: queue.appendingPathComponent(hash+".json")) }
        }
        try KeychainManager.shared.destroyIdentityProtection()
        let root=RuntimePaths.applicationSupport
        for name in ["msgat_vault.sqlite","msgat_v2_vault.sqlite","msgat_vault.db","msgat_v2_vault.db"] {
            for suffix in ["","-wal","-shm","-journal"] { try remove(root.appendingPathComponent(name+suffix)) }
        }
        for name in ["media","objects"] { try remove(root.appendingPathComponent(name,isDirectory:true)) }
        let caches=FileManager.default.urls(for:.cachesDirectory,in:.userDomainMask)[0].appendingPathComponent(RuntimePaths.identifier,isDirectory:true)
        try remove(caches)
        RuntimePaths.defaults.removePersistentDomain(forName:RuntimePaths.identifier)
        guard RuntimePaths.defaults.synchronize() else { throw failure() }
        try remove(marker) // Startup can provision a fresh identity only after the old content is gone.
    }
    static func deliver(after cursor: String?, transport: AttachmentTransport, reportPending: (Int) -> Void) -> String? {
        let entries=(try? FileManager.default.contentsOfDirectory(at:queue,includingPropertiesForKeys:nil)) ?? []
        let files = entries.filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
        var pending = files.count
        reportPending(pending)
        guard !files.isEmpty else { return nil }
        let start = cursor.flatMap { name in files.firstIndex { $0.lastPathComponent > name } } ?? 0
        var lastAttempted = cursor
        for offset in 0..<min(files.count, 8) {
            let file = files[(start + offset) % files.count]
            lastAttempted = file.lastPathComponent
            do {
                let intent=try read(file); let hash=try validate(intent)
                guard file.lastPathComponent==hash+".json", let value=intent.endpoint else { continue }
                let endpoint=RelayEndpoint(host:value.host,port:value.port,certificatePin:value.pin,allowDevelopmentCertificate:value.development)
                guard let proof=Data(base64Encoded:intent.proof) else { throw failure() }
                try transport.revoke(endpoint: endpoint,proof:proof)
                try remove(file)
                pending -= 1
                reportPending(pending)
            } catch is CancellationError {
                break
            } catch { /* Keep the public signed intent for the next bounded maintenance tick. */ }
        }
        return lastAttempted
    }
    private static func validate(_ intent: Intent) throws -> String {
        let result=try SecurityCore.call(["operation":"verify_revocation","proof":intent.proof])
        if let endpoint=intent.endpoint {
            guard !endpoint.host.isEmpty, endpoint.host.utf8.count<=253, endpoint.port>0,
                endpoint.pin.isEmpty || (endpoint.pin.count==64 && endpoint.pin.allSatisfy({ $0.isHexDigit })) else { throw failure() }
        }
        guard let hash=result["proof_hash"] as? String else { throw failure() }; return hash
    }
    private static func read(_ file: URL) throws -> Intent {
        try JSONDecoder().decode(Intent.self, from: readBytes(file))
    }
    private static func readBytes(_ file: URL) throws -> Data {
        let attributes=try FileManager.default.attributesOfItem(atPath:file.path)
        guard let size=attributes[.size] as? NSNumber, size.intValue<=8192 else { throw failure() }
        return try Data(contentsOf:file)
    }
    private static func write(_ bytes: Data,to file: URL) throws {
        try FileManager.default.createDirectory(at:file.deletingLastPathComponent(),withIntermediateDirectories:true,attributes:[.posixPermissions:0o700])
        try bytes.write(to:file,options:.atomic)
        try FileManager.default.setAttributes([.posixPermissions:0o600],ofItemAtPath:file.path)
        let handle=try FileHandle(forWritingTo:file); defer { try? handle.close() }; try handle.synchronize()
        let descriptor=Darwin.open(file.deletingLastPathComponent().path,O_RDONLY)
        guard descriptor>=0 else { throw failure() }; defer { Darwin.close(descriptor) }
        guard fsync(descriptor)==0 else { throw failure() }
    }
    private static func remove(_ file: URL) throws { if FileManager.default.fileExists(atPath:file.path) { try FileManager.default.removeItem(at:file) } }
    private static func failure() -> Error { MsgATDatabaseError(operation:"Identity reset",detail:"The pending identity reset could not be completed. Existing reset state was retained.") }
}

final class IdentityRevocationWorker: @unchecked Sendable {
    static let shared=IdentityRevocationWorker()
    static let pendingChanged = Notification.Name("MsgAT.IdentityRevocationsPendingChanged")
    private let queue=DispatchQueue(label:"org.msgat.identity-revocations",qos:.utility)
    private let transport = AttachmentTransport()
    private let pendingLock = NSLock()
    private var count = 0
    private var lastAttempted: String?
    private var timer: DispatchSourceTimer?
    var pendingCount: Int {
        pendingLock.lock(); defer { pendingLock.unlock() }
        return count
    }
    private func reportPending(_ pending: Int) {
        pendingLock.lock()
        let changed = count != pending
        count = pending
        pendingLock.unlock()
        if changed { NotificationCenter.default.post(name: Self.pendingChanged, object: nil) }
    }
    func start() {
        guard timer==nil else { return }
        let source=DispatchSource.makeTimerSource(queue:queue)
        source.schedule(deadline:.now(),repeating:30)
        source.setEventHandler { [weak self] in
            guard let self else { return }
            self.lastAttempted = IdentityLifecycle.deliver(after: self.lastAttempted, transport: self.transport, reportPending: self.reportPending)
        }
        timer=source; source.resume()
    }
    func close() {
        timer?.cancel(); timer=nil
        transport.close()
        queue.sync { }
    }
}
