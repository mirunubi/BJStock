package com.mirunubi.bjstock.core.paper

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Paper trading cost assumptions.
 *
 * SIMULATION ASSUMPTION — these rates are fixtures for forward-test realism.
 * They are not claimed to be current Korean brokerage fees or tax law.
 */
data class TradingCostPolicy(
    val commissionRate: BigDecimal,
    val sellTaxRate: BigDecimal,
) {
    init {
        require(commissionRate >= BigDecimal.ZERO) { "commissionRate must be >= 0" }
        require(sellTaxRate >= BigDecimal.ZERO) { "sellTaxRate must be >= 0" }
    }

    fun commission(grossWon: Long): Long {
        if (grossWon <= 0L || commissionRate.compareTo(BigDecimal.ZERO) == 0) return 0L
        return BigDecimal(grossWon)
            .multiply(commissionRate)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    fun sellTax(grossWon: Long): Long {
        if (grossWon <= 0L || sellTaxRate.compareTo(BigDecimal.ZERO) == 0) return 0L
        return BigDecimal(grossWon)
            .multiply(sellTaxRate)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    companion object {
        /** Zero-cost fixture for deterministic quantity math tests. */
        val ZERO = TradingCostPolicy(BigDecimal.ZERO, BigDecimal.ZERO)

        /**
         * Baseline simulation rates used by the app default.
         * SIMULATION ASSUMPTION only — not a legal/brokerage quote.
         */
        val BASELINE = TradingCostPolicy(
            commissionRate = BigDecimal("0.00015"),
            sellTaxRate = BigDecimal("0.0020"),
        )
    }
}

/**
 * Slippage policy placeholder. Phase 6 default is zero.
 */
data class SlippagePolicy(
    val buySlippageBps: BigDecimal = BigDecimal.ZERO,
    val sellSlippageBps: BigDecimal = BigDecimal.ZERO,
) {
    fun applyToBuy(openPriceWon: Long): Long {
        if (buySlippageBps.compareTo(BigDecimal.ZERO) == 0) return openPriceWon
        val factor = BigDecimal.ONE.add(
            buySlippageBps.divide(BigDecimal("10000"), 12, RoundingMode.HALF_UP),
        )
        return BigDecimal(openPriceWon).multiply(factor).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    fun applyToSell(openPriceWon: Long): Long {
        if (sellSlippageBps.compareTo(BigDecimal.ZERO) == 0) return openPriceWon
        val factor = BigDecimal.ONE.subtract(
            sellSlippageBps.divide(BigDecimal("10000"), 12, RoundingMode.HALF_UP),
        )
        return BigDecimal(openPriceWon).multiply(factor).setScale(0, RoundingMode.HALF_UP).longValueExact()
            .coerceAtLeast(0L)
    }

    companion object {
        val ZERO = SlippagePolicy()
    }
}

/**
 * MVP paper policy. Stored in code for Phase 6; not yet persisted per run.
 */
data class PaperTradingPolicy(
    val buyAllocationPercent: BigDecimal = BigDecimal("10"),
    val costPolicy: TradingCostPolicy = TradingCostPolicy.BASELINE,
    val slippagePolicy: SlippagePolicy = SlippagePolicy.ZERO,
) {
    init {
        require(buyAllocationPercent > BigDecimal.ZERO) { "buyAllocationPercent must be > 0" }
        require(buyAllocationPercent <= BigDecimal(100)) { "buyAllocationPercent must be <= 100" }
    }

    companion object {
        val DEFAULT = PaperTradingPolicy()
        val ZERO_COST = PaperTradingPolicy(costPolicy = TradingCostPolicy.ZERO)
    }
}

object PaperQuantityMath {
    /**
     * Largest whole-share quantity such that gross + commission <= cashBudget.
     */
    fun maxAffordableBuyQuantity(
        cashBudgetWon: Long,
        executionPriceWon: Long,
        costPolicy: TradingCostPolicy,
    ): Long {
        if (cashBudgetWon <= 0L || executionPriceWon <= 0L) return 0L
        var quantity = cashBudgetWon / executionPriceWon
        while (quantity > 0L) {
            val gross = executionPriceWon * quantity
            val commission = costPolicy.commission(gross)
            if (gross + commission <= cashBudgetWon) {
                return quantity
            }
            quantity--
        }
        return 0L
    }

    fun buyBudget(cashWon: Long, allocationPercent: BigDecimal): Long {
        return BigDecimal(cashWon)
            .multiply(allocationPercent)
            .divide(BigDecimal(100), 0, RoundingMode.DOWN)
            .longValueExact()
    }
}
