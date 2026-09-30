package com.mirunubi.bjstock.core.forward

import com.mirunubi.bjstock.core.audit.ForwardOperationContext
import com.mirunubi.bjstock.core.audit.ForwardOperationKeys
import com.mirunubi.bjstock.core.audit.ForwardOperationLogService
import com.mirunubi.bjstock.core.audit.OperationCounts
import com.mirunubi.bjstock.core.audit.OperationalEventInput
import com.mirunubi.bjstock.core.audit.OperationalEventKeys
import com.mirunubi.bjstock.core.audit.StartOperationRequest
import com.mirunubi.bjstock.core.audit.StartOperationResult
import com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity
import com.mirunubi.bjstock.core.error.AppErrorMapper
import com.mirunubi.bjstock.core.model.ForwardCycleStage
import com.mirunubi.bjstock.core.model.ForwardOperationKind
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.ForwardOutcomeReason
import com.mirunubi.bjstock.core.model.OperationalEventType
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * The single runtime entry point for Forward Test execution (docs/150 §20.4).
 * Manual Run Now, the Worker, and Retry Failed Cycle all start here: one invocation creates one
 * `forward_operations` row, and an app-scoped single-flight guard prevents overlapping execution.
 */
class ForwardTestExecutionCoordinator(
    private val executor: ForwardRunExecutor,
    private val operationLog: ForwardOperationLogService,
    private val clock: ForwardTestClock,
    private val executionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {
    private val singleFlight = Mutex()

    val isExecutionActive: Boolean
        get() = singleFlight.isLocked

    /** Executes in [executionScope] so leaving the screen does not cancel the operation; the caller only awaits. */
    suspend fun runManualNow(): ForwardOperationOutcome = executionScope.async {
        val throughDate = clock.throughDate()
        execute(
            StartOperationRequest(
                operationKey = ForwardOperationKeys.manual(newRequestId()),
                trigger = ForwardOperationTrigger.MANUAL,
                throughDate = throughDate,
            ),
        ) { observer -> executor.executeForwardRuns(throughDate, observer) }
    }.await()

    suspend fun retryFailedCycle(target: RetryFailedCycleTarget): ForwardOperationOutcome = executionScope.async {
        val throughDate = clock.throughDate()
        execute(
            StartOperationRequest(
                operationKey = ForwardOperationKeys.manualRetry(newRequestId()),
                trigger = ForwardOperationTrigger.MANUAL,
                throughDate = throughDate,
                kind = ForwardOperationKind.RETRY_FAILED_CYCLE,
            ),
        ) { observer -> executor.executeRetryFailedCycle(target, throughDate, observer) }
    }.await()

    /**
     * Executes in the Worker's own coroutine so WorkManager stop / cancellation semantics are unchanged.
     * Identity is [scheduleInstanceId] + [runAttempt]; the through-date is computed at the actual run time.
     */
    suspend fun runWorker(workId: String, runAttempt: Int, scheduleInstanceId: String): ForwardOperationOutcome {
        val throughDate = clock.throughDate()
        return execute(
            StartOperationRequest(
                operationKey = ForwardOperationKeys.worker(scheduleInstanceId, runAttempt),
                trigger = ForwardOperationTrigger.WORKER,
                throughDate = throughDate,
                workId = workId,
                workAttempt = runAttempt,
                scheduleInstanceId = scheduleInstanceId,
            ),
        ) { observer -> executor.executeForwardRuns(throughDate, observer) }
    }

    private suspend fun execute(
        request: StartOperationRequest,
        body: suspend (ForwardExecutionObserver) -> ForwardExecutionReport,
    ): ForwardOperationOutcome {
        val operationId = when (val started = operationLog.startOperation(request)) {
            is StartOperationResult.AlreadyExists -> return replay(started)
            is StartOperationResult.Started -> started.operationId
        }
        if (!singleFlight.tryLock()) {
            return finish(
                operationId,
                Completion(
                    status = ForwardOperationStatus.BLOCKED,
                    counts = OperationCounts(),
                    finalCode = ForwardOutcomeReason.ALREADY_RUNNING.name,
                    safeMessage = ALREADY_RUNNING_MESSAGE,
                    display = ForwardOrchestratorResult.Blocked(
                        marketDate = null,
                        errorCode = ForwardOutcomeReason.ALREADY_RUNNING.name,
                        errorMessage = ALREADY_RUNNING_MESSAGE,
                        retryable = true,
                    ),
                    disposition = WorkerDisposition.RETRY,
                ),
            )
        }
        val recorder = OperationEventRecorder(operationId, operationLog)
        try {
            val completion = try {
                Completion.of(withContext(ForwardOperationContext(operationId)) { body(recorder) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Completion.failed(failure, recorder.counts)
            }
            return finish(operationId, completion)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                operationLog.finishOperation(
                    operationId = operationId,
                    status = ForwardOperationStatus.FAILED,
                    counts = recorder.counts,
                    finalCode = ForwardOutcomeReason.CANCELLED.name,
                    safeMessage = CANCELLED_MESSAGE,
                )
            }
            throw cancelled
        } finally {
            singleFlight.unlock()
        }
    }

    private suspend fun finish(operationId: Long, completion: Completion): ForwardOperationOutcome {
        operationLog.finishOperation(
            operationId = operationId,
            status = completion.status,
            counts = completion.counts,
            finalCode = completion.finalCode,
            safeMessage = completion.safeMessage,
        )
        return ForwardOperationOutcome(
            operationId = operationId,
            status = completion.status,
            finalCode = completion.finalCode,
            display = completion.display,
            disposition = completion.disposition,
        )
    }

    /**
     * The same operation key never executes twice; the stored row decides the outcome. A row closed as
     * PROCESS_INTERRUPTED answers RETRY so WorkManager's next attempt runs under its own attempt key.
     */
    private suspend fun replay(existing: StartOperationResult.AlreadyExists): ForwardOperationOutcome {
        val row = operationLog.findOperation(existing.operationId)
        val finalCode = row?.finalCode
        val retryable = finalCode == ForwardOutcomeReason.ALREADY_RUNNING.name ||
            finalCode == ForwardOutcomeReason.PROCESS_INTERRUPTED.name ||
            AppErrorMapper.fromForwardErrorCodeName(finalCode).isRetryableAutomatically
        val disposition = when (existing.status) {
            ForwardOperationStatus.RUNNING -> WorkerDisposition.RETRY
            ForwardOperationStatus.SUCCEEDED, ForwardOperationStatus.NO_OP -> WorkerDisposition.SUCCESS
            else -> if (retryable) WorkerDisposition.RETRY else WorkerDisposition.FAILURE
        }
        val display = if (disposition == WorkerDisposition.SUCCESS) {
            ForwardOrchestratorResult.NoOp(REPLAY_MESSAGE)
        } else {
            ForwardOrchestratorResult.Blocked(
                marketDate = null,
                errorCode = finalCode ?: existing.status.name,
                errorMessage = REPLAY_MESSAGE,
                retryable = disposition == WorkerDisposition.RETRY,
            )
        }
        return ForwardOperationOutcome(
            operationId = existing.operationId,
            status = existing.status,
            finalCode = finalCode,
            display = display,
            disposition = disposition,
            replayed = true,
        )
    }

    private class Completion(
        val status: ForwardOperationStatus,
        val counts: OperationCounts,
        val finalCode: String?,
        val safeMessage: String?,
        val display: ForwardOrchestratorResult,
        val disposition: WorkerDisposition,
    ) {
        companion object {
            fun of(report: ForwardExecutionReport) = Completion(
                status = report.status,
                counts = report.counts,
                finalCode = report.finalCode,
                safeMessage = report.safeMessage,
                display = report.summary,
                disposition = report.workerDisposition,
            )

            /** [Throwable.message] is never used; only the catalog safe message and class name. */
            fun failed(failure: Exception, counts: OperationCounts): Completion {
                val error = AppErrorMapper.fromThrowable(failure, logicalEndpoint = LOGICAL_ENDPOINT)
                val message = listOfNotNull(
                    error.safeMessage,
                    error.diagnostics.exceptionType?.let { "($it)" },
                ).joinToString(" ")
                return Completion(
                    status = ForwardOperationStatus.FAILED,
                    counts = counts,
                    finalCode = error.code.name,
                    safeMessage = message,
                    display = ForwardOrchestratorResult.Blocked(null, error.code.name, message, retryable = false),
                    disposition = WorkerDisposition.FAILURE,
                )
            }
        }
    }

    private companion object {
        const val LOGICAL_ENDPOINT = "FORWARD_OPERATION"
        const val ALREADY_RUNNING_MESSAGE = "Another Forward Test operation is already running"
        const val CANCELLED_MESSAGE = "Operation cancelled before completion"
        const val REPLAY_MESSAGE = "Operation already recorded for this invocation"
    }
}

data class ForwardOperationOutcome(
    val operationId: Long,
    val status: ForwardOperationStatus,
    val finalCode: String?,
    val display: ForwardOrchestratorResult,
    val disposition: WorkerDisposition,
    val replayed: Boolean = false,
)

/** Writes MARKET_SYNC_RESULT / RUN_RESULT / CYCLE_* for one operation and tallies partial counts. */
private class OperationEventRecorder(
    private val operationId: Long,
    private val log: ForwardOperationLogService,
) : ForwardExecutionObserver {
    private var runsSelected = 0
    private val runReports = mutableListOf<ForwardRunReport>()
    private var cyclesCompleted = 0
    private var cyclesFailed = 0

    val counts: OperationCounts
        get() = ForwardExecutionReport(runReports).counts.copy(
            runsConsidered = maxOf(runsSelected, runReports.size),
            cyclesCompleted = cyclesCompleted,
            cyclesFailed = cyclesFailed,
        )

    override suspend fun onRunsSelected(runIds: List<Long>) {
        runsSelected = runIds.size
    }

    override suspend fun onMarketSync(
        runId: Long,
        throughDate: LocalDate,
        outcome: MarketSyncOutcome,
        elapsedMs: Long,
    ) {
        val range = outcome.requestedStart?.let { "$it to $throughDate" } ?: "through $throughDate"
        append(
            OperationalEventInput(
                eventKey = OperationalEventKeys.marketSyncResult(operationId, runId),
                eventType = OperationalEventType.MARKET_SYNC_RESULT,
                operationId = operationId,
                runId = runId,
                marketDate = throughDate,
                result = if (outcome.success) SYNC_SUCCESS else SYNC_FAILED,
                reasonCode = if (outcome.success) null else outcome.errorCode,
                safeMessage = "Requested $range; inserted ${outcome.insertedCount}, " +
                    "updated ${outcome.updatedCount}, unchanged ${outcome.unchangedCount}",
                elapsedMs = elapsedMs,
            ),
        )
    }

    override suspend fun onCycleStarted(cycle: ForwardTestCycleEntity) {
        append(
            OperationalEventInput(
                eventKey = OperationalEventKeys.cycleStarted(operationId, cycle.id, cycle.attemptCount),
                eventType = OperationalEventType.CYCLE_STARTED,
                operationId = operationId,
                runId = cycle.strategyRunId,
                cycleId = cycle.id,
                marketDate = cycle.marketDate,
                safeMessage = "Attempt ${cycle.attemptCount}",
            ),
        )
    }

    override suspend fun onCycleFinished(
        cycle: ForwardTestCycleEntity,
        complete: Boolean,
        reasonCode: String?,
        stage: ForwardCycleStage?,
        elapsedMs: Long,
    ) {
        if (complete) cyclesCompleted += 1 else cyclesFailed += 1
        append(
            OperationalEventInput(
                eventKey = OperationalEventKeys.cycleFinished(operationId, cycle.id, cycle.attemptCount),
                eventType = OperationalEventType.CYCLE_FINISHED,
                operationId = operationId,
                runId = cycle.strategyRunId,
                cycleId = cycle.id,
                marketDate = cycle.marketDate,
                result = if (complete) CYCLE_COMPLETE else CYCLE_FAILED,
                reasonCode = reasonCode,
                safeMessage = listOfNotNull("Attempt ${cycle.attemptCount}", stage?.let { "stage ${it.name}" })
                    .joinToString("; "),
                elapsedMs = elapsedMs,
            ),
        )
    }

    override suspend fun onRunResult(report: ForwardRunReport) {
        runReports += report
        append(
            OperationalEventInput(
                eventKey = OperationalEventKeys.runResult(operationId, report.runId),
                eventType = OperationalEventType.RUN_RESULT,
                operationId = operationId,
                runId = report.runId,
                result = report.result.name,
                reasonCode = report.reasonCode,
                safeMessage = if (report.reasonCode == ForwardOutcomeReason.PRIOR_RUN_BLOCKED.name) {
                    PRIOR_RUN_BLOCKED_MESSAGE
                } else {
                    "Cycles completed ${report.cyclesCompleted}, failed ${report.cyclesFailed}"
                },
                elapsedMs = report.elapsedMs,
            ),
        )
    }

    private suspend fun append(event: OperationalEventInput) {
        log.appendOperationalEvent(event)
    }

    private companion object {
        const val SYNC_SUCCESS = "SUCCESS"
        const val SYNC_FAILED = "FAILED"
        const val CYCLE_COMPLETE = "COMPLETE"
        const val CYCLE_FAILED = "FAILED"
        const val PRIOR_RUN_BLOCKED_MESSAGE = "Skipped because an earlier run blocked the operation"
    }
}
