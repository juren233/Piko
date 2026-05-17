package com.piko.app.platform

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.piko.app.data.DeviceIdentityStore
import com.piko.app.data.ReceiveMediaSaveLocation
import com.piko.app.data.TokenStorage
import com.piko.app.domain.ReceiveFileType
import com.piko.app.domain.ReceiveHistoryFile
import com.piko.app.domain.ReceiveHistoryItem
import com.piko.app.domain.ReceiveTransferEvent
import com.piko.app.domain.SendDevice
import com.piko.app.domain.SendDeviceGroup
import com.piko.app.domain.SendFileItem
import com.piko.app.domain.SendFileType
import com.piko.app.domain.SendLanDiscoveryState
import com.piko.app.domain.SendPageState
import com.piko.app.domain.SendMediaItem
import com.piko.app.domain.SendTransferEvent
import com.piko.app.domain.SendTransferHeader
import com.piko.app.domain.SendTransferHeaderFile
import com.piko.app.domain.SendTransferProtocol
import com.piko.app.domain.SendTransferRequest
import com.piko.app.domain.SendTransferStatus
import com.piko.app.domain.SendTransportPath
import com.piko.app.transport.AndroidLocalSendMulticast
import com.piko.app.transport.LocalSendDeviceInfo
import com.piko.app.transport.LocalSendHttpServer
import com.piko.app.transport.LocalSendHttpUploadClient
import com.piko.app.transport.P2PTransferFailure
import com.piko.app.transport.P2PTransferClient
import com.piko.app.transport.SignalingWebSocketClient
import com.piko.app.transport.TransferTransport
import com.piko.app.transport.TransferTransportKind
import com.piko.app.transport.TransferSessionApiClient
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
private val PIKO_MAGIC = byteArrayOf(0x50, 0x49, 0x4B, 0x4F)
private const val PIKO_PROTOCOL_VERSION = 2

