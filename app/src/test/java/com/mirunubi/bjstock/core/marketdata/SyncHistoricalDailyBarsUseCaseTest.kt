package com.mirunubi.bjstock.core.marketdata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.kis.market.CurrentStockQuote
import com.mirunubi.bjstock.core.kis.market.DailyStockBar
import com.mirunubi.bjstock.core.kis.market.KisMarketException
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.kis.market.KisMarketRepository
import com.mirunubi.bjstock.core.kis.market.KisPriceAdjustment
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
class SyncHistoricalDailyBarsUseCaseTest {
    private lateinit var database: BJStockDatabase
    private lateinit var localRepository: MarketDataLocalRepository
    private lateinit var marketRepository: ScriptedKisMarketRepository
    private lateinit var historical: SyncHistoricalDailyBarsUseCase
    private lateinit var fromLatest: SyncDailyBarsFromLatestUseCase
    private var instrumentId: Long = 0

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localRepository = MarketDataLocalRepository(
            database = database,
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
        )
        marketRepository = ScriptedKisMarketRepository()
        historical = SyncHistoricalDailyBarsUseCase(marketRepository, localRepository)
        fromLatest = SyncDailyBarsFromLatestUseCase(localRepository, historical)
        instrumentId = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "삼성전자"),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun multiChunkMerge_deduplicatesAndSortsAscending() = runBlocking {
        val start = LocalDate.of(2026, 1, 1)
        val end = start.plusDays(90)
        marketRepository.byRange[start to start.plusDays(89)] = listOf(
            bar(LocalDate.of(2026, 3, 31), 3),
            bar(LocalDate.of(2026, 1, 2), 1),
            bar(LocalDate.of(2026, 1, 2), 11),
        )
        marketRepository.byRange[start.plusDays(90) to end] = listOf(
            bar(LocalDate.of(2026, 4, 1), 4),
            bar(LocalDate.of(2026, 3, 31), 99),
        )
        val result = historical(instrumentId, start, end)
        assertEquals(2, result.requestCount)
        assertEquals(KisPriceAdjustment.ADJUSTED, marketRepository.lastAdjustment)
        val stored = localRepository.findByDateRange(instrumentId, start, end)
        assertEquals(
            listOf(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 1)),
            stored.map { it.tradeDate },
        )
        assertEquals(99L, stored[1].closePrice)
        assertEquals(3, result.persistedCount)
    }

    @Test
    fun partialNetworkFailure_writesNothing() = runBlocking {
        val start = LocalDate.of(2026, 1, 1)
        val end = start.plusDays(180)
        marketRepository.failOnCall = 3
        marketRepository.defaultBars = listOf(bar(LocalDate.of(2026, 1, 2), 70_000))
        val error = runCatching { historical(instrumentId, start, end) }.exceptionOrNull()
        assertTrue(error is KisMarketException)
        assertEquals(0, database.marketDailyBarDao().count())
        assertEquals(3, marketRepository.calls.size)
    }

    @Test
    fun resyncSameRange_doesNotIncreaseRowCount() = runBlocking {
        val start = LocalDate.of(2025, 9, 21)
        val end = LocalDate.of(2026, 9, 20)
        marketRepository.defaultBars = listOf(
            bar(LocalDate.of(2025, 10, 1), 70_000),
            bar(LocalDate.of(2026, 3, 1), 71_000),
        )
        historical(instrumentId, start, end)
        val countAfterFirst = localRepository.countByInstrument(instrumentId)
        val second = historical(instrumentId, start, end)
        assertEquals(countAfterFirst, localRepository.countByInstrument(instrumentId))
        assertEquals(0, second.let { localRepository.countByInstrument(instrumentId) - countAfterFirst })
        assertTrue(marketRepository.calls.size > 1)
    }

    @Test
    fun incremental_startsTheDayAfterLatest() = runBlocking {
        localRepository.persistDailyBars(
            database.instrumentDao().findById(instrumentId)!!,
            listOf(bar(LocalDate.of(2026, 9, 10), 70_000)),
            KisPriceAdjustment.ADJUSTED,
        )
        marketRepository.defaultBars = listOf(bar(LocalDate.of(2026, 9, 11), 70_100))
        fromLatest(instrumentId, LocalDate.of(2026, 9, 20))
        assertEquals(LocalDate.of(2026, 9, 11), marketRepository.calls.first().first)
        assertEquals(LocalDate.of(2026, 9, 20), marketRepository.calls.first().second)
        assertEquals(KisPriceAdjustment.ADJUSTED, marketRepository.lastAdjustment)
    }

    @Test
    fun alreadyCurrent_isNoOpWithoutNetworkOrWrites() = runBlocking {
        localRepository.persistDailyBars(
            database.instrumentDao().findById(instrumentId)!!,
            listOf(bar(LocalDate.of(2026, 9, 20), 70_000)),
            KisPriceAdjustment.ADJUSTED,
        )
        val before = database.marketDailyBarDao().count()
        val result = fromLatest(instrumentId, LocalDate.of(2026, 9, 20))
        assertEquals(HistoricalSyncStatus.NO_OP, result.status)
        assertEquals(0, result.requestCount)
        assertEquals(0, marketRepository.calls.size)
        assertEquals(before, database.marketDailyBarDao().count())
    }

    private fun bar(date: LocalDate, close: Long) = DailyStockBar(
        symbol = "005930",
        tradeDate = date,
        openPrice = close,
        highPrice = close,
        lowPrice = close,
        closePrice = close,
        volume = 1,
        tradingValue = close,
    )
}

private class ScriptedKisMarketRepository : KisMarketRepository {
    val byRange = mutableMapOf<Pair<LocalDate, LocalDate>, List<DailyStockBar>>()
    var defaultBars: List<DailyStockBar> = emptyList()
    var failOnCall: Int? = null
    val calls = mutableListOf<Pair<LocalDate, LocalDate>>()
    var lastAdjustment: KisPriceAdjustment? = null

    override suspend fun inquireCurrentPrice(symbol: String): CurrentStockQuote {
        error("current quote is not used")
    }

    override suspend fun inquireDailyBars(
        symbol: String,
        startDate: LocalDate,
        endDate: LocalDate,
        adjustment: KisPriceAdjustment,
    ): List<DailyStockBar> {
        calls += startDate to endDate
        lastAdjustment = adjustment
        failOnCall?.let { limit ->
            if (calls.size >= limit) {
                throw KisMarketException(
                    kind = KisMarketErrorKind.HTTP,
                    publicMessage = "KIS 응답 오류",
                )
            }
        }
        return byRange[startDate to endDate] ?: defaultBars
    }
}
