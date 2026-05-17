# Piko Native Dual UI Rewrite PRD

## 1. 背景和目标

Piko 当前已经具备双端原生工程形态：iOS 由 Swift/SwiftUI 驱动，Android 由 Kotlin + Jetpack Compose 驱动，后端提供账号、好友、设备、presence、signaling 和 P2P session 能力。本次需求不是重写传输内核，而是全面推倒并重构移动端 UI，让 iOS 和 Android 分别回到平台原生体验，减少当前“跨平台视觉互相迁就”和“页面状态编排混在壳层里”的问题。

目标是交付一套新的移动端 UI 产品规格：iOS 使用纯原生 SwiftUI 页面组件，Android 使用纯原生 Kotlin 页面组件；两端共享产品信息架构、状态语义和验收标准，但不共享 UI 框架、不引入 Flutter、React Native、Compose Multiplatform 或 WebView UI 壳。

## 2. 当前项目审计摘要

### 2.1 仓库结构

- Android app module: `android/`
- iOS Xcode project: `ios/Piko.xcodeproj`
- iOS Swift sources: `ios/*.swift`
- Backend worker/API: `backend/src`
- Shared build entry: `scripts/build-mobile.sh`
- P2P hard boundary: `AGENTS.md`

### 2.2 iOS 当前形态

iOS 入口为 `ios/PikoApp.swift`，主界面为 `ios/PikoRootView.swift`，核心状态集中在 `ios/NativePikoModel.swift`。当前 UI 已经是原生 Swift/SwiftUI，但仍存在明显的混合层和重构债：

- `PikoRootView` 使用 `TabView` 组织接收、发送、设置三页，并用自定义 overlay 做顶部折叠标题。
- `NativeReceiveView` 通过 `UIViewControllerRepresentable` 包装 `UITableViewController`，不是纯 SwiftUI 页面组件。
- `PikoStyle.swift` 承担调色、字体、滚动进度探测、通用面板、图标解析等多种职责。
- `NativePikoModel` 同时持有 LAN discovery、P2P client、signaling、account、friends、receive history、send selection 等状态，UI 重构必须复用其非视觉能力，但需要把页面展示状态和传输行为入口划清。

应保留的非 UI 能力包括：

- `NativePikoModel` 中的设备昵称、发现、账号、好友、presence、接收历史和发送动作编排。
- `NativeP2PTransferClient.swift`、`NativeWebRTCEngine.swift`、`NativeTransferProtocolV3.swift`、`NativeTransferSessionApiClient.swift`、`NativeSignalingClient.swift`。
- `NativeLocalSend*`、`NativeReceiveFileStore`、`NativeReceiveHistoryStore`。

### 2.3 Android 当前形态

Android 入口为 `android/src/main/kotlin/com/piko/app/MainActivity.kt`，运行 `AndroidPikoApp()`。现有实现虽然已经是 Kotlin + Compose，但上一轮只把默认壳层换成 Material 3 `Scaffold`、`NavigationBar` 和 `FloatingActionButton`，接收、发送、设置、好友等核心页面仍沿用旧的 `ui/` 层结构和装饰性组件。因此它不满足“全面推倒重来”的产品目标。

当前 Android UI 债务必须按重写而不是换皮处理：

- `AndroidPikoApp.kt` 仍然混合 app composition、runtime wiring、副作用、弹窗、导航状态和发送动作触发，主入口缺少清晰的 app shell 与 feature route 边界。
- `PikoReceiveScreen`、`PikoSendScreen`、`PikoSettingsScreen`、`FriendsScreen`、`FriendRequestsScreen` 仍以 `pikoPageBrush()`、`PikoHeroPanel`、自定义 section panel、装饰性卡片和手写图形为主要页面语言，视觉变化不够系统级。
- 接收页仍是旧“Hero + banner + 卡片”结构，没有改造成 Android 用户熟悉的 top app bar、list item、status card、empty state 和 confirmation flow。
- 发送页仍是旧分块堆叠结构，没有改造成“目标设备、待发送内容、发送状态、主行动”四段式 Material 3 task flow。
- 设置页和好友页仍像自定义面板集合，账号/好友入口没有形成 Android 原生 settings/list/detail 结构。
- 旧 `glass/` 实验和旧 `ui/` 页面组件不得出现在默认运行路径；如果暂时留在仓库中，只能作为未引用的历史代码，不能被 `AndroidPikoApp` 或新的 app shell import。

