import Foundation
import CryptoKit
import Security

public final class KeychainManager: ObservableObject {
    public static let shared = KeychainManager()

    private let serviceName = "org.msgat.mac"
    private let seedAccount = "MSGAT_ROOT_SEED"
    private let dbPassphraseAccount = "MSGAT_DB_PASSPHRASE"
    private let turnPasswordAccount = "MSGAT_TURN_PASSWORD"

    private let defaults = UserDefaults.standard
    private let identityLock = NSRecursiveLock()

    private init() {
        if !defaults.bool(forKey: "relay_tls_compatibility_v1") {
            defaults.removeObject(forKey: "server_certificate_sha256")
            defaults.set(true, forKey: "allow_development_certificate")
            defaults.set(true, forKey: "relay_tls_compatibility_v1")
        }
    }

    // MARK: - Root Seed & Keys

    public func getOrCreateSeed() -> Data {
        identityLock.lock()
        defer { identityLock.unlock() }
        if let existing = loadKeychainData(account: seedAccount) {
            guard existing.count == 32 else {
                preconditionFailure("The MsgAT identity seed in Keychain has an invalid length.")
            }
            return existing
        }
        let seedData = randomData(count: 32)
        saveKeychainData(data: seedData, account: seedAccount)
        return seedData
    }

    public var ed25519PrivateKey: Curve25519.Signing.PrivateKey {
        let seed = getOrCreateSeed()
        return try! Curve25519.Signing.PrivateKey(rawRepresentation: seed)
    }

    public var x25519PrivateKey: Curve25519.KeyAgreement.PrivateKey {
        let seed = getOrCreateSeed()
        return try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: seed)
    }

    public var myUuid: String {
        let pubKey = ed25519PrivateKey.publicKey.rawRepresentation
        return SHA256.hash(data: pubKey).map { String(format: "%02x", $0) }.joined()
    }

    public var myMailboxId: String {
        return CryptoManager.calculateMailboxId(uuid: myUuid)
    }

    public var myX25519PublicKeyBase64: String {
        return x25519PrivateKey.publicKey.rawRepresentation.base64EncodedString()
    }

    // MARK: - Database Passphrase

    public func getOrCreateDatabasePassphrase() -> Data {
        identityLock.lock()
        defer { identityLock.unlock() }
        if let existing = loadKeychainData(account: dbPassphraseAccount) {
            guard existing.count == 32 else {
                preconditionFailure("The MsgAT database passphrase in Keychain has an invalid length.")
            }
            return existing
        }
        let keyData = randomData(count: 32)
        saveKeychainData(data: keyData, account: dbPassphraseAccount)
        return keyData
    }

    // MARK: - App Preferences

    public var serverIp: String? {
        get { defaults.string(forKey: "server_ip") }
        set { defaults.set(newValue, forKey: "server_ip") }
    }

    public var serverPort: Int {
        get { defaults.integer(forKey: "server_port") }
        set { defaults.set(newValue, forKey: "server_port") }
    }

    public var serverCertificateSha256: String? {
        get { defaults.string(forKey: "server_certificate_sha256") }
        set { defaults.set(newValue, forKey: "server_certificate_sha256") }
    }

    public var allowDevelopmentCertificate: Bool {
        get { defaults.object(forKey: "allow_development_certificate") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "allow_development_certificate") }
    }

    public var myNickname: String? {
        get { defaults.string(forKey: "my_nickname") }
        set { defaults.set(newValue, forKey: "my_nickname") }
    }

    // Optional TURN relay configuration (empty = pure P2P, ICE always prefers direct)
    public var turnServer: String? {
        get { defaults.string(forKey: "turn_server") }
        set { defaults.set(newValue, forKey: "turn_server") }
    }

    public var turnPort: Int {
        get { defaults.integer(forKey: "turn_port") == 0 ? 3478 : defaults.integer(forKey: "turn_port") }
        set { defaults.set(newValue, forKey: "turn_port") }
    }

    public var turnUsername: String? {
        get { defaults.string(forKey: "turn_username") }
        set { defaults.set(newValue, forKey: "turn_username") }
    }

    public var turnPassword: String? {
        get {
            if let data = loadKeychainData(account: turnPasswordAccount) {
                guard let password = String(data: data, encoding: .utf8) else {
                    preconditionFailure("The TURN password in Keychain is not valid UTF-8.")
                }
                return password
            }
            // One-time migration from versions that stored this secret in UserDefaults.
            if let legacy = defaults.string(forKey: "turn_password") {
                saveKeychainData(data: Data(legacy.utf8), account: turnPasswordAccount)
                defaults.removeObject(forKey: "turn_password")
                return legacy
            }
            return nil
        }
        set {
            if let newValue, !newValue.isEmpty {
                saveKeychainData(data: Data(newValue.utf8), account: turnPasswordAccount)
            } else {
                deleteKeychainData(account: turnPasswordAccount)
            }
            defaults.removeObject(forKey: "turn_password")
        }
    }

    public var turnUseTls: Bool {
        get { defaults.bool(forKey: "turn_use_tls") }
        set { defaults.set(newValue, forKey: "turn_use_tls") }
    }

    public var myAvatarPath: String? {
        get { defaults.string(forKey: "my_avatar_path") }
        set { defaults.set(newValue, forKey: "my_avatar_path") }
    }

    public var isConfigured: Bool {
        return !myUuid.isEmpty && (serverIp != nil && !serverIp!.isEmpty) && (1...65535).contains(serverPort)
    }

    // MARK: - Burn Identity

    public func burnIdentityAndGenerateNew() {
        identityLock.lock()
        defer { identityLock.unlock() }
        let newSeed = randomData(count: 32)
        let newPassphrase = randomData(count: 32)
        saveKeychainData(data: newSeed, account: seedAccount)
        saveKeychainData(data: newPassphrase, account: dbPassphraseAccount)
        myNickname = nil
        myAvatarPath = nil
    }

    // MARK: - Keychain Helper

    private func saveKeychainData(data: Data, account: String) {
        let identityQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: account
        ]
        let attributes: [String: Any] = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(identityQuery as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            keychainFailure(operation: "update", status: updateStatus)
        }

        var addQuery = identityQuery
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            keychainFailure(operation: "save", status: addStatus)
        }
    }

    private func loadKeychainData(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess {
            guard let data = result as? Data else {
                preconditionFailure("Keychain returned an invalid MsgAT value.")
            }
            return data
        }
        if status == errSecItemNotFound { return nil }
        keychainFailure(operation: "read", status: status)
    }

    private func deleteKeychainData(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: account
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            keychainFailure(operation: "delete", status: status)
        }
    }

    private func randomData(count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        guard status == errSecSuccess else {
            keychainFailure(operation: "generate secure random data", status: status)
        }
        return Data(bytes)
    }

    private func keychainFailure(operation: String, status: OSStatus) -> Never {
        let detail = SecCopyErrorMessageString(status, nil) as String? ?? "OSStatus \(status)"
        preconditionFailure("Unable to \(operation) the MsgAT Keychain vault: \(detail)")
    }
}
