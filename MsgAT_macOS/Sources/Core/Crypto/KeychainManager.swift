import Foundation
import CryptoKit
import Security

public final class KeychainManager: ObservableObject {
    public static let shared = KeychainManager()

    /// Evaluated before creating the main window or starting any network services.
    static let startupFailure: String? = {
        do {
            var seed = try shared.unlockOrCreateSeed()
            seed.resetBytes(in: seed.startIndex..<seed.endIndex)
            return nil
        } catch {
            return "MsgAT requires Secure Enclave hardware protection to create or unlock an identity. No software fallback is allowed. Existing identity data has not been replaced.\n\n\(error.localizedDescription)"
        }
    }()

    private let serviceName = RuntimePaths.identifier
    private let seedAccount = "MSGAT_ROOT_SEED"
    private let dbPassphraseAccount = "MSGAT_DB_KEY_ENVELOPE_V2"
    private let turnPasswordAccount = "MSGAT_TURN_PASSWORD"
    private let wrappingKeyTag = Data("\(RuntimePaths.identifier).identity-wrap.v2".utf8)
    private let seedEnvelopePrefix = Data("MSI2".utf8)

    private let defaults = RuntimePaths.defaults
    private let identityLock = NSRecursiveLock()

    private init() {
        if !defaults.bool(forKey: "relay_tls_security_v2") {
            defaults.set(false, forKey: "allow_development_certificate")
            defaults.set(true, forKey: "relay_tls_security_v2")
        }
    }

    // MARK: - Root Seed & Keys

    public func getOrCreateSeed() -> Data {
        do { return try unlockOrCreateSeed() }
        catch { preconditionFailure("Unable to unlock this device's MsgAT identity: \(error.localizedDescription)") }
    }

    private func unlockOrCreateSeed() throws -> Data {
        identityLock.lock()
        defer { identityLock.unlock() }
        if let existing = try readKeychainData(account: seedAccount) {
                let seed: Data
                if existing.count == 32 && !defaults.bool(forKey: "identity_hardware_wrapped_v2") {
                    // One-time in-place protection of the existing local identity.
                    try writeKeychainData(data: sealIdentitySeed(existing), account: seedAccount)
                    seed = existing
                } else if existing.starts(with: SecureEnclaveSeedEnvelope.prefix) {
                    seed = try SecureEnclaveSeedEnvelope.open(existing)
                } else {
                    guard existing.starts(with: seedEnvelopePrefix) else {
                        throw identityFailure("The identity envelope is invalid; refusing identity replacement.")
                    }
                    let key = try legacyIdentityWrappingKey()
                    var error: Unmanaged<CFError>?
                    guard let opened = SecKeyCreateDecryptedData(key, .eciesEncryptionCofactorX963SHA256AESGCM,
                        existing.dropFirst(seedEnvelopePrefix.count) as CFData, &error) else {
                        throw error?.takeRetainedValue() ?? NSError(domain: NSOSStatusErrorDomain, code: Int(errSecDecode))
                    }
                    seed = opened as Data
                }
                guard seed.count == 32 else { throw identityFailure("Invalid identity seed length.") }
                defaults.set(true, forKey: "identity_hardware_wrapped_v2")
                defaults.set(true, forKey: "identity_initialized_v2")
                return seed
        }
        let existingVault = FileManager.default.fileExists(atPath: RuntimePaths.applicationSupport.appendingPathComponent("msgat_v2_vault.sqlite").path)
        guard !defaults.bool(forKey: "identity_initialized_v2"), !existingVault else {
            throw identityFailure("The identity seed is missing. Refusing to silently replace the identity.")
        }
        let seedData = randomData(count: 32)
        try writeKeychainData(data: sealIdentitySeed(seedData), account: seedAccount)
        defaults.set(true, forKey: "identity_hardware_wrapped_v2")
        defaults.set(true, forKey: "identity_initialized_v2")
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
        do {
            let seed = getOrCreateSeed()
            if let existing = loadKeychainData(account: dbPassphraseAccount) {
                return try RootKeyHierarchy.unwrap(seed: seed, purpose: "database", envelope: existing)
            }
            let existingDatabase = RuntimePaths.applicationSupport.appendingPathComponent("msgat_v2_vault.sqlite")
            if FileManager.default.fileExists(atPath: existingDatabase.path) {
                preconditionFailure("Database key is missing; refusing to replace the existing vault key.")
            }
            let keyData = randomData(count: 32)
            let envelope = try RootKeyHierarchy.wrap(seed: seed, purpose: "database", secret: keyData)
            saveKeychainData(data: envelope, account: dbPassphraseAccount)
            return keyData
        } catch {
            preconditionFailure("Unable to unlock the root-owned MsgAT database key.")
        }
    }

