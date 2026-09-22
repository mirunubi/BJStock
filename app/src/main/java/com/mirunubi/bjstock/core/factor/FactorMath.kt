package com.mirunubi.bjstock.core.factor

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

object FactorMath {
    val CONTEXT = MathContext(16, RoundingMode.HALF_UP)
    val ZERO: BigDecimal = BigDecimal.ZERO
    val ONE: BigDecimal = BigDecimal.ONE
    val HUNDRED: BigDecimal = BigDecimal("100")
    const val RAW_SCALE = 8

    fun percentFromRatio(current: BigDecimal, base: BigDecimal): FactorRawOutcome {
        if (base.compareTo(ZERO) == 0) {
            return FactorRawOutcome(FactorCalculationStatus.INVALID_DATA)
        }
        val raw = current.divide(base, CONTEXT).subtract(ONE).multiply(HUNDRED)
        return FactorRawOutcome(FactorCalculationStatus.SUCCESS, raw)
    }

    fun dailyReturn(today: BigDecimal, previous: BigDecimal): BigDecimal? {
        if (previous.compareTo(ZERO) == 0) return null
        return today.divide(previous, CONTEXT).subtract(ONE)
    }

    fun populationStdev(values: List<BigDecimal>): BigDecimal {
        require(values.isNotEmpty())
        val n = BigDecimal(values.size)
        val mean = values.reduce { acc, value -> acc.add(value) }.divide(n, CONTEXT)
        val sumSq = values.fold(ZERO) { acc, value ->
            val delta = value.subtract(mean)
            acc.add(delta.multiply(delta, CONTEXT), CONTEXT)
        }
        val variance = sumSq.divide(n, CONTEXT)
        return variance.sqrt(CONTEXT)
    }

    fun formatRaw(value: BigDecimal): String {
        return value.setScale(RAW_SCALE, RoundingMode.HALF_UP).toPlainString()
    }

    fun clampScore(score: BigDecimal): BigDecimal {
        return score.max(ZERO).min(HUNDRED)
    }
}

object FactorBars {
    fun usable(bars: List<FactorBar>, asOfDate: LocalDate): List<FactorBar> {
        return bars
            .filter { !it.tradeDate.isAfter(asOfDate) }
            .sortedBy { it.tradeDate }
    }

    fun asOfBar(usable: List<FactorBar>, asOfDate: LocalDate): FactorBar? {
        return usable.lastOrNull()?.takeIf { it.tradeDate == asOfDate }
    }
}
