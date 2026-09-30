package com.mirunubi.bjstock.core.forward

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * BJStock operational market clock. Independent of device timezone.
 * Cutoff 18:00 Asia/Seoul is an operational assumption, not exchange law.
 */
object ForwardTestConfig {
    val MARKET_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    val OPERATIONAL_CUTOFF: LocalTime = LocalTime.of(18, 0)

    /** Earliest eligible Auto wake-up in [MARKET_ZONE]; WorkManager may run later (docs/150 §20.9). */
    val AUTO_TARGET_TIME: LocalTime = LocalTime.of(7, 30)
    const val AUTO_WORK_TAG = "bjstock_forward_test_auto_v2"

    /** Gate 5 periodic work. Only ever cancelled; never enqueued again. */
    const val LEGACY_PERIODIC_WORK_NAME = "bjstock_forward_test_v1"
    const val WARMUP_TRADING_BARS = 61
    const val HISTORY_PREPARE_CALENDAR_DAYS = 180L
}

class ForwardTestClock(
    private val clock: Clock = Clock.system(ForwardTestConfig.MARKET_ZONE),
) {
    fun nowInstant(): Instant = clock.instant()

    fun nowSeoul(): LocalDateTime =
        LocalDateTime.ofInstant(nowInstant(), ForwardTestConfig.MARKET_ZONE)

    fun throughDate(runEndDate: LocalDate? = null): LocalDate {
        val seoul = nowSeoul()
        val calculated = if (!seoul.toLocalTime().isBefore(ForwardTestConfig.OPERATIONAL_CUTOFF)) {
            seoul.toLocalDate()
        } else {
            seoul.toLocalDate().minusDays(1)
        }
        return if (runEndDate != null && runEndDate.isBefore(calculated)) {
            runEndDate
        } else {
            calculated
        }
    }
}
