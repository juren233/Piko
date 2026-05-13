package com.piko.app.transport

import com.piko.app.domain.AccountError
import com.piko.app.domain.AccountResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

data class IceServerConfig(
    val urls: String,
)

data class TransferSessionConfig(
    val sessionId: String,
    val iceServers: List<IceServerConfig>,
    val expiresAt: Long,
)

class TransferSessionApiClient(
    private val baseUrl: String,
    private val timeoutMillis: Int = 15_000,
) {
    suspend fun createSession(
        token: String,
        receiverUserId: String,
        receiverDeviceId: String,
        transferId: String,
        manifestHashB64: String,
        senderX25519EphPubB64: String,
        senderDeviceId: String,
        senderInviteSignatureB64: String,
    ): AccountResult<TransferSessionConfig> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("receiver_user_id", receiverUserId)
            .put("receiver_device_id", receiverDeviceId)
            .put("transfer_id", transferId)
            .put("manifest_hash_b64", manifestHashB64)
            .put("sender_x25519_eph_pub_b64", senderX25519EphPubB64)
            .put("sender_device_id", senderDeviceId)
            .put("sender_invite_signature_b64", senderInviteSignatureB64)
            .toString()
        request("POST", "/v1/transfers/sessions", body, token) { json ->
            TransferSessionConfig(
                sessionId = json.getString("session_id"),
                iceServers = json.getJSONArray("ice_servers").mapObjects { server ->
                    IceServerConfig(urls = server.getString("urls"))
                },
                expiresAt = json.getLong("expires_at"),
            )
        }
    }

    suspend fun finishSession(token: String, sessionId: String): AccountResult<Unit> = withContext(Dispatchers.IO) {
        request("POST", "/v1/transfers/sessions/$sessionId/finish", null, token) { Unit }
    }

    private fun <T> request(
        method: String,
        path: String,
        body: String?,
        bearer: String,
        parse: (JSONObject) -> T,
    ): AccountResult<T> {
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            doInput = true
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { os: OutputStream -> os.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
            return when {
                status == 204 -> AccountResult.Ok(parse(JSONObject()))
                status in 200..299 -> AccountResult.Ok(parse(JSONObject(text)))
                else -> AccountResult.Err(parseServerError(status, text))
            }
        } catch (_: IOException) {
            return AccountResult.Err(AccountError.Network)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseServerError(status: Int, body: String): AccountError {
        val error = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
        val code = error?.optString("code")?.takeIf { it.isNotBlank() }
        val message = error?.optString("message").orEmpty()
        return when (code) {
            "SESSION_EXPIRED" -> AccountError.SessionExpired
            null -> AccountError.Server("HTTP_$status", "HTTP $status")
            else -> AccountError.Server(code, message)
        }
    }
}

private inline fun <T> org.json.JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
    return (0 until length()).map { index -> transform(getJSONObject(index)) }
}
