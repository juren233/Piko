package com.piko.app.feature.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.piko.app.design.PikoEmptyState
import com.piko.app.design.PikoSectionHeader
import com.piko.app.design.PikoSpacing
import com.piko.app.domain.FriendRelationship
import com.piko.app.domain.FriendRequest
import com.piko.app.domain.FriendRequestDirection
import com.piko.app.domain.FriendRequestStatus
import com.piko.app.domain.FriendUser
import com.piko.app.domain.FriendsState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun FriendsRoute(
    state: FriendsState,
    query: String,
    onQueryChange: (String) -> Unit,
    onRequestsClick: () -> Unit,
    onSendRequest: (String) -> Unit,
    onRemoveFriend: (String) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    var searchExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = PikoSpacing.screenHorizontal,
            top = PikoSpacing.screenTop,
            end = PikoSpacing.screenHorizontal,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(PikoSpacing.section),
    ) {
        item {
            PikoSectionHeader(
                title = "好友",
                supportingText = "${state.friends.size} 人 · ${state.pendingIncomingCount} 个待处理申请",
            )
        }
        item {
            SearchBar(
                inputField = {
                    InputField(
                        query = query,
                        onQueryChange = onQueryChange,
                        onSearch = onQueryChange,
                        expanded = searchExpanded,
                        onExpandedChange = { searchExpanded = it },
                        label = "搜索用户名或邮箱",
                    )
                },
                expanded = searchExpanded,
                onExpandedChange = { searchExpanded = it },
            ) {
            }
        }
        if (query.isNotBlank()) {
            item {
                SearchResultsCard(
                    state = state,
                    onSendRequest = onSendRequest,
                )
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = "好友申请",
                    summary = "收到 ${state.incoming.size} · 发出 ${state.outgoing.size}",
                    endActions = {
                        Button(
                            onClick = onRequestsClick,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text("查看")
                        }
                    },
                )
            }
        }
        item {
            PikoSectionHeader(title = "我的好友")
        }
        if (state.friends.isEmpty()) {
            item {
                PikoEmptyState(
                    title = "还没有好友",
                    body = "搜索用户并发送申请后，对方在线设备会出现在发送页。",
                )
            }
        } else {
            items(
                count = state.friends.size,
                key = { index -> state.friends[index].userId },
            ) { index ->
                FriendRow(
                    friend = state.friends[index],
                    onRemove = { onRemoveFriend(state.friends[index].userId) },
                )
            }
        }
    }
}

@Composable
private fun SearchResultsCard(
    state: FriendsState,
    onSendRequest: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        if (state.searchResults.isEmpty()) {
            BasicComponent(
                title = "没有搜索结果",
                summary = "至少输入 2 个字符后会搜索用户。",
            )
        } else {
            state.searchResults.forEach { result ->
                BasicComponent(
                    title = result.user.displayName,
                    summary = result.relationship.label,
                    endActions = {
                        Button(
                            onClick = { onSendRequest(result.user.userId) },
                            enabled = result.relationship == FriendRelationship.None,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text("申请")
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun FriendRequestsRoute(
    state: FriendsState,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onCancel: (String) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = PikoSpacing.screenHorizontal,
            top = PikoSpacing.screenTop,
            end = PikoSpacing.screenHorizontal,
            bottom = bottomPadding + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(PikoSpacing.section),
    ) {
        item {
            PikoSectionHeader(
                title = "好友申请",
                supportingText = "收到 ${state.incoming.size} · 发出 ${state.outgoing.size}",
            )
        }
        item {
            RequestSection(
                title = "收到的",
                requests = state.incoming,
                onAccept = onAccept,
                onReject = onReject,
                onCancel = onCancel,
            )
        }
        item {
            RequestSection(
                title = "我发出的",
                requests = state.outgoing,
                onAccept = onAccept,
                onReject = onReject,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun FriendRow(
    friend: FriendUser,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = friend.displayName,
            summary = friend.presenceLabel,
            endActions = {
                TextButton(
                    text = "删除",
                    onClick = onRemove,
                )
            },
        )
    }
}

@Composable
private fun RequestSection(
    title: String,
    requests: List<FriendRequest>,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(title = title)
        if (requests.isEmpty()) {
            BasicComponent(
                title = "暂无申请",
                summary = "新的申请会显示在这里。",
            )
        } else {
            requests.forEach { request ->
                BasicComponent(
                    title = request.otherUser.displayName,
                    summary = request.status.label,
                    endActions = {
                        RequestActions(
                            request = request,
                            onAccept = onAccept,
                            onReject = onReject,
                            onCancel = onCancel,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun RequestActions(
    request: FriendRequest,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (request.direction == FriendRequestDirection.Incoming &&
            request.status == FriendRequestStatus.Pending
        ) {
            TextButton(
                text = "拒绝",
                onClick = { onReject(request.id) },
            )
            Button(
                onClick = { onAccept(request.id) },
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text("接受")
            }
        } else if (request.direction == FriendRequestDirection.Outgoing &&
            request.status == FriendRequestStatus.Pending
        ) {
            TextButton(
                text = "取消",
                onClick = { onCancel(request.id) },
            )
        }
    }
}

private val FriendRelationship.label: String
    get() = when (this) {
        FriendRelationship.Self -> "这是你自己"
        FriendRelationship.None -> "可以发送好友申请"
        FriendRelationship.PendingOut -> "已发出申请"
        FriendRelationship.PendingIn -> "对方已申请你"
        FriendRelationship.Friend -> "已是好友"
    }

private val FriendRequestStatus.label: String
    get() = when (this) {
        FriendRequestStatus.Pending -> "待处理"
        FriendRequestStatus.Accepted -> "已接受"
        FriendRequestStatus.Rejected -> "已拒绝"
        FriendRequestStatus.Canceled -> "已取消"
    }
