package com.piko.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSNumber
import platform.Foundation.NSSortDescriptor
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAuthorizationStatus
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusRestricted
import platform.Photos.PHFetchOptions
import platform.Photos.PHImageContentModeAspectFill
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeFastFormat
import platform.Photos.PHImageRequestOptionsResizeModeFast
import platform.Photos.PHPhotoLibrary
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIDevice
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIViewController
import platform.UIKit.UIApplication
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_get_global_queue
import platform.posix.AF_INET
import platform.posix.INADDR_ANY
import platform.posix.SOCK_STREAM
import platform.posix.accept
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memcpy
import platform.posix.recv
import platform.posix.send
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socklen_tVar
import platform.posix.socket
import kotlin.concurrent.Volatile

private const val PIKO_SERVICE_TYPE = "_piko-share._tcp."
private const val RECENT_IMAGE_LIMIT = 24
private const val PICKED_IMAGE_LIMIT = 30

@Composable
fun rememberIosSendPlatformActions(): SendPlatformActions {
    val coordinator = remember {
        IosSendCoordinator(currentDeviceName = UIDevice.currentDevice.name)
    }

    DisposableEffect(coordinator) {
        onDispose { coordinator.stopLanDiscovery() }
    }

    return remember(coordinator) {
        SendPlatformActions(
            requestRecentImages = coordinator::requestRecentImages,
            pickImages = coordinator::pickImages,
            pickFiles = coordinator::pickFiles,
            startLanDiscovery = coordinator::startLanDiscovery,
            stopLanDiscovery = coordinator::stopLanDiscovery,
            startTransfer = coordinator::startTransfer,
            pauseTransfer = coordinator::pauseTransfer,
            cancelTransfer = coordinator::cancelTransfer,
        )
    }
}

private class IosSendCoordinator(
    private val currentDeviceName: String,
) {
    private val lanDiscovery = IosLanDiscovery(currentDeviceName)
    private val transferClient = IosTransferClient()
    private var imagePickerDelegate: IosImagePickerDelegate? = null
    private var filePickerDelegate: IosDocumentPickerDelegate? = null

    fun requestRecentImages(callback: (SendPermissionState, List<SendImageItem>) -> Unit) {
        val status = PHPhotoLibrary.authorizationStatus()
        if (status.isPhotoGranted()) {
            callback(SendPermissionState.Granted, emptyList())
            loadRecentImages(callback)
            return
        }

        if (status == PHAuthorizationStatusDenied || status == PHAuthorizationStatusRestricted) {
            callback(SendPermissionState.Denied, emptyList())
            return
        }

        callback(SendPermissionState.Requesting, emptyList())
        PHPhotoLibrary.requestAuthorization { nextStatus ->
            postToMain {
                if (nextStatus.isPhotoGranted()) {
                    loadRecentImages(callback)
                } else {
                    callback(SendPermissionState.Denied, emptyList())
                }
            }
        }
    }

    fun pickImages(callback: (List<SendImageItem>) -> Unit) {
        val presenter = topViewController()
        if (presenter == null) {
            callback(emptyList())
            return
        }

        val configuration = PHPickerConfiguration().apply {
            selectionLimit = PICKED_IMAGE_LIMIT.toLong()
            filter = PHPickerFilter.imagesFilter()
        }
        val picker = PHPickerViewController(configuration)
        val delegate = IosImagePickerDelegate(
            onComplete = { images ->
                imagePickerDelegate = null
                callback(images)
            },
        )
        imagePickerDelegate = delegate
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    fun pickFiles(callback: (List<SendFileItem>) -> Unit) {
        val presenter = topViewController()
        if (presenter == null) {
            callback(emptyList())
            return
        }

        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypeItem),
            asCopy = true,
        ).apply {
            allowsMultipleSelection = true
        }
        val delegate = IosDocumentPickerDelegate(
            onComplete = { files ->
                filePickerDelegate = null
                callback(files)
            },
        )
        filePickerDelegate = delegate
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    fun startLanDiscovery(callback: (SendLanDiscoveryState, List<SendDevice>) -> Unit) {
        lanDiscovery.start(callback)
    }

    fun stopLanDiscovery() {
        lanDiscovery.stop()
    }

    fun startTransfer(
        request: SendTransferRequest,
        callback: (SendTransferEvent) -> Unit,
    ) {
        transferClient.startTransfer(request, callback)
    }

    fun pauseTransfer(transferId: String) {
        transferClient.pauseTransfer(transferId)
    }

    fun cancelTransfer(transferId: String) {
        transferClient.cancelTransfer(transferId)
    }

    private fun loadRecentImages(callback: (SendPermissionState, List<SendImageItem>) -> Unit) {
        val options = PHFetchOptions().apply {
            sortDescriptors = listOf(NSSortDescriptor.sortDescriptorWithKey("creationDate", ascending = false))
            fetchLimit = RECENT_IMAGE_LIMIT.toULong()
        }
        val assets = PHAsset.fetchAssetsWithMediaType(PHAssetMediaTypeImage, options)
        val count = minOf(assets.count.toInt(), RECENT_IMAGE_LIMIT)
        if (count == 0) {
            callback(SendPermissionState.Granted, emptyList())
            return
        }

        val images = MutableList<SendImageItem?>(count) { null }
        var remaining = count
        repeat(count) { index ->
            val asset = assets.objectAtIndex(index.toULong()) as PHAsset
            requestThumbnail(asset) { bytes ->
                images[index] = SendImageItem(
                    id = asset.localIdentifier,
                    displayName = "图片 ${index + 1}",
                    uri = asset.localIdentifier,
                    sizeBytes = bytes?.size?.toLong() ?: 0L,
                    thumbnailBytes = bytes,
                )
                remaining -= 1
                if (remaining == 0) {
                    callback(SendPermissionState.Granted, images.filterNotNull())
                }
            }
        }
    }
}

