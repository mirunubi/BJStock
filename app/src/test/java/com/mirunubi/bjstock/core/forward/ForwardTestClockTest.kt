package com.mirunubi.bjstock.core.forward

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardTestClockTest {
    @Test
    fun cutoff_beforeEighteen_usesYesterday() {
        val clock = fixedSeoul(LocalDate.of(2026, 9, 22), hour = 17, minute = 59)
        assertEquals(LocalDate.of(2026, 9, 21), clock.throughDate())
    }

    @Test
    fun cutoff_atEighteen_usesToday() {
        val clock = fixedSeoul(LocalDate.of(2026, 9, 22), hour = 18, minute = 0)
        assertEquals(LocalDate.of(2026, 9, 22), clock.throughDate())
    }

    @Test
    fun throughDate_respectsRunEndDate() {
        val clock = fixedSeoul(LocalDate.of(2026, 9, 22), hour = 18, minute = 0)
        assertEquals(
            LocalDate.of(2026, 9, 20),
            clock.throughDate(runEndDate = LocalDate.of(2026, 9, 20)),
        )
    }

    @Test
    fun timezone_deviceUtc_stillUsesSeoulCutoff() {
        // Instant corresponding to 2026-09-22 17:59 Asia/Seoul = 08:59 UTC
        val instant = Instant.parse("2026-09-22T08:59:00Z")
        val clock = ForwardTestClock(Clock.fixed(instant, ZoneOffset.UTC))
        assertEquals(LocalDate.of(2026, 9, 21), clock.throughDate())
    }

    @Test
    fun timezone_deviceLosAngeles_stillUsesSeoulCutoff() {
        val instant = Instant.parse("2026-09-22T09:00:00Z") // 18:00 Seoul
        val la = ZoneId.of("America/Los_Angeles")
        val clock = ForwardTestClock(Clock.fixed(instant, la))
        assertEquals(LocalDate.of(2026, 9, 22), clock.throughDate())
    }

    @Test
    fun timezone_deviceSeoul_matchesOperationalCutoff() {
        val clock = fixedSeoul(LocalDate.of(2026, 9, 22), hour = 18, minute = 0)
        assertEquals(LocalDate.of(2026, 9, 22), clock.throughDate())
    }

    private fun fixedSeoul(date: LocalDate, hour: Int, minute: Int): ForwardTestClock {
        val zdt = date.atTime(hour, minute).atZone(ForwardTestConfig.MARKET_ZONE)
        return ForwardTestClock(Clock.fixed(zdt.toInstant(), ForwardTestConfig.MARKET_ZONE))
    }
}
