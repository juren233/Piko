package com.piko.app

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.unit.dp
import platform.UIKit.UIDevice

fun MainViewController() = ComposeUIViewController {
    App(
        currentDeviceName = UIDevice.currentDevice.name,
        sendPlatformActions = rememberIosSendPlatformActions(),
        bottomContentPadding = 92.dp,
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
        bottomContentPadding = 92.dp,
    )
}
