// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import CryptoKit

/// CryptoKit exports only an opaque, device-bound Secure Enclave key blob.
/// The root envelope stays in Keychain; this file never contains a plaintext key.
enum SecureEnclaveSeedEnvelope {
    static let prefix = Data("MSI3".utf8)
    private static let domain = Data("MsgAT/v3/device-root".utf8)
    private static var keyURL: URL { RuntimePaths.applicationSupport.appendingPathComponent("identity-device.v3") }

    static func seal(_ seed: Data) throws -> Data {
        guard seed.count == 32 else { throw failure("Invalid root seed") }
        let device = try deviceKey(create: true)
        let ephemeral = P256.KeyAgreement.PrivateKey()
        let publicBytes = ephemeral.publicKey.x963Representation
        let key = try ephemeral.sharedSecretFromKeyAgreement(with: device.publicKey)
            .hkdfDerivedSymmetricKey(using: SHA256.self, salt: domain, sharedInfo: publicBytes, outputByteCount: 32)
        let header = prefix + publicBytes
        guard let combined = try AES.GCM.seal(seed, using: key, authenticating: header).combined else {
            throw failure("Unable to encode the hardware root envelope")
        }
        return header + combined
    }

    static func open(_ envelope: Data) throws -> Data {
        guard envelope.count == prefix.count + 65 + 60, envelope.starts(with: prefix) else { throw failure("Invalid hardware root envelope") }
        let publicBytes = envelope.subdata(in: prefix.count..<(prefix.count + 65))
        let remote = try P256.KeyAgreement.PublicKey(x963Representation: publicBytes)
        let key = try deviceKey(create: false).sharedSecretFromKeyAgreement(with: remote)
            .hkdfDerivedSymmetricKey(using: SHA256.self, salt: domain, sharedInfo: publicBytes, outputByteCount: 32)
        let header = envelope.prefix(prefix.count + 65)
        let sealed = try AES.GCM.SealedBox(combined: envelope.dropFirst(header.count))
        return try AES.GCM.open(sealed, using: key, authenticating: header)
    }

    static func destroyDeviceKey() throws {
        if FileManager.default.fileExists(atPath: keyURL.path) { try FileManager.default.removeItem(at: keyURL) }
    }

    private static func deviceKey(create: Bool) throws -> SecureEnclave.P256.KeyAgreement.PrivateKey {
        guard SecureEnclave.isAvailable else { throw failure("Secure Enclave hardware is unavailable") }
        if FileManager.default.fileExists(atPath: keyURL.path) {
            let attributes = try FileManager.default.attributesOfItem(atPath: keyURL.path)
            guard let size = attributes[.size] as? NSNumber, size.intValue > 0, size.intValue <= 16384 else { throw failure("Invalid device key blob") }
            let data = try Data(contentsOf: keyURL)
            return try SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: data)
        }
        guard create else { throw failure("This device's identity protection key is missing") }
        try FileManager.default.createDirectory(at: RuntimePaths.applicationSupport, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let key = try SecureEnclave.P256.KeyAgreement.PrivateKey()
        try key.dataRepresentation.write(to: keyURL, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: keyURL.path)
        var url = keyURL
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
        return key
    }

    private static func failure(_ detail: String) -> NSError {
        NSError(domain: "org.msgat.device-key", code: 1, userInfo: [NSLocalizedDescriptionKey: detail])
    }
}
