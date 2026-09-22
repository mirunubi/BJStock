package com.mirunubi.bjstock.core.analytics

import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceMathTest {
    @Test
    fun cumulativeReturn_positive() {
        assertEquals(BigDecimal("0.20"), PerformanceMath.returnFromAssets(100, 120).setScale(2))
        assertEquals(20L, PerformanceMath.profit(100, 120))
    }

    @Test
    fun cumulativeReturn_negative() {
        assertEquals(BigDecimal("-0.20"), PerformanceMath.returnFromAssets(100, 80).setScale(2))
    }

    @Test
    fun mdd_fromSeries() {
        val assets = listOf(100L, 120L, 108L, 90L, 126L)
        val drawdowns = runningDrawdowns(initialPeak = 100L, assets = assets)
        val mdd = PerformanceMath.maxDrawdown(drawdowns)
        assertEquals(BigDecimal("-0.25"), mdd.setScale(2))
        assertTrue(mdd.signum() <= 0)
    }

    @Test
    fun futurePeak_doesNotPoisonPastDrawdown() {
        val drawdowns = runningDrawdowns(100L, listOf(100L, 90L, 1_000L))
        assertEquals(BigDecimal("-0.10"), drawdowns[1].setScale(2))
        assertEquals(BigDecimal.ZERO.setScale(2), drawdowns[2].setScale(2))
    }

    @Test
    fun monthlyReturns_exact() {
        val octRet = PerformanceMath.returnFromAssets(100, 105)
        val novRet = PerformanceMath.returnFromAssets(105, 102)
        val decRet = PerformanceMath.returnFromAssets(102, 110)
        assertEquals(BigDecimal("0.05"), octRet.setScale(2))
        assertEquals(BigDecimal("-0.028571428571"), novRet.setScale(12))
        assertEquals(BigDecimal("0.078431372549"), decRet.setScale(12))
    }

    @Test
    fun winRate_excludesBreakeven() {
        val profits = listOf(10L, -5L, 0L, 4L)
        val wins = profits.count { it > 0 }
        val losses = profits.count { it < 0 }
        assertEquals(2, wins)
        assertEquals(1, losses)
        assertEquals(1, profits.count { it == 0L })
        assertEquals(
            BigDecimal("0.666666666667"),
            PerformanceMath.ratio(wins.toLong(), (wins + losses).toLong()).setScale(12),
        )
    }

    @Test
    fun tradeCost_netProfitAndReturn() {
        val buyGross = 50_000L * 10L
        val buyCommission = 75L
        val sellGross = 55_000L * 10L
        val sellCommission = 83L
        val tax = 1_100L
        val buyCost = buyGross + buyCommission
        val sellNet = sellGross - sellCommission - tax
        val net = sellNet - buyCost
        assertEquals(48_742L, net)
        assertEquals(0, PerformanceMath.ratio(net, buyCost).compareTo(BigDecimal("0.097469379593")))
    }

    @Test
    fun holdingDays_calendar() {
        assertEquals(
            10L,
            ChronoUnit.DAYS.between(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11)),
        )
    }

    @Test
    fun cagr_na_underOneYear() {
        assertNull(PerformanceMath.cagr(100, 120, 364))
        assertTrue(PerformanceMath.cagr(100, 120, 365) != null)
    }

    private fun runningDrawdowns(initialPeak: Long, assets: List<Long>): List<BigDecimal> {
        var peak = initialPeak
        return assets.map { total ->
            peak = maxOf(peak, total)
            PerformanceMath.drawdown(total, peak)
        }
    }
}
