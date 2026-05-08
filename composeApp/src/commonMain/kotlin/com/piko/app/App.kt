package com.piko.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class PikoTab(
    val title: String,
) {
    Receive("接收"),
    Send("发送"),
    Settings("设置"),
}

@Composable
fun App(
    tab: PikoTab = PikoTab.Receive,
    currentDeviceName: String = "当前设备",
    sendPlatformActions: SendPlatformActions = SendPlatformActions.Empty,
    sendOverlayController: SendOverlayController? = null,
) {
    var state by remember(currentDeviceName) { mutableStateOf(PikoHomeState.initial(currentDeviceName)) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            PikoTabScreen(
                tab = tab,
                state = state,
                onStateMutate = { transform -> state = transform(state) },
                onCreateSampleReceiveHistory = {
                    state = state.withSampleReceiveHistory()
                },
                sendPlatformActions = sendPlatformActions,
                sendOverlayController = sendOverlayController,
            )
        }
    }
}

@Composable
fun PikoTabScreen(
    tab: PikoTab,
    state: PikoHomeState,
    onStateMutate: ((PikoHomeState) -> PikoHomeState) -> Unit,
    onCreateSampleReceiveHistory: () -> Unit,
    sendPlatformActions: SendPlatformActions = SendPlatformActions.Empty,
    bottomContentPadding: Dp = 0.dp,
    sendOverlayController: SendOverlayController? = null,
    modifier: Modifier = Modifier,
) {
    when (tab) {
        PikoTab.Receive -> PikoReceiveScreen(
            state = state,
            bottomContentPadding = bottomContentPadding,
            modifier = modifier,
        )

        PikoTab.Send -> PikoSendScreen(
            sendPage = state.sendPage,
            onStateMutate = onStateMutate,
            sendPlatformActions = sendPlatformActions,
            bottomContentPadding = bottomContentPadding,
            sendOverlayController = sendOverlayController,
            modifier = modifier,
        )

        PikoTab.Settings -> PikoSettingsScreen(
            bottomContentPadding = bottomContentPadding,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PikoReceiveScreen(
    state: PikoHomeState,
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Piko",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (state.receiveHistoryDescending.isEmpty()) {
                ReceiveHistoryEmptyState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = bottomContentPadding + 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = state.receiveHistoryDescending,
                        key = { history -> history.id },
                    ) { history ->
                        ReceiveHistoryCard(history = history)
                    }
                }
            }
        }
    }
}

