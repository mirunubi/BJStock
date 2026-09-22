package com.mirunubi.bjstock.core.paper

import com.mirunubi.bjstock.core.database.dao.PaperTradingPolicyDao
import com.mirunubi.bjstock.core.database.entity.PaperTradingPolicyEntity
import com.mirunubi.bjstock.core.database.mapping.NumericMapping
import com.mirunubi.bjstock.core.model.AdditionalBuyPolicy
import com.mirunubi.bjstock.core.model.ExecutionPricePolicy
import com.mirunubi.bjstock.core.model.SellPolicy
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Runtime view of a persisted [PaperTradingPolicyEntity].
 * Trading code must load this from the run snapshot — never from a live global default.
 */
data class RunPaperTradingPolicy(
    val policyVersion: String,
    val buyAllocationRate: BigDecimal,
    val costPolicy: TradingCostPolicy,
    val slippagePolicy: SlippagePolicy,
    val executionPricePolicy: ExecutionPricePolicy,
    val additionalBuyPolicy: AdditionalBuyPolicy,
    val sellPolicy: SellPolicy,
    val shortSellingAllowed: Boolean,
) {
    val buyAllocationPercent: BigDecimal
        get() = buyAllocationRate.multiply(BigDecimal(100))
}

class PaperTradingPolicyService(
    private val policyDao: PaperTradingPolicyDao,
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun findByRun(strategyRunId: Long): PaperTradingPolicyEntity? =
        policyDao.findByRun(strategyRunId)

    suspend fun requireByRun(strategyRunId: Long): PaperTradingPolicyEntity =
        policyDao.findByRun(strategyRunId)
            ?: throw MissingTradingPolicyException(strategyRunId)

    suspend fun requireRuntime(strategyRunId: Long): RunPaperTradingPolicy =
        toRuntime(requireByRun(strategyRunId))

    suspend fun createSnapshot(
        strategyRunId: Long,
        template: PaperTradingPolicy = PaperTradingPolicy.DEFAULT,
        policyVersion: String = PaperTradingPolicy.V1,
    ): Long {
        if (policyDao.countByRun(strategyRunId) > 0) {
            throw DuplicateTradingPolicyException(strategyRunId)
        }
        require(policyVersion.trim().isNotEmpty()) { "policy_version must not be blank" }
        require(!template.shortSellingAllowed) { "short selling is not allowed in Phase 6.1" }
        require(template.additionalBuyPolicy == AdditionalBuyPolicy.DISALLOW)
        require(template.sellPolicy == SellPolicy.FULL_POSITION)
        require(template.executionPricePolicy == ExecutionPricePolicy.NEXT_TRADING_DAY_OPEN)

        return policyDao.insert(
            PaperTradingPolicyEntity(
                strategyRunId = strategyRunId,
                policyVersion = policyVersion.trim(),
                buyAllocationRate = rateToStored(template.buyAllocationRate),
                commissionRate = rateToStored(template.costPolicy.commissionRate),
                sellTaxRate = rateToStored(template.costPolicy.sellTaxRate),
                slippageBps = template.slippageBps,
                executionPricePolicy = template.executionPricePolicy,
                additionalBuyPolicy = template.additionalBuyPolicy,
                sellPolicy = template.sellPolicy,
                shortSellingAllowed = false,
                createdAt = now(),
            ),
        )
    }

    fun toRuntime(entity: PaperTradingPolicyEntity): RunPaperTradingPolicy {
        require(!entity.shortSellingAllowed) { "short selling snapshot must be false" }
        val slippage = BigDecimal(entity.slippageBps)
        return RunPaperTradingPolicy(
            policyVersion = entity.policyVersion,
            buyAllocationRate = storedToRate(entity.buyAllocationRate),
            costPolicy = TradingCostPolicy(
                commissionRate = storedToRate(entity.commissionRate),
                sellTaxRate = storedToRate(entity.sellTaxRate),
            ),
            slippagePolicy = SlippagePolicy(
                buySlippageBps = slippage,
                sellSlippageBps = slippage,
            ),
            executionPricePolicy = entity.executionPricePolicy,
            additionalBuyPolicy = entity.additionalBuyPolicy,
            sellPolicy = entity.sellPolicy,
            shortSellingAllowed = false,
        )
    }

    companion object {
        fun rateToStored(rate: BigDecimal): Long =
            rate.multiply(BigDecimal(NumericMapping.WEIGHT_FACTOR))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()

        fun storedToRate(stored: Long): BigDecimal =
            BigDecimal(stored).divide(
                BigDecimal(NumericMapping.WEIGHT_FACTOR),
                NumericMapping.WEIGHT_SCALE,
                RoundingMode.HALF_UP,
            )
    }
}

class MissingTradingPolicyException(
    val strategyRunId: Long,
) : IllegalStateException("MISSING_TRADING_POLICY for strategy_run_id=$strategyRunId")

class DuplicateTradingPolicyException(
    val strategyRunId: Long,
) : IllegalStateException("paper trading policy already exists for strategy_run_id=$strategyRunId")
