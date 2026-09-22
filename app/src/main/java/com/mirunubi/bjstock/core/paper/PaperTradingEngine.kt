package com.mirunubi.bjstock.core.paper

import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import java.time.Instant
import java.time.LocalDate

/**
 * Facade for manual / lab-driven paper trading steps. No network. No WorkManager.
 */
class PaperTradingEngine(
    private val strategyRunDao: StrategyRunDao,
    private val evaluationDao: StockEvaluationDao,
    private val processEvaluation: ProcessEvaluationUseCase,
    private val processPending: ProcessPendingOrdersUseCase,
    private val createSnapshot: CreateDailySnapshotUseCase,
    private val cashLedger: CashLedgerService,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun ensureRunning(strategyRunId: Long) {
        val run = strategyRunDao.findById(strategyRunId) ?: return
        if (run.status == RunStatus.READY) {
            strategyRunDao.updateStatus(strategyRunId, RunStatus.RUNNING, now())
        }
    }

    suspend fun processEvaluations(strategyRunId: Long): List<PaperTradeResult> {
        ensureRunning(strategyRunId)
        val evaluations = evaluationDao.findByRun(strategyRunId)
            .sortedWith(compareBy({ it.evaluationDate }, { it.id }))
            .filter {
                it.quantDecision == TradeDecision.BUY || it.quantDecision == TradeDecision.SELL
            }
        return evaluations.map { processEvaluation(it.id) }
    }

    suspend fun processPendingOrders(strategyRunId: Long): List<PaperTradeResult> {
        ensureRunning(strategyRunId)
        return processPending(strategyRunId)
    }

    suspend fun createDailySnapshot(
        strategyRunId: Long,
        snapshotDate: LocalDate,
    ): PaperTradeResult = createSnapshot(strategyRunId, snapshotDate)

    suspend fun currentCash(strategyRunId: Long): Long = cashLedger.currentCash(strategyRunId)
}
