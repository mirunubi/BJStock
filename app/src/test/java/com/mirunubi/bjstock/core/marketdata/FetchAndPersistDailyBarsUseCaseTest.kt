package com.mirunubi.bjstock.core.marketdata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.kis.market.CurrentStockQuote
import com.mirunubi.bjstock.core.kis.market.DailyStockBar
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
class FetchAndPersistDailyBarsUseCaseTest {
    private lateinit var database: BJStockDatabase
    private lateinit var localRepository: MarketDataLocalRepository
    private lateinit var marketRepository: RecordingKisMarketRepository
    private lateinit var useCase: FetchAndPersistDailyBarsUseCase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localRepository = MarketDataLocalRepository(
            database = database,
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
        )
        marketRepository = RecordingKisMarketRepository()
        useCase = FetchAndPersistDailyBarsUseCase(marketRepository, localRepository)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun missingInstrument_doesNotCallNetworkOrWrite() = runBlocking {
        val error = runCatching {
            useCase("005930", LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 18))
        }.exceptionOrNull()
        assertEquals(
            MarketDataPersistenceErrorKind.INSTRUMENT_NOT_FOUND,
            (error as MarketDataPersistenceException).kind,
        )
        assertEquals(0, marketRepository.dailyCalls)
        assertEquals(0, database.marketDailyBarDao().count())
    }

    @Test
    fun fetchAndPersist_writesAdjustedBars() = runBlocking {
        database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "삼성전자"),
        )
        marketRepository.bars = listOf(
            DailyStockBar("005930", LocalDate.of(2026, 9, 16), 70_000, 70_100, 69_900, 70_000, 10, 100),
            DailyStockBar("005930", LocalDate.of(2026, 9, 17), 70_200, 70_300, 70_100, 70_250, 11, 110),
        )
        val result = useCase("005930", LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 17))
        val instrument = database.instrumentDao().findByMarketAndSymbol("KRX", "005930")!!
        assertEquals(1, marketRepository.dailyCalls)
        assertEquals(KisPriceAdjustment.ADJUSTED, marketRepository.lastAdjustment)
        assertEquals(2, result.insertedCount)
        assertEquals(2, localRepository.countByInstrument(instrument.id))
    }
}

private class RecordingKisMarketRepository : KisMarketRepository {
    var bars: List<DailyStockBar> = emptyList()
    var dailyCalls: Int = 0
    var lastAdjustment: KisPriceAdjustment? = null

    override suspend fun inquireCurrentPrice(symbol: String): CurrentStockQuote {
        error("current quote is not persisted in Phase 3-C")
    }

    override suspend fun inquireDailyBars(
        symbol: String,
        startDate: LocalDate,
        endDate: LocalDate,
        adjustment: KisPriceAdjustment,
    ): List<DailyStockBar> {
        dailyCalls += 1
        lastAdjustment = adjustment
        return bars
    }
}
