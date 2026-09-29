package com.mirunubi.bjstock.core.forward

import com.mirunubi.bjstock.core.audit.OperationCounts
import com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity
import com.mirunubi.bjstock.core.model.ForwardCycleStage
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOutcomeReason
import com.mirunubi.bjstock.core.model.ForwardRunResult
import java.time.LocalDate

sealed class ForwardOrchestratorResult {
    data class Ok(
        val processedDates: List<LocalDate>,
        val message: String? = null,
    ) : ForwardOrchestratorResult()

    data class Blocked(
        val marketDate: LocalDate?,
        val errorCode: String,
        val errorMessage: String,
        val retryable: Boolean,
    ) : ForwardOrchestratorResult()

    data class NoOp(val reason: String) : ForwardOrchestratorResult()
}

enum class ForwardErrorCode {
    AUTH_REQUIRED,
    MISSING_TRADING_POLICY,
    INSUFFICIENT_WARMUP_DATA,
    EMPTY_UNIVERSE,
    INVALID_RUN,
    DATA_INTEGRITY_ERROR,
    SNAPSHOT_MISSING_PRICE,
    NETWORK_FAILURE,
    CYCLE_FAILED,
}

interface ForwardMarketDataGateway {
    suspend fun ensureCredentials(): Boolean

    /**
     * Sync daily bars for instruments through [throughDate].
     * Sequential per instrument. Returns false on hard failure.
     */
    suspend fun syncUniverseTo(
        instrumentIds: List<Long>,
        throughDate: LocalDate,
    ): MarketSyncOutcome
}

/** Counts cover the instruments synced before any failure; [requestedStart] is the earliest requested date. */
data class MarketSyncOutcome(
    val success: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val retryable: Boolean = false,
    val requestedStart: LocalDate? = null,
    val insertedCount: Int = 0,
    val updatedCount: Int = 0,
    val unchangedCount: Int = 0,
)

/** Runtime evidence hooks. Implementations must not swallow persistence failures. */
interface ForwardExecutionObserver {
    suspend fun onRunsSelected(runIds: List<Long>) {}

    suspend fun onMarketSync(runId: Long, throughDate: LocalDate, outcome: MarketSyncOutcome, elapsedMs: Long) {}

    suspend fun onCycleStarted(cycle: ForwardTestCycleEntity) {}

    suspend fun onCycleFinished(
        cycle: ForwardTestCycleEntity,
        complete: Boolean,
        reasonCode: String?,
        stage: ForwardCycleStage?,
        elapsedMs: Long,
    ) {}

    suspend fun onRunResult(report: ForwardRunReport) {}

    companion object {
        val NONE = object : ForwardExecutionObserver {}
    }
}

/**
 * Final outcome of one Strategy Run inside one operation.
 * [reasonCode] is a [ForwardOutcomeReason] name or, for failures, the canonical error code.
 * [processingStarted] is true once market sync (or the retried cycle) was attempted.
 */
data class ForwardRunReport(
    val runId: Long,
    val result: ForwardRunResult,
    val reasonCode: String,
    val outcome: ForwardOrchestratorResult?,
    val processingStarted: Boolean = false,
    val cyclesCompleted: Int = 0,
    val cyclesFailed: Int = 0,
    val elapsedMs: Long = 0,
) {
    val isProblem: Boolean
        get() = result == ForwardRunResult.BLOCKED || result == ForwardRunResult.FAILED

    val retryable: Boolean
        get() = (outcome as? ForwardOrchestratorResult.Blocked)?.retryable == true

    /** A non-retryable block still stops later runs (run isolation is unchanged). */
    val stopsLaterRuns: Boolean
        get() = outcome is ForwardOrchestratorResult.Blocked && !outcome.retryable

    companion object {
        fun priorRunBlocked(runId: Long) = ForwardRunReport(
            runId = runId,
            result = ForwardRunResult.SKIPPED,
            reasonCode = ForwardOutcomeReason.PRIOR_RUN_BLOCKED.name,
            outcome = null,
        )
    }
}

