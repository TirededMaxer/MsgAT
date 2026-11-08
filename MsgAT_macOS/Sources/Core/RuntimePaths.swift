// SPDX-License-Identifier: AGPL-3.0-only
import Foundation

/// Debug builds use the real application with a separate identity and data store.
enum RuntimePaths {
    #if DEBUG
    static let identifier: String = {
        let prefix = "org.msgat.mac.development"
        guard let bundle = Bundle.main.bundleIdentifier, bundle == prefix || bundle.hasPrefix(prefix + ".") else { return prefix }
        return bundle
    }()
    #else
    static let identifier = "org.msgat.mac"
    #endif

    static let defaults: UserDefaults = {
        // The app bundle already has an isolated preferences domain. Foundation
        // rejects constructing an extra suite with that same bundle identifier.
        if Bundle.main.bundleIdentifier == identifier { return .standard }
        guard let suite = UserDefaults(suiteName: identifier) else {
            preconditionFailure("Unable to open the isolated MsgAT preferences domain.")
        }
        return suite
    }()

    static var applicationSupport: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(identifier, isDirectory: true)
    }
}
