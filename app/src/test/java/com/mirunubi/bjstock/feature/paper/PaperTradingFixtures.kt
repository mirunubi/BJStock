package com.mirunubi.bjstock.feature.paper

import com.mirunubi.bjstock.core.analytics.OpenPositionView
import com.mirunubi.bjstock.core.analytics.PerformanceStatus
import com.mirunubi.bjstock.core.analytics.RecentExecutionView
import com.mirunubi.bjstock.core.analytics.RunPerformanceSummary
import com.mirunubi.bjstock.core.analytics.TradingPolicyView
import com.mirunubi.bjstock.core.database.entity.ForwardOperationEntity
import com.mirunubi.bjstock.core.database.entity.ForwardTestCycleEntity
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.database.entity.OrderEntity
import com.mirunubi.bjstock.core.database.entity.StrategyRunEntity
import com.mirunubi.bjstock.core.database.entity.ThemeEntity
import com.mirunubi.bjstock.core.database.entity.TradeAuditLogEntity
import com.mirunubi.bjstock.core.forward.AutoScheduleStatus
import com.mirunubi.bjstock.core.forward.ForwardOperationOutcome
import com.mirunubi.bjstock.core.forward.ForwardOrchestratorResult
import com.mirunubi.bjstock.core.forward.WorkerDisposition
import com.mirunubi.bjstock.core.model.ForwardCycleStage
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.ForwardOperationTrigger
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.OrderStatus
import com.mirunubi.bjstock.core.model.OrderType
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.core.model.TradeAuditEventType
import com.mirunubi.bjstock.core.strategy.ActiveStrategyVersion
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.ZoneId

object PaperFixtures {
    val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    val START: LocalDate = LocalDate.of(2026, 9, 18)
    const val SAMSUNG_ID = 11L
    val SAMSUNG = InstrumentLabel(SAMSUNG_ID, "005930", "삼성전자")
    const val OPERATION_KEY = "auto:2026-10-01:0700:KST:attempt:1"
    const val WORK_ID = "69a16826-0000-4000-8000-000000000001"

    fun kst(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, SEOUL).toInstant()

    val NOW: Instant = kst(2026, 10, 1, 13, 0)

