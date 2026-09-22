package com.mirunubi.bjstock.core.factor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.marketdata.MarketDataSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FactorCalculationServiceTest {
    private lateinit var database: BJStockDatabase
    private lateinit var repository: FactorValueRepository
    private lateinit var service: FactorCalculationService
    private var instrumentId: Long = 0
    private val start = LocalDate.of(2026, 1, 2)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, BJStockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FactorValueRepository(
            factorDao = database.factorDao(),
            now = { Instant.EPOCH },
        )
        service = FactorCalculationService(
            registry = SystemFactorRegistryFactory.create(),
            instrumentDao = database.instrumentDao(),
            marketDailyBarDao = database.marketDailyBarDao(),
            factorValues = repository,
        )
        instrumentId = database.instrumentDao().insert(
            InstrumentEntity(market = "KRX", symbol = "005930", name = "삼성전자"),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun lookAhead_day60IgnoresLaterBars() = runBlocking {
        insertCloses(List(100) { 10_000L + it })
        val day60 = start.plusDays(59)
        val before = service.calculateFactor(instrumentId, FactorCodes.PRICE_VS_MA20, day60)
        poisonFuture(day60)
        val after = service.calculateFactor(instrumentId, FactorCodes.PRICE_VS_MA20, day60)
        assertEquals(FactorCalculationStatus.SUCCESS, before.status)
        assertEquals(before.rawValue, after.rawValue)
        assertEquals(before.normalizedScore, after.normalizedScore)
    }

    @Test
    fun futureDataPoison_doesNotChangeDay60Momentum() = runBlocking {
        insertCloses(List(100) { 10_000L })
        val day60 = start.plusDays(59)
        val before = service.calculateFactor(instrumentId, FactorCodes.MOMENTUM_20D, day60)
        poisonFuture(day60)
        val after = service.calculateFactor(instrumentId, FactorCodes.MOMENTUM_20D, day60)
        assertEquals(before.rawValue, after.rawValue)
        assertEquals(before.normalizedScore, after.normalizedScore)
    }

    @Test
    fun insufficientHistory_doesNotPersist() = runBlocking {
        insertCloses(List(30) { 10_000L })
        val asOf = start.plusDays(29)
        val result = service.calculateFactor(instrumentId, FactorCodes.PRICE_VS_MA60, asOf)
        assertEquals(FactorCalculationStatus.INSUFFICIENT_HISTORY, result.status)
        assertNull(result.rawValue)
        assertEquals(0, repository.countValuesByInstrument(instrumentId))
    }

    @Test
    fun missingAsOfBar_isNoDataAndDoesNotCopyFriday() = runBlocking {
        insertCloses(List(20) { 10_000L })
        val friday = start.plusDays(19)
        val saturday = friday.plusDays(1)
        val fridayResult = service.calculateFactor(instrumentId, FactorCodes.PRICE_VS_MA20, friday)
        val saturdayResult = service.calculateFactor(instrumentId, FactorCodes.PRICE_VS_MA20, saturday)
        assertEquals(FactorCalculationStatus.SUCCESS, fridayResult.status)
        assertEquals(FactorCalculationStatus.NO_DATA, saturdayResult.status)
        assertNull(saturdayResult.rawValue)
        val stored = repository.findValues(instrumentId, saturday)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun zeroVolumeAverage_isInvalidAndNotStored() = runBlocking {
        insertBars(List(21) { 10_000L }, List(20) { 0L } + 200L)
        val asOf = start.plusDays(20)
        val result = service.calculateFactor(instrumentId, FactorCodes.VOLUME_RATIO_20D, asOf)
        assertEquals(FactorCalculationStatus.INVALID_DATA, result.status)
        assertEquals(0, repository.countValuesByInstrument(instrumentId))
    }

    @Test
    fun calculateAll_persistsSuccessAndSkipsInsufficient() = runBlocking {
        insertCloses(List(21) { 10_000L })
        val asOf = start.plusDays(20)
        val results = service.calculateAllSystemFactors(instrumentId, asOf)
        val byCode = results.associateBy { it.factorCode }
        assertEquals(FactorCalculationStatus.SUCCESS, byCode.getValue(FactorCodes.PRICE_VS_MA20).status)
        assertEquals(FactorCalculationStatus.INSUFFICIENT_HISTORY, byCode.getValue(FactorCodes.PRICE_VS_MA60).status)
        assertEquals(FactorCalculationStatus.SUCCESS, byCode.getValue(FactorCodes.MOMENTUM_20D).status)
        assertEquals(FactorCalculationStatus.INSUFFICIENT_HISTORY, byCode.getValue(FactorCodes.MOMENTUM_60D).status)
        val stored = repository.findValues(instrumentId, asOf)
        assertTrue(stored.isNotEmpty())
        assertTrue(stored.none { value ->
            val code = database.factorDao().findDefinitionById(value.factorId)!!.factorCode
            code == FactorCodes.PRICE_VS_MA60 || code == FactorCodes.MOMENTUM_60D
        })
        assertTrue(stored.all { it.source == FactorSources.BJSTOCK_MARKET_ENGINE })
        assertTrue(stored.all { it.calculationVersion == FactorCalculationVersions.V1 })
    }

    @Test
    fun persistence_recalculationUpdatesSameRow() = runBlocking {
        insertCloses(List(20) { 10_000L })
        val asOf = start.plusDays(19)
        service.calculateFactor(instrumentId, FactorCodes.PRICE_VS_MA20, asOf)
        val first = repository.findValues(instrumentId, asOf).single()
        val bar = database.marketDailyBarDao().findByInstrumentAndDate(instrumentId, asOf)!!
        database.marketDailyBarDao().update(bar.copy(closePrice = 12_000L))
        service.calculateFactor(instrumentId, FactorCodes.PRICE_VS_MA20, asOf)
        val second = repository.findValues(instrumentId, asOf).single()
        assertEquals(1, repository.countValuesByInstrument(instrumentId))
        assertEquals(first.id, second.id)
        assertNotEquals(first.rawValue, second.rawValue)
        assertEquals(first.createdAt, second.createdAt)
    }

    @Test
    fun versionPreservation_keepsV1WhenV2IsInserted() = runBlocking {
        insertCloses(List(20) { 10_000L })
        val asOf = start.plusDays(19)
        service.calculateFactor(instrumentId, FactorCodes.PRICE_VS_MA20, asOf)
        val definition = repository.findDefinitionByCode(FactorCodes.PRICE_VS_MA20)!!
        repository.upsertSuccess(
            instrumentId = instrumentId,
            factorId = definition.id,
            evaluationDate = asOf,
            rawValue = "1.00000000",
            normalizedScore = FactorScoreCodec.toStored(BigDecimal("50")),
            source = FactorSources.BJSTOCK_MARKET_ENGINE,
            calculationVersion = "v2",
        )
        assertEquals(2, repository.countValuesByInstrument(instrumentId))
        val versions = repository.findValues(instrumentId, asOf).map { it.calculationVersion }.toSet()
        assertEquals(setOf(FactorCalculationVersions.V1, "v2"), versions)
    }

    @Test
    fun ensureSystemFactorDefinitions_isIdempotent() = runBlocking {
        repository.ensureSystemFactorDefinitions()
        repository.ensureSystemFactorDefinitions()
        assertEquals(6, database.factorDao().findAllDefinitions().size)
    }

    private suspend fun poisonFuture(day60: LocalDate) {
        var cursor = day60.plusDays(1)
        val end = start.plusDays(99)
        while (!cursor.isAfter(end)) {
            val existing = database.marketDailyBarDao().findByInstrumentAndDate(instrumentId, cursor)!!
            database.marketDailyBarDao().update(existing.copy(closePrice = 1_000_000_000L))
            cursor = cursor.plusDays(1)
        }
    }

    private suspend fun insertCloses(closes: List<Long>) {
        insertBars(closes, closes.map { 1_000L })
    }

    private suspend fun insertBars(closes: List<Long>, volumes: List<Long>) {
        closes.forEachIndexed { index, close ->
            database.marketDailyBarDao().insert(
                MarketDailyBarEntity(
                    instrumentId = instrumentId,
                    tradeDate = start.plusDays(index.toLong()),
                    openPrice = close,
                    highPrice = close,
                    lowPrice = close,
                    closePrice = close,
                    volume = volumes[index],
                    source = MarketDataSource.KIS,
                    collectedAt = Instant.EPOCH,
                    createdAt = Instant.EPOCH,
                ),
            )
        }
    }
}
