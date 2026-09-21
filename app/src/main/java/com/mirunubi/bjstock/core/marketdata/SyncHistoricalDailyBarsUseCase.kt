package com.mirunubi.bjstock.core.marketdata

import com.mirunubi.bjstock.core.kis.market.DailyStockBar
import com.mirunubi.bjstock.core.kis.market.KisMarketRepository
import com.mirunubi.bjstock.core.kis.market.KisPriceAdjustment
import java.time.LocalDate

class SyncHistoricalDailyBarsUseCase(
    private val marketRepository: KisMarketRepository,
    private val localRepository: MarketDataLocalRepository,
) {
    suspend operator fun invoke(
        instrumentId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ): HistoricalSyncResult {
        if (startDate.isAfter(endDate)) {
            throw HistoricalSyncException(
                kind = HistoricalSyncErrorKind.INVALID_DATE_RANGE,
                publicMessage = "startDate must be on or before endDate",
            )
        }
        val instrument = localRepository.findInstrumentById(instrumentId)
            ?: throw HistoricalSyncException(
                kind = HistoricalSyncErrorKind.INSTRUMENT_NOT_FOUND,
                publicMessage = "Instrument not registered",
            )
        val chunks = DateRangeChunker.chunks(startDate, endDate)
        val merged = LinkedHashMap<LocalDate, DailyStockBar>()
        chunks.forEach { chunk ->
            val bars = marketRepository.inquireDailyBars(
                symbol = instrument.symbol,
                startDate = chunk.start,
                endDate = chunk.endInclusive,
                adjustment = KisPriceAdjustment.ADJUSTED,
            )
            bars.forEach { bar -> merged[bar.tradeDate] = bar }
        }
        val sorted = merged.values.sortedBy { it.tradeDate }
        val persist = localRepository.persistDailyBars(
            instrument = instrument,
            bars = sorted,
            adjustment = KisPriceAdjustment.ADJUSTED,
        )
        return HistoricalSyncResult(
            instrumentId = instrument.id,
            symbol = instrument.symbol,
            requestedStart = startDate,
            requestedEnd = endDate,
            requestCount = chunks.size,
            receivedCount = sorted.size,
            persistedCount = persist.persistedCount,
            firstDate = persist.firstDate,
            lastDate = persist.lastDate,
            status = when (persist.status) {
                MarketDataPersistStatus.SUCCESS -> HistoricalSyncStatus.SUCCESS
                MarketDataPersistStatus.SUCCESS_EMPTY -> HistoricalSyncStatus.SUCCESS_EMPTY
            },
        )
    }
}
