package com.piko.app

import com.piko.app.transport.LocalSendDeviceInfo
import com.piko.app.transport.LocalSendFileMetadata
import com.piko.app.transport.LocalSendProtocol
import com.piko.app.transport.TransferTransportKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalSendProtocolTest {
    @Test
    fun encodesPrepareUploadRequestInLocalSendShape() {
        val request = LocalSendProtocol.prepareUploadRequest(
            sender = LocalSendDeviceInfo(
                alias = "Pixel",
                version = "2.0",
                deviceModel = "Android",
                deviceType = "mobile",
                fingerprint = "piko-fingerprint",
                port = 53317,
                protocol = "http",
                download = false,
            ),
            files = listOf(
                LocalSendFileMetadata(
                    id = "file-1",
                    fileName = "photo.jpg",
                    size = 4096,
                    fileType = "image/jpeg",
                    sha256 = "abc123",
                    preview = "preview-bytes",
                    relativePath = "album/photo.jpg",
                ),
            ),
        )

        assertTrue("\"info\"" in request)
        assertTrue("\"files\"" in request)
        assertTrue("\"alias\":\"Pixel\"" in request)
        assertTrue("\"file-1\"" in request)
        assertTrue("\"fileName\":\"photo.jpg\"" in request)
        assertTrue("\"sha256\":\"abc123\"" in request)
        assertTrue("\"relativePath\":\"album/photo.jpg\"" in request)
    }

    @Test
    fun decodesPrepareUploadRequestByFileId() {
        val json = """
            {
              "info": {
                "alias": "MacBook",
                "version": "2.0",
                "deviceType": "desktop",
                "fingerprint": "fingerprint",
                "port": 53317,
                "protocol": "http",
                "download": true
              },
              "files": {
                "file-a": {
                  "id": "file-a",
                  "fileName": "report.pdf",
                  "size": 1024,
                  "fileType": "application/pdf",
                  "sha256": "hash-a",
                  "preview": null,
                  "metadata": {
                    "relativePath": "docs/report.pdf"
                  }
                }
              }
            }
        """.trimIndent()

        val decoded = LocalSendProtocol.decodePrepareUploadRequest(json)

        assertEquals("MacBook", decoded.info.alias)
        assertEquals("desktop", decoded.info.deviceType)
        assertEquals(1, decoded.files.size)
        assertEquals("file-a", decoded.files.single().id)
        assertEquals("report.pdf", decoded.files.single().fileName)
        assertEquals("application/pdf", decoded.files.single().fileType)
        assertEquals("hash-a", decoded.files.single().sha256)
        assertEquals("docs/report.pdf", decoded.files.single().relativePath)
    }

    @Test
    fun encodesPrepareUploadResponseWithPerFileTokens() {
        val response = LocalSendProtocol.prepareUploadResponse(
            sessionId = "session-1",
            fileTokens = mapOf("file-a" to "token-a", "file-b" to "token-b"),
        )

        assertTrue("\"sessionId\":\"session-1\"" in response)
        assertTrue("\"file-a\":\"token-a\"" in response)
        assertTrue("\"file-b\":\"token-b\"" in response)
    }

    @Test
    fun decodesPrepareUploadResponseWithPerFileTokens() {
        val response = LocalSendProtocol.decodePrepareUploadResponse(
            """
                {
                  "sessionId": "session-1",
                  "files": {
                    "file-a": "token-a",
                    "file-b": "token-b"
                  }
                }
            """.trimIndent(),
        )

        assertEquals("session-1", response.sessionId)
        assertEquals("token-a", response.fileTokens.getValue("file-a"))
        assertEquals("token-b", response.fileTokens.getValue("file-b"))
    }

    @Test
    fun encodesAndDecodesMulticastAnnouncement() {
        val info = LocalSendDeviceInfo(
            alias = "Pixel",
            version = "2.0",
            deviceModel = "Android",
            deviceType = "mobile",
            fingerprint = "piko-fingerprint",
            port = 53317,
            protocol = "http",
            download = false,
        )

        val announcement = LocalSendProtocol.announcement(info, announce = true)
        val decoded = LocalSendProtocol.decodeAnnouncement(announcement)

        assertTrue("\"announce\":true" in announcement)
        assertEquals(info, decoded.info)
        assertTrue(decoded.announce)
    }

    @Test
    fun encodesMulticastResponseWithAnnounceFalse() {
        val info = LocalSendDeviceInfo(
            alias = "Pixel",
            fingerprint = "piko-fingerprint",
            port = 53317,
        )

        val response = LocalSendProtocol.announcement(info, announce = false)
        val decoded = LocalSendProtocol.decodeAnnouncement(response)

        assertTrue("\"announce\":false" in response)
        assertEquals("Pixel", decoded.info.alias)
        assertFalse(decoded.announce)
    }

    @Test
    fun transportKindsReserveP2pAndRelayWithoutASecondTransferApi() {
        assertEquals("lan-direct", TransferTransportKind.LanDirect.wireName)
        assertEquals("p2p-direct", TransferTransportKind.P2pDirect.wireName)
        assertEquals("relay", TransferTransportKind.Relay.wireName)
        assertFalse(TransferTransportKind.P2pDirect.isImplemented)
    }
}
