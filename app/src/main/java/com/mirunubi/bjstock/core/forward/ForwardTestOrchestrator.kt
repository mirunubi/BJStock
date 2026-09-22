package com.mirunubi.bjstock.core.forward

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
import com.mirunubi.bjstock.core.factor.FactorCalculationService
import com.mirunubi.bjstock.core.model.ForwardCycleStage
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
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

/**
 * Long-term forward-test orchestration shared by WorkManager and Manual Run Now.
 * Does not call AI Advisory.
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
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun runForwardTests(throughDate: LocalDate? = null): ForwardOrchestratorResult {
        val runs = strategyRunDao.findAll()
            .filter { it.status == RunStatus.READY || it.status == RunStatus.RUNNING }
            .sortedBy { it.id }
        if (runs.isEmpty()) return ForwardOrchestratorResult.NoOp("no READY/RUNNING runs")
        var last: ForwardOrchestratorResult = ForwardOrchestratorResult.NoOp("no work")
        for (run in runs) {
            val result = runSingleStrategyRun(run.id, throughDate)
            last = result
            if (result is ForwardOrchestratorResult.Blocked && !result.retryable) {
                return result
            }
        }
        return last
    }

    suspend fun runSingleStrategyRun(
        runId: Long,
        throughDate: LocalDate? = null,
    ): ForwardOrchestratorResult {
        val run = strategyRunDao.findById(runId)
            ?: return blocked(null, ForwardErrorCode.INVALID_RUN, "strategy run not found", false)
        if (run.status !in setOf(RunStatus.READY, RunStatus.RUNNING)) {
            return ForwardOrchestratorResult.NoOp("run status ${run.status}")
        }
        if (universeDao.countByRun(runId) <= 0) {
            return blocked(null, ForwardErrorCode.EMPTY_UNIVERSE, "universe empty", false)
        }
        try {
            policyService.requireByRun(runId)
        } catch (_: MissingTradingPolicyException) {
            return blocked(null, ForwardErrorCode.MISSING_TRADING_POLICY, "MISSING_TRADING_POLICY", false)
        }
        if (!marketData.ensureCredentials()) {
            return blocked(null, ForwardErrorCode.AUTH_REQUIRED, "KIS credentials required", false)
        }

        val effectiveThrough = throughDate ?: clock.throughDate(run.endDate)
        if (effectiveThrough.isBefore(run.startDate)) {
            return ForwardOrchestratorResult.NoOp("throughDate before start_date")
        }

        val blockedCycle = cycleDao.findOldestByStatus(runId, ForwardCycleStatus.FAILED)
        if (blockedCycle != null && !blockedCycle.retryable) {
            return ForwardOrchestratorResult.Blocked(
                marketDate = blockedCycle.marketDate,
                errorCode = blockedCycle.errorCode ?: ForwardErrorCode.CYCLE_FAILED.name,
                errorMessage = blockedCycle.errorMessage ?: "blocked failed cycle",
                retryable = false,
            )
        }

        val instrumentIds = universeDao.findByRun(runId).map { it.instrumentId }.sorted()
        val sync = marketData.syncUniverseTo(instrumentIds, effectiveThrough)
        if (!sync.success) {
            return ForwardOrchestratorResult.Blocked(
                marketDate = null,
                errorCode = sync.errorCode ?: ForwardErrorCode.NETWORK_FAILURE.name,
                errorMessage = sanitizeError(sync.errorMessage) ?: "network failure",
                retryable = sync.retryable,
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
            return ForwardOrchestratorResult.NoOp("WAITING FOR MARKET DATA")
        }

        if (run.status == RunStatus.READY) {
            strategyRunDao.updateStatus(runId, RunStatus.RUNNING, now())
        }

        val processed = mutableListOf<LocalDate>()
        for (marketDate in dates) {
            val result = processCycle(
                runId = runId,
                marketDate = marketDate,
                allowNewOrders = run.endDate == null || marketDate.isBefore(run.endDate),
            )
            if (result is ForwardOrchestratorResult.Blocked) return result
            processed += marketDate
            if (run.endDate != null && marketDate == run.endDate) {
                finalizeRunEnd(runId)
                break
            }
        }
        return ForwardOrchestratorResult.Ok(processedDates = processed)
    }

    suspend fun retryFailedCycle(
        runId: Long,
        marketDate: LocalDate? = null,
        throughDate: LocalDate? = null,
    ): ForwardOrchestratorResult {
        val failed = if (marketDate != null) {
            cycleDao.find(runId, marketDate)
        } else {
            cycleDao.findOldestByStatus(runId, ForwardCycleStatus.FAILED)
        } ?: return ForwardOrchestratorResult.NoOp("no failed cycle")
        if (failed.status != ForwardCycleStatus.FAILED) {
            return ForwardOrchestratorResult.NoOp("cycle not failed")
        }
        val run = strategyRunDao.findById(runId)
            ?: return blocked(null, ForwardErrorCode.INVALID_RUN, "run not found", false)
        val result = processCycle(
            runId = runId,
            marketDate = failed.marketDate,
            allowNewOrders = run.endDate == null || failed.marketDate.isBefore(run.endDate),
        )
        if (result is ForwardOrchestratorResult.Ok) {
            return runSingleStrategyRun(runId, throughDate)
        }
        return result
    }

    private suspend fun processCycle(
        runId: Long,
        marketDate: LocalDate,
        allowNewOrders: Boolean,
    ): ForwardOrchestratorResult {
        val existing = cycleDao.find(runId, marketDate)
        if (existing?.status == ForwardCycleStatus.COMPLETE) {
            return ForwardOrchestratorResult.Ok(listOf(marketDate), "already complete")
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

    private suspend fun finalizeRunEnd(runId: Long) {
        for (order in orderDao.findByRunAndStatus(runId, OrderStatus.PENDING_EXECUTION)) {
            orderDao.update(
                order.copy(status = OrderStatus.CANCELLED, cancelledAt = now()),
            )
        }
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
