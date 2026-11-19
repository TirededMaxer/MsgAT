import Foundation
import Network
import CryptoKit
import Security
import Combine

public enum SyncState: String, Sendable {
    case disconnected = "Disconnected"
    case syncing = "Syncing..."
    case synced = "Connected"
}

// Transport state is confined to queue; published UI state is changed on the main queue.
public final class NWClient: ObservableObject, @unchecked Sendable {
    public static let shared = NWClient()

    @Published public var connectionState: SyncState = .disconnected

    private var connection: NWConnection?
    private var generation: UInt64 = 0
    private var endpoint: RelayEndpoint?
    private var isManualDisconnect = false
    private var stopping = false
    private var retryAttempt = 0
    private var receiveBuffer = Data()
    private var authentication: RelayAuthentication?
    private var authenticationDeadline: DispatchWorkItem?
    private static let initialSyncTimeout: UInt64 = 40_000_000_000
    private var initialSyncProgressAt: UInt64?

    private var heartbeatTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var pathMonitor: NWPathMonitor?

    public var onPacketReceived: ((GhostPacket, UInt64) -> Void)?

    private let queue = DispatchQueue(label: "org.msgat.nwclient", qos: .userInitiated)

    private let queueKey = DispatchSpecificKey<Bool>()

    private init() { queue.setSpecific(key: queueKey, value: true) }

    private func onQueue<T>(_ work: () -> T) -> T {
        if DispatchQueue.getSpecific(key: queueKey) == true { return work() }
        return queue.sync(execute: work)
    }

    public func resetBackoff() {
        onQueue { retryAttempt = 0 }
    }

    private func calculateBackoffDelay() -> TimeInterval {
        let baseDelay = min(Double(1 << min(retryAttempt, 5)), 30.0)
        let jitter = Double.random(in: 0.0...1.0)
        return baseDelay + jitter
    }

    public func connect() { onQueue { connectOnQueue() } }

    private func connectOnQueue() {
        guard !stopping, KeychainManager.shared.isConfigured else { return }
        guard connection == nil else { return }

        isManualDisconnect = false
        guard let endpoint = RelayEndpoint.configured() else { return }
        monitorNetworkPath()
        generation &+= 1
        self.endpoint = endpoint
        guard let parameters = endpoint.parameters(queue: queue) else { return }
        let conn = NWConnection(host: NWEndpoint.Host(endpoint.host), port: NWEndpoint.Port(rawValue: endpoint.port)!, using: parameters)
        self.connection = conn

        conn.viabilityUpdateHandler = { [weak self, weak conn] viable in
            guard !viable, let self, let conn, self.connection === conn, conn.state == .ready else { return }
            self.handleDisconnection()
        }
        conn.stateUpdateHandler = { [weak self, weak conn] state in
            guard let self, let conn, self.connection === conn else { return }
            switch state {
            case .ready:
                guard let metadata = conn.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata,
                      let authentication = RelayAuthentication(mailbox: KeychainManager.shared.myMailboxId,
                          metadata: metadata.securityProtocolMetadata) else {
                    self.handleDisconnection(); return
                }
                self.authentication = authentication
                let deadline = DispatchWorkItem { [weak self, weak conn] in
                    guard let self, let conn, self.connection === conn,
                          self.authentication?.authenticated != true else { return }
                    self.handleDisconnection()
                }
                self.authenticationDeadline = deadline
                self.queue.asyncAfter(deadline: .now() + 30, execute: deadline)
                DispatchQueue.main.async { self.connectionState = .syncing }
                self.startReceiving()
                self.sendAuthRequest()
            case .failed(let error):
                print("[NWClient] Connection failed: \(error)")
                self.handleDisconnection()
            case .cancelled:
                self.handleDisconnection()
            case .waiting(let error):
                print("[NWClient] Connection waiting: \(error)")
                // A refused endpoint can remain waiting without a path change.
                // Recreate it through the same bounded retry path as failures.
                self.handleDisconnection()
            default:
                break
            }
        }

        conn.start(queue: queue)
    }