本轮 Android 重写必须建立新的默认 UI 架构：

- `com.piko.app.app`：只负责 app shell、destination state、top/bottom bars、FAB、dialog host 和 route 分发。
- `com.piko.app.design`：只放 Android-native Material 3 theme、spacing、status labels 和少量可复用 Material 组件。
- `com.piko.app.feature.receive`：接收页完整重写，使用 Material 3 status card、list item、empty state、history actions。
- `com.piko.app.feature.send`：发送页完整重写，使用 Material 3 device list、file selection summary、transfer status 和 direct-path labels。
- `com.piko.app.feature.settings`：设置、账号、好友入口完整重写，使用 settings list 结构。
- `com.piko.app.feature.friends`：好友列表和申请页完整重写，使用搜索栏、list item 和明确的申请状态 action。

应保留的非 UI 能力包括：

- `domain/` 下 `PikoHomeState`、`SendPageState`、`FriendModels`、`TransferProtocolV3` 等业务状态和协议模型。
- `platform/AndroidSendPlatformActions.kt` 的系统选择器、LAN discovery、LocalSend、P2P 调用和接收动作。
- `data/` 下 token、identity、receive history、friends、progress store。
- `transport/` 下 LocalSend、P2P、session、signaling、API client 和 XQUIC direct transport。

### 2.4 后端和传输边界

Backend 当前提供账号、好友、presence、设备注册、ICE config、transfer session 和 signaling websocket。`backend/src/ice.ts` 只返回 STUN servers；`backend/src/routes/transfer-sessions.ts` 负责 session 登记、好友/设备校验、signaling invite 派发和短期 session 存储。

本次 UI 重构不得改变这些传输边界。P2P 失败只能通过诊断、状态展示、直连等待策略和用户提示改进，不得引入 TURN、relay、中继、中转、云端代传、对象存储临时上传下载等方案。

## 3. 产品定位

Piko 是一个移动端文件直连工具。新的 UI 要让用户在 3 秒内理解三件事：

1. 当前设备是否正在接收。
2. 可以把文件发给谁。
3. 当前传输为什么成功、等待或失败。

设计方向是“安静、清晰、原生、高信任感”，不是炫技型视觉实验。页面优先展示传输状态、设备关系和可执行动作；装饰性玻璃、复杂动效和跨平台拟态不是目标。

## 4. 范围

### 4.1 In Scope

- iOS 端重建 SwiftUI-first 页面体系。
- Android 端重建 Kotlin + Jetpack Compose + Miuix 页面体系，并用 AndroidLiquidGlass/Backdrop 作为少量前景强调层。
- 双端统一信息架构：接收、发送、好友/设备、设置。
- 双端统一状态语义：搜索中、可发送、等待确认、传输中、完成、失败、离线、权限缺失。
- 重新定义导航、空状态、错误状态、权限状态、传输状态、历史记录和设备选择组件。
- 写入可测试验收标准，确保 UI 重构不破坏现有传输能力。

### 4.2 Out of Scope

- 不重写 LocalSend、P2P、WebRTC、XQUIC、TransferProtocolV3、signaling、backend route。
- 不新增 TURN、relay、中继、中转、云端转发、对象存储临时上传下载。
- 不更换账号/好友/设备注册 API 合同。
- 不调整 Android release signing、iOS unsigned IPA 构建合同。
- 不把 UI 重构扩展到桌面端或 Web 端。

## 5. 用户体验需求

### 5.1 全局导航

移动端保留三个主入口：

