# 第三方组件

MIT 许可证适用于本仓库维护者有权许可的项目代码，不替代第三方依赖自身的许可证。发布包包含或使用以下主要组件；完整版本与传递依赖以各组件的构建文件和锁文件为准。

| 平台 | 主要组件 | 版本来源 |
| --- | --- | --- |
| Android | AndroidX / Jetpack Compose、Kotlin、Room、SQLCipher、Netty、Bouncy Castle、WebRTC、Coil | `MsgAT/gradle/libs.versions.toml`、`MsgAT/app/build.gradle.kts` |
| Windows | .NET / WPF、WebView2、Microsoft.Data.Sqlite、SQLitePCLRaw SQLCipher、Bouncy Castle | `MsgAT_Windows/Directory.Packages.props` |
| macOS | SQLCipher.swift、WebRTC、BLAKE2b 实现 | `MsgAT_macOS/Package.swift`、`Package.resolved`、`Sources/CBlake2/` |
| 服务端 | Netty、Bouncy Castle、SLF4J、Logback、Gson、SQLite JDBC | `MsgAT_Console/pom.xml` |

保留依赖包内的 LICENSE、NOTICE、版权声明和相关资源。修改或再分发时，请同时遵守对应依赖的许可条件。第三方名称和商标归各自权利人所有。
