import Foundation

public struct GhostPacket: Sendable {
    public let type: UInt8
    public let targetMailboxId: String?
    public let nonceOrIv: Data?      // 16 bytes
    public let encryptedData: Data?
    public let authTag: Data?        // 16 bytes

    public var payloadLen: Int {
        return encryptedData?.count ?? 0
    }

    public init(
        type: UInt8,
        targetMailboxId: String? = nil,
        nonceOrIv: Data? = nil,
        encryptedData: Data? = nil,
        authTag: Data? = nil
    ) {
        self.type = type
        self.targetMailboxId = targetMailboxId
        self.nonceOrIv = nonceOrIv
        self.encryptedData = encryptedData
        self.authTag = authTag
    }

    public static let maxPacketSize: Int = 262144 // 256 KB

    public static let typeAuthReq: UInt8 = 0x00
    public static let typeAuthChal: UInt8 = 0x01
    public static let typeAuthResp: UInt8 = 0x02
    public static let typeMessage: UInt8 = 0x03
    public static let typeHeartbeat: UInt8 = 0x04
    public static let typeAck: UInt8 = 0x05
    public static let typeOfflineEnd: UInt8 = 0x06
    public static let typeSearchUser: UInt8 = 0x07
    public static let typeUpdateProfile: UInt8 = 0x08
    public static let typeGetProfile: UInt8 = 0x0C
    public static let typeProfileResp: UInt8 = 0x0D
    public static let typeFriendAccepted: UInt8 = 0x0E
    public static let typeFriendDeleted: UInt8 = 0x0F
    public static let typeRequestRevoked: UInt8 = 0x10
    public static let typeAccountReset: UInt8 = 0x11
    public static let typeDeleteAccount: UInt8 = 0x12

    // Voice Call Signaling
    public static let typeCallOffer: UInt8 = 0x13
    public static let typeCallAnswer: UInt8 = 0x14
    public static let typeCallIce: UInt8 = 0x15
    public static let typeCallReject: UInt8 = 0x16
    public static let typeCallEnd: UInt8 = 0x17
    public static let typeCallBusy: UInt8 = 0x18
    public static let typeCallSync: UInt8 = 0x19
}
