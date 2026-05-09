package com.piko.app

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

private const val PIKO_SERVICE_TYPE = "_piko-share._tcp."
private const val PIKO_SERVICE_PREFIX = "Piko-"
private const val PIKO_ATTR_TITLE = "title"
private const val PIKO_ATTR_CODE = "code"
private const val PIKO_ATTR_FINGERPRINT = "fp"
private const val RECENT_IMAGE_LIMIT = 24
private val PIKO_MAGIC = byteArrayOf(0x50, 0x49, 0x4B, 0x4F)
private const val PIKO_PROTOCOL_VERSION = 1

@Composable
internal fun rememberAndroidSendPlatformActions(currentNickname: DeviceNickname): SendPlatformActions {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var recentImagesCallback by remember {
        mutableStateOf<((SendPermissionState, List<SendImageItem>) -> Unit)?>(null)
    }
    var pickedImagesCallback by remember {
        mutableStateOf<((List<SendImageItem>) -> Unit)?>(null)
    }
    var pickedFilesCallback by remember {
        mutableStateOf<((List<SendFileItem>) -> Unit)?>(null)
    }
    val lanDiscovery = remember(appContext, currentNickname) {
        AndroidLanDiscovery(
            context = appContext,
            currentNickname = currentNickname,
        )
    }
    val transferClient = remember(appContext) {
        AndroidTransferClient(appContext)
    }

    val imagePermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        val callback = recentImagesCallback
        recentImagesCallback = null
        if (granted) {
            callback?.invoke(SendPermissionState.Granted, loadRecentImages(appContext))
        } else {
            callback?.invoke(SendPermissionState.Denied, emptyList())
        }
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(PickMultipleVisualMedia(30)) { uris ->
        val callback = pickedImagesCallback
        pickedImagesCallback = null
        callback?.invoke(loadPickedImages(appContext, uris))
    }
    val filePickerLauncher = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        val callback = pickedFilesCallback
        pickedFilesCallback = null
        callback?.invoke(loadPickedFiles(appContext, uris))
    }

    DisposableEffect(lanDiscovery) {
        lanDiscovery.startPresence()
        onDispose {
            lanDiscovery.stop()
            transferClient.cancelAll()
        }
    }

    return SendPlatformActions(
        requestRecentImages = { callback ->
            if (hasImagePermission(appContext)) {
                callback(SendPermissionState.Granted, loadRecentImages(appContext))
            } else {
                recentImagesCallback = callback
                callback(SendPermissionState.Requesting, emptyList())
                imagePermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
            }
        },
        pickImages = { callback ->
            pickedImagesCallback = callback
            imagePickerLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        },
        pickFiles = { callback ->
            pickedFilesCallback = callback
            filePickerLauncher.launch(arrayOf("*/*"))
        },
        startLanDiscovery = { callback ->
            lanDiscovery.start(callback)
        },
        stopLanDiscovery = {
            lanDiscovery.stopDiscovery()
        },
        startTransfer = transferClient::startTransfer,
        pauseTransfer = transferClient::pauseTransfer,
        cancelTransfer = transferClient::cancelTransfer,
    )
}

private fun hasImagePermission(context: Context): Boolean {
    return context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
}

private fun loadRecentImages(context: Context): List<SendImageItem> {
    val resolver = context.contentResolver
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.SIZE,
    )
    val queryArgs = Bundle().apply {
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        putInt(ContentResolver.QUERY_ARG_LIMIT, RECENT_IMAGE_LIMIT)
    }

    return resolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        queryArgs,
        null,
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        buildList {
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idColumn),
                )
                add(
                    SendImageItem(
                        id = uri.toString(),
                        displayName = cursor.getString(nameColumn) ?: "图片",
                        uri = uri.toString(),
                        sizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                        thumbnailBytes = loadThumbnailBytes(resolver, uri),
                    ),
                )
            }
        }
    } ?: emptyList()
}

private fun loadPickedImages(context: Context, uris: List<Uri>): List<SendImageItem> {
    val resolver = context.contentResolver
    return uris.map { uri ->
        SendImageItem(
            id = uri.toString(),
            displayName = queryDisplayName(resolver, uri) ?: "图片",
            uri = uri.toString(),
            sizeBytes = querySizeBytes(resolver, uri),
            thumbnailBytes = loadThumbnailBytes(resolver, uri),
        )
    }
}

