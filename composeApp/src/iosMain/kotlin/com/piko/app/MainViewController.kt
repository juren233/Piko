package com.piko.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIDevice

fun MainViewController() = ComposeUIViewController {
    App(
        currentDeviceName = UIDevice.currentDevice.name,
        sendPlatformActions = rememberIosSendPlatformActions(),
    )
}

fun MainViewController(tabName: String) = MainViewController(tabName = tabName, sendOverlayController = null)

fun MainViewController(
    tabName: String,
    sendOverlayController: SendOverlayController?,
) = ComposeUIViewController {
    App(
        tab = PikoTab.entries.firstOrNull { tab -> tab.name == tabName } ?: PikoTab.Receive,
        currentDeviceName = UIDevice.currentDevice.name,
        sendPlatformActions = rememberIosSendPlatformActions(),
        sendOverlayController = sendOverlayController,
    )
}
