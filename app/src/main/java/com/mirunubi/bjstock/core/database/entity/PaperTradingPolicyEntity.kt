package com.mirunubi.bjstock.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mirunubi.bjstock.core.model.AdditionalBuyPolicy
import com.mirunubi.bjstock.core.model.ExecutionPricePolicy
import com.mirunubi.bjstock.core.model.SellPolicy
import java.time.Instant

@Entity(
    tableName = "paper_trading_policies",
    foreignKeys = [
        ForeignKey(
            entity = StrategyRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["strategy_run_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["strategy_run_id"],
            unique = true,
            name = "uq_paper_trading_policies_strategy_run",
        ),
    ],
)
data class PaperTradingPolicyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "strategy_run_id")
    val strategyRunId: Long,
    @ColumnInfo(name = "policy_version")
    val policyVersion: String,
    /** Fraction 0..1 scaled by WEIGHT_FACTOR (e.g. 10% → 100_000). */
    @ColumnInfo(name = "buy_allocation_rate")
    val buyAllocationRate: Long,
    /** Fraction 0..1 scaled by WEIGHT_FACTOR. SIMULATION ASSUMPTION. */
    @ColumnInfo(name = "commission_rate")
    val commissionRate: Long,
    /** Fraction 0..1 scaled by WEIGHT_FACTOR. SIMULATION ASSUMPTION. */
    @ColumnInfo(name = "sell_tax_rate")
    val sellTaxRate: Long,
    @ColumnInfo(name = "slippage_bps")
    val slippageBps: Long,
    @ColumnInfo(name = "execution_price_policy")
    val executionPricePolicy: ExecutionPricePolicy,
    @ColumnInfo(name = "additional_buy_policy")
    val additionalBuyPolicy: AdditionalBuyPolicy,
    @ColumnInfo(name = "sell_policy")
    val sellPolicy: SellPolicy,
    @ColumnInfo(name = "short_selling_allowed")
    val shortSellingAllowed: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