private class IosImagePickerDelegate(
    private val onComplete: (List<SendImageItem>) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        if (didFinishPicking.isEmpty()) {
            onComplete(emptyList())
            return
        }

        val images = mutableListOf<SendImageItem>()
        var remaining = didFinishPicking.size
        didFinishPicking.forEachIndexed { index, item ->
            val result = item as? PHPickerResult
            val provider = result?.itemProvider
            if (provider == null) {
                remaining -= 1
                if (remaining == 0) {
                    onComplete(images)
                }
                return@forEachIndexed
            }

            provider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
                if (data != null) {
                    images += SendImageItem(
                        id = result.assetIdentifier ?: "ios-picked-$index",
                        displayName = "图片 ${index + 1}",
                        uri = result.assetIdentifier ?: "ios-picked-$index",
                        sizeBytes = data.length.toLong(),
                        thumbnailBytes = data.toByteArray(),
                    )
                }
                remaining -= 1
                if (remaining == 0) {
                    postToMain { onComplete(images) }
                }
            }
        }
    }
}

private class IosDocumentPickerDelegate(
    private val onComplete: (List<SendFileItem>) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onComplete(
            didPickDocumentsAtURLs.mapNotNull { url ->
                (url as? NSURL)?.toSendFileItem()
            },
        )
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onComplete(emptyList())
    }
}

