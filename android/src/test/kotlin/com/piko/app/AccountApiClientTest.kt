package com.piko.app

import com.piko.app.domain.AccountError
import com.piko.app.domain.AccountResult
import com.piko.app.transport.AccountApiClient
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.json.JSONObject

private data class StubResponse(val status: Int, val body: String)

private class StubServer(private val responder: (StubRequest) -> StubResponse) {
    private val server = ServerSocket(0)
    val port: Int = server.localPort
    private val capturedRequests = mutableListOf<StubRequest>()
    val requests: List<StubRequest> get() = synchronized(capturedRequests) { capturedRequests.toList() }

    init {
        thread(name = "stub-server", isDaemon = true) { loop() }
    }

    private fun loop() {
        try {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: SocketException) { return }
                try { handleOne(client) } catch (_: Exception) {} finally { client.close() }
            }
        } catch (_: IOException) {}
    }

    private fun handleOne(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val firstLine = reader.readLine() ?: return
        val parts = firstLine.split(" ")
        val method = parts.getOrElse(0) { "" }
        val path = parts.getOrElse(1) { "" }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                line.substring(idx + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val chars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(chars, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            String(chars, 0, read)
        } else ""
        val request = StubRequest(method = method, path = path, headers = headers, body = body)
        synchronized(capturedRequests) { capturedRequests.add(request) }
        val resp = responder(request)
        val bodyBytes = resp.body.toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 ${resp.status} STATUS\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Length: ${bodyBytes.size}\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(bodyBytes)
        out.flush()
    }

    fun close() {
        server.close()
    }
}

private data class StubRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

class AccountApiClientTest {
    private var server: StubServer? = null

    @AfterTest
    fun teardown() {
        server?.close()
        server = null
    }

    private fun start(responder: (StubRequest) -> StubResponse): AccountApiClient {
        val s = StubServer(responder)
        server = s
        return AccountApiClient(baseUrl = "http://127.0.0.1:${s.port}")
    }

    @Test
    fun registerPostsJsonAndReturnsAuthSuccess() = runBlocking {
        val responseBody = """{"token":"a".repeat(43),"user":{"id":"u1","email":"a@b.com","username":"alice","nickname":"Alice"}}"""
            .replace("\"a\".repeat(43)", "\"" + "a".repeat(43) + "\"")
        val client = start { _ -> StubResponse(201, responseBody) }

        val res = client.register("a@b.com", "hunter2hunter2", "alice", "Alice")

        assertTrue(res is AccountResult.Ok)
        assertEquals("alice", (res as AccountResult.Ok).value.user.username)
        assertEquals("a".repeat(43), res.value.token)

        val req = server!!.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/v1/auth/register", req.path)
        val payload = JSONObject(req.body)
        assertEquals("a@b.com", payload.getString("email"))
        assertEquals("hunter2hunter2", payload.getString("password"))
        assertEquals("alice", payload.getString("username"))
        assertEquals("Alice", payload.getString("nickname"))
    }

    @Test
    fun registerWithoutNicknameOmitsField() = runBlocking {
        val responseBody = """{"token":"${"a".repeat(43)}","user":{"id":"u1","email":"a@b.com","username":"alice","nickname":null}}"""
        val client = start { _ -> StubResponse(201, responseBody) }

        val res = client.register("a@b.com", "hunter2hunter2", "alice", nickname = null)
        assertTrue(res is AccountResult.Ok)
        assertEquals(null, (res as AccountResult.Ok).value.user.nickname)

        val payload = JSONObject(server!!.requests.single().body)
        assertTrue(!payload.has("nickname"))
    }

    @Test
    fun registerMapsEmailTakenError() = runBlocking {
        val body = """{"error":{"code":"EMAIL_TAKEN","message":"邮箱已被注册"}}"""
        val client = start { _ -> StubResponse(409, body) }

        val res = client.register("a@b.com", "hunter2hunter2", "alice", null)
        assertTrue(res is AccountResult.Err)
        assertEquals(AccountError.EmailTaken, (res as AccountResult.Err).error)
    }

    @Test
    fun registerMapsUsernameTakenError() = runBlocking {
        val body = """{"error":{"code":"USERNAME_TAKEN","message":"用户名已被占用"}}"""
        val client = start { _ -> StubResponse(409, body) }

        val res = client.register("a@b.com", "hunter2hunter2", "alice", null)
        assertTrue(res is AccountResult.Err)
        assertEquals(AccountError.UsernameTaken, (res as AccountResult.Err).error)
    }

    @Test
    fun loginMapsInvalidCredentials() = runBlocking {
        val body = """{"error":{"code":"INVALID_CREDENTIALS","message":"邮箱或密码错误"}}"""
        val client = start { _ -> StubResponse(401, body) }

        val res = client.login("a@b.com", "wrong-password")
        assertTrue(res is AccountResult.Err)
        assertEquals(AccountError.InvalidCredentials, (res as AccountResult.Err).error)
    }

    @Test
    fun meSendsBearerHeader() = runBlocking {
        val token = "tok-" + "x".repeat(39)
        val body = """{"user":{"id":"u1","email":"a@b.com","username":"alice","nickname":"Alice"}}"""
        val client = start { _ -> StubResponse(200, body) }

        val res = client.me(token)
        assertTrue(res is AccountResult.Ok)
        assertEquals("alice", (res as AccountResult.Ok).value.username)

        val req = server!!.requests.single()
        assertEquals("GET", req.method)
        assertEquals("/v1/users/me", req.path)
        assertEquals("Bearer $token", req.headers["authorization"])
    }

    @Test
    fun meMapsSessionExpired() = runBlocking {
        val body = """{"error":{"code":"SESSION_EXPIRED","message":"登录状态已失效，请重新登录"}}"""
        val client = start { _ -> StubResponse(401, body) }

        val res = client.me("expired-token-" + "x".repeat(28))
        assertTrue(res is AccountResult.Err)
        assertEquals(AccountError.SessionExpired, (res as AccountResult.Err).error)
    }

    @Test
    fun logoutReturnsOkOn204() = runBlocking {
        val client = start { _ -> StubResponse(204, "") }
        val res = client.logout("any-token-" + "z".repeat(33))
        assertTrue(res is AccountResult.Ok)
    }

    @Test
    fun networkFailureMapsToNetworkError() = runBlocking {
        // 启服后立即关掉，连接会失败
        val s = StubServer { _ -> StubResponse(200, "{}") }
        val port = s.port
        s.close()
        val client = AccountApiClient(baseUrl = "http://127.0.0.1:$port", timeoutMillis = 1_000)

        val res = client.login("a@b.com", "hunter2hunter2")
        if (res !is AccountResult.Err) fail("expected network error, got $res")
        assertEquals(AccountError.Network, res.error)
    }
}
