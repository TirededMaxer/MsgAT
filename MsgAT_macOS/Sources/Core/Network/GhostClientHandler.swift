// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// Transport authentication only. Business events must pass through the V2
/// authenticated Signal envelope and its transactional protocol store.
public final class GhostClientHandler: @unchecked Sendable {
    public static let shared = GhostClientHandler()
    private let queue = DispatchQueue(label: "org.msgat.handler", qos: .userInitiated)
    private init() {}

    public func handlePacket(_ packet: GhostPacket, epoch: UInt64) {
        queue.async {
            guard NWClient.shared.isCurrent(epoch) else { return }
            switch packet.type {
            case GhostPacket.typeV2KeyPublished, GhostPacket.typeV2KeyResponse,
                 GhostPacket.typeV2Rejected, GhostPacket.typeV2Deferred,
                 GhostPacket.typeV2Stored, GhostPacket.typeV2Delivery, GhostPacket.typeV2SyncEnd:
                SecureMessaging.shared.handle(packet, epoch: epoch)
            case GhostPacket.typeOfflineEnd:
                SecureMessaging.shared.beginSync(epoch: epoch, directoryNonce: packet.nonceOrIv)
            default:
                break // Never interpret or decrypt V1 business packets.
            }
        }
    }

}
