# Telegram Android UI 研究记录 — 第一轮源码证据

研究日期：2026-11-09。状态：**源码与安装包身份已固定，关键源码路径已实读；运行/逐屏视觉与帧级动画研究尚未完成。不能据此开始宣称最终 UI 已一比一还原。**

本记录覆盖当前批准的单聊、联系人、媒体/文件、链接、本地表情、设置、隐私及一对一通话。群聊、故事、机器人、商业功能、付费和公开内容分发不加入本轮产品范围。Telegram 中存在这些分支，不代表 MsgAT 应新增它们。

## 1. 证据身份与边界

| 项目 | 记录 |
| --- | --- |
| 官方入口 | [Telegram 官方应用及源码目录](https://telegram.org/apps)、[Android 官方仓库](https://github.com/DrKLO/Telegram) |
| 源码基线 | commit `7be672ead283d022812d75c729d3a04dd4c2a2c6`，提交时间 `2026-11-07T15:29:10Z` |
| 源码版本字段 | `APP_VERSION_NAME=13.2.1`、`APP_VERSION_CODE=7291`；[gradle.properties:16](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/gradle.properties#L16-L17) |
| 官网 APK（由主代理实际读取） | 包名 `org.telegram.messenger.web`，versionName `13.2.1`，versionCode `72919`，minSDK 23，target/compileSDK 36 |
| APK SHA-256（主代理记录） | `2b73067f429a5cc20cf060e84c9296a18a04b77f1aa02739fa6457c6b5a508d2` |
| APK 签名证书 SHA-256（主代理记录） | `49c1522548ebacd46ce322b6fd47f6092bb745d0f88082145caf35e14dcc38e1` |
| 发行变体关系 | standalone 使用 `.web` 后缀，`afat` 的 ABI 编码为 9，输出编码是 `7291 × 10 + 9 = 72919`。见 [build.gradle:85](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj_AppStandalone/build.gradle#L85-L90)、[build.gradle:107](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj_AppStandalone/build.gradle#L107-L126)。这解释版本编码，不证明源码已完整复现 APK。 |
| APK 静态证据索引 | 主代理 REA inventory：`ev_c98581423dba5a78255f94c014f3fcc623539201ea01ea0aad7897735d1e1fc8`，7309 个归档条目；有大小写冲突名称，不在大小写不敏感的文件系统上直接全量提取 |
| 本轮源码下载 | 39 个聚焦文件，共 11,409,919 字节；下载文件 SHA-256 已记录，所有文件的 Git blob SHA-1 与该 commit 的完整、未截断 Git tree 一致 |
| 临时源码位置 | `.build-tools/telegram-ui/sources/android/`；`source-identity.json`、`downloaded-files.json` 与 `tree.json` 保留来源和哈希，均不提交或随产品分发 |

下列“源码确认”指已读到对应实现；“路径定位”只指官方提交中存在该文件；两者都不代表已在官方客户端观察到画面、触感或动画。时间单位为毫秒，尺寸为代码中的 dp 及对应配置单位，不冒充实机像素测量。本源码子任务未构建 Telegram、未写或运行测试、未安装它、未操作任何 Telegram 账号。

## 2. 先固定参考状态，再制作

逐屏实际研究时，每个记录应同时固定：APK 哈希、系统/API、设备型号、屏幕分辨率与密度、字体/显示缩放、主题、键盘与导航方式、刷新率、系统动画开关、Telegram 性能/动画设置、会话内容和当前状态。相同版本中可能存在 glass/compact 样式与性能分支，不能凭旧印象选一组截图，再混用另一个分支的尺寸。

已确认当前源码包含 glass 顶栏和输入“island”路径：顶栏标题/副标题在 glass 模式为 17.5 / 13.5，对照普通构造路径的 18 / 14；输入基础控件高度 `DEFAULT_HEIGHT=44`。见 [ChatAvatarContainer.java:280](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java#L280-L312)、[ChatAvatarContainer.java:760](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java#L760-L775)、[ChatActivityEnterView.java:6563](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L6563-L6569)。这些是分支参数；最终参考分支仍须用该 APK 的实际界面确定。

正文绘制字号来自 `SharedConfig.fontSize`，代码块和时间字号会随它变化；不能把所有文本直接写成固定 16sp。见 [Theme.java:8130](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java#L8130-L8149)、[Theme.java:8497](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java#L8497-L8515)。

## 3. 已实查的布局与状态规则

| 领域 | 源码确认内容及对制作的约束 | 固定源码证据 |
| --- | --- | --- |
| 气泡宽高 | 普通文字路径使用实际 `textWidth` 和额外横向内容量形成背景宽度；高度包含 `textHeight`、19.5dp 的该路径附加量和名字偏移。时间、链接卡片、相册、引用、转发、反应等有独立分支。不能用一个固定最小宽/高替代自然测量，也不能把某一条件下的附加量当全局 padding。 | [ChatMessageCell.java:7201](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java#L7201-L7215)、[ChatMessageCell.java:7222](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java#L7222-L7275) |
| 连续消息和媒体分组 | `pinnedTop/pinnedBottom` 与相册位置标记共同决定连接边、圆角/尾部和顶部/底部空间。需要实拍同作者连续消息、换作者、跨日期、回复、单图、组合媒体等，不能只调一条短消息。 | [ChatMessageCell.java:6325](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java#L6325-L6368) |
| 输入区状态 | 非空内容、待发音频/视频及强制发送状态切换发送入口；录音中和编辑中不走普通入口刷新。暂停时禁用相应动画。输入附带按钮位置还取决于 caption 和 RTL；不是只有“加号变发送”两个图标。 | [ChatActivityEnterView.java:8023](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L8023-L8040)、[ChatActivityEnterView.java:8222](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L8222-L8255)、[ChatActivityEnterView.java:15827](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L15827-L15850) |
| 回复/编辑顶部栏 | 显示需协调键盘和 popup，尚未展开键盘时先等待或由键盘过渡触发；隐藏会取消待显示任务。动画改变实际输入岛总高度并带动布局。 | [ChatActivityEnterView.java:6423](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L6423-L6453)、[ChatActivityEnterView.java:6477](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L6477-L6491)、[ChatActivityEnterView.java:15897](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L15897-L15921) |
| 附件层与聊天 IME 所有权 | 附件 sheet 显示或处于特定发送退场期间，聊天底层过滤 IME Insets，避免同一次键盘变化同时移动聊天与附件两层。左右安全区、搜索容器、列表 padding 和可见 IME 状态集中更新。 | [ChatActivity.java:9086](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java#L9086-L9131) |
| 媒体查看器 | 用来源位置、裁剪进度、背景和内容 alpha 共同进出；来源单元可见性、播放资源和 idle 回调在结束阶段恢复。原地缩放、视频、PiP、头像裁剪有不同分支，不能把任一 200/250ms 套在全部媒体转场。 | [PhotoViewer.java:17807](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java#L17807-L17860)、[PhotoViewer.java:18450](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java#L18450-L18508)、[PhotoViewer.java:18548](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java#L18548-L18585) |
| 安全区与裁剪 | 聊天使用窗口 Insets holder/provider；媒体根布局扣除 bottom Insets 并单独测量内容；裁剪内容回正基于边界、旋转、缩放计算。应借鉴所有权和布局方法，不能照搬当前 MsgAT 的顶部重叠。用户要求 MsgAT 裁剪重叠留在 UI 阶段修复，本轮不改它。 | [ChatActivity.java:9086](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java#L9086-L9131)、[PhotoViewer.java:23924](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java#L23924-L23947)、[CropView.java:674](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/Crop/CropView.java#L674-L777) |
| 一对一通话 | 扬声器、视频、静音、结束分别为独立控件；有对应系统 Insets 的窗口监听，视频与浮窗、接听和结束等状态还需继续实查。 | [VoIPFragment.java:425](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/VoIPFragment.java#L425-L427)、[VoIPFragment.java:1062](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/VoIPFragment.java#L1062-L1094) |

浅色默认色仅作为当前代码锚点：接收背景 `#FFFFFF`、发送背景 `#EFFFDE`、正文 `#000000`、链接 `#2678B6`、发送勾 `#5DB050`、失败 `#DB3535`。compact/glass 的轮廓、阴影、渐变和运行主题会进一步改变观感；这不是最终全主题 token 表。见 [ThemeColors.java:306](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ThemeColors.java#L306-L323)、[ThemeColors.java:350](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ThemeColors.java#L350-L391)。MsgAT 已批准的灰单勾/绿单勾/绿双勾语义继续由自己的端到端状态定义，不能照抄 Telegram 的后台回执语义。

## 4. 已实查的关键动画路径

这些是源码参数，不是录屏测得的端到端帧时序。制作时必须同时保留触发、状态所有权、取消、重入和结束清理；只抄时长会得到错误交互。

### A01 文字从输入区进入消息单元

`TextMessageEnterTransition` 外层 ValueAnimator 为线性 0→1、250ms，记录输入文字与目标消息坐标，期间协调编辑框和消息显示。绘制时 Y/形状进度使用聊天列表专用曲线，X 进度组合 `EASE_OUT_QUINT` 与 `EASE_OUT`，所以不是整体线性位移；结束恢复编辑框/回复文本可见性并解除通知锁。

证据：[TextMessageEnterTransition.java:347](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/TextMessageEnterTransition.java#L347-L405)、[TextMessageEnterTransition.java:450](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/TextMessageEnterTransition.java#L450-L478)。

### A02 列表插入与布局变化

普通已读到的 add 路径使用 250ms，alpha 0→1；允许缩放时从 0.9→1。问候贴纸有单独 350ms Overshoot 分支。Move/Change 的覆盖返回 250ms，不能推出所有 remove 都是 250ms。取消回调恢复 alpha，结束清理 scale/translation 与动画集合，避免回收后的行残留半透明。

证据：[ChatListItemAnimator.java:46](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/recyclerview/ChatListItemAnimator.java#L46-L74)、[ChatListItemAnimator.java:1493](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/recyclerview/ChatListItemAnimator.java#L1493-L1542)、[ChatListItemAnimator.java:1630](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/recyclerview/ChatListItemAnimator.java#L1630-L1637)。

### A03 回复顶部栏与输入高度

两者共用 250ms 和列表曲线。顶部栏通过可见系数从输入区高度内展开，输入总高度包含顶部栏实际测量高度。等待键盘的后备任务延迟设为 200ms，实际键盘过渡开始可提前触发；这不是实际渲染时延的上限。反向更新 BoolAnimator 时 FactorAnimator 取消旧动画并从当前 factor 到新目标；系统关闭动画时 duration 归零。

证据：[ChatActivityEnterView.java:6423](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L6423-L6453)、[ChatActivityEnterView.java:6477](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L6477-L6491)、[ChatActivityEnterView.java:15862](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L15862-L15921)、[BoolAnimator.java:102](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/me/vkryl/android/animator/BoolAnimator.java#L102-L128)、[FactorAnimator.java:170](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/me/vkryl/android/animator/FactorAnimator.java#L170-L207)。

### A04 输入为空/有文字的控制切换

有文字等条件进入发送态的该路径为 220ms、`EASE_OUT_QUINT`，麦克风等旧入口缩至 0.1 并淡出。清空后恢复录音入口的该路径为 150ms；附件区有单独 100ms 动画。切换前取消 runningAnimation / runningAnimation2，结束回调验证动画对象是否仍为当前对象。恢复录音的本地块未显式设置插值器，不能无依据标成同一 quint 曲线。

证据：[ChatActivityEnterView.java:8222](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L8222-L8255)、[ChatActivityEnterView.java:8321](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L8321-L8385)、[ChatActivityEnterView.java:8715](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L8715-L8785)。

### A05 附件图标在输入区域间切换

caption、RTL、录音和附带输入状态决定位置与可见性。独立路径为 320ms、`EASE_OUT_QUINT`；alpha 目标 0/1，scale 目标 0.5/1；开始前取消旧 attachButtonAnimator。

证据：[ChatActivityEnterView.java:15827](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L15827-L15850)。

### A06 附件 sheet 打开

必须拆成三部分：容器从其测量高度沿 Y 进入，使用 SpringAnimation，阻尼比 0.75、刚度 350；按钮进度动画 400ms、延迟 20ms；背景遮罩 400ms、延迟 20ms、openInterpolator。基础 BottomSheet 的 openInterpolator 为 `EASE_OUT_QUINT`。不能把整页误实现成固定 400ms ease-out 位移。取消入口会一并取消 sheet、spring 和按钮动画；完成阶段解除锁和硬件层。

证据：[ChatAttachAlert.java:5330](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java#L5330-L5428)、[BottomSheet.java:211](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/BottomSheet.java#L211-L217)。

### A07 附件类型切换

常规类型切换先让当前页向下移动 78dp、180ms、`DEFAULT`，再用下一页 spring 回到 Y=0（阻尼比 0.75、刚度 500）。正在切换/评论条动画期间拒绝重入；点当前类型执行滚到顶部；结束时调用两页的 onHidden/onShown、替换 currentAttachLayout。照片预览横向切换是另一分支，仍需单独研究。

证据：[ChatAttachAlert.java:4608](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java#L4608-L4623)、[ChatAttachAlert.java:4655](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java#L4655-L4745)。

### A08 表情/贴纸面板展开与收起

两个方向均 300ms、`DEFAULT`，输入区和面板位移与箭头进度同步。高度按可用窗口减状态栏、导航栏、ActionBar、输入控件和 6dp 计算。键盘过渡中不启动冲突动画；开始前取消旧展开动画；结束恢复层类型和原始高度、清除 scrim，并处理键盘已显示的情况。

证据：[ChatActivityEnterView.java:13939](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L13939-L14089)。这里只研究本地贴纸/GIF 面板行为，不把 Telegram 云商店和账号目录带入 MsgAT。

### A09 会话列表进入/退出搜索

显示 200ms，关闭 180ms，曲线 `EASE_OUT`；退出另有 20ms 延迟。源码包含 alpha、平移以及非 budget 条件下的 scale；列表与搜索层在结束阶段切换可见性，清除过滤条件，恢复布局/键盘调整方式。此轮未完成搜索结果输入、下载筛选和空/错误状态的运行对照。

证据：[DialogsActivity.java:7495](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java#L7495-L7558)。

### A10 媒体查看器进出

已读到的非全屏视频打开分支为 200ms，来源裁剪图、遮罩、内容、导航条同时改变；头像路径还会淡入裁剪视图。关闭到来源/退场的相关分支以及无来源缩小淡出的分支也设置 200ms。视频已全屏时可直接完成打开，PiP/手势另有不同时间。该局部块没有显式设置全局统一插值器，需在实际 APK 中录制开闭与取消操作，不能把源码片段等同于所有媒体行为。

纵向关闭在媒体为原始比例时启动。纵向累计移动达到 30 dp，且超过横向移动两倍后，画面开始跟随手指。松手时，从启动点移动超过查看区域高度的六分之一才关闭，否则用默认 250 ms 回正。MsgAT 将这个手势接入单项、相册和共享媒体预览，图片放大时保留平移手势。 [手势方向与启动阈值](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java#L18163-L18189) [释放与回正](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java#L18302-L18316) [回正时长](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java#L18408-L18425)

证据：[PhotoViewer.java:17807](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java#L17807-L17862)、[PhotoViewer.java:18450](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java#L18450-L18508)、[PhotoViewer.java:18515](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java#L18515-L18585)。

### A11 裁剪内容边界回正

`fitContentInBounds` 计算旋转后的内容边界与目标裁剪边界，必要时缩放、平移，动画常规 200ms、fast 100ms；结束后可能再走 fast 校正。曲线来自 CropAreaView 的 `AccelerateDecelerateInterpolator`。视频缩略图浮现是另一个 250ms Overshoot(1.01) 动画，不能与裁剪回正混淆。

证据：[CropView.java:674](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/Crop/CropView.java#L674-L777)、[CropAreaView.java:80](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/Crop/CropAreaView.java#L80-L86)、[CropAreaView.java:222](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/Crop/CropAreaView.java#L222-L224)、[PhotoCropView.java:275](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/PhotoCropView.java#L275-L325)。

### A12 键盘与面板同步

回退方案为 250ms，使用列表专用曲线。API 30+ 的窗口 Insets 动画回调在代码条件满足且刷新率至少 90Hz 时改用系统 IME 动画进度，并在结束时停止过渡。不能对所有系统/键盘强行固定 250ms。聊天和附件必须只有对应可见输入层拥有 IME。

证据：[AdjustPanLayoutHelper.java:40](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/AdjustPanLayoutHelper.java#L40-L43)、[AdjustPanLayoutHelper.java:408](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/AdjustPanLayoutHelper.java#L408-L443)、[ChatActivity.java:9086](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java#L9086-L9131)。

### A13 通话底部控制入场

扬声器、视频、静音、结束四个按钮从 Y=100dp、scale=0 到正常位置和 scale=1，各持续 250ms，延迟分别 150、166、182、198ms。此块未显式指定曲线；接听、连接、视频切换、小窗、旋转与挂断路径不能用这个入场配置替代，仍需继续追踪及运行观察。

证据：[VoIPFragment.java:1062](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/VoIPFragment.java#L1062-L1094)。

共享曲线定义：`DEFAULT=(0.25,0.1,0.25,1)`、`EASE_OUT=(0,0,0.58,1)`、`EASE_OUT_QUINT=(0.23,1,0.32,1)`。聊天列表专用曲线为 `(0.19919472913616398,0.010644531250000006,0.27920937042459737,0.91025390625)`。名字中的 quint 不意味着可直接替换成任意平台同名 easing 函数；应按控制点还原。见 [CubicBezierInterpolator.java:9](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/CubicBezierInterpolator.java#L9-L22)、[ChatListItemAnimator.java:46](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/recyclerview/ChatListItemAnimator.java#L46-L48)。

## 5. 单聊 UI 全范围覆盖矩阵

状态定义：**源码部分实读**不等于整模块研究完成；**仅路径定位**不等于已读。以下全部模块的官方客户端实际运行/逐屏截图、动画帧记录及 MsgAT 同状态对照均待做。

| 页面/组件 | 必须覆盖的状态与交互 | 官方源路径 / 本轮证据状态 |
| --- | --- | --- |
| 会话列表与圆形未读数 | 空列表、最后消息摘要/草稿/失败、未读数变化、静音、置顶、归档、文件夹、长按多选、滑动、重连与离线；头像/文字/尾部标记的对齐 | [DialogsActivity.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java#L1)；源码部分实读；DialogCell 仅路径定位 |
| 全局搜索 | 展开/清空/退出、输入法、历史、联系人与消息结果、筛选、空/加载/失败、切换列表的滚动恢复 | [DialogsSearchAdapter.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Adapters/DialogsSearchAdapter.java#L1)；已下载；搜索转场在 DialogsActivity 部分实读 |
| 聊天顶栏 | 头像、长昵称、省略、状态/输入中、搜索和菜单、通话/视频入口、认证标志、返回与旋转 | [ChatAvatarContainer.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java#L1)；源码部分实读 |
| 消息流与气泡 | 短/长/多行、连续消息、日期分隔、引用/转发/编辑标记、选中、发送/失败、已读、删除后布局、滚动锚点、回到底部 | [ChatMessageCell.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java#L1)；源码部分实读；全分支/命中区域未完成 |
| 正文与选择 | 链接命中、多链接、富文本、代码、剧透、复制与文本选择手柄、长按菜单和无障碍 | [TextSelectionHelper.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Cells/TextSelectionHelper.java#L1)；仅路径定位；ChatMessageCell 只部分实读 |
| 回复、引用和编辑 | 进入/切换/退出、回复跳转、长引用、键盘开闭、编辑发送中/过期、取消和草稿恢复 | [ChatActivity.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java#L1)；源码部分实读；完整操作链未运行 |
| 输入区与发送控制 | 文字/语音模式、IME组合、多行、输入高度、草稿、撤销、发送失败重试/取消、静默/定时菜单及长按 | [ChatActivityEnterView.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java#L1)；源码部分实读；所有快捷/菜单状态未完成 |
| 附件选择与确认 | 展开/收起、照片/文件/音频、选择序号、相册、原图/压缩、备注、移除、取消、发送确认及权限拒绝 | [ChatAttachAlert.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlert.java#L1)；源码部分实读；PhotoLayout、DocumentLayout 已下载待深入 |
| 相册发送预览 | 多媒体排序、选中变化、回到网格、增删、备注/上方下方、取消和恢复 | [ChatAttachAlertPhotoLayoutPreview.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertPhotoLayoutPreview.java#L1)；仅路径定位 |
| 文件/媒体消息 | 缩略图、下载/上传进度、暂停/取消/失败重试、原文件名/尺寸、损坏、保存/分享与权限 | [ChatMessageCell.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java#L1)；源码部分实读；文件状态全表未完成 |
| 全屏媒体与裁剪 | 来源转场、缩放/双击/拖动、视频播放和拖动、旋转、裁剪/回正/取消、键盘、安全区、回原消息 | [PhotoViewer.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java#L1)；源码部分实读；全部手势/媒体分支未运行 |
| 链接预览 | 生成/移除/多个URL选择、上方下方、图/无图/失败、点域名/正文/预览不同命中区域 | [ChatMessageCell.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java#L1)；链接布局部分实读；发送侧预览链未完成 |
| 贴纸/GIF面板 | 本地网格、分组/最近/收藏、搜索、长按预览、拖动展开、选中发送、静态/动图与失败 | [EmojiView.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/EmojiView.java#L1)；已下载；展开动画在 EnterView 实读，面板内部待深入 |
| 联系人与资料 | 列表/搜索、加好友、资料、公开ID、验证、共享内容、拉黑/删除、头像预览和修改 | [ProfileActivity.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java#L1)；已下载；ContactsActivity 仅路径定位；MsgAT申请与硬件身份需独立设计 |
| 共享媒体/文件分类 | 媒体、文件、链接、音频、搜索、日期定位、多选、回原消息、空/加载/错误 | [SharedMediaLayout.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/SharedMediaLayout.java#L1)；仅路径定位 |
| 聊天内搜索和定位 | 搜索条、结果计数、上/下一个、日期选择、回复/置顶/未读跳转、原滚动位置恢复 | [ChatActivity.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java#L1)；路径定位与局部布局实读；完整搜索链待深入 |
| 设置主列表与个人资料 | 滚动、各入口、返回焦点、头像/昵称、确认取消、超长文字、加载/失败、主题/语言/字号 | [SettingsActivity.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java#L1)；已下载，尚未逐项实读与运行 |
| 主题/语言/字号 | 浅/深、系统主题、字体调整、显示缩放、对比度、图标/文字色和动态效果开关 | [ThemeActivity.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ThemeActivity.java#L1)；仅路径定位；Theme/ThemeColors 部分实读 |
| 隐私与锁定 | 已读/在线/输入状态控制、消息保留/自毁、应用锁、通知内容、自动锁/解锁、拒绝/不可用 | [PrivacySettingsActivity.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/PrivacySettingsActivity.java#L1)；仅路径定位；MsgAT根身份/Reset流程按自身安全协议 |
| 网络/存储/通知 | 设置滚动、网络字段、连接/失败、自动下载、缓存用量和清理确认、通知隐私、后台权限 | [DataSettingsActivity.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/DataSettingsActivity.java#L1)；仅路径定位；CacheControlActivity/NotificationsSettingsActivity 亦仅定位 |
| 语音/视频通话 | 呼出/来电、确认接通、等待/重连/失败、静音、扬声器/设备、视频与摄像头切换、忙线拒接、结束记录 | [VoIPFragment.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/VoIPFragment.java#L1)；源码部分实读；连接态/小窗/挂断与设备切换未完成 |
| 扩展卡片 | 联系人卡片、位置/实时位置、权限拒绝、明确开始/结束分享、预览与地图外链 | [ChatAttachAlertContactsLayout.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertContactsLayout.java#L1)；仅路径定位；LocationLayout/LocationActivity 同状态 |
| 通用窗口和系统适配 | popup/sheet/dialog层级、Back与预测返回、焦点/无障碍、横竖屏/平板、状态栏/刘海/导航栏/键盘、性能分支 | [ActionBarLayout.java:1](https://github.com/DrKLO/Telegram/blob/7be672ead283d022812d75c729d3a04dd4c2a2c6/TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java#L1)；源码部分实读；WindowInsetsStateHolder/InAppController 已下载待深入 |

补充路径均已通过该 commit 未截断的 tree 核对；本轮没有把只定位到的文件自动标为“已研究”。头像、联系人申请、服务器设置、硬件不可用错误、身份销毁及微信/QQ导入属于 MsgAT 特有或差异流程，须在借鉴 Telegram 组件规范后单独做完整状态设计。

## 6. 运行研究与最终 UI 验收仍缺什么

1. 在隔离、经用户授权的 Telegram 研究环境中准备合成单聊与媒体内容；如需要登录，由用户在官方客户端自行完成，研究不读取其真实聊天。当前没有已完成该环节的证据。
2. 按上表逐屏录制交互；同一入口重复触发、半程反向/取消、键盘交接、旋转/字体放大、低/高刷新率、减少动态效果都要实际操作。记录帧序列与真实渲染尺寸，不能把代码时长冒充实测。
3. 深入尚未读完的消息菜单、文件状态、链接发送预览、媒体手势、贴纸/GIF内部、设置全部子页、通话状态、小窗与退出恢复。补齐全部 light/dark/性能与屏幕分支，再冻结 Android UI 参数表。
4. MsgAT 开始 UI 实现后，使用同一合成内容、设备密度与页面状态对照官方参考：全屏布局、局部文字/图标、命中区域及完整动画过程分别记录差异。不得通过模糊、任意缩放截图或只挑静止帧掩盖差异，不宣称没有测量依据的相似百分比。
5. 官方源码、解包资源与应用包只作为临时研究材料。本轮没有把 Telegram 代码、图标、字体、logo 或贴纸复制进 MsgAT 产品；直接复用时另行核对相关许可与现有 AGPL 组件边界。本研究文档用自己的描述记录事实与参数。

本轮结论：可以建立 Android 的初步源码与动效依据，**仍不足以关闭“先研究透 Telegram 全 UI 再重构”的前置门槛**。未新增测试代码，未构建、发布或推送任何产品包。
