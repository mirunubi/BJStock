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
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val message: String? = null,
    val loading: Boolean = false,
)

@HiltViewModel
class ForwardTestViewModel @Inject constructor(
    private val analytics: PerformanceAnalyticsService,
    private val repository: PerformanceAnalyticsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ForwardTestUiState())
    val uiState: StateFlow<ForwardTestUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { reloadRuns() }
    }

    fun selectRun(runId: Long) {
        viewModelScope.launch { loadDashboard(runId) }
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
        _uiState.update {
            it.copy(
                loading = false,
                summary = summary,
                dailySeries = series,
                monthly = monthly,
                openPositions = positions,
                recentExecutions = executions,
                policy = policy,
                message = mutationWarning ?: summary.errorMessage,
            )
        }
    }

    companion object {
        fun formatPercent(rate: BigDecimal?): String =
            rate?.let { PerformanceMath.formatSignedPercent(it) } ?: "N/A"

        fun formatWon(value: Long?): String =
            value?.let { PerformanceMath.formatWon(it) } ?: "N/A"

        fun formatRateAsAssumption(rate: BigDecimal): String =
            PerformanceMath.formatSignedPercent(rate).removePrefix("+")
    }
}
