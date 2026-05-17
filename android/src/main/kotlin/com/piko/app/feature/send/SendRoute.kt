package com.piko.app.feature.send

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.piko.app.design.DirectPathChip
import com.piko.app.design.PikoEmptyState
import com.piko.app.design.PikoSectionHeader
import com.piko.app.design.PikoSpacing
import com.piko.app.domain.PikoHomeState
import com.piko.app.domain.SendDevice
import com.piko.app.domain.SendFileType
import com.piko.app.domain.SendLanDiscoveryState
import com.piko.app.domain.SendMediaItem
import com.piko.app.domain.SendPageState
import com.piko.app.domain.SendTransportPath
import com.piko.app.domain.SendTransferEvent
import com.piko.app.domain.SendTransferStatus
import com.piko.app.platform.SendPlatformActions
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SendRoute(
    sendPage: SendPageState,
    onStateMutate: ((PikoHomeState) -> PikoHomeState) -> Unit,
    sendPlatformActions: SendPlatformActions,
    onStartSendTransfer: () -> Unit,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val failedTransfer = sendPage.activeTransfer.takeIf { transfer ->
        transfer.status == SendTransferStatus.Failed &&
            !transfer.errorMessage.isNullOrBlank() &&
            transfer.targets.any { target -> target.transportPath == SendTransportPath.P2P }
    }

    LaunchedEffect(Unit) {
        onStateMutate { state ->
            state.copy(sendPage = state.sendPage.updateLanDiscovery(SendLanDiscoveryState.Searching))
        }
        sendPlatformActions.startLanDiscovery { discoveryState, devices ->
            onStateMutate { state ->
                state.copy(
                    sendPage = state.sendPage
                        .updateLanDiscovery(discoveryState)
                        .updateLanDevices(devices),
                )
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            sendPlatformActions.stopLanDiscovery()
        }
    }

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
        if (sendPage.activeTransfer.status != SendTransferStatus.Idle) {
            item {
                ActiveSendCard(
                    sendPage = sendPage,
                    onPause = {
                        sendPage.activeTransfer.transferId?.let { transferId ->
                            sendPlatformActions.pauseTransfer(transferId)
                            onStateMutate { state ->
                                state.copy(
                                    sendPage = state.sendPage.applyTransferEvent(
                                        SendTransferEvent.Paused(transferId),
                                    ),
                                )
                            }
                        }
                    },
                    onCancel = {
                        sendPage.activeTransfer.transferId?.let { transferId ->
                            sendPlatformActions.cancelTransfer(transferId)
                            onStateMutate { state ->
                                state.copy(
                                    sendPage = state.sendPage.applyTransferEvent(
                                        SendTransferEvent.Canceled(transferId),
                                    ),
                                )
                            }
                        }
                    },
                )
            }
        }
        item {
            SendSummaryCard(
                sendPage = sendPage,
                onSend = onStartSendTransfer,
            )
        }
        item {
            DeviceGroup(
                title = "我的设备",
                devices = sendPage.myDevices,
                emptyText = "同账号设备上线后会显示在这里。",
                sendPage = sendPage,
                onToggleDevice = { deviceId ->
                    onStateMutate { state -> state.copy(sendPage = state.sendPage.toggleDeviceSelection(deviceId)) }
                },
            )
        }
        item {
            DeviceGroup(
                title = "局域网设备",
                devices = sendPage.lanDevices,
                emptyText = sendPage.lanDiscoveryState.label,
                sendPage = sendPage,
                onToggleDevice = { deviceId ->
                    onStateMutate { state -> state.copy(sendPage = state.sendPage.toggleDeviceSelection(deviceId)) }
                },
            )
        }
        item {
            DeviceGroup(
                title = "好友设备",
                devices = sendPage.friendDevices,
                emptyText = "好友在线后会显示 P2P direct 设备。",
                sendPage = sendPage,
                onToggleDevice = { deviceId ->
                    onStateMutate { state -> state.copy(sendPage = state.sendPage.toggleDeviceSelection(deviceId)) }
                },
            )
        }
        item {
            FilePickerCard(
                sendPage = sendPage,
                onPickMedia = {
                    sendPlatformActions.pickMedia { items ->
                        onStateMutate { state -> state.copy(sendPage = state.sendPage.addSelectedMedia(items)) }
                    }
                },
                onPickFiles = {
                    sendPlatformActions.pickFiles { files ->
                        onStateMutate { state -> state.copy(sendPage = state.sendPage.addSelectedFiles(files)) }
                    }
                },
                onRemoveMedia = { mediaId ->
                    onStateMutate { state -> state.copy(sendPage = state.sendPage.removeSelectedMedia(mediaId)) }
                },
                onRemoveFile = { fileId ->
                    onStateMutate { state -> state.copy(sendPage = state.sendPage.removeSelectedFile(fileId)) }
                },
            )
        }
    }

    failedTransfer?.let { transfer ->
        SendTransferFailureDialog(
            message = transfer.errorMessage.orEmpty(),
            onDismiss = {
                onStateMutate { state ->
                    state.copy(
                        sendPage = state.sendPage.applyTransferEvent(
                            SendTransferEvent.Canceled(transfer.transferId ?: ""),
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun SendSummaryCard(
    sendPage: SendPageState,
    onSend: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = if (sendPage.canSend) "准备发送" else "选择目标和文件",
            summary = "${sendPage.selectedDevices.size} 台设备 · ${sendPage.selectedTransferItems.size} 项 · ${sendPage.transferTotalBytes.toReadableSize()}",
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Send,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                )
            },
            endActions = {
                if (sendPage.selectedTransferItems.isNotEmpty()) {
                    Button(
                        onClick = onSend,
                        enabled = sendPage.canSend,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = MiuixIcons.Send,
                                contentDescription = null,
                            )
                            Text("发送")
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ActiveSendCard(
    sendPage: SendPageState,
    onPause: () -> Unit,
    onCancel: () -> Unit,
) {
    val transfer = sendPage.activeTransfer
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = transfer.title.ifBlank { "正在发送" },
            summary = "${transfer.completedBytes.toReadableSize()} / ${transfer.totalBytes.toReadableSize()}",
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Send,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                )
            },
            bottomAction = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    LinearProgressIndicator(progress = transfer.progress)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = "暂停",
                            onClick = onPause,
                            enabled = transfer.status == SendTransferStatus.Transferring,
                        )
                        Button(onClick = onCancel) {
                            Text("取消")
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun DeviceGroup(
    title: String,
    devices: List<SendDevice>,
    emptyText: String,
    sendPage: SendPageState,
    onToggleDevice: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PikoSpacing.item)) {
        PikoSectionHeader(title = title)
        if (devices.isEmpty()) {
            PikoEmptyState(title = "暂无设备", body = emptyText)
        } else {
            devices.forEach { device ->
                DeviceRow(
                    device = device,
                    selected = device.id in sendPage.selectedDeviceIds,
                    onClick = { onToggleDevice(device.id) },
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: SendDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = device.name,
            summary = device.subtitle ?: if (device.isConnectable) "可发送" else "缺少直连参数",
            bottomAction = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DirectPathChip(path = device.transportPath, online = device.online)
                    SelectionPill(
                        text = if (selected) "已选择" else "选择",
                        selected = selected,
                        enabled = device.isConnectable,
                        onClick = onClick,
                    )
                }
            },
        )
    }
}

@Composable
private fun FilePickerCard(
    sendPage: SendPageState,
    onPickMedia: () -> Unit,
    onPickFiles: () -> Unit,
    onRemoveMedia: (String) -> Unit,
    onRemoveFile: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PikoSpacing.item)) {
        PikoSectionHeader(
            title = "待发送内容",
            supportingText = "${sendPage.selectedTransferItems.size} 项 · ${sendPage.transferTotalBytes.toReadableSize()}",
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "图片/视频",
                summary = "${sendPage.selectedMediaItems.size} 个媒体已加入",
                endActions = {
                    Button(
                        onClick = onPickMedia,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("选择")
                    }
                },
            )
            sendPage.selectedMediaItems.forEach { item ->
                MediaPreviewRow(
                    item = item,
                    onRemove = { onRemoveMedia(item.id) },
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            BasicComponent(
                title = "文件",
                summary = "${sendPage.selectedFiles.size} 个文件已选择",
                endActions = {
                    Button(
                        onClick = onPickFiles,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("选择")
                    }
                },
            )
            sendPage.selectedFiles.forEach { file ->
                BasicComponent(
                    title = file.displayName,
                    summary = "${file.fileType.label} · ${file.sizeBytes.toReadableSize()}",
                    endActions = {
                        TextButton(
                            text = "移除",
                            onClick = { onRemoveFile(file.id) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun MediaPreviewRow(
    item: SendMediaItem,
    onRemove: () -> Unit,
) {
    BasicComponent(
        title = item.displayName,
        summary = "${item.fileType.mediaLabel} · ${item.sizeBytes.toReadableSize()}",
        startAction = {
            MediaPreviewThumbnail(item = item)
        },
        endActions = {
            TextButton(
                text = "移除",
                onClick = onRemove,
            )
        },
    )
}

@Composable
private fun MediaPreviewThumbnail(item: SendMediaItem) {
    val imageBitmap = remember(item.thumbnailBytes) {
        item.thumbnailBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = null,
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MiuixTheme.colorScheme.secondaryContainer,
            contentColor = MiuixTheme.colorScheme.primary,
        ) {
            Icon(
                imageVector = MiuixIcons.Send,
                contentDescription = null,
                modifier = Modifier
                    .size(58.dp)
                    .padding(15.dp),
            )
        }
    }
}

@Composable
private fun SelectionPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = when {
            !enabled -> MiuixTheme.colorScheme.secondaryContainer
            selected -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.secondaryVariant
        },
        contentColor = when {
            !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
            selected -> MiuixTheme.colorScheme.onPrimary
            else -> MiuixTheme.colorScheme.onSecondaryVariant
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SendTransferFailureDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    SuperDialog(
        show = true,
        title = "P2P direct 失败",
        summary = message,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                text = "复制诊断",
                onClick = {
                    val clipboardManager =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("P2P direct 失败", message))
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text("知道了")
            }
        }
    }
}

private val SendLanDiscoveryState.label: String
    get() = when (this) {
        SendLanDiscoveryState.Idle -> "尚未开始发现局域网设备。"
        SendLanDiscoveryState.Searching -> "正在搜索局域网直连设备。"
        SendLanDiscoveryState.Found -> "发现了可直连设备。"
        SendLanDiscoveryState.Empty -> "没有发现局域网设备。"
        SendLanDiscoveryState.Unavailable -> "当前网络不可用。"
        SendLanDiscoveryState.Failed -> "局域网发现失败。"
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

private val SendFileType.mediaLabel: String
    get() = when (this) {
        SendFileType.Video -> "视频"
        else -> "图片"
    }