    private func monitorNetworkPath() {
        guard pathMonitor == nil else { return }
        let monitor = NWPathMonitor()
        pathMonitor = monitor
        monitor.pathUpdateHandler = { [weak self, weak monitor] path in
            guard let self, let monitor, self.pathMonitor === monitor, path.status == .satisfied,
                  !self.isManualDisconnect, self.connection == nil, let retry = self.reconnectTask else { return }
            retry.cancel()
            self.reconnectTask = nil
            self.retryAttempt = 0
            self.connectOnQueue()
        }
        monitor.start(queue: queue)
    }

    public func disconnect() { onQueue { if !stopping { disconnectOnQueue() } } }

    func stopForTermination() {
        onQueue {
            guard !stopping else { return }
            stopping = true
            disconnectOnQueue()
            onPacketReceived = nil
        }
    }

    private func disconnectOnQueue() {
        generation &+= 1
        endpoint = nil
        SecureMessaging.shared.disconnected(epoch: generation)
        isManualDisconnect = true
        heartbeatTask?.cancel()
        heartbeatTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        pathMonitor?.cancel()
        pathMonitor = nil
        authenticationDeadline?.cancel()
        authenticationDeadline = nil
        authentication = nil
        initialSyncProgressAt = nil

        let previous = connection
        connection = nil
        previous?.stateUpdateHandler = nil
        previous?.viabilityUpdateHandler = nil
        previous?.cancel()
        receiveBuffer.removeAll()

        DispatchQueue.main.async {
            self.connectionState = .disconnected
        }
    }

