package com.mirunubi.bjstock.core.marketdata

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateRangeChunkerTest {
    @Test
    fun thirtyDays_isOneCall() {
        val start = LocalDate.of(2026, 1, 1)
        val end = start.plusDays(29)
        val chunks = DateRangeChunker.chunks(start, end)
        assertEquals(1, chunks.size)
        assertEquals(start..end, chunks.single())
    }

    @Test
    fun ninetyDays_isOneCall() {
        val start = LocalDate.of(2026, 1, 1)
        val end = start.plusDays(89)
        val chunks = DateRangeChunker.chunks(start, end)
        assertEquals(1, chunks.size)
        assertEquals(90, inclusiveDays(chunks.single()))
    }

    @Test
    fun ninetyOneDays_isTwoCalls() {
        val start = LocalDate.of(2026, 1, 1)
        val end = start.plusDays(90)
        val chunks = DateRangeChunker.chunks(start, end)
        assertEquals(2, chunks.size)
        assertEquals(start, chunks[0].start)
        assertEquals(start.plusDays(89), chunks[0].endInclusive)
        assertEquals(start.plusDays(90), chunks[1].start)
        assertEquals(end, chunks[1].endInclusive)
    }

    @Test
    fun threeHundredSixtyFiveDays_areMultipleNonOverlappingCalls() {
        val start = LocalDate.of(2026, 1, 1)
        val end = start.plusDays(364)
        val chunks = DateRangeChunker.chunks(start, end)
        assertTrue(chunks.size > 1)
        assertCoverage(start, end, chunks)
        chunks.forEach { chunk ->
            assertTrue(inclusiveDays(chunk) <= DateRangeChunker.CHUNK_CALENDAR_DAYS)
            assertTrue(inclusiveDays(chunk) < DateRangeChunker.KIS_DAILY_RECORD_LIMIT)
        }
    }

    @Test
    fun chunkBoundaries_haveNoGapsOrOverlaps() {
        val start = LocalDate.of(2025, 3, 1)
        val end = LocalDate.of(2026, 2, 28)
        assertCoverage(start, end, DateRangeChunker.chunks(start, end))
    }

    @Test
    fun noChunkReachesOneHundredCalendarDays() {
        val chunks = DateRangeChunker.chunks(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1))
        chunks.forEach { chunk ->
            assertTrue(inclusiveDays(chunk) < 100)
            assertTrue(inclusiveDays(chunk) <= 90)
        }
    }

    private fun inclusiveDays(range: ClosedRange<LocalDate>): Long {
        return java.time.temporal.ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1
    }

    private fun assertCoverage(
        start: LocalDate,
        end: LocalDate,
        chunks: List<ClosedRange<LocalDate>>,
    ) {
        val dates = LinkedHashSet<LocalDate>()
        chunks.forEachIndexed { index, chunk ->
            if (index > 0) {
                assertEquals(chunks[index - 1].endInclusive.plusDays(1), chunk.start)
            }
            var cursor = chunk.start
            while (!cursor.isAfter(chunk.endInclusive)) {
                assertTrue(dates.add(cursor))
                cursor = cursor.plusDays(1)
            }
        }
        assertEquals(start, chunks.first().start)
        assertEquals(end, chunks.last().endInclusive)
        var expected = start
        dates.forEach { date ->
            assertEquals(expected, date)
            expected = expected.plusDays(1)
        }
        assertEquals(end.plusDays(1), expected)
    }
}
