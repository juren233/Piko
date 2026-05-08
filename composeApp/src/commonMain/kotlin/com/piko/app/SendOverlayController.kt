package com.piko.app

class SendOverlayController {
    private var sendAction: (() -> Unit)? = null

    var canSend: Boolean = false
        private set

    fun update(
        canSend: Boolean,
        sendAction: () -> Unit,
    ) {
        this.canSend = canSend
        this.sendAction = sendAction
    }

    fun clear() {
        canSend = false
        sendAction = null
    }

    fun send() {
        sendAction?.invoke()
    }
}
