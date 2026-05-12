package com.piko.app.domain

private const val CURRENT_DEVICE_ID = "current-device"

data class SendPageState(
    val myDevices: List<SendDevice>,
    val lanDevices: List<SendDevice>,
    val friendDevices: List<SendDevice>,
    val selectedDeviceIds: Set<String>,
    val recentImages: List<SendImageItem>,
    val selectedImageIds: Set<String>,
    val selectedFiles: List<SendFileItem>,
    val imageSectionExpanded: Boolean,
    val photoPermissionState: SendPermissionState,
    val lanDiscoveryState: SendLanDiscoveryState,
    val activeTransfer: SendTransferState,
) {
    val visibleImages: List<SendImageItem>
        get() {
            if (imageSectionExpanded) {
                return recentImages
            }

            val selectedImages = recentImages.filter { it.id in selectedImageIds }
            return selectedImages.ifEmpty { recentImages.take(6) }
        }

    val selectedTransferItems: List<SendTransferItem>
        get() {
            val images = recentImages
                .filter { image -> image.id in selectedImageIds }
                .map { image ->
                    SendTransferItem(
                        id = image.id,
                        displayName = image.displayName,
                        sizeBytes = image.sizeBytes,
                        fileType = SendFileType.Image,
                        sourceUri = image.uri,
                        inlineBytes = image.thumbnailBytes,
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
            return (images + files).distinctBy { item -> item.id }
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

    fun toggleImageSelection(imageId: String): SendPageState {
        val nextIds = if (imageId in selectedImageIds) {
            selectedImageIds - imageId
        } else {
            selectedImageIds + imageId
        }
        return copy(selectedImageIds = nextIds)
    }

    fun toggleImageSectionExpanded(): SendPageState {
        return copy(imageSectionExpanded = !imageSectionExpanded)
    }

    fun replaceRecentImages(images: List<SendImageItem>): SendPageState {
        val imageIds = images.map { it.id }.toSet()
        return copy(
            recentImages = images,
            selectedImageIds = selectedImageIds.intersect(imageIds),
        )
    }

    fun addSelectedImages(images: List<SendImageItem>): SendPageState {
        val merged = (recentImages + images).distinctBy { it.id }
        return copy(
            recentImages = merged,
            selectedImageIds = selectedImageIds + images.map { it.id },
        )
    }

    fun addSelectedFiles(files: List<SendFileItem>): SendPageState {
        return copy(selectedFiles = (selectedFiles + files).distinctBy { it.id })
    }

    fun removeSelectedFile(fileId: String): SendPageState {
        return copy(selectedFiles = selectedFiles.filterNot { it.id == fileId })
    }

    fun updateLanDevices(devices: List<SendDevice>): SendPageState {
        val nextLanDevices = devices.filterNot { device -> device.isCurrentDevicePlaceholder }
        val deviceIds = allDevices(nextLanDevices).map { it.id }.toSet()
        return copy(
            lanDevices = nextLanDevices,
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
                friendDevices = listOf(
                    SendDevice(
                        id = "friend-demo-cavan",
                        name = "Cavan",
                        group = SendDeviceGroup.Friend,
                        isSample = true,
                    ),
                    SendDevice(
                        id = "friend-demo-piko",
                        name = "Piko",
                        group = SendDeviceGroup.Friend,
                        isSample = true,
                    ),
                ),
                selectedDeviceIds = emptySet(),
                recentImages = emptyList(),
                selectedImageIds = emptySet(),
                selectedFiles = emptyList(),
                imageSectionExpanded = false,
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
) {
    val isConnectable: Boolean
        get() = !host.isNullOrBlank() && port != null && port > 0
}

private val SendDevice.isCurrentDevicePlaceholder: Boolean
    get() = id == CURRENT_DEVICE_ID || group == SendDeviceGroup.MyDevice

enum class SendDeviceGroup {
    MyDevice,
    Lan,
    Friend,
}

data class SendImageItem(
    val id: String,
    val displayName: String,
    val uri: String,
    val sizeBytes: Long = 0L,
    val thumbnailBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SendImageItem) return false
        return id == other.id &&
            displayName == other.displayName &&
            uri == other.uri &&
            sizeBytes == other.sizeBytes &&
            thumbnailBytes.contentEquals(other.thumbnailBytes)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + sizeBytes.hashCode()
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
