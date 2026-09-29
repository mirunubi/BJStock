package com.mirunubi.bjstock.core.marketdata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.kis.KisEnvironment
import com.mirunubi.bjstock.core.kis.market.CurrentStockQuote
import com.mirunubi.bjstock.core.kis.market.DailyStockBar
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorAudit
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.kis.market.KisMarketException
import com.mirunubi.bjstock.core.kis.market.KisMarketRepository
import com.mirunubi.bjstock.core.kis.market.KisPriceAdjustment
import com.mirunubi.bjstock.core.kis.market.KisRequestPolicy
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
class SyncHistoricalDailyBarsUseCaseTest {
    private lateinit var database: BJStockDatabase
    private lateinit var localRepository: MarketDataLocalRepository
    private lateinit var marketRepository: ScriptedKisMarketRepository
    private lateinit var historical: SyncHistoricalDailyBarsUseCase
    private lateinit var fromLatest: SyncDailyBarsFromLatestUseCase
    private var instrumentId: Long = 0
    private var environment = KisEnvironment.PRODUCTION
    private val events = mutableListOf<String>()
    private val sleeps = mutableListOf<Long>()
    private var persistCalls = 0

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
            now = {
                persistCalls += 1
                Instant.parse("2026-09-29T00:00:00Z").plusSeconds(persistCalls.toLong())
            },
        )
        marketRepository = ScriptedKisMarketRepository(events)
        historical = SyncHistoricalDailyBarsUseCase(
            marketRepository = marketRepository,
            localRepository = localRepository,
            environment = { environment },
            sleep = { millis ->
                events += "sleep:$millis"
                sleeps += millis
            },
        )
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
    fun resyncSameRange_keepsPrimaryIdsAndUpdatesValues() = runBlocking {
        marketRepository.defaultBars = gate11Bars(close = 70_000)
        historical(instrumentId, GATE11_START, GATE11_END)
        val first = storedRows()
        marketRepository.defaultBars = gate11Bars(close = 70_500)
        historical(instrumentId, GATE11_START, GATE11_END)
        val second = storedRows()
        assertEquals(first.map { it.id to it.tradeDate }, second.map { it.id to it.tradeDate })
        assertTrue(second.all { it.closePrice == 70_500L })
        assertEquals(first.map { it.createdAt }, second.map { it.createdAt })
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

    @Test
    fun productionPacing_sleepsMinimumIntervalBetweenChunks() = runBlocking {
        marketRepository.defaultBars = gate11Bars(close = 70_000)
        val result = historical(instrumentId, GATE11_START, GATE11_END)
        assertEquals(listOf("call:1", "sleep:100", "call:2", "sleep:100", "call:3"), events)
        assertEquals(KisRequestPolicy.PRODUCTION_MIN_INTERVAL_MILLIS, 100L)
        assertEquals(3, result.requestCount)
        assertTrue(marketRepository.adjustments.all { it == KisPriceAdjustment.ADJUSTED })
    }

    @Test
    fun virtualPacing_sleepsFiveHundredMillisBetweenChunks() = runBlocking {
        environment = KisEnvironment.VIRTUAL
        marketRepository.defaultBars = gate11Bars(close = 70_000)
        historical(instrumentId, GATE11_START, GATE11_END)
        assertEquals(listOf("call:1", "sleep:500", "call:2", "sleep:500", "call:3"), events)
        assertEquals(KisRequestPolicy.VIRTUAL_MIN_INTERVAL_MILLIS, 500L)
    }

    @Test
    fun firstRequest_hasNoInitialDelay() = runBlocking {
        marketRepository.defaultBars = listOf(bar(LocalDate.of(2026, 9, 28), 70_000))
        historical(instrumentId, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28))
        assertEquals(listOf("call:1"), events)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun rateLimit_retriesSameChunkAfterWait_boundedAtThreeAttempts() = runBlocking {
        marketRepository.defaultBars = gate11Bars(close = 70_000)
        marketRepository.failures[2] = rateLimited()
        marketRepository.failures[3] = rateLimited()
        marketRepository.failures[4] = rateLimited()
        val error = runCatching { historical(instrumentId, GATE11_START, GATE11_END) }.exceptionOrNull()
        assertEquals(KisMarketErrorKind.RATE_LIMITED, (error as KisMarketException).kind)
        assertEquals(KisRequestPolicy.RATE_LIMIT_MSG_CD, error.audit?.msgCd)
        val chunk2 = marketRepository.calls[1]
        assertEquals(listOf(chunk2, chunk2, chunk2), marketRepository.calls.drop(1))
        assertEquals(listOf(100L, 61_000L, 61_000L), sleeps)
        assertEquals(KisRequestPolicy.RATE_LIMIT_MAX_ATTEMPTS, marketRepository.calls.count { it == chunk2 })
    }

    @Test
    fun rateLimitThenSuccess_continuesAndPersistsOnce() = runBlocking {
        marketRepository.defaultBars = gate11Bars(close = 70_000)
        marketRepository.failures[2] = rateLimited()
        val result = historical(instrumentId, GATE11_START, GATE11_END)
        assertEquals(
            listOf("call:1", "sleep:100", "call:2", "sleep:61000", "call:3", "sleep:100", "call:4"),
            events,
        )
        assertEquals(marketRepository.calls[1], marketRepository.calls[2])
        assertEquals(4, result.requestCount)
        assertEquals(HistoricalSyncStatus.SUCCESS, result.status)
        assertEquals(1, persistCalls)
        assertEquals(gate11Bars(close = 70_000).size, database.marketDailyBarDao().count())
    }

    @Test
    fun rateLimitExhausted_failsWholeSyncWithoutMutation() = runBlocking {
        seedExistingRows()
        val before = storedRows()
        val persistBefore = persistCalls
        marketRepository.defaultBars = gate11Bars(close = 99_000)
        marketRepository.failures[3] = rateLimited()
        marketRepository.failures[4] = rateLimited()
        marketRepository.failures[5] = rateLimited()
        val error = runCatching { historical(instrumentId, GATE11_START, GATE11_END) }.exceptionOrNull()
        assertEquals(KisMarketErrorKind.RATE_LIMITED, (error as KisMarketException).kind)
        assertEquals(5, marketRepository.calls.size)
        assertEquals(before, storedRows())
        assertEquals(persistBefore, persistCalls)
    }

    @Test
    fun nonRateLimitErrors_areNotRetried_andWriteNothing() = runBlocking {
        seedExistingRows()
        val before = storedRows()
        val nonRetryable = listOf(
            KisMarketException(KisMarketErrorKind.HTTP, "연결 실패", KisMarketErrorAudit(httpCode = 500)),
            KisMarketException(
                KisMarketErrorKind.HTTP,
                "연결 실패",
                KisMarketErrorAudit(httpCode = 500, msgCd = "EGW00500"),
            ),
            KisMarketException(KisMarketErrorKind.MALFORMED_RESPONSE, "KIS 응답 오류"),
            KisMarketException(KisMarketErrorKind.AUTHENTICATION, "인증 필요"),
            KisMarketException(KisMarketErrorKind.BUSINESS, "KIS 응답 오류"),
            KisMarketException(KisMarketErrorKind.MAPPING_FAILURE, "KIS 응답 오류"),
        )
        nonRetryable.forEach { failure ->
            marketRepository.reset()
            events.clear()
            sleeps.clear()
            marketRepository.defaultBars = gate11Bars(close = 99_000)
            marketRepository.failures[2] = failure
            val error = runCatching { historical(instrumentId, GATE11_START, GATE11_END) }.exceptionOrNull()
            assertEquals(failure, error)
            assertEquals(2, marketRepository.calls.size)
            assertEquals(listOf(100L), sleeps)
            assertEquals(before, storedRows())
        }
    }

    private suspend fun seedExistingRows() {
        localRepository.persistDailyBars(
            database.instrumentDao().findById(instrumentId)!!,
            gate11Bars(close = 70_000),
            KisPriceAdjustment.ADJUSTED,
        )
    }

    private suspend fun storedRows(): List<MarketDailyBarEntity> =
        localRepository.findByDateRange(instrumentId, GATE11_START, GATE11_END)

    private fun gate11Bars(close: Long): List<DailyStockBar> = listOf(
        bar(LocalDate.of(2026, 3, 3), close),
        bar(LocalDate.of(2026, 6, 15), close),
        bar(LocalDate.of(2026, 9, 28), close),
    )

    private fun rateLimited() = KisMarketException(
        kind = KisMarketErrorKind.RATE_LIMITED,
        publicMessage = "KIS 요청 한도 초과",
        audit = KisMarketErrorAudit(httpCode = 500, msgCd = KisRequestPolicy.RATE_LIMIT_MSG_CD),
    )

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

    private companion object {
        val GATE11_START: LocalDate = LocalDate.of(2026, 3, 2)
        val GATE11_END: LocalDate = LocalDate.of(2026, 9, 28)
    }
}

private class ScriptedKisMarketRepository(
    private val events: MutableList<String>,
) : KisMarketRepository {
    val byRange = mutableMapOf<Pair<LocalDate, LocalDate>, List<DailyStockBar>>()
    var defaultBars: List<DailyStockBar> = emptyList()
    var failOnCall: Int? = null
    val failures = mutableMapOf<Int, KisMarketException>()
    val calls = mutableListOf<Pair<LocalDate, LocalDate>>()
    val adjustments = mutableListOf<KisPriceAdjustment>()
    var lastAdjustment: KisPriceAdjustment? = null

    fun reset() {
        failOnCall = null
        failures.clear()
        calls.clear()
        adjustments.clear()
    }

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
        events += "call:${calls.size}"
        adjustments += adjustment
        lastAdjustment = adjustment
        failures[calls.size]?.let { throw it }
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
