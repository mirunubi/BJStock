package com.mirunubi.bjstock.feature.strategy

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.error.SafeLogText
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.strategy.StrategyActivationResult
import com.mirunubi.bjstock.core.strategy.StrategyScoreMath
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
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
 * Strategy tab state. Opening the tab only reads; every write is a named user action that delegates to
 * [StrategyDataSource] (and so to `StrategyVersionService`). Layers: version detail over strategy
 * versions over the strategy list; [back] closes the top layer.
 */
@HiltViewModel
class StrategyViewModel @Inject constructor(
    private val source: StrategyDataSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StrategyUiState())
    val uiState: StateFlow<StrategyUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var previewJob: Job? = null

    init {
        viewModelScope.launch { loadList() }
    }

    fun refresh() {
        viewModelScope.launch {
            loadList()
            _uiState.value.strategy?.let { loadVersions(it.strategyId) }
            _uiState.value.version?.let { reloadVersion(it.versionId) }
        }
    }

    // region Navigation

    fun openStrategy(strategyId: Long) {
        val card = (_uiState.value.list as? ListState.Loaded)?.cards?.firstOrNull { it.strategyId == strategyId } ?: return
        _uiState.update {
            it.copy(strategy = StrategyPanel(card.strategyId, card.name, card.code, VersionsState.Loading), version = null)
        }
        viewModelScope.launch { loadVersions(strategyId) }
    }

    fun openVersion(versionId: Long) {
        searchJob?.cancel()
        previewJob?.cancel()
        _uiState.update { it.copy(version = VersionLayer.Loading(versionId)) }
        viewModelScope.launch { loadVersion(versionId) }
    }

    /** Closes the version detail, then the strategy. False when already at the strategy list. */
    fun back(): Boolean {
        val state = _uiState.value
        return when {
            state.version != null -> {
                searchJob?.cancel()
                previewJob?.cancel()
                _uiState.update { it.copy(version = null) }
                true
            }
            state.strategy != null -> {
                _uiState.update { it.copy(strategy = null) }
                true
            }
            else -> false
        }
    }

    fun dismissNotice() = _uiState.update { it.copy(notice = null) }

    fun dismissDialog() = _uiState.update { it.copy(dialog = null) }

    // endregion

    // region Lifecycle actions

    fun showCreateStrategy() = _uiState.update { it.copy(dialog = StrategyDialog.CreateStrategy()) }

    fun onCreateNameChange(value: String) = updateCreateDialog { it.copy(name = value, error = null) }

    fun onCreateCodeChange(value: String) = updateCreateDialog { it.copy(code = value, error = null) }

    /** Creates the strategy and its first DRAFT (V1), as the Strategy Lab does, then opens that draft. */
    fun confirmCreateStrategy() {
        val dialog = _uiState.value.dialog as? StrategyDialog.CreateStrategy ?: return
        val inputError = when {
            dialog.name.isBlank() -> StrategyPresenter.NAME_REQUIRED
            dialog.code.isBlank() -> StrategyPresenter.CODE_REQUIRED
            else -> null
        }
        if (inputError != null) {
            updateCreateDialog { it.copy(error = inputError) }
            return
        }
        mutate("create strategy") {
            if (source.strategyCodeExists(dialog.code)) {
                updateCreateDialog { it.copy(error = StrategyPresenter.CODE_EXISTS) }
                return@mutate
            }
            val (strategyId, versionId) = source.createStrategy(dialog.code.trim(), dialog.name.trim())
            _uiState.update { it.copy(dialog = null) }
            loadList()
            openStrategy(strategyId)
            openVersion(versionId)
            notify("새 전략과 작성중 V1을 만들었습니다.")
        }
    }

    /** New default DRAFT for the open strategy. */
    fun createDraft() {
        val strategy = _uiState.value.strategy ?: return
        mutate("create draft") {
            val versionId = source.createDraft(strategy.strategyId)
            loadList()
            loadVersions(strategy.strategyId)
            openVersion(versionId)
            notify("새 작성본을 만들었습니다.")
        }
    }

    /** Copies the open version (any status) into a new DRAFT through the existing copy behavior. */
    fun copyVersion() {
        val panel = loadedPanel() ?: return
        mutate("copy version") {
            val versionId = source.copyToDraft(panel.versionId)
            loadList()
            loadVersions(panel.strategyId)
            openVersion(versionId)
            notify("${panel.label}${StrategyPresenter.objectParticle(panel.versionNo)} 복사해 새 작성본을 만들었습니다.")
        }
    }

    /** Opens the confirmation; activation never happens on a single tap. */
    fun requestActivate() {
        val panel = loadedPanel() ?: return
        if (!panel.editable) return
        if (panel.hasUnsavedChanges) {
            notify(StrategyPresenter.UNSAVED_BLOCKS_ACTIVATION, isError = true)
            return
        }
        _uiState.update { it.copy(dialog = StrategyPresenter.activationDialog(panel)) }
    }

    fun confirmActivate() {
        val dialog = _uiState.value.dialog as? StrategyDialog.ConfirmActivate ?: return
        val panel = loadedPanel()?.takeIf { it.versionId == dialog.versionId } ?: return
        _uiState.update { it.copy(dialog = null) }
        mutate("activate") {
            when (val result = source.activate(panel.versionId)) {
                is StrategyActivationResult.Success -> {
                    loadList()
                    loadVersions(panel.strategyId)
                    reloadVersion(panel.versionId)
                    notify("${panel.label} 사용을 시작했습니다.")
                }
                is StrategyActivationResult.Failed ->
                    notify(StrategyPresenter.activationFailure(result.kind), isError = true)
            }
        }
    }

    // endregion

    // region Thresholds

    fun onSellChange(value: String) = editPanel { it.copy(sellInput = value) }

    fun onBuyChange(value: String) = editPanel { it.copy(buyInput = value) }

    fun saveThresholds() {
        val panel = loadedPanel()?.takeIf { it.editable } ?: return
        val sell = StrategyPresenter.decimal(panel.sellInput)
        val buy = StrategyPresenter.decimal(panel.buyInput)
        if (sell == null || buy == null) {
            notify(StrategyPresenter.SCORE_NOT_NUMBER, isError = true)
            return
        }
        mutate("save thresholds") {
            source.saveThresholds(panel.versionId, StrategyScoreMath.scoreToStored(sell), StrategyScoreMath.scoreToStored(buy))
            loadVersions(panel.strategyId)
            reloadVersion(panel.versionId)
            notify("판단 기준을 저장했습니다.")
        }
    }

    fun discardThresholds() = editPanel { it.copy(sellInput = it.savedSell, buyInput = it.savedBuy) }

    // endregion

    // region Factor weights

    fun onFactorEnabled(code: String, enabled: Boolean) = editFactor(code) { it.copy(enabled = enabled) }

    fun onFactorWeight(code: String, value: String) = editFactor(code) { it.copy(weightPercent = value) }

    fun onFactorMin(code: String, value: String) = editFactor(code) { it.copy(minScore = value) }

    fun onFactorMax(code: String, value: String) = editFactor(code) { it.copy(maxScore = value) }

    fun onFactorVersion(code: String, value: String) = editFactor(code) { it.copy(calculationVersion = value) }

    /** 고급 설정 (gates, calculation version) is shown per factor on demand; works read-only too. */
    fun toggleFactorAdvanced(code: String) = updatePanel { panel ->
        val expanded = if (code in panel.expandedFactors) panel.expandedFactors - code else panel.expandedFactors + code
        panel.copy(expandedFactors = expanded)
    }

    /** Saves only the factor rows that changed. */
    fun saveFactors() {
        val panel = loadedPanel()?.takeIf { it.editable } ?: return
        val changed = panel.factors.filterIndexed { index, factor -> factor != panel.savedFactors.getOrNull(index) }
        if (changed.isEmpty()) return
        val drafts = try {
            changed.map(::draftWeight)
        } catch (_: InvalidInput) {
            return
        }
        mutate("save factors") {
            source.saveWeights(panel.versionId, drafts)
            reloadVersion(panel.versionId)
            notify("팩터 비중을 저장했습니다.")
        }
    }

    fun discardFactors() = editPanel { it.copy(factors = it.savedFactors) }

    private fun draftWeight(factor: FactorInput): DraftWeight {
        val weight = StrategyPresenter.decimal(factor.weightPercent.ifBlank { "0" })
            ?: invalid(StrategyPresenter.WEIGHT_NOT_NUMBER)
        return DraftWeight(
            factorCode = factor.code,
            weightStored = convert { StrategyScoreMath.percentToWeightStored(weight) },
            enabled = factor.enabled,
            calculationVersion = factor.calculationVersion,
            minScoreStored = optionalScore(factor.minScore),
            maxScoreStored = optionalScore(factor.maxScore),
        )
    }

    private fun optionalScore(raw: String): Long? {
        if (raw.isBlank()) return null
        val value = StrategyPresenter.decimal(raw) ?: invalid(StrategyPresenter.GATE_NOT_NUMBER)
        return convert { StrategyScoreMath.scoreToStored(value) }
    }

    private fun <T> convert(block: () -> T): T = try {
        block()
    } catch (_: ArithmeticException) {
        invalid(StrategyPresenter.INPUT_INVALID)
    }

    private fun invalid(message: String): Nothing {
        notify(message, isError = true)
        throw InvalidInput()
    }

    private class InvalidInput : Exception()

    // endregion

    // region Signal rules

    fun startNewRule() = editPanel { it.copy(ruleForm = RuleForm()) }

    fun editRule(ruleId: Long) = editPanel { panel ->
        val rule = panel.rules.firstOrNull { it.ruleId == ruleId } ?: return@editPanel panel
        panel.copy(ruleForm = StrategyPresenter.ruleForm(rule))
    }

    fun cancelRule() = updatePanel { it.copy(ruleForm = null) }

    fun onRuleCode(value: String) = editRuleForm { it.copy(ruleCode = value) }

    fun onRuleOperator(value: SignalOperator) = editRuleForm { it.copy(operator = value) }

    fun onRuleThreshold(value: String) = editRuleForm { it.copy(threshold = value) }

    fun onRuleAction(value: SignalAction) = editRuleForm { it.copy(action = value) }

    fun onRulePriority(value: String) = editRuleForm { it.copy(priority = value) }

    fun saveRule() {
        val panel = loadedPanel()?.takeIf { it.editable } ?: return
        val form = panel.ruleForm ?: return
        StrategyPresenter.ruleFormError(form)?.let {
            notify(it, isError = true)
            return
        }
        mutate("save rule") {
            source.saveRule(
                versionId = panel.versionId,
                ruleCode = form.ruleCode.trim(),
                operator = form.operator,
                thresholdValue = form.threshold.trim(),
                action = form.action,
                priority = form.priority.trim().toInt(),
            )
            reloadVersion(panel.versionId)
            notify("신호 규칙을 저장했습니다.")
        }
    }

    fun requestDeleteRule(ruleId: Long) {
        val panel = loadedPanel()?.takeIf { it.editable } ?: return
        val rule = panel.rules.firstOrNull { it.ruleId == ruleId } ?: return
        _uiState.update { it.copy(dialog = StrategyDialog.ConfirmDeleteRule(rule.ruleId, rule.name)) }
    }

    fun confirmDeleteRule() {
        val dialog = _uiState.value.dialog as? StrategyDialog.ConfirmDeleteRule ?: return
        val panel = loadedPanel()?.takeIf { it.editable } ?: return
        _uiState.update { it.copy(dialog = null) }
        mutate("delete rule") {
            source.deleteRule(dialog.ruleId)
            reloadVersion(panel.versionId)
            notify("신호 규칙을 삭제했습니다.")
        }
    }

    // endregion

    // region Preview

    fun onPreviewQuery(value: String) {
        updatePreview { it.copy(query = value) }
        searchJob?.cancel()
        val query = value.trim()
        if (query.isEmpty()) {
            updatePreview { it.copy(search = PreviewSearch.Idle) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            runPreviewSearch(query)
        }
    }

    fun submitPreviewSearch() {
        val query = loadedPanel()?.preview?.query?.trim().orEmpty()
        if (query.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runPreviewSearch(query) }
    }

    private suspend fun runPreviewSearch(query: String) {
        updatePreview { it.copy(search = PreviewSearch.Searching) }
        val next = try {
            val found = source.searchInstruments(query)
            if (found.isEmpty()) PreviewSearch.NoResults else PreviewSearch.Results(found)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logFailure("preview search", failure)
            PreviewSearch.Failed(StrategyPresenter.SEARCH_FAILED)
        }
        updatePreview { it.copy(search = next) }
    }

    /** Selecting a stock offers its latest stored trade dates; the newest is prefilled. */
    fun selectPreviewInstrument(instrument: PreviewInstrument) {
        searchJob?.cancel()
        updatePreview {
            it.copy(instrument = instrument, search = PreviewSearch.Idle, dates = emptyList(), result = PreviewResultState.Idle)
        }
        viewModelScope.launch {
            val dates = try {
                source.recentTradeDates(instrument.instrumentId, PREVIEW_DATES)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logFailure("preview dates", failure)
                emptyList()
            }
            updatePreview { preview ->
                if (preview.instrument != instrument) {
                    preview
                } else {
                    preview.copy(dates = dates, dateInput = dates.firstOrNull()?.toString() ?: preview.dateInput)
                }
            }
        }
    }

    fun clearPreviewInstrument() = updatePreview { PreviewPanel(query = it.query) }

    fun onPreviewDate(value: String) = updatePreview { it.copy(dateInput = value, result = PreviewResultState.Idle) }

    fun pickPreviewDate(date: LocalDate) = onPreviewDate(date.toString())

    /** Read-only evaluation of the saved version; creates no evaluation, order, execution, or cash entry. */
    fun runPreview() {
        val panel = loadedPanel() ?: return
        val instrument = panel.preview.instrument ?: return
        val date = StrategyPresenter.parseDate(panel.preview.dateInput) ?: run {
            updatePreview { it.copy(result = PreviewResultState.Failed(StrategyPresenter.PREVIEW_DATE_INVALID)) }
            return
        }
        previewJob?.cancel()
        updatePreview { it.copy(result = PreviewResultState.Loading) }
        previewJob = viewModelScope.launch {
            val next = try {
                val result = source.preview(panel.versionId, instrument.instrumentId, date)
                PreviewResultState.Shown(StrategyPresenter.preview(result, instrument, date, panel.rules))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logFailure("preview", failure)
                PreviewResultState.Failed(StrategyPresenter.PREVIEW_FAILED)
            }
            updatePreview { it.copy(result = next) }
        }
    }

    // endregion

    // region Loading

    private suspend fun loadList() {
        val next = try {
            ListState.Loaded(source.strategies().map { StrategyPresenter.card(it, source.versions(it.id)) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logFailure("list", failure)
            ListState.Failed(StrategyPresenter.LIST_FAILED)
        }
        _uiState.update { state ->
            val card = (next as? ListState.Loaded)?.cards?.firstOrNull { it.strategyId == state.strategy?.strategyId }
            state.copy(
                list = next,
                strategy = if (card != null) state.strategy?.copy(name = card.name, code = card.code) else state.strategy,
            )
        }
    }

    private suspend fun loadVersions(strategyId: Long) {
        val next = try {
            VersionsState.Loaded(StrategyPresenter.versionRows(source.versions(strategyId)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logFailure("versions", failure)
            VersionsState.Failed(StrategyPresenter.VERSIONS_FAILED)
        }
        _uiState.update { state ->
            if (state.strategy?.strategyId == strategyId) state.copy(strategy = state.strategy.copy(versions = next)) else state
        }
    }

    private suspend fun loadVersion(versionId: Long) {
        val next = buildVersion(versionId, previous = null)
        _uiState.update { state -> if (state.version?.versionId == versionId) state.copy(version = next) else state }
    }

    /** Reloads stored values after a write; the preview section is kept. */
    private suspend fun reloadVersion(versionId: Long) {
        val previous = (_uiState.value.version as? VersionLayer.Loaded)?.panel?.takeIf { it.versionId == versionId }
        val next = buildVersion(versionId, previous)
        _uiState.update { state -> if (state.version?.versionId == versionId) state.copy(version = next) else state }
    }

    private suspend fun buildVersion(versionId: Long, previous: VersionPanel?): VersionLayer = try {
        val snapshot = source.snapshot(versionId)
        if (snapshot == null) {
            VersionLayer.Failed(versionId, StrategyPresenter.VERSION_FAILED)
        } else {
            val name = _uiState.value.strategy?.name ?: ""
            val panel = StrategyPresenter.panel(name, snapshot, source::calculationVersions)
            VersionLayer.Loaded(
                if (previous == null) panel else panel.copy(expandedFactors = previous.expandedFactors, preview = previous.preview),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logFailure("version", failure)
        VersionLayer.Failed(versionId, StrategyPresenter.VERSION_FAILED)
    }

    fun retryVersion() {
        val versionId = _uiState.value.version?.versionId ?: return
        openVersion(versionId)
    }

    // endregion

    // region Helpers

    private fun mutate(step: String, block: suspend () -> Unit) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logFailure(step, failure)
                notify(StrategyPresenter.failure(failure), isError = true)
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun notify(message: String, isError: Boolean = false) =
        _uiState.update { it.copy(notice = Notice(message, isError)) }

    private fun loadedPanel(): VersionPanel? = (_uiState.value.version as? VersionLayer.Loaded)?.panel

    private fun updatePanel(change: (VersionPanel) -> VersionPanel) = _uiState.update { state ->
        val layer = state.version as? VersionLayer.Loaded ?: return@update state
        state.copy(version = VersionLayer.Loaded(change(layer.panel)))
    }

    /** Edits apply only to DRAFT versions; ACTIVE / RETIRED panels ignore them. */
    private fun editPanel(change: (VersionPanel) -> VersionPanel) = updatePanel { if (it.editable) change(it) else it }

    private fun editFactor(code: String, change: (FactorInput) -> FactorInput) = editPanel { panel ->
        panel.copy(factors = panel.factors.map { if (it.code == code) change(it) else it })
    }

    private fun editRuleForm(change: (RuleForm) -> RuleForm) = editPanel { panel ->
        panel.ruleForm?.let { panel.copy(ruleForm = change(it)) } ?: panel
    }

    private fun updatePreview(change: (PreviewPanel) -> PreviewPanel) = updatePanel { it.copy(preview = change(it.preview)) }

    private fun updateCreateDialog(change: (StrategyDialog.CreateStrategy) -> StrategyDialog.CreateStrategy) =
        _uiState.update { state ->
            val dialog = state.dialog as? StrategyDialog.CreateStrategy ?: return@update state
            state.copy(dialog = change(dialog))
        }

    private fun logFailure(step: String, failure: Exception) {
        val type = SafeLogText.exceptionType(failure.javaClass.simpleName) ?: "Exception"
        Log.w(TAG, "Strategy $step failed ($type)")
    }

    // endregion

    companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val PREVIEW_DATES = 10
        private const val TAG = "BJStockStrategy"
    }
}
