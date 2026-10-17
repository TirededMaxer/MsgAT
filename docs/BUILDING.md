# 云端构建与发布

项目自 2026-10-17 起使用 GitHub-hosted runners 构建。本地保留源码、Gradle Wrapper、依赖声明及锁文件、资源、许可证和文档；安装包与构建中间文件不作为构建输入。

## 日常校验

推送和 Pull Request 自动运行 [CI](https://github.com/TirededMaxer/MsgAT/actions/workflows/ci.yml)，检查 Android、Windows、macOS 和服务端。也可从 Actions 页面手动运行。

每个任务使用新的托管环境，依赖从公开仓库下载。Gradle 的 Actions 缓存、构建缓存和配置缓存均关闭；Maven、NuGet 和 SwiftPM 不恢复缓存。不依赖开发电脑上的 Android SDK、IDEA、Xcode、虚拟机或任何旧构建目录。

## 生成安装包

进入 [Cloud packages](https://github.com/TirededMaxer/MsgAT/actions/workflows/packages.yml)，点击 **Run workflow**，选择 `main`：

- `publish` 不勾选：只构建，不修改 Release。
- `publish` 勾选：填写新的 `release-*` 标签，例如 `release-2026-10-17`；四端全部成功后发布。

完成后在该次运行的 **Artifacts → MsgAT-release** 下载完整安装包与校验文件。分平台附件保留 14 天，汇总附件保留 30 天；正式 Release 长期保存。

| 平台 | 云端环境 | 构建结果 |
| --- | --- | --- |
| Android | Ubuntu、Java 21、SDK 36 | 使用原发布密钥签名的 APK，校验证书指纹和版本 |
| Windows | Windows 2025、.NET 9 | x64 自包含单文件 EXE |
| macOS | macOS 26 arm64、Xcode | arm64 App、ad-hoc 签名 DMG，验证签名与磁盘映像 |
| 服务端 | Ubuntu、Java 21、Maven | 混淆 JAR、server-config.json、启动说明 |

版本和文件名从各项目源码读取。`release-manifest.json` 记录提交、版本、云端运行链接及各文件 SHA-256；`SHA256SUMS.txt` 提供下载校验。打包实现见 `scripts/ci/packages.py`。

Mac 当前沿用本机临时签名方案，未经过 Apple 公证；改成云端构建不会自动获得 Developer ID。Windows 仍需 WebView2 Runtime。

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

发布任务仅有 `contents: write` 权限，使用当前工作流自带的 `GITHUB_TOKEN`，不需要个人访问令牌。

新 Release 先创建为草稿，上传 APK、EXE、DMG、JAR、配置、说明、许可证、构建清单与校验文件。远端全部文件的大小与 SHA-256 必须一致才正式发布。新版本发布成功后删除其他 Release，保留 Git 标签与提交历史。构建或上传失败时，上一份正式 Release 继续可用。

重新执行同一提交、同一标签的失败运行可以补全草稿；其他提交必须使用新标签，不覆盖旧标签对应的包。

## 本地目录

本地不再维护 `.gradle/`、`.kotlin/`、`build/`、`target/`、`bin/`、`obj/`、`.build/`、DerivedData、`release-artifacts/` 等缓存与产物。它们由 `.gitignore` 排除。源码资源、锁文件、Wrapper、签名密钥离线备份和真实运行数据不能当作构建缓存删除。

历史本机构建版本为 `release-2026-10-05`；以后的安装包来源在 Release 构建清单中标记为 GitHub-hosted runners。
