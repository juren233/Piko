package com.piko.app.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.piko.app.data.ReceiveMediaSaveLocation
import com.piko.app.design.PikoMiuixTheme
import com.piko.app.domain.PikoHomeState
import com.piko.app.domain.ReceiveHistoryItem
import com.piko.app.feature.friends.FriendRequestsRoute
import com.piko.app.feature.friends.FriendsRoute
import com.piko.app.feature.receive.ReceiveRoute
import com.piko.app.feature.send.SendRoute
import com.piko.app.feature.settings.SettingsRoute
import com.piko.app.platform.SendPlatformActions
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class PikoDestination(
    val title: String,
) {
    Receive("接收"),
    Send("发送"),
    Settings("设置"),
    Friends("好友"),
    FriendRequests("申请"),
}

@Composable
fun PikoAndroidAppShell(
    destination: PikoDestination,
    onDestinationChange: (PikoDestination) -> Unit,
    state: PikoHomeState,
    onStateMutate: ((PikoHomeState) -> PikoHomeState) -> Unit,
    onResetCurrentDeviceName: () -> Unit,
    mediaSaveLocation: ReceiveMediaSaveLocation,
    onMediaSaveLocationChange: (ReceiveMediaSaveLocation) -> Unit,
    sendPlatformActions: SendPlatformActions,
    authSection: AuthSection,
    friendsEntry: FriendsEntry,
    friendSearchQuery: String,
    onFriendSearchQueryChange: (String) -> Unit,
    onFriendRequestsClick: () -> Unit,
    onSendFriendRequest: (String) -> Unit,
    onAcceptFriendRequest: (String) -> Unit,
    onRejectFriendRequest: (String) -> Unit,
    onCancelFriendRequest: (String) -> Unit,
    onRemoveFriend: (String) -> Unit,
    onDeleteReceiveHistory: (ReceiveHistoryItem, Boolean) -> Unit,
    onAcceptReceiveTransfer: (String) -> Unit,
    onCancelReceiveTransfer: (String) -> Unit,
    onStartSendTransfer: () -> Unit,
    appVersion: String,
) {
    val topLevelDestination = when (destination) {
        PikoDestination.Receive,
        PikoDestination.Send,
        PikoDestination.Settings -> destination
        PikoDestination.Friends,
        PikoDestination.FriendRequests -> PikoDestination.Settings
    }

    PikoMiuixTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MiuixTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = destination.title,
                    largeTitle = destination.title,
                    color = MiuixTheme.colorScheme.background,
                    navigationIcon = {
                        if (destination == PikoDestination.Friends || destination == PikoDestination.FriendRequests) {
                            TextButton(
                                text = "返回",
                                onClick = { onDestinationChange(PikoDestination.Settings) },
                            )
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar(
                    color = MiuixTheme.colorScheme.surface,
                ) {
                    TopLevelDestinations.forEach { item ->
                        NavigationBarItem(
                            selected = topLevelDestination == item,
                            onClick = { onDestinationChange(item) },
                            icon = item.icon,
                            label = item.title,
                        )
                    }
                }
            },
        ) { innerPadding ->
            PikoDestinationContent(
                destination = destination,
                state = state,
                onStateMutate = onStateMutate,
                onResetCurrentDeviceName = onResetCurrentDeviceName,
                mediaSaveLocation = mediaSaveLocation,
                onMediaSaveLocationChange = onMediaSaveLocationChange,
                sendPlatformActions = sendPlatformActions,
                authSection = authSection,
                friendsEntry = friendsEntry,
                friendSearchQuery = friendSearchQuery,
                onFriendSearchQueryChange = onFriendSearchQueryChange,
                onFriendRequestsClick = onFriendRequestsClick,
                onSendFriendRequest = onSendFriendRequest,
                onAcceptFriendRequest = onAcceptFriendRequest,
                onRejectFriendRequest = onRejectFriendRequest,
                onCancelFriendRequest = onCancelFriendRequest,
                onRemoveFriend = onRemoveFriend,
                onDeleteReceiveHistory = onDeleteReceiveHistory,
                onAcceptReceiveTransfer = onAcceptReceiveTransfer,
                onCancelReceiveTransfer = onCancelReceiveTransfer,
                onStartSendTransfer = onStartSendTransfer,
                appVersion = appVersion,
                innerPadding = innerPadding,
            )
        }

        state.activeReceive
            .takeIf { it.requiresConfirmation && it.transferId != null }
            ?.let { pendingReceive ->
                ReceiveConfirmDialog(
                    message = pendingReceive.receiveConfirmationMessage,
                    onAccept = {
                        pendingReceive.transferId?.let(onAcceptReceiveTransfer)
                    },
                    onReject = {
                        pendingReceive.transferId?.let(onCancelReceiveTransfer)
                    },
                )
            }
    }
}

