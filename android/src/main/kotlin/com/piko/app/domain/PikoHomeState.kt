package com.piko.app.domain

data class PikoHomeState(
    val currentDeviceName: String,
    val trustedDeviceCount: Int,
    val pendingReceiveCount: Int,
    val receiveHistory: List<ReceiveHistoryItem>,
    val activeReceive: ReceiveTransferState,
    val sendPage: SendPageState,
    val friendsState: FriendsState,
) {
    val receiveHistoryDescending: List<ReceiveHistoryItem>
        get() = receiveHistory.sortedByDescending { it.receivedAtEpochMillis }

    fun applyReceiveTransferEvent(event: ReceiveTransferEvent): PikoHomeState {
        val current = activeReceive
        if (current.transferId != null && event.transferId != current.transferId) {
            return this
        }
        return when (event) {
            is ReceiveTransferEvent.Started -> {
                val sameTransfer = current.transferId == event.transferId
                val hasCurrentDetails = sameTransfer &&
                    (current.files.isNotEmpty() || current.totalBytes > 0L || current.completedBytes > 0L)
                if (event.files.isEmpty() && hasCurrentDetails) {
                    copy(
                        activeReceive = current.copy(
                            senderName = event.senderName.ifBlank { current.senderName },
                        ),
                    )
                } else {
                    copy(
                        activeReceive = ReceiveTransferState(
                            transferId = event.transferId,
                            senderName = event.senderName,
                            files = event.files,
                            totalBytes = event.totalBytes,
                            completedBytes = if (sameTransfer) current.completedBytes.coerceAtMost(event.totalBytes) else 0L,
                            requiresConfirmation = event.requiresConfirmation,
                        ),
                    )
                }
            }

            is ReceiveTransferEvent.Progress -> copy(
                activeReceive = current.copy(
                    completedBytes = event.completedBytes,
                    totalBytes = event.totalBytes,
                ),
            )

            is ReceiveTransferEvent.Canceled -> copy(activeReceive = ReceiveTransferState.Idle)
            is ReceiveTransferEvent.Failed -> copy(activeReceive = ReceiveTransferState.Idle)
            is ReceiveTransferEvent.Notice -> this
            is ReceiveTransferEvent.Completed -> {
                val files = event.files
                if (files.isEmpty()) {
                    copy(activeReceive = ReceiveTransferState.Idle)
                } else {
                    copy(
                        activeReceive = ReceiveTransferState.Idle,
                        receiveHistory = listOf(
                            ReceiveHistoryItem(
                                id = event.transferId,
                                receivedAtEpochMillis = event.receivedAtEpochMillis,
                                receivedAtLabel = event.receivedAtLabel,
                                sourceDeviceName = event.senderName.visibleDeviceName,
                                fileCount = files.size,
                                files = files,
                            ),
                        ) + receiveHistory,
                    )
                }
            }
        }
    }

    fun withSampleReceiveHistory(): PikoHomeState {
        return copy(
            receiveHistory = sampleReceiveHistory.sortedByDescending { it.receivedAtEpochMillis },
        )
    }

    fun removeReceiveHistory(id: String): PikoHomeState {
        return copy(receiveHistory = receiveHistory.filterNot { it.id == id })
    }

    companion object {
        fun initial(
            currentDeviceName: String = "当前设备",
            receiveHistory: List<ReceiveHistoryItem> = emptyList(),
        ): PikoHomeState {
            return PikoHomeState(
                currentDeviceName = currentDeviceName,
                trustedDeviceCount = 0,
                pendingReceiveCount = 0,
                receiveHistory = receiveHistory,
                activeReceive = ReceiveTransferState.Idle,
                sendPage = SendPageState.initial(currentDeviceName = currentDeviceName),
                friendsState = FriendsState.Empty,
            )
        }

        private val sampleReceiveHistory = listOf(
            ReceiveHistoryItem(
                id = "receive-history-multi",
                receivedAtEpochMillis = 1_747_011_600_000,
                receivedAtLabel = "2026.05.08",
                sourceDeviceName = "MacBook Pro",
                fileCount = 3,
                files = listOf(
                    ReceiveHistoryFile(
                        displayName = "旅行计划.pdf",
                        fileType = ReceiveFileType.Document,
                        sizeBytes = 2_300_000,
                    ),
                    ReceiveHistoryFile(
                        displayName = "费用清单.xlsx",
                        fileType = ReceiveFileType.Spreadsheet,
                        sizeBytes = 680_000,
                    ),
                    ReceiveHistoryFile(
                        displayName = "封面图.png",
                        fileType = ReceiveFileType.Image,
                        sizeBytes = 1_800_000,
                    ),
                ),
            ),
            ReceiveHistoryItem(
                id = "receive-history-image",
                receivedAtEpochMillis = 1_747_004_400_000,
                receivedAtLabel = "2026.05.07",
                sourceDeviceName = "iPhone 16",
                fileCount = 1,
                files = listOf(
                    ReceiveHistoryFile(
                        displayName = "IMG_20260507_183012.jpg",
                        fileType = ReceiveFileType.Image,
                        sizeBytes = 3_600_000,
                    ),
                ),
            ),
            ReceiveHistoryItem(
                id = "receive-history-single",
                receivedAtEpochMillis = 1_746_917_100_000,
                receivedAtLabel = "2026.05.06",
                sourceDeviceName = "Windows 台式机",
                fileCount = 1,
                files = listOf(
                    ReceiveHistoryFile(
                        displayName = "Piko-发布说明.docx",
                        fileType = ReceiveFileType.Document,
                        sizeBytes = 940_000,
                    ),
                ),
            ),
        )
    }
}

