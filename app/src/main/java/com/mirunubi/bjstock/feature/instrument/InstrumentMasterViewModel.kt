package com.mirunubi.bjstock.feature.instrument

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.instrument.InstrumentMasterConfig
import com.mirunubi.bjstock.core.instrument.InstrumentMasterException
import com.mirunubi.bjstock.core.instrument.InstrumentMasterSyncResult
import com.mirunubi.bjstock.core.instrument.InstrumentMasterSynchronizer
import com.mirunubi.bjstock.core.kis.market.KisMarketException
import com.mirunubi.bjstock.core.marketdata.HistoricalSyncException
import com.mirunubi.bjstock.core.marketdata.HistoricalSyncResult
import com.mirunubi.bjstock.core.marketdata.MarketDataLocalRepository
import com.mirunubi.bjstock.core.marketdata.SyncDailyBarsFromLatestUseCase
import com.mirunubi.bjstock.core.marketdata.SyncHistoricalDailyBarsUseCase
import com.mirunubi.bjstock.core.model.Board
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InstrumentMasterUiState(
    val kospiActiveCount: Int = 0,
    val kosdaqActiveCount: Int = 0,
    val query: String = "",
    val searchResults: List<InstrumentEntity> = emptyList(),
    val selected: InstrumentEntity? = null,
    val startDate: String = "",
    val endDate: String = "",
    val latestDate: String? = null,
    val kospiSyncing: Boolean = false,
    val kosdaqSyncing: Boolean = false,
    val historicalSyncing: Boolean = false,
    val incrementalSyncing: Boolean = false,
    val masterResult: String? = null,
    val historicalResult: String? = null,
    val message: String? = null,
)

