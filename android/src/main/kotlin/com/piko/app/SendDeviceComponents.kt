package com.piko.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SendDeviceSection(
    title: String,
    devices: List<SendDevice>,
    selectedDeviceIds: Set<String>,
    onDeviceClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String? = null,
) {
    PikoSectionPanel(
        title = title,
        modifier = modifier,
        trailing = emptyText?.let { label ->
            { PikoInfoPill(text = label) }
        },
    ) {
        if (devices.isEmpty()) {
            PikoEmptyPlane(
                text = emptyText ?: "暂无设备",
            ) {
                Text(
                    text = "· · ·",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(devices, key = { device -> device.id }) { device ->
                    SendDeviceAvatar(
                        device = device,
                        selected = device.id in selectedDeviceIds,
                        onClick = { onDeviceClick(device.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SendDeviceAvatar(
    device: SendDevice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val avatarShape = if (selected) RoundedCornerShape(24.dp) else CircleShape
    Column(
        modifier = modifier
            .width(96.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f)
                },
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
                },
                shape = RoundedCornerShape(28.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(avatarShape)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = avatarShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = device.name.avatarLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                SelectionBadge(modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        Text(
            text = device.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
