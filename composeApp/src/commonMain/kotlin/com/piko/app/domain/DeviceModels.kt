package com.piko.app.domain

enum class DeviceTrustLevel {
    Standard,
    Trusted,
}

enum class DevicePlatform {
    Android,
    IOS,
    Windows,
    MacOS,
}

data class PikoDevice(
    val id: String,
    val name: String,
    val platform: DevicePlatform,
    val trustLevel: DeviceTrustLevel,
    val revoked: Boolean,
    val receiveDirectoryConfigured: Boolean,
)

