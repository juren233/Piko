package com.piko.app.transport

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import com.piko.app.data.LocalSendSessionStore
import com.piko.app.data.ReceiveMediaSaveLocation
import com.piko.app.data.ReceiveSaveDestination
import com.piko.app.domain.ReceiveFileType
import com.piko.app.domain.ReceiveHistoryFile
import com.piko.app.domain.ReceiveHistoryItem
import com.piko.app.domain.ReceiveTransferEvent
import com.piko.app.domain.ReceiveTransferState
import com.piko.app.domain.SendFileType
import com.piko.app.domain.SendTransferHeader
import com.piko.app.domain.SendTransferHeaderFile
import com.piko.app.platform.currentTimeMillis
import com.piko.app.platform.newTransferId
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

private val LEGACY_PIKO_MAGIC = byteArrayOf(0x50, 0x49, 0x4B, 0x4F)
private const val LEGACY_PIKO_PROTOCOL_VERSION = 2

class LocalSendHttpServer(
    private val context: Context,
    private val deviceInfo: (port: Int) -> LocalSendDeviceInfo,
    private val mediaSaveLocation: () -> ReceiveMediaSaveLocation = { ReceiveMediaSaveLocation.Folder },
    private val onReceiveTransferEvent: (ReceiveTransferEvent) -> Unit = {},
    private val onActiveReceiveChanged: (String?, Socket?) -> Unit = { _, _ -> },
) : TransferTransport {
    override val kind: TransferTransportKind = TransferTransportKind.LanDirect

    private val sessionStore = LocalSendSessionStore()
    private val receiveSessions = ConcurrentHashMap<String, LocalSendReceiveSessionState>()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val port: Int
        get() = requireNotNull(serverSocket).localPort

    fun start(preferredPort: Int = LOCALSEND_PORT): Int {
        if (serverSocket != null) {
            return port
        }
        val socket = runCatching { ServerSocket(preferredPort) }.getOrElse { ServerSocket(0) }
        serverSocket = socket
        acceptThread = thread(name = "PikoLocalSendHttpServer", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "PikoLocalSendHttpConnection", isDaemon = true) {
                    client.use { incoming ->
                        runCatching { handle(incoming) }
                    }
                }
            }
        }
        return socket.localPort
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        acceptThread?.interrupt()
        serverSocket = null
        acceptThread = null
    }

    private fun handle(socket: Socket) {
        val input = PushbackBufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        if (input.startsWith(LEGACY_PIKO_MAGIC)) {
            receiveLegacyTransfer(
                context = context,
                input = input,
                mediaSaveLocation = mediaSaveLocation(),
                onReceiveTransferEvent = onReceiveTransferEvent,
                onActiveReceiveChanged = { transferId, activeSocket ->
                    onActiveReceiveChanged(transferId, activeSocket)
                },
                socket = socket,
            )
            return
        }

        val requestLine = input.readHttpLine() ?: return
        val route = LocalSendHttpRoute.parse(requestLine)
        val headers = input.readHeaders()
        val contentLength = headers["content-length"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        when {
            route.method == "GET" && route.path == "/api/localsend/v2/info" -> {
                output.writeJson(200, LocalSendProtocol.deviceInfoResponse(deviceInfo(port)))
            }

            route.method == "POST" && route.path == "/api/localsend/v2/register" -> {
                input.skipFixedLength(contentLength)
                output.writeJson(200, LocalSendProtocol.deviceInfoResponse(deviceInfo(port)))
            }

            route.method == "POST" && route.path == "/api/localsend/v2/prepare-upload" -> {
                val request = LocalSendProtocol.decodePrepareUploadRequest(input.readUtf8Body(contentLength))
                val response = sessionStore.prepare(request)
                val files = request.files.map { file -> file.toReceiveHistoryFile() }
                val totalBytes = request.files.sumOf { file -> file.size }
                receiveSessions[response.sessionId] = LocalSendReceiveSessionState(
                    senderName = request.info.alias,
                    files = files,
                    totalBytes = totalBytes,
                )
                onReceiveTransferEvent(
                    ReceiveTransferEvent.Started(
                        transferId = response.sessionId,
                        senderName = request.info.alias,
                        files = files,
                        totalBytes = totalBytes,
                    ),
                )
                output.writeJson(
                    code = 200,
                    body = LocalSendProtocol.prepareUploadResponse(
                        sessionId = response.sessionId,
                        fileTokens = response.fileTokens,
                    ),
                )
            }

            route.method == "POST" && route.path == "/api/localsend/v2/upload" -> {
                val sessionId = route.query["sessionId"].orEmpty()
                val fileId = route.query["fileId"].orEmpty()
                val token = route.query["token"].orEmpty()
                val file = sessionStore.validate(sessionId, fileId, token)
                if (file == null) {
                    input.skipFixedLength(contentLength)
                    output.writeJson(403, """{"error":"invalid upload token"}""")
                    return
                }
                onActiveReceiveChanged(sessionId, socket)
                val uri = context.contentResolver.saveDownload(file.metadata, input, contentLength, mediaSaveLocation())
                val session = receiveSessions[sessionId]
                if (session != null) {
                    val completedFile = file.metadata.toReceiveHistoryFile(
                        thumbnailBytes = if (file.metadata.fileType.isMediaPreview) {
                            context.contentResolver.loadThumbnailBytes(uri)
                        } else {
                            null
                        },
                        savedUri = uri.toString(),
                    )
                    val completedBytes = session.completeFile(file.metadata.fileName, completedFile, contentLength)
                    onReceiveTransferEvent(
                        ReceiveTransferEvent.Progress(
                            transferId = sessionId,
                            completedBytes = completedBytes.coerceAtMost(session.totalBytes),
                            totalBytes = session.totalBytes,
                        ),
                    )
                    if (completedBytes >= session.totalBytes) {
                        receiveSessions.remove(sessionId)
                        onReceiveTransferEvent(
                            ReceiveTransferEvent.Completed(
                                transferId = sessionId,
                                senderName = session.senderName,
                                files = session.historyFiles(),
                                receivedAtEpochMillis = currentTimeMillis(),
                                receivedAtLabel = "刚刚",
                            ),
                        )
                    }
                }
                onActiveReceiveChanged(null, null)
                output.writeJson(200, """{"success":true}""")
            }

            route.method == "POST" && route.path == "/api/localsend/v2/cancel" -> {
                input.skipFixedLength(contentLength)
                route.query["sessionId"]?.let { sessionId ->
                    sessionStore.cancel(sessionId)
                    receiveSessions.remove(sessionId)
                    onReceiveTransferEvent(ReceiveTransferEvent.Canceled(sessionId))
                }
                output.writeJson(200, """{"success":true}""")
            }

            else -> {
                input.skipFixedLength(contentLength)
                output.writeJson(404, """{"error":"not found"}""")
            }
        }
    }
}

