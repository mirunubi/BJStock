package com.mirunubi.bjstock.core.forward

import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.database.dao.FactorDao
import com.mirunubi.bjstock.core.database.dao.ForwardTestCycleDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.dao.OrderDao
import com.mirunubi.bjstock.core.database.dao.PortfolioDailySnapshotDao
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunInstrumentDao
import com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity
import com.mirunubi.bjstock.core.database.entity.StrategyFactorWeightEntity
import com.mirunubi.bjstock.core.error.AppErrorMapper
import com.mirunubi.bjstock.core.factor.FactorCalculationService
import com.mirunubi.bjstock.core.model.ForwardCycleStage
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.ForwardOutcomeReason
import com.mirunubi.bjstock.core.model.ForwardRunResult
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.core.paper.CreateDailySnapshotUseCase
import com.mirunubi.bjstock.core.paper.MissingTradingPolicyException
import com.mirunubi.bjstock.core.paper.PaperTradeAction
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
import com.mirunubi.bjstock.core.paper.ProcessEvaluationUseCase
import com.mirunubi.bjstock.core.paper.ProcessPendingOrdersUseCase
import com.mirunubi.bjstock.core.strategy.EvaluateStrategyRunUseCase
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

/**
 * Long-term forward-test orchestration. Runtime entry points (Run Now, Worker, Retry Failed Cycle)
 * reach it only through [ForwardTestExecutionCoordinator]. Does not call AI Advisory.
 */
