package com.mirunubi.bjstock.feature.strategy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.StrategyEntity
import com.mirunubi.bjstock.core.database.entity.StrategyVersionEntity
import com.mirunubi.bjstock.core.factor.FactorCalculationVersions
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.FactorRegistry
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorCatalog
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.strategy.PreviewStrategyEvaluationUseCase
import com.mirunubi.bjstock.core.strategy.StrategyActivationResult
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationResult
import com.mirunubi.bjstock.core.strategy.StrategyEvaluationStatus
import com.mirunubi.bjstock.core.strategy.StrategyScoreMath
import com.mirunubi.bjstock.core.strategy.StrategyVersionException
import com.mirunubi.bjstock.core.strategy.StrategyVersionService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StrategyFactorEditorUi(
    val factorId: Long,
    val factorCode: String,
    val factorName: String,
    val enabled: Boolean,
    val weightPercent: String,
    val calculationVersion: String,
    val availableVersions: List<String>,
    val minScore: String,
    val maxScore: String,
)

data class PreviewFactorRowUi(
    val factorCode: String,
    val score: String,
    val weightPercent: String,
    val contribution: String,
    val gateFailed: Boolean,
    val gateNote: String?,
)

data class StrategyLabUiState(
    val strategies: List<StrategyEntity> = emptyList(),
    val versions: List<StrategyVersionEntity> = emptyList(),
    val newCode: String = "",
    val newName: String = "",
    val selectedStrategyId: Long? = null,
    val selectedVersionId: Long? = null,
    val sellThreshold: String = "40",
    val buyThreshold: String = "70",
    val factors: List<StrategyFactorEditorUi> = emptyList(),
    val query: String = "",
    val searchResults: List<InstrumentEntity> = emptyList(),
    val selectedInstrument: InstrumentEntity? = null,
    val evaluationDate: String = "",
    val preview: StrategyEvaluationResult? = null,
    val message: String? = null,
    val busy: Boolean = false,
) {
    val selectedVersion: StrategyVersionEntity?
        get() = versions.firstOrNull { it.id == selectedVersionId }

    val isDraft: Boolean
        get() = selectedVersion?.status == StrategyVersionStatus.DRAFT

    val enabledWeightPercent: BigDecimal
        get() = factors.filter { it.enabled }.fold(BigDecimal.ZERO) { acc, factor ->
            acc.add(factor.weightPercent.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO)
        }

    val weightTotalLabel: String
        get() = "Total Weight ${enabledWeightPercent.stripTrailingZeros().toPlainString()}%"
}

