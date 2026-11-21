# 构建与发布

> 先完成批准范围内的代码并暂停，等用户准备就绪后再统一构建和实际验收。四个组件均由 GitHub 托管工作流构建。保留签名备份和真实运行数据。

项目自 2026-10-17 起使用 GitHub-hosted runners 构建。本地保留源码、Gradle Wrapper、依赖声明及锁文件、资源、许可证和文档；安装包与构建中间文件不作为构建输入。

## 日常校验

推送和 Pull Request 自动运行 [CI](https://github.com/TirededMaxer/MsgAT/actions/workflows/ci.yml)，检查 Android、Windows、macOS 和服务端。也可从 Actions 页面手动运行。

每个任务使用新的托管环境，依赖从公开仓库下载。Gradle 的 Actions 缓存、构建缓存和配置缓存均关闭；Maven、NuGet 和 SwiftPM 不恢复缓存。不依赖开发电脑上的 Android SDK、IDEA、Xcode、虚拟机或任何旧构建目录。

V2 客户端先通过 `.github/actions/native-security` 构建共享加密核心，再构建原生应用。Rust 版本由 `shared/security-core/rust-toolchain.toml` 固定，libsignal/SPQR 及其他 Rust 依赖由提交号和 `Cargo.lock` 固定；Protobuf 3.25.5 编译器从官方 Maven 仓库下载，按各平台固定 SHA-256 验证。Android 使用固定 NDK 26.3.11579264 生成 ARM64、ARMv7 JNI 库，Mac 静态链接 ARM64 库，Windows 将 x64 原生 DLL 随应用打包。库文件不进入 Git，也不从开发电脑上传。相同提交的构建来源可追溯，不承诺不同运行时间的安装包逐字节一致。

V2 仍在重构中。云端编译和打包成功只证明构建链路；硬件身份保护、聊天及其他交互必须通过实际客户端验收，不可据此宣称重构已完成。

V2 开发分支的 CI 提供 `development-android`、`development-windows`、`development-macos` 和 `development-relay` 附件，保留七天，供实际客户端验收。Android 使用 `org.msgat.development` 独立包名；Windows 使用 development 资料目录；Mac 附件包含独立的 QA One／QA Two 应用。它们不是正式 Release。Windows 发布目录显式指定到 runner 临时目录，避免 MSVC 环境变量改变默认输出路径后漏传 EXE。

Windows Qt 验收附件为 `development-windows-qt`。将 ZIP 完整解压后打开 `Open MsgAT Qt.cmd`，程序使用开发版的独立资料目录。Qt 的 DLL、平台插件、对应 Qt Base 源码和许可证随附件提供。若系统缺少 MSVC x64 运行时，使用 Qt 部署目录里的 Microsoft VC++ Redistributable。正式 Windows EXE 的切换以功能迁移和验收完成为准。 [Qt Windows 部署文档](https://doc.qt.io/qt-6/windows-deployment.html)

Android 开发包使用单独的 Actions Secrets `ANDROID_DEVELOPMENT_KEYSTORE_BASE64`、`ANDROID_DEVELOPMENT_STORE_PASSWORD`，用于跨构建覆盖更新并验证身份连续性。公开证书指纹与别名位于 `scripts/ci/development-signing.json`，私钥不进入源码、日志或附件。当前开发证书 SHA-256 为 `b90eb5dc5b74b8f77d6cefc5896ce82013574c59cd03cc8234ce8102e4695cc1`。Fork／外部 PR 未获得该签名时使用临时 SDK debug 证书，不能作为原开发包的覆盖更新。正式包继续使用下述原发布证书。

## 生成安装包

在 [Cloud packages](https://github.com/TirededMaxer/MsgAT/actions/workflows/packages.yml) 手动选择待验收的分支。保持 `publish` 关闭，运行四端构建。

完成后在该次运行的 **Artifacts → MsgAT-release** 下载完整安装包与校验文件。分平台附件保留 14 天，汇总附件保留 30 天；正式 Release 长期保存。

| 平台 | 云端环境 | 构建结果 |
| --- | --- | --- |
| Android | Ubuntu、Java 21、SDK 36 | 使用原发布密钥签名的 APK，校验证书指纹和版本 |
| Windows | Windows 2025、.NET 10 LTS | x64 自包含单文件 EXE |
| macOS | macOS 26 arm64、Xcode | arm64 App、ad-hoc 签名 DMG，验证签名与磁盘映像 |
| 服务端 | Ubuntu、Java 21、Maven | 混淆 JAR、server-config.json、启动说明 |

版本和文件名从各项目源码读取。`release-manifest.json` 记录提交、版本、云端运行链接及各文件 SHA-256。`SHA256SUMS.txt` 用于核对下载文件。统一实机验收时记录这次运行的 ID、源码提交和 `release-manifest.json` 的 SHA-256。汇总任务会打印清单的 SHA-256。打包实现见 `scripts/ci/packages.py`。

Mac 当前使用 ad-hoc 签名，未经过 Apple 公证；改成云端构建不会自动获得 Developer ID。Windows 仍需 WebView2 Runtime。

## Android 签名 Secrets

以下内容放在仓库 **Settings → Secrets and variables → Actions**，不写入源码或日志：

| Secret | 内容 |
| --- | --- |
| ANDROID_KEYSTORE_BASE64 | 原发布 keystore 文件的 Base64 |
| ANDROID_STORE_PASSWORD | keystore 密码 |
| ANDROID_KEY_ALIAS | 发布密钥别名 |
| ANDROID_KEY_PASSWORD | 发布密钥密码 |

当前原签名证书 SHA-256：`4a4f2fa7a3564906000c4daf577c03c61cc4876e5d29c0c8e723f9f297c42ce0`。工作流会拒绝使用其他证书签名的 APK，避免生成无法覆盖现有安装的版本。密钥仅还原到 runner 临时目录，签名后删除；正式打包仅由维护者手动触发，不在外部 Pull Request 中读取 Secrets。

Fork 仓库需要自行配置 Secrets；如果使用自己的签名，应在打包脚本中更新公开的预期证书指纹。原发布密钥的离线备份应保留在项目之外。

## 发布与保留规则

验收通过后，从 `main` 再次手动运行 Cloud packages，开启 `publish`。填写新的 `release-*` 标签、验收构建的 `accepted_run_id` 和 `accepted_manifest_sha256`。验收提交须已进入 `main` 历史。发布任务通过自带的 `GITHUB_TOKEN` 读取此前运行的 `MsgAT-release` 附件，核对清单、四端包及配套文件的哈希，再上传这批原件。发布任务使用 `actions: read` 和 `contents: write`。

新 Release 先创建为草稿，上传 APK、EXE、DMG、JAR、配置、说明、许可证、构建清单与校验文件。远端全部文件的大小与 SHA-256 必须一致才正式发布。新版本发布成功后删除其他 Release，保留 Git 标签与提交历史。构建或上传失败时，上一份正式 Release 继续可用。

同一次验收构建、同一清单和标签可以重试未完成的发布。验收附件过期时需重新构建并验收新包。已有标签必须指向验收提交。

## 本地目录

正常云端构建阶段，本地不再维护 `.gradle/`、`.kotlin/`、`build/`、`target/`、`bin/`、`obj/`、`.build/`、DerivedData、`release-artifacts/` 等缓存与产物。它们由 `.gitignore` 排除。源码资源、锁文件、Wrapper、签名密钥离线备份和真实运行数据不能当作构建缓存删除。

历史本机构建版本为 `release-2026-10-05`；以后的安装包来源在 Release 构建清单中标记为 GitHub-hosted runners。
