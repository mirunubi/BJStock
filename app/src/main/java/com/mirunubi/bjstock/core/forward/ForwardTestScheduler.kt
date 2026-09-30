package com.mirunubi.bjstock.core.forward

import android.util.Log
import com.mirunubi.bjstock.core.audit.ForwardOperationLogService
import com.mirunubi.bjstock.core.audit.OperationalEventInput
import com.mirunubi.bjstock.core.audit.OperationalEventKeys
import com.mirunubi.bjstock.core.error.SafeLogText
import com.mirunubi.bjstock.core.model.OperationalEventType
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Daily one-time Auto scheduling (docs/150 §20.9). Each slot is a unique OneTimeWorkRequest delayed until
 * 07:30 Asia/Seoul; every valid invocation schedules the following slot before it executes. Auto is OFF by
 * default, and turning it ON never executes anything immediately. WorkManager is the source of truth for
 * scheduled work; [ForwardTestSchedulerSettings] only holds the Auto flag.
 */
class ForwardTestScheduler(
    private val settings: ForwardTestSchedulerSettings,
    private val gateway: AutoWorkGateway,
    private val operationLog: ForwardOperationLogService,
    private val clock: ForwardTestClock,
) {
    private val mutex = Mutex()

    @Volatile
    private var lastScheduleFailure: String? = null

    fun isAutoEnabled(): Boolean = settings.isAutoEnabled()

    suspend fun setAutoEnabled(enabled: Boolean): ScheduleChange = mutex.withLock {
        settings.setAutoEnabled(enabled)
        reportingFailure {
            if (enabled) reconcileEnabled(legacyFirst = true) else reconcileDisabled(userInitiated = true)
        }
    }

    /** Re-applies the persisted flag at process start. Never enables Auto and never duplicates pending work. */
    suspend fun reconcileOnAppStart(): ScheduleChange = mutex.withLock {
        reportingFailure {
            if (settings.isAutoEnabled()) {
                reconcileEnabled(legacyFirst = true)
            } else {
                reconcileDisabled(userInitiated = false)
            }
        }
    }

    /**
     * Called by a valid v2 invocation of [current] before it executes: the next slot strictly after both the
     * actual time and [current] is scheduled, so a late invocation never produces a burst of missed slots.
     */
    suspend fun ensureSlotAfter(current: AutoScheduleSlot): ScheduleChange = mutex.withLock {
        if (!settings.isAutoEnabled()) return@withLock ScheduleChange()
        reportingFailure {
            val after = maxOf(clock.nowInstant(), current.scheduledAt)
            enqueue(AutoScheduleSlot.nextAfter(after))
        }
    }

    /**
     * Called by a legacy periodic invocation (no schedule instance id). The v2 slot is ensured before the
     * legacy work is cancelled, because cancelling it may stop the calling Worker.
     */
    suspend fun migrateFromLegacyInvocation(): ScheduleChange = mutex.withLock {
        if (!settings.isAutoEnabled()) return@withLock ScheduleChange()
        reportingFailure { reconcileEnabled(legacyFirst = false) }
    }

    suspend fun status(): AutoScheduleStatus {
        val next = gateway.activeAutoWork()
            .mapNotNull { info -> AutoScheduleSlot.parse(info.scheduleInstanceId)?.let { it to info } }
            .minWithOrNull(compareBy({ (_, info) -> info.state == RUNNING_STATE }, { (slot, _) -> slot.date }))
        return AutoScheduleStatus(
            autoEnabled = settings.isAutoEnabled(),
            nextScheduleInstanceId = next?.first?.scheduleInstanceId,
            nextScheduledAt = next?.first?.scheduledAt,
            workId = next?.second?.workId,
            workState = next?.second?.state,
            lastScheduleFailure = lastScheduleFailure,
        )
    }

    private suspend fun reconcileEnabled(legacyFirst: Boolean): ScheduleChange {
        var change = ScheduleChange()
        if (legacyFirst) change += cancelLegacy()
        if (gateway.activeAutoWork().isEmpty()) {
            change += enqueue(AutoScheduleSlot.nextAfter(clock.nowInstant()))
        }
        if (!legacyFirst) change += cancelLegacy()
        return change
    }

    private suspend fun reconcileDisabled(userInitiated: Boolean): ScheduleChange {
        val hadAutoWork = gateway.activeAutoWork().isNotEmpty()
        val legacy = cancelLegacy()
        gateway.cancelAllAuto()
        if (!userInitiated && !hadAutoWork) return legacy
        val failure = record(
            OperationalEventInput(
                eventKey = OperationalEventKeys.workerScheduleChanged(AUTO_DISABLED, clock.nowInstant().toEpochMilli()),
                eventType = OperationalEventType.WORKER_SCHEDULE_CHANGED,
                operationId = null,
                result = AUTO_DISABLED,
                safeMessage = AUTO_DISABLED_MESSAGE,
            ),
        )
        return legacy + ScheduleChange(autoWorkCancelled = true, eventFailures = listOfNotNull(failure))
    }

    private suspend fun cancelLegacy(): ScheduleChange {
        val wasActive = gateway.hasActiveLegacyPeriodic()
        gateway.cancelLegacyPeriodic()
        if (!wasActive) return ScheduleChange()
        val failure = record(
            OperationalEventInput(
                eventKey = OperationalEventKeys.legacyPeriodicCancelled(ForwardTestConfig.LEGACY_PERIODIC_WORK_NAME),
                eventType = OperationalEventType.WORKER_SCHEDULE_CHANGED,
                operationId = null,
                result = LEGACY_PERIODIC_CANCELLED,
                safeMessage = LEGACY_PERIODIC_CANCELLED_MESSAGE,
            ),
        )
        return ScheduleChange(legacyCancelled = true, eventFailures = listOfNotNull(failure))
    }

    private suspend fun enqueue(slot: AutoScheduleSlot): ScheduleChange {
        val delayMillis = slot.scheduledAt.toEpochMilli() - clock.nowInstant().toEpochMilli()
        gateway.enqueueSlot(slot, delayMillis)
        val failure = record(
            OperationalEventInput(
                eventKey = OperationalEventKeys.scheduleSlotEnqueued(slot.scheduleInstanceId),
                eventType = OperationalEventType.WORKER_SCHEDULE_CHANGED,
                operationId = null,
                marketDate = slot.date,
                result = SLOT_ENQUEUED,
                safeMessage = SLOT_ENQUEUED_MESSAGE,
            ),
        )
        return ScheduleChange(enqueuedSlot = slot, eventFailures = listOfNotNull(failure))
    }

    /** Schedule events are diagnostics: a failed append is surfaced, never allowed to undo scheduling. */
    private suspend fun record(event: OperationalEventInput): String? =
        try {
            operationLog.appendOperationalEvent(event)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val failure = "$SCHEDULE_EVENT_NOT_PERSISTED:${SafeLogText.exceptionType(e::class.simpleName) ?: "Exception"}"
            lastScheduleFailure = failure
            Log.w(TAG, failure)
            failure
        }

    /** WorkManager failures propagate to the caller; the last one stays visible in [status]. */
    private suspend fun reportingFailure(block: suspend () -> ScheduleChange): ScheduleChange =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            lastScheduleFailure =
                "$SCHEDULE_UPDATE_FAILED:${SafeLogText.exceptionType(e::class.simpleName) ?: "Exception"}"
            throw e
        }

    companion object {
        private const val TAG = "ForwardTestScheduler"
        private const val RUNNING_STATE = "RUNNING"
        const val SLOT_ENQUEUED = "SLOT_ENQUEUED"
        const val AUTO_DISABLED = "AUTO_DISABLED"
        const val LEGACY_PERIODIC_CANCELLED = "LEGACY_PERIODIC_CANCELLED"
        const val SCHEDULE_EVENT_NOT_PERSISTED = "SCHEDULE_EVENT_NOT_PERSISTED"
        const val SCHEDULE_UPDATE_FAILED = "SCHEDULE_UPDATE_FAILED"
        const val SLOT_ENQUEUED_MESSAGE = "Auto Forward Test slot scheduled for 07:30 KST"
        const val AUTO_DISABLED_MESSAGE = "Auto Forward Test disabled; pending slots cancelled"
        const val LEGACY_PERIODIC_CANCELLED_MESSAGE = "Legacy periodic Forward Test work cancelled"
    }
}

/** What one scheduler call changed. [eventFailures] lists schedule events that could not be persisted. */
data class ScheduleChange(
    val enqueuedSlot: AutoScheduleSlot? = null,
    val legacyCancelled: Boolean = false,
    val autoWorkCancelled: Boolean = false,
    val eventFailures: List<String> = emptyList(),
) {
    operator fun plus(other: ScheduleChange) = ScheduleChange(
        enqueuedSlot = other.enqueuedSlot ?: enqueuedSlot,
        legacyCancelled = legacyCancelled || other.legacyCancelled,
        autoWorkCancelled = autoWorkCancelled || other.autoWorkCancelled,
        eventFailures = eventFailures + other.eventFailures,
    )
}

/** Read model for the UI; no WorkManager names or operation keys need to be parsed by callers. */
data class AutoScheduleStatus(
    val autoEnabled: Boolean,
    val nextScheduleInstanceId: String?,
    val nextScheduledAt: Instant?,
    val workId: String?,
    val workState: String?,
    val lastScheduleFailure: String?,
)
