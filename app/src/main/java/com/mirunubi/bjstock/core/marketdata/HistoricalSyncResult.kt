package com.mirunubi.bjstock.core.marketdata

import java.time.LocalDate

enum class HistoricalSyncStatus {
    SUCCESS,
    SUCCESS_EMPTY,
    NO_OP,
}

data class HistoricalSyncResult(
    val instrumentId: Long,
    val symbol: String,
    val requestedStart: LocalDate,
    val requestedEnd: LocalDate,
    val requestCount: Int,
    val receivedCount: Int,
    val persistedCount: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val status: HistoricalSyncStatus,
)

class HistoricalSyncException(
    val kind: HistoricalSyncErrorKind,
    val publicMessage: String,
) : Exception(publicMessage)

enum class HistoricalSyncErrorKind {
    INSTRUMENT_NOT_FOUND,
    INVALID_DATE_RANGE,
    NO_LATEST_BAR,
}
