// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// Transport authentication only. Business events must pass through the V2
/// authenticated Signal envelope and its transactional protocol store.
public final class GhostClientHandler: @unchecked Sendable {
    public static let shared = GhostClientHandler()
    private let queue = DispatchQueue(label: "org.msgat.handler", qos: .userInitiated)
    private init() {}

    public func handlePacket(_ packet: GhostPacket) {
        queue.async {
            switch packet.type {
            case GhostPacket.typeV2KeyPublished, GhostPacket.typeV2KeyResponse,
                 GhostPacket.typeV2Stored, GhostPacket.typeV2Delivery, GhostPacket.typeV2SyncEnd:
                SecureMessaging.shared.handle(packet)
            case GhostPacket.typeAuthChal:
                self.handleAuthChallenge(packet)
            case GhostPacket.typeOfflineEnd:
                SecureMessaging.shared.beginSync()
            default:
                break // Never interpret or decrypt V1 business packets.
            }
        }
    }

    private func handleAuthChallenge(_ packet: GhostPacket) {
        guard let nonce = packet.nonceOrIv, nonce.count == 16,
              let (publicKey, signature) = try? CryptoManager.signChallenge(
                privateKey: KeychainManager.shared.ed25519PrivateKey, nonce: nonce) else { return }
        var response = publicKey
        response.append(signature)
        NWClient.shared.sendPacket(GhostPacket(type: GhostPacket.typeAuthResp,
            targetMailboxId: KeychainManager.shared.myMailboxId, nonceOrIv: nonce,
            encryptedData: response, authTag: Data(count: 16)))
    }
}
