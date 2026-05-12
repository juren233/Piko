package com.piko.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.piko.app.domain.PikoHomeState
import com.piko.app.domain.SendLanDiscoveryState
import com.piko.app.domain.SendPageState
import com.piko.app.domain.SendTransferEvent
import com.piko.app.domain.SendTransferStatus
import com.piko.app.platform.SendPlatformActions

@Composable
internal fun PikoSendScreen(
    sendPage: SendPageState,
    onStateMutate: ((PikoHomeState) -> PikoHomeState) -> Unit,
    sendPlatformActions: SendPlatformActions,
    bottomContentPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val selectedTargetCount = sendPage.selectedDevices.size
    val selectedItemCount = sendPage.selectedTransferItems.size

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
            sendPlatformActions.stopLanDiscovery()
        }
    }

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
                    title = "发送",
                    subtitle = "选择目标和文件后直接传输",
                    metric = "${selectedTargetCount} 台 / ${selectedItemCount} 项",
                )
            }
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
}