/** Every run selected for one operation, in processing order, with the aggregate policy. */
data class ForwardExecutionReport(val runs: List<ForwardRunReport>) {
    val counts: OperationCounts
        get() = OperationCounts(
            runsConsidered = runs.size,
            runsProcessed = runs.count { it.processingStarted },
            runsSkipped = runs.count {
                !it.processingStarted &&
                    (it.result == ForwardRunResult.SKIPPED || it.result == ForwardRunResult.NO_OP)
            },
            cyclesCompleted = runs.sumOf { it.cyclesCompleted },
            cyclesFailed = runs.sumOf { it.cyclesFailed },
        )

    /** The problem that decides the aggregate: the first non-retryable one, else the first retryable one. */
    val decisiveProblem: ForwardRunReport?
        get() = runs.firstOrNull { it.isProblem && !it.retryable } ?: runs.firstOrNull { it.isProblem }

    val status: ForwardOperationStatus
        get() {
            val progress = runs.any { it.result == ForwardRunResult.PROCESSED } ||
                runs.any { it.cyclesCompleted > 0 }
            return when {
                decisiveProblem != null && progress -> ForwardOperationStatus.PARTIAL
                decisiveProblem != null -> ForwardOperationStatus.BLOCKED
                runs.any { it.result == ForwardRunResult.PROCESSED } -> ForwardOperationStatus.SUCCEEDED
                else -> ForwardOperationStatus.NO_OP
            }
        }

    val finalCode: String?
        get() = when {
            runs.isEmpty() -> ForwardOutcomeReason.NO_ELIGIBLE_RUNS.name
            decisiveProblem != null -> decisiveProblem!!.reasonCode
            status == ForwardOperationStatus.NO_OP -> runs.first().reasonCode
            else -> null
        }

    val safeMessage: String
        get() = with(counts) {
            "Processed $runsProcessed of $runsConsidered run(s); skipped $runsSkipped; " +
                "cycles completed $cyclesCompleted, failed $cyclesFailed"
        }

    val workerDisposition: WorkerDisposition
        get() = when {
            decisiveProblem == null -> WorkerDisposition.SUCCESS
            decisiveProblem!!.retryable -> WorkerDisposition.RETRY
            else -> WorkerDisposition.FAILURE
        }

    /** User-visible result; identical to the single run's result when one run is selected. */
    val summary: ForwardOrchestratorResult
        get() {
            if (runs.isEmpty()) return ForwardOrchestratorResult.NoOp("no READY/RUNNING runs")
            val outcomes = runs.mapNotNull { it.outcome }
            val blocked = outcomes.filterIsInstance<ForwardOrchestratorResult.Blocked>()
            blocked.firstOrNull { !it.retryable }?.let { return it }
            blocked.firstOrNull()?.let { return it }
            val ok = outcomes.filterIsInstance<ForwardOrchestratorResult.Ok>()
            if (ok.size == 1) return ok.single()
            if (ok.isNotEmpty()) return ForwardOrchestratorResult.Ok(ok.flatMap { it.processedDates })
            return outcomes.last()
        }
}

enum class WorkerDisposition {
    SUCCESS,
    RETRY,
    FAILURE,
}

/** Identifies the failed cycle a Retry Failed Cycle invocation targets. */
data class RetryFailedCycleTarget(
    val runId: Long,
    val marketDate: LocalDate? = null,
    val expectedCycleId: Long? = null,
)

/** The Forward Test engine behind [ForwardTestExecutionCoordinator]. */
interface ForwardRunExecutor {
    /** [operationThroughDate] is the operation-level nominal through-date; each run clamps it to its end date. */
    suspend fun executeForwardRuns(
        operationThroughDate: LocalDate,
        observer: ForwardExecutionObserver,
    ): ForwardExecutionReport

    suspend fun executeRetryFailedCycle(
        target: RetryFailedCycleTarget,
        operationThroughDate: LocalDate,
        observer: ForwardExecutionObserver,
    ): ForwardExecutionReport
}

/** Room-only gateway for unit tests / offline fixtures (no network). */
class LocalOnlyMarketDataGateway : ForwardMarketDataGateway {
    override suspend fun ensureCredentials(): Boolean = true

    override suspend fun syncUniverseTo(
        instrumentIds: List<Long>,
        throughDate: LocalDate,
    ): MarketSyncOutcome = MarketSyncOutcome(success = true)
}