private class PushbackBufferedInputStream(
    input: InputStream,
) : BufferedInputStream(input) {
    fun startsWith(prefix: ByteArray): Boolean {
        mark(prefix.size)
        val bytes = ByteArray(prefix.size)
        val read = read(bytes)
        reset()
        return read == prefix.size && bytes.contentEquals(prefix)
    }
}

private fun InputStream.readHttpLine(): String? {
    val output = ByteArrayOutputStream()
    while (true) {
        val byte = read()
        if (byte == -1) {
            return if (output.size() == 0) null else output.toString(Charsets.US_ASCII.name())
        }
        if (byte == '\n'.code) {
            break
        }
        if (byte != '\r'.code) {
            output.write(byte)
        }
        require(output.size() <= 8192) { "HTTP line too long" }
    }
    return output.toString(Charsets.US_ASCII.name())
}

private fun InputStream.readHeaders(): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    while (true) {
        val line = readHttpLine() ?: break
        if (line.isBlank()) {
            break
        }
        val name = line.substringBefore(':').trim().lowercase()
        val value = line.substringAfter(':', missingDelimiterValue = "").trim()
        if (name.isNotBlank()) {
            headers[name] = value
        }
    }
    return headers
}

private fun InputStream.readUtf8Body(contentLength: Long): String {
    val output = ByteArrayOutputStream()
    copyFixedLength(
        output = output,
        contentLength = contentLength,
        digest = null,
    )
    return output.toString(Charsets.UTF_8.name())
}

private fun InputStream.skipFixedLength(contentLength: Long) {
    var remaining = contentLength
    while (remaining > 0L) {
        val skipped = skip(remaining)
        if (skipped <= 0L) {
            if (read() == -1) break
            remaining -= 1
        } else {
            remaining -= skipped
        }
    }
}

private fun InputStream.copyFixedLength(
    output: OutputStream,
    contentLength: Long,
    digest: MessageDigest?,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = contentLength
    while (remaining > 0L) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        require(read != -1) { "传输内容不完整" }
        output.write(buffer, 0, read)
        digest?.update(buffer, 0, read)
        remaining -= read
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString("") { byte -> "%02x".format(byte) }
}

private fun OutputStream.writeJson(
    code: Int,
    body: String,
) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val reason = when (code) {
        200 -> "OK"
        403 -> "Forbidden"
        404 -> "Not Found"
        else -> "Error"
    }
    write(
        buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII),
    )
    write(bytes)
    flush()
}

