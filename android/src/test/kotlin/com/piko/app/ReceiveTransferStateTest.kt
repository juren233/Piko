package com.piko.app

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
}
