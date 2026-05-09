package com.piko.app

data class PikoHomeState(
    val currentDeviceName: String,
    val trustedDeviceCount: Int,
    val pendingReceiveCount: Int,
    val receiveHistory: List<ReceiveHistoryItem>,
    val sendPage: SendPageState,
) {
    val receiveHistoryDescending: List<ReceiveHistoryItem>
        get() = receiveHistory.sortedByDescending { it.receivedAtEpochMillis }

    fun withSampleReceiveHistory(): PikoHomeState {
        return copy(
            receiveHistory = sampleReceiveHistory.sortedByDescending { it.receivedAtEpochMillis },
        )
    }

    companion object {
        fun initial(currentDeviceName: String = "当前设备"): PikoHomeState {
            return PikoHomeState(
                currentDeviceName = currentDeviceName,
                trustedDeviceCount = 0,
                pendingReceiveCount = 0,
                receiveHistory = emptyList(),
                sendPage = SendPageState.initial(currentDeviceName = currentDeviceName),
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
                        isImage = false,
                        thumbnailDescription = null,
                    ),
                    ReceiveHistoryFile(
                        displayName = "费用清单.xlsx",
                        fileType = ReceiveFileType.Spreadsheet,
                        isImage = false,
                        thumbnailDescription = null,
                    ),
                    ReceiveHistoryFile(
                        displayName = "封面图.png",
                        fileType = ReceiveFileType.Image,
                        isImage = true,
                        thumbnailDescription = "海边日落缩略图",
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
                        isImage = true,
                        thumbnailDescription = "晚霞照片缩略图",
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
                        isImage = false,
                        thumbnailDescription = null,
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

    val hasImagePreview: Boolean
        get() = files.any { it.isImage && !it.thumbnailDescription.isNullOrBlank() }

    val imagePreviewDescription: String?
        get() = primaryFile.thumbnailDescription?.takeIf { primaryFile.isImage && it.isNotBlank() }

    val title: String
        get() = if (fileCount == 1) {
            primaryFile.displayName
        } else {
            "${primaryFile.displayName}+${fileCount - 1}个文件"
        }

    val subtitle: String
        get() = "$receivedAtLabel - 来自 $sourceDeviceName"
}

data class ReceiveHistoryFile(
    val displayName: String,
    val fileType: ReceiveFileType,
    val isImage: Boolean,
    val thumbnailDescription: String?,
)

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
