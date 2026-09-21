package com.mirunubi.bjstock.core.marketdata

import java.time.LocalDate

class SyncDailyBarsFromLatestUseCase(
    private val localRepository: MarketDataLocalRepository,
    private val historicalSync: SyncHistoricalDailyBarsUseCase,
) {
    suspend operator fun invoke(
        instrumentId: Long,
        endDate: LocalDate,
    ): HistoricalSyncResult {
        val instrument = localRepository.findInstrumentById(instrumentId)
            ?: throw HistoricalSyncException(
                kind = HistoricalSyncErrorKind.INSTRUMENT_NOT_FOUND,
                publicMessage = "Instrument not registered",
            )
        val latest = localRepository.findLatest(instrumentId)
            ?: throw HistoricalSyncException(
                kind = HistoricalSyncErrorKind.NO_LATEST_BAR,
                publicMessage = "Explicit startDate is required when no daily bars exist",
            )
        if (!latest.tradeDate.isBefore(endDate)) {
            return HistoricalSyncResult(
                instrumentId = instrument.id,
                symbol = instrument.symbol,
                requestedStart = endDate,
                requestedEnd = endDate,
                requestCount = 0,
                receivedCount = 0,
                persistedCount = 0,
                firstDate = latest.tradeDate,
                lastDate = latest.tradeDate,
                status = HistoricalSyncStatus.NO_OP,
            )
        }
        val startDate = latest.tradeDate.plusDays(1)
        return historicalSync(instrumentId, startDate, endDate)
    }
}
