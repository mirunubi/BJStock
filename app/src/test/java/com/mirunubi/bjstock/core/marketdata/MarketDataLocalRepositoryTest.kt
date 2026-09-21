package com.mirunubi.bjstock.core.marketdata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.kis.market.DailyStockBar
import com.mirunubi.bjstock.core.kis.market.KisPriceAdjustment
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MarketDataLocalRepositoryTest {
    private lateinit var database: BJStockDatabase
    private lateinit var repository: MarketDataLocalRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MarketDataLocalRepository(
            database = database,
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun persistThreeBars_countAndOrder() = runBlocking {
        val instrument = insertSamsung()
        val result = repository.persistDailyBars(
            instrument,
            listOf(
                bar(DATE_3, 71_000),
                bar(DATE_1, 70_000),
                bar(DATE_2, 70_500),
            ),
            KisPriceAdjustment.ADJUSTED,
        )
        assertEquals(3, result.insertedCount)
        assertEquals(3, repository.countByInstrument(instrument.id))
        val stored = repository.findByDateRange(instrument.id, DATE_1, DATE_3)
        assertEquals(listOf(DATE_1, DATE_2, DATE_3), stored.map { it.tradeDate })
        assertTrue(stored.all { it.source == MarketDataSource.KIS })
        assertEquals(DATE_1, result.firstDate)
        assertEquals(DATE_3, result.lastDate)
    }

    @Test
    fun persistDuplicateBatch_doesNotCreateExtraRows() = runBlocking {
        val instrument = insertSamsung()
        val bars = listOf(bar(DATE_1, 70_000), bar(DATE_2, 70_500), bar(DATE_3, 71_000))
        repository.persistDailyBars(instrument, bars, KisPriceAdjustment.ADJUSTED)
        val second = repository.persistDailyBars(instrument, bars, KisPriceAdjustment.ADJUSTED)
        assertEquals(3, repository.countByInstrument(instrument.id))
        assertEquals(0, second.insertedCount)
        assertEquals(3, second.unchangedCount)
    }

    @Test
    fun persistUpdate_replacesCloseAndKeepsCountAndId() = runBlocking {
        val instrument = insertSamsung()
        repository.persistDailyBars(instrument, listOf(bar(DATE_3, 70_000)), KisPriceAdjustment.ADJUSTED)
        val original = repository.findLatest(instrument.id)
        assertNotNull(original)
        val originalId = original!!.id
        val originalCreatedAt = original.createdAt
        val updated = repository.persistDailyBars(
            instrument,
            listOf(bar(DATE_3, 70_500)),
            KisPriceAdjustment.ADJUSTED,
        )
        assertEquals(1, updated.updatedCount)
        assertEquals(1, repository.countByInstrument(instrument.id))
        val latest = repository.findLatest(instrument.id)!!
        assertEquals(70_500L, latest.closePrice)
        assertEquals(originalId, latest.id)
        assertEquals(originalCreatedAt, latest.createdAt)
        assertTrue(latest.collectedAt >= original.collectedAt)
    }

    @Test
    fun missingInstrument_doesNotWriteBars() = runBlocking {
        val error = runCatching {
            repository.requireInstrument("KRX", "005930")
        }.exceptionOrNull()
        assertTrue(error is MarketDataPersistenceException)
        assertEquals(
            MarketDataPersistenceErrorKind.INSTRUMENT_NOT_FOUND,
            (error as MarketDataPersistenceException).kind,
        )
        assertEquals(0, database.marketDailyBarDao().count())
    }

    @Test
    fun invalidBar_rollsBackEntireBatch() = runBlocking {
        val instrument = insertSamsung()
        val error = runCatching {
            repository.persistDailyBars(
                instrument,
                listOf(
                    bar(DATE_1, 70_000),
                    DailyStockBar(
                        symbol = "005930",
                        tradeDate = DATE_2,
                        openPrice = 70_000,
                        highPrice = 69_000,
                        lowPrice = 70_500,
                        closePrice = 70_200,
                        volume = 1,
                        tradingValue = 1,
                    ),
                    bar(DATE_3, 71_000),
                ),
                KisPriceAdjustment.ADJUSTED,
            )
        }.exceptionOrNull()
        assertEquals(
            MarketDataPersistenceErrorKind.INVALID_DAILY_BAR,
            (error as MarketDataPersistenceException).kind,
        )
        assertEquals(0, repository.countByInstrument(instrument.id))
        assertEquals(0, database.marketDailyBarDao().count())
    }

    @Test
    fun unadjustedBars_areRejected() = runBlocking {
        val instrument = insertSamsung()
        val error = runCatching {
            repository.persistDailyBars(
                instrument,
                listOf(bar(DATE_1, 70_000)),
                KisPriceAdjustment.UNADJUSTED,
            )
        }.exceptionOrNull()
        assertEquals(
            MarketDataPersistenceErrorKind.UNSUPPORTED_PRICE_ADJUSTMENT,
            (error as MarketDataPersistenceException).kind,
        )
        assertEquals(0, database.marketDailyBarDao().count())
    }

    @Test
    fun dateRangeQuery_returnsInclusiveAscendingSubset() = runBlocking {
        val instrument = insertSamsung()
        repository.persistDailyBars(
            instrument,
            listOf(
                bar(LocalDate.of(2026, 9, 1), 70_100),
                bar(LocalDate.of(2026, 9, 2), 70_200),
                bar(LocalDate.of(2026, 9, 3), 70_300),
                bar(LocalDate.of(2026, 9, 4), 70_400),
                bar(LocalDate.of(2026, 9, 5), 70_500),
            ),
            KisPriceAdjustment.ADJUSTED,
        )
        val range = repository.findByDateRange(
            instrument.id,
            LocalDate.of(2026, 9, 2),
            LocalDate.of(2026, 9, 4),
        )
        assertEquals(3, range.size)
        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 2),
                LocalDate.of(2026, 9, 3),
                LocalDate.of(2026, 9, 4),
            ),
            range.map { it.tradeDate },
        )
    }

    @Test
    fun latestAndCount() = runBlocking {
        val instrument = insertSamsung()
        repository.persistDailyBars(
            instrument,
            listOf(bar(DATE_1, 70_000), bar(DATE_3, 71_000), bar(DATE_2, 70_500)),
            KisPriceAdjustment.ADJUSTED,
        )
        assertEquals(DATE_3, repository.findLatest(instrument.id)?.tradeDate)
        assertEquals(3, repository.countByInstrument(instrument.id))
    }

    @Test
    fun emptyBatch_isSuccessEmpty() = runBlocking {
        val instrument = insertSamsung()
        val result = repository.persistDailyBars(instrument, emptyList(), KisPriceAdjustment.ADJUSTED)
        assertEquals(MarketDataPersistStatus.SUCCESS_EMPTY, result.status)
        assertEquals(0, result.persistedCount)
        assertEquals(0, database.marketDailyBarDao().count())
    }

    private suspend fun insertSamsung(): InstrumentEntity {
        val id = database.instrumentDao().insert(
            InstrumentEntity(
                market = "KRX",
                symbol = "005930",
                name = "삼성전자",
            ),
        )
        return database.instrumentDao().findById(id)!!
    }

    private fun bar(tradeDate: LocalDate, close: Long): DailyStockBar = DailyStockBar(
        symbol = "005930",
        tradeDate = tradeDate,
        openPrice = close,
        highPrice = close + 100,
        lowPrice = close - 100,
        closePrice = close,
        volume = 1_000,
        tradingValue = 1_000_000,
    )

    companion object {
        private val DATE_1 = LocalDate.of(2026, 9, 16)
        private val DATE_2 = LocalDate.of(2026, 9, 17)
        private val DATE_3 = LocalDate.of(2026, 9, 18)
    }
}
