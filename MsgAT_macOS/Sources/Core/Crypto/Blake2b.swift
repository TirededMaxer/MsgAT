import Foundation
import CBlake2

public enum Blake2b {
    /// Computes Blake2b-512 digest (64 bytes)
    public static func hash512(data: Data, key: Data? = nil) -> Data {
        var out = [UInt8](repeating: 0, count: 64)
        let inBytes = [UInt8](data)
        let keyBytes = key != nil ? [UInt8](key!) : []

        _ = blake2b(
            &out,
            64,
            inBytes,
            inBytes.count,
            key != nil ? keyBytes : nil,
            keyBytes.count
        )
        return Data(out)
    }

    /// Computes Blake2b-256 digest (32 bytes)
    public static func hash256(data: Data, key: Data? = nil) -> Data {
        var out = [UInt8](repeating: 0, count: 32)
        let inBytes = [UInt8](data)
        let keyBytes = key != nil ? [UInt8](key!) : []

        _ = blake2b(
            &out,
            32,
            inBytes,
            inBytes.count,
            key != nil ? keyBytes : nil,
            keyBytes.count
        )
        return Data(out)
    }
}
