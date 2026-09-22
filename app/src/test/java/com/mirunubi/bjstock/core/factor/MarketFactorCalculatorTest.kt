package com.mirunubi.bjstock.core.factor

import com.mirunubi.bjstock.core.factor.calculator.MomentumCalculator
import com.mirunubi.bjstock.core.factor.calculator.PriceVsMaCalculator
import com.mirunubi.bjstock.core.factor.calculator.VolatilityCalculator
import com.mirunubi.bjstock.core.factor.calculator.VolumeRatioCalculator
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarketFactorCalculatorTest {
    private val start = LocalDate.of(2026, 1, 2)

    @Test
    fun ma20_matchesManualAverage() {
        val closes = List(19) { 100L } + 120L
        val bars = bars(closes)
        val result = PriceVsMaCalculator(FactorCodes.PRICE_VS_MA20, 20)
            .calculate(1, bars.last().tradeDate, bars)
        val ma = BigDecimal("101")
        val expected = FactorMath.percentFromRatio(BigDecimal("120"), ma).rawValue!!
        assertEquals(FactorCalculationStatus.SUCCESS, result.status)
        assertDecimal(expected, result.rawValue!!)
    }

    @Test
    fun ma60_matchesManualAverage() {
        val closes = List(59) { 200L } + 260L
        val bars = bars(closes)
        val result = PriceVsMaCalculator(FactorCodes.PRICE_VS_MA60, 60)
            .calculate(1, bars.last().tradeDate, bars)
        val ma = BigDecimal("201")
        val expected = FactorMath.percentFromRatio(BigDecimal("260"), ma).rawValue!!
        assertEquals(FactorCalculationStatus.SUCCESS, result.status)
        assertDecimal(expected, result.rawValue!!)
    }

    @Test
    fun momentum20_upAndDownPercents() {
        val up = bars(List(20) { 100L } + 110L)
        val down = bars(List(20) { 100L } + 90L)
        val calculator = MomentumCalculator(FactorCodes.MOMENTUM_20D, 20)
        val upRaw = calculator.calculate(1, up.last().tradeDate, up)
        val downRaw = calculator.calculate(1, down.last().tradeDate, down)
        assertDecimal(BigDecimal("10"), upRaw.rawValue!!)
        assertDecimal(BigDecimal("-10"), downRaw.rawValue!!)
    }

    @Test
    fun momentum60_upAndDownPercents() {
        val up = bars(List(60) { 100L } + 110L)
        val down = bars(List(60) { 100L } + 90L)
        val calculator = MomentumCalculator(FactorCodes.MOMENTUM_60D, 60)
        assertDecimal(BigDecimal("10"), calculator.calculate(1, up.last().tradeDate, up).rawValue!!)
        assertDecimal(BigDecimal("-10"), calculator.calculate(1, down.last().tradeDate, down).rawValue!!)
    }

    @Test
    fun volatility20_identicalReturnsAreZero() {
        val bars = bars(List(21) { 100L })
        val result = VolatilityCalculator(FactorCodes.VOLATILITY_20D, 20)
            .calculate(1, bars.last().tradeDate, bars)
        assertEquals(FactorCalculationStatus.SUCCESS, result.status)
        assertDecimal(BigDecimal.ZERO, result.rawValue!!)
    }

    @Test
    fun volatility_smallWindowMatchesPopulationStdev() {
        val bars = bars(listOf(100L, 110L, 100L))
        val result = VolatilityCalculator(FactorCodes.VOLATILITY_20D, 2)
            .calculate(1, bars.last().tradeDate, bars)
        val r1 = FactorMath.dailyReturn(BigDecimal("110"), BigDecimal("100"))!!
        val r2 = FactorMath.dailyReturn(BigDecimal("100"), BigDecimal("110"))!!
        val expected = FactorMath.populationStdev(listOf(r1, r2)).multiply(FactorMath.HUNDRED)
        assertDecimal(expected, result.rawValue!!)
    }

    @Test
    fun volumeRatio20_excludesCurrentVolumeFromAverage() {
        val volumes = List(20) { 100L } + 200L
        val bars = bars(List(21) { 10_000L }, volumes)
        val result = VolumeRatioCalculator(FactorCodes.VOLUME_RATIO_20D, 20)
            .calculate(1, bars.last().tradeDate, bars)
        assertDecimal(BigDecimal("2"), result.rawValue!!)
    }

    @Test
    fun volumeRatio_zeroAverageIsInvalid() {
        val volumes = List(20) { 0L } + 200L
        val bars = bars(List(21) { 10_000L }, volumes)
        val result = VolumeRatioCalculator(FactorCodes.VOLUME_RATIO_20D, 20)
            .calculate(1, bars.last().tradeDate, bars)
        assertEquals(FactorCalculationStatus.INVALID_DATA, result.status)
        assertNull(result.rawValue)
    }

    @Test
    fun futureBarsAreIgnoredByCalculators() {
        val history = bars(List(20) { 100L })
        val asOf = history.last().tradeDate
        val poisoned = history + FactorBar(asOf.plusDays(1), 1_000_000_000L, 1)
        val result = PriceVsMaCalculator(FactorCodes.PRICE_VS_MA20, 20)
            .calculate(1, asOf, poisoned)
        assertDecimal(BigDecimal.ZERO, result.rawValue!!)
    }

    @Test
    fun missingAsOfBarIsNoData() {
        val history = bars(List(20) { 100L })
        val saturday = history.last().tradeDate.plusDays(1)
        val result = PriceVsMaCalculator(FactorCodes.PRICE_VS_MA20, 20)
            .calculate(1, saturday, history)
        assertEquals(FactorCalculationStatus.NO_DATA, result.status)
        assertNull(result.rawValue)
    }

    @Test
    fun insufficientHistoryForMa60() {
        val history = bars(List(30) { 100L })
        val result = PriceVsMaCalculator(FactorCodes.PRICE_VS_MA60, 60)
            .calculate(1, history.last().tradeDate, history)
        assertEquals(FactorCalculationStatus.INSUFFICIENT_HISTORY, result.status)
        assertNull(result.rawValue)
    }

    private fun bars(closes: List<Long>, volumes: List<Long> = closes.map { 1_000L }): List<FactorBar> {
        return closes.mapIndexed { index, close ->
            FactorBar(
                tradeDate = start.plusDays(index.toLong()),
                closePrice = close,
                volume = volumes[index],
            )
        }
    }

    private fun assertDecimal(expected: BigDecimal, actual: BigDecimal) {
        assertEquals(
            0,
            expected.setScale(8, RoundingMode.HALF_UP).compareTo(actual.setScale(8, RoundingMode.HALF_UP)),
        )
    }
}
