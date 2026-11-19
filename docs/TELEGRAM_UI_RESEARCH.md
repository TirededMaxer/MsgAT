# Telegram UI 研究索引

研究从 2026-11-09 开始。已取得三端官方包、固定源码和部分视频参考，已据此开始编写会话列表、聊天区、附件和输入区。完整的逐屏与动画对照仍在进行。功能进度见 [模块完成表](REBUILD_MODULES.md)。

## 参考材料与工具选择

按用户最新要求，以三端官方安装包、公开源码和 YouTube 视频共同研究，视频只从 YouTube 找，并优先筛选包含完整操作的使用教程。浏览视频使用 Chrome，保持播放器静音。用户不需要为本轮研究提供 Telegram 账号；源码参数、安装包事实和视频观察分别记录，材料不足的状态继续补充参考，不冒充已观察。

正式包从 [Telegram 官方应用及源码入口](https://telegram.org/apps)指向的下载地址取得。第三方研究包、提取资源和源码快照只放在被 Git 忽略的 `.build-tools/telegram-ui/`，不进入 MsgAT 源码或发布安装包。不复制 Telegram 标识、素材或大段 GPL 实现到产品。

## 已核对的三端包

| 平台 | 官方下载入口 | 实际元信息与检查 | SHA-256 |
| --- | --- | --- | --- |
| Android | [官方 APK](https://telegram.org/dl/android/apk) | `org.telegram.messenger.web`，13.2.1 / 72919；min SDK23，target/compile36；`apksigner verify` 通过；79,564,232 字节 | `2b73067f429a5cc20cf060e84c9296a18a04b77f1aa02739fa6457c6b5a508d2` |
| macOS | [原生 Mac DMG](https://telegram.org/dl/macos) | `ru.keepcoder.Telegram`，13.2.0 / 283467；只读挂载；`codesign --verify --deep --strict` 通过；Developer ID：TELEGRAM MESSENGER LLP，Team `6N38VWS5BX`；169,723,549 字节 | `3f732bd3e0c082b5ec07e1cf59a655e2d1738968ad274c8bc806cceed29f1c88` |
| Windows | [官方 x64 Portable](https://telegram.org/dl/desktop/win64_portable) | ZIP 84,898,022 字节；提取 EXE 为 x86_64，PE version resource 的 File/ProductVersion 均为7.4.1.0；尚未做 Windows Authenticode 系统验证 | `6c1191b770d49a2b8f6b0d78fd8d4402e6d7098513e7213a2649990471238392` |

Android 签名证书 SHA-256：`49c1522548ebacd46ce322b6fd47f6092bb745d0f88082145caf35e14dcc38e1`。Windows 提取 EXE SHA-256：`a29e55bdde9923187f1de944fafb6a1ce06dc4ed746daec2f3080b1ca97ffaf6`。签名/包内版本事实与源码可复现构建证明不同，本轮没有执行 Telegram 可复现构建。

REA 静态包清单已完成并导出本地 `rea-evidence.json`。其 occurrence 统计包含根容器：Android7309个文件 occurrence；Mac904个 occurrence（文件736、目录162、符号链接6）；Windows7个 occurrence（文件3、目录4）。Windows ZIP直接成员实际为4个目录和2个文件，另有根 ZIP 自身的 occurrence。这些数量仅证明容器清单覆盖，不证明 UI 行为。

| 材料 | REA 保留证据 |
| --- | --- |
| Android 包检查 | `ev_c98581423dba5a78255f94c014f3fcc623539201ea01ea0aad7897735d1e1fc8` |
| Mac DMG 检查 | `ev_71445d26618e41695672afac35b5423f088cde6b5889eaa3cd3b650f949b52c1` |
| Windows ZIP 检查 | `ev_6271a0b5d2f406d548e73b0abb3bba9495e7031c2016c25e9dbd777dea9b224d` |
| Windows PE 资源检查 | `ev_2c9eb9537cb0485f83b8da7391bf080222d473f00e3e78d6956f79a612509e97` |

Android ZIP 存在大小写折叠后重名，不能全量提取到不区分大小写的目标并假装完整。Mac 源码读取发现正式包启动包含共享资料路径/迁移逻辑，尚无核实可用的运行时隔离目录参数，本轮未启动 Mac 正式包。

## 首轮源码研究

| 平台 | 固定源码 | 已实读内容 | 记录 |
| --- | --- | --- | --- |
| Android | `7be672ead283d022812d75c729d3a04dd4c2a2c6` | 13 条布局/动效路径；23 个范围模块索引。源码7291×10+afat9对应包72919，发行名与版本编码一致 | [Android 记录](TELEGRAM_ANDROID_UI_RESEARCH.md) |
| 原生 macOS | `561169337e838c3281f7cfe24652757a5b522df9` | 13 条布局/动效路径；源码 build283357 与包283467不同，参数仍需结合正式包资源/视频核对 | [Mac 记录](TELEGRAM_MACOS_UI_RESEARCH.md) |
| Windows Desktop | v7.4.1 / `157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19`；lib_ui `42c6f1e88db81e6d9e8006f3e7b1c3ba39f9cac4` | 15 条布局/动效路径；版本名与 EXE资源一致，未宣称字节可复现一致 | [Windows 记录](TELEGRAM_WINDOWS_UI_RESEARCH.md) |

已解析包内矢量动画元数据：Mac407个、Android334个。记录完整时间轴的帧率、入出帧、尺寸和散列，仅作资源事实；代码选择的片段、播放速率、循环与过渡组合另行核对。例如 Mac `video_to_mic.tgs` 的完整时间轴250ms，并不能单独证明所有发送按钮切换都持续250ms。

## YouTube 视频研究状态

用户已明确允许用使用教程观察 UI 行为。每个入选视频记录：链接、作者、发布日期、可确认的客户端/版本、画面分辨率、对应页面、实际观察时间点、播放速度、剪辑/变速情况，以及能与当前包对应的依据。标题中的“2026”不能单独证明录制年份或客户端版本。

已在 Chrome 静音检查 [Android experimental 12.3.0 新闻视频](https://www.youtube.com/watch?v=60K2O9zkFrg)：页面说明明确为旧 experimental12.3.0，实际观察到约00:46段为评论画面。它不作为当前13.2.1完整界面或动画的主要依据。教程候选已找到，但尚未完成观看和逐项记录，不能算视频研究通过。

### 2026-11-11 补充观察

| 来源 | 实际观察 | 使用边界 |
| --- | --- | --- |
| [Tips & Tricks 媒体教程](https://www.youtube.com/watch?v=Z7Hbjf9M2OY)，2026-08-30 | 00:34 可见安卓聊天底部回形针，头像和会话名称位于顶栏。00:41 可见底部附件面板，顶部有拖动把手，中间为三列预览，底部独立显示 Gallery、File、Location 等分类。00:57 和 01:03 仍停留在相册网格 | 手机外拍，版本和设备密度未确认。示例为群组，只采用通用面板布局。采样未看到选中后的确认或发送结果 |
| [How Tap 文件教程](https://www.youtube.com/watch?v=MZOTomdaDZM)，2026-06-28 | 00:22、00:44、01:03 均显示安卓桌面，字幕在讲解文件发送 | 不作为文件发送流程或 UI 的视觉依据 |
| [Valdic Gram 安卓界面演示](https://www.youtube.com/watch?v=k8ayuG9lIyI)，2026-02-20 | 01:28 会话列表用圆形头像、消息摘要、右对齐时间和徽标，新建会话按钮在底部导航上方。02:02 联系人搜索位于标题下，邀请好友和最近通话位于联系人列表上方。02:15 通话标签已开启，页面出现短暂的撤销提示 | Chrome 静音观看。深色模式手机外拍，具体客户端版本和设备密度未确认 |

官方 Android 源码补查确认，照片网格普通布局以三列开始，间隙计入网格跨度，权限提示或末尾占位可用整行。选中顺序由独立列表保存，取消一项后更新其余选中序号。附件分类切换到独立文件或联系人布局，照片预览又是独立的可返回页面。源码事实用于区分浏览、选中和确认三个状态。见 [网格跨度](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertPhotoLayout.java#L870-L905)、[选中顺序](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertPhotoLayout.java#L1763-L1799)、[分类切换](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java#L4901-L4946)。

### 发送确认中的继续添加、排序和退出

以下来自已固定版本的本地源码。录像中的具体过渡和焦点行为仍待对照。

| 平台 | 已读行为 | MsgAT 对照点 |
| --- | --- | --- |
| Android | 照片网格和已选预览是同一附件面板中的不同布局。取消选择时更新余下项目的编号。选中数量归零时收起说明输入，数量变化时更新发送按钮。返回键先处理搜索、当前分类和说明输入的浮层，再交还给外层。最终发送先验证说明，再交给发送代理。 | 从已选预览返回网格时保留顺序和滚动位置。发送、返回、关闭三种操作各自处理，并避免键盘浮层和附件面板同时退出。见 [布局切换](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java#L4900-L4912)、[计数更新](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java#L5972-L5990) 和 [返回路径](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java#L6993-L7011)。 |
| 原生 macOS | 已打开的发送确认接受继续拖入文件，并检查回调仍属于原操作。确认窗口检查输入法的 marked text。说明有变更时关闭会先询问，确认丢弃后才退出。发送与取消有不同的退场路径，成功发送时取消返回附件按钮的 morph。列表至少两项时启用拖动排序。 | 继续拖入只扩充本批。确认框关闭时，旧拖放回调失效。发送完成和取消的动效需要分别实现。见 [追加、Enter 和关闭](https://github.com/overtake/TelegramSwift/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/PreviewSenderUI/Sources/PreviewSenderUI/PreviewSenderController.swift#L1499-L1566) 和 [排序](https://github.com/overtake/TelegramSwift/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/PreviewSenderUI/Sources/PreviewSenderUI/PreviewSenderController.swift#L2225-L2230)。 |
| Windows Desktop | 发送确认禁用点外部关闭。确认框自身接受追加文件，按当前发送方式判定图片或文件区域。可见说明框优先获取焦点。发送确认时先应用预览块的排序和修改，正在准备时记录确认动作并等待准备结束。 | 外部文件追加和框内排序使用不同的拖放路径，保留现有说明和选项。点击发送后才开始消息提交。见 [确认关闭与追加](https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/boxes/send_files_box.cpp#L923-L989) 和 [焦点与发送](https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/boxes/send_files_box.cpp#L2810-L2883)。 |

MsgAT 的桌面发送确认已接入追加拖放。Windows 沿用确认框的文件预览和上限。Mac 在异步读取拖入项时保留文件顺序，并询问是否放弃已输入的附件说明。两端都需要在统一实机阶段检查回调、键盘焦点、取消和连续拖入。

### 普通会话列表行

Android 参考的双行会话高度为 70dp，头像 52dp，默认起点为 11dp，预览正文从 76dp 开始。MsgAT 沿用这些尺寸，给右侧时间留出独立空间，分隔线从文字列开始。 [默认高度与间距](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java#L166-L169) [双行头像和预览位置](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java#L2387-L2407)

macOS 普通会话用 50pt 圆头像、10pt 内边距和 70pt 行高，摘要最多两行。Windows 按 62 DIP 行高、46 DIP 头像及 13 DIP 时间字号调整。两端保留现有系统列表控件和右侧完整未读数字。 [macOS 头像](https://github.com/overtake/TelegramSwift/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatListUI/Sources/ChatListUI/ChatListRowView.swift#L1546) [macOS 摘要](https://github.com/overtake/TelegramSwift/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatListUI/Sources/ChatListUI/ChatListRowItem.swift#L1167) [Windows 行样式](https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/dialogs/dialogs.style#L78-L103)

### 回复中的片段引用

Android 参考源码在消息预览内记录选区，收起预览后把片段更新到输入区。MsgAT 三端从消息菜单进入气泡内的原生选区，确认后把片段带回输入区。跳转后在原消息里高亮唯一匹配的可见片段，找不到或存在多个匹配时保留整条消息的定位提示。选区手势和焦点待实机对照。 [Telegram Android 选区交接](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java#L11515-L11544)

### 回复和编辑的切换

Android 的输入栏保留滑动进出，切换消息或切换回复与编辑时单独过渡。退出回复后等原窗口重新获得输入焦点，再恢复键盘。macOS 根据消息和模式切换附件栏并跟随系统减少动态效果设置。Windows 采用单个 49 DIP 附件栏，在回复和编辑之间原位切换图标、说明和关闭动作，避免重复调整聊天视口。 [Android 顶栏显示及键盘](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L6419-L6460) [Mac 附件栏位置过渡](https://github.com/overtake/TelegramSwift/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatInputView.swift#L468-L476) [Windows 附件栏显隐](https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/history/view/controls/history_view_compose_controls.cpp#L908-L922)

### 联系人与资料

Android 的资料动作分为聊天、语音、视频和通知，身份字段单独列在资料区。MsgAT 按照该层级布置头像、常用动作、公开 ID 和通知选项。联系人目录打开独立资料页，安全码页可以返回原资料。 [Telegram Android 资料动作](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java#L3867-L3910) [资料字段的布局顺序](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java#L10959-L10993)

Mac 参考源码按当前页面状态更新导航栏。Windows 参考把个人资料封面放在动作和设置分组之前。MsgAT 在两个桌面端保留原生双栏目录，右侧将资料、可用操作和验证分组，安全码使用单独的返回路径。窗口尺寸、过渡与键盘操作待统一实机对照。 [Telegram Mac 资料导航](https://github.com/overtake/TelegramSwift/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/PeerInfoUI/Sources/PeerInfoUI/PeerInfoController.swift#L545-L582) [Telegram Desktop 资料封面和分组](https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/settings/sections/settings_main.cpp#L726-L756)

### 输入区贴纸面板

Android 参考的贴纸网格以五列起步，短按选中发送，长按由预览层处理。MsgAT 在输入区下方使用五列网格、可展开的面板和独立预览。macOS 将搜索与本地分组放进输入栏上方的 SwiftUI 面板。Windows 按 345 DIP 宽度上限摆放，类别切换采用 200 ms 的淡入与横移，并恢复滚动位置。三个面板使用 MsgAT 的加密本地贴纸和已选发送选项。实际手势、弹出高度与动效待统一验收。 [Telegram Android 贴纸网格与点击](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/EmojiView.java#L2273-L2368) [Telegram Mac 本地分组](https://github.com/overtake/TelegramSwift/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/EntertainmentUI/Sources/EntertainmentUI/StickersViewController.swift#L420-L481) [Telegram Desktop 切换与滚动](https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/chat_helpers/tabbed_selector.cpp#L1083-L1120)

### 追随最新消息时的入场

Android 的聊天列表在底部跟随时将新增行从下方向静止位置移动，使用默认 250 ms 的列表动效。原生 macOS 给正在显示的新气泡按收发侧锚定缩放和淡入，参考时长 350 ms。Telegram Desktop 对新增项使用 150 ms 的圆形缓出，主动发送时可使用独立的输入框过渡。 [Android 新增行](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/recyclerview/ChatListItemAnimator.java#L327-L375) [macOS 气泡入场](https://github.com/overtake/TelegramSwift/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/ChatUI/Sources/ChatUI/ChatRowView.swift#L3021-L3032) [Windows 新增项](https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/history/history_widget.cpp#L8980-L9003) [Windows 时长](https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/ui/chat/chat.style#L58)

MsgAT 为正在跟看最新消息时追加的小批消息接入相应原生动效。日期及未读分隔保持原位，阅读定位和回执更新沿用既有数据流程。初次加载、历史翻页、定位与回执变更不触发入场。实际帧序列与输入框到消息气泡的衔接仍待对照。

### 2026-11-18 补充实际画面

以下片段在 Chrome 中静音观察。记录可见状态和动作顺序，客户端的精确版本、原始录制分辨率及转场时长仍待确认。

| 平台与来源 | 抽查画面 | 用于 MsgAT 的交互要求 |
| --- | --- | --- |
| Windows · Smart Fixer，2025-08-25。[多图发送](https://www.youtube.com/watch?v=uy0Fk0xBpjw&t=37s) | 00:37 打开系统文件选择器，00:55 同时选中两张图，01:01 回到应用的发送确认，预览逐项显示编辑和删除，分组、压缩、说明及发送动作在预览下方。01:06 确认框已关闭，聊天中可见同组上下排列的两张照片的边缘。后段有画面放大剪辑，无法凭该片段测量转场。 | 保留系统多选到应用内二次确认的交接，按批次集中说明、分组和压缩。确认页应直接提供继续添加和逐项编辑。沿用 MsgAT 自有视觉与底部固定操作区。 |
| macOS · Foxtecc，2023-10-16。[Mac 安装演示](https://www.youtube.com/watch?v=X2UQtccewTs&t=232s) | 03:36 显示带原生标题栏的登录窗口。03:52 在窄窗深色模式中，设置首页仍显示搜索、主类别列表与底部导航。手机外拍且版本较早，未展示当前版本聊天双栏、附件或设置转场。 | 窄窗口优先保留设置搜索、可滚动类别与返回路径。沿用 MsgAT 的硬件身份和网络设置结构。登录画面没有可复用的 MsgAT 身份操作。 |
| Android · Sky Tech Studio。[文件夹分享教程](https://www.youtube.com/watch?v=tToNFDo53KE) | 01:03 至 01:16 的可用画面是设备文件管理器和移动文件流程。后段抽样没有取得足以确认 Telegram 应用内多选或发送过渡的独立画面。 | 本片段只用于辨别系统文件操作与应用内图库流程，应用内交互继续以上方 Android 实录与固定源码为参考。 |

Windows 录像中的“选择文件 → 确认选项 → 进入聊天”与固定源码里的确认布局、说明输入及发送分支相符。逐项移除、放弃修改、继续添加、读取失败和窗口焦点恢复仍分别按 MsgAT 的既有流程覆盖。 [确认区布局](https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/boxes/send_files_box.cpp#L2683-L2766) · [实际行为清单](V2_MEDIA_CONFIRMATION.md)

#### 呼叫控件与系统通知入口

Chrome 静音并暂停取样。以下时间为视频播放器的定位时间。录制版本、源分辨率和剪辑速度未确认，画面只用于确定可见状态与控件顺序。

| 平台与来源 | 可见状态 | MsgAT 对照 |
| --- | --- | --- |
| Windows · Shortcut Academy。[桌面视频呼出](https://www.youtube.com/watch?v=nKuENVuu8KA&t=109s) | 01:33 聊天顶栏有电话按钮。01:49 打开独立呼出窗口，可见视频、取消、语音三个操作。02:04 同一窗口显示等待和视频预览区，底部有停用视频、挂断和静音。02:19 仍显示等待。 | 呼出等待期间区分联系人、连接状态和媒体控件。MsgAT 顶栏已有独立的语音和视频入口，摄像头继续由既有通话设置控制。该演示没有提供接通或重连证据。 |
| Android · How To Fix Pro。[视频通话中的手机画面](https://www.youtube.com/watch?v=pPruF2Gy4Ig&t=76s) | 00:55 是桌面聊天及视频消息。01:16 画面切到带安卓系统导航的竖屏来电方或通话方录屏，视频覆盖主区域，底部可见镜头切换、停用视频、静音和结束。01:30 仍显示相同构图。 | 视频与主要操作在同一页，操作区保留底部系统安全空间。片段不足以确定呼入、接通、缩小或控件转场顺序。 |
| macOS · Digit Wise。[系统通知设置](https://www.youtube.com/watch?v=c5N-69FxFMQ&t=29s) | 00:29、00:53 都能看到系统设置「通知」中的应用详情。页面同时展示允许通知、显示位置、声音和预览选项。 | MsgAT 通知页提供系统设置入口，重新激活应用时重新读取授权和系统预览选项。预览正文仍仅在系统选择解锁时显示的条件下交给系统。 [Apple 通知设置说明](https://support.apple.com/en-ca/guide/mac-help/-mh40583/mac) |
| macOS · Aires Communication。[Mac 入门演示](https://www.youtube.com/watch?v=TKMnFBM_l5Q&t=133s) | 02:13、02:32 可见 Dock 上的 Telegram 菜单，窗口、隐藏、退出与系统的「在程序坞中保留」设置独立。ZenCut AI 的 [MacBook 指南](https://www.youtube.com/watch?v=QY9dhyqcP3E&t=54s) 在 00:54 只显示字幕卡。 | 保留 macOS 系统窗口和退出命令。以上片段未补足原生 Mac 的聊天动效或通话证据。 |

### 一对一通话与当前窗口

- Android 参考源码为语音和视频来电分别设置状态文字及接听区位置。第一次连接与已接通后的恢复分别显示，通话时长只在建立后出现。返回动作按相机预览、已展开内容、浮窗或关闭的顺序处理。 [呼入与连接状态](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/VoIPFragment.java#L1785-L1841) · [返回路径](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/VoIPFragment.java#L447-L475)
- 原生 macOS 参考源码使用独立通话窗口，状态改变时通过 200 ms 的弹簧布局过渡。结束时先处理全屏退出，再由所属窗口收起当前内容。完整通话窗口打开会主动获得焦点。MsgAT 的模态期间来电小窗按项目要求保留原编辑焦点，用户选择接听或拒接后再进入来电操作。 [状态切换与关闭](https://github.com/overtake/TelegramSwift/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/PrivateCallScreen/Sources/PeerCallScreen.swift#L395-L425) · [激活窗口](https://github.com/overtake/TelegramSwift/blob/561169337e838c3281f7cfe24652757a5b522df9/packages/PrivateCallScreen/Sources/PeerCallScreen.swift#L433-L458)
- Windows 参考源码按响铃、忙线及已连接状态切换操作。已接通时，关闭系统窗口只收起通话界面，挂断通过显式按钮执行。隐藏窗口中的状态即时更新。 [系统关闭处理](https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/calls/calls_panel.cpp#L1738-L1758) · [操作切换](https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/calls/calls_panel.cpp#L1809-L1844)

三段源码对应参考客户端行为。实际来电打断、关闭屏幕前的输入法状态、视频设备切换及恢复时的转场仍待录制。MsgAT 原型已加入附件说明期间的来电和重连情景，用于确认操作前后的内容及焦点。 [界面规格](V2_UI_SPEC.md)

## 后续覆盖标准

会话、聊天与发送确认的 MsgAT [界面规格和三端交互原型](V2_UI_SPEC.md)采用项目现有配色、术语和回执语义。原型用于记录布局与状态选择，三端真实界面及动效仍按下方覆盖范围对照。

逐屏清单须覆盖本次批准的所有 UI 范围：正常/空白/加载/失败/权限/离线/恢复/取消；浅深主题、字号、DPI/Insets、输入法、键盘/鼠标/触控与动画中断反向。对应状态分别标记“源码已读”“视频已观察”“MsgAT已实现”“MsgAT实际通过”。缺一个状态不能靠同页另一张截图补齐；不以单一相似度百分比代替逐项差异说明。

MsgAT 保留身份与安全规则、SwiftUI/.NET 原生实现、桌面默认双栏，以及拖入文件二次确认。聊天附件中的拍照和录像仅在 Android 提供。桌面端保留本地媒体选择、拖放、粘贴和发送确认；视频通话保留摄像头控制。Android 的头像裁剪、照片编辑和视频裁剪已接入独立安全区顶栏，尚待统一实机验收。其他界面继续按上面的参考覆盖标准补齐。
