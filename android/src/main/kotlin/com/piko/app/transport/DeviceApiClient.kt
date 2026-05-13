package com.piko.app.transport

import com.piko.app.data.DeviceIdentity
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

class DeviceApiClient(
    private val baseUrl: String,
    private val timeoutMillis: Int = 15_000,
) {
    suspend fun registerDevice(
        token: String,
        identity: DeviceIdentity,
        deviceName: String,
        appVersion: String?,
    ): AccountResult<Unit> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("device_id", identity.deviceId)
            .put("platform", "android")
            .put("device_name", deviceName)
            .put("ed25519_pub_b64", identity.ed25519PublicB64)
            .put("x25519_pub_b64", identity.x25519PublicB64)
            .put("app_version", appVersion)
            .toString()
        request("POST", "/v1/devices/keys", body, token) { Unit }
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
