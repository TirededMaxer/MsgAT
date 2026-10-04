# 三端以 Android 为基准的对齐记录

更新：2026-10-04。根据用户澄清，UI 对齐指页面层级、功能入口和行为，Mac 保留 SwiftUI、Windows 保留原 WPF 风格，不要求控件皮肤或动画一致。前次七类复核项中的视觉差异不能作为功能未同步的证据。本文件集中记录当前状态，替代此前互相矛盾的阶段性交付描述。源码复核、构建成功和用户实机验收是不同阶段，不能用前两项证明所有环境下的运行结果。

## 工作范围与交付边界

以 Android 为行为与界面基准，修正 Android 自身的问题，再同步 Windows 和 macOS。三端核心功能、主界面与聊天界面层级、通话覆盖层、消息操作、提示和控件行为均纳入源码核对；桌面保留原来的双栏布局，颜色、字体、控件皮肤和动画采用各平台原有实现。

本轮未启动客户端、未运行单元或设备测试、未修改本机实际音频输出，也未操作真实身份和聊天数据。正式签名 APK 通过 IDEA 的 Generate Signed App Bundle or APK 构建。实机验收由用户自行进行。2026-10-05 维护者授权提交、推送并公开仓库；本轮未新增 GitHub Release。

## 范围清单

| 项目 | Android 基准 | 当前源码状态 |
| --- | --- | --- |
| 导航、标题、连接状态和主菜单 | SessionListScreen | 桌面保留左侧会话列表、右侧聊天双栏布局，功能入口与行为对齐 |
| 会话顺序、好友行、头像、UUID、未读数 | SessionListScreen、SessionListViewModel | 按插入顺序展示，圆形完整未读数字 |
| 添加/验证好友、请求、接受/拒绝和删除 | Dialogs、FriendRequestsDialog、SessionListViewModel | 字段、动作顺序、确认和失败状态已核对 |
| 身份创建、头像裁切、复制和重置 UUID | IdentitySetupDialog、Dialogs | 写入头像成功后完成身份建立，重置仅在设置 |
| 设置、折叠高级网络、TLS、TURN 和保存 | Dialogs、SecurePrefs | 保留滚动内容和校验；Mac 原生字段/开关、Windows 原 WPF 字段/复选框 |
| 聊天标题、连接指示、输入和附件 | ChatScreen | 发送/加号入口、附件区和引用操作保持一致，使用平台控件样式 |
| 文本、图片、视频、语音、时间和回执 | ChatScreen | 媒体独立于气泡、250 × 200 缩略图，回执状态映射一致 |
| 分页、最新消息、引用定位及高亮 | ChatScreen、ChatViewModel | 每页 50，按页定位引用，定位后高亮 1.5 秒 |
| 回复、编辑、撤回和重新编辑 | ChatScreen、ChatViewModel | 同会话/本人消息/120 秒窗口，发送前清除引用 |
| 已读、送达 ACK、离线重发 | 网络处理器、MsgDao | 最新三条可见时已读，遮挡时暂停，数据库原子标记 |
| 媒体预览、播放、保存、关闭和失败 | ChatScreen、媒体管理器 | 应用内覆盖层，统一缩放、保存确认与提示 |
| 通话、静音、扬声器、最小化 | CallOverlay、CallManager | 通话覆盖层、最小化和实际设备路由已实现，动画采用平台实现 |
| 信令、超时、ICE、重连和清理 | CallManager、CallSignalingHandler | 通话代次校验、顺序发送 ICE、超时及资源清理已核对 |
| 通知、权限和错误提示 | NotificationHelper、业务层 | 通知点击、消息合并/清理及麦克风提示已补齐 |

## 共同约束

- 主菜单顺序：Add Friend、Friend Requests、Settings；会话菜单：Clear Message、Delete Friend。
- 连接状态：Disconnected（红点）、Syncing...（绿色进度）、Connected（绿点）；Mac 的 Connected 文字使用灰色。
- 会话显示头像、昵称、UUID 和右侧未读数；字体、留白和控件尺寸按桌面原有风格实现。
- 未读徽标为红色 `#F22626`、白色 11/semibold、最小直径 20，按数字所需空间增大且保持圆形。
- 回执：1 灰色单钩表示已发送、对方未接收；2 绿色单钩表示已接收、未查看；3 绿色双钩表示已查看；0 为发送中。
- 清理确认：Warning / Clear all messages in this conversation? / Confirm / Cancel。
- 删除确认：Warning / Delete this friend and clear history? / Confirm / Cancel。
- 媒体大小上限 100 MB，超限只显示限制提示，避免 Android 重复追加通用发送失败提示。
- 每 500 ms 用单调时钟更新通话时长，每 2 秒采样 RTT；从成功候选和 remote-inbound-rtp 取有效正数最小值，没有新有效数据时保留已有值。

## 上轮复核项及纠正后的处理