@Composable
private fun PikoSendScreen(
    sendPage: SendPageState,
    onStateMutate: ((PikoHomeState) -> PikoHomeState) -> Unit,
    sendPlatformActions: SendPlatformActions,
    bottomContentPadding: Dp = 0.dp,
    sendOverlayController: SendOverlayController? = null,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        onStateMutate { state ->
            state.copy(sendPage = state.sendPage.updateLanDiscovery(SendLanDiscoveryState.Searching))
        }
        sendPlatformActions.requestRecentImages { permissionState, images ->
            onStateMutate { state ->
                state.copy(
                    sendPage = state.sendPage
                        .updatePhotoPermission(permissionState)
                        .replaceRecentImages(images),
                )
            }
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
            sendOverlayController?.clear()
            sendPlatformActions.stopLanDiscovery()
        }
    }
    LaunchedEffect(
        sendPage.canSend,
        sendPage.selectedDeviceIds,
        sendPage.selectedImageIds,
        sendPage.selectedFiles,
        sendPage.activeTransfer.status,
    ) {
        sendOverlayController?.update(sendPage.canSend) {
            startSendTransfer(
                sendPage = sendPage,
                onStateMutate = onStateMutate,
                sendPlatformActions = sendPlatformActions,
            )
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        contentPadding = PaddingValues(bottom = bottomContentPadding + 20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        if (sendPage.activeTransfer.status != SendTransferStatus.Idle) {
            item {
                SendTransferStatusCard(
                    transfer = sendPage.activeTransfer,
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
            SendDeviceSection(
                title = "我的设备",
                devices = sendPage.myDevices,
                selectedDeviceIds = sendPage.selectedDeviceIds,
                onDeviceClick = { deviceId ->
                    onStateMutate { state ->
                        state.copy(sendPage = state.sendPage.toggleDeviceSelection(deviceId))
                    }
                },
            )
        }
        item {
            SendDeviceSection(
                title = "局域网设备",
                devices = sendPage.lanDevices,
                selectedDeviceIds = sendPage.selectedDeviceIds,
                emptyText = sendPage.lanDiscoveryState.label,
                onDeviceClick = { deviceId ->
                    onStateMutate { state ->
                        state.copy(sendPage = state.sendPage.toggleDeviceSelection(deviceId))
                    }
                },
            )
        }
        item {
            SendDeviceSection(
                title = "我的好友",
                devices = sendPage.friendDevices,
                selectedDeviceIds = sendPage.selectedDeviceIds,
                onDeviceClick = { deviceId ->
                    onStateMutate { state ->
                        state.copy(sendPage = state.sendPage.toggleDeviceSelection(deviceId))
                    }
                },
            )
        }
        item {
            SendImageSection(
                sendPage = sendPage,
                onToggleExpanded = {
                    onStateMutate { state ->
                        state.copy(sendPage = state.sendPage.toggleImageSectionExpanded())
                    }
                },
                onImageClick = { imageId ->
                    onStateMutate { state ->
                        state.copy(sendPage = state.sendPage.toggleImageSelection(imageId))
                    }
                },
                onPickImages = {
                    sendPlatformActions.pickImages { images ->
                        onStateMutate { state ->
                            state.copy(sendPage = state.sendPage.addSelectedImages(images))
                        }
                    }
                },
            )
        }
        item {
            SendFileSection(
                files = sendPage.selectedFiles,
                onPickFiles = {
                    sendPlatformActions.pickFiles { files ->
                        onStateMutate { state ->
                            state.copy(sendPage = state.sendPage.addSelectedFiles(files))
                        }
                    }
                },
                onRemoveFile = { fileId ->
                    onStateMutate { state ->
                        state.copy(sendPage = state.sendPage.removeSelectedFile(fileId))
                    }
                },
            )
        }
    }
}

fun startSendTransfer(
    sendPage: SendPageState,
    onStateMutate: ((PikoHomeState) -> PikoHomeState) -> Unit,
    sendPlatformActions: SendPlatformActions,
) {
    val request = sendPage.buildTransferRequest() ?: return
    sendPlatformActions.startTransfer(request) { event ->
        onStateMutate { state ->
            state.copy(sendPage = state.sendPage.applyTransferEvent(event))
        }
    }
}

@Composable
private fun SendDeviceSection(
    title: String,
    devices: List<SendDevice>,
    selectedDeviceIds: Set<String>,
    onDeviceClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (devices.isEmpty()) {
            Text(
                text = emptyText ?: "暂无设备",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun SendTransferStatusCard(
    transfer: SendTransferState,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (transfer.primaryFileType == SendFileType.Image) {
                            LucideImageIcon
                        } else {
                            LucideFileIcon
                        },
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = transfer.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = transfer.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onPause,
                    enabled = transfer.status == SendTransferStatus.Transferring,
                ) {
                    Text(text = "暂停")
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = LucideXIcon,
                        contentDescription = "取消",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { transfer.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            if (transfer.status == SendTransferStatus.Failed && !transfer.errorMessage.isNullOrBlank()) {
                Text(
                    text = transfer.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
    val avatarShape = if (selected) RoundedCornerShape(20.dp) else CircleShape
    Column(
        modifier = modifier.width(76.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
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
                )
                .clickable(onClick = onClick),
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

@Composable
private fun SendImageSection(
    sendPage: SendPageState,
    onToggleExpanded: () -> Unit,
    onImageClick: (String) -> Unit,
    onPickImages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "图片",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (sendPage.imageSectionExpanded) "收起" else "展开",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (sendPage.visibleImages.isEmpty()) {
            SendImagesEmptyState(
                permissionState = sendPage.photoPermissionState,
                onPickImages = onPickImages,
            )
        } else if (sendPage.imageSectionExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sendPage.visibleImages.chunked(3).forEach { rowImages ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowImages.forEach { image ->
                            SendImageTile(
                                image = image,
                                selected = image.id in sendPage.selectedImageIds,
                                onClick = { onImageClick(image.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - rowImages.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sendPage.visibleImages, key = { image -> image.id }) { image ->
                    SendImageTile(
                        image = image,
                        selected = image.id in sendPage.selectedImageIds,
                        onClick = { onImageClick(image.id) },
                        modifier = Modifier.width(92.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SendImagesEmptyState(
    permissionState: SendPermissionState,
    onPickImages: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = permissionState.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onPickImages) {
            Text(text = "选择图片")
        }
    }
}

@Composable
private fun SendImageTile(
    image: SendImageItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                },
                shape = RoundedCornerShape(22.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SendPlatformImageThumbnail(
            image = image,
            modifier = Modifier.fillMaxSize(),
        )
        if (selected) {
            SelectionBadge(modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun SendFileSection(
    files: List<SendFileItem>,
    onPickFiles: () -> Unit,
    onRemoveFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f),
                shape = RoundedCornerShape(28.dp),
            )
            .clickable(enabled = files.isEmpty(), onClick = onPickFiles)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "文件",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FilledTonalButton(onClick = onPickFiles) {
                Icon(
                    imageVector = LucidePlusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "继续添加")
            }
        }

        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 118.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = LucideFileIcon,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "点击选择需要传输的文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                files.forEach { file ->
                    SendFileRow(
                        file = file,
                        onRemove = { onRemoveFile(file.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SendFileRow(
    file: SendFileItem,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (file.fileType == SendFileType.Image) LucideImageIcon else LucideFileIcon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = file.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = file.sizeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = LucideXIcon,
                contentDescription = "移除",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SelectionBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LucideCheckIcon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun PikoSettingsScreen(
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = bottomContentPadding + 20.dp),
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
private fun ReceiveHistoryCard(history: ReceiveHistoryItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReceiveHistoryPreview(history = history)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = history.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = history.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReceiveHistoryPreview(history: ReceiveHistoryItem) {
    val imagePreviewDescription = history.imagePreviewDescription

    when {
        history.fileCount > 1 -> MultiFilePreviewBadge(
            fileType = history.primaryFile.fileType,
            fileCount = history.fileCount,
            size = 60.dp,
        )

        imagePreviewDescription != null -> ImageThumbnailPreview(
            description = imagePreviewDescription,
            size = 60.dp,
        )

        else -> FileTypePreviewBadge(
            fileType = history.primaryFile.fileType,
            size = 60.dp,
        )
    }
}

@Composable
private fun MultiFilePreviewBadge(
    fileType: ReceiveFileType,
    fileCount: Int,
    size: Dp,
) {
    val corner = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        LayeredPreviewCard(
            modifier = Modifier
                .size(size * 0.7f)
                .offset(x = 8.dp, y = (-6).dp)
                .graphicsLayer { rotationZ = 8f },
            shape = corner,
            backgroundBrush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.surface,
                ),
            ),
        )
        LayeredPreviewCard(
            modifier = Modifier
                .size(size * 0.74f)
                .offset(x = (-8).dp, y = 8.dp)
                .graphicsLayer { rotationZ = -10f },
            shape = corner,
            backgroundBrush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ),
        )
        LayeredPreviewCard(
            modifier = Modifier
                .size(size * 0.78f)
                .graphicsLayer { rotationZ = -2f },
            shape = corner,
            backgroundBrush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ),
        ) {
            FileTypeGlyph(
                label = fileType.previewLabel,
                modifier = Modifier.align(Alignment.Center),
            )
            Text(
                text = "+${fileCount - 1}",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun FileTypePreviewBadge(
    fileType: ReceiveFileType,
    size: Dp,
) {
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.56f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(14.dp),
                ),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(topEnd = 8.dp, bottomStart = 6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
            FileTypeGlyph(
                label = fileType.previewLabel,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun ImageThumbnailPreview(
    description: String,
    size: Dp,
) {
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.primaryContainer,
                    ),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp)
                .size(11.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(size * 0.34f)
                .offset(y = 8.dp)
                .graphicsLayer { rotationZ = -7f }
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 26.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text = description.take(6),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LayeredPreviewCard(
    modifier: Modifier,
    shape: RoundedCornerShape,
    backgroundBrush: Brush,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundBrush)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = shape,
            ),
        content = content,
    )
}

@Composable
private fun FileTypeGlyph(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ReceiveHistoryEmptyState(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyReceiveHistoryIcon()
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "还没有接收过文件",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyReceiveHistoryIcon() {
    Box(
        modifier = Modifier
            .size(104.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LucideInboxIcon,
            contentDescription = null,
            modifier = Modifier.size(54.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
        )
    }
}

private val LucideInboxIcon: ImageVector = ImageVector.Builder(
    name = "LucideInbox",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(22f, 12f)
        lineTo(16f, 12f)
        lineTo(14f, 15f)
        lineTo(10f, 15f)
        lineTo(8f, 12f)
        lineTo(2f, 12f)
    }
    lucidePath {
        moveTo(5.45f, 5.11f)
        lineTo(2f, 12f)
        verticalLineToRelative(6f)
        arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
        horizontalLineToRelative(16f)
        arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
        verticalLineToRelative(-6f)
        lineToRelative(-3.45f, -6.89f)
        arcToRelative(2f, 2f, 0f, false, false, -1.79f, -1.11f)
        horizontalLineTo(7.24f)
        arcToRelative(2f, 2f, 0f, false, false, -1.79f, 1.11f)
        close()
    }
}.build()

private val LucideCheckIcon: ImageVector = ImageVector.Builder(
    name = "LucideCheck",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(20f, 6f)
        lineTo(9f, 17f)
        lineToRelative(-5f, -5f)
    }
}.build()

private val LucideXIcon: ImageVector = ImageVector.Builder(
    name = "LucideX",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(18f, 6f)
        lineTo(6f, 18f)
    }
    lucidePath {
        moveTo(6f, 6f)
        lineTo(18f, 18f)
    }
}.build()

private val LucideFileIcon: ImageVector = ImageVector.Builder(
    name = "LucideFile",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(6f, 22f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
        verticalLineTo(4f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
        horizontalLineToRelative(8f)
        arcToRelative(2.4f, 2.4f, 0f, false, true, 1.704f, 0.706f)
        lineToRelative(3.588f, 3.588f)
        arcToRelative(2.4f, 2.4f, 0f, false, true, 0.708f, 1.706f)
        verticalLineToRelative(12f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
        close()
    }
    lucidePath {
        moveTo(14f, 2f)
        verticalLineToRelative(5f)
        arcToRelative(1f, 1f, 0f, false, false, 1f, 1f)
        horizontalLineToRelative(5f)
    }
}.build()

internal val LucideImageIcon: ImageVector = ImageVector.Builder(
    name = "LucideImage",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(5f, 3f)
        horizontalLineToRelative(14f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
        verticalLineToRelative(14f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
        horizontalLineTo(5f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
        verticalLineTo(5f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
        close()
    }
    lucidePath {
        moveTo(9f, 9f)
        moveToRelative(-2f, 0f)
        arcToRelative(2f, 2f, 0f, true, false, 4f, 0f)
        arcToRelative(2f, 2f, 0f, true, false, -4f, 0f)
    }
    lucidePath {
        moveTo(21f, 15f)
        lineToRelative(-3.086f, -3.086f)
        arcToRelative(2f, 2f, 0f, false, false, -2.828f, 0f)
        lineTo(6f, 21f)
    }
}.build()

private val LucidePlusIcon: ImageVector = ImageVector.Builder(
    name = "LucidePlus",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    lucidePath {
        moveTo(5f, 12f)
        horizontalLineToRelative(14f)
    }
    lucidePath {
        moveTo(12f, 5f)
        verticalLineToRelative(14f)
    }
}.build()

private fun ImageVector.Builder.lucidePath(pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = pathBuilder,
    )
}

private val String.avatarLabel: String
    get() = trim().take(2).ifBlank { "设备" }

private val SendPermissionState.label: String
    get() = when (this) {
        SendPermissionState.Unknown -> "正在读取最近图片"
        SendPermissionState.Requesting -> "等待相册授权"
        SendPermissionState.Granted -> "还没有读取到最近图片"
        SendPermissionState.Denied -> "相册权限未开启"
        SendPermissionState.Unavailable -> "当前平台暂不可读取相册"
    }

private val SendLanDiscoveryState.label: String
    get() = when (this) {
        SendLanDiscoveryState.Idle -> "等待搜索局域网设备"
        SendLanDiscoveryState.Searching -> "正在搜索局域网设备"
        SendLanDiscoveryState.Found -> "已发现局域网设备"
        SendLanDiscoveryState.Empty -> "暂无局域网设备"
        SendLanDiscoveryState.Unavailable -> "当前平台暂不可发现设备"
        SendLanDiscoveryState.Failed -> "局域网发现失败"
    }

private val SendFileItem.sizeLabel: String
    get() {
        val units = listOf("B", "KB", "MB", "GB")
        var value = sizeBytes.toDouble().coerceAtLeast(0.0)
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

private val ReceiveFileType.previewLabel: String
    get() = when (this) {
        ReceiveFileType.Document -> "DOC"
        ReceiveFileType.Spreadsheet -> "XLS"
        ReceiveFileType.Image -> "IMG"
        ReceiveFileType.Video -> "VID"
        ReceiveFileType.Archive -> "ZIP"
        ReceiveFileType.Other -> "FILE"
    }
