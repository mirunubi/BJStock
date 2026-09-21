package com.mirunubi.bjstock.core.marketdata

import com.mirunubi.bjstock.core.kis.market.KisMarketDivision
import com.mirunubi.bjstock.core.kis.market.KisMarketRepository
import com.mirunubi.bjstock.core.kis.market.KisPriceAdjustment
import java.time.LocalDate

class FetchAndPersistDailyBarsUseCase(
    private val marketRepository: KisMarketRepository,
    private val localRepository: MarketDataLocalRepository,
) {
    suspend operator fun invoke(
        symbol: String,
        startDate: LocalDate,
        endDate: LocalDate,
        market: String = KisMarketDivision.KRX.name,
    ): MarketDataPersistResult {
        val instrument = localRepository.requireInstrument(market, symbol)
        val bars = marketRepository.inquireDailyBars(
            symbol = symbol,
            startDate = startDate,
            endDate = endDate,
            adjustment = KisPriceAdjustment.ADJUSTED,
        )
        return localRepository.persistDailyBars(
            instrument = instrument,
            bars = bars,
            adjustment = KisPriceAdjustment.ADJUSTED,
        )
    }
}
