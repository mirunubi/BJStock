package com.mirunubi.bjstock.feature.performance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirunubi.bjstock.core.analytics.DailyPerformancePoint
import com.mirunubi.bjstock.core.analytics.PerformanceMath
import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardTestDashboardScreen(
    onBack: () -> Unit,
    onOpenCompare: () -> Unit,
    viewModel: ForwardTestViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val summary = state.summary

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Forward Test") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    TextButton(onClick = onOpenCompare) { Text("Compare Runs") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Strategy Runs", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.runs.forEach { run ->
                    FilterChip(
                        selected = state.selectedRunId == run.id,
                        onClick = { viewModel.selectRun(run.id) },
                        label = { Text("${run.id}:${run.runName}") },
                    )
                }
            }

            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            if (summary == null) {
                Text("Select a strategy run")
                return@Column
            }

            when (summary.status) {
                PerformanceStatus.EMPTY -> {
                    Text("No performance data yet", style = MaterialTheme.typography.titleMedium)
                    Text("Initial Cash ${ForwardTestViewModel.formatWon(summary.initialCash)}")
                    Text("Run status ${summary.runStatus}")
                }
                PerformanceStatus.DATA_ERROR -> {
                    Text("DATA_ERROR", style = MaterialTheme.typography.titleMedium)
                    Text(summary.errorMessage ?: "Snapshot or trade integrity failure")
                }
                else -> {
                    SummarySection(summary)
                    EquityCurveSection(state.dailySeries)
                    MonthlySection(state.monthly)
                    TradeStatsSection(summary)
                    OpenPositionsSection(state.openPositions)
                    ExecutionsSection(state.recentExecutions)
                    PolicySection(state.policy)
                }
            }
        }
    }
}

@Composable
private fun SummarySection(summary: com.mirunubi.bjstock.core.analytics.RunPerformanceSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Summary", style = MaterialTheme.typography.titleMedium)
            Text("${summary.strategyName} ${summary.strategyVersionLabel}")
            Text("Status ${summary.runStatus} / Analytics ${summary.status}")
            Text(
                "Period ${summary.startDate ?: "?"} ~ ${summary.endDate ?: "?"} " +
                    "(${summary.tradingDays} trading days)",
            )
            Text("Initial Asset ${ForwardTestViewModel.formatWon(summary.initialCash)}")
            Text("Current Asset ${ForwardTestViewModel.formatWon(summary.latestTotalAsset)}")
            MetricLine("Cumulative Profit", ForwardTestViewModel.formatWon(summary.cumulativeProfit))
            MetricLine(
                "Cumulative Return",
                ForwardTestViewModel.formatPercent(summary.cumulativeReturn),
                summary.cumulativeReturn,
            )
            MetricLine(
                "MDD",
                ForwardTestViewModel.formatPercent(summary.maxDrawdown),
                summary.maxDrawdown,
            )
            MetricLine(
                "CAGR",
                summary.cagr?.let { ForwardTestViewModel.formatPercent(it) } ?: "N/A",
                summary.cagr,
            )
            Text("Closed Trades ${summary.closedTrades}")
            MetricLine("Win Rate", ForwardTestViewModel.formatPercent(summary.winRate), summary.winRate)
            Text("Open Positions ${summary.openPositions}")
            Text(
                "Signals BUY ${summary.buySignals} / SELL ${summary.sellSignals} / " +
                    "HOLD ${summary.holdSignals} / NO_ACTION ${summary.noActionSignals}",
            )
            Text(
                "Executions BUY ${summary.buyExecutions} / SELL ${summary.sellExecutions}",
            )
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String, rate: BigDecimal? = null) {
    val color = when {
        rate == null -> MaterialTheme.colorScheme.onSurface
        rate.signum() > 0 -> MaterialTheme.colorScheme.primary
        rate.signum() < 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text("$label $value", color = color)
}

@Composable
private fun EquityCurveSection(points: List<DailyPerformancePoint>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Equity Curve (Total Asset)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (points.isEmpty()) {
                Text("No snapshots")
            } else {
                val lineColor = MaterialTheme.colorScheme.primary
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                ) {
                    val minY = points.minOf { it.totalAsset }.toFloat()
                    val maxY = points.maxOf { it.totalAsset }.toFloat()
                    val range = (maxY - minY).takeIf { it > 0f } ?: 1f
                    val path = Path()
                    points.forEachIndexed { index, point ->
                        val x = if (points.size == 1) {
                            size.width / 2f
                        } else {
                            size.width * index / (points.size - 1).toFloat()
                        }
                        val y = size.height - ((point.totalAsset.toFloat() - minY) / range) * size.height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(width = 3f, cap = StrokeCap.Round),
                    )
                    // baseline markers
                    drawLine(
                        color = lineColor.copy(alpha = 0.2f),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1f,
                    )
                }
                Text(
                    "${points.first().date} → ${points.last().date}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "${PerformanceMath.formatWon(points.first().totalAsset)} → " +
                        PerformanceMath.formatWon(points.last().totalAsset),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun MonthlySection(monthly: List<com.mirunubi.bjstock.core.analytics.MonthlyPerformance>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Monthly Returns", style = MaterialTheme.typography.titleMedium)
            if (monthly.isEmpty()) {
                Text("No monthly data")
            } else {
                monthly.forEach { row ->
                    MetricLine(
                        label = "%04d-%02d".format(row.year, row.month),
                        value = ForwardTestViewModel.formatPercent(row.returnRate),
                        rate = row.returnRate,
                    )
                }
            }
        }
    }
}

@Composable
private fun TradeStatsSection(summary: com.mirunubi.bjstock.core.analytics.RunPerformanceSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Trade Statistics", style = MaterialTheme.typography.titleMedium)
            Text("Closed ${summary.closedTrades}")
            Text(
                "Wins ${summary.winningTrades} / Losses ${summary.losingTrades} / " +
                    "Breakeven ${summary.breakevenTrades}",
            )
            MetricLine("Win Rate", ForwardTestViewModel.formatPercent(summary.winRate), summary.winRate)
            MetricLine(
                "Average Trade Return",
                ForwardTestViewModel.formatPercent(summary.averageTradeReturn),
                summary.averageTradeReturn,
            )
            MetricLine(
                "Best Trade",
                ForwardTestViewModel.formatPercent(summary.bestTradeReturn),
                summary.bestTradeReturn,
            )
            MetricLine(
                "Worst Trade",
                ForwardTestViewModel.formatPercent(summary.worstTradeReturn),
                summary.worstTradeReturn,
            )
            Text(
                "Average Holding Days (calendar) " +
                    (summary.averageHoldingDays?.toPlainString() ?: "N/A"),
            )
            Text("Open Trades ${summary.openTrades}")
        }
    }
}

