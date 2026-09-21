package com.mirunubi.bjstock.core.marketdata

import com.mirunubi.bjstock.core.kis.market.DailyStockBar

object DailyBarValidator {
    fun requireValid(bar: DailyStockBar) {
        if (bar.openPrice < 0 || bar.highPrice < 0 || bar.lowPrice < 0 ||
            bar.closePrice < 0 || bar.volume < 0
        ) {
            invalid()
        }
        val tradingValue = bar.tradingValue
        if (tradingValue != null && tradingValue < 0) {
            invalid()
        }
        if (bar.highPrice < bar.lowPrice ||
            bar.highPrice < bar.openPrice ||
            bar.highPrice < bar.closePrice ||
            bar.lowPrice > bar.openPrice ||
            bar.lowPrice > bar.closePrice
        ) {
            invalid()
        }
    }

    private fun invalid(): Nothing {
        throw MarketDataPersistenceException(
            kind = MarketDataPersistenceErrorKind.INVALID_DAILY_BAR,
            publicMessage = "KIS 응답 오류",
        )
    }
}
