package com.piko.app

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController {
    App()
}

fun MainViewController(tabName: String) = ComposeUIViewController {
    App(tab = PikoTab.valueOf(tabName))
}
