# MsgAT

支持 Android、Windows 和 macOS 的加密聊天应用，配套可自行部署的 Java 中继服务端。

**本分支为 V2 重构源码，等待统一构建和实机验收。** 下方下载及快速开始对应已发布的 V1。V2 的源码、设计核对和实际验收进度见 [模块完成表](docs/REBUILD_MODULES.md)、[业务逻辑核对](docs/V2_LOGIC_REVIEW.md)和[执行记录](docs/REBUILD_STATUS.md)。

## V2 重构

- V2 身份绑定原安装环境，创建和解锁需要 Android 的 TEE 或 StrongBox、Mac 的 Secure Enclave 或 Windows 的 TPM。重置会销毁本机身份和对应资料。 [身份销毁](docs/V2_IDENTITY_RESET.md)
- 单聊采用共享 libsignal 核心。三端用 SQLCipher 保存聊天与协议状态，按权限处理已读、在线及输入状态。聊天中继持有必要的公开信息与密文。V2 客户端需配套 V2 中继，并校验系统信任或手动核验的证书指纹。 [V2 协议](docs/V2_PROTOCOL.md) · [服务端部署](docs/RELAY_DEPLOYMENT.md)
- 本轮覆盖单聊、资料和安全码、会话整理、文件和媒体、定时及静默发送、位置、一对一语音和视频。聊天内拍照和录视频仅在安卓提供。桌面支持导入现有媒体，三端均支持录制语音消息。 [模块完成表](docs/REBUILD_MODULES.md)
- 网页预览由发送端生成，接收端读取加密预览。贴纸库支持文档列出的本地微信与 QQ 素材。安卓使用持续接收服务，并提供可选 UnifiedPush 唤醒。 [网页预览](docs/V2_WEB_PREVIEWS.md) · [本地表情](docs/V2_STICKERS.md) · [后台接收](docs/V2_ANDROID_BACKGROUND.md)

以下下载与功能说明仍对应已发布的 V1 版本。

通过身份 UUID 添加好友，发送文字、图片、视频与文件，进行语音通话。客户端支持离线消息、送达与已读回执、引用回复、消息编辑和撤回，以及本地加密聊天记录。

## V1 版本与下载