@Composable
private fun PikoDestinationContent(
    destination: PikoDestination,
    state: PikoHomeState,
    onStateMutate: ((PikoHomeState) -> PikoHomeState) -> Unit,
    onResetCurrentDeviceName: () -> Unit,
    mediaSaveLocation: ReceiveMediaSaveLocation,
    onMediaSaveLocationChange: (ReceiveMediaSaveLocation) -> Unit,
    sendPlatformActions: SendPlatformActions,
    authSection: AuthSection,
    friendsEntry: FriendsEntry,
    friendSearchQuery: String,
    onFriendSearchQueryChange: (String) -> Unit,
    onFriendRequestsClick: () -> Unit,
    onSendFriendRequest: (String) -> Unit,
    onAcceptFriendRequest: (String) -> Unit,
    onRejectFriendRequest: (String) -> Unit,
    onCancelFriendRequest: (String) -> Unit,
    onRemoveFriend: (String) -> Unit,
    onDeleteReceiveHistory: (ReceiveHistoryItem, Boolean) -> Unit,
    onAcceptReceiveTransfer: (String) -> Unit,
    onCancelReceiveTransfer: (String) -> Unit,
    onStartSendTransfer: () -> Unit,
    appVersion: String,
    innerPadding: PaddingValues,
) {
    val contentModifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    val bottomPadding = 0.dp
    when (destination) {
        PikoDestination.Receive -> ReceiveRoute(
            state = state,
            onResetCurrentDeviceName = onResetCurrentDeviceName,
            onAcceptReceiveTransfer = onAcceptReceiveTransfer,
            onCancelReceiveTransfer = onCancelReceiveTransfer,
            onDeleteReceiveHistory = onDeleteReceiveHistory,
            bottomPadding = bottomPadding,
            modifier = contentModifier,
        )

        PikoDestination.Send -> SendRoute(
            sendPage = state.sendPage,
            onStateMutate = onStateMutate,
            sendPlatformActions = sendPlatformActions,
            onStartSendTransfer = onStartSendTransfer,
            bottomPadding = bottomPadding,
            modifier = contentModifier,
        )

        PikoDestination.Settings -> SettingsRoute(
            mediaSaveLocation = mediaSaveLocation,
            onMediaSaveLocationChange = onMediaSaveLocationChange,
            authSection = authSection,
            friendsEntry = friendsEntry,
            appVersion = appVersion,
            bottomPadding = bottomPadding,
            modifier = contentModifier,
        )

        PikoDestination.Friends -> FriendsRoute(
            state = state.friendsState,
            query = friendSearchQuery,
            onQueryChange = onFriendSearchQueryChange,
            onRequestsClick = onFriendRequestsClick,
            onSendRequest = onSendFriendRequest,
            onRemoveFriend = onRemoveFriend,
            bottomPadding = bottomPadding,
            modifier = contentModifier,
        )

        PikoDestination.FriendRequests -> FriendRequestsRoute(
            state = state.friendsState,
            onAccept = onAcceptFriendRequest,
            onReject = onRejectFriendRequest,
            onCancel = onCancelFriendRequest,
            bottomPadding = bottomPadding,
            modifier = contentModifier,
        )
    }
}

@Composable
private fun ReceiveConfirmDialog(
    message: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    SuperDialog(
        show = true,
        title = "确认接收",
        summary = message,
        onDismissRequest = null,
    ) {
        Row {
            TextButton(
                text = "拒绝",
                onClick = onReject,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = "接收",
                onClick = onAccept,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val TopLevelDestinations = listOf(
    PikoDestination.Receive,
    PikoDestination.Send,
    PikoDestination.Settings,
)

private val PikoDestination.subtitle: String
    get() = when (this) {
        PikoDestination.Receive -> "等待直连传入"
        PikoDestination.Send -> "选择设备和内容"
        PikoDestination.Settings -> "账号、偏好、诊断"
        PikoDestination.Friends -> "好友和在线设备"
        PikoDestination.FriendRequests -> "处理好友申请"
    }

private val PikoDestination.icon
    get() = when (this) {
        PikoDestination.Receive -> MiuixIcons.Download
        PikoDestination.Send -> MiuixIcons.Send
        PikoDestination.Settings,
        PikoDestination.Friends,
        PikoDestination.FriendRequests -> MiuixIcons.Settings
    }
