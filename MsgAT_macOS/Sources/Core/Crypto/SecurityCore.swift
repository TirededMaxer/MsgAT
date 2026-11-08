// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
#if SWIFT_PACKAGE
import MsgATNativeSecurity
#endif

enum SecurityCore {
    enum Failure: LocalizedError {
        case unsupportedABI, invalidResponse, operation(String)
        var errorDescription: String? {
            switch self {
            case .unsupportedABI: return "The native security component has an incompatible version."
            case .invalidResponse: return "The native security component returned an invalid response."
            case .operation(let detail): return detail
            }
        }
    }

    static func call(_ request: [String: Any]) throws -> [String: Any] {
        guard msgat_security_abi_version() == 2 else { throw Failure.unsupportedABI }
        var encoded = try JSONSerialization.data(withJSONObject: request)
        defer { encoded.resetBytes(in: encoded.startIndex..<encoded.endIndex) }
        var buffer = MsgatBuffer(data: nil, length: 0)
        let status = encoded.withUnsafeBytes { bytes in
            msgat_security_call(bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count, &buffer)
        }
        defer { msgat_buffer_free(&buffer) }
        guard status == 0, let address = buffer.data, buffer.length > 0, buffer.length <= 8 * 1024 * 1024 else {
            throw Failure.invalidResponse
        }
        var responseData = Data(bytes: address, count: buffer.length)
        defer { responseData.resetBytes(in: responseData.startIndex..<responseData.endIndex) }
        guard let response = try JSONSerialization.jsonObject(with: responseData) as? [String: Any] else {
            throw Failure.invalidResponse
        }
        guard response["ok"] as? Bool == true else {
            throw Failure.operation(response["error"] as? String ?? "The security operation failed.")
        }
        guard let result = response["result"] as? [String: Any] else { throw Failure.invalidResponse }
        return result
    }
}
