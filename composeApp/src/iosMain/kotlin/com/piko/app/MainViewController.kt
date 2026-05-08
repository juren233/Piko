package com.piko.app

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController {
    App(sendPlatformActions = rememberIosSendPlatformActions())
}

fun MainViewController(tabName: String) = MainViewController(tabName = tabName, sendOverlayController = null)

fun MainViewController(
    tabName: String,
    sendOverlayController: SendOverlayController?,
) = ComposeUIViewController {
    App(
        tab = PikoTab.entries.firstOrNull { tab -> tab.name == tabName } ?: PikoTab.Receive,
        sendPlatformActions = rememberIosSendPlatformActions(),
        sendOverlayController = sendOverlayController,
    )
}
