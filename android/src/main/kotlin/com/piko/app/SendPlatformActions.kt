package com.piko.app

class SendPlatformActions(
    val requestRecentImages: (((SendPermissionState, List<SendImageItem>) -> Unit) -> Unit) = { callback ->
        callback(SendPermissionState.Unavailable, emptyList())
    },
    val pickImages: ((List<SendImageItem>) -> Unit) -> Unit = { callback ->
        callback(emptyList())
    },
    val pickFiles: ((List<SendFileItem>) -> Unit) -> Unit = { callback ->
        callback(emptyList())
    },
    val startLanDiscovery: (((SendLanDiscoveryState, List<SendDevice>) -> Unit) -> Unit) = { callback ->
        callback(SendLanDiscoveryState.Unavailable, emptyList())
    },
    val stopLanDiscovery: () -> Unit = {},
    val startTransfer: (SendTransferRequest, (SendTransferEvent) -> Unit) -> Unit = { request, callback ->
        val transferId = newTransferId()
        callback(SendTransferEvent.Started(transferId, request, request.totalBytes))
        callback(SendTransferEvent.Failed(transferId, "当前平台暂不可发送文件"))
    },
    val pauseTransfer: (String) -> Unit = {},
    val cancelTransfer: (String) -> Unit = {},
    val cancelReceiveTransfer: (String) -> Unit = {},
) {
    companion object {
        val Empty = SendPlatformActions()
    }
}

fun newTransferId(): String = "transfer-${currentTimeMillis()}"

internal fun currentTimeMillis(): Long = System.currentTimeMillis()
