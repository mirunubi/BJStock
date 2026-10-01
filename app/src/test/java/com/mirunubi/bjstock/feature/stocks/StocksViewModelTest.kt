package com.mirunubi.bjstock.feature.stocks

import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.kis.market.KisMarketException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StocksViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = FakeStocksDataSource()
    private lateinit var viewModel: StocksViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = StocksViewModel(source)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_hasNoSelection_andGuidesTheUser() = runTest(dispatcher) {
        source.themes += ThemeRef(1, "HBM관련")
        viewModel.refreshThemes()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SearchState.Idle, state.search)
        assertNull(state.detail)
        assertNull(state.themeBrowse)
        assertEquals(listOf(ThemeChip(1, "HBM관련")), state.themes)
        assertEquals("종목명이나 종목코드를 검색해 보세요.", StocksPresenter.GUIDE)
        assertTrue(source.searchCalls.isEmpty())
    }

    @Test
    fun typing_searchesAfterADebounce_showingSearchingFirst() = runTest(dispatcher) {
        viewModel.onQueryChange("0059")
        viewModel.onQueryChange("005930")
        dispatcher.scheduler.advanceTimeBy(StocksViewModel.SEARCH_DEBOUNCE_MILLIS + 1)
        dispatcher.scheduler.runCurrent()

        val results = viewModel.uiState.value.search as SearchState.Results
        assertEquals(listOf("005930"), source.searchCalls)
        assertEquals(listOf("삼성전자"), results.rows.map { it.name })
    }

    @Test
    fun searchByName_submit_andNoResults() = runTest(dispatcher) {
        viewModel.onQueryChange("SK하이닉스")
        viewModel.submitSearch()
        advanceUntilIdle()
        assertEquals(listOf("000660 · KOSPI"), (viewModel.uiState.value.search as SearchState.Results).rows.map { it.subtitle })

        viewModel.onQueryChange("없는종목")
        advanceUntilIdle()
        assertEquals(SearchState.NoResults, viewModel.uiState.value.search)

        viewModel.clearQuery()
        assertEquals(SearchState.Idle, viewModel.uiState.value.search)
    }

    @Test
    fun searchFailure_isSafeKorean() = runTest(dispatcher) {
        source.searchFailure = IllegalStateException("SELECT * FROM instruments near TEST_APP_SECRET")
        viewModel.onQueryChange("삼성")
        advanceUntilIdle()

        val failed = viewModel.uiState.value.search as SearchState.Failed
        assertEquals(StocksPresenter.SEARCH_FAILED, failed.message)
        assertFalse(viewModel.uiState.value.toString().contains("SELECT"))
    }

    @Test
    fun selecting_showsLatestStoredBar_chart_factors_andThemes() = runTest(dispatcher) {
        val bars = StocksFixtures.bars(LocalDate.of(2026, 8, 31), List(30) { 266_000L + it * 300 })
        source.bars[StocksFixtures.SAMSUNG.instrumentId] = bars
        source.themes += listOf(ThemeRef(1, "HBM관련"), ThemeRef(2, "반도체"), ThemeRef(3, "2차전지"))
        source.members[1] = listOf(StocksFixtures.SAMSUNG.instrumentId)
        source.members[2] = listOf(StocksFixtures.SAMSUNG.instrumentId, StocksFixtures.HYNIX.instrumentId)

        viewModel.select(StocksPresenter.row(StocksFixtures.SAMSUNG))
        assertTrue(viewModel.uiState.value.detail is DetailState.Loading)
        advanceUntilIdle()

        val detail = viewModel.uiState.value.detail as DetailState.Content
        assertEquals("삼성전자", detail.header.name)
        assertEquals("005930 · KOSPI", detail.header.subtitle)
        assertEquals(StocksPresenter.won(bars.last().closePrice), detail.price!!.close)
        assertEquals(StocksPresenter.date(bars.last().tradeDate), detail.price!!.tradeDate)
        assertEquals(30, (detail.chart as ChartState.Line).points.size)
        assertEquals(6, (detail.factors as FactorPanel.Rows).rows.size)
        assertEquals(listOf("HBM관련", "반도체"), (detail.themes as ThemesSection.Loaded).chips.map { it.name })
    }

    @Test
    fun factors_useTheLatestStoredTradeDate_notToday() = runTest(dispatcher) {
        val bars = StocksFixtures.bars(LocalDate.of(2026, 9, 1), List(29) { 100_000L })
        source.bars[StocksFixtures.SAMSUNG.instrumentId] = bars

        viewModel.select(StocksPresenter.row(StocksFixtures.SAMSUNG))
        advanceUntilIdle()

        assertEquals(listOf(StocksFixtures.SAMSUNG.instrumentId to LocalDate.of(2026, 9, 29)), source.factorCalls)
        assertEquals("2026.09.29 저장 일봉 기준", (viewModel.state().factors as FactorPanel.Rows).asOf)
    }

    @Test
    fun noStoredBars_showsEmptyStates_andCalculatesNoFactors() = runTest(dispatcher) {
        viewModel.select(StocksPresenter.row(StocksFixtures.HYNIX))
        advanceUntilIdle()

        val detail = viewModel.state()
        assertNull(detail.price)
        assertEquals(ChartState.Empty("저장된 시세 데이터가 없습니다."), detail.chart)
        assertEquals(FactorPanel.Unavailable(StocksPresenter.FACTOR_NO_DATA), detail.factors)
        assertEquals(ThemesSection.Loaded(emptyList()), detail.themes)
        assertTrue(source.factorCalls.isEmpty())
    }

    @Test
    fun liveQuote_isNeverCalledAutomatically() = runTest(dispatcher) {
        source.bars[StocksFixtures.SAMSUNG.instrumentId] = StocksFixtures.bars(LocalDate.of(2026, 9, 1), List(5) { 1_000L })
        source.themes += ThemeRef(1, "HBM관련")
        source.members[1] = listOf(StocksFixtures.SAMSUNG.instrumentId)

        viewModel.refreshThemes()
        viewModel.onQueryChange("삼성")
        advanceUntilIdle()
        viewModel.select((viewModel.uiState.value.search as SearchState.Results).rows.single())
        advanceUntilIdle()
        viewModel.openTheme(ThemeChip(1, "HBM관련"))
        viewModel.refreshThemes()
        advanceUntilIdle()
        viewModel.back()
        viewModel.select(StocksPresenter.row(StocksFixtures.SAMSUNG))
        advanceUntilIdle()

        assertTrue(source.quoteCalls.isEmpty())
        assertEquals(QuoteState.Idle, viewModel.state().quote)
    }

    @Test
    fun liveQuote_isCalledOnlyByTheExplicitAction() = runTest(dispatcher) {
        selectSamsung()

        viewModel.requestCurrentPrice()
        assertEquals(QuoteState.Loading, viewModel.state().quote)
        advanceUntilIdle()

        assertEquals(listOf("005930"), source.quoteCalls)
        val card = (viewModel.state().quote as QuoteState.Loaded).card
        assertEquals("276,000원", card.price)
        assertEquals("-1,000원 (-0.36%)", card.change)
        assertEquals(PriceDirection.DOWN, card.direction)
    }

    @Test
    fun liveQuoteFailure_showsOnlyTheSafePublicMessage() = runTest(dispatcher) {
        selectSamsung()
        source.quoteFailure = KisMarketException(kind = KisMarketErrorKind.AUTHENTICATION, publicMessage = "인증 필요")
        viewModel.requestCurrentPrice()
        advanceUntilIdle()
        assertEquals(QuoteState.Failed("현재가 조회 실패 · 인증 필요"), viewModel.state().quote)

        val raw = "HTTP 500 {\"msg1\":\"appsecret TEST_APP_SECRET\"} Bearer abc"
        source.quoteFailure = IllegalStateException(raw)
        viewModel.requestCurrentPrice()
        advanceUntilIdle()
        assertEquals(QuoteState.Failed(StocksPresenter.QUOTE_FAILED), viewModel.state().quote)
        listOf(raw, "IllegalStateException", "HTTP 500", "Bearer", "appsecret").forEach {
            assertFalse(it, viewModel.uiState.value.toString().contains(it))
        }
    }

    @Test
    fun themeBrowsing_listsMembers_andBackReturnsLayerByLayer() = runTest(dispatcher) {
        source.themes += ThemeRef(2, "반도체")
        source.members[2] = listOf(StocksFixtures.SAMSUNG.instrumentId, StocksFixtures.HYNIX.instrumentId)

        viewModel.openTheme(ThemeChip(2, "반도체"))
        advanceUntilIdle()
        val members = viewModel.uiState.value.themeBrowse!!.members as MembersState.Loaded
        assertEquals(listOf("삼성전자", "SK하이닉스"), members.rows.map { it.name })

        viewModel.select(members.rows.first())
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.detail is DetailState.Content)

        assertTrue(viewModel.back())
        assertNull(viewModel.uiState.value.detail)
        assertEquals("반도체", viewModel.uiState.value.themeBrowse!!.theme.name)
        assertTrue(viewModel.back())
        assertNull(viewModel.uiState.value.themeBrowse)
        assertFalse(viewModel.back())
    }

    @Test
    fun emptyTheme_showsAKoreanEmptyState() = runTest(dispatcher) {
        viewModel.openTheme(ThemeChip(9, "신규테마"))
        advanceUntilIdle()
        assertEquals(MembersState.Empty, viewModel.uiState.value.themeBrowse!!.members)
    }

    private fun TestScope.selectSamsung() {
        source.bars[StocksFixtures.SAMSUNG.instrumentId] = StocksFixtures.bars(LocalDate.of(2026, 9, 1), List(5) { 1_000L })
        viewModel.select(StocksPresenter.row(StocksFixtures.SAMSUNG))
        advanceUntilIdle()
    }

    private fun StocksViewModel.state(): DetailState.Content = uiState.value.detail as DetailState.Content
}
