package com.mirunubi.bjstock.feature.stocks

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mirunubi.bjstock.core.error.SafeLogText
import com.mirunubi.bjstock.core.kis.market.KisMarketException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
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
 * Stocks tab state. Browsing reads stored data only; KIS is called solely from [requestCurrentPrice].
 * Screen order: detail over theme members over the search root; [back] closes the top layer.
 */
@HiltViewModel
class StocksViewModel @Inject constructor(
    private val source: StocksDataSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StocksUiState())
    val uiState: StateFlow<StocksUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var themesJob: Job? = null
    private var membersJob: Job? = null
    private var detailJob: Job? = null
    private var quoteJob: Job? = null

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        searchJob?.cancel()
        val query = value.trim()
        if (query.isEmpty()) {
            _uiState.update { it.copy(search = SearchState.Idle) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            runSearch(query)
        }
    }

    fun submitSearch() {
        searchJob?.cancel()
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return
        searchJob = viewModelScope.launch { runSearch(query) }
    }

    fun clearQuery() = onQueryChange("")

    private suspend fun runSearch(query: String) {
        _uiState.update { it.copy(search = SearchState.Searching) }
        val next = try {
            val rows = source.search(query).map(StocksPresenter::row)
            if (rows.isEmpty()) SearchState.NoResults else SearchState.Results(rows)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logFailure("search", failure)
            SearchState.Failed(StocksPresenter.SEARCH_FAILED)
        }
        _uiState.update { it.copy(search = next) }
    }

    /** Reloads theme chips, and the open stock's themes, e.g. after returning from theme management. */
    fun refreshThemes() {
        themesJob?.cancel()
        themesJob = viewModelScope.launch {
            val chips = try {
                StocksPresenter.themes(source.activeThemes())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logFailure("themes", failure)
                emptyList()
            }
            _uiState.update { it.copy(themes = chips) }
            val detail = _uiState.value.detail as? DetailState.Content ?: return@launch
            val section = loadThemesOf(detail.instrument)
            updateContent(detail.instrument) { it.copy(themes = section) }
        }
    }

    fun openTheme(chip: ThemeChip) {
        membersJob?.cancel()
        _uiState.update { it.copy(themeBrowse = ThemeBrowse(chip, MembersState.Loading)) }
        membersJob = viewModelScope.launch {
            val members = try {
                val rows = source.themeMembers(chip.themeId).map(StocksPresenter::row)
                if (rows.isEmpty()) MembersState.Empty else MembersState.Loaded(rows)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logFailure("theme members", failure)
                MembersState.Failed(StocksPresenter.MEMBERS_FAILED)
            }
            _uiState.update { state ->
                if (state.themeBrowse?.theme == chip) state.copy(themeBrowse = ThemeBrowse(chip, members)) else state
            }
        }
    }

    fun select(row: InstrumentRow) {
        val instrument = row.instrument
        detailJob?.cancel()
        quoteJob?.cancel()
        val header = StocksPresenter.header(instrument)
        _uiState.update { it.copy(detail = DetailState.Loading(instrument, header)) }
        detailJob = viewModelScope.launch {
            val detail = try {
                loadDetail(instrument, header)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logFailure("detail", failure)
                DetailState.Failed(instrument, header, StocksPresenter.DETAIL_FAILED)
            }
            _uiState.update { state ->
                if (state.detail?.instrument == instrument) state.copy(detail = detail) else state
            }
        }
    }

    fun retryDetail() {
        val detail = _uiState.value.detail ?: return
        select(StocksPresenter.row(detail.instrument))
    }

    private suspend fun loadDetail(instrument: InstrumentSummary, header: StockHeader): DetailState.Content {
        val bars = source.recentBars(instrument.instrumentId, CHART_BARS)
        val factors = bars.lastOrNull()?.let { latest -> loadFactors(instrument, latest) }
            ?: FactorPanel.Unavailable(StocksPresenter.FACTOR_NO_DATA)
        return DetailState.Content(
            instrument = instrument,
            header = header,
            price = StocksPresenter.price(bars),
            chart = StocksPresenter.chart(bars),
            factors = factors,
            themes = loadThemesOf(instrument),
        )
    }

    /** Factors are calculated as of the latest stored trade date, never as of today. */
    private suspend fun loadFactors(instrument: InstrumentSummary, latest: StoredBar): FactorPanel = try {
        StocksPresenter.factors(latest.tradeDate, source.factors(instrument.instrumentId, latest.tradeDate))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logFailure("factors", failure)
        FactorPanel.Unavailable(StocksPresenter.FACTOR_FAILED)
    }

    private suspend fun loadThemesOf(instrument: InstrumentSummary): ThemesSection = try {
        ThemesSection.Loaded(StocksPresenter.themes(source.themesOf(instrument.instrumentId)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logFailure("instrument themes", failure)
        ThemesSection.Failed(StocksPresenter.THEMES_FAILED)
    }

    /** The only path that calls KIS. Shows the safe public message on failure. */
    fun requestCurrentPrice() {
        val detail = _uiState.value.detail as? DetailState.Content ?: return
        if (detail.quote == QuoteState.Loading) return
        val instrument = detail.instrument
        updateContent(instrument) { it.copy(quote = QuoteState.Loading) }
        quoteJob = viewModelScope.launch {
            val quote = try {
                QuoteState.Loaded(StocksPresenter.quote(source.currentPrice(instrument.symbol), Instant.now()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: KisMarketException) {
                logFailure("current price", failure)
                QuoteState.Failed("현재가 조회 실패 · ${failure.publicMessage}")
            } catch (failure: Exception) {
                logFailure("current price", failure)
                QuoteState.Failed(StocksPresenter.QUOTE_FAILED)
            }
            updateContent(instrument) { it.copy(quote = quote) }
        }
    }

    /** Closes the top layer (detail, then theme members). False when already at the search root. */
    fun back(): Boolean {
        val state = _uiState.value
        return when {
            state.detail != null -> {
                detailJob?.cancel()
                quoteJob?.cancel()
                _uiState.update { it.copy(detail = null) }
                true
            }
            state.themeBrowse != null -> {
                membersJob?.cancel()
                _uiState.update { it.copy(themeBrowse = null) }
                true
            }
            else -> false
        }
    }

    private fun updateContent(instrument: InstrumentSummary, change: (DetailState.Content) -> DetailState.Content) {
        _uiState.update { state ->
            val content = state.detail as? DetailState.Content
            if (content != null && content.instrument == instrument) state.copy(detail = change(content)) else state
        }
    }

    private fun logFailure(step: String, failure: Exception) {
        val type = SafeLogText.exceptionType(failure.javaClass.simpleName) ?: "Exception"
        Log.w(TAG, "Stocks $step failed ($type)")
    }

    companion object {
        const val CHART_BARS = 30
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        private const val TAG = "BJStockStocks"
    }
}