    private func handleDisconnection() {
        generation &+= 1
        endpoint = nil
        SecureMessaging.shared.disconnected(epoch: generation)
        authenticationDeadline?.cancel()
        authenticationDeadline = nil
        authentication = nil
        initialSyncProgressAt = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil

        let previous = connection
        connection = nil
        previous?.stateUpdateHandler = nil
        previous?.viabilityUpdateHandler = nil
        previous?.cancel()
        receiveBuffer.removeAll()

        DispatchQueue.main.async {
            self.connectionState = .disconnected
        }

        guard !isManualDisconnect, KeychainManager.shared.isConfigured else { return }

        let delay = calculateBackoffDelay()
        retryAttempt += 1
        print("[NWClient] Scheduling reconnect #\(retryAttempt) in \(delay)s")

        reconnectTask?.cancel()
        reconnectTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self.onQueue {
                if !self.isManualDisconnect && self.connection == nil {
                    self.reconnectTask = nil
                    self.connectOnQueue()
                }
            }
        }
    }

    public func sendPacket(_ packet: GhostPacket) -> Bool { onQueue { sendOnQueue(packet) } }

    func sendPacket(_ packet: GhostPacket, epoch: UInt64) -> Bool {
        onQueue { generation == epoch && authentication?.authenticated == true && sendOnQueue(packet) }
    }
    var connectionSnapshot: (epoch: UInt64, endpoint: RelayEndpoint?) {
        onQueue { (generation, authentication?.authenticated == true ? endpoint : nil) }
    }
    func isCurrent(_ epoch: UInt64) -> Bool { onQueue { generation == epoch } }

    func reportInitialSyncProgress(epoch: UInt64) {
        onQueue {
            guard generation == epoch, initialSyncProgressAt != nil else { return }
            initialSyncProgressAt = DispatchTime.now().uptimeNanoseconds
        }
    }

    func completeInitialSync(epoch: UInt64) {
        onQueue {
            guard generation == epoch, initialSyncProgressAt != nil else { return }
            initialSyncProgressAt = nil
            resetBackoff()
            DispatchQueue.main.async {
                if self.isCurrent(epoch) { self.connectionState = .synced }
            }
        }
    }

    private func watchInitialSync(epoch: UInt64, remaining: UInt64) {
        queue.asyncAfter(deadline: .now() + .nanoseconds(Int(remaining))) { [weak self] in
            guard let self, self.generation == epoch, let progress = self.initialSyncProgressAt else { return }
            let elapsed = DispatchTime.now().uptimeNanoseconds - progress
            if elapsed >= Self.initialSyncTimeout {
                self.handleDisconnection()
            } else {
                self.watchInitialSync(epoch: epoch, remaining: Self.initialSyncTimeout - elapsed)
            }
        }
    }

    private func sendOnQueue(_ packet: GhostPacket) -> Bool {
        guard let conn = connection, conn.state == .ready else {
            return false
        }
        guard let encodedData = GhostPacketCodec.encode(packet) else {
            print("[NWClient] Refused to send a packet outside the protocol size limit")
            return false
        }
        conn.send(content: encodedData, completion: .contentProcessed({ error in
            if let error = error {
                print("[NWClient] Send error: \(error)")
                self.queue.async {
                    if self.connection === conn { self.handleDisconnection() }
                }
            }
        }))
        return true
    }

    // MARK: - Receiving

    private func startReceiving() {
        guard let conn = connection else { return }

        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] (data, context, isComplete, error) in
            guard let self = self, self.connection === conn else { return }

            if let data = data, !data.isEmpty {
                self.receiveBuffer.append(data)

                while !self.receiveBuffer.isEmpty {
                    let previousCount = self.receiveBuffer.count
                    if let packet = GhostPacketCodec.decode(from: &self.receiveBuffer) {
                        guard let authentication = self.authentication else { self.handleDisconnection(); return }
                        if packet.type == GhostPacket.typeAuthChal {
                            guard let transcript = authentication.transcript(packet),
                                  let (publicKey, signature) = try? CryptoManager.signChallenge(
                                    privateKey: KeychainManager.shared.ed25519PrivateKey, nonce: transcript) else {
                                self.handleDisconnection(); return
                            }
                            guard self.sendOnQueue(GhostPacket(type: GhostPacket.typeAuthResp,
                                targetMailboxId: KeychainManager.shared.myMailboxId, nonceOrIv: packet.nonceOrIv,
                                encryptedData: publicKey + signature, authTag: Data(count: 16))) else {
                                self.handleDisconnection(); return
                            }
                            continue
                        }
                        if packet.type == GhostPacket.typeOfflineEnd {
                            guard authentication.complete(packet), let nonce = packet.nonceOrIv,
                                  nonce.count == 16, nonce.contains(where: { $0 != 0 }) else { self.handleDisconnection(); return }
                            self.authenticationDeadline?.cancel()
                            self.authenticationDeadline = nil
                            self.initialSyncProgressAt = DispatchTime.now().uptimeNanoseconds
                            self.watchInitialSync(epoch: self.generation, remaining: Self.initialSyncTimeout)
                            self.startHeartbeat()
                        }
                        guard authentication.authenticated else { self.handleDisconnection(); return }
                        self.onPacketReceived?(packet, self.generation)
                    } else {
                        if self.receiveBuffer.count != previousCount { self.handleDisconnection(); return }
                        break
                    }
                }
            }

            if isComplete {
                self.handleDisconnection()
            } else if let error = error {
                print("[NWClient] Receive error: \(error)")
                self.handleDisconnection()
            } else {
                self.startReceiving()
            }
        }
    }

    // MARK: - Auth & Heartbeat

    private func sendAuthRequest() {
        guard let authentication, sendOnQueue(authentication.request()) else {
            handleDisconnection(); return
        }
    }

    private func startHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 180 * 1_000_000_000)
                guard !Task.isCancelled, self.onQueue({ self.connection?.state == .ready }) else { break }
                let heartbeatPacket = GhostPacket(
                    type: GhostPacket.typeHeartbeat,
                    targetMailboxId: nil,
                    nonceOrIv: Data(count: 16),
                    encryptedData: Data(),
                    authTag: Data(count: 16)
                )
                _ = self.sendPacket(heartbeatPacket)
            }
        }
    }
}

/// Queue-confined and scoped to a single established TLS connection.
final class RelayAuthentication {
    private let mailbox: String
    private let mailboxBytes: Data
    private let clientNonce: Data
    private let endpoint: Data
    private let transfer: Bool
    private var challenged = false
    private(set) var authenticated = false

