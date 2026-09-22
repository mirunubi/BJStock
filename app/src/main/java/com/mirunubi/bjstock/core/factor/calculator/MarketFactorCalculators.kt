package com.mirunubi.bjstock.core.factor.calculator

import com.mirunubi.bjstock.core.factor.FactorBar
import com.mirunubi.bjstock.core.factor.FactorBars
import com.mirunubi.bjstock.core.factor.FactorCalculationStatus
import com.mirunubi.bjstock.core.factor.FactorCalculator
import com.mirunubi.bjstock.core.factor.FactorMath
import com.mirunubi.bjstock.core.factor.FactorRawOutcome
import java.math.BigDecimal
import java.time.LocalDate

class PriceVsMaCalculator(
    override val factorCode: String,
    private val window: Int,
) : FactorCalculator {
    override val requiredHistoryDays: Int = window

    override fun calculate(
        instrumentId: Long,
        asOfDate: LocalDate,
        bars: List<FactorBar>,
    ): FactorRawOutcome {
        val usable = FactorBars.usable(bars, asOfDate)
        FactorBars.asOfBar(usable, asOfDate) ?: return FactorRawOutcome(FactorCalculationStatus.NO_DATA)
        if (usable.size < window) return FactorRawOutcome(FactorCalculationStatus.INSUFFICIENT_HISTORY)
        val windowBars = usable.takeLast(window)
        if (windowBars.any { it.closePrice <= 0L }) {
            return FactorRawOutcome(FactorCalculationStatus.INVALID_DATA)
        }
        val ma = windowBars
            .fold(FactorMath.ZERO) { acc, bar -> acc.add(BigDecimal(bar.closePrice)) }
            .divide(BigDecimal(window), FactorMath.CONTEXT)
        return FactorMath.percentFromRatio(BigDecimal(windowBars.last().closePrice), ma)
    }
}

class MomentumCalculator(
    override val factorCode: String,
    private val lookbackTradingDays: Int,
) : FactorCalculator {
    override val requiredHistoryDays: Int = lookbackTradingDays + 1

    override fun calculate(
        instrumentId: Long,
        asOfDate: LocalDate,
        bars: List<FactorBar>,
    ): FactorRawOutcome {
        val usable = FactorBars.usable(bars, asOfDate)
        val asOf = FactorBars.asOfBar(usable, asOfDate)
            ?: return FactorRawOutcome(FactorCalculationStatus.NO_DATA)
        if (usable.size < requiredHistoryDays) {
            return FactorRawOutcome(FactorCalculationStatus.INSUFFICIENT_HISTORY)
        }
        val past = usable[usable.size - requiredHistoryDays]
        if (asOf.closePrice <= 0L || past.closePrice <= 0L) {
            return FactorRawOutcome(FactorCalculationStatus.INVALID_DATA)
        }
        return FactorMath.percentFromRatio(BigDecimal(asOf.closePrice), BigDecimal(past.closePrice))
    }
}

class VolatilityCalculator(
    override val factorCode: String,
    private val returnDays: Int,
) : FactorCalculator {
    override val requiredHistoryDays: Int = returnDays + 1

    override fun calculate(
        instrumentId: Long,
        asOfDate: LocalDate,
        bars: List<FactorBar>,
    ): FactorRawOutcome {
        val usable = FactorBars.usable(bars, asOfDate)
        FactorBars.asOfBar(usable, asOfDate) ?: return FactorRawOutcome(FactorCalculationStatus.NO_DATA)
        if (usable.size < requiredHistoryDays) {
            return FactorRawOutcome(FactorCalculationStatus.INSUFFICIENT_HISTORY)
        }
        val window = usable.takeLast(requiredHistoryDays)
        if (window.any { it.closePrice <= 0L }) {
            return FactorRawOutcome(FactorCalculationStatus.INVALID_DATA)
        }
        val returns = ArrayList<BigDecimal>(returnDays)
        for (index in 1 until window.size) {
            val daily = FactorMath.dailyReturn(
                BigDecimal(window[index].closePrice),
                BigDecimal(window[index - 1].closePrice),
            ) ?: return FactorRawOutcome(FactorCalculationStatus.INVALID_DATA)
            returns += daily
        }
        val stdevPercent = FactorMath.populationStdev(returns).multiply(FactorMath.HUNDRED, FactorMath.CONTEXT)
        return FactorRawOutcome(FactorCalculationStatus.SUCCESS, stdevPercent)
    }
}

class VolumeRatioCalculator(
    override val factorCode: String,
    private val priorDays: Int,
) : FactorCalculator {
    override val requiredHistoryDays: Int = priorDays + 1

    override fun calculate(
        instrumentId: Long,
        asOfDate: LocalDate,
        bars: List<FactorBar>,
    ): FactorRawOutcome {
        val usable = FactorBars.usable(bars, asOfDate)
        val asOf = FactorBars.asOfBar(usable, asOfDate)
            ?: return FactorRawOutcome(FactorCalculationStatus.NO_DATA)
        if (usable.size < requiredHistoryDays) {
            return FactorRawOutcome(FactorCalculationStatus.INSUFFICIENT_HISTORY)
        }
        if (asOf.volume < 0L) {
            return FactorRawOutcome(FactorCalculationStatus.INVALID_DATA)
        }
        val prior = usable.takeLast(requiredHistoryDays).dropLast(1)
        if (prior.any { it.volume < 0L }) {
            return FactorRawOutcome(FactorCalculationStatus.INVALID_DATA)
        }
        val average = prior
            .fold(FactorMath.ZERO) { acc, bar -> acc.add(BigDecimal(bar.volume)) }
            .divide(BigDecimal(priorDays), FactorMath.CONTEXT)
        if (average.compareTo(FactorMath.ZERO) == 0) {
            return FactorRawOutcome(FactorCalculationStatus.INVALID_DATA)
        }
        val ratio = BigDecimal(asOf.volume).divide(average, FactorMath.CONTEXT)
        return FactorRawOutcome(FactorCalculationStatus.SUCCESS, ratio)
    }
}
