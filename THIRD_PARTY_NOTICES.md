# 第三方组件

V2 重构的 Android、Windows、macOS 客户端及 shared/security-core 按 AGPL-3.0-only 发布，全文见 LICENSE-AGPL-3.0；Java 服务端及未另行声明的原有代码继续适用根目录 MIT 许可证。第三方组件保留各自许可证。本次客户端许可调整经项目所有者批准。

共享安全核心使用 libsignal v0.106.2，固定提交 850942581c06fa65c0323fd52029afa02fa1a1ba（AGPL-3.0-only），包含其相应的后量子协议依赖。上游官方不提供对第三方产品的使用支持。发布安装包必须附带相应许可、第三方声明与对应源代码获取方式，不能只附 MIT 文本。

V2 附件加密使用 RustCrypto 的 aes-gcm-siv 0.12.1（MIT 或 Apache-2.0 双许可），采用 AES-256-GCM-SIV；具体依赖版本以 shared/security-core/Cargo.lock 为准。完整 Rust 静态链接依赖的版权及许可证清单仍属于正式发布前的检查项。

发布包包含或使用以下主要组件；完整版本与传递依赖以各组件的构建文件和锁文件为准。

| 平台 | 主要组件 | 版本来源 |
| --- | --- | --- |
| Android | AndroidX / Jetpack Compose、Kotlin、Room、SQLCipher、Netty、Bouncy Castle、WebRTC、Coil、ZXing Core 3.5.4（Apache-2.0） | `MsgAT/gradle/libs.versions.toml`、`MsgAT/app/build.gradle.kts` |
| Windows | .NET / WPF、WebView2、Microsoft.Data.Sqlite、SQLitePCLRaw SQLCipher、Bouncy Castle、SkiaSharp（MIT，Skia 保留其 BSD 等声明）、ZXing.Net 0.16.11（Apache-2.0） | `MsgAT_Windows/Directory.Packages.props` |
| macOS | SQLCipher.swift、WebRTC、BLAKE2b 实现 | `MsgAT_macOS/Package.swift`、`Package.resolved`、`Sources/CBlake2/` |
| 服务端 | Netty、Bouncy Castle、SLF4J、Logback、Gson、SQLite JDBC | `MsgAT_Console/pom.xml` |

保留依赖包内的 LICENSE、NOTICE、版权声明和相关资源。修改或再分发时，请同时遵守对应依赖的许可条件。第三方名称和商标归各自权利人所有。

Mac 的 `MsgAT_macOS/NativeAudio/include/RTCAudioDevice.h` 是 WebRTC M125 的公开接口头文件，保留原版权及 BSD/WebRTC 许可声明，用于补齐该版本 macOS 二进制包未导出的协议声明；音频桥接使用公开的工厂初始化方法。

联系人二维码：Android 使用 [ZXing Core](https://github.com/zxing/zxing)，Windows 使用 [ZXing.Net](https://github.com/micjahn/ZXing.Net)，均适用 Apache-2.0。许可和组件声明位于 `shared/licenses/`；Android assets 与 Windows 嵌入资源包含这两份文本。Mac 的生成/识别使用系统 Core Image 和 Vision，没有引入额外二维码依赖。安全码本身由固定版本 libsignal 生成和比较。


### UnifiedPush

安卓推送唤醒使用 UnifiedPush Android Connector 3.0.10 和 Tink Android 1.17.0。中继使用 Tink Web Push 1.15.0。相关依赖采用 Apache-2.0，版本见安卓和服务端构建文件。

### Android CameraX

Android photo capture uses Google AndroidX CameraX camera-camera2, camera-lifecycle and camera-view 1.5.3 under Apache-2.0. It receives capture images in memory; this integration adds no analytics or third-party push SDK. See [AndroidX CameraX](https://developer.android.com/jetpack/androidx/releases/camera) and the included Apache-2.0 license. Native Apple AVFoundation and Windows MediaCapture remain platform APIs.
