package com.mirunubi.bjstock.core.kis.market

import java.time.LocalDate

interface KisMarketRepository {
    suspend fun inquireCurrentPrice(symbol: String): CurrentStockQuote

    suspend fun inquireDailyBars(
        symbol: String,
        startDate: LocalDate,
        endDate: LocalDate,
        adjustment: KisPriceAdjustment = KisPriceAdjustment.ADJUSTED,
    ): List<DailyStockBar>
}
