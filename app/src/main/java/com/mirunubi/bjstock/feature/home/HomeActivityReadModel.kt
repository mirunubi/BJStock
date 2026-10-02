package com.mirunubi.bjstock.feature.home

import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsRepository
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.TradeAuditLogDao
import com.mirunubi.bjstock.core.database.entity.ExecutionEntity
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.feature.admin.AdminAuditData
import com.mirunubi.bjstock.feature.admin.AdminDataSource
import com.mirunubi.bjstock.feature.admin.AdminErrorData
import com.mirunubi.bjstock.feature.paper.InstrumentLabel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import java.time.LocalDate
import javax.inject.Inject

data class HomeRunRef(
    val runId: Long,
    val runName: String,
    val strategyName: String,
    val versionLabel: String,
    val status: RunStatus,
    val initialCash: Long,
)

/** Strategy audit rows of the most recent processed market date found in the scanned window. */
data class HomeSignalData(
    val marketDate: LocalDate?,
    /** Newest first. */
    val logs: List<TradeAuditLogEntity>,
    val decisions: Map<Long, TradeDecision>,
    val instruments: Map<Long, InstrumentLabel>,
    val runs: Map<Long, HomeRunRef>,
)

data class HomeTradeData(
    /** Newest first, at most [RoomHomeActivitySource.TRADE_LIMIT]. */
    val orders: List<OrderEntity>,
    val totalOrders: Int,
    val executions: Map<Long, ExecutionEntity>,
    val instruments: Map<Long, InstrumentLabel>,
)

/** Read-only summaries for the Home activity cards. Each call is independent so one failure cannot blank the others. */
interface HomeActivitySource {
    suspend fun activeRuns(): List<HomeRunRef>

    suspend fun signals(): HomeSignalData

    suspend fun trades(): HomeTradeData

    suspend fun errors(): AdminErrorData

    suspend fun audit(): AdminAuditData
}

/** Composes existing read APIs (analytics repository, 운영 · 감사 data source, audit/evaluation DAOs). Never writes. */
class RoomHomeActivitySource @Inject constructor(
    private val repository: PerformanceAnalyticsRepository,
    private val admin: AdminDataSource,
    private val auditDao: TradeAuditLogDao,
    private val evaluationDao: StockEvaluationDao,
    private val instrumentDao: InstrumentDao,
) : HomeActivitySource {
    override suspend fun activeRuns(): List<HomeRunRef> =
        repository.loadAllRuns()
            .filter { it.status in ACTIVE_STATUSES }
            .sortedWith(compareBy<StrategyRunEntity> { ACTIVE_STATUSES.indexOf(it.status) }.thenByDescending { it.id })
            .map { ref(it) }

    override suspend fun signals(): HomeSignalData {
        val strategyLogs = auditDao.findRecent(SIGNAL_SCAN_LIMIT)
            .filter { it.eventType in SIGNAL_TYPES && it.marketDate != null }
        val date = strategyLogs.maxOfOrNull { it.marketDate!! }
        val logs = strategyLogs.filter { it.marketDate == date }
        return HomeSignalData(
            marketDate = date,
            logs = logs,
            decisions = logs.mapNotNull { it.evaluationId }.distinct().mapNotNull { id ->
                evaluationDao.findEvaluationById(id)?.let { id to it.finalDecision }
            }.toMap(),
            instruments = instruments(logs.mapNotNull { it.instrumentId }),
            runs = logs.map { it.strategyRunId }.distinct().mapNotNull { id ->
                repository.loadRun(id)?.let { id to ref(it) }
            }.toMap(),
        )
    }

    override suspend fun trades(): HomeTradeData {
        val all = repository.loadAllRuns().flatMap { repository.loadOrders(it.id) }
            .sortedWith(compareByDescending<OrderEntity> { it.createdAt }.thenByDescending { it.id })
        val recent = all.take(TRADE_LIMIT)
        val recentIds = recent.map { it.id }.toSet()
        val executions = recent.map { it.strategyRunId }.distinct()
            .flatMap { repository.loadExecutions(it) }
            .filter { it.orderId in recentIds }
            .associateBy { it.orderId }
        return HomeTradeData(
            orders = recent,
            totalOrders = all.size,
            executions = executions,
            instruments = instruments(recent.map { it.instrumentId }),
        )
    }

    override suspend fun errors(): AdminErrorData = admin.errors()

    override suspend fun audit(): AdminAuditData = admin.audit()

    private suspend fun ref(run: StrategyRunEntity): HomeRunRef {
        val (strategyName, versionLabel) = repository.loadStrategyLabel(run)
        return HomeRunRef(run.id, run.runName, strategyName, versionLabel, run.status, run.initialCash)
    }

    private suspend fun instruments(ids: List<Long>): Map<Long, InstrumentLabel> =
        ids.distinct().mapNotNull { id ->
            instrumentDao.findById(id)?.let { id to InstrumentLabel(id, it.symbol, it.name) }
        }.toMap()

    companion object {
        const val SIGNAL_SCAN_LIMIT = 100
        const val TRADE_LIMIT = 3
        val ACTIVE_STATUSES = listOf(RunStatus.RUNNING, RunStatus.READY)
        val SIGNAL_TYPES = setOf(TradeAuditEventType.RULE_TRIGGERED, TradeAuditEventType.EVALUATION_DECIDED)
    }
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class HomeActivityModule {
    @Binds
    abstract fun bindHomeActivitySource(source: RoomHomeActivitySource): HomeActivitySource
}
