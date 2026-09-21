package com.mirunubi.bjstock.core.kis.market

import java.time.LocalDate

enum class KisMarketDivision {
    KRX,
}

enum class KisChartPeriod {
    DAILY,
}

enum class KisPriceAdjustment {
    ADJUSTED,
    UNADJUSTED,
}

data class CurrentStockQuote(
    val symbol: String,
    val currentPrice: Long,
    val previousCloseDifference: Long,
    val changeRate: Long,
    val openPrice: Long,
    val highPrice: Long,
    val lowPrice: Long,
    val volume: Long,
    val tradingValue: Long?,
    val businessDate: LocalDate?,
    val source: String,
)

data class DailyStockBar(
    val symbol: String,
    val tradeDate: LocalDate,
    val openPrice: Long,
    val highPrice: Long,
    val lowPrice: Long,
    val closePrice: Long,
    val volume: Long,
    val tradingValue: Long?,
)
