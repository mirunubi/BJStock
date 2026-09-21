package com.mirunubi.bjstock.core.kis.market

object KisDomesticSymbol {
    private val DOMESTIC_EQUITY = Regex("^\\d{6}$")

    fun requireValid(symbol: String): String {
        val trimmed = symbol.trim()
        if (!DOMESTIC_EQUITY.matches(trimmed)) {
            throw KisMarketException(
                kind = KisMarketErrorKind.INVALID_SYMBOL,
                publicMessage = "잘못된 종목",
            )
        }
        return trimmed
    }
}
