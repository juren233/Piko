package com.piko.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SendTransferProtocolTest {
    @Test
    fun encodesAndDecodesSingleFileHeader() {
        val item = SendTransferItem(
            id = "content://file/photo.jpg",
            displayName = "photo.jpg",
            sizeBytes = 4096,
            fileType = SendFileType.Image,
            sourceUri = "content://file/photo.jpg",
        )

        val decoded = SendTransferProtocol.decodeHeader(
            SendTransferProtocol.encodeHeader(listOf(item), senderName = "清亮竹影"),
        )

        assertEquals("清亮竹影", decoded.senderName)
        assertEquals(1, decoded.files.size)
        assertEquals("photo.jpg", decoded.files.single().displayName)
        assertEquals(SendFileType.Image, decoded.files.single().fileType)
        assertEquals(4096, decoded.files.single().sizeBytes)
    }

    @Test
    fun encodesAndDecodesHeaderWithMultipleFilesAndUnicodeNames() {
        val items = listOf(
            SendTransferItem(
                id = "content://file/1",
                displayName = "报告.pdf",
                sizeBytes = 1024,
                fileType = SendFileType.Document,
                sourceUri = "content://file/1",
            ),
            SendTransferItem(
                id = "content://file/2",
                displayName = "empty.txt",
                sizeBytes = 0,
                fileType = SendFileType.Other,
                sourceUri = "content://file/2",
            ),
        )

        val decoded = SendTransferProtocol.decodeHeader(
            SendTransferProtocol.encodeHeader(items, senderName = "MacBook Pro"),
        )

        assertEquals("MacBook Pro", decoded.senderName)
        assertEquals(2, decoded.files.size)
        assertEquals("报告.pdf", decoded.files[0].displayName)
        assertEquals(SendFileType.Document, decoded.files[0].fileType)
        assertEquals(1024, decoded.files[0].sizeBytes)
        assertEquals("empty.txt", decoded.files[1].displayName)
        assertEquals(0, decoded.files[1].sizeBytes)
    }
}
