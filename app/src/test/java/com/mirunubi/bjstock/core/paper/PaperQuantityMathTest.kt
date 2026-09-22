package com.mirunubi.bjstock.core.paper

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class PaperQuantityMathTest {
    @Test
    fun buyBudget_tenPercent() {
        assertEquals(10_000_000L, PaperQuantityMath.buyBudget(100_000_000L, BigDecimal("10")))
    }

    @Test
    fun maxAffordable_zeroCommission_isFloorDivision() {
        assertEquals(
            200L,
            PaperQuantityMath.maxAffordableBuyQuantity(
                cashBudgetWon = 10_000_000L,
                executionPriceWon = 50_000L,
                costPolicy = TradingCostPolicy.ZERO,
            ),
        )
    }

    @Test
    fun maxAffordable_withCommission_reducesQuantity() {
        val qty = PaperQuantityMath.maxAffordableBuyQuantity(
            cashBudgetWon = 10_000_000L,
            executionPriceWon = 50_000L,
            costPolicy = TradingCostPolicy(BigDecimal("0.01"), BigDecimal.ZERO),
        )
        assertEquals(198L, qty)
    }

    @Test
    fun drawdownRatio_matchesTwentyPercent() {
        val stored = CreateDailySnapshotUseCase.ratioStored(-22_000_000L, 110_000_000L)
        // -0.2 exactly
        assertEquals(-20_000_000L, stored)
    }
}
