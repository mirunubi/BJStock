package com.mirunubi.bjstock.core.marketdata

import androidx.room.withTransaction
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.dao.MarketDailyBarDao
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.MarketDailyBarEntity
import com.mirunubi.bjstock.core.kis.market.DailyStockBar
import com.mirunubi.bjstock.core.kis.market.KisPriceAdjustment
import java.time.Instant
import java.time.LocalDate

class MarketDataLocalRepository(
    private val database: BJStockDatabase,
    private val instrumentDao: InstrumentDao,
    private val marketDailyBarDao: MarketDailyBarDao,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun findInstrument(market: String, symbol: String): InstrumentEntity? =
        instrumentDao.findByMarketAndSymbol(market, symbol)

    suspend fun requireInstrument(market: String, symbol: String): InstrumentEntity {
        return findInstrument(market, symbol)
            ?: throw MarketDataPersistenceException(
                kind = MarketDataPersistenceErrorKind.INSTRUMENT_NOT_FOUND,
                publicMessage = "Instrument not registered",
            )
    }

    suspend fun persistDailyBars(
        instrument: InstrumentEntity,
        bars: List<DailyStockBar>,
        adjustment: KisPriceAdjustment,
    ): MarketDataPersistResult {
        if (adjustment != KisPriceAdjustment.ADJUSTED) {
            throw MarketDataPersistenceException(
                kind = MarketDataPersistenceErrorKind.UNSUPPORTED_PRICE_ADJUSTMENT,
                publicMessage = "UNADJUSTED daily bars cannot be persisted",
            )
        }
        val unique = LinkedHashMap<LocalDate, DailyStockBar>()
        bars.forEach { bar ->
            if (bar.symbol != instrument.symbol) {
                throw MarketDataPersistenceException(
                    kind = MarketDataPersistenceErrorKind.INVALID_DAILY_BAR,
                    publicMessage = "KIS 응답 오류",
                )
            }
            unique[bar.tradeDate] = bar
        }
        val batch = unique.values.sortedBy { it.tradeDate }
        if (batch.isEmpty()) {
            return MarketDataPersistResult(
                symbol = instrument.symbol,
                status = MarketDataPersistStatus.SUCCESS_EMPTY,
                requestedCount = 0,
                insertedCount = 0,
                updatedCount = 0,
                unchangedCount = 0,
                firstDate = null,
                lastDate = null,
            )
        }
        batch.forEach(DailyBarValidator::requireValid)
        val collectedAt = now()
        return database.withTransaction {
            var inserted = 0
            var updated = 0
            var unchanged = 0
            batch.forEach { bar ->
                val existing = marketDailyBarDao.findByInstrumentAndDate(
                    instrument.id,
                    bar.tradeDate,
                )
                if (existing == null) {
                    marketDailyBarDao.insert(
                        toEntity(
                            instrumentId = instrument.id,
                            bar = bar,
                            collectedAt = collectedAt,
                            createdAt = collectedAt,
                        ),
                    )
                    inserted += 1
                } else {
                    val sameValues = sameMarketValues(existing, bar)
                    marketDailyBarDao.update(
                        existing.copy(
                            openPrice = bar.openPrice,
                            highPrice = bar.highPrice,
                            lowPrice = bar.lowPrice,
                            closePrice = bar.closePrice,
                            volume = bar.volume,
                            tradingValue = bar.tradingValue,
                            source = MarketDataSource.KIS,
                            collectedAt = collectedAt,
                        ),
                    )
                    if (sameValues) {
                        unchanged += 1
                    } else {
                        updated += 1
                    }
                }
            }
            MarketDataPersistResult(
                symbol = instrument.symbol,
                status = MarketDataPersistStatus.SUCCESS,
                requestedCount = batch.size,
                insertedCount = inserted,
                updatedCount = updated,
                unchangedCount = unchanged,
                firstDate = batch.first().tradeDate,
                lastDate = batch.last().tradeDate,
            )
        }
    }

    suspend fun findByDateRange(
        instrumentId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<MarketDailyBarEntity> =
        marketDailyBarDao.findByInstrumentAndDateRange(instrumentId, startDate, endDate)

    suspend fun findLatest(instrumentId: Long): MarketDailyBarEntity? =
        marketDailyBarDao.findLatest(instrumentId)

    suspend fun countByInstrument(instrumentId: Long): Int =
        marketDailyBarDao.countByInstrument(instrumentId)

    private fun toEntity(
        instrumentId: Long,
        bar: DailyStockBar,
        collectedAt: Instant,
        createdAt: Instant,
    ): MarketDailyBarEntity = MarketDailyBarEntity(
        instrumentId = instrumentId,
        tradeDate = bar.tradeDate,
        openPrice = bar.openPrice,
        highPrice = bar.highPrice,
        lowPrice = bar.lowPrice,
        closePrice = bar.closePrice,
        volume = bar.volume,
        tradingValue = bar.tradingValue,
        source = MarketDataSource.KIS,
        collectedAt = collectedAt,
        createdAt = createdAt,
    )

    private fun sameMarketValues(existing: MarketDailyBarEntity, bar: DailyStockBar): Boolean {
        return existing.openPrice == bar.openPrice &&
            existing.highPrice == bar.highPrice &&
            existing.lowPrice == bar.lowPrice &&
            existing.closePrice == bar.closePrice &&
            existing.volume == bar.volume &&
            existing.tradingValue == bar.tradingValue
    }
}
