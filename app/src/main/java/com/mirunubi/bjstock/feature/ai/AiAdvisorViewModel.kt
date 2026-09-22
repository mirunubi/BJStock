package com.mirunubi.bjstock.feature.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.ai.AiAdviceHistoryRow
import com.mirunubi.bjstock.core.ai.AiAdviceOutcome
import com.mirunubi.bjstock.core.ai.AiAdviceResponseParser
import com.mirunubi.bjstock.core.ai.AiAdvisoryMode
import com.mirunubi.bjstock.core.ai.AiAdvisoryService
import com.mirunubi.bjstock.core.ai.AiPromptPackage
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.StockEvaluationDao
import com.mirunubi.bjstock.core.database.dao.StrategyRunDao
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EvaluationOption(
    val evaluation: StockEvaluationEntity,
    val symbol: String,
)

data class AiAdvisorUiState(
    val mode: AiAdvisoryMode = AiAdvisoryMode.OFF,
    val runs: List<StrategyRunEntity> = emptyList(),
    val selectedRunId: Long? = null,
    val evaluations: List<EvaluationOption> = emptyList(),
    val selectedEvaluationId: Long? = null,
    val history: List<AiAdviceHistoryRow> = emptyList(),
    val activeRequestId: Long? = null,
    val prompt: AiPromptPackage? = null,
    val pasteBuffer: String = "",
    val validationMessage: String? = null,
    val message: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class AiAdvisorViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val aiService: AiAdvisoryService,
    private val strategyRunDao: StrategyRunDao,
    private val evaluationDao: StockEvaluationDao,
    private val instrumentDao: InstrumentDao,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AiAdvisorUiState(mode = aiService.getMode()))
    val uiState: StateFlow<AiAdvisorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { reloadRuns() }
    }

    fun setMode(mode: AiAdvisoryMode) {
        aiService.setMode(mode)
        _uiState.update { it.copy(mode = mode, message = "Mode = $mode") }
    }

    fun selectRun(runId: Long) {
        viewModelScope.launch {
            val evaluations = evaluationDao.findByRun(runId).map { evaluation ->
                EvaluationOption(
                    evaluation = evaluation,
                    symbol = instrumentDao.findById(evaluation.instrumentId)?.symbol
                        ?: evaluation.instrumentId.toString(),
                )
            }
            val selected = evaluations.firstOrNull()?.evaluation?.id
            _uiState.update {
                it.copy(
                    selectedRunId = runId,
                    evaluations = evaluations,
                    selectedEvaluationId = selected,
                    prompt = null,
                    activeRequestId = null,
                    history = emptyList(),
                )
            }
            selected?.let { loadHistory(it) }
        }
    }

    fun selectEvaluation(evaluationId: Long) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedEvaluationId = evaluationId,
                    prompt = null,
                    activeRequestId = null,
                )
            }
            loadHistory(evaluationId)
        }
    }

    fun onPasteChanged(value: String) {
        _uiState.update { it.copy(pasteBuffer = value, validationMessage = null) }
    }

    fun generatePrompt() {
        val evaluationId = _uiState.value.selectedEvaluationId ?: return
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null, validationMessage = null) }
            when (val outcome = aiService.createManualRequest(evaluationId)) {
                is AiAdviceOutcome.PromptReady -> {
                    _uiState.update {
                        it.copy(
                            busy = false,
                            activeRequestId = outcome.requestId,
                            prompt = outcome.prompt,
                            message = "Prompt generated (request ${outcome.requestId})",
                        )
                    }
                    loadHistory(evaluationId)
                }
                is AiAdviceOutcome.Failure -> {
                    _uiState.update {
                        it.copy(busy = false, message = "${outcome.kind}: ${outcome.message}")
                    }
                }
                is AiAdviceOutcome.Success -> {
                    _uiState.update { it.copy(busy = false, message = "unexpected success") }
                }
            }
        }
    }

    fun askAgain() = generatePrompt()

    fun copyPrompt() {
        val text = _uiState.value.prompt?.promptText ?: return
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BJStock AI Prompt", text))
        _uiState.update { it.copy(message = "Prompt copied to clipboard") }
    }

    fun sharePromptIntent(): Intent? {
        val text = _uiState.value.prompt?.promptText ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "BJStock AI Advisory Prompt")
        }
    }

    fun validateOnly() {
        val prompt = _uiState.value.prompt
        val raw = _uiState.value.pasteBuffer
        if (prompt == null) {
            _uiState.update { it.copy(validationMessage = "Generate a prompt first") }
            return
        }
        when (val outcome = AiAdviceResponseParser().parse(raw, prompt.requestFingerprint)) {
            is AiAdviceOutcome.Success -> {
                _uiState.update {
                    it.copy(
                        validationMessage =
                            "VALID ${outcome.advice.stance} confidence=${outcome.advice.confidence}",
                    )
                }
            }
            is AiAdviceOutcome.Failure -> {
                _uiState.update {
                    it.copy(validationMessage = "${outcome.kind}: ${outcome.message}")
                }
            }
            else -> Unit
        }
    }

    fun saveAdvice() {
        val requestId = _uiState.value.activeRequestId ?: return
        val evaluationId = _uiState.value.selectedEvaluationId ?: return
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null) }
            when (val outcome = aiService.saveManualResponse(requestId, _uiState.value.pasteBuffer)) {
                is AiAdviceOutcome.Success -> {
                    _uiState.update {
                        it.copy(
                            busy = false,
                            message =
                                "Saved ${outcome.advice.stance} agreement=${outcome.advice.agreement}",
                            validationMessage = null,
                        )
                    }
                    loadHistory(evaluationId)
                }
                is AiAdviceOutcome.Failure -> {
                    _uiState.update {
                        it.copy(busy = false, message = "${outcome.kind}: ${outcome.message}")
                    }
                }
                else -> _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun selectHistoryRequest(requestId: Long) {
        viewModelScope.launch {
            val prompt = aiService.loadRequestPrompt(requestId)
            _uiState.update {
                it.copy(
                    activeRequestId = requestId,
                    prompt = prompt,
                    message = "Loaded request $requestId",
                )
            }
        }
    }

    private suspend fun reloadRuns() {
        val runs = strategyRunDao.findAll().sortedBy { it.id }
        _uiState.update { it.copy(runs = runs) }
        runs.firstOrNull()?.id?.let { selectRun(it) }
    }

    private suspend fun loadHistory(evaluationId: Long) {
        val history = aiService.listAdviceHistory(evaluationId)
        _uiState.update { it.copy(history = history) }
    }
}
