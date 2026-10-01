package com.mirunubi.bjstock.feature.performance

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.error.SafeLogText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 성과 tab state. Structurally read-only: [PerformanceDataSource] has no write function, so opening the tab,
 * selecting a Run, refreshing and comparing Runs can never execute, schedule or record anything.
 */
@HiltViewModel
class PerformanceViewModel @Inject constructor(
    private val source: PerformanceDataSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PerformanceUiState())
    val uiState: StateFlow<PerformanceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { loadRuns(select = null) }
    }

    fun refresh() {
        viewModelScope.launch { loadRuns(select = _uiState.value.selectedRunId) }
    }

    fun selectRun(runId: Long) {
        if (_uiState.value.selectedRunId == runId && _uiState.value.detail is PerformanceDetailState.Loaded) return
        _uiState.update { it.copy(selectedRunId = runId, detail = PerformanceDetailState.Loading) }
        viewModelScope.launch { loadDetail(runId) }
    }

    // region Comparison

    fun openComparison() = _uiState.update {
        it.copy(comparison = ComparisonState(open = true, selected = listOfNotNull(it.selectedRunId)))
    }

    fun closeComparison() = _uiState.update { it.copy(comparison = ComparisonState()) }

    fun toggleComparisonRun(runId: Long) = _uiState.update { state ->
        val comparison = state.comparison
        if (!comparison.open) return@update state
        val next = when {
            runId in comparison.selected -> comparison.copy(selected = comparison.selected - runId, message = null)
            comparison.selected.size >= PerformancePresenter.MAX_COMPARE ->
                comparison.copy(message = PerformancePresenter.COMPARE_MAX_MESSAGE)
            else -> comparison.copy(selected = comparison.selected + runId, message = null)
        }
        state.copy(comparison = if (next.selected != comparison.selected) next.copy(result = ComparisonResult.None) else next)
    }

    fun compare() {
        val comparison = _uiState.value.comparison
        if (!comparison.open) return
        val ids = comparison.selected
        if (ids.size < PerformancePresenter.MIN_COMPARE) {
            _uiState.update { it.copy(comparison = it.comparison.copy(message = PerformancePresenter.COMPARE_MIN_MESSAGE)) }
            return
        }
        if (ids.size > PerformancePresenter.MAX_COMPARE) return
        _uiState.update { it.copy(comparison = it.comparison.copy(message = null, result = ComparisonResult.Loading)) }
        viewModelScope.launch {
            val result = try {
                val rows = source.compare(ids)
                ComparisonResult.Loaded(PerformancePresenter.comparison(rows, loadedRows().associateBy { it.runId }))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logFailure("compare", failure)
                ComparisonResult.Failed(PerformancePresenter.COMPARE_FAILED)
            }
            _uiState.update { state ->
                if (state.comparison.open && state.comparison.selected == ids) {
                    state.copy(comparison = state.comparison.copy(result = result))
                } else {
                    state
                }
            }
        }
    }

    // endregion

    // region Loading

    private suspend fun loadRuns(select: Long?) {
        val runs = try {
            source.runs()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logFailure("runs", failure)
            _uiState.update { it.copy(runs = PerformanceRunsState.Failed(PerformancePresenter.RUNS_FAILED)) }
            return
        }
        val selection = select?.takeIf { id -> runs.any { it.run.id == id } } ?: PerformancePresenter.defaultSelection(runs)
        _uiState.update {
            it.copy(
                runs = PerformanceRunsState.Loaded(PerformancePresenter.runRows(runs)),
                selectedRunId = selection,
                detail = when {
                    selection == null -> PerformanceDetailState.None
                    it.selectedRunId == selection && it.detail is PerformanceDetailState.Loaded -> it.detail
                    else -> PerformanceDetailState.Loading
                },
            )
        }
        selection?.let { loadDetail(it) }
    }

    private suspend fun loadDetail(runId: Long) {
        val next = try {
            source.detail(runId)?.let { PerformanceDetailState.Loaded(PerformancePresenter.detail(it)) }
                ?: PerformanceDetailState.Failed(PerformancePresenter.DETAIL_FAILED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logFailure("detail", failure)
            PerformanceDetailState.Failed(PerformancePresenter.DETAIL_FAILED)
        }
        _uiState.update { state -> if (state.selectedRunId == runId) state.copy(detail = next) else state }
    }

    // endregion

    private fun loadedRows(): List<PerformanceRunRow> =
        (_uiState.value.runs as? PerformanceRunsState.Loaded)?.rows.orEmpty()

    private fun logFailure(step: String, failure: Exception) {
        val type = SafeLogText.exceptionType(failure.javaClass.simpleName) ?: "Exception"
        Log.w(TAG, "Performance $step failed ($type)")
    }

    private companion object {
        const val TAG = "BJStockPerformance"
    }
}
