package com.piko.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.piko.app.domain.FriendRelationship
import com.piko.app.domain.FriendRequest
import com.piko.app.domain.FriendRequestStatus
import com.piko.app.domain.FriendSearchResult
import com.piko.app.domain.FriendUser

@Composable
internal fun FriendUserRow(
    friend: FriendUser,
    trailing: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = friend.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = friend.presenceLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@Composable
internal fun FriendSearchResultRow(
    result: FriendSearchResult,
    onSendRequest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FriendUserRow(
        friend = result.user.copy(online = false),
        modifier = modifier,
        trailing = {
            if (result.relationship == FriendRelationship.None) {
                Button(onClick = { onSendRequest(result.user.userId) }) {
                    Text(AuthLabels.addFriendButton)
                }
            } else {
                Text(
                    text = result.relationship.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
internal fun FriendRequestRow(
    request: FriendRequest,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FriendUserRow(
        friend = request.otherUser,
        modifier = modifier,
        trailing = {
            when {
                request.status != FriendRequestStatus.Pending -> Text(request.status.label)
                request.direction.name == "Incoming" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAccept(request.id) }) { Text(AuthLabels.acceptButton) }
                    OutlinedButton(onClick = { onReject(request.id) }) { Text(AuthLabels.rejectButton) }
                }
                else -> OutlinedButton(onClick = { onCancel(request.id) }) {
                    Text(AuthLabels.cancelRequestButton)
                }
            }
        },
    )
}

private val FriendRelationship.label: String
    get() = when (this) {
        FriendRelationship.Self -> "自己"
        FriendRelationship.None -> "未添加"
        FriendRelationship.PendingOut -> "已申请"
        FriendRelationship.PendingIn -> "待你处理"
        FriendRelationship.Friend -> "已是好友"
    }

private val FriendRequestStatus.label: String
    get() = when (this) {
        FriendRequestStatus.Pending -> "待处理"
        FriendRequestStatus.Accepted -> "已同意"
        FriendRequestStatus.Rejected -> "已拒绝"
        FriendRequestStatus.Canceled -> "已撤回"
    }