class ForwardTestOrchestrator(
    private val strategyRunDao: StrategyRunDao,
    private val strategyDao: StrategyDao,
    private val universeDao: StrategyRunInstrumentDao,
    private val cycleDao: ForwardTestCycleDao,
    private val marketDailyBarDao: MarketDailyBarDao,
    private val orderDao: OrderDao,
    private val evaluationDao: StockEvaluationDao,
    private val snapshotDao: PortfolioDailySnapshotDao,
    private val factorDao: FactorDao,
    private val policyService: PaperTradingPolicyService,
    private val processPending: ProcessPendingOrdersUseCase,
    private val factorCalculation: FactorCalculationService,
    private val evaluateRun: EvaluateStrategyRunUseCase,
    private val processEvaluation: ProcessEvaluationUseCase,
    private val createSnapshot: CreateDailySnapshotUseCase,
    private val marketData: ForwardMarketDataGateway,
    private val clock: ForwardTestClock = ForwardTestClock(),
    private val apiErrorLog: ApiErrorLogService? = null,
    private val now: () -> Instant = { Instant.now() },
) : ForwardRunExecutor {
    suspend fun runForwardTests(throughDate: LocalDate? = null): ForwardOrchestratorResult =
        forwardRuns(explicitOrClock(throughDate), ForwardExecutionObserver.NONE).summary

    override suspend fun executeForwardRuns(
        operationThroughDate: LocalDate,
        observer: ForwardExecutionObserver,
    ): ForwardExecutionReport = forwardRuns(clampedToRunEnd(operationThroughDate), observer)

    suspend fun runSingleStrategyRun(
        runId: Long,
        throughDate: LocalDate? = null,
    ): ForwardOrchestratorResult =
        checkNotNull(executeRun(runId, explicitOrClock(throughDate), ForwardExecutionObserver.NONE).outcome)

    suspend fun retryFailedCycle(
        runId: Long,
        marketDate: LocalDate? = null,
        throughDate: LocalDate? = null,
    ): ForwardOrchestratorResult =
        retry(RetryFailedCycleTarget(runId, marketDate), explicitOrClock(throughDate), ForwardExecutionObserver.NONE)
            .summary

    override suspend fun executeRetryFailedCycle(
        target: RetryFailedCycleTarget,
        operationThroughDate: LocalDate,
        observer: ForwardExecutionObserver,
    ): ForwardExecutionReport = retry(target, clampedToRunEnd(operationThroughDate), observer)

    private suspend fun forwardRuns(
        throughFor: (LocalDate?) -> LocalDate,
        observer: ForwardExecutionObserver,
    ): ForwardExecutionReport {
        val runIds = strategyRunDao.findAll()
            .filter { it.status == RunStatus.READY || it.status == RunStatus.RUNNING }
            .sortedBy { it.id }
            .map { it.id }
        observer.onRunsSelected(runIds)
        val reports = mutableListOf<ForwardRunReport>()
        for ((index, runId) in runIds.withIndex()) {
            val report = executeRun(runId, throughFor, observer)
            observer.onRunResult(report)
            reports += report
            if (report.stopsLaterRuns) {
                runIds.drop(index + 1).forEach { skipped ->
                    val skippedReport = ForwardRunReport.priorRunBlocked(skipped)
                    observer.onRunResult(skippedReport)
                    reports += skippedReport
                }
                break
            }
        }
        return ForwardExecutionReport(reports).also { cleanupApiErrorsOnSuccess(it.summary) }
    }

    private suspend fun executeRun(
        runId: Long,
        throughFor: (LocalDate?) -> LocalDate,
        observer: ForwardExecutionObserver,
    ): ForwardRunReport {
        val startedAt = now()
        var processingStarted = false
        var cyclesCompleted = 0
        var cyclesFailed = 0
        fun report(result: ForwardRunResult, reason: String, outcome: ForwardOrchestratorResult) =
            ForwardRunReport(
                runId = runId,
                result = result,
                reasonCode = reason,
                outcome = outcome,
                processingStarted = processingStarted,
                cyclesCompleted = cyclesCompleted,
                cyclesFailed = cyclesFailed,
                elapsedMs = elapsedSince(startedAt),
            )

        val run = strategyRunDao.findById(runId)
            ?: return report(
                ForwardRunResult.BLOCKED,
                ForwardOutcomeReason.RUN_NOT_FOUND.name,
                blocked(null, ForwardErrorCode.INVALID_RUN, "strategy run not found", false),
            )
        if (run.status !in setOf(RunStatus.READY, RunStatus.RUNNING)) {
            return report(
                ForwardRunResult.SKIPPED,
                ForwardOutcomeReason.INVALID_RUN_STATE.name,
                ForwardOrchestratorResult.NoOp("run status ${run.status}"),
            )
        }
        if (universeDao.countByRun(runId) <= 0) {
            return report(
                ForwardRunResult.BLOCKED,
                ForwardOutcomeReason.EMPTY_UNIVERSE.name,
                blocked(null, ForwardErrorCode.EMPTY_UNIVERSE, "universe empty", false),
            )
        }
        try {
            policyService.requireByRun(runId)
        } catch (_: MissingTradingPolicyException) {
            return report(
                ForwardRunResult.BLOCKED,
                ForwardOutcomeReason.MISSING_POLICY.name,
                blocked(null, ForwardErrorCode.MISSING_TRADING_POLICY, "MISSING_TRADING_POLICY", false),
            )
        }
        if (!marketData.ensureCredentials()) {
            return report(
                ForwardRunResult.BLOCKED,
                ForwardOutcomeReason.AUTH_REQUIRED.name,
                blocked(null, ForwardErrorCode.AUTH_REQUIRED, "KIS credentials required", false),
            )
        }

        val effectiveThrough = throughFor(run.endDate)
        if (effectiveThrough.isBefore(run.startDate)) {
            return report(
                ForwardRunResult.SKIPPED,
                ForwardOutcomeReason.THROUGH_DATE_BEFORE_START.name,
                ForwardOrchestratorResult.NoOp("throughDate before start_date"),
            )
        }

        val blockedCycle = cycleDao.findOldestByStatus(runId, ForwardCycleStatus.FAILED)
        if (blockedCycle != null && !blockedCycle.retryable) {
            return report(
                ForwardRunResult.BLOCKED,
                ForwardOutcomeReason.PREVIOUS_FAILED_CYCLE.name,
                ForwardOrchestratorResult.Blocked(
                    marketDate = blockedCycle.marketDate,
                    errorCode = blockedCycle.errorCode ?: ForwardErrorCode.CYCLE_FAILED.name,
                    errorMessage = blockedCycle.errorMessage ?: "blocked failed cycle",
                    retryable = false,
                ),
            )
        }

        val instrumentIds = universeDao.findByRun(runId).map { it.instrumentId }.sorted()
        processingStarted = true
        val syncStartedAt = now()
        val sync = marketData.syncUniverseTo(instrumentIds, effectiveThrough)
        observer.onMarketSync(runId, effectiveThrough, sync, elapsedSince(syncStartedAt))
        if (!sync.success) {
            val code = sync.errorCode ?: ForwardErrorCode.NETWORK_FAILURE.name
            return report(
                ForwardRunResult.BLOCKED,
                code,
                ForwardOrchestratorResult.Blocked(
                    marketDate = null,
                    errorCode = code,
                    errorMessage = sanitizeError(sync.errorMessage) ?: "network failure",
                    retryable = sync.retryable,
                ),
            )
        }

        val lastComplete = cycleDao.findLastCompleteDate(runId)
        val afterDate = lastComplete ?: run.startDate.minusDays(1)
        val dates = marketDailyBarDao.findDistinctTradeDatesInRange(
            instrumentIds = instrumentIds,
            afterDate = afterDate,
            throughDate = effectiveThrough,
        ).filter { !it.isBefore(run.startDate) }
        if (dates.isEmpty()) {
            return report(
                ForwardRunResult.NO_OP,
                ForwardOutcomeReason.WAITING_FOR_MARKET_DATA.name,
                ForwardOrchestratorResult.NoOp("WAITING FOR MARKET DATA"),
            )
        }

        if (run.status == RunStatus.READY) {
            strategyRunDao.updateStatus(runId, RunStatus.RUNNING, now())
        }

        val processed = mutableListOf<LocalDate>()
        for (marketDate in dates) {
            val cycle = processCycle(
                runId = runId,
                marketDate = marketDate,
                allowNewOrders = run.endDate == null || marketDate.isBefore(run.endDate),
                observer = observer,
            )
            val result = cycle.result
            if (result is ForwardOrchestratorResult.Blocked) {
                cyclesFailed += 1
                return report(ForwardRunResult.FAILED, result.errorCode, result)
            }
            if (cycle.attempted) cyclesCompleted += 1
            processed += marketDate
            if (run.endDate != null && marketDate == run.endDate) {
                finalizeRunEnd(runId, marketDate)
                break
            }
        }
        return report(
            ForwardRunResult.PROCESSED,
            ForwardOutcomeReason.PROCESSED.name,
            ForwardOrchestratorResult.Ok(processedDates = processed),
        )
    }

    private suspend fun retry(
        target: RetryFailedCycleTarget,
        throughFor: (LocalDate?) -> LocalDate,
        observer: ForwardExecutionObserver,
    ): ForwardExecutionReport {
        val runId = target.runId
        observer.onRunsSelected(listOf(runId))
        val startedAt = now()
        fun targetReport(result: ForwardRunResult, reason: ForwardOutcomeReason, outcome: ForwardOrchestratorResult) =
            ForwardRunReport(runId, result, reason.name, outcome, elapsedMs = elapsedSince(startedAt))

        val failed = when {
            target.expectedCycleId != null -> cycleDao.findById(target.expectedCycleId)
            target.marketDate != null -> cycleDao.find(runId, target.marketDate)
            else -> cycleDao.findOldestByStatus(runId, ForwardCycleStatus.FAILED)
        }
        val run = strategyRunDao.findById(runId)
        val report = when {
            failed == null -> targetReport(
                ForwardRunResult.NO_OP,
                ForwardOutcomeReason.TARGET_CYCLE_NOT_FOUND,
                ForwardOrchestratorResult.NoOp("no failed cycle"),
            )
            failed.strategyRunId != runId -> targetReport(
                ForwardRunResult.BLOCKED,
                ForwardOutcomeReason.TARGET_CYCLE_RUN_MISMATCH,
                blocked(failed.marketDate, ForwardErrorCode.INVALID_RUN, "cycle belongs to another run", false),
            )
            failed.status != ForwardCycleStatus.FAILED -> targetReport(
                ForwardRunResult.NO_OP,
                ForwardOutcomeReason.TARGET_CYCLE_NOT_FAILED,
                ForwardOrchestratorResult.NoOp("cycle not failed"),
            )
            run == null -> targetReport(
                ForwardRunResult.BLOCKED,
                ForwardOutcomeReason.RUN_NOT_FOUND,
                blocked(null, ForwardErrorCode.INVALID_RUN, "run not found", false),
            )
            else -> retryAndContinue(run.id, failed.marketDate, run.endDate, throughFor, observer, startedAt)
        }
        observer.onRunResult(report)
        return ForwardExecutionReport(listOf(report))
    }

    private suspend fun retryAndContinue(
        runId: Long,
        marketDate: LocalDate,
        runEndDate: LocalDate?,
        throughFor: (LocalDate?) -> LocalDate,
        observer: ForwardExecutionObserver,
        startedAt: Instant,
    ): ForwardRunReport {
        val retried = processCycle(
            runId = runId,
            marketDate = marketDate,
            allowNewOrders = runEndDate == null || marketDate.isBefore(runEndDate),
            observer = observer,
        ).result
        if (retried is ForwardOrchestratorResult.Blocked) {
            return ForwardRunReport(
                runId = runId,
                result = ForwardRunResult.FAILED,
                reasonCode = retried.errorCode,
                outcome = retried,
                processingStarted = true,
                cyclesFailed = 1,
                elapsedMs = elapsedSince(startedAt),
            )
        }
        val continued = executeRun(runId, throughFor, observer)
        cleanupApiErrorsOnSuccess(checkNotNull(continued.outcome))
        return continued.copy(
            result = if (continued.isProblem) continued.result else ForwardRunResult.PROCESSED,
            reasonCode = if (continued.isProblem) continued.reasonCode else ForwardOutcomeReason.PROCESSED.name,
            processingStarted = true,
            cyclesCompleted = continued.cyclesCompleted + 1,
            elapsedMs = elapsedSince(startedAt),
        )
    }

    /** Legacy entry points: an explicit date is used as-is; otherwise the clock clamps to the run end. */
    private fun explicitOrClock(throughDate: LocalDate?): (LocalDate?) -> LocalDate =
        { runEndDate -> throughDate ?: clock.throughDate(runEndDate) }

    private fun clampedToRunEnd(operationThroughDate: LocalDate): (LocalDate?) -> LocalDate =
        { runEndDate ->
            if (runEndDate != null && runEndDate.isBefore(operationThroughDate)) runEndDate else operationThroughDate
        }

    private fun elapsedSince(startedAt: Instant): Long =
        (now().toEpochMilli() - startedAt.toEpochMilli()).coerceAtLeast(0L)

    private suspend fun cleanupApiErrorsOnSuccess(result: ForwardOrchestratorResult) {
        if (result is ForwardOrchestratorResult.Ok || result is ForwardOrchestratorResult.NoOp) {
            runCatching { apiErrorLog?.cleanupOlderThanSevenDays() }
        }
    }

    private class CycleOutcome(val result: ForwardOrchestratorResult, val attempted: Boolean)

    private suspend fun processCycle(
        runId: Long,
        marketDate: LocalDate,
        allowNewOrders: Boolean,
        observer: ForwardExecutionObserver,
    ): CycleOutcome {
        val existing = cycleDao.find(runId, marketDate)
        if (existing?.status == ForwardCycleStatus.COMPLETE) {
            return CycleOutcome(ForwardOrchestratorResult.Ok(listOf(marketDate), "already complete"), attempted = false)
        }
        val cycle = if (existing == null) {
            val id = cycleDao.insert(
                ForwardTestCycleEntity(
                    strategyRunId = runId,
                    marketDate = marketDate,
                    status = ForwardCycleStatus.RUNNING,
                    currentStage = ForwardCycleStage.PENDING_FILLS,
                    attemptCount = 1,
                    startedAt = now(),
                    createdAt = now(),
                    updatedAt = now(),
                ),
            )
            cycleDao.find(runId, marketDate)!!.copy(id = id)
        } else {
            val updated = existing.copy(
                status = ForwardCycleStatus.RUNNING,
                currentStage = ForwardCycleStage.PENDING_FILLS,
                attemptCount = existing.attemptCount + 1,
                errorCode = null,
                errorMessage = null,
                retryable = false,
                startedAt = existing.startedAt ?: now(),
                updatedAt = now(),
            )
            cycleDao.update(updated)
            updated
        }

        val startedAt = now()
        observer.onCycleStarted(cycle)
        val result = try {
            runCycleStages(runId, marketDate, allowNewOrders)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            try {
                observer.onCycleFinished(
                    cycle = cycle,
                    complete = false,
                    reasonCode = AppErrorMapper.fromThrowable(failure).code.name,
                    stage = cycleDao.find(runId, marketDate)?.currentStage,
                    elapsedMs = elapsedSince(startedAt),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (evidenceFailure: Exception) {
                failure.addSuppressed(evidenceFailure)
            }
            throw failure
        }
        observer.onCycleFinished(
            cycle = cycle,
            complete = result is ForwardOrchestratorResult.Ok,
            reasonCode = (result as? ForwardOrchestratorResult.Blocked)?.errorCode,
            stage = cycleDao.find(runId, marketDate)?.currentStage,
            elapsedMs = elapsedSince(startedAt),
        )
        return CycleOutcome(result, attempted = true)
    }

    private suspend fun runCycleStages(
        runId: Long,
        marketDate: LocalDate,
        allowNewOrders: Boolean,
    ): ForwardOrchestratorResult {
        setStage(runId, marketDate, ForwardCycleStage.PENDING_FILLS)
        processPending(runId, asOfMarketDate = marketDate)

        val instrumentIds = universeDao.findByRun(runId).map { it.instrumentId }.sorted()
        val run = strategyRunDao.findById(runId)!!
        val weights = strategyDao.findWeights(run.strategyVersionId).filter { it.enabled }

        setStage(runId, marketDate, ForwardCycleStage.FACTORS)
        calculateFactors(instrumentIds, weights, marketDate)

        setStage(runId, marketDate, ForwardCycleStage.EVALUATIONS)
        for (instrumentId in instrumentIds) {
            if (marketDailyBarDao.findByInstrumentAndDate(instrumentId, marketDate) == null) continue
            evaluateRun(runId, instrumentId, marketDate)
        }

        setStage(runId, marketDate, ForwardCycleStage.ORDER_CREATION)
        if (allowNewOrders) {
            val evaluations = evaluationDao.findByRun(runId)
                .filter { it.evaluationDate == marketDate }
                .filter {
                    it.quantDecision == TradeDecision.BUY || it.quantDecision == TradeDecision.SELL
                }
                .sortedBy { it.id }
            for (evaluation in evaluations) {
                processEvaluation(evaluation.id)
            }
        }

        setStage(runId, marketDate, ForwardCycleStage.SNAPSHOT)
        val snapshot = createSnapshot(runId, marketDate)
        val snapshotOk = snapshot.action == PaperTradeAction.SNAPSHOT_CREATED ||
            snapshot.action == PaperTradeAction.SNAPSHOT_ALREADY_EXISTS ||
            snapshotDao.find(runId, marketDate) != null
        if (!snapshotOk || snapshot.action == PaperTradeAction.SNAPSHOT_FAILED) {
            return failCycle(
                runId,
                marketDate,
                ForwardErrorCode.SNAPSHOT_MISSING_PRICE,
                sanitizeError(snapshot.message) ?: "SNAPSHOT_FAIL",
                retryable = false,
            )
        }

        val latest = cycleDao.find(runId, marketDate)!!
        cycleDao.update(
            latest.copy(
                status = ForwardCycleStatus.COMPLETE,
                currentStage = ForwardCycleStage.COMPLETE,
                errorCode = null,
                errorMessage = null,
                retryable = false,
                completedAt = now(),
                updatedAt = now(),
            ),
        )
        return ForwardOrchestratorResult.Ok(listOf(marketDate))
    }

    private suspend fun calculateFactors(
        instrumentIds: List<Long>,
        weights: List<StrategyFactorWeightEntity>,
        marketDate: LocalDate,
    ) {
        for (instrumentId in instrumentIds) {
            if (marketDailyBarDao.findByInstrumentAndDate(instrumentId, marketDate) == null) continue
            for (weight in weights.sortedBy { it.factorId }) {
                val def = factorDao.findDefinitionById(weight.factorId) ?: continue
                factorCalculation.calculateFactor(
                    instrumentId = instrumentId,
                    factorCode = def.factorCode,
                    asOfDate = marketDate,
                    persist = true,
                )
            }
        }
    }

    private suspend fun setStage(
        runId: Long,
        marketDate: LocalDate,
        stage: ForwardCycleStage,
    ) {
        val latest = cycleDao.find(runId, marketDate) ?: return
        cycleDao.update(latest.copy(currentStage = stage, updatedAt = now()))
    }

    private suspend fun failCycle(
        runId: Long,
        marketDate: LocalDate,
        code: ForwardErrorCode,
        message: String,
        retryable: Boolean,
    ): ForwardOrchestratorResult.Blocked {
        val latest = cycleDao.find(runId, marketDate)!!
        cycleDao.update(
            latest.copy(
                status = ForwardCycleStatus.FAILED,
                errorCode = code.name,
                errorMessage = sanitizeError(message),
                retryable = retryable,
                updatedAt = now(),
            ),
        )
        return ForwardOrchestratorResult.Blocked(
            marketDate = marketDate,
            errorCode = code.name,
            errorMessage = sanitizeError(message) ?: code.name,
            retryable = retryable,
        )
    }

    private suspend fun finalizeRunEnd(runId: Long, endDate: LocalDate) {
        processPending.cancelPendingAtRunEnd(runId, endDate, now())
        strategyRunDao.updateStatus(runId, RunStatus.COMPLETED, now())
    }

    private fun blocked(
        date: LocalDate?,
        code: ForwardErrorCode,
        message: String,
        retryable: Boolean,
    ) = ForwardOrchestratorResult.Blocked(date, code.name, message, retryable)

    companion object {
        fun sanitizeError(message: String?): String? {
            if (message == null) return null
            val lowered = message.lowercase()
            val secrets = listOf(
                "appkey", "appsecret", "app_key", "app_secret",
                "authorization", "bearer", "access_token", "access token",
            )
            if (secrets.any { lowered.contains(it) }) {
                return "secure error details omitted"
            }
            return message.take(500)
        }
    }
}
