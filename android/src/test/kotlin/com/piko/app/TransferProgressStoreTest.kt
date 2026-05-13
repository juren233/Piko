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

    private fun testFile(): TransferV3File =
        TransferV3File(
            index = 0,
            displayName = "demo.bin",
            fileType = SendFileType.Other,
            sizeBytes = 6,
            chunkSize = 4,
            chunkCount = 2,
            fileHash = ByteArray(32) { 7 },
        )
}
