package com.piko.app.domain

private const val CURRENT_DEVICE_ID = "current-device"

data class SendPageState(
    val myDevices: List<SendDevice>,
    val lanDevices: List<SendDevice>,
    val friendDevices: List<SendDevice>,
    val selectedDeviceIds: Set<String>,
    val selectedMediaItems: List<SendMediaItem>,
    val selectedFiles: List<SendFileItem>,
    val photoPermissionState: SendPermissionState,
    val lanDiscoveryState: SendLanDiscoveryState,
    val activeTransfer: SendTransferState,
) {
    val selectedTransferItems: List<SendTransferItem>
        get() {
            val media = selectedMediaItems
                .map { item ->
                    SendTransferItem(
                        id = item.id,
                        displayName = item.displayName,
                        sizeBytes = item.sizeBytes,
                        fileType = item.fileType,
                        sourceUri = item.uri,
                        inlineBytes = item.thumbnailBytes,
                    )
                }
            val files = selectedFiles.map { file ->
                SendTransferItem(
                    id = file.id,
                    displayName = file.displayName,
                    sizeBytes = file.sizeBytes,
                    fileType = file.fileType,
                    sourceUri = file.sourceUri,
                    inlineBytes = null,
                )
            }
            return (media + files).distinctBy { item -> item.id }
        }

    val selectedDevices: List<SendDevice>
        get() = allDevices().filter { device -> device.id in selectedDeviceIds }

    val transferTotalBytes: Long
        get() = selectedTransferItems.sumOf { item -> item.sizeBytes }

    val transferSummaryTitle: String
        get() = selectedTransferItems.transferTitle()

    val canSend: Boolean
        get() = selectedDevices.any { device -> device.isConnectable } &&
            selectedTransferItems.isNotEmpty() &&
            !activeTransfer.isBlockingSend

    fun toggleDeviceSelection(deviceId: String): SendPageState {
        val nextIds = if (deviceId in selectedDeviceIds) {
            selectedDeviceIds - deviceId
        } else {
            selectedDeviceIds + deviceId
        }
        return copy(selectedDeviceIds = nextIds)
    }

    fun addSelectedMedia(items: List<SendMediaItem>): SendPageState {
        return copy(selectedMediaItems = (selectedMediaItems + items).distinctBy { it.id })
    }

    fun removeSelectedMedia(mediaId: String): SendPageState {
        return copy(selectedMediaItems = selectedMediaItems.filterNot { it.id == mediaId })
    }

    fun addSelectedFiles(files: List<SendFileItem>): SendPageState {
        return copy(selectedFiles = (selectedFiles + files).distinctBy { it.id })
    }

    fun removeSelectedFile(fileId: String): SendPageState {
        return copy(selectedFiles = selectedFiles.filterNot { it.id == fileId })
    }

    fun clearSelectedItems(): SendPageState {
        return copy(
            selectedMediaItems = emptyList(),
            selectedFiles = emptyList(),
        )
    }

    fun updateLanDevices(devices: List<SendDevice>): SendPageState {
        val nextLanDevices = devices.filterNot { device -> device.isCurrentDevicePlaceholder }
        val deviceIds = allDevices(nextLanDevices).map { it.id }.toSet()
        return copy(
            lanDevices = nextLanDevices,
            selectedDeviceIds = selectedDeviceIds.intersect(deviceIds),
        )
    }

    fun replaceFriendDevices(devices: List<FriendDevice>): SendPageState {
        val nextFriendDevices = devices.map { device ->
            SendDevice(
                id = "friend-${device.deviceId}",
                name = device.deviceName,
                group = SendDeviceGroup.Friend,
                subtitle = "${device.platformLabel} · ${device.presenceLabel}",
                host = null,
                port = null,
                platformHint = device.platform,
                transportPath = SendTransportPath.P2P,
                receiverUserId = device.ownerUserId,
                receiverDeviceId = device.deviceId,
                receiverEd25519PubB64 = device.ed25519PubB64,
                receiverX25519PubB64 = device.x25519PubB64,
                online = device.online,
            )
        }
        val deviceIds = allDevices().filterNot { it.group == SendDeviceGroup.Friend }.map { it.id }.toSet() +
            nextFriendDevices.map { it.id }
        return copy(
            friendDevices = nextFriendDevices,
            selectedDeviceIds = selectedDeviceIds.intersect(deviceIds),
        )
    }

    fun updatePhotoPermission(state: SendPermissionState): SendPageState {
        return copy(photoPermissionState = state)
    }

    fun updateLanDiscovery(state: SendLanDiscoveryState): SendPageState {
        return copy(lanDiscoveryState = state)
    }

    fun buildTransferRequest(senderName: String = "当前设备"): SendTransferRequest? {
        if (!canSend) {
            return null
        }
        return SendTransferRequest(
            senderName = senderName,
            targets = selectedDevices.filter { device -> device.isConnectable },
            items = selectedTransferItems,
        )
    }

    fun startTransfer(transferId: String): SendPageState {
        val request = buildTransferRequest()
        val items = request?.items ?: selectedTransferItems
        val targets = request?.targets ?: selectedDevices
        return copy(
            activeTransfer = SendTransferState(
                transferId = transferId,
                status = SendTransferStatus.Transferring,
                targets = targets,
                items = items,
                totalBytes = items.sumOf { item -> item.sizeBytes } * targets.size.coerceAtLeast(1),
                completedBytes = 0L,
            ),
        )
    }

    fun applyTransferEvent(event: SendTransferEvent): SendPageState {
        val current = activeTransfer
        if (current.transferId != null && event.transferId != current.transferId) {
            return this
        }
        return when (event) {
            is SendTransferEvent.Started -> copy(
                activeTransfer = SendTransferState(
                    transferId = event.transferId,
                    status = SendTransferStatus.Transferring,
                    targets = event.request.targets,
                    items = event.request.items,
                    totalBytes = event.totalBytes,
                    completedBytes = 0L,
                ),
            )

            is SendTransferEvent.Progress -> copy(
                activeTransfer = current.copy(
                    transferId = event.transferId,
                    status = SendTransferStatus.Transferring,
                    completedBytes = event.completedBytes,
                    totalBytes = event.totalBytes,
                    errorMessage = null,
                ),
            )

            is SendTransferEvent.TransportNotice -> this
            is SendTransferEvent.Paused -> copy(activeTransfer = current.copy(status = SendTransferStatus.Paused))
            is SendTransferEvent.Canceled -> copy(activeTransfer = SendTransferState.Idle)
            is SendTransferEvent.Completed -> copy(activeTransfer = SendTransferState.Idle)

            is SendTransferEvent.Failed -> copy(
                activeTransfer = current.copy(
                    status = SendTransferStatus.Failed,
                    errorMessage = event.message,
                ),
            )
        }
    }

    private fun allDevices(nextLanDevices: List<SendDevice> = lanDevices): List<SendDevice> {
        return myDevices + nextLanDevices + friendDevices
    }

    companion object {
        fun initial(currentDeviceName: String): SendPageState {
            return SendPageState(
                myDevices = emptyList(),
                lanDevices = emptyList(),
                friendDevices = emptyList(),
                selectedDeviceIds = emptySet(),
                selectedMediaItems = emptyList(),
                selectedFiles = emptyList(),
                photoPermissionState = SendPermissionState.Unknown,
                lanDiscoveryState = SendLanDiscoveryState.Idle,
                activeTransfer = SendTransferState.Idle,
            )
        }
    }
}

