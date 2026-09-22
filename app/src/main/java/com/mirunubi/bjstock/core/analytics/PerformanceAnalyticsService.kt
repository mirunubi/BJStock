package com.mirunubi.bjstock.core.analytics

import com.mirunubi.bjstock.core.database.entity.ExecutionEntity
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.PaperTradingPolicyEntity
import com.mirunubi.bjstock.core.database.entity.PortfolioDailySnapshotEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.core.paper.MarketExecutionTime
import com.mirunubi.bjstock.core.paper.PaperTradingPolicyService
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Read-only, deterministic performance analytics over Room history.
 * Never mutates trading tables. Never calls network.
 */
class PerformanceAnalyticsService(
    private val repository: PerformanceAnalyticsRepository,
) {
    suspend fun calculateSummary(runId: Long): RunPerformanceSummary {
        val run = repository.loadRun(runId)
            ?: return emptySummaryError(runId, "strategy run not found")
        val (strategyName, versionLabel) = repository.loadStrategyLabel(run)
        val snapshots = repository.loadSnapshots(runId)
        val integrity = validateSnapshots(run, snapshots)
        if (integrity != null) {
            return emptySummaryError(
                runId = runId,
                message = integrity,
                run = run,
                strategyName = strategyName,
                versionLabel = versionLabel,
            ).copy(status = PerformanceStatus.DATA_ERROR)
        }
        if (snapshots.isEmpty()) {
            val trades = try {
                pairClosedTrades(runId)
            } catch (e: TradeHistoryIntegrityError) {
                return emptySummaryError(
                    runId = runId,
                    message = e.message ?: "TradeHistoryIntegrityError",
                    run = run,
                    strategyName = strategyName,
                    versionLabel = versionLabel,
                ).copy(status = PerformanceStatus.DATA_ERROR)
            }
            val closed = trades.closed
            val wins = closed.count { it.netProfit > 0L }
            val losses = closed.count { it.netProfit < 0L }
            val flat = closed.count { it.netProfit == 0L }
            val winDenom = wins + losses
            val winRate = if (winDenom == 0) {
                null
            } else {
                PerformanceMath.ratio(wins.toLong(), winDenom.toLong())
            }
            val signals = signalCounts(runId)
            val fills = fillCounts(runId)
            return RunPerformanceSummary(
                strategyRunId = runId,
                status = PerformanceStatus.EMPTY,
                runStatus = run.status,
                strategyName = strategyName,
                strategyVersionLabel = versionLabel,
                startDate = run.startDate,
                endDate = null,
                tradingDays = 0,
                initialCash = run.initialCash,
                latestCash = null,
                latestMarketValue = null,
                latestTotalAsset = null,
                cumulativeProfit = null,
                cumulativeReturn = null,
                maxDrawdown = null,
                cagr = null,
                closedTrades = closed.size,
                winningTrades = wins,
                losingTrades = losses,
                breakevenTrades = flat,
                winRate = winRate,
                averageTradeReturn = null,
                bestTradeReturn = null,
                worstTradeReturn = null,
                averageHoldingDays = null,
                openPositions = repository.loadOpenPositions(runId).size,
                openTrades = trades.open.size,
                buySignals = signals.buy,
                sellSignals = signals.sell,
                holdSignals = signals.hold,
                noActionSignals = signals.noAction,
                buyExecutions = fills.buy,
                sellExecutions = fills.sell,
                errorMessage = null,
            )
        }

        val series = buildDailySeries(run.initialCash, snapshots)
        val latest = snapshots.last()
        val cumulativeProfit = PerformanceMath.profit(run.initialCash, latest.totalAsset)
        val cumulativeReturn = PerformanceMath.returnFromAssets(run.initialCash, latest.totalAsset)
        val mdd = PerformanceMath.maxDrawdown(series.map { it.drawdown })
        val elapsedDays = ChronoUnit.DAYS.between(run.startDate, latest.snapshotDate)
        val cagr = PerformanceMath.cagr(run.initialCash, latest.totalAsset, elapsedDays)

        val tradeResult = try {
            Result.success(pairClosedTrades(runId))
        } catch (e: TradeHistoryIntegrityError) {
            return emptySummaryError(
                runId = runId,
                message = e.message ?: "TradeHistoryIntegrityError",
                run = run,
                strategyName = strategyName,
                versionLabel = versionLabel,
            ).copy(status = PerformanceStatus.DATA_ERROR)
        }
        val trades = tradeResult.getOrThrow()
        val closed = trades.closed
        val wins = closed.count { it.netProfit > 0L }
        val losses = closed.count { it.netProfit < 0L }
        val flat = closed.count { it.netProfit == 0L }
        val winDenom = wins + losses
        val winRate = if (winDenom == 0) {
            null
        } else {
            PerformanceMath.ratio(wins.toLong(), winDenom.toLong())
        }
        val avgReturn = if (closed.isEmpty()) {
            null
        } else {
            closed.map { it.returnRate }
                .fold(BigDecimal.ZERO) { a, b -> a.add(b) }
                .divide(BigDecimal(closed.size), PerformanceMath.CALC_SCALE, PerformanceMath.ROUNDING)
        }
        val best = closed.maxOfOrNull { it.returnRate }
        val worst = closed.minOfOrNull { it.returnRate }
        val avgHold = if (closed.isEmpty()) {
            null
        } else {
            BigDecimal(closed.sumOf { it.holdingDays })
                .divide(BigDecimal(closed.size), PerformanceMath.CALC_SCALE, PerformanceMath.ROUNDING)
        }
        val signals = signalCounts(runId)
        val fills = fillCounts(runId)
        val status = when (run.status) {
            RunStatus.COMPLETED, RunStatus.CANCELLED -> PerformanceStatus.COMPLETE
            else -> PerformanceStatus.IN_PROGRESS
        }
        return RunPerformanceSummary(
            strategyRunId = runId,
            status = status,
            runStatus = run.status,
            strategyName = strategyName,
            strategyVersionLabel = versionLabel,
            startDate = run.startDate,
            endDate = latest.snapshotDate,
            tradingDays = snapshots.size,
            initialCash = run.initialCash,
            latestCash = latest.cash,
            latestMarketValue = latest.marketValue,
            latestTotalAsset = latest.totalAsset,
            cumulativeProfit = cumulativeProfit,
            cumulativeReturn = cumulativeReturn,
            maxDrawdown = mdd,
            cagr = cagr,
            closedTrades = closed.size,
            winningTrades = wins,
            losingTrades = losses,
            breakevenTrades = flat,
            winRate = winRate,
            averageTradeReturn = avgReturn,
            bestTradeReturn = best,
            worstTradeReturn = worst,
            averageHoldingDays = avgHold,
            openPositions = repository.loadOpenPositions(runId).size,
            openTrades = trades.open.size,
            buySignals = signals.buy,
            sellSignals = signals.sell,
            holdSignals = signals.hold,
            noActionSignals = signals.noAction,
            buyExecutions = fills.buy,
            sellExecutions = fills.sell,
        )
    }

    suspend fun calculateDailySeries(runId: Long): List<DailyPerformancePoint> {
        val run = repository.loadRun(runId) ?: return emptyList()
        val snapshots = repository.loadSnapshots(runId)
        validateSnapshots(run, snapshots)?.let { throw SnapshotDataError(it) }
        if (snapshots.isEmpty()) return emptyList()
        return buildDailySeries(run.initialCash, snapshots)
    }

    suspend fun calculateMonthlyReturns(runId: Long): List<MonthlyPerformance> {
        val run = repository.loadRun(runId) ?: return emptyList()
        val snapshots = repository.loadSnapshots(runId)
        validateSnapshots(run, snapshots)?.let { throw SnapshotDataError(it) }
        if (snapshots.isEmpty()) return emptyList()
        val byMonth = snapshots.groupBy { it.snapshotDate.year to it.snapshotDate.monthValue }
            .toSortedMap(compareBy({ it.first }, { it.second }))
        val monthEnds = byMonth.map { (ym, rows) -> ym to rows.maxBy { it.snapshotDate } }
        val result = mutableListOf<MonthlyPerformance>()
        var previousEnd: Long? = null
        for ((ym, endRow) in monthEnds) {
            val startAsset = previousEnd ?: run.initialCash
            val endAsset = endRow.totalAsset
            result += MonthlyPerformance(
                year = ym.first,
                month = ym.second,
                startAsset = startAsset,
                endAsset = endAsset,
                returnRate = PerformanceMath.returnFromAssets(startAsset, endAsset),
            )
            previousEnd = endAsset
        }
        return result
    }

    suspend fun calculateClosedTrades(runId: Long): TradePairingResult = pairClosedTrades(runId)

    suspend fun loadTradingPolicy(runId: Long): TradingPolicyView? {
        val entity = repository.loadPolicy(runId) ?: return null
        return toPolicyView(entity)
    }

    suspend fun loadOpenPositionViews(runId: Long): List<OpenPositionView> {
        return repository.loadOpenPositions(runId).map { position ->
            val symbol = repository.loadInstrumentSymbol(position.instrumentId)
            val close = repository.loadLatestClose(position.instrumentId)
            val marketValue = close?.let { it * position.quantity }
            val unrealized = marketValue?.let { it - position.quantity * position.averagePrice }
            OpenPositionView(
                instrumentId = position.instrumentId,
                symbol = symbol,
                quantity = position.quantity,
                averagePrice = position.averagePrice,
                latestClose = close,
                marketValue = marketValue,
                unrealizedPricePnl = unrealized,
            )
        }
    }

    suspend fun loadRecentExecutions(runId: Long, limit: Int = 20): List<RecentExecutionView> {
        val orders = repository.loadOrders(runId).associateBy { it.id }
        return repository.loadExecutions(runId)
            .sortedWith(
                compareByDescending<ExecutionEntity> { it.executedAt }
                    .thenByDescending { it.id },
            )
            .take(limit)
            .mapNotNull { execution ->
                val order = orders[execution.orderId] ?: return@mapNotNull null
                RecentExecutionView(
                    side = order.side.name,
                    symbol = repository.loadInstrumentSymbol(order.instrumentId),
                    executionDate = MarketExecutionTime.toTradeDate(execution.executedAt),
                    quantity = execution.quantity,
                    price = execution.executionPrice,
                    commission = execution.commission,
                    tax = execution.tax,
                )
            }
    }

    suspend fun compareRuns(runIds: List<Long>): List<RunComparisonRow> {
        return runIds.distinct().sorted().map { runId ->
            val summary = calculateSummary(runId)
            val policy = repository.loadPolicy(runId)
            val run = repository.loadRun(runId)
            RunComparisonRow(
                runId = runId,
                runName = run?.runName ?: "",
                strategyName = summary.strategyName,
                strategyVersion = summary.strategyVersionLabel,
                startDate = summary.startDate,
                endDate = summary.endDate,
                tradingDays = summary.tradingDays,
                initialCash = summary.initialCash,
                latestAsset = summary.latestTotalAsset,
                cumulativeReturn = summary.cumulativeReturn,
                maxDrawdown = summary.maxDrawdown,
                closedTrades = summary.closedTrades,
                winRate = summary.winRate,
                policyVersion = policy?.policyVersion,
                buyAllocationRate = policy?.let {
                    PaperTradingPolicyService.storedToRate(it.buyAllocationRate)
                },
                commissionRate = policy?.let {
                    PaperTradingPolicyService.storedToRate(it.commissionRate)
                },
                sellTaxRate = policy?.let {
                    PaperTradingPolicyService.storedToRate(it.sellTaxRate)
                },
                performanceStatus = summary.status,
            )
        }
    }

    fun buildDailySeries(
        initialCash: Long,
        snapshots: List<PortfolioDailySnapshotEntity>,
    ): List<DailyPerformancePoint> {
        val points = mutableListOf<DailyPerformancePoint>()
        var previousTotal: Long? = null
        var peak = initialCash
        for (snap in snapshots) {
            peak = maxOf(peak, snap.totalAsset)
            val dailyProfit = if (previousTotal == null) {
                PerformanceMath.profit(initialCash, snap.totalAsset)
            } else {
                PerformanceMath.profit(previousTotal, snap.totalAsset)
            }
            val dailyReturn = if (previousTotal == null) {
                PerformanceMath.returnFromAssets(initialCash, snap.totalAsset)
            } else {
                PerformanceMath.returnFromAssets(previousTotal, snap.totalAsset)
            }
            val cumulativeReturn = PerformanceMath.returnFromAssets(initialCash, snap.totalAsset)
            val drawdown = PerformanceMath.drawdown(snap.totalAsset, peak)
            points += DailyPerformancePoint(
                date = snap.snapshotDate,
                cash = snap.cash,
                marketValue = snap.marketValue,
                totalAsset = snap.totalAsset,
                dailyProfit = dailyProfit,
                dailyReturn = dailyReturn,
                cumulativeReturn = cumulativeReturn,
                drawdown = drawdown,
            )
            previousTotal = snap.totalAsset
        }
        return points
    }

    fun validateSnapshots(
        run: StrategyRunEntity,
        snapshots: List<PortfolioDailySnapshotEntity>,
    ): String? {
        var previousDate: LocalDate? = null
        for (snap in snapshots) {
            if (previousDate != null && !snap.snapshotDate.isAfter(previousDate)) {
                return "snapshot dates must be strictly ascending"
            }
            if (snap.totalAsset < 0L || snap.cash < 0L || snap.marketValue < 0L) {
                return "negative asset components"
            }
            if (snap.cash + snap.marketValue != snap.totalAsset) {
                return "cash + market_value != total_asset on ${snap.snapshotDate}"
            }
            val expectedCumProfit = snap.totalAsset - run.initialCash
            if (snap.cumulativeProfit != expectedCumProfit) {
                return "cumulative_profit mismatch on ${snap.snapshotDate}"
            }
            val expectedCumReturn = PerformanceMath.toStoredRatio(
                PerformanceMath.returnFromAssets(run.initialCash, snap.totalAsset),
            )
            if (snap.cumulativeReturn != expectedCumReturn) {
                return "cumulative_return mismatch on ${snap.snapshotDate}"
            }
            previousDate = snap.snapshotDate
        }
        return null
    }

    data class TradePairingResult(
        val closed: List<ClosedTrade>,
        val open: List<OpenTradeRow>,
    )

    private suspend fun pairClosedTrades(runId: Long): TradePairingResult {
        val orders = repository.loadOrders(runId).associateBy { it.id }
        val filled = repository.loadExecutions(runId).mapNotNull { execution ->
            val order = orders[execution.orderId] ?: return@mapNotNull null
            FilledLeg(order, execution)
        }
        val byInstrument = filled.groupBy { it.order.instrumentId }
        val closed = mutableListOf<ClosedTrade>()
        val open = mutableListOf<OpenTradeRow>()
        for ((instrumentId, legs) in byInstrument.toSortedMap()) {
            val sorted = legs.sortedWith(
                compareBy<FilledLeg> { it.execution.executedAt }
                    .thenBy { it.execution.id },
            )
            var pendingBuy: FilledLeg? = null
            for (leg in sorted) {
                when (leg.order.side) {
                    OrderSide.BUY -> {
                        if (pendingBuy != null) {
                            throw TradeHistoryIntegrityError(
                                "BUY followed by BUY for instrument $instrumentId",
                            )
                        }
                        pendingBuy = leg
                    }
                    OrderSide.SELL -> {
                        val buy = pendingBuy
                            ?: throw TradeHistoryIntegrityError(
                                "SELL without BUY for instrument $instrumentId",
                            )
                        if (leg.execution.quantity != buy.execution.quantity) {
                            throw TradeHistoryIntegrityError(
                                "BUY/SELL quantity mismatch for instrument $instrumentId",
                            )
                        }
                        closed += buildClosedTrade(instrumentId, buy, leg)
                        pendingBuy = null
                    }
                }
            }
            pendingBuy?.let { buy ->
                open += OpenTradeRow(
                    instrumentId = instrumentId,
                    symbol = repository.loadInstrumentSymbol(instrumentId),
                    buyDate = MarketExecutionTime.toTradeDate(buy.execution.executedAt),
                    quantity = buy.execution.quantity,
                    buyPrice = buy.execution.executionPrice,
                    buyCommission = buy.execution.commission,
                )
            }
        }
        return TradePairingResult(closed = closed, open = open)
    }

    private suspend fun buildClosedTrade(
        instrumentId: Long,
        buy: FilledLeg,
        sell: FilledLeg,
    ): ClosedTrade {
        val buyGross = buy.execution.executionPrice * buy.execution.quantity
        val sellGross = sell.execution.executionPrice * sell.execution.quantity
        val buyCost = buyGross + buy.execution.commission
        val sellNet = sellGross - sell.execution.commission - sell.execution.tax
        val netProfit = sellNet - buyCost
        val returnRate = if (buyCost == 0L) {
            BigDecimal.ZERO
        } else {
            PerformanceMath.ratio(netProfit, buyCost)
        }
        val buyDate = MarketExecutionTime.toTradeDate(buy.execution.executedAt)
        val sellDate = MarketExecutionTime.toTradeDate(sell.execution.executedAt)
        return ClosedTrade(
            instrumentId = instrumentId,
            symbol = repository.loadInstrumentSymbol(instrumentId),
            buyDate = buyDate,
            sellDate = sellDate,
            quantity = buy.execution.quantity,
            buyPrice = buy.execution.executionPrice,
            sellPrice = sell.execution.executionPrice,
            buyGross = buyGross,
            sellGross = sellGross,
            buyCommission = buy.execution.commission,
            sellCommission = sell.execution.commission,
            tax = sell.execution.tax,
            netProfit = netProfit,
            returnRate = returnRate,
            holdingDays = ChronoUnit.DAYS.between(buyDate, sellDate),
        )
    }

    private suspend fun signalCounts(runId: Long): SignalCounts {
        val rows = repository.loadEvaluations(runId)
        return SignalCounts(
            buy = rows.count { it.finalDecision == TradeDecision.BUY },
            sell = rows.count { it.finalDecision == TradeDecision.SELL },
            hold = rows.count { it.finalDecision == TradeDecision.HOLD },
            noAction = rows.count { it.finalDecision == TradeDecision.NO_ACTION },
        )
    }

    private suspend fun fillCounts(runId: Long): FillCounts {
        val orders = repository.loadOrders(runId).associateBy { it.id }
        var buy = 0
        var sell = 0
        for (execution in repository.loadExecutions(runId)) {
            when (orders[execution.orderId]?.side) {
                OrderSide.BUY -> buy++
                OrderSide.SELL -> sell++
                null -> Unit
            }
        }
        return FillCounts(buy, sell)
    }

    private fun toPolicyView(entity: PaperTradingPolicyEntity): TradingPolicyView =
        TradingPolicyView(
            policyVersion = entity.policyVersion,
            buyAllocationRate = PaperTradingPolicyService.storedToRate(entity.buyAllocationRate),
            commissionRate = PaperTradingPolicyService.storedToRate(entity.commissionRate),
            sellTaxRate = PaperTradingPolicyService.storedToRate(entity.sellTaxRate),
            slippageBps = entity.slippageBps,
            executionPricePolicy = entity.executionPricePolicy.name,
            additionalBuyPolicy = entity.additionalBuyPolicy.name,
            sellPolicy = entity.sellPolicy.name,
            shortSellingAllowed = entity.shortSellingAllowed,
        )

    private fun emptySummaryError(
        runId: Long,
        message: String,
        run: StrategyRunEntity? = null,
        strategyName: String = "",
        versionLabel: String = "",
    ): RunPerformanceSummary = RunPerformanceSummary(
        strategyRunId = runId,
        status = PerformanceStatus.DATA_ERROR,
        runStatus = run?.status ?: RunStatus.DRAFT,
        strategyName = strategyName,
        strategyVersionLabel = versionLabel,
        startDate = run?.startDate,
        endDate = null,
        tradingDays = 0,
        initialCash = run?.initialCash ?: 0L,
        latestCash = null,
        latestMarketValue = null,
        latestTotalAsset = null,
        cumulativeProfit = null,
        cumulativeReturn = null,
        maxDrawdown = null,
        cagr = null,
        closedTrades = 0,
        winningTrades = 0,
        losingTrades = 0,
        breakevenTrades = 0,
        winRate = null,
        averageTradeReturn = null,
        bestTradeReturn = null,
        worstTradeReturn = null,
        averageHoldingDays = null,
        openPositions = 0,
        openTrades = 0,
        buySignals = 0,
        sellSignals = 0,
        holdSignals = 0,
        noActionSignals = 0,
        buyExecutions = 0,
        sellExecutions = 0,
        errorMessage = message,
    )

    private data class FilledLeg(val order: OrderEntity, val execution: ExecutionEntity)
    private data class SignalCounts(val buy: Int, val sell: Int, val hold: Int, val noAction: Int)
    private data class FillCounts(val buy: Int, val sell: Int)

    companion object {
        /** Cross-check helper for snapshot drawdown identity tests. */
        fun expectedStoredDrawdown(totalAsset: Long, peak: Long): Long =
            PerformanceMath.toStoredRatio(PerformanceMath.drawdown(totalAsset, peak))
    }
}
