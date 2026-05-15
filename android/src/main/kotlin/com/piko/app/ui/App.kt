package com.piko.app.ui

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
import com.piko.app.data.ReceiveMediaSaveLocation
import com.piko.app.domain.PikoHomeState
import com.piko.app.domain.ReceiveHistoryItem
import com.piko.app.domain.SendPageState
import com.piko.app.domain.SendTransferEvent
import com.piko.app.platform.SendPlatformActions

enum class PikoTab(
    val title: String,
) {
    Receive("接收"),
    Send("发送"),
    Settings("设置"),
}

enum class SettingsDestination {
    Settings,
    Friends,
    FriendRequests,
}

@Composable
fun App(
    tab: PikoTab = PikoTab.Receive,
    currentDeviceName: String = "当前设备",
    onResetCurrentDeviceName: () -> Unit = {},
    sendPlatformActions: SendPlatformActions = SendPlatformActions.Empty,
    onDeleteReceiveHistory: (ReceiveHistoryItem, Boolean) -> Unit = { _, _ -> },
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
                onDeleteReceiveHistory = { item, deleteFiles ->
                    onDeleteReceiveHistory(item, deleteFiles)
                    state = state.removeReceiveHistory(item.id)
                },
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
    onDeleteReceiveHistory: (ReceiveHistoryItem, Boolean) -> Unit = { _, _ -> },
    authSection: AuthSection = AuthSection.Empty,
    friendsEntry: FriendsEntry = FriendsEntry.Empty,
    settingsDestination: SettingsDestination = SettingsDestination.Settings,
    friendSearchQuery: String = "",
    onFriendSearchQueryChange: (String) -> Unit = {},
    onFriendRequestsClick: () -> Unit = {},
    onSendFriendRequest: (String) -> Unit = {},
    onAcceptFriendRequest: (String) -> Unit = {},
    onRejectFriendRequest: (String) -> Unit = {},
    onCancelFriendRequest: (String) -> Unit = {},
    onRemoveFriend: (String) -> Unit = {},
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    when (tab) {
        PikoTab.Receive -> PikoReceiveScreen(
            state = state,
            onStateMutate = onStateMutate,
            onResetCurrentDeviceName = onResetCurrentDeviceName,
            sendPlatformActions = sendPlatformActions,
            onDeleteReceiveHistory = onDeleteReceiveHistory,
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

        PikoTab.Settings -> when (settingsDestination) {
            SettingsDestination.Settings -> PikoSettingsScreen(
                mediaSaveLocation = mediaSaveLocation,
                onMediaSaveLocationChange = onMediaSaveLocationChange,
                authSection = authSection,
                friendsEntry = friendsEntry,
                bottomContentPadding = bottomContentPadding,
                modifier = modifier,
            )
            SettingsDestination.Friends -> FriendsScreen(
                state = state.friendsState,
                query = friendSearchQuery,
                onQueryChange = onFriendSearchQueryChange,
                onRequestsClick = onFriendRequestsClick,
                onSendRequest = onSendFriendRequest,
                onRemoveFriend = onRemoveFriend,
                bottomContentPadding = bottomContentPadding,
                modifier = modifier,
            )
            SettingsDestination.FriendRequests -> FriendRequestsScreen(
                state = state.friendsState,
                onAccept = onAcceptFriendRequest,
                onReject = onRejectFriendRequest,
                onCancel = onCancelFriendRequest,
                bottomContentPadding = bottomContentPadding,
                modifier = modifier,
            )
        }
    }
}

fun startSendTransfer(
    sendPage: SendPageState,
    senderName: String,
    onStateMutate: ((PikoHomeState) -> PikoHomeState) -> Unit,
    sendPlatformActions: SendPlatformActions,
    onTransferNotice: (String) -> Unit = {},
) {
    val request = sendPage.buildTransferRequest(senderName = senderName.substringBefore("@")) ?: return
    sendPlatformActions.startTransfer(request) { event ->
        if (event is SendTransferEvent.TransportNotice) {
            onTransferNotice(event.message)
        }
        onStateMutate { state ->
            state.copy(sendPage = state.sendPage.applyTransferEvent(event))
        }
    }
}
