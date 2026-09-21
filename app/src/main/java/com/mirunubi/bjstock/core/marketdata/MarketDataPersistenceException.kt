package com.mirunubi.bjstock.core.marketdata

enum class MarketDataPersistenceErrorKind {
    INSTRUMENT_NOT_FOUND,
    INVALID_DAILY_BAR,
    UNSUPPORTED_PRICE_ADJUSTMENT,
}

class MarketDataPersistenceException(
    val kind: MarketDataPersistenceErrorKind,
    val publicMessage: String,
) : Exception(publicMessage)