private fun ContentResolver.saveDownload(
    file: LocalSendFileMetadata,
    input: InputStream,
    contentLength: Long,
    mediaSaveLocation: ReceiveMediaSaveLocation,
): Uri {
    val saveTarget = file.saveTarget(mediaSaveLocation)
    val uri = insert(
        saveTarget.collectionUri,
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.fileName.safeDownloadName())
            put(MediaStore.MediaColumns.MIME_TYPE, file.fileType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, saveTarget.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        },
    ) ?: error("无法创建接收文件 ${file.fileName}")
    try {
        val digest = file.sha256?.let { MessageDigest.getInstance("SHA-256") }
        openOutputStream(uri).use { output ->
            requireNotNull(output) { "无法打开接收文件 ${file.fileName}" }
            input.copyFixedLength(
                output = output,
                contentLength = contentLength,
                digest = digest,
            )
        }
        val expected = file.sha256
        if (expected != null) {
            val actual = requireNotNull(digest).digest().toHexString()
            require(actual.equals(expected, ignoreCase = true)) { "文件校验失败：${file.fileName}" }
        }
        update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        return uri
    } catch (error: Throwable) {
        delete(uri, null, null)
        throw error
    }
}

private fun receiveLegacyTransfer(
    context: Context,
    input: InputStream,
    mediaSaveLocation: ReceiveMediaSaveLocation,
    onReceiveTransferEvent: (ReceiveTransferEvent) -> Unit,
    onActiveReceiveChanged: (String?, Socket?) -> Unit,
    socket: Socket,
) {
    val transferId = newTransferId()
    onActiveReceiveChanged(transferId, socket)
    DataInputStream(input).use { dataInput ->
        val header = readLegacyTransferHeader(dataInput)
        val totalBytes = header.files.sumOf { file -> file.sizeBytes }
        val pendingFiles = header.files.map { file -> file.toReceiveHistoryFile() }
        onReceiveTransferEvent(
            ReceiveTransferEvent.Started(
                transferId = transferId,
                senderName = header.senderName,
                files = pendingFiles,
                totalBytes = totalBytes,
            ),
        )
        var completedBytes = 0L
        val receivedFiles = mutableListOf<ReceiveHistoryFile>()
        header.files.forEach { file ->
            val metadata = LocalSendFileMetadata(
                id = file.displayName,
                fileName = file.displayName,
                size = file.sizeBytes,
                fileType = file.fileType.mimeType,
            )
            val uri = context.contentResolver.saveDownload(
                file = metadata,
                input = dataInput,
                contentLength = file.sizeBytes,
                mediaSaveLocation = mediaSaveLocation,
            )
            completedBytes += file.sizeBytes
            receivedFiles += file.toReceiveHistoryFile(
                thumbnailBytes = if (file.fileType.isMediaPreview) {
                    context.contentResolver.loadThumbnailBytes(uri)
                } else {
                    null
                },
                savedUri = uri.toString(),
            )
            onReceiveTransferEvent(
                ReceiveTransferEvent.Progress(
                    transferId = transferId,
                    completedBytes = completedBytes.coerceAtMost(totalBytes),
                    totalBytes = totalBytes,
                ),
            )
        }
        onReceiveTransferEvent(
            ReceiveTransferEvent.Completed(
                transferId = transferId,
                senderName = header.senderName,
                files = receivedFiles,
                receivedAtEpochMillis = currentTimeMillis(),
                receivedAtLabel = "刚刚",
            ),
        )
    }
    onActiveReceiveChanged(null, null)
}

private data class AndroidReceiveSaveTarget(
    val collectionUri: android.net.Uri,
    val relativePath: String,
)

private class LocalSendReceiveSessionState(
    val senderName: String,
    files: List<ReceiveHistoryFile>,
    val totalBytes: Long,
) {
    private val files = files.toMutableList()
    private var completedBytes = 0L

    @Synchronized
    fun completeFile(fileName: String, file: ReceiveHistoryFile, bytes: Long): Long {
        val index = files.indexOfFirst { it.displayName == fileName }
        if (index >= 0) {
            files[index] = file
        }
        completedBytes += bytes
        return completedBytes
    }

    @Synchronized
    fun historyFiles(): List<ReceiveHistoryFile> = files.toList()
}

private fun LocalSendFileMetadata.toReceiveHistoryFile(
    thumbnailBytes: ByteArray? = null,
    savedUri: String? = null,
): ReceiveHistoryFile {
    return ReceiveHistoryFile(
        displayName = fileName,
        fileType = fileType.toReceiveFileType(),
        sizeBytes = size,
        thumbnailBytes = thumbnailBytes,
        savedUri = savedUri,
    )
}

