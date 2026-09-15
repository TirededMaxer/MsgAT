// swift-tools-version: 5.9
import PackageDescription

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
        .target(
            name: "CBlake2",
            publicHeadersPath: "include"
        ),
        .executableTarget(
            name: "MsgAT",
            dependencies: [
                "CBlake2",
                .product(name: "SQLCipher", package: "SQLCipher.swift"),
                .product(name: "WebRTC", package: "WebRTC")
            ],
            path: "Sources",
            exclude: [
                "CBlake2",
                "App/Assets.xcassets",
                "App/Info.plist",
                "App/MsgAT.entitlements"
            ],
            cSettings: [.define("SQLITE_HAS_CODEC")],
            swiftSettings: [.define("SQLITE_HAS_CODEC")]
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
