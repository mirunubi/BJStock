package com.mirunubi.bjstock.core.database.mapping

/**
 * Room storage scales for PostgreSQL NUMERIC columns.
 * Money never uses Float/Double.
 */
object NumericMapping {
    /** KRW prices/cash: integer won. PostgreSQL NUMERIC(18/20, 4) stored as Long. */
    const val WON_SCALE = 0

    /** Scores 0..100 with 4 decimal places. value = score * 10_000. */
    const val SCORE_SCALE = 4
    const val SCORE_FACTOR = 10_000L

    /** Weights/confidence 0..1 with 6 decimal places. value = weight * 1_000_000. */
    const val WEIGHT_SCALE = 6
    const val WEIGHT_FACTOR = 1_000_000L

    /** Returns/drawdown with 8 decimal places. value = ratio * 100_000_000. */
    const val RATIO_SCALE = 8
    const val RATIO_FACTOR = 100_000_000L

    /** AI confidence 0..1 with 4 decimal places. value = confidence * 10_000. */
    const val CONFIDENCE_SCALE = 4
    const val CONFIDENCE_FACTOR = 10_000L
}
