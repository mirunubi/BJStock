package com.mirunubi.bjstock.feature.factor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.factor.FactorCalculationResult
import com.mirunubi.bjstock.core.factor.FactorCalculationService
import com.mirunubi.bjstock.core.factor.FactorCalculationStatus
import com.mirunubi.bjstock.core.factor.SystemFactorCatalog
import com.mirunubi.bjstock.core.model.FactorValueType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FactorRowUi(
    val code: String,
    val status: String,
    val raw: String,
    val score: String,
)

data class FactorTestUiState(
    val query: String = "",
    val searchResults: List<InstrumentEntity> = emptyList(),
    val selected: InstrumentEntity? = null,
    val evaluationDate: String = "",
    val calculating: Boolean = false,
    val rows: List<FactorRowUi> = emptyList(),
    val message: String? = null,
)

@HiltViewModel
class FactorTestViewModel @Inject constructor(
    private val instrumentDao: InstrumentDao,
    private val calculationService: FactorCalculationService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(FactorTestUiState())
    val uiState: StateFlow<FactorTestUiState> = _uiState.asStateFlow()

    fun onQueryChanged(value: String) {
        _uiState.update { it.copy(query = value, message = null) }
    }

    fun onEvaluationDateChanged(value: String) {
        _uiState.update { it.copy(evaluationDate = value) }
    }

    fun search() {
        viewModelScope.launch {
            val query = _uiState.value.query.trim()
            val results = if (query.isEmpty()) {
                emptyList()
            } else {
                instrumentDao.searchActive("%$query%")
            }
            _uiState.update { it.copy(searchResults = results) }
        }
    }

    fun select(instrument: InstrumentEntity) {
        _uiState.update {
            it.copy(selected = instrument, rows = emptyList(), message = null)
        }
    }

    fun calculate() {
        val selected = _uiState.value.selected ?: return
        val asOfDate = parseDate(_uiState.value.evaluationDate) ?: run {
            _uiState.update { it.copy(message = "Evaluation Date YYYY-MM-DD") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(calculating = true, message = null, rows = emptyList()) }
            val results = calculationService.calculateAllSystemFactors(selected.id, asOfDate)
            _uiState.update {
                it.copy(
                    calculating = false,
                    rows = results.map(::toRow),
                )
            }
        }
    }

    private fun toRow(result: FactorCalculationResult): FactorRowUi {
        if (result.status != FactorCalculationStatus.SUCCESS) {
            return FactorRowUi(
                code = result.factorCode,
                status = result.status.name,
                raw = "-",
                score = "-",
            )
        }
        val valueType = SystemFactorCatalog.definition(result.factorCode).valueType
        return FactorRowUi(
            code = result.factorCode,
            status = result.status.name,
            raw = formatRaw(result.rawValue!!, valueType),
            score = result.normalizedScore!!.stripTrailingZeros().toPlainString(),
        )
    }

    private fun formatRaw(raw: java.math.BigDecimal, valueType: FactorValueType): String {
        val scaled = raw.setScale(2, java.math.RoundingMode.HALF_UP)
        val text = scaled.toPlainString()
        return when (valueType) {
            FactorValueType.PERCENT -> {
                val signed = if (scaled.signum() > 0) "+$text" else text
                "$signed%"
            }
            else -> text
        }
    }

    private fun parseDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw.trim())
    } catch (_: DateTimeParseException) {
        null
    }
}
