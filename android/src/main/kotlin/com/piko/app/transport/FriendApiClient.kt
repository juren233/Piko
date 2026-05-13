package com.piko.app.transport

import com.piko.app.domain.AccountError
import com.piko.app.domain.AccountResult
import com.piko.app.domain.FriendDevice
import com.piko.app.domain.FriendRelationship
import com.piko.app.domain.FriendRequest
import com.piko.app.domain.FriendRequestDirection
import com.piko.app.domain.FriendRequestStatus
import com.piko.app.domain.FriendSearchResult
import com.piko.app.domain.FriendUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

interface FriendApi {
    suspend fun search(token: String, query: String): AccountResult<List<FriendSearchResult>>
    suspend fun friends(token: String): AccountResult<List<FriendUser>>
    suspend fun friendDevices(token: String, userId: String): AccountResult<List<FriendDevice>>
    suspend fun requests(token: String, direction: FriendRequestDirection): AccountResult<List<FriendRequest>>
    suspend fun sendRequest(token: String, userId: String): AccountResult<FriendRequest>
    suspend fun accept(token: String, requestId: String): AccountResult<FriendRequest>
    suspend fun reject(token: String, requestId: String): AccountResult<FriendRequest>
    suspend fun cancel(token: String, requestId: String): AccountResult<FriendRequest>
    suspend fun removeFriend(token: String, userId: String): AccountResult<Unit>
    suspend fun heartbeat(token: String): AccountResult<Unit>
}

