// swift-tools-version: 5.9
import PackageDescription
import Foundation

let nativeSecurityLibraries = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("NativeSecurity/lib").path

let package = Package(
    name: "MsgAT",
    defaultLocalization: "en",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .executable(
            name: "MsgAT",
            targets: ["MsgAT"]
        )
    ],
    dependencies: [
        .package(url: "https://github.com/sqlcipher/SQLCipher.swift.git", from: "4.10.0"),
        .package(url: "https://github.com/stasel/WebRTC.git", from: "125.0.0")
    ],
    targets: [
        .systemLibrary(name: "MsgATNativeSecurity", path: "NativeSecurity"),
        .target(
            name: "CBlake2",
            publicHeadersPath: "include"
        ),
        .target(
            name: "MsgATNativeAudio",
            dependencies: [.product(name: "WebRTC", package: "WebRTC")],
            path: "NativeAudio",
            exclude: ["LICENSE", "PATENTS"],
            publicHeadersPath: "include"
        ),
        .executableTarget(
            name: "MsgAT",
            dependencies: [
                "CBlake2",
                "MsgATNativeAudio",
                "MsgATNativeSecurity",
                .product(name: "SQLCipher", package: "SQLCipher.swift"),
                .product(name: "WebRTC", package: "WebRTC")
            ],
            path: "Sources",
            exclude: [
                "CBlake2",
                "App/Assets.xcassets",
                "App/Info.plist",
                "App/MsgAT-Bridging-Header.h",
                "App/MsgAT.entitlements"
            ],
            resources: [.copy("App/ThirdParty")],
            cSettings: [.define("SQLITE_HAS_CODEC")],
            swiftSettings: [.define("SQLITE_HAS_CODEC")],
            linkerSettings: [
                .unsafeFlags(["-L", nativeSecurityLibraries + "/Debug"], .when(configuration: .debug)),
                .unsafeFlags(["-L", nativeSecurityLibraries + "/Release"], .when(configuration: .release))
            ]
        ),
        .testTarget(
            name: "MsgATTests",
            dependencies: [
                "MsgAT",
                .product(name: "SQLCipher", package: "SQLCipher.swift")
            ],
            path: "Tests"
        )
    ]
)