data class SendDevice(
    val id: String,
    val name: String,
    val group: SendDeviceGroup,
    val subtitle: String? = null,
    val isSample: Boolean = false,
    val host: String? = null,
    val port: Int? = null,
    val platformHint: String? = null,
    val transportPath: SendTransportPath = SendTransportPath.Lan,
    val receiverUserId: String? = null,
    val receiverDeviceId: String? = null,
    val receiverEd25519PubB64: String? = null,
    val receiverX25519PubB64: String? = null,
    val online: Boolean = true,
) {
    val isConnectable: Boolean
        get() = when (transportPath) {
            SendTransportPath.Lan -> !host.isNullOrBlank() && port != null && port > 0
            SendTransportPath.P2P -> !receiverUserId.isNullOrBlank() &&
                !receiverDeviceId.isNullOrBlank() &&
                !receiverEd25519PubB64.isNullOrBlank() &&
                !receiverX25519PubB64.isNullOrBlank()
        }
}

private val SendDevice.isCurrentDevicePlaceholder: Boolean
    get() = id == CURRENT_DEVICE_ID || group == SendDeviceGroup.MyDevice

enum class SendTransportPath {
    Lan,
    P2P,
}

enum class SendDeviceGroup {
    MyDevice,
    Lan,
    Friend,
}

