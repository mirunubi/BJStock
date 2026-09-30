package com.mirunubi.bjstock.core.forward

import java.util.UUID

/** In-memory WorkManager model: unique names with KEEP, tag-wide cancellation, and the legacy periodic work. */
class FakeAutoWorkGateway : AutoWorkGateway {
    data class Pending(val slot: AutoScheduleSlot, val delayMillis: Long, val workId: String, var state: String)

    val calls = mutableListOf<String>()
    val enqueueCalls = mutableListOf<Pair<AutoScheduleSlot, Long>>()
    val pending = mutableListOf<Pending>()

    /** Active v2-tagged work whose slot tag no longer parses (e.g. a slot from a previous target time). */
    val staleActive = mutableListOf<String>()
    var legacyActive = false
    var legacyCancelRequests = 0
    var cancelAllRequests = 0
    var failEnqueue: Exception? = null

    val activeSlots: List<AutoScheduleSlot>
        get() = pending.filter { it.state in ACTIVE }.map { it.slot }

    override suspend fun hasActiveLegacyPeriodic(): Boolean = legacyActive

    override suspend fun cancelLegacyPeriodic() {
        calls += "cancelLegacy"
        legacyCancelRequests++
        legacyActive = false
    }

    override suspend fun activeAutoWork(): List<AutoWorkInfo> =
        pending.filter { it.state in ACTIVE }.map { AutoWorkInfo(it.workId, it.slot.scheduleInstanceId, it.state) } +
            staleActive.map { AutoWorkInfo(it, null, "ENQUEUED") }

    override suspend fun enqueueSlot(slot: AutoScheduleSlot, initialDelayMillis: Long) {
        failEnqueue?.let { throw it }
        calls += "enqueue:${slot.scheduleInstanceId}"
        enqueueCalls += slot to initialDelayMillis
        if (pending.none { it.slot == slot && it.state in ACTIVE }) {
            pending += Pending(slot, initialDelayMillis, UUID.randomUUID().toString(), "ENQUEUED")
        }
    }

    override suspend fun cancelAllAuto() {
        calls += "cancelAllAuto"
        cancelAllRequests++
        pending.filter { it.state in ACTIVE }.forEach { it.state = "CANCELLED" }
        staleActive.clear()
    }

    fun markRunning(slot: AutoScheduleSlot) {
        pending.single { it.slot == slot && it.state in ACTIVE }.state = "RUNNING"
    }

    private companion object {
        val ACTIVE = setOf("ENQUEUED", "RUNNING", "BLOCKED")
    }
}
