# Telegram 原生 macOS UI 研究记录

研究日期：2026-11-09。当前状态：**第一轮源码实查已完成，正式包运行、动画录制和逐屏视觉测量未完成，尚不满足开始大规模 UI 重构的前置条件。** 本记录覆盖本轮批准的单聊范围。

## 参考身份与证据边界

| 对象 | 固定身份 | 本轮证据 |
| --- | --- | --- |
| 产品入口 | [Telegram for macOS](https://macos.telegram.org/)，与 Qt Telegram Desktop 的 Mac 版分开 | 官方页面在研究时标为 13.2.0，2026-11-06 |
| 官方源码 | [TelegramMessenger/telegram-macos，561169337e838c3281f7cfe24652757a5b522df9](https://github.com/TelegramMessenger/telegram-macos/tree/561169337e838c3281f7cfe24652757a5b522df9)，提交时间 2026-11-04T17:12:25Z | GitHub 完整递归树未截断；按问题获取并固定 59 个相关文件，约 3.16 MB；实查范围以下文各路径记录为准，没有克隆依赖或构建 |
| 源码里的构建号 | `Telegram-Mac/Info.plist:53-54` 为 **283357** | 文件读取；营销版本字段为构建变量，不能凭此推导正式包版本 |
| 主任务只读检查的官方 DMG | **13.2.0 / 283467**；bundle `ru.keepcoder.Telegram`；最低 macOS 10.13 | 主任务提供的包元数据与 REA 记录；本子任务没有运行这个包 |
| DMG SHA-256 | `3f732bd3e0c082b5ec07e1cf59a655e2d1738968ad274c8bc806cceed29f1c88` | 主任务只读包检查记录 |
| 对应包检查证据 | `ev_71445d26618e41695672afac35b5423f088cde6b5889eaa3cd3b650f949b52c1` | REA 父证据，由主任务保管 |
| 正式包签名 | `TELEGRAM MESSENGER LLP (6N38VWS5BX)`；主任务严格深度签名核验 exit 0 | 这是包签名检查，不证明账号隔离或 UI 已运行 |

**源码 283357 与正式包 283467 不同。** 下文数值是固定源码的实际声明或分支行为，不能写成正式包的实测值，更不能声称这一批源码已被证明与下载包逐字节对应。后续以已固定正式包的实际画面与操作为视觉、动效基线，源码帮助解释；差异需逐项核对。

第三方资料临时放在 `.build-tools/telegram-ui/sources/macos/`。`source-identity.json` 保存 commit，`tree.json` 保存完整路径树，`source-files.json` 保存本轮获取文件的尺寸和 SHA-256。第三方源码及包不提交 MsgAT Git，也不复用 Telegram 名称、标志或未经核对许可的素材。本记录是独立行为/参数描述，没有引入 Telegram 产品代码。官方原生 Mac 仓库许可为 GPL-2.0；MsgAT 保持已批准的 SwiftUI 原生实现、默认双栏和自身身份/安全规则。

主任务还只读解析了正式包内 407 个 Lottie/TGS 的帧、时间轴和散列，记录在 `.build-tools/telegram-ui/artifacts/vector-animation-metadata.json`。例如 `video_to_mic.tgs` 的完整资产时间轴为 250ms，部分菜单资产为 500ms，`anim_delete.json` 整体为 60 秒。**这些不等于交互持续时间**：播放片段、速率、循环、hover 状态都要与调用路径及实际操作对应；不能按资产总帧数直接给 UI 动画定时。

## 启动隔离先查结果

不能直接把下载的官方应用当成隔离测试身份运行，也不能照搬 Windows Desktop 的 `-workdir`：

- [资料根路径][M01]由系统 App Group 容器加构建渠道前缀组成；组名由 team ID 与 bundle ID 拼接。前缀只有 debug、stable、appstore、beta 等构建配置分支。
- [配置读取][M02]来自应用 `Info.plist` 的 `SOURCE`，不是发现了一个可传参改变的运行时 profile。
- [启动过程][M03]会先调用迁移：当渠道目录为空时，[迁移逻辑][M04]可能移动旧 App Group 目录内容。即使不登录，直接启动也可能触及既有资料。
- 已检查 `main.swift`、`AppDelegate.swift` 和 ApiCredentials 包的入口/路径逻辑，未找到资料目录 override 或内建 UI 演示启动参数。`main.swift:104` 将 argv 交给 `NSApplicationMain`；这本身不证明任何自定义参数存在。这是上述已读范围内的结论，不是对整个仓库所有参数的排除证明。
- 仓库中的 `tools/perflab`、`tools/tests` 属于源码里的实验/测试工具，不能当成下载正式包已包含的演示模式；本任务没有构建或运行它们。

下一步运行前需先确定不会访问真实 Telegram 资料的隔离方式，再由用户在需要时自行完成 Telegram 测试账号登录。不能把更改 HOME、改 bundle ID、重新签名或伪造登录作为已验证隔离。此条件未解决前，继续源码及包资源研究，不启动正式参考客户端。

## 已实查的布局和动画路径

单位为源码中的 AppKit 逻辑 point；不是 Retina 物理像素。时间单位为秒。以下均为**源码观察**，运行列统一待补。

| ID / 路径 | 确认的参数或行为 | 触发、结束和中断 | 证据 |
| --- | --- | --- | --- |
| MAC-01 双栏及窄窗口 | 双栏状态下左列使用已保存宽度；拖动时先以 300 下限处理，再按给右列保留 380、扣除固定前置栏后的剩余空间截断；左列 Insets 为上/左/下 8、右 0；最小化左列 90。应用注册 dual 区间为 650–1250；启动 chrome 也以 650 为分界。不能把 650 简化为所有分栏条件。 | `.single`、`.dual`、`.minimisize` 各有控制器装配；布局变化后更新窗口限制和 chrome。拖动分隔位置在该实现中会写回宽度。 | [装配及拖动][M05]、[dual 范围][M06]、[启动 chrome][M07] |
| MAC-02 侧面板过渡 | trailing pane 使用 0.3 秒 DisplayLinkAnimator，Bezier 控制点 `(0.25, 0.8, 0.25, 1.0)`；是否容纳还取决于右侧可用空间。 | 窗口存在且非 live resize 才动画；目标未变则不重启；立即布局分支会 invalidate 旧 animator 并直接设终值。运行中反向开启的连续性仍待录像。 | [面板显隐][M08] |
| MAC-03 输入区几何 | 外侧 edge 12，附件与正文 gap 10，正文左侧内距 10；可用正文宽度扣除附件、附加菜单、右侧 actions 和内嵌发送槽。输入总高度为正文/附件高度再加底距 12；正文高度在自身 min/max 之间夹紧。 | 文本换行、附件条、回复/编辑/链接预览会参与同一几何计算；正文与发送按钮不能分别硬编码宽度。最高输入高度还在 `textViewUpdateHeight` 中按父视图高度约束。 | [宽度计算][M09]、[高度][M10]、[更新][M11] |
| MAC-04 语音/发送按钮切换 | 内嵌发送槽 40×32，内距 4，正文 gap 6；field motion 0.22、淡入淡出 0.08、模糊半径 6 / 0.15 easeOut；spring mass 1、stiffness 555.027、damping 47.118；收缩 scale 0.1。 | 首次出现不做切换动画。field/non-field 切换分支不同；非 field 切换用 alpha 0.1、scale spring 0.6；旧项淡出完成后才隐藏。反向切换的 transform 从 presentation layer 当前值继续。spring 实际时长取 `settlingDuration`，不能当成固定 0.22。 | [参数][M12]、[状态机][M13]、[中断与 spring][M14] |
| MAC-05 气泡自然宽度与时间 | 正文气泡内距 11，附加 bubble inset 6，正文与日期间距 10，常规内容间距 7。气泡宽度根据正文最后一行、时间/回执、回复/转发等计算，不是固定宽度；frame 顶部 y=2、高度为 row height−4。 | 正文最后一行能放日期时与不能放时走不同分支；RTL、网页卡片等会强制独立元信息行。媒体/overlay 的时间几何与普通文本分开。 | [气泡间距][M15]、[内容间距][M42]、[宽度][M16]、[强制独立行][M17]、[元信息坐标][M18] |
| MAC-06 回复、编辑、链接预览条 | 附件区按不同状态分别绑定 dismiss；回复关闭会清 reply ID，编辑关闭走 cancelEditing，URL 预览关闭记录对应 URL 的禁用状态；显示内容后的整体 accessory 高度还加 10。 | 显示模型及关闭 handler 每次随状态更新，不能把关闭做成只藏一层 View；回复条还能定位原消息。完整状态优先级和转发组合仍需实机复核。 | [关闭的状态变更][M19]、[回复条][M20]、[额外高度][M21] |
| MAC-07 发送前媒体确认/拖放 | 普通图片/文件 drop 都进入 `showPreviewSender`。媒体确认控制器初始 320×300；caption 输入 min 40、max 180；header 50；高度变更使用 0.2 easeOut，并限制弹窗内容高度不超过窗口可用内容高度−70。 | drop 开始隐藏输入区，退出恢复；deferred action 确认仍属于当前会话后才继续。**官方源码的 Shift+文件 drop 可直接发送，MsgAT 不能照搬这条，用户要求发送二次确认。** | [drop 路由][M22]、[确认页尺寸][M23]、[caption][M24]、[高度约束][M25]、[高度动画][M40]、[header][M41] |
| MAC-08 媒体展开及回收 | Gallery 的缩略图到大图位置/缩放过渡 0.25、`.spring`，新图 alpha 仅一半时长；从窗口坐标换算源/目标 rect。打开等待图片最多 0.7，关闭最多 0.1。 | 图片就绪后再解锁转场；没有源缩略图时有降级分支；close 清理订阅/媒体控制；presentation generation 防止旧关闭动画回调把新打开的 viewer 关掉。`.spring` 的具体曲线实现尚未追到，不能换成猜测的固定 Bezier。 | [进入/动画][M26]、[回收][M27]、[viewer 生命周期][M28] |
| MAC-09 裁剪/编辑窗口 | 编辑器按可用窗口内容宽−80、高−160 计算最大内容；Escape 会走取消，不直接当作成功。return 生成编辑结果。 | cancel 使用一次性关闭标记、清空取消回调，避免重复完成；图片生成结果的过期会话拦截仍需结合 PreviewSender 实机核对。 | [取消/回车][M29]、[窗口约束][M30] |
| MAC-10 会话列表密度 | 普通 `.chat` 和 `.savedMessages` 行高 70；折叠行可为 35；头像/摘要/时间/未读分区仍需逐元素量。 | 隐藏内容、archive、窄列与普通行分支不能混成一个固定模板。这里只确认行高声明，未测当前正式包画面。 | [行高分支][M31] |
| MAC-11 聊天顶栏 | 头像容器 36×36；有 back 时左 inset 20，否则 2；有头像时活动状态 x=头像右边+8、y=25；右侧状态标记相对标题右边+2、y+1。 | 通话/搜索按钮按可见性排位；活动/在线文字与标题、badge 分离。不能把名字和标记合并为巨大按钮。最终字号、标题栏高度、图标墨迹尺寸仍需量。 | [头像][M32]、[布局][M33] |
| MAC-12 回执图形及状态切换 | 回执分配 state/read/sending 独立位置；删除旧图标从当前 presentation alpha 起，0.2 easeOut，同时 scale 1→0.01，完成后移除图层。 | 时间、回执与发送中控件按实际状态出现，不能依赖字体字符冒充勾。Telegram 自身状态语义不覆盖 MsgAT 已确定的灰单勾/绿单勾/绿双勾规则。 | [移除动画][M34]、[状态分配][M35] |
| MAC-13 一对一通话与悬浮小窗 | 小视频拖动松手按四象限吸附，布局用 0.35 `.spring`；控制按钮出现常用 alpha 0.2 和 scale spring 0.01→1、无 bounce。CallWindowTransition 另有 120Hz 采样、自定义多通道 spring 和最长 2 秒上限。 | 窗口转场不是单一 easeOut：位置、宽、高、开合分通道；取消时移除所有已命名动画、移走内容并隐藏转场 window。完整呼入/连接/重连/失败/设备切换仍未运行。 | [拖动吸附][M36]、[按钮出现][M37]、[转场参数][M38]、[取消][M39] |

## 页面、状态和输入方式覆盖清单

“已实查”表示上文有读到的具体实现；“已定位”只证明路径存在，尚不证明细节已研究。**所有行的正式包运行观察和 MsgAT 对照均未完成。** 此清单是现阶段范围索引，后续发现新状态继续补齐，不能据此宣称 Telegram 所有 UI 已研究完。

| 页面/组件 | 必须覆盖的状态与操作 | 当前源码进度/入口 |
| --- | --- | --- |
| 首次启动、身份与解锁 | 首开、不可用硬件、锁定、恢复失败、Reset Identity | Telegram 认证不能替代 MsgAT 身份逻辑；ApiCredentials、AppDelegate 资料路径已实查；AuthUI 仅定位 |
| 主窗口双栏/资料面板 | 空会话、已选会话、侧栏拖动、窄窗口、全屏、隐藏侧栏、资料展开收起 | MAC-01/02；MainViewController、ApplicationContext、TGSplitView 已实查；所有转场叠加仍待补 |
| 会话列表 | 长名字、摘要、草稿、时间、未读数字、静音、置顶、悬停、选中、右键、拖动排序、清历史/删除 | MAC-10 行高已实查；ChatListRowView 的字体/边距/数字 badge、菜单各态待实查 |
| 全局搜索及聊天内搜索 | 空输入、建议、加载、无结果、结果选择、跳转及清除、键盘上下/回车/退出 | `ChatListUI/SearchController.swift`、`ChatUI/ChatSearchHeader.swift` 已定位并初读；未完成布局/动画提取 |
| 聊天顶栏 | 头像/名字、在线/输入中、验证标记、搜索、通话、更多、标题截断 | MAC-11；状态换行、不同文字长度、窄窗口待测 |
| 文本气泡及分组 | 短文本、多行、长 URL、RTL、连续作者、时间/日期分隔、失败、发送中、回执、已编辑、撤回 | MAC-05/12；分组边界、字体/颜色、最大宽度、气泡曲率与尾角待补 |
| 新消息与历史滚动 | 首开定位、底部跟随、上翻锚点、加载旧页、未读分隔、回到底部、引用定位高亮 | ChatController 与 row geometry 初读；历史锚点恢复和 highlight 的完整动效仍待提取 |
| 输入框及快捷键 | focus、IME 组合、Enter/Shift+Enter、草稿、选择复制、粘贴图片、清空、失焦再回来 | MAC-03/04；`InputView/ChatInputTextView.swift:1342-1374` 已确认 Enter 先排除 marked text；其他快捷键需逐动作实测 |
| 附件面板与输入模式 | 展开/收起、鼠标移出、ESC、发送/语音切换、文本多行时尺寸变化、重复快速反向操作 | MAC-03/04；ChatInputMenuView、AttachView 已初读；附件菜单全部展开轨迹待测 |
| 回复、编辑、转发、多选 | 引用跳转、取消、编辑恢复、发送完成、会话切换、原消息已撤回/删除 | MAC-06；批量选择、转发选择器及右键菜单仅定位，未完整实查 |
| 文件及媒体发送确认 | 单文件、多文件、相册排序、作为文件/媒体、caption、取消、拖出、进度/失败/重试 | MAC-07；PreviewSenderController 初读；每种布局及多选动画待补 |
| 图片、视频、音频/语音、GIF | 占位、下载/暂停/取消、解码失败、播放控制、比例/长图、横竖屏、全屏、上一张/下一张 | MAC-08；GalleryUI 各 item、ChatMediaUI 已定位；还缺全部控制条和播放器动态细节 |
| 媒体预览/裁剪/导出 | 缩略图转场、缩放、拖动、旋转、裁剪、保存/取消、打开原位置、关闭回聊天 | MAC-08/09；焦点恢复和滚动锚点需要实际观察 |
| 网页预览/URL | 编辑链接、移除预览、多个链接、加载/失败、默认浏览器打开、预览图 | `WPArticleLayout.swift`、`WPContentView.swift` 初读；MAC-06 关闭语义已查；完整卡片布局待补。MsgAT 发送端生成及加密规则另按安全方案 |
| 表情包面板与本地导入 | 最近、收藏、搜索、分类、预览、GIF/动画、选中发送、导入失败/部分导入 | EntertainmentUI/EntertainmentViewController、StickersViewController 已定位；微信/QQ 导入为 MsgAT 自有流程，不能假称 Telegram 原生已提供 |
| 个人资料、联系人和验证 | 资料查看、展开媒体、好友请求/接受/拒绝、改备注、清历史、删除/拉黑、核验 | PeerInfoController 已定位；MsgAT 无手机号码/账号同步，身份资料字段必须独立映射 |
| 设置 | 列表分组、开关、导航返回、搜索、主题/字号、通知、下载存储、隐私、设备、网络/代理、Danger Zone | GeneralSettingsViewController 已初读；其余 SettingsUI、CallSettingsUI、DataUsageUI、Privacy 相关包仅定位 |
| 通知、菜单和弹窗 | 菜单定位、hover/选中、键盘、确认/取消、失焦、通知权限、错误提示 | TGUIKit/ContextMenu、Modal 已定位；不能以原生控件存在代替完整交互对照 |
| 一对一通话 | 呼出/呼入、接听/拒绝、连接/重连/失败、音频/视频、麦克风/扬声器/设备、权限、结束 | MAC-13；PrivateCallScreen / CallVideoLayer 已实查部分，CallsUI / CallSettingsUI 还需扩展 |
| 系统适配/无障碍 | Retina、窗口缩放、动态字体、多语言、键盘完整操作、VoiceOver、减少动态效果、高对比度 | 若干控件有 accessibility label/press 接口；尚未完成源路径和实机全覆盖 |

## 需要保留的 MsgAT 差异

1. 身份仍由不可迁移根种子代表；没有 Telegram 手机号登录、云端聊天解密或多设备身份迁移。借鉴登录/解锁画面的视觉和导航，不照搬账号模型。
2. 三端回执继续采用用户规定：灰色单勾=已发送未接收，绿色单勾=已接收未读，绿色双勾=已读。Telegram 的勾图形/动画只能作为视觉参考，不能改变已确定语义。
3. 桌面拖放/粘贴文件发送必须先确认。官方源码的 Shift-drop 直接发送分支不能带入 MsgAT。
4. Enter 发送、Shift+Enter 换行及 IME 组合输入规则保持用户要求；从 Telegram 的 marked-text 保护和焦点恢复学习具体行为。
5. URL 用默认浏览器打开；网页预览只按 MsgAT 发送端生成、收件端不自动访问远程页面的安全链路提供。UI 可以接近 Telegram，不能把它的网络请求模型直接搬进来。
6. 群聊、频道、商业/广告、付费、机器人/AI、故事和多账号等非本轮批准功能，不因参考源码存在就加入制作范围；这类源分支不拿来冒充 MsgAT 必须复制的 UI。

## 后续实际研究步骤

1. 先解决原生 Mac 正式参考包的资料隔离，记录包签名、profile 实际路径与测试账号使用方式；不操作真实 Telegram 账号资料。
2. 对每个单聊页面使用一致的合成内容，固定窗口尺寸、backing scale、主题、语言、字号，记录完整页面和关键部位；建立消息长度、长 URL、回复/编辑、文件、图片/视频等代表状态。
3. 录制动画开始、稳定、结束和反向中断过程；测量轨迹及耗时，逐项确认或修正 MAC-01…13 的源码候选参数，补齐当前只定位的模块。
4. 同时记录点击热区、键盘/IME、焦点、滚动锚点、缩放/DPI、VoiceOver 与减少动态效果等实际行为；每项保留已研究/已实现/已对照三个独立状态。
5. 研究覆盖完成后再以 SwiftUI 原生组件和必要的 AppKit 桥接制作 MsgAT；用同内容、同尺寸、同状态的真实应用画面对照。不写无用测试代码，不虚构总体相似度百分比。

本文件没有执行 Telegram 客户端、没有读取真实 Telegram 数据、没有改动 MsgAT 产品代码，也没有运行任何测试。

[M01]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ApiCredentials/Sources/ApiCredentials/Config.swift#L20-L27
[M02]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ApiCredentials/Sources/ApiCredentials/Configuration.swift#L8-L13
[M03]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/Telegram-Mac/app/AppDelegate.swift#L352-L360
[M04]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ApiCredentials/Sources/ApiCredentials/Config.swift#L30-L43
[M05]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/Telegram-Mac/app/ApplicationContext.swift#L1130-L1206
[M06]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/Telegram-Mac/app/ApplicationContext.swift#L391-L402
[M07]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/Telegram-Mac/app/AppDelegate.swift#L333-L337
[M08]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/TGUIKit/Sources/TGSplitView.swift#L258-L290
[M09]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatInputView.swift#L293-L315
[M10]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatInputView.swift#L1050-L1067
[M11]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatInputView.swift#L2014-L2035
[M12]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatInputSendButton.swift#L110-L123
[M13]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatInputSendButton.swift#L427-L466
[M14]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatInputSendButton.swift#L491-L543
[M15]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatRowItem.swift#L3344-L3354
[M16]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatRowItem.swift#L3421-L3488
[M17]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatMessageItem.swift#L640-L663
[M18]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatRowGeometry.swift#L59-L87
[M19]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatInputAccessory.swift#L53-L65
[M20]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatInputAccessory.swift#L334-L351
[M21]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatInputView.swift#L209-L213
[M22]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatController.swift#L11932-L12017
[M23]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/PreviewSenderUI/Sources/PreviewSenderUI/PreviewSenderController.swift#L2958-L2963
[M24]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/PreviewSenderUI/Sources/PreviewSenderUI/PreviewSenderController.swift#L247-L253
[M25]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/PreviewSenderUI/Sources/PreviewSenderUI/PreviewSenderController.swift#L1470-L1493
[M26]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/GalleryUI/Sources/GalleryUI/GalleryPageController.swift#L1327-L1447
[M27]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/GalleryUI/Sources/GalleryUI/GalleryPageController.swift#L1455-L1513
[M28]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/GalleryUI/Sources/GalleryUI/GalleryViewer.swift#L1661-L1753
[M29]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/AvatarEditorUI/Sources/AvatarEditorUI/EditImageModalController.swift#L827-L850
[M30]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/AvatarEditorUI/Sources/AvatarEditorUI/EditImageModalController.swift#L871-L885
[M31]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatListUI/Sources/ChatListUI/ChatListRowItem.swift#L2638-L2656
[M32]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatTitleBarView.swift#L62-L74
[M33]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatTitleBarView.swift#L470-L504
[M34]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatRightView.swift#L214-L223
[M35]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatRightView.swift#L330-L342
[M36]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/PrivateCallScreen/Sources/PeerCallScreenView.swift#L370-L394
[M37]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/PrivateCallScreen/Sources/PeerCallScreenView.swift#L714-L725
[M38]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/CallVideoLayer/Sources/CallWindowTransition.swift#L77-L145
[M39]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/CallVideoLayer/Sources/CallWindowTransition.swift#L300-L312
[M40]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/PreviewSenderUI/Sources/PreviewSenderUI/PreviewSenderController.swift#L478-L490
[M41]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/PreviewSenderUI/Sources/PreviewSenderUI/PreviewSenderController.swift#L578-L585
[M42]: https://github.com/TelegramMessenger/telegram-macos/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatRowItem.swift#L518-L524
