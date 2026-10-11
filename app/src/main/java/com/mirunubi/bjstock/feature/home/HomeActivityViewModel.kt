package com.mirunubi.bjstock.feature.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.error.SafeLogText
import com.mirunubi.bjstock.feature.admin.SectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

data class HomeActivityState(
    val runs: SectionState<HomeSummary> = SectionState.Loading,
    val signals: SectionState<HomeSummary> = SectionState.Loading,
    val trades: SectionState<HomeSummary> = SectionState.Loading,
    val errors: SectionState<HomeSummary> = SectionState.Loading,
    val audit: SectionState<HomeSummary> = SectionState.Loading,
) {
    fun section(section: HomeActivitySection): SectionState<HomeSummary> = when (section) {
        HomeActivitySection.RUNS -> runs
        HomeActivitySection.SIGNALS -> signals
        HomeActivitySection.TRADES -> trades
        HomeActivitySection.ERRORS -> errors
        HomeActivitySection.AUDIT -> audit
    }
}

/** Observational Home summaries. Every section loads and fails on its own; nothing here writes or schedules. */
@HiltViewModel
class HomeActivityViewModel @Inject constructor(
    private val source: HomeActivitySource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeActivityState())
    val uiState: StateFlow<HomeActivityState> = _uiState.asStateFlow()
    private var loading: Job? = null

    /** Reloads every section. Sections already shown stay visible while refreshing. */
    fun refresh() {
        if (loading?.isActive == true) return
        loading = viewModelScope.launch {
            listOf(
                launch { load(HomeActivitySection.RUNS) { HomeActivityPresenter.runs(source.activeRuns()) } },
                launch { load(HomeActivitySection.SIGNALS) { HomeActivityPresenter.signals(source.signals()) } },
                launch { load(HomeActivitySection.TRADES) { HomeActivityPresenter.trades(source.trades()) } },
                launch { load(HomeActivitySection.ERRORS) { HomeActivityPresenter.errors(source.errors()) } },
                launch { load(HomeActivitySection.AUDIT) { HomeActivityPresenter.audit(source.audit()) } },
            ).joinAll()
        }
    }

    private suspend fun load(section: HomeActivitySection, block: suspend () -> SectionState<HomeSummary>) {
        val next = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val type = SafeLogText.exceptionType(failure.javaClass.simpleName) ?: "Exception"
            Log.w(TAG, "Home ${section.name} load failed ($type)")
            SectionState.Failed(HomeActivityPresenter.LOAD_FAILED)
        }
        _uiState.update { state ->
            when (section) {
                HomeActivitySection.RUNS -> state.copy(runs = next)
                HomeActivitySection.SIGNALS -> state.copy(signals = next)
                HomeActivitySection.TRADES -> state.copy(trades = next)
                HomeActivitySection.ERRORS -> state.copy(errors = next)
                HomeActivitySection.AUDIT -> state.copy(audit = next)
            }
        }
    }

    private companion object {
        const val TAG = "BJStockHome"
    }
}
