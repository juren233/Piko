package com.piko.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.piko.app.domain.TransferStatus

enum class PikoTab(
    val title: String,
) {
    Receive("接收"),
    Send("发送"),
    Settings("设置"),
}

@Composable
fun App(tab: PikoTab = PikoTab.Receive) {
    var state by remember { mutableStateOf(PikoHomeState.initial()) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            PikoTabScreen(
                tab = tab,
                state = state,
                onCreateSampleTransfer = {
                    state = state.withSampleTransfer()
                },
            )
        }
    }
}

@Composable
fun PikoTabScreen(
    tab: PikoTab,
    state: PikoHomeState,
    onCreateSampleTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (tab) {
        PikoTab.Receive -> PikoReceiveScreen(
            state = state,
            onCreateSampleTransfer = onCreateSampleTransfer,
            modifier = modifier,
        )

        PikoTab.Send -> PikoSendScreen(
            onCreateSampleTransfer = onCreateSampleTransfer,
            modifier = modifier,
        )

        PikoTab.Settings -> PikoSettingsScreen(modifier = modifier)
    }
}

@Composable
private fun PikoReceiveScreen(
    state: PikoHomeState,
    onCreateSampleTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(text = "Piko", style = MaterialTheme.typography.headlineMedium)
                    Text(text = state.currentDeviceName, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onCreateSampleTransfer) {
                    Text(text = "发送文件")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    title = "可信设备",
                    value = state.trustedDeviceCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    title = "接收队列",
                    value = state.pendingReceiveCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Text(text = "传输", style = MaterialTheme.typography.titleLarge)
        }

        if (state.transfers.isEmpty()) {
            item {
                Text(text = "暂无任务", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(state.transfers) { transfer ->
                TransferCard(transfer = transfer)
            }
        }
    }
}

@Composable
private fun PikoSendScreen(
    onCreateSampleTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "发送", style = MaterialTheme.typography.headlineMedium)
        Text(text = "选择文件后创建发送任务", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onCreateSampleTransfer) {
            Text(text = "发送文件")
        }
    }
}

@Composable
private fun PikoSettingsScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "设置", style = MaterialTheme.typography.headlineMedium)
        MetricCard(title = "自动接收", value = "可信设备")
        MetricCard(title = "登录方式", value = "邮箱账号")
        MetricCard(title = "传输策略", value = "局域网优先")
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun TransferCard(transfer: TransferListItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = transfer.name, style = MaterialTheme.typography.titleMedium)
                Text(text = transfer.status.label, style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(
                progress = { transfer.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(text = transfer.sizeLabel, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val TransferStatus.label: String
    get() = when (this) {
        TransferStatus.PendingConfirmation -> "待确认"
        TransferStatus.Queued -> "排队中"
        TransferStatus.Transferring -> "传输中"
        TransferStatus.Paused -> "已暂停"
        TransferStatus.Failed -> "失败"
        TransferStatus.Completed -> "完成"
    }