    init?(mailbox: String, metadata: sec_protocol_metadata_t, transfer: Bool = false) {
        guard mailbox.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { return nil }
        var leaf: Data?
        let available = sec_protocol_metadata_access_peer_certificate_chain(metadata) { certificate in
            if leaf == nil {
                let value = sec_certificate_copy_ref(certificate).takeRetainedValue()
                leaf = Data(SHA256.hash(data: SecCertificateCopyData(value) as Data))
            }
        }
        guard available, let leaf else { return nil }
        var random = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, random.count, &random) == errSecSuccess else { return nil }
        self.mailbox = mailbox
        self.mailboxBytes = Data(stride(from: 0, to: 64, by: 2).map { offset in
            let start = mailbox.index(mailbox.startIndex, offsetBy: offset)
            return UInt8(mailbox[start..<mailbox.index(start, offsetBy: 2)], radix: 16)!
        })
        self.clientNonce = Data(random)
        self.endpoint = leaf
        self.transfer = transfer
    }

    func request() -> GhostPacket {
        GhostPacket(type: GhostPacket.typeAuthReq, targetMailboxId: mailbox, nonceOrIv: clientNonce,
            encryptedData: Data((transfer ? "MTB2" : "MTA2").utf8), authTag: Data(count: 16))
    }

    func transcript(_ packet: GhostPacket) -> Data? {
        guard !challenged, !authenticated, packet.targetMailboxId == mailbox,
              let nonce = packet.nonceOrIv, nonce.count == 16,
              let body = packet.encryptedData, body.count == 50, body.first == 2, body[1] == (transfer ? 1 : 0),
              body.subdata(in: 2..<18) == clientNonce, body.subdata(in: 18..<50) == endpoint else { return nil }
        challenged = true
        var result = Data("MsgAT/v2/relay-auth\0".utf8)
        result.append(2)
        result.append(transfer ? 1 : 0)
        result.append(mailboxBytes)
        result.append(clientNonce)
        result.append(nonce)
        result.append(endpoint)
        return result
    }

    func complete(_ packet: GhostPacket) -> Bool {
        guard challenged, !authenticated, packet.type == GhostPacket.typeOfflineEnd,
              packet.targetMailboxId == mailbox else { return false }
        authenticated = true
        return true
    }
}


/// Immutable snapshot shared by control and bulk sockets. A settings edit cannot
/// silently route an existing object lease to a different server.
struct RelayEndpoint: Sendable {
    let host: String
    let port: UInt16
    let certificatePin: String
    let allowDevelopmentCertificate: Bool

    static func configured() -> RelayEndpoint? {
        guard let host = KeychainManager.shared.serverIp, !host.isEmpty,
              let port = UInt16(exactly: KeychainManager.shared.serverPort), port > 0 else { return nil }
        let pin = (KeychainManager.shared.serverCertificateSha256 ?? "")
            .replacingOccurrences(of: ":", with: "").replacingOccurrences(of: " ", with: "").lowercased()
        guard pin.isEmpty || pin.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { return nil }
        return RelayEndpoint(host: host, port: port, certificatePin: pin,
            allowDevelopmentCertificate: KeychainManager.shared.allowDevelopmentCertificate)
    }

    func parameters(queue: DispatchQueue) -> NWParameters? {
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_max_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
        if !certificatePin.isEmpty {
            let pin = certificatePin
            sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { (_, trust, completion) in
                let value = sec_trust_copy_ref(trust).takeRetainedValue()
                guard let chain = SecTrustCopyCertificateChain(value) as? [SecCertificate], let leaf = chain.first else { completion(false); return }
                let actual = SHA256.hash(data: SecCertificateCopyData(leaf) as Data).map { String(format: "%02x", $0) }.joined()
                completion(actual == pin)
            }, queue)
        } else if allowDevelopmentCertificate {
            sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { (_, _, completion) in completion(true) }, queue)
        }
        let tcp = NWProtocolTCP.Options()
        tcp.enableKeepalive = true; tcp.keepaliveIdle = 60
        return NWParameters(tls: tls, tcp: tcp)
    }
}