data class ReceiveHistoryItem(
    val id: String,
    val receivedAtEpochMillis: Long,
    val receivedAtLabel: String,
    val sourceDeviceName: String,
    val fileCount: Int,
    val files: List<ReceiveHistoryFile>,
) {
    init {
        require(files.isNotEmpty()) { "接收记录至少需要一个文件" }
        require(fileCount == files.size) { "文件数量需要与文件列表一致" }
    }

    val primaryFile: ReceiveHistoryFile
        get() = files.first()

    val hasMediaPreview: Boolean
        get() = files.any { it.isMediaPreview && it.thumbnailBytes?.isNotEmpty() == true }

    val mediaPreviewDescription: String?
        get() = primaryFile.displayName.takeIf { primaryFile.isMediaPreview && primaryFile.thumbnailBytes?.isNotEmpty() == true }

    val title: String
        get() = if (fileCount == 1) {
            primaryFile.displayName
        } else {
            "${primaryFile.displayName} + ${fileCount - 1} 个文件"
        }

    val subtitle: String
        get() = files.sumOf { file -> file.sizeBytes }.sizeLabel

    val deleteConfirmationTitle: String
        get() = if (fileCount == 1) {
            "真的要删除${primaryFile.displayName}吗？"
        } else {
            "真的要删除这${fileCount}个吗？"
        }

    val deleteConfirmationBody: String
        get() = if (fileCount == 1) {
            "此操作不可逆！"
        } else {
            "将会删除：${files.joinToString("、") { it.displayName }} 此操作不可逆！"
        }
}

data class ReceiveHistoryFile(
    val displayName: String,
    val fileType: ReceiveFileType,
    val sizeBytes: Long,
    val thumbnailBytes: ByteArray? = null,
    val savedUri: String? = null,
) {
    val isMediaPreview: Boolean
        get() = fileType == ReceiveFileType.Image || fileType == ReceiveFileType.Video

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReceiveHistoryFile) return false
        return displayName == other.displayName &&
            fileType == other.fileType &&
            sizeBytes == other.sizeBytes &&
            thumbnailBytes.contentEquals(other.thumbnailBytes) &&
            savedUri == other.savedUri
    }

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + fileType.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + (thumbnailBytes?.contentHashCode() ?: 0)
        result = 31 * result + (savedUri?.hashCode() ?: 0)
        return result
    }
}

data class ReceiveTransferState(
    val transferId: String?,
    val senderName: String,
    val files: List<ReceiveHistoryFile>,
    val totalBytes: Long,
    val completedBytes: Long,
    val requiresConfirmation: Boolean = false,
) {
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else (completedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    val title: String
        get() = when {
            files.isEmpty() && requiresConfirmation -> "${senderName.visibleDeviceName}想发送文件"
            files.isEmpty() -> "正在准备从${senderName.visibleDeviceName}接收"
            requiresConfirmation -> "${senderName.visibleDeviceName}想发送${files.size}个文件"
            else -> "正在从${senderName.visibleDeviceName}接收${files.size}个文件"
        }

    val subtitle: String
        get() = if (files.isEmpty()) "等待文件清单…" else "${completedBytes.sizeLabel}/${totalBytes.sizeLabel}"

    val receiveConfirmationMessage: String
        get() {
            if (files.isEmpty()) {
                return "${senderName.visibleDeviceName}想给你发送文件，等待文件清单…"
            }
            val file = files.singleOrNull()
            return if (file != null) {
                "${senderName.visibleDeviceName}想发送${file.displayName}，大小${totalBytes.sizeLabel}"
            } else {
                "${senderName.visibleDeviceName}想发送${files.size}个文件，合计${totalBytes.sizeLabel}"
            }
        }

    val primaryFileType: ReceiveFileType
        get() = files.firstOrNull()?.fileType ?: ReceiveFileType.Other

    companion object {
        val Idle = ReceiveTransferState(
            transferId = null,
            senderName = "",
            files = emptyList(),
            totalBytes = 0L,
            completedBytes = 0L,
            requiresConfirmation = false,
        )
    }
}

sealed class ReceiveTransferEvent {
    abstract val transferId: String

    data class Started(
        override val transferId: String,
        val senderName: String,
        val files: List<ReceiveHistoryFile>,
        val totalBytes: Long,
        val requiresConfirmation: Boolean = false,
    ) : ReceiveTransferEvent()

    data class Progress(
        override val transferId: String,
        val completedBytes: Long,
        val totalBytes: Long,
    ) : ReceiveTransferEvent()

    data class Completed(
        override val transferId: String,
        val senderName: String,
        val files: List<ReceiveHistoryFile>,
        val receivedAtEpochMillis: Long,
        val receivedAtLabel: String,
    ) : ReceiveTransferEvent()

    data class Canceled(override val transferId: String) : ReceiveTransferEvent()
    data class Failed(override val transferId: String, val message: String) : ReceiveTransferEvent()

    data class Notice(
        override val transferId: String,
        val message: String,
    ) : ReceiveTransferEvent()
}

private val String.visibleDeviceName: String
    get() = substringBefore("@").trim().ifBlank { ReceivePreparingPlaceholder }

enum class ReceiveFileType(
    val label: String,
) {
    Document("文档"),
    Spreadsheet("表格"),
    Image("图片"),
    Video("视频"),
    Archive("压缩包"),
    Other("文件"),
}