- 接收：默认首页，展示本机接收状态、设备名、接收确认、传输进度和历史记录。
- 发送：选择目标设备、选择文件/图片、展示发送准备和进行中状态。
- 设置：账号、好友入口、接收偏好、设备名、版本和诊断入口。

Android 使用 Miuix `NavigationBar`/`FloatingNavigationBar` 或适配大屏的 `NavigationRail`；iOS 使用 SwiftUI `TabView` 和系统 tab item。Android 可以在底部导航和主行动上使用 AndroidLiquidGlass 前景效果，但不得把旧 `glass/` 实验组件作为默认导航。

### 5.2 接收页

接收页必须优先回答“我现在能不能收文件”。

必须包含：

- 本机设备名和重置昵称入口。
- 接收服务状态：启动中、可接收、启动失败。
- 待确认传输卡片：发送方、文件数、总大小、接收/拒绝。
- 进行中传输卡片：进度、已接收大小、当前文件、取消。
- 接收历史列表：文件名、来源设备、时间、大小、文件类型、缩略图。
- 删除历史记录时必须保留“只删记录”和“同时删除文件”的确认语义。
- 空状态必须说明当前设备正在等待接收，而不是只显示“暂无记录”。

### 5.3 发送页

发送页必须优先回答“我能发给谁、要发什么、能不能现在发”。

必须包含：

- 目标设备分组：我的设备、局域网设备、好友设备。
- 每个设备显示在线/离线、平台、传输路径：LAN direct 或 P2P direct。
- 文件选择：图片、视频、文档，支持多选、移除、总大小汇总。
- 发送前确认区：已选目标数、文件数、总大小、预计传输路径。
- 发送中状态：目标、进度、暂停/取消能力只在底层已支持时展示。
- P2P 失败弹窗保留复制诊断能力，但文案要面向用户说明真实原因。

### 5.4 好友和账号

账号和好友不应被隐藏在长设置列表里。设置页保留账号入口，登录后可进入好友列表。

必须包含：

- 登录/注册入口。
- 当前账号信息。
- 好友数量和待处理申请数量。
- 搜索用户、发送申请、接受/拒绝/取消申请、删除好友。
- 好友设备 presence 变化需要能反映到发送页。

### 5.5 设置页

设置页只放长期偏好和诊断入口，不承载主任务。

必须包含：

- 图片/视频保存位置：文件夹或相册。
- 自动接收策略展示：首版可只展示当前策略，不新增复杂配置。
- 设备昵称重置入口。
- 账号和好友入口。
- 版本信息和基础诊断入口。

## 6. 平台实现要求

### 6.1 iOS

iOS UI 必须使用 SwiftUI-first 组件：

- 主导航使用 SwiftUI `TabView`。
- 页面使用 SwiftUI `NavigationStack`、`ScrollView`、`List`、`Section`、`Button`、`Picker`、`ProgressView`、`PhotosPicker` 等原生组件。
- 不再使用自定义 `UITableViewController` 作为接收页主体。
- 不再通过全局 UIKit appearance 或自定义 overlay 模拟主要导航体验，除非是系统能力缺口且在代码注释中说明原因。
- 文件选择、图片选择、系统分享/预览等确需 UIKit bridge 的能力，可以保留薄平台适配层，但不得把业务页面做成 UIKit controller。
- 非 UI 的 P2P、LocalSend、signaling、账号和存储代码保留。

建议包/文件方向：

- `ios/UI/AppShell/`：Tab shell、navigation shell。
- `ios/UI/Receive/`：接收页 SwiftUI 组件。
- `ios/UI/Send/`：发送页 SwiftUI 组件。
- `ios/UI/Friends/`：好友和申请页面。
- `ios/UI/Settings/`：设置页。
- `ios/UI/DesignSystem/`：颜色、字体、间距、按钮、状态卡片。
- `ios/Native*` 非 UI 能力可以逐步迁移命名，但不能与 UI 重构捆绑重写。

### 6.2 Android