下载 [最新 Release](https://github.com/TirededMaxer/MsgAT/releases/latest)，包含三端安装包、服务端 JAR、JSON 配置示例和 SHA-256 校验清单。

| 组件 | 版本 | 下载 | 运行要求 |
| --- | --- | --- | --- |
| Android | 1.6（versionCode 11） | [APK](https://github.com/TirededMaxer/MsgAT/releases/latest/download/MsgAT-Android-1.6.apk) | Android 10 或更新版本 |
| Windows | 1.1 | [EXE](https://github.com/TirededMaxer/MsgAT/releases/latest/download/MsgAT-Windows-1.1-x64.exe) | Windows 10 1809 或更新版本，x64，WebView2 Runtime |
| macOS | 1.1（build 15） | [DMG](https://github.com/TirededMaxer/MsgAT/releases/latest/download/MsgAT-macOS-1.1-arm64.dmg) | macOS 13 或更新版本，Apple Silicon |
| 服务端 | 1.1（增加 JSON 配置与性能优化） | [JAR](https://github.com/TirededMaxer/MsgAT/releases/latest/download/MsgAT-Console-1.1.jar) | Java 21 |

Windows 包含 .NET 运行时。Mac DMG 打开后将 `MsgAT.app` 拖入“应用程序”；目前使用 ad-hoc 签名，未经过 Apple 公证，首次打开可能需要在系统“隐私与安全性”中允许。Android 正式 APK 使用项目发布密钥签名；自行构建需使用自己的密钥。Release 附带 `SHA256SUMS.txt`，供核对下载文件。

## V1 功能与改进

- 保留 Mac 原生 SwiftUI 和 Windows WPF 双栏界面，修正设置滚动布局、连接状态、附件展开动画和 Windows 短消息气泡宽度。
- 三端支持输入框聚焦后粘贴图片直接发送，并同步消息回执、未读状态、引用、媒体和语音处理。
- Mac 通话支持临时切换到系统扬声器，并在关闭或结束通话时恢复先前输出。
- 服务端增加 JAR 同目录 JSON 端口配置，离线消息分批发送，限制任务与网络写入积压，减少媒体分片的数据库重复扫描。

详细改动见 [更新记录](CHANGELOG.md)。安装包由 GitHub Actions 构建、签名和核验，具体版本、提交和云端运行链接记录在随包的构建清单中。

## V1 快速开始

1. 下载服务端，执行下方命令。端口读取 JAR 同目录的 `server-config.json`，缺失时自动生成，默认为 TCP `34478`；修改后重启并放行对应端口。
2. 启动客户端，设置昵称和头像，在设置中填写服务端地址与端口。
3. 交换身份 UUID，通过“＋ → Add Friend”添加好友，对方接受后开始聊天。
4. 语音首次使用时允许麦克风访问。无法直接连通的网络可在设置中配置 TURN；聊天中继本身不是 TURN 服务。

```bash
java -jar MsgAT-Console-1.1.jar
```

本轮服务端端口配置示例（`server-config.json`，修改后重启生效）：

```json
{
  "port": 34478
}
```

V1 服务端使用内置数据库密钥，默认在启动目录的 `data/` 保存数据。升级时保留该目录。

客户端默认接受自签名 TLS 证书，因此服务端未配置固定证书时也可直接连接。该模式保留传输加密，但不认证服务端证书；需要认证时，可在客户端关闭兼容选项或配置 SHA-256 指纹。从旧版本升级时会执行一次兼容设置迁移，之后保留用户修改。详见 [安全说明](SECURITY.md)。

## V1 使用说明

- **消息回执**：灰色单钩表示已发送、对方未接收；绿色单钩表示对方已接收但未查看；绿色双钩表示已查看。
- **粘贴图片**：输入框获得焦点后，Mac 使用 ⌘V、Windows 使用 Ctrl+V，Android 使用粘贴菜单或输入法图片入口，可直接发送剪贴板图片；普通文字仍粘贴到草稿中。
- **消息操作**：长按消息或在桌面端右键，可引用、复制、保存附件；自己的消息在允许的时间内可编辑或撤回。
- **会话操作**：Windows 在左侧会话列表右键，可清空消息或删除好友。
- **身份管理**：身份密钥决定 UUID。重置身份会删除对应本地聊天数据，应只在确实需要创建新身份时使用。
- **语音连接**：使用 WebRTC；网络、NAT、防火墙和 TURN 配置影响连接能力。发布构建成功不等于所有网络组合已验证，当前验证范围见 [更新记录](CHANGELOG.md)。

## 源码结构

| 路径 | 内容 |
| --- | --- |
| `MsgAT/` | Android：Kotlin、Jetpack Compose、Room、SQLCipher |
| `MsgAT_Windows/` | Windows：C#、.NET 10 LTS、WPF、WebView2 |
| `MsgAT_macOS/` | macOS：Swift、SwiftUI、原生 WebRTC |
| `MsgAT_Console/` | 中继：Java 21、Netty、SQLite |
| `docs/` | 构建、部署和协议说明 |
| `scripts/ci/` | 云端打包、签名核验和发布脚本 |
| `.github/` | CI、依赖更新、问题和 PR 模板 |

## 构建与贡献

四端均通过 GitHub Actions 构建。CI 提供开发包，Cloud packages 提供正式签名 APK、EXE、DMG 和服务端 JAR。完成实际验收后，从同一次构建发布通过验收的包，并保留最新成功的 Release。 [CI](https://github.com/TirededMaxer/MsgAT/actions/workflows/ci.yml) · [Cloud packages](https://github.com/TirededMaxer/MsgAT/actions/workflows/packages.yml)

云端从源码和锁文件恢复依赖，不使用本地缓存。本地只维护源码、资源、构建脚本和文档。签名配置、触发方式及清单说明见 [构建指南](docs/BUILDING.md)，贡献约定见 [贡献指南](CONTRIBUTING.md)。

## 许可证

V2 重构中的 Android、Windows、macOS 客户端及共享安全核心采用 [AGPL-3.0-only](LICENSE-AGPL-3.0)，Java 服务端继续采用 [MIT License](LICENSE)。第三方依赖保留各自的许可证，详见 [第三方说明](THIRD_PARTY_NOTICES.md)。当前重构范围与尚未完成的验收见 [执行记录](docs/REBUILD_STATUS.md)；上方 Release 功能说明对应原有已发布版本。
