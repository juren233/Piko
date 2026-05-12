package com.piko.app.transport

import com.piko.app.domain.AccountError
import com.piko.app.domain.AccountResult
import com.piko.app.domain.AuthSuccess
import com.piko.app.domain.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Piko 后端 (CloudFlare Workers) HTTP 客户端。
 *
 * 设计原则：
 * - suspend + Dispatchers.IO，避免阻塞主线程
 * - 所有方法返回 AccountResult，错误类型化
 * - 与 LAN 协议的手写 HTTP parser 完全隔离（这是云端 HTTPS，URLConnection 即可）
 */
interface AccountApi {
    suspend fun register(
        email: String,
        password: String,
        username: String,
        nickname: String?,
    ): AccountResult<AuthSuccess>

    suspend fun login(email: String, password: String): AccountResult<AuthSuccess>

    suspend fun logout(token: String): AccountResult<Unit>

    suspend fun me(token: String): AccountResult<User>
}

class AccountApiClient(
    private val baseUrl: String,
    private val timeoutMillis: Int = 15_000,
) : AccountApi {

    override suspend fun register(
        email: String,
        password: String,
        username: String,
        nickname: String?,
    ): AccountResult<AuthSuccess> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("username", username)
            if (nickname != null) put("nickname", nickname)
        }
        request("POST", "/v1/auth/register", body = body.toString()) { json ->
            parseAuthSuccess(json)
        }
    }

    override suspend fun login(email: String, password: String): AccountResult<AuthSuccess> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
        }
        request("POST", "/v1/auth/login", body = body.toString()) { json ->
            parseAuthSuccess(json)
        }
    }

    override suspend fun logout(token: String): AccountResult<Unit> = withContext(Dispatchers.IO) {
        request("POST", "/v1/auth/logout", body = null, bearer = token) { Unit }
    }

    override suspend fun me(token: String): AccountResult<User> = withContext(Dispatchers.IO) {
        request("GET", "/v1/users/me", body = null, bearer = token) { json ->
            parseUser(json.getJSONObject("user"))
        }
    }

    private fun <T> request(
        method: String,
        path: String,
        body: String?,
        bearer: String? = null,
        parse: (JSONObject) -> T,
    ): AccountResult<T> {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            instanceFollowRedirects = false
            doInput = true
            if (bearer != null) {
                setRequestProperty("Authorization", "Bearer $bearer")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            setRequestProperty("Accept", "application/json")
        }

        try {
            if (body != null) {
                conn.outputStream.use { os: OutputStream ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
            } ?: ""

            return when {
                status == 204 -> AccountResult.Ok(parse(JSONObject()))
                status in 200..299 -> {
                    val json = JSONObject(text)
                    AccountResult.Ok(parse(json))
                }
                else -> AccountResult.Err(parseServerError(status, text))
            }
        } catch (e: IOException) {
            return AccountResult.Err(AccountError.Network)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseServerError(status: Int, body: String): AccountError {
        val code = runCatching {
            JSONObject(body).getJSONObject("error").getString("code")
        }.getOrNull()
        val message = runCatching {
            JSONObject(body).getJSONObject("error").getString("message")
        }.getOrDefault("")

        return when (code) {
            "EMAIL_TAKEN" -> AccountError.EmailTaken
            "USERNAME_TAKEN" -> AccountError.UsernameTaken
            "INVALID_EMAIL" -> AccountError.InvalidEmail
            "INVALID_PASSWORD" -> AccountError.InvalidPassword
            "INVALID_USERNAME" -> AccountError.InvalidUsername
            "INVALID_NICKNAME" -> AccountError.InvalidNickname
            "INVALID_CREDENTIALS" -> AccountError.InvalidCredentials
            "SESSION_EXPIRED" -> AccountError.SessionExpired
            null -> AccountError.Server("HTTP_$status", "HTTP $status")
            else -> AccountError.Server(code, message)
        }
    }

    private fun parseAuthSuccess(json: JSONObject): AuthSuccess {
        val token = json.getString("token")
        val user = parseUser(json.getJSONObject("user"))
        return AuthSuccess(token = token, user = user)
    }

    private fun parseUser(json: JSONObject): User {
        return User(
            id = json.getString("id"),
            email = json.getString("email"),
            username = json.getString("username"),
            nickname = if (json.isNull("nickname")) null else json.optString("nickname", "").ifEmpty { null },
        )
    }
}
