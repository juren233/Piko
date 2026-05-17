package com.piko.app.app

import com.piko.app.domain.PikoHomeState
import com.piko.app.domain.SendPageState
import com.piko.app.domain.SendTransferEvent
import com.piko.app.platform.SendPlatformActions

fun startSendTransfer(
    sendPage: SendPageState,
    senderName: String,
    onStateMutate: ((PikoHomeState) -> PikoHomeState) -> Unit,
    sendPlatformActions: SendPlatformActions,
    onTransferNotice: (String) -> Unit = {},
) {
    val request = sendPage.buildTransferRequest(senderName = senderName.substringBefore("@")) ?: return
    sendPlatformActions.startTransfer(request) { event ->
        if (event is SendTransferEvent.TransportNotice) {
            onTransferNotice(event.message)
        }
        onStateMutate { state ->
            val nextSendPage = state.sendPage.applyTransferEvent(event)
            state.copy(
                sendPage = if (event is SendTransferEvent.Completed) {
                    nextSendPage.clearSelectedItems()
                } else {
                    nextSendPage
                },
            )
        }
    }
}
