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
import com.mirunubi.bjstock.core.error.AppErrorMapper
import com.mirunubi.bjstock.core.forward.ForwardOperationOutcome
import com.mirunubi.bjstock.core.forward.ForwardOrchestratorResult
import com.mirunubi.bjstock.core.forward.ForwardTestClock
import com.mirunubi.bjstock.core.forward.ForwardTestExecutionCoordinator
import com.mirunubi.bjstock.core.forward.ForwardTestScheduler
import com.mirunubi.bjstock.core.forward.RetryFailedCycleTarget
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.strategy.ActiveStrategyVersion
import com.mirunubi.bjstock.core.strategy.StrategyRunService
import com.mirunubi.bjstock.core.theme.ThemeService
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.database.entity.ThemeEntity
import com.mirunubi.bjstock.core.audit.TradeAuditLogService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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

data class ReadyConfirmation(
    val runId: Long,
    val runName: String,
    val strategyLabel: String,
    val startDate: LocalDate,
    val initialCash: Long,
    val universeCount: Int,
)

data class ForwardTestUiState(
    val runs: List<StrategyRunEntity> = emptyList(),
    val selectedRunId: Long? = null,
    val selectedRunStatus: RunStatus? = null,
    val readyConfirmation: ReadyConfirmation? = null,
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
    val activeVersions: List<ActiveStrategyVersion> = emptyList(),
    val draftVersionId: Long? = null,
    val draftRunName: String = "",
    val draftStartDate: String = "",
    val draftInitialCash: String = "100000000",
    val message: String? = null,
    val loading: Boolean = false,
) {
    val canMarkReady: Boolean
        get() = selectedRunStatus == RunStatus.DRAFT
}

@HiltViewModel
class ForwardTestViewModel @Inject constructor(
    private val analytics: PerformanceAnalyticsService,
    private val repository: PerformanceAnalyticsRepository,
    private val coordinator: ForwardTestExecutionCoordinator,
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
        _uiState.update { it.copy(draftStartDate = clock.nowSeoul().toLocalDate().toString()) }
        viewModelScope.launch { reloadRuns() }
    }

    fun selectRun(runId: Long) {
        viewModelScope.launch { loadDashboard(runId) }
    }

    fun onDraftVersionSelected(strategyVersionId: Long) =
        _uiState.update { it.copy(draftVersionId = strategyVersionId) }

    fun onDraftRunNameChanged(value: String) = _uiState.update { it.copy(draftRunName = value) }

    fun onDraftStartDateChanged(value: String) = _uiState.update { it.copy(draftStartDate = value) }

    fun onDraftInitialCashChanged(value: String) = _uiState.update { it.copy(draftInitialCash = value) }

    fun createDraftRun() {
        val state = _uiState.value
        val versionId = state.draftVersionId ?: run {
            _uiState.update { it.copy(message = "Select an ACTIVE strategy version") }
            return
        }
        val startDate = parseDate(state.draftStartDate) ?: run {
            _uiState.update { it.copy(message = "Start date YYYY-MM-DD") }
            return
        }
        val initialCash = parseWon(state.draftInitialCash) ?: run {
            _uiState.update { it.copy(message = "Initial cash must be a positive KRW amount") }
            return
        }
        viewModelScope.launch {
            runCatching {
                runService.createDraftRun(
                    strategyVersionId = versionId,
                    runName = state.draftRunName,
                    startDate = startDate,
                    initialCashWon = initialCash,
                )
            }.onSuccess { runId ->
                _uiState.update { it.copy(draftRunName = "") }
                reloadRuns(selectRunId = runId)
                _uiState.update { it.copy(message = "Draft run $runId created") }
            }.onFailure { e ->
                _uiState.update { it.copy(message = e.message) }
            }
        }
    }

    fun requestMarkReady() {
        val runId = _uiState.value.selectedRunId ?: return
        viewModelScope.launch {
            val run = runService.findById(runId)
            if (run?.status != RunStatus.DRAFT) {
                _uiState.update { it.copy(message = "Only DRAFT runs can be marked READY") }
                return@launch
            }
            val (strategyName, versionLabel) = repository.loadStrategyLabel(run)
            val confirmation = ReadyConfirmation(
                runId = run.id,
                runName = run.runName,
                strategyLabel = "$strategyName $versionLabel",
                startDate = run.startDate,
                initialCash = run.initialCash,
                universeCount = universeDao.findByRun(runId).size,
            )
            _uiState.update { it.copy(readyConfirmation = confirmation) }
        }
    }

    fun cancelMarkReady() = _uiState.update { it.copy(readyConfirmation = null) }

    fun confirmMarkReady() {
        val confirmation = _uiState.value.readyConfirmation ?: return
        val runId = confirmation.runId
        _uiState.update { it.copy(readyConfirmation = null, loading = true, message = null) }
        viewModelScope.launch {
            runCatching { runService.markReady(runId) }
                .onSuccess {
                    reloadRuns(selectRunId = runId)
                    _uiState.update { it.copy(message = "Run $runId is READY") }
                }
                .onFailure { e ->
                    loadDashboard(runId)
                    _uiState.update {
                        it.copy(message = "Mark Ready failed: ${e.message ?: e::class.simpleName}")
                    }
                }
        }
    }

    fun toggleAuto(enabled: Boolean) {
        scheduler.setAutoEnabled(enabled)
        _uiState.update { it.copy(autoEnabled = scheduler.isAutoEnabled()) }
    }

    fun runNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = null) }
            val message = runOperation { coordinator.runManualNow() }
            _uiState.update {
                it.copy(
                    loading = false,
                    message = message,
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
            val message = runOperation { coordinator.retryFailedCycle(RetryFailedCycleTarget(runId)) }
            _uiState.update {
                it.copy(
                    loading = false,
                    message = message,
                )
            }
            loadDashboard(runId)
        }
    }

    /** The coordinator records operation failures itself; only a failure to record reaches this catch. */
    private suspend fun runOperation(operation: suspend () -> ForwardOperationOutcome): String =
        try {
            formatResult(operation().display)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val error = AppErrorMapper.fromThrowable(failure)
            listOfNotNull(error.safeMessage, error.diagnostics.exceptionType?.let { "($it)" }).joinToString(" ")
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

    private suspend fun reloadRuns(selectRunId: Long? = null) {
        val runs = repository.loadAllRuns().sortedBy { it.id }
        val activeVersions = runService.listActiveVersions()
        _uiState.update {
            it.copy(
                runs = runs,
                compareCandidates = runs,
                selectedRunId = selectRunId ?: it.selectedRunId ?: runs.firstOrNull()?.id,
                autoEnabled = scheduler.isAutoEnabled(),
                activeVersions = activeVersions,
                draftVersionId = it.draftVersionId
                    ?.takeIf { id -> activeVersions.any { v -> v.strategyVersionId == id } }
                    ?: activeVersions.firstOrNull()?.strategyVersionId,
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
                selectedRunStatus = run?.status,
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
        fun parseDate(raw: String): LocalDate? = try {
            LocalDate.parse(raw.trim())
        } catch (_: DateTimeParseException) {
            null
        }

        fun parseWon(raw: String): Long? =
            raw.trim().replace(",", "").toLongOrNull()?.takeIf { it > 0L }

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
