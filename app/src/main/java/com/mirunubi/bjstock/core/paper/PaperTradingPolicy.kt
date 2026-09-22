package com.mirunubi.bjstock.core.paper

import com.mirunubi.bjstock.core.database.mapping.NumericMapping
import com.mirunubi.bjstock.core.model.AdditionalBuyPolicy
import com.mirunubi.bjstock.core.model.ExecutionPricePolicy
import com.mirunubi.bjstock.core.model.SellPolicy
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
        val ZERO = TradingCostPolicy(BigDecimal.ZERO, BigDecimal.ZERO)

        /**
         * Baseline simulation rates used only as the template for new run snapshots.
         * SIMULATION ASSUMPTION only — not a legal/brokerage quote.
         */
        val BASELINE = TradingCostPolicy(
            commissionRate = BigDecimal("0.00015"),
            sellTaxRate = BigDecimal("0.0020"),
        )
    }
}

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
 * Template used only when creating a new strategy run's policy snapshot.
 * Live trading must read [RunPaperTradingPolicy] from the DB snapshot.
 */
data class PaperTradingPolicy(
    val buyAllocationRate: BigDecimal = BigDecimal("0.10"),
    val costPolicy: TradingCostPolicy = TradingCostPolicy.BASELINE,
    val slippageBps: Long = 0L,
    val executionPricePolicy: ExecutionPricePolicy = ExecutionPricePolicy.NEXT_TRADING_DAY_OPEN,
    val additionalBuyPolicy: AdditionalBuyPolicy = AdditionalBuyPolicy.DISALLOW,
    val sellPolicy: SellPolicy = SellPolicy.FULL_POSITION,
    val shortSellingAllowed: Boolean = false,
) {
    init {
        require(buyAllocationRate > BigDecimal.ZERO) { "buyAllocationRate must be > 0" }
        require(buyAllocationRate <= BigDecimal.ONE) { "buyAllocationRate must be <= 1" }
        require(slippageBps >= 0L) { "slippageBps must be >= 0" }
        require(!shortSellingAllowed) { "short selling is not allowed" }
    }

    val buyAllocationPercent: BigDecimal
        get() = buyAllocationRate.multiply(BigDecimal(100))

    companion object {
        const val V1 = "v1"

        val DEFAULT = PaperTradingPolicy()

        val ZERO_COST = PaperTradingPolicy(costPolicy = TradingCostPolicy.ZERO)
    }
}

object PaperQuantityMath {
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

    fun buyBudgetFromRate(cashWon: Long, allocationRate: BigDecimal): Long {
        return BigDecimal(cashWon)
            .multiply(allocationRate)
            .setScale(0, RoundingMode.DOWN)
            .longValueExact()
    }

    fun buyBudgetFromStoredRate(cashWon: Long, allocationRateStored: Long): Long {
        return BigDecimal(cashWon)
            .multiply(BigDecimal(allocationRateStored))
            .divide(BigDecimal(NumericMapping.WEIGHT_FACTOR), 0, RoundingMode.DOWN)
            .longValueExact()
    }
}
