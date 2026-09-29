package com.mirunubi.bjstock.core.audit

import androidx.room.withTransaction
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.database.entity.OperationalEventEntity
import com.mirunubi.bjstock.core.error.SafeAppError
import com.mirunubi.bjstock.core.error.SafeLogText
import com.mirunubi.bjstock.core.model.ForwardOperationKind
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.OperationalEventType
import java.time.Instant
import java.time.LocalDate

/**
 * Operational evidence foundation (docs/150_OPERATIONAL_RELIABILITY_STANDARD.md §7–§8).
 * Accepts only typed, allowlisted fields. Timestamps are persisted as UTC epoch millis.
 */
class ForwardOperationLogService(
    private val database: BJStockDatabase,
    private val now: () -> Instant = { Instant.now() },
) {
    private val operationDao = database.forwardOperationDao()
    private val eventDao = database.operationalEventDao()

    suspend fun startOperation(request: StartOperationRequest): StartOperationResult =
        database.withTransaction {
            val key = SafeLogText.operationKey(request.operationKey)
            validateTrigger(request)
            operationDao.findByKey(key)?.let {
                return@withTransaction StartOperationResult.AlreadyExists(it.id, it.status)
            }
            val startedAt = now()
            val id = operationDao.insert(
                ForwardOperationEntity(
                    operationKey = key,
                    trigger = request.trigger,
                    operationKind = request.kind,
                    workId = request.workId,
                    workAttempt = request.workAttempt,
                    throughDate = request.throughDate,
                    status = ForwardOperationStatus.RUNNING,
                    startedAt = startedAt,
                ),
            )
            if (id <= 0L) {
                val existing = checkNotNull(operationDao.findByKey(key)) {
                    "operation insert ignored without an existing row"
                }
                return@withTransaction StartOperationResult.AlreadyExists(existing.id, existing.status)
            }
            eventDao.insert(
                OperationalEventEntity(
                    eventKey = OperationalEventKeys.operationStarted(id),
                    operationId = id,
                    marketDate = request.throughDate,
                    eventType = OperationalEventType.OPERATION_STARTED,
                    result = request.trigger.name,
                    createdAt = startedAt,
                ),
            )
            StartOperationResult.Started(id)
        }

    suspend fun finishOperation(
        operationId: Long,
        status: ForwardOperationStatus,
        counts: OperationCounts,
        finalCode: String? = null,
        safeMessage: String? = null,
    ): FinishOperationResult = database.withTransaction {
        require(status != ForwardOperationStatus.RUNNING) { "finish requires a terminal status" }
        val code = finalCode?.let(SafeLogText::code)
        val message = SafeLogText.message(safeMessage)
        val existing = operationDao.findById(operationId)
            ?: return@withTransaction FinishOperationResult.NotFound(operationId)
        if (existing.status != ForwardOperationStatus.RUNNING) {
            return@withTransaction FinishOperationResult.AlreadyFinished(existing.id, existing.status)
        }
        val finishedAt = now()
        val elapsedMs = (finishedAt.toEpochMilli() - existing.startedAt.toEpochMilli()).coerceAtLeast(0L)
        val updated = operationDao.finishRunning(
            id = operationId,
            status = status,
            finishedAt = finishedAt,
            finalCode = code,
            safeMessage = message,
            runsConsidered = counts.runsConsidered,
            runsProcessed = counts.runsProcessed,
            runsSkipped = counts.runsSkipped,
            cyclesCompleted = counts.cyclesCompleted,
            cyclesFailed = counts.cyclesFailed,
            elapsedMs = elapsedMs,
        )
        check(updated == 1) { "RUNNING operation was not updated" }
        eventDao.insert(
            OperationalEventEntity(
                eventKey = OperationalEventKeys.operationFinished(operationId),
                operationId = operationId,
                marketDate = existing.throughDate,
                eventType = OperationalEventType.OPERATION_FINISHED,
                result = status.name,
                reasonCode = code,
                safeMessage = message,
                elapsedMs = elapsedMs,
                createdAt = finishedAt,
            ),
        )
        FinishOperationResult.Finished(operationId, status)
    }

    suspend fun finishOperation(
        operationId: Long,
        status: ForwardOperationStatus,
        counts: OperationCounts,
        error: SafeAppError,
    ): FinishOperationResult = finishOperation(
        operationId = operationId,
        status = status,
        counts = counts,
        finalCode = error.code.name,
        safeMessage = error.safeMessage,
    )

    suspend fun appendOperationalEvent(event: OperationalEventInput): AppendEventResult {
        require(event.eventType !in LIFECYCLE_TYPES) {
            "operation lifecycle events are written by start/finishOperation only"
        }
        require(event.operationId != null || event.eventType == OperationalEventType.WORKER_SCHEDULE_CHANGED) {
            "operation_id is required for ${event.eventType}"
        }
        require(event.elapsedMs == null || event.elapsedMs >= 0L) { "elapsed_ms must be non-negative" }
        val entity = OperationalEventEntity(
            eventKey = SafeLogText.eventKey(event.eventKey),
            operationId = event.operationId,
            runId = event.runId,
            cycleId = event.cycleId,
            instrumentId = event.instrumentId,
            marketDate = event.marketDate,
            eventType = event.eventType,
            result = event.result?.let(SafeLogText::code),
            reasonCode = event.reasonCode?.let(SafeLogText::code),
            safeMessage = SafeLogText.message(event.safeMessage),
            elapsedMs = event.elapsedMs,
            createdAt = now(),
        )
        return database.withTransaction {
            val id = eventDao.insert(entity)
            if (id > 0L) {
                AppendEventResult.Appended(id)
            } else {
                val existing = checkNotNull(eventDao.findByEventKey(entity.eventKey)) {
                    "event insert ignored without an existing row"
                }
                AppendEventResult.Duplicate(existing.id)
            }
        }
    }

    suspend fun findOperation(operationId: Long) = operationDao.findById(operationId)

    suspend fun findEvents(operationId: Long) = eventDao.findByOperation(operationId)

    private fun validateTrigger(request: StartOperationRequest) {
        when (request.trigger) {
            ForwardOperationTrigger.WORKER -> {
                require(!request.workId.isNullOrBlank() && request.workAttempt != null) {
                    "WORKER operations require work_id and work_attempt"
                }
                require(request.workAttempt >= 0) { "work_attempt must be non-negative" }
                require(request.kind == ForwardOperationKind.FORWARD_RUN) { "WORKER operations are FORWARD_RUN" }
                require(
                    request.operationKey ==
                        ForwardOperationKeys.worker(request.workId, request.throughDate, request.workAttempt),
                ) { "WORKER key must be worker:<work_id>:<through_date>:<attempt>" }
            }
            ForwardOperationTrigger.MANUAL -> {
                require(request.workId == null && request.workAttempt == null) {
                    "MANUAL operations must not carry work_id / work_attempt"
                }
                val prefix = when (request.kind) {
                    ForwardOperationKind.FORWARD_RUN -> "manual:"
                    ForwardOperationKind.RETRY_FAILED_CYCLE -> "manual-retry:"
                }
                require(request.operationKey.startsWith(prefix)) { "MANUAL ${request.kind} key must start with $prefix" }
            }
        }
    }

    private companion object {
        val LIFECYCLE_TYPES = setOf(
            OperationalEventType.OPERATION_STARTED,
            OperationalEventType.OPERATION_FINISHED,
        )
    }
}

