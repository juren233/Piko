package com.piko.app.feature.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.piko.app.design.PikoEmptyState
import com.piko.app.design.PikoSectionHeader
import com.piko.app.design.PikoSpacing
import com.piko.app.domain.PikoHomeState
import com.piko.app.domain.ReceiveHistoryItem
import com.piko.app.domain.ReceiveTransferState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ReceiveRoute(
    state: PikoHomeState,
    onResetCurrentDeviceName: () -> Unit,
    onAcceptReceiveTransfer: (String) -> Unit,
    onCancelReceiveTransfer: (String) -> Unit,
    onDeleteReceiveHistory: (ReceiveHistoryItem, Boolean) -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val activeReceive = state.activeReceive.takeIf { it.transferId != null }
    var pendingDelete by remember { mutableStateOf<ReceiveHistoryItem?>(null) }

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
            DeviceStatusCard(
                deviceName = state.currentDeviceName,
                historyCount = state.receiveHistory.size,
                onReset = onResetCurrentDeviceName,
            )
        }
        if (activeReceive != null) {
            item(key = activeReceive.transferId) {
                ActiveReceiveCard(
                    transfer = activeReceive,
                    onAccept = { activeReceive.transferId?.let(onAcceptReceiveTransfer) },
                    onCancel = { activeReceive.transferId?.let(onCancelReceiveTransfer) },
                )
            }
        }
        item {
            PikoSectionHeader(
                title = "接收历史",
                supportingText = if (state.receiveHistoryDescending.isEmpty()) {
                    "这台设备正在等待直连传输。"
                } else {
                    "${state.receiveHistoryDescending.size} 条记录"
                },
            )
        }
        if (state.receiveHistoryDescending.isEmpty()) {
            item {
                PikoEmptyState(
                    title = "还没有收到文件",
                    body = "保持 Piko 打开，对方选择你的设备后会在这里出现确认和进度。",
                )
            }
        } else {
            items(
                items = state.receiveHistoryDescending,
                key = { item -> item.id },
            ) { item ->
                ReceiveHistoryRow(
                    item = item,
                    onDelete = { pendingDelete = item },
                )
            }
        }
    }

    pendingDelete?.let { item ->
        DeleteReceiveHistoryDialog(
            item = item,
            onDismiss = { pendingDelete = null },
            onDeleteRecord = {
                onDeleteReceiveHistory(item, false)
                pendingDelete = null
            },
            onDeleteRecordAndFiles = {
                onDeleteReceiveHistory(item, true)
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun DeviceStatusCard(
    deviceName: String,
    historyCount: Int,
    onReset: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = deviceName,
            summary = "可接收 · $historyCount 条历史记录",
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Download,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                )
            },
            endActions = {
                TextButton(
                    text = "更换",
                    onClick = onReset,
                )
            },
        )
    }
}

@Composable
private fun ActiveReceiveCard(
    transfer: ReceiveTransferState,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = transfer.title,
            summary = transfer.subtitle,
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Download,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                )
            },
            bottomAction = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LinearProgressIndicator(progress = transfer.progress)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (transfer.requiresConfirmation) {
                            Button(
                                onClick = onAccept,
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) {
                                Text("接收")
                            }
                        }
                        TextButton(
                            text = if (transfer.requiresConfirmation) "拒绝" else "取消",
                            onClick = onCancel,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun ReceiveHistoryRow(
    item: ReceiveHistoryItem,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = item.title,
            summary = "${item.sourceDeviceName} · ${item.receivedAtLabel} · ${item.subtitle}",
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Download,
                    contentDescription = null,
                )
            },
            endActions = {
                TextButton(
                    text = "删除",
                    onClick = onDelete,
                )
            },
            bottomAction = if (item.files.size > 1) {
                {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item.files.take(3).forEach { file ->
                            Text(
                                text = "${file.displayName} · ${file.sizeBytes.toReadableSize()}",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun DeleteReceiveHistoryDialog(
    item: ReceiveHistoryItem,
    onDismiss: () -> Unit,
    onDeleteRecord: () -> Unit,
    onDeleteRecordAndFiles: () -> Unit,
) {
    SuperDialog(
        show = true,
        title = item.deleteConfirmationTitle,
        summary = item.deleteConfirmationBody,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                text = "只删记录",
                onClick = onDeleteRecord,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onDeleteRecordAndFiles,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text("删除记录与文件")
            }
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun Long.toReadableSize(): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = toDouble().coerceAtLeast(0.0)
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        "${(value * 10).toLong() / 10.0} ${units[unitIndex]}"
    }
}
