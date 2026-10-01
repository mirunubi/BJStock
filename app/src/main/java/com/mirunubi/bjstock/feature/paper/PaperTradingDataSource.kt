package com.mirunubi.bjstock.feature.paper

import com.mirunubi.bjstock.core.analytics.OpenPositionView
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsRepository
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsService
import com.mirunubi.bjstock.core.analytics.RecentExecutionView
import com.mirunubi.bjstock.core.analytics.RunPerformanceSummary
import com.mirunubi.bjstock.core.analytics.TradingPolicyView
import com.mirunubi.bjstock.core.audit.TradeAuditLogService
import com.mirunubi.bjstock.core.database.dao.ForwardOperationDao
import com.mirunubi.bjstock.core.database.dao.ForwardTestCycleDao
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunInstrumentDao
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.database.entity.ThemeEntity
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.forward.AutoScheduleStatus
import com.mirunubi.bjstock.core.forward.ForwardOperationOutcome
import com.mirunubi.bjstock.core.forward.ForwardTestClock
import com.mirunubi.bjstock.core.forward.ForwardTestExecutionCoordinator
import com.mirunubi.bjstock.core.forward.ForwardTestScheduler
import com.mirunubi.bjstock.core.forward.RetryFailedCycleTarget
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.strategy.ActiveStrategyVersion
import com.mirunubi.bjstock.core.strategy.StrategyRunService
import com.mirunubi.bjstock.core.theme.ThemeService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

data class InstrumentLabel(val instrumentId: Long, val symbol: String, val name: String)

data class PaperRunData(
    val run: StrategyRunEntity,
    val strategyName: String,
    val versionLabel: String,
    val summary: RunPerformanceSummary,
)

data class PaperRunDetailData(
    val run: PaperRunData,
    val positions: List<OpenPositionView>,
    val executions: List<RecentExecutionView>,
    /** Newest first. */
    val orders: List<OrderEntity>,
    val policy: TradingPolicyView?,
    val universe: List<InstrumentLabel>,
    /** Every instrument referenced by positions, orders, universe or the timeline, by id. */
    val instruments: Map<Long, InstrumentLabel>,
    val cycles: List<ForwardTestCycleEntity>,
    val failedCycle: ForwardTestCycleEntity?,
    val timeline: List<TradeAuditLogEntity>,
    /** Active themes, loaded only while the Run is DRAFT. */
    val themes: List<ThemeEntity>,
)

data class PaperAutomationData(
    val status: AutoScheduleStatus,
    val operations: List<ForwardOperationEntity>,
    val now: Instant,
)

/**
 * Data for the 모의투자 tab. Read functions never write. Each write maps to one confirmed user action and
 * delegates to the existing service that owns it (StrategyRunService, ForwardTestScheduler,
 * ForwardTestExecutionCoordinator).
 */
interface PaperTradingDataSource {
    suspend fun runs(): List<PaperRunData>

    suspend fun detail(runId: Long): PaperRunDetailData?

    suspend fun automation(): PaperAutomationData

    suspend fun activeVersions(): List<ActiveStrategyVersion>

    suspend fun searchInstruments(query: String): List<InstrumentEntity>

    fun today(): LocalDate

    suspend fun createDraftRun(strategyVersionId: Long, runName: String, startDate: LocalDate, initialCashWon: Long): Long

    suspend fun addInstrument(runId: Long, instrumentId: Long)

    suspend fun removeInstrument(runId: Long, instrumentId: Long)

    suspend fun addTheme(runId: Long, themeId: Long): Int

    suspend fun markReady(runId: Long)

    suspend fun setAutoEnabled(enabled: Boolean)

    suspend fun runNow(): ForwardOperationOutcome

    suspend fun retryFailedCycle(runId: Long, marketDate: LocalDate, cycleId: Long): ForwardOperationOutcome
}