| 2026-10-03 发现 | 2026-10-04 修正 | 主要源码位置 |
| --- | --- | --- |
| Windows 通知没有点击打开处理 | BalloonTipClicked 打开主窗口 | Windows App.xaml.cs |
| Mac 每条消息随机通知 ID，前台未清理 | 使用发送者稳定 ID，前台清理消息通知，点击时恢复/重开主窗口 | Mac NotificationHelper.swift、AppDelegate.swift、MainWindow.swift、MsgATApp.swift |
| Windows 引用清除时间不同，Android 超限提示重复 | 三端发送前清除引用；失败恢复文字时保留后续新输入；Android 移除重复回调 | Windows MainViewModel.cs、Android ChatScreen.kt/ChatViewModel.kt、Mac ChatView.swift |
| 通话计时/RTT 算法与周期不同 | Mac 改为 systemUptime，Windows 改为 500 ms/2 s；统一成功候选、remote-inbound 回退及最小正 RTT，Mac/Win 拒绝非有限值 | 三端 CallManager/CallService、Windows webrtc-host.html |
| Windows 缺少麦克风权限提示 | NotAllowedError/SecurityError 映射统一提示，旧通话代次不弹提示 | Windows WebRtcEngine.cs、CallService.cs、webrtc-host.html、CallOverlay.xaml.cs |
| 通话动画不同 | 属于平台风格差异，不要求复制 Android；Mac 保留 SwiftUI 动画，Windows 保留 WPF 控件交互 | 桌面 CallOverlay |
| 设置控件及端口解析不同 | 浮动标签/开关皮肤不作为功能要求，恢复 Mac roundedBorder/switch 和原 WPF 字段/CheckBox；保留端口空白校验修复 | 桌面 Dialogs、Windows App.xaml |

修正桌面设置窗口排布：个人资料内联复制图标，服务器字段标签对齐，高级网络使用原生折叠分组；重置操作单独分组，取消/保存按钮固定在滚动区外。关闭高级项时使用较小窗口，展开后按屏幕可用空间限制高度，超出内容滚动。Mac 恢复 sidebar 列表与窗口工具栏标题，Windows 增加可拖动侧栏分隔条；移除双栏不需要的手机式返回按钮，长昵称截断并为状态图标保留空间。

撤回桌面 Material 配色、强制深色/绿色强调、浮动标签、自定义 Material 开关和 Android 按压动画；恢复 Mac 原气泡配色及系统控件，Windows 原主题/窗口外框/字段和按钮样式。回执辅助功能说明保留。Windows 原有 Message... 占位提示已核对，无需重复实现。取消 Windows 每十秒发送、其余客户端不发送且接收端不使用的冗余通话同步包，保留协议兼容入口。

Mac 双栏复核补充：已读回执统一在入口检查当前聊天仍显示、最新消息可见、应用与主窗口获得焦点、没有附属弹窗或完整媒体/通话覆盖。弹窗关闭或主窗口恢复焦点时重新计算已读。通知恢复通过主窗口弱引用查找，不依赖当前聊天标题。

桌面名字与右侧小尺寸连接对勾在顶栏最左侧排列。Mac 原生工具栏关闭共享背景，不将标题或对勾包进按钮；Windows 使用紧邻名字的 14 点指示区。连接对勾颜色为 Mac #16834A、Windows #2AA46F；侧栏绿点为 Mac #1D9656、Windows #35B67D。桌面附件栏增加展开和收起动画。

三端输入框支持粘贴图片直接发送，保留文字草稿和普通文字粘贴。Mac 使用原生文本编辑器的粘贴入口，Windows 使用输入框范围内的 Paste 命令，Android 使用 Compose 接收内容接口，并在异步读取结束前持有输入法内容权限对象。发送目标和引用在接收图片时确定；临时图片发送入库后清理。Android 的接口依据见 [Compose 复制和粘贴文档](https://developer.android.com/develop/ui/compose/touch-input/copy-and-paste)。

## Mac 扬声器方案

用户已同意暂时切换系统默认音频输出。开启时保存原设备 UID 并选择内置扬声器；关闭、结束通话或退出时尝试恢复原设备。仅当前系统输出仍是本次设置的扬声器时恢复，保留用户或其他应用更改的路由。恢复记录持久保存，异常退出后下一次启动再尝试恢复；原设备断开时保留记录供后续恢复。

实际通话播放使用公开 RTCAudioDevice 与 AVAudioEngine 音频桥接，监听输出变化并重新绑定设备。切换异步回调校验通话连接，且在完成时读取实际路由，避免手动切换期间按钮与设备状态不一致。WebRTC 公开接口头文件及 LICENSE/PATENTS 随项目和 Mac 应用保留。

Windows 使用 WebView2 当前通话的默认/通信输出选择；Android 使用设备音频路由。桌面没有手机听筒，扬声器路径按各平台实际设备能力实现。系统文件选择器、权限弹窗、字体和系统通知外观由平台提供，不作为像素一致声明。

## 构建与交付

当前版本：Android 1.6 / versionCode 11；Windows 1.1.0 x64；macOS 1.1 / build 15 arm64；服务端版本保持 1.1，后续增加 JSON 端口配置与性能优化，见 [服务端部署](RELAY_DEPLOYMENT.md)。

- IDEA 正式签名 Release APK 构建成功；apksigner v2 验证通过，签名证书 SHA-256 为 `4a4f2fa7a3564906000c4daf577c03c61cc4876e5d29c0c8e723f9f297c42ce0`，与上一版一致。
- Windows Release 单文件、自包含发布成功。
- Mac Xcode Release 与 SwiftPM Release 构建成功。DMG 内应用使用本机 ad-hoc 签名，未经过 Apple 公证；严格深度签名验证通过。
- 本轮安装包位于 `~/Downloads/MsgAT/`：app-release.apk、MsgAT-Windows-1.1-x64.exe、MsgAT-macOS-1.1-arm64-review.dmg。
- 同目录包含 SHA256SUMS.txt、review-manifest.json、REVIEW-NOTES.txt。精确大小与散列以该次产物清单为准。下载目录只保留一个 MsgAT 文件夹，旧安装包及清单直接替换。

功能类差异的修复保留，视觉项按用户澄清恢复平台风格；三端重新编译并封装，Android code 11 通过 IDEA 的 Generate Signed App Bundle or APK 生成正式签名包。跨平台音频、通知权限和实际布局仍需用户验收，当前不宣称 100% 实机运行证明。2026-10-05 已获准提交、推送并公开源码；不将该授权表述为全部设备测试通过，新版 Release 附件仍待单独发布。
