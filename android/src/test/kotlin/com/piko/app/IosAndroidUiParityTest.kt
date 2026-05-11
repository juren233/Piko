package com.piko.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosAndroidUiParityTest {
    private val rootDir: File
        get() = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun iosContentUsesAndroidPageStructureAndCopy() {
        val sendView = readIos("NativeSendView.swift")
        val settingsView = readIos("NativeSettingsView.swift")
        val receiveView = readIos("NativeReceiveView.swift")
        val style = readIos("PikoStyle.swift")

        listOf(sendView, settingsView, receiveView).forEach { source ->
            assertFalse("NavigationView" in source)
            assertFalse("navigationTitle" in source)
            assertFalse("pageGradient" in source)
        }
        assertFalse("LinearGradient(" in style)

        assertInOrder(sendView, "我的设备", "局域网设备", "我的好友", "NativeImageSection", "NativeFileSection")
        assertInOrder(settingsView, "传输", "自动接收", "传输策略", "账号", "登录方式")
        assertFalse("最近接收" in receiveView)
        assertTrue("NativeReceiveHistoryRow" in receiveView)
    }

    @Test
    fun androidUsesFixedIosPaletteInsteadOfDynamicSystemColors() {
        val androidTheme = File(rootDir, "android/src/main/kotlin/com/piko/app/PikoTheme.kt").readText()
        val androidApp = File(rootDir, "android/src/main/kotlin/com/piko/app/AndroidPikoApp.kt").readText()
        val sharedApp = File(rootDir, "android/src/main/kotlin/com/piko/app/App.kt").readText()
        val bottomTabs = File(rootDir, "android/src/main/kotlin/com/piko/app/glass/LiquidBottomTabs.kt").readText()
        val iosStyle = readIos("PikoStyle.swift")

        assertTrue("UIColor.systemBackground" in iosStyle)
        assertTrue("UIColor.secondarySystemBackground" in iosStyle)
        assertTrue("UIColor.systemBlue" in iosStyle)
        assertTrue("IOS_SYSTEM_BLUE_LIGHT = Color(0xFF007AFF)" in androidTheme)
        assertTrue("IOS_SYSTEM_BLUE_DARK = Color(0xFF0A84FF)" in androidTheme)
        assertTrue("IOS_SECONDARY_SYSTEM_BACKGROUND_LIGHT = Color(0xFFF2F2F7)" in androidTheme)
        assertTrue("IOS_SECONDARY_SYSTEM_BACKGROUND_DARK = Color(0xFF1C1C1E)" in androidTheme)
        assertTrue("PikoTheme {" in androidApp)
        assertTrue("PikoTheme {" in sharedApp)
        assertTrue("PikoColors.accent" in bottomTabs)
        assertFalse("dynamicLightColorScheme" in androidTheme + androidApp + sharedApp)
        assertFalse("dynamicDarkColorScheme" in androidTheme + androidApp + sharedApp)
        assertFalse("Color(0xFF0088FF)" in bottomTabs)
        assertFalse("Color(0xFF0091FF)" in bottomTabs)
    }

    @Test
    fun iosContentIconsStayLucideSourcedLikeAndroid() {
        val style = readIos("PikoStyle.swift")
        val receiveView = readIos("NativeReceiveView.swift")
        val sendItemSection = readIos("NativeSendItemSection.swift")
        val sendDeviceSection = readIos("NativeSendDeviceSection.swift")
        val transferSection = readIos("NativeSendTransferSection.swift")

        assertTrue("case inbox" in style)
        assertTrue("M22,12H16l-2,3H10l-2,-3H2" in style)
        assertTrue("case file" in style)
        assertTrue("case image" in style)
        assertTrue("case plus" in style)
        assertTrue("case x" in style)
        assertTrue("case check" in style)
        assertTrue("case smartphone" in style)
        assertTrue("case refreshCw" in style)
        assertTrue("M7,2h10a2,2 0,0 1,2 2v16a2,2 0,0 1,-2 2H7a2,2 0,0 1,-2 -2V4a2,2 0,0 1,2 -2z" in style)
        assertTrue("M3,12a9,9 0,0 1,9 -9 9.75,9.75 0,0 1,6.74 2.74L21,8" in style)

        val iosContent = receiveView + sendItemSection + sendDeviceSection + transferSection
        listOf("inbox", "file", "image", "plus", "x", "check", "smartphone", "refreshCw").forEach { name ->
            assertTrue("LucideTabIcon.$name.image" in iosContent)
        }
        assertFalse("Image(systemName: \"tray" in iosContent)
        assertFalse("Image(systemName: \"doc" in iosContent)
        assertFalse("Image(systemName: \"photo" in iosContent)
        assertFalse("Image(systemName: \"plus" in iosContent)
        assertFalse("Image(systemName: \"xmark" in iosContent)
        assertFalse("Image(systemName: \"checkmark" in iosContent)
    }

    @Test
    fun iosSendDeviceSubtitleKeepsAndroidOptionalContract() {
        val androidState = File(rootDir, "android/src/main/kotlin/com/piko/app/SendPageState.kt").readText()
        val iosModel = readIos("NativePikoModel.swift")
        val iosDeviceSection = readIos("NativeSendDeviceSection.swift")

        assertTrue("val subtitle: String? = null" in androidState)
        assertTrue("let subtitle: String?" in iosModel)
        assertTrue("if let subtitle = device.subtitle" in iosDeviceSection)
    }

    @Test
    fun iosWorkflowBuildsWithAvailablePhoneSdkWithoutInstallingSimulator() {
        val workflow = File(rootDir, ".github/workflows/build-packages.yml").readText()
        val iosScript = File(rootDir, "scripts/ios/build-packages.sh").readText()
        val xcodeCandidateLoop = workflow.substringBefore("if [[ -z \"\$selected_xcode\" ]]; then")

        assertFalse("xcodebuild -downloadPlatform iOS" in workflow)
        assertFalse("iphonesimulator" in workflow)
        assertFalse("iphonesimulator" in iosScript)
        assertFalse("-destination" in workflow)
        assertFalse("-destination" in iosScript)
        assertFalse("xcodebuild \\" in iosScript)
        assertFalse("SUPPORTED_PLATFORMS=iphoneos" in iosScript)
        assertTrue("sdk_version=\"\$(xcrun --sdk iphoneos --show-sdk-version)\"" in xcodeCandidateLoop)
        assertTrue("sdk_version=\"\$(xcrun --sdk iphoneos --show-sdk-version)\"" in iosScript)
        assertTrue("xcrun swiftc" in iosScript)
        assertTrue("-target \"arm64-apple-ios\${DEPLOYMENT_TARGET}\"" in iosScript)
        assertTrue("sdk_path=\"\$(xcrun --sdk iphoneos --show-sdk-path)\"" in iosScript)
        assertTrue("\"CFBundleIdentifier\": bundle_id" in iosScript)
        assertTrue("\"MinimumOSVersion\": deployment_target" in iosScript)
        assertInOrder(
            xcodeCandidateLoop,
            "sdk_version=\"\$(xcrun --sdk iphoneos --show-sdk-version)\"",
            "sdk_path=\"\$(xcrun --sdk iphoneos --show-sdk-path)\"",
            "selected_xcode=\"\$candidate\"",
        )
        assertInOrder(
            iosScript,
            "sdk_version=\"\$(xcrun --sdk iphoneos --show-sdk-version)\"",
            "sdk_path=\"\$(xcrun --sdk iphoneos --show-sdk-path)\"",
            "xcrun swiftc",
            "-sdk \"\$sdk_path\"",
            "-parse-as-library",
            "-module-name \"\$SCHEME\"",
            "-o \"\$executable\"",
        )
    }

    @Test
    fun sendPagesDoNotExposeCurrentDeviceAndUseRandomNicknameBanner() {
        val androidState = File(rootDir, "android/src/main/kotlin/com/piko/app/SendPageState.kt").readText()
        val androidApp = File(rootDir, "android/src/main/kotlin/com/piko/app/AndroidPikoApp.kt").readText()
        val androidDiscovery = File(rootDir, "android/src/main/kotlin/com/piko/app/AndroidSendPlatformActions.kt").readText()
        val androidReceive = File(rootDir, "android/src/main/kotlin/com/piko/app/ReceiveScreen.kt").readText()
        val iosModel = readIos("NativePikoModel.swift")
        val iosReceive = readIos("NativeReceiveView.swift")
        val iosProject = File(rootDir, "ios/Piko.xcodeproj/project.pbxproj").readText()
        val iosEntitlements = File(rootDir, "ios/Piko.entitlements")

        assertTrue("myDevices = emptyList()" in androidState)
        assertTrue("var myDevices: [NativeSendDevice] { [] }" in iosModel)
        assertTrue("name: nickname.title" in iosModel)
        assertTrue("subtitle: nickname.code" in iosModel)
        assertTrue("name: currentServiceName" in iosModel)
        assertTrue("nickname.fingerprint != self.nickname.fingerprint" in iosModel)
        assertFalse("name: currentDeviceName" in iosModel)
        assertFalse("UIDevice.current.name" in iosModel)
        assertFalse("identifierForVendor" in iosModel)
        assertFalse("iPhone A567" in iosModel)
        assertFalse(iosEntitlements.isFile)
        assertFalse("CODE_SIGN_ENTITLEMENTS = Piko.entitlements;" in iosProject)
        assertFalse("Settings.Global.DEVICE_NAME" in androidApp)
        assertFalse("Build.MODEL" in androidApp)
        assertTrue("LucideSmartphoneIcon" in androidReceive)
        assertTrue("LucideRefreshCwIcon" in androidReceive)
        assertTrue("text = nickname" in androidReceive)
        assertTrue("text = \"本设备名称\"" in androidReceive)
        assertTrue("换个昵称" in androidReceive)
        assertTrue("LucideTabIcon.smartphone.image" in iosReceive)
        assertTrue("LucideTabIcon.refreshCw.image" in iosReceive)
        assertTrue("Text(nickname)" in iosReceive)
        assertTrue("Text(\"本设备名称\")" in iosReceive)
        assertFalse("Image(uiImage: LucideTabIcon.inbox.image)" in iosReceive.substringBefore("private struct NativeReceiveEmptyState"))
        assertFalse("Image(systemName:" in iosReceive)
        assertTrue("换个昵称" in iosReceive)
        assertTrue("subtitle = nickname.code" in androidDiscovery)
        assertFalse("subtitle = resolvedService.host?.hostAddress" in androidDiscovery)
    }

    @Test
    fun receivePagesUseCompactActiveProgressAndMediaPreview() {
        val androidReceive = File(rootDir, "android/src/main/kotlin/com/piko/app/ReceiveScreen.kt").readText()
        val androidState = File(rootDir, "android/src/main/kotlin/com/piko/app/PikoHomeState.kt").readText()
        val androidDiscovery = File(rootDir, "android/src/main/kotlin/com/piko/app/AndroidSendPlatformActions.kt").readText()
        val androidLocalSendServer = File(rootDir, "android/src/main/kotlin/com/piko/app/LocalSendHttpServer.kt").readText()
        val iosModel = readIos("NativePikoModel.swift")
        val iosReceive = readIos("NativeReceiveView.swift")

        assertFalse("PikoInfoPill(text = \"最近接收\"" in androidReceive)
        assertFalse("PikoPill(text: \"最近接收\"" in iosReceive)
        assertFalse("CircularProgressIndicator" in androidReceive)
        assertFalse("Circle()\n                .trim" in iosReceive)
        assertTrue("RoundedRectProgressIndicator" in androidReceive)
        assertTrue("RoundedRectangle(cornerRadius: 18, style: .continuous)\n                .trim" in iosReceive)
        assertTrue("style = MaterialTheme.typography.bodyLarge" in androidReceive)
        assertTrue(".font(PikoFont.compactTitle)" in iosReceive)
        assertTrue(".offset(x = (-8).dp)" in androidReceive)
        assertTrue(".offset(x: -8)" in iosReceive)
        assertTrue("mediaPreviewDescription" in androidState)
        assertTrue("isMediaPreview" in androidState)
        assertTrue("MediaThumbnailPreview" in androidReceive)
        assertTrue("mediaPreviewData" in iosModel)
        assertTrue("NativeMediaPreview" in iosReceive)
        assertTrue("AVAssetImageGenerator" in iosModel)
        assertTrue("file.fileType.isMediaPreview" in androidDiscovery)
        assertTrue("file.fileType.isMediaPreview" in androidLocalSendServer)
    }

    @Test
    fun iosReceiveFileListUsesNativeSwiftUIListAndKeepsRequiredControls() {
        val androidReceive = File(rootDir, "android/src/main/kotlin/com/piko/app/ReceiveScreen.kt").readText()
        val iosReceive = readIos("NativeReceiveView.swift")

        assertTrue("modifier = Modifier.weight(1f)" in androidReceive)
        assertTrue("List {" in iosReceive)
        assertTrue(".listStyle(.plain)" in iosReceive)
        assertTrue("ForEach(model.receiveHistory, id: \\.id)" in iosReceive)
        assertTrue("nativeReceiveFileListRow()" in iosReceive)
        assertTrue(".listRowSeparator(.visible)" in iosReceive)
        assertTrue(".swipeActions(edge: .trailing, allowsFullSwipe: false)" in iosReceive)
        assertTrue("Button(\"删除\", role: .destructive)" in iosReceive)
        assertTrue("NativeReceiveHistoryRow(item: item)" in iosReceive)
        assertTrue("NativeReceiveHistoryPreview(item: item)" in iosReceive)
        assertTrue("NativeActiveReceiveRow(" in iosReceive)
        assertTrue("NativeActiveReceiveProgressIcon(transfer: transfer)" in iosReceive)
        assertTrue("ProgressView(value: transfer.progress)" in iosReceive)
        assertTrue("Button(action: onCancel)" in iosReceive)
        assertTrue("Text(transfer.title)" in iosReceive)
        assertTrue("Text(transfer.subtitle)" in iosReceive)
        assertTrue("Text(item.title)" in iosReceive)
        assertTrue("Text(item.subtitle)" in iosReceive)
        assertTrue("private struct NativeReceiveTextColumn<Content: View>: View" in iosReceive)
        assertTrue(".frame(maxWidth: .infinity, alignment: .leading)" in iosReceive)
        assertTrue(".layoutPriority(1)" in iosReceive)
        assertFalse("ScrollView(showsIndicators: false)" in iosReceive)
        assertFalse("LazyVStack" in iosReceive)
        assertFalse("NativeReceiveHistoryCard" in iosReceive)
        assertFalse("NativeActiveReceiveCard" in iosReceive)
        assertFalse("rowView(bottom: index < model.receiveHistory.count - 1" in iosReceive)
        assertFalse("static let historyRowSpacing" in iosReceive)
        assertFalse("NativeReceiveSwipeRow" in iosReceive)
        assertFalse("DragGesture(minimumDistance: 12)" in iosReceive)
        assertFalse("UIViewControllerRepresentable" in iosReceive)
        assertFalse("UITableView" in iosReceive)
        assertFalse("UIHostingController" in iosReceive)
        assertFalse("NativeReceiveTableRow" in iosReceive)
        assertFalse("performBatchUpdates" in iosReceive)
        assertFalse("UIContextualAction" in iosReceive)
        assertFalse("cachedEmptyStateRowHeight" in iosReceive)
        assertFalse("historyEstimatedHeight" in iosReceive)
    }

    @Test
    fun receiveHistoryPersistsAndIosSavesInDocumentsRoot() {
        val androidApp = File(rootDir, "android/src/main/kotlin/com/piko/app/AndroidPikoApp.kt").readText()
        val androidState = File(rootDir, "android/src/main/kotlin/com/piko/app/PikoHomeState.kt").readText()
        val androidStore = File(rootDir, "android/src/main/kotlin/com/piko/app/ReceiveHistoryStore.kt").readText()
        val iosModel = readIos("NativePikoModel.swift")

        assertTrue("ReceiveHistoryStore.fromContext(appContext)" in androidApp)
        assertTrue("receiveHistory = receiveHistoryStore.load()" in androidApp)
        assertTrue("receiveHistoryStore.save(nextState.receiveHistory)" in androidApp)
        assertTrue("receiveHistory: List<ReceiveHistoryItem> = emptyList()" in androidState)
        assertTrue("receive_history.json" in androidStore)
        assertTrue("NativeReceiveHistoryStore.load()" in iosModel)
        assertTrue("NativeReceiveHistoryStore.save(receiveHistory)" in iosModel)
        assertTrue("FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]" in iosModel)
        assertFalse(".appendingPathComponent(\"Piko\", isDirectory: true)" in iosModel)
    }

    @Test
    fun receiveHistoryDeletionUsesSwipeConfirmationAndSavedFileReferencesOnBothPlatforms() {
        val androidApp = File(rootDir, "android/src/main/kotlin/com/piko/app/AndroidPikoApp.kt").readText()
        val androidReceive = File(rootDir, "android/src/main/kotlin/com/piko/app/ReceiveScreen.kt").readText()
        val androidState = File(rootDir, "android/src/main/kotlin/com/piko/app/PikoHomeState.kt").readText()
        val androidStore = File(rootDir, "android/src/main/kotlin/com/piko/app/ReceiveHistoryStore.kt").readText()
        val androidLocalSendServer = File(rootDir, "android/src/main/kotlin/com/piko/app/LocalSendHttpServer.kt").readText()
        val androidLegacyReceiver = File(rootDir, "android/src/main/kotlin/com/piko/app/AndroidSendPlatformActions.kt").readText()
        val iosReceive = readIos("NativeReceiveView.swift")
        val normalizedIosReceive = iosReceive.replace("\r\n", "\n")
        val iosModel = readIos("NativePikoModel.swift")

        assertTrue("detectHorizontalDragGestures" in androidReceive)
        assertTrue("val deleteWidth = 96.dp" in androidReceive)
        assertTrue("val deleteButtonOffset" in androidReceive)
        assertTrue(".width(deleteWidth)" in androidReceive)
        assertTrue(".clip(RoundedCornerShape(20.dp))" in androidReceive)
        assertTrue("copy(alpha = revealFraction)" in androidReceive)
        assertFalse("targetOffset" in androidReceive)
        assertFalse("val revealedWidth" in androidReceive)
        assertFalse(".background(MaterialTheme.colorScheme.error.copy(alpha = 0.28f" in androidReceive)
        assertTrue("DeleteReceiveHistoryDialog" in androidReceive)
        assertTrue("AlertDialog(" in androidReceive)
        assertTrue("onDeleteReceiveHistory(history, false)" in androidReceive)
        assertTrue("onDeleteReceiveHistory(history, true)" in androidReceive)
        assertTrue("仅删除记录" in androidReceive)
        assertTrue("删除记录与文件" in androidReceive)
        assertTrue("Column(horizontalAlignment = Alignment.End)" in androidReceive)
        assertInOrder(androidReceive, "Text(text = \"算了\")", "Text(text = \"仅删除记录\")", "text = \"删除记录与文件\"")
        assertFalse("Checkbox(" in androidReceive)
        assertFalse("Dialog(onDismissRequest = onDismiss)" in androidReceive)
        assertFalse("同时删除文件" in androidReceive)
        assertTrue("算了" in androidReceive)
        assertTrue("删除" in androidReceive)
        assertTrue("真的要删除" in androidState)
        assertTrue("将会删除：" in androidState)
        assertTrue("removeReceiveHistory" in androidState)
        assertTrue("savedUri" in androidStore)
        assertTrue("savedUri = uri.toString()" in androidLocalSendServer)
        assertTrue("savedUri = uri.toString()" in androidLegacyReceiver)
        assertTrue("contentResolver.delete(Uri.parse(savedUri), null, null)" in androidApp)

        assertTrue("static let pageHorizontalInset: CGFloat = 24" in iosReceive)
        assertTrue("static let contentTrailingInset: CGFloat = 0" in iosReceive)
        assertTrue("static let bottomSpacerHeight: CGFloat = 112" in iosReceive)
        assertTrue("ForEach(model.receiveHistory, id: \\.id)" in iosReceive)
        assertTrue("NativeReceiveHistoryRow(item: item)" in iosReceive)
        assertTrue(".nativeReceiveFileListRow()" in iosReceive)
        assertFalse("swipeEditingIndexPath" in iosReceive)
        assertFalse("NativeSwipeToDeleteReceiveHistoryCard" in iosReceive)
        assertFalse("NativeReceiveHistoryCard" in iosReceive)
        assertFalse("NativeActiveReceiveCard" in iosReceive)
        assertFalse("static let historyRowSpacing" in iosReceive)
        assertFalse("let deleteWidth: CGFloat = 96" in iosReceive)
        assertFalse("DragGesture(minimumDistance: 12)" in iosReceive)
        assertTrue(".swipeActions(edge: .trailing, allowsFullSwipe: false)" in iosReceive)
        assertTrue("Button(\"删除\", role: .destructive)" in iosReceive)
        assertTrue("pendingDeleteHistory = item" in iosReceive)
        assertTrue("confirmDelete(item, deleteFiles: false)" in iosReceive)
        assertTrue("confirmDelete(item, deleteFiles: true)" in iosReceive)
        assertTrue("仅删除记录" in iosReceive)
        assertTrue("删除记录与文件" in iosReceive)
        assertInOrder(iosReceive, "Button(\"算了\"", "Button(\"仅删除记录\"", "Button(\"删除记录与文件\"")
        assertTrue("List {" in iosReceive)
        assertFalse("NativeReceiveSwipeRow" in iosReceive)
        assertFalse("pendingDeleteItem" in iosReceive)
        assertFalse("UIImage(systemName:" in iosReceive)
        assertFalse("action.image" in iosReceive)
        assertFalse("NativeDeleteReceiveHistoryDialog" in iosReceive)
        assertFalse("deleteReceivedFiles" in iosReceive)
        assertFalse("同时删除文件" in iosReceive)
        assertTrue("deleteReceiveHistory" in iosModel)
        assertTrue("savedURLPath" in iosModel)
        assertTrue("photoAssetIdentifier" in iosModel)
        assertTrue("PHPhotoLibrary.requestAuthorization(for: .readWrite)" in iosModel)
        assertTrue("FileManager.default.removeItem(atPath: path)" in iosModel)
        assertTrue("真的要删除" in iosModel)
        assertTrue("将会删除：" in iosModel)
    }

    @Test
    fun iosReceiveRootUsesPageBackgroundThroughSystemBars() {
        val iosRoot = readIos("PikoRootView.swift")
        val iosReceive = readIos("NativeReceiveView.swift")
        val iosStyle = readIos("PikoStyle.swift")

        assertTrue("static let pageBackgroundUIColor = surfaceUIColor" in iosStyle)
        assertTrue("PikoPalette.pageBackgroundUIColor" in iosRoot)
        assertTrue("view.window?.backgroundColor = PikoPalette.pageBackgroundUIColor" in iosRoot)
        assertTrue("view.superview?.backgroundColor = PikoPalette.pageBackgroundUIColor" in iosRoot)
        assertTrue("safeAreaRegions = []" in iosRoot)
        assertTrue(".ignoresSafeArea(.container, edges: [.top, .bottom])" in iosReceive)
        assertTrue("PikoPalette.pageBackground.ignoresSafeArea()" in iosReceive)
        assertTrue("List {" in iosReceive)
        assertTrue(".listStyle(.plain)" in iosReceive)
        assertTrue("static let deviceNicknameBottomSpacing: CGFloat = 8" in iosReceive)
        assertTrue("static let deviceNicknameVerticalPadding: CGFloat = 9" in iosReceive)
        assertTrue("static let emptyStateTopSpacing: CGFloat = 24" in iosReceive)
        assertTrue("static let emptyStateBottomSpacing: CGFloat = 112" in iosReceive)
        assertTrue("static let emptyStateMinimumContentHeight: CGFloat = 164" in iosReceive)
        assertTrue("emptyStateCardView(" in iosReceive)
        assertTrue("private struct NativeReceiveEmptyStateContent: View" in iosReceive)
        assertTrue(".background(PikoPalette.pageBackground)" in iosReceive)
        assertFalse("tableView.backgroundColor = PikoPalette.pageBackgroundUIColor" in iosReceive)
        assertFalse("tableView.contentInsetAdjustmentBehavior = .never" in iosReceive)
        assertFalse("static func emptyStateRowHeight(for tableHeight: CGFloat)" in iosReceive)
        assertFalse("emptyStateHeightCacheKeyPrefix" in iosReceive)
        assertFalse("let emptyStateShape = RoundedRectangle(cornerRadius: 24, style: .continuous)" in iosReceive)
        assertFalse(".fill(Color.secondary.opacity(0.08))" in iosReceive)
        assertFalse(".clipShape(emptyStateShape)" in iosReceive)
        assertTrue(".frame(maxWidth: .infinity)" in iosReceive)
        assertTrue(".frame(minHeight: NativeReceiveLayout.emptyStateMinimumContentHeight)" in iosReceive)
        assertFalse("static let emptyStateRowHeight: CGFloat = 300" in iosReceive)
        assertFalse("emptyStateEstimatedHeight" in iosReceive)
        assertFalse("PikoEmptyPlane(text: \"还没有接收过文件\")" in iosReceive)
        assertFalse(".frame(maxWidth: .infinity, height: height, alignment: .top)" in iosReceive)
        assertFalse("rowView(bottom: 136)" in iosReceive)
        assertFalse("return 156" in iosReceive)
        assertFalse("return 82" in iosReceive)
        assertTrue(".strokeBorder(Color.secondary.opacity(0.16), lineWidth: 1)" in iosReceive)
        assertFalse(".stroke(Color.secondary.opacity(0.16), lineWidth: 1)" in iosReceive)
    }

    @Test
    fun appTextUsesAdaptiveTypographyForSmallAndLargeScreens() {
        val androidTheme = File(rootDir, "android/src/main/kotlin/com/piko/app/PikoTheme.kt").readText()
        val androidApp = File(rootDir, "android/src/main/kotlin/com/piko/app/AndroidPikoApp.kt").readText()
        val androidSettings = File(rootDir, "android/src/main/kotlin/com/piko/app/SettingsScreen.kt").readText()
        val iosStyle = readIos("PikoStyle.swift")
        val iosRoot = readIos("PikoRootView.swift")
        val iosReceive = readIos("NativeReceiveView.swift")
        val iosSendTransfer = readIos("NativeSendTransferSection.swift")
        val iosSendDevice = readIos("NativeSendDeviceSection.swift")
        val iosSendItem = readIos("NativeSendItemSection.swift")
        val iosSettings = readIos("NativeSettingsView.swift")
        val iosAppText = iosRoot + iosReceive + iosSendTransfer + iosSendDevice + iosSendItem + iosSettings

        assertTrue("internal object PikoTypography" in androidTheme)
        assertTrue("LocalConfiguration.current.screenWidthDp" in androidTheme)
        assertTrue("widthDp <= 375 -> PikoScreenTextScale.Compact" in androidTheme)
        assertTrue("widthDp >= 430 -> PikoScreenTextScale.Expanded" in androidTheme)
        assertTrue("Compact(0.92f)" in androidTheme)
        assertTrue("Expanded(1.06f)" in androidTheme)
        assertTrue("typography = PikoTypography.current()" in androidTheme)
        assertFalse("typography = MaterialTheme.typography" in androidTheme)
        assertTrue("maxLines = 1" in androidApp)
        assertTrue("overflow = TextOverflow.Ellipsis" in androidApp)
        assertTrue("maxLines = 1" in androidSettings)
        assertTrue("overflow = TextOverflow.Ellipsis" in androidSettings)

        assertTrue("enum PikoFont" in iosStyle)
        assertTrue("UIScreen.main.bounds" in iosStyle)
        assertTrue("case compact" in iosStyle)
        assertTrue("case regular" in iosStyle)
        assertTrue("case expanded" in iosStyle)
        assertTrue("compact: return 0.92" in iosStyle)
        assertTrue("expanded: return 1.06" in iosStyle)
        assertTrue("static var pageTitle" in iosStyle)
        assertTrue("static var rowTitle" in iosStyle)
        assertTrue("static var pill" in iosStyle)
        assertTrue("static var tabLabel" in iosStyle)
        assertFalse("textStyle: .caption," in iosStyle)
        assertTrue("textStyle: .caption1" in iosStyle)
        assertFalse(".font(.largeTitle" in iosStyle)
        assertFalse(".font(.title3" in iosAppText)
        assertTrue(".font(PikoFont.tabLabel)" in iosRoot)
        assertTrue(".font(PikoFont.rowTitle)" in iosReceive)
        assertTrue(".minimumScaleFactor(0.88)" in iosAppText)
        assertTrue(".truncationMode(.tail)" in iosAppText)
    }

    private fun readIos(name: String): String =
        File(rootDir, "ios/$name").readText()

    private fun assertInOrder(source: String, vararg snippets: String) {
        var cursor = 0
        snippets.forEach { snippet ->
            val index = source.indexOf(snippet, cursor)
            assertTrue(index >= 0, "Missing snippet in order: $snippet")
            cursor = index + snippet.length
        }
    }
}
