package com.mirunubi.bjstock.feature.home

import com.mirunubi.bjstock.core.analytics.OpenPositionView
import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import com.mirunubi.bjstock.core.analytics.RunPerformanceSummary
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.StockEvaluationEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.forward.AutoScheduleStatus
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

object HomeFixtures {
    val AUTO_OFF = AutoScheduleStatus(
        autoEnabled = false,
        nextScheduleInstanceId = null,
        nextScheduledAt = null,
        workId = null,
        workState = null,
        lastScheduleFailure = null,
    )

    val AUTO_ON = AUTO_OFF.copy(
        autoEnabled = true,
        nextScheduleInstanceId = "auto:2026-10-01:0700:KST",
        nextScheduledAt = Instant.parse("2026-09-30T22:00:00Z"),
        workId = "work-1",
        workState = "ENQUEUED",
    )

    fun run(id: Long, status: RunStatus, name: String = "Run $id") = StrategyRunEntity(
        id = id,
        runName = name,
        strategyVersionId = 1,
        startDate = LocalDate.of(2026, 9, 1),
        initialCash = 100_000_000,
        status = status,
    )

    fun summary(
        runId: Long,
        status: PerformanceStatus = PerformanceStatus.IN_PROGRESS,
        totalAsset: Long? = 100_331_524,
        cumulativeReturn: BigDecimal? = BigDecimal("0.00331524"),
        errorMessage: String? = null,
    ) = RunPerformanceSummary(
        strategyRunId = runId,
        status = status,
        runStatus = RunStatus.RUNNING,
        strategyName = "Momentum",
        strategyVersionLabel = "V2",
        startDate = LocalDate.of(2026, 9, 1),
        endDate = null,
        tradingDays = 20,
        initialCash = 100_000_000,
        latestCash = 90_000_000,
        latestMarketValue = 10_331_524,
        latestTotalAsset = totalAsset,
        cumulativeProfit = totalAsset?.minus(100_000_000),
        cumulativeReturn = cumulativeReturn,
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
        openPositions = 1,
        openTrades = 1,
        buySignals = 1,
        sellSignals = 0,
        holdSignals = 3,
        noActionSignals = 0,
        buyExecutions = 1,
        sellExecutions = 0,
        errorMessage = errorMessage,
    )

    val SAMSUNG = InstrumentEntity(id = 11, market = "KRX", symbol = "005930", name = "삼성전자")

    fun evaluation(decision: TradeDecision = TradeDecision.HOLD) = StockEvaluationEntity(
        id = 7,
        strategyRunId = 3,
        instrumentId = SAMSUNG.id,
        evaluationDate = LocalDate.of(2026, 9, 30),
        quantScore = 563_200,
        finalScore = 563_200,
        quantDecision = decision,
        finalDecision = decision,
    )

    val POSITION = OpenPositionView(
        instrumentId = SAMSUNG.id,
        symbol = "005930",
        quantity = 37,
        averagePrice = 271_000,
        latestClose = 280_000,
        marketValue = 10_360_000,
        unrealizedPricePnl = 333_000,
    )

    fun runData(
        summary: RunPerformanceSummary = summary(3),
        positions: List<OpenPositionView> = listOf(POSITION),
        evaluation: StockEvaluationEntity? = evaluation(),
    ) = HomeRunData(
        run = run(3, RunStatus.RUNNING, name = "go hbm"),
        summary = summary,
        positions = positions,
        instrumentNames = mapOf(SAMSUNG.id to SAMSUNG.name),
        latestEvaluation = evaluation,
        latestEvaluationInstrument = evaluation?.let { SAMSUNG },
        sameDayEvaluationCount = if (evaluation == null) 0 else 1,
    )

    fun operation(
        status: ForwardOperationStatus = ForwardOperationStatus.SUCCEEDED,
        finalCode: String? = null,
    ) = ForwardOperationEntity(
        id = 5,
        operationKey = "worker:auto:2026-09-30:0700:KST:0",
        trigger = ForwardOperationTrigger.WORKER,
        throughDate = LocalDate.of(2026, 9, 29),
        status = status,
        startedAt = Instant.parse("2026-09-29T22:34:00Z"),
        finalCode = finalCode,
        scheduleInstanceId = "auto:2026-09-30:0700:KST",
    )

    fun snapshot(
        run: HomeRunData? = runData(),
        candidates: Int = if (run == null) 0 else 1,
        auto: AutoScheduleStatus = AUTO_OFF,
        operation: ForwardOperationEntity? = null,
        now: Instant = BEFORE_SLOT,
    ) = HomeSnapshot(run = run, candidateRunCount = candidates, auto = auto, latestOperation = operation, now = now)

    /** 2026-09-30 21:00 KST, before [AUTO_ON]'s 10-01 07:00 slot. */
    val BEFORE_SLOT: Instant = Instant.parse("2026-09-30T12:00:00Z")

    /** 2026-10-01 13:22 KST, after [AUTO_ON]'s 10-01 07:00 slot. */
    val AFTER_SLOT: Instant = Instant.parse("2026-10-01T04:22:00Z")
}
