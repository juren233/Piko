package com.piko.app

import com.piko.app.domain.PikoHomeState
import com.piko.app.domain.ReceiveFileType
import com.piko.app.domain.ReceiveHistoryFile
import com.piko.app.domain.ReceiveHistoryItem
import com.piko.app.domain.ReceiveTransferEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReceiveTransferStateTest {
    @Test
    fun receiveProgressShowsSenderTitleCountAndSizeRatio() {
        val state = PikoHomeState.initial(currentDeviceName = "Pixel")
            .applyReceiveTransferEvent(
                ReceiveTransferEvent.Started(
                    transferId = "receive-1",
                    senderName = "清亮竹影@4971",
                    files = listOf(
                        ReceiveHistoryFile(
                            displayName = "照片.jpg",
                            fileType = ReceiveFileType.Image,
                            sizeBytes = 2048,
                            thumbnailBytes = null,
                        ),
                        ReceiveHistoryFile(
                            displayName = "报告.pdf",
                            fileType = ReceiveFileType.Document,
                            sizeBytes = 1024,
                            thumbnailBytes = null,
                        ),
                    ),
                    totalBytes = 3072,
                ),
            )
            .applyReceiveTransferEvent(ReceiveTransferEvent.Progress("receive-1", completedBytes = 1024, totalBytes = 3072))

        assertEquals("正在从清亮竹影接收2个文件", state.activeReceive.title)
        assertEquals("1.0 KB/3.0 KB", state.activeReceive.subtitle)
        assertEquals(1024, state.activeReceive.completedBytes)
    }

    @Test
    fun placeholderStartedDoesNotOverwriteReceivedManifestOrProgress() {
        val file = ReceiveHistoryFile(
            displayName = "照片.jpg",
            fileType = ReceiveFileType.Image,
            sizeBytes = 2048,
            thumbnailBytes = null,
        )
        val state = PikoHomeState.initial(currentDeviceName = "Pixel")
            .applyReceiveTransferEvent(
                ReceiveTransferEvent.Started(
                    transferId = "receive-1",
                    senderName = "MacBook Pro",
                    files = listOf(file),
                    totalBytes = 2048,
                ),
            )
            .applyReceiveTransferEvent(ReceiveTransferEvent.Progress("receive-1", completedBytes = 1024, totalBytes = 2048))
            .applyReceiveTransferEvent(
                ReceiveTransferEvent.Started(
                    transferId = "receive-1",
                    senderName = "",
                    files = emptyList(),
                    totalBytes = 0,
                    requiresConfirmation = false,
                ),
            )

        assertEquals(listOf(file), state.activeReceive.files)
        assertEquals(2048, state.activeReceive.totalBytes)
        assertEquals(1024, state.activeReceive.completedBytes)
        assertEquals("1.0 KB/2.0 KB", state.activeReceive.subtitle)
    }

    @Test
    fun completedReceivePrependsHistoryAndClearsActiveTransfer() {
        val file = ReceiveHistoryFile(
            displayName = "照片.jpg",
            fileType = ReceiveFileType.Image,
            sizeBytes = 2048,
            thumbnailBytes = byteArrayOf(1, 2, 3),
        )
        val state = PikoHomeState.initial(currentDeviceName = "Pixel")
            .applyReceiveTransferEvent(
                ReceiveTransferEvent.Started(
                    transferId = "receive-1",
                    senderName = "MacBook Pro",
                    files = listOf(file),
                    totalBytes = 2048,
                ),
            )
            .applyReceiveTransferEvent(
                ReceiveTransferEvent.Completed(
                    transferId = "receive-1",
                    senderName = "MacBook Pro",
                    files = listOf(file),
                    receivedAtEpochMillis = 1_747_011_600_000,
                    receivedAtLabel = "刚刚",
                ),
            )

        assertNull(state.activeReceive.transferId)
        assertEquals("照片.jpg", state.receiveHistoryDescending.first().title)
        assertEquals("2.0 KB", state.receiveHistoryDescending.first().subtitle)
        assertEquals(byteArrayOf(1, 2, 3).toList(), state.receiveHistoryDescending.first().primaryFile.thumbnailBytes?.toList())
    }

    @Test
    fun videoReceiveKeepsMediaPreviewThumbnailInLatestHistory() {
        val file = ReceiveHistoryFile(
            displayName = "旅行短片.mp4",
            fileType = ReceiveFileType.Video,
            sizeBytes = 4096,
            thumbnailBytes = byteArrayOf(9, 8, 7),
        )
        val state = PikoHomeState.initial(currentDeviceName = "Pixel")
            .applyReceiveTransferEvent(
                ReceiveTransferEvent.Started(
                    transferId = "receive-video",
                    senderName = "MacBook Pro",
                    files = listOf(file),
                    totalBytes = 4096,
                ),
            )
            .applyReceiveTransferEvent(
                ReceiveTransferEvent.Completed(
                    transferId = "receive-video",
                    senderName = "MacBook Pro",
                    files = listOf(file),
                    receivedAtEpochMillis = 1_747_011_700_000,
                    receivedAtLabel = "刚刚",
                ),
            )

        val history = state.receiveHistoryDescending.first()
        assertEquals("旅行短片.mp4", history.mediaPreviewDescription)
        assertEquals(byteArrayOf(9, 8, 7).toList(), history.primaryFile.thumbnailBytes?.toList())
    }

    @Test
    fun receiveHistoryDeleteCopyRemovesOnlyMatchingHistory() {
        val first = ReceiveHistoryItem(
            id = "receive-first",
            receivedAtEpochMillis = 1_000,
            receivedAtLabel = "昨天",
            sourceDeviceName = "MacBook",
            fileCount = 1,
            files = listOf(
                ReceiveHistoryFile(
                    displayName = "报告.pdf",
                    fileType = ReceiveFileType.Document,
                    sizeBytes = 2_048,
                ),
            ),
        )
        val second = ReceiveHistoryItem(
            id = "receive-second",
            receivedAtEpochMillis = 2_000,
            receivedAtLabel = "刚刚",
            sourceDeviceName = "iPhone",
            fileCount = 1,
            files = listOf(
                ReceiveHistoryFile(
                    displayName = "照片.jpg",
                    fileType = ReceiveFileType.Image,
                    sizeBytes = 4_096,
                ),
            ),
        )

        val state = PikoHomeState.initial(receiveHistory = listOf(first, second))
            .removeReceiveHistory("receive-first")

        assertEquals(listOf("receive-second"), state.receiveHistory.map { it.id })
    }

    @Test
    fun receiveNoticeEventPassesThroughWithoutMutatingActiveReceive() {
        val initial = PikoHomeState.initial(currentDeviceName = "Pixel")
            .applyReceiveTransferEvent(
                ReceiveTransferEvent.Started(
                    transferId = "receive-notice",
                    senderName = "MacBook",
                    files = emptyList(),
                    totalBytes = 0,
                    requiresConfirmation = true,
                ),
            )

        val afterNotice = initial.applyReceiveTransferEvent(
            ReceiveTransferEvent.Notice(
                transferId = "receive-notice",
                message = "等待发送端建立连接，请稍候...",
            ),
        )

        assertEquals(initial.activeReceive, afterNotice.activeReceive)
        assertEquals("receive-notice", afterNotice.activeReceive.transferId)
    }

    @Test
    fun receiveNoticeForUnrelatedTransferIsIgnored() {
        val state = PikoHomeState.initial(currentDeviceName = "Pixel")
            .applyReceiveTransferEvent(
                ReceiveTransferEvent.Started(
                    transferId = "active",
                    senderName = "MacBook",
                    files = emptyList(),
                    totalBytes = 0,
                    requiresConfirmation = true,
                ),
            )

        val afterUnrelated = state.applyReceiveTransferEvent(
            ReceiveTransferEvent.Notice(
                transferId = "other",
                message = "should-be-ignored",
            ),
        )

        assertEquals(state, afterUnrelated)
    }

    @Test
    fun receiveHistoryDeleteDialogCopyMatchesSingleAndMultipleFiles() {
        val single = ReceiveHistoryItem(
            id = "single",
            receivedAtEpochMillis = 1_000,
            receivedAtLabel = "刚刚",
            sourceDeviceName = "MacBook",
            fileCount = 1,
            files = listOf(
                ReceiveHistoryFile(
                    displayName = "照片.jpg",
                    fileType = ReceiveFileType.Image,
                    sizeBytes = 2_048,
                ),
            ),
        )
        val multiple = ReceiveHistoryItem(
            id = "multiple",
            receivedAtEpochMillis = 2_000,
            receivedAtLabel = "刚刚",
            sourceDeviceName = "MacBook",
            fileCount = 2,
            files = listOf(
                ReceiveHistoryFile(
                    displayName = "旅行计划.pdf",
                    fileType = ReceiveFileType.Document,
                    sizeBytes = 2_048,
                ),
                ReceiveHistoryFile(
                    displayName = "费用清单.xlsx",
                    fileType = ReceiveFileType.Spreadsheet,
                    sizeBytes = 4_096,
                ),
            ),
        )

        assertEquals("真的要删除照片.jpg吗？", single.deleteConfirmationTitle)
        assertEquals("此操作不可逆！", single.deleteConfirmationBody)
        assertEquals("真的要删除这2个吗？", multiple.deleteConfirmationTitle)
        assertEquals("将会删除：旅行计划.pdf、费用清单.xlsx 此操作不可逆！", multiple.deleteConfirmationBody)
    }
}
