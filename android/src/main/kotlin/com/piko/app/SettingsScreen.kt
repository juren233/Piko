package com.piko.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun PikoSettingsScreen(
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(pikoPageBrush())
            .statusBarsPadding()
            .padding(start = 18.dp, top = 14.dp, end = 18.dp, bottom = bottomContentPadding + 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PikoHeroPanel(
            title = "设置",
            subtitle = "设备、账号和传输偏好",
            metric = "本机",
        )
        PikoSectionPanel(title = "传输") {
            MetricRow(title = "自动接收", value = "可信设备")
            MetricRow(title = "传输策略", value = "局域网优先")
        }
        PikoSectionPanel(title = "账号") {
            MetricRow(title = "登录方式", value = "邮箱账号")
        }
    }
}

@Composable
private fun MetricRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.headlineSmall)
    }
}
