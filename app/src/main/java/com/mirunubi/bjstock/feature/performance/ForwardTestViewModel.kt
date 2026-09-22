package com.mirunubi.bjstock.feature.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.analytics.DailyPerformancePoint
import com.mirunubi.bjstock.core.analytics.MonthlyPerformance
import com.mirunubi.bjstock.core.analytics.OpenPositionView
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsRepository
import com.mirunubi.bjstock.core.analytics.PerformanceAnalyticsService
import com.mirunubi.bjstock.core.analytics.PerformanceMath
import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import com.mirunubi.bjstock.core.analytics.RecentExecutionView
import com.mirunubi.bjstock.core.analytics.RunComparisonRow
import com.mirunubi.bjstock.core.analytics.RunPerformanceSummary
import com.mirunubi.bjstock.core.analytics.TradingPolicyView
import com.mirunubi.bjstock.core.database.dao.ForwardTestCycleDao
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunInstrumentDao
import com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.forward.ForwardOrchestratorResult
import com.mirunubi.bjstock.core.forward.ForwardTestClock
import com.mirunubi.bjstock.core.forward.ForwardTestOrchestrator
import com.mirunubi.bjstock.core.forward.ForwardTestScheduler
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.strategy.StrategyRunService
import com.mirunubi.bjstock.core.theme.ThemeService
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.database.entity.ThemeEntity
import com.mirunubi.bjstock.core.audit.TradeAuditLogService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ForwardOpsStatus {
    UP_TO_DATE,
    CATCHING_UP,
    WAITING_FOR_MARKET_DATA,
    FAILED,
    BLOCKED,
}

data class UniverseInstrumentView(
    val instrumentId: Long,
    val symbol: String,
    val name: String,
)

data class ForwardTestUiState(
    val runs: List<StrategyRunEntity> = emptyList(),
    val selectedRunId: Long? = null,
    val summary: RunPerformanceSummary? = null,
    val dailySeries: List<DailyPerformancePoint> = emptyList(),
    val monthly: List<MonthlyPerformance> = emptyList(),
    val openPositions: List<OpenPositionView> = emptyList(),
    val recentExecutions: List<RecentExecutionView> = emptyList(),
    val policy: TradingPolicyView? = null,
    val compareCandidates: List<StrategyRunEntity> = emptyList(),
    val selectedCompareIds: Set<Long> = emptySet(),
    val comparisonRows: List<RunComparisonRow> = emptyList(),
    val autoEnabled: Boolean = false,
    val lastCompleteDate: LocalDate? = null,
    val latestMarketDate: LocalDate? = null,
    val opsStatus: ForwardOpsStatus? = null,
    val universe: List<UniverseInstrumentView> = emptyList(),
    val universeEditable: Boolean = false,
    val cycleHistory: List<ForwardTestCycleEntity> = emptyList(),
    val instrumentSearch: String = "",
    val instrumentSearchResults: List<InstrumentEntity> = emptyList(),
    val activeThemes: List<ThemeEntity> = emptyList(),
    val tradeTimeline: List<TradeAuditLogEntity> = emptyList(),
    val message: String? = null,
    val loading: Boolean = false,
)

