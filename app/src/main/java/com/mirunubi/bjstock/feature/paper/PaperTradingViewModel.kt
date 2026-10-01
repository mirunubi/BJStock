package com.mirunubi.bjstock.feature.paper

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.error.SafeLogText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 모의투자 tab state. Opening the tab, selecting a Run and refreshing only read. Every write (draft creation,
 * universe edits, 운영 준비 완료, Auto ON/OFF, 지금 실행, 실패한 날짜 다시 처리) runs only from a confirmed
 * user action through [PaperTradingDataSource].
 */
@HiltViewModel
class PaperTradingViewModel @Inject constructor(
    private val source: PaperTradingDataSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PaperTradingUiState())
    val uiState: StateFlow<PaperTradingUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            loadRuns(select = null)
            loadAutomation()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loadRuns(select = _uiState.value.selectedRunId)
            loadAutomation()
        }
    }

    fun selectRun(runId: Long) {
        if (_uiState.value.selectedRunId == runId && _uiState.value.detail is DetailState.Loaded) return
        searchJob?.cancel()
        _uiState.update { it.copy(selectedRunId = runId, detail = DetailState.Loading, universeSearch = UniverseSearch()) }
        viewModelScope.launch { loadDetail(runId) }
    }

    fun togglePolicy() = _uiState.update { it.copy(policyExpanded = !it.policyExpanded) }

    fun dismissNotice() = _uiState.update { it.copy(notice = null) }

    fun dismissDialog() = _uiState.update { it.copy(dialog = null) }

    // region Automation

    fun requestAuto(enable: Boolean) = _uiState.update { it.copy(dialog = PaperDialog.ConfirmAuto(enable)) }

    fun confirmAuto() {
        val dialog = _uiState.value.dialog as? PaperDialog.ConfirmAuto ?: return
        _uiState.update { it.copy(dialog = null) }
        mutate("auto", failureMessage = { PaperTradingPresenter.autoFailure(dialog.enable) }) {
            try {
                source.setAutoEnabled(dialog.enable)
            } finally {
                loadAutomation()
            }
            notify(if (dialog.enable) "자동운영을 켰습니다. 지금 즉시 실행되지는 않습니다." else "자동운영을 껐습니다.")
        }
    }

    fun requestRunNow() = _uiState.update { it.copy(dialog = PaperDialog.ConfirmRunNow) }

    fun confirmRunNow() {
        if (_uiState.value.dialog != PaperDialog.ConfirmRunNow) return
        _uiState.update { it.copy(dialog = null) }
        mutate("run now", failureMessage = PaperTradingPresenter::operationFailure) {
            try {
                val outcome = source.runNow()
                _uiState.update { it.copy(notice = PaperTradingPresenter.outcome(outcome)) }
            } finally {
                reloadAll()
            }
        }
    }

    fun requestRetry() {
        val view = loadedDetail() ?: return
        val retry = view.retry as? RetryView.Retryable ?: return
        _uiState.update { it.copy(dialog = PaperDialog.ConfirmRetry(view.runId, retry.cycleId, retry.marketDate)) }
    }

    fun confirmRetry() {
        val dialog = _uiState.value.dialog as? PaperDialog.ConfirmRetry ?: return
        _uiState.update { it.copy(dialog = null) }
        mutate("retry", failureMessage = PaperTradingPresenter::operationFailure) {
            try {
                val outcome = source.retryFailedCycle(dialog.runId, dialog.marketDate, dialog.cycleId)
                _uiState.update { it.copy(notice = PaperTradingPresenter.outcome(outcome)) }
            } finally {
                reloadAll()
            }
        }
    }

    // endregion

    // region Draft Run

    /** Opens the form only; nothing is created until [confirmCreateDraft]. */
    fun showCreateDraft() {
        viewModelScope.launch {
            val versions = try {
                PaperTradingPresenter.versionOptions(source.activeVersions())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logFailure("active versions", failure)
                notify(PaperTradingPresenter.GENERIC_FAILURE, isError = true)
                return@launch
            }
            _uiState.update {
                it.copy(
                    dialog = PaperDialog.CreateDraft(
                        versions = versions,
                        selectedVersionId = versions.firstOrNull()?.strategyVersionId,
                        name = "",
                        startDate = source.today().toString(),
                        initialCash = PaperTradingPresenter.DEFAULT_INITIAL_CASH,
                        error = if (versions.isEmpty()) PaperTradingPresenter.NO_ACTIVE_VERSION else null,
                    ),
                )
            }
        }
    }

    fun onDraftVersion(strategyVersionId: Long) = updateDraft { it.copy(selectedVersionId = strategyVersionId, error = null) }

    fun onDraftName(value: String) = updateDraft { it.copy(name = value, error = null) }

    fun onDraftStartDate(value: String) = updateDraft { it.copy(startDate = value, error = null) }

    fun onDraftInitialCash(value: String) = updateDraft { it.copy(initialCash = value, error = null) }

    fun confirmCreateDraft() {
        val dialog = _uiState.value.dialog as? PaperDialog.CreateDraft ?: return
        val versionId = dialog.selectedVersionId?.takeIf { id -> dialog.versions.any { it.strategyVersionId == id } }
        val startDate = PaperTradingPresenter.parseDate(dialog.startDate)
        val cash = PaperTradingPresenter.parseWon(dialog.initialCash)
        val inputError = when {
            dialog.versions.isEmpty() -> PaperTradingPresenter.NO_ACTIVE_VERSION
            versionId == null -> PaperTradingPresenter.VERSION_REQUIRED
            dialog.name.isBlank() -> PaperTradingPresenter.NAME_REQUIRED
            startDate == null -> PaperTradingPresenter.DATE_INVALID
            cash == null -> PaperTradingPresenter.CASH_INVALID
            else -> null
        }
        if (inputError != null || versionId == null || startDate == null || cash == null) {
            updateDraft { it.copy(error = inputError) }
            return
        }
        val failureMessage = { failure: Exception ->
            PaperTradingPresenter.draftFailure(failure).also { message -> updateDraft { it.copy(error = message) } }
        }
        mutate("create draft", failureMessage = failureMessage) {
            val runId = source.createDraftRun(versionId, dialog.name.trim(), startDate, cash)
            _uiState.update { it.copy(dialog = null) }
            loadRuns(select = runId)
            notify("새 모의투자를 만들었습니다. 투자 대상을 추가한 뒤 운영 준비를 완료해 주세요.")
        }
    }

    // endregion

    // region Universe (DRAFT only)

    fun onUniverseQuery(query: String) {
        _uiState.update { it.copy(universeSearch = it.universeSearch.copy(query = query)) }
        searchJob?.cancel()
        if (loadedDetail()?.universe?.editable != true || query.isBlank()) {
            _uiState.update { it.copy(universeSearch = it.universeSearch.copy(results = emptyList())) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            val results = try {
                source.searchInstruments(query).map {
                    UniverseItem(it.id, PaperTradingPresenter.instrumentLabel(it.name, it.symbol))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logFailure("search", failure)
                emptyList()
            }
            _uiState.update { state ->
                if (state.universeSearch.query == query) state.copy(universeSearch = state.universeSearch.copy(results = results)) else state
            }
        }
    }

    fun addInstrument(instrumentId: Long) {
        val view = loadedDetail()?.takeIf { it.universe.editable } ?: return
        mutate("add instrument", failureMessage = PaperTradingPresenter::universeFailure) {
            try {
                source.addInstrument(view.runId, instrumentId)
            } finally {
                loadDetail(view.runId)
            }
        }
    }

    fun removeInstrument(instrumentId: Long) {
        val view = loadedDetail()?.takeIf { it.universe.editable } ?: return
        mutate("remove instrument", failureMessage = PaperTradingPresenter::universeFailure) {
            try {
                source.removeInstrument(view.runId, instrumentId)
            } finally {
                loadDetail(view.runId)
            }
        }
    }

    fun addTheme(themeId: Long) {
        val view = loadedDetail()?.takeIf { it.universe.editable } ?: return
        mutate("add theme", failureMessage = PaperTradingPresenter::universeFailure) {
            val added = try {
                source.addTheme(view.runId, themeId)
            } finally {
                loadDetail(view.runId)
            }
            notify(if (added > 0) "테마에서 ${added}종목을 추가했습니다." else "추가할 새 종목이 없습니다.")
        }
    }

    // endregion

    // region 운영 준비 완료

    fun requestReady() {
        val view = loadedDetail()?.takeIf { it.canMarkReady } ?: return
        _uiState.update { it.copy(dialog = PaperDialog.ConfirmReady(view.runId, view.readyConfirmation.rows)) }
    }

    fun confirmReady() {
        val dialog = _uiState.value.dialog as? PaperDialog.ConfirmReady ?: return
        _uiState.update { it.copy(dialog = null) }
        mutate("mark ready", failureMessage = PaperTradingPresenter::readyFailure) {
            try {
                source.markReady(dialog.runId)
            } finally {
                loadRuns(select = dialog.runId)
            }
            notify("운영 준비를 완료했습니다. 투자 대상과 거래 정책이 고정되었습니다.")
        }
    }

    // endregion

    // region Loading

    private suspend fun reloadAll() {
        loadRuns(select = _uiState.value.selectedRunId)
        loadAutomation()
    }

    private suspend fun loadRuns(select: Long?) {
        val runs = try {
            source.runs()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logFailure("runs", failure)
            _uiState.update { it.copy(runs = RunsState.Failed(PaperTradingPresenter.RUNS_FAILED)) }
            return
        }
        val selected = select?.takeIf { id -> runs.any { it.run.id == id } } ?: PaperTradingPresenter.defaultSelection(runs)
        _uiState.update {
            it.copy(
                runs = RunsState.Loaded(PaperTradingPresenter.runRows(runs)),
                selectedRunId = selected,
                detail = if (selected == null) DetailState.None else it.detail,
            )
        }
        selected?.let { loadDetail(it) }
    }

    private suspend fun loadDetail(runId: Long) {
        val next = try {
            source.detail(runId)?.let { DetailState.Loaded(PaperTradingPresenter.detail(it)) }
                ?: DetailState.Failed(PaperTradingPresenter.DETAIL_FAILED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logFailure("detail", failure)
            DetailState.Failed(PaperTradingPresenter.DETAIL_FAILED)
        }
        _uiState.update { state -> if (state.selectedRunId == runId) state.copy(detail = next) else state }
    }

    private suspend fun loadAutomation() {
        val next = try {
            AutomationState.Loaded(PaperTradingPresenter.automation(source.automation()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logFailure("automation", failure)
            AutomationState.Failed(PaperTradingPresenter.AUTOMATION_FAILED)
        }
        _uiState.update { it.copy(automation = next) }
    }

    // endregion

    // region Helpers

    private fun mutate(
        step: String,
        failureMessage: (Exception) -> String,
        block: suspend () -> Unit,
    ) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logFailure(step, failure)
                notify(failureMessage(failure), isError = true)
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun notify(message: String, isError: Boolean = false) =
        _uiState.update { it.copy(notice = PaperNotice(message, isError)) }

    private fun loadedDetail(): RunDetailView? = (_uiState.value.detail as? DetailState.Loaded)?.view

    private fun updateDraft(change: (PaperDialog.CreateDraft) -> PaperDialog.CreateDraft) = _uiState.update { state ->
        val dialog = state.dialog as? PaperDialog.CreateDraft ?: return@update state
        state.copy(dialog = change(dialog))
    }

    private fun logFailure(step: String, failure: Exception) {
        val type = SafeLogText.exceptionType(failure.javaClass.simpleName) ?: "Exception"
        Log.w(TAG, "Paper trading $step failed ($type)")
    }

    // endregion

    companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        private const val TAG = "BJStockPaperTrading"
    }
}