@Composable
private fun OpenPositionsSection(rows: List<com.mirunubi.bjstock.core.analytics.OpenPositionView>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Open Positions", style = MaterialTheme.typography.titleMedium)
            if (rows.isEmpty()) {
                Text("None")
            } else {
                rows.forEach { row ->
                    Text(
                        "${row.symbol} qty ${row.quantity} avg " +
                            PerformanceMath.formatWon(row.averagePrice),
                    )
                    row.latestClose?.let {
                        Text("  Latest stored close ${PerformanceMath.formatWon(it)}")
                    }
                    row.marketValue?.let {
                        Text("  Market value ${PerformanceMath.formatWon(it)}")
                    }
                    row.unrealizedPricePnl?.let {
                        Text(
                            "  Unrealized P/L (price basis, excludes commissions) " +
                                PerformanceMath.formatSignedWon(it),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionsSection(rows: List<com.mirunubi.bjstock.core.analytics.RecentExecutionView>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Recent Executions", style = MaterialTheme.typography.titleMedium)
            if (rows.isEmpty()) {
                Text("None")
            } else {
                rows.forEach { row ->
                    Text(
                        "${row.side} ${row.symbol} ${row.executionDate} " +
                            "qty ${row.quantity} @ ${PerformanceMath.formatWon(row.price)}",
                    )
                    Text(
                        "  commission ${PerformanceMath.formatWon(row.commission)} " +
                            "tax ${PerformanceMath.formatWon(row.tax)}",
                    )
                }
            }
        }
    }
}

@Composable
private fun PolicySection(policy: com.mirunubi.bjstock.core.analytics.TradingPolicyView?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Trading Policy", style = MaterialTheme.typography.titleMedium)
            if (policy == null) {
                Text("No policy snapshot")
            } else {
                Text("Policy Version ${policy.policyVersion}")
                Text(
                    "BUY Allocation " +
                        ForwardTestViewModel.formatPercent(policy.buyAllocationRate),
                )
                Text(
                    "Commission Assumption " +
                        ForwardTestViewModel.formatRateAsAssumption(policy.commissionRate),
                )
                Text(
                    "Sell Tax Assumption " +
                        ForwardTestViewModel.formatRateAsAssumption(policy.sellTaxRate),
                )
                Text("Slippage ${policy.slippageBps} bps")
                Text("Execution ${policy.executionPricePolicy}")
                Text("Simulation Assumption — rates are fixtures, not legal/broker quotes.")
            }
        }
    }
}
