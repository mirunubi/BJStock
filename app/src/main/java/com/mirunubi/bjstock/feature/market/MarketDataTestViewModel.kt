package com.mirunubi.bjstock.feature.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.kis.market.CurrentStockQuote
import com.mirunubi.bjstock.core.kis.market.DailyStockBar
import com.mirunubi.bjstock.core.kis.market.KisMarketException
import com.mirunubi.bjstock.core.kis.market.KisMarketRepository
import com.mirunubi.bjstock.core.marketdata.FetchAndPersistDailyBarsUseCase
import com.mirunubi.bjstock.core.marketdata.MarketDataPersistStatus
import com.mirunubi.bjstock.core.marketdata.MarketDataPersistenceException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MarketDataUiState(
    val symbol: String = "005930",
    val startDate: String = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(7).toString(),
    val endDate: String = LocalDate.now(ZoneId.of("Asia/Seoul")).toString(),
    val loadingQuote: Boolean = false,
    val loadingBars: Boolean = false,
    val loadingPersist: Boolean = false,
    val quote: CurrentStockQuote? = null,
    val bars: List<DailyStockBar> = emptyList(),
    val persistSummary: String? = null,
    val message: String? = null,
)

@HiltViewModel
class MarketDataTestViewModel @Inject constructor(
    private val repository: KisMarketRepository,
    private val fetchAndPersistDailyBars: FetchAndPersistDailyBarsUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MarketDataUiState())
    val uiState: StateFlow<MarketDataUiState> = _uiState.asStateFlow()

    fun onSymbolChanged(value: String) {
        _uiState.update { it.copy(symbol = value) }
    }

    fun onStartDateChanged(value: String) {
        _uiState.update { it.copy(startDate = value) }
    }

    fun onEndDateChanged(value: String) {
        _uiState.update { it.copy(endDate = value) }
    }

    fun inquireCurrentPrice() {
        if (_uiState.value.loadingQuote) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loadingQuote = true, message = null) }
            val result = runCatching { repository.inquireCurrentPrice(_uiState.value.symbol) }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { quote ->
                        state.copy(loadingQuote = false, quote = quote, message = null)
                    },
                    onFailure = { error ->
                        state.copy(loadingQuote = false, quote = null, message = publicMessage(error))
                    },
                )
            }
        }
    }

    fun inquireDailyBars() {
        if (_uiState.value.loadingBars) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loadingBars = true, message = null) }
            val dates = parseDates(_uiState.value.startDate, _uiState.value.endDate)
            if (dates == null) {
                _uiState.update {
                    it.copy(loadingBars = false, message = "잘못된 조회 기간")
                }
                return@launch
            }
            val result = runCatching {
                repository.inquireDailyBars(_uiState.value.symbol, dates.first, dates.second)
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { bars ->
                        state.copy(
                            loadingBars = false,
                            bars = bars,
                            persistSummary = "조회 결과: ${bars.size}건",
                            message = null,
                        )
                    },
                    onFailure = { error ->
                        state.copy(loadingBars = false, bars = emptyList(), message = publicMessage(error))
                    },
                )
            }
        }
    }

    fun persistDailyBars() {
        if (_uiState.value.loadingPersist) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loadingPersist = true, message = null) }
            val dates = parseDates(_uiState.value.startDate, _uiState.value.endDate)
            if (dates == null) {
                _uiState.update {
                    it.copy(loadingPersist = false, message = "잘못된 조회 기간")
                }
                return@launch
            }
            val result = runCatching {
                fetchAndPersistDailyBars(_uiState.value.symbol, dates.first, dates.second)
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { persist ->
                        val summary = if (persist.status == MarketDataPersistStatus.SUCCESS_EMPTY) {
                            "Room 저장: 0건"
                        } else {
                            "Room 저장: inserted ${persist.insertedCount}, " +
                                "updated ${persist.updatedCount}, unchanged ${persist.unchangedCount}"
                        }
                        state.copy(loadingPersist = false, persistSummary = summary, message = null)
                    },
                    onFailure = { error ->
                        state.copy(loadingPersist = false, message = publicMessage(error))
                    },
                )
            }
        }
    }

    private fun parseDates(start: String, end: String): Pair<LocalDate, LocalDate>? {
        return try {
            LocalDate.parse(start.trim()) to LocalDate.parse(end.trim())
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun publicMessage(error: Throwable): String {
        return when (error) {
            is MarketDataPersistenceException -> error.publicMessage
            is KisMarketException -> error.publicMessage
            else -> "연결 실패"
        }
    }
}