@HiltViewModel
class ForwardTestViewModel @Inject constructor(
    private val analytics: PerformanceAnalyticsService,
    private val repository: PerformanceAnalyticsRepository,
    private val orchestrator: ForwardTestOrchestrator,
    private val scheduler: ForwardTestScheduler,
    private val runService: StrategyRunService,
    private val cycleDao: ForwardTestCycleDao,
    private val universeDao: StrategyRunInstrumentDao,
    private val instrumentDao: InstrumentDao,
    private val marketDailyBarDao: MarketDailyBarDao,
    private val clock: ForwardTestClock,
    private val themeService: ThemeService,
    private val tradeAuditLogService: TradeAuditLogService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ForwardTestUiState())
    val uiState: StateFlow<ForwardTestUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { reloadRuns() }
    }

    fun selectRun(runId: Long) {
        viewModelScope.launch { loadDashboard(runId) }
    }

    fun toggleAuto(enabled: Boolean) {
        scheduler.setAutoEnabled(enabled)
        _uiState.update { it.copy(autoEnabled = scheduler.isAutoEnabled()) }
    }

    fun runNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = null) }
            val result = orchestrator.runForwardTests()
            _uiState.update {
                it.copy(
                    loading = false,
                    message = formatResult(result),
                )
            }
            _uiState.value.selectedRunId?.let { loadDashboard(it) }
                ?: reloadRuns()
        }
    }

    fun retryFailedCycle() {
        val runId = _uiState.value.selectedRunId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = null) }
            val result = orchestrator.retryFailedCycle(runId)
            _uiState.update {
                it.copy(
                    loading = false,
                    message = formatResult(result),
                )
            }
            loadDashboard(runId)
        }
    }

    fun setInstrumentSearch(query: String) {
        _uiState.update { it.copy(instrumentSearch = query) }
        viewModelScope.launch {
            val results = if (query.isBlank()) {
                emptyList()
            } else {
                instrumentDao.searchActive("%${query.trim()}%", limit = 20)
            }
            _uiState.update { it.copy(instrumentSearchResults = results) }
        }
    }

    fun addInstrument(instrumentId: Long) {
        val runId = _uiState.value.selectedRunId ?: return
        viewModelScope.launch {
            runCatching { runService.addInstrument(runId, instrumentId) }
                .onFailure { e ->
                    _uiState.update { it.copy(message = e.message) }
                }
            loadDashboard(runId)
        }
    }

    fun removeInstrument(instrumentId: Long) {
        val runId = _uiState.value.selectedRunId ?: return
        viewModelScope.launch {
            runCatching { runService.removeInstrument(runId, instrumentId) }
                .onFailure { e ->
                    _uiState.update { it.copy(message = e.message) }
                }
            loadDashboard(runId)
        }
    }

    fun addThemeToUniverse(themeId: Long) {
        val runId = _uiState.value.selectedRunId ?: return
        viewModelScope.launch {
            runCatching { runService.addThemeToUniverse(runId, themeId) }
                .onSuccess { added ->
                    _uiState.update { it.copy(message = "Added $added instrument(s) from theme") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(message = e.message) }
                }
            loadDashboard(runId)
        }
    }

    fun toggleCompare(runId: Long) {
        _uiState.update { state ->
            val next = state.selectedCompareIds.toMutableSet()
            if (!next.add(runId)) next.remove(runId)
            if (next.size > 3) {
                return@update state.copy(message = "Compare up to 3 runs")
            }
            state.copy(selectedCompareIds = next, message = null)
        }
    }

    fun loadComparison() {
        val ids = _uiState.value.selectedCompareIds.toList()
        if (ids.size < 2) {
            _uiState.update { it.copy(message = "Select at least 2 runs to compare") }
            return
        }
        viewModelScope.launch {
            val rows = analytics.compareRuns(ids)
            _uiState.update { it.copy(comparisonRows = rows, message = null) }
        }
    }

    private suspend fun reloadRuns() {
        val runs = repository.loadAllRuns().sortedBy { it.id }
        _uiState.update {
            it.copy(
                runs = runs,
                compareCandidates = runs,
                selectedRunId = it.selectedRunId ?: runs.firstOrNull()?.id,
                autoEnabled = scheduler.isAutoEnabled(),
            )
        }
        _uiState.value.selectedRunId?.let { loadDashboard(it) }
    }

    private suspend fun loadDashboard(runId: Long) {
        _uiState.update { it.copy(loading = true, selectedRunId = runId, message = null) }
        val before = repository.countFingerprint(runId)
        val summary = analytics.calculateSummary(runId)
        val series = if (summary.status == PerformanceStatus.DATA_ERROR) {
            emptyList()
        } else {
            runCatching { analytics.calculateDailySeries(runId) }.getOrDefault(emptyList())
        }
        val monthly = if (summary.status == PerformanceStatus.DATA_ERROR) {
            emptyList()
        } else {
            runCatching { analytics.calculateMonthlyReturns(runId) }.getOrDefault(emptyList())
        }
        val positions = analytics.loadOpenPositionViews(runId)
        val executions = analytics.loadRecentExecutions(runId)
        val policy = analytics.loadTradingPolicy(runId)
        val after = repository.countFingerprint(runId)
        val mutationWarning = if (before != after) {
            "Analytics mutated Room data — unexpected"
        } else {
            null
        }

        val run = runService.findById(runId)
        val universeRows = universeDao.findByRun(runId)
        val universeViews = universeRows.mapNotNull { row ->
            val instrument = instrumentDao.findById(row.instrumentId) ?: return@mapNotNull null
            UniverseInstrumentView(
                instrumentId = instrument.id,
                symbol = instrument.symbol,
                name = instrument.name,
            )
        }
        val lastComplete = cycleDao.findLastCompleteDate(runId)
        val latestMarket = universeRows.mapNotNull { row ->
            marketDailyBarDao.findLatest(row.instrumentId)?.tradeDate
        }.maxOrNull()
        val cycles = cycleDao.findRecentByRun(runId, limit = 30)
        val failed = cycleDao.findOldestByStatus(runId, ForwardCycleStatus.FAILED)
        val throughDate = clock.throughDate(run?.endDate)
        val opsStatus = resolveOpsStatus(
            run = run,
            lastComplete = lastComplete,
            latestMarket = latestMarket,
            throughDate = throughDate,
            failed = failed,
        )

        val themes = themeService.listActiveThemes()
        val timeline = tradeAuditLogService.findRecentByRun(runId, limit = 50)
        _uiState.update {
            it.copy(
                loading = false,
                summary = summary,
                dailySeries = series,
                monthly = monthly,
                openPositions = positions,
                recentExecutions = executions,
                policy = policy,
                autoEnabled = scheduler.isAutoEnabled(),
                lastCompleteDate = lastComplete,
                latestMarketDate = latestMarket,
                opsStatus = opsStatus,
                universe = universeViews,
                universeEditable = run?.status == RunStatus.DRAFT,
                cycleHistory = cycles,
                activeThemes = themes,
                tradeTimeline = timeline,
                message = mutationWarning ?: summary.errorMessage,
            )
        }
    }

    private fun resolveOpsStatus(
        run: StrategyRunEntity?,
        lastComplete: LocalDate?,
        latestMarket: LocalDate?,
        throughDate: LocalDate,
        failed: ForwardTestCycleEntity?,
    ): ForwardOpsStatus {
        if (failed != null && !failed.retryable) return ForwardOpsStatus.BLOCKED
        if (failed != null) return ForwardOpsStatus.FAILED
        if (run == null || run.status == RunStatus.DRAFT) {
            return ForwardOpsStatus.WAITING_FOR_MARKET_DATA
        }
        if (latestMarket == null) return ForwardOpsStatus.WAITING_FOR_MARKET_DATA
        val target = if (run.endDate != null && run.endDate.isBefore(throughDate)) {
            run.endDate
        } else {
            minOf(throughDate, latestMarket)
        }
        if (lastComplete == null) {
            return if (latestMarket < run.startDate) {
                ForwardOpsStatus.WAITING_FOR_MARKET_DATA
            } else {
                ForwardOpsStatus.CATCHING_UP
            }
        }
        return if (!lastComplete.isBefore(target)) {
            ForwardOpsStatus.UP_TO_DATE
        } else {
            ForwardOpsStatus.CATCHING_UP
        }
    }

    private fun formatResult(result: ForwardOrchestratorResult): String = when (result) {
        is ForwardOrchestratorResult.Ok ->
            "Processed ${result.processedDates.size} day(s)" +
                (result.message?.let { " — $it" } ?: "")
        is ForwardOrchestratorResult.Blocked ->
            "Blocked ${result.marketDate ?: ""} ${result.errorCode}: ${result.errorMessage}"
        is ForwardOrchestratorResult.NoOp -> result.reason
    }

    companion object {
        fun formatPercent(rate: BigDecimal?): String =
            rate?.let { PerformanceMath.formatSignedPercent(it) } ?: "N/A"

        fun formatWon(value: Long?): String =
            value?.let { PerformanceMath.formatWon(it) } ?: "N/A"

        fun formatRateAsAssumption(rate: BigDecimal): String =
            PerformanceMath.formatSignedPercent(rate).removePrefix("+")

        fun formatOpsStatus(status: ForwardOpsStatus?): String = when (status) {
            ForwardOpsStatus.UP_TO_DATE -> "UP TO DATE"
            ForwardOpsStatus.CATCHING_UP -> "CATCHING UP"
            ForwardOpsStatus.WAITING_FOR_MARKET_DATA -> "WAITING FOR MARKET DATA"
            ForwardOpsStatus.FAILED -> "FAILED"
            ForwardOpsStatus.BLOCKED -> "BLOCKED"
            null -> "—"
        }
    }
}