class FriendApiClient(
    private val baseUrl: String,
    private val timeoutMillis: Int = 15_000,
) : FriendApi {
    override suspend fun search(token: String, query: String): AccountResult<List<FriendSearchResult>> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
            request("GET", "/v1/users/search?q=$encoded", null, token) { json ->
                json.getJSONArray("results").mapObjects { result ->
                    FriendSearchResult(
                        user = parseSearchUser(result),
                        relationship = parseRelationship(result.getString("relationship")),
                    )
                }
            }
        }

    override suspend fun friends(token: String): AccountResult<List<FriendUser>> = withContext(Dispatchers.IO) {
        request("GET", "/v1/friends", null, token) { json ->
            json.getJSONArray("friends").mapObjects { parseFriendUser(it) }
        }
    }

    override suspend fun friendDevices(token: String, userId: String): AccountResult<List<FriendDevice>> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(userId, Charsets.UTF_8.name())
            request("GET", "/v1/friends/$encoded/devices", null, token) { json ->
                json.getJSONArray("devices").mapObjects { parseFriendDevice(userId, it) }
            }
        }

    override suspend fun requests(
        token: String,
        direction: FriendRequestDirection,
    ): AccountResult<List<FriendRequest>> = withContext(Dispatchers.IO) {
        val value = if (direction == FriendRequestDirection.Incoming) "incoming" else "outgoing"
        request("GET", "/v1/friends/requests?direction=$value", null, token) { json ->
            json.getJSONArray("requests").mapObjects { parseRequest(it, direction) }
        }
    }

    override suspend fun sendRequest(token: String, userId: String): AccountResult<FriendRequest> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("receiver_user_id", userId).toString()
            request("POST", "/v1/friends/requests", body, token) { json ->
                parseRequest(json.getJSONObject("request"), FriendRequestDirection.Outgoing)
            }
        }

    override suspend fun accept(token: String, requestId: String): AccountResult<FriendRequest> =
        mutateRequest(token, requestId, "accept", FriendRequestDirection.Incoming)

    override suspend fun reject(token: String, requestId: String): AccountResult<FriendRequest> =
        mutateRequest(token, requestId, "reject", FriendRequestDirection.Incoming)

    override suspend fun cancel(token: String, requestId: String): AccountResult<FriendRequest> =
        withContext(Dispatchers.IO) {
            request("DELETE", "/v1/friends/requests/$requestId", null, token) { json ->
                parseRequest(json.getJSONObject("request"), FriendRequestDirection.Outgoing)
            }
        }

    override suspend fun removeFriend(token: String, userId: String): AccountResult<Unit> = withContext(Dispatchers.IO) {
        request("DELETE", "/v1/friends/$userId", null, token) { Unit }
    }

    override suspend fun heartbeat(token: String): AccountResult<Unit> = withContext(Dispatchers.IO) {
        request("POST", "/v1/presence/heartbeat", null, token) { Unit }
    }

    private suspend fun mutateRequest(
        token: String,
        requestId: String,
        action: String,
        direction: FriendRequestDirection,
    ): AccountResult<FriendRequest> = withContext(Dispatchers.IO) {
        request("POST", "/v1/friends/requests/$requestId/$action", null, token) { json ->
            parseRequest(json.getJSONObject("request"), direction)
        }
    }

    private fun <T> request(
        method: String,
        path: String,
        body: String?,
        bearer: String,
        parse: (JSONObject) -> T,
    ): AccountResult<T> {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
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
        } catch (e: IOException) {
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

    private fun parseRequest(json: JSONObject, direction: FriendRequestDirection): FriendRequest {
        val user = json.optJSONObject("other_user")
        return FriendRequest(
            id = json.getString("id"),
            direction = direction,
            otherUser = FriendUser(
                userId = user?.getString("id") ?: "",
                username = user?.getString("username") ?: "",
                nickname = user?.optString("nickname")?.takeIf { it.isNotBlank() },
                online = false,
                lastSeenAt = null,
                since = json.optLong("created_at", 0L),
            ),
            status = parseRequestStatus(json.getString("status")),
            createdAt = json.optLong("created_at", 0L),
        )
    }

    private fun parseFriendUser(json: JSONObject): FriendUser {
        return FriendUser(
            userId = json.getString("user_id"),
            username = json.getString("username"),
            nickname = json.optString("nickname").takeIf { it.isNotBlank() && it != "null" },
            online = json.optBoolean("online", false),
            lastSeenAt = if (json.isNull("last_seen_at")) null else json.optLong("last_seen_at"),
            since = json.optLong("since", 0L),
        )
    }

    private fun parseFriendDevice(ownerUserId: String, json: JSONObject): FriendDevice {
        return FriendDevice(
            ownerUserId = ownerUserId,
            deviceId = json.getString("device_id"),
            platform = json.getString("platform"),
            deviceName = json.getString("device_name"),
            ed25519PubB64 = json.getString("ed25519_pub_b64"),
            x25519PubB64 = json.getString("x25519_pub_b64"),
            appVersion = json.optString("app_version").takeIf { it.isNotBlank() && it != "null" },
            lastSeenAt = if (json.isNull("last_seen_at")) null else json.optLong("last_seen_at"),
            online = json.optBoolean("online", false),
        )
    }

    private fun parseSearchUser(json: JSONObject): FriendUser {
        return FriendUser(
            userId = json.getString("id"),
            username = json.getString("username"),
            nickname = json.optString("nickname").takeIf { it.isNotBlank() && it != "null" },
            online = false,
            lastSeenAt = null,
            since = 0L,
        )
    }

    private fun parseRelationship(value: String): FriendRelationship = when (value) {
        "self" -> FriendRelationship.Self
        "pending-out" -> FriendRelationship.PendingOut
        "pending-in" -> FriendRelationship.PendingIn
        "friend" -> FriendRelationship.Friend
        else -> FriendRelationship.None
    }

    private fun parseRequestStatus(value: String): FriendRequestStatus = when (value) {
        "accepted" -> FriendRequestStatus.Accepted
        "rejected" -> FriendRequestStatus.Rejected
        "canceled" -> FriendRequestStatus.Canceled
        else -> FriendRequestStatus.Pending
    }
}

private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
    return (0 until length()).map { index -> transform(getJSONObject(index)) }
}