Android UI 必须使用纯原生 Kotlin + Jetpack Compose + Miuix，并且本轮验收不是“换一层导航壳”，而是默认运行路径的页面级重写。Miuix 是系统 UI foundation；AndroidLiquidGlass/Backdrop 只能作为少数高价值前景层，用于底部导航、发送主行动、底部操作面板等视觉焦点。当前构建链是 AGP 9.0.0 + compileSdk 36，Miuix 0.9.x AAR metadata 要求 compileSdk 37；因此本轮采用可构建的 `miuix-android:0.8.8` + `miuix-icons-android:0.8.8`，后续升级 SDK/AGP 后再切到 0.9.x 拆分包。

硬性要求：

- 主入口仍由 `MainActivity` 启动 Kotlin/Compose app。
- 不引入 Flutter、React Native、Compose Multiplatform、WebView UI 壳。
- 默认 runtime path 必须从 `com.piko.app.platform.AndroidPikoApp` 进入 `com.piko.app.app.PikoAndroidAppShell` 或同等 app-shell 组件。
- `AndroidPikoApp.kt` 只保留 runtime wiring、repository/platform action 创建、生命周期副作用和 state mutation，不再直接声明主导航 UI、页面 UI、FAB 内容或 feature screen。
- 默认页面必须来自 `com.piko.app.feature.receive`、`feature.send`、`feature.settings`、`feature.friends`；旧 `com.piko.app.ui.PikoReceiveScreen`、`PikoSendScreen`、`PikoSettingsScreen`、`FriendsScreen`、`FriendRequestsScreen` 不得被默认 app shell 引用。
- 新页面必须使用 Miuix 的 `Scaffold`、`TopAppBar`/`SmallTopAppBar`、`NavigationBar`/`FloatingNavigationBar`、`Card`、`BasicComponent`、`Button`、`TextButton`、`ProgressIndicator`、`SearchBar`、`SuperDialog` 和 preference/list components 组织界面。
- 设置页必须优先使用 Miuix 0.8.8 的 `SuperArrow`、`SuperRadioButton` 或等价 Miuix preference/list 组件，而不是手写 Material list row。升级到 Miuix 0.9.x 后可替换为 `ArrowPreference` / `RadioButtonPreference`。
- 默认页面不得使用 `pikoPageBrush()`、`PikoHeroPanel`、`PikoSectionPanel`、`RoundedRectProgressIndicator`、旧 `LiquidBottomTabs`、旧 `LiquidSendFloatingButton`、Material3 `LargeTopAppBar`、Material3 `ElevatedCard`、Material3 `ListItem`、Material3 `FilterChip`、Material3 `AlertDialog` 或其他旧装饰性 chrome。
- 主题必须建立 Android-native Miuix tokens：`PikoMiuixTheme`、`PikoSpacing`、`PikoStatusTone` 等，不再沿用 iOS 命名、Material3 color scheme 或页面级渐变背景。
- LiquidGlass 必须通过新的 `com.piko.app.design.glass` wrapper 使用，默认路径不得 import 旧 `com.piko.app.glass` 或 `com.piko.app.ui`。
- 接收页首屏必须展示当前设备名、接收状态、待确认/进行中传输、历史空状态或历史列表；删除历史必须保留“只删记录”和“同时删除文件”语义。
- 发送页首屏必须展示目标设备分组、文件/图片选择、发送前 summary、进行中传输和 P2P direct failure dialog；不得暗示云端转发。
- 设置页必须使用 Android settings-list 结构展示传输偏好、保存位置、账号、好友和版本诊断入口；账号/好友可进入独立 route，而不是埋在旧装饰面板里。
- 好友和申请页必须是独立 feature screen，提供搜索、申请、接受、拒绝、取消、删除好友 action。

建议包结构：

