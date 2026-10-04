# MsgAT

支持 Android、Windows 和 macOS 的加密聊天应用，配套可自行部署的 Java 中继服务端。

通过身份 UUID 添加好友，发送文字、图片、视频与文件，进行语音通话。客户端支持离线消息、送达与已读回执、引用回复、消息编辑和撤回，以及本地加密聊天记录。

## 版本与下载

下载 [2026-10-05 Release](https://github.com/TirededMaxer/MsgAT/releases/tag/release-2026-10-05)，包含三端安装包、服务端 JAR、JSON 配置示例和 SHA-256 校验清单。

| 组件 | 版本 | 下载 | 运行要求 |
| --- | --- | --- | --- |
| Android | 1.6（versionCode 11） | [APK](https://github.com/TirededMaxer/MsgAT/releases/download/release-2026-10-05/MsgAT-Android-1.6.apk) | Android 10 或更新版本 |
| Windows | 1.1 | [EXE](https://github.com/TirededMaxer/MsgAT/releases/download/release-2026-10-05/MsgAT-Windows-1.1-x64.exe) | Windows 10 1809 或更新版本，x64，WebView2 Runtime |
| macOS | 1.1（build 15） | [DMG](https://github.com/TirededMaxer/MsgAT/releases/download/release-2026-10-05/MsgAT-macOS-1.1-arm64.dmg) | macOS 13 或更新版本，Apple Silicon |
| 服务端 | 1.1（增加 JSON 配置与性能优化） | [JAR](https://github.com/TirededMaxer/MsgAT/releases/download/release-2026-10-05/MsgAT-Console-1.1.jar) | Java 21 |

Windows 包含 .NET 运行时。Mac DMG 打开后将 `MsgAT.app` 拖入“应用程序”；目前使用本机签名，未经过 Apple 公证，首次打开可能需要在系统“隐私与安全性”中允许。Android 正式 APK 使用项目发布密钥签名；自行构建需使用自己的密钥。Release 附带 `SHA256SUMS.txt`，供核对下载文件。

## 本轮更新

- 保留 Mac 原生 SwiftUI 和 Windows WPF 双栏界面，修正设置滚动布局、连接状态、附件展开动画和 Windows 短消息气泡宽度。
- 三端支持输入框聚焦后粘贴图片直接发送，并同步消息回执、未读状态、引用、媒体和语音处理。
- Mac 通话支持临时切换到系统扬声器，并在关闭或结束通话时恢复先前输出。
- 服务端增加 JAR 同目录 JSON 端口配置，离线消息分批发送，限制任务与网络写入积压，减少媒体分片的数据库重复扫描。

详细改动见 [更新记录](CHANGELOG.md)。本轮完成本地编译、签名及产物检查；未额外运行设备测试或压力测试，不提供未经测量的并发容量及 CPU、内存占用结论。

## 快速开始

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

服务端使用内置数据库密钥，无需单独提供数据库密钥文件或 `-noverify`。默认在启动目录的 `data/` 保存数据，升级时保留该目录。自定义端口和持久运行配置见 [服务端部署](docs/RELAY_DEPLOYMENT.md)。

客户端默认接受自签名 TLS 证书，因此服务端未配置固定证书时也可直接连接。该模式保留传输加密，但不认证服务端证书；需要认证时，可在客户端关闭兼容选项或配置 SHA-256 指纹。从旧版本升级时会执行一次兼容设置迁移，之后保留用户修改。详见 [安全说明](SECURITY.md)。

## 使用说明

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
| `MsgAT_Windows/` | Windows：C#、.NET 9、WPF、WebView2 |
| `MsgAT_macOS/` | macOS：Swift、SwiftUI、原生 WebRTC |
| `MsgAT_Console/` | 中继：Java 21、Netty、SQLite |
| `docs/` | 构建、部署和协议说明 |
| `.github/` | CI、依赖更新、问题和 PR 模板 |

## 构建与贡献

请阅读 [构建指南](docs/BUILDING.md) 和 [贡献指南](CONTRIBUTING.md)。Gradle Wrapper、Swift 包锁文件、NuGet 版本定义等构建输入随仓库提交；构建产物、缓存、真实数据库、签名密钥和本地 IDE 配置不会提交。发布二进制作为 Release 附件提供。

## 许可证

项目采用 [MIT License](LICENSE)。第三方依赖保留各自的许可证，详见 [第三方说明](THIRD_PARTY_NOTICES.md)。
