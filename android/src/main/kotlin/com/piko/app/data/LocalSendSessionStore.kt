package com.piko.app.data

import com.piko.app.transport.LocalSendDeviceInfo
import com.piko.app.transport.LocalSendFileMetadata
import com.piko.app.transport.LocalSendPrepareUploadRequest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class LocalSendPrepareUploadResponse(
    val sessionId: String,
    val fileTokens: Map<String, String>,
)

data class LocalSendSessionFile(
    val metadata: LocalSendFileMetadata,
    val token: String,
)

class LocalSendSessionStore {
    private val sessions = ConcurrentHashMap<String, LocalSendSession>()
    private val random = SecureRandom()

    fun prepare(request: LocalSendPrepareUploadRequest): LocalSendPrepareUploadResponse {
        val sessionId = newToken()
        val files = request.files.associate { file ->
            file.id to LocalSendSessionFile(
                metadata = file,
                token = newToken(),
            )
        }
        sessions[sessionId] = LocalSendSession(
            sender = request.info,
            files = files,
        )
        return LocalSendPrepareUploadResponse(
            sessionId = sessionId,
            fileTokens = files.mapValues { (_, file) -> file.token },
        )
    }

    fun validate(
        sessionId: String,
        fileId: String,
        token: String,
    ): LocalSendSessionFile? {
        val session = sessions[sessionId] ?: return null
        val file = session.files[fileId] ?: return null
        if (file.token != token) {
            return null
        }
        return file
    }

    fun cancel(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun hasActiveSession(sessionId: String): Boolean {
        return sessions.containsKey(sessionId)
    }

    private fun newToken(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }
}

private data class LocalSendSession(
    val sender: LocalSendDeviceInfo,
    val files: Map<String, LocalSendSessionFile>,
)
