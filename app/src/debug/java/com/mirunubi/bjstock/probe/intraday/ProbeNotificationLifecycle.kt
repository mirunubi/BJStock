package com.mirunubi.bjstock.probe.intraday

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Mirrors probe state into the ongoing notification. [shutdown] cancels and joins the collector before cancelling
 * the notification, so no update can be reposted after the foreground notification has been removed.
 */
class ProbeNotificationLifecycle(
    private val post: (ProbeUiState) -> Unit,
    private val cancel: () -> Unit,
) {
    private val lock = Any()
    private var collector: Job? = null

    @Volatile
    private var shutDown = false

    fun start(scope: CoroutineScope, states: StateFlow<ProbeUiState>) {
        synchronized(lock) {
            shutDown = false
            if (collector?.isActive == true) return
            collector = scope.launch {
                states.collect { if (!shutDown) post(it) }
            }
        }
    }

    suspend fun shutdown() {
        val current = synchronized(lock) {
            shutDown = true
            collector.also { collector = null }
        }
        current?.cancelAndJoin()
        cancel()
    }
}