@HiltViewModel
class InstrumentMasterViewModel @Inject constructor(
    private val synchronizer: InstrumentMasterSynchronizer,
    private val instrumentDao: InstrumentDao,
    private val localRepository: MarketDataLocalRepository,
    private val historicalSync: SyncHistoricalDailyBarsUseCase,
    private val fromLatest: SyncDailyBarsFromLatestUseCase,
) : ViewModel() {
    private val form = MutableStateFlow(FormState())

    val uiState: StateFlow<InstrumentMasterUiState> = combine(
        instrumentDao.observeActiveCountByMarketAndBoard(
            InstrumentMasterConfig.MARKET_KRX,
            Board.KOSPI,
        ),
        instrumentDao.observeActiveCountByMarketAndBoard(
            InstrumentMasterConfig.MARKET_KRX,
            Board.KOSDAQ,
        ),
        form,
    ) { kospi, kosdaq, formState ->
        InstrumentMasterUiState(
            kospiActiveCount = kospi,
            kosdaqActiveCount = kosdaq,
            query = formState.query,
            searchResults = formState.searchResults,
            selected = formState.selected,
            startDate = formState.startDate,
            endDate = formState.endDate,
            latestDate = formState.latestDate,
            kospiSyncing = formState.kospiSyncing,
            kosdaqSyncing = formState.kosdaqSyncing,
            historicalSyncing = formState.historicalSyncing,
            incrementalSyncing = formState.incrementalSyncing,
            masterResult = formState.masterResult,
            historicalResult = formState.historicalResult,
            message = formState.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InstrumentMasterUiState(),
    )

    fun onQueryChanged(value: String) {
        form.update { it.copy(query = value, message = null) }
    }

    fun search() {
        viewModelScope.launch {
            val query = form.value.query.trim()
            val results = if (query.isEmpty()) {
                emptyList()
            } else {
                instrumentDao.searchActive("%$query%")
            }
            form.update { it.copy(searchResults = results, message = null) }
        }
    }

    fun select(instrument: InstrumentEntity) {
        viewModelScope.launch {
            val latest = localRepository.findLatest(instrument.id)
            form.update {
                it.copy(
                    selected = instrument,
                    latestDate = latest?.tradeDate?.toString(),
                    message = null,
                    historicalResult = null,
                )
            }
        }
    }

    fun onStartDateChanged(value: String) {
        form.update { it.copy(startDate = value) }
    }

    fun onEndDateChanged(value: String) {
        form.update { it.copy(endDate = value) }
    }

    fun syncKospi() = syncBoard(Board.KOSPI)

    fun syncKosdaq() = syncBoard(Board.KOSDAQ)

    fun syncHistory() {
        val selected = form.value.selected ?: return
        val start = parseDate(form.value.startDate) ?: run {
            form.update { it.copy(message = "Start Date YYYY-MM-DD") }
            return
        }
        val end = parseDate(form.value.endDate) ?: run {
            form.update { it.copy(message = "End Date YYYY-MM-DD") }
            return
        }
        viewModelScope.launch {
            form.update { it.copy(historicalSyncing = true, message = null, historicalResult = null) }
            val outcome = runCatching { historicalSync(selected.id, start, end) }
            form.update {
                it.copy(
                    historicalSyncing = false,
                    historicalResult = outcome.fold(
                        onSuccess = { result -> formatHistorical(result) },
                        onFailure = { error -> publicError(error) },
                    ),
                    latestDate = outcome.getOrNull()?.lastDate?.toString() ?: it.latestDate,
                )
            }
        }
    }

    fun syncFromLatest() {
        val selected = form.value.selected ?: return
        val end = parseDate(form.value.endDate) ?: run {
            form.update { it.copy(message = "End Date YYYY-MM-DD") }
            return
        }
        viewModelScope.launch {
            form.update { it.copy(incrementalSyncing = true, message = null, historicalResult = null) }
            val outcome = runCatching { fromLatest(selected.id, end) }
            form.update {
                it.copy(
                    incrementalSyncing = false,
                    historicalResult = outcome.fold(
                        onSuccess = { result -> formatHistorical(result) },
                        onFailure = { error -> publicError(error) },
                    ),
                    latestDate = outcome.getOrNull()?.lastDate?.toString() ?: it.latestDate,
                )
            }
        }
    }

    private fun syncBoard(board: Board) {
        viewModelScope.launch {
            form.update {
                when (board) {
                    Board.KOSPI -> it.copy(kospiSyncing = true, message = null)
                    Board.KOSDAQ -> it.copy(kosdaqSyncing = true, message = null)
                    Board.OTHER -> it
                }
            }
            val outcome = runCatching { synchronizer.sync(board) }
            form.update {
                val resultText = outcome.fold(
                    onSuccess = { result -> formatMaster(result) },
                    onFailure = { error -> publicError(error) },
                )
                when (board) {
                    Board.KOSPI -> it.copy(kospiSyncing = false, masterResult = resultText)
                    Board.KOSDAQ -> it.copy(kosdaqSyncing = false, masterResult = resultText)
                    Board.OTHER -> it
                }
            }
        }
    }

    private fun formatMaster(result: InstrumentMasterSyncResult): String {
        val status = if (result.success) "SUCCESS" else "FAILED"
        return buildString {
            append("${result.board} $status")
            append(" parsed=${result.parsed}")
            append(" inserted=${result.inserted}")
            append(" updated=${result.updated}")
            append(" deactivated=${result.deactivated}")
            append(" invalid=${result.invalid}")
            result.failureReason?.let { append(" $it") }
        }
    }

    private fun formatHistorical(result: HistoricalSyncResult): String {
        return buildString {
            append(result.status.name)
            append(" API Calls=${result.requestCount}")
            append(" Received Bars=${result.receivedCount}")
            append(" Persisted Bars=${result.persistedCount}")
            append(" First Date=${result.firstDate ?: "-"}")
            append(" Last Date=${result.lastDate ?: "-"}")
        }
    }

    private fun publicError(error: Throwable): String = when (error) {
        is InstrumentMasterException -> error.publicMessage
        is HistoricalSyncException -> error.publicMessage
        is KisMarketException -> error.publicMessage
        else -> "요청을 처리할 수 없습니다"
    }

    private fun parseDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw.trim())
    } catch (_: DateTimeParseException) {
        null
    }

    private data class FormState(
        val query: String = "",
        val searchResults: List<InstrumentEntity> = emptyList(),
        val selected: InstrumentEntity? = null,
        val startDate: String = "",
        val endDate: String = "",
        val latestDate: String? = null,
        val kospiSyncing: Boolean = false,
        val kosdaqSyncing: Boolean = false,
        val historicalSyncing: Boolean = false,
        val incrementalSyncing: Boolean = false,
        val masterResult: String? = null,
        val historicalResult: String? = null,
        val message: String? = null,
    )
}
