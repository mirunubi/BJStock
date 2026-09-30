package com.mirunubi.bjstock.core.forward

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScheduleSlotTest {
    @Test
    fun canonicalTarget_is0700Seoul() {
        assertEquals(LocalTime.of(7, 0), ForwardTestConfig.AUTO_TARGET_TIME)
    }

    @Test
    fun before0700_targetsTheSameDay() {
        assertEquals(slot(2026, 10, 1), AutoScheduleSlot.nextAfter(kst(2026, 10, 1, 0, 0)))
        assertEquals(slot(2026, 10, 1), AutoScheduleSlot.nextAfter(kst(2026, 10, 1, 6, 0)))
        assertEquals(slot(2026, 10, 1), AutoScheduleSlot.nextAfter(kst(2026, 10, 1, 6, 59, 59)))
        assertEquals(slot(2026, 10, 1), AutoScheduleSlot.nextAfter(kst(2026, 10, 1, 6, 59, 59, 999_999_999)))
    }

    @Test
    fun atOrAfter0700_targetsTheNextDay_neverTheCurrentInstant() {
        assertEquals(slot(2026, 10, 2), AutoScheduleSlot.nextAfter(kst(2026, 10, 1, 7, 0, 0)))
        assertEquals(slot(2026, 10, 2), AutoScheduleSlot.nextAfter(kst(2026, 10, 1, 7, 0, 1)))
        assertEquals(slot(2026, 10, 2), AutoScheduleSlot.nextAfter(kst(2026, 10, 1, 7, 30)))
        assertEquals(slot(2026, 10, 2), AutoScheduleSlot.nextAfter(kst(2026, 10, 1, 15, 0)))
        assertEquals(slot(2026, 10, 2), AutoScheduleSlot.nextAfter(kst(2026, 10, 1, 23, 59)))
        listOf(kst(2026, 10, 1, 7, 0), kst(2026, 10, 1, 7, 0, 0, 1), kst(2026, 12, 31, 23, 59, 59)).forEach { now ->
            val next = AutoScheduleSlot.nextAfter(now)
            assertTrue("slot must be strictly after $now", next.scheduledAt.isAfter(now))
        }
        assertEquals(slot(2027, 1, 1), AutoScheduleSlot.nextAfter(kst(2026, 12, 31, 12, 0)))
    }

    @Test
    fun slotInstant_isFixedAsiaSeoul_independentOfDeviceTimezone() {
        val original = TimeZone.getDefault()
        try {
            val expected = Instant.parse("2026-09-30T22:00:00Z")
            listOf("UTC", "America/Los_Angeles", "Europe/Berlin", "Asia/Seoul").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val next = AutoScheduleSlot.nextAfter(Instant.parse("2026-09-30T21:00:00Z"))
                assertEquals(zone, slot(2026, 10, 1), next)
                assertEquals(zone, expected, next.scheduledAt)
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun everyCalendarDayIsASlot_includingWeekendsAndHolidays() {
        // 2026-10-03 (Saturday, National Foundation Day), 2026-10-04 (Sunday), 2026-10-09 (Hangul Day)
        listOf(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 9)).forEach { day ->
            assertEquals(AutoScheduleSlot(day), AutoScheduleSlot.nextAfter(kst(day.year, day.monthValue, day.dayOfMonth, 6, 0)))
        }
        assertEquals(slot(2026, 10, 4), AutoScheduleSlot.nextAfter(kst(2026, 10, 3, 8, 0)))
    }

    @Test
    fun identity_format_uniqueWorkName_andRoundTrip() {
        val slot = slot(2026, 10, 1)
        assertEquals("auto:2026-10-01:0700:KST", slot.scheduleInstanceId)
        assertEquals("bjstock_forward_test_auto_2026-10-01_0700_KST", slot.uniqueWorkName)
        assertEquals(slot, AutoScheduleSlot.parse(slot.scheduleInstanceId))
        assertNotEquals(slot.scheduleInstanceId, slot(2026, 10, 2).scheduleInstanceId)
    }

    @Test
    fun parse_neverGuesses_andRejectsThePrevious0730Target() {
        listOf(
            null,
            "",
            "auto:2026-10-01",
            "auto:2026-10-01:0730:KST",
            "auto:2026-10-01:0700:UTC",
            "auto:2026-10-01:0800:KST",
            "auto:2026-02-30:0700:KST",
            "worker:auto:2026-10-01:0700:KST:0",
            "7b0c2f55-1d2e-4a6b-9f3c-0d1e2f3a4b5c",
        ).forEach { assertNull(it, AutoScheduleSlot.parse(it)) }
        assertNull(AutoWorkRequests.scheduleInstanceIdFromTag("bjstock_forward_test_auto_slot=auto:2026-10-01:0730:KST"))
    }

    private fun slot(year: Int, month: Int, day: Int) = AutoScheduleSlot(LocalDate.of(year, month, day))

    private fun kst(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, nanos: Int = 0): Instant =
        ZonedDateTime.of(year, month, day, hour, minute, second, nanos, ForwardTestConfig.MARKET_ZONE).toInstant()
}
