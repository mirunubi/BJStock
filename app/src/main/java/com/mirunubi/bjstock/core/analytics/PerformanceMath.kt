package com.mirunubi.bjstock.core.analytics

import com.mirunubi.bjstock.core.database.mapping.NumericMapping
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

/**
 * Centralized BigDecimal performance math.
 * Domain/source-of-truth calculations never use Double/Float.
 */
object PerformanceMath {
    val ZERO: BigDecimal = BigDecimal.ZERO
    private val ONE: BigDecimal = BigDecimal.ONE
    private val HUNDRED: BigDecimal = BigDecimal("100")
    val ROUNDING: RoundingMode = RoundingMode.HALF_UP

    /** Internal calculation scale (higher than display). */
    const val CALC_SCALE: Int = 12

    /** Display percent scale (e.g. +12.34%). */
    const val DISPLAY_PERCENT_SCALE: Int = 2

    fun ratio(numerator: Long, denominator: Long): BigDecimal {
        if (denominator == 0L) return ZERO
        return BigDecimal(numerator)
            .divide(BigDecimal(denominator), CALC_SCALE, ROUNDING)
    }

    fun returnFromAssets(startAsset: Long, endAsset: Long): BigDecimal =
        ratio(endAsset, startAsset).subtract(ONE)

    fun profit(startAsset: Long, endAsset: Long): Long = endAsset - startAsset

    fun drawdown(totalAsset: Long, peak: Long): BigDecimal {
        if (peak <= 0L) return ZERO
        return returnFromAssets(peak, totalAsset)
    }

    fun maxDrawdown(drawdowns: List<BigDecimal>): BigDecimal {
        if (drawdowns.isEmpty()) return ZERO
        return drawdowns.minOrNull() ?: ZERO
    }

    fun toStoredRatio(value: BigDecimal): Long =
        value.multiply(BigDecimal(NumericMapping.RATIO_FACTOR))
            .setScale(0, ROUNDING)
            .longValueExact()

    fun fromStoredRatio(stored: Long): BigDecimal =
        BigDecimal(stored).divide(
            BigDecimal(NumericMapping.RATIO_FACTOR),
            NumericMapping.RATIO_SCALE,
            ROUNDING,
        )

    fun formatSignedPercent(rate: BigDecimal): String {
        val pct = rate.multiply(HUNDRED).setScale(DISPLAY_PERCENT_SCALE, ROUNDING)
        val plain = pct.toPlainString()
        return when {
            pct.signum() > 0 -> "+$plain%"
            else -> "$plain%"
        }
    }

    fun formatWon(value: Long): String = "₩%,d".format(value)

    fun formatSignedWon(value: Long): String =
        if (value > 0L) "+${formatWon(value)}" else formatWon(value)

    fun cagr(initialCash: Long, latestAsset: Long, elapsedCalendarDays: Long): BigDecimal? {
        if (elapsedCalendarDays < 365L) return null
        if (initialCash <= 0L || latestAsset < 0L) return null
        // Fractional power is not expressible as exact BigDecimal arithmetic;
        // CAGR is the only metric that uses IEEE pow, then stores as BigDecimal.
        val base = latestAsset.toDouble() / initialCash.toDouble()
        if (base <= 0.0 || base.isNaN() || base.isInfinite()) return null
        val powered = base.pow(365.0 / elapsedCalendarDays.toDouble())
        if (powered.isNaN() || powered.isInfinite()) return null
        return BigDecimal.valueOf(powered - 1.0).setScale(CALC_SCALE, ROUNDING)
    }
}