private class IosLanDiscovery(
    private val currentDeviceName: String,
) : NSObject(),
    NSNetServiceBrowserDelegateProtocol,
    NSNetServiceDelegateProtocol {
    private val devices = linkedMapOf<String, SendDevice>()
    private val resolvingServices = linkedMapOf<String, NSNetService>()
    private val browser = NSNetServiceBrowser()
    private var service: NSNetService? = null
    private var transferServer: IosTransferServer? = null
    private var callback: ((SendLanDiscoveryState, List<SendDevice>) -> Unit)? = null
    private var active = false

    fun start(callback: (SendLanDiscoveryState, List<SendDevice>) -> Unit) {
        stop()
        this.callback = callback
        active = true
        devices.clear()
        post(SendLanDiscoveryState.Searching)

        val server = IosTransferServer().also { it.start() }
        transferServer = server
        service = NSNetService(
            domain = "local.",
            type = PIKO_SERVICE_TYPE,
            name = "Piko-$currentDeviceName",
            port = server.port,
        ).apply {
            delegate = this@IosLanDiscovery
            publish()
        }

        browser.delegate = this
        browser.searchForServicesOfType(PIKO_SERVICE_TYPE, inDomain = "local.")
    }

    fun stop() {
        active = false
        browser.stop()
        service?.stop()
        transferServer?.stop()
        service = null
        transferServer = null
        callback = null
        devices.clear()
        resolvingServices.clear()
    }

    override fun netServiceBrowserWillSearch(browser: NSNetServiceBrowser) = Unit

    override fun netServiceBrowserDidStopSearch(browser: NSNetServiceBrowser) = Unit

    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didNotSearch: Map<Any?, *>,
    ) {
        post(SendLanDiscoveryState.Failed)
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didFindService: NSNetService,
        moreComing: Boolean,
    ) {
        if (didFindService.name == service?.name || didFindService.name.startsWith("Piko-$currentDeviceName")) {
            return
        }
        resolvingServices[didFindService.name] = didFindService
        didFindService.delegate = this
        didFindService.resolveWithTimeout(5.0)
        if (!moreComing) {
            post(if (devices.isEmpty()) SendLanDiscoveryState.Empty else SendLanDiscoveryState.Found)
        }
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didRemoveService: NSNetService,
        moreComing: Boolean,
    ) {
        devices.entries.removeAll { (_, device) -> device.name == didRemoveService.name.removePrefix("Piko-") }
        resolvingServices.remove(didRemoveService.name)
        if (!moreComing) {
            post(if (devices.isEmpty()) SendLanDiscoveryState.Empty else SendLanDiscoveryState.Found)
        }
    }

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        val id = "${sender.name}-${sender.hostName ?: "local"}-${sender.port}"
        devices[id] = SendDevice(
            id = id,
            name = sender.name.removePrefix("Piko-"),
            group = SendDeviceGroup.Lan,
            subtitle = sender.hostName,
            host = sender.hostName,
            port = sender.port.toInt(),
            platformHint = "ios",
        )
        resolvingServices.remove(sender.name)
        post(SendLanDiscoveryState.Found)
    }

    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
        resolvingServices.remove(sender.name)
    }

    private fun post(state: SendLanDiscoveryState) {
        val nextCallback = callback ?: return
        postToMain {
            if (active) {
                nextCallback(state, devices.values.toList())
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosTransferServer {
    private var socketFd: Int = -1
    var port: Int = 0
        private set

    fun start() {
        if (socketFd >= 0) {
            return
        }
        memScoped {
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            require(fd >= 0) { "无法启动 iOS 接收服务" }
            val address = alloc<sockaddr_in>()
            address.sin_len = sizeOf<sockaddr_in>().convert()
            address.sin_family = AF_INET.convert()
            address.sin_port = 0u
            address.sin_addr.s_addr = INADDR_ANY
            require(bind(fd, address.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0) {
                "无法绑定 iOS 接收端口"
            }
            require(listen(fd, 8) == 0) { "无法监听 iOS 接收端口" }
            val length = alloc<socklen_tVar>()
            length.value = sizeOf<sockaddr_in>().convert()
            require(getsockname(fd, address.ptr.reinterpret<sockaddr>(), length.ptr) == 0) {
                "无法读取 iOS 接收端口"
            }
            socketFd = fd
            port = networkShortToHost(address.sin_port)
        }
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            acceptLoop()
        }
    }

    fun stop() {
        val fd = socketFd
        socketFd = -1
        if (fd >= 0) {
            close(fd)
        }
    }

    private fun acceptLoop() {
        while (socketFd >= 0) {
            val client = accept(socketFd, null, null)
            if (client < 0) {
                break
            }
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                runCatching {
                    receiveIncomingTransfer(client)
                }
                close(client)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosTransferClient {
    @Volatile
    private var activeTransferId: String? = null

    @Volatile
    private var activeSocket: Int = -1

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
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            var completedBytes = 0L
            try {
                request.targets.forEach { target ->
                    ensureNotStopped()
                    val host = requireNotNull(target.host) { "目标设备缺少地址" }
                    val port = requireNotNull(target.port) { "目标设备缺少端口" }
                    val fd = connectSocket(host, port)
                    activeSocket = fd
                    writeTransferHeader(fd, request.items)
                    request.items.forEach { item ->
                        ensureNotStopped()
                        completedBytes += writeTransferItem(
                            fd = fd,
                            item = item,
                            totalCompletedBeforeFile = completedBytes,
                            totalBytes = request.totalBytes,
                            transferId = transferId,
                            callback = callback,
                        )
                    }
                    close(fd)
                    activeSocket = -1
                }
                postToMain { callback(SendTransferEvent.Completed(transferId)) }
            } catch (_: TransferPausedException) {
                postToMain { callback(SendTransferEvent.Paused(transferId)) }
            } catch (_: TransferCanceledException) {
                postToMain { callback(SendTransferEvent.Canceled(transferId)) }
            } catch (error: Throwable) {
                postToMain { callback(SendTransferEvent.Failed(transferId, error.message ?: "发送失败")) }
            } finally {
                if (activeSocket >= 0) {
                    close(activeSocket)
                }
                activeSocket = -1
                activeTransferId = null
                requestedStop = null
            }
        }
    }

    fun pauseTransfer(transferId: String) {
        if (transferId == activeTransferId) {
            requestedStop = SendTransferStatus.Paused
            if (activeSocket >= 0) {
                close(activeSocket)
            }
        }
    }

    fun cancelTransfer(transferId: String) {
        if (transferId == activeTransferId) {
            requestedStop = SendTransferStatus.Canceled
            if (activeSocket >= 0) {
                close(activeSocket)
            }
        }
    }

    private fun ensureNotStopped() {
        when (requestedStop) {
            SendTransferStatus.Paused -> throw TransferPausedException()
            SendTransferStatus.Canceled -> throw TransferCanceledException()
            else -> Unit
        }
    }

    private fun writeTransferItem(
        fd: Int,
        item: SendTransferItem,
        totalCompletedBeforeFile: Long,
        totalBytes: Long,
        transferId: String,
        callback: (SendTransferEvent) -> Unit,
    ): Long {
        val bytes = item.inlineBytes ?: readFileBytes(item.sourceUri)
        var copied = 0L
        var offset = 0
        while (offset < bytes.size) {
            ensureNotStopped()
            val size = minOf(8_192, bytes.size - offset)
            sendAll(fd, bytes, offset, size)
            offset += size
            copied += size
            val completed = (totalCompletedBeforeFile + copied).coerceAtMost(totalBytes)
            postToMain {
                callback(SendTransferEvent.Progress(transferId, completed, totalBytes))
            }
        }
        return copied
    }
}

private fun PHAuthorizationStatus.isPhotoGranted(): Boolean {
    return this == PHAuthorizationStatusAuthorized || this == PHAuthorizationStatusLimited
}

@OptIn(ExperimentalForeignApi::class)
private fun requestThumbnail(asset: PHAsset, onComplete: (ByteArray?) -> Unit) {
    val options = PHImageRequestOptions().apply {
        deliveryMode = PHImageRequestOptionsDeliveryModeFastFormat
        resizeMode = PHImageRequestOptionsResizeModeFast
        networkAccessAllowed = true
    }
    PHImageManager.defaultManager().requestImageForAsset(
        asset = asset,
        targetSize = CGSizeMake(240.0, 240.0),
        contentMode = PHImageContentModeAspectFill,
        options = options,
    ) { image, _ ->
        onComplete(image?.jpegBytes())
    }
}

private fun NSURL.toSendFileItem(): SendFileItem {
    val displayName = lastPathComponent ?: "未命名文件"
    return SendFileItem(
        id = absoluteString ?: displayName,
        displayName = displayName,
        sizeBytes = fileSizeBytes(),
        fileType = resolveFileType(displayName),
        sourceUri = absoluteString ?: displayName,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun connectSocket(host: String, port: Int): Int = memScoped {
    val hints = alloc<addrinfo>()
    hints.ai_family = AF_INET
    hints.ai_socktype = SOCK_STREAM
    val result = alloc<kotlinx.cinterop.CPointerVar<addrinfo>>()
    val error = getaddrinfo(host, port.toString(), hints.ptr, result.ptr)
    require(error == 0 && result.value != null) { "无法解析目标设备地址" }
    var current = result.value
    var connectedFd = -1
    while (current != null && connectedFd < 0) {
        val info = current.pointed
        val fd = socket(info.ai_family, info.ai_socktype, info.ai_protocol)
        if (fd >= 0 && connect(fd, info.ai_addr, info.ai_addrlen) == 0) {
            connectedFd = fd
        } else if (fd >= 0) {
            close(fd)
        }
        current = info.ai_next
    }
    freeaddrinfo(result.value)
    require(connectedFd >= 0) { "无法连接目标设备" }
    connectedFd
}

private fun writeTransferHeader(
    fd: Int,
    items: List<SendTransferItem>,
) {
    sendAll(fd, PIKO_MAGIC, 0, PIKO_MAGIC.size)
    sendInt(fd, PIKO_PROTOCOL_VERSION)
    sendInt(fd, items.size)
    items.forEach { item ->
        val nameBytes = item.displayName.encodeToByteArray()
        sendInt(fd, nameBytes.size)
        sendAll(fd, nameBytes, 0, nameBytes.size)
        sendInt(fd, item.fileType.ordinal)
        sendLong(fd, item.sizeBytes)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun sendAll(
    fd: Int,
    bytes: ByteArray,
    offset: Int,
    length: Int,
) {
    var sent = 0
    bytes.usePinned { pinned ->
        while (sent < length) {
            val written = send(fd, pinned.addressOf(offset + sent), (length - sent).convert(), 0)
            require(written > 0) { "发送连接已断开" }
            sent += written.toInt()
        }
    }
}

private fun sendInt(fd: Int, value: Int) {
    sendAll(
        fd,
        byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        ),
        0,
        4,
    )
}

private fun sendLong(fd: Int, value: Long) {
    val bytes = ByteArray(8)
    for (index in bytes.indices) {
        val shift = 56 - index * 8
        bytes[index] = ((value ushr shift) and 0xFF).toByte()
    }
    sendAll(fd, bytes, 0, bytes.size)
}

private fun receiveIncomingTransfer(fd: Int) {
    val files = readTransferHeader(fd)
    files.forEach { file ->
        val bytes = recvFully(fd, file.sizeBytes)
        val path = pikoInboxPath(file.displayName)
        writeFileBytes(path, bytes)
    }
}

private fun readTransferHeader(fd: Int): List<SendTransferHeaderFile> {
    val magic = recvFully(fd, PIKO_MAGIC.size.toLong())
    require(magic.contentEquals(PIKO_MAGIC)) { "传输协议标识不匹配" }
    require(recvInt(fd) == PIKO_PROTOCOL_VERSION) { "传输协议版本不支持" }
    val count = recvInt(fd)
    require(count >= 0) { "文件数量无效" }
    return List(count) {
        val nameSize = recvInt(fd)
        require(nameSize >= 0) { "文件名长度无效" }
        val name = recvFully(fd, nameSize.toLong()).decodeToString()
        val type = SendFileType.entries.getOrElse(recvInt(fd)) { SendFileType.Other }
        val size = recvLong(fd)
        require(size >= 0L) { "文件大小无效" }
        SendTransferHeaderFile(name, type, size)
    }
}

private fun recvInt(fd: Int): Int {
    val bytes = recvFully(fd, 4)
    return ((bytes[0].toInt() and 0xFF) shl 24) or
        ((bytes[1].toInt() and 0xFF) shl 16) or
        ((bytes[2].toInt() and 0xFF) shl 8) or
        (bytes[3].toInt() and 0xFF)
}

private fun recvLong(fd: Int): Long {
    val bytes = recvFully(fd, 8)
    var result = 0L
    bytes.forEach { byte ->
        result = (result shl 8) or (byte.toLong() and 0xFF)
    }
    return result
}

@OptIn(ExperimentalForeignApi::class)
private fun recvFully(fd: Int, sizeBytes: Long): ByteArray {
    require(sizeBytes <= Int.MAX_VALUE) { "单文件过大" }
    val result = ByteArray(sizeBytes.toInt())
    var offset = 0
    result.usePinned { pinned ->
        while (offset < result.size) {
            val read = recv(fd, pinned.addressOf(offset), (result.size - offset).convert(), 0)
            require(read > 0) { "接收连接已断开" }
            offset += read.toInt()
        }
    }
    return result
}

@OptIn(ExperimentalForeignApi::class)
private fun readFileBytes(sourceUri: String): ByteArray {
    val path = NSURL.URLWithString(sourceUri)?.path ?: sourceUri
    val file = fopen(path, "rb") ?: return ByteArray(0)
    try {
        fseek(file, 0, SEEK_END)
        val length = ftell(file)
        if (length <= 0) {
            return ByteArray(0)
        }
        fseek(file, 0, SEEK_SET)
        val result = ByteArray(length.toInt())
        result.usePinned { pinned ->
            val read = fread(pinned.addressOf(0), 1.convert(), result.size.convert(), file).toInt()
            return if (read == result.size) result else result.copyOf(read.coerceAtLeast(0))
        }
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun pikoInboxPath(displayName: String): String {
    val manager = NSFileManager.defaultManager
    val documents = manager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
        .firstOrNull() as? NSURL
    val directory = "${documents?.path ?: ""}/Piko"
    manager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
    return "$directory/${displayName.sanitizedFileName()}"
}

private fun String.sanitizedFileName(): String {
    return replace("/", "_").replace(":", "_").ifBlank { "未命名文件" }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeFileBytes(path: String, bytes: ByteArray) {
    val file = fopen(path, "wb")
    require(file != null) { "无法创建接收文件" }
    try {
        if (bytes.isEmpty()) {
            return
        }
        bytes.usePinned { pinned ->
            val written = fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
            require(written.toInt() == bytes.size) { "接收文件写入不完整" }
        }
    } finally {
        fclose(file)
    }
}

private fun networkShortToHost(value: UShort): Int {
    return (((value.toInt() and 0xFF) shl 8) or ((value.toInt() ushr 8) and 0xFF))
}

private val PIKO_MAGIC = byteArrayOf(0x50, 0x49, 0x4B, 0x4F)
private const val PIKO_PROTOCOL_VERSION = 1

private class TransferPausedException : RuntimeException()
private class TransferCanceledException : RuntimeException()

private fun resolveFileType(displayName: String): SendFileType {
    val lowerName = displayName.lowercase()
    return when {
        lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".heic") -> SendFileType.Image
        lowerName.endsWith(".mp4") || lowerName.endsWith(".mov") -> SendFileType.Video
        lowerName.endsWith(".zip") || lowerName.endsWith(".rar") || lowerName.endsWith(".7z") -> SendFileType.Archive
        lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx") || lowerName.endsWith(".csv") -> SendFileType.Spreadsheet
        lowerName.endsWith(".pdf") || lowerName.endsWith(".doc") || lowerName.endsWith(".docx") -> SendFileType.Document
        else -> SendFileType.Other
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSURL.fileSizeBytes(): Long {
    val localPath = path ?: return 0L
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(localPath, error = null)
    return (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
}

private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}

private fun postToMain(block: () -> Unit) {
    dispatch_async(dispatch_get_main_queue()) {
        block()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun UIImage.jpegBytes(): ByteArray? {
    return UIImageJPEGRepresentation(this, 0.82)?.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = length.toInt()
    val result = ByteArray(length)
    if (length == 0) {
        return result
    }
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, this.length)
    }
    return result
}
