package com.mirunubi.bjstock.core.forward

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * One daily Auto wake-up slot: [date] at [ForwardTestConfig.AUTO_TARGET_TIME] in [ForwardTestConfig.MARKET_ZONE].
 * [scheduleInstanceId] names the intended invocation (`auto:<YYYY-MM-DD>:0730:KST`); it is not the market
 * through-date and not the WorkManager id. Every calendar day is a slot; market days are decided by catch-up.
 */
data class AutoScheduleSlot(val date: LocalDate) {
    val scheduledAt: Instant
        get() = date.atTime(ForwardTestConfig.AUTO_TARGET_TIME).atZone(ForwardTestConfig.MARKET_ZONE).toInstant()

    val scheduleInstanceId: String
        get() = "auto:$date:$TIME_TOKEN:$ZONE_TOKEN"

    val uniqueWorkName: String
        get() = "bjstock_forward_test_auto_${date}_${TIME_TOKEN}_$ZONE_TOKEN"

    companion object {
        private val TIME_TOKEN = "%02d%02d".format(
            ForwardTestConfig.AUTO_TARGET_TIME.hour,
            ForwardTestConfig.AUTO_TARGET_TIME.minute,
        )
        private const val ZONE_TOKEN = "KST"
        private val ID = Regex("^auto:([0-9]{4}-[0-9]{2}-[0-9]{2}):$TIME_TOKEN:$ZONE_TOKEN$")

        /** The first slot strictly after [instant]; a slot exactly at [instant] is not eligible. */
        fun nextAfter(instant: Instant): AutoScheduleSlot {
            val local = instant.atZone(ForwardTestConfig.MARKET_ZONE)
            val today = AutoScheduleSlot(local.toLocalDate())
            return if (instant.isBefore(today.scheduledAt)) today else AutoScheduleSlot(local.toLocalDate().plusDays(1))
        }

        /** Null when [scheduleInstanceId] is missing or not a canonical slot id; never guesses. */
        fun parse(scheduleInstanceId: String?): AutoScheduleSlot? {
            val match = scheduleInstanceId?.let(ID::matchEntire) ?: return null
            return try {
                AutoScheduleSlot(LocalDate.parse(match.groupValues[1]))
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