private fun loadPickedFiles(context: Context, uris: List<Uri>): List<SendFileItem> {
    val resolver = context.contentResolver
    return uris.map { uri ->
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val displayName = queryDisplayName(resolver, uri) ?: uri.lastPathSegment ?: "未命名文件"
        SendFileItem(
            id = uri.toString(),
            displayName = displayName,
            sizeBytes = querySizeBytes(resolver, uri),
            fileType = resolveFileType(
                mimeType = resolver.getType(uri),
                displayName = displayName,
            ),
            sourceUri = uri.toString(),
        )
    }
}

private fun loadThumbnailBytes(resolver: ContentResolver, uri: Uri): ByteArray? {
    return runCatching {
        val bitmap = resolver.loadThumbnail(uri, Size(240, 240), null)
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            output.toByteArray()
        }
    }.getOrNull()
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
    return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } else {
            null
        }
    }
}

private fun querySizeBytes(resolver: ContentResolver, uri: Uri): Long {
    return resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)).coerceAtLeast(0L)
        } else {
            0L
        }
    } ?: 0L
}

private fun resolveFileType(mimeType: String?, displayName: String): SendFileType {
    val lowerName = displayName.lowercase()
    return when {
        mimeType?.startsWith("image/") == true -> SendFileType.Image
        mimeType?.startsWith("video/") == true -> SendFileType.Video
        lowerName.endsWith(".zip") || lowerName.endsWith(".rar") || lowerName.endsWith(".7z") -> SendFileType.Archive
        lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx") || lowerName.endsWith(".csv") -> SendFileType.Spreadsheet
        lowerName.endsWith(".pdf") || lowerName.endsWith(".doc") || lowerName.endsWith(".docx") -> SendFileType.Document
        else -> SendFileType.Other
    }
}

private class AndroidLanDiscovery(
    private val context: Context,
    private val currentNickname: DeviceNickname,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val devices = linkedMapOf<String, SendDevice>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var registeredServiceName: String? = null
    private var discoveryActive = false

    fun startPresence() {
        runCatching {
            registerLocalService()
        }
    }

    fun start(callback: (SendLanDiscoveryState, List<SendDevice>) -> Unit) {
        stopDiscovery()
        discoveryActive = true
        devices.clear()
        post(callback, SendLanDiscoveryState.Searching)
        runCatching {
            registerLocalService()
        }.onFailure {
            post(callback, SendLanDiscoveryState.Failed)
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.normalizedServiceType() != PIKO_SERVICE_TYPE.normalizedServiceType() ||
                    isRegisteredLocalService(serviceInfo.serviceName)
                ) {
                    return
                }
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                            val nickname = resolvedService.deviceNickname()
                            if (isRegisteredLocalService(resolvedService.serviceName) ||
                                nickname.fingerprint == currentNickname.fingerprint
                            ) {
                                return
                            }
                            val id = "${resolvedService.serviceName}-${resolvedService.host?.hostAddress}-${resolvedService.port}"
                            devices[id] = SendDevice(
                                id = id,
                                name = nickname.title,
                                group = SendDeviceGroup.Lan,
                                subtitle = nickname.code,
                                host = resolvedService.host?.hostAddress,
                                port = resolvedService.port,
                                platformHint = "android",
                            )
                            post(callback, SendLanDiscoveryState.Found)
                        }
                    },
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                devices.entries.removeAll { (id, _) -> id.startsWith("${serviceInfo.serviceName}-") }
                post(callback, if (devices.isEmpty()) SendLanDiscoveryState.Empty else SendLanDiscoveryState.Found)
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                post(callback, SendLanDiscoveryState.Failed)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(PIKO_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            post(callback, SendLanDiscoveryState.Failed)
        }
        mainHandler.postDelayed({
            if (discoveryActive && devices.isEmpty()) {
                post(callback, SendLanDiscoveryState.Empty)
            }
        }, 2_500L)
    }

    fun stopDiscovery() {
        discoveryActive = false
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        devices.clear()
    }

    fun stop() {
        stopDiscovery()
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        runCatching { serverSocket?.close() }
        acceptThread?.interrupt()
        registrationListener = null
        releaseMulticastLock()
        serverSocket = null
        acceptThread = null
        registeredServiceName = null
    }

    private fun registerLocalService() {
        if (serverSocket != null && registrationListener != null) {
            return
        }
        acquireMulticastLock()
        val socket = ServerSocket(0)
        serverSocket = socket
        startAcceptLoop(socket)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$PIKO_SERVICE_PREFIX${currentNickname.fullName}"
            serviceType = PIKO_SERVICE_TYPE
            port = socket.localPort
            setAttribute(PIKO_ATTR_TITLE, currentNickname.title)
            setAttribute(PIKO_ATTR_CODE, currentNickname.code)
            setAttribute(PIKO_ATTR_FINGERPRINT, currentNickname.fingerprint)
        }
        registeredServiceName = serviceInfo.serviceName
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registeredServiceName = serviceInfo.serviceName
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            registrationListener = listener
        } catch (error: RuntimeException) {
            runCatching { socket.close() }
            serverSocket = null
            throw error
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) {
            return
        }
        multicastLock = wifiManager?.createMulticastLock("PikoLanDiscovery")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        multicastLock = null
    }

    private fun startAcceptLoop(socket: ServerSocket) {
        acceptThread = thread(
            name = "PikoAndroidReceiveServer",
            isDaemon = true,
        ) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(
                    name = "PikoAndroidReceiveConnection",
                    isDaemon = true,
                ) {
                    client.use { incoming ->
                        runCatching { receiveIncomingTransfer(context, incoming) }
                    }
                }
            }
        }
    }

    private fun post(
        callback: (SendLanDiscoveryState, List<SendDevice>) -> Unit,
        state: SendLanDiscoveryState,
    ) {
        mainHandler.post {
            if (discoveryActive) {
                callback(state, devices.values.toList())
            }
        }
    }

    private fun isRegisteredLocalService(serviceName: String): Boolean {
        return serviceName == registeredServiceName
    }
}