- `com.piko.app.app`：application shell、route host、destination state、dialog host、top/bottom bars。
- `com.piko.app.design`：Miuix theme、spacing、status labels、shared Miuix list/card helpers。
- `com.piko.app.design.glass`：Backdrop host、glass navigation surface、send action surface 和少量 foreground effect wrappers。
- `com.piko.app.feature.receive`：接收页面。
- `com.piko.app.feature.send`：发送页面。
- `com.piko.app.feature.friends`：好友和申请页面。
- `com.piko.app.feature.settings`：设置页面。
- `domain`、`data`、`transport`、`platform` 继续作为非 UI 能力边界。

Android 验收测试必须检查：

- 新 app shell 与 feature packages 存在并被默认入口引用。
- `gradle/libs.versions.toml` pin Miuix Android artifacts，默认 runtime path import `top.yukonga.miuix.kmp.*`。
- 默认 runtime path 使用新的 `design.glass` wrapper，且只把 LiquidGlass 用在导航、主行动或 sheet 等有限前景层。
- 默认 app shell 不 import `com.piko.app.ui.PikoTabScreen`、旧 screen composables 或 `com.piko.app.glass`。
- 旧装饰性组件不出现在默认运行路径。
- `domain`、`transport`、`platform/AndroidSendPlatformActions.kt` 的传输能力未被 UI 重写改动。

## 7. 状态和数据流

双端重构后，页面必须遵守同一个数据流原则：

1. UI 只读取 screen state。
2. 用户动作通过 event sink 发出。
3. app coordinator 或 view model 负责调用平台能力和 transport。
4. transport 回调转换为 domain event。
5. domain event 更新 screen state。

禁止页面直接拼接网络请求、直接操作 signaling 消息或直接改 transfer protocol。

最低需要定义这些 screen state：

- `ReceiveScreenState`
- `SendScreenState`
- `FriendScreenState`
- `SettingsScreenState`
- `TransferFailureUiState`
- `DeviceCardUiState`
- `FileSelectionUiState`

## 8. 视觉和交互原则

- 原生优先：iOS 看起来像 iOS，Android 看起来像 Android。
- 信息优先：核心状态、可点击动作和错误原因比装饰重要。
- 少即是多：首版不追求复杂动态背景、液态玻璃、过度动效。
- 一屏可判断：接收页和发送页首屏都必须能判断当前主状态。
- 可恢复：失败状态必须给出下一步动作或真实原因。
- 可访问：支持动态字体、暗色模式、系统 reduce motion、高对比度。

## 9. 传输硬边界

本节是硬性验收项。

- P2P 文件传输只采用端到端直连能力。
- 允许继续使用 STUN 进行候选发现。
- 不允许新增 TURN server、relay candidate、中继服务器、中转服务、云端代传、对象存储临时上传下载。
- 不允许把“上传到服务器后让对方下载”包装成 P2P 失败兜底。
- 不允许修改 backend 让其承载文件内容。
- 不允许在 UI 文案中暗示存在云端转发能力。
- 如果直连失败，UI 只能展示诊断、建议切换网络、检查本地网络权限、等待对方在线或重试。

当前可保留的直连诊断包括：

- STUN 服务不可达。
- 未采集到本机候选。
- 对方未采集到候选。
- 对称 NAT。
- 双方 CGNAT。
- mDNS-only 导致跨网不可达。
- ICE connectivity check 全部失败。

## 10. 阶段计划

### Phase 0: UI Contract Freeze

交付物：

- 梳理并冻结当前非 UI 合同。
- 为 Receive/Send/Settings/Friends 写双端 UI contract tests。
- 明确禁止触碰的传输文件清单。

验收：

- Android `:android:testDebugUnitTest` 中存在 UI contract 覆盖。
- iOS 项目能列出所有保留的 transfer/signaling 文件。
- `rg` 检查 PRD 和新增 UI 代码无 TURN/relay/中继方案。

### Phase 1: App Shell

交付物：

- iOS 新 SwiftUI Tab shell。
- Android 新 Compose Material 3 app shell。
- 双端主导航、状态栏、底栏、暗色模式基础完成。

验收：

- 默认启动路径进入新 shell。
- 旧 shell 不再是默认路径。
- 接收、发送、设置三入口都可达。

