package com.piko.app

import com.piko.app.data.LocalSendSessionStore
import com.piko.app.transport.LocalSendDeviceInfo
import com.piko.app.transport.LocalSendFileMetadata
import com.piko.app.transport.LocalSendPrepareUploadRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalSendSessionStoreTest {
    @Test
    fun prepareCreatesSessionAndPerFileTokens() {
        val store = LocalSendSessionStore()

        val response = store.prepare(
            LocalSendPrepareUploadRequest(
                info = LocalSendDeviceInfo(
                    alias = "MacBook",
                    fingerprint = "fingerprint",
                    port = 53317,
                ),
                files = listOf(
                    LocalSendFileMetadata(
                        id = "file-a",
                        fileName = "a.txt",
                        size = 3,
                        fileType = "text/plain",
                    ),
                    LocalSendFileMetadata(
                        id = "file-b",
                        fileName = "b.txt",
                        size = 4,
                        fileType = "text/plain",
                    ),
                ),
            ),
        )

        assertTrue(response.sessionId.isNotBlank())
        assertEquals(setOf("file-a", "file-b"), response.fileTokens.keys)
        assertNotEquals(response.fileTokens["file-a"], response.fileTokens["file-b"])
        assertNotNull(store.validate(response.sessionId, "file-a", response.fileTokens.getValue("file-a")))
    }

    @Test
    fun validationRejectsWrongTokenAndCanceledSessions() {
        val store = LocalSendSessionStore()
        val response = store.prepare(
            LocalSendPrepareUploadRequest(
                info = LocalSendDeviceInfo(alias = "MacBook", fingerprint = "fingerprint", port = 53317),
                files = listOf(
                    LocalSendFileMetadata(
                        id = "file-a",
                        fileName = "a.txt",
                        size = 3,
                        fileType = "text/plain",
                    ),
                ),
            ),
        )

        assertFalse(store.hasActiveSession("missing"))
        assertEquals(null, store.validate(response.sessionId, "file-a", "wrong-token"))

        store.cancel(response.sessionId)

        assertEquals(null, store.validate(response.sessionId, "file-a", response.fileTokens.getValue("file-a")))
        assertFalse(store.hasActiveSession(response.sessionId))
    }
}
