package com.piko.app

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun PikoSettingsScreen(
    mediaSaveLocation: ReceiveMediaSaveLocation,
    onMediaSaveLocationChange: (ReceiveMediaSaveLocation) -> Unit,
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
                PikoSectionPanel(title = "账号") {
                    MetricRow(title = "登录方式", value = "邮箱账号")
                }
            }
        }
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
        )
        ReceiveMediaSaveLocation.entries.forEach { location ->
            if (location == selected) {
                Button(onClick = { onSelected(location) }) {
                    Text(location.label)
                }
            } else {
                OutlinedButton(onClick = { onSelected(location) }) {
                    Text(location.label)
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
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}