### Phase 2: Receive

交付物：

- 双端新接收页。
- 接收确认、进行中、完成、历史、删除确认完成。

验收：

- 删除确认语义不回退。
- 接收历史可持久化加载。
- 传输完成事件能生成历史记录。

### Phase 3: Send

交付物：

- 双端新发送页。
- 目标设备分组、文件选择、发送前摘要、传输进度、失败诊断完成。

验收：

- LAN direct 设备发现和选择不退化。
- P2P direct 好友设备展示在线状态。
- P2P 失败弹窗保留复制诊断。

### Phase 4: Account and Friends

交付物：

- 双端账号登录/注册。
- 好友列表、搜索、申请处理、好友设备 presence。

验收：

- 登录后设备注册和 signaling connect 行为不回退。
- 好友设备可进入发送目标列表。

### Phase 5: Settings and Diagnostics

交付物：

- 双端设置页。
- 保存位置、设备名、版本、诊断入口。

验收：

- 保存位置偏好在重启后保持。
- 诊断入口不泄露 token 或私密文件路径。

### Phase 6: Legacy UI Removal

交付物：

- 删除已不再被默认路径引用的旧 UI 文件。
- 保留非 UI transfer/domain/data/platform 文件。

验收：

- Android 默认 APK 字符串和入口确认使用新 UI path。
- iOS Xcode project 不再引用旧 UI 页面文件。
- 双端构建通过。

## 11. 验收标准

### 11.1 静态验收

- iOS UI 文件不再以 `UITableViewController` 作为业务页面主体。
- Android 默认 UI 不再依赖 `com.piko.app.glass` 作为主导航。
- 新增 UI 代码不新增 `turn:`、relay server、server-side file relay、object storage fallback。
- backend transfer/session/signaling route 不因 UI 重构改合同。

### 11.2 行为验收

- 首次进入接收页可以看到本机可接收状态和设备名。
- 发送页未选目标或未选文件时不能发送，并给出清晰禁用状态。
- 有 LAN 设备时能选择并走 LAN direct。
- 有好友在线设备时能选择并走 P2P direct。
- 直连失败时展示真实诊断，不承诺云端兜底。
- 接收完成后历史记录出现，重启后仍存在。

### 11.3 构建验收

推荐命令：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home sh ./gradlew :android:testDebugUnitTest
xcodebuild -project ios/Piko.xcodeproj -scheme Piko -configuration Debug -sdk iphonesimulator -derivedDataPath build/ios-derived build
scripts/build-mobile.sh
git diff --check
```

如只改 PRD，可用：

```bash
git diff --check -- PRD.md tasks/todo.md
rg -n "\bTURN\b|\bturn:|\brelay\b|中继|中转|对象存储|云端转发|临时上传" PRD.md
```

第二条命令允许命中本 PRD 的禁止事项章节，不允许命中新方案章节。

## 12. 风险和决策

- iOS 风险：如果严格禁止所有 UIKit bridge，文件选择、照片选择和部分系统能力会被不必要地复杂化。决策：业务页面必须 SwiftUI-first，系统能力 bridge 可以薄封装保留。
- Android 风险：当前 Compose 已经是原生 Kotlin，真正问题不是“有没有 Kotlin”，而是壳层职责过重和视觉语言不原生。决策：重构重点放在 app shell、state holder 和 screen contracts。
- 传输风险：UI 重构容易把“失败兜底”写成中继方案。决策：PRD 明确写死纯直连边界，所有失败只展示诊断和重试建议。
- 进度风险：双端同时推倒容易破坏可运行路径。决策：按 shell、receive、send、friends、settings、legacy removal 分阶段，每阶段都保持默认入口可运行。

## 13. 明确不做

- 不做云端文件暂存。
- 不做 TURN fallback。
- 不做 relay mode。
- 不做服务端文件代理。
- 不做跨端共享 UI framework。
- 不做桌面端 UI。
- 不做与本 PRD 无关的协议优化。
