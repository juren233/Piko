package com.piko.app.transport

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class SignalingWebSocketClient(
    private val baseUrl: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var activeToken: String? = null
    private var activeDeviceId: String? = null
    private val listeners = linkedSetOf<(JSONObject) -> Unit>()
    private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "piko-signaling-reconnect").apply { isDaemon = true }
    }
    private var reconnectAttempts = 0
    private var pendingReconnect: ScheduledFuture<*>? = null
    @Volatile
    private var closedByUser = false

    fun connect(token: String, deviceId: String) {
        if (socket != null && activeDeviceId == deviceId && activeToken == token) return
        closedByUser = false
        cancelPendingReconnect()
        reconnectAttempts = 0
        connectInternal(token, deviceId)
    }

    private fun connectInternal(token: String, deviceId: String) {
        closeSocket()
        activeToken = token
        activeDeviceId = deviceId
        val request = Request.Builder()
            .url("${baseUrl.toWebSocketBase()}/v1/signaling/ws?device_id=${deviceId.urlEncode()}")
            .header("Authorization", "Bearer $token")
            .build()
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    reconnectAttempts = 0
                    webSocket.send(JSONObject().put("type", "hello").toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (message.optString("type") == "ping") {
                        webSocket.send(JSONObject().put("type", "pong").toString())
                        return
                    }
                    runCatching { onMessage(message) }
                    listeners.toList().forEach { listener ->
                        runCatching { listener(message) }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (socket === webSocket) socket = null
                    onFailure(t)
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket === webSocket) socket = null
                    if (!closedByUser) scheduleReconnect()
                }
            },
        )
    }

    private fun scheduleReconnect() {
        if (closedByUser) return
        val token = activeToken ?: return
        val deviceId = activeDeviceId ?: return
        cancelPendingReconnect()
        val attempt = reconnectAttempts.coerceAtMost(6)
        reconnectAttempts += 1
        val delayMillis = (500L shl attempt).coerceAtMost(30_000L)
        pendingReconnect = reconnectExecutor.schedule(
            { if (!closedByUser) connectInternal(token, deviceId) },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelPendingReconnect() {
        pendingReconnect?.cancel(false)
        pendingReconnect = null
    }

    fun send(message: JSONObject): Boolean = socket?.send(message.toString()) == true

    fun addListener(listener: (JSONObject) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun close() {
        closedByUser = true
        cancelPendingReconnect()
        closeSocket()
        activeToken = null
        activeDeviceId = null
    }

    private fun closeSocket() {
        socket?.close(1000, "closed")
        socket = null
    }
}

private fun String.toWebSocketBase(): String {
    val trimmed = trimEnd('/')
    return when {
        trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
        trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
        else -> trimmed
    }
}

private fun String.urlEncode(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
