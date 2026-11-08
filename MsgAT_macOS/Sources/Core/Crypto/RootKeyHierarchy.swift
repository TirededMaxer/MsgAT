// SPDX-License-Identifier: AGPL-3.0-only
import CryptoKit
import Foundation

enum RootKeyHierarchy {
    enum KeyError: Error { case invalidSeed, invalidPurpose, invalidEnvelope }

    static func derive(seed: Data, purpose: String) throws -> SymmetricKey {
        guard seed.count == 32 else { throw KeyError.invalidSeed }
        guard !purpose.isEmpty, purpose.utf8.count <= 64,
              purpose.utf8.allSatisfy({ (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 47 }) else {
            throw KeyError.invalidPurpose
        }
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: seed),
            salt: Data("MsgAT/v2/root-key-hierarchy".utf8),
            info: Data(purpose.utf8),
            outputByteCount: 32
        )
    }

    static func wrap(seed: Data, purpose: String, secret: Data) throws -> Data {
        guard secret.count == 32 else { throw KeyError.invalidEnvelope }
        let key = try derive(seed: seed, purpose: "wrap/\(purpose)")
        let box = try AES.GCM.seal(secret, using: key, authenticating: Data("MsgAT/v2/wrapped-key/\(purpose)".utf8))
        guard let combined = box.combined else { throw KeyError.invalidEnvelope }
        return combined
    }

    static func unwrap(seed: Data, purpose: String, envelope: Data) throws -> Data {
        guard envelope.count == 60 else { throw KeyError.invalidEnvelope }
        let key = try derive(seed: seed, purpose: "wrap/\(purpose)")
        return try AES.GCM.open(
            AES.GCM.SealedBox(combined: envelope),
            using: key,
            authenticating: Data("MsgAT/v2/wrapped-key/\(purpose)".utf8)
        )
    }
}