private val FriendDevice.platformLabel: String
    get() = when (platform.lowercase()) {
        "ios" -> "iOS"
        "android" -> "Android"
        "macos" -> "macOS"
        "windows" -> "Windows"
        else -> platform
    }

data class SendMediaItem(
    val id: String,
    val displayName: String,
    val uri: String,
    val sizeBytes: Long = 0L,
    val fileType: SendFileType = SendFileType.Image,
    val thumbnailBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SendMediaItem) return false
        return id == other.id &&
            displayName == other.displayName &&
            uri == other.uri &&
            sizeBytes == other.sizeBytes &&
            fileType == other.fileType &&
            thumbnailBytes.contentEquals(other.thumbnailBytes)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + fileType.hashCode()
        result = 31 * result + (thumbnailBytes?.contentHashCode() ?: 0)
        return result
    }
}

data class SendFileItem(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val fileType: SendFileType,
    val sourceUri: String = id,
)

data class SendTransferItem(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val fileType: SendFileType,
    val sourceUri: String,
    val inlineBytes: ByteArray? = null,
)

data class SendTransferRequest(
    val senderName: String,
    val targets: List<SendDevice>,
    val items: List<SendTransferItem>,
) {
    val totalBytes: Long
        get() = items.sumOf { item -> item.sizeBytes } * targets.size.coerceAtLeast(1)
}

enum class SendTransferStatus {
    Idle,
    Transferring,
    Paused,
    Canceled,
    Failed,
    Completed,
}

data class SendTransferState(
    val transferId: String?,
    val status: SendTransferStatus,
    val targets: List<SendDevice>,
    val items: List<SendTransferItem>,
    val totalBytes: Long,
    val completedBytes: Long,
    val errorMessage: String? = null,
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else (completedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    val title: String
        get() = items.transferTitle()

    val subtitle: String
        get() = totalBytes.sizeLabel

    val primaryFileType: SendFileType
        get() = items.firstOrNull()?.fileType ?: SendFileType.Other

    val isBlockingSend: Boolean
        get() = status == SendTransferStatus.Transferring

    companion object {
        val Idle = SendTransferState(
            transferId = null,
            status = SendTransferStatus.Idle,
            targets = emptyList(),
            items = emptyList(),
            totalBytes = 0L,
            completedBytes = 0L,
        )
    }
}

sealed class SendTransferEvent {
    abstract val transferId: String

    data class Started(
        override val transferId: String,
        val request: SendTransferRequest,
        val totalBytes: Long,
    ) : SendTransferEvent()

    data class Progress(
        override val transferId: String,
        val completedBytes: Long,
        val totalBytes: Long,
    ) : SendTransferEvent()

    data class TransportNotice(
        override val transferId: String,
        val message: String,
    ) : SendTransferEvent()

    data class Paused(override val transferId: String) : SendTransferEvent()
    data class Canceled(override val transferId: String) : SendTransferEvent()
    data class Completed(override val transferId: String) : SendTransferEvent()

    data class Failed(
        override val transferId: String,
        val message: String,
    ) : SendTransferEvent()
}

enum class SendFileType(
    val label: String,
) {
    Image("图片"),
    Document("文档"),
    Spreadsheet("表格"),
    Video("视频"),
    Archive("压缩包"),
    Other("文件"),
}

enum class SendPermissionState {
    Unknown,
    Requesting,
    Granted,
    Denied,
    Unavailable,
}

enum class SendLanDiscoveryState {
    Idle,
    Searching,
    Found,
    Empty,
    Unavailable,
    Failed,
}

internal fun List<SendTransferItem>.transferTitle(): String {
    val first = firstOrNull() ?: return ""
    return if (size == 1) {
        first.displayName
    } else {
        "${first.displayName} + ${size - 1} 个文件"
    }
}

internal val Long.sizeLabel: String
    get() {
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
