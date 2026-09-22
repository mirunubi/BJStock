package com.mirunubi.bjstock.core.analytics

import com.mirunubi.bjstock.core.model.RunStatus
import java.math.BigDecimal
import java.time.LocalDate

enum class PerformanceStatus {
    EMPTY,
    IN_PROGRESS,
    COMPLETE,
    DATA_ERROR,
}

data class RunPerformanceSummary(
    val strategyRunId: Long,
    val status: PerformanceStatus,
    val runStatus: RunStatus,
    val strategyName: String,
    val strategyVersionLabel: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val tradingDays: Int,
    val initialCash: Long,
    val latestCash: Long?,
    val latestMarketValue: Long?,
    val latestTotalAsset: Long?,
    val cumulativeProfit: Long?,
    val cumulativeReturn: BigDecimal?,
    val maxDrawdown: BigDecimal?,
    val cagr: BigDecimal?,
    val closedTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val breakevenTrades: Int,
    val winRate: BigDecimal?,
    val averageTradeReturn: BigDecimal?,
    val bestTradeReturn: BigDecimal?,
    val worstTradeReturn: BigDecimal?,
    val averageHoldingDays: BigDecimal?,
    val openPositions: Int,
    val openTrades: Int,
    val buySignals: Int,
    val sellSignals: Int,
    val holdSignals: Int,
    val noActionSignals: Int,
    val buyExecutions: Int,
    val sellExecutions: Int,
    val errorMessage: String? = null,
)

data class DailyPerformancePoint(
    val date: LocalDate,
    val cash: Long,
    val marketValue: Long,
    val totalAsset: Long,
    val dailyProfit: Long,
    val dailyReturn: BigDecimal,
    val cumulativeReturn: BigDecimal,
    val drawdown: BigDecimal,
)

data class MonthlyPerformance(
    val year: Int,
    val month: Int,
    val startAsset: Long,
    val endAsset: Long,
    val returnRate: BigDecimal,
)

data class ClosedTrade(
    val instrumentId: Long,
    val symbol: String,
    val buyDate: LocalDate,
    val sellDate: LocalDate,
    val quantity: Long,
    val buyPrice: Long,
    val sellPrice: Long,
    val buyGross: Long,
    val sellGross: Long,
    val buyCommission: Long,
    val sellCommission: Long,
    val tax: Long,
    val netProfit: Long,
    val returnRate: BigDecimal,
    val holdingDays: Long,
)

data class OpenTradeRow(
    val instrumentId: Long,
    val symbol: String,
    val buyDate: LocalDate,
    val quantity: Long,
    val buyPrice: Long,
    val buyCommission: Long,
)

data class RunComparisonRow(
    val runId: Long,
    val runName: String,
    val strategyName: String,
    val strategyVersion: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val tradingDays: Int,
    val initialCash: Long,
    val latestAsset: Long?,
    val cumulativeReturn: BigDecimal?,
    val maxDrawdown: BigDecimal?,
    val closedTrades: Int,
    val winRate: BigDecimal?,
    val policyVersion: String?,
    val buyAllocationRate: BigDecimal?,
    val commissionRate: BigDecimal?,
    val sellTaxRate: BigDecimal?,
    val performanceStatus: PerformanceStatus,
)

data class OpenPositionView(
    val instrumentId: Long,
    val symbol: String,
    val quantity: Long,
    val averagePrice: Long,
    val latestClose: Long?,
    val marketValue: Long?,
    /** Price-basis unrealized only (excludes commissions). */
    val unrealizedPricePnl: Long?,
)

data class RecentExecutionView(
    val side: String,
    val symbol: String,
    val executionDate: LocalDate,
    val quantity: Long,
    val price: Long,
    val commission: Long,
    val tax: Long,
)

data class TradingPolicyView(
    val policyVersion: String,
    val buyAllocationRate: BigDecimal,
    val commissionRate: BigDecimal,
    val sellTaxRate: BigDecimal,
    val slippageBps: Long,
    val executionPricePolicy: String,
    val additionalBuyPolicy: String,
    val sellPolicy: String,
    val shortSellingAllowed: Boolean,
)

class TradeHistoryIntegrityError(
    message: String,
) : IllegalStateException(message)

class SnapshotDataError(
    message: String,
) : IllegalStateException(message)
