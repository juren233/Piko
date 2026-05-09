package com.piko.app

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class LocalSendHttpUploadClient(
    private val context: Context,
    private val senderInfo: () -> LocalSendDeviceInfo,
) {
    fun upload(
        target: SendDevice,
        items: List<SendTransferItem>,
        totalCompletedBeforeTarget: Long,
        totalBytes: Long,
        transferId: String,
        callback: (SendTransferEvent) -> Unit,
        ensureActive: () -> Unit,
    ): Long {
        val host = requireNotNull(target.host) { "目标设备缺少地址" }
        val port = requireNotNull(target.port) { "目标设备缺少端口" }
        val indexedItems = items.mapIndexed { index, item ->
            LocalSendIndexedItem(
                fileId = "file-$index",
                item = item,
                metadata = item.toLocalSendMetadata(fileId = "file-$index"),
            )
        }
        val response = prepareUpload(host, port, indexedItems.map { it.metadata })
        var targetCompletedBytes = 0L
        indexedItems.forEach { indexed ->
            ensureActive()
            val token = requireNotNull(response.fileTokens[indexed.fileId]) {
                "目标设备未返回 ${indexed.item.displayName} 的上传令牌"
            }
            uploadFile(
                host = host,
                port = port,
                sessionId = response.sessionId,
                fileId = indexed.fileId,
                token = token,
                item = indexed.item,
                totalCompletedBeforeFile = totalCompletedBeforeTarget + targetCompletedBytes,
                totalBytes = totalBytes,
                transferId = transferId,
                callback = callback,
                ensureActive = ensureActive,
            )
            targetCompletedBytes += indexed.item.sizeBytes
        }
        return targetCompletedBytes
    }

    private fun prepareUpload(
        host: String,
        port: Int,
        files: List<LocalSendFileMetadata>,
    ): LocalSendPrepareUploadResponse {
        val body = LocalSendProtocol.prepareUploadRequest(
            sender = senderInfo(),
            files = files,
        )
        val response = postJson(
            url = "http://$host:$port/api/localsend/v2/prepare-upload",
            body = body,
        )
        return LocalSendProtocol.decodePrepareUploadResponse(response)
    }

    private fun uploadFile(
        host: String,
        port: Int,
        sessionId: String,
        fileId: String,
        token: String,
        item: SendTransferItem,
        totalCompletedBeforeFile: Long,
        totalBytes: Long,
        transferId: String,
        callback: (SendTransferEvent) -> Unit,
        ensureActive: () -> Unit,
    ) {
        val url = "http://$host:$port/api/localsend/v2/upload" +
            "?sessionId=${sessionId.urlEncode()}" +
            "&fileId=${fileId.urlEncode()}" +
            "&token=${token.urlEncode()}"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(item.sizeBytes)
        connection.connectTimeout = 5_000
        connection.readTimeout = 30_000
        context.contentResolver.openInputStream(Uri.parse(item.sourceUri)).use { input ->
            requireNotNull(input) { "无法读取 ${item.displayName}" }
            BufferedOutputStream(connection.outputStream).use { output ->
                input.copyWithProgress(
                    output = output,
                    totalCompletedBeforeFile = totalCompletedBeforeFile,
                    totalBytes = totalBytes,
                    transferId = transferId,
                    callback = callback,
                    ensureActive = ensureActive,
                )
            }
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("LocalSend 上传失败：HTTP $code")
        }
        connection.disconnect()
    }

    private fun postJson(
        url: String,
        body: String,
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        val bytes = body.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { output ->
            output.write(bytes)
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("LocalSend prepare-upload 失败：HTTP $code")
        }
        return response
    }
}

private data class LocalSendIndexedItem(
    val fileId: String,
    val item: SendTransferItem,
    val metadata: LocalSendFileMetadata,
)

private fun SendTransferItem.toLocalSendMetadata(fileId: String): LocalSendFileMetadata {
    return LocalSendFileMetadata(
        id = fileId,
        fileName = displayName,
        size = sizeBytes,
        fileType = fileType.mimeType,
        sha256 = null,
        preview = inlineBytes?.let { bytes -> android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) },
        relativePath = displayName,
    )
}

private fun InputStream.copyWithProgress(
    output: java.io.OutputStream,
    totalCompletedBeforeFile: Long,
    totalBytes: Long,
    transferId: String,
    callback: (SendTransferEvent) -> Unit,
    ensureActive: () -> Unit,
) {
    val input = BufferedInputStream(this)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        ensureActive()
        val read = input.read(buffer)
        if (read == -1) {
            break
        }
        output.write(buffer, 0, read)
        copied += read
        callback(
            SendTransferEvent.Progress(
                transferId = transferId,
                completedBytes = (totalCompletedBeforeFile + copied).coerceAtMost(totalBytes),
                totalBytes = totalBytes,
            ),
        )
    }
}

private val SendFileType.mimeType: String
    get() = when (this) {
        SendFileType.Image -> "image/*"
        SendFileType.Document -> "application/octet-stream"
        SendFileType.Spreadsheet -> "application/octet-stream"
        SendFileType.Video -> "video/*"
        SendFileType.Archive -> "application/zip"
        SendFileType.Other -> "application/octet-stream"
    }

private fun String.urlEncode(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name())
}
