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
        assertTrue("PikoPill(text: \"最近接收\", emphasized: true)" in receiveView)
        assertTrue("NativeReceiveHistoryCard" in receiveView)
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

        val iosContent = receiveView + sendItemSection + sendDeviceSection + transferSection
        listOf("inbox", "file", "image", "plus", "x", "check").forEach { name ->
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
        assertTrue("本设备名称：" in androidReceive)
        assertTrue("换个昵称" in androidReceive)
        assertTrue("本设备名称：" in iosReceive)
        assertTrue("换个昵称" in iosReceive)
        assertTrue("subtitle = nickname.code" in androidDiscovery)
        assertFalse("subtitle = resolvedService.host?.hostAddress" in androidDiscovery)
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
