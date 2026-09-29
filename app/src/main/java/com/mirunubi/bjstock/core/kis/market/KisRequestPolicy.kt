package com.mirunubi.bjstock.core.kis.market

import com.mirunubi.bjstock.core.kis.KisEnvironment

/**
 * Request pacing and the single bounded retry case for KIS market-data calls.
 *
 * Intervals follow the official KIS sample pacing between consecutive REST calls.
 * EGW00201 (초당 거래건수 초과) is the only code that is retried; everything else fails fast.
 */
object KisRequestPolicy {
    const val PRODUCTION_MIN_INTERVAL_MILLIS = 100L
    const val VIRTUAL_MIN_INTERVAL_MILLIS = 500L

    const val RATE_LIMIT_MSG_CD = "EGW00201"
    const val RATE_LIMIT_WAIT_MILLIS = 61_000L
    const val RATE_LIMIT_MAX_ATTEMPTS = 3

    fun minIntervalMillis(environment: KisEnvironment): Long = when (environment) {
        KisEnvironment.PRODUCTION -> PRODUCTION_MIN_INTERVAL_MILLIS
        KisEnvironment.VIRTUAL -> VIRTUAL_MIN_INTERVAL_MILLIS
    }

    fun isRateLimit(msgCd: String?): Boolean = msgCd?.trim() == RATE_LIMIT_MSG_CD
}
