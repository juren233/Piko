package com.piko.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.piko.app.domain.FriendsState

@Composable
internal fun FriendRequestsScreen(
    state: FriendsState,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onCancel: (String) -> Unit,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pikoPageBrush()),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 32.dp, bottom = bottomContentPadding + 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                PikoHeroPanel(
                    title = AuthLabels.friendRequestsTitle,
                    subtitle = "收到的 ${state.incoming.size} / 我发出的 ${state.outgoing.size}",
                    metric = "${state.pendingIncomingCount}",
                )
            }
            item {
                PikoSectionPanel(title = "收到的") {
                    state.incoming.forEach { request ->
                        FriendRequestRow(request, onAccept, onReject, onCancel)
                    }
                }
            }
            item {
                PikoSectionPanel(title = "我发出的") {
                    state.outgoing.forEach { request ->
                        FriendRequestRow(request, onAccept, onReject, onCancel)
                    }
                }
            }
        }
    }
}
