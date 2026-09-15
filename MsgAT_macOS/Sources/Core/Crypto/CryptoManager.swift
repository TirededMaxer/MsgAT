import Foundation
import CryptoKit

public enum CryptoError: Error, LocalizedError {
    case invalidKey
    case encryptionFailed
    case decryptionFailed
    case invalidNonce

    public var errorDescription: String? {
        switch self {
        case .invalidKey: return "Invalid cryptographic key"
        case .encryptionFailed: return "Encryption failed"
        case .decryptionFailed: return "Decryption failed"
        case .invalidNonce: return "Invalid Nonce/IV"
        }
    }
}

public final class CryptoManager {
    public static let shared = CryptoManager()

    private init() {}

    /// Computes a blake2b hash of the UUID to serve as the server routing mailbox ID (64 hex characters).
    public static func calculateMailboxId(uuid: String) -> String {
        guard let data = uuid.data(using: .utf8) else { return "" }
        let hash = Blake2b.hash512(data: data)
        let first32Bytes = hash.prefix(32)
        return first32Bytes.map { String(format: "%02x", $0) }.joined()
    }

    /// Signs a network challenge nonce using the local Ed25519 private key for authentication.
    public static func signChallenge(privateKey: Curve25519.Signing.PrivateKey, nonce: Data) throws -> (publicKey: Data, signature: Data) {
        let pubKey = privateKey.publicKey.rawRepresentation
        let signature = try privateKey.signature(for: nonce)
        return (pubKey, signature)
    }

    /// Fallback encryption key generation for friend requests when the peer's public key is unknown.
    public static func getSymmetricKeyFallback(myUuid: String, targetUuid: String) -> SymmetricKey {
        let sorted = [myUuid, targetUuid].sorted()
        let combined = "\(sorted[0])|MSGAT|\(sorted[1])"
        let digest = Blake2b.hash256(data: Data(combined.utf8))
        return SymmetricKey(data: digest)
    }

    /// True ECDH Key Agreement using X25519 + HKDF-SHA256
    public static func getSymmetricKeyECDH(myPrivateKey: Curve25519.KeyAgreement.PrivateKey, peerPublicKeyBase64: String) throws -> SymmetricKey {
        guard let peerPubBytes = Data(base64Encoded: peerPublicKeyBase64) else {
            throw CryptoError.invalidKey
        }
        let peerPublicKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPubBytes)
        let sharedSecret = try myPrivateKey.sharedSecretFromKeyAgreement(with: peerPublicKey)

        let hkdfInfo = "MSGAT_E2EE".data(using: .utf8)!
        let aesKey = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(),
            sharedInfo: hkdfInfo,
            outputByteCount: 32
        )
        return aesKey
    }

    /// Encrypts a payload using ECDH.
    public static func encryptPayloadECDH(
        myPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        peerPublicKeyBase64: String,
        iv: Data,
        plaintext: Data
    ) throws -> (cipherText: Data, authTag: Data) {
        let key = try getSymmetricKeyECDH(myPrivateKey: myPrivateKey, peerPublicKeyBase64: peerPublicKeyBase64)
        guard let nonce = try? AES.GCM.Nonce(data: iv) else {
            throw CryptoError.invalidNonce
        }
        let sealedBox = try AES.GCM.seal(plaintext, using: key, nonce: nonce)
        return (sealedBox.ciphertext, sealedBox.tag)
    }

    /// Decrypts a payload using ECDH.
    public static func decryptPayloadECDH(
        myPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        peerPublicKeyBase64: String,
        iv: Data,
        cipherText: Data,
        authTag: Data
    ) throws -> Data {
        let key = try getSymmetricKeyECDH(myPrivateKey: myPrivateKey, peerPublicKeyBase64: peerPublicKeyBase64)
        guard let nonce = try? AES.GCM.Nonce(data: iv) else {
            throw CryptoError.invalidNonce
        }
        let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: cipherText, tag: authTag)
        return try AES.GCM.open(sealedBox, using: key)
    }

    /// Encrypts a payload using fallback symmetric key.
    public static func encryptPayloadFallback(
        myUuid: String,
        targetUuid: String,
        iv: Data,
        plaintext: Data
    ) throws -> (cipherText: Data, authTag: Data) {
        let key = getSymmetricKeyFallback(myUuid: myUuid, targetUuid: targetUuid)
        guard let nonce = try? AES.GCM.Nonce(data: iv) else {
            throw CryptoError.invalidNonce
        }
        let sealedBox = try AES.GCM.seal(plaintext, using: key, nonce: nonce)
        return (sealedBox.ciphertext, sealedBox.tag)
    }

    /// Decrypts a payload using fallback symmetric key.
    public static func decryptPayloadFallback(
        myUuid: String,
        targetUuid: String,
        iv: Data,
        cipherText: Data,
        authTag: Data
    ) throws -> Data {
        let key = getSymmetricKeyFallback(myUuid: myUuid, targetUuid: targetUuid)
        guard let nonce = try? AES.GCM.Nonce(data: iv) else {
            throw CryptoError.invalidNonce
        }
        let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: cipherText, tag: authTag)
        return try AES.GCM.open(sealedBox, using: key)
    }
}