    // MARK: - App Preferences

    public var serverIp: String? {
        get { defaults.string(forKey: "server_ip") }
        set { defaults.set(newValue, forKey: "server_ip") }
    }

    public var serverPort: Int {
        get { defaults.object(forKey: "server_port") as? Int ?? 34478 }
        set { defaults.set(newValue, forKey: "server_port") }
    }

    public var serverCertificateSha256: String? {
        get { defaults.string(forKey: "server_certificate_sha256") }
        set { defaults.set(newValue, forKey: "server_certificate_sha256") }
    }

    public var allowDevelopmentCertificate: Bool {
        get { defaults.object(forKey: "allow_development_certificate") as? Bool ?? false }
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
        do { try SecureEnclaveSeedEnvelope.destroyDeviceKey() }
        catch { preconditionFailure("Unable to destroy the old device protection key: \(error.localizedDescription)") }
        if loadKeychainData(account: seedAccount)?.starts(with: seedEnvelopePrefix) == true {
            let query: [String: Any] = [kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: wrappingKeyTag]
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                keychainFailure(operation: "destroy the legacy identity wrapping key", status: status)
            }
        }
        do { saveKeychainData(data: try sealIdentitySeed(newSeed), account: seedAccount) }
        catch { preconditionFailure("Unable to protect the replacement identity: \(error.localizedDescription)") }
        deleteKeychainData(account: dbPassphraseAccount)
        deleteKeychainData(account: "MSGAT_DB_PASSPHRASE")
        myNickname = nil
        myAvatarPath = nil
    }

    // MARK: - Keychain Helper

    private func legacyIdentityWrappingKey() throws -> SecKey {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
            kSecAttrApplicationTag as String: wrappingKeyTag,
            kSecReturnRef as String: true
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess, let result {
            let key = result as! SecKey
            let attributes = SecKeyCopyAttributes(key) as? [String: Any]
            guard attributes?[kSecAttrTokenID as String] as? String == kSecAttrTokenIDSecureEnclave as String else {
                throw identityFailure("The identity key is not protected by Secure Enclave.")
            }
            return key
        }
        throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
    }

    private func sealIdentitySeed(_ seed: Data) throws -> Data {
        try SecureEnclaveSeedEnvelope.seal(seed)
    }

    private func saveKeychainData(data: Data, account: String) {
        do { try writeKeychainData(data: data, account: account) }
        catch { preconditionFailure("Unable to save MsgAT Keychain data: \(error.localizedDescription)") }
    }

    private func writeKeychainData(data: Data, account: String) throws {
        let identityQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: account
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let updateStatus = SecItemUpdate(identityQuery as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(updateStatus))
        }

        var addQuery = identityQuery
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        addQuery[kSecAttrSynchronizable as String] = false
        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(addStatus))
        }
    }

    private func loadKeychainData(account: String) -> Data? {
        do { return try readKeychainData(account: account) }
        catch { preconditionFailure("Unable to read MsgAT Keychain data: \(error.localizedDescription)") }
    }

    private func readKeychainData(account: String) throws -> Data? {
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
                throw identityFailure("Keychain returned an invalid MsgAT value.")
            }
            return data
        }
        if status == errSecItemNotFound { return nil }
        throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
    }

    private func identityFailure(_ detail: String) -> NSError {
        NSError(domain: "org.msgat.identity", code: 1, userInfo: [NSLocalizedDescriptionKey: detail])
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
