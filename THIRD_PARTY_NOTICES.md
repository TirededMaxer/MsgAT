# 第三方组件

V2 重构的 Android、Windows、macOS 客户端及 shared/security-core 按 AGPL-3.0-only 发布，全文见 LICENSE-AGPL-3.0；Java 服务端及未另行声明的原有代码继续适用根目录 MIT 许可证。第三方组件保留各自许可证。本次客户端许可调整经项目所有者批准。

共享安全核心使用 libsignal v0.106.2，固定提交 d5c8bb84459a46945273f11b8a7e067c5e872b33（AGPL-3.0-only），包含其相应的后量子协议依赖。上游官方不提供对第三方产品的使用支持。发布安装包必须附带相应许可、第三方声明与对应源代码获取方式，不能只附 MIT 文本。

发布包包含或使用以下主要组件；完整版本与传递依赖以各组件的构建文件和锁文件为准。

| 平台 | 主要组件 | 版本来源 |
| --- | --- | --- |
| Android | AndroidX / Jetpack Compose、Kotlin、Room、SQLCipher、Netty、Bouncy Castle、WebRTC、Coil | `MsgAT/gradle/libs.versions.toml`、`MsgAT/app/build.gradle.kts` |
| Windows | .NET / WPF、WebView2、Microsoft.Data.Sqlite、SQLitePCLRaw SQLCipher、Bouncy Castle、SkiaSharp（MIT，Skia 保留其 BSD 等声明） | `MsgAT_Windows/Directory.Packages.props` |
| macOS | SQLCipher.swift、WebRTC、BLAKE2b 实现 | `MsgAT_macOS/Package.swift`、`Package.resolved`、`Sources/CBlake2/` |
| 服务端 | Netty、Bouncy Castle、SLF4J、Logback、Gson、SQLite JDBC | `MsgAT_Console/pom.xml` |

保留依赖包内的 LICENSE、NOTICE、版权声明和相关资源。修改或再分发时，请同时遵守对应依赖的许可条件。第三方名称和商标归各自权利人所有。

Mac 的 `MsgAT_macOS/NativeAudio/include/RTCAudioDevice.h` 是 WebRTC M125 的公开接口头文件，保留原版权及 BSD/WebRTC 许可声明，用于补齐该版本 macOS 二进制包未导出的协议声明；音频桥接使用公开的工厂初始化方法。
