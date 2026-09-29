package com.mirunubi.bjstock.core.marketdata

import com.mirunubi.bjstock.core.kis.KisEnvironment
import com.mirunubi.bjstock.core.kis.market.DailyStockBar
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.kis.market.KisMarketException
import com.mirunubi.bjstock.core.kis.market.KisMarketRepository
import com.mirunubi.bjstock.core.kis.market.KisPriceAdjustment
import com.mirunubi.bjstock.core.kis.market.KisRequestPolicy
import java.time.LocalDate
import kotlinx.coroutines.delay

class SyncHistoricalDailyBarsUseCase(
    private val marketRepository: KisMarketRepository,
    private val localRepository: MarketDataLocalRepository,
    private val environment: suspend () -> KisEnvironment,
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
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
        val interval = KisRequestPolicy.minIntervalMillis(environment())
        val merged = LinkedHashMap<LocalDate, DailyStockBar>()
        var requestCount = 0
        chunks.forEachIndexed { index, chunk ->
            if (index > 0) sleep(interval)
            var attempt = 1
            while (true) {
                requestCount += 1
                val bars = try {
                    marketRepository.inquireDailyBars(
                        symbol = instrument.symbol,
                        startDate = chunk.start,
                        endDate = chunk.endInclusive,
                        adjustment = KisPriceAdjustment.ADJUSTED,
                    )
                } catch (error: KisMarketException) {
                    if (error.kind != KisMarketErrorKind.RATE_LIMITED ||
                        attempt >= KisRequestPolicy.RATE_LIMIT_MAX_ATTEMPTS
                    ) {
                        throw error
                    }
                    attempt += 1
                    sleep(KisRequestPolicy.RATE_LIMIT_WAIT_MILLIS)
                    continue
                }
                bars.forEach { bar -> merged[bar.tradeDate] = bar }
                break
            }
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
            requestCount = requestCount,
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
