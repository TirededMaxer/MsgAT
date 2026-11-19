# Telegram Desktop Windows UI 研究

研究日期：2026-11-09。**当前是发布版源码研究记录，尚未完成 UI 研究前置门槛。** 本记录没有启动 Telegram、登录账号、操作虚拟机、构建程序或新增测试代码，也不代表 MsgAT 已完成这些界面。安装包来源、签名、SHA-256 及实际画面由总研究记录另行核验；不能仅凭源码标签认定下载的 EXE 与其可重现一致。

## 固定参考与证据边界

| 项目 | 固定值 |
| --- | --- |
| 官方下载入口 | [Telegram Desktop](https://desktop.telegram.org/)；Windows 参考为官方 Desktop，不以第三方 Unigram 代替 |
| 官方仓库 | [telegramdesktop/tdesktop](https://github.com/telegramdesktop/tdesktop) |
| 发布参考 | [v7.4.1](https://github.com/telegramdesktop/tdesktop/releases/tag/v7.4.1)，发布于 2026-11-06 |
| 发布标签解析后的 commit | `157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19` |
| 当日 `dev` HEAD | `7381fb1df75d863eb3463fdddef57a2ac51f1d56`，仅用于记录版本差异，本文不混用其代码 |
| 锁定 UI 子模块 | `desktop-app/lib_ui`，`42c6f1e88db81e6d9e8006f3e7b1c3ba39f9cac4`；由发布版 gitlink 与 `.gitmodules` 确认 |
| 本地只读研究材料 | `.build-tools/telegram-ui/sources/windows/`；固定 commit、完整未截断文件树、所读文件及 SHA-256 清单均在此处，不进入产品代码或发布包 |
| 授权/实现边界 | 官方代码为 GPL，本文归纳可观察行为、布局事实与参数，不复制大段实现或把 Telegram 素材混入 MsgAT。MsgAT Windows 仍使用 .NET 原生 UI，默认双栏 |

下文“源码已查”表示实际读取了指定发布版文件及相关分支；“索引”只表示已定位官方文件，不能视为读完；**所有项目的官方客户端运行对照目前均未完成**。本轮未做字体、DPI、帧时序或像素相似度的实测，不能给出还原百分比。

尺寸采用源码样式在 100% 应用缩放下的参考值。Telegram 把应用缩放、设备像素比及字体解析分开处理；这些值不能直接当作某台 Win11 高 DPI 截图上的物理像素。源码基准缩放为 100，样式转换另有取整，Windows DPI 回退值为 96。[S01] [S02]

## 已实查的布局与动效路径

### W01 双栏、窄窗口与分隔宽度

参考默认窗口为 800 × 600，较大默认窗口为 1024 × 768，应用最小内容窗口为 380 × 480；左列最小宽度 260，主聊天列最小宽度 380。正文宽度先扣除文件夹栏，再计算列布局。有会话列表且正文宽度小于 **640** 时进入单栏。双栏情况下，左栏按保存的比例取整，至少 260，随后由剩余主栏至少 380 的条件限制。[S03] [S04]

重要细节：虽然样式声明了左栏最大值 540，但 `countDialogsWidthFromRatio` 中对应的最大值限制被注释，不能把 540 写成该发布版真实强制上限。第三栏还有独立开关及宽度门槛，不能因源码存在第三栏就把 MsgAT 默认改为三栏。拖动分隔条的抓取宽度、跨显示器缩放、比例保存与窄窗口返回路径仍需运行观察。

### W02 页面滑入、返回与底部转场

普通页面滑入使用 **240 ms / easeOutCirc**，旧页面最多平移 100，叠加渐暗与边缘阴影；从底部进入使用 **480 ms / easeOutQuint**。向左返回会交换前后缓存及进度方向，不能简单播放相同方向的位移。[S36] [S06] [S37]

此类使用前后页面缓存绘制；完成时调用完成回调。它没有独立的“用户拖到一半反向”公开 API。页面 owner 销毁、连续导航、重新定位时如何接管动画，须继续沿调用方核对并在真实窗口中复现；不能仅看到曲线常量就宣称中断行为一致。

### W03 会话列表、头像、文字与未读徽章

普通行高 **62**，头像 **46**，行内边距左右 10、上下 8；名称及摘要起点 x=68，名称 y=10、摘要 y=34。默认未读字体为 **12 / bold**，徽章高 **19**、左右附加留白 5，日期字体为 13。[S07]

样式中另有不同类型行高；这些不能混作普通单聊列表。头像圆角、徽章多位数字的实际形态、日期与摘要抢占宽度、媒体摘要与失败状态、列表选中及悬停颜色仍需实际对照。MsgAT 已获批的右侧未读数位置继续保留，不能回退到头像角标。

### W04 列表筛选切换与程序滚动

列表筛选标签切换为 **200 ms**，滑动手势切换为 **140 ms**；只有筛选实际改变、列表可见且没有页面切换动画等条件成立时才播放。旧切换画布先清除，再捕获前后列表。[S08] [S09]

程序滚动目标使用 **240 ms / sineInOut**。如果用户手动滚动越过目标，停止程序滚动；每帧也只在能更接近目标时推进，避免和滚轮争夺位置。[S10] [S36] 这是需要带入 MsgAT 的行为依据；触控板/滚轮单位与加速度本轮没有测量，不能用任意倍率声称复原灵敏度。

### W05 消息气泡的自然宽度与分组间距

样式参考：消息最大宽 430，正文左右内边距 11、上下 8，普通消息外边距为左 16、上 6、右 56、下 2；连续附着消息的顶部间距为 0。[S11]

**`msgMinWidth=160` 不是每个短文字气泡的最终下限。** `countGeometry()` 会按实际文字宽度加左右内边距收缩，并考虑非文字部分、媒体、自身最大宽度和聊天模式。对齐在最终宽度计算后完成。[S12] 因此不能将 160 硬设成 MsgAT 每条短消息的 `MinWidth`，重现用户此前报告的大块留白。

文本测量、元信息占位、单行/多行收缩、回复、编辑标记、媒体附文及圆角连接需要分别做相同内容对照。样式常量不能单独证明某条实际消息的高度。

### W06 时间与回执的独立绘制

`BottomInfo::paint()` 将发送中、未读、已读绘制为不同图标，另处理媒体反色图标；日期/编辑信息按其测量宽度排布，而不是用普通字体里的勾字符拼接。[S13]

MsgAT 的回执语义是用户的固定要求：**灰色单勾=已发送未送达；绿色单勾=送达未读；绿色双勾=已读**。Telegram 自身的业务状态不能覆盖这套语义。研究借鉴的是图标尺寸、基线、留白与状态切换反馈；这些参数的最终显示仍待参考 EXE 与 MsgAT 的同状态对照。

### W07 输入框增长、回复/编辑条与键盘策略

基础输入样式最小高 36，样式层最大高 72；实际聊天会按可用空间重新设置最大高，并限制在 **224** 以内，扣除顶栏、可见状态条、回复/编辑/链接预览条、发送区留白，同时为消息区保留空间。附件和发送按钮参考 **44 × 46**，发送区域上下留白为 9；回复区域高度为 49。[S14] [S15] [S49] [S50]

不能只照抄样式的 72 而漏掉运行时最大高度。提交方式在聊天中读取用户设置；MsgAT 仍按既定要求实现 Enter 发送、Shift+Enter 换行和输入法组合保护，实际键盘焦点、光标、草稿恢复与溢出滚动需要单独运行观察。[S16]

### W08 发送/录音/保存图标状态切换

普通按钮类型变化先保留旧内容、重算新尺寸，再使用 **120 ms** 的通用动画做透明度与缩放切换，默认 `Simple` 曲线为 linear。已有状态动画会停止，再开始新状态；`finishAnimating()` 可直接结束并按最终状态更新大小。语音/圆形视频切换走独立图标帧动画，不能把它也强行视作这 120 ms 路径。[S17] [S18] [S36]

可访问名称也按 Send、Record、Save、Cancel 等真实状态同步变化。此处没有复制 Telegram 的付费/慢速模式等专属业务。MsgAT 的实际发送条件必须决定按钮可用性和图标，不能显示没有真实行为的入口。

### W09 附件展开与拖放确认

附件菜单的显示/隐藏会等待按钮 ripple 的隐藏时长，然后调用菜单动画；这个时长来自具体按钮样式，不是已确定的整个附件面板动画时长。[S19]

拖放区域在录音等不允许附加文件的状态受阻。图片区与文件区走不同处理，最终均进入发送确认；粘贴图片也进入确认路径。确认前保留原输入文字和选择范围，取消后恢复；这不能被简化成拖入即发。[S20] [S21]

拖放高亮及整体透明度参考 **200 ms**，默认 linear；进入/离开会重定向进行中的透明度动画，完成隐藏后清除缓存与区域激活状态，快速隐藏直接停止动画。拖放区域留白为左右 20、上下 10。[S22] [S23] [S18] [S38] [S39] 文件混合、多个文件、裁剪后确认、Esc、窗口失活、从其他应用拖入与输入焦点恢复仍待实际操作。

### W10 表情/贴纸/GIF 面板切页

面板样式参考宽 **345**，高限制 **278–640**，另声明高度比例 0.75；实际容器可用空间与约束组合仍需运行核对。标签间切换 **200 ms**。驱动时间线为 linear，但到达/离开的位移分别使用 **easeOutCirc / easeInCirc**，透明度也分别使用对应的进出组合，不能只做线性左右平移。[S24] [S25] [S40]

切换保存旧标签滚动位置、刷新并预载新标签，再显示快照转场。隐藏完成与重新显示都会停止旧滑动并清除快照；隐藏前会处理焦点归还。[S41]没有把 Unicode emoji、Telegram 云表情库、或付费贴纸业务直接引入 MsgAT；本地贴纸与 QQ/微信导入仍按获批范围单独实现。

### W11 媒体查看器的控件显隐与交互阻止

控件闲置 **1100 ms** 后尝试隐藏；显示 **200 ms**，隐藏 **600 ms**，控件悬停淡变 **150 ms**，上述控制透明度使用 linear。光标移动等激活可从正在隐藏/已隐藏切回显示；重定向从当前透明度继续。菜单、鼠标按下和触摸移动会阻止通常的自动隐藏；禁用动画时直接推进完成。[S26] [S27] [S42]

媒体画面、视频播放控件、菜单和保存提示有独立状态，不能用整个窗口的透明度代替全部动画。载入、下载失败、下一张、缩放、全屏、Esc、关闭后聊天焦点/滚动恢复以及安全视频表面的捕获情况仍待真实 EXE 观察。

### W12 裁剪网格与图像编辑边界

裁剪网格透明度参考 **100 ms**；中途改变显隐时先读取当前值、停止旧动画，再按剩余透明度差乘 100 计算新时长，避免快速反向时忽闪或速度跳变。网格动画是 linear；图像编辑底部操作区参考高 146，左右/顶部图像边距参考 20。[S28] [S29]

这只证明 Windows 发布版中对应编辑控件的源码行为，不证明手机裁剪已经适配。用户报告的 Android 状态栏与裁剪顶部按钮重叠已明确延期到 UI 重构阶段；不得借本研究擅自提前修复或通过隐藏状态栏掩盖问题。

### W13 一对一通话控件与状态变化

来电、等待、忙线、连接中等状态决定接听、拒绝、取消、静音和挂断控件。挂断控件显隐位置变化采用 **150 ms / sineInOut**；通话视频控件整体显隐使用 **240 ms** 默认 linear；隐藏的窗口可直接更新控件，避免后台仍播放无意义转场。[S30] [S31] [S43]

这里研究了既有源码分支，尚未建立真实来电/拨出/拒绝/忙线/超时/断网/权限拒绝/设备切换的画面和声音证据。群通话及屏幕共享不加入本次功能范围；MsgAT 的端到端加密状态不得被参考界面的视觉复制削弱。

### W14 设置页滚动、固定操作区与返回恢复

设置容器为顶部/底部固定区域分别预留滚动空间；随可用宽度重新布局。返回状态保存并恢复滚动位置，折叠顶栏的高度也参与视口计算。[S32] [S44] 这支持 MsgAT 用合理宽度、受屏幕约束的可滚动设置页，并保留可达操作按钮。

设置页包含搜索、个人资料、通知、隐私、外观、存储和快捷操作等不同子页，不能仅把旧长表单放入滚动条就称为 Telegram 重构完成。实际导航转场、分组尺寸、焦点、错误信息和退出/恢复均待逐页操作。

### W15 Windows 窗口、主题、字体与托盘

Windows 原生窗口边框设置与夜间模式变化会共同触发主题更新。[S47]窗口+托盘、仅托盘、仅窗口分别处理任务栏 owner；托盘激活判断保留最近 **500 ms** 的失活窗口，避免点击托盘本身先使应用失活而误判。[S33] [S45] [S48]

默认非等宽字体解析为 **Open Sans**，允许系统/用户覆盖，另加载内置字体和替代字体；基础字号 13。WPF 字体度量、中文回退、字重、行距及 ClearType 与 Qt 不同，因此需要同 DPI、同文本实测，不能仅设置相同字号就认定一致。[S34] [S05]

实际读取了内置 `day-blue.tdesktop-theme` 与 `night.tdesktop-theme` 的 ZIP 配色文本。前者窗口底色 `#ffffff`、外发气泡 `#def1fd`；后者窗口底色 `#17212b`、收发气泡 `#182533`/`#2b5278`。这是**两个指定内置主题**的事实，不声称某次安装当前正在使用它们；其他主题、系统高对比度和运行时 accent 均未验收。[S35] [S46]

## 全部获批 UI 范围的覆盖索引

下表用于防止只研究几个主页面便开始大规模制作。`源码已查`不等于完整实现链或运行通过；`索引`仍需继续读取。各项均需补官方参考运行记录、MsgAT 实现记录及同状态对照结果。

| UI 范围 | 已定位的官方入口（均在固定 commit） | 当前研究状态 / 主要缺口 |
| --- | --- | --- |
| 会话列表、密度、选中、未读、日期、媒体摘要 | `dialogs/dialogs.style`、`dialogs/dialogs_widget.cpp`、`dialogs/ui/dialogs_layout.*` | 行与徽章样式已查；复杂行布局和空/错状态待逐项 |
| 搜索、结果、高亮、搜索内筛选 | `dialogs/dialogs_widget.cpp`、`dialogs/dialogs_search_from_controllers.cpp`、`dialogs/ui/chat_search_in.cpp` | 列表切换及滚动已查；搜索 UI 链和分页待查 |
| 双栏、分隔、窄窗口、DPI、窗口恢复 | `window/window_session_controller.cpp`、`window/window.style`、`platform/win/main_window_win.cpp` | 列算法/DPI 基础已查；分隔手势及多屏实际行为待查 |
| 聊天顶栏、名字/标记/状态、菜单 | `history/view/history_view_top_bar_widget.cpp` | 入口及绘制结构已查；图标、实际高度及所有状态需补 |
| 消息文本、媒体、时间、回执、分组圆角 | `history/view/history_view_message.cpp`、`history_view_bottom_info.cpp`、`ui/chat/chat.style` | 文本收缩/元信息已查；圆角资源、各类型实际画面待补 |
| 滚动锚点、新消息提示、回到底部 | `history/history_widget.cpp`、`history/view/history_view_list_widget.cpp` | 程序滚动的一条路径已查；消息列表锚定全链待查 |
| 输入框、键盘、输入法、草稿 | `history/history_widget.cpp`、`history/view/controls/history_view_compose_controls.cpp` | 尺寸和提交设置已查；IME/快捷键完整实际行为待补 |
| 回复、引用、编辑、取消/恢复 | `history/view/controls/history_view_compose_controls.cpp`、`chat_helpers/chat_helpers.style` | 条高/提交分支已查；所有切换动效与选区恢复待补 |
| 转发、多选、撤回、删除、复制 | `history/view/history_view_context_menu.cpp`、`history/history_widget.cpp` | 文件已定位；菜单可用性、选择模式及确认流程待读 |
| 附件展开、图标状态、拖放与粘贴确认 | `ui/controls/send_button.cpp`、`history/history_drag_area.cpp`、`boxes/send_files_box.cpp` | 主要路径已查；混合多文件及焦点实际表现待补 |
| 文件卡片、进度、取消、重试、导出 | `history/view/media/history_view_document.cpp`、`history_view_file.cpp` | 真实路径索引；具体布局及动画待读 |
| 图片、动图、视频、音频、相册/附文 | `history/view/media/history_view_photo.cpp`、`history_view_gif.cpp`、`history_view_media_grouped.cpp` | 真实路径索引；各类独立点击区与状态待读 |
| 媒体大图、缩放、视频控件、全屏与关闭 | `media/view/media_view_overlay_widget.cpp`、`media_view.style` | 控件显隐已查；媒体切换与关闭恢复实际行为待补 |
| 裁剪、旋转、编辑、发送二次确认 | `editor/editor_crop.cpp`、`editor/photo_editor_controls.cpp`、`boxes/send_files_box.cpp` | 网格/边界和确认入口已查；各工具全部状态待补 |
| URL 点击、链接卡片、预览设置/移除 | `history/view/media/history_view_web_page.cpp`、`history/history_widget.cpp` | 卡片标题/描述/链接处理入口已查；完整布局与切换待补 |
| 贴纸/GIF 面板、搜索、收藏、本地导入 | `chat_helpers/tabbed_selector.cpp`、`chat_helpers/stickers_list_widget.cpp`、`gifs_list_widget.cpp` | 面板/切页已查；资源格子、搜索/加载实际表现及 MsgAT 导入专属 UI 待补 |
| 联系人、资料、备注、封禁、媒体分类 | `info/profile/info_profile_widget.cpp`、`info/media/info_media_widget.cpp`、`boxes/add_contact_box.cpp` | 路径索引；单聊相关全部页面和状态待读 |
| 设置首页/子页、搜索、滚动、保存取消 | `info/settings/info_settings_widget.cpp`、`settings/sections/settings_main.cpp`、`settings/settings.style` | 容器滚动/恢复已查；全部子页排布与确认状态待补 |
| 消息通知、静音、托盘、关闭/退出 | `window/notifications_manager_default.cpp`、`platform/win/tray_win.cpp`、`platform/win/main_window_win.cpp` | owner/托盘激活已查；通知位置、操作与 Exit 实际流程待补 |
| 一对一语音/视频、设备选择、权限与失败 | `calls/calls_panel.cpp`、`calls/calls_top_bar.cpp`、`calls/calls.style` | 关键状态切换及动画已查；全呼叫状态画面与设备交互待补 |
| 全局菜单、弹窗、toast、tooltip、焦点 | `window/window_main_menu.cpp`、`ui/layers/*`（lib_ui）、`ui/widgets/*`（lib_ui） | 基础动画引擎已查；每种覆盖与恢复路径待补 |
| 深浅主题、字体放大、无障碍、减少动画 | `ui/style/*`（lib_ui）、`Resources/*.tdesktop-theme`、`settings/sections/*` | 缩放、字体及两份主题已查；实际无障碍及减少动画矩阵待补 |
| 硬件身份、密钥故障、安全验证、Reset Identity | Telegram 无与 MsgAT 相同的身份模型 | MsgAT 专属设计；沿用已研究的视觉规范，不照搬手机号/多设备登录流程 |

这份清单覆盖本轮批准的单聊和日常桌面操作。频道、故事、付费、机器人平台等 Telegram 专属业务不因其源码存在而自动加入 MsgAT。聊天功能有待确认的具体扩展必须在总功能矩阵中明确，而不是做无行为的按钮。

## 制作时必须保留的 MsgAT 约束

1. WPF/.NET 原生 UI 与默认双栏保持，参考版的布局逻辑、光学尺寸与动效独立实现；不整体换成 Qt，也不把 Mac/Android 套成同一皮肤。
2. 回执继续使用用户确认的三状态语义；未读计数在左侧会话行右侧，当前已查看的消息不能再产生该会话红点。
3. 媒体独立呈现与准确点击边界；自然宽度文本、紧凑分组间距、元信息基线分别对照，避免以前的空白气泡和全屏误触预览。
4. 原生 Exit、关闭、托盘恢复、首次打开/白窗、DPI 切换、界面线程刷新是实际使用验收项；动画不能阻塞退出或掩盖状态错误。
5. UI 相似不能改变 MsgAT 的硬件身份、端到端加密、密文媒体缓存与隐私规则。链接预览由发送方生成后加密，收方不能因复刻 Telegram 卡片而自动请求外部网页。
6. 先补齐尚未研究的页面、状态与真实动效依据，再制作大规模 UI；源码常量、静态截图、录像时序、交互结果和最终 MsgAT 对照分别记录。

## 需要补的实际操作证据

需要官方固定版、隔离数据目录和可用的 Telegram 参考账号；聊天、消息状态、附件、贴纸、通话等通常需要真实会话或另一参考身份。账号登录如要求验证码/密码，由用户在系统或客户端内完成，不通过聊天收集敏感凭据。

- 在明确的 Windows 版本、DPI、应用缩放、主题、字体、窗口尺寸和同一合成素材下记录参考画面；先 100% 缩放，再覆盖实际 Win11 VM 的缩放及多屏/窄窗口。
- 逐项操作上表：空、加载、正常、进行中、失败、取消、重试、离线/重连、关闭后恢复；保存来源画面与实际操作结论。
- 对动画记录开始、关键帧、结束、连续快速切换、中途关闭/反向、窗口失活与减少动态效果；时长常量仍须与真实帧呈现核对。
- 验证拖入/粘贴图片及文件的确认与取消恢复，输入法组合/选区、Enter/Shift+Enter、默认浏览器链接、滚轮/触控板与聊天滚动锚点。
- 用同样内容在 MsgAT 中复现并逐项比较，记录布局偏差、漏掉的状态、交互差异和仍需修复的具体问题；不捏造相似度分数。

**当前缺口：** 未启动官方参考程序；无正式运行截图/动画帧；未核实字体、图标、圆角和多 DPI 下的实际度量；覆盖索引内多条复杂路径尚未读完；无完整可进入大规模 UI 制作的完成结论。该缺口不能用增加测试代码或更多常量表代替。

## 固定源码引用

以下链接均锁定发布 commit；UI 工具库来源锁定其 `lib_ui` 子模块 commit。行号定位的是本次实际读取的文本范围；它们不是实际运行证据。

[S01]: https://github.com/desktop-app/lib_ui/blob/42c6f1e88db81e6d9e8006f3e7b1c3ba39f9cac4/ui/style/style_core_scale.h#L19-L55
[S02]: https://github.com/desktop-app/lib_ui/blob/42c6f1e88db81e6d9e8006f3e7b1c3ba39f9cac4/ui/style/style_core_scale.cpp#L27-L64
[S03]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/window/window.style#L13-L40
[S04]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/window/window_session_controller.cpp#L2798-L2877
[S05]: https://github.com/desktop-app/lib_ui/blob/42c6f1e88db81e6d9e8006f3e7b1c3ba39f9cac4/ui/basic.style#L53-L55
[S06]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/window/window_slide_animation.cpp#L115-L225
[S07]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/dialogs/dialogs.style#L78-L103
[S08]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/dialogs/dialogs.style#L215-L220
[S09]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/dialogs/dialogs_widget.cpp#L2613-L2644
[S10]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/dialogs/dialogs_widget.cpp#L2818-L2845
[S11]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/ui/chat/chat.style#L14-L58
[S12]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/history/view/history_view_message.cpp#L6493-L6545
[S13]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/history/view/history_view_bottom_info.cpp#L265-L308
[S14]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/chat_helpers/chat_helpers.style#L1307-L1343
[S15]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/history/history_widget.cpp#L9277-L9296
[S16]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/history/history_widget.cpp#L4030-L4034
[S17]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/ui/controls/send_button.cpp#L40-L205
[S18]: https://github.com/desktop-app/lib_ui/blob/42c6f1e88db81e6d9e8006f3e7b1c3ba39f9cac4/ui/effects/animations.h#L66-L79
[S19]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/history/view/controls/history_view_compose_controls.cpp#L2236-L2253
[S20]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/history/history_widget.cpp#L650-L724
[S21]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/history/history_widget.cpp#L8087-L8132
[S22]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/history/history_drag_area.cpp#L611-L618
[S23]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/history/history_drag_area.cpp#L719-L773
[S24]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/chat_helpers/chat_helpers.style#L514-L521
[S25]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/chat_helpers/tabbed_selector.cpp#L1342-L1417
[S26]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/media/view/media_view.style#L279-L282
[S27]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/media/view/media_view_overlay_widget.cpp#L3217-L3264
[S28]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/editor/editor_crop.cpp#L469-L484
[S29]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/editor/editor.style#L15-L27
[S30]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/calls/calls_panel.cpp#L1814-L1837
[S31]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/calls/calls_panel.cpp#L1320-L1348
[S32]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/info/settings/info_settings_widget.cpp#L88-L153
[S33]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/platform/win/main_window_win.cpp#L522-L549
[S34]: https://github.com/desktop-app/lib_ui/blob/42c6f1e88db81e6d9e8006f3e7b1c3ba39f9cac4/ui/style/style_core_font.cpp#L306-L326
[S35]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/Resources/day-blue.tdesktop-theme

[S36]: https://github.com/desktop-app/lib_ui/blob/42c6f1e88db81e6d9e8006f3e7b1c3ba39f9cac4/ui/basic.style#L96-L135
[S37]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/window/window_slide_animation.h#L41-L45
[S38]: https://github.com/desktop-app/lib_ui/blob/42c6f1e88db81e6d9e8006f3e7b1c3ba39f9cac4/ui/layers/layers.style#L37-L38
[S39]: https://github.com/desktop-app/lib_ui/blob/42c6f1e88db81e6d9e8006f3e7b1c3ba39f9cac4/ui/effects/animations.h#L450-L505
[S40]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/chat_helpers/tabbed_selector.cpp#L136-L164
[S41]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/chat_helpers/tabbed_selector.cpp#L1083-L1120
[S42]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/media/view/media_view_overlay_widget.cpp#L2568-L2604
[S43]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/calls/calls.style#L502
[S44]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/info/settings/info_settings_widget.cpp#L271-L280
[S45]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/platform/win/main_window_win.cpp#L591-L594
[S46]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/Resources/night.tdesktop-theme
[S47]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/platform/win/main_window_win.cpp#L422-L434
[S48]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/platform/win/main_window_win.cpp#L59-L64
[S49]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/chat_helpers/chat_helpers.style#L1141-L1147
[S50]: https://github.com/telegramdesktop/tdesktop/blob/157f5cfc85e2dd1fa67f129a5e31a81db5ddeb19/Telegram/SourceFiles/chat_helpers/chat_helpers.style#L1429-L1435
