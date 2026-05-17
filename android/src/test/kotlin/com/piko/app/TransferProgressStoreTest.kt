package com.piko.app

import com.piko.app.data.TransferProgressStore
import com.piko.app.domain.SendFileType
import com.piko.app.domain.TransferV3File
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TransferProgressStoreTest {
    @Test
    fun completedBitmapDoesNotAdvertiseMissingPartFile() {
        val directory = createTempDirectory("piko-transfer-progress-missing-part").toFile()
        try {
            val store = TransferProgressStore(directory)
            val manifest = listOf(testFile())
            store.save(
                transferId = "transfer-1",
                manifestHashB64 = "manifest-hash",
                manifest = manifest,
                completedChunks = mapOf(0 to booleanArrayOf(true, true)),
            )

            assertEquals(null, store.completedBitmapB64("transfer-1", "manifest-hash"))
            assertEquals(emptyMap(), store.completedChunks("transfer-1", "manifest-hash", manifest))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun completedBitmapOnlyKeepsChunksCoveredByPartFile() {
        val directory = createTempDirectory("piko-transfer-progress-covered-part").toFile()
        try {
            val store = TransferProgressStore(directory)
            val manifest = listOf(testFile())
            store.save(
                transferId = "transfer-2",
                manifestHashB64 = "manifest-hash",
                manifest = manifest,
                completedChunks = mapOf(0 to booleanArrayOf(true, true)),
            )
            File(store.transferDir("transfer-2"), "0.part").writeBytes(byteArrayOf(1, 2, 3, 4))

            val decoded = TransferProgressStore.decodeCompletedBitmap(
                store.completedBitmapB64("transfer-2", "manifest-hash"),
            )
            val restored = store.completedChunks("transfer-2", "manifest-hash", manifest)

            assertEquals(setOf(0), decoded[0])
            assertContentEquals(booleanArrayOf(true, false), restored.getValue(0))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun saveProgressCanBeCalledAfterMultipleChunksWithoutLosingBitmap() {
        val directory = createTempDirectory("piko-transfer-progress-batched-bitmap").toFile()
        try {
            val store = TransferProgressStore(directory)
            val manifest = listOf(testFile(chunkSize = 4, chunkCount = 16, sizeBytes = 64))
            val completed = BooleanArray(16) { index -> index % 3 == 0 || index == 15 }
            val partFile = File(store.transferDir("transfer-3"), "0.part")
            partFile.parentFile?.mkdirs()
            partFile.writeBytes(ByteArray(64) { it.toByte() })

            store.save(
                transferId = "transfer-3",
                manifestHashB64 = "manifest-hash",
                manifest = manifest,
                completedChunks = mapOf(0 to completed),
            )

            val restored = store.completedChunks("transfer-3", "manifest-hash", manifest)
            val decoded = TransferProgressStore.decodeCompletedBitmap(
                store.completedBitmapB64("transfer-3", "manifest-hash"),
            )

            assertContentEquals(completed, restored.getValue(0))
            assertEquals(setOf(0, 3, 6, 9, 12, 15), decoded[0])
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun testFile(chunkSize: Int = 4, chunkCount: Int = 2, sizeBytes: Long = 6): TransferV3File =
        TransferV3File(
            index = 0,
            displayName = "demo.bin",
            fileType = SendFileType.Other,
            sizeBytes = sizeBytes,
            chunkSize = chunkSize,
            chunkCount = chunkCount,
            fileHash = ByteArray(32) { 7 },
        )
}