private fun LocalSendFileMetadata.saveTarget(
    mediaSaveLocation: ReceiveMediaSaveLocation,
): AndroidReceiveSaveTarget {
    return when (mediaSaveLocation.destinationFor(fileType)) {
        ReceiveSaveDestination.Album -> albumSaveTarget(fileType)
        ReceiveSaveDestination.Folder -> AndroidReceiveSaveTarget(
            collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            relativePath = "${Environment.DIRECTORY_DOWNLOADS}/Piko",
        )
    }
}

private fun albumSaveTarget(mimeType: String): AndroidReceiveSaveTarget {
    return if (mimeType.startsWith("video/")) {
        AndroidReceiveSaveTarget(
            collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            relativePath = "${Environment.DIRECTORY_MOVIES}/Piko",
        )
    } else {
        AndroidReceiveSaveTarget(
            collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativePath = "${Environment.DIRECTORY_PICTURES}/Piko",
        )
    }
}

private fun readLegacyTransferHeader(input: DataInputStream): SendTransferHeader {
    val magic = ByteArray(LEGACY_PIKO_MAGIC.size)
    input.readFully(magic)
    require(magic.contentEquals(LEGACY_PIKO_MAGIC)) { "传输协议标识不匹配" }
    val version = input.readInt()
    require(version in 1..LEGACY_PIKO_PROTOCOL_VERSION) { "传输协议版本不支持" }
    val senderName = if (version >= 2) {
        val senderNameSize = input.readInt()
        require(senderNameSize >= 0) { "设备名称长度无效" }
        val senderNameBytes = ByteArray(senderNameSize)
        input.readFully(senderNameBytes)
        senderNameBytes.toString(Charsets.UTF_8)
    } else {
        "局域网设备"
    }
    val count = input.readInt()
    require(count >= 0) { "文件数量无效" }
    val files = List(count) {
        val nameSize = input.readInt()
        require(nameSize >= 0) { "文件名长度无效" }
        val nameBytes = ByteArray(nameSize)
        input.readFully(nameBytes)
        val type = SendFileType.entries.getOrElse(input.readInt()) { SendFileType.Other }
        val size = input.readLong()
        require(size >= 0L) { "文件大小无效" }
        SendTransferHeaderFile(
            displayName = nameBytes.toString(Charsets.UTF_8),
            fileType = type,
            sizeBytes = size,
        )
    }
    return SendTransferHeader(senderName = senderName, files = files)
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

private val String.isMediaPreview: Boolean
    get() = startsWith("image/") || startsWith("video/")

private val SendFileType.isMediaPreview: Boolean
    get() = this == SendFileType.Image || this == SendFileType.Video

private fun ContentResolver.loadThumbnailBytes(uri: Uri): ByteArray? {
    return runCatching {
        val bitmap = loadThumbnail(uri, Size(240, 240), null)
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            output.toByteArray()
        }
    }.getOrNull()
}

private fun SendTransferHeaderFile.toReceiveHistoryFile(
    thumbnailBytes: ByteArray? = null,
    savedUri: String? = null,
): ReceiveHistoryFile {
    return ReceiveHistoryFile(
        displayName = displayName,
        fileType = fileType.toReceiveFileType(),
        sizeBytes = sizeBytes,
        thumbnailBytes = thumbnailBytes,
        savedUri = savedUri,
    )
}

private fun String.toReceiveFileType(): ReceiveFileType {
    return when {
        startsWith("image/") -> ReceiveFileType.Image
        startsWith("video/") -> ReceiveFileType.Video
        contains("zip") -> ReceiveFileType.Archive
        contains("spreadsheet") || contains("excel") || contains("csv") -> ReceiveFileType.Spreadsheet
        contains("pdf") || contains("document") || contains("word") -> ReceiveFileType.Document
        else -> ReceiveFileType.Other
    }
}

private fun SendFileType.toReceiveFileType(): ReceiveFileType {
    return when (this) {
        SendFileType.Image -> ReceiveFileType.Image
        SendFileType.Document -> ReceiveFileType.Document
        SendFileType.Spreadsheet -> ReceiveFileType.Spreadsheet
        SendFileType.Video -> ReceiveFileType.Video
        SendFileType.Archive -> ReceiveFileType.Archive
        SendFileType.Other -> ReceiveFileType.Other
    }
}

private fun String.safeDownloadName(): String {
    return replace('/', '_').replace('\\', '_').ifBlank { "Piko 文件" }
}
