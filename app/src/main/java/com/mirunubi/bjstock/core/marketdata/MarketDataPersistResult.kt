package com.mirunubi.bjstock.core.marketdata

import java.time.LocalDate

enum class MarketDataPersistStatus {
    SUCCESS,
    SUCCESS_EMPTY,
}

data class MarketDataPersistResult(
    val symbol: String,
    val status: MarketDataPersistStatus,
    val requestedCount: Int,
    val insertedCount: Int,
    val updatedCount: Int,
    val unchangedCount: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
) {
    val persistedCount: Int = insertedCount + updatedCount + unchangedCount
}