@HiltViewModel
class StrategyLabViewModel @Inject constructor(
    private val strategyService: StrategyVersionService,
    private val previewEvaluation: PreviewStrategyEvaluationUseCase,
    private val instrumentDao: InstrumentDao,
    private val factorValues: FactorValueRepository,
    private val registry: FactorRegistry,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StrategyLabUiState())
    val uiState: StateFlow<StrategyLabUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { reloadStrategies() }
    }

    fun onNewCodeChanged(value: String) = _uiState.update { it.copy(newCode = value, message = null) }

    fun onNewNameChanged(value: String) = _uiState.update { it.copy(newName = value, message = null) }

    fun onSellChanged(value: String) = _uiState.update { it.copy(sellThreshold = value, message = null) }

    fun onBuyChanged(value: String) = _uiState.update { it.copy(buyThreshold = value, message = null) }

    fun onQueryChanged(value: String) = _uiState.update { it.copy(query = value) }

    fun onEvaluationDateChanged(value: String) = _uiState.update { it.copy(evaluationDate = value) }

    fun createStrategy() {
        val state = _uiState.value
        viewModelScope.launch {
            runCatching {
                val strategyId = strategyService.createStrategy(state.newCode, state.newName)
                val versionId = strategyService.createDraftVersion(strategyId)
                reloadStrategies(strategyId, versionId)
                _uiState.update { it.copy(newCode = "", newName = "", message = "Draft V1 created") }
            }.onFailure { error ->
                _uiState.update { it.copy(message = error.message) }
            }
        }
    }

    fun selectStrategy(strategyId: Long) {
        viewModelScope.launch { reloadStrategies(strategyId, null) }
    }

    fun selectVersion(versionId: Long) {
        viewModelScope.launch { loadVersion(versionId) }
    }

    fun createDraftVersion() {
        val strategyId = _uiState.value.selectedStrategyId ?: return
        viewModelScope.launch {
            val versionId = strategyService.createDraftVersion(strategyId)
            reloadStrategies(strategyId, versionId)
        }
    }

    fun copySelectedVersion() {
        val versionId = _uiState.value.selectedVersionId ?: return
        viewModelScope.launch {
            val newId = strategyService.copyDraftFrom(versionId)
            val strategyId = _uiState.value.selectedStrategyId
            reloadStrategies(strategyId, newId)
            _uiState.update { it.copy(message = "Copied to a new DRAFT version") }
        }
    }

    fun saveDraftThresholds() {
        val state = _uiState.value
        val versionId = state.selectedVersionId ?: return
        if (!state.isDraft) return
        viewModelScope.launch {
            runCatching {
                strategyService.updateDraftThresholds(
                    strategyVersionId = versionId,
                    sellThresholdStored = parseScore(state.sellThreshold),
                    buyThresholdStored = parseScore(state.buyThreshold),
                )
                loadVersion(versionId)
                _uiState.update { it.copy(message = "Thresholds saved") }
            }.onFailure { error ->
                _uiState.update { it.copy(message = error.message) }
            }
        }
    }

    fun onFactorEnabled(factorCode: String, enabled: Boolean) {
        updateFactor(factorCode) { it.copy(enabled = enabled) }
        persistFactor(factorCode)
    }

    fun onFactorWeight(factorCode: String, percent: String) {
        updateFactor(factorCode) { it.copy(weightPercent = percent) }
    }

    fun persistFactorWeight(factorCode: String) {
        persistFactor(factorCode)
    }

    fun onFactorVersion(factorCode: String, version: String) {
        updateFactor(factorCode) { it.copy(calculationVersion = version) }
        persistFactor(factorCode)
    }

    fun onFactorMin(factorCode: String, value: String) {
        updateFactor(factorCode) { it.copy(minScore = value) }
    }

    fun onFactorMax(factorCode: String, value: String) {
        updateFactor(factorCode) { it.copy(maxScore = value) }
    }

    fun persistFactorGate(factorCode: String) {
        persistFactor(factorCode)
    }

    fun activate() {
        val versionId = _uiState.value.selectedVersionId ?: return
        viewModelScope.launch {
            runCatching {
                saveAllFactors()
                when (val result = strategyService.activateStrategyVersion(versionId)) {
                    is StrategyActivationResult.Success -> {
                        loadVersion(versionId)
                        _uiState.update { it.copy(message = "ACTIVE") }
                    }
                    is StrategyActivationResult.Failed -> {
                        _uiState.update { it.copy(message = result.message) }
                    }
                }
            }.onFailure { error ->
                _uiState.update { it.copy(message = error.message) }
            }
        }
    }

    fun searchInstruments() {
        viewModelScope.launch {
            val query = _uiState.value.query.trim()
            val results = if (query.isEmpty()) emptyList() else instrumentDao.searchActive("%$query%")
            _uiState.update { it.copy(searchResults = results) }
        }
    }

    fun selectInstrument(instrument: InstrumentEntity) {
        _uiState.update { it.copy(selectedInstrument = instrument, preview = null) }
    }

    fun preview() {
        val state = _uiState.value
        val versionId = state.selectedVersionId ?: return
        val instrument = state.selectedInstrument ?: return
        val date = parseDate(state.evaluationDate) ?: run {
            _uiState.update { it.copy(message = "Evaluation Date YYYY-MM-DD") }
            return
        }
        viewModelScope.launch {
            runCatching {
                if (state.isDraft) {
                    saveDraftNow()
                }
                previewEvaluation(versionId, instrument.id, date)
            }.onSuccess { result ->
                _uiState.update { it.copy(preview = result, message = result.message) }
            }.onFailure { error ->
                _uiState.update { it.copy(preview = null, message = error.message) }
            }
        }
    }

    private suspend fun saveDraftNow() {
        val state = _uiState.value
        val versionId = state.selectedVersionId ?: return
        if (!state.isDraft) return
        strategyService.updateDraftThresholds(
            strategyVersionId = versionId,
            sellThresholdStored = parseScore(state.sellThreshold),
            buyThresholdStored = parseScore(state.buyThreshold),
        )
        saveAllFactors()
    }

    private suspend fun saveAllFactors() {
        _uiState.value.factors.forEach { persistFactorSuspend(it) }
    }

    private fun persistFactor(factorCode: String) {
        viewModelScope.launch {
            val factor = _uiState.value.factors.firstOrNull { it.factorCode == factorCode } ?: return@launch
            runCatching { persistFactorSuspend(factor) }
                .onFailure { error ->
                    val message = if (error is StrategyVersionException) error.message else error.message
                    _uiState.update { it.copy(message = message) }
                }
        }
    }

    private suspend fun persistFactorSuspend(factor: StrategyFactorEditorUi) {
        val versionId = _uiState.value.selectedVersionId ?: return
        if (!_uiState.value.isDraft) return
        strategyService.upsertDraftWeight(
            strategyVersionId = versionId,
            factorId = factor.factorId,
            weightStored = StrategyScoreMath.percentToWeightStored(
                parseDecimal(factor.weightPercent) ?: BigDecimal.ZERO,
            ),
            enabled = factor.enabled,
            factorCalculationVersion = factor.calculationVersion,
            minScoreStored = parseOptionalScore(factor.minScore),
            maxScoreStored = parseOptionalScore(factor.maxScore),
        )
    }

    private fun updateFactor(
        factorCode: String,
        transform: (StrategyFactorEditorUi) -> StrategyFactorEditorUi,
    ) {
        _uiState.update { state ->
            state.copy(
                factors = state.factors.map { factor ->
                    if (factor.factorCode == factorCode) transform(factor) else factor
                },
                message = null,
            )
        }
    }

    private suspend fun reloadStrategies(strategyId: Long? = null, versionId: Long? = null) {
        factorValues.ensureSystemFactorDefinitions()
        val strategies = strategyService.findAllStrategies()
        val selectedStrategy = strategyId ?: strategies.firstOrNull()?.id
        val versions = selectedStrategy?.let { strategyService.findVersionsByStrategy(it) }.orEmpty()
        val selectedVersion = versionId ?: versions.lastOrNull()?.id
        _uiState.update {
            it.copy(
                strategies = strategies,
                versions = versions,
                selectedStrategyId = selectedStrategy,
                selectedVersionId = selectedVersion,
            )
        }
        if (selectedVersion != null) {
            loadVersion(selectedVersion)
        }
    }

    private suspend fun loadVersion(versionId: Long) {
        val version = strategyService.findStrategyVersion(versionId) ?: return
        val weights = strategyService.findWeights(versionId).associateBy { it.factorId }
        val definitions = factorValues.findAllDefinitions().associateBy { it.factorCode }
        val factors = FactorCodes.SYSTEM.map { code ->
            val definition = definitions.getValue(code)
            val weight = weights[definition.id]
            val catalog = SystemFactorCatalog.definition(code)
            StrategyFactorEditorUi(
                factorId = definition.id,
                factorCode = code,
                factorName = catalog.factorName,
                enabled = weight?.enabled ?: false,
                weightPercent = weight?.let {
                    StrategyScoreMath.weightStoredToPercent(it.weight).stripTrailingZeros().toPlainString()
                } ?: "0",
                calculationVersion = weight?.factorCalculationVersion ?: FactorCalculationVersions.V1,
                availableVersions = registry.versionsFor(code).ifEmpty { listOf(FactorCalculationVersions.V1) },
                minScore = weight?.minScore?.let {
                    StrategyScoreMath.scoreToDisplay(it).stripTrailingZeros().toPlainString()
                } ?: "",
                maxScore = weight?.maxScore?.let {
                    StrategyScoreMath.scoreToDisplay(it).stripTrailingZeros().toPlainString()
                } ?: "",
            )
        }
        _uiState.update {
            it.copy(
                selectedVersionId = versionId,
                versions = strategyService.findVersionsByStrategy(version.strategyId),
                sellThreshold = StrategyScoreMath.scoreToDisplay(version.sellThreshold)
                    .stripTrailingZeros()
                    .toPlainString(),
                buyThreshold = StrategyScoreMath.scoreToDisplay(version.buyThreshold)
                    .stripTrailingZeros()
                    .toPlainString(),
                factors = factors,
                preview = null,
            )
        }
    }

    private fun parseScore(raw: String): Long {
        val value = parseDecimal(raw) ?: error("score required")
        return StrategyScoreMath.scoreToStored(value)
    }

    private fun parseOptionalScore(raw: String): Long? {
        if (raw.isBlank()) return null
        return parseScore(raw)
    }

    companion object {
        fun parseDecimal(raw: String): BigDecimal? =
            raw.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()

        fun parseDate(raw: String): LocalDate? = try {
            LocalDate.parse(raw.trim())
        } catch (_: DateTimeParseException) {
            null
        }

        fun previewRows(result: StrategyEvaluationResult): List<PreviewFactorRowUi> =
            result.factorDetails.map { detail ->
                val score = StrategyScoreMath.scoreToDisplay(detail.factorScoreStored)
                val weightPercent = StrategyScoreMath.weightStoredToPercent(detail.weightStored)
                val contribution = StrategyScoreMath.scoreToDisplay(
                    StrategyScoreMath.quantScoreStored(listOf(detail.weightedScoreStored)),
                )
                PreviewFactorRowUi(
                    factorCode = detail.factorCode,
                    score = score.stripTrailingZeros().toPlainString(),
                    weightPercent = weightPercent.stripTrailingZeros().toPlainString(),
                    contribution = contribution.stripTrailingZeros().toPlainString(),
                    gateFailed = detail.gateFailed,
                    gateNote = when {
                        detail.gateFailed && detail.minScoreStored != null &&
                            detail.factorScoreStored < detail.minScoreStored ->
                            "Minimum Required ${StrategyScoreMath.scoreToDisplay(detail.minScoreStored).stripTrailingZeros().toPlainString()}"
                        detail.gateFailed && detail.maxScoreStored != null &&
                            detail.factorScoreStored > detail.maxScoreStored ->
                            "Maximum Allowed ${StrategyScoreMath.scoreToDisplay(detail.maxScoreStored).stripTrailingZeros().toPlainString()}"
                        else -> null
                    },
                )
            }
    }
}