@Composable
internal fun rememberAndroidSendPlatformActions(
    currentNickname: DeviceNickname,
    mediaSaveLocation: ReceiveMediaSaveLocation,
    tokenStore: TokenStorage,
    deviceIdentityStore: DeviceIdentityStore,
    signalingClient: SignalingWebSocketClient,
    apiBaseUrl: String,
    onReceiveTransferEvent: (ReceiveTransferEvent) -> Unit,
): SendPlatformActions {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var pickedMediaCallback by remember {
        mutableStateOf<((List<SendMediaItem>) -> Unit)?>(null)
    }
    var pickedFilesCallback by remember {
        mutableStateOf<((List<SendFileItem>) -> Unit)?>(null)
    }
    val lanDiscovery = remember(appContext, currentNickname, mediaSaveLocation) {
        AndroidLanDiscovery(
            context = appContext,
            currentNickname = currentNickname,
            mediaSaveLocation = mediaSaveLocation,
            onReceiveTransferEvent = onReceiveTransferEvent,
        )
    }
    val transferClient = remember(appContext, currentNickname, tokenStore, deviceIdentityStore, signalingClient, apiBaseUrl) {
        AndroidTransferClient(
            context = appContext,
            currentNickname = currentNickname,
            tokenStore = tokenStore,
            deviceIdentityStore = deviceIdentityStore,
            signalingClient = signalingClient,
            apiBaseUrl = apiBaseUrl,
            onReceiveTransferEvent = onReceiveTransferEvent,
        )
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(PickMultipleVisualMedia(30)) { uris ->
        val callback = pickedMediaCallback
        pickedMediaCallback = null
        callback?.invoke(loadPickedMedia(appContext, uris))
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
        pickMedia = { callback ->
            pickedMediaCallback = callback
            imagePickerLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
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
        acceptReceiveTransfer = transferClient::acceptReceiveTransfer,
        cancelReceiveTransfer = { transferId ->
            lanDiscovery.cancelReceiveTransfer(transferId)
            transferClient.cancelReceiveTransfer(transferId)
        },
    )
}

private fun loadPickedMedia(context: Context, uris: List<Uri>): List<SendMediaItem> {
    val resolver = context.contentResolver
    return uris.map { uri ->
        val displayName = queryDisplayName(resolver, uri) ?: "媒体"
        SendMediaItem(
            id = uri.toString(),
            displayName = displayName,
            uri = uri.toString(),
            sizeBytes = querySizeBytes(resolver, uri),
            fileType = resolveFileType(
                mimeType = resolver.getType(uri),
                displayName = displayName,
            ).let { fileType ->
                if (fileType == SendFileType.Video) SendFileType.Video else SendFileType.Image
            },
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
    private val mediaSaveLocation: ReceiveMediaSaveLocation,
    private val onReceiveTransferEvent: (ReceiveTransferEvent) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val devices = linkedMapOf<String, SendDevice>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val serviceInfoCallbacks = mutableSetOf<Any>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var localSendServer: LocalSendHttpServer? = null
    private var localSendMulticast: AndroidLocalSendMulticast? = null
    private var activeReceiveId: String? = null
    private var activeReceiveSocket: Socket? = null
    private var registeredServiceName: String? = null
    private var discoveryCallback: ((SendLanDiscoveryState, List<SendDevice>) -> Unit)? = null
    private var discoveryActive = false

    fun startPresence() {
        runCatching {
            registerLocalService()
        }
    }

    fun start(callback: (SendLanDiscoveryState, List<SendDevice>) -> Unit) {
        stopDiscovery()
        discoveryActive = true
        discoveryCallback = callback
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
                resolveRemoteService(serviceInfo) { resolvedService ->
                    onRemoteServiceResolved(resolvedService, callback)
                }
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
        localSendMulticast?.announce()
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
        discoveryCallback = null
        unregisterServiceInfoCallbacks()
        devices.clear()
    }

    fun stop() {
        stopDiscovery()
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        localSendServer?.stop()
        localSendMulticast?.stop()
        registrationListener = null
        releaseMulticastLock()
        localSendServer = null
        localSendMulticast = null
        registeredServiceName = null
    }

    private fun registerLocalService() {
        if (localSendServer != null && registrationListener != null) {
            return
        }
        acquireMulticastLock()
        val server = localSendServer ?: LocalSendHttpServer(
            context = context,
            deviceInfo = { port -> localSendDeviceInfo(currentNickname, port) },
            mediaSaveLocation = { mediaSaveLocation },
            onReceiveTransferEvent = { event ->
                mainHandler.post { onReceiveTransferEvent(event) }
            },
            onActiveReceiveChanged = { transferId, activeSocket ->
                activeReceiveId = transferId
                activeReceiveSocket = activeSocket
            },
        ).also { localServer ->
            localServer.start()
            localSendServer = localServer
        }
        if (localSendMulticast == null) {
            localSendMulticast = AndroidLocalSendMulticast(
                localInfo = { localSendDeviceInfo(currentNickname, server.port) },
                onDevice = { address, info ->
                    if (info.fingerprint == currentNickname.fingerprint || info.port <= 0) {
                        return@AndroidLocalSendMulticast
                    }
                    val id = "localsend-${info.fingerprint}-${address.hostAddress}-${info.port}"
                    devices[id] = SendDevice(
                        id = id,
                        name = info.alias,
                        group = SendDeviceGroup.Lan,
                        subtitle = "LocalSend",
                        host = address.hostAddress,
                        port = info.port,
                        platformHint = info.deviceType,
                    )
                    discoveryCallback?.let { callback ->
                        post(callback, SendLanDiscoveryState.Found)
                    }
                },
            ).also { it.start() }
        }
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$PIKO_SERVICE_PREFIX${currentNickname.fullName}"
            serviceType = PIKO_SERVICE_TYPE
            port = server.port
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
            server.stop()
            localSendServer = null
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

    fun cancelReceiveTransfer(transferId: String) {
        if (transferId == activeReceiveId) {
            runCatching { activeReceiveSocket?.close() }
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

    private fun resolveRemoteService(
        serviceInfo: NsdServiceInfo,
        onResolved: (NsdServiceInfo) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveRemoteServiceWithCallback(serviceInfo, onResolved)
        } else {
            resolveRemoteServiceLegacy(serviceInfo, onResolved)
        }
    }

    private fun resolveRemoteServiceWithCallback(
        serviceInfo: NsdServiceInfo,
        onResolved: (NsdServiceInfo) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        var callback: NsdManager.ServiceInfoCallback? = null
        fun unregisterCallback() {
            val activeCallback = callback ?: return
            callback = null
            serviceInfoCallbacks.remove(activeCallback)
            runCatching { nsdManager.unregisterServiceInfoCallback(activeCallback) }
        }
        val createdCallback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                unregisterCallback()
            }

            override fun onServiceInfoCallbackUnregistered() {
                serviceInfoCallbacks.remove(this)
                callback = null
            }

            override fun onServiceLost() {
                unregisterCallback()
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                unregisterCallback()
                onResolved(serviceInfo)
            }
        }
        callback = createdCallback
        serviceInfoCallbacks += createdCallback
        runCatching {
            nsdManager.registerServiceInfoCallback(serviceInfo, context.mainExecutor, createdCallback)
        }.onFailure {
            unregisterCallback()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveRemoteServiceLegacy(
        serviceInfo: NsdServiceInfo,
        onResolved: (NsdServiceInfo) -> Unit,
    ) {
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                    onResolved(resolvedService)
                }
            },
        )
    }

    private fun onRemoteServiceResolved(
        resolvedService: NsdServiceInfo,
        callback: (SendLanDiscoveryState, List<SendDevice>) -> Unit,
    ) {
        if (!discoveryActive) {
            return
        }
        val nickname = resolvedService.deviceNickname()
        if (isRegisteredLocalService(resolvedService.serviceName) ||
            nickname.fingerprint == currentNickname.fingerprint
        ) {
            return
        }
        val hostAddress = resolvedService.primaryHostAddress()
        val id = "${resolvedService.serviceName}-$hostAddress-${resolvedService.port}"
        devices[id] = SendDevice(
            id = id,
            name = nickname.title,
            group = SendDeviceGroup.Lan,
            subtitle = nickname.code,
            host = hostAddress,
            port = resolvedService.port,
            platformHint = "android",
        )
        post(callback, SendLanDiscoveryState.Found)
    }

    private fun unregisterServiceInfoCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfoCallbacks.clear()
            return
        }
        val activeCallbacks = serviceInfoCallbacks.toList()
        serviceInfoCallbacks.clear()
        activeCallbacks.forEach { callback ->
            runCatching { nsdManager.unregisterServiceInfoCallback(callback as NsdManager.ServiceInfoCallback) }
        }
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

private fun NsdServiceInfo.primaryHostAddress(): String? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        hostAddresses.firstOrNull()?.hostAddress
    } else {
        @Suppress("DEPRECATION")
        host?.hostAddress
    }
}

private fun localSendDeviceInfo(
    currentNickname: DeviceNickname,
    port: Int,
): LocalSendDeviceInfo {
    return LocalSendDeviceInfo(
        alias = currentNickname.title,
        version = "2.0",
        deviceModel = Build.MODEL ?: "Android",
        deviceType = "mobile",
        fingerprint = currentNickname.fingerprint,
        port = port,
        protocol = "http",
        download = false,
    )
}

private class AndroidTransferClient(
    private val context: Context,
    currentNickname: DeviceNickname,
    tokenStore: TokenStorage,
    deviceIdentityStore: DeviceIdentityStore,
    signalingClient: SignalingWebSocketClient,
    apiBaseUrl: String,
    onReceiveTransferEvent: (ReceiveTransferEvent) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val localSendClient = LocalSendHttpUploadClient(context) {
        localSendDeviceInfo(currentNickname, port = 0)
    }
    private val p2pTransferClient = P2PTransferClient(
        context = context,
        tokenStore = tokenStore,
        identityStore = deviceIdentityStore,
        sessionsApi = TransferSessionApiClient(apiBaseUrl),
        signalingClient = signalingClient,
        senderName = currentNickname.fullName,
        onReceiveTransferEvent = { event -> mainHandler.post { onReceiveTransferEvent(event) } },
    )

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
        val emit: (SendTransferEvent) -> Unit = { event ->
            mainHandler.post { callback(event) }
        }
        activeTransferId = transferId
        requestedStop = null
        emit(SendTransferEvent.Started(transferId, request, request.totalBytes))
        thread(
            name = "PikoAndroidSendTransfer",
            isDaemon = true,
        ) {
            var completedBytes = 0L
            var activeTarget: SendDevice? = null
            try {
                request.targets.forEach { target ->
                    activeTarget = target
                    ensureNotStopped()
                    completedBytes += when (target.transportPath) {
                        SendTransportPath.Lan -> runCatching {
                            localSendClient.upload(
                                target = target,
                                items = request.items,
                                totalCompletedBeforeTarget = completedBytes,
                                totalBytes = request.totalBytes,
                                transferId = transferId,
                                callback = callback,
                                ensureActive = ::ensureNotStopped,
                            )
                        }.getOrElse { error ->
                            if (error is TransferPausedException || error is TransferCanceledException) {
                                throw error
                            }
                            sendLegacyTransfer(
                                target = target,
                                request = request,
                                totalCompletedBeforeTarget = completedBytes,
                                totalBytes = request.totalBytes,
                                transferId = transferId,
                                callback = emit,
                            )
                        }
                        SendTransportPath.P2P -> {
                            p2pTransferClient.send(
                                target = target,
                                transferId = transferId,
                                items = request.items,
                                totalCompletedBeforeTarget = completedBytes,
                                totalBytes = request.totalBytes,
                                callback = emit,
                                ensureActive = ::ensureNotStopped,
                            )
                        }
                    }
                }
                emit(SendTransferEvent.Completed(transferId))
            } catch (_: TransferPausedException) {
                emit(SendTransferEvent.Paused(transferId))
            } catch (_: TransferCanceledException) {
                emit(SendTransferEvent.Canceled(transferId))
            } catch (error: Throwable) {
                val target = activeTarget
                val message = if (target?.transportPath == SendTransportPath.P2P) {
                    p2pFailureMessage(target = target, transferId = transferId, cause = error)
                } else {
                    error.message ?: "发送失败"
                }
                emit(SendTransferEvent.Failed(transferId, message))
            } finally {
                activeSocket = null
                activeTransferId = null
                requestedStop = null
            }
        }
    }

    private fun p2pFailureMessage(target: SendDevice, transferId: String, cause: Throwable): String {
        val p2pFailure = cause as? P2PTransferFailure
        val sessionId = p2pFailure?.sessionId?.ifBlank { null } ?: "未创建/未知"
        val stage = p2pFailure?.stage ?: "unknown"
        val originalReason = p2pFailure?.originalReason ?: (cause.message ?: cause::class.java.simpleName)
        val diagnostic = p2pFailure?.diagnostic
        val failureReason = diagnostic?.failureReason
        val userId = target.receiverUserId?.ifBlank { null } ?: "未知用户"
        val deviceId = target.receiverDeviceId?.ifBlank { null } ?: "未知设备"
        val receiverPlatform = target.platformHint?.ifBlank { null } ?: "未知"
        val onlineSnapshot = if (target.online) "在线" else "离线"
        return buildList {
            if (failureReason != null) {
                add("失败原因：${failureReason.title}")
                add("建议：${failureReason.suggestion}")
                add("")
            }
            add("目标：${target.name}")
            add("用户：$userId")
            add("设备：$deviceId")
            add("传输：$transferId")
            add("会话：$sessionId")
            add("路径：P2P")
            add("发送端：Android")
            add("接收端：$receiverPlatform")
            add("在线快照：$onlineSnapshot")
            add("阶段：$stage")
            add("原始原因：$originalReason")
            add("direct_attempt_plan：${diagnostic?.directAttemptPlan ?: "unknown"}")
            add("direct_endpoint_count：${diagnostic?.directEndpointCount ?: 0}")
            add("direct_endpoints：${diagnostic?.directEndpoints ?: "none"}")
            add("direct_selected：${diagnostic?.directSelected ?: "none"}")
            add("direct_attempt_result：${diagnostic?.directAttemptResult ?: "not_attempted"}")
            add("direct_last_error：${diagnostic?.directLastError ?: "none"}")
            add("offer_sent：${diagnostic?.offerSent ?: false}")
            add("answer_received：${diagnostic?.answerReceived ?: false}")
            add("local_ice_count：${diagnostic?.localIceCount ?: 0}")
            add("remote_ice_count：${diagnostic?.remoteIceCount ?: 0}")
            add("ice_server_urls：${diagnostic?.iceServerUrls ?: "unknown"}")
            add("local_candidate_types：${diagnostic?.localCandidateTypes ?: "none"}")
            add("remote_candidate_types：${diagnostic?.remoteCandidateTypes ?: "none"}")
            add("local_candidate_details：${diagnostic?.localCandidateDetails ?: "none"}")
            add("remote_candidate_details：${diagnostic?.remoteCandidateDetails ?: "none"}")
            add("ice_connection_state：${diagnostic?.iceConnectionState ?: "unknown"}")
            add("peer_connection_state：${diagnostic?.peerConnectionState ?: "unknown"}")
            add("ice_gathering_state：${diagnostic?.iceGatheringState ?: "unknown"}")
            add("signaling_state：${diagnostic?.signalingState ?: "unknown"}")
            add("data_channel_state：${diagnostic?.dataChannelState ?: "unknown"}")
            add("ice_candidate_errors：${diagnostic?.iceCandidateErrors ?: "none"}")
            add("selected_candidate_pair：${diagnostic?.selectedCandidatePair ?: "none"}")
            add("ice_candidate_pair_stats：${diagnostic?.iceCandidatePairStats ?: "none"}")
            add("stun_error_rate：${"%.2f".format(diagnostic?.stunErrorRate ?: 0.0)}")
            add("gathering_incomplete：${diagnostic?.gatheringIncomplete ?: false}")
            add("symmetric_nat_suspect：${diagnostic?.symmetricNatSuspect ?: false}")
            add("remote_only_mdns：${diagnostic?.remoteOnlyMdns ?: false}")
            add("failure_reason_code：${failureReason?.name ?: "unknown"}")
        }.joinToString("\n")
    }

    private fun sendLegacyTransfer(
        target: SendDevice,
        request: SendTransferRequest,
        totalCompletedBeforeTarget: Long,
        totalBytes: Long,
        transferId: String,
        callback: (SendTransferEvent) -> Unit,
    ): Long {
        val host = requireNotNull(target.host) { "目标设备缺少地址" }
        val port = requireNotNull(target.port) { "目标设备缺少端口" }
        var targetCompletedBytes = 0L
        Socket().use { socket ->
            activeSocket = socket
            socket.connect(InetSocketAddress(host, port), 5_000)
            DataOutputStream(BufferedOutputStream(socket.getOutputStream())).use { output ->
                writeTransferHeader(output, request)
                request.items.forEach { item ->
                    ensureNotStopped()
                    context.contentResolver.openInputStream(Uri.parse(item.sourceUri)).use { input ->
                        requireNotNull(input) { "无法读取 ${item.displayName}" }
                        val copied = copyWithProgress(
                            input = input,
                            output = output,
                            totalCompletedBeforeFile = totalCompletedBeforeTarget + targetCompletedBytes,
                            totalBytes = totalBytes,
                            transferId = transferId,
                            callback = callback,
                        )
                        targetCompletedBytes += copied
                    }
                }
                output.flush()
            }
        }
        activeSocket = null
        return targetCompletedBytes
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

    fun acceptReceiveTransfer(transferId: String) {
        p2pTransferClient.acceptReceiveTransfer(transferId)
    }

    fun cancelReceiveTransfer(transferId: String) {
        p2pTransferClient.cancelReceiveTransfer(transferId)
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
    request: SendTransferRequest,
) {
    output.write(PIKO_MAGIC)
    output.writeInt(PIKO_PROTOCOL_VERSION)
    val senderNameBytes = request.senderName.toByteArray(Charsets.UTF_8)
    output.writeInt(senderNameBytes.size)
    output.write(senderNameBytes)
    output.writeInt(request.items.size)
    request.items.forEach { item ->
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
    onReceiveTransferEvent: (ReceiveTransferEvent) -> Unit,
    onActiveReceiveChanged: (String?, Socket?) -> Unit,
) {
    val transferId = newTransferId()
    onActiveReceiveChanged(transferId, socket)
    try {
        DataInputStream(BufferedInputStream(socket.getInputStream())).use { input ->
            val header = readTransferHeader(input)
            val totalBytes = header.files.sumOf { file -> file.sizeBytes }
            val pendingFiles = header.files.map { file ->
                ReceiveHistoryFile(
                    displayName = file.displayName,
                    fileType = file.fileType.toReceiveFileType(),
                    sizeBytes = file.sizeBytes,
                    thumbnailBytes = null,
                )
            }
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
                val uri = context.contentResolver.createDownloadUri(file)
                context.contentResolver.openOutputStream(uri).use { output ->
                    requireNotNull(output) { "无法创建接收文件 ${file.displayName}" }
                    completedBytes += copyFixedLength(
                        input = input,
                        output = output,
                        sizeBytes = file.sizeBytes,
                        totalCompletedBeforeFile = completedBytes,
                        totalBytes = totalBytes,
                        transferId = transferId,
                        onReceiveTransferEvent = onReceiveTransferEvent,
                    )
                }
                receivedFiles += ReceiveHistoryFile(
                    displayName = file.displayName,
                    fileType = file.fileType.toReceiveFileType(),
                    sizeBytes = file.sizeBytes,
                    thumbnailBytes = if (file.fileType.isMediaPreview) {
                        loadThumbnailBytes(context.contentResolver, uri)
                    } else {
                        null
                    },
                    savedUri = uri.toString(),
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
    } catch (_: java.net.SocketException) {
        onReceiveTransferEvent(ReceiveTransferEvent.Canceled(transferId))
    } catch (error: Throwable) {
        onReceiveTransferEvent(ReceiveTransferEvent.Failed(transferId, error.message ?: "接收失败"))
    } finally {
        onActiveReceiveChanged(null, null)
    }
}

private fun readTransferHeader(input: DataInputStream): SendTransferHeader {
    val magic = ByteArray(PIKO_MAGIC.size)
    input.readFully(magic)
    require(magic.contentEquals(PIKO_MAGIC)) { "传输协议标识不匹配" }
    val version = input.readInt()
    require(version in 1..PIKO_PROTOCOL_VERSION) { "传输协议版本不支持" }
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

private fun ContentResolver.createDownloadUri(file: SendTransferHeaderFile): Uri {
    return requireNotNull(
        insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, file.fileType.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Piko")
            }
        ),
    ) { "无法创建接收文件 ${file.displayName}" }
}

private fun copyFixedLength(
    input: DataInputStream,
    output: OutputStream,
    sizeBytes: Long,
    totalCompletedBeforeFile: Long,
    totalBytes: Long,
    transferId: String,
    onReceiveTransferEvent: (ReceiveTransferEvent) -> Unit,
): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = sizeBytes
    var copied = 0L
    while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        require(read != -1) { "传输内容不完整" }
        output.write(buffer, 0, read)
        copied += read
        remaining -= read
        onReceiveTransferEvent(
            ReceiveTransferEvent.Progress(
                transferId = transferId,
                completedBytes = (totalCompletedBeforeFile + copied).coerceAtMost(totalBytes),
                totalBytes = totalBytes,
            ),
        )
    }
    return copied
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

private val SendFileType.isMediaPreview: Boolean
    get() = this == SendFileType.Image || this == SendFileType.Video

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

private class TransferPausedException : RuntimeException()
private class TransferCanceledException : RuntimeException()
