package com.piko.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.piko.app.data.ReceiveMediaSaveLocation

data class FriendsEntry(
    val enabled: Boolean,
    val friendCount: Int,
    val pendingCount: Int,
    val onClick: () -> Unit,
) {
    companion object {
        val Empty = FriendsEntry(
            enabled = false,
            friendCount = 0,
            pendingCount = 0,
            onClick = {},
        )
    }
}

@Composable
internal fun PikoSettingsScreen(
    mediaSaveLocation: ReceiveMediaSaveLocation,
    onMediaSaveLocationChange: (ReceiveMediaSaveLocation) -> Unit,
    authSection: AuthSection = AuthSection.Empty,
    friendsEntry: FriendsEntry = FriendsEntry.Empty,
    bottomContentPadding: Dp = 0.dp,
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
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                PikoHeroPanel(
                    title = "设置",
                    subtitle = "设备、账号和传输偏好",
                    metric = "本机",
                )
            }
            item {
                PikoSectionPanel(title = "传输") {
                    MetricRow(title = "自动接收", value = "可信设备")
                    MediaSaveLocationRow(
                        selected = mediaSaveLocation,
                        onSelected = onMediaSaveLocationChange,
                    )
                    MetricRow(title = "传输策略", value = "局域网优先")
                }
            }
            item {
                PikoSectionPanel(title = AuthLabels.accountSectionTitle) {
                    FriendsEntryRow(entry = friendsEntry)
                    AuthSectionContent(section = authSection)
                }
            }
        }
    }
}

@Composable
internal fun FriendsEntryRow(
    entry: FriendsEntry,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = entry.enabled, onClick = entry.onClick),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = AuthLabels.friendsEntry,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val value = if (entry.enabled) {
            if (entry.pendingCount > 0) "${entry.friendCount} 人 · ${entry.pendingCount} 个申请" else "${entry.friendCount} 人"
        } else {
            AuthLabels.friendsLoginHint
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MediaSaveLocationRow(
    selected: ReceiveMediaSaveLocation,
    onSelected: (ReceiveMediaSaveLocation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "图片视频保存位置",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ReceiveMediaSaveLocation.entries.forEach { location ->
            if (location == selected) {
                Button(onClick = { onSelected(location) }) {
                    Text(
                        text = location.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                OutlinedButton(onClick = { onSelected(location) }) {
                    Text(
                        text = location.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
