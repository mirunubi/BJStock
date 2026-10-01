package com.mirunubi.bjstock.feature.stocks

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.factor.FactorCalculationService
import com.mirunubi.bjstock.core.factor.FactorCalculationStatus
import com.mirunubi.bjstock.core.factor.FactorCodes
import com.mirunubi.bjstock.core.factor.FactorValueRepository
import com.mirunubi.bjstock.core.factor.SystemFactorRegistryFactory
import com.mirunubi.bjstock.core.kis.market.CurrentStockQuote
import com.mirunubi.bjstock.core.kis.market.DailyStockBar
import com.mirunubi.bjstock.core.kis.market.KisMarketRepository
import com.mirunubi.bjstock.core.kis.market.KisPriceAdjustment
import com.mirunubi.bjstock.core.marketdata.MarketDataSource
import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.theme.ThemeService
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomStocksDataSourceTest {
    private lateinit var database: BJStockDatabase
    private lateinit var themes: ThemeService
    private lateinit var source: RoomStocksDataSource
    private val kis = CountingKisMarketRepository()
    private var samsung = 0L
    private var hynix = 0L
    private var ecopro = 0L
    private var delisted = 0L
    private val start = LocalDate.of(2026, 6, 1)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val factorValues = FactorValueRepository(database.factorDao(), now = { Instant.EPOCH })
        factorValues.ensureSystemFactorDefinitions()
        themes = ThemeService(database.themeDao(), database.instrumentDao(), now = { Instant.EPOCH })
        source = RoomStocksDataSource(
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            factorCalculationService = FactorCalculationService(
                registry = SystemFactorRegistryFactory.create(),
                instrumentDao = database.instrumentDao(),
                marketDailyBarDao = database.marketDailyBarDao(),
                factorValues = factorValues,
            ),
            themeService = themes,
            kisMarketRepository = kis,
        )
        val dao = database.instrumentDao()
        samsung = dao.insert(InstrumentEntity(market = "KRX", symbol = "005930", name = "삼성전자", board = Board.KOSPI))
        dao.insert(InstrumentEntity(market = "KRX", symbol = "005935", name = "삼성전자우", board = Board.KOSPI))
        hynix = dao.insert(InstrumentEntity(market = "KRX", symbol = "000660", name = "SK하이닉스", board = Board.KOSPI))
        ecopro = dao.insert(InstrumentEntity(market = "KRX", symbol = "247540", name = "에코프로비엠", board = Board.KOSDAQ))
        delisted = dao.insert(
            InstrumentEntity(market = "KRX", symbol = "009999", name = "삼성테스트폐지", board = Board.KOSPI, isActive = false),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun search_bySymbol_name_andPartial() = runBlocking {
        assertEquals(listOf("005930"), source.search("005930").map { it.symbol })
        assertEquals(listOf("005930", "005935"), source.search("삼성전자").map { it.symbol })
        assertEquals(listOf("000660"), source.search("000660").map { it.symbol })
        assertEquals(listOf("SK하이닉스"), source.search("하이닉").map { it.name })
        assertEquals(listOf("005930", "005935"), source.search(" 00593 ").map { it.symbol })
    }

    @Test
    fun search_neverReturnsInactiveInstruments() = runBlocking {
        assertTrue(source.search("009999").isEmpty())
        assertTrue(source.search("삼성").none { it.instrumentId == delisted })
    }

    @Test
    fun search_carriesTheBoard_forKospiKosdaqBadges() = runBlocking {
        assertEquals(Board.KOSPI, source.search("005930").single().board)
        val kosdaq = source.search("에코프로").single()
        assertEquals(Board.KOSDAQ, kosdaq.board)
        assertEquals("247540 · KOSDAQ", StocksPresenter.row(kosdaq).subtitle)
    }

    @Test
    fun recentBars_areTheLatestStoredBars_oldestFirst() = runBlocking {
        insertBars(samsung, 40)

        val bars = source.recentBars(samsung, 30)

        assertEquals(30, bars.size)
        assertEquals(start.plusDays(10), bars.first().tradeDate)
        assertEquals(start.plusDays(39), bars.last().tradeDate)
        assertEquals(bars.sortedBy { it.tradeDate }, bars)
        assertTrue(source.recentBars(hynix, 30).isEmpty())
    }

    @Test
    fun factors_areCalculatedForDisplay_withPersistFalse() = runBlocking {
        insertBars(samsung, 70)
        val asOf = start.plusDays(69)

        val results = source.factors(samsung, asOf)

        assertEquals(FactorCodes.SYSTEM, results.map { it.factorCode })
        assertTrue(results.all { it.status == FactorCalculationStatus.SUCCESS && it.asOfDate == asOf })
        assertEquals(0, database.factorDao().countValuesByInstrument(samsung))
    }

    @Test
    fun factors_withShortHistory_reportInsufficientHistory_andStoreNothing() = runBlocking {
        insertBars(samsung, 30)

        val results = source.factors(samsung, start.plusDays(29)).associateBy { it.factorCode }

        assertEquals(FactorCalculationStatus.SUCCESS, results.getValue(FactorCodes.PRICE_VS_MA20).status)
        assertEquals(FactorCalculationStatus.INSUFFICIENT_HISTORY, results.getValue(FactorCodes.MOMENTUM_60D).status)
        assertEquals(null, results.getValue(FactorCodes.MOMENTUM_60D).normalizedScore)
        assertEquals(0, database.factorDao().countValuesByInstrument(samsung))
    }

    @Test
    fun themes_ofAnInstrument_areActiveThemesContainingIt() = runBlocking {
        val hbm = themes.createTheme("HBM관련")
        val semis = themes.createTheme("반도체")
        val retired = themes.createTheme("종료테마")
        themes.addInstrument(hbm, samsung)
        themes.addInstrument(semis, samsung)
        themes.addInstrument(semis, hynix)
        themes.addInstrument(retired, samsung)
        themes.setActive(retired, false)

        assertEquals(listOf("HBM관련", "반도체"), source.themesOf(samsung).map { it.name }.sorted())
        assertEquals(listOf("반도체"), source.themesOf(hynix).map { it.name })
        assertTrue(source.themesOf(ecopro).isEmpty())
        assertTrue(source.activeThemes().none { it.name == "종료테마" })
    }

    @Test
    fun themeMembers_areActiveInstruments_bySymbol() = runBlocking {
        val semis = themes.createTheme("반도체")
        themes.addInstrument(semis, samsung)
        themes.addInstrument(semis, hynix)
        themes.addInstrument(semis, delisted)

        assertEquals(listOf("000660", "005930"), source.themeMembers(semis).map { it.symbol })
    }

    @Test
    fun browsing_mutatesNothing_andNeverCallsKis() = runBlocking {
        insertBars(samsung, 70)
        val semis = themes.createTheme("반도체")
        themes.addInstrument(semis, samsung)
        val before = rowCounts()

        source.search("삼성")
        source.recentBars(samsung, 30)
        source.factors(samsung, start.plusDays(69))
        source.factors(hynix, start.plusDays(69))
        source.activeThemes()
        source.themesOf(samsung)
        source.themeMembers(semis)

        assertEquals(before, rowCounts())
        assertEquals(0, kis.currentPriceCalls)
        assertEquals(0, kis.dailyBarCalls)
    }

    @Test
    fun currentPrice_delegatesToTheReadOnlyRepository() = runBlocking {
        val quote = source.currentPrice("005930")
        assertEquals("005930", quote.symbol)
        assertEquals(1, kis.currentPriceCalls)
        assertEquals(0, kis.dailyBarCalls)
    }

    private fun rowCounts(): Map<String, Int> {
        val tables = mutableListOf<String>()
        database.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .use { while (it.moveToNext()) tables += it.getString(0) }
        return tables.associateWith { table ->
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }
        }
    }

    private suspend fun insertBars(instrumentId: Long, count: Int) {
        repeat(count) { index ->
            val close = 100_000L + index * 50
            database.marketDailyBarDao().insert(
                MarketDailyBarEntity(
                    instrumentId = instrumentId,
                    tradeDate = start.plusDays(index.toLong()),
                    openPrice = close,
                    highPrice = close,
                    lowPrice = close,
                    closePrice = close,
                    volume = 1_000L + index,
                    source = MarketDataSource.KIS,
                    collectedAt = Instant.EPOCH,
                    createdAt = Instant.EPOCH,
                ),
            )
        }
    }

    private class CountingKisMarketRepository : KisMarketRepository {
        var currentPriceCalls = 0
        var dailyBarCalls = 0

        override suspend fun inquireCurrentPrice(symbol: String): CurrentStockQuote {
            currentPriceCalls++
            return StocksFixtures.quote(symbol)
        }

        override suspend fun inquireDailyBars(
            symbol: String,
            startDate: LocalDate,
            endDate: LocalDate,
            adjustment: KisPriceAdjustment,
        ): List<DailyStockBar> {
            dailyBarCalls++
            return emptyList()
        }
    }
}
