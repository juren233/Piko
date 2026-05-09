package com.piko.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDeviceNameTest {
    @Test
    fun systemDeviceNameWinsOverHardwareModel() {
        val name = resolveAndroidDeviceName(
            systemDeviceName = "Cavan's Xiaomi 15",
            manufacturer = "Xiaomi",
            model = "24129PN74C",
        )

        assertEquals("Cavan's Xiaomi 15", name)
    }

    @Test
    fun blankSystemDeviceNameFallsBackToManufacturerAndModel() {
        val name = resolveAndroidDeviceName(
            systemDeviceName = "   ",
            manufacturer = "Google",
            model = "Pixel 9 Pro",
        )

        assertEquals("Google Pixel 9 Pro", name)
    }

    @Test
    fun duplicateManufacturerModelUsesModelOnce() {
        val name = resolveAndroidDeviceName(
            systemDeviceName = null,
            manufacturer = "Xiaomi",
            model = "Xiaomi 15",
        )

        assertEquals("Xiaomi 15", name)
    }
}
