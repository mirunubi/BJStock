package com.mirunubi.bjstock.core.marketdata

import java.time.LocalDate

object DateRangeChunker {
    const val CHUNK_CALENDAR_DAYS = 90
    const val KIS_DAILY_RECORD_LIMIT = 100

    fun chunks(startDate: LocalDate, endDate: LocalDate): List<ClosedRange<LocalDate>> {
        require(!startDate.isAfter(endDate)) { "startDate must be on or before endDate" }
        val ranges = mutableListOf<ClosedRange<LocalDate>>()
        var cursor = startDate
        while (!cursor.isAfter(endDate)) {
            val chunkEnd = minOf(cursor.plusDays(CHUNK_CALENDAR_DAYS - 1L), endDate)
            ranges += cursor..chunkEnd
            cursor = chunkEnd.plusDays(1)
        }
        return ranges
    }
}
