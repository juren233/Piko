package com.piko.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeSystemUiContractTest {
    private val rootDir: File
        get() = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun iosReceiveAndAppShellUseSwiftUiFirstSystemNavigation() {
        val app = readIos("PikoApp.swift")
        val root = readIos("PikoRootView.swift")
        val receive = readIos("NativeReceiveView.swift")

        assertFalse("ImmersiveRootView" in app)
        assertFalse("ImmersiveRootView" in root)
        assertFalse("prefersHomeIndicatorAutoHidden" in root)
        assertFalse("PikoCollapsingTopBar" in root)
        assertFalse("UIViewControllerRepresentable" in receive)
        assertFalse("UITableViewController" in receive)
        assertFalse("UIHostingConfiguration" in receive)

        assertTrue("NavigationStack" in root)
        assertTrue(".navigationTitle(\"Piko\")" in receive)
        assertTrue("List" in receive)
        assertTrue("ContentUnavailableView" in receive)
    }

    @Test
    fun iosPrimaryFlowsDoNotCompileLegacyCustomUiChrome() {
        val project = File(rootDir, "ios/Piko.xcodeproj/project.pbxproj").readText()
        val removedLegacyFiles = listOf(
            "PikoStyle.swift",
            "NativeSendDeviceSection.swift",
            "NativeSendItemSection.swift",
            "NativeSendTransferSection.swift",
        )
        removedLegacyFiles.forEach { fileName ->
            assertFalse(File(rootDir, "ios/$fileName").exists(), "$fileName should be removed from the native system UI surface")
            assertFalse(fileName in project, "$fileName should not be compiled by the iOS project")
        }

        val primaryUiSources = listOf(
            "PikoRootView.swift",
            "NativeReceiveView.swift",
            "NativeSendView.swift",
            "NativeSettingsView.swift",
            "NativeLoginView.swift",
            "NativeRegisterView.swift",
            "NativeFriendsView.swift",
            "NativeFriendRequestsView.swift",
        ).joinToString("\n") { readIos(it) }

        listOf(
            "PikoFont",
            "PikoPalette",
            "PikoSectionPanel",
            "PikoCollapsingPageHeroHeader",
            "LucideTabIcon",
            "UIViewControllerRepresentable",
            "UITableViewController",
            "UIHostingConfiguration",
        ).forEach { legacyMarker ->
            assertFalse(legacyMarker in primaryUiSources, "$legacyMarker should not be used by primary iOS UI flows")
        }

        assertTrue("TabView(selection:" in readIos("PikoRootView.swift"))
        assertTrue("List {" in readIos("NativeReceiveView.swift"))
        assertTrue("List {" in readIos("NativeSendView.swift"))
        assertTrue("Form {" in readIos("NativeSettingsView.swift"))
        assertTrue("Form {" in readIos("NativeLoginView.swift"))
        assertTrue("Form {" in readIos("NativeRegisterView.swift"))
        assertTrue("List {" in readIos("NativeFriendsView.swift"))
        assertTrue("List {" in readIos("NativeFriendRequestsView.swift"))
    }

    @Test
    fun androidDefaultShellUsesPlainMiuixFeatureScreensInsteadOfOldUiChrome() {
        val runtime = readAndroid("platform/AndroidPikoApp.kt")
        val shell = readAndroid("app/PikoAndroidAppShell.kt")
        val theme = readAndroid("design/PikoMiuixTheme.kt")
        val receive = readAndroid("feature/receive/ReceiveRoute.kt")
        val send = readAndroid("feature/send/SendRoute.kt")
        val settings = readAndroid("feature/settings/SettingsRoute.kt")
        val friends = readAndroid("feature/friends/FriendsRoute.kt")
        val versionCatalog = File(rootDir, "gradle/libs.versions.toml").readText()
        val androidBuild = File(rootDir, "android/build.gradle.kts").readText()
        val defaultPath = listOf(runtime, shell, theme, receive, send, settings, friends).joinToString("\n")
        val glassDir = File(rootDir, "android/src/main/kotlin/com/piko/app/glass")

        val legacyUiDir = File(rootDir, "android/src/main/kotlin/com/piko/app/ui")
        assertFalse(legacyUiDir.exists() && legacyUiDir.walkTopDown().any { it.isFile })
        assertFalse(glassDir.exists() && glassDir.walkTopDown().any { it.isFile })
        assertTrue("PikoAndroidAppShell(" in runtime)
        assertTrue("com.piko.app.app.PikoAndroidAppShell" in runtime)
        assertTrue("miuix = \"0.8.8\"" in versionCatalog)
        assertTrue("compose = \"1.10.3\"" in versionCatalog)
        assertTrue("miuix-android" in versionCatalog)
        assertTrue("miuix-icons-android" in versionCatalog)
        assertTrue("implementation(libs.miuix)" in androidBuild)
        assertTrue("implementation(libs.miuix.icons)" in androidBuild)
        assertFalse("implementation(platform(libs.androidx.compose.bom))" in androidBuild)
        assertFalse("composeBom = \"2026.05.00\"" in versionCatalog)
        assertFalse("compose-material3" in versionCatalog)
        assertFalse("implementation(libs.compose.material3)" in androidBuild)

        assertTrue("PikoMiuixTheme" in shell)
        assertTrue("NavigationBar(" in shell)
        assertTrue("NavigationBarItem(" in shell)
        assertFalse("PikoLiquidGlassBackdropHost" in shell)
        assertFalse("InstallerXFloatingBottomBar(" in shell)
        assertFalse("InstallerXFloatingBottomBarItem(" in shell)
        assertFalse("PikoGlassNavigationBar(" in shell)
        assertFalse("PikoGlassNavigationItem(" in shell)
        assertFalse("state.sendPage.selectedTransferItems.isNotEmpty()" in shell)
        assertFalse("if (destination == PikoDestination.Send) {" in shell)
        assertTrue("ReceiveRoute(" in shell)
        assertTrue("SendRoute(" in shell)
        assertTrue("SettingsRoute(" in shell)
        assertTrue("FriendsRoute(" in shell)

        listOf(
            "com.piko.app.glass",
            "com.piko.app.ui",
            "PikoMaterial",
            "androidx.compose.material3",
            "LiquidBottomTabs",
            "LiquidBottomTab",
            "LiquidSendFloatingButton",
            "PikoTabScreen",
            "PikoReceiveScreen",
            "PikoSendScreen",
            "PikoSettingsScreen",
            "pikoPageBrush",
            "PikoHeroPanel",
            "PikoSectionPanel",
            "RoundedRectProgressIndicator",
            "androidx.compose.material3.LargeTopAppBar",
            "androidx.compose.material3.ElevatedCard",
            "androidx.compose.material3.ListItem",
            "androidx.compose.material3.FilterChip",
            "androidx.compose.material3.AlertDialog",
        ).forEach { legacyMarker ->
            assertFalse(legacyMarker in defaultPath, "$legacyMarker should not be in the default Android runtime UI path")
        }

        assertFalse("IOS_SYSTEM_BLUE" in theme)
        assertFalse("IOS_SYSTEM_BACKGROUND" in theme)

        assertTrue("MiuixTheme(" in theme)
        assertTrue("ThemeController(" in theme)
        assertTrue("top.yukonga.miuix.kmp" in defaultPath)
        assertTrue("TopAppBar(" in shell)
        assertTrue("Card(" in receive)
        assertTrue("BasicComponent(" in receive)
        assertTrue("Card(" in send)
        assertTrue("BasicComponent(" in send)
        assertTrue("SuperArrow(" in settings)
        assertTrue("SuperRadioButton(" in settings)
        assertTrue("SearchBar(" in friends)
        assertTrue("SuperDialog(" in shell)
        assertTrue("SuperDialog(" in receive)
        assertTrue("SuperDialog(" in send)
    }

    @Test
    fun androidSendActionLivesInsideSummaryCardInsteadOfFloatingOverContent() {
        val shell = readAndroid("app/PikoAndroidAppShell.kt")
        val send = readAndroid("feature/send/SendRoute.kt")

        assertFalse("floatingActionButton =" in shell)
        assertFalse("PikoGlassSendAction(" in shell)
        assertFalse("state.sendPage.selectedTransferItems.isNotEmpty()" in shell)

        assertTrue("onStartSendTransfer: () -> Unit" in send)
        assertTrue("SendSummaryCard(\n                sendPage = sendPage,\n                onSend = onStartSendTransfer," in send)
        assertTrue("private fun SendSummaryCard(\n    sendPage: SendPageState,\n    onSend: () -> Unit," in send)
        assertTrue("endActions = {" in send)
        assertTrue("if (sendPage.selectedTransferItems.isNotEmpty()) {" in send)
        assertTrue("enabled = sendPage.canSend" in send)
        assertTrue("onClick = onSend" in send)
        assertTrue("Text(\"发送\")" in send)
    }

    @Test
    fun androidDeviceSelectionPillMatchesDirectPathChipHeight() {
        val send = readAndroid("feature/send/SendRoute.kt")
        val components = readAndroid("design/PikoMiuixComponents.kt")

        assertTrue("verticalAlignment = Alignment.CenterVertically" in send)
        assertTrue("private fun SelectionPill(" in send)
        assertTrue("style = MiuixTheme.textStyles.body2" in send)
        assertTrue("modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)" in send)

        assertTrue("style = MiuixTheme.textStyles.body2" in components)
        assertTrue("modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)" in components)
    }

    @Test
    fun receiveDeviceRenameButtonsUseUnifiedCopyAndNativeSwiftUiControl() {
        val androidReceive = readAndroid("feature/receive/ReceiveRoute.kt")
        val iosReceive = readIos("NativeReceiveView.swift")

        assertTrue("TextButton(\n                    text = \"更换\"," in androidReceive)
        assertFalse("text = \"重置\"" in androidReceive)

        assertTrue("ReceiveStatusSummary(model: model)" in iosReceive)
        assertTrue("Image(systemName: \"iphone\")" in iosReceive)
        assertTrue("Button(\"更换\", action: model.resetDeviceNickname)" in iosReceive)
        assertTrue(".buttonStyle(.bordered)" in iosReceive)
        assertTrue(".controlSize(.small)" in iosReceive)
        assertFalse("Label(\"更换\", systemImage: \"arrow.clockwise\")" in iosReceive)
        assertFalse("LabeledContent(\"本设备名称\")" in iosReceive)
        assertFalse("Button(\"更换\", systemImage: \"arrow.clockwise\", action: model.resetDeviceNickname)" in iosReceive)
        assertFalse("Button(\"换名\"" in iosReceive)
        assertFalse("Label(\"换名\"" in iosReceive)
    }

    @Test
    fun ciPrereleaseBuildsDeriveBetaVersionFromRunNumber() {
        val buildConfig = File(rootDir, ".github/build-config.json").readText()
        val workflow = File(rootDir, ".github/workflows/build-packages.yml").readText()
        val androidBuild = File(rootDir, "android/build.gradle.kts").readText()
        val androidPackageScript = File(rootDir, "scripts/android/build-packages.ps1").readText()
        val localPackageScript = File(rootDir, "scripts/build-mobile.sh").readText()
        val iosPackageScript = File(rootDir, "scripts/ios/build-packages.sh").readText()

        assertTrue("\"betaRunNumberOffset\"" in buildConfig)
        assertTrue("\"betaRunNumberOffset\": 126" in buildConfig)

        assertTrue("$" + "env:GITHUB_RUN_NUMBER" in workflow)
        assertTrue("$" + "config.githubRelease.betaRunNumberOffset" in workflow)
        assertTrue("$" + "effectiveVersionName" in workflow)
        assertTrue("version_name=$" + "effectiveVersionName" in workflow)
        assertTrue("version_code=$" + "effectiveVersionCode" in workflow)
        assertTrue("release_tag=v$" + "effectiveVersionName" in workflow)
        assertTrue("PIKO_VERSION_NAME: $" + "{{ needs.prepare.outputs.version_name }}" in workflow)
        assertTrue("PIKO_VERSION_CODE: $" + "{{ needs.prepare.outputs.version_code }}" in workflow)

        assertTrue("System.getenv(environmentName)" in androidBuild)
        assertTrue("\"PIKO_VERSION_NAME\"" in androidBuild)
        assertTrue("\"PIKO_VERSION_CODE\"" in androidBuild)
        assertTrue("$" + "env:PIKO_VERSION_NAME" in androidPackageScript)
        assertTrue("$" + "env:PIKO_VERSION_CODE" in androidPackageScript)
        assertTrue("PIKO_VERSION_NAME" in localPackageScript)
        assertTrue("PIKO_VERSION_CODE" in localPackageScript)
        assertTrue("PIKO_VERSION_NAME" in iosPackageScript)
        assertTrue("PIKO_VERSION_CODE" in iosPackageScript)
    }

    @Test
    fun androidMiuixPaletteUsesPlainMiuixBottomBarWithoutLiquidGlass() {
        val theme = readAndroid("design/PikoMiuixTheme.kt")
        val shell = readAndroid("app/PikoAndroidAppShell.kt")
        val glassDir = File(rootDir, "android/src/main/kotlin/com/piko/app/glass")

        assertFalse("ColorSchemeMode.MonetSystem" in theme)
        assertFalse("0xFF006A60" in theme)
        assertFalse("0xFF53DBC9" in theme)
        assertTrue("ColorSchemeMode.Light" in theme)
        assertTrue("primary = Color(0xFF3F7DF6)" in theme)
        assertTrue("background = Color(0xFFF7F8FB)" in theme)
        assertTrue("surface = Color(0xFFFFFFFF)" in theme)
        assertTrue("keyColor = Color(0xFF3F7DF6)" in theme)

        assertFalse(glassDir.exists() && glassDir.walkTopDown().any { it.isFile })
        assertTrue("NavigationBar(" in shell)
        assertTrue("NavigationBarItem(" in shell)
        assertTrue("selected = topLevelDestination == item" in shell)
        assertFalse("PikoLiquidGlassBackdropHost" in shell)
        assertFalse("InstallerXFloatingBottomBar(" in shell)
        assertFalse("PikoGlassNavigationBar(" in shell)
        assertFalse("drawBackdrop" in shell)
        assertFalse("rememberLayerBackdrop" in shell)
    }

    @Test
    fun sendPagesUseSystemMediaPickerAndPreviewWaitingAreaOnBothPlatforms() {
        val androidSend = readAndroid("feature/send/SendRoute.kt")
        val androidActions = readAndroid("platform/AndroidSendPlatformActions.kt")
        val androidActionContract = readAndroid("platform/SendPlatformActions.kt")
        val androidState = readAndroid("domain/SendPageState.kt")
        val androidStarter = readAndroid("app/SendTransferStarter.kt")
        val iosSend = readIos("NativeSendView.swift")
        val iosPicker = readIos("NativePickers.swift")
        val iosModel = readIos("NativePikoModel.swift")

        assertTrue("Section(\"图片/视频\")" in iosSend)
        assertTrue("NativeSendMediaPreviewRow" in iosSend)
        assertTrue("NativeMediaPicker" in iosSend)
        assertTrue("configuration.filter = .any(of: [.images, .videos])" in iosPicker)
        assertTrue("configuration.selectionLimit = 0" in iosPicker)
        assertTrue("previewData:" in iosPicker)
        assertTrue("clearSelectedItems()" in iosModel)
        assertFalse("Section(\"图片\")" in iosSend)
        assertFalse("toggleImageSectionExpanded" in iosSend)

        assertTrue("pickMedia" in androidActionContract)
        assertTrue("PickMultipleVisualMedia(30)" in androidActions)
        assertTrue("PickVisualMedia.ImageAndVideo" in androidActions)
        assertTrue("selectedMediaItems" in androidState)
        assertTrue("clearSelectedItems()" in androidState)
        assertTrue("clearSelectedItems()" in androidStarter)
        assertTrue("MediaPreviewRow(" in androidSend)
        assertTrue("title = \"图片/视频\"" in androidSend)
        assertFalse("requestRecentImages" in androidActionContract)
        assertFalse("loadRecentImages" in androidActions)
        assertFalse("visibleImages.forEach" in androidSend)
        assertFalse("onToggleImage" in androidSend)
        assertFalse("toggleImageSelection" in androidState)
        assertFalse("imageSectionExpanded" in androidState)
    }

    private fun readIos(name: String): String = File(rootDir, "ios/$name").readText()

    private fun readAndroid(path: String): String =
        File(rootDir, "android/src/main/kotlin/com/piko/app/$path").readText()
}
