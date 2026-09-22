package com.mirunubi.bjstock.core.factor

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactorNormalizerTest {
    private val ma20 = LinearNormalizer(SystemFactorCatalog.ma20Zero, SystemFactorCatalog.ma20Hundred)
    private val ma60 = LinearNormalizer(SystemFactorCatalog.ma60Zero, SystemFactorCatalog.ma60Hundred)
    private val momentum20 = LinearNormalizer(
        SystemFactorCatalog.momentum20Zero,
        SystemFactorCatalog.momentum20Hundred,
    )
    private val momentum60 = LinearNormalizer(
        SystemFactorCatalog.momentum60Zero,
        SystemFactorCatalog.momentum60Hundred,
    )
    private val volatility = LinearNormalizer(
        SystemFactorCatalog.volatilityZero,
        SystemFactorCatalog.volatilityHundred,
    )
    private val volume = PiecewiseLinearNormalizer(SystemFactorCatalog.volumeRatioPoints)

    @Test
    fun priceVsMa20_mapsMinMidMaxAndClamps() {
        assertScore(0, ma20.normalize(BigDecimal("-20")))
        assertScore(50, ma20.normalize(BigDecimal.ZERO))
        assertScore(100, ma20.normalize(BigDecimal("20")))
        assertScore(0, ma20.normalize(BigDecimal("-40")))
        assertScore(100, ma20.normalize(BigDecimal("40")))
    }

    @Test
    fun priceVsMa60_mapsMinMidMaxAndClamps() {
        assertScore(0, ma60.normalize(BigDecimal("-30")))
        assertScore(50, ma60.normalize(BigDecimal.ZERO))
        assertScore(100, ma60.normalize(BigDecimal("30")))
        assertScore(0, ma60.normalize(BigDecimal("-90")))
        assertScore(100, ma60.normalize(BigDecimal("90")))
    }

    @Test
    fun momentum20_mapsMinMidMaxAndClamps() {
        assertScore(0, momentum20.normalize(BigDecimal("-20")))
        assertScore(50, momentum20.normalize(BigDecimal.ZERO))
        assertScore(100, momentum20.normalize(BigDecimal("20")))
        assertScore(0, momentum20.normalize(BigDecimal("-50")))
        assertScore(100, momentum20.normalize(BigDecimal("50")))
    }

    @Test
    fun momentum60_mapsMinMidMaxAndClamps() {
        assertScore(0, momentum60.normalize(BigDecimal("-30")))
        assertScore(50, momentum60.normalize(BigDecimal.ZERO))
        assertScore(100, momentum60.normalize(BigDecimal("30")))
        assertScore(0, momentum60.normalize(BigDecimal("-80")))
        assertScore(100, momentum60.normalize(BigDecimal("80")))
    }

    @Test
    fun volatility_lowerIsBetterAndClamps() {
        assertScore(100, volatility.normalize(BigDecimal.ZERO))
        assertScore(50, volatility.normalize(BigDecimal("5")))
        assertScore(0, volatility.normalize(BigDecimal("10")))
        assertScore(100, volatility.normalize(BigDecimal("-1")))
        assertScore(0, volatility.normalize(BigDecimal("25")))
    }

    @Test
    fun volumeRatio_piecewiseAndClamp() {
        assertScore(20, volume.normalize(BigDecimal("0.5")))
        assertScore(50, volume.normalize(BigDecimal("1.0")))
        assertScore(80, volume.normalize(BigDecimal("2.0")))
        assertScore(100, volume.normalize(BigDecimal("3.0")))
        assertScore(20, volume.normalize(BigDecimal("0.1")))
        assertScore(100, volume.normalize(BigDecimal("9")))
        assertScore(65, volume.normalize(BigDecimal("1.5")))
    }

    @Test
    fun scoresStayInsideZeroToHundred() {
        val samples = listOf(
            ma20.normalize(BigDecimal("-1000")),
            ma20.normalize(BigDecimal("1000")),
            volatility.normalize(BigDecimal("1000")),
            volume.normalize(BigDecimal("-5")),
        )
        samples.forEach { score ->
            assertTrue(score >= BigDecimal.ZERO)
            assertTrue(score <= FactorMath.HUNDRED)
        }
    }

    private fun assertScore(expected: Int, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual.setScale(4, java.math.RoundingMode.HALF_UP)))
    }
}
