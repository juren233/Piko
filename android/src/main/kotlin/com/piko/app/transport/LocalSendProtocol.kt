package com.piko.app.transport

import com.piko.app.data.LocalSendPrepareUploadResponse
import org.json.JSONObject

private const val LOCALSEND_VERSION = "2.0"
internal const val LOCALSEND_MULTICAST_ADDRESS = "224.0.0.167"
internal const val LOCALSEND_PORT = 53317

data class LocalSendDeviceInfo(
    val alias: String,
    val version: String = LOCALSEND_VERSION,
    val deviceModel: String? = null,
    val deviceType: String = "mobile",
    val fingerprint: String,
    val port: Int,
    val protocol: String = "http",
    val download: Boolean = false,
)

data class LocalSendFileMetadata(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    val sha256: String? = null,
    val preview: String? = null,
    val relativePath: String? = null,
)

data class LocalSendPrepareUploadRequest(
    val info: LocalSendDeviceInfo,
    val files: List<LocalSendFileMetadata>,
)

data class LocalSendAnnouncement(
    val info: LocalSendDeviceInfo,
    val announce: Boolean,
)

enum class TransferTransportKind(
    val wireName: String,
    val isImplemented: Boolean,
) {
    LanDirect("lan-direct", true),
    P2pDirect("p2p-direct", false),
}

interface TransferTransport {
    val kind: TransferTransportKind
}

object LocalSendProtocol {
    fun announcement(
        info: LocalSendDeviceInfo,
        announce: Boolean,
    ): String {
        return info.toJson()
            .put("announce", announce)
            .toString()
    }

    fun decodeAnnouncement(json: String): LocalSendAnnouncement {
        val root = JSONObject(json)
        return LocalSendAnnouncement(
            info = root.toLocalSendDeviceInfo(),
            announce = root.optBoolean("announce", false),
        )
    }

    fun deviceInfoResponse(info: LocalSendDeviceInfo): String {
        return info.toJson().toString()
    }

    fun prepareUploadRequest(
        sender: LocalSendDeviceInfo,
        files: List<LocalSendFileMetadata>,
    ): String {
        return JSONObject()
            .put("info", sender.toJson())
            .put("files", files.associateBy { it.id }.toJsonObject { it.toJson() })
            .toString()
    }

    fun decodePrepareUploadRequest(json: String): LocalSendPrepareUploadRequest {
        val root = JSONObject(json)
        val filesObject = root.getJSONObject("files")
        val files = filesObject.keys().asSequence().map { key ->
            filesObject.getJSONObject(key).toLocalSendFileMetadata(defaultId = key)
        }.toList()
        return LocalSendPrepareUploadRequest(
            info = root.getJSONObject("info").toLocalSendDeviceInfo(),
            files = files,
        )
    }

    fun prepareUploadResponse(
        sessionId: String,
        fileTokens: Map<String, String>,
    ): String {
        return JSONObject()
            .put("sessionId", sessionId)
            .put("files", JSONObject(fileTokens))
            .toString()
    }

    fun decodePrepareUploadResponse(json: String): LocalSendPrepareUploadResponse {
        val root = JSONObject(json)
        val filesObject = root.getJSONObject("files")
        val fileTokens = filesObject.keys().asSequence().associateWith { key ->
            filesObject.getString(key)
        }
        return LocalSendPrepareUploadResponse(
            sessionId = root.getString("sessionId"),
            fileTokens = fileTokens,
        )
    }
}

private fun LocalSendDeviceInfo.toJson(): JSONObject {
    return JSONObject()
        .put("alias", alias)
        .put("version", version)
        .putNullable("deviceModel", deviceModel)
        .put("deviceType", deviceType)
        .put("fingerprint", fingerprint)
        .put("port", port)
        .put("protocol", protocol)
        .put("download", download)
}

private fun LocalSendFileMetadata.toJson(): JSONObject {
    val metadata = JSONObject().putNullable("relativePath", relativePath)
    return JSONObject()
        .put("id", id)
        .put("fileName", fileName)
        .put("size", size)
        .put("fileType", fileType)
        .putNullable("sha256", sha256)
        .putNullable("preview", preview)
        .put("metadata", metadata)
}

private fun JSONObject.toLocalSendDeviceInfo(): LocalSendDeviceInfo {
    return LocalSendDeviceInfo(
        alias = getString("alias"),
        version = optString("version", LOCALSEND_VERSION),
        deviceModel = optNullableString("deviceModel"),
        deviceType = optString("deviceType", "desktop"),
        fingerprint = optString("fingerprint"),
        port = optInt("port", 0),
        protocol = optString("protocol", "http"),
        download = optBoolean("download", false),
    )
}

private fun JSONObject.toLocalSendFileMetadata(defaultId: String): LocalSendFileMetadata {
    val metadata = optJSONObject("metadata")
    return LocalSendFileMetadata(
        id = optString("id", defaultId),
        fileName = getString("fileName"),
        size = getLong("size"),
        fileType = optString("fileType", "application/octet-stream"),
        sha256 = optNullableString("sha256"),
        preview = optNullableString("preview"),
        relativePath = metadata?.optNullableString("relativePath"),
    )
}

private fun JSONObject.putNullable(name: String, value: String?): JSONObject {
    return put(name, value ?: JSONObject.NULL)
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return optString(name)
}

private fun <T> Map<String, T>.toJsonObject(value: (T) -> JSONObject): JSONObject {
    val output = JSONObject()
    forEach { (key, item) ->
        output.put(key, value(item))
    }
    return output
}
