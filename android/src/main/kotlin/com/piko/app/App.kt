package com.piko.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class PikoTab(
    val title: String,
) {
    Receive("接收"),
    Send("发送"),
    Settings("设置"),
}

@Composable
fun App(
    tab: PikoTab = PikoTab.Receive,
    currentDeviceName: String = "当前设备",
    onResetCurrentDeviceName: () -> Unit = {},
    sendPlatformActions: SendPlatformActions = SendPlatformActions.Empty,
    bottomContentPadding: Dp = 0.dp,
) {
    var state by remember(currentDeviceName) { mutableStateOf(PikoHomeState.initial(currentDeviceName)) }
    var mediaSaveLocation by remember { mutableStateOf(ReceiveMediaSaveLocation.Folder) }

    PikoTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            PikoTabScreen(
                tab = tab,
                state = state,
                onStateMutate = { transform -> state = transform(state) },
                onResetCurrentDeviceName = onResetCurrentDeviceName,
                mediaSaveLocation = mediaSaveLocation,
                onMediaSaveLocationChange = { mediaSaveLocation = it },
                sendPlatformActions = sendPlatformActions,
                bottomContentPadding = bottomContentPadding,
            )
        }
    }
}

@Composable
fun PikoTabScreen(
    tab: PikoTab,
    state: PikoHomeState,
    onStateMutate: ((PikoHomeState) -> PikoHomeState) -> Unit,
    onResetCurrentDeviceName: () -> Unit = {},
    mediaSaveLocation: ReceiveMediaSaveLocation = ReceiveMediaSaveLocation.Folder,
    onMediaSaveLocationChange: (ReceiveMediaSaveLocation) -> Unit = {},
    sendPlatformActions: SendPlatformActions = SendPlatformActions.Empty,
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    when (tab) {
        PikoTab.Receive -> PikoReceiveScreen(
            state = state,
            onStateMutate = onStateMutate,
            onResetCurrentDeviceName = onResetCurrentDeviceName,
            sendPlatformActions = sendPlatformActions,
            bottomContentPadding = bottomContentPadding,
            modifier = modifier,
        )

        PikoTab.Send -> PikoSendScreen(
            sendPage = state.sendPage,
            onStateMutate = onStateMutate,
            sendPlatformActions = sendPlatformActions,
            bottomContentPadding = bottomContentPadding,
            modifier = modifier,
        )

        PikoTab.Settings -> PikoSettingsScreen(
            mediaSaveLocation = mediaSaveLocation,
            onMediaSaveLocationChange = onMediaSaveLocationChange,
            bottomContentPadding = bottomContentPadding,
            modifier = modifier,
        )
    }
}

fun startSendTransfer(
    sendPage: SendPageState,
    senderName: String,
    onStateMutate: ((PikoHomeState) -> PikoHomeState) -> Unit,
    sendPlatformActions: SendPlatformActions,
) {
    val request = sendPage.buildTransferRequest(senderName = senderName.substringBefore("@")) ?: return
    sendPlatformActions.startTransfer(request) { event ->
        onStateMutate { state ->
            state.copy(sendPage = state.sendPage.applyTransferEvent(event))
        }
    }
}
