package com.piko.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemBarConfigurationTest {
    private val rootDir: File
        get() = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun androidWindowKeepsSystemBarsTransparentWithoutHidingThem() {
        val mainActivity = File(rootDir, "android/src/main/kotlin/com/piko/app/MainActivity.kt").readText()
        val styles = File(rootDir, "android/src/main/res/values/styles.xml").readText()

        assertTrue("enableEdgeToEdge(" in mainActivity)
        assertTrue("SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)" in mainActivity)
        assertTrue("WindowCompat.setDecorFitsSystemWindows(window, false)" in mainActivity)
        assertTrue("window.statusBarColor = Color.TRANSPARENT" in mainActivity)
        assertTrue("window.navigationBarColor = Color.TRANSPARENT" in mainActivity)
        assertTrue("window.isStatusBarContrastEnforced = false" in mainActivity)
        assertTrue("window.isNavigationBarContrastEnforced = false" in mainActivity)
        assertTrue("""android:statusBarColor">@android:color/transparent""" in styles)
        assertTrue("""android:navigationBarColor">@android:color/transparent""" in styles)
        assertTrue("""android:enforceStatusBarContrast">false""" in styles)
        assertTrue("""android:enforceNavigationBarContrast">false""" in styles)
        assertFalse("hide(WindowInsetsCompat.Type.systemBars())" in mainActivity)
    }

    @Test
    fun iosSystemBarsUseSystemDefaults() {
        val swift = File(rootDir, "ios/PikoApp.swift").readText()
        val root = File(rootDir, "ios/PikoRootView.swift").readText()

        assertTrue("WindowGroup" in swift)
        assertTrue("TabView(selection:" in root)
        assertTrue("NavigationStack" in root)
        assertFalse("UITabBarAppearance" in swift)
        assertFalse("UINavigationBarAppearance" in swift)
        assertFalse("toolbarBackground(.hidden" in swift)
    }
}
