package com.piko.app.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PresenceHeartbeatScheduler(
    private val scope: CoroutineScope,
    private val tick: suspend () -> Unit,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            tick()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
