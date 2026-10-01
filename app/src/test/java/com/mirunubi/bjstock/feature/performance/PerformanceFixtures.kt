package com.mirunubi.bjstock.feature.performance

import com.mirunubi.bjstock.core.analytics.DailyPerformancePoint
import com.mirunubi.bjstock.core.analytics.MonthlyPerformance
import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import com.mirunubi.bjstock.core.analytics.RunComparisonRow
import com.mirunubi.bjstock.core.analytics.RunPerformanceSummary
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.model.RunStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

object PerformanceFixtures {
    val START: LocalDate = LocalDate.of(2026, 9, 18)
    val LATEST: LocalDate = LocalDate.of(2026, 9, 30)

    fun run(id: Long, status: RunStatus, name: String = "모의투자 $id") = StrategyRunEntity(
        id = id,
        runName = name,
        strategyVersionId = 2,
        startDate = START,
        initialCash = 100_000_000,
        status = status,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    /** IN_PROGRESS / COMPLETE: measured values; EMPTY: no snapshot yet; DATA_ERROR: zeroed with a raw message. */
    fun summary(run: StrategyRunEntity, status: PerformanceStatus = PerformanceStatus.IN_PROGRESS): RunPerformanceSummary {
        val measured = status == PerformanceStatus.IN_PROGRESS || status == PerformanceStatus.COMPLETE
        return RunPerformanceSummary(
            strategyRunId = run.id,
            status = status,
            runStatus = run.status,
            strategyName = "기본 모멘텀 전략",
            strategyVersionLabel = "V2",
            startDate = run.startDate,
            endDate = if (measured) LATEST else null,
            tradingDays = if (measured) 9 else 0,
            initialCash = run.initialCash,
            latestCash = if (measured) 90_157_524 else null,
            latestMarketValue = if (measured) 10_174_000 else null,
            latestTotalAsset = if (measured) 100_331_524 else null,
            cumulativeProfit = if (measured) 331_524 else null,
            cumulativeReturn = if (measured) BigDecimal("0.003315240000") else null,
            maxDrawdown = if (measured) BigDecimal("-0.084200000000") else null,
            cagr = null,
            closedTrades = if (status == PerformanceStatus.DATA_ERROR) 0 else 4,
            winningTrades = if (status == PerformanceStatus.DATA_ERROR) 0 else 2,
            losingTrades = if (status == PerformanceStatus.DATA_ERROR) 0 else 1,
            breakevenTrades = if (status == PerformanceStatus.DATA_ERROR) 0 else 1,
            winRate = if (status == PerformanceStatus.DATA_ERROR) null else BigDecimal("0.666666666667"),
            averageTradeReturn = if (measured) BigDecimal("0.012345000000") else null,
            bestTradeReturn = if (measured) BigDecimal("0.051000000000") else null,
            worstTradeReturn = if (measured) BigDecimal("-0.020000000000") else null,
            averageHoldingDays = if (measured) BigDecimal("3.500000000000") else null,
            openPositions = 1,
            openTrades = 1,
            buySignals = 5,
            sellSignals = 4,
            holdSignals = 12,
            noActionSignals = 3,
            buyExecutions = 5,
            sellExecutions = 4,
            errorMessage = if (status == PerformanceStatus.DATA_ERROR) RAW_ERROR else null,
        )
    }

    const val RAW_ERROR = "cumulative_return mismatch on 2026-09-29 SQLiteException run_id=7"

    fun runData(id: Long, status: RunStatus, performance: PerformanceStatus = PerformanceStatus.IN_PROGRESS, name: String = "모의투자 $id") =
        run(id, status, name).let { PerformanceRunData(it, "기본 모멘텀 전략", "V2", summary(it, performance)) }

    fun point(date: LocalDate, total: Long, daily: Long = 0, drawdown: String = "0") = DailyPerformancePoint(
        date = date,
        cash = total,
        marketValue = 0,
        totalAsset = total,
        dailyProfit = daily,
        dailyReturn = BigDecimal("0.001"),
        cumulativeReturn = BigDecimal("0.0033"),
        drawdown = BigDecimal(drawdown),
    )

    val SERIES = listOf(
        point(LocalDate.of(2026, 9, 18), 100_000_000),
        point(LocalDate.of(2026, 9, 21), 99_000_000, daily = -1_000_000, drawdown = "-0.01"),
        point(LocalDate.of(2026, 9, 30), 100_331_524, daily = 1_331_524),
    )

    val MONTHLY = listOf(
        MonthlyPerformance(2026, 9, 100_000_000, 103_210_000, BigDecimal("0.0321")),
        MonthlyPerformance(2026, 10, 103_210_000, 102_095_332, BigDecimal("-0.0108")),
    )

    fun detail(data: PerformanceRunData, series: List<DailyPerformancePoint> = SERIES, monthly: List<MonthlyPerformance> = MONTHLY) =
        PerformanceDetailData(data, series, monthly)

    fun comparisonRow(id: Long, name: String, status: PerformanceStatus = PerformanceStatus.IN_PROGRESS, withPolicy: Boolean = true) =
        RunComparisonRow(
            runId = id,
            runName = name,
            strategyName = "기본 모멘텀 전략",
            strategyVersion = "V2",
            startDate = START,
            endDate = if (status == PerformanceStatus.EMPTY || status == PerformanceStatus.DATA_ERROR) null else LATEST,
            tradingDays = if (status == PerformanceStatus.IN_PROGRESS || status == PerformanceStatus.COMPLETE) 9 else 0,
            initialCash = 100_000_000,
            latestAsset = if (status == PerformanceStatus.IN_PROGRESS || status == PerformanceStatus.COMPLETE) 100_331_524 else null,
            cumulativeReturn = if (status == PerformanceStatus.IN_PROGRESS || status == PerformanceStatus.COMPLETE) BigDecimal("0.00331524") else null,
            maxDrawdown = if (status == PerformanceStatus.IN_PROGRESS || status == PerformanceStatus.COMPLETE) BigDecimal("-0.0842") else null,
            closedTrades = 4,
            winRate = BigDecimal("0.5"),
            policyVersion = if (withPolicy) "PAPER_POLICY_V1" else null,
            buyAllocationRate = if (withPolicy) BigDecimal("0.1") else null,
            commissionRate = if (withPolicy) BigDecimal("0.00015") else null,
            sellTaxRate = if (withPolicy) BigDecimal("0.002") else null,
            performanceStatus = status,
        )
}

/** Records every call. It has only read functions, like the real data source. */
class FakePerformanceDataSource(
    var runs: List<PerformanceRunData> = listOf(
        PerformanceFixtures.runData(1, RunStatus.COMPLETED, PerformanceStatus.COMPLETE, name = "지난 운영"),
        PerformanceFixtures.runData(2, RunStatus.DRAFT, PerformanceStatus.EMPTY, name = "go hbm"),
        PerformanceFixtures.runData(3, RunStatus.RUNNING, name = "모멘텀 운영"),
        PerformanceFixtures.runData(4, RunStatus.READY, PerformanceStatus.EMPTY, name = "반도체"),
    ),
) : PerformanceDataSource {
    val reads = mutableListOf<String>()
    val compared = mutableListOf<List<Long>>()
    var failDetail = false

    override suspend fun runs(): List<PerformanceRunData> {
        reads += "runs"
        return runs
    }

    override suspend fun detail(runId: Long): PerformanceDetailData? {
        reads += "detail:$runId"
        if (failDetail) error("SQLiteException at com.mirunubi.Foo")
        val data = runs.firstOrNull { it.run.id == runId } ?: return null
        val measured = data.summary.status == PerformanceStatus.IN_PROGRESS || data.summary.status == PerformanceStatus.COMPLETE
        return if (measured) PerformanceFixtures.detail(data) else PerformanceFixtures.detail(data, emptyList(), emptyList())
    }

    override suspend fun compare(runIds: List<Long>): List<RunComparisonRow> {
        reads += "compare"
        compared += runIds
        return runIds.sorted().map { id ->
            val data = runs.first { it.run.id == id }
            PerformanceFixtures.comparisonRow(id, data.run.runName, data.summary.status, withPolicy = data.run.status != RunStatus.DRAFT)
        }
    }
}