private fun String.normalizedServiceType(): String {
    return trim().trimEnd('.')
}

private fun NsdServiceInfo.deviceNickname(): DeviceNickname {
    val txtAttributes = this.attributes
    val serviceNickname = serviceName.removePrefix(PIKO_SERVICE_PREFIX)
    val fallbackTitle = serviceNickname.substringBefore("@").ifBlank { serviceNickname }
    val fallbackCode = serviceNickname.substringAfter("@", "").takeIf { it.matches(Regex("\\d{4}")) }
    return DeviceNickname(
        title = txtAttributes[PIKO_ATTR_TITLE]?.toString(Charsets.UTF_8)?.ifBlank { null } ?: fallbackTitle,
        code = txtAttributes[PIKO_ATTR_CODE]?.toString(Charsets.UTF_8)?.takeIf { it.matches(Regex("\\d{4}")) }
            ?: fallbackCode
            ?: "0000",
        fingerprint = txtAttributes[PIKO_ATTR_FINGERPRINT]?.toString(Charsets.UTF_8).orEmpty(),
    )
}

private class AndroidTransferClient(
    private val context: Context,
) {
    @Volatile
    private var activeTransferId: String? = null

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var requestedStop: SendTransferStatus? = null

    fun startTransfer(
        request: SendTransferRequest,
        callback: (SendTransferEvent) -> Unit,
    ) {
        val transferId = newTransferId()
        activeTransferId = transferId
        requestedStop = null
        callback(SendTransferEvent.Started(transferId, request, request.totalBytes))
        thread(
            name = "PikoAndroidSendTransfer",
            isDaemon = true,
        ) {
            var completedBytes = 0L
            try {
                request.targets.forEach { target ->
                    ensureNotStopped()
                    val host = requireNotNull(target.host) { "目标设备缺少地址" }
                    val port = requireNotNull(target.port) { "目标设备缺少端口" }
                    Socket().use { socket ->
                        activeSocket = socket
                        socket.connect(InetSocketAddress(host, port), 5_000)
                        DataOutputStream(BufferedOutputStream(socket.getOutputStream())).use { output ->
                            writeTransferHeader(output, request.items)
                            request.items.forEach { item ->
                                ensureNotStopped()
                                context.contentResolver.openInputStream(Uri.parse(item.sourceUri)).use { input ->
                                    requireNotNull(input) { "无法读取 ${item.displayName}" }
                                    completedBytes += copyWithProgress(
                                        input = input,
                                        output = output,
                                        totalCompletedBeforeFile = completedBytes,
                                        totalBytes = request.totalBytes,
                                        transferId = transferId,
                                        callback = callback,
                                    )
                                }
                            }
                            output.flush()
                        }
                    }
                    activeSocket = null
                }
                callback(SendTransferEvent.Completed(transferId))
            } catch (_: TransferPausedException) {
                callback(SendTransferEvent.Paused(transferId))
            } catch (_: TransferCanceledException) {
                callback(SendTransferEvent.Canceled(transferId))
            } catch (error: Throwable) {
                callback(SendTransferEvent.Failed(transferId, error.message ?: "发送失败"))
            } finally {
                activeSocket = null
                activeTransferId = null
                requestedStop = null
            }
        }
    }

    fun pauseTransfer(transferId: String) {
        if (transferId == activeTransferId) {
            requestedStop = SendTransferStatus.Paused
            runCatching { activeSocket?.close() }
        }
    }

    fun cancelTransfer(transferId: String) {
        if (transferId == activeTransferId) {
            requestedStop = SendTransferStatus.Canceled
            runCatching { activeSocket?.close() }
        }
    }

    fun cancelAll() {
        requestedStop = SendTransferStatus.Canceled
        runCatching { activeSocket?.close() }
    }

    private fun ensureNotStopped() {
        when (requestedStop) {
            SendTransferStatus.Paused -> throw TransferPausedException()
            SendTransferStatus.Canceled -> throw TransferCanceledException()
            else -> Unit
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: DataOutputStream,
        totalCompletedBeforeFile: Long,
        totalBytes: Long,
        transferId: String,
        callback: (SendTransferEvent) -> Unit,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            ensureNotStopped()
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
        return copied
    }
}

private fun writeTransferHeader(
    output: DataOutputStream,
    items: List<SendTransferItem>,
) {
    output.write(PIKO_MAGIC)
    output.writeInt(PIKO_PROTOCOL_VERSION)
    output.writeInt(items.size)
    items.forEach { item ->
        val nameBytes = item.displayName.toByteArray(Charsets.UTF_8)
        output.writeInt(nameBytes.size)
        output.write(nameBytes)
        output.writeInt(item.fileType.ordinal)
        output.writeLong(item.sizeBytes)
    }
}

private fun receiveIncomingTransfer(
    context: Context,
    socket: Socket,
) {
    DataInputStream(BufferedInputStream(socket.getInputStream())).use { input ->
        val files = readTransferHeader(input)
        files.forEach { file ->
            context.contentResolver.openOutputStreamForDownload(file).use { output ->
                requireNotNull(output) { "无法创建接收文件 ${file.displayName}" }
                copyFixedLength(input, output, file.sizeBytes)
            }
        }
    }
}

private fun readTransferHeader(input: DataInputStream): List<SendTransferHeaderFile> {
    val magic = ByteArray(PIKO_MAGIC.size)
    input.readFully(magic)
    require(magic.contentEquals(PIKO_MAGIC)) { "传输协议标识不匹配" }
    require(input.readInt() == PIKO_PROTOCOL_VERSION) { "传输协议版本不支持" }
    val count = input.readInt()
    require(count >= 0) { "文件数量无效" }
    return List(count) {
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
}

private fun ContentResolver.openOutputStreamForDownload(file: SendTransferHeaderFile): OutputStream? {
    val uri = insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, file.fileType.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Piko")
        },
    )
    return uri?.let { openOutputStream(it) }
}

private fun copyFixedLength(
    input: DataInputStream,
    output: OutputStream,
    sizeBytes: Long,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = sizeBytes
    while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        require(read != -1) { "传输内容不完整" }
        output.write(buffer, 0, read)
        remaining -= read
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

private class TransferPausedException : RuntimeException()
private class TransferCanceledException : RuntimeException()