    fun run(
        id: Long,
        status: RunStatus,
        name: String = "모의투자 $id",
        initialCash: Long = 100_000_000,
        endDate: LocalDate? = null,
    ) = StrategyRunEntity(
        id = id,
        runName = name,
        strategyVersionId = 2,
        startDate = START,
        endDate = endDate,
        initialCash = initialCash,
        status = status,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    fun summary(
        run: StrategyRunEntity,
        totalAsset: Long? = null,
        status: PerformanceStatus = if (totalAsset == null) PerformanceStatus.EMPTY else PerformanceStatus.IN_PROGRESS,
    ) = RunPerformanceSummary(
        strategyRunId = run.id,
        status = status,
        runStatus = run.status,
        strategyName = "기본 모멘텀 전략",
        strategyVersionLabel = "V2",
        startDate = run.startDate,
        endDate = null,
        tradingDays = if (totalAsset == null) 0 else 8,
        initialCash = run.initialCash,
        latestCash = totalAsset?.let { 90_157_524 },
        latestMarketValue = totalAsset?.let { it - 90_157_524 },
        latestTotalAsset = totalAsset,
        cumulativeProfit = totalAsset?.minus(run.initialCash),
        cumulativeReturn = totalAsset?.let { BigDecimal("0.00147524") },
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
        holdSignals = 0,
        noActionSignals = 0,
        buyExecutions = 1,
        sellExecutions = 0,
    )

    fun runData(id: Long, status: RunStatus, totalAsset: Long? = null, name: String = "모의투자 $id"): PaperRunData {
        val run = run(id, status, name)
        return PaperRunData(run, "기본 모멘텀 전략", "V2", summary(run, totalAsset))
    }

    val POSITION = OpenPositionView(
        instrumentId = SAMSUNG_ID,
        symbol = "005930",
        quantity = 37,
        averagePrice = 266_000,
        latestClose = 270_000,
        marketValue = 9_990_000,
        unrealizedPricePnl = 148_000,
    )

    val BUY_EXECUTION = RecentExecutionView(
        side = "BUY",
        symbol = "005930",
        executionDate = LocalDate.of(2026, 9, 29),
        quantity = 37,
        price = 266_000,
        commission = 1_476,
        tax = 0,
    )

    val SELL_EXECUTION = RecentExecutionView(
        side = "SELL",
        symbol = "005930",
        executionDate = LocalDate.of(2026, 9, 30),
        quantity = 37,
        price = 270_000,
        commission = 1_498,
        tax = 19_980,
    )

    fun order(status: OrderStatus = OrderStatus.VIRTUAL_FILLED, side: OrderSide = OrderSide.BUY) = OrderEntity(
        id = 1,
        clientOrderId = "paper-order-1",
        strategyRunId = 3,
        instrumentId = SAMSUNG_ID,
        side = side,
        orderType = OrderType.MARKET,
        quantity = 37,
        status = status,
        createdAt = kst(2026, 9, 26, 18, 30),
    )

    val POLICY = TradingPolicyView(
        policyVersion = "v1",
        buyAllocationRate = BigDecimal("0.10"),
        commissionRate = BigDecimal("0.00015"),
        sellTaxRate = BigDecimal("0.0020"),
        slippageBps = 0,
        executionPricePolicy = "NEXT_TRADING_DAY_OPEN",
        additionalBuyPolicy = "DISALLOW",
        sellPolicy = "FULL_POSITION",
        shortSellingAllowed = false,
    )

    fun cycle(
        id: Long,
        date: LocalDate,
        status: ForwardCycleStatus,
        retryable: Boolean = false,
        errorCode: String? = null,
    ) = ForwardTestCycleEntity(
        id = id,
        strategyRunId = 3,
        marketDate = date,
        status = status,
        currentStage = if (status == ForwardCycleStatus.COMPLETE) ForwardCycleStage.COMPLETE else ForwardCycleStage.FACTORS,
        attemptCount = 1,
        errorCode = errorCode,
        errorMessage = "raw provider message must stay hidden",
        retryable = retryable,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    fun audit(type: TradeAuditEventType, reason: String? = null) = TradeAuditLogEntity(
        id = type.ordinal.toLong() + 1,
        strategyRunId = 3,
        instrumentId = SAMSUNG_ID,
        marketDate = LocalDate.of(2026, 9, 26),
        eventType = type,
        reasonText = reason,
        eventKey = "audit:${type.name}",
        createdAt = Instant.EPOCH,
    )

    fun detail(
        data: PaperRunData,
        universe: List<InstrumentLabel> = listOf(SAMSUNG),
        failedCycle: ForwardTestCycleEntity? = null,
        positions: List<OpenPositionView> = listOf(POSITION),
        policy: TradingPolicyView? = POLICY,
    ) = PaperRunDetailData(
        run = data,
        positions = positions,
        executions = listOf(BUY_EXECUTION),
        orders = listOf(order()),
        policy = policy,
        universe = universe,
        instruments = mapOf(SAMSUNG_ID to SAMSUNG),
        cycles = listOf(cycle(7, LocalDate.of(2026, 9, 30), ForwardCycleStatus.COMPLETE)),
        failedCycle = failedCycle,
        timeline = listOf(audit(TradeAuditEventType.ORDER_CREATED, "BUY score 72 >= 70")),
        themes = if (data.run.status == RunStatus.DRAFT) listOf(ThemeEntity(id = 5, name = "반도체")) else emptyList(),
    )

    fun autoStatus(
        enabled: Boolean = true,
        scheduledAt: Instant? = kst(2026, 10, 2, 7, 0),
        workState: String? = "ENQUEUED",
        failure: String? = null,
    ) = AutoScheduleStatus(
        autoEnabled = enabled,
        nextScheduleInstanceId = scheduledAt?.let { "auto:2026-10-02:0700:KST" },
        nextScheduledAt = scheduledAt,
        workId = scheduledAt?.let { WORK_ID },
        workState = workState,
        lastScheduleFailure = failure,
    )

    fun blockedOperation(id: Long, attempt: Int) = ForwardOperationEntity(
        id = id,
        operationKey = OPERATION_KEY.replace("attempt:1", "attempt:$attempt"),
        trigger = ForwardOperationTrigger.WORKER,
        workId = WORK_ID,
        workAttempt = attempt,
        throughDate = LocalDate.of(2026, 9, 30),
        status = ForwardOperationStatus.BLOCKED,
        startedAt = kst(2026, 10, 1, 12, 50 + attempt),
        finishedAt = kst(2026, 10, 1, 12, 50 + attempt),
        finalCode = "NETWORK_FAILURE",
        safeMessage = "Processed 0 of 2 run(s); skipped 0; cycles completed 0, failed 0",
        scheduleInstanceId = "auto:2026-10-01:0700:KST",
    )

    fun outcome(
        status: ForwardOperationStatus = ForwardOperationStatus.SUCCEEDED,
        finalCode: String? = null,
        display: ForwardOrchestratorResult = ForwardOrchestratorResult.Ok(listOf(LocalDate.of(2026, 9, 30))),
    ) = ForwardOperationOutcome(
        operationId = 4,
        status = status,
        finalCode = finalCode,
        display = display,
        disposition = WorkerDisposition.SUCCESS,
    )
}

/** Records every write; reads are served from the fields. Init / selection / refresh must leave [writes] empty. */
class FakePaperTradingDataSource : PaperTradingDataSource {
    val writes = mutableListOf<String>()
    val reads = mutableListOf<String>()
    var runList: List<PaperRunData> = listOf(
        PaperFixtures.runData(1, RunStatus.COMPLETED),
        PaperFixtures.runData(2, RunStatus.DRAFT, name = "go hbm"),
        PaperFixtures.runData(3, RunStatus.RUNNING, totalAsset = 100_147_524, name = "모멘텀 운영"),
    )
    val details = mutableMapOf<Long, PaperRunDetailData>()
    var autoStatus = PaperFixtures.autoStatus()
    var operations = listOf(
        PaperFixtures.blockedOperation(3, 3),
        PaperFixtures.blockedOperation(2, 2),
        PaperFixtures.blockedOperation(1, 1),
    )
    var versions = listOf(ActiveStrategyVersion(strategyVersionId = 2, strategyName = "기본 모멘텀 전략", versionNo = 2))
    var searchResults = listOf(InstrumentEntity(id = 12, market = "KRX", symbol = "000660", name = "SK하이닉스"))
    var failure: Exception? = null
    var outcome = PaperFixtures.outcome()

    override suspend fun runs(): List<PaperRunData> {
        reads += "runs"
        return runList
    }

    override suspend fun detail(runId: Long): PaperRunDetailData? {
        reads += "detail:$runId"
        return details[runId] ?: runList.firstOrNull { it.run.id == runId }?.let { PaperFixtures.detail(it) }
    }

    override suspend fun automation(): PaperAutomationData {
        reads += "automation"
        return PaperAutomationData(autoStatus, operations, PaperFixtures.NOW)
    }

    override suspend fun activeVersions(): List<ActiveStrategyVersion> {
        reads += "activeVersions"
        return versions
    }

    override suspend fun searchInstruments(query: String): List<InstrumentEntity> {
        reads += "search:$query"
        return searchResults
    }

    override fun today(): LocalDate = LocalDate.of(2026, 10, 1)

    override suspend fun createDraftRun(strategyVersionId: Long, runName: String, startDate: LocalDate, initialCashWon: Long): Long {
        write("createDraft:$strategyVersionId:$runName:$startDate:$initialCashWon")
        val id = runList.maxOf { it.run.id } + 1
        runList = runList + PaperFixtures.runData(id, RunStatus.DRAFT, name = runName)
        return id
    }

    override suspend fun addInstrument(runId: Long, instrumentId: Long) = write("addInstrument:$runId:$instrumentId")

    override suspend fun removeInstrument(runId: Long, instrumentId: Long) = write("removeInstrument:$runId:$instrumentId")

    override suspend fun addTheme(runId: Long, themeId: Long): Int {
        write("addTheme:$runId:$themeId")
        return 3
    }

    override suspend fun markReady(runId: Long) = write("markReady:$runId")

    override suspend fun setAutoEnabled(enabled: Boolean) {
        write("setAuto:$enabled")
        autoStatus = autoStatus.copy(autoEnabled = enabled)
    }

    override suspend fun runNow(): ForwardOperationOutcome {
        write("runNow")
        return outcome
    }

    override suspend fun retryFailedCycle(runId: Long, marketDate: LocalDate, cycleId: Long): ForwardOperationOutcome {
        write("retry:$runId:$marketDate:$cycleId")
        return outcome
    }

    private fun write(call: String) {
        writes += call
        failure?.let { throw it }
    }
}