class RoomPaperTradingDataSource @Inject constructor(
    private val repository: PerformanceAnalyticsRepository,
    private val analytics: PerformanceAnalyticsService,
    private val runService: StrategyRunService,
    private val scheduler: ForwardTestScheduler,
    private val coordinator: ForwardTestExecutionCoordinator,
    private val clock: ForwardTestClock,
    private val operationDao: ForwardOperationDao,
    private val cycleDao: ForwardTestCycleDao,
    private val universeDao: StrategyRunInstrumentDao,
    private val instrumentDao: InstrumentDao,
    private val themeService: ThemeService,
    private val tradeAuditLog: TradeAuditLogService,
) : PaperTradingDataSource {
    override suspend fun runs(): List<PaperRunData> = repository.loadAllRuns().map { runData(it) }

    override suspend fun detail(runId: Long): PaperRunDetailData? {
        val run = repository.loadRun(runId) ?: return null
        val positions = analytics.loadOpenPositionViews(runId)
        val orders = repository.loadOrders(runId).sortedWith(
            compareByDescending<OrderEntity> { it.createdAt }.thenByDescending { it.id },
        )
        val universeIds = universeDao.findByRun(runId).map { it.instrumentId }
        val timeline = tradeAuditLog.findRecentByRun(runId, limit = TIMELINE_LIMIT)
        val instrumentIds = (positions.map { it.instrumentId } + orders.map { it.instrumentId } + universeIds +
            timeline.mapNotNull { it.instrumentId }).toSet()
        val instruments = instrumentIds.mapNotNull { id ->
            instrumentDao.findById(id)?.let { id to InstrumentLabel(id, it.symbol, it.name) }
        }.toMap()
        return PaperRunDetailData(
            run = runData(run),
            positions = positions,
            executions = analytics.loadRecentExecutions(runId, limit = EXECUTION_LIMIT),
            orders = orders,
            policy = analytics.loadTradingPolicy(runId),
            universe = universeIds.mapNotNull { instruments[it] },
            instruments = instruments,
            cycles = cycleDao.findRecentByRun(runId, limit = CYCLE_LIMIT),
            failedCycle = cycleDao.findOldestByStatus(runId, ForwardCycleStatus.FAILED),
            timeline = timeline,
            themes = if (run.status == RunStatus.DRAFT) themeService.listActiveThemes() else emptyList(),
        )
    }

    override suspend fun automation(): PaperAutomationData = PaperAutomationData(
        status = scheduler.status(),
        operations = operationDao.findRecent(limit = OPERATION_LIMIT),
        now = clock.nowInstant(),
    )

    override suspend fun activeVersions(): List<ActiveStrategyVersion> = runService.listActiveVersions()

    override suspend fun searchInstruments(query: String): List<InstrumentEntity> =
        instrumentDao.searchActive("%${query.trim()}%", limit = SEARCH_LIMIT)

    override fun today(): LocalDate = clock.nowSeoul().toLocalDate()

    override suspend fun createDraftRun(
        strategyVersionId: Long,
        runName: String,
        startDate: LocalDate,
        initialCashWon: Long,
    ): Long = runService.createDraftRun(strategyVersionId, runName, startDate, initialCashWon)

    override suspend fun addInstrument(runId: Long, instrumentId: Long) = runService.addInstrument(runId, instrumentId)

    override suspend fun removeInstrument(runId: Long, instrumentId: Long) = runService.removeInstrument(runId, instrumentId)

    override suspend fun addTheme(runId: Long, themeId: Long): Int = runService.addThemeToUniverse(runId, themeId)

    override suspend fun markReady(runId: Long) = runService.markReady(runId)

    override suspend fun setAutoEnabled(enabled: Boolean) {
        scheduler.setAutoEnabled(enabled)
    }

    override suspend fun runNow(): ForwardOperationOutcome = coordinator.runManualNow()

    override suspend fun retryFailedCycle(runId: Long, marketDate: LocalDate, cycleId: Long): ForwardOperationOutcome =
        coordinator.retryFailedCycle(RetryFailedCycleTarget(runId = runId, marketDate = marketDate, expectedCycleId = cycleId))

    private suspend fun runData(run: StrategyRunEntity): PaperRunData {
        val (strategyName, versionLabel) = repository.loadStrategyLabel(run)
        return PaperRunData(run, strategyName, versionLabel, analytics.calculateSummary(run.id))
    }

    private companion object {
        const val TIMELINE_LIMIT = 30
        const val EXECUTION_LIMIT = 10
        const val CYCLE_LIMIT = 10
        const val OPERATION_LIMIT = 10
        const val SEARCH_LIMIT = 20
    }
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class PaperTradingScreenModule {
    @Binds
    abstract fun bindPaperTradingDataSource(source: RoomPaperTradingDataSource): PaperTradingDataSource
}
