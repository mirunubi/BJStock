package com.mirunubi.bjstock.probe.intraday

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-6: no notification may be reposted after the foreground notification is removed. */
class ProbeNotificationLifecycleTest {
    private class Recorder {
        val log = CopyOnWriteArrayList<String>()
        val lifecycle = ProbeNotificationLifecycle(
            post = { log += "post:${it.status.name}" },
            cancel = { log += "cancel" },
        )
    }

    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun shutdownCancelsCollectorBeforeRemovingAndNeverRepostsAfterwards() = runBlocking {
        val r = Recorder()
        val states = MutableStateFlow(ProbeUiState(status = ProbeStatus.STARTING))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        r.lifecycle.start(scope, states)
        awaitUntil { r.log.contains("post:STARTING") }
        states.value = ProbeUiState(status = ProbeStatus.ERROR, sessionActive = true)
        awaitUntil { r.log.contains("post:ERROR") }

        r.lifecycle.shutdown()
        states.value = ProbeUiState(status = ProbeStatus.RUNNING)
        states.value = ProbeUiState(status = ProbeStatus.STOPPED)
        delay(200)
        scope.cancel()

        assertEquals("cancel", r.log.last())
        assertEquals(1, r.log.count { it == "cancel" })
        assertTrue(r.log.none { it == "post:RUNNING" || it == "post:STOPPED" })
    }

    @Test
    fun refusedStartShutdownLeavesNoNotificationAndCanBeRearmed() = runBlocking {
        val r = Recorder()
        val states = MutableStateFlow(ProbeUiState(status = ProbeStatus.STOPPED, lastMessage = "VIRTUAL_CREDENTIAL_MISSING"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        r.lifecycle.start(scope, states)
        r.lifecycle.shutdown()
        val afterRefusal = r.log.size
        states.value = ProbeUiState(status = ProbeStatus.ERROR)
        delay(100)
        assertEquals(afterRefusal, r.log.size)
        assertEquals("cancel", r.log.last())

        r.lifecycle.start(scope, states)
        awaitUntil { r.log.size > afterRefusal }
        r.lifecycle.shutdown()
        scope.cancel()
        assertEquals("cancel", r.log.last())
    }
}
