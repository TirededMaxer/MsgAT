import Foundation

public enum GhostPacketCodec {

    /// Calculates dynamic byte boundary size for packet framing (matches Netty implementation).
    public static func getBoundarySize(actualSize: Int) -> Int {
        let boundaries = [4096]
        for b in boundaries {
            if actualSize <= b {
                return b
            }
        }
        return actualSize
    }

    /// Encodes a GhostPacket into a continuous byte sequence ready for wire transmission.
    public static func encode(_ packet: GhostPacket) -> Data? {
        if let nonce = packet.nonceOrIv, nonce.count != 16 { return nil }
        if let authTag = packet.authTag, authTag.count != 16 { return nil }
        if let mailboxId = packet.targetMailboxId,
           mailboxId.lengthOfBytes(using: .utf8) > 64 { return nil }
        let payloadLen = packet.payloadLen
        // actualSize: 4(totalLen) + 1(type) + 64(mailbox) + 16(nonce) + 4(payloadLen) + payloadLen + 16(authTag)
        let actualSize = 4 + 1 + 64 + 16 + 4 + payloadLen + 16
        let paddedSize = getBoundarySize(actualSize: actualSize)
        guard paddedSize >= 105, paddedSize <= GhostPacket.maxPacketSize else {
            return nil
        }

        var data = Data()
        data.reserveCapacity(paddedSize)

        // 1. Total padded size (4 bytes, Big Endian)
        var paddedSizeBE = UInt32(paddedSize).bigEndian
        withUnsafeBytes(of: &paddedSizeBE) { data.append(contentsOf: $0) }

        // 2. Type (1 byte)
        data.append(packet.type)

        // 3. Mailbox ID (64 bytes)
        var mailboxBytes = [UInt8](repeating: 0, count: 64)
        if let mailboxId = packet.targetMailboxId, let mbData = mailboxId.data(using: .utf8) {
            let count = min(mbData.count, 64)
            mbData.copyBytes(to: &mailboxBytes, count: count)
        }
        data.append(contentsOf: mailboxBytes)

        // 4. Nonce / IV (16 bytes)
        var nonceBytes = [UInt8](repeating: 0, count: 16)
        if let nonce = packet.nonceOrIv {
            let count = min(nonce.count, 16)
            nonce.copyBytes(to: &nonceBytes, count: count)
        }
        data.append(contentsOf: nonceBytes)

        // 5. Payload length (4 bytes, Big Endian)
        var payloadLenBE = UInt32(payloadLen).bigEndian
        withUnsafeBytes(of: &payloadLenBE) { data.append(contentsOf: $0) }

        // 6. Encrypted Data
        if let encData = packet.encryptedData {
            data.append(encData)
        }

        // 7. Auth Tag (16 bytes)
        var tagBytes = [UInt8](repeating: 0, count: 16)
        if let authTag = packet.authTag {
            let count = min(authTag.count, 16)
            authTag.copyBytes(to: &tagBytes, count: count)
        }
        data.append(contentsOf: tagBytes)

        // 8. Padding (Random bytes up to paddedSize)
        let paddingSize = paddedSize - actualSize
        if paddingSize > 0 {
            var padding = [UInt8](repeating: 0, count: paddingSize)
            _ = SecRandomCopyBytes(kSecRandomDefault, paddingSize, &padding)
            data.append(contentsOf: padding)
        }

        return data
    }

    /// Decodes a GhostPacket from a buffer Data. Returns packet if complete, or nil if more data needed.
    public static func decode(from buffer: inout Data) -> GhostPacket? {
        guard buffer.count >= 4 else { return nil }

        let totalLen: Int = buffer.withUnsafeBytes { rawPtr in
            guard let baseAddress = rawPtr.baseAddress else { return 0 }
            let beValue = baseAddress.loadUnaligned(as: UInt32.self)
            return Int(UInt32(bigEndian: beValue))
        }

        guard totalLen >= 105 && totalLen <= GhostPacket.maxPacketSize else {
            // Corrupted stream, discard leading bytes
            buffer = Data(buffer.dropFirst(min(buffer.count, 4)))
            return nil
        }

        guard buffer.count >= totalLen else {
            // Need more data from wire
            return nil
        }

        // Extract exact packet frame as a fresh, contiguous Data with startIndex == 0
        let frameData = Data(buffer.prefix(totalLen))
        buffer = Data(buffer.dropFirst(totalLen))

        return frameData.withUnsafeBytes { (rawPtr: UnsafeRawBufferPointer) -> GhostPacket? in
            guard let ptr = rawPtr.baseAddress else { return nil }

            // Frame offsets:
            // 0..3: totalLen (4B)
            // 4: type (1B)
            // 5..68: mailboxId (64B)
            // 69..84: nonceOrIv (16B)
            // 85..88: payloadLen (4B, big-endian)
            // 89..(89+payloadLen-1): encryptedData
            // (89+payloadLen)..(89+payloadLen+15): authTag (16B)

            let type = ptr.load(fromByteOffset: 4, as: UInt8.self)

            let mailboxData = Data(bytes: ptr.advanced(by: 5), count: 64)
            let mailboxId = String(data: mailboxData, encoding: .utf8)?
                .trimmingCharacters(in: CharacterSet(charactersIn: "\0"))

            let nonceOrIv = Data(bytes: ptr.advanced(by: 69), count: 16)

            let payloadLenBE = ptr.loadUnaligned(fromByteOffset: 85, as: UInt32.self)
            let payloadLen = Int(UInt32(bigEndian: payloadLenBE))

            guard payloadLen >= 0 && payloadLen <= (totalLen - 105) else {
                return nil
            }

            let encStart = 89
            let encryptedData = Data(bytes: ptr.advanced(by: encStart), count: payloadLen)

            let tagStart = encStart + payloadLen
            let authTag = Data(bytes: ptr.advanced(by: tagStart), count: 16)

            return GhostPacket(
                type: type,
                targetMailboxId: mailboxId,
                nonceOrIv: nonceOrIv,
                encryptedData: encryptedData,
                authTag: authTag
            )
        }
    }
}
