package com.piko.app

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceiveHistoryStoreTest {
    @Test
    fun savedHistoryLoadsAfterStateRecreationWithMediaPreviewBytes() {
        val directory = createTempDirectory("piko-receive-history").toFile()
        try {
            val store = ReceiveHistoryStore(File(directory, "receive_history.json"))
            val older = ReceiveHistoryItem(
                id = "receive-old",
                receivedAtEpochMillis = 1_000,
                receivedAtLabel = "昨天",
                sourceDeviceName = "MacBook",
                fileCount = 1,
                files = listOf(
                    ReceiveHistoryFile(
                        displayName = "旧照片.jpg",
                        fileType = ReceiveFileType.Image,
                        sizeBytes = 2_048,
                        thumbnailBytes = byteArrayOf(1, 2, 3),
                    ),
                ),
            )
            val newer = ReceiveHistoryItem(
                id = "receive-new",
                receivedAtEpochMillis = 2_000,
                receivedAtLabel = "刚刚",
                sourceDeviceName = "iPhone",
                fileCount = 1,
                files = listOf(
                    ReceiveHistoryFile(
                        displayName = "旅行短片.mp4",
                        fileType = ReceiveFileType.Video,
                        sizeBytes = 4_096,
                        thumbnailBytes = byteArrayOf(9, 8, 7),
                        savedUri = "content://piko/receive-new",
                    ),
                ),
            )

            store.save(listOf(older, newer))

            val recreatedState = PikoHomeState.initial(
                currentDeviceName = "Pixel",
                receiveHistory = store.load(),
            )
            val restored = recreatedState.receiveHistoryDescending
            assertEquals(listOf("receive-new", "receive-old"), restored.map { it.id })
            assertEquals(byteArrayOf(9, 8, 7).toList(), restored.first().primaryFile.thumbnailBytes?.toList())
            assertEquals("content://piko/receive-new", restored.first().primaryFile.savedUri)
            assertEquals("旅行短片.mp4", restored.first().mediaPreviewDescription)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun legacyHistoryWithoutSavedUriStillLoads() {
        val directory = createTempDirectory("piko-receive-history-legacy").toFile()
        try {
            val file = File(directory, "receive_history.json")
            file.writeText(
                """
                [
                  {
                    "id":"legacy-receive",
                    "receivedAtEpochMillis":1000,
                    "receivedAtLabel":"昨天",
                    "sourceDeviceName":"MacBook",
                    "fileCount":1,
                    "files":[
                      {
                        "displayName":"旧报告.pdf",
                        "fileType":"Document",
                        "sizeBytes":2048,
                        "thumbnailBytes":null
                      }
                    ]
                  }
                ]
                """.trimIndent(),
            )
            val store = ReceiveHistoryStore(file)
            val history = store.load().single()

            assertEquals("legacy-receive", history.id)
            assertEquals("旧报告.pdf", history.primaryFile.displayName)
            assertEquals(null, history.primaryFile.savedUri)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun damagedHistoryFileFallsBackToEmptyHistory() {
        val directory = createTempDirectory("piko-receive-history-damaged").toFile()
        try {
            val file = File(directory, "receive_history.json")
            file.writeText("{bad json")
            val store = ReceiveHistoryStore(file)

            assertEquals(emptyList(), store.load())
        } finally {
            directory.deleteRecursively()
        }
    }
}
