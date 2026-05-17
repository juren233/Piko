package com.piko.app.platform

import com.piko.app.domain.SendDevice
import com.piko.app.domain.SendFileItem
import com.piko.app.domain.SendMediaItem
import com.piko.app.domain.SendLanDiscoveryState
import com.piko.app.domain.SendTransferEvent
import com.piko.app.domain.SendTransferRequest

class SendPlatformActions(
    val pickMedia: ((List<SendMediaItem>) -> Unit) -> Unit = { callback ->
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
    val acceptReceiveTransfer: (String) -> Unit = {},
    val cancelReceiveTransfer: (String) -> Unit = {},
) {
    companion object {
        val Empty = SendPlatformActions()
    }
}

fun newTransferId(): String = "transfer-${currentTimeMillis()}"

internal fun currentTimeMillis(): Long = System.currentTimeMillis()
