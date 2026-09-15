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
    private var isManualDisconnect = false
    private var retryAttempt = 0
    private var receiveBuffer = Data()

    private var heartbeatTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?

    public var onPacketReceived: ((GhostPacket) -> Void)?

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
        guard KeychainManager.shared.isConfigured else { return }
        guard connection == nil else { return }

        isManualDisconnect = false
        let ip = KeychainManager.shared.serverIp!
        guard let port = UInt16(exactly: KeychainManager.shared.serverPort), port > 0 else { return }

        let tlsOptions = NWProtocolTLS.Options()
        let pin = (KeychainManager.shared.serverCertificateSha256 ?? "")
            .replacingOccurrences(of: ":", with: "").replacingOccurrences(of: " ", with: "").lowercased()
        if !pin.isEmpty {
            guard pin.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { return }
            sec_protocol_options_set_verify_block(tlsOptions.securityProtocolOptions, { (_, trust, completion) in
                let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
                guard let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate], let certificate = chain.first else { completion(false); return }
                let actual = SHA256.hash(data: SecCertificateCopyData(certificate) as Data)
                    .map { String(format: "%02x", $0) }.joined()
                completion(actual == pin)
            }, queue)
        } else if KeychainManager.shared.allowDevelopmentCertificate {
            sec_protocol_options_set_verify_block(tlsOptions.securityProtocolOptions,
                { (_, _, completion) in completion(true) }, queue)
        }

        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.enableKeepalive = true
        tcpOptions.keepaliveIdle = 60

        let params = NWParameters(tls: tlsOptions, tcp: tcpOptions)
        params.allowFastOpen = true

        let host = NWEndpoint.Host(ip)
        let portEndpoint = NWEndpoint.Port(rawValue: port)!

        let conn = NWConnection(host: host, port: portEndpoint, using: params)
        self.connection = conn

        conn.stateUpdateHandler = { [weak self] state in
            guard let self = self, self.connection === conn else { return }
            switch state {
            case .ready:
                DispatchQueue.main.async { self.connectionState = .syncing }
                self.resetBackoff()
                self.startReceiving()
                self.startHeartbeat()
                self.sendAuthRequest()
            case .failed(let error):
                print("[NWClient] Connection failed: \(error)")
                self.handleDisconnection()
            case .cancelled:
                self.handleDisconnection()
            case .waiting(let error):
                print("[NWClient] Connection waiting: \(error)")
            default:
                break
            }
        }

        conn.start(queue: queue)
    }

    public func disconnect() { onQueue { disconnectOnQueue() } }

    private func disconnectOnQueue() {
        isManualDisconnect = true
        heartbeatTask?.cancel()
        heartbeatTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil

        let previous = connection
        connection = nil
        previous?.stateUpdateHandler = nil
        previous?.cancel()
        receiveBuffer.removeAll()

        DispatchQueue.main.async {
            self.connectionState = .disconnected
        }
    }

    private func handleDisconnection() {
        heartbeatTask?.cancel()
        heartbeatTask = nil

        let previous = connection
        connection = nil
        previous?.stateUpdateHandler = nil
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
                if !self.isManualDisconnect && self.connection == nil { self.connectOnQueue() }
            }
        }
    }

    private var deletionReply: (nonce: Data, completion: (Bool) -> Void)?

    public func deleteAccount() async -> Bool {
        await withCheckedContinuation { continuation in
            queue.async {
                guard self.deletionReply == nil else { continuation.resume(returning: false); return }
                var bytes = [UInt8](repeating: 0, count: 16)
                guard SecRandomCopyBytes(kSecRandomDefault, 16, &bytes) == errSecSuccess else {
                    continuation.resume(returning: false); return
                }
                let nonce = Data(bytes)
                self.deletionReply = (nonce, { continuation.resume(returning: $0) })
                let packet = GhostPacket(type: GhostPacket.typeDeleteAccount,
                    targetMailboxId: KeychainManager.shared.myMailboxId, nonceOrIv: nonce,
                    encryptedData: Data(), authTag: Data(count: 16))
                if !self.sendPacket(packet) { self.finishDeletion(false); return }
                self.queue.asyncAfter(deadline: .now() + 10) {
                    if self.deletionReply?.nonce == nonce { self.finishDeletion(false) }
                }
            }
        }
    }

    private func finishDeletion(_ result: Bool) {
        let pending = deletionReply
        deletionReply = nil
        pending?.completion(result)
    }

    // MARK: - Sending

    @discardableResult
    public func sendPacket(_ packet: GhostPacket) -> Bool { onQueue { sendOnQueue(packet) } }

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
                        if packet.type == GhostPacket.typeDeleteAccount, packet.nonceOrIv == self.deletionReply?.nonce {
                            self.finishDeletion(true)
                        } else { self.onPacketReceived?(packet) }
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
        let mailboxId = KeychainManager.shared.myMailboxId
        let packet = GhostPacket(
            type: GhostPacket.typeAuthReq,
            targetMailboxId: mailboxId,
            nonceOrIv: Data(count: 16),
            encryptedData: Data(),
            authTag: Data(count: 16)
        )
        sendPacket(packet)
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
                self.sendPacket(heartbeatPacket)
            }
        }
    }
}
