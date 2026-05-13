package com.piko.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.piko.app.domain.FriendsState

@Composable
internal fun FriendsScreen(
    state: FriendsState,
    query: String,
    onQueryChange: (String) -> Unit,
    onRequestsClick: () -> Unit,
    onSendRequest: (String) -> Unit,
    onRemoveFriend: (String) -> Unit,
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
                PikoHeroPanel(title = AuthLabels.friendsEntry, subtitle = "搜索、申请、管理", metric = "${state.friends.size} 人")
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(AuthLabels.searchPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotBlank()) {
                item {
                    PikoSectionPanel(title = "搜索结果") {
                        state.searchResults.forEach { result ->
                            FriendSearchResultRow(result = result, onSendRequest = onSendRequest)
                        }
                    }
                }
            }
            item {
                PikoSectionPanel(
                    title = "我的好友",
                    trailing = {
                        Button(onClick = onRequestsClick) {
                            Text(
                                text = "${AuthLabels.friendRequestsTitle}${state.pendingIncomingCount.takeIf { it > 0 }?.let { " $it" } ?: ""}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                ) {
                    if (state.friends.isEmpty()) {
                        Text(AuthLabels.noFriendsHint)
                    } else {
                        state.friends.forEach { friend ->
                            FriendUserRow(
                                friend = friend,
                                trailing = {
                                    Button(onClick = { onRemoveFriend(friend.userId) }) {
                                        Text(AuthLabels.removeFriendButton)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