data class StartOperationRequest(
    val operationKey: String,
    val trigger: ForwardOperationTrigger,
    val throughDate: LocalDate,
    val workId: String? = null,
    val workAttempt: Int? = null,
    val kind: ForwardOperationKind = ForwardOperationKind.FORWARD_RUN,
)

data class OperationCounts(
    val runsConsidered: Int = 0,
    val runsProcessed: Int = 0,
    val runsSkipped: Int = 0,
    val cyclesCompleted: Int = 0,
    val cyclesFailed: Int = 0,
) {
    init {
        require(
            runsConsidered >= 0 && runsProcessed >= 0 && runsSkipped >= 0 &&
                cyclesCompleted >= 0 && cyclesFailed >= 0,
        ) { "operation counts must be non-negative" }
    }
}

/** Typed operational event. There is intentionally no free-form payload field. */
data class OperationalEventInput(
    val eventKey: String,
    val eventType: OperationalEventType,
    val operationId: Long?,
    val runId: Long? = null,
    val cycleId: Long? = null,
    val instrumentId: Long? = null,
    val marketDate: LocalDate? = null,
    val result: String? = null,
    val reasonCode: String? = null,
    val safeMessage: String? = null,
    val elapsedMs: Long? = null,
)

sealed class StartOperationResult {
    abstract val operationId: Long

    data class Started(override val operationId: Long) : StartOperationResult()

    data class AlreadyExists(
        override val operationId: Long,
        val status: ForwardOperationStatus,
    ) : StartOperationResult()
}

sealed class FinishOperationResult {
    data class Finished(val operationId: Long, val status: ForwardOperationStatus) : FinishOperationResult()

    data class AlreadyFinished(val operationId: Long, val status: ForwardOperationStatus) : FinishOperationResult()

    data class NotFound(val operationId: Long) : FinishOperationResult()
}

sealed class AppendEventResult {
    abstract val eventId: Long

    data class Appended(override val eventId: Long) : AppendEventResult()

    data class Duplicate(override val eventId: Long) : AppendEventResult()
}

object ForwardOperationKeys {
    /**
     * Interim key for the current PeriodicWorkRequest: WorkManager reuses the work id every period
     * and resets the run attempt to 0 after each period, so the through-date separates periods.
     */
    fun worker(workId: String, throughDate: LocalDate, workAttempt: Int): String =
        "worker:$workId:$throughDate:$workAttempt"

    fun manual(requestId: String): String = "manual:$requestId"

    fun manualRetry(requestId: String): String = "manual-retry:$requestId"
}

object OperationalEventKeys {
    fun operationStarted(operationId: Long) = "op:$operationId:started"
    fun operationFinished(operationId: Long) = "op:$operationId:finished"
    fun marketSyncResult(operationId: Long, runId: Long) = "op:$operationId:run:$runId:sync"
    fun runResult(operationId: Long, runId: Long) = "op:$operationId:run:$runId:result"
    fun cycleStarted(operationId: Long, cycleId: Long, attempt: Int) =
        "op:$operationId:cycle:$cycleId:attempt:$attempt:started"
    fun cycleFinished(operationId: Long, cycleId: Long, attempt: Int) =
        "op:$operationId:cycle:$cycleId:attempt:$attempt:finished"
    fun workerScheduleChanged(action: String, epochMillis: Long) =
        "schedule:${SafeLogText.code(action)}:$epochMillis"
}
