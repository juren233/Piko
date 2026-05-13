package com.piko.app.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PresenceHeartbeatScheduler(
    private val scope: CoroutineScope,
    private val heartbeat: suspend () -> Unit,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            heartbeat()
            while (isActive) {
                delay(30_000)
                heartbeat()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
