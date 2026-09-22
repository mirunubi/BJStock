package com.mirunubi.bjstock.core.analytics

import com.mirunubi.bjstock.core.database.dao.CashLedgerDao
import com.mirunubi.bjstock.core.database.dao.ExecutionDao
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.dao.OrderDao
import com.mirunubi.bjstock.core.database.dao.PaperTradingPolicyDao
import com.mirunubi.bjstock.core.database.dao.PortfolioDailySnapshotDao
import com.mirunubi.bjstock.core.database.dao.PositionDao
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.StrategyDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.entity.ExecutionEntity
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.PaperTradingPolicyEntity
import com.mirunubi.bjstock.core.database.entity.PortfolioDailySnapshotEntity
import com.mirunubi.bjstock.core.database.entity.PositionEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity

/**
 * Read-only queries for performance analytics. Never writes.
 */
class PerformanceAnalyticsRepository(
    private val strategyRunDao: StrategyRunDao,
    private val strategyDao: StrategyDao,
    private val snapshotDao: PortfolioDailySnapshotDao,
    private val orderDao: OrderDao,
    private val executionDao: ExecutionDao,
    private val positionDao: PositionDao,
    private val cashLedgerDao: CashLedgerDao,
    private val evaluationDao: StockEvaluationDao,
    private val policyDao: PaperTradingPolicyDao,
    private val instrumentDao: InstrumentDao,
    private val marketDailyBarDao: MarketDailyBarDao,
) {
    suspend fun loadRun(runId: Long): StrategyRunEntity? = strategyRunDao.findById(runId)

    suspend fun loadAllRuns(): List<StrategyRunEntity> = strategyRunDao.findAll()

    suspend fun loadSnapshots(runId: Long): List<PortfolioDailySnapshotEntity> =
        snapshotDao.findByRun(runId)

    suspend fun loadOrders(runId: Long): List<OrderEntity> = orderDao.findByRun(runId)

    suspend fun loadExecutions(runId: Long): List<ExecutionEntity> = executionDao.findByRun(runId)

    suspend fun loadPositions(runId: Long): List<PositionEntity> = positionDao.findByRun(runId)

    suspend fun loadOpenPositions(runId: Long): List<PositionEntity> =
        positionDao.findOpenByRun(runId)

    suspend fun loadEvaluations(runId: Long): List<StockEvaluationEntity> =
        evaluationDao.findByRun(runId)

    suspend fun loadPolicy(runId: Long): PaperTradingPolicyEntity? = policyDao.findByRun(runId)

    suspend fun loadStrategyLabel(run: StrategyRunEntity): Pair<String, String> {
        val version = strategyDao.findVersionById(run.strategyVersionId)
        val strategy = version?.let { strategyDao.findStrategyById(it.strategyId) }
        val name = strategy?.strategyName ?: strategy?.strategyCode ?: "Unknown"
        val versionLabel = version?.let { "V${it.versionNo}" } ?: "?"
        return name to versionLabel
    }

    suspend fun loadInstrumentSymbol(instrumentId: Long): String =
        instrumentDao.findById(instrumentId)?.symbol ?: instrumentId.toString()

    suspend fun loadLatestClose(instrumentId: Long): Long? =
        marketDailyBarDao.findLatest(instrumentId)?.closePrice

    suspend fun countFingerprint(runId: Long): AnalyticsFingerprint =
        AnalyticsFingerprint(
            orders = orderDao.countByRun(runId),
            executions = executionDao.countByRun(runId),
            cashLedger = cashLedgerDao.countByRun(runId),
            positions = positionDao.findByRun(runId).size,
            snapshots = snapshotDao.countByRun(runId),
            evaluations = evaluationDao.findByRun(runId).size,
        )
}

data class AnalyticsFingerprint(
    val orders: Int,
    val executions: Int,
    val cashLedger: Int,
    val positions: Int,
    val snapshots: Int,
    val evaluations: Int,
)
